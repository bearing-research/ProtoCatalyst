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
import protocatalyst.plan.{PhysicalPlanner, ProtoLogicalPlan, ProtoPhysicalPlan}
import protocatalyst.schema.ProtoSchema
import protocatalyst.sql.parser.SqlParser
import protocatalyst.sql.transform.AstToProtoTransform

/** Proves the Truffle backend executes *real, optimizer-produced* TPC-H physical plans — the same
  * `ProtoPhysicalPlan` the interpreter runs — not just hand-built fixtures. The shared pipeline is
  * `SQL → AstToProtoTransform → Optimizer → PhysicalPlanner → ProtoPhysicalPlan`; the interpreter and
  * `TypedTruffleCompiler` are two consumers of that one plan. Parity is asserted as equal row counts
  * over the real sf-0.01 parquet (skips when the gitignored data is absent).
  *
  * This is the integration gate for the cross-engine benchmark (cold-start + steady-state vs Spark):
  * if these run and agree, the benchmark can route the identical plan through both engines.
  */
class TpchTruffleParitySpec extends munit.FunSuite:

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

  private def tableDir(t: String): String = s"$dataDir/$t.parquet"
  private def partFiles(t: String): Vector[String] =
    Files.list(Paths.get(tableDir(t))).iterator.asScala
      .map(_.toString)
      .filter(_.endsWith(".parquet"))
      .toVector
      .sorted

  private val dataAvailable = Files.isDirectory(Paths.get(tableDir("lineitem")))

  private def schemaOf(table: String): ProtoSchema = ParquetIO.readSchema(partFiles(table).head)
  private def catalogOf(tables: String*): Map[String, ProtoSchema] =
    tables.map(t => t -> schemaOf(t)).toMap

  private def loadTable(table: String, allocator: BufferAllocator): Batch =
    val parts = partFiles(table)
    val (root, schema) = ParquetIO.read(parts.head, allocator)
    parts.tail.foreach { p =>
      val (r, _) = ParquetIO.read(p, allocator)
      VectorSchemaRootAppender.append(root, r)
      r.close()
    }
    Batch.fromRoot(root, schema)

  /** SQL → optimized logical plan against a multi-table catalog. */
  private def compileLogical(sql: String, catalog: Map[String, ProtoSchema]): ProtoLogicalPlan =
    val stmt = SqlParser.parse(sql).fold(e => fail(s"parse error: $e"), identity)
    val plan = AstToProtoTransform
      .transformStmt(stmt, catalog)
      .fold(e => fail(s"transform error: $e"), identity)
    Optimizer.optimize(plan)

  /** The shared physical plan both engines consume. */
  private def physicalOf(sql: String, tables: Seq[String]): (ProtoPhysicalPlan, Map[String, Batch], RootAllocator) =
    val allocator = new RootAllocator()
    val statsCatalog = new Catalog()
    val batches = tables.map { t =>
      val b = loadTable(t, allocator)
      statsCatalog.registerTable(t, b)
      statsCatalog.registerStatistics(t, ParquetIO.readStatistics(partFiles(t).head))
      t -> b
    }.toMap
    val logical = compileLogical(sql, catalogOf(tables*))
    val physical = PhysicalPlanner(statsCatalog.statsProvider).plan(logical)
    (physical, batches, allocator)

  /** Normalize a cell for cross-engine comparison: numbers (incl. BigDecimal) → Double, else String,
    * NULL → null. Matches how the cross-backend suite compares Local vs DataFusion. */
  private def norm(v: Any): Any = v match
    case null                     => null
    // The interpreter returns a date column as a LocalDate (Arrow getObject); the Truffle backend
    // boxes it as its epoch-day long. Normalize both to the epoch day so a date GROUP BY key / output
    // (TPC-H Q3 `o.orderdate`) compares across engines.
    case d: java.time.LocalDate   => d.toEpochDay.toDouble
    case n: java.lang.Number      => n.doubleValue
    case other                    => other.toString

  private def runInterpRows(physical: ProtoPhysicalPlan, batches: Map[String, Batch], allocator: RootAllocator): Vector[Vector[Any]] =
    val catalog = new Catalog()
    batches.foreach((t, b) => catalog.registerTable(t, b))
    val batch = PhysicalPlanExecutor(catalog, allocator).execute(physical)
    (0 until batch.rowCount).map { r =>
      (0 until batch.numColumns).map(c => norm(batch.column(c).getObject(r))).toVector
    }.toVector

  private def runTruffleRows(physical: ProtoPhysicalPlan, batches: Map[String, Batch]): Vector[Vector[Any]] =
    TypedTruffleCompiler.compile(physical).run(batches).rows.map(_.map(norm))

  /** Row-for-row equality with a relative tolerance on numeric cells (Double-vs-Decimal rounding),
    * order-insensitive (group/join order differs by engine). */
  private def rowsAgree(a: Vector[Vector[Any]], b: Vector[Vector[Any]]): Boolean =
    val sa = a.sortBy(_.mkString("|"))
    val sb = b.sortBy(_.mkString("|"))
    sa.size == sb.size && sa.zip(sb).forall { (ra, rb) =>
      ra.size == rb.size && ra.zip(rb).forall {
        case (x: Double, y: Double) => math.abs(x - y) <= 1e-6 * math.max(1.0, math.abs(x))
        case (x, y)                 => x == y
      }
    }

  private def checkParity(sql: String, tables: Seq[String]): Unit =
    val (physical, batches, allocator) = physicalOf(sql, tables)
    val interp = runInterpRows(physical, batches, allocator)
    val truffle = runTruffleRows(physical, batches)
    assert(interp.nonEmpty, s"interpreter produced no rows — query is vacuous:\n$sql")
    assertEquals(truffle.size, interp.size, s"row count for:\n$sql\nplan: $physical")
    assert(rowsAgree(truffle, interp), s"row-level mismatch for:\n$sql\n  interp=${interp.take(5)}\n  truffle=${truffle.take(5)}")

  test("Q6 selection (filter+project) — Truffle runs the optimizer plan, agrees with interpreter"):
    assume(dataAvailable, s"TPC-H parquet not found at $dataDir (run scripts/gen-tpch.sh)")
    checkParity(
      """SELECT orderkey, extendedprice, discount
        |FROM lineitem
        |WHERE discount BETWEEN 0.05 AND 0.07 AND quantity < 24""".stripMargin,
      Seq("lineitem")
    )

  test("Q6 aggregate (global SUM revenue, double-cast) — Truffle agrees with interpreter"):
    assume(dataAvailable, s"TPC-H parquet not found at $dataDir (run scripts/gen-tpch.sh)")
    checkParity(
      """SELECT SUM(CAST(extendedprice AS DOUBLE) * CAST(discount AS DOUBLE)) AS revenue
        |FROM lineitem
        |WHERE shipdate >= DATE '1994-01-01' AND shipdate < DATE '1995-01-01'
        |  AND discount BETWEEN 0.05 AND 0.07 AND quantity < 24""".stripMargin,
      Seq("lineitem")
    )

  test("Q1-style grouped aggregate (GROUP BY + ORDER BY) — Truffle agrees with interpreter"):
    assume(dataAvailable, s"TPC-H parquet not found at $dataDir (run scripts/gen-tpch.sh)")
    checkParity(
      """SELECT returnflag, linestatus, COUNT(*) AS cnt, SUM(quantity) AS sum_qty
        |FROM lineitem
        |GROUP BY returnflag, linestatus
        |ORDER BY returnflag, linestatus""".stripMargin,
      Seq("lineitem")
    )

  test("Two-table join (customer ⋈ nation) GROUP BY — Truffle agrees with interpreter"):
    assume(dataAvailable, s"TPC-H parquet not found at $dataDir (run scripts/gen-tpch.sh)")
    checkParity(
      """SELECT n.name, COUNT(*) AS cnt, SUM(c.acctbal) AS total_bal
        |FROM customer c JOIN nation n ON c.nationkey = n.nationkey
        |GROUP BY n.name
        |ORDER BY n.name""".stripMargin,
      Seq("customer", "nation")
    )

  test("TPC-H Q3 (3-join + SUM(expr) + GROUP BY + ORDER BY + LIMIT) — Truffle agrees"):
    assume(dataAvailable, s"TPC-H parquet not found at $dataDir (run scripts/gen-tpch.sh)")
    checkParity(
      """SELECT l.orderkey,
        |       SUM(CAST(l.extendedprice AS DOUBLE) * (1 - CAST(l.discount AS DOUBLE))) AS revenue,
        |       o.orderdate, o.shippriority
        |FROM customer c
        |JOIN orders o ON c.custkey = o.custkey
        |JOIN lineitem l ON l.orderkey = o.orderkey
        |WHERE c.mktsegment = 'BUILDING'
        |  AND o.orderdate < DATE '1995-03-15'
        |  AND l.shipdate > DATE '1995-03-15'
        |GROUP BY l.orderkey, o.orderdate, o.shippriority
        |ORDER BY revenue DESC, o.orderdate
        |LIMIT 10""".stripMargin,
      Seq("customer", "orders", "lineitem")
    )

  test("TPC-H Q5 (6-join + SUM(expr) + GROUP BY + ORDER BY) — Truffle agrees"):
    assume(dataAvailable, s"TPC-H parquet not found at $dataDir (run scripts/gen-tpch.sh)")
    checkParity(
      """SELECT n.name,
        |       SUM(CAST(l.extendedprice AS DOUBLE) * (1 - CAST(l.discount AS DOUBLE))) AS revenue
        |FROM customer c
        |JOIN orders o ON c.custkey = o.custkey
        |JOIN lineitem l ON l.orderkey = o.orderkey
        |JOIN supplier s ON l.suppkey = s.suppkey AND c.nationkey = s.nationkey
        |JOIN nation n ON s.nationkey = n.nationkey
        |JOIN region r ON n.regionkey = r.regionkey
        |WHERE r.name = 'ASIA'
        |  AND o.orderdate >= DATE '1994-01-01'
        |  AND o.orderdate < DATE '1995-01-01'
        |GROUP BY n.name
        |ORDER BY revenue DESC""".stripMargin,
      Seq("customer", "orders", "lineitem", "supplier", "nation", "region")
    )

  test("TPC-H Q10 (4-join + SUM(expr) + GROUP BY + ORDER BY + LIMIT) — Truffle agrees"):
    assume(dataAvailable, s"TPC-H parquet not found at $dataDir (run scripts/gen-tpch.sh)")
    checkParity(
      """SELECT c.custkey, c.name,
        |       SUM(CAST(l.extendedprice AS DOUBLE) * (1 - CAST(l.discount AS DOUBLE))) AS revenue,
        |       c.acctbal, n.name AS nation, c.address, c.phone, c.comment
        |FROM customer c
        |JOIN orders o ON c.custkey = o.custkey
        |JOIN lineitem l ON l.orderkey = o.orderkey
        |JOIN nation n ON c.nationkey = n.nationkey
        |WHERE o.orderdate >= DATE '1993-10-01'
        |  AND o.orderdate < DATE '1994-01-01'
        |  AND l.returnflag = 'R'
        |GROUP BY c.custkey, c.name, c.acctbal, c.phone, n.name, c.address, c.comment
        |ORDER BY revenue DESC
        |LIMIT 20""".stripMargin,
      Seq("customer", "orders", "lineitem", "nation")
    )

end TpchTruffleParitySpec
