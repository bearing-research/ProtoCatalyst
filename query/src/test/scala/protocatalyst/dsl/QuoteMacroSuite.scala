package protocatalyst.dsl

import protocatalyst.encoder._
import protocatalyst.expr._
import protocatalyst.plan._
import protocatalyst.types.LiteralValue

// Test case classes
case class QuoteUser(name: String, age: Int, salary: Double) derives ProtoEncoder

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
