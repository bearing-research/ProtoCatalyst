package protocatalyst.ml.codec

import protocatalyst.ml.artifact._
import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph._
import protocatalyst.ml.types._

class MLProtoConverterSuite extends munit.FunSuite:

  private val f32 = TensorDType.Float32
  private val i64 = TensorDType.Int64

  private def tensorType(dtype: TensorDType, dims: Int*): TensorType =
    TensorType(dtype, dims.map(Dim(_)).toVector)

  private val vecF32 = tensorType(f32, 10)

  // Helper: roundtrip a TensorExpr through protobuf
  private def roundtrip(expr: TensorExpr): TensorExpr =
    val proto = MLProtoConverter.toProtoExpr(expr)
    MLProtoConverter.fromProtoExpr(proto)

  // Helper: roundtrip a ComputeGraph through protobuf
  private def roundtripGraph(graph: ComputeGraph): ComputeGraph =
    val proto = MLProtoConverter.toProtoGraph(graph)
    MLProtoConverter.fromProtoGraph(proto)

  // ============================================================================
  // TensorDType roundtrip
  // ============================================================================

  test("TensorDType roundtrip: all variants"):
    for dt <- TensorDType.values do
      val proto = MLProtoConverter.toProtoDType(dt)
      val back = MLProtoConverter.fromProtoDType(proto)
      assertEquals(back, dt, s"Failed for $dt")

  // ============================================================================
  // TensorType roundtrip
  // ============================================================================

  test("TensorType roundtrip: scalar"):
    val tt = TensorType(f32, Vector.empty)
    val back = MLProtoConverter.fromProtoTensorType(MLProtoConverter.toProtoTensorType(tt))
    assertEquals(back, tt)

  test("TensorType roundtrip: with static dims"):
    val tt = tensorType(f32, 1, 3, 224, 224)
    val back = MLProtoConverter.fromProtoTensorType(MLProtoConverter.toProtoTensorType(tt))
    assertEquals(back, tt)

  test("TensorType roundtrip: with dynamic dims"):
    val tt = TensorType(f32, Vector(Dim.dynamic("batch"), Dim(3), Dim(224), Dim(224)))
    val back = MLProtoConverter.fromProtoTensorType(MLProtoConverter.toProtoTensorType(tt))
    assertEquals(back, tt)

  test("TensorType roundtrip: with layout"):
    val tt = TensorType(f32, Vector(Dim(1), Dim(3), Dim(224), Dim(224)), DataLayout.NCHW)
    val back = MLProtoConverter.fromProtoTensorType(MLProtoConverter.toProtoTensorType(tt))
    assertEquals(back, tt)

  // ============================================================================
  // TensorData roundtrip
  // ============================================================================

  test("TensorData roundtrip"):
    val td = TensorData(f32, Vector(2, 3), Array[Byte](1, 2, 3, 4, 5, 6))
    val back = MLProtoConverter.fromProtoTensorData(MLProtoConverter.toProtoTensorData(td))
    assertEquals(back, td)

  // ============================================================================
  // Initializer roundtrip
  // ============================================================================

  test("Initializer roundtrip: all variants"):
    val inits = Seq(
      Initializer.Zeros,
      Initializer.Ones,
      Initializer.Xavier(2.0),
      Initializer.Kaiming("fan_out", "leaky_relu"),
      Initializer.Normal(0.0, 0.02),
      Initializer.Uniform(-0.1, 0.1)
    )
    for init <- inits do
      val back = MLProtoConverter.fromProtoInitializer(MLProtoConverter.toProtoInitializer(init))
      assertEquals(back, init, s"Failed for $init")

  // ============================================================================
  // Leaf nodes roundtrip
  // ============================================================================

  test("Input roundtrip"):
    val expr = TensorExpr.Input("x", tensorType(f32, 16, 784))
    assertEquals(roundtrip(expr), expr)

  test("Constant roundtrip"):
    val data = TensorData(f32, Vector(2), Array[Byte](0, 0, 0, 0, 0, 0, 0, 0))
    val expr = TensorExpr.Constant("bias", tensorType(f32, 2), data)
    assertEquals(roundtrip(expr), expr)

  test("Parameter roundtrip: with initializer"):
    val expr = TensorExpr.Parameter(
      "w",
      tensorType(f32, 256, 784),
      Some(Initializer.Kaiming("fan_in", "relu"))
    )
    assertEquals(roundtrip(expr), expr)

  test("Parameter roundtrip: without initializer"):
    val expr = TensorExpr.Parameter("w", tensorType(f32, 256, 784), None)
    assertEquals(roundtrip(expr), expr)

  // ============================================================================
  // Linear algebra roundtrip
  // ============================================================================

  test("MatMul roundtrip"):
    val a = TensorExpr.Input("a", tensorType(f32, 3, 4))
    val b = TensorExpr.Input("b", tensorType(f32, 4, 5))
    val expr = TensorExpr.MatMul(a, b, transA = true, transB = false)
    assertEquals(roundtrip(expr), expr)

  test("Gemm roundtrip"):
    val a = TensorExpr.Input("a", tensorType(f32, 3, 4))
    val b = TensorExpr.Input("b", tensorType(f32, 4, 5))
    val c = TensorExpr.Input("c", tensorType(f32, 3, 5))
    val expr = TensorExpr.Gemm(a, b, Some(c), 0.5, 2.0, transA = false, transB = true)
    assertEquals(roundtrip(expr), expr)

  test("Linear roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 16, 784))
    val w = TensorExpr.Parameter("w", tensorType(f32, 256, 784), None)
    val bias = TensorExpr.Parameter("b", tensorType(f32, 256), Some(Initializer.Zeros))
    val expr = TensorExpr.Linear(x, w, Some(bias))
    assertEquals(roundtrip(expr), expr)

  // ============================================================================
  // Activations roundtrip
  // ============================================================================

  test("activation roundtrip"):
    val x = TensorExpr.Input("x", vecF32)
    val exprs = Seq(
      TensorExpr.Relu(x),
      TensorExpr.LeakyRelu(x, 0.01),
      TensorExpr.Sigmoid(x),
      TensorExpr.Tanh(x),
      TensorExpr.Softmax(x, axis = -1),
      TensorExpr.LogSoftmax(x, axis = 1),
      TensorExpr.Gelu(x, approximate = true),
      TensorExpr.Silu(x),
      TensorExpr.Elu(x, 1.0),
      TensorExpr.HardSwish(x)
    )
    for expr <- exprs do assertEquals(roundtrip(expr), expr, s"Failed for $expr")

  // ============================================================================
  // Arithmetic roundtrip
  // ============================================================================

  test("arithmetic roundtrip"):
    val a = TensorExpr.Input("a", vecF32)
    val b = TensorExpr.Input("b", vecF32)
    val exprs = Seq(
      TensorExpr.Add(a, b),
      TensorExpr.Sub(a, b),
      TensorExpr.Mul(a, b),
      TensorExpr.Div(a, b),
      TensorExpr.Pow(a, b),
      TensorExpr.Sqrt(a),
      TensorExpr.Neg(a),
      TensorExpr.Abs(a),
      TensorExpr.Exp(a),
      TensorExpr.Log(a),
      TensorExpr.Clip(a, Some(0.0), Some(6.0))
    )
    for expr <- exprs do assertEquals(roundtrip(expr), expr, s"Failed for $expr")

  // ============================================================================
  // Normalization roundtrip
  // ============================================================================

  test("BatchNorm roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 64, 112, 112))
    val s = TensorExpr.Parameter("scale", tensorType(f32, 64), None)
    val b = TensorExpr.Parameter("bias", tensorType(f32, 64), None)
    val m = TensorExpr.Parameter("mean", tensorType(f32, 64), None)
    val v = TensorExpr.Parameter("var", tensorType(f32, 64), None)
    val expr = TensorExpr.BatchNorm(x, s, b, m, v, 1e-5, 0.1, training = false)
    assertEquals(roundtrip(expr), expr)

  test("LayerNorm roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 16, 512))
    val expr = TensorExpr.LayerNorm(x, Vector(512), None, None, 1e-5)
    assertEquals(roundtrip(expr), expr)

  // ============================================================================
  // Convolution roundtrip
  // ============================================================================

  test("Conv roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 3, 224, 224))
    val w = TensorExpr.Parameter("w", tensorType(f32, 64, 3, 7, 7), None)
    val expr = TensorExpr.Conv(
      x,
      w,
      None,
      strides = Vector(2, 2),
      pads = Vector(3, 3, 3, 3),
      dilations = Vector(1, 1),
      group = 1
    )
    assertEquals(roundtrip(expr), expr)

  // ============================================================================
  // Shape manipulation roundtrip
  // ============================================================================

  test("shape ops roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 3, 4))
    val exprs = Seq(
      TensorExpr.Reshape(x, Vector(6, 4)),
      TensorExpr.Transpose(x, Vector(2, 0, 1)),
      TensorExpr.Flatten(x, 1),
      TensorExpr.Squeeze(x, Vector(0)),
      TensorExpr.Unsqueeze(x, Vector(0))
    )
    for expr <- exprs do assertEquals(roundtrip(expr), expr, s"Failed for $expr")

  // ============================================================================
  // Loss roundtrip
  // ============================================================================

  test("loss functions roundtrip"):
    val pred = TensorExpr.Input("pred", tensorType(f32, 16, 10))
    val target = TensorExpr.Input("target", tensorType(i64, 16))
    val exprs = Seq(
      TensorExpr.CrossEntropyLoss(pred, target, None, Reduction.Mean),
      TensorExpr.MSELoss(pred, target, Reduction.Sum),
      TensorExpr.L1Loss(pred, target, Reduction.None)
    )
    for expr <- exprs do assertEquals(roundtrip(expr), expr, s"Failed for $expr")

  // ============================================================================
  // Recurrent roundtrip
  // ============================================================================

  test("LSTM roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 32, 10, 128))
    val wih = TensorExpr.Parameter("w_ih", tensorType(f32, 1024, 128), None)
    val whh = TensorExpr.Parameter("w_hh", tensorType(f32, 1024, 256), None)
    val expr = TensorExpr.LSTM(
      x,
      wih,
      whh,
      None,
      hiddenSize = 256,
      numLayers = 2,
      bidirectional = true,
      dropout = 0.1
    )
    assertEquals(roundtrip(expr), expr)

  // ============================================================================
  // Attention roundtrip
  // ============================================================================

  test("ScaledDotProductAttention roundtrip"):
    val q = TensorExpr.Input("q", tensorType(f32, 1, 8, 64, 64))
    val k = TensorExpr.Input("k", tensorType(f32, 1, 8, 64, 64))
    val v = TensorExpr.Input("v", tensorType(f32, 1, 8, 64, 64))
    val expr = TensorExpr.ScaledDotProductAttention(q, k, v, None, dropout = 0.0, isCausal = true)
    assertEquals(roundtrip(expr), expr)

  // ============================================================================
  // OpaqueOp roundtrip
  // ============================================================================

  test("OpaqueOp roundtrip"):
    val x = TensorExpr.Input("x", vecF32)
    val expr = TensorExpr.OpaqueOp(
      "custom::my_op",
      Vector(x),
      Map("alpha" -> OpAttribute.FloatAttr(0.5), "size" -> OpAttribute.IntAttr(42)),
      vecF32
    )
    assertEquals(roundtrip(expr), expr)

  // ============================================================================
  // ComputeGraph roundtrip
  // ============================================================================

  test("ComputeGraph roundtrip: two-layer MLP"):
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
      ),
      opsetVersion = 13
    )

    val back = roundtripGraph(graph)
    assertEquals(back.name, graph.name)
    assertEquals(back.inputs, graph.inputs)
    assertEquals(back.outputs, graph.outputs)
    assertEquals(back.opsetVersion, graph.opsetVersion)
    assertEquals(back.nodes.size, graph.nodes.size)
    for (orig, rt) <- graph.nodes.zip(back.nodes) do
      assertEquals(rt.name, orig.name)
      assertEquals(rt.outputType, orig.outputType)
      assertEquals(rt.expr, orig.expr)

  // ============================================================================
  // CompiledMLArtifact roundtrip
  // ============================================================================

  test("CompiledMLArtifact roundtrip"):
    val x = TensorExpr.Input("x", vecF32)
    val relu = TensorExpr.Relu(x)
    val graph = ComputeGraph(
      name = "simple",
      inputs = Vector(GraphIO("x", vecF32)),
      outputs = Vector(GraphIO("out", vecF32)),
      nodes = Vector(NamedNode("relu", relu, vecF32))
    )

    val artifact = CompiledMLArtifact(
      formatVersion = MLArtifactVersion(0, 1, 0),
      protocatalystVersion = "0.1.0-test",
      compiledAt = 1234567890L,
      contentHash = 42L,
      graph = graph,
      sourceInfo = Some(MLSourceInfo("test.scala", 42))
    )

    val proto = MLProtoConverter.toProto(artifact)
    val back = MLProtoConverter.fromProto(proto)
    assertEquals(back.formatVersion, artifact.formatVersion)
    assertEquals(back.protocatalystVersion, artifact.protocatalystVersion)
    assertEquals(back.compiledAt, artifact.compiledAt)
    assertEquals(back.graph.name, artifact.graph.name)
    assertEquals(back.sourceInfo, artifact.sourceInfo)

  // ============================================================================
  // Convolution: ConvTranspose roundtrip
  // ============================================================================

  test("ConvTranspose roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 64, 7, 7))
    val w = TensorExpr.Parameter("w", tensorType(f32, 64, 32, 4, 4), None)
    val b = TensorExpr.Parameter("b", tensorType(f32, 32), Some(Initializer.Zeros))
    val expr = TensorExpr.ConvTranspose(
      x,
      w,
      Some(b),
      strides = Vector(2, 2),
      pads = Vector(1, 1, 1, 1),
      outputPads = Vector(0, 0),
      dilations = Vector(1, 1),
      group = 1
    )
    assertEquals(roundtrip(expr), expr)

  // ============================================================================
  // Pooling roundtrip
  // ============================================================================

  test("MaxPool roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 64, 112, 112))
    val expr = TensorExpr.MaxPool(
      x,
      kernelSize = Vector(3, 3),
      strides = Vector(2, 2),
      pads = Vector(1, 1, 1, 1)
    )
    assertEquals(roundtrip(expr), expr)

  test("AvgPool roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 64, 56, 56))
    val expr = TensorExpr.AvgPool(
      x,
      kernelSize = Vector(2, 2),
      strides = Vector(2, 2),
      pads = Vector(0, 0, 0, 0),
      countIncludePad = true
    )
    assertEquals(roundtrip(expr), expr)

  test("GlobalAvgPool roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 512, 7, 7))
    val expr = TensorExpr.GlobalAvgPool(x)
    assertEquals(roundtrip(expr), expr)

  test("AdaptiveAvgPool roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 512, 14, 14))
    val expr = TensorExpr.AdaptiveAvgPool(x, outputSize = Vector(7, 7))
    assertEquals(roundtrip(expr), expr)

  // ============================================================================
  // Normalization: GroupNorm, InstanceNorm roundtrip
  // ============================================================================

  test("GroupNorm roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 64, 56, 56))
    val w = TensorExpr.Parameter("w", tensorType(f32, 64), None)
    val b = TensorExpr.Parameter("b", tensorType(f32, 64), None)
    val expr =
      TensorExpr.GroupNorm(x, numGroups = 8, weight = Some(w), bias = Some(b), epsilon = 1e-5)
    assertEquals(roundtrip(expr), expr)

  test("InstanceNorm roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 64, 56, 56))
    val s = TensorExpr.Parameter("scale", tensorType(f32, 64), None)
    val b = TensorExpr.Parameter("bias", tensorType(f32, 64), None)
    val expr = TensorExpr.InstanceNorm(x, s, b, epsilon = 1e-5)
    assertEquals(roundtrip(expr), expr)

  // ============================================================================
  // Reduction roundtrip
  // ============================================================================

  test("reduction ops roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 4, 8, 16))
    val exprs = Seq(
      TensorExpr.ReduceSum(x, axes = Vector(1), keepDims = true),
      TensorExpr.ReduceMean(x, axes = Vector(2), keepDims = false),
      TensorExpr.ReduceMax(x, axes = Vector(0, 2), keepDims = true),
      TensorExpr.ReduceMin(x, axes = Vector(1), keepDims = false),
      TensorExpr.ReduceProd(x, axes = Vector(0), keepDims = true)
    )
    for expr <- exprs do assertEquals(roundtrip(expr), expr, s"Failed for $expr")

  // ============================================================================
  // Shape manipulation: additional ops roundtrip
  // ============================================================================

  test("TensorConcat roundtrip"):
    val a = TensorExpr.Input("a", tensorType(f32, 2, 3))
    val b = TensorExpr.Input("b", tensorType(f32, 2, 5))
    val expr = TensorExpr.TensorConcat(Vector(a, b), axis = 1)
    assertEquals(roundtrip(expr), expr)

  test("Split roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 6, 4))
    val expr = TensorExpr.Split(x, axis = 0, splitSizes = Vector(2, 2, 2))
    assertEquals(roundtrip(expr), expr)

  test("TensorSlice roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 10, 20))
    val expr = TensorExpr.TensorSlice(
      x,
      starts = Vector(1, 2),
      ends = Vector(5, 10),
      axes = Vector(0, 1),
      steps = Vector(1, 2)
    )
    assertEquals(roundtrip(expr), expr)

  test("Gather roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 100, 64))
    val idx = TensorExpr.Input("idx", tensorType(i64, 16))
    val expr = TensorExpr.Gather(x, idx, axis = 0)
    assertEquals(roundtrip(expr), expr)

  test("Scatter roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 10, 5))
    val idx = TensorExpr.Input("idx", tensorType(i64, 3, 5))
    val upd = TensorExpr.Input("updates", tensorType(f32, 3, 5))
    val expr = TensorExpr.Scatter(x, idx, upd, axis = 0)
    assertEquals(roundtrip(expr), expr)

  test("Pad roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 3, 224, 224))
    val expr = TensorExpr.Pad(
      x,
      pads = Vector(0, 0, 1, 1, 0, 0, 1, 1),
      mode = PadMode.Reflect,
      constantValue = 0.0
    )
    assertEquals(roundtrip(expr), expr)

  test("Expand roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 64))
    val expr = TensorExpr.Expand(x, shape = Vector(16, 64))
    assertEquals(roundtrip(expr), expr)

  // ============================================================================
  // Recurrent: GRU roundtrip
  // ============================================================================

  test("GRU roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 32, 10, 128))
    val wih = TensorExpr.Parameter("w_ih", tensorType(f32, 768, 128), None)
    val whh = TensorExpr.Parameter("w_hh", tensorType(f32, 768, 256), None)
    val bias = TensorExpr.Parameter("b", tensorType(f32, 1536), Some(Initializer.Zeros))
    val expr = TensorExpr.GRU(
      x,
      wih,
      whh,
      Some(bias),
      hiddenSize = 256,
      numLayers = 1,
      bidirectional = false,
      dropout = 0.0
    )
    assertEquals(roundtrip(expr), expr)

  // ============================================================================
  // Attention: MultiHeadAttention roundtrip
  // ============================================================================

  test("MultiHeadAttention roundtrip"):
    val q = TensorExpr.Input("q", tensorType(f32, 1, 16, 512))
    val k = TensorExpr.Input("k", tensorType(f32, 1, 16, 512))
    val v = TensorExpr.Input("v", tensorType(f32, 1, 16, 512))
    val wQ = TensorExpr.Parameter("wQ", tensorType(f32, 512, 512), None)
    val wK = TensorExpr.Parameter("wK", tensorType(f32, 512, 512), None)
    val wV = TensorExpr.Parameter("wV", tensorType(f32, 512, 512), None)
    val wO = TensorExpr.Parameter("wO", tensorType(f32, 512, 512), None)
    val expr = TensorExpr.MultiHeadAttention(
      q,
      k,
      v,
      wQ,
      wK,
      wV,
      wO,
      numHeads = 8,
      mask = None,
      dropout = 0.1
    )
    assertEquals(roundtrip(expr), expr)

  // ============================================================================
  // Embedding roundtrip
  // ============================================================================

  test("Embedding roundtrip"):
    val idx = TensorExpr.Input("token_ids", tensorType(i64, 1, 128))
    val w = TensorExpr.Parameter("embed_w", tensorType(f32, 30000, 768), None)
    val expr = TensorExpr.Embedding(idx, w, paddingIdx = Some(0))
    assertEquals(roundtrip(expr), expr)

  test("Embedding roundtrip: no padding idx"):
    val idx = TensorExpr.Input("ids", tensorType(i64, 16))
    val w = TensorExpr.Parameter("w", tensorType(f32, 10000, 256), None)
    val expr = TensorExpr.Embedding(idx, w, paddingIdx = None)
    assertEquals(roundtrip(expr), expr)

  // ============================================================================
  // Regularization: Dropout roundtrip
  // ============================================================================

  test("Dropout roundtrip"):
    val x = TensorExpr.Input("x", tensorType(f32, 16, 256))
    val expr = TensorExpr.Dropout(x, ratio = 0.5, training = true)
    assertEquals(roundtrip(expr), expr)

  // ============================================================================
  // Loss: BCELoss, BCEWithLogitsLoss roundtrip
  // ============================================================================

  test("BCELoss roundtrip"):
    val pred = TensorExpr.Input("pred", tensorType(f32, 16, 1))
    val target = TensorExpr.Input("target", tensorType(f32, 16, 1))
    val w = TensorExpr.Parameter("w", tensorType(f32, 16, 1), None)
    val expr = TensorExpr.BCELoss(pred, target, Some(w), Reduction.Mean)
    assertEquals(roundtrip(expr), expr)

  test("BCEWithLogitsLoss roundtrip"):
    val pred = TensorExpr.Input("pred", tensorType(f32, 16, 1))
    val target = TensorExpr.Input("target", tensorType(f32, 16, 1))
    val expr = TensorExpr.BCEWithLogitsLoss(pred, target, None, Reduction.Sum)
    assertEquals(roundtrip(expr), expr)

  // ============================================================================
  // Comparison / logical roundtrip
  // ============================================================================

  test("comparison ops roundtrip"):
    val a = TensorExpr.Input("a", vecF32)
    val b = TensorExpr.Input("b", vecF32)
    val exprs = Seq(
      TensorExpr.Equal(a, b),
      TensorExpr.Greater(a, b),
      TensorExpr.Less(a, b)
    )
    for expr <- exprs do assertEquals(roundtrip(expr), expr, s"Failed for $expr")

  test("Where roundtrip"):
    val cond = TensorExpr.Input("cond", tensorType(TensorDType.Bool, 10))
    val x = TensorExpr.Input("x", vecF32)
    val y = TensorExpr.Input("y", vecF32)
    val expr = TensorExpr.Where(cond, x, y)
    assertEquals(roundtrip(expr), expr)

  // ============================================================================
  // Type casting roundtrip
  // ============================================================================

  test("Cast roundtrip"):
    val x = TensorExpr.Input("x", vecF32)
    val expr = TensorExpr.Cast(x, TensorDType.Float16)
    assertEquals(roundtrip(expr), expr)

  // ============================================================================
  // Protobuf binary roundtrip
  // ============================================================================

  test("binary serialization roundtrip"):
    val x = TensorExpr.Input("x", vecF32)
    val relu = TensorExpr.Relu(x)
    val graph = ComputeGraph(
      name = "binary_test",
      inputs = Vector(GraphIO("x", vecF32)),
      outputs = Vector(GraphIO("out", vecF32)),
      nodes = Vector(NamedNode("relu", relu, vecF32))
    )

    val artifact = CompiledMLArtifact(
      formatVersion = MLArtifactVersion(0, 1, 0),
      protocatalystVersion = "test",
      compiledAt = 0L,
      contentHash = 0L,
      graph = graph,
      sourceInfo = None
    )

    // Serialize to bytes and back
    val bytes = MLProtoConverter.toProto(artifact).toByteArray
    val parsed = io.protocatalyst.proto.v1.CompiledMLArtifactMsg.parseFrom(bytes)
    val back = MLProtoConverter.fromProto(parsed)
    assertEquals(back.graph.name, "binary_test")
    assertEquals(back.graph.nodes.size, 1)
