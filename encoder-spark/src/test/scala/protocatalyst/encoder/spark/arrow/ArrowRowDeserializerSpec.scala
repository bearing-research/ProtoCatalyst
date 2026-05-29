package protocatalyst.encoder.spark.arrow

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.file.{Files, Paths}
import java.{sql => jsql}
import java.math.{BigDecimal => JBigDecimal}
import java.time.{Duration, Instant, LocalDate, LocalDateTime, LocalTime, Period}

import munit.FunSuite
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.ipc.ArrowStreamReader

/** [[ArrowRowDeserializer]] verification — two angles:
  *   1. Roundtrip: write rows via our serializer, read them back via our deserializer, assert
  *      structural equality.
  *   2. Spark-fixture parity: feed Spark-produced IPC bytes (from A6) through our deserializer,
  *      assert the recovered values match the originals from the fixture generator.
  *
  * (1) proves the writer/reader pair is self-consistent. (2) proves our reader correctly handles
  * the exact wire format Spark Connect emits — combined with A7's write-side parity, this closes
  * the loop on Phase A.
  */
class ArrowRowDeserializerSpec extends FunSuite:

  // ---------------------------------------------------------------------------
  // Mirror case classes + records. Must stay identical to ArrowIpcParityFixtures
  // (Scala 2.13) and ArrowIpcParitySpec (Scala 3).
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
  // LocalTime is an extension-only type (Spark rejects it). Roundtrip test only; no parity.
  case class WithLocalTime(lt: LocalTime)

  // Nested shapes (Struct + List) — mirror the Scala 2.13 fixture file.
  case class Point(x: Int, y: Int)
  case class Line(start: Point, end: Point)
  case class Tagged(id: Int, tags: Seq[String])
  case class Nums(id: Int, values: Seq[Int])
  case class Squad(name: String, members: Seq[Point])
  case class Holder(id: Int, inner: Tagged)
  case class OptList(id: Int, maybe: Option[Seq[Int]])

  val simpleRecords: List[Simple] = List(
    Simple(1, "alice"),
    Simple(2, "bob"),
    Simple(3, null)
  )

  val allPrimitivesRecords: List[AllPrimitives] = List(
    AllPrimitives(true, Byte.MinValue, Short.MaxValue, Int.MinValue, Long.MaxValue, 1.5f, 3.14159265358979),
    AllPrimitives(false, 0, 0, 42, -7L, 0.0f, -0.0)
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
      // Timezone-independent — see ArrowIpcParityFixtures comment.
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

  val localTimeRecords: List[WithLocalTime] = List(
    WithLocalTime(LocalTime.of(12, 34, 56)),
    WithLocalTime(LocalTime.MIDNIGHT)
  )

  // Nested records — must match ArrowIpcParityFixtures value-for-value. Empty Seq round-trips to
  // an empty List (immutable.Seq is List at runtime), so equality holds against the originals.
  val pointRecords: List[Point] = List(Point(1, 2), Point(-3, 4), Point(0, 0))
  val lineRecords: List[Line] = List(Line(Point(1, 2), Point(3, 4)), Line(Point(5, 6), Point(7, 8)))
  val taggedRecords: List[Tagged] =
    List(Tagged(1, Seq("a", "b")), Tagged(2, Seq.empty), Tagged(3, Seq("x", null, "z")))
  val numsRecords: List[Nums] =
    List(Nums(1, Seq(1, 2, 3)), Nums(2, Seq.empty), Nums(3, Seq(Int.MinValue, 0, Int.MaxValue)))
  val squadRecords: List[Squad] = List(
    Squad("a", Seq(Point(1, 1), Point(2, 2))),
    Squad("b", Seq.empty),
    Squad("c", Seq(Point(9, 9)))
  )
  val holderRecords: List[Holder] =
    List(Holder(1, Tagged(10, Seq("p", "q"))), Holder(2, Tagged(20, Seq.empty)))
  val optListRecords: List[OptList] =
    List(OptList(1, Some(Seq(1, 2, 3))), OptList(2, None), OptList(3, Some(Seq.empty)))

  /** Canonicalize a null Option to None — the reader can't distinguish "wrote null" from
    * "wrote None" because the vector only encodes an isNull bit. The writer parity test in
    * `ArrowIpcParitySpec` proves the null Option doesn't crash and produces the same bytes
    * as None; this side just verifies the round-trip lands on the canonical None.
    */
  private def canonicalizeOptional(o: Optional): Optional =
    Optional(
      if o.oi == null then None else o.oi,
      if o.os == null then None else o.os,
      if o.od == null then None else o.od
    )

  // ---------------------------------------------------------------------------
  // Helpers.
  // ---------------------------------------------------------------------------

  private val parityDir = "encoder-spark/src/test/resources/arrow-ipc-parity"

  private def withReader[T](bytes: Array[Byte], deser: ArrowRowDeserializer[T])(
      f: List[T] => Unit
  ): Unit = {
    val alloc = new RootAllocator(Long.MaxValue)
    try {
      val reader = new ArrowStreamReader(new ByteArrayInputStream(bytes), alloc)
      try {
        val buf = scala.collection.mutable.ListBuffer.empty[T]
        while reader.loadNextBatch() do
          val root = reader.getVectorSchemaRoot
          for i <- 0 until root.getRowCount do buf += deser.read(root, i)
        f(buf.toList)
      } finally reader.close()
    } finally alloc.close()
  }

  /** Roundtrip a list of records through our serializer + deserializer. */
  private def roundtrip[T](
      records: List[T],
      ser: ArrowRowSerializer[T],
      deser: ArrowRowDeserializer[T]
  ): List[T] = {
    val alloc = ser.allocator
    val it = ArrowStreaming.serializeWith(
      ser,
      records.iterator,
      maxRecordsPerBatch = 128,
      maxBatchSize = Long.MaxValue,
      batchSizeCheckInterval = 128
    )
    val out = new ByteArrayOutputStream()
    try while it.hasNext do out.write(it.next())
    finally it.close()
    val bytes = out.toByteArray
    val readAlloc = new RootAllocator(Long.MaxValue)
    try {
      val reader = new ArrowStreamReader(new ByteArrayInputStream(bytes), readAlloc)
      try {
        val buf = scala.collection.mutable.ListBuffer.empty[T]
        while reader.loadNextBatch() do
          val root = reader.getVectorSchemaRoot
          for i <- 0 until root.getRowCount do buf += deser.read(root, i)
        buf.toList
      } finally reader.close()
    } finally readAlloc.close()
  }

  // ---------------------------------------------------------------------------
  // (1) Roundtrip tests — writer+reader self-consistency.
  // ---------------------------------------------------------------------------

  test("Simple: roundtrip via our serializer + deserializer"):
    val alloc = new RootAllocator(Long.MaxValue)
    val ser = ArrowRowSerializer.derived[Simple](alloc)
    try
      val got = roundtrip(simpleRecords, ser, ArrowRowDeserializer.derived[Simple])
      assertEquals(got, simpleRecords)
    finally alloc.close()

  test("AllPrimitives: roundtrip"):
    val alloc = new RootAllocator(Long.MaxValue)
    val ser = ArrowRowSerializer.derived[AllPrimitives](alloc)
    try
      val got = roundtrip(allPrimitivesRecords, ser, ArrowRowDeserializer.derived[AllPrimitives])
      assertEquals(got, allPrimitivesRecords)
    finally alloc.close()

  test("Strings: roundtrip (Array[Byte] equality via .toList)"):
    val alloc = new RootAllocator(Long.MaxValue)
    val ser = ArrowRowSerializer.derived[Strings](alloc)
    try
      val got = roundtrip(stringsRecords, ser, ArrowRowDeserializer.derived[Strings])
      // Array[Byte] uses reference equality by default; compare element-wise.
      assertEquals(got.size, stringsRecords.size)
      got.zip(stringsRecords).foreach { case (a, b) =>
        assertEquals(a.s, b.s)
        if a.b == null then assert(b.b == null)
        else assertEquals(a.b.toList, b.b.toList)
      }
    finally alloc.close()

  test("Decimals: roundtrip (scale 18 preserved both sides)"):
    val alloc = new RootAllocator(Long.MaxValue)
    val ser = ArrowRowSerializer.derived[Decimals](alloc)
    try
      val got = roundtrip(decimalsRecords, ser, ArrowRowDeserializer.derived[Decimals])
      // Compare numerically — scale-normalized BigDecimal equality
      assertEquals(got.size, decimalsRecords.size)
      got.zip(decimalsRecords).foreach { case (a, b) =>
        assertEquals(a.s.bigDecimal.compareTo(b.s.bigDecimal.setScale(18)), 0)
        assertEquals(a.j.compareTo(b.j.setScale(18)), 0)
      }
    finally alloc.close()

  test("Temporal: roundtrip (LocalDate/jsql.Date/Instant/jsql.Timestamp/LocalDateTime/Duration/Period)"):
    val alloc = new RootAllocator(Long.MaxValue)
    val ser = ArrowRowSerializer.derived[Temporal](alloc)
    try
      val got = roundtrip(temporalRecords, ser, ArrowRowDeserializer.derived[Temporal])
      assertEquals(got, temporalRecords)
    finally alloc.close()

  test("Optional: roundtrip (Option[Int/String/LocalDate], includes null Option row)"):
    val alloc = new RootAllocator(Long.MaxValue)
    val ser = ArrowRowSerializer.derived[Optional](alloc)
    try
      val got = roundtrip(optionalRecords, ser, ArrowRowDeserializer.derived[Optional])
      // Null Options round-trip to None — see canonicalizeOptional.
      assertEquals(got, optionalRecords.map(canonicalizeOptional))
    finally alloc.close()

  test("WithLocalTime: roundtrip (extension type)"):
    val alloc = new RootAllocator(Long.MaxValue)
    val ser = ArrowRowSerializer.derived[WithLocalTime](alloc)
    try
      val got = roundtrip(localTimeRecords, ser, ArrowRowDeserializer.derived[WithLocalTime])
      assertEquals(got, localTimeRecords)
    finally alloc.close()

  // ---------------------------------------------------------------------------
  // (2) Spark-fixture parity — read Spark-produced IPC bytes, assert recovered values.
  // ---------------------------------------------------------------------------

  private def loadFixture(name: String): Array[Byte] = {
    val path = Paths.get(parityDir, s"$name.arrow-ipc")
    if !Files.exists(path) then
      fail(
        s"Missing fixture $path — regenerate with " +
          "`sbt 'benchmarkSpark/runMain org.apache.spark.sql.protocatalyst.ArrowIpcParityFixtures'`"
      )
    Files.readAllBytes(path)
  }

  test("Spark-fixture parity: Simple"):
    withReader(loadFixture("Simple"), ArrowRowDeserializer.derived[Simple]) { got =>
      assertEquals(got, simpleRecords)
    }

  test("Spark-fixture parity: AllPrimitives"):
    withReader(loadFixture("AllPrimitives"), ArrowRowDeserializer.derived[AllPrimitives]) { got =>
      assertEquals(got, allPrimitivesRecords)
    }

  test("Spark-fixture parity: Strings"):
    withReader(loadFixture("Strings"), ArrowRowDeserializer.derived[Strings]) { got =>
      assertEquals(got.size, stringsRecords.size)
      got.zip(stringsRecords).foreach { case (a, b) =>
        assertEquals(a.s, b.s)
        if a.b == null then assert(b.b == null)
        else assertEquals(a.b.toList, b.b.toList)
      }
    }

  test("Spark-fixture parity: Decimals"):
    withReader(loadFixture("Decimals"), ArrowRowDeserializer.derived[Decimals]) { got =>
      assertEquals(got.size, decimalsRecords.size)
      got.zip(decimalsRecords).foreach { case (a, b) =>
        assertEquals(a.s.bigDecimal.compareTo(b.s.bigDecimal.setScale(18)), 0)
        assertEquals(a.j.compareTo(b.j.setScale(18)), 0)
      }
    }

  test("Spark-fixture parity: Temporal"):
    withReader(loadFixture("Temporal"), ArrowRowDeserializer.derived[Temporal]) { got =>
      assertEquals(got, temporalRecords)
    }

  test("Spark-fixture parity: Optional (null Option → None on read)"):
    withReader(loadFixture("Optional"), ArrowRowDeserializer.derived[Optional]) { got =>
      assertEquals(got, optionalRecords.map(canonicalizeOptional))
    }

  test("Spark-fixture parity: Simple-largeVarTypes (LargeUtf8 read path)"):
    withReader(
      loadFixture("Simple-largeVarTypes"),
      ArrowRowDeserializer.derived[Simple]("UTC", true)
    ) { got =>
      assertEquals(got, simpleRecords)
    }

  // --- Nested shapes: decode Spark Connect's actual IPC bytes through our reader. ---

  test("Spark-fixture parity: Point (flat struct)"):
    withReader(loadFixture("Point"), ArrowRowDeserializer.derived[Point]) { got =>
      assertEquals(got, pointRecords)
    }

  test("Spark-fixture parity: Line (struct of struct)"):
    withReader(loadFixture("Line"), ArrowRowDeserializer.derived[Line]) { got =>
      assertEquals(got, lineRecords)
    }

  test("Spark-fixture parity: Tagged (list<string>, empty + null element)"):
    withReader(loadFixture("Tagged"), ArrowRowDeserializer.derived[Tagged]) { got =>
      assertEquals(got, taggedRecords)
    }

  test("Spark-fixture parity: Nums (list<int>)"):
    withReader(loadFixture("Nums"), ArrowRowDeserializer.derived[Nums]) { got =>
      assertEquals(got, numsRecords)
    }

  test("Spark-fixture parity: Squad (list<struct>)"):
    withReader(loadFixture("Squad"), ArrowRowDeserializer.derived[Squad]) { got =>
      assertEquals(got, squadRecords)
    }

  test("Spark-fixture parity: Holder (struct containing a list)"):
    withReader(loadFixture("Holder"), ArrowRowDeserializer.derived[Holder]) { got =>
      assertEquals(got, holderRecords)
    }

  test("Spark-fixture parity: OptList (nullable list via Option)"):
    withReader(loadFixture("OptList"), ArrowRowDeserializer.derived[OptList]) { got =>
      assertEquals(got, optListRecords)
    }
