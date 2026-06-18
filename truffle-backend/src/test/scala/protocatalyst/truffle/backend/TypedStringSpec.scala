package protocatalyst.truffle.backend

import java.nio.charset.StandardCharsets.UTF_8

import scala.jdk.CollectionConverters.*

import munit.FunSuite
import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.apache.arrow.vector.{BigIntVector, FieldVector, VarCharVector, VectorSchemaRoot}

import protocatalyst.executor.Catalog
import protocatalyst.executor.exec.Batch
import protocatalyst.executor.physical.PhysicalPlanExecutor
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan.{ProtoPhysicalPlan, Statistics}
import protocatalyst.schema.ProtoSchema
import protocatalyst.types.{LiteralValue, ProtoStructField, ProtoType}

/** Layer-1 string column kind: equality filter on a NULLable string column and null-preserving string
  * projection, both parity-checked against the Scala interpreter. `flag` is NULL on every 3rd row,
  * else "A"/"B" alternating. */
class TypedStringSpec extends FunSuite:

  private val n = 9

  private def buildBatch(alloc: BufferAllocator): (Batch, ProtoSchema) =
    val orderkey = new BigIntVector("orderkey", alloc)
    val flag = new VarCharVector("flag", alloc)
    orderkey.allocateNew(n)
    flag.allocateNew()
    var i = 0
    while i < n do
      orderkey.set(i, i.toLong)
      if i % 3 == 0 then flag.setNull(i)
      else flag.setSafe(i, (if i % 2 == 0 then "A" else "B").getBytes(UTF_8))
      i += 1
    orderkey.setValueCount(n)
    flag.setValueCount(n)
    val vecs: java.util.List[FieldVector] = List[FieldVector](orderkey, flag).asJava
    val root = new VectorSchemaRoot(vecs)
    root.setRowCount(n)
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("orderkey", ProtoType.LongType, false),
        ProtoStructField("flag", ProtoType.StringType, true)
      )
    )
    (Batch.fromRoot(root, schema), schema)

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
        case (x: Double, y: Double) => math.abs(x - y) <= 1e-6 * math.max(1.0, math.abs(x))
        case (x, y)                 => x == y
      }
    }

  private def checkProjection(proj: Vector[ProtoExpr]): Unit =
    val alloc = new RootAllocator()
    val (batch, schema) = buildBatch(alloc)
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
    assert(rowsAgree(localRows, typedRows), s"mismatch:\n  local=$localRows\n  typed=$typedRows")

  private def flagRef = ProtoExpr.ColumnRef("flag", None, ProtoType.StringType, true)

  test("LOWER(flag) and LENGTH(flag) — NULL-aware string functions"):
    checkProjection(Vector(ProtoExpr.Lower(flagRef), ProtoExpr.Length(flagRef)))

  test("UPPER(flag) preserves and agrees"):
    checkProjection(Vector(ProtoExpr.Upper(flagRef)))

  test("string equality filter (flag = 'A') matches the interpreter, NULLs excluded"):
    val alloc = new RootAllocator()
    val (batch, schema) = buildBatch(alloc)
    val cond = ProtoExpr.Eq(
      ProtoExpr.ColumnRef("flag", None, ProtoType.StringType, true),
      ProtoExpr.Literal(LiteralValue.StringValue("A"))
    )
    val plan = ProtoPhysicalPlan.PhysicalFilter(cond, scanOf(schema))

    val catalog = new Catalog()
    catalog.registerTable("t", batch)
    catalog.registerStatistics("t", Statistics(n.toLong, n * 64L))
    val localCount = PhysicalPlanExecutor(catalog, alloc).execute(plan).rowCount.toLong

    val typedCount = TypedTruffleCompiler.compileFilterCount(plan).count(batch)
    assertEquals(typedCount, localCount, "string equality + 3VL must match the interpreter")
    assert(localCount > 0 && localCount < n, "some but not all rows are flag = 'A'")

  test("string projection preserves NULLs and agrees with the interpreter"):
    val alloc = new RootAllocator()
    val (batch, schema) = buildBatch(alloc)
    val proj = Vector(
      ProtoExpr.ColumnRef("orderkey", None, ProtoType.LongType, false),
      ProtoExpr.ColumnRef("flag", None, ProtoType.StringType, true)
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
    assert(localRows.exists(_(1) == null), "some projected flag cells must be NULL")
    assert(rowsAgree(localRows, typedRows), "typed string projection must agree, NULLs included")

end TypedStringSpec
