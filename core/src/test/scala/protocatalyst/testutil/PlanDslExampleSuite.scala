package protocatalyst.testutil

import protocatalyst.expr._
import protocatalyst.optimizer.rules._
import protocatalyst.plan._
import protocatalyst.types._

/** Example test suite demonstrating the new DSL and test utilities.
  *
  * This shows how the improved testing infrastructure makes tests more readable and maintainable.
  */
class PlanDslExampleSuite extends munit.FunSuite with PlanTestBase:
  import PlanDsl._

  // ========================================================
  // Example: PruneFilters Tests with DSL
  // ========================================================

  test("PruneFilters: filter with FALSE becomes Limit 0 (DSL style)"):
    val input = relation("t").where(lit(false))
    val result = PruneFilters(input)

    result match
      case ProtoLogicalPlan.Limit(0, _) => ()
      case _                            => fail(s"Expected Limit(0, _), got $result")

  test("PruneFilters: filter with NULL boolean becomes Limit 0 (DSL style)"):
    val input = relation("t").where(litNull(ProtoType.BooleanType))
    val result = PruneFilters(input)

    result match
      case ProtoLogicalPlan.Limit(0, _) => ()
      case _                            => fail(s"Expected Limit(0, _), got $result")

  test("PruneFilters: filter with TRUE remains unchanged"):
    val input = relation("t").where(lit(true))
    val result = PruneFilters(input)

    // Using comparePlans for structural comparison
    comparePlans(result, input)

  // ========================================================
  // Example: Complex Plan Construction
  // ========================================================

  test("DSL: complex query construction"):
    // SELECT a, SUM(b) FROM t WHERE a > 10 GROUP BY a ORDER BY a DESC
    val query = relation("t")
      .where(col("a") > lit(10))
      .groupBy(col("a"))(col("a"), sum(col("b")))
      .orderBy(col("a").desc)

    // Verify structure
    query match
      case ProtoLogicalPlan.Sort(
            _,
            ProtoLogicalPlan.Aggregate(
              _,
              _,
              ProtoLogicalPlan.Filter(_, ProtoLogicalPlan.RelationRef(_, _, _))
            )
          ) =>
        () // Structure is correct
      case _ => fail(s"Unexpected plan structure: $query")

  test("DSL: join construction"):
    val t1 = relation("t1").subquery("x")
    val t2 = relation("t2").subquery("y")

    val joinQuery = t1.innerJoin(t2, col("x", "a") === col("y", "a"))

    joinQuery match
      case ProtoLogicalPlan.Join(_, _, JoinType.Inner, Some(_)) => ()
      case _ => fail(s"Expected inner join with condition, got $joinQuery")

  test("DSL: expression operators"):
    // Test arithmetic
    val arith = col("a") + col("b") * lit(2) - col("c")
    assert(arith.isInstanceOf[ProtoExpr.Subtract])

    // Test comparison
    val cmp = col("a") > lit(10) && col("b") <= lit(20)
    assert(cmp.isInstanceOf[ProtoExpr.And])

    // Test null checks
    val nullCheck = col("a").isNull || col("b").isNotNull
    assert(nullCheck.isInstanceOf[ProtoExpr.Or])

  test("DSL: CASE WHEN construction"):
    val caseExpr = when(col("a") === lit(1), lit("one"))
      .when(col("a") === lit(2), lit("two"))
      .otherwise(lit("other"))

    caseExpr match
      case ProtoExpr.CaseWhen(branches, Some(_)) =>
        assertEquals(branches.size, 2)
      case _ => fail(s"Expected CaseWhen with 2 branches, got $caseExpr")

  // ========================================================
  // Example: comparePlans with Failure
  // ========================================================

  test("comparePlans: demonstrates useful error messages"):
    val plan1 = relation("t")
      .where(col("a") > lit(10))
      .select(col("a"), col("b"))

    val plan2 = relation("t")
      .where(col("a") > lit(10))
      .select(col("a"), col("b"))

    // These should match
    comparePlans(plan1, plan2)

  // ========================================================
  // Example: Set Operations
  // ========================================================

  test("DSL: UNION, INTERSECT, EXCEPT"):
    val t1 = relation("t1").select(col("a"))
    val t2 = relation("t2").select(col("a"))

    val unionPlan = t1.union(t2)
    assert(unionPlan.isInstanceOf[ProtoLogicalPlan.Union])

    val intersectPlan = t1.intersect(t2)
    assert(intersectPlan.isInstanceOf[ProtoLogicalPlan.Intersect])

    val exceptPlan = t1.except(t2)
    assert(exceptPlan.isInstanceOf[ProtoLogicalPlan.Except])

  // ========================================================
  // Example: Rule Testing Pattern
  // ========================================================

  test("RemoveRedundantSorts: nested same sorts are collapsed (DSL style)"):
    val sortOrder = Vector(SortOrder(col("a"), SortDirection.Ascending, NullOrdering.NullsFirst))

    // Sort(Sort(relation))
    val input = relation("t").sort(sortOrder.head).sort(sortOrder.head)

    val result = RemoveRedundantSorts(input)

    // Should have only one sort
    result match
      case ProtoLogicalPlan.Sort(_, ProtoLogicalPlan.RelationRef(_, _, _)) => ()
      case _ => fail(s"Expected single Sort over RelationRef, got $result")

  test("ReplaceDistinctWithAggregate: DISTINCT becomes GROUP BY (DSL style)"):
    val input = relation("t").select(col("a"), col("b")).distinct

    val result = ReplaceDistinctWithAggregate(input)

    result match
      case ProtoLogicalPlan.Aggregate(grouping, _, _) =>
        assertEquals(grouping.size, 2)
      case _ => fail(s"Expected Aggregate, got $result")
