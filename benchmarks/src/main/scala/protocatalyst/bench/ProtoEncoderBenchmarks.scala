package protocatalyst.bench

import java.util.concurrent.TimeUnit

import scala.compiletime.uninitialized

import org.openjdk.jmh.annotations._

import protocatalyst.encoder.{InlineRowSerializer, InternalTypeConverter, ProtoEncoder}

/** Benchmarks for ProtoEncoder schema derivation and InlineRowSerializer serialization.
  *
  * Tests compile-time encoder derivation and runtime serialization performance.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(2)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 20, time = 1)
class ProtoEncoderBenchmarks:

  given InternalTypeConverter = InternalTypeConverter.default

  // Pre-derived serializers for serialization benchmarks
  val simpleSerializer: InlineRowSerializer[Simple] = InlineRowSerializer.derived[Simple]
  val personSerializer: InlineRowSerializer[Person] = InlineRowSerializer.derived[Person]
  val complexSerializer: InlineRowSerializer[Complex] = InlineRowSerializer.derived[Complex]
  val wideSerializer: InlineRowSerializer[Wide] = InlineRowSerializer.derived[Wide]

  // Test data
  val simpleData: Simple = BenchmarkData.simple
  val personData: Person = BenchmarkData.person
  val complexData: Complex = BenchmarkData.complex
  val wideData: Wide = BenchmarkData.wide

  // Pre-serialized data for deserialization benchmarks
  var simpleSerialized: Array[Any] = uninitialized
  var personSerialized: Array[Any] = uninitialized
  var complexSerialized: Array[Any] = uninitialized

  @Setup
  def setup(): Unit =
    simpleSerialized = simpleSerializer.serialize(simpleData)
    personSerialized = personSerializer.serialize(personData)
    complexSerialized = complexSerializer.serialize(complexData)

  // ========== Schema Derivation Benchmarks ==========

  @Benchmark
  def deriveSimple(): ProtoEncoder[Simple] =
    ProtoEncoder.derived[Simple]

  @Benchmark
  def derivePerson(): ProtoEncoder[Person] =
    ProtoEncoder.derived[Person]

  @Benchmark
  def deriveComplex(): ProtoEncoder[Complex] =
    ProtoEncoder.derived[Complex]

  @Benchmark
  def deriveWide(): ProtoEncoder[Wide] =
    ProtoEncoder.derived[Wide]

  // ========== Serialization Benchmarks ==========

  @Benchmark
  def serializeSimple(): Array[Any] =
    simpleSerializer.serialize(simpleData)

  @Benchmark
  def serializePerson(): Array[Any] =
    personSerializer.serialize(personData)

  @Benchmark
  def serializeComplex(): Array[Any] =
    complexSerializer.serialize(complexData)

  @Benchmark
  def serializeWide(): Array[Any] =
    wideSerializer.serialize(wideData)

  // ========== Deserialization Benchmarks ==========

  @Benchmark
  def deserializeSimple(): Simple =
    simpleSerializer.deserialize(simpleSerialized)

  @Benchmark
  def deserializePerson(): Person =
    personSerializer.deserialize(personSerialized)

  @Benchmark
  def deserializeComplex(): Complex =
    complexSerializer.deserialize(complexSerialized)

  // ========== Roundtrip Benchmarks ==========

  @Benchmark
  def roundtripSimple(): Simple =
    simpleSerializer.deserialize(simpleSerializer.serialize(simpleData))

  @Benchmark
  def roundtripPerson(): Person =
    personSerializer.deserialize(personSerializer.serialize(personData))

  @Benchmark
  def roundtripComplex(): Complex =
    complexSerializer.deserialize(complexSerializer.serialize(complexData))
