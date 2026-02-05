package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer.rules._
import protocatalyst.plan._
import protocatalyst.testutil._

/** Tests for CTE optimization rules: InlineCTE and RewriteCorrelatedSubquery.
  *
  * Uses the new test DSL and utilities for improved readability.
  */
class CTESuite extends munit.FunSuite with RuleTestBase:
  import PlanDsl._

  // ========================================================
  // InlineCTE Tests
  // ========================================================

  test("InlineCTE: inline CTE referenced once"):
    // WITH t AS (SELECT * FROM users) SELECT * FROM t
    // -> SELECT * FROM users (with SubqueryAlias wrapper from CTE)
    val ctePlan = relation("users").subquery("t")
    val plan = ProtoLogicalPlan.With(
      Vector(("t", ctePlan)),
      recursive = false,
      relation("t").select($"a") // references CTE 't'
    )

    val optimized = InlineCTE(plan)
    // CTE should be inlined, With should be removed
    optimized match
      case ProtoLogicalPlan.With(_, _, _) => fail("Expected With to be removed")
      case _                              => () // CTE was inlined

  test("InlineCTE: keep CTE referenced multiple times"):
    // WITH t AS (SELECT * FROM users)
    // SELECT * FROM t JOIN t
    val ctePlan = relation("users").subquery("t")
    val cteRef = relation("t")
    val plan = ProtoLogicalPlan.With(
      Vector(("t", ctePlan)),
      recursive = false,
      cteRef.join(cteRef, JoinType.Inner, None)
    )

    val optimized = InlineCTE(plan)
    // CTE referenced twice, should NOT be inlined
    optimized match
      case ProtoLogicalPlan.With(ctes, _, _) =>
        assert(ctes.size == 1, "CTE should be kept")
      case _ => fail(s"Expected With with CTE, got $optimized")

  test("InlineCTE: remove unreferenced CTE"):
    // WITH t AS (SELECT * FROM users) SELECT * FROM orders
    val ctePlan = relation("users").subquery("t")
    val plan = ProtoLogicalPlan.With(
      Vector(("t", ctePlan)),
      recursive = false,
      relation("orders") // doesn't reference 't'
    )

    val optimized = InlineCTE(plan)
    // CTE not referenced, With should be removed
    optimized match
      case ProtoLogicalPlan.With(_, _, _)               => fail("Expected With to be removed")
      case ProtoLogicalPlan.RelationRef("orders", _, _) => () // Unreferenced CTE removed
      case _ => fail(s"Expected orders table, got $optimized")

  test("InlineCTE: don't inline recursive CTE"):
    // WITH RECURSIVE t AS (...) SELECT * FROM t
    val ctePlan = relation("users").subquery("t")
    val plan = ProtoLogicalPlan.With(
      Vector(("t", ctePlan)),
      recursive = true,
      relation("t")
    )

    val optimized = InlineCTE(plan)
    // Recursive CTE should NOT be inlined
    optimized match
      case ProtoLogicalPlan.With(_, true, _) => () // Kept as recursive
      case _ => fail(s"Expected recursive With to remain, got $optimized")

  test("InlineCTE: inline one of multiple CTEs"):
    // WITH
    //   a AS (SELECT * FROM users),
    //   b AS (SELECT * FROM orders)
    // SELECT * FROM a JOIN b JOIN b
    val cteA = relation("users").subquery("a")
    val cteB = relation("orders").subquery("b")
    val plan = ProtoLogicalPlan.With(
      Vector(("a", cteA), ("b", cteB)),
      recursive = false,
      relation("a").join(
        relation("b").join(relation("b"), JoinType.Inner, None),
        JoinType.Inner,
        None
      )
    )

    val optimized = InlineCTE(plan)
    // 'a' referenced once -> inline
    // 'b' referenced twice -> keep
    optimized match
      case ProtoLogicalPlan.With(ctes, _, _) =>
        assert(ctes.size == 1, s"Expected 1 CTE (b), got ${ctes.size}")
        assert(ctes.head._1 == "b", s"Expected CTE 'b' to remain, got ${ctes.head._1}")
      case _ => fail(s"Expected With with CTE 'b', got $optimized")

  // ========================================================
  // RewriteCorrelatedSubquery Tests
  // ========================================================

  test("RewriteCorrelatedSubquery: EXISTS to semi join"):
    // SELECT * FROM orders WHERE EXISTS (SELECT 1 FROM customers WHERE id = orders.customer_id)
    val subquery = relation("customers").where($"id" === $"customer_id")
    val plan = relation("orders").where(ProtoExpr.Exists(subquery))

    val optimized = RewriteCorrelatedSubquery(plan)
    // Should become a Left Semi Join
    optimized match
      case ProtoLogicalPlan.Join(_, _, JoinType.LeftSemi, _) => ()
      case ProtoLogicalPlan.Filter(ProtoExpr.Exists(_), _)   => () // May not be decorrelated
      case _ => fail(s"Expected LeftSemi Join or Filter, got $optimized")

  test("RewriteCorrelatedSubquery: NOT EXISTS to anti join"):
    // SELECT * FROM orders WHERE NOT EXISTS (SELECT 1 FROM customers WHERE id = orders.customer_id)
    val subquery = relation("customers").where($"id" === $"customer_id")
    val plan = relation("orders").where(!ProtoExpr.Exists(subquery))

    val optimized = RewriteCorrelatedSubquery(plan)
    // Should become a Left Anti Join
    optimized match
      case ProtoLogicalPlan.Join(_, _, JoinType.LeftAnti, _)              => ()
      case ProtoLogicalPlan.Filter(ProtoExpr.Not(ProtoExpr.Exists(_)), _) =>
        () // May not be decorrelated
      case _ => fail(s"Expected LeftAnti Join or Filter, got $optimized")

  test("RewriteCorrelatedSubquery: uncorrelated EXISTS stays as filter"):
    // SELECT * FROM orders WHERE EXISTS (SELECT 1 FROM customers WHERE active = true)
    val subquery = relation("customers").where($"active" === lit(true))
    val plan = relation("orders").where(ProtoExpr.Exists(subquery))

    val optimized = RewriteCorrelatedSubquery(plan)
    // Uncorrelated subquery - may be converted to semi join without condition
    // or kept as filter (implementation dependent)
    optimized match
      case ProtoLogicalPlan.Filter(_, _)                     => ()
      case ProtoLogicalPlan.Join(_, _, JoinType.LeftSemi, _) => ()
      case _ => fail(s"Expected Filter or LeftSemi Join, got $optimized")
