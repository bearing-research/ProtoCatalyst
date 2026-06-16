package protocatalyst.executor.sql

import java.util

import scala.util.Using

import org.apache.arrow.adbc.core.*
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.{NullVector, VectorSchemaRoot}
import org.apache.arrow.vector.ipc.ArrowReader

import protocatalyst.arrow.ArrowSchemaConverter
import protocatalyst.executor.exec.{Batch, ExecutionException}
import protocatalyst.plan.ProtoLogicalPlan
import protocatalyst.sql.SqlGenerator

/** Shared ADBC implementation of [[SqlBackend]] for any Arrow-ADBC SQL engine.
  *
  * Concrete subclasses supply the ADBC driver, the connection parameters, and the dialect-specific
  * Parquet-registration SQL; everything else lives here — plan transpilation, query/update
  * execution, Arrow → `Batch` conversion, and lifecycle.
  *
  * The driver and connection params are passed as constructor *arguments* (evaluated in the subclass
  * before this constructor runs), so the database can be opened eagerly without the init-order hazard
  * of calling an overridden method from a superclass constructor.
  *
  * Not thread-safe; create one instance per concurrent caller.
  *
  * @param allocator
  *   Arrow allocator (must outlive the backend; closed separately by the caller)
  * @param driver
  *   the ADBC driver to open the database with
  * @param connectionParams
  *   ADBC connection parameters (URI, auth, …)
  * @param connectionDescription
  *   human-readable connection target, used in connection-error messages
  */
abstract class AdbcSqlBackend(
    allocator: BufferAllocator,
    driver: AdbcDriver,
    connectionParams: util.Map[String, AnyRef],
    connectionDescription: String
) extends SqlBackend:

  private val database: AdbcDatabase =
    try driver.open(connectionParams)
    catch
      case e: AdbcException =>
        throw ExecutionException(s"Failed to connect to $connectionDescription: ${e.getMessage}", e)

  /** Dialect-specific SQL to register a Parquet file as a table. */
  protected def parquetRegisterSql(tableName: String, parquetPath: String): String

  final def execute(plan: ProtoLogicalPlan): Batch =
    executeSql(SqlGenerator.generate(plan))

  final def executeSql(sql: String): Batch =
    try
      Using.resource(database.connect()) { connection =>
        Using.resource(connection.createStatement()) { statement =>
          statement.setSqlQuery(sql)
          Using.resource(statement.executeQuery()) { queryResult =>
            readAllBatches(queryResult.getReader)
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

  final def executeUpdate(sql: String): Unit =
    try
      Using.resource(database.connect()) { connection =>
        Using.resource(connection.createStatement()) { statement =>
          statement.setSqlQuery(sql)
          statement.executeUpdate()
        }
      }
    catch
      case e: AdbcException =>
        throw ExecutionException(s"Failed to execute SQL statement: $sql\nError: ${e.getMessage}", e)
      case e: Exception =>
        throw ExecutionException(
          s"Unexpected error executing SQL statement: $sql\nError: ${e.getMessage}",
          e
        )

  final def registerParquetTable(tableName: String, parquetPath: String): Unit =
    executeUpdate(parquetRegisterSql(tableName, parquetPath))

  final def close(): Unit =
    try if database != null then database.close()
    catch
      case e: Exception =>
        System.err.println(s"Warning: Error closing ${getClass.getSimpleName}: ${e.getMessage}")

  /** Materialize *all* result batches into a single [[Batch]] owned by this backend's allocator.
    *
    * The ADBC/Flight reader reuses one `VectorSchemaRoot` per `loadNextBatch()`, so each batch is
    * copied into the accumulating target before the next load overwrites it. `NullVector` columns
    * (e.g. `SELECT NULL`) carry no data, so only the row count is advanced for them — `copyFromSafe`
    * is unsupported on `NullVector`.
    */
  private def readAllBatches(reader: ArrowReader): Batch =
    val sourceRoot = reader.getVectorSchemaRoot
    val schema = ArrowSchemaConverter.fromArrowSchema(sourceRoot.getSchema)
    val target = VectorSchemaRoot.create(sourceRoot.getSchema, allocator)
    target.allocateNew()
    var total = 0
    while reader.loadNextBatch() do
      val n = sourceRoot.getRowCount
      for i <- 0 until sourceRoot.getFieldVectors.size do
        sourceRoot.getVector(i) match
          case _: NullVector => () // no data buffer; row count handled by setRowCount below
          case sourceVec =>
            val targetVec = target.getVector(i)
            for row <- 0 until n do targetVec.copyFromSafe(row, total + row, sourceVec)
      total += n
    target.setRowCount(total)
    Batch.fromRoot(target, schema)

end AdbcSqlBackend
