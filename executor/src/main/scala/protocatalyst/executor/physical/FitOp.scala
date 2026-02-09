package protocatalyst.executor.physical

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector._

import protocatalyst.arrow.ArrowSchemaConverter
import protocatalyst.executor.exec._
import protocatalyst.expr.ProtoExpr
import protocatalyst.ml.graph.ComputeGraph
import protocatalyst.ml.optimizer.Autograd
import protocatalyst.plan.{OptimizerType, TrainConfig}
import protocatalyst.schema.ProtoSchema
import protocatalyst.types._

/** Executes ML model training (Fit) on Arrow batches.
  *
  *   1. Extract features and labels from input batch via inputMapping and labelMapping 2. Convert
  *      Arrow vectors → float arrays 3. Build gradient graph via Autograd.backward() 4. Initialize
  *      parameters from Parameter nodes 5. Training loop: for each epoch, run trainEpoch and record
  *      loss
  *   6. Return metrics batch: (epoch: INT, loss: DOUBLE)
  */
object FitOp:

  def execute(
      input: Batch,
      model: ComputeGraph,
      inputMapping: Vector[(String, ProtoExpr)],
      labelMapping: Vector[(String, ProtoExpr)],
      trainConfig: TrainConfig,
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): Batch =
    val rowCount = input.rowCount
    if rowCount == 0 then return emptyMetricsBatch(allocator)

    // 1. Extract input and label tensors from Arrow batch
    val featureTensors = inputMapping.map { (inputName, expr) =>
      val vec = evaluator.eval(expr, input)
      inputName -> arrowToTensor(vec, rowCount)
    }.toMap

    val labelTensors = labelMapping.map { (labelName, expr) =>
      val vec = evaluator.eval(expr, input)
      labelName -> arrowToTensor(vec, rowCount)
    }.toMap

    val allInputs = featureTensors ++ labelTensors

    // 2. Build gradient graph using Autograd
    val gradGraph = Autograd.backward(model, trainConfig.lossNode)

    // 3. Initialize parameters
    val params = GraphEvaluator.initializeParams(model)

    // 4. Create Adam state if needed
    val adamState = trainConfig.optimizer match
      case OptimizerType.Adam => Some(GraphEvaluator.AdamState())
      case OptimizerType.SGD  => None

    // 5. Training loop
    val epochs = trainConfig.epochs
    val losses = new Array[Double](epochs)

    for epoch <- 0 until epochs do
      val loss = GraphEvaluator.trainEpoch(
        model,
        gradGraph,
        allInputs,
        params,
        trainConfig,
        adamState
      )
      losses(epoch) = loss

    // 6. Build output metrics batch: (epoch: INT, loss: DOUBLE)
    buildMetricsBatch(epochs, losses, allocator)

  /** Build the metrics output batch with epoch and loss columns. */
  private def buildMetricsBatch(
      epochs: Int,
      losses: Array[Double],
      allocator: BufferAllocator
  ): Batch =
    val schema = metricsSchema
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(schema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.allocateNew()

    val epochVec = root.getVector(0).asInstanceOf[IntVector]
    val lossVec = root.getVector(1).asInstanceOf[Float8Vector]

    for i <- 0 until epochs do
      epochVec.setSafe(i, i + 1) // 1-based epoch numbering
      lossVec.setSafe(i, losses(i))

    root.setRowCount(epochs)
    Batch.fromRoot(root, schema)

  private def emptyMetricsBatch(allocator: BufferAllocator): Batch =
    Batch.empty(metricsSchema, allocator)

  private val metricsSchema = ProtoSchema(
    Vector(
      ProtoStructField("epoch", ProtoType.IntegerType, nullable = false),
      ProtoStructField("loss", ProtoType.DoubleType, nullable = false)
    )
  )

  /** Convert an Arrow FieldVector to a GraphEvaluator.Tensor. */
  private def arrowToTensor(vec: FieldVector, rowCount: Int): GraphEvaluator.Tensor =
    val data = new Array[Float](rowCount)
    for i <- 0 until rowCount do
      if vec.isNull(i) then data(i) = 0.0f
      else
        data(i) = vec match
          case v: Float4Vector   => v.get(i)
          case v: Float8Vector   => v.get(i).toFloat
          case v: IntVector      => v.get(i).toFloat
          case v: BigIntVector   => v.get(i).toFloat
          case v: SmallIntVector => v.get(i).toFloat
          case v: TinyIntVector  => v.get(i).toFloat
          case v: BitVector      => v.get(i).toFloat
          case _                 =>
            val obj = Batch.getValue(vec, i)
            obj match
              case n: Number => n.floatValue()
              case _         => 0.0f
    GraphEvaluator.Tensor(data, Vector(rowCount))
