package protocatalyst.bench.tpch

import java.lang.management.ManagementFactory
import java.time.LocalDate

import org.apache.spark.sql.{Dataset, Encoder, SparkSession}
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder

import protocatalyst.encoder.spark.AgnosticDerivation.{deriveAgnosticEncoder, given}
import protocatalyst.encoder.spark.tpch.Schemas

/** Replacement half of the real-query benchmark: the **same short-lived Spark driver, same data,
  * same queries** as `benchmark-spark/.../QueryColdStartBaseline`, but every `Encoder[T]` comes from
  * compile-time derivation (`deriveAgnosticEncoder`) instead of `ScalaReflection.encoderFor`.
  *
  * This runs from a **Scala 3** process against stock Spark 4.1.2, which is only possible with
  * `spark-reflection-patch` on the classpath — it clears both walls: `ScalaReflection`'s eager
  * runtime universe (REPORT §3) and `SparkSession.builder()`'s reflective companion lookup
  * (`SparkSessionWallSpec`). The driver script puts it there.
  *
  * The two halves differ in exactly one thing — where the encoder comes from. Everything downstream
  * (`ExpressionEncoder`, ser/deser codegen, Catalyst, execution) is Spark's own, unmodified, so the
  * steady-state rows/sec is expected to be at parity and the difference should show up in the
  * one-off costs. Reporting both is the point.
  *
  * Usage: {{{ java -cp <benchmarks runtime cp> protocatalyst.bench.tpch.QueryColdStartProto
  *            <sf> <q6|wide> <steadyIters> }}}
  */
object QueryColdStartProto:

  private val Q6DateFrom: LocalDate = LocalDate.of(1994, 1, 1)
  private val Q6DateTo: LocalDate   = LocalDate.of(1995, 1, 1)

  def main(args: Array[String]): Unit =
    val sf          = if args.length > 0 then args(0) else "1"
    val workload    = if args.length > 1 then args(1) else "q6"
    val steadyIters = if args.length > 2 then args(2).toInt else 10

    val jvmToMainMs = ManagementFactory.getRuntimeMXBean.getUptime.toDouble

    // (1) COLD encoder, measured before anything else touches Spark — mirrors the baseline's
    // ordering exactly. Here there is no runtime universe to initialize; what is timed is first
    // touch of ExpressionEncoder/AgnosticEncoders plus building the encoder tree the macro emitted.
    val tCold0 = System.nanoTime()
    val encLineitem: Encoder[Schemas.Lineitem] = ExpressionEncoder(deriveAgnosticEncoder[Schemas.Lineitem])
    val encoderColdMs = (System.nanoTime() - tCold0) / 1e6

    val tSession0 = System.nanoTime()
    val spark = SparkSession
      .builder()
      .master("local[*]")
      .appName("query-coldstart-proto")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "8")
      .getOrCreate()
    spark.sparkContext.setLogLevel("WARN")
    val sessionMs = (System.nanoTime() - tSession0) / 1e6

    try
      // (2) First encoder built AFTER the session exists. Measured, but do not read it as a
      // derivation cost: it is dominated by Spark's own one-time init on first encoder use in a
      // live session, which both sides pay identically (~equal in every run to date). The real
      // per-additional-type cost is `encoder_per_additional_type` below.
      val tWarm0 = System.nanoTime()
      val encOrders: Encoder[Schemas.Orders] = ExpressionEncoder(deriveAgnosticEncoder[Schemas.Orders])
      val encoderWarmMs = (System.nanoTime() - tWarm0) / 1e6

      val r = workload match
        case "q6"   => runQ6(spark, sf, steadyIters, encLineitem)
        case "wide" => runWide(spark, sf, steadyIters, encLineitem, encOrders)
        case other  => sys.error(s"unknown workload: $other")

      emit(workload, sf, "jvm_to_main", jvmToMainMs)
      emit(workload, sf, "encoder_cold_first_type", encoderColdMs)
      emit(workload, sf, "session_create", sessionMs)
      emit(workload, sf, "encoder_after_session", encoderWarmMs)
      emit(workload, sf, "encoder_remaining_types", r.encoderMs)
      if r.remainingTypeCount > 0 then
        emit(workload, sf, "encoder_per_additional_type", r.encoderMs / r.remainingTypeCount)
      emit(workload, sf, "query_build", r.buildMs)
      emit(workload, sf, "first_action", r.firstActionMs)
      emit(workload, sf, "total_to_first_result", r.uptimeAtFirstResultMs)
      emit(workload, sf, "steady_median", r.steadyMedianMs)
      System.err.println(
        f"[proto/$workload%s sf=$sf%s] total-to-first-result ${r.uptimeAtFirstResultMs}%.0f ms " +
          f"(encoder cold ${encoderColdMs}%.1f, session ${sessionMs}%.0f, encoder warm " +
          f"${encoderWarmMs}%.1f, rest ${r.encoderMs}%.1f, build ${r.buildMs}%.0f, " +
          f"action ${r.firstActionMs}%.0f); steady median ${r.steadyMedianMs}%.0f ms; result=${r.result}"
      )
    finally spark.stop()

  private def emit(workload: String, sf: String, metric: String, ms: Double): Unit =
    println(f"RESULT,proto,$workload%s,$sf%s,$metric%s,$ms%.3f")

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
    if xs.isEmpty then Double.NaN
    else
      val s = xs.sorted
      s(s.length / 2)

  /** Single-type scan-heavy pipeline: TPC-H Q6 predicate as a typed lambda. Mirrors
    * `QueryColdStartBaseline.runQ6` line for line. */
  private def runQ6(
      spark: SparkSession,
      sf: String,
      steadyIters: Int,
      enc: Encoder[Schemas.Lineitem]
  ): Phases =
    val encoderMs = 0.0 // single-type workload: no further types to derive

    val from = Q6DateFrom
    val to   = Q6DateTo

    val tBuild0 = System.nanoTime()
    val ds: Dataset[Schemas.Lineitem] =
      spark.read.parquet(s"${parquetDir(sf)}/lineitem.parquet").as[Schemas.Lineitem](using enc)
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

    val steady = Array.fill(steadyIters):
      val t = System.nanoTime()
      filtered.count()
      (System.nanoTime() - t) / 1e6

    Phases(encoderMs, buildMs, firstActionMs, uptimeAtFirst, median(steady), first.toString, 0)

  /** Many-types pipeline: all 8 TPC-H tables as typed Datasets. Mirrors
    * `QueryColdStartBaseline.runWide`. */
  private def runWide(
      spark: SparkSession,
      sf: String,
      steadyIters: Int,
      eLineitem: Encoder[Schemas.Lineitem],
      eOrders: Encoder[Schemas.Orders]
  ): Phases =
    val dir = parquetDir(sf)

    // The six types this job still needs, all warm — same set, same order as the baseline.
    val tEnc0 = System.nanoTime()
    val eCustomer = ExpressionEncoder(deriveAgnosticEncoder[Schemas.Customer])
    val ePart     = ExpressionEncoder(deriveAgnosticEncoder[Schemas.Part])
    val eSupplier = ExpressionEncoder(deriveAgnosticEncoder[Schemas.Supplier])
    val ePartSupp = ExpressionEncoder(deriveAgnosticEncoder[Schemas.PartSupp])
    val eNation   = ExpressionEncoder(deriveAgnosticEncoder[Schemas.Nation])
    val eRegion   = ExpressionEncoder(deriveAgnosticEncoder[Schemas.Region])
    val encoderMs = (System.nanoTime() - tEnc0) / 1e6

    val tBuild0 = System.nanoTime()
    val counts: Seq[() => Long] = Seq(
      () => spark.read.parquet(s"$dir/lineitem.parquet").as(using eLineitem).filter(_.quantity > BigDecimal(1)).count(),
      () => spark.read.parquet(s"$dir/orders.parquet").as(using eOrders).filter(_.orderstatus != "X").count(),
      () => spark.read.parquet(s"$dir/customer.parquet").as(using eCustomer).filter(_.acctbal > BigDecimal(-1)).count(),
      () => spark.read.parquet(s"$dir/part.parquet").as(using ePart).filter(_.size > 0).count(),
      () => spark.read.parquet(s"$dir/supplier.parquet").as(using eSupplier).filter(_.nationkey >= 0L).count(),
      () => spark.read.parquet(s"$dir/partsupp.parquet").as(using ePartSupp).filter(_.availqty >= 0).count(),
      () => spark.read.parquet(s"$dir/nation.parquet").as(using eNation).filter(_.nationkey >= 0L).count(),
      () => spark.read.parquet(s"$dir/region.parquet").as(using eRegion).filter(_.regionkey >= 0L).count()
    )
    val buildMs = (System.nanoTime() - tBuild0) / 1e6

    val tAct0 = System.nanoTime()
    val total = counts.map(_.apply()).sum
    val firstActionMs = (System.nanoTime() - tAct0) / 1e6
    val uptimeAtFirst = ManagementFactory.getRuntimeMXBean.getUptime.toDouble

    val steady = Array.fill(steadyIters):
      val t = System.nanoTime()
      counts.foreach(_.apply())
      (System.nanoTime() - t) / 1e6

    Phases(encoderMs, buildMs, firstActionMs, uptimeAtFirst, median(steady), total.toString, 6)

  private def parquetDir(sf: String): String = s"data/tpch/sf-$sf-parquet"
