package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan
import protocatalyst.types._

/** Simplifies binary comparisons with known outcomes.
  *
  * This rule identifies comparisons where the result can be determined statically, such as
  * comparing an expression to itself.
  *
  * Examples:
  *   - `x = x` (non-nullable) → `TRUE`
  *   - `x != x` (non-nullable) → `FALSE`
  *   - `x < x` → `FALSE`
  *   - `x > x` → `FALSE`
  *   - `x <= x` (non-nullable) → `TRUE`
  *   - `x >= x` (non-nullable) → `TRUE`
  *
  * Note: When the expression is nullable, `x = x` could be NULL (when x is NULL), so we only
  * simplify for non-nullable expressions.
  *
  * Based on Spark Catalyst's SimplifyBinaryComparison rule.
  */
object SimplifyBinaryComparison extends Rule:
  override val ruleName: String = "SimplifyBinaryComparison"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanExprs(plan)(simplifyComparisons)

  /** Simplify binary comparisons in an expression tree.
    */
  def simplifyComparisons(expr: ProtoExpr): ProtoExpr =
    TreeTransform.transformExprUp(expr) {
      // x = x → TRUE (only if not nullable, since NULL = NULL is NULL, not TRUE)
      case ProtoExpr.Eq(left, right) if left == right && !isNullable(left) =>
        ProtoExpr.Literal(LiteralValue.BooleanValue(true))

      // x != x → FALSE (only if not nullable)
      case ProtoExpr.NotEq(left, right) if left == right && !isNullable(left) =>
        ProtoExpr.Literal(LiteralValue.BooleanValue(false))

      // x < x → FALSE (always, even for nullable - if x is NULL, result is NULL which is falsy)
      case ProtoExpr.Lt(left, right) if left == right =>
        ProtoExpr.Literal(LiteralValue.BooleanValue(false))

      // x > x → FALSE (always)
      case ProtoExpr.Gt(left, right) if left == right =>
        ProtoExpr.Literal(LiteralValue.BooleanValue(false))

      // x <= x → TRUE (only if not nullable)
      case ProtoExpr.LtEq(left, right) if left == right && !isNullable(left) =>
        ProtoExpr.Literal(LiteralValue.BooleanValue(true))

      // x >= x → TRUE (only if not nullable)
      case ProtoExpr.GtEq(left, right) if left == right && !isNullable(left) =>
        ProtoExpr.Literal(LiteralValue.BooleanValue(true))
    }

  /** Check if an expression could produce NULL.
    */
  private def isNullable(expr: ProtoExpr): Boolean = expr match
    case ProtoExpr.Literal(LiteralValue.NullValue(_)) => true
    case ProtoExpr.Literal(_)                         => false
    case ProtoExpr.ColumnRef(_, _, _, nullable)       => nullable
    case ProtoExpr.BoundRef(_, _, nullable)           => nullable
    case ProtoExpr.Alias(child, _)                    => isNullable(child)
    case ProtoExpr.Cast(child, _)                     => isNullable(child)
    case ProtoExpr.Coalesce(children)                 => children.forall(isNullable)
    case ProtoExpr.If(_, trueVal, falseVal)           => isNullable(trueVal) || isNullable(falseVal)
    case ProtoExpr.CaseWhen(branches, elseVal)        =>
      branches.exists { case (_, v) => isNullable(v) } || elseVal.exists(isNullable)
    // Arithmetic operations propagate nullability
    case ProtoExpr.Add(left, right)      => isNullable(left) || isNullable(right)
    case ProtoExpr.Subtract(left, right) => isNullable(left) || isNullable(right)
    case ProtoExpr.Multiply(left, right) => isNullable(left) || isNullable(right)
    case ProtoExpr.Divide(_, _)          => true // Division can produce NULL (div by zero)
    // String operations
    case ProtoExpr.Concat(children) => children.exists(isNullable)
    case ProtoExpr.Upper(child)     => isNullable(child)
    case ProtoExpr.Lower(child)     => isNullable(child)
    case ProtoExpr.Length(child)    => isNullable(child)
    // Null check operations are never null
    case ProtoExpr.IsNull(_)    => false
    case ProtoExpr.IsNotNull(_) => false
    // Comparison results can be null if operands are null
    case ProtoExpr.Eq(left, right)    => isNullable(left) || isNullable(right)
    case ProtoExpr.NotEq(left, right) => isNullable(left) || isNullable(right)
    case ProtoExpr.Lt(left, right)    => isNullable(left) || isNullable(right)
    case ProtoExpr.LtEq(left, right)  => isNullable(left) || isNullable(right)
    case ProtoExpr.Gt(left, right)    => isNullable(left) || isNullable(right)
    case ProtoExpr.GtEq(left, right)  => isNullable(left) || isNullable(right)
    // Boolean operations - AND/OR have special null handling but can still produce null
    case ProtoExpr.And(children) => children.exists(isNullable)
    case ProtoExpr.Or(children)  => children.exists(isNullable)
    case ProtoExpr.Not(child)    => isNullable(child)
    // Aggregates can be null (e.g., AVG of empty set)
    case ProtoExpr.Sum(_)      => true
    case ProtoExpr.Avg(_)      => true
    case ProtoExpr.Min(_)      => true
    case ProtoExpr.Max(_)      => true
    case ProtoExpr.Count(_, _) => false // COUNT never returns null
    // Subqueries
    case ProtoExpr.ScalarSubquery(_) => true // Scalar subqueries can be null
    case ProtoExpr.Exists(_)         => false // EXISTS returns boolean, never null
    case ProtoExpr.InSubquery(_, _)  => true // IN can be null
    // Default: assume nullable for safety
    case _ => true
