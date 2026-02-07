package protocatalyst.optimizer.rules

import protocatalyst.optimizer._
import protocatalyst.plan._

/** Removes redundant Sort operations.
  *
  * When multiple sorts are stacked or when a sort is not needed, this rule eliminates the redundant
  * ones.
  *
  * Examples:
  *   - `Sort(a ASC, Sort(b DESC, x))` → `Sort(a ASC, x)` (outer sort dominates)
  *   - `Sort(a ASC, Sort(a ASC, x))` → `Sort(a ASC, x)` (same order is redundant)
  *   - `Sort(empty_order, x)` → `x` (empty sort is no-op)
  *
  * Note: This differs from EliminateSorts which removes sorts that are known to be unnecessary
  * based on context (e.g., in subqueries). This rule focuses on redundant/duplicate sorts.
  *
  * Based on Spark Catalyst's RemoveRedundantSorts rule (Optimizer.scala).
  */
object RemoveRedundantSorts extends Rule:
  override val ruleName: String = "RemoveRedundantSorts"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      // Nested sorts - outer sort dominates (inner becomes meaningless)
      case ProtoLogicalPlan.Sort(outerOrder, ProtoLogicalPlan.Sort(_, child)) =>
        ProtoLogicalPlan.Sort(outerOrder, child)

      // Empty sort order is a no-op
      case ProtoLogicalPlan.Sort(order, child) if order.isEmpty =>
        child

      // Sort with same order as another sort child (would need order tracking)
      // This is partially handled by the nested sort rule above
    }
