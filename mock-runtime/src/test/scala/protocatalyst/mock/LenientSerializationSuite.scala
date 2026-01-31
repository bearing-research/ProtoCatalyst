package protocatalyst.mock

import munit.FunSuite
import protocatalyst.types.ProtoType

class LenientSerializationSuite extends FunSuite:
  val conv = MockInternalTypeConverter

  // === DateType lenient serialization ===

  test("DateType accepts java.sql.Date"):
    val date = java.sql.Date.valueOf("2024-01-15")
    val internal = conv.toInternal(date, ProtoType.DateType)
    // java.sql.Date uses local timezone, so compare using LocalDate equivalent
    val expected = date.toLocalDate.toEpochDay.toInt
    assertEquals(internal, expected)

  test("DateType accepts java.time.LocalDate"):
    val date = java.time.LocalDate.of(2024, 1, 15)
    val internal = conv.toInternal(date, ProtoType.DateType)
    assertEquals(internal, date.toEpochDay.toInt)

  test("DateType accepts String"):
    val internal = conv.toInternal("2024-01-15", ProtoType.DateType)
    assertEquals(internal, java.time.LocalDate.parse("2024-01-15").toEpochDay.toInt)

  test("DateType accepts Int (already internal)"):
    val epochDays = java.time.LocalDate.of(2024, 1, 15).toEpochDay.toInt
    val internal = conv.toInternal(epochDays, ProtoType.DateType)
    assertEquals(internal, epochDays)

  test("DateType strict deserialization returns LocalDate"):
    val date = java.time.LocalDate.of(2024, 1, 15)
    val epochDays = date.toEpochDay.toInt
    val result = conv.fromInternal(epochDays, ProtoType.DateType)
    assertEquals(result, date)

  test("DateType handles null in toInternal"):
    assertEquals(conv.toInternal(null, ProtoType.DateType), null)

  test("DateType handles null in fromInternal"):
    assertEquals(conv.fromInternal(null, ProtoType.DateType), null)

  test("DateType rejects invalid input type"):
    intercept[IllegalArgumentException]:
      conv.toInternal(3.14, ProtoType.DateType)

  // === TimestampType lenient serialization ===

  test("TimestampType accepts java.sql.Timestamp"):
    val ts = java.sql.Timestamp.valueOf("2024-01-15 10:30:00")
    val internal = conv.toInternal(ts, ProtoType.TimestampType)
    assert(internal.isInstanceOf[Long])
    val micros = internal.asInstanceOf[Long]
    // java.sql.Timestamp uses local timezone, compare with Instant equivalent
    assertEquals(micros, ts.toInstant.toEpochMilli * 1000L)

  test("TimestampType accepts java.time.Instant"):
    val instant = java.time.Instant.parse("2024-01-15T10:30:00Z")
    val internal = conv.toInternal(instant, ProtoType.TimestampType)
    assert(internal.isInstanceOf[Long])
    val micros = internal.asInstanceOf[Long]
    assertEquals(micros, instant.toEpochMilli * 1000L)

  test("TimestampType accepts String"):
    val instant = java.time.Instant.parse("2024-01-15T10:30:00Z")
    val internal = conv.toInternal("2024-01-15T10:30:00Z", ProtoType.TimestampType)
    assert(internal.isInstanceOf[Long])
    val micros = internal.asInstanceOf[Long]
    assertEquals(micros, instant.toEpochMilli * 1000L)

  test("TimestampType accepts Long (already internal)"):
    val instant = java.time.Instant.parse("2024-01-15T10:30:00Z")
    val micros = instant.toEpochMilli * 1000L
    val internal = conv.toInternal(micros, ProtoType.TimestampType)
    assertEquals(internal, micros)

  test("TimestampType strict deserialization returns Instant"):
    val originalInstant = java.time.Instant.parse("2024-01-15T10:30:00Z")
    val micros = originalInstant.toEpochMilli * 1000L
    val result = conv.fromInternal(micros, ProtoType.TimestampType)
    assert(result.isInstanceOf[java.time.Instant])
    // Note: we lose sub-millisecond precision in conversion
    val instant = result.asInstanceOf[java.time.Instant]
    assertEquals(instant.toEpochMilli, originalInstant.toEpochMilli)

  test("TimestampType handles null in toInternal"):
    assertEquals(conv.toInternal(null, ProtoType.TimestampType), null)

  test("TimestampType handles null in fromInternal"):
    assertEquals(conv.fromInternal(null, ProtoType.TimestampType), null)

  test("TimestampType rejects invalid input type"):
    intercept[IllegalArgumentException]:
      conv.toInternal(3.14, ProtoType.TimestampType)

  // === TimestampNTZType lenient serialization ===

  test("TimestampNTZType accepts java.time.LocalDateTime"):
    val ldt = java.time.LocalDateTime.of(2024, 1, 15, 10, 30, 0)
    val internal = conv.toInternal(ldt, ProtoType.TimestampNTZType)
    assert(internal.isInstanceOf[Long])

  test("TimestampNTZType accepts String"):
    val internal = conv.toInternal("2024-01-15T10:30:00", ProtoType.TimestampNTZType)
    assert(internal.isInstanceOf[Long])

  test("TimestampNTZType accepts Long (already internal)"):
    val ldt = java.time.LocalDateTime.of(2024, 1, 15, 10, 30, 0)
    val micros = ldt.toEpochSecond(java.time.ZoneOffset.UTC) * 1000000L + ldt.getNano / 1000
    val internal = conv.toInternal(micros, ProtoType.TimestampNTZType)
    assertEquals(internal, micros)

  test("TimestampNTZType strict deserialization returns LocalDateTime"):
    val ldt = java.time.LocalDateTime.of(2024, 1, 15, 10, 30, 0)
    val micros = conv.toInternal(ldt, ProtoType.TimestampNTZType).asInstanceOf[Long]
    val result = conv.fromInternal(micros, ProtoType.TimestampNTZType)
    assert(result.isInstanceOf[java.time.LocalDateTime])
    assertEquals(result.asInstanceOf[java.time.LocalDateTime], ldt)

  test("TimestampNTZType handles null in toInternal"):
    assertEquals(conv.toInternal(null, ProtoType.TimestampNTZType), null)

  test("TimestampNTZType handles null in fromInternal"):
    assertEquals(conv.fromInternal(null, ProtoType.TimestampNTZType), null)

  // === Roundtrip tests ===

  test("DateType roundtrip from java.sql.Date"):
    val original = java.sql.Date.valueOf("2024-01-15")
    val internal = conv.toInternal(original, ProtoType.DateType)
    val restored = conv.fromInternal(internal, ProtoType.DateType)
    assertEquals(restored, java.time.LocalDate.of(2024, 1, 15))

  test("DateType roundtrip from LocalDate"):
    val original = java.time.LocalDate.of(2024, 1, 15)
    val internal = conv.toInternal(original, ProtoType.DateType)
    val restored = conv.fromInternal(internal, ProtoType.DateType)
    assertEquals(restored, original)

  test("TimestampType roundtrip from Instant"):
    val original = java.time.Instant.parse("2024-01-15T10:30:00Z")
    val internal = conv.toInternal(original, ProtoType.TimestampType)
    val restored = conv.fromInternal(internal, ProtoType.TimestampType)
    // Compare at millisecond precision
    val restoredInstant = restored.asInstanceOf[java.time.Instant]
    assertEquals(restoredInstant.toEpochMilli, original.toEpochMilli)

  test("TimestampNTZType roundtrip from LocalDateTime"):
    val original = java.time.LocalDateTime.of(2024, 1, 15, 10, 30, 0)
    val internal = conv.toInternal(original, ProtoType.TimestampNTZType)
    val restored = conv.fromInternal(internal, ProtoType.TimestampNTZType)
    assertEquals(restored, original)
