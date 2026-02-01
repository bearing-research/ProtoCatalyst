package protocatalyst.codec

import protocatalyst.artifact._
import protocatalyst.expr._
import protocatalyst.plan._
import protocatalyst.schema._
import protocatalyst.types._

class JsonArtifactCodecSuite extends munit.FunSuite:

  // === ProtoType Roundtrip Tests ===

  test("roundtrip primitive types through JSON"):
    val primitives = List(
      ProtoType.BooleanType,
      ProtoType.ByteType,
      ProtoType.ShortType,
      ProtoType.IntType,
      ProtoType.LongType,
      ProtoType.FloatType,
      ProtoType.DoubleType,
      ProtoType.StringType,
      ProtoType.BinaryType,
      ProtoType.DateType,
      ProtoType.TimestampType,
      ProtoType.TimestampNTZType
    )

    for pt <- primitives do
      val artifact = makeArtifact(ProtoExpr.lit(1), schemaWithType(pt))
      val bytes = JsonArtifactCodec.serialize(artifact)
      val result = JsonArtifactCodec.deserialize(bytes)
      assert(result.isRight, s"Failed for $pt: ${result.left.getOrElse("")}")
      assertEquals(result.toOption.get.outputSchema.fields.head.dataType, pt)

  test("roundtrip DecimalType"):
    val decimal = ProtoType.DecimalType(18, 6)
    val artifact = makeArtifact(ProtoExpr.lit(1), schemaWithType(decimal))
    val bytes = JsonArtifactCodec.serialize(artifact)
    val result = JsonArtifactCodec.deserialize(bytes)
    assert(result.isRight)
    result.toOption.get.outputSchema.fields.head.dataType match
      case ProtoType.DecimalType(p, s) =>
        assertEquals(p, 18)
        assertEquals(s, 6)
      case other => fail(s"Expected DecimalType, got $other")

  test("roundtrip ArrayType"):
    val arrayType = ProtoType.ArrayType(ProtoType.IntType, containsNull = true)
    val artifact = makeArtifact(ProtoExpr.lit(1), schemaWithType(arrayType))
    val bytes = JsonArtifactCodec.serialize(artifact)
    val result = JsonArtifactCodec.deserialize(bytes)
    assert(result.isRight)
    assertEquals(result.toOption.get.outputSchema.fields.head.dataType, arrayType)

  test("roundtrip MapType"):
    val mapType =
      ProtoType.MapType(ProtoType.StringType, ProtoType.IntType, valueContainsNull = true)
    val artifact = makeArtifact(ProtoExpr.lit(1), schemaWithType(mapType))
    val bytes = JsonArtifactCodec.serialize(artifact)
    val result = JsonArtifactCodec.deserialize(bytes)
    assert(result.isRight)
    assertEquals(result.toOption.get.outputSchema.fields.head.dataType, mapType)

  test("roundtrip StructType"):
    val structType = ProtoType.StructType(
      Vector(
        ProtoStructField("x", ProtoType.IntType, nullable = false),
        ProtoStructField("y", ProtoType.StringType, nullable = true)
      )
    )
    val artifact = makeArtifact(ProtoExpr.lit(1), schemaWithType(structType))
    val bytes = JsonArtifactCodec.serialize(artifact)
    val result = JsonArtifactCodec.deserialize(bytes)
    assert(result.isRight)
    assertEquals(result.toOption.get.outputSchema.fields.head.dataType, structType)

  test("roundtrip nested complex types"):
    // Array of Maps of Structs
    val nestedType = ProtoType.ArrayType(
      ProtoType.MapType(
        ProtoType.StringType,
        ProtoType.StructType(
          Vector(
            ProtoStructField("value", ProtoType.DoubleType, nullable = false)
          )
        ),
        valueContainsNull = true
      ),
      containsNull = false
    )
    val artifact = makeArtifact(ProtoExpr.lit(1), schemaWithType(nestedType))
    val bytes = JsonArtifactCodec.serialize(artifact)
    val result = JsonArtifactCodec.deserialize(bytes)
    assert(result.isRight)
    assertEquals(result.toOption.get.outputSchema.fields.head.dataType, nestedType)

  // === LiteralValue Roundtrip Tests ===

  test("roundtrip LiteralValue.BooleanValue"):
    val expr = ProtoExpr.Literal(LiteralValue.BooleanValue(true))
    val artifact = makeArtifact(expr, simpleSchema)
    assertExprRoundtrip(artifact, expr)

  test("roundtrip LiteralValue.ByteValue"):
    val expr = ProtoExpr.Literal(LiteralValue.ByteValue(42.toByte))
    val artifact = makeArtifact(expr, simpleSchema)
    assertExprRoundtrip(artifact, expr)

  test("roundtrip LiteralValue.ShortValue"):
    val expr = ProtoExpr.Literal(LiteralValue.ShortValue(1000.toShort))
    val artifact = makeArtifact(expr, simpleSchema)
    assertExprRoundtrip(artifact, expr)

  test("roundtrip LiteralValue.IntValue"):
    val expr = ProtoExpr.Literal(LiteralValue.IntValue(123456))
    val artifact = makeArtifact(expr, simpleSchema)
    assertExprRoundtrip(artifact, expr)

  test("roundtrip LiteralValue.LongValue"):
    val expr = ProtoExpr.Literal(LiteralValue.LongValue(9876543210L))
    val artifact = makeArtifact(expr, simpleSchema)
    assertExprRoundtrip(artifact, expr)

  test("roundtrip LiteralValue.FloatValue"):
    val expr = ProtoExpr.Literal(LiteralValue.FloatValue(3.14f))
    val artifact = makeArtifact(expr, simpleSchema)
    assertExprRoundtrip(artifact, expr)

  test("roundtrip LiteralValue.DoubleValue"):
    val expr = ProtoExpr.Literal(LiteralValue.DoubleValue(2.718281828))
    val artifact = makeArtifact(expr, simpleSchema)
    assertExprRoundtrip(artifact, expr)

  test("roundtrip LiteralValue.StringValue"):
    val expr = ProtoExpr.Literal(LiteralValue.StringValue("hello world"))
    val artifact = makeArtifact(expr, simpleSchema)
    assertExprRoundtrip(artifact, expr)

  test("roundtrip LiteralValue.StringValue with unicode"):
    val expr = ProtoExpr.Literal(LiteralValue.StringValue("hello 世界 🌍"))
    val artifact = makeArtifact(expr, simpleSchema)
    assertExprRoundtrip(artifact, expr)

  test("roundtrip LiteralValue.BinaryValue"):
    val expr = ProtoExpr.Literal(LiteralValue.BinaryValue(Array[Byte](1, 2, 3, 4, 5)))
    val artifact = makeArtifact(expr, simpleSchema)
    val bytes = JsonArtifactCodec.serialize(artifact)
    val result = JsonArtifactCodec.deserialize(bytes)
    assert(result.isRight)
    val resultExpr = extractFirstExpr(result.toOption.get)
    resultExpr match
      case ProtoExpr.Literal(LiteralValue.BinaryValue(arr)) =>
        assert(arr.sameElements(Array[Byte](1, 2, 3, 4, 5)))
      case _ => fail(s"Expected BinaryValue, got $resultExpr")

  test("roundtrip LiteralValue.DecimalValue"):
    val expr = ProtoExpr.Literal(LiteralValue.DecimalValue(BigDecimal("123.456")))
    val artifact = makeArtifact(expr, simpleSchema)
    assertExprRoundtrip(artifact, expr)

  test("roundtrip LiteralValue.DateValue"):
    val expr = ProtoExpr.Literal(LiteralValue.DateValue(19000)) // epoch days
    val artifact = makeArtifact(expr, simpleSchema)
    assertExprRoundtrip(artifact, expr)

  test("roundtrip LiteralValue.TimestampValue"):
    val expr = ProtoExpr.Literal(LiteralValue.TimestampValue(1700000000000000L)) // epoch micros
    val artifact = makeArtifact(expr, simpleSchema)
    assertExprRoundtrip(artifact, expr)

  test("roundtrip LiteralValue.NullValue"):
    val expr = ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.StringType))
    val artifact = makeArtifact(expr, simpleSchema)
    assertExprRoundtrip(artifact, expr)

  // === Expression Roundtrip Tests ===

  test("roundtrip ColumnRef"):
    val expr = ProtoExpr.ColumnRef("name", Some("t1"), ProtoType.StringType, nullable = true)
    val artifact = makeArtifact(expr, simpleSchema)
    assertExprRoundtrip(artifact, expr)

  test("roundtrip BoundRef"):
    val expr = ProtoExpr.BoundRef(0, ProtoType.IntType, nullable = false)
    val artifact = makeArtifact(expr, simpleSchema)
    assertExprRoundtrip(artifact, expr)

  test("roundtrip comparison expressions"):
    val left = ProtoExpr.lit(10)
    val right = ProtoExpr.lit(20)
    val exprs = List(
      ProtoExpr.Eq(left, right),
      ProtoExpr.NotEq(left, right),
      ProtoExpr.Lt(left, right),
      ProtoExpr.LtEq(left, right),
      ProtoExpr.Gt(left, right),
      ProtoExpr.GtEq(left, right)
    )
    for expr <- exprs do
      val artifact = makeArtifact(expr, simpleSchema)
      assertExprRoundtrip(artifact, expr)

  test("roundtrip logical expressions"):
    val a = ProtoExpr.lit(true)
    val b = ProtoExpr.lit(false)
    val exprs = List(
      ProtoExpr.And(Vector(a, b)),
      ProtoExpr.Or(Vector(a, b)),
      ProtoExpr.Not(a)
    )
    for expr <- exprs do
      val artifact = makeArtifact(expr, simpleSchema)
      assertExprRoundtrip(artifact, expr)

  test("roundtrip null-handling expressions"):
    val col = ProtoExpr.ColumnRef("x", None, ProtoType.IntType, nullable = true)
    val exprs = List(
      ProtoExpr.IsNull(col),
      ProtoExpr.IsNotNull(col),
      ProtoExpr.Coalesce(Vector(col, ProtoExpr.lit(0)))
    )
    for expr <- exprs do
      val artifact = makeArtifact(expr, simpleSchema)
      assertExprRoundtrip(artifact, expr)

  test("roundtrip arithmetic expressions"):
    val left = ProtoExpr.lit(10)
    val right = ProtoExpr.lit(5)
    val exprs = List(
      ProtoExpr.Add(left, right),
      ProtoExpr.Subtract(left, right),
      ProtoExpr.Multiply(left, right),
      ProtoExpr.Divide(left, right)
    )
    for expr <- exprs do
      val artifact = makeArtifact(expr, simpleSchema)
      assertExprRoundtrip(artifact, expr)

  test("roundtrip string expressions"):
    val str = ProtoExpr.lit("hello")
    val exprs = List(
      ProtoExpr.Concat(Vector(str, ProtoExpr.lit(" world"))),
      ProtoExpr.Substring(str, ProtoExpr.lit(1), ProtoExpr.lit(3)),
      ProtoExpr.Upper(str),
      ProtoExpr.Lower(str)
    )
    for expr <- exprs do
      val artifact = makeArtifact(expr, simpleSchema)
      assertExprRoundtrip(artifact, expr)

  test("roundtrip aggregate expressions"):
    val col = ProtoExpr.ColumnRef("value", None, ProtoType.IntType, nullable = false)
    val exprs = List(
      ProtoExpr.Count(col, distinct = false),
      ProtoExpr.Count(col, distinct = true),
      ProtoExpr.Sum(col),
      ProtoExpr.Avg(col),
      ProtoExpr.Min(col),
      ProtoExpr.Max(col)
    )
    for expr <- exprs do
      val artifact = makeArtifact(expr, simpleSchema)
      assertExprRoundtrip(artifact, expr)

  test("roundtrip CaseWhen"):
    val cond = ProtoExpr.Eq(ProtoExpr.lit(1), ProtoExpr.lit(1))
    val expr = ProtoExpr.CaseWhen(
      Vector((cond, ProtoExpr.lit("yes"))),
      Some(ProtoExpr.lit("no"))
    )
    val artifact = makeArtifact(expr, simpleSchema)
    assertExprRoundtrip(artifact, expr)

  test("roundtrip If"):
    val expr = ProtoExpr.If(
      ProtoExpr.lit(true),
      ProtoExpr.lit(1),
      ProtoExpr.lit(0)
    )
    val artifact = makeArtifact(expr, simpleSchema)
    assertExprRoundtrip(artifact, expr)

  test("roundtrip In"):
    val expr = ProtoExpr.In(
      ProtoExpr.lit(1),
      Vector(ProtoExpr.lit(1), ProtoExpr.lit(2), ProtoExpr.lit(3))
    )
    val artifact = makeArtifact(expr, simpleSchema)
    assertExprRoundtrip(artifact, expr)

  test("roundtrip Cast"):
    val expr = ProtoExpr.Cast(ProtoExpr.lit(42), ProtoType.StringType)
    val artifact = makeArtifact(expr, simpleSchema)
    assertExprRoundtrip(artifact, expr)

  test("roundtrip Alias"):
    val expr = ProtoExpr.Alias(ProtoExpr.lit(42), "answer")
    val artifact = makeArtifact(expr, simpleSchema)
    assertExprRoundtrip(artifact, expr)

  test("roundtrip OpaqueCall"):
    val expr = ProtoExpr.OpaqueCall(
      "my_udf",
      Vector(ProtoExpr.lit(1), ProtoExpr.lit(2)),
      Some(ProtoType.IntType),
      deterministic = true
    )
    val artifact = makeArtifact(expr, simpleSchema)
    assertExprRoundtrip(artifact, expr)

  // === Plan Roundtrip Tests ===

  test("roundtrip RelationRef"):
    val contract = SchemaContract(
      "users",
      Vector(FieldContract("id", ProtoType.LongType, expectedNullable = false, position = 0)),
      SchemaFingerprint.fromLong(12345L)
    )
    val plan = ProtoLogicalPlan.RelationRef("users", Some("u"), contract)
    val artifact = makeArtifactWithPlan(plan, simpleSchema)
    assertPlanRoundtrip(artifact, plan)

  test("roundtrip Project"):
    val plan = ProtoLogicalPlan.Project(
      Vector(ProtoExpr.ColumnRef("id", None, ProtoType.LongType, nullable = false)),
      baseRelation
    )
    val artifact = makeArtifactWithPlan(plan, simpleSchema)
    assertPlanRoundtrip(artifact, plan)

  test("roundtrip Filter"):
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.Gt(
        ProtoExpr.ColumnRef("age", None, ProtoType.IntType, nullable = false),
        ProtoExpr.lit(18)
      ),
      baseRelation
    )
    val artifact = makeArtifactWithPlan(plan, simpleSchema)
    assertPlanRoundtrip(artifact, plan)

  test("roundtrip Aggregate"):
    val plan = ProtoLogicalPlan.Aggregate(
      Vector(ProtoExpr.ColumnRef("dept", None, ProtoType.StringType, nullable = false)),
      Vector(
        ProtoExpr.Count(
          ProtoExpr.ColumnRef("id", None, ProtoType.LongType, nullable = false),
          distinct = false
        )
      ),
      baseRelation
    )
    val artifact = makeArtifactWithPlan(plan, simpleSchema)
    assertPlanRoundtrip(artifact, plan)

  test("roundtrip Sort with all directions"):
    val plan = ProtoLogicalPlan.Sort(
      Vector(
        SortOrder(
          ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true),
          SortDirection.Ascending,
          NullOrdering.NullsFirst
        ),
        SortOrder(
          ProtoExpr.ColumnRef("age", None, ProtoType.IntType, nullable = false),
          SortDirection.Descending,
          NullOrdering.NullsLast
        )
      ),
      global = true,
      baseRelation
    )
    val artifact = makeArtifactWithPlan(plan, simpleSchema)
    assertPlanRoundtrip(artifact, plan)

  test("roundtrip Limit"):
    val plan = ProtoLogicalPlan.Limit(100, baseRelation)
    val artifact = makeArtifactWithPlan(plan, simpleSchema)
    assertPlanRoundtrip(artifact, plan)

  test("roundtrip Distinct"):
    val plan = ProtoLogicalPlan.Distinct(baseRelation)
    val artifact = makeArtifactWithPlan(plan, simpleSchema)
    assertPlanRoundtrip(artifact, plan)

  test("roundtrip SubqueryAlias"):
    val plan = ProtoLogicalPlan.SubqueryAlias("t", baseRelation)
    val artifact = makeArtifactWithPlan(plan, simpleSchema)
    assertPlanRoundtrip(artifact, plan)

  test("roundtrip Join with all types"):
    val joinTypes = List(
      JoinType.Inner,
      JoinType.LeftOuter,
      JoinType.RightOuter,
      JoinType.FullOuter,
      JoinType.LeftSemi,
      JoinType.LeftAnti,
      JoinType.Cross
    )
    for jt <- joinTypes do
      val plan = ProtoLogicalPlan.Join(
        baseRelation,
        baseRelation,
        jt,
        Some(
          ProtoExpr.Eq(
            ProtoExpr.ColumnRef("id", Some("left"), ProtoType.LongType, nullable = false),
            ProtoExpr.ColumnRef("id", Some("right"), ProtoType.LongType, nullable = false)
          )
        )
      )
      val artifact = makeArtifactWithPlan(plan, simpleSchema)
      assertPlanRoundtrip(artifact, plan)

  test("roundtrip Union"):
    val plan = ProtoLogicalPlan.Union(
      Vector(baseRelation, baseRelation),
      byName = false,
      allowMissingColumns = false
    )
    val artifact = makeArtifactWithPlan(plan, simpleSchema)
    assertPlanRoundtrip(artifact, plan)

  test("roundtrip Intersect"):
    val plan = ProtoLogicalPlan.Intersect(baseRelation, baseRelation, isAll = false)
    val artifact = makeArtifactWithPlan(plan, simpleSchema)
    assertPlanRoundtrip(artifact, plan)

  test("roundtrip Except"):
    val plan = ProtoLogicalPlan.Except(baseRelation, baseRelation, isAll = true)
    val artifact = makeArtifactWithPlan(plan, simpleSchema)
    assertPlanRoundtrip(artifact, plan)

  test("roundtrip Window"):
    val plan = ProtoLogicalPlan.Window(
      Vector(ProtoExpr.Count(ProtoExpr.lit(1), distinct = false)),
      Vector(ProtoExpr.ColumnRef("dept", None, ProtoType.StringType, nullable = true)),
      Vector(
        SortOrder(
          ProtoExpr.ColumnRef("date", None, ProtoType.DateType, nullable = false),
          SortDirection.Ascending,
          NullOrdering.NullsFirst
        )
      ),
      baseRelation
    )
    val artifact = makeArtifactWithPlan(plan, simpleSchema)
    assertPlanRoundtrip(artifact, plan)

  test("roundtrip Values"):
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("a", ProtoType.IntType, nullable = false),
        ProtoStructField("b", ProtoType.StringType, nullable = true)
      )
    )
    val plan = ProtoLogicalPlan.Values(
      Vector(
        Vector(ProtoExpr.lit(1), ProtoExpr.lit("x")),
        Vector(ProtoExpr.lit(2), ProtoExpr.lit("y"))
      ),
      schema
    )
    val artifact = makeArtifactWithPlan(plan, schema)
    assertPlanRoundtrip(artifact, plan)

  // === Artifact Metadata Tests ===

  test("roundtrip artifact version"):
    val artifact = CompiledArtifact(
      formatVersion = ArtifactVersion(2, 3, 4),
      protocatalystVersion = "0.1.0-test",
      compiledAt = System.currentTimeMillis(),
      contentHash = 12345L,
      schemaContracts = Vector.empty,
      plan = baseRelation,
      outputSchema = simpleSchema,
      sourceInfo = None
    )
    val bytes = JsonArtifactCodec.serialize(artifact)
    val result = JsonArtifactCodec.deserialize(bytes)
    assert(result.isRight)
    val restored = result.toOption.get
    assertEquals(restored.formatVersion.major, 2)
    assertEquals(restored.formatVersion.minor, 3)
    assertEquals(restored.formatVersion.patch, 4)

  test("roundtrip SourceInfo"):
    val artifact = CompiledArtifact(
      formatVersion = ArtifactVersion.current,
      protocatalystVersion = "0.1.0",
      compiledAt = 1700000000000L,
      contentHash = 12345L,
      schemaContracts = Vector.empty,
      plan = baseRelation,
      outputSchema = simpleSchema,
      sourceInfo = Some(SourceInfo("test.scala", 42, Some("SELECT * FROM users")))
    )
    val bytes = JsonArtifactCodec.serialize(artifact)
    val result = JsonArtifactCodec.deserialize(bytes)
    assert(result.isRight)
    val restored = result.toOption.get
    assert(restored.sourceInfo.isDefined)
    assertEquals(restored.sourceInfo.get.sourceFile, "test.scala")
    assertEquals(restored.sourceInfo.get.lineNumber, 42)
    assertEquals(restored.sourceInfo.get.originalSql, Some("SELECT * FROM users"))

  test("roundtrip SchemaContracts"):
    val contracts = Vector(
      SchemaContract(
        "users",
        Vector(
          FieldContract("id", ProtoType.LongType, expectedNullable = false, position = 0),
          FieldContract("name", ProtoType.StringType, expectedNullable = true, position = 1)
        ),
        SchemaFingerprint.fromLong(11111L)
      ),
      SchemaContract(
        "orders",
        Vector(
          FieldContract("order_id", ProtoType.LongType, expectedNullable = false, position = 0),
          FieldContract("user_id", ProtoType.LongType, expectedNullable = false, position = 1)
        ),
        SchemaFingerprint.fromLong(22222L)
      )
    )
    val artifact = CompiledArtifact(
      formatVersion = ArtifactVersion.current,
      protocatalystVersion = "0.1.0",
      compiledAt = 1700000000000L,
      contentHash = 12345L,
      schemaContracts = contracts,
      plan = baseRelation,
      outputSchema = simpleSchema,
      sourceInfo = None
    )
    val bytes = JsonArtifactCodec.serialize(artifact)
    val result = JsonArtifactCodec.deserialize(bytes)
    assert(result.isRight)
    assertEquals(result.toOption.get.schemaContracts, contracts)

  // === Error Handling Tests ===

  test("deserialize invalid JSON returns Left"):
    val result = JsonArtifactCodec.deserialize("not valid json".getBytes("UTF-8"))
    assert(result.isLeft)
    assert(result.left.getOrElse("").contains("JSON deserialization failed"))

  test("deserialize empty bytes returns Left"):
    val result = JsonArtifactCodec.deserialize(Array.emptyByteArray)
    assert(result.isLeft)

  test("format returns json"):
    assertEquals(JsonArtifactCodec.format, "json")

  // === Helper Methods ===

  private val simpleSchema = ProtoSchema(
    Vector(
      ProtoStructField("id", ProtoType.LongType, nullable = false),
      ProtoStructField("value", ProtoType.IntType, nullable = true)
    )
  )

  private val baseRelation = ProtoLogicalPlan.RelationRef(
    "test_table",
    None,
    SchemaContract(
      "test_table",
      Vector(FieldContract("id", ProtoType.LongType, expectedNullable = false, position = 0)),
      SchemaFingerprint.fromLong(99999L)
    )
  )

  private def schemaWithType(pt: ProtoType): ProtoSchema =
    ProtoSchema(Vector(ProtoStructField("field", pt, nullable = true)))

  private def makeArtifact(expr: ProtoExpr, schema: ProtoSchema): CompiledArtifact =
    CompiledArtifact(
      formatVersion = ArtifactVersion.current,
      protocatalystVersion = "0.1.0",
      compiledAt = 1700000000000L,
      contentHash = 12345L,
      schemaContracts = Vector.empty,
      plan = ProtoLogicalPlan.Project(Vector(expr), baseRelation),
      outputSchema = schema,
      sourceInfo = None
    )

  private def makeArtifactWithPlan(plan: ProtoLogicalPlan, schema: ProtoSchema): CompiledArtifact =
    CompiledArtifact(
      formatVersion = ArtifactVersion.current,
      protocatalystVersion = "0.1.0",
      compiledAt = 1700000000000L,
      contentHash = 12345L,
      schemaContracts = Vector.empty,
      plan = plan,
      outputSchema = schema,
      sourceInfo = None
    )

  private def extractFirstExpr(artifact: CompiledArtifact): ProtoExpr =
    artifact.plan match
      case ProtoLogicalPlan.Project(exprs, _) => exprs.head
      case _                                  => fail("Expected Project plan")

  private def assertExprRoundtrip(artifact: CompiledArtifact, expected: ProtoExpr): Unit =
    val bytes = JsonArtifactCodec.serialize(artifact)
    val result = JsonArtifactCodec.deserialize(bytes)
    assert(result.isRight, s"Deserialization failed: ${result.left.getOrElse("")}")
    val resultExpr = extractFirstExpr(result.toOption.get)
    assertEquals(resultExpr, expected)

  private def assertPlanRoundtrip(artifact: CompiledArtifact, expected: ProtoLogicalPlan): Unit =
    val bytes = JsonArtifactCodec.serialize(artifact)
    val result = JsonArtifactCodec.deserialize(bytes)
    assert(result.isRight, s"Deserialization failed: ${result.left.getOrElse("")}")
    assertEquals(result.toOption.get.plan, expected)
