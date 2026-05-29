// Lives under `org.apache.spark.sql.*` for access to `private[sql] CloseableIterator` returned
// by ArrowSerializer.serialize. We never reference CloseableIterator's type directly; we just
// iterate via the Iterator parent and close() via AutoCloseable, but the implicit conversion
// requires source-package compatibility.
package org.apache.spark.sql.protocatalyst

import java.io.{ByteArrayOutputStream, File, FileOutputStream}
import java.time.{Duration, Instant, LocalDate, LocalDateTime, Period}
import java.sql.{Date => SqlDate, Timestamp => SqlTimestamp}

import org.apache.arrow.memory.RootAllocator
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.connect.client.arrow.ArrowSerializer

/** Generates Arrow IPC byte fixtures from Spark Connect's `ArrowSerializer.serialize(...)`,
  * used by the Scala 3 byte-parity test in encoder-spark.
  *
  * For each test case we hand-pick a small input set (small enough to fit in one batch with
  * `maxRecordsPerBatch=128`), drive Spark's serializer with a fixed config, concatenate all
  * yielded IPC streams into one file, and write it as `<Name>.arrow-ipc`. The Scala 3 test
  * runs `ArrowStreaming.serialize` with the identical config + records and asserts byte
  * equivalence.
  *
  * Re-run after type/schema/data changes:
  * {{{
  * sbt 'benchmarkSpark/runMain org.apache.spark.sql.protocatalyst.ArrowIpcParityFixtures'
  * }}}
  *
  * Case-class shapes here MUST stay structurally identical to those in
  * `encoder-spark/src/test/scala/.../arrow/ArrowIpcParitySpec.scala`. The records too — the
  * spec re-creates the same `Iterator[T]` to feed `ArrowStreaming.serialize`.
  */
object ArrowIpcParityFixtures {

  // ---------------------------------------------------------------------------
  // Mirror case classes (kept structurally identical to the Scala 3 spec).
  // ---------------------------------------------------------------------------

  case class Simple(id: Int, name: String)
  case class AllPrimitives(
      b: Boolean,
      i8: Byte,
      i16: Short,
      i32: Int,
      i64: Long,
      f32: Float,
      f64: Double)
  case class Strings(s: String, b: Array[Byte])

  // Decimal precision/scale defaults to (38, 18) on Spark's ScalaDecimalEncoder; both sides
  // emit Decimal(38, 18, 128). We pick values that fit comfortably.
  case class Decimals(s: BigDecimal, j: java.math.BigDecimal)

  case class Temporal(
      d: LocalDate,
      sd: SqlDate,
      i: Instant,
      st: SqlTimestamp,
      ldt: LocalDateTime,
      dur: Duration,
      per: Period)

  case class Optional(oi: Option[Int], os: Option[String], od: Option[LocalDate])

  // Nested shapes (Struct + List). Mirror the Scala 3 spec field-for-field.
  case class Point(x: Int, y: Int)
  case class Line(start: Point, end: Point)
  case class Tagged(id: Int, tags: Seq[String])
  case class Nums(id: Int, values: Seq[Int])
  case class Squad(name: String, members: Seq[Point])
  case class Holder(id: Int, inner: Tagged)
  case class OptList(id: Int, maybe: Option[Seq[Int]])

  // ---------------------------------------------------------------------------
  // Records: hand-crafted inputs that must EXACTLY match what the Scala 3 spec re-creates.
  // Any drift here breaks the byte-parity test — comment so both sides are easy to compare.
  // ---------------------------------------------------------------------------

  // Simple: 3 rows, including one with a null name (tests setNull on VarCharVector).
  val simpleRecords: List[Simple] = List(
    Simple(1, "alice"),
    Simple(2, "bob"),
    Simple(3, null)
  )

  // AllPrimitives: 2 rows covering extremes and mid-range values for each primitive.
  val allPrimitivesRecords: List[AllPrimitives] = List(
    AllPrimitives(
      b = true,
      i8 = Byte.MinValue,
      i16 = Short.MaxValue,
      i32 = Int.MinValue,
      i64 = Long.MaxValue,
      f32 = 1.5f,
      f64 = 3.14159265358979),
    AllPrimitives(
      b = false,
      i8 = 0,
      i16 = 0,
      i32 = 42,
      i64 = -7L,
      f32 = 0.0f,
      f64 = -0.0)
  )

  val stringsRecords: List[Strings] = List(
    Strings("hello", Array[Byte](1, 2, 3, 4, 5)),
    Strings("", Array.emptyByteArray),
    Strings(null, null)
  )

  val decimalsRecords: List[Decimals] = List(
    Decimals(BigDecimal("123.456789012345678"), new java.math.BigDecimal("0.000000001000000001")),
    Decimals(BigDecimal("0"), java.math.BigDecimal.ZERO)
  )

  // Temporal: 1 row with fixed instants. Timestamp is built from a UTC Instant rather than
  // `Timestamp.valueOf("2026-05-28 15:00:00")` because the valueOf overload parses in the JVM's
  // default timezone — fixtures generated in PDT then fail when the spec runs under UTC.
  // Note: LocalTime is excluded (Spark 4.1.2 rejects TimeType at ExpressionEncoder construction).
  val temporalRecords: List[Temporal] = List(
    Temporal(
      d = LocalDate.of(2026, 5, 28),
      sd = SqlDate.valueOf("2026-05-28"),
      i = Instant.parse("2026-05-28T15:00:00Z"),
      st = SqlTimestamp.from(Instant.parse("2026-05-28T15:00:00Z")),
      ldt = LocalDateTime.of(2026, 5, 28, 15, 0, 0),
      dur = Duration.ofSeconds(3600),
      per = Period.ofMonths(7))
  )

  // Includes one row with a literal `null` Option (oi=null) — Spark's ArrowSerializer treats
  // null as null; our writer must too. Regression coverage for the null-Option NPE.
  val optionalRecords: List[Optional] = List(
    Optional(Some(1), Some("yes"), Some(LocalDate.of(2026, 1, 1))),
    Optional(None, None, None),
    Optional(Some(42), None, Some(LocalDate.of(2026, 12, 31))),
    Optional(null, null, null)
  )

  // Nested records — must match the Scala 3 spec value-for-value.
  val pointRecords: List[Point] = List(Point(1, 2), Point(-3, 4), Point(0, 0))

  val lineRecords: List[Line] =
    List(Line(Point(1, 2), Point(3, 4)), Line(Point(5, 6), Point(7, 8)))

  // Includes an empty list and a list with a null element (containsNull element vector).
  val taggedRecords: List[Tagged] = List(
    Tagged(1, Seq("a", "b")),
    Tagged(2, Seq.empty),
    Tagged(3, Seq("x", null, "z"))
  )

  val numsRecords: List[Nums] = List(
    Nums(1, Seq(1, 2, 3)),
    Nums(2, Seq.empty),
    Nums(3, Seq(Int.MinValue, 0, Int.MaxValue))
  )

  // list<struct>, including an empty list.
  val squadRecords: List[Squad] = List(
    Squad("a", Seq(Point(1, 1), Point(2, 2))),
    Squad("b", Seq.empty),
    Squad("c", Seq(Point(9, 9)))
  )

  // struct containing a list.
  val holderRecords: List[Holder] =
    List(Holder(1, Tagged(10, Seq("p", "q"))), Holder(2, Tagged(20, Seq.empty)))

  // nullable list via Option: Some / None / empty.
  val optListRecords: List[OptList] =
    List(OptList(1, Some(Seq(1, 2, 3))), OptList(2, None), OptList(3, Some(Seq.empty)))

  // ---------------------------------------------------------------------------
  // Spark-side serialization: produce IPC bytes for the given records under fixed config.
  // ---------------------------------------------------------------------------

  // Fixed parity config. Mirror in the Scala 3 spec.
  val MaxRecordsPerBatch = 128
  val MaxBatchSize = 8L * 1024
  val TimeZoneId = "UTC"
  val LargeVarTypes = false
  val BatchSizeCheckInterval = 128

  private def sparkIpcBytes[T](
      records: Iterable[T],
      enc: ExpressionEncoder[T],
      largeVarTypes: Boolean = LargeVarTypes
  ): Array[Byte] = {
    val alloc = new RootAllocator(Long.MaxValue)
    try {
      val it = ArrowSerializer.serialize[T](
        input = records.iterator,
        enc = enc.encoder,
        allocator = alloc,
        maxRecordsPerBatch = MaxRecordsPerBatch,
        maxBatchSize = MaxBatchSize,
        timeZoneId = TimeZoneId,
        largeVarTypes = largeVarTypes,
        batchSizeCheckInterval = BatchSizeCheckInterval)
      val out = new ByteArrayOutputStream()
      try {
        while (it.hasNext) out.write(it.next())
      } finally it.close()
      out.toByteArray
    } finally alloc.close()
  }

  private def writeFixture(dir: File, name: String, data: Array[Byte]): Unit = {
    dir.mkdirs()
    val f = new File(dir, s"$name.arrow-ipc")
    val out = new FileOutputStream(f)
    try out.write(data)
    finally out.close()
    println(f"Wrote ${f.getPath} (${data.length}%4dB)")
  }

  // ---------------------------------------------------------------------------
  // Main.
  // ---------------------------------------------------------------------------

  def main(args: Array[String]): Unit = {
    val outDir =
      if (args.nonEmpty) new File(args(0))
      else new File("encoder-spark/src/test/resources/arrow-ipc-parity")
    println(s"Output dir: ${outDir.getAbsolutePath}")

    writeFixture(outDir, "Simple", sparkIpcBytes(simpleRecords, ExpressionEncoder[Simple]()))
    writeFixture(
      outDir,
      "Simple-largeVarTypes",
      sparkIpcBytes(simpleRecords, ExpressionEncoder[Simple](), largeVarTypes = true))
    writeFixture(
      outDir,
      "AllPrimitives",
      sparkIpcBytes(allPrimitivesRecords, ExpressionEncoder[AllPrimitives]()))
    writeFixture(outDir, "Strings", sparkIpcBytes(stringsRecords, ExpressionEncoder[Strings]()))
    writeFixture(
      outDir,
      "Decimals",
      sparkIpcBytes(decimalsRecords, ExpressionEncoder[Decimals]()))
    writeFixture(
      outDir,
      "Temporal",
      sparkIpcBytes(temporalRecords, ExpressionEncoder[Temporal]()))
    writeFixture(
      outDir,
      "Optional",
      sparkIpcBytes(optionalRecords, ExpressionEncoder[Optional]()))

    // Nested shapes (Struct + List).
    writeFixture(outDir, "Point", sparkIpcBytes(pointRecords, ExpressionEncoder[Point]()))
    writeFixture(outDir, "Line", sparkIpcBytes(lineRecords, ExpressionEncoder[Line]()))
    writeFixture(outDir, "Tagged", sparkIpcBytes(taggedRecords, ExpressionEncoder[Tagged]()))
    writeFixture(outDir, "Nums", sparkIpcBytes(numsRecords, ExpressionEncoder[Nums]()))
    writeFixture(outDir, "Squad", sparkIpcBytes(squadRecords, ExpressionEncoder[Squad]()))
    writeFixture(outDir, "Holder", sparkIpcBytes(holderRecords, ExpressionEncoder[Holder]()))
    writeFixture(outDir, "OptList", sparkIpcBytes(optListRecords, ExpressionEncoder[OptList]()))

    println("Done.")
  }
}
