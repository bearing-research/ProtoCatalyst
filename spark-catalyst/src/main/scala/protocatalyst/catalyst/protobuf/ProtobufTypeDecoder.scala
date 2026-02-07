package protocatalyst.catalyst.protobuf

import io.protocatalyst.proto.{v1 => pb}
import org.apache.spark.sql.types._

/** Decodes protobuf ProtoTypeMsg to Spark DataType. */
object ProtobufTypeDecoder {

  def decode(msg: pb.ProtoTypeMsg): DataType = {
    import pb.ProtoTypeMsg.TypeCase._
    msg.getTypeCase match {
      case BOOLEAN_TYPE             => BooleanType
      case BYTE_TYPE                => ByteType
      case SHORT_TYPE               => ShortType
      case INTEGER_TYPE             => IntegerType
      case LONG_TYPE                => LongType
      case FLOAT_TYPE               => FloatType
      case DOUBLE_TYPE              => DoubleType
      case STRING_TYPE              => StringType
      case BINARY_TYPE              => BinaryType
      case DATE_TYPE                => DateType
      case TIMESTAMP_TYPE           => TimestampType
      case TIMESTAMP_NTZ_TYPE       => TimestampNTZType
      case TIME_TYPE                => StringType // Spark doesn't have TimeType
      case DAY_TIME_INTERVAL_TYPE   => DayTimeIntervalType()
      case YEAR_MONTH_INTERVAL_TYPE => YearMonthIntervalType()
      case CALENDAR_INTERVAL_TYPE   => CalendarIntervalType
      case CHAR_TYPE                => CharType(msg.getCharType.getLength)
      case VARCHAR_TYPE             => VarcharType(msg.getVarcharType.getLength)
      case DECIMAL_TYPE => DecimalType(msg.getDecimalType.getPrecision, msg.getDecimalType.getScale)
      case ARRAY_TYPE   =>
        val at = msg.getArrayType
        ArrayType(decode(at.getElementType), at.getContainsNull)
      case MAP_TYPE =>
        val mt = msg.getMapType
        MapType(decode(mt.getKeyType), decode(mt.getValueType), mt.getValueContainsNull)
      case STRUCT_TYPE =>
        val st = msg.getStructType
        val fields = (0 until st.getFieldsCount).map { i =>
          val f = st.getFields(i)
          StructField(f.getName, decode(f.getDataType), f.getNullable)
        }
        StructType(fields.toArray)
      case UDT_TYPE =>
        decode(msg.getUdtType.getSqlType)
      case VARIANT_TYPE    => VariantType
      case NULL_TYPE       => NullType
      case SUM_TYPE        => StringType // Sum types (ADTs) -> StringType
      case UNRESOLVED_TYPE => StringType // Unresolved -> StringType
      case TYPE_NOT_SET    =>
        throw new IllegalArgumentException("ProtoTypeMsg type not set")
    }
  }
}
