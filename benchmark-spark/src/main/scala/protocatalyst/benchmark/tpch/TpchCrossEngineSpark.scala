package protocatalyst.benchmark.tpch

import java.io.{File, PrintWriter}
import java.time.Instant

import org.apache.spark.sql.SparkSession

/** Spark 4.1 baseline for the cross-engine TPC-H comparison: runs the *same* SQL (Q1/Q3/Q5/Q6/Q10)
 * the Truffle backend and the interpreter run (`truffle-backend`'s `TpchBench`), over the *same*
 * parquet (`data/tpch/sf-$sf-parquet`), so the three engines are directly comparable.
 *
 * Two measurements:
 *  - `--mode steady` (default): warm execution latency — `warmup` untimed + `iters` timed
 *    `collect()`s per query, reporting median/min/p90. Comparable to `TpchBench`.
 *  - `--mode cold`: whole-process cold start — one `spark.sql(q).collect()` of a single query
 *    (default Q6) timed from JVM start, *including* SparkSession creation and whole-stage codegen.
 *    This is the headline axis: Spark's JVM-warmup + Janino-codegen startup tax. Run as a fresh
 *    process and compare wall time against the native-image Truffle driver.
 *
 * Run:
 * {{{
 *   sbt 'benchmarkSpark/runMain protocatalyst.benchmark.tpch.TpchCrossEngineSpark --sf 1 --mode steady'
 *   sbt 'benchmarkSpark/runMain protocatalyst.benchmark.tpch.TpchCrossEngineSpark --sf 1 --mode cold --query Q6'
 * }}}
 * `collect()` (not `count()`) forces full work — the optimizer can't prune output. Result sets are
 * tiny (≤25 rows). Single config: whole-stage codegen on, AQE on, `local[*]` (Spark as users run it;
 * the Truffle backend is currently single-threaded — disclosed in the output header).
 */
object TpchCrossEngineSpark {

  private val Revenue =
    "SUM(CAST(l.extendedprice AS DOUBLE) * (1 - CAST(l.discount AS DOUBLE))) AS revenue"

  private val Queries: Map[String, String] = Map(
    "Q1" ->
      """SELECT returnflag, linestatus, COUNT(*) AS cnt,
        |       SUM(CAST(quantity AS DOUBLE)) AS sum_qty,
        |       SUM(CAST(extendedprice AS DOUBLE)) AS sum_base_price
        |FROM lineitem
        |GROUP BY returnflag, linestatus
        |ORDER BY returnflag, linestatus""".stripMargin,
    "Q3" ->
      s"""SELECT l.orderkey, $Revenue, o.orderdate, o.shippriority
         |FROM customer c
         |JOIN orders o ON c.custkey = o.custkey
         |JOIN lineitem l ON l.orderkey = o.orderkey
         |WHERE c.mktsegment = 'BUILDING'
         |  AND o.orderdate < DATE '1995-03-15'
         |  AND l.shipdate > DATE '1995-03-15'
         |GROUP BY l.orderkey, o.orderdate, o.shippriority
         |ORDER BY revenue DESC, o.orderdate
         |LIMIT 10""".stripMargin,
    "Q5" ->
      s"""SELECT n.name, $Revenue
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
    "Q6" ->
      """SELECT SUM(CAST(extendedprice AS DOUBLE) * CAST(discount AS DOUBLE)) AS revenue
        |FROM lineitem
        |WHERE shipdate >= DATE '1994-01-01' AND shipdate < DATE '1995-01-01'
        |  AND discount BETWEEN 0.05 AND 0.07 AND quantity < 24""".stripMargin,
    "Q10" ->
      s"""SELECT c.custkey, c.name, $Revenue, c.acctbal, n.name AS nation, c.address, c.phone, c.comment
         |FROM customer c
         |JOIN orders o ON c.custkey = o.custkey
         |JOIN lineitem l ON l.orderkey = o.orderkey
         |JOIN nation n ON c.nationkey = n.nationkey
         |WHERE o.orderdate >= DATE '1993-10-01'
         |  AND o.orderdate < DATE '1994-01-01'
         |  AND l.returnflag = 'R'
         |GROUP BY c.custkey, c.name, c.acctbal, c.phone, n.name, c.address, c.comment
         |ORDER BY revenue DESC
         |LIMIT 20""".stripMargin
  )

  private val Order = Seq("Q1", "Q3", "Q5", "Q6", "Q10")

  def main(args: Array[String]): Unit = {
    val sf = argOr(args, "--sf", "0.01")
    val mode = argOr(args, "--mode", "steady")
    val warmup = argOr(args, "--warmup", "10").toInt
    val iters = argOr(args, "--iters", "30").toInt
    val coldQuery = argOr(args, "--query", "Q6")

    val parquet = TpchQueries.parquetDir(sf)
    if (!new File(parquet).isDirectory) {
      System.err.println(s"Parquet dir not found: $parquet — run scripts/gen-tpch.sh $sf")
      sys.exit(1)
    }

    mode match {
      case "cold"   => runCold(sf, coldQuery)
      case "steady" => runSteady(sf, warmup, iters)
      case other    => System.err.println(s"unknown --mode $other (steady|cold)"); sys.exit(1)
    }
  }

  /** Whole-process cold start: time from here (JVM already up, but Spark not) through SparkSession
   * creation, table registration, and the first `collect()` of one query. The wall time a user waits
   * for their first result. */
  private def runCold(sf: String, query: String): Unit = {
    val sql = Queries.getOrElse(query, sys.error(s"unknown query $query"))
    val t0 = System.nanoTime()
    val spark = newSession()
    try {
      TpchQueries.registerTables(spark, sf)
      val rows = spark.sql(sql).collect()
      val elapsedMs = (System.nanoTime() - t0) / 1000000L
      println(s"# TPC-H Spark COLD START — sf=$sf query=$query")
      header(sf)
      println(s"spark,cold,$query,$elapsedMs,${rows.length}")
      println(f"# first-result wall time (SparkSession init + codegen + execute): $elapsedMs%,d ms")
    } finally spark.stop()
  }

  private def runSteady(sf: String, warmup: Int, iters: Int): Unit = {
    println(s"# TPC-H Spark STEADY STATE — sf=$sf warmup=$warmup iters=$iters")
    header(sf)
    val spark = newSession()
    val csv = new StringBuilder("query,engine,median_us,min_us,p90_us,rows\n")
    try {
      TpchQueries.registerTables(spark, sf)
      println(f"${"query"}%-5s  ${"engine"}%-8s  result")
      println("-" * 70)
      for (q <- Order) {
        val sql = Queries(q)
        var rows = 0L
        var w = 0
        while (w < warmup) { rows = spark.sql(sql).collect().length.toLong; w += 1 }
        val samples = new Array[Double](iters)
        var i = 0
        while (i < iters) {
          val s0 = System.nanoTime()
          rows = spark.sql(sql).collect().length.toLong
          samples(i) = (System.nanoTime() - s0) / 1000.0
          i += 1
        }
        val sorted = samples.sorted
        val med = pct(sorted, 0.5); val mn = sorted.head; val p90 = pct(sorted, 0.9)
        println(f"$q%-5s  ${"spark"}%-8s  median=$med%10.1f  min=$mn%10.1f  p90=$p90%10.1f  rows=$rows")
        csv.append(f"$q,spark,$med%.1f,$mn%.1f,$p90%.1f,$rows\n")
      }
    } finally spark.stop()
    val out = new File("results", s"spark-steadystate-sf$sf-${System.currentTimeMillis()}.csv")
    out.getParentFile.mkdirs()
    val w = new PrintWriter(out); try w.write(csv.toString) finally w.close()
    println(s"# wrote ${out.getPath}")
  }

  private def newSession(): SparkSession =
    SparkSession.builder()
      .appName("TpchCrossEngineSpark")
      .master("local[*]")
      .config("spark.sql.codegen.wholeStage", "true")
      .config("spark.sql.adaptive.enabled", "true")
      .config("spark.sql.shuffle.partitions", "8")
      .config("spark.ui.enabled", "false")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.driver.userClassPathFirst", "true")
      .config("spark.executor.userClassPathFirst", "true")
      .getOrCreate()

  private def pct(sorted: Array[Double], p: Double): Double =
    if (sorted.isEmpty) 0.0
    else sorted(math.min(sorted.length - 1, math.max(0, math.round(p * (sorted.length - 1)).toInt)))

  private def header(sf: String): Unit = {
    println(s"# spark=4.1.2 scala=2.13.16 master=local[*] codegen=on aqe=on")
    println(s"# jdk=${System.getProperty("java.version")} os=${System.getProperty("os.name")} " +
      s"arch=${System.getProperty("os.arch")} cores=${Runtime.getRuntime.availableProcessors()}")
    println(s"# timestamp=${Instant.now()}")
    println(s"# NOTE: Spark runs multi-threaded (local[*]); the Truffle backend is single-threaded.")
  }

  private def argOr(args: Array[String], flag: String, default: String): String = {
    val i = args.indexOf(flag)
    if (i >= 0 && i + 1 < args.length) args(i + 1) else default
  }
}
