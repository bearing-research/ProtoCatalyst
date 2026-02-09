package protocatalyst.executor

import scala.collection.mutable

import protocatalyst.executor.exec.Batch
import protocatalyst.plan.Statistics
import protocatalyst.schema.ProtoSchema

/** Registry of in-memory tables for the executor.
  *
  * Tables are registered as Arrow batches with their ProtoSchema. The catalog resolves
  * `RelationRef` plan nodes to their corresponding batches during execution.
  */
final class Catalog:
  private val tables: mutable.Map[String, Batch] = mutable.Map.empty
  private val statsMap: mutable.Map[String, Statistics] = mutable.Map.empty

  /** Register a table with the given name. Overwrites any existing table with the same name. */
  def registerTable(name: String, batch: Batch): Unit =
    tables(name.toLowerCase) = batch

  /** Look up a table by name (case-insensitive). */
  def getTable(name: String): Option[Batch] =
    tables.get(name.toLowerCase)

  /** Get the schema for a registered table. */
  def tableSchema(name: String): Option[ProtoSchema] =
    tables.get(name.toLowerCase).map(_.schema)

  /** All registered table names. */
  def tableNames: Set[String] =
    tables.keySet.toSet

  /** Register explicit statistics for a table. */
  def registerStatistics(name: String, stats: Statistics): Unit =
    statsMap(name.toLowerCase) = stats

  /** Get statistics for a table. Returns explicitly registered stats, auto-derived stats from the
    * batch, or `Statistics.unknown` if the table is not registered.
    */
  def getStatistics(name: String): Statistics =
    val key = name.toLowerCase
    statsMap.getOrElse(
      key,
      tables.get(key) match
        case Some(batch) => deriveStats(batch)
        case None        => Statistics.unknown
    )

  /** Statistics provider function for PhysicalPlanner. */
  def statsProvider: String => Statistics = name => getStatistics(name)

  /** Derive basic statistics from a Batch. */
  private def deriveStats(batch: Batch): Statistics =
    val rowCount = batch.rowCount.toLong
    // Estimate ~100 bytes per row as a rough heuristic
    val sizeInBytes = rowCount * 100L
    Statistics(rowCount = rowCount, sizeInBytes = sizeInBytes)
