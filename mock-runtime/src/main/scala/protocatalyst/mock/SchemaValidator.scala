package protocatalyst.mock

import protocatalyst.query.CompiledQuery
import protocatalyst.schema.*
import protocatalyst.types.*

/** Validates schema contracts against actual table schemas. This is the mock equivalent of
  * spark/QueryBinder.validate().
  */
object SchemaValidator:

  sealed trait ValidationResult:
    def isSuccess: Boolean

  case class Success(warnings: Vector[String]) extends ValidationResult:
    def isSuccess = true

  case class Failure(errors: Vector[ValidationError]) extends ValidationResult:
    def isSuccess = false

  sealed trait ValidationError:
    def relationName: String
    def message: String

  case class RelationNotFound(relationName: String) extends ValidationError:
    def message = s"Relation '$relationName' not found in catalog"

  case class FieldMissing(relationName: String, fieldName: String) extends ValidationError:
    def message = s"Field '$fieldName' not found in relation '$relationName'"

  case class TypeMismatch(
      relationName: String,
      fieldName: String,
      expected: ProtoType,
      actual: MockDataType
  ) extends ValidationError:
    def message =
      s"Type mismatch for '$relationName.$fieldName': expected $expected, got ${actual.typeName}"

  case class NullabilityMismatch(
      relationName: String,
      fieldName: String,
      expectedNullable: Boolean,
      actualNullable: Boolean
  ) extends ValidationError:
    def message =
      s"Nullability mismatch for '$relationName.$fieldName': expected nullable=$expectedNullable, got nullable=$actualNullable"

  /** Validate a compiled query against a catalog.
    */
  def validate(compiled: CompiledQuery[?], catalog: MockCatalog): ValidationResult =
    val errors = compiled.requiredSchemas.flatMap(validateContract(_, catalog))
    if errors.isEmpty then Success(Vector.empty)
    else Failure(errors)

  /** Validate a single schema contract.
    */
  def validateContract(contract: SchemaContract, catalog: MockCatalog): Vector[ValidationError] =
    catalog.getTableSchema(contract.relationName) match
      case None =>
        Vector(RelationNotFound(contract.relationName))
      case Some(actualSchema) =>
        validateFields(contract, actualSchema)

  private def validateFields(
      contract: SchemaContract,
      actualSchema: MockDataType.StructType
  ): Vector[ValidationError] =
    contract.requiredFields.flatMap { field =>
      actualSchema.find(_.name.equalsIgnoreCase(field.name)) match
        case None =>
          Vector(FieldMissing(contract.relationName, field.name))
        case Some(actualField) =>
          validateFieldTypes(contract.relationName, field, actualField)
    }

  private def validateFieldTypes(
      relationName: String,
      expected: FieldContract,
      actual: MockStructField
  ): Vector[ValidationError] =
    var errors = Vector.empty[ValidationError]

    val expectedMock = MockSchemaConverter.toMockType(expected.expectedType)
    if !isTypeCompatible(expectedMock, actual.dataType) then
      errors :+= TypeMismatch(relationName, expected.name, expected.expectedType, actual.dataType)

    // Nullability check: actual can be more permissive (nullable) but query expecting
    // nullable cannot receive non-nullable (stricter actual would fail at runtime)
    if expected.expectedNullable && !actual.nullable then
      errors :+= NullabilityMismatch(
        relationName,
        expected.name,
        expected.expectedNullable,
        actual.nullable
      )

    errors

  /** Type compatibility check with safe widening rules. The actual type can be narrower (will be
    * implicitly widened).
    */
  def isTypeCompatible(expected: MockDataType, actual: MockDataType): Boolean =
    (expected, actual) match
      // Exact match
      case (e, a) if e == a => true

      // Safe widening: actual narrower -> expected wider
      case (MockDataType.LongType, MockDataType.IntegerType)  => true
      case (MockDataType.LongType, MockDataType.ShortType)    => true
      case (MockDataType.LongType, MockDataType.ByteType)     => true
      case (MockDataType.IntegerType, MockDataType.ShortType) => true
      case (MockDataType.IntegerType, MockDataType.ByteType)  => true
      case (MockDataType.ShortType, MockDataType.ByteType)    => true
      case (MockDataType.DoubleType, MockDataType.FloatType)  => true

      // Decimal widening - actual can have lower precision/scale
      case (MockDataType.DecimalType(p1, s1), MockDataType.DecimalType(p2, s2)) =>
        p1 >= p2 && s1 >= s2

      // Array element compatibility
      case (MockDataType.ArrayType(e1, n1), MockDataType.ArrayType(e2, n2)) =>
        isTypeCompatible(e1, e2) && (n1 || !n2) // actual can be stricter (non-null)

      // Map compatibility
      case (MockDataType.MapType(k1, v1, n1), MockDataType.MapType(k2, v2, n2)) =>
        isTypeCompatible(k1, k2) && isTypeCompatible(v1, v2) && (n1 || !n2)

      // Struct compatibility (all expected fields must match)
      case (MockDataType.StructType(f1), MockDataType.StructType(f2)) =>
        f1.forall { expectedField =>
          f2.find(_.name.equalsIgnoreCase(expectedField.name)).exists { actualField =>
            isTypeCompatible(expectedField.dataType, actualField.dataType)
          }
        }

      case _ => false
