package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan
import protocatalyst.types._

/** Removes or simplifies filters based on their conditions.
  *
  * This rule handles filters with constant or determinable conditions:
  *   - `Filter(TRUE, child)` → `child` (always passes, already in RemoveNoopOperators)
  *   - `Filter(FALSE, child)` → empty (no rows pass)
  *   - `Filter(NULL, child)` → empty (NULL in boolean context is falsy)
  *
  * Note: We can't fully eliminate to EmptyRelation without schema info, but we convert FALSE filters
  * to a standard form that other rules can handle.
  *
  * Based on Spark Catalyst's PruneFilters rule (Optimizer.scala:2001-2036).
  */
object PruneFilters extends Rule:
  override val ruleName: String = "PruneFilters"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      // Filter with FALSE condition produces no rows
      // Convert to a Limit(0, child) which represents empty result
      case ProtoLogicalPlan.Filter(condition, child) if isFalseLiteral(condition) =>
        ProtoLogicalPlan.Limit(0, child)

      // Filter with NULL condition also produces no rows (NULL is treated as FALSE in WHERE)
      case ProtoLogicalPlan.Filter(condition, child) if isNullLiteral(condition) =>
        ProtoLogicalPlan.Limit(0, child)
    }

  private def isFalseLiteral(expr: ProtoExpr): Boolean = expr match
    case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => true
    case _                                                   => false

  private def isNullLiteral(expr: ProtoExpr): Boolean = expr match
    case ProtoExpr.Literal(LiteralValue.NullValue(_)) => true
    case _                                            => false
