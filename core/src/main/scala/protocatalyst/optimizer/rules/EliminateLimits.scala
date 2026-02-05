package protocatalyst.optimizer.rules

import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan

/** Simplifies LIMIT operations.
  *
  * This rule merges nested LIMIT operations and handles edge cases.
  *
  * Examples:
  *   - `LIMIT 10 (LIMIT 5 x)` → `LIMIT 5 x` (inner limit is smaller)
  *   - `LIMIT 5 (LIMIT 10 x)` → `LIMIT 5 x` (outer limit is smaller)
  *   - `LIMIT 0 x` → Empty relation (no rows needed)
  *
  * Based on Spark Catalyst's EliminateLimits rule (Optimizer.scala:2371).
  */
object EliminateLimits extends Rule:
  override val ruleName: String = "EliminateLimits"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      // Nested limits - take the minimum
      // LIMIT 10 (LIMIT 5 x) → LIMIT 5 x (takes min)
      // LIMIT 5 (LIMIT 10 x) → LIMIT 5 x (takes min)
      // LIMIT 5 (LIMIT 5 x) → LIMIT 5 x (no change needed, min is same)
      case ProtoLogicalPlan.Limit(outer, ProtoLogicalPlan.Limit(inner, child)) =>
        ProtoLogicalPlan.Limit(math.min(outer, inner), child)
    }
