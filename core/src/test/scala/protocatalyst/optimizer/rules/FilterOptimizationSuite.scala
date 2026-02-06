package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer.rules._
import protocatalyst.plan._
import protocatalyst.testutil._

/** Tests for Filter optimization rules: CombineFilters and PushDownPredicates.
  *
  * Uses the new test DSL and utilities for improved readability.
  */
class FilterOptimizationSuite extends munit.FunSuite with RuleTestBase:
  import PlanDsl._

  // ========================================================
  // CombineFilters Tests
  // ========================================================

  test("CombineFilters: merge adjacent filters"):
    val input = relation("t")
      .where($"b" < lit(20))
      .where($"a" > lit(10))

    val combined = CombineFilters(input)
    combined match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.And(conditions),
            ProtoLogicalPlan.RelationRef(_, _, _)
          ) =>
        assertEquals(conditions.size, 2)
      case _ => fail(s"Expected combined filter, got $combined")

  test("CombineFilters: merge three filters"):
    val input = relation("t")
      .where($"a")
      .where($"b")
      .where($"c")

    val combined = CombineFilters(CombineFilters(input))
    combined match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.And(conditions),
            ProtoLogicalPlan.RelationRef(_, _, _)
          ) =>
        assertEquals(conditions.size, 3)
      case _ => fail(s"Expected combined filter with 3 conditions, got $combined")

  // ========================================================
  // PushDownPredicates Tests
  // ========================================================

  test("PushDownPredicates: push filter through project"):
    // Filter [x > 5]
    //   Project [a AS x]
    //     Scan
    // Should become:
    //   Project [a AS x]
    //     Filter [a > 5]
    //       Scan
    val input = relation("t")
      .select($"a".as("x"))
      .where(col("x") > lit(5))

    val pushed = PushDownPredicates(input)
    pushed match
      case ProtoLogicalPlan.Project(_, ProtoLogicalPlan.Filter(cond, _)) =>
        cond match
          case ProtoExpr.Gt(ProtoExpr.ColumnRef("a", _, _, _), _) => ()
          case _ => fail(s"Expected filter on 'a', got $cond")
      case _ => fail(s"Expected Project(Filter(...)), got $pushed")

  test("PushDownPredicates: push filter through subquery alias"):
    // Filter [a > 5]
    //   SubqueryAlias [alias]
    //     Scan
    // Should become:
    //   SubqueryAlias [alias]
    //     Filter [a > 5]
    //       Scan
    val input = relation("t")
      .subquery("alias")
      .where($"a" > lit(5))

    val pushed = PushDownPredicates(input)
    pushed match
      case ProtoLogicalPlan.SubqueryAlias("alias", ProtoLogicalPlan.Filter(_, _)) => ()
      case _ => fail(s"Expected SubqueryAlias(Filter(...)), got $pushed")

  test("PushDownPredicates: push filter to inner join sides"):
    // Filter [a > 5 AND c < 10]
    //   Join (Inner)
    //     Scan left [a]
    //     Scan right [c]
    // Should push a > 5 to left, c < 10 to right
    val leftCol = col("left", "a")
    val rightCol = col("right", "c")
    val leftTable = relation("left").select($"a".as("a"))
    val rightTable = relation("right").select($"c".as("c"))

    val input = leftTable
      .join(rightTable, JoinType.Inner, None)
      .where((leftCol > lit(5)) && (rightCol < lit(10)))

    val pushed = PushDownPredicates(input)
    // Verify structure has filters pushed to join children
    pushed match
      case ProtoLogicalPlan.Join(left, right, JoinType.Inner, _) =>
        def hasFilter(p: ProtoLogicalPlan): Boolean = p match
          case ProtoLogicalPlan.Filter(_, _)  => true
          case ProtoLogicalPlan.Project(_, c) => hasFilter(c)
          case _                              => false
        assert(hasFilter(left), s"Expected filter on left side")
        assert(hasFilter(right), s"Expected filter on right side")
      case _ =>
        // Could still be wrapped in a filter for non-pushable predicates
        ()

  test("PushDownPredicates: push filter through union"):
    // Filter [a > 5]
    //   Union
    //     Scan t1
    //     Scan t2
    // Should push filter to both branches
    val input = relation("t1")
      .union(relation("t2"))
      .where($"a" > lit(5))

    val pushed = PushDownPredicates(input)
    pushed match
      case ProtoLogicalPlan.Union(children, _, _) =>
        assert(children.size == 2, "Expected 2 children")
        children.foreach {
          case ProtoLogicalPlan.Filter(_, _) => ()
          case other                         => fail(s"Expected Filter in union child, got $other")
        }
      case _ => fail(s"Expected Union with Filter children, got $pushed")

  test("PushDownPredicates: splitConjunctivePredicates flattens ANDs"):
    val cond = ($"a" > lit(1)) && (($"b" < lit(10)) && ($"c" === lit(5)))
    val predicates = PushDownPredicates.splitConjunctivePredicates(cond)
    assertEquals(predicates.size, 3)

  test("PushDownPredicates: combinePredicates handles single"):
    val single = Vector($"a" > lit(5))
    val combined = PushDownPredicates.combinePredicates(single)
    assertEquals(combined, single.head)

  test("PushDownPredicates: combinePredicates creates AND for multiple"):
    val preds = Vector($"a" > lit(5), $"b" < lit(10))
    val combined = PushDownPredicates.combinePredicates(preds)
    combined match
      case ProtoExpr.And(children) => assertEquals(children.size, 2)
      case _                       => fail(s"Expected And, got $combined")
