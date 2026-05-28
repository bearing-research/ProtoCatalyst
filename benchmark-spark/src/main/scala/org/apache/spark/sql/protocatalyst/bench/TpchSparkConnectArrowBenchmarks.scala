// Lives under `org.apache.spark.sql.*` for access to `private[sql]` Arrow/encoder internals.
package org.apache.spark.sql.protocatalyst.bench

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.connect.client.arrow.ArrowSerializer
import org.openjdk.jmh.annotations._

import protocatalyst.benchmark.tpch.{Schemas, TpchData}

/** JMH benchmarks for Spark Connect's `ArrowSerializer[T]` (Scala 2.13, reflection-based
  * AgnosticEncoder derivation) on TPC-H shapes.
  *
  * Compared against `benchmarks/.../TpchArrowRowSerializerBenchmarks` (Scala 3, compile-time
  * macro-derived). Two separate JMH runs, two JSON outputs, merged at report time — same
  * pattern as the UnsafeRow side.
  *
  * Per-row metric: average time to encode one record as one batch worth of Arrow IPC bytes,
  * amortized over `OpsPerBatch` rows per benchmark invocation. The amortization bounds
  * vector memory (one batch's worth) and keeps the reset() out of the hot path.
  *
  * Run:
  * {{{
  * sbt 'benchmarkSpark/Jmh/run -f 3 -wi 5 -i 15 -prof gc TpchSparkConnectArrowBenchmarks'
  * }}}
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 15, time = 1)
class TpchSparkConnectArrowBenchmarks {

  // Per-invocation batch size. Picked so total memory per benchmark stays in low MB even for
  // 16-column Lineitem (~100 B/row × 1024 = ~100 KB). Larger batches amortize reset() more
  // but pay vector-growth cost during warmup; 1024 is a comfortable middle.
  final val OpsPerBatch = 1024

  @Param(Array("0.01", "1"))
  var sf: String = _

  // Per-table state.
  var allocator: BufferAllocator = _

  var lineitemRows: Vector[Schemas.Lineitem] = _
  var lineitemEnc: ExpressionEncoder[Schemas.Lineitem] = _

  var ordersRows: Vector[Schemas.Orders] = _
  var ordersEnc: ExpressionEncoder[Schemas.Orders] = _

  var customerRows: Vector[Schemas.Customer] = _
  var customerEnc: ExpressionEncoder[Schemas.Customer] = _

  var partRows: Vector[Schemas.Part] = _
  var partEnc: ExpressionEncoder[Schemas.Part] = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    allocator = new RootAllocator(Long.MaxValue)
    lineitemRows = TpchData.loadLineitem(sf)
    lineitemEnc = ExpressionEncoder[Schemas.Lineitem]()
    ordersRows = TpchData.loadOrders(sf)
    ordersEnc = ExpressionEncoder[Schemas.Orders]()
    customerRows = TpchData.loadCustomer(sf)
    customerEnc = ExpressionEncoder[Schemas.Customer]()
    partRows = TpchData.loadPart(sf)
    partEnc = ExpressionEncoder[Schemas.Part]()
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = allocator.close()

  // === Per-batch IPC byte production. Returns total bytes (Blackhole-equivalent: prevents DCE). ===

  @Benchmark
  @OperationsPerInvocation(1024)
  def lineitemBatch: Long = batchTo[Schemas.Lineitem](lineitemRows, lineitemEnc)

  @Benchmark
  @OperationsPerInvocation(1024)
  def ordersBatch: Long = batchTo[Schemas.Orders](ordersRows, ordersEnc)

  @Benchmark
  @OperationsPerInvocation(1024)
  def customerBatch: Long = batchTo[Schemas.Customer](customerRows, customerEnc)

  @Benchmark
  @OperationsPerInvocation(1024)
  def partBatch: Long = batchTo[Schemas.Part](partRows, partEnc)

  /** Encode `OpsPerBatch` rows (cycling through `rows` if shorter) as a single Arrow IPC batch
    * via Spark Connect's `ArrowSerializer.serialize`. Returns total bytes to defeat DCE.
    */
  private def batchTo[T](rows: Vector[T], enc: ExpressionEncoder[T]): Long = {
    val input = Iterator.tabulate(OpsPerBatch)(i => rows(i % rows.size))
    val it = ArrowSerializer.serialize[T](
      input = input,
      enc = enc.encoder,
      allocator = allocator,
      maxRecordsPerBatch = OpsPerBatch,
      maxBatchSize = Long.MaxValue,
      timeZoneId = "UTC",
      largeVarTypes = false,
      batchSizeCheckInterval = 128)
    val out = new ByteArrayOutputStream()
    try {
      while (it.hasNext) out.write(it.next())
    } finally it.close()
    out.size().toLong
  }
}
