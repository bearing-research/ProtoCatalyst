package protocatalyst.executor.datafusion

/** Configuration for connecting to a Flight SQL server (e.g., DataFusion).
  *
  * @param host
  *   Server hostname (default: "localhost")
  * @param port
  *   Server port (default: 50051)
  * @param username
  *   Optional username for authentication
  * @param password
  *   Optional password for authentication
  * @param useTls
  *   Whether to use TLS encryption (default: false)
  */
case class FlightSqlConfig(
    host: String = "localhost",
    port: Int = 50051,
    username: Option[String] = None,
    password: Option[String] = None,
    useTls: Boolean = false
):
  /** Convert configuration to ADBC connection URI.
    *
    * Format:
    *   - Without TLS: `grpc://host:port/`
    *   - With TLS: `grpc+tls://host:port/`
    *
    * @return
    *   ADBC URI string for Flight SQL driver
    */
  def toAdbcUri: String =
    val protocol = if useTls then "grpc+tls" else "grpc"
    s"$protocol://$host:$port/"

  /** Create ADBC connection parameters map.
    *
    * @return
    *   Map of ADBC connection parameters (URI, username, password if provided)
    */
  def toAdbcParams: Map[String, String] =
    var params = Map("adbc.flight.sql.client_option.uri" -> toAdbcUri)
    username.foreach(u => params += ("adbc.flight.sql.client_option.username" -> u))
    password.foreach(p => params += ("adbc.flight.sql.client_option.password" -> p))
    params

object FlightSqlConfig:
  /** Create configuration for localhost development server.
    *
    * @param port
    *   Server port (default: 50051)
    * @return
    *   FlightSqlConfig for localhost
    */
  def localhost(port: Int = 50051): FlightSqlConfig =
    FlightSqlConfig(host = "localhost", port = port)

  /** Create configuration with TLS enabled.
    *
    * @param host
    *   Server hostname
    * @param port
    *   Server port
    * @param username
    *   Optional username for authentication
    * @param password
    *   Optional password for authentication
    * @return
    *   FlightSqlConfig with TLS enabled
    */
  def withTls(
      host: String,
      port: Int = 50051,
      username: Option[String] = None,
      password: Option[String] = None
  ): FlightSqlConfig =
    FlightSqlConfig(
      host = host,
      port = port,
      username = username,
      password = password,
      useTls = true
    )
