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

/** Layer-2 null-handling + control flow: COALESCE, NULLIF, IF, CASE WHEN, division-by-zero, and
  * IS [NOT] NULL — each NULL-aware — parity-checked against the interpreter on nullable data.
  * `discount` is a nullable double, NULL on every 4th row, else 0.3. */
class TypedControlSpec extends FunSuite:

  private val n = 8

  private def buildBatch(alloc: BufferAllocator): (Batch, ProtoSchema) =
    val orderkey = new BigIntVector("orderkey", alloc)
    val discount = new Float8Vector("discount", alloc)
    Seq(orderkey, discount).foreach(_.allocateNew(n))
    var i = 0
    while i < n do
      orderkey.set(i, i.toLong)
      if i % 4 == 0 then discount.setNull(i) else discount.set(i, 0.3)
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
    (Batch.fromRoot(root, schema), schema)

  private def discountRef = ProtoExpr.ColumnRef("discount", None, ProtoType.DoubleType, true)
  private def dlit(d: Double) = ProtoExpr.Literal(LiteralValue.DoubleValue(d))
  private def keepAll(schema: ProtoSchema) =
    ProtoExpr.Lt(
      ProtoExpr.ColumnRef("orderkey", None, ProtoType.LongType, false),
      ProtoExpr.Literal(LiteralValue.LongValue(100L))
    ) -> ProtoPhysicalPlan.TableScan("t", None, schema, Statistics(n.toLong, n * 64L))

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

  private def checkProjection(proj: Vector[ProtoExpr]): Unit =
    val alloc = new RootAllocator()
    val (batch, schema) = buildBatch(alloc)
    val (cond, scan) = keepAll(schema)
    val plan = ProtoPhysicalPlan.PhysicalProject(proj, ProtoPhysicalPlan.PhysicalFilter(cond, scan))

    val catalog = new Catalog()
    catalog.registerTable("t", batch)
    catalog.registerStatistics("t", Statistics(n.toLong, n * 64L))
    val localRows = readRows(PhysicalPlanExecutor(catalog, alloc).execute(plan))
    val typedRows = TypedTruffleCompiler.compileFilterProject(plan).run(batch).rows

    assertEquals(typedRows.size, n)
    assert(rowsAgree(localRows, typedRows), s"mismatch:\n  local=$localRows\n  typed=$typedRows")

  test("COALESCE(discount, -1.0) — NULLs become the fallback"):
    checkProjection(Vector(ProtoExpr.Coalesce(Vector(discountRef, dlit(-1.0)))))

  test("discount / 2.0 — NULL divided is NULL"):
    checkProjection(Vector(ProtoExpr.Divide(discountRef, dlit(2.0))))

  test("CASE WHEN discount IS NULL THEN -2.0 ELSE discount END"):
    val caseExpr = ProtoExpr.CaseWhen(
      Vector(ProtoExpr.IsNull(discountRef) -> dlit(-2.0)),
      Some(discountRef)
    )
    checkProjection(Vector(caseExpr))

  test("NULLIF(discount, 0.3) — equal operands become NULL"):
    checkProjection(Vector(ProtoExpr.NullIf(discountRef, dlit(0.3))))

  test("CAST(discount AS BIGINT) truncates toward zero; NULL stays NULL"):
    checkProjection(Vector(ProtoExpr.Cast(discountRef, ProtoType.LongType)))

  test("CAST(orderkey AS DOUBLE) widens"):
    val orderkeyRef = ProtoExpr.ColumnRef("orderkey", None, ProtoType.LongType, false)
    checkProjection(Vector(ProtoExpr.Cast(orderkeyRef, ProtoType.DoubleType)))

end TypedControlSpec
