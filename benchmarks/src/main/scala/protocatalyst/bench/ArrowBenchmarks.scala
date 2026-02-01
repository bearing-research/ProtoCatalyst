package protocatalyst.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized
import protocatalyst.arrow.*
import org.apache.arrow.vector.*
import org.apache.arrow.memory.RootAllocator
import java.nio.charset.StandardCharsets

/** Benchmarks for compile-time Arrow serialization.
  *
  * Compares InlineArrowWriter/Reader (compile-time specialization) with a runtime type dispatch
  * baseline (simulating Spark's approach).
  *
  * Expected improvements:
  *   - Write: 2-5x faster (eliminates runtime type matching)
  *   - Read: 2-5x faster (eliminates runtime type matching)
  *   - Roundtrip: 2-5x faster overall
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(2)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 20, time = 1)
class ArrowBenchmarks:

  // ========== Arrow Writers/Readers ==========
  val simpleWriter: InlineArrowWriter[Simple] = InlineArrowWriter.derived[Simple]
  val simpleReader: InlineArrowReader[Simple] = InlineArrowReader.derived[Simple]

  val wideWriter: InlineArrowWriter[Wide] = InlineArrowWriter.derived[Wide]
  val wideReader: InlineArrowReader[Wide] = InlineArrowReader.derived[Wide]

  val temporalWriter: InlineArrowWriter[Temporal] = InlineArrowWriter.derived[Temporal]
  val temporalReader: InlineArrowReader[Temporal] = InlineArrowReader.derived[Temporal]

  // Optimized writers with vector caching
  val optimizedSimpleWriter: OptimizedArrowWriter[Simple] = OptimizedArrowWriter.derived[Simple]
  val optimizedWideWriter: OptimizedArrowWriter[Wide] = OptimizedArrowWriter.derived[Wide]
  val optimizedTemporalWriter: OptimizedArrowWriter[Temporal] =
    OptimizedArrowWriter.derived[Temporal]

  // IndexedSeq versions for optimized writer
  var simpleDataIndexed: IndexedSeq[Simple] = uninitialized
  var wideDataIndexed: IndexedSeq[Wide] = uninitialized
  var temporalDataIndexed: IndexedSeq[Temporal] = uninitialized

  // ========== Allocator and Roots ==========
  var allocator: RootAllocator = uninitialized
  var simpleRoot: VectorSchemaRoot = uninitialized
  var wideRoot: VectorSchemaRoot = uninitialized
  var temporalRoot: VectorSchemaRoot = uninitialized

  // Pre-written roots for read benchmarks
  var preWrittenSimpleRoot: VectorSchemaRoot = uninitialized
  var preWrittenWideRoot: VectorSchemaRoot = uninitialized
  var preWrittenTemporalRoot: VectorSchemaRoot = uninitialized

  // ========== Test Data ==========
  var simpleData: Seq[Simple] = uninitialized
  var wideData: Seq[Wide] = uninitialized
  var temporalData: Seq[Temporal] = uninitialized

  // Batch sizes
  val batchSize = 10000

  @Setup
  def setup(): Unit =
    allocator = ArrowAllocator.createRoot()

    // Create test data
    simpleData = (1 to batchSize).map(i => Simple(s"name$i", i)).toSeq
    wideData = (1 to batchSize).map { i =>
      Wide(
        i,
        i + 1,
        i + 2,
        i + 3,
        i + 4,
        i + 5,
        i + 6,
        i + 7,
        i + 8,
        i + 9,
        s"s$i",
        s"s${i + 1}",
        s"s${i + 2}",
        s"s${i + 3}",
        s"s${i + 4}",
        i * 1.1,
        i * 1.2,
        i * 1.3,
        i * 1.4,
        i * 1.5
      )
    }.toSeq
    temporalData = (1 to batchSize).map { i =>
      Temporal(
        java.time.LocalDate.ofEpochDay(i.toLong),
        java.time.Instant.ofEpochSecond(i.toLong * 86400)
      )
    }.toSeq

    // IndexedSeq versions for optimized writers
    simpleDataIndexed = simpleData.toIndexedSeq
    wideDataIndexed = wideData.toIndexedSeq
    temporalDataIndexed = temporalData.toIndexedSeq

    // Create roots for write benchmarks
    simpleRoot = VectorSchemaRoot.create(simpleWriter.schema, allocator)
    wideRoot = VectorSchemaRoot.create(wideWriter.schema, allocator)
    temporalRoot = VectorSchemaRoot.create(temporalWriter.schema, allocator)

    // Create and pre-write roots for read benchmarks
    preWrittenSimpleRoot = VectorSchemaRoot.create(simpleWriter.schema, allocator)
    simpleWriter.write(simpleData, preWrittenSimpleRoot)

    preWrittenWideRoot = VectorSchemaRoot.create(wideWriter.schema, allocator)
    wideWriter.write(wideData, preWrittenWideRoot)

    preWrittenTemporalRoot = VectorSchemaRoot.create(temporalWriter.schema, allocator)
    temporalWriter.write(temporalData, preWrittenTemporalRoot)

  @TearDown
  def teardown(): Unit =
    simpleRoot.close()
    wideRoot.close()
    temporalRoot.close()
    preWrittenSimpleRoot.close()
    preWrittenWideRoot.close()
    preWrittenTemporalRoot.close()
    allocator.close()

  // ========== Simple (2 fields: String, Int) - Write ==========

  @Benchmark
  def inlineWriteSimple(): Unit =
    simpleRoot.clear()
    simpleWriter.write(simpleData, simpleRoot)

  @Benchmark
  def optimizedWriteSimple(): Unit =
    simpleRoot.clear()
    optimizedSimpleWriter.writeIndexed(simpleDataIndexed, simpleRoot)

  @Benchmark
  def runtimeWriteSimple(): Unit =
    simpleRoot.clear()
    RuntimeArrowWriter.writeSimple(simpleData, simpleRoot)

  // ========== Simple - Read ==========

  @Benchmark
  def inlineReadSimple(): Seq[Simple] =
    simpleReader.read(preWrittenSimpleRoot)

  @Benchmark
  def runtimeReadSimple(): Seq[Simple] =
    RuntimeArrowReader.readSimple(preWrittenSimpleRoot)

  // ========== Simple - Roundtrip ==========

  @Benchmark
  def inlineRoundtripSimple(): Seq[Simple] =
    simpleRoot.clear()
    simpleWriter.write(simpleData, simpleRoot)
    simpleReader.read(simpleRoot)

  // ========== Wide (20 fields) - Write ==========

  @Benchmark
  def inlineWriteWide(): Unit =
    wideRoot.clear()
    wideWriter.write(wideData, wideRoot)

  @Benchmark
  def optimizedWriteWide(): Unit =
    wideRoot.clear()
    optimizedWideWriter.writeIndexed(wideDataIndexed, wideRoot)

  @Benchmark
  def runtimeWriteWide(): Unit =
    wideRoot.clear()
    RuntimeArrowWriter.writeWide(wideData, wideRoot)

  // ========== Wide - Read ==========

  @Benchmark
  def inlineReadWide(): Seq[Wide] =
    wideReader.read(preWrittenWideRoot)

  @Benchmark
  def runtimeReadWide(): Seq[Wide] =
    RuntimeArrowReader.readWide(preWrittenWideRoot)

  // ========== Temporal - Write ==========

  @Benchmark
  def inlineWriteTemporal(): Unit =
    temporalRoot.clear()
    temporalWriter.write(temporalData, temporalRoot)

  @Benchmark
  def optimizedWriteTemporal(): Unit =
    temporalRoot.clear()
    optimizedTemporalWriter.writeIndexed(temporalDataIndexed, temporalRoot)

  @Benchmark
  def runtimeWriteTemporal(): Unit =
    temporalRoot.clear()
    RuntimeArrowWriter.writeTemporal(temporalData, temporalRoot)

  // ========== Temporal - Read ==========

  @Benchmark
  def inlineReadTemporal(): Seq[Temporal] =
    temporalReader.read(preWrittenTemporalRoot)

  @Benchmark
  def runtimeReadTemporal(): Seq[Temporal] =
    RuntimeArrowReader.readTemporal(preWrittenTemporalRoot)

/** Runtime type dispatch Arrow writer - simulates Spark's approach.
  *
  * This baseline uses runtime type matching for each field, similar to how Spark's ArrowWriter
  * dispatches based on DataType.
  */
object RuntimeArrowWriter:

  def writeSimple(data: Seq[Simple], root: VectorSchemaRoot): Unit =
    root.allocateNew()
    val nameVec = root.getVector(0).asInstanceOf[VarCharVector]
    val ageVec = root.getVector(1).asInstanceOf[IntVector]
    var i = 0
    for d <- data do
      // Simulate runtime type dispatch
      writeField(nameVec, i, d.name, "string")
      writeField(ageVec, i, d.age, "int")
      i += 1
    root.setRowCount(data.size)

  def writeWide(data: Seq[Wide], root: VectorSchemaRoot): Unit =
    root.allocateNew()
    var i = 0
    for d <- data do
      // 10 Int fields
      writeField(root.getVector(0), i, d.f1, "int")
      writeField(root.getVector(1), i, d.f2, "int")
      writeField(root.getVector(2), i, d.f3, "int")
      writeField(root.getVector(3), i, d.f4, "int")
      writeField(root.getVector(4), i, d.f5, "int")
      writeField(root.getVector(5), i, d.f6, "int")
      writeField(root.getVector(6), i, d.f7, "int")
      writeField(root.getVector(7), i, d.f8, "int")
      writeField(root.getVector(8), i, d.f9, "int")
      writeField(root.getVector(9), i, d.f10, "int")
      // 5 String fields
      writeField(root.getVector(10), i, d.f11, "string")
      writeField(root.getVector(11), i, d.f12, "string")
      writeField(root.getVector(12), i, d.f13, "string")
      writeField(root.getVector(13), i, d.f14, "string")
      writeField(root.getVector(14), i, d.f15, "string")
      // 5 Double fields
      writeField(root.getVector(15), i, d.f16, "double")
      writeField(root.getVector(16), i, d.f17, "double")
      writeField(root.getVector(17), i, d.f18, "double")
      writeField(root.getVector(18), i, d.f19, "double")
      writeField(root.getVector(19), i, d.f20, "double")
      i += 1
    root.setRowCount(data.size)

  def writeTemporal(data: Seq[Temporal], root: VectorSchemaRoot): Unit =
    root.allocateNew()
    var i = 0
    for d <- data do
      writeField(root.getVector(0), i, d.date, "date")
      writeField(root.getVector(1), i, d.time, "timestamp")
      i += 1
    root.setRowCount(data.size)

  /** Runtime type dispatch - simulates Spark's ArrowFieldWriter pattern */
  private def writeField(vec: ValueVector, idx: Int, value: Any, typeHint: String): Unit =
    typeHint match
      case "int" =>
        vec.asInstanceOf[IntVector].setSafe(idx, value.asInstanceOf[Int])
      case "long" =>
        vec.asInstanceOf[BigIntVector].setSafe(idx, value.asInstanceOf[Long])
      case "double" =>
        vec.asInstanceOf[Float8Vector].setSafe(idx, value.asInstanceOf[Double])
      case "float" =>
        vec.asInstanceOf[Float4Vector].setSafe(idx, value.asInstanceOf[Float])
      case "boolean" =>
        vec.asInstanceOf[BitVector].setSafe(idx, if value.asInstanceOf[Boolean] then 1 else 0)
      case "string" =>
        val bytes = value.asInstanceOf[String].getBytes(StandardCharsets.UTF_8)
        vec.asInstanceOf[VarCharVector].setSafe(idx, bytes)
      case "date" =>
        val date = value.asInstanceOf[java.time.LocalDate]
        vec.asInstanceOf[DateDayVector].setSafe(idx, date.toEpochDay.toInt)
      case "timestamp" =>
        val instant = value.asInstanceOf[java.time.Instant]
        val micros = instant.getEpochSecond * 1000000L + instant.getNano / 1000
        vec.asInstanceOf[TimeStampMicroTZVector].setSafe(idx, micros)
      case _ => ()

/** Runtime type dispatch Arrow reader - simulates Spark's approach */
object RuntimeArrowReader:

  def readSimple(root: VectorSchemaRoot): Seq[Simple] =
    val rowCount = root.getRowCount
    val nameVec = root.getVector(0).asInstanceOf[VarCharVector]
    val ageVec = root.getVector(1).asInstanceOf[IntVector]
    val result = new Array[Simple](rowCount)
    var i = 0
    while i < rowCount do
      val name = readField(nameVec, i, "string").asInstanceOf[String]
      val age = readField(ageVec, i, "int").asInstanceOf[Int]
      result(i) = Simple(name, age)
      i += 1
    result.toSeq

  def readWide(root: VectorSchemaRoot): Seq[Wide] =
    val rowCount = root.getRowCount
    val result = new Array[Wide](rowCount)
    var i = 0
    while i < rowCount do
      result(i) = Wide(
        readField(root.getVector(0), i, "int").asInstanceOf[Int],
        readField(root.getVector(1), i, "int").asInstanceOf[Int],
        readField(root.getVector(2), i, "int").asInstanceOf[Int],
        readField(root.getVector(3), i, "int").asInstanceOf[Int],
        readField(root.getVector(4), i, "int").asInstanceOf[Int],
        readField(root.getVector(5), i, "int").asInstanceOf[Int],
        readField(root.getVector(6), i, "int").asInstanceOf[Int],
        readField(root.getVector(7), i, "int").asInstanceOf[Int],
        readField(root.getVector(8), i, "int").asInstanceOf[Int],
        readField(root.getVector(9), i, "int").asInstanceOf[Int],
        readField(root.getVector(10), i, "string").asInstanceOf[String],
        readField(root.getVector(11), i, "string").asInstanceOf[String],
        readField(root.getVector(12), i, "string").asInstanceOf[String],
        readField(root.getVector(13), i, "string").asInstanceOf[String],
        readField(root.getVector(14), i, "string").asInstanceOf[String],
        readField(root.getVector(15), i, "double").asInstanceOf[Double],
        readField(root.getVector(16), i, "double").asInstanceOf[Double],
        readField(root.getVector(17), i, "double").asInstanceOf[Double],
        readField(root.getVector(18), i, "double").asInstanceOf[Double],
        readField(root.getVector(19), i, "double").asInstanceOf[Double]
      )
      i += 1
    result.toSeq

  def readTemporal(root: VectorSchemaRoot): Seq[Temporal] =
    val rowCount = root.getRowCount
    val result = new Array[Temporal](rowCount)
    var i = 0
    while i < rowCount do
      val date = readField(root.getVector(0), i, "date").asInstanceOf[java.time.LocalDate]
      val time = readField(root.getVector(1), i, "timestamp").asInstanceOf[java.time.Instant]
      result(i) = Temporal(date, time)
      i += 1
    result.toSeq

  /** Runtime type dispatch - simulates Spark's ArrowColumnVector pattern */
  private def readField(vec: ValueVector, idx: Int, typeHint: String): Any =
    typeHint match
      case "int" =>
        vec.asInstanceOf[IntVector].get(idx)
      case "long" =>
        vec.asInstanceOf[BigIntVector].get(idx)
      case "double" =>
        vec.asInstanceOf[Float8Vector].get(idx)
      case "float" =>
        vec.asInstanceOf[Float4Vector].get(idx)
      case "boolean" =>
        vec.asInstanceOf[BitVector].get(idx) == 1
      case "string" =>
        new String(vec.asInstanceOf[VarCharVector].get(idx), StandardCharsets.UTF_8)
      case "date" =>
        val epochDay = vec.asInstanceOf[DateDayVector].get(idx)
        java.time.LocalDate.ofEpochDay(epochDay.toLong)
      case "timestamp" =>
        val micros = vec.asInstanceOf[TimeStampMicroTZVector].get(idx)
        java.time.Instant.ofEpochSecond(micros / 1000000L, (micros % 1000000L) * 1000)
      case _ => null
