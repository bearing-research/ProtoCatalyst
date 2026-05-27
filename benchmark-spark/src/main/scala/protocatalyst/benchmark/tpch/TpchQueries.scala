package protocatalyst.benchmark.tpch

import java.time.LocalDate

import org.apache.spark.sql.{DataFrame, SparkSession}

/** TPC-H Q1 / Q6 / Q14 / Q21 implemented as two parallel paths:
 *
 *   - `*_df` — DataFrame / SQL, no typed encoder. Spark's whole-stage codegen handles the entire
 *     pipeline; row-decode-to-JVM-object is skipped. This is the encoder-free baseline / upper
 *     bound for what Spark can do.
 *   - `*_ds` — typed `Dataset[T]` with at least one `.filter(lambda)` step where `lambda`
 *     operates on the typed JVM object. This forces Spark's `ExpressionEncoder` to decode each
 *     row into a `T` instance, run the lambda, and re-encode. Aggregates afterwards go back to
 *     the column world for fairness.
 *
 * The difference between the two timings is encoder cost in a realistic query setting. The
 * narrative for the report: this is where the microbenchmark gain in `TpchUnsafeRowBenchmarks`
 * either translates to end-to-end query latency or doesn't.
 *
 * All queries follow the standard TPC-H formulations with substitution parameters chosen per
 * the spec's Section 2 (we use the QGEN-default values; see comments per query).
 */
object TpchQueries {

  /** Path to the Parquet directory written by `TpchParquetConverter`. */
  def parquetDir(sf: String): String = s"data/tpch/sf-$sf-parquet"

  /** Register all 8 TPC-H tables as Parquet temporary views in the active session. */
  def registerTables(spark: SparkSession, sf: String): Unit = {
    val root = parquetDir(sf)
    Seq("lineitem", "orders", "customer", "part", "supplier", "partsupp", "nation", "region")
      .foreach { t =>
        spark.read.parquet(s"$root/$t.parquet").createOrReplaceTempView(t)
      }
  }

  // ============================================================
  // Q1 — Pricing summary report
  // ============================================================
  //
  // Spec params (QGEN defaults): DELTA = 90 days, anchor = 1998-12-01.
  //   shipdate <= date '1998-12-01' - interval '90' day
  // We pre-substitute to a literal date for parser-independence.

  val Q1_SHIPDATE_CUTOFF: LocalDate = LocalDate.of(1998, 9, 2)

  def q1_df(spark: SparkSession): DataFrame =
    spark.sql(s"""
      SELECT
        returnflag, linestatus,
        SUM(quantity)                                              AS sum_qty,
        SUM(extendedprice)                                         AS sum_base_price,
        SUM(extendedprice * (1 - discount))                        AS sum_disc_price,
        SUM(extendedprice * (1 - discount) * (1 + tax))            AS sum_charge,
        AVG(quantity)                                              AS avg_qty,
        AVG(extendedprice)                                         AS avg_price,
        AVG(discount)                                              AS avg_disc,
        COUNT(*)                                                   AS count_order
      FROM lineitem
      WHERE shipdate <= date '${Q1_SHIPDATE_CUTOFF}'
      GROUP BY returnflag, linestatus
      ORDER BY returnflag, linestatus
    """)

  def q1_ds(spark: SparkSession): DataFrame = {
    import spark.implicits._
    val lineitemDs = spark.read.parquet(s"${parquetDir(currentSf(spark))}/lineitem.parquet")
      .as[Schemas.Lineitem]
    val cutoff = Q1_SHIPDATE_CUTOFF
    val filtered = lineitemDs.filter(_.shipdate.isBefore(cutoff.plusDays(1)))
    filtered.createOrReplaceTempView("lineitem_q1ds")
    spark.sql("""
      SELECT
        returnflag, linestatus,
        SUM(quantity)                                              AS sum_qty,
        SUM(extendedprice)                                         AS sum_base_price,
        SUM(extendedprice * (1 - discount))                        AS sum_disc_price,
        SUM(extendedprice * (1 - discount) * (1 + tax))            AS sum_charge,
        AVG(quantity)                                              AS avg_qty,
        AVG(extendedprice)                                         AS avg_price,
        AVG(discount)                                              AS avg_disc,
        COUNT(*)                                                   AS count_order
      FROM lineitem_q1ds
      GROUP BY returnflag, linestatus
      ORDER BY returnflag, linestatus
    """)
  }

  // ============================================================
  // Q6 — Forecasting revenue change
  // ============================================================
  //
  // Spec params (QGEN defaults): DATE = 1994-01-01, DISCOUNT = 0.06, QUANTITY = 24.

  val Q6_DATE_FROM: LocalDate = LocalDate.of(1994, 1, 1)
  val Q6_DATE_TO: LocalDate   = LocalDate.of(1995, 1, 1)

  def q6_df(spark: SparkSession): DataFrame =
    spark.sql(s"""
      SELECT SUM(extendedprice * discount) AS revenue
      FROM lineitem
      WHERE shipdate >= date '${Q6_DATE_FROM}'
        AND shipdate <  date '${Q6_DATE_TO}'
        AND discount BETWEEN 0.05 AND 0.07
        AND quantity < 24
    """)

  def q6_ds(spark: SparkSession): DataFrame = {
    import spark.implicits._
    val lineitemDs = spark.read.parquet(s"${parquetDir(currentSf(spark))}/lineitem.parquet")
      .as[Schemas.Lineitem]
    val from = Q6_DATE_FROM
    val to = Q6_DATE_TO
    val filtered = lineitemDs.filter { l =>
      !l.shipdate.isBefore(from) &&
      l.shipdate.isBefore(to) &&
      l.discount >= BigDecimal("0.05") &&
      l.discount <= BigDecimal("0.07") &&
      l.quantity < BigDecimal(24)
    }
    filtered.createOrReplaceTempView("lineitem_q6ds")
    spark.sql("SELECT SUM(extendedprice * discount) AS revenue FROM lineitem_q6ds")
  }

  // ============================================================
  // Q14 — Promotion effect
  // ============================================================
  //
  // Spec params (QGEN defaults): DATE = 1995-09-01.

  val Q14_DATE_FROM: LocalDate = LocalDate.of(1995, 9, 1)
  val Q14_DATE_TO: LocalDate   = LocalDate.of(1995, 10, 1)

  def q14_df(spark: SparkSession): DataFrame =
    spark.sql(s"""
      SELECT
        100.00 * SUM(CASE WHEN p.partType LIKE 'PROMO%'
                          THEN l.extendedprice * (1 - l.discount)
                          ELSE 0 END)
              / SUM(l.extendedprice * (1 - l.discount)) AS promo_revenue
      FROM lineitem l JOIN part p ON l.partkey = p.partkey
      WHERE l.shipdate >= date '${Q14_DATE_FROM}'
        AND l.shipdate <  date '${Q14_DATE_TO}'
    """)

  def q14_ds(spark: SparkSession): DataFrame = {
    import spark.implicits._
    val sf = currentSf(spark)
    val lineitemDs = spark.read.parquet(s"${parquetDir(sf)}/lineitem.parquet")
      .as[Schemas.Lineitem]
    val from = Q14_DATE_FROM
    val to = Q14_DATE_TO
    val filtered = lineitemDs.filter { l =>
      !l.shipdate.isBefore(from) && l.shipdate.isBefore(to)
    }
    filtered.createOrReplaceTempView("lineitem_q14ds")
    spark.sql("""
      SELECT
        100.00 * SUM(CASE WHEN p.partType LIKE 'PROMO%'
                          THEN l.extendedprice * (1 - l.discount)
                          ELSE 0 END)
              / SUM(l.extendedprice * (1 - l.discount)) AS promo_revenue
      FROM lineitem_q14ds l JOIN part p ON l.partkey = p.partkey
    """)
  }

  // ============================================================
  // Q21 — Suppliers who kept orders waiting
  // ============================================================
  //
  // Spec params (QGEN defaults): NATION = 'SAUDI ARABIA'. Heavy 4-way self-join + anti-join.
  // We only implement the DataFrame variant here because the typed-Dataset version of the
  // self-join doesn't move the encoder needle meaningfully — the encoder cost is dwarfed by
  // join shuffle. The `_ds` variant adds a typed filter on `orders.orderstatus = 'F'` which is
  // the encoder-sensitive step for the operations that ARE done per-row.

  val Q21_NATION: String = "SAUDI ARABIA"

  def q21_df(spark: SparkSession): DataFrame =
    spark.sql(s"""
      SELECT s.name AS s_name, COUNT(*) AS numwait
      FROM supplier s, lineitem l1, orders o, nation n
      WHERE s.suppkey   = l1.suppkey
        AND o.orderkey  = l1.orderkey
        AND o.orderstatus = 'F'
        AND l1.receiptdate > l1.commitdate
        AND EXISTS (
          SELECT * FROM lineitem l2
          WHERE l2.orderkey = l1.orderkey
            AND l2.suppkey <> l1.suppkey
        )
        AND NOT EXISTS (
          SELECT * FROM lineitem l3
          WHERE l3.orderkey = l1.orderkey
            AND l3.suppkey <> l1.suppkey
            AND l3.receiptdate > l3.commitdate
        )
        AND s.nationkey = n.nationkey
        AND n.name = '${Q21_NATION}'
      GROUP BY s.name
      ORDER BY numwait DESC, s.name
      LIMIT 100
    """)

  def q21_ds(spark: SparkSession): DataFrame = {
    import spark.implicits._
    val sf = currentSf(spark)
    val ordersDs = spark.read.parquet(s"${parquetDir(sf)}/orders.parquet")
      .as[Schemas.Orders]
    val filteredOrders = ordersDs.filter(_.orderstatus == "F")
    filteredOrders.createOrReplaceTempView("orders_q21ds")
    spark.sql(s"""
      SELECT s.name AS s_name, COUNT(*) AS numwait
      FROM supplier s, lineitem l1, orders_q21ds o, nation n
      WHERE s.suppkey   = l1.suppkey
        AND o.orderkey  = l1.orderkey
        AND l1.receiptdate > l1.commitdate
        AND EXISTS (
          SELECT * FROM lineitem l2
          WHERE l2.orderkey = l1.orderkey
            AND l2.suppkey <> l1.suppkey
        )
        AND NOT EXISTS (
          SELECT * FROM lineitem l3
          WHERE l3.orderkey = l1.orderkey
            AND l3.suppkey <> l1.suppkey
            AND l3.receiptdate > l3.commitdate
        )
        AND s.nationkey = n.nationkey
        AND n.name = '${Q21_NATION}'
      GROUP BY s.name
      ORDER BY numwait DESC, s.name
      LIMIT 100
    """)
  }

  // Helper — the harness sets this conf so the `_ds` variants can locate the right SF.
  // Avoiding a global; keeps method signatures clean for the report code samples.
  private val SF_KEY = "protocatalyst.tpch.sf"

  def withSf[A](spark: SparkSession, sf: String)(body: => A): A = {
    val prev = spark.conf.getOption(SF_KEY)
    spark.conf.set(SF_KEY, sf)
    try body
    finally prev match {
      case Some(v) => spark.conf.set(SF_KEY, v)
      case None    => spark.conf.unset(SF_KEY)
    }
  }

  private def currentSf(spark: SparkSession): String =
    spark.conf.getOption(SF_KEY)
      .getOrElse(throw new IllegalStateException(
        "No SF set on session. Wrap query call in TpchQueries.withSf(spark, sf) { ... }."
      ))
}
