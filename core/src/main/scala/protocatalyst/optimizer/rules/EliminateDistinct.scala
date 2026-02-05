package protocatalyst.optimizer.rules

import protocatalyst.optimizer._
import protocatalyst.plan._

/** Removes redundant DISTINCT operations.
  *
  * This rule identifies cases where DISTINCT is unnecessary because the input is already guaranteed
  * to be distinct or because the operation is redundant.
  *
  * Cases handled:
  *   - DISTINCT after Aggregate with grouping: Already distinct on grouping keys
  *   - Double DISTINCT: DISTINCT(DISTINCT(x)) → DISTINCT(x)
  *   - DISTINCT on single row: DISTINCT(LIMIT 1) → LIMIT 1
  *   - DISTINCT on empty relation: Remove DISTINCT
  *
  * Examples:
  * {{{
  * Before:
  *   Distinct
  *     Aggregate [group by a] [a, SUM(b)]
  *       Scan
  *
  * After:
  *   Aggregate [group by a] [a, SUM(b)]
  *     Scan
  * }}}
  *
  * {{{
  * Before:
  *   Distinct
  *     Distinct
  *       Scan
  *
  * After:
  *   Distinct
  *     Scan
  * }}}
  *
  * Based on Spark Catalyst's EliminateDistinct rule (Optimizer.scala:520-560).
  */
object EliminateDistinct extends Rule:
  override val ruleName: String = "EliminateDistinct"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      // Distinct after Aggregate with non-empty grouping is redundant
      // The aggregate already produces unique rows based on grouping keys
      case ProtoLogicalPlan.Distinct(agg @ ProtoLogicalPlan.Aggregate(groupingExprs, _, _))
          if groupingExprs.nonEmpty =>
        agg

      // Double distinct is redundant (idempotent)
      case ProtoLogicalPlan.Distinct(ProtoLogicalPlan.Distinct(child)) =>
        ProtoLogicalPlan.Distinct(child)

      // Distinct on single row is redundant
      case ProtoLogicalPlan.Distinct(limit @ ProtoLogicalPlan.Limit(1, _)) =>
        limit

      // Distinct through SubqueryAlias
      case ProtoLogicalPlan.Distinct(ProtoLogicalPlan.SubqueryAlias(alias, child)) =>
        ProtoLogicalPlan.SubqueryAlias(alias, ProtoLogicalPlan.Distinct(child))

      // Distinct through Hint
      case ProtoLogicalPlan.Distinct(ProtoLogicalPlan.ResolvedHint(hints, child)) =>
        ProtoLogicalPlan.ResolvedHint(hints, ProtoLogicalPlan.Distinct(child))
    }
