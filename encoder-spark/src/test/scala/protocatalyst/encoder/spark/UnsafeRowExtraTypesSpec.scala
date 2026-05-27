package protocatalyst.encoder.spark

import java.time.{Duration, Period}
import java.util.UUID

import munit.FunSuite
import org.apache.spark.sql.types.{
  IntegerType => SparkIntegerType,
  StringType => SparkStringType,
  StructType => SparkStructType,
  DayTimeIntervalType => SparkDayTimeIntervalType,
  YearMonthIntervalType => SparkYearMonthIntervalType
}

/** Coverage for types added in the P2 fix: Duration, Period, UUID. Each verifies schema match
  * (the Spark `DataType` we report agrees with `AgnosticEncoder` expectations) and end-to-end
  * round-trip through `UnsafeRowSerializer`.
  */
class UnsafeRowExtraTypesSpec extends FunSuite:

  // === Duration → DayTimeIntervalType (epoch micros Long) ===

  case class WithDuration(id: Int, span: Duration)

  test("Duration: schema matches Spark DayTimeIntervalType"):
    val ser = UnsafeRowSerializer.derived[WithDuration]
    val expected = new SparkStructType()
      .add("id", SparkIntegerType, nullable = false)
      .add("span", SparkDayTimeIntervalType(), nullable = false)
    assertEquals(ser.sparkSchema, expected)

  test("Duration: round-trip"):
    val ser = UnsafeRowSerializer.derived[WithDuration]
    val original = WithDuration(1, Duration.ofHours(36).plusMinutes(15))
    val restored = ser.deserialize(ser.serialize(original))
    assertEquals(restored, original)

  // === Period → YearMonthIntervalType (months Int) ===

  case class WithPeriod(id: Int, span: Period)

  test("Period: schema matches Spark YearMonthIntervalType"):
    val ser = UnsafeRowSerializer.derived[WithPeriod]
    val expected = new SparkStructType()
      .add("id", SparkIntegerType, nullable = false)
      .add("span", SparkYearMonthIntervalType(), nullable = false)
    assertEquals(ser.sparkSchema, expected)

  test("Period: round-trip"):
    val ser = UnsafeRowSerializer.derived[WithPeriod]
    val original = WithPeriod(1, Period.of(2, 3, 0))  // (years, months, days) — days dropped
    val restored = ser.deserialize(ser.serialize(original))
    // Period of (2y 3m 0d) → 27 months → Period.ofMonths(27) = (2y 3m). Roundtrip lossy on days
    // by design (YearMonthIntervalType stores months only).
    assertEquals(restored.span.toTotalMonths, original.span.toTotalMonths.toLong)

  // === UUID → StringType (canonical 36-char) ===

  case class WithUUID(id: Int, key: UUID)

  test("UUID: schema is StringType"):
    val ser = UnsafeRowSerializer.derived[WithUUID]
    val expected = new SparkStructType()
      .add("id", SparkIntegerType, nullable = false)
      .add("key", SparkStringType, nullable = false)
    assertEquals(ser.sparkSchema, expected)

  test("UUID: round-trip"):
    val ser = UnsafeRowSerializer.derived[WithUUID]
    val original = WithUUID(1, UUID.fromString("550e8400-e29b-41d4-a716-446655440000"))
    val restored = ser.deserialize(ser.serialize(original))
    assertEquals(restored, original)

  // === Option over the new types ===

  case class WithOptions(d: Option[Duration], p: Option[Period], u: Option[UUID])

  test("Option[Duration/Period/UUID]: Some round-trips"):
    val ser = UnsafeRowSerializer.derived[WithOptions]
    val original = WithOptions(
      Some(Duration.ofSeconds(42)),
      Some(Period.ofMonths(5)),
      Some(UUID.randomUUID())
    )
    val restored = ser.deserialize(ser.serialize(original))
    assertEquals(restored, original)

  test("Option[Duration/Period/UUID]: None round-trips"):
    val ser = UnsafeRowSerializer.derived[WithOptions]
    val original = WithOptions(None, None, None)
    val restored = ser.deserialize(ser.serialize(original))
    assertEquals(restored, original)

  // ============================================================
  // P3: generic case classes must substitute the applied type
  // ============================================================
  //
  // Before the fix, the macro read `vd.tpt.tpe` which preserved the type parameter `A` instead
  // of substituting `String` / `Long` / `BigDecimal`. `UnsafeRowSerializer.derived[Box[A]]`
  // would abort with "unsupported field type A at index 1". The fix uses
  // `tpe.memberType(fieldSym)` so applied types resolve correctly.

  case class Box[A](id: Int, value: A)

  test("Generic Box[String]: derives + round-trips"):
    val ser = UnsafeRowSerializer.derived[Box[String]]
    val original = Box(1, "hello")
    val restored = ser.deserialize(ser.serialize(original))
    assertEquals(restored, original)

  test("Generic Box[Long]: derives + round-trips (primitive substitution)"):
    val ser = UnsafeRowSerializer.derived[Box[Long]]
    val original = Box(2, 12345L)
    val restored = ser.deserialize(ser.serialize(original))
    assertEquals(restored, original)

  test("Generic Box[BigDecimal]: derives + round-trips"):
    val ser = UnsafeRowSerializer.derived[Box[BigDecimal]]
    val original = Box(3, BigDecimal("99.50"))
    val restored = ser.deserialize(ser.serialize(original))
    assertEquals(restored, original)

  case class Pair[A, B](first: A, second: B)

  test("Generic Pair[Int, String]: two-parameter substitution"):
    val ser = UnsafeRowSerializer.derived[Pair[Int, String]]
    val original = Pair(42, "world")
    val restored = ser.deserialize(ser.serialize(original))
    assertEquals(restored, original)
