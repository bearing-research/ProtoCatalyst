package protocatalyst.ml.codec

import java.nio.{ByteBuffer, ByteOrder}

import scala.jdk.CollectionConverters._
import scala.util.Using

import com.jyuzawa.onnxruntime.OnnxRuntime

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph._
import protocatalyst.ml.types._

class OnnxRuntimeValidationSuite extends munit.FunSuite:

  private val api = OnnxRuntime.get().getApi
  private val env = api.newEnvironment().build()

  override def afterAll(): Unit =
    env.close()

  private val f32 = TensorDType.Float32

  private def tensorType(dtype: TensorDType, dims: Int*): TensorType =
    TensorType(dtype, dims.map(Dim(_)).toVector)

  // ============================================================================
  // TensorData helpers
  // ============================================================================

  private def floatTensorData(shape: Vector[Int], values: Array[Float]): TensorData =
    val buf = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN)
    values.foreach(buf.putFloat)
    TensorData(TensorDType.Float32, shape, buf.array())

  private def constantTensorData(shape: Vector[Int], value: Float): TensorData =
    floatTensorData(shape, Array.fill(shape.product)(value))

  // ============================================================================
  // Graph construction helpers
  // ============================================================================

  private def constant(name: String, shape: Vector[Int], data: TensorData): TensorExpr =
    TensorExpr.Constant(name, tensorType(f32, shape*), data)

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

  // ============================================================================
  // ONNX Runtime execution
  // ============================================================================

  case class RuntimeResult(shape: Seq[Long], values: Array[Float])

  private def runGraph(
      graph: ComputeGraph,
      inputs: Map[String, (Array[Long], Array[Float])]
  ): Vector[RuntimeResult] =
    val bytes = OnnxExporter.toBytes(graph)
    Using.Manager { use =>
      val session = use(env.newSession().setByteArray(bytes).build())
      val transaction = use(session.newTransaction().build())

      // Add inputs and write data
      inputs.foreach { case (name, (_, data)) =>
        val inputTensor = transaction.addInput(name).asTensor()
        val buf = inputTensor.getFloatBuffer
        buf.put(data)
        buf.flip()
      }

      // Add outputs
      graph.outputs.foreach(o => transaction.addOutput(o.name))

      // Run
      val results = transaction.run()

      // Read outputs
      graph.outputs.map { output =>
        val tensor = results.get(output.name).asTensor()
        val shape = tensor.getInfo.getShape
        val fb = tensor.getFloatBuffer
        val arr = new Array[Float](fb.remaining())
        fb.get(arr)
        RuntimeResult(shape.asInstanceOf[java.util.List[Long]].asScala.toSeq, arr)
      }
    }.get

  // ============================================================================
  // Assertion helpers
  // ============================================================================

  private def assertEqualsFloat(actual: Float, expected: Float, tol: Float): Unit =
    assert(
      math.abs(actual - expected) <= tol,
      s"Expected $expected +/- $tol, got $actual"
    )

  private def assertFinite(values: Array[Float], context: String): Unit =
    val bad = values.count(v => v.isNaN || v.isInfinite)
    assert(bad == 0, s"Found $bad NaN/Inf values in $context")

  private def assertShape(actual: Seq[Long], expected: Seq[Int], context: String): Unit =
    assertEquals(actual, expected.map(_.toLong), s"Shape mismatch in $context")

  private def assertArrayEquals(
      actual: Array[Float],
      expected: Array[Float],
      tol: Float,
      context: String
  ): Unit =
    assertEquals(actual.length, expected.length, s"Length mismatch in $context")
    actual.zip(expected).zipWithIndex.foreach { case ((a, e), i) =>
      assert(
        math.abs(a - e) <= tol,
        s"$context[$i]: expected $e +/- $tol, got $a"
      )
    }

  // ============================================================================
  // Category 1: Unary activations
  // ============================================================================

  test("runtime: Relu"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 3))
    val graph = singleOpGraph(
      TensorExpr.Relu(x),
      Vector(GraphIO("x", tensorType(f32, 2, 3))),
      tensorType(f32, 2, 3)
    )
    val input = Array(-1.0f, 0.0f, 1.0f, -0.5f, 2.0f, -3.0f)
    val result = runGraph(graph, Map("x" -> (Array(2L, 3L), input)))
    assertShape(result(0).shape, Seq(2, 3), "Relu")
    assertArrayEquals(
      result(0).values,
      Array(0.0f, 0.0f, 1.0f, 0.0f, 2.0f, 0.0f),
      1e-6f,
      "Relu"
    )

  test("runtime: Sigmoid"):
    val x = TensorExpr.Input("x", tensorType(f32, 4))
    val graph = singleOpGraph(
      TensorExpr.Sigmoid(x),
      Vector(GraphIO("x", tensorType(f32, 4))),
      tensorType(f32, 4)
    )
    val result = runGraph(graph, Map("x" -> (Array(4L), Array(0.0f, 1.0f, -1.0f, 10.0f))))
    assertShape(result(0).shape, Seq(4), "Sigmoid")
    assertFinite(result(0).values, "Sigmoid")
    assertEqualsFloat(result(0).values(0), 0.5f, 1e-5f)
    result(0).values.foreach(v => assert(v >= 0.0f && v <= 1.0f, s"Sigmoid out of [0,1]: $v"))

  test("runtime: Tanh"):
    val x = TensorExpr.Input("x", tensorType(f32, 4))
    val graph = singleOpGraph(
      TensorExpr.Tanh(x),
      Vector(GraphIO("x", tensorType(f32, 4))),
      tensorType(f32, 4)
    )
    val result = runGraph(graph, Map("x" -> (Array(4L), Array(0.0f, 1.0f, -1.0f, 5.0f))))
    assertShape(result(0).shape, Seq(4), "Tanh")
    assertEqualsFloat(result(0).values(0), 0.0f, 1e-5f)
    result(0).values.foreach(v => assert(v >= -1.0f && v <= 1.0f, s"Tanh out of [-1,1]: $v"))

  // ============================================================================
  // Category 2: Binary ops
  // ============================================================================

  test("runtime: Add"):
    val x = TensorExpr.Input("x", tensorType(f32, 3))
    val y = TensorExpr.Input("y", tensorType(f32, 3))
    val graph = singleOpGraph(
      TensorExpr.Add(x, y),
      Vector(GraphIO("x", tensorType(f32, 3)), GraphIO("y", tensorType(f32, 3))),
      tensorType(f32, 3)
    )
    val result = runGraph(
      graph,
      Map("x" -> (Array(3L), Array(1.0f, 2.0f, 3.0f)), "y" -> (Array(3L), Array(4.0f, 5.0f, 6.0f)))
    )
    assertArrayEquals(result(0).values, Array(5.0f, 7.0f, 9.0f), 1e-6f, "Add")

  test("runtime: Mul"):
    val x = TensorExpr.Input("x", tensorType(f32, 3))
    val y = TensorExpr.Input("y", tensorType(f32, 3))
    val graph = singleOpGraph(
      TensorExpr.Mul(x, y),
      Vector(GraphIO("x", tensorType(f32, 3)), GraphIO("y", tensorType(f32, 3))),
      tensorType(f32, 3)
    )
    val result = runGraph(
      graph,
      Map("x" -> (Array(3L), Array(2.0f, 3.0f, 4.0f)), "y" -> (Array(3L), Array(5.0f, 6.0f, 7.0f)))
    )
    assertArrayEquals(result(0).values, Array(10.0f, 18.0f, 28.0f), 1e-6f, "Mul")

  // ============================================================================
  // Category 3: Unary math
  // ============================================================================

  test("runtime: Sqrt"):
    val x = TensorExpr.Input("x", tensorType(f32, 4))
    val graph = singleOpGraph(
      TensorExpr.Sqrt(x),
      Vector(GraphIO("x", tensorType(f32, 4))),
      tensorType(f32, 4)
    )
    val result =
      runGraph(graph, Map("x" -> (Array(4L), Array(1.0f, 4.0f, 9.0f, 16.0f))))
    assertArrayEquals(result(0).values, Array(1.0f, 2.0f, 3.0f, 4.0f), 1e-5f, "Sqrt")

  test("runtime: Exp"):
    val x = TensorExpr.Input("x", tensorType(f32, 3))
    val graph = singleOpGraph(
      TensorExpr.Exp(x),
      Vector(GraphIO("x", tensorType(f32, 3))),
      tensorType(f32, 3)
    )
    val result = runGraph(graph, Map("x" -> (Array(3L), Array(0.0f, 1.0f, -1.0f))))
    assertFinite(result(0).values, "Exp")
    assertEqualsFloat(result(0).values(0), 1.0f, 1e-5f)
    assertEqualsFloat(result(0).values(1), math.E.toFloat, 1e-5f)

  test("runtime: Neg"):
    val x = TensorExpr.Input("x", tensorType(f32, 3))
    val graph = singleOpGraph(
      TensorExpr.Neg(x),
      Vector(GraphIO("x", tensorType(f32, 3))),
      tensorType(f32, 3)
    )
    val result = runGraph(graph, Map("x" -> (Array(3L), Array(1.0f, -2.0f, 0.0f))))
    assertArrayEquals(result(0).values, Array(-1.0f, 2.0f, 0.0f), 1e-6f, "Neg")

  // ============================================================================
  // Category 4: Linear algebra
  // ============================================================================

  test("runtime: MatMul"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 3))
    val y = TensorExpr.Input("y", tensorType(f32, 3, 2))
    val graph = singleOpGraph(
      TensorExpr.MatMul(x, y, false, false),
      Vector(GraphIO("x", tensorType(f32, 2, 3)), GraphIO("y", tensorType(f32, 3, 2))),
      tensorType(f32, 2, 2)
    )
    // [[1,2,3],[4,5,6]] @ [[7,8],[9,10],[11,12]] = [[58,64],[139,154]]
    val result = runGraph(
      graph,
      Map(
        "x" -> (Array(2L, 3L), Array(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f)),
        "y" -> (Array(3L, 2L), Array(7.0f, 8.0f, 9.0f, 10.0f, 11.0f, 12.0f))
      )
    )
    assertShape(result(0).shape, Seq(2, 2), "MatMul")
    assertArrayEquals(result(0).values, Array(58.0f, 64.0f, 139.0f, 154.0f), 1e-4f, "MatMul")

  test("runtime: Linear (decomposes to Gemm with transB=1)"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 3))
    val wData = constantTensorData(Vector(4, 3), 0.1f)
    val bData = constantTensorData(Vector(4), 0.0f)
    val w = constant("w", Vector(4, 3), wData)
    val b = constant("b", Vector(4), bData)
    val graph = singleOpGraph(
      TensorExpr.Linear(x, w, Some(b)),
      Vector(GraphIO("x", tensorType(f32, 2, 3))),
      tensorType(f32, 2, 4)
    )
    // x=[1,1,1], w=0.1 (all), b=0 => output = 3*0.1 = 0.3 per element
    val result = runGraph(graph, Map("x" -> (Array(2L, 3L), Array.fill(6)(1.0f))))
    assertShape(result(0).shape, Seq(2, 4), "Linear")
    result(0).values.foreach(v => assertEqualsFloat(v, 0.3f, 1e-5f))

  test("runtime: Gemm with bias"):
    val a = TensorExpr.Input("a", tensorType(f32, 2, 3))
    val b = TensorExpr.Input("b", tensorType(f32, 3, 4))
    val cData = constantTensorData(Vector(2, 4), 1.0f)
    val c = constant("c", Vector(2, 4), cData)
    val graph = singleOpGraph(
      TensorExpr.Gemm(a, b, Some(c), 1.0, 1.0, false, false),
      Vector(GraphIO("a", tensorType(f32, 2, 3)), GraphIO("b", tensorType(f32, 3, 4))),
      tensorType(f32, 2, 4)
    )
    // a=1, b=0.5, c=1 => 1*0.5*3 + 1 = 2.5
    val result = runGraph(
      graph,
      Map(
        "a" -> (Array(2L, 3L), Array.fill(6)(1.0f)),
        "b" -> (Array(3L, 4L), Array.fill(12)(0.5f))
      )
    )
    assertShape(result(0).shape, Seq(2, 4), "Gemm")
    result(0).values.foreach(v => assertEqualsFloat(v, 2.5f, 1e-5f))

  // ============================================================================
  // Category 5: Shape manipulation
  // ============================================================================

  test("runtime: Reshape"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 3))
    val graph = singleOpGraph(
      TensorExpr.Reshape(x, Vector(6)),
      Vector(GraphIO("x", tensorType(f32, 2, 3))),
      tensorType(f32, 6)
    )
    val input = Array(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f)
    val result = runGraph(graph, Map("x" -> (Array(2L, 3L), input)))
    assertShape(result(0).shape, Seq(6), "Reshape")
    assertArrayEquals(result(0).values, input, 1e-6f, "Reshape")

  test("runtime: Transpose"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 3))
    val graph = singleOpGraph(
      TensorExpr.Transpose(x, Vector(1, 0)),
      Vector(GraphIO("x", tensorType(f32, 2, 3))),
      tensorType(f32, 3, 2)
    )
    // [[1,2,3],[4,5,6]] -> [[1,4],[2,5],[3,6]]
    val result =
      runGraph(graph, Map("x" -> (Array(2L, 3L), Array(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f))))
    assertShape(result(0).shape, Seq(3, 2), "Transpose")
    assertArrayEquals(
      result(0).values,
      Array(1.0f, 4.0f, 2.0f, 5.0f, 3.0f, 6.0f),
      1e-6f,
      "Transpose"
    )

  test("runtime: Flatten"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 3, 4))
    val graph = singleOpGraph(
      TensorExpr.Flatten(x, 1),
      Vector(GraphIO("x", tensorType(f32, 2, 3, 4))),
      tensorType(f32, 2, 12)
    )
    val input = Array.tabulate(24)(_.toFloat)
    val result = runGraph(graph, Map("x" -> (Array(2L, 3L, 4L), input)))
    assertShape(result(0).shape, Seq(2, 12), "Flatten")
    assertArrayEquals(result(0).values, input, 1e-6f, "Flatten")

  // ============================================================================
  // Category 6: Decomposed activations
  // ============================================================================

  test("runtime: Silu (Sigmoid + Mul decomposition)"):
    val x = TensorExpr.Input("x", tensorType(f32, 4))
    val graph = singleOpGraph(
      TensorExpr.Silu(x),
      Vector(GraphIO("x", tensorType(f32, 4))),
      tensorType(f32, 4)
    )
    val input = Array(0.0f, 1.0f, -1.0f, 2.0f)
    val result = runGraph(graph, Map("x" -> (Array(4L), input)))
    assertFinite(result(0).values, "Silu")
    assertEqualsFloat(result(0).values(0), 0.0f, 1e-5f)
    input.zip(result(0).values).foreach { case (xi, yi) =>
      val expected = xi * (1.0f / (1.0f + math.exp(-xi).toFloat))
      assertEqualsFloat(yi, expected, 1e-4f)
    }

  test("runtime: Gelu exact (5-op decomposition)"):
    val x = TensorExpr.Input("x", tensorType(f32, 4))
    val graph = singleOpGraph(
      TensorExpr.Gelu(x, approximate = false),
      Vector(GraphIO("x", tensorType(f32, 4))),
      tensorType(f32, 4)
    )
    val result = runGraph(graph, Map("x" -> (Array(4L), Array(0.0f, 1.0f, -1.0f, 2.0f))))
    assertFinite(result(0).values, "Gelu")
    assertEqualsFloat(result(0).values(0), 0.0f, 1e-5f)

  test("runtime: HardSwish (4-op decomposition)"):
    val x = TensorExpr.Input("x", tensorType(f32, 6))
    val graph = singleOpGraph(
      TensorExpr.HardSwish(x),
      Vector(GraphIO("x", tensorType(f32, 6))),
      tensorType(f32, 6)
    )
    val input = Array(-4.0f, -3.0f, 0.0f, 1.0f, 3.0f, 4.0f)
    val result = runGraph(graph, Map("x" -> (Array(6L), input)))
    assertFinite(result(0).values, "HardSwish")
    assertEqualsFloat(result(0).values(0), 0.0f, 1e-5f)
    assertEqualsFloat(result(0).values(5), 4.0f, 1e-5f)

  // ============================================================================
  // Category 7: Multi-layer models
  // ============================================================================

  test("runtime: two-layer MLP"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 4))
    val w1 = constant("w1", Vector(8, 4), constantTensorData(Vector(8, 4), 0.1f))
    val b1 = constant("b1", Vector(8), constantTensorData(Vector(8), 0.0f))
    val w2 = constant("w2", Vector(3, 8), constantTensorData(Vector(3, 8), 0.1f))
    val b2 = constant("b2", Vector(3), constantTensorData(Vector(3), 0.0f))
    val linear1 = TensorExpr.Linear(x, w1, Some(b1))
    val relu = TensorExpr.Relu(linear1)
    val linear2 = TensorExpr.Linear(relu, w2, Some(b2))
    val graph = ComputeGraph(
      name = "mlp",
      inputs = Vector(GraphIO("x", tensorType(f32, 2, 4))),
      outputs = Vector(GraphIO("logits", tensorType(f32, 2, 3))),
      nodes = Vector(
        NamedNode("hidden", linear1, tensorType(f32, 2, 8)),
        NamedNode("activated", relu, tensorType(f32, 2, 8)),
        NamedNode("logits", linear2, tensorType(f32, 2, 3))
      )
    )
    val result = runGraph(graph, Map("x" -> (Array(2L, 4L), Array.fill(8)(1.0f))))
    assertShape(result(0).shape, Seq(2, 3), "MLP")
    assertFinite(result(0).values, "MLP")
    result(0).values.foreach(v => assert(v >= 0.0f, s"Expected non-negative MLP output, got $v"))

  test("runtime: residual block (Relu(x) + x)"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 3))
    val reluExpr = TensorExpr.Relu(x)
    val addExpr = TensorExpr.Add(reluExpr, x)
    val graph = ComputeGraph(
      name = "residual",
      inputs = Vector(GraphIO("x", tensorType(f32, 2, 3))),
      outputs = Vector(GraphIO("add_out", tensorType(f32, 2, 3))),
      nodes = Vector(
        NamedNode("relu_out", reluExpr, tensorType(f32, 2, 3)),
        NamedNode("add_out", addExpr, tensorType(f32, 2, 3))
      )
    )
    val input = Array(-1.0f, 2.0f, -3.0f, 4.0f, -5.0f, 6.0f)
    val result = runGraph(graph, Map("x" -> (Array(2L, 3L), input)))
    assertArrayEquals(
      result(0).values,
      Array(-1.0f, 4.0f, -3.0f, 8.0f, -5.0f, 12.0f),
      1e-5f,
      "Residual"
    )

  test("runtime: Conv + Relu"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 1, 4, 4))
    val wData = constantTensorData(Vector(1, 1, 3, 3), 1.0f / 9.0f)
    val w = constant("conv_w", Vector(1, 1, 3, 3), wData)
    val conv = TensorExpr.Conv(x, w, None, Vector(1, 1), Vector(0, 0, 0, 0), Vector(1, 1), 1)
    val relu = TensorExpr.Relu(conv)
    val outType = tensorType(f32, 1, 1, 2, 2)
    val graph = ComputeGraph(
      name = "conv_relu",
      inputs = Vector(GraphIO("x", tensorType(f32, 1, 1, 4, 4))),
      outputs = Vector(GraphIO("out", outType)),
      nodes = Vector(
        NamedNode("conv_out", conv, outType),
        NamedNode("out", relu, outType)
      )
    )
    val result = runGraph(graph, Map("x" -> (Array(1L, 1L, 4L, 4L), Array.fill(16)(1.0f))))
    assertShape(result(0).shape, Seq(1, 1, 2, 2), "Conv+Relu")
    result(0).values.foreach(v => assertEqualsFloat(v, 1.0f, 1e-4f))

  // ============================================================================
  // Error handling: custom domain ops
  // ============================================================================

  test("runtime: OpaqueOp with custom domain is rejected by ONNX Runtime"):
    val x = TensorExpr.Input("x", tensorType(f32, 4))
    val graph = singleOpGraph(
      TensorExpr.OpaqueOp(
        "MyCustomOp",
        Vector(x),
        Map("threshold" -> OpAttribute.FloatAttr(0.5)),
        tensorType(f32, 4)
      ),
      Vector(GraphIO("x", tensorType(f32, 4))),
      tensorType(f32, 4)
    )
    intercept[Exception]:
      runGraph(graph, Map("x" -> (Array(4L), Array(1.0f, 2.0f, 3.0f, 4.0f))))
