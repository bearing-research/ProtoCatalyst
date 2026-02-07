package protocatalyst.e2e

import java.nio.file.{Files, Path, Paths}

import protocatalyst.dsl._
import protocatalyst.query.CompiledQuery

/** Generates PCAT artifacts from compile-time DSL and SQL queries for end-to-end testing.
  *
  * Each test case uses a literal `quote {}` or `CompiledQuery.sqlOptimized` call — inline macros
  * require compile-time-known expressions, so these cannot be generated in a loop.
  *
  * Run: `sbt "query/Test/runMain protocatalyst.e2e.E2EArtifactGenerator [output-dir]"`
  *
  * The generated .pcat files are loaded by `E2EIntegrationSuite` in the spark-catalyst module and
  * executed against real Spark DataFrames.
  */
object E2EArtifactGenerator:

  // === DSL path: quote { } macro ===

  private def dslTableScan(): CompiledQuery[QuoteUser] =
    QuoteMacro.quote(Table[QuoteUser]("users"))

  private def dslFilterGt(): CompiledQuery[QuoteUser] =
    QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.age > 25)
    }

  private def dslFilterLimit(): CompiledQuery[QuoteUser] =
    QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.salary >= 70000.0).limit(2)
    }

  private def dslDistinct(): CompiledQuery[QuoteUser] =
    QuoteMacro.quote {
      Table[QuoteUser]("users").distinct
    }

  private def dslStringEq(): CompiledQuery[QuoteUser] =
    QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.name === "Alice")
    }

  // === SQL path: CompiledQuery.sqlOptimized ===

  private def sqlSelectWhere(): CompiledQuery[QuoteUser] =
    CompiledQuery.sqlOptimized[QuoteUser]("SELECT name, age FROM users WHERE age > 25")

  private def sqlOrderLimit(): CompiledQuery[QuoteUser] =
    CompiledQuery.sqlOptimized[QuoteUser](
      "SELECT name, salary FROM users ORDER BY salary DESC LIMIT 3"
    )

  private def sqlArithmetic(): CompiledQuery[QuoteUser] =
    CompiledQuery.sqlOptimized[QuoteUser](
      "SELECT name, salary + 1000 FROM users WHERE age >= 28"
    )

  // === Test case registry ===

  case class E2ETestCase(
      id: String,
      description: String,
      source: String,
      compile: () => CompiledQuery[QuoteUser]
  )

  private val testCases: Vector[E2ETestCase] = Vector(
    E2ETestCase(
      "dsl_table_scan",
      "DSL: Table[QuoteUser](users) — full table scan",
      "dsl",
      () => dslTableScan()
    ),
    E2ETestCase(
      "dsl_filter_gt",
      "DSL: Table[QuoteUser](users).filter(_.age > 25)",
      "dsl",
      () => dslFilterGt()
    ),
    E2ETestCase(
      "dsl_filter_limit",
      "DSL: Table[QuoteUser](users).filter(_.salary >= 70000.0).limit(2)",
      "dsl",
      () => dslFilterLimit()
    ),
    E2ETestCase(
      "dsl_distinct",
      "DSL: Table[QuoteUser](users).distinct",
      "dsl",
      () => dslDistinct()
    ),
    E2ETestCase(
      "dsl_string_eq",
      "DSL: Table[QuoteUser](users).filter(_.name === \"Alice\")",
      "dsl",
      () => dslStringEq()
    ),
    E2ETestCase(
      "sql_select_where",
      "SQL: SELECT name, age FROM users WHERE age > 25",
      "sql",
      () => sqlSelectWhere()
    ),
    E2ETestCase(
      "sql_order_limit",
      "SQL: SELECT name, salary FROM users ORDER BY salary DESC LIMIT 3",
      "sql",
      () => sqlOrderLimit()
    ),
    E2ETestCase(
      "sql_arithmetic",
      "SQL: SELECT name, salary + 1000 FROM users WHERE age >= 28",
      "sql",
      () => sqlArithmetic()
    )
  )

  // === Generation ===

  private def writeMeta(outputDir: Path, id: String, description: String, source: String): Unit =
    val meta = s"""{
                   |  "id": "$id",
                   |  "description": "${description.replace("\"", "\\\"")}",
                   |  "source": "$source"
                   |}""".stripMargin
    Files.writeString(outputDir.resolve(s"$id.meta.json"), meta)

  def generateAll(outputDir: Path): Unit =
    Files.createDirectories(outputDir)

    var passed = 0
    var failed = 0

    for tc <- testCases do
      try
        val query = tc.compile()
        val bytes = query.toBytes
        Files.write(outputDir.resolve(s"${tc.id}.pcat"), bytes)
        writeMeta(outputDir, tc.id, tc.description, tc.source)
        println(s"  [ok] ${tc.id}: ${tc.description}")
        passed += 1
      catch
        case e: Exception =>
          println(s"  [FAIL] ${tc.id}: ${e.getMessage}")
          failed += 1

    println(s"\nGenerated $passed artifacts, $failed failed")
    println(s"Output directory: $outputDir")

  def main(args: Array[String]): Unit =
    val outputDir = args.headOption
      .map(Paths.get(_))
      .getOrElse(Paths.get("spark-catalyst/src/test/resources/e2e-artifacts"))

    println("E2E Artifact Generator — ProtoCatalyst")
    println("=" * 40)
    generateAll(outputDir)
