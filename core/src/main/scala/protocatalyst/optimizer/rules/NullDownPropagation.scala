package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan

/** Propagates null checks downward through null-intolerant expressions.
  *
  * For null-intolerant operations (operations that return NULL if any input is NULL), we can push
  * IsNull/IsNotNull checks down to the individual inputs.
  *
  * Examples:
  *   - `IsNull(a + b)` → `IsNull(a) OR IsNull(b)`
  *   - `IsNotNull(a + b)` → `IsNotNull(a) AND IsNotNull(b)`
  *   - `IsNull(CONCAT(a, b, c))` → `IsNull(a) OR IsNull(b) OR IsNull(c)`
  *
  * This enables better predicate pushdown and can simplify expressions when combined with constant
  * propagation.
  *
  * Based on Spark Catalyst's NullDownPropagation rule (expressions.scala:936-976).
  */
object NullDownPropagation extends Rule:
  override val ruleName: String = "NullDownPropagation"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanExprs(plan)(propagateNullDown)

  /** Propagate null checks through null-intolerant expressions.
    */
  def propagateNullDown(expr: ProtoExpr): ProtoExpr =
    TreeTransform.transformExprUp(expr) {
      // IsNull on null-intolerant binary operations
      case ProtoExpr.IsNull(ProtoExpr.Add(left, right)) =>
        makeOr(ProtoExpr.IsNull(left), ProtoExpr.IsNull(right))

      case ProtoExpr.IsNull(ProtoExpr.Subtract(left, right)) =>
        makeOr(ProtoExpr.IsNull(left), ProtoExpr.IsNull(right))

      case ProtoExpr.IsNull(ProtoExpr.Multiply(left, right)) =>
        makeOr(ProtoExpr.IsNull(left), ProtoExpr.IsNull(right))

      case ProtoExpr.IsNull(ProtoExpr.Divide(left, right)) =>
        makeOr(ProtoExpr.IsNull(left), ProtoExpr.IsNull(right))

      // IsNull on string operations
      case ProtoExpr.IsNull(ProtoExpr.Concat(children)) if children.nonEmpty =>
        children.map(ProtoExpr.IsNull.apply).reduceLeft(makeOr)

      case ProtoExpr.IsNull(ProtoExpr.Upper(child)) =>
        ProtoExpr.IsNull(child)

      case ProtoExpr.IsNull(ProtoExpr.Lower(child)) =>
        ProtoExpr.IsNull(child)

      // IsNotNull on null-intolerant binary operations
      case ProtoExpr.IsNotNull(ProtoExpr.Add(left, right)) =>
        makeAnd(ProtoExpr.IsNotNull(left), ProtoExpr.IsNotNull(right))

      case ProtoExpr.IsNotNull(ProtoExpr.Subtract(left, right)) =>
        makeAnd(ProtoExpr.IsNotNull(left), ProtoExpr.IsNotNull(right))

      case ProtoExpr.IsNotNull(ProtoExpr.Multiply(left, right)) =>
        makeAnd(ProtoExpr.IsNotNull(left), ProtoExpr.IsNotNull(right))

      case ProtoExpr.IsNotNull(ProtoExpr.Divide(left, right)) =>
        makeAnd(ProtoExpr.IsNotNull(left), ProtoExpr.IsNotNull(right))

      // IsNotNull on string operations
      case ProtoExpr.IsNotNull(ProtoExpr.Concat(children)) if children.nonEmpty =>
        children.map(ProtoExpr.IsNotNull.apply).reduceLeft(makeAnd)

      case ProtoExpr.IsNotNull(ProtoExpr.Upper(child)) =>
        ProtoExpr.IsNotNull(child)

      case ProtoExpr.IsNotNull(ProtoExpr.Lower(child)) =>
        ProtoExpr.IsNotNull(child)

      // IsNull/IsNotNull on Cast propagates through
      case ProtoExpr.IsNull(ProtoExpr.Cast(child, _)) =>
        ProtoExpr.IsNull(child)

      case ProtoExpr.IsNotNull(ProtoExpr.Cast(child, _)) =>
        ProtoExpr.IsNotNull(child)

      // IsNull/IsNotNull on Alias propagates through
      case ProtoExpr.IsNull(ProtoExpr.Alias(child, _)) =>
        ProtoExpr.IsNull(child)

      case ProtoExpr.IsNotNull(ProtoExpr.Alias(child, _)) =>
        ProtoExpr.IsNotNull(child)
    }

  private def makeOr(left: ProtoExpr, right: ProtoExpr): ProtoExpr =
    ProtoExpr.Or(Vector(left, right))

  private def makeAnd(left: ProtoExpr, right: ProtoExpr): ProtoExpr =
    ProtoExpr.And(Vector(left, right))
