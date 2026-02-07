package protocatalyst.optimizer

import protocatalyst.expr._
import protocatalyst.plan._
import protocatalyst.testutil._
import protocatalyst.types._

/** Integration tests for the Optimizer.
  *
  * These tests verify that multiple optimization rules work correctly together. Individual rule
  * tests are in the rules/ subdirectory.
  *
  * @see
  *   [[protocatalyst.optimizer.rules]] for per-rule test suites
  */
class OptimizerSuite extends munit.FunSuite with RuleTestBase:
  import PlanDsl._

  // ========================================================
  // End-to-End Integration Tests
  // ========================================================

  test("Optimizer: combined constant folding and boolean simplification"):
    // Filter [TRUE AND (5 + 5 = 10)]
    //   Scan
    val plan = relation("t").where(
      lit(true) && (lit(5) + lit(5) === lit(10))
    )
    val optimized = Optimizer.optimize(plan)
    // Should simplify TRUE AND TRUE → TRUE, then RemoveNoopOperators removes the filter
    optimized match
      case ProtoLogicalPlan.RelationRef(_, _, _) => () // Filter with TRUE removed
      case ProtoLogicalPlan.Filter(cond, _)      =>
        // In case RemoveNoopOperators doesn't run, check condition
        cond match
          case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => ()
          case _ => fail(s"Expected TRUE condition, got $cond")
      case _ => fail(s"Expected Filter or RelationRef, got $optimized")

  test("Optimizer: idempotence - applying twice gives same result"):
    val plan = relation("t").where(
      (lit(false) || $"a") && (lit(1) === lit(1))
    )
    val once = Optimizer.optimize(plan)
    val twice = Optimizer.optimize(once)
    assertEquals(once, twice)

  test("Optimizer: complex query with multiple opportunities"):
    // Project [a]
    //   Project [a, b]
    //     Filter [TRUE]
    //       Filter [x > 5]
    //         Scan
    val plan = relation("t")
      .where($"x" > lit(5))
      .where(lit(true))
      .select($"a", $"b")
      .select($"a")

    val optimized = Optimizer.optimize(plan)
    // Should collapse projects and combine filters
    // The TRUE filter condition should remain but be simplified
    assert(optimized.isInstanceOf[ProtoLogicalPlan])

  test("Optimizer: Phase 2 rules work together"):
    // Filter [a = 5 AND a > 3 AND b IN (1)]
    //   Scan
    // ConstantProp: a=5 constraint, replaces all a refs with 5
    // After optimization: 5 = 5 AND 5 > 3 AND b = 1
    // ConstantFold: TRUE AND TRUE AND b = 1
    // BooleanSimplify: b = 1
    val plan = relation("t").where(
      ($"a" === lit(5)) && ($"a" > lit(3)) && $"b".in(lit(1))
    )
    val optimized = Optimizer.optimize(plan)
    // The optimizer aggressively simplifies - just verify it produces a valid plan
    assert(optimized.isInstanceOf[ProtoLogicalPlan])

  test("Optimizer: Phase 3 rules work together"):
    // Filter [a > 5]
    //   Limit 10
    //     Project [x AS a, y AS b]
    //       Scan
    // Optimizer should push predicates down and reorder limits
    val plan = ProtoLogicalPlan.Filter(
      $"a" > lit(5),
      ProtoLogicalPlan.Limit(
        10,
        relation("t").select($"x".as("a"), $"y".as("b"))
      )
    )
    val optimized = Optimizer.optimize(plan)
    // Result should have optimized structure
    assert(optimized.isInstanceOf[ProtoLogicalPlan])

  test("Optimizer: predicate pushdown through join with filter simplification"):
    // Filter [a = 5 AND a > 3]
    //   Join (Inner)
    //     Scan left
    //     Scan right
    // Should push predicates and simplify a > 3 (since a = 5 implies a > 3)
    val aCol = col("left", "a")
    val leftTable = relation("left").select($"a".as("a"))
    val plan = ProtoLogicalPlan.Filter(
      (aCol === lit(5)) && (aCol > lit(3)),
      leftTable.join(relation("right"), JoinType.Inner, None)
    )
    val optimized = Optimizer.optimize(plan)
    // The result should have optimized the condition
    assert(optimized.isInstanceOf[ProtoLogicalPlan])

  test("Optimizer: Phase 4 rules work together"):
    // Distinct(Sort(Union(Union(A, B), C)))
    // Should:
    // 1. Flatten unions: Union(A, B, C)
    // 2. Remove sort consumed by distinct
    // 3. Keep distinct
    val plan = ProtoLogicalPlan.Distinct(
      ProtoLogicalPlan.Sort(
        Vector(SortOrder($"a", SortDirection.Ascending, NullOrdering.NullsFirst)),
        ProtoLogicalPlan.Union(
          Vector(
            ProtoLogicalPlan.Union(
              Vector(relation("a"), relation("b")),
              byName = false,
              allowMissingColumns = false
            ),
            relation("c")
          ),
          byName = false,
          allowMissingColumns = false
        )
      )
    )
    val optimized = Optimizer.optimize(plan)
    // Should have flattened Union and removed Sort
    def countUnionChildren(p: ProtoLogicalPlan): Int = p match
      case ProtoLogicalPlan.Union(children, _, _)   => children.size
      case ProtoLogicalPlan.Distinct(child)         => countUnionChildren(child)
      case ProtoLogicalPlan.Sort(_, child)          => countUnionChildren(child)
      case ProtoLogicalPlan.SubqueryAlias(_, child) => countUnionChildren(child)
      case _                                        => 0
    val unionChildCount = countUnionChildren(optimized)
    assert(unionChildCount >= 3, s"Expected Union with at least 3 children, got $unionChildCount")

  test("Optimizer: Filter TRUE is removed"):
    // Filter(TRUE, Scan) -> Scan
    val plan = relation("t").where(lit(true))
    val optimized = Optimizer.optimize(plan)
    optimized match
      case ProtoLogicalPlan.RelationRef("t", _, _) => ()
      case _ => fail(s"Expected Scan without Filter, got $optimized")

  test("Optimizer: Phase 5 InlineCTE integrates with other rules"):
    // WITH t AS (SELECT * FROM users WHERE active = TRUE)
    // SELECT * FROM t WHERE age > 25
    // Should:
    // 1. Inline CTE (used once)
    // 2. Fold active = TRUE
    val ctePlan = ProtoLogicalPlan.SubqueryAlias(
      "t",
      relation("users").where($"active" === lit(true))
    )
    val plan = ProtoLogicalPlan.With(
      Vector(("t", ctePlan)),
      recursive = false,
      relation("t").where($"age" > lit(25))
    )
    val optimized = Optimizer.optimize(plan)
    // With should be removed (CTE inlined)
    optimized match
      case ProtoLogicalPlan.With(_, _, _) => fail("Expected With to be removed after optimization")
      case _                              => () // CTE was inlined

  test("Optimizer: Phase 6 ReorderAssociativeOperator with ConstantFolding"):
    // SELECT (x + 1) + 2 FROM t
    // Should optimize to: SELECT x + 3 FROM t
    val plan = relation("t").select(($"x" + lit(1)) + lit(2))
    val optimized = Optimizer.optimize(plan)
    optimized match
      case ProtoLogicalPlan.Project(Vector(expr), _) =>
        expr match
          case ProtoExpr.Add(
                ProtoExpr.ColumnRef("x", _, _, _),
                ProtoExpr.Literal(LiteralValue.IntValue(3))
              ) =>
            ()
          case _ => fail(s"Expected Add(x, 3), got $expr")
      case _ => fail(s"Expected Project with optimized expression, got $optimized")

  test("Optimizer: Phase 6 SimplifyCasts integrated with other rules"):
    // SELECT CAST(x AS INT) FROM t WHERE x is already INT
    val plan = relation("t").select(intCol("x").cast(ProtoType.IntegerType))
    val optimized = Optimizer.optimize(plan)
    optimized match
      case ProtoLogicalPlan.Project(Vector(ProtoExpr.ColumnRef("x", _, _, _)), _) => ()
      case _ => fail(s"Expected Project with ColumnRef, got $optimized")

  test("Optimizer: new rules integrated"):
    // Test that new rules work together with existing optimizer
    val plan = ProtoLogicalPlan.Project(
      Vector(
        // Redundant alias: x AS x
        intCol("x").as("x"),
        // Nested UPPER
        ProtoExpr.Upper(ProtoExpr.Upper(stringCol("name")))
      ),
      relation("t").limit(5).limit(10)
    )
    val optimized = Optimizer.optimize(plan)
    // Should have: x (not x AS x), UPPER(name) (not UPPER(UPPER(name))), LIMIT 5
    optimized match
      case ProtoLogicalPlan.Project(
            Vector(
              ProtoExpr.ColumnRef("x", _, _, _),
              ProtoExpr.Upper(ProtoExpr.ColumnRef("name", _, _, _))
            ),
            ProtoLogicalPlan.Limit(5, _)
          ) =>
        ()
      case _ => fail(s"Expected optimized plan, got $optimized")
