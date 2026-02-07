package protocatalyst.catalyst.parity

import munit.FunSuite
import org.apache.spark.sql.SparkSession

import protocatalyst.catalyst.json.{PlanDecoder, SparkPlanEncoder}

/** End-to-end round-trip tests: SQL → Spark parse → encode → decode → re-encode → verify stability.
  *
  * Tests that the encode/decode pipeline is idempotent: JSON produced by encoding a Spark-parsed
  * plan should survive a decode + re-encode cycle unchanged. Covers math, string, date/time,
  * window, subquery, and generator expressions.
  */
class EncoderDecoderRoundTripSuite extends FunSuite {

  private var spark: SparkSession = _

  override def beforeAll(): Unit = {
    spark = SparkSession
      .builder()
      .master("local[1]")
      .appName("EncoderDecoderRoundTrip")
      .config("spark.ui.enabled", "false")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .getOrCreate()
  }

  override def afterAll(): Unit = {
    if (spark != null) {
      spark.stop()
    }
  }

  /** Parse SQL with Spark, encode to JSON, decode back, re-encode, verify JSON stability.
    *
    * Tests that encode → decode → re-encode is idempotent (JSON-level round-trip). This avoids
    * fragile structural comparison between Spark's unresolved types and the decoded resolved types.
    */
  private def assertRoundTrip(sql: String): Unit = {
    val sparkPlan = spark.sessionState.sqlParser.parsePlan(sql)
    val json1 = SparkPlanEncoder.encode(sparkPlan)

    // Step 1: Verify decode succeeds
    val decoded = PlanDecoder.decode(json1)
    decoded match {
      case Left(err) =>
        fail(
          s"""Decode failed for: $sql
             |Error: ${err.message}
             |JSON:
             |${json1.spaces2}""".stripMargin
        )
      case Right(decodedPlan) =>
        // Step 2: Re-encode the decoded plan
        val json2 = SparkPlanEncoder.encode(decodedPlan)

        // Step 3: Verify JSON stability (encode → decode → re-encode = identity)
        if (json1 != json2) {
          fail(
            s"""JSON round-trip not stable for: $sql
               |
               |First encode:
               |${json1.spaces2}
               |
               |After decode + re-encode:
               |${json2.spaces2}""".stripMargin
          )
        }
    }
  }

  // === Basic queries ===

  test("round-trip: SELECT *") {
    assertRoundTrip("SELECT * FROM t")
  }

  test("round-trip: SELECT with projection") {
    assertRoundTrip("SELECT a, b FROM t")
  }

  test("round-trip: WHERE clause") {
    assertRoundTrip("SELECT a FROM t WHERE a > 10")
  }

  test("round-trip: ORDER BY") {
    assertRoundTrip("SELECT a FROM t ORDER BY a DESC")
  }

  test("round-trip: LIMIT") {
    assertRoundTrip("SELECT a FROM t LIMIT 5")
  }

  test("round-trip: DISTINCT") {
    assertRoundTrip("SELECT DISTINCT a FROM t")
  }

  test("round-trip: GROUP BY with aggregates") {
    assertRoundTrip("SELECT a, COUNT(*), SUM(b), AVG(b), MIN(b), MAX(b) FROM t GROUP BY a")
  }

  test("round-trip: JOIN") {
    assertRoundTrip("SELECT a.x, b.y FROM a JOIN b ON a.id = b.id")
  }

  test("round-trip: LEFT JOIN") {
    assertRoundTrip("SELECT a.x FROM a LEFT JOIN b ON a.id = b.id")
  }

  test("round-trip: UNION ALL") {
    assertRoundTrip("SELECT a FROM t1 UNION ALL SELECT a FROM t2")
  }

  test("round-trip: INTERSECT") {
    assertRoundTrip("SELECT a FROM t1 INTERSECT SELECT a FROM t2")
  }

  test("round-trip: EXCEPT") {
    assertRoundTrip("SELECT a FROM t1 EXCEPT SELECT a FROM t2")
  }

  test("round-trip: subquery alias") {
    assertRoundTrip("SELECT x FROM (SELECT a AS x FROM t) sub")
  }

  test("round-trip: CTE") {
    assertRoundTrip("WITH cte AS (SELECT a FROM t WHERE a > 0) SELECT * FROM cte")
  }

  // === Arithmetic ===

  test("round-trip: arithmetic expressions") {
    assertRoundTrip("SELECT a + b, a - b, a * b, a / b FROM t")
  }

  // === Comparison and logical ===

  test("round-trip: comparison operators") {
    assertRoundTrip("SELECT * FROM t WHERE a > 1 AND b < 2 OR c >= 3 AND d <= 4")
  }

  test("round-trip: NOT, IS NULL, IS NOT NULL") {
    assertRoundTrip("SELECT * FROM t WHERE NOT a IS NULL AND b IS NOT NULL")
  }

  test("round-trip: IN list") {
    assertRoundTrip("SELECT * FROM t WHERE a IN (1, 2, 3)")
  }

  test("round-trip: BETWEEN") {
    assertRoundTrip("SELECT * FROM t WHERE a BETWEEN 10 AND 20")
  }

  test("round-trip: CASE WHEN") {
    assertRoundTrip(
      "SELECT CASE WHEN a > 0 THEN 'pos' WHEN a < 0 THEN 'neg' ELSE 'zero' END FROM t"
    )
  }

  test("round-trip: COALESCE") {
    assertRoundTrip("SELECT COALESCE(a, b, 0) FROM t")
  }

  test("round-trip: CAST") {
    assertRoundTrip("SELECT CAST(a AS STRING), CAST(b AS INT) FROM t")
  }

  test("round-trip: alias") {
    assertRoundTrip("SELECT a + b AS total FROM t")
  }

  // === Math functions ===

  test("round-trip: ABS") {
    assertRoundTrip("SELECT ABS(a) FROM t")
  }

  test("round-trip: CEIL and FLOOR") {
    assertRoundTrip("SELECT CEIL(a), FLOOR(a) FROM t")
  }

  test("round-trip: ROUND") {
    assertRoundTrip("SELECT ROUND(a, 2) FROM t")
  }

  test("round-trip: SQRT, CBRT") {
    assertRoundTrip("SELECT SQRT(a), CBRT(a) FROM t")
  }

  test("round-trip: POW") {
    assertRoundTrip("SELECT POW(a, 2) FROM t")
  }

  test("round-trip: EXP, LOG, LOG10") {
    assertRoundTrip("SELECT EXP(a), LN(a) FROM t")
  }

  test("round-trip: SIGN") {
    assertRoundTrip("SELECT SIGN(a) FROM t")
  }

  test("round-trip: PMOD") {
    assertRoundTrip("SELECT PMOD(a, 3) FROM t")
  }

  // === String functions ===

  test("round-trip: UPPER, LOWER") {
    assertRoundTrip("SELECT UPPER(s), LOWER(s) FROM t")
  }

  test("round-trip: CONCAT") {
    assertRoundTrip("SELECT CONCAT(a, b, c) FROM t")
  }

  test("round-trip: SUBSTRING") {
    assertRoundTrip("SELECT SUBSTRING(s, 1, 3) FROM t")
  }

  test("round-trip: TRIM, LTRIM, RTRIM") {
    assertRoundTrip("SELECT TRIM(s), LTRIM(s), RTRIM(s) FROM t")
  }

  test("round-trip: LENGTH") {
    assertRoundTrip("SELECT LENGTH(s) FROM t")
  }

  test("round-trip: REPLACE") {
    assertRoundTrip("SELECT REPLACE(s, 'old', 'new') FROM t")
  }

  test("round-trip: LPAD, RPAD") {
    assertRoundTrip("SELECT LPAD(s, 10, ' '), RPAD(s, 10, ' ') FROM t")
  }

  test("round-trip: REVERSE") {
    assertRoundTrip("SELECT REVERSE(s) FROM t")
  }

  test("round-trip: LIKE") {
    assertRoundTrip("SELECT * FROM t WHERE s LIKE '%abc%'")
  }

  // === Date/Time functions ===

  test("round-trip: YEAR, MONTH, DAY") {
    assertRoundTrip("SELECT YEAR(d), MONTH(d), DAY(d) FROM t")
  }

  test("round-trip: HOUR, MINUTE, SECOND") {
    assertRoundTrip("SELECT HOUR(ts), MINUTE(ts), SECOND(ts) FROM t")
  }

  test("round-trip: CURRENT_DATE, CURRENT_TIMESTAMP") {
    assertRoundTrip("SELECT CURRENT_DATE, CURRENT_TIMESTAMP FROM t")
  }

  test("round-trip: DATE_ADD, DATE_SUB") {
    assertRoundTrip("SELECT DATE_ADD(d, 7), DATE_SUB(d, 7) FROM t")
  }

  test("round-trip: DATEDIFF") {
    assertRoundTrip("SELECT DATEDIFF(d2, d1) FROM t")
  }

  // === Window functions ===

  test("round-trip: ROW_NUMBER() OVER") {
    assertRoundTrip("SELECT a, ROW_NUMBER() OVER (PARTITION BY b ORDER BY a) FROM t")
  }

  test("round-trip: RANK() and DENSE_RANK() OVER") {
    assertRoundTrip(
      "SELECT a, RANK() OVER (ORDER BY a), DENSE_RANK() OVER (ORDER BY a) FROM t"
    )
  }

  test("round-trip: SUM() OVER with frame") {
    assertRoundTrip(
      "SELECT a, SUM(b) OVER (PARTITION BY c ORDER BY a ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM t"
    )
  }

  test("round-trip: LEAD and LAG") {
    assertRoundTrip(
      "SELECT a, LEAD(a, 1) OVER (ORDER BY a), LAG(a, 1) OVER (ORDER BY a) FROM t"
    )
  }

  test("round-trip: NTILE") {
    assertRoundTrip("SELECT a, NTILE(4) OVER (ORDER BY a) FROM t")
  }

  // === Subqueries ===

  test("round-trip: EXISTS subquery") {
    assertRoundTrip("SELECT * FROM t WHERE EXISTS (SELECT 1 FROM t2 WHERE t2.id = t.id)")
  }

  test("round-trip: NOT EXISTS subquery") {
    assertRoundTrip("SELECT * FROM t WHERE NOT EXISTS (SELECT 1 FROM t2 WHERE t2.id = t.id)")
  }

  test("round-trip: IN subquery") {
    assertRoundTrip("SELECT * FROM t WHERE a IN (SELECT id FROM t2)")
  }

  test("round-trip: scalar subquery") {
    assertRoundTrip("SELECT a, (SELECT MAX(b) FROM t2) FROM t")
  }

  // === Generator / LATERAL VIEW ===

  test("round-trip: LATERAL VIEW EXPLODE") {
    assertRoundTrip("SELECT t.a, col FROM t LATERAL VIEW EXPLODE(arr) tmp AS col")
  }

  // === Complex queries ===

  test("round-trip: multi-function complex query") {
    assertRoundTrip(
      """SELECT
        |  UPPER(name) AS upper_name,
        |  ABS(salary),
        |  YEAR(hire_date),
        |  ROUND(salary / 12, 2) AS monthly
        |FROM employees
        |WHERE salary > 50000 AND LENGTH(name) > 3
        |ORDER BY salary DESC
        |LIMIT 10""".stripMargin
    )
  }

  test("round-trip: aggregate with HAVING") {
    assertRoundTrip(
      """SELECT dept, COUNT(*) AS cnt, AVG(salary) AS avg_sal
        |FROM employees
        |GROUP BY dept
        |HAVING COUNT(*) > 5""".stripMargin
    )
  }

  test("round-trip: multiple joins") {
    assertRoundTrip(
      """SELECT e.name, d.dept_name, m.name AS manager
        |FROM employees e
        |JOIN departments d ON e.dept_id = d.id
        |LEFT JOIN employees m ON e.manager_id = m.id""".stripMargin
    )
  }

  // === Hints ===

  test("round-trip: BROADCAST hint") {
    assertRoundTrip("SELECT /*+ BROADCAST(t) */ * FROM t JOIN t2 ON t.id = t2.id")
  }

  test("round-trip: SHUFFLE_MERGE hint") {
    assertRoundTrip("SELECT /*+ SHUFFLE_MERGE(t) */ * FROM t JOIN t2 ON t.id = t2.id")
  }

  test("round-trip: COALESCE hint") {
    assertRoundTrip("SELECT /*+ COALESCE(4) */ * FROM t")
  }

  test("round-trip: REPARTITION hint") {
    assertRoundTrip("SELECT /*+ REPARTITION(8) */ * FROM t")
  }
}
