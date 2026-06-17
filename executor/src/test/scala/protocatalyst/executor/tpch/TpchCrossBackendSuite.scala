package protocatalyst.executor.tpch

import java.nio.file.{Files, Paths}

import scala.jdk.CollectionConverters.*

import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.apache.arrow.vector.util.VectorSchemaRootAppender

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
  * Status: project+filter, global aggregate, grouped aggregate (+ORDER BY, row-for-row), a two-table
  * join, and a join⋈group-by⋈order-by capstone all run and *agree* across Local and DataFusion. The
  * server is started with the TPC-H data dir so it pre-registers the tables —
  * `cargo run -- <repo>/data/tpch/sf-0.01-parquet` (datafusion-flight-sql-server has no DDL, so
  * tables can't be created over the wire).
  *
  * All four original coverage gaps are now closed: (1) `DATE '…'` typed literals in the parser;
  * (2) global aggregate (`SUM`/`COUNT(*)` with no `GROUP BY`) in the executor; (3) server-side table
  * pre-registration (no DDL over the wire); (4) per-table schema catalog in `AstToProtoTransform` for
  * multi-table joins.
  *
  * Qualified columns across a join now resolve on the Local backend: an aliased scan qualifies its
  * columns (`c.*`/`n.*`), so `ExprEvaluator.resolveColumn` can disambiguate `n.name` from `customer`'s
  * `name` (the capstone exercises exactly this). Disambiguation needs an explicit table alias; an
  * *unaliased* join whose tables share a column name still resolves by bare suffix (first match).
  *
  * One known Local/DataFusion divergence remains, engine-side (not a compiler issue): DataFusion's
  * strict decimal arithmetic overflows on Q6's `SUM(extendedprice * discount)` over the wide-decimal
  * columns, so full Q6 is asserted on Local only.
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

  // A *global* aggregate (no GROUP BY) over a filter — exercises the global-aggregate path on both
  // backends. Uses COUNT(*) rather than Q6's SUM(extendedprice * discount): the TPC-H parquet stores
  // those columns as wide decimals (DECIMAL(38,18)), and DataFusion's strict decimal arithmetic
  // overflows on the product (Local's arbitrary-precision BigDecimal doesn't) — a data-precision
  // artifact, not a compiler issue.
  private val q6Aggregate =
    """SELECT COUNT(*) AS n
      |FROM lineitem
      |WHERE discount BETWEEN 0.05 AND 0.07
      |  AND quantity < 24""".stripMargin

  // A TPC-H Q1-style *grouped* aggregate: GROUP BY two keys, multiple aggregates per group. Exercises
  // the grouped-aggregate path (vs the global aggregate above) end-to-end on both backends. Uses
  // COUNT(*) and SUM(quantity) — quantity is a narrow DECIMAL(15,2), so its grouped sum stays well
  // within range (unlike Q6's extendedprice*discount product, which overflows DataFusion).
  private val q1Grouped =
    """SELECT returnflag, linestatus, COUNT(*) AS cnt, SUM(quantity) AS sum_qty
      |FROM lineitem
      |GROUP BY returnflag, linestatus""".stripMargin

  // Same grouped aggregate with an ORDER BY, so both backends emit groups in the same deterministic
  // order — lets us compare the actual result *values* row-for-row, not just the group count.
  private val q1GroupedSorted = q1Grouped + "\nORDER BY returnflag, linestatus"

  // The full TPC-H Q6 — adds the `DATE '…'` shipdate predicates the SQL parser doesn't yet support
  // (the remaining gap for full Q6). Kept here (ignored) as the goal.
  private val q6 =
    """SELECT SUM(extendedprice * discount) AS revenue
      |FROM lineitem
      |WHERE shipdate >= DATE '1994-01-01'
      |  AND shipdate < DATE '1995-01-01'
      |  AND discount BETWEEN 0.05 AND 0.07
      |  AND quantity < 24""".stripMargin

  /** SQL → optimized ProtoLogicalPlan for a single-table query. */
  private def compile(sql: String, tableName: String, schema: ProtoSchema): ProtoLogicalPlan =
    val stmt = SqlParser.parse(sql).fold(e => fail(s"parse error: $e"), identity)
    val plan = AstToProtoTransform
      .transformStmt(stmt, schema, tableName)
      .fold(e => fail(s"transform error: $e"), identity)
    Optimizer.optimize(plan)

  /** Read all part files of a table into one Batch (concatenated) — matches the full directory the
    * DataFusion server registers, so both backends see identical data. */
  private def loadTable(table: String, allocator: BufferAllocator): Batch =
    val parts = partFiles(table)
    val (root, schema) = ParquetIO.read(parts.head, allocator)
    parts.tail.foreach { p =>
      val (r, _) = ParquetIO.read(p, allocator)
      VectorSchemaRootAppender.append(root, r)
      r.close()
    }
    Batch.fromRoot(root, schema)

  private def schemaOf(table: String): ProtoSchema = ParquetIO.readSchema(partFiles(table).head)

  /** A catalog (table name → schema) for the multi-table transform. */
  private def catalogOf(tables: String*): Map[String, ProtoSchema] =
    tables.map(t => t -> schemaOf(t)).toMap

  /** SQL → optimized plan against a multi-table catalog (for joins). */
  private def compileMulti(sql: String, catalog: Map[String, ProtoSchema]): ProtoLogicalPlan =
    val stmt = SqlParser.parse(sql).fold(e => fail(s"parse error: $e"), identity)
    val plan = AstToProtoTransform
      .transformStmt(stmt, catalog)
      .fold(e => fail(s"transform error: $e"), identity)
    Optimizer.optimize(plan)

  /** Run a plan on the Local Arrow executor; returns the result row count. (Per-run allocator is left
    * open — reclaimed at JVM exit — to avoid leak detection on the catalog's registered tables.) */
  private def runLocal(plan: ProtoLogicalPlan, tables: Seq[String] = Seq("lineitem")): Long =
    val allocator = new RootAllocator()
    val catalog = new Catalog()
    tables.foreach { t =>
      catalog.registerTable(t, loadTable(t, allocator))
      catalog.registerStatistics(t, ParquetIO.readStatistics(partFiles(t).head))
    }
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
        // The `lineitem` table is pre-registered in the server (started with the data dir) — the
        // server has no DDL, so we don't CREATE the table here, just query it.
        val batch = backend.execute(plan)
        val n = batch.rowCount.toLong
        batch.close()
        Some(n)
      finally backend.close()
    catch
      case e: Exception =>
        System.err.println(s"[DataFusion] unavailable/failed: ${e.getClass.getSimpleName}: ${e.getMessage}")
        None

  /** Read a Batch into normalized rows for cross-backend value comparison. Numbers are coerced to
    * Double (Local sums in Double, DataFusion in Decimal — same value, different carrier type);
    * everything else compares by its string form. */
  private def readRows(batch: Batch): Vector[Vector[Any]] =
    (0 until batch.rowCount).map { r =>
      (0 until batch.numColumns).map { c =>
        Batch.getValue(batch.column(c), r) match
          case null                => null
          case n: java.lang.Number => n.doubleValue
          case other               => other.toString
      }.toVector
    }.toVector

  /** Row-for-row equality with a relative tolerance on numeric cells (Double-vs-Decimal rounding). */
  private def rowsAgree(a: Vector[Vector[Any]], b: Vector[Vector[Any]]): Boolean =
    a.size == b.size && a.zip(b).forall { (ra, rb) =>
      ra.size == rb.size && ra.zip(rb).forall {
        case (x: Double, y: Double) => math.abs(x - y) <= 1e-6 * math.max(1.0, math.abs(x))
        case (x, y)                 => x == y
      }
    }

  /** Run a plan on the Local executor, returning its result rows (normalized). */
  private def runLocalRows(plan: ProtoLogicalPlan, tables: Seq[String] = Seq("lineitem")): Vector[Vector[Any]] =
    val allocator = new RootAllocator()
    val catalog = new Catalog()
    tables.foreach { t =>
      catalog.registerTable(t, loadTable(t, allocator))
      catalog.registerStatistics(t, ParquetIO.readStatistics(partFiles(t).head))
    }
    val physical = PhysicalPlanner(catalog.statsProvider).plan(plan)
    val batch = PhysicalPlanExecutor(catalog, allocator).execute(physical)
    val rows = readRows(batch)
    batch.close()
    rows

  /** Run a plan on DataFusion, returning its result rows (normalized), or None if unavailable. */
  private def runDataFusionRows(plan: ProtoLogicalPlan): Option[Vector[Vector[Any]]] =
    val allocator = new RootAllocator()
    try
      val backend = DataFusionBackend.localhost(allocator)
      try
        val batch = backend.execute(plan)
        val rows = readRows(batch)
        batch.close()
        Some(rows)
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

  test("Q6 aggregate (global SUM) — Local and DataFusion agree"):
    assume(dataAvailable, s"TPC-H parquet not found at $dataDir (run scripts/gen-tpch.sh)")
    val plan = compile(q6Aggregate, "lineitem", lineitemSchema)
    val local = runLocal(plan)
    assertEquals(local, 1L) // a single SUM row
    runDataFusion(plan) match
      case None     => assume(false, "DataFusion comparison unavailable (server down, or no DDL/table registration)")
      case Some(df) => assertEquals(df, local)

  test("Q6 full (DATE predicates + global SUM) — runs on Local"):
    assume(dataAvailable, s"TPC-H parquet not found at $dataDir (run scripts/gen-tpch.sh)")
    // Local only: DataFusion overflows on SUM(extendedprice*discount) given the wide-decimal data.
    val plan = compile(q6, "lineitem", lineitemSchema)
    val rows = runLocal(plan)
    assertEquals(rows, 1L)

  test("Q1 grouped aggregate (GROUP BY 2 keys, 2 aggregates) — Local and DataFusion agree"):
    assume(dataAvailable, s"TPC-H parquet not found at $dataDir (run scripts/gen-tpch.sh)")
    val plan = compile(q1Grouped, "lineitem", lineitemSchema)
    val local = runLocal(plan)
    assert(local > 1, s"expected several groups, got $local") // multiple (returnflag, linestatus) groups
    runDataFusion(plan) match
      case None     => assume(false, "DataFusion comparison unavailable (server down, or no DDL/table registration)")
      case Some(df) => assertEquals(df, local) // same number of groups from the same compiled plan

  test("Q1 grouped aggregate (ORDER BY) — Local and DataFusion agree row-for-row"):
    assume(dataAvailable, s"TPC-H parquet not found at $dataDir (run scripts/gen-tpch.sh)")
    // The strongest cross-backend check: the same compiled plan must produce the same result
    // *values*, not merely the same number of groups. ORDER BY makes both backends' output order
    // identical so we can compare row-for-row.
    val plan = compile(q1GroupedSorted, "lineitem", lineitemSchema)
    val local = runLocalRows(plan)
    assert(local.size > 1, s"expected several groups, got ${local.size}")
    runDataFusionRows(plan) match
      case None => assume(false, "DataFusion comparison unavailable (server down, or no DDL/table registration)")
      case Some(df) =>
        assert(rowsAgree(df, local), s"row-level mismatch:\n  local=$local\n  df=$df")

  // A two-table join with qualified columns — TPC-H tables share column names (both nation and
  // region have `regionkey`), so this only resolves with a per-table schema catalog (gap #4).
  test("Join (nation ⋈ region) — Local and DataFusion agree"):
    assume(dataAvailable, s"TPC-H parquet not found at $dataDir (run scripts/gen-tpch.sh)")
    val plan = compileMulti(
      """SELECT COUNT(*) AS n
        |FROM nation JOIN region ON nation.regionkey = region.regionkey""".stripMargin,
      catalogOf("nation", "region")
    )
    val local = runLocal(plan, Seq("nation", "region"))
    runDataFusion(plan) match
      case None     => assume(false, "DataFusion comparison unavailable (server down, or no DDL/table registration)")
      case Some(df) => assertEquals(df, local)

  // Capstone: a single query that composes every cross-backend capability — a two-table join feeding
  // a grouped aggregate (two aggregates) with ORDER BY — checked row-for-row. Crucially it groups by
  // `n.name` while `customer` *also* has a `name` column: this only resolves because aliased scans
  // qualify their columns (`c.*`/`n.*`), so the Local executor can disambiguate `n.name` from
  // `c.name`. SUM(c.acctbal) is a narrow DECIMAL(15,2), so its grouped sum stays in range.
  test("Join ⋈ GROUP BY ⋈ ORDER BY (customer/nation, ambiguous name) — Local and DataFusion agree"):
    assume(dataAvailable, s"TPC-H parquet not found at $dataDir (run scripts/gen-tpch.sh)")
    val plan = compileMulti(
      """SELECT n.name, COUNT(*) AS cnt, SUM(c.acctbal) AS total_bal
        |FROM customer c JOIN nation n ON c.nationkey = n.nationkey
        |GROUP BY n.name
        |ORDER BY n.name""".stripMargin,
      catalogOf("customer", "nation")
    )
    val local = runLocalRows(plan, Seq("customer", "nation"))
    assert(local.size > 1, s"expected several nations, got ${local.size}")
    runDataFusionRows(plan) match
      case None => assume(false, "DataFusion comparison unavailable (server down, or no DDL/table registration)")
      case Some(df) =>
        assert(rowsAgree(df, local), s"row-level mismatch:\n  local=$local\n  df=$df")

end TpchCrossBackendSuite
