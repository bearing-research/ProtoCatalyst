package protocatalyst.optimizer.rules

import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan

/** Removes duplicate expressions from GROUP BY clauses.
  *
  * When the same expression appears multiple times in GROUP BY, only the first occurrence is
  * needed. Removing duplicates simplifies the plan.
  *
  * Examples:
  *   - `GROUP BY a, b, a` → `GROUP BY a, b`
  *   - `GROUP BY x, x, x` → `GROUP BY x`
  *   - `GROUP BY a + 1, b, a + 1` → `GROUP BY a + 1, b`
  *
  * Based on Spark Catalyst's RemoveRepetitionFromGroupExpressions rule (Optimizer.scala:2843).
  */
object RemoveRepetitionFromGroupExpressions extends Rule:
  override val ruleName: String = "RemoveRepetitionFromGroupExpressions"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      case agg @ ProtoLogicalPlan.Aggregate(groupingExprs, aggregateExprs, child)
          if groupingExprs.distinct.size < groupingExprs.size =>
        ProtoLogicalPlan.Aggregate(groupingExprs.distinct, aggregateExprs, child)
    }
