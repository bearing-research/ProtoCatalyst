package protocatalyst.encoder.spark

import java.time.{Instant, LocalDate, LocalTime}

import munit.FunSuite
import org.apache.spark.sql.types.{
  ArrayType => SparkArrayType,
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

/** Smoke test for `InternalRowSerializer`. Verifies:
  *  - The Spark-typed schema matches what `ExpressionEncoder[T]` would build.
  *  - The serialized `InternalRow` slots contain Spark's canonical internal types
  *    (`UTF8String`, `Decimal`, epoch days/micros, `GenericArrayData`).
  *  - Round-trip `T → InternalRow → T` returns the original value.
  *
  * Byte-level parity with Spark's `ExpressionEncoder` lives in the spark-catalyst module's
  * parity suite (task #4) — `ExpressionEncoder` uses Scala 2 `TypeTag` and can't be constructed
  * from Scala 3 directly.
  */
class InternalRowSerializerSpec extends FunSuite:

  // === Simple flat case class ===

  case class Simple(id: Int, name: String)

  test("simple: schema matches Spark layout"):
    val ser = InternalRowSerializer.derived[Simple]
    val expected = new SparkStructType()
      .add("id", SparkIntegerType, nullable = false)
      .add("name", SparkStringType, nullable = false)
    assertEquals(ser.sparkSchema, expected)

  test("simple: serialized slots use Spark internal types"):
    val ser = InternalRowSerializer.derived[Simple]
    val row = ser.serialize(Simple(42, "alice"))
    assertEquals(row.getInt(0), 42)
    assertEquals(row.getUTF8String(1), UTF8String.fromString("alice"))

  test("simple: roundtrip"):
    val ser = InternalRowSerializer.derived[Simple]
    val original = Simple(42, "alice")
    val restored = ser.deserialize(ser.serialize(original))
    assertEquals(restored, original)

  // === Decimal + temporal types — the TPC-H-critical bits ===

  case class Lineitem(orderkey: Long, quantity: BigDecimal, shipdate: LocalDate, ts: Instant)

  test("temporal/decimal: schema matches"):
    val ser = InternalRowSerializer.derived[Lineitem]
    val expected = new SparkStructType()
      .add("orderkey", SparkLongType, nullable = false)
      .add("quantity", SparkDecimalType(38, 18), nullable = false)
      .add("shipdate", SparkDateType, nullable = false)
      .add("ts", SparkTimestampType, nullable = false)
    assertEquals(ser.sparkSchema, expected)

  test("temporal/decimal: serialized slots have correct internal forms"):
    val ser = InternalRowSerializer.derived[Lineitem]
    val date = LocalDate.of(2026, 5, 26)
    val ts = Instant.parse("2026-05-26T15:00:00Z")
    val row = ser.serialize(Lineitem(1234L, BigDecimal("17.5"), date, ts))

    assertEquals(row.getLong(0), 1234L)
    val dec = row.getDecimal(1, 38, 18)
    assertEquals(dec, SparkDecimal(BigDecimal("17.5").bigDecimal, 38, 18))
    // DateType is epoch days as Int
    assert(row.getInt(2) > 0, s"expected positive epoch days, got ${row.getInt(2)}")
    // TimestampType is epoch micros as Long
    assertEquals(row.getLong(3), ts.toEpochMilli * 1000L)

  test("temporal/decimal: roundtrip"):
    val ser = InternalRowSerializer.derived[Lineitem]
    val original = Lineitem(
      1234L,
      BigDecimal("17.500000000000000000"),
      LocalDate.of(2026, 5, 26),
      Instant.parse("2026-05-26T15:00:00Z")
    )
    val restored = ser.deserialize(ser.serialize(original))
    assertEquals(restored, original)

  // === Option / null handling ===

  case class WithOption(id: Int, label: Option[String])

  test("option: None becomes null slot"):
    val ser = InternalRowSerializer.derived[WithOption]
    val row = ser.serialize(WithOption(1, None))
    assertEquals(row.getInt(0), 1)
    assert(row.isNullAt(1))

  test("option: Some round-trips"):
    val ser = InternalRowSerializer.derived[WithOption]
    val original = WithOption(7, Some("seven"))
    val restored = ser.deserialize(ser.serialize(original))
    assertEquals(restored, original)

  test("option: None round-trips"):
    val ser = InternalRowSerializer.derived[WithOption]
    val original = WithOption(7, None)
    val restored = ser.deserialize(ser.serialize(original))
    assertEquals(restored, original)

  // === Collection ===

  case class WithList(id: Int, tags: List[String])

  test("list: schema matches Spark ArrayType layout"):
    val ser = InternalRowSerializer.derived[WithList]
    // ArrayType(StringType, containsNull = false-ish — depends on element encoder's nullability)
    val sparkArr = ser.sparkSchema.fields(1).dataType.asInstanceOf[SparkArrayType]
    assertEquals(sparkArr.elementType, SparkStringType)

  test("list: serialized slot is GenericArrayData of UTF8Strings"):
    val ser = InternalRowSerializer.derived[WithList]
    val row = ser.serialize(WithList(1, List("a", "b", "c")))
    val arr = row.getArray(1)
    assertEquals(arr.numElements(), 3)
    assertEquals(arr.getUTF8String(0).toString, "a")
    assertEquals(arr.getUTF8String(1).toString, "b")
    assertEquals(arr.getUTF8String(2).toString, "c")

  test("list: roundtrip"):
    val ser = InternalRowSerializer.derived[WithList]
    val original = WithList(1, List("a", "b", "c"))
    val restored = ser.deserialize(ser.serialize(original))
    // Round-trip may go through Vector via fromInternal; compare element-wise to be tolerant.
    assertEquals(restored.id, original.id)
    assertEquals(restored.tags, original.tags)

  // === LocalTime (Spark 4.1 only — closes the only "ProtoCatalyst ahead of Spark" temporal gap) ===

  case class WithTime(id: Int, opened: LocalTime)

  test("LocalTime: schema uses Spark TimeType(6)"):
    val ser = InternalRowSerializer.derived[WithTime]
    val expected = new SparkStructType()
      .add("id", SparkIntegerType, nullable = false)
      .add("opened", SparkTimeType(6), nullable = false)
    assertEquals(ser.sparkSchema, expected)

  test("LocalTime: serialized slot is nanos since midnight"):
    val ser = InternalRowSerializer.derived[WithTime]
    val time = LocalTime.of(9, 30, 0)
    val row = ser.serialize(WithTime(1, time))
    assertEquals(row.getLong(1), time.toNanoOfDay)

  test("LocalTime: roundtrip"):
    val ser = InternalRowSerializer.derived[WithTime]
    val original = WithTime(1, LocalTime.of(9, 30, 15, 123_000_000))
    val restored = ser.deserialize(ser.serialize(original))
    assertEquals(restored, original)
