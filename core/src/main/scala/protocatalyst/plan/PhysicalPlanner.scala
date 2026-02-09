package protocatalyst.plan

import java.io.Serializable

import protocatalyst.expr._
import protocatalyst.schema._
import protocatalyst.types._

/** Configuration for the physical planner. */
case class PlannerConfig(
    broadcastThreshold: Long = 10L * 1024 * 1024,
    preferHashJoin: Boolean = true,
    preferHashAggregate: Boolean = true
) extends Serializable

/** Converts ProtoLogicalPlan → ProtoPhysicalPlan.
  *
  * Takes a `statsProvider` function to look up relation statistics by name. This decouples the
  * planner from any specific catalog implementation — the executor's Catalog provides one
  * implementation, but Spark or other backends can supply their own.
  */
class PhysicalPlanner(
    statsProvider: String => Statistics,
    config: PlannerConfig = PlannerConfig()
):
  import ProtoLogicalPlan._
  import ProtoPhysicalPlan._

  /** Convert a logical plan to a physical plan. */
  def plan(logical: ProtoLogicalPlan): ProtoPhysicalPlan =
    planInternal(logical, activeHints = Vector.empty)

  // ── Internal planner with hint context ──

  private def planInternal(
      logical: ProtoLogicalPlan,
      activeHints: Vector[PlanHint]
  ): ProtoPhysicalPlan =
    logical match
      // Leaf nodes
      case RelationRef(name, alias, contract) =>
        val stats = statsProvider(name)
        val schema = schemaFromContract(contract)
        TableScan(name, alias, schema, stats)

      case Values(rows, schema) =>
        PhysicalValues(rows, schema)

      // Unary operators — 1:1 mapping
      case Project(projectList, child) =>
        PhysicalProject(projectList, planInternal(child, activeHints))

      case Filter(condition, child) =>
        PhysicalFilter(condition, planInternal(child, activeHints))

      case Sort(order, child) =>
        PhysicalSort(order, planInternal(child, activeHints))

      case Limit(n, child) =>
        PhysicalLimit(n, planInternal(child, activeHints))

      case Distinct(child) =>
        PhysicalDistinct(planInternal(child, activeHints))

      // Join — strategy selection based on keys, hints, and statistics
      case Join(left, right, joinType, condition) =>
        planJoin(left, right, joinType, condition, activeHints)

      // Aggregate — hash vs sort strategy
      case Aggregate(groupingExprs, aggregateExprs, child) =>
        planAggregate(groupingExprs, aggregateExprs, child, activeHints)

      // SubqueryAlias — consumed, not present in physical plan
      case SubqueryAlias(_, child) =>
        planInternal(child, activeHints)

      // ResolvedHint — consumed, affects subsequent join/aggregate selection
      case ResolvedHint(hints, child) =>
        planInternal(child, activeHints ++ hints)

      // Pass-through operators — same structure
      case Window(windowExprs, partitionSpec, orderSpec, child) =>
        PhysicalWindow(windowExprs, partitionSpec, orderSpec, planInternal(child, activeHints))

      case Union(children, byName, allowMissingColumns) =>
        PhysicalUnion(children.map(c => planInternal(c, activeHints)), byName, allowMissingColumns)

      case Intersect(left, right, isAll) =>
        PhysicalIntersect(
          planInternal(left, activeHints),
          planInternal(right, activeHints),
          isAll
        )

      case Except(left, right, isAll) =>
        PhysicalExcept(
          planInternal(left, activeHints),
          planInternal(right, activeHints),
          isAll
        )

      case With(cteRelations, recursive, child) =>
        PhysicalWith(
          cteRelations.map((name, plan) => (name, planInternal(plan, activeHints))),
          recursive,
          planInternal(child, activeHints)
        )

      case Pivot(groupingExprs, pivotColumn, pivotValues, aggregates, child) =>
        PhysicalPivot(
          groupingExprs,
          pivotColumn,
          pivotValues,
          aggregates,
          planInternal(child, activeHints)
        )

      case Unpivot(valueColumnName, variableColumnName, columns, includeNulls, child) =>
        PhysicalUnpivot(
          valueColumnName,
          variableColumnName,
          columns,
          includeNulls,
          planInternal(child, activeHints)
        )

      case LateralJoin(left, lateral, condition) =>
        PhysicalLateralJoin(
          planInternal(left, activeHints),
          planInternal(lateral, activeHints),
          condition
        )

      case Generate(generator, generatorOutput, outer, child) =>
        PhysicalGenerate(generator, generatorOutput, outer, planInternal(child, activeHints))

  // ── Join strategy selection ──

  private def planJoin(
      left: ProtoLogicalPlan,
      right: ProtoLogicalPlan,
      joinType: JoinType,
      condition: Option[ProtoExpr],
      hints: Vector[PlanHint]
  ): ProtoPhysicalPlan =
    val physLeft = planInternal(left, hints)
    val physRight = planInternal(right, hints)
    val leftStats = estimateStats(physLeft)
    val rightStats = estimateStats(physRight)

    val (leftKeys, rightKeys, residual) = condition match
      case Some(cond) => extractEquiJoinKeys(cond)
      case None       => (Vector.empty, Vector.empty, None)

    val hasEquiKeys = leftKeys.nonEmpty

    // Cross join — always nested loop, no keys needed
    if joinType == JoinType.Cross then
      return NestedLoopJoin(physLeft, physRight, joinType, condition)

    // No equi-keys — must use nested loop
    if !hasEquiKeys then return NestedLoopJoin(physLeft, physRight, joinType, condition)

    // Check hints for forced strategy
    val hintStrategy = selectStrategyFromHints(hints)

    hintStrategy match
      case Some("BROADCAST") =>
        val buildSide = selectBuildSide(leftStats, rightStats)
        BroadcastHashJoin(physLeft, physRight, joinType, leftKeys, rightKeys, residual, buildSide)

      case Some("SHUFFLE_MERGE") =>
        SortMergeJoin(physLeft, physRight, joinType, leftKeys, rightKeys, residual)

      case Some("SHUFFLE_HASH") =>
        val buildSide = selectBuildSide(leftStats, rightStats)
        HashJoin(physLeft, physRight, joinType, leftKeys, rightKeys, residual, buildSide)

      case _ =>
        // Cost-based selection
        val smallEnoughForBroadcast =
          leftStats.sizeInBytes >= 0 && leftStats.sizeInBytes <= config.broadcastThreshold ||
            rightStats.sizeInBytes >= 0 && rightStats.sizeInBytes <= config.broadcastThreshold

        if smallEnoughForBroadcast then
          val buildSide = selectBuildSide(leftStats, rightStats)
          BroadcastHashJoin(physLeft, physRight, joinType, leftKeys, rightKeys, residual, buildSide)
        else if config.preferHashJoin then
          val buildSide = selectBuildSide(leftStats, rightStats)
          HashJoin(physLeft, physRight, joinType, leftKeys, rightKeys, residual, buildSide)
        else SortMergeJoin(physLeft, physRight, joinType, leftKeys, rightKeys, residual)

  // ── Aggregate strategy selection ──

  private def planAggregate(
      groupingExprs: Vector[ProtoExpr],
      aggregateExprs: Vector[ProtoExpr],
      child: ProtoLogicalPlan,
      hints: Vector[PlanHint]
  ): ProtoPhysicalPlan =
    val physChild = planInternal(child, hints)
    if config.preferHashAggregate then HashAggregate(groupingExprs, aggregateExprs, physChild)
    else SortAggregate(groupingExprs, aggregateExprs, physChild)

  // ── Equi-join key extraction ──

  /** Extract equi-join keys from a join condition.
    *
    * Splits `a.id = b.id AND a.x = b.y AND a.z > 5` into:
    *   - leftKeys = [a.id, a.x]
    *   - rightKeys = [b.id, b.y]
    *   - residual = Some(a.z > 5)
    *
    * An expression is an equi-key if it's `Eq(ColumnRef, ColumnRef)` with different qualifiers (or
    * at least two different column names). Since we don't have table context at this point, we use
    * the convention that the first operand is from the left side and the second from the right side
    * when both are column references.
    */
  private[plan] def extractEquiJoinKeys(
      condition: ProtoExpr
  ): (Vector[ProtoExpr], Vector[ProtoExpr], Option[ProtoExpr]) =
    val conjuncts = flattenAnd(condition)
    val (equiPairs, nonEqui) = conjuncts.partition(isEquiJoinPredicate)

    val leftKeys = equiPairs.map:
      case ProtoExpr.Eq(left, _) => left
      case other                 => other // shouldn't happen

    val rightKeys = equiPairs.map:
      case ProtoExpr.Eq(_, right) => right
      case other                  => other // shouldn't happen

    val residual = nonEqui match
      case Vector()  => None
      case Vector(e) => Some(e)
      case multiple  => Some(ProtoExpr.And(multiple))

    (leftKeys, rightKeys, residual)

  /** Flatten nested AND expressions into a flat list of conjuncts. */
  private def flattenAnd(expr: ProtoExpr): Vector[ProtoExpr] =
    expr match
      case ProtoExpr.And(children) => children.flatMap(flattenAnd)
      case other                   => Vector(other)

  /** Check if an expression is an equi-join predicate (Eq between two column references). */
  private def isEquiJoinPredicate(expr: ProtoExpr): Boolean =
    expr match
      case ProtoExpr.Eq(l: ProtoExpr.ColumnRef, r: ProtoExpr.ColumnRef) =>
        // Must be different columns (either different names or different qualifiers)
        l.name != r.name || l.qualifier != r.qualifier
      case _ => false

  // ── Hint processing ──

  /** Extract join strategy hint from active hints. */
  private def selectStrategyFromHints(hints: Vector[PlanHint]): Option[String] =
    val hintNames = hints.map(_.name.toUpperCase)
    if hintNames.contains("BROADCAST") || hintNames.contains("BROADCASTJOIN") || hintNames.contains(
        "MAPJOIN"
      )
    then Some("BROADCAST")
    else if hintNames.contains("SHUFFLE_MERGE") || hintNames.contains("MERGE") || hintNames
        .contains("MERGEJOIN")
    then Some("SHUFFLE_MERGE")
    else if hintNames.contains("SHUFFLE_HASH") || hintNames.contains("HASHJOIN") then
      Some("SHUFFLE_HASH")
    else None

  // ── Build side selection ──

  /** Select which side to build the hash table from — smaller side by sizeInBytes. */
  private def selectBuildSide(
      leftStats: Statistics,
      rightStats: Statistics
  ): BuildSide =
    // If we don't know either side's size, default to building right
    if leftStats.sizeInBytes < 0 && rightStats.sizeInBytes < 0 then BuildSide.BuildRight
    else if leftStats.sizeInBytes < 0 then BuildSide.BuildRight
    else if rightStats.sizeInBytes < 0 then BuildSide.BuildLeft
    else if leftStats.sizeInBytes <= rightStats.sizeInBytes then BuildSide.BuildLeft
    else BuildSide.BuildRight

  // ── Statistics estimation ──

  /** Estimate the output statistics of a physical plan node. */
  private[plan] def estimateStats(plan: ProtoPhysicalPlan): Statistics =
    plan match
      case TableScan(_, _, _, stats) => stats
      case PhysicalValues(rows, _)   =>
        Statistics(rowCount = rows.size.toLong, sizeInBytes = rows.size * 100L)

      case PhysicalProject(projectList, child) =>
        val childStats = estimateStats(child)
        Statistics.afterProject(childStats, projectList.size, math.max(1, projectList.size))

      case PhysicalFilter(_, child) =>
        Statistics.afterFilter(estimateStats(child))

      case PhysicalSort(_, child)  => estimateStats(child)
      case PhysicalLimit(n, child) =>
        val childStats = estimateStats(child)
        if childStats.rowCount < 0 then childStats
        else
          val rows = math.min(n.toLong, childStats.rowCount)
          val bytes =
            if childStats.rowCount > 0 then
              (childStats.sizeInBytes.toDouble * rows / childStats.rowCount).toLong
            else 0L
          Statistics(rowCount = rows, sizeInBytes = bytes)

      case PhysicalDistinct(child) =>
        val childStats = estimateStats(child)
        if childStats.rowCount < 0 then childStats
        else
          Statistics(
            rowCount = math.max(1, childStats.rowCount / 2),
            sizeInBytes = childStats.sizeInBytes / 2
          )

      case HashJoin(left, right, joinType, _, _, _, _) =>
        Statistics.afterJoin(estimateStats(left), estimateStats(right), joinType)
      case SortMergeJoin(left, right, joinType, _, _, _) =>
        Statistics.afterJoin(estimateStats(left), estimateStats(right), joinType)
      case BroadcastHashJoin(left, right, joinType, _, _, _, _) =>
        Statistics.afterJoin(estimateStats(left), estimateStats(right), joinType)
      case NestedLoopJoin(left, right, joinType, _) =>
        Statistics.afterJoin(estimateStats(left), estimateStats(right), joinType)

      case HashAggregate(groupingExprs, _, child) =>
        Statistics.afterAggregate(estimateStats(child), groupingExprs.size)
      case SortAggregate(groupingExprs, _, child) =>
        Statistics.afterAggregate(estimateStats(child), groupingExprs.size)

      case Exchange(_, child) => estimateStats(child)

      case PhysicalWindow(_, _, _, child) => estimateStats(child)
      case PhysicalUnion(children, _, _)  =>
        val stats = children.map(estimateStats)
        val totalRows = stats.map(_.rowCount).sum
        val totalBytes = stats.map(_.sizeInBytes).sum
        Statistics(rowCount = totalRows, sizeInBytes = totalBytes)

      case PhysicalIntersect(left, _, _)      => estimateStats(left)
      case PhysicalExcept(left, _, _)         => estimateStats(left)
      case PhysicalWith(_, _, child)          => estimateStats(child)
      case PhysicalPivot(_, _, _, _, child)   => estimateStats(child)
      case PhysicalUnpivot(_, _, _, _, child) => estimateStats(child)
      case PhysicalLateralJoin(left, _, _)    => estimateStats(left)
      case PhysicalGenerate(_, _, _, child)   =>
        val childStats = estimateStats(child)
        if childStats.rowCount < 0 then childStats
        else
          Statistics(rowCount = childStats.rowCount * 2, sizeInBytes = childStats.sizeInBytes * 2)

  // ── Helpers ──

  /** Derive a ProtoSchema from a SchemaContract's required fields. */
  private def schemaFromContract(contract: SchemaContract): ProtoSchema =
    ProtoSchema(
      contract.requiredFields.map(f => ProtoStructField(f.name, f.expectedType, f.expectedNullable))
    )
