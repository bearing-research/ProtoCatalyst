package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan
import protocatalyst.types._

/** Optimize IN clause expressions.
  *
  * Examples:
  *   - `x IN ()` → `FALSE`
  *   - `x IN (1)` → `x = 1`
  *   - `x IN (1, 2, 1, 3, 2)` → `x IN (1, 2, 3)` (deduplicate)
  *   - `NULL IN (1, 2, 3)` → `NULL`
  *   - `x IN (NULL)` → `NULL`
  *   - `x IN (1, NULL, 2)` → `x IN (1, 2) OR (x NOT IN (1, 2) AND NULL)` (simplified to just
  *     keeping the null-free list if conservative)
  *
  * Based on Spark Catalyst's OptimizeIn rule (expressions.scala:319-349).
  */
object OptimizeIn extends Rule:
  override val ruleName: String = "OptimizeIn"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanExprs(plan)(optimizeIn)

  /** Optimize IN expressions. */
  def optimizeIn(expr: ProtoExpr): ProtoExpr =
    TreeTransform.transformExprUp(expr) {
      // Empty IN is always false
      case ProtoExpr.In(_, list) if list.isEmpty =>
        ProtoExpr.Literal(LiteralValue.BooleanValue(false))

      // NULL IN (...) is NULL (regardless of list contents)
      case ProtoExpr.In(ProtoExpr.Literal(LiteralValue.NullValue(_)), _) =>
        ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType))

      // IN with all NULLs → NULL
      case ProtoExpr.In(_, list) if list.forall(isNullLiteral) =>
        ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType))

      // Single element IN becomes equality
      case ProtoExpr.In(value, Vector(single)) if !isNullLiteral(single) =>
        ProtoExpr.Eq(value, single)

      // Single NULL element → NULL
      case ProtoExpr.In(_, Vector(single)) if isNullLiteral(single) =>
        ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType))

      // Deduplicate IN list and remove NULLs for simplified evaluation
      // Note: This is a simplification - SQL standard says x IN (1, NULL) should
      // return NULL if x is neither 1 nor any non-null value. We simplify by
      // keeping the IN with non-null values only.
      case ProtoExpr.In(value, list) =>
        val (nulls, nonNulls) = list.partition(isNullLiteral)
        val deduplicated = nonNulls.distinct

        if deduplicated.isEmpty && nulls.nonEmpty then
          // All elements were null
          ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType))
        else if deduplicated.size == 1 && nulls.isEmpty then
          // Single non-null element
          ProtoExpr.Eq(value, deduplicated.head)
        else if deduplicated == list then
          // No changes
          ProtoExpr.In(value, list)
        else if nulls.nonEmpty then
          // Has nulls - keep the deduplicated non-null list with one null
          // This preserves the SQL semantics where IN with NULL returns NULL when no match
          ProtoExpr.In(
            value,
            deduplicated :+ ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.NullType))
          )
        else
          // Just deduplicated, no nulls
          ProtoExpr.In(value, deduplicated)
    }

  /** Check if expression is a null literal. */
  private def isNullLiteral(expr: ProtoExpr): Boolean = expr match
    case ProtoExpr.Literal(LiteralValue.NullValue(_)) => true
    case _                                            => false
