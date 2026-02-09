package protocatalyst.executor.physical

import org.apache.arrow.memory.BufferAllocator

import protocatalyst.arrow.ArrowAllocator
import protocatalyst.executor.exec._
import protocatalyst.executor.{Catalog, QueryRunner}
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan._
import protocatalyst.schema._
import protocatalyst.types._

class PhysicalPlanExecutorSuite extends munit.FunSuite:

  private var allocator: BufferAllocator = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    allocator = ArrowAllocator.createRoot()

  override def afterAll(): Unit = ()

  // ── Fixtures ──

  private def usersSchema = ProtoSchema(
    Vector(
      ProtoStructField("id", ProtoType.IntegerType, nullable = false),
      ProtoStructField("name", ProtoType.StringType, nullable = true),
      ProtoStructField("age", ProtoType.IntegerType, nullable = false)
    )
  )

  private def usersBatch = Batch.fromValues(
    Vector(
      Vector(ProtoExpr.lit(1), ProtoExpr.lit("Alice"), ProtoExpr.lit(30)),
      Vector(ProtoExpr.lit(2), ProtoExpr.lit("Bob"), ProtoExpr.lit(25)),
      Vector(ProtoExpr.lit(3), ProtoExpr.lit("Carol"), ProtoExpr.lit(35))
    ),
    usersSchema,
    allocator
  )

  private def ordersSchema = ProtoSchema(
    Vector(
      ProtoStructField("order_id", ProtoType.IntegerType, nullable = false),
      ProtoStructField("user_id", ProtoType.IntegerType, nullable = false),
      ProtoStructField("amount", ProtoType.DoubleType, nullable = false)
    )
  )

  private def ordersBatch = Batch.fromValues(
    Vector(
      Vector(ProtoExpr.lit(101), ProtoExpr.lit(1), ProtoExpr.lit(100.0)),
      Vector(ProtoExpr.lit(102), ProtoExpr.lit(2), ProtoExpr.lit(200.0)),
      Vector(ProtoExpr.lit(103), ProtoExpr.lit(1), ProtoExpr.lit(50.0))
    ),
    ordersSchema,
    allocator
  )

  private def makeCatalog(): Catalog =
    val catalog = Catalog()
    catalog.registerTable("users", usersBatch)
    catalog.registerTable("orders", ordersBatch)
    catalog

  private def collect(batch: Batch): Vector[Map[String, Any]] =
    QueryRunner.collect(batch)

  private val usersStats = Statistics(rowCount = 3, sizeInBytes = 300)
  private val ordersStats = Statistics(rowCount = 3, sizeInBytes = 300)

  // ── Leaf nodes ──

  test("TableScan"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val plan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 3)
    val rows = collect(result)
    assertEquals(rows.head("name"), "Alice")

  test("PhysicalValues"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val schema = ProtoSchema(
      Vector(ProtoStructField("x", ProtoType.IntegerType, nullable = false))
    )
    val plan = ProtoPhysicalPlan.PhysicalValues(
      Vector(Vector(ProtoExpr.lit(1)), Vector(ProtoExpr.lit(2))),
      schema
    )
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 2)

  // ── Unary operators ──

  test("PhysicalFilter"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val scan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val plan = ProtoPhysicalPlan.PhysicalFilter(
      ProtoExpr.Gt(
        ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false),
        ProtoExpr.lit(28)
      ),
      scan
    )
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 2) // Alice(30) and Carol(35)

  test("PhysicalProject"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val scan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val plan = ProtoPhysicalPlan.PhysicalProject(
      Vector(
        ProtoExpr.ColumnRef("name", None, ProtoType.StringType, true),
        ProtoExpr.Alias(
          ProtoExpr.Add(
            ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false),
            ProtoExpr.lit(1)
          ),
          "next_age"
        )
      ),
      scan
    )
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 3)
    assertEquals(result.schema.fields.size, 2)

  test("PhysicalSort"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val scan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val plan = ProtoPhysicalPlan.PhysicalSort(
      Vector(
        SortOrder(
          ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false),
          SortDirection.Descending,
          NullOrdering.NullsLast
        )
      ),
      scan
    )
    val result = executor.execute(plan)
    val rows = collect(result)
    assertEquals(rows(0)("name"), "Carol")
    assertEquals(rows(1)("name"), "Alice")
    assertEquals(rows(2)("name"), "Bob")

  test("PhysicalLimit"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val scan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val plan = ProtoPhysicalPlan.PhysicalLimit(2, scan)
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 2)

  test("PhysicalDistinct"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val schema = ProtoSchema(
      Vector(ProtoStructField("x", ProtoType.IntegerType, nullable = false))
    )
    val values = ProtoPhysicalPlan.PhysicalValues(
      Vector(Vector(ProtoExpr.lit(1)), Vector(ProtoExpr.lit(1)), Vector(ProtoExpr.lit(2))),
      schema
    )
    val plan = ProtoPhysicalPlan.PhysicalDistinct(values)
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 2)

  // ── Hash Join ──

  test("HashJoin inner"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val leftScan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val rightScan = ProtoPhysicalPlan.TableScan("orders", None, ordersSchema, ordersStats)
    val plan = ProtoPhysicalPlan.HashJoin(
      leftScan,
      rightScan,
      JoinType.Inner,
      Vector(ProtoExpr.ColumnRef("id", None, ProtoType.IntegerType, false)),
      Vector(ProtoExpr.ColumnRef("user_id", None, ProtoType.IntegerType, false)),
      condition = None,
      buildSide = BuildSide.BuildLeft
    )
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 3) // Alice: 2, Bob: 1
    val rows = collect(result)
    val aliceRows = rows.filter(_("name") == "Alice")
    assertEquals(aliceRows.size, 2)

  test("BroadcastHashJoin"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val leftScan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val rightScan = ProtoPhysicalPlan.TableScan("orders", None, ordersSchema, ordersStats)
    val plan = ProtoPhysicalPlan.BroadcastHashJoin(
      leftScan,
      rightScan,
      JoinType.Inner,
      Vector(ProtoExpr.ColumnRef("id", None, ProtoType.IntegerType, false)),
      Vector(ProtoExpr.ColumnRef("user_id", None, ProtoType.IntegerType, false)),
      condition = None,
      buildSide = BuildSide.BuildRight
    )
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 3)

  test("SortMergeJoin"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val leftScan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val rightScan = ProtoPhysicalPlan.TableScan("orders", None, ordersSchema, ordersStats)
    val plan = ProtoPhysicalPlan.SortMergeJoin(
      leftScan,
      rightScan,
      JoinType.Inner,
      Vector(ProtoExpr.ColumnRef("id", None, ProtoType.IntegerType, false)),
      Vector(ProtoExpr.ColumnRef("user_id", None, ProtoType.IntegerType, false)),
      condition = None
    )
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 3)

  test("NestedLoopJoin cross"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val leftScan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val rightScan = ProtoPhysicalPlan.TableScan("orders", None, ordersSchema, ordersStats)
    val plan = ProtoPhysicalPlan.NestedLoopJoin(
      leftScan,
      rightScan,
      JoinType.Cross,
      None
    )
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 9) // 3 * 3

  // ── Aggregate ──

  test("HashAggregate"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val scan = ProtoPhysicalPlan.TableScan("orders", None, ordersSchema, ordersStats)
    val plan = ProtoPhysicalPlan.HashAggregate(
      Vector(ProtoExpr.ColumnRef("user_id", None, ProtoType.IntegerType, false)),
      Vector(ProtoExpr.Sum(ProtoExpr.ColumnRef("amount", None, ProtoType.DoubleType, false))),
      scan
    )
    val result = executor.execute(plan)
    // user_id=1: 100+50=150, user_id=2: 200
    assertEquals(result.rowCount, 2)

  test("SortAggregate"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val scan = ProtoPhysicalPlan.TableScan("orders", None, ordersSchema, ordersStats)
    val plan = ProtoPhysicalPlan.SortAggregate(
      Vector(ProtoExpr.ColumnRef("user_id", None, ProtoType.IntegerType, false)),
      Vector(
        ProtoExpr.Count(ProtoExpr.ColumnRef("order_id", None, ProtoType.IntegerType, false), false)
      ),
      scan
    )
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 2)

  // ── Exchange (no-op) ──

  test("Exchange is no-op"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val scan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val plan = ProtoPhysicalPlan.Exchange(
      Partitioning.HashPartitioning(
        Vector(ProtoExpr.ColumnRef("id", None, ProtoType.IntegerType, false)),
        4
      ),
      scan
    )
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 3)

  // ── Set operations ──

  test("PhysicalUnion"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val scan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val plan = ProtoPhysicalPlan.PhysicalUnion(
      Vector(scan, scan),
      byName = false,
      allowMissingColumns = false
    )
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 6)

  // ── CTE ──

  test("PhysicalWith"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val scan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val plan = ProtoPhysicalPlan.PhysicalWith(
      Vector(("cte_users", scan)),
      recursive = false,
      child = ProtoPhysicalPlan.TableScan("cte_users", None, usersSchema, usersStats)
    )
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 3)

  // ── End-to-end: PhysicalPlanner + PhysicalPlanExecutor ──

  test("end-to-end: logical → physical → execute"):
    val catalog = makeCatalog()
    val planner = PhysicalPlanner(catalog.statsProvider)
    val contract = SchemaContract(
      "users",
      Vector(
        FieldContract("id", ProtoType.IntegerType, expectedNullable = false),
        FieldContract("name", ProtoType.StringType, expectedNullable = true),
        FieldContract("age", ProtoType.IntegerType, expectedNullable = false)
      ),
      SchemaFingerprint.fromLong(0L)
    )
    val logical = ProtoLogicalPlan.Filter(
      ProtoExpr.Gt(
        ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false),
        ProtoExpr.lit(28)
      ),
      ProtoLogicalPlan.RelationRef("users", None, contract)
    )
    val physical = planner.plan(logical)
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val result = executor.execute(physical)
    assertEquals(result.rowCount, 2)

  test("end-to-end: join via logical → physical → execute"):
    val catalog = makeCatalog()
    val planner = PhysicalPlanner(catalog.statsProvider)
    val usersContract = SchemaContract(
      "users",
      Vector(
        FieldContract("id", ProtoType.IntegerType, expectedNullable = false),
        FieldContract("name", ProtoType.StringType, expectedNullable = true),
        FieldContract("age", ProtoType.IntegerType, expectedNullable = false)
      ),
      SchemaFingerprint.fromLong(0L)
    )
    val ordersContract = SchemaContract(
      "orders",
      Vector(
        FieldContract("order_id", ProtoType.IntegerType, expectedNullable = false),
        FieldContract("user_id", ProtoType.IntegerType, expectedNullable = false),
        FieldContract("amount", ProtoType.DoubleType, expectedNullable = false)
      ),
      SchemaFingerprint.fromLong(0L)
    )
    val logical = ProtoLogicalPlan.Join(
      ProtoLogicalPlan.RelationRef("users", Some("u"), usersContract),
      ProtoLogicalPlan.RelationRef("orders", Some("o"), ordersContract),
      JoinType.Inner,
      Some(
        ProtoExpr.Eq(
          ProtoExpr.ColumnRef("id", Some("u"), ProtoType.IntegerType, false),
          ProtoExpr.ColumnRef("user_id", Some("o"), ProtoType.IntegerType, false)
        )
      )
    )
    val physical = planner.plan(logical)
    // The planner should produce a hash join (both sides tiny, but still equi-join)
    assert(
      physical.isInstanceOf[ProtoPhysicalPlan.HashJoin] ||
        physical.isInstanceOf[ProtoPhysicalPlan.BroadcastHashJoin],
      s"Expected hash join but got ${physical.getClass.getSimpleName}"
    )
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val result = executor.execute(physical)
    assertEquals(result.rowCount, 3)
    val rows = collect(result)
    val aliceRows = rows.filter(_("name") == "Alice")
    assertEquals(aliceRows.size, 2)

  // ── Set operations: Intersect and Except ──

  test("PhysicalIntersect"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val schema = ProtoSchema(
      Vector(ProtoStructField("x", ProtoType.IntegerType, nullable = false))
    )
    val left = ProtoPhysicalPlan.PhysicalValues(
      Vector(Vector(ProtoExpr.lit(1)), Vector(ProtoExpr.lit(2)), Vector(ProtoExpr.lit(3))),
      schema
    )
    val right = ProtoPhysicalPlan.PhysicalValues(
      Vector(Vector(ProtoExpr.lit(2)), Vector(ProtoExpr.lit(3)), Vector(ProtoExpr.lit(4))),
      schema
    )
    val plan = ProtoPhysicalPlan.PhysicalIntersect(left, right, isAll = false)
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 2) // {2, 3}
    val rows = collect(result)
    val values = rows.map(_("x")).toSet
    assertEquals(values, Set[Any](2, 3))

  test("PhysicalIntersect ALL"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val schema = ProtoSchema(
      Vector(ProtoStructField("x", ProtoType.IntegerType, nullable = false))
    )
    val left = ProtoPhysicalPlan.PhysicalValues(
      Vector(Vector(ProtoExpr.lit(1)), Vector(ProtoExpr.lit(1)), Vector(ProtoExpr.lit(2))),
      schema
    )
    val right = ProtoPhysicalPlan.PhysicalValues(
      Vector(Vector(ProtoExpr.lit(1)), Vector(ProtoExpr.lit(1)), Vector(ProtoExpr.lit(1))),
      schema
    )
    val plan = ProtoPhysicalPlan.PhysicalIntersect(left, right, isAll = true)
    val result = executor.execute(plan)
    // Left has two 1s, right has three 1s → intersect all = two 1s
    assertEquals(result.rowCount, 2)

  test("PhysicalExcept"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val schema = ProtoSchema(
      Vector(ProtoStructField("x", ProtoType.IntegerType, nullable = false))
    )
    val left = ProtoPhysicalPlan.PhysicalValues(
      Vector(Vector(ProtoExpr.lit(1)), Vector(ProtoExpr.lit(2)), Vector(ProtoExpr.lit(3))),
      schema
    )
    val right = ProtoPhysicalPlan.PhysicalValues(
      Vector(Vector(ProtoExpr.lit(2)), Vector(ProtoExpr.lit(4))),
      schema
    )
    val plan = ProtoPhysicalPlan.PhysicalExcept(left, right, isAll = false)
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 2) // {1, 3}
    val rows = collect(result)
    val values = rows.map(_("x")).toSet
    assertEquals(values, Set[Any](1, 3))

  test("PhysicalExcept ALL"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val schema = ProtoSchema(
      Vector(ProtoStructField("x", ProtoType.IntegerType, nullable = false))
    )
    val left = ProtoPhysicalPlan.PhysicalValues(
      Vector(Vector(ProtoExpr.lit(1)), Vector(ProtoExpr.lit(1)), Vector(ProtoExpr.lit(2))),
      schema
    )
    val right = ProtoPhysicalPlan.PhysicalValues(
      Vector(Vector(ProtoExpr.lit(1))),
      schema
    )
    val plan = ProtoPhysicalPlan.PhysicalExcept(left, right, isAll = true)
    val result = executor.execute(plan)
    // Left: {1,1,2}, Right: {1} → except all removes one 1 → {1,2}
    assertEquals(result.rowCount, 2)

  // ── Aggregate: Avg, Min, Max ──

  test("HashAggregate Avg"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val scan = ProtoPhysicalPlan.TableScan("orders", None, ordersSchema, ordersStats)
    val plan = ProtoPhysicalPlan.HashAggregate(
      Vector(ProtoExpr.ColumnRef("user_id", None, ProtoType.IntegerType, false)),
      Vector(ProtoExpr.Avg(ProtoExpr.ColumnRef("amount", None, ProtoType.DoubleType, false))),
      scan
    )
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 2)
    val rows = collect(result)
    // user_id=1: avg(100,50)=75, user_id=2: avg(200)=200
    val user1 = rows.find(_("user_id") == 1).get
    assertEquals(user1("avg"), 75.0)
    val user2 = rows.find(_("user_id") == 2).get
    assertEquals(user2("avg"), 200.0)

  test("HashAggregate Min and Max"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val scan = ProtoPhysicalPlan.TableScan("orders", None, ordersSchema, ordersStats)
    val plan = ProtoPhysicalPlan.HashAggregate(
      Vector(ProtoExpr.ColumnRef("user_id", None, ProtoType.IntegerType, false)),
      Vector(
        ProtoExpr.Min(ProtoExpr.ColumnRef("amount", None, ProtoType.DoubleType, false)),
        ProtoExpr.Max(ProtoExpr.ColumnRef("amount", None, ProtoType.DoubleType, false))
      ),
      scan
    )
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 2)
    val rows = collect(result)
    // user_id=1: min=50, max=100
    val user1 = rows.find(_("user_id") == 1).get
    assertEquals(user1("min"), 50.0)
    assertEquals(user1("max"), 100.0)

  test("HashAggregate global (no grouping)"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val scan = ProtoPhysicalPlan.TableScan("orders", None, ordersSchema, ordersStats)
    val plan = ProtoPhysicalPlan.HashAggregate(
      Vector.empty,
      Vector(
        ProtoExpr.Sum(ProtoExpr.ColumnRef("amount", None, ProtoType.DoubleType, false)),
        ProtoExpr.Count(ProtoExpr.ColumnRef("order_id", None, ProtoType.IntegerType, false), false)
      ),
      scan
    )
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 1) // single global group
    val rows = collect(result)
    assertEquals(rows.head("sum"), 350.0) // 100+200+50
    assertEquals(rows.head("count"), 3L)

  // ── Composite join keys ──

  test("HashJoin with composite keys"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val leftSchema = ProtoSchema(
      Vector(
        ProtoStructField("a", ProtoType.IntegerType, nullable = false),
        ProtoStructField("b", ProtoType.StringType, nullable = false),
        ProtoStructField("val", ProtoType.IntegerType, nullable = false)
      )
    )
    val rightSchema = ProtoSchema(
      Vector(
        ProtoStructField("x", ProtoType.IntegerType, nullable = false),
        ProtoStructField("y", ProtoType.StringType, nullable = false),
        ProtoStructField("score", ProtoType.IntegerType, nullable = false)
      )
    )
    val leftBatch = Batch.fromValues(
      Vector(
        Vector(ProtoExpr.lit(1), ProtoExpr.lit("A"), ProtoExpr.lit(10)),
        Vector(ProtoExpr.lit(1), ProtoExpr.lit("B"), ProtoExpr.lit(20)),
        Vector(ProtoExpr.lit(2), ProtoExpr.lit("A"), ProtoExpr.lit(30))
      ),
      leftSchema,
      allocator
    )
    val rightBatch = Batch.fromValues(
      Vector(
        Vector(ProtoExpr.lit(1), ProtoExpr.lit("A"), ProtoExpr.lit(100)),
        Vector(ProtoExpr.lit(2), ProtoExpr.lit("B"), ProtoExpr.lit(200))
      ),
      rightSchema,
      allocator
    )
    catalog.registerTable("lt", leftBatch)
    catalog.registerTable("rt", rightBatch)
    val leftStats = Statistics(rowCount = 3, sizeInBytes = 300)
    val rightStats = Statistics(rowCount = 2, sizeInBytes = 200)
    val plan = ProtoPhysicalPlan.HashJoin(
      ProtoPhysicalPlan.TableScan("lt", None, leftSchema, leftStats),
      ProtoPhysicalPlan.TableScan("rt", None, rightSchema, rightStats),
      JoinType.Inner,
      Vector(
        ProtoExpr.ColumnRef("a", None, ProtoType.IntegerType, false),
        ProtoExpr.ColumnRef("b", None, ProtoType.StringType, false)
      ),
      Vector(
        ProtoExpr.ColumnRef("x", None, ProtoType.IntegerType, false),
        ProtoExpr.ColumnRef("y", None, ProtoType.StringType, false)
      ),
      condition = None,
      buildSide = BuildSide.BuildRight
    )
    val result = executor.execute(plan)
    // Only (1,A)=(1,A) matches
    assertEquals(result.rowCount, 1)
    val rows = collect(result)
    assertEquals(rows.head("val"), 10)
    assertEquals(rows.head("score"), 100)

  test("SortMergeJoin with composite keys"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val leftSchema = ProtoSchema(
      Vector(
        ProtoStructField("a", ProtoType.IntegerType, nullable = false),
        ProtoStructField("b", ProtoType.StringType, nullable = false),
        ProtoStructField("val", ProtoType.IntegerType, nullable = false)
      )
    )
    val rightSchema = ProtoSchema(
      Vector(
        ProtoStructField("x", ProtoType.IntegerType, nullable = false),
        ProtoStructField("y", ProtoType.StringType, nullable = false),
        ProtoStructField("score", ProtoType.IntegerType, nullable = false)
      )
    )
    val leftBatch = Batch.fromValues(
      Vector(
        Vector(ProtoExpr.lit(1), ProtoExpr.lit("A"), ProtoExpr.lit(10)),
        Vector(ProtoExpr.lit(1), ProtoExpr.lit("B"), ProtoExpr.lit(20)),
        Vector(ProtoExpr.lit(2), ProtoExpr.lit("A"), ProtoExpr.lit(30))
      ),
      leftSchema,
      allocator
    )
    val rightBatch = Batch.fromValues(
      Vector(
        Vector(ProtoExpr.lit(1), ProtoExpr.lit("A"), ProtoExpr.lit(100)),
        Vector(ProtoExpr.lit(2), ProtoExpr.lit("B"), ProtoExpr.lit(200))
      ),
      rightSchema,
      allocator
    )
    catalog.registerTable("lt2", leftBatch)
    catalog.registerTable("rt2", rightBatch)
    val leftStats = Statistics(rowCount = 3, sizeInBytes = 300)
    val rightStats = Statistics(rowCount = 2, sizeInBytes = 200)
    val plan = ProtoPhysicalPlan.SortMergeJoin(
      ProtoPhysicalPlan.TableScan("lt2", None, leftSchema, leftStats),
      ProtoPhysicalPlan.TableScan("rt2", None, rightSchema, rightStats),
      JoinType.Inner,
      Vector(
        ProtoExpr.ColumnRef("a", None, ProtoType.IntegerType, false),
        ProtoExpr.ColumnRef("b", None, ProtoType.StringType, false)
      ),
      Vector(
        ProtoExpr.ColumnRef("x", None, ProtoType.IntegerType, false),
        ProtoExpr.ColumnRef("y", None, ProtoType.StringType, false)
      ),
      condition = None
    )
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 1)
    val rows = collect(result)
    assertEquals(rows.head("val"), 10)
    assertEquals(rows.head("score"), 100)

  // ── Empty batch edge cases ──

  test("Filter produces empty result"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val scan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val plan = ProtoPhysicalPlan.PhysicalFilter(
      ProtoExpr.Gt(
        ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false),
        ProtoExpr.lit(100)
      ),
      scan
    )
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 0)
    assertEquals(result.schema.fields.size, 3)

  test("Limit zero"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val scan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val plan = ProtoPhysicalPlan.PhysicalLimit(0, scan)
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 0)

  test("Limit exceeds row count"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val scan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val plan = ProtoPhysicalPlan.PhysicalLimit(100, scan)
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 3)

  test("Distinct on already unique data"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val scan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val plan = ProtoPhysicalPlan.PhysicalDistinct(scan)
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 3)

  test("HashJoin with empty right"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val emptySchema = ProtoSchema(
      Vector(
        ProtoStructField("oid", ProtoType.IntegerType, nullable = false),
        ProtoStructField("uid", ProtoType.IntegerType, nullable = false)
      )
    )
    val emptyBatch = Batch.fromValues(Vector.empty, emptySchema, allocator)
    catalog.registerTable("empty_orders", emptyBatch)
    val emptyStats = Statistics(rowCount = 0, sizeInBytes = 0)
    val plan = ProtoPhysicalPlan.HashJoin(
      ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats),
      ProtoPhysicalPlan.TableScan("empty_orders", None, emptySchema, emptyStats),
      JoinType.Inner,
      Vector(ProtoExpr.ColumnRef("id", None, ProtoType.IntegerType, false)),
      Vector(ProtoExpr.ColumnRef("uid", None, ProtoType.IntegerType, false)),
      condition = None,
      buildSide = BuildSide.BuildRight
    )
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 0)

  test("left outer join with empty right preserves all left"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val emptySchema = ProtoSchema(
      Vector(
        ProtoStructField("oid", ProtoType.IntegerType, nullable = false),
        ProtoStructField("uid", ProtoType.IntegerType, nullable = false)
      )
    )
    val emptyBatch = Batch.fromValues(Vector.empty, emptySchema, allocator)
    catalog.registerTable("empty_orders2", emptyBatch)
    val emptyStats = Statistics(rowCount = 0, sizeInBytes = 0)
    val plan = ProtoPhysicalPlan.HashJoin(
      ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats),
      ProtoPhysicalPlan.TableScan("empty_orders2", None, emptySchema, emptyStats),
      JoinType.LeftOuter,
      Vector(ProtoExpr.ColumnRef("id", None, ProtoType.IntegerType, false)),
      Vector(ProtoExpr.ColumnRef("uid", None, ProtoType.IntegerType, false)),
      condition = None,
      buildSide = BuildSide.BuildRight
    )
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 3) // all left rows preserved with null right
    val rows = collect(result)
    assert(rows.forall(_("oid") == null))

  // ── Error paths ──

  test("TableScan: missing table throws ExecutionException"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val plan = ProtoPhysicalPlan.TableScan("nonexistent", None, usersSchema, usersStats)
    intercept[ExecutionException]:
      executor.execute(plan)

  test("Recursive CTE throws ExecutionException"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val scan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val plan = ProtoPhysicalPlan.PhysicalWith(
      Vector(("recursive_cte", scan)),
      recursive = true,
      child = ProtoPhysicalPlan.TableScan("recursive_cte", None, usersSchema, usersStats)
    )
    intercept[ExecutionException]:
      executor.execute(plan)

  // ── Cross-validation: HashJoin vs SortMergeJoin produce identical results ──

  test("HashJoin and SortMergeJoin produce identical inner join results"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val leftScan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val rightScan = ProtoPhysicalPlan.TableScan("orders", None, ordersSchema, ordersStats)
    val leftKey = Vector(ProtoExpr.ColumnRef("id", None, ProtoType.IntegerType, false))
    val rightKey = Vector(ProtoExpr.ColumnRef("user_id", None, ProtoType.IntegerType, false))

    val hashResult = executor.execute(
      ProtoPhysicalPlan.HashJoin(
        leftScan,
        rightScan,
        JoinType.Inner,
        leftKey,
        rightKey,
        None,
        BuildSide.BuildLeft
      )
    )
    val smjResult = executor.execute(
      ProtoPhysicalPlan.SortMergeJoin(
        leftScan,
        rightScan,
        JoinType.Inner,
        leftKey,
        rightKey,
        None
      )
    )
    assertEquals(hashResult.rowCount, smjResult.rowCount)
    val hashRows = collect(hashResult).map(r => (r("name"), r("order_id"))).toSet
    val smjRows = collect(smjResult).map(r => (r("name"), r("order_id"))).toSet
    assertEquals(hashRows, smjRows)

  test("HashJoin and SortMergeJoin produce identical left outer results"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val leftScan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val rightScan = ProtoPhysicalPlan.TableScan("orders", None, ordersSchema, ordersStats)
    val leftKey = Vector(ProtoExpr.ColumnRef("id", None, ProtoType.IntegerType, false))
    val rightKey = Vector(ProtoExpr.ColumnRef("user_id", None, ProtoType.IntegerType, false))

    val hashResult = executor.execute(
      ProtoPhysicalPlan.HashJoin(
        leftScan,
        rightScan,
        JoinType.LeftOuter,
        leftKey,
        rightKey,
        None,
        BuildSide.BuildRight
      )
    )
    val smjResult = executor.execute(
      ProtoPhysicalPlan.SortMergeJoin(
        leftScan,
        rightScan,
        JoinType.LeftOuter,
        leftKey,
        rightKey,
        None
      )
    )
    assertEquals(hashResult.rowCount, smjResult.rowCount)

  // ── Pipeline: chained operators ──

  test("Filter → Sort → Limit pipeline"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val scan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val filtered = ProtoPhysicalPlan.PhysicalFilter(
      ProtoExpr.Gt(
        ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false),
        ProtoExpr.lit(24)
      ),
      scan
    )
    val sorted = ProtoPhysicalPlan.PhysicalSort(
      Vector(
        SortOrder(
          ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false),
          SortDirection.Ascending,
          NullOrdering.NullsLast
        )
      ),
      filtered
    )
    val plan = ProtoPhysicalPlan.PhysicalLimit(2, sorted)
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 2)
    val rows = collect(result)
    // ages > 24 are 25, 30, 35 → sorted ascending → first 2 are Bob(25), Alice(30)
    assertEquals(rows(0)("name"), "Bob")
    assertEquals(rows(1)("name"), "Alice")

  test("Join → Aggregate pipeline"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val leftScan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val rightScan = ProtoPhysicalPlan.TableScan("orders", None, ordersSchema, ordersStats)
    val join = ProtoPhysicalPlan.HashJoin(
      leftScan,
      rightScan,
      JoinType.Inner,
      Vector(ProtoExpr.ColumnRef("id", None, ProtoType.IntegerType, false)),
      Vector(ProtoExpr.ColumnRef("user_id", None, ProtoType.IntegerType, false)),
      condition = None,
      buildSide = BuildSide.BuildLeft
    )
    val plan = ProtoPhysicalPlan.HashAggregate(
      Vector(ProtoExpr.ColumnRef("name", None, ProtoType.StringType, true)),
      Vector(ProtoExpr.Sum(ProtoExpr.ColumnRef("amount", None, ProtoType.DoubleType, false))),
      join
    )
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 2) // Alice and Bob
    val rows = collect(result)
    val alice = rows.find(_("name") == "Alice").get
    assertEquals(alice("sum"), 150.0) // 100 + 50
    val bob = rows.find(_("name") == "Bob").get
    assertEquals(bob("sum"), 200.0)

  test("Union → Distinct pipeline"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val scan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val union = ProtoPhysicalPlan.PhysicalUnion(
      Vector(scan, scan),
      byName = false,
      allowMissingColumns = false
    )
    val plan = ProtoPhysicalPlan.PhysicalDistinct(union)
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 3) // duplicates removed

  test("PhysicalWith chained CTEs"):
    val catalog = makeCatalog()
    val executor = PhysicalPlanExecutor(catalog, allocator)
    val scan = ProtoPhysicalPlan.TableScan("users", None, usersSchema, usersStats)
    val filtered = ProtoPhysicalPlan.PhysicalFilter(
      ProtoExpr.Gt(
        ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false),
        ProtoExpr.lit(28)
      ),
      ProtoPhysicalPlan.TableScan("cte1", None, usersSchema, usersStats)
    )
    val plan = ProtoPhysicalPlan.PhysicalWith(
      Vector(
        ("cte1", scan),
        ("cte2", filtered)
      ),
      recursive = false,
      child = ProtoPhysicalPlan.TableScan("cte2", None, usersSchema, usersStats)
    )
    val result = executor.execute(plan)
    assertEquals(result.rowCount, 2) // Alice(30) and Carol(35)
