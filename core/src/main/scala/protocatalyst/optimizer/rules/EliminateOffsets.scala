package protocatalyst.optimizer.rules

import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan

/** Eliminates or simplifies OFFSET operations.
  *
  * Note: ProtoCatalyst's ProtoLogicalPlan doesn't have a direct Offset node, but we can handle
  * patterns where offset is represented via other means. This rule is a placeholder for when Offset
  * support is added.
  *
  * For now, this rule handles patterns that effectively represent offset behavior.
  *
  * Examples (when Offset is supported):
  *   - `OFFSET 0` → remove (no-op)
  *   - `LIMIT n (OFFSET m)` → combined limit/offset handling
  *
  * Based on Spark Catalyst's EliminateOffsets rule (Optimizer.scala:2407).
  */
object EliminateOffsets extends Rule:
  override val ruleName: String = "EliminateOffsets"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    // Currently a no-op since ProtoLogicalPlan doesn't have Offset
    // When Offset is added, implement the following patterns:
    // - Offset(0, child) → child
    // - Offset(n, Offset(m, child)) → Offset(n + m, child)
    plan
