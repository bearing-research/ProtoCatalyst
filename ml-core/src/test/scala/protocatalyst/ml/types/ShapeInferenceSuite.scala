package protocatalyst.ml.types

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph._

class ShapeInferenceSuite extends munit.FunSuite:

  private val f32 = TensorDType.Float32
  private val i64 = TensorDType.Int64

  private def tensorType(dtype: TensorDType, dims: Int*): TensorType =
    TensorType(dtype, dims.map(Dim(_)).toVector)

  private def dynamicType(dtype: TensorDType, dims: (Option[String], Option[Int])*): TensorType =
    TensorType(
      dtype,
      dims.map {
        case (None, Some(size))    => Dim.Static(size)
        case (Some(name), None)    => Dim.Dynamic(Some(name))
        case (None, None)          => Dim.Dynamic(None)
        case (Some(name), Some(_)) => Dim.Dynamic(Some(name))
      }.toVector
    )

  // ============================================================================
  // Broadcasting helpers
  // ============================================================================

  test("broadcastDim: same static"):
    assertEquals(
      ShapeInference.broadcastDim(Dim.Static(4), Dim.Static(4)),
      Dim.Static(4)
    )

  test("broadcastDim: broadcast from 1"):
    assertEquals(
      ShapeInference.broadcastDim(Dim.Static(1), Dim.Static(4)),
      Dim.Static(4)
    )
    assertEquals(
      ShapeInference.broadcastDim(Dim.Static(4), Dim.Static(1)),
      Dim.Static(4)
    )

  test("broadcastDim: incompatible static"):
    intercept[ShapeInference.ShapeInferenceException] {
      ShapeInference.broadcastDim(Dim.Static(3), Dim.Static(4))
    }

  test("broadcastDim: dynamic + static(1)"):
    val dyn = Dim.Dynamic(Some("batch"))
    assertEquals(ShapeInference.broadcastDim(dyn, Dim.Static(1)), dyn)
    assertEquals(ShapeInference.broadcastDim(Dim.Static(1), dyn), dyn)

  test("broadcastDim: dynamic + static(n)"):
    // Assumes graph is correct, so returns Static(n)
    assertEquals(
      ShapeInference.broadcastDim(Dim.Dynamic(None), Dim.Static(4)),
      Dim.Static(4)
    )

  test("broadcastDim: same named dynamic"):
    val dyn = Dim.Dynamic(Some("batch"))
    assertEquals(ShapeInference.broadcastDim(dyn, dyn), dyn)

  test("broadcastDim: different named dynamic"):
    assertEquals(
      ShapeInference.broadcastDim(Dim.Dynamic(Some("a")), Dim.Dynamic(Some("b"))),
      Dim.Dynamic(None)
    )

  test("broadcastShapes: different ranks"):
    val result =
      ShapeInference.broadcastShapes(Vector(Dim(3), Dim(4)), Vector(Dim(4)))
    assertEquals(result, Vector(Dim(3), Dim(4)))

  test("broadcastShapes: scalar + vector"):
    val result = ShapeInference.broadcastShapes(Vector.empty, Vector(Dim(4)))
    assertEquals(result, Vector(Dim(4)))

  // ============================================================================
  // Leaf nodes
  // ============================================================================

  test("Input returns embedded type"):
    val tt = tensorType(f32, 16, 784)
    val input = TensorExpr.Input("x", tt)
    assertEquals(ShapeInference.inferType(input), tt)

  test("Input with typeEnv override"):
    val tt = tensorType(f32, 16, 784)
    val override_ = tensorType(f32, 32, 784)
    val input = TensorExpr.Input("x", tt)
    assertEquals(ShapeInference.inferType(input, Map("x" -> override_)), override_)

  test("Constant returns embedded type"):
    val tt = tensorType(f32, 3, 3)
    val data = TensorData(f32, Vector(3, 3), Array.emptyByteArray)
    val c = TensorExpr.Constant("c", tt, data)
    assertEquals(ShapeInference.inferType(c), tt)

  // ============================================================================
  // Shape-preserving unary ops
  // ============================================================================

  test("Relu preserves shape"):
    val x = TensorExpr.Input("x", tensorType(f32, 16, 256))
    val relu = TensorExpr.Relu(x)
    assertEquals(ShapeInference.inferType(relu), tensorType(f32, 16, 256))

  test("Sigmoid preserves shape with dynamic dims"):
    val tt = dynamicType(f32, (Some("batch"), None), (None, Some(256)))
    val x = TensorExpr.Input("x", tt)
    val sig = TensorExpr.Sigmoid(x)
    assertEquals(ShapeInference.inferType(sig), tt)

  // ============================================================================
  // Binary broadcast ops
  // ============================================================================

  test("Add: same shapes"):
    val x = TensorExpr.Input("x", tensorType(f32, 16, 256))
    val y = TensorExpr.Input("y", tensorType(f32, 16, 256))
    assertEquals(ShapeInference.inferType(TensorExpr.Add(x, y)), tensorType(f32, 16, 256))

  test("Add: broadcasting (3,4) + (1,4)"):
    val x = TensorExpr.Input("x", tensorType(f32, 3, 4))
    val y = TensorExpr.Input("y", tensorType(f32, 1, 4))
    assertEquals(ShapeInference.inferType(TensorExpr.Add(x, y)), tensorType(f32, 3, 4))

  test("Add: different ranks (3,4) + (4,)"):
    val x = TensorExpr.Input("x", tensorType(f32, 3, 4))
    val y = TensorExpr.Input("y", tensorType(f32, 4))
    assertEquals(ShapeInference.inferType(TensorExpr.Add(x, y)), tensorType(f32, 3, 4))

  test("Add: with bias broadcasting (16, 256) + (256,)"):
    val x = TensorExpr.Input("x", tensorType(f32, 16, 256))
    val b = TensorExpr.Input("b", tensorType(f32, 256))
    assertEquals(ShapeInference.inferType(TensorExpr.Add(x, b)), tensorType(f32, 16, 256))

  // ============================================================================
  // Comparison ops
  // ============================================================================

  test("Equal produces Bool dtype"):
    val x = TensorExpr.Input("x", tensorType(f32, 3, 4))
    val y = TensorExpr.Input("y", tensorType(f32, 3, 4))
    val result = ShapeInference.inferType(TensorExpr.Equal(x, y))
    assertEquals(result.dtype, TensorDType.Bool)
    assertEquals(result.shape, Vector(Dim(3), Dim(4)))

  // ============================================================================
  // MatMul
  // ============================================================================

  test("MatMul: 2D x 2D"):
    val a = TensorExpr.Input("a", tensorType(f32, 4, 3))
    val b = TensorExpr.Input("b", tensorType(f32, 3, 5))
    val result = ShapeInference.inferType(TensorExpr.MatMul(a, b, false, false))
    assertEquals(result, tensorType(f32, 4, 5))

  test("MatMul: with transB"):
    val a = TensorExpr.Input("a", tensorType(f32, 4, 3))
    val b = TensorExpr.Input("b", tensorType(f32, 5, 3)) // transposed: (3, 5)
    val result = ShapeInference.inferType(TensorExpr.MatMul(a, b, false, true))
    assertEquals(result, tensorType(f32, 4, 5))

  test("MatMul: batched 3D x 3D"):
    val a = TensorExpr.Input("a", tensorType(f32, 2, 4, 3))
    val b = TensorExpr.Input("b", tensorType(f32, 2, 3, 5))
    val result = ShapeInference.inferType(TensorExpr.MatMul(a, b, false, false))
    assertEquals(result, tensorType(f32, 2, 4, 5))

  test("MatMul: 1D x 2D (vector-matrix)"):
    val a = TensorExpr.Input("a", tensorType(f32, 3))
    val b = TensorExpr.Input("b", tensorType(f32, 3, 5))
    val result = ShapeInference.inferType(TensorExpr.MatMul(a, b, false, false))
    assertEquals(result, tensorType(f32, 5))

  test("MatMul: 2D x 1D (matrix-vector)"):
    val a = TensorExpr.Input("a", tensorType(f32, 4, 3))
    val b = TensorExpr.Input("b", tensorType(f32, 3))
    val result = ShapeInference.inferType(TensorExpr.MatMul(a, b, false, false))
    assertEquals(result, tensorType(f32, 4))

  test("MatMul: dynamic batch dim"):
    val batchDim = Dim.Dynamic(Some("batch"))
    val a = TensorExpr.Input("a", TensorType(f32, Vector(batchDim, Dim(4), Dim(3))))
    val b = TensorExpr.Input("b", TensorType(f32, Vector(batchDim, Dim(3), Dim(5))))
    val result = ShapeInference.inferType(TensorExpr.MatMul(a, b, false, false))
    assertEquals(result.shape, Vector(batchDim, Dim(4), Dim(5)))

  // ============================================================================
  // Linear
  // ============================================================================

  test("Linear: basic"):
    val x = TensorExpr.Input("x", tensorType(f32, 16, 784))
    val w = TensorExpr.Parameter("w", tensorType(f32, 256, 784), None)
    val b = TensorExpr.Parameter("b", tensorType(f32, 256), None)
    val result = ShapeInference.inferType(TensorExpr.Linear(x, w, Some(b)))
    assertEquals(result, tensorType(f32, 16, 256))

  test("Linear: dynamic batch"):
    val batchDim = Dim.Dynamic(Some("batch"))
    val x = TensorExpr.Input("x", TensorType(f32, Vector(batchDim, Dim(784))))
    val w = TensorExpr.Parameter("w", tensorType(f32, 256, 784), None)
    val result = ShapeInference.inferType(TensorExpr.Linear(x, w, None))
    assertEquals(result.shape, Vector(batchDim, Dim(256)))

  // ============================================================================
  // Conv
  // ============================================================================

  test("Conv: ResNet first layer (224 → 112)"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 3, 224, 224))
    val w = TensorExpr.Parameter("w", tensorType(f32, 64, 3, 7, 7), None)
    val conv = TensorExpr.Conv(x, w, None, Vector(2, 2), Vector(3, 3, 3, 3), Vector(1, 1), 1)
    val result = ShapeInference.inferType(conv)
    assertEquals(result, tensorType(f32, 1, 64, 112, 112))

  test("Conv: dynamic batch"):
    val batchDim = Dim.Dynamic(Some("batch"))
    val x = TensorExpr.Input(
      "x",
      TensorType(f32, Vector(batchDim, Dim(3), Dim(32), Dim(32)))
    )
    val w = TensorExpr.Parameter("w", tensorType(f32, 16, 3, 3, 3), None)
    val conv = TensorExpr.Conv(x, w, None, Vector(1, 1), Vector(1, 1, 1, 1), Vector(1, 1), 1)
    val result = ShapeInference.inferType(conv)
    assertEquals(result.shape(0), batchDim)
    assertEquals(result.shape(1), Dim(16))
    assertEquals(result.shape(2), Dim(32))
    assertEquals(result.shape(3), Dim(32))

  // ============================================================================
  // Pooling
  // ============================================================================

  test("MaxPool: kernel 2, stride 2"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 64, 112, 112))
    val pool = TensorExpr.MaxPool(x, Vector(2, 2), Vector(2, 2), Vector(0, 0, 0, 0))
    val result = ShapeInference.inferType(pool)
    assertEquals(result, tensorType(f32, 1, 64, 56, 56))

  test("GlobalAvgPool"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 64, 7, 7))
    val result = ShapeInference.inferType(TensorExpr.GlobalAvgPool(x))
    assertEquals(result, tensorType(f32, 1, 64, 1, 1))

  test("AdaptiveAvgPool"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 512, 7, 7))
    val result = ShapeInference.inferType(TensorExpr.AdaptiveAvgPool(x, Vector(1, 1)))
    assertEquals(result, tensorType(f32, 1, 512, 1, 1))

  // ============================================================================
  // Reduction
  // ============================================================================

  test("ReduceSum: keepDims=true"):
    val x = TensorExpr.Input("x", tensorType(f32, 3, 4, 5))
    val result = ShapeInference.inferType(TensorExpr.ReduceSum(x, Vector(1), true))
    assertEquals(result, tensorType(f32, 3, 1, 5))

  test("ReduceMean: keepDims=false"):
    val x = TensorExpr.Input("x", tensorType(f32, 3, 4, 5))
    val result = ShapeInference.inferType(TensorExpr.ReduceMean(x, Vector(1), false))
    assertEquals(result, tensorType(f32, 3, 5))

  test("ReduceMax: negative axis"):
    val x = TensorExpr.Input("x", tensorType(f32, 3, 4, 5))
    val result = ShapeInference.inferType(TensorExpr.ReduceMax(x, Vector(-1), false))
    assertEquals(result, tensorType(f32, 3, 4))

  // ============================================================================
  // Shape manipulation
  // ============================================================================

  test("Reshape: static"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 3, 4))
    val result = ShapeInference.inferType(TensorExpr.Reshape(x, Vector(6, 4)))
    assertEquals(result, tensorType(f32, 6, 4))

  test("Reshape: with -1"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 3, 4))
    val result = ShapeInference.inferType(TensorExpr.Reshape(x, Vector(-1, 4)))
    assertEquals(result, tensorType(f32, 6, 4))

  test("Reshape: -1 with dynamic input"):
    val tt = dynamicType(f32, (Some("batch"), None), (None, Some(12)))
    val x = TensorExpr.Input("x", tt)
    val result = ShapeInference.inferType(TensorExpr.Reshape(x, Vector(-1, 4)))
    assertEquals(result.shape(0), Dim.Dynamic(None))
    assertEquals(result.shape(1), Dim.Static(4))

  test("Transpose"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 3, 4))
    val result = ShapeInference.inferType(TensorExpr.Transpose(x, Vector(2, 0, 1)))
    assertEquals(result, tensorType(f32, 4, 2, 3))

  test("Flatten: axis=1"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 3, 4))
    val result = ShapeInference.inferType(TensorExpr.Flatten(x, 1))
    assertEquals(result, tensorType(f32, 2, 12))

  test("Squeeze"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 3, 1, 4))
    val result = ShapeInference.inferType(TensorExpr.Squeeze(x, Vector(0, 2)))
    assertEquals(result, tensorType(f32, 3, 4))

  test("Unsqueeze"):
    val x = TensorExpr.Input("x", tensorType(f32, 3, 4))
    val result = ShapeInference.inferType(TensorExpr.Unsqueeze(x, Vector(0, 3)))
    assertEquals(result, tensorType(f32, 1, 3, 4, 1))

  test("TensorConcat"):
    val a = TensorExpr.Input("a", tensorType(f32, 2, 3))
    val b = TensorExpr.Input("b", tensorType(f32, 2, 5))
    val result = ShapeInference.inferType(TensorExpr.TensorConcat(Vector(a, b), 1))
    assertEquals(result, tensorType(f32, 2, 8))

  test("Gather"):
    val x = TensorExpr.Input("x", tensorType(f32, 10, 20))
    val idx = TensorExpr.Input("idx", tensorType(i64, 3))
    val result = ShapeInference.inferType(TensorExpr.Gather(x, idx, 0))
    assertEquals(result, tensorType(f32, 3, 20))

  test("Pad"):
    val x = TensorExpr.Input("x", tensorType(f32, 3, 4))
    val result =
      ShapeInference.inferType(TensorExpr.Pad(x, Vector(1, 2, 3, 4), PadMode.Constant, 0.0))
    // pad before: (1, 2), pad after: (3, 4) → (3+1+3, 4+2+4) = (7, 10)
    assertEquals(result, tensorType(f32, 7, 10))

  // ============================================================================
  // Cast
  // ============================================================================

  test("Cast: changes dtype, preserves shape"):
    val x = TensorExpr.Input("x", tensorType(f32, 3, 4))
    val result = ShapeInference.inferType(TensorExpr.Cast(x, TensorDType.Float16))
    assertEquals(result.dtype, TensorDType.Float16)
    assertEquals(result.shape, Vector(Dim(3), Dim(4)))

  // ============================================================================
  // LSTM / GRU
  // ============================================================================

  test("LSTM: unidirectional"):
    val x = TensorExpr.Input("x", tensorType(f32, 10, 4, 8)) // (seq, batch, input)
    val wIh = TensorExpr.Parameter("wIh", tensorType(f32, 1, 64, 8), None) // 4*hidden=64, input=8
    val wHh = TensorExpr.Parameter("wHh", tensorType(f32, 1, 64, 16), None)
    val lstm = TensorExpr.LSTM(
      x,
      wIh,
      wHh,
      None,
      hiddenSize = 16,
      numLayers = 1,
      bidirectional = false,
      dropout = 0.0
    )
    val result = ShapeInference.inferType(lstm)
    assertEquals(result, tensorType(f32, 10, 4, 16))

  test("LSTM: bidirectional"):
    val x = TensorExpr.Input("x", tensorType(f32, 10, 4, 8))
    val wIh = TensorExpr.Parameter("wIh", tensorType(f32, 2, 64, 8), None)
    val wHh = TensorExpr.Parameter("wHh", tensorType(f32, 2, 64, 16), None)
    val lstm = TensorExpr.LSTM(
      x,
      wIh,
      wHh,
      None,
      hiddenSize = 16,
      numLayers = 1,
      bidirectional = true,
      dropout = 0.0
    )
    val result = ShapeInference.inferType(lstm)
    assertEquals(result, tensorType(f32, 10, 4, 32)) // 16 * 2

  // ============================================================================
  // Loss functions
  // ============================================================================

  test("CrossEntropyLoss: Mean → scalar"):
    val logits = TensorExpr.Input("logits", tensorType(f32, 16, 10))
    val targets = TensorExpr.Input("targets", tensorType(i64, 16))
    val loss = TensorExpr.CrossEntropyLoss(logits, targets, None, Reduction.Mean)
    val result = ShapeInference.inferType(loss)
    assertEquals(result.shape, Vector.empty)

  test("CrossEntropyLoss: None → per-sample"):
    val logits = TensorExpr.Input("logits", tensorType(f32, 16, 10))
    val targets = TensorExpr.Input("targets", tensorType(i64, 16))
    val loss = TensorExpr.CrossEntropyLoss(logits, targets, None, Reduction.None)
    val result = ShapeInference.inferType(loss)
    assertEquals(result, tensorType(f32, 16))

  test("MSELoss: Mean → scalar"):
    val pred = TensorExpr.Input("pred", tensorType(f32, 16, 1))
    val target = TensorExpr.Input("target", tensorType(f32, 16, 1))
    val loss = TensorExpr.MSELoss(pred, target, Reduction.Mean)
    assertEquals(ShapeInference.inferType(loss).shape, Vector.empty)

  // ============================================================================
  // Embedding
  // ============================================================================

  test("Embedding"):
    val idx = TensorExpr.Input("idx", tensorType(i64, 4, 10)) // batch=4, seq=10
    val w = TensorExpr.Parameter("w", tensorType(f32, 1000, 128), None) // vocab=1000, embed=128
    val result = ShapeInference.inferType(TensorExpr.Embedding(idx, w, None))
    assertEquals(result, tensorType(f32, 4, 10, 128))
    assertEquals(result.dtype, f32) // dtype from weight, not indices

  // ============================================================================
  // OpaqueOp
  // ============================================================================

  test("OpaqueOp returns explicit outputType"):
    val x = TensorExpr.Input("x", tensorType(f32, 3, 4))
    val outType = tensorType(f32, 3, 8)
    val op = TensorExpr.OpaqueOp("custom_op", Vector(x), Map.empty, outType)
    assertEquals(ShapeInference.inferType(op), outType)

  // ============================================================================
  // Graph-level inference
  // ============================================================================

  test("inferGraphTypes: MLP"):
    val x = TensorExpr.Input("x", tensorType(f32, 16, 784))
    val w1 = TensorExpr.Parameter("w1", tensorType(f32, 256, 784), None)
    val b1 = TensorExpr.Parameter("b1", tensorType(f32, 256), None)
    val w2 = TensorExpr.Parameter("w2", tensorType(f32, 10, 256), None)
    val b2 = TensorExpr.Parameter("b2", tensorType(f32, 10), None)

    val linear1 = TensorExpr.Linear(x, w1, Some(b1))
    val relu = TensorExpr.Relu(linear1)
    val linear2 = TensorExpr.Linear(relu, w2, Some(b2))

    // Intentionally use placeholder types (all zeros) to verify inference overwrites them
    val placeholder = tensorType(f32, 0)
    val graph = ComputeGraph(
      name = "mlp",
      inputs = Vector(GraphIO("x", tensorType(f32, 16, 784))),
      outputs = Vector(GraphIO("logits", placeholder)),
      nodes = Vector(
        NamedNode("linear1", linear1, placeholder),
        NamedNode("relu1", relu, placeholder),
        NamedNode("linear2", linear2, placeholder)
      )
    )

    val inferred = ShapeInference.inferGraphTypes(graph)

    assertEquals(inferred.nodes(0).outputType, tensorType(f32, 16, 256))
    assertEquals(inferred.nodes(1).outputType, tensorType(f32, 16, 256))
    assertEquals(inferred.nodes(2).outputType, tensorType(f32, 16, 10))

  test("inferGraphTypes: Conv + BN + ReLU"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 3, 224, 224))
    val convW = TensorExpr.Parameter("conv.weight", tensorType(f32, 64, 3, 7, 7), None)
    val convB = TensorExpr.Parameter("conv.bias", tensorType(f32, 64), None)
    val bnScale = TensorExpr.Parameter("bn.scale", tensorType(f32, 64), None)
    val bnBias = TensorExpr.Parameter("bn.bias", tensorType(f32, 64), None)
    val bnMean = TensorExpr.Parameter("bn.mean", tensorType(f32, 64), None)
    val bnVar = TensorExpr.Parameter("bn.var", tensorType(f32, 64), None)

    val conv =
      TensorExpr.Conv(x, convW, Some(convB), Vector(2, 2), Vector(3, 3, 3, 3), Vector(1, 1), 1)
    val bn = TensorExpr.BatchNorm(conv, bnScale, bnBias, bnMean, bnVar, 1e-5, 0.1, false)
    val relu = TensorExpr.Relu(bn)

    val placeholder = tensorType(f32, 0)
    val graph = ComputeGraph(
      name = "conv_bn_relu",
      inputs = Vector(GraphIO("x", tensorType(f32, 1, 3, 224, 224))),
      outputs = Vector(GraphIO("out", placeholder)),
      nodes = Vector(
        NamedNode("conv1", conv, placeholder),
        NamedNode("bn1", bn, placeholder),
        NamedNode("relu1", relu, placeholder)
      )
    )

    val inferred = ShapeInference.inferGraphTypes(graph)

    val expected = tensorType(f32, 1, 64, 112, 112)
    assertEquals(inferred.nodes(0).outputType, expected)
    assertEquals(inferred.nodes(1).outputType, expected)
    assertEquals(inferred.nodes(2).outputType, expected)

  test("validateGraphTypes: detects mismatch"):
    val x = TensorExpr.Input("x", tensorType(f32, 16, 784))
    val w = TensorExpr.Parameter("w", tensorType(f32, 256, 784), None)
    val linear = TensorExpr.Linear(x, w, None)

    val wrongType = tensorType(f32, 16, 128) // wrong: should be (16, 256)
    val graph = ComputeGraph(
      name = "test",
      inputs = Vector(GraphIO("x", tensorType(f32, 16, 784))),
      outputs = Vector(GraphIO("out", wrongType)),
      nodes = Vector(NamedNode("linear", linear, wrongType))
    )

    val mismatches = ShapeInference.validateGraphTypes(graph)
    assertEquals(mismatches.size, 1)
    assertEquals(mismatches.head._1, "linear")
    assertEquals(mismatches.head._2, wrongType) // declared
    assertEquals(mismatches.head._3, tensorType(f32, 16, 256)) // inferred

  test("validateGraphTypes: no mismatch when correct"):
    val x = TensorExpr.Input("x", tensorType(f32, 16, 784))
    val w = TensorExpr.Parameter("w", tensorType(f32, 256, 784), None)
    val linear = TensorExpr.Linear(x, w, None)

    val correctType = tensorType(f32, 16, 256)
    val graph = ComputeGraph(
      name = "test",
      inputs = Vector(GraphIO("x", tensorType(f32, 16, 784))),
      outputs = Vector(GraphIO("out", correctType)),
      nodes = Vector(NamedNode("linear", linear, correctType))
    )

    val mismatches = ShapeInference.validateGraphTypes(graph)
    assertEquals(mismatches.size, 0)
