package protocatalyst.spark

import protocatalyst.query.*
import protocatalyst.schema.*
import protocatalyst.types.*
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.{DataType, StructType}

/** Runtime binder - validates schema contracts and binds to Spark. */
object QueryBinder:

  case class BindingResult(
      warnings: Vector[String]
  )

  case class BindingError(
      relation: String,
      expected: SchemaContract,
      actual: StructType,
      mismatches: Vector[FieldMismatch]
  )

  enum FieldMismatch:
    case Missing(name: String)
    case TypeMismatch(name: String, expected: ProtoType, actual: DataType)
    case NullabilityMismatch(name: String, expectedNullable: Boolean, actualNullable: Boolean)

  def validate(
      compiled: CompiledQuery[?],
      schemas: Map[String, StructType]
  ): Either[Vector[BindingError], BindingResult] =
    var errors = Vector.empty[BindingError]
    var warnings = Vector.empty[String]

    for contract <- compiled.requiredSchemas do
      schemas.get(contract.relationName) match
        case None =>
          errors :+= BindingError(
            contract.relationName,
            contract,
            StructType(Nil),
            Vector(FieldMismatch.Missing(s"Relation not found: ${contract.relationName}"))
          )

        case Some(actual) =>
          val mismatches = validateContract(contract, actual)
          if mismatches.nonEmpty then
            errors :+= BindingError(contract.relationName, contract, actual, mismatches)

    if errors.nonEmpty then Left(errors)
    else Right(BindingResult(warnings))

  private def validateContract(
      contract: SchemaContract,
      actual: StructType
  ): Vector[FieldMismatch] =
    var mismatches = Vector.empty[FieldMismatch]

    for field <- contract.requiredFields do
      actual.find(_.name.equalsIgnoreCase(field.name)) match
        case None =>
          mismatches :+= FieldMismatch.Missing(field.name)

        case Some(actualField) =>
          val expectedSpark = SchemaConverter.toSparkType(field.expectedType)
          if !isTypeCompatible(expectedSpark, actualField.dataType) then
            mismatches :+= FieldMismatch.TypeMismatch(
              field.name,
              field.expectedType,
              actualField.dataType
            )

    mismatches

  private def isTypeCompatible(expected: DataType, actual: DataType): Boolean =
    (expected, actual) match
      case (e, a) if e == a => true
      // Allow widening conversions
      case (org.apache.spark.sql.types.LongType, org.apache.spark.sql.types.IntegerType) => true
      case (org.apache.spark.sql.types.DoubleType, org.apache.spark.sql.types.FloatType) => true
      case _ => false
