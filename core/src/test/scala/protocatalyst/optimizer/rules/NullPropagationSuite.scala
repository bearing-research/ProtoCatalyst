package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer.rules.NullPropagation
import protocatalyst.testutil._
import protocatalyst.types._

/** Tests for NullPropagation optimizer rule.
  *
  * Uses the new test DSL and utilities for improved readability.
  */
class NullPropagationSuite extends munit.FunSuite with RuleTestBase:
  import PlanDsl._

  // ========================================================
  // IsNull/IsNotNull on Non-Nullable Column Tests
  // ========================================================

  test("NullPropagation: IsNull on non-nullable column → FALSE"):
    val expr = nonNullCol("a").isNull
    val propagated = NullPropagation.propagateNulls(expr)
    propagated match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => ()
      case _ => fail(s"Expected FALSE, got $propagated")

  test("NullPropagation: IsNotNull on non-nullable column → TRUE"):
    val expr = nonNullCol("a").isNotNull
    val propagated = NullPropagation.propagateNulls(expr)
    propagated match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => ()
      case _ => fail(s"Expected TRUE, got $propagated")

  // ========================================================
  // Arithmetic with NULL Tests
  // ========================================================

  test("NullPropagation: arithmetic with NULL → NULL"):
    val expr = litNull(ProtoType.IntegerType) + lit(5)
    val propagated = NullPropagation.propagateNulls(expr)
    propagated match
      case ProtoExpr.Literal(LiteralValue.NullValue(_)) => ()
      case _                                            => fail(s"Expected NULL, got $propagated")

  test("NullPropagation: comparison with NULL → NULL"):
    val expr = litNull(ProtoType.IntegerType) === lit(5)
    val propagated = NullPropagation.propagateNulls(expr)
    propagated match
      case ProtoExpr.Literal(LiteralValue.NullValue(_)) => ()
      case _                                            => fail(s"Expected NULL, got $propagated")

  // ========================================================
  // IF with NULL Predicate Tests
  // ========================================================

  test("NullPropagation: IF with NULL predicate → else branch"):
    val expr = ifExpr(litNull(ProtoType.BooleanType), lit("yes"), lit("no"))
    val propagated = NullPropagation.propagateNulls(expr)
    propagated match
      case ProtoExpr.Literal(LiteralValue.StringValue("no")) => ()
      case _ => fail(s"Expected 'no', got $propagated")

  // ========================================================
  // COALESCE Tests
  // ========================================================

  test("NullPropagation: COALESCE removes NULL literals"):
    val expr = coalesce(litNull(ProtoType.IntegerType), litNull(ProtoType.IntegerType), $"a")
    val propagated = NullPropagation.propagateNulls(expr)
    propagated match
      case ProtoExpr.Coalesce(Vector(single))  => assertEquals(single, $"a")
      case c @ ProtoExpr.ColumnRef(_, _, _, _) => assertEquals(c, $"a")
      case _ => fail(s"Expected COALESCE(a) or a, got $propagated")

  test("NullPropagation: COALESCE all NULLs → NULL"):
    val expr = coalesce(litNull(ProtoType.IntegerType), litNull(ProtoType.IntegerType))
    val propagated = NullPropagation.propagateNulls(expr)
    propagated match
      case ProtoExpr.Literal(LiteralValue.NullValue(_)) => ()
      case _                                            => fail(s"Expected NULL, got $propagated")

  // ========================================================
  // AND/OR with NULL Tests
  // ========================================================

  test("NullPropagation: AND with NULL and FALSE → FALSE"):
    val expr = ProtoExpr.And(Vector(litNull(ProtoType.BooleanType), lit(false)))
    val propagated = NullPropagation.propagateNulls(expr)
    propagated match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => ()
      case _ => fail(s"Expected FALSE, got $propagated")

  test("NullPropagation: OR with NULL and TRUE → TRUE"):
    val expr = ProtoExpr.Or(Vector(litNull(ProtoType.BooleanType), lit(true)))
    val propagated = NullPropagation.propagateNulls(expr)
    propagated match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => ()
      case _ => fail(s"Expected TRUE, got $propagated")
