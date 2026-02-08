package protocatalyst.catalyst

import io.circe.Json
import io.circe.parser.parse
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.{DataFrame, ProtoCatalystBridge, SparkSession}

import protocatalyst.catalyst.json.ArtifactParser

/** Executes ProtoCatalyst compiled artifacts as Spark DataFrames.
  *
  * This is the primary runtime entry point for executing queries that were compiled and optimized
  * at Scala 3 compile time. Accepts PCAT-format artifact bytes and produces Spark DataFrames.
  *
  * {{{
  * // Compile time (Scala 3):
  * val bytes = quote { Table[User]("users").filter(_.age > 18) }.compile.toBytes
  *
  * // Runtime (Scala 2.13 + Spark):
  * val df = SparkQueryRunner.execute(bytes, spark)
  * df.show()
  * }}}
  */
object SparkQueryRunner {

  /** Configuration for query execution. */
  case class ExecutionConfig(
      validateSchema: Boolean = false
  )

  val DefaultConfig: ExecutionConfig = ExecutionConfig()

  /** Execute a compiled artifact and return a DataFrame.
    *
    * @param artifactBytes
    *   PCAT-format artifact bytes (4-byte magic + format byte + JSON payload)
    * @param spark
    *   Active SparkSession
    * @param config
    *   Execution configuration (schema validation, etc.)
    * @return
    *   DataFrame with query results
    * @throws ProtoCatalystExecutionException
    *   if parsing or validation fails
    */
  def execute(
      artifactBytes: Array[Byte],
      spark: SparkSession,
      config: ExecutionConfig = DefaultConfig
  ): DataFrame = {
    // Validate schema contracts if enabled
    if (config.validateSchema) {
      SchemaValidator.validate(artifactBytes, spark) match {
        case SchemaValidator.Invalid(errors) =>
          throw new ProtoCatalystExecutionException(
            s"Schema validation failed:\n${errors.map(_.message).mkString("\n")}"
          )
        case _ => // Valid
      }
    }

    // Parse artifact → unresolved LogicalPlan
    val plan = ArtifactParser.parsePlan(artifactBytes) match {
      case scala.Right(p)  => p
      case scala.Left(err) =>
        throw new ProtoCatalystExecutionException(s"Failed to parse artifact: $err")
    }

    // Submit to Spark — analyzer resolves names, physical planner executes
    ProtoCatalystBridge.createDataFrame(spark, plan)
  }

  /** Execute a compiled artifact from a JSON string (without PCAT header).
    *
    * Useful when the artifact JSON is stored in a file or database without the binary header.
    */
  def executeFromJson(
      jsonStr: String,
      spark: SparkSession,
      config: ExecutionConfig = DefaultConfig
  ): DataFrame = {
    // Validate schema contracts if enabled
    if (config.validateSchema) {
      val json = parse(jsonStr) match {
        case scala.Right(j)  => j
        case scala.Left(err) =>
          throw new ProtoCatalystExecutionException(s"JSON parse error: ${err.getMessage}")
      }
      SchemaValidator.validateFromJson(json, spark) match {
        case SchemaValidator.Invalid(errors) =>
          throw new ProtoCatalystExecutionException(
            s"Schema validation failed:\n${errors.map(_.message).mkString("\n")}"
          )
        case _ => // Valid
      }
    }

    // Parse plan from JSON string
    val plan = ArtifactParser.parsePlanFromJsonString(jsonStr) match {
      case scala.Right(p)  => p
      case scala.Left(err) =>
        throw new ProtoCatalystExecutionException(s"Failed to parse plan: $err")
    }

    ProtoCatalystBridge.createDataFrame(spark, plan)
  }

  /** Convert artifact bytes to a Spark LogicalPlan without executing.
    *
    * Useful for plan inspection, debugging, or integration with custom execution engines.
    */
  def toPlan(artifactBytes: Array[Byte]): scala.Either[String, LogicalPlan] =
    ArtifactParser.parsePlan(artifactBytes)

  /** Convert a JSON artifact string to a Spark LogicalPlan without executing. */
  def toPlanFromJson(jsonStr: String): scala.Either[String, LogicalPlan] =
    ArtifactParser.parsePlanFromJsonString(jsonStr)

  /** Extract metadata from an artifact without decoding the full plan tree.
    *
    * Reads top-level fields (version, schema contracts, output schema, source info) for inspection
    * without the cost of full plan conversion.
    */
  def inspectArtifact(artifactBytes: Array[Byte]): scala.Either[String, ArtifactMetadata] = {
    if (artifactBytes.length < 5) return scala.Left("Artifact too short")
    val jsonBytes = artifactBytes.slice(5, artifactBytes.length)
    val jsonStr = new String(jsonBytes, "UTF-8")
    for {
      json <- parse(jsonStr).left.map(e => s"JSON parse error: ${e.getMessage}")
      meta <- extractMetadata(json)
    } yield meta
  }

  /** Extract metadata from a JSON artifact string. */
  def inspectArtifactFromJson(jsonStr: String): scala.Either[String, ArtifactMetadata] = {
    for {
      json <- parse(jsonStr).left.map(e => s"JSON parse error: ${e.getMessage}")
      meta <- extractMetadata(json)
    } yield meta
  }

  private def extractMetadata(json: Json): scala.Either[String, ArtifactMetadata] = {
    val c = json.hcursor
    for {
      compiledAt <- c.get[Long]("compiledAt").left.map(_.getMessage)
      contentHash <- c.get[Long]("contentHash").left.map(_.getMessage)
    } yield {
      // Extract optional fields gracefully
      val contracts = c
        .get[Vector[Json]]("schemaContracts")
        .toOption
        .map { jsons =>
          jsons.flatMap { j =>
            val jc = j.hcursor
            for {
              name <- jc.get[String]("relationName").toOption
              fields <- jc.get[Vector[Json]]("requiredFields").toOption
            } yield SchemaContractInfo(
              name,
              fields.flatMap { fj =>
                val fc = fj.hcursor
                for {
                  fname <- fc.get[String]("name").toOption
                  ftype <- fc.get[String]("expectedType").toOption
                  fnull <- fc.get[Boolean]("expectedNullable").toOption
                } yield FieldInfo(fname, ftype, fnull)
              }
            )
          }
        }
        .getOrElse(Vector.empty)

      val outputFields = c
        .downField("outputSchema")
        .get[Vector[Json]]("fields")
        .toOption
        .map { jsons =>
          jsons.flatMap { fj =>
            val fc = fj.hcursor
            for {
              fname <- fc.get[String]("name").toOption
              ftype <- fc.get[String]("dataType").toOption
              fnull <- fc.get[Boolean]("nullable").toOption
            } yield FieldInfo(fname, ftype, fnull)
          }
        }
        .getOrElse(Vector.empty)

      val originalSql =
        c.downField("sourceInfo").get[String]("originalSql").toOption

      ArtifactMetadata(
        compiledAt = compiledAt,
        contentHash = contentHash,
        schemaContracts = contracts,
        outputSchemaFields = outputFields,
        originalSql = originalSql
      )
    }
  }
}

/** Metadata extracted from a compiled artifact. */
case class ArtifactMetadata(
    compiledAt: Long,
    contentHash: Long,
    schemaContracts: Seq[SchemaContractInfo],
    outputSchemaFields: Seq[FieldInfo],
    originalSql: Option[String]
)

/** Schema contract info for a single relation. */
case class SchemaContractInfo(
    relationName: String,
    requiredFields: Seq[FieldInfo]
)

/** Field info (name, type, nullability). */
case class FieldInfo(
    name: String,
    dataType: String,
    nullable: Boolean
)

/** Exception thrown when ProtoCatalyst artifact execution fails. */
class ProtoCatalystExecutionException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)
