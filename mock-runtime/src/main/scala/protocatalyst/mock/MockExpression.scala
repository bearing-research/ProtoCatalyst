package protocatalyst.mock

import protocatalyst.mock.MockDataType._

/** Mock Spark Expression hierarchy.
  *
  * Mirrors org.apache.spark.sql.catalyst.expressions.Expression for testing the conversion layer
  * before real Spark Scala 3 is available.
  */
sealed trait MockExpression:
  def dataType: MockDataType
  def nullable: Boolean
  def children: Seq[MockExpression]

object MockExpression:

  // === Leaf Expressions ===

  case class Literal(value: Any, dataType: MockDataType) extends MockExpression:
    def nullable: Boolean = value == null
    def children: Seq[MockExpression] = Seq.empty

  case class UnresolvedAttribute(nameParts: Seq[String]) extends MockExpression:
    def dataType: MockDataType = throw IllegalStateException("Unresolved attribute has no type")
    def nullable: Boolean = true
    def children: Seq[MockExpression] = Seq.empty

  case class AttributeReference(
      name: String,
      dataType: MockDataType,
      nullable: Boolean,
      qualifier: Seq[String] = Seq.empty
  ) extends MockExpression:
    def children: Seq[MockExpression] = Seq.empty

  case class BoundReference(ordinal: Int, dataType: MockDataType, nullable: Boolean)
      extends MockExpression:
    def children: Seq[MockExpression] = Seq.empty

  // === Comparison Expressions ===

  sealed trait BinaryComparison extends MockExpression:
    def left: MockExpression
    def right: MockExpression
    def dataType: MockDataType = BooleanType
    def nullable: Boolean = left.nullable || right.nullable
    def children: Seq[MockExpression] = Seq(left, right)

  case class EqualTo(left: MockExpression, right: MockExpression) extends BinaryComparison
  case class EqualNullSafe(left: MockExpression, right: MockExpression) extends BinaryComparison
  case class LessThan(left: MockExpression, right: MockExpression) extends BinaryComparison
  case class LessThanOrEqual(left: MockExpression, right: MockExpression) extends BinaryComparison
  case class GreaterThan(left: MockExpression, right: MockExpression) extends BinaryComparison
  case class GreaterThanOrEqual(left: MockExpression, right: MockExpression)
      extends BinaryComparison

  // === Logical Expressions ===

  case class And(left: MockExpression, right: MockExpression) extends MockExpression:
    def dataType: MockDataType = BooleanType
    def nullable: Boolean = left.nullable || right.nullable
    def children: Seq[MockExpression] = Seq(left, right)

  case class Or(left: MockExpression, right: MockExpression) extends MockExpression:
    def dataType: MockDataType = BooleanType
    def nullable: Boolean = left.nullable || right.nullable
    def children: Seq[MockExpression] = Seq(left, right)

  case class Not(child: MockExpression) extends MockExpression:
    def dataType: MockDataType = BooleanType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child)

  // === Null Handling ===

  case class IsNull(child: MockExpression) extends MockExpression:
    def dataType: MockDataType = BooleanType
    def nullable: Boolean = false
    def children: Seq[MockExpression] = Seq(child)

  case class IsNotNull(child: MockExpression) extends MockExpression:
    def dataType: MockDataType = BooleanType
    def nullable: Boolean = false
    def children: Seq[MockExpression] = Seq(child)

  case class Coalesce(children: Seq[MockExpression]) extends MockExpression:
    def dataType: MockDataType = children.headOption.map(_.dataType).getOrElse(StringType)
    def nullable: Boolean = children.forall(_.nullable)

  case class NullIf(left: MockExpression, right: MockExpression) extends MockExpression:
    def dataType: MockDataType = left.dataType
    def nullable: Boolean = true
    def children: Seq[MockExpression] = Seq(left, right)

  // === Arithmetic Expressions ===

  sealed trait BinaryArithmetic extends MockExpression:
    def left: MockExpression
    def right: MockExpression
    def dataType: MockDataType = promoteNumericTypes(left.dataType, right.dataType)
    def nullable: Boolean = left.nullable || right.nullable
    def children: Seq[MockExpression] = Seq(left, right)

  case class Add(left: MockExpression, right: MockExpression) extends BinaryArithmetic
  case class Subtract(left: MockExpression, right: MockExpression) extends BinaryArithmetic
  case class Multiply(left: MockExpression, right: MockExpression) extends BinaryArithmetic
  case class Divide(left: MockExpression, right: MockExpression) extends BinaryArithmetic:
    override def nullable: Boolean = true // Division can produce null (div by zero)

  // === Math Functions ===

  case class Abs(child: MockExpression) extends MockExpression:
    def dataType: MockDataType = child.dataType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child)

  case class Ceil(child: MockExpression) extends MockExpression:
    def dataType: MockDataType = LongType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child)

  case class Floor(child: MockExpression) extends MockExpression:
    def dataType: MockDataType = LongType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child)

  case class Round(child: MockExpression, scale: MockExpression) extends MockExpression:
    def dataType: MockDataType = child.dataType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child, scale)

  case class Truncate(child: MockExpression, scale: MockExpression) extends MockExpression:
    def dataType: MockDataType = child.dataType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child, scale)

  case class Sqrt(child: MockExpression) extends MockExpression:
    def dataType: MockDataType = DoubleType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child)

  case class Cbrt(child: MockExpression) extends MockExpression:
    def dataType: MockDataType = DoubleType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child)

  case class Pow(left: MockExpression, right: MockExpression) extends MockExpression:
    def dataType: MockDataType = DoubleType
    def nullable: Boolean = left.nullable || right.nullable
    def children: Seq[MockExpression] = Seq(left, right)

  case class Pmod(left: MockExpression, right: MockExpression) extends MockExpression:
    def dataType: MockDataType = promoteNumericTypes(left.dataType, right.dataType)
    def nullable: Boolean = left.nullable || right.nullable
    def children: Seq[MockExpression] = Seq(left, right)

  case class Sign(child: MockExpression) extends MockExpression:
    def dataType: MockDataType = child.dataType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child)

  case class Log(child: MockExpression, base: Option[MockExpression]) extends MockExpression:
    def dataType: MockDataType = DoubleType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child) ++ base.toSeq

  case class Exp(child: MockExpression) extends MockExpression:
    def dataType: MockDataType = DoubleType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child)

  // === String Expressions ===

  case class Concat(children: Seq[MockExpression]) extends MockExpression:
    def dataType: MockDataType = StringType
    def nullable: Boolean = children.exists(_.nullable)

  case class Substring(str: MockExpression, pos: MockExpression, len: MockExpression)
      extends MockExpression:
    def dataType: MockDataType = StringType
    def nullable: Boolean = str.nullable || pos.nullable || len.nullable
    def children: Seq[MockExpression] = Seq(str, pos, len)

  case class Upper(child: MockExpression) extends MockExpression:
    def dataType: MockDataType = StringType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child)

  case class Lower(child: MockExpression) extends MockExpression:
    def dataType: MockDataType = StringType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child)

  case class Like(left: MockExpression, right: MockExpression, escapeChar: Option[Char] = None)
      extends MockExpression:
    def dataType: MockDataType = BooleanType
    def nullable: Boolean = left.nullable || right.nullable
    def children: Seq[MockExpression] = Seq(left, right)

  case class Trim(child: MockExpression, trimStr: Option[MockExpression], trimType: TrimType)
      extends MockExpression:
    def dataType: MockDataType = StringType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child) ++ trimStr.toSeq

  case class Length(child: MockExpression) extends MockExpression:
    def dataType: MockDataType = IntegerType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child)

  case class Replace(str: MockExpression, search: MockExpression, replace: MockExpression)
      extends MockExpression:
    def dataType: MockDataType = StringType
    def nullable: Boolean = str.nullable || search.nullable || replace.nullable
    def children: Seq[MockExpression] = Seq(str, search, replace)

  case class StringLocate(
      substr: MockExpression,
      str: MockExpression,
      start: Option[MockExpression]
  ) extends MockExpression:
    def dataType: MockDataType = IntegerType
    def nullable: Boolean = substr.nullable || str.nullable
    def children: Seq[MockExpression] = Seq(substr, str) ++ start.toSeq

  case class Lpad(str: MockExpression, len: MockExpression, pad: MockExpression)
      extends MockExpression:
    def dataType: MockDataType = StringType
    def nullable: Boolean = str.nullable || len.nullable || pad.nullable
    def children: Seq[MockExpression] = Seq(str, len, pad)

  case class Rpad(str: MockExpression, len: MockExpression, pad: MockExpression)
      extends MockExpression:
    def dataType: MockDataType = StringType
    def nullable: Boolean = str.nullable || len.nullable || pad.nullable
    def children: Seq[MockExpression] = Seq(str, len, pad)

  case class StringSplit(
      str: MockExpression,
      delimiter: MockExpression,
      limit: Option[MockExpression]
  ) extends MockExpression:
    def dataType: MockDataType = ArrayType(StringType, false)
    def nullable: Boolean = str.nullable || delimiter.nullable
    def children: Seq[MockExpression] = Seq(str, delimiter) ++ limit.toSeq

  case class Reverse(child: MockExpression) extends MockExpression:
    def dataType: MockDataType = child.dataType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child)

  case class StringRepeat(str: MockExpression, times: MockExpression) extends MockExpression:
    def dataType: MockDataType = StringType
    def nullable: Boolean = str.nullable || times.nullable
    def children: Seq[MockExpression] = Seq(str, times)

  enum TrimType:
    case Both, Leading, Trailing

  // === Aggregate Expressions ===

  sealed trait AggregateExpression extends MockExpression

  case class Count(child: MockExpression, isDistinct: Boolean = false) extends AggregateExpression:
    def dataType: MockDataType = LongType
    def nullable: Boolean = false
    def children: Seq[MockExpression] = Seq(child)

  case class Sum(child: MockExpression) extends AggregateExpression:
    def dataType: MockDataType = child.dataType
    def nullable: Boolean = true
    def children: Seq[MockExpression] = Seq(child)

  case class Avg(child: MockExpression) extends AggregateExpression:
    def dataType: MockDataType = DoubleType
    def nullable: Boolean = true
    def children: Seq[MockExpression] = Seq(child)

  case class Min(child: MockExpression) extends AggregateExpression:
    def dataType: MockDataType = child.dataType
    def nullable: Boolean = true
    def children: Seq[MockExpression] = Seq(child)

  case class Max(child: MockExpression) extends AggregateExpression:
    def dataType: MockDataType = child.dataType
    def nullable: Boolean = true
    def children: Seq[MockExpression] = Seq(child)

  // === Control Flow ===

  case class CaseWhen(
      branches: Seq[(MockExpression, MockExpression)],
      elseValue: Option[MockExpression] = None
  ) extends MockExpression:
    def dataType: MockDataType = branches.headOption.map(_._2.dataType).getOrElse(StringType)
    def nullable: Boolean =
      branches.exists(_._2.nullable) || elseValue.exists(_.nullable) || elseValue.isEmpty
    def children: Seq[MockExpression] = branches.flatMap(b => Seq(b._1, b._2)) ++ elseValue.toSeq

  case class If(predicate: MockExpression, trueValue: MockExpression, falseValue: MockExpression)
      extends MockExpression:
    def dataType: MockDataType = trueValue.dataType
    def nullable: Boolean = predicate.nullable || trueValue.nullable || falseValue.nullable
    def children: Seq[MockExpression] = Seq(predicate, trueValue, falseValue)

  case class In(value: MockExpression, list: Seq[MockExpression]) extends MockExpression:
    def dataType: MockDataType = BooleanType
    def nullable: Boolean = value.nullable || list.exists(_.nullable)
    def children: Seq[MockExpression] = value +: list

  // === Cast and Alias ===

  case class Cast(child: MockExpression, dataType: MockDataType) extends MockExpression:
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child)

  case class Alias(child: MockExpression, name: String) extends MockExpression:
    def dataType: MockDataType = child.dataType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child)

  // === Subquery Expressions ===

  case class ScalarSubquery(plan: MockLogicalPlan, dataType: MockDataType) extends MockExpression:
    def nullable: Boolean = true
    def children: Seq[MockExpression] = Seq.empty

  case class Exists(plan: MockLogicalPlan) extends MockExpression:
    def dataType: MockDataType = BooleanType
    def nullable: Boolean = false
    def children: Seq[MockExpression] = Seq.empty

  case class InSubquery(value: MockExpression, plan: MockLogicalPlan) extends MockExpression:
    def dataType: MockDataType = BooleanType
    def nullable: Boolean = value.nullable
    def children: Seq[MockExpression] = Seq(value)

  // === Window Functions ===

  sealed trait WindowFunction extends MockExpression

  case class RowNumber() extends WindowFunction:
    def dataType: MockDataType = IntegerType
    def nullable: Boolean = false
    def children: Seq[MockExpression] = Seq.empty

  case class Rank() extends WindowFunction:
    def dataType: MockDataType = IntegerType
    def nullable: Boolean = false
    def children: Seq[MockExpression] = Seq.empty

  case class DenseRank() extends WindowFunction:
    def dataType: MockDataType = IntegerType
    def nullable: Boolean = false
    def children: Seq[MockExpression] = Seq.empty

  case class Ntile(n: MockExpression) extends WindowFunction:
    def dataType: MockDataType = IntegerType
    def nullable: Boolean = false
    def children: Seq[MockExpression] = Seq(n)

  case class Lead(input: MockExpression, offset: MockExpression, default: Option[MockExpression])
      extends WindowFunction:
    def dataType: MockDataType = input.dataType
    def nullable: Boolean = true
    def children: Seq[MockExpression] = Seq(input, offset) ++ default.toSeq

  case class Lag(input: MockExpression, offset: MockExpression, default: Option[MockExpression])
      extends WindowFunction:
    def dataType: MockDataType = input.dataType
    def nullable: Boolean = true
    def children: Seq[MockExpression] = Seq(input, offset) ++ default.toSeq

  case class FirstValue(input: MockExpression, ignoreNulls: Boolean) extends WindowFunction:
    def dataType: MockDataType = input.dataType
    def nullable: Boolean = true
    def children: Seq[MockExpression] = Seq(input)

  case class LastValue(input: MockExpression, ignoreNulls: Boolean) extends WindowFunction:
    def dataType: MockDataType = input.dataType
    def nullable: Boolean = true
    def children: Seq[MockExpression] = Seq(input)

  case class NthValue(input: MockExpression, n: MockExpression) extends WindowFunction:
    def dataType: MockDataType = input.dataType
    def nullable: Boolean = true
    def children: Seq[MockExpression] = Seq(input, n)

  case class WindowSpecDefinition(
      partitionSpec: Seq[MockExpression],
      orderSpec: Seq[SortOrder],
      frameSpec: Option[WindowFrame]
  )

  case class WindowExpression(
      windowFunction: MockExpression,
      windowSpec: WindowSpecDefinition
  ) extends MockExpression:
    def dataType: MockDataType = windowFunction.dataType
    def nullable: Boolean = windowFunction.nullable
    def children: Seq[MockExpression] =
      Seq(windowFunction) ++ windowSpec.partitionSpec ++ windowSpec.orderSpec.map(_.child)

  // === Sort Specification ===

  case class SortOrder(
      child: MockExpression,
      direction: SortDirection,
      nullOrdering: NullOrdering
  ):
    def dataType: MockDataType = child.dataType

  enum SortDirection:
    case Ascending, Descending

  enum NullOrdering:
    case NullsFirst, NullsLast

  // === Window Frame ===

  case class WindowFrame(
      frameType: FrameType,
      lower: FrameBound,
      upper: FrameBound
  )

  enum FrameType:
    case RowFrame, RangeFrame

  enum FrameBound:
    case UnboundedPreceding
    case UnboundedFollowing
    case CurrentRow
    case Preceding(n: Long)
    case Following(n: Long)

  // === Date/Time Expressions ===

  case class CurrentDate() extends MockExpression:
    def dataType: MockDataType = DateType
    def nullable: Boolean = false
    def children: Seq[MockExpression] = Seq.empty

  case class CurrentTimestamp() extends MockExpression:
    def dataType: MockDataType = TimestampType
    def nullable: Boolean = false
    def children: Seq[MockExpression] = Seq.empty

  case class DateAdd(startDate: MockExpression, days: MockExpression) extends MockExpression:
    def dataType: MockDataType = DateType
    def nullable: Boolean = startDate.nullable || days.nullable
    def children: Seq[MockExpression] = Seq(startDate, days)

  case class DateSub(startDate: MockExpression, days: MockExpression) extends MockExpression:
    def dataType: MockDataType = DateType
    def nullable: Boolean = startDate.nullable || days.nullable
    def children: Seq[MockExpression] = Seq(startDate, days)

  case class DateDiff(endDate: MockExpression, startDate: MockExpression) extends MockExpression:
    def dataType: MockDataType = IntegerType
    def nullable: Boolean = endDate.nullable || startDate.nullable
    def children: Seq[MockExpression] = Seq(endDate, startDate)

  enum DateTimeField:
    case Year, Month, Day, Hour, Minute, Second
    case Quarter, Week, DayOfWeek, DayOfYear
    case Microsecond, Millisecond

  case class Extract(field: DateTimeField, source: MockExpression) extends MockExpression:
    def dataType: MockDataType = IntegerType
    def nullable: Boolean = source.nullable
    def children: Seq[MockExpression] = Seq(source)

  case class DateTrunc(field: DateTimeField, timestamp: MockExpression) extends MockExpression:
    def dataType: MockDataType = timestamp.dataType
    def nullable: Boolean = timestamp.nullable
    def children: Seq[MockExpression] = Seq(timestamp)

  case class ToDate(str: MockExpression, format: Option[MockExpression]) extends MockExpression:
    def dataType: MockDataType = DateType
    def nullable: Boolean = true
    def children: Seq[MockExpression] = str +: format.toSeq

  case class ToTimestamp(str: MockExpression, format: Option[MockExpression]) extends MockExpression:
    def dataType: MockDataType = TimestampType
    def nullable: Boolean = true
    def children: Seq[MockExpression] = str +: format.toSeq

  case class Year(child: MockExpression) extends MockExpression:
    def dataType: MockDataType = IntegerType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child)

  case class Month(child: MockExpression) extends MockExpression:
    def dataType: MockDataType = IntegerType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child)

  case class DayOfMonth(child: MockExpression) extends MockExpression:
    def dataType: MockDataType = IntegerType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child)

  case class Hour(child: MockExpression) extends MockExpression:
    def dataType: MockDataType = IntegerType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child)

  case class Minute(child: MockExpression) extends MockExpression:
    def dataType: MockDataType = IntegerType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child)

  case class Second(child: MockExpression) extends MockExpression:
    def dataType: MockDataType = IntegerType
    def nullable: Boolean = child.nullable
    def children: Seq[MockExpression] = Seq(child)

  // === Unresolved Function ===

  case class UnresolvedFunction(
      name: String,
      arguments: Seq[MockExpression],
      isDistinct: Boolean = false
  ) extends MockExpression:
    def dataType: MockDataType = throw IllegalStateException("Unresolved function has no type")
    def nullable: Boolean = true
    def children: Seq[MockExpression] = arguments

  // === Helper ===

  private def promoteNumericTypes(t1: MockDataType, t2: MockDataType): MockDataType =
    (t1, t2) match
      case (DoubleType, _) | (_, DoubleType)   => DoubleType
      case (FloatType, _) | (_, FloatType)     => FloatType
      case (LongType, _) | (_, LongType)       => LongType
      case (IntegerType, _) | (_, IntegerType) => IntegerType
      case (ShortType, _) | (_, ShortType)     => ShortType
      case (ByteType, _) | (_, ByteType)       => ByteType
      case _                                   => t1
