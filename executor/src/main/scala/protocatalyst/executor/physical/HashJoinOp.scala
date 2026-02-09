package protocatalyst.executor.physical

import scala.collection.mutable

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector._

import protocatalyst.arrow.ArrowSchemaConverter
import protocatalyst.executor.exec._
import protocatalyst.executor.exec.operators.JoinOp
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan.{BuildSide, JoinType}
import protocatalyst.schema.ProtoSchema

/** Hash join operator: build a hash table on the build side, probe with the other side.
  *
  * Requires equi-join keys (`leftKeys[i] = rightKeys[i]`). An optional residual condition is
  * applied after the key match.
  *
  * The build side is selected by the planner (smaller input by sizeInBytes). All 7 join types are
  * supported.
  */
object HashJoinOp:

  def execute(
      left: Batch,
      right: Batch,
      joinType: JoinType,
      leftKeys: Vector[ProtoExpr],
      rightKeys: Vector[ProtoExpr],
      condition: Option[ProtoExpr],
      buildSide: BuildSide,
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): Batch =
    // Determine build and probe sides
    val (buildBatch, probeBatch, buildKeys, probeKeys, swapped) = buildSide match
      case BuildSide.BuildLeft  => (left, right, leftKeys, rightKeys, true)
      case BuildSide.BuildRight => (right, left, rightKeys, leftKeys, false)

    joinType match
      case JoinType.LeftSemi =>
        hashSemiJoin(
          left,
          right,
          leftKeys,
          rightKeys,
          condition,
          evaluator,
          allocator,
          anti = false
        )
      case JoinType.LeftAnti =>
        hashSemiJoin(left, right, leftKeys, rightKeys, condition, evaluator, allocator, anti = true)
      case _ =>
        hashJoin(
          left,
          right,
          joinType,
          buildBatch,
          probeBatch,
          buildKeys,
          probeKeys,
          condition,
          swapped,
          evaluator,
          allocator
        )

  /** Build phase: hash build-side rows by key columns into a multi-map. */
  private def buildHashTable(
      batch: Batch,
      keys: Vector[ProtoExpr],
      evaluator: ExprEvaluator
  ): mutable.HashMap[Vector[Any], mutable.ArrayBuffer[Int]] =
    val table = mutable.HashMap[Vector[Any], mutable.ArrayBuffer[Int]]()
    for row <- 0 until batch.rowCount do
      val keyValues = extractKeyValues(batch, row, keys, evaluator)
      // Skip null keys — null never matches in equi-join
      if !keyValues.contains(null) then
        table.getOrElseUpdate(keyValues, mutable.ArrayBuffer[Int]()) += row
    table

  /** Extract key values for a given row. */
  private def extractKeyValues(
      batch: Batch,
      row: Int,
      keys: Vector[ProtoExpr],
      evaluator: ExprEvaluator
  ): Vector[Any] =
    keys.map: key =>
      val vec = evaluator.eval(key, batch)
      Batch.getValue(vec, row)

  /** Core hash join for Inner, LeftOuter, RightOuter, FullOuter, Cross. */
  private def hashJoin(
      left: Batch,
      right: Batch,
      joinType: JoinType,
      buildBatch: Batch,
      probeBatch: Batch,
      buildKeys: Vector[ProtoExpr],
      probeKeys: Vector[ProtoExpr],
      condition: Option[ProtoExpr],
      swapped: Boolean, // true if build=left, so probe=right
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): Batch =
    // Build hash table on build side
    val hashTable = buildHashTable(buildBatch, buildKeys, evaluator)

    // Output schema: always left fields + right fields
    val outputFields = left.schema.fields ++ right.schema.fields
    val outputSchema = ProtoSchema(outputFields)
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(outputSchema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.allocateNew()

    val buildMatched = mutable.BitSet()
    var outputRow = 0

    // Probe phase
    for probeRow <- 0 until probeBatch.rowCount do
      val probeKeyValues = extractKeyValues(probeBatch, probeRow, probeKeys, evaluator)
      val hasNullKey = probeKeyValues.contains(null)

      var hasMatch = false

      if !hasNullKey then
        hashTable.get(probeKeyValues) match
          case Some(buildRows) =>
            for buildRow <- buildRows do
              // Determine actual left/right row indices
              val (leftRow, rightRow) =
                if swapped then (buildRow, probeRow) else (probeRow, buildRow)

              // Apply residual condition if present
              val passes = condition match
                case None       => true
                case Some(cond) =>
                  val combined = JoinOp.combineSingleRow(
                    left,
                    leftRow,
                    right,
                    rightRow,
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
                buildMatched += buildRow
                joinType match
                  case JoinType.Inner | JoinType.LeftOuter | JoinType.RightOuter |
                      JoinType.FullOuter | JoinType.Cross =>
                    emitRow(left, leftRow, right, rightRow, root, outputRow, evaluator)
                    outputRow += 1
                  case _ => ()

          case None => ()

      // Handle outer joins: emit unmatched probe row
      if !hasMatch then
        joinType match
          case JoinType.LeftOuter if !swapped =>
            // Probe is left, no match → emit left with null right
            emitLeftWithNulls(left, probeRow, right.numColumns, root, outputRow, evaluator)
            outputRow += 1
          case JoinType.LeftOuter if swapped =>
            // Probe is right, no match for right in left-outer → no emit
            ()
          case JoinType.RightOuter if swapped =>
            // Probe is right, no match → emit null left with right
            emitRightWithNulls(right, probeRow, left.numColumns, root, outputRow, evaluator)
            outputRow += 1
          case JoinType.RightOuter if !swapped =>
            // Probe is left, no match for left in right-outer → no emit
            ()
          case JoinType.FullOuter =>
            if swapped then
              // Probe is right, unmatched → null left + right
              emitRightWithNulls(right, probeRow, left.numColumns, root, outputRow, evaluator)
            else
              // Probe is left, unmatched → left + null right
              emitLeftWithNulls(left, probeRow, right.numColumns, root, outputRow, evaluator)
            outputRow += 1
          case _ => ()

    // Handle build-side unmatched rows for outer joins.
    // The rule: left outer preserves all left rows, right outer preserves all right rows,
    // full outer preserves all rows from both sides.
    // We only need to emit build-side rows here — unmatched probe-side rows were handled above.
    joinType match
      case JoinType.FullOuter =>
        for buildRow <- 0 until buildBatch.rowCount do
          if !buildMatched.contains(buildRow) then
            if swapped then
              // Build is left, unmatched → left + null right
              emitLeftWithNulls(left, buildRow, right.numColumns, root, outputRow, evaluator)
            else
              // Build is right, unmatched → null left + right
              emitRightWithNulls(right, buildRow, left.numColumns, root, outputRow, evaluator)
            outputRow += 1
      case JoinType.LeftOuter if swapped =>
        // Build is left → unmatched left rows should be preserved with null right
        for buildRow <- 0 until buildBatch.rowCount do
          if !buildMatched.contains(buildRow) then
            emitLeftWithNulls(left, buildRow, right.numColumns, root, outputRow, evaluator)
            outputRow += 1
      case JoinType.RightOuter if !swapped =>
        // Build is right → unmatched right rows should be preserved with null left
        for buildRow <- 0 until buildBatch.rowCount do
          if !buildMatched.contains(buildRow) then
            emitRightWithNulls(right, buildRow, left.numColumns, root, outputRow, evaluator)
            outputRow += 1
      case _ => ()
      // LeftOuter if !swapped: build=right, unmatched right → no emit (left outer only keeps left)
      // RightOuter if swapped: build=left, unmatched left → no emit (right outer only keeps right)

    root.setRowCount(outputRow)
    Batch.fromRoot(root, outputSchema)

  /** Hash semi/anti join: only emit left rows that have (or don't have) a match in right. */
  private def hashSemiJoin(
      left: Batch,
      right: Batch,
      leftKeys: Vector[ProtoExpr],
      rightKeys: Vector[ProtoExpr],
      condition: Option[ProtoExpr],
      evaluator: ExprEvaluator,
      allocator: BufferAllocator,
      anti: Boolean
  ): Batch =
    // Build hash table on right side
    val hashTable = buildHashTable(right, rightKeys, evaluator)

    val arrowSchema = ArrowSchemaConverter.toArrowSchema(left.schema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.allocateNew()

    val combinedSchema = ProtoSchema(left.schema.fields ++ right.schema.fields)
    var outputRow = 0

    for leftRow <- 0 until left.rowCount do
      val leftKeyValues = extractKeyValues(left, leftRow, leftKeys, evaluator)
      val hasNullKey = leftKeyValues.contains(null)

      var hasMatch = false
      if !hasNullKey then
        hashTable.get(leftKeyValues) match
          case Some(rightRows) =>
            condition match
              case None       => hasMatch = true
              case Some(cond) =>
                var ri = 0
                while !hasMatch && ri < rightRows.size do
                  val combined = JoinOp.combineSingleRow(
                    left,
                    leftRow,
                    right,
                    rightRows(ri),
                    combinedSchema,
                    evaluator,
                    allocator
                  )
                  val condVec = evaluator.eval(cond, combined)
                  hasMatch = !condVec.isNull(0) && condVec.asInstanceOf[BitVector].get(0) != 0
                  combined.close()
                  ri += 1
          case None => ()

      val emit = if anti then !hasMatch else hasMatch
      if emit then
        for colIdx <- 0 until left.numColumns do
          evaluator.copyValue(
            left.root.getVector(colIdx),
            leftRow,
            root.getVector(colIdx),
            outputRow
          )
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
