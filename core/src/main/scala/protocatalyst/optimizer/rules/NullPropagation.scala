package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan
import protocatalyst.types._

/** Null propagation optimization rule.
  *
  * Propagates null knowledge through expressions and simplifies null-related operations.
  *
  * Examples:
  *   - `IsNull(non_nullable_expr)` → `FALSE`
  *   - `IsNotNull(non_nullable_expr)` → `TRUE`
  *   - `null_intolerant_func(NULL)` → `NULL`
  *   - `COALESCE(NULL, x, NULL, y)` → `COALESCE(x, y)`
  *   - `COALESCE(x, non_null, y)` → `COALESCE(x, non_null)` (truncate after non-nullable)
  *   - `IF(NULL, x, y)` → `y`
  *   - `NullIf(x, x)` → `NULL`
  *
  * Based on Spark Catalyst's NullPropagation rule (expressions.scala:860-929).
  */
object NullPropagation extends Rule:
  override val ruleName: String = "NullPropagation"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanExprs(plan)(propagateNulls)

  /** Propagate null knowledge through expressions. */
  def propagateNulls(expr: ProtoExpr): ProtoExpr =
    TreeTransform.transformExprUp(expr) {
      // IsNull on non-nullable expression is always FALSE
      case ProtoExpr.IsNull(child) if !isNullable(child) =>
        ProtoExpr.Literal(LiteralValue.BooleanValue(false))

      // IsNotNull on non-nullable expression is always TRUE
      case ProtoExpr.IsNotNull(child) if !isNullable(child) =>
        ProtoExpr.Literal(LiteralValue.BooleanValue(true))

      // IsNull on literal null is TRUE
      case ProtoExpr.IsNull(ProtoExpr.Literal(LiteralValue.NullValue(_))) =>
        ProtoExpr.Literal(LiteralValue.BooleanValue(true))

      // IsNotNull on literal null is FALSE
      case ProtoExpr.IsNotNull(ProtoExpr.Literal(LiteralValue.NullValue(_))) =>
        ProtoExpr.Literal(LiteralValue.BooleanValue(false))

      // Arithmetic with null → null (null-intolerant)
      case ProtoExpr.Add(ProtoExpr.Literal(LiteralValue.NullValue(t)), _) =>
        ProtoExpr.Literal(LiteralValue.NullValue(t))
      case ProtoExpr.Add(_, ProtoExpr.Literal(LiteralValue.NullValue(t))) =>
        ProtoExpr.Literal(LiteralValue.NullValue(t))
      case ProtoExpr.Subtract(ProtoExpr.Literal(LiteralValue.NullValue(t)), _) =>
        ProtoExpr.Literal(LiteralValue.NullValue(t))
      case ProtoExpr.Subtract(_, ProtoExpr.Literal(LiteralValue.NullValue(t))) =>
        ProtoExpr.Literal(LiteralValue.NullValue(t))
      case ProtoExpr.Multiply(ProtoExpr.Literal(LiteralValue.NullValue(t)), _) =>
        ProtoExpr.Literal(LiteralValue.NullValue(t))
      case ProtoExpr.Multiply(_, ProtoExpr.Literal(LiteralValue.NullValue(t))) =>
        ProtoExpr.Literal(LiteralValue.NullValue(t))
      case ProtoExpr.Divide(ProtoExpr.Literal(LiteralValue.NullValue(t)), _) =>
        ProtoExpr.Literal(LiteralValue.NullValue(t))
      case ProtoExpr.Divide(_, ProtoExpr.Literal(LiteralValue.NullValue(t))) =>
        ProtoExpr.Literal(LiteralValue.NullValue(t))

      // Comparisons with null → null
      case ProtoExpr.Eq(ProtoExpr.Literal(LiteralValue.NullValue(_)), _) =>
        ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType))
      case ProtoExpr.Eq(_, ProtoExpr.Literal(LiteralValue.NullValue(_))) =>
        ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType))
      case ProtoExpr.NotEq(ProtoExpr.Literal(LiteralValue.NullValue(_)), _) =>
        ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType))
      case ProtoExpr.NotEq(_, ProtoExpr.Literal(LiteralValue.NullValue(_))) =>
        ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType))
      case ProtoExpr.Lt(ProtoExpr.Literal(LiteralValue.NullValue(_)), _) =>
        ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType))
      case ProtoExpr.Lt(_, ProtoExpr.Literal(LiteralValue.NullValue(_))) =>
        ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType))
      case ProtoExpr.LtEq(ProtoExpr.Literal(LiteralValue.NullValue(_)), _) =>
        ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType))
      case ProtoExpr.LtEq(_, ProtoExpr.Literal(LiteralValue.NullValue(_))) =>
        ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType))
      case ProtoExpr.Gt(ProtoExpr.Literal(LiteralValue.NullValue(_)), _) =>
        ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType))
      case ProtoExpr.Gt(_, ProtoExpr.Literal(LiteralValue.NullValue(_))) =>
        ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType))
      case ProtoExpr.GtEq(ProtoExpr.Literal(LiteralValue.NullValue(_)), _) =>
        ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType))
      case ProtoExpr.GtEq(_, ProtoExpr.Literal(LiteralValue.NullValue(_))) =>
        ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType))

      // IF with null predicate → else branch (null is treated as false)
      case ProtoExpr.If(ProtoExpr.Literal(LiteralValue.NullValue(_)), _, falseValue) =>
        falseValue

      // Coalesce simplification
      case ProtoExpr.Coalesce(children) =>
        simplifyCoalesce(children)

      // AND with null handling: NULL AND FALSE → FALSE, NULL AND TRUE → NULL
      case ProtoExpr.And(children) if containsNullLiteral(children) =>
        simplifyAndWithNull(children)

      // OR with null handling: NULL OR TRUE → TRUE, NULL OR FALSE → NULL
      case ProtoExpr.Or(children) if containsNullLiteral(children) =>
        simplifyOrWithNull(children)

      // NullIf(x, x) → NULL when both sides are equivalent
      case ProtoExpr.NullIf(left, right) if left == right =>
        ProtoExpr.Literal(LiteralValue.NullValue(inferType(left)))
    }

  /** Simplify COALESCE by removing null literals and truncating after first non-nullable. */
  private def simplifyCoalesce(children: Vector[ProtoExpr]): ProtoExpr =
    // Remove null literals
    val withoutNulls = children.filterNot {
      case ProtoExpr.Literal(LiteralValue.NullValue(_)) => true
      case _                                            => false
    }

    if withoutNulls.isEmpty then
      // All nulls → return null
      ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.NullType))
    else
      // Truncate after first non-nullable expression
      val truncated = withoutNulls.indexWhere(!isNullable(_)) match
        case -1  => withoutNulls // No non-nullable found, keep all
        case idx => withoutNulls.take(idx + 1) // Include the non-nullable, stop after

      truncated match
        case Vector(single) => single
        case multiple       => ProtoExpr.Coalesce(multiple)

  /** Check if expression is nullable. */
  private def isNullable(expr: ProtoExpr): Boolean = expr match
    case ProtoExpr.Literal(LiteralValue.NullValue(_)) => true
    case ProtoExpr.Literal(_)                         => false
    case ProtoExpr.ColumnRef(_, _, _, nullable)       => nullable
    case ProtoExpr.BoundRef(_, _, nullable)           => nullable

    // Expressions that are never null
    case ProtoExpr.IsNull(_)    => false
    case ProtoExpr.IsNotNull(_) => false

    // Coalesce is non-nullable if any child is non-nullable
    case ProtoExpr.Coalesce(children) => children.forall(isNullable)

    // If is nullable if either branch is nullable
    case ProtoExpr.If(_, t, f) => isNullable(t) || isNullable(f)

    // For most expressions, nullable if any child is nullable (conservative)
    case _ => true

  /** Infer the type of an expression (simplified). */
  private def inferType(expr: ProtoExpr): ProtoType = expr match
    case ProtoExpr.Literal(LiteralValue.BooleanValue(_))       => ProtoType.BooleanType
    case ProtoExpr.Literal(LiteralValue.IntValue(_))           => ProtoType.IntegerType
    case ProtoExpr.Literal(LiteralValue.LongValue(_))          => ProtoType.LongType
    case ProtoExpr.Literal(LiteralValue.DoubleValue(_))        => ProtoType.DoubleType
    case ProtoExpr.Literal(LiteralValue.StringValue(_))        => ProtoType.StringType
    case ProtoExpr.Literal(LiteralValue.NullValue(t))          => t
    case ProtoExpr.ColumnRef(_, _, resolvedType, _)            => resolvedType
    case ProtoExpr.BoundRef(_, dataType, _)                    => dataType
    case ProtoExpr.Cast(_, targetType)                         => targetType
    case ProtoExpr.Eq(_, _) | ProtoExpr.NotEq(_, _)            => ProtoType.BooleanType
    case ProtoExpr.Lt(_, _) | ProtoExpr.LtEq(_, _)             => ProtoType.BooleanType
    case ProtoExpr.Gt(_, _) | ProtoExpr.GtEq(_, _)             => ProtoType.BooleanType
    case ProtoExpr.And(_) | ProtoExpr.Or(_) | ProtoExpr.Not(_) => ProtoType.BooleanType
    case ProtoExpr.IsNull(_) | ProtoExpr.IsNotNull(_)          => ProtoType.BooleanType
    case _                                                     => ProtoType.NullType

  /** Check if children contain a null literal. */
  private def containsNullLiteral(children: Vector[ProtoExpr]): Boolean =
    children.exists {
      case ProtoExpr.Literal(LiteralValue.NullValue(_)) => true
      case _                                            => false
    }

  /** Simplify AND with null literals. NULL AND FALSE → FALSE, NULL AND TRUE → NULL */
  private def simplifyAndWithNull(children: Vector[ProtoExpr]): ProtoExpr =
    val (nulls, nonNulls) = children.partition {
      case ProtoExpr.Literal(LiteralValue.NullValue(_)) => true
      case _                                            => false
    }

    // If any non-null child is FALSE, result is FALSE
    if nonNulls.exists {
        case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => true
        case _                                                   => false
      }
    then ProtoExpr.Literal(LiteralValue.BooleanValue(false))
    // If all non-null children are TRUE and there are nulls, result is NULL
    else if nonNulls.forall {
        case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => true
        case _                                                  => false
      } && nulls.nonEmpty
    then ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType))
    // Otherwise keep the AND with non-null children and one null
    else if nonNulls.isEmpty then ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType))
    else if nulls.isEmpty then ProtoExpr.And(nonNulls)
    else ProtoExpr.And(nonNulls :+ nulls.head)

  /** Simplify OR with null literals. NULL OR TRUE → TRUE, NULL OR FALSE → NULL */
  private def simplifyOrWithNull(children: Vector[ProtoExpr]): ProtoExpr =
    val (nulls, nonNulls) = children.partition {
      case ProtoExpr.Literal(LiteralValue.NullValue(_)) => true
      case _                                            => false
    }

    // If any non-null child is TRUE, result is TRUE
    if nonNulls.exists {
        case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => true
        case _                                                  => false
      }
    then ProtoExpr.Literal(LiteralValue.BooleanValue(true))
    // If all non-null children are FALSE and there are nulls, result is NULL
    else if nonNulls.forall {
        case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => true
        case _                                                   => false
      } && nulls.nonEmpty
    then ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType))
    // Otherwise keep the OR with non-null children and one null
    else if nonNulls.isEmpty then ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType))
    else if nulls.isEmpty then ProtoExpr.Or(nonNulls)
    else ProtoExpr.Or(nonNulls :+ nulls.head)
