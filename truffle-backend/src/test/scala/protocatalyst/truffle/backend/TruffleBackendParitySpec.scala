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

/** Phase-3 parity: the same `ProtoPhysicalPlan` (Q6 selection — Project over a Filter) runs through
  * BOTH the project's Scala interpreter (`PhysicalPlanExecutor`) and the fused-row Truffle backend
  * over the same Arrow batch; the result rows must agree. This is the Truffle backend joining the
  * cross-backend correctness story (see `docs/compiler/CROSS_BACKEND.md`) as a third engine, on real
  * IR rather than the hand-built micro-slice.
  *
  * Data is synthetic and all-numeric (the backend's supported subset): orderkey:long, and
  * extendedprice/discount/quantity:double, with discount ∈ {0.06, 0.20} so the BETWEEN bounds are not
  * at double-rounding boundaries (keeps parity exact, not tolerance-dependent).
  */
class TruffleBackendParitySpec extends FunSuite:

  private def buildBatch(n: Int, alloc: BufferAllocator): (Batch, ProtoSchema) =
    val orderkey = new BigIntVector("orderkey", alloc)
    val extendedprice = new Float8Vector("extendedprice", alloc)
    val discount = new Float8Vector("discount", alloc)
    val quantity = new Float8Vector("quantity", alloc)
    Seq(orderkey, extendedprice, discount, quantity).foreach(_.allocateNew(n))
    var i = 0
    while i < n do
      orderkey.set(i, i.toLong)
      extendedprice.set(i, 1000.0 + (i % 9000))
      discount.set(i, if i % 2 == 0 then 0.06 else 0.20)
      quantity.set(i, (i % 48).toDouble)
      i += 1
    Seq(orderkey, extendedprice, discount, quantity).foreach(_.setValueCount(n))
    val vecs: java.util.List[FieldVector] =
      List[FieldVector](orderkey, extendedprice, discount, quantity).asJava
    val root = new VectorSchemaRoot(vecs)
    root.setRowCount(n)
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("orderkey", ProtoType.LongType, false),
        ProtoStructField("extendedprice", ProtoType.DoubleType, false),
        ProtoStructField("discount", ProtoType.DoubleType, false),
        ProtoStructField("quantity", ProtoType.DoubleType, false)
      )
    )
    (Batch.fromRoot(root, schema), schema)

  private def colRef(name: String, tpe: ProtoType) = ProtoExpr.ColumnRef(name, None, tpe, false)
  private def dlit(d: Double) = ProtoExpr.Literal(LiteralValue.DoubleValue(d))

  /** discount ∈ [0.05, 0.07] AND quantity < 24 — Q6's filter, shared by both plans below. */
  private def q6FilterExpr: ProtoExpr =
    ProtoExpr.And(
      Vector(
        ProtoExpr.GtEq(colRef("discount", ProtoType.DoubleType), dlit(0.05)),
        ProtoExpr.LtEq(colRef("discount", ProtoType.DoubleType), dlit(0.07)),
        ProtoExpr.Lt(colRef("quantity", ProtoType.DoubleType), dlit(24.0))
      )
    )

  private def scanOf(schema: ProtoSchema, n: Int) =
    ProtoPhysicalPlan.TableScan("t", None, schema, Statistics(n.toLong, n * 64L))

  /** Q6 selection: project (orderkey, extendedprice, discount) over the filter. */
  private def q6Selection(schema: ProtoSchema, n: Int): ProtoPhysicalPlan =
    val proj = Vector(
      colRef("orderkey", ProtoType.LongType),
      colRef("extendedprice", ProtoType.DoubleType),
      colRef("discount", ProtoType.DoubleType)
    )
    ProtoPhysicalPlan.PhysicalProject(
      proj,
      ProtoPhysicalPlan.PhysicalFilter(q6FilterExpr, scanOf(schema, n))
    )

  /** Q6 revenue: a *global* SUM(extendedprice * discount) over the filter (no GROUP BY). */
  private def q6Revenue(schema: ProtoSchema, n: Int): ProtoPhysicalPlan =
    val revenue = ProtoExpr.Alias(
      ProtoExpr.Sum(
        ProtoExpr.Multiply(
          colRef("extendedprice", ProtoType.DoubleType),
          colRef("discount", ProtoType.DoubleType)
        )
      ),
      "revenue"
    )
    ProtoPhysicalPlan.HashAggregate(
      Vector.empty,
      Vector(revenue),
      ProtoPhysicalPlan.PhysicalFilter(q6FilterExpr, scanOf(schema, n))
    )

  /** Normalize a batch to rows with numeric cells as Double — same yardstick as the cross-backend harness. */
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
        case (x: Double, y: Double) => math.abs(x - y) <= 1e-6 * math.max(1.0, math.abs(x))
        case (x, y)                 => x == y
      }
    }

  test("Q6 selection — Truffle backend agrees with the Scala interpreter row-for-row"):
    val n = 8192
    val alloc = new RootAllocator()
    val (batch, schema) = buildBatch(n, alloc)
    val physical = q6Selection(schema, n)

    val catalog = new Catalog()
    catalog.registerTable("t", batch)
    catalog.registerStatistics("t", Statistics(n.toLong, n * 64L))
    val localBatch = PhysicalPlanExecutor(catalog, alloc).execute(physical)
    val localRows = readRows(localBatch)

    val truffleRows = ProtoTruffleCompiler.compile(physical).run(batch).rows

    assert(localRows.nonEmpty, "filter should keep some rows")
    assert(localRows.size < n, "filter should drop some rows")
    assertEquals(truffleRows.size, localRows.size, "row counts must match")
    assert(rowsAgree(localRows, truffleRows), "Truffle and Local results must agree row-for-row")

  test("Q6 revenue (filter + global SUM) — Truffle backend agrees with the Scala interpreter"):
    val n = 8192
    val alloc = new RootAllocator()
    val (batch, schema) = buildBatch(n, alloc)
    val physical = q6Revenue(schema, n)

    val catalog = new Catalog()
    catalog.registerTable("t", batch)
    catalog.registerStatistics("t", Statistics(n.toLong, n * 64L))
    val localRows = readRows(PhysicalPlanExecutor(catalog, alloc).execute(physical))

    val truffleRows = ProtoTruffleCompiler.compile(physical).run(batch).rows

    assertEquals(localRows.size, 1, "global aggregate yields one row")
    assertEquals(truffleRows.size, 1)
    assert(
      truffleRows.head.head.asInstanceOf[Double] > 0.0,
      "revenue should be a positive sum"
    )
    assert(rowsAgree(localRows, truffleRows), "Truffle and Local revenue must agree")

end TruffleBackendParitySpec
