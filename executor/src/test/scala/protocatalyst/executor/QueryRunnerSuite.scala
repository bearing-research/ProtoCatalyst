package protocatalyst.executor

import scala.compiletime.uninitialized

import org.apache.arrow.memory.BufferAllocator

import protocatalyst.arrow.ArrowAllocator
import protocatalyst.executor.exec._
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan._
import protocatalyst.schema.ProtoSchema
import protocatalyst.types._

/** End-to-end tests for the executor module.
  *
  * Tests execute ProtoLogicalPlan trees directly against in-memory tables and verify results.
  */
class QueryRunnerSuite extends munit.FunSuite:

  private var allocator: BufferAllocator = uninitialized

  override def beforeAll(): Unit =
    allocator = ArrowAllocator.createRoot()

  override def afterAll(): Unit =
    // Note: In production, batches should be closed before the allocator.
    // For tests, we just let the allocator go out of scope.
    ()

  private def makeUsersBatch(): Batch =
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("name", ProtoType.StringType, nullable = false),
        ProtoStructField("age", ProtoType.IntegerType, nullable = false),
        ProtoStructField("city", ProtoType.StringType, nullable = true)
      )
    )
    Batch.fromValues(
      Vector(
        Vector(ProtoExpr.lit("Alice"), ProtoExpr.lit(30), ProtoExpr.lit("NYC")),
        Vector(ProtoExpr.lit("Bob"), ProtoExpr.lit(25), ProtoExpr.lit("SF")),
        Vector(ProtoExpr.lit("Carol"), ProtoExpr.lit(35), ProtoExpr.lit("NYC")),
        Vector(ProtoExpr.lit("Dave"), ProtoExpr.lit(28), ProtoExpr.lit("LA"))
      ),
      schema,
      allocator
    )

  test("Filter: age > 28") {
    val catalog = Catalog()
    catalog.registerTable("users", makeUsersBatch())

    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.Gt(
        ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false),
        ProtoExpr.lit(28)
      ),
      ProtoLogicalPlan.RelationRef(
        "users",
        None,
        null.asInstanceOf[protocatalyst.schema.SchemaContract]
      )
    )

    val executor = PlanExecutor(catalog, allocator)
    val result = executor.execute(plan)

    assertEquals(result.rowCount, 2)
    val rows = QueryRunner.collect(result)
    assertEquals(rows.map(_("name")).toSet, Set[Any]("Alice", "Carol"))
  }

  test("Project: select name, age + 1 as next_age") {
    val catalog = Catalog()
    catalog.registerTable("users", makeUsersBatch())

    val plan = ProtoLogicalPlan.Project(
      Vector(
        ProtoExpr.Alias(
          ProtoExpr.ColumnRef("name", None, ProtoType.StringType, false),
          "name"
        ),
        ProtoExpr.Alias(
          ProtoExpr.Add(
            ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false),
            ProtoExpr.lit(1)
          ),
          "next_age"
        )
      ),
      ProtoLogicalPlan.RelationRef(
        "users",
        None,
        null.asInstanceOf[protocatalyst.schema.SchemaContract]
      )
    )

    val executor = PlanExecutor(catalog, allocator)
    val result = executor.execute(plan)

    assertEquals(result.rowCount, 4)
    val rows = QueryRunner.collect(result)
    assertEquals(rows.head("name"), "Alice")
    assertEquals(rows.head("next_age"), 31) // 30 + 1
  }

  test("Sort: order by age descending") {
    val catalog = Catalog()
    catalog.registerTable("users", makeUsersBatch())

    val plan = ProtoLogicalPlan.Sort(
      Vector(
        SortOrder(
          ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false),
          SortDirection.Descending,
          NullOrdering.NullsLast
        )
      ),
      ProtoLogicalPlan.RelationRef(
        "users",
        None,
        null.asInstanceOf[protocatalyst.schema.SchemaContract]
      )
    )

    val executor = PlanExecutor(catalog, allocator)
    val result = executor.execute(plan)

    val rows = QueryRunner.collect(result)
    assertEquals(rows.map(_("name")).toVector, Vector("Carol", "Alice", "Dave", "Bob"))
  }

  test("Limit: take 2") {
    val catalog = Catalog()
    catalog.registerTable("users", makeUsersBatch())

    val plan = ProtoLogicalPlan.Limit(
      2,
      ProtoLogicalPlan.RelationRef(
        "users",
        None,
        null.asInstanceOf[protocatalyst.schema.SchemaContract]
      )
    )

    val executor = PlanExecutor(catalog, allocator)
    val result = executor.execute(plan)

    assertEquals(result.rowCount, 2)
  }

  test("Distinct") {
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("x", ProtoType.IntegerType, nullable = false)
      )
    )
    val batch = Batch.fromValues(
      Vector(
        Vector(ProtoExpr.lit(1)),
        Vector(ProtoExpr.lit(2)),
        Vector(ProtoExpr.lit(1)),
        Vector(ProtoExpr.lit(3)),
        Vector(ProtoExpr.lit(2))
      ),
      schema,
      allocator
    )

    val catalog = Catalog()
    catalog.registerTable("nums", batch)

    val plan = ProtoLogicalPlan.Distinct(
      ProtoLogicalPlan.RelationRef(
        "nums",
        None,
        null.asInstanceOf[protocatalyst.schema.SchemaContract]
      )
    )

    val executor = PlanExecutor(catalog, allocator)
    val result = executor.execute(plan)

    assertEquals(result.rowCount, 3)
  }

  test("Aggregate: count and sum by city") {
    val catalog = Catalog()
    catalog.registerTable("users", makeUsersBatch())

    val plan = ProtoLogicalPlan.Aggregate(
      groupingExprs = Vector(
        ProtoExpr.ColumnRef("city", None, ProtoType.StringType, true)
      ),
      aggregateExprs = Vector(
        ProtoExpr.Alias(
          ProtoExpr.Count(
            ProtoExpr.ColumnRef("name", None, ProtoType.StringType, false),
            distinct = false
          ),
          "cnt"
        ),
        ProtoExpr.Alias(
          ProtoExpr.Sum(
            ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false)
          ),
          "total_age"
        )
      ),
      child = ProtoLogicalPlan.RelationRef(
        "users",
        None,
        null.asInstanceOf[protocatalyst.schema.SchemaContract]
      )
    )

    val executor = PlanExecutor(catalog, allocator)
    val result = executor.execute(plan)

    val rows = QueryRunner.collect(result)
    val nycRow = rows.find(_("city") == "NYC").get
    assertEquals(nycRow("cnt"), 2L) // Alice + Carol
    assertEquals(nycRow("total_age"), 65.0) // 30 + 35
  }

  test("Join: inner join") {
    val usersSchema = ProtoSchema(
      Vector(
        ProtoStructField("id", ProtoType.IntegerType, false),
        ProtoStructField("name", ProtoType.StringType, false)
      )
    )
    val usersBatch = Batch.fromValues(
      Vector(
        Vector(ProtoExpr.lit(1), ProtoExpr.lit("Alice")),
        Vector(ProtoExpr.lit(2), ProtoExpr.lit("Bob"))
      ),
      usersSchema,
      allocator
    )

    val ordersSchema = ProtoSchema(
      Vector(
        ProtoStructField("user_id", ProtoType.IntegerType, false),
        ProtoStructField("amount", ProtoType.IntegerType, false)
      )
    )
    val ordersBatch = Batch.fromValues(
      Vector(
        Vector(ProtoExpr.lit(1), ProtoExpr.lit(100)),
        Vector(ProtoExpr.lit(1), ProtoExpr.lit(200)),
        Vector(ProtoExpr.lit(3), ProtoExpr.lit(300))
      ),
      ordersSchema,
      allocator
    )

    val catalog = Catalog()
    catalog.registerTable("users", usersBatch)
    catalog.registerTable("orders", ordersBatch)

    val plan = ProtoLogicalPlan.Join(
      ProtoLogicalPlan
        .RelationRef("users", None, null.asInstanceOf[protocatalyst.schema.SchemaContract]),
      ProtoLogicalPlan
        .RelationRef("orders", None, null.asInstanceOf[protocatalyst.schema.SchemaContract]),
      JoinType.Inner,
      Some(
        ProtoExpr.Eq(
          ProtoExpr.ColumnRef("id", None, ProtoType.IntegerType, false),
          ProtoExpr.ColumnRef("user_id", None, ProtoType.IntegerType, false)
        )
      )
    )

    val executor = PlanExecutor(catalog, allocator)
    val result = executor.execute(plan)

    assertEquals(result.rowCount, 2) // Alice matches 2 orders, Bob matches 0, user_id=3 no match
    val rows = QueryRunner.collect(result)
    assert(rows.forall(_("name") == "Alice"))
  }

  test("Union: combine two tables") {
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("x", ProtoType.IntegerType, false)
      )
    )
    val batch1 = Batch.fromValues(
      Vector(Vector(ProtoExpr.lit(1)), Vector(ProtoExpr.lit(2))),
      schema,
      allocator
    )
    val batch2 = Batch.fromValues(
      Vector(Vector(ProtoExpr.lit(3)), Vector(ProtoExpr.lit(4))),
      schema,
      allocator
    )

    val catalog = Catalog()
    catalog.registerTable("a", batch1)
    catalog.registerTable("b", batch2)

    val plan = ProtoLogicalPlan.Union(
      Vector(
        ProtoLogicalPlan
          .RelationRef("a", None, null.asInstanceOf[protocatalyst.schema.SchemaContract]),
        ProtoLogicalPlan
          .RelationRef("b", None, null.asInstanceOf[protocatalyst.schema.SchemaContract])
      ),
      byName = false,
      allowMissingColumns = false
    )

    val executor = PlanExecutor(catalog, allocator)
    val result = executor.execute(plan)

    assertEquals(result.rowCount, 4)
    val values = QueryRunner.collect(result).map(_("x")).toSet
    assertEquals(values, Set[Any](1, 2, 3, 4))
  }

  test("Values: inline literal rows") {
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("a", ProtoType.IntegerType, false),
        ProtoStructField("b", ProtoType.StringType, false)
      )
    )

    val plan = ProtoLogicalPlan.Values(
      Vector(
        Vector(ProtoExpr.lit(1), ProtoExpr.lit("hello")),
        Vector(ProtoExpr.lit(2), ProtoExpr.lit("world"))
      ),
      schema
    )

    val catalog = Catalog()
    val executor = PlanExecutor(catalog, allocator)
    val result = executor.execute(plan)

    assertEquals(result.rowCount, 2)
    val rows = QueryRunner.collect(result)
    assertEquals(rows(0)("a"), 1)
    assertEquals(rows(0)("b"), "hello")
    assertEquals(rows(1)("a"), 2)
    assertEquals(rows(1)("b"), "world")
  }

  test("CTE: WITH clause") {
    val catalog = Catalog()
    catalog.registerTable("users", makeUsersBatch())

    val plan = ProtoLogicalPlan.With(
      cteRelations = Vector(
        "adults" -> ProtoLogicalPlan.Filter(
          ProtoExpr.GtEq(
            ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false),
            ProtoExpr.lit(30)
          ),
          ProtoLogicalPlan
            .RelationRef("users", None, null.asInstanceOf[protocatalyst.schema.SchemaContract])
        )
      ),
      recursive = false,
      child = ProtoLogicalPlan.RelationRef(
        "adults",
        None,
        null.asInstanceOf[protocatalyst.schema.SchemaContract]
      )
    )

    val executor = PlanExecutor(catalog, allocator)
    val result = executor.execute(plan)

    assertEquals(result.rowCount, 2) // Alice (30) and Carol (35)
  }

  test("Filter + Project pipeline") {
    val catalog = Catalog()
    catalog.registerTable("users", makeUsersBatch())

    // SELECT upper(name) as uname FROM users WHERE age >= 30
    val plan = ProtoLogicalPlan.Project(
      Vector(
        ProtoExpr.Alias(
          ProtoExpr.Upper(ProtoExpr.ColumnRef("name", None, ProtoType.StringType, false)),
          "uname"
        )
      ),
      ProtoLogicalPlan.Filter(
        ProtoExpr.GtEq(
          ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false),
          ProtoExpr.lit(30)
        ),
        ProtoLogicalPlan.RelationRef(
          "users",
          None,
          null.asInstanceOf[protocatalyst.schema.SchemaContract]
        )
      )
    )

    val executor = PlanExecutor(catalog, allocator)
    val result = executor.execute(plan)

    assertEquals(result.rowCount, 2)
    val rows = QueryRunner.collect(result)
    assertEquals(rows.map(_("uname")).toSet, Set[Any]("ALICE", "CAROL"))
  }

  // ============================================================
  // Phase E: Advanced operators
  // ============================================================

  test("Unpivot: columns to rows") {
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("id", ProtoType.IntegerType, false),
        ProtoStructField("q1", ProtoType.IntegerType, false),
        ProtoStructField("q2", ProtoType.IntegerType, false),
        ProtoStructField("q3", ProtoType.IntegerType, false)
      )
    )
    val batch = Batch.fromValues(
      Vector(
        Vector(ProtoExpr.lit(1), ProtoExpr.lit(10), ProtoExpr.lit(20), ProtoExpr.lit(30)),
        Vector(ProtoExpr.lit(2), ProtoExpr.lit(40), ProtoExpr.lit(50), ProtoExpr.lit(60))
      ),
      schema,
      allocator
    )

    val catalog = Catalog()
    catalog.registerTable("sales", batch)

    val plan = ProtoLogicalPlan.Unpivot(
      valueColumnName = "revenue",
      variableColumnName = "quarter",
      columns = Vector(
        (ProtoExpr.ColumnRef("q1", None, ProtoType.IntegerType, false), None),
        (ProtoExpr.ColumnRef("q2", None, ProtoType.IntegerType, false), None),
        (ProtoExpr.ColumnRef("q3", None, ProtoType.IntegerType, false), None)
      ),
      includeNulls = false,
      child = ProtoLogicalPlan.RelationRef(
        "sales",
        None,
        null.asInstanceOf[protocatalyst.schema.SchemaContract]
      )
    )

    val executor = PlanExecutor(catalog, allocator)
    val result = executor.execute(plan)

    assertEquals(result.rowCount, 6) // 2 rows × 3 quarters
    val rows = QueryRunner.collect(result)
    val quarters = rows.map(_("quarter")).toSet
    assertEquals(quarters, Set[Any]("q1", "q2", "q3"))
  }

  test("Pivot: rows to columns") {
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("city", ProtoType.StringType, false),
        ProtoStructField("quarter", ProtoType.StringType, false),
        ProtoStructField("revenue", ProtoType.IntegerType, false)
      )
    )
    val batch = Batch.fromValues(
      Vector(
        Vector(ProtoExpr.lit("NYC"), ProtoExpr.lit("Q1"), ProtoExpr.lit(100)),
        Vector(ProtoExpr.lit("NYC"), ProtoExpr.lit("Q2"), ProtoExpr.lit(200)),
        Vector(ProtoExpr.lit("SF"), ProtoExpr.lit("Q1"), ProtoExpr.lit(300)),
        Vector(ProtoExpr.lit("SF"), ProtoExpr.lit("Q2"), ProtoExpr.lit(400))
      ),
      schema,
      allocator
    )

    val catalog = Catalog()
    catalog.registerTable("sales", batch)

    val plan = ProtoLogicalPlan.Pivot(
      groupingExprs = Vector(
        ProtoExpr.ColumnRef("city", None, ProtoType.StringType, false)
      ),
      pivotColumn = ProtoExpr.ColumnRef("quarter", None, ProtoType.StringType, false),
      pivotValues = Vector(ProtoExpr.lit("Q1"), ProtoExpr.lit("Q2")),
      aggregates = Vector(
        ProtoExpr.Sum(ProtoExpr.ColumnRef("revenue", None, ProtoType.IntegerType, false))
      ),
      child = ProtoLogicalPlan.RelationRef(
        "sales",
        None,
        null.asInstanceOf[protocatalyst.schema.SchemaContract]
      )
    )

    val executor = PlanExecutor(catalog, allocator)
    val result = executor.execute(plan)

    assertEquals(result.rowCount, 2)
    val rows = QueryRunner.collect(result)
    val nycRow = rows.find(_("city") == "NYC").get
    assertEquals(nycRow("Q1"), 100.0)
    assertEquals(nycRow("Q2"), 200.0)
  }

  test("Scalar subquery") {
    val catalog = Catalog()
    catalog.registerTable("users", makeUsersBatch())

    // SELECT name, (SELECT MAX(age) FROM users) as max_age FROM users WHERE age = 30
    val plan = ProtoLogicalPlan.Project(
      Vector(
        ProtoExpr.Alias(
          ProtoExpr.ColumnRef("name", None, ProtoType.StringType, false),
          "name"
        ),
        ProtoExpr.Alias(
          ProtoExpr.ScalarSubquery(
            ProtoLogicalPlan.Aggregate(
              groupingExprs = Vector.empty,
              aggregateExprs = Vector(
                ProtoExpr.Alias(
                  ProtoExpr.Max(
                    ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false)
                  ),
                  "max_age"
                )
              ),
              child = ProtoLogicalPlan
                .RelationRef("users", None, null.asInstanceOf[protocatalyst.schema.SchemaContract])
            )
          ),
          "max_age"
        )
      ),
      ProtoLogicalPlan.Filter(
        ProtoExpr.Eq(
          ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false),
          ProtoExpr.lit(30)
        ),
        ProtoLogicalPlan.RelationRef(
          "users",
          None,
          null.asInstanceOf[protocatalyst.schema.SchemaContract]
        )
      )
    )

    val executor = PlanExecutor(catalog, allocator)
    val result = executor.execute(plan)

    assertEquals(result.rowCount, 1)
    val rows = QueryRunner.collect(result)
    assertEquals(rows.head("name"), "Alice")
    assertEquals(rows.head("max_age"), 35.0) // Max age in users is Carol's 35
  }

  test("Exists subquery") {
    val catalog = Catalog()
    catalog.registerTable("users", makeUsersBatch())

    // SELECT name FROM users WHERE EXISTS (SELECT 1 FROM users WHERE age > 100)
    val plan = ProtoLogicalPlan.Project(
      Vector(
        ProtoExpr.Alias(
          ProtoExpr.ColumnRef("name", None, ProtoType.StringType, false),
          "name"
        )
      ),
      ProtoLogicalPlan.Filter(
        ProtoExpr.Exists(
          ProtoLogicalPlan.Filter(
            ProtoExpr.Gt(
              ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false),
              ProtoExpr.lit(100)
            ),
            ProtoLogicalPlan
              .RelationRef("users", None, null.asInstanceOf[protocatalyst.schema.SchemaContract])
          )
        ),
        ProtoLogicalPlan.RelationRef(
          "users",
          None,
          null.asInstanceOf[protocatalyst.schema.SchemaContract]
        )
      )
    )

    val executor = PlanExecutor(catalog, allocator)
    val result = executor.execute(plan)

    assertEquals(result.rowCount, 0) // No users with age > 100, so EXISTS is false
  }

  test("Window: row_number + rank") {
    val catalog = Catalog()
    catalog.registerTable("users", makeUsersBatch())

    // Simple row_number test without window frame (already tested)
    // Test first_value
    val plan = ProtoLogicalPlan.Window(
      windowExprs = Vector(
        ProtoExpr.Alias(ProtoExpr.RowNumber(), "rn")
      ),
      partitionSpec = Vector(
        ProtoExpr.ColumnRef("city", None, ProtoType.StringType, true)
      ),
      orderSpec = Vector(
        SortOrder(
          ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false),
          SortDirection.Ascending,
          NullOrdering.NullsLast
        )
      ),
      child = ProtoLogicalPlan.RelationRef(
        "users",
        None,
        null.asInstanceOf[protocatalyst.schema.SchemaContract]
      )
    )

    val executor = PlanExecutor(catalog, allocator)
    val result = executor.execute(plan)

    assertEquals(result.rowCount, 4)
    val rows = QueryRunner.collect(result)
    // In NYC partition (Alice=30, Carol=35), row numbers should be 1 and 2
    val nycRows = rows.filter(_("city") == "NYC").sortBy(_("rn").asInstanceOf[Long])
    assertEquals(nycRows.size, 2)
    assertEquals(nycRows(0)("rn"), 1L)
    assertEquals(nycRows(1)("rn"), 2L)
  }

  test("Window: first_value and last_value") {
    val catalog = Catalog()
    catalog.registerTable("users", makeUsersBatch())

    val plan = ProtoLogicalPlan.Window(
      windowExprs = Vector(
        ProtoExpr.Alias(
          ProtoExpr.FirstValue(
            ProtoExpr.ColumnRef("name", None, ProtoType.StringType, false),
            ignoreNulls = false
          ),
          "first_name"
        ),
        ProtoExpr.Alias(
          ProtoExpr.LastValue(
            ProtoExpr.ColumnRef("name", None, ProtoType.StringType, false),
            ignoreNulls = false
          ),
          "last_name"
        )
      ),
      partitionSpec = Vector(
        ProtoExpr.ColumnRef("city", None, ProtoType.StringType, true)
      ),
      orderSpec = Vector(
        SortOrder(
          ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false),
          SortDirection.Ascending,
          NullOrdering.NullsLast
        )
      ),
      child = ProtoLogicalPlan.RelationRef(
        "users",
        None,
        null.asInstanceOf[protocatalyst.schema.SchemaContract]
      )
    )

    val executor = PlanExecutor(catalog, allocator)
    val result = executor.execute(plan)

    val rows = QueryRunner.collect(result)
    // NYC partition ordered by age: Alice(30), Carol(35)
    val nycRows = rows.filter(_("city") == "NYC")
    assert(nycRows.forall(_("first_name") == "Alice"))
    assert(nycRows.forall(_("last_name") == "Carol"))
  }

  test("Window: sum aggregate") {
    val catalog = Catalog()
    catalog.registerTable("users", makeUsersBatch())

    val plan = ProtoLogicalPlan.Window(
      windowExprs = Vector(
        ProtoExpr.Alias(
          ProtoExpr.Sum(ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false)),
          "total_age"
        )
      ),
      partitionSpec = Vector(
        ProtoExpr.ColumnRef("city", None, ProtoType.StringType, true)
      ),
      orderSpec = Vector.empty,
      child = ProtoLogicalPlan.RelationRef(
        "users",
        None,
        null.asInstanceOf[protocatalyst.schema.SchemaContract]
      )
    )

    val executor = PlanExecutor(catalog, allocator)
    val result = executor.execute(plan)

    val rows = QueryRunner.collect(result)
    val nycRows = rows.filter(_("city") == "NYC")
    // NYC: Alice(30) + Carol(35) = 65
    assert(nycRows.forall(_("total_age") == 65.0))
  }

  test("LateralJoin: cross join equivalent") {
    val usersSchema = ProtoSchema(
      Vector(
        ProtoStructField("name", ProtoType.StringType, false)
      )
    )
    val usersBatch = Batch.fromValues(
      Vector(
        Vector(ProtoExpr.lit("Alice")),
        Vector(ProtoExpr.lit("Bob"))
      ),
      usersSchema,
      allocator
    )

    val colorsSchema = ProtoSchema(
      Vector(
        ProtoStructField("color", ProtoType.StringType, false)
      )
    )
    val colorsBatch = Batch.fromValues(
      Vector(
        Vector(ProtoExpr.lit("red")),
        Vector(ProtoExpr.lit("blue"))
      ),
      colorsSchema,
      allocator
    )

    val catalog = Catalog()
    catalog.registerTable("users", usersBatch)
    catalog.registerTable("colors", colorsBatch)

    val plan = ProtoLogicalPlan.LateralJoin(
      left = ProtoLogicalPlan
        .RelationRef("users", None, null.asInstanceOf[protocatalyst.schema.SchemaContract]),
      lateral = ProtoLogicalPlan
        .RelationRef("colors", None, null.asInstanceOf[protocatalyst.schema.SchemaContract]),
      condition = None
    )

    val executor = PlanExecutor(catalog, allocator)
    val result = executor.execute(plan)

    assertEquals(result.rowCount, 4) // 2 × 2 cross join
    val rows = QueryRunner.collect(result)
    val pairs = rows.map(r => (r("name"), r("color"))).toSet
    assertEquals(
      pairs,
      Set[(Any, Any)](
        ("Alice", "red"),
        ("Alice", "blue"),
        ("Bob", "red"),
        ("Bob", "blue")
      )
    )
  }
