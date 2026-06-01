package protocatalyst.bench

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.*

import protocatalyst.encoder.ProtoEncoder
import protocatalyst.encoder.spark.AgnosticEncoderBridge
import protocatalyst.encoder.spark.tpch.Schemas.*

/** Encoder *derivation* throughput for the compile-time path: `ProtoEncoder.derived[T]` (Scala 3
  * `Mirror`/`inline`, no `TypeTag`, no `scala.reflect.runtime`) lowered to Spark's `AgnosticEncoder`
  * via the bridge. There is no runtime `<:<` and no global lock, so it scales with cores — the
  * lock-free counterpart to `SparkEncoderDerivationBenchmarks` (which serializes on
  * `ScalaSubtypeLock`).
  *
  * NOTE: this measures *building the encoder description*, not executing it. Executing a
  * Spark-derived serializer from a Scala 3 process is blocked by stock Spark's residual
  * `scala.reflect.runtime` (see docs/REFLECTION_REPLACEMENT.md §2.1) — but derivation itself is
  * reflection-free and runs cleanly here.
  *
  * {{{
  * sbt 'benchmarks/Jmh/run -f 3 -wi 5 -i 10 -t 1  EncoderDerivationBenchmarks'
  * sbt 'benchmarks/Jmh/run -f 3 -wi 5 -i 10 -t 8  EncoderDerivationBenchmarks'
  * }}}
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
class EncoderDerivationBenchmarks:

  @Benchmark
  def deriveLineitem: AnyRef =
    AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[Lineitem])

  @Benchmark
  def deriveOrders: AnyRef =
    AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[Orders])

  /** Lock-free counterpart to `SparkEncoderDerivationBenchmarks.deriveMixed`: derive all 8 *distinct*
    * TPC-H encoders in one op. Compile-time `Mirror`/`inline` derivation has no runtime `<:<` and no
    * global monitor, so concurrent threads deriving different types never serialize — throughput
    * scales with cores instead of collapsing.
    */
  @Benchmark
  def deriveMixed: Array[AnyRef] = Array[AnyRef](
    AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[Region]),
    AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[Nation]),
    AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[Part]),
    AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[Supplier]),
    AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[PartSupp]),
    AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[Customer]),
    AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[Orders]),
    AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[Lineitem])
  )
