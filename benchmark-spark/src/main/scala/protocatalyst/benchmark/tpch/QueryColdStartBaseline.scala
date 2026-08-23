package protocatalyst.benchmark.tpch

import java.lang.management.ManagementFactory

import org.apache.spark.sql.{Dataset, Encoder, SparkSession}
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder

/** Baseline half of the real-query benchmark: a **short-lived Spark driver** whose typed `Dataset[T]`
 * encoders come from Spark's reflective `ScalaReflection.encoderFor` (Scala 2.13, stock Spark).
 *
 * The Scala 3 half — same workload, same data, encoders from compile-time derivation — is
 * `benchmarks/.../bench/tpch/QueryColdStartProto.scala`. Both print the same CSV rows; the driver
 * script `scripts/query-bench.sh` runs each in fresh JVMs and aggregates.
 *
 * Why a whole process rather than JMH: the cost this benchmark is about (reflective universe init +
 * per-type derivation, both once per JVM) is *by construction* invisible to a warmed-up in-JVM
 * harness. What an end user feels is the wall clock of the job, so that is what is measured — with
 * a phase breakdown so the difference is attributable rather than magic.
 *
 * Usage: {{{ java -cp <benchmarkSpark runtime cp> protocatalyst.benchmark.tpch.QueryColdStartBaseline
 *            <sf> <q6|wide> <steadyIters> }}}
 */
object QueryColdStartBaseline {

  def main(args: Array[String]): Unit = {
    val sf = if (args.length > 0) args(0) else "1"
    val workload = if (args.length > 1) args(1) else "q6"
    val steadyIters = if (args.length > 2) args(2).toInt else 10

    val rt = ManagementFactory.getRuntimeMXBean
    val jvmToMainMs = rt.getUptime.toDouble

    // (1) COLD encoder, measured before anything else touches Spark. On this side that means
    // scala.reflect.runtime.universe symbol-table init + the derivation itself. It has to be first:
    // SparkSession.builder() also forces the universe (sql-api's reflective lookupCompanion), so
    // measuring the encoder after session creation silently charges the universe init to the
    // session and makes the derivation look free.
    val tCold0 = System.nanoTime()
    val encLineitem: Encoder[Schemas.Lineitem] = ExpressionEncoder[Schemas.Lineitem]()
    val encoderColdMs = (System.nanoTime() - tCold0) / 1e6

    val tSession0 = System.nanoTime()
    val spark = SparkSession
      .builder()
      .master("local[*]")
      .appName("query-coldstart-baseline")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "8")
      .getOrCreate()
    spark.sparkContext.setLogLevel("WARN")
    val sessionMs = (System.nanoTime() - tSession0) / 1e6

    try {
      // (2) First encoder built AFTER the session exists. Measured, but do not read it as a
      // derivation cost: it is dominated by Spark's own one-time init on first encoder use in a
      // live session, which both sides pay identically (~equal in every run to date). The real
      // per-additional-type cost is `encoder_per_additional_type` below.
      val tWarm0 = System.nanoTime()
      val encOrders: Encoder[Schemas.Orders] = ExpressionEncoder[Schemas.Orders]()
      val encoderWarmMs = (System.nanoTime() - tWarm0) / 1e6

      val r = workload match {
        case "q6"   => runQ6(spark, sf, steadyIters, encLineitem)
        case "wide" => runWide(spark, sf, steadyIters, encLineitem, encOrders)
        case other  => sys.error(s"unknown workload: $other")
      }
      val totalToFirstMs = r.uptimeAtFirstResultMs

      emit(workload, sf, "jvm_to_main", jvmToMainMs)
      emit(workload, sf, "encoder_cold_first_type", encoderColdMs)
      emit(workload, sf, "session_create", sessionMs)
      emit(workload, sf, "encoder_after_session", encoderWarmMs)
      emit(workload, sf, "encoder_remaining_types", r.encoderMs)
      if (r.remainingTypeCount > 0)
        emit(workload, sf, "encoder_per_additional_type", r.encoderMs / r.remainingTypeCount)
      emit(workload, sf, "query_build", r.buildMs)
      emit(workload, sf, "first_action", r.firstActionMs)
      emit(workload, sf, "total_to_first_result", totalToFirstMs)
      emit(workload, sf, "steady_median", r.steadyMedianMs)
      System.err.println(
        f"[baseline/$workload%s sf=$sf%s] total-to-first-result ${totalToFirstMs}%.0f ms " +
          f"(encoder cold ${encoderColdMs}%.1f, session ${sessionMs}%.0f, encoder warm " +
          f"${encoderWarmMs}%.1f, rest ${r.encoderMs}%.1f, build ${r.buildMs}%.0f, " +
          f"action ${r.firstActionMs}%.0f); steady median ${r.steadyMedianMs}%.0f ms; result=${r.result}"
      )
    } finally spark.stop()
  }

  private def emit(workload: String, sf: String, metric: String, ms: Double): Unit =
    println(f"RESULT,baseline,$workload%s,$sf%s,$metric%s,$ms%.3f")

  private case class Phases(
      encoderMs: Double,
      buildMs: Double,
      firstActionMs: Double,
      uptimeAtFirstResultMs: Double,
      steadyMedianMs: Double,
      result: String,
      remainingTypeCount: Int
  )

  private def median(xs: Array[Double]): Double =
    if (xs.isEmpty) Double.NaN
    else { val s = xs.sorted; s(s.length / 2) }

  /** Single-type scan-heavy pipeline: TPC-H Q6 predicate expressed as a typed lambda. */
  private def runQ6(
      spark: SparkSession,
      sf: String,
      steadyIters: Int,
      enc: Encoder[Schemas.Lineitem]
  ): Phases = {
    val encoderMs = 0.0 // single-type workload: no further types to derive

    val from = TpchQueries.Q6_DATE_FROM
    val to = TpchQueries.Q6_DATE_TO

    val tBuild0 = System.nanoTime()
    val ds: Dataset[Schemas.Lineitem] =
      spark.read.parquet(s"${TpchQueries.parquetDir(sf)}/lineitem.parquet").as[Schemas.Lineitem](enc)
    val filtered = ds.filter { l =>
      !l.shipdate.isBefore(from) &&
      l.shipdate.isBefore(to) &&
      l.discount >= BigDecimal("0.05") &&
      l.discount <= BigDecimal("0.07") &&
      l.quantity < BigDecimal(24)
    }
    val buildMs = (System.nanoTime() - tBuild0) / 1e6

    val tAct0 = System.nanoTime()
    val first = filtered.count()
    val firstActionMs = (System.nanoTime() - tAct0) / 1e6
    val uptimeAtFirst = ManagementFactory.getRuntimeMXBean.getUptime.toDouble

    val steady = Array.fill(steadyIters) {
      val t = System.nanoTime()
      filtered.count()
      (System.nanoTime() - t) / 1e6
    }
    Phases(encoderMs, buildMs, firstActionMs, uptimeAtFirst, median(steady), first.toString, 0)
  }

  /** Many-types pipeline: a job that touches all 8 TPC-H tables as typed Datasets. Reflective
   * derivation is per-type (and serialized on a global lock), so this is where a realistic
   * multi-table job diverges from a single-type microbenchmark. */
  private def runWide(
      spark: SparkSession,
      sf: String,
      steadyIters: Int,
      eLineitem: Encoder[Schemas.Lineitem],
      eOrders: Encoder[Schemas.Orders]
  ): Phases = {
    val dir = TpchQueries.parquetDir(sf)

    // The six types this job still needs, all warm. Reflective derivation is per-type and runs
    // under a global lock, so this is the cost that grows with the table count of a real job.
    val tEnc0 = System.nanoTime()
    val eCustomer = ExpressionEncoder[Schemas.Customer]()
    val ePart = ExpressionEncoder[Schemas.Part]()
    val eSupplier = ExpressionEncoder[Schemas.Supplier]()
    val ePartsupp = ExpressionEncoder[Schemas.PartSupp]()
    val eNation = ExpressionEncoder[Schemas.Nation]()
    val eRegion = ExpressionEncoder[Schemas.Region]()
    val encoderMs = (System.nanoTime() - tEnc0) / 1e6

    val tBuild0 = System.nanoTime()
    val counts: Seq[() => Long] = Seq(
      () => spark.read.parquet(s"$dir/lineitem.parquet").as(eLineitem).filter(_.quantity > BigDecimal(1)).count(),
      () => spark.read.parquet(s"$dir/orders.parquet").as(eOrders).filter(_.orderstatus != "X").count(),
      () => spark.read.parquet(s"$dir/customer.parquet").as(eCustomer).filter(_.acctbal > BigDecimal(-1)).count(),
      () => spark.read.parquet(s"$dir/part.parquet").as(ePart).filter(_.size > 0).count(),
      () => spark.read.parquet(s"$dir/supplier.parquet").as(eSupplier).filter(_.nationkey >= 0L).count(),
      () => spark.read.parquet(s"$dir/partsupp.parquet").as(ePartsupp).filter(_.availqty >= 0).count(),
      () => spark.read.parquet(s"$dir/nation.parquet").as(eNation).filter(_.nationkey >= 0L).count(),
      () => spark.read.parquet(s"$dir/region.parquet").as(eRegion).filter(_.regionkey >= 0L).count()
    )
    val buildMs = (System.nanoTime() - tBuild0) / 1e6

    val tAct0 = System.nanoTime()
    val total = counts.map(_.apply()).sum
    val firstActionMs = (System.nanoTime() - tAct0) / 1e6
    val uptimeAtFirst = ManagementFactory.getRuntimeMXBean.getUptime.toDouble

    val steady = Array.fill(steadyIters) {
      val t = System.nanoTime()
      counts.foreach(_.apply())
      (System.nanoTime() - t) / 1e6
    }
    Phases(encoderMs, buildMs, firstActionMs, uptimeAtFirst, median(steady), total.toString, 6)
  }
}
