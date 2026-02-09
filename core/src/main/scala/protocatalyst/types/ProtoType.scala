package protocatalyst.types

import java.io.Serializable

import scala.collection.immutable

/** Compile-time representation of Spark DataTypes. */
enum ProtoType extends Serializable:
  case BooleanType
  case ByteType
  case ShortType
  case IntegerType
  case LongType
  case FloatType
  case DoubleType
  case StringType
  case BinaryType
  case DateType
  case TimestampType
  case TimestampNTZType
  case DayTimeIntervalType // For java.time.Duration
  case YearMonthIntervalType // For java.time.Period
  case TimeType(precision: Int) // For java.time.LocalTime (microseconds since midnight)
  case CalendarIntervalType // For CalendarInterval (months, days, microseconds)
  case VariantType // For semi-structured data (JSON-like)
  case NullType // For null-only columns (java.lang.Void)
  case CharType(length: Int) // Fixed-length string
  case VarcharType(length: Int) // Variable-length string with max
  case DecimalType(precision: Int, scale: Int)
  case ArrayType(elementType: ProtoType, containsNull: Boolean)
  case MapType(keyType: ProtoType, valueType: ProtoType, valueContainsNull: Boolean)
  case StructType(fields: Vector[ProtoStructField])
  case UDTType(udtClassName: String, sqlType: ProtoType)
  case UnresolvedType(hint: String)
  case SumType(discriminatorField: String, variants: Vector[SumVariant])

case class ProtoStructField(
    name: String,
    dataType: ProtoType,
    nullable: Boolean,
    metadata: Map[String, String] = Map.empty
) extends Serializable

/** Variant descriptor for sum types (sealed traits/ADTs) */
case class SumVariant(
    name: String,
    ordinal: Int,
    dataType: Option[ProtoType] // None for case objects (no data)
) extends Serializable

/** Type-safe literal values for ProtoExpr.Literal */
enum LiteralValue extends Serializable:
  case BooleanValue(value: Boolean)
  case ByteValue(value: Byte)
  case ShortValue(value: Short)
  case IntValue(value: Int)
  case LongValue(value: Long)
  case FloatValue(value: Float)
  case DoubleValue(value: Double)
  case StringValue(value: String)
  case BinaryValue(value: immutable.ArraySeq[Byte])
  case DecimalValue(value: BigDecimal)
  case DateValue(epochDays: Int)
  case TimestampValue(epochMicros: Long)
  case TimeValue(microsSinceMidnight: Long)
  case CalendarIntervalValue(months: Int, days: Int, microseconds: Long)
  case NullValue(dataType: ProtoType)

object LiteralValue:
  def typeOf(lit: LiteralValue): ProtoType = lit match
    case BooleanValue(_)     => ProtoType.BooleanType
    case ByteValue(_)        => ProtoType.ByteType
    case ShortValue(_)       => ProtoType.ShortType
    case IntValue(_)         => ProtoType.IntegerType
    case LongValue(_)        => ProtoType.LongType
    case FloatValue(_)       => ProtoType.FloatType
    case DoubleValue(_)      => ProtoType.DoubleType
    case StringValue(_)      => ProtoType.StringType
    case BinaryValue(_)      => ProtoType.BinaryType
    case DecimalValue(value) =>
      val s = value.scale.max(0)
      val p = value.precision.max(s + 1) // ensure p >= s + 1 for integer part
      ProtoType.DecimalType(p, s)
    case DateValue(_)                   => ProtoType.DateType
    case TimestampValue(_)              => ProtoType.TimestampType
    case TimeValue(_)                   => ProtoType.TimeType(6) // Default microsecond precision
    case CalendarIntervalValue(_, _, _) => ProtoType.CalendarIntervalType
    case NullValue(dt)                  => dt
