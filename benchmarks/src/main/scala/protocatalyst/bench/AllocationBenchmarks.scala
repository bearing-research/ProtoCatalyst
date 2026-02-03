package protocatalyst.bench

import java.util.concurrent.TimeUnit

import scala.compiletime.uninitialized

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import protocatalyst.encoder.{InlineRowSerializer, InternalTypeConverter}

/** Memory allocation benchmarks for InlineRowSerializer.
  *
  * Run with GC profiler to measure allocations: sbt "benchmarks/Jmh/run -prof gc AllocationBenchmarks"
  *
  * Key metrics:
  *   - gc.alloc.rate: Allocation rate in MB/sec
  *   - gc.alloc.rate.norm: Bytes allocated per operation (normalized)
  *   - gc.count: Number of GC events
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 2, jvmArgs = Array("-Xms2G", "-Xmx2G"))
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
class AllocationBenchmarks:

  given InternalTypeConverter = InternalTypeConverter.default

  // Serializers (created once, not counted in allocations)
  val simpleSerializer: InlineRowSerializer[Simple] = InlineRowSerializer.derived[Simple]
  val personSerializer: InlineRowSerializer[Person] = InlineRowSerializer.derived[Person]
  val listSerializer: InlineRowSerializer[WithStringList] =
    InlineRowSerializer.derived[WithStringList]
  val mapSerializer: InlineRowSerializer[WithIntMap] = InlineRowSerializer.derived[WithIntMap]
  val complexSerializer: InlineRowSerializer[Complex] = InlineRowSerializer.derived[Complex]

  // Test data
  val simpleData: Simple = BenchmarkData.simple
  val personData: Person = BenchmarkData.person
  val listData: WithStringList = WithStringList(BenchmarkData.mediumList)
  val mapData: WithIntMap = WithIntMap(BenchmarkData.mediumMap)
  val complexData: Complex = BenchmarkData.complex

  // Pre-serialized for deserialization benchmarks
  var simpleSerialized: Array[Any] = uninitialized
  var personSerialized: Array[Any] = uninitialized
  var listSerialized: Array[Any] = uninitialized
  var mapSerialized: Array[Any] = uninitialized
  var complexSerialized: Array[Any] = uninitialized

  @Setup
  def setup(): Unit =
    simpleSerialized = simpleSerializer.serialize(simpleData)
    personSerialized = personSerializer.serialize(personData)
    listSerialized = listSerializer.serialize(listData)
    mapSerialized = mapSerializer.serialize(mapData)
    complexSerialized = complexSerializer.serialize(complexData)

  // ========== Serialization Allocation Tests ==========

  @Benchmark
  def allocSerializeSimple(bh: Blackhole): Unit =
    bh.consume(simpleSerializer.serialize(simpleData))

  @Benchmark
  def allocSerializePerson(bh: Blackhole): Unit =
    bh.consume(personSerializer.serialize(personData))

  @Benchmark
  def allocSerializeList(bh: Blackhole): Unit =
    bh.consume(listSerializer.serialize(listData))

  @Benchmark
  def allocSerializeMap(bh: Blackhole): Unit =
    bh.consume(mapSerializer.serialize(mapData))

  @Benchmark
  def allocSerializeComplex(bh: Blackhole): Unit =
    bh.consume(complexSerializer.serialize(complexData))

  // ========== Deserialization Allocation Tests ==========

  @Benchmark
  def allocDeserializeSimple(bh: Blackhole): Unit =
    bh.consume(simpleSerializer.deserialize(simpleSerialized))

  @Benchmark
  def allocDeserializePerson(bh: Blackhole): Unit =
    bh.consume(personSerializer.deserialize(personSerialized))

  @Benchmark
  def allocDeserializeList(bh: Blackhole): Unit =
    bh.consume(listSerializer.deserialize(listSerialized))

  @Benchmark
  def allocDeserializeMap(bh: Blackhole): Unit =
    bh.consume(mapSerializer.deserialize(mapSerialized))

  @Benchmark
  def allocDeserializeComplex(bh: Blackhole): Unit =
    bh.consume(complexSerializer.deserialize(complexSerialized))

  // ========== Roundtrip Allocation Tests ==========

  @Benchmark
  def allocRoundtripSimple(bh: Blackhole): Unit =
    bh.consume(simpleSerializer.deserialize(simpleSerializer.serialize(simpleData)))

  @Benchmark
  def allocRoundtripPerson(bh: Blackhole): Unit =
    bh.consume(personSerializer.deserialize(personSerializer.serialize(personData)))

  @Benchmark
  def allocRoundtripComplex(bh: Blackhole): Unit =
    bh.consume(complexSerializer.deserialize(complexSerializer.serialize(complexData)))
