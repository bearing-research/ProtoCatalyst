package protocatalyst.executor.sql

import protocatalyst.executor.exec.Batch
import protocatalyst.plan.ProtoLogicalPlan

/** A SQL-based execution backend: transpiles a `ProtoLogicalPlan` to SQL (via `SqlGenerator`) and
  * runs it on an external SQL engine, returning Arrow results as a [[Batch]].
  *
  * Implemented by [[AdbcSqlBackend]] for any Arrow-ADBC engine (DataFusion today; DuckDB, Postgres,
  * Trino, … are small subclasses that supply a driver, connection params, and dialect-specific DDL).
  */
trait SqlBackend extends AutoCloseable:

  /** Transpile a logical plan to SQL and execute it, returning all rows as a single Batch. */
  def execute(plan: ProtoLogicalPlan): Batch

  /** Execute a raw SQL query string, returning all rows as a single Batch. */
  def executeSql(sql: String): Batch

  /** Execute a DDL/DML statement that returns no rows (CREATE, INSERT, …). */
  def executeUpdate(sql: String): Unit

  /** Register a Parquet file as a queryable table (the SQL is dialect-specific). */
  def registerParquetTable(tableName: String, parquetPath: String): Unit
