package protocatalyst.ml.optimizer

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph.{ComputeGraph, GraphIO, NamedNode}
import protocatalyst.ml.types._

class MLTreeTransformSuite extends munit.FunSuite:

  private val f32 = TensorDType.Float32

  private def tensorType(dtype: TensorDType, dims: Int*): TensorType =
    TensorType(dtype, dims.map(Dim(_)).toVector)

  private val vecF32 = tensorType(f32, 10)
  private val matF32 = tensorType(f32, 3, 4)

  // ============================================================================
  // transformExprUp
  // ============================================================================

  test("transformExprUp: leaf nodes are unchanged when no match"):
    val x = TensorExpr.Input("x", vecF32)
    val result = MLTreeTransform.transformExprUp(x) { case _ if false => ??? }
    assert(result.eq(x))

  test("transformExprUp: replaces matching node"):
    val x = TensorExpr.Input("x", vecF32)
    val y = TensorExpr.Input("y", vecF32)
    val relu = TensorExpr.Relu(x)
    val result = MLTreeTransform.transformExprUp(relu) { case TensorExpr.Relu(input) =>
      TensorExpr.Sigmoid(input)
    }
    result match
      case TensorExpr.Sigmoid(input) => assert(input.eq(x))
      case _                         => fail("Expected Sigmoid")

  test("transformExprUp: transforms children before parent"):
    val x = TensorExpr.Input("x", vecF32)
    val relu = TensorExpr.Relu(x)
    val neg = TensorExpr.Neg(relu)

    // Replace Relu with Sigmoid, then check Neg has the new child
    val result = MLTreeTransform.transformExprUp(neg) { case TensorExpr.Relu(i) =>
      TensorExpr.Sigmoid(i)
    }
    result match
      case TensorExpr.Neg(TensorExpr.Sigmoid(input)) => assert(input.eq(x))
      case _                                         => fail("Expected Neg(Sigmoid(x))")

  test("transformExprUp: identity-preserving (returns same instance when nothing changes)"):
    val x = TensorExpr.Input("x", vecF32)
    val add = TensorExpr.Add(x, x)
    val result = MLTreeTransform.transformExprUp(add) { case TensorExpr.Mul(_, _) =>
      ??? // never matches
    }
    assert(result.eq(add))

  // ============================================================================
  // DAG memoization
  // ============================================================================

  test("transformExprUp: preserves DAG sharing (shared node transformed once)"):
    val x = TensorExpr.Input("x", vecF32)
    val relu = TensorExpr.Relu(x)
    val add = TensorExpr.Add(relu, relu) // relu shared

    var transformCount = 0
    val result = MLTreeTransform.transformExprUp(add) { case r @ TensorExpr.Relu(_) =>
      transformCount += 1
      r
    }

    // Relu should be visited exactly once (memoized)
    assertEquals(transformCount, 1)
    // And the result should still share the same relu reference
    val TensorExpr.Add(l, r) = result: @unchecked
    assert(l.eq(r))

  test("transformExprUp: DAG replacement maintains sharing"):
    val x = TensorExpr.Input("x", vecF32)
    val relu = TensorExpr.Relu(x)
    val add = TensorExpr.Add(relu, relu)

    val result = MLTreeTransform.transformExprUp(add) { case TensorExpr.Relu(i) =>
      TensorExpr.Sigmoid(i)
    }

    // Both branches should point to the same Sigmoid instance
    val TensorExpr.Add(l, r) = result: @unchecked
    assert(l.eq(r))
    l match
      case TensorExpr.Sigmoid(_) => () // ok
      case _                     => fail("Expected Sigmoid")

  test("transformExprUp: complex DAG (residual pattern)"):
    val x = TensorExpr.Input("x", vecF32)
    val w = TensorExpr.Parameter("w", matF32, None)
    val linear = TensorExpr.Linear(x, w, None)
    val relu = TensorExpr.Relu(linear)
    val residual = TensorExpr.Add(relu, x) // x shared between linear and residual

    // Replace all Relu with Sigmoid
    val result = MLTreeTransform.transformExprUp(residual) { case TensorExpr.Relu(i) =>
      TensorExpr.Sigmoid(i)
    }

    val TensorExpr.Add(lhs, rhs) = result: @unchecked
    // rhs should be the same x
    assert(rhs.eq(x))
    // lhs should be Sigmoid(Linear(x, w, None))
    lhs match
      case TensorExpr.Sigmoid(TensorExpr.Linear(li, lw, _)) =>
        assert(li.eq(x)) // x shared
        assert(lw.eq(w))
      case _ => fail("Expected Sigmoid(Linear(...))")

  // ============================================================================
  // transformExprDown
  // ============================================================================

  test("transformExprDown: transforms parent before children"):
    val x = TensorExpr.Input("x", vecF32)
    val relu = TensorExpr.Relu(x)
    val neg = TensorExpr.Neg(relu)

    // Replace Neg with Add(child, child), then Relu stays
    val result = MLTreeTransform.transformExprDown(neg) { case TensorExpr.Neg(i) =>
      TensorExpr.Add(i, i)
    }
    result match
      case TensorExpr.Add(l, r) =>
        // Both should be the same relu instance
        assert(l.eq(r))
        assert(l.eq(relu))
      case _ => fail("Expected Add(relu, relu)")

  // ============================================================================
  // transformExprChildren
  // ============================================================================

  test("transformExprChildren: leaves have no children"):
    val x = TensorExpr.Input("x", vecF32)
    val result = MLTreeTransform.transformExprChildren(x)(_ => ???)
    assert(result.eq(x))

  test("transformExprChildren: binary op"):
    val a = TensorExpr.Input("a", vecF32)
    val b = TensorExpr.Input("b", vecF32)
    val add = TensorExpr.Add(a, b)

    val c = TensorExpr.Input("c", vecF32)
    val result = MLTreeTransform.transformExprChildren(add) {
      case e if e.eq(a) => c
      case e            => e
    }
    val TensorExpr.Add(l, r) = result: @unchecked
    assert(l.eq(c))
    assert(r.eq(b))

  test("transformExprChildren: vector inputs (TensorConcat)"):
    val a = TensorExpr.Input("a", vecF32)
    val b = TensorExpr.Input("b", vecF32)
    val concat = TensorExpr.TensorConcat(Vector(a, b), axis = 0)

    val c = TensorExpr.Input("c", vecF32)
    val result = MLTreeTransform.transformExprChildren(concat) {
      case e if e.eq(b) => c
      case e            => e
    }
    val TensorExpr.TensorConcat(inputs, _) = result: @unchecked
    assertEquals(inputs.size, 2)
    assert(inputs(0).eq(a))
    assert(inputs(1).eq(c))

  // ============================================================================
  // transformGraph
  // ============================================================================

  test("transformGraph: transforms all nodes in graph"):
    val x = TensorExpr.Input("x", vecF32)
    val relu = TensorExpr.Relu(x)
    val neg = TensorExpr.Neg(relu)

    val graph = ComputeGraph(
      name = "test",
      inputs = Vector(GraphIO("x", vecF32)),
      outputs = Vector(GraphIO("out", vecF32)),
      nodes = Vector(
        NamedNode("relu", relu, vecF32),
        NamedNode("neg", neg, vecF32)
      )
    )

    val result = MLTreeTransform.transformGraph(graph) { case TensorExpr.Relu(i) =>
      TensorExpr.Sigmoid(i)
    }

    // relu node should now be Sigmoid
    result.nodeMap("relu").expr match
      case TensorExpr.Sigmoid(_) => () // ok
      case _                     => fail("Expected Sigmoid")

    // neg node should contain the transformed child
    result.nodeMap("neg").expr match
      case TensorExpr.Neg(TensorExpr.Sigmoid(_)) => () // ok
      case _                                     => fail("Expected Neg(Sigmoid(...))")

  test("transformGraph: returns same instance when no changes"):
    val x = TensorExpr.Input("x", vecF32)
    val relu = TensorExpr.Relu(x)
    val graph = ComputeGraph(
      name = "test",
      inputs = Vector(GraphIO("x", vecF32)),
      outputs = Vector(GraphIO("out", vecF32)),
      nodes = Vector(NamedNode("relu", relu, vecF32))
    )

    val result = MLTreeTransform.transformGraph(graph) { case TensorExpr.Mul(_, _) =>
      ??? // never matches
    }
    assert(result.eq(graph))
