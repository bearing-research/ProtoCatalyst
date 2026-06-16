package protocatalyst.executor.tpch

import java.nio.file.{Files, Paths}

import scala.jdk.CollectionConverters.*

import org.apache.arrow.memory.{BufferAllocator, RootAllocator}

import protocatalyst.arrow.parquet.ParquetIO
import protocatalyst.executor.Catalog
import protocatalyst.executor.datafusion.DataFusionBackend
import protocatalyst.executor.exec.Batch
import protocatalyst.executor.physical.PhysicalPlanExecutor
import protocatalyst.optimizer.Optimizer
import protocatalyst.plan.{PhysicalPlanner, ProtoLogicalPlan}
import protocatalyst.schema.ProtoSchema
import protocatalyst.sql.parser.SqlParser
import protocatalyst.sql.transform.AstToProtoTransform

/** Cross-backend TPC-H harness: compile a query once (SQL → ProtoLogicalPlan) and run the *same*
  * plan on the Local Arrow executor and on DataFusion, comparing results. A concrete demonstration
  * of the engine-independent compiler thesis.
  *
  * Requires locally-generated TPC-H parquet at `data/tpch/sf-0.01-parquet` (gitignored — TPC license
  * restricts redistribution; regenerate via `scripts/gen-tpch.sh`). Tests skip when it's absent. The
  * DataFusion comparison additionally needs `tools/datafusion-server` running; it skips otherwise.
  *
  * Status: the Local path runs a real TPC-H selection (project+filter over lineitem) end to end.
  * Running this against more of TPC-H surfaced a concrete coverage backlog, across three layers:
  *   1. SQL parser — no `DATE '…'` typed literals (blocks the date predicates).
  *   2. Local executor — global aggregate (`SUM` with no `GROUP BY`): "Aggregate expressions must be
  *      evaluated by AggregateOp".
  *   3. `datafusion-flight-sql-server` — no DDL: `CREATE EXTERNAL TABLE` hits an unimplemented
  *      `do_put_statement_update`, so the client can't register tables (pre-register them in
  *      `tools/datafusion-server`, or add a do_put shim, to enable the DataFusion comparison).
  *   4. Multi-table joins — need a per-table schema catalog in `AstToProtoTransform` (it currently
  *      applies one schema to all tables).
  */
class TpchCrossBackendSuite extends munit.FunSuite:

  // The forked test JVM's working directory isn't necessarily the repo root, so locate the data dir
  // by walking up from the cwd (TPC-H parquet lives at <repo>/data/tpch/sf-0.01-parquet).
  private val dataDir: String =
    val rel = Paths.get("data", "tpch", "sf-0.01-parquet")
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .take(6)
      .map(_.resolve(rel))
      .find(p => Files.isDirectory(p.resolve("lineitem.parquet")))
      .map(_.toString)
      .getOrElse(rel.toString)

  /** A table is a directory of one or more `.parquet` part files (Spark-style output). */
  private def tableDir(t: String): String = s"$dataDir/$t.parquet"
  private def partFiles(t: String): Vector[String] =
    Files.list(Paths.get(tableDir(t))).iterator.asScala
      .map(_.toString)
      .filter(_.endsWith(".parquet"))
      .toVector
      .sorted

  private val dataAvailable = Files.isDirectory(Paths.get(tableDir("lineitem")))

  // A project+filter over lineitem (Q6's selection, without the aggregate) — the SQL surface that
  // both backends fully support today: column refs, arithmetic-free projection, BETWEEN, AND, `<`.
  private val q6Selection =
    """SELECT orderkey, extendedprice, discount
      |FROM lineitem
      |WHERE discount BETWEEN 0.05 AND 0.07
      |  AND quantity < 24""".stripMargin

  // The full TPC-H Q6 (global SUM). Currently *not* runnable end-to-end — this harness surfaced two
  // coverage gaps it depends on: (1) the SQL parser lacks `DATE '…'` typed literals; (2) the Local
  // executor can't evaluate a global aggregate (SUM with no GROUP BY) — "Aggregate expressions must
  // be evaluated by AggregateOp". Tracked as the next hardening work; kept here (ignored) as the goal.
  private val q6 =
    """SELECT SUM(extendedprice * discount) AS revenue
      |FROM lineitem
      |WHERE discount BETWEEN 0.05 AND 0.07
      |  AND quantity < 24""".stripMargin

  /** SQL → optimized ProtoLogicalPlan for a single-table query. */
  private def compile(sql: String, tableName: String, schema: ProtoSchema): ProtoLogicalPlan =
    val stmt = SqlParser.parse(sql).fold(e => fail(s"parse error: $e"), identity)
    val plan = AstToProtoTransform
      .transformStmt(stmt, schema, tableName)
      .fold(e => fail(s"transform error: $e"), identity)
    Optimizer.optimize(plan)

  // Both backends read the *same single part file* so they see identical data — a clean
  // engine-independence comparison. (Reading the whole multi-part directory is a follow-up: it needs
  // ParquetIO directory support on the Local side and DataFusion's directory-LOCATION handling.)
  private def lineitemPart: String = Paths.get(partFiles("lineitem").head).toAbsolutePath.toString

  private def loadTable(path: String, allocator: BufferAllocator): Batch =
    val (root, schema) = ParquetIO.read(path, allocator)
    Batch.fromRoot(root, schema)

  /** Run a plan on the Local Arrow executor; returns the result row count. (Per-run allocator is left
    * open — reclaimed at JVM exit — to avoid leak detection on the catalog's registered table.) */
  private def runLocal(plan: ProtoLogicalPlan): Long =
    val allocator = new RootAllocator()
    val catalog = new Catalog()
    catalog.registerTable("lineitem", loadTable(lineitemPart, allocator))
    catalog.registerStatistics("lineitem", ParquetIO.readStatistics(lineitemPart))
    val physical = PhysicalPlanner(catalog.statsProvider).plan(plan)
    val batch = PhysicalPlanExecutor(catalog, allocator).execute(physical)
    val n = batch.rowCount.toLong
    batch.close()
    n

  /** Run a plan on DataFusion; returns the result row count, or None if the server is unavailable. */
  private def runDataFusion(plan: ProtoLogicalPlan): Option[Long] =
    val allocator = new RootAllocator()
    try
      val backend = DataFusionBackend.localhost(allocator)
      try
        backend.registerParquetTable("lineitem", lineitemPart) // same single part file as Local
        val batch = backend.execute(plan)
        val n = batch.rowCount.toLong
        batch.close()
        Some(n)
      finally backend.close()
    catch
      case e: Exception =>
        System.err.println(s"[DataFusion] unavailable/failed: ${e.getClass.getSimpleName}: ${e.getMessage}")
        None

  private def lineitemSchema: ProtoSchema = ParquetIO.readSchema(partFiles("lineitem").head)

  test("Q6 selection (project+filter) — runs on the Local Arrow executor"):
    assume(dataAvailable, s"TPC-H parquet not found at $dataDir (run scripts/gen-tpch.sh)")
    val plan = compile(q6Selection, "lineitem", lineitemSchema)
    val rows = runLocal(plan)
    assert(rows > 0, s"expected some filtered rows, got $rows")

  test("Q6 selection (project+filter) — Local and DataFusion agree"):
    assume(dataAvailable, s"TPC-H parquet not found at $dataDir (run scripts/gen-tpch.sh)")
    val plan = compile(q6Selection, "lineitem", lineitemSchema)
    val local = runLocal(plan)
    runDataFusion(plan) match
      case None =>
        // Server not running, OR it can't register the table: datafusion-flight-sql-server leaves
        // do_put_statement_update (DDL like CREATE EXTERNAL TABLE) unimplemented. Closing this needs
        // either pre-registering the TPC-H tables in tools/datafusion-server at startup, or a
        // do_put_statement_update shim. See the suite scaladoc.
        assume(false, "DataFusion comparison unavailable (server down, or no DDL/table registration)")
      case Some(df) => assertEquals(df, local) // same compiled plan → same row count, both engines

  test("Q6 (global SUM) — pending: parser DATE literals + Local global-aggregate".ignore):
    val plan = compile(q6, "lineitem", lineitemSchema)
    val local = runLocal(plan)
    assertEquals(local, 1L)

end TpchCrossBackendSuite
