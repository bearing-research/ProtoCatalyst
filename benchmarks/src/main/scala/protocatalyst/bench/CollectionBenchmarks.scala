package protocatalyst.bench

import java.util.concurrent.TimeUnit

import scala.compiletime.uninitialized

import org.openjdk.jmh.annotations._

import protocatalyst.encoder.{InlineRowSerializer, InternalTypeConverter}

/** Benchmarks for collection serialization at various sizes.
  *
  * Tests:
  *   - List[String] with 10, 100, 1000 elements
  *   - Map[String, Int] with 10, 100, 1000 entries
  *   - List[Person] with 10, 100 elements (nested structs in collections)
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 20, time = 1)
class CollectionBenchmarks:

  given InternalTypeConverter = InternalTypeConverter.default

  // Serializers
  val listSerializer: InlineRowSerializer[WithStringList] =
    InlineRowSerializer.derived[WithStringList]
  val mapSerializer: InlineRowSerializer[WithIntMap] =
    InlineRowSerializer.derived[WithIntMap]
  val personListSerializer: InlineRowSerializer[WithPersonList] =
    InlineRowSerializer.derived[WithPersonList]

  // Test data
  val smallListData: WithStringList = WithStringList(BenchmarkData.smallList)
  val mediumListData: WithStringList = WithStringList(BenchmarkData.mediumList)
  val largeListData: WithStringList = WithStringList(BenchmarkData.largeList)

  val smallMapData: WithIntMap = WithIntMap(BenchmarkData.smallMap)
  val mediumMapData: WithIntMap = WithIntMap(BenchmarkData.mediumMap)
  val largeMapData: WithIntMap = WithIntMap(BenchmarkData.largeMap)

  val smallTeamData: WithPersonList = WithPersonList(BenchmarkData.smallTeam)
  val mediumTeamData: WithPersonList = WithPersonList(BenchmarkData.mediumTeam)

  // Pre-serialized data for deserialization benchmarks
  var smallListSerialized: Array[Any] = uninitialized
  var mediumListSerialized: Array[Any] = uninitialized
  var largeListSerialized: Array[Any] = uninitialized

  var smallMapSerialized: Array[Any] = uninitialized
  var mediumMapSerialized: Array[Any] = uninitialized
  var largeMapSerialized: Array[Any] = uninitialized

  var smallTeamSerialized: Array[Any] = uninitialized
  var mediumTeamSerialized: Array[Any] = uninitialized

  @Setup
  def setup(): Unit =
    smallListSerialized = listSerializer.serialize(smallListData)
    mediumListSerialized = listSerializer.serialize(mediumListData)
    largeListSerialized = listSerializer.serialize(largeListData)

    smallMapSerialized = mapSerializer.serialize(smallMapData)
    mediumMapSerialized = mapSerializer.serialize(mediumMapData)
    largeMapSerialized = mapSerializer.serialize(largeMapData)

    smallTeamSerialized = personListSerializer.serialize(smallTeamData)
    mediumTeamSerialized = personListSerializer.serialize(mediumTeamData)

  // ========== List[String] Serialization ==========

  @Benchmark
  def serializeSmallList(): Array[Any] =
    listSerializer.serialize(smallListData)

  @Benchmark
  def serializeMediumList(): Array[Any] =
    listSerializer.serialize(mediumListData)

  @Benchmark
  def serializeLargeList(): Array[Any] =
    listSerializer.serialize(largeListData)

  // ========== List[String] Deserialization ==========

  @Benchmark
  def deserializeSmallList(): WithStringList =
    listSerializer.deserialize(smallListSerialized)

  @Benchmark
  def deserializeMediumList(): WithStringList =
    listSerializer.deserialize(mediumListSerialized)

  @Benchmark
  def deserializeLargeList(): WithStringList =
    listSerializer.deserialize(largeListSerialized)

  // ========== Map[String, Int] Serialization ==========

  @Benchmark
  def serializeSmallMap(): Array[Any] =
    mapSerializer.serialize(smallMapData)

  @Benchmark
  def serializeMediumMap(): Array[Any] =
    mapSerializer.serialize(mediumMapData)

  @Benchmark
  def serializeLargeMap(): Array[Any] =
    mapSerializer.serialize(largeMapData)

  // ========== Map[String, Int] Deserialization ==========

  @Benchmark
  def deserializeSmallMap(): WithIntMap =
    mapSerializer.deserialize(smallMapSerialized)

  @Benchmark
  def deserializeMediumMap(): WithIntMap =
    mapSerializer.deserialize(mediumMapSerialized)

  @Benchmark
  def deserializeLargeMap(): WithIntMap =
    mapSerializer.deserialize(largeMapSerialized)

  // ========== List[Person] (nested structs) Serialization ==========

  @Benchmark
  def serializeSmallTeam(): Array[Any] =
    personListSerializer.serialize(smallTeamData)

  @Benchmark
  def serializeMediumTeam(): Array[Any] =
    personListSerializer.serialize(mediumTeamData)

  // ========== List[Person] Deserialization ==========

  @Benchmark
  def deserializeSmallTeam(): WithPersonList =
    personListSerializer.deserialize(smallTeamSerialized)

  @Benchmark
  def deserializeMediumTeam(): WithPersonList =
    personListSerializer.deserialize(mediumTeamSerialized)

  // ========== Roundtrip benchmarks ==========

  @Benchmark
  def roundtripSmallList(): WithStringList =
    listSerializer.deserialize(listSerializer.serialize(smallListData))

  @Benchmark
  def roundtripLargeList(): WithStringList =
    listSerializer.deserialize(listSerializer.serialize(largeListData))

  @Benchmark
  def roundtripSmallMap(): WithIntMap =
    mapSerializer.deserialize(mapSerializer.serialize(smallMapData))

  @Benchmark
  def roundtripLargeMap(): WithIntMap =
    mapSerializer.deserialize(mapSerializer.serialize(largeMapData))

  @Benchmark
  def roundtripSmallTeam(): WithPersonList =
    personListSerializer.deserialize(personListSerializer.serialize(smallTeamData))

  @Benchmark
  def roundtripMediumTeam(): WithPersonList =
    personListSerializer.deserialize(personListSerializer.serialize(mediumTeamData))
