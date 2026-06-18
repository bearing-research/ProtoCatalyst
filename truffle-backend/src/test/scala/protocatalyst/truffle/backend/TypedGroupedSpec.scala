package protocatalyst.truffle.backend

import java.nio.charset.StandardCharsets.UTF_8

import scala.jdk.CollectionConverters.*

import munit.FunSuite
import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.apache.arrow.vector.{BigIntVector, FieldVector, Float8Vector, VarCharVector, VectorSchemaRoot}

import protocatalyst.executor.Catalog
import protocatalyst.executor.exec.Batch
import protocatalyst.executor.physical.PhysicalPlanExecutor
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan.{ProtoPhysicalPlan, Statistics}
import protocatalyst.schema.ProtoSchema
import protocatalyst.types.{LiteralValue, ProtoStructField, ProtoType}

/** Layer-3 grouped aggregate (GROUP BY): `SELECT flag, COUNT(*), SUM(discount) GROUP BY flag` —
  * parity-checked against the interpreter. Group order differs between engines (hash order), so both
  * result sets are sorted by the group key before comparison. `flag` cycles A/B/C; `discount` is NULL
  * on every 4th row (SUM skips NULLs). */
class TypedGroupedSpec extends FunSuite:

  private val n = 12

  private def buildBatch(alloc: BufferAllocator): (Batch, ProtoSchema) =
    val orderkey = new BigIntVector("orderkey", alloc)
    val flag = new VarCharVector("flag", alloc)
    val discount = new Float8Vector("discount", alloc)
    orderkey.allocateNew(n)
    flag.allocateNew()
    discount.allocateNew(n)
    var i = 0
    while i < n do
      orderkey.set(i, i.toLong)
      flag.setSafe(i, ('A' + (i % 3)).toChar.toString.getBytes(UTF_8))
      if i % 4 == 0 then discount.setNull(i) else discount.set(i, 0.1 * (i + 1))
      i += 1
    orderkey.setValueCount(n)
    flag.setValueCount(n)
    discount.setValueCount(n)
    val vecs: java.util.List[FieldVector] = List[FieldVector](orderkey, flag, discount).asJava
    val root = new VectorSchemaRoot(vecs)
    root.setRowCount(n)
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("orderkey", ProtoType.LongType, false),
        ProtoStructField("flag", ProtoType.StringType, false),
        ProtoStructField("discount", ProtoType.DoubleType, true)
      )
    )
    (Batch.fromRoot(root, schema), schema)

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

  test("GROUP BY flag: COUNT(*) + SUM(discount) matches the interpreter"):
    val alloc = new RootAllocator()
    val (batch, schema) = buildBatch(alloc)
    val flagRef = ProtoExpr.ColumnRef("flag", None, ProtoType.StringType, false)
    val discountRef = ProtoExpr.ColumnRef("discount", None, ProtoType.DoubleType, true)
    val aggs = Vector(
      ProtoExpr.Alias(ProtoExpr.Count(ProtoExpr.Literal(LiteralValue.IntValue(1)), false), "cnt"),
      ProtoExpr.Alias(ProtoExpr.Sum(discountRef), "sm")
    )
    val plan = ProtoPhysicalPlan.HashAggregate(
      Vector(flagRef),
      aggs,
      ProtoPhysicalPlan.TableScan("t", None, schema, Statistics(n.toLong, n * 64L))
    )

    val catalog = new Catalog()
    catalog.registerTable("t", batch)
    catalog.registerStatistics("t", Statistics(n.toLong, n * 64L))
    val localRows = readRows(PhysicalPlanExecutor(catalog, alloc).execute(plan)).sortBy(_.head.toString)
    val typedRows = TypedTruffleCompiler.compile(plan).run(batch).rows.sortBy(_.head.toString)

    assertEquals(typedRows.size, 3, "three groups A/B/C")
    assertEquals(typedRows.size, localRows.size)
    assert(rowsAgree(localRows, typedRows), s"mismatch:\n  local=$localRows\n  typed=$typedRows")

end TypedGroupedSpec
