package protocatalyst.arrow

import protocatalyst.types.*
import protocatalyst.schema.*
import protocatalyst.encoder.ProtoEncoder
import org.apache.arrow.vector.types.*
import org.apache.arrow.vector.types.pojo.*

class ArrowSchemaConverterSuite extends munit.FunSuite:

  // === Primitive Type Conversion Tests ===

  test("convert BooleanType to Arrow Bool"):
    val arrowType = ArrowSchemaConverter.toArrowType(ProtoType.BooleanType)
    assertEquals(arrowType, ArrowType.Bool.INSTANCE)

  test("convert ByteType to Arrow Int8"):
    val arrowType = ArrowSchemaConverter.toArrowType(ProtoType.ByteType)
    arrowType match
      case i: ArrowType.Int =>
        assertEquals(i.getBitWidth, 8)
        assertEquals(i.getIsSigned, true)
      case _ => fail("Expected ArrowType.Int")

  test("convert ShortType to Arrow Int16"):
    val arrowType = ArrowSchemaConverter.toArrowType(ProtoType.ShortType)
    arrowType match
      case i: ArrowType.Int =>
        assertEquals(i.getBitWidth, 16)
        assertEquals(i.getIsSigned, true)
      case _ => fail("Expected ArrowType.Int")

  test("convert IntType to Arrow Int32"):
    val arrowType = ArrowSchemaConverter.toArrowType(ProtoType.IntType)
    arrowType match
      case i: ArrowType.Int =>
        assertEquals(i.getBitWidth, 32)
        assertEquals(i.getIsSigned, true)
      case _ => fail("Expected ArrowType.Int")

  test("convert LongType to Arrow Int64"):
    val arrowType = ArrowSchemaConverter.toArrowType(ProtoType.LongType)
    arrowType match
      case i: ArrowType.Int =>
        assertEquals(i.getBitWidth, 64)
        assertEquals(i.getIsSigned, true)
      case _ => fail("Expected ArrowType.Int")

  test("convert FloatType to Arrow Float"):
    val arrowType = ArrowSchemaConverter.toArrowType(ProtoType.FloatType)
    arrowType match
      case fp: ArrowType.FloatingPoint =>
        assertEquals(fp.getPrecision, FloatingPointPrecision.SINGLE)
      case _ => fail("Expected ArrowType.FloatingPoint")

  test("convert DoubleType to Arrow Double"):
    val arrowType = ArrowSchemaConverter.toArrowType(ProtoType.DoubleType)
    arrowType match
      case fp: ArrowType.FloatingPoint =>
        assertEquals(fp.getPrecision, FloatingPointPrecision.DOUBLE)
      case _ => fail("Expected ArrowType.FloatingPoint")

  test("convert StringType to Arrow Utf8"):
    val arrowType = ArrowSchemaConverter.toArrowType(ProtoType.StringType)
    assertEquals(arrowType, ArrowType.Utf8.INSTANCE)

  test("convert BinaryType to Arrow Binary"):
    val arrowType = ArrowSchemaConverter.toArrowType(ProtoType.BinaryType)
    assertEquals(arrowType, ArrowType.Binary.INSTANCE)

  // === Temporal Type Conversion Tests ===

  test("convert DateType to Arrow Date"):
    val arrowType = ArrowSchemaConverter.toArrowType(ProtoType.DateType)
    arrowType match
      case d: ArrowType.Date =>
        assertEquals(d.getUnit, DateUnit.DAY)
      case _ => fail("Expected ArrowType.Date")

  test("convert TimestampType to Arrow Timestamp with UTC"):
    val arrowType = ArrowSchemaConverter.toArrowType(ProtoType.TimestampType)
    arrowType match
      case ts: ArrowType.Timestamp =>
        assertEquals(ts.getUnit, TimeUnit.MICROSECOND)
        assertEquals(ts.getTimezone, "UTC")
      case _ => fail("Expected ArrowType.Timestamp")

  test("convert TimestampNTZType to Arrow Timestamp without timezone"):
    val arrowType = ArrowSchemaConverter.toArrowType(ProtoType.TimestampNTZType)
    arrowType match
      case ts: ArrowType.Timestamp =>
        assertEquals(ts.getUnit, TimeUnit.MICROSECOND)
        assertEquals(ts.getTimezone, null)
      case _ => fail("Expected ArrowType.Timestamp")

  // === Decimal Type Conversion Tests ===

  test("convert DecimalType to Arrow Decimal"):
    val arrowType = ArrowSchemaConverter.toArrowType(ProtoType.DecimalType(10, 2))
    arrowType match
      case d: ArrowType.Decimal =>
        assertEquals(d.getPrecision, 10)
        assertEquals(d.getScale, 2)
        assertEquals(d.getBitWidth, 128)
      case _ => fail("Expected ArrowType.Decimal")

  // === Schema Conversion Tests ===

  test("convert simple ProtoSchema to Arrow Schema"):
    val protoSchema = ProtoSchema(Vector(
      ProtoStructField("id", ProtoType.LongType, false),
      ProtoStructField("name", ProtoType.StringType, true),
      ProtoStructField("score", ProtoType.DoubleType, false)
    ))

    val arrowSchema = ArrowSchemaConverter.toArrowSchema(protoSchema)

    assertEquals(arrowSchema.getFields.size(), 3)
    assertEquals(arrowSchema.getFields.get(0).getName, "id")
    assertEquals(arrowSchema.getFields.get(0).isNullable, false)
    assertEquals(arrowSchema.getFields.get(1).getName, "name")
    assertEquals(arrowSchema.getFields.get(1).isNullable, true)
    assertEquals(arrowSchema.getFields.get(2).getName, "score")

  // === Roundtrip Tests ===

  test("roundtrip primitive types"):
    val types = List(
      ProtoType.BooleanType,
      ProtoType.ByteType,
      ProtoType.ShortType,
      ProtoType.IntType,
      ProtoType.LongType,
      ProtoType.FloatType,
      ProtoType.DoubleType,
      ProtoType.StringType,
      ProtoType.BinaryType
    )

    for pt <- types do
      val protoSchema = ProtoSchema(Vector(ProtoStructField("field", pt, false)))
      val arrowSchema = ArrowSchemaConverter.toArrowSchema(protoSchema)
      val backToProto = ArrowSchemaConverter.fromArrowSchema(arrowSchema)
      assertEquals(backToProto.fields.head.dataType, pt, s"Roundtrip failed for $pt")

  test("roundtrip temporal types"):
    val types = List(
      ProtoType.DateType,
      ProtoType.TimestampType,
      ProtoType.TimestampNTZType
    )

    for pt <- types do
      val protoSchema = ProtoSchema(Vector(ProtoStructField("field", pt, false)))
      val arrowSchema = ArrowSchemaConverter.toArrowSchema(protoSchema)
      val backToProto = ArrowSchemaConverter.fromArrowSchema(arrowSchema)
      assertEquals(backToProto.fields.head.dataType, pt, s"Roundtrip failed for $pt")

  test("roundtrip DecimalType"):
    val pt = ProtoType.DecimalType(18, 6)
    val protoSchema = ProtoSchema(Vector(ProtoStructField("amount", pt, false)))
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(protoSchema)
    val backToProto = ArrowSchemaConverter.fromArrowSchema(arrowSchema)
    assertEquals(backToProto.fields.head.dataType, pt)

  test("roundtrip ArrayType"):
    val pt = ProtoType.ArrayType(ProtoType.IntType, true)
    val protoSchema = ProtoSchema(Vector(ProtoStructField("values", pt, false)))
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(protoSchema)
    val backToProto = ArrowSchemaConverter.fromArrowSchema(arrowSchema)
    assertEquals(backToProto.fields.head.dataType, pt)

  test("roundtrip MapType"):
    val pt = ProtoType.MapType(ProtoType.StringType, ProtoType.IntType, true)
    val protoSchema = ProtoSchema(Vector(ProtoStructField("mapping", pt, false)))
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(protoSchema)
    val backToProto = ArrowSchemaConverter.fromArrowSchema(arrowSchema)
    assertEquals(backToProto.fields.head.dataType, pt)

  test("roundtrip StructType"):
    val nestedFields = Vector(
      ProtoStructField("x", ProtoType.IntType, false),
      ProtoStructField("y", ProtoType.IntType, false)
    )
    val pt = ProtoType.StructType(nestedFields)
    val protoSchema = ProtoSchema(Vector(ProtoStructField("point", pt, false)))
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(protoSchema)
    val backToProto = ArrowSchemaConverter.fromArrowSchema(arrowSchema)
    assertEquals(backToProto.fields.head.dataType, pt)

  test("roundtrip complex schema"):
    val protoSchema = ProtoSchema(Vector(
      ProtoStructField("id", ProtoType.LongType, false),
      ProtoStructField("name", ProtoType.StringType, true),
      ProtoStructField("scores", ProtoType.ArrayType(ProtoType.DoubleType, true), false),
      ProtoStructField("metadata", ProtoType.MapType(ProtoType.StringType, ProtoType.StringType, true), true),
      ProtoStructField("created", ProtoType.TimestampType, false)
    ))

    val arrowSchema = ArrowSchemaConverter.toArrowSchema(protoSchema)
    val backToProto = ArrowSchemaConverter.fromArrowSchema(arrowSchema)

    assertEquals(backToProto.fields.size, protoSchema.fields.size)
    for (original, converted) <- protoSchema.fields.zip(backToProto.fields) do
      assertEquals(converted.name, original.name)
      assertEquals(converted.dataType, original.dataType)
      assertEquals(converted.nullable, original.nullable)
