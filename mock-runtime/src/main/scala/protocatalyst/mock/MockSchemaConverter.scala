package protocatalyst.mock

import protocatalyst.schema.ProtoSchema
import protocatalyst.types.*

/**
 * Bidirectional conversion between ProtoType and MockDataType.
 * Mirrors the real spark/SchemaConverter.scala implementation.
 */
object MockSchemaConverter:

  def toMockType(pt: ProtoType): MockDataType = pt match
    case ProtoType.BooleanType      => MockDataType.BooleanType
    case ProtoType.ByteType         => MockDataType.ByteType
    case ProtoType.ShortType        => MockDataType.ShortType
    case ProtoType.IntType          => MockDataType.IntegerType
    case ProtoType.LongType         => MockDataType.LongType
    case ProtoType.FloatType        => MockDataType.FloatType
    case ProtoType.DoubleType       => MockDataType.DoubleType
    case ProtoType.StringType       => MockDataType.StringType
    case ProtoType.BinaryType       => MockDataType.BinaryType
    case ProtoType.DateType         => MockDataType.DateType
    case ProtoType.TimestampType    => MockDataType.TimestampType
    case ProtoType.TimestampNTZType => MockDataType.TimestampNTZType
    case ProtoType.DecimalType(p, s) =>
      MockDataType.DecimalType(p, s)
    case ProtoType.ArrayType(elem, containsNull) =>
      MockDataType.ArrayType(toMockType(elem), containsNull)
    case ProtoType.MapType(key, value, valueContainsNull) =>
      MockDataType.MapType(toMockType(key), toMockType(value), valueContainsNull)
    case ProtoType.StructType(fields) =>
      MockDataType.StructType(fields.map(toMockField))
    case ProtoType.UnresolvedType(hint) =>
      throw IllegalArgumentException(s"Cannot convert unresolved type: $hint")

  def toMockField(f: ProtoStructField): MockStructField =
    MockStructField(f.name, toMockType(f.dataType), f.nullable, f.metadata)

  def toMockSchema(schema: ProtoSchema): MockDataType.StructType =
    MockDataType.StructType(schema.fields.map(toMockField))

  def toProtoType(dt: MockDataType): ProtoType = dt match
    case MockDataType.BooleanType      => ProtoType.BooleanType
    case MockDataType.ByteType         => ProtoType.ByteType
    case MockDataType.ShortType        => ProtoType.ShortType
    case MockDataType.IntegerType      => ProtoType.IntType
    case MockDataType.LongType         => ProtoType.LongType
    case MockDataType.FloatType        => ProtoType.FloatType
    case MockDataType.DoubleType       => ProtoType.DoubleType
    case MockDataType.StringType       => ProtoType.StringType
    case MockDataType.BinaryType       => ProtoType.BinaryType
    case MockDataType.DateType         => ProtoType.DateType
    case MockDataType.TimestampType    => ProtoType.TimestampType
    case MockDataType.TimestampNTZType => ProtoType.TimestampNTZType
    case MockDataType.DecimalType(p, s) =>
      ProtoType.DecimalType(p, s)
    case MockDataType.ArrayType(elem, containsNull) =>
      ProtoType.ArrayType(toProtoType(elem), containsNull)
    case MockDataType.MapType(key, value, valueContainsNull) =>
      ProtoType.MapType(toProtoType(key), toProtoType(value), valueContainsNull)
    case MockDataType.StructType(fields) =>
      ProtoType.StructType(fields.map(toProtoField))

  def toProtoField(f: MockStructField): ProtoStructField =
    ProtoStructField(f.name, toProtoType(f.dataType), f.nullable, f.metadata)

  def toProtoSchema(schema: MockDataType.StructType): ProtoSchema =
    ProtoSchema(schema.fields.map(toProtoField))
