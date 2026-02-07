package protocatalyst.ml.graph

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.types._

class ComputeGraphSuite extends munit.FunSuite:

  private val f32 = TensorDType.Float32

  private def tensorType(dtype: TensorDType, dims: Int*): TensorType =
    TensorType(dtype, dims.map(Dim(_)).toVector)

  // ============================================================================
  // Simple graph construction
  // ============================================================================

  test("empty graph"):
    val graph = ComputeGraph(
      name = "empty",
      inputs = Vector.empty,
      outputs = Vector.empty,
      nodes = Vector.empty
    )
    assertEquals(graph.numOps, 0)
    assertEquals(graph.opsetVersion, 1)

  test("single linear layer graph"):
    val inputType = tensorType(f32, 16, 784)
    val outputType = tensorType(f32, 16, 10)
    val weightType = tensorType(f32, 10, 784)
    val biasType = tensorType(f32, 10)

    val x = TensorExpr.Input("x", inputType)
    val w = TensorExpr.Parameter("w", weightType, Some(Initializer.Kaiming("fan_in", "relu")))
    val b = TensorExpr.Parameter("b", biasType, Some(Initializer.Zeros))
    val linear = TensorExpr.Linear(x, w, Some(b))

    val graph = ComputeGraph(
      name = "linear_model",
      inputs = Vector(GraphIO("x", inputType)),
      outputs = Vector(GraphIO("output", outputType)),
      nodes = Vector(NamedNode("linear1", linear, outputType))
    )

    assertEquals(graph.name, "linear_model")
    assertEquals(graph.inputs.size, 1)
    assertEquals(graph.outputs.size, 1)
    assertEquals(graph.numOps, 1)

  test("two-layer MLP graph"):
    val x = TensorExpr.Input("x", tensorType(f32, 16, 784))
    val w1 = TensorExpr.Parameter("w1", tensorType(f32, 256, 784), None)
    val b1 = TensorExpr.Parameter("b1", tensorType(f32, 256), Some(Initializer.Zeros))
    val w2 = TensorExpr.Parameter("w2", tensorType(f32, 10, 256), None)
    val b2 = TensorExpr.Parameter("b2", tensorType(f32, 10), Some(Initializer.Zeros))

    val linear1 = TensorExpr.Linear(x, w1, Some(b1))
    val relu = TensorExpr.Relu(linear1)
    val linear2 = TensorExpr.Linear(relu, w2, Some(b2))

    val graph = ComputeGraph(
      name = "mlp",
      inputs = Vector(GraphIO("x", tensorType(f32, 16, 784))),
      outputs = Vector(GraphIO("logits", tensorType(f32, 16, 10))),
      nodes = Vector(
        NamedNode("linear1", linear1, tensorType(f32, 16, 256)),
        NamedNode("relu1", relu, tensorType(f32, 16, 256)),
        NamedNode("linear2", linear2, tensorType(f32, 16, 10))
      )
    )

    assertEquals(graph.numOps, 3)
    assertEquals(graph.nodeMap.size, 3)
    assert(graph.nodeMap.contains("linear1"))
    assert(graph.nodeMap.contains("relu1"))
    assert(graph.nodeMap.contains("linear2"))

  test("residual block graph (DAG with shared node)"):
    val x = TensorExpr.Input("x", tensorType(f32, 16, 256))
    val w = TensorExpr.Parameter("w", tensorType(f32, 256, 256), None)
    val linear = TensorExpr.Linear(x, w, None)
    val relu = TensorExpr.Relu(linear)
    val residual = TensorExpr.Add(relu, x) // x is shared — DAG, not tree

    val graph = ComputeGraph(
      name = "residual",
      inputs = Vector(GraphIO("x", tensorType(f32, 16, 256))),
      outputs = Vector(GraphIO("out", tensorType(f32, 16, 256))),
      nodes = Vector(
        NamedNode("linear", linear, tensorType(f32, 16, 256)),
        NamedNode("relu", relu, tensorType(f32, 16, 256)),
        NamedNode("residual", residual, tensorType(f32, 16, 256))
      )
    )

    assertEquals(graph.numOps, 3)
    // Verify x is shared in the DAG
    val TensorExpr.Add(_, addRhs) = graph.nodeMap("residual").expr: @unchecked
    val TensorExpr.Linear(linInput, _, _) = graph.nodeMap("linear").expr: @unchecked
    assert(addRhs.eq(x))
    assert(linInput.eq(x))

  // ============================================================================
  // GraphIO
  // ============================================================================

  test("GraphIO stores name and type"):
    val io = GraphIO("input_image", tensorType(f32, 1, 3, 224, 224))
    assertEquals(io.name, "input_image")
    assertEquals(io.tensorType.rank, 4)

  // ============================================================================
  // NamedNode
  // ============================================================================

  test("NamedNode stores name, expr, and output type"):
    val x = TensorExpr.Input("x", tensorType(f32, 10))
    val relu = TensorExpr.Relu(x)
    val node = NamedNode("relu1", relu, tensorType(f32, 10))
    assertEquals(node.name, "relu1")
    assertEquals(node.outputType.dtype, f32)

  // ============================================================================
  // Custom opset version
  // ============================================================================

  test("custom opset version"):
    val graph = ComputeGraph(
      name = "versioned",
      inputs = Vector.empty,
      outputs = Vector.empty,
      nodes = Vector.empty,
      opsetVersion = 13
    )
    assertEquals(graph.opsetVersion, 13)

  // ============================================================================
  // Conv + BN + ReLU pattern (common in CNNs)
  // ============================================================================

  test("conv + batchnorm + relu pattern"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 3, 224, 224))
    val convW = TensorExpr.Parameter("conv.weight", tensorType(f32, 64, 3, 7, 7), None)
    val convB = TensorExpr.Parameter("conv.bias", tensorType(f32, 64), Some(Initializer.Zeros))
    val bnScale = TensorExpr.Parameter("bn.scale", tensorType(f32, 64), Some(Initializer.Ones))
    val bnBias = TensorExpr.Parameter("bn.bias", tensorType(f32, 64), Some(Initializer.Zeros))
    val bnMean = TensorExpr.Parameter("bn.mean", tensorType(f32, 64), Some(Initializer.Zeros))
    val bnVar = TensorExpr.Parameter("bn.var", tensorType(f32, 64), Some(Initializer.Ones))

    val conv = TensorExpr.Conv(
      x,
      convW,
      Some(convB),
      strides = Vector(2, 2),
      pads = Vector(3, 3, 3, 3),
      dilations = Vector(1, 1),
      group = 1
    )
    val bn = TensorExpr.BatchNorm(
      conv,
      bnScale,
      bnBias,
      bnMean,
      bnVar,
      epsilon = 1e-5,
      momentum = 0.1,
      training = false
    )
    val relu = TensorExpr.Relu(bn)

    val graph = ComputeGraph(
      name = "conv_bn_relu",
      inputs = Vector(GraphIO("x", tensorType(f32, 1, 3, 224, 224))),
      outputs = Vector(GraphIO("out", tensorType(f32, 1, 64, 112, 112))),
      nodes = Vector(
        NamedNode("conv1", conv, tensorType(f32, 1, 64, 112, 112)),
        NamedNode("bn1", bn, tensorType(f32, 1, 64, 112, 112)),
        NamedNode("relu1", relu, tensorType(f32, 1, 64, 112, 112))
      )
    )

    assertEquals(graph.numOps, 3)
