package protocatalyst.executor.datafusion

import org.apache.arrow.memory.BufferAllocator

import protocatalyst.executor.flightsql.{FlightSqlBackend, FlightSqlConfig}

/** A [[FlightSqlBackend]] preset for a DataFusion Flight SQL server (`tools/datafusion-server`).
  *
  * DataFusion is just one Flight SQL server; the generic client is `FlightSqlBackend`. This preset
  * only supplies DataFusion's external-table DDL and a localhost factory.
  *
  * Start the server with:
  * {{{
  * cd tools/datafusion-server && cargo run --release   // listens on 0.0.0.0:50051
  * }}}
  *
  * @param config
  *   Flight SQL server configuration
  * @param allocator
  *   Arrow memory allocator (must remain open for the lifetime of the backend)
  */
class DataFusionBackend(config: FlightSqlConfig, allocator: BufferAllocator)
    extends FlightSqlBackend(config, allocator, DataFusionBackend.parquetDdl)

object DataFusionBackend:

  /** DataFusion registers external Parquet via `CREATE EXTERNAL TABLE … STORED AS PARQUET`. */
  private val parquetDdl: (String, String) => String =
    (tableName, parquetPath) =>
      s"CREATE EXTERNAL TABLE $tableName STORED AS PARQUET LOCATION '$parquetPath'"

  /** Connect to a DataFusion server at localhost:50051. */
  def localhost(allocator: BufferAllocator): DataFusionBackend =
    new DataFusionBackend(FlightSqlConfig.localhost(), allocator)

  /** Connect to a DataFusion server at a custom host/port. */
  def apply(host: String, port: Int, allocator: BufferAllocator): DataFusionBackend =
    new DataFusionBackend(FlightSqlConfig(host = host, port = port), allocator)
