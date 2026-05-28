package protocatalyst.encoder.spark.arrow

import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Paths}
import java.{sql => jsql}
import java.math.{BigDecimal => JBigDecimal}
import java.time.{Duration, Instant, LocalDate, LocalDateTime, Period}

import munit.FunSuite
import org.apache.arrow.memory.RootAllocator

/** End-to-end byte-parity of [[ArrowStreaming.serialize]] against Spark Connect's
  * `ArrowSerializer.serialize(...)`. The fixtures under `src/test/resources/arrow-ipc-parity/`
  * are produced by `org.apache.spark.sql.protocatalyst.ArrowIpcParityFixtures` running the same
  * records through Spark with the identical config. We re-create the records here and assert
  * byte equality on the concatenated IPC bytes.
  *
  * Phase A7 scope: only the cases supported by the Phase A4 writer (primitives + String). The
  * remaining fixtures (Strings with Array[Byte], Decimals, Temporal, Optional) ship pre-generated
  * for A8 to cover once the writer matrix expands.
  *
  * Config MUST stay byte-identical to the fixture generator:
  *   - maxRecordsPerBatch = 128
  *   - maxBatchSize       = 8 KB
  *   - timeZoneId         = "UTC"
  *   - largeVarTypes      = false
  *   - batchSizeCheckInterval = 128
  */
class ArrowIpcParitySpec extends FunSuite:

  // ---------------------------------------------------------------------------
  // Mirror case classes — structurally identical to the Scala 2.13 fixture.
  // ---------------------------------------------------------------------------

  case class Simple(id: Int, name: String)
  case class AllPrimitives(
      b: Boolean,
      i8: Byte,
      i16: Short,
      i32: Int,
      i64: Long,
      f32: Float,
      f64: Double
  )
  case class Strings(s: String, b: Array[Byte])
  case class Decimals(s: BigDecimal, j: JBigDecimal)
  case class Temporal(
      d: LocalDate,
      sd: jsql.Date,
      i: Instant,
      st: jsql.Timestamp,
      ldt: LocalDateTime,
      dur: Duration,
      per: Period
  )
  case class Optional(oi: Option[Int], os: Option[String], od: Option[LocalDate])

  // ---------------------------------------------------------------------------
  // Records: MUST stay value-for-value identical to ArrowIpcParityFixtures.
  // ---------------------------------------------------------------------------

  val simpleRecords: List[Simple] = List(
    Simple(1, "alice"),
    Simple(2, "bob"),
    Simple(3, null)
  )

  val allPrimitivesRecords: List[AllPrimitives] = List(
    AllPrimitives(
      b = true,
      i8 = Byte.MinValue,
      i16 = Short.MaxValue,
      i32 = Int.MinValue,
      i64 = Long.MaxValue,
      f32 = 1.5f,
      f64 = 3.14159265358979
    ),
    AllPrimitives(
      b = false,
      i8 = 0,
      i16 = 0,
      i32 = 42,
      i64 = -7L,
      f32 = 0.0f,
      f64 = -0.0
    )
  )

  val stringsRecords: List[Strings] = List(
    Strings("hello", Array[Byte](1, 2, 3, 4, 5)),
    Strings("", Array.emptyByteArray),
    Strings(null, null)
  )

  val decimalsRecords: List[Decimals] = List(
    Decimals(BigDecimal("123.456789012345678"), new JBigDecimal("0.000000001000000001")),
    Decimals(BigDecimal("0"), JBigDecimal.ZERO)
  )

  val temporalRecords: List[Temporal] = List(
    Temporal(
      d = LocalDate.of(2026, 5, 28),
      sd = jsql.Date.valueOf("2026-05-28"),
      i = Instant.parse("2026-05-28T15:00:00Z"),
      // Built from a UTC Instant — Timestamp.valueOf parses in JVM-default TZ which breaks parity
      // under -Duser.timezone=UTC. Must match the fixture generator.
      st = jsql.Timestamp.from(Instant.parse("2026-05-28T15:00:00Z")),
      ldt = LocalDateTime.of(2026, 5, 28, 15, 0, 0),
      dur = Duration.ofSeconds(3600),
      per = Period.ofMonths(7)
    )
  )

  val optionalRecords: List[Optional] = List(
    Optional(Some(1), Some("yes"), Some(LocalDate.of(2026, 1, 1))),
    Optional(None, None, None),
    Optional(Some(42), None, Some(LocalDate.of(2026, 12, 31))),
    Optional(null, null, null)
  )

  // ---------------------------------------------------------------------------
  // Shared parity config — match fixture generator exactly.
  // ---------------------------------------------------------------------------

  val MaxRecordsPerBatch = 128
  val MaxBatchSize = 8L * 1024
  val TimeZoneId = "UTC"
  val LargeVarTypes = false
  val BatchSizeCheckInterval = 128

  private val fixtureDir = "encoder-spark/src/test/resources/arrow-ipc-parity"

  private def loadFixture(name: String): Array[Byte] =
    val path = Paths.get(fixtureDir, s"$name.arrow-ipc")
    if !Files.exists(path) then
      fail(
        s"Missing fixture $path — regenerate with " +
          "`sbt 'benchmarkSpark/runMain org.apache.spark.sql.protocatalyst.ArrowIpcParityFixtures'`"
      )
    Files.readAllBytes(path)

  private def oursIpcBytes[T](makeIt: RootAllocator => CloseableIteratorLike[Array[Byte]]): Array[Byte] =
    val alloc = new RootAllocator(Long.MaxValue)
    try
      val it = makeIt(alloc)
      val out = new ByteArrayOutputStream()
      try
        while it.hasNext do out.write(it.next())
      finally it.close()
      out.toByteArray
    finally alloc.close()

  private def assertByteParity(name: String, actual: Array[Byte]): Unit =
    val expected = loadFixture(name)
    if !java.util.Arrays.equals(expected, actual) then
      val mismatch = expected
        .zip(actual)
        .zipWithIndex
        .find { case ((a, b), _) => a != b }
        .map { case ((a, b), i) => f"first diff at offset $i: expected=0x${a & 0xff}%02x, actual=0x${b & 0xff}%02x" }
        .getOrElse("(no per-byte diff but arrays differ in length)")
      fail(
        s"$name IPC parity failure: expected ${expected.length}B, actual ${actual.length}B; $mismatch"
      )

  // ---------------------------------------------------------------------------
  // Per-case parity tests (A4 writer coverage: primitives + String).
  // ---------------------------------------------------------------------------

  test("Simple: IPC byte parity against Spark Connect ArrowSerializer"):
    val actual = oursIpcBytes[Simple] { alloc =>
      ArrowStreaming.serialize[Simple](
        simpleRecords.iterator,
        alloc,
        MaxRecordsPerBatch,
        MaxBatchSize,
        TimeZoneId,
        LargeVarTypes,
        BatchSizeCheckInterval
      )
    }
    assertByteParity("Simple", actual)

  test("Simple: IPC byte parity with largeVarTypes=true (LargeVarChar path)"):
    val actual = oursIpcBytes[Simple] { alloc =>
      ArrowStreaming.serialize[Simple](
        simpleRecords.iterator,
        alloc,
        MaxRecordsPerBatch,
        MaxBatchSize,
        TimeZoneId,
        largeVarTypes = true,
        BatchSizeCheckInterval
      )
    }
    assertByteParity("Simple-largeVarTypes", actual)

  test("AllPrimitives: IPC byte parity against Spark Connect ArrowSerializer"):
    val actual = oursIpcBytes[AllPrimitives] { alloc =>
      ArrowStreaming.serialize[AllPrimitives](
        allPrimitivesRecords.iterator,
        alloc,
        MaxRecordsPerBatch,
        MaxBatchSize,
        TimeZoneId,
        LargeVarTypes,
        BatchSizeCheckInterval
      )
    }
    assertByteParity("AllPrimitives", actual)

  test("Strings: IPC byte parity (String + Array[Byte])"):
    val actual = oursIpcBytes[Strings] { alloc =>
      ArrowStreaming.serialize[Strings](
        stringsRecords.iterator,
        alloc,
        MaxRecordsPerBatch,
        MaxBatchSize,
        TimeZoneId,
        LargeVarTypes,
        BatchSizeCheckInterval
      )
    }
    assertByteParity("Strings", actual)

  test("Decimals: IPC byte parity (Scala + Java BigDecimal at scale 18)"):
    val actual = oursIpcBytes[Decimals] { alloc =>
      ArrowStreaming.serialize[Decimals](
        decimalsRecords.iterator,
        alloc,
        MaxRecordsPerBatch,
        MaxBatchSize,
        TimeZoneId,
        LargeVarTypes,
        BatchSizeCheckInterval
      )
    }
    assertByteParity("Decimals", actual)

  test("Temporal: IPC byte parity (LocalDate, jsql.Date, Instant, jsql.Timestamp, LocalDateTime, Duration, Period)"):
    val actual = oursIpcBytes[Temporal] { alloc =>
      ArrowStreaming.serialize[Temporal](
        temporalRecords.iterator,
        alloc,
        MaxRecordsPerBatch,
        MaxBatchSize,
        TimeZoneId,
        LargeVarTypes,
        BatchSizeCheckInterval
      )
    }
    assertByteParity("Temporal", actual)

  test("Optional: IPC byte parity (Option[Int/String/LocalDate], includes all-None row)"):
    val actual = oursIpcBytes[Optional] { alloc =>
      ArrowStreaming.serialize[Optional](
        optionalRecords.iterator,
        alloc,
        MaxRecordsPerBatch,
        MaxBatchSize,
        TimeZoneId,
        LargeVarTypes,
        BatchSizeCheckInterval
      )
    }
    assertByteParity("Optional", actual)
