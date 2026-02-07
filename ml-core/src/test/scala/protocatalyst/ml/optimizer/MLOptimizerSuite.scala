package protocatalyst.ml.optimizer

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph.{ComputeGraph, GraphIO, NamedNode}
import protocatalyst.ml.types._

class MLOptimizerSuite extends munit.FunSuite:

  private val f32 = TensorDType.Float32

  private def tensorType(dtype: TensorDType, dims: Int*): TensorType =
    TensorType(dtype, dims.map(Dim(_)).toVector)

  private val vecF32 = tensorType(f32, 10)
  private val matF32 = tensorType(f32, 3, 4)

  // ============================================================================
  // DropoutElimination
  // ============================================================================

  test("DropoutElimination: removes dropout in inference mode"):
    val x = TensorExpr.Input("x", vecF32)
    val dropout = TensorExpr.Dropout(x, 0.5, training = false)
    val relu = TensorExpr.Relu(dropout)

    val graph = ComputeGraph(
      name = "test",
      inputs = Vector(GraphIO("x", vecF32)),
      outputs = Vector(GraphIO("out", vecF32)),
      nodes = Vector(
        NamedNode("dropout", dropout, vecF32),
        NamedNode("relu", relu, vecF32)
      )
    )

    val optimized = MLOptimizer.optimize(graph)

    // relu should now directly reference x (dropout eliminated)
    optimized.nodeMap("relu").expr match
      case TensorExpr.Relu(input) => assert(input.eq(x))
      case _                      => fail("Expected Relu(x) after dropout elimination")

  test("DropoutElimination: keeps dropout in training mode"):
    val x = TensorExpr.Input("x", vecF32)
    val dropout = TensorExpr.Dropout(x, 0.5, training = true)
    val relu = TensorExpr.Relu(dropout)

    val graph = ComputeGraph(
      name = "test",
      inputs = Vector(GraphIO("x", vecF32)),
      outputs = Vector(GraphIO("out", vecF32)),
      nodes = Vector(
        NamedNode("dropout", dropout, vecF32),
        NamedNode("relu", relu, vecF32)
      )
    )

    val optimized = MLOptimizer.optimize(graph)

    // relu should still reference dropout
    optimized.nodeMap("relu").expr match
      case TensorExpr.Relu(TensorExpr.Dropout(_, _, true)) => () // ok
      case _ => fail("Expected Relu(Dropout(x, 0.5, true)) — training dropout should be kept")

  // ============================================================================
  // IdentityElimination
  // ============================================================================

  test("IdentityElimination: removes identity transpose"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 3))
    val transpose = TensorExpr.Transpose(x, Vector(0, 1)) // identity perm
    val relu = TensorExpr.Relu(transpose)

    val graph = ComputeGraph(
      name = "test",
      inputs = Vector(GraphIO("x", tensorType(f32, 2, 3))),
      outputs = Vector(GraphIO("out", tensorType(f32, 2, 3))),
      nodes = Vector(
        NamedNode("transpose", transpose, tensorType(f32, 2, 3)),
        NamedNode("relu", relu, tensorType(f32, 2, 3))
      )
    )

    val optimized = MLOptimizer.optimize(graph)

    // relu should directly reference x
    optimized.nodeMap("relu").expr match
      case TensorExpr.Relu(input) => assert(input.eq(x))
      case _                      => fail("Expected Relu(x) after identity transpose elimination")

  test("IdentityElimination: keeps non-identity transpose"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 3))
    val transpose = TensorExpr.Transpose(x, Vector(1, 0)) // actual swap
    val relu = TensorExpr.Relu(transpose)

    val graph = ComputeGraph(
      name = "test",
      inputs = Vector(GraphIO("x", tensorType(f32, 2, 3))),
      outputs = Vector(GraphIO("out", tensorType(f32, 3, 2))),
      nodes = Vector(
        NamedNode("transpose", transpose, tensorType(f32, 3, 2)),
        NamedNode("relu", relu, tensorType(f32, 3, 2))
      )
    )

    val optimized = MLOptimizer.optimize(graph)

    // transpose should remain
    optimized.nodeMap("relu").expr match
      case TensorExpr.Relu(TensorExpr.Transpose(_, _)) => () // ok
      case _ => fail("Expected Relu(Transpose(x, [1,0])) — non-identity transpose should be kept")

  test("IdentityElimination: removes cast to same dtype"):
    val x = TensorExpr.Input("x", tensorType(f32, 10))
    val cast = TensorExpr.Cast(x, TensorDType.Float32) // same dtype
    val relu = TensorExpr.Relu(cast)

    val graph = ComputeGraph(
      name = "test",
      inputs = Vector(GraphIO("x", vecF32)),
      outputs = Vector(GraphIO("out", vecF32)),
      nodes = Vector(
        NamedNode("cast", cast, vecF32),
        NamedNode("relu", relu, vecF32)
      )
    )

    val optimized = MLOptimizer.optimize(graph)

    optimized.nodeMap("relu").expr match
      case TensorExpr.Relu(input) => assert(input.eq(x))
      case _                      => fail("Expected Relu(x) after identity cast elimination")

  // ============================================================================
  // MatMulAddFusion
  // ============================================================================

  test("MatMulAddFusion: fuses MatMul + Add into Gemm"):
    val a = TensorExpr.Input("a", tensorType(f32, 3, 4))
    val b = TensorExpr.Input("b", tensorType(f32, 4, 5))
    val c = TensorExpr.Input("c", tensorType(f32, 3, 5))
    val mm = TensorExpr.MatMul(a, b, transA = false, transB = false)
    val add = TensorExpr.Add(mm, c)

    val graph = ComputeGraph(
      name = "test",
      inputs = Vector(
        GraphIO("a", tensorType(f32, 3, 4)),
        GraphIO("b", tensorType(f32, 4, 5)),
        GraphIO("c", tensorType(f32, 3, 5))
      ),
      outputs = Vector(GraphIO("out", tensorType(f32, 3, 5))),
      nodes = Vector(
        NamedNode("mm", mm, tensorType(f32, 3, 5)),
        NamedNode("add", add, tensorType(f32, 3, 5))
      )
    )

    val optimized = MLOptimizer.optimize(graph)

    optimized.nodeMap("add").expr match
      case TensorExpr.Gemm(ga, gb, gc, alpha, beta, false, false) =>
        assert(ga.eq(a))
        assert(gb.eq(b))
        assertEquals(gc, Some(c))
        assertEquals(alpha, 1.0)
        assertEquals(beta, 1.0)
      case other => fail(s"Expected Gemm, got $other")

  test("MatMulAddFusion: commutative (Add(c, MatMul))"):
    val a = TensorExpr.Input("a", tensorType(f32, 3, 4))
    val b = TensorExpr.Input("b", tensorType(f32, 4, 5))
    val c = TensorExpr.Input("c", tensorType(f32, 3, 5))
    val mm = TensorExpr.MatMul(a, b, transA = false, transB = false)
    val add = TensorExpr.Add(c, mm) // c + mm (reversed order)

    val graph = ComputeGraph(
      name = "test",
      inputs = Vector(
        GraphIO("a", tensorType(f32, 3, 4)),
        GraphIO("b", tensorType(f32, 4, 5)),
        GraphIO("c", tensorType(f32, 3, 5))
      ),
      outputs = Vector(GraphIO("out", tensorType(f32, 3, 5))),
      nodes = Vector(
        NamedNode("mm", mm, tensorType(f32, 3, 5)),
        NamedNode("add", add, tensorType(f32, 3, 5))
      )
    )

    val optimized = MLOptimizer.optimize(graph)

    optimized.nodeMap("add").expr match
      case TensorExpr.Gemm(_, _, Some(_), _, _, _, _) => () // ok, fused
      case other                                      => fail(s"Expected Gemm, got $other")

  test("MatMulAddFusion: does not fuse when MatMul has transA/transB"):
    val a = TensorExpr.Input("a", tensorType(f32, 4, 3))
    val b = TensorExpr.Input("b", tensorType(f32, 4, 5))
    val c = TensorExpr.Input("c", tensorType(f32, 3, 5))
    val mm = TensorExpr.MatMul(a, b, transA = true, transB = false) // transA = true
    val add = TensorExpr.Add(mm, c)

    val graph = ComputeGraph(
      name = "test",
      inputs = Vector(
        GraphIO("a", tensorType(f32, 4, 3)),
        GraphIO("b", tensorType(f32, 4, 5)),
        GraphIO("c", tensorType(f32, 3, 5))
      ),
      outputs = Vector(GraphIO("out", tensorType(f32, 3, 5))),
      nodes = Vector(
        NamedNode("mm", mm, tensorType(f32, 3, 5)),
        NamedNode("add", add, tensorType(f32, 3, 5))
      )
    )

    val optimized = MLOptimizer.optimize(graph)

    // Should NOT be fused (transA = true)
    optimized.nodeMap("add").expr match
      case TensorExpr.Add(_, _) => () // ok, not fused
      case other                => fail(s"Expected Add (not fused), got $other")

  // ============================================================================
  // DeadNodeElimination
  // ============================================================================

  test("DeadNodeElimination: removes unreachable nodes"):
    val x = TensorExpr.Input("x", vecF32)
    val y = TensorExpr.Input("y", vecF32)
    val relu = TensorExpr.Relu(x)
    val deadNode = TensorExpr.Sigmoid(y) // not reachable from output

    val graph = ComputeGraph(
      name = "test",
      inputs = Vector(GraphIO("x", vecF32)),
      outputs = Vector(GraphIO("out", vecF32)),
      nodes = Vector(
        NamedNode("dead", deadNode, vecF32),
        NamedNode("relu", relu, vecF32)
      )
    )

    val optimized = MLOptimizer.optimize(graph)

    // dead node should be removed
    assertEquals(optimized.nodes.size, 1)
    assertEquals(optimized.nodes.head.name, "relu")

  test("DeadNodeElimination: keeps all reachable nodes"):
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

    val optimized = MLOptimizer.optimize(graph)

    // Both nodes are reachable from the output
    assertEquals(optimized.nodes.size, 2)

  // ============================================================================
  // Combined optimization
  // ============================================================================

  test("combined: dropout elimination + dead node elimination"):
    val x = TensorExpr.Input("x", vecF32)
    val dropout = TensorExpr.Dropout(x, 0.5, training = false)
    val relu = TensorExpr.Relu(dropout)

    val graph = ComputeGraph(
      name = "test",
      inputs = Vector(GraphIO("x", vecF32)),
      outputs = Vector(GraphIO("out", vecF32)),
      nodes = Vector(
        NamedNode("dropout", dropout, vecF32),
        NamedNode("relu", relu, vecF32)
      )
    )

    val optimized = MLOptimizer.optimize(graph)

    // After dropout elimination, the dropout node expr is replaced with x
    // Dead node elimination doesn't remove the NamedNode (the node still exists,
    // but its expr changed). However, the dropout expr is no longer reachable.
    optimized.nodeMap("relu").expr match
      case TensorExpr.Relu(input) => assert(input.eq(x))
      case _                      => fail("Expected Relu(x)")

  test("combined: full MLP inference optimization"):
    val x = TensorExpr.Input("x", tensorType(f32, 16, 784))
    val w = TensorExpr.Parameter("w", tensorType(f32, 256, 784), None)
    val b = TensorExpr.Parameter("b", tensorType(f32, 256), Some(Initializer.Zeros))
    val linear = TensorExpr.Linear(x, w, Some(b))
    val dropout = TensorExpr.Dropout(linear, 0.5, training = false) // should be eliminated
    val relu = TensorExpr.Relu(dropout)

    val graph = ComputeGraph(
      name = "mlp_inference",
      inputs = Vector(GraphIO("x", tensorType(f32, 16, 784))),
      outputs = Vector(GraphIO("out", tensorType(f32, 16, 256))),
      nodes = Vector(
        NamedNode("linear", linear, tensorType(f32, 16, 256)),
        NamedNode("dropout", dropout, tensorType(f32, 16, 256)),
        NamedNode("relu", relu, tensorType(f32, 16, 256))
      )
    )

    val optimized = MLOptimizer.optimize(graph)

    // relu should reference linear directly (dropout eliminated)
    optimized.nodeMap("relu").expr match
      case TensorExpr.Relu(TensorExpr.Linear(_, _, _)) => () // ok
      case other => fail(s"Expected Relu(Linear(...)), got $other")

  // ============================================================================
  // MLRuleExecutor
  // ============================================================================

  test("MLRuleExecutor: empty graph is a no-op"):
    val graph = ComputeGraph("empty", Vector.empty, Vector.empty, Vector.empty)
    val optimized = MLOptimizer.optimize(graph)
    assertEquals(optimized.nodes.size, 0)

  test("MLRuleExecutor: FixedPoint converges"):
    // Create a graph where IdentityElimination needs multiple passes
    val x = TensorExpr.Input("x", tensorType(f32, 3, 4))
    // Transpose(Transpose(x, [0,1]), [0,1]) — two identity transposes
    val t1 = TensorExpr.Transpose(x, Vector(0, 1))
    val t2 = TensorExpr.Transpose(t1, Vector(0, 1))
    val relu = TensorExpr.Relu(t2)

    val graph = ComputeGraph(
      name = "test",
      inputs = Vector(GraphIO("x", tensorType(f32, 3, 4))),
      outputs = Vector(GraphIO("out", tensorType(f32, 3, 4))),
      nodes = Vector(
        NamedNode("t1", t1, tensorType(f32, 3, 4)),
        NamedNode("t2", t2, tensorType(f32, 3, 4)),
        NamedNode("relu", relu, tensorType(f32, 3, 4))
      )
    )

    val optimized = MLOptimizer.optimize(graph)

    // Both identity transposes should be eliminated
    optimized.nodeMap("relu").expr match
      case TensorExpr.Relu(input) => assert(input.eq(x))
      case other                  => fail(s"Expected Relu(x), got $other")
