package protocatalyst.encoder.spark.arrow

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import munit.FunSuite
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.ipc.ArrowStreamReader

/** Writer+reader self-consistency for nested types (Struct + List). Proves the recursive macro
  * expands and round-trips for nested case classes, collections, collections-of-structs,
  * structs-containing-collections, nullable collections, and Arrays — independent of Spark
  * (byte-parity against Spark Connect lives in `ArrowSchemaParitySpec` / `ArrowIpcParitySpec`).
  */
class ArrowNestedRoundtripSpec extends FunSuite:

  // --- Nested shapes (shared structurally with the parity fixtures). ---

  case class Point(x: Int, y: Int)
  case class Line(start: Point, end: Point)
  case class Tagged(id: Int, tags: Seq[String])
  case class Nums(id: Int, values: Seq[Int])
  case class Squad(name: String, members: Seq[Point])
  case class Holder(id: Int, inner: Tagged)
  case class OptList(id: Int, maybe: Option[Seq[Int]])
  case class Vecs(id: Int, vs: Vector[Long])
  case class Arr(id: Int, nums: Array[Long])

  private def roundtrip[T](
      records: List[T],
      ser: ArrowRowSerializer[T],
      deser: ArrowRowDeserializer[T]
  ): List[T] =
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
    try
      val reader = new ArrowStreamReader(new ByteArrayInputStream(bytes), readAlloc)
      try
        val buf = scala.collection.mutable.ListBuffer.empty[T]
        while reader.loadNextBatch() do
          val root = reader.getVectorSchemaRoot
          for i <- 0 until root.getRowCount do buf += deser.read(root, i)
        buf.toList
      finally reader.close()
    finally readAlloc.close()

  test("Point: nested-eligible flat struct round-trips at top level"):
    val alloc = new RootAllocator(Long.MaxValue)
    try
      val got = roundtrip(
        List(Point(1, 2), Point(-3, 4)),
        ArrowRowSerializer.derived[Point](alloc),
        ArrowRowDeserializer.derived[Point]
      )
      assertEquals(got, List(Point(1, 2), Point(-3, 4)))
    finally alloc.close()

  test("Line: struct-of-struct round-trips"):
    val recs = List(Line(Point(1, 2), Point(3, 4)), Line(Point(0, 0), Point(-1, -1)))
    val alloc = new RootAllocator(Long.MaxValue)
    try
      val got = roundtrip(recs, ArrowRowSerializer.derived[Line](alloc), ArrowRowDeserializer.derived[Line])
      assertEquals(got, recs)
    finally alloc.close()

  test("Tagged: list<string> incl. empty list and null element"):
    val recs = List(
      Tagged(1, Seq("a", "b")),
      Tagged(2, Seq.empty),
      Tagged(3, Seq("x", null, "z"))
    )
    val alloc = new RootAllocator(Long.MaxValue)
    try
      val got = roundtrip(recs, ArrowRowSerializer.derived[Tagged](alloc), ArrowRowDeserializer.derived[Tagged])
      assertEquals(got, recs)
    finally alloc.close()

  test("Nums: list<int> (non-null elements)"):
    val recs = List(Nums(1, Seq(1, 2, 3)), Nums(2, Seq.empty), Nums(3, Seq(Int.MinValue, 0, Int.MaxValue)))
    val alloc = new RootAllocator(Long.MaxValue)
    try
      val got = roundtrip(recs, ArrowRowSerializer.derived[Nums](alloc), ArrowRowDeserializer.derived[Nums])
      assertEquals(got, recs)
    finally alloc.close()

  test("Squad: list<struct>"):
    val recs = List(
      Squad("a", Seq(Point(1, 1), Point(2, 2))),
      Squad("b", Seq.empty),
      Squad("c", Seq(Point(9, 9)))
    )
    val alloc = new RootAllocator(Long.MaxValue)
    try
      val got = roundtrip(recs, ArrowRowSerializer.derived[Squad](alloc), ArrowRowDeserializer.derived[Squad])
      assertEquals(got, recs)
    finally alloc.close()

  test("Holder: struct containing a list"):
    val recs = List(Holder(1, Tagged(10, Seq("p", "q"))), Holder(2, Tagged(20, Seq.empty)))
    val alloc = new RootAllocator(Long.MaxValue)
    try
      val got = roundtrip(recs, ArrowRowSerializer.derived[Holder](alloc), ArrowRowDeserializer.derived[Holder])
      assertEquals(got, recs)
    finally alloc.close()

  test("OptList: nullable list (Some/None/empty)"):
    val recs = List(OptList(1, Some(Seq(1, 2, 3))), OptList(2, None), OptList(3, Some(Seq.empty)))
    val alloc = new RootAllocator(Long.MaxValue)
    try
      val got = roundtrip(recs, ArrowRowSerializer.derived[OptList](alloc), ArrowRowDeserializer.derived[OptList])
      assertEquals(got, recs)
    finally alloc.close()

  test("Vecs: Vector[Long] reconstructs as Vector"):
    val recs = List(Vecs(1, Vector(1L, 2L, 3L)), Vecs(2, Vector.empty))
    val alloc = new RootAllocator(Long.MaxValue)
    try
      val got = roundtrip(recs, ArrowRowSerializer.derived[Vecs](alloc), ArrowRowDeserializer.derived[Vecs])
      assertEquals(got, recs)
      assert(got.head.vs.isInstanceOf[Vector[?]])
    finally alloc.close()

  test("Arr: Array[Long] round-trips (compare element-wise)"):
    val recs = List(Arr(1, Array(1L, 2L, 3L)), Arr(2, Array.empty[Long]))
    val alloc = new RootAllocator(Long.MaxValue)
    try
      val got = roundtrip(recs, ArrowRowSerializer.derived[Arr](alloc), ArrowRowDeserializer.derived[Arr])
      assertEquals(got.size, recs.size)
      got.zip(recs).foreach { case (a, b) =>
        assertEquals(a.id, b.id)
        assertEquals(a.nums.toList, b.nums.toList)
      }
    finally alloc.close()
