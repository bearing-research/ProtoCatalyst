package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer.rules._
import protocatalyst.testutil._
import protocatalyst.types._

/** Tests for expression simplification rules: SimplifyCasts, SimplifyBinaryComparison, and ReorderAssociativeOperator.
  *
  * Uses the new test DSL and utilities for improved readability.
  */
class ExpressionSimplificationSuite extends munit.FunSuite with RuleTestBase:
  import PlanDsl._

  // ========================================================
  // SimplifyCasts Tests
  // ========================================================

  test("SimplifyCasts: cast to same type is eliminated"):
    // CAST(int_col AS INT) -> int_col
    val xCol = intCol("x")
    val expr = xCol.cast(ProtoType.IntegerType)
    val simplified = SimplifyCasts.simplifyCasts(expr)
    simplified match
      case ProtoExpr.ColumnRef("x", _, ProtoType.IntegerType, _) => ()
      case _ => fail(s"Expected ColumnRef x, got $simplified")

  test("SimplifyCasts: nested casts to same type are collapsed"):
    // CAST(CAST(x AS BIGINT) AS BIGINT) -> CAST(x AS BIGINT)
    val xCol = intCol("x")
    val innerCast = xCol.cast(ProtoType.LongType)
    val outerCast = innerCast.cast(ProtoType.LongType)
    val simplified = SimplifyCasts.simplifyCasts(outerCast)
    simplified match
      case ProtoExpr.Cast(ProtoExpr.ColumnRef("x", _, _, _), ProtoType.LongType) => ()
      case _ => fail(s"Expected single Cast to LongType, got $simplified")

  test("SimplifyCasts: widening casts are collapsed"):
    // CAST(CAST(x AS INT) AS BIGINT) -> CAST(x AS BIGINT)
    val byteCol = colOf("x", ProtoType.ByteType)
    val innerCast = byteCol.cast(ProtoType.IntegerType)
    val outerCast = innerCast.cast(ProtoType.LongType)
    val simplified = SimplifyCasts.simplifyCasts(outerCast)
    simplified match
      case ProtoExpr.Cast(ProtoExpr.ColumnRef("x", _, _, _), ProtoType.LongType) => ()
      case _ => fail(s"Expected single Cast to LongType, got $simplified")

  // ========================================================
  // SimplifyBinaryComparison Tests
  // ========================================================

  test("SimplifyBinaryComparison: x = x (non-nullable) → TRUE"):
    val col = nonNullCol("x")
    val expr = col === col
    val simplified = SimplifyBinaryComparison.simplifyComparisons(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => ()
      case _ => fail(s"Expected TRUE, got $simplified")

  test("SimplifyBinaryComparison: x = x (nullable) is NOT simplified"):
    val col = $"x"
    val expr = col === col
    val simplified = SimplifyBinaryComparison.simplifyComparisons(expr)
    // Should stay as Eq since x could be NULL and NULL = NULL is NULL, not TRUE
    simplified match
      case ProtoExpr.Eq(_, _) => ()
      case _                  => fail(s"Expected Eq to remain, got $simplified")

  test("SimplifyBinaryComparison: x != x (non-nullable) → FALSE"):
    val col = nonNullCol("x")
    val expr = col =!= col
    val simplified = SimplifyBinaryComparison.simplifyComparisons(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => ()
      case _ => fail(s"Expected FALSE, got $simplified")

  test("SimplifyBinaryComparison: x < x → FALSE"):
    val col = $"x"
    val expr = col < col
    val simplified = SimplifyBinaryComparison.simplifyComparisons(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => ()
      case _ => fail(s"Expected FALSE, got $simplified")

  test("SimplifyBinaryComparison: x > x → FALSE"):
    val col = $"x"
    val expr = col > col
    val simplified = SimplifyBinaryComparison.simplifyComparisons(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => ()
      case _ => fail(s"Expected FALSE, got $simplified")

  test("SimplifyBinaryComparison: x <= x (non-nullable) → TRUE"):
    val col = nonNullCol("x")
    val expr = col <= col
    val simplified = SimplifyBinaryComparison.simplifyComparisons(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => ()
      case _ => fail(s"Expected TRUE, got $simplified")

  test("SimplifyBinaryComparison: x >= x (non-nullable) → TRUE"):
    val col = nonNullCol("x")
    val expr = col >= col
    val simplified = SimplifyBinaryComparison.simplifyComparisons(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => ()
      case _ => fail(s"Expected TRUE, got $simplified")

  // ========================================================
  // ReorderAssociativeOperator Tests
  // ========================================================

  test("ReorderAssociativeOperator: fold (x + 1) + 2 to x + 3"):
    // (x + 1) + 2 -> x + 3
    val expr = ($"x" + lit(1)) + lit(2)
    val reordered = ReorderAssociativeOperator.reorderAssociative(expr)
    reordered match
      case ProtoExpr.Add(colRef, ProtoExpr.Literal(LiteralValue.IntValue(3))) =>
        colRef match
          case ProtoExpr.ColumnRef("x", _, _, _) => ()
          case _                                 => fail(s"Expected ColumnRef x, got $colRef")
      case _ => fail(s"Expected Add(x, 3), got $reordered")

  test("ReorderAssociativeOperator: fold 1 + (x + 2) to x + 3"):
    // 1 + (x + 2) -> x + 3
    val expr = lit(1) + ($"x" + lit(2))
    val reordered = ReorderAssociativeOperator.reorderAssociative(expr)
    reordered match
      case ProtoExpr.Add(colRef, ProtoExpr.Literal(LiteralValue.IntValue(3))) =>
        colRef match
          case ProtoExpr.ColumnRef("x", _, _, _) => ()
          case _                                 => fail(s"Expected ColumnRef x, got $colRef")
      case _ => fail(s"Expected Add(x, 3), got $reordered")

  test("ReorderAssociativeOperator: fold (x * 2) * 3 to x * 6"):
    // (x * 2) * 3 -> x * 6
    val expr = ($"x" * lit(2)) * lit(3)
    val reordered = ReorderAssociativeOperator.reorderAssociative(expr)
    reordered match
      case ProtoExpr.Multiply(colRef, ProtoExpr.Literal(LiteralValue.IntValue(6))) =>
        colRef match
          case ProtoExpr.ColumnRef("x", _, _, _) => ()
          case _                                 => fail(s"Expected ColumnRef x, got $colRef")
      case _ => fail(s"Expected Multiply(x, 6), got $reordered")

  test("ReorderAssociativeOperator: fold (1 + x) + (2 + y) to (x + y) + 3"):
    // (1 + x) + (2 + y) -> (x + y) + 3
    val expr = (lit(1) + $"x") + (lit(2) + $"y")
    val reordered = ReorderAssociativeOperator.reorderAssociative(expr)
    // Result should be ((x + y) + 3) or similar
    reordered match
      case ProtoExpr.Add(ProtoExpr.Add(left, right), ProtoExpr.Literal(LiteralValue.IntValue(3))) =>
        // left and right should be x and y in some order
        val cols = Set(left, right).collect { case ProtoExpr.ColumnRef(name, _, _, _) => name }
        assertEquals(cols, Set("x", "y"))
      case _ => fail(s"Expected Add(Add(x, y), 3), got $reordered")

  test("ReorderAssociativeOperator: multiplication by zero"):
    // x * 0 * y -> 0
    val expr = ($"x" * lit(0)) * $"y"
    val reordered = ReorderAssociativeOperator.reorderAssociative(expr)
    reordered match
      case ProtoExpr.Literal(LiteralValue.IntValue(0)) => ()
      case _ => fail(s"Expected Literal(0), got $reordered")

  test("ReorderAssociativeOperator: multiplication by one is eliminated"):
    // x * 1 -> x
    val expr = $"x" * lit(1)
    val reordered = ReorderAssociativeOperator.reorderAssociative(expr)
    reordered match
      case ProtoExpr.ColumnRef("x", _, _, _) => ()
      case _                                 => fail(s"Expected ColumnRef x, got $reordered")

  test("ReorderAssociativeOperator: merge adjacent string literals in concat"):
    // CONCAT(s, 'a', 'b') -> CONCAT(s, 'ab')
    val expr = ProtoExpr.Concat(Vector($"s", lit("a"), lit("b")))
    val reordered = ReorderAssociativeOperator.reorderAssociative(expr)
    reordered match
      case ProtoExpr.Concat(Vector(colRef, ProtoExpr.Literal(LiteralValue.StringValue("ab")))) =>
        colRef match
          case ProtoExpr.ColumnRef("s", _, _, _) => ()
          case _                                 => fail(s"Expected ColumnRef s, got $colRef")
      case _ => fail(s"Expected Concat(s, 'ab'), got $reordered")

  test("ReorderAssociativeOperator: nested concat flattening"):
    // CONCAT(CONCAT(s, 'a'), 'b', 'c') -> CONCAT(s, 'abc')
    val expr = ProtoExpr.Concat(
      Vector(
        ProtoExpr.Concat(Vector($"s", lit("a"))),
        lit("b"),
        lit("c")
      )
    )
    val reordered = ReorderAssociativeOperator.reorderAssociative(expr)
    reordered match
      case ProtoExpr.Concat(Vector(colRef, ProtoExpr.Literal(LiteralValue.StringValue("abc")))) =>
        colRef match
          case ProtoExpr.ColumnRef("s", _, _, _) => ()
          case _                                 => fail(s"Expected ColumnRef s, got $colRef")
      case _ => fail(s"Expected Concat(s, 'abc'), got $reordered")

  test("ReorderAssociativeOperator: no change when no constants to fold"):
    // x + y stays as x + y
    val expr = $"x" + $"y"
    val reordered = ReorderAssociativeOperator.reorderAssociative(expr)
    reordered match
      case ProtoExpr.Add(
            ProtoExpr.ColumnRef("x", _, _, _),
            ProtoExpr.ColumnRef("y", _, _, _)
          ) =>
        ()
      case _ => fail(s"Expected Add(x, y), got $reordered")
