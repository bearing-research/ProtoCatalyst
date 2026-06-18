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
import protocatalyst.plan.{BuildSide, JoinType, ProtoPhysicalPlan, Statistics}
import protocatalyst.schema.ProtoSchema
import protocatalyst.types.{ProtoStructField, ProtoType}

/** Layer-3 inner equi-join: `nation ⋈ region ON nation.regionkey = region.regionkey`, parity-checked
  * against the interpreter. Output is left ++ right columns; rows are compared as sets (sorted), since
  * join/hash order differs by engine. */
class TypedJoinSpec extends FunSuite:

  private def varchar(name: String, alloc: BufferAllocator, values: Seq[String]): VarCharVector =
    val v = new VarCharVector(name, alloc)
    v.allocateNew()
    values.zipWithIndex.foreach((s, i) => v.setSafe(i, s.getBytes(UTF_8)))
    v.setValueCount(values.size)
    v

  private def bigint(name: String, alloc: BufferAllocator, values: Seq[Long]): BigIntVector =
    val v = new BigIntVector(name, alloc)
    v.allocateNew(values.size)
    values.zipWithIndex.foreach((x, i) => v.set(i, x))
    v.setValueCount(values.size)
    v

  private def batchOf(rows: Int, vecs: Seq[FieldVector], fields: Vector[ProtoStructField]): (Batch, ProtoSchema) =
    val list: java.util.List[FieldVector] = vecs.toList.asJava
    val root = new VectorSchemaRoot(list)
    root.setRowCount(rows)
    val schema = ProtoSchema(fields)
    (Batch.fromRoot(root, schema), schema)

  private def nation(alloc: BufferAllocator): (Batch, ProtoSchema) =
    batchOf(
      6,
      Seq(
        bigint("nationkey", alloc, 0L to 5L),
        bigint("regionkey", alloc, (0 to 5).map(i => (i % 3).toLong)),
        varchar("nname", alloc, (0 to 5).map("N" + _))
      ),
      Vector(
        ProtoStructField("nationkey", ProtoType.LongType, false),
        ProtoStructField("regionkey", ProtoType.LongType, false),
        ProtoStructField("nname", ProtoType.StringType, false)
      )
    )

  private def region(alloc: BufferAllocator): (Batch, ProtoSchema) =
    batchOf(
      3,
      Seq(
        bigint("regionkey", alloc, 0L to 2L),
        varchar("rname", alloc, (0 to 2).map("R" + _))
      ),
      Vector(
        ProtoStructField("regionkey", ProtoType.LongType, false),
        ProtoStructField("rname", ProtoType.StringType, false)
      )
    )

  private def readRows(b: Batch): Vector[Vector[Any]] =
    (0 until b.rowCount).map { r =>
      (0 until b.numColumns).map { c =>
        b.column(c).getObject(r) match
          case null                => null
          case x: java.lang.Number => x.doubleValue: Any
          case other               => other.toString: Any
      }.toVector
    }.toVector

  test("nation ⋈ region on regionkey — inner equi-join matches the interpreter"):
    val alloc = new RootAllocator()
    val (nationBatch, nationSchema) = nation(alloc)
    val (regionBatch, regionSchema) = region(alloc)
    val key = ProtoExpr.ColumnRef("regionkey", None, ProtoType.LongType, false)
    val plan = ProtoPhysicalPlan.HashJoin(
      ProtoPhysicalPlan.TableScan("nation", None, nationSchema, Statistics(6, 600)),
      ProtoPhysicalPlan.TableScan("region", None, regionSchema, Statistics(3, 300)),
      JoinType.Inner,
      Vector(key),
      Vector(key),
      None,
      BuildSide.BuildRight
    )

    val catalog = new Catalog()
    catalog.registerTable("nation", nationBatch)
    catalog.registerTable("region", regionBatch)
    catalog.registerStatistics("nation", Statistics(6, 600))
    catalog.registerStatistics("region", Statistics(3, 300))
    val localRows = readRows(PhysicalPlanExecutor(catalog, alloc).execute(plan)).sortBy(_.mkString("|"))

    val typedRows = TypedTruffleCompiler.compileJoin(plan).run(nationBatch, regionBatch).rows.sortBy(_.mkString("|"))

    assertEquals(typedRows.size, 6, "each of 6 nations joins exactly one region")
    assertEquals(typedRows.size, localRows.size)
    assertEquals(typedRows, localRows, s"join output must match the interpreter")

end TypedJoinSpec
