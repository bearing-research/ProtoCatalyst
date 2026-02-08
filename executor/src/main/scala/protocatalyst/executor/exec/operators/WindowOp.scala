package protocatalyst.executor.exec.operators

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector._

import protocatalyst.executor.exec._
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan.SortOrder
import protocatalyst.schema.ProtoSchema
import protocatalyst.types._

/** Window function operator.
  *
  * Partitions rows, sorts within each partition, and computes window functions.
  */
object WindowOp:

  def execute(
      input: Batch,
      windowExprs: Vector[ProtoExpr],
      partitionSpec: Vector[ProtoExpr],
      orderSpec: Vector[SortOrder],
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): Batch =
    if input.rowCount == 0 then return input

    // Evaluate partition keys
    val partVecs = partitionSpec.map(e => evaluator.eval(e, input))

    // Build partitions: partKey → sorted row indices
    val partitions = buildPartitions(input, partVecs, orderSpec, evaluator)

    // Compute window functions and append result columns
    val windowResults =
      windowExprs.map(expr => computeWindow(expr, input, partitions, evaluator, allocator))

    // Build output: input columns + window columns
    val windowFields = windowExprs.zip(windowResults).map { (expr, vec) =>
      val name = extractWindowName(expr)
      val protoType = ProjectOp.inferProtoType(vec)
      ProtoStructField(name, protoType, nullable = true)
    }

    val outputSchema = ProtoSchema(input.schema.fields ++ windowFields)
    val allVectors = (0 until input.numColumns).map(i => input.root.getVector(i)) ++ windowResults
    val root = new VectorSchemaRoot(allVectors.map(_.asInstanceOf[FieldVector]).toList.asJava)
    root.setRowCount(input.rowCount)
    Batch.fromRoot(root, outputSchema)

  /** Build partitions: group rows by partition key, sort within each partition. */
  private def buildPartitions(
      input: Batch,
      partVecs: Vector[FieldVector],
      orderSpec: Vector[SortOrder],
      evaluator: ExprEvaluator
  ): Vector[Vector[Int]] =
    // Group row indices by partition key
    val groups = mutable.LinkedHashMap[Vector[Any], mutable.ArrayBuffer[Int]]()
    for i <- 0 until input.rowCount do
      val key = partVecs.map(v => Batch.getValue(v, i)).toVector
      groups.getOrElseUpdate(key, mutable.ArrayBuffer.empty) += i

    // Sort within each partition if orderSpec is non-empty
    if orderSpec.isEmpty then groups.values.map(_.toVector).toVector
    else
      val sortVecs = orderSpec.map(so => evaluator.eval(so.child, input))
      groups.values.map { indices =>
        indices.sortWith { (a, b) =>
          sortCompareRows(a, b, sortVecs, orderSpec) < 0
        }.toVector
      }.toVector

  /** Compute a window function for all partitions. */
  private def computeWindow(
      expr: ProtoExpr,
      input: Batch,
      partitions: Vector[Vector[Int]],
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): FieldVector =
    val windowExpr = expr match
      case ProtoExpr.Alias(child, _)               => child
      case ProtoExpr.WindowExpr(function, _, _, _) => function
      case other                                   => other

    // Extract sort vectors for rank computation (needed by Rank/DenseRank)
    lazy val sortVecs = expr match
      case ProtoExpr.Alias(_, _) | ProtoExpr.WindowExpr(_, _, _, _) =>
        val orderSpec = expr match
          case ProtoExpr.WindowExpr(_, _, os, _) => os
          case _                                 => Vector.empty
        orderSpec.map(so => evaluator.eval(so.child, input))
      case _ => Vector.empty

    windowExpr match
      case ProtoExpr.RowNumber() =>
        rowNumber(input.rowCount, partitions, allocator)
      case ProtoExpr.Rank() =>
        rank(input.rowCount, partitions, sortVecs, allocator, dense = false)
      case ProtoExpr.DenseRank() =>
        rank(input.rowCount, partitions, sortVecs, allocator, dense = true)
      case ProtoExpr.Ntile(n) =>
        ntile(n, input, partitions, evaluator, allocator)
      case ProtoExpr.Lead(child, offset, default) =>
        leadLag(child, offset, default, input, partitions, evaluator, allocator, isLead = true)
      case ProtoExpr.Lag(child, offset, default) =>
        leadLag(child, offset, default, input, partitions, evaluator, allocator, isLead = false)
      case ProtoExpr.FirstValue(child, ignoreNulls) =>
        firstLastValue(child, input, partitions, evaluator, allocator, isFirst = true, ignoreNulls)
      case ProtoExpr.LastValue(child, ignoreNulls) =>
        firstLastValue(child, input, partitions, evaluator, allocator, isFirst = false, ignoreNulls)
      case ProtoExpr.NthValue(child, n) =>
        nthValue(child, n, input, partitions, evaluator, allocator)
      case agg @ (_: ProtoExpr.Sum | _: ProtoExpr.Count | _: ProtoExpr.Avg | _: ProtoExpr.Min |
          _: ProtoExpr.Max) =>
        windowAggregate(agg, input, partitions, evaluator, allocator)
      case other =>
        throw ExecutionException(s"Unsupported window function: $other")

  private def rowNumber(
      totalRows: Int,
      partitions: Vector[Vector[Int]],
      allocator: BufferAllocator
  ): FieldVector =
    val result = new BigIntVector("row_number", allocator)
    result.allocateNew(totalRows)
    for partition <- partitions do
      for (rowIdx, rank) <- partition.zipWithIndex do result.setSafe(rowIdx, (rank + 1).toLong)
    result.setValueCount(totalRows)
    result

  /** LEAD / LAG: writes values in sequential row order to avoid VarChar offset corruption. */
  private def leadLag(
      child: ProtoExpr,
      offsetExpr: ProtoExpr,
      defaultExpr: Option[ProtoExpr],
      input: Batch,
      partitions: Vector[Vector[Int]],
      evaluator: ExprEvaluator,
      allocator: BufferAllocator,
      isLead: Boolean
  ): FieldVector =
    val childVec = evaluator.eval(child, input)
    val offsetVec = evaluator.eval(offsetExpr, input)
    val defaultVec = defaultExpr.map(e => evaluator.eval(e, input))

    val result = childVec.getField.createVector(allocator)
    result.allocateNew()

    // Build mapping: rowIdx → (srcVec, srcRow)
    // -1 means use default or null
    val srcRowMapping = new Array[Int](input.rowCount)
    val usesChild = new Array[Boolean](input.rowCount) // true=childVec, false=defaultVec/null
    java.util.Arrays.fill(srcRowMapping, -1)

    for partition <- partitions do
      for (rowIdx, posInPartition) <- partition.zipWithIndex do
        val offset = Batch.getValue(offsetVec, rowIdx) match
          case i: Int  => i
          case l: Long => l.toInt
          case _       => 1
        val targetPos = if isLead then posInPartition + offset else posInPartition - offset
        if targetPos >= 0 && targetPos < partition.size then
          srcRowMapping(rowIdx) = partition(targetPos)
          usesChild(rowIdx) = true

    // Write in sequential order
    for i <- 0 until input.rowCount do
      if usesChild(i) then evaluator.copyValue(childVec, srcRowMapping(i), result, i)
      else
        defaultVec match
          case Some(dv) => evaluator.copyValue(dv, i, result, i)
          case None     => Batch.setNull(result, i)

    result.setValueCount(input.rowCount)
    result

  /** RANK / DENSE_RANK: assign rank based on sort key equality within each partition. */
  private def rank(
      totalRows: Int,
      partitions: Vector[Vector[Int]],
      sortVecs: Vector[FieldVector],
      allocator: BufferAllocator,
      dense: Boolean
  ): FieldVector =
    val result = new BigIntVector("rank", allocator)
    result.allocateNew(totalRows)

    for partition <- partitions do
      if partition.nonEmpty then
        result.setSafe(partition.head, 1L)
        var currentRank = 1L
        var denseRank = 1L
        for i <- 1 until partition.size do
          val prevRow = partition(i - 1)
          val currRow = partition(i)
          val same = sortVecs.forall { vec =>
            val a = Batch.getValue(vec, prevRow)
            val b = Batch.getValue(vec, currRow)
            (a == null && b == null) || (a != null && b != null && SortOp.compareValues(a, b) == 0)
          }
          if same then result.setSafe(currRow, if dense then denseRank else currentRank)
          else
            currentRank = i.toLong + 1
            denseRank += 1
            result.setSafe(currRow, if dense then denseRank else currentRank)

    result.setValueCount(totalRows)
    result

  /** NTILE: distribute partition rows into n approximately equal groups. */
  private def ntile(
      nExpr: ProtoExpr,
      input: Batch,
      partitions: Vector[Vector[Int]],
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): FieldVector =
    val nVec = evaluator.eval(nExpr, input)
    val result = new BigIntVector("ntile", allocator)
    result.allocateNew(input.rowCount)

    for partition <- partitions do
      if partition.nonEmpty then
        // Use the n value from the first row of the partition
        val n = Batch.getValue(nVec, partition.head) match
          case i: Int  => i
          case l: Long => l.toInt
          case _       => 1
        val bucketSize = partition.size / n
        val remainder = partition.size % n
        var bucket = 1
        var count = 0
        val currentBucketSize = if remainder > 0 then bucketSize + 1 else bucketSize

        var pos = 0
        var currentBucket = 1
        for (rowIdx, posInPartition) <- partition.zipWithIndex do
          // Rows 0..(remainder*(bucketSize+1)-1) go into first `remainder` buckets of size bucketSize+1
          // Remaining rows go into buckets of size bucketSize
          val boundary =
            if currentBucket <= remainder then remainder * (bucketSize + 1)
            else remainder * (bucketSize + 1) + (currentBucket - remainder) * bucketSize

          if posInPartition >= boundary && currentBucket < n then
            currentBucket =
              if posInPartition < remainder * (bucketSize + 1) then
                posInPartition / (bucketSize + 1) + 1
              else remainder + (posInPartition - remainder * (bucketSize + 1)) / bucketSize + 1

          // Simpler approach: directly compute bucket from position
          val b =
            if n <= 0 then 1
            else if posInPartition < remainder * (bucketSize + 1) then
              posInPartition / (bucketSize + 1) + 1
            else
              remainder + (posInPartition - remainder * (bucketSize + 1)) / (if bucketSize > 0 then
                                                                               bucketSize
                                                                             else 1) + 1
          result.setSafe(rowIdx, math.min(b, n).toLong)

    result.setValueCount(input.rowCount)
    result

  /** FIRST_VALUE / LAST_VALUE: return the first or last value in each partition. Writes values in
    * sequential row order to avoid VarChar offset corruption.
    */
  private def firstLastValue(
      child: ProtoExpr,
      input: Batch,
      partitions: Vector[Vector[Int]],
      evaluator: ExprEvaluator,
      allocator: BufferAllocator,
      isFirst: Boolean,
      ignoreNulls: Boolean
  ): FieldVector =
    val childVec = evaluator.eval(child, input)
    val result = childVec.getField.createVector(allocator)
    result.allocateNew()

    // Build rowIdx → srcRow mapping
    val rowMapping = new Array[Int](input.rowCount)
    val rowHasValue = new Array[Boolean](input.rowCount)
    java.util.Arrays.fill(rowMapping, -1)

    for partition <- partitions do
      val targetRow =
        if isFirst then
          if ignoreNulls then partition.find(r => !childVec.isNull(r))
          else partition.headOption
        else if ignoreNulls then partition.findLast(r => !childVec.isNull(r))
        else partition.lastOption

      for rowIdx <- partition do
        targetRow match
          case Some(srcRow) =>
            rowMapping(rowIdx) = srcRow
            rowHasValue(rowIdx) = true
          case None =>
            rowHasValue(rowIdx) = false

    // Write in sequential order
    for i <- 0 until input.rowCount do
      if rowHasValue(i) then evaluator.copyValue(childVec, rowMapping(i), result, i)
      else Batch.setNull(result, i)

    result.setValueCount(input.rowCount)
    result

  /** NTH_VALUE: return the n-th value (1-based) in each partition. Writes values in sequential row
    * order to avoid VarChar offset corruption.
    */
  private def nthValue(
      child: ProtoExpr,
      nExpr: ProtoExpr,
      input: Batch,
      partitions: Vector[Vector[Int]],
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): FieldVector =
    val childVec = evaluator.eval(child, input)
    val nVec = evaluator.eval(nExpr, input)
    val result = childVec.getField.createVector(allocator)
    result.allocateNew()

    // Build rowIdx → srcRow mapping
    val rowMapping = new Array[Int](input.rowCount)
    val rowHasValue = new Array[Boolean](input.rowCount)
    java.util.Arrays.fill(rowMapping, -1)

    for partition <- partitions do
      for rowIdx <- partition do
        val n = Batch.getValue(nVec, rowIdx) match
          case i: Int  => i
          case l: Long => l.toInt
          case _       => 1
        if n >= 1 && n <= partition.size then
          rowMapping(rowIdx) = partition(n - 1)
          rowHasValue(rowIdx) = true

    // Write in sequential order
    for i <- 0 until input.rowCount do
      if rowHasValue(i) then evaluator.copyValue(childVec, rowMapping(i), result, i)
      else Batch.setNull(result, i)

    result.setValueCount(input.rowCount)
    result

  /** Window aggregates: compute running aggregate over entire partition for each row. */
  private def windowAggregate(
      aggExpr: ProtoExpr,
      input: Batch,
      partitions: Vector[Vector[Int]],
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): FieldVector =
    val (childExpr, aggType) = aggExpr match
      case ProtoExpr.Sum(child)      => (child, "sum")
      case ProtoExpr.Count(child, _) => (child, "count")
      case ProtoExpr.Avg(child)      => (child, "avg")
      case ProtoExpr.Min(child)      => (child, "min")
      case ProtoExpr.Max(child)      => (child, "max")
      case _ => throw ExecutionException(s"Unsupported window aggregate: $aggExpr")

    val childVec = evaluator.eval(childExpr, input)

    aggType match
      case "count" =>
        val result = new BigIntVector("count", allocator)
        result.allocateNew(input.rowCount)
        for partition <- partitions do
          var count = 0L
          for rowIdx <- partition do if !childVec.isNull(rowIdx) then count += 1
          for rowIdx <- partition do result.setSafe(rowIdx, count)
        result.setValueCount(input.rowCount)
        result

      case "sum" =>
        val result = new Float8Vector("sum", allocator)
        result.allocateNew(input.rowCount)
        for partition <- partitions do
          var sum = 0.0
          var hasValue = false
          for rowIdx <- partition do
            if !childVec.isNull(rowIdx) then
              hasValue = true
              sum += toDouble(Batch.getValue(childVec, rowIdx))
          for rowIdx <- partition do
            if hasValue then result.setSafe(rowIdx, sum)
            else result.setNull(rowIdx)
        result.setValueCount(input.rowCount)
        result

      case "avg" =>
        val result = new Float8Vector("avg", allocator)
        result.allocateNew(input.rowCount)
        for partition <- partitions do
          var sum = 0.0
          var count = 0L
          for rowIdx <- partition do
            if !childVec.isNull(rowIdx) then
              sum += toDouble(Batch.getValue(childVec, rowIdx))
              count += 1
          for rowIdx <- partition do
            if count > 0 then result.setSafe(rowIdx, sum / count)
            else result.setNull(rowIdx)
        result.setValueCount(input.rowCount)
        result

      case "min" =>
        val result = new Float8Vector("min", allocator)
        result.allocateNew(input.rowCount)
        for partition <- partitions do
          var min: java.lang.Double = null
          for rowIdx <- partition do
            if !childVec.isNull(rowIdx) then
              val v = toDouble(Batch.getValue(childVec, rowIdx))
              if min == null || v < min then min = v
          for rowIdx <- partition do
            if min != null then result.setSafe(rowIdx, min)
            else result.setNull(rowIdx)
        result.setValueCount(input.rowCount)
        result

      case "max" =>
        val result = new Float8Vector("max", allocator)
        result.allocateNew(input.rowCount)
        for partition <- partitions do
          var max: java.lang.Double = null
          for rowIdx <- partition do
            if !childVec.isNull(rowIdx) then
              val v = toDouble(Batch.getValue(childVec, rowIdx))
              if max == null || v > max then max = v
          for rowIdx <- partition do
            if max != null then result.setSafe(rowIdx, max)
            else result.setNull(rowIdx)
        result.setValueCount(input.rowCount)
        result

  private def toDouble(v: Any): Double = v match
    case d: Double                => d
    case f: Float                 => f.toDouble
    case l: Long                  => l.toDouble
    case i: Int                   => i.toDouble
    case s: Short                 => s.toDouble
    case b: Byte                  => b.toDouble
    case bd: java.math.BigDecimal => bd.doubleValue
    case _                        => v.toString.toDouble

  private def extractWindowName(expr: ProtoExpr): String = expr match
    case ProtoExpr.Alias(_, name)            => name
    case ProtoExpr.WindowExpr(func, _, _, _) => extractWindowName(func)
    case _: ProtoExpr.RowNumber              => "row_number"
    case _: ProtoExpr.Rank                   => "rank"
    case _: ProtoExpr.DenseRank              => "dense_rank"
    case _                                   => "window"

  /** Compare two rows using sort order specs. Delegates to SortOp. */
  private def sortCompareRows(
      a: Int,
      b: Int,
      sortVecs: Vector[FieldVector],
      orderSpec: Vector[SortOrder]
  ): Int =
    val keys = sortVecs.zip(orderSpec).map { (vec, so) =>
      (vec, so.direction, so.nullOrdering)
    }
    SortOp.compareRows(a, b, keys)
