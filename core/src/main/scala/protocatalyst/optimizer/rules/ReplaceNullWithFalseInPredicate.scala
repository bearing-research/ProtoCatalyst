package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan
import protocatalyst.types._

/** Replaces null literals with false in predicate positions.
  *
  * In SQL, NULL in boolean context (WHERE, HAVING, JOIN ON, CASE WHEN) is treated as FALSE. This
  * rule makes that explicit, enabling further optimizations.
  *
  * Examples:
  *   - `WHERE NULL` → `WHERE FALSE`
  *   - `WHERE x AND NULL` → `WHERE x AND FALSE` → `WHERE FALSE`
  *   - `CASE WHEN NULL THEN a ELSE b END` → `CASE WHEN FALSE THEN a ELSE b END` → `b`
  *
  * Based on Spark Catalyst's ReplaceNullWithFalseInPredicate rule
  * (ReplaceNullWithFalseInPredicate.scala:53).
  */
object ReplaceNullWithFalseInPredicate extends Rule:
  override val ruleName: String = "ReplaceNullWithFalseInPredicate"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      // Filter condition
      case ProtoLogicalPlan.Filter(condition, child) =>
        ProtoLogicalPlan.Filter(replaceNullWithFalse(condition), child)

      // Join condition
      case ProtoLogicalPlan.Join(left, right, joinType, Some(condition)) =>
        ProtoLogicalPlan.Join(left, right, joinType, Some(replaceNullWithFalse(condition)))
    }

  /** Replace NULL literals with FALSE in a predicate expression.
    */
  private def replaceNullWithFalse(expr: ProtoExpr): ProtoExpr =
    TreeTransform.transformExprUp(expr) {
      // Direct NULL literal in boolean position → FALSE
      case ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType)) =>
        ProtoExpr.Literal(LiteralValue.BooleanValue(false))

      // NULL in AND/OR - replace with FALSE
      case ProtoExpr.And(children) =>
        val replaced = children.map {
          case ProtoExpr.Literal(LiteralValue.NullValue(_)) =>
            ProtoExpr.Literal(LiteralValue.BooleanValue(false))
          case other => other
        }
        ProtoExpr.And(replaced)

      case ProtoExpr.Or(children) =>
        val replaced = children.map {
          case ProtoExpr.Literal(LiteralValue.NullValue(_)) =>
            ProtoExpr.Literal(LiteralValue.BooleanValue(false))
          case other => other
        }
        ProtoExpr.Or(replaced)

      // CASE WHEN NULL THEN ... → CASE WHEN FALSE THEN ...
      case ProtoExpr.CaseWhen(branches, elseValue) =>
        val replacedBranches = branches.map { case (cond, value) =>
          val newCond = cond match
            case ProtoExpr.Literal(LiteralValue.NullValue(_)) =>
              ProtoExpr.Literal(LiteralValue.BooleanValue(false))
            case other => other
          (newCond, value)
        }
        ProtoExpr.CaseWhen(replacedBranches, elseValue)

      // IF(NULL, ...) → IF(FALSE, ...)
      case ProtoExpr.If(ProtoExpr.Literal(LiteralValue.NullValue(_)), trueVal, falseVal) =>
        ProtoExpr.If(ProtoExpr.Literal(LiteralValue.BooleanValue(false)), trueVal, falseVal)
    }
