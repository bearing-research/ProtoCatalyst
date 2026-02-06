package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan
import protocatalyst.types._

/** Optimizes binary comparisons by removing unnecessary casts.
  *
  * When comparing a casted column with a literal, we can often remove the cast and adjust the
  * literal instead. This can enable predicate pushdown and reduce runtime overhead.
  *
  * Examples:
  *   - `CAST(int_col AS BIGINT) = 5L` → `int_col = 5`
  *   - `CAST(int_col AS BIGINT) > 10L` → `int_col > 10`
  *   - `CAST(short_col AS INT) = 100` → `short_col = 100S`
  *
  * Based on Spark Catalyst's UnwrapCastInBinaryComparison rule.
  */
object UnwrapCastInBinaryComparison extends Rule:
  override val ruleName: String = "UnwrapCastInBinaryComparison"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanExprs(plan)(unwrapCast)

  def unwrapCast(expr: ProtoExpr): ProtoExpr =
    TreeTransform.transformExprUp(expr) {
      case eq @ ProtoExpr.Eq(ProtoExpr.Cast(child, _), ProtoExpr.Literal(v)) =>
        tryUnwrap(child, v).map(l => ProtoExpr.Eq(child, ProtoExpr.Literal(l))).getOrElse(eq)

      case eq @ ProtoExpr.Eq(ProtoExpr.Literal(v), ProtoExpr.Cast(child, _)) =>
        tryUnwrap(child, v).map(l => ProtoExpr.Eq(ProtoExpr.Literal(l), child)).getOrElse(eq)

      case neq @ ProtoExpr.NotEq(ProtoExpr.Cast(child, _), ProtoExpr.Literal(v)) =>
        tryUnwrap(child, v).map(l => ProtoExpr.NotEq(child, ProtoExpr.Literal(l))).getOrElse(neq)

      case neq @ ProtoExpr.NotEq(ProtoExpr.Literal(v), ProtoExpr.Cast(child, _)) =>
        tryUnwrap(child, v).map(l => ProtoExpr.NotEq(ProtoExpr.Literal(l), child)).getOrElse(neq)

      case lt @ ProtoExpr.Lt(ProtoExpr.Cast(child, _), ProtoExpr.Literal(v)) =>
        tryUnwrap(child, v).map(l => ProtoExpr.Lt(child, ProtoExpr.Literal(l))).getOrElse(lt)

      case lt @ ProtoExpr.Lt(ProtoExpr.Literal(v), ProtoExpr.Cast(child, _)) =>
        tryUnwrap(child, v).map(l => ProtoExpr.Lt(ProtoExpr.Literal(l), child)).getOrElse(lt)

      case lte @ ProtoExpr.LtEq(ProtoExpr.Cast(child, _), ProtoExpr.Literal(v)) =>
        tryUnwrap(child, v).map(l => ProtoExpr.LtEq(child, ProtoExpr.Literal(l))).getOrElse(lte)

      case lte @ ProtoExpr.LtEq(ProtoExpr.Literal(v), ProtoExpr.Cast(child, _)) =>
        tryUnwrap(child, v).map(l => ProtoExpr.LtEq(ProtoExpr.Literal(l), child)).getOrElse(lte)

      case gt @ ProtoExpr.Gt(ProtoExpr.Cast(child, _), ProtoExpr.Literal(v)) =>
        tryUnwrap(child, v).map(l => ProtoExpr.Gt(child, ProtoExpr.Literal(l))).getOrElse(gt)

      case gt @ ProtoExpr.Gt(ProtoExpr.Literal(v), ProtoExpr.Cast(child, _)) =>
        tryUnwrap(child, v).map(l => ProtoExpr.Gt(ProtoExpr.Literal(l), child)).getOrElse(gt)

      case gte @ ProtoExpr.GtEq(ProtoExpr.Cast(child, _), ProtoExpr.Literal(v)) =>
        tryUnwrap(child, v).map(l => ProtoExpr.GtEq(child, ProtoExpr.Literal(l))).getOrElse(gte)

      case gte @ ProtoExpr.GtEq(ProtoExpr.Literal(v), ProtoExpr.Cast(child, _)) =>
        tryUnwrap(child, v).map(l => ProtoExpr.GtEq(ProtoExpr.Literal(l), child)).getOrElse(gte)
    }

  private def tryUnwrap(child: ProtoExpr, literal: LiteralValue): Option[LiteralValue] =
    getExprType(child).flatMap(tpe => castLiteral(literal, tpe))

  private def getExprType(expr: ProtoExpr): Option[ProtoType] = expr match
    case ProtoExpr.ColumnRef(_, _, resolvedType, _) => Some(resolvedType)
    case ProtoExpr.BoundRef(_, dataType, _)         => Some(dataType)
    case _                                          => None

  private def castLiteral(literal: LiteralValue, targetType: ProtoType): Option[LiteralValue] =
    castIntegerTypes(literal, targetType)
      .orElse(castFloatingTypes(literal, targetType))

  private def castIntegerTypes(literal: LiteralValue, targetType: ProtoType): Option[LiteralValue] =
    (literal, targetType) match
      case (LiteralValue.LongValue(v), ProtoType.IntegerType)
          if v >= Int.MinValue && v <= Int.MaxValue =>
        Some(LiteralValue.IntValue(v.toInt))
      case (LiteralValue.LongValue(v), ProtoType.ShortType)
          if v >= Short.MinValue && v <= Short.MaxValue =>
        Some(LiteralValue.ShortValue(v.toShort))
      case (LiteralValue.LongValue(v), ProtoType.ByteType)
          if v >= Byte.MinValue && v <= Byte.MaxValue =>
        Some(LiteralValue.ByteValue(v.toByte))
      case (v @ LiteralValue.LongValue(_), ProtoType.LongType) =>
        Some(v)
      case (LiteralValue.IntValue(v), ProtoType.ShortType)
          if v >= Short.MinValue && v <= Short.MaxValue =>
        Some(LiteralValue.ShortValue(v.toShort))
      case (LiteralValue.IntValue(v), ProtoType.ByteType)
          if v >= Byte.MinValue && v <= Byte.MaxValue =>
        Some(LiteralValue.ByteValue(v.toByte))
      case (v @ LiteralValue.IntValue(_), ProtoType.IntegerType) =>
        Some(v)
      case (LiteralValue.ShortValue(v), ProtoType.ByteType)
          if v >= Byte.MinValue && v <= Byte.MaxValue =>
        Some(LiteralValue.ByteValue(v.toByte))
      case (v @ LiteralValue.ShortValue(_), ProtoType.ShortType) =>
        Some(v)
      case (v @ LiteralValue.ByteValue(_), ProtoType.ByteType) =>
        Some(v)
      case _ =>
        None

  private def castFloatingTypes(
      literal: LiteralValue,
      targetType: ProtoType
  ): Option[LiteralValue] =
    (literal, targetType) match
      case (LiteralValue.DoubleValue(v), ProtoType.FloatType)
          if v >= Float.MinValue && v <= Float.MaxValue =>
        Some(LiteralValue.FloatValue(v.toFloat))
      case (v @ LiteralValue.DoubleValue(_), ProtoType.DoubleType) =>
        Some(v)
      case (v @ LiteralValue.FloatValue(_), ProtoType.FloatType) =>
        Some(v)
      case _ =>
        None
