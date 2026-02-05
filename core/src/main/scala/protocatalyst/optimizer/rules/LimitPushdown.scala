package protocatalyst.optimizer.rules

import protocatalyst.optimizer._
import protocatalyst.plan._

/** Pushes LIMIT operations closer to data sources to reduce processing.
  *
  * This rule optimizes query execution by limiting data early in the pipeline where safe to do so.
  * Care must be taken to preserve semantics - limits can only be pushed through operators that
  * don't change cardinality.
  *
  * Examples:
  * {{{
  * Before:
  *   Limit 10
  *     Union
  *       Scan A
  *       Scan B
  *
  * After:
  *   Limit 10
  *     Union
  *       Limit 10
  *         Scan A
  *       Limit 10
  *         Scan B
  * }}}
  *
  * {{{
  * Before:
  *   Limit 10
  *     Project [a, b]
  *       Scan
  *
  * After:
  *   Project [a, b]
  *     Limit 10
  *       Scan
  * }}}
  *
  * Based on Spark Catalyst's LimitPushdown rule (Optimizer.scala:873-972).
  */
object LimitPushdown extends Rule:
  override val ruleName: String = "LimitPushdown"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanDown(plan) {
      // Push Limit through Project (safe - Project doesn't change cardinality)
      case ProtoLogicalPlan.Limit(n, ProtoLogicalPlan.Project(projectList, child)) =>
        ProtoLogicalPlan.Project(projectList, ProtoLogicalPlan.Limit(n, child))

      // Push Limit through SubqueryAlias (safe)
      case ProtoLogicalPlan.Limit(n, ProtoLogicalPlan.SubqueryAlias(alias, child)) =>
        ProtoLogicalPlan.SubqueryAlias(alias, ProtoLogicalPlan.Limit(n, child))

      // Push Limit to Union children (add limit to each branch)
      case ProtoLogicalPlan.Limit(
            n,
            union @ ProtoLogicalPlan.Union(children, byName, allowMissing)
          ) =>
        ProtoLogicalPlan.Limit(
          n,
          ProtoLogicalPlan.Union(
            children.map(child => ProtoLogicalPlan.Limit(n, child)),
            byName,
            allowMissing
          )
        )

      // Push Limit to left side of Left Outer Join
      // This is safe because left outer join preserves all left rows
      case ProtoLogicalPlan.Limit(
            n,
            ProtoLogicalPlan.Join(left, right, JoinType.LeftOuter, condition)
          ) =>
        ProtoLogicalPlan.Limit(
          n,
          ProtoLogicalPlan.Join(
            ProtoLogicalPlan.Limit(n, left),
            right,
            JoinType.LeftOuter,
            condition
          )
        )

      // Push Limit to right side of Right Outer Join
      case ProtoLogicalPlan.Limit(
            n,
            ProtoLogicalPlan.Join(left, right, JoinType.RightOuter, condition)
          ) =>
        ProtoLogicalPlan.Limit(
          n,
          ProtoLogicalPlan.Join(
            left,
            ProtoLogicalPlan.Limit(n, right),
            JoinType.RightOuter,
            condition
          )
        )

      // Merge nested Limits - take the smaller one
      case ProtoLogicalPlan.Limit(n1, ProtoLogicalPlan.Limit(n2, child)) =>
        ProtoLogicalPlan.Limit(math.min(n1, n2), child)

      // Eliminate Limit 0 (produces empty result, but keep for correctness)
      // Note: We don't eliminate here as that would require EmptyRelation

      // Push Limit through Window (only if window doesn't affect row count)
      // This is NOT safe in general because window functions may depend on order
      // Skip for now

      // Push Limit through Hint
      case ProtoLogicalPlan.Limit(n, ProtoLogicalPlan.ResolvedHint(hints, child)) =>
        ProtoLogicalPlan.ResolvedHint(hints, ProtoLogicalPlan.Limit(n, child))

      // Sort + Limit optimization: keep the limit close to sort (TopK optimization)
      // This pattern is already optimal, don't push further

      // Push Limit above Distinct (still need all distinct values before limiting)
      // NOT safe - keep limit above distinct

      // Push Limit above Filter (dangerous - could change results)
      // NOT safe - keep limit above filter

      // Push Limit above Aggregate
      // NOT safe - aggregation may need all rows

      // Limit above Sort - TopK optimization is handled at physical planning
      // Keep as is for semantic correctness
    }
