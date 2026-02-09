package protocatalyst.executor.physical

import org.apache.arrow.memory.BufferAllocator

import protocatalyst.arrow.ArrowAllocator
import protocatalyst.executor.QueryRunner
import protocatalyst.executor.exec._
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan.{BuildSide, JoinType}
import protocatalyst.schema.ProtoSchema
import protocatalyst.types._

class HashJoinOpSuite extends munit.FunSuite:

  private var allocator: BufferAllocator = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    allocator = ArrowAllocator.createRoot()

  override def afterAll(): Unit = ()

  private def evaluator = ExprEvaluator(allocator)

  // ── Fixtures ──

  private def usersSchema = ProtoSchema(
    Vector(
      ProtoStructField("id", ProtoType.IntegerType, nullable = false),
      ProtoStructField("name", ProtoType.StringType, nullable = true)
    )
  )

  private def ordersSchema = ProtoSchema(
    Vector(
      ProtoStructField("order_id", ProtoType.IntegerType, nullable = false),
      ProtoStructField("user_id", ProtoType.IntegerType, nullable = false),
      ProtoStructField("amount", ProtoType.DoubleType, nullable = false)
    )
  )

  private def usersBatch = Batch.fromValues(
    Vector(
      Vector(ProtoExpr.lit(1), ProtoExpr.lit("Alice")),
      Vector(ProtoExpr.lit(2), ProtoExpr.lit("Bob")),
      Vector(ProtoExpr.lit(3), ProtoExpr.lit("Carol"))
    ),
    usersSchema,
    allocator
  )

  private def ordersBatch = Batch.fromValues(
    Vector(
      Vector(ProtoExpr.lit(101), ProtoExpr.lit(1), ProtoExpr.lit(100.0)),
      Vector(ProtoExpr.lit(102), ProtoExpr.lit(1), ProtoExpr.lit(200.0)),
      Vector(ProtoExpr.lit(103), ProtoExpr.lit(2), ProtoExpr.lit(50.0)),
      Vector(ProtoExpr.lit(104), ProtoExpr.lit(4), ProtoExpr.lit(75.0)) // user_id=4 has no match
    ),
    ordersSchema,
    allocator
  )

  private val leftKey = ProtoExpr.ColumnRef("id", None, ProtoType.IntegerType, false)
  private val rightKey = ProtoExpr.ColumnRef("user_id", None, ProtoType.IntegerType, false)

  private def collect(batch: Batch): Vector[Map[String, Any]] =
    QueryRunner.collect(batch)

  // ── Inner Join ──

  test("inner join: matching keys"):
    val result = HashJoinOp.execute(
      usersBatch,
      ordersBatch,
      JoinType.Inner,
      Vector(leftKey),
      Vector(rightKey),
      condition = None,
      buildSide = BuildSide.BuildLeft,
      evaluator,
      allocator
    )
    // Alice(1) matches orders 101,102; Bob(2) matches order 103; Carol(3) has no match
    assertEquals(result.rowCount, 3)
    val rows = collect(result)
    // Verify the matches
    val aliceRows = rows.filter(_(("name")) == "Alice")
    assertEquals(aliceRows.size, 2)
    val bobRows = rows.filter(_("name") == "Bob")
    assertEquals(bobRows.size, 1)
    assertEquals(bobRows.head("amount"), 50.0)

  test("inner join: BuildRight"):
    val result = HashJoinOp.execute(
      usersBatch,
      ordersBatch,
      JoinType.Inner,
      Vector(leftKey),
      Vector(rightKey),
      condition = None,
      buildSide = BuildSide.BuildRight,
      evaluator,
      allocator
    )
    assertEquals(result.rowCount, 3)
    val rows = collect(result)
    val aliceRows = rows.filter(_("name") == "Alice")
    assertEquals(aliceRows.size, 2)

  test("inner join: no matches"):
    val emptyOrders = Batch.fromValues(
      Vector(Vector(ProtoExpr.lit(201), ProtoExpr.lit(99), ProtoExpr.lit(10.0))),
      ordersSchema,
      allocator
    )
    val result = HashJoinOp.execute(
      usersBatch,
      emptyOrders,
      JoinType.Inner,
      Vector(leftKey),
      Vector(rightKey),
      condition = None,
      buildSide = BuildSide.BuildRight,
      evaluator,
      allocator
    )
    assertEquals(result.rowCount, 0)

  test("inner join: with residual condition"):
    val residual = ProtoExpr.Gt(
      ProtoExpr.ColumnRef("amount", None, ProtoType.DoubleType, false),
      ProtoExpr.Literal(LiteralValue.DoubleValue(150.0))
    )
    val result = HashJoinOp.execute(
      usersBatch,
      ordersBatch,
      JoinType.Inner,
      Vector(leftKey),
      Vector(rightKey),
      condition = Some(residual),
      buildSide = BuildSide.BuildLeft,
      evaluator,
      allocator
    )
    // Only Alice's order of 200.0 passes > 150.0
    assertEquals(result.rowCount, 1)
    val rows = collect(result)
    assertEquals(rows.head("name"), "Alice")
    assertEquals(rows.head("amount"), 200.0)

  // ── Left Outer Join ──

  test("left outer join: unmatched left rows get nulls"):
    val result = HashJoinOp.execute(
      usersBatch,
      ordersBatch,
      JoinType.LeftOuter,
      Vector(leftKey),
      Vector(rightKey),
      condition = None,
      buildSide = BuildSide.BuildRight,
      evaluator,
      allocator
    )
    // Alice: 2 matches, Bob: 1 match, Carol: 0 matches (gets null right)
    assertEquals(result.rowCount, 4)
    val rows = collect(result)
    val carolRows = rows.filter(_("name") == "Carol")
    assertEquals(carolRows.size, 1)
    assertEquals(carolRows.head("order_id"), null)
    assertEquals(carolRows.head("amount"), null)

  test("left outer join: BuildLeft preserves unmatched left rows"):
    val result = HashJoinOp.execute(
      usersBatch,
      ordersBatch,
      JoinType.LeftOuter,
      Vector(leftKey),
      Vector(rightKey),
      condition = None,
      buildSide = BuildSide.BuildLeft,
      evaluator,
      allocator
    )
    assertEquals(result.rowCount, 4)
    val rows = collect(result)
    val carolRows = rows.filter(_("name") == "Carol")
    assertEquals(carolRows.size, 1)
    assertEquals(carolRows.head("order_id"), null)

  // ── Right Outer Join ──

  test("right outer join: unmatched right rows get nulls"):
    val result = HashJoinOp.execute(
      usersBatch,
      ordersBatch,
      JoinType.RightOuter,
      Vector(leftKey),
      Vector(rightKey),
      condition = None,
      buildSide = BuildSide.BuildLeft,
      evaluator,
      allocator
    )
    // 3 matches + 1 unmatched right (order_id=104, user_id=4)
    assertEquals(result.rowCount, 4)
    val rows = collect(result)
    val unmatchedRows = rows.filter(_("name") == null)
    assertEquals(unmatchedRows.size, 1)
    assertEquals(unmatchedRows.head("order_id"), 104)

  test("right outer join: BuildRight preserves unmatched right rows"):
    val result = HashJoinOp.execute(
      usersBatch,
      ordersBatch,
      JoinType.RightOuter,
      Vector(leftKey),
      Vector(rightKey),
      condition = None,
      buildSide = BuildSide.BuildRight,
      evaluator,
      allocator
    )
    assertEquals(result.rowCount, 4)
    val rows = collect(result)
    val unmatchedRows = rows.filter(_("name") == null)
    assertEquals(unmatchedRows.size, 1)
    assertEquals(unmatchedRows.head("order_id"), 104)

  // ── Full Outer Join ──

  test("full outer join: both unmatched sides"):
    val result = HashJoinOp.execute(
      usersBatch,
      ordersBatch,
      JoinType.FullOuter,
      Vector(leftKey),
      Vector(rightKey),
      condition = None,
      buildSide = BuildSide.BuildRight,
      evaluator,
      allocator
    )
    // 3 matches + 1 unmatched left (Carol) + 1 unmatched right (user_id=4)
    assertEquals(result.rowCount, 5)
    val rows = collect(result)
    val carolRows = rows.filter(r => r("name") == "Carol")
    assertEquals(carolRows.size, 1)
    assertEquals(carolRows.head("order_id"), null)
    val unmatchedRight = rows.filter(r => r("name") == null)
    assertEquals(unmatchedRight.size, 1)
    assertEquals(unmatchedRight.head("order_id"), 104)

  // ── Left Semi Join ──

  test("left semi join: returns left rows with match"):
    val result = HashJoinOp.execute(
      usersBatch,
      ordersBatch,
      JoinType.LeftSemi,
      Vector(leftKey),
      Vector(rightKey),
      condition = None,
      buildSide = BuildSide.BuildRight,
      evaluator,
      allocator
    )
    // Alice and Bob have matches, Carol does not
    assertEquals(result.rowCount, 2)
    assertEquals(result.schema.fields.size, 2) // only left fields
    val rows = collect(result)
    val names = rows.map(_("name")).toSet
    assert(names.contains("Alice"))
    assert(names.contains("Bob"))
    assert(!names.contains("Carol"))

  // ── Left Anti Join ──

  test("left anti join: returns left rows without match"):
    val result = HashJoinOp.execute(
      usersBatch,
      ordersBatch,
      JoinType.LeftAnti,
      Vector(leftKey),
      Vector(rightKey),
      condition = None,
      buildSide = BuildSide.BuildRight,
      evaluator,
      allocator
    )
    // Only Carol has no match
    assertEquals(result.rowCount, 1)
    val rows = collect(result)
    assertEquals(rows.head("name"), "Carol")

  // ── Null key handling ──

  test("null keys are excluded from matches"):
    val leftWithNull = Batch.fromValues(
      Vector(
        Vector(ProtoExpr.lit(1), ProtoExpr.lit("Alice")),
        Vector(ProtoExpr.litNull(ProtoType.IntegerType), ProtoExpr.lit("NullId"))
      ),
      usersSchema,
      allocator
    )
    val rightWithNull = Batch.fromValues(
      Vector(
        Vector(ProtoExpr.lit(201), ProtoExpr.lit(1), ProtoExpr.lit(50.0)),
        Vector(ProtoExpr.lit(202), ProtoExpr.litNull(ProtoType.IntegerType), ProtoExpr.lit(75.0))
      ),
      ordersSchema,
      allocator
    )
    val result = HashJoinOp.execute(
      leftWithNull,
      rightWithNull,
      JoinType.Inner,
      Vector(leftKey),
      Vector(rightKey),
      condition = None,
      buildSide = BuildSide.BuildRight,
      evaluator,
      allocator
    )
    // Only Alice(1) matches order 201. Null keys should not match.
    assertEquals(result.rowCount, 1)
    val rows = collect(result)
    assertEquals(rows.head("name"), "Alice")

  // ── Duplicate keys ──

  test("duplicate keys: many-to-many join"):
    val leftDups = Batch.fromValues(
      Vector(
        Vector(ProtoExpr.lit(1), ProtoExpr.lit("A1")),
        Vector(ProtoExpr.lit(1), ProtoExpr.lit("A2"))
      ),
      usersSchema,
      allocator
    )
    val rightDups = Batch.fromValues(
      Vector(
        Vector(ProtoExpr.lit(301), ProtoExpr.lit(1), ProtoExpr.lit(10.0)),
        Vector(ProtoExpr.lit(302), ProtoExpr.lit(1), ProtoExpr.lit(20.0))
      ),
      ordersSchema,
      allocator
    )
    val result = HashJoinOp.execute(
      leftDups,
      rightDups,
      JoinType.Inner,
      Vector(leftKey),
      Vector(rightKey),
      condition = None,
      buildSide = BuildSide.BuildLeft,
      evaluator,
      allocator
    )
    // 2 left * 2 right = 4 matches
    assertEquals(result.rowCount, 4)

  // ── String keys ──

  test("string key join"):
    val leftSchema = ProtoSchema(
      Vector(ProtoStructField("name", ProtoType.StringType, nullable = false))
    )
    val rightSchema = ProtoSchema(
      Vector(
        ProtoStructField("name", ProtoType.StringType, nullable = false),
        ProtoStructField("score", ProtoType.IntegerType, nullable = false)
      )
    )
    val leftBatch = Batch.fromValues(
      Vector(Vector(ProtoExpr.lit("Alice")), Vector(ProtoExpr.lit("Bob"))),
      leftSchema,
      allocator
    )
    val rightBatch = Batch.fromValues(
      Vector(
        Vector(ProtoExpr.lit("Alice"), ProtoExpr.lit(95)),
        Vector(ProtoExpr.lit("Carol"), ProtoExpr.lit(88))
      ),
      rightSchema,
      allocator
    )
    val nameKey = ProtoExpr.ColumnRef("name", None, ProtoType.StringType, false)
    val result = HashJoinOp.execute(
      leftBatch,
      rightBatch,
      JoinType.Inner,
      Vector(nameKey),
      Vector(nameKey),
      condition = None,
      buildSide = BuildSide.BuildRight,
      evaluator,
      allocator
    )
    assertEquals(result.rowCount, 1)
    val rows = collect(result)
    assertEquals(rows.head("score"), 95)

  // ── Catalog statistics ──

  test("Catalog.getStatistics returns registered stats"):
    import protocatalyst.executor.Catalog
    import protocatalyst.plan.Statistics
    val catalog = Catalog()
    val stats = Statistics(rowCount = 1000, sizeInBytes = 50000)
    catalog.registerStatistics("users", stats)
    assertEquals(catalog.getStatistics("users").rowCount, 1000L)
    assertEquals(catalog.getStatistics("users").sizeInBytes, 50000L)

  test("Catalog.getStatistics auto-derives from batch"):
    import protocatalyst.executor.Catalog
    val catalog = Catalog()
    catalog.registerTable("users", usersBatch)
    val stats = catalog.getStatistics("users")
    assertEquals(stats.rowCount, 3L)
    assert(stats.sizeInBytes > 0)

  test("Catalog.getStatistics returns unknown for missing table"):
    import protocatalyst.executor.Catalog
    val catalog = Catalog()
    val stats = catalog.getStatistics("nonexistent")
    assertEquals(stats.rowCount, -1L)

  test("Catalog.statsProvider works with PhysicalPlanner"):
    import protocatalyst.executor.Catalog
    import protocatalyst.plan._
    import protocatalyst.schema._
    val catalog = Catalog()
    catalog.registerTable("users", usersBatch)
    val planner = PhysicalPlanner(catalog.statsProvider)
    val contract = SchemaContract(
      "users",
      Vector(
        FieldContract("id", ProtoType.IntegerType, expectedNullable = false),
        FieldContract("name", ProtoType.StringType, expectedNullable = true)
      ),
      SchemaFingerprint.fromLong(0L)
    )
    val physical = planner.plan(ProtoLogicalPlan.RelationRef("users", None, contract))
    val ts = physical.asInstanceOf[ProtoPhysicalPlan.TableScan]
    assertEquals(ts.stats.rowCount, 3L)
