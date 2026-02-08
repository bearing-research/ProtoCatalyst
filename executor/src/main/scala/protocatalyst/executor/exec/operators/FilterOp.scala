package protocatalyst.executor.exec.operators

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector._

import protocatalyst.arrow.ArrowSchemaConverter
import protocatalyst.executor.exec._
import protocatalyst.expr.ProtoExpr

/** Filter operator: evaluates a boolean condition and keeps matching rows. */
object FilterOp:

  def execute(
      input: Batch,
      condition: ProtoExpr,
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): Batch =
    // Evaluate condition to a BitVector
    val condVec = evaluator.eval(condition, input).asInstanceOf[BitVector]

    // Count matching rows
    val matchingIndices = (0 until input.rowCount).filter { i =>
      !condVec.isNull(i) && condVec.get(i) != 0
    }

    if matchingIndices.size == input.rowCount then
      // No rows filtered — return input as-is
      return input

    // Build filtered output
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(input.schema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.allocateNew()

    for (srcIdx, dstIdx) <- matchingIndices.zipWithIndex do
      for colIdx <- 0 until input.numColumns do
        evaluator.copyValue(
          input.root.getVector(colIdx),
          srcIdx,
          root.getVector(colIdx),
          dstIdx
        )

    root.setRowCount(matchingIndices.size)
    Batch.fromRoot(root, input.schema)
