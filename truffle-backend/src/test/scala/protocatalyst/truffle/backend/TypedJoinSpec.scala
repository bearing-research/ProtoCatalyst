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

  // nation N3 has regionkey 3 (no such region) → unmatched on the left.
  private def nation(alloc: BufferAllocator): (Batch, ProtoSchema) =
    batchOf(
      6,
      Seq(
        bigint("nationkey", alloc, 0L to 5L),
        bigint("regionkey", alloc, Seq(0L, 1L, 2L, 3L, 0L, 1L)),
        varchar("nname", alloc, (0 to 5).map("N" + _))
      ),
      Vector(
        ProtoStructField("nationkey", ProtoType.LongType, false),
        ProtoStructField("regionkey", ProtoType.LongType, false),
        ProtoStructField("nname", ProtoType.StringType, false)
      )
    )

  // region R4 (regionkey 4) is referenced by no nation → unmatched on the right.
  private def region(alloc: BufferAllocator): (Batch, ProtoSchema) =
    batchOf(
      4,
      Seq(
        bigint("regionkey", alloc, Seq(0L, 1L, 2L, 4L)),
        varchar("rname", alloc, Seq("R0", "R1", "R2", "R4"))
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

  private def checkJoin(joinType: JoinType, expectedRows: Int): Unit =
    val alloc = new RootAllocator()
    val (nationBatch, nationSchema) = nation(alloc)
    val (regionBatch, regionSchema) = region(alloc)
    val key = ProtoExpr.ColumnRef("regionkey", None, ProtoType.LongType, false)
    val plan = ProtoPhysicalPlan.HashJoin(
      ProtoPhysicalPlan.TableScan("nation", None, nationSchema, Statistics(6, 600)),
      ProtoPhysicalPlan.TableScan("region", None, regionSchema, Statistics(4, 400)),
      joinType,
      Vector(key),
      Vector(key),
      None,
      BuildSide.BuildRight
    )

    val catalog = new Catalog()
    catalog.registerTable("nation", nationBatch)
    catalog.registerTable("region", regionBatch)
    catalog.registerStatistics("nation", Statistics(6, 600))
    catalog.registerStatistics("region", Statistics(4, 400))
    val localRows = readRows(PhysicalPlanExecutor(catalog, alloc).execute(plan)).sortBy(_.mkString("|"))
    val typedRows =
      TypedTruffleCompiler.compileJoin(plan).run(nationBatch, regionBatch).rows.sortBy(_.mkString("|"))

    assertEquals(typedRows.size, expectedRows)
    assertEquals(typedRows, localRows, "join output must match the interpreter")

  test("INNER join on regionkey — matches the interpreter (5 matched rows)"):
    checkJoin(JoinType.Inner, 5)

  test("LEFT OUTER join — unmatched left nation gets a null-padded region"):
    // 5 matches + nation N3 (regionkey 3, no region) with NULL right
    checkJoin(JoinType.LeftOuter, 6)

  test("RIGHT OUTER join — unmatched right region gets a null-padded nation"):
    // 5 matches + region R4 (regionkey 4, no nation) with NULL left
    checkJoin(JoinType.RightOuter, 6)

  test("FULL OUTER join — both unmatched sides padded"):
    // 5 matches + nation N3 (null right) + region R4 (null left)
    checkJoin(JoinType.FullOuter, 7)

end TypedJoinSpec
