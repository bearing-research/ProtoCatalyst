package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan
import protocatalyst.types.LiteralValue

/** Boolean simplification optimization rule.
  *
  * Simplifies boolean expressions using logical identities.
  *
  * Examples:
  *   - `TRUE AND x` → `x`
  *   - `FALSE AND x` → `FALSE`
  *   - `TRUE OR x` → `TRUE`
  *   - `FALSE OR x` → `x`
  *   - `NOT NOT x` → `x`
  *   - `x AND x` → `x`
  *   - `x OR x` → `x`
  *   - `x AND NOT x` → `FALSE`
  *   - `x OR NOT x` → `TRUE`
  *
  * Based on Spark Catalyst's BooleanSimplification rule (expressions.scala:359-588).
  */
object BooleanSimplification extends Rule:
  override val ruleName: String = "BooleanSimplification"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanExprs(plan)(simplifyBoolean)

  /** Simplify boolean expressions.
    */
  def simplifyBoolean(expr: ProtoExpr): ProtoExpr =
    TreeTransform.transformExprUp(expr) {
      // Double negation: NOT NOT x → x
      case ProtoExpr.Not(ProtoExpr.Not(e)) => e

      // NOT with literal: NOT TRUE → FALSE, NOT FALSE → TRUE
      case ProtoExpr.Not(ProtoExpr.Literal(LiteralValue.BooleanValue(b))) =>
        ProtoExpr.Literal(LiteralValue.BooleanValue(!b))

      // De Morgan's laws (push NOT inward)
      case ProtoExpr.Not(ProtoExpr.And(children)) =>
        ProtoExpr.Or(children.map(c => ProtoExpr.Not(c)))

      case ProtoExpr.Not(ProtoExpr.Or(children)) =>
        ProtoExpr.And(children.map(c => ProtoExpr.Not(c)))

      // AND simplification
      case and @ ProtoExpr.And(children) =>
        simplifyAnd(children)

      // OR simplification
      case or @ ProtoExpr.Or(children) =>
        simplifyOr(children)

      // NOT comparison inversions
      case ProtoExpr.Not(ProtoExpr.Eq(l, r))    => ProtoExpr.NotEq(l, r)
      case ProtoExpr.Not(ProtoExpr.NotEq(l, r)) => ProtoExpr.Eq(l, r)
      case ProtoExpr.Not(ProtoExpr.Lt(l, r))    => ProtoExpr.GtEq(l, r)
      case ProtoExpr.Not(ProtoExpr.LtEq(l, r))  => ProtoExpr.Gt(l, r)
      case ProtoExpr.Not(ProtoExpr.Gt(l, r))    => ProtoExpr.LtEq(l, r)
      case ProtoExpr.Not(ProtoExpr.GtEq(l, r))  => ProtoExpr.Lt(l, r)

      // NOT IsNull / IsNotNull
      case ProtoExpr.Not(ProtoExpr.IsNull(e))    => ProtoExpr.IsNotNull(e)
      case ProtoExpr.Not(ProtoExpr.IsNotNull(e)) => ProtoExpr.IsNull(e)
    }

  private val trueExpr = ProtoExpr.Literal(LiteralValue.BooleanValue(true))
  private val falseExpr = ProtoExpr.Literal(LiteralValue.BooleanValue(false))

  /** Simplify AND expression.
    *
    * Rules applied:
    *   - FALSE AND x → FALSE
    *   - TRUE AND x → x
    *   - x AND x → x
    *   - x AND NOT x → FALSE
    *   - Flatten nested ANDs
    *   - Remove duplicates
    */
  private def simplifyAnd(children: Vector[ProtoExpr]): ProtoExpr =
    // Flatten nested ANDs
    val flattened = children.flatMap {
      case ProtoExpr.And(nested) => nested
      case other                 => Vector(other)
    }

    // Check for FALSE - short circuit
    if flattened.contains(falseExpr) then return falseExpr

    // Remove TRUE literals
    val withoutTrue = flattened.filterNot(_ == trueExpr)

    if withoutTrue.isEmpty then return trueExpr

    // Remove duplicates (preserving order)
    val deduplicated = withoutTrue.distinct

    // Check for x AND NOT x patterns → FALSE
    val hasContradiction = deduplicated.exists { e =>
      deduplicated.exists {
        case ProtoExpr.Not(inner) => exprsEqual(inner, e)
        case _                    => false
      }
    }

    if hasContradiction then return falseExpr

    // Build result
    deduplicated match
      case Vector(single) => single
      case multiple       => ProtoExpr.And(multiple)

  /** Simplify OR expression.
    *
    * Rules applied:
    *   - TRUE OR x → TRUE
    *   - FALSE OR x → x
    *   - x OR x → x
    *   - x OR NOT x → TRUE
    *   - Flatten nested ORs
    *   - Remove duplicates
    */
  private def simplifyOr(children: Vector[ProtoExpr]): ProtoExpr =
    // Flatten nested ORs
    val flattened = children.flatMap {
      case ProtoExpr.Or(nested) => nested
      case other                => Vector(other)
    }

    // Check for TRUE - short circuit
    if flattened.contains(trueExpr) then return trueExpr

    // Remove FALSE literals
    val withoutFalse = flattened.filterNot(_ == falseExpr)

    if withoutFalse.isEmpty then return falseExpr

    // Remove duplicates (preserving order)
    val deduplicated = withoutFalse.distinct

    // Check for x OR NOT x patterns → TRUE
    val hasTautology = deduplicated.exists { e =>
      deduplicated.exists {
        case ProtoExpr.Not(inner) => exprsEqual(inner, e)
        case _                    => false
      }
    }

    if hasTautology then return trueExpr

    // Build result
    deduplicated match
      case Vector(single) => single
      case multiple       => ProtoExpr.Or(multiple)

  /** Check if two expressions are structurally equal.
    */
  private def exprsEqual(e1: ProtoExpr, e2: ProtoExpr): Boolean =
    e1 == e2
