package protocatalyst.optimizer.rules

import protocatalyst.optimizer.rules._
import protocatalyst.plan._
import protocatalyst.testutil._

/** Tests for EliminateDistinct optimizer rule.
  *
  * Uses the new test DSL and utilities for improved readability.
  */
class EliminateDistinctSuite extends munit.FunSuite with RuleTestBase:
  import PlanDsl._

  // ========================================================
  // Redundant DISTINCT After Aggregate Tests
  // ========================================================

  test("EliminateDistinct: remove redundant DISTINCT after Aggregate"):
    // DISTINCT after GROUP BY is redundant
    val input = relation("t")
      .groupBy($"a")($"a", lit(1).as("count"))
      .distinct

    val optimized = EliminateDistinct(input)
    optimized match
      case ProtoLogicalPlan.Aggregate(_, _, _) => ()
      case _ => fail(s"Expected Aggregate without Distinct wrapper, got $optimized")

  test("EliminateDistinct: keep DISTINCT after Aggregate with empty grouping"):
    // DISTINCT after Aggregate without grouping is NOT redundant
    val input = relation("t")
      .aggregate(lit(1).as("count"))
      .distinct

    val optimized = EliminateDistinct(input)
    optimized match
      case ProtoLogicalPlan.Distinct(_) => ()
      case _                            => fail(s"Expected Distinct to remain, got $optimized")

  // ========================================================
  // Double DISTINCT Tests
  // ========================================================

  test("EliminateDistinct: remove double DISTINCT"):
    // DISTINCT(DISTINCT(x)) -> DISTINCT(x)
    val input = relation("t").distinct.distinct

    val optimized = EliminateDistinct(input)
    optimized match
      case ProtoLogicalPlan.Distinct(ProtoLogicalPlan.RelationRef(_, _, _)) => ()
      case _ => fail(s"Expected single Distinct, got $optimized")

  // ========================================================
  // Single Row DISTINCT Tests
  // ========================================================

  test("EliminateDistinct: remove DISTINCT on single row"):
    // DISTINCT(LIMIT 1) -> LIMIT 1
    val input = relation("t").limit(1).distinct

    val optimized = EliminateDistinct(input)
    optimized match
      case ProtoLogicalPlan.Limit(1, _) => ()
      case _                            => fail(s"Expected Limit without Distinct, got $optimized")

  // ========================================================
  // Push Through SubqueryAlias Tests
  // ========================================================

  test("EliminateDistinct: push through SubqueryAlias"):
    val input = relation("t").subquery("alias").distinct

    val optimized = EliminateDistinct(input)
    optimized match
      case ProtoLogicalPlan.SubqueryAlias("alias", ProtoLogicalPlan.Distinct(_)) => ()
      case _ => fail(s"Expected SubqueryAlias(Distinct), got $optimized")
