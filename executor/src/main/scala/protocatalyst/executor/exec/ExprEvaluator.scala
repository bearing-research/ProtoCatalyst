package protocatalyst.executor.exec

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector._
import org.apache.arrow.vector.types.pojo.FieldType

import protocatalyst.arrow.ArrowSchemaConverter
import protocatalyst.expr._
import protocatalyst.types._

/** Evaluates ProtoExpr expressions against Arrow batches.
  *
  * Returns FieldVector results allocated from the provided allocator. Callers are responsible for
  * closing returned vectors when they are no longer needed (typically when the output Batch is
  * closed).
  */
class ExprEvaluator(allocator: BufferAllocator):

  /** Optional callback for evaluating subquery plans. Set by PlanExecutor. */
  private[executor] var subqueryEvaluator: Option[protocatalyst.plan.ProtoLogicalPlan => Batch] =
    None

  /** Evaluate an expression against a batch, returning a column vector. */
  def eval(expr: ProtoExpr, batch: Batch): FieldVector = expr match
    // === Leaf nodes ===
    case ProtoExpr.Literal(value)                   => materializeLiteral(value, batch.rowCount)
    case ProtoExpr.ColumnRef(name, qualifier, _, _) => resolveColumn(name, qualifier, batch)
    case ProtoExpr.BoundRef(index, _, _)            => batch.column(index)
    case ProtoExpr.Alias(child, _)                  => eval(child, batch)

    // === Comparison ===
    case ProtoExpr.Eq(left, right)    => compareOp(left, right, batch, CompareOp.Eq)
    case ProtoExpr.NotEq(left, right) => compareOp(left, right, batch, CompareOp.NotEq)
    case ProtoExpr.Lt(left, right)    => compareOp(left, right, batch, CompareOp.Lt)
    case ProtoExpr.LtEq(left, right)  => compareOp(left, right, batch, CompareOp.LtEq)
    case ProtoExpr.Gt(left, right)    => compareOp(left, right, batch, CompareOp.Gt)
    case ProtoExpr.GtEq(left, right)  => compareOp(left, right, batch, CompareOp.GtEq)

    // === Logical ===
    case ProtoExpr.And(children) =>
      val vecs = children.map(c => eval(c, batch))
      andVectors(vecs, batch.rowCount)

    case ProtoExpr.Or(children) =>
      val vecs = children.map(c => eval(c, batch))
      orVectors(vecs, batch.rowCount)

    case ProtoExpr.Not(child) =>
      notVector(eval(child, batch), batch.rowCount)

    // === Null handling ===
    case ProtoExpr.IsNull(child) =>
      val vec = eval(child, batch)
      isNullVector(vec, batch.rowCount)

    case ProtoExpr.IsNotNull(child) =>
      val vec = eval(child, batch)
      isNotNullVector(vec, batch.rowCount)

    case ProtoExpr.Coalesce(children) =>
      coalesce(children, batch)

    case ProtoExpr.NullIf(left, right) =>
      val leftVec = eval(left, batch)
      val rightVec = eval(right, batch)
      nullIf(leftVec, rightVec, batch.rowCount)

    // === Arithmetic ===
    case ProtoExpr.Add(left, right)      => arithmeticOp(left, right, batch, ArithOp.Add)
    case ProtoExpr.Subtract(left, right) => arithmeticOp(left, right, batch, ArithOp.Sub)
    case ProtoExpr.Multiply(left, right) => arithmeticOp(left, right, batch, ArithOp.Mul)
    case ProtoExpr.Divide(left, right)   => arithmeticOp(left, right, batch, ArithOp.Div)

    // === Math functions ===
    case ProtoExpr.Abs(child)   => unaryMathOp(child, batch, math.abs(_: Double))
    case ProtoExpr.Ceil(child)  => unaryMathOp(child, batch, math.ceil)
    case ProtoExpr.Floor(child) => unaryMathOp(child, batch, math.floor)
    case ProtoExpr.Sqrt(child)  => unaryMathOp(child, batch, math.sqrt)
    case ProtoExpr.Cbrt(child)  => unaryMathOp(child, batch, math.cbrt)
    case ProtoExpr.Exp(child)   => unaryMathOp(child, batch, math.exp)
    case ProtoExpr.Sign(child)  => unaryMathOp(child, batch, math.signum)

    case ProtoExpr.Log(child, base) =>
      val childVec = eval(child, batch)
      base match
        case Some(b) =>
          val baseVec = eval(b, batch)
          binaryDoubleOp(childVec, baseVec, batch.rowCount, (v, b) => math.log(v) / math.log(b))
        case None =>
          unaryDoubleOp(childVec, batch.rowCount, math.log)

    case ProtoExpr.Pow(left, right) =>
      val leftVec = eval(left, batch)
      val rightVec = eval(right, batch)
      binaryDoubleOp(leftVec, rightVec, batch.rowCount, math.pow)

    case ProtoExpr.Pmod(left, right) =>
      arithmeticOp(left, right, batch, ArithOp.Mod)

    case ProtoExpr.Round(child, scale) =>
      val childVec = eval(child, batch)
      val scaleVec = eval(scale, batch)
      roundOp(childVec, scaleVec, batch.rowCount)

    case ProtoExpr.Truncate(child, scale) =>
      val childVec = eval(child, batch)
      val scaleVec = eval(scale, batch)
      truncateOp(childVec, scaleVec, batch.rowCount)

    // === String functions ===
    case ProtoExpr.Upper(child)   => unaryStringOp(child, batch, _.toUpperCase)
    case ProtoExpr.Lower(child)   => unaryStringOp(child, batch, _.toLowerCase)
    case ProtoExpr.Reverse(child) => unaryStringOp(child, batch, _.reverse)

    case ProtoExpr.Length(child) =>
      stringToIntOp(child, batch, _.length)

    case ProtoExpr.Concat(children) =>
      concatOp(children, batch)

    case ProtoExpr.Substring(str, pos, len) =>
      substringOp(str, pos, len, batch)

    case ProtoExpr.Trim(child, trimStr, trimType) =>
      trimOp(child, trimStr, trimType, batch)

    case ProtoExpr.Replace(str, search, replace) =>
      replaceOp(str, search, replace, batch)

    case ProtoExpr.StringLocate(substr, str, start) =>
      locateOp(substr, str, start, batch)

    case ProtoExpr.Lpad(str, len, pad) =>
      padOp(str, len, pad, batch, leftPad = true)

    case ProtoExpr.Rpad(str, len, pad) =>
      padOp(str, len, pad, batch, leftPad = false)

    case ProtoExpr.StringSplit(str, delimiter, limit) =>
      stringSplitOp(str, delimiter, limit, batch)

    case ProtoExpr.StringRepeat(str, times) =>
      stringRepeatOp(str, times, batch)

    // === Control flow ===
    case ProtoExpr.CaseWhen(branches, elseValue) =>
      caseWhenOp(branches, elseValue, batch)

    case ProtoExpr.If(predicate, trueValue, falseValue) =>
      caseWhenOp(Vector((predicate, trueValue)), Some(falseValue), batch)

    case ProtoExpr.In(value, list) =>
      inOp(value, list, batch)

    // === Pattern matching ===
    case ProtoExpr.Like(value, pattern, escape) =>
      likeOp(value, pattern, escape, batch)

    // === Cast ===
    case ProtoExpr.Cast(child, targetType) =>
      castOp(child, targetType, batch)

    // === Date/Time ===
    case ProtoExpr.CurrentDate() =>
      materializeLiteral(LiteralValue.DateValue(currentEpochDays()), batch.rowCount)
    case ProtoExpr.CurrentTimestamp() =>
      materializeLiteral(LiteralValue.TimestampValue(currentEpochMicros()), batch.rowCount)
    case ProtoExpr.Year(child)            => extractDateField(child, batch, DateTimeField.Year)
    case ProtoExpr.Month(child)           => extractDateField(child, batch, DateTimeField.Month)
    case ProtoExpr.DayOfMonth(child)      => extractDateField(child, batch, DateTimeField.Day)
    case ProtoExpr.Hour(child)            => extractDateField(child, batch, DateTimeField.Hour)
    case ProtoExpr.Minute(child)          => extractDateField(child, batch, DateTimeField.Minute)
    case ProtoExpr.Second(child)          => extractDateField(child, batch, DateTimeField.Second)
    case ProtoExpr.Extract(field, source) => extractDateField(source, batch, field)
    case ProtoExpr.DateAdd(start, days)   => dateArithOp(start, days, batch, add = true)
    case ProtoExpr.DateSub(start, days)   => dateArithOp(start, days, batch, add = false)
    case ProtoExpr.DateDiff(end, start)   => dateDiffOp(end, start, batch)
    case ProtoExpr.DateTrunc(field, timestamp) => dateTruncOp(field, timestamp, batch)
    case ProtoExpr.ToDate(str, format)         => toDateOp(str, format, batch)
    case ProtoExpr.ToTimestamp(str, format)    => toTimestampOp(str, format, batch)

    // === Aggregates (evaluated by AggregateOp, not here) ===
    case _: ProtoExpr.Count | _: ProtoExpr.Sum | _: ProtoExpr.Avg | _: ProtoExpr.Min |
        _: ProtoExpr.Max =>
      throw ExecutionException("Aggregate expressions must be evaluated by AggregateOp")

    // === Window functions (evaluated by WindowOp) ===
    case _: ProtoExpr.RowNumber | _: ProtoExpr.Rank | _: ProtoExpr.DenseRank | _: ProtoExpr.Ntile |
        _: ProtoExpr.Lead | _: ProtoExpr.Lag | _: ProtoExpr.FirstValue | _: ProtoExpr.LastValue |
        _: ProtoExpr.NthValue | _: ProtoExpr.WindowExpr =>
      throw ExecutionException("Window expressions must be evaluated by WindowOp")

    // === Subquery expressions ===
    case ProtoExpr.ScalarSubquery(plan) =>
      val execFn = subqueryEvaluator.getOrElse(
        throw ExecutionException(
          "ScalarSubquery requires a subquery evaluator (set by PlanExecutor)"
        )
      )
      val subResult = execFn(plan)
      if subResult.rowCount == 0 then
        materializeLiteral(LiteralValue.NullValue(ProtoType.StringType), batch.rowCount)
      else
        // Scalar subquery returns first column, first row, broadcast to all rows
        val value = Batch.getValue(subResult.root.getVector(0), 0)
        if value == null then
          materializeLiteral(LiteralValue.NullValue(ProtoType.StringType), batch.rowCount)
        else
          val lit = anyToLiteral(value)
          materializeLiteral(lit, batch.rowCount)

    case ProtoExpr.Exists(plan) =>
      val execFn = subqueryEvaluator.getOrElse(
        throw ExecutionException("Exists requires a subquery evaluator (set by PlanExecutor)")
      )
      val subResult = execFn(plan)
      val exists = subResult.rowCount > 0
      val result = new BitVector("result", allocator)
      result.allocateNew(batch.rowCount)
      for i <- 0 until batch.rowCount do result.setSafe(i, if exists then 1 else 0)
      result.setValueCount(batch.rowCount)
      result

    case ProtoExpr.InSubquery(value, plan) =>
      val execFn = subqueryEvaluator.getOrElse(
        throw ExecutionException("InSubquery requires a subquery evaluator (set by PlanExecutor)")
      )
      val subResult = execFn(plan)
      val subValues =
        (0 until subResult.rowCount).map(i => Batch.getValue(subResult.root.getVector(0), i)).toSet
      val valueVec = eval(value, batch)
      val result = new BitVector("result", allocator)
      result.allocateNew(batch.rowCount)
      for i <- 0 until batch.rowCount do
        if valueVec.isNull(i) then result.setNull(i)
        else
          val v = Batch.getValue(valueVec, i)
          result.setSafe(i, if subValues.contains(v) then 1 else 0)
      result.setValueCount(batch.rowCount)
      result

    // === Generator functions ===
    case _: ProtoExpr.Explode | _: ProtoExpr.PosExplode | _: ProtoExpr.Inline |
        _: ProtoExpr.Stack =>
      throw ExecutionException("Generator expressions must be evaluated by Generate operator")

    // === Grouping ===
    case _: ProtoExpr.Grouping =>
      throw ExecutionException("Grouping expressions must be evaluated by AggregateOp")

    // === Opaque call ===
    case ProtoExpr.OpaqueCall(name, _, _, _) =>
      throw ExecutionException(s"OpaqueCall '$name' cannot be evaluated locally (no UDF registry)")

  // ============================================================
  // Column resolution
  // ============================================================

  private def resolveColumn(name: String, qualifier: Option[String], batch: Batch): FieldVector =
    qualifier match
      case Some(q) =>
        // Try qualified name first: "qualifier.name"
        val qualifiedName = s"$q.$name"
        val idx = batch.schema.fields.indexWhere(f =>
          f.name.equalsIgnoreCase(qualifiedName) || f.name.equalsIgnoreCase(name)
        )
        if idx < 0 then throw ExecutionException(s"Column not found: $qualifiedName")
        batch.root.getVector(idx)
      case None =>
        val idx = batch.schema.fields.indexWhere(_.name.equalsIgnoreCase(name))
        if idx < 0 then throw ExecutionException(s"Column not found: $name")
        batch.root.getVector(idx)

  // ============================================================
  // Literal materialization
  // ============================================================

  private[exec] def materializeLiteral(value: LiteralValue, rowCount: Int): FieldVector =
    val dt = LiteralValue.typeOf(value)
    val arrowType = ArrowSchemaConverter.toArrowType(dt)
    val isNull = value.isInstanceOf[LiteralValue.NullValue]
    val fieldType = new FieldType(true, arrowType, null)
    val field = new org.apache.arrow.vector.types.pojo.Field(
      "literal",
      fieldType,
      java.util.Collections.emptyList()
    )
    val vec = field.createVector(allocator)
    vec.allocateNew()
    for i <- 0 until rowCount do
      if isNull then Batch.setNull(vec, i)
      else Batch.setLiteralValue(vec, i, value)
    vec.setValueCount(rowCount)
    vec

  // ============================================================
  // Comparison operations
  // ============================================================

  private enum CompareOp:
    case Eq, NotEq, Lt, LtEq, Gt, GtEq

  private def compareOp(
      left: ProtoExpr,
      right: ProtoExpr,
      batch: Batch,
      op: CompareOp
  ): FieldVector =
    val leftVec = eval(left, batch)
    val rightVec = eval(right, batch)
    val result = new BitVector("result", allocator)
    result.allocateNew(batch.rowCount)

    for i <- 0 until batch.rowCount do
      if leftVec.isNull(i) || rightVec.isNull(i) then result.setNull(i)
      else
        val leftVal = Batch.getValue(leftVec, i)
        val rightVal = Batch.getValue(rightVec, i)
        val cmp = compareValues(leftVal, rightVal)
        val bit = op match
          case CompareOp.Eq    => cmp == 0
          case CompareOp.NotEq => cmp != 0
          case CompareOp.Lt    => cmp < 0
          case CompareOp.LtEq  => cmp <= 0
          case CompareOp.Gt    => cmp > 0
          case CompareOp.GtEq  => cmp >= 0
        result.setSafe(i, if bit then 1 else 0)

    result.setValueCount(batch.rowCount)
    result

  private def compareValues(a: Any, b: Any): Int = (a, b) match
    case (a: Boolean, b: Boolean)                           => java.lang.Boolean.compare(a, b)
    case (a: Byte, b: Byte)                                 => java.lang.Byte.compare(a, b)
    case (a: Short, b: Short)                               => java.lang.Short.compare(a, b)
    case (a: Int, b: Int)                                   => java.lang.Integer.compare(a, b)
    case (a: Long, b: Long)                                 => java.lang.Long.compare(a, b)
    case (a: Float, b: Float)                               => java.lang.Float.compare(a, b)
    case (a: Double, b: Double)                             => java.lang.Double.compare(a, b)
    case (a: String, b: String)                             => a.compareTo(b)
    case (a: java.math.BigDecimal, b: java.math.BigDecimal) => a.compareTo(b)
    // Widen numeric types for cross-type comparison
    case (a: Number, b: Number) => java.lang.Double.compare(a.doubleValue, b.doubleValue)
    case _                      => a.toString.compareTo(b.toString)

  // ============================================================
  // Logical operations
  // ============================================================

  private def andVectors(vecs: Vector[FieldVector], rowCount: Int): FieldVector =
    val result = new BitVector("result", allocator)
    result.allocateNew(rowCount)
    for i <- 0 until rowCount do
      var allTrue = true
      var anyNull = false
      for vec <- vecs do
        if vec.isNull(i) then anyNull = true
        else if vec.asInstanceOf[BitVector].get(i) == 0 then allTrue = false
      if !allTrue then result.setSafe(i, 0)
      else if anyNull then result.setNull(i)
      else result.setSafe(i, 1)
    result.setValueCount(rowCount)
    result

  private def orVectors(vecs: Vector[FieldVector], rowCount: Int): FieldVector =
    val result = new BitVector("result", allocator)
    result.allocateNew(rowCount)
    for i <- 0 until rowCount do
      var anyTrue = false
      var anyNull = false
      for vec <- vecs do
        if vec.isNull(i) then anyNull = true
        else if vec.asInstanceOf[BitVector].get(i) != 0 then anyTrue = true
      if anyTrue then result.setSafe(i, 1)
      else if anyNull then result.setNull(i)
      else result.setSafe(i, 0)
    result.setValueCount(rowCount)
    result

  private def notVector(vec: FieldVector, rowCount: Int): FieldVector =
    val result = new BitVector("result", allocator)
    result.allocateNew(rowCount)
    val bitVec = vec.asInstanceOf[BitVector]
    for i <- 0 until rowCount do
      if vec.isNull(i) then result.setNull(i)
      else result.setSafe(i, if bitVec.get(i) == 0 then 1 else 0)
    result.setValueCount(rowCount)
    result

  // ============================================================
  // Null handling operations
  // ============================================================

  private def isNullVector(vec: FieldVector, rowCount: Int): FieldVector =
    val result = new BitVector("result", allocator)
    result.allocateNew(rowCount)
    for i <- 0 until rowCount do result.setSafe(i, if vec.isNull(i) then 1 else 0)
    result.setValueCount(rowCount)
    result

  private def isNotNullVector(vec: FieldVector, rowCount: Int): FieldVector =
    val result = new BitVector("result", allocator)
    result.allocateNew(rowCount)
    for i <- 0 until rowCount do result.setSafe(i, if vec.isNull(i) then 0 else 1)
    result.setValueCount(rowCount)
    result

  private def coalesce(children: Vector[ProtoExpr], batch: Batch): FieldVector =
    val vecs = children.map(c => eval(c, batch))
    // Use first non-null vector's type for the result
    val first = vecs.head
    val result = first.getField.createVector(allocator)
    result.allocateNew()
    for i <- 0 until batch.rowCount do
      var found = false
      var j = 0
      while !found && j < vecs.size do
        if !vecs(j).isNull(i) then
          copyValue(vecs(j), i, result, i)
          found = true
        j += 1
      if !found then Batch.setNull(result, i)
    result.setValueCount(batch.rowCount)
    result

  private def nullIf(leftVec: FieldVector, rightVec: FieldVector, rowCount: Int): FieldVector =
    val result = leftVec.getField.createVector(allocator)
    result.allocateNew()
    for i <- 0 until rowCount do
      if leftVec.isNull(i) then Batch.setNull(result, i)
      else if !rightVec
          .isNull(i) && compareValues(Batch.getValue(leftVec, i), Batch.getValue(rightVec, i)) == 0
      then Batch.setNull(result, i)
      else copyValue(leftVec, i, result, i)
    result.setValueCount(rowCount)
    result

  // ============================================================
  // Arithmetic operations
  // ============================================================

  private enum ArithOp:
    case Add, Sub, Mul, Div, Mod

  private def arithmeticOp(
      left: ProtoExpr,
      right: ProtoExpr,
      batch: Batch,
      op: ArithOp
  ): FieldVector =
    val leftVec = eval(left, batch)
    val rightVec = eval(right, batch)

    // Determine result type based on operand types
    val resultVec = leftVec match
      case _: IntVector =>
        val result = new IntVector("result", allocator)
        result.allocateNew(batch.rowCount)
        for i <- 0 until batch.rowCount do
          if leftVec.isNull(i) || rightVec.isNull(i) then result.setNull(i)
          else
            val l = Batch.getValue(leftVec, i).asInstanceOf[Int]
            val r = toInt(Batch.getValue(rightVec, i))
            val v = op match
              case ArithOp.Add => l + r
              case ArithOp.Sub => l - r
              case ArithOp.Mul => l * r
              case ArithOp.Div =>
                if r == 0 then { result.setNull(i); 0 }
                else l / r
              case ArithOp.Mod =>
                if r == 0 then { result.setNull(i); 0 }
                else ((l % r) + r) % r
            if !leftVec.isNull(i) && !rightVec.isNull(i) then result.setSafe(i, v)
        result.setValueCount(batch.rowCount)
        result

      case _: BigIntVector =>
        val result = new BigIntVector("result", allocator)
        result.allocateNew(batch.rowCount)
        for i <- 0 until batch.rowCount do
          if leftVec.isNull(i) || rightVec.isNull(i) then result.setNull(i)
          else
            val l = Batch.getValue(leftVec, i).asInstanceOf[Long]
            val r = toLong(Batch.getValue(rightVec, i))
            val v = op match
              case ArithOp.Add => l + r
              case ArithOp.Sub => l - r
              case ArithOp.Mul => l * r
              case ArithOp.Div =>
                if r == 0 then { result.setNull(i); 0L }
                else l / r
              case ArithOp.Mod =>
                if r == 0 then { result.setNull(i); 0L }
                else ((l % r) + r) % r
            if !leftVec.isNull(i) && !rightVec.isNull(i) then result.setSafe(i, v)
        result.setValueCount(batch.rowCount)
        result

      case _ =>
        // Default to Double for other numeric types
        val result = new Float8Vector("result", allocator)
        result.allocateNew(batch.rowCount)
        for i <- 0 until batch.rowCount do
          if leftVec.isNull(i) || rightVec.isNull(i) then result.setNull(i)
          else
            val l = toDouble(Batch.getValue(leftVec, i))
            val r = toDouble(Batch.getValue(rightVec, i))
            val v = op match
              case ArithOp.Add => l + r
              case ArithOp.Sub => l - r
              case ArithOp.Mul => l * r
              case ArithOp.Div =>
                if r == 0.0 then { result.setNull(i); 0.0 }
                else l / r
              case ArithOp.Mod =>
                if r == 0.0 then { result.setNull(i); 0.0 }
                else ((l % r) + r) % r
            if !leftVec.isNull(i) && !rightVec.isNull(i) then result.setSafe(i, v)
        result.setValueCount(batch.rowCount)
        result

    resultVec

  // ============================================================
  // Math helpers
  // ============================================================

  private def unaryMathOp(child: ProtoExpr, batch: Batch, f: Double => Double): FieldVector =
    val childVec = eval(child, batch)
    unaryDoubleOp(childVec, batch.rowCount, f)

  private def unaryDoubleOp(vec: FieldVector, rowCount: Int, f: Double => Double): FieldVector =
    val result = new Float8Vector("result", allocator)
    result.allocateNew(rowCount)
    for i <- 0 until rowCount do
      if vec.isNull(i) then result.setNull(i)
      else result.setSafe(i, f(toDouble(Batch.getValue(vec, i))))
    result.setValueCount(rowCount)
    result

  private def binaryDoubleOp(
      left: FieldVector,
      right: FieldVector,
      rowCount: Int,
      f: (Double, Double) => Double
  ): FieldVector =
    val result = new Float8Vector("result", allocator)
    result.allocateNew(rowCount)
    for i <- 0 until rowCount do
      if left.isNull(i) || right.isNull(i) then result.setNull(i)
      else
        result.setSafe(i, f(toDouble(Batch.getValue(left, i)), toDouble(Batch.getValue(right, i))))
    result.setValueCount(rowCount)
    result

  private def roundOp(childVec: FieldVector, scaleVec: FieldVector, rowCount: Int): FieldVector =
    val result = new Float8Vector("result", allocator)
    result.allocateNew(rowCount)
    for i <- 0 until rowCount do
      if childVec.isNull(i) then result.setNull(i)
      else
        val v = toDouble(Batch.getValue(childVec, i))
        val s = if scaleVec.isNull(i) then 0 else toInt(Batch.getValue(scaleVec, i))
        val factor = math.pow(10, s)
        result.setSafe(i, math.round(v * factor).toDouble / factor)
    result.setValueCount(rowCount)
    result

  private def truncateOp(childVec: FieldVector, scaleVec: FieldVector, rowCount: Int): FieldVector =
    val result = new Float8Vector("result", allocator)
    result.allocateNew(rowCount)
    for i <- 0 until rowCount do
      if childVec.isNull(i) then result.setNull(i)
      else
        val v = toDouble(Batch.getValue(childVec, i))
        val s = if scaleVec.isNull(i) then 0 else toInt(Batch.getValue(scaleVec, i))
        val factor = math.pow(10, s)
        result.setSafe(i, (v * factor).toLong.toDouble / factor)
    result.setValueCount(rowCount)
    result

  // ============================================================
  // String operations
  // ============================================================

  private def unaryStringOp(child: ProtoExpr, batch: Batch, f: String => String): FieldVector =
    val vec = eval(child, batch)
    val result = new VarCharVector("result", allocator)
    result.allocateNew()
    for i <- 0 until batch.rowCount do
      if vec.isNull(i) then result.setNull(i)
      else
        val s = getString(vec, i)
        val bytes = f(s).getBytes("UTF-8")
        result.setSafe(i, bytes, 0, bytes.length)
    result.setValueCount(batch.rowCount)
    result

  private def stringToIntOp(child: ProtoExpr, batch: Batch, f: String => Int): FieldVector =
    val vec = eval(child, batch)
    val result = new IntVector("result", allocator)
    result.allocateNew(batch.rowCount)
    for i <- 0 until batch.rowCount do
      if vec.isNull(i) then result.setNull(i)
      else result.setSafe(i, f(getString(vec, i)))
    result.setValueCount(batch.rowCount)
    result

  private def concatOp(children: Vector[ProtoExpr], batch: Batch): FieldVector =
    val vecs = children.map(c => eval(c, batch))
    val result = new VarCharVector("result", allocator)
    result.allocateNew()
    for i <- 0 until batch.rowCount do
      val sb = new StringBuilder
      var anyNull = false
      for vec <- vecs do
        if vec.isNull(i) then anyNull = true
        else sb.append(getString(vec, i))
      if anyNull && vecs.size == 1 then result.setNull(i)
      else
        val bytes = sb.toString.getBytes("UTF-8")
        result.setSafe(i, bytes, 0, bytes.length)
    result.setValueCount(batch.rowCount)
    result

  private def substringOp(
      str: ProtoExpr,
      pos: ProtoExpr,
      len: ProtoExpr,
      batch: Batch
  ): FieldVector =
    val strVec = eval(str, batch)
    val posVec = eval(pos, batch)
    val lenVec = eval(len, batch)
    val result = new VarCharVector("result", allocator)
    result.allocateNew()
    for i <- 0 until batch.rowCount do
      if strVec.isNull(i) then result.setNull(i)
      else
        val s = getString(strVec, i)
        val p = math.max(0, toInt(Batch.getValue(posVec, i)) - 1) // SQL is 1-based
        val l = toInt(Batch.getValue(lenVec, i))
        val sub = s.substring(math.min(p, s.length), math.min(p + l, s.length))
        val bytes = sub.getBytes("UTF-8")
        result.setSafe(i, bytes, 0, bytes.length)
    result.setValueCount(batch.rowCount)
    result

  private def trimOp(
      child: ProtoExpr,
      trimStr: Option[ProtoExpr],
      trimType: TrimType,
      batch: Batch
  ): FieldVector =
    val vec = eval(child, batch)
    val trimChars = trimStr.map(t => getString(eval(t, batch), 0)).getOrElse(" ")
    val result = new VarCharVector("result", allocator)
    result.allocateNew()
    for i <- 0 until batch.rowCount do
      if vec.isNull(i) then result.setNull(i)
      else
        var s = getString(vec, i)
        trimType match
          case TrimType.Both =>
            s = s.stripLeading().nn.stripTrailing().nn
          case TrimType.Leading =>
            s = s.stripLeading().nn
          case TrimType.Trailing =>
            s = s.stripTrailing().nn
        val bytes = s.getBytes("UTF-8")
        result.setSafe(i, bytes, 0, bytes.length)
    result.setValueCount(batch.rowCount)
    result

  private def replaceOp(
      str: ProtoExpr,
      search: ProtoExpr,
      replace: ProtoExpr,
      batch: Batch
  ): FieldVector =
    val strVec = eval(str, batch)
    val searchVec = eval(search, batch)
    val replaceVec = eval(replace, batch)
    val result = new VarCharVector("result", allocator)
    result.allocateNew()
    for i <- 0 until batch.rowCount do
      if strVec.isNull(i) then result.setNull(i)
      else
        val s = getString(strVec, i)
        val srch = getString(searchVec, i)
        val repl = getString(replaceVec, i)
        val bytes = s.replace(srch, repl).getBytes("UTF-8")
        result.setSafe(i, bytes, 0, bytes.length)
    result.setValueCount(batch.rowCount)
    result

  private def locateOp(
      substr: ProtoExpr,
      str: ProtoExpr,
      start: Option[ProtoExpr],
      batch: Batch
  ): FieldVector =
    val substrVec = eval(substr, batch)
    val strVec = eval(str, batch)
    val startVec = start.map(s => eval(s, batch))
    val result = new IntVector("result", allocator)
    result.allocateNew(batch.rowCount)
    for i <- 0 until batch.rowCount do
      if substrVec.isNull(i) || strVec.isNull(i) then result.setNull(i)
      else
        val sub = getString(substrVec, i)
        val s = getString(strVec, i)
        val from = startVec.map(v => toInt(Batch.getValue(v, i)) - 1).getOrElse(0)
        val idx = s.indexOf(sub, math.max(0, from))
        result.setSafe(i, idx + 1) // SQL is 1-based
    result.setValueCount(batch.rowCount)
    result

  private def padOp(
      str: ProtoExpr,
      len: ProtoExpr,
      pad: ProtoExpr,
      batch: Batch,
      leftPad: Boolean
  ): FieldVector =
    val strVec = eval(str, batch)
    val lenVec = eval(len, batch)
    val padVec = eval(pad, batch)
    val result = new VarCharVector("result", allocator)
    result.allocateNew()
    for i <- 0 until batch.rowCount do
      if strVec.isNull(i) then result.setNull(i)
      else
        val s = getString(strVec, i)
        val targetLen = toInt(Batch.getValue(lenVec, i))
        val p = getString(padVec, i)
        val padded =
          if s.length >= targetLen then s.take(targetLen)
          else if leftPad then
            val padding = p * ((targetLen - s.length) / p.length + 1)
            (padding.take(targetLen - s.length) + s)
          else
            val padding = p * ((targetLen - s.length) / p.length + 1)
            (s + padding.take(targetLen - s.length))
        val bytes = padded.getBytes("UTF-8")
        result.setSafe(i, bytes, 0, bytes.length)
    result.setValueCount(batch.rowCount)
    result

  private def stringRepeatOp(str: ProtoExpr, times: ProtoExpr, batch: Batch): FieldVector =
    val strVec = eval(str, batch)
    val timesVec = eval(times, batch)
    val result = new VarCharVector("result", allocator)
    result.allocateNew()
    for i <- 0 until batch.rowCount do
      if strVec.isNull(i) then result.setNull(i)
      else
        val s = getString(strVec, i)
        val n = toInt(Batch.getValue(timesVec, i))
        val bytes = (s * math.max(0, n)).getBytes("UTF-8")
        result.setSafe(i, bytes, 0, bytes.length)
    result.setValueCount(batch.rowCount)
    result

  /** StringSplit: split a string by delimiter, returning a VarChar with the joined result. Since
    * Arrow doesn't natively support array columns in our setup, we store the split result as a
    * JSON-like array string representation.
    */
  private def stringSplitOp(
      str: ProtoExpr,
      delimiter: ProtoExpr,
      limit: Option[ProtoExpr],
      batch: Batch
  ): FieldVector =
    val strVec = eval(str, batch)
    val delimVec = eval(delimiter, batch)
    val limitVec = limit.map(l => eval(l, batch))
    val result = new VarCharVector("result", allocator)
    result.allocateNew()
    for i <- 0 until batch.rowCount do
      if strVec.isNull(i) then result.setNull(i)
      else
        val s = getString(strVec, i)
        val d = getString(delimVec, i)
        val lim = limitVec.map(v => toInt(Batch.getValue(v, i))).getOrElse(-1)
        val parts =
          if lim > 0 then s.split(java.util.regex.Pattern.quote(d), lim)
          else s.split(java.util.regex.Pattern.quote(d))
        val bytes = parts.mkString("[", ",", "]").getBytes("UTF-8")
        result.setSafe(i, bytes, 0, bytes.length)
    result.setValueCount(batch.rowCount)
    result

  // ============================================================
  // Control flow
  // ============================================================

  private def caseWhenOp(
      branches: Vector[(ProtoExpr, ProtoExpr)],
      elseValue: Option[ProtoExpr],
      batch: Batch
  ): FieldVector =
    // Evaluate the first result branch to determine output type
    val firstResult = eval(branches.head._2, batch)
    val result = firstResult.getField.createVector(allocator)
    result.allocateNew()

    for i <- 0 until batch.rowCount do
      var matched = false
      var branchIdx = 0
      while !matched && branchIdx < branches.size do
        val (cond, value) = branches(branchIdx)
        val condVec = eval(cond, batch)
        if !condVec.isNull(i) && condVec.asInstanceOf[BitVector].get(i) != 0 then
          val valVec = eval(value, batch)
          if valVec.isNull(i) then Batch.setNull(result, i)
          else copyValue(valVec, i, result, i)
          matched = true
        branchIdx += 1
      if !matched then
        elseValue match
          case Some(e) =>
            val elseVec = eval(e, batch)
            if elseVec.isNull(i) then Batch.setNull(result, i)
            else copyValue(elseVec, i, result, i)
          case None =>
            Batch.setNull(result, i)

    result.setValueCount(batch.rowCount)
    result

  private def inOp(value: ProtoExpr, list: Vector[ProtoExpr], batch: Batch): FieldVector =
    val valueVec = eval(value, batch)
    val listVecs = list.map(l => eval(l, batch))
    val result = new BitVector("result", allocator)
    result.allocateNew(batch.rowCount)
    for i <- 0 until batch.rowCount do
      if valueVec.isNull(i) then result.setNull(i)
      else
        val v = Batch.getValue(valueVec, i)
        val found = listVecs.exists { lv =>
          !lv.isNull(i) && compareValues(v, Batch.getValue(lv, i)) == 0
        }
        result.setSafe(i, if found then 1 else 0)
    result.setValueCount(batch.rowCount)
    result

  // ============================================================
  // Pattern matching (LIKE)
  // ============================================================

  private def likeOp(
      value: ProtoExpr,
      pattern: ProtoExpr,
      escape: Option[ProtoExpr],
      batch: Batch
  ): FieldVector =
    val valueVec = eval(value, batch)
    val patternVec = eval(pattern, batch)
    val escapeChar = escape.map(e => getString(eval(e, batch), 0).charAt(0))
    val result = new BitVector("result", allocator)
    result.allocateNew(batch.rowCount)
    for i <- 0 until batch.rowCount do
      if valueVec.isNull(i) || patternVec.isNull(i) then result.setNull(i)
      else
        val v = getString(valueVec, i)
        val p = getString(patternVec, i)
        val regex = likeToRegex(p, escapeChar)
        result.setSafe(i, if v.matches(regex) then 1 else 0)
    result.setValueCount(batch.rowCount)
    result

  private def likeToRegex(pattern: String, escape: Option[Char]): String =
    val sb = new StringBuilder("(?s)^")
    var i = 0
    while i < pattern.length do
      val c = pattern.charAt(i)
      if escape.contains(c) && i + 1 < pattern.length then
        sb.append(java.util.regex.Pattern.quote(pattern.charAt(i + 1).toString))
        i += 2
      else if c == '%' then
        sb.append(".*")
        i += 1
      else if c == '_' then
        sb.append(".")
        i += 1
      else
        sb.append(java.util.regex.Pattern.quote(c.toString))
        i += 1
    sb.append("$")
    sb.toString

  // ============================================================
  // Cast
  // ============================================================

  private def castOp(child: ProtoExpr, targetType: ProtoType, batch: Batch): FieldVector =
    val childVec = eval(child, batch)
    val arrowType = ArrowSchemaConverter.toArrowType(targetType)
    val fieldType = new FieldType(true, arrowType, null)
    val field = new org.apache.arrow.vector.types.pojo.Field(
      "cast",
      fieldType,
      java.util.Collections.emptyList()
    )
    val result = field.createVector(allocator)
    result.allocateNew()

    for i <- 0 until batch.rowCount do
      if childVec.isNull(i) then Batch.setNull(result, i)
      else
        val v = Batch.getValue(childVec, i)
        castValue(v, targetType, result, i)

    result.setValueCount(batch.rowCount)
    result

  private def castValue(value: Any, target: ProtoType, vec: FieldVector, row: Int): Unit =
    target match
      case ProtoType.IntegerType =>
        vec.asInstanceOf[IntVector].setSafe(row, toInt(value))
      case ProtoType.LongType =>
        vec.asInstanceOf[BigIntVector].setSafe(row, toLong(value))
      case ProtoType.DoubleType =>
        vec.asInstanceOf[Float8Vector].setSafe(row, toDouble(value))
      case ProtoType.FloatType =>
        vec.asInstanceOf[Float4Vector].setSafe(row, toDouble(value).toFloat)
      case ProtoType.StringType | ProtoType.CharType(_) | ProtoType.VarcharType(_) =>
        val bytes = value.toString.getBytes("UTF-8")
        vec.asInstanceOf[VarCharVector].setSafe(row, bytes, 0, bytes.length)
      case ProtoType.BooleanType =>
        val b = value match
          case b: Boolean => b
          case n: Number  => n.intValue != 0
          case s: String  => s.equalsIgnoreCase("true") || s == "1"
          case _          => false
        vec.asInstanceOf[BitVector].setSafe(row, if b then 1 else 0)
      case ProtoType.ShortType =>
        vec.asInstanceOf[SmallIntVector].setSafe(row, toInt(value).toShort)
      case ProtoType.ByteType =>
        vec.asInstanceOf[TinyIntVector].setSafe(row, toInt(value).toByte)
      case _ =>
        throw ExecutionException(s"Cast to $target not yet implemented")

  // ============================================================
  // Date/time operations
  // ============================================================

  private def currentEpochDays(): Int =
    (System.currentTimeMillis() / (24L * 60 * 60 * 1000)).toInt

  private def currentEpochMicros(): Long =
    System.currentTimeMillis() * 1000

  private def extractDateField(child: ProtoExpr, batch: Batch, field: DateTimeField): FieldVector =
    val vec = eval(child, batch)
    val result = new IntVector("result", allocator)
    result.allocateNew(batch.rowCount)
    for i <- 0 until batch.rowCount do
      if vec.isNull(i) then result.setNull(i)
      else
        val value = Batch.getValue(vec, i)
        val extracted = extractField(value, field, vec)
        result.setSafe(i, extracted)
    result.setValueCount(batch.rowCount)
    result

  private def extractField(value: Any, field: DateTimeField, vec: FieldVector): Int =
    import java.time._
    vec match
      case _: DateDayVector =>
        val date = LocalDate.ofEpochDay(value.asInstanceOf[Int].toLong)
        field match
          case DateTimeField.Year    => date.getYear
          case DateTimeField.Month   => date.getMonthValue
          case DateTimeField.Day     => date.getDayOfMonth
          case DateTimeField.Quarter => (date.getMonthValue - 1) / 3 + 1
          case DateTimeField.Week => date.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
          case DateTimeField.DayOfWeek => date.getDayOfWeek.getValue
          case DateTimeField.DayOfYear => date.getDayOfYear
          case _                       => 0

      case _: TimeStampMicroTZVector =>
        val micros = value.asInstanceOf[Long]
        val instant = Instant.ofEpochSecond(micros / 1_000_000, (micros % 1_000_000) * 1000)
        val dt = LocalDateTime.ofInstant(instant, ZoneId.of("UTC"))
        field match
          case DateTimeField.Year    => dt.getYear
          case DateTimeField.Month   => dt.getMonthValue
          case DateTimeField.Day     => dt.getDayOfMonth
          case DateTimeField.Hour    => dt.getHour
          case DateTimeField.Minute  => dt.getMinute
          case DateTimeField.Second  => dt.getSecond
          case DateTimeField.Quarter => (dt.getMonthValue - 1) / 3 + 1
          case DateTimeField.Week    => dt.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
          case DateTimeField.DayOfWeek   => dt.getDayOfWeek.getValue
          case DateTimeField.DayOfYear   => dt.getDayOfYear
          case DateTimeField.Microsecond => (micros % 1_000_000).toInt
          case DateTimeField.Millisecond => ((micros % 1_000_000) / 1000).toInt

      case _ => 0

  private def dateArithOp(
      start: ProtoExpr,
      days: ProtoExpr,
      batch: Batch,
      add: Boolean
  ): FieldVector =
    val startVec = eval(start, batch)
    val daysVec = eval(days, batch)
    val result = new DateDayVector("result", allocator)
    result.allocateNew(batch.rowCount)
    for i <- 0 until batch.rowCount do
      if startVec.isNull(i) || daysVec.isNull(i) then result.setNull(i)
      else
        val d = Batch.getValue(startVec, i).asInstanceOf[Int]
        val n = toInt(Batch.getValue(daysVec, i))
        result.setSafe(i, if add then d + n else d - n)
    result.setValueCount(batch.rowCount)
    result

  private def dateDiffOp(end: ProtoExpr, start: ProtoExpr, batch: Batch): FieldVector =
    val endVec = eval(end, batch)
    val startVec = eval(start, batch)
    val result = new IntVector("result", allocator)
    result.allocateNew(batch.rowCount)
    for i <- 0 until batch.rowCount do
      if endVec.isNull(i) || startVec.isNull(i) then result.setNull(i)
      else
        val e = Batch.getValue(endVec, i).asInstanceOf[Int]
        val s = Batch.getValue(startVec, i).asInstanceOf[Int]
        result.setSafe(i, e - s)
    result.setValueCount(batch.rowCount)
    result

  /** DATE_TRUNC: truncate a timestamp to the given field precision. */
  private def dateTruncOp(field: DateTimeField, timestamp: ProtoExpr, batch: Batch): FieldVector =
    import java.time._
    val tsVec = eval(timestamp, batch)
    val result = new TimeStampMicroTZVector("result", allocator, "UTC")
    result.allocateNew(batch.rowCount)
    for i <- 0 until batch.rowCount do
      if tsVec.isNull(i) then result.setNull(i)
      else
        val micros = Batch.getValue(tsVec, i).asInstanceOf[Long]
        val instant = Instant.ofEpochSecond(micros / 1_000_000, (micros % 1_000_000) * 1000)
        val dt = LocalDateTime.ofInstant(instant, ZoneId.of("UTC"))
        val truncated = field match
          case DateTimeField.Year =>
            dt.withMonth(1).withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
          case DateTimeField.Quarter =>
            val qMonth = ((dt.getMonthValue - 1) / 3) * 3 + 1
            dt.withMonth(qMonth)
              .withDayOfMonth(1)
              .withHour(0)
              .withMinute(0)
              .withSecond(0)
              .withNano(0)
          case DateTimeField.Month =>
            dt.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
          case DateTimeField.Week =>
            val dow = dt.getDayOfWeek.getValue // Monday=1
            dt.minusDays((dow - 1).toLong).withHour(0).withMinute(0).withSecond(0).withNano(0)
          case DateTimeField.Day    => dt.withHour(0).withMinute(0).withSecond(0).withNano(0)
          case DateTimeField.Hour   => dt.withMinute(0).withSecond(0).withNano(0)
          case DateTimeField.Minute => dt.withSecond(0).withNano(0)
          case DateTimeField.Second => dt.withNano(0)
          case _                    => dt
        val truncInstant = truncated.toInstant(ZoneOffset.UTC)
        val truncMicros = truncInstant.getEpochSecond * 1_000_000 + truncInstant.getNano / 1000
        result.setSafe(i, truncMicros)
    result.setValueCount(batch.rowCount)
    result

  /** TO_DATE: parse a string to a date (epoch days). */
  private def toDateOp(str: ProtoExpr, formatExpr: Option[ProtoExpr], batch: Batch): FieldVector =
    import java.time._
    import java.time.format.DateTimeFormatter
    val strVec = eval(str, batch)
    val fmt = formatExpr.map(f => getString(eval(f, batch), 0)).getOrElse("yyyy-MM-dd")
    val formatter = DateTimeFormatter.ofPattern(fmt)
    val result = new DateDayVector("result", allocator)
    result.allocateNew(batch.rowCount)
    for i <- 0 until batch.rowCount do
      if strVec.isNull(i) then result.setNull(i)
      else
        try
          val s = getString(strVec, i)
          val date = LocalDate.parse(s, formatter)
          result.setSafe(i, date.toEpochDay.toInt)
        catch case _: Exception => result.setNull(i)
    result.setValueCount(batch.rowCount)
    result

  /** TO_TIMESTAMP: parse a string to a timestamp (epoch micros). */
  private def toTimestampOp(
      str: ProtoExpr,
      formatExpr: Option[ProtoExpr],
      batch: Batch
  ): FieldVector =
    import java.time._
    import java.time.format.DateTimeFormatter
    val strVec = eval(str, batch)
    val fmt = formatExpr.map(f => getString(eval(f, batch), 0)).getOrElse("yyyy-MM-dd HH:mm:ss")
    val formatter = DateTimeFormatter.ofPattern(fmt)
    val result = new TimeStampMicroTZVector("result", allocator, "UTC")
    result.allocateNew(batch.rowCount)
    for i <- 0 until batch.rowCount do
      if strVec.isNull(i) then result.setNull(i)
      else
        try
          val s = getString(strVec, i)
          val dt = LocalDateTime.parse(s, formatter)
          val instant = dt.toInstant(ZoneOffset.UTC)
          val micros = instant.getEpochSecond * 1_000_000 + instant.getNano / 1000
          result.setSafe(i, micros)
        catch case _: Exception => result.setNull(i)
    result.setValueCount(batch.rowCount)
    result

  // ============================================================
  // Helpers
  // ============================================================

  private def getString(vec: FieldVector, row: Int): String =
    Batch.getValue(vec, row).asInstanceOf[String]

  private def toInt(v: Any): Int = v match
    case i: Int                   => i
    case l: Long                  => l.toInt
    case s: Short                 => s.toInt
    case b: Byte                  => b.toInt
    case f: Float                 => f.toInt
    case d: Double                => d.toInt
    case bd: java.math.BigDecimal => bd.intValue
    case s: String                => s.toInt
    case b: Boolean               => if b then 1 else 0
    case _                        => throw ExecutionException(s"Cannot convert $v to Int")

  private def toLong(v: Any): Long = v match
    case l: Long                  => l
    case i: Int                   => i.toLong
    case s: Short                 => s.toLong
    case b: Byte                  => b.toLong
    case f: Float                 => f.toLong
    case d: Double                => d.toLong
    case bd: java.math.BigDecimal => bd.longValue
    case s: String                => s.toLong
    case _                        => throw ExecutionException(s"Cannot convert $v to Long")

  private def toDouble(v: Any): Double = v match
    case d: Double                => d
    case f: Float                 => f.toDouble
    case l: Long                  => l.toDouble
    case i: Int                   => i.toDouble
    case s: Short                 => s.toDouble
    case b: Byte                  => b.toDouble
    case bd: java.math.BigDecimal => bd.doubleValue
    case s: String                => s.toDouble
    case b: Boolean               => if b then 1.0 else 0.0
    case _                        => throw ExecutionException(s"Cannot convert $v to Double")

  /** Copy a value from one vector to another at the given row indices. */
  private[executor] def copyValue(
      src: FieldVector,
      srcRow: Int,
      dst: FieldVector,
      dstRow: Int
  ): Unit =
    if src.isNull(srcRow) then Batch.setNull(dst, dstRow)
    else
      (src, dst) match
        case (s: BitVector, d: BitVector)           => d.setSafe(dstRow, s.get(srcRow))
        case (s: TinyIntVector, d: TinyIntVector)   => d.setSafe(dstRow, s.get(srcRow))
        case (s: SmallIntVector, d: SmallIntVector) => d.setSafe(dstRow, s.get(srcRow))
        case (s: IntVector, d: IntVector)           => d.setSafe(dstRow, s.get(srcRow))
        case (s: BigIntVector, d: BigIntVector)     => d.setSafe(dstRow, s.get(srcRow))
        case (s: Float4Vector, d: Float4Vector)     => d.setSafe(dstRow, s.get(srcRow))
        case (s: Float8Vector, d: Float8Vector)     => d.setSafe(dstRow, s.get(srcRow))
        case (s: VarCharVector, d: VarCharVector)   =>
          val bytes = s.get(srcRow)
          d.setSafe(dstRow, bytes, 0, bytes.length)
        case (s: VarBinaryVector, d: VarBinaryVector) =>
          val bytes = s.get(srcRow)
          d.setSafe(dstRow, bytes, 0, bytes.length)
        case (s: DecimalVector, d: DecimalVector) =>
          d.setSafe(dstRow, s.getObject(srcRow))
        case (s: DateDayVector, d: DateDayVector) =>
          d.setSafe(dstRow, s.get(srcRow))
        case (s: TimeStampMicroTZVector, d: TimeStampMicroTZVector) =>
          d.setSafe(dstRow, s.get(srcRow))
        case _ =>
          // Generic fallback via object representation
          val v = Batch.getValue(src, srcRow)
          val lit = anyToLiteral(v)
          Batch.setLiteralValue(dst, dstRow, lit)

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
