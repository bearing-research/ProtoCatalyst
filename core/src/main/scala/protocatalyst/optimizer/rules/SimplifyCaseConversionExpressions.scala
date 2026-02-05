package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan

/** Simplifies nested case conversion expressions (UPPER/LOWER).
  *
  * Nested calls to the same case conversion function are redundant since the inner call already
  * converts the string.
  *
  * Examples:
  *   - `UPPER(UPPER(x))` → `UPPER(x)`
  *   - `LOWER(LOWER(x))` → `LOWER(x)`
  *   - `UPPER(LOWER(x))` → `UPPER(x)` (outer dominates)
  *   - `LOWER(UPPER(x))` → `LOWER(x)` (outer dominates)
  *
  * Based on Spark Catalyst's SimplifyCaseConversionExpressions rule (expressions.scala:1145).
  */
object SimplifyCaseConversionExpressions extends Rule:
  override val ruleName: String = "SimplifyCaseConversionExpressions"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanExprs(plan)(simplifyCaseConversion)

  /** Simplify case conversion expressions in an expression tree.
    */
  def simplifyCaseConversion(expr: ProtoExpr): ProtoExpr =
    TreeTransform.transformExprUp(expr) {
      // UPPER(UPPER(x)) → UPPER(x)
      case ProtoExpr.Upper(ProtoExpr.Upper(child)) =>
        ProtoExpr.Upper(child)

      // LOWER(LOWER(x)) → LOWER(x)
      case ProtoExpr.Lower(ProtoExpr.Lower(child)) =>
        ProtoExpr.Lower(child)

      // UPPER(LOWER(x)) → UPPER(x) - outer dominates
      case ProtoExpr.Upper(ProtoExpr.Lower(child)) =>
        ProtoExpr.Upper(child)

      // LOWER(UPPER(x)) → LOWER(x) - outer dominates
      case ProtoExpr.Lower(ProtoExpr.Upper(child)) =>
        ProtoExpr.Lower(child)
    }
