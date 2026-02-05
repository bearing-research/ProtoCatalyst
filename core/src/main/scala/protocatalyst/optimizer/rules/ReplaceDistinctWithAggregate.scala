package protocatalyst.optimizer.rules

import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan

/** Replaces DISTINCT with equivalent GROUP BY aggregation.
  *
  * A DISTINCT operation is semantically equivalent to a GROUP BY on all columns. Converting
  * DISTINCT to Aggregate can enable additional optimizations that work on aggregations.
  *
  * Example:
  * {{{
  * -- Before:
  * SELECT DISTINCT a, b FROM t
  *
  * -- After:
  * SELECT a, b FROM t GROUP BY a, b
  *
  * -- In plan form:
  * Distinct(Project([a, b], Scan)) → Aggregate([a, b], [a, b], Scan)
  * }}}
  *
  * Note: This transformation is only applied when the input has a known projection list, as we need
  * to know the output columns to create the grouping expressions.
  *
  * Based on Spark Catalyst's ReplaceDistinctWithAggregate rule (Optimizer.scala:2542).
  */
object ReplaceDistinctWithAggregate extends Rule:
  override val ruleName: String = "ReplaceDistinctWithAggregate"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      // Distinct on Project - use project list as grouping keys
      case ProtoLogicalPlan.Distinct(proj @ ProtoLogicalPlan.Project(projectList, child)) =>
        // The aggregate expressions are the same as the grouping (no aggregates)
        ProtoLogicalPlan.Aggregate(projectList, projectList, child)

      // Other cases (like Distinct on RelationRef) are left unchanged
      // since we need schema info to generate the column references
    }
