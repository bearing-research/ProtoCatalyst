package protocatalyst.executor.exec.operators

import scala.collection.mutable

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector._

import protocatalyst.arrow.ArrowSchemaConverter
import protocatalyst.executor.exec._
import protocatalyst.expr.ProtoExpr
import protocatalyst.schema.ProtoSchema
import protocatalyst.types._

/** Hash aggregate operator.
  *
  * Groups rows by key expressions, then computes aggregate functions (Count, Sum, Avg, Min, Max)
  * over each group.
  */
object AggregateOp:

  /** Accumulator for aggregate functions. */
  private sealed trait Accumulator:
    def update(value: Any, isNull: Boolean): Unit
    def result: Any
    def isResultNull: Boolean

  private class CountAcc(distinct: Boolean) extends Accumulator:
    private var count: Long = 0
    private val seen = if distinct then Some(mutable.HashSet[Any]()) else None
    def update(value: Any, isNull: Boolean): Unit =
      if !isNull then
        seen match
          case Some(s) => if s.add(value) then count += 1
          case None    => count += 1
    def result: Any = count
    def isResultNull: Boolean = false

  private class SumAcc extends Accumulator:
    private var sum: Double = 0.0
    private var hasValue: Boolean = false
    def update(value: Any, isNull: Boolean): Unit =
      if !isNull then
        hasValue = true
        sum += toDouble(value)
    def result: Any = sum
    def isResultNull: Boolean = !hasValue

  private class AvgAcc extends Accumulator:
    private var sum: Double = 0.0
    private var count: Long = 0
    def update(value: Any, isNull: Boolean): Unit =
      if !isNull then
        sum += toDouble(value)
        count += 1
    def result: Any = if count > 0 then sum / count else null
    def isResultNull: Boolean = count == 0

  private class MinAcc extends Accumulator:
    private var min: Any = null
    def update(value: Any, isNull: Boolean): Unit =
      if !isNull then if min == null || compareValues(value, min) < 0 then min = value
    def result: Any = if min == null then null else toDouble(min)
    def isResultNull: Boolean = min == null

  private class MaxAcc extends Accumulator:
    private var max: Any = null
    def update(value: Any, isNull: Boolean): Unit =
      if !isNull then if max == null || compareValues(value, max) > 0 then max = value
    def result: Any = if max == null then null else toDouble(max)
    def isResultNull: Boolean = max == null

  def execute(
      input: Batch,
      groupingExprs: Vector[ProtoExpr],
      aggregateExprs: Vector[ProtoExpr],
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): Batch =
    // Evaluate grouping key vectors
    val groupVecs = groupingExprs.map(e => evaluator.eval(e, input))

    // Build groups: groupKey → list of row indices
    val groups = mutable.LinkedHashMap[Vector[Any], mutable.ArrayBuffer[Int]]()
    for i <- 0 until input.rowCount do
      val key = groupVecs.map(v => Batch.getValue(v, i)).toVector
      groups.getOrElseUpdate(key, mutable.ArrayBuffer.empty) += i

    // If no grouping, treat as single global group
    val effectiveGroups = if groupingExprs.isEmpty then
      val allRows = mutable.ArrayBuffer.from(0 until input.rowCount)
      mutable.LinkedHashMap(Vector.empty[Any] -> allRows)
    else groups

    // Determine output schema
    val groupFields = groupingExprs.zipWithIndex.map { (expr, idx) =>
      val name = ProjectOp.extractName(expr)
      val protoType = ProjectOp.inferProtoType(groupVecs(idx))
      ProtoStructField(name, protoType, nullable = true)
    }

    val aggFields = aggregateExprs.map { expr =>
      val name = extractAggName(expr)
      val protoType = inferAggType(expr)
      ProtoStructField(name, protoType, nullable = true)
    }

    val outputSchema = ProtoSchema(groupFields ++ aggFields)
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(outputSchema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.allocateNew()

    var outputRow = 0
    for (key, rowIndices) <- effectiveGroups do
      // Write grouping columns
      for (value, colIdx) <- key.zipWithIndex do
        if value == null then Batch.setNull(root.getVector(colIdx), outputRow)
        else
          val lit = anyToLiteralValue(value)
          Batch.setLiteralValue(root.getVector(colIdx), outputRow, lit)

      // Compute aggregates
      for (aggExpr, aggIdx) <- aggregateExprs.zipWithIndex do
        val acc = createAccumulator(aggExpr)
        val childExpr = extractAggChild(aggExpr)
        val childVec = evaluator.eval(childExpr, input)

        for rowIdx <- rowIndices do
          acc.update(Batch.getValue(childVec, rowIdx), childVec.isNull(rowIdx))

        val colIdx = groupFields.size + aggIdx
        if acc.isResultNull then Batch.setNull(root.getVector(colIdx), outputRow)
        else
          val lit = anyToLiteralValue(acc.result)
          Batch.setLiteralValue(root.getVector(colIdx), outputRow, lit)

      outputRow += 1

    root.setRowCount(outputRow)
    Batch.fromRoot(root, outputSchema)

  private def createAccumulator(expr: ProtoExpr): Accumulator = expr match
    case ProtoExpr.Alias(child, _)    => createAccumulator(child)
    case ProtoExpr.Count(_, distinct) => CountAcc(distinct)
    case _: ProtoExpr.Sum             => SumAcc()
    case _: ProtoExpr.Avg             => AvgAcc()
    case _: ProtoExpr.Min             => MinAcc()
    case _: ProtoExpr.Max             => MaxAcc()
    case other                        => throw ExecutionException(s"Unknown aggregate: $other")

  private def extractAggChild(expr: ProtoExpr): ProtoExpr = expr match
    case ProtoExpr.Alias(child, _) => extractAggChild(child)
    case ProtoExpr.Count(child, _) => child
    case ProtoExpr.Sum(child)      => child
    case ProtoExpr.Avg(child)      => child
    case ProtoExpr.Min(child)      => child
    case ProtoExpr.Max(child)      => child
    case other                     => throw ExecutionException(s"Cannot extract child from: $other")

  private[operators] def extractAggName(expr: ProtoExpr): String = expr match
    case ProtoExpr.Alias(_, name) => name
    case ProtoExpr.Count(_, _)    => "count"
    case _: ProtoExpr.Sum         => "sum"
    case _: ProtoExpr.Avg         => "avg"
    case _: ProtoExpr.Min         => "min"
    case _: ProtoExpr.Max         => "max"
    case _                        => "agg"

  private def inferAggType(expr: ProtoExpr): ProtoType = expr match
    case ProtoExpr.Alias(child, _) => inferAggType(child)
    case ProtoExpr.Count(_, _)     => ProtoType.LongType
    case _: ProtoExpr.Sum          => ProtoType.DoubleType
    case _: ProtoExpr.Avg          => ProtoType.DoubleType
    case _: ProtoExpr.Min          => ProtoType.DoubleType // simplified
    case _: ProtoExpr.Max          => ProtoType.DoubleType // simplified
    case _                         => ProtoType.DoubleType

  private def toDouble(v: Any): Double = v match
    case d: Double                => d
    case f: Float                 => f.toDouble
    case l: Long                  => l.toDouble
    case i: Int                   => i.toDouble
    case s: Short                 => s.toDouble
    case b: Byte                  => b.toDouble
    case bd: java.math.BigDecimal => bd.doubleValue
    case _                        => v.toString.toDouble

  private def compareValues(a: Any, b: Any): Int = (a, b) match
    case (a: Number, b: Number) => java.lang.Double.compare(a.doubleValue, b.doubleValue)
    case (a: String, b: String) => a.compareTo(b)
    case (a: Comparable[?], b)  => a.asInstanceOf[Comparable[Any]].compareTo(b)
    case _                      => a.toString.compareTo(b.toString)

  private def anyToLiteralValue(v: Any): LiteralValue = v match
    case b: Boolean               => LiteralValue.BooleanValue(b)
    case b: Byte                  => LiteralValue.ByteValue(b)
    case s: Short                 => LiteralValue.ShortValue(s)
    case i: Int                   => LiteralValue.IntValue(i)
    case l: Long                  => LiteralValue.LongValue(l)
    case f: Float                 => LiteralValue.FloatValue(f)
    case d: Double                => LiteralValue.DoubleValue(d)
    case s: String                => LiteralValue.StringValue(s)
    case bd: java.math.BigDecimal => LiteralValue.DecimalValue(BigDecimal(bd))
    case _                        => LiteralValue.StringValue(v.toString)
