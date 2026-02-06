package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer.rules._
import protocatalyst.testutil._
import protocatalyst.types._

/** Tests for conditional expression optimization rules: SimplifyConditionals, OptimizeIn, and
  * LikeSimplification.
  *
  * Uses the new test DSL and utilities for improved readability.
  */
class ConditionalSuite extends munit.FunSuite with RuleTestBase:
  import PlanDsl._

  // ========================================================
  // SimplifyConditionals Tests
  // ========================================================

  test("SimplifyConditionals: IF with identical branches → branch value"):
    val expr = ifExpr($"cond", lit(42), lit(42))
    val simplified = SimplifyConditionals.simplifyConditionals(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.IntValue(42)) => ()
      case _                                            => fail(s"Expected 42, got $simplified")

  test("SimplifyConditionals: CASE WHEN TRUE → value"):
    val expr = ProtoExpr.CaseWhen(
      Vector((lit(true), lit("yes"))),
      Some(lit("no"))
    )
    val simplified = SimplifyConditionals.simplifyConditionals(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.StringValue("yes")) => ()
      case _ => fail(s"Expected 'yes', got $simplified")

  test("SimplifyConditionals: CASE WHEN FALSE → else"):
    val expr = ProtoExpr.CaseWhen(
      Vector((lit(false), lit("yes"))),
      Some(lit("no"))
    )
    val simplified = SimplifyConditionals.simplifyConditionals(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.StringValue("no")) => ()
      case _ => fail(s"Expected 'no', got $simplified")

  test("SimplifyConditionals: remove FALSE branches from CASE"):
    val expr = ProtoExpr.CaseWhen(
      Vector(
        (lit(false), lit("a")),
        ($"cond", lit("b"))
      ),
      Some(lit("c"))
    )
    val simplified = SimplifyConditionals.simplifyConditionals(expr)
    simplified match
      case ProtoExpr.If(_, thenVal, elseVal) =>
        assertEquals(thenVal, lit("b"))
        assertEquals(elseVal, lit("c"))
      case _ => fail(s"Expected IF, got $simplified")

  test("SimplifyConditionals: all branches same value → that value"):
    val expr = ProtoExpr.CaseWhen(
      Vector(
        ($"a", lit(42)),
        ($"b", lit(42))
      ),
      Some(lit(42))
    )
    val simplified = SimplifyConditionals.simplifyConditionals(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.IntValue(42)) => ()
      case _                                            => fail(s"Expected 42, got $simplified")

  // ========================================================
  // OptimizeIn Tests
  // ========================================================

  test("OptimizeIn: empty IN → FALSE"):
    val expr = $"a".in()
    val optimized = OptimizeIn.optimizeIn(expr)
    optimized match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => ()
      case _ => fail(s"Expected FALSE, got $optimized")

  test("OptimizeIn: single element IN → equality"):
    val expr = $"a".in(lit(5))
    val optimized = OptimizeIn.optimizeIn(expr)
    optimized match
      case ProtoExpr.Eq(_, ProtoExpr.Literal(LiteralValue.IntValue(5))) => ()
      case _ => fail(s"Expected Eq(a, 5), got $optimized")

  test("OptimizeIn: NULL IN (...) → NULL"):
    val expr = litNull(ProtoType.IntegerType).in(lit(1), lit(2))
    val optimized = OptimizeIn.optimizeIn(expr)
    optimized match
      case ProtoExpr.Literal(LiteralValue.NullValue(_)) => ()
      case _                                            => fail(s"Expected NULL, got $optimized")

  test("OptimizeIn: deduplicate IN list"):
    val expr = $"a".in(lit(1), lit(2), lit(1), lit(3), lit(2))
    val optimized = OptimizeIn.optimizeIn(expr)
    optimized match
      case ProtoExpr.In(_, list) =>
        assertEquals(list.size, 3)
      case _ => fail(s"Expected IN with 3 elements, got $optimized")

  test("OptimizeIn: IN with all NULLs → NULL"):
    val expr = $"a".in(litNull(ProtoType.IntegerType), litNull(ProtoType.IntegerType))
    val optimized = OptimizeIn.optimizeIn(expr)
    optimized match
      case ProtoExpr.Literal(LiteralValue.NullValue(_)) => ()
      case _                                            => fail(s"Expected NULL, got $optimized")

  // ========================================================
  // LikeSimplification Tests
  // ========================================================

  test("LikeSimplification: no wildcards → equality"):
    val expr = $"a".like("hello")
    val simplified = LikeSimplification.simplifyLike(expr)
    simplified match
      case ProtoExpr.Eq(_, ProtoExpr.Literal(LiteralValue.StringValue("hello"))) => ()
      case _ => fail(s"Expected Eq(a, 'hello'), got $simplified")

  test("LikeSimplification: just % → IsNotNull"):
    val expr = $"a".like("%")
    val simplified = LikeSimplification.simplifyLike(expr)
    simplified match
      case ProtoExpr.IsNotNull(_) => ()
      case _                      => fail(s"Expected IsNotNull, got $simplified")

  test("LikeSimplification: prefix% → LOCATE = 1"):
    val expr = $"a".like("abc%")
    val simplified = LikeSimplification.simplifyLike(expr)
    simplified match
      case ProtoExpr.Eq(
            ProtoExpr.StringLocate(_, _, _),
            ProtoExpr.Literal(LiteralValue.IntValue(1))
          ) =>
        ()
      case _ => fail(s"Expected LOCATE = 1, got $simplified")

  test("LikeSimplification: %suffix → LOCATE check"):
    val expr = $"a".like("%xyz")
    val simplified = LikeSimplification.simplifyLike(expr)
    simplified match
      case ProtoExpr.Eq(ProtoExpr.StringLocate(_, _, _), _) => ()
      case _ => fail(s"Expected LOCATE check, got $simplified")

  test("LikeSimplification: %contains% → LOCATE > 0"):
    val expr = $"a".like("%abc%")
    val simplified = LikeSimplification.simplifyLike(expr)
    simplified match
      case ProtoExpr.Gt(
            ProtoExpr.StringLocate(_, _, _),
            ProtoExpr.Literal(LiteralValue.IntValue(0))
          ) =>
        ()
      case _ => fail(s"Expected LOCATE > 0, got $simplified")

  test("LikeSimplification: complex pattern stays as LIKE"):
    val expr = $"a".like("a%b%c")
    val simplified = LikeSimplification.simplifyLike(expr)
    simplified match
      case ProtoExpr.Like(_, _, _) => ()
      case _                       => fail(s"Expected LIKE to remain, got $simplified")
