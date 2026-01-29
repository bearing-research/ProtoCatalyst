package protocatalyst.spark

import protocatalyst.schema.*
import protocatalyst.types.*
import org.apache.spark.sql.types.*

/** Converts ProtoSchema to Spark StructType. */
object SchemaConverter:

  def toSparkSchema(proto: ProtoSchema): StructType =
    StructType(proto.fields.map(toSparkField))

  def toSparkField(f: ProtoStructField): StructField =
    StructField(
      f.name,
      toSparkType(f.dataType),
      f.nullable,
      toSparkMetadata(f.metadata)
    )

  def toSparkType(pt: ProtoType): DataType = pt match
    case ProtoType.BooleanType => BooleanType
    case ProtoType.ByteType => ByteType
    case ProtoType.ShortType => ShortType
    case ProtoType.IntType => IntegerType
    case ProtoType.LongType => LongType
    case ProtoType.FloatType => FloatType
    case ProtoType.DoubleType => DoubleType
    case ProtoType.StringType => StringType
    case ProtoType.BinaryType => BinaryType
    case ProtoType.DateType => DateType
    case ProtoType.TimestampType => TimestampType
    case ProtoType.TimestampNTZType => TimestampNTZType
    case ProtoType.DecimalType(p, s) => DecimalType(p, s)
    case ProtoType.ArrayType(elem, containsNull) =>
      ArrayType(toSparkType(elem), containsNull)
    case ProtoType.MapType(key, value, valueContainsNull) =>
      MapType(toSparkType(key), toSparkType(value), valueContainsNull)
    case ProtoType.StructType(fields) =>
      StructType(fields.map(toSparkField))
    case ProtoType.UnresolvedType(hint) =>
      throw IllegalArgumentException(s"Cannot convert unresolved type: $hint")

  def toSparkMetadata(meta: Map[String, String]): Metadata =
    val builder = new MetadataBuilder()
    meta.foreach { case (k, v) => builder.putString(k, v) }
    builder.build()

  def fromSparkType(dt: DataType): ProtoType = dt match
    case BooleanType => ProtoType.BooleanType
    case ByteType => ProtoType.ByteType
    case ShortType => ProtoType.ShortType
    case IntegerType => ProtoType.IntType
    case LongType => ProtoType.LongType
    case FloatType => ProtoType.FloatType
    case DoubleType => ProtoType.DoubleType
    case StringType => ProtoType.StringType
    case BinaryType => ProtoType.BinaryType
    case DateType => ProtoType.DateType
    case TimestampType => ProtoType.TimestampType
    case TimestampNTZType => ProtoType.TimestampNTZType
    case DecimalType.Fixed(p, s) => ProtoType.DecimalType(p, s)
    case ArrayType(elem, containsNull) =>
      ProtoType.ArrayType(fromSparkType(elem), containsNull)
    case MapType(key, value, valueContainsNull) =>
      ProtoType.MapType(fromSparkType(key), fromSparkType(value), valueContainsNull)
    case StructType(fields) =>
      ProtoType.StructType(fields.map(fromSparkField).toVector)
    case other =>
      ProtoType.UnresolvedType(other.typeName)

  def fromSparkField(f: StructField): ProtoStructField =
    ProtoStructField(f.name, fromSparkType(f.dataType), f.nullable)
