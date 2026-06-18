package protocatalyst.truffle.backend

import scala.jdk.CollectionConverters.*

import munit.FunSuite
import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.apache.arrow.vector.{BigIntVector, DecimalVector, FieldVector, VectorSchemaRoot}

import protocatalyst.executor.Catalog
import protocatalyst.executor.exec.Batch
import protocatalyst.executor.physical.PhysicalPlanExecutor
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan.{ProtoPhysicalPlan, Statistics}
import protocatalyst.schema.ProtoSchema
import protocatalyst.types.{LiteralValue, ProtoStructField, ProtoType}

/** Layer-1 decimal column kind: an exact decimal-range filter and null-preserving decimal projection,
  * parity-checked against the interpreter. `discount` is DECIMAL(12,2): NULL on every 5th row, else a
  * cycle of 0.05 / 0.06 / 0.07 / 0.20. The filter `discount ∈ [0.05, 0.07]` keeps the first three —
  * a boundary the `double` path can't represent exactly, but `BigDecimal.compareTo` handles precisely. */
class TypedDecimalSpec extends FunSuite:

  private val n = 10

  private def buildBatch(alloc: BufferAllocator): (Batch, ProtoSchema) =
    val orderkey = new BigIntVector("orderkey", alloc)
    val discount = new DecimalVector("discount", alloc, 12, 2)
    orderkey.allocateNew(n)
    discount.allocateNew(n)
    var i = 0
    while i < n do
      orderkey.set(i, i.toLong)
      if i % 5 == 0 then discount.setNull(i)
      else
        val s = (i % 4) match
          case 0 => "0.05"; case 1 => "0.06"; case 2 => "0.07"; case _ => "0.20"
        discount.setSafe(i, new java.math.BigDecimal(s))
      i += 1
    orderkey.setValueCount(n)
    discount.setValueCount(n)
    val vecs: java.util.List[FieldVector] = List[FieldVector](orderkey, discount).asJava
    val root = new VectorSchemaRoot(vecs)
    root.setRowCount(n)
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("orderkey", ProtoType.LongType, false),
        ProtoStructField("discount", ProtoType.DecimalType(12, 2), true)
      )
    )
    (Batch.fromRoot(root, schema), schema)

  private def dlit(s: String) = ProtoExpr.Literal(LiteralValue.DecimalValue(BigDecimal(s)))
  private def discountRef = ProtoExpr.ColumnRef("discount", None, ProtoType.DecimalType(12, 2), true)
  private def scanOf(schema: ProtoSchema) =
    ProtoPhysicalPlan.TableScan("t", None, schema, Statistics(n.toLong, n * 64L))

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
        case (x: Double, y: Double) => math.abs(x - y) <= 1e-9 * math.max(1.0, math.abs(x))
        case (x, y)                 => x == y
      }
    }

  test("exact decimal-range filter (discount in [0.05, 0.07]) matches the interpreter"):
    val alloc = new RootAllocator()
    val (batch, schema) = buildBatch(alloc)
    val cond = ProtoExpr.And(
      Vector(ProtoExpr.GtEq(discountRef, dlit("0.05")), ProtoExpr.LtEq(discountRef, dlit("0.07")))
    )
    val plan = ProtoPhysicalPlan.PhysicalFilter(cond, scanOf(schema))

    val catalog = new Catalog()
    catalog.registerTable("t", batch)
    catalog.registerStatistics("t", Statistics(n.toLong, n * 64L))
    val localCount = PhysicalPlanExecutor(catalog, alloc).execute(plan).rowCount.toLong

    val typedCount = TypedTruffleCompiler.compileFilterCount(plan).count(batch)
    assertEquals(typedCount, localCount, "exact decimal comparison + 3VL must match the interpreter")
    assert(localCount > 0 && localCount < n, "some but not all rows are in range")

  test("decimal projection preserves NULLs and agrees with the interpreter"):
    val alloc = new RootAllocator()
    val (batch, schema) = buildBatch(alloc)
    val proj = Vector(ProtoExpr.ColumnRef("orderkey", None, ProtoType.LongType, false), discountRef)
    val cond = ProtoExpr.Lt(
      ProtoExpr.ColumnRef("orderkey", None, ProtoType.LongType, false),
      ProtoExpr.Literal(LiteralValue.LongValue(100L))
    )
    val plan = ProtoPhysicalPlan.PhysicalProject(proj, ProtoPhysicalPlan.PhysicalFilter(cond, scanOf(schema)))

    val catalog = new Catalog()
    catalog.registerTable("t", batch)
    catalog.registerStatistics("t", Statistics(n.toLong, n * 64L))
    val localRows = readRows(PhysicalPlanExecutor(catalog, alloc).execute(plan))

    val typedRows = TypedTruffleCompiler.compileFilterProject(plan).run(batch).rows
    assertEquals(typedRows.size, n)
    assert(localRows.exists(_(1) == null), "some projected discount cells must be NULL")
    assert(rowsAgree(localRows, typedRows), "typed decimal projection must agree, NULLs included")

  test("decimal arithmetic: discount * discount matches the interpreter (NULL-aware)"):
    val alloc = new RootAllocator()
    val (batch, schema) = buildBatch(alloc)
    val proj = Vector(
      ProtoExpr.ColumnRef("orderkey", None, ProtoType.LongType, false),
      ProtoExpr.Multiply(discountRef, discountRef)
    )
    val cond = ProtoExpr.Lt(
      ProtoExpr.ColumnRef("orderkey", None, ProtoType.LongType, false),
      ProtoExpr.Literal(LiteralValue.LongValue(100L))
    )
    val plan = ProtoPhysicalPlan.PhysicalProject(proj, ProtoPhysicalPlan.PhysicalFilter(cond, scanOf(schema)))

    val catalog = new Catalog()
    catalog.registerTable("t", batch)
    catalog.registerStatistics("t", Statistics(n.toLong, n * 64L))
    val localRows = readRows(PhysicalPlanExecutor(catalog, alloc).execute(plan))

    val typedRows = TypedTruffleCompiler.compileFilterProject(plan).run(batch).rows
    assertEquals(typedRows.size, n)
    assert(localRows.exists(_(1) == null), "NULL discount → NULL product")
    assert(rowsAgree(localRows, typedRows), "exact BigDecimal product must agree with the interpreter")

end TypedDecimalSpec
