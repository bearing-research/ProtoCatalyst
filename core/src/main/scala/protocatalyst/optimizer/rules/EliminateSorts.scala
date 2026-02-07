package protocatalyst.optimizer.rules

import protocatalyst.optimizer._
import protocatalyst.plan._

/** Removes unnecessary Sort operations.
  *
  * This rule identifies cases where sorting is unnecessary because:
  *   - The input has only one row (already sorted)
  *   - There's an outer sort that dominates
  *   - The sort is in a position where order doesn't matter
  *
  * Cases handled:
  *   - Sort on LIMIT 1: Single row is always sorted
  *   - Nested sorts: Outer sort dominates, inner sort is removed
  *   - Empty sort order: No-op sort is removed
  *   - Sort in subquery consumed by aggregation: Order irrelevant
  *
  * Examples:
  * {{{
  * Before:
  *   Sort [a ASC]
  *     Limit 1
  *       Scan
  *
  * After:
  *   Limit 1
  *     Scan
  * }}}
  *
  * {{{
  * Before:
  *   Sort [a ASC]
  *     Sort [b DESC]
  *       Scan
  *
  * After:
  *   Sort [a ASC]
  *     Scan
  * }}}
  *
  * Based on Spark Catalyst's EliminateSorts rule (Optimizer.scala:950-1000).
  */
object EliminateSorts extends Rule:
  override val ruleName: String = "EliminateSorts"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      // Sort on single row is redundant
      case ProtoLogicalPlan.Sort(_, limit @ ProtoLogicalPlan.Limit(1, _)) =>
        limit

      // Nested sorts: outer sort dominates
      case ProtoLogicalPlan.Sort(outerOrder, ProtoLogicalPlan.Sort(_, child)) =>
        ProtoLogicalPlan.Sort(outerOrder, child)

      // Empty sort order (no-op)
      case ProtoLogicalPlan.Sort(order, child) if order.isEmpty =>
        child

      // Sort consumed by Aggregate (order doesn't matter for aggregation)
      case agg @ ProtoLogicalPlan.Aggregate(
            grouping,
            aggregates,
            ProtoLogicalPlan.Sort(_, child)
          ) =>
        ProtoLogicalPlan.Aggregate(grouping, aggregates, child)

      // Sort consumed by Distinct (order doesn't matter for distinctness)
      case ProtoLogicalPlan.Distinct(ProtoLogicalPlan.Sort(_, child)) =>
        ProtoLogicalPlan.Distinct(child)

      // Sort through SubqueryAlias: push sort inside
      case ProtoLogicalPlan.Sort(order, ProtoLogicalPlan.SubqueryAlias(alias, child)) =>
        ProtoLogicalPlan.SubqueryAlias(alias, ProtoLogicalPlan.Sort(order, child))

      // Sort through Hint: push sort inside
      case ProtoLogicalPlan.Sort(order, ProtoLogicalPlan.ResolvedHint(hints, child)) =>
        ProtoLogicalPlan.ResolvedHint(hints, ProtoLogicalPlan.Sort(order, child))
    }
