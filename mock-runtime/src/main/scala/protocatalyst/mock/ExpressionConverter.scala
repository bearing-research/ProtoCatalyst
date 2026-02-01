package protocatalyst.mock

import protocatalyst.expr._
import protocatalyst.plan.{
  NullOrdering => ProtoNullOrdering,
  SortDirection => ProtoSortDirection,
  SortOrder => ProtoSortOrder
}
import protocatalyst.types._

/** Bidirectional converter between ProtoExpr and MockExpression.
  *
  * This enables testing the conversion layer before real Spark Scala 3 is available. When Spark
  * Scala 3 becomes available, this can be adapted to convert to real Spark Expression types.
  */
object ExpressionConverter:

  // ============================================
  // ProtoExpr → MockExpression
  // ============================================

  def toMock(expr: ProtoExpr): MockExpression =
    import ProtoExpr.*
    import MockExpression as ME

    expr match
      // === Literals ===
      case Literal(lit) => convertLiteral(lit)

      // === Column References ===
      case ColumnRef(name, qualifier, resolvedType, nullable) =>
        qualifier match
          case Some(q) => ME.UnresolvedAttribute(Seq(q, name))
          case None    => ME.UnresolvedAttribute(Seq(name))

      case BoundRef(index, dataType, nullable) =>
        ME.BoundReference(index, TypeConverter.toMock(dataType), nullable)

      // === Comparisons ===
      case Eq(left, right)    => ME.EqualTo(toMock(left), toMock(right))
      case NotEq(left, right) => ME.Not(ME.EqualTo(toMock(left), toMock(right)))
      case Lt(left, right)    => ME.LessThan(toMock(left), toMock(right))
      case LtEq(left, right)  => ME.LessThanOrEqual(toMock(left), toMock(right))
      case Gt(left, right)    => ME.GreaterThan(toMock(left), toMock(right))
      case GtEq(left, right)  => ME.GreaterThanOrEqual(toMock(left), toMock(right))

      // === Logical ===
      case And(children) =>
        children.map(toMock).reduceLeft((l, r) => ME.And(l, r))

      case Or(children) =>
        children.map(toMock).reduceLeft((l, r) => ME.Or(l, r))

      case Not(child) => ME.Not(toMock(child))

      // === Null Handling ===
      case IsNull(child)       => ME.IsNull(toMock(child))
      case IsNotNull(child)    => ME.IsNotNull(toMock(child))
      case Coalesce(children)  => ME.Coalesce(children.map(toMock))
      case NullIf(left, right) => ME.NullIf(toMock(left), toMock(right))

      // === Arithmetic ===
      case Add(left, right)      => ME.Add(toMock(left), toMock(right))
      case Subtract(left, right) => ME.Subtract(toMock(left), toMock(right))
      case Multiply(left, right) => ME.Multiply(toMock(left), toMock(right))
      case Divide(left, right)   => ME.Divide(toMock(left), toMock(right))

      // === Math Functions ===
      case Abs(child)             => ME.Abs(toMock(child))
      case Ceil(child)            => ME.Ceil(toMock(child))
      case Floor(child)           => ME.Floor(toMock(child))
      case Round(child, scale)    => ME.Round(toMock(child), toMock(scale))
      case Truncate(child, scale) => ME.Truncate(toMock(child), toMock(scale))
      case Sqrt(child)            => ME.Sqrt(toMock(child))
      case Cbrt(child)            => ME.Cbrt(toMock(child))
      case Pow(left, right)       => ME.Pow(toMock(left), toMock(right))
      case Pmod(left, right)      => ME.Pmod(toMock(left), toMock(right))
      case Sign(child)            => ME.Sign(toMock(child))
      case Log(child, base)       => ME.Log(toMock(child), base.map(toMock))
      case Exp(child)             => ME.Exp(toMock(child))

      // === String Operations ===
      case Concat(children)               => ME.Concat(children.map(toMock))
      case Substring(str, pos, len)       => ME.Substring(toMock(str), toMock(pos), toMock(len))
      case Upper(child)                   => ME.Upper(toMock(child))
      case Lower(child)                   => ME.Lower(toMock(child))
      case Trim(child, trimStr, trimType) =>
        val mockTrimType = trimType match
          case TrimType.Both     => ME.TrimType.Both
          case TrimType.Leading  => ME.TrimType.Leading
          case TrimType.Trailing => ME.TrimType.Trailing
        ME.Trim(toMock(child), trimStr.map(toMock), mockTrimType)
      case Length(child)                 => ME.Length(toMock(child))
      case Replace(str, search, replace) => ME.Replace(toMock(str), toMock(search), toMock(replace))
      case StringLocate(substr, str, start) =>
        ME.StringLocate(toMock(substr), toMock(str), start.map(toMock))
      case Lpad(str, len, pad)                => ME.Lpad(toMock(str), toMock(len), toMock(pad))
      case Rpad(str, len, pad)                => ME.Rpad(toMock(str), toMock(len), toMock(pad))
      case StringSplit(str, delimiter, limit) =>
        ME.StringSplit(toMock(str), toMock(delimiter), limit.map(toMock))
      case Reverse(child)               => ME.Reverse(toMock(child))
      case StringRepeat(str, times)     => ME.StringRepeat(toMock(str), toMock(times))
      case Like(value, pattern, escape) =>
        val escapeChar = escape.map(e => evaluateLiteralString(e).headOption.getOrElse('\\'))
        ME.Like(toMock(value), toMock(pattern), escapeChar)

      // === Aggregates ===
      case Count(child, distinct) => ME.Count(toMock(child), distinct)
      case Sum(child)             => ME.Sum(toMock(child))
      case Avg(child)             => ME.Avg(toMock(child))
      case Min(child)             => ME.Min(toMock(child))
      case Max(child)             => ME.Max(toMock(child))

      // === Control Flow ===
      case CaseWhen(branches, elseValue) =>
        ME.CaseWhen(
          branches.map((cond, result) => (toMock(cond), toMock(result))),
          elseValue.map(toMock)
        )

      case If(predicate, trueValue, falseValue) =>
        ME.If(toMock(predicate), toMock(trueValue), toMock(falseValue))

      case In(value, list) => ME.In(toMock(value), list.map(toMock))

      // === Cast and Alias ===
      case Cast(child, targetType) => ME.Cast(toMock(child), TypeConverter.toMock(targetType))
      case Alias(child, name)      => ME.Alias(toMock(child), name)

      // === Subqueries ===
      case ScalarSubquery(plan) =>
        val mockPlan = PlanConverter.toMock(plan)
        // Scalar subquery result type comes from the plan's output
        val resultType =
          mockPlan.output.headOption.map(_.dataType).getOrElse(MockDataType.StringType)
        ME.ScalarSubquery(mockPlan, resultType)

      case Exists(plan)            => ME.Exists(PlanConverter.toMock(plan))
      case InSubquery(value, plan) => ME.InSubquery(toMock(value), PlanConverter.toMock(plan))

      // === Window Functions ===
      case RowNumber()                  => ME.RowNumber()
      case Rank()                       => ME.Rank()
      case DenseRank()                  => ME.DenseRank()
      case Ntile(n)                     => ME.Ntile(toMock(n))
      case Lead(input, offset, default) =>
        ME.Lead(toMock(input), toMock(offset), default.map(toMock))
      case Lag(input, offset, default) => ME.Lag(toMock(input), toMock(offset), default.map(toMock))
      case FirstValue(input, ignoreNulls) => ME.FirstValue(toMock(input), ignoreNulls)
      case LastValue(input, ignoreNulls)  => ME.LastValue(toMock(input), ignoreNulls)
      case NthValue(input, n)             => ME.NthValue(toMock(input), toMock(n))

      case WindowExpr(function, partitionSpec, orderSpec, frameSpec) =>
        ME.WindowExpression(
          toMock(function),
          ME.WindowSpecDefinition(
            partitionSpec.map(toMock),
            orderSpec.map(convertSortOrder),
            frameSpec.map(convertWindowFrame)
          )
        )

      // === Opaque Function ===
      case OpaqueCall(name, args, returnType, deterministic) =>
        ME.UnresolvedFunction(name, args.map(toMock))

  private def convertLiteral(lit: LiteralValue): MockExpression.Literal =
    import LiteralValue.*
    import MockDataType.*

    lit match
      case BooleanValue(v)                => MockExpression.Literal(v, BooleanType)
      case ByteValue(v)                   => MockExpression.Literal(v, ByteType)
      case ShortValue(v)                  => MockExpression.Literal(v, ShortType)
      case IntValue(v)                    => MockExpression.Literal(v, IntegerType)
      case LongValue(v)                   => MockExpression.Literal(v, LongType)
      case FloatValue(v)                  => MockExpression.Literal(v, FloatType)
      case DoubleValue(v)                 => MockExpression.Literal(v, DoubleType)
      case StringValue(v)                 => MockExpression.Literal(v, StringType)
      case BinaryValue(v)                 => MockExpression.Literal(v, BinaryType)
      case DecimalValue(v)                => MockExpression.Literal(v, DecimalType(38, 18))
      case DateValue(epochDays)           => MockExpression.Literal(epochDays, DateType)
      case TimestampValue(epochMicros)    => MockExpression.Literal(epochMicros, TimestampType)
      case TimeValue(microsSinceMidnight) =>
        MockExpression.Literal(microsSinceMidnight, TimeType(6))
      case CalendarIntervalValue(months, days, micros) =>
        // Store as a tuple - CalendarInterval would need proper MockDataType support
        MockExpression.Literal((months, days, micros), CalendarIntervalType)
      case NullValue(dt) => MockExpression.Literal(null, TypeConverter.toMock(dt))

  private def convertSortOrder(so: ProtoSortOrder): MockExpression.SortOrder =
    import MockExpression.{SortDirection as MSD, NullOrdering as MNO}
    MockExpression.SortOrder(
      toMock(so.child),
      so.direction match
        case ProtoSortDirection.Ascending  => MSD.Ascending
        case ProtoSortDirection.Descending => MSD.Descending,
      so.nullOrdering match
        case ProtoNullOrdering.NullsFirst => MNO.NullsFirst
        case ProtoNullOrdering.NullsLast  => MNO.NullsLast
    )

  private def convertWindowFrame(frame: WindowFrame): MockExpression.WindowFrame =
    import MockExpression.{FrameType as MFT, FrameBound as MFB}
    MockExpression.WindowFrame(
      frame.frameType match
        case FrameType.Rows  => MFT.RowFrame
        case FrameType.Range => MFT.RangeFrame,
      frame.lower match
        case FrameBound.UnboundedPreceding => MFB.UnboundedPreceding
        case FrameBound.UnboundedFollowing => MFB.UnboundedFollowing
        case FrameBound.CurrentRow         => MFB.CurrentRow
        case FrameBound.Preceding(n)       => MFB.Preceding(n)
        case FrameBound.Following(n)       => MFB.Following(n),
      frame.upper match
        case FrameBound.UnboundedPreceding => MFB.UnboundedPreceding
        case FrameBound.UnboundedFollowing => MFB.UnboundedFollowing
        case FrameBound.CurrentRow         => MFB.CurrentRow
        case FrameBound.Preceding(n)       => MFB.Preceding(n)
        case FrameBound.Following(n)       => MFB.Following(n)
    )

  private def evaluateLiteralString(expr: ProtoExpr): String =
    expr match
      case ProtoExpr.Literal(LiteralValue.StringValue(s)) => s
      case _                                              => ""

  // ============================================
  // MockExpression → ProtoExpr
  // ============================================

  def fromMock(expr: MockExpression): ProtoExpr =
    import MockExpression as ME
    import ProtoExpr as PE

    expr match
      // === Literals ===
      case ME.Literal(value, dataType) => convertMockLiteral(value, dataType)

      // === Column References ===
      case ME.UnresolvedAttribute(nameParts) =>
        nameParts match
          case Seq(name) => PE.ColumnRef(name, None, ProtoType.UnresolvedType("unresolved"), true)
          case Seq(qualifier, name) =>
            PE.ColumnRef(name, Some(qualifier), ProtoType.UnresolvedType("unresolved"), true)
          case parts =>
            PE.ColumnRef(
              parts.last,
              Some(parts.init.mkString(".")),
              ProtoType.UnresolvedType("unresolved"),
              true
            )

      case ME.AttributeReference(name, dataType, nullable, qualifier) =>
        PE.ColumnRef(name, qualifier.headOption, TypeConverter.fromMock(dataType), nullable)

      case ME.BoundReference(ordinal, dataType, nullable) =>
        PE.BoundRef(ordinal, TypeConverter.fromMock(dataType), nullable)

      // === Comparisons ===
      case ME.EqualTo(left, right)            => PE.Eq(fromMock(left), fromMock(right))
      case ME.LessThan(left, right)           => PE.Lt(fromMock(left), fromMock(right))
      case ME.LessThanOrEqual(left, right)    => PE.LtEq(fromMock(left), fromMock(right))
      case ME.GreaterThan(left, right)        => PE.Gt(fromMock(left), fromMock(right))
      case ME.GreaterThanOrEqual(left, right) => PE.GtEq(fromMock(left), fromMock(right))

      // === Logical ===
      case ME.And(left, right) =>
        (fromMock(left), fromMock(right)) match
          case (PE.And(lChildren), PE.And(rChildren)) => PE.And(lChildren ++ rChildren)
          case (PE.And(lChildren), r)                 => PE.And(lChildren :+ r)
          case (l, PE.And(rChildren))                 => PE.And(l +: rChildren)
          case (l, r)                                 => PE.And(Vector(l, r))

      case ME.Or(left, right) =>
        (fromMock(left), fromMock(right)) match
          case (PE.Or(lChildren), PE.Or(rChildren)) => PE.Or(lChildren ++ rChildren)
          case (PE.Or(lChildren), r)                => PE.Or(lChildren :+ r)
          case (l, PE.Or(rChildren))                => PE.Or(l +: rChildren)
          case (l, r)                               => PE.Or(Vector(l, r))

      case ME.Not(ME.EqualTo(left, right)) => PE.NotEq(fromMock(left), fromMock(right))
      case ME.Not(child)                   => PE.Not(fromMock(child))

      // === Null Handling ===
      case ME.IsNull(child)       => PE.IsNull(fromMock(child))
      case ME.IsNotNull(child)    => PE.IsNotNull(fromMock(child))
      case ME.Coalesce(children)  => PE.Coalesce(children.map(fromMock).toVector)
      case ME.NullIf(left, right) => PE.NullIf(fromMock(left), fromMock(right))

      // === Arithmetic ===
      case ME.Add(left, right)      => PE.Add(fromMock(left), fromMock(right))
      case ME.Subtract(left, right) => PE.Subtract(fromMock(left), fromMock(right))
      case ME.Multiply(left, right) => PE.Multiply(fromMock(left), fromMock(right))
      case ME.Divide(left, right)   => PE.Divide(fromMock(left), fromMock(right))

      // === Math Functions ===
      case ME.Abs(child)             => PE.Abs(fromMock(child))
      case ME.Ceil(child)            => PE.Ceil(fromMock(child))
      case ME.Floor(child)           => PE.Floor(fromMock(child))
      case ME.Round(child, scale)    => PE.Round(fromMock(child), fromMock(scale))
      case ME.Truncate(child, scale) => PE.Truncate(fromMock(child), fromMock(scale))
      case ME.Sqrt(child)            => PE.Sqrt(fromMock(child))
      case ME.Cbrt(child)            => PE.Cbrt(fromMock(child))
      case ME.Pow(left, right)       => PE.Pow(fromMock(left), fromMock(right))
      case ME.Pmod(left, right)      => PE.Pmod(fromMock(left), fromMock(right))
      case ME.Sign(child)            => PE.Sign(fromMock(child))
      case ME.Log(child, base)       => PE.Log(fromMock(child), base.map(fromMock))
      case ME.Exp(child)             => PE.Exp(fromMock(child))

      // === String Operations ===
      case ME.Concat(children)         => PE.Concat(children.map(fromMock).toVector)
      case ME.Substring(str, pos, len) => PE.Substring(fromMock(str), fromMock(pos), fromMock(len))
      case ME.Upper(child)             => PE.Upper(fromMock(child))
      case ME.Lower(child)             => PE.Lower(fromMock(child))
      case ME.Trim(child, trimStr, trimType) =>
        val protoTrimType = trimType match
          case ME.TrimType.Both     => TrimType.Both
          case ME.TrimType.Leading  => TrimType.Leading
          case ME.TrimType.Trailing => TrimType.Trailing
        PE.Trim(fromMock(child), trimStr.map(fromMock), protoTrimType)
      case ME.Length(child)                 => PE.Length(fromMock(child))
      case ME.Replace(str, search, replace) =>
        PE.Replace(fromMock(str), fromMock(search), fromMock(replace))
      case ME.StringLocate(substr, str, start) =>
        PE.StringLocate(fromMock(substr), fromMock(str), start.map(fromMock))
      case ME.Lpad(str, len, pad) => PE.Lpad(fromMock(str), fromMock(len), fromMock(pad))
      case ME.Rpad(str, len, pad) => PE.Rpad(fromMock(str), fromMock(len), fromMock(pad))
      case ME.StringSplit(str, delimiter, limit) =>
        PE.StringSplit(fromMock(str), fromMock(delimiter), limit.map(fromMock))
      case ME.Reverse(child)                => PE.Reverse(fromMock(child))
      case ME.StringRepeat(str, times)      => PE.StringRepeat(fromMock(str), fromMock(times))
      case ME.Like(left, right, escapeChar) =>
        PE.Like(fromMock(left), fromMock(right), escapeChar.map(c => PE.lit(c.toString)))

      // === Aggregates ===
      case ME.Count(child, isDistinct) => PE.Count(fromMock(child), isDistinct)
      case ME.Sum(child)               => PE.Sum(fromMock(child))
      case ME.Avg(child)               => PE.Avg(fromMock(child))
      case ME.Min(child)               => PE.Min(fromMock(child))
      case ME.Max(child)               => PE.Max(fromMock(child))

      // === Control Flow ===
      case ME.CaseWhen(branches, elseValue) =>
        PE.CaseWhen(
          branches.map((cond, result) => (fromMock(cond), fromMock(result))).toVector,
          elseValue.map(fromMock)
        )

      case ME.If(predicate, trueValue, falseValue) =>
        PE.If(fromMock(predicate), fromMock(trueValue), fromMock(falseValue))

      case ME.In(value, list) => PE.In(fromMock(value), list.map(fromMock).toVector)

      // === Cast and Alias ===
      case ME.Cast(child, dataType) => PE.Cast(fromMock(child), TypeConverter.fromMock(dataType))
      case ME.Alias(child, name)    => PE.Alias(fromMock(child), name)

      // === Subqueries ===
      case ME.ScalarSubquery(plan, _) => PE.ScalarSubquery(PlanConverter.fromMock(plan))
      case ME.Exists(plan)            => PE.Exists(PlanConverter.fromMock(plan))
      case ME.InSubquery(value, plan) =>
        PE.InSubquery(fromMock(value), PlanConverter.fromMock(plan))

      // === Window Functions ===
      case ME.RowNumber()                  => PE.RowNumber()
      case ME.Rank()                       => PE.Rank()
      case ME.DenseRank()                  => PE.DenseRank()
      case ME.Ntile(n)                     => PE.Ntile(fromMock(n))
      case ME.Lead(input, offset, default) =>
        PE.Lead(fromMock(input), fromMock(offset), default.map(fromMock))
      case ME.Lag(input, offset, default) =>
        PE.Lag(fromMock(input), fromMock(offset), default.map(fromMock))
      case ME.FirstValue(input, ignoreNulls) => PE.FirstValue(fromMock(input), ignoreNulls)
      case ME.LastValue(input, ignoreNulls)  => PE.LastValue(fromMock(input), ignoreNulls)
      case ME.NthValue(input, n)             => PE.NthValue(fromMock(input), fromMock(n))

      case ME.WindowExpression(function, spec) =>
        PE.WindowExpr(
          fromMock(function),
          spec.partitionSpec.map(fromMock).toVector,
          spec.orderSpec.map(fromMockSortOrder).toVector,
          spec.frameSpec.map(fromMockWindowFrame)
        )

      // === Unresolved Function ===
      case ME.UnresolvedFunction(name, args, isDistinct) =>
        PE.OpaqueCall(name, args.map(fromMock).toVector, None, true)

      case other =>
        throw IllegalArgumentException(s"Cannot convert MockExpression: $other")

  private def convertMockLiteral(value: Any, dataType: MockDataType): ProtoExpr =
    import MockDataType.*
    import LiteralValue.*
    import ProtoExpr.Literal

    if value == null then Literal(NullValue(TypeConverter.fromMock(dataType)))
    else
      dataType match
        case BooleanType       => Literal(BooleanValue(value.asInstanceOf[Boolean]))
        case ByteType          => Literal(ByteValue(value.asInstanceOf[Byte]))
        case ShortType         => Literal(ShortValue(value.asInstanceOf[Short]))
        case IntegerType       => Literal(IntValue(value.asInstanceOf[Int]))
        case LongType          => Literal(LongValue(value.asInstanceOf[Long]))
        case FloatType         => Literal(FloatValue(value.asInstanceOf[Float]))
        case DoubleType        => Literal(DoubleValue(value.asInstanceOf[Double]))
        case StringType        => Literal(StringValue(value.asInstanceOf[String]))
        case BinaryType        => Literal(BinaryValue(value.asInstanceOf[Array[Byte]]))
        case DecimalType(_, _) => Literal(DecimalValue(value.asInstanceOf[BigDecimal]))
        case DateType          => Literal(DateValue(value.asInstanceOf[Int]))
        case TimestampType     => Literal(TimestampValue(value.asInstanceOf[Long]))
        case _                 => Literal(StringValue(value.toString))

  private def fromMockSortOrder(so: MockExpression.SortOrder): ProtoSortOrder =
    import MockExpression.{SortDirection as MSD, NullOrdering as MNO}
    ProtoSortOrder(
      fromMock(so.child),
      so.direction match
        case MSD.Ascending  => ProtoSortDirection.Ascending
        case MSD.Descending => ProtoSortDirection.Descending,
      so.nullOrdering match
        case MNO.NullsFirst => ProtoNullOrdering.NullsFirst
        case MNO.NullsLast  => ProtoNullOrdering.NullsLast
    )

  private def fromMockWindowFrame(frame: MockExpression.WindowFrame): WindowFrame =
    import MockExpression.{FrameType as MFT, FrameBound as MFB}
    WindowFrame(
      frame.frameType match
        case MFT.RowFrame   => FrameType.Rows
        case MFT.RangeFrame => FrameType.Range,
      frame.lower match
        case MFB.UnboundedPreceding => FrameBound.UnboundedPreceding
        case MFB.UnboundedFollowing => FrameBound.UnboundedFollowing
        case MFB.CurrentRow         => FrameBound.CurrentRow
        case MFB.Preceding(n)       => FrameBound.Preceding(n)
        case MFB.Following(n)       => FrameBound.Following(n),
      frame.upper match
        case MFB.UnboundedPreceding => FrameBound.UnboundedPreceding
        case MFB.UnboundedFollowing => FrameBound.UnboundedFollowing
        case MFB.CurrentRow         => FrameBound.CurrentRow
        case MFB.Preceding(n)       => FrameBound.Preceding(n)
        case MFB.Following(n)       => FrameBound.Following(n)
    )

/** Type converter between ProtoType and MockDataType.
  */
object TypeConverter:

  def toMock(pt: ProtoType): MockDataType =
    import ProtoType.*
    import MockDataType as MDT

    pt match
      case BooleanType                            => MDT.BooleanType
      case ByteType                               => MDT.ByteType
      case ShortType                              => MDT.ShortType
      case IntType                                => MDT.IntegerType
      case LongType                               => MDT.LongType
      case FloatType                              => MDT.FloatType
      case DoubleType                             => MDT.DoubleType
      case StringType                             => MDT.StringType
      case BinaryType                             => MDT.BinaryType
      case DateType                               => MDT.DateType
      case TimestampType                          => MDT.TimestampType
      case TimestampNTZType                       => MDT.TimestampNTZType
      case DayTimeIntervalType                    => MDT.DayTimeIntervalType
      case YearMonthIntervalType                  => MDT.YearMonthIntervalType
      case TimeType(precision)                    => MDT.TimeType(precision)
      case CalendarIntervalType                   => MDT.CalendarIntervalType
      case VariantType                            => MDT.VariantType
      case CharType(length)                       => MDT.CharType(length)
      case VarcharType(length)                    => MDT.VarcharType(length)
      case DecimalType(p, s)                      => MDT.DecimalType(p, s)
      case ArrayType(elem, containsNull)          => MDT.ArrayType(toMock(elem), containsNull)
      case MapType(key, value, valueContainsNull) =>
        MDT.MapType(toMock(key), toMock(value), valueContainsNull)
      case StructType(fields) =>
        MDT.StructType(fields.map(f => MockStructField(f.name, toMock(f.dataType), f.nullable)))
      case UDTType(_, sqlType)       => toMock(sqlType) // UDT uses underlying SQL type
      case UnresolvedType(hint)      => MDT.StringType // Fallback
      case SumType(discriminator, _) =>
        // Convert SumType to a struct with discriminator and ordinal
        MDT.StructType(
          Vector(
            MockStructField(discriminator, MDT.StringType, nullable = false),
            MockStructField("_ordinal", MDT.IntegerType, nullable = false)
          )
        )

  def fromMock(mdt: MockDataType): ProtoType =
    import MockDataType as MDT
    import ProtoType as PT

    mdt match
      case MDT.BooleanType                            => PT.BooleanType
      case MDT.ByteType                               => PT.ByteType
      case MDT.ShortType                              => PT.ShortType
      case MDT.IntegerType                            => PT.IntType
      case MDT.LongType                               => PT.LongType
      case MDT.FloatType                              => PT.FloatType
      case MDT.DoubleType                             => PT.DoubleType
      case MDT.StringType                             => PT.StringType
      case MDT.BinaryType                             => PT.BinaryType
      case MDT.DateType                               => PT.DateType
      case MDT.TimestampType                          => PT.TimestampType
      case MDT.TimestampNTZType                       => PT.TimestampNTZType
      case MDT.DayTimeIntervalType                    => PT.DayTimeIntervalType
      case MDT.YearMonthIntervalType                  => PT.YearMonthIntervalType
      case MDT.TimeType(precision)                    => PT.TimeType(precision)
      case MDT.CalendarIntervalType                   => PT.CalendarIntervalType
      case MDT.VariantType                            => PT.VariantType
      case MDT.CharType(length)                       => PT.CharType(length)
      case MDT.VarcharType(length)                    => PT.VarcharType(length)
      case MDT.DecimalType(p, s)                      => PT.DecimalType(p, s)
      case MDT.ArrayType(elem, containsNull)          => PT.ArrayType(fromMock(elem), containsNull)
      case MDT.MapType(key, value, valueContainsNull) =>
        PT.MapType(fromMock(key), fromMock(value), valueContainsNull)
      case MDT.StructType(fields) =>
        PT.StructType(
          fields.map(f =>
            protocatalyst.types.ProtoStructField(f.name, fromMock(f.dataType), f.nullable)
          )
        )
