package protocatalyst.ml.codec

import onnx.{Onnx => ox}

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph._
import protocatalyst.ml.types._

class OnnxExporterSuite extends munit.FunSuite:

  private val f32 = TensorDType.Float32
  private val i64 = TensorDType.Int64

  private def tensorType(dtype: TensorDType, dims: Int*): TensorType =
    TensorType(dtype, dims.map(Dim(_)).toVector)

  private val vecF32 = tensorType(f32, 10)
  private val matF32 = tensorType(f32, 4, 10)

  /** Build a minimal graph with a single operation. */
  private def singleOpGraph(
      expr: TensorExpr,
      inputs: Vector[GraphIO],
      outputType: TensorType
  ): ComputeGraph =
    ComputeGraph(
      name = "test_graph",
      inputs = inputs,
      outputs = Vector(GraphIO("output", outputType)),
      nodes = Vector(NamedNode("output", expr, outputType))
    )

  /** Export and parse back to ModelProto for verification. */
  private def exportAndParse(graph: ComputeGraph): ox.ModelProto =
    val bytes = OnnxExporter.toBytes(graph)
    ox.ModelProto.parseFrom(bytes)

  /** Get the first (and usually only) non-constant node from the exported graph. */
  private def firstOpNode(model: ox.ModelProto): ox.NodeProto =
    val nodes = (0 until model.getGraph.getNodeCount)
      .map(model.getGraph.getNode)
    nodes.head

  /** Get all op types from the exported graph. */
  private def opTypes(model: ox.ModelProto): Seq[String] =
    (0 until model.getGraph.getNodeCount)
      .map(model.getGraph.getNode(_).getOpType)

  // ============================================================================
  // Structural tests
  // ============================================================================

  test("ModelProto structure: ir_version, opset, producer"):
    val x = TensorExpr.Input("x", vecF32)
    val graph = singleOpGraph(
      TensorExpr.Relu(x),
      Vector(GraphIO("x", vecF32)),
      vecF32
    )
    val model = exportAndParse(graph)
    assertEquals(model.getIrVersion, 8L)
    assertEquals(model.getOpsetImportCount, 1)
    assertEquals(model.getOpsetImport(0).getDomain, "")
    assertEquals(model.getOpsetImport(0).getVersion, 17L)
    assertEquals(model.getProducerName, "ProtoCatalyst")
    assertEquals(model.getGraph.getName, "test_graph")

  test("graph inputs and outputs"):
    val x = TensorExpr.Input("x", vecF32)
    val graph = singleOpGraph(
      TensorExpr.Relu(x),
      Vector(GraphIO("x", vecF32)),
      vecF32
    )
    val model = exportAndParse(graph)
    // Graph should have at least the declared input
    val inputNames = (0 until model.getGraph.getInputCount)
      .map(model.getGraph.getInput(_).getName)
    assert(inputNames.contains("x"))
    assertEquals(model.getGraph.getOutputCount, 1)
    assertEquals(model.getGraph.getOutput(0).getName, "output")

  // ============================================================================
  // Type mapping
  // ============================================================================

  test("TensorDType mapping: all variants"):
    val expected = Map(
      TensorDType.Float16 -> 10,
      TensorDType.Float32 -> 1,
      TensorDType.Float64 -> 11,
      TensorDType.BFloat16 -> 16,
      TensorDType.Int8 -> 3,
      TensorDType.Int16 -> 5,
      TensorDType.Int32 -> 6,
      TensorDType.Int64 -> 7,
      TensorDType.UInt8 -> 2,
      TensorDType.Bool -> 9,
      TensorDType.Complex64 -> 14,
      TensorDType.Complex128 -> 15
    )
    // Verify by exporting a graph with each dtype
    for (dtype, onnxType) <- expected do
      val tt = TensorType(dtype, Vector(Dim(1)))
      val x = TensorExpr.Input("x", tt)
      val graph = singleOpGraph(TensorExpr.Relu(x), Vector(GraphIO("x", tt)), tt)
      val model = exportAndParse(graph)
      val inputType = model.getGraph.getInput(0).getType.getTensorType.getElemType
      assertEquals(inputType, onnxType, s"Failed for $dtype")

  // ============================================================================
  // Leaf node tests
  // ============================================================================

  test("Constant appears as initializer"):
    val data = TensorData(f32, Vector(3), Array[Byte](0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
    val c = TensorExpr.Constant("weights", tensorType(f32, 3), data)
    val x = TensorExpr.Input("x", tensorType(f32, 3))
    val graph = singleOpGraph(
      TensorExpr.Add(x, c),
      Vector(GraphIO("x", tensorType(f32, 3))),
      tensorType(f32, 3)
    )
    val model = exportAndParse(graph)
    val initNames = (0 until model.getGraph.getInitializerCount)
      .map(model.getGraph.getInitializer(_).getName)
    assert(initNames.contains("weights"))

  test("Parameter appears as initializer"):
    val p = TensorExpr.Parameter("bias", tensorType(f32, 10), Some(Initializer.Zeros))
    val x = TensorExpr.Input("x", tensorType(f32, 10))
    val graph = singleOpGraph(
      TensorExpr.Add(x, p),
      Vector(GraphIO("x", tensorType(f32, 10))),
      tensorType(f32, 10)
    )
    val model = exportAndParse(graph)
    val initNames = (0 until model.getGraph.getInitializerCount)
      .map(model.getGraph.getInitializer(_).getName)
    assert(initNames.contains("bias"))

  // ============================================================================
  // Category A: Direct mapping tests
  // ============================================================================

  test("Relu"):
    val x = TensorExpr.Input("x", vecF32)
    val model =
      exportAndParse(singleOpGraph(TensorExpr.Relu(x), Vector(GraphIO("x", vecF32)), vecF32))
    val node = firstOpNode(model)
    assertEquals(node.getOpType, "Relu")
    assertEquals(node.getInputCount, 1)
    assertEquals(node.getOutputCount, 1)

  test("Sigmoid"):
    val x = TensorExpr.Input("x", vecF32)
    val model =
      exportAndParse(singleOpGraph(TensorExpr.Sigmoid(x), Vector(GraphIO("x", vecF32)), vecF32))
    assertEquals(firstOpNode(model).getOpType, "Sigmoid")

  test("Tanh"):
    val x = TensorExpr.Input("x", vecF32)
    val model =
      exportAndParse(singleOpGraph(TensorExpr.Tanh(x), Vector(GraphIO("x", vecF32)), vecF32))
    assertEquals(firstOpNode(model).getOpType, "Tanh")

  test("LeakyRelu with alpha"):
    val x = TensorExpr.Input("x", vecF32)
    val model = exportAndParse(
      singleOpGraph(TensorExpr.LeakyRelu(x, 0.01), Vector(GraphIO("x", vecF32)), vecF32)
    )
    val node = firstOpNode(model)
    assertEquals(node.getOpType, "LeakyRelu")
    val alpha = node.getAttribute(0)
    assertEquals(alpha.getName, "alpha")
    assert(math.abs(alpha.getF - 0.01f) < 1e-6f)

  test("Softmax with axis"):
    val x = TensorExpr.Input("x", matF32)
    val model = exportAndParse(
      singleOpGraph(TensorExpr.Softmax(x, -1), Vector(GraphIO("x", matF32)), matF32)
    )
    val node = firstOpNode(model)
    assertEquals(node.getOpType, "Softmax")
    assertEquals(node.getAttribute(0).getI, -1L)

  test("Elu with alpha"):
    val x = TensorExpr.Input("x", vecF32)
    val model = exportAndParse(
      singleOpGraph(TensorExpr.Elu(x, 1.0), Vector(GraphIO("x", vecF32)), vecF32)
    )
    assertEquals(firstOpNode(model).getOpType, "Elu")

  test("Add"):
    val x = TensorExpr.Input("x", vecF32)
    val y = TensorExpr.Input("y", vecF32)
    val graph = singleOpGraph(
      TensorExpr.Add(x, y),
      Vector(GraphIO("x", vecF32), GraphIO("y", vecF32)),
      vecF32
    )
    val model = exportAndParse(graph)
    val node = firstOpNode(model)
    assertEquals(node.getOpType, "Add")
    assertEquals(node.getInputCount, 2)

  test("Sub, Mul, Div"):
    for (mkExpr, opName) <- Seq(
        (TensorExpr.Sub.apply, "Sub"),
        (TensorExpr.Mul.apply, "Mul"),
        (TensorExpr.Div.apply, "Div")
      )
    do
      val x = TensorExpr.Input("x", vecF32)
      val y = TensorExpr.Input("y", vecF32)
      val graph = singleOpGraph(
        mkExpr(x, y),
        Vector(GraphIO("x", vecF32), GraphIO("y", vecF32)),
        vecF32
      )
      val model = exportAndParse(graph)
      assertEquals(firstOpNode(model).getOpType, opName, s"Failed for $opName")

  test("Sqrt, Neg, Abs, Exp, Log"):
    val unaryOps = Seq(
      (TensorExpr.Sqrt.apply, "Sqrt"),
      (TensorExpr.Neg.apply, "Neg"),
      (TensorExpr.Abs.apply, "Abs"),
      (TensorExpr.Exp.apply, "Exp"),
      (TensorExpr.Log.apply, "Log")
    )
    for (mkExpr, opName) <- unaryOps do
      val x = TensorExpr.Input("x", vecF32)
      val model = exportAndParse(singleOpGraph(mkExpr(x), Vector(GraphIO("x", vecF32)), vecF32))
      assertEquals(firstOpNode(model).getOpType, opName, s"Failed for $opName")

  test("MatMul"):
    val x = TensorExpr.Input("x", tensorType(f32, 3, 4))
    val y = TensorExpr.Input("y", tensorType(f32, 4, 5))
    val graph = singleOpGraph(
      TensorExpr.MatMul(x, y, false, false),
      Vector(GraphIO("x", tensorType(f32, 3, 4)), GraphIO("y", tensorType(f32, 4, 5))),
      tensorType(f32, 3, 5)
    )
    val model = exportAndParse(graph)
    assertEquals(firstOpNode(model).getOpType, "MatMul")

  test("MatMul with transB emits Transpose + MatMul"):
    val x = TensorExpr.Input("x", tensorType(f32, 3, 4))
    val y = TensorExpr.Input("y", tensorType(f32, 5, 4))
    val graph = singleOpGraph(
      TensorExpr.MatMul(x, y, false, true),
      Vector(GraphIO("x", tensorType(f32, 3, 4)), GraphIO("y", tensorType(f32, 5, 4))),
      tensorType(f32, 3, 5)
    )
    val model = exportAndParse(graph)
    val ops = opTypes(model)
    assert(ops.contains("Transpose"))
    assert(ops.contains("MatMul"))

  test("Gemm with all attributes"):
    val a = TensorExpr.Input("a", tensorType(f32, 3, 4))
    val b = TensorExpr.Input("b", tensorType(f32, 4, 5))
    val c = TensorExpr.Input("c", tensorType(f32, 3, 5))
    val graph = singleOpGraph(
      TensorExpr.Gemm(a, b, Some(c), 1.0, 1.0, false, false),
      Vector(
        GraphIO("a", tensorType(f32, 3, 4)),
        GraphIO("b", tensorType(f32, 4, 5)),
        GraphIO("c", tensorType(f32, 3, 5))
      ),
      tensorType(f32, 3, 5)
    )
    val model = exportAndParse(graph)
    val node = firstOpNode(model)
    assertEquals(node.getOpType, "Gemm")
    assertEquals(node.getInputCount, 3)

  test("Conv with attributes"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 3, 8, 8))
    val w = TensorExpr.Parameter("w", tensorType(f32, 16, 3, 3, 3), None)
    val graph = singleOpGraph(
      TensorExpr.Conv(x, w, None, Vector(1, 1), Vector(1, 1, 1, 1), Vector(1, 1), 1),
      Vector(GraphIO("x", tensorType(f32, 1, 3, 8, 8))),
      tensorType(f32, 1, 16, 8, 8)
    )
    val model = exportAndParse(graph)
    val node = firstOpNode(model)
    assertEquals(node.getOpType, "Conv")

  test("BatchNormalization"):
    val tt = tensorType(f32, 1, 3, 8, 8)
    val scaleType = tensorType(f32, 3)
    val x = TensorExpr.Input("x", tt)
    val scale = TensorExpr.Parameter("scale", scaleType, None)
    val bias = TensorExpr.Parameter("bn_bias", scaleType, None)
    val mean = TensorExpr.Parameter("mean", scaleType, None)
    val variance = TensorExpr.Parameter("var", scaleType, None)
    val graph = singleOpGraph(
      TensorExpr.BatchNorm(x, scale, bias, mean, variance, 1e-5, 0.1, false),
      Vector(GraphIO("x", tt)),
      tt
    )
    val model = exportAndParse(graph)
    val node = firstOpNode(model)
    assertEquals(node.getOpType, "BatchNormalization")
    assertEquals(node.getInputCount, 5)

  test("LayerNormalization"):
    val tt = tensorType(f32, 2, 10)
    val wt = tensorType(f32, 10)
    val x = TensorExpr.Input("x", tt)
    val w = TensorExpr.Parameter("ln_weight", wt, None)
    val b = TensorExpr.Parameter("ln_bias", wt, None)
    val graph = singleOpGraph(
      TensorExpr.LayerNorm(x, Vector(10), Some(w), Some(b), 1e-5),
      Vector(GraphIO("x", tt)),
      tt
    )
    val model = exportAndParse(graph)
    assertEquals(firstOpNode(model).getOpType, "LayerNormalization")

  test("MaxPool"):
    val tt = tensorType(f32, 1, 3, 8, 8)
    val x = TensorExpr.Input("x", tt)
    val graph = singleOpGraph(
      TensorExpr.MaxPool(x, Vector(3, 3), Vector(1, 1), Vector(1, 1, 1, 1)),
      Vector(GraphIO("x", tt)),
      tt
    )
    val model = exportAndParse(graph)
    assertEquals(firstOpNode(model).getOpType, "MaxPool")

  test("AveragePool"):
    val tt = tensorType(f32, 1, 3, 8, 8)
    val x = TensorExpr.Input("x", tt)
    val graph = singleOpGraph(
      TensorExpr.AvgPool(x, Vector(3, 3), Vector(1, 1), Vector(1, 1, 1, 1), true),
      Vector(GraphIO("x", tt)),
      tt
    )
    val model = exportAndParse(graph)
    assertEquals(firstOpNode(model).getOpType, "AveragePool")

  test("GlobalAveragePool"):
    val tt = tensorType(f32, 1, 3, 8, 8)
    val x = TensorExpr.Input("x", tt)
    val graph = singleOpGraph(
      TensorExpr.GlobalAvgPool(x),
      Vector(GraphIO("x", tt)),
      tensorType(f32, 1, 3, 1, 1)
    )
    val model = exportAndParse(graph)
    assertEquals(firstOpNode(model).getOpType, "GlobalAveragePool")

  test("ReduceMean with axes and keepdims"):
    val x = TensorExpr.Input("x", matF32)
    val graph = singleOpGraph(
      TensorExpr.ReduceMean(x, Vector(1), true),
      Vector(GraphIO("x", matF32)),
      tensorType(f32, 4, 1)
    )
    val model = exportAndParse(graph)
    val ops = opTypes(model)
    assert(ops.contains("ReduceMean"))

  test("Reshape"):
    val x = TensorExpr.Input("x", matF32)
    val graph = singleOpGraph(
      TensorExpr.Reshape(x, Vector(40)),
      Vector(GraphIO("x", matF32)),
      tensorType(f32, 40)
    )
    val model = exportAndParse(graph)
    val ops = opTypes(model)
    assert(ops.contains("Reshape"))

  test("Transpose with perm"):
    val x = TensorExpr.Input("x", matF32)
    val graph = singleOpGraph(
      TensorExpr.Transpose(x, Vector(1, 0)),
      Vector(GraphIO("x", matF32)),
      tensorType(f32, 10, 4)
    )
    val model = exportAndParse(graph)
    val node = firstOpNode(model)
    assertEquals(node.getOpType, "Transpose")

  test("Flatten"):
    val tt = tensorType(f32, 2, 3, 4)
    val x = TensorExpr.Input("x", tt)
    val graph = singleOpGraph(
      TensorExpr.Flatten(x, 1),
      Vector(GraphIO("x", tt)),
      tensorType(f32, 2, 12)
    )
    val model = exportAndParse(graph)
    assertEquals(firstOpNode(model).getOpType, "Flatten")

  test("Concat"):
    val x = TensorExpr.Input("x", vecF32)
    val y = TensorExpr.Input("y", vecF32)
    val graph = singleOpGraph(
      TensorExpr.TensorConcat(Vector(x, y), 0),
      Vector(GraphIO("x", vecF32), GraphIO("y", vecF32)),
      tensorType(f32, 20)
    )
    val model = exportAndParse(graph)
    assertEquals(firstOpNode(model).getOpType, "Concat")

  test("Gather"):
    val x = TensorExpr.Input("x", matF32)
    val idx = TensorExpr.Input("idx", tensorType(i64, 2))
    val graph = singleOpGraph(
      TensorExpr.Gather(x, idx, 0),
      Vector(GraphIO("x", matF32), GraphIO("idx", tensorType(i64, 2))),
      tensorType(f32, 2, 10)
    )
    val model = exportAndParse(graph)
    assertEquals(firstOpNode(model).getOpType, "Gather")

  test("Equal, Greater, Less"):
    for (mkExpr, opName) <- Seq(
        (TensorExpr.Equal.apply, "Equal"),
        (TensorExpr.Greater.apply, "Greater"),
        (TensorExpr.Less.apply, "Less")
      )
    do
      val x = TensorExpr.Input("x", vecF32)
      val y = TensorExpr.Input("y", vecF32)
      val boolType = TensorType(TensorDType.Bool, Vector(Dim(10)))
      val graph = singleOpGraph(
        mkExpr(x, y),
        Vector(GraphIO("x", vecF32), GraphIO("y", vecF32)),
        boolType
      )
      val model = exportAndParse(graph)
      assertEquals(firstOpNode(model).getOpType, opName, s"Failed for $opName")

  test("Where"):
    val cond = TensorExpr.Input("cond", TensorType(TensorDType.Bool, Vector(Dim(10))))
    val x = TensorExpr.Input("x", vecF32)
    val y = TensorExpr.Input("y", vecF32)
    val graph = singleOpGraph(
      TensorExpr.Where(cond, x, y),
      Vector(
        GraphIO("cond", TensorType(TensorDType.Bool, Vector(Dim(10)))),
        GraphIO("x", vecF32),
        GraphIO("y", vecF32)
      ),
      vecF32
    )
    val model = exportAndParse(graph)
    assertEquals(firstOpNode(model).getOpType, "Where")

  test("Cast"):
    val x = TensorExpr.Input("x", vecF32)
    val graph = singleOpGraph(
      TensorExpr.Cast(x, TensorDType.Float64),
      Vector(GraphIO("x", vecF32)),
      TensorType(TensorDType.Float64, Vector(Dim(10)))
    )
    val model = exportAndParse(graph)
    val node = firstOpNode(model)
    assertEquals(node.getOpType, "Cast")
    assertEquals(node.getAttribute(0).getI, 11L) // DOUBLE = 11

  test("Dropout training=false emits Identity"):
    val x = TensorExpr.Input("x", vecF32)
    val graph = singleOpGraph(
      TensorExpr.Dropout(x, 0.5, training = false),
      Vector(GraphIO("x", vecF32)),
      vecF32
    )
    val model = exportAndParse(graph)
    assertEquals(firstOpNode(model).getOpType, "Identity")

  test("Dropout training=true emits Dropout"):
    val x = TensorExpr.Input("x", vecF32)
    val graph = singleOpGraph(
      TensorExpr.Dropout(x, 0.5, training = true),
      Vector(GraphIO("x", vecF32)),
      vecF32
    )
    val model = exportAndParse(graph)
    val ops = opTypes(model)
    assert(ops.contains("Dropout"))

  test("LSTM"):
    val seqType = tensorType(f32, 10, 1, 32) // seq_len, batch, input_size
    val wType = tensorType(f32, 1, 128, 32) // num_dir, 4*hidden, input
    val rType = tensorType(f32, 1, 128, 32) // num_dir, 4*hidden, hidden
    val x = TensorExpr.Input("x", seqType)
    val w = TensorExpr.Parameter("w_ih", wType, None)
    val r = TensorExpr.Parameter("w_hh", rType, None)
    val graph = singleOpGraph(
      TensorExpr.LSTM(x, w, r, None, 32, 1, false, 0.0),
      Vector(GraphIO("x", seqType)),
      seqType
    )
    val model = exportAndParse(graph)
    assertEquals(firstOpNode(model).getOpType, "LSTM")

  test("GRU"):
    val seqType = tensorType(f32, 10, 1, 32)
    val wType = tensorType(f32, 1, 96, 32)
    val rType = tensorType(f32, 1, 96, 32)
    val x = TensorExpr.Input("x", seqType)
    val w = TensorExpr.Parameter("w_ih", wType, None)
    val r = TensorExpr.Parameter("w_hh", rType, None)
    val graph = singleOpGraph(
      TensorExpr.GRU(x, w, r, None, 32, 1, false, 0.0),
      Vector(GraphIO("x", seqType)),
      seqType
    )
    val model = exportAndParse(graph)
    assertEquals(firstOpNode(model).getOpType, "GRU")

  // ============================================================================
  // Category B: Decomposition tests
  // ============================================================================

  test("Linear decomposes to Gemm with transB=1"):
    val x = TensorExpr.Input("x", tensorType(f32, 4, 10))
    val w = TensorExpr.Parameter("w", tensorType(f32, 5, 10), None)
    val b = TensorExpr.Parameter("b", tensorType(f32, 5), None)
    val graph = singleOpGraph(
      TensorExpr.Linear(x, w, Some(b)),
      Vector(GraphIO("x", tensorType(f32, 4, 10))),
      tensorType(f32, 4, 5)
    )
    val model = exportAndParse(graph)
    val node = firstOpNode(model)
    assertEquals(node.getOpType, "Gemm")
    // transB should be 1
    val transB = (0 until node.getAttributeCount)
      .map(node.getAttribute)
      .find(_.getName == "transB")
      .get
    assertEquals(transB.getI, 1L)

  test("Silu decomposes to Sigmoid + Mul"):
    val x = TensorExpr.Input("x", vecF32)
    val model = exportAndParse(
      singleOpGraph(TensorExpr.Silu(x), Vector(GraphIO("x", vecF32)), vecF32)
    )
    val ops = opTypes(model)
    assert(ops.contains("Sigmoid"), s"Expected Sigmoid in $ops")
    assert(ops.contains("Mul"), s"Expected Mul in $ops")
    assertEquals(ops.size, 2)

  test("HardSwish decomposes to Add + Clip + Mul + Div"):
    val x = TensorExpr.Input("x", vecF32)
    val model = exportAndParse(
      singleOpGraph(TensorExpr.HardSwish(x), Vector(GraphIO("x", vecF32)), vecF32)
    )
    val ops = opTypes(model)
    assert(ops.contains("Add"))
    assert(ops.contains("Clip"))
    assert(ops.contains("Mul"))
    assert(ops.contains("Div"))

  test("Gelu exact decomposes to Div + Erf + Add + Mul + Mul"):
    val x = TensorExpr.Input("x", vecF32)
    val model = exportAndParse(
      singleOpGraph(TensorExpr.Gelu(x, approximate = false), Vector(GraphIO("x", vecF32)), vecF32)
    )
    val ops = opTypes(model)
    assert(ops.contains("Div"))
    assert(ops.contains("Erf"))
    assertEquals(ops.size, 5)

  test("Gelu approximate decomposes with Tanh"):
    val x = TensorExpr.Input("x", vecF32)
    val model = exportAndParse(
      singleOpGraph(TensorExpr.Gelu(x, approximate = true), Vector(GraphIO("x", vecF32)), vecF32)
    )
    val ops = opTypes(model)
    assert(ops.contains("Tanh"))

  test("Embedding decomposes to Gather"):
    val idx = TensorExpr.Input("idx", tensorType(i64, 5))
    val w = TensorExpr.Parameter("embed_w", tensorType(f32, 100, 32), None)
    val graph = singleOpGraph(
      TensorExpr.Embedding(idx, w, None),
      Vector(GraphIO("idx", tensorType(i64, 5))),
      tensorType(f32, 5, 32)
    )
    val model = exportAndParse(graph)
    val node = firstOpNode(model)
    assertEquals(node.getOpType, "Gather")
    val axis = (0 until node.getAttributeCount)
      .map(node.getAttribute)
      .find(_.getName == "axis")
      .get
    assertEquals(axis.getI, 0L)

  test("CrossEntropyLoss maps to SoftmaxCrossEntropyLoss"):
    val x = TensorExpr.Input("logits", tensorType(f32, 4, 10))
    val t = TensorExpr.Input("targets", tensorType(i64, 4))
    val graph = singleOpGraph(
      TensorExpr.CrossEntropyLoss(x, t, None, Reduction.Mean),
      Vector(GraphIO("logits", tensorType(f32, 4, 10)), GraphIO("targets", tensorType(i64, 4))),
      tensorType(f32)
    )
    val model = exportAndParse(graph)
    val node = firstOpNode(model)
    assertEquals(node.getOpType, "SoftmaxCrossEntropyLoss")

  test("MSELoss decomposes to Sub + Mul + ReduceMean"):
    val x = TensorExpr.Input("x", vecF32)
    val t = TensorExpr.Input("t", vecF32)
    val graph = singleOpGraph(
      TensorExpr.MSELoss(x, t, Reduction.Mean),
      Vector(GraphIO("x", vecF32), GraphIO("t", vecF32)),
      tensorType(f32)
    )
    val model = exportAndParse(graph)
    val ops = opTypes(model)
    assert(ops.contains("Sub"))
    assert(ops.contains("Mul"))
    assert(ops.contains("ReduceMean"))

  test("L1Loss decomposes to Sub + Abs + ReduceMean"):
    val x = TensorExpr.Input("x", vecF32)
    val t = TensorExpr.Input("t", vecF32)
    val graph = singleOpGraph(
      TensorExpr.L1Loss(x, t, Reduction.Mean),
      Vector(GraphIO("x", vecF32), GraphIO("t", vecF32)),
      tensorType(f32)
    )
    val model = exportAndParse(graph)
    val ops = opTypes(model)
    assert(ops.contains("Sub"))
    assert(ops.contains("Abs"))
    assert(ops.contains("ReduceMean"))

  test("ScaledDotProductAttention decomposes correctly"):
    val tt = tensorType(f32, 1, 8, 64)
    val q = TensorExpr.Input("q", tt)
    val k = TensorExpr.Input("k", tt)
    val v = TensorExpr.Input("v", tt)
    val graph = singleOpGraph(
      TensorExpr.ScaledDotProductAttention(q, k, v, None, 0.0, false),
      Vector(GraphIO("q", tt), GraphIO("k", tt), GraphIO("v", tt)),
      tt
    )
    val model = exportAndParse(graph)
    val ops = opTypes(model)
    assert(ops.contains("Transpose"))
    assert(ops.count(_ == "MatMul") >= 2)
    assert(ops.contains("Softmax"))
    assert(ops.contains("Div"))

  // ============================================================================
  // DAG test — shared references
  // ============================================================================

  test("DAG: residual block with shared input"):
    val x = TensorExpr.Input("x", vecF32)
    // res = relu(x) + x — x is referenced by both Relu and Add
    val reluExpr = TensorExpr.Relu(x)
    val addExpr = TensorExpr.Add(reluExpr, x)
    val graph = ComputeGraph(
      name = "residual",
      inputs = Vector(GraphIO("x", vecF32)),
      outputs = Vector(GraphIO("add_out", vecF32)),
      nodes = Vector(
        NamedNode("relu_out", reluExpr, vecF32),
        NamedNode("add_out", addExpr, vecF32)
      )
    )
    val model = exportAndParse(graph)
    val ops = opTypes(model)
    assert(ops.contains("Relu"))
    assert(ops.contains("Add"))
    // Add node should reference "x" directly (shared input)
    val addNode = (0 until model.getGraph.getNodeCount)
      .map(model.getGraph.getNode)
      .find(_.getOpType == "Add")
      .get
    val addInputs = (0 until addNode.getInputCount).map(addNode.getInput)
    assert(addInputs.contains("x"), s"Expected 'x' in Add inputs: $addInputs")

  // ============================================================================
  // Full model tests
  // ============================================================================

  test("two-layer MLP"):
    val inputType = tensorType(f32, 4, 784)
    val x = TensorExpr.Input("x", inputType)
    val w1 = TensorExpr.Parameter("w1", tensorType(f32, 256, 784), None)
    val b1 = TensorExpr.Parameter("b1", tensorType(f32, 256), None)
    val w2 = TensorExpr.Parameter("w2", tensorType(f32, 10, 256), None)
    val b2 = TensorExpr.Parameter("b2", tensorType(f32, 10), None)

    val linear1 = TensorExpr.Linear(x, w1, Some(b1))
    val relu = TensorExpr.Relu(linear1)
    val linear2 = TensorExpr.Linear(relu, w2, Some(b2))

    val graph = ComputeGraph(
      name = "mlp",
      inputs = Vector(GraphIO("x", inputType)),
      outputs = Vector(GraphIO("logits", tensorType(f32, 4, 10))),
      nodes = Vector(
        NamedNode("hidden", linear1, tensorType(f32, 4, 256)),
        NamedNode("activated", relu, tensorType(f32, 4, 256)),
        NamedNode("logits", linear2, tensorType(f32, 4, 10))
      )
    )
    val model = exportAndParse(graph)
    assertEquals(model.getGraph.getName, "mlp")
    val ops = opTypes(model)
    // Linear → Gemm, so we expect 2 Gemms and 1 Relu
    assertEquals(ops.count(_ == "Gemm"), 2)
    assertEquals(ops.count(_ == "Relu"), 1)
    // Should have 4 initializers (w1, b1, w2, b2)
    assertEquals(model.getGraph.getInitializerCount, 4)

  test("Conv + BN + ReLU pattern"):
    val tt = tensorType(f32, 1, 3, 8, 8)
    val outTt = tensorType(f32, 1, 16, 8, 8)
    val chType = tensorType(f32, 16)
    val x = TensorExpr.Input("x", tt)
    val w = TensorExpr.Parameter("conv_w", tensorType(f32, 16, 3, 3, 3), None)
    val scale = TensorExpr.Parameter("bn_scale", chType, None)
    val bias = TensorExpr.Parameter("bn_bias", chType, None)
    val mean = TensorExpr.Parameter("bn_mean", chType, None)
    val variance = TensorExpr.Parameter("bn_var", chType, None)

    val conv = TensorExpr.Conv(x, w, None, Vector(1, 1), Vector(1, 1, 1, 1), Vector(1, 1), 1)
    val bn = TensorExpr.BatchNorm(conv, scale, bias, mean, variance, 1e-5, 0.1, false)
    val relu = TensorExpr.Relu(bn)

    val graph = ComputeGraph(
      name = "conv_bn_relu",
      inputs = Vector(GraphIO("x", tt)),
      outputs = Vector(GraphIO("out", outTt)),
      nodes = Vector(
        NamedNode("conv_out", conv, outTt),
        NamedNode("bn_out", bn, outTt),
        NamedNode("out", relu, outTt)
      )
    )
    val model = exportAndParse(graph)
    val ops = opTypes(model)
    assert(ops.contains("Conv"))
    assert(ops.contains("BatchNormalization"))
    assert(ops.contains("Relu"))

  // ============================================================================
  // OpaqueOp test
  // ============================================================================

  test("OpaqueOp emits custom domain"):
    val x = TensorExpr.Input("x", vecF32)
    val graph = singleOpGraph(
      TensorExpr.OpaqueOp(
        "MyCustomOp",
        Vector(x),
        Map("threshold" -> OpAttribute.FloatAttr(0.5)),
        vecF32
      ),
      Vector(GraphIO("x", vecF32)),
      vecF32
    )
    val model = exportAndParse(graph)
    // Should have custom domain in opset imports
    val domains = (0 until model.getOpsetImportCount).map(model.getOpsetImport(_).getDomain)
    assert(domains.contains("protocatalyst.custom"), s"Expected custom domain in $domains")
    // Node should have custom domain
    val node = firstOpNode(model)
    assertEquals(node.getOpType, "MyCustomOp")
    assertEquals(node.getDomain, "protocatalyst.custom")

  // ============================================================================
  // Binary roundtrip test
  // ============================================================================

  test("binary roundtrip: export → parse → verify"):
    val x = TensorExpr.Input("x", vecF32)
    val graph = singleOpGraph(TensorExpr.Relu(x), Vector(GraphIO("x", vecF32)), vecF32)
    val bytes = OnnxExporter.toBytes(graph)
    assert(bytes.length > 0)
    val parsed = ox.ModelProto.parseFrom(bytes)
    assertEquals(parsed.getGraph.getNodeCount, 1)
    assertEquals(parsed.getGraph.getNode(0).getOpType, "Relu")
