package protocatalyst.benchmark.tpch

import java.util.concurrent.TimeUnit

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.openjdk.jmh.annotations._

import protocatalyst.benchmark.tpch.Schemas._

/** JMH benchmarks for Spark's `ExpressionEncoder` on TPC-H shapes (Scala 2.13, runtime
 * reflection + whole-stage codegen → UnsafeRow).
 *
 * The Scala 3 / ProtoCatalyst side lives in `benchmarks/.../TpchUnsafeRowBenchmarks`. Two JSON
 * outputs, merged at report time.
 *
 * Run:
 * {{{
 *   sbt 'benchmarkSpark/Jmh/run -f 3 -wi 5 -i 15 -prof gc TpchSparkEncoderBenchmarks'
 * }}}
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 15, time = 1)
class TpchSparkEncoderBenchmarks {

  @Param(Array("0.01", "1"))
  var sf: String = _

  // === Lineitem ===
  var lineitemRows: Vector[Lineitem] = _
  var lineitemSer: Lineitem => InternalRow = _
  var lineitemDeser: InternalRow => Lineitem = _
  var lineitemPreSerialized: InternalRow = _

  // === Orders ===
  var ordersRows: Vector[Orders] = _
  var ordersSer: Orders => InternalRow = _
  var ordersDeser: InternalRow => Orders = _
  var ordersPreSerialized: InternalRow = _

  // === Customer ===
  var customerRows: Vector[Customer] = _
  var customerSer: Customer => InternalRow = _
  var customerDeser: InternalRow => Customer = _
  var customerPreSerialized: InternalRow = _

  // === Part ===
  var partRows: Vector[Part] = _
  var partSer: Part => InternalRow = _
  var partDeser: InternalRow => Part = _
  var partPreSerialized: InternalRow = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    lineitemRows = TpchData.loadLineitem(sf)
    val lineitemEnc = ExpressionEncoder[Lineitem]()
    lineitemSer = lineitemEnc.createSerializer()
    lineitemDeser = lineitemEnc.resolveAndBind().createDeserializer()
    lineitemPreSerialized = lineitemSer(lineitemRows.head).copy()

    ordersRows = TpchData.loadOrders(sf)
    val ordersEnc = ExpressionEncoder[Orders]()
    ordersSer = ordersEnc.createSerializer()
    ordersDeser = ordersEnc.resolveAndBind().createDeserializer()
    ordersPreSerialized = ordersSer(ordersRows.head).copy()

    customerRows = TpchData.loadCustomer(sf)
    val customerEnc = ExpressionEncoder[Customer]()
    customerSer = customerEnc.createSerializer()
    customerDeser = customerEnc.resolveAndBind().createDeserializer()
    customerPreSerialized = customerSer(customerRows.head).copy()

    partRows = TpchData.loadPart(sf)
    val partEnc = ExpressionEncoder[Part]()
    partSer = partEnc.createSerializer()
    partDeser = partEnc.resolveAndBind().createDeserializer()
    partPreSerialized = partSer(partRows.head).copy()
  }

  // === Serialize ===
  @Benchmark def lineitemSerialize: InternalRow = lineitemSer(lineitemRows.head)
  @Benchmark def ordersSerialize: InternalRow   = ordersSer(ordersRows.head)
  @Benchmark def customerSerialize: InternalRow = customerSer(customerRows.head)
  @Benchmark def partSerialize: InternalRow     = partSer(partRows.head)

  // === Deserialize ===
  @Benchmark def lineitemDeserialize: Lineitem = lineitemDeser(lineitemPreSerialized)
  @Benchmark def ordersDeserialize: Orders     = ordersDeser(ordersPreSerialized)
  @Benchmark def customerDeserialize: Customer = customerDeser(customerPreSerialized)
  @Benchmark def partDeserialize: Part         = partDeser(partPreSerialized)

  // === Roundtrip ===
  @Benchmark def lineitemRoundtrip: Lineitem = lineitemDeser(lineitemSer(lineitemRows.head))
  @Benchmark def ordersRoundtrip: Orders     = ordersDeser(ordersSer(ordersRows.head))
  @Benchmark def customerRoundtrip: Customer = customerDeser(customerSer(customerRows.head))
  @Benchmark def partRoundtrip: Part         = partDeser(partSer(partRows.head))
}
