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
import protocatalyst.types.{LiteralValue, ProtoStructField, ProtoType}

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
      TypedTruffleCompiler
        .compile(plan)
        .run(Map("nation" -> nationBatch, "region" -> regionBatch))
        .rows
        .sortBy(_.mkString("|"))

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

  test("composition: (nation WHERE nationkey < 3) JOIN region — filter runs under the join"):
    val alloc = new RootAllocator()
    val (nationBatch, nationSchema) = nation(alloc)
    val (regionBatch, regionSchema) = region(alloc)
    val key = ProtoExpr.ColumnRef("regionkey", None, ProtoType.LongType, false)
    val filteredNation = ProtoPhysicalPlan.PhysicalFilter(
      ProtoExpr.Lt(
        ProtoExpr.ColumnRef("nationkey", None, ProtoType.LongType, false),
        ProtoExpr.Literal(LiteralValue.LongValue(3L))
      ),
      ProtoPhysicalPlan.TableScan("nation", None, nationSchema, Statistics(6, 600))
    )
    val plan = ProtoPhysicalPlan.HashJoin(
      filteredNation,
      ProtoPhysicalPlan.TableScan("region", None, regionSchema, Statistics(4, 400)),
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
    catalog.registerStatistics("region", Statistics(4, 400))
    val localRows = readRows(PhysicalPlanExecutor(catalog, alloc).execute(plan)).sortBy(_.mkString("|"))
    val typedRows = TypedTruffleCompiler
      .compile(plan)
      .run(Map("nation" -> nationBatch, "region" -> regionBatch))
      .rows
      .sortBy(_.mkString("|"))

    assertEquals(typedRows.size, 3, "nations 0,1,2 (regionkeys 0,1,2) each join one region")
    assertEquals(typedRows, localRows, "filter-under-join must match the interpreter")

  test("composition: GROUP BY over a join — COUNT(*) nations per region"):
    val alloc = new RootAllocator()
    val (nationBatch, nationSchema) = nation(alloc)
    val (regionBatch, regionSchema) = region(alloc)
    val key = ProtoExpr.ColumnRef("regionkey", None, ProtoType.LongType, false)
    val join = ProtoPhysicalPlan.HashJoin(
      ProtoPhysicalPlan.TableScan("nation", None, nationSchema, Statistics(6, 600)),
      ProtoPhysicalPlan.TableScan("region", None, regionSchema, Statistics(4, 400)),
      JoinType.Inner,
      Vector(key),
      Vector(key),
      None,
      BuildSide.BuildRight
    )
    val plan = ProtoPhysicalPlan.HashAggregate(
      Vector(ProtoExpr.ColumnRef("rname", None, ProtoType.StringType, false)),
      Vector(ProtoExpr.Alias(ProtoExpr.Count(ProtoExpr.Literal(LiteralValue.IntValue(1)), false), "cnt")),
      join
    )

    val catalog = new Catalog()
    catalog.registerTable("nation", nationBatch)
    catalog.registerTable("region", regionBatch)
    catalog.registerStatistics("nation", Statistics(6, 600))
    catalog.registerStatistics("region", Statistics(4, 400))
    val localRows = readRows(PhysicalPlanExecutor(catalog, alloc).execute(plan)).sortBy(_.head.toString)
    val typedRows = TypedTruffleCompiler
      .compile(plan)
      .run(Map("nation" -> nationBatch, "region" -> regionBatch))
      .rows
      .sortBy(_.head.toString)

    // R0→2 (nations 0,4), R1→2 (nations 1,5), R2→1 (nation 2); R4 has no nations (inner join).
    assertEquals(typedRows.size, 3)
    assertEquals(typedRows, localRows, "GROUP BY over a join must match the interpreter")

  test("join with residual condition: nation ⋈ region ON regionkey AND nationkey < 3"):
    val alloc = new RootAllocator()
    val (nationBatch, nationSchema) = nation(alloc)
    val (regionBatch, regionSchema) = region(alloc)
    val key = ProtoExpr.ColumnRef("regionkey", None, ProtoType.LongType, false)
    val cond = ProtoExpr.Lt(
      ProtoExpr.ColumnRef("nationkey", None, ProtoType.LongType, false),
      ProtoExpr.Literal(LiteralValue.LongValue(3L))
    )
    val plan = ProtoPhysicalPlan.HashJoin(
      ProtoPhysicalPlan.TableScan("nation", None, nationSchema, Statistics(6, 600)),
      ProtoPhysicalPlan.TableScan("region", None, regionSchema, Statistics(4, 400)),
      JoinType.Inner,
      Vector(key),
      Vector(key),
      Some(cond),
      BuildSide.BuildRight
    )

    val catalog = new Catalog()
    catalog.registerTable("nation", nationBatch)
    catalog.registerTable("region", regionBatch)
    catalog.registerStatistics("nation", Statistics(6, 600))
    catalog.registerStatistics("region", Statistics(4, 400))
    val localRows = readRows(PhysicalPlanExecutor(catalog, alloc).execute(plan)).sortBy(_.mkString("|"))
    val typedRows = TypedTruffleCompiler
      .compile(plan)
      .run(Map("nation" -> nationBatch, "region" -> regionBatch))
      .rows
      .sortBy(_.mkString("|"))

    // equi-match keeps nations 0,1,2,4,5; the residual nationkey < 3 keeps only 0,1,2.
    assertEquals(typedRows.size, 3)
    assertEquals(typedRows, localRows, "residual join condition must match the interpreter")

  private def nationRegionJoin(nationSchema: ProtoSchema, regionSchema: ProtoSchema) =
    val key = ProtoExpr.ColumnRef("regionkey", None, ProtoType.LongType, false)
    ProtoPhysicalPlan.HashJoin(
      ProtoPhysicalPlan.TableScan("nation", None, nationSchema, Statistics(6, 600)),
      ProtoPhysicalPlan.TableScan("region", None, regionSchema, Statistics(4, 400)),
      JoinType.Inner,
      Vector(key),
      Vector(key),
      None,
      BuildSide.BuildRight
    )

  private def runComposed(plan: ProtoPhysicalPlan, nationBatch: Batch, regionBatch: Batch): (Vector[Vector[Any]], Vector[Vector[Any]]) =
    val alloc = new RootAllocator()
    val catalog = new Catalog()
    catalog.registerTable("nation", nationBatch)
    catalog.registerTable("region", regionBatch)
    catalog.registerStatistics("nation", Statistics(6, 600))
    catalog.registerStatistics("region", Statistics(4, 400))
    val local = readRows(PhysicalPlanExecutor(catalog, alloc).execute(plan)).sortBy(_.mkString("|"))
    val typed = TypedTruffleCompiler
      .compile(plan)
      .run(Map("nation" -> nationBatch, "region" -> regionBatch))
      .rows
      .sortBy(_.mkString("|"))
    (local, typed)

  test("composition: GROUP BY over a join with HAVING COUNT(*) > 1"):
    val alloc = new RootAllocator()
    val (nationBatch, nationSchema) = nation(alloc)
    val (regionBatch, regionSchema) = region(alloc)
    val agg = ProtoPhysicalPlan.HashAggregate(
      Vector(ProtoExpr.ColumnRef("rname", None, ProtoType.StringType, false)),
      Vector(ProtoExpr.Alias(ProtoExpr.Count(ProtoExpr.Literal(LiteralValue.IntValue(1)), false), "cnt")),
      nationRegionJoin(nationSchema, regionSchema)
    )
    val having = ProtoPhysicalPlan.PhysicalFilter(
      ProtoExpr.Gt(ProtoExpr.ColumnRef("cnt", None, ProtoType.LongType, false), ProtoExpr.Literal(LiteralValue.LongValue(1L))),
      agg
    )
    val (local, typed) = runComposed(having, nationBatch, regionBatch)
    // R0→2, R1→2, R2→1; HAVING cnt>1 keeps R0, R1.
    assertEquals(typed.size, 2)
    assertEquals(typed, local, "HAVING over GROUP BY over a join must match the interpreter")

  test("composition: SELECT nname, rname over a join (project over join)"):
    val alloc = new RootAllocator()
    val (nationBatch, nationSchema) = nation(alloc)
    val (regionBatch, regionSchema) = region(alloc)
    val plan = ProtoPhysicalPlan.PhysicalProject(
      Vector(
        ProtoExpr.ColumnRef("nname", None, ProtoType.StringType, false),
        ProtoExpr.ColumnRef("rname", None, ProtoType.StringType, false)
      ),
      nationRegionJoin(nationSchema, regionSchema)
    )
    val (local, typed) = runComposed(plan, nationBatch, regionBatch)
    assertEquals(typed.size, 5, "5 inner-join rows")
    assertEquals(typed, local, "project over a join must match the interpreter")

  test("nested-loop cross join: nation × region = 6 × 4 = 24 rows"):
    val alloc = new RootAllocator()
    val (nationBatch, nationSchema) = nation(alloc)
    val (regionBatch, regionSchema) = region(alloc)
    val plan = ProtoPhysicalPlan.NestedLoopJoin(
      ProtoPhysicalPlan.TableScan("nation", None, nationSchema, Statistics(6, 600)),
      ProtoPhysicalPlan.TableScan("region", None, regionSchema, Statistics(4, 400)),
      JoinType.Inner,
      None
    )
    val (local, typed) = runComposed(plan, nationBatch, regionBatch)
    assertEquals(typed.size, 24, "full cross product")
    assertEquals(typed, local, "cross join must match the interpreter")

  test("nested-loop non-equi join on uniquely-named columns: nationkey < (nation's) regionkey"):
    // Bare (unqualified) references resolve against the first matching name — fine when the referenced
    // name is unique across the join (`nationkey` here). Shared names need a qualifier (next test).
    val alloc = new RootAllocator()
    val (nationBatch, nationSchema) = nation(alloc)
    val (regionBatch, regionSchema) = region(alloc)
    val cond = ProtoExpr.Lt(
      ProtoExpr.ColumnRef("nationkey", None, ProtoType.LongType, false),
      ProtoExpr.Literal(LiteralValue.LongValue(2L))
    )
    val plan = ProtoPhysicalPlan.NestedLoopJoin(
      ProtoPhysicalPlan.TableScan("nation", None, nationSchema, Statistics(6, 600)),
      ProtoPhysicalPlan.TableScan("region", None, regionSchema, Statistics(4, 400)),
      JoinType.Inner,
      Some(cond)
    )
    val (local, typed) = runComposed(plan, nationBatch, regionBatch)
    // nationkey < 2 → nations 0,1 (2 of them) × 4 regions = 8 rows.
    assertEquals(typed.size, 8)
    assertEquals(typed, local, "non-equi nested-loop join must match the interpreter")

  test("qualifier disambiguation: (n) × (r) ON n.nationkey < r.regionkey — shared `regionkey`"):
    // Both sides expose `regionkey`. The condition references the *region* side via the qualifier
    // `r`; with alias-qualified leaf names (n.*, r.*) and qualifier-aware resolution it binds to
    // r.regionkey. (Bare-name resolution would wrongly bind to nation's regionkey → 0 rows.)
    val alloc = new RootAllocator()
    val (nationBatch, nationSchema) = nation(alloc)
    val (regionBatch, regionSchema) = region(alloc)
    val cond = ProtoExpr.Lt(
      ProtoExpr.ColumnRef("nationkey", Some("n"), ProtoType.LongType, false),
      ProtoExpr.ColumnRef("regionkey", Some("r"), ProtoType.LongType, false)
    )
    val plan = ProtoPhysicalPlan.NestedLoopJoin(
      ProtoPhysicalPlan.TableScan("nation", Some("n"), nationSchema, Statistics(6, 600)),
      ProtoPhysicalPlan.TableScan("region", Some("r"), regionSchema, Statistics(4, 400)),
      JoinType.Inner,
      Some(cond)
    )
    val (local, typed) = runComposed(plan, nationBatch, regionBatch)
    // nk0<{1,2,4}=3, nk1<{2,4}=2, nk2<{4}=1, nk3<{4}=1, nk4/nk5 none → 7 pairs.
    assertEquals(typed.size, 7, "n.nationkey < r.regionkey must bind regionkey to the region side")
    assertEquals(typed, local, "qualified non-equi join must match the interpreter")

  test("qualified equi-join: n.regionkey = r.regionkey then SELECT n.nname, r.rname"):
    // The realistic TPC-H shape: aliased tables, qualified equi-keys on a shared name, and a
    // downstream projection that reaches both qualified `regionkey`s through their qualifiers.
    val alloc = new RootAllocator()
    val (nationBatch, nationSchema) = nation(alloc)
    val (regionBatch, regionSchema) = region(alloc)
    val join = ProtoPhysicalPlan.HashJoin(
      ProtoPhysicalPlan.TableScan("nation", Some("n"), nationSchema, Statistics(6, 600)),
      ProtoPhysicalPlan.TableScan("region", Some("r"), regionSchema, Statistics(4, 400)),
      JoinType.Inner,
      Vector(ProtoExpr.ColumnRef("regionkey", Some("n"), ProtoType.LongType, false)),
      Vector(ProtoExpr.ColumnRef("regionkey", Some("r"), ProtoType.LongType, false)),
      None,
      BuildSide.BuildRight
    )
    val plan = ProtoPhysicalPlan.PhysicalProject(
      Vector(
        ProtoExpr.ColumnRef("nname", Some("n"), ProtoType.StringType, false),
        ProtoExpr.ColumnRef("rname", Some("r"), ProtoType.StringType, false)
      ),
      join
    )
    val (local, typed) = runComposed(plan, nationBatch, regionBatch)
    assertEquals(typed.size, 5, "5 matched nations")
    assertEquals(typed, local, "qualified equi-join + project must match the interpreter")

end TypedJoinSpec
