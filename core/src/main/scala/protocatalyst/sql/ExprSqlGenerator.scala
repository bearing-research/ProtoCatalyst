package protocatalyst.sql

import protocatalyst.expr._
import protocatalyst.plan.{NullOrdering, SortDirection, SortOrder}
import protocatalyst.types.LiteralValue

/** Converts [[ProtoExpr]] to SQL expression fragments (ANSI SQL / DataFusion compatible).
  *
  * Produces SQL expressions suitable for SELECT, WHERE, GROUP BY, ORDER BY clauses.
  */
object ExprSqlGenerator:

  /** Generate SQL expression from ProtoExpr.
    *
    * Examples:
    *   - `ProtoExpr.lit(42)` → `"42"`
    *   - `ProtoExpr.ColumnRef("name", None, ...)` → `"name"`
    *   - `ProtoExpr.Add(lit(1), lit(2))` → `"1 + 2"`
    */
  def generate(expr: ProtoExpr): String = expr match
    // ========== Leaf nodes ==========
    case ProtoExpr.Literal(value) => generateLiteral(value)

    case ProtoExpr.ColumnRef(name, Some(qualifier), _, _) => s"$qualifier.$name"
    case ProtoExpr.ColumnRef(name, None, _, _)            => name

    case ProtoExpr.BoundRef(index, _, _) =>
      // Positional references - DataFusion supports $1, $2, etc. for prepared statements
      s"$$${index + 1}" // Convert 0-based to 1-based

    // ========== Comparison ==========
    case ProtoExpr.Eq(left, right)    => binary(left, "=", right)
    case ProtoExpr.NotEq(left, right) => binary(left, "<>", right)
    case ProtoExpr.Lt(left, right)    => binary(left, "<", right)
    case ProtoExpr.LtEq(left, right)  => binary(left, "<=", right)
    case ProtoExpr.Gt(left, right)    => binary(left, ">", right)
    case ProtoExpr.GtEq(left, right)  => binary(left, ">=", right)

    // ========== Logical ==========
    case ProtoExpr.And(children) =>
      if children.isEmpty then "TRUE"
      else children.map(c => s"(${generate(c)})").mkString(" AND ")

    case ProtoExpr.Or(children) =>
      if children.isEmpty then "FALSE"
      else children.map(c => s"(${generate(c)})").mkString(" OR ")

    case ProtoExpr.Not(child) => s"NOT (${generate(child)})"

    // ========== Null handling ==========
    case ProtoExpr.IsNull(child)    => s"${generate(child)} IS NULL"
    case ProtoExpr.IsNotNull(child) => s"${generate(child)} IS NOT NULL"

    case ProtoExpr.Coalesce(children) =>
      val args = children.map(generate).mkString(", ")
      s"COALESCE($args)"

    case ProtoExpr.NullIf(left, right) =>
      s"NULLIF(${generate(left)}, ${generate(right)})"

    // ========== Arithmetic ==========
    case ProtoExpr.Add(left, right)      => binary(left, "+", right)
    case ProtoExpr.Subtract(left, right) => binary(left, "-", right)
    case ProtoExpr.Multiply(left, right) => binary(left, "*", right)
    case ProtoExpr.Divide(left, right)   => binary(left, "/", right)

    // ========== Math functions ==========
    case ProtoExpr.Abs(child)   => s"ABS(${generate(child)})"
    case ProtoExpr.Ceil(child)  => s"CEIL(${generate(child)})"
    case ProtoExpr.Floor(child) => s"FLOOR(${generate(child)})"

    case ProtoExpr.Round(child, scale) =>
      s"ROUND(${generate(child)}, ${generate(scale)})"

    case ProtoExpr.Truncate(child, scale) =>
      s"TRUNC(${generate(child)}, ${generate(scale)})"

    case ProtoExpr.Sqrt(child) => s"SQRT(${generate(child)})"
    case ProtoExpr.Cbrt(child) => s"CBRT(${generate(child)})"

    case ProtoExpr.Pow(left, right) =>
      s"POWER(${generate(left)}, ${generate(right)})"

    case ProtoExpr.Pmod(left, right) =>
      s"PMOD(${generate(left)}, ${generate(right)})"

    case ProtoExpr.Sign(child) => s"SIGN(${generate(child)})"

    case ProtoExpr.Log(child, Some(base)) =>
      s"LOG(${generate(base)}, ${generate(child)})"
    case ProtoExpr.Log(child, None) =>
      s"LN(${generate(child)})"

    case ProtoExpr.Exp(child) => s"EXP(${generate(child)})"

    // ========== String functions ==========
    case ProtoExpr.Concat(children) =>
      val args = children.map(generate).mkString(", ")
      s"CONCAT($args)"

    case ProtoExpr.Substring(str, pos, len) =>
      s"SUBSTRING(${generate(str)}, ${generate(pos)}, ${generate(len)})"

    case ProtoExpr.Upper(child) => s"UPPER(${generate(child)})"
    case ProtoExpr.Lower(child) => s"LOWER(${generate(child)})"

    case ProtoExpr.Trim(child, Some(trimStr), trimType) =>
      val trimTypeStr = trimType match
        case TrimType.Both     => "BOTH"
        case TrimType.Leading  => "LEADING"
        case TrimType.Trailing => "TRAILING"
      s"TRIM($trimTypeStr ${generate(trimStr)} FROM ${generate(child)})"

    case ProtoExpr.Trim(child, None, TrimType.Both) =>
      s"TRIM(${generate(child)})"
    case ProtoExpr.Trim(child, None, TrimType.Leading) =>
      s"LTRIM(${generate(child)})"
    case ProtoExpr.Trim(child, None, TrimType.Trailing) =>
      s"RTRIM(${generate(child)})"

    case ProtoExpr.Length(child) => s"LENGTH(${generate(child)})"

    case ProtoExpr.Replace(str, search, replace) =>
      s"REPLACE(${generate(str)}, ${generate(search)}, ${generate(replace)})"

    case ProtoExpr.StringLocate(substr, str, Some(start)) =>
      s"LOCATE(${generate(substr)}, ${generate(str)}, ${generate(start)})"
    case ProtoExpr.StringLocate(substr, str, None) =>
      s"LOCATE(${generate(substr)}, ${generate(str)})"

    case ProtoExpr.Lpad(str, len, pad) =>
      s"LPAD(${generate(str)}, ${generate(len)}, ${generate(pad)})"

    case ProtoExpr.Rpad(str, len, pad) =>
      s"RPAD(${generate(str)}, ${generate(len)}, ${generate(pad)})"

    case ProtoExpr.StringSplit(str, delimiter, Some(limit)) =>
      s"SPLIT(${generate(str)}, ${generate(delimiter)}, ${generate(limit)})"
    case ProtoExpr.StringSplit(str, delimiter, None) =>
      s"SPLIT(${generate(str)}, ${generate(delimiter)})"

    case ProtoExpr.Reverse(child) => s"REVERSE(${generate(child)})"

    case ProtoExpr.StringRepeat(str, times) =>
      s"REPEAT(${generate(str)}, ${generate(times)})"

    // ========== Aggregates ==========
    case ProtoExpr.Count(child, distinct) =>
      if distinct then s"COUNT(DISTINCT ${generate(child)})"
      else s"COUNT(${generate(child)})"

    case ProtoExpr.Sum(child) => s"SUM(${generate(child)})"
    case ProtoExpr.Avg(child) => s"AVG(${generate(child)})"
    case ProtoExpr.Min(child) => s"MIN(${generate(child)})"
    case ProtoExpr.Max(child) => s"MAX(${generate(child)})"

    // ========== Control flow ==========
    case ProtoExpr.CaseWhen(branches, elseValue) =>
      val whenClauses = branches
        .map { case (cond, value) =>
          s"WHEN ${generate(cond)} THEN ${generate(value)}"
        }
        .mkString(" ")
      val elseClause = elseValue.map(v => s" ELSE ${generate(v)}").getOrElse("")
      s"CASE $whenClauses$elseClause END"

    case ProtoExpr.If(predicate, trueValue, falseValue) =>
      s"CASE WHEN ${generate(predicate)} THEN ${generate(trueValue)} ELSE ${generate(falseValue)} END"

    case ProtoExpr.In(value, list) =>
      val values = list.map(generate).mkString(", ")
      s"${generate(value)} IN ($values)"

    // ========== Pattern matching ==========
    case ProtoExpr.Like(value, pattern, Some(escape)) =>
      s"${generate(value)} LIKE ${generate(pattern)} ESCAPE ${generate(escape)}"
    case ProtoExpr.Like(value, pattern, None) =>
      s"${generate(value)} LIKE ${generate(pattern)}"

    // ========== Cast and alias ==========
    case ProtoExpr.Cast(child, targetType) =>
      s"CAST(${generate(child)} AS ${TypeSqlGenerator.generate(targetType)})"

    case ProtoExpr.Alias(child, name) =>
      s"${generate(child)} AS $name"

    // ========== Subquery expressions ==========
    case ProtoExpr.ScalarSubquery(plan) =>
      // Recursive call to SqlGenerator (will be available once we create it)
      s"(${SqlGenerator.generate(plan)})"

    case ProtoExpr.Exists(plan) =>
      s"EXISTS (${SqlGenerator.generate(plan)})"

    case ProtoExpr.InSubquery(value, plan) =>
      s"${generate(value)} IN (${SqlGenerator.generate(plan)})"

    // ========== Window functions ==========
    case ProtoExpr.RowNumber() => "ROW_NUMBER()"
    case ProtoExpr.Rank()      => "RANK()"
    case ProtoExpr.DenseRank() => "DENSE_RANK()"
    case ProtoExpr.Ntile(n)    => s"NTILE(${generate(n)})"

    case ProtoExpr.Lead(input, offset, default) =>
      val defaultClause = default.map(d => s", ${generate(d)}").getOrElse("")
      s"LEAD(${generate(input)}, ${generate(offset)}$defaultClause)"

    case ProtoExpr.Lag(input, offset, default) =>
      val defaultClause = default.map(d => s", ${generate(d)}").getOrElse("")
      s"LAG(${generate(input)}, ${generate(offset)}$defaultClause)"

    case ProtoExpr.FirstValue(input, ignoreNulls) =>
      val ignoreClause = if ignoreNulls then " IGNORE NULLS" else ""
      s"FIRST_VALUE(${generate(input)})$ignoreClause"

    case ProtoExpr.LastValue(input, ignoreNulls) =>
      val ignoreClause = if ignoreNulls then " IGNORE NULLS" else ""
      s"LAST_VALUE(${generate(input)})$ignoreClause"

    case ProtoExpr.NthValue(input, n) =>
      s"NTH_VALUE(${generate(input)}, ${generate(n)})"

    case ProtoExpr.WindowExpr(function, partitionSpec, orderSpec, frameSpec) =>
      val funcStr = generate(function)
      val partitionClause =
        if partitionSpec.nonEmpty then
          s" PARTITION BY ${partitionSpec.map(generate).mkString(", ")}"
        else ""
      val orderClause =
        if orderSpec.nonEmpty then s" ORDER BY ${orderSpec.map(generateSortOrder).mkString(", ")}"
        else ""
      val frameClause = frameSpec.map(generateWindowFrame).getOrElse("")
      s"$funcStr OVER ($partitionClause$orderClause$frameClause)"

    // ========== Date/Time functions ==========
    case ProtoExpr.CurrentDate()      => "CURRENT_DATE"
    case ProtoExpr.CurrentTimestamp() => "CURRENT_TIMESTAMP"

    case ProtoExpr.DateAdd(start, days) =>
      s"DATE_ADD(${generate(start)}, ${generate(days)})"

    case ProtoExpr.DateSub(start, days) =>
      s"DATE_SUB(${generate(start)}, ${generate(days)})"

    case ProtoExpr.DateDiff(end, start) =>
      s"DATEDIFF(${generate(end)}, ${generate(start)})"

    case ProtoExpr.Extract(field, source) =>
      s"EXTRACT(${dateTimeFieldToSql(field)} FROM ${generate(source)})"

    case ProtoExpr.DateTrunc(field, timestamp) =>
      s"DATE_TRUNC('${dateTimeFieldToSql(field)}', ${generate(timestamp)})"

    case ProtoExpr.ToDate(str, Some(format)) =>
      s"TO_DATE(${generate(str)}, ${generate(format)})"
    case ProtoExpr.ToDate(str, None) =>
      s"TO_DATE(${generate(str)})"

    case ProtoExpr.ToTimestamp(str, Some(format)) =>
      s"TO_TIMESTAMP(${generate(str)}, ${generate(format)})"
    case ProtoExpr.ToTimestamp(str, None) =>
      s"TO_TIMESTAMP(${generate(str)})"

    case ProtoExpr.Year(child)       => s"YEAR(${generate(child)})"
    case ProtoExpr.Month(child)      => s"MONTH(${generate(child)})"
    case ProtoExpr.DayOfMonth(child) => s"DAY(${generate(child)})"
    case ProtoExpr.Hour(child)       => s"HOUR(${generate(child)})"
    case ProtoExpr.Minute(child)     => s"MINUTE(${generate(child)})"
    case ProtoExpr.Second(child)     => s"SECOND(${generate(child)})"

    // ========== Grouping function ==========
    case ProtoExpr.Grouping(columns) =>
      val args = columns.map(generate).mkString(", ")
      s"GROUPING($args)"

    // ========== Generator functions ==========
    case ProtoExpr.Explode(child) =>
      s"EXPLODE(${generate(child)})"

    case ProtoExpr.PosExplode(child) =>
      s"POSEXPLODE(${generate(child)})"

    case ProtoExpr.Inline(child) =>
      s"INLINE(${generate(child)})"

    case ProtoExpr.Stack(numRows, children) =>
      val args = (generate(numRows) +: children.map(generate)).mkString(", ")
      s"STACK($args)"

    // ========== Opaque function call ==========
    case ProtoExpr.OpaqueCall(functionName, arguments, _, _) =>
      val args = arguments.map(generate).mkString(", ")
      s"$functionName($args)"

  end generate

  // ========== Helper methods ==========

  private def binary(left: ProtoExpr, op: String, right: ProtoExpr): String =
    s"${generate(left)} $op ${generate(right)}"

  private def generateLiteral(value: LiteralValue): String = value match
    case LiteralValue.NullValue(_) => "NULL"

    case LiteralValue.BooleanValue(true)  => "TRUE"
    case LiteralValue.BooleanValue(false) => "FALSE"

    case LiteralValue.ByteValue(v)  => v.toString
    case LiteralValue.ShortValue(v) => v.toString
    case LiteralValue.IntValue(v)   => v.toString
    case LiteralValue.LongValue(v)  => v.toString

    case LiteralValue.FloatValue(v) =>
      if v.isNaN then "'NaN'::REAL"
      else if v.isPosInfinity then "'Infinity'::REAL"
      else if v.isNegInfinity then "'-Infinity'::REAL"
      else v.toString

    case LiteralValue.DoubleValue(v) =>
      if v.isNaN then "'NaN'::DOUBLE"
      else if v.isPosInfinity then "'Infinity'::DOUBLE"
      else if v.isNegInfinity then "'-Infinity'::DOUBLE"
      else v.toString

    case LiteralValue.StringValue(v) =>
      // Escape single quotes by doubling them
      s"'${v.replace("'", "''")}'"

    case LiteralValue.BinaryValue(bytes) =>
      // Hex string format: X'...'
      s"X'${bytes.map(b => f"$b%02x").mkString}'"

    case LiteralValue.DecimalValue(v) =>
      val scale = v.scale.max(0)
      val precision = v.precision.max(scale + 1)
      s"${v.toString}::DECIMAL($precision, $scale)"

    case LiteralValue.DateValue(epochDays) =>
      // Convert epoch days to DATE 'YYYY-MM-DD' format
      val localDate = java.time.LocalDate.ofEpochDay(epochDays.toLong)
      s"DATE '$localDate'"

    case LiteralValue.TimestampValue(epochMicros) =>
      // Convert epoch micros to TIMESTAMP 'YYYY-MM-DD HH:MM:SS.ssssss' format
      val instant = java.time.Instant.ofEpochSecond(
        epochMicros / 1_000_000,
        (epochMicros % 1_000_000) * 1000
      )
      val localDateTime = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC)
      s"TIMESTAMP '$localDateTime'"

    case LiteralValue.TimeValue(microsSinceMidnight) =>
      // TIME type - not widely supported in SQL, throw for now
      throw UnsupportedSqlFeatureException("TimeValue literals not supported in SQL generation")

    case LiteralValue.CalendarIntervalValue(months, days, microseconds) =>
      // INTERVAL type - complex representation, throw for now
      throw UnsupportedSqlFeatureException(
        "CalendarIntervalValue literals not supported in SQL generation"
      )

  def generateSortOrder(order: SortOrder): String =
    val exprStr = generate(order.child)
    val dirStr = order.direction match
      case SortDirection.Ascending  => "ASC"
      case SortDirection.Descending => "DESC"
    val nullsStr = order.nullOrdering match
      case NullOrdering.NullsFirst => " NULLS FIRST"
      case NullOrdering.NullsLast  => " NULLS LAST"
    s"$exprStr $dirStr$nullsStr"

  private def generateWindowFrame(frame: WindowFrame): String =
    val frameTypeStr = frame.frameType match
      case FrameType.Rows  => "ROWS"
      case FrameType.Range => "RANGE"
    val lowerStr = frameBoundToSql(frame.lower)
    val upperStr = frameBoundToSql(frame.upper)
    s" $frameTypeStr BETWEEN $lowerStr AND $upperStr"

  private def frameBoundToSql(bound: FrameBound): String = bound match
    case FrameBound.UnboundedPreceding => "UNBOUNDED PRECEDING"
    case FrameBound.Preceding(offset)  => s"${offset} PRECEDING"
    case FrameBound.CurrentRow         => "CURRENT ROW"
    case FrameBound.Following(offset)  => s"${offset} FOLLOWING"
    case FrameBound.UnboundedFollowing => "UNBOUNDED FOLLOWING"

  private def dateTimeFieldToSql(field: DateTimeField): String = field match
    case DateTimeField.Year        => "YEAR"
    case DateTimeField.Month       => "MONTH"
    case DateTimeField.Day         => "DAY"
    case DateTimeField.Hour        => "HOUR"
    case DateTimeField.Minute      => "MINUTE"
    case DateTimeField.Second      => "SECOND"
    case DateTimeField.Week        => "WEEK"
    case DateTimeField.Quarter     => "QUARTER"
    case DateTimeField.DayOfWeek   => "DAYOFWEEK"
    case DateTimeField.DayOfYear   => "DAYOFYEAR"
    case DateTimeField.Microsecond => "MICROSECOND"
    case DateTimeField.Millisecond => "MILLISECOND"

end ExprSqlGenerator
