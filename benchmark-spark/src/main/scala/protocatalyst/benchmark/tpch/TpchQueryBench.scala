package protocatalyst.benchmark.tpch

import java.io.{File, PrintWriter}
import java.time.Instant

import scala.util.Try

import org.apache.spark.sql.{DataFrame, SparkSession}

/** End-to-end TPC-H query benchmark harness.
 *
 * Runs Q1 / Q6 / Q14 / Q21 in two variants (DataFrame / Dataset[T]) under multiple Spark
 * configurations (codegen on/off, AQE on/off, threads), reports min-of-3-after-warmup
 * wall-clock per the methodology in `docs/scala3-encoder/BENCHMARKS.md`.
 *
 * Run:
 * {{{
 *   sbt 'benchmarkSpark/runMain protocatalyst.benchmark.tpch.TpchQueryBench [SF]'
 * }}}
 * Defaults to SF=1 if no argument given.
 *
 * Output: appends one row per (query × variant × config) to
 * `results/tpch-queries-<timestamp>.csv` with full disclosure header. The CSV is the rawest
 * possible artifact — chart rendering happens in the report (B.4).
 */
object TpchQueryBench {

  private case class Variant(name: String, run: SparkSession => DataFrame)

  private val queries: Seq[(String, Seq[Variant])] = Seq(
    "q1" -> Seq(
      Variant("df", TpchQueries.q1_df),
      Variant("ds", TpchQueries.q1_ds)
    ),
    "q6" -> Seq(
      Variant("df", TpchQueries.q6_df),
      Variant("ds", TpchQueries.q6_ds)
    ),
    "q14" -> Seq(
      Variant("df", TpchQueries.q14_df),
      Variant("ds", TpchQueries.q14_ds)
    ),
    "q21" -> Seq(
      Variant("df", TpchQueries.q21_df),
      Variant("ds", TpchQueries.q21_ds)
    )
  )

  // Three ablation axes per methodology doc.
  private case class Config(codegen: Boolean, aqe: Boolean, threads: String) {
    def label: String =
      s"codegen=${if (codegen) "on" else "off"},aqe=${if (aqe) "on" else "off"},threads=$threads"
    def asMaster: String = if (threads == "1") "local[1]" else "local[*]"
  }

  // Default 8 combos = 2 codegen × 2 AQE × 2 thread-count. Override via CLI args later if needed.
  private val configs: Seq[Config] = for {
    cg <- Seq(true, false)
    aqe <- Seq(true, false)
    th <- Seq("*", "1")
  } yield Config(cg, aqe, th)

  // Warmup + measurement counts come from the methodology doc.
  private val Warmup = 1
  private val Measure = 3

  // -----------------------------------------------------------------

  def main(args: Array[String]): Unit = {
    val sf = if (args.nonEmpty) args(0) else "1"
    val parquetExists = new File(TpchQueries.parquetDir(sf)).isDirectory
    if (!parquetExists) {
      System.err.println(s"Parquet dir not found: ${TpchQueries.parquetDir(sf)}")
      System.err.println("Generate it first:")
      System.err.println(s"  ./scripts/gen-tpch.sh $sf")
      System.err.println(s"  sbt 'benchmarkSpark/runMain protocatalyst.benchmark.tpch.TpchParquetConverter $sf'")
      sys.exit(1)
    }

    val ts = Instant.now().toString.replace(":", "-")
    val resultsDir = new File("results")
    resultsDir.mkdirs()
    val outFile = new File(resultsDir, s"tpch-queries-sf$sf-$ts.csv")
    val out = new PrintWriter(outFile)

    try {
      writeDisclosureHeader(out, sf)
      out.println("query,variant,codegen,aqe,threads,run,wall_ms")
      out.flush()

      configs.foreach { config =>
        runOneConfig(sf, config, out)
      }

      println(s"\n==> Results written to ${outFile.getAbsolutePath}")
    } finally out.close()
  }

  private def runOneConfig(sf: String, config: Config, out: PrintWriter): Unit = {
    println(s"\n=== Config: ${config.label} ===")

    val spark = SparkSession.builder()
      .appName(s"TpchQueryBench-${config.label}")
      .master(config.asMaster)
      .config("spark.sql.codegen.wholeStage", config.codegen.toString)
      .config("spark.sql.adaptive.enabled", config.aqe.toString)
      .config("spark.sql.shuffle.partitions", "8")
      .config("spark.ui.enabled", "false")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .config("spark.driver.host", "127.0.0.1")
      // Force user classpath first so sbt's `runMain` doesn't trip the REPL ExecutorClassLoader.
      .config("spark.driver.userClassPathFirst", "true")
      .config("spark.executor.userClassPathFirst", "true")
      .getOrCreate()

    try {
      TpchQueries.registerTables(spark, sf)

      queries.foreach { case (qName, variants) =>
        variants.foreach { variant =>
          val tag = s"$qName.${variant.name}"
          val runWithRetries: Either[Throwable, Seq[Long]] = Try {
            // Warmup. Use `collect()` (not `count()`) so Spark's optimizer can't prune unused
            // aggregates, ORDER BY, or output projections. For TPC-H the result sets are tiny
            // (Q1: ~4 rows, Q6/Q14: 1 row, Q21: ≤100 rows), so driver-side materialization is
            // cheap and full work is forced.
            (1 to Warmup).foreach { _ =>
              TpchQueries.withSf(spark, sf) {
                variant.run(spark).collect()
              }
            }
            // Measure
            (1 to Measure).map { _ =>
              clearCaches(spark)
              val t0 = System.nanoTime()
              val rows = TpchQueries.withSf(spark, sf) {
                variant.run(spark).collect()
              }
              val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
              println(f"  $tag%-8s run-${rows.length}%-5d → $elapsedMs ms")
              elapsedMs
            }
          }.toEither

          runWithRetries match {
            case Right(timings) =>
              timings.zipWithIndex.foreach { case (ms, i) =>
                out.println(
                  s"$qName,${variant.name},${config.codegen},${config.aqe},${config.threads},${i + 1},$ms"
                )
              }
              println(f"  $tag%-8s min=${timings.min}%5d ms  max=${timings.max}%5d ms")
            case Left(err) =>
              println(s"  $tag FAILED: ${err.getMessage}")
              out.println(
                s"$qName,${variant.name},${config.codegen},${config.aqe},${config.threads},error,${err.getClass.getSimpleName}"
              )
          }
          out.flush()
        }
      }
    } finally spark.stop()
  }

  private def clearCaches(spark: SparkSession): Unit = {
    spark.catalog.clearCache()
    // Page cache lives in the OS; we don't try to evict it here. The cold-vs-hot distinction
    // is approximated by re-creating SparkSession across configs; within a config we measure
    // hot-cache. The methodology doc's full cold-cache run is reserved for cloud runs (B.4).
  }

  private def writeDisclosureHeader(out: PrintWriter, sf: String): Unit = {
    val jdk = System.getProperty("java.version")
    val vendor = System.getProperty("java.vendor")
    val osName = System.getProperty("os.name")
    val osArch = System.getProperty("os.arch")
    val cores = Runtime.getRuntime.availableProcessors()
    val gitSha =
      Try(scala.sys.process.Process("git rev-parse HEAD").!!.trim).toOption.getOrElse("unknown")

    out.println(s"# tpch-query-bench")
    out.println(s"# scale_factor=$sf")
    out.println(s"# warmup=$Warmup measure=$Measure")
    out.println(s"# jdk=$jdk vendor=$vendor")
    out.println(s"# os=$osName arch=$osArch cores=$cores")
    out.println(s"# spark=4.1.2 scala=2.13.16")
    out.println(s"# git=$gitSha")
    out.println(s"# timestamp=${Instant.now()}")
    out.println(s"#")
  }
}
