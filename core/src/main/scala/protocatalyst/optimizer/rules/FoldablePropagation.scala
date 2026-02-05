package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan

/** Propagates foldable (constant) expressions through the plan.
  *
  * When a Project aliases a constant expression, references to that alias can be replaced with the
  * constant value. This enables further constant folding optimizations.
  *
  * Examples:
  * {{{
  * -- Before:
  * Project [a, b]
  *   Project [1 AS a, x AS b]
  *     Scan
  *
  * -- After (with CollapseProject):
  * Project [1 AS a, x AS b]
  *   Scan
  * }}}
  *
  * Note: This is a simplified version that propagates through Project aliases. The full Spark
  * implementation also handles more complex cases with constraints.
  *
  * Based on Spark Catalyst's FoldablePropagation rule (expressions.scala:978-1113).
  */
object FoldablePropagation extends Rule:
  override val ruleName: String = "FoldablePropagation"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      // Propagate constants from child Project into parent expressions
      case ProtoLogicalPlan.Project(projectList, child @ ProtoLogicalPlan.Project(childList, _)) =>
        val foldableMap = extractFoldables(childList)
        if foldableMap.isEmpty then ProtoLogicalPlan.Project(projectList, child)
        else
          val newProjectList = projectList.map(substituteConstants(_, foldableMap))
          ProtoLogicalPlan.Project(newProjectList, child)

      // Propagate constants into Filter
      case ProtoLogicalPlan.Filter(condition, child @ ProtoLogicalPlan.Project(childList, _)) =>
        val foldableMap = extractFoldables(childList)
        if foldableMap.isEmpty then ProtoLogicalPlan.Filter(condition, child)
        else
          val newCondition = substituteConstants(condition, foldableMap)
          ProtoLogicalPlan.Filter(newCondition, child)
    }

  /** Extract a map from alias names to their constant values.
    */
  private def extractFoldables(exprs: Vector[ProtoExpr]): Map[String, ProtoExpr] =
    exprs.collect { case ProtoExpr.Alias(lit @ ProtoExpr.Literal(_), name) =>
      name -> lit
    }.toMap

  /** Substitute column references with their constant values.
    */
  private def substituteConstants(expr: ProtoExpr, foldables: Map[String, ProtoExpr]): ProtoExpr =
    TreeTransform.transformExprUp(expr) {
      case col @ ProtoExpr.ColumnRef(name, None, _, _) if foldables.contains(name) =>
        foldables(name)
    }
