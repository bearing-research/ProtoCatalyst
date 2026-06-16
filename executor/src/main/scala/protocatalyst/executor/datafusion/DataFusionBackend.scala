package protocatalyst.executor.datafusion

import java.util

import org.apache.arrow.adbc.core.AdbcDriver
import org.apache.arrow.adbc.driver.flightsql.FlightSqlDriver
import org.apache.arrow.memory.BufferAllocator

import protocatalyst.executor.sql.AdbcSqlBackend

/** DataFusion backend via ADBC Flight SQL driver.
  *
  * Executes ProtoCatalyst IR by:
  *   1. Transpiling ProtoLogicalPlan → SQL using SqlGenerator
  *   1. Sending SQL to a DataFusion Flight SQL server via ADBC
  *   1. Reading results as Arrow RecordBatches
  *   1. Converting to ProtoCatalyst Batch
  *
  * The shared ADBC/SQL/Arrow plumbing lives in [[protocatalyst.executor.sql.AdbcSqlBackend]]; this
  * class only supplies the Flight SQL driver, the connection params, and DataFusion's Parquet DDL.
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
  *   - `close()` closes the database (call when done)
  *
  * **Thread Safety**: Not thread-safe. Create separate instances for concurrent execution.
  *
  * @param config
  *   Flight SQL server configuration
  * @param allocator
  *   Arrow memory allocator (must remain open for lifetime of backend)
  */
class DataFusionBackend(config: FlightSqlConfig, allocator: BufferAllocator)
    extends AdbcSqlBackend(
      allocator,
      new FlightSqlDriver(allocator),
      DataFusionBackend.connectionParams(config),
      config.toAdbcUri
    ):

  /** DataFusion registers external Parquet via `CREATE EXTERNAL TABLE … STORED AS PARQUET`. */
  override protected def parquetRegisterSql(tableName: String, parquetPath: String): String =
    s"CREATE EXTERNAL TABLE $tableName STORED AS PARQUET LOCATION '$parquetPath'"

end DataFusionBackend

object DataFusionBackend:

  /** Build the ADBC connection params for a Flight SQL endpoint (URI + optional auth). */
  private def connectionParams(config: FlightSqlConfig): util.Map[String, AnyRef] =
    val params = new util.HashMap[String, AnyRef]()
    params.put(AdbcDriver.PARAM_URI.getKey, config.toAdbcUri)
    config.username.foreach(u => params.put("username", u))
    config.password.foreach(p => params.put("password", p))
    params

  /** Create a DataFusionBackend connected to localhost:50051. */
  def localhost(allocator: BufferAllocator): DataFusionBackend =
    new DataFusionBackend(FlightSqlConfig.localhost(), allocator)

  /** Create a DataFusionBackend with a custom host/port. */
  def apply(host: String, port: Int, allocator: BufferAllocator): DataFusionBackend =
    new DataFusionBackend(FlightSqlConfig(host = host, port = port), allocator)
