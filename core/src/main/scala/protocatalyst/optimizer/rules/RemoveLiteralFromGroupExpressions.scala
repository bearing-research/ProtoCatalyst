package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan

/** Removes literal (constant) expressions from GROUP BY clauses.
  *
  * Grouping by a constant has no effect since all rows have the same value for that constant.
  * Removing these simplifies the plan and reduces overhead.
  *
  * Examples:
  *   - `GROUP BY a, 1, b` → `GROUP BY a, b`
  *   - `GROUP BY 'constant', a` → `GROUP BY a`
  *   - `GROUP BY 1, 2, 3` → (empty grouping) - all constants removed
  *
  * Note: If all grouping expressions are literals, we keep at least one to preserve semantics (the
  * result would be a single group).
  *
  * Based on Spark Catalyst's RemoveLiteralFromGroupExpressions rule (Optimizer.scala:2765).
  */
object RemoveLiteralFromGroupExpressions extends Rule:
  override val ruleName: String = "RemoveLiteralFromGroupExpressions"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      case agg @ ProtoLogicalPlan.Aggregate(groupingExprs, aggregateExprs, child)
          if groupingExprs.exists(isLiteral) =>
        val nonLiterals = groupingExprs.filterNot(isLiteral)
        // If all were literals, keep empty grouping (single group semantics)
        ProtoLogicalPlan.Aggregate(nonLiterals, aggregateExprs, child)
    }

  private def isLiteral(expr: ProtoExpr): Boolean = expr match
    case ProtoExpr.Literal(_) => true
    case _                    => false
