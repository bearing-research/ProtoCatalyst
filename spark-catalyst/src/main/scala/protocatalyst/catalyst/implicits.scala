package protocatalyst.catalyst

import org.apache.spark.sql.{DataFrame, SparkSession}

/** SparkSession extension methods for ProtoCatalyst artifact execution.
  *
  * {{{
  * import protocatalyst.catalyst.implicits._
  *
  * val df = spark.executeArtifact(artifactBytes)
  * spark.validateArtifact(artifactBytes) match {
  *   case SchemaValidator.Valid => println("ok")
  *   case SchemaValidator.Invalid(errors) => errors.foreach(e => println(e.message))
  * }
  * }}}
  */
object implicits {

  implicit class ProtoCatalystSparkSession(val spark: SparkSession) extends AnyVal {

    /** Execute a compiled ProtoCatalyst artifact as a DataFrame. */
    def executeArtifact(
        artifactBytes: Array[Byte],
        config: SparkQueryRunner.ExecutionConfig = SparkQueryRunner.DefaultConfig
    ): DataFrame =
      SparkQueryRunner.execute(artifactBytes, spark, config)

    /** Execute a compiled artifact from JSON string. */
    def executeArtifactFromJson(
        jsonStr: String,
        config: SparkQueryRunner.ExecutionConfig = SparkQueryRunner.DefaultConfig
    ): DataFrame =
      SparkQueryRunner.executeFromJson(jsonStr, spark, config)

    /** Validate schema contracts against the Spark catalog. */
    def validateArtifact(artifactBytes: Array[Byte]): SchemaValidator.ValidationResult =
      SchemaValidator.validate(artifactBytes, spark)
  }
}
