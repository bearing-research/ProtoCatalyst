package protocatalyst.bench

import java.util.concurrent.TimeUnit

import scala.compiletime.uninitialized

import org.openjdk.jmh.annotations._

import protocatalyst.encoder.TransformingEncoder

/** Benchmarks for comparing codec performance: Java vs Kryo vs Fory.
  *
  * TransformingEncoder uses pluggable codecs for binary serialization. This benchmark compares
  * throughput and latency across codec implementations.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(2)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 20, time = 1)
class CodecBenchmarks:

  // Test data
  val complexData: Complex = BenchmarkData.complex
  val personData: Person = BenchmarkData.person

  // Encoders for each codec
  val javaComplexEncoder: TransformingEncoder[Complex] = TransformingEncoder.java[Complex]
  val kryoComplexEncoder: TransformingEncoder[Complex] = TransformingEncoder.kryo[Complex]
  val foryComplexEncoder: TransformingEncoder[Complex] = TransformingEncoder.fory[Complex]

  val javaPersonEncoder: TransformingEncoder[Person] = TransformingEncoder.java[Person]
  val kryoPersonEncoder: TransformingEncoder[Person] = TransformingEncoder.kryo[Person]
  val foryPersonEncoder: TransformingEncoder[Person] = TransformingEncoder.fory[Person]

  // Pre-encoded bytes for decode benchmarks
  var javaComplexBytes: Array[Byte] = uninitialized
  var kryoComplexBytes: Array[Byte] = uninitialized
  var foryComplexBytes: Array[Byte] = uninitialized

  var javaPersonBytes: Array[Byte] = uninitialized
  var kryoPersonBytes: Array[Byte] = uninitialized
  var foryPersonBytes: Array[Byte] = uninitialized

  @Setup
  def setup(): Unit =
    javaComplexBytes = javaComplexEncoder.encode(complexData)
    kryoComplexBytes = kryoComplexEncoder.encode(complexData)
    foryComplexBytes = foryComplexEncoder.encode(complexData)

    javaPersonBytes = javaPersonEncoder.encode(personData)
    kryoPersonBytes = kryoPersonEncoder.encode(personData)
    foryPersonBytes = foryPersonEncoder.encode(personData)

  // ========== Complex Object Encode ==========

  @Benchmark
  def javaEncodeComplex(): Array[Byte] =
    javaComplexEncoder.encode(complexData)

  @Benchmark
  def kryoEncodeComplex(): Array[Byte] =
    kryoComplexEncoder.encode(complexData)

  @Benchmark
  def foryEncodeComplex(): Array[Byte] =
    foryComplexEncoder.encode(complexData)

  // ========== Complex Object Decode ==========

  @Benchmark
  def javaDecodeComplex(): Complex =
    javaComplexEncoder.decode(javaComplexBytes)

  @Benchmark
  def kryoDecodeComplex(): Complex =
    kryoComplexEncoder.decode(kryoComplexBytes)

  @Benchmark
  def foryDecodeComplex(): Complex =
    foryComplexEncoder.decode(foryComplexBytes)

  // ========== Complex Object Roundtrip ==========

  @Benchmark
  def javaRoundtripComplex(): Complex =
    javaComplexEncoder.decode(javaComplexEncoder.encode(complexData))

  @Benchmark
  def kryoRoundtripComplex(): Complex =
    kryoComplexEncoder.decode(kryoComplexEncoder.encode(complexData))

  @Benchmark
  def foryRoundtripComplex(): Complex =
    foryComplexEncoder.decode(foryComplexEncoder.encode(complexData))

  // ========== Person Object (Nested) Encode ==========

  @Benchmark
  def javaEncodePerson(): Array[Byte] =
    javaPersonEncoder.encode(personData)

  @Benchmark
  def kryoEncodePerson(): Array[Byte] =
    kryoPersonEncoder.encode(personData)

  @Benchmark
  def foryEncodePerson(): Array[Byte] =
    foryPersonEncoder.encode(personData)

  // ========== Person Object (Nested) Decode ==========

  @Benchmark
  def javaDecodePerson(): Person =
    javaPersonEncoder.decode(javaPersonBytes)

  @Benchmark
  def kryoDecodePerson(): Person =
    kryoPersonEncoder.decode(kryoPersonBytes)

  @Benchmark
  def foryDecodePerson(): Person =
    foryPersonEncoder.decode(foryPersonBytes)
