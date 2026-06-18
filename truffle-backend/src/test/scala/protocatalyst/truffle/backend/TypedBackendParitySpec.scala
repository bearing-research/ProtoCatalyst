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

end TypedBackendParitySpec
