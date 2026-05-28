package protocatalyst.encoder.spark.arrow

import java.{sql => jsql}
import java.math.{BigDecimal => JBigDecimal}
import java.time.{Duration, Instant, LocalDate, LocalDateTime, LocalTime, Period}

import scala.jdk.CollectionConverters.*

import munit.FunSuite
import org.apache.arrow.vector.types.{DateUnit, FloatingPointPrecision, IntervalUnit, TimeUnit}
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, Schema}

/** Macro-side smoke tests for [[ArrowSchemaBuilders]]. Per-variant Field types and nullability
  * are checked here against expected Arrow objects; the byte-for-byte parity against Spark's
  * `ArrowUtils.toArrowSchema(...)` (Schema serialized to IPC bytes) is covered separately by the
  * Phase A3 fixture-driven test.
  */
class ArrowSchemaBuildersSpec extends FunSuite:

  // ---------------------------------------------------------------------------
  // Primitive coverage — non-nullable unboxed cases.
  // ---------------------------------------------------------------------------

  case class AllPrimitives(
      b: Boolean,
      i8: Byte,
      i16: Short,
      i32: Int,
      i64: Long,
      f32: Float,
      f64: Double
  )

  test("primitives: 7 non-nullable fields with matching Arrow types"):
    val schema = ArrowSchemaBuilders.schemaFor[AllPrimitives]
    val fields = schema.getFields.asScala.toList
    assertEquals(fields.map(_.getName), List("b", "i8", "i16", "i32", "i64", "f32", "f64"))
    assert(fields.forall(!_.isNullable), "all unboxed primitives must be non-nullable")
    assertEquals(fields(0).getType, ArrowType.Bool.INSTANCE: ArrowType)
    assertEquals(fields(1).getType, new ArrowType.Int(8, true): ArrowType)
    assertEquals(fields(2).getType, new ArrowType.Int(16, true): ArrowType)
    assertEquals(fields(3).getType, new ArrowType.Int(32, true): ArrowType)
    assertEquals(fields(4).getType, new ArrowType.Int(64, true): ArrowType)
    assertEquals(
      fields(5).getType,
      new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE): ArrowType
    )
    assertEquals(
      fields(6).getType,
      new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE): ArrowType
    )

  // ---------------------------------------------------------------------------
  // Strings, binary, decimal — nullable reference types.
  // ---------------------------------------------------------------------------

  case class Strings(s: String, b: Array[Byte])

  test("String + Binary default (Utf8/Binary, both nullable)"):
    val schema = ArrowSchemaBuilders.schemaFor[Strings]
    val fields = schema.getFields.asScala.toList
    assert(fields.forall(_.isNullable))
    assertEquals(fields(0).getType, ArrowType.Utf8.INSTANCE: ArrowType)
    assertEquals(fields(1).getType, ArrowType.Binary.INSTANCE: ArrowType)

  test("String + Binary with largeVarTypes=true → LargeUtf8 / LargeBinary"):
    val schema = ArrowSchemaBuilders.schemaFor[Strings]("UTC", true)
    val fields = schema.getFields.asScala.toList
    assertEquals(fields(0).getType, ArrowType.LargeUtf8.INSTANCE: ArrowType)
    assertEquals(fields(1).getType, ArrowType.LargeBinary.INSTANCE: ArrowType)

  case class Decimals(s: BigDecimal, j: JBigDecimal)

  test("BigDecimal / JBigDecimal → Decimal(38, 18, 128)"):
    val schema = ArrowSchemaBuilders.schemaFor[Decimals]
    val expected: ArrowType = new ArrowType.Decimal(38, 18, 128)
    schema.getFields.asScala.foreach { f =>
      assert(f.isNullable)
      assertEquals(f.getType, expected)
    }

  // ---------------------------------------------------------------------------
  // Date/Time variants.
  // ---------------------------------------------------------------------------

  case class Temporal(
      d: LocalDate,
      sd: jsql.Date,
      i: Instant,
      st: jsql.Timestamp,
      ldt: LocalDateTime,
      lt: LocalTime,
      dur: Duration,
      per: Period
  )

  test("Temporal: all 8 variants map to expected Arrow types"):
    val schema = ArrowSchemaBuilders.schemaFor[Temporal]("America/New_York", false)
    val fields = schema.getFields.asScala.toList
    assert(fields.forall(_.isNullable))
    assertEquals(fields(0).getType, new ArrowType.Date(DateUnit.DAY): ArrowType)
    assertEquals(fields(1).getType, new ArrowType.Date(DateUnit.DAY): ArrowType)
    assertEquals(
      fields(2).getType,
      new ArrowType.Timestamp(TimeUnit.MICROSECOND, "America/New_York"): ArrowType
    )
    assertEquals(
      fields(3).getType,
      new ArrowType.Timestamp(TimeUnit.MICROSECOND, "America/New_York"): ArrowType
    )
    // LocalDateTime → TimestampNTZ → Timestamp(MICROSECOND, null)
    assertEquals(
      fields(4).getType,
      new ArrowType.Timestamp(TimeUnit.MICROSECOND, null.asInstanceOf[String]): ArrowType
    )
    assertEquals(fields(5).getType, new ArrowType.Time(TimeUnit.MICROSECOND, 64): ArrowType)
    // Spark's asymmetry: Duration → Arrow Duration, but Period → Arrow Interval(YEAR_MONTH).
    assertEquals(fields(6).getType, new ArrowType.Duration(TimeUnit.MICROSECOND): ArrowType)
    assertEquals(
      fields(7).getType,
      new ArrowType.Interval(IntervalUnit.YEAR_MONTH): ArrowType
    )

  // ---------------------------------------------------------------------------
  // Option — outer is nullable, inner Arrow type unchanged from the Option-stripped form.
  // ---------------------------------------------------------------------------

  case class Optional(oi: Option[Int], os: Option[String], od: Option[LocalDate])

  test("Option[T]: outer always nullable, inner type matches non-Option case"):
    val schema = ArrowSchemaBuilders.schemaFor[Optional]
    val fields = schema.getFields.asScala.toList
    assert(fields.forall(_.isNullable))
    assertEquals(fields(0).getType, new ArrowType.Int(32, true): ArrowType)
    assertEquals(fields(1).getType, ArrowType.Utf8.INSTANCE: ArrowType)
    assertEquals(fields(2).getType, new ArrowType.Date(DateUnit.DAY): ArrowType)

  // ---------------------------------------------------------------------------
  // Schema-level invariants: name preservation, field count, no children for leaf types.
  // ---------------------------------------------------------------------------

  case class Mixed(id: Long, label: String, price: BigDecimal, when: Option[Instant])

  test("Mixed: field count, names, and (no) children"):
    val schema = ArrowSchemaBuilders.schemaFor[Mixed]
    assertEquals(schema.getFields.size, 4)
    assertEquals(
      schema.getFields.asScala.toList.map(_.getName),
      List("id", "label", "price", "when")
    )
    schema.getFields.asScala.foreach { f =>
      assertEquals(f.getChildren.size, 0, s"${f.getName} should be a leaf field")
    }

  // ---------------------------------------------------------------------------
  // Schema IPC-roundtrip — Schema → bytes → Schema preserves all attributes.
  // This is an internal sanity check; cross-Spark byte parity is A3.
  // ---------------------------------------------------------------------------

  test("Schema IPC roundtrip: bytes → Schema preserves field types and nullability"):
    val schema = ArrowSchemaBuilders.schemaFor[Mixed]
    val bytes = schema.serializeAsMessage()
    val restored = Schema.deserializeMessage(java.nio.ByteBuffer.wrap(bytes))
    assertEquals(restored.getFields.size, schema.getFields.size)
    schema.getFields.asScala.zip(restored.getFields.asScala).foreach { case (a, b) =>
      assertEquals(b.getName, a.getName)
      assertEquals(b.isNullable, a.isNullable)
      assertEquals(b.getType, a.getType)
    }
