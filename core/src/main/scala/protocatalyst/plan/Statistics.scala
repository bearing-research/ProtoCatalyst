package protocatalyst.plan

import java.io.Serializable

/** Per-column statistics for physical planning decisions. */
case class ColumnStatistics(
    distinctCount: Option[Long] = None,
    nullCount: Option[Long] = None,
    avgLen: Option[Long] = None,
    maxLen: Option[Long] = None
) extends Serializable

/** Statistics about a plan node's output — used by PhysicalPlanner for strategy selection.
  *
  * Statistics flow bottom-up through the plan: leaf nodes (TableScan) carry known statistics, and
  * each operator estimates its output statistics from its children's statistics.
  */
case class Statistics(
    rowCount: Long,
    sizeInBytes: Long,
    columnStats: Map[String, ColumnStatistics] = Map.empty
) extends Serializable

object Statistics:

  /** Sentinel for unknown statistics (e.g., unregistered tables). */
  val unknown: Statistics = Statistics(rowCount = -1, sizeInBytes = -1)

  /** Estimate output statistics after a filter. Uses a default selectivity of 0.33 (one-third of
    * rows pass).
    */
  def afterFilter(input: Statistics, selectivity: Double = 0.33): Statistics =
    if input.rowCount < 0 then input
    else
      val estRows = math.max(1, (input.rowCount * selectivity).toLong)
      val estBytes =
        if input.sizeInBytes > 0 && input.rowCount > 0 then
          math.max(1, (input.sizeInBytes.toDouble * estRows / input.rowCount).toLong)
        else input.sizeInBytes
      Statistics(rowCount = estRows, sizeInBytes = estBytes)

  /** Estimate output statistics after a join. */
  def afterJoin(
      left: Statistics,
      right: Statistics,
      joinType: JoinType
  ): Statistics =
    if left.rowCount < 0 || right.rowCount < 0 then unknown
    else
      val estRows = joinType match
        case JoinType.Inner =>
          left.rowCount * right.rowCount / math.max(1, math.max(left.rowCount, right.rowCount))
        case JoinType.LeftOuter =>
          math.max(
            left.rowCount,
            left.rowCount * right.rowCount / math.max(1, math.max(left.rowCount, right.rowCount))
          )
        case JoinType.RightOuter =>
          math.max(
            right.rowCount,
            left.rowCount * right.rowCount / math.max(1, math.max(left.rowCount, right.rowCount))
          )
        case JoinType.FullOuter                    => left.rowCount + right.rowCount
        case JoinType.LeftSemi | JoinType.LeftAnti => left.rowCount
        case JoinType.Cross                        => left.rowCount * right.rowCount
      val avgRowSize =
        if left.rowCount > 0 && right.rowCount > 0 then
          (left.sizeInBytes.toDouble / left.rowCount) + (right.sizeInBytes.toDouble / right.rowCount)
        else 100.0
      Statistics(rowCount = estRows, sizeInBytes = (estRows * avgRowSize).toLong)

  /** Estimate output statistics after aggregation. */
  def afterAggregate(input: Statistics, numGroupKeys: Int): Statistics =
    if input.rowCount < 0 then input
    else
      val estGroups =
        if numGroupKeys == 0 then 1L
        else math.min(input.rowCount, math.max(1L, input.rowCount / 10))
      val estBytes =
        if input.sizeInBytes > 0 && input.rowCount > 0 then
          math.max(1, (input.sizeInBytes.toDouble * estGroups / input.rowCount).toLong)
        else input.sizeInBytes
      Statistics(rowCount = estGroups, sizeInBytes = estBytes)

  /** Estimate output statistics after a projection (row count unchanged). */
  def afterProject(input: Statistics, numOutputCols: Int, numInputCols: Int): Statistics =
    if input.sizeInBytes < 0 || numInputCols == 0 then input
    else
      val ratio = numOutputCols.toDouble / numInputCols
      Statistics(
        rowCount = input.rowCount,
        sizeInBytes = math.max(1, (input.sizeInBytes * ratio).toLong)
      )
