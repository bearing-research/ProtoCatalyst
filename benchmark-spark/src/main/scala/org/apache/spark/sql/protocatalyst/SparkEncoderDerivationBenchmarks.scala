package org.apache.spark.sql.protocatalyst

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._

import org.apache.spark.sql.catalyst.ScalaReflection

import protocatalyst.benchmark.tpch.Schemas._

/** Encoder *derivation* throughput for Spark's reflective `ScalaReflection.encoderFor[T]` — the
  * function the whole typed-encoder pipeline funnels through. Its dispatch is a chain of
  * `isSubtype(t, …)` calls, each of which takes a **global** `ScalaSubtypeLock.synchronized`
  * (because Scala 2's `<:<` is not thread-safe — scala/bug#10766). So concurrent derivation
  * serializes on one lock.
  *
  * Run at increasing thread counts to expose it; compare against the Scala 3 lock-free
  * `EncoderDerivationBenchmarks` (compile-time `ProtoEncoder.derived` + bridge):
  * {{{
  * sbt 'benchmarkSpark/Jmh/run -f 3 -wi 5 -i 10 -t 1  SparkEncoderDerivationBenchmarks'
  * sbt 'benchmarkSpark/Jmh/run -f 3 -wi 5 -i 10 -t 8  SparkEncoderDerivationBenchmarks'
  * }}}
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
class SparkEncoderDerivationBenchmarks {

  // Lineitem: 16 fields — a realistic encoder, ~16+ isSubtype dispatches under the global lock.
  @Benchmark
  def deriveLineitem: AnyRef = ScalaReflection.encoderFor[Lineitem]

  @Benchmark
  def deriveOrders: AnyRef = ScalaReflection.encoderFor[Orders]
}
