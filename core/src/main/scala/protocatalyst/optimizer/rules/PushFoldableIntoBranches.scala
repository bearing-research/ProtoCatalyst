package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan

/** Pushes foldable (constant) expressions into conditional branches.
  *
  * When an arithmetic operation has a constant operand and a conditional expression, we can push
  * the constant into each branch. This enables constant folding within each branch.
  *
  * Examples:
  *   - `IF(cond, 1, 2) + 10` → `IF(cond, 11, 12)`
  *   - `CASE WHEN c THEN a ELSE b END + 5` → `CASE WHEN c THEN a+5 ELSE b+5 END`
  *   - `10 * IF(x, 2, 3)` → `IF(x, 20, 30)`
  *
  * Based on Spark Catalyst's PushFoldableIntoBranches rule (expressions.scala:675-763).
  */
object PushFoldableIntoBranches extends Rule:
  override val ruleName: String = "PushFoldableIntoBranches"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanExprs(plan)(pushFoldableIntoBranches)

  /** Push foldable expressions into conditional branches.
    */
  def pushFoldableIntoBranches(expr: ProtoExpr): ProtoExpr =
    TreeTransform.transformExprUp(expr) {
      // Addition: literal + IF → IF with literal added to each branch
      case ProtoExpr.Add(lit @ ProtoExpr.Literal(_), ProtoExpr.If(cond, trueVal, falseVal)) =>
        ProtoExpr.If(cond, ProtoExpr.Add(lit, trueVal), ProtoExpr.Add(lit, falseVal))

      case ProtoExpr.Add(ProtoExpr.If(cond, trueVal, falseVal), lit @ ProtoExpr.Literal(_)) =>
        ProtoExpr.If(cond, ProtoExpr.Add(trueVal, lit), ProtoExpr.Add(falseVal, lit))

      // Subtraction
      case ProtoExpr.Subtract(lit @ ProtoExpr.Literal(_), ProtoExpr.If(cond, trueVal, falseVal)) =>
        ProtoExpr.If(cond, ProtoExpr.Subtract(lit, trueVal), ProtoExpr.Subtract(lit, falseVal))

      case ProtoExpr.Subtract(ProtoExpr.If(cond, trueVal, falseVal), lit @ ProtoExpr.Literal(_)) =>
        ProtoExpr.If(cond, ProtoExpr.Subtract(trueVal, lit), ProtoExpr.Subtract(falseVal, lit))

      // Multiplication
      case ProtoExpr.Multiply(lit @ ProtoExpr.Literal(_), ProtoExpr.If(cond, trueVal, falseVal)) =>
        ProtoExpr.If(cond, ProtoExpr.Multiply(lit, trueVal), ProtoExpr.Multiply(lit, falseVal))

      case ProtoExpr.Multiply(ProtoExpr.If(cond, trueVal, falseVal), lit @ ProtoExpr.Literal(_)) =>
        ProtoExpr.If(cond, ProtoExpr.Multiply(trueVal, lit), ProtoExpr.Multiply(falseVal, lit))

      // Division
      case ProtoExpr.Divide(lit @ ProtoExpr.Literal(_), ProtoExpr.If(cond, trueVal, falseVal)) =>
        ProtoExpr.If(cond, ProtoExpr.Divide(lit, trueVal), ProtoExpr.Divide(lit, falseVal))

      case ProtoExpr.Divide(ProtoExpr.If(cond, trueVal, falseVal), lit @ ProtoExpr.Literal(_)) =>
        ProtoExpr.If(cond, ProtoExpr.Divide(trueVal, lit), ProtoExpr.Divide(falseVal, lit))

      // CaseWhen with addition
      case ProtoExpr.Add(lit @ ProtoExpr.Literal(_), ProtoExpr.CaseWhen(branches, elseVal)) =>
        val newBranches = branches.map { case (cond, value) => (cond, ProtoExpr.Add(lit, value)) }
        val newElse = elseVal.map(e => ProtoExpr.Add(lit, e))
        ProtoExpr.CaseWhen(newBranches, newElse)

      case ProtoExpr.Add(ProtoExpr.CaseWhen(branches, elseVal), lit @ ProtoExpr.Literal(_)) =>
        val newBranches = branches.map { case (cond, value) => (cond, ProtoExpr.Add(value, lit)) }
        val newElse = elseVal.map(e => ProtoExpr.Add(e, lit))
        ProtoExpr.CaseWhen(newBranches, newElse)

      // CaseWhen with multiplication
      case ProtoExpr.Multiply(lit @ ProtoExpr.Literal(_), ProtoExpr.CaseWhen(branches, elseVal)) =>
        val newBranches =
          branches.map { case (cond, value) => (cond, ProtoExpr.Multiply(lit, value)) }
        val newElse = elseVal.map(e => ProtoExpr.Multiply(lit, e))
        ProtoExpr.CaseWhen(newBranches, newElse)

      case ProtoExpr.Multiply(ProtoExpr.CaseWhen(branches, elseVal), lit @ ProtoExpr.Literal(_)) =>
        val newBranches =
          branches.map { case (cond, value) => (cond, ProtoExpr.Multiply(value, lit)) }
        val newElse = elseVal.map(e => ProtoExpr.Multiply(e, lit))
        ProtoExpr.CaseWhen(newBranches, newElse)

      // String concatenation with IF
      case ProtoExpr.Concat(children)
          if children.exists(isLiteral) && children.exists(isIf) && children.size == 2 =>
        val (literals, ifs) = children.partition(isLiteral)
        if literals.size == 1 && ifs.size == 1 then
          (literals.head, ifs.head) match
            case (lit @ ProtoExpr.Literal(_), ProtoExpr.If(cond, trueVal, falseVal)) =>
              ProtoExpr.If(
                cond,
                ProtoExpr.Concat(Vector(lit, trueVal)),
                ProtoExpr.Concat(Vector(lit, falseVal))
              )
            case _ => ProtoExpr.Concat(children)
        else ProtoExpr.Concat(children)
    }

  private def isLiteral(expr: ProtoExpr): Boolean = expr match
    case ProtoExpr.Literal(_) => true
    case _                    => false

  private def isIf(expr: ProtoExpr): Boolean = expr match
    case ProtoExpr.If(_, _, _) => true
    case _                     => false
