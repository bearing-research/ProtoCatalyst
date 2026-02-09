package protocatalyst.plan

import java.io.Serializable

/** Cost estimate for a physical plan node.
  *
  * Used by PhysicalPlanner to compare alternative execution strategies (e.g., HashJoin vs
  * SortMergeJoin). Costs are additive — a plan's total cost is the sum of its nodes' costs.
  */
case class Cost(cpu: Double, io: Double, memory: Double) extends Serializable:
  def total(
      cpuWeight: Double = 1.0,
      ioWeight: Double = 1.0,
      memWeight: Double = 0.5
  ): Double =
    cpu * cpuWeight + io * ioWeight + memory * memWeight

  def +(other: Cost): Cost =
    Cost(cpu + other.cpu, io + other.io, memory + other.memory)

object Cost:
  val zero: Cost = Cost(0.0, 0.0, 0.0)

object CostEstimator:

  /** Estimate the local cost of a single physical plan node (not including children). */
  def estimate(plan: ProtoPhysicalPlan, childStats: Statistics): Cost =
    import ProtoPhysicalPlan._
    val rows = math.max(1L, childStats.rowCount).toDouble
    val bytes = math.max(1L, childStats.sizeInBytes).toDouble

    plan match
      // Leaf nodes — I/O cost proportional to data size
      case _: TableScan =>
        Cost(cpu = rows, io = bytes, memory = 0.0)
      case _: PhysicalValues =>
        Cost.zero

      // Unary — CPU proportional to rows
      case _: PhysicalFilter =>
        Cost(cpu = rows, io = 0.0, memory = 0.0)
      case p: PhysicalProject =>
        Cost(cpu = rows * p.projectList.size, io = 0.0, memory = 0.0)
      case _: PhysicalSort =>
        val logN = math.max(1.0, math.log(rows) / math.log(2.0))
        Cost(cpu = rows * logN, io = 0.0, memory = bytes)
      case _: PhysicalLimit =>
        Cost(cpu = 1.0, io = 0.0, memory = 0.0)
      case _: PhysicalDistinct =>
        Cost(cpu = rows, io = 0.0, memory = bytes)

      // Hash join — CPU = probe * key columns, memory = build side
      case hj: HashJoin =>
        val keyCols = math.max(1, hj.leftKeys.size).toDouble
        Cost(cpu = rows * keyCols, io = 0.0, memory = bytes / 2.0)
      case bhj: BroadcastHashJoin =>
        val keyCols = math.max(1, bhj.leftKeys.size).toDouble
        Cost(cpu = rows * keyCols, io = bytes / 2.0, memory = bytes / 2.0)

      // Sort-merge join — CPU = N log N for sorting
      case _: SortMergeJoin =>
        val logN = math.max(1.0, math.log(rows) / math.log(2.0))
        Cost(cpu = rows * logN, io = 0.0, memory = bytes)

      // Nested-loop join — CPU = left * right
      case _: NestedLoopJoin =>
        Cost(cpu = rows * rows, io = 0.0, memory = 0.0)

      // Hash aggregate — CPU = rows, memory = groups
      case _: HashAggregate =>
        Cost(cpu = rows, io = 0.0, memory = bytes / 10.0)
      case _: SortAggregate =>
        val logN = math.max(1.0, math.log(rows) / math.log(2.0))
        Cost(cpu = rows * logN, io = 0.0, memory = bytes)

      // Exchange — I/O cost for shuffle
      case _: Exchange =>
        Cost(cpu = rows, io = bytes, memory = 0.0)

      // Pass-through operators
      case _: PhysicalWindow =>
        val logN = math.max(1.0, math.log(rows) / math.log(2.0))
        Cost(cpu = rows * logN, io = 0.0, memory = bytes)
      case _: PhysicalUnion =>
        Cost.zero
      case _: PhysicalIntersect =>
        Cost(cpu = rows, io = 0.0, memory = bytes)
      case _: PhysicalExcept =>
        Cost(cpu = rows, io = 0.0, memory = bytes)
      case _: PhysicalWith =>
        Cost.zero
      case _: PhysicalPivot =>
        Cost(cpu = rows, io = 0.0, memory = bytes)
      case _: PhysicalUnpivot =>
        Cost(cpu = rows, io = 0.0, memory = 0.0)
      case _: PhysicalLateralJoin =>
        Cost(cpu = rows * rows, io = 0.0, memory = 0.0)
      case _: PhysicalGenerate =>
        Cost(cpu = rows, io = 0.0, memory = 0.0)
