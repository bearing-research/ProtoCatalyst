package protocatalyst.sql

import java.nio.file.{Files, Path, Paths}

import protocatalyst.artifact.{ArtifactVersion, CompiledArtifact, SourceInfo}
import protocatalyst.codec.JsonArtifactCodec
import protocatalyst.schema.{FieldContract, ProtoSchema, SchemaContract, SchemaFingerprint}
import protocatalyst.sql.ast.SqlStatement
import protocatalyst.sql.parser.SqlParser
import protocatalyst.sql.transform.AstToProtoTransform
import protocatalyst.types.{ProtoStructField, ProtoType}

/** Generates JSON artifacts for parity testing with spark-catalyst.
  *
  * This utility parses SQL with ProtoCatalyst and serializes the result as JSON, which can then be
  * read by spark-catalyst for comparison against Spark's parser.
  */
object ParityArtifactGenerator:

  /** A test case for parity testing */
  case class ParityTestCase(
      id: String,
      sql: String,
      schema: ProtoSchema,
      tableName: String
  )

  /** Standard test schemas */
  object Schemas:
    val users = ProtoSchema(
      Vector(
        ProtoStructField("id", ProtoType.IntegerType, nullable = false),
        ProtoStructField("name", ProtoType.StringType, nullable = false),
        ProtoStructField("age", ProtoType.IntegerType, nullable = false),
        ProtoStructField("salary", ProtoType.DoubleType, nullable = true),
        ProtoStructField("email", ProtoType.StringType, nullable = true)
      )
    )

    val orders = ProtoSchema(
      Vector(
        ProtoStructField("order_id", ProtoType.IntegerType, nullable = false),
        ProtoStructField("user_id", ProtoType.IntegerType, nullable = false),
        ProtoStructField("amount", ProtoType.DoubleType, nullable = false),
        ProtoStructField("status", ProtoType.StringType, nullable = false),
        ProtoStructField("created_at", ProtoType.TimestampType, nullable = false)
      )
    )

    val products = ProtoSchema(
      Vector(
        ProtoStructField("product_id", ProtoType.IntegerType, nullable = false),
        ProtoStructField("name", ProtoType.StringType, nullable = false),
        ProtoStructField("price", ProtoType.DecimalType(10, 2), nullable = false),
        ProtoStructField("category", ProtoType.StringType, nullable = true)
      )
    )

  /** Standard test cases covering various SQL features */
  val standardTestCases: Vector[ParityTestCase] = Vector(
    // Basic SELECT
    ParityTestCase("select_star", "SELECT * FROM users", Schemas.users, "users"),
    ParityTestCase("select_columns", "SELECT name, age FROM users", Schemas.users, "users"),
    ParityTestCase("select_alias", "SELECT name AS n, age AS a FROM users", Schemas.users, "users"),

    // WHERE clauses
    ParityTestCase("where_eq", "SELECT name FROM users WHERE age = 30", Schemas.users, "users"),
    ParityTestCase("where_gt", "SELECT name FROM users WHERE age > 18", Schemas.users, "users"),
    ParityTestCase(
      "where_lt",
      "SELECT name FROM users WHERE salary < 50000",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "where_and",
      "SELECT name FROM users WHERE age > 18 AND salary > 50000",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "where_or",
      "SELECT name FROM users WHERE age < 18 OR age > 65",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "where_between",
      "SELECT name FROM users WHERE age BETWEEN 18 AND 65",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "where_in",
      "SELECT name FROM users WHERE age IN (18, 21, 30)",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "where_like",
      "SELECT name FROM users WHERE name LIKE 'A%'",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "where_is_null",
      "SELECT name FROM users WHERE email IS NULL",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "where_is_not_null",
      "SELECT name FROM users WHERE email IS NOT NULL",
      Schemas.users,
      "users"
    ),

    // ORDER BY
    ParityTestCase(
      "order_by_asc",
      "SELECT name, age FROM users ORDER BY age ASC",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "order_by_desc",
      "SELECT name, age FROM users ORDER BY salary DESC",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "order_by_multiple",
      "SELECT name, age FROM users ORDER BY age ASC, name DESC",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "order_by_nulls",
      "SELECT name, salary FROM users ORDER BY salary NULLS FIRST",
      Schemas.users,
      "users"
    ),

    // LIMIT
    ParityTestCase("limit_simple", "SELECT name FROM users LIMIT 10", Schemas.users, "users"),
    ParityTestCase(
      "limit_with_order",
      "SELECT name FROM users ORDER BY age DESC LIMIT 5",
      Schemas.users,
      "users"
    ),

    // DISTINCT
    ParityTestCase("distinct_simple", "SELECT DISTINCT name FROM users", Schemas.users, "users"),
    ParityTestCase(
      "distinct_multiple",
      "SELECT DISTINCT name, age FROM users",
      Schemas.users,
      "users"
    ),

    // GROUP BY with aggregates
    ParityTestCase(
      "group_by_count",
      "SELECT name, COUNT(*) FROM users GROUP BY name",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "group_by_sum",
      "SELECT name, SUM(salary) FROM users GROUP BY name",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "group_by_avg",
      "SELECT name, AVG(age) FROM users GROUP BY name",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "group_by_min_max",
      "SELECT name, MIN(age), MAX(age) FROM users GROUP BY name",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "group_by_having",
      "SELECT name, COUNT(*) FROM users GROUP BY name HAVING COUNT(*) > 5",
      Schemas.users,
      "users"
    ),

    // CASE WHEN
    ParityTestCase(
      "case_when_simple",
      "SELECT CASE WHEN age > 18 THEN 'adult' ELSE 'minor' END FROM users",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "case_when_multiple",
      "SELECT CASE WHEN age < 18 THEN 'minor' WHEN age < 65 THEN 'adult' ELSE 'senior' END FROM users",
      Schemas.users,
      "users"
    ),

    // CAST
    ParityTestCase(
      "cast_to_string",
      "SELECT CAST(age AS STRING) FROM users",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "cast_to_double",
      "SELECT CAST(age AS DOUBLE) FROM users",
      Schemas.users,
      "users"
    ),

    // String functions
    ParityTestCase("func_upper", "SELECT UPPER(name) FROM users", Schemas.users, "users"),
    ParityTestCase("func_lower", "SELECT LOWER(name) FROM users", Schemas.users, "users"),
    ParityTestCase(
      "func_concat",
      "SELECT CONCAT(name, ' - ', email) FROM users",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "func_substring",
      "SELECT SUBSTRING(name, 1, 3) FROM users",
      Schemas.users,
      "users"
    ),
    ParityTestCase("func_trim", "SELECT TRIM(name) FROM users", Schemas.users, "users"),
    ParityTestCase("func_length", "SELECT LENGTH(name) FROM users", Schemas.users, "users"),

    // Math functions
    ParityTestCase("func_abs", "SELECT ABS(salary) FROM users", Schemas.users, "users"),
    ParityTestCase("func_round", "SELECT ROUND(salary, 2) FROM users", Schemas.users, "users"),
    ParityTestCase("func_ceil", "SELECT CEIL(salary) FROM users", Schemas.users, "users"),
    ParityTestCase("func_floor", "SELECT FLOOR(salary) FROM users", Schemas.users, "users"),

    // Null handling
    ParityTestCase(
      "func_coalesce",
      "SELECT COALESCE(email, 'unknown') FROM users",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "func_nullif",
      "SELECT NULLIF(name, 'Unknown') FROM users",
      Schemas.users,
      "users"
    ),

    // Arithmetic
    ParityTestCase("arithmetic_add", "SELECT salary + 1000 FROM users", Schemas.users, "users"),
    ParityTestCase(
      "arithmetic_subtract",
      "SELECT salary - 1000 FROM users",
      Schemas.users,
      "users"
    ),
    ParityTestCase("arithmetic_multiply", "SELECT salary * 1.1 FROM users", Schemas.users, "users"),
    ParityTestCase("arithmetic_divide", "SELECT salary / 12 FROM users", Schemas.users, "users"),

    // Subqueries
    ParityTestCase(
      "subquery_in",
      "SELECT name FROM users WHERE id IN (SELECT user_id FROM orders)",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "subquery_exists",
      "SELECT name FROM users WHERE EXISTS (SELECT 1 FROM orders WHERE orders.user_id = users.id)",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "subquery_scalar",
      "SELECT name, (SELECT MAX(amount) FROM orders) AS max_amount FROM users",
      Schemas.users,
      "users"
    ),

    // Set operations
    ParityTestCase(
      "union",
      "SELECT name FROM users UNION SELECT name FROM users",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "union_all",
      "SELECT name FROM users UNION ALL SELECT name FROM users",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "intersect",
      "SELECT name FROM users INTERSECT SELECT name FROM users",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "except",
      "SELECT name FROM users EXCEPT SELECT name FROM users",
      Schemas.users,
      "users"
    ),

    // CTEs
    ParityTestCase(
      "cte_simple",
      "WITH active AS (SELECT * FROM users WHERE age > 18) SELECT * FROM active",
      Schemas.users,
      "users"
    ),
    ParityTestCase(
      "cte_multiple",
      "WITH young AS (SELECT * FROM users WHERE age < 30), old AS (SELECT * FROM users WHERE age >= 30) SELECT * FROM young",
      Schemas.users,
      "users"
    )
  )

  /** Parse SQL and create a CompiledArtifact */
  def createArtifact(testCase: ParityTestCase): Either[String, CompiledArtifact] =
    for
      stmt <- SqlParser.parse(testCase.sql)
      plan <- stmt match
        case s: SqlStatement.SelectStatement =>
          AstToProtoTransform.transform(s, testCase.schema, testCase.tableName).left.map(_.message)
        case c: SqlStatement.CompoundStatement =>
          AstToProtoTransform
            .transformStmt(c, testCase.schema, testCase.tableName)
            .left
            .map(_.message)
        case w: SqlStatement.WithStatement =>
          AstToProtoTransform
            .transformStmt(w, testCase.schema, testCase.tableName)
            .left
            .map(_.message)
    yield CompiledArtifact(
      formatVersion = ArtifactVersion.current,
      protocatalystVersion = "0.1.0",
      compiledAt = System.currentTimeMillis(),
      contentHash = scala.util.hashing.MurmurHash3.stringHash(testCase.sql).toLong,
      schemaContracts = Vector(
        SchemaContract(
          testCase.tableName,
          testCase.schema.fields.map { f =>
            FieldContract(f.name, f.dataType, f.nullable)
          },
          SchemaFingerprint.compute(testCase.schema.fields)
        )
      ),
      plan = plan,
      outputSchema = testCase.schema,
      sourceInfo = Some(SourceInfo("parity-test", 0, Some(testCase.sql)))
    )

  /** Serialize an artifact to JSON */
  def toJson(artifact: CompiledArtifact): String =
    val bytes = JsonArtifactCodec.serialize(artifact)
    new String(bytes, "UTF-8")

  /** Generate all test artifacts and write to the output directory */
  def generateAll(outputDir: Path): Unit =
    Files.createDirectories(outputDir)

    // Write index file
    val indexBuilder = new StringBuilder
    indexBuilder.append("# Parity Test Artifacts\n\n")
    indexBuilder.append("Generated test cases for spark-catalyst parity testing.\n\n")

    var passed = 0
    var failed = 0

    for testCase <- standardTestCases do
      createArtifact(testCase) match
        case Right(artifact) =>
          try
            val json = toJson(artifact)
            val outputFile = outputDir.resolve(s"${testCase.id}.json")
            Files.writeString(outputFile, json)

            // Also write a metadata file
            val metaFile = outputDir.resolve(s"${testCase.id}.sql")
            Files.writeString(metaFile, testCase.sql)

            indexBuilder.append(s"- [x] ${testCase.id}: ${testCase.sql.take(60)}...\n")
            passed += 1
          catch
            case e: Exception =>
              indexBuilder.append(s"- [ ] ${testCase.id}: SERIALIZATION ERROR - ${e.getMessage}\n")
              failed += 1

        case Left(error) =>
          indexBuilder.append(s"- [ ] ${testCase.id}: TRANSFORM ERROR - $error\n")
          failed += 1

    indexBuilder.append(s"\n\nTotal: $passed passed, $failed failed\n")
    Files.writeString(outputDir.resolve("README.md"), indexBuilder.toString())

    println(s"Generated $passed artifacts, $failed failed")
    println(s"Output directory: $outputDir")

  /** Main entry point for command-line generation */
  def main(args: Array[String]): Unit =
    val outputDir = args.headOption
      .map(Paths.get(_))
      .getOrElse(Paths.get("spark-catalyst/src/test/resources/parity-artifacts"))

    generateAll(outputDir)
