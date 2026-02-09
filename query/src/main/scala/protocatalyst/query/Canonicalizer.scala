package protocatalyst.query

import protocatalyst.expr._
import protocatalyst.plan._
import protocatalyst.types._

/** Canonicalization transforms for plan stability. */
object Canonicalizer:

  def canonicalize(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    transformPlan(plan, canonicalizeExpr)

  def canonicalizeExpr(expr: ProtoExpr): ProtoExpr =
    val passes = Seq(
      flattenAndOr,
      sortAndChildren,
      foldConstants,
      simplifyBooleans
    )
    passes.foldLeft(expr)((e, pass) => transformExpr(e, pass))

  /** Flatten nested AND/OR. */
  val flattenAndOr: ProtoExpr => ProtoExpr = {
    case ProtoExpr.And(children) =>
      val flattened = children.flatMap {
        case ProtoExpr.And(inner) => inner.map(flattenAndOr)
        case other                => Vector(flattenAndOr(other))
      }
      ProtoExpr.And(flattened)

    case ProtoExpr.Or(children) =>
      val flattened = children.flatMap {
        case ProtoExpr.Or(inner) => inner.map(flattenAndOr)
        case other               => Vector(flattenAndOr(other))
      }
      ProtoExpr.Or(flattened)

    case other => other
  }

  /** Sort AND/OR children for deterministic ordering. */
  val sortAndChildren: ProtoExpr => ProtoExpr = {
    case ProtoExpr.And(children) =>
      ProtoExpr.And(children.sortBy(exprSortKey))
    case ProtoExpr.Or(children) =>
      ProtoExpr.Or(children.sortBy(exprSortKey))
    case other => other
  }

  /** Fold compile-time constants. */
  val foldConstants: ProtoExpr => ProtoExpr = {
    case ProtoExpr.Add(
          ProtoExpr.Literal(LiteralValue.IntValue(a)),
          ProtoExpr.Literal(LiteralValue.IntValue(b))
        ) =>
      ProtoExpr.Literal(LiteralValue.IntValue(a + b))

    case ProtoExpr.Add(
          ProtoExpr.Literal(LiteralValue.LongValue(a)),
          ProtoExpr.Literal(LiteralValue.LongValue(b))
        ) =>
      ProtoExpr.Literal(LiteralValue.LongValue(a + b))

    case ProtoExpr.Concat(children) if children.forall {
          case ProtoExpr.Literal(LiteralValue.StringValue(_)) => true
          case _                                              => false
        } =>
      val str = children.collect { case ProtoExpr.Literal(LiteralValue.StringValue(s)) =>
        s
      }.mkString
      ProtoExpr.Literal(LiteralValue.StringValue(str))

    case other => other
  }

  /** Simplify boolean expressions. */
  val simplifyBooleans: ProtoExpr => ProtoExpr = {
    case ProtoExpr.Not(ProtoExpr.Not(inner)) => inner

    case ProtoExpr.And(children) if children.isEmpty =>
      ProtoExpr.Literal(LiteralValue.BooleanValue(true))

    case ProtoExpr.And(children) =>
      val filtered = children.filterNot {
        case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => true
        case _                                                  => false
      }
      if filtered.exists {
          case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => true
          case _                                                   => false
        }
      then ProtoExpr.Literal(LiteralValue.BooleanValue(false))
      else if filtered.isEmpty then ProtoExpr.Literal(LiteralValue.BooleanValue(true))
      else if filtered.size == 1 then filtered.head
      else ProtoExpr.And(filtered)

    case ProtoExpr.Or(children) if children.isEmpty =>
      ProtoExpr.Literal(LiteralValue.BooleanValue(false))

    case ProtoExpr.Or(children) =>
      val filtered = children.filterNot {
        case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => true
        case _                                                   => false
      }
      if filtered.exists {
          case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => true
          case _                                                  => false
        }
      then ProtoExpr.Literal(LiteralValue.BooleanValue(true))
      else if filtered.isEmpty then ProtoExpr.Literal(LiteralValue.BooleanValue(false))
      else if filtered.size == 1 then filtered.head
      else ProtoExpr.Or(filtered)

    case other => other
  }

  private def exprSortKey(e: ProtoExpr): String = e match
    case ProtoExpr.ColumnRef(name, qual, _, _)          => qual.getOrElse("") + "." + name
    case ProtoExpr.Literal(LiteralValue.StringValue(s)) => s"lit:$s"
    case ProtoExpr.Literal(LiteralValue.IntValue(i))    => s"lit:$i"
    case _                                              => e.hashCode.toString

  private def transformPlan(
      plan: ProtoLogicalPlan,
      exprTransform: ProtoExpr => ProtoExpr
  ): ProtoLogicalPlan =
    import ProtoLogicalPlan.*
    plan match
      case Filter(cond, child) =>
        Filter(exprTransform(cond), transformPlan(child, exprTransform))

      case Project(exprs, child) =>
        Project(exprs.map(exprTransform), transformPlan(child, exprTransform))

      case Aggregate(grouping, aggs, child) =>
        Aggregate(
          grouping.map(exprTransform),
          aggs.map(exprTransform),
          transformPlan(child, exprTransform)
        )

      case Sort(order, child) =>
        Sort(
          order.map(so => so.copy(child = exprTransform(so.child))),
          transformPlan(child, exprTransform)
        )

      case Limit(n, child) =>
        Limit(n, transformPlan(child, exprTransform))

      case Distinct(child) =>
        Distinct(transformPlan(child, exprTransform))

      case SubqueryAlias(alias, child) =>
        SubqueryAlias(alias, transformPlan(child, exprTransform))

      case Join(left, right, joinType, cond) =>
        Join(
          transformPlan(left, exprTransform),
          transformPlan(right, exprTransform),
          joinType,
          cond.map(exprTransform)
        )

      case Union(children, byName, allowMissing) =>
        Union(children.map(transformPlan(_, exprTransform)), byName, allowMissing)

      case Intersect(left, right, isAll) =>
        Intersect(
          transformPlan(left, exprTransform),
          transformPlan(right, exprTransform),
          isAll
        )

      case Except(left, right, isAll) =>
        Except(
          transformPlan(left, exprTransform),
          transformPlan(right, exprTransform),
          isAll
        )

      case Window(windowExprs, partitionSpec, orderSpec, child) =>
        Window(
          windowExprs.map(exprTransform),
          partitionSpec.map(exprTransform),
          orderSpec.map(so => so.copy(child = exprTransform(so.child))),
          transformPlan(child, exprTransform)
        )

      case Values(rows, schema) =>
        Values(rows.map(_.map(exprTransform)), schema)

      case With(cteRelations, recursive, child) =>
        With(
          cteRelations.map((name, plan) => (name, transformPlan(plan, exprTransform))),
          recursive,
          transformPlan(child, exprTransform)
        )

      case Pivot(grouping, pivotCol, pivotVals, aggs, child) =>
        Pivot(
          grouping.map(exprTransform),
          exprTransform(pivotCol),
          pivotVals.map(exprTransform),
          aggs.map(exprTransform),
          transformPlan(child, exprTransform)
        )

      case Unpivot(valueCol, varCol, columns, includeNulls, child) =>
        Unpivot(
          valueCol,
          varCol,
          columns.map((e, alias) => (exprTransform(e), alias)),
          includeNulls,
          transformPlan(child, exprTransform)
        )

      case LateralJoin(left, lateral, condition) =>
        LateralJoin(
          transformPlan(left, exprTransform),
          transformPlan(lateral, exprTransform),
          condition.map(exprTransform)
        )

      case Generate(generator, output, outer, child) =>
        Generate(
          exprTransform(generator),
          output,
          outer,
          transformPlan(child, exprTransform)
        )

      case ResolvedHint(hints, child) =>
        ResolvedHint(hints, transformPlan(child, exprTransform))

      case Predict(model, inputMapping, child) =>
        Predict(
          model,
          inputMapping.map((name, expr) => (name, exprTransform(expr))),
          transformPlan(child, exprTransform)
        )

      case Fit(model, inputMapping, labelMapping, trainConfig, child) =>
        Fit(
          model,
          inputMapping.map((name, expr) => (name, exprTransform(expr))),
          labelMapping.map((name, expr) => (name, exprTransform(expr))),
          trainConfig,
          transformPlan(child, exprTransform)
        )

      case r: RelationRef => r

  private def transformExpr(
      expr: ProtoExpr,
      transform: ProtoExpr => ProtoExpr
  ): ProtoExpr =
    import ProtoExpr.*
    val transformed = expr match
      case And(children)  => And(children.map(c => transformExpr(c, transform)))
      case Or(children)   => Or(children.map(c => transformExpr(c, transform)))
      case Not(child)     => Not(transformExpr(child, transform))
      case Eq(l, r)       => Eq(transformExpr(l, transform), transformExpr(r, transform))
      case NotEq(l, r)    => NotEq(transformExpr(l, transform), transformExpr(r, transform))
      case Lt(l, r)       => Lt(transformExpr(l, transform), transformExpr(r, transform))
      case LtEq(l, r)     => LtEq(transformExpr(l, transform), transformExpr(r, transform))
      case Gt(l, r)       => Gt(transformExpr(l, transform), transformExpr(r, transform))
      case GtEq(l, r)     => GtEq(transformExpr(l, transform), transformExpr(r, transform))
      case Add(l, r)      => Add(transformExpr(l, transform), transformExpr(r, transform))
      case Subtract(l, r) => Subtract(transformExpr(l, transform), transformExpr(r, transform))
      case Multiply(l, r) => Multiply(transformExpr(l, transform), transformExpr(r, transform))
      case Divide(l, r)   => Divide(transformExpr(l, transform), transformExpr(r, transform))
      case Coalesce(cs)   => Coalesce(cs.map(c => transformExpr(c, transform)))
      case Concat(cs)     => Concat(cs.map(c => transformExpr(c, transform)))
      case In(v, list)    =>
        In(transformExpr(v, transform), list.map(e => transformExpr(e, transform)))
      case IsNull(c)    => IsNull(transformExpr(c, transform))
      case IsNotNull(c) => IsNotNull(transformExpr(c, transform))
      case Cast(c, t)   => Cast(transformExpr(c, transform), t)
      case Alias(c, n)  => Alias(transformExpr(c, transform), n)
      case If(p, t, f)  =>
        If(transformExpr(p, transform), transformExpr(t, transform), transformExpr(f, transform))
      case CaseWhen(branches, elseVal) =>
        CaseWhen(
          branches.map((c, v) => (transformExpr(c, transform), transformExpr(v, transform))),
          elseVal.map(e => transformExpr(e, transform))
        )
      case other => other
    transform(transformed)
