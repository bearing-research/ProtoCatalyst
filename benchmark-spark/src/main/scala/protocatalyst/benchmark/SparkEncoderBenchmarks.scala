package protocatalyst.benchmark

import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.catalyst.InternalRow

/** Benchmarks for Spark's ExpressionEncoder.
  *
  * These benchmarks measure Spark's runtime reflection-based encoder performance
  * for comparison with ProtoCatalyst's compile-time derived encoders.
  *
  * Run alongside ProtoEncoderBenchmarks to compare results.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(2)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 20, time = 1)
class SparkEncoderBenchmarks {

  // Test data
  val simpleData: Simple = BenchmarkData.simple
  val personData: Person = BenchmarkData.person
  val complexData: Complex = BenchmarkData.complex

  // Pre-created encoders for serialization benchmarks
  var simpleEncoder: ExpressionEncoder[Simple] = _
  var personEncoder: ExpressionEncoder[Person] = _
  var complexEncoder: ExpressionEncoder[Complex] = _

  // Pre-created serializers (bound to schema)
  var simpleSerializer: Simple => InternalRow = _
  var personSerializer: Person => InternalRow = _
  var complexSerializer: Complex => InternalRow = _

  // Pre-created deserializers
  var simpleDeserializer: InternalRow => Simple = _
  var personDeserializer: InternalRow => Person = _
  var complexDeserializer: InternalRow => Complex = _

  // Pre-serialized rows for deserialization benchmarks
  var simpleSerialized: InternalRow = _
  var personSerialized: InternalRow = _
  var complexSerialized: InternalRow = _

  @Setup
  def setup(): Unit = {
    // Create encoders (this includes schema inference via TypeTag)
    simpleEncoder = ExpressionEncoder[Simple]()
    personEncoder = ExpressionEncoder[Person]()
    complexEncoder = ExpressionEncoder[Complex]()

    // Create serializers
    simpleSerializer = simpleEncoder.createSerializer()
    personSerializer = personEncoder.createSerializer()
    complexSerializer = complexEncoder.createSerializer()

    // Create deserializers (requires resolveAndBind)
    simpleDeserializer = simpleEncoder.resolveAndBind().createDeserializer()
    personDeserializer = personEncoder.resolveAndBind().createDeserializer()
    complexDeserializer = complexEncoder.resolveAndBind().createDeserializer()

    // Pre-serialize for deserialization benchmarks
    simpleSerialized = simpleSerializer(simpleData)
    personSerialized = personSerializer(personData)
    complexSerialized = complexSerializer(complexData)
  }

  // ========== Schema Derivation Benchmarks ==========
  // These measure the cost of TypeTag-based runtime reflection

  @Benchmark
  def deriveSimple(): ExpressionEncoder[Simple] =
    ExpressionEncoder[Simple]()

  @Benchmark
  def derivePerson(): ExpressionEncoder[Person] =
    ExpressionEncoder[Person]()

  @Benchmark
  def deriveComplex(): ExpressionEncoder[Complex] =
    ExpressionEncoder[Complex]()

  // ========== Serialization Benchmarks ==========

  @Benchmark
  def serializeSimple(): InternalRow =
    simpleSerializer(simpleData)

  @Benchmark
  def serializePerson(): InternalRow =
    personSerializer(personData)

  @Benchmark
  def serializeComplex(): InternalRow =
    complexSerializer(complexData)

  // ========== Deserialization Benchmarks ==========

  @Benchmark
  def deserializeSimple(): Simple =
    simpleDeserializer(simpleSerialized)

  @Benchmark
  def deserializePerson(): Person =
    personDeserializer(personSerialized)

  @Benchmark
  def deserializeComplex(): Complex =
    complexDeserializer(complexSerialized)

  // ========== Roundtrip Benchmarks ==========

  @Benchmark
  def roundtripSimple(): Simple =
    simpleDeserializer(simpleSerializer(simpleData))

  @Benchmark
  def roundtripPerson(): Person =
    personDeserializer(personSerializer(personData))

  @Benchmark
  def roundtripComplex(): Complex =
    complexDeserializer(complexSerializer(complexData))
}
