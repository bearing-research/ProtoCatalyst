package protocatalyst.executor.datafusion

import org.apache.arrow.memory.RootAllocator

import protocatalyst.executor.flightsql.FlightSqlConfig

/** Quick connectivity test — prints error details instead of swallowing exceptions. */
object FlightSqlConnectTest:
  def main(args: Array[String]): Unit =
    val allocator = new RootAllocator()
    try
      println(s"Connecting to grpc://localhost:50051/ ...")
      val backend = new DataFusionBackend(FlightSqlConfig.localhost(), allocator)
      try
        println("Connection opened. Executing SELECT 1 ...")
        val result = backend.executeSql("SELECT 1")
        println(s"Success! Got ${result.rowCount} rows, ${result.numColumns} columns")
        result.close()
      finally backend.close()
    catch
      case e: Exception =>
        println(s"ERROR: ${e.getClass.getName}: ${e.getMessage}")
        if e.getCause != null then
          println(s"CAUSE: ${e.getCause.getClass.getName}: ${e.getCause.getMessage}")
        e.printStackTrace()
    finally allocator.close()
