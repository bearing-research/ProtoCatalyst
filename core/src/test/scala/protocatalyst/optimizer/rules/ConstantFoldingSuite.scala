package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer.rules.ConstantFolding
import protocatalyst.testutil._
import protocatalyst.types._

/** Tests for ConstantFolding optimizer rule.
  *
  * Uses the new test DSL and utilities for improved readability.
  */
class ConstantFoldingSuite extends munit.FunSuite with RuleTestBase:
  import PlanDsl._

  // ========================================================
  // Arithmetic Folding Tests
  // ========================================================

  Seq(
    ExprTestCase("5 + 10 = 15", lit(5) + lit(10), lit(15)),
    ExprTestCase("20 - 8 = 12", lit(20) - lit(8), lit(12)),
    ExprTestCase("6 * 7 = 42", lit(6) * lit(7), lit(42))
  ).foreach { tc =>
    test(s"ConstantFolding: arithmetic ${tc.desc}"):
      compareExpressions(ConstantFolding.foldConstants(tc.input), tc.expected)
  }

  test("ConstantFolding: fold integer division"):
    val folded = ConstantFolding.foldConstants(lit(10) / lit(4))
    folded match
      case ProtoExpr.Literal(LiteralValue.DoubleValue(v)) =>
        assertEqualsDouble(v, 2.5, 0.001)
      case _ => fail(s"Expected Literal(2.5), got $folded")

  test("ConstantFolding: division by zero returns null"):
    val folded = ConstantFolding.foldConstants(lit(10) / lit(0))
    folded match
      case ProtoExpr.Literal(LiteralValue.NullValue(_)) => ()
      case _                                            => fail(s"Expected NullValue, got $folded")

  test("ConstantFolding: nested arithmetic (2 + 3) * (4 - 1) = 15"):
    val expr = (lit(2) + lit(3)) * (lit(4) - lit(1))
    compareExpressions(ConstantFolding.foldConstants(expr), lit(15))

  // ========================================================
  // Comparison Folding Tests
  // ========================================================

  Seq(
    ExprTestCase("5 = 5 is true", lit(5) === lit(5), lit(true)),
    ExprTestCase("5 = 6 is false", lit(5) === lit(6), lit(false)),
    ExprTestCase("3 < 5 is true", lit(3) < lit(5), lit(true)),
    ExprTestCase("5 < 3 is false", lit(5) < lit(3), lit(false))
  ).foreach { tc =>
    test(s"ConstantFolding: comparison ${tc.desc}"):
      compareExpressions(ConstantFolding.foldConstants(tc.input), tc.expected)
  }

  // ========================================================
  // Boolean Folding Tests
  // ========================================================

  test("ConstantFolding: fold NOT true = false"):
    compareExpressions(ConstantFolding.foldConstants(!lit(true)), lit(false))

  test("ConstantFolding: fold NOT false = true"):
    compareExpressions(ConstantFolding.foldConstants(!lit(false)), lit(true))

  // ========================================================
  // String Function Folding Tests
  // ========================================================

  test("ConstantFolding: fold CONCAT"):
    val expr = ProtoExpr.Concat(Vector(lit("hello"), lit(" "), lit("world")))
    compareExpressions(ConstantFolding.foldConstants(expr), lit("hello world"))

  test("ConstantFolding: fold UPPER"):
    val expr = ProtoExpr.Upper(lit("hello"))
    compareExpressions(ConstantFolding.foldConstants(expr), lit("HELLO"))

  test("ConstantFolding: fold LOWER"):
    val expr = ProtoExpr.Lower(lit("HELLO"))
    compareExpressions(ConstantFolding.foldConstants(expr), lit("hello"))

  test("ConstantFolding: fold LENGTH"):
    val expr = ProtoExpr.Length(lit("hello"))
    compareExpressions(ConstantFolding.foldConstants(expr), lit(5))

  // ========================================================
  // Null Handling Tests
  // ========================================================

  test("ConstantFolding: fold ABS"):
    val expr = ProtoExpr.Abs(lit(-42))
    compareExpressions(ConstantFolding.foldConstants(expr), lit(42))

  test("ConstantFolding: fold IsNull on non-null"):
    val expr = ProtoExpr.IsNull(lit(42))
    compareExpressions(ConstantFolding.foldConstants(expr), lit(false))

  test("ConstantFolding: fold IsNull on null"):
    val expr = ProtoExpr.IsNull(litNull(ProtoType.IntegerType))
    compareExpressions(ConstantFolding.foldConstants(expr), lit(true))

  test("ConstantFolding: fold Coalesce with first non-null"):
    val expr = coalesce(litNull(ProtoType.IntegerType), lit(42), lit(100))
    compareExpressions(ConstantFolding.foldConstants(expr), lit(42))

  // ========================================================
  // Conditional Expression Folding Tests
  // ========================================================

  test("ConstantFolding: fold IF with true predicate"):
    val expr = ifExpr(lit(true), lit("yes"), lit("no"))
    compareExpressions(ConstantFolding.foldConstants(expr), lit("yes"))

  test("ConstantFolding: fold IF with false predicate"):
    val expr = ifExpr(lit(false), lit("yes"), lit("no"))
    compareExpressions(ConstantFolding.foldConstants(expr), lit("no"))

  // ========================================================
  // Cast Folding Tests
  // ========================================================

  test("ConstantFolding: fold cast Int to Long"):
    val expr = lit(42).cast(ProtoType.LongType)
    val folded = ConstantFolding.foldConstants(expr)
    folded match
      case ProtoExpr.Literal(LiteralValue.LongValue(42L)) => ()
      case _ => fail(s"Expected Literal(42L), got $folded")

  // ========================================================
  // No-Op Tests (should not fold)
  // ========================================================

  test("ConstantFolding: don't fold non-constant expressions"):
    val expr = $"a" + lit(5)
    val folded = ConstantFolding.foldConstants(expr)
    // Should remain unchanged
    assertEquals(folded, expr)
