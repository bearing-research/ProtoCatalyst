package protocatalyst.bench.tpch

import java.util.concurrent.TimeUnit

import scala.compiletime.uninitialized

import org.apache.spark.sql.catalyst.expressions.UnsafeRow
import org.openjdk.jmh.annotations.*

import protocatalyst.encoder.spark.UnsafeRowSerializer
import protocatalyst.encoder.spark.tpch.Schemas

/** JMH benchmarks for `UnsafeRowSerializer` (Scala 3, compile-time-derived) on TPC-H shapes.
  *
  * The Spark side of this comparison lives in `benchmark-spark/.../TpchSparkEncoderBenchmarks`
  * (Scala 2.13, `ExpressionEncoder`). Two separate JMH runs, two JSON outputs, merged at report
  * time — the Scala 3 / Scala 2.13 wall makes a single benchmark module impractical (Spark's
  * codegen path can't run from Scala 3; see `docs/ENCODER_PARITY.md`).
  *
  * Methodology (per `docs/BENCHMARK_METHODOLOGY.md`): 3 forks, 5 warmup + 15 measurement
  * iterations × 1s, `-prof gc` for allocation profiling. Scale factor parameterised via
  * `@Param`; SF=1 is the canonical JMH scale.
  *
  * Run:
  * {{{
  * sbt 'benchmarks/Jmh/run -f 3 -wi 5 -i 15 -prof gc TpchUnsafeRowBenchmarks'
  * }}}
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 15, time = 1)
class TpchUnsafeRowBenchmarks:

  @Param(Array("0.01", "1"))
  var sf: String = uninitialized

  // === Lineitem — the 16-column wide-fact workhorse ===

  var lineitemRows: Vector[Schemas.Lineitem] = uninitialized
  var lineitemSer: UnsafeRowSerializer[Schemas.Lineitem] = uninitialized
  var lineitemPreSerialized: UnsafeRow = uninitialized

  // === Orders — 9 columns, mid-width ===

  var ordersRows: Vector[Schemas.Orders] = uninitialized
  var ordersSer: UnsafeRowSerializer[Schemas.Orders] = uninitialized
  var ordersPreSerialized: UnsafeRow = uninitialized

  // === Customer — 8 columns, varchar-heavy ===

  var customerRows: Vector[Schemas.Customer] = uninitialized
  var customerSer: UnsafeRowSerializer[Schemas.Customer] = uninitialized
  var customerPreSerialized: UnsafeRow = uninitialized

  // === Part — 9 columns, decimal-heavy ===

  var partRows: Vector[Schemas.Part] = uninitialized
  var partSer: UnsafeRowSerializer[Schemas.Part] = uninitialized
  var partPreSerialized: UnsafeRow = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    lineitemRows = TpchData.loadLineitem(sf)
    lineitemSer = UnsafeRowSerializer.derived[Schemas.Lineitem]
    lineitemPreSerialized = lineitemSer.serialize(lineitemRows.head).copy()

    ordersRows = TpchData.loadOrders(sf)
    ordersSer = UnsafeRowSerializer.derived[Schemas.Orders]
    ordersPreSerialized = ordersSer.serialize(ordersRows.head).copy()

    customerRows = TpchData.loadCustomer(sf)
    customerSer = UnsafeRowSerializer.derived[Schemas.Customer]
    customerPreSerialized = customerSer.serialize(customerRows.head).copy()

    partRows = TpchData.loadPart(sf)
    partSer = UnsafeRowSerializer.derived[Schemas.Part]
    partPreSerialized = partSer.serialize(partRows.head).copy()

  // === Serialize ===

  @Benchmark def lineitemSerialize: UnsafeRow = lineitemSer.serialize(lineitemRows.head)
  @Benchmark def ordersSerialize: UnsafeRow   = ordersSer.serialize(ordersRows.head)
  @Benchmark def customerSerialize: UnsafeRow = customerSer.serialize(customerRows.head)
  @Benchmark def partSerialize: UnsafeRow     = partSer.serialize(partRows.head)

  // === Deserialize (from pre-serialized row) ===

  @Benchmark def lineitemDeserialize: Schemas.Lineitem = lineitemSer.deserialize(lineitemPreSerialized)
  @Benchmark def ordersDeserialize: Schemas.Orders     = ordersSer.deserialize(ordersPreSerialized)
  @Benchmark def customerDeserialize: Schemas.Customer = customerSer.deserialize(customerPreSerialized)
  @Benchmark def partDeserialize: Schemas.Part         = partSer.deserialize(partPreSerialized)

  // === Roundtrip ===

  @Benchmark def lineitemRoundtrip: Schemas.Lineitem =
    lineitemSer.deserialize(lineitemSer.serialize(lineitemRows.head))
  @Benchmark def ordersRoundtrip: Schemas.Orders =
    ordersSer.deserialize(ordersSer.serialize(ordersRows.head))
  @Benchmark def customerRoundtrip: Schemas.Customer =
    customerSer.deserialize(customerSer.serialize(customerRows.head))
  @Benchmark def partRoundtrip: Schemas.Part =
    partSer.deserialize(partSer.serialize(partRows.head))
