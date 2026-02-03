package protocatalyst.sql

import protocatalyst.expr._
import protocatalyst.plan._
import protocatalyst.schema._
import protocatalyst.sql.ast._
import protocatalyst.sql.parser.SqlParser
import protocatalyst.sql.transform.{AstToProtoTransform, TransformContext}
import protocatalyst.types._

class TransformSuite extends munit.FunSuite:

  // Helper to extract SelectStatement from SqlStatement
  def asSelect(stmt: SqlStatement): SqlStatement.SelectStatement = stmt match
    case s: SqlStatement.SelectStatement => s
    case _ => fail("Expected SelectStatement").asInstanceOf[SqlStatement.SelectStatement]

  val userSchema = ProtoSchema(
    Vector(
      ProtoStructField("name", ProtoType.StringType, nullable = false),
      ProtoStructField("age", ProtoType.IntegerType, nullable = false),
      ProtoStructField("salary", ProtoType.DoubleType, nullable = false)
    )
  )

  test("transforms simple SELECT"):
    val stmt = asSelect(SqlParser.parse("SELECT name FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)
    result.toOption.get match
      case ProtoLogicalPlan.Project(exprs, _) =>
        assertEquals(exprs.size, 1)
      case _ => fail("Expected Project plan")

  test("transforms SELECT *"):
    val stmt = asSelect(SqlParser.parse("SELECT * FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)
    result.toOption.get match
      case ProtoLogicalPlan.RelationRef(name, _, _) =>
        assertEquals(name, "users")
      case _ => fail("Expected RelationRef plan (no projection for SELECT *)")

  test("transforms WHERE clause"):
    val stmt = asSelect(SqlParser.parse("SELECT name FROM users WHERE age > 18").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)

    def findFilter(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Filter] = plan match
      case f @ ProtoLogicalPlan.Filter(_, _)  => Some(f)
      case ProtoLogicalPlan.Project(_, child) => findFilter(child)
      case ProtoLogicalPlan.Sort(_, _, child) => findFilter(child)
      case ProtoLogicalPlan.Limit(_, child)   => findFilter(child)
      case ProtoLogicalPlan.Distinct(child)   => findFilter(child)
      case _                                  => None

    findFilter(result.toOption.get) match
      case Some(ProtoLogicalPlan.Filter(ProtoExpr.Gt(_, _), _)) => () // ok
      case other => fail(s"Expected Filter with Gt condition, got $other")

  test("transforms ORDER BY"):
    val stmt = asSelect(SqlParser.parse("SELECT name FROM users ORDER BY age DESC").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)

    def findSort(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Sort] = plan match
      case s @ ProtoLogicalPlan.Sort(_, _, _) => Some(s)
      case ProtoLogicalPlan.Limit(_, child)   => findSort(child)
      case _                                  => None

    findSort(result.toOption.get) match
      case Some(ProtoLogicalPlan.Sort(orders, true, _)) =>
        assertEquals(orders.size, 1)
        assertEquals(orders.head.direction, SortDirection.Descending)
      case _ => fail("Expected Sort plan")

  test("transforms LIMIT"):
    val stmt = asSelect(SqlParser.parse("SELECT name FROM users LIMIT 10").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)
    result.toOption.get match
      case ProtoLogicalPlan.Limit(10, _) => () // ok
      case _                             => fail("Expected Limit plan")

  test("transforms DISTINCT"):
    val stmt = asSelect(SqlParser.parse("SELECT DISTINCT name FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)

    def hasDistinct(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Distinct(_)       => true
      case ProtoLogicalPlan.Project(_, child) => hasDistinct(child)
      case ProtoLogicalPlan.Filter(_, child)  => hasDistinct(child)
      case ProtoLogicalPlan.Sort(_, _, child) => hasDistinct(child)
      case ProtoLogicalPlan.Limit(_, child)   => hasDistinct(child)
      case _                                  => false

    assert(hasDistinct(result.toOption.get))

  test("transforms AND expression"):
    val stmt = asSelect(
      SqlParser.parse("SELECT name FROM users WHERE age > 18 AND salary > 50000").toOption.get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)

    def findFilter(plan: ProtoLogicalPlan): Option[ProtoExpr] = plan match
      case ProtoLogicalPlan.Filter(cond, _)   => Some(cond)
      case ProtoLogicalPlan.Project(_, child) => findFilter(child)
      case _                                  => None

    findFilter(result.toOption.get) match
      case Some(ProtoExpr.And(_)) => () // ok
      case other                  => fail(s"Expected And expression, got $other")

  test("transforms OR expression"):
    val stmt =
      asSelect(SqlParser.parse("SELECT name FROM users WHERE age < 18 OR age > 65").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)

    def findFilter(plan: ProtoLogicalPlan): Option[ProtoExpr] = plan match
      case ProtoLogicalPlan.Filter(cond, _)   => Some(cond)
      case ProtoLogicalPlan.Project(_, child) => findFilter(child)
      case _                                  => None

    findFilter(result.toOption.get) match
      case Some(ProtoExpr.Or(_)) => () // ok
      case other                 => fail(s"Expected Or expression, got $other")

  test("transforms comparison operators"):
    val operators = Vector(
      ("=", "Eq"),
      ("<>", "NotEq"),
      ("<", "Lt"),
      ("<=", "LtEq"),
      (">", "Gt"),
      (">=", "GtEq")
    )

    for (op, _) <- operators do
      val stmt = asSelect(SqlParser.parse(s"SELECT name FROM users WHERE age $op 18").toOption.get)
      val result = AstToProtoTransform.transform(stmt, userSchema, "users")
      assert(result.isRight, s"Failed for operator $op")

  test("transforms IS NULL"):
    val schemaWithNullable = ProtoSchema(
      Vector(
        ProtoStructField("name", ProtoType.StringType, nullable = true),
        ProtoStructField("age", ProtoType.IntegerType, nullable = false),
        ProtoStructField("salary", ProtoType.DoubleType, nullable = false)
      )
    )

    val stmt = asSelect(SqlParser.parse("SELECT age FROM users WHERE name IS NULL").toOption.get)
    val result = AstToProtoTransform.transform(stmt, schemaWithNullable, "users")

    assert(result.isRight)

    def findFilter(plan: ProtoLogicalPlan): Option[ProtoExpr] = plan match
      case ProtoLogicalPlan.Filter(cond, _)   => Some(cond)
      case ProtoLogicalPlan.Project(_, child) => findFilter(child)
      case _                                  => None

    findFilter(result.toOption.get) match
      case Some(ProtoExpr.IsNull(_)) => () // ok
      case other                     => fail(s"Expected IsNull expression, got $other")

  test("fails on unknown column"):
    asSelect(SqlParser.parse("SELECT unknown FROM users").toOption.get)
    val ctx = TransformContext(Map("users" -> userSchema), userSchema)

    // The transform itself doesn't fail - validation happens separately
    val result = AstToProtoTransform.transformExpr(SqlExpr.ColumnRef("unknown", None), ctx)
    assert(result.isLeft)

  test("transforms qualified column with table alias"):
    asSelect(SqlParser.parse("SELECT u.name FROM users u").toOption.get)
    val ctx = TransformContext(Map("u" -> userSchema), userSchema)

    val result = AstToProtoTransform.transformExpr(SqlExpr.ColumnRef("name", Some("u")), ctx)
    assert(result.isRight)

  test("fails on wrong table qualifier"):
    asSelect(SqlParser.parse("SELECT other.name FROM users").toOption.get)
    val ctx = TransformContext(Map("users" -> userSchema), userSchema)

    val result = AstToProtoTransform.transformExpr(SqlExpr.ColumnRef("name", Some("other")), ctx)
    assert(result.isLeft)

  // Phase 4 - GROUP BY and HAVING tests

  test("transforms simple GROUP BY"):
    val stmt =
      asSelect(SqlParser.parse("SELECT name, COUNT(*) FROM users GROUP BY name").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)

    def findAggregate(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Aggregate] = plan match
      case a @ ProtoLogicalPlan.Aggregate(_, _, _) => Some(a)
      case ProtoLogicalPlan.Project(_, child)      => findAggregate(child)
      case ProtoLogicalPlan.Filter(_, child)       => findAggregate(child)
      case ProtoLogicalPlan.Sort(_, _, child)      => findAggregate(child)
      case ProtoLogicalPlan.Limit(_, child)        => findAggregate(child)
      case ProtoLogicalPlan.Distinct(child)        => findAggregate(child)
      case _                                       => None

    findAggregate(result.toOption.get) match
      case Some(ProtoLogicalPlan.Aggregate(groupingExprs, aggregateExprs, _)) =>
        assertEquals(groupingExprs.size, 1)
        assert(aggregateExprs.nonEmpty)
      case _ => fail("Expected Aggregate plan")

  test("transforms GROUP BY with multiple columns"):
    val stmt = asSelect(
      SqlParser.parse("SELECT name, age, COUNT(*) FROM users GROUP BY name, age").toOption.get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)

    def findAggregate(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Aggregate] = plan match
      case a @ ProtoLogicalPlan.Aggregate(_, _, _) => Some(a)
      case ProtoLogicalPlan.Project(_, child)      => findAggregate(child)
      case ProtoLogicalPlan.Filter(_, child)       => findAggregate(child)
      case ProtoLogicalPlan.Sort(_, _, child)      => findAggregate(child)
      case ProtoLogicalPlan.Limit(_, child)        => findAggregate(child)
      case ProtoLogicalPlan.Distinct(child)        => findAggregate(child)
      case _                                       => None

    findAggregate(result.toOption.get) match
      case Some(ProtoLogicalPlan.Aggregate(groupingExprs, _, _)) =>
        assertEquals(groupingExprs.size, 2)
      case _ => fail("Expected Aggregate plan with 2 grouping expressions")

  test("transforms GROUP BY with HAVING"):
    val stmt = asSelect(
      SqlParser
        .parse("SELECT name, COUNT(*) FROM users GROUP BY name HAVING COUNT(*) > 5")
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)

    // HAVING becomes a Filter on top of Aggregate
    def findFilterAfterAggregate(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Filter(_, ProtoLogicalPlan.Aggregate(_, _, _)) => true
      case ProtoLogicalPlan.Sort(_, _, child) => findFilterAfterAggregate(child)
      case ProtoLogicalPlan.Limit(_, child)   => findFilterAfterAggregate(child)
      case ProtoLogicalPlan.Distinct(child)   => findFilterAfterAggregate(child)
      case _                                  => false

    assert(
      findFilterAfterAggregate(result.toOption.get),
      "Expected Filter (HAVING) after Aggregate"
    )

  test("transforms SUM aggregate"):
    val stmt =
      asSelect(SqlParser.parse("SELECT name, SUM(salary) FROM users GROUP BY name").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)

    def findSum(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Aggregate(_, aggregateExprs, _) =>
        aggregateExprs.exists {
          case ProtoExpr.Sum(_) => true
          case _                => false
        }
      case ProtoLogicalPlan.Project(_, child) => findSum(child)
      case ProtoLogicalPlan.Filter(_, child)  => findSum(child)
      case ProtoLogicalPlan.Sort(_, _, child) => findSum(child)
      case ProtoLogicalPlan.Limit(_, child)   => findSum(child)
      case _                                  => false

    assert(findSum(result.toOption.get), "Expected SUM aggregate")

  test("transforms AVG, MIN, MAX aggregates"):
    val stmt = asSelect(
      SqlParser
        .parse("SELECT name, AVG(age), MIN(age), MAX(age) FROM users GROUP BY name")
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)

    def countAggregates(plan: ProtoLogicalPlan): Int = plan match
      case ProtoLogicalPlan.Aggregate(_, aggregateExprs, _) =>
        aggregateExprs.count {
          case ProtoExpr.Avg(_) | ProtoExpr.Min(_) | ProtoExpr.Max(_) => true
          case _                                                      => false
        }
      case ProtoLogicalPlan.Project(_, child) => countAggregates(child)
      case ProtoLogicalPlan.Filter(_, child)  => countAggregates(child)
      case ProtoLogicalPlan.Sort(_, _, child) => countAggregates(child)
      case ProtoLogicalPlan.Limit(_, child)   => countAggregates(child)
      case _                                  => 0

    assertEquals(countAggregates(result.toOption.get), 3)

  // Phase 12 - Advanced Grouping tests

  test("transforms GROUPING SETS"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT name, age, COUNT(*) FROM users GROUP BY GROUPING SETS ((name), (age), (name, age))"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)

    // GROUPING SETS should produce an Aggregate with all referenced columns
    def findAggregate(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Aggregate] = plan match
      case agg @ ProtoLogicalPlan.Aggregate(_, _, _) => Some(agg)
      case ProtoLogicalPlan.Project(_, child)        => findAggregate(child)
      case ProtoLogicalPlan.Filter(_, child)         => findAggregate(child)
      case _                                         => None

    val agg = findAggregate(result.toOption.get)
    assert(agg.isDefined, "Expected Aggregate in plan")
    // All grouping columns should be present
    assertEquals(agg.get.groupingExprs.size, 2) // name, age (flattened from all sets)

  test("transforms CUBE"):
    val stmt = asSelect(
      SqlParser
        .parse("SELECT name, age, COUNT(*) FROM users GROUP BY CUBE(name, age)")
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)

    def findAggregate(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Aggregate] = plan match
      case agg @ ProtoLogicalPlan.Aggregate(_, _, _) => Some(agg)
      case ProtoLogicalPlan.Project(_, child)        => findAggregate(child)
      case ProtoLogicalPlan.Filter(_, child)         => findAggregate(child)
      case _                                         => None

    val agg = findAggregate(result.toOption.get)
    assert(agg.isDefined, "Expected Aggregate in plan")
    assertEquals(agg.get.groupingExprs.size, 2) // name, age

  test("transforms ROLLUP"):
    val stmt = asSelect(
      SqlParser
        .parse("SELECT name, age, COUNT(*) FROM users GROUP BY ROLLUP(name, age)")
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)

    def findAggregate(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Aggregate] = plan match
      case agg @ ProtoLogicalPlan.Aggregate(_, _, _) => Some(agg)
      case ProtoLogicalPlan.Project(_, child)        => findAggregate(child)
      case ProtoLogicalPlan.Filter(_, child)         => findAggregate(child)
      case _                                         => None

    val agg = findAggregate(result.toOption.get)
    assert(agg.isDefined, "Expected Aggregate in plan")
    assertEquals(agg.get.groupingExprs.size, 2) // name, age

  test("transforms GROUPING function"):
    val stmt = asSelect(
      SqlParser
        .parse("SELECT name, GROUPING(name), COUNT(*) FROM users GROUP BY CUBE(name)")
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)

    def findGrouping(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, child) =>
        exprs.exists {
          case ProtoExpr.Grouping(_) => true
          case _                     => false
        } || findGrouping(child)
      case ProtoLogicalPlan.Aggregate(_, exprs, child) =>
        exprs.exists {
          case ProtoExpr.Grouping(_) => true
          case _                     => false
        } || findGrouping(child)
      case ProtoLogicalPlan.Filter(_, child) => findGrouping(child)
      case _                                 => false

    assert(findGrouping(result.toOption.get), "Expected GROUPING function in plan")

  test("transforms WITH CUBE"):
    val stmt = asSelect(
      SqlParser
        .parse("SELECT name, age, COUNT(*) FROM users GROUP BY name, age WITH CUBE")
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)

    def findAggregate(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Aggregate] = plan match
      case agg @ ProtoLogicalPlan.Aggregate(_, _, _) => Some(agg)
      case ProtoLogicalPlan.Project(_, child)        => findAggregate(child)
      case ProtoLogicalPlan.Filter(_, child)         => findAggregate(child)
      case _                                         => None

    val agg = findAggregate(result.toOption.get)
    assert(agg.isDefined, "Expected Aggregate in plan")
    assertEquals(agg.get.groupingExprs.size, 2) // name, age

  test("transforms WITH ROLLUP"):
    val stmt = asSelect(
      SqlParser
        .parse("SELECT name, age, COUNT(*) FROM users GROUP BY name, age WITH ROLLUP")
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)

    def findAggregate(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Aggregate] = plan match
      case agg @ ProtoLogicalPlan.Aggregate(_, _, _) => Some(agg)
      case ProtoLogicalPlan.Project(_, child)        => findAggregate(child)
      case ProtoLogicalPlan.Filter(_, child)         => findAggregate(child)
      case _                                         => None

    val agg = findAggregate(result.toOption.get)
    assert(agg.isDefined, "Expected Aggregate in plan")
    assertEquals(agg.get.groupingExprs.size, 2) // name, age

  // Phase 5 - CASE WHEN and CAST tests

  test("transforms CASE WHEN expression"):
    val stmt = asSelect(
      SqlParser
        .parse("SELECT CASE WHEN age > 18 THEN 'adult' ELSE 'minor' END FROM users")
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)

    def findCaseWhen(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.CaseWhen(_, _) => true
          case _                        => false
        }
      case ProtoLogicalPlan.Filter(_, child) => findCaseWhen(child)
      case _                                 => false

    assert(findCaseWhen(result.toOption.get), "Expected CaseWhen in projection")

  test("transforms CAST expression"):
    val stmt = asSelect(SqlParser.parse("SELECT CAST(age AS DOUBLE) FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)

    def findCast(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.Cast(_, ProtoType.DoubleType) => true
          case _                                       => false
        }
      case _ => false

    assert(findCast(result.toOption.get), "Expected Cast to DoubleType in projection")

  test("transforms CAST to different types"):
    val stmt = asSelect(
      SqlParser.parse("SELECT CAST(name AS STRING), CAST(age AS BIGINT) FROM users").toOption.get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight)

    def countCasts(plan: ProtoLogicalPlan): Int = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.count {
          case ProtoExpr.Cast(_, _) => true
          case _                    => false
        }
      case _ => 0

    assertEquals(countCasts(result.toOption.get), 2)

  // Phase 7 - Set Operations tests (UNION, INTERSECT, EXCEPT)

  test("transforms UNION"):
    val stmt = SqlParser.parse("SELECT name FROM users UNION SELECT name FROM users").toOption.get
    val result = AstToProtoTransform.transformStmt(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    result.toOption.get match
      case ProtoLogicalPlan.Union(children, _, _) =>
        assertEquals(children.size, 2)
      case other => fail(s"Expected Union plan, got $other")

  test("transforms UNION ALL"):
    val stmt =
      SqlParser.parse("SELECT name FROM users UNION ALL SELECT name FROM users").toOption.get
    val result = AstToProtoTransform.transformStmt(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    result.toOption.get match
      case ProtoLogicalPlan.Union(children, _, _) =>
        assertEquals(children.size, 2)
      case other => fail(s"Expected Union plan, got $other")

  test("transforms INTERSECT"):
    val stmt =
      SqlParser.parse("SELECT name FROM users INTERSECT SELECT name FROM users").toOption.get
    val result = AstToProtoTransform.transformStmt(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    result.toOption.get match
      case ProtoLogicalPlan.Intersect(_, _, isAll) =>
        assertEquals(isAll, false)
      case other => fail(s"Expected Intersect plan, got $other")

  test("transforms INTERSECT ALL"):
    val stmt =
      SqlParser.parse("SELECT name FROM users INTERSECT ALL SELECT name FROM users").toOption.get
    val result = AstToProtoTransform.transformStmt(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    result.toOption.get match
      case ProtoLogicalPlan.Intersect(_, _, isAll) =>
        assertEquals(isAll, true)
      case other => fail(s"Expected Intersect plan, got $other")

  test("transforms EXCEPT"):
    val stmt = SqlParser.parse("SELECT name FROM users EXCEPT SELECT name FROM users").toOption.get
    val result = AstToProtoTransform.transformStmt(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    result.toOption.get match
      case ProtoLogicalPlan.Except(_, _, isAll) =>
        assertEquals(isAll, false)
      case other => fail(s"Expected Except plan, got $other")

  test("transforms EXCEPT ALL"):
    val stmt =
      SqlParser.parse("SELECT name FROM users EXCEPT ALL SELECT name FROM users").toOption.get
    val result = AstToProtoTransform.transformStmt(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    result.toOption.get match
      case ProtoLogicalPlan.Except(_, _, isAll) =>
        assertEquals(isAll, true)
      case other => fail(s"Expected Except plan, got $other")

  test("transforms chained set operations"):
    val stmt = SqlParser
      .parse("SELECT name FROM users UNION SELECT name FROM users UNION SELECT name FROM users")
      .toOption
      .get
    val result = AstToProtoTransform.transformStmt(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    // Chained UNION creates nested Union plans
    def countUnions(plan: ProtoLogicalPlan): Int = plan match
      case ProtoLogicalPlan.Union(children, _, _) =>
        1 + children.map(countUnions).sum
      case _ => 0

    // Two UNION operations means 2 union nodes total (left-associative nesting)
    assertEquals(countUnions(result.toOption.get), 2)

  test("transforms set operation with WHERE clause"):
    val stmt = SqlParser
      .parse(
        "SELECT name FROM users WHERE age > 18 UNION SELECT name FROM users WHERE salary > 50000"
      )
      .toOption
      .get
    val result = AstToProtoTransform.transformStmt(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    result.toOption.get match
      case ProtoLogicalPlan.Union(children, _, _) =>
        assertEquals(children.size, 2)
        // Both children should contain filters
        def hasFilter(plan: ProtoLogicalPlan): Boolean = plan match
          case ProtoLogicalPlan.Filter(_, _)      => true
          case ProtoLogicalPlan.Project(_, child) => hasFilter(child)
          case _                                  => false
        assert(children.forall(hasFilter), "Expected both sides to have filters")
      case other => fail(s"Expected Union plan, got $other")

  // Note: ORDER BY/LIMIT after set operations requires special handling
  // to apply to the compound result. Testing basic set operations for now.
  test("transforms UNION with multiple projections"):
    val stmt =
      SqlParser.parse("SELECT name, age FROM users UNION SELECT name, age FROM users").toOption.get
    val result = AstToProtoTransform.transformStmt(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    result.toOption.get match
      case ProtoLogicalPlan.Union(children, _, _) =>
        assertEquals(children.size, 2)
      case other => fail(s"Expected Union plan, got $other")

  // Phase 8 - Window Functions tests

  test("transforms ROW_NUMBER window function"):
    val stmt = asSelect(
      SqlParser.parse("SELECT name, ROW_NUMBER() OVER (ORDER BY age) FROM users").toOption.get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findWindowExpr(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.WindowExpr(ProtoExpr.RowNumber(), _, _, _) => true
          case _                                                    => false
        }
      case _ => false

    assert(findWindowExpr(result.toOption.get), "Expected WindowExpr with RowNumber")

  test("transforms RANK window function"):
    val stmt = asSelect(
      SqlParser.parse("SELECT name, RANK() OVER (ORDER BY salary DESC) FROM users").toOption.get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findWindowExpr(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.WindowExpr(ProtoExpr.Rank(), _, orderSpec, _) =>
            orderSpec.nonEmpty && orderSpec.head.direction == SortDirection.Descending
          case _ => false
        }
      case _ => false

    assert(findWindowExpr(result.toOption.get), "Expected WindowExpr with Rank and DESC order")

  test("transforms SUM window function with PARTITION BY"):
    val stmt = asSelect(
      SqlParser.parse("SELECT name, SUM(salary) OVER (PARTITION BY name) FROM users").toOption.get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findWindowExpr(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.WindowExpr(ProtoExpr.Sum(_), partitionSpec, _, _) =>
            partitionSpec.nonEmpty
          case _ => false
        }
      case _ => false

    assert(findWindowExpr(result.toOption.get), "Expected WindowExpr with Sum and PARTITION BY")

  test("transforms LAG window function"):
    val stmt = asSelect(
      SqlParser.parse("SELECT name, LAG(salary) OVER (ORDER BY age) FROM users").toOption.get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findWindowExpr(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.WindowExpr(ProtoExpr.Lag(_, _, _), _, _, _) => true
          case _                                                     => false
        }
      case _ => false

    assert(findWindowExpr(result.toOption.get), "Expected WindowExpr with Lag")

  test("transforms window function with frame specification"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT SUM(salary) OVER (ORDER BY age ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM users"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findWindowWithFrame(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.WindowExpr(_, _, _, Some(frame)) =>
            frame.frameType == protocatalyst.expr.FrameType.Rows &&
            frame.lower == protocatalyst.expr.FrameBound.UnboundedPreceding &&
            frame.upper == protocatalyst.expr.FrameBound.CurrentRow
          case _ => false
        }
      case _ => false

    assert(findWindowWithFrame(result.toOption.get), "Expected WindowExpr with ROWS frame")

  // Phase 9 - CTE (Common Table Expression) tests

  test("transforms simple CTE"):
    val stmt = SqlParser
      .parse("""
      WITH active_users AS (
        SELECT name, age FROM users WHERE age > 18
      )
      SELECT * FROM active_users
    """)
      .toOption
      .get
    val result = AstToProtoTransform.transformStmt(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    result.toOption.get match
      case ProtoLogicalPlan.With(cteRelations, recursive, child) =>
        assertEquals(cteRelations.size, 1)
        assertEquals(cteRelations.head._1, "active_users")
        assertEquals(recursive, false)
        // Verify CTE plan contains a filter
        cteRelations.head._2 match
          case ProtoLogicalPlan.SubqueryAlias("active_users", inner) =>
            def hasFilter(p: ProtoLogicalPlan): Boolean = p match
              case ProtoLogicalPlan.Filter(_, _)   => true
              case ProtoLogicalPlan.Project(_, ch) => hasFilter(ch)
              case _                               => false
            assert(hasFilter(inner), "Expected filter in CTE")
          case other => fail(s"Expected SubqueryAlias, got $other")
      case other => fail(s"Expected With plan, got $other")

  test("transforms multiple CTEs"):
    val stmt = SqlParser
      .parse("""
      WITH
        young_users AS (
          SELECT name FROM users WHERE age < 30
        ),
        old_users AS (
          SELECT name FROM users WHERE age >= 30
        )
      SELECT * FROM young_users
    """)
      .toOption
      .get
    val result = AstToProtoTransform.transformStmt(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    result.toOption.get match
      case ProtoLogicalPlan.With(cteRelations, _, _) =>
        assertEquals(cteRelations.size, 2)
        assertEquals(cteRelations(0)._1, "young_users")
        assertEquals(cteRelations(1)._1, "old_users")
      case other => fail(s"Expected With plan, got $other")

  test("transforms CTE with aggregate in inner query"):
    val stmt = SqlParser
      .parse("""
      WITH salary_stats AS (
        SELECT name, AVG(salary) AS avg_salary
        FROM users
        GROUP BY name
      )
      SELECT * FROM salary_stats
    """)
      .toOption
      .get
    val result = AstToProtoTransform.transformStmt(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    result.toOption.get match
      case ProtoLogicalPlan.With(cteRelations, _, _) =>
        // Verify CTE plan contains an aggregate
        def hasAggregate(p: ProtoLogicalPlan): Boolean = p match
          case ProtoLogicalPlan.Aggregate(_, _, _)   => true
          case ProtoLogicalPlan.SubqueryAlias(_, ch) => hasAggregate(ch)
          case _                                     => false
        assert(hasAggregate(cteRelations.head._2), "Expected Aggregate in CTE")
      case other => fail(s"Expected With plan, got $other")

  test("transforms CTE with UNION in main query"):
    val stmt = SqlParser
      .parse("""
      WITH high_earners AS (
        SELECT name FROM users WHERE salary > 100000
      )
      SELECT name FROM high_earners WHERE age > 30
      UNION
      SELECT name FROM high_earners WHERE age <= 30
    """)
      .toOption
      .get
    val result = AstToProtoTransform.transformStmt(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    result.toOption.get match
      case ProtoLogicalPlan.With(cteRelations, _, child) =>
        assertEquals(cteRelations.size, 1)
        // Main query should be a Union
        child match
          case ProtoLogicalPlan.Union(_, _, _) => () // ok
          case other                           => fail(s"Expected Union as main query, got $other")
      case other => fail(s"Expected With plan, got $other")

  test("transforms recursive CTE"):
    val stmt = SqlParser
      .parse("""
      WITH RECURSIVE cte AS (
        SELECT * FROM users WHERE age = 1
      )
      SELECT * FROM cte
    """)
      .toOption
      .get
    val result = AstToProtoTransform.transformStmt(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    result.toOption.get match
      case ProtoLogicalPlan.With(cteRelations, recursive, _) =>
        assertEquals(cteRelations.size, 1)
        assertEquals(cteRelations.head._1, "cte")
        assert(recursive, "Expected recursive flag to be true")
      case other => fail(s"Expected With plan, got $other")

  test("transforms recursive CTE with UNION ALL"):
    // Simplified test: recursive CTE with UNION ALL
    // Note: Full recursive CTE column resolution would require schema inference from the anchor query
    val stmt = SqlParser
      .parse("""
      WITH RECURSIVE hierarchy AS (
        SELECT name, age FROM users WHERE age = 1
        UNION ALL
        SELECT name, age FROM users WHERE age < 10
      )
      SELECT * FROM hierarchy
    """)
      .toOption
      .get
    val result = AstToProtoTransform.transformStmt(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    result.toOption.get match
      case ProtoLogicalPlan.With(cteRelations, recursive, _) =>
        assertEquals(cteRelations.size, 1)
        assertEquals(cteRelations.head._1, "hierarchy")
        assert(recursive, "Expected recursive flag to be true")
        // The inner CTE should contain a UNION
        cteRelations.head._2 match
          case ProtoLogicalPlan.SubqueryAlias(_, ProtoLogicalPlan.Union(_, _, _)) =>
            () // ok
          case other =>
            fail(s"Expected SubqueryAlias containing Union, got $other")
      case other => fail(s"Expected With plan, got $other")

  test("transforms non-recursive CTE has recursive flag false"):
    val stmt = SqlParser
      .parse("""
      WITH simple_cte AS (
        SELECT * FROM users WHERE age > 25
      )
      SELECT * FROM simple_cte
    """)
      .toOption
      .get
    val result = AstToProtoTransform.transformStmt(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    result.toOption.get match
      case ProtoLogicalPlan.With(_, recursive, _) =>
        assert(!recursive, "Expected recursive flag to be false for non-recursive CTE")
      case other => fail(s"Expected With plan, got $other")

  // === Phase 10: String and Math Functions ===

  test("transforms TRIM function"):
    val stmt = asSelect(SqlParser.parse("SELECT TRIM(name) FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

  test("transforms LENGTH function"):
    val stmt = asSelect(SqlParser.parse("SELECT LENGTH(name) FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

  test("transforms REPLACE function"):
    val stmt = asSelect(SqlParser.parse("SELECT REPLACE(name, 'a', 'b') FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

  test("transforms ABS function"):
    val stmt = asSelect(SqlParser.parse("SELECT ABS(salary) FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

  test("transforms CEIL function"):
    val stmt = asSelect(SqlParser.parse("SELECT CEIL(salary) FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

  test("transforms FLOOR function"):
    val stmt = asSelect(SqlParser.parse("SELECT FLOOR(salary) FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

  test("transforms ROUND function"):
    val stmt = asSelect(SqlParser.parse("SELECT ROUND(salary, 2) FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

  test("transforms SQRT function"):
    val stmt = asSelect(SqlParser.parse("SELECT SQRT(age) FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

  test("transforms POW function"):
    val stmt = asSelect(SqlParser.parse("SELECT POW(age, 2) FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

  test("transforms MOD function"):
    val stmt = asSelect(SqlParser.parse("SELECT MOD(age, 10) FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

  test("transforms NULLIF function"):
    val stmt = asSelect(SqlParser.parse("SELECT NULLIF(name, 'Unknown') FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

  test("transforms IFNULL function"):
    val stmt = asSelect(SqlParser.parse("SELECT IFNULL(name, 'default') FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

  test("transforms IF function"):
    val stmt =
      asSelect(SqlParser.parse("SELECT IF(age > 18, 'adult', 'minor') FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

  test("transforms complex expression with multiple functions"):
    val stmt = asSelect(
      SqlParser
        .parse("""
      SELECT
        UPPER(TRIM(name)) AS clean_name,
        ROUND(salary * 1.1, 2) AS new_salary,
        ABS(age - 30) AS age_diff
      FROM users
    """).toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

  // === Phase 11: Date/Time Functions ===

  test("transforms CURRENT_DATE function"):
    val stmt = asSelect(SqlParser.parse("SELECT CURRENT_DATE() FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

    def findCurrentDate(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.CurrentDate() => true
          case _                       => false
        }
      case _ => false

    assert(findCurrentDate(result.toOption.get), "Expected CurrentDate in projection")

  test("transforms CURRENT_TIMESTAMP function"):
    val stmt = asSelect(SqlParser.parse("SELECT CURRENT_TIMESTAMP() FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

    def findCurrentTimestamp(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.CurrentTimestamp() => true
          case _                            => false
        }
      case _ => false

    assert(findCurrentTimestamp(result.toOption.get), "Expected CurrentTimestamp in projection")

  test("transforms NOW function (alias for CURRENT_TIMESTAMP)"):
    val stmt = asSelect(SqlParser.parse("SELECT NOW() FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

    def findCurrentTimestamp(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.CurrentTimestamp() => true
          case _                            => false
        }
      case _ => false

    assert(findCurrentTimestamp(result.toOption.get), "Expected CurrentTimestamp from NOW()")

  test("transforms DATE_ADD function"):
    val stmt = asSelect(SqlParser.parse("SELECT DATE_ADD(CURRENT_DATE(), 30) FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

    def findDateAdd(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.DateAdd(_, _) => true
          case _                       => false
        }
      case _ => false

    assert(findDateAdd(result.toOption.get), "Expected DateAdd in projection")

  test("transforms DATE_SUB function"):
    val stmt = asSelect(SqlParser.parse("SELECT DATE_SUB(CURRENT_DATE(), 30) FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

    def findDateSub(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.DateSub(_, _) => true
          case _                       => false
        }
      case _ => false

    assert(findDateSub(result.toOption.get), "Expected DateSub in projection")

  test("transforms DATE_DIFF function"):
    val stmt = asSelect(
      SqlParser.parse("SELECT DATE_DIFF(CURRENT_DATE(), CURRENT_DATE()) FROM users").toOption.get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

    def findDateDiff(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.DateDiff(_, _) => true
          case _                        => false
        }
      case _ => false

    assert(findDateDiff(result.toOption.get), "Expected DateDiff in projection")

  test("transforms EXTRACT function"):
    val stmt = asSelect(
      SqlParser.parse("SELECT EXTRACT(YEAR FROM CURRENT_TIMESTAMP()) FROM users").toOption.get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

    def findExtract(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.Extract(DateTimeField.Year, _) => true
          case _                                        => false
        }
      case _ => false

    assert(findExtract(result.toOption.get), "Expected Extract with Year field")

  test("transforms EXTRACT with different fields"):
    val fields = Vector("YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND")
    for field <- fields do
      val stmt = asSelect(
        SqlParser.parse(s"SELECT EXTRACT($field FROM CURRENT_TIMESTAMP()) FROM users").toOption.get
      )
      val result = AstToProtoTransform.transform(stmt, userSchema, "users")
      assert(result.isRight, s"Failed for EXTRACT($field)")

  test("transforms DATE_TRUNC function"):
    val stmt = asSelect(
      SqlParser.parse("SELECT DATE_TRUNC('MONTH', CURRENT_TIMESTAMP()) FROM users").toOption.get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

    def findDateTrunc(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.DateTrunc(DateTimeField.Month, _) => true
          case _                                           => false
        }
      case _ => false

    assert(findDateTrunc(result.toOption.get), "Expected DateTrunc with Month field")

  test("transforms TO_DATE function"):
    val stmt = asSelect(SqlParser.parse("SELECT TO_DATE('2024-01-15') FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

    def findToDate(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.ToDate(_, _) => true
          case _                      => false
        }
      case _ => false

    assert(findToDate(result.toOption.get), "Expected ToDate in projection")

  test("transforms TO_DATE with format"):
    val stmt = asSelect(
      SqlParser.parse("SELECT TO_DATE('15/01/2024', 'dd/MM/yyyy') FROM users").toOption.get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

    def findToDateWithFormat(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.ToDate(_, Some(_)) => true
          case _                            => false
        }
      case _ => false

    assert(findToDateWithFormat(result.toOption.get), "Expected ToDate with format")

  test("transforms TO_TIMESTAMP function"):
    val stmt = asSelect(
      SqlParser.parse("SELECT TO_TIMESTAMP('2024-01-15 10:30:00') FROM users").toOption.get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

    def findToTimestamp(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.ToTimestamp(_, _) => true
          case _                           => false
        }
      case _ => false

    assert(findToTimestamp(result.toOption.get), "Expected ToTimestamp in projection")

  test("transforms YEAR function"):
    val stmt = asSelect(SqlParser.parse("SELECT YEAR(CURRENT_DATE()) FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

    def findYear(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.Year(_) => true
          case _                 => false
        }
      case _ => false

    assert(findYear(result.toOption.get), "Expected Year in projection")

  test("transforms MONTH function"):
    val stmt = asSelect(SqlParser.parse("SELECT MONTH(CURRENT_DATE()) FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

    def findMonth(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.Month(_) => true
          case _                  => false
        }
      case _ => false

    assert(findMonth(result.toOption.get), "Expected Month in projection")

  test("transforms DAY function"):
    val stmt = asSelect(SqlParser.parse("SELECT DAY(CURRENT_DATE()) FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

    def findDay(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.DayOfMonth(_) => true
          case _                       => false
        }
      case _ => false

    assert(findDay(result.toOption.get), "Expected DayOfMonth in projection")

  test("transforms HOUR function"):
    val stmt = asSelect(SqlParser.parse("SELECT HOUR(CURRENT_TIMESTAMP()) FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

    def findHour(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.Hour(_) => true
          case _                 => false
        }
      case _ => false

    assert(findHour(result.toOption.get), "Expected Hour in projection")

  test("transforms MINUTE function"):
    val stmt = asSelect(SqlParser.parse("SELECT MINUTE(CURRENT_TIMESTAMP()) FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

    def findMinute(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.Minute(_) => true
          case _                   => false
        }
      case _ => false

    assert(findMinute(result.toOption.get), "Expected Minute in projection")

  test("transforms SECOND function"):
    val stmt = asSelect(SqlParser.parse("SELECT SECOND(CURRENT_TIMESTAMP()) FROM users").toOption.get)
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

    def findSecond(plan: ProtoLogicalPlan): Boolean = plan match
      case ProtoLogicalPlan.Project(exprs, _) =>
        exprs.exists {
          case ProtoExpr.Second(_) => true
          case _                   => false
        }
      case _ => false

    assert(findSecond(result.toOption.get), "Expected Second in projection")

  test("transforms complex date/time query"):
    val stmt = asSelect(
      SqlParser
        .parse("""
      SELECT
        YEAR(CURRENT_DATE()) AS current_year,
        MONTH(CURRENT_DATE()) AS current_month,
        DATE_ADD(CURRENT_DATE(), 30) AS next_month,
        EXTRACT(HOUR FROM CURRENT_TIMESTAMP()) AS current_hour
      FROM users
    """).toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")
    assert(result.isRight)

  // === Phase 13: PIVOT/UNPIVOT ===

  val salesSchema = ProtoSchema(
    Vector(
      ProtoStructField("product", ProtoType.StringType, nullable = false),
      ProtoStructField("region", ProtoType.StringType, nullable = false),
      ProtoStructField("amount", ProtoType.DoubleType, nullable = false),
      ProtoStructField("q1", ProtoType.DoubleType, nullable = true),
      ProtoStructField("q2", ProtoType.DoubleType, nullable = true),
      ProtoStructField("q3", ProtoType.DoubleType, nullable = true),
      ProtoStructField("q4", ProtoType.DoubleType, nullable = true)
    )
  )

  test("transforms simple PIVOT"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT * FROM sales PIVOT (SUM(amount) FOR region IN ('East', 'West', 'North'))"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, salesSchema, "sales")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findPivot(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Pivot] = plan match
      case p @ ProtoLogicalPlan.Pivot(_, _, _, _, _) => Some(p)
      case ProtoLogicalPlan.Project(_, child)        => findPivot(child)
      case ProtoLogicalPlan.Filter(_, child)         => findPivot(child)
      case ProtoLogicalPlan.SubqueryAlias(_, child)  => findPivot(child)
      case _                                         => None

    val pivot = findPivot(result.toOption.get)
    assert(pivot.isDefined, "Expected Pivot in plan")
    assertEquals(pivot.get.pivotValues.size, 3)
    assertEquals(pivot.get.aggregates.size, 1)

  test("transforms PIVOT with multiple aggregates"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT * FROM sales PIVOT (SUM(amount), AVG(amount) FOR region IN ('East', 'West'))"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, salesSchema, "sales")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findPivot(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Pivot] = plan match
      case p @ ProtoLogicalPlan.Pivot(_, _, _, _, _) => Some(p)
      case ProtoLogicalPlan.Project(_, child)        => findPivot(child)
      case ProtoLogicalPlan.SubqueryAlias(_, child)  => findPivot(child)
      case _                                         => None

    val pivot = findPivot(result.toOption.get)
    assert(pivot.isDefined, "Expected Pivot in plan")
    assertEquals(pivot.get.aggregates.size, 2)
    assertEquals(pivot.get.pivotValues.size, 2)

  test("transforms PIVOT with value aliases"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT * FROM sales PIVOT (SUM(amount) FOR region IN ('East' AS e, 'West' AS w))"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, salesSchema, "sales")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findPivot(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Pivot] = plan match
      case p @ ProtoLogicalPlan.Pivot(_, _, _, _, _) => Some(p)
      case ProtoLogicalPlan.Project(_, child)        => findPivot(child)
      case ProtoLogicalPlan.SubqueryAlias(_, child)  => findPivot(child)
      case _                                         => None

    val pivot = findPivot(result.toOption.get)
    assert(pivot.isDefined, "Expected Pivot in plan")
    assertEquals(pivot.get.pivotValues.size, 2)

  test("transforms simple UNPIVOT"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT * FROM sales UNPIVOT (amount FOR quarter IN (q1, q2, q3, q4))"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, salesSchema, "sales")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findUnpivot(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Unpivot] = plan match
      case u @ ProtoLogicalPlan.Unpivot(_, _, _, _, _) => Some(u)
      case ProtoLogicalPlan.Project(_, child)          => findUnpivot(child)
      case ProtoLogicalPlan.Filter(_, child)           => findUnpivot(child)
      case ProtoLogicalPlan.SubqueryAlias(_, child)    => findUnpivot(child)
      case _                                           => None

    val unpivot = findUnpivot(result.toOption.get)
    assert(unpivot.isDefined, "Expected Unpivot in plan")
    assertEquals(unpivot.get.valueColumnName, "amount")
    assertEquals(unpivot.get.variableColumnName, "quarter")
    assertEquals(unpivot.get.columns.size, 4)

  test("transforms UNPIVOT with INCLUDE NULLS"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT * FROM sales UNPIVOT INCLUDE NULLS (amount FOR quarter IN (q1, q2))"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, salesSchema, "sales")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findUnpivot(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Unpivot] = plan match
      case u @ ProtoLogicalPlan.Unpivot(_, _, _, _, _) => Some(u)
      case ProtoLogicalPlan.Project(_, child)          => findUnpivot(child)
      case ProtoLogicalPlan.SubqueryAlias(_, child)    => findUnpivot(child)
      case _                                           => None

    val unpivot = findUnpivot(result.toOption.get)
    assert(unpivot.isDefined, "Expected Unpivot in plan")
    assert(unpivot.get.includeNulls, "Expected includeNulls to be true")

  test("transforms UNPIVOT with EXCLUDE NULLS"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT * FROM sales UNPIVOT EXCLUDE NULLS (amount FOR quarter IN (q1, q2))"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, salesSchema, "sales")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findUnpivot(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Unpivot] = plan match
      case u @ ProtoLogicalPlan.Unpivot(_, _, _, _, _) => Some(u)
      case ProtoLogicalPlan.Project(_, child)          => findUnpivot(child)
      case ProtoLogicalPlan.SubqueryAlias(_, child)    => findUnpivot(child)
      case _                                           => None

    val unpivot = findUnpivot(result.toOption.get)
    assert(unpivot.isDefined, "Expected Unpivot in plan")
    assert(!unpivot.get.includeNulls, "Expected includeNulls to be false")

  test("transforms UNPIVOT with column aliases"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT * FROM sales UNPIVOT (amount FOR quarter IN (q1 AS first, q2 AS second))"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, salesSchema, "sales")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findUnpivot(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Unpivot] = plan match
      case u @ ProtoLogicalPlan.Unpivot(_, _, _, _, _) => Some(u)
      case ProtoLogicalPlan.Project(_, child)          => findUnpivot(child)
      case ProtoLogicalPlan.SubqueryAlias(_, child)    => findUnpivot(child)
      case _                                           => None

    val unpivot = findUnpivot(result.toOption.get)
    assert(unpivot.isDefined, "Expected Unpivot in plan")
    assertEquals(unpivot.get.columns.size, 2)

  // === Phase 14: LATERAL Subquery ===

  test("transforms simple LATERAL subquery"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT * FROM users CROSS JOIN LATERAL (SELECT name FROM users LIMIT 5) AS sub"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findJoin(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Join] = plan match
      case j @ ProtoLogicalPlan.Join(_, _, _, _) => Some(j)
      case ProtoLogicalPlan.Project(_, child)    => findJoin(child)
      case ProtoLogicalPlan.Filter(_, child)     => findJoin(child)
      case _                                     => None

    val join = findJoin(result.toOption.get)
    assert(join.isDefined, "Expected Join in plan")
    assertEquals(join.get.joinType, protocatalyst.plan.JoinType.Cross)

  test("transforms LATERAL with LEFT JOIN"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT * FROM users LEFT JOIN LATERAL (SELECT name FROM users WHERE age > 18) AS sub ON true"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findJoin(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Join] = plan match
      case j @ ProtoLogicalPlan.Join(_, _, _, _) => Some(j)
      case ProtoLogicalPlan.Project(_, child)    => findJoin(child)
      case ProtoLogicalPlan.Filter(_, child)     => findJoin(child)
      case _                                     => None

    val join = findJoin(result.toOption.get)
    assert(join.isDefined, "Expected Join in plan")
    assertEquals(join.get.joinType, protocatalyst.plan.JoinType.LeftOuter)

  test("transforms LATERAL with comma syntax"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT * FROM users, LATERAL (SELECT name FROM users) AS sub"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findJoin(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Join] = plan match
      case j @ ProtoLogicalPlan.Join(_, _, _, _) => Some(j)
      case ProtoLogicalPlan.Project(_, child)    => findJoin(child)
      case ProtoLogicalPlan.Filter(_, child)     => findJoin(child)
      case _                                     => None

    val join = findJoin(result.toOption.get)
    assert(join.isDefined, "Expected Join in plan")
    assertEquals(join.get.joinType, protocatalyst.plan.JoinType.Cross)

  test("transforms nested LATERAL subqueries"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT * FROM users, LATERAL (SELECT name FROM users) AS a, LATERAL (SELECT age FROM users) AS b"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def countJoins(plan: ProtoLogicalPlan): Int = plan match
      case ProtoLogicalPlan.Join(left, _, _, _) => 1 + countJoins(left)
      case ProtoLogicalPlan.Project(_, child)   => countJoins(child)
      case ProtoLogicalPlan.Filter(_, child)    => countJoins(child)
      case _                                    => 0

    assertEquals(countJoins(result.toOption.get), 2)

  // === Phase 15: LATERAL VIEW ===

  // Schema for LATERAL VIEW tests with an array field
  val arraySchema = ProtoSchema(
    Vector(
      ProtoStructField("name", ProtoType.StringType, nullable = false),
      ProtoStructField("tags", ProtoType.ArrayType(ProtoType.StringType, true), nullable = true),
      ProtoStructField("attributes", ProtoType.MapType(ProtoType.StringType, ProtoType.StringType, true), nullable = true)
    )
  )

  test("transforms simple LATERAL VIEW EXPLODE"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT * FROM users LATERAL VIEW EXPLODE(tags) t AS tag"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, arraySchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findGenerate(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Generate] = plan match
      case g @ ProtoLogicalPlan.Generate(_, _, _, _) => Some(g)
      case ProtoLogicalPlan.Project(_, child)        => findGenerate(child)
      case ProtoLogicalPlan.Filter(_, child)         => findGenerate(child)
      case _                                         => None

    val generate = findGenerate(result.toOption.get)
    assert(generate.isDefined, "Expected Generate in plan")
    assertEquals(generate.get.outer, false)
    assertEquals(generate.get.generatorOutput, Vector("tag"))

  test("transforms LATERAL VIEW OUTER EXPLODE"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT * FROM users LATERAL VIEW OUTER EXPLODE(tags) t AS tag"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, arraySchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findGenerate(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Generate] = plan match
      case g @ ProtoLogicalPlan.Generate(_, _, _, _) => Some(g)
      case ProtoLogicalPlan.Project(_, child)        => findGenerate(child)
      case _                                         => None

    val generate = findGenerate(result.toOption.get)
    assert(generate.isDefined, "Expected Generate in plan")
    assertEquals(generate.get.outer, true)

  test("transforms LATERAL VIEW with POSEXPLODE"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT * FROM users LATERAL VIEW POSEXPLODE(tags) t AS pos, tag"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, arraySchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findGenerate(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Generate] = plan match
      case g @ ProtoLogicalPlan.Generate(_, _, _, _) => Some(g)
      case ProtoLogicalPlan.Project(_, child)        => findGenerate(child)
      case _                                         => None

    val generate = findGenerate(result.toOption.get)
    assert(generate.isDefined, "Expected Generate in plan")
    assertEquals(generate.get.generatorOutput, Vector("pos", "tag"))
    generate.get.generator match
      case ProtoExpr.PosExplode(_) => () // ok
      case _ => fail(s"Expected PosExplode, got ${generate.get.generator}")

  test("transforms multiple LATERAL VIEWs"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT * FROM users LATERAL VIEW EXPLODE(tags) t1 AS tag1 LATERAL VIEW EXPLODE(tags) t2 AS tag2"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, arraySchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def countGenerates(plan: ProtoLogicalPlan): Int = plan match
      case ProtoLogicalPlan.Generate(_, _, _, child) => 1 + countGenerates(child)
      case ProtoLogicalPlan.Project(_, child)        => countGenerates(child)
      case ProtoLogicalPlan.Filter(_, child)         => countGenerates(child)
      case _                                         => 0

    assertEquals(countGenerates(result.toOption.get), 2)

  test("transforms LATERAL VIEW with map EXPLODE"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT * FROM users LATERAL VIEW EXPLODE(attributes) t AS key, value"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, arraySchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findGenerate(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Generate] = plan match
      case g @ ProtoLogicalPlan.Generate(_, _, _, _) => Some(g)
      case ProtoLogicalPlan.Project(_, child)        => findGenerate(child)
      case _                                         => None

    val generate = findGenerate(result.toOption.get)
    assert(generate.isDefined, "Expected Generate in plan")
    assertEquals(generate.get.generatorOutput, Vector("key", "value"))

  // === Phase 16: VALUES Clause ===

  test("transforms simple VALUES clause"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT * FROM (VALUES (1, 'a'), (2, 'b'), (3, 'c')) AS t(id, name)"
        )
        .toOption
        .get
    )
    // For VALUES, we don't need an external schema - the types are inferred from literals
    val dummySchema = ProtoSchema(Vector.empty)
    val result = AstToProtoTransform.transform(stmt, dummySchema, "t")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findValues(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Values] = plan match
      case v @ ProtoLogicalPlan.Values(_, _) => Some(v)
      case ProtoLogicalPlan.Project(_, child) => findValues(child)
      case ProtoLogicalPlan.SubqueryAlias(_, child) => findValues(child)
      case _ => None

    val values = findValues(result.toOption.get)
    assert(values.isDefined, "Expected Values in plan")
    assertEquals(values.get.rows.size, 3)

  test("transforms VALUES with single row"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT * FROM (VALUES (42, 'hello')) AS single(num, text)"
        )
        .toOption
        .get
    )
    val dummySchema = ProtoSchema(Vector.empty)
    val result = AstToProtoTransform.transform(stmt, dummySchema, "single")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findValues(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Values] = plan match
      case v @ ProtoLogicalPlan.Values(_, _) => Some(v)
      case ProtoLogicalPlan.Project(_, child) => findValues(child)
      case ProtoLogicalPlan.SubqueryAlias(_, child) => findValues(child)
      case _ => None

    val values = findValues(result.toOption.get)
    assert(values.isDefined, "Expected Values in plan")
    assertEquals(values.get.rows.size, 1)

  test("transforms VALUES with various literal types"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT * FROM (VALUES (1, 2.5, 'text', true)) AS t(a, b, c, d)"
        )
        .toOption
        .get
    )
    val dummySchema = ProtoSchema(Vector.empty)
    val result = AstToProtoTransform.transform(stmt, dummySchema, "t")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findValues(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Values] = plan match
      case v @ ProtoLogicalPlan.Values(_, _) => Some(v)
      case ProtoLogicalPlan.Project(_, child) => findValues(child)
      case ProtoLogicalPlan.SubqueryAlias(_, child) => findValues(child)
      case _ => None

    val values = findValues(result.toOption.get)
    assert(values.isDefined, "Expected Values in plan")
    val row = values.get.rows.head
    assertEquals(row.size, 4)

  test("transforms VALUES infers schema from column aliases"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT * FROM (VALUES (1, 'a')) AS t(id, name)"
        )
        .toOption
        .get
    )
    val dummySchema = ProtoSchema(Vector.empty)
    val result = AstToProtoTransform.transform(stmt, dummySchema, "t")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findValues(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.Values] = plan match
      case v @ ProtoLogicalPlan.Values(_, _) => Some(v)
      case ProtoLogicalPlan.Project(_, child) => findValues(child)
      case ProtoLogicalPlan.SubqueryAlias(_, child) => findValues(child)
      case _ => None

    val values = findValues(result.toOption.get)
    assert(values.isDefined, "Expected Values in plan")
    // Schema should have column names from aliases
    assertEquals(values.get.schema.fields.map(_.name), Vector("id", "name"))

  // === Phase 17: Query Hints ===

  test("transforms query with BROADCAST hint"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT /*+ BROADCAST(users) */ * FROM users"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findHint(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.ResolvedHint] = plan match
      case h @ ProtoLogicalPlan.ResolvedHint(_, _) => Some(h)
      case ProtoLogicalPlan.Project(_, child)      => findHint(child)
      case ProtoLogicalPlan.Filter(_, child)       => findHint(child)
      case ProtoLogicalPlan.SubqueryAlias(_, child) => findHint(child)
      case _                                       => None

    val hint = findHint(result.toOption.get)
    assert(hint.isDefined, "Expected ResolvedHint in plan")
    assertEquals(hint.get.hints.size, 1)
    hint.get.hints.head match
      case protocatalyst.plan.PlanHint.Broadcast(tables) =>
        assertEquals(tables, Vector("users"))
      case other => fail(s"Expected Broadcast hint, got $other")

  test("transforms query with REPARTITION hint"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT /*+ REPARTITION(4) */ * FROM users"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findHint(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.ResolvedHint] = plan match
      case h @ ProtoLogicalPlan.ResolvedHint(_, _) => Some(h)
      case ProtoLogicalPlan.Project(_, child)      => findHint(child)
      case _                                       => None

    val hint = findHint(result.toOption.get)
    assert(hint.isDefined, "Expected ResolvedHint in plan")
    hint.get.hints.head match
      case protocatalyst.plan.PlanHint.Repartition(partitions, _) =>
        assertEquals(partitions, 4)
      case other => fail(s"Expected Repartition hint, got $other")

  test("transforms query with multiple hints"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT /*+ BROADCAST(users) COALESCE(1) */ * FROM users"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findHint(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.ResolvedHint] = plan match
      case h @ ProtoLogicalPlan.ResolvedHint(_, _) => Some(h)
      case ProtoLogicalPlan.Project(_, child)      => findHint(child)
      case _                                       => None

    val hint = findHint(result.toOption.get)
    assert(hint.isDefined, "Expected ResolvedHint in plan")
    assertEquals(hint.get.hints.size, 2)

  test("transforms query without hints has no ResolvedHint"):
    val stmt = asSelect(
      SqlParser
        .parse(
          "SELECT * FROM users"
        )
        .toOption
        .get
    )
    val result = AstToProtoTransform.transform(stmt, userSchema, "users")

    assert(result.isRight, s"Transform failed: ${result.left.toOption}")

    def findHint(plan: ProtoLogicalPlan): Option[ProtoLogicalPlan.ResolvedHint] = plan match
      case h @ ProtoLogicalPlan.ResolvedHint(_, _) => Some(h)
      case ProtoLogicalPlan.Project(_, child)      => findHint(child)
      case ProtoLogicalPlan.Filter(_, child)       => findHint(child)
      case _                                       => None

    val hint = findHint(result.toOption.get)
    assert(hint.isEmpty, "Expected no ResolvedHint in plan for query without hints")
