package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan._
import protocatalyst.types._

/** Eliminates outer joins that can be converted to inner joins.
  *
  * An outer join can be converted to an inner join when there's a filter condition that makes NULL
  * rows from the outer side impossible (null-rejecting predicate).
  *
  * Examples:
  *   - `LEFT OUTER JOIN ... WHERE right.col IS NOT NULL` → `INNER JOIN`
  *   - `RIGHT OUTER JOIN ... WHERE left.col IS NOT NULL` → `INNER JOIN`
  *   - `LEFT OUTER JOIN ... WHERE right.col = 5` → `INNER JOIN` (comparison rejects NULL)
  *   - `FULL OUTER JOIN ... WHERE left.col IS NOT NULL AND right.col IS NOT NULL` → `INNER JOIN`
  *
  * This optimization is important because inner joins have more optimization opportunities than
  * outer joins (e.g., join reordering).
  *
  * Based on Spark Catalyst's EliminateOuterJoin rule (joins.scala:158).
  */
object EliminateOuterJoin extends Rule:
  override val ruleName: String = "EliminateOuterJoin"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      // Filter on top of outer join - check if filter rejects nulls
      case ProtoLogicalPlan.Filter(
            condition,
            join @ ProtoLogicalPlan.Join(left, right, joinType, joinCond)
          ) if isOuterJoin(joinType) =>
        val leftColumns = collectColumns(left)
        val rightColumns = collectColumns(right)
        val filterPredicates = splitConjunctivePredicates(condition)

        joinType match
          case JoinType.LeftOuter =>
            // Left outer join: check if filter rejects nulls from right side
            if filterPredicates.exists(rejectsNullsFrom(_, rightColumns)) then
              ProtoLogicalPlan.Filter(
                condition,
                ProtoLogicalPlan.Join(left, right, JoinType.Inner, joinCond)
              )
            else ProtoLogicalPlan.Filter(condition, join)

          case JoinType.RightOuter =>
            // Right outer join: check if filter rejects nulls from left side
            if filterPredicates.exists(rejectsNullsFrom(_, leftColumns)) then
              ProtoLogicalPlan.Filter(
                condition,
                ProtoLogicalPlan.Join(left, right, JoinType.Inner, joinCond)
              )
            else ProtoLogicalPlan.Filter(condition, join)

          case JoinType.FullOuter =>
            // Full outer join: check if filter rejects nulls from both sides
            val rejectsLeft = filterPredicates.exists(rejectsNullsFrom(_, leftColumns))
            val rejectsRight = filterPredicates.exists(rejectsNullsFrom(_, rightColumns))
            if rejectsLeft && rejectsRight then
              ProtoLogicalPlan.Filter(
                condition,
                ProtoLogicalPlan.Join(left, right, JoinType.Inner, joinCond)
              )
            else if rejectsLeft then
              ProtoLogicalPlan.Filter(
                condition,
                ProtoLogicalPlan.Join(left, right, JoinType.RightOuter, joinCond)
              )
            else if rejectsRight then
              ProtoLogicalPlan.Filter(
                condition,
                ProtoLogicalPlan.Join(left, right, JoinType.LeftOuter, joinCond)
              )
            else ProtoLogicalPlan.Filter(condition, join)

          case _ =>
            ProtoLogicalPlan.Filter(condition, join)
    }

  private def isOuterJoin(joinType: JoinType): Boolean = joinType match
    case JoinType.LeftOuter | JoinType.RightOuter | JoinType.FullOuter => true
    case _                                                             => false

  /** Collect table aliases from a plan (for identifying which side of join a column comes from).
    */
  private def collectColumns(plan: ProtoLogicalPlan): Set[(String, Option[String])] =
    collectAliases(plan).map(alias => ("", Some(alias)))

  /** Collect table aliases and names from a plan.
    */
  private def collectAliases(plan: ProtoLogicalPlan): Set[String] =
    plan match
      case ProtoLogicalPlan.RelationRef(name, alias, _) =>
        alias.map(Set(_)).getOrElse(Set(name))
      case ProtoLogicalPlan.SubqueryAlias(alias, _) =>
        Set(alias)
      case ProtoLogicalPlan.Project(_, child)       => collectAliases(child)
      case ProtoLogicalPlan.Filter(_, child)        => collectAliases(child)
      case ProtoLogicalPlan.Join(left, right, _, _) =>
        collectAliases(left) ++ collectAliases(right)
      case ProtoLogicalPlan.Aggregate(_, _, child) => collectAliases(child)
      case ProtoLogicalPlan.Sort(_, _, child)      => collectAliases(child)
      case ProtoLogicalPlan.Limit(_, child)        => collectAliases(child)
      case ProtoLogicalPlan.Distinct(child)        => collectAliases(child)
      case _                                       => Set.empty

  /** Split conjunctive predicates (AND) into individual predicates.
    */
  private def splitConjunctivePredicates(expr: ProtoExpr): Vector[ProtoExpr] = expr match
    case ProtoExpr.And(children) => children.flatMap(splitConjunctivePredicates)
    case other                   => Vector(other)

  /** Check if a predicate would reject rows where columns from the given set are NULL.
    *
    * A predicate "rejects nulls" if it evaluates to FALSE or NULL when the column is NULL, meaning
    * rows with NULL in that column would be filtered out.
    */
  private def rejectsNullsFrom(
      predicate: ProtoExpr,
      columns: Set[(String, Option[String])]
  ): Boolean =
    // Get columns referenced in this predicate
    val referencedColumns = collectReferencedColumns(predicate)

    // Extract aliases from the columns set
    val targetAliases = columns.flatMap(_._2)

    // Check if any referenced column is from the target side (by qualifier matching alias)
    val referencesTargetSide = referencedColumns.exists { case (_, qual) =>
      qual.exists(targetAliases.contains)
    }

    if !referencesTargetSide then return false

    // Check if the predicate type rejects nulls
    isNullRejecting(predicate)

  /** Collect column references from an expression.
    */
  private def collectReferencedColumns(expr: ProtoExpr): Set[(String, Option[String])] =
    var columns = Set.empty[(String, Option[String])]

    def collect(e: ProtoExpr): Unit = e match
      case ProtoExpr.ColumnRef(name, qualifier, _, _) =>
        columns = columns + ((name, qualifier))
      case ProtoExpr.And(children) => children.foreach(collect)
      case ProtoExpr.Or(children)  => children.foreach(collect)
      case ProtoExpr.Not(child)    => collect(child)
      case ProtoExpr.Eq(l, r)      =>
        collect(l); collect(r)
      case ProtoExpr.NotEq(l, r) =>
        collect(l); collect(r)
      case ProtoExpr.Lt(l, r) =>
        collect(l); collect(r)
      case ProtoExpr.LtEq(l, r) =>
        collect(l); collect(r)
      case ProtoExpr.Gt(l, r) =>
        collect(l); collect(r)
      case ProtoExpr.GtEq(l, r) =>
        collect(l); collect(r)
      case ProtoExpr.IsNull(child)    => collect(child)
      case ProtoExpr.IsNotNull(child) => collect(child)
      case ProtoExpr.In(value, list)  =>
        collect(value)
        list.foreach(collect)
      case ProtoExpr.Like(value, pattern, _) =>
        collect(value)
        collect(pattern)
      case ProtoExpr.Add(l, r) =>
        collect(l); collect(r)
      case ProtoExpr.Subtract(l, r) =>
        collect(l); collect(r)
      case ProtoExpr.Multiply(l, r) =>
        collect(l); collect(r)
      case ProtoExpr.Divide(l, r) =>
        collect(l); collect(r)
      case ProtoExpr.Cast(child, _)  => collect(child)
      case ProtoExpr.Alias(child, _) => collect(child)
      case _                         => ()

    collect(expr)
    columns

  /** Check if a predicate is null-rejecting.
    *
    * A predicate is null-rejecting if it evaluates to FALSE or NULL when any of its column inputs
    * are NULL.
    */
  private def isNullRejecting(predicate: ProtoExpr): Boolean = predicate match
    // IS NOT NULL explicitly rejects nulls
    case ProtoExpr.IsNotNull(_) => true

    // IS NULL does NOT reject nulls (it accepts them)
    case ProtoExpr.IsNull(_) => false

    // Comparisons with non-null literal reject nulls (comparison with NULL is NULL, which is falsy)
    case ProtoExpr.Eq(_, ProtoExpr.Literal(v)) if !isNullLiteral(v)    => true
    case ProtoExpr.Eq(ProtoExpr.Literal(v), _) if !isNullLiteral(v)    => true
    case ProtoExpr.NotEq(_, ProtoExpr.Literal(v)) if !isNullLiteral(v) => true
    case ProtoExpr.NotEq(ProtoExpr.Literal(v), _) if !isNullLiteral(v) => true
    case ProtoExpr.Lt(_, ProtoExpr.Literal(v)) if !isNullLiteral(v)    => true
    case ProtoExpr.Lt(ProtoExpr.Literal(v), _) if !isNullLiteral(v)    => true
    case ProtoExpr.LtEq(_, ProtoExpr.Literal(v)) if !isNullLiteral(v)  => true
    case ProtoExpr.LtEq(ProtoExpr.Literal(v), _) if !isNullLiteral(v)  => true
    case ProtoExpr.Gt(_, ProtoExpr.Literal(v)) if !isNullLiteral(v)    => true
    case ProtoExpr.Gt(ProtoExpr.Literal(v), _) if !isNullLiteral(v)    => true
    case ProtoExpr.GtEq(_, ProtoExpr.Literal(v)) if !isNullLiteral(v)  => true
    case ProtoExpr.GtEq(ProtoExpr.Literal(v), _) if !isNullLiteral(v)  => true

    // Comparisons between two columns also reject nulls
    case ProtoExpr.Eq(ProtoExpr.ColumnRef(_, _, _, _), ProtoExpr.ColumnRef(_, _, _, _))    => true
    case ProtoExpr.NotEq(ProtoExpr.ColumnRef(_, _, _, _), ProtoExpr.ColumnRef(_, _, _, _)) => true
    case ProtoExpr.Lt(ProtoExpr.ColumnRef(_, _, _, _), ProtoExpr.ColumnRef(_, _, _, _))    => true
    case ProtoExpr.LtEq(ProtoExpr.ColumnRef(_, _, _, _), ProtoExpr.ColumnRef(_, _, _, _))  => true
    case ProtoExpr.Gt(ProtoExpr.ColumnRef(_, _, _, _), ProtoExpr.ColumnRef(_, _, _, _))    => true
    case ProtoExpr.GtEq(ProtoExpr.ColumnRef(_, _, _, _), ProtoExpr.ColumnRef(_, _, _, _))  => true

    // IN with non-null values rejects nulls
    case ProtoExpr.In(_, list) if list.forall(isNonNullLiteral) => true

    // AND is null-rejecting if any child is null-rejecting
    case ProtoExpr.And(children) => children.exists(isNullRejecting)

    // Other predicates - conservatively say they don't reject nulls
    case _ => false

  private def isNullLiteral(v: LiteralValue): Boolean = v match
    case LiteralValue.NullValue(_) => true
    case _                         => false

  private def isNonNullLiteral(e: ProtoExpr): Boolean = e match
    case ProtoExpr.Literal(v) => !isNullLiteral(v)
    case _                    => false
