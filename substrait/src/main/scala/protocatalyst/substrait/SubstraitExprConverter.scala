package protocatalyst.substrait

import io.substrait.expression.{Expression, ExpressionCreator}

import protocatalyst.expr.{DateTimeField, ProtoExpr, TrimType}
import protocatalyst.types.LiteralValue

/** Converts ProtoCatalyst expressions to Substrait expressions.
  *
  * This converter maps the 93+ ProtoExpr variants to Substrait's Expression type. The conversion
  * strategy:
  *   - Literals → Expression.Literal (via ExpressionCreator)
  *   - Column references → Expression.FieldReference
  *   - Binary operators → Expression.ScalarFunction with Substrait function signatures
  *   - Aggregates → Expression.AggregateFunction
  *   - Subqueries → Expression.Subquery
  *
  * **Unsupported expressions**: ML-specific expressions (TensorLiteral, MatmulOp, etc.) have no
  * Substrait equivalent and throw UnsupportedSubstraitFeatureException.
  *
  * **Function mapping**: Substrait uses function signatures (e.g., "add:i32_i32" for integer
  * addition). This converter generates the appropriate function signatures based on operand types.
  */
object SubstraitExprConverter:
  // Use default function registry
  private val functionRegistry = SubstraitFunctionRegistry.default

  /** Convert a ProtoExpr to a Substrait Expression.
    *
    * @param expr
    *   The ProtoCatalyst expression to convert
    * @return
    *   Substrait Expression
    * @throws UnsupportedSubstraitFeatureException
    *   if the expression cannot be represented in Substrait
    */
  def toSubstrait(expr: ProtoExpr): Expression = expr match
    // ========== Literals ==========
    case ProtoExpr.Literal(value) =>
      literalToSubstrait(value)

    // ========== Column References ==========
    case ProtoExpr.ColumnRef(name, qualifier, resolvedType, nullable) =>
      // Substrait uses field references (ordinal-based)
      // For now, we'll use a direct field reference
      // TODO: This requires schema context to map name → ordinal
      throw UnsupportedSubstraitFeatureException(
        "ColumnRef conversion requires schema context (not yet implemented)"
      )

    case ProtoExpr.BoundRef(index, dataType, nullable) =>
      // Direct ordinal reference (use StructReference for field access)
      // TODO: Implement proper field reference
      throw UnsupportedSubstraitFeatureException(
        "BoundRef conversion not yet implemented"
      )

    // ========== Comparison Operators ==========
    case ProtoExpr.Eq(left, right) =>
      scalarFunction("equal", Vector(left, right))

    case ProtoExpr.NotEq(left, right) =>
      scalarFunction("not_equal", Vector(left, right))

    case ProtoExpr.Lt(left, right) =>
      scalarFunction("lt", Vector(left, right))

    case ProtoExpr.LtEq(left, right) =>
      scalarFunction("lte", Vector(left, right))

    case ProtoExpr.Gt(left, right) =>
      scalarFunction("gt", Vector(left, right))

    case ProtoExpr.GtEq(left, right) =>
      scalarFunction("gte", Vector(left, right))

    // ========== Logical Operators ==========
    case ProtoExpr.And(children) =>
      // Substrait's and() takes exactly 2 arguments, so we need to chain for multiple
      if children.isEmpty then
        throw new IllegalArgumentException("And requires at least one child")
      else if children.size == 1 then toSubstrait(children.head)
      else
        children.map(toSubstrait).reduce { (left, right) =>
          scalarFunctionDirect("and", Vector(left, right))
        }

    case ProtoExpr.Or(children) =>
      // Similar to And, chain binary or() calls
      if children.isEmpty then
        throw new IllegalArgumentException("Or requires at least one child")
      else if children.size == 1 then toSubstrait(children.head)
      else
        children.map(toSubstrait).reduce { (left, right) =>
          scalarFunctionDirect("or", Vector(left, right))
        }

    case ProtoExpr.Not(child) =>
      scalarFunction("not", Vector(child))

    // ========== Null Handling ==========
    case ProtoExpr.IsNull(child) =>
      scalarFunction("is_null", Vector(child))

    case ProtoExpr.IsNotNull(child) =>
      scalarFunction("is_not_null", Vector(child))

    case ProtoExpr.Coalesce(children) =>
      scalarFunction("coalesce", children)

    case ProtoExpr.NullIf(left, right) =>
      scalarFunction("nullif", Vector(left, right))

    // ========== Arithmetic ==========
    case ProtoExpr.Add(left, right) =>
      scalarFunction("add", Vector(left, right))

    case ProtoExpr.Subtract(left, right) =>
      scalarFunction("subtract", Vector(left, right))

    case ProtoExpr.Multiply(left, right) =>
      scalarFunction("multiply", Vector(left, right))

    case ProtoExpr.Divide(left, right) =>
      scalarFunction("divide", Vector(left, right))

    // ========== Math Functions ==========
    case ProtoExpr.Abs(child) =>
      scalarFunction("abs", Vector(child))

    case ProtoExpr.Ceil(child) =>
      scalarFunction("ceil", Vector(child))

    case ProtoExpr.Floor(child) =>
      scalarFunction("floor", Vector(child))

    case ProtoExpr.Round(child, scale) =>
      scalarFunction("round", Vector(child, scale))

    case ProtoExpr.Truncate(child, scale) =>
      scalarFunction("trunc", Vector(child, scale))

    case ProtoExpr.Sqrt(child) =>
      scalarFunction("sqrt", Vector(child))

    case ProtoExpr.Cbrt(child) =>
      scalarFunction("cbrt", Vector(child))

    case ProtoExpr.Pow(left, right) =>
      scalarFunction("power", Vector(left, right))

    case ProtoExpr.Pmod(left, right) =>
      scalarFunction("modulus", Vector(left, right))

    case ProtoExpr.Sign(child) =>
      scalarFunction("sign", Vector(child))

    case ProtoExpr.Log(child, base) =>
      base match
        case Some(b) => scalarFunction("log", Vector(b, child))
        case None    => scalarFunction("ln", Vector(child))

    case ProtoExpr.Exp(child) =>
      scalarFunction("exp", Vector(child))

    // ========== String Functions ==========
    case ProtoExpr.Concat(children) =>
      scalarFunction("concat", children)

    case ProtoExpr.Substring(str, pos, len) =>
      scalarFunction("substring", Vector(str, pos, len))

    case ProtoExpr.Upper(child) =>
      scalarFunction("upper", Vector(child))

    case ProtoExpr.Lower(child) =>
      scalarFunction("lower", Vector(child))

    case ProtoExpr.Trim(child, trimStr, trimType) =>
      // Substrait has trim, ltrim, rtrim
      val funcName = trimType match
        case TrimType.Both    => "trim"
        case TrimType.Leading => "ltrim"
        case TrimType.Trailing => "rtrim"
      trimStr match
        case Some(s) => scalarFunction(funcName, Vector(child, s))
        case None    => scalarFunction(funcName, Vector(child))

    case ProtoExpr.Length(child) =>
      scalarFunction("char_length", Vector(child))

    case ProtoExpr.Replace(str, search, replace) =>
      scalarFunction("replace", Vector(str, search, replace))

    case ProtoExpr.StringLocate(substr, str, start) =>
      start match
        case Some(s) => scalarFunction("strpos", Vector(str, substr, s))
        case None    => scalarFunction("strpos", Vector(str, substr))

    case ProtoExpr.Lpad(str, len, pad) =>
      scalarFunction("lpad", Vector(str, len, pad))

    case ProtoExpr.Rpad(str, len, pad) =>
      scalarFunction("rpad", Vector(str, len, pad))

    case ProtoExpr.StringSplit(str, delimiter, limit) =>
      limit match
        case Some(l) => scalarFunction("split", Vector(str, delimiter, l))
        case None    => scalarFunction("split", Vector(str, delimiter))

    case ProtoExpr.Reverse(child) =>
      scalarFunction("reverse", Vector(child))

    case ProtoExpr.StringRepeat(str, times) =>
      scalarFunction("repeat", Vector(str, times))

    // ========== Aggregates ==========
    case ProtoExpr.Count(child, distinct) =>
      // TODO: Substrait AggregateFunction with distinct flag
      throw UnsupportedSubstraitFeatureException("Aggregate functions not yet implemented")

    case ProtoExpr.Sum(child) =>
      throw UnsupportedSubstraitFeatureException("Aggregate functions not yet implemented")

    case ProtoExpr.Avg(child) =>
      throw UnsupportedSubstraitFeatureException("Aggregate functions not yet implemented")

    case ProtoExpr.Min(child) =>
      throw UnsupportedSubstraitFeatureException("Aggregate functions not yet implemented")

    case ProtoExpr.Max(child) =>
      throw UnsupportedSubstraitFeatureException("Aggregate functions not yet implemented")

    // ========== Control Flow ==========
    case ProtoExpr.CaseWhen(branches, elseValue) =>
      // Substrait has if_then_else which can be nested
      throw UnsupportedSubstraitFeatureException("CaseWhen not yet implemented")

    case ProtoExpr.If(predicate, trueValue, falseValue) =>
      scalarFunction("if", Vector(predicate, trueValue, falseValue))

    case ProtoExpr.In(value, list) =>
      // Substrait has "in" function
      scalarFunction("in", value +: list)

    // ========== Pattern Matching ==========
    case ProtoExpr.Like(value, pattern, escape) =>
      escape match
        case Some(e) => scalarFunction("like", Vector(value, pattern, e))
        case None    => scalarFunction("like", Vector(value, pattern))

    // ========== Cast and Alias ==========
    case ProtoExpr.Cast(child, targetType) =>
      val substraitType = SubstraitTypeConverter.toSubstrait(targetType)
      val childExpr = toSubstrait(child)
      // Use default failure behavior (THROW_EXCEPTION)
      ExpressionCreator.cast(
        substraitType,
        childExpr,
        io.substrait.expression.Expression.FailureBehavior.THROW_EXCEPTION
      )

    case ProtoExpr.Alias(child, name) =>
      // Substrait doesn't have Alias at expression level (handled at plan level)
      // Just return the child expression
      toSubstrait(child)

    // ========== Subqueries ==========
    case ProtoExpr.ScalarSubquery(plan) =>
      throw UnsupportedSubstraitFeatureException("Subqueries not yet implemented")

    case ProtoExpr.Exists(plan) =>
      throw UnsupportedSubstraitFeatureException("Subqueries not yet implemented")

    case ProtoExpr.InSubquery(value, plan) =>
      throw UnsupportedSubstraitFeatureException("Subqueries not yet implemented")

    // ========== Window Functions ==========
    case ProtoExpr.RowNumber() =>
      throw UnsupportedSubstraitFeatureException("Window functions not yet implemented")

    case ProtoExpr.Rank() =>
      throw UnsupportedSubstraitFeatureException("Window functions not yet implemented")

    case ProtoExpr.DenseRank() =>
      throw UnsupportedSubstraitFeatureException("Window functions not yet implemented")

    case ProtoExpr.Ntile(n) =>
      throw UnsupportedSubstraitFeatureException("Window functions not yet implemented")

    case ProtoExpr.Lead(input, offset, default) =>
      throw UnsupportedSubstraitFeatureException("Window functions not yet implemented")

    case ProtoExpr.Lag(input, offset, default) =>
      throw UnsupportedSubstraitFeatureException("Window functions not yet implemented")

    case ProtoExpr.FirstValue(input, ignoreNulls) =>
      throw UnsupportedSubstraitFeatureException("Window functions not yet implemented")

    case ProtoExpr.LastValue(input, ignoreNulls) =>
      throw UnsupportedSubstraitFeatureException("Window functions not yet implemented")

    case ProtoExpr.NthValue(input, n) =>
      throw UnsupportedSubstraitFeatureException("Window functions not yet implemented")

    case ProtoExpr.WindowExpr(function, partitionSpec, orderSpec, frameSpec) =>
      throw UnsupportedSubstraitFeatureException("Window functions not yet implemented")

    // ========== Date/Time Functions ==========
    case ProtoExpr.CurrentDate() =>
      scalarFunction("current_date", Vector.empty)

    case ProtoExpr.CurrentTimestamp() =>
      scalarFunction("current_timestamp", Vector.empty)

    case ProtoExpr.DateAdd(start, days) =>
      scalarFunction("add_days", Vector(start, days))

    case ProtoExpr.DateSub(start, days) =>
      scalarFunction("subtract_days", Vector(start, days))

    case ProtoExpr.DateDiff(end, start) =>
      scalarFunction("date_diff", Vector(end, start))

    case ProtoExpr.Extract(field, source) =>
      val fieldName = field match
        case DateTimeField.Year        => "year"
        case DateTimeField.Month       => "month"
        case DateTimeField.Day         => "day"
        case DateTimeField.Hour        => "hour"
        case DateTimeField.Minute      => "minute"
        case DateTimeField.Second      => "second"
        case DateTimeField.Quarter     => "quarter"
        case DateTimeField.Week        => "week"
        case DateTimeField.DayOfWeek   => "day_of_week"
        case DateTimeField.DayOfYear   => "day_of_year"
        case DateTimeField.Microsecond => "microsecond"
        case DateTimeField.Millisecond => "millisecond"
      scalarFunction(s"extract_$fieldName", Vector(source))

    case ProtoExpr.DateTrunc(field, timestamp) =>
      val fieldName = field match
        case DateTimeField.Year        => "year"
        case DateTimeField.Month       => "month"
        case DateTimeField.Day         => "day"
        case DateTimeField.Hour        => "hour"
        case DateTimeField.Minute      => "minute"
        case DateTimeField.Second      => "second"
        case DateTimeField.Quarter     => "quarter"
        case DateTimeField.Week        => "week"
        case DateTimeField.DayOfWeek   => "day_of_week"
        case DateTimeField.DayOfYear   => "day_of_year"
        case DateTimeField.Microsecond => "microsecond"
        case DateTimeField.Millisecond => "millisecond"
      scalarFunction(s"date_trunc_$fieldName", Vector(timestamp))

    case ProtoExpr.ToDate(str, format) =>
      format match
        case Some(f) => scalarFunction("to_date", Vector(str, f))
        case None    => scalarFunction("to_date", Vector(str))

    case ProtoExpr.ToTimestamp(str, format) =>
      format match
        case Some(f) => scalarFunction("to_timestamp", Vector(str, f))
        case None    => scalarFunction("to_timestamp", Vector(str))

    case ProtoExpr.Year(child) =>
      scalarFunction("extract_year", Vector(child))

    case ProtoExpr.Month(child) =>
      scalarFunction("extract_month", Vector(child))

    case ProtoExpr.DayOfMonth(child) =>
      scalarFunction("extract_day", Vector(child))

    case ProtoExpr.Hour(child) =>
      scalarFunction("extract_hour", Vector(child))

    case ProtoExpr.Minute(child) =>
      scalarFunction("extract_minute", Vector(child))

    case ProtoExpr.Second(child) =>
      scalarFunction("extract_second", Vector(child))

    // ========== Grouping ==========
    case ProtoExpr.Grouping(columns) =>
      throw UnsupportedSubstraitFeatureException("Grouping function not supported in Substrait")

    // ========== Generator Functions ==========
    case ProtoExpr.Explode(child) =>
      throw UnsupportedSubstraitFeatureException("Generator functions not supported in Substrait")

    case ProtoExpr.PosExplode(child) =>
      throw UnsupportedSubstraitFeatureException("Generator functions not supported in Substrait")

    case ProtoExpr.Inline(child) =>
      throw UnsupportedSubstraitFeatureException("Generator functions not supported in Substrait")

    case ProtoExpr.Stack(numRows, children) =>
      throw UnsupportedSubstraitFeatureException("Generator functions not supported in Substrait")

    // ========== Opaque Function Call ==========
    case ProtoExpr.OpaqueCall(functionName, arguments, returnType, deterministic) =>
      // Map to Substrait scalar function
      scalarFunction(functionName, arguments)

  end toSubstrait

  // ========== Helper Methods ==========

  /** Convert a ProtoExpr literal to a Substrait Expression.Literal. */
  private def literalToSubstrait(value: LiteralValue): Expression = value match
    case LiteralValue.NullValue(dataType) =>
      // Create a null literal with the appropriate type
      val substraitType = SubstraitTypeConverter.toSubstrait(dataType)
      // Substrait uses typed null literals - use appropriate creator based on type
      throw UnsupportedSubstraitFeatureException("Null literals not yet implemented")

    case LiteralValue.BooleanValue(v) =>
      ExpressionCreator.bool(false, v)

    case LiteralValue.ByteValue(v) =>
      ExpressionCreator.i8(false, v.toInt)

    case LiteralValue.ShortValue(v) =>
      ExpressionCreator.i16(false, v.toInt)

    case LiteralValue.IntValue(v) =>
      ExpressionCreator.i32(false, v)

    case LiteralValue.LongValue(v) =>
      ExpressionCreator.i64(false, v)

    case LiteralValue.FloatValue(v) =>
      ExpressionCreator.fp32(false, v)

    case LiteralValue.DoubleValue(v) =>
      ExpressionCreator.fp64(false, v)

    case LiteralValue.StringValue(v) =>
      ExpressionCreator.string(false, v)

    case LiteralValue.BinaryValue(v) =>
      ExpressionCreator.binary(false, v.toArray)

    case LiteralValue.DateValue(epochDays) =>
      ExpressionCreator.date(false, epochDays)

    case LiteralValue.TimestampValue(epochMicros) =>
      // Suppress deprecation warning - this is the only way to create timestamp from micros
      ExpressionCreator.timestamp(false, epochMicros): @annotation.nowarn("msg=deprecated")

    case LiteralValue.DecimalValue(value) =>
      // Substrait DecimalLiteral requires precision and scale
      // Get from BigDecimal
      val precision = value.precision
      val scale = value.scale
      ExpressionCreator.decimal(false, value.bigDecimal, precision, scale)

    case LiteralValue.TimeValue(microsSinceMidnight) =>
      throw UnsupportedSubstraitFeatureException("Time literals not yet implemented")

    case LiteralValue.CalendarIntervalValue(months, days, microseconds) =>
      throw UnsupportedSubstraitFeatureException("CalendarInterval not supported in Substrait")

  /** Create a Substrait scalar function call. Converts ProtoExpr arguments to Substrait. */
  private def scalarFunction(name: String, args: Vector[ProtoExpr]): Expression =
    val substraitArgs = args.map(toSubstrait)
    scalarFunctionDirect(name, substraitArgs)

  /** Create a Substrait scalar function call from already-converted Expression arguments. */
  private def scalarFunctionDirect(name: String, args: Vector[Expression]): Expression =
    // Infer output type based on function name and arguments
    val outputType = inferOutputType(name, args)
    functionRegistry.createScalarFunction(name, args, outputType)

  /** Infer the output type of a function based on its name and arguments.
    *
    * This is a simple heuristic-based approach. For more complex type inference, we would need to
    * look up the function's return type from the Substrait extension.
    */
  private def inferOutputType(name: String, args: Vector[Expression]): io.substrait.`type`.Type =
    import io.substrait.`type`.TypeCreator

    name match
      // Comparison operators always return boolean
      case "equal" | "not_equal" | "lt" | "lte" | "gt" | "gte" =>
        TypeCreator.NULLABLE.BOOLEAN

      // Logical operators return boolean
      case "and" | "or" | "not" =>
        TypeCreator.NULLABLE.BOOLEAN

      // Null checks return boolean
      case "is_null" | "is_not_null" =>
        TypeCreator.NULLABLE.BOOLEAN

      // Arithmetic operators return same type as first argument
      case "add" | "subtract" | "multiply" | "divide" =>
        if args.nonEmpty then args.head.getType
        else TypeCreator.NULLABLE.I32 // default

      // Math functions typically return the same type as input
      case "abs" | "ceil" | "floor" | "sqrt" | "cbrt" | "sign" | "exp" =>
        if args.nonEmpty then args.head.getType
        else TypeCreator.NULLABLE.FP64

      // Round/trunc return same type as first arg
      case "round" | "trunc" =>
        if args.nonEmpty then args.head.getType
        else TypeCreator.NULLABLE.FP64

      // Power/log return double
      case "power" | "ln" | "log" =>
        TypeCreator.NULLABLE.FP64

      // Modulus returns same type as first arg
      case "modulus" =>
        if args.nonEmpty then args.head.getType
        else TypeCreator.NULLABLE.I32

      // String functions return string
      case "concat" | "substring" | "upper" | "lower" | "trim" | "ltrim" | "rtrim" | "replace" |
          "lpad" | "rpad" | "reverse" | "repeat" =>
        TypeCreator.NULLABLE.STRING

      // String length returns integer
      case "char_length" | "strpos" =>
        TypeCreator.NULLABLE.I32

      // String split returns array of strings
      case "split" =>
        TypeCreator.NULLABLE.list(TypeCreator.NULLABLE.STRING)

      // Coalesce returns type of first argument
      case "coalesce" =>
        if args.nonEmpty then args.head.getType
        else TypeCreator.NULLABLE.I32

      // NullIf returns type of first argument
      case "nullif" =>
        if args.nonEmpty then args.head.getType
        else TypeCreator.NULLABLE.I32

      // If returns type of second argument (true branch)
      case "if" =>
        if args.size >= 2 then args(1).getType
        else TypeCreator.NULLABLE.I32

      // In returns boolean
      case "in" =>
        TypeCreator.NULLABLE.BOOLEAN

      // Like returns boolean
      case "like" =>
        TypeCreator.NULLABLE.BOOLEAN

      // Date/time functions
      case "current_date" =>
        TypeCreator.NULLABLE.DATE

      case "current_timestamp" =>
        TypeCreator.NULLABLE.TIMESTAMP

      case "add_days" | "subtract_days" =>
        TypeCreator.NULLABLE.DATE

      case "date_diff" =>
        TypeCreator.NULLABLE.I32

      case "to_date" =>
        TypeCreator.NULLABLE.DATE

      case "to_timestamp" =>
        TypeCreator.NULLABLE.TIMESTAMP

      // Extract functions return integer
      case n if n.startsWith("extract_") =>
        TypeCreator.NULLABLE.I32

      // Date trunc returns timestamp
      case n if n.startsWith("date_trunc_") =>
        TypeCreator.NULLABLE.TIMESTAMP

      // Default: return first argument type or i32
      case _ =>
        if args.nonEmpty then args.head.getType
        else TypeCreator.NULLABLE.I32

end SubstraitExprConverter
