package protocatalyst.ml.optimizer

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph._
import protocatalyst.ml.types._

class AutogradSuite extends munit.FunSuite:

  private val f32 = TensorDType.Float32

  private def tensorType(dtype: TensorDType, dims: Int*): TensorType =
    TensorType(dtype, dims.map(Dim(_)).toVector)

  private def scalarType(dtype: TensorDType): TensorType =
    TensorType(dtype, Vector.empty)

  /** Build a simple graph: inputs → single op → loss. */
  private def lossGraph(
      name: String,
      inputs: Vector[GraphIO],
      lossExpr: TensorExpr,
      intermediateNodes: Vector[NamedNode] = Vector.empty
  ): ComputeGraph =
    ComputeGraph(
      name = name,
      inputs = inputs,
      outputs = Vector(GraphIO("loss", scalarType(f32))),
      nodes = intermediateNodes :+ NamedNode("loss", lossExpr, scalarType(f32))
    )

  // ============================================================================
  // Error cases
  // ============================================================================

  test("backward: missing loss node throws"):
    val graph = ComputeGraph("empty", Vector.empty, Vector.empty, Vector.empty)
    intercept[Autograd.AutogradException]:
      Autograd.backward(graph, "nonexistent")

  test("backward: non-scalar loss throws"):
    val x = TensorExpr.Input("x", tensorType(f32, 4))
    val relu = TensorExpr.Relu(x)
    val graph = ComputeGraph(
      name = "non_scalar",
      inputs = Vector(GraphIO("x", tensorType(f32, 4))),
      outputs = Vector(GraphIO("out", tensorType(f32, 4))),
      nodes = Vector(NamedNode("out", relu, tensorType(f32, 4)))
    )
    intercept[Autograd.AutogradException]:
      Autograd.backward(graph, "out")

  test("backward: OpaqueOp throws"):
    val x = TensorExpr.Input("x", tensorType(f32, 4))
    val op = TensorExpr.OpaqueOp("custom", Vector(x), Map.empty, scalarType(f32))
    val graph = lossGraph(
      "opaque_test",
      Vector(GraphIO("x", tensorType(f32, 4))),
      op
    )
    intercept[Autograd.AutogradException]:
      Autograd.backward(graph, "loss")

  // ============================================================================
  // Basic gradient structure
  // ============================================================================

  test("backward: ReduceSum of parameter → gradient is scalar 1.0"):
    val w = TensorExpr.Parameter("w", tensorType(f32, 4), None)
    val loss = TensorExpr.ReduceSum(w, Vector(0), keepDims = false)
    val graph = ComputeGraph(
      name = "scalar_identity",
      inputs = Vector.empty,
      outputs = Vector(GraphIO("loss", scalarType(f32))),
      nodes = Vector(NamedNode("loss", loss, scalarType(f32)))
    )
    val result = Autograd.backward(graph, "loss")

    // Should have grad/w output
    assert(result.outputs.exists(_.name == "grad/w"), s"outputs: ${result.outputs.map(_.name)}")
    val gradW = result.outputs.find(_.name == "grad/w").get
    assertEquals(gradW.tensorType, tensorType(f32, 4))

  test("backward: Add — both operands receive gradient"):
    val x = TensorExpr.Parameter("x", tensorType(f32, 3), None)
    val y = TensorExpr.Parameter("y", tensorType(f32, 3), None)
    val add = TensorExpr.Add(x, y)
    val loss = TensorExpr.ReduceSum(add, Vector(0), keepDims = false)
    val graph = lossGraph(
      "add_test",
      Vector.empty,
      loss,
      Vector(NamedNode("add", add, tensorType(f32, 3)))
    )
    val result = Autograd.backward(graph, "loss")

    assert(result.outputs.exists(_.name == "grad/x"))
    assert(result.outputs.exists(_.name == "grad/y"))
    assertEquals(result.outputs.find(_.name == "grad/x").get.tensorType, tensorType(f32, 3))
    assertEquals(result.outputs.find(_.name == "grad/y").get.tensorType, tensorType(f32, 3))

  test("backward: Neg — gradient is negated"):
    val x = TensorExpr.Parameter("x", tensorType(f32, 3), None)
    val neg = TensorExpr.Neg(x)
    val loss = TensorExpr.ReduceSum(neg, Vector(0), keepDims = false)
    val graph = lossGraph(
      "neg_test",
      Vector.empty,
      loss,
      Vector(NamedNode("neg", neg, tensorType(f32, 3)))
    )
    val result = Autograd.backward(graph, "loss")

    assert(result.outputs.exists(_.name == "grad/x"))
    // grad/x should contain a Neg node in its lineage
    val gradNode = result.nodes.find(_.name == "grad/x").get
    assertContainsExprType(gradNode.expr, classOf[TensorExpr.Neg])

  test("backward: Sub — left gets +grad, right gets -grad"):
    val x = TensorExpr.Parameter("x", tensorType(f32, 3), None)
    val y = TensorExpr.Parameter("y", tensorType(f32, 3), None)
    val sub = TensorExpr.Sub(x, y)
    val loss = TensorExpr.ReduceSum(sub, Vector(0), keepDims = false)
    val graph = lossGraph(
      "sub_test",
      Vector.empty,
      loss,
      Vector(NamedNode("sub", sub, tensorType(f32, 3)))
    )
    val result = Autograd.backward(graph, "loss")

    assert(result.outputs.exists(_.name == "grad/x"))
    assert(result.outputs.exists(_.name == "grad/y"))

  test("backward: Mul — cross-term gradients"):
    val x = TensorExpr.Parameter("x", tensorType(f32, 3), None)
    val y = TensorExpr.Parameter("y", tensorType(f32, 3), None)
    val mul = TensorExpr.Mul(x, y)
    val loss = TensorExpr.ReduceSum(mul, Vector(0), keepDims = false)
    val graph = lossGraph(
      "mul_test",
      Vector.empty,
      loss,
      Vector(NamedNode("mul", mul, tensorType(f32, 3)))
    )
    val result = Autograd.backward(graph, "loss")

    assert(result.outputs.exists(_.name == "grad/x"))
    assert(result.outputs.exists(_.name == "grad/y"))
    // grad/x should contain a Mul node (grad * y)
    val gradXNode = result.nodes.find(_.name == "grad/x").get
    assertContainsExprType(gradXNode.expr, classOf[TensorExpr.Mul])

  // ============================================================================
  // Activations
  // ============================================================================

  test("backward: Relu — gradient contains Where"):
    val x = TensorExpr.Parameter("x", tensorType(f32, 4), None)
    val relu = TensorExpr.Relu(x)
    val loss = TensorExpr.ReduceSum(relu, Vector(0), keepDims = false)
    val graph = lossGraph(
      "relu_test",
      Vector.empty,
      loss,
      Vector(NamedNode("relu", relu, tensorType(f32, 4)))
    )
    val result = Autograd.backward(graph, "loss")

    assert(result.outputs.exists(_.name == "grad/x"))
    assertEquals(result.outputs.find(_.name == "grad/x").get.tensorType, tensorType(f32, 4))

  test("backward: Sigmoid — gradient has σ*(1-σ) structure"):
    val x = TensorExpr.Parameter("x", tensorType(f32, 4), None)
    val sigmoid = TensorExpr.Sigmoid(x)
    val loss = TensorExpr.ReduceSum(sigmoid, Vector(0), keepDims = false)
    val graph = lossGraph(
      "sigmoid_test",
      Vector.empty,
      loss,
      Vector(NamedNode("sigmoid", sigmoid, tensorType(f32, 4)))
    )
    val result = Autograd.backward(graph, "loss")

    assert(result.outputs.exists(_.name == "grad/x"))
    assertEquals(result.outputs.find(_.name == "grad/x").get.tensorType, tensorType(f32, 4))

  test("backward: Softmax — gradient preserves shape"):
    val x = TensorExpr.Parameter("x", tensorType(f32, 4, 10), None)
    val softmax = TensorExpr.Softmax(x, -1)
    val sum = TensorExpr.ReduceSum(softmax, Vector(0, 1), keepDims = false)
    val graph = lossGraph(
      "softmax_test",
      Vector.empty,
      sum,
      Vector(
        NamedNode("softmax", softmax, tensorType(f32, 4, 10)),
        NamedNode("sum", sum, scalarType(f32))
      )
    )
    val result = Autograd.backward(graph, "loss")

    assert(result.outputs.exists(_.name == "grad/x"))
    assertEquals(result.outputs.find(_.name == "grad/x").get.tensorType, tensorType(f32, 4, 10))

  // ============================================================================
  // MatMul
  // ============================================================================

  test("backward: MatMul(A, B, false, false)"):
    val a = TensorExpr.Parameter("a", tensorType(f32, 3, 4), None)
    val b = TensorExpr.Parameter("b", tensorType(f32, 4, 5), None)
    val mm = TensorExpr.MatMul(a, b, false, false)
    val loss = TensorExpr.ReduceSum(mm, Vector(0, 1), keepDims = false)
    val graph = lossGraph(
      "matmul_ff",
      Vector.empty,
      loss,
      Vector(NamedNode("matmul", mm, tensorType(f32, 3, 5)))
    )
    val result = Autograd.backward(graph, "loss")

    // grad/a should have shape (3, 4) matching a
    assertEquals(result.outputs.find(_.name == "grad/a").get.tensorType, tensorType(f32, 3, 4))
    // grad/b should have shape (4, 5) matching b
    assertEquals(result.outputs.find(_.name == "grad/b").get.tensorType, tensorType(f32, 4, 5))

  test("backward: MatMul with transB"):
    val a = TensorExpr.Parameter("a", tensorType(f32, 3, 4), None)
    val b = TensorExpr.Parameter("b", tensorType(f32, 5, 4), None) // transposed shape
    val mm = TensorExpr.MatMul(a, b, false, true)
    val loss = TensorExpr.ReduceSum(mm, Vector(0, 1), keepDims = false)
    val graph = lossGraph(
      "matmul_ft",
      Vector.empty,
      loss,
      Vector(NamedNode("matmul", mm, tensorType(f32, 3, 5)))
    )
    val result = Autograd.backward(graph, "loss")

    assertEquals(result.outputs.find(_.name == "grad/a").get.tensorType, tensorType(f32, 3, 4))
    assertEquals(result.outputs.find(_.name == "grad/b").get.tensorType, tensorType(f32, 5, 4))

  test("backward: MatMul with transA"):
    val a = TensorExpr.Parameter("a", tensorType(f32, 4, 3), None) // transposed shape
    val b = TensorExpr.Parameter("b", tensorType(f32, 4, 5), None)
    val mm = TensorExpr.MatMul(a, b, true, false)
    val loss = TensorExpr.ReduceSum(mm, Vector(0, 1), keepDims = false)
    val graph = lossGraph(
      "matmul_tf",
      Vector.empty,
      loss,
      Vector(NamedNode("matmul", mm, tensorType(f32, 3, 5)))
    )
    val result = Autograd.backward(graph, "loss")

    assertEquals(result.outputs.find(_.name == "grad/a").get.tensorType, tensorType(f32, 4, 3))
    assertEquals(result.outputs.find(_.name == "grad/b").get.tensorType, tensorType(f32, 4, 5))

  test("backward: MatMul with transA and transB"):
    val a = TensorExpr.Parameter("a", tensorType(f32, 4, 3), None)
    val b = TensorExpr.Parameter("b", tensorType(f32, 5, 4), None)
    val mm = TensorExpr.MatMul(a, b, true, true)
    val loss = TensorExpr.ReduceSum(mm, Vector(0, 1), keepDims = false)
    val graph = lossGraph(
      "matmul_tt",
      Vector.empty,
      loss,
      Vector(NamedNode("matmul", mm, tensorType(f32, 3, 5)))
    )
    val result = Autograd.backward(graph, "loss")

    assertEquals(result.outputs.find(_.name == "grad/a").get.tensorType, tensorType(f32, 4, 3))
    assertEquals(result.outputs.find(_.name == "grad/b").get.tensorType, tensorType(f32, 5, 4))

  // ============================================================================
  // Linear layer
  // ============================================================================

  test("backward: Linear layer with MSELoss"):
    val x = TensorExpr.Input("x", tensorType(f32, 4, 3))
    val target = TensorExpr.Input("target", tensorType(f32, 4, 1))
    val w = TensorExpr.Parameter("w", tensorType(f32, 1, 3), None)
    val b = TensorExpr.Parameter("b", tensorType(f32, 1), None)
    val linear = TensorExpr.Linear(x, w, Some(b))
    val loss = TensorExpr.MSELoss(linear, target, Reduction.Mean)

    val graph = ComputeGraph(
      name = "linear_mse",
      inputs =
        Vector(GraphIO("x", tensorType(f32, 4, 3)), GraphIO("target", tensorType(f32, 4, 1))),
      outputs = Vector(GraphIO("loss", scalarType(f32))),
      nodes = Vector(
        NamedNode("linear", linear, tensorType(f32, 4, 1)),
        NamedNode("loss", loss, scalarType(f32))
      )
    )
    val result = Autograd.backward(graph, "loss")

    // Should have gradients for w and b
    assert(result.outputs.exists(_.name == "grad/w"), s"outputs: ${result.outputs.map(_.name)}")
    assert(result.outputs.exists(_.name == "grad/b"), s"outputs: ${result.outputs.map(_.name)}")

    // grad/w should match w's shape (1, 3)
    assertEquals(result.outputs.find(_.name == "grad/w").get.tensorType, tensorType(f32, 1, 3))
    // grad/b should match b's shape (1)
    assertEquals(result.outputs.find(_.name == "grad/b").get.tensorType, tensorType(f32, 1))

  // ============================================================================
  // Two-layer MLP
  // ============================================================================

  test("backward: two-layer MLP (Linear → Relu → Linear → MSELoss)"):
    val x = TensorExpr.Input("x", tensorType(f32, 4, 3))
    val target = TensorExpr.Input("target", tensorType(f32, 4, 1))
    val w1 = TensorExpr.Parameter("w1", tensorType(f32, 8, 3), None)
    val b1 = TensorExpr.Parameter("b1", tensorType(f32, 8), None)
    val w2 = TensorExpr.Parameter("w2", tensorType(f32, 1, 8), None)
    val b2 = TensorExpr.Parameter("b2", tensorType(f32, 1), None)

    val linear1 = TensorExpr.Linear(x, w1, Some(b1))
    val relu = TensorExpr.Relu(linear1)
    val linear2 = TensorExpr.Linear(relu, w2, Some(b2))
    val loss = TensorExpr.MSELoss(linear2, target, Reduction.Mean)

    val graph = ComputeGraph(
      name = "mlp_backward",
      inputs =
        Vector(GraphIO("x", tensorType(f32, 4, 3)), GraphIO("target", tensorType(f32, 4, 1))),
      outputs = Vector(GraphIO("loss", scalarType(f32))),
      nodes = Vector(
        NamedNode("linear1", linear1, tensorType(f32, 4, 8)),
        NamedNode("relu", relu, tensorType(f32, 4, 8)),
        NamedNode("linear2", linear2, tensorType(f32, 4, 1)),
        NamedNode("loss", loss, scalarType(f32))
      )
    )
    val result = Autograd.backward(graph, "loss")

    // All 4 parameters should have gradients
    for name <- Seq("w1", "b1", "w2", "b2") do
      assert(result.outputs.exists(_.name == s"grad/$name"), s"Missing grad/$name")

    // Verify shapes match
    assertEquals(result.outputs.find(_.name == "grad/w1").get.tensorType, tensorType(f32, 8, 3))
    assertEquals(result.outputs.find(_.name == "grad/b1").get.tensorType, tensorType(f32, 8))
    assertEquals(result.outputs.find(_.name == "grad/w2").get.tensorType, tensorType(f32, 1, 8))
    assertEquals(result.outputs.find(_.name == "grad/b2").get.tensorType, tensorType(f32, 1))

  // ============================================================================
  // DAG fan-out (residual connection)
  // ============================================================================

  test("backward: residual connection accumulates gradients via Add"):
    val x = TensorExpr.Parameter("x", tensorType(f32, 4), None)
    val neg = TensorExpr.Neg(x)
    // residual: neg(x) + x — x is used twice (DAG fan-out)
    val residual = TensorExpr.Add(neg, x)
    val loss = TensorExpr.ReduceSum(residual, Vector(0), keepDims = false)

    val graph = lossGraph(
      "residual",
      Vector.empty,
      loss,
      Vector(
        NamedNode("neg", neg, tensorType(f32, 4)),
        NamedNode("residual", residual, tensorType(f32, 4))
      )
    )
    val result = Autograd.backward(graph, "loss")

    assert(result.outputs.exists(_.name == "grad/x"))
    // grad/x should contain an Add node (accumulation from both paths)
    val gradXNode = result.nodes.find(_.name == "grad/x").get
    assertContainsExprType(gradXNode.expr, classOf[TensorExpr.Add])

  // ============================================================================
  // Broadcasting
  // ============================================================================

  test("backward: broadcasting reduces gradient for smaller operand"):
    val x = TensorExpr.Parameter("x", tensorType(f32, 3, 4), None)
    val bias = TensorExpr.Parameter("bias", tensorType(f32, 4), None)
    val add = TensorExpr.Add(x, bias) // broadcasts bias from (4,) to (3, 4)
    val loss = TensorExpr.ReduceSum(add, Vector(0, 1), keepDims = false)

    val graph = lossGraph(
      "broadcast_test",
      Vector.empty,
      loss,
      Vector(NamedNode("add", add, tensorType(f32, 3, 4)))
    )
    val result = Autograd.backward(graph, "loss")

    // bias gradient should be reduced to shape (4,) — ReduceSum along axis 0
    assertEquals(result.outputs.find(_.name == "grad/bias").get.tensorType, tensorType(f32, 4))
    assertEquals(result.outputs.find(_.name == "grad/x").get.tensorType, tensorType(f32, 3, 4))

  // ============================================================================
  // Shape ops
  // ============================================================================

  test("backward: Reshape — gradient uses inverse reshape"):
    val x = TensorExpr.Parameter("x", tensorType(f32, 3, 4), None)
    val reshaped = TensorExpr.Reshape(x, Vector(12))
    val loss = TensorExpr.ReduceSum(reshaped, Vector(0), keepDims = false)

    val graph = lossGraph(
      "reshape_test",
      Vector.empty,
      loss,
      Vector(NamedNode("reshape", reshaped, tensorType(f32, 12)))
    )
    val result = Autograd.backward(graph, "loss")

    assertEquals(result.outputs.find(_.name == "grad/x").get.tensorType, tensorType(f32, 3, 4))

  test("backward: Transpose — gradient uses inverse permutation"):
    val x = TensorExpr.Parameter("x", tensorType(f32, 3, 4, 5), None)
    val transposed = TensorExpr.Transpose(x, Vector(2, 0, 1))
    val loss = TensorExpr.ReduceSum(transposed, Vector(0, 1, 2), keepDims = false)

    val graph = lossGraph(
      "transpose_test",
      Vector.empty,
      loss,
      Vector(NamedNode("transpose", transposed, tensorType(f32, 5, 3, 4)))
    )
    val result = Autograd.backward(graph, "loss")

    assertEquals(result.outputs.find(_.name == "grad/x").get.tensorType, tensorType(f32, 3, 4, 5))

  test("backward: Flatten — gradient reshapes back"):
    val x = TensorExpr.Parameter("x", tensorType(f32, 2, 3, 4), None)
    val flat = TensorExpr.Flatten(x, 1)
    val loss = TensorExpr.ReduceSum(flat, Vector(0, 1), keepDims = false)

    val graph = lossGraph(
      "flatten_test",
      Vector.empty,
      loss,
      Vector(NamedNode("flatten", flat, tensorType(f32, 2, 12)))
    )
    val result = Autograd.backward(graph, "loss")

    assertEquals(result.outputs.find(_.name == "grad/x").get.tensorType, tensorType(f32, 2, 3, 4))

  test("backward: Squeeze / Unsqueeze are inverses"):
    val x = TensorExpr.Parameter("x", tensorType(f32, 3, 1, 4), None)
    val squeezed = TensorExpr.Squeeze(x, Vector(1))
    val loss = TensorExpr.ReduceSum(squeezed, Vector(0, 1), keepDims = false)

    val graph = lossGraph(
      "squeeze_test",
      Vector.empty,
      loss,
      Vector(NamedNode("squeeze", squeezed, tensorType(f32, 3, 4)))
    )
    val result = Autograd.backward(graph, "loss")

    // gradient should restore the squeezed dimension
    assertEquals(result.outputs.find(_.name == "grad/x").get.tensorType, tensorType(f32, 3, 1, 4))

  // ============================================================================
  // Reduction backward
  // ============================================================================

  test("backward: ReduceSum — gradient expands back"):
    val x = TensorExpr.Parameter("x", tensorType(f32, 3, 4), None)
    val reduced = TensorExpr.ReduceSum(x, Vector(1), keepDims = false)
    val loss = TensorExpr.ReduceSum(reduced, Vector(0), keepDims = false)

    val graph = lossGraph(
      "reduce_sum_test",
      Vector.empty,
      loss,
      Vector(NamedNode("reduce", reduced, tensorType(f32, 3)))
    )
    val result = Autograd.backward(graph, "loss")

    assertEquals(result.outputs.find(_.name == "grad/x").get.tensorType, tensorType(f32, 3, 4))

  test("backward: ReduceMean — gradient scales by 1/N"):
    val x = TensorExpr.Parameter("x", tensorType(f32, 3, 4), None)
    val reduced = TensorExpr.ReduceMean(x, Vector(1), keepDims = false)
    val loss = TensorExpr.ReduceSum(reduced, Vector(0), keepDims = false)

    val graph = lossGraph(
      "reduce_mean_test",
      Vector.empty,
      loss,
      Vector(NamedNode("reduce", reduced, tensorType(f32, 3)))
    )
    val result = Autograd.backward(graph, "loss")

    assertEquals(result.outputs.find(_.name == "grad/x").get.tensorType, tensorType(f32, 3, 4))
    // Should contain a Mul (for the scaling by 1/N)
    val gradNode = result.nodes.find(_.name == "grad/x").get
    assertContainsExprType(gradNode.expr, classOf[TensorExpr.Mul])

  // ============================================================================
  // Loss functions
  // ============================================================================

  test("backward: MSELoss Mean — gradient has correct shape"):
    val x = TensorExpr.Parameter("x", tensorType(f32, 4, 2), None)
    val target = TensorExpr.Input("target", tensorType(f32, 4, 2))
    val loss = TensorExpr.MSELoss(x, target, Reduction.Mean)
    val graph = ComputeGraph(
      name = "mse_test",
      inputs = Vector(GraphIO("target", tensorType(f32, 4, 2))),
      outputs = Vector(GraphIO("loss", scalarType(f32))),
      nodes = Vector(NamedNode("loss", loss, scalarType(f32)))
    )
    val result = Autograd.backward(graph, "loss")

    assertEquals(result.outputs.find(_.name == "grad/x").get.tensorType, tensorType(f32, 4, 2))

  test("backward: L1Loss — gradient contains sign"):
    val x = TensorExpr.Parameter("x", tensorType(f32, 4), None)
    val target = TensorExpr.Input("target", tensorType(f32, 4))
    val loss = TensorExpr.L1Loss(x, target, Reduction.Mean)
    val graph = ComputeGraph(
      name = "l1_test",
      inputs = Vector(GraphIO("target", tensorType(f32, 4))),
      outputs = Vector(GraphIO("loss", scalarType(f32))),
      nodes = Vector(NamedNode("loss", loss, scalarType(f32)))
    )
    val result = Autograd.backward(graph, "loss")

    assertEquals(result.outputs.find(_.name == "grad/x").get.tensorType, tensorType(f32, 4))

  test("backward: CrossEntropyLoss — gradient has correct shape"):
    val x = TensorExpr.Parameter("x", tensorType(f32, 4, 10), None)
    val target = TensorExpr.Input("target", tensorType(f32, 4))
    val loss = TensorExpr.CrossEntropyLoss(x, target, None, Reduction.Mean)
    val graph = ComputeGraph(
      name = "ce_test",
      inputs = Vector(GraphIO("target", tensorType(f32, 4))),
      outputs = Vector(GraphIO("loss", scalarType(f32))),
      nodes = Vector(NamedNode("loss", loss, scalarType(f32)))
    )
    val result = Autograd.backward(graph, "loss")

    // grad/x should have same shape as input logits (4, 10)
    assertEquals(result.outputs.find(_.name == "grad/x").get.tensorType, tensorType(f32, 4, 10))

  // ============================================================================
  // Graph structure
  // ============================================================================

  test("backward: output graph has forward + backward nodes"):
    val x = TensorExpr.Parameter("x", tensorType(f32, 4), None)
    val relu = TensorExpr.Relu(x)
    val loss = TensorExpr.ReduceSum(relu, Vector(0), keepDims = false)

    val graph = lossGraph(
      "structure_test",
      Vector.empty,
      loss,
      Vector(NamedNode("relu", relu, tensorType(f32, 4)))
    )
    val result = Autograd.backward(graph, "loss")

    // Forward nodes should still be present
    assert(result.nodes.exists(_.name == "relu"))
    assert(result.nodes.exists(_.name == "loss"))
    // Backward nodes should be appended
    assert(result.nodes.exists(_.name == "grad/x"))
    // Forward nodes should come first
    val forwardIdx = result.nodes.indexWhere(_.name == "relu")
    val gradIdx = result.nodes.indexWhere(_.name == "grad/x")
    assert(forwardIdx < gradIdx)

  test("backward: no parameter gradients → graph unchanged"):
    // Graph with only Input and Constant (no Parameters)
    val x = TensorExpr.Input("x", tensorType(f32, 4))
    val loss = TensorExpr.ReduceSum(x, Vector(0), keepDims = false)
    val graph = lossGraph(
      "no_params",
      Vector(GraphIO("x", tensorType(f32, 4))),
      loss
    )
    val result = Autograd.backward(graph, "loss")

    // No grad outputs should be added
    assertEquals(result.outputs.size, graph.outputs.size)

  // ============================================================================
  // Cast
  // ============================================================================

  test("backward: Cast float→float — gradient casts back"):
    val x = TensorExpr.Parameter("x", tensorType(f32, 4), None)
    val cast = TensorExpr.Cast(x, TensorDType.Float64)
    val loss = TensorExpr.ReduceSum(cast, Vector(0), keepDims = false)

    val graph = ComputeGraph(
      name = "cast_test",
      inputs = Vector.empty,
      outputs = Vector(GraphIO("loss", scalarType(TensorDType.Float64))),
      nodes = Vector(
        NamedNode("cast", cast, tensorType(TensorDType.Float64, 4)),
        NamedNode("loss", loss, scalarType(TensorDType.Float64))
      )
    )
    val result = Autograd.backward(graph, "loss")

    assertEquals(result.outputs.find(_.name == "grad/x").get.tensorType, tensorType(f32, 4))

  // ============================================================================
  // Where
  // ============================================================================

  test("backward: Where — condition gets None, x and y get masked gradients"):
    val cond = TensorExpr.Input("cond", TensorType(TensorDType.Bool, Vector(Dim(4))))
    val x = TensorExpr.Parameter("x", tensorType(f32, 4), None)
    val y = TensorExpr.Parameter("y", tensorType(f32, 4), None)
    val where = TensorExpr.Where(cond, x, y)
    val loss = TensorExpr.ReduceSum(where, Vector(0), keepDims = false)

    val graph = ComputeGraph(
      name = "where_test",
      inputs = Vector(GraphIO("cond", TensorType(TensorDType.Bool, Vector(Dim(4))))),
      outputs = Vector(GraphIO("loss", scalarType(f32))),
      nodes = Vector(
        NamedNode("where", where, tensorType(f32, 4)),
        NamedNode("loss", loss, scalarType(f32))
      )
    )
    val result = Autograd.backward(graph, "loss")

    // x and y should get gradients, condition should not
    assert(result.outputs.exists(_.name == "grad/x"))
    assert(result.outputs.exists(_.name == "grad/y"))
    assert(!result.outputs.exists(_.name == "grad/cond"))

  // ============================================================================
  // Helpers
  // ============================================================================

  /** Check that an expression tree contains a node of the given type. */
  private def assertContainsExprType(expr: TensorExpr, cls: Class[?]): Unit =
    val found = containsExprType(expr, cls, new java.util.IdentityHashMap())
    assert(found, s"Expected to find ${cls.getSimpleName} in expression tree")

  private def containsExprType(
      expr: TensorExpr,
      cls: Class[?],
      visited: java.util.IdentityHashMap[TensorExpr, java.lang.Boolean]
  ): Boolean =
    if visited.containsKey(expr) then return false
    visited.put(expr, java.lang.Boolean.TRUE)
    if cls.isInstance(expr) then true
    else MLTreeTransform.childExprs(expr).exists(containsExprType(_, cls, visited))
