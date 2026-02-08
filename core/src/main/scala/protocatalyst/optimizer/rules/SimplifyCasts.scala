package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan
import protocatalyst.types._

/** Removes unnecessary cast operations.
  *
  * This rule simplifies cast expressions that don't actually change the type or are redundant.
  *
  * Examples:
  *   - `CAST(int_col AS INT)` → `int_col` (cast to same type)
  *   - `CAST(CAST(x AS BIGINT) AS BIGINT)` → `CAST(x AS BIGINT)` (nested casts to same type)
  *   - `CAST(CAST(x AS INT) AS BIGINT)` → `CAST(x AS BIGINT)` (collapse widening casts)
  *   - `CAST(literal AS type)` is handled by ConstantFolding
  *
  * Based on Spark Catalyst's SimplifyCasts rule (expressions.scala:1200-1280).
  */
object SimplifyCasts extends Rule:
  override val ruleName: String = "SimplifyCasts"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanExprs(plan)(simplifyCasts)

  /** Simplify cast expressions in an expression tree.
    */
  def simplifyCasts(expr: ProtoExpr): ProtoExpr =
    TreeTransform.transformExprUp(expr) {
      // Cast to same type is a no-op
      case ProtoExpr.Cast(child, targetType) if getExprType(child).contains(targetType) =>
        child

      // Nested casts to same type: CAST(CAST(x AS T) AS T) → CAST(x AS T)
      case ProtoExpr.Cast(ProtoExpr.Cast(inner, innerType), outerType) if innerType == outerType =>
        ProtoExpr.Cast(inner, outerType)

      // Nested widening casts can be collapsed
      // CAST(CAST(x AS INT) AS BIGINT) → CAST(x AS BIGINT)
      case ProtoExpr.Cast(ProtoExpr.Cast(inner, innerType), outerType)
          if isWideningCast(innerType, outerType) =>
        ProtoExpr.Cast(inner, outerType)

      // Cast of a column reference that already has the target type
      case cast @ ProtoExpr.Cast(ProtoExpr.ColumnRef(name, qual, colType, nullable), targetType)
          if colType == targetType =>
        ProtoExpr.ColumnRef(name, qual, colType, nullable)
    }

  /** Get the type of an expression if known.
    */
  private def getExprType(expr: ProtoExpr): Option[ProtoType] = expr match
    case ProtoExpr.Literal(v)                       => Some(getLiteralType(v))
    case ProtoExpr.ColumnRef(_, _, resolvedType, _) => Some(resolvedType)
    case ProtoExpr.Cast(_, targetType)              => Some(targetType)
    case ProtoExpr.BoundRef(_, dataType, _)         => Some(dataType)
    case ProtoExpr.Add(_, _)                        => None // Would need type inference
    case ProtoExpr.Subtract(_, _)                   => None
    case ProtoExpr.Multiply(_, _)                   => None
    case ProtoExpr.Divide(_, _)                     => Some(ProtoType.DoubleType)
    case ProtoExpr.Eq(_, _)                         => Some(ProtoType.BooleanType)
    case ProtoExpr.NotEq(_, _)                      => Some(ProtoType.BooleanType)
    case ProtoExpr.Lt(_, _)                         => Some(ProtoType.BooleanType)
    case ProtoExpr.LtEq(_, _)                       => Some(ProtoType.BooleanType)
    case ProtoExpr.Gt(_, _)                         => Some(ProtoType.BooleanType)
    case ProtoExpr.GtEq(_, _)                       => Some(ProtoType.BooleanType)
    case ProtoExpr.And(_)                           => Some(ProtoType.BooleanType)
    case ProtoExpr.Or(_)                            => Some(ProtoType.BooleanType)
    case ProtoExpr.Not(_)                           => Some(ProtoType.BooleanType)
    case ProtoExpr.IsNull(_)                        => Some(ProtoType.BooleanType)
    case ProtoExpr.IsNotNull(_)                     => Some(ProtoType.BooleanType)
    case ProtoExpr.Like(_, _, _)                    => Some(ProtoType.BooleanType)
    case ProtoExpr.In(_, _)                         => Some(ProtoType.BooleanType)
    case ProtoExpr.Upper(_)                         => Some(ProtoType.StringType)
    case ProtoExpr.Lower(_)                         => Some(ProtoType.StringType)
    case ProtoExpr.Concat(_)                        => Some(ProtoType.StringType)
    case ProtoExpr.Substring(_, _, _)               => Some(ProtoType.StringType)
    case ProtoExpr.Trim(_, _, _)                    => Some(ProtoType.StringType)
    case ProtoExpr.Replace(_, _, _)                 => Some(ProtoType.StringType)
    case ProtoExpr.Reverse(_)                       => Some(ProtoType.StringType)
    case ProtoExpr.Length(_)                        => Some(ProtoType.IntegerType)
    case ProtoExpr.Alias(child, _)                  => getExprType(child)
    case _                                          => None

  /** Get the type of a literal value.
    */
  private def getLiteralType(v: LiteralValue): ProtoType = v match
    case LiteralValue.BooleanValue(_)     => ProtoType.BooleanType
    case LiteralValue.ByteValue(_)        => ProtoType.ByteType
    case LiteralValue.ShortValue(_)       => ProtoType.ShortType
    case LiteralValue.IntValue(_)         => ProtoType.IntegerType
    case LiteralValue.LongValue(_)        => ProtoType.LongType
    case LiteralValue.FloatValue(_)       => ProtoType.FloatType
    case LiteralValue.DoubleValue(_)      => ProtoType.DoubleType
    case LiteralValue.StringValue(_)      => ProtoType.StringType
    case LiteralValue.DecimalValue(value) =>
      val s = value.scale.max(0)
      val p = value.precision.max(s + 1)
      ProtoType.DecimalType(p, s)
    case LiteralValue.DateValue(_)                   => ProtoType.DateType
    case LiteralValue.TimestampValue(_)              => ProtoType.TimestampType
    case LiteralValue.TimeValue(_)                   => ProtoType.TimeType(6)
    case LiteralValue.CalendarIntervalValue(_, _, _) => ProtoType.CalendarIntervalType
    case LiteralValue.BinaryValue(_)                 => ProtoType.BinaryType
    case LiteralValue.NullValue(t)                   => t

  /** Check if casting from one type to another is a widening cast. Widening casts preserve the
    * value without loss of precision.
    */
  private def isWideningCast(from: ProtoType, to: ProtoType): Boolean = (from, to) match
    // Integer widening
    case (ProtoType.ByteType, ProtoType.ShortType)    => true
    case (ProtoType.ByteType, ProtoType.IntegerType)  => true
    case (ProtoType.ByteType, ProtoType.LongType)     => true
    case (ProtoType.ShortType, ProtoType.IntegerType) => true
    case (ProtoType.ShortType, ProtoType.LongType)    => true
    case (ProtoType.IntegerType, ProtoType.LongType)  => true

    // Integer to floating point (may lose precision for large values but is widening)
    case (ProtoType.IntegerType, ProtoType.DoubleType) => true
    case (ProtoType.LongType, ProtoType.DoubleType)    => true
    case (ProtoType.FloatType, ProtoType.DoubleType)   => true

    case _ => false
