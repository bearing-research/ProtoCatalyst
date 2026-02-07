package protocatalyst.ml.optimizer

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph.{ComputeGraph, GraphIO, NamedNode}
import protocatalyst.ml.optimizer.rules._
import protocatalyst.ml.types._

/** Tests for advanced optimizer rules added in Phase 7. */
class AdvancedRulesSuite extends munit.FunSuite:

  private val f32 = TensorDType.Float32

  private def tensorType(dtype: TensorDType, dims: Int*): TensorType =
    TensorType(dtype, dims.map(Dim(_)).toVector)

  private val vecF32 = tensorType(f32, 10)
  private val matF32 = tensorType(f32, 3, 4)

  private def mkGraph(
      name: String,
      inputs: Vector[(String, TensorType)],
      nodes: Vector[(String, TensorExpr, TensorType)]
  ): ComputeGraph =
    ComputeGraph(
      name = name,
      inputs = inputs.map((n, t) => GraphIO(n, t)),
      outputs = nodes.lastOption.map((n, _, t) => Vector(GraphIO(n, t))).getOrElse(Vector.empty),
      nodes = nodes.map((n, e, t) => NamedNode(n, e, t))
    )

  // ============================================================================
  // TransposeFusion
  // ============================================================================

  test("TransposeFusion: double 2D transpose → identity"):
    val x = TensorExpr.Input("x", matF32)
    val t1 = TensorExpr.Transpose(x, Vector(1, 0))
    val t2 = TensorExpr.Transpose(t1, Vector(1, 0))
    val relu = TensorExpr.Relu(t2)

    val graph = mkGraph(
      "test",
      Vector("x" -> matF32),
      Vector(("t1", t1, matF32), ("t2", t2, matF32), ("relu", relu, matF32))
    )
    val optimized = TransposeFusion(graph)

    optimized.nodeMap("relu").expr match
      case TensorExpr.Relu(input) => assert(input eq x, "Expected Relu(x) after double transpose")
      case other                  => fail(s"Expected Relu(x), got $other")

  test("TransposeFusion: 3D double transpose → identity"):
    val t3d = tensorType(f32, 2, 3, 4)
    val x = TensorExpr.Input("x", t3d)
    val t1 = TensorExpr.Transpose(x, Vector(2, 0, 1))
    val t2 = TensorExpr.Transpose(t1, Vector(1, 2, 0)) // inverse of [2,0,1]
    val relu = TensorExpr.Relu(t2)

    val graph = mkGraph(
      "test",
      Vector("x" -> t3d),
      Vector(("t1", t1, t3d), ("t2", t2, t3d), ("relu", relu, t3d))
    )
    val optimized = TransposeFusion(graph)

    optimized.nodeMap("relu").expr match
      case TensorExpr.Relu(input) =>
        assert(input eq x, "Expected Relu(x) after double 3D transpose")
      case other => fail(s"Expected Relu(x), got $other")

  test("TransposeFusion: non-inverse double transpose → composed"):
    val t3d = tensorType(f32, 2, 3, 4)
    val x = TensorExpr.Input("x", t3d)
    val t1 = TensorExpr.Transpose(x, Vector(1, 2, 0))
    val t2 = TensorExpr.Transpose(t1, Vector(1, 2, 0)) // NOT inverse — should compose
    val relu = TensorExpr.Relu(t2)

    val graph = mkGraph(
      "test",
      Vector("x" -> t3d),
      Vector(("t1", t1, t3d), ("t2", t2, t3d), ("relu", relu, t3d))
    )
    val optimized = TransposeFusion(graph)

    optimized.nodeMap("relu").expr match
      case TensorExpr.Relu(TensorExpr.Transpose(input, perm)) =>
        assert(input eq x, "Expected single transpose of x")
        assertEquals(perm, Vector(2, 0, 1)) // [1,2,0] composed with [1,2,0] = [2,0,1]
      case other => fail(s"Expected Relu(Transpose(x, [2,0,1])), got $other")

  test("TransposeFusion: MatMul left transpose → transA"):
    val x = TensorExpr.Input("x", tensorType(f32, 4, 3))
    val y = TensorExpr.Input("y", tensorType(f32, 4, 5))
    val xt = TensorExpr.Transpose(x, Vector(1, 0))
    val mm = TensorExpr.MatMul(xt, y, transA = false, transB = false)

    val graph = mkGraph(
      "test",
      Vector("x" -> tensorType(f32, 4, 3), "y" -> tensorType(f32, 4, 5)),
      Vector(("xt", xt, tensorType(f32, 3, 4)), ("mm", mm, tensorType(f32, 3, 5)))
    )
    val optimized = TransposeFusion(graph)

    optimized.nodeMap("mm").expr match
      case TensorExpr.MatMul(a, b, true, false) =>
        assert(a eq x)
        assert(b eq y)
      case other => fail(s"Expected MatMul(x, y, transA=true), got $other")

  test("TransposeFusion: MatMul right transpose → transB"):
    val x = TensorExpr.Input("x", tensorType(f32, 3, 4))
    val y = TensorExpr.Input("y", tensorType(f32, 5, 4))
    val yt = TensorExpr.Transpose(y, Vector(1, 0))
    val mm = TensorExpr.MatMul(x, yt, transA = false, transB = false)

    val graph = mkGraph(
      "test",
      Vector("x" -> tensorType(f32, 3, 4), "y" -> tensorType(f32, 5, 4)),
      Vector(("yt", yt, tensorType(f32, 4, 5)), ("mm", mm, tensorType(f32, 3, 5)))
    )
    val optimized = TransposeFusion(graph)

    optimized.nodeMap("mm").expr match
      case TensorExpr.MatMul(a, b, false, true) =>
        assert(a eq x)
        assert(b eq y)
      case other => fail(s"Expected MatMul(x, y, transB=true), got $other")

  test("TransposeFusion: does not fold 3D transpose into MatMul"):
    val t3d = tensorType(f32, 2, 3, 4)
    val x = TensorExpr.Input("x", t3d)
    val y = TensorExpr.Input("y", matF32)
    val xt = TensorExpr.Transpose(x, Vector(0, 2, 1))
    val mm = TensorExpr.MatMul(xt, y, transA = false, transB = false)

    val graph = mkGraph(
      "test",
      Vector("x" -> t3d, "y" -> matF32),
      Vector(("xt", xt, t3d), ("mm", mm, matF32))
    )
    val optimized = TransposeFusion(graph)

    optimized.nodeMap("mm").expr match
      case TensorExpr.MatMul(TensorExpr.Transpose(_, _), _, false, false) => ()
      case other => fail(s"Expected MatMul(Transpose(...), ..., false, false), got $other")

  // ============================================================================
  // AlgebraicSimplification
  // ============================================================================

  test("AlgebraicSimplification: double negation → identity"):
    val x = TensorExpr.Input("x", vecF32)
    val neg1 = TensorExpr.Neg(x)
    val neg2 = TensorExpr.Neg(neg1)

    val graph =
      mkGraph("test", Vector("x" -> vecF32), Vector(("neg1", neg1, vecF32), ("neg2", neg2, vecF32)))
    val optimized = AlgebraicSimplification(graph)

    optimized.nodeMap("neg2").expr match
      case input => assert(input eq x, "Expected x after double negation elimination")

  test("AlgebraicSimplification: idempotent Relu"):
    val x = TensorExpr.Input("x", vecF32)
    val r1 = TensorExpr.Relu(x)
    val r2 = TensorExpr.Relu(r1)

    val graph =
      mkGraph("test", Vector("x" -> vecF32), Vector(("r1", r1, vecF32), ("r2", r2, vecF32)))
    val optimized = AlgebraicSimplification(graph)

    optimized.nodeMap("r2").expr match
      case TensorExpr.Relu(input) => assert(input eq x, "Expected Relu(x)")
      case other                  => fail(s"Expected Relu(x), got $other")

  test("AlgebraicSimplification: idempotent Abs"):
    val x = TensorExpr.Input("x", vecF32)
    val a1 = TensorExpr.Abs(x)
    val a2 = TensorExpr.Abs(a1)

    val graph =
      mkGraph("test", Vector("x" -> vecF32), Vector(("a1", a1, vecF32), ("a2", a2, vecF32)))
    val optimized = AlgebraicSimplification(graph)

    optimized.nodeMap("a2").expr match
      case TensorExpr.Abs(input) => assert(input eq x, "Expected Abs(x)")
      case other                 => fail(s"Expected Abs(x), got $other")

  test("AlgebraicSimplification: Abs(Neg(x)) → Abs(x)"):
    val x = TensorExpr.Input("x", vecF32)
    val neg = TensorExpr.Neg(x)
    val abs = TensorExpr.Abs(neg)

    val graph =
      mkGraph("test", Vector("x" -> vecF32), Vector(("neg", neg, vecF32), ("abs", abs, vecF32)))
    val optimized = AlgebraicSimplification(graph)

    optimized.nodeMap("abs").expr match
      case TensorExpr.Abs(input) => assert(input eq x, "Expected Abs(x)")
      case other                 => fail(s"Expected Abs(x), got $other")

  test("AlgebraicSimplification: Relu(Abs(x)) → Abs(x)"):
    val x = TensorExpr.Input("x", vecF32)
    val abs = TensorExpr.Abs(x)
    val relu = TensorExpr.Relu(abs)

    val graph =
      mkGraph("test", Vector("x" -> vecF32), Vector(("abs", abs, vecF32), ("relu", relu, vecF32)))
    val optimized = AlgebraicSimplification(graph)

    optimized.nodeMap("relu").expr match
      case TensorExpr.Abs(input) => assert(input eq x, "Expected Abs(x)")
      case other                 => fail(s"Expected Abs(x), got $other")

  test("AlgebraicSimplification: Exp(Log(x)) → x"):
    val x = TensorExpr.Input("x", vecF32)
    val log = TensorExpr.Log(x)
    val exp = TensorExpr.Exp(log)

    val graph =
      mkGraph("test", Vector("x" -> vecF32), Vector(("log", log, vecF32), ("exp", exp, vecF32)))
    val optimized = AlgebraicSimplification(graph)

    optimized.nodeMap("exp").expr match
      case input => assert(input eq x, "Expected x after Exp(Log(x))")

  test("AlgebraicSimplification: Log(Exp(x)) → x"):
    val x = TensorExpr.Input("x", vecF32)
    val exp = TensorExpr.Exp(x)
    val log = TensorExpr.Log(exp)

    val graph =
      mkGraph("test", Vector("x" -> vecF32), Vector(("exp", exp, vecF32), ("log", log, vecF32)))
    val optimized = AlgebraicSimplification(graph)

    optimized.nodeMap("log").expr match
      case input => assert(input eq x, "Expected x after Log(Exp(x))")

  test("AlgebraicSimplification: Reshape chain → single Reshape"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 6))
    val r1 = TensorExpr.Reshape(x, Vector(3, 4))
    val r2 = TensorExpr.Reshape(r1, Vector(12))

    val graph = mkGraph(
      "test",
      Vector("x" -> tensorType(f32, 2, 6)),
      Vector(("r1", r1, tensorType(f32, 3, 4)), ("r2", r2, tensorType(f32, 12)))
    )
    val optimized = AlgebraicSimplification(graph)

    optimized.nodeMap("r2").expr match
      case TensorExpr.Reshape(input, Vector(12)) => assert(input eq x, "Expected Reshape(x, [12])")
      case other                                 => fail(s"Expected Reshape(x, [12]), got $other")

  test("AlgebraicSimplification: single Neg preserved"):
    val x = TensorExpr.Input("x", vecF32)
    val neg = TensorExpr.Neg(x)

    val graph = mkGraph("test", Vector("x" -> vecF32), Vector(("neg", neg, vecF32)))
    val optimized = AlgebraicSimplification(graph)

    optimized.nodeMap("neg").expr match
      case TensorExpr.Neg(input) => assert(input eq x, "Single Neg should be preserved")
      case other                 => fail(s"Expected Neg(x), got $other")

  // ============================================================================
  // CommonSubexprElimination
  // ============================================================================

  test("CSE: deduplicates identical Relu(x) expressions"):
    val x = TensorExpr.Input("x", vecF32)
    val relu1 = TensorExpr.Relu(x)
    val relu2 = TensorExpr.Relu(x)
    val add = TensorExpr.Add(relu1, relu2)

    assert(relu1 == relu2, "Precondition: structurally equal")
    assert(!(relu1 eq relu2), "Precondition: different instances")

    val graph = mkGraph(
      "test",
      Vector("x" -> vecF32),
      Vector(("relu1", relu1, vecF32), ("relu2", relu2, vecF32), ("add", add, vecF32))
    )
    val optimized = CommonSubexprElimination(graph)

    optimized.nodeMap("add").expr match
      case TensorExpr.Add(left, right) =>
        assert(left eq right, "Expected shared reference after CSE")
      case other => fail(s"Expected Add with shared references, got $other")

  test("CSE: deduplicates nested common subexpressions"):
    val x = TensorExpr.Input("x", vecF32)
    val neg1 = TensorExpr.Neg(x)
    val neg2 = TensorExpr.Neg(x)
    val relu1 = TensorExpr.Relu(neg1)
    val relu2 = TensorExpr.Relu(neg2)
    val add = TensorExpr.Add(relu1, relu2)

    val graph = mkGraph(
      "test",
      Vector("x" -> vecF32),
      Vector(
        ("neg1", neg1, vecF32),
        ("neg2", neg2, vecF32),
        ("relu1", relu1, vecF32),
        ("relu2", relu2, vecF32),
        ("add", add, vecF32)
      )
    )
    val optimized = CommonSubexprElimination(graph)

    optimized.nodeMap("add").expr match
      case TensorExpr.Add(left, right) =>
        assert(left eq right, "Expected shared reference after CSE")
        left match
          case TensorExpr.Relu(n) =>
            assert(n.isInstanceOf[TensorExpr.Neg], "Expected Relu(Neg(x))")
          case other => fail(s"Expected Relu, got $other")
      case other => fail(s"Expected Add, got $other")

  test("CSE: preserves distinct subexpressions"):
    val x = TensorExpr.Input("x", vecF32)
    val relu = TensorExpr.Relu(x)
    val sigmoid = TensorExpr.Sigmoid(x)
    val add = TensorExpr.Add(relu, sigmoid)

    val graph = mkGraph(
      "test",
      Vector("x" -> vecF32),
      Vector(("relu", relu, vecF32), ("sigmoid", sigmoid, vecF32), ("add", add, vecF32))
    )
    val optimized = CommonSubexprElimination(graph)

    optimized.nodeMap("add").expr match
      case TensorExpr.Add(left, right) =>
        assert(!(left eq right), "Different operations should not be deduplicated")
      case other => fail(s"Expected Add, got $other")

  // ============================================================================
  // BatchNormElimination
  // ============================================================================

  test("BatchNormElimination: removes BN after Conv in inference mode"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 3, 32, 32))
    val w = TensorExpr.Parameter("w", tensorType(f32, 16, 3, 3, 3), None)
    val conv = TensorExpr.Conv(x, w, None, Vector(3, 3), Vector(1, 1), Vector(0, 0), 1)

    val scale = TensorExpr.Parameter("scale", tensorType(f32, 16), None)
    val bias = TensorExpr.Parameter("bias", tensorType(f32, 16), None)
    val mean = TensorExpr.Parameter("mean", tensorType(f32, 16), None)
    val variance = TensorExpr.Parameter("var", tensorType(f32, 16), None)
    val bn = TensorExpr.BatchNorm(conv, scale, bias, mean, variance, 1e-5, 0.1, training = false)

    val graph = mkGraph(
      "test",
      Vector("x" -> tensorType(f32, 1, 3, 32, 32)),
      Vector(
        ("conv", conv, tensorType(f32, 1, 16, 30, 30)),
        ("bn", bn, tensorType(f32, 1, 16, 30, 30))
      )
    )
    val optimized = BatchNormElimination(graph)

    optimized.nodeMap("bn").expr match
      case c: TensorExpr.Conv => assert(c eq conv, "Expected Conv after BN elimination")
      case other              => fail(s"Expected Conv, got $other")

  test("BatchNormElimination: removes BN after Linear in inference mode"):
    val x = TensorExpr.Input("x", tensorType(f32, 16, 784))
    val w = TensorExpr.Parameter("w", tensorType(f32, 256, 784), None)
    val linear = TensorExpr.Linear(x, w, None)

    val scale = TensorExpr.Parameter("scale", tensorType(f32, 256), None)
    val bias = TensorExpr.Parameter("bias", tensorType(f32, 256), None)
    val mean = TensorExpr.Parameter("mean", tensorType(f32, 256), None)
    val variance = TensorExpr.Parameter("var", tensorType(f32, 256), None)
    val bn = TensorExpr.BatchNorm(linear, scale, bias, mean, variance, 1e-5, 0.1, training = false)

    val graph = mkGraph(
      "test",
      Vector("x" -> tensorType(f32, 16, 784)),
      Vector(
        ("linear", linear, tensorType(f32, 16, 256)),
        ("bn", bn, tensorType(f32, 16, 256))
      )
    )
    val optimized = BatchNormElimination(graph)

    optimized.nodeMap("bn").expr match
      case l: TensorExpr.Linear => assert(l eq linear)
      case other                => fail(s"Expected Linear, got $other")

  test("BatchNormElimination: keeps BN in training mode"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 3, 32, 32))
    val w = TensorExpr.Parameter("w", tensorType(f32, 16, 3, 3, 3), None)
    val conv = TensorExpr.Conv(x, w, None, Vector(3, 3), Vector(1, 1), Vector(0, 0), 1)

    val scale = TensorExpr.Parameter("scale", tensorType(f32, 16), None)
    val bias = TensorExpr.Parameter("bias", tensorType(f32, 16), None)
    val mean = TensorExpr.Parameter("mean", tensorType(f32, 16), None)
    val variance = TensorExpr.Parameter("var", tensorType(f32, 16), None)
    val bn = TensorExpr.BatchNorm(conv, scale, bias, mean, variance, 1e-5, 0.1, training = true)

    val graph = mkGraph(
      "test",
      Vector("x" -> tensorType(f32, 1, 3, 32, 32)),
      Vector(
        ("conv", conv, tensorType(f32, 1, 16, 30, 30)),
        ("bn", bn, tensorType(f32, 1, 16, 30, 30))
      )
    )
    val optimized = BatchNormElimination(graph)

    optimized.nodeMap("bn").expr match
      case _: TensorExpr.BatchNorm => ()
      case other => fail(s"Expected BatchNorm preserved in training mode, got $other")

  test("BatchNormElimination: keeps standalone BN (not after Conv/Linear)"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 16, 32, 32))
    val relu = TensorExpr.Relu(x)

    val scale = TensorExpr.Parameter("scale", tensorType(f32, 16), None)
    val bias = TensorExpr.Parameter("bias", tensorType(f32, 16), None)
    val mean = TensorExpr.Parameter("mean", tensorType(f32, 16), None)
    val variance = TensorExpr.Parameter("var", tensorType(f32, 16), None)
    val bn = TensorExpr.BatchNorm(relu, scale, bias, mean, variance, 1e-5, 0.1, training = false)

    val graph = mkGraph(
      "test",
      Vector("x" -> tensorType(f32, 1, 16, 32, 32)),
      Vector(
        ("relu", relu, tensorType(f32, 1, 16, 32, 32)),
        ("bn", bn, tensorType(f32, 1, 16, 32, 32))
      )
    )
    val optimized = BatchNormElimination(graph)

    optimized.nodeMap("bn").expr match
      case _: TensorExpr.BatchNorm => ()
      case other => fail(s"Expected BatchNorm preserved (not after Conv/Linear), got $other")

  // ============================================================================
  // Combined: integrated optimization tests
  // ============================================================================

  test("combined: TransposeFusion + AlgebraicSimplification in MLOptimizer"):
    val x = TensorExpr.Input("x", matF32)
    val t1 = TensorExpr.Transpose(x, Vector(1, 0))
    val t2 = TensorExpr.Transpose(t1, Vector(1, 0))
    val neg1 = TensorExpr.Neg(t2)
    val neg2 = TensorExpr.Neg(neg1)

    val graph = mkGraph(
      "test",
      Vector("x" -> matF32),
      Vector(
        ("t1", t1, matF32),
        ("t2", t2, matF32),
        ("neg1", neg1, matF32),
        ("neg2", neg2, matF32)
      )
    )
    val optimized = MLOptimizer.optimize(graph)

    optimized.nodeMap("neg2").expr match
      case input => assert(input eq x, s"Expected x after combined optimization, got $input")

  test("combined: CSE after algebraic simplification"):
    val x = TensorExpr.Input("x", vecF32)
    val relu1 = TensorExpr.Relu(x)
    val relu2 = TensorExpr.Relu(x)
    val add = TensorExpr.Add(relu1, relu2)

    val graph = mkGraph(
      "test",
      Vector("x" -> vecF32),
      Vector(("relu1", relu1, vecF32), ("relu2", relu2, vecF32), ("add", add, vecF32))
    )
    val optimized = MLOptimizer.optimize(graph)

    optimized.nodeMap("add").expr match
      case TensorExpr.Add(left, right) =>
        assert(left eq right, "CSE should deduplicate identical Relu(x)")
      case other => fail(s"Expected Add with shared refs, got $other")
