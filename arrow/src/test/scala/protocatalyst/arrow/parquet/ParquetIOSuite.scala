package protocatalyst.arrow.parquet

import java.nio.file.{Files, Path}

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector._

import protocatalyst.arrow.{ArrowAllocator, ArrowSchemaConverter}
import protocatalyst.schema._
import protocatalyst.types._

class ParquetIOSuite extends munit.FunSuite:

  private var allocator: BufferAllocator = scala.compiletime.uninitialized
  private var tempDir: Path = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    allocator = ArrowAllocator.createRoot()
    tempDir = Files.createTempDirectory("parquet-io-test")

  override def afterAll(): Unit =
    // Clean up temp files
    Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))

  private def tempFile(name: String): String =
    tempDir.resolve(name).toString

  private def createRoot(schema: ProtoSchema): VectorSchemaRoot =
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(schema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.allocateNew()
    root

  test("roundtrip: integers") {
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("id", ProtoType.IntegerType, nullable = false),
        ProtoStructField("value", ProtoType.LongType, nullable = true)
      )
    )
    val root = createRoot(schema)
    val idVec = root.getVector(0).asInstanceOf[IntVector]
    val valVec = root.getVector(1).asInstanceOf[BigIntVector]
    for i <- 0 until 5 do
      idVec.setSafe(i, i + 1)
      if i % 2 == 0 then valVec.setSafe(i, i.toLong * 100)
      else valVec.setNull(i)
    root.setRowCount(5)

    val path = tempFile("integers.parquet")
    ParquetIO.write(root, schema, path)
    root.close()

    val (readRoot, readSchema) = ParquetIO.read(path, allocator)
    assertEquals(readRoot.getRowCount, 5)
    assertEquals(readSchema.fields.size, 2)

    val rId = readRoot.getVector(0).asInstanceOf[IntVector]
    val rVal = readRoot.getVector(1).asInstanceOf[BigIntVector]
    for i <- 0 until 5 do
      assertEquals(rId.get(i), i + 1)
      if i % 2 == 0 then assertEquals(rVal.get(i), i.toLong * 100)
      else assert(rVal.isNull(i), s"Expected null at row $i")
    readRoot.close()
  }

  test("roundtrip: float and double") {
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("f", ProtoType.FloatType, nullable = false),
        ProtoStructField("d", ProtoType.DoubleType, nullable = false)
      )
    )
    val root = createRoot(schema)
    val fVec = root.getVector(0).asInstanceOf[Float4Vector]
    val dVec = root.getVector(1).asInstanceOf[Float8Vector]
    fVec.setSafe(0, 1.5f)
    fVec.setSafe(1, -2.75f)
    dVec.setSafe(0, 3.14159)
    dVec.setSafe(1, -0.001)
    root.setRowCount(2)

    val path = tempFile("floats.parquet")
    ParquetIO.write(root, schema, path)
    root.close()

    val (readRoot, _) = ParquetIO.read(path, allocator)
    val rF = readRoot.getVector(0).asInstanceOf[Float4Vector]
    val rD = readRoot.getVector(1).asInstanceOf[Float8Vector]
    assertEquals(rF.get(0), 1.5f)
    assertEquals(rF.get(1), -2.75f)
    assertEquals(rD.get(0), 3.14159)
    assertEquals(rD.get(1), -0.001)
    readRoot.close()
  }

  test("roundtrip: boolean and string") {
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("flag", ProtoType.BooleanType, nullable = false),
        ProtoStructField("name", ProtoType.StringType, nullable = true)
      )
    )
    val root = createRoot(schema)
    val bVec = root.getVector(0).asInstanceOf[BitVector]
    val sVec = root.getVector(1).asInstanceOf[VarCharVector]
    bVec.setSafe(0, 1)
    bVec.setSafe(1, 0)
    bVec.setSafe(2, 1)
    val hello = "hello".getBytes("UTF-8")
    sVec.setSafe(0, hello, 0, hello.length)
    sVec.setNull(1)
    val unicode = "\u00e9\u00e0\u00fc".getBytes("UTF-8")
    sVec.setSafe(2, unicode, 0, unicode.length)
    root.setRowCount(3)

    val path = tempFile("bool_string.parquet")
    ParquetIO.write(root, schema, path)
    root.close()

    val (readRoot, _) = ParquetIO.read(path, allocator)
    val rB = readRoot.getVector(0).asInstanceOf[BitVector]
    val rS = readRoot.getVector(1).asInstanceOf[VarCharVector]
    assertEquals(rB.get(0), 1)
    assertEquals(rB.get(1), 0)
    assertEquals(rB.get(2), 1)
    assertEquals(new String(rS.get(0), "UTF-8"), "hello")
    assert(rS.isNull(1))
    assertEquals(new String(rS.get(2), "UTF-8"), "\u00e9\u00e0\u00fc")
    readRoot.close()
  }

  test("roundtrip: date and timestamp") {
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("dt", ProtoType.DateType, nullable = false),
        ProtoStructField("ts", ProtoType.TimestampType, nullable = false)
      )
    )
    val root = createRoot(schema)
    val dateVec = root.getVector(0).asInstanceOf[DateDayVector]
    val tsVec = root.getVector(1).asInstanceOf[TimeStampMicroTZVector]
    dateVec.setSafe(0, 19000) // some epoch day
    tsVec.setSafe(0, 1704067200000000L) // 2024-01-01T00:00:00Z in micros
    root.setRowCount(1)

    val path = tempFile("temporal.parquet")
    ParquetIO.write(root, schema, path)
    root.close()

    val (readRoot, _) = ParquetIO.read(path, allocator)
    assertEquals(readRoot.getVector(0).asInstanceOf[DateDayVector].get(0), 19000)
    assertEquals(
      readRoot.getVector(1).asInstanceOf[TimeStampMicroTZVector].get(0),
      1704067200000000L
    )
    readRoot.close()
  }

  test("roundtrip: byte and short") {
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("b", ProtoType.ByteType, nullable = false),
        ProtoStructField("s", ProtoType.ShortType, nullable = false)
      )
    )
    val root = createRoot(schema)
    root.getVector(0).asInstanceOf[TinyIntVector].setSafe(0, 42)
    root.getVector(1).asInstanceOf[SmallIntVector].setSafe(0, 1000)
    root.setRowCount(1)

    val path = tempFile("byte_short.parquet")
    ParquetIO.write(root, schema, path)
    root.close()

    val (readRoot, _) = ParquetIO.read(path, allocator)
    assertEquals(readRoot.getVector(0).asInstanceOf[TinyIntVector].get(0), 42: Byte)
    assertEquals(readRoot.getVector(1).asInstanceOf[SmallIntVector].get(0), 1000: Short)
    readRoot.close()
  }

  test("roundtrip: decimal types") {
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("dec_small", ProtoType.DecimalType(9, 2), nullable = false),
        ProtoStructField("dec_large", ProtoType.DecimalType(18, 6), nullable = false)
      )
    )
    val root = createRoot(schema)
    root
      .getVector(0)
      .asInstanceOf[DecimalVector]
      .setSafe(0, new java.math.BigDecimal("1234567.89"))
    root
      .getVector(1)
      .asInstanceOf[DecimalVector]
      .setSafe(0, new java.math.BigDecimal("123456789012.345678"))
    root.setRowCount(1)

    val path = tempFile("decimals.parquet")
    ParquetIO.write(root, schema, path)
    root.close()

    val (readRoot, _) = ParquetIO.read(path, allocator)
    val d0 = readRoot.getVector(0).asInstanceOf[DecimalVector].getObject(0)
    val d1 = readRoot.getVector(1).asInstanceOf[DecimalVector].getObject(0)
    assertEquals(d0.toPlainString, "1234567.89")
    assertEquals(d1.toPlainString, "123456789012.345678")
    readRoot.close()
  }

  test("roundtrip: binary") {
    val schema = ProtoSchema(
      Vector(ProtoStructField("data", ProtoType.BinaryType, nullable = false))
    )
    val root = createRoot(schema)
    val bytes = Array[Byte](0, 1, 2, 127, -128)
    root.getVector(0).asInstanceOf[VarBinaryVector].setSafe(0, bytes, 0, bytes.length)
    root.setRowCount(1)

    val path = tempFile("binary.parquet")
    ParquetIO.write(root, schema, path)
    root.close()

    val (readRoot, _) = ParquetIO.read(path, allocator)
    val readBytes = readRoot.getVector(0).asInstanceOf[VarBinaryVector].get(0)
    assertEquals(readBytes.toSeq, bytes.toSeq)
    readRoot.close()
  }

  test("roundtrip: empty file (0 rows)") {
    val schema = ProtoSchema(
      Vector(ProtoStructField("x", ProtoType.IntegerType, nullable = false))
    )
    val root = createRoot(schema)
    root.setRowCount(0)

    val path = tempFile("empty.parquet")
    ParquetIO.write(root, schema, path)
    root.close()

    val (readRoot, readSchema) = ParquetIO.read(path, allocator)
    assertEquals(readRoot.getRowCount, 0)
    assertEquals(readSchema.fields.size, 1)
    readRoot.close()
  }

  test("readSchema: returns schema without reading data") {
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("a", ProtoType.IntegerType, nullable = false),
        ProtoStructField("b", ProtoType.StringType, nullable = true)
      )
    )
    val root = createRoot(schema)
    root.getVector(0).asInstanceOf[IntVector].setSafe(0, 1)
    val bytes = "x".getBytes("UTF-8")
    root.getVector(1).asInstanceOf[VarCharVector].setSafe(0, bytes, 0, bytes.length)
    root.setRowCount(1)

    val path = tempFile("schema_only.parquet")
    ParquetIO.write(root, schema, path)
    root.close()

    val readSchema = ParquetIO.readSchema(path)
    assertEquals(readSchema.fields.size, 2)
    assertEquals(readSchema.fields(0).dataType, ProtoType.IntegerType)
    assertEquals(readSchema.fields(1).dataType, ProtoType.StringType)
  }

  test("readRowCount: returns correct count") {
    val schema = ProtoSchema(
      Vector(ProtoStructField("x", ProtoType.IntegerType, nullable = false))
    )
    val root = createRoot(schema)
    for i <- 0 until 42 do root.getVector(0).asInstanceOf[IntVector].setSafe(i, i)
    root.setRowCount(42)

    val path = tempFile("row_count.parquet")
    ParquetIO.write(root, schema, path)
    root.close()

    assertEquals(ParquetIO.readRowCount(path), 42L)
  }

  test("readStatistics: returns row count and size") {
    val schema = ProtoSchema(
      Vector(ProtoStructField("x", ProtoType.IntegerType, nullable = false))
    )
    val root = createRoot(schema)
    for i <- 0 until 100 do root.getVector(0).asInstanceOf[IntVector].setSafe(i, i)
    root.setRowCount(100)

    val path = tempFile("stats.parquet")
    ParquetIO.write(root, schema, path)
    root.close()

    val stats = ParquetIO.readStatistics(path)
    assertEquals(stats.rowCount, 100L)
    assert(stats.sizeInBytes > 0, "Size should be positive")
  }

  test("roundtrip: large batch (1000 rows)") {
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("id", ProtoType.IntegerType, nullable = false),
        ProtoStructField("value", ProtoType.DoubleType, nullable = false),
        ProtoStructField("label", ProtoType.StringType, nullable = true)
      )
    )
    val root = createRoot(schema)
    val idVec = root.getVector(0).asInstanceOf[IntVector]
    val valVec = root.getVector(1).asInstanceOf[Float8Vector]
    val lblVec = root.getVector(2).asInstanceOf[VarCharVector]

    for i <- 0 until 1000 do
      idVec.setSafe(i, i)
      valVec.setSafe(i, i * 0.1)
      if i % 3 == 0 then lblVec.setNull(i)
      else
        val s = s"row_$i".getBytes("UTF-8")
        lblVec.setSafe(i, s, 0, s.length)
    root.setRowCount(1000)

    val path = tempFile("large.parquet")
    ParquetIO.write(root, schema, path)
    root.close()

    val (readRoot, _) = ParquetIO.read(path, allocator)
    assertEquals(readRoot.getRowCount, 1000)
    val rId = readRoot.getVector(0).asInstanceOf[IntVector]
    val rVal = readRoot.getVector(1).asInstanceOf[Float8Vector]
    val rLbl = readRoot.getVector(2).asInstanceOf[VarCharVector]

    // Spot check
    assertEquals(rId.get(0), 0)
    assertEquals(rId.get(999), 999)
    assertEquals(rVal.get(50), 5.0, 0.001)
    assert(rLbl.isNull(0))
    assertEquals(new String(rLbl.get(1), "UTF-8"), "row_1")
    readRoot.close()
  }

  test("write with uncompressed codec") {
    val schema = ProtoSchema(
      Vector(ProtoStructField("x", ProtoType.IntegerType, nullable = false))
    )
    val root = createRoot(schema)
    root.getVector(0).asInstanceOf[IntVector].setSafe(0, 42)
    root.setRowCount(1)

    val path = tempFile("uncompressed.parquet")
    ParquetIO.write(root, schema, path, ParquetIO.WriteConfig(ParquetIO.Compression.Uncompressed))
    root.close()

    val (readRoot, _) = ParquetIO.read(path, allocator)
    assertEquals(readRoot.getVector(0).asInstanceOf[IntVector].get(0), 42)
    readRoot.close()
  }
