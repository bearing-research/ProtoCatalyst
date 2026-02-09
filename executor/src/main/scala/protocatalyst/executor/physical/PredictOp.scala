package protocatalyst.executor.physical

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector._

import protocatalyst.arrow.ArrowSchemaConverter
import protocatalyst.executor.exec._
import protocatalyst.expr.ProtoExpr
import protocatalyst.ml.graph.ComputeGraph
import protocatalyst.schema.ProtoSchema
import protocatalyst.types._

/** Executes ML model inference (Predict) on Arrow batches.
  *
  *   1. Evaluate inputMapping expressions against the input Batch to extract feature columns 2.
  *      Convert Arrow vectors → flat Array[Float] tensors (row-major, shape [batchSize, features]) 3.
  *      Run GraphEvaluator.forward(model, inputs) to produce output tensors 4. Convert output
  *      tensors → new Arrow columns 5. Append output columns to input batch, producing the result
  *      Batch
  */
object PredictOp:

  def execute(
      input: Batch,
      model: ComputeGraph,
      inputMapping: Vector[(String, ProtoExpr)],
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): Batch =
    val rowCount = input.rowCount
    if rowCount == 0 then return input

    // 1. Extract input tensors from Arrow batch via inputMapping
    val inputTensors = inputMapping.map { (inputName, expr) =>
      val vec = evaluator.eval(expr, input)
      val tensor = arrowToTensor(vec, rowCount)
      inputName -> tensor
    }.toMap

    // 2. Forward pass through the model
    val outputs = GraphEvaluator.forward(model, inputTensors)

    // 3. Build output schema: input fields + model output fields
    val outputFields = model.outputs.map { io =>
      ProtoStructField(io.name, ProtoType.FloatType, nullable = true)
    }
    val outputSchema = ProtoSchema(input.schema.fields ++ outputFields)

    // 4. Create output Arrow vectors
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(outputSchema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.allocateNew()

    // Copy input columns
    for colIdx <- 0 until input.numColumns do
      val src = input.root.getVector(colIdx)
      val dst = root.getVector(colIdx)
      for i <- 0 until rowCount do evaluator.copyValue(src, i, dst, i)

    // Write model output columns
    for (io, outIdx) <- model.outputs.zipWithIndex do
      val tensor = outputs.getOrElse(
        io.name,
        throw ExecutionException(s"Model output '${io.name}' not produced")
      )
      val vec = root.getVector(input.numColumns + outIdx).asInstanceOf[Float4Vector]
      for i <- 0 until rowCount do
        if i < tensor.data.length then vec.setSafe(i, tensor.data(i))
        else vec.setSafe(i, 0.0f)

    root.setRowCount(rowCount)
    Batch.fromRoot(root, outputSchema)

  /** Convert an Arrow FieldVector to a GraphEvaluator.Tensor.
    *
    * Numeric columns are converted to Float. Shape is [rowCount] for a single column or [rowCount,
    * 1] for consistency with model expectations.
    */
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
