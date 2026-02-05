package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan

/** Combines adjacent CONCAT operations into a single CONCAT.
  *
  * Nested CONCAT calls can be flattened into a single CONCAT with all children. This reduces
  * expression nesting and can enable further optimizations.
  *
  * Examples:
  *   - `CONCAT(CONCAT(a, b), c)` → `CONCAT(a, b, c)`
  *   - `CONCAT(a, CONCAT(b, c), d)` → `CONCAT(a, b, c, d)`
  *   - `CONCAT(CONCAT(a, b), CONCAT(c, d))` → `CONCAT(a, b, c, d)`
  *
  * Note: This is separate from ReorderAssociativeOperator which also handles concat but focuses on
  * constant folding. This rule focuses purely on flattening the structure.
  *
  * Based on Spark Catalyst's CombineConcats rule (expressions.scala:1204+).
  */
object CombineConcats extends Rule:
  override val ruleName: String = "CombineConcats"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanExprs(plan)(combineConcats)

  /** Combine nested CONCAT expressions.
    */
  def combineConcats(expr: ProtoExpr): ProtoExpr =
    TreeTransform.transformExprUp(expr) { case ProtoExpr.Concat(children) =>
      val flattened = children.flatMap {
        case ProtoExpr.Concat(nested) => nested
        case other                    => Vector(other)
      }
      // Only create new Concat if we actually flattened something
      if flattened.size != children.size then ProtoExpr.Concat(flattened)
      else ProtoExpr.Concat(children)
    }
