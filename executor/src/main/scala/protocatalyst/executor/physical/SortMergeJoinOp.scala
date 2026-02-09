package protocatalyst.executor.physical

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector._

import protocatalyst.arrow.ArrowSchemaConverter
import protocatalyst.executor.exec._
import protocatalyst.executor.exec.operators.{JoinOp, SortOp}
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan.JoinType
import protocatalyst.schema.ProtoSchema

/** Sort-merge join operator: sort both sides by join keys, then merge.
  *
  * Requires equi-join keys (`leftKeys[i] = rightKeys[i]`). An optional residual condition is
  * applied after the key match. Both inputs are sorted by their respective keys before merging.
  *
  * Sort-merge join is efficient for large, pre-sorted inputs and avoids building an in-memory hash
  * table. All 7 join types are supported.
  */
object SortMergeJoinOp:

  def execute(
      left: Batch,
      right: Batch,
      joinType: JoinType,
      leftKeys: Vector[ProtoExpr],
      rightKeys: Vector[ProtoExpr],
      condition: Option[ProtoExpr],
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): Batch =
    // Sort both sides by their join keys (ascending, nulls last)
    val sortedLeft = sortByKeys(left, leftKeys, evaluator, allocator)
    val sortedRight = sortByKeys(right, rightKeys, evaluator, allocator)

    // Extract key values for sorted inputs
    val leftKeyValues = extractAllKeys(sortedLeft, leftKeys, evaluator)
    val rightKeyValues = extractAllKeys(sortedRight, rightKeys, evaluator)

    joinType match
      case JoinType.LeftSemi =>
        mergeSemiJoin(
          sortedLeft,
          sortedRight,
          leftKeyValues,
          rightKeyValues,
          condition,
          evaluator,
          allocator,
          anti = false
        )
      case JoinType.LeftAnti =>
        mergeSemiJoin(
          sortedLeft,
          sortedRight,
          leftKeyValues,
          rightKeyValues,
          condition,
          evaluator,
          allocator,
          anti = true
        )
      case _ =>
        mergeJoin(
          sortedLeft,
          sortedRight,
          joinType,
          leftKeyValues,
          rightKeyValues,
          condition,
          evaluator,
          allocator
        )

  /** Sort a batch by key expressions (ascending, nulls last). */
  private def sortByKeys(
      batch: Batch,
      keys: Vector[ProtoExpr],
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): Batch =
    import protocatalyst.plan.{SortDirection, SortOrder, NullOrdering}
    val order = keys.map(k => SortOrder(k, SortDirection.Ascending, NullOrdering.NullsLast))
    SortOp.execute(batch, order, evaluator, allocator)

  /** Pre-extract all key values for every row in a batch. */
  private def extractAllKeys(
      batch: Batch,
      keys: Vector[ProtoExpr],
      evaluator: ExprEvaluator
  ): Vector[Vector[Any]] =
    val keyVecs = keys.map(k => evaluator.eval(k, batch))
    (0 until batch.rowCount).map { row =>
      keyVecs.map(vec => Batch.getValue(vec, row))
    }.toVector

  /** Compare two key vectors. Returns negative, zero, or positive. Null keys sort last (compare as
    * greater than any non-null).
    */
  private def compareKeys(a: Vector[Any], b: Vector[Any]): Int =
    import scala.util.boundary, boundary.break
    boundary:
      for i <- a.indices do
        val aNull = a(i) == null
        val bNull = b(i) == null
        val cmp =
          if aNull && bNull then 0
          else if aNull then 1 // nulls last
          else if bNull then -1
          else SortOp.compareValues(a(i), b(i))
        if cmp != 0 then break(cmp)
      0

  /** True if any element of the key vector is null. */
  private def hasNullKey(key: Vector[Any]): Boolean = key.contains(null)

  /** Core merge join for Inner, LeftOuter, RightOuter, FullOuter, Cross. */
  private def mergeJoin(
      left: Batch,
      right: Batch,
      joinType: JoinType,
      leftKeys: Vector[Vector[Any]],
      rightKeys: Vector[Vector[Any]],
      condition: Option[ProtoExpr],
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): Batch =
    val outputFields = left.schema.fields ++ right.schema.fields
    val outputSchema = ProtoSchema(outputFields)
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(outputSchema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.allocateNew()

    var outputRow = 0
    var leftIdx = 0
    var rightIdx = 0

    while leftIdx < left.rowCount && rightIdx < right.rowCount do
      val lk = leftKeys(leftIdx)
      val rk = rightKeys(rightIdx)

      // Skip null keys — null never matches in equi-join
      if hasNullKey(lk) then
        if joinType == JoinType.LeftOuter || joinType == JoinType.FullOuter then
          emitLeftWithNulls(left, leftIdx, right.numColumns, root, outputRow, evaluator)
          outputRow += 1
        leftIdx += 1
      else if hasNullKey(rk) then
        if joinType == JoinType.RightOuter || joinType == JoinType.FullOuter then
          emitRightWithNulls(right, rightIdx, left.numColumns, root, outputRow, evaluator)
          outputRow += 1
        rightIdx += 1
      else
        val cmp = compareKeys(lk, rk)
        if cmp < 0 then
          // Left key is smaller — no match for this left row
          if joinType == JoinType.LeftOuter || joinType == JoinType.FullOuter then
            emitLeftWithNulls(left, leftIdx, right.numColumns, root, outputRow, evaluator)
            outputRow += 1
          leftIdx += 1
        else if cmp > 0 then
          // Right key is smaller — no match for this right row
          if joinType == JoinType.RightOuter || joinType == JoinType.FullOuter then
            emitRightWithNulls(right, rightIdx, left.numColumns, root, outputRow, evaluator)
            outputRow += 1
          rightIdx += 1
        else
          // Keys are equal — find the run of matching rows on both sides
          val leftRunEnd = findRunEnd(leftKeys, leftIdx)
          val rightRunEnd = findRunEnd(rightKeys, rightIdx)

          // Emit cross product of matching ranges
          for li <- leftIdx until leftRunEnd do
            var hasMatch = false
            for ri <- rightIdx until rightRunEnd do
              val passes = condition match
                case None       => true
                case Some(cond) =>
                  val combined = JoinOp.combineSingleRow(
                    left,
                    li,
                    right,
                    ri,
                    outputSchema,
                    evaluator,
                    allocator
                  )
                  val condVec = evaluator.eval(cond, combined)
                  val result = !condVec.isNull(0) && condVec.asInstanceOf[BitVector].get(0) != 0
                  combined.close()
                  result

              if passes then
                hasMatch = true
                emitRow(left, li, right, ri, root, outputRow, evaluator)
                outputRow += 1

            // Left outer: emit unmatched left row from this run
            if !hasMatch && (joinType == JoinType.LeftOuter || joinType == JoinType.FullOuter) then
              emitLeftWithNulls(left, li, right.numColumns, root, outputRow, evaluator)
              outputRow += 1

          // Right outer: check each right row in the run for matches (with residual)
          if condition.isDefined && (joinType == JoinType.RightOuter || joinType == JoinType.FullOuter)
          then
            for ri <- rightIdx until rightRunEnd do
              val anyLeftMatch = (leftIdx until leftRunEnd).exists { li =>
                val combined = JoinOp.combineSingleRow(
                  left,
                  li,
                  right,
                  ri,
                  outputSchema,
                  evaluator,
                  allocator
                )
                val condVec = evaluator.eval(condition.get, combined)
                val result = !condVec.isNull(0) && condVec.asInstanceOf[BitVector].get(0) != 0
                combined.close()
                result
              }
              if !anyLeftMatch then
                emitRightWithNulls(right, ri, left.numColumns, root, outputRow, evaluator)
                outputRow += 1

          leftIdx = leftRunEnd
          rightIdx = rightRunEnd

    // Emit remaining unmatched left rows
    if joinType == JoinType.LeftOuter || joinType == JoinType.FullOuter then
      while leftIdx < left.rowCount do
        emitLeftWithNulls(left, leftIdx, right.numColumns, root, outputRow, evaluator)
        outputRow += 1
        leftIdx += 1

    // Emit remaining unmatched right rows
    if joinType == JoinType.RightOuter || joinType == JoinType.FullOuter then
      while rightIdx < right.rowCount do
        emitRightWithNulls(right, rightIdx, left.numColumns, root, outputRow, evaluator)
        outputRow += 1
        rightIdx += 1

    root.setRowCount(outputRow)
    Batch.fromRoot(root, outputSchema)

  /** Find the end of a run of rows with the same key starting at `start`. */
  private def findRunEnd(keys: Vector[Vector[Any]], start: Int): Int =
    val key = keys(start)
    var end = start + 1
    while end < keys.size && compareKeys(keys(end), key) == 0 do end += 1
    end

  /** Merge semi/anti join: emit left rows that do/don't have a match in right. */
  private def mergeSemiJoin(
      left: Batch,
      right: Batch,
      leftKeys: Vector[Vector[Any]],
      rightKeys: Vector[Vector[Any]],
      condition: Option[ProtoExpr],
      evaluator: ExprEvaluator,
      allocator: BufferAllocator,
      anti: Boolean
  ): Batch =
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(left.schema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.allocateNew()

    val combinedSchema = ProtoSchema(left.schema.fields ++ right.schema.fields)
    var outputRow = 0
    var rightIdx = 0

    for leftIdx <- 0 until left.rowCount do
      val lk = leftKeys(leftIdx)

      if hasNullKey(lk) then
        // Null keys never match → anti emits, semi doesn't
        if anti then
          emitLeftOnly(left, leftIdx, root, outputRow, evaluator)
          outputRow += 1
      else
        // Advance right pointer past keys smaller than left
        while rightIdx < right.rowCount && !hasNullKey(rightKeys(rightIdx)) &&
          compareKeys(rightKeys(rightIdx), lk) < 0
        do rightIdx += 1

        // Check for matches in the right run with the same key
        var hasMatch = false
        var ri = rightIdx
        while !hasMatch && ri < right.rowCount && !hasNullKey(rightKeys(ri)) &&
          compareKeys(rightKeys(ri), lk) == 0
        do
          val passes = condition match
            case None       => true
            case Some(cond) =>
              val combined = JoinOp.combineSingleRow(
                left,
                leftIdx,
                right,
                ri,
                combinedSchema,
                evaluator,
                allocator
              )
              val condVec = evaluator.eval(cond, combined)
              val result = !condVec.isNull(0) && condVec.asInstanceOf[BitVector].get(0) != 0
              combined.close()
              result
          if passes then hasMatch = true
          ri += 1

        val emit = if anti then !hasMatch else hasMatch
        if emit then
          emitLeftOnly(left, leftIdx, root, outputRow, evaluator)
          outputRow += 1

    root.setRowCount(outputRow)
    Batch.fromRoot(root, left.schema)

  // ── Emit helpers ──

  private def emitRow(
      left: Batch,
      leftRow: Int,
      right: Batch,
      rightRow: Int,
      root: VectorSchemaRoot,
      outputRow: Int,
      evaluator: ExprEvaluator
  ): Unit =
    for colIdx <- 0 until left.numColumns do
      evaluator.copyValue(left.root.getVector(colIdx), leftRow, root.getVector(colIdx), outputRow)
    for colIdx <- 0 until right.numColumns do
      evaluator.copyValue(
        right.root.getVector(colIdx),
        rightRow,
        root.getVector(left.numColumns + colIdx),
        outputRow
      )

  private def emitLeftWithNulls(
      left: Batch,
      leftRow: Int,
      rightCols: Int,
      root: VectorSchemaRoot,
      outputRow: Int,
      evaluator: ExprEvaluator
  ): Unit =
    for colIdx <- 0 until left.numColumns do
      evaluator.copyValue(left.root.getVector(colIdx), leftRow, root.getVector(colIdx), outputRow)
    for colIdx <- left.numColumns until left.numColumns + rightCols do
      Batch.setNull(root.getVector(colIdx), outputRow)

  private def emitRightWithNulls(
      right: Batch,
      rightRow: Int,
      leftCols: Int,
      root: VectorSchemaRoot,
      outputRow: Int,
      evaluator: ExprEvaluator
  ): Unit =
    for colIdx <- 0 until leftCols do Batch.setNull(root.getVector(colIdx), outputRow)
    for colIdx <- 0 until right.numColumns do
      evaluator.copyValue(
        right.root.getVector(colIdx),
        rightRow,
        root.getVector(leftCols + colIdx),
        outputRow
      )

  private def emitLeftOnly(
      left: Batch,
      leftRow: Int,
      root: VectorSchemaRoot,
      outputRow: Int,
      evaluator: ExprEvaluator
  ): Unit =
    for colIdx <- 0 until left.numColumns do
      evaluator.copyValue(left.root.getVector(colIdx), leftRow, root.getVector(colIdx), outputRow)
