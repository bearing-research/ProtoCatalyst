package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer.rules._
import protocatalyst.plan._
import protocatalyst.testutil._

/** Tests for Limit and Sort optimization rules: LimitPushdown, EliminateSorts, and EliminateLimits.
  *
  * Uses the new test DSL and utilities for improved readability.
  */
class LimitSortSuite extends munit.FunSuite with RuleTestBase:
  import PlanDsl._

  // ========================================================
  // LimitPushdown Tests
  // ========================================================

  test("LimitPushdown: push limit through project"):
    // Limit 10
    //   Project [a, b]
    //     Scan
    // Should become:
    //   Project [a, b]
    //     Limit 10
    //       Scan
    val input = relation("t")
      .select($"a", $"b")
      .limit(10)

    val pushed = LimitPushdown(input)
    pushed match
      case ProtoLogicalPlan.Project(_, ProtoLogicalPlan.Limit(10, _)) => ()
      case _ => fail(s"Expected Project(Limit(...)), got $pushed")

  test("LimitPushdown: push limit through subquery alias"):
    // Limit 10
    //   SubqueryAlias [alias]
    //     Scan
    // Should become:
    //   SubqueryAlias [alias]
    //     Limit 10
    //       Scan
    val input = relation("t")
      .subquery("alias")
      .limit(10)

    val pushed = LimitPushdown(input)
    pushed match
      case ProtoLogicalPlan.SubqueryAlias("alias", ProtoLogicalPlan.Limit(10, _)) => ()
      case _ => fail(s"Expected SubqueryAlias(Limit(...)), got $pushed")

  test("LimitPushdown: push limit to union children"):
    // Limit 10
    //   Union
    //     Scan t1
    //     Scan t2
    // Should add Limit 10 to each branch
    val input = relation("t1")
      .union(relation("t2"))
      .limit(10)

    val pushed = LimitPushdown(input)
    pushed match
      case ProtoLogicalPlan.Limit(10, ProtoLogicalPlan.Union(children, _, _)) =>
        children.foreach {
          case ProtoLogicalPlan.Limit(10, _) => ()
          case other                         => fail(s"Expected Limit in union child, got $other")
        }
      case _ => fail(s"Expected Limit(Union(Limit...)), got $pushed")

  test("LimitPushdown: merge nested limits"):
    // Limit 10
    //   Limit 20
    //     Scan
    // Should become Limit 10 (min of 10, 20)
    val input = relation("t").limit(20).limit(10)

    val pushed = LimitPushdown(input)
    pushed match
      case ProtoLogicalPlan.Limit(10, _: ProtoLogicalPlan.RelationRef) => ()
      case ProtoLogicalPlan.Limit(10, ProtoLogicalPlan.Limit(_, _))    =>
        fail("Expected merged limits")
      case _ => fail(s"Expected Limit(10, Scan), got $pushed")

  test("LimitPushdown: nested limits take minimum"):
    // Limit 20
    //   Limit 5
    //     Scan
    // Should become Limit 5 (min of 20, 5)
    val input = relation("t").limit(5).limit(20)

    val pushed = LimitPushdown(input)
    pushed match
      case ProtoLogicalPlan.Limit(5, _: ProtoLogicalPlan.RelationRef) => ()
      case _ => fail(s"Expected Limit(5, Scan), got $pushed")

  test("LimitPushdown: push to left side of left outer join"):
    val input = relation("left")
      .leftJoin(relation("right"), $"a" === $"b")
      .limit(10)

    val pushed = LimitPushdown(input)
    pushed match
      case ProtoLogicalPlan.Limit(
            10,
            ProtoLogicalPlan.Join(ProtoLogicalPlan.Limit(10, _), _, JoinType.LeftOuter, _)
          ) =>
        ()
      case _ => fail(s"Expected Limit pushed to left join child, got $pushed")

  // ========================================================
  // EliminateSorts Tests
  // ========================================================

  test("EliminateSorts: remove Sort on single row"):
    // Sort(LIMIT 1) -> LIMIT 1
    val input = relation("t")
      .limit(1)
      .orderBy($"a".asc)

    val optimized = EliminateSorts(input)
    optimized match
      case ProtoLogicalPlan.Limit(1, _) => ()
      case _                            => fail(s"Expected Limit without Sort, got $optimized")

  test("EliminateSorts: remove inner Sort when outer Sort exists"):
    // Sort(Sort(x)) -> Sort(x)
    val input = relation("t")
      .orderBy($"b".desc)
      .orderBy($"a".asc)

    val optimized = EliminateSorts(input)
    optimized match
      case ProtoLogicalPlan.Sort(order, ProtoLogicalPlan.RelationRef(_, _, _)) =>
        assertEquals(order.head.child.asInstanceOf[ProtoExpr.ColumnRef].name, "a")
      case _ => fail(s"Expected Sort(a, Scan) without inner Sort, got $optimized")

  test("EliminateSorts: remove empty Sort"):
    // Sort with no order -> child
    val plan = ProtoLogicalPlan.Sort(
      Vector.empty,
      relation("t")
    )
    val optimized = EliminateSorts(plan)
    optimized match
      case ProtoLogicalPlan.RelationRef(_, _, _) => ()
      case _ => fail(s"Expected child without Sort, got $optimized")

  test("EliminateSorts: remove Sort consumed by Aggregate"):
    // Aggregate(Sort(x)) -> Aggregate(x)
    val input = relation("t")
      .orderBy($"b".asc)
      .groupBy($"a")($"a")

    val optimized = EliminateSorts(input)
    optimized match
      case ProtoLogicalPlan.Aggregate(_, _, ProtoLogicalPlan.RelationRef(_, _, _)) => ()
      case _ => fail(s"Expected Aggregate without inner Sort, got $optimized")

  test("EliminateSorts: remove Sort consumed by Distinct"):
    // Distinct(Sort(x)) -> Distinct(x)
    val input = relation("t")
      .orderBy($"a".asc)
      .distinct

    val optimized = EliminateSorts(input)
    optimized match
      case ProtoLogicalPlan.Distinct(ProtoLogicalPlan.RelationRef(_, _, _)) => ()
      case _ => fail(s"Expected Distinct without inner Sort, got $optimized")

  // ========================================================
  // EliminateLimits Tests
  // ========================================================

  test("EliminateLimits: nested limits take minimum (outer smaller)"):
    // LIMIT 5 (LIMIT 10 x) → LIMIT 5 x
    val input = relation("t").limit(10).limit(5)

    val optimized = EliminateLimits(input)
    optimized match
      case ProtoLogicalPlan.Limit(5, ProtoLogicalPlan.RelationRef("t", _, _)) => ()
      case _ => fail(s"Expected LIMIT 5, got $optimized")

  test("EliminateLimits: nested limits take minimum (inner smaller)"):
    // LIMIT 10 (LIMIT 5 x) → LIMIT 5 x
    val input = relation("t").limit(5).limit(10)

    val optimized = EliminateLimits(input)
    optimized match
      case ProtoLogicalPlan.Limit(5, ProtoLogicalPlan.RelationRef("t", _, _)) => ()
      case _ => fail(s"Expected LIMIT 5, got $optimized")

  test("EliminateLimits: nested limits with same value"):
    // LIMIT 5 (LIMIT 5 x) → LIMIT 5 x
    val input = relation("t").limit(5).limit(5)

    val optimized = EliminateLimits(input)
    optimized match
      case ProtoLogicalPlan.Limit(5, ProtoLogicalPlan.RelationRef("t", _, _)) => ()
      case _ => fail(s"Expected LIMIT 5, got $optimized")

  test("EliminateLimits: deeply nested limits"):
    // LIMIT 100 (LIMIT 50 (LIMIT 10 x)) → LIMIT 10 x
    val input = relation("t").limit(10).limit(50).limit(100)

    val optimized = EliminateLimits(EliminateLimits(input))
    optimized match
      case ProtoLogicalPlan.Limit(10, ProtoLogicalPlan.RelationRef("t", _, _)) => ()
      case _ => fail(s"Expected LIMIT 10, got $optimized")
