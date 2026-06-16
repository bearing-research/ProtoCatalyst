package protocatalyst.executor.datafusion

import org.apache.arrow.memory.{BufferAllocator, RootAllocator}

import protocatalyst.executor.exec.ExecutionException
import protocatalyst.executor.flightsql.FlightSqlConfig
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan.ProtoLogicalPlan
import protocatalyst.schema.{FieldContract, ProtoSchema, SchemaContract, SchemaFingerprint}
import protocatalyst.types.{ProtoStructField, ProtoType}

/** Test suite for DataFusionBackend.
  *
  * **Prerequisites**: Tests require a running DataFusion Flight SQL server.
  *
  * To run the DataFusion server:
  * {{{
  * # Install DataFusion CLI (includes Flight SQL server)
  * cargo install datafusion-cli
  *
  * # Start Flight SQL server
  * datafusion-cli --flight-sql-server localhost:50051
  * }}}
  *
  * **Test Behavior**:
  *   - If server is not available, all tests are skipped (using munit's `assume()`)
  *   - Tests won't fail CI when server is not running
  *   - To run tests locally, start the server first
  */
class DataFusionBackendSuite extends munit.FunSuite:

  var backend: DataFusionBackend = null
  var allocator: BufferAllocator = null

  val serverUrl = "grpc://localhost:50051/"

  /** Check if DataFusion Flight SQL server is available.
    *
    * Attempts to create a connection and immediately close it.
    *
    * @return
    *   true if server is available, false otherwise
    */
  def checkServerAvailable(): Boolean =
    try
      val testAllocator = new RootAllocator()
      try
        val testBackend = new DataFusionBackend(FlightSqlConfig.localhost(), testAllocator)
        try
          // Try executing a simple query (close the result Batch so testAllocator can close
          // cleanly — otherwise a successful query leaks memory and the close below throws).
          testBackend.executeSql("SELECT 1").close()
          true
        finally testBackend.close()
      finally testAllocator.close()
    catch case _: Exception => false

  val serverAvailable = checkServerAvailable()

  override def beforeAll(): Unit =
    if serverAvailable then
      allocator = new RootAllocator()
      backend = new DataFusionBackend(FlightSqlConfig.localhost(), allocator)

  override def afterAll(): Unit =
    if backend != null then backend.close()
    if allocator != null then allocator.close()

  // Helper to create SchemaContract
  def makeContract(name: String, fields: (String, ProtoType, Boolean)*): SchemaContract =
    val fieldContracts = fields.toVector.map { case (fname, ftype, nullable) =>
      FieldContract(fname, ftype, nullable)
    }
    val structFields = fields.toVector.map { case (fname, ftype, nullable) =>
      ProtoStructField(fname, ftype, nullable)
    }
    SchemaContract(name, fieldContracts, SchemaFingerprint.compute(structFields))

  // ========== Basic SQL Execution Tests ==========

  test("executeSql - simple SELECT literal"):
    assume(serverAvailable, "DataFusion Flight SQL server not available")

    val result = backend.executeSql("SELECT 42 AS answer")

    assertEquals(result.numColumns, 1)
    assertEquals(result.rowCount, 1)
    result.close()

  test("executeSql - SELECT with multiple columns"):
    assume(serverAvailable, "DataFusion Flight SQL server not available")

    val result = backend.executeSql("SELECT 1 AS a, 2 AS b, 3 AS c")

    assertEquals(result.numColumns, 3)
    assertEquals(result.rowCount, 1)
    result.close()

  test("executeSql - SELECT with arithmetic"):
    assume(serverAvailable, "DataFusion Flight SQL server not available")

    val result = backend.executeSql("SELECT 10 + 5 AS sum, 10 * 5 AS product")

    assertEquals(result.numColumns, 2)
    assertEquals(result.rowCount, 1)
    result.close()

  test("executeSql - SELECT with WHERE clause"):
    assume(serverAvailable, "DataFusion Flight SQL server not available")

    val result = backend.executeSql("""
      SELECT * FROM (
        SELECT 1 AS id, 'Alice' AS name
        UNION ALL
        SELECT 2 AS id, 'Bob' AS name
        UNION ALL
        SELECT 3 AS id, 'Charlie' AS name
      ) AS users WHERE id > 1
    """)

    assertEquals(result.rowCount, 2)
    result.close()

  test("executeSql - GROUP BY with aggregates"):
    assume(serverAvailable, "DataFusion Flight SQL server not available")

    val result = backend.executeSql("""
      SELECT category, COUNT(*) AS count, SUM(value) AS total
      FROM (
        SELECT 'A' AS category, 10 AS value
        UNION ALL
        SELECT 'A' AS category, 20 AS value
        UNION ALL
        SELECT 'B' AS category, 30 AS value
      ) AS data
      GROUP BY category
    """)

    assertEquals(result.rowCount, 2)
    result.close()

  test("executeSql - ORDER BY"):
    assume(serverAvailable, "DataFusion Flight SQL server not available")

    val result = backend.executeSql("""
      SELECT * FROM (
        SELECT 3 AS value
        UNION ALL
        SELECT 1 AS value
        UNION ALL
        SELECT 2 AS value
      ) AS data
      ORDER BY value ASC
    """)

    assertEquals(result.rowCount, 3)
    result.close()

  test("executeSql - LIMIT"):
    assume(serverAvailable, "DataFusion Flight SQL server not available")

    val result = backend.executeSql("""
      SELECT * FROM (
        SELECT 1 AS id
        UNION ALL
        SELECT 2 AS id
        UNION ALL
        SELECT 3 AS id
      ) AS data
      LIMIT 2
    """)

    assertEquals(result.rowCount, 2)
    result.close()

  test("executeSql - JOIN"):
    assume(serverAvailable, "DataFusion Flight SQL server not available")

    val result = backend.executeSql("""
      SELECT l.id, l.name, r.dept
      FROM (
        SELECT 1 AS id, 'Alice' AS name
        UNION ALL
        SELECT 2 AS id, 'Bob' AS name
      ) AS l
      INNER JOIN (
        SELECT 1 AS id, 'Engineering' AS dept
        UNION ALL
        SELECT 2 AS id, 'Sales' AS dept
      ) AS r
      ON l.id = r.id
    """)

    assertEquals(result.rowCount, 2)
    result.close()

  test("executeSql - UNION"):
    assume(serverAvailable, "DataFusion Flight SQL server not available")

    val result = backend.executeSql("""
      SELECT 1 AS id
      UNION ALL
      SELECT 2 AS id
      UNION ALL
      SELECT 3 AS id
    """)

    assertEquals(result.rowCount, 3)
    result.close()

  test("executeSql - CTE (WITH clause)"):
    assume(serverAvailable, "DataFusion Flight SQL server not available")

    val result = backend.executeSql("""
      WITH data AS (
        SELECT 1 AS id, 'Alice' AS name
        UNION ALL
        SELECT 2 AS id, 'Bob' AS name
      )
      SELECT * FROM data WHERE id = 1
    """)

    assertEquals(result.rowCount, 1)
    result.close()

  // ========== ProtoLogicalPlan Execution Tests ==========

  test("execute - simple Project"):
    assume(serverAvailable, "DataFusion Flight SQL server not available")

    val plan = ProtoLogicalPlan.Project(
      projectList = Vector(ProtoExpr.lit(42)),
      child = ProtoLogicalPlan.Values(
        rows = Vector(Vector.empty),
        schema = ProtoSchema(Vector.empty)
      )
    )

    val result = backend.execute(plan)

    assertEquals(result.rowCount, 1)
    result.close()

  test("execute - Filter"):
    assume(serverAvailable, "DataFusion Flight SQL server not available")

    val schema = ProtoSchema(
      Vector(
        ProtoStructField("id", ProtoType.IntegerType, nullable = false),
        ProtoStructField("name", ProtoType.StringType, nullable = false)
      )
    )

    val plan = ProtoLogicalPlan.Filter(
      condition = ProtoExpr.Gt(
        ProtoExpr.ColumnRef("id", None, ProtoType.IntegerType, nullable = false),
        ProtoExpr.lit(1)
      ),
      child = ProtoLogicalPlan.Values(
        rows = Vector(
          Vector(ProtoExpr.lit(1), ProtoExpr.lit("Alice")),
          Vector(ProtoExpr.lit(2), ProtoExpr.lit("Bob")),
          Vector(ProtoExpr.lit(3), ProtoExpr.lit("Charlie"))
        ),
        schema = schema
      )
    )

    val result = backend.execute(plan)

    assertEquals(result.rowCount, 2) // id > 1: Bob and Charlie
    result.close()

  test("execute - Aggregate"):
    assume(serverAvailable, "DataFusion Flight SQL server not available")

    val schema = ProtoSchema(
      Vector(
        ProtoStructField("category", ProtoType.StringType, nullable = false),
        ProtoStructField("value", ProtoType.IntegerType, nullable = false)
      )
    )

    val plan = ProtoLogicalPlan.Aggregate(
      groupingExprs =
        Vector(ProtoExpr.ColumnRef("category", None, ProtoType.StringType, nullable = false)),
      aggregateExprs = Vector(
        ProtoExpr.Count(
          ProtoExpr.ColumnRef("value", None, ProtoType.IntegerType, nullable = false),
          distinct = false
        )
      ),
      child = ProtoLogicalPlan.Values(
        rows = Vector(
          Vector(ProtoExpr.lit("A"), ProtoExpr.lit(10)),
          Vector(ProtoExpr.lit("A"), ProtoExpr.lit(20)),
          Vector(ProtoExpr.lit("B"), ProtoExpr.lit(30))
        ),
        schema = schema
      )
    )

    val result = backend.execute(plan)

    assertEquals(result.rowCount, 2) // Two categories: A and B
    result.close()

  // ========== Error Handling Tests ==========

  test("executeSql - invalid SQL throws ExecutionException"):
    assume(serverAvailable, "DataFusion Flight SQL server not available")

    intercept[ExecutionException] {
      backend.executeSql("SELECT * FROM nonexistent_table")
    }

  test("execute - unsupported plan throws exception"):
    assume(serverAvailable, "DataFusion Flight SQL server not available")

    val plan = ProtoLogicalPlan.Pivot(
      groupingExprs = Vector.empty,
      pivotColumn = ProtoExpr.ColumnRef("year", None, ProtoType.IntegerType, nullable = false),
      pivotValues = Vector.empty,
      aggregates = Vector.empty,
      child = ProtoLogicalPlan.Values(Vector.empty, ProtoSchema(Vector.empty))
    )

    // Should throw when generating SQL (Pivot not supported)
    intercept[Exception] {
      backend.execute(plan)
    }

  // ========== Edge Cases ==========

  test("executeSql - empty result set"):
    assume(serverAvailable, "DataFusion Flight SQL server not available")

    val result = backend.executeSql("""
      SELECT * FROM (
        SELECT 1 AS id
      ) AS data
      WHERE id > 100
    """)

    assertEquals(result.rowCount, 0)
    assertEquals(result.isEmpty, true)
    result.close()

  test("executeSql - NULL values"):
    assume(serverAvailable, "DataFusion Flight SQL server not available")

    val result = backend.executeSql("SELECT NULL AS value")

    assertEquals(result.rowCount, 1)
    result.close()

end DataFusionBackendSuite
