package protocatalyst.truffle.backend

import scala.jdk.CollectionConverters.*

import munit.FunSuite
import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.apache.arrow.vector.{BigIntVector, FieldVector, VectorSchemaRoot}

import protocatalyst.executor.Catalog
import protocatalyst.executor.exec.Batch
import protocatalyst.executor.physical.PhysicalPlanExecutor
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan.{NullOrdering, ProtoPhysicalPlan, SortDirection, SortOrder, Statistics}
import protocatalyst.schema.ProtoSchema
import protocatalyst.types.{LiteralValue, ProtoStructField, ProtoType}

/** Layer-3 ranking window functions over PARTITION BY + ORDER BY, parity-checked against the
  * interpreter. `category` = orderkey/3 (3 partitions of 3); `score` has ties within partitions so
  * RANK/DENSE_RANK differ from ROW_NUMBER. Results are sorted by the unique `orderkey` to compare. */
class TypedWindowSpec extends FunSuite:

  private val n = 9

  private def buildBatch(alloc: BufferAllocator): (Batch, ProtoSchema) =
    val orderkey = new BigIntVector("orderkey", alloc)
    val category = new BigIntVector("category", alloc)
    val score = new BigIntVector("score", alloc)
    val scores = Seq(10L, 10L, 20L, 5L, 15L, 15L, 30L, 30L, 30L)
    Seq(orderkey, category, score).foreach(_.allocateNew(n))
    var i = 0
    while i < n do
      orderkey.set(i, i.toLong)
      category.set(i, (i / 3).toLong)
      score.set(i, scores(i))
      i += 1
    Seq(orderkey, category, score).foreach(_.setValueCount(n))
    val vecs: java.util.List[FieldVector] = List[FieldVector](orderkey, category, score).asJava
    val root = new VectorSchemaRoot(vecs)
    root.setRowCount(n)
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("orderkey", ProtoType.LongType, false),
        ProtoStructField("category", ProtoType.LongType, false),
        ProtoStructField("score", ProtoType.LongType, false)
      )
    )
    (Batch.fromRoot(root, schema), schema)

  private def colRef(name: String) = ProtoExpr.ColumnRef(name, None, ProtoType.LongType, false)
  private def asc(name: String) = SortOrder(colRef(name), SortDirection.Ascending, NullOrdering.NullsLast)

  private def readRows(b: Batch): Vector[Vector[Any]] =
    (0 until b.rowCount).map { r =>
      (0 until b.numColumns).map { c =>
        b.column(c).getObject(r) match
          case null                => null
          case x: java.lang.Number => x.doubleValue: Any
          case other               => other.toString: Any
      }.toVector
    }.toVector

  private def checkWindow(plan: ProtoPhysicalPlan): Unit =
    val alloc = new RootAllocator()
    val (batch, schema) = buildBatch(alloc)
    val catalog = new Catalog()
    catalog.registerTable("t", batch)
    catalog.registerStatistics("t", Statistics(n.toLong, n * 64L))
    val localRows = readRows(PhysicalPlanExecutor(catalog, alloc).execute(plan)).sortBy(_.head.asInstanceOf[Double])
    val typedRows = TypedTruffleCompiler.compile(plan).run(batch).rows.sortBy(_.head.asInstanceOf[Double])
    assertEquals(typedRows.size, n)
    assertEquals(typedRows, localRows, s"window output must match the interpreter")

  private def windowPlan(schema: ProtoSchema, exprs: Vector[ProtoExpr], orderCol: String): ProtoPhysicalPlan =
    ProtoPhysicalPlan.PhysicalWindow(
      exprs,
      Vector(colRef("category")),
      Vector(asc(orderCol)),
      ProtoPhysicalPlan.TableScan("t", None, schema, Statistics(n.toLong, n * 64L))
    )

  test("ROW_NUMBER() OVER (PARTITION BY category ORDER BY orderkey)"):
    val (_, schema) = buildBatch(new RootAllocator())
    checkWindow(windowPlan(schema, Vector(ProtoExpr.Alias(ProtoExpr.RowNumber(), "rn")), "orderkey"))

  test("RANK() and DENSE_RANK() OVER (PARTITION BY category ORDER BY score) — with ties"):
    val (_, schema) = buildBatch(new RootAllocator())
    // The interpreter derives the tie order from the WindowExpr's own spec (read one level deep, so no
    // outer Alias). Our parseWindowFn unwraps WindowExpr to the same function.
    val part = Vector(colRef("category"))
    val ord = Vector(asc("score"))
    val exprs = Vector(
      ProtoExpr.WindowExpr(ProtoExpr.Rank(), part, ord, None),
      ProtoExpr.WindowExpr(ProtoExpr.DenseRank(), part, ord, None)
    )
    checkWindow(windowPlan(schema, exprs, "score"))

  test("aggregate windows: SUM/COUNT/AVG/MIN/MAX(score) OVER (PARTITION BY category)"):
    val (_, schema) = buildBatch(new RootAllocator())
    val score = colRef("score")
    val exprs = Vector(
      ProtoExpr.Alias(ProtoExpr.Sum(score), "sm"),
      ProtoExpr.Alias(ProtoExpr.Count(ProtoExpr.Literal(LiteralValue.IntValue(1)), false), "cnt"),
      ProtoExpr.Alias(ProtoExpr.Avg(score), "av"),
      ProtoExpr.Alias(ProtoExpr.Min(score), "mn"),
      ProtoExpr.Alias(ProtoExpr.Max(score), "mx")
    )
    val plan = ProtoPhysicalPlan.PhysicalWindow(
      exprs,
      Vector(colRef("category")),
      Vector.empty,
      ProtoPhysicalPlan.TableScan("t", None, schema, Statistics(n.toLong, n * 64L))
    )
    checkWindow(plan)

  test("LAG/LEAD/NTILE OVER (PARTITION BY category ORDER BY orderkey)"):
    val (_, schema) = buildBatch(new RootAllocator())
    val score = colRef("score")
    val one = ProtoExpr.Literal(LiteralValue.IntValue(1))
    val exprs = Vector(
      ProtoExpr.Alias(ProtoExpr.Lag(score, one, None), "lg"),
      ProtoExpr.Alias(ProtoExpr.Lead(score, one, None), "ld"),
      ProtoExpr.Alias(ProtoExpr.Ntile(ProtoExpr.Literal(LiteralValue.IntValue(2))), "nt")
    )
    checkWindow(windowPlan(schema, exprs, "orderkey"))

end TypedWindowSpec
