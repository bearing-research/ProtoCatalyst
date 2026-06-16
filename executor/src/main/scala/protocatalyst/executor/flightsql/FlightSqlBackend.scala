package protocatalyst.executor.flightsql

import java.util

import org.apache.arrow.adbc.core.AdbcDriver
import org.apache.arrow.adbc.driver.flightsql.FlightSqlDriver
import org.apache.arrow.memory.BufferAllocator

import protocatalyst.executor.sql.AdbcSqlBackend

/** A generic Arrow Flight SQL backend (ADBC).
  *
  * Works against **any** Flight SQL server — DataFusion, Dremio, InfluxDB 3.x, Apache Doris,
  * Ballista, GreptimeDB, … — because nothing here is engine-specific except *how* a Parquet file is
  * registered as a table, which is supplied per engine as `registerParquetDdl`. Everything else
  * (transpile, execute, Arrow → `Batch`, lifecycle) is inherited from
  * [[protocatalyst.executor.sql.AdbcSqlBackend]].
  *
  * Not thread-safe; create one instance per concurrent caller.
  *
  * @param config
  *   Flight SQL connection (host/port/TLS/auth)
  * @param allocator
  *   Arrow allocator (must outlive the backend; closed separately by the caller)
  * @param registerParquetDdl
  *   `(tableName, parquetPath) => SQL` to register a Parquet file (dialect-specific)
  */
class FlightSqlBackend(
    config: FlightSqlConfig,
    allocator: BufferAllocator,
    registerParquetDdl: (String, String) => String
) extends AdbcSqlBackend(
      allocator,
      new FlightSqlDriver(allocator),
      FlightSqlBackend.connectionParams(config),
      config.toAdbcUri
    ):

  override protected def parquetRegisterSql(tableName: String, parquetPath: String): String =
    registerParquetDdl(tableName, parquetPath)

end FlightSqlBackend

object FlightSqlBackend:

  /** ADBC connection params for a Flight SQL endpoint (URI + optional basic auth). */
  private[executor] def connectionParams(config: FlightSqlConfig): util.Map[String, AnyRef] =
    val params = new util.HashMap[String, AnyRef]()
    params.put(AdbcDriver.PARAM_URI.getKey, config.toAdbcUri)
    config.username.foreach(u => params.put("username", u))
    config.password.foreach(p => params.put("password", p))
    params

  /** Create a Flight SQL backend with an explicit Parquet-registration DDL. */
  def apply(
      config: FlightSqlConfig,
      allocator: BufferAllocator,
      registerParquetDdl: (String, String) => String
  ): FlightSqlBackend =
    new FlightSqlBackend(config, allocator, registerParquetDdl)
