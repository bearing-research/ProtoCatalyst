package protocatalyst.sql

import protocatalyst.types.ProtoType

/** Converts [[ProtoType]] to SQL type names (ANSI SQL / DataFusion compatible).
  *
  * Produces SQL type names suitable for DDL statements and CAST expressions.
  */
object TypeSqlGenerator:

  /** Generate SQL type name from ProtoType.
    *
    * Examples:
    *   - `ProtoType.IntegerType` → `"INTEGER"`
    *   - `ProtoType.DecimalType(10, 2)` → `"DECIMAL(10, 2)"`
    *   - `ProtoType.ArrayType(ProtoType.StringType, true)` → `"ARRAY<VARCHAR>"`
    */
  def generate(ty: ProtoType): String = ty match
    case ProtoType.BooleanType      => "BOOLEAN"
    case ProtoType.ByteType         => "TINYINT"
    case ProtoType.ShortType        => "SMALLINT"
    case ProtoType.IntegerType      => "INTEGER"
    case ProtoType.LongType         => "BIGINT"
    case ProtoType.FloatType        => "REAL"
    case ProtoType.DoubleType       => "DOUBLE"
    case ProtoType.StringType       => "VARCHAR"
    case ProtoType.BinaryType       => "VARBINARY"
    case ProtoType.DateType         => "DATE"
    case ProtoType.TimestampType    => "TIMESTAMP"
    case ProtoType.TimestampNTZType => "TIMESTAMP" // DataFusion doesn't distinguish TZ vs NTZ

    case ProtoType.CharType(length)    => s"CHAR($length)"
    case ProtoType.VarcharType(length) => s"VARCHAR($length)"

    case ProtoType.DecimalType(precision, scale) => s"DECIMAL($precision, $scale)"

    case ProtoType.ArrayType(elementType, _) =>
      s"ARRAY<${generate(elementType)}>"

    case ProtoType.MapType(keyType, valueType, _) =>
      s"MAP<${generate(keyType)}, ${generate(valueType)}>"

    case ProtoType.StructType(fields) =>
      val fieldStrings = fields.map { field =>
        s"${field.name}: ${generate(field.dataType)}"
      }
      s"STRUCT<${fieldStrings.mkString(", ")}>"

    // Unsupported types
    case ProtoType.TimeType(_) =>
      throw UnsupportedSqlFeatureException(s"TimeType not supported in SQL generation")

    case ProtoType.DayTimeIntervalType =>
      throw UnsupportedSqlFeatureException(s"DayTimeIntervalType not supported in SQL generation")

    case ProtoType.YearMonthIntervalType =>
      throw UnsupportedSqlFeatureException(s"YearMonthIntervalType not supported in SQL generation")

    case ProtoType.CalendarIntervalType =>
      throw UnsupportedSqlFeatureException(s"CalendarIntervalType not supported in SQL generation")

    case ProtoType.VariantType =>
      throw UnsupportedSqlFeatureException(s"VariantType not supported in SQL generation")

    case ProtoType.NullType =>
      throw UnsupportedSqlFeatureException(s"NullType not supported in SQL generation")

    case ProtoType.UDTType(udtClassName, _) =>
      throw UnsupportedSqlFeatureException(
        s"UDTType($udtClassName) not supported in SQL generation"
      )

    case ProtoType.SumType(_, _) =>
      throw UnsupportedSqlFeatureException(s"SumType not supported in SQL generation")

    case ProtoType.UnresolvedType(hint) =>
      throw UnsupportedSqlFeatureException(s"UnresolvedType($hint) not supported in SQL generation")

end TypeSqlGenerator

/** Exception thrown when a ProtoType cannot be represented as SQL. */
class UnsupportedSqlFeatureException(message: String) extends RuntimeException(message)
