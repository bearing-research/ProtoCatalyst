package protocatalyst.dsl

import protocatalyst.encoder._
import protocatalyst.expr._
import protocatalyst.plan._
import protocatalyst.types.LiteralValue

// Test case classes
case class QuoteUser(name: String, age: Int, salary: Double) derives ProtoEncoder
case class QuoteDept(id: Int, deptName: String) derives ProtoEncoder
case class QuoteUserNullable(name: String, age: Int, nickname: Option[String]) derives ProtoEncoder
// For join tests - employee has deptId that matches dept's id
case class QuoteEmployee(id: Int, name: String, deptId: Int) derives ProtoEncoder
case class QuoteDepartment(id: Int, name: String) derives ProtoEncoder

class QuoteMacroSuite extends munit.FunSuite:

  test("quote simple table"):
    // Must use inline table creation - macro can't extract from variable references
    val query = QuoteMacro.quote(Table[QuoteUser]("users"))

    assertEquals(query.requiredSchemas.size, 1)
    assertEquals(query.requiredSchemas(0).relationName, "users")
    assert(query.artifact.sourceInfo.exists(_.sourceFile == "dsl-quote-compile-time"))

  test("quote table with filter"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.age > 18)
    }

    assertEquals(query.requiredSchemas.size, 1)
    assertEquals(query.requiredSchemas(0).relationName, "users")

    // Verify plan structure
    query.artifact.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Gt(ProtoExpr.ColumnRef("age", _, _, _), ProtoExpr.Literal(_)),
            ProtoLogicalPlan.RelationRef("users", _, _)
          ) =>
        () // ok
      case other =>
        fail(s"Expected Filter(Gt(age, 18), RelationRef), got: $other")

  test("quote table with limit"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users").limit(10)
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Limit(10, ProtoLogicalPlan.RelationRef("users", _, _)) =>
        () // ok
      case other =>
        fail(s"Expected Limit(10, RelationRef), got: $other")

  test("quote table with distinct"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users").distinct
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Distinct(ProtoLogicalPlan.RelationRef("users", _, _)) =>
        () // ok
      case other =>
        fail(s"Expected Distinct(RelationRef), got: $other")

  test("quote chained filter and limit"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users")
        .filter(_.age > 21)
        .limit(100)
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Limit(
            100,
            ProtoLogicalPlan.Filter(
              ProtoExpr.Gt(ProtoExpr.ColumnRef("age", _, _, _), _),
              ProtoLogicalPlan.RelationRef("users", _, _)
            )
          ) =>
        () // ok
      case other =>
        fail(s"Expected Limit(Filter(RelationRef)), got: $other")

  test("quote produces optimized plan"):
    // The optimizer should fold `true AND condition` to just `condition`
    // or combine adjacent filters
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users")
        .filter(_.age > 18)
        .filter(_.salary > 50000.0)
    }

    // The plan should be optimized (filters potentially combined or kept separate)
    query.artifact.plan match
      case ProtoLogicalPlan.Filter(_, ProtoLogicalPlan.Filter(_, _)) =>
        // Filters kept separate (no optimization applied for this case)
        ()
      case ProtoLogicalPlan.Filter(ProtoExpr.And(_), _) =>
        // Filters combined by optimizer
        ()
      case other =>
        fail(s"Unexpected plan structure: $other")

  test("quote content hash is compile-time constant"):
    val query1 = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.age > 18)
    }

    val query2 = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.age > 18)
    }

    // Same query should produce same content hash (compile-time constant)
    assertEquals(query1.contentHash, query2.contentHash)

  test("quote different queries have different hashes"):
    val query1 = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.age > 18)
    }

    val query2 = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.age > 21)
    }

    // Different queries should have different hashes
    assertNotEquals(query1.contentHash, query2.contentHash)

  test("quote with multiple comparison operators"):
    val queryGt = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.age > 18)
    }

    val queryGtEq = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.age >= 18)
    }

    val queryLt = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.age < 65)
    }

    val queryLtEq = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.age <= 65)
    }

    // Verify each produces the correct comparison
    queryGt.artifact.plan match
      case ProtoLogicalPlan.Filter(ProtoExpr.Gt(_, _), _) => ()
      case _ => fail("Expected Gt")

    queryGtEq.artifact.plan match
      case ProtoLogicalPlan.Filter(ProtoExpr.GtEq(_, _), _) => ()
      case _ => fail("Expected GtEq")

    queryLt.artifact.plan match
      case ProtoLogicalPlan.Filter(ProtoExpr.Lt(_, _), _) => ()
      case _ => fail("Expected Lt")

    queryLtEq.artifact.plan match
      case ProtoLogicalPlan.Filter(ProtoExpr.LtEq(_, _), _) => ()
      case _ => fail("Expected LtEq")

  test("quote with string equality"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.name === "Alice")
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Eq(
              ProtoExpr.ColumnRef("name", _, _, _),
              ProtoExpr.Literal(LiteralValue.StringValue("Alice"))
            ),
            _
          ) =>
        () // ok
      case other =>
        fail(s"Expected Filter(Eq(name, 'Alice')), got: $other")

  test("quote with double comparison"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.salary >= 50000.0)
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.GtEq(
              ProtoExpr.ColumnRef("salary", _, _, _),
              ProtoExpr.Literal(LiteralValue.DoubleValue(50000.0))
            ),
            _
          ) =>
        () // ok
      case other =>
        fail(s"Expected Filter(GtEq(salary, 50000.0)), got: $other")

  test("quote with boolean AND"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(u => (u.age > 18) && (u.salary > 50000.0))
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.And(
              Vector(
                ProtoExpr.Gt(ProtoExpr.ColumnRef("age", _, _, _), _),
                ProtoExpr.Gt(ProtoExpr.ColumnRef("salary", _, _, _), _)
              )
            ),
            _
          ) =>
        () // ok
      case other =>
        fail(s"Expected Filter(And(Gt, Gt)), got: $other")

  test("quote with boolean OR"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(u => (u.age < 18) || (u.age > 65))
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Or(
              Vector(
                ProtoExpr.Lt(ProtoExpr.ColumnRef("age", _, _, _), _),
                ProtoExpr.Gt(ProtoExpr.ColumnRef("age", _, _, _), _)
              )
            ),
            _
          ) =>
        () // ok
      case other =>
        fail(s"Expected Filter(Or(Lt, Gt)), got: $other")

  test("quote with not equal"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.name =!= "Admin")
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.NotEq(
              ProtoExpr.ColumnRef("name", _, _, _),
              ProtoExpr.Literal(LiteralValue.StringValue("Admin"))
            ),
            _
          ) =>
        () // ok
      case other =>
        fail(s"Expected Filter(NotEq(name, 'Admin')), got: $other")

  test("quote with as alias"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users").as("u")
    }

    query.artifact.plan match
      case ProtoLogicalPlan.SubqueryAlias("u", ProtoLogicalPlan.RelationRef("users", _, _)) =>
        () // ok
      case other =>
        fail(s"Expected SubqueryAlias(u, RelationRef), got: $other")

  test("quote with filter and alias"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users")
        .filter(_.age > 21)
        .as("adults")
    }

    query.artifact.plan match
      case ProtoLogicalPlan.SubqueryAlias(
            "adults",
            ProtoLogicalPlan.Filter(
              ProtoExpr.Gt(ProtoExpr.ColumnRef("age", _, _, _), _),
              ProtoLogicalPlan.RelationRef("users", _, _)
            )
          ) =>
        () // ok
      case other =>
        fail(s"Expected SubqueryAlias(adults, Filter(RelationRef)), got: $other")

  test("quote with union"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users1").union(Table[QuoteUser]("users2").toQuery)
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Union(
            Vector(
              ProtoLogicalPlan.RelationRef("users1", _, _),
              ProtoLogicalPlan.RelationRef("users2", _, _)
            ),
            false,
            false
          ) =>
        () // ok
      case other =>
        fail(s"Expected Union([users1, users2]), got: $other")

  test("quote with filtered union"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("active_users")
        .filter(_.age > 18)
        .union(
          Table[QuoteUser]("archived_users").filter(_.age > 21)
        )
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Union(
            Vector(
              ProtoLogicalPlan.Filter(_, ProtoLogicalPlan.RelationRef("active_users", _, _)),
              ProtoLogicalPlan.Filter(_, ProtoLogicalPlan.RelationRef("archived_users", _, _))
            ),
            false,
            false
          ) =>
        () // ok
      case other =>
        fail(s"Expected Union([Filter(active_users), Filter(archived_users)]), got: $other")

  test("quote with intersect"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users1").intersect(Table[QuoteUser]("users2").toQuery)
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Intersect(
            ProtoLogicalPlan.RelationRef("users1", _, _),
            ProtoLogicalPlan.RelationRef("users2", _, _),
            false
          ) =>
        () // ok
      case other =>
        fail(s"Expected Intersect(users1, users2, isAll=false), got: $other")

  test("quote with intersectAll"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users1").intersectAll(Table[QuoteUser]("users2").toQuery)
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Intersect(
            ProtoLogicalPlan.RelationRef("users1", _, _),
            ProtoLogicalPlan.RelationRef("users2", _, _),
            true
          ) =>
        () // ok
      case other =>
        fail(s"Expected Intersect(users1, users2, isAll=true), got: $other")

  test("quote with except"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users1").except(Table[QuoteUser]("users2").toQuery)
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Except(
            ProtoLogicalPlan.RelationRef("users1", _, _),
            ProtoLogicalPlan.RelationRef("users2", _, _),
            false
          ) =>
        () // ok
      case other =>
        fail(s"Expected Except(users1, users2, isAll=false), got: $other")

  test("quote with exceptAll"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users1").exceptAll(Table[QuoteUser]("users2").toQuery)
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Except(
            ProtoLogicalPlan.RelationRef("users1", _, _),
            ProtoLogicalPlan.RelationRef("users2", _, _),
            true
          ) =>
        () // ok
      case other =>
        fail(s"Expected Except(users1, users2, isAll=true), got: $other")

  test("quote with arithmetic addition in filter"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.age + 10 > 30)
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Gt(
              ProtoExpr.Add(
                ProtoExpr.ColumnRef("age", _, _, _),
                ProtoExpr.Literal(LiteralValue.IntValue(10))
              ),
              ProtoExpr.Literal(LiteralValue.IntValue(30))
            ),
            _
          ) =>
        () // ok
      case other =>
        fail(s"Expected Filter with Add expression, got: $other")

  test("quote with arithmetic subtraction in filter"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.age - 5 >= 18)
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.GtEq(
              ProtoExpr.Subtract(
                ProtoExpr.ColumnRef("age", _, _, _),
                ProtoExpr.Literal(LiteralValue.IntValue(5))
              ),
              ProtoExpr.Literal(LiteralValue.IntValue(18))
            ),
            _
          ) =>
        () // ok
      case other =>
        fail(s"Expected Filter with Subtract expression, got: $other")

  test("quote with arithmetic multiplication in filter"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.salary * 2.0 > 100000.0)
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Gt(
              ProtoExpr.Multiply(
                ProtoExpr.ColumnRef("salary", _, _, _),
                ProtoExpr.Literal(LiteralValue.DoubleValue(2.0))
              ),
              ProtoExpr.Literal(LiteralValue.DoubleValue(100000.0))
            ),
            _
          ) =>
        () // ok
      case other =>
        fail(s"Expected Filter with Multiply expression, got: $other")

  test("quote with arithmetic division in filter"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.salary / 12.0 > 5000.0)
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Gt(
              ProtoExpr.Divide(
                ProtoExpr.ColumnRef("salary", _, _, _),
                ProtoExpr.Literal(LiteralValue.DoubleValue(12.0))
              ),
              ProtoExpr.Literal(LiteralValue.DoubleValue(5000.0))
            ),
            _
          ) =>
        () // ok
      case other =>
        fail(s"Expected Filter with Divide expression, got: $other")

  test("quote with complex arithmetic expression"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.age * 2 + 10 > 50)
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Gt(
              ProtoExpr.Add(
                ProtoExpr.Multiply(
                  ProtoExpr.ColumnRef("age", _, _, _),
                  ProtoExpr.Literal(LiteralValue.IntValue(2))
                ),
                ProtoExpr.Literal(LiteralValue.IntValue(10))
              ),
              ProtoExpr.Literal(LiteralValue.IntValue(50))
            ),
            _
          ) =>
        () // ok
      case other =>
        fail(s"Expected Filter with nested arithmetic (Add(Multiply(...), ...)), got: $other")

  test("quote with crossJoin"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users").crossJoin(Table[QuoteDept]("depts").toQuery)
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Join(
            ProtoLogicalPlan.RelationRef("users", _, _),
            ProtoLogicalPlan.RelationRef("depts", _, _),
            JoinType.Cross,
            None
          ) =>
        () // ok
      case other =>
        fail(s"Expected Join(users, depts, Cross, None), got: $other")

  test("quote with isNull on nullable field"):
    // Use nullable field to avoid optimizer folding (isNull on non-nullable is always false)
    val query = QuoteMacro.quote {
      Table[QuoteUserNullable]("users").filter(_.nickname.isNull)
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.IsNull(ProtoExpr.ColumnRef("nickname", _, _, _)),
            ProtoLogicalPlan.RelationRef("users", _, _)
          ) =>
        () // ok
      case other =>
        fail(s"Expected Filter(IsNull(nickname)), got: $other")

  test("quote with isNotNull on nullable field"):
    // Use nullable field to avoid optimizer folding (isNotNull on non-nullable is always true)
    val query = QuoteMacro.quote {
      Table[QuoteUserNullable]("users").filter(_.nickname.isNotNull)
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.IsNotNull(ProtoExpr.ColumnRef("nickname", _, _, _)),
            ProtoLogicalPlan.RelationRef("users", _, _)
          ) =>
        () // ok
      case other =>
        fail(s"Expected Filter(IsNotNull(nickname)), got: $other")

  test("quote with NOT operator"):
    // The optimizer simplifies !(age > 18) to (age <= 18)
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(u => !(u.age > 18))
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.LtEq(ProtoExpr.ColumnRef("age", _, _, _), _),
            ProtoLogicalPlan.RelationRef("users", _, _)
          ) =>
        // Optimizer applied NOT simplification: !(a > b) => a <= b
        ()
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Not(ProtoExpr.Gt(ProtoExpr.ColumnRef("age", _, _, _), _)),
            ProtoLogicalPlan.RelationRef("users", _, _)
          ) =>
        // Unoptimized form
        ()
      case other =>
        fail(s"Expected Filter(LtEq or Not(Gt)), got: $other")

  test("quote with upper string operation"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.name.upper === "ALICE")
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Eq(
              ProtoExpr.Upper(ProtoExpr.ColumnRef("name", _, _, _)),
              ProtoExpr.Literal(LiteralValue.StringValue("ALICE"))
            ),
            ProtoLogicalPlan.RelationRef("users", _, _)
          ) =>
        () // ok
      case other =>
        fail(s"Expected Filter(Eq(Upper(name), 'ALICE')), got: $other")

  test("quote with lower string operation"):
    val query = QuoteMacro.quote {
      Table[QuoteUser]("users").filter(_.name.lower === "alice")
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Eq(
              ProtoExpr.Lower(ProtoExpr.ColumnRef("name", _, _, _)),
              ProtoExpr.Literal(LiteralValue.StringValue("alice"))
            ),
            ProtoLogicalPlan.RelationRef("users", _, _)
          ) =>
        () // ok
      case other =>
        fail(s"Expected Filter(Eq(Lower(name), 'alice')), got: $other")

  test("quote with inner join and condition"):
    val query = QuoteMacro.quote {
      Table[QuoteEmployee]("employees")
        .join(Table[QuoteDepartment]("departments").toQuery)
        .on((e, d) => e.deptId === d.id)
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Join(
            ProtoLogicalPlan.RelationRef("employees", _, _),
            ProtoLogicalPlan.RelationRef("departments", _, _),
            JoinType.Inner,
            Some(ProtoExpr.Eq(
              ProtoExpr.ColumnRef("deptId", Some("_1"), _, _),
              ProtoExpr.ColumnRef("id", Some("_2"), _, _)
            ))
          ) =>
        () // ok
      case other =>
        fail(s"Expected Join with condition, got: $other")

  test("quote with left join and condition"):
    val query = QuoteMacro.quote {
      Table[QuoteEmployee]("employees")
        .leftJoin(Table[QuoteDepartment]("departments").toQuery)
        .on((e, d) => e.deptId === d.id)
    }

    query.artifact.plan match
      case ProtoLogicalPlan.Join(
            ProtoLogicalPlan.RelationRef("employees", _, _),
            ProtoLogicalPlan.RelationRef("departments", _, _),
            JoinType.LeftOuter,
            Some(_)
          ) =>
        () // ok
      case other =>
        fail(s"Expected LeftOuter Join, got: $other")
