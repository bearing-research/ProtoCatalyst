package protocatalyst.bench.tpch

import java.util.concurrent.TimeUnit

import scala.compiletime.uninitialized

import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.openjdk.jmh.annotations.*

import protocatalyst.encoder.spark.arrow.{ArrowRowSerializer, ArrowStreaming}
import protocatalyst.encoder.spark.tpch.Schemas

/** JMH benchmarks for `ArrowRowSerializer` (Scala 3, compile-time macro-derived) on TPC-H
  * shapes. Mirror of `benchmarkSpark/.../TpchSparkConnectArrowBenchmarks`.
  *
  * Per-row metric: average time to encode one record as one batch worth of Arrow IPC bytes,
  * amortized over 1024 rows per benchmark invocation. The amortization bounds vector memory
  * (one batch's worth) and keeps the reset() out of the hot path.
  *
  * Run:
  * {{{
  * sbt 'benchmarks/Jmh/run -f 3 -wi 5 -i 15 -prof gc TpchArrowRowSerializerBenchmarks'
  * }}}
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 15, time = 1)
class TpchArrowRowSerializerBenchmarks:

  final val OpsPerBatch = 1024

  @Param(Array("0.01", "1"))
  var sf: String = uninitialized

  var allocator: BufferAllocator = uninitialized

  var lineitemRows: Vector[Schemas.Lineitem] = uninitialized
  var ordersRows: Vector[Schemas.Orders] = uninitialized
  var customerRows: Vector[Schemas.Customer] = uninitialized
  var partRows: Vector[Schemas.Part] = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    allocator = new RootAllocator(Long.MaxValue)
    lineitemRows = TpchData.loadLineitem(sf)
    ordersRows = TpchData.loadOrders(sf)
    customerRows = TpchData.loadCustomer(sf)
    partRows = TpchData.loadPart(sf)

  @TearDown(Level.Trial)
  def teardown(): Unit = allocator.close()

  // === Per-batch IPC byte production. Returns total bytes to defeat DCE. ===

  @Benchmark
  @OperationsPerInvocation(1024)
  def lineitemBatch: Long =
    val input = Iterator.tabulate(OpsPerBatch)(i => lineitemRows(i % lineitemRows.size))
    val it = ArrowStreaming.serialize[Schemas.Lineitem](
      input,
      allocator,
      maxRecordsPerBatch = OpsPerBatch,
      maxBatchSize = Long.MaxValue,
      timeZoneId = "UTC",
      largeVarTypes = false,
      batchSizeCheckInterval = 128
    )
    drain(it)

  @Benchmark
  @OperationsPerInvocation(1024)
  def ordersBatch: Long =
    val input = Iterator.tabulate(OpsPerBatch)(i => ordersRows(i % ordersRows.size))
    val it = ArrowStreaming.serialize[Schemas.Orders](
      input,
      allocator,
      maxRecordsPerBatch = OpsPerBatch,
      maxBatchSize = Long.MaxValue,
      timeZoneId = "UTC",
      largeVarTypes = false,
      batchSizeCheckInterval = 128
    )
    drain(it)

  @Benchmark
  @OperationsPerInvocation(1024)
  def customerBatch: Long =
    val input = Iterator.tabulate(OpsPerBatch)(i => customerRows(i % customerRows.size))
    val it = ArrowStreaming.serialize[Schemas.Customer](
      input,
      allocator,
      maxRecordsPerBatch = OpsPerBatch,
      maxBatchSize = Long.MaxValue,
      timeZoneId = "UTC",
      largeVarTypes = false,
      batchSizeCheckInterval = 128
    )
    drain(it)

  @Benchmark
  @OperationsPerInvocation(1024)
  def partBatch: Long =
    val input = Iterator.tabulate(OpsPerBatch)(i => partRows(i % partRows.size))
    val it = ArrowStreaming.serialize[Schemas.Part](
      input,
      allocator,
      maxRecordsPerBatch = OpsPerBatch,
      maxBatchSize = Long.MaxValue,
      timeZoneId = "UTC",
      largeVarTypes = false,
      batchSizeCheckInterval = 128
    )
    drain(it)

  /** Drain the IPC stream iterator into a byte counter — defeats DCE without buffering. */
  private inline def drain(
      it: protocatalyst.encoder.spark.arrow.CloseableIteratorLike[Array[Byte]]
  ): Long =
    var total = 0L
    try while it.hasNext do total += it.next().length.toLong
    finally it.close()
    total
