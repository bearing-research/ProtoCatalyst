package protocatalyst.encoder.spark

import org.apache.spark.sql.types.{
  ArrayType => SparkArrayType,
  BinaryType => SparkBinaryType,
  BooleanType => SparkBooleanType,
  ByteType => SparkByteType,
  CalendarIntervalType => SparkCalendarIntervalType,
  CharType => SparkCharType,
  DataType => SparkDataType,
  DateType => SparkDateType,
  DayTimeIntervalType => SparkDayTimeIntervalType,
  DecimalType => SparkDecimalType,
  DoubleType => SparkDoubleType,
  FloatType => SparkFloatType,
  IntegerType => SparkIntegerType,
  LongType => SparkLongType,
  MapType => SparkMapType,
  Metadata => SparkMetadata,
  NullType => SparkNullType,
  ShortType => SparkShortType,
  StringType => SparkStringType,
  StructField => SparkStructField,
  StructType => SparkStructType,
  TimeType => SparkTimeType,
  TimestampNTZType => SparkTimestampNTZType,
  TimestampType => SparkTimestampType,
  VarcharType => SparkVarcharType,
  VariantType => SparkVariantType,
  YearMonthIntervalType => SparkYearMonthIntervalType
}

import protocatalyst.schema.ProtoSchema
import protocatalyst.types.{ProtoStructField, ProtoType}

/** Bidirectional bridge between `ProtoType` (engine-independent IR) and Spark's `DataType`.
  *
  * Used by `InternalRowSerializer` / `UnsafeRowSerializer` to dispatch the right Spark internal
  * accessor for each field. The mapping is intentionally total over every `ProtoType` that has a
  * corresponding `AgnosticEncoder` in Spark 4.0; see `docs/ENCODER_PARITY.md`.
  *
  * Three `ProtoType` cases — `UDTType`, `SumType`, `UnresolvedType` — do not have a direct Spark
  * equivalent and throw `IllegalArgumentException` if encountered. UDTs are intended to be
  * resolved upstream into their `sqlType`; sum types and unresolved types are
  * compile-time-only constructs.
  */
object SparkTypeMapping:

  def toSparkDataType(pt: ProtoType): SparkDataType = pt match
    case ProtoType.BooleanType                  => SparkBooleanType
    case ProtoType.ByteType                     => SparkByteType
    case ProtoType.ShortType                    => SparkShortType
    case ProtoType.IntegerType                  => SparkIntegerType
    case ProtoType.LongType                     => SparkLongType
    case ProtoType.FloatType                    => SparkFloatType
    case ProtoType.DoubleType                   => SparkDoubleType
    case ProtoType.StringType                   => SparkStringType
    case ProtoType.BinaryType                   => SparkBinaryType
    case ProtoType.DateType                     => SparkDateType
    case ProtoType.TimestampType                => SparkTimestampType
    case ProtoType.TimestampNTZType             => SparkTimestampNTZType
    case ProtoType.DayTimeIntervalType          => SparkDayTimeIntervalType()
    case ProtoType.YearMonthIntervalType        => SparkYearMonthIntervalType()
    case ProtoType.TimeType(precision)          => SparkTimeType(precision)
    case ProtoType.CalendarIntervalType         => SparkCalendarIntervalType
    case ProtoType.VariantType                  => SparkVariantType
    case ProtoType.NullType                     => SparkNullType
    case ProtoType.CharType(length)             => SparkCharType(length)
    case ProtoType.VarcharType(length)          => SparkVarcharType(length)
    case ProtoType.DecimalType(p, s)            => SparkDecimalType(p, s)
    case ProtoType.ArrayType(elem, containsNull) =>
      SparkArrayType(toSparkDataType(elem), containsNull)
    case ProtoType.MapType(k, v, vcn)           =>
      SparkMapType(toSparkDataType(k), toSparkDataType(v), vcn)
    case ProtoType.StructType(fields)           => SparkStructType(fields.map(toSparkStructField))
    case ProtoType.UDTType(_, sqlType)          =>
      // UDTs are resolved to their underlying SQL type for row interop. The Spark UDT
      // wrapping happens in `SparkExpressionEncoder`-shaped contexts, not here.
      toSparkDataType(sqlType)
    case ProtoType.SumType(_, _)                =>
      throw IllegalArgumentException(
        "ProtoType.SumType has no Spark DataType equivalent. Resolve sealed traits to a struct " +
          "(with discriminator + variant data) before crossing the Spark boundary."
      )
    case ProtoType.UnresolvedType(hint)         =>
      throw IllegalArgumentException(
        s"ProtoType.UnresolvedType($hint) cannot be lowered to a Spark DataType — resolve it first."
      )

  def toSparkStructField(f: ProtoStructField): SparkStructField =
    SparkStructField(f.name, toSparkDataType(f.dataType), f.nullable, SparkMetadata.empty)

  def toSparkStructType(schema: ProtoSchema): SparkStructType =
    SparkStructType(schema.fields.map(toSparkStructField))
