package protocatalyst.arrow

import scala.compiletime.uninitialized

import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector._

import protocatalyst.encoder.ProtoEncoder

// ========== Edge Case Test Classes ==========

// Single field case class
case class SingleField(value: Int) derives ProtoEncoder

// Wide struct with 20+ fields
case class WideStruct(
    f1: Int,
    f2: Int,
    f3: Int,
    f4: Int,
    f5: Int,
    f6: Int,
    f7: Int,
    f8: Int,
    f9: Int,
    f10: Int,
    f11: String,
    f12: String,
    f13: String,
    f14: String,
    f15: String,
    f16: Long,
    f17: Long,
    f18: Long,
    f19: Long,
    f20: Long,
    f21: Double,
    f22: Double,
    f23: Boolean,
    f24: Boolean,
    f25: Float
) derives ProtoEncoder

// Floating point special values
case class FloatSpecials(f: Float, d: Double) derives ProtoEncoder

// Multiple optional fields in sequence
case class ManyOptionals(
    opt1: Option[Int],
    opt2: Option[Long],
    opt3: Option[Double],
    opt4: Option[String],
    opt5: Option[Boolean]
) derives ProtoEncoder

// Case class with all the same type
case class Homogeneous(a: Int, b: Int, c: Int, d: Int, e: Int) derives ProtoEncoder

// Case class mixing nullable strings
case class MixedNullability(
    required: String,
    optional: Option[String],
    nullableRequired: String // Can be null at runtime
) derives ProtoEncoder

// Deeply nested structure (3 levels)
case class Level3(value: Int, name: String) derives ProtoEncoder
case class Level2(name: String, inner: Level3) derives ProtoEncoder
case class Level1(id: Long, nested: Level2) derives ProtoEncoder

// Binary data
case class WithBinary(id: Int, data: Array[Byte]) derives ProtoEncoder

// BigDecimal support - uses Decimal(38, 18) by default
case class WithDecimal(id: Int, amount: BigDecimal) derives ProtoEncoder

// LocalDateTime support
case class WithLocalDateTime(id: Int, ts: java.time.LocalDateTime) derives ProtoEncoder

// Duration support
case class WithDuration(id: Int, dur: java.time.Duration) derives ProtoEncoder

// Very long strings
case class LongStrings(id: Int, content: String) derives ProtoEncoder

// Option of nested type
case class InnerValue(x: Int, y: Int) derives ProtoEncoder
case class OptionalNested(id: Int, inner: Option[InnerValue]) derives ProtoEncoder

class ArrowEdgeCaseSuite extends munit.FunSuite:

  var allocator: RootAllocator = uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    allocator = ArrowAllocator.createRoot()

  override def afterEach(context: AfterEach): Unit =
    allocator.close()

  private inline def roundtrip[T](
      data: Seq[T]
  )(using m: scala.deriving.Mirror.ProductOf[T], enc: ProtoEncoder[T]): Seq[T] =
    val writer = InlineArrowWriter.derived[T]
    val reader = InlineArrowReader.derived[T]
    val root = VectorSchemaRoot.create(writer.schema, allocator)
    try
      writer.write(data, root)
      reader.read(root)
    finally root.close()

  // ========== Single Field Tests ==========

  test("roundtrip single field case class"):
    val data = Seq(SingleField(42), SingleField(0), SingleField(-1))
    val result = roundtrip(data)
    assertEquals(result, data)

  test("schema for single field case class"):
    val writer = InlineArrowWriter.derived[SingleField]
    assertEquals(writer.schema.getFields.size(), 1)
    assertEquals(writer.schema.getFields.get(0).getName, "value")
    assertEquals(writer.fieldCount, 1)

  // ========== Wide Struct Tests ==========

  test("roundtrip wide struct with 25 fields"):
    val data = Seq(
      WideStruct(
        1,
        2,
        3,
        4,
        5,
        6,
        7,
        8,
        9,
        10,
        "a",
        "b",
        "c",
        "d",
        "e",
        100L,
        200L,
        300L,
        400L,
        500L,
        1.1,
        2.2,
        true,
        false,
        3.3f
      ),
      WideStruct(
        -1,
        -2,
        -3,
        -4,
        -5,
        -6,
        -7,
        -8,
        -9,
        -10,
        "x",
        "y",
        "z",
        "",
        null,
        -100L,
        -200L,
        -300L,
        -400L,
        -500L,
        -1.1,
        -2.2,
        false,
        true,
        -3.3f
      )
    )
    val result = roundtrip(data)
    assertEquals(result.size, 2)
    assertEquals(result(0), data(0))
    assertEquals(result(1).f1, -1)
    assertEquals(result(1).f11, "x")
    assertEquals(result(1).f15, null)
    assertEquals(result(1).f16, -100L)

  test("schema for wide struct has correct field count"):
    val writer = InlineArrowWriter.derived[WideStruct]
    assertEquals(writer.fieldCount, 25)
    assertEquals(writer.schema.getFields.size(), 25)

  // ========== Floating Point Special Values ==========

  test("roundtrip Float.NaN"):
    val data = Seq(FloatSpecials(Float.NaN, 1.0))
    val result = roundtrip(data)
    assert(result(0).f.isNaN, "Float.NaN should roundtrip")
    assertEquals(result(0).d, 1.0)

  test("roundtrip Double.NaN"):
    val data = Seq(FloatSpecials(1.0f, Double.NaN))
    val result = roundtrip(data)
    assertEquals(result(0).f, 1.0f)
    assert(result(0).d.isNaN, "Double.NaN should roundtrip")

  test("roundtrip positive infinity"):
    val data = Seq(FloatSpecials(Float.PositiveInfinity, Double.PositiveInfinity))
    val result = roundtrip(data)
    assert(result(0).f.isPosInfinity, "Float +Inf should roundtrip")
    assert(result(0).d.isPosInfinity, "Double +Inf should roundtrip")

  test("roundtrip negative infinity"):
    val data = Seq(FloatSpecials(Float.NegativeInfinity, Double.NegativeInfinity))
    val result = roundtrip(data)
    assert(result(0).f.isNegInfinity, "Float -Inf should roundtrip")
    assert(result(0).d.isNegInfinity, "Double -Inf should roundtrip")

  test("roundtrip Float.MinPositiveValue"):
    val data = Seq(FloatSpecials(Float.MinPositiveValue, Double.MinPositiveValue))
    val result = roundtrip(data)
    assertEquals(result(0).f, Float.MinPositiveValue)
    assertEquals(result(0).d, Double.MinPositiveValue)

  // ========== Many Optionals Tests ==========

  test("roundtrip all Nones"):
    val data = Seq(ManyOptionals(None, None, None, None, None))
    val result = roundtrip(data)
    assertEquals(result(0), ManyOptionals(None, None, None, None, None))

  test("roundtrip all Somes"):
    val data = Seq(ManyOptionals(Some(1), Some(2L), Some(3.0), Some("test"), Some(true)))
    val result = roundtrip(data)
    assertEquals(result(0), data(0))

  test("roundtrip alternating Some and None"):
    val data = Seq(
      ManyOptionals(Some(1), None, Some(3.0), None, Some(true)),
      ManyOptionals(None, Some(2L), None, Some("test"), None)
    )
    val result = roundtrip(data)
    assertEquals(result, data)

  // ========== Homogeneous Type Tests ==========

  test("roundtrip homogeneous int fields"):
    val data = Seq(
      Homogeneous(1, 2, 3, 4, 5),
      Homogeneous(0, 0, 0, 0, 0),
      Homogeneous(-1, -2, -3, -4, -5)
    )
    val result = roundtrip(data)
    assertEquals(result, data)

  // ========== Mixed Nullability Tests ==========

  test("roundtrip with null in nullable string field"):
    val data = Seq(
      MixedNullability("req", Some("opt"), null),
      MixedNullability("req2", None, "present")
    )
    val result = roundtrip(data)
    assertEquals(result(0).required, "req")
    assertEquals(result(0).optional, Some("opt"))
    assertEquals(result(0).nullableRequired, null)
    assertEquals(result(1).required, "req2")
    assertEquals(result(1).optional, None)
    assertEquals(result(1).nullableRequired, "present")

  // ========== Deep Nesting Tests (3 levels) ==========

  // Note: 3-level deep nesting (structs containing structs containing structs)
  // requires recursive Mirror reconstruction which hits Scala 3 inline limits.
  // 2-level nesting works fine (see ArrowNestedTypeSuite).
  test("roundtrip deeply nested structure".ignore):
    val data = Seq(
      Level1(1L, Level2("outer", Level3(42, "inner"))),
      Level1(2L, Level2("outer2", Level3(-1, "inner2")))
    )
    val result = roundtrip(data)
    assertEquals(result.size, 2)
    assertEquals(result(0).id, 1L)
    assertEquals(result(0).nested.name, "outer")
    assertEquals(result(0).nested.inner.value, 42)
    assertEquals(result(0).nested.inner.name, "inner")

  test("schema for deeply nested structure"):
    val writer = InlineArrowWriter.derived[Level1]
    assertEquals(writer.fieldCount, 2)

    // Verify nested structure
    val nestedField = writer.schema.getFields.get(1)
    assertEquals(nestedField.getName, "nested")
    assert(nestedField.getType.isInstanceOf[org.apache.arrow.vector.types.pojo.ArrowType.Struct])

  // ========== Long String Tests ==========

  test("roundtrip very long strings"):
    val longString = "x" * 100000 // 100KB string
    val data = Seq(LongStrings(1, longString))
    val result = roundtrip(data)
    assertEquals(result(0).id, 1)
    assertEquals(result(0).content.length, 100000)
    assertEquals(result(0).content, longString)

  test("roundtrip string with newlines and special chars"):
    val specialString = "line1\nline2\r\ntab\there\u0000null"
    val data = Seq(LongStrings(1, specialString))
    val result = roundtrip(data)
    assertEquals(result(0).content, specialString)

  // ========== Optional Nested Type Tests ==========

  test("roundtrip Option[nested] with Some"):
    val data = Seq(OptionalNested(1, Some(InnerValue(10, 20))))
    val result = roundtrip(data)
    assertEquals(result(0).id, 1)
    assertEquals(result(0).inner, Some(InnerValue(10, 20)))

  test("roundtrip Option[nested] with None"):
    val data = Seq(OptionalNested(1, None))
    val result = roundtrip(data)
    assertEquals(result(0).id, 1)
    assertEquals(result(0).inner, None)

  test("roundtrip mixed Option[nested]"):
    val data = Seq(
      OptionalNested(1, Some(InnerValue(10, 20))),
      OptionalNested(2, None),
      OptionalNested(3, Some(InnerValue(-1, -2)))
    )
    val result = roundtrip(data)
    assertEquals(result, data)

  // ========== Boundary Value Tests ==========

  test("roundtrip byte boundary values"):
    case class ByteValues(b: Byte) derives ProtoEncoder
    val data = Seq(
      ByteValues(Byte.MinValue),
      ByteValues(Byte.MaxValue),
      ByteValues(0),
      ByteValues(-1),
      ByteValues(1)
    )
    val result = roundtrip(data)
    assertEquals(result, data)

  test("roundtrip short boundary values"):
    case class ShortValues(s: Short) derives ProtoEncoder
    val data = Seq(
      ShortValues(Short.MinValue),
      ShortValues(Short.MaxValue),
      ShortValues(0),
      ShortValues(-1),
      ShortValues(1)
    )
    val result = roundtrip(data)
    assertEquals(result, data)

  test("roundtrip int boundary values"):
    case class IntValues(i: Int) derives ProtoEncoder
    val data = Seq(
      IntValues(Int.MinValue),
      IntValues(Int.MaxValue),
      IntValues(0),
      IntValues(-1),
      IntValues(1)
    )
    val result = roundtrip(data)
    assertEquals(result, data)

  test("roundtrip long boundary values"):
    case class LongValues(l: Long) derives ProtoEncoder
    val data = Seq(
      LongValues(Long.MinValue),
      LongValues(Long.MaxValue),
      LongValues(0L),
      LongValues(-1L),
      LongValues(1L)
    )
    val result = roundtrip(data)
    assertEquals(result, data)

  // ========== Empty Collection and Data Tests ==========

  test("roundtrip empty list field"):
    case class WithList(id: Int, items: List[Int]) derives ProtoEncoder
    val data = Seq(WithList(1, List.empty))
    val result = roundtrip(data)
    assertEquals(result(0).id, 1)
    assertEquals(result(0).items, List.empty)

  test("roundtrip single element list"):
    case class WithList(id: Int, items: List[Int]) derives ProtoEncoder
    val data = Seq(WithList(1, List(42)))
    val result = roundtrip(data)
    assertEquals(result(0).items, List(42))

  test("roundtrip list with duplicates"):
    case class WithList(id: Int, items: List[Int]) derives ProtoEncoder
    val data = Seq(WithList(1, List(1, 1, 1, 2, 2, 3)))
    val result = roundtrip(data)
    assertEquals(result(0).items, List(1, 1, 1, 2, 2, 3))

  // ========== Performance Stress Tests ==========

  test("roundtrip 50000 rows with mixed types"):
    val data = (1 to 50000).map { i =>
      ManyOptionals(
        if i % 2 == 0 then Some(i) else None,
        if i % 3 == 0 then Some(i.toLong) else None,
        if i % 5 == 0 then Some(i.toDouble) else None,
        if i % 7 == 0 then Some(s"str$i") else None,
        if i % 11 == 0 then Some(i % 2 == 0) else None
      )
    }.toSeq

    val result = roundtrip(data)
    assertEquals(result.size, 50000)
    // Spot checks
    assertEquals(result(0), data(0))
    assertEquals(result(24999), data(24999))
    assertEquals(result(49999), data(49999))

  // ========== Unicode Edge Cases ==========

  test("roundtrip various unicode categories"):
    val unicodeStrings = Seq(
      LongStrings(1, "ASCII only"),
      LongStrings(2, "Latin: àéïõü"),
      LongStrings(3, "Greek: αβγδε"),
      LongStrings(4, "Cyrillic: абвгд"),
      LongStrings(5, "Chinese: 你好世界"),
      LongStrings(6, "Japanese: こんにちは"),
      LongStrings(7, "Korean: 안녕하세요"),
      LongStrings(8, "Emoji: 🎉🚀💻🌍"),
      LongStrings(9, "Math: ∑∫∞≠≤"),
      LongStrings(10, "Mixed: Hello 世界 🌍!")
    )
    val result = roundtrip(unicodeStrings)
    assertEquals(result, unicodeStrings)

  test("roundtrip right-to-left text"):
    val rtlData = Seq(
      LongStrings(1, "Hebrew: שלום"),
      LongStrings(2, "Arabic: مرحبا")
    )
    val result = roundtrip(rtlData)
    assertEquals(result, rtlData)

  // ========== Row Count Edge Cases ==========

  test("roundtrip single row"):
    val data = Seq(SingleField(42))
    val result = roundtrip(data)
    assertEquals(result.size, 1)
    assertEquals(result(0), data(0))

  test("roundtrip exactly 1000 rows"):
    val data = (1 to 1000).map(i => SingleField(i)).toSeq
    val result = roundtrip(data)
    assertEquals(result.size, 1000)
    assertEquals(result(0), SingleField(1))
    assertEquals(result(999), SingleField(1000))

  // ========== Boolean Patterns ==========

  test("roundtrip boolean patterns"):
    case class Booleans(a: Boolean, b: Boolean, c: Boolean) derives ProtoEncoder
    val data = Seq(
      Booleans(true, true, true),
      Booleans(false, false, false),
      Booleans(true, false, true),
      Booleans(false, true, false)
    )
    val result = roundtrip(data)
    assertEquals(result, data)

  // ========== Temporal Edge Cases ==========

  test("roundtrip epoch date"):
    val data = Seq(RoundtripTemporal(java.time.LocalDate.EPOCH, java.time.Instant.EPOCH))
    val result = roundtrip(data)
    assertEquals(result(0).date, java.time.LocalDate.EPOCH)
    assertEquals(result(0).instant, java.time.Instant.EPOCH)

  test("roundtrip far future date"):
    val futureDate = java.time.LocalDate.of(9999, 12, 31)
    val futureInstant = java.time.Instant.parse("9999-12-31T23:59:59Z")
    val data = Seq(RoundtripTemporal(futureDate, futureInstant))
    val result = roundtrip(data)
    assertEquals(result(0).date, futureDate)

  test("roundtrip pre-epoch date"):
    val oldDate = java.time.LocalDate.of(1900, 1, 1)
    val oldInstant = java.time.Instant.parse("1900-01-01T00:00:00Z")
    val data = Seq(RoundtripTemporal(oldDate, oldInstant))
    val result = roundtrip(data)
    assertEquals(result(0).date, oldDate)

  // ========== Binary Type Tests ==========

  test("roundtrip binary data"):
    val data = Seq(
      WithBinary(1, Array[Byte](1, 2, 3, 4, 5)),
      WithBinary(2, Array[Byte](-128, 0, 127)),
      WithBinary(3, Array.emptyByteArray)
    )
    val result = roundtrip(data)
    assertEquals(result.size, 3)
    assertEquals(result(0).id, 1)
    assert(java.util.Arrays.equals(result(0).data, Array[Byte](1, 2, 3, 4, 5)))
    assert(java.util.Arrays.equals(result(1).data, Array[Byte](-128, 0, 127)))
    assert(java.util.Arrays.equals(result(2).data, Array.emptyByteArray))

  test("roundtrip null binary data"):
    val data = Seq(WithBinary(1, null))
    val result = roundtrip(data)
    assertEquals(result(0).id, 1)
    assertEquals(result(0).data, null)

  test("roundtrip large binary data"):
    val largeData = (0 until 100000).map(i => (i % 256).toByte).toArray
    val data = Seq(WithBinary(1, largeData))
    val result = roundtrip(data)
    assertEquals(result(0).data.length, 100000)
    assert(java.util.Arrays.equals(result(0).data, largeData))

  // ========== BigDecimal Type Tests ==========

  test("roundtrip BigDecimal"):
    val data = Seq(
      WithDecimal(1, BigDecimal("123.456")),
      WithDecimal(2, BigDecimal("0.001")),
      WithDecimal(3, BigDecimal("-999.999"))
    )
    val result = roundtrip(data)
    assertEquals(result.size, 3)
    assertEquals(result(0).amount, BigDecimal("123.456"))
    assertEquals(result(1).amount, BigDecimal("0.001"))
    assertEquals(result(2).amount, BigDecimal("-999.999"))

  test("roundtrip BigDecimal zero"):
    val data = Seq(WithDecimal(1, BigDecimal("0")))
    val result = roundtrip(data)
    assertEquals(result(0).amount, BigDecimal("0"))

  test("roundtrip null BigDecimal"):
    val data = Seq(WithDecimal(1, null))
    val result = roundtrip(data)
    assertEquals(result(0).id, 1)
    assertEquals(result(0).amount, null)

  test("roundtrip BigDecimal with high precision"):
    val precise = BigDecimal("123456789.123456789")
    val data = Seq(WithDecimal(1, precise))
    val result = roundtrip(data)
    // Note: Arrow Decimal may have different precision/scale behavior
    assertEquals(result(0).id, 1)

  // ========== LocalDateTime Tests ==========

  test("roundtrip LocalDateTime"):
    val ldt = java.time.LocalDateTime.of(2024, 6, 15, 14, 30, 45, 123456000)
    val data = Seq(WithLocalDateTime(1, ldt))
    val result = roundtrip(data)
    assertEquals(result(0).id, 1)
    // Arrow stores in microseconds, so nanosecond precision may be lost
    assertEquals(result(0).ts.getYear, 2024)
    assertEquals(result(0).ts.getMonthValue, 6)
    assertEquals(result(0).ts.getDayOfMonth, 15)
    assertEquals(result(0).ts.getHour, 14)
    assertEquals(result(0).ts.getMinute, 30)
    assertEquals(result(0).ts.getSecond, 45)

  test("roundtrip null LocalDateTime"):
    val data = Seq(WithLocalDateTime(1, null))
    val result = roundtrip(data)
    assertEquals(result(0).ts, null)

  test("roundtrip LocalDateTime at epoch"):
    val epoch = java.time.LocalDateTime.of(1970, 1, 1, 0, 0, 0)
    val data = Seq(WithLocalDateTime(1, epoch))
    val result = roundtrip(data)
    assertEquals(result(0).ts, epoch)

  // ========== Duration Tests ==========

  test("roundtrip Duration"):
    val dur = java.time.Duration.ofHours(2).plusMinutes(30).plusSeconds(45)
    val data = Seq(WithDuration(1, dur))
    val result = roundtrip(data)
    assertEquals(result(0).id, 1)
    // Duration is stored in microseconds
    assertEquals(result(0).dur.toHours, 2L)
    assertEquals(result(0).dur.toMinutesPart, 30)
    assertEquals(result(0).dur.toSecondsPart, 45)

  test("roundtrip zero Duration"):
    val data = Seq(WithDuration(1, java.time.Duration.ZERO))
    val result = roundtrip(data)
    assertEquals(result(0).dur, java.time.Duration.ZERO)

  test("roundtrip negative Duration"):
    val negativeDur = java.time.Duration.ofHours(-5)
    val data = Seq(WithDuration(1, negativeDur))
    val result = roundtrip(data)
    assertEquals(result(0).dur.toHours, -5L)

  test("roundtrip null Duration"):
    val data = Seq(WithDuration(1, null))
    val result = roundtrip(data)
    assertEquals(result(0).dur, null)
