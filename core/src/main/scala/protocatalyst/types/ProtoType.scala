package protocatalyst.types

import java.io.Serializable

/** Compile-time representation of Spark DataTypes. */
enum ProtoType extends Serializable:
  case BooleanType
  case ByteType
  case ShortType
  case IntType
  case LongType
  case FloatType
  case DoubleType
  case StringType
  case BinaryType
  case DateType
  case TimestampType
  case TimestampNTZType
  case DayTimeIntervalType   // For java.time.Duration
  case YearMonthIntervalType // For java.time.Period
  case DecimalType(precision: Int, scale: Int)
  case ArrayType(elementType: ProtoType, containsNull: Boolean)
  case MapType(keyType: ProtoType, valueType: ProtoType, valueContainsNull: Boolean)
  case StructType(fields: Vector[ProtoStructField])
  case UDTType(udtClassName: String, sqlType: ProtoType)
  case UnresolvedType(hint: String)

case class ProtoStructField(
    name: String,
    dataType: ProtoType,
    nullable: Boolean,
    metadata: Map[String, String] = Map.empty
) extends Serializable

enum Nullability extends Serializable:
  case NonNull
  case Nullable
  case Unknown

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
  case BinaryValue(value: Array[Byte])
  case DecimalValue(value: BigDecimal)
  case DateValue(epochDays: Int)
  case TimestampValue(epochMicros: Long)
  case NullValue(dataType: ProtoType)

object LiteralValue:
  def typeOf(lit: LiteralValue): ProtoType = lit match
    case BooleanValue(_) => ProtoType.BooleanType
    case ByteValue(_) => ProtoType.ByteType
    case ShortValue(_) => ProtoType.ShortType
    case IntValue(_) => ProtoType.IntType
    case LongValue(_) => ProtoType.LongType
    case FloatValue(_) => ProtoType.FloatType
    case DoubleValue(_) => ProtoType.DoubleType
    case StringValue(_) => ProtoType.StringType
    case BinaryValue(_) => ProtoType.BinaryType
    case DecimalValue(_) => ProtoType.DecimalType(38, 18)
    case DateValue(_) => ProtoType.DateType
    case TimestampValue(_) => ProtoType.TimestampType
    case NullValue(dt) => dt
