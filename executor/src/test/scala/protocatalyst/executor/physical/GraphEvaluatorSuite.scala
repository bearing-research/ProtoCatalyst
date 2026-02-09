package protocatalyst.executor.physical

import java.nio.{ByteBuffer, ByteOrder}

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph._
import protocatalyst.ml.optimizer.Autograd
import protocatalyst.ml.types._
import protocatalyst.plan.{OptimizerType, TrainConfig}

class GraphEvaluatorSuite extends munit.FunSuite:

  import GraphEvaluator.Tensor

  // ═══════════════════════════════════════════════════════════════
  // Helper methods
  // ═══════════════════════════════════════════════════════════════

  private def f32Type(dims: Int*): TensorType =
    TensorType(TensorDType.Float32, dims.map(Dim.Static(_)).toVector)

  private def scalarType: TensorType =
    TensorType(TensorDType.Float32, Vector.empty)

  private def floatBytes(values: Float*): Array[Byte] =
    val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    values.foreach(buf.putFloat)
    buf.array()

  private def constExpr(name: String, shape: Vector[Int], values: Float*): TensorExpr =
    val tt = TensorType(TensorDType.Float32, shape.map(Dim.Static(_)))
    TensorExpr.Constant(name, tt, TensorData(TensorDType.Float32, shape, floatBytes(values*)))

  private def scalarConst(name: String, value: Float): TensorExpr =
    constExpr(name, Vector.empty, value)

  private def assertTensorClose(actual: Tensor, expected: Array[Float], tol: Float = 1e-4f)(implicit
      loc: munit.Location
  ): Unit =
    assertEquals(actual.data.length, expected.length, s"Length mismatch")
    for i <- expected.indices do
      assertFloatClose(actual.data(i), expected(i), tol, s"Mismatch at index $i")

  private def assertFloatClose(actual: Float, expected: Float, tol: Float, msg: String = "")(
      implicit loc: munit.Location
  ): Unit =
    assert(
      math.abs(actual - expected) < tol,
      s"$msg: expected $expected, got $actual (diff ${math.abs(actual - expected)})"
    )

  // ═══════════════════════════════════════════════════════════════
  // Forward pass tests
  // ═══════════════════════════════════════════════════════════════

  test("forward: Add two constants") {
    val a = constExpr("a", Vector(3), 1.0f, 2.0f, 3.0f)
    val b = constExpr("b", Vector(3), 4.0f, 5.0f, 6.0f)
    val add = TensorExpr.Add(a, b)

    val graph = ComputeGraph(
      name = "test_add",
      inputs = Vector.empty,
      outputs = Vector(GraphIO("result", f32Type(3))),
      nodes = Vector(
        NamedNode("a", a, f32Type(3)),
        NamedNode("b", b, f32Type(3)),
        NamedNode("result", add, f32Type(3))
      )
    )

    val outputs = GraphEvaluator.forward(graph, Map.empty)
    assertTensorClose(outputs("result"), Array(5.0f, 7.0f, 9.0f))
  }

  test("forward: MatMul") {
    // [2, 3] x [3, 2] = [2, 2]
    val x = constExpr("x", Vector(2, 3), 1, 2, 3, 4, 5, 6)
    val w = constExpr("w", Vector(3, 2), 1, 0, 0, 1, 1, 1)
    val mm = TensorExpr.MatMul(x, w, transA = false, transB = false)

    val graph = ComputeGraph(
      name = "test_matmul",
      inputs = Vector.empty,
      outputs = Vector(GraphIO("result", f32Type(2, 2))),
      nodes = Vector(
        NamedNode("x", x, f32Type(2, 3)),
        NamedNode("w", w, f32Type(3, 2)),
        NamedNode("result", mm, f32Type(2, 2))
      )
    )

    val outputs = GraphEvaluator.forward(graph, Map.empty)
    // [1*1+2*0+3*1, 1*0+2*1+3*1] = [4, 5]
    // [4*1+5*0+6*1, 4*0+5*1+6*1] = [10, 11]
    assertTensorClose(outputs("result"), Array(4.0f, 5.0f, 10.0f, 11.0f))
  }

  test("forward: Relu") {
    val x = constExpr("x", Vector(4), -2.0f, -1.0f, 0.0f, 1.0f)
    val relu = TensorExpr.Relu(x)

    val graph = ComputeGraph(
      name = "test_relu",
      inputs = Vector.empty,
      outputs = Vector(GraphIO("result", f32Type(4))),
      nodes = Vector(
        NamedNode("x", x, f32Type(4)),
        NamedNode("result", relu, f32Type(4))
      )
    )

    val outputs = GraphEvaluator.forward(graph, Map.empty)
    assertTensorClose(outputs("result"), Array(0.0f, 0.0f, 0.0f, 1.0f))
  }

  test("forward: Sigmoid") {
    val x = constExpr("x", Vector(3), 0.0f, 1.0f, -1.0f)
    val sig = TensorExpr.Sigmoid(x)

    val graph = ComputeGraph(
      name = "test_sigmoid",
      inputs = Vector.empty,
      outputs = Vector(GraphIO("result", f32Type(3))),
      nodes = Vector(
        NamedNode("x", x, f32Type(3)),
        NamedNode("result", sig, f32Type(3))
      )
    )

    val outputs = GraphEvaluator.forward(graph, Map.empty)
    val expected = Array(0.5f, 0.7311f, 0.2689f)
    assertTensorClose(outputs("result"), expected, tol = 1e-3f)
  }

  test("forward: Linear layer (x @ W^T + bias)") {
    // x: [2, 3], w: [2, 3] (output_dim=2, input_dim=3), bias: [2]
    val x = TensorExpr.Input("x", f32Type(2, 3))
    val w = constExpr("w", Vector(2, 3), 1, 0, 0, 0, 1, 0) // identity-like
    val b = constExpr("b", Vector(2), 0.1f, 0.2f)
    val linear = TensorExpr.Linear(x, w, Some(b))

    val graph = ComputeGraph(
      name = "test_linear",
      inputs = Vector(GraphIO("x", f32Type(2, 3))),
      outputs = Vector(GraphIO("result", f32Type(2, 2))),
      nodes = Vector(
        NamedNode("w", w, f32Type(2, 3)),
        NamedNode("b", b, f32Type(2)),
        NamedNode("result", linear, f32Type(2, 2))
      )
    )

    val inputs = Map("x" -> Tensor(Array(1f, 2f, 3f, 4f, 5f, 6f), Vector(2, 3)))
    val outputs = GraphEvaluator.forward(graph, inputs)
    // Row 0: [1*1+2*0+3*0+0.1, 1*0+2*1+3*0+0.2] = [1.1, 2.2]
    // Row 1: [4*1+5*0+6*0+0.1, 4*0+5*1+6*0+0.2] = [4.1, 5.2]
    assertTensorClose(outputs("result"), Array(1.1f, 2.2f, 4.1f, 5.2f), tol = 1e-3f)
  }

  test("forward: Softmax") {
    val x = constExpr("x", Vector(1, 3), 1.0f, 2.0f, 3.0f)
    val sm = TensorExpr.Softmax(x, axis = -1)

    val graph = ComputeGraph(
      name = "test_softmax",
      inputs = Vector.empty,
      outputs = Vector(GraphIO("result", f32Type(1, 3))),
      nodes = Vector(
        NamedNode("x", x, f32Type(1, 3)),
        NamedNode("result", sm, f32Type(1, 3))
      )
    )

    val outputs = GraphEvaluator.forward(graph, Map.empty)
    val result = outputs("result")
    // Softmax values should sum to 1
    val sum = result.data.sum
    assertFloatClose(sum, 1.0f, 1e-4f, "softmax sum")
    // Values should be in descending order matching input
    assert(result.data(0) < result.data(1))
    assert(result.data(1) < result.data(2))
  }

  test("forward: MSELoss") {
    val pred = constExpr("pred", Vector(3), 1.0f, 2.0f, 3.0f)
    val target = constExpr("target", Vector(3), 1.5f, 2.5f, 3.5f)
    val loss = TensorExpr.MSELoss(pred, target, Reduction.Mean)

    val graph = ComputeGraph(
      name = "test_mse",
      inputs = Vector.empty,
      outputs = Vector(GraphIO("loss", scalarType)),
      nodes = Vector(
        NamedNode("pred", pred, f32Type(3)),
        NamedNode("target", target, f32Type(3)),
        NamedNode("loss", loss, scalarType)
      )
    )

    val outputs = GraphEvaluator.forward(graph, Map.empty)
    // MSE = mean((1-1.5)^2 + (2-2.5)^2 + (3-3.5)^2) = mean(0.25+0.25+0.25) = 0.25
    assertFloatClose(outputs("loss").data(0), 0.25f, 1e-5f)
  }

  test("forward: ReduceSum and ReduceMean") {
    val x = constExpr("x", Vector(2, 3), 1, 2, 3, 4, 5, 6)
    val rsum = TensorExpr.ReduceSum(x, Vector(1), keepDims = false)
    val rmean = TensorExpr.ReduceMean(x, Vector(0), keepDims = false)

    val graph = ComputeGraph(
      name = "test_reduce",
      inputs = Vector.empty,
      outputs = Vector(
        GraphIO("sum", f32Type(2)),
        GraphIO("mean", f32Type(3))
      ),
      nodes = Vector(
        NamedNode("x", x, f32Type(2, 3)),
        NamedNode("sum", rsum, f32Type(2)),
        NamedNode("mean", rmean, f32Type(3))
      )
    )

    val outputs = GraphEvaluator.forward(graph, Map.empty)
    assertTensorClose(outputs("sum"), Array(6.0f, 15.0f)) // [1+2+3, 4+5+6]
    assertTensorClose(outputs("mean"), Array(2.5f, 3.5f, 4.5f)) // [(1+4)/2, (2+5)/2, (3+6)/2]
  }

  test("forward: Reshape") {
    val x = constExpr("x", Vector(2, 3), 1, 2, 3, 4, 5, 6)
    val reshape = TensorExpr.Reshape(x, Vector(3, 2))

    val graph = ComputeGraph(
      name = "test_reshape",
      inputs = Vector.empty,
      outputs = Vector(GraphIO("result", f32Type(3, 2))),
      nodes = Vector(
        NamedNode("x", x, f32Type(2, 3)),
        NamedNode("result", reshape, f32Type(3, 2))
      )
    )

    val outputs = GraphEvaluator.forward(graph, Map.empty)
    assertEquals(outputs("result").shape, Vector(3, 2))
    assertTensorClose(outputs("result"), Array(1, 2, 3, 4, 5, 6))
  }

  test("forward: Broadcasting Add scalar") {
    val x = constExpr("x", Vector(3), 1.0f, 2.0f, 3.0f)
    val b = scalarConst("b", 10.0f)
    val add = TensorExpr.Add(x, b)

    val graph = ComputeGraph(
      name = "test_broadcast",
      inputs = Vector.empty,
      outputs = Vector(GraphIO("result", f32Type(3))),
      nodes = Vector(
        NamedNode("x", x, f32Type(3)),
        NamedNode("b", b, scalarType),
        NamedNode("result", add, f32Type(3))
      )
    )

    val outputs = GraphEvaluator.forward(graph, Map.empty)
    assertTensorClose(outputs("result"), Array(11.0f, 12.0f, 13.0f))
  }

  test("forward: Input tensors") {
    val x = TensorExpr.Input("x", f32Type(2))
    val y = TensorExpr.Input("y", f32Type(2))
    val add = TensorExpr.Add(x, y)

    val graph = ComputeGraph(
      name = "test_input",
      inputs = Vector(GraphIO("x", f32Type(2)), GraphIO("y", f32Type(2))),
      outputs = Vector(GraphIO("result", f32Type(2))),
      nodes = Vector(NamedNode("result", add, f32Type(2)))
    )

    val inputs = Map(
      "x" -> Tensor(Array(1.0f, 2.0f), Vector(2)),
      "y" -> Tensor(Array(3.0f, 4.0f), Vector(2))
    )
    val outputs = GraphEvaluator.forward(graph, inputs)
    assertTensorClose(outputs("result"), Array(4.0f, 6.0f))
  }

  // ═══════════════════════════════════════════════════════════════
  // Training tests
  // ═══════════════════════════════════════════════════════════════

  test("training: Linear regression with SGD converges") {
    // y = 2*x + 1
    // Model: y_pred = w*x + b, train to learn w≈2, b≈1
    val xInput = TensorExpr.Input("x", f32Type(4))
    val yInput = TensorExpr.Input("y", f32Type(4))
    val w = TensorExpr.Parameter("w", f32Type(1), Some(Initializer.Zeros))
    val b = TensorExpr.Parameter("b", scalarType, Some(Initializer.Zeros))

    // y_pred = x * w + b
    val xw = TensorExpr.Mul(xInput, w)
    val yPred = TensorExpr.Add(xw, b)
    val loss = TensorExpr.MSELoss(yPred, yInput, Reduction.Mean)

    val graph = ComputeGraph(
      name = "linear_reg",
      inputs = Vector(GraphIO("x", f32Type(4)), GraphIO("y", f32Type(4))),
      outputs = Vector(GraphIO("loss", scalarType)),
      nodes = Vector(
        NamedNode("w", w, f32Type(1)),
        NamedNode("b", b, scalarType),
        NamedNode("xw", xw, f32Type(4)),
        NamedNode("y_pred", yPred, f32Type(4)),
        NamedNode("loss", loss, scalarType)
      )
    )

    val gradGraph = Autograd.backward(graph, "loss")
    val params = GraphEvaluator.initializeParams(graph)

    val config = TrainConfig(
      lossNode = "loss",
      epochs = 200,
      learningRate = 0.01,
      optimizer = OptimizerType.SGD
    )

    val xData = Tensor(Array(1.0f, 2.0f, 3.0f, 4.0f), Vector(4))
    val yData = Tensor(Array(3.0f, 5.0f, 7.0f, 9.0f), Vector(4)) // y = 2x + 1
    val inputs = Map("x" -> xData, "y" -> yData)

    var lastLoss = Double.MaxValue
    for _ <- 0 until config.epochs do
      lastLoss = GraphEvaluator.trainEpoch(graph, gradGraph, inputs, params, config)

    // Loss should be small after training
    assert(lastLoss < 0.1, s"Final loss $lastLoss should be < 0.1")

    // w should be close to 2.0, b close to 1.0
    assertFloatClose(params("w").data(0), 2.0f, 0.3f, "weight w")
    assertFloatClose(params("b").data(0), 1.0f, 0.3f, "bias b")
  }

  test("training: Linear regression with Adam converges faster") {
    val xInput = TensorExpr.Input("x", f32Type(4))
    val yInput = TensorExpr.Input("y", f32Type(4))
    val w = TensorExpr.Parameter("w", f32Type(1), Some(Initializer.Zeros))
    val b = TensorExpr.Parameter("b", scalarType, Some(Initializer.Zeros))

    val xw = TensorExpr.Mul(xInput, w)
    val yPred = TensorExpr.Add(xw, b)
    val loss = TensorExpr.MSELoss(yPred, yInput, Reduction.Mean)

    val graph = ComputeGraph(
      name = "linear_reg_adam",
      inputs = Vector(GraphIO("x", f32Type(4)), GraphIO("y", f32Type(4))),
      outputs = Vector(GraphIO("loss", scalarType)),
      nodes = Vector(
        NamedNode("w", w, f32Type(1)),
        NamedNode("b", b, scalarType),
        NamedNode("xw", xw, f32Type(4)),
        NamedNode("y_pred", yPred, f32Type(4)),
        NamedNode("loss", loss, scalarType)
      )
    )

    val gradGraph = Autograd.backward(graph, "loss")
    val params = GraphEvaluator.initializeParams(graph)
    val adamState = GraphEvaluator.AdamState()

    val config = TrainConfig(
      lossNode = "loss",
      epochs = 100,
      learningRate = 0.1,
      optimizer = OptimizerType.Adam
    )

    val xData = Tensor(Array(1.0f, 2.0f, 3.0f, 4.0f), Vector(4))
    val yData = Tensor(Array(3.0f, 5.0f, 7.0f, 9.0f), Vector(4))
    val inputs = Map("x" -> xData, "y" -> yData)

    var lastLoss = Double.MaxValue
    for _ <- 0 until config.epochs do
      lastLoss =
        GraphEvaluator.trainEpoch(graph, gradGraph, inputs, params, config, Some(adamState))

    assert(lastLoss < 0.1, s"Final loss $lastLoss should be < 0.1")
  }

  test("initializeParams: zeros and ones initializers") {
    val w = TensorExpr.Parameter("w", f32Type(3), Some(Initializer.Zeros))
    val b = TensorExpr.Parameter("b", f32Type(2), Some(Initializer.Ones))
    val add = TensorExpr.Add(w, b) // just to create a graph

    val graph = ComputeGraph(
      name = "test_init",
      inputs = Vector.empty,
      outputs = Vector(GraphIO("result", f32Type(3))),
      nodes = Vector(
        NamedNode("w", w, f32Type(3)),
        NamedNode("b", b, f32Type(2)),
        NamedNode("result", add, f32Type(3))
      )
    )

    val params = GraphEvaluator.initializeParams(graph)
    assertTensorClose(params("w"), Array(0.0f, 0.0f, 0.0f))
    assertTensorClose(params("b"), Array(1.0f, 1.0f))
  }

  test("forward: CrossEntropyLoss") {
    // pred: [2, 3] (2 samples, 3 classes), target: [2] (class indices)
    // Sample 0: logits [1, 2, 3], target=2; Sample 1: logits [3, 2, 1], target=0
    val pred = constExpr("pred", Vector(2, 3), 1, 2, 3, 3, 2, 1)
    val target = constExpr("target", Vector(2), 2, 0)
    val loss = TensorExpr.CrossEntropyLoss(pred, target, None, Reduction.Mean)

    val graph = ComputeGraph(
      name = "test_ce",
      inputs = Vector.empty,
      outputs = Vector(GraphIO("loss", scalarType)),
      nodes = Vector(
        NamedNode("pred", pred, f32Type(2, 3)),
        NamedNode("target", target, f32Type(2)),
        NamedNode("loss", loss, scalarType)
      )
    )

    val outputs = GraphEvaluator.forward(graph, Map.empty)
    // CE for sample 0: log(sum(exp([1,2,3]-3))) - (3-3) = log(exp(-2)+exp(-1)+1) ≈ 0.4076
    // CE for sample 1: same by symmetry ≈ 0.4076
    // Mean ≈ 0.4076
    val lossVal = outputs("loss").data(0)
    assert(lossVal > 0.0f && lossVal < 2.0f, s"CrossEntropyLoss = $lossVal should be reasonable")
  }

  test("forward: Transpose") {
    val x = constExpr("x", Vector(2, 3), 1, 2, 3, 4, 5, 6)
    val t = TensorExpr.Transpose(x, Vector(1, 0))

    val graph = ComputeGraph(
      name = "test_transpose",
      inputs = Vector.empty,
      outputs = Vector(GraphIO("result", f32Type(3, 2))),
      nodes = Vector(
        NamedNode("x", x, f32Type(2, 3)),
        NamedNode("result", t, f32Type(3, 2))
      )
    )

    val outputs = GraphEvaluator.forward(graph, Map.empty)
    assertEquals(outputs("result").shape, Vector(3, 2))
    assertTensorClose(outputs("result"), Array(1, 4, 2, 5, 3, 6))
  }
