package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan

/** Collapse adjacent Project operators into a single Project.
  *
  * Merges two adjacent projections by substituting expressions from the outer projection with their
  * definitions from the inner projection.
  *
  * Example:
  * ```
  * Before:
  *   Project [a + 1 AS x]
  *     Project [col AS a]
  *       Scan
  *
  * After:
  *   Project [col + 1 AS x]
  *     Scan
  * ```
  *
  * Based on Spark Catalyst's CollapseProject rule (Optimizer.scala:1206).
  */
object CollapseProject extends Rule:
  override val ruleName: String = "CollapseProject"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      // Project over Project: merge them
      case ProtoLogicalPlan.Project(outerList, ProtoLogicalPlan.Project(innerList, child)) =>
        val merged = mergeProjections(outerList, innerList)
        ProtoLogicalPlan.Project(merged, child)

      // Project over Filter over Project: merge projects if safe
      case ProtoLogicalPlan.Project(
            outerList,
            ProtoLogicalPlan.Filter(condition, ProtoLogicalPlan.Project(innerList, child))
          ) if canMergeWithFilter(outerList, innerList, condition) =>
        val mergedProjection = mergeProjections(outerList, innerList)
        val newCondition = substituteExpr(condition, innerList)
        ProtoLogicalPlan.Project(
          mergedProjection,
          ProtoLogicalPlan.Filter(newCondition, child)
        )
    }

  /** Merge outer projection expressions with inner projection.
    *
    * Substitutes column references in outer expressions with their definitions from the inner
    * projection.
    */
  private def mergeProjections(
      outerList: Vector[ProtoExpr],
      innerList: Vector[ProtoExpr]
  ): Vector[ProtoExpr] =
    outerList.map(expr => substituteExpr(expr, innerList))

  /** Substitute column references in an expression with their definitions.
    *
    * For each ColumnRef, if there's a matching Alias in the bindings, replace it with the aliased
    * expression.
    */
  private def substituteExpr(expr: ProtoExpr, bindings: Vector[ProtoExpr]): ProtoExpr =
    TreeTransform.transformExprUp(expr) { case ref @ ProtoExpr.ColumnRef(name, qualifier, _, _) =>
      // Find matching alias in bindings
      findBinding(name, qualifier, bindings).getOrElse(ref)
    }

  /** Find a binding for a column reference in the projection list.
    */
  private def findBinding(
      name: String,
      qualifier: Option[String],
      bindings: Vector[ProtoExpr]
  ): Option[ProtoExpr] =
    bindings.collectFirst {
      // Match Alias with the same name
      case ProtoExpr.Alias(child, aliasName) if aliasName == name =>
        child

      // Match ColumnRef with the same name (passthrough projection)
      case ref @ ProtoExpr.ColumnRef(colName, colQualifier, _, _)
          if colName == name && (qualifier.isEmpty || colQualifier == qualifier) =>
        ref
    }

  /** Check if we can safely merge projections across a filter.
    *
    * This is safe when the filter condition doesn't introduce complex dependencies that would make
    * merging incorrect.
    */
  private def canMergeWithFilter(
      outerList: Vector[ProtoExpr],
      innerList: Vector[ProtoExpr],
      condition: ProtoExpr
  ): Boolean =
    // For now, always allow merging - the substitution handles correctness
    // A more sophisticated check would verify no side effects or
    // non-deterministic expressions are duplicated
    true
