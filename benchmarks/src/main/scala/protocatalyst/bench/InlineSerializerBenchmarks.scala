package protocatalyst.bench

import java.util.concurrent.TimeUnit

import scala.compiletime.uninitialized

import org.openjdk.jmh.annotations._

import protocatalyst.encoder.{InlineRowSerializer, InternalTypeConverter, RowSerializer}

/** Benchmarks comparing InlineRowSerializer vs RowSerializer.
  *
  * InlineRowSerializer uses compile-time type specialization to eliminate runtime type dispatch in
  * serialization/deserialization.
  *
  * Expected improvements:
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

  // ========== Current RowSerializer ==========
  val currentSimple: RowSerializer[Simple] = RowSerializer.derived[Simple]
  val currentPerson: RowSerializer[Person] = RowSerializer.derived[Person]
  val currentWide: RowSerializer[Wide] = RowSerializer.derived[Wide]
  val currentTeam: RowSerializer[Team] = RowSerializer.derived[Team]

  // ========== Inline specialized serializers ==========
  val inlineSimple: InlineRowSerializer[Simple] = InlineRowSerializer.derived[Simple]
  val inlinePerson: InlineRowSerializer[Person] = InlineRowSerializer.derived[Person]
  val inlineWide: InlineRowSerializer[Wide] = InlineRowSerializer.derived[Wide]
  val inlineTeam: InlineRowSerializer[Team] = InlineRowSerializer.derived[Team]

  // Test data
  val simpleData: Simple = BenchmarkData.simple
  val personData: Person = BenchmarkData.person
  val wideData: Wide = BenchmarkData.wide
  val teamData: Team = BenchmarkData.team

  // Pre-serialized data for deserialization benchmarks
  var currentSimpleSerialized: Array[Any] = uninitialized
  var currentPersonSerialized: Array[Any] = uninitialized
  var currentWideSerialized: Array[Any] = uninitialized
  var currentTeamSerialized: Array[Any] = uninitialized
  var inlineSimpleSerialized: Array[Any] = uninitialized
  var inlinePersonSerialized: Array[Any] = uninitialized
  var inlineWideSerialized: Array[Any] = uninitialized
  var inlineTeamSerialized: Array[Any] = uninitialized

  @Setup
  def setup(): Unit =
    currentSimpleSerialized = currentSimple.serialize(simpleData)
    currentPersonSerialized = currentPerson.serialize(personData)
    currentWideSerialized = currentWide.serialize(wideData)
    currentTeamSerialized = currentTeam.serialize(teamData)
    inlineSimpleSerialized = inlineSimple.serialize(simpleData)
    inlinePersonSerialized = inlinePerson.serialize(personData)
    inlineWideSerialized = inlineWide.serialize(wideData)
    inlineTeamSerialized = inlineTeam.serialize(teamData)

  // ========== Simple (2 fields: String, Int) ==========

  @Benchmark
  def currentSerializeSimple(): Array[Any] =
    currentSimple.serialize(simpleData)

  @Benchmark
  def inlineSerializeSimple(): Array[Any] =
    inlineSimple.serialize(simpleData)

  @Benchmark
  def currentDeserializeSimple(): Simple =
    currentSimple.deserialize(currentSimpleSerialized)

  @Benchmark
  def inlineDeserializeSimple(): Simple =
    inlineSimple.deserialize(inlineSimpleSerialized)

  // ========== Person (nested: String, Int, Address(String, String, String)) ==========

  @Benchmark
  def currentSerializePerson(): Array[Any] =
    currentPerson.serialize(personData)

  @Benchmark
  def inlineSerializePerson(): Array[Any] =
    inlinePerson.serialize(personData)

  @Benchmark
  def currentDeserializePerson(): Person =
    currentPerson.deserialize(currentPersonSerialized)

  @Benchmark
  def inlineDeserializePerson(): Person =
    inlinePerson.deserialize(inlinePersonSerialized)

  // ========== Wide (20 fields: 10 Int, 5 String, 5 Double) ==========

  @Benchmark
  def currentSerializeWide(): Array[Any] =
    currentWide.serialize(wideData)

  @Benchmark
  def inlineSerializeWide(): Array[Any] =
    inlineWide.serialize(wideData)

  @Benchmark
  def currentDeserializeWide(): Wide =
    currentWide.deserialize(currentWideSerialized)

  @Benchmark
  def inlineDeserializeWide(): Wide =
    inlineWide.deserialize(inlineWideSerialized)

  // ========== Roundtrip benchmarks ==========

  @Benchmark
  def currentRoundtripSimple(): Simple =
    currentSimple.deserialize(currentSimple.serialize(simpleData))

  @Benchmark
  def inlineRoundtripSimple(): Simple =
    inlineSimple.deserialize(inlineSimple.serialize(simpleData))

  @Benchmark
  def currentRoundtripPerson(): Person =
    currentPerson.deserialize(currentPerson.serialize(personData))

  @Benchmark
  def inlineRoundtripPerson(): Person =
    inlinePerson.deserialize(inlinePerson.serialize(personData))

  @Benchmark
  def currentRoundtripWide(): Wide =
    currentWide.deserialize(currentWide.serialize(wideData))

  @Benchmark
  def inlineRoundtripWide(): Wide =
    inlineWide.deserialize(inlineWide.serialize(wideData))

  // ========== Team (List[Person] - collection of custom types) ==========

  @Benchmark
  def currentSerializeTeam(): Array[Any] =
    currentTeam.serialize(teamData)

  @Benchmark
  def inlineSerializeTeam(): Array[Any] =
    inlineTeam.serialize(teamData)

  @Benchmark
  def currentDeserializeTeam(): Team =
    currentTeam.deserialize(currentTeamSerialized)

  @Benchmark
  def inlineDeserializeTeam(): Team =
    inlineTeam.deserialize(inlineTeamSerialized)

  @Benchmark
  def currentRoundtripTeam(): Team =
    currentTeam.deserialize(currentTeam.serialize(teamData))

  @Benchmark
  def inlineRoundtripTeam(): Team =
    inlineTeam.deserialize(inlineTeam.serialize(teamData))
