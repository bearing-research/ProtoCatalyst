package protocatalyst.arrow.parquet

import scala.jdk.CollectionConverters._

import org.apache.parquet.schema.LogicalTypeAnnotation._
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName._
import org.apache.parquet.schema.Type.Repetition
import org.apache.parquet.schema._

import protocatalyst.schema._
import protocatalyst.types._

/** Bidirectional conversion between ProtoSchema and Parquet MessageType.
  *
  * Follows the same pattern as ArrowSchemaConverter: two top-level methods with recursive helpers
  * for nested types. Uses Parquet's LogicalTypeAnnotation (not deprecated OriginalType).
  */
object ParquetSchemaConverter:

  // ============================================================
  // ProtoSchema -> Parquet MessageType
  // ============================================================

  def toParquetSchema(schema: ProtoSchema, name: String = "protocatalyst"): MessageType =
    val fields = schema.fields.map(toParquetType)
    new MessageType(name, fields.toList.asJava)

  def toParquetType(field: ProtoStructField): Type =
    val rep = if field.nullable then Repetition.OPTIONAL else Repetition.REQUIRED
    toParquetType(field.name, field.dataType, rep)

  private def toParquetType(name: String, dt: ProtoType, rep: Repetition): Type =
    dt match
      case ProtoType.BooleanType =>
        Types.primitive(BOOLEAN, rep).named(name)
      case ProtoType.ByteType =>
        Types.primitive(INT32, rep).as(intType(8, true)).named(name)
      case ProtoType.ShortType =>
        Types.primitive(INT32, rep).as(intType(16, true)).named(name)
      case ProtoType.IntegerType =>
        Types.primitive(INT32, rep).as(intType(32, true)).named(name)
      case ProtoType.LongType =>
        Types.primitive(INT64, rep).as(intType(64, true)).named(name)
      case ProtoType.FloatType =>
        Types.primitive(FLOAT, rep).named(name)
      case ProtoType.DoubleType =>
        Types.primitive(DOUBLE, rep).named(name)
      case ProtoType.StringType | ProtoType.CharType(_) | ProtoType.VarcharType(_) =>
        Types.primitive(BINARY, rep).as(stringType()).named(name)
      case ProtoType.BinaryType =>
        Types.primitive(BINARY, rep).named(name)
      case ProtoType.DateType =>
        Types.primitive(INT32, rep).as(dateType()).named(name)
      case ProtoType.TimestampType =>
        Types.primitive(INT64, rep).as(timestampType(true, TimeUnit.MICROS)).named(name)
      case ProtoType.TimestampNTZType =>
        Types.primitive(INT64, rep).as(timestampType(false, TimeUnit.MICROS)).named(name)
      case ProtoType.TimeType(_) =>
        Types.primitive(INT64, rep).as(timeType(true, TimeUnit.MICROS)).named(name)
      case ProtoType.DayTimeIntervalType =>
        // Store as microseconds in INT64
        Types.primitive(INT64, rep).as(intType(64, true)).named(name)
      case ProtoType.YearMonthIntervalType =>
        // Store as months in INT32
        Types.primitive(INT32, rep).as(intType(32, true)).named(name)

      case ProtoType.DecimalType(precision, scale) =>
        if precision <= 9 then
          Types.primitive(INT32, rep).as(decimalType(scale, precision)).named(name)
        else if precision <= 18 then
          Types.primitive(INT64, rep).as(decimalType(scale, precision)).named(name)
        else
          val byteLen = decimalByteLength(precision)
          Types
            .primitive(FIXED_LEN_BYTE_ARRAY, rep)
            .length(byteLen)
            .as(decimalType(scale, precision))
            .named(name)

      case ProtoType.ArrayType(elemType, containsNull) =>
        val elemRep = if containsNull then Repetition.OPTIONAL else Repetition.REQUIRED
        val elemParquet = toParquetType("element", elemType, elemRep)
        Types
          .list(rep)
          .element(elemParquet)
          .named(name)

      case ProtoType.MapType(keyType, valueType, valueContainsNull) =>
        val keyParquet = toParquetType("key", keyType, Repetition.REQUIRED)
        val valueRep = if valueContainsNull then Repetition.OPTIONAL else Repetition.REQUIRED
        val valueParquet = toParquetType("value", valueType, valueRep)
        Types
          .map(rep)
          .key(keyParquet)
          .value(valueParquet)
          .named(name)

      case ProtoType.StructType(fields) =>
        val children = fields.map(toParquetType)
        Types.buildGroup(rep).addFields(children*).named(name)

      case ProtoType.NullType =>
        // No native null type in Parquet; use optional int32 with no values set
        Types.primitive(INT32, Repetition.OPTIONAL).named(name)

      case other =>
        throw IllegalArgumentException(s"Unsupported ProtoType for Parquet: $other")

  private def decimalByteLength(precision: Int): Int =
    // Compute bytes needed: ceil(log2(10^precision)) / 8, approximately
    math.ceil(math.log10(math.pow(10, precision)) * math.log(10) / math.log(2) / 8.0).toInt.max(1)

  // ============================================================
  // Parquet MessageType -> ProtoSchema
  // ============================================================

  def fromParquetSchema(messageType: MessageType): ProtoSchema =
    val fields = (0 until messageType.getFieldCount).map { i =>
      fromParquetType(messageType.getType(i))
    }.toVector
    ProtoSchema(fields)

  def fromParquetType(parquetType: Type): ProtoStructField =
    val nullable = parquetType.getRepetition != Repetition.REQUIRED
    val dataType = fromParquetDataType(parquetType)
    ProtoStructField(parquetType.getName, dataType, nullable)

  private def fromParquetDataType(parquetType: Type): ProtoType =
    parquetType match
      case pt: PrimitiveType => fromPrimitiveType(pt)
      case gt: GroupType     => fromGroupType(gt)

  private def fromPrimitiveType(pt: PrimitiveType): ProtoType =
    val logical = pt.getLogicalTypeAnnotation
    (pt.getPrimitiveTypeName, logical) match
      case (BOOLEAN, _) => ProtoType.BooleanType

      case (INT32, i: IntLogicalTypeAnnotation) =>
        i.getBitWidth match
          case 8  => ProtoType.ByteType
          case 16 => ProtoType.ShortType
          case _  => ProtoType.IntegerType

      case (INT32, _: DateLogicalTypeAnnotation) => ProtoType.DateType

      case (INT32, d: DecimalLogicalTypeAnnotation) =>
        ProtoType.DecimalType(d.getPrecision, d.getScale)

      case (INT32, _) => ProtoType.IntegerType

      case (INT64, i: IntLogicalTypeAnnotation) =>
        if i.getBitWidth == 64 then ProtoType.LongType
        else ProtoType.LongType

      case (INT64, ts: TimestampLogicalTypeAnnotation) =>
        if ts.isAdjustedToUTC then ProtoType.TimestampType
        else ProtoType.TimestampNTZType

      case (INT64, _: TimeLogicalTypeAnnotation) =>
        ProtoType.TimeType(6)

      case (INT64, d: DecimalLogicalTypeAnnotation) =>
        ProtoType.DecimalType(d.getPrecision, d.getScale)

      case (INT64, _) => ProtoType.LongType

      case (FLOAT, _)  => ProtoType.FloatType
      case (DOUBLE, _) => ProtoType.DoubleType

      case (BINARY, _: StringLogicalTypeAnnotation) => ProtoType.StringType

      case (BINARY, d: DecimalLogicalTypeAnnotation) =>
        ProtoType.DecimalType(d.getPrecision, d.getScale)

      case (BINARY, _) => ProtoType.BinaryType

      case (FIXED_LEN_BYTE_ARRAY, d: DecimalLogicalTypeAnnotation) =>
        ProtoType.DecimalType(d.getPrecision, d.getScale)

      case (FIXED_LEN_BYTE_ARRAY, _) => ProtoType.BinaryType

      case (INT96, _) =>
        // Legacy timestamp representation
        ProtoType.TimestampType

      case _ =>
        ProtoType.BinaryType

  private def fromGroupType(gt: GroupType): ProtoType =
    val logical = gt.getLogicalTypeAnnotation
    logical match
      case _: ListLogicalTypeAnnotation =>
        // Standard 3-level list: list -> list (repeated) -> element
        val repeatedGroup = gt.getType(0).asGroupType()
        val elemType = repeatedGroup.getType(0)
        val elemProto = fromParquetDataType(elemType)
        val containsNull = elemType.getRepetition != Repetition.REQUIRED
        ProtoType.ArrayType(elemProto, containsNull)

      case _: MapLogicalTypeAnnotation =>
        // Standard 3-level map: map -> key_value (repeated) -> {key, value}
        val keyValueGroup = gt.getType(0).asGroupType()
        val keyType = fromParquetDataType(keyValueGroup.getType(0))
        val valueField = keyValueGroup.getType(1)
        val valueType = fromParquetDataType(valueField)
        val valueContainsNull = valueField.getRepetition != Repetition.REQUIRED
        ProtoType.MapType(keyType, valueType, valueContainsNull)

      case _ =>
        // Plain struct
        val fields = (0 until gt.getFieldCount).map { i =>
          fromParquetType(gt.getType(i))
        }.toVector
        ProtoType.StructType(fields)
