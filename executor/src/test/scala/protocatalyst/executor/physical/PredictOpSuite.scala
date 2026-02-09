package protocatalyst.executor.physical

import java.nio.{ByteBuffer, ByteOrder}

import org.apache.arrow.memory.BufferAllocator

import protocatalyst.arrow.ArrowAllocator
import protocatalyst.executor.exec._
import protocatalyst.expr.ProtoExpr
import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph._
import protocatalyst.ml.types._
import protocatalyst.schema.ProtoSchema
import protocatalyst.types._

class PredictOpSuite extends munit.FunSuite:

  private var allocator: BufferAllocator = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    allocator = ArrowAllocator.createRoot()

  override def afterAll(): Unit = ()

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

  test("PredictOp: identity model passes input through") {
    // Model: output = input (identity)
    val inputExpr = TensorExpr.Input("x", f32Type(3))
    val model = ComputeGraph(
      name = "identity",
      inputs = Vector(GraphIO("x", f32Type(3))),
      outputs = Vector(GraphIO("result", f32Type(3))),
      nodes = Vector(NamedNode("result", inputExpr, f32Type(3)))
    )

    // Input batch: 3 rows with a float column
    val schema = ProtoSchema(
      Vector(ProtoStructField("value", ProtoType.FloatType, nullable = false))
    )
    val batch = Batch.fromValues(
      Vector(
        Vector(ProtoExpr.Literal(LiteralValue.FloatValue(1.0f))),
        Vector(ProtoExpr.Literal(LiteralValue.FloatValue(2.0f))),
        Vector(ProtoExpr.Literal(LiteralValue.FloatValue(3.0f)))
      ),
      schema,
      allocator
    )

    val evaluator = ExprEvaluator(allocator)
    val inputMapping =
      Vector("x" -> ProtoExpr.ColumnRef("value", None, ProtoType.FloatType, nullable = false))
    val result = PredictOp.execute(batch, model, inputMapping, evaluator, allocator)

    // Output should have 2 columns: value + result
    assertEquals(result.numColumns, 2)
    assertEquals(result.rowCount, 3)
    assertEquals(result.schema.fields(1).name, "result")
  }

  test("PredictOp: linear model produces output column") {
    // Model: output = x * 2 + 1
    val inputExpr = TensorExpr.Input("x", f32Type(3))
    val two = constExpr("two", Vector(1), 2.0f)
    val one = constExpr("one", Vector(1), 1.0f)
    val mul = TensorExpr.Mul(inputExpr, two)
    val add = TensorExpr.Add(mul, one)

    val model = ComputeGraph(
      name = "linear_2x_plus_1",
      inputs = Vector(GraphIO("x", f32Type(3))),
      outputs = Vector(GraphIO("prediction", f32Type(3))),
      nodes = Vector(
        NamedNode("two", two, f32Type(1)),
        NamedNode("one", one, f32Type(1)),
        NamedNode("mul", mul, f32Type(3)),
        NamedNode("prediction", add, f32Type(3))
      )
    )

    val schema = ProtoSchema(
      Vector(
        ProtoStructField("id", ProtoType.IntegerType, nullable = false),
        ProtoStructField("feature", ProtoType.FloatType, nullable = false)
      )
    )
    val batch = Batch.fromValues(
      Vector(
        Vector(ProtoExpr.lit(1), ProtoExpr.Literal(LiteralValue.FloatValue(10.0f))),
        Vector(ProtoExpr.lit(2), ProtoExpr.Literal(LiteralValue.FloatValue(20.0f))),
        Vector(ProtoExpr.lit(3), ProtoExpr.Literal(LiteralValue.FloatValue(30.0f)))
      ),
      schema,
      allocator
    )

    val evaluator = ExprEvaluator(allocator)
    val inputMapping =
      Vector("x" -> ProtoExpr.ColumnRef("feature", None, ProtoType.FloatType, nullable = false))
    val result = PredictOp.execute(batch, model, inputMapping, evaluator, allocator)

    // Output should have 3 columns: id, feature, prediction
    assertEquals(result.numColumns, 3)
    assertEquals(result.schema.fields(2).name, "prediction")

    // prediction values should be 2*feature + 1 = 21, 41, 61
    val predVec = result.root.getVector(2).asInstanceOf[org.apache.arrow.vector.Float4Vector]
    assertEquals(predVec.get(0), 21.0f, 0.01f)
    assertEquals(predVec.get(1), 41.0f, 0.01f)
    assertEquals(predVec.get(2), 61.0f, 0.01f)
  }

  test("PredictOp: sigmoid model outputs between 0 and 1") {
    val inputExpr = TensorExpr.Input("x", f32Type(4))
    val sig = TensorExpr.Sigmoid(inputExpr)

    val model = ComputeGraph(
      name = "sigmoid_model",
      inputs = Vector(GraphIO("x", f32Type(4))),
      outputs = Vector(GraphIO("prob", f32Type(4))),
      nodes = Vector(NamedNode("prob", sig, f32Type(4)))
    )

    val schema = ProtoSchema(
      Vector(ProtoStructField("score", ProtoType.FloatType, nullable = false))
    )
    val batch = Batch.fromValues(
      Vector(
        Vector(ProtoExpr.Literal(LiteralValue.FloatValue(-10.0f))),
        Vector(ProtoExpr.Literal(LiteralValue.FloatValue(0.0f))),
        Vector(ProtoExpr.Literal(LiteralValue.FloatValue(10.0f))),
        Vector(ProtoExpr.Literal(LiteralValue.FloatValue(100.0f)))
      ),
      schema,
      allocator
    )

    val evaluator = ExprEvaluator(allocator)
    val inputMapping =
      Vector("x" -> ProtoExpr.ColumnRef("score", None, ProtoType.FloatType, nullable = false))
    val result = PredictOp.execute(batch, model, inputMapping, evaluator, allocator)

    val predVec = result.root.getVector(1).asInstanceOf[org.apache.arrow.vector.Float4Vector]
    for i <- 0 until 4 do
      val v = predVec.get(i)
      assert(v >= 0.0f && v <= 1.0f, s"Sigmoid output at $i should be in [0,1], got $v")

    // sigmoid(0) ≈ 0.5
    assertEquals(predVec.get(1), 0.5f, 0.01f)
  }
