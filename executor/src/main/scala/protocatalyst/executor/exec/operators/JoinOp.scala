package protocatalyst.executor.exec.operators

import scala.collection.mutable

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector._

import protocatalyst.arrow.ArrowSchemaConverter
import protocatalyst.executor.exec._
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan.JoinType
import protocatalyst.schema.ProtoSchema

/** Join operator: nested-loop join with optional condition.
  *
  * Supports all 7 join types: Inner, LeftOuter, RightOuter, FullOuter, LeftSemi, LeftAnti, Cross.
  */
object JoinOp:

  def execute(
      left: Batch,
      right: Batch,
      joinType: JoinType,
      condition: Option[ProtoExpr],
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): Batch =
    joinType match
      case JoinType.LeftSemi => semiJoin(left, right, condition, evaluator, allocator, anti = false)
      case JoinType.LeftAnti => semiJoin(left, right, condition, evaluator, allocator, anti = true)
      case _ => nestedLoopJoin(left, right, joinType, condition, evaluator, allocator)

  private def nestedLoopJoin(
      left: Batch,
      right: Batch,
      joinType: JoinType,
      condition: Option[ProtoExpr],
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): Batch =
    // Output schema: left fields + right fields
    val outputFields = left.schema.fields ++ right.schema.fields
    val outputSchema = ProtoSchema(outputFields)
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(outputSchema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.allocateNew()

    val leftMatched = mutable.BitSet()
    val rightMatched = mutable.BitSet()
    var outputRow = 0

    for li <- 0 until left.rowCount do
      var hasMatch = false
      for ri <- 0 until right.rowCount do
        val matches = condition match
          case None       => true
          case Some(cond) =>
            // Build combined batch for condition evaluation
            val combined = combineSingleRow(left, li, right, ri, outputSchema, evaluator, allocator)
            val condVec = evaluator.eval(cond, combined)
            val result = !condVec.isNull(0) && condVec.asInstanceOf[BitVector].get(0) != 0
            combined.close()
            result

        if matches then
          hasMatch = true
          leftMatched += li
          rightMatched += ri
          joinType match
            case JoinType.Inner | JoinType.LeftOuter | JoinType.RightOuter | JoinType.FullOuter |
                JoinType.Cross =>
              emitRow(left, li, right, ri, root, outputRow, evaluator, outputFields.size)
              outputRow += 1
            case _ => // handled separately

      // Left outer: emit left row with nulls for right side if no match
      if !hasMatch && (joinType == JoinType.LeftOuter || joinType == JoinType.FullOuter) then
        emitLeftWithNulls(left, li, right.numColumns, root, outputRow, evaluator, left.numColumns)
        outputRow += 1

    // Right outer / full outer: emit unmatched right rows with null left
    if joinType == JoinType.RightOuter || joinType == JoinType.FullOuter then
      for ri <- 0 until right.rowCount do
        if !rightMatched.contains(ri) then
          emitRightWithNulls(
            right,
            ri,
            left.numColumns,
            root,
            outputRow,
            evaluator,
            outputFields.size
          )
          outputRow += 1

    root.setRowCount(outputRow)
    Batch.fromRoot(root, outputSchema)

  private def semiJoin(
      left: Batch,
      right: Batch,
      condition: Option[ProtoExpr],
      evaluator: ExprEvaluator,
      allocator: BufferAllocator,
      anti: Boolean
  ): Batch =
    // Output schema is just left fields
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(left.schema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.allocateNew()

    val combinedFields = left.schema.fields ++ right.schema.fields
    val combinedSchema = ProtoSchema(combinedFields)
    var outputRow = 0

    for li <- 0 until left.rowCount do
      var hasMatch = false
      var ri = 0
      while !hasMatch && ri < right.rowCount do
        val matches = condition match
          case None       => true
          case Some(cond) =>
            val combined =
              combineSingleRow(left, li, right, ri, combinedSchema, evaluator, allocator)
            val condVec = evaluator.eval(cond, combined)
            val result = !condVec.isNull(0) && condVec.asInstanceOf[BitVector].get(0) != 0
            combined.close()
            result
        if matches then hasMatch = true
        ri += 1

      val emit = if anti then !hasMatch else hasMatch
      if emit then
        for colIdx <- 0 until left.numColumns do
          evaluator.copyValue(left.root.getVector(colIdx), li, root.getVector(colIdx), outputRow)
        outputRow += 1

    root.setRowCount(outputRow)
    Batch.fromRoot(root, left.schema)

  /** Build a single-row combined batch for condition evaluation. */
  private[executor] def combineSingleRow(
      left: Batch,
      leftRow: Int,
      right: Batch,
      rightRow: Int,
      schema: ProtoSchema,
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): Batch =
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(schema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.allocateNew()

    for colIdx <- 0 until left.numColumns do
      evaluator.copyValue(left.root.getVector(colIdx), leftRow, root.getVector(colIdx), 0)
    for colIdx <- 0 until right.numColumns do
      evaluator.copyValue(
        right.root.getVector(colIdx),
        rightRow,
        root.getVector(left.numColumns + colIdx),
        0
      )

    root.setRowCount(1)
    Batch.fromRoot(root, schema)

  private def emitRow(
      left: Batch,
      li: Int,
      right: Batch,
      ri: Int,
      root: VectorSchemaRoot,
      outputRow: Int,
      evaluator: ExprEvaluator,
      totalCols: Int
  ): Unit =
    for colIdx <- 0 until left.numColumns do
      evaluator.copyValue(left.root.getVector(colIdx), li, root.getVector(colIdx), outputRow)
    for colIdx <- 0 until right.numColumns do
      evaluator.copyValue(
        right.root.getVector(colIdx),
        ri,
        root.getVector(left.numColumns + colIdx),
        outputRow
      )

  private def emitLeftWithNulls(
      left: Batch,
      li: Int,
      rightCols: Int,
      root: VectorSchemaRoot,
      outputRow: Int,
      evaluator: ExprEvaluator,
      leftCols: Int
  ): Unit =
    for colIdx <- 0 until leftCols do
      evaluator.copyValue(left.root.getVector(colIdx), li, root.getVector(colIdx), outputRow)
    for colIdx <- leftCols until leftCols + rightCols do
      Batch.setNull(root.getVector(colIdx), outputRow)

  private def emitRightWithNulls(
      right: Batch,
      ri: Int,
      leftCols: Int,
      root: VectorSchemaRoot,
      outputRow: Int,
      evaluator: ExprEvaluator,
      totalCols: Int
  ): Unit =
    for colIdx <- 0 until leftCols do Batch.setNull(root.getVector(colIdx), outputRow)
    for colIdx <- 0 until right.numColumns do
      evaluator.copyValue(
        right.root.getVector(colIdx),
        ri,
        root.getVector(leftCols + colIdx),
        outputRow
      )
