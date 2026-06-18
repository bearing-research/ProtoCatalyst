package protocatalyst.truffle.backend

import scala.jdk.CollectionConverters.*

import munit.FunSuite
import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.apache.arrow.vector.{BigIntVector, FieldVector, Float8Vector, VectorSchemaRoot}

import protocatalyst.executor.Catalog
import protocatalyst.executor.exec.Batch
import protocatalyst.executor.physical.PhysicalPlanExecutor
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan.{ProtoPhysicalPlan, Statistics}
import protocatalyst.schema.ProtoSchema
import protocatalyst.types.{LiteralValue, ProtoStructField, ProtoType}

/** Layer-1 integration parity: a real `Filter` plan over a **nullable** column, run through the typed
  * backend ([[TypedTruffleCompiler]]) vs the Scala interpreter — and contrasted with the double-only
  * backend ([[ProtoTruffleCompiler]]) to show the typed model fixes a genuine correctness bug.
  *
  * Data: `discount` is NULL on every 4th row, else 0.3. Filter `discount < 0.5`:
  *   - Interpreter + typed backend: NULL fails the predicate (3VL) → only non-null rows pass.
  *   - Double-only backend: it has no null model — its decode calls Arrow's `get()`, which throws on a
  *     null cell. So it can't even run on nullable data; the typed layer is what makes it possible.
  */
class TypedBackendParitySpec extends FunSuite:

  private val n = 8

  private def buildBatch(alloc: BufferAllocator): (Batch, ProtoSchema, Int) =
    val orderkey = new BigIntVector("orderkey", alloc)
    val discount = new Float8Vector("discount", alloc)
    Seq(orderkey, discount).foreach(_.allocateNew(n))
    var nullCount = 0
    var i = 0
    while i < n do
      orderkey.set(i, i.toLong)
      if i % 4 == 0 then
        discount.setNull(i)
        nullCount += 1
      else discount.set(i, 0.3)
      i += 1
    Seq(orderkey, discount).foreach(_.setValueCount(n))
    val vecs: java.util.List[FieldVector] = List[FieldVector](orderkey, discount).asJava
    val root = new VectorSchemaRoot(vecs)
    root.setRowCount(n)
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("orderkey", ProtoType.LongType, false),
        ProtoStructField("discount", ProtoType.DoubleType, true)
      )
    )
    (Batch.fromRoot(root, schema), schema, nullCount)

  private def filterPlan(schema: ProtoSchema): ProtoPhysicalPlan =
    val cond = ProtoExpr.Lt(
      ProtoExpr.ColumnRef("discount", None, ProtoType.DoubleType, true),
      ProtoExpr.Literal(LiteralValue.DoubleValue(0.5))
    )
    val scan = ProtoPhysicalPlan.TableScan("t", None, schema, Statistics(n.toLong, n * 64L))
    ProtoPhysicalPlan.PhysicalFilter(cond, scan)

  /** Project (orderkey, discount) over orderkey < 100 — all rows pass, so the NULL discount cells
    * survive into the output and must be preserved. */
  private def projectPlan(schema: ProtoSchema): ProtoPhysicalPlan =
    val cond = ProtoExpr.Lt(
      ProtoExpr.ColumnRef("orderkey", None, ProtoType.LongType, false),
      ProtoExpr.Literal(LiteralValue.LongValue(100L))
    )
    val scan = ProtoPhysicalPlan.TableScan("t", None, schema, Statistics(n.toLong, n * 64L))
    val proj = Vector(
      ProtoExpr.ColumnRef("orderkey", None, ProtoType.LongType, false),
      ProtoExpr.ColumnRef("discount", None, ProtoType.DoubleType, true)
    )
    ProtoPhysicalPlan.PhysicalProject(proj, ProtoPhysicalPlan.PhysicalFilter(cond, scan))

  /** Global SUM(discount) over the rows with orderkey < threshold (no GROUP BY). */
  private def sumPlan(schema: ProtoSchema, threshold: Long): ProtoPhysicalPlan =
    val cond = ProtoExpr.Lt(
      ProtoExpr.ColumnRef("orderkey", None, ProtoType.LongType, false),
      ProtoExpr.Literal(LiteralValue.LongValue(threshold))
    )
    val scan = ProtoPhysicalPlan.TableScan("t", None, schema, Statistics(n.toLong, n * 64L))
    val agg = ProtoExpr.Alias(
      ProtoExpr.Sum(ProtoExpr.ColumnRef("discount", None, ProtoType.DoubleType, true)),
      "s"
    )
    ProtoPhysicalPlan.HashAggregate(Vector.empty, Vector(agg), ProtoPhysicalPlan.PhysicalFilter(cond, scan))

  private def readRows(b: Batch): Vector[Vector[Any]] =
    (0 until b.rowCount).map { r =>
      (0 until b.numColumns).map { c =>
        b.column(c).getObject(r) match
          case null                => null
          case x: java.lang.Number => x.doubleValue: Any
          case other               => other.toString: Any
      }.toVector
    }.toVector

  private def rowsAgree(a: Vector[Vector[Any]], b: Vector[Vector[Any]]): Boolean =
    a.size == b.size && a.zip(b).forall { (ra, rb) =>
      ra.size == rb.size && ra.zip(rb).forall {
        case (null, null)           => true
        case (x: Double, y: Double) => math.abs(x - y) <= 1e-6 * math.max(1.0, math.abs(x))
        case (x, y)                 => x == y
      }
    }

  test("Filter over a NULLable column: typed backend matches the interpreter; double-only cannot"):
    val alloc = new RootAllocator()
    val (batch, schema, nullCount) = buildBatch(alloc)
    val plan = filterPlan(schema)
    assert(nullCount > 0, "test needs some NULLs")

    val catalog = new Catalog()
    catalog.registerTable("t", batch)
    catalog.registerStatistics("t", Statistics(n.toLong, n * 64L))
    val localCount = PhysicalPlanExecutor(catalog, alloc).execute(plan).rowCount.toLong

    val typedCount = TypedTruffleCompiler.compileFilterCount(plan).count(batch)

    // The interpreter is the oracle: NULL fails `< 0.5`, so only the non-null rows pass.
    assertEquals(typedCount, localCount, "typed backend must match the interpreter's 3VL")
    assertEquals(typedCount, (n - nullCount).toLong)

    // The double-only backend has no null model — running it on nullable data throws.
    intercept[Exception] {
      ProtoTruffleCompiler.compile(plan).run(batch)
    }

  test("typed projection preserves NULLs in the output and agrees with the interpreter"):
    val alloc = new RootAllocator()
    val (batch, schema, nullCount) = buildBatch(alloc)
    val plan = projectPlan(schema)
    assert(nullCount > 0, "test needs some NULLs")

    val catalog = new Catalog()
    catalog.registerTable("t", batch)
    catalog.registerStatistics("t", Statistics(n.toLong, n * 64L))
    val localRows = readRows(PhysicalPlanExecutor(catalog, alloc).execute(plan))

    val typedRows = TypedTruffleCompiler.compileFilterProject(plan).run(batch).rows

    assertEquals(localRows.size, n, "orderkey < 100 keeps every row")
    assert(localRows.exists(_(1) == null), "some projected discount cells must be NULL")
    assertEquals(typedRows.size, localRows.size)
    assert(rowsAgree(localRows, typedRows), "typed projection must agree, NULLs included")

  test("typed SUM ignores NULL inputs and agrees with the interpreter"):
    val alloc = new RootAllocator()
    val (batch, schema, nullCount) = buildBatch(alloc)
    val plan = sumPlan(schema, 100L) // all rows pass; SUM(discount) over non-null discounts
    assert(nullCount > 0)

    val catalog = new Catalog()
    catalog.registerTable("t", batch)
    catalog.registerStatistics("t", Statistics(n.toLong, n * 64L))
    val localRows = readRows(PhysicalPlanExecutor(catalog, alloc).execute(plan))

    val typedRows = TypedTruffleCompiler.compile(plan).run(batch).rows
    assertEquals(typedRows.size, 1)
    assert(typedRows.head.head != null, "SUM over some non-null values is not NULL")
    assert(rowsAgree(localRows, typedRows), "typed SUM must agree with the interpreter")

  test("typed SUM over zero rows is NULL (not 0) and agrees with the interpreter"):
    val alloc = new RootAllocator()
    val (batch, schema, _) = buildBatch(alloc)
    val plan = sumPlan(schema, 0L) // orderkey < 0 keeps nothing → SUM of empty = NULL

    val catalog = new Catalog()
    catalog.registerTable("t", batch)
    catalog.registerStatistics("t", Statistics(n.toLong, n * 64L))
    val localRows = readRows(PhysicalPlanExecutor(catalog, alloc).execute(plan))

    val typedRows = TypedTruffleCompiler.compile(plan).run(batch).rows
    assertEquals(typedRows.size, 1)
    assertEquals(typedRows.head.head, null, "SQL SUM of empty input is NULL")
    assert(rowsAgree(localRows, typedRows))

end TypedBackendParitySpec
