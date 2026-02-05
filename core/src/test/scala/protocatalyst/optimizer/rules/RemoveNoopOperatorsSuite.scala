package protocatalyst.optimizer.rules

import protocatalyst.optimizer.rules._
import protocatalyst.plan._
import protocatalyst.testutil._

/** Tests for RemoveNoopOperators optimizer rule.
  *
  * Uses the new test DSL and utilities for improved readability.
  */
class RemoveNoopOperatorsSuite extends munit.FunSuite with RuleTestBase:
  import PlanDsl._

  // ========================================================
  // Filter TRUE Removal Tests
  // ========================================================

  test("RemoveNoopOperators: remove Filter with TRUE condition"):
    // Filter(TRUE, child) -> child
    val input = relation("t").where(lit(true))

    val optimized = RemoveNoopOperators(input)
    optimized match
      case ProtoLogicalPlan.RelationRef("t", _, _) => ()
      case _ => fail(s"Expected child without Filter, got $optimized")

  test("RemoveNoopOperators: keep Filter with non-TRUE condition"):
    // Filter(a > 5, child) should remain
    val input = relation("t").where($"a" > lit(5))

    val optimized = RemoveNoopOperators(input)
    optimized match
      case ProtoLogicalPlan.Filter(_, _) => ()
      case _                             => fail(s"Expected Filter to remain, got $optimized")

  // ========================================================
  // Redundant Limit Tests
  // ========================================================

  test("RemoveNoopOperators: remove redundant outer Limit"):
    // Limit 20 (Limit 10) -> Limit 10
    val input = relation("t").limit(10).limit(20)

    val optimized = RemoveNoopOperators(input)
    optimized match
      case ProtoLogicalPlan.Limit(10, _) => ()
      case _                             => fail(s"Expected Limit(10), got $optimized")

  test("RemoveNoopOperators: keep tighter outer Limit"):
    // Limit 5 (Limit 10) should remain (handled by LimitPushdown merge)
    val input = relation("t").limit(10).limit(5)

    val optimized = RemoveNoopOperators(input)
    // This case is NOT handled by RemoveNoopOperators (n1 < n2)
    // So it should remain as is
    optimized match
      case ProtoLogicalPlan.Limit(5, ProtoLogicalPlan.Limit(10, _)) => ()
      case _ => fail(s"Expected nested Limit, got $optimized")

  // ========================================================
  // Empty Hint Tests
  // ========================================================

  test("RemoveNoopOperators: remove empty Hint"):
    // ResolvedHint(empty, child) -> child
    val plan = ProtoLogicalPlan.ResolvedHint(
      Vector.empty,
      relation("t")
    )

    val optimized = RemoveNoopOperators(plan)
    optimized match
      case ProtoLogicalPlan.RelationRef("t", _, _) => ()
      case _ => fail(s"Expected child without Hint, got $optimized")

  test("RemoveNoopOperators: keep non-empty Hint"):
    // ResolvedHint(hints, child) should remain
    val plan = ProtoLogicalPlan.ResolvedHint(
      Vector(PlanHint.Broadcast(Vector("t"))),
      relation("t")
    )

    val optimized = RemoveNoopOperators(plan)
    optimized match
      case ProtoLogicalPlan.ResolvedHint(_, _) => ()
      case _                                   => fail(s"Expected Hint to remain, got $optimized")

  // ========================================================
  // Duplicate SubqueryAlias Tests
  // ========================================================

  test("RemoveNoopOperators: collapse duplicate SubqueryAlias"):
    // SubqueryAlias(a, SubqueryAlias(a, x)) -> SubqueryAlias(a, x)
    val input = relation("t").subquery("alias").subquery("alias")

    val optimized = RemoveNoopOperators(input)
    optimized match
      case ProtoLogicalPlan.SubqueryAlias("alias", ProtoLogicalPlan.RelationRef(_, _, _)) => ()
      case _ => fail(s"Expected single SubqueryAlias, got $optimized")
