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

  /** Q6 selection as a physical plan: project (orderkey, extendedprice, discount) over a filter of
    * discount ∈ [0.05, 0.07] AND quantity < 24. */
  private def q6Selection(schema: ProtoSchema, n: Int): ProtoPhysicalPlan =
    def col(name: String, tpe: ProtoType) = ProtoExpr.ColumnRef(name, None, tpe, false)
    def lit(d: Double) = ProtoExpr.Literal(LiteralValue.DoubleValue(d))
    val filter = ProtoExpr.And(
      Vector(
        ProtoExpr.GtEq(col("discount", ProtoType.DoubleType), lit(0.05)),
        ProtoExpr.LtEq(col("discount", ProtoType.DoubleType), lit(0.07)),
        ProtoExpr.Lt(col("quantity", ProtoType.DoubleType), lit(24.0))
      )
    )
    val proj = Vector(
      col("orderkey", ProtoType.LongType),
      col("extendedprice", ProtoType.DoubleType),
      col("discount", ProtoType.DoubleType)
    )
    val scan = ProtoPhysicalPlan.TableScan("t", None, schema, Statistics(n.toLong, n * 64L))
    ProtoPhysicalPlan.PhysicalProject(proj, ProtoPhysicalPlan.PhysicalFilter(filter, scan))

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

end TruffleBackendParitySpec
