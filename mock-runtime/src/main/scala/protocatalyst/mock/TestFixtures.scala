package protocatalyst.mock

import protocatalyst.schema.ProtoSchema
import protocatalyst.types.*

/**
 * Pre-built test fixtures for common testing scenarios.
 */
object TestFixtures:

  // === Standard Schema Definitions ===

  val userSchema: MockDataType.StructType = MockDataType.StructType(Vector(
    MockStructField("id", MockDataType.LongType, nullable = false),
    MockStructField("name", MockDataType.StringType, nullable = false),
    MockStructField("age", MockDataType.IntegerType, nullable = true),
    MockStructField("salary", MockDataType.DoubleType, nullable = true),
    MockStructField("department_id", MockDataType.IntegerType, nullable = true)
  ))

  val departmentSchema: MockDataType.StructType = MockDataType.StructType(Vector(
    MockStructField("id", MockDataType.IntegerType, nullable = false),
    MockStructField("name", MockDataType.StringType, nullable = false),
    MockStructField("budget", MockDataType.DecimalType(18, 2), nullable = true)
  ))

  val orderSchema: MockDataType.StructType = MockDataType.StructType(Vector(
    MockStructField("order_id", MockDataType.LongType, nullable = false),
    MockStructField("user_id", MockDataType.LongType, nullable = false),
    MockStructField("amount", MockDataType.DecimalType(18, 2), nullable = false),
    MockStructField("created_at", MockDataType.TimestampType, nullable = false)
  ))

  // === ProtoSchema Equivalents ===

  val userProtoSchema: ProtoSchema = ProtoSchema(Vector(
    ProtoStructField("id", ProtoType.LongType, nullable = false),
    ProtoStructField("name", ProtoType.StringType, nullable = false),
    ProtoStructField("age", ProtoType.IntType, nullable = true),
    ProtoStructField("salary", ProtoType.DoubleType, nullable = true),
    ProtoStructField("department_id", ProtoType.IntType, nullable = true)
  ))

  val departmentProtoSchema: ProtoSchema = ProtoSchema(Vector(
    ProtoStructField("id", ProtoType.IntType, nullable = false),
    ProtoStructField("name", ProtoType.StringType, nullable = false),
    ProtoStructField("budget", ProtoType.DecimalType(18, 2), nullable = true)
  ))

  // === Catalog Fixtures ===

  def standardCatalog: InMemoryCatalog =
    val catalog = InMemoryCatalog()
    catalog.registerTable("users", userSchema)
    catalog.registerTable("departments", departmentSchema)
    catalog.registerTable("orders", orderSchema)
    catalog

  // === Schema Mismatch Scenarios ===

  /** Schema with missing fields */
  val userSchemaMissingField: MockDataType.StructType = MockDataType.StructType(Vector(
    MockStructField("id", MockDataType.LongType, nullable = false),
    MockStructField("name", MockDataType.StringType, nullable = false)
    // Missing: age, salary, department_id
  ))

  /** Schema with type mismatch */
  val userSchemaTypeMismatch: MockDataType.StructType = MockDataType.StructType(Vector(
    MockStructField("id", MockDataType.LongType, nullable = false),
    MockStructField("name", MockDataType.StringType, nullable = false),
    MockStructField("age", MockDataType.StringType, nullable = true), // Wrong type: String instead of Int
    MockStructField("salary", MockDataType.DoubleType, nullable = true),
    MockStructField("department_id", MockDataType.IntegerType, nullable = true)
  ))

  /** Schema with nullability mismatch */
  val userSchemaNullabilityMismatch: MockDataType.StructType = MockDataType.StructType(Vector(
    MockStructField("id", MockDataType.LongType, nullable = false),
    MockStructField("name", MockDataType.StringType, nullable = true), // Was non-nullable
    MockStructField("age", MockDataType.IntegerType, nullable = false), // Was nullable
    MockStructField("salary", MockDataType.DoubleType, nullable = true),
    MockStructField("department_id", MockDataType.IntegerType, nullable = true)
  ))

  /** Schema with compatible widening (narrower types that can be widened) */
  val userSchemaWidened: MockDataType.StructType = MockDataType.StructType(Vector(
    MockStructField("id", MockDataType.LongType, nullable = false),
    MockStructField("name", MockDataType.StringType, nullable = false),
    MockStructField("age", MockDataType.ShortType, nullable = true), // Narrower: Short instead of Int (compatible)
    MockStructField("salary", MockDataType.FloatType, nullable = true), // Narrower: Float instead of Double (compatible)
    MockStructField("department_id", MockDataType.IntegerType, nullable = true)
  ))

  // === Sample Data ===

  val sampleUserRows: Vector[MockRow] = Vector(
    MockRow(1L, "Alice", 30, 75000.0, 1),
    MockRow(2L, "Bob", 25, 55000.0, 1),
    MockRow(3L, "Charlie", 35, 85000.0, 2),
    MockRow(4L, "Diana", null, 65000.0, null)
  )

  val sampleDepartmentRows: Vector[MockRow] = Vector(
    MockRow(1, "Engineering", BigDecimal(1000000)),
    MockRow(2, "Marketing", BigDecimal(500000))
  )

  // === Simple Schema for Basic Testing ===

  val simpleUserSchema: MockDataType.StructType = MockDataType.StructType(Vector(
    MockStructField("name", MockDataType.StringType, nullable = false),
    MockStructField("age", MockDataType.IntegerType, nullable = false),
    MockStructField("salary", MockDataType.DoubleType, nullable = false)
  ))

  val simpleUserProtoSchema: ProtoSchema = ProtoSchema(Vector(
    ProtoStructField("name", ProtoType.StringType, nullable = false),
    ProtoStructField("age", ProtoType.IntType, nullable = false),
    ProtoStructField("salary", ProtoType.DoubleType, nullable = false)
  ))

  val simpleUserRows: Vector[MockRow] = Vector(
    MockRow("Alice", 30, 75000.0),
    MockRow("Bob", 25, 55000.0),
    MockRow("Charlie", 35, 85000.0)
  )
