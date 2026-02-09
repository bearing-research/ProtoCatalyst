package protocatalyst.plan

import protocatalyst.expr._
import protocatalyst.schema._
import protocatalyst.types._

class PhysicalPlannerSuite extends munit.FunSuite:

  // ── Fixtures ──

  private val simpleSchema = ProtoSchema(
    Vector(
      ProtoStructField("id", ProtoType.IntegerType, nullable = false),
      ProtoStructField("name", ProtoType.StringType, nullable = true)
    )
  )

  private val contract = SchemaContract(
    "users",
    Vector(
      FieldContract("id", ProtoType.IntegerType, expectedNullable = false),
      FieldContract("name", ProtoType.StringType, expectedNullable = true)
    ),
    SchemaFingerprint.fromLong(0L)
  )

  private val ordersContract = SchemaContract(
    "orders",
    Vector(
      FieldContract("order_id", ProtoType.IntegerType, expectedNullable = false),
      FieldContract("user_id", ProtoType.IntegerType, expectedNullable = false),
      FieldContract("amount", ProtoType.DoubleType, expectedNullable = false)
    ),
    SchemaFingerprint.fromLong(0L)
  )

  // Both above broadcast threshold (10MB = 10485760) so default is HashJoin
  private val usersStats = Statistics(rowCount = 1000000, sizeInBytes = 50000000L)
  private val ordersStats = Statistics(rowCount = 5000000, sizeInBytes = 250000000L)
  private val smallStats = Statistics(rowCount = 10, sizeInBytes = 500) // below broadcast threshold

  private val defaultStatsProvider: String => Statistics =
    case "users"  => usersStats
    case "orders" => ordersStats
    case "small"  => smallStats
    case _        => Statistics.unknown

  private val planner = PhysicalPlanner(defaultStatsProvider)

  private val colId = ProtoExpr.ColumnRef("id", Some("u"), ProtoType.IntegerType, false)
  private val colUserId = ProtoExpr.ColumnRef("user_id", Some("o"), ProtoType.IntegerType, false)
  private val colAmount = ProtoExpr.ColumnRef("amount", Some("o"), ProtoType.DoubleType, false)
  private val litInt42 = ProtoExpr.Literal(LiteralValue.IntValue(42))

  // ── Leaf nodes ──

  test("RelationRef → TableScan with stats from provider"):
    val logical = ProtoLogicalPlan.RelationRef("users", Some("u"), contract)
    val physical = planner.plan(logical)
    val ts = physical.asInstanceOf[ProtoPhysicalPlan.TableScan]
    assertEquals(ts.name, "users")
    assertEquals(ts.alias, Some("u"))
    assertEquals(ts.stats.rowCount, usersStats.rowCount)
    assertEquals(ts.stats.sizeInBytes, usersStats.sizeInBytes)

  test("RelationRef schema derived from contract"):
    val logical = ProtoLogicalPlan.RelationRef("users", None, contract)
    val physical = planner.plan(logical)
    val ts = physical.asInstanceOf[ProtoPhysicalPlan.TableScan]
    assertEquals(ts.schema.fields.size, 2)
    assertEquals(ts.schema.fields(0).name, "id")
    assertEquals(ts.schema.fields(1).name, "name")

  test("Values → PhysicalValues"):
    val logical = ProtoLogicalPlan.Values(
      Vector(Vector(ProtoExpr.Literal(LiteralValue.IntValue(1)))),
      simpleSchema
    )
    val physical = planner.plan(logical)
    assert(physical.isInstanceOf[ProtoPhysicalPlan.PhysicalValues])

  // ── Unary operators ──

  test("Project → PhysicalProject"):
    val logical = ProtoLogicalPlan.Project(
      Vector(colId),
      ProtoLogicalPlan.RelationRef("users", None, contract)
    )
    val physical = planner.plan(logical)
    val pp = physical.asInstanceOf[ProtoPhysicalPlan.PhysicalProject]
    assertEquals(pp.projectList.size, 1)

  test("Filter → PhysicalFilter"):
    val logical = ProtoLogicalPlan.Filter(
      ProtoExpr.Gt(colId, litInt42),
      ProtoLogicalPlan.RelationRef("users", None, contract)
    )
    val physical = planner.plan(logical)
    assert(physical.isInstanceOf[ProtoPhysicalPlan.PhysicalFilter])

  test("Sort → PhysicalSort"):
    val logical = ProtoLogicalPlan.Sort(
      Vector(SortOrder(colId, SortDirection.Ascending, NullOrdering.NullsLast)),
      ProtoLogicalPlan.RelationRef("users", None, contract)
    )
    val physical = planner.plan(logical)
    assert(physical.isInstanceOf[ProtoPhysicalPlan.PhysicalSort])

  test("Limit → PhysicalLimit"):
    val logical = ProtoLogicalPlan.Limit(10, ProtoLogicalPlan.RelationRef("users", None, contract))
    val physical = planner.plan(logical)
    val pl = physical.asInstanceOf[ProtoPhysicalPlan.PhysicalLimit]
    assertEquals(pl.limit, 10)

  test("Distinct → PhysicalDistinct"):
    val logical = ProtoLogicalPlan.Distinct(ProtoLogicalPlan.RelationRef("users", None, contract))
    val physical = planner.plan(logical)
    assert(physical.isInstanceOf[ProtoPhysicalPlan.PhysicalDistinct])

  // ── SubqueryAlias consumed ──

  test("SubqueryAlias is consumed (transparent)"):
    val logical = ProtoLogicalPlan.SubqueryAlias(
      "u",
      ProtoLogicalPlan.RelationRef("users", None, contract)
    )
    val physical = planner.plan(logical)
    assert(physical.isInstanceOf[ProtoPhysicalPlan.TableScan])

  // ── Join strategy selection ──

  test("equi-join with default config → HashJoin"):
    val logical = ProtoLogicalPlan.Join(
      ProtoLogicalPlan.RelationRef("users", Some("u"), contract),
      ProtoLogicalPlan.RelationRef("orders", Some("o"), ordersContract),
      JoinType.Inner,
      Some(ProtoExpr.Eq(colId, colUserId))
    )
    val physical = planner.plan(logical)
    val hj = physical.asInstanceOf[ProtoPhysicalPlan.HashJoin]
    assertEquals(hj.joinType, JoinType.Inner)
    assertEquals(hj.leftKeys.size, 1)
    assertEquals(hj.rightKeys.size, 1)
    // Smaller side (users, 50000 bytes) should be build side
    assertEquals(hj.buildSide, BuildSide.BuildLeft)

  test("equi-join with preferHashJoin=false → SortMergeJoin"):
    val smPlanner = PhysicalPlanner(defaultStatsProvider, PlannerConfig(preferHashJoin = false))
    val logical = ProtoLogicalPlan.Join(
      ProtoLogicalPlan.RelationRef("users", Some("u"), contract),
      ProtoLogicalPlan.RelationRef("orders", Some("o"), ordersContract),
      JoinType.Inner,
      Some(ProtoExpr.Eq(colId, colUserId))
    )
    val physical = smPlanner.plan(logical)
    assert(physical.isInstanceOf[ProtoPhysicalPlan.SortMergeJoin])

  test("non-equi join → NestedLoopJoin"):
    val logical = ProtoLogicalPlan.Join(
      ProtoLogicalPlan.RelationRef("users", Some("u"), contract),
      ProtoLogicalPlan.RelationRef("orders", Some("o"), ordersContract),
      JoinType.Inner,
      Some(ProtoExpr.Gt(colId, colUserId))
    )
    val physical = planner.plan(logical)
    assert(physical.isInstanceOf[ProtoPhysicalPlan.NestedLoopJoin])

  test("cross join → NestedLoopJoin"):
    val logical = ProtoLogicalPlan.Join(
      ProtoLogicalPlan.RelationRef("users", None, contract),
      ProtoLogicalPlan.RelationRef("orders", None, ordersContract),
      JoinType.Cross,
      None
    )
    val physical = planner.plan(logical)
    val nlj = physical.asInstanceOf[ProtoPhysicalPlan.NestedLoopJoin]
    assertEquals(nlj.joinType, JoinType.Cross)

  test("join with no condition → NestedLoopJoin"):
    val logical = ProtoLogicalPlan.Join(
      ProtoLogicalPlan.RelationRef("users", None, contract),
      ProtoLogicalPlan.RelationRef("orders", None, ordersContract),
      JoinType.Inner,
      None
    )
    val physical = planner.plan(logical)
    assert(physical.isInstanceOf[ProtoPhysicalPlan.NestedLoopJoin])

  // ── Broadcast threshold ──

  test("small table triggers BroadcastHashJoin"):
    val smallContract = SchemaContract(
      "small",
      Vector(FieldContract("id", ProtoType.IntegerType, expectedNullable = false)),
      SchemaFingerprint.fromLong(0L)
    )
    val logical = ProtoLogicalPlan.Join(
      ProtoLogicalPlan.RelationRef("orders", Some("o"), ordersContract),
      ProtoLogicalPlan.RelationRef("small", Some("s"), smallContract),
      JoinType.Inner,
      Some(
        ProtoExpr.Eq(
          ProtoExpr.ColumnRef("user_id", Some("o"), ProtoType.IntegerType, false),
          ProtoExpr.ColumnRef("id", Some("s"), ProtoType.IntegerType, false)
        )
      )
    )
    val physical = planner.plan(logical)
    val bhj = physical.asInstanceOf[ProtoPhysicalPlan.BroadcastHashJoin]
    // Small side (500 bytes) should be build side
    assertEquals(bhj.buildSide, BuildSide.BuildRight)

  // ── Equi-join key extraction ──

  test("extract single equi-key"):
    val cond = ProtoExpr.Eq(colId, colUserId)
    val (leftKeys, rightKeys, residual) = planner.extractEquiJoinKeys(cond)
    assertEquals(leftKeys.size, 1)
    assertEquals(rightKeys.size, 1)
    assert(residual.isEmpty)

  test("extract multiple equi-keys with AND"):
    val cond = ProtoExpr.And(
      Vector(
        ProtoExpr.Eq(colId, colUserId),
        ProtoExpr.Eq(
          ProtoExpr.ColumnRef("name", Some("u"), ProtoType.StringType, true),
          ProtoExpr.ColumnRef("label", Some("o"), ProtoType.StringType, true)
        )
      )
    )
    val (leftKeys, rightKeys, residual) = planner.extractEquiJoinKeys(cond)
    assertEquals(leftKeys.size, 2)
    assertEquals(rightKeys.size, 2)
    assert(residual.isEmpty)

  test("extract equi-keys with residual"):
    val cond = ProtoExpr.And(
      Vector(
        ProtoExpr.Eq(colId, colUserId),
        ProtoExpr.Gt(colAmount, ProtoExpr.Literal(LiteralValue.DoubleValue(100.0)))
      )
    )
    val (leftKeys, rightKeys, residual) = planner.extractEquiJoinKeys(cond)
    assertEquals(leftKeys.size, 1)
    assertEquals(rightKeys.size, 1)
    assert(residual.isDefined)

  test("extract equi-keys: all non-equi → no keys"):
    val cond = ProtoExpr.Gt(colId, colUserId)
    val (leftKeys, rightKeys, residual) = planner.extractEquiJoinKeys(cond)
    assert(leftKeys.isEmpty)
    assert(rightKeys.isEmpty)
    assert(residual.isDefined)

  test("extract equi-keys: nested AND"):
    val cond = ProtoExpr.And(
      Vector(
        ProtoExpr.And(
          Vector(
            ProtoExpr.Eq(colId, colUserId)
          )
        ),
        ProtoExpr.Gt(colAmount, ProtoExpr.Literal(LiteralValue.DoubleValue(50.0)))
      )
    )
    val (leftKeys, rightKeys, residual) = planner.extractEquiJoinKeys(cond)
    assertEquals(leftKeys.size, 1)
    assertEquals(rightKeys.size, 1)
    assert(residual.isDefined)

  // ── Hint processing ──

  test("BROADCAST hint forces BroadcastHashJoin"):
    val logical = ProtoLogicalPlan.ResolvedHint(
      Vector(PlanHint("BROADCAST", Vector.empty)),
      ProtoLogicalPlan.Join(
        ProtoLogicalPlan.RelationRef("users", Some("u"), contract),
        ProtoLogicalPlan.RelationRef("orders", Some("o"), ordersContract),
        JoinType.Inner,
        Some(ProtoExpr.Eq(colId, colUserId))
      )
    )
    val physical = planner.plan(logical)
    assert(physical.isInstanceOf[ProtoPhysicalPlan.BroadcastHashJoin])

  test("SHUFFLE_MERGE hint forces SortMergeJoin"):
    val logical = ProtoLogicalPlan.ResolvedHint(
      Vector(PlanHint("SHUFFLE_MERGE", Vector.empty)),
      ProtoLogicalPlan.Join(
        ProtoLogicalPlan.RelationRef("users", Some("u"), contract),
        ProtoLogicalPlan.RelationRef("orders", Some("o"), ordersContract),
        JoinType.Inner,
        Some(ProtoExpr.Eq(colId, colUserId))
      )
    )
    val physical = planner.plan(logical)
    assert(physical.isInstanceOf[ProtoPhysicalPlan.SortMergeJoin])

  test("SHUFFLE_HASH hint forces HashJoin"):
    val logical = ProtoLogicalPlan.ResolvedHint(
      Vector(PlanHint("SHUFFLE_HASH", Vector.empty)),
      ProtoLogicalPlan.Join(
        ProtoLogicalPlan.RelationRef("users", Some("u"), contract),
        ProtoLogicalPlan.RelationRef("orders", Some("o"), ordersContract),
        JoinType.Inner,
        Some(ProtoExpr.Eq(colId, colUserId))
      )
    )
    val physical = planner.plan(logical)
    assert(physical.isInstanceOf[ProtoPhysicalPlan.HashJoin])

  test("BROADCASTJOIN alias works"):
    val logical = ProtoLogicalPlan.ResolvedHint(
      Vector(PlanHint("BROADCASTJOIN", Vector.empty)),
      ProtoLogicalPlan.Join(
        ProtoLogicalPlan.RelationRef("users", Some("u"), contract),
        ProtoLogicalPlan.RelationRef("orders", Some("o"), ordersContract),
        JoinType.Inner,
        Some(ProtoExpr.Eq(colId, colUserId))
      )
    )
    val physical = planner.plan(logical)
    assert(physical.isInstanceOf[ProtoPhysicalPlan.BroadcastHashJoin])

  // ── Aggregate strategy ──

  test("Aggregate with default config → HashAggregate"):
    val logical = ProtoLogicalPlan.Aggregate(
      Vector(colId),
      Vector(ProtoExpr.Count(colId, false)),
      ProtoLogicalPlan.RelationRef("users", None, contract)
    )
    val physical = planner.plan(logical)
    val ha = physical.asInstanceOf[ProtoPhysicalPlan.HashAggregate]
    assertEquals(ha.groupingExprs.size, 1)
    assertEquals(ha.aggregateExprs.size, 1)

  test("Aggregate with preferHashAggregate=false → SortAggregate"):
    val saPlanner =
      PhysicalPlanner(defaultStatsProvider, PlannerConfig(preferHashAggregate = false))
    val logical = ProtoLogicalPlan.Aggregate(
      Vector(colId),
      Vector(ProtoExpr.Sum(colId)),
      ProtoLogicalPlan.RelationRef("users", None, contract)
    )
    val physical = saPlanner.plan(logical)
    assert(physical.isInstanceOf[ProtoPhysicalPlan.SortAggregate])

  // ── Pass-through operators ──

  test("Window → PhysicalWindow"):
    val logical = ProtoLogicalPlan.Window(
      Vector(colId),
      Vector(colId),
      Vector(SortOrder(colId, SortDirection.Ascending, NullOrdering.NullsLast)),
      ProtoLogicalPlan.RelationRef("users", None, contract)
    )
    val physical = planner.plan(logical)
    assert(physical.isInstanceOf[ProtoPhysicalPlan.PhysicalWindow])

  test("Union → PhysicalUnion"):
    val ref = ProtoLogicalPlan.RelationRef("users", None, contract)
    val logical =
      ProtoLogicalPlan.Union(Vector(ref, ref), byName = false, allowMissingColumns = false)
    val physical = planner.plan(logical)
    val pu = physical.asInstanceOf[ProtoPhysicalPlan.PhysicalUnion]
    assertEquals(pu.children.size, 2)

  test("Intersect → PhysicalIntersect"):
    val ref = ProtoLogicalPlan.RelationRef("users", None, contract)
    val logical = ProtoLogicalPlan.Intersect(ref, ref, isAll = true)
    val physical = planner.plan(logical)
    val pi = physical.asInstanceOf[ProtoPhysicalPlan.PhysicalIntersect]
    assertEquals(pi.isAll, true)

  test("Except → PhysicalExcept"):
    val ref = ProtoLogicalPlan.RelationRef("users", None, contract)
    val logical = ProtoLogicalPlan.Except(ref, ref, isAll = false)
    val physical = planner.plan(logical)
    val pe = physical.asInstanceOf[ProtoPhysicalPlan.PhysicalExcept]
    assertEquals(pe.isAll, false)

  test("With → PhysicalWith"):
    val ref = ProtoLogicalPlan.RelationRef("users", None, contract)
    val logical = ProtoLogicalPlan.With(Vector(("cte1", ref)), recursive = false, child = ref)
    val physical = planner.plan(logical)
    val pw = physical.asInstanceOf[ProtoPhysicalPlan.PhysicalWith]
    assertEquals(pw.cteRelations.size, 1)

  test("Generate → PhysicalGenerate"):
    val ref = ProtoLogicalPlan.RelationRef("users", None, contract)
    val logical = ProtoLogicalPlan.Generate(
      ProtoExpr.Explode(colId),
      Vector("col1"),
      outer = true,
      child = ref
    )
    val physical = planner.plan(logical)
    val pg = physical.asInstanceOf[ProtoPhysicalPlan.PhysicalGenerate]
    assertEquals(pg.outer, true)

  test("LateralJoin → PhysicalLateralJoin"):
    val ref = ProtoLogicalPlan.RelationRef("users", None, contract)
    val logical = ProtoLogicalPlan.LateralJoin(ref, ref, None)
    val physical = planner.plan(logical)
    assert(physical.isInstanceOf[ProtoPhysicalPlan.PhysicalLateralJoin])

  test("Pivot → PhysicalPivot"):
    val ref = ProtoLogicalPlan.RelationRef("users", None, contract)
    val logical = ProtoLogicalPlan.Pivot(
      Vector(colId),
      colId,
      Vector(litInt42),
      Vector(ProtoExpr.Count(colId, false)),
      ref
    )
    val physical = planner.plan(logical)
    assert(physical.isInstanceOf[ProtoPhysicalPlan.PhysicalPivot])

  test("Unpivot → PhysicalUnpivot"):
    val ref = ProtoLogicalPlan.RelationRef("users", None, contract)
    val logical =
      ProtoLogicalPlan.Unpivot("val", "var", Vector((colId, None)), includeNulls = false, ref)
    val physical = planner.plan(logical)
    assert(physical.isInstanceOf[ProtoPhysicalPlan.PhysicalUnpivot])

  // ── Build side selection ──

  test("build side: smaller left → BuildLeft"):
    val logical = ProtoLogicalPlan.Join(
      ProtoLogicalPlan.RelationRef("users", Some("u"), contract), // 50000 bytes
      ProtoLogicalPlan.RelationRef("orders", Some("o"), ordersContract), // 5000000 bytes
      JoinType.Inner,
      Some(ProtoExpr.Eq(colId, colUserId))
    )
    val physical = planner.plan(logical)
    val hj = physical.asInstanceOf[ProtoPhysicalPlan.HashJoin]
    assertEquals(hj.buildSide, BuildSide.BuildLeft)

  test("build side: smaller right → BuildRight"):
    val logical = ProtoLogicalPlan.Join(
      ProtoLogicalPlan.RelationRef("orders", Some("o"), ordersContract), // 5000000 bytes
      ProtoLogicalPlan.RelationRef("users", Some("u"), contract), // 50000 bytes
      JoinType.Inner,
      Some(ProtoExpr.Eq(colUserId, colId))
    )
    val physical = planner.plan(logical)
    val hj = physical.asInstanceOf[ProtoPhysicalPlan.HashJoin]
    assertEquals(hj.buildSide, BuildSide.BuildRight)

  // ── Statistics estimation ──

  test("estimateStats for TableScan returns provider stats"):
    val logical = ProtoLogicalPlan.RelationRef("users", None, contract)
    val physical = planner.plan(logical)
    val stats = planner.estimateStats(physical)
    assertEquals(stats.rowCount, usersStats.rowCount)
    assertEquals(stats.sizeInBytes, usersStats.sizeInBytes)

  test("estimateStats for Filter reduces rows"):
    val logical = ProtoLogicalPlan.Filter(
      ProtoExpr.Gt(colId, litInt42),
      ProtoLogicalPlan.RelationRef("users", None, contract)
    )
    val physical = planner.plan(logical)
    val stats = planner.estimateStats(physical)
    assert(stats.rowCount < usersStats.rowCount)
    assert(stats.rowCount > 0L)

  test("estimateStats for Limit caps rows"):
    val logical = ProtoLogicalPlan.Limit(5, ProtoLogicalPlan.RelationRef("users", None, contract))
    val physical = planner.plan(logical)
    val stats = planner.estimateStats(physical)
    assertEquals(stats.rowCount, 5L)

  // ── Complex plans ──

  test("deeply nested plan: project → filter → join"):
    val logical = ProtoLogicalPlan.Project(
      Vector(colId, colAmount),
      ProtoLogicalPlan.Filter(
        ProtoExpr.Gt(colAmount, ProtoExpr.Literal(LiteralValue.DoubleValue(100.0))),
        ProtoLogicalPlan.Join(
          ProtoLogicalPlan.RelationRef("users", Some("u"), contract),
          ProtoLogicalPlan.RelationRef("orders", Some("o"), ordersContract),
          JoinType.Inner,
          Some(ProtoExpr.Eq(colId, colUserId))
        )
      )
    )
    val physical = planner.plan(logical)
    val project = physical.asInstanceOf[ProtoPhysicalPlan.PhysicalProject]
    val filter = project.child.asInstanceOf[ProtoPhysicalPlan.PhysicalFilter]
    assert(filter.child.isInstanceOf[ProtoPhysicalPlan.HashJoin])

  test("join with equi-keys and residual"):
    val cond = ProtoExpr.And(
      Vector(
        ProtoExpr.Eq(colId, colUserId),
        ProtoExpr.Gt(colAmount, ProtoExpr.Literal(LiteralValue.DoubleValue(100.0)))
      )
    )
    val logical = ProtoLogicalPlan.Join(
      ProtoLogicalPlan.RelationRef("users", Some("u"), contract),
      ProtoLogicalPlan.RelationRef("orders", Some("o"), ordersContract),
      JoinType.Inner,
      Some(cond)
    )
    val physical = planner.plan(logical)
    val hj = physical.asInstanceOf[ProtoPhysicalPlan.HashJoin]
    assertEquals(hj.leftKeys.size, 1)
    assertEquals(hj.rightKeys.size, 1)
    assert(hj.condition.isDefined, "residual condition should be preserved")

  test("unknown stats defaults to BuildRight"):
    val unknownContract = SchemaContract(
      "unknown_table",
      Vector(FieldContract("id", ProtoType.IntegerType, expectedNullable = false)),
      SchemaFingerprint.fromLong(0L)
    )
    val logical = ProtoLogicalPlan.Join(
      ProtoLogicalPlan.RelationRef("unknown_table", Some("a"), unknownContract),
      ProtoLogicalPlan.RelationRef("unknown_table", Some("b"), unknownContract),
      JoinType.Inner,
      Some(
        ProtoExpr.Eq(
          ProtoExpr.ColumnRef("id", Some("a"), ProtoType.IntegerType, false),
          ProtoExpr.ColumnRef("id", Some("b"), ProtoType.IntegerType, false)
        )
      )
    )
    val physical = planner.plan(logical)
    val hj = physical.asInstanceOf[ProtoPhysicalPlan.HashJoin]
    assertEquals(hj.buildSide, BuildSide.BuildRight)

  test("all join types produce valid physical plans"):
    for joinType <- JoinType.values if joinType != JoinType.Cross do
      val logical = ProtoLogicalPlan.Join(
        ProtoLogicalPlan.RelationRef("users", Some("u"), contract),
        ProtoLogicalPlan.RelationRef("orders", Some("o"), ordersContract),
        joinType,
        Some(ProtoExpr.Eq(colId, colUserId))
      )
      val physical = planner.plan(logical)
      assert(
        physical.isInstanceOf[ProtoPhysicalPlan.HashJoin] ||
          physical.isInstanceOf[ProtoPhysicalPlan.SortMergeJoin] ||
          physical.isInstanceOf[ProtoPhysicalPlan.BroadcastHashJoin],
        s"$joinType should produce a hash or sort-merge join"
      )
