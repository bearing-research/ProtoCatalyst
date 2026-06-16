package protocatalyst.executor.datafusion

import java.util

import scala.util.Using

import org.apache.arrow.adbc.core._
import org.apache.arrow.adbc.driver.flightsql.FlightSqlDriver
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowReader

import protocatalyst.arrow.ArrowSchemaConverter
import protocatalyst.executor.exec.{Batch, ExecutionException}
import protocatalyst.plan.ProtoLogicalPlan
import protocatalyst.sql.SqlGenerator

/** DataFusion backend via ADBC Flight SQL driver.
  *
  * Executes ProtoCatalyst IR by:
  *   1. Transpiling ProtoLogicalPlan → SQL using SqlGenerator
  *   1. Sending SQL to DataFusion Flight SQL server via ADBC
  *   1. Reading results as Arrow RecordBatches
  *   1. Converting to ProtoCatalyst Batch
  *
  * **Prerequisites**: Requires a running DataFusion Flight SQL server (`tools/datafusion-server`).
  * Start it with:
  * {{{
  * cd tools/datafusion-server && cargo run --release   // listens on 0.0.0.0:50051
  * }}}
  *
  * **Architecture**: `ProtoLogicalPlan` → SQL string (transpiler) → ADBC Flight SQL driver →
  * DataFusion server → Arrow RecordBatches → `Batch`
  *
  * **Connection Lifecycle**:
  *   - Constructor opens database connection
  *   - `execute()` / `executeSql()` create temporary statements
  *   - `close()` closes database and driver (call when done)
  *
  * **Thread Safety**: Not thread-safe. Create separate instances for concurrent execution.
  *
  * @param config
  *   Flight SQL server configuration
  * @param allocator
  *   Arrow memory allocator (must remain open for lifetime of backend)
  */
class DataFusionBackend(config: FlightSqlConfig, allocator: BufferAllocator) extends AutoCloseable:

  // ADBC driver and database connection (initialized in constructor)
  private val driver: AdbcDriver = new FlightSqlDriver(allocator)
  private val database: AdbcDatabase = {
    val params = new util.HashMap[String, AnyRef]()
    // Set connection URI
    params.put(AdbcDriver.PARAM_URI.getKey, config.toAdbcUri)
    // Set authentication if provided
    config.username.foreach(u => params.put("username", u))
    config.password.foreach(p => params.put("password", p))
    try driver.open(params)
    catch
      case e: AdbcException =>
        throw ExecutionException(
          s"Failed to connect to Flight SQL server at ${config.toAdbcUri}: ${e.getMessage}",
          e
        )
  }

  /** Execute a ProtoLogicalPlan query and return results as a Batch.
    *
    * This method:
    *   1. Transpiles the plan to SQL using SqlGenerator
    *   1. Executes the SQL via ADBC Flight SQL driver
    *   1. Reads all result batches into a single Batch
    *
    * **Note**: Currently loads all results into memory. For large result sets, consider streaming
    * API (future enhancement).
    *
    * @param plan
    *   The logical plan to execute
    * @return
    *   Result batch containing all rows
    * @throws ExecutionException
    *   if SQL generation or execution fails
    */
  def execute(plan: ProtoLogicalPlan): Batch =
    val sql = SqlGenerator.generate(plan)
    executeSql(sql)

  /** Execute a SQL query string and return results as a Batch.
    *
    * @param sql
    *   SQL query string (ANSI SQL / DataFusion compatible)
    * @return
    *   Result batch containing all rows
    * @throws ExecutionException
    *   if query execution fails
    */
  def executeSql(sql: String): Batch =
    try
      Using.resource(database.connect()) { connection =>
        Using.resource(connection.createStatement()) { statement =>
          // Set SQL query
          statement.setSqlQuery(sql)

          // Execute query and get reader
          Using.resource(statement.executeQuery()) { queryResult =>
            val reader = queryResult.getReader

            // Read all batches (for now, just materialize everything)
            // Future enhancement: support streaming via Iterator[Batch]
            readAllBatches(reader)
          }
        }
      }
    catch
      case e: AdbcException =>
        throw ExecutionException(s"Failed to execute SQL query: $sql\nError: ${e.getMessage}", e)
      case e: ExecutionException => throw e
      case e: Exception          =>
        throw ExecutionException(
          s"Unexpected error executing SQL query: $sql\nError: ${e.getMessage}",
          e
        )

  /** Register a Parquet file as a table in DataFusion.
    *
    * Creates an external table backed by a Parquet file using DataFusion's CREATE EXTERNAL TABLE
    * syntax.
    *
    * Example:
    * {{{
    * backend.registerParquetTable("users", "/data/users.parquet")
    * // Now can query: SELECT * FROM users
    * }}}
    *
    * @param tableName
    *   Name of the table to create
    * @param parquetPath
    *   Absolute path to Parquet file
    * @throws ExecutionException
    *   if table creation fails
    */
  def registerParquetTable(tableName: String, parquetPath: String): Unit =
    val sql = s"CREATE EXTERNAL TABLE $tableName STORED AS PARQUET LOCATION '$parquetPath'"
    executeUpdate(sql)

  /** Execute a DDL/DML statement that doesn't return results (CREATE, INSERT, UPDATE, DELETE).
    *
    * @param sql
    *   SQL statement to execute
    * @throws ExecutionException
    *   if statement execution fails
    */
  def executeUpdate(sql: String): Unit =
    try
      Using.resource(database.connect()) { connection =>
        Using.resource(connection.createStatement()) { statement =>
          statement.setSqlQuery(sql)
          // Execute update (returns affected row count, which we ignore)
          statement.executeUpdate()
        }
      }
    catch
      case e: AdbcException =>
        throw ExecutionException(
          s"Failed to execute SQL statement: $sql\nError: ${e.getMessage}",
          e
        )
      case e: Exception =>
        throw ExecutionException(
          s"Unexpected error executing SQL statement: $sql\nError: ${e.getMessage}",
          e
        )

  /** Close database connection and driver.
    *
    * **Important**: Call this when done with the backend to release resources. The allocator should
    * be closed separately by the caller.
    */
  def close(): Unit =
    try
      if database != null then database.close()
      // Note: AdbcDriver doesn't have a close() method - it's auto-closed when database closes
    catch
      case e: Exception =>
        // Log but don't throw in close()
        System.err.println(s"Warning: Error closing DataFusionBackend: ${e.getMessage}")

  // ========== Private Helper Methods ==========

  /** Read all batches from an ArrowReader into a single Batch.
    *
    * **Current Implementation**: Materializes all results into a single VectorSchemaRoot. For large
    * result sets, this may consume significant memory.
    *
    * **Future Enhancement**: Support streaming via Iterator[Batch] to process results
    * incrementally.
    *
    * @param reader
    *   ArrowReader from ADBC query result
    * @return
    *   Single Batch containing all rows
    */
  private def readAllBatches(reader: ArrowReader): Batch =
    val schema = ArrowSchemaConverter.fromArrowSchema(reader.getVectorSchemaRoot.getSchema)

    // For now, just read the first batch
    // Future: concatenate multiple batches or return Iterator[Batch]
    if reader.loadNextBatch() then
      // Transfer ownership: create new root from existing data
      val sourceRoot = reader.getVectorSchemaRoot
      val newRoot = VectorSchemaRoot.create(sourceRoot.getSchema, allocator)

      // Allocate and copy data
      newRoot.allocateNew()
      newRoot.setRowCount(sourceRoot.getRowCount)

      // Copy vectors
      for i <- 0 until sourceRoot.getFieldVectors.size do
        val sourceVec = sourceRoot.getVector(i)
        val targetVec = newRoot.getVector(i)
        // Copy all values from source to target
        for row <- 0 until sourceRoot.getRowCount do targetVec.copyFromSafe(row, row, sourceVec)

      Batch.fromRoot(newRoot, schema)
    else
      // Empty result set
      Batch.empty(schema, allocator)

end DataFusionBackend

object DataFusionBackend:
  /** Create a DataFusionBackend with default localhost configuration.
    *
    * Connects to DataFusion Flight SQL server at localhost:50051.
    *
    * @param allocator
    *   Arrow memory allocator
    * @return
    *   DataFusionBackend instance
    */
  def localhost(allocator: BufferAllocator): DataFusionBackend =
    new DataFusionBackend(FlightSqlConfig.localhost(), allocator)

  /** Create a DataFusionBackend with custom configuration.
    *
    * @param host
    *   Server hostname
    * @param port
    *   Server port
    * @param allocator
    *   Arrow memory allocator
    * @return
    *   DataFusionBackend instance
    */
  def apply(host: String, port: Int, allocator: BufferAllocator): DataFusionBackend =
    new DataFusionBackend(FlightSqlConfig(host = host, port = port), allocator)
