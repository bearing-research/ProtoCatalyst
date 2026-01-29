package protocatalyst.mock

import protocatalyst.dsl.*
import protocatalyst.encoder.*
import protocatalyst.types.*

// Test case class that matches simpleUserSchema
case class SimpleUser(name: String, age: Int, salary: Double) derives ProtoEncoder

class SchemaValidatorSuite extends munit.FunSuite:

  test("validates matching schema"):
    val catalog = InMemoryCatalog()
    catalog.registerTable("users", TestFixtures.simpleUserSchema)

    val users = Table[SimpleUser]("users")
    val compiled = users.compile

    SchemaValidator.validate(compiled, catalog) match
      case SchemaValidator.Success(_) => () // ok
      case SchemaValidator.Failure(errors) => fail(s"Expected success, got errors: $errors")

  test("detects missing table"):
    val catalog = InMemoryCatalog() // empty catalog
    val users = Table[SimpleUser]("users")
    val compiled = users.compile

    SchemaValidator.validate(compiled, catalog) match
      case SchemaValidator.Failure(errors) =>
        assert(errors.exists(_.isInstanceOf[SchemaValidator.RelationNotFound]))
        assertEquals(errors.head.asInstanceOf[SchemaValidator.RelationNotFound].relationName, "users")
      case _ => fail("Expected RelationNotFound error")

  test("detects missing field"):
    val catalog = InMemoryCatalog()
    // Schema with only 'name' field, missing 'age' and 'salary'
    catalog.registerTable("users", MockDataType.StructType(Vector(
      MockStructField("name", MockDataType.StringType, nullable = false)
    )))

    val users = Table[SimpleUser]("users")
    val compiled = users.compile

    SchemaValidator.validate(compiled, catalog) match
      case SchemaValidator.Failure(errors) =>
        val missing = errors.collect { case e: SchemaValidator.FieldMissing => e }
        assert(missing.nonEmpty, "Expected FieldMissing errors")
      case _ => fail("Expected FieldMissing error")

  test("detects type mismatch"):
    val catalog = InMemoryCatalog()
    catalog.registerTable("users", MockDataType.StructType(Vector(
      MockStructField("name", MockDataType.StringType, nullable = false),
      MockStructField("age", MockDataType.StringType, nullable = false), // Wrong type
      MockStructField("salary", MockDataType.DoubleType, nullable = false)
    )))

    val users = Table[SimpleUser]("users")
    val compiled = users.compile

    SchemaValidator.validate(compiled, catalog) match
      case SchemaValidator.Failure(errors) =>
        val mismatches = errors.collect { case e: SchemaValidator.TypeMismatch => e }
        assert(mismatches.exists(_.fieldName == "age"), "Expected TypeMismatch for 'age'")
      case _ => fail("Expected TypeMismatch error")

  test("allows safe type widening"):
    val catalog = InMemoryCatalog()
    // Short can be widened to Int
    catalog.registerTable("users", MockDataType.StructType(Vector(
      MockStructField("name", MockDataType.StringType, nullable = false),
      MockStructField("age", MockDataType.ShortType, nullable = false), // Narrower type
      MockStructField("salary", MockDataType.FloatType, nullable = false) // Narrower type
    )))

    val users = Table[SimpleUser]("users")
    val compiled = users.compile

    SchemaValidator.validate(compiled, catalog) match
      case SchemaValidator.Success(_) => () // ok - widening is allowed
      case SchemaValidator.Failure(errors) => fail(s"Widening should be allowed: $errors")

  test("type compatibility - exact match"):
    assert(SchemaValidator.isTypeCompatible(MockDataType.IntegerType, MockDataType.IntegerType))
    assert(SchemaValidator.isTypeCompatible(MockDataType.StringType, MockDataType.StringType))

  test("type compatibility - widening allowed"):
    // Int -> Long is compatible (actual is narrower)
    assert(SchemaValidator.isTypeCompatible(MockDataType.LongType, MockDataType.IntegerType))
    assert(SchemaValidator.isTypeCompatible(MockDataType.LongType, MockDataType.ShortType))
    assert(SchemaValidator.isTypeCompatible(MockDataType.IntegerType, MockDataType.ShortType))
    assert(SchemaValidator.isTypeCompatible(MockDataType.DoubleType, MockDataType.FloatType))

  test("type compatibility - incompatible types"):
    assert(!SchemaValidator.isTypeCompatible(MockDataType.IntegerType, MockDataType.StringType))
    assert(!SchemaValidator.isTypeCompatible(MockDataType.StringType, MockDataType.IntegerType))
    // Cannot narrow (Int -> Long is not allowed if expected is Int)
    assert(!SchemaValidator.isTypeCompatible(MockDataType.IntegerType, MockDataType.LongType))

  test("type compatibility - array element types"):
    assert(SchemaValidator.isTypeCompatible(
      MockDataType.ArrayType(MockDataType.IntegerType, containsNull = true),
      MockDataType.ArrayType(MockDataType.IntegerType, containsNull = true)
    ))
    // Actual can be stricter (non-null elements)
    assert(SchemaValidator.isTypeCompatible(
      MockDataType.ArrayType(MockDataType.IntegerType, containsNull = true),
      MockDataType.ArrayType(MockDataType.IntegerType, containsNull = false)
    ))

  test("type compatibility - decimal precision"):
    assert(SchemaValidator.isTypeCompatible(
      MockDataType.DecimalType(18, 2),
      MockDataType.DecimalType(10, 2) // Lower precision is ok
    ))
    assert(!SchemaValidator.isTypeCompatible(
      MockDataType.DecimalType(10, 2),
      MockDataType.DecimalType(18, 2) // Higher precision not allowed
    ))
