package protocatalyst.substrait

import io.substrait.`type`.{Type, TypeCreator}
import protocatalyst.types.ProtoType

/** Converts ProtoCatalyst types to Substrait types.
  *
  * Maps ProtoCatalyst's type system to Substrait's protobuf-based type representation. Substrait
  * types are the portable interchange format for type information across query engines.
  */
object SubstraitTypeConverter:

  /** Convert a ProtoType to a Substrait Type.
    *
    * @param protoType
    *   The ProtoCatalyst type to convert
    * @return
    *   Equivalent Substrait Type
    * @throws UnsupportedSubstraitFeatureException
    *   if the type cannot be represented in Substrait
    */
  def toSubstrait(protoType: ProtoType): Type = protoType match
    // ========== Primitive Types ==========
    case ProtoType.BooleanType => TypeCreator.NULLABLE.BOOLEAN
    case ProtoType.ByteType    => TypeCreator.NULLABLE.I8
    case ProtoType.ShortType   => TypeCreator.NULLABLE.I16
    case ProtoType.IntegerType => TypeCreator.NULLABLE.I32
    case ProtoType.LongType    => TypeCreator.NULLABLE.I64
    case ProtoType.FloatType   => TypeCreator.NULLABLE.FP32
    case ProtoType.DoubleType  => TypeCreator.NULLABLE.FP64

    // ========== String and Binary ==========
    case ProtoType.StringType => TypeCreator.NULLABLE.STRING
    case ProtoType.BinaryType => TypeCreator.NULLABLE.BINARY

    // ========== Decimal ==========
    case ProtoType.DecimalType(precision, scale) =>
      TypeCreator.NULLABLE.decimal(precision, scale)

    // ========== Date and Time ==========
    case ProtoType.DateType      => TypeCreator.NULLABLE.DATE
    case ProtoType.TimestampType => TypeCreator.NULLABLE.TIMESTAMP_TZ

    // ========== Complex Types ==========
    case ProtoType.ArrayType(elementType, containsNull) =>
      val substraitElementType = toSubstrait(elementType)
      TypeCreator.NULLABLE.list(substraitElementType)

    case ProtoType.MapType(keyType, valueType, valueContainsNull) =>
      val substraitKeyType = toSubstrait(keyType)
      val substraitValueType = toSubstrait(valueType)
      TypeCreator.NULLABLE.map(substraitKeyType, substraitValueType)

    case ProtoType.StructType(fields) =>
      val substraitFields = fields.map { field =>
        val fieldType = toSubstrait(field.dataType)
        // Substrait struct field as Type
        fieldType
      }
      // Use TypeCreator to create struct with field types
      TypeCreator.NULLABLE.struct(substraitFields*)

    // ========== Unsupported Types ==========
    case ProtoType.NullType =>
      // NullType is not standard in Substrait
      throw UnsupportedSubstraitFeatureException("NullType not supported in Substrait")

    case ProtoType.TimeType(_) =>
      // TimeType (time of day) - Substrait has TIME type
      TypeCreator.NULLABLE.TIME

    case ProtoType.CalendarIntervalType =>
      // CalendarIntervalType - Substrait has INTERVAL_YEAR, INTERVAL_DAY, etc.
      // For now, throw unsupported
      throw UnsupportedSubstraitFeatureException(
        "CalendarIntervalType not yet supported in Substrait converter"
      )

    case ProtoType.YearMonthIntervalType =>
      // Year-month interval (e.g., INTERVAL '1-2' YEAR TO MONTH)
      // Substrait has INTERVAL_YEAR type
      throw UnsupportedSubstraitFeatureException(
        "YearMonthIntervalType not yet supported in Substrait converter"
      )

    case ProtoType.DayTimeIntervalType =>
      // Day-time interval (e.g., INTERVAL '1 2:3:4' DAY TO SECOND)
      // Substrait has INTERVAL_DAY type
      throw UnsupportedSubstraitFeatureException(
        "DayTimeIntervalType not yet supported in Substrait converter"
      )

    case ProtoType.TimestampNTZType =>
      // Timestamp without timezone
      TypeCreator.NULLABLE.TIMESTAMP

    case ProtoType.VariantType =>
      // Variant type (semi-structured data)
      throw UnsupportedSubstraitFeatureException("VariantType not supported in Substrait")

    case ProtoType.CharType(_) | ProtoType.VarcharType(_) =>
      // Fixed-length and variable-length character types
      TypeCreator.NULLABLE.STRING

    case ProtoType.UDTType(_, _) =>
      // User-defined type
      throw UnsupportedSubstraitFeatureException("UDTType not supported in Substrait")

    case ProtoType.UnresolvedType(_) =>
      // Unresolved type placeholder
      throw UnsupportedSubstraitFeatureException(
        "UnresolvedType cannot be converted (must be resolved first)"
      )

    case ProtoType.SumType(_, _) =>
      // Sum type (tagged union)
      throw UnsupportedSubstraitFeatureException("SumType not yet supported in Substrait converter")

end SubstraitTypeConverter

/** Exception thrown when a ProtoCatalyst feature cannot be represented in Substrait. */
class UnsupportedSubstraitFeatureException(message: String) extends Exception(message)
