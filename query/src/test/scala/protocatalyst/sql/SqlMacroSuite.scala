package protocatalyst.sql

import protocatalyst.encoder.*
import protocatalyst.plan.*
import protocatalyst.query.CompiledQuery

case class SqlTestUser(name: String, age: Int, salary: Double) derives ProtoEncoder

class SqlMacroSuite extends munit.FunSuite:

  test("compiles simple SELECT"):
    val query = CompiledQuery.sql[SqlTestUser]("SELECT name FROM users")
    assertNotEquals(query.contentHash, 0L)

  test("compiles SELECT with WHERE"):
    val query = CompiledQuery.sql[SqlTestUser]("SELECT name, age FROM users WHERE age > 18")
    assertNotEquals(query.contentHash, 0L)
    query.artifact.plan match
      case ProtoLogicalPlan.Project(_, ProtoLogicalPlan.Filter(_, _)) => () // ok
      case _ => fail("Expected Project over Filter")

  test("compiles SELECT with ORDER BY"):
    val query = CompiledQuery.sql[SqlTestUser]("SELECT name FROM users ORDER BY age DESC")
    assertNotEquals(query.contentHash, 0L)

  test("compiles SELECT with LIMIT"):
    val query = CompiledQuery.sql[SqlTestUser]("SELECT name FROM users LIMIT 10")
    assertNotEquals(query.contentHash, 0L)

  test("compiles complex query"):
    val query = CompiledQuery.sql[SqlTestUser]("""
      SELECT name, salary
      FROM users
      WHERE age >= 21 AND salary > 50000
      ORDER BY salary DESC
      LIMIT 100
    """)
    assertNotEquals(query.contentHash, 0L)

  test("produces correct output schema"):
    val query = CompiledQuery.sql[SqlTestUser]("SELECT name, age FROM users")
    assertEquals(query.outputSchema.fields.size, 2)
    assertEquals(query.outputSchema.fields(0).name, "name")
    assertEquals(query.outputSchema.fields(1).name, "age")

  test("SELECT * produces full schema"):
    val query = CompiledQuery.sql[SqlTestUser]("SELECT * FROM users")
    assertEquals(query.outputSchema.fields.size, 3)

  test("sqlEither returns Right on valid SQL"):
    val result = CompiledQuery.sqlEither[SqlTestUser]("SELECT name FROM users")
    assert(result.isRight)

  test("sqlEither returns Left on unknown column"):
    val result = CompiledQuery.sqlEither[SqlTestUser]("SELECT unknown FROM users")
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains("Unknown column"))

  test("serialization roundtrip"):
    val query = CompiledQuery.sql[SqlTestUser]("""
      SELECT name, age
      FROM users
      WHERE age > 18
      ORDER BY age
      LIMIT 50
    """)
    val bytes = query.toBytes
    val restored = CompiledQuery.fromBytes[SqlTestUser](bytes)

    assert(restored.isRight)
    assertEquals(restored.toOption.get.contentHash, query.contentHash)

  test("WHERE with string comparison"):
    val query = CompiledQuery.sql[SqlTestUser]("SELECT age FROM users WHERE name = 'Alice'")
    assertNotEquals(query.contentHash, 0L)

  test("WHERE with AND/OR"):
    val query = CompiledQuery.sql[SqlTestUser](
      "SELECT name FROM users WHERE age > 18 AND salary > 50000 OR age > 65"
    )
    assertNotEquals(query.contentHash, 0L)

  test("WHERE with IS NULL"):
    val query = CompiledQuery.sql[SqlTestUser]("SELECT name FROM users WHERE salary IS NOT NULL")
    assertNotEquals(query.contentHash, 0L)

  test("aliased columns"):
    val query = CompiledQuery.sql[SqlTestUser]("SELECT name AS n, age AS a FROM users")
    assertEquals(query.outputSchema.fields(0).name, "n")
    assertEquals(query.outputSchema.fields(1).name, "a")

  // Phase 2 tests

  test("WHERE with BETWEEN"):
    val query = CompiledQuery.sql[SqlTestUser]("SELECT name FROM users WHERE age BETWEEN 18 AND 65")
    assertNotEquals(query.contentHash, 0L)

  test("WHERE with NOT BETWEEN"):
    val query =
      CompiledQuery.sql[SqlTestUser]("SELECT name FROM users WHERE age NOT BETWEEN 0 AND 17")
    assertNotEquals(query.contentHash, 0L)

  test("WHERE with LIKE"):
    val query = CompiledQuery.sql[SqlTestUser]("SELECT name FROM users WHERE name LIKE 'A%'")
    assertNotEquals(query.contentHash, 0L)

  test("WHERE with NOT LIKE"):
    val query =
      CompiledQuery.sql[SqlTestUser]("SELECT name FROM users WHERE name NOT LIKE '%test%'")
    assertNotEquals(query.contentHash, 0L)

  test("WHERE with IN"):
    val query =
      CompiledQuery.sql[SqlTestUser]("SELECT name FROM users WHERE age IN (18, 21, 25, 30)")
    assertNotEquals(query.contentHash, 0L)

  test("WHERE with NOT IN"):
    val query = CompiledQuery.sql[SqlTestUser]("SELECT name FROM users WHERE age NOT IN (1, 2, 3)")
    assertNotEquals(query.contentHash, 0L)

  test("SELECT with UPPER function"):
    val query = CompiledQuery.sql[SqlTestUser]("SELECT UPPER(name) FROM users")
    assertNotEquals(query.contentHash, 0L)

  test("SELECT with LOWER function"):
    val query = CompiledQuery.sql[SqlTestUser]("SELECT LOWER(name) FROM users")
    assertNotEquals(query.contentHash, 0L)

  test("SELECT with COALESCE"):
    val query = CompiledQuery.sql[SqlTestUser]("SELECT COALESCE(name, 'Unknown') FROM users")
    assertNotEquals(query.contentHash, 0L)

  test("SELECT with aggregate COUNT"):
    val query = CompiledQuery.sql[SqlTestUser]("SELECT COUNT(name) FROM users")
    assertNotEquals(query.contentHash, 0L)

  test("SELECT with aggregate SUM"):
    val query = CompiledQuery.sql[SqlTestUser]("SELECT SUM(salary) FROM users")
    assertNotEquals(query.contentHash, 0L)

  test("SELECT with aggregate AVG"):
    val query = CompiledQuery.sql[SqlTestUser]("SELECT AVG(age) FROM users")
    assertNotEquals(query.contentHash, 0L)

  test("complex query with Phase 2 features"):
    val query = CompiledQuery.sql[SqlTestUser]("""
      SELECT UPPER(name), age
      FROM users
      WHERE age BETWEEN 18 AND 65
        AND name LIKE 'A%'
        AND salary NOT IN (0, 100)
      ORDER BY age DESC
      LIMIT 50
    """)
    assertNotEquals(query.contentHash, 0L)

  // Phase 3 tests - JOINs

  test("compiles INNER JOIN"):
    val query = CompiledQuery.sql[SqlTestUser](
      "SELECT users.name FROM users INNER JOIN employees ON users.name = employees.name"
    )
    assertNotEquals(query.contentHash, 0L)
    query.artifact.plan match
      case protocatalyst.plan.ProtoLogicalPlan.Project(_, child) =>
        child match
          case protocatalyst.plan.ProtoLogicalPlan.Join(_, _, joinType, _) =>
            assertEquals(joinType, protocatalyst.plan.JoinType.Inner)
          case _ => fail(s"Expected Join plan, got $child")
      case _ => fail("Expected Project plan")

  test("compiles LEFT JOIN"):
    val query = CompiledQuery.sql[SqlTestUser](
      "SELECT users.name FROM users LEFT JOIN employees ON users.name = employees.name"
    )
    assertNotEquals(query.contentHash, 0L)

  test("compiles CROSS JOIN"):
    val query = CompiledQuery.sql[SqlTestUser](
      "SELECT users.name FROM users CROSS JOIN employees"
    )
    assertNotEquals(query.contentHash, 0L)

  test("compiles self-join"):
    val query = CompiledQuery.sql[SqlTestUser](
      "SELECT u1.name, u2.age FROM users u1 INNER JOIN users u2 ON u1.age = u2.age"
    )
    assertNotEquals(query.contentHash, 0L)

  test("compiles multi-way JOIN"):
    val query = CompiledQuery.sql[SqlTestUser]("""
      SELECT u.name
      FROM users u
      INNER JOIN employees e ON u.name = e.name
      INNER JOIN departments d ON e.name = d.name
    """)
    assertNotEquals(query.contentHash, 0L)
