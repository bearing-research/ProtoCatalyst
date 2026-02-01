package protocatalyst.arrow

import protocatalyst.encoder.ProtoEncoder
import org.apache.arrow.vector.*
import org.apache.arrow.memory.RootAllocator
import java.nio.charset.StandardCharsets
import scala.compiletime.uninitialized

// Test case classes - defined at package level for proper derivation
case class ArrowSimple(name: String, age: Int) derives ProtoEncoder
case class ArrowPrimitives(
    b: Boolean,
    by: Byte,
    s: Short,
    i: Int,
    l: Long,
    f: Float,
    d: Double
) derives ProtoEncoder
case class ArrowWithStrings(id: Long, firstName: String, lastName: String) derives ProtoEncoder
case class ArrowWithOption(id: Int, name: Option[String], score: Option[Double])
    derives ProtoEncoder
case class ArrowNested(name: String, point: ArrowPoint) derives ProtoEncoder
case class ArrowPoint(x: Int, y: Int) derives ProtoEncoder
case class ArrowWithDate(id: Int, date: java.time.LocalDate) derives ProtoEncoder
case class ArrowWithTimestamp(id: Int, ts: java.time.Instant) derives ProtoEncoder

class InlineArrowWriterSuite extends munit.FunSuite:

  // Allocator for all tests
  var allocator: RootAllocator = uninitialized

  override def beforeEach(context: BeforeEach): Unit =
    allocator = ArrowAllocator.createRoot()

  override def afterEach(context: AfterEach): Unit =
    allocator.close()

  // === Basic Primitive Tests ===

  test("write simple case class with String and Int"):
    val writer = InlineArrowWriter.derived[ArrowSimple]
    val data = Seq(
      ArrowSimple("Alice", 30),
      ArrowSimple("Bob", 25)
    )

    val root = VectorSchemaRoot.create(writer.schema, allocator)
    try
      writer.write(data, root)

      assertEquals(root.getRowCount, 2)

      val nameVec = root.getVector(0).asInstanceOf[VarCharVector]
      val ageVec = root.getVector(1).asInstanceOf[IntVector]

      assertEquals(new String(nameVec.get(0), StandardCharsets.UTF_8), "Alice")
      assertEquals(new String(nameVec.get(1), StandardCharsets.UTF_8), "Bob")
      assertEquals(ageVec.get(0), 30)
      assertEquals(ageVec.get(1), 25)
    finally root.close()

  test("write all primitive types"):
    val writer = InlineArrowWriter.derived[ArrowPrimitives]
    val data = Seq(
      ArrowPrimitives(true, 1.toByte, 2.toShort, 3, 4L, 5.0f, 6.0)
    )

    val root = VectorSchemaRoot.create(writer.schema, allocator)
    try
      writer.write(data, root)

      assertEquals(root.getRowCount, 1)
      assertEquals(root.getVector(0).asInstanceOf[BitVector].get(0), 1)
      assertEquals(root.getVector(1).asInstanceOf[TinyIntVector].get(0), 1.toByte)
      assertEquals(root.getVector(2).asInstanceOf[SmallIntVector].get(0), 2.toShort)
      assertEquals(root.getVector(3).asInstanceOf[IntVector].get(0), 3)
      assertEquals(root.getVector(4).asInstanceOf[BigIntVector].get(0), 4L)
      assertEquals(root.getVector(5).asInstanceOf[Float4Vector].get(0), 5.0f)
      assertEquals(root.getVector(6).asInstanceOf[Float8Vector].get(0), 6.0)
    finally root.close()

  // === String Tests ===

  test("write multiple string fields"):
    val writer = InlineArrowWriter.derived[ArrowWithStrings]
    val data = Seq(
      ArrowWithStrings(1L, "John", "Doe"),
      ArrowWithStrings(2L, "Jane", "Smith")
    )

    val root = VectorSchemaRoot.create(writer.schema, allocator)
    try
      writer.write(data, root)

      assertEquals(root.getRowCount, 2)

      val firstNameVec = root.getVector(1).asInstanceOf[VarCharVector]
      val lastNameVec = root.getVector(2).asInstanceOf[VarCharVector]

      assertEquals(new String(firstNameVec.get(0), StandardCharsets.UTF_8), "John")
      assertEquals(new String(lastNameVec.get(0), StandardCharsets.UTF_8), "Doe")
      assertEquals(new String(firstNameVec.get(1), StandardCharsets.UTF_8), "Jane")
      assertEquals(new String(lastNameVec.get(1), StandardCharsets.UTF_8), "Smith")
    finally root.close()

  test("write null strings"):
    val writer = InlineArrowWriter.derived[ArrowWithStrings]
    val data = Seq(
      ArrowWithStrings(1L, "Hello", null)
    )

    val root = VectorSchemaRoot.create(writer.schema, allocator)
    try
      writer.write(data, root)

      assertEquals(root.getRowCount, 1)

      val firstNameVec = root.getVector(1).asInstanceOf[VarCharVector]
      val lastNameVec = root.getVector(2).asInstanceOf[VarCharVector]

      assertEquals(new String(firstNameVec.get(0), StandardCharsets.UTF_8), "Hello")
      assert(lastNameVec.isNull(0))
    finally root.close()

  // === Option Tests ===

  test("write Option[String] with Some and None"):
    val writer = InlineArrowWriter.derived[ArrowWithOption]
    val data = Seq(
      ArrowWithOption(1, Some("present"), Some(1.5)),
      ArrowWithOption(2, None, None),
      ArrowWithOption(3, Some("also present"), None)
    )

    val root = VectorSchemaRoot.create(writer.schema, allocator)
    try
      writer.write(data, root)

      assertEquals(root.getRowCount, 3)

      val nameVec = root.getVector(1).asInstanceOf[VarCharVector]
      val scoreVec = root.getVector(2).asInstanceOf[Float8Vector]

      // Row 0: Some values
      assert(!nameVec.isNull(0))
      assertEquals(new String(nameVec.get(0), StandardCharsets.UTF_8), "present")
      assert(!scoreVec.isNull(0))
      assertEquals(scoreVec.get(0), 1.5)

      // Row 1: None values
      assert(nameVec.isNull(1))
      assert(scoreVec.isNull(1))

      // Row 2: Mixed
      assert(!nameVec.isNull(2))
      assert(scoreVec.isNull(2))
    finally root.close()

  // === Large Dataset Tests ===

  test("write 10000 rows"):
    val writer = InlineArrowWriter.derived[ArrowSimple]
    val data = (1 to 10000).map(i => ArrowSimple(s"User$i", i)).toSeq

    val root = VectorSchemaRoot.create(writer.schema, allocator)
    try
      writer.write(data, root)

      assertEquals(root.getRowCount, 10000)

      val nameVec = root.getVector(0).asInstanceOf[VarCharVector]
      val ageVec = root.getVector(1).asInstanceOf[IntVector]

      // Spot check
      assertEquals(new String(nameVec.get(0), StandardCharsets.UTF_8), "User1")
      assertEquals(ageVec.get(0), 1)
      assertEquals(new String(nameVec.get(9999), StandardCharsets.UTF_8), "User10000")
      assertEquals(ageVec.get(9999), 10000)
    finally root.close()

  // === ArrowBatchBuilder Tests ===

  test("ArrowBatchBuilder.scoped creates and closes resources"):
    val data = Seq(ArrowSimple("Alice", 30))

    ArrowBatchBuilder.scoped(data) { root =>
      assertEquals(root.getRowCount, 1)
      val nameVec = root.getVector(0).asInstanceOf[VarCharVector]
      assertEquals(new String(nameVec.get(0), StandardCharsets.UTF_8), "Alice")
    }

  test("ArrowBatchBuilder.create with manual resource management"):
    val builder = ArrowBatchBuilder.create[ArrowSimple]
    try
      val data = Seq(ArrowSimple("Bob", 25))
      val root = builder.build(data)
      try
        assertEquals(root.getRowCount, 1)
      finally
        root.close()
    finally builder.close()

  // === Schema Tests ===

  test("writer schema matches encoder schema"):
    val writer = InlineArrowWriter.derived[ArrowSimple]

    assertEquals(writer.schema.getFields.size(), 2)
    assertEquals(writer.schema.getFields.get(0).getName, "name")
    assertEquals(writer.schema.getFields.get(1).getName, "age")

  test("writer fieldCount matches number of fields"):
    val simpleWriter = InlineArrowWriter.derived[ArrowSimple]
    assertEquals(simpleWriter.fieldCount, 2)

    val primitivesWriter = InlineArrowWriter.derived[ArrowPrimitives]
    assertEquals(primitivesWriter.fieldCount, 7)

  // === Empty Sequence Test ===

  test("write empty sequence"):
    val writer = InlineArrowWriter.derived[ArrowSimple]
    val data = Seq.empty[ArrowSimple]

    val root = VectorSchemaRoot.create(writer.schema, allocator)
    try
      writer.write(data, root)
      assertEquals(root.getRowCount, 0)
    finally root.close()

  // === Temporal Type Tests ===

  test("write LocalDate field"):
    val writer = InlineArrowWriter.derived[ArrowWithDate]
    val date = java.time.LocalDate.of(2024, 1, 15)
    val data = Seq(ArrowWithDate(1, date))

    val root = VectorSchemaRoot.create(writer.schema, allocator)
    try
      writer.write(data, root)

      assertEquals(root.getRowCount, 1)
      val dateVec = root.getVector(1).asInstanceOf[DateDayVector]
      assertEquals(dateVec.get(0), date.toEpochDay.toInt)
    finally root.close()

  test("write Instant field"):
    val writer = InlineArrowWriter.derived[ArrowWithTimestamp]
    val instant = java.time.Instant.parse("2024-01-15T10:30:00Z")
    val data = Seq(ArrowWithTimestamp(1, instant))

    val root = VectorSchemaRoot.create(writer.schema, allocator)
    try
      writer.write(data, root)

      assertEquals(root.getRowCount, 1)
      val tsVec = root.getVector(1).asInstanceOf[TimeStampMicroTZVector]
      val micros = instant.getEpochSecond * 1000000L + instant.getNano / 1000
      assertEquals(tsVec.get(0), micros)
    finally root.close()
