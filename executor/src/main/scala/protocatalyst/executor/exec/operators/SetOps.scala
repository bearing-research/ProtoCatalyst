package protocatalyst.executor.exec.operators

import scala.collection.mutable

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector._

import protocatalyst.arrow.ArrowSchemaConverter
import protocatalyst.executor.exec._

/** Set operations: Union, Intersect, Except. */
object SetOps:

  /** Union: concatenate batches (by position or by name). */
  def union(
      batches: Vector[Batch],
      byName: Boolean,
      allocator: BufferAllocator
  ): Batch =
    if batches.isEmpty then throw ExecutionException("UNION requires at least one child")
    if batches.size == 1 then return batches.head

    val firstSchema = batches.head.schema
    val totalRows = batches.map(_.rowCount).sum

    val arrowSchema = ArrowSchemaConverter.toArrowSchema(firstSchema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.allocateNew()

    var outputRow = 0
    val eval = ExprEvaluator(allocator)

    for batch <- batches do
      for i <- 0 until batch.rowCount do
        if byName then
          // Match columns by name
          for (field, colIdx) <- firstSchema.fields.zipWithIndex do
            val srcIdx = batch.schema.fields.indexWhere(_.name.equalsIgnoreCase(field.name))
            if srcIdx >= 0 then
              eval.copyValue(batch.root.getVector(srcIdx), i, root.getVector(colIdx), outputRow)
            else Batch.setNull(root.getVector(colIdx), outputRow)
        else
          // Match columns by position
          for colIdx <- 0 until firstSchema.fields.size do
            if colIdx < batch.numColumns then
              eval.copyValue(batch.root.getVector(colIdx), i, root.getVector(colIdx), outputRow)
            else Batch.setNull(root.getVector(colIdx), outputRow)
        outputRow += 1

    root.setRowCount(totalRows)
    Batch.fromRoot(root, firstSchema)

  /** Intersect: keep rows that exist in both inputs. */
  def intersect(
      left: Batch,
      right: Batch,
      isAll: Boolean,
      allocator: BufferAllocator
  ): Batch =
    // Build hash set from right side
    val rightRows = buildRowSet(right)
    val seen = if !isAll then mutable.HashSet[Vector[Any]]() else null

    val arrowSchema = ArrowSchemaConverter.toArrowSchema(left.schema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.allocateNew()

    val eval = ExprEvaluator(allocator)
    var outputRow = 0

    for i <- 0 until left.rowCount do
      val row = getRow(left, i)
      val inRight = rightRows.contains(row)
      val notSeen = if !isAll then seen.add(row) else true
      if inRight && notSeen then
        for colIdx <- 0 until left.numColumns do
          eval.copyValue(left.root.getVector(colIdx), i, root.getVector(colIdx), outputRow)
        outputRow += 1

    root.setRowCount(outputRow)
    Batch.fromRoot(root, left.schema)

  /** Except: keep left rows that don't exist in right. */
  def except(
      left: Batch,
      right: Batch,
      isAll: Boolean,
      allocator: BufferAllocator
  ): Batch =
    val rightRows = if isAll then
      // For ALL, track counts
      val counts = mutable.HashMap[Vector[Any], Int]()
      for i <- 0 until right.rowCount do
        val row = getRow(right, i)
        counts(row) = counts.getOrElse(row, 0) + 1
      counts
    else
      val set = buildRowSet(right)
      null

    val rightSet = if !isAll then buildRowSet(right) else null
    val rightCounts = if isAll then
      val counts = mutable.HashMap[Vector[Any], Int]()
      for i <- 0 until right.rowCount do
        val row = getRow(right, i)
        counts(row) = counts.getOrElse(row, 0) + 1
      counts
    else null

    val arrowSchema = ArrowSchemaConverter.toArrowSchema(left.schema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.allocateNew()

    val eval = ExprEvaluator(allocator)
    val seen = if !isAll then mutable.HashSet[Vector[Any]]() else null
    var outputRow = 0

    for i <- 0 until left.rowCount do
      val row = getRow(left, i)
      val emit = if isAll then
        val count = rightCounts.getOrElse(row, 0)
        if count > 0 then
          rightCounts(row) = count - 1
          false
        else true
      else !rightSet.contains(row) && seen.add(row)

      if emit then
        for colIdx <- 0 until left.numColumns do
          eval.copyValue(left.root.getVector(colIdx), i, root.getVector(colIdx), outputRow)
        outputRow += 1

    root.setRowCount(outputRow)
    Batch.fromRoot(root, left.schema)

  private def getRow(batch: Batch, i: Int): Vector[Any] =
    (0 until batch.numColumns).map(c => Batch.getValue(batch.root.getVector(c), i)).toVector

  private def buildRowSet(batch: Batch): mutable.HashSet[Vector[Any]] =
    val set = mutable.HashSet[Vector[Any]]()
    for i <- 0 until batch.rowCount do set += getRow(batch, i)
    set
