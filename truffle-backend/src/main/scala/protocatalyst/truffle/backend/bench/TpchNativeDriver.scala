package protocatalyst.truffle.backend.bench

import java.io.FileInputStream
import java.nio.file.{Files, Paths}

import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.apache.arrow.vector.ipc.ArrowFileReader

import protocatalyst.arrow.ArrowSchemaConverter
import protocatalyst.executor.Catalog
import protocatalyst.executor.exec.Batch
import protocatalyst.executor.physical.PhysicalPlanExecutor
import protocatalyst.optimizer.Optimizer
import protocatalyst.plan.{PhysicalPlanner, ProtoPhysicalPlan, Statistics}
import protocatalyst.sql.parser.SqlParser
import protocatalyst.sql.transform.AstToProtoTransform
import protocatalyst.truffle.backend.TypedTruffleCompiler

/** Cold-start driver and **native-image entrypoint** for the full query pipeline: takes a SQL query
  * (one of the benchmark slice, by name), parses → optimizes → physical-plans → executes it via either
  * the Truffle backend or the interpreter, over Arrow IPC tables, and prints the **first-result wall
  * time** (process-logic start → result).
  *
  * Unlike `Q6Native` (a single hardcoded plan over synthetic arrays), this exercises the *entire*
  * compiler at runtime — the analogue of `spark.sql(userInput)`. The point of native-imaging it is the
  * existence proof: a runtime-parsed, runtime-planned SQL query executes in a closed-world native
  * binary with **no runtime bytecode generation**, partial evaluation intact.
  *
  * JVM:    `sbt 'truffleBackend/runMain …bench.TpchNativeDriver --query Q6 --sf 0.01 --engine truffle'`
  * Native: build via the recipe in docs/compiler (native-image over truffleBackend's runtime cp),
  *         then `./tpch-native --query Q6 --sf 0.01 --engine truffle`.
  *
  * Data: Arrow IPC under `data/tpch/sf-$sf-arrow/` (produce with `TpchArrowConvert`). Parquet is
  * avoided on purpose — parquet-mr/Hadoop do not native-image.
  */
object TpchNativeDriver:

  def main(args: Array[String]): Unit =
    val t0 = System.nanoTime()
    val query = argOr(args, "--query", "Q6")
    val sf = argOr(args, "--sf", "0.01")
    val engine = argOr(args, "--engine", "truffle")
    val q = Tpch.byName(query)

    val allocator = new RootAllocator()
    val arrowDir = locateArrow(sf)
    val batches = q.tables.map(t => t -> loadIpc(s"$arrowDir/$t.arrow", allocator)).toMap

    val physical = planOf(q.sql, batches)
    val rows =
      if engine == "interp" then runInterp(physical, batches, allocator)
      else runTruffle(physical, batches)

    val ms = (System.nanoTime() - t0) / 1e6
    println(s"engine=$engine query=$query sf=$sf rows=$rows")
    println(f"first-result wall: $ms%.2f ms")

  private def planOf(sql: String, batches: Map[String, Batch]): ProtoPhysicalPlan =
    val catalog = batches.map((t, b) => t -> b.schema)
    val stmt = SqlParser.parse(sql).fold(e => sys.error(s"parse: $e"), identity)
    val logical = AstToProtoTransform.transformStmt(stmt, catalog).fold(e => sys.error(s"transform: $e"), identity)
    val stats = new Catalog()
    batches.foreach((t, b) => stats.registerStatistics(t, Statistics(b.rowCount.toLong, b.rowCount.toLong * 64L)))
    PhysicalPlanner(stats.statsProvider).plan(Optimizer.optimize(logical))

  private def runInterp(physical: ProtoPhysicalPlan, batches: Map[String, Batch], alloc: BufferAllocator): Long =
    val catalog = new Catalog()
    batches.foreach((t, b) => catalog.registerTable(t, b))
    PhysicalPlanExecutor(catalog, alloc).execute(physical).rowCount.toLong

  private def runTruffle(physical: ProtoPhysicalPlan, batches: Map[String, Batch]): Long =
    TypedTruffleCompiler.compile(physical).run(batches).rows.size.toLong

  /** Read a single-record-batch Arrow IPC file into a Batch (reader left open; freed at process exit). */
  private def loadIpc(path: String, allocator: BufferAllocator): Batch =
    val in = new FileInputStream(path)
    val reader = new ArrowFileReader(in.getChannel, allocator)
    reader.loadNextBatch()
    val root = reader.getVectorSchemaRoot
    val schema = ArrowSchemaConverter.fromArrowSchema(root.getSchema)
    Batch.fromRoot(root, schema)

  private def locateArrow(sf: String): String =
    val rel = Paths.get("data", "tpch", s"sf-$sf-arrow")
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .take(6)
      .map(_.resolve(rel))
      .find(p => Files.isRegularFile(p.resolve("lineitem.arrow")))
      .map(_.toString)
      .getOrElse(sys.error(s"Arrow IPC not found ($rel); run TpchArrowConvert --sf $sf"))

  private def argOr(args: Array[String], flag: String, default: String): String =
    val i = args.indexOf(flag); if i >= 0 && i + 1 < args.length then args(i + 1) else default
