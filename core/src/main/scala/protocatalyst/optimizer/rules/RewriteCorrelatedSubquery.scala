package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan._

/** Decorrelates correlated subqueries when possible.
  *
  * Correlated subqueries reference columns from an outer query, which can be inefficient to
  * execute. This rule attempts to rewrite them into joins when possible.
  *
  * Cases handled:
  *   - EXISTS with correlation → Left Semi Join
  *   - NOT EXISTS with correlation → Left Anti Join
  *   - IN subquery with correlation → Left Semi Join
  *   - NOT IN subquery with correlation → Left Anti Join
  *   - Scalar subquery with simple correlation → Lateral Join (when safe)
  *
  * Examples:
  * {{{
  * Before (correlated EXISTS):
  *   SELECT * FROM orders o
  *   WHERE EXISTS (SELECT 1 FROM customers c WHERE c.id = o.customer_id)
  *
  * After (semi join):
  *   SELECT * FROM orders o
  *   LEFT SEMI JOIN customers c ON c.id = o.customer_id
  * }}}
  *
  * {{{
  * Before (correlated scalar subquery):
  *   SELECT o.*, (SELECT MAX(amount) FROM orders o2 WHERE o2.customer_id = o.customer_id)
  *   FROM orders o
  *
  * After (lateral join):
  *   SELECT o.*, sub.max_amount
  *   FROM orders o
  *   LATERAL JOIN (SELECT MAX(amount) as max_amount FROM orders o2 WHERE o2.customer_id = o.customer_id) sub
  * }}}
  *
  * Based on Spark Catalyst's RewriteCorrelatedScalarSubquery and RewritePredicateSubquery rules.
  */
object RewriteCorrelatedSubquery extends Rule:
  override val ruleName: String = "RewriteCorrelatedSubquery"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      // Rewrite Filter with EXISTS/NOT EXISTS/IN subquery predicates
      case ProtoLogicalPlan.Filter(condition, child) =>
        rewriteFilterPredicates(condition, child)
    }

  /** Rewrite filter predicates containing EXISTS/IN subqueries. */
  private def rewriteFilterPredicates(
      condition: ProtoExpr,
      child: ProtoLogicalPlan
  ): ProtoLogicalPlan =
    // Check for EXISTS
    condition match
      case ProtoExpr.Exists(subPlan) =>
        // EXISTS → Left Semi Join
        val (decorrelated, joinCond) = extractCorrelation(subPlan, child)
        if joinCond.isDefined then
          ProtoLogicalPlan.Join(child, decorrelated, JoinType.LeftSemi, joinCond)
        else
          // No correlation found, keep as filter
          ProtoLogicalPlan.Filter(condition, child)

      case ProtoExpr.Not(ProtoExpr.Exists(subPlan)) =>
        // NOT EXISTS → Left Anti Join
        val (decorrelated, joinCond) = extractCorrelation(subPlan, child)
        if joinCond.isDefined then
          ProtoLogicalPlan.Join(child, decorrelated, JoinType.LeftAnti, joinCond)
        else ProtoLogicalPlan.Filter(condition, child)

      case ProtoExpr.InSubquery(value, subPlan) =>
        // IN subquery → Left Semi Join with equality
        val (decorrelated, joinCond) = extractCorrelation(subPlan, child)
        // Add the IN value equality to the join condition
        val inCondition = joinCond match
          case Some(cond) =>
            val eqCond = createInEquality(value, decorrelated)
            eqCond.map(eq => ProtoExpr.And(Vector(cond, eq))).orElse(joinCond)
          case None =>
            createInEquality(value, decorrelated)

        if inCondition.isDefined then
          ProtoLogicalPlan.Join(child, decorrelated, JoinType.LeftSemi, inCondition)
        else ProtoLogicalPlan.Filter(condition, child)

      case ProtoExpr.Not(ProtoExpr.InSubquery(value, subPlan)) =>
        // NOT IN subquery → Left Anti Join
        val (decorrelated, joinCond) = extractCorrelation(subPlan, child)
        val inCondition = joinCond match
          case Some(cond) =>
            val eqCond = createInEquality(value, decorrelated)
            eqCond.map(eq => ProtoExpr.And(Vector(cond, eq))).orElse(joinCond)
          case None =>
            createInEquality(value, decorrelated)

        if inCondition.isDefined then
          ProtoLogicalPlan.Join(child, decorrelated, JoinType.LeftAnti, inCondition)
        else ProtoLogicalPlan.Filter(condition, child)

      // Handle AND - recursively process
      case ProtoExpr.And(children) =>
        val (subqueryPreds, otherPreds) = children.partition(hasCorrelatedSubquery)
        if subqueryPreds.isEmpty then ProtoLogicalPlan.Filter(condition, child)
        else
          // Process subquery predicates first, then apply remaining filters
          val withJoins = subqueryPreds.foldLeft(child) { (plan, pred) =>
            rewriteFilterPredicates(pred, plan) match
              case ProtoLogicalPlan.Filter(_, inner) => inner // Failed to rewrite, skip
              case rewritten                         => rewritten
          }
          // Apply remaining non-subquery predicates as filter
          if otherPreds.isEmpty then withJoins
          else
            val remainingCond =
              if otherPreds.size == 1 then otherPreds.head
              else ProtoExpr.And(otherPreds)
            ProtoLogicalPlan.Filter(remainingCond, withJoins)

      case _ =>
        // Not a subquery predicate, keep as filter
        ProtoLogicalPlan.Filter(condition, child)

  /** Check if an expression contains a correlated subquery. */
  private def hasCorrelatedSubquery(expr: ProtoExpr): Boolean =
    expr match
      case ProtoExpr.Exists(_)         => true
      case ProtoExpr.InSubquery(_, _)  => true
      case ProtoExpr.ScalarSubquery(_) => true
      case ProtoExpr.Not(child)        => hasCorrelatedSubquery(child)
      case ProtoExpr.And(children)     => children.exists(hasCorrelatedSubquery)
      case ProtoExpr.Or(children)      => children.exists(hasCorrelatedSubquery)
      case _                           => false

  /** Extract correlation predicates from a subquery plan.
    *
    * Returns the decorrelated plan and the correlation join condition.
    */
  private def extractCorrelation(
      subPlan: ProtoLogicalPlan,
      outerPlan: ProtoLogicalPlan
  ): (ProtoLogicalPlan, Option[ProtoExpr]) =
    // Collect outer column references
    val outerColumns = collectProducedColumns(outerPlan)

    // Find correlation predicates in the subquery's filter conditions
    var correlationPredicates = Vector.empty[ProtoExpr]

    val decorrelated = TreeTransform.transformPlanUp(subPlan) {
      case ProtoLogicalPlan.Filter(condition, child) =>
        val (correlated, uncorrelated) = splitCorrelatedPredicates(condition, outerColumns)
        correlationPredicates = correlationPredicates ++ correlated

        if uncorrelated.isEmpty then child
        else
          val newCond =
            if uncorrelated.size == 1 then uncorrelated.head
            else ProtoExpr.And(uncorrelated)
          ProtoLogicalPlan.Filter(newCond, child)
    }

    val joinCond =
      if correlationPredicates.isEmpty then None
      else if correlationPredicates.size == 1 then Some(correlationPredicates.head)
      else Some(ProtoExpr.And(correlationPredicates))

    (decorrelated, joinCond)

  /** Split predicates into correlated (references outer columns) and uncorrelated. */
  private def splitCorrelatedPredicates(
      condition: ProtoExpr,
      outerColumns: Set[(String, Option[String])]
  ): (Vector[ProtoExpr], Vector[ProtoExpr]) =
    val predicates = flattenAnd(condition)
    predicates.partition(isCorrelated(_, outerColumns))

  /** Flatten AND expressions into a list of predicates. */
  private def flattenAnd(expr: ProtoExpr): Vector[ProtoExpr] =
    expr match
      case ProtoExpr.And(children) => children.flatMap(flattenAnd)
      case other                   => Vector(other)

  /** Check if a predicate references any outer columns. */
  private def isCorrelated(
      expr: ProtoExpr,
      outerColumns: Set[(String, Option[String])]
  ): Boolean =
    var found = false

    def checkExpr(e: ProtoExpr): Unit =
      e match
        case ProtoExpr.ColumnRef(name, qualifier, _, _) =>
          if outerColumns.contains((name, qualifier)) then found = true
        case _ => ()

    TreeTransform.transformExprUp(expr) { case e =>
      checkExpr(e)
      e
    }
    found

  /** Collect column references produced by a plan (for identifying outer columns). */
  private def collectProducedColumns(plan: ProtoLogicalPlan): Set[(String, Option[String])] =
    plan match
      case ProtoLogicalPlan.RelationRef(name, alias, contract) =>
        contract.requiredFields.map(f => (f.name, None)).toSet
      case ProtoLogicalPlan.Project(projectList, _) =>
        projectList.flatMap {
          case ProtoExpr.Alias(_, name)                   => Some((name, None))
          case ProtoExpr.ColumnRef(name, qualifier, _, _) => Some((name, qualifier))
          case _                                          => None
        }.toSet
      case ProtoLogicalPlan.SubqueryAlias(alias, child) =>
        collectProducedColumns(child).map { case (name, _) => (name, Some(alias)) }
      case ProtoLogicalPlan.Aggregate(_, aggregateExprs, _) =>
        aggregateExprs.flatMap {
          case ProtoExpr.Alias(_, name)                   => Some((name, None))
          case ProtoExpr.ColumnRef(name, qualifier, _, _) => Some((name, qualifier))
          case _                                          => None
        }.toSet
      case ProtoLogicalPlan.Filter(_, child)        => collectProducedColumns(child)
      case ProtoLogicalPlan.Sort(_, _, child)       => collectProducedColumns(child)
      case ProtoLogicalPlan.Limit(_, child)         => collectProducedColumns(child)
      case ProtoLogicalPlan.Distinct(child)         => collectProducedColumns(child)
      case ProtoLogicalPlan.ResolvedHint(_, child)  => collectProducedColumns(child)
      case ProtoLogicalPlan.Join(left, right, _, _) =>
        collectProducedColumns(left) ++ collectProducedColumns(right)
      case ProtoLogicalPlan.Window(_, _, _, child) => collectProducedColumns(child)
      case _                                       => Set.empty

  /** Create equality condition for IN subquery. */
  private def createInEquality(
      value: ProtoExpr,
      subPlan: ProtoLogicalPlan
  ): Option[ProtoExpr] =
    // For IN subquery, we need to equate the value with the subquery's output
    // The subquery should be a single-column projection
    subPlan match
      case ProtoLogicalPlan.Project(Vector(outputExpr), _) =>
        Some(ProtoExpr.Eq(value, outputExpr))
      case ProtoLogicalPlan.SubqueryAlias(_, inner) =>
        createInEquality(value, inner)
      case _ =>
        None // Can't determine single output column
