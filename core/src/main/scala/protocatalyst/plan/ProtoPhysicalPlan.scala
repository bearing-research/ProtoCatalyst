package protocatalyst.plan

import java.io.Serializable

import protocatalyst.expr._
import protocatalyst.schema._

/** Build side selection for hash joins. */
enum BuildSide extends Serializable:
  case BuildLeft, BuildRight

/** Data partitioning strategy for Exchange nodes. */
enum Partitioning extends Serializable:
  case HashPartitioning(keys: Vector[ProtoExpr], numPartitions: Int)
  case SinglePartition
  case RoundRobinPartitioning(numPartitions: Int)

/** Physical plan nodes — execution-strategy-aware counterparts to ProtoLogicalPlan.
  *
  * Each logical plan node maps to one or more physical variants that encode *how* to execute the
  * operation (e.g., Join → HashJoin / SortMergeJoin / BroadcastHashJoin / NestedLoopJoin).
  *
  * Physical plans are produced by PhysicalPlanner from logical plans + statistics, and consumed by
  * backend executors. They are serializable via protobuf for cross-backend portability.
  */
enum ProtoPhysicalPlan extends Serializable:

  // ── Leaf nodes ──

  case TableScan(
      name: String,
      alias: Option[String],
      schema: ProtoSchema,
      stats: Statistics
  )

  case PhysicalValues(
      rows: Vector[Vector[ProtoExpr]],
      schema: ProtoSchema
  )

  // ── Unary (1:1 with logical) ──

  case PhysicalProject(
      projectList: Vector[ProtoExpr],
      child: ProtoPhysicalPlan
  )

  case PhysicalFilter(
      condition: ProtoExpr,
      child: ProtoPhysicalPlan
  )

  case PhysicalSort(
      order: Vector[SortOrder],
      child: ProtoPhysicalPlan
  )

  case PhysicalLimit(
      limit: Int,
      child: ProtoPhysicalPlan
  )

  case PhysicalDistinct(
      child: ProtoPhysicalPlan
  )

  // ── Join strategies ──

  /** Hash join: build a hash table on the build side, probe with the other side. Requires equi-join
    * keys (leftKeys[i] = rightKeys[i]).
    */
  case HashJoin(
      left: ProtoPhysicalPlan,
      right: ProtoPhysicalPlan,
      joinType: JoinType,
      leftKeys: Vector[ProtoExpr],
      rightKeys: Vector[ProtoExpr],
      condition: Option[ProtoExpr],
      buildSide: BuildSide
  )

  /** Sort-merge join: sort both sides by join keys, then merge. Requires equi-join keys.
    */
  case SortMergeJoin(
      left: ProtoPhysicalPlan,
      right: ProtoPhysicalPlan,
      joinType: JoinType,
      leftKeys: Vector[ProtoExpr],
      rightKeys: Vector[ProtoExpr],
      condition: Option[ProtoExpr]
  )

  /** Broadcast hash join: broadcast the small side to all partitions, then hash join. Requires
    * equi-join keys. Used when one side is below the broadcast threshold.
    */
  case BroadcastHashJoin(
      left: ProtoPhysicalPlan,
      right: ProtoPhysicalPlan,
      joinType: JoinType,
      leftKeys: Vector[ProtoExpr],
      rightKeys: Vector[ProtoExpr],
      condition: Option[ProtoExpr],
      buildSide: BuildSide
  )

  /** Nested-loop join: evaluate condition for every pair of rows. Fallback for non-equi joins and
    * cross joins.
    */
  case NestedLoopJoin(
      left: ProtoPhysicalPlan,
      right: ProtoPhysicalPlan,
      joinType: JoinType,
      condition: Option[ProtoExpr]
  )

  // ── Aggregate strategies ──

  case HashAggregate(
      groupingExprs: Vector[ProtoExpr],
      aggregateExprs: Vector[ProtoExpr],
      child: ProtoPhysicalPlan
  )

  case SortAggregate(
      groupingExprs: Vector[ProtoExpr],
      aggregateExprs: Vector[ProtoExpr],
      child: ProtoPhysicalPlan
  )

  // ── Data redistribution ──

  /** Exchange: repartition data according to a partitioning scheme. No-op in local execution;
    * meaningful for distributed backends.
    */
  case Exchange(
      partitioning: Partitioning,
      child: ProtoPhysicalPlan
  )

  // ── Pass-through (same structure as logical) ──

  case PhysicalWindow(
      windowExprs: Vector[ProtoExpr],
      partitionSpec: Vector[ProtoExpr],
      orderSpec: Vector[SortOrder],
      child: ProtoPhysicalPlan
  )

  case PhysicalUnion(
      children: Vector[ProtoPhysicalPlan],
      byName: Boolean,
      allowMissingColumns: Boolean
  )

  case PhysicalIntersect(
      left: ProtoPhysicalPlan,
      right: ProtoPhysicalPlan,
      isAll: Boolean
  )

  case PhysicalExcept(
      left: ProtoPhysicalPlan,
      right: ProtoPhysicalPlan,
      isAll: Boolean
  )

  case PhysicalWith(
      cteRelations: Vector[(String, ProtoPhysicalPlan)],
      recursive: Boolean,
      child: ProtoPhysicalPlan
  )

  case PhysicalPivot(
      groupingExprs: Vector[ProtoExpr],
      pivotColumn: ProtoExpr,
      pivotValues: Vector[ProtoExpr],
      aggregates: Vector[ProtoExpr],
      child: ProtoPhysicalPlan
  )

  case PhysicalUnpivot(
      valueColumnName: String,
      variableColumnName: String,
      columns: Vector[(ProtoExpr, Option[String])],
      includeNulls: Boolean,
      child: ProtoPhysicalPlan
  )

  case PhysicalLateralJoin(
      left: ProtoPhysicalPlan,
      lateral: ProtoPhysicalPlan,
      condition: Option[ProtoExpr]
  )

  case PhysicalGenerate(
      generator: ProtoExpr,
      generatorOutput: Vector[String],
      outer: Boolean,
      child: ProtoPhysicalPlan
  )
