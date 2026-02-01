package protocatalyst.arrow

import protocatalyst.encoder.ProtoEncoder
import org.apache.arrow.vector.*
import org.apache.arrow.memory.RootAllocator
import scala.compiletime.uninitialized

// Additional test case classes for roundtrip testing
case class RoundtripAllPrimitives(
    b: Boolean,
    by: Byte,
    s: Short,
    i: Int,
    l: Long,
    f: Float,
    d: Double,
    str: String
) derives ProtoEncoder

case class RoundtripWithOptions(
    optInt: Option[Int],
    optLong: Option[Long],
    optDouble: Option[Double],
    optString: Option[String]
) derives ProtoEncoder

case class RoundtripTemporal(
    date: java.time.LocalDate,
    instant: java.time.Instant
) derives ProtoEncoder

case class RoundtripMixed(
    id: Long,
    name: String,
    score: Option[Double],
    active: Boolean
) derives ProtoEncoder

class ArrowRoundtripSuite extends munit.FunSuite:

  // Allocator for all tests
  var allocator: RootAllocator = uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    allocator = ArrowAllocator.createRoot()

  override def afterEach(context: AfterEach): Unit =
    allocator.close()

  // === Helper for roundtrip testing ===
  private inline def roundtrip[T](
      data: Seq[T]
  )(using m: scala.deriving.Mirror.ProductOf[T], enc: ProtoEncoder[T]): Seq[T] =
    val writer = InlineArrowWriter.derived[T]
    val reader = InlineArrowReader.derived[T]
    val root = VectorSchemaRoot.create(writer.schema, allocator)
    try
      writer.write(data, root)
      reader.read(root)
    finally
      root.close()

  // === Primitive Roundtrip Tests ===

  test("roundtrip all primitive types"):
    val data = Seq(
      RoundtripAllPrimitives(true, 1, 2, 3, 4L, 5.5f, 6.6, "hello"),
      RoundtripAllPrimitives(false, -1, -2, -3, -4L, -5.5f, -6.6, "world"),
      RoundtripAllPrimitives(true, Byte.MaxValue, Short.MaxValue, Int.MaxValue, Long.MaxValue, Float.MaxValue, Double.MaxValue, "")
    )

    val result = roundtrip(data)

    assertEquals(result.size, data.size)
    for (original, read) <- data.zip(result) do
      assertEquals(read, original)

  test("roundtrip with null string"):
    val data = Seq(
      RoundtripAllPrimitives(true, 1, 2, 3, 4L, 5.5f, 6.6, null)
    )

    val result = roundtrip(data)

    assertEquals(result.size, 1)
    assertEquals(result(0).str, null)
    assertEquals(result(0).i, 3)

  // === Option Roundtrip Tests ===

  test("roundtrip with all Some values"):
    val data = Seq(
      RoundtripWithOptions(Some(42), Some(100L), Some(3.14), Some("test"))
    )

    val result = roundtrip(data)

    assertEquals(result(0), data(0))

  test("roundtrip with all None values"):
    val data = Seq(
      RoundtripWithOptions(None, None, None, None)
    )

    val result = roundtrip(data)

    assertEquals(result(0), RoundtripWithOptions(None, None, None, None))

  test("roundtrip with mixed Some and None"):
    val data = Seq(
      RoundtripWithOptions(Some(1), None, Some(2.0), None),
      RoundtripWithOptions(None, Some(2L), None, Some("hello")),
      RoundtripWithOptions(Some(3), Some(4L), None, None)
    )

    val result = roundtrip(data)

    assertEquals(result.size, 3)
    for (original, read) <- data.zip(result) do
      assertEquals(read, original)

  // === Temporal Roundtrip Tests ===

  test("roundtrip LocalDate"):
    val data = Seq(
      RoundtripTemporal(java.time.LocalDate.of(2024, 1, 15), java.time.Instant.parse("2024-01-15T10:30:00Z")),
      RoundtripTemporal(java.time.LocalDate.of(1970, 1, 1), java.time.Instant.EPOCH),
      RoundtripTemporal(java.time.LocalDate.of(2099, 12, 31), java.time.Instant.parse("2099-12-31T23:59:59Z"))
    )

    val result = roundtrip(data)

    assertEquals(result.size, 3)
    for (original, read) <- data.zip(result) do
      assertEquals(read.date, original.date)

  test("roundtrip Instant with microsecond precision"):
    // Arrow timestamps are in microseconds, so we need to test that precision is preserved
    val instant = java.time.Instant.parse("2024-06-15T14:30:45.123456Z")
    val data = Seq(
      RoundtripTemporal(java.time.LocalDate.now(), instant)
    )

    val result = roundtrip(data)

    // Check that microsecond precision is preserved (not nanosecond)
    val expectedMicros = instant.getEpochSecond * 1000000L + instant.getNano / 1000
    val actualMicros = result(0).instant.getEpochSecond * 1000000L + result(0).instant.getNano / 1000
    assertEquals(actualMicros, expectedMicros)

  // === Mixed Type Roundtrip Tests ===

  test("roundtrip mixed types"):
    val data = Seq(
      RoundtripMixed(1L, "Alice", Some(95.5), true),
      RoundtripMixed(2L, "Bob", None, false),
      RoundtripMixed(3L, "Charlie", Some(87.3), true)
    )

    val result = roundtrip(data)

    assertEquals(result.size, 3)
    for (original, read) <- data.zip(result) do
      assertEquals(read, original)

  // === Large Dataset Roundtrip Tests ===

  test("roundtrip 100000 rows"):
    val data = (1 to 100000).map { i =>
      RoundtripMixed(
        i.toLong,
        s"User$i",
        if i % 3 == 0 then None else Some(i.toDouble / 10),
        i % 2 == 0
      )
    }.toSeq

    val result = roundtrip(data)

    assertEquals(result.size, 100000)
    // Spot checks
    assertEquals(result(0), data(0))
    assertEquals(result(49999), data(49999))
    assertEquals(result(99999), data(99999))

  // === Edge Cases ===

  test("roundtrip empty string"):
    val data = Seq(
      RoundtripAllPrimitives(true, 0, 0, 0, 0L, 0.0f, 0.0, "")
    )

    val result = roundtrip(data)

    assertEquals(result(0).str, "")

  test("roundtrip unicode strings"):
    val data = Seq(
      RoundtripAllPrimitives(true, 1, 2, 3, 4L, 5.0f, 6.0, "Hello 世界 🌍")
    )

    val result = roundtrip(data)

    assertEquals(result(0).str, "Hello 世界 🌍")

  test("roundtrip negative numbers"):
    val data = Seq(
      RoundtripAllPrimitives(
        false,
        Byte.MinValue,
        Short.MinValue,
        Int.MinValue,
        Long.MinValue,
        Float.MinValue,
        Double.MinValue,
        "negative"
      )
    )

    val result = roundtrip(data)

    assertEquals(result(0), data(0))

  test("roundtrip zero values"):
    val data = Seq(
      RoundtripAllPrimitives(false, 0, 0, 0, 0L, 0.0f, 0.0, "zero")
    )

    val result = roundtrip(data)

    assertEquals(result(0), data(0))

  // === ArrowBatchBuilder Integration ===

  test("ArrowBatchBuilder.scoped roundtrip"):
    val data = Seq(
      ArrowSimple("Alice", 30),
      ArrowSimple("Bob", 25)
    )

    ArrowBatchBuilder.scoped(data) { root =>
      val reader = InlineArrowReader.derived[ArrowSimple]
      val result = reader.read(root)

      assertEquals(result.size, 2)
      assertEquals(result(0), ArrowSimple("Alice", 30))
      assertEquals(result(1), ArrowSimple("Bob", 25))
    }
