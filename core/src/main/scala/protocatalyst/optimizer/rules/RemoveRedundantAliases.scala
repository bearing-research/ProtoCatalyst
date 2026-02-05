package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan

/** Removes aliases that don't change the name.
  *
  * An alias is redundant when it assigns the same name to a column that already has that name.
  *
  * Examples:
  *   - `a AS a` → `a`
  *   - `col AS col` → `col`
  *
  * This simplifies the plan and reduces expression overhead.
  *
  * Based on Spark Catalyst's RemoveRedundantAliases rule (Optimizer.scala:604).
  */
object RemoveRedundantAliases extends Rule:
  override val ruleName: String = "RemoveRedundantAliases"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanExprs(plan)(removeRedundantAliases)

  /** Remove redundant aliases from an expression tree.
    */
  def removeRedundantAliases(expr: ProtoExpr): ProtoExpr =
    TreeTransform.transformExprUp(expr) {
      // Alias of a column with the same name is redundant
      case ProtoExpr.Alias(col @ ProtoExpr.ColumnRef(name, _, _, _), aliasName)
          if name == aliasName =>
        col

      // Alias of an alias with the same name
      case ProtoExpr.Alias(ProtoExpr.Alias(child, innerName), outerName)
          if innerName == outerName =>
        ProtoExpr.Alias(child, outerName)
    }
