package protocatalyst.sql

import protocatalyst.expr._
import protocatalyst.plan.{JoinType, NullOrdering, ProtoLogicalPlan, SortDirection, SortOrder}
import protocatalyst.schema.{FieldContract, ProtoSchema, SchemaContract, SchemaFingerprint}
import protocatalyst.types.{ProtoStructField, ProtoType}

class SqlGeneratorSuite extends munit.FunSuite:
  import SqlGenerator.generate

  // Helper to create a SchemaContract
  def makeContract(name: String, fields: (String, ProtoType, Boolean)*): SchemaContract =
    val fieldContracts = fields.toVector.map { case (fname, ftype, nullable) =>
      FieldContract(fname, ftype, nullable)
    }
    val structFields = fields.toVector.map { case (fname, ftype, nullable) =>
      ProtoStructField(fname, ftype, nullable)
    }
    SchemaContract(name, fieldContracts, SchemaFingerprint.compute(structFields))

  // ========== Leaf Nodes ==========
  test("RelationRef without alias"):
    val plan = ProtoLogicalPlan.RelationRef(
      "users",
      None,
      makeContract(
        "users",
        ("id", ProtoType.IntegerType, false),
        ("name", ProtoType.StringType, true)
      )
    )
    assertEquals(generate(plan), "users")

  test("RelationRef with alias"):
    val plan = ProtoLogicalPlan.RelationRef(
      "users",
      Some("u"),
      makeContract(
        "users",
        ("id", ProtoType.IntegerType, false),
        ("name", ProtoType.StringType, true)
      )
    )
    assertEquals(generate(plan), "users AS u")

  test("Values with rows"):
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("col1", ProtoType.IntegerType, false),
        ProtoStructField("col2", ProtoType.StringType, true)
      )
    )
    val plan = ProtoLogicalPlan.Values(
      Vector(
        Vector(ProtoExpr.lit(1), ProtoExpr.lit("a")),
        Vector(ProtoExpr.lit(2), ProtoExpr.lit("b"))
      ),
      schema
    )
    assertEquals(generate(plan), "VALUES (1, 'a'), (2, 'b')")

  test("Values empty"):
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("col1", ProtoType.IntegerType, false)
      )
    )
    val plan = ProtoLogicalPlan.Values(Vector.empty, schema)
    assertEquals(generate(plan), "VALUES (NULL)")

  // ========== Unary Operators ==========
  test("Project"):
    val child = ProtoLogicalPlan.RelationRef(
      "users",
      None,
      makeContract(
        "users",
        ("id", ProtoType.IntegerType, false),
        ("name", ProtoType.StringType, true)
      )
    )
    val plan = ProtoLogicalPlan.Project(
      Vector(
        ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true),
        ProtoExpr.ColumnRef("id", None, ProtoType.IntegerType, nullable = false)
      ),
      child
    )
    assertEquals(generate(plan), "SELECT name, id FROM users")

  test("Filter"):
    val child = ProtoLogicalPlan.RelationRef(
      "users",
      None,
      makeContract(
        "users",
        ("id", ProtoType.IntegerType, false),
        ("age", ProtoType.IntegerType, false)
      )
    )
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.Gt(
        ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, nullable = false),
        ProtoExpr.lit(18)
      ),
      child
    )
    assertEquals(generate(plan), "SELECT * FROM users WHERE age > 18")

  test("Aggregate"):
    val child = ProtoLogicalPlan.RelationRef(
      "orders",
      None,
      makeContract(
        "orders",
        ("user_id", ProtoType.IntegerType, false),
        ("amount", ProtoType.DoubleType, false)
      )
    )
    val plan = ProtoLogicalPlan.Aggregate(
      groupingExprs =
        Vector(ProtoExpr.ColumnRef("user_id", None, ProtoType.IntegerType, nullable = false)),
      aggregateExprs = Vector(
        ProtoExpr.Sum(ProtoExpr.ColumnRef("amount", None, ProtoType.DoubleType, nullable = false))
      ),
      child
    )
    val sql = generate(plan)
    assert(sql.contains("SELECT"))
    assert(sql.contains("user_id"))
    assert(sql.contains("SUM(amount)"))
    assert(sql.contains("GROUP BY user_id"))

  test("Sort"):
    val child = ProtoLogicalPlan.RelationRef(
      "users",
      None,
      makeContract(
        "users",
        ("name", ProtoType.StringType, true),
        ("age", ProtoType.IntegerType, false)
      )
    )
    val plan = ProtoLogicalPlan.Sort(
      Vector(
        SortOrder(
          ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, nullable = false),
          SortDirection.Descending,
          NullOrdering.NullsLast
        )
      ),
      child
    )
    assertEquals(generate(plan), "SELECT * FROM users ORDER BY age DESC NULLS LAST")

  test("Limit"):
    val child = ProtoLogicalPlan.RelationRef(
      "users",
      None,
      makeContract("users", ("id", ProtoType.IntegerType, false))
    )
    val plan = ProtoLogicalPlan.Limit(10, child)
    assertEquals(generate(plan), "SELECT * FROM users LIMIT 10")

  test("Distinct"):
    val child = ProtoLogicalPlan.RelationRef(
      "users",
      None,
      makeContract("users", ("name", ProtoType.StringType, true))
    )
    val plan = ProtoLogicalPlan.Distinct(child)
    assertEquals(generate(plan), "SELECT DISTINCT * FROM users")

  test("SubqueryAlias"):
    val child = ProtoLogicalPlan.RelationRef(
      "users",
      None,
      makeContract("users", ("id", ProtoType.IntegerType, false))
    )
    val plan = ProtoLogicalPlan.SubqueryAlias("u", child)
    assertEquals(generate(plan), "(users) AS u")

  // ========== Binary Operators ==========
  test("Join - Inner"):
    val left = ProtoLogicalPlan.RelationRef(
      "users",
      None,
      makeContract("users", ("id", ProtoType.IntegerType, false))
    )
    val right = ProtoLogicalPlan.RelationRef(
      "orders",
      None,
      makeContract("orders", ("user_id", ProtoType.IntegerType, false))
    )
    val plan = ProtoLogicalPlan.Join(
      left,
      right,
      JoinType.Inner,
      Some(
        ProtoExpr.Eq(
          ProtoExpr.ColumnRef("id", Some("users"), ProtoType.IntegerType, nullable = false),
          ProtoExpr.ColumnRef("user_id", Some("orders"), ProtoType.IntegerType, nullable = false)
        )
      )
    )
    val sql = generate(plan)
    assert(sql.contains("INNER JOIN"))
    assert(sql.contains("users"))
    assert(sql.contains("orders"))
    assert(sql.contains("ON"))

  test("Join - Left Outer"):
    val left = ProtoLogicalPlan.RelationRef(
      "users",
      None,
      makeContract("users", ("id", ProtoType.IntegerType, false))
    )
    val right = ProtoLogicalPlan.RelationRef(
      "orders",
      None,
      makeContract("orders", ("user_id", ProtoType.IntegerType, false))
    )
    val plan = ProtoLogicalPlan.Join(
      left,
      right,
      JoinType.LeftOuter,
      Some(
        ProtoExpr.Eq(
          ProtoExpr.ColumnRef("id", None, ProtoType.IntegerType, nullable = false),
          ProtoExpr.ColumnRef("user_id", None, ProtoType.IntegerType, nullable = false)
        )
      )
    )
    val sql = generate(plan)
    assert(sql.contains("LEFT OUTER JOIN"))

  test("Join - Cross"):
    val left = ProtoLogicalPlan.RelationRef(
      "users",
      None,
      makeContract("users", ("id", ProtoType.IntegerType, false))
    )
    val right = ProtoLogicalPlan.RelationRef(
      "orders",
      None,
      makeContract("orders", ("id", ProtoType.IntegerType, false))
    )
    val plan = ProtoLogicalPlan.Join(
      left,
      right,
      JoinType.Cross,
      None
    )
    val sql = generate(plan)
    assert(sql.contains("CROSS JOIN"))

  // ========== Set Operations ==========
  test("Union"):
    val left = ProtoLogicalPlan.RelationRef(
      "users1",
      None,
      makeContract("users1", ("id", ProtoType.IntegerType, false))
    )
    val right = ProtoLogicalPlan.RelationRef(
      "users2",
      None,
      makeContract("users2", ("id", ProtoType.IntegerType, false))
    )
    val plan =
      ProtoLogicalPlan.Union(Vector(left, right), byName = false, allowMissingColumns = false)
    assertEquals(generate(plan), "users1 UNION ALL users2")

  test("Union with multiple children"):
    val child1 = ProtoLogicalPlan.RelationRef(
      "t1",
      None,
      makeContract("t1", ("col", ProtoType.IntegerType, false))
    )
    val child2 = ProtoLogicalPlan.RelationRef(
      "t2",
      None,
      makeContract("t2", ("col", ProtoType.IntegerType, false))
    )
    val child3 = ProtoLogicalPlan.RelationRef(
      "t3",
      None,
      makeContract("t3", ("col", ProtoType.IntegerType, false))
    )
    val plan = ProtoLogicalPlan.Union(
      Vector(child1, child2, child3),
      byName = false,
      allowMissingColumns = false
    )
    assertEquals(generate(plan), "t1 UNION ALL t2 UNION ALL t3")

  test("Intersect"):
    val left = ProtoLogicalPlan.RelationRef(
      "users1",
      None,
      makeContract("users1", ("id", ProtoType.IntegerType, false))
    )
    val right = ProtoLogicalPlan.RelationRef(
      "users2",
      None,
      makeContract("users2", ("id", ProtoType.IntegerType, false))
    )
    val plan = ProtoLogicalPlan.Intersect(left, right, isAll = false)
    assertEquals(generate(plan), "(users1) INTERSECT (users2)")

  test("Except"):
    val left = ProtoLogicalPlan.RelationRef(
      "users1",
      None,
      makeContract("users1", ("id", ProtoType.IntegerType, false))
    )
    val right = ProtoLogicalPlan.RelationRef(
      "users2",
      None,
      makeContract("users2", ("id", ProtoType.IntegerType, false))
    )
    val plan = ProtoLogicalPlan.Except(left, right, isAll = false)
    assertEquals(generate(plan), "(users1) EXCEPT (users2)")

  // ========== Window ==========
  test("Window"):
    val child = ProtoLogicalPlan.RelationRef(
      "sales",
      None,
      makeContract(
        "sales",
        ("dept", ProtoType.StringType, false),
        ("amount", ProtoType.DoubleType, false)
      )
    )
    val plan = ProtoLogicalPlan.Window(
      windowExprs = Vector(
        ProtoExpr.WindowExpr(
          ProtoExpr.RowNumber(),
          Vector(ProtoExpr.ColumnRef("dept", None, ProtoType.StringType, nullable = false)),
          Vector(
            SortOrder(
              ProtoExpr.ColumnRef("amount", None, ProtoType.DoubleType, nullable = false),
              SortDirection.Descending,
              NullOrdering.NullsLast
            )
          ),
          None
        )
      ),
      partitionSpec = Vector.empty,
      orderSpec = Vector.empty,
      child
    )
    val sql = generate(plan)
    assert(sql.contains("ROW_NUMBER()"))
    assert(sql.contains("OVER"))
    assert(sql.contains("PARTITION BY dept"))

  // ========== CTE (WITH) ==========
  test("With single CTE"):
    val cte = ProtoLogicalPlan.RelationRef(
      "users",
      None,
      makeContract("users", ("id", ProtoType.IntegerType, false))
    )
    val child = ProtoLogicalPlan.RelationRef(
      "user_cte",
      None,
      makeContract("user_cte", ("id", ProtoType.IntegerType, false))
    )
    val plan = ProtoLogicalPlan.With(
      Vector(("user_cte", cte)),
      recursive = false,
      child
    )
    assertEquals(generate(plan), "WITH user_cte AS (users) user_cte")

  test("With multiple CTEs"):
    val cte1 = ProtoLogicalPlan.RelationRef(
      "users",
      None,
      makeContract("users", ("id", ProtoType.IntegerType, false))
    )
    val cte2 = ProtoLogicalPlan.RelationRef(
      "orders",
      None,
      makeContract("orders", ("id", ProtoType.IntegerType, false))
    )
    val child = ProtoLogicalPlan.RelationRef(
      "cte1",
      None,
      makeContract("cte1", ("id", ProtoType.IntegerType, false))
    )
    val plan = ProtoLogicalPlan.With(
      Vector(("cte1", cte1), ("cte2", cte2)),
      recursive = false,
      child
    )
    assertEquals(generate(plan), "WITH cte1 AS (users), cte2 AS (orders) cte1")

  test("With recursive CTE"):
    val cte = ProtoLogicalPlan.RelationRef(
      "base",
      None,
      makeContract("base", ("id", ProtoType.IntegerType, false))
    )
    val child = ProtoLogicalPlan.RelationRef(
      "recursive_cte",
      None,
      makeContract("recursive_cte", ("id", ProtoType.IntegerType, false))
    )
    val plan = ProtoLogicalPlan.With(
      Vector(("recursive_cte", cte)),
      recursive = true,
      child
    )
    assertEquals(generate(plan), "WITH RECURSIVE recursive_cte AS (base) recursive_cte")

  // ========== Unsupported Nodes ==========
  test("Pivot throws exception"):
    val child = ProtoLogicalPlan.RelationRef(
      "sales",
      None,
      makeContract("sales", ("year", ProtoType.IntegerType, false))
    )
    val plan = ProtoLogicalPlan.Pivot(
      groupingExprs = Vector.empty,
      pivotColumn = ProtoExpr.ColumnRef("year", None, ProtoType.IntegerType, nullable = false),
      pivotValues = Vector.empty,
      aggregates = Vector.empty,
      child
    )
    intercept[UnsupportedSqlFeatureException] {
      generate(plan)
    }

  test("Unpivot throws exception"):
    val child = ProtoLogicalPlan.RelationRef(
      "sales",
      None,
      makeContract("sales", ("q1", ProtoType.DoubleType, false))
    )
    val plan = ProtoLogicalPlan.Unpivot(
      valueColumnName = "amount",
      variableColumnName = "quarter",
      columns = Vector.empty,
      includeNulls = false,
      child
    )
    intercept[UnsupportedSqlFeatureException] {
      generate(plan)
    }

  test("LateralJoin throws exception"):
    val left = ProtoLogicalPlan.RelationRef(
      "users",
      None,
      makeContract("users", ("id", ProtoType.IntegerType, false))
    )
    val right = ProtoLogicalPlan.RelationRef(
      "orders",
      None,
      makeContract("orders", ("user_id", ProtoType.IntegerType, false))
    )
    val plan = ProtoLogicalPlan.LateralJoin(left, right, None)
    intercept[UnsupportedSqlFeatureException] {
      generate(plan)
    }

  test("Generate throws exception"):
    val child = ProtoLogicalPlan.RelationRef(
      "users",
      None,
      makeContract("users", ("tags", ProtoType.ArrayType(ProtoType.StringType, true), true))
    )
    val plan = ProtoLogicalPlan.Generate(
      generator = ProtoExpr.Explode(
        ProtoExpr
          .ColumnRef("tags", None, ProtoType.ArrayType(ProtoType.StringType, true), nullable = true)
      ),
      generatorOutput = Vector("tag"),
      outer = false,
      child
    )
    intercept[UnsupportedSqlFeatureException] {
      generate(plan)
    }

  test("ResolvedHint skips hint and processes child"):
    val child = ProtoLogicalPlan.RelationRef(
      "users",
      None,
      makeContract("users", ("id", ProtoType.IntegerType, false))
    )
    val plan = ProtoLogicalPlan.ResolvedHint(Vector.empty, child)
    assertEquals(generate(plan), "users")

  // ========== Complex Nested Query ==========
  test("Nested query with Project, Filter, Join"):
    val users = ProtoLogicalPlan.RelationRef(
      "users",
      None,
      makeContract(
        "users",
        ("id", ProtoType.IntegerType, false),
        ("name", ProtoType.StringType, true),
        ("age", ProtoType.IntegerType, false)
      )
    )
    val orders = ProtoLogicalPlan.RelationRef(
      "orders",
      None,
      makeContract(
        "orders",
        ("id", ProtoType.IntegerType, false),
        ("user_id", ProtoType.IntegerType, false),
        ("amount", ProtoType.DoubleType, false)
      )
    )

    val filteredUsers = ProtoLogicalPlan.Filter(
      ProtoExpr.Gt(
        ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, nullable = false),
        ProtoExpr.lit(18)
      ),
      users
    )

    val joined = ProtoLogicalPlan.Join(
      filteredUsers,
      orders,
      JoinType.Inner,
      Some(
        ProtoExpr.Eq(
          ProtoExpr.ColumnRef("id", Some("users"), ProtoType.IntegerType, nullable = false),
          ProtoExpr.ColumnRef("user_id", Some("orders"), ProtoType.IntegerType, nullable = false)
        )
      )
    )

    val plan = ProtoLogicalPlan.Project(
      Vector(
        ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true),
        ProtoExpr.ColumnRef("amount", None, ProtoType.DoubleType, nullable = false)
      ),
      joined
    )

    val sql = generate(plan)
    assert(sql.contains("SELECT"))
    assert(sql.contains("name"))
    assert(sql.contains("amount"))
    assert(sql.contains("INNER JOIN"))
    assert(sql.contains("WHERE age > 18"))

end SqlGeneratorSuite
