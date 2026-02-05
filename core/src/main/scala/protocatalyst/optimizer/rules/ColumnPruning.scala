package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan._

/** Removes unused columns from intermediate operators.
  *
  * This rule eliminates columns that are not needed by parent operators, reducing memory usage and
  * processing time. It works by propagating column requirements from top to bottom.
  *
  * Examples:
  * {{{
  * Before:
  *   Project [a]
  *     Project [a, b, c, d]
  *       Scan [a, b, c, d, e, f]
  *
  * After:
  *   Project [a]
  *     Project [a]
  *       Scan [a]  (if scan supports column pruning)
  * }}}
  *
  * {{{
  * Before:
  *   Project [a + b AS x]
  *     Filter [c > 5]
  *       Scan [a, b, c, d, e]
  *
  * After:
  *   Project [a + b AS x]
  *     Filter [c > 5]
  *       Scan [a, b, c]  (if scan supports column pruning)
  * }}}
  *
  * Based on Spark Catalyst's ColumnPruning rule (Optimizer.scala:1051-1204).
  *
  * Note: This implementation focuses on eliminating unused columns in Project nodes. Full column
  * pruning down to scan level requires schema information that may not be available at compile
  * time.
  */
object ColumnPruning extends Rule:
  override val ruleName: String = "ColumnPruning"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      // Prune Project above Project - keep only columns needed by outer
      case ProtoLogicalPlan.Project(outerList, ProtoLogicalPlan.Project(innerList, child)) =>
        val requiredColumns = outerList.flatMap(getReferencedColumns).toSet
        val prunedInnerList = innerList.filter { expr =>
          getProducedColumn(expr).exists(requiredColumns.contains)
        }
        // If we pruned any columns, create new projects
        if prunedInnerList.size < innerList.size then
          ProtoLogicalPlan.Project(outerList, ProtoLogicalPlan.Project(prunedInnerList, child))
        else ProtoLogicalPlan.Project(outerList, ProtoLogicalPlan.Project(innerList, child))

      // Prune Project above Aggregate - eliminate unused aggregate results
      case ProtoLogicalPlan.Project(
            projectList,
            agg @ ProtoLogicalPlan.Aggregate(groupingExprs, aggregateExprs, child)
          ) =>
        val requiredColumns = projectList.flatMap(getReferencedColumns).toSet
        val prunedAggregates = aggregateExprs.filter { expr =>
          getProducedColumn(expr).exists(requiredColumns.contains) ||
          groupingExprs.contains(expr)
        }
        if prunedAggregates.size < aggregateExprs.size then
          ProtoLogicalPlan.Project(
            projectList,
            ProtoLogicalPlan.Aggregate(groupingExprs, prunedAggregates, child)
          )
        else ProtoLogicalPlan.Project(projectList, agg)

      // Remove unnecessary columns from Project above Join
      case ProtoLogicalPlan.Project(
            projectList,
            join @ ProtoLogicalPlan.Join(left, right, joinType, condition)
          ) =>
        val requiredColumns = projectList.flatMap(getReferencedColumns).toSet ++
          condition.toVector.flatMap(getReferencedColumns).toSet

        val leftRequired = requiredColumns.intersect(collectProducedColumns(left))
        val rightRequired = requiredColumns.intersect(collectProducedColumns(right))

        // Insert projections if we can prune columns
        val newLeft =
          if leftRequired.nonEmpty && leftRequired.size < collectProducedColumns(left).size then
            createProjection(left, leftRequired)
          else left

        val newRight =
          if rightRequired.nonEmpty && rightRequired.size < collectProducedColumns(right).size then
            createProjection(right, rightRequired)
          else right

        if (newLeft ne left) || (newRight ne right) then
          ProtoLogicalPlan.Project(
            projectList,
            ProtoLogicalPlan.Join(newLeft, newRight, joinType, condition)
          )
        else ProtoLogicalPlan.Project(projectList, join)

      // Prune columns in Window over Project (must be before general Project case)
      case ProtoLogicalPlan.Project(
            projectList,
            window @ ProtoLogicalPlan.Window(windowExprs, partitionSpec, orderSpec, child)
          ) =>
        val requiredColumns = projectList.flatMap(getReferencedColumns).toSet
        val windowRefs = windowExprs.flatMap(getReferencedColumns) ++
          partitionSpec.flatMap(getReferencedColumns) ++
          orderSpec.flatMap(so => getReferencedColumns(so.child))

        // All columns needed by window and project
        val allRequired = requiredColumns ++ windowRefs.toSet
        val childColumns = collectProducedColumns(child)

        if allRequired.size < childColumns.size && allRequired.nonEmpty then
          val newChild = createProjection(child, allRequired)
          ProtoLogicalPlan.Project(
            projectList,
            ProtoLogicalPlan.Window(windowExprs, partitionSpec, orderSpec, newChild)
          )
        else ProtoLogicalPlan.Project(projectList, window)

      // Remove Project that produces same columns as child (identity projection)
      case project @ ProtoLogicalPlan.Project(projectList, child) =>
        val childColumns = collectProducedColumns(child)
        // Check if this is an identity projection (pass-through)
        if isIdentityProjection(projectList, childColumns) then child
        else project
    }

  /** Get column references from an expression. */
  private def getReferencedColumns(expr: ProtoExpr): Set[(String, Option[String])] =
    var refs = Set.empty[(String, Option[String])]
    TreeTransform.transformExprUp(expr) { case ref @ ProtoExpr.ColumnRef(name, qualifier, _, _) =>
      refs = refs + ((name, qualifier))
      ref
    }
    refs

  /** Get the column name produced by an expression (if it produces one). */
  private def getProducedColumn(expr: ProtoExpr): Option[(String, Option[String])] =
    expr match
      case ProtoExpr.Alias(_, name)                   => Some((name, None))
      case ProtoExpr.ColumnRef(name, qualifier, _, _) => Some((name, qualifier))
      case _                                          => None

  /** Collect all columns produced by a plan. */
  private def collectProducedColumns(plan: ProtoLogicalPlan): Set[(String, Option[String])] =
    plan match
      case ProtoLogicalPlan.Project(projectList, _) =>
        projectList.flatMap(getProducedColumn).toSet

      case ProtoLogicalPlan.Aggregate(_, aggregateExprs, _) =>
        aggregateExprs.flatMap(getProducedColumn).toSet

      case ProtoLogicalPlan.SubqueryAlias(alias, child) =>
        collectProducedColumns(child).map { case (name, _) => (name, Some(alias)) }

      case ProtoLogicalPlan.Filter(_, child)       => collectProducedColumns(child)
      case ProtoLogicalPlan.Sort(_, _, child)      => collectProducedColumns(child)
      case ProtoLogicalPlan.Limit(_, child)        => collectProducedColumns(child)
      case ProtoLogicalPlan.Distinct(child)        => collectProducedColumns(child)
      case ProtoLogicalPlan.ResolvedHint(_, child) => collectProducedColumns(child)

      case ProtoLogicalPlan.Join(left, right, _, _) =>
        collectProducedColumns(left) ++ collectProducedColumns(right)

      case ProtoLogicalPlan.Window(windowExprs, _, _, child) =>
        collectProducedColumns(child) ++ windowExprs.flatMap(getProducedColumn).toSet

      case ProtoLogicalPlan.RelationRef(_, _, _) =>
        // For relations, we don't have schema info at this level
        Set.empty

      case _ => Set.empty

  /** Create a projection for the required columns. */
  private def createProjection(
      plan: ProtoLogicalPlan,
      requiredColumns: Set[(String, Option[String])]
  ): ProtoLogicalPlan =
    plan match
      case ProtoLogicalPlan.Project(projectList, child) =>
        val prunedList = projectList.filter { expr =>
          getProducedColumn(expr).exists(requiredColumns.contains)
        }
        if prunedList.isEmpty then plan // Don't create empty projection
        else if prunedList.size == projectList.size then plan
        else ProtoLogicalPlan.Project(prunedList, child)

      case _ =>
        // Create a new projection with only required columns
        val projectList = requiredColumns.toVector.map { case (name, qualifier) =>
          ProtoExpr.ColumnRef(name, qualifier, protocatalyst.types.ProtoType.NullType, true)
        }
        if projectList.isEmpty then plan
        else ProtoLogicalPlan.Project(projectList, plan)

  /** Check if a projection is an identity (pass-through) projection. */
  private def isIdentityProjection(
      projectList: Vector[ProtoExpr],
      childColumns: Set[(String, Option[String])]
  ): Boolean =
    if projectList.size != childColumns.size then false
    else
      projectList.forall {
        case ProtoExpr.ColumnRef(name, qualifier, _, _) =>
          childColumns.contains((name, qualifier))
        case _ => false
      }
