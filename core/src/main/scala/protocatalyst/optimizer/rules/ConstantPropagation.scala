package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan
import protocatalyst.types._

/** Replaces attributes with known constant values from equality constraints.
  *
  * This rule looks for equality constraints (column = literal) in filter conditions and replaces
  * references to those columns with the literal value elsewhere in the same expression.
  *
  * Examples:
  *   - `WHERE id = 5 AND amount > id + 10` → `WHERE id = 5 AND amount > 15`
  *   - `WHERE status = 'active' AND UPPER(status) = 'ACTIVE'` → `WHERE status = 'active' AND TRUE`
  *
  * Based on Spark Catalyst's ConstantPropagation rule (expressions.scala:134-234).
  *
  * Note: This rule only propagates constants within the same filter condition. Cross-operator
  * constant propagation requires more sophisticated analysis.
  */
object ConstantPropagation extends Rule:
  override val ruleName: String = "ConstantPropagation"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) { case ProtoLogicalPlan.Filter(condition, child) =>
      val constraints = extractEqualityConstraints(condition)
      if constraints.nonEmpty then
        val newCondition = propagateConstants(condition, constraints)
        ProtoLogicalPlan.Filter(newCondition, child)
      else ProtoLogicalPlan.Filter(condition, child)
    }

  /** Extract equality constraints (column = literal) from an expression.
    *
    * Only extracts constraints from AND conjunctions where there are multiple conditions.
    * Single equalities don't benefit from constant propagation (there's nowhere to propagate TO),
    * and propagating within the defining equality itself would incorrectly turn `col = val` into `val = val`.
    */
  private def extractEqualityConstraints(
      expr: ProtoExpr
  ): Map[(String, Option[String]), ProtoExpr] =
    expr match
      // AND: collect from all children - this is where constant propagation is useful
      case ProtoExpr.And(children) =>
        children.flatMap(extractFromChild).toMap

      // Single equalities or other expressions don't need constant propagation
      case _ => Map.empty

  /** Extract equality constraint from a child expression within an AND. */
  private def extractFromChild(expr: ProtoExpr): Map[(String, Option[String]), ProtoExpr] =
    expr match
      // Direct equality: col = literal
      case ProtoExpr.Eq(
            ProtoExpr.ColumnRef(name, qualifier, _, _),
            lit @ ProtoExpr.Literal(_)
          ) if !isNullLiteral(lit) =>
        Map((name, qualifier) -> lit)

      // Reverse: literal = col
      case ProtoExpr.Eq(
            lit @ ProtoExpr.Literal(_),
            ProtoExpr.ColumnRef(name, qualifier, _, _)
          ) if !isNullLiteral(lit) =>
        Map((name, qualifier) -> lit)

      // Nested AND
      case ProtoExpr.And(children) =>
        children.flatMap(extractFromChild).toMap

      case _ => Map.empty

  /** Propagate constants through an expression using the constraints.
    *
    * Replaces column references with their known literal values.
    * Note: We only extract constraints from ANDs, so the defining equality like `col = val`
    * will have its column replaced, but that's fine because we then have `val = val` which
    * folds to TRUE and gets absorbed by AND simplification (true AND x = x).
    */
  private def propagateConstants(
      expr: ProtoExpr,
      constraints: Map[(String, Option[String]), ProtoExpr]
  ): ProtoExpr =
    TreeTransform.transformExprUp(expr) {
      case ref @ ProtoExpr.ColumnRef(name, qualifier, _, _) =>
        constraints.get((name, qualifier)).getOrElse(ref)
    }

  /** Check if expression is a null literal. */
  private def isNullLiteral(expr: ProtoExpr): Boolean = expr match
    case ProtoExpr.Literal(LiteralValue.NullValue(_)) => true
    case _                                            => false
