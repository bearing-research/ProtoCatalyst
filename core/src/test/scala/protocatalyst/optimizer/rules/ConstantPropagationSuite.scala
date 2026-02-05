package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer.rules._
import protocatalyst.plan._
import protocatalyst.testutil._
import protocatalyst.types._

/** Tests for ConstantPropagation optimizer rule.
  *
  * Uses the new test DSL and utilities for improved readability.
  */
class ConstantPropagationSuite extends munit.FunSuite with RuleTestBase:
  import PlanDsl._

  // ========================================================
  // Basic Propagation Tests
  // ========================================================

  test("ConstantPropagation: propagate equality constraint"):
    // WHERE id = 5 AND amount > id
    // Should become: WHERE id = 5 AND amount > 5
    val input = relation("t")
      .where(($"id" === lit(5)) && ($"amount" > $"id"))

    val propagated = ConstantPropagation(input)
    propagated match
      case ProtoLogicalPlan.Filter(ProtoExpr.And(conditions), _) =>
        // Second condition should now be amount > 5
        conditions.last match
          case ProtoExpr.Gt(_, ProtoExpr.Literal(LiteralValue.IntValue(5))) => ()
          case _ => fail(s"Expected amount > 5, got ${conditions.last}")
      case _ => fail(s"Expected Filter with AND, got $propagated")

  test("ConstantPropagation: multiple constraints"):
    // WHERE a = 1 AND b = 2 AND c = a + b
    // Should become: WHERE a = 1 AND b = 2 AND c = 1 + 2
    val input = relation("t")
      .where(
        ($"a" === lit(1)) &&
          ($"b" === lit(2)) &&
          ($"c" === ($"a" + $"b"))
      )

    val propagated = ConstantPropagation(input)

    // Extract all conditions from potentially nested ANDs
    def extractConditions(expr: ProtoExpr): Vector[ProtoExpr] = expr match
      case ProtoExpr.And(children) => children.flatMap(extractConditions)
      case other                   => Vector(other)

    propagated match
      case ProtoLogicalPlan.Filter(cond, _) =>
        val allConditions = extractConditions(cond)
        // Find the condition with c = ... and verify it has literals
        val cEquality = allConditions.find {
          case ProtoExpr.Eq(ProtoExpr.ColumnRef("c", _, _, _), _) => true
          case _                                                  => false
        }
        cEquality match
          case Some(
                ProtoExpr.Eq(
                  _,
                  ProtoExpr.Add(
                    ProtoExpr.Literal(LiteralValue.IntValue(1)),
                    ProtoExpr.Literal(LiteralValue.IntValue(2))
                  )
                )
              ) =>
            ()
          case other => fail(s"Expected c = 1 + 2, got $other")
      case _ => fail(s"Expected Filter, got $propagated")

  // ========================================================
  // NULL Constraint Tests
  // ========================================================

  test("ConstantPropagation: don't propagate NULL constraints"):
    // WHERE a = NULL AND b = a (should not propagate NULL)
    val input = relation("t")
      .where(
        ($"a" === litNull(ProtoType.IntegerType)) &&
          ($"b" === $"a")
      )

    val propagated = ConstantPropagation(input)
    propagated match
      case ProtoLogicalPlan.Filter(ProtoExpr.And(conditions), _) =>
        // Second condition should still reference col("a"), not NULL
        conditions(1) match
          case ProtoExpr.Eq(_, ProtoExpr.ColumnRef("a", _, _, _)) => ()
          case other => fail(s"Expected b = a (not propagated), got $other")
      case _ => fail(s"Expected Filter with AND, got $propagated")
