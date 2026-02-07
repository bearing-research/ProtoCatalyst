package protocatalyst.catalyst

import io.circe.Json
import io.circe.parser.parse
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types._

/** Validates compile-time schema contracts against the Spark catalog at runtime.
  *
  * Schema contracts capture the expected table schemas at compile time. This validator checks that
  * the actual tables in the Spark catalog match those expectations before query execution.
  */
object SchemaValidator {

  sealed trait ValidationResult
  case object Valid extends ValidationResult
  case class Invalid(errors: Seq[ValidationError]) extends ValidationResult

  sealed trait ValidationError {
    def message: String
  }
  case class TableNotFound(tableName: String) extends ValidationError {
    def message: String = s"Table '$tableName' not found in catalog"
  }
  case class FieldNotFound(tableName: String, fieldName: String) extends ValidationError {
    def message: String = s"Field '$fieldName' not found in table '$tableName'"
  }
  case class TypeMismatch(
      tableName: String,
      fieldName: String,
      expected: String,
      actual: DataType
  ) extends ValidationError {
    def message: String =
      s"Type mismatch for '$tableName.$fieldName': expected $expected, got ${actual.simpleString}"
  }

  /** Validate schema contracts from PCAT artifact bytes against the Spark catalog. */
  def validate(artifactBytes: Array[Byte], spark: SparkSession): ValidationResult = {
    extractContractsJson(artifactBytes) match {
      case scala.Left(err) =>
        Invalid(Seq(new ValidationError {
          def message: String = s"Failed to parse artifact: $err"
        }))
      case scala.Right(contracts) => validateContracts(contracts, spark)
    }
  }

  /** Validate schema contracts from a parsed JSON artifact. */
  def validateFromJson(artifactJson: Json, spark: SparkSession): ValidationResult = {
    extractContractsFromJson(artifactJson) match {
      case scala.Left(err) =>
        Invalid(Seq(new ValidationError {
          def message: String = s"Failed to extract schema contracts: $err"
        }))
      case scala.Right(contracts) => validateContracts(contracts, spark)
    }
  }

  private case class SchemaContract(
      relationName: String,
      requiredFields: Seq[RequiredField]
  )

  private case class RequiredField(
      name: String,
      expectedType: String,
      expectedNullable: Boolean
  )

  private def extractContractsJson(
      bytes: Array[Byte]
  ): scala.Either[String, Seq[SchemaContract]] = {
    if (bytes.length < 5) return scala.Left("Artifact too short")
    val jsonBytes = bytes.slice(5, bytes.length)
    val jsonStr = new String(jsonBytes, "UTF-8")
    for {
      json <- parse(jsonStr).left.map(e => s"JSON parse error: ${e.getMessage}")
      contracts <- extractContractsFromJson(json)
    } yield contracts
  }

  private def extractContractsFromJson(
      json: Json
  ): scala.Either[String, Seq[SchemaContract]] = {
    val c = json.hcursor
    c.get[Vector[Json]]("schemaContracts")
      .left
      .map(e => s"Missing schemaContracts: ${e.getMessage}")
      .flatMap { contractsJson =>
        val results = contractsJson.map(decodeContract)
        val errors = results.collect { case scala.Left(err) => err }
        if (errors.nonEmpty) scala.Left(errors.mkString("; "))
        else scala.Right(results.collect { case scala.Right(c) => c })
      }
  }

  private def decodeContract(json: Json): scala.Either[String, SchemaContract] = {
    val c = json.hcursor
    for {
      name <- c.get[String]("relationName").left.map(_.getMessage)
      fieldsJson <- c.get[Vector[Json]]("requiredFields").left.map(_.getMessage)
      fields <- {
        val results = fieldsJson.map(decodeField)
        val errors = results.collect { case scala.Left(err) => err }
        if (errors.nonEmpty) scala.Left(errors.mkString("; "))
        else scala.Right(results.collect { case scala.Right(f) => f })
      }
    } yield SchemaContract(name, fields)
  }

  private def decodeField(json: Json): scala.Either[String, RequiredField] = {
    val c = json.hcursor
    for {
      name <- c.get[String]("name").left.map(_.getMessage)
      expectedType <- c.get[String]("expectedType").left.map(_.getMessage)
      expectedNullable <- c.get[Boolean]("expectedNullable").left.map(_.getMessage)
    } yield RequiredField(name, expectedType, expectedNullable)
  }

  private def validateContracts(
      contracts: Seq[SchemaContract],
      spark: SparkSession
  ): ValidationResult = {
    val errors = contracts.flatMap(validateContract(_, spark))
    if (errors.isEmpty) Valid else Invalid(errors)
  }

  private def validateContract(
      contract: SchemaContract,
      spark: SparkSession
  ): Seq[ValidationError] = {
    if (!spark.catalog.tableExists(contract.relationName)) {
      return Seq(TableNotFound(contract.relationName))
    }

    val actualSchema = spark.table(contract.relationName).schema
    contract.requiredFields.flatMap { field =>
      // Case-insensitive field lookup
      actualSchema.fields.find(_.name.equalsIgnoreCase(field.name)) match {
        case None =>
          Seq(FieldNotFound(contract.relationName, field.name))
        case Some(actualField) =>
          val expectedType = simpleTypeToDataType(field.expectedType)
          expectedType match {
            case Some(expected) if !isTypeCompatible(expected, actualField.dataType) =>
              Seq(
                TypeMismatch(
                  contract.relationName,
                  field.name,
                  field.expectedType,
                  actualField.dataType
                )
              )
            case None =>
              // Unknown expected type — skip validation for this field
              Seq.empty
            case _ =>
              Seq.empty
          }
      }
    }
  }

  /** Convert simple type name strings to Spark DataType. */
  private def simpleTypeToDataType(typeName: String): Option[DataType] = typeName match {
    case "BooleanType"   => Some(BooleanType)
    case "ByteType"      => Some(ByteType)
    case "ShortType"     => Some(ShortType)
    case "IntegerType"   => Some(IntegerType)
    case "LongType"      => Some(LongType)
    case "FloatType"     => Some(FloatType)
    case "DoubleType"    => Some(DoubleType)
    case "StringType"    => Some(StringType)
    case "BinaryType"    => Some(BinaryType)
    case "DateType"      => Some(DateType)
    case "TimestampType" => Some(TimestampType)
    case _               => None
  }

  /** Check type compatibility with widening support. */
  private def isTypeCompatible(expected: DataType, actual: DataType): Boolean = {
    (expected, actual) match {
      case (e, a) if e == a          => true
      case (LongType, IntegerType)   => true // Int widens to Long
      case (DoubleType, FloatType)   => true // Float widens to Double
      case (LongType, ShortType)     => true
      case (LongType, ByteType)      => true
      case (IntegerType, ShortType)  => true
      case (IntegerType, ByteType)   => true
      case (DoubleType, IntegerType) => true
      case (DoubleType, LongType)    => true
      case _                         => false
    }
  }
}
