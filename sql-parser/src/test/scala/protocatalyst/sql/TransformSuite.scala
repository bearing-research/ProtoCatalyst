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
      case ProtoLogicalPlan.With(cteRelations, child) =>
        assertEquals(cteRelations.size, 1)
        assertEquals(cteRelations.head._1, "active_users")
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
      case ProtoLogicalPlan.With(cteRelations, _) =>
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
      case ProtoLogicalPlan.With(cteRelations, _) =>
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
      case ProtoLogicalPlan.With(cteRelations, child) =>
        assertEquals(cteRelations.size, 1)
        // Main query should be a Union
        child match
          case ProtoLogicalPlan.Union(_, _, _) => () // ok
          case other                           => fail(s"Expected Union as main query, got $other")
      case other => fail(s"Expected With plan, got $other")

  test("recursive CTE returns error"):
    val stmt = SqlParser
      .parse("""
      WITH RECURSIVE cte AS (
        SELECT * FROM users WHERE id = 1
      )
      SELECT * FROM cte
    """)
      .toOption
      .get
    val result = AstToProtoTransform.transformStmt(stmt, userSchema, "users")

    // Recursive CTEs are not yet supported
    assert(result.isLeft, "Expected error for recursive CTE")
    assert(
      result.left.toOption.get.message.contains("Recursive"),
      "Expected error message about recursive CTEs"
    )

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
