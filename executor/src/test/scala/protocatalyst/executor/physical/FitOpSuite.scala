package protocatalyst.executor.physical

import org.apache.arrow.memory.BufferAllocator

import protocatalyst.arrow.ArrowAllocator
import protocatalyst.executor.exec._
import protocatalyst.expr.ProtoExpr
import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph._
import protocatalyst.ml.types._
import protocatalyst.plan.{OptimizerType, TrainConfig}
import protocatalyst.schema.ProtoSchema
import protocatalyst.types._

class FitOpSuite extends munit.FunSuite:

  private var allocator: BufferAllocator = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    allocator = ArrowAllocator.createRoot()

  override def afterAll(): Unit = ()

  private def f32Type(dims: Int*): TensorType =
    TensorType(TensorDType.Float32, dims.map(Dim.Static(_)).toVector)

  private def scalarType: TensorType =
    TensorType(TensorDType.Float32, Vector.empty)

  test("FitOp: linear regression training reduces loss") {
    // Train y = 2*x + 1 from data
    val xInput = TensorExpr.Input("x", f32Type(4))
    val yInput = TensorExpr.Input("y", f32Type(4))
    val w = TensorExpr.Parameter("w", f32Type(1), Some(Initializer.Zeros))
    val b = TensorExpr.Parameter("b", scalarType, Some(Initializer.Zeros))

    val xw = TensorExpr.Mul(xInput, w)
    val yPred = TensorExpr.Add(xw, b)
    val loss = TensorExpr.MSELoss(yPred, yInput, Reduction.Mean)

    val model = ComputeGraph(
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

    // Training data: y = 2*x + 1
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("feature", ProtoType.FloatType, nullable = false),
        ProtoStructField("label", ProtoType.FloatType, nullable = false)
      )
    )
    val batch = Batch.fromValues(
      Vector(
        Vector(
          ProtoExpr.Literal(LiteralValue.FloatValue(1.0f)),
          ProtoExpr.Literal(LiteralValue.FloatValue(3.0f))
        ),
        Vector(
          ProtoExpr.Literal(LiteralValue.FloatValue(2.0f)),
          ProtoExpr.Literal(LiteralValue.FloatValue(5.0f))
        ),
        Vector(
          ProtoExpr.Literal(LiteralValue.FloatValue(3.0f)),
          ProtoExpr.Literal(LiteralValue.FloatValue(7.0f))
        ),
        Vector(
          ProtoExpr.Literal(LiteralValue.FloatValue(4.0f)),
          ProtoExpr.Literal(LiteralValue.FloatValue(9.0f))
        )
      ),
      schema,
      allocator
    )

    val evaluator = ExprEvaluator(allocator)
    val trainConfig = TrainConfig(
      lossNode = "loss",
      epochs = 100,
      learningRate = 0.01,
      optimizer = OptimizerType.SGD
    )

    val result = FitOp.execute(
      batch,
      model,
      inputMapping =
        Vector("x" -> ProtoExpr.ColumnRef("feature", None, ProtoType.FloatType, nullable = false)),
      labelMapping =
        Vector("y" -> ProtoExpr.ColumnRef("label", None, ProtoType.FloatType, nullable = false)),
      trainConfig = trainConfig,
      evaluator = evaluator,
      allocator = allocator
    )

    // Output should be (epoch: INT, loss: DOUBLE) with 100 rows
    assertEquals(result.numColumns, 2)
    assertEquals(result.rowCount, 100)
    assertEquals(result.schema.fields(0).name, "epoch")
    assertEquals(result.schema.fields(1).name, "loss")

    // Epoch numbers should be 1-based
    val epochVec = result.root.getVector(0).asInstanceOf[org.apache.arrow.vector.IntVector]
    assertEquals(epochVec.get(0), 1: Int)
    assertEquals(epochVec.get(99), 100: Int)

    // Loss should decrease — first loss > last loss
    val lossVec = result.root.getVector(1).asInstanceOf[org.apache.arrow.vector.Float8Vector]
    val firstLoss = lossVec.get(0)
    val lastLoss = lossVec.get(99)
    assert(lastLoss < firstLoss, s"Loss should decrease: first=$firstLoss, last=$lastLoss")
    assert(lastLoss < 0.1, s"Final loss $lastLoss should be < 0.1")
  }

  test("FitOp: Adam optimizer converges") {
    val xInput = TensorExpr.Input("x", f32Type(4))
    val yInput = TensorExpr.Input("y", f32Type(4))
    val w = TensorExpr.Parameter("w", f32Type(1), Some(Initializer.Zeros))
    val b = TensorExpr.Parameter("b", scalarType, Some(Initializer.Zeros))

    val xw = TensorExpr.Mul(xInput, w)
    val yPred = TensorExpr.Add(xw, b)
    val loss = TensorExpr.MSELoss(yPred, yInput, Reduction.Mean)

    val model = ComputeGraph(
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

    val schema = ProtoSchema(
      Vector(
        ProtoStructField("feature", ProtoType.FloatType, nullable = false),
        ProtoStructField("label", ProtoType.FloatType, nullable = false)
      )
    )
    val batch = Batch.fromValues(
      Vector(
        Vector(
          ProtoExpr.Literal(LiteralValue.FloatValue(1.0f)),
          ProtoExpr.Literal(LiteralValue.FloatValue(3.0f))
        ),
        Vector(
          ProtoExpr.Literal(LiteralValue.FloatValue(2.0f)),
          ProtoExpr.Literal(LiteralValue.FloatValue(5.0f))
        ),
        Vector(
          ProtoExpr.Literal(LiteralValue.FloatValue(3.0f)),
          ProtoExpr.Literal(LiteralValue.FloatValue(7.0f))
        ),
        Vector(
          ProtoExpr.Literal(LiteralValue.FloatValue(4.0f)),
          ProtoExpr.Literal(LiteralValue.FloatValue(9.0f))
        )
      ),
      schema,
      allocator
    )

    val evaluator = ExprEvaluator(allocator)
    val trainConfig = TrainConfig(
      lossNode = "loss",
      epochs = 50,
      learningRate = 0.1,
      optimizer = OptimizerType.Adam
    )

    val result = FitOp.execute(
      batch,
      model,
      inputMapping =
        Vector("x" -> ProtoExpr.ColumnRef("feature", None, ProtoType.FloatType, nullable = false)),
      labelMapping =
        Vector("y" -> ProtoExpr.ColumnRef("label", None, ProtoType.FloatType, nullable = false)),
      trainConfig = trainConfig,
      evaluator = evaluator,
      allocator = allocator
    )

    assertEquals(result.rowCount, 50)

    val lossVec = result.root.getVector(1).asInstanceOf[org.apache.arrow.vector.Float8Vector]
    val lastLoss = lossVec.get(49)
    assert(lastLoss < 1.0, s"Adam final loss $lastLoss should be < 1.0")
  }

  test("FitOp: empty batch returns empty metrics") {
    val xInput = TensorExpr.Input("x", f32Type(1))
    val yInput = TensorExpr.Input("y", f32Type(1))
    val loss = TensorExpr.MSELoss(xInput, yInput, Reduction.Mean)

    val model = ComputeGraph(
      name = "empty_test",
      inputs = Vector(GraphIO("x", f32Type(1)), GraphIO("y", f32Type(1))),
      outputs = Vector(GraphIO("loss", scalarType)),
      nodes = Vector(NamedNode("loss", loss, scalarType))
    )

    val schema = ProtoSchema(
      Vector(
        ProtoStructField("feature", ProtoType.FloatType, nullable = false),
        ProtoStructField("label", ProtoType.FloatType, nullable = false)
      )
    )
    val batch = Batch.empty(schema, allocator)

    val evaluator = ExprEvaluator(allocator)
    val trainConfig = TrainConfig("loss", 10, 0.01, OptimizerType.SGD)

    val result = FitOp.execute(
      batch,
      model,
      inputMapping =
        Vector("x" -> ProtoExpr.ColumnRef("feature", None, ProtoType.FloatType, nullable = false)),
      labelMapping =
        Vector("y" -> ProtoExpr.ColumnRef("label", None, ProtoType.FloatType, nullable = false)),
      trainConfig = trainConfig,
      evaluator = evaluator,
      allocator = allocator
    )

    assertEquals(result.rowCount, 0)
    assertEquals(result.numColumns, 2)
  }
