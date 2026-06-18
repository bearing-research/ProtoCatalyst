package protocatalyst.truffle.backend

import scala.jdk.CollectionConverters.*

import munit.FunSuite
import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.apache.arrow.vector.{BigIntVector, FieldVector, Float8Vector, VectorSchemaRoot}

import protocatalyst.executor.Catalog
import protocatalyst.executor.exec.Batch
import protocatalyst.executor.physical.PhysicalPlanExecutor
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan.{NullOrdering, ProtoPhysicalPlan, SortDirection, SortOrder, Statistics}
import protocatalyst.schema.ProtoSchema
import protocatalyst.types.{ProtoStructField, ProtoType}

/** Layer-3 result-level operators: ORDER BY, LIMIT, DISTINCT — parity-checked against the interpreter.
  * `orderkey` 0..n-1, `category` = orderkey % 3, `amount` = orderkey * 1.5. */
class TypedOperatorSpec extends FunSuite:

  private val n = 9

  private def buildBatch(alloc: BufferAllocator): (Batch, ProtoSchema) =
    val orderkey = new BigIntVector("orderkey", alloc)
    val category = new BigIntVector("category", alloc)
    val amount = new Float8Vector("amount", alloc)
    Seq(orderkey, category, amount).foreach(_.allocateNew(n))
    var i = 0
    while i < n do
      orderkey.set(i, i.toLong)
      category.set(i, (i % 3).toLong)
      amount.set(i, i * 1.5)
      i += 1
    Seq(orderkey, category, amount).foreach(_.setValueCount(n))
    val vecs: java.util.List[FieldVector] = List[FieldVector](orderkey, category, amount).asJava
    val root = new VectorSchemaRoot(vecs)
    root.setRowCount(n)
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("orderkey", ProtoType.LongType, false),
        ProtoStructField("category", ProtoType.LongType, false),
        ProtoStructField("amount", ProtoType.DoubleType, false)
      )
    )
    (Batch.fromRoot(root, schema), schema)

  private def colRef(name: String, tpe: ProtoType) = ProtoExpr.ColumnRef(name, None, tpe, false)
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
        case (x: Double, y: Double) => math.abs(x - y) <= 1e-9 * math.max(1.0, math.abs(x))
        case (x, y)                 => x == y
      }
    }

  /** Run a full plan through both engines; `sort` re-sorts both sides when the plan's own order is
    * not deterministic (DISTINCT). */
  private def check(plan: ProtoPhysicalPlan, sort: Boolean): Unit =
    val alloc = new RootAllocator()
    val (batch, schema) = buildBatch(alloc)
    val catalog = new Catalog()
    catalog.registerTable("t", batch)
    catalog.registerStatistics("t", Statistics(n.toLong, n * 64L))
    var localRows = readRows(PhysicalPlanExecutor(catalog, alloc).execute(plan))
    var typedRows = TypedTruffleCompiler.compile(plan).run(batch).rows
    if sort then
      localRows = localRows.sortBy(_.mkString("|"))
      typedRows = typedRows.sortBy(_.mkString("|"))
    assert(rowsAgree(localRows, typedRows), s"mismatch:\n  local=$localRows\n  typed=$typedRows")

  private def project(schema: ProtoSchema, cols: Vector[ProtoExpr]) =
    ProtoPhysicalPlan.PhysicalProject(cols, scanOf(schema))

  private def orderByOrderkey(dir: SortDirection) =
    SortOrder(colRef("orderkey", ProtoType.LongType), dir, NullOrdering.NullsLast)

  test("ORDER BY orderkey DESC — row-for-row vs the interpreter"):
    val (_, schema) = buildBatch(new RootAllocator())
    val proj = Vector(colRef("orderkey", ProtoType.LongType), colRef("amount", ProtoType.DoubleType))
    val plan = ProtoPhysicalPlan.PhysicalSort(Vector(orderByOrderkey(SortDirection.Descending)), project(schema, proj))
    check(plan, sort = false)

  test("ORDER BY orderkey ASC LIMIT 4 — first four rows vs the interpreter"):
    val (_, schema) = buildBatch(new RootAllocator())
    val proj = Vector(colRef("orderkey", ProtoType.LongType), colRef("amount", ProtoType.DoubleType))
    val sorted = ProtoPhysicalPlan.PhysicalSort(Vector(orderByOrderkey(SortDirection.Ascending)), project(schema, proj))
    val plan = ProtoPhysicalPlan.PhysicalLimit(4, sorted)
    check(plan, sort = false)

  test("DISTINCT category — three distinct values vs the interpreter"):
    val (_, schema) = buildBatch(new RootAllocator())
    val plan = ProtoPhysicalPlan.PhysicalDistinct(project(schema, Vector(colRef("category", ProtoType.LongType))))
    check(plan, sort = true)

end TypedOperatorSpec
