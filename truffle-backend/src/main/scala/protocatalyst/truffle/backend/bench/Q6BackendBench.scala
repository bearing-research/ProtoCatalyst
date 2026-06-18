package protocatalyst.truffle.backend.bench

import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.{BigIntVector, FieldVector, Float8Vector, VectorSchemaRoot}
import org.openjdk.jmh.annotations.*

import protocatalyst.executor.Catalog
import protocatalyst.executor.exec.Batch
import protocatalyst.executor.physical.PhysicalPlanExecutor
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan.{ProtoPhysicalPlan, Statistics}
import protocatalyst.schema.ProtoSchema
import protocatalyst.truffle.backend.{CompiledTruffleQuery, ProtoTruffleCompiler}
import protocatalyst.truffle.slice.ColumnBatch
import protocatalyst.types.{LiteralValue, ProtoStructField, ProtoType}

/** Phase-3 gate benchmark: the same Q6-selection `ProtoPhysicalPlan` run through the project's Scala
  * interpreter (`PhysicalPlanExecutor`) vs the fused-row Truffle backend, over the same Arrow batch.
  *
  *   - `scalaInterp` — the columnar Scala interpreter (reads Arrow directly, returns an Arrow batch).
  *   - `truffleFull` — Truffle end-to-end: Arrow→double[] decode + fused AST execute.
  *   - `truffleExec` — Truffle pure execution over a pre-decoded column batch (decode amortized),
  *     isolating fused-AST cost from the decode tax the primitive-array model pays.
  *
  * Run: `sbt 'truffleBackend/Jmh/run -f2 -wi5 -i8 -t1 Q6BackendBench'`.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(2)
class Q6BackendBench:

  @Param(Array("65536"))
  var rows: Int = scala.compiletime.uninitialized

  private var alloc: RootAllocator = scala.compiletime.uninitialized
  private var catalog: Catalog = scala.compiletime.uninitialized
  private var physical: ProtoPhysicalPlan = scala.compiletime.uninitialized
  private var compiled: CompiledTruffleQuery = scala.compiletime.uninitialized
  private var input: Batch = scala.compiletime.uninitialized
  private var predecoded: ColumnBatch = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    alloc = new RootAllocator()
    val n = rows
    val orderkey = new BigIntVector("orderkey", alloc)
    val extendedprice = new Float8Vector("extendedprice", alloc)
    val discount = new Float8Vector("discount", alloc)
    val quantity = new Float8Vector("quantity", alloc)
    Seq(orderkey, extendedprice, discount, quantity).foreach(_.allocateNew(n))
    var i = 0
    while i < n do
      orderkey.set(i, i.toLong)
      extendedprice.set(i, 1000.0 + (i % 9000))
      discount.set(i, if i % 2 == 0 then 0.06 else 0.20)
      quantity.set(i, (i % 48).toDouble)
      i += 1
    Seq(orderkey, extendedprice, discount, quantity).foreach(_.setValueCount(n))
    val vecs: java.util.List[FieldVector] =
      List[FieldVector](orderkey, extendedprice, discount, quantity).asJava
    val root = new VectorSchemaRoot(vecs)
    root.setRowCount(n)
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("orderkey", ProtoType.LongType, false),
        ProtoStructField("extendedprice", ProtoType.DoubleType, false),
        ProtoStructField("discount", ProtoType.DoubleType, false),
        ProtoStructField("quantity", ProtoType.DoubleType, false)
      )
    )
    input = Batch.fromRoot(root, schema)

    def col(name: String, tpe: ProtoType) = ProtoExpr.ColumnRef(name, None, tpe, false)
    def lit(d: Double) = ProtoExpr.Literal(LiteralValue.DoubleValue(d))
    val filter = ProtoExpr.And(
      Vector(
        ProtoExpr.GtEq(col("discount", ProtoType.DoubleType), lit(0.05)),
        ProtoExpr.LtEq(col("discount", ProtoType.DoubleType), lit(0.07)),
        ProtoExpr.Lt(col("quantity", ProtoType.DoubleType), lit(24.0))
      )
    )
    // Filter-only (project all columns via identity): FilterOp copies into owned buffers, so the
    // Scala interpreter's result batch can be closed cleanly each call — no Arrow leak in the loop.
    // (PhysicalProject shares/drops vectors and leaks under tight iteration; out of scope here.)
    val scan = ProtoPhysicalPlan.TableScan("t", None, schema, Statistics(n.toLong, n * 64L))
    physical = ProtoPhysicalPlan.PhysicalFilter(filter, scan)

    catalog = new Catalog()
    catalog.registerTable("t", input)
    catalog.registerStatistics("t", Statistics(n.toLong, n * 64L))

    compiled = ProtoTruffleCompiler.compile(physical)
    predecoded = compiled.decode(input)

  @TearDown(Level.Trial)
  def teardown(): Unit =
    // The Scala interpreter copies the scanned table per call and frees it only at allocator close
    // (it's built for one-shot runs, not tight loops), so the loop accumulates Arrow memory. Swallow
    // the leak assertion at teardown; the `scalaInterp` timing is therefore leak-affected and should
    // be read as an order-of-magnitude figure, not a precise one. The Truffle numbers are clean (heap).
    try
      input.close()
      alloc.close()
    catch case _: IllegalStateException => ()

  @Benchmark
  def scalaInterp(): Int =
    val b = PhysicalPlanExecutor(catalog, alloc).execute(physical)
    val k = b.rowCount
    b.close()
    k

  @Benchmark
  def truffleFull(): Int = compiled.run(input).rowCount

  @Benchmark
  def truffleExec(): Int = compiled.execute(predecoded).rowCount

end Q6BackendBench
