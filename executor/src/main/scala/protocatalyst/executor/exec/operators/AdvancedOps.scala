package protocatalyst.executor.exec.operators

import scala.collection.mutable

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector._

import protocatalyst.arrow.ArrowSchemaConverter
import protocatalyst.executor.exec._
import protocatalyst.expr.ProtoExpr
import protocatalyst.schema.ProtoSchema
import protocatalyst.types._

/** Advanced plan operators: Pivot, Unpivot, Generate, LateralJoin. */
object AdvancedOps:

  // ============================================================
  // Unpivot: wide → long
  // ============================================================

  /** Unpivot transforms columns into rows.
    *
    * For example, given columns (id, q1, q2, q3) with variableColumnName="quarter" and
    * valueColumnName="revenue", produces rows like (id, "q1", val1), (id, "q2", val2), etc.
    *
    * The `columns` parameter is a Vector of (expression, optional alias). The expression is
    * typically a ColumnRef, and the alias (if present) overrides the column name used as the
    * variable value.
    */
  def unpivot(
      input: Batch,
      valueColumnName: String,
      variableColumnName: String,
      columns: Vector[(ProtoExpr, Option[String])],
      includeNulls: Boolean,
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): Batch =
    if input.rowCount == 0 || columns.isEmpty then
      val fields = Vector(
        ProtoStructField(variableColumnName, ProtoType.StringType, nullable = false),
        ProtoStructField(valueColumnName, ProtoType.StringType, nullable = true)
      )
      return Batch.empty(ProtoSchema(fields), allocator)

    // Identify "other" columns (not in the unpivot list)
    val unpivotColNames = columns.map { (expr, _) =>
      ProjectOp.extractName(expr)
    }.toSet

    val otherFields = input.schema.fields.filterNot(f => unpivotColNames.contains(f.name))
    val otherIndices = otherFields.map(f => input.schema.fields.indexWhere(_.name == f.name))

    // Evaluate unpivot column vectors
    val unpivotVecs = columns.map((expr, _) => evaluator.eval(expr, input))
    val variableNames = columns.map { (expr, alias) =>
      alias.getOrElse(ProjectOp.extractName(expr))
    }

    // Infer value type from first unpivot column
    val valueType =
      if unpivotVecs.nonEmpty then ProjectOp.inferProtoType(unpivotVecs.head)
      else ProtoType.StringType

    // Output schema: otherFields + variableColumn + valueColumn
    val outputFields = otherFields :+
      ProtoStructField(variableColumnName, ProtoType.StringType, nullable = false) :+
      ProtoStructField(valueColumnName, valueType, nullable = true)
    val outputSchema = ProtoSchema(outputFields)
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(outputSchema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.allocateNew()

    var outputRow = 0
    for inputRow <- 0 until input.rowCount do
      for (unpivotVec, colIdx) <- unpivotVecs.zipWithIndex do
        val isNull = unpivotVec.isNull(inputRow)
        if includeNulls || !isNull then
          // Copy "other" columns
          for (srcColIdx, dstColIdx) <- otherIndices.zipWithIndex do
            evaluator.copyValue(
              input.root.getVector(srcColIdx),
              inputRow,
              root.getVector(dstColIdx),
              outputRow
            )
          // Write variable name
          val varIdx = otherFields.size
          val nameBytes = variableNames(colIdx).getBytes("UTF-8")
          root
            .getVector(varIdx)
            .asInstanceOf[VarCharVector]
            .setSafe(outputRow, nameBytes, 0, nameBytes.length)
          // Write value
          val valIdx = otherFields.size + 1
          if isNull then Batch.setNull(root.getVector(valIdx), outputRow)
          else evaluator.copyValue(unpivotVec, inputRow, root.getVector(valIdx), outputRow)
          outputRow += 1

    root.setRowCount(outputRow)
    Batch.fromRoot(root, outputSchema)

  // ============================================================
  // Pivot: long → wide
  // ============================================================

  /** Pivot transforms rows into columns.
    *
    * Groups by groupingExprs, then for each distinct pivotValue, creates a new column containing
    * the aggregate result for that pivot value.
    */
  def pivot(
      input: Batch,
      groupingExprs: Vector[ProtoExpr],
      pivotColumn: ProtoExpr,
      pivotValues: Vector[ProtoExpr],
      aggregates: Vector[ProtoExpr],
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): Batch =
    // Evaluate grouping keys and pivot column
    val groupVecs = groupingExprs.map(e => evaluator.eval(e, input))
    val pivotVec = evaluator.eval(pivotColumn, input)

    // Determine pivot value literals
    val pivotLiterals = pivotValues.map {
      case ProtoExpr.Literal(lit) => lit
      case other => throw ExecutionException(s"PIVOT values must be literals, got: $other")
    }
    val pivotValuesAny = pivotLiterals.map(litToAny)

    // Group rows: groupKey → (pivotValue → rowIndices)
    val groups = mutable.LinkedHashMap[Vector[Any], mutable.Map[Any, mutable.ArrayBuffer[Int]]]()
    for i <- 0 until input.rowCount do
      val groupKey = groupVecs.map(v => Batch.getValue(v, i)).toVector
      val pivotVal = Batch.getValue(pivotVec, i)
      val pivotMap = groups.getOrElseUpdate(groupKey, mutable.LinkedHashMap())
      pivotMap.getOrElseUpdate(pivotVal, mutable.ArrayBuffer.empty) += i

    // Build output schema: group columns + (pivotValue_aggName) columns
    val groupFields = groupingExprs.zipWithIndex.map { (expr, idx) =>
      val name = ProjectOp.extractName(expr)
      val protoType = ProjectOp.inferProtoType(groupVecs(idx))
      ProtoStructField(name, protoType, nullable = true)
    }

    val pivotFields = for
      pv <- pivotValues
      agg <- aggregates
    yield
      val pvName = pv match
        case ProtoExpr.Literal(LiteralValue.StringValue(s)) => s
        case ProtoExpr.Literal(lit)                         => litToAny(lit).toString
        case _                                              => pv.toString
      val aggName = AggregateOp.extractAggName(agg)
      val colName = if aggregates.size == 1 then pvName else s"${pvName}_$aggName"
      ProtoStructField(colName, ProtoType.DoubleType, nullable = true)

    val outputSchema = ProtoSchema(groupFields ++ pivotFields)
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(outputSchema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.allocateNew()

    var outputRow = 0
    for (groupKey, pivotMap) <- groups do
      // Write group columns
      for (value, colIdx) <- groupKey.zipWithIndex do
        if value == null then Batch.setNull(root.getVector(colIdx), outputRow)
        else
          val lit = anyToLiteral(value)
          Batch.setLiteralValue(root.getVector(colIdx), outputRow, lit)

      // Write pivot columns
      var pivotColOffset = groupFields.size
      for pvAny <- pivotValuesAny do
        val rowIndices = pivotMap.getOrElse(pvAny, mutable.ArrayBuffer.empty)
        for agg <- aggregates do
          if rowIndices.isEmpty then Batch.setNull(root.getVector(pivotColOffset), outputRow)
          else
            val aggResult = computeSimpleAggregate(agg, rowIndices.toVector, input, evaluator)
            if aggResult == null then Batch.setNull(root.getVector(pivotColOffset), outputRow)
            else
              val lit = anyToLiteral(aggResult)
              Batch.setLiteralValue(root.getVector(pivotColOffset), outputRow, lit)
          pivotColOffset += 1

      outputRow += 1

    root.setRowCount(outputRow)
    Batch.fromRoot(root, outputSchema)

  // ============================================================
  // Generate: table-generating functions (Explode, etc.)
  // ============================================================

  /** Generate operator: applies a generator function (Explode, PosExplode, etc.) to each input row,
    * producing zero or more output rows per input row.
    */
  def generate(
      input: Batch,
      generator: ProtoExpr,
      generatorOutput: Vector[String],
      outer: Boolean,
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): Batch =
    // Determine generator type and the child expression
    val (genFunc, childExpr) = generator match
      case ProtoExpr.Explode(child)           => (GenFunc.Explode, child)
      case ProtoExpr.PosExplode(child)        => (GenFunc.PosExplode, child)
      case ProtoExpr.Inline(child)            => (GenFunc.Inline, child)
      case ProtoExpr.Stack(numRows, children) => (GenFunc.Stack, generator)
      case other => throw ExecutionException(s"Unsupported generator: $other")

    // Evaluate the child expression to get array/struct values
    val childVec = evaluator.eval(childExpr, input)

    // Build output: input columns + generator output columns
    val genOutputFields =
      generatorOutput.map(name => ProtoStructField(name, ProtoType.StringType, nullable = true))
    val outputSchema = ProtoSchema(input.schema.fields ++ genOutputFields)
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(outputSchema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.allocateNew()

    var outputRow = 0
    for inputRow <- 0 until input.rowCount do
      val elements = extractElements(childVec, inputRow, genFunc)

      if elements.isEmpty && outer then
        // OUTER: emit input row with nulls for generator columns
        for colIdx <- 0 until input.numColumns do
          evaluator.copyValue(
            input.root.getVector(colIdx),
            inputRow,
            root.getVector(colIdx),
            outputRow
          )
        for genColIdx <- 0 until genOutputFields.size do
          Batch.setNull(root.getVector(input.numColumns + genColIdx), outputRow)
        outputRow += 1
      else
        for (element, elemIdx) <- elements.zipWithIndex do
          // Copy input columns
          for colIdx <- 0 until input.numColumns do
            evaluator.copyValue(
              input.root.getVector(colIdx),
              inputRow,
              root.getVector(colIdx),
              outputRow
            )
          // Write generator output
          genFunc match
            case GenFunc.PosExplode =>
              // First output column is the position (0-based)
              if genOutputFields.size >= 1 then
                root.getVector(input.numColumns).asInstanceOf[IntVector].setSafe(outputRow, elemIdx)
              if genOutputFields.size >= 2 then
                setAnyValue(root.getVector(input.numColumns + 1), outputRow, element)
            case _ =>
              element match
                case values: Vector[?] =>
                  for (v, gi) <- values.zipWithIndex if gi < genOutputFields.size do
                    setAnyValue(root.getVector(input.numColumns + gi), outputRow, v)
                case _ =>
                  if genOutputFields.nonEmpty then
                    setAnyValue(root.getVector(input.numColumns), outputRow, element)
          outputRow += 1

    root.setRowCount(outputRow)
    Batch.fromRoot(root, outputSchema)

  // ============================================================
  // LateralJoin
  // ============================================================

  /** Lateral join: for each row of the left input, execute the right (lateral) subquery and join.
    */
  def lateralJoin(
      left: Batch,
      rightFn: () => Batch,
      condition: Option[ProtoExpr],
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): Batch =
    // Execute right side once (simplified: lateral typically re-evaluates per left row,
    // but without correlated references, we just cross-join)
    val right = rightFn()

    val outputFields = left.schema.fields ++ right.schema.fields
    val outputSchema = ProtoSchema(outputFields)
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(outputSchema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.allocateNew()

    var outputRow = 0
    for li <- 0 until left.rowCount do
      for ri <- 0 until right.rowCount do
        val matches = condition match
          case None       => true
          case Some(cond) =>
            val combined =
              JoinOp.combineSingleRow(left, li, right, ri, outputSchema, evaluator, allocator)
            val condVec = evaluator.eval(cond, combined)
            val result = !condVec.isNull(0) && condVec.asInstanceOf[BitVector].get(0) != 0
            combined.close()
            result

        if matches then
          for colIdx <- 0 until left.numColumns do
            evaluator.copyValue(left.root.getVector(colIdx), li, root.getVector(colIdx), outputRow)
          for colIdx <- 0 until right.numColumns do
            evaluator.copyValue(
              right.root.getVector(colIdx),
              ri,
              root.getVector(left.numColumns + colIdx),
              outputRow
            )
          outputRow += 1

    root.setRowCount(outputRow)
    Batch.fromRoot(root, outputSchema)

  // ============================================================
  // Helpers
  // ============================================================

  private enum GenFunc:
    case Explode, PosExplode, Inline, Stack

  /** Extract elements from a vector value for the generator. */
  private def extractElements(vec: FieldVector, row: Int, genFunc: GenFunc): Vector[Any] =
    if vec.isNull(row) then return Vector.empty
    val value = Batch.getValue(vec, row)
    value match
      case list: java.util.List[?] => Vector.from(list.toArray)
      case arr: Array[?]           => arr.toVector
      case s: String               =>
        // Treat as a single element
        Vector(s)
      case _ => Vector(value)

  private def setAnyValue(vec: FieldVector, row: Int, value: Any): Unit =
    if value == null then Batch.setNull(vec, row)
    else
      val lit = anyToLiteral(value)
      Batch.setLiteralValue(vec, row, lit)

  private def anyToLiteral(v: Any): LiteralValue = v match
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

  private def litToAny(lit: LiteralValue): Any = lit match
    case LiteralValue.BooleanValue(v) => v
    case LiteralValue.ByteValue(v)    => v
    case LiteralValue.ShortValue(v)   => v
    case LiteralValue.IntValue(v)     => v
    case LiteralValue.LongValue(v)    => v
    case LiteralValue.FloatValue(v)   => v
    case LiteralValue.DoubleValue(v)  => v
    case LiteralValue.StringValue(v)  => v
    case LiteralValue.DecimalValue(v) => v.bigDecimal
    case LiteralValue.NullValue(_)    => null
    case _                            => lit.toString

  /** Compute a simple aggregate over specific row indices. Used by Pivot to aggregate within each
    * pivot group.
    */
  private def computeSimpleAggregate(
      aggExpr: ProtoExpr,
      rowIndices: Vector[Int],
      input: Batch,
      evaluator: ExprEvaluator
  ): Any =
    val (func, childExpr) = aggExpr match
      case ProtoExpr.Alias(child, _) =>
        return computeSimpleAggregate(child, rowIndices, input, evaluator)
      case ProtoExpr.Count(child, _) => ("count", child)
      case ProtoExpr.Sum(child)      => ("sum", child)
      case ProtoExpr.Avg(child)      => ("avg", child)
      case ProtoExpr.Min(child)      => ("min", child)
      case ProtoExpr.Max(child)      => ("max", child)
      case other => throw ExecutionException(s"Unsupported pivot aggregate: $other")

    val childVec = evaluator.eval(childExpr, input)
    func match
      case "count" =>
        var count = 0L
        for i <- rowIndices do if !childVec.isNull(i) then count += 1
        count
      case "sum" =>
        var sum = 0.0
        var hasValue = false
        for i <- rowIndices do
          if !childVec.isNull(i) then
            hasValue = true
            sum += toDouble(Batch.getValue(childVec, i))
        if hasValue then sum else null
      case "avg" =>
        var sum = 0.0
        var count = 0L
        for i <- rowIndices do
          if !childVec.isNull(i) then
            sum += toDouble(Batch.getValue(childVec, i))
            count += 1
        if count > 0 then sum / count else null
      case "min" =>
        var min: Any = null
        for i <- rowIndices do
          if !childVec.isNull(i) then
            val v = Batch.getValue(childVec, i)
            if min == null || SortOp.compareValues(v, min) < 0 then min = v
        min
      case "max" =>
        var max: Any = null
        for i <- rowIndices do
          if !childVec.isNull(i) then
            val v = Batch.getValue(childVec, i)
            if max == null || SortOp.compareValues(v, max) > 0 then max = v
        max

  private def toDouble(v: Any): Double = v match
    case d: Double                => d
    case f: Float                 => f.toDouble
    case l: Long                  => l.toDouble
    case i: Int                   => i.toDouble
    case s: Short                 => s.toDouble
    case b: Byte                  => b.toDouble
    case bd: java.math.BigDecimal => bd.doubleValue
    case _                        => v.toString.toDouble
