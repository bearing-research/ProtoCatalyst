package protocatalyst.mock

import protocatalyst.dsl.*
import protocatalyst.encoder.*
import protocatalyst.query.CompiledQuery

case class IntegrationUser(name: String, age: Int, salary: Double) derives ProtoEncoder

class IntegrationSuite extends munit.FunSuite:

  test("serialization roundtrip preserves content hash"):
    val users = Table[IntegrationUser]("users")
    val name = users.col[String]("name")
    val salary = users.col[Double]("salary")
    val compiled = users.filter(_.age > 18).select(name, salary).compile

    val bytes = compiled.toBytes
    val restored = CompiledQuery.fromBytes[IntegrationUser](bytes)

    restored match
      case Right(q)  => assertEquals(q.contentHash, compiled.contentHash)
      case Left(err) => fail(s"Deserialization failed: $err")

  test("validates then binds compiled query"):
    val catalog = InMemoryCatalog()
    catalog.registerTable("users", TestFixtures.simpleUserSchema)

    val users = Table[IntegrationUser]("users")
    val compiled = users.filter(_.age > 18).compile

    // Step 1: Validate
    val validation = SchemaValidator.validate(compiled, catalog)
    assert(validation.isSuccess, s"Validation failed: $validation")

    // Step 2: Bind
    val binding = MockQueryBinder.bind(compiled.artifact.plan, catalog)
    binding match
      case MockQueryBinder.BoundPlan(_)         => () // ok
      case MockQueryBinder.BindingError(msg, _) => fail(s"Binding failed: $msg")

  test("full workflow - compile, validate, bind"):
    val catalog = InMemoryCatalog()
    catalog.registerTable("users", TestFixtures.simpleUserSchema)

    // Create query
    val users = Table[IntegrationUser]("users")
    val name = users.col[String]("name")
    val age = users.col[Int]("age")
    val salary = users.col[Double]("salary")
    val query = users
      .filter(_.age >= 21)
      .filter(_.salary > 50000.0)
      .select(name, age, salary)
      .orderBy(salary.desc)
      .limit(10)

    // Compile
    val compiled = query.compile
    assert(compiled.contentHash != 0L, "Content hash should be non-zero")

    // Validate
    val validation = SchemaValidator.validate(compiled, catalog)
    assert(validation.isSuccess, s"Validation failed: $validation")

    // Bind
    val binding = MockQueryBinder.bind(compiled.artifact.plan, catalog)
    binding match
      case MockQueryBinder.BoundPlan(_)         => () // ok
      case MockQueryBinder.BindingError(msg, _) => fail(s"Binding failed: $msg")

  test("validation fails on schema mismatch"):
    val catalog = InMemoryCatalog()
    // Register schema with wrong type for 'age'
    catalog.registerTable(
      "users",
      MockDataType.StructType(
        Vector(
          MockStructField("name", MockDataType.StringType, nullable = false),
          MockStructField("age", MockDataType.StringType, nullable = false), // Wrong type!
          MockStructField("salary", MockDataType.DoubleType, nullable = false)
        )
      )
    )

    val users = Table[IntegrationUser]("users")
    val compiled = users.filter(_.age > 18).compile

    val validation = SchemaValidator.validate(compiled, catalog)
    assert(!validation.isSuccess, "Validation should fail on type mismatch")

  test("expression evaluation after binding"):
    val catalog = InMemoryCatalog()
    catalog.registerTable("users", TestFixtures.simpleUserSchema)

    val users = Table[IntegrationUser]("users")
    val compiled = users.filter(_.age > 18).compile

    // Bind the plan
    val binding = MockQueryBinder.bind(compiled.artifact.plan, catalog)

    binding match
      case MockQueryBinder.BoundPlan(plan) =>
        plan match
          case protocatalyst.plan.ProtoLogicalPlan.Filter(cond, _) =>
            // Evaluate condition against test rows
            val aliceRow = MockRow("Alice", 30, 75000.0) // age 30 > 18
            val bobRow = MockRow("Bob", 16, 0.0) // age 16 <= 18

            assertEquals(
              ExpressionEvaluator.eval(cond, aliceRow),
              ExpressionEvaluator.Success(true)
            )
            assertEquals(ExpressionEvaluator.eval(cond, bobRow), ExpressionEvaluator.Success(false))
          case _ => fail("Expected Filter plan")
      case MockQueryBinder.BindingError(msg, _) =>
        fail(s"Binding failed: $msg")

  test("serialization roundtrip with complex query"):
    val users = Table[IntegrationUser]("users")
    val name = users.col[String]("name")
    val salary = users.col[Double]("salary")

    val query = users
      .filter(u => (u.age >= 21) && (u.salary > 50000.0))
      .select(name, salary)
      .orderBy(salary.desc)
      .limit(100)

    val compiled = query.compile
    val bytes = compiled.toBytes
    val restored = CompiledQuery.fromBytes[IntegrationUser](bytes)

    restored match
      case Right(q) =>
        assertEquals(q.contentHash, compiled.contentHash)
        assertEquals(q.requiredSchemas.size, compiled.requiredSchemas.size)
      case Left(err) => fail(s"Deserialization failed: $err")

  test("multiple tables can be registered"):
    val catalog = TestFixtures.standardCatalog

    assert(catalog.tableExists("users"))
    assert(catalog.tableExists("departments"))
    assert(catalog.tableExists("orders"))

    assertEquals(catalog.listTables.size, 3)

  test("schema converter roundtrip via catalog"):
    val catalog = InMemoryCatalog()
    catalog.registerTableFromProto("users", TestFixtures.simpleUserProtoSchema)

    val schema = catalog.getTableSchema("users")
    assert(schema.isDefined)

    val mockSchema = schema.get
    assertEquals(mockSchema.fields.size, 3)
    assertEquals(mockSchema.fields(0).name, "name")
    assertEquals(mockSchema.fields(0).dataType, MockDataType.StringType)
