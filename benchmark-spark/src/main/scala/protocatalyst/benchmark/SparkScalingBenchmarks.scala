package protocatalyst.benchmark

import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.catalyst.InternalRow

/** Benchmarks to measure how Spark's ExpressionEncoder scales with batch size.
  *
  * Companion to ScalingBenchmarks in the ProtoCatalyst module.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
class SparkScalingBenchmarks {

  // Serializers
  var personSerializer: Person => InternalRow = _
  var personDeserializer: InternalRow => Person = _
  var teamSerializer: Team => InternalRow = _

  // Batch data of different sizes
  var persons10: Array[Person] = _
  var persons100: Array[Person] = _
  var persons1000: Array[Person] = _
  var persons10000: Array[Person] = _

  var teams10: Array[Team] = _
  var teams100: Array[Team] = _
  var teams1000: Array[Team] = _

  // Pre-serialized for deserialization benchmarks
  var personsSerialized10: Array[InternalRow] = _
  var personsSerialized100: Array[InternalRow] = _
  var personsSerialized1000: Array[InternalRow] = _
  var personsSerialized10000: Array[InternalRow] = _

  @Setup
  def setup(): Unit = {
    // Create encoders
    val personEncoder = ExpressionEncoder[Person]()
    val teamEncoder = ExpressionEncoder[Team]()

    personSerializer = personEncoder.createSerializer()
    personDeserializer = personEncoder.resolveAndBind().createDeserializer()
    teamSerializer = teamEncoder.createSerializer()

    val address = Address("123 Main St", "NYC", "10001")

    // Create Person batches
    persons10 = (1 to 10).map(i => Person(s"Person$i", 20 + i % 50, address)).toArray
    persons100 = (1 to 100).map(i => Person(s"Person$i", 20 + i % 50, address)).toArray
    persons1000 = (1 to 1000).map(i => Person(s"Person$i", 20 + i % 50, address)).toArray
    persons10000 = (1 to 10000).map(i => Person(s"Person$i", 20 + i % 50, address)).toArray

    // Create Team batches (each team has 3 members)
    teams10 = (1 to 10).map(i => Team(s"Team$i", List(
      Person(s"Member${i}a", 25, address),
      Person(s"Member${i}b", 30, address),
      Person(s"Member${i}c", 35, address)
    ))).toArray
    teams100 = (1 to 100).map(i => Team(s"Team$i", List(
      Person(s"Member${i}a", 25, address),
      Person(s"Member${i}b", 30, address),
      Person(s"Member${i}c", 35, address)
    ))).toArray
    teams1000 = (1 to 1000).map(i => Team(s"Team$i", List(
      Person(s"Member${i}a", 25, address),
      Person(s"Member${i}b", 30, address),
      Person(s"Member${i}c", 35, address)
    ))).toArray

    // Pre-serialize for deserialization benchmarks
    personsSerialized10 = persons10.map(personSerializer)
    personsSerialized100 = persons100.map(personSerializer)
    personsSerialized1000 = persons1000.map(personSerializer)
    personsSerialized10000 = persons10000.map(personSerializer)
  }

  // ========== Person Batch Serialization ==========

  @Benchmark
  def serializePersonBatch10(): Int = {
    var i = 0
    while (i < persons10.length) {
      personSerializer(persons10(i))
      i += 1
    }
    i
  }

  @Benchmark
  def serializePersonBatch100(): Int = {
    var i = 0
    while (i < persons100.length) {
      personSerializer(persons100(i))
      i += 1
    }
    i
  }

  @Benchmark
  def serializePersonBatch1000(): Int = {
    var i = 0
    while (i < persons1000.length) {
      personSerializer(persons1000(i))
      i += 1
    }
    i
  }

  @Benchmark
  def serializePersonBatch10000(): Int = {
    var i = 0
    while (i < persons10000.length) {
      personSerializer(persons10000(i))
      i += 1
    }
    i
  }

  // ========== Person Batch Deserialization ==========

  @Benchmark
  def deserializePersonBatch10(): Int = {
    var i = 0
    while (i < personsSerialized10.length) {
      personDeserializer(personsSerialized10(i))
      i += 1
    }
    i
  }

  @Benchmark
  def deserializePersonBatch100(): Int = {
    var i = 0
    while (i < personsSerialized100.length) {
      personDeserializer(personsSerialized100(i))
      i += 1
    }
    i
  }

  @Benchmark
  def deserializePersonBatch1000(): Int = {
    var i = 0
    while (i < personsSerialized1000.length) {
      personDeserializer(personsSerialized1000(i))
      i += 1
    }
    i
  }

  @Benchmark
  def deserializePersonBatch10000(): Int = {
    var i = 0
    while (i < personsSerialized10000.length) {
      personDeserializer(personsSerialized10000(i))
      i += 1
    }
    i
  }

  // ========== Team (List[Person]) Batch Serialization ==========

  @Benchmark
  def serializeTeamBatch10(): Int = {
    var i = 0
    while (i < teams10.length) {
      teamSerializer(teams10(i))
      i += 1
    }
    i
  }

  @Benchmark
  def serializeTeamBatch100(): Int = {
    var i = 0
    while (i < teams100.length) {
      teamSerializer(teams100(i))
      i += 1
    }
    i
  }

  @Benchmark
  def serializeTeamBatch1000(): Int = {
    var i = 0
    while (i < teams1000.length) {
      teamSerializer(teams1000(i))
      i += 1
    }
    i
  }
}
