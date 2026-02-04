package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan
import protocatalyst.types._

/** Constant folding optimization rule.
  *
  * Evaluates expressions with constant (literal) inputs at compile time.
  *
  * Examples:
  *   - `5 + 10` → `15`
  *   - `'hello' || ' world'` → `'hello world'`
  *   - `LENGTH('abc')` → `3`
  *   - `TRUE AND FALSE` → `FALSE`
  *
  * Based on Spark Catalyst's ConstantFolding rule (expressions.scala:49-130).
  */
object ConstantFolding extends Rule:
  override val ruleName: String = "ConstantFolding"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanExprs(plan)(foldConstants)

  /** Fold constants in an expression tree.
    */
  def foldConstants(expr: ProtoExpr): ProtoExpr =
    TreeTransform.transformExprUp(expr) {
      // Arithmetic operations
      case ProtoExpr.Add(ProtoExpr.Literal(l), ProtoExpr.Literal(r)) =>
        foldAdd(l, r)

      case ProtoExpr.Subtract(ProtoExpr.Literal(l), ProtoExpr.Literal(r)) =>
        foldSubtract(l, r)

      case ProtoExpr.Multiply(ProtoExpr.Literal(l), ProtoExpr.Literal(r)) =>
        foldMultiply(l, r)

      case ProtoExpr.Divide(ProtoExpr.Literal(l), ProtoExpr.Literal(r)) =>
        foldDivide(l, r)

      // Comparison operations
      case ProtoExpr.Eq(ProtoExpr.Literal(l), ProtoExpr.Literal(r)) =>
        ProtoExpr.Literal(LiteralValue.BooleanValue(literalEquals(l, r)))

      case ProtoExpr.NotEq(ProtoExpr.Literal(l), ProtoExpr.Literal(r)) =>
        ProtoExpr.Literal(LiteralValue.BooleanValue(!literalEquals(l, r)))

      case ProtoExpr.Lt(ProtoExpr.Literal(l), ProtoExpr.Literal(r)) =>
        foldComparison(l, r, _ < 0)

      case ProtoExpr.LtEq(ProtoExpr.Literal(l), ProtoExpr.Literal(r)) =>
        foldComparison(l, r, _ <= 0)

      case ProtoExpr.Gt(ProtoExpr.Literal(l), ProtoExpr.Literal(r)) =>
        foldComparison(l, r, _ > 0)

      case ProtoExpr.GtEq(ProtoExpr.Literal(l), ProtoExpr.Literal(r)) =>
        foldComparison(l, r, _ >= 0)

      // Logical operations
      case ProtoExpr.Not(ProtoExpr.Literal(LiteralValue.BooleanValue(b))) =>
        ProtoExpr.Literal(LiteralValue.BooleanValue(!b))

      // String operations
      case ProtoExpr.Concat(children) if children.forall(isStringLiteral) =>
        val concatenated = children.collect { case ProtoExpr.Literal(LiteralValue.StringValue(s)) =>
          s
        }.mkString
        ProtoExpr.Literal(LiteralValue.StringValue(concatenated))

      case ProtoExpr.Upper(ProtoExpr.Literal(LiteralValue.StringValue(s))) =>
        ProtoExpr.Literal(LiteralValue.StringValue(s.toUpperCase))

      case ProtoExpr.Lower(ProtoExpr.Literal(LiteralValue.StringValue(s))) =>
        ProtoExpr.Literal(LiteralValue.StringValue(s.toLowerCase))

      case ProtoExpr.Length(ProtoExpr.Literal(LiteralValue.StringValue(s))) =>
        ProtoExpr.Literal(LiteralValue.IntValue(s.length))

      case ProtoExpr.Substring(
            ProtoExpr.Literal(LiteralValue.StringValue(s)),
            ProtoExpr.Literal(LiteralValue.IntValue(pos)),
            ProtoExpr.Literal(LiteralValue.IntValue(len))
          ) =>
        // SQL SUBSTRING is 1-indexed
        val startIdx = math.max(0, pos - 1)
        val endIdx = math.min(s.length, startIdx + len)
        ProtoExpr.Literal(LiteralValue.StringValue(s.substring(startIdx, endIdx)))

      case ProtoExpr.Reverse(ProtoExpr.Literal(LiteralValue.StringValue(s))) =>
        ProtoExpr.Literal(LiteralValue.StringValue(s.reverse))

      // Math functions
      case ProtoExpr.Abs(ProtoExpr.Literal(v)) =>
        foldAbs(v)

      case ProtoExpr.Ceil(ProtoExpr.Literal(LiteralValue.DoubleValue(d))) =>
        ProtoExpr.Literal(LiteralValue.LongValue(math.ceil(d).toLong))

      case ProtoExpr.Floor(ProtoExpr.Literal(LiteralValue.DoubleValue(d))) =>
        ProtoExpr.Literal(LiteralValue.LongValue(math.floor(d).toLong))

      case ProtoExpr.Sqrt(ProtoExpr.Literal(LiteralValue.DoubleValue(d))) if d >= 0 =>
        ProtoExpr.Literal(LiteralValue.DoubleValue(math.sqrt(d)))

      case ProtoExpr.Sign(ProtoExpr.Literal(v)) =>
        foldSign(v)

      // Null handling
      case ProtoExpr.IsNull(ProtoExpr.Literal(LiteralValue.NullValue(_))) =>
        ProtoExpr.Literal(LiteralValue.BooleanValue(true))

      case ProtoExpr.IsNull(ProtoExpr.Literal(_)) =>
        ProtoExpr.Literal(LiteralValue.BooleanValue(false))

      case ProtoExpr.IsNotNull(ProtoExpr.Literal(LiteralValue.NullValue(_))) =>
        ProtoExpr.Literal(LiteralValue.BooleanValue(false))

      case ProtoExpr.IsNotNull(ProtoExpr.Literal(_)) =>
        ProtoExpr.Literal(LiteralValue.BooleanValue(true))

      case ProtoExpr.Coalesce(children) =>
        // Find first non-null literal or keep first non-literal expression
        val result = children.foldLeft[Option[ProtoExpr]](None) {
          case (Some(found), _)                                     => Some(found)
          case (None, ProtoExpr.Literal(LiteralValue.NullValue(_))) => None
          case (None, expr @ ProtoExpr.Literal(_))                  => Some(expr)
          case (None, expr) => Some(expr) // Non-literal - stop here
        }
        result.getOrElse(ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.NullType)))

      // Cast of literal
      case ProtoExpr.Cast(ProtoExpr.Literal(v), targetType) =>
        foldCast(v, targetType).getOrElse(ProtoExpr.Cast(ProtoExpr.Literal(v), targetType))

      // If with literal predicate
      case ProtoExpr.If(ProtoExpr.Literal(LiteralValue.BooleanValue(true)), trueVal, _) =>
        trueVal

      case ProtoExpr.If(ProtoExpr.Literal(LiteralValue.BooleanValue(false)), _, falseVal) =>
        falseVal
    }

  // Helper methods

  private def isStringLiteral(expr: ProtoExpr): Boolean = expr match
    case ProtoExpr.Literal(LiteralValue.StringValue(_)) => true
    case _                                              => false

  private def literalEquals(l: LiteralValue, r: LiteralValue): Boolean = (l, r) match
    case (LiteralValue.NullValue(_), _) | (_, LiteralValue.NullValue(_)) => false
    case _                                                               => l == r

  private def foldAdd(l: LiteralValue, r: LiteralValue): ProtoExpr = (l, r) match
    case (LiteralValue.IntValue(a), LiteralValue.IntValue(b)) =>
      ProtoExpr.Literal(LiteralValue.IntValue(a + b))
    case (LiteralValue.LongValue(a), LiteralValue.LongValue(b)) =>
      ProtoExpr.Literal(LiteralValue.LongValue(a + b))
    case (LiteralValue.DoubleValue(a), LiteralValue.DoubleValue(b)) =>
      ProtoExpr.Literal(LiteralValue.DoubleValue(a + b))
    case (LiteralValue.FloatValue(a), LiteralValue.FloatValue(b)) =>
      ProtoExpr.Literal(LiteralValue.FloatValue(a + b))
    case (LiteralValue.DecimalValue(a), LiteralValue.DecimalValue(b)) =>
      ProtoExpr.Literal(LiteralValue.DecimalValue(a + b))
    // Mixed numeric types - promote to wider type
    case (LiteralValue.IntValue(a), LiteralValue.LongValue(b)) =>
      ProtoExpr.Literal(LiteralValue.LongValue(a.toLong + b))
    case (LiteralValue.LongValue(a), LiteralValue.IntValue(b)) =>
      ProtoExpr.Literal(LiteralValue.LongValue(a + b.toLong))
    case (LiteralValue.IntValue(a), LiteralValue.DoubleValue(b)) =>
      ProtoExpr.Literal(LiteralValue.DoubleValue(a.toDouble + b))
    case (LiteralValue.DoubleValue(a), LiteralValue.IntValue(b)) =>
      ProtoExpr.Literal(LiteralValue.DoubleValue(a + b.toDouble))
    case (LiteralValue.LongValue(a), LiteralValue.DoubleValue(b)) =>
      ProtoExpr.Literal(LiteralValue.DoubleValue(a.toDouble + b))
    case (LiteralValue.DoubleValue(a), LiteralValue.LongValue(b)) =>
      ProtoExpr.Literal(LiteralValue.DoubleValue(a + b.toDouble))
    // Null propagation
    case (LiteralValue.NullValue(t), _) => ProtoExpr.Literal(LiteralValue.NullValue(t))
    case (_, LiteralValue.NullValue(t)) => ProtoExpr.Literal(LiteralValue.NullValue(t))
    case _                              => ProtoExpr.Add(ProtoExpr.Literal(l), ProtoExpr.Literal(r))

  private def foldSubtract(l: LiteralValue, r: LiteralValue): ProtoExpr = (l, r) match
    case (LiteralValue.IntValue(a), LiteralValue.IntValue(b)) =>
      ProtoExpr.Literal(LiteralValue.IntValue(a - b))
    case (LiteralValue.LongValue(a), LiteralValue.LongValue(b)) =>
      ProtoExpr.Literal(LiteralValue.LongValue(a - b))
    case (LiteralValue.DoubleValue(a), LiteralValue.DoubleValue(b)) =>
      ProtoExpr.Literal(LiteralValue.DoubleValue(a - b))
    case (LiteralValue.FloatValue(a), LiteralValue.FloatValue(b)) =>
      ProtoExpr.Literal(LiteralValue.FloatValue(a - b))
    case (LiteralValue.DecimalValue(a), LiteralValue.DecimalValue(b)) =>
      ProtoExpr.Literal(LiteralValue.DecimalValue(a - b))
    // Null propagation
    case (LiteralValue.NullValue(t), _) => ProtoExpr.Literal(LiteralValue.NullValue(t))
    case (_, LiteralValue.NullValue(t)) => ProtoExpr.Literal(LiteralValue.NullValue(t))
    case _ => ProtoExpr.Subtract(ProtoExpr.Literal(l), ProtoExpr.Literal(r))

  private def foldMultiply(l: LiteralValue, r: LiteralValue): ProtoExpr = (l, r) match
    case (LiteralValue.IntValue(a), LiteralValue.IntValue(b)) =>
      ProtoExpr.Literal(LiteralValue.IntValue(a * b))
    case (LiteralValue.LongValue(a), LiteralValue.LongValue(b)) =>
      ProtoExpr.Literal(LiteralValue.LongValue(a * b))
    case (LiteralValue.DoubleValue(a), LiteralValue.DoubleValue(b)) =>
      ProtoExpr.Literal(LiteralValue.DoubleValue(a * b))
    case (LiteralValue.FloatValue(a), LiteralValue.FloatValue(b)) =>
      ProtoExpr.Literal(LiteralValue.FloatValue(a * b))
    case (LiteralValue.DecimalValue(a), LiteralValue.DecimalValue(b)) =>
      ProtoExpr.Literal(LiteralValue.DecimalValue(a * b))
    // Null propagation
    case (LiteralValue.NullValue(t), _) => ProtoExpr.Literal(LiteralValue.NullValue(t))
    case (_, LiteralValue.NullValue(t)) => ProtoExpr.Literal(LiteralValue.NullValue(t))
    case _ => ProtoExpr.Multiply(ProtoExpr.Literal(l), ProtoExpr.Literal(r))

  private def foldDivide(l: LiteralValue, r: LiteralValue): ProtoExpr = (l, r) match
    // Division by zero returns null
    case (_, LiteralValue.IntValue(0)) =>
      ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.DoubleType))
    case (_, LiteralValue.LongValue(0)) =>
      ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.DoubleType))
    case (_, LiteralValue.DoubleValue(0.0)) =>
      ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.DoubleType))
    case (LiteralValue.IntValue(a), LiteralValue.IntValue(b)) =>
      ProtoExpr.Literal(LiteralValue.DoubleValue(a.toDouble / b.toDouble))
    case (LiteralValue.LongValue(a), LiteralValue.LongValue(b)) =>
      ProtoExpr.Literal(LiteralValue.DoubleValue(a.toDouble / b.toDouble))
    case (LiteralValue.DoubleValue(a), LiteralValue.DoubleValue(b)) =>
      ProtoExpr.Literal(LiteralValue.DoubleValue(a / b))
    case (LiteralValue.FloatValue(a), LiteralValue.FloatValue(b)) =>
      ProtoExpr.Literal(LiteralValue.FloatValue(a / b))
    case (LiteralValue.DecimalValue(a), LiteralValue.DecimalValue(b)) if b != 0 =>
      ProtoExpr.Literal(LiteralValue.DecimalValue(a / b))
    // Null propagation
    case (LiteralValue.NullValue(t), _) => ProtoExpr.Literal(LiteralValue.NullValue(t))
    case (_, LiteralValue.NullValue(t)) => ProtoExpr.Literal(LiteralValue.NullValue(t))
    case _ => ProtoExpr.Divide(ProtoExpr.Literal(l), ProtoExpr.Literal(r))

  private def foldComparison(l: LiteralValue, r: LiteralValue, cmp: Int => Boolean): ProtoExpr =
    compareValues(l, r) match
      case Some(result) => ProtoExpr.Literal(LiteralValue.BooleanValue(cmp(result)))
      case None         => ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType))

  private def compareValues(l: LiteralValue, r: LiteralValue): Option[Int] = (l, r) match
    case (LiteralValue.NullValue(_), _) | (_, LiteralValue.NullValue(_))  => None
    case (LiteralValue.IntValue(a), LiteralValue.IntValue(b))             => Some(a.compareTo(b))
    case (LiteralValue.LongValue(a), LiteralValue.LongValue(b))           => Some(a.compareTo(b))
    case (LiteralValue.DoubleValue(a), LiteralValue.DoubleValue(b))       => Some(a.compareTo(b))
    case (LiteralValue.FloatValue(a), LiteralValue.FloatValue(b))         => Some(a.compareTo(b))
    case (LiteralValue.StringValue(a), LiteralValue.StringValue(b))       => Some(a.compareTo(b))
    case (LiteralValue.DateValue(a), LiteralValue.DateValue(b))           => Some(a.compareTo(b))
    case (LiteralValue.TimestampValue(a), LiteralValue.TimestampValue(b)) => Some(a.compareTo(b))
    case (LiteralValue.DecimalValue(a), LiteralValue.DecimalValue(b))     => Some(a.compareTo(b))
    case _                                                                => None

  private def foldAbs(v: LiteralValue): ProtoExpr = v match
    case LiteralValue.IntValue(n)     => ProtoExpr.Literal(LiteralValue.IntValue(math.abs(n)))
    case LiteralValue.LongValue(n)    => ProtoExpr.Literal(LiteralValue.LongValue(math.abs(n)))
    case LiteralValue.DoubleValue(n)  => ProtoExpr.Literal(LiteralValue.DoubleValue(math.abs(n)))
    case LiteralValue.FloatValue(n)   => ProtoExpr.Literal(LiteralValue.FloatValue(math.abs(n)))
    case LiteralValue.DecimalValue(n) => ProtoExpr.Literal(LiteralValue.DecimalValue(n.abs))
    case LiteralValue.NullValue(t)    => ProtoExpr.Literal(LiteralValue.NullValue(t))
    case _                            => ProtoExpr.Abs(ProtoExpr.Literal(v))

  private def foldSign(v: LiteralValue): ProtoExpr = v match
    case LiteralValue.IntValue(n)     => ProtoExpr.Literal(LiteralValue.IntValue(n.sign))
    case LiteralValue.LongValue(n)    => ProtoExpr.Literal(LiteralValue.LongValue(n.sign))
    case LiteralValue.DoubleValue(n)  => ProtoExpr.Literal(LiteralValue.DoubleValue(math.signum(n)))
    case LiteralValue.FloatValue(n)   => ProtoExpr.Literal(LiteralValue.FloatValue(math.signum(n)))
    case LiteralValue.DecimalValue(n) => ProtoExpr.Literal(LiteralValue.IntValue(n.signum))
    case LiteralValue.NullValue(t)    => ProtoExpr.Literal(LiteralValue.NullValue(t))
    case _                            => ProtoExpr.Sign(ProtoExpr.Literal(v))

  private def foldCast(v: LiteralValue, targetType: ProtoType): Option[ProtoExpr] =
    (v, targetType) match
      // Identity casts
      case (LiteralValue.IntValue(_), ProtoType.IntegerType)     => Some(ProtoExpr.Literal(v))
      case (LiteralValue.LongValue(_), ProtoType.LongType)       => Some(ProtoExpr.Literal(v))
      case (LiteralValue.DoubleValue(_), ProtoType.DoubleType)   => Some(ProtoExpr.Literal(v))
      case (LiteralValue.StringValue(_), ProtoType.StringType)   => Some(ProtoExpr.Literal(v))
      case (LiteralValue.BooleanValue(_), ProtoType.BooleanType) => Some(ProtoExpr.Literal(v))

      // Numeric widening
      case (LiteralValue.IntValue(n), ProtoType.LongType) =>
        Some(ProtoExpr.Literal(LiteralValue.LongValue(n.toLong)))
      case (LiteralValue.IntValue(n), ProtoType.DoubleType) =>
        Some(ProtoExpr.Literal(LiteralValue.DoubleValue(n.toDouble)))
      case (LiteralValue.LongValue(n), ProtoType.DoubleType) =>
        Some(ProtoExpr.Literal(LiteralValue.DoubleValue(n.toDouble)))
      case (LiteralValue.FloatValue(n), ProtoType.DoubleType) =>
        Some(ProtoExpr.Literal(LiteralValue.DoubleValue(n.toDouble)))

      // String to numeric
      case (LiteralValue.StringValue(s), ProtoType.IntegerType) =>
        s.toIntOption.map(n => ProtoExpr.Literal(LiteralValue.IntValue(n)))
      case (LiteralValue.StringValue(s), ProtoType.LongType) =>
        s.toLongOption.map(n => ProtoExpr.Literal(LiteralValue.LongValue(n)))
      case (LiteralValue.StringValue(s), ProtoType.DoubleType) =>
        s.toDoubleOption.map(n => ProtoExpr.Literal(LiteralValue.DoubleValue(n)))

      // Numeric to string
      case (LiteralValue.IntValue(n), ProtoType.StringType) =>
        Some(ProtoExpr.Literal(LiteralValue.StringValue(n.toString)))
      case (LiteralValue.LongValue(n), ProtoType.StringType) =>
        Some(ProtoExpr.Literal(LiteralValue.StringValue(n.toString)))
      case (LiteralValue.DoubleValue(n), ProtoType.StringType) =>
        Some(ProtoExpr.Literal(LiteralValue.StringValue(n.toString)))
      case (LiteralValue.BooleanValue(b), ProtoType.StringType) =>
        Some(ProtoExpr.Literal(LiteralValue.StringValue(b.toString)))

      // Null cast preserves null with target type
      case (LiteralValue.NullValue(_), t) =>
        Some(ProtoExpr.Literal(LiteralValue.NullValue(t)))

      case _ => None
