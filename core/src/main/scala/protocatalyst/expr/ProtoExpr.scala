package protocatalyst.expr

import java.io.Serializable

import protocatalyst.types._

/** Compile-time expression representation. */
enum ProtoExpr extends Serializable:
  // Leaf nodes
  case Literal(value: LiteralValue)
  case ColumnRef(
      name: String,
      qualifier: Option[String],
      resolvedType: ProtoType,
      nullable: Boolean
  )
  case BoundRef(index: Int, dataType: ProtoType, nullable: Boolean)

  // Comparison
  case Eq(left: ProtoExpr, right: ProtoExpr)
  case NotEq(left: ProtoExpr, right: ProtoExpr)
  case Lt(left: ProtoExpr, right: ProtoExpr)
  case LtEq(left: ProtoExpr, right: ProtoExpr)
  case Gt(left: ProtoExpr, right: ProtoExpr)
  case GtEq(left: ProtoExpr, right: ProtoExpr)

  // Logical
  case And(children: Vector[ProtoExpr])
  case Or(children: Vector[ProtoExpr])
  case Not(child: ProtoExpr)

  // Null handling
  case IsNull(child: ProtoExpr)
  case IsNotNull(child: ProtoExpr)
  case Coalesce(children: Vector[ProtoExpr])
  case NullIf(left: ProtoExpr, right: ProtoExpr)

  // Arithmetic
  case Add(left: ProtoExpr, right: ProtoExpr)
  case Subtract(left: ProtoExpr, right: ProtoExpr)
  case Multiply(left: ProtoExpr, right: ProtoExpr)
  case Divide(left: ProtoExpr, right: ProtoExpr)

  // Math functions
  case Abs(child: ProtoExpr)
  case Ceil(child: ProtoExpr)
  case Floor(child: ProtoExpr)
  case Round(child: ProtoExpr, scale: ProtoExpr)
  case Truncate(child: ProtoExpr, scale: ProtoExpr)
  case Sqrt(child: ProtoExpr)
  case Cbrt(child: ProtoExpr)
  case Pow(left: ProtoExpr, right: ProtoExpr)
  case Pmod(left: ProtoExpr, right: ProtoExpr)
  case Sign(child: ProtoExpr)
  case Log(child: ProtoExpr, base: Option[ProtoExpr])
  case Exp(child: ProtoExpr)

  // String
  case Concat(children: Vector[ProtoExpr])
  case Substring(str: ProtoExpr, pos: ProtoExpr, len: ProtoExpr)
  case Upper(child: ProtoExpr)
  case Lower(child: ProtoExpr)
  case Trim(child: ProtoExpr, trimStr: Option[ProtoExpr], trimType: TrimType)
  case Length(child: ProtoExpr)
  case Replace(str: ProtoExpr, search: ProtoExpr, replace: ProtoExpr)
  case StringLocate(substr: ProtoExpr, str: ProtoExpr, start: Option[ProtoExpr])
  case Lpad(str: ProtoExpr, len: ProtoExpr, pad: ProtoExpr)
  case Rpad(str: ProtoExpr, len: ProtoExpr, pad: ProtoExpr)
  case StringSplit(str: ProtoExpr, delimiter: ProtoExpr, limit: Option[ProtoExpr])
  case Reverse(child: ProtoExpr)
  case StringRepeat(str: ProtoExpr, times: ProtoExpr)

  // Aggregates
  case Count(child: ProtoExpr, distinct: Boolean)
  case Sum(child: ProtoExpr)
  case Avg(child: ProtoExpr)
  case Min(child: ProtoExpr)
  case Max(child: ProtoExpr)

  // Control flow
  case CaseWhen(branches: Vector[(ProtoExpr, ProtoExpr)], elseValue: Option[ProtoExpr])
  case If(predicate: ProtoExpr, trueValue: ProtoExpr, falseValue: ProtoExpr)
  case In(value: ProtoExpr, list: Vector[ProtoExpr])

  // Pattern matching
  case Like(value: ProtoExpr, pattern: ProtoExpr, escape: Option[ProtoExpr])

  // Cast and alias
  case Cast(child: ProtoExpr, targetType: ProtoType)
  case Alias(child: ProtoExpr, name: String)

  // Subquery expressions
  case ScalarSubquery(plan: protocatalyst.plan.ProtoLogicalPlan)
  case Exists(plan: protocatalyst.plan.ProtoLogicalPlan)
  case InSubquery(value: ProtoExpr, plan: protocatalyst.plan.ProtoLogicalPlan)

  // Window functions
  case RowNumber()
  case Rank()
  case DenseRank()
  case Ntile(n: ProtoExpr)
  case Lead(input: ProtoExpr, offset: ProtoExpr, default: Option[ProtoExpr])
  case Lag(input: ProtoExpr, offset: ProtoExpr, default: Option[ProtoExpr])
  case FirstValue(input: ProtoExpr, ignoreNulls: Boolean)
  case LastValue(input: ProtoExpr, ignoreNulls: Boolean)
  case NthValue(input: ProtoExpr, n: ProtoExpr)

  // Window specification wrapper - wraps an expression with its window spec
  case WindowExpr(
      function: ProtoExpr,
      partitionSpec: Vector[ProtoExpr],
      orderSpec: Vector[protocatalyst.plan.SortOrder],
      frameSpec: Option[WindowFrame]
  )

  // Date/Time functions
  case CurrentDate()
  case CurrentTimestamp()
  case DateAdd(start: ProtoExpr, days: ProtoExpr)
  case DateSub(start: ProtoExpr, days: ProtoExpr)
  case DateDiff(end: ProtoExpr, start: ProtoExpr)
  case Extract(field: DateTimeField, source: ProtoExpr)
  case DateTrunc(field: DateTimeField, timestamp: ProtoExpr)
  case ToDate(str: ProtoExpr, format: Option[ProtoExpr])
  case ToTimestamp(str: ProtoExpr, format: Option[ProtoExpr])
  case Year(child: ProtoExpr)
  case Month(child: ProtoExpr)
  case DayOfMonth(child: ProtoExpr)
  case Hour(child: ProtoExpr)
  case Minute(child: ProtoExpr)
  case Second(child: ProtoExpr)

  // Grouping function for GROUPING SETS, CUBE, ROLLUP
  // Returns 1 if the column is null due to grouping (super-aggregate row), 0 otherwise
  case Grouping(columns: Vector[ProtoExpr])

  // Generator functions (for LATERAL VIEW)
  /** EXPLODE: produces one row per element in an array or per key-value pair in a map */
  case Explode(child: ProtoExpr)
  /** POSEXPLODE: like EXPLODE but also outputs the position/index */
  case PosExplode(child: ProtoExpr)
  /** INLINE: explodes an array of structs into rows */
  case Inline(child: ProtoExpr)
  /** STACK: separates expressions into n rows */
  case Stack(numRows: ProtoExpr, children: Vector[ProtoExpr])

  // Opaque function call (UDFs and unknown functions)
  case OpaqueCall(
      functionName: String,
      arguments: Vector[ProtoExpr],
      returnType: Option[ProtoType],
      deterministic: Boolean
  )

/** Window frame specification. */
case class WindowFrame(
    frameType: FrameType,
    lower: FrameBound,
    upper: FrameBound
) extends Serializable

enum FrameType extends Serializable:
  case Rows, Range

enum FrameBound extends Serializable:
  case UnboundedPreceding
  case UnboundedFollowing
  case CurrentRow
  case Preceding(n: Long)
  case Following(n: Long)

/** Trim type for TRIM function. */
enum TrimType extends Serializable:
  case Both, Leading, Trailing

/** Date/time field for EXTRACT and DATE_TRUNC. */
enum DateTimeField extends Serializable:
  case Year, Month, Day, Hour, Minute, Second
  case Quarter, Week, DayOfWeek, DayOfYear
  case Microsecond, Millisecond

object ProtoExpr:
  // Convenience constructors for literals
  def lit(value: Boolean): ProtoExpr = Literal(LiteralValue.BooleanValue(value))
  def lit(value: Int): ProtoExpr = Literal(LiteralValue.IntValue(value))
  def lit(value: Long): ProtoExpr = Literal(LiteralValue.LongValue(value))
  def lit(value: Double): ProtoExpr = Literal(LiteralValue.DoubleValue(value))
  def lit(value: String): ProtoExpr = Literal(LiteralValue.StringValue(value))
  def litNull(dataType: ProtoType): ProtoExpr = Literal(LiteralValue.NullValue(dataType))
