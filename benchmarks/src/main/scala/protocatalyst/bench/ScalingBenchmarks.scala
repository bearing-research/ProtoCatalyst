package protocatalyst.bench

import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized
import protocatalyst.encoder.{InlineRowSerializer, InternalTypeConverter}

/** Benchmarks to measure how performance scales with batch size.
  *
  * Tests whether compile-time specialization maintains its advantage at different data sizes (10,
  * 100, 1000, 10000 elements).
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
class ScalingBenchmarks:

  given InternalTypeConverter = InternalTypeConverter.default

  // Serializers
  val personSerializer: InlineRowSerializer[Person] = InlineRowSerializer.derived[Person]
  val teamSerializer: InlineRowSerializer[Team] = InlineRowSerializer.derived[Team]

  // Batch data of different sizes
  var persons10: Array[Person] = uninitialized
  var persons100: Array[Person] = uninitialized
  var persons1000: Array[Person] = uninitialized
  var persons10000: Array[Person] = uninitialized

  var teams10: Array[Team] = uninitialized
  var teams100: Array[Team] = uninitialized
  var teams1000: Array[Team] = uninitialized

  // Pre-serialized for deserialization benchmarks
  var personsSerialized10: Array[Array[Any]] = uninitialized
  var personsSerialized100: Array[Array[Any]] = uninitialized
  var personsSerialized1000: Array[Array[Any]] = uninitialized
  var personsSerialized10000: Array[Array[Any]] = uninitialized

  @Setup
  def setup(): Unit =
    val address = Address("123 Main St", "NYC", "10001")

    // Create Person batches
    persons10 = (1 to 10).map(i => Person(s"Person$i", 20 + i % 50, address)).toArray
    persons100 = (1 to 100).map(i => Person(s"Person$i", 20 + i % 50, address)).toArray
    persons1000 = (1 to 1000).map(i => Person(s"Person$i", 20 + i % 50, address)).toArray
    persons10000 = (1 to 10000).map(i => Person(s"Person$i", 20 + i % 50, address)).toArray

    // Create Team batches (each team has 3 members)
    teams10 = (1 to 10)
      .map(i =>
        Team(
          s"Team$i",
          List(
            Person(s"Member${i}a", 25, address),
            Person(s"Member${i}b", 30, address),
            Person(s"Member${i}c", 35, address)
          )
        )
      )
      .toArray
    teams100 = (1 to 100)
      .map(i =>
        Team(
          s"Team$i",
          List(
            Person(s"Member${i}a", 25, address),
            Person(s"Member${i}b", 30, address),
            Person(s"Member${i}c", 35, address)
          )
        )
      )
      .toArray
    teams1000 = (1 to 1000)
      .map(i =>
        Team(
          s"Team$i",
          List(
            Person(s"Member${i}a", 25, address),
            Person(s"Member${i}b", 30, address),
            Person(s"Member${i}c", 35, address)
          )
        )
      )
      .toArray

    // Pre-serialize for deserialization benchmarks
    personsSerialized10 = persons10.map(personSerializer.serialize)
    personsSerialized100 = persons100.map(personSerializer.serialize)
    personsSerialized1000 = persons1000.map(personSerializer.serialize)
    personsSerialized10000 = persons10000.map(personSerializer.serialize)

  // ========== Person Batch Serialization ==========

  @Benchmark
  def serializePersonBatch10(): Int =
    var i = 0
    while i < persons10.length do
      personSerializer.serialize(persons10(i))
      i += 1
    i

  @Benchmark
  def serializePersonBatch100(): Int =
    var i = 0
    while i < persons100.length do
      personSerializer.serialize(persons100(i))
      i += 1
    i

  @Benchmark
  def serializePersonBatch1000(): Int =
    var i = 0
    while i < persons1000.length do
      personSerializer.serialize(persons1000(i))
      i += 1
    i

  @Benchmark
  def serializePersonBatch10000(): Int =
    var i = 0
    while i < persons10000.length do
      personSerializer.serialize(persons10000(i))
      i += 1
    i

  // ========== Person Batch Deserialization ==========

  @Benchmark
  def deserializePersonBatch10(): Int =
    var i = 0
    while i < personsSerialized10.length do
      personSerializer.deserialize(personsSerialized10(i))
      i += 1
    i

  @Benchmark
  def deserializePersonBatch100(): Int =
    var i = 0
    while i < personsSerialized100.length do
      personSerializer.deserialize(personsSerialized100(i))
      i += 1
    i

  @Benchmark
  def deserializePersonBatch1000(): Int =
    var i = 0
    while i < personsSerialized1000.length do
      personSerializer.deserialize(personsSerialized1000(i))
      i += 1
    i

  @Benchmark
  def deserializePersonBatch10000(): Int =
    var i = 0
    while i < personsSerialized10000.length do
      personSerializer.deserialize(personsSerialized10000(i))
      i += 1
    i

  // ========== Team (List[Person]) Batch Serialization ==========

  @Benchmark
  def serializeTeamBatch10(): Int =
    var i = 0
    while i < teams10.length do
      teamSerializer.serialize(teams10(i))
      i += 1
    i

  @Benchmark
  def serializeTeamBatch100(): Int =
    var i = 0
    while i < teams100.length do
      teamSerializer.serialize(teams100(i))
      i += 1
    i

  @Benchmark
  def serializeTeamBatch1000(): Int =
    var i = 0
    while i < teams1000.length do
      teamSerializer.serialize(teams1000(i))
      i += 1
    i
