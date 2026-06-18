package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan._

/** Pushes filter predicates closer to data sources.
  *
  * This rule moves filter conditions down through the plan tree to reduce the amount of data
  * processed by subsequent operators. It handles:
  *
  *   - Push through Project: Rewrites filter conditions using projection aliases
  *   - Push through SubqueryAlias: Simply moves the filter below the alias
  *   - Push to Join sides: Splits conjunctive predicates and pushes them to the appropriate side
  *
  * Examples:
  * {{{
  * Before:
  *   Filter [a > 5]
  *     Project [x AS a, y AS b]
  *       Scan
  *
  * After:
  *   Project [x AS a, y AS b]
  *     Filter [x > 5]
  *       Scan
  * }}}
  *
  * {{{
  * Before:
  *   Filter [a > 5 AND b < 10]
  *     Join [a = c] (Inner)
  *       Scan left [a, b]
  *       Scan right [c, d]
  *
  * After:
  *   Join [a = c] (Inner)
  *     Filter [a > 5 AND b < 10]
  *       Scan left [a, b]
  *     Scan right [c, d]
  * }}}
  *
  * Based on Spark Catalyst's PushDownPredicates rule (Optimizer.scala:2038-2234).
  */
object PushDownPredicates extends Rule:
  override val ruleName: String = "PushDownPredicates"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanDown(plan) {
      // Push Filter through Project
      case ProtoLogicalPlan.Filter(
            condition,
            project @ ProtoLogicalPlan.Project(projectList, child)
          ) =>
        val rewrittenCondition = rewriteCondition(condition, projectList)
        ProtoLogicalPlan.Project(
          projectList,
          ProtoLogicalPlan.Filter(rewrittenCondition, child)
        )

      // Push Filter through SubqueryAlias
      case ProtoLogicalPlan.Filter(
            condition,
            ProtoLogicalPlan.SubqueryAlias(alias, child)
          ) =>
        ProtoLogicalPlan.SubqueryAlias(
          alias,
          ProtoLogicalPlan.Filter(condition, child)
        )

      // Push Filter through Inner Join
      case ProtoLogicalPlan.Filter(
            condition,
            join @ ProtoLogicalPlan.Join(left, right, JoinType.Inner, joinCond)
          ) =>
        val predicates = splitConjunctivePredicates(condition)
        val leftColumns = collectColumnRefs(left)
        val rightColumns = collectColumnRefs(right)

        val (leftPredicates, rightPredicates, remainingPredicates) =
          partitionPredicates(predicates, leftColumns, rightColumns)

        val newLeft =
          if leftPredicates.nonEmpty then
            ProtoLogicalPlan.Filter(combinePredicates(leftPredicates), left)
          else left

        val newRight =
          if rightPredicates.nonEmpty then
            ProtoLogicalPlan.Filter(combinePredicates(rightPredicates), right)
          else right

        val newJoin = ProtoLogicalPlan.Join(newLeft, newRight, JoinType.Inner, joinCond)

        if remainingPredicates.nonEmpty then
          ProtoLogicalPlan.Filter(combinePredicates(remainingPredicates), newJoin)
        else newJoin

      // Push Filter through Cross Join (same as Inner)
      case ProtoLogicalPlan.Filter(
            condition,
            join @ ProtoLogicalPlan.Join(left, right, JoinType.Cross, joinCond)
          ) =>
        val predicates = splitConjunctivePredicates(condition)
        val leftColumns = collectColumnRefs(left)
        val rightColumns = collectColumnRefs(right)

        val (leftPredicates, rightPredicates, remainingPredicates) =
          partitionPredicates(predicates, leftColumns, rightColumns)

        val newLeft =
          if leftPredicates.nonEmpty then
            ProtoLogicalPlan.Filter(combinePredicates(leftPredicates), left)
          else left

        val newRight =
          if rightPredicates.nonEmpty then
            ProtoLogicalPlan.Filter(combinePredicates(rightPredicates), right)
          else right

        val newJoin = ProtoLogicalPlan.Join(newLeft, newRight, JoinType.Cross, joinCond)

        if remainingPredicates.nonEmpty then
          ProtoLogicalPlan.Filter(combinePredicates(remainingPredicates), newJoin)
        else newJoin

      // Push Filter to left side of Left Outer Join
      // Only predicates referencing only left columns can be pushed
      case ProtoLogicalPlan.Filter(
            condition,
            join @ ProtoLogicalPlan.Join(left, right, JoinType.LeftOuter, joinCond)
          ) =>
        val predicates = splitConjunctivePredicates(condition)
        val leftColumns = collectColumnRefs(left)
        val rightColumns = collectColumnRefs(right)

        // For left outer join, we can only push predicates that reference ONLY left columns
        val (leftPredicates, remainingPredicates) = predicates.partition { pred =>
          val refs = getColumnRefs(pred)
          refs.nonEmpty && refs.forall(leftColumns.contains)
        }

        val newLeft =
          if leftPredicates.nonEmpty then
            ProtoLogicalPlan.Filter(combinePredicates(leftPredicates), left)
          else left

        val newJoin = ProtoLogicalPlan.Join(newLeft, right, JoinType.LeftOuter, joinCond)

        if remainingPredicates.nonEmpty then
          ProtoLogicalPlan.Filter(combinePredicates(remainingPredicates), newJoin)
        else newJoin

      // Push Filter to right side of Right Outer Join
      case ProtoLogicalPlan.Filter(
            condition,
            join @ ProtoLogicalPlan.Join(left, right, JoinType.RightOuter, joinCond)
          ) =>
        val predicates = splitConjunctivePredicates(condition)
        val leftColumns = collectColumnRefs(left)
        val rightColumns = collectColumnRefs(right)

        // For right outer join, we can only push predicates that reference ONLY right columns
        val (rightPredicates, remainingPredicates) = predicates.partition { pred =>
          val refs = getColumnRefs(pred)
          refs.nonEmpty && refs.forall(rightColumns.contains)
        }

        val newRight =
          if rightPredicates.nonEmpty then
            ProtoLogicalPlan.Filter(combinePredicates(rightPredicates), right)
          else right

        val newJoin = ProtoLogicalPlan.Join(left, newRight, JoinType.RightOuter, joinCond)

        if remainingPredicates.nonEmpty then
          ProtoLogicalPlan.Filter(combinePredicates(remainingPredicates), newJoin)
        else newJoin

      // Push Filter through Union (to all children)
      case ProtoLogicalPlan.Filter(
            condition,
            ProtoLogicalPlan.Union(children, byName, allowMissing)
          ) =>
        ProtoLogicalPlan.Union(
          children.map(child => ProtoLogicalPlan.Filter(condition, child)),
          byName,
          allowMissing
        )

      // Push Filter through Hint
      case ProtoLogicalPlan.Filter(
            condition,
            ProtoLogicalPlan.ResolvedHint(hints, child)
          ) =>
        ProtoLogicalPlan.ResolvedHint(
          hints,
          ProtoLogicalPlan.Filter(condition, child)
        )
    }

  /** Rewrite filter condition by substituting column references with their definitions from
    * projections.
    */
  private def rewriteCondition(condition: ProtoExpr, projectList: Vector[ProtoExpr]): ProtoExpr =
    TreeTransform.transformExprUp(condition) {
      case ref @ ProtoExpr.ColumnRef(name, qualifier, _, _) =>
        findBinding(name, qualifier, projectList).getOrElse(ref)
    }

  /** Find a binding for a column reference in the projection list. */
  private def findBinding(
      name: String,
      qualifier: Option[String],
      bindings: Vector[ProtoExpr]
  ): Option[ProtoExpr] =
    bindings.collectFirst {
      case ProtoExpr.Alias(child, aliasName) if aliasName == name => child
      case ref @ ProtoExpr.ColumnRef(colName, colQualifier, _, _)
          if colName == name && (qualifier.isEmpty || colQualifier == qualifier) =>
        ref
    }

  /** Split a conjunctive predicate (AND) into individual predicates. */
  def splitConjunctivePredicates(condition: ProtoExpr): Vector[ProtoExpr] =
    condition match
      case ProtoExpr.And(children) => children.flatMap(splitConjunctivePredicates)
      case other                   => Vector(other)

  /** Combine predicates with AND. */
  def combinePredicates(predicates: Vector[ProtoExpr]): ProtoExpr =
    predicates match
      case Vector(single) => single
      case multiple       => ProtoExpr.And(multiple)

  /** Partition predicates into left-only, right-only, and remaining. */
  private def partitionPredicates(
      predicates: Vector[ProtoExpr],
      leftColumns: Set[(String, Option[String])],
      rightColumns: Set[(String, Option[String])]
  ): (Vector[ProtoExpr], Vector[ProtoExpr], Vector[ProtoExpr]) =
    val leftOnly = predicates.filter { pred =>
      val refs = getColumnRefs(pred)
      refs.nonEmpty && refs.forall(leftColumns.contains)
    }
    val rightOnly = predicates.filter { pred =>
      val refs = getColumnRefs(pred)
      refs.nonEmpty && refs.forall(rightColumns.contains)
    }
    val remaining = predicates.filterNot(p => leftOnly.contains(p) || rightOnly.contains(p))
    (leftOnly, rightOnly, remaining)

  /** Get all column references from an expression. */
  private def getColumnRefs(expr: ProtoExpr): Set[(String, Option[String])] =
    var refs = Set.empty[(String, Option[String])]
    TreeTransform.transformExprUp(expr) { case ref @ ProtoExpr.ColumnRef(name, qualifier, _, _) =>
      refs = refs + ((name, qualifier))
      ref
    }
    refs

  /** Collect column references from a plan's output (simplified). */
  private def collectColumnRefs(plan: ProtoLogicalPlan): Set[(String, Option[String])] =
    plan match
      case ProtoLogicalPlan.RelationRef(name, alias, contract) =>
        // The relation's own columns, qualified by its alias (or name). Without this, a predicate
        // can't be attributed to a join side and won't push down — so e.g. TPC-H Q3's
        // `l.shipdate > DATE '…'` stayed *above* the join, forcing the join to materialize the full
        // 6M-row product before the filter cut it to ~30k. Each field is offered both qualified
        // (`l.shipdate`) and bare, so references resolve whether or not they carry the alias.
        val qualifier = alias.orElse(Some(name))
        contract.requiredFields.flatMap(f => Seq((f.name, qualifier), (f.name, None))).toSet

      case ProtoLogicalPlan.Project(projectList, _) =>
        projectList.flatMap {
          case ProtoExpr.Alias(_, name)                   => Some((name, None))
          case ProtoExpr.ColumnRef(name, qualifier, _, _) => Some((name, qualifier))
          case _                                          => None
        }.toSet

      case ProtoLogicalPlan.SubqueryAlias(alias, child) =>
        // Columns from child are now qualified with the alias
        collectColumnRefs(child).map { case (name, _) => (name, Some(alias)) }

      case ProtoLogicalPlan.Filter(_, child)   => collectColumnRefs(child)
      case ProtoLogicalPlan.Sort(_, child)     => collectColumnRefs(child)
      case ProtoLogicalPlan.Limit(_, child)    => collectColumnRefs(child)
      case ProtoLogicalPlan.Distinct(child)    => collectColumnRefs(child)
      case ProtoLogicalPlan.ResolvedHint(_, c) => collectColumnRefs(c)

      case ProtoLogicalPlan.Join(left, right, _, _) =>
        collectColumnRefs(left) ++ collectColumnRefs(right)

      case ProtoLogicalPlan.Aggregate(_, aggregateExprs, _) =>
        aggregateExprs.flatMap {
          case ProtoExpr.Alias(_, name)                   => Some((name, None))
          case ProtoExpr.ColumnRef(name, qualifier, _, _) => Some((name, qualifier))
          case _                                          => None
        }.toSet

      case _ => Set.empty
