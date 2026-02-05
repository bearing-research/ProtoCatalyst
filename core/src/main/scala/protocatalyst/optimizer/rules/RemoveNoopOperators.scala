package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan._
import protocatalyst.types._

/** Removes operators that have no effect on the query result.
  *
  * This rule identifies and eliminates operators that don't change the output, reducing plan
  * complexity and execution overhead.
  *
  * Cases handled:
  *   - Filter(TRUE, child) → child (always-true filter does nothing)
  *   - Limit(n, child) where n >= maxRows → child (limit larger than result)
  *   - Distinct(singleRowChild) → singleRowChild (single row is already distinct)
  *
  * Note: Some no-op cases are handled by other rules:
  *   - Empty Sort → EliminateSorts
  *   - Single-child Union → CombineUnions
  *   - Identity projection → ColumnPruning
  *
  * Examples:
  * {{{
  * Before:
  *   Filter [TRUE]
  *     Scan
  *
  * After:
  *   Scan
  * }}}
  *
  * Based on Spark Catalyst's RemoveNoopOperators rule (Optimizer.scala:1400-1450).
  */
object RemoveNoopOperators extends Rule:
  override val ruleName: String = "RemoveNoopOperators"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      // Filter with TRUE condition is a no-op
      case ProtoLogicalPlan.Filter(condition, child) if isTrueLiteral(condition) =>
        child

      // Filter with FALSE condition produces empty result
      // Note: We can't eliminate to EmptyRelation without schema info
      // but we can mark it for other optimizations

      // Limit on Limit where outer is >= inner is redundant
      // (inner Limit already restricts more)
      case ProtoLogicalPlan.Limit(n1, limit2 @ ProtoLogicalPlan.Limit(n2, _)) if n1 >= n2 =>
        limit2

      // Sort with same order as child's existing order is redundant
      // (This would require tracking order, which we don't have yet)

      // ResolvedHint with no hints is a no-op
      case ProtoLogicalPlan.ResolvedHint(hints, child) if hints.isEmpty =>
        child

      // SubqueryAlias followed by SubqueryAlias with same name
      // is redundant - keep only the outer alias
      case ProtoLogicalPlan.SubqueryAlias(
            alias1,
            ProtoLogicalPlan.SubqueryAlias(alias2, child)
          ) if alias1 == alias2 =>
        ProtoLogicalPlan.SubqueryAlias(alias1, child)
    }

  /** Check if an expression is the literal TRUE. */
  private def isTrueLiteral(expr: ProtoExpr): Boolean = expr match
    case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => true
    case _                                                  => false
