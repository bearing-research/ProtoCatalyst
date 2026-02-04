package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan

/** Combine adjacent Filter operators into a single Filter with AND.
  *
  * Merges consecutive filter conditions into a single filter node.
  *
  * Example:
  * ```
  * Before:
  *   Filter [x > 10]
  *     Filter [y < 20]
  *       Scan
  *
  * After:
  *   Filter [x > 10 AND y < 20]
  *     Scan
  * ```
  *
  * Based on Spark Catalyst's CombineFilters rule (Optimizer.scala:1885).
  */
object CombineFilters extends Rule:
  override val ruleName: String = "CombineFilters"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      // Filter over Filter: combine conditions with AND
      case ProtoLogicalPlan.Filter(
            outerCondition,
            ProtoLogicalPlan.Filter(innerCondition, child)
          ) =>
        val combined = combineConditions(outerCondition, innerCondition)
        ProtoLogicalPlan.Filter(combined, child)
    }

  /** Combine two filter conditions with AND.
    *
    * Handles flattening of nested ANDs for cleaner output.
    */
  private def combineConditions(outer: ProtoExpr, inner: ProtoExpr): ProtoExpr =
    (outer, inner) match
      // Both are ANDs - merge children
      case (ProtoExpr.And(outerChildren), ProtoExpr.And(innerChildren)) =>
        ProtoExpr.And(innerChildren ++ outerChildren)

      // Only outer is AND - prepend inner
      case (ProtoExpr.And(outerChildren), _) =>
        ProtoExpr.And(inner +: outerChildren)

      // Only inner is AND - append outer
      case (_, ProtoExpr.And(innerChildren)) =>
        ProtoExpr.And(innerChildren :+ outer)

      // Neither is AND - create new AND
      case _ =>
        ProtoExpr.And(Vector(inner, outer))
