package protocatalyst.truffle.backend

import java.nio.file.{Files, Paths}

import scala.jdk.CollectionConverters.*

import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.apache.arrow.vector.util.VectorSchemaRootAppender

import protocatalyst.arrow.parquet.ParquetIO
import protocatalyst.executor.Catalog
import protocatalyst.executor.exec.Batch
import protocatalyst.executor.physical.PhysicalPlanExecutor
import protocatalyst.optimizer.Optimizer
import protocatalyst.plan.{PhysicalPlanner, ProtoPhysicalPlan}
import protocatalyst.schema.ProtoSchema
import protocatalyst.sql.parser.SqlParser
import protocatalyst.sql.transform.AstToProtoTransform

/** Exercises the **parallel scan** path by forcing `ParallelScan.threshold` low so the sf-0.01 fixture
  * (~60k lineitem rows) is split across threads, and checks the parallel result equals the serial
  * interpreter's — i.e. row-slicing + per-slice fresh-AST execution + merge (Concat for filter/project,
  * key-combine for aggregates, sum for count) is order-insensitively identical to single-threaded.
  * The other suites run below threshold (serial); this is the only one that drives the parallel code.
  */
class TypedParallelScanSpec extends munit.FunSuite:

  private val dataDir: String =
    val rel = Paths.get("data", "tpch", "sf-0.01-parquet")
    Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null).take(6)
      .map(_.resolve(rel)).find(p => Files.isDirectory(p.resolve("lineitem.parquet")))
      .map(_.toString).getOrElse(rel.toString)
  private val dataAvailable = Files.isDirectory(Paths.get(s"$dataDir/lineitem.parquet"))

  private def partFiles(t: String): Vector[String] =
    Files.list(Paths.get(s"$dataDir/$t.parquet")).iterator.asScala
      .map(_.toString).filter(_.endsWith(".parquet")).toVector.sorted
  private def schemaOf(t: String): ProtoSchema = ParquetIO.readSchema(partFiles(t).head)
  private def loadTable(t: String, a: BufferAllocator): Batch =
    val parts = partFiles(t)
    val (root, schema) = ParquetIO.read(parts.head, a)
    parts.tail.foreach { p => val (r, _) = ParquetIO.read(p, a); VectorSchemaRootAppender.append(root, r); r.close() }
    Batch.fromRoot(root, schema)

  private def physicalOf(sql: String, tables: Seq[String]): ProtoPhysicalPlan =
    val cat = tables.map(t => t -> schemaOf(t)).toMap
    val stmt = SqlParser.parse(sql).fold(e => fail(s"parse: $e"), identity)
    val logical = AstToProtoTransform.transformStmt(stmt, cat).fold(e => fail(s"transform: $e"), identity)
    val stats = new Catalog()
    tables.foreach(t => stats.registerStatistics(t, ParquetIO.readStatistics(partFiles(t).head)))
    PhysicalPlanner(stats.statsProvider).plan(Optimizer.optimize(logical))

  private def norm(v: Any): Any = v match
    case null                    => null
    case d: java.time.LocalDate  => d.toEpochDay.toDouble
    case n: java.lang.Number     => n.doubleValue
    case other                   => other.toString

  private def interpRows(physical: ProtoPhysicalPlan, batches: Map[String, Batch], a: RootAllocator): Vector[Vector[Any]] =
    val cat = new Catalog(); batches.foreach((t, b) => cat.registerTable(t, b))
    val batch = PhysicalPlanExecutor(cat, a).execute(physical)
    (0 until batch.rowCount).map(r => (0 until batch.numColumns).map(c => norm(batch.column(c).getObject(r))).toVector).toVector

  private def truffleRows(physical: ProtoPhysicalPlan, batches: Map[String, Batch]): Vector[Vector[Any]] =
    TypedTruffleCompiler.compile(physical).run(batches).rows.map(_.map(norm))

  /** Order-insensitive, column-order-insensitive (front-end gap, see TpchSparkOracleSpec), tolerant. */
  private def agree(a: Vector[Vector[Any]], b: Vector[Vector[Any]]): Boolean =
    def cell(x: Any) = x match { case d: Double => f"$d%.4f"; case null => "_"; case o => o.toString }
    def key(rs: Vector[Vector[Any]]) = rs.map(_.map(cell).sorted.mkString("|")).sorted
    a.size == b.size && key(a) == key(b)

  private def forceParallel[A](body: => A): A =
    val saved = ParallelScan.threshold
    ParallelScan.threshold = 1
    try body finally ParallelScan.threshold = saved

  private def checkParallelMatchesSerial(sql: String, tables: Seq[String]): Unit =
    val alloc = new RootAllocator()
    val batches = tables.map(t => t -> loadTable(t, alloc)).toMap
    val physical = physicalOf(sql, tables)
    val serial = interpRows(physical, batches, alloc)
    val parallel = forceParallel(truffleRows(physical, batches))
    assert(parallel.nonEmpty, s"empty result for: $sql")
    assert(agree(parallel, serial), s"parallel ≠ serial for:\n$sql\n  serial=${serial.size} parallel=${parallel.size}")

  test("parallel global aggregate (Q6 SUM) matches serial"):
    assume(dataAvailable, s"TPC-H parquet not found at $dataDir")
    checkParallelMatchesSerial(
      """SELECT SUM(CAST(extendedprice AS DOUBLE) * CAST(discount AS DOUBLE)) AS revenue
        |FROM lineitem WHERE discount BETWEEN 0.05 AND 0.07 AND quantity < 24""".stripMargin,
      Seq("lineitem"))

  test("parallel grouped aggregate (Q1 SUM/COUNT) matches serial"):
    assume(dataAvailable, s"TPC-H parquet not found at $dataDir")
    checkParallelMatchesSerial(
      """SELECT returnflag, linestatus, COUNT(*) AS cnt, SUM(CAST(quantity AS DOUBLE)) AS sum_qty
        |FROM lineitem GROUP BY returnflag, linestatus""".stripMargin,
      Seq("lineitem"))

  test("parallel grouped MIN/MAX matches serial"):
    assume(dataAvailable, s"TPC-H parquet not found at $dataDir")
    checkParallelMatchesSerial(
      """SELECT returnflag, MIN(CAST(extendedprice AS DOUBLE)) AS lo, MAX(CAST(extendedprice AS DOUBLE)) AS hi
        |FROM lineitem GROUP BY returnflag""".stripMargin,
      Seq("lineitem"))

  test("parallel filter+project (Concat merge) matches serial"):
    assume(dataAvailable, s"TPC-H parquet not found at $dataDir")
    checkParallelMatchesSerial(
      """SELECT orderkey, extendedprice, discount
        |FROM lineitem WHERE discount BETWEEN 0.05 AND 0.07 AND quantity < 24""".stripMargin,
      Seq("lineitem"))

  test("parallel filter count matches serial"):
    assume(dataAvailable, s"TPC-H parquet not found at $dataDir")
    val alloc = new RootAllocator()
    val batch = loadTable("lineitem", alloc)
    val plan = physicalOf(
      "SELECT orderkey FROM lineitem WHERE discount BETWEEN 0.05 AND 0.07 AND quantity < 24",
      Seq("lineitem"))
    // Filter-count path: a PhysicalFilter over the scan (drop the project the planner added on top).
    val filterPlan = plan match
      case ProtoPhysicalPlan.PhysicalProject(_, child) => child
      case other                                       => other
    val cat = new Catalog(); cat.registerTable("lineitem", batch)
    val serial = PhysicalPlanExecutor(cat, alloc).execute(filterPlan).rowCount.toLong
    val parallel = forceParallel(TypedTruffleCompiler.compileFilterCount(filterPlan).count(batch))
    assert(parallel > 0, "expected some surviving rows")
    assertEquals(parallel, serial, "parallel count must equal serial")

end TypedParallelScanSpec
