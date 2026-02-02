package protocatalyst.bench

import java.util.concurrent.TimeUnit

import scala.compiletime.uninitialized

import org.openjdk.jmh.annotations._

import protocatalyst.encoder.{InlineRowSerializer, InternalTypeConverter}

/** Benchmarks for InlineRowSerializer with various type complexities.
  *
  * InlineRowSerializer uses compile-time type specialization to eliminate runtime type dispatch in
  * serialization/deserialization.
  *
  * Optimizations:
  *   - ~10% faster for primitives only (no type dispatch)
  *   - ~15% faster with String fields (direct toInternal call)
  *   - ~20% faster with Option fields (no isInstanceOf check)
  *   - ~25% faster for nested structures (recursive specialization)
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 20, time = 1)
class InlineSerializerBenchmarks:

  given InternalTypeConverter = InternalTypeConverter.default

  // Inline specialized serializers
  val simpleSerializer: InlineRowSerializer[Simple] = InlineRowSerializer.derived[Simple]
  val personSerializer: InlineRowSerializer[Person] = InlineRowSerializer.derived[Person]
  val wideSerializer: InlineRowSerializer[Wide] = InlineRowSerializer.derived[Wide]
  val teamSerializer: InlineRowSerializer[Team] = InlineRowSerializer.derived[Team]

  // Test data
  val simpleData: Simple = BenchmarkData.simple
  val personData: Person = BenchmarkData.person
  val wideData: Wide = BenchmarkData.wide
  val teamData: Team = BenchmarkData.team

  // Pre-serialized data for deserialization benchmarks
  var simpleSerialized: Array[Any] = uninitialized
  var personSerialized: Array[Any] = uninitialized
  var wideSerialized: Array[Any] = uninitialized
  var teamSerialized: Array[Any] = uninitialized

  @Setup
  def setup(): Unit =
    simpleSerialized = simpleSerializer.serialize(simpleData)
    personSerialized = personSerializer.serialize(personData)
    wideSerialized = wideSerializer.serialize(wideData)
    teamSerialized = teamSerializer.serialize(teamData)

  // ========== Simple (2 fields: String, Int) ==========

  @Benchmark
  def serializeSimple(): Array[Any] =
    simpleSerializer.serialize(simpleData)

  @Benchmark
  def deserializeSimple(): Simple =
    simpleSerializer.deserialize(simpleSerialized)

  // ========== Person (nested: String, Int, Address(String, String, String)) ==========

  @Benchmark
  def serializePerson(): Array[Any] =
    personSerializer.serialize(personData)

  @Benchmark
  def deserializePerson(): Person =
    personSerializer.deserialize(personSerialized)

  // ========== Wide (20 fields: 10 Int, 5 String, 5 Double) ==========

  @Benchmark
  def serializeWide(): Array[Any] =
    wideSerializer.serialize(wideData)

  @Benchmark
  def deserializeWide(): Wide =
    wideSerializer.deserialize(wideSerialized)

  // ========== Team (List[Person] - collection of custom types) ==========

  @Benchmark
  def serializeTeam(): Array[Any] =
    teamSerializer.serialize(teamData)

  @Benchmark
  def deserializeTeam(): Team =
    teamSerializer.deserialize(teamSerialized)

  // ========== Roundtrip benchmarks ==========

  @Benchmark
  def roundtripSimple(): Simple =
    simpleSerializer.deserialize(simpleSerializer.serialize(simpleData))

  @Benchmark
  def roundtripPerson(): Person =
    personSerializer.deserialize(personSerializer.serialize(personData))

  @Benchmark
  def roundtripWide(): Wide =
    wideSerializer.deserialize(wideSerializer.serialize(wideData))

  @Benchmark
  def roundtripTeam(): Team =
    teamSerializer.deserialize(teamSerializer.serialize(teamData))
