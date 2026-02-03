package protocatalyst.plan

import protocatalyst.expr._
import protocatalyst.schema._
import protocatalyst.types._

class ProtoLogicalPlanSuite extends munit.FunSuite:

  // === RelationRef Tests ===

  test("RelationRef with alias"):
    val contract = makeContract("users")
    val plan = ProtoLogicalPlan.RelationRef("users", Some("u"), contract)
    plan match
      case ProtoLogicalPlan.RelationRef(name, alias, c) =>
        assertEquals(name, "users")
        assertEquals(alias, Some("u"))
        assertEquals(c, contract)
      case _ => fail(s"Expected RelationRef, got $plan")

  test("RelationRef without alias"):
    val contract = makeContract("orders")
    val plan = ProtoLogicalPlan.RelationRef("orders", None, contract)
    plan match
      case ProtoLogicalPlan.RelationRef(name, None, _) =>
        assertEquals(name, "orders")
      case _ => fail(s"Expected RelationRef without alias, got $plan")

  // === Values Tests ===

  test("Values with rows"):
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("a", ProtoType.IntegerType, nullable = false),
        ProtoStructField("b", ProtoType.StringType, nullable = true)
      )
    )
    val rows = Vector(
      Vector(ProtoExpr.lit(1), ProtoExpr.lit("x")),
      Vector(ProtoExpr.lit(2), ProtoExpr.lit("y"))
    )
    val plan = ProtoLogicalPlan.Values(rows, schema)
    plan match
      case ProtoLogicalPlan.Values(r, s) =>
        assertEquals(r.size, 2)
        assertEquals(s.fields.size, 2)
      case _ => fail(s"Expected Values, got $plan")

  test("Values with empty rows"):
    val schema = ProtoSchema(Vector(ProtoStructField("x", ProtoType.IntegerType, nullable = false)))
    val plan = ProtoLogicalPlan.Values(Vector.empty, schema)
    plan match
      case ProtoLogicalPlan.Values(r, _) =>
        assertEquals(r.size, 0)
      case _ => fail(s"Expected empty Values, got $plan")

  // === Project Tests ===

  test("Project with column references"):
    val exprs = Vector(
      ProtoExpr.ColumnRef("id", None, ProtoType.LongType, nullable = false),
      ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true)
    )
    val plan = ProtoLogicalPlan.Project(exprs, baseRelation)
    plan match
      case ProtoLogicalPlan.Project(projectList, child) =>
        assertEquals(projectList.size, 2)
        assertEquals(child, baseRelation)
      case _ => fail(s"Expected Project, got $plan")

  test("Project with expressions"):
    val exprs = Vector(
      ProtoExpr.Alias(ProtoExpr.Add(ProtoExpr.lit(1), ProtoExpr.lit(2)), "sum"),
      ProtoExpr.Alias(ProtoExpr.Upper(ProtoExpr.lit("hello")), "upper")
    )
    val plan = ProtoLogicalPlan.Project(exprs, baseRelation)
    plan match
      case ProtoLogicalPlan.Project(projectList, _) =>
        assertEquals(projectList.size, 2)
      case _ => fail(s"Expected Project, got $plan")

  // === Filter Tests ===

  test("Filter with simple condition"):
    val condition = ProtoExpr.Gt(
      ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, nullable = false),
      ProtoExpr.lit(18)
    )
    val plan = ProtoLogicalPlan.Filter(condition, baseRelation)
    plan match
      case ProtoLogicalPlan.Filter(cond, child) =>
        assertEquals(cond, condition)
        assertEquals(child, baseRelation)
      case _ => fail(s"Expected Filter, got $plan")

  test("Filter with complex condition"):
    val condition = ProtoExpr.And(
      Vector(
        ProtoExpr.Gt(
          ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, nullable = false),
          ProtoExpr.lit(18)
        ),
        ProtoExpr.Lt(
          ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, nullable = false),
          ProtoExpr.lit(65)
        ),
        ProtoExpr.IsNotNull(
          ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true)
        )
      )
    )
    val plan = ProtoLogicalPlan.Filter(condition, baseRelation)
    plan match
      case ProtoLogicalPlan.Filter(ProtoExpr.And(children), _) =>
        assertEquals(children.size, 3)
      case _ => fail(s"Expected Filter with And condition, got $plan")

  // === Aggregate Tests ===

  test("Aggregate with grouping and aggregates"):
    val grouping = Vector(
      ProtoExpr.ColumnRef("dept", None, ProtoType.StringType, nullable = false)
    )
    val aggs = Vector(
      ProtoExpr.Count(
        ProtoExpr.ColumnRef("id", None, ProtoType.LongType, nullable = false),
        distinct = false
      ),
      ProtoExpr.Sum(ProtoExpr.ColumnRef("salary", None, ProtoType.DoubleType, nullable = false))
    )
    val plan = ProtoLogicalPlan.Aggregate(grouping, aggs, baseRelation)
    plan match
      case ProtoLogicalPlan.Aggregate(grp, ag, child) =>
        assertEquals(grp.size, 1)
        assertEquals(ag.size, 2)
        assertEquals(child, baseRelation)
      case _ => fail(s"Expected Aggregate, got $plan")

  test("Aggregate without grouping (global aggregate)"):
    val aggs = Vector(
      ProtoExpr.Count(ProtoExpr.lit(1), distinct = false)
    )
    val plan = ProtoLogicalPlan.Aggregate(Vector.empty, aggs, baseRelation)
    plan match
      case ProtoLogicalPlan.Aggregate(grp, _, _) =>
        assertEquals(grp.size, 0)
      case _ => fail(s"Expected Aggregate without grouping, got $plan")

  // === Sort Tests ===

  test("Sort with single order"):
    val order = Vector(
      SortOrder(
        ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true),
        SortDirection.Ascending,
        NullOrdering.NullsFirst
      )
    )
    val plan = ProtoLogicalPlan.Sort(order, global = true, baseRelation)
    plan match
      case ProtoLogicalPlan.Sort(o, g, _) =>
        assertEquals(o.size, 1)
        assertEquals(g, true)
      case _ => fail(s"Expected Sort, got $plan")

  test("Sort with multiple orders"):
    val order = Vector(
      SortOrder(
        ProtoExpr.ColumnRef("dept", None, ProtoType.StringType, nullable = false),
        SortDirection.Ascending,
        NullOrdering.NullsLast
      ),
      SortOrder(
        ProtoExpr.ColumnRef("salary", None, ProtoType.DoubleType, nullable = false),
        SortDirection.Descending,
        NullOrdering.NullsLast
      )
    )
    val plan = ProtoLogicalPlan.Sort(order, global = false, baseRelation)
    plan match
      case ProtoLogicalPlan.Sort(o, false, _) =>
        assertEquals(o.size, 2)
      case _ => fail(s"Expected Sort, got $plan")

  test("all SortDirection variants"):
    val asc = SortDirection.Ascending
    val desc = SortDirection.Descending
    assert(asc.isInstanceOf[SortDirection])
    assert(desc.isInstanceOf[SortDirection])
    assertNotEquals(asc, desc)

  test("all NullOrdering variants"):
    val first = NullOrdering.NullsFirst
    val last = NullOrdering.NullsLast
    assert(first.isInstanceOf[NullOrdering])
    assert(last.isInstanceOf[NullOrdering])
    assertNotEquals(first, last)

  // === Limit Tests ===

  test("Limit construction"):
    val plan = ProtoLogicalPlan.Limit(100, baseRelation)
    plan match
      case ProtoLogicalPlan.Limit(limit, child) =>
        assertEquals(limit, 100)
        assertEquals(child, baseRelation)
      case _ => fail(s"Expected Limit, got $plan")

  test("Limit with zero"):
    val plan = ProtoLogicalPlan.Limit(0, baseRelation)
    plan match
      case ProtoLogicalPlan.Limit(0, _) => ()
      case _                            => fail(s"Expected Limit(0), got $plan")

  // === Distinct Tests ===

  test("Distinct construction"):
    val plan = ProtoLogicalPlan.Distinct(baseRelation)
    plan match
      case ProtoLogicalPlan.Distinct(child) =>
        assertEquals(child, baseRelation)
      case _ => fail(s"Expected Distinct, got $plan")

  // === SubqueryAlias Tests ===

  test("SubqueryAlias construction"):
    val plan = ProtoLogicalPlan.SubqueryAlias("subq", baseRelation)
    plan match
      case ProtoLogicalPlan.SubqueryAlias(alias, child) =>
        assertEquals(alias, "subq")
        assertEquals(child, baseRelation)
      case _ => fail(s"Expected SubqueryAlias, got $plan")

  // === Join Tests ===

  test("Join with all types"):
    val joinTypes = List(
      JoinType.Inner,
      JoinType.LeftOuter,
      JoinType.RightOuter,
      JoinType.FullOuter,
      JoinType.LeftSemi,
      JoinType.LeftAnti,
      JoinType.Cross
    )
    for jt <- joinTypes do
      val plan = ProtoLogicalPlan.Join(baseRelation, baseRelation, jt, None)
      plan match
        case ProtoLogicalPlan.Join(_, _, joinType, _) =>
          assertEquals(joinType, jt)
        case _ => fail(s"Expected Join with $jt, got $plan")

  test("Join with condition"):
    val condition = ProtoExpr.Eq(
      ProtoExpr.ColumnRef("id", Some("left"), ProtoType.LongType, nullable = false),
      ProtoExpr.ColumnRef("id", Some("right"), ProtoType.LongType, nullable = false)
    )
    val plan = ProtoLogicalPlan.Join(baseRelation, baseRelation, JoinType.Inner, Some(condition))
    plan match
      case ProtoLogicalPlan.Join(_, _, _, Some(cond)) =>
        assertEquals(cond, condition)
      case _ => fail(s"Expected Join with condition, got $plan")

  test("Cross join without condition"):
    val plan = ProtoLogicalPlan.Join(baseRelation, baseRelation, JoinType.Cross, None)
    plan match
      case ProtoLogicalPlan.Join(_, _, JoinType.Cross, None) => ()
      case _ => fail(s"Expected Cross Join without condition, got $plan")

  // === Union Tests ===

  test("Union by position"):
    val plan = ProtoLogicalPlan.Union(
      Vector(baseRelation, baseRelation),
      byName = false,
      allowMissingColumns = false
    )
    plan match
      case ProtoLogicalPlan.Union(children, false, false) =>
        assertEquals(children.size, 2)
      case _ => fail(s"Expected Union by position, got $plan")

  test("Union by name"):
    val plan = ProtoLogicalPlan.Union(
      Vector(baseRelation, baseRelation, baseRelation),
      byName = true,
      allowMissingColumns = true
    )
    plan match
      case ProtoLogicalPlan.Union(children, true, true) =>
        assertEquals(children.size, 3)
      case _ => fail(s"Expected Union by name, got $plan")

  // === Intersect Tests ===

  test("Intersect distinct"):
    val plan = ProtoLogicalPlan.Intersect(baseRelation, baseRelation, isAll = false)
    plan match
      case ProtoLogicalPlan.Intersect(left, right, false) =>
        assertEquals(left, baseRelation)
        assertEquals(right, baseRelation)
      case _ => fail(s"Expected Intersect distinct, got $plan")

  test("Intersect all"):
    val plan = ProtoLogicalPlan.Intersect(baseRelation, baseRelation, isAll = true)
    plan match
      case ProtoLogicalPlan.Intersect(_, _, true) => ()
      case _                                      => fail(s"Expected Intersect all, got $plan")

  // === Except Tests ===

  test("Except distinct"):
    val plan = ProtoLogicalPlan.Except(baseRelation, baseRelation, isAll = false)
    plan match
      case ProtoLogicalPlan.Except(left, right, false) =>
        assertEquals(left, baseRelation)
        assertEquals(right, baseRelation)
      case _ => fail(s"Expected Except distinct, got $plan")

  test("Except all"):
    val plan = ProtoLogicalPlan.Except(baseRelation, baseRelation, isAll = true)
    plan match
      case ProtoLogicalPlan.Except(_, _, true) => ()
      case _                                   => fail(s"Expected Except all, got $plan")

  // === Window Tests ===

  test("Window construction"):
    val windowExprs = Vector(ProtoExpr.RowNumber())
    val partition =
      Vector(ProtoExpr.ColumnRef("dept", None, ProtoType.StringType, nullable = false))
    val order = Vector(
      SortOrder(
        ProtoExpr.ColumnRef("date", None, ProtoType.DateType, nullable = false),
        SortDirection.Ascending,
        NullOrdering.NullsFirst
      )
    )
    val plan = ProtoLogicalPlan.Window(windowExprs, partition, order, baseRelation)
    plan match
      case ProtoLogicalPlan.Window(we, ps, os, child) =>
        assertEquals(we.size, 1)
        assertEquals(ps.size, 1)
        assertEquals(os.size, 1)
        assertEquals(child, baseRelation)
      case _ => fail(s"Expected Window, got $plan")

  test("Window without partition"):
    val windowExprs = Vector(ProtoExpr.RowNumber())
    val order = Vector(
      SortOrder(
        ProtoExpr.ColumnRef("id", None, ProtoType.LongType, nullable = false),
        SortDirection.Ascending,
        NullOrdering.NullsFirst
      )
    )
    val plan = ProtoLogicalPlan.Window(windowExprs, Vector.empty, order, baseRelation)
    plan match
      case ProtoLogicalPlan.Window(_, ps, _, _) =>
        assertEquals(ps.size, 0)
      case _ => fail(s"Expected Window without partition, got $plan")

  // === With (CTE) Tests ===

  test("With single CTE"):
    val cteRelation = ProtoLogicalPlan.Project(
      Vector(ProtoExpr.ColumnRef("id", None, ProtoType.LongType, nullable = false)),
      baseRelation
    )
    val plan = ProtoLogicalPlan.With(
      Vector(("cte1", cteRelation)),
      baseRelation
    )
    plan match
      case ProtoLogicalPlan.With(ctes, child) =>
        assertEquals(ctes.size, 1)
        assertEquals(ctes.head._1, "cte1")
        assertEquals(child, baseRelation)
      case _ => fail(s"Expected With, got $plan")

  test("With multiple CTEs"):
    val cte1 = ProtoLogicalPlan.Project(Vector(ProtoExpr.lit(1)), baseRelation)
    val cte2 = ProtoLogicalPlan.Project(Vector(ProtoExpr.lit(2)), baseRelation)
    val plan = ProtoLogicalPlan.With(
      Vector(("a", cte1), ("b", cte2)),
      baseRelation
    )
    plan match
      case ProtoLogicalPlan.With(ctes, _) =>
        assertEquals(ctes.size, 2)
        assertEquals(ctes(0)._1, "a")
        assertEquals(ctes(1)._1, "b")
      case _ => fail(s"Expected With with multiple CTEs, got $plan")

  // === Nested Plan Tests ===

  test("nested plans: Filter over Project"):
    val project = ProtoLogicalPlan.Project(
      Vector(ProtoExpr.ColumnRef("id", None, ProtoType.LongType, nullable = false)),
      baseRelation
    )
    val filter = ProtoLogicalPlan.Filter(
      ProtoExpr.Gt(
        ProtoExpr.ColumnRef("id", None, ProtoType.LongType, nullable = false),
        ProtoExpr.lit(0)
      ),
      project
    )
    filter match
      case ProtoLogicalPlan.Filter(_, ProtoLogicalPlan.Project(_, _)) => ()
      case _ => fail(s"Expected Filter over Project, got $filter")

  test("complex nested plan"):
    val scan = baseRelation
    val filtered = ProtoLogicalPlan.Filter(
      ProtoExpr.IsNotNull(ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true)),
      scan
    )
    val projected = ProtoLogicalPlan.Project(
      Vector(
        ProtoExpr.ColumnRef("id", None, ProtoType.LongType, nullable = false),
        ProtoExpr.Upper(ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true))
      ),
      filtered
    )
    val sorted = ProtoLogicalPlan.Sort(
      Vector(
        SortOrder(
          ProtoExpr.ColumnRef("id", None, ProtoType.LongType, nullable = false),
          SortDirection.Ascending,
          NullOrdering.NullsFirst
        )
      ),
      global = true,
      projected
    )
    val limited = ProtoLogicalPlan.Limit(10, sorted)

    limited match
      case ProtoLogicalPlan.Limit(
            10,
            ProtoLogicalPlan.Sort(_, _, ProtoLogicalPlan.Project(_, ProtoLogicalPlan.Filter(_, _)))
          ) =>
        ()
      case _ => fail(s"Expected complex nested plan, got $limited")

  // === Plan Equality Tests ===

  test("plan equality"):
    val plan1 = ProtoLogicalPlan.Limit(100, baseRelation)
    val plan2 = ProtoLogicalPlan.Limit(100, baseRelation)
    val plan3 = ProtoLogicalPlan.Limit(200, baseRelation)

    assertEquals(plan1, plan2)
    assertNotEquals(plan1, plan3)

  test("SortOrder equality"):
    val order1 = SortOrder(ProtoExpr.lit(1), SortDirection.Ascending, NullOrdering.NullsFirst)
    val order2 = SortOrder(ProtoExpr.lit(1), SortDirection.Ascending, NullOrdering.NullsFirst)
    val order3 = SortOrder(ProtoExpr.lit(1), SortDirection.Descending, NullOrdering.NullsFirst)

    assertEquals(order1, order2)
    assertNotEquals(order1, order3)

  // === Helper Methods ===

  private def makeContract(name: String): SchemaContract =
    SchemaContract(
      name,
      Vector(FieldContract("id", ProtoType.LongType, expectedNullable = false, position = 0)),
      SchemaFingerprint.fromLong(name.hashCode.toLong)
    )

  private val baseRelation = ProtoLogicalPlan.RelationRef(
    "test_table",
    None,
    SchemaContract(
      "test_table",
      Vector(FieldContract("id", ProtoType.LongType, expectedNullable = false, position = 0)),
      SchemaFingerprint.fromLong(99999L)
    )
  )
