package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan
import protocatalyst.types._

/** Reorders associative operations to fold constants together.
  *
  * This rule flattens nested associative operations and groups constants together so they can be
  * folded by ConstantFolding.
  *
  * Examples:
  *   - `(x + 1) + 2` → `x + 3`
  *   - `(x * 2) * 3` → `x * 6`
  *   - `(s || 'a') || 'b'` → `s || 'ab'`
  *   - `1 + (x + 2)` → `x + 3`
  *   - `(1 + x) + (2 + y)` → `(x + y) + 3`
  *
  * Based on Spark Catalyst's ReorderAssociativeOperator rule (expressions.scala:239-317).
  */
object ReorderAssociativeOperator extends Rule:
  override val ruleName: String = "ReorderAssociativeOperator"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanExprs(plan)(reorderAssociative)

  /** Reorder associative operations in an expression tree.
    */
  def reorderAssociative(expr: ProtoExpr): ProtoExpr =
    TreeTransform.transformExprUp(expr) {
      // Addition is associative and commutative
      case add @ ProtoExpr.Add(_, _) =>
        reorderAdd(add)

      // Multiplication is associative and commutative
      case mult @ ProtoExpr.Multiply(_, _) =>
        reorderMultiply(mult)

      // String concatenation is associative
      case concat @ ProtoExpr.Concat(_) =>
        reorderConcat(concat)
    }

  /** Reorder addition: flatten, separate literals, fold and recombine.
    */
  private def reorderAdd(expr: ProtoExpr): ProtoExpr =
    val operands = flattenAdd(expr)
    if operands.size <= 1 then return expr

    val (literals, nonLiterals) = operands.partition(isNumericLiteral)

    // If we have less than 2 literals, no benefit from reordering for this operation
    if literals.size < 2 && nonLiterals.size == operands.size then return expr

    // Fold literals together
    val foldedLiteral = literals match
      case Vector()       => None
      case Vector(single) => Some(single)
      case multiple       =>
        Some(multiple.reduce { (a, b) =>
          foldAddLiterals(a, b).getOrElse(ProtoExpr.Add(a, b))
        })

    // Reconstruct: non-literals first (left-associative), then folded literal
    val allOperands = nonLiterals ++ foldedLiteral.toVector

    allOperands match
      case Vector()       => ProtoExpr.Literal(LiteralValue.IntValue(0))
      case Vector(single) => single
      case multiple       => multiple.reduceLeft(ProtoExpr.Add(_, _))

  /** Reorder multiplication: flatten, separate literals, fold and recombine.
    */
  private def reorderMultiply(expr: ProtoExpr): ProtoExpr =
    val operands = flattenMultiply(expr)
    if operands.size <= 1 then return expr

    val (literals, nonLiterals) = operands.partition(isNumericLiteral)

    // If we have less than 2 literals, no benefit from reordering
    if literals.size < 2 && nonLiterals.size == operands.size then return expr

    // Check for zero - short circuit
    if literals.exists(isZero) then return ProtoExpr.Literal(LiteralValue.IntValue(0))

    // Fold literals together
    val foldedLiteral = literals match
      case Vector()       => None
      case Vector(single) => Some(single)
      case multiple       =>
        Some(multiple.reduce { (a, b) =>
          foldMultiplyLiterals(a, b).getOrElse(ProtoExpr.Multiply(a, b))
        })

    // If folded literal is 1, we can skip it
    val finalLiteral = foldedLiteral.filterNot(isOne)

    // Reconstruct: non-literals first, then folded literal
    val allOperands = nonLiterals ++ finalLiteral.toVector

    allOperands match
      case Vector()       => ProtoExpr.Literal(LiteralValue.IntValue(1))
      case Vector(single) => single
      case multiple       => multiple.reduceLeft(ProtoExpr.Multiply(_, _))

  /** Reorder string concatenation: flatten, merge adjacent literals.
    */
  private def reorderConcat(expr: ProtoExpr): ProtoExpr =
    val operands = flattenConcat(expr)
    if operands.size <= 1 then return expr

    // Merge adjacent string literals
    val merged = mergeAdjacentStringLiterals(operands)

    if merged == operands then return expr

    merged match
      case Vector()       => ProtoExpr.Literal(LiteralValue.StringValue(""))
      case Vector(single) => single
      case multiple       => ProtoExpr.Concat(multiple)

  // Flatten helpers

  private def flattenAdd(expr: ProtoExpr): Vector[ProtoExpr] = expr match
    case ProtoExpr.Add(left, right) => flattenAdd(left) ++ flattenAdd(right)
    case other                      => Vector(other)

  private def flattenMultiply(expr: ProtoExpr): Vector[ProtoExpr] = expr match
    case ProtoExpr.Multiply(left, right) => flattenMultiply(left) ++ flattenMultiply(right)
    case other                           => Vector(other)

  private def flattenConcat(expr: ProtoExpr): Vector[ProtoExpr] = expr match
    case ProtoExpr.Concat(children) => children.flatMap(flattenConcat)
    case other                      => Vector(other)

  // Type check helpers

  private def isNumericLiteral(expr: ProtoExpr): Boolean = expr match
    case ProtoExpr.Literal(LiteralValue.IntValue(_))     => true
    case ProtoExpr.Literal(LiteralValue.LongValue(_))    => true
    case ProtoExpr.Literal(LiteralValue.DoubleValue(_))  => true
    case ProtoExpr.Literal(LiteralValue.FloatValue(_))   => true
    case ProtoExpr.Literal(LiteralValue.DecimalValue(_)) => true
    case _                                               => false

  private def isStringLiteral(expr: ProtoExpr): Boolean = expr match
    case ProtoExpr.Literal(LiteralValue.StringValue(_)) => true
    case _                                              => false

  private def isZero(expr: ProtoExpr): Boolean = expr match
    case ProtoExpr.Literal(LiteralValue.IntValue(0))                                        => true
    case ProtoExpr.Literal(LiteralValue.LongValue(0))                                       => true
    case ProtoExpr.Literal(LiteralValue.DoubleValue(0.0))                                   => true
    case ProtoExpr.Literal(LiteralValue.FloatValue(0.0f))                                   => true
    case ProtoExpr.Literal(LiteralValue.DecimalValue(d)) if d.compareTo(BigDecimal(0)) == 0 => true
    case _                                                                                  => false

  private def isOne(expr: ProtoExpr): Boolean = expr match
    case ProtoExpr.Literal(LiteralValue.IntValue(1))                                        => true
    case ProtoExpr.Literal(LiteralValue.LongValue(1))                                       => true
    case ProtoExpr.Literal(LiteralValue.DoubleValue(1.0))                                   => true
    case ProtoExpr.Literal(LiteralValue.FloatValue(1.0f))                                   => true
    case ProtoExpr.Literal(LiteralValue.DecimalValue(d)) if d.compareTo(BigDecimal(1)) == 0 => true
    case _                                                                                  => false

  // Folding helpers

  private def foldAddLiterals(a: ProtoExpr, b: ProtoExpr): Option[ProtoExpr] =
    (a, b) match
      case (ProtoExpr.Literal(la), ProtoExpr.Literal(lb)) =>
        foldAddValues(la, lb)
      case _ => None

  private def foldAddValues(l: LiteralValue, r: LiteralValue): Option[ProtoExpr] = (l, r) match
    case (LiteralValue.IntValue(a), LiteralValue.IntValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.IntValue(a + b)))
    case (LiteralValue.LongValue(a), LiteralValue.LongValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.LongValue(a + b)))
    case (LiteralValue.DoubleValue(a), LiteralValue.DoubleValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.DoubleValue(a + b)))
    case (LiteralValue.FloatValue(a), LiteralValue.FloatValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.FloatValue(a + b)))
    case (LiteralValue.DecimalValue(a), LiteralValue.DecimalValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.DecimalValue(a + b)))
    // Mixed numeric - promote to wider type
    case (LiteralValue.IntValue(a), LiteralValue.LongValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.LongValue(a.toLong + b)))
    case (LiteralValue.LongValue(a), LiteralValue.IntValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.LongValue(a + b.toLong)))
    case (LiteralValue.IntValue(a), LiteralValue.DoubleValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.DoubleValue(a.toDouble + b)))
    case (LiteralValue.DoubleValue(a), LiteralValue.IntValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.DoubleValue(a + b.toDouble)))
    case (LiteralValue.LongValue(a), LiteralValue.DoubleValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.DoubleValue(a.toDouble + b)))
    case (LiteralValue.DoubleValue(a), LiteralValue.LongValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.DoubleValue(a + b.toDouble)))
    case _ => None

  private def foldMultiplyLiterals(a: ProtoExpr, b: ProtoExpr): Option[ProtoExpr] =
    (a, b) match
      case (ProtoExpr.Literal(la), ProtoExpr.Literal(lb)) =>
        foldMultiplyValues(la, lb)
      case _ => None

  private def foldMultiplyValues(l: LiteralValue, r: LiteralValue): Option[ProtoExpr] = (l, r) match
    case (LiteralValue.IntValue(a), LiteralValue.IntValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.IntValue(a * b)))
    case (LiteralValue.LongValue(a), LiteralValue.LongValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.LongValue(a * b)))
    case (LiteralValue.DoubleValue(a), LiteralValue.DoubleValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.DoubleValue(a * b)))
    case (LiteralValue.FloatValue(a), LiteralValue.FloatValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.FloatValue(a * b)))
    case (LiteralValue.DecimalValue(a), LiteralValue.DecimalValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.DecimalValue(a * b)))
    // Mixed numeric - promote to wider type
    case (LiteralValue.IntValue(a), LiteralValue.LongValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.LongValue(a.toLong * b)))
    case (LiteralValue.LongValue(a), LiteralValue.IntValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.LongValue(a * b.toLong)))
    case (LiteralValue.IntValue(a), LiteralValue.DoubleValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.DoubleValue(a.toDouble * b)))
    case (LiteralValue.DoubleValue(a), LiteralValue.IntValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.DoubleValue(a * b.toDouble)))
    case (LiteralValue.LongValue(a), LiteralValue.DoubleValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.DoubleValue(a.toDouble * b)))
    case (LiteralValue.DoubleValue(a), LiteralValue.LongValue(b)) =>
      Some(ProtoExpr.Literal(LiteralValue.DoubleValue(a * b.toDouble)))
    case _ => None

  /** Merge adjacent string literals in a list.
    */
  private def mergeAdjacentStringLiterals(exprs: Vector[ProtoExpr]): Vector[ProtoExpr] =
    if exprs.isEmpty then return exprs

    exprs.foldLeft(Vector.empty[ProtoExpr]) { (acc, expr) =>
      (acc.lastOption, expr) match
        case (
              Some(ProtoExpr.Literal(LiteralValue.StringValue(s1))),
              ProtoExpr.Literal(LiteralValue.StringValue(s2))
            ) =>
          // Merge with previous string literal
          acc.init :+ ProtoExpr.Literal(LiteralValue.StringValue(s1 + s2))
        case _ =>
          acc :+ expr
    }
