package protocatalyst.ml.codec

import java.nio.{ByteBuffer, ByteOrder}

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph._
import protocatalyst.ml.types._

class OnnxImporterSuite extends munit.FunSuite:

  private val f32 = TensorDType.Float32
  private val i64 = TensorDType.Int64

  private def tensorType(dtype: TensorDType, dims: Int*): TensorType =
    TensorType(dtype, dims.map(Dim(_)).toVector)

  private def dynamicTensorType(dtype: TensorDType, dims: (String, Int)*): TensorType =
    TensorType(
      dtype,
      dims.map { case (name, size) =>
        if name.isEmpty then Dim.Static(size) else Dim.Dynamic(Some(name))
      }.toVector
    )

  private val vecF32 = tensorType(f32, 4)
  private val matF32 = tensorType(f32, 2, 3)

  private def floatTensorData(shape: Vector[Int], values: Array[Float]): TensorData =
    val buf = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN)
    values.foreach(buf.putFloat)
    TensorData(f32, shape, buf.array())

  private def constantTensorData(shape: Vector[Int], value: Float): TensorData =
    floatTensorData(shape, Array.fill(shape.product)(value))

  private def singleOpGraph(
      expr: TensorExpr,
      inputs: Vector[GraphIO],
      outputType: TensorType
  ): ComputeGraph =
    ComputeGraph(
      name = "test",
      inputs = inputs,
      outputs = Vector(GraphIO("output", outputType)),
      nodes = Vector(NamedNode("output", expr, outputType))
    )

  // ============================================================================
  // Round-trip tests: export → import → compare
  // ============================================================================

  test("round-trip: Relu"):
    val x = TensorExpr.Input("x", vecF32)
    val graph = singleOpGraph(TensorExpr.Relu(x), Vector(GraphIO("x", vecF32)), vecF32)
    val imported = OnnxImporter.fromBytes(OnnxExporter.toBytes(graph))

    assertEquals(imported.name, "test")
    assertEquals(imported.inputs.size, 1)
    assertEquals(imported.inputs.head.name, "x")
    assertEquals(imported.outputs.size, 1)
    assertEquals(imported.outputs.head.name, "output")
    // Find the Relu node (skip auxiliary nodes)
    val reluNode = imported.nodes.find(n => n.expr.isInstanceOf[TensorExpr.Relu])
    assert(
      reluNode.isDefined,
      s"Expected Relu node, got: ${imported.nodes.map(n => n.name -> n.expr.getClass.getSimpleName)}"
    )

  test("round-trip: Add"):
    val x = TensorExpr.Input("x", vecF32)
    val y = TensorExpr.Input("y", vecF32)
    val graph = singleOpGraph(
      TensorExpr.Add(x, y),
      Vector(GraphIO("x", vecF32), GraphIO("y", vecF32)),
      vecF32
    )
    val imported = OnnxImporter.fromBytes(OnnxExporter.toBytes(graph))

    assertEquals(imported.inputs.size, 2)
    val addNode = imported.nodes.find(_.expr.isInstanceOf[TensorExpr.Add])
    assert(addNode.isDefined)

  test("round-trip: MatMul"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 3))
    val y = TensorExpr.Input("y", tensorType(f32, 3, 4))
    val outType = tensorType(f32, 2, 4)
    val graph = singleOpGraph(
      TensorExpr.MatMul(x, y, transA = false, transB = false),
      Vector(GraphIO("x", tensorType(f32, 2, 3)), GraphIO("y", tensorType(f32, 3, 4))),
      outType
    )
    val imported = OnnxImporter.fromBytes(OnnxExporter.toBytes(graph))

    val matmulNode = imported.nodes.find(_.expr.isInstanceOf[TensorExpr.MatMul])
    assert(matmulNode.isDefined)

  test("round-trip: Conv with bias"):
    val input = TensorExpr.Input("input", tensorType(f32, 1, 3, 8, 8))
    val weight = TensorExpr.Constant(
      "weight",
      tensorType(f32, 16, 3, 3, 3),
      constantTensorData(Vector(16, 3, 3, 3), 0.1f)
    )
    val bias =
      TensorExpr.Constant("bias", tensorType(f32, 16), constantTensorData(Vector(16), 0.0f))
    val outType = tensorType(f32, 1, 16, 6, 6)
    val graph = singleOpGraph(
      TensorExpr.Conv(input, weight, Some(bias), Vector(1, 1), Vector(0, 0, 0, 0), Vector(1, 1), 1),
      Vector(GraphIO("input", tensorType(f32, 1, 3, 8, 8))),
      outType
    )
    val imported = OnnxImporter.fromBytes(OnnxExporter.toBytes(graph))

    val convNode = imported.nodes.find(_.expr.isInstanceOf[TensorExpr.Conv])
    assert(convNode.isDefined)
    val conv = convNode.get.expr.asInstanceOf[TensorExpr.Conv]
    assertEquals(conv.strides, Vector(1, 1))
    assertEquals(conv.pads, Vector(0, 0, 0, 0))
    assertEquals(conv.dilations, Vector(1, 1))
    assertEquals(conv.group, 1)
    assert(conv.bias.isDefined)

  test("round-trip: Linear (Gemm with transB=1)"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 3))
    val w = TensorExpr.Constant("w", tensorType(f32, 4, 3), constantTensorData(Vector(4, 3), 0.5f))
    val b = TensorExpr.Constant("b", tensorType(f32, 4), constantTensorData(Vector(4), 0.1f))
    val outType = tensorType(f32, 2, 4)
    val graph = singleOpGraph(
      TensorExpr.Linear(x, w, Some(b)),
      Vector(GraphIO("x", tensorType(f32, 2, 3))),
      outType
    )
    val imported = OnnxImporter.fromBytes(OnnxExporter.toBytes(graph))

    // Linear exports as Gemm(transB=1), which should be recognized back as Linear
    val linearNode = imported.nodes.find(_.expr.isInstanceOf[TensorExpr.Linear])
    assert(
      linearNode.isDefined,
      s"Expected Linear node, got: ${imported.nodes.map(n => n.name -> n.expr.getClass.getSimpleName)}"
    )
    val linear = linearNode.get.expr.asInstanceOf[TensorExpr.Linear]
    assert(linear.bias.isDefined)

  test("round-trip: multi-layer MLP"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 4))
    val w1 =
      TensorExpr.Constant("w1", tensorType(f32, 8, 4), constantTensorData(Vector(8, 4), 0.1f))
    val b1 = TensorExpr.Constant("b1", tensorType(f32, 8), constantTensorData(Vector(8), 0.0f))
    val w2 =
      TensorExpr.Constant("w2", tensorType(f32, 2, 8), constantTensorData(Vector(2, 8), 0.1f))
    val b2 = TensorExpr.Constant("b2", tensorType(f32, 2), constantTensorData(Vector(2), 0.0f))

    val linear1 = TensorExpr.Linear(x, w1, Some(b1))
    val relu = TensorExpr.Relu(linear1)
    val linear2 = TensorExpr.Linear(relu, w2, Some(b2))

    val outType = tensorType(f32, 1, 2)
    val graph = ComputeGraph(
      name = "mlp",
      inputs = Vector(GraphIO("x", tensorType(f32, 1, 4))),
      outputs = Vector(GraphIO("linear2", outType)),
      nodes = Vector(
        NamedNode("linear1", linear1, tensorType(f32, 1, 8)),
        NamedNode("relu1", relu, tensorType(f32, 1, 8)),
        NamedNode("linear2", linear2, outType)
      )
    )
    val imported = OnnxImporter.fromBytes(OnnxExporter.toBytes(graph))

    assertEquals(imported.name, "mlp")
    assertEquals(imported.inputs.size, 1)
    assertEquals(imported.outputs.size, 1)
    // Should have Linear, Relu, Linear nodes (at minimum)
    val linearNodes = imported.nodes.filter(_.expr.isInstanceOf[TensorExpr.Linear])
    val reluNodes = imported.nodes.filter(_.expr.isInstanceOf[TensorExpr.Relu])
    assertEquals(linearNodes.size, 2)
    assertEquals(reluNodes.size, 1)

  test("round-trip: Reshape"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 3))
    val outType = tensorType(f32, 6)
    val graph = singleOpGraph(
      TensorExpr.Reshape(x, Vector(6)),
      Vector(GraphIO("x", tensorType(f32, 2, 3))),
      outType
    )
    val imported = OnnxImporter.fromBytes(OnnxExporter.toBytes(graph))

    val reshapeNode = imported.nodes.find(_.expr.isInstanceOf[TensorExpr.Reshape])
    assert(reshapeNode.isDefined)
    val reshape = reshapeNode.get.expr.asInstanceOf[TensorExpr.Reshape]
    assertEquals(reshape.shape, Vector(6))

  test("round-trip: Transpose"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 3, 4))
    val outType = tensorType(f32, 4, 3, 2)
    val graph = singleOpGraph(
      TensorExpr.Transpose(x, Vector(2, 1, 0)),
      Vector(GraphIO("x", tensorType(f32, 2, 3, 4))),
      outType
    )
    val imported = OnnxImporter.fromBytes(OnnxExporter.toBytes(graph))

    val transposeNode = imported.nodes.find(_.expr.isInstanceOf[TensorExpr.Transpose])
    assert(transposeNode.isDefined)
    assertEquals(transposeNode.get.expr.asInstanceOf[TensorExpr.Transpose].perm, Vector(2, 1, 0))

  test("round-trip: Flatten"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 3, 4))
    val outType = tensorType(f32, 2, 12)
    val graph = singleOpGraph(
      TensorExpr.Flatten(x, 1),
      Vector(GraphIO("x", tensorType(f32, 2, 3, 4))),
      outType
    )
    val imported = OnnxImporter.fromBytes(OnnxExporter.toBytes(graph))

    val flattenNode = imported.nodes.find(_.expr.isInstanceOf[TensorExpr.Flatten])
    assert(flattenNode.isDefined)
    assertEquals(flattenNode.get.expr.asInstanceOf[TensorExpr.Flatten].axis, 1)

  // ============================================================================
  // Type handling tests
  // ============================================================================

  test("dtype round-trip: all supported types"):
    val allDTypes = Vector(
      TensorDType.Float16,
      TensorDType.Float32,
      TensorDType.Float64,
      TensorDType.BFloat16,
      TensorDType.Int8,
      TensorDType.Int16,
      TensorDType.Int32,
      TensorDType.Int64,
      TensorDType.UInt8,
      TensorDType.Bool,
      TensorDType.Complex64,
      TensorDType.Complex128
    )
    for dtype <- allDTypes do
      val tt = TensorType(dtype, Vector(Dim.Static(2)))
      val x = TensorExpr.Input("x", tt)
      val graph = singleOpGraph(TensorExpr.Relu(x), Vector(GraphIO("x", tt)), tt)
      val imported = OnnxImporter.fromBytes(OnnxExporter.toBytes(graph))
      assertEquals(
        imported.inputs.head.tensorType.dtype,
        dtype,
        s"Failed for $dtype"
      )

  test("dynamic dimensions are preserved"):
    val tt = dynamicTensorType(f32, "batch" -> 0, "" -> 784)
    val x = TensorExpr.Input("x", tt)
    val graph = singleOpGraph(TensorExpr.Relu(x), Vector(GraphIO("x", tt)), tt)
    val imported = OnnxImporter.fromBytes(OnnxExporter.toBytes(graph))

    val importedType = imported.inputs.head.tensorType
    assertEquals(importedType.shape.size, 2)
    importedType.shape(0) match
      case Dim.Dynamic(Some("batch")) => () // ok
      case other                      => fail(s"Expected Dynamic(batch), got $other")
    importedType.shape(1) match
      case Dim.Static(784) => () // ok
      case other           => fail(s"Expected Static(784), got $other")

  // ============================================================================
  // Constant data round-trip
  // ============================================================================

  test("constant tensor data is preserved"):
    val x = TensorExpr.Input("x", vecF32)
    val data = floatTensorData(Vector(4), Array(1.0f, 2.0f, 3.0f, 4.0f))
    val c = TensorExpr.Constant("c", vecF32, data)
    val graph = singleOpGraph(
      TensorExpr.Add(x, c),
      Vector(GraphIO("x", vecF32)),
      vecF32
    )
    val imported = OnnxImporter.fromBytes(OnnxExporter.toBytes(graph))

    val addNode = imported.nodes.find(_.expr.isInstanceOf[TensorExpr.Add])
    assert(addNode.isDefined)
    val add = addNode.get.expr.asInstanceOf[TensorExpr.Add]
    // One of the inputs should be a Constant with our data
    val constant = add match
      case TensorExpr.Add(_, c: TensorExpr.Constant) => c
      case TensorExpr.Add(c: TensorExpr.Constant, _) => c
      case _                                         => fail("Expected one Constant child")
    assertEquals(constant.data.shape, Vector(4))
    val buf = ByteBuffer.wrap(constant.data.rawBytes).order(ByteOrder.LITTLE_ENDIAN)
    val values = Array.fill(4)(buf.getFloat)
    for i <- 0 until 4 do assertEqualsFloat(values(i), data.rawBytes.slice(i * 4, i * 4 + 4), i)

  // ============================================================================
  // Edge cases
  // ============================================================================

  test("Identity nodes are passthrough"):
    // Build graph where Dropout in inference mode produces Identity
    val x = TensorExpr.Input("x", vecF32)
    val graph = singleOpGraph(
      TensorExpr.Dropout(x, 0.5, training = false),
      Vector(GraphIO("x", vecF32)),
      vecF32
    )
    val imported = OnnxImporter.fromBytes(OnnxExporter.toBytes(graph))

    // Identity should not create a node — output maps directly to input
    // The imported graph might have zero nodes if Identity is the only op
    assertEquals(imported.nodes.size, 0)

  test("unknown ops become OpaqueOp"):
    val x = TensorExpr.Input("x", vecF32)
    val graph = singleOpGraph(
      TensorExpr.OpaqueOp("MyCustomOp", Vector(x), Map("magic" -> OpAttribute.IntAttr(42)), vecF32),
      Vector(GraphIO("x", vecF32)),
      vecF32
    )
    val imported = OnnxImporter.fromBytes(OnnxExporter.toBytes(graph))

    val opaqueNode = imported.nodes.find(_.expr.isInstanceOf[TensorExpr.OpaqueOp])
    assert(opaqueNode.isDefined)
    val opaque = opaqueNode.get.expr.asInstanceOf[TensorExpr.OpaqueOp]
    assertEquals(opaque.name, "MyCustomOp")
    assertEquals(opaque.attributes("magic"), OpAttribute.IntAttr(42))

  test("Clip with optional min/max"):
    val x = TensorExpr.Input("x", vecF32)
    val graph = singleOpGraph(
      TensorExpr.Clip(x, Some(0.0), None),
      Vector(GraphIO("x", vecF32)),
      vecF32
    )
    val imported = OnnxImporter.fromBytes(OnnxExporter.toBytes(graph))

    val clipNode = imported.nodes.find(_.expr.isInstanceOf[TensorExpr.Clip])
    assert(clipNode.isDefined)
    val clip = clipNode.get.expr.asInstanceOf[TensorExpr.Clip]
    assert(clip.min.isDefined)
    assertEqualsDouble(clip.min.get, 0.0, 1e-6)

  // ============================================================================
  // Decomposed ops stay decomposed
  // ============================================================================

  test("Silu import stays decomposed (Sigmoid + Mul)"):
    val x = TensorExpr.Input("x", vecF32)
    val graph = singleOpGraph(TensorExpr.Silu(x), Vector(GraphIO("x", vecF32)), vecF32)
    val imported = OnnxImporter.fromBytes(OnnxExporter.toBytes(graph))

    // Silu is decomposed into Sigmoid + Mul, importer keeps them separate
    val sigmoidNodes = imported.nodes.filter(_.expr.isInstanceOf[TensorExpr.Sigmoid])
    val mulNodes = imported.nodes.filter(_.expr.isInstanceOf[TensorExpr.Mul])
    assertEquals(sigmoidNodes.size, 1)
    assertEquals(mulNodes.size, 1)

  // ============================================================================
  // Helpers
  // ============================================================================

  private def assertEqualsFloat(actual: Float, expectedBytes: Array[Byte], index: Int): Unit =
    val expected = ByteBuffer.wrap(expectedBytes).order(ByteOrder.LITTLE_ENDIAN).getFloat
    assert(
      Math.abs(actual - expected) < 1e-6f,
      s"Float mismatch at index $index: expected $expected, got $actual"
    )

  private def assertEqualsDouble(actual: Double, expected: Double, tolerance: Double): Unit =
    assert(
      Math.abs(actual - expected) < tolerance,
      s"Double mismatch: expected $expected, got $actual"
    )
