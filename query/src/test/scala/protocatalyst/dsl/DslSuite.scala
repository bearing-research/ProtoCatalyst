package protocatalyst.dsl

import protocatalyst.encoder._
import protocatalyst.expr._
import protocatalyst.plan._
import protocatalyst.types._

// Test case classes
case class User(name: String, age: Int, salary: Double) derives ProtoEncoder
case class Department(id: Int, name: String) derives ProtoEncoder

class DslSuite extends munit.FunSuite:

  // === Table and Column Tests ===

  test("create table with derived schema"):
    val users = Table[User]("users")

    assertEquals(users.tableName, "users")
    assertEquals(users.encoder.fields.size, 3)
    assertEquals(users.encoder.fields(0).name, "name")
    assertEquals(users.encoder.fields(1).name, "age")
    assertEquals(users.encoder.fields(2).name, "salary")

  test("get typed column from table"):
    val users = Table[User]("users")

    val nameCol = users.col[String]("name")
    val ageCol = users.col[Int]("age")
    val salaryCol = users.col[Double]("salary")

    assertEquals(nameCol.name, "name")
    assertEquals(nameCol.protoType, ProtoType.StringType)
    assertEquals(ageCol.name, "age")
    assertEquals(ageCol.protoType, ProtoType.IntegerType)
    assertEquals(salaryCol.name, "salary")
    assertEquals(salaryCol.protoType, ProtoType.DoubleType)

  test("column not found throws exception"):
    val users = Table[User]("users")

    intercept[IllegalArgumentException]:
      users.col[String]("nonexistent")

  // === Expression Tests ===

  test("literal expressions"):
    val intLit = Expr.lit(42)
    val strLit = Expr.lit("hello")
    Expr.lit(true)

    intLit.toProtoExpr match
      case ProtoExpr.Literal(LiteralValue.IntValue(42)) => () // ok
      case other => fail(s"Expected IntValue(42), got $other")

    strLit.toProtoExpr match
      case ProtoExpr.Literal(LiteralValue.StringValue("hello")) => () // ok
      case other => fail(s"Expected StringValue, got $other")

  test("comparison expressions"):
    val users = Table[User]("users")
    val age = users.col[Int]("age")

    val gt = age > Expr.lit(18)
    age < Expr.lit(65)
    age === Expr.lit(30)

    gt.toProtoExpr match
      case ProtoExpr.Gt(_, ProtoExpr.Literal(LiteralValue.IntValue(18))) => () // ok
      case other => fail(s"Expected Gt, got $other")

  test("arithmetic expressions"):
    val users = Table[User]("users")
    val salary = users.col[Double]("salary")

    val doubled = salary * Expr.lit(2.0)

    doubled.toProtoExpr match
      case ProtoExpr.Multiply(_, _) => () // ok
      case other                    => fail(s"Expected Multiply, got $other")

  test("boolean expressions"):
    val users = Table[User]("users")
    val age = users.col[Int]("age")

    val cond1 = age > Expr.lit(18)
    val cond2 = age < Expr.lit(65)
    val combined = cond1 && cond2

    combined.toProtoExpr match
      case ProtoExpr.And(children) =>
        assertEquals(children.size, 2)
      case other => fail(s"Expected And, got $other")

  test("string expressions"):
    val users = Table[User]("users")
    val name = users.col[String]("name")

    val upper = name.upper

    upper.toProtoExpr match
      case ProtoExpr.Upper(_) => () // ok
      case other              => fail(s"Expected Upper, got $other")

  // === Query Builder Tests ===

  test("filter query"):
    val users = Table[User]("users")
    val age = users.col[Int]("age")

    val query = users.filter(age > Expr.lit(18))

    query.plan match
      case ProtoLogicalPlan.Filter(_, ProtoLogicalPlan.RelationRef(_, _, _)) => () // ok
      case other => fail(s"Expected Filter, got $other")

  test("select query"):
    val users = Table[User]("users")
    val name = users.col[String]("name")
    val age = users.col[Int]("age")

    val query = users.select(name, age)

    query.plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        assertEquals(exprs.size, 2)
      case other => fail(s"Expected Project, got $other")

  test("chained filter and select"):
    val users = Table[User]("users")
    val name = users.col[String]("name")
    val age = users.col[Int]("age")

    val query = users
      .filter(age > Expr.lit(18))
      .select(name, age)

    query.plan match
      case ProtoLogicalPlan.Project(_, ProtoLogicalPlan.Filter(_, _)) => () // ok
      case other => fail(s"Expected Project(Filter), got $other")

  test("orderBy query"):
    val users = Table[User]("users")
    val age = users.col[Int]("age")

    val query = users.orderBy(age.desc)

    query.plan match
      case ProtoLogicalPlan.Sort(orders, _) =>
        assertEquals(orders.size, 1)
        assertEquals(orders(0).direction, SortDirection.Descending)
      case other => fail(s"Expected Sort, got $other")

  test("limit query"):
    val users = Table[User]("users")

    val query = users.limit(10)

    query.plan match
      case ProtoLogicalPlan.Limit(10, _) => () // ok
      case other                         => fail(s"Expected Limit(10), got $other")

  test("distinct query"):
    val users = Table[User]("users")

    val query = users.distinct

    query.plan match
      case ProtoLogicalPlan.Distinct(_) => () // ok
      case other                        => fail(s"Expected Distinct, got $other")

  // === Aggregate Tests ===

  test("groupBy with count"):
    val users = Table[User]("users")
    val dept = users.col[String]("name")

    val query = users
      .groupBy(dept)
      .agg(functions.count)

    query.plan match
      case ProtoLogicalPlan.Aggregate(grouping, aggs, _) =>
        assertEquals(grouping.size, 1)
        assertEquals(aggs.size, 1)
      case other => fail(s"Expected Aggregate, got $other")

  test("aggregate functions"):
    val users = Table[User]("users")
    val salary = users.col[Double]("salary")

    // Just test that they compile and produce correct ProtoExpr types
    val cnt = functions.count
    functions.sumDouble(salary)
    val av = functions.avg(salary)
    functions.min(salary)
    functions.max(salary)

    cnt.toProtoExpr match
      case ProtoExpr.Count(_, false) => () // ok
      case other                     => fail(s"Expected Count, got $other")

    av.toProtoExpr match
      case ProtoExpr.Avg(_) => () // ok
      case other            => fail(s"Expected Avg, got $other")

  // === Join Tests ===

  test("inner join"):
    val users = Table[User]("users")
    val depts = Table[Department]("departments")
    val userId = users.col[Int]("age") // pretend this is user_id
    val deptId = depts.col[Int]("id")

    val query = users.join(depts).on(userId === deptId)

    query.plan match
      case ProtoLogicalPlan.Join(_, _, JoinType.Inner, Some(_)) => () // ok
      case other => fail(s"Expected Join(Inner), got $other")

  test("cross join"):
    val users = Table[User]("users")
    val depts = Table[Department]("departments")

    val query = users.crossJoin(depts)

    query.plan match
      case ProtoLogicalPlan.Join(_, _, JoinType.Cross, None) => () // ok
      case other => fail(s"Expected Join(Cross), got $other")

  // === Compilation Tests ===

  test("compile query to artifact"):
    val users = Table[User]("users")
    val name = users.col[String]("name")
    val age = users.col[Int]("age")

    val query = users
      .filter(age > Expr.lit(18))
      .select(name, age)

    val compiled = query.compile

    assertEquals(compiled.requiredSchemas.size, 1)
    assertEquals(compiled.requiredSchemas(0).relationName, "users")
    assert(compiled.contentHash != 0L)

  test("compiled query has correct output schema"):
    val users = Table[User]("users")
    val name = users.col[String]("name")
    val age = users.col[Int]("age")

    val compiled = users.select(name, age).compile

    // Output should be (String, Int)
    assertEquals(compiled.outputSchema.fields.size, 2)

  test("compiled query serialization roundtrip"):
    val users = Table[User]("users")
    val age = users.col[Int]("age")

    val compiled = users.filter(age > Expr.lit(18)).compile

    val bytes = compiled.toBytes
    val restored = protocatalyst.query.CompiledQuery.fromBytes[User](bytes)

    restored match
      case Left(err) => fail(s"Deserialization failed: $err")
      case Right(q)  => assertEquals(q.contentHash, compiled.contentHash)

  // === Complex Query Tests ===

  test("complex query with multiple operations"):
    val users = Table[User]("users")
    val name = users.col[String]("name")
    val age = users.col[Int]("age")
    val salary = users.col[Double]("salary")

    val query = users
      .filter(age >= Expr.lit(21))
      .filter(salary > Expr.lit(50000.0))
      .select(name, age, salary)
      .orderBy(salary.desc, age.asc)
      .limit(100)

    query.compile

    // Verify structure
    query.plan match
      case ProtoLogicalPlan.Limit(
            100,
            ProtoLogicalPlan.Sort(
              _,
              ProtoLogicalPlan.Project(_, ProtoLogicalPlan.Filter(_, ProtoLogicalPlan.Filter(_, _)))
            )
          ) =>
        () // ok
      case other => fail(s"Unexpected plan structure: $other")

  // === Lambda-Style Field Access Tests ===

  test("lambda filter on table"):
    val users = Table[User]("users")

    val query = users.filter(_.age > 18)

    query.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Gt(
              ProtoExpr.ColumnRef("age", _, _, _),
              ProtoExpr.Literal(LiteralValue.IntValue(18))
            ),
            ProtoLogicalPlan.RelationRef(_, _, _)
          ) =>
        () // ok
      case other => fail(s"Expected Filter with age > 18, got $other")

  test("lambda filter with where alias"):
    val users = Table[User]("users")

    val query = users.where(_.salary >= 50000.0)

    query.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.GtEq(ProtoExpr.ColumnRef("salary", _, _, _), _),
            _
          ) =>
        () // ok
      case other => fail(s"Expected Filter with salary >= 50000, got $other")

  test("lambda filter on query"):
    val users = Table[User]("users")

    val query = users
      .filter(_.age > 18)
      .filter(_.salary > 30000.0)

    query.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Gt(ProtoExpr.ColumnRef("salary", _, _, _), _),
            ProtoLogicalPlan.Filter(
              ProtoExpr.Gt(ProtoExpr.ColumnRef("age", _, _, _), _),
              _
            )
          ) =>
        () // ok
      case other => fail(s"Expected nested Filter, got $other")

  test("lambda filter combined with boolean ops"):
    val users = Table[User]("users")

    val query = users.filter(u => (u.age > 18) && (u.salary > 30000.0))

    query.plan match
      case ProtoLogicalPlan.Filter(ProtoExpr.And(conditions), _) =>
        assertEquals(conditions.size, 2)
      case other => fail(s"Expected Filter with And condition, got $other")

  test("FieldSelector provides column access"):
    val users = Table[User]("users")
    val selector = users.row

    val ageCol = selector.age
    val nameCol = selector.name

    assertEquals(ageCol.name, "age")
    assertEquals(nameCol.name, "name")

  test("FieldSelector rejects invalid field at compile time"):
    // With the transparent inline macro, invalid field names are now caught at compile time
    // rather than throwing a runtime exception. This is verified by the compile error:
    // "Field 'nonexistent' not found in User. Available fields: name, age, salary"
    assert(compileErrors("FieldSelector[protocatalyst.dsl.User].nonexistent").nonEmpty)

  test("query row method returns field selector"):
    val users = Table[User]("users")
    val query = users.filter(_.age > 18)

    val selector = query.row
    val nameCol = selector.name

    assertEquals(nameCol.name, "name")

  test("lambda filter produces same plan as explicit column access"):
    val users = Table[User]("users")

    val explicitQuery = users.filter(users.col[Int]("age") > Expr.lit(18))
    val lambdaQuery = users.filter(_.age > 18)

    // Both should produce equivalent Filter plans
    (explicitQuery.plan, lambdaQuery.plan) match
      case (
            ProtoLogicalPlan.Filter(ProtoExpr.Gt(ProtoExpr.ColumnRef("age", _, _, _), _), _),
            ProtoLogicalPlan.Filter(ProtoExpr.Gt(ProtoExpr.ColumnRef("age", _, _, _), _), _)
          ) =>
        () // ok
      case other => fail(s"Plans should be equivalent: $other")

  // === Spark-Compatible Syntax Tests ===

  test("lambda filter without Expr.lit wrapper"):
    val users = Table[User]("users")

    // New Spark-compatible syntax: _.age > 18 instead of _.age > 18
    val query = users.filter(_.age > 18)

    query.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Gt(
              ProtoExpr.ColumnRef("age", _, _, _),
              ProtoExpr.Literal(LiteralValue.IntValue(18))
            ),
            _
          ) =>
        () // ok
      case other => fail(s"Expected Filter with age > 18, got $other")

  test("lambda filter with double literal"):
    val users = Table[User]("users")

    val query = users.filter(_.salary >= 50000.0)

    query.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.GtEq(
              ProtoExpr.ColumnRef("salary", _, _, _),
              ProtoExpr.Literal(LiteralValue.DoubleValue(50000.0))
            ),
            _
          ) =>
        () // ok
      case other => fail(s"Expected Filter with salary >= 50000.0, got $other")

  test("$ string interpolator for column access"):
    import SparkSyntax.$

    val ageCol = $"age"

    assertEquals(ageCol.name, "age")
    ageCol.toProtoExpr match
      case ProtoExpr.ColumnRef("age", None, ProtoType.UnresolvedType("untyped"), true) => () // ok
      case other => fail(s"Expected ColumnRef(age), got $other")

  test("$ column with comparison"):
    import SparkSyntax.$

    val users = Table[User]("users")
    val query = users.filter($"age" > 18)

    query.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Gt(
              ProtoExpr.ColumnRef("age", _, _, _),
              ProtoExpr.Literal(LiteralValue.IntValue(18))
            ),
            _
          ) =>
        () // ok
      case other => fail(s"Expected Filter with $$age > 18, got $other")

  test("SparkSyntax.col function"):
    import SparkSyntax.col

    val nameCol = col("name")

    assertEquals(nameCol.name, "name")

  test("mixed syntax - lambda and $"):
    import SparkSyntax.$

    val users = Table[User]("users")

    // Both should work
    val lambdaQuery = users.filter(_.age > 18)
    val dollarQuery = users.filter($"age" > 18)

    // Verify both produce Filter plans
    lambdaQuery.plan match
      case ProtoLogicalPlan.Filter(_, _) => () // ok
      case _                             => fail("Lambda query should produce Filter")

    dollarQuery.plan match
      case ProtoLogicalPlan.Filter(_, _) => () // ok
      case _                             => fail("Dollar query should produce Filter")

  test("equality comparison with string literal"):
    val users = Table[User]("users")

    val query = users.filter(_.name === "Alice")

    query.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Eq(
              ProtoExpr.ColumnRef("name", _, _, _),
              ProtoExpr.Literal(LiteralValue.StringValue("Alice"))
            ),
            _
          ) =>
        () // ok
      case other => fail(s"Expected Filter with name === Alice, got $other")

  test("combined boolean conditions with direct literals"):
    val users = Table[User]("users")

    val query = users.filter(u => (u.age > 18) && (u.salary >= 50000.0))

    query.plan match
      case ProtoLogicalPlan.Filter(ProtoExpr.And(conditions), _) =>
        assertEquals(conditions.size, 2)
      case other => fail(s"Expected Filter with And condition, got $other")

  // === Subquery Tests ===

  test("IN subquery expression"):
    val employees = Table[User]("employees")
    val depts = Table[Department]("departments")
    val age = employees.col[Int]("age")
    val deptId = depts.col[Int]("id")

    val subquery = depts.select(deptId)
    val query = employees.filter(age in subquery)

    query.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.InSubquery(
              ProtoExpr.ColumnRef("age", _, _, _),
              ProtoLogicalPlan.Project(_, ProtoLogicalPlan.RelationRef("departments", _, _))
            ),
            ProtoLogicalPlan.RelationRef("employees", _, _)
          ) =>
        () // ok
      case other => fail(s"Expected Filter with InSubquery, got $other")

  test("NOT IN subquery expression"):
    val employees = Table[User]("employees")
    val depts = Table[Department]("departments")
    val age = employees.col[Int]("age")
    val deptId = depts.col[Int]("id")

    val subquery = depts.select(deptId)
    val query = employees.filter(age notIn subquery)

    query.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Not(ProtoExpr.InSubquery(ProtoExpr.ColumnRef("age", _, _, _), _)),
            _
          ) =>
        () // ok
      case other => fail(s"Expected Filter with Not(InSubquery), got $other")

  test("EXISTS subquery"):
    val employees = Table[User]("employees")
    val depts = Table[Department]("departments")

    val query = employees.filter(functions.exists(depts.toQuery))

    query.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Exists(ProtoLogicalPlan.RelationRef("departments", _, _)),
            _
          ) =>
        () // ok
      case other => fail(s"Expected Filter with Exists, got $other")

  test("NOT EXISTS subquery"):
    val employees = Table[User]("employees")
    val depts = Table[Department]("departments")

    val query = employees.filter(functions.notExists(depts.toQuery))

    query.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Not(ProtoExpr.Exists(ProtoLogicalPlan.RelationRef("departments", _, _))),
            _
          ) =>
        () // ok
      case other => fail(s"Expected Filter with Not(Exists), got $other")

  // === Correlated Subquery Tests ===

  test("correlated EXISTS subquery"):
    val employees = Table[User]("employees")
    val depts = Table[Department]("departments")

    val query = employees.filter { e =>
      functions.exists(
        depts.filter(d => d.id === e.age)
      )
    }

    query.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Exists(
              ProtoLogicalPlan.Filter(
                ProtoExpr.Eq(
                  ProtoExpr.ColumnRef("id", _, _, _),
                  ProtoExpr.ColumnRef("age", _, _, _)
                ),
                ProtoLogicalPlan.RelationRef("departments", _, _)
              )
            ),
            ProtoLogicalPlan.RelationRef("employees", _, _)
          ) =>
        () // ok
      case other => fail(s"Expected correlated EXISTS, got $other")

  test("correlated NOT EXISTS subquery"):
    val employees = Table[User]("employees")
    val depts = Table[Department]("departments")

    val query = employees.filter { e =>
      functions.notExists(
        depts.filter(d => d.id === e.age)
      )
    }

    query.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Not(
              ProtoExpr.Exists(
                ProtoLogicalPlan.Filter(
                  ProtoExpr.Eq(
                    ProtoExpr.ColumnRef("id", _, _, _),
                    ProtoExpr.ColumnRef("age", _, _, _)
                  ),
                  ProtoLogicalPlan.RelationRef("departments", _, _)
                )
              )
            ),
            ProtoLogicalPlan.RelationRef("employees", _, _)
          ) =>
        () // ok
      case other => fail(s"Expected correlated NOT EXISTS, got $other")

  test("correlated EXISTS with compound inner predicate"):
    val employees = Table[User]("employees")
    val depts = Table[Department]("departments")

    val query = employees.filter { e =>
      functions.exists(
        depts.filter(d => (d.id === e.age) && (d.name =!= "HR"))
      )
    }

    query.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Exists(
              ProtoLogicalPlan.Filter(
                ProtoExpr.And(conditions),
                ProtoLogicalPlan.RelationRef("departments", _, _)
              )
            ),
            ProtoLogicalPlan.RelationRef("employees", _, _)
          ) =>
        assertEquals(conditions.size, 2)
      case other => fail(s"Expected correlated EXISTS with And, got $other")

  // === Scalar Subquery Tests ===

  test("scalar subquery in select"):
    val employees = Table[User]("employees")
    val depts = Table[Department]("departments")
    val salary = employees.col[Double]("salary")

    val innerQuery = employees.select(functions.max(salary))
    val query = depts.select(functions.scalarSubquery(innerQuery))

    query.plan match
      case ProtoLogicalPlan.Project(
            Vector(
              ProtoExpr.ScalarSubquery(
                ProtoLogicalPlan.Project(
                  Vector(ProtoExpr.Max(_)),
                  ProtoLogicalPlan.RelationRef("employees", _, _)
                )
              )
            ),
            ProtoLogicalPlan.RelationRef("departments", _, _)
          ) =>
        () // ok
      case other => fail(s"Expected Project(ScalarSubquery(Max)), got $other")

  test("scalar subquery in filter comparison"):
    val employees = Table[User]("employees")
    val depts = Table[Department]("departments")
    val deptId = depts.col[Int]("id")

    val subquery = depts.select(functions.min(deptId))
    val query = employees.filter(e => e.age > functions.scalarSubquery(subquery))

    query.plan match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Gt(
              ProtoExpr.ColumnRef("age", _, _, _),
              ProtoExpr.ScalarSubquery(_)
            ),
            ProtoLogicalPlan.RelationRef("employees", _, _)
          ) =>
        () // ok
      case other => fail(s"Expected Filter(Gt(age, ScalarSubquery)), got $other")

  // === Window Function Tests ===

  test("window rowNumber"):
    val employees = Table[User]("employees")
    val age = employees.col[Int]("age")
    val salary = employees.col[Double]("salary")

    import window.*

    val query = employees.select(
      rowNumber.over(Window.partitionBy(age).orderBy(salary.asc))
    )

    query.plan match
      case ProtoLogicalPlan.Project(
            Vector(winExpr),
            ProtoLogicalPlan.RelationRef("employees", _, _)
          ) =>
        winExpr match
          case ProtoExpr.WindowExpr(ProtoExpr.RowNumber(), parts, ords, None) =>
            assertEquals(parts.size, 1)
            assertEquals(ords.size, 1)
            parts.head match
              case ProtoExpr.ColumnRef("age", _, _, _) => () // ok
              case other => fail(s"Expected ColumnRef(age), got $other")
            ords.head match
              case SortOrder(ProtoExpr.ColumnRef("salary", _, _, _), SortDirection.Ascending, _) =>
                () // ok
              case other => fail(s"Expected salary ascending, got $other")
          case other => fail(s"Expected WindowExpr(RowNumber), got $other")
      case other => fail(s"Expected Project(WindowExpr(RowNumber)), got $other")

  test("window rank and denseRank"):
    val employees = Table[User]("employees")
    val salary = employees.col[Double]("salary")

    import window.*

    val rankQuery = employees.select(
      rank.over(Window.orderBy(salary.desc))
    )

    rankQuery.plan match
      case ProtoLogicalPlan.Project(Vector(winExpr), _) =>
        winExpr match
          case ProtoExpr.WindowExpr(ProtoExpr.Rank(), parts, ords, None) =>
            assertEquals(parts.size, 0)
            assertEquals(ords.size, 1)
          case other => fail(s"Expected WindowExpr(Rank), got $other")
      case other => fail(s"Expected Project with WindowExpr(Rank), got $other")

    val denseRankQuery = employees.select(
      denseRank.over(Window.orderBy(salary.desc))
    )

    denseRankQuery.plan match
      case ProtoLogicalPlan.Project(
            Vector(ProtoExpr.WindowExpr(ProtoExpr.DenseRank(), _, _, None)),
            _
          ) =>
        () // ok
      case other => fail(s"Expected WindowExpr(DenseRank), got $other")

  test("window aggregate sum"):
    val employees = Table[User]("employees")
    val age = employees.col[Int]("age")
    val salary = employees.col[Double]("salary")

    import window.*

    val query = employees.select(
      functions.sumDouble(salary).over(Window.partitionBy(age))
    )

    query.plan match
      case ProtoLogicalPlan.Project(
            Vector(winExpr),
            ProtoLogicalPlan.RelationRef("employees", _, _)
          ) =>
        winExpr match
          case ProtoExpr.WindowExpr(
                ProtoExpr.Sum(ProtoExpr.ColumnRef("salary", _, _, _)),
                parts,
                ords,
                None
              ) =>
            assertEquals(parts.size, 1)
            assertEquals(ords.size, 0)
          case other => fail(s"Expected WindowExpr(Sum), got $other")
      case other => fail(s"Expected Project with WindowExpr(Sum), got $other")

  test("window lead and lag"):
    val employees = Table[User]("employees")
    val salary = employees.col[Double]("salary")
    val age = employees.col[Int]("age")

    import window.*

    val leadQuery = employees.select(
      lead(salary).over(Window.partitionBy(age).orderBy(salary.asc))
    )

    leadQuery.plan match
      case ProtoLogicalPlan.Project(
            Vector(
              ProtoExpr.WindowExpr(
                ProtoExpr.Lead(ProtoExpr.ColumnRef("salary", _, _, _), _, None),
                _,
                _,
                None
              )
            ),
            _
          ) =>
        () // ok
      case other => fail(s"Expected WindowExpr(Lead), got $other")

    val lagQuery = employees.select(
      lag(salary, 2).over(Window.partitionBy(age).orderBy(salary.asc))
    )

    lagQuery.plan match
      case ProtoLogicalPlan.Project(
            Vector(
              ProtoExpr.WindowExpr(
                ProtoExpr.Lag(ProtoExpr.ColumnRef("salary", _, _, _), _, None),
                _,
                _,
                None
              )
            ),
            _
          ) =>
        () // ok
      case other => fail(s"Expected WindowExpr(Lag), got $other")

  test("window with frame spec"):
    val employees = Table[User]("employees")
    val age = employees.col[Int]("age")
    val salary = employees.col[Double]("salary")

    import window.*

    val query = employees.select(
      functions
        .sumDouble(salary)
        .over(
          Window
            .partitionBy(age)
            .orderBy(salary.asc)
            .rowsBetween(FrameBound.UnboundedPreceding, FrameBound.CurrentRow)
        )
    )

    query.plan match
      case ProtoLogicalPlan.Project(Vector(winExpr), _) =>
        winExpr match
          case ProtoExpr.WindowExpr(
                ProtoExpr.Sum(_),
                parts,
                ords,
                Some(
                  WindowFrame(FrameType.Rows, FrameBound.UnboundedPreceding, FrameBound.CurrentRow)
                )
              ) =>
            assertEquals(parts.size, 1)
            assertEquals(ords.size, 1)
          case other => fail(s"Expected WindowExpr with frame spec, got $other")
      case other => fail(s"Expected Project with WindowExpr, got $other")

  test("aggregate .over() extension"):
    val employees = Table[User]("employees")
    val age = employees.col[Int]("age")

    import window.*

    val query = employees.select(
      functions.count.over(Window.partitionBy(age))
    )

    query.plan match
      case ProtoLogicalPlan.Project(Vector(winExpr), _) =>
        winExpr match
          case ProtoExpr.WindowExpr(ProtoExpr.Count(_, false), parts, _, None) =>
            assertEquals(parts.size, 1)
          case other => fail(s"Expected WindowExpr(Count), got $other")
      case other => fail(s"Expected Project with WindowExpr(Count), got $other")

  // === Hint Tests ===

  test("hint produces ResolvedHint with opaque name"):
    val query =
      Table[User]("users").hint(PlanHint("BROADCAST", Vector(HintParam.StringVal("users"))))
    query.plan match
      case ProtoLogicalPlan.ResolvedHint(hints, ProtoLogicalPlan.RelationRef("users", _, _)) =>
        assertEquals(hints.size, 1)
        assertEquals(hints.head.name, "BROADCAST")
      case other => fail(s"Expected ResolvedHint, got $other")

  test("hint with int param"):
    val query = Table[User]("users").hint(PlanHint("COALESCE", Vector(HintParam.IntVal(4))))
    query.plan match
      case ProtoLogicalPlan.ResolvedHint(
            Vector(PlanHint("COALESCE", Vector(HintParam.IntVal(4)))),
            ProtoLogicalPlan.RelationRef("users", _, _)
          ) =>
        () // ok
      case other => fail(s"Expected ResolvedHint(COALESCE, 4), got $other")

  test("hint on filtered query"):
    val query =
      Table[User]("users").filter(_.age > 18).hint(PlanHint("BROADCAST", Vector.empty))
    query.plan match
      case ProtoLogicalPlan.ResolvedHint(
            Vector(PlanHint("BROADCAST", _)),
            ProtoLogicalPlan.Filter(_, ProtoLogicalPlan.RelationRef("users", _, _))
          ) =>
        () // ok
      case other => fail(s"Expected ResolvedHint(BROADCAST, Filter), got $other")
