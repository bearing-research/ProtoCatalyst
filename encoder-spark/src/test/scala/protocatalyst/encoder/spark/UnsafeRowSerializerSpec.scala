package protocatalyst.encoder.spark

import java.time.{Instant, LocalDate, LocalTime}

import munit.FunSuite
import org.apache.spark.sql.types.{
  DateType => SparkDateType,
  DecimalType => SparkDecimalType,
  IntegerType => SparkIntegerType,
  LongType => SparkLongType,
  StringType => SparkStringType,
  StructType => SparkStructType,
  TimeType => SparkTimeType,
  TimestampType => SparkTimestampType,
  Decimal => SparkDecimal
}
import org.apache.spark.unsafe.types.UTF8String

/** Verifies that `UnsafeRowSerializer` writes the same packed byte layout Spark's whole-stage
  * codegen produces. The byte-for-byte comparison against `ExpressionEncoder[T]` lives in the
  * spark-catalyst parity suite (task #4); these tests focus on the writer mechanics and the
  * round-trip contract.
  */
class UnsafeRowSerializerSpec extends FunSuite:

  // === Schema parity (same as InternalRowSerializer, but a guardrail) ===

  case class Simple(id: Int, name: String)

  test("simple: sparkSchema matches expected layout"):
    val ser = UnsafeRowSerializer.derived[Simple]
    val expected = new SparkStructType()
      .add("id", SparkIntegerType, nullable = false)
      .add("name", SparkStringType, nullable = false)
    assertEquals(ser.sparkSchema, expected)

  // === Simple primitives + String ===

  test("simple: serialize fills slots and read-back matches"):
    val ser = UnsafeRowSerializer.derived[Simple]
    val row = ser.serialize(Simple(42, "alice"))
    assertEquals(row.numFields, 2)
    assertEquals(row.getInt(0), 42)
    assertEquals(row.getUTF8String(1), UTF8String.fromString("alice"))
    assert(!row.isNullAt(0))
    assert(!row.isNullAt(1))

  test("simple: roundtrip"):
    val ser = UnsafeRowSerializer.derived[Simple]
    val original = Simple(42, "alice")
    val restored = ser.deserialize(ser.serialize(original))
    assertEquals(restored, original)

  test("simple: packed row size is reasonable (no wasted space)"):
    val ser = UnsafeRowSerializer.derived[Simple]
    val row = ser.serialize(Simple(42, "alice"))
    // 8B null bitmask + 8B int slot + 8B string offset/size slot + 5B 'alice' bytes (padded to 8)
    // = 32 bytes total. UnsafeRow may pad to 8-byte multiples; assert a tight upper bound.
    assert(
      row.getSizeInBytes <= 40,
      s"expected packed row <= 40 bytes, got ${row.getSizeInBytes}"
    )

  // === Decimal + temporal — the TPC-H-critical bits ===

  case class Lineitem(orderkey: Long, quantity: BigDecimal, shipdate: LocalDate, ts: Instant)

  test("temporal/decimal: schema matches"):
    val ser = UnsafeRowSerializer.derived[Lineitem]
    val expected = new SparkStructType()
      .add("orderkey", SparkLongType, nullable = false)
      .add("quantity", SparkDecimalType(38, 18), nullable = false)
      .add("shipdate", SparkDateType, nullable = false)
      .add("ts", SparkTimestampType, nullable = false)
    assertEquals(ser.sparkSchema, expected)

  test("temporal/decimal: serialize writes correct internal forms"):
    val ser = UnsafeRowSerializer.derived[Lineitem]
    val date = LocalDate.of(2026, 5, 26)
    val ts = Instant.parse("2026-05-26T15:00:00Z")
    val row = ser.serialize(Lineitem(1234L, BigDecimal("17.5"), date, ts))

    assertEquals(row.getLong(0), 1234L)
    assertEquals(row.getDecimal(1, 38, 18), SparkDecimal(BigDecimal("17.5").bigDecimal, 38, 18))
    assertEquals(row.getInt(2), date.toEpochDay.toInt)
    assertEquals(row.getLong(3), ts.toEpochMilli * 1000L)

  test("temporal/decimal: roundtrip"):
    val ser = UnsafeRowSerializer.derived[Lineitem]
    val original = Lineitem(
      1234L,
      BigDecimal("17.500000000000000000"),
      LocalDate.of(2026, 5, 26),
      Instant.parse("2026-05-26T15:00:00Z")
    )
    val restored = ser.deserialize(ser.serialize(original))
    assertEquals(restored, original)

  // === Option / null handling — UnsafeRow null bitmask is the critical piece ===

  case class WithOption(id: Int, label: Option[String])

  test("option: None sets the null bit"):
    val ser = UnsafeRowSerializer.derived[WithOption]
    val row = ser.serialize(WithOption(1, None))
    assertEquals(row.getInt(0), 1)
    assert(row.isNullAt(1), "expected null bit set for None")

  test("option: Some round-trips"):
    val ser = UnsafeRowSerializer.derived[WithOption]
    val restored = ser.deserialize(ser.serialize(WithOption(7, Some("seven"))))
    assertEquals(restored, WithOption(7, Some("seven")))

  test("option: None round-trips"):
    val ser = UnsafeRowSerializer.derived[WithOption]
    val restored = ser.deserialize(ser.serialize(WithOption(7, None)))
    assertEquals(restored, WithOption(7, None))

  // Note: `Option[String](null)` produces `Some(null)`, not `None`. Spark's codegen serializes
  // the null with isNullAt set. We defer asserting that exact semantics to the parity suite
  // (#4), where we compare against `ExpressionEncoder` byte-for-byte.

  // === LocalTime (Spark 4.1+) ===

  case class WithTime(id: Int, opened: LocalTime)

  test("LocalTime: schema uses Spark TimeType(6)"):
    val ser = UnsafeRowSerializer.derived[WithTime]
    val expected = new SparkStructType()
      .add("id", SparkIntegerType, nullable = false)
      .add("opened", SparkTimeType(6), nullable = false)
    assertEquals(ser.sparkSchema, expected)

  test("LocalTime: nanos since midnight in slot"):
    val ser = UnsafeRowSerializer.derived[WithTime]
    val time = LocalTime.of(9, 30, 0)
    val row = ser.serialize(WithTime(1, time))
    assertEquals(row.getLong(1), time.toNanoOfDay)

  test("LocalTime: roundtrip"):
    val ser = UnsafeRowSerializer.derived[WithTime]
    val original = WithTime(1, LocalTime.of(9, 30, 15, 123_000_000))
    val restored = ser.deserialize(ser.serialize(original))
    assertEquals(restored, original)

  // === Writer reuse — the alloc-free hot path ===

  test("writeTo: reusing the same writer across rows produces independent UnsafeRows"):
    val ser = UnsafeRowSerializer.derived[Simple]
    val writer = ser.newWriter()

    val row1 = ser.writeTo(writer, Simple(1, "one"))
    // Capture the bytes before reusing the buffer — `row1` will share the writer's backing
    // storage and get clobbered when we call writeTo again. So copy now.
    val copy1 = row1.copy()

    val row2 = ser.writeTo(writer, Simple(2, "two"))
    val copy2 = row2.copy()

    assertEquals(copy1.getInt(0), 1)
    assertEquals(copy1.getUTF8String(1).toString, "one")
    assertEquals(copy2.getInt(0), 2)
    assertEquals(copy2.getUTF8String(1).toString, "two")

  // === Failure mode for not-yet-supported types ===

  case class HasList(id: Int, tags: List[String])

  test("unsupported field types throw with a clear message"):
    val ser = UnsafeRowSerializer.derived[HasList]
    val caught = intercept[IllegalStateException]:
      ser.serialize(HasList(1, List("a")))
    assert(
      caught.getMessage.contains("UnsafeRowSerializer"),
      s"expected guidance message, got: ${caught.getMessage}"
    )
