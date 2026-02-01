package protocatalyst.benchmark

import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.execution.arrow.ArrowWriter
import org.apache.spark.sql.vectorized.{ArrowColumnVector, ColumnarBatch}
import org.apache.spark.sql.types._
import org.apache.spark.sql.catalyst.util.ArrayData
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, FieldType, Schema}
import org.apache.arrow.vector.types.{DateUnit, FloatingPointPrecision}
import org.apache.arrow.vector.types.{TimeUnit => ArrowTimeUnit}
import org.apache.arrow.memory.RootAllocator
import scala.jdk.CollectionConverters._

/** Benchmarks for Spark's Arrow serialization.
  *
  * Measures Spark's ArrowWriter performance for comparison with
  * ProtoCatalyst's compile-time InlineArrowWriter.
  *
  * Spark's approach:
  *   - Uses runtime type dispatch via DataType pattern matching
  *   - Creates ArrowFieldWriter instances at runtime
  *   - Each field write goes through type checking
  *
  * Run with: sbt 'benchmark-spark/Jmh/run SparkArrowBenchmarks'
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(2)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 20, time = 1)
class SparkArrowBenchmarks {

  // Batch size for Arrow operations
  val batchSize = 10000

  // Arrow allocator
  var allocator: RootAllocator = _

  // Schemas
  val simpleSchema: StructType = StructType(Seq(
    StructField("name", StringType, nullable = false),
    StructField("age", IntegerType, nullable = false)
  ))

  val wideSchema: StructType = StructType(Seq(
    StructField("f1", IntegerType, nullable = false),
    StructField("f2", IntegerType, nullable = false),
    StructField("f3", IntegerType, nullable = false),
    StructField("f4", IntegerType, nullable = false),
    StructField("f5", IntegerType, nullable = false),
    StructField("f6", IntegerType, nullable = false),
    StructField("f7", IntegerType, nullable = false),
    StructField("f8", IntegerType, nullable = false),
    StructField("f9", IntegerType, nullable = false),
    StructField("f10", IntegerType, nullable = false),
    StructField("f11", StringType, nullable = false),
    StructField("f12", StringType, nullable = false),
    StructField("f13", StringType, nullable = false),
    StructField("f14", StringType, nullable = false),
    StructField("f15", StringType, nullable = false),
    StructField("f16", DoubleType, nullable = false),
    StructField("f17", DoubleType, nullable = false),
    StructField("f18", DoubleType, nullable = false),
    StructField("f19", DoubleType, nullable = false),
    StructField("f20", DoubleType, nullable = false)
  ))

  val temporalSchema: StructType = StructType(Seq(
    StructField("date", DateType, nullable = false),
    StructField("time", TimestampType, nullable = false)
  ))

  // Test data as InternalRows
  var simpleRows: Array[InternalRow] = _
  var wideRows: Array[InternalRow] = _
  var temporalRows: Array[InternalRow] = _

  // Arrow roots for write benchmarks
  var simpleRoot: VectorSchemaRoot = _
  var wideRoot: VectorSchemaRoot = _
  var temporalRoot: VectorSchemaRoot = _

  // Pre-written roots for read benchmarks
  var preWrittenSimpleRoot: VectorSchemaRoot = _
  var preWrittenWideRoot: VectorSchemaRoot = _
  var preWrittenTemporalRoot: VectorSchemaRoot = _

  // Encoders for creating InternalRows
  val simpleEncoder: ExpressionEncoder[Simple] = ExpressionEncoder[Simple]()
  val wideEncoder: ExpressionEncoder[Wide] = ExpressionEncoder[Wide]()
  val temporalEncoder: ExpressionEncoder[Temporal] = ExpressionEncoder[Temporal]()

  @Setup
  def setup(): Unit = {
    allocator = new RootAllocator(Long.MaxValue)

    // Create test data as InternalRows
    val simpleSerializer = simpleEncoder.createSerializer()
    val wideSerializer = wideEncoder.createSerializer()
    val temporalSerializer = temporalEncoder.createSerializer()

    simpleRows = (1 to batchSize).map { i =>
      simpleSerializer(Simple(s"name$i", i)).copy()
    }.toArray

    wideRows = (1 to batchSize).map { i =>
      wideSerializer(Wide(
        i, i + 1, i + 2, i + 3, i + 4, i + 5, i + 6, i + 7, i + 8, i + 9,
        s"s$i", s"s${i + 1}", s"s${i + 2}", s"s${i + 3}", s"s${i + 4}",
        i * 1.1, i * 1.2, i * 1.3, i * 1.4, i * 1.5
      )).copy()
    }.toArray

    temporalRows = (1 to batchSize).map { i =>
      temporalSerializer(Temporal(
        java.time.LocalDate.ofEpochDay(i.toLong),
        java.time.Instant.ofEpochSecond(i.toLong * 86400)
      )).copy()
    }.toArray

    // Create Arrow roots using Spark's ArrowUtils
    simpleRoot = createRoot(simpleSchema)
    wideRoot = createRoot(wideSchema)
    temporalRoot = createRoot(temporalSchema)

    // Create and pre-write roots for read benchmarks
    preWrittenSimpleRoot = createRoot(simpleSchema)
    writeWithSpark(simpleRows, preWrittenSimpleRoot, simpleSchema)

    preWrittenWideRoot = createRoot(wideSchema)
    writeWithSpark(wideRows, preWrittenWideRoot, wideSchema)

    preWrittenTemporalRoot = createRoot(temporalSchema)
    writeWithSpark(temporalRows, preWrittenTemporalRoot, temporalSchema)
  }

  @TearDown
  def teardown(): Unit = {
    simpleRoot.close()
    wideRoot.close()
    temporalRoot.close()
    preWrittenSimpleRoot.close()
    preWrittenWideRoot.close()
    preWrittenTemporalRoot.close()
    allocator.close()
  }

  private def createRoot(schema: StructType): VectorSchemaRoot = {
    val arrowSchema = toArrowSchema(schema)
    VectorSchemaRoot.create(arrowSchema, allocator)
  }

  private def toArrowSchema(schema: StructType): Schema = {
    val fields = schema.fields.map { f =>
      val arrowType = f.dataType match {
        case IntegerType => new ArrowType.Int(32, true)
        case LongType => new ArrowType.Int(64, true)
        case DoubleType => new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)
        case StringType => ArrowType.Utf8.INSTANCE
        case DateType => new ArrowType.Date(DateUnit.DAY)
        case TimestampType => new ArrowType.Timestamp(ArrowTimeUnit.MICROSECOND, "UTC")
        case _ => ArrowType.Utf8.INSTANCE
      }
      new Field(f.name, new FieldType(f.nullable, arrowType, null), java.util.Collections.emptyList())
    }
    new Schema(fields.toList.asJava)
  }

  private def writeWithSpark(rows: Array[InternalRow], root: VectorSchemaRoot, schema: StructType): Unit = {
    val writer = ArrowWriter.create(root)
    rows.foreach(writer.write)
    writer.finish()
  }

  // ========== Simple (2 fields: String, Int) - Write ==========

  @Benchmark
  def sparkWriteSimple(): Unit = {
    simpleRoot.clear()
    val writer = ArrowWriter.create(simpleRoot)
    var i = 0
    while (i < batchSize) {
      writer.write(simpleRows(i))
      i += 1
    }
    writer.finish()
  }

  // ========== Simple - Read ==========

  @Benchmark
  def sparkReadSimple(): Array[Simple] = {
    val result = new Array[Simple](batchSize)
    val nameVec = new ArrowColumnVector(preWrittenSimpleRoot.getVector(0))
    val ageVec = new ArrowColumnVector(preWrittenSimpleRoot.getVector(1))
    var i = 0
    while (i < batchSize) {
      val name = nameVec.getUTF8String(i).toString
      val age = ageVec.getInt(i)
      result(i) = Simple(name, age)
      i += 1
    }
    result
  }

  // ========== Wide (20 fields) - Write ==========

  @Benchmark
  def sparkWriteWide(): Unit = {
    wideRoot.clear()
    val writer = ArrowWriter.create(wideRoot)
    var i = 0
    while (i < batchSize) {
      writer.write(wideRows(i))
      i += 1
    }
    writer.finish()
  }

  // ========== Wide - Read ==========

  @Benchmark
  def sparkReadWide(): Array[Wide] = {
    val result = new Array[Wide](batchSize)
    val vecs = (0 until 20).map(j => new ArrowColumnVector(preWrittenWideRoot.getVector(j))).toArray
    var i = 0
    while (i < batchSize) {
      result(i) = Wide(
        vecs(0).getInt(i), vecs(1).getInt(i), vecs(2).getInt(i), vecs(3).getInt(i), vecs(4).getInt(i),
        vecs(5).getInt(i), vecs(6).getInt(i), vecs(7).getInt(i), vecs(8).getInt(i), vecs(9).getInt(i),
        vecs(10).getUTF8String(i).toString, vecs(11).getUTF8String(i).toString,
        vecs(12).getUTF8String(i).toString, vecs(13).getUTF8String(i).toString, vecs(14).getUTF8String(i).toString,
        vecs(15).getDouble(i), vecs(16).getDouble(i), vecs(17).getDouble(i), vecs(18).getDouble(i), vecs(19).getDouble(i)
      )
      i += 1
    }
    result
  }

  // ========== Temporal - Write ==========

  @Benchmark
  def sparkWriteTemporal(): Unit = {
    temporalRoot.clear()
    val writer = ArrowWriter.create(temporalRoot)
    var i = 0
    while (i < batchSize) {
      writer.write(temporalRows(i))
      i += 1
    }
    writer.finish()
  }

  // ========== Temporal - Read ==========

  @Benchmark
  def sparkReadTemporal(): Array[Temporal] = {
    val result = new Array[Temporal](batchSize)
    val dateVec = new ArrowColumnVector(preWrittenTemporalRoot.getVector(0))
    val timeVec = new ArrowColumnVector(preWrittenTemporalRoot.getVector(1))
    var i = 0
    while (i < batchSize) {
      // Spark stores dates as days since epoch, timestamps as microseconds
      val epochDay = dateVec.getInt(i)
      val micros = timeVec.getLong(i)
      result(i) = Temporal(
        java.time.LocalDate.ofEpochDay(epochDay.toLong),
        java.time.Instant.ofEpochSecond(micros / 1000000L, (micros % 1000000L) * 1000)
      )
      i += 1
    }
    result
  }
}

// Wide case class for Spark benchmarks (Scala 2.13 syntax)
case class Wide(
    f1: Int, f2: Int, f3: Int, f4: Int, f5: Int,
    f6: Int, f7: Int, f8: Int, f9: Int, f10: Int,
    f11: String, f12: String, f13: String, f14: String, f15: String,
    f16: Double, f17: Double, f18: Double, f19: Double, f20: Double
)

// Temporal case class for Spark benchmarks
case class Temporal(date: java.time.LocalDate, time: java.time.Instant)
