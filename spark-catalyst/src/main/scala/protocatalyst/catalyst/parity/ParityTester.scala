package protocatalyst.catalyst.parity

import io.circe.Json
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import protocatalyst.catalyst.json.ArtifactParser

/** Tests parity between ProtoCatalyst's SQL parsing and Spark's SQL parsing.
  *
  * This compares:
  * 1. ProtoCatalyst's parsed SQL (from JSON) converted to Spark LogicalPlan
  * 2. Spark's own parsed LogicalPlan from the same SQL
  */
object ParityTester {

  case class ParityResult(
      sql: String,
      sparkPlan: LogicalPlan,
      protoPlan: LogicalPlan,
      matches: Boolean,
      differences: Seq[String]
  ) {
    def summary: String = {
      if (matches) {
        s"PASS: Plans match for: $sql"
      } else {
        s"FAIL: Plans differ for: $sql\n${differences.map("  - " + _).mkString("\n")}"
      }
    }

    def planTrees: String = {
      s"""SQL: $sql
         |
         |Spark plan:
         |${sparkPlan.treeString}
         |
         |ProtoCatalyst plan:
         |${protoPlan.treeString}
         |""".stripMargin
    }
  }

  /** Test parity for a SQL statement and its ProtoCatalyst JSON representation.
    *
    * @param sql The SQL statement
    * @param protoCatalystJson JSON string containing the ProtoCatalyst CompiledArtifact
    * @param spark The SparkSession to use for parsing SQL
    * @return ParityResult with comparison details
    */
  def testParity(sql: String, protoCatalystJson: String)(implicit spark: SparkSession): ParityResult = {
    // Parse with Spark's parser
    val sparkPlan = spark.sessionState.sqlParser.parsePlan(sql)

    // Parse ProtoCatalyst JSON and convert to Spark LogicalPlan
    val protoPlan = ArtifactParser.parsePlanFromJsonString(protoCatalystJson) match {
      case Right(plan) => plan
      case Left(err) => throw new RuntimeException(s"Failed to parse ProtoCatalyst JSON: $err")
    }

    // Compare the plans
    val comparison = PlanComparator.compare(sparkPlan, protoPlan)

    ParityResult(
      sql = sql,
      sparkPlan = sparkPlan,
      protoPlan = protoPlan,
      matches = comparison.isEquivalent,
      differences = comparison.differences
    )
  }

  /** Test parity using raw artifact bytes (with PCAT header). */
  def testParityFromBytes(sql: String, artifactBytes: Array[Byte])(implicit spark: SparkSession): ParityResult = {
    val sparkPlan = spark.sessionState.sqlParser.parsePlan(sql)

    val protoPlan = ArtifactParser.parsePlan(artifactBytes) match {
      case Right(plan) => plan
      case Left(err) => throw new RuntimeException(s"Failed to parse ProtoCatalyst artifact: $err")
    }

    val comparison = PlanComparator.compare(sparkPlan, protoPlan)

    ParityResult(
      sql = sql,
      sparkPlan = sparkPlan,
      protoPlan = protoPlan,
      matches = comparison.isEquivalent,
      differences = comparison.differences
    )
  }

  /** Test parity using pre-parsed plan JSON. */
  def testParityFromPlanJson(sql: String, planJson: Json)(implicit spark: SparkSession): ParityResult = {
    val sparkPlan = spark.sessionState.sqlParser.parsePlan(sql)

    val protoPlan = ArtifactParser.parseRawPlan(planJson) match {
      case Right(plan) => plan
      case Left(err) => throw new RuntimeException(s"Failed to parse plan JSON: $err")
    }

    val comparison = PlanComparator.compare(sparkPlan, protoPlan)

    ParityResult(
      sql = sql,
      sparkPlan = sparkPlan,
      protoPlan = protoPlan,
      matches = comparison.isEquivalent,
      differences = comparison.differences
    )
  }

  /** Run a batch of parity tests. */
  def runBatch(testCases: Seq[(String, String)])(implicit spark: SparkSession): BatchResult = {
    val results = testCases.map { case (sql, json) => testParity(sql, json) }
    val passed = results.count(_.matches)
    val failed = results.count(!_.matches)

    BatchResult(
      total = results.size,
      passed = passed,
      failed = failed,
      results = results
    )
  }

  case class BatchResult(
      total: Int,
      passed: Int,
      failed: Int,
      results: Seq[ParityResult]
  ) {
    def summary: String = {
      val header = s"Parity Test Results: $passed/$total passed ($failed failed)"
      val details = results.map(_.summary).mkString("\n")
      s"$header\n\n$details"
    }

    def failedResults: Seq[ParityResult] = results.filter(!_.matches)
    def passedResults: Seq[ParityResult] = results.filter(_.matches)
  }
}
