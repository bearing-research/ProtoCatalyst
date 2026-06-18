package protocatalyst.truffle.backend.bench

/** The five benchmark-slice TPC-H queries (Q1/Q3/Q5/Q6/Q10), as SQL over the parquet tables'
  * (unprefixed) column names. Shared by the steady-state harness (`TpchBench`), the cold-start driver
  * (`TpchNativeDriver`), and — by copy, to stay test-self-contained — the parity/oracle specs. Revenue
  * is computed in double (`CAST … AS DOUBLE`) to match the Spark oracle and sidestep wide-decimal
  * overflow; results are Spark-validated in `TpchSparkOracleSpec`. */
object Tpch:

  final case class Query(name: String, sql: String, tables: Seq[String])

  private val revenue =
    "SUM(CAST(l.extendedprice AS DOUBLE) * (1 - CAST(l.discount AS DOUBLE))) AS revenue"

  val queries: Vector[Query] = Vector(
    Query(
      "Q1",
      """SELECT returnflag, linestatus, COUNT(*) AS cnt,
        |       SUM(CAST(quantity AS DOUBLE)) AS sum_qty,
        |       SUM(CAST(extendedprice AS DOUBLE)) AS sum_base_price
        |FROM lineitem
        |GROUP BY returnflag, linestatus
        |ORDER BY returnflag, linestatus""".stripMargin,
      Seq("lineitem")
    ),
    Query(
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
    Query(
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
    Query(
      "Q6",
      """SELECT SUM(CAST(extendedprice AS DOUBLE) * CAST(discount AS DOUBLE)) AS revenue
        |FROM lineitem
        |WHERE shipdate >= DATE '1994-01-01' AND shipdate < DATE '1995-01-01'
        |  AND discount BETWEEN 0.05 AND 0.07 AND quantity < 24""".stripMargin,
      Seq("lineitem")
    ),
    Query(
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

  def byName(name: String): Query =
    queries.find(_.name.equalsIgnoreCase(name)).getOrElse(sys.error(s"unknown query: $name"))
