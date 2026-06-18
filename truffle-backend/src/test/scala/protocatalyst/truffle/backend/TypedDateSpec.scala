package protocatalyst.truffle.backend

import java.time.LocalDate

import scala.jdk.CollectionConverters.*

import munit.FunSuite
import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.apache.arrow.vector.{BigIntVector, DateDayVector, FieldVector, VectorSchemaRoot}

import protocatalyst.executor.Catalog
import protocatalyst.executor.exec.Batch
import protocatalyst.executor.physical.PhysicalPlanExecutor
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan.{ProtoPhysicalPlan, Statistics}
import protocatalyst.schema.ProtoSchema
import protocatalyst.types.{LiteralValue, ProtoStructField, ProtoType}

/** Layer-1 date column kind: a date-range filter (`shipdate >= DATE '1994-01-01'`) parity-checked
  * against the interpreter. Dates flow internally as `long` epoch-days, so comparison is exact; this
  * is the common date use (range predicates). `shipdate` is NULL on every 4th row.
  *
  * Date *projection output* (epoch-long vs the interpreter's date object) is a normalization boundary
  * left out of scope — comparison/filtering is what TPC-H date predicates need. */
class TypedDateSpec extends FunSuite:

  private val n = 10
  private val epoch1994 = LocalDate.of(1994, 1, 1).toEpochDay.toInt

  private def buildBatch(alloc: BufferAllocator): (Batch, ProtoSchema) =
    val orderkey = new BigIntVector("orderkey", alloc)
    val shipdate = new DateDayVector("shipdate", alloc)
    orderkey.allocateNew(n)
    shipdate.allocateNew(n)
    var i = 0
    while i < n do
      orderkey.set(i, i.toLong)
      if i % 4 == 0 then shipdate.setNull(i)
      else shipdate.set(i, epoch1994 - 120 + i * 30) // straddles the 1994-01-01 boundary
      i += 1
    orderkey.setValueCount(n)
    shipdate.setValueCount(n)
    val vecs: java.util.List[FieldVector] = List[FieldVector](orderkey, shipdate).asJava
    val root = new VectorSchemaRoot(vecs)
    root.setRowCount(n)
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("orderkey", ProtoType.LongType, false),
        ProtoStructField("shipdate", ProtoType.DateType, true)
      )
    )
    (Batch.fromRoot(root, schema), schema)

  test("date-range filter (shipdate >= DATE '1994-01-01') matches the interpreter, NULLs excluded"):
    val alloc = new RootAllocator()
    val (batch, schema) = buildBatch(alloc)
    val cond = ProtoExpr.GtEq(
      ProtoExpr.ColumnRef("shipdate", None, ProtoType.DateType, true),
      ProtoExpr.Literal(LiteralValue.DateValue(epoch1994))
    )
    val plan =
      ProtoPhysicalPlan.PhysicalFilter(cond, ProtoPhysicalPlan.TableScan("t", None, schema, Statistics(n.toLong, n * 64L)))

    val catalog = new Catalog()
    catalog.registerTable("t", batch)
    catalog.registerStatistics("t", Statistics(n.toLong, n * 64L))
    val localCount = PhysicalPlanExecutor(catalog, alloc).execute(plan).rowCount.toLong

    val typedCount = TypedTruffleCompiler.compileFilterCount(plan).count(batch)
    assertEquals(typedCount, localCount, "date comparison + 3VL must match the interpreter")
    assert(localCount > 0 && localCount < n, "some but not all rows are on/after the boundary")

end TypedDateSpec
