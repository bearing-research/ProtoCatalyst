package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer.rules.BooleanSimplification
import protocatalyst.testutil._
import protocatalyst.types._

/** Tests for BooleanSimplification optimizer rule.
  *
  * Uses the new test DSL and utilities for improved readability.
  */
class BooleanSimplificationSuite extends munit.FunSuite with RuleTestBase:
  import PlanDsl._

  // ========================================================
  // Double Negation Tests
  // ========================================================

  test("BooleanSimplification: NOT NOT x → x"):
    val expr = !(!$"a")
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    assertEquals(simplified, $"a")

  // ========================================================
  // AND Identity/Annihilator Tests
  // ========================================================

  Seq(
    ExprTestCase("TRUE AND x → x", lit(true) && $"a", $"a"),
    ExprTestCase("x AND TRUE → x", $"a" && lit(true), $"a")
  ).foreach { tc =>
    test(s"BooleanSimplification: ${tc.desc}"):
      compareExpressions(BooleanSimplification.simplifyBoolean(tc.input), tc.expected)
  }

  test("BooleanSimplification: FALSE AND x → FALSE"):
    val expr = lit(false) && $"a"
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => ()
      case _ => fail(s"Expected FALSE, got $simplified")

  // ========================================================
  // OR Identity/Annihilator Tests
  // ========================================================

  test("BooleanSimplification: TRUE OR x → TRUE"):
    val expr = lit(true) || $"a"
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => ()
      case _ => fail(s"Expected TRUE, got $simplified")

  Seq(
    ExprTestCase("FALSE OR x → x", lit(false) || $"a", $"a"),
    ExprTestCase("x OR FALSE → x", $"a" || lit(false), $"a")
  ).foreach { tc =>
    test(s"BooleanSimplification: ${tc.desc}"):
      compareExpressions(BooleanSimplification.simplifyBoolean(tc.input), tc.expected)
  }

  // ========================================================
  // Idempotence Tests
  // ========================================================

  test("BooleanSimplification: x AND x → x"):
    val expr = $"a" && $"a"
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    assertEquals(simplified, $"a")

  test("BooleanSimplification: x OR x → x"):
    val expr = $"a" || $"a"
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    assertEquals(simplified, $"a")

  // ========================================================
  // Contradiction/Tautology Tests
  // ========================================================

  test("BooleanSimplification: x AND NOT x → FALSE"):
    val a = $"a"
    val expr = a && !a
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => ()
      case _ => fail(s"Expected FALSE, got $simplified")

  test("BooleanSimplification: x OR NOT x → TRUE"):
    val a = $"a"
    val expr = a || !a
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => ()
      case _ => fail(s"Expected TRUE, got $simplified")

  // ========================================================
  // Flattening Tests
  // ========================================================

  test("BooleanSimplification: flatten nested ANDs"):
    val expr = ($"a" && $"b") && $"c"
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    simplified match
      case ProtoExpr.And(children) =>
        assertEquals(children.size, 3)
      case _ => fail(s"Expected flattened AND, got $simplified")

  test("BooleanSimplification: flatten nested ORs"):
    val expr = ($"a" || $"b") || $"c"
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    simplified match
      case ProtoExpr.Or(children) =>
        assertEquals(children.size, 3)
      case _ => fail(s"Expected flattened OR, got $simplified")

  // ========================================================
  // NOT Pushdown Tests (De Morgan's Laws)
  // ========================================================

  test("BooleanSimplification: NOT Eq → NotEq"):
    val expr = !($"a" === $"b")
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    simplified match
      case ProtoExpr.NotEq(_, _) => ()
      case _                     => fail(s"Expected NotEq, got $simplified")

  test("BooleanSimplification: NOT Lt → GtEq"):
    val expr = !($"a" < $"b")
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    simplified match
      case ProtoExpr.GtEq(_, _) => ()
      case _                    => fail(s"Expected GtEq, got $simplified")

  test("BooleanSimplification: NOT IsNull → IsNotNull"):
    val expr = !$"a".isNull
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    simplified match
      case ProtoExpr.IsNotNull(_) => ()
      case _                      => fail(s"Expected IsNotNull, got $simplified")
