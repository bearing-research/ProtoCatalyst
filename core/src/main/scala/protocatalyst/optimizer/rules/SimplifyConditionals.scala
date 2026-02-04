package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan
import protocatalyst.types._

/** Simplify conditional expressions (CASE/WHEN and IF).
  *
  * Examples:
  *   - `CASE WHEN TRUE THEN x ELSE y END` → `x`
  *   - `CASE WHEN FALSE THEN x ELSE y END` → `y`
  *   - `IF(TRUE, x, y)` → `x`
  *   - `IF(FALSE, x, y)` → `y`
  *   - `CASE WHEN cond THEN x ELSE x END` → `x`
  *   - `IF(cond, x, x)` → `x` (when cond has no side effects)
  *   - `CASE WHEN c1 THEN x WHEN FALSE THEN y ELSE z END` → `CASE WHEN c1 THEN x ELSE z END`
  *
  * Based on Spark Catalyst's SimplifyConditionals rule (expressions.scala:1000-1100).
  */
object SimplifyConditionals extends Rule:
  override val ruleName: String = "SimplifyConditionals"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanExprs(plan)(simplifyConditionals)

  /** Simplify conditional expressions. */
  def simplifyConditionals(expr: ProtoExpr): ProtoExpr =
    TreeTransform.transformExprUp(expr) {
      // IF(TRUE, x, y) → x (already handled in ConstantFolding, but keep for completeness)
      case ProtoExpr.If(ProtoExpr.Literal(LiteralValue.BooleanValue(true)), trueValue, _) =>
        trueValue

      // IF(FALSE, x, y) → y
      case ProtoExpr.If(ProtoExpr.Literal(LiteralValue.BooleanValue(false)), _, falseValue) =>
        falseValue

      // IF(NULL, x, y) → y (null predicate treated as false)
      case ProtoExpr.If(ProtoExpr.Literal(LiteralValue.NullValue(_)), _, falseValue) =>
        falseValue

      // IF(cond, x, x) → x when both branches are identical
      case ProtoExpr.If(_, trueValue, falseValue) if trueValue == falseValue =>
        trueValue

      // CaseWhen simplifications
      case ProtoExpr.CaseWhen(branches, elseValue) =>
        simplifyCaseWhen(branches, elseValue)
    }

  /** Simplify CaseWhen expression. */
  private def simplifyCaseWhen(
      branches: Vector[(ProtoExpr, ProtoExpr)],
      elseValue: Option[ProtoExpr]
  ): ProtoExpr =
    // Remove branches with FALSE condition
    val filteredBranches = branches.filter {
      case (ProtoExpr.Literal(LiteralValue.BooleanValue(false)), _) => false
      case (ProtoExpr.Literal(LiteralValue.NullValue(_)), _)        => false
      case _                                                        => true
    }

    // Check for TRUE condition - take that branch and stop
    val (beforeTrue, fromTrue) = filteredBranches.span {
      case (ProtoExpr.Literal(LiteralValue.BooleanValue(true)), _) => false
      case _                                                       => true
    }

    val effectiveBranches = fromTrue.headOption match
      case Some((ProtoExpr.Literal(LiteralValue.BooleanValue(true)), value)) =>
        // TRUE found - only keep branches before it
        if beforeTrue.isEmpty then return value // First branch is TRUE, return its value
        else beforeTrue :+ (ProtoExpr.Literal(LiteralValue.BooleanValue(true)) -> value)
      case _ =>
        filteredBranches

    // If no branches left, return else value or null
    if effectiveBranches.isEmpty then
      return elseValue.getOrElse(ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.NullType)))

    // Check if all branches (and else) return the same value
    val allValues = effectiveBranches.map(_._2) ++ elseValue.toVector
    if allValues.distinct.size == 1 then return allValues.head

    // Single branch with else → IF
    effectiveBranches match
      case Vector((cond, thenValue)) =>
        elseValue match
          case Some(e) if e == thenValue => thenValue // CASE WHEN c THEN x ELSE x → x
          case Some(e)                   => ProtoExpr.If(cond, thenValue, e)
          case None                      =>
            // No else means null for else
            ProtoExpr.If(
              cond,
              thenValue,
              ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.NullType))
            )

      case _ =>
        // Check if last branch is TRUE (unconditional)
        effectiveBranches.last match
          case (ProtoExpr.Literal(LiteralValue.BooleanValue(true)), value) =>
            // Last branch is TRUE - it becomes the else
            if effectiveBranches.size == 1 then value
            else ProtoExpr.CaseWhen(effectiveBranches.init, Some(value))
          case _ =>
            if effectiveBranches == branches && elseValue == elseValue then
              ProtoExpr.CaseWhen(effectiveBranches, elseValue)
            else ProtoExpr.CaseWhen(effectiveBranches, elseValue)
