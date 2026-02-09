package protocatalyst.executor

import java.nio.file.{Files, Path}

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector._

import protocatalyst.arrow.ArrowAllocator
import protocatalyst.arrow.parquet.ParquetIO
import protocatalyst.executor.ParquetSupport._
import protocatalyst.executor.exec._
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan._
import protocatalyst.schema.ProtoSchema
import protocatalyst.types._

class ParquetSupportSuite extends munit.FunSuite:

  private var allocator: BufferAllocator = scala.compiletime.uninitialized
  private var tempDir: Path = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    allocator = ArrowAllocator.createRoot()
    tempDir = Files.createTempDirectory("parquet-support-test")

  override def afterAll(): Unit =
    Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete(_))

  private def tempFile(name: String): String =
    tempDir.resolve(name).toString

  private def writeTestFile(path: String): ProtoSchema =
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("name", ProtoType.StringType, nullable = false),
        ProtoStructField("age", ProtoType.IntegerType, nullable = false),
        ProtoStructField("score", ProtoType.DoubleType, nullable = true)
      )
    )
    val batch = Batch.fromValues(
      Vector(
        Vector(
          ProtoExpr.lit("Alice"),
          ProtoExpr.lit(30),
          ProtoExpr.Literal(LiteralValue.DoubleValue(95.5))
        ),
        Vector(
          ProtoExpr.lit("Bob"),
          ProtoExpr.lit(25),
          ProtoExpr.Literal(LiteralValue.DoubleValue(87.3))
        ),
        Vector(
          ProtoExpr.lit("Carol"),
          ProtoExpr.lit(35),
          ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.DoubleType))
        ),
        Vector(
          ProtoExpr.lit("Dave"),
          ProtoExpr.lit(28),
          ProtoExpr.Literal(LiteralValue.DoubleValue(92.1))
        )
      ),
      schema,
      allocator
    )
    ParquetSupport.writeBatch(batch, path)
    batch.close()
    schema

  test("readBatch: reads Parquet file into Batch") {
    val path = tempFile("read_test.parquet")
    writeTestFile(path)

    val batch = ParquetSupport.readBatch(path, allocator)
    assertEquals(batch.rowCount, 4)
    assertEquals(batch.numColumns, 3)
    assertEquals(batch.schema.fields(0).name, "name")
    assertEquals(batch.schema.fields(1).name, "age")
    assertEquals(batch.schema.fields(2).name, "score")

    val nameVec = batch.column("name").asInstanceOf[VarCharVector]
    assertEquals(new String(nameVec.get(0), "UTF-8"), "Alice")
    assertEquals(new String(nameVec.get(3), "UTF-8"), "Dave")

    val ageVec = batch.column("age").asInstanceOf[IntVector]
    assertEquals(ageVec.get(1), 25)

    // Null value preserved
    val scoreVec = batch.column("score").asInstanceOf[Float8Vector]
    assert(scoreVec.isNull(2), "Carol's score should be null")
    assertEquals(scoreVec.get(0), 95.5, 0.01)
    batch.close()
  }

  test("writeBatch + readBatch roundtrip") {
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("id", ProtoType.IntegerType, nullable = false),
        ProtoStructField("label", ProtoType.StringType, nullable = true)
      )
    )
    val batch = Batch.fromValues(
      Vector(
        Vector(ProtoExpr.lit(1), ProtoExpr.lit("hello")),
        Vector(ProtoExpr.lit(2), ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.StringType))),
        Vector(ProtoExpr.lit(3), ProtoExpr.lit("world"))
      ),
      schema,
      allocator
    )

    val path = tempFile("roundtrip.parquet")
    ParquetSupport.writeBatch(batch, path)
    batch.close()

    val readBack = ParquetSupport.readBatch(path, allocator)
    assertEquals(readBack.rowCount, 3)
    val idVec = readBack.column("id").asInstanceOf[IntVector]
    val lblVec = readBack.column("label").asInstanceOf[VarCharVector]
    assertEquals(idVec.get(0), 1)
    assertEquals(idVec.get(2), 3)
    assertEquals(new String(lblVec.get(0), "UTF-8"), "hello")
    assert(lblVec.isNull(1))
    assertEquals(new String(lblVec.get(2), "UTF-8"), "world")
    readBack.close()
  }

  test("registerParquetTable: query Parquet-backed table") {
    val path = tempFile("catalog_test.parquet")
    writeTestFile(path)

    val catalog = Catalog()
    catalog.registerParquetTable("users", path, allocator)

    // Verify table is registered
    assert(catalog.getTable("users").isDefined)
    val tableSchema = catalog.tableSchema("users").get
    assertEquals(tableSchema.fields.size, 3)

    // Verify statistics are populated
    val stats = catalog.getStatistics("users")
    assertEquals(stats.rowCount, 4L)
    assert(stats.sizeInBytes > 0)
  }

  test("registerParquetTable: execute query against Parquet data") {
    val path = tempFile("query_test.parquet")
    writeTestFile(path)

    val catalog = Catalog()
    catalog.registerParquetTable("people", path, allocator)

    // Filter: age > 28
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.Gt(
        ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false),
        ProtoExpr.lit(28)
      ),
      ProtoLogicalPlan.RelationRef(
        "people",
        None,
        null.asInstanceOf[protocatalyst.schema.SchemaContract]
      )
    )

    val executor = PlanExecutor(catalog, allocator)
    val result = executor.execute(plan)

    assertEquals(result.rowCount, 2)
    val rows = QueryRunner.collect(result)
    val names = rows.map(_("name")).toSet
    assertEquals(names, Set[Any]("Alice", "Carol"))
  }

  test("writeBatch with compression config") {
    val schema = ProtoSchema(
      Vector(ProtoStructField("x", ProtoType.IntegerType, nullable = false))
    )
    val batch = Batch.fromValues(
      Vector(Vector(ProtoExpr.lit(42))),
      schema,
      allocator
    )

    val path = tempFile("compressed.parquet")
    ParquetSupport.writeBatch(
      batch,
      path,
      ParquetIO.WriteConfig(compression = ParquetIO.Compression.Uncompressed)
    )
    batch.close()

    val readBack = ParquetSupport.readBatch(path, allocator)
    assertEquals(readBack.rowCount, 1)
    assertEquals(readBack.column("x").asInstanceOf[IntVector].get(0), 42)
    readBack.close()
  }
