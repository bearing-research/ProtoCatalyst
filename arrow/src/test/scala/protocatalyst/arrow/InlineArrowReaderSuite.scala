package protocatalyst.arrow
import scala.compiletime.uninitialized

import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector._

class InlineArrowReaderSuite extends munit.FunSuite:

  // Allocator for all tests
  var allocator: RootAllocator = uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    allocator = ArrowAllocator.createRoot()

  override def afterEach(context: AfterEach): Unit =
    allocator.close()

  // === Basic Primitive Tests ===

  test("read simple case class with String and Int"):
    val writer = InlineArrowWriter.derived[ArrowSimple]
    val reader = InlineArrowReader.derived[ArrowSimple]
    val data = Seq(
      ArrowSimple("Alice", 30),
      ArrowSimple("Bob", 25)
    )

    val root = VectorSchemaRoot.create(writer.schema, allocator)
    try
      writer.write(data, root)
      val result = reader.read(root)

      assertEquals(result.size, 2)
      assertEquals(result(0), ArrowSimple("Alice", 30))
      assertEquals(result(1), ArrowSimple("Bob", 25))
    finally root.close()

  test("read all primitive types"):
    val writer = InlineArrowWriter.derived[ArrowPrimitives]
    val reader = InlineArrowReader.derived[ArrowPrimitives]
    val data = Seq(
      ArrowPrimitives(true, 1.toByte, 2.toShort, 3, 4L, 5.0f, 6.0)
    )

    val root = VectorSchemaRoot.create(writer.schema, allocator)
    try
      writer.write(data, root)
      val result = reader.read(root)

      assertEquals(result.size, 1)
      assertEquals(result(0), ArrowPrimitives(true, 1.toByte, 2.toShort, 3, 4L, 5.0f, 6.0))
    finally root.close()

  // === String Tests ===

  test("read multiple string fields"):
    val writer = InlineArrowWriter.derived[ArrowWithStrings]
    val reader = InlineArrowReader.derived[ArrowWithStrings]
    val data = Seq(
      ArrowWithStrings(1L, "John", "Doe"),
      ArrowWithStrings(2L, "Jane", "Smith")
    )

    val root = VectorSchemaRoot.create(writer.schema, allocator)
    try
      writer.write(data, root)
      val result = reader.read(root)

      assertEquals(result.size, 2)
      assertEquals(result(0), ArrowWithStrings(1L, "John", "Doe"))
      assertEquals(result(1), ArrowWithStrings(2L, "Jane", "Smith"))
    finally root.close()

  test("read null strings"):
    val writer = InlineArrowWriter.derived[ArrowWithStrings]
    val reader = InlineArrowReader.derived[ArrowWithStrings]
    val data = Seq(
      ArrowWithStrings(1L, "Hello", null)
    )

    val root = VectorSchemaRoot.create(writer.schema, allocator)
    try
      writer.write(data, root)
      val result = reader.read(root)

      assertEquals(result.size, 1)
      assertEquals(result(0).id, 1L)
      assertEquals(result(0).firstName, "Hello")
      assertEquals(result(0).lastName, null)
    finally root.close()

  // === Option Tests ===

  test("read Option[String] with Some and None"):
    val writer = InlineArrowWriter.derived[ArrowWithOption]
    val reader = InlineArrowReader.derived[ArrowWithOption]
    val data = Seq(
      ArrowWithOption(1, Some("present"), Some(1.5)),
      ArrowWithOption(2, None, None),
      ArrowWithOption(3, Some("also present"), None)
    )

    val root = VectorSchemaRoot.create(writer.schema, allocator)
    try
      writer.write(data, root)
      val result = reader.read(root)

      assertEquals(result.size, 3)
      assertEquals(result(0), ArrowWithOption(1, Some("present"), Some(1.5)))
      assertEquals(result(1), ArrowWithOption(2, None, None))
      assertEquals(result(2), ArrowWithOption(3, Some("also present"), None))
    finally root.close()

  // === Large Dataset Tests ===

  test("read 10000 rows"):
    val writer = InlineArrowWriter.derived[ArrowSimple]
    val reader = InlineArrowReader.derived[ArrowSimple]
    val data = (1 to 10000).map(i => ArrowSimple(s"User$i", i)).toSeq

    val root = VectorSchemaRoot.create(writer.schema, allocator)
    try
      writer.write(data, root)
      val result = reader.read(root)

      assertEquals(result.size, 10000)
      assertEquals(result(0), ArrowSimple("User1", 1))
      assertEquals(result(9999), ArrowSimple("User10000", 10000))
    finally root.close()

  // === Single Row Read Tests ===

  test("readRow reads single row at index"):
    val writer = InlineArrowWriter.derived[ArrowSimple]
    val reader = InlineArrowReader.derived[ArrowSimple]
    val data = Seq(
      ArrowSimple("Alice", 30),
      ArrowSimple("Bob", 25),
      ArrowSimple("Charlie", 35)
    )

    val root = VectorSchemaRoot.create(writer.schema, allocator)
    try
      writer.write(data, root)

      assertEquals(reader.readRow(root, 0), ArrowSimple("Alice", 30))
      assertEquals(reader.readRow(root, 1), ArrowSimple("Bob", 25))
      assertEquals(reader.readRow(root, 2), ArrowSimple("Charlie", 35))
    finally root.close()

  // === Schema Tests ===

  test("reader schema matches encoder schema"):
    val reader = InlineArrowReader.derived[ArrowSimple]

    assertEquals(reader.schema.getFields.size(), 2)
    assertEquals(reader.schema.getFields.get(0).getName, "name")
    assertEquals(reader.schema.getFields.get(1).getName, "age")

  test("reader fieldCount matches number of fields"):
    val simpleReader = InlineArrowReader.derived[ArrowSimple]
    assertEquals(simpleReader.fieldCount, 2)

    val primitivesReader = InlineArrowReader.derived[ArrowPrimitives]
    assertEquals(primitivesReader.fieldCount, 7)

  // === Empty Sequence Test ===

  test("read empty sequence"):
    val writer = InlineArrowWriter.derived[ArrowSimple]
    val reader = InlineArrowReader.derived[ArrowSimple]
    val data = Seq.empty[ArrowSimple]

    val root = VectorSchemaRoot.create(writer.schema, allocator)
    try
      writer.write(data, root)
      val result = reader.read(root)
      assertEquals(result.size, 0)
    finally root.close()

  // === Temporal Type Tests ===

  test("read LocalDate field"):
    val writer = InlineArrowWriter.derived[ArrowWithDate]
    val reader = InlineArrowReader.derived[ArrowWithDate]
    val date = java.time.LocalDate.of(2024, 1, 15)
    val data = Seq(ArrowWithDate(1, date))

    val root = VectorSchemaRoot.create(writer.schema, allocator)
    try
      writer.write(data, root)
      val result = reader.read(root)

      assertEquals(result.size, 1)
      assertEquals(result(0), ArrowWithDate(1, date))
    finally root.close()

  test("read Instant field"):
    val writer = InlineArrowWriter.derived[ArrowWithTimestamp]
    val reader = InlineArrowReader.derived[ArrowWithTimestamp]
    val instant = java.time.Instant.parse("2024-01-15T10:30:00Z")
    val data = Seq(ArrowWithTimestamp(1, instant))

    val root = VectorSchemaRoot.create(writer.schema, allocator)
    try
      writer.write(data, root)
      val result = reader.read(root)

      assertEquals(result.size, 1)
      assertEquals(result(0), ArrowWithTimestamp(1, instant))
    finally root.close()
