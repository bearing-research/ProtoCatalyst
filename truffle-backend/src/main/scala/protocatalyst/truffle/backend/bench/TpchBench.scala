package protocatalyst.truffle.backend.bench

import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.*

import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.apache.arrow.vector.util.VectorSchemaRootAppender

import protocatalyst.arrow.parquet.ParquetIO
import protocatalyst.executor.Catalog
import protocatalyst.executor.exec.Batch
import protocatalyst.executor.physical.PhysicalPlanExecutor
import protocatalyst.optimizer.Optimizer
import protocatalyst.plan.{PhysicalPlanner, ProtoPhysicalPlan}
import protocatalyst.schema.ProtoSchema
import protocatalyst.sql.parser.SqlParser
import protocatalyst.sql.transform.AstToProtoTransform
import protocatalyst.truffle.backend.TypedTruffleCompiler

/** Steady-state (warm) timing harness: runs the benchmark-slice TPC-H queries (Q1/Q3/Q5/Q6/Q10) on
  * both the Scala interpreter (`PhysicalPlanExecutor`) and the Truffle backend (`TypedTruffleCompiler`)
  * over the *same* optimizer-produced `ProtoPhysicalPlan`, and reports per-engine execution latency.
  *
  * This is the steady-state half of the cross-engine figure (the cold-start half is the native-image
  * driver). Both engines consume the identical plan, so the comparison is apples-to-apples; the queries
  * are parity-checked row-for-row in `TpchTruffleParitySpec`. Spark (the external baseline) is measured
  * separately in `benchmark-spark` on the same SQL.
  *
  * Run:
  * {{{
  * sbt 'truffleBackend/runMain protocatalyst.truffle.backend.bench.TpchBench --sf 1 --warmup 15 --iters 50'
  * }}}
  * Output: a console table (median / min / p90 µs per query×engine) and a CSV under `results/`.
  *
  * Methodology: a fixed plan is compiled once per engine, then run `warmup` untimed iterations (so the
  * JVM JIT and Truffle partial evaluation warm up) followed by `iters` timed iterations; we report the
  * median and p90 (steady-state, robust to GC/scheduling outliers) per Georges et al. (OOPSLA 2007).
  */
object TpchBench:

  private case class Q(name: String, sql: String, tables: Seq[String])

  private val revenue =
    "SUM(CAST(l.extendedprice AS DOUBLE) * (1 - CAST(l.discount AS DOUBLE))) AS revenue"

  private val queries: Vector[Q] = Vector(
    Q(
      "Q1",
      """SELECT returnflag, linestatus, COUNT(*) AS cnt,
        |       SUM(CAST(quantity AS DOUBLE)) AS sum_qty,
        |       SUM(CAST(extendedprice AS DOUBLE)) AS sum_base_price
        |FROM lineitem
        |GROUP BY returnflag, linestatus
        |ORDER BY returnflag, linestatus""".stripMargin,
      Seq("lineitem")
    ),
    Q(
      "Q3",
      s"""SELECT l.orderkey, $revenue, o.orderdate, o.shippriority
         |FROM customer c
         |JOIN orders o ON c.custkey = o.custkey
         |JOIN lineitem l ON l.orderkey = o.orderkey
         |WHERE c.mktsegment = 'BUILDING'
         |  AND o.orderdate < DATE '1995-03-15'
         |  AND l.shipdate > DATE '1995-03-15'
         |GROUP BY l.orderkey, o.orderdate, o.shippriority
         |ORDER BY revenue DESC, o.orderdate
         |LIMIT 10""".stripMargin,
      Seq("customer", "orders", "lineitem")
    ),
    Q(
      "Q5",
      s"""SELECT n.name, $revenue
         |FROM customer c
         |JOIN orders o ON c.custkey = o.custkey
         |JOIN lineitem l ON l.orderkey = o.orderkey
         |JOIN supplier s ON l.suppkey = s.suppkey AND c.nationkey = s.nationkey
         |JOIN nation n ON s.nationkey = n.nationkey
         |JOIN region r ON n.regionkey = r.regionkey
         |WHERE r.name = 'ASIA'
         |  AND o.orderdate >= DATE '1994-01-01'
         |  AND o.orderdate < DATE '1995-01-01'
         |GROUP BY n.name
         |ORDER BY revenue DESC""".stripMargin,
      Seq("customer", "orders", "lineitem", "supplier", "nation", "region")
    ),
    Q(
      "Q6",
      """SELECT SUM(CAST(extendedprice AS DOUBLE) * CAST(discount AS DOUBLE)) AS revenue
        |FROM lineitem
        |WHERE shipdate >= DATE '1994-01-01' AND shipdate < DATE '1995-01-01'
        |  AND discount BETWEEN 0.05 AND 0.07 AND quantity < 24""".stripMargin,
      Seq("lineitem")
    ),
    Q(
      "Q10",
      s"""SELECT c.custkey, c.name, $revenue, c.acctbal, n.name AS nation, c.address, c.phone, c.comment
         |FROM customer c
         |JOIN orders o ON c.custkey = o.custkey
         |JOIN lineitem l ON l.orderkey = o.orderkey
         |JOIN nation n ON c.nationkey = n.nationkey
         |WHERE o.orderdate >= DATE '1993-10-01'
         |  AND o.orderdate < DATE '1994-01-01'
         |  AND l.returnflag = 'R'
         |GROUP BY c.custkey, c.name, c.acctbal, c.phone, n.name, c.address, c.comment
         |ORDER BY revenue DESC
         |LIMIT 20""".stripMargin,
      Seq("customer", "orders", "lineitem", "nation")
    )
  )

  private case class Stats(median: Double, min: Double, p90: Double, rows: Long):
    def fmt: String = f"median=$median%9.1f  min=$min%9.1f  p90=$p90%9.1f  rows=$rows"

  def main(args: Array[String]): Unit =
    val sf = argOr(args, "--sf", "0.01")
    val warmup = argOr(args, "--warmup", "10").toInt
    val iters = argOr(args, "--iters", "30").toInt
    // `--query Q5` runs a single query (so each can run in its own process — at SF=1 the inputs are
    // large and the interpreter leaks Arrow intermediates per call, so one-query-per-JVM bounds RSS).
    val only = argOr(args, "--query", "all")
    // `--engines truffle` skips the interpreter (whose per-call leak is impractical at scale).
    val engines = argOr(args, "--engines", "both")

    val dataDir = locateData(sf)
    println(s"# TPC-H steady-state benchmark — sf=$sf  warmup=$warmup  iters=$iters")
    println(s"# data: $dataDir")
    println(s"# units: microseconds per query execution (lower is better)")
    println()

    val csv = new StringBuilder("query,engine,median_us,min_us,p90_us,rows\n")
    println(f"${"query"}%-5s  ${"engine"}%-12s  result")
    println("-" * 78)

    val selected = queries.filter(q => only == "all" || q.name.equalsIgnoreCase(only))
    val runInterp = engines != "truffle"
    val runTruffleEng = engines != "interp"
    for q <- selected do
      val allocator = new RootAllocator()
      val batches = q.tables.map(t => t -> loadTable(dataDir, t, allocator)).toMap
      val physical = physicalOf(dataDir, q.sql, q.tables)

      if runInterp then
        val interp = benchInterp(physical, batches, allocator, warmup, iters)
        println(f"${q.name}%-5s  ${"interpreter"}%-12s  ${interp.fmt}")
        csv ++= f"${q.name},interpreter,${interp.median}%.1f,${interp.min}%.1f,${interp.p90}%.1f,${interp.rows}\n"
      if runTruffleEng then
        val truffle = benchTruffle(physical, batches, warmup, iters)
        println(f"${q.name}%-5s  ${"truffle"}%-12s  ${truffle.fmt}")
        csv ++= f"${q.name},truffle,${truffle.median}%.1f,${truffle.min}%.1f,${truffle.p90}%.1f,${truffle.rows}\n"
      println()

      // The allocator is intentionally left open (reclaimed at JVM exit). The interpreter allocates
      // intermediate Arrow vectors per `execute` without freeing them (the same behavior
      // TpchCrossBackendSuite documents), so closing here would trip Arrow's leak detector. This is a
      // measurement caveat — and itself a contrast: the Truffle backend produces GC'd heap rows.

    val out = Paths.get("results", s"truffle-steadystate-sf$sf-${System.currentTimeMillis()}.csv")
    Files.createDirectories(out.getParent)
    Files.writeString(out, csv.toString)
    println(s"# wrote $out")

  /** Time the interpreter: a fresh catalog per run (it allocates result vectors), result closed each
    * iteration so the allocator doesn't grow. */
  private def benchInterp(
      physical: ProtoPhysicalPlan,
      batches: Map[String, Batch],
      allocator: BufferAllocator,
      warmup: Int,
      iters: Int
  ): Stats =
    def once(): Long =
      val catalog = new Catalog()
      batches.foreach((t, b) => catalog.registerTable(t, b))
      val batch = PhysicalPlanExecutor(catalog, allocator).execute(physical)
      val n = batch.rowCount.toLong
      batch.close()
      n
    measure(warmup, iters, once)

  /** Time the Truffle backend: compile the plan once (so Graal partial evaluation warms the call
    * target across iterations), then run repeatedly. */
  private def benchTruffle(
      physical: ProtoPhysicalPlan,
      batches: Map[String, Batch],
      warmup: Int,
      iters: Int
  ): Stats =
    val compiled = TypedTruffleCompiler.compile(physical)
    def once(): Long = compiled.run(batches).rows.size.toLong
    measure(warmup, iters, once)

  private def measure(warmup: Int, iters: Int, body: () => Long): Stats =
    var rows = 0L
    var w = 0
    while w < warmup do { rows = body(); w += 1 }
    val samples = new Array[Double](iters)
    var i = 0
    while i < iters do
      val t0 = System.nanoTime()
      rows = body()
      samples(i) = (System.nanoTime() - t0) / 1000.0 // µs
      i += 1
    val sorted = samples.sorted
    Stats(percentile(sorted, 0.50), sorted.head, percentile(sorted, 0.90), rows)

  private def percentile(sorted: Array[Double], p: Double): Double =
    if sorted.isEmpty then 0.0
    else
      val idx = math.min(sorted.length - 1, math.max(0, math.round(p * (sorted.length - 1)).toInt))
      sorted(idx)

  // ── shared SQL → physical plan + data loading (mirrors TpchTruffleParitySpec) ──

  private def physicalOf(dataDir: String, sql: String, tables: Seq[String]): ProtoPhysicalPlan =
    val catalog = tables.map(t => t -> schemaOf(dataDir, t)).toMap
    val stmt = SqlParser.parse(sql).fold(e => sys.error(s"parse error: $e"), identity)
    val logical = AstToProtoTransform
      .transformStmt(stmt, catalog)
      .fold(e => sys.error(s"transform error: $e"), identity)
    val optimized = Optimizer.optimize(logical)
    // A stats provider so the planner can pick join build sides; per-table row/byte estimates.
    val statsCatalog = new Catalog()
    tables.foreach(t => statsCatalog.registerStatistics(t, ParquetIO.readStatistics(partFiles(dataDir, t).head)))
    PhysicalPlanner(statsCatalog.statsProvider).plan(optimized)

  private def schemaOf(dataDir: String, table: String): ProtoSchema =
    ParquetIO.readSchema(partFiles(dataDir, table).head)

  private def partFiles(dataDir: String, t: String): Vector[String] =
    Files.list(Paths.get(s"$dataDir/$t.parquet")).iterator.asScala
      .map(_.toString)
      .filter(_.endsWith(".parquet"))
      .toVector
      .sorted

  private def loadTable(dataDir: String, table: String, allocator: BufferAllocator): Batch =
    val parts = partFiles(dataDir, table)
    val (root, schema) = ParquetIO.read(parts.head, allocator)
    parts.tail.foreach { p =>
      val (r, _) = ParquetIO.read(p, allocator)
      VectorSchemaRootAppender.append(root, r)
      r.close()
    }
    Batch.fromRoot(root, schema)

  private def locateData(sf: String): String =
    val rel = Paths.get("data", "tpch", s"sf-$sf-parquet")
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .take(6)
      .map(_.resolve(rel))
      .find((p: Path) => Files.isDirectory(p.resolve("lineitem.parquet")))
      .map(_.toString)
      .getOrElse(sys.error(s"TPC-H parquet not found for sf=$sf (looked for $rel); run scripts/gen-tpch.sh"))

  private def argOr(args: Array[String], flag: String, default: String): String =
    val i = args.indexOf(flag)
    if i >= 0 && i + 1 < args.length then args(i + 1) else default
