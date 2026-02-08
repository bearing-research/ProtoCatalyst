package protocatalyst.executor

import scala.collection.mutable

import protocatalyst.executor.exec.Batch
import protocatalyst.schema.ProtoSchema

/** Registry of in-memory tables for the executor.
  *
  * Tables are registered as Arrow batches with their ProtoSchema. The catalog resolves
  * `RelationRef` plan nodes to their corresponding batches during execution.
  */
final class Catalog:
  private val tables: mutable.Map[String, Batch] = mutable.Map.empty

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
