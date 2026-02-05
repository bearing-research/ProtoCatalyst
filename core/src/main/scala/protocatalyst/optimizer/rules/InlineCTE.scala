package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan._

/** Inlines Common Table Expressions (CTEs) when beneficial.
  *
  * This rule inlines CTEs that are referenced only once (or are small) to reduce plan complexity
  * and enable further optimizations. CTEs that are referenced multiple times are kept to avoid
  * redundant computation.
  *
  * Cases handled:
  *   - CTE referenced exactly once: inline the CTE plan
  *   - CTE not referenced at all: remove the CTE definition
  *   - All CTEs inlined/removed: remove the With wrapper entirely
  *
  * Cases NOT inlined:
  *   - Recursive CTEs: cannot be inlined (would cause infinite expansion)
  *   - CTEs referenced multiple times: kept to enable materialization
  *
  * Examples:
  * {{{
  * Before:
  *   With [t AS (SELECT * FROM users WHERE active)]
  *     Select * FROM t WHERE age > 25
  *
  * After (CTE used once):
  *   Select * FROM (SELECT * FROM users WHERE active) WHERE age > 25
  * }}}
  *
  * {{{
  * Before:
  *   With [t AS (SELECT * FROM users)]
  *     Select * FROM t JOIN t
  *
  * After (CTE used twice - not inlined):
  *   With [t AS (SELECT * FROM users)]
  *     Select * FROM t JOIN t
  * }}}
  *
  * Based on Spark Catalyst's InlineCTE rule (Optimizer.scala:200-280).
  */
object InlineCTE extends Rule:
  override val ruleName: String = "InlineCTE"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      case w @ ProtoLogicalPlan.With(cteRelations, recursive, child) =>
        // Don't inline recursive CTEs
        if recursive then w
        else inlineCTEs(cteRelations, child)
    }

  /** Inline CTEs that are referenced only once. */
  private def inlineCTEs(
      cteRelations: Vector[(String, ProtoLogicalPlan)],
      child: ProtoLogicalPlan
  ): ProtoLogicalPlan =
    // Count references to each CTE
    val cteNames = cteRelations.map(_._1).toSet
    val refCounts = countCTEReferences(child, cteNames)

    // Also count references within CTE definitions themselves (for chained CTEs)
    val internalRefCounts = cteRelations.foldLeft(Map.empty[String, Int]) { case (counts, (_, p)) =>
      val innerCounts = countCTEReferences(p, cteNames)
      innerCounts.foldLeft(counts) { case (acc, (name, count)) =>
        acc.updated(name, acc.getOrElse(name, 0) + count)
      }
    }

    // Merge counts
    val totalRefCounts = cteNames.map { name =>
      name -> (refCounts.getOrElse(name, 0) + internalRefCounts.getOrElse(name, 0))
    }.toMap

    // Find CTEs to inline (referenced exactly once)
    val cteMap = cteRelations.toMap
    val toInline = totalRefCounts.filter(_._2 == 1).keySet

    // Find CTEs to remove (not referenced at all)
    val toRemove = totalRefCounts.filter(_._2 == 0).keySet

    // Inline CTEs in the child plan
    val inlinedChild = toInline.foldLeft(child) { (currentPlan, cteName) =>
      cteMap.get(cteName) match
        case Some(ctePlan) => inlineCTE(currentPlan, cteName, ctePlan)
        case None          => currentPlan
    }

    // Keep only CTEs that weren't inlined or removed
    val remainingCTEs = cteRelations.filterNot { case (name, _) =>
      toInline.contains(name) || toRemove.contains(name)
    }

    // If no CTEs remain, return just the child
    if remainingCTEs.isEmpty then inlinedChild
    else ProtoLogicalPlan.With(remainingCTEs, recursive = false, inlinedChild)

  /** Count references to CTEs in a plan. */
  private def countCTEReferences(
      plan: ProtoLogicalPlan,
      cteNames: Set[String]
  ): Map[String, Int] =
    var counts = Map.empty[String, Int]

    def countInPlan(p: ProtoLogicalPlan): Unit =
      p match
        case ProtoLogicalPlan.RelationRef(name, _, _) if cteNames.contains(name) =>
          counts = counts.updated(name, counts.getOrElse(name, 0) + 1)
        case ProtoLogicalPlan.SubqueryAlias(alias, inner) if cteNames.contains(alias) =>
          // Don't count the CTE definition itself as a reference
          // This can happen when the SubqueryAlias wraps a CTE definition
          countInPlan(inner)
        case _ =>
          ()

      // Recursively count in children
      p match
        case ProtoLogicalPlan.Project(_, child)          => countInPlan(child)
        case ProtoLogicalPlan.Filter(_, child)           => countInPlan(child)
        case ProtoLogicalPlan.Aggregate(_, _, child)     => countInPlan(child)
        case ProtoLogicalPlan.Sort(_, _, child)          => countInPlan(child)
        case ProtoLogicalPlan.Limit(_, child)            => countInPlan(child)
        case ProtoLogicalPlan.Distinct(child)            => countInPlan(child)
        case ProtoLogicalPlan.SubqueryAlias(_, child)    => countInPlan(child)
        case ProtoLogicalPlan.ResolvedHint(_, child)     => countInPlan(child)
        case ProtoLogicalPlan.Window(_, _, _, child)     => countInPlan(child)
        case ProtoLogicalPlan.Pivot(_, _, _, _, child)   => countInPlan(child)
        case ProtoLogicalPlan.Unpivot(_, _, _, _, child) => countInPlan(child)
        case ProtoLogicalPlan.Generate(_, _, _, child)   => countInPlan(child)
        case ProtoLogicalPlan.Join(left, right, _, _)    =>
          countInPlan(left)
          countInPlan(right)
        case ProtoLogicalPlan.LateralJoin(left, lateral, _) =>
          countInPlan(left)
          countInPlan(lateral)
        case ProtoLogicalPlan.Union(children, _, _)     => children.foreach(countInPlan)
        case ProtoLogicalPlan.Intersect(left, right, _) =>
          countInPlan(left)
          countInPlan(right)
        case ProtoLogicalPlan.Except(left, right, _) =>
          countInPlan(left)
          countInPlan(right)
        case ProtoLogicalPlan.With(ctes, _, child) =>
          // Don't count into nested With CTEs, but do count in the child
          countInPlan(child)
        case ProtoLogicalPlan.RelationRef(_, _, _) => ()
        case ProtoLogicalPlan.Values(_, _)         => ()

      // Also count references in subquery expressions
      countInExprs(p)

    def countInExprs(p: ProtoLogicalPlan): Unit =
      def countInExpr(e: ProtoExpr): Unit =
        e match
          case ProtoExpr.ScalarSubquery(subPlan) => countInPlan(subPlan)
          case ProtoExpr.Exists(subPlan)         => countInPlan(subPlan)
          case ProtoExpr.InSubquery(_, subPlan)  => countInPlan(subPlan)
          case _                                 => ()

      p match
        case ProtoLogicalPlan.Project(exprs, _)          => exprs.foreach(countInExpr)
        case ProtoLogicalPlan.Filter(cond, _)            => countInExpr(cond)
        case ProtoLogicalPlan.Aggregate(groups, aggs, _) =>
          groups.foreach(countInExpr)
          aggs.foreach(countInExpr)
        case ProtoLogicalPlan.Join(_, _, _, Some(cond)) => countInExpr(cond)
        case _                                          => ()

    countInPlan(plan)
    counts

  /** Inline a single CTE in a plan by replacing RelationRef with the CTE plan. */
  private def inlineCTE(
      plan: ProtoLogicalPlan,
      cteName: String,
      ctePlan: ProtoLogicalPlan
  ): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      // Replace RelationRef that matches the CTE name
      case ProtoLogicalPlan.RelationRef(name, alias, _) if name == cteName =>
        // If there's an alias, wrap the CTE plan with SubqueryAlias
        alias match
          case Some(a) => ProtoLogicalPlan.SubqueryAlias(a, ctePlan)
          case None    => ctePlan

      // Also inline in subquery expressions
      case p: ProtoLogicalPlan =>
        inlineCTEInExprs(p, cteName, ctePlan)
    }

  /** Inline CTE references in subquery expressions. */
  private def inlineCTEInExprs(
      plan: ProtoLogicalPlan,
      cteName: String,
      ctePlan: ProtoLogicalPlan
  ): ProtoLogicalPlan =
    TreeTransform.transformPlanExprs(plan) { expr =>
      TreeTransform.transformExprUp(expr) {
        case ProtoExpr.ScalarSubquery(subPlan) =>
          ProtoExpr.ScalarSubquery(inlineCTE(subPlan, cteName, ctePlan))
        case ProtoExpr.Exists(subPlan) =>
          ProtoExpr.Exists(inlineCTE(subPlan, cteName, ctePlan))
        case ProtoExpr.InSubquery(value, subPlan) =>
          ProtoExpr.InSubquery(value, inlineCTE(subPlan, cteName, ctePlan))
      }
    }
