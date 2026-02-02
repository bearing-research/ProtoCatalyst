package protocatalyst.arrow

import scala.jdk.CollectionConverters._

import org.apache.arrow.vector.types._
import org.apache.arrow.vector.types.pojo._

import protocatalyst.schema._
import protocatalyst.types._

/** Bidirectional conversion between ProtoCatalyst schema types and Apache Arrow schema types.
  *
  * This converter handles the mapping between ProtoType (compile-time type representation) and
  * Arrow's type system for columnar data.
  */
object ArrowSchemaConverter:

  // ============================================================
  // ProtoType -> Arrow Type Conversion
  // ============================================================

  /** Convert ProtoSchema to Arrow Schema */
  def toArrowSchema(proto: ProtoSchema): Schema =
    val fields = proto.fields.map(toArrowField).toList.asJava
    new Schema(fields)

  /** Convert ProtoStructField to Arrow Field */
  def toArrowField(f: ProtoStructField): Field =
    val fieldType = new FieldType(f.nullable, toArrowType(f.dataType), null)
    val children = getChildFields(f.dataType)
    new Field(f.name, fieldType, children.asJava)

  /** Convert ProtoType to Arrow ArrowType */
  def toArrowType(pt: ProtoType): ArrowType = pt match
    // Primitives
    case ProtoType.BooleanType => ArrowType.Bool.INSTANCE
    case ProtoType.ByteType    => new ArrowType.Int(8, true)
    case ProtoType.ShortType   => new ArrowType.Int(16, true)
    case ProtoType.IntType     => new ArrowType.Int(32, true)
    case ProtoType.LongType    => new ArrowType.Int(64, true)
    case ProtoType.FloatType   => new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)
    case ProtoType.DoubleType  => new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)
    case ProtoType.StringType  => ArrowType.Utf8.INSTANCE
    case ProtoType.BinaryType  => ArrowType.Binary.INSTANCE

    // Temporal
    case ProtoType.DateType              => new ArrowType.Date(DateUnit.DAY)
    case ProtoType.TimestampType         => new ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC")
    case ProtoType.TimestampNTZType      => new ArrowType.Timestamp(TimeUnit.MICROSECOND, null)
    case ProtoType.DayTimeIntervalType   => new ArrowType.Duration(TimeUnit.MICROSECOND)
    case ProtoType.YearMonthIntervalType => new ArrowType.Interval(IntervalUnit.YEAR_MONTH)
    case ProtoType.TimeType(precision)   =>
      // TimeType stores microseconds since midnight
      new ArrowType.Time(TimeUnit.MICROSECOND, 64) // 64-bit time with microsecond precision
    case ProtoType.CalendarIntervalType =>
      // CalendarInterval maps to Arrow Interval (month/day/nano)
      new ArrowType.Interval(IntervalUnit.MONTH_DAY_NANO)
    case ProtoType.VariantType =>
      // Variant is semi-structured data - store as binary
      ArrowType.LargeBinary.INSTANCE
    case ProtoType.NullType =>
      // Null type represents null-only columns
      ArrowType.Null.INSTANCE
    case ProtoType.CharType(_) | ProtoType.VarcharType(_) =>
      // Char/Varchar are stored as UTF8 strings
      ArrowType.Utf8.INSTANCE

    // Decimal
    case ProtoType.DecimalType(precision, scale) =>
      new ArrowType.Decimal(precision, scale, 128)

    // Complex types (Arrow type only - children handled separately)
    case ProtoType.ArrayType(_, _)  => ArrowType.List.INSTANCE
    case ProtoType.MapType(_, _, _) => new ArrowType.Map(false)
    case ProtoType.StructType(_)    => ArrowType.Struct.INSTANCE

    // Special types
    case ProtoType.UDTType(_, sqlType)  => toArrowType(sqlType)
    case ProtoType.SumType(_, _)        => ArrowType.Struct.INSTANCE
    case ProtoType.UnresolvedType(hint) =>
      throw IllegalArgumentException(s"Cannot convert unresolved type: $hint")

  /** Get child fields for complex types */
  private def getChildFields(pt: ProtoType): List[Field] = pt match
    case ProtoType.ArrayType(elemType, containsNull) =>
      val elemField = new Field(
        "item",
        new FieldType(containsNull, toArrowType(elemType), null),
        getChildFields(elemType).asJava
      )
      List(elemField)

    case ProtoType.MapType(keyType, valueType, valueContainsNull) =>
      val keyField = new Field(
        "key",
        new FieldType(false, toArrowType(keyType), null),
        getChildFields(keyType).asJava
      )
      val valueField = new Field(
        "value",
        new FieldType(valueContainsNull, toArrowType(valueType), null),
        getChildFields(valueType).asJava
      )
      val entriesField = new Field(
        "entries",
        new FieldType(false, ArrowType.Struct.INSTANCE, null),
        List(keyField, valueField).asJava
      )
      List(entriesField)

    case ProtoType.StructType(fields) =>
      fields.map(toArrowField).toList

    case ProtoType.SumType(_, variants) =>
      // Sum type as struct with discriminator fields
      val typeField = new Field(
        "_type",
        new FieldType(false, ArrowType.Utf8.INSTANCE, null),
        List.empty[Field].asJava
      )
      val ordinalField = new Field(
        "_ordinal",
        new FieldType(false, new ArrowType.Int(32, true), null),
        List.empty[Field].asJava
      )
      val valueField = new Field(
        "_value",
        new FieldType(true, ArrowType.Binary.INSTANCE, null),
        List.empty[Field].asJava
      )
      List(typeField, ordinalField, valueField)

    case _ => List.empty

  // ============================================================
  // Arrow Type -> ProtoType Conversion
  // ============================================================

  /** Convert Arrow Schema to ProtoSchema */
  def fromArrowSchema(schema: Schema): ProtoSchema =
    val fields = schema.getFields.asScala.map(fromArrowField).toVector
    ProtoSchema(fields)

  /** Convert Arrow Field to ProtoStructField */
  def fromArrowField(f: Field): ProtoStructField =
    ProtoStructField(
      f.getName,
      fromArrowType(f.getType, f.getChildren.asScala.toList),
      f.isNullable
    )

  /** Convert Arrow ArrowType to ProtoType */
  def fromArrowType(at: ArrowType, children: List[Field]): ProtoType = at match
    // Bool
    case ArrowType.Bool.INSTANCE => ProtoType.BooleanType

    // Integer types
    case i: ArrowType.Int =>
      (i.getBitWidth, i.getIsSigned) match
        case (8, true)  => ProtoType.ByteType
        case (16, true) => ProtoType.ShortType
        case (32, true) => ProtoType.IntType
        case (64, true) => ProtoType.LongType
        case _          =>
          ProtoType.UnresolvedType(
            s"Unsupported int type: ${i.getBitWidth}-bit, signed=${i.getIsSigned}"
          )

    // Floating point
    case fp: ArrowType.FloatingPoint =>
      fp.getPrecision match
        case FloatingPointPrecision.SINGLE => ProtoType.FloatType
        case FloatingPointPrecision.DOUBLE => ProtoType.DoubleType
        case _                             =>
          ProtoType.UnresolvedType(s"Unsupported floating point precision: ${fp.getPrecision}")

    // String and Binary
    case ArrowType.Utf8.INSTANCE        => ProtoType.StringType
    case ArrowType.LargeUtf8.INSTANCE   => ProtoType.StringType
    case ArrowType.Binary.INSTANCE      => ProtoType.BinaryType
    case ArrowType.LargeBinary.INSTANCE => ProtoType.BinaryType

    // Date and Timestamp
    case _: ArrowType.Date       => ProtoType.DateType
    case ts: ArrowType.Timestamp =>
      if ts.getTimezone != null then ProtoType.TimestampType
      else ProtoType.TimestampNTZType

    // Duration and Interval
    case _: ArrowType.Duration => ProtoType.DayTimeIntervalType
    case i: ArrowType.Interval =>
      i.getUnit match
        case IntervalUnit.YEAR_MONTH => ProtoType.YearMonthIntervalType
        case _                       => ProtoType.DayTimeIntervalType

    // Decimal
    case d: ArrowType.Decimal =>
      ProtoType.DecimalType(d.getPrecision, d.getScale)

    // Complex types
    case ArrowType.List.INSTANCE =>
      val elemField = children.headOption.getOrElse(
        throw IllegalArgumentException("List type must have element child")
      )
      ProtoType.ArrayType(
        fromArrowType(elemField.getType, elemField.getChildren.asScala.toList),
        elemField.isNullable
      )

    case _: ArrowType.Map =>
      val entriesField = children.headOption.getOrElse(
        throw IllegalArgumentException("Map type must have entries child")
      )
      val keyValue = entriesField.getChildren.asScala.toList
      if keyValue.size < 2 then
        throw IllegalArgumentException("Map entries must have key and value children")
      val keyField = keyValue.head
      val valueField = keyValue(1)
      ProtoType.MapType(
        fromArrowType(keyField.getType, keyField.getChildren.asScala.toList),
        fromArrowType(valueField.getType, valueField.getChildren.asScala.toList),
        valueField.isNullable
      )

    case ArrowType.Struct.INSTANCE =>
      ProtoType.StructType(children.map(fromArrowField).toVector)

    case ArrowType.Null.INSTANCE =>
      ProtoType.NullType

    case other =>
      ProtoType.UnresolvedType(other.toString)
