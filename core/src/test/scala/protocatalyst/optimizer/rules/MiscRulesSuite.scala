package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer.rules._
import protocatalyst.plan._
import protocatalyst.testutil._

/** Tests for miscellaneous optimization rules: RemoveRedundantAliases, EliminateOuterJoin,
  * SimplifyCaseConversionExpressions, and CombineConcats.
  *
  * Uses the new test DSL and utilities for improved readability.
  */
class MiscRulesSuite extends munit.FunSuite with RuleTestBase:
  import PlanDsl._

  // ========================================================
  // RemoveRedundantAliases Tests
  // ========================================================

  test("RemoveRedundantAliases: remove x AS x"):
    // x AS x → x
    val expr = intCol("x").as("x")
    val simplified = RemoveRedundantAliases.removeRedundantAliases(expr)
    simplified match
      case ProtoExpr.ColumnRef("x", _, _, _) => ()
      case _                                 => fail(s"Expected ColumnRef(x), got $simplified")

  test("RemoveRedundantAliases: keep x AS y"):
    // x AS y stays as-is
    val expr = intCol("x").as("y")
    val simplified = RemoveRedundantAliases.removeRedundantAliases(expr)
    simplified match
      case ProtoExpr.Alias(ProtoExpr.ColumnRef("x", _, _, _), "y") => ()
      case _ => fail(s"Expected Alias(x, y), got $simplified")

  test("RemoveRedundantAliases: nested aliases with same name"):
    // (x AS a) AS a → x AS a
    val expr = $"x".as("a").as("a")
    val simplified = RemoveRedundantAliases.removeRedundantAliases(expr)
    simplified match
      case ProtoExpr.Alias(_, "a") => ()
      case _                       => fail(s"Expected Alias(_, a), got $simplified")

  test("RemoveRedundantAliases: in project list"):
    val plan = relation("t")
      .select(
        intCol("id").as("id"),
        stringCol("name").as("alias_name")
      )

    val optimized = RemoveRedundantAliases(plan)
    optimized match
      case ProtoLogicalPlan.Project(
            Vector(
              ProtoExpr.ColumnRef("id", _, _, _),
              ProtoExpr.Alias(ProtoExpr.ColumnRef("name", _, _, _), "alias_name")
            ),
            _
          ) =>
        ()
      case _ =>
        fail(s"Expected id to be unwrapped and name AS alias_name to remain, got $optimized")

  // ========================================================
  // EliminateOuterJoin Tests
  // ========================================================

  test("EliminateOuterJoin: LEFT OUTER with IS NOT NULL on right → INNER"):
    // SELECT * FROM left l LEFT JOIN right r ON l.id = r.id WHERE r.col IS NOT NULL
    val left = relation("left").subquery("l")
    val right = relation("right").subquery("r")
    val joinCond = col("l", "id") === col("r", "id")
    val join = ProtoLogicalPlan.Join(left, right, JoinType.LeftOuter, Some(joinCond))
    val filter = ProtoLogicalPlan.Filter(
      col("r", "col").isNotNull,
      join
    )

    val optimized = EliminateOuterJoin(filter)
    optimized match
      case ProtoLogicalPlan.Filter(_, ProtoLogicalPlan.Join(_, _, JoinType.Inner, _)) => ()
      case _ => fail(s"Expected INNER join, got $optimized")

  test("EliminateOuterJoin: LEFT OUTER with comparison on right → INNER"):
    // SELECT * FROM left l LEFT JOIN right r ON l.id = r.id WHERE r.col = 5
    val left = relation("left").subquery("l")
    val right = relation("right").subquery("r")
    val joinCond = col("l", "id") === col("r", "id")
    val join = ProtoLogicalPlan.Join(left, right, JoinType.LeftOuter, Some(joinCond))
    val filter = ProtoLogicalPlan.Filter(
      col("r", "col") === lit(5),
      join
    )

    val optimized = EliminateOuterJoin(filter)
    optimized match
      case ProtoLogicalPlan.Filter(_, ProtoLogicalPlan.Join(_, _, JoinType.Inner, _)) => ()
      case _ => fail(s"Expected INNER join, got $optimized")

  test("EliminateOuterJoin: LEFT OUTER without null-rejecting filter stays LEFT OUTER"):
    // SELECT * FROM left l LEFT JOIN right r ON l.id = r.id WHERE l.col > 0
    val left = relation("left").subquery("l")
    val right = relation("right").subquery("r")
    val joinCond = col("l", "id") === col("r", "id")
    val join = ProtoLogicalPlan.Join(left, right, JoinType.LeftOuter, Some(joinCond))
    val filter = ProtoLogicalPlan.Filter(
      col("l", "col") > lit(0),
      join
    )

    val optimized = EliminateOuterJoin(filter)
    optimized match
      case ProtoLogicalPlan.Filter(_, ProtoLogicalPlan.Join(_, _, JoinType.LeftOuter, _)) => ()
      case _ => fail(s"Expected LEFT OUTER join to remain, got $optimized")

  test("EliminateOuterJoin: RIGHT OUTER with null-rejecting filter on left → INNER"):
    // SELECT * FROM left l RIGHT JOIN right r ON l.id = r.id WHERE l.col IS NOT NULL
    val left = relation("left").subquery("l")
    val right = relation("right").subquery("r")
    val joinCond = col("l", "id") === col("r", "id")
    val join = ProtoLogicalPlan.Join(left, right, JoinType.RightOuter, Some(joinCond))
    val filter = ProtoLogicalPlan.Filter(
      col("l", "col").isNotNull,
      join
    )

    val optimized = EliminateOuterJoin(filter)
    optimized match
      case ProtoLogicalPlan.Filter(_, ProtoLogicalPlan.Join(_, _, JoinType.Inner, _)) => ()
      case _ => fail(s"Expected INNER join, got $optimized")

  // ========================================================
  // SimplifyCaseConversionExpressions Tests
  // ========================================================

  test("SimplifyCaseConversionExpressions: UPPER(UPPER(x)) → UPPER(x)"):
    val expr = ProtoExpr.Upper(ProtoExpr.Upper($"x"))
    val simplified = SimplifyCaseConversionExpressions.simplifyCaseConversion(expr)
    simplified match
      case ProtoExpr.Upper(ProtoExpr.ColumnRef("x", _, _, _)) => ()
      case _ => fail(s"Expected UPPER(x), got $simplified")

  test("SimplifyCaseConversionExpressions: LOWER(LOWER(x)) → LOWER(x)"):
    val expr = ProtoExpr.Lower(ProtoExpr.Lower($"x"))
    val simplified = SimplifyCaseConversionExpressions.simplifyCaseConversion(expr)
    simplified match
      case ProtoExpr.Lower(ProtoExpr.ColumnRef("x", _, _, _)) => ()
      case _ => fail(s"Expected LOWER(x), got $simplified")

  test("SimplifyCaseConversionExpressions: UPPER(LOWER(x)) → UPPER(x)"):
    val expr = ProtoExpr.Upper(ProtoExpr.Lower($"x"))
    val simplified = SimplifyCaseConversionExpressions.simplifyCaseConversion(expr)
    simplified match
      case ProtoExpr.Upper(ProtoExpr.ColumnRef("x", _, _, _)) => ()
      case _ => fail(s"Expected UPPER(x), got $simplified")

  test("SimplifyCaseConversionExpressions: LOWER(UPPER(x)) → LOWER(x)"):
    val expr = ProtoExpr.Lower(ProtoExpr.Upper($"x"))
    val simplified = SimplifyCaseConversionExpressions.simplifyCaseConversion(expr)
    simplified match
      case ProtoExpr.Lower(ProtoExpr.ColumnRef("x", _, _, _)) => ()
      case _ => fail(s"Expected LOWER(x), got $simplified")

  test("SimplifyCaseConversionExpressions: deeply nested"):
    // UPPER(UPPER(UPPER(x))) → UPPER(x)
    val expr = ProtoExpr.Upper(ProtoExpr.Upper(ProtoExpr.Upper($"x")))
    val simplified = SimplifyCaseConversionExpressions.simplifyCaseConversion(expr)
    simplified match
      case ProtoExpr.Upper(ProtoExpr.ColumnRef("x", _, _, _)) => ()
      case _ => fail(s"Expected UPPER(x), got $simplified")

  // ========================================================
  // CombineConcats Tests
  // ========================================================

  test("CombineConcats: CONCAT(CONCAT(a, b), c) → CONCAT(a, b, c)"):
    val expr = ProtoExpr.Concat(
      Vector(
        ProtoExpr.Concat(Vector($"a", $"b")),
        $"c"
      )
    )
    val simplified = CombineConcats.combineConcats(expr)
    simplified match
      case ProtoExpr.Concat(children) if children.size == 3 => ()
      case _ => fail(s"Expected CONCAT with 3 children, got $simplified")

  test("CombineConcats: CONCAT(a, CONCAT(b, c)) → CONCAT(a, b, c)"):
    val expr = ProtoExpr.Concat(
      Vector(
        $"a",
        ProtoExpr.Concat(Vector($"b", $"c"))
      )
    )
    val simplified = CombineConcats.combineConcats(expr)
    simplified match
      case ProtoExpr.Concat(children) if children.size == 3 => ()
      case _ => fail(s"Expected CONCAT with 3 children, got $simplified")

  test("CombineConcats: CONCAT(CONCAT(a, b), CONCAT(c, d)) → CONCAT(a, b, c, d)"):
    val expr = ProtoExpr.Concat(
      Vector(
        ProtoExpr.Concat(Vector($"a", $"b")),
        ProtoExpr.Concat(Vector($"c", $"d"))
      )
    )
    val simplified = CombineConcats.combineConcats(expr)
    simplified match
      case ProtoExpr.Concat(children) if children.size == 4 => ()
      case _ => fail(s"Expected CONCAT with 4 children, got $simplified")

  test("CombineConcats: non-nested CONCAT stays unchanged"):
    val expr = ProtoExpr.Concat(Vector($"a", $"b", $"c"))
    val simplified = CombineConcats.combineConcats(expr)
    simplified match
      case ProtoExpr.Concat(children) if children.size == 3 => ()
      case _ => fail(s"Expected CONCAT with 3 children, got $simplified")

  test("CombineConcats: deeply nested"):
    // CONCAT(CONCAT(CONCAT(a, b), c), d) → CONCAT(a, b, c, d)
    val inner = ProtoExpr.Concat(Vector($"a", $"b"))
    val middle = ProtoExpr.Concat(Vector(inner, $"c"))
    val outer = ProtoExpr.Concat(Vector(middle, $"d"))
    val simplified = CombineConcats.combineConcats(outer)
    simplified match
      case ProtoExpr.Concat(children) if children.size == 4 => ()
      case _ => fail(s"Expected CONCAT with 4 children, got $simplified")
