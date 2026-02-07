package protocatalyst.codec

import protocatalyst.artifact._
import protocatalyst.expr._
import protocatalyst.plan._
import protocatalyst.schema._
import protocatalyst.types._

class ProtobufArtifactCodecSuite extends munit.FunSuite:

  private val codec = ProtobufArtifactCodec

  // === ProtoType Roundtrip Tests ===

  test("roundtrip all primitive types through protobuf"):
    val primitives = List(
      ProtoType.BooleanType,
      ProtoType.ByteType,
      ProtoType.ShortType,
      ProtoType.IntegerType,
      ProtoType.LongType,
      ProtoType.FloatType,
      ProtoType.DoubleType,
      ProtoType.StringType,
      ProtoType.BinaryType,
      ProtoType.DateType,
      ProtoType.TimestampType,
      ProtoType.TimestampNTZType,
      ProtoType.DayTimeIntervalType,
      ProtoType.YearMonthIntervalType,
      ProtoType.CalendarIntervalType,
      ProtoType.VariantType,
      ProtoType.NullType
    )
    for pt <- primitives do
      val artifact = makeArtifact(ProtoExpr.lit(1), schemaWithType(pt))
      val bytes = codec.serialize(artifact)
      val result = codec.deserialize(bytes)
      assert(result.isRight, s"Failed for $pt: ${result.left.getOrElse("")}")
      assertEquals(result.toOption.get.outputSchema.fields.head.dataType, pt)

  test("roundtrip parameterized types"):
    val types = List(
      ProtoType.DecimalType(18, 6),
      ProtoType.CharType(10),
      ProtoType.VarcharType(255),
      ProtoType.TimeType(6)
    )
    for pt <- types do
      val artifact = makeArtifact(ProtoExpr.lit(1), schemaWithType(pt))
      val bytes = codec.serialize(artifact)
      val result = codec.deserialize(bytes)
      assert(result.isRight, s"Failed for $pt: ${result.left.getOrElse("")}")
      assertEquals(result.toOption.get.outputSchema.fields.head.dataType, pt)

  test("roundtrip complex types"):
    val types = List(
      ProtoType.ArrayType(ProtoType.IntegerType, containsNull = true),
      ProtoType.MapType(ProtoType.StringType, ProtoType.DoubleType, valueContainsNull = false),
      ProtoType.StructType(
        Vector(
          ProtoStructField("x", ProtoType.IntegerType, nullable = false),
          ProtoStructField("y", ProtoType.StringType, nullable = true, Map("comment" -> "test"))
        )
      ),
      ProtoType.UDTType("com.example.MyType", ProtoType.StringType),
      ProtoType.UnresolvedType("unknown"),
      ProtoType.SumType(
        "_type",
        Vector(
          SumVariant("A", 0, Some(ProtoType.IntegerType)),
          SumVariant("B", 1, None)
        )
      )
    )
    for pt <- types do
      val artifact = makeArtifact(ProtoExpr.lit(1), schemaWithType(pt))
      val bytes = codec.serialize(artifact)
      val result = codec.deserialize(bytes)
      assert(result.isRight, s"Failed for $pt: ${result.left.getOrElse("")}")
      assertEquals(result.toOption.get.outputSchema.fields.head.dataType, pt)

  test("roundtrip nested complex types"):
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
    val bytes = codec.serialize(artifact)
    val result = codec.deserialize(bytes)
    assert(result.isRight)
    assertEquals(result.toOption.get.outputSchema.fields.head.dataType, nestedType)

  // === LiteralValue Roundtrip Tests ===

  test("roundtrip all literal values"):
    val literals = List(
      ProtoExpr.Literal(LiteralValue.BooleanValue(true)),
      ProtoExpr.Literal(LiteralValue.ByteValue(42.toByte)),
      ProtoExpr.Literal(LiteralValue.ShortValue(1000.toShort)),
      ProtoExpr.Literal(LiteralValue.IntValue(123456)),
      ProtoExpr.Literal(LiteralValue.LongValue(9876543210L)),
      ProtoExpr.Literal(LiteralValue.FloatValue(3.14f)),
      ProtoExpr.Literal(LiteralValue.DoubleValue(2.718281828)),
      ProtoExpr.Literal(LiteralValue.StringValue("hello 世界")),
      ProtoExpr.Literal(LiteralValue.DecimalValue(BigDecimal("123.456789012345678"))),
      ProtoExpr.Literal(LiteralValue.DateValue(19000)),
      ProtoExpr.Literal(LiteralValue.TimestampValue(1700000000000000L)),
      ProtoExpr.Literal(LiteralValue.TimeValue(43200000000L)),
      ProtoExpr.Literal(LiteralValue.CalendarIntervalValue(12, 30, 1000000L)),
      ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.StringType))
    )
    for expr <- literals do
      val artifact = makeArtifact(expr, simpleSchema)
      assertExprRoundtrip(artifact, expr)

  test("roundtrip BinaryValue"):
    val expr = ProtoExpr.Literal(LiteralValue.BinaryValue(Array[Byte](1, 2, 3, 4, 5)))
    val artifact = makeArtifact(expr, simpleSchema)
    val bytes = codec.serialize(artifact)
    val result = codec.deserialize(bytes)
    assert(result.isRight)
    val resultExpr = extractFirstExpr(result.toOption.get)
    resultExpr match
      case ProtoExpr.Literal(LiteralValue.BinaryValue(arr)) =>
        assert(arr.sameElements(Array[Byte](1, 2, 3, 4, 5)))
      case _ => fail(s"Expected BinaryValue, got $resultExpr")

  // === Expression Roundtrip Tests ===

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
    for expr <- exprs do assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  test("roundtrip logical expressions"):
    val a = ProtoExpr.lit(true)
    val b = ProtoExpr.lit(false)
    val exprs = List(
      ProtoExpr.And(Vector(a, b)),
      ProtoExpr.Or(Vector(a, b)),
      ProtoExpr.Not(a)
    )
    for expr <- exprs do assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  test("roundtrip null-handling expressions"):
    val col = ProtoExpr.ColumnRef("x", None, ProtoType.IntegerType, nullable = true)
    val exprs = List(
      ProtoExpr.IsNull(col),
      ProtoExpr.IsNotNull(col),
      ProtoExpr.Coalesce(Vector(col, ProtoExpr.lit(0))),
      ProtoExpr.NullIf(ProtoExpr.lit(1), ProtoExpr.lit(0))
    )
    for expr <- exprs do assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  test("roundtrip arithmetic expressions"):
    val left = ProtoExpr.lit(10)
    val right = ProtoExpr.lit(5)
    val exprs = List(
      ProtoExpr.Add(left, right),
      ProtoExpr.Subtract(left, right),
      ProtoExpr.Multiply(left, right),
      ProtoExpr.Divide(left, right)
    )
    for expr <- exprs do assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  test("roundtrip math functions"):
    val col = ProtoExpr.ColumnRef("x", None, ProtoType.DoubleType, nullable = false)
    val exprs = List(
      ProtoExpr.Abs(col),
      ProtoExpr.Ceil(col),
      ProtoExpr.Floor(col),
      ProtoExpr.Round(col, ProtoExpr.lit(2)),
      ProtoExpr.Truncate(col, ProtoExpr.lit(2)),
      ProtoExpr.Sqrt(col),
      ProtoExpr.Cbrt(col),
      ProtoExpr.Pow(col, ProtoExpr.lit(2)),
      ProtoExpr.Pmod(col, ProtoExpr.lit(3)),
      ProtoExpr.Sign(col),
      ProtoExpr.Log(col, Some(ProtoExpr.lit(10))),
      ProtoExpr.Log(col, None),
      ProtoExpr.Exp(col)
    )
    for expr <- exprs do assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  test("roundtrip string expressions"):
    val str = ProtoExpr.lit("hello")
    val exprs = List(
      ProtoExpr.Concat(Vector(str, ProtoExpr.lit(" world"))),
      ProtoExpr.Substring(str, ProtoExpr.lit(1), ProtoExpr.lit(3)),
      ProtoExpr.Upper(str),
      ProtoExpr.Lower(str),
      ProtoExpr.Trim(str, Some(ProtoExpr.lit(" ")), TrimType.Both),
      ProtoExpr.Trim(str, None, TrimType.Leading),
      ProtoExpr.Length(str),
      ProtoExpr.Replace(str, ProtoExpr.lit("l"), ProtoExpr.lit("r")),
      ProtoExpr.StringLocate(ProtoExpr.lit("l"), str, Some(ProtoExpr.lit(1))),
      ProtoExpr.StringLocate(ProtoExpr.lit("l"), str, None),
      ProtoExpr.Lpad(str, ProtoExpr.lit(10), ProtoExpr.lit("*")),
      ProtoExpr.Rpad(str, ProtoExpr.lit(10), ProtoExpr.lit("*")),
      ProtoExpr.StringSplit(str, ProtoExpr.lit(","), Some(ProtoExpr.lit(3))),
      ProtoExpr.StringSplit(str, ProtoExpr.lit(","), None),
      ProtoExpr.Reverse(str),
      ProtoExpr.StringRepeat(str, ProtoExpr.lit(3))
    )
    for expr <- exprs do assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  test("roundtrip aggregate expressions"):
    val col = ProtoExpr.ColumnRef("value", None, ProtoType.IntegerType, nullable = false)
    val exprs = List(
      ProtoExpr.Count(col, distinct = false),
      ProtoExpr.Count(col, distinct = true),
      ProtoExpr.Sum(col),
      ProtoExpr.Avg(col),
      ProtoExpr.Min(col),
      ProtoExpr.Max(col)
    )
    for expr <- exprs do assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  test("roundtrip control flow expressions"):
    val cond = ProtoExpr.Eq(ProtoExpr.lit(1), ProtoExpr.lit(1))
    val exprs = List(
      ProtoExpr.CaseWhen(Vector((cond, ProtoExpr.lit("yes"))), Some(ProtoExpr.lit("no"))),
      ProtoExpr.CaseWhen(Vector((cond, ProtoExpr.lit("yes"))), None),
      ProtoExpr.If(ProtoExpr.lit(true), ProtoExpr.lit(1), ProtoExpr.lit(0)),
      ProtoExpr.In(ProtoExpr.lit(1), Vector(ProtoExpr.lit(1), ProtoExpr.lit(2), ProtoExpr.lit(3)))
    )
    for expr <- exprs do assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  test("roundtrip Like expression"):
    val exprs = List(
      ProtoExpr.Like(ProtoExpr.lit("hello"), ProtoExpr.lit("%llo"), None),
      ProtoExpr.Like(ProtoExpr.lit("hello"), ProtoExpr.lit("%llo"), Some(ProtoExpr.lit("\\")))
    )
    for expr <- exprs do assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  test("roundtrip Cast and Alias"):
    val exprs = List(
      ProtoExpr.Cast(ProtoExpr.lit(42), ProtoType.StringType),
      ProtoExpr.Alias(ProtoExpr.lit(42), "answer")
    )
    for expr <- exprs do assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  test("roundtrip ColumnRef and BoundRef"):
    val exprs = List(
      ProtoExpr.ColumnRef("name", Some("t1"), ProtoType.StringType, nullable = true),
      ProtoExpr.ColumnRef("id", None, ProtoType.LongType, nullable = false),
      ProtoExpr.BoundRef(0, ProtoType.IntegerType, nullable = false)
    )
    for expr <- exprs do assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  test("roundtrip window functions"):
    val col = ProtoExpr.ColumnRef("salary", None, ProtoType.DoubleType, nullable = false)
    val exprs = List(
      ProtoExpr.RowNumber(),
      ProtoExpr.Rank(),
      ProtoExpr.DenseRank(),
      ProtoExpr.Ntile(ProtoExpr.lit(4)),
      ProtoExpr.Lead(col, ProtoExpr.lit(1), Some(ProtoExpr.lit(0.0))),
      ProtoExpr.Lead(col, ProtoExpr.lit(1), None),
      ProtoExpr.Lag(col, ProtoExpr.lit(1), None),
      ProtoExpr.FirstValue(col, ignoreNulls = false),
      ProtoExpr.LastValue(col, ignoreNulls = true),
      ProtoExpr.NthValue(col, ProtoExpr.lit(3))
    )
    for expr <- exprs do assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  test("roundtrip WindowExpr with full spec"):
    val col = ProtoExpr.ColumnRef("salary", None, ProtoType.DoubleType, nullable = false)
    val dept = ProtoExpr.ColumnRef("dept", None, ProtoType.StringType, nullable = false)
    val expr = ProtoExpr.WindowExpr(
      ProtoExpr.Sum(col),
      Vector(dept),
      Vector(SortOrder(col, SortDirection.Descending, NullOrdering.NullsLast)),
      Some(WindowFrame(FrameType.Rows, FrameBound.UnboundedPreceding, FrameBound.CurrentRow))
    )
    assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  test("roundtrip WindowExpr without frame"):
    val expr = ProtoExpr.WindowExpr(
      ProtoExpr.RowNumber(),
      Vector.empty,
      Vector(SortOrder(ProtoExpr.lit(1), SortDirection.Ascending, NullOrdering.NullsFirst)),
      None
    )
    assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  test("roundtrip all FrameBound variants"):
    val bounds = List(
      FrameBound.UnboundedPreceding,
      FrameBound.UnboundedFollowing,
      FrameBound.CurrentRow,
      FrameBound.Preceding(5),
      FrameBound.Following(10)
    )
    for lower <- bounds; upper <- bounds do
      val expr = ProtoExpr.WindowExpr(
        ProtoExpr.Sum(ProtoExpr.lit(1)),
        Vector.empty,
        Vector.empty,
        Some(WindowFrame(FrameType.Rows, lower, upper))
      )
      assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  test("roundtrip date/time expressions"):
    val col = ProtoExpr.ColumnRef("ts", None, ProtoType.TimestampType, nullable = false)
    val dateCol = ProtoExpr.ColumnRef("d", None, ProtoType.DateType, nullable = false)
    val exprs = List(
      ProtoExpr.CurrentDate(),
      ProtoExpr.CurrentTimestamp(),
      ProtoExpr.DateAdd(dateCol, ProtoExpr.lit(7)),
      ProtoExpr.DateSub(dateCol, ProtoExpr.lit(7)),
      ProtoExpr.DateDiff(dateCol, dateCol),
      ProtoExpr.Extract(DateTimeField.Year, col),
      ProtoExpr.Extract(DateTimeField.Microsecond, col),
      ProtoExpr.DateTrunc(DateTimeField.Month, col),
      ProtoExpr.ToDate(ProtoExpr.lit("2024-01-01"), Some(ProtoExpr.lit("yyyy-MM-dd"))),
      ProtoExpr.ToDate(ProtoExpr.lit("2024-01-01"), None),
      ProtoExpr.ToTimestamp(ProtoExpr.lit("2024-01-01 12:00:00"), None),
      ProtoExpr.Year(dateCol),
      ProtoExpr.Month(dateCol),
      ProtoExpr.DayOfMonth(dateCol),
      ProtoExpr.Hour(col),
      ProtoExpr.Minute(col),
      ProtoExpr.Second(col)
    )
    for expr <- exprs do assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  test("roundtrip all DateTimeField variants"):
    val fields = List(
      DateTimeField.Year,
      DateTimeField.Month,
      DateTimeField.Day,
      DateTimeField.Hour,
      DateTimeField.Minute,
      DateTimeField.Second,
      DateTimeField.Quarter,
      DateTimeField.Week,
      DateTimeField.DayOfWeek,
      DateTimeField.DayOfYear,
      DateTimeField.Microsecond,
      DateTimeField.Millisecond
    )
    for field <- fields do
      val expr = ProtoExpr.Extract(field, ProtoExpr.lit(1))
      assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  test("roundtrip Grouping"):
    val expr = ProtoExpr.Grouping(
      Vector(
        ProtoExpr.ColumnRef("a", None, ProtoType.IntegerType, nullable = false),
        ProtoExpr.ColumnRef("b", None, ProtoType.IntegerType, nullable = false)
      )
    )
    assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  test("roundtrip generator expressions"):
    val col = ProtoExpr.ColumnRef(
      "arr",
      None,
      ProtoType.ArrayType(ProtoType.IntegerType, true),
      nullable = false
    )
    val exprs = List(
      ProtoExpr.Explode(col),
      ProtoExpr.PosExplode(col),
      ProtoExpr.Inline(col),
      ProtoExpr.Stack(ProtoExpr.lit(2), Vector(ProtoExpr.lit(1), ProtoExpr.lit(2)))
    )
    for expr <- exprs do assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  test("roundtrip OpaqueCall"):
    val expr = ProtoExpr.OpaqueCall(
      "my_udf",
      Vector(ProtoExpr.lit(1), ProtoExpr.lit("x")),
      Some(ProtoType.IntegerType),
      deterministic = true
    )
    assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  test("roundtrip OpaqueCall without return type"):
    val expr = ProtoExpr.OpaqueCall("fn", Vector.empty, None, deterministic = false)
    assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  test("roundtrip subquery expressions"):
    val innerPlan = ProtoLogicalPlan.Project(
      Vector(ProtoExpr.ColumnRef("id", None, ProtoType.LongType, nullable = false)),
      baseRelation
    )
    val exprs = List(
      ProtoExpr.ScalarSubquery(innerPlan),
      ProtoExpr.Exists(innerPlan),
      ProtoExpr.InSubquery(ProtoExpr.lit(1), innerPlan)
    )
    for expr <- exprs do assertExprRoundtrip(makeArtifact(expr, simpleSchema), expr)

  // === Plan Roundtrip Tests ===

  test("roundtrip RelationRef"):
    val contract = SchemaContract(
      "users",
      Vector(FieldContract("id", ProtoType.LongType, expectedNullable = false, position = 0)),
      SchemaFingerprint.fromLong(12345L)
    )
    val plan = ProtoLogicalPlan.RelationRef("users", Some("u"), contract)
    assertPlanRoundtrip(makeArtifactWithPlan(plan, simpleSchema), plan)

  test("roundtrip Project"):
    val plan = ProtoLogicalPlan.Project(
      Vector(ProtoExpr.ColumnRef("id", None, ProtoType.LongType, nullable = false)),
      baseRelation
    )
    assertPlanRoundtrip(makeArtifactWithPlan(plan, simpleSchema), plan)

  test("roundtrip Filter"):
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.Gt(
        ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, nullable = false),
        ProtoExpr.lit(18)
      ),
      baseRelation
    )
    assertPlanRoundtrip(makeArtifactWithPlan(plan, simpleSchema), plan)

  test("roundtrip Aggregate"):
    val plan = ProtoLogicalPlan.Aggregate(
      Vector(ProtoExpr.ColumnRef("dept", None, ProtoType.StringType, nullable = false)),
      Vector(ProtoExpr.Count(ProtoExpr.lit(1), distinct = false)),
      baseRelation
    )
    assertPlanRoundtrip(makeArtifactWithPlan(plan, simpleSchema), plan)

  test("roundtrip Sort"):
    val plan = ProtoLogicalPlan.Sort(
      Vector(
        SortOrder(
          ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true),
          SortDirection.Ascending,
          NullOrdering.NullsFirst
        ),
        SortOrder(
          ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, nullable = false),
          SortDirection.Descending,
          NullOrdering.NullsLast
        )
      ),
      baseRelation
    )
    assertPlanRoundtrip(makeArtifactWithPlan(plan, simpleSchema), plan)

  test("roundtrip Limit, Distinct, SubqueryAlias"):
    val plans = List(
      ProtoLogicalPlan.Limit(100, baseRelation),
      ProtoLogicalPlan.Distinct(baseRelation),
      ProtoLogicalPlan.SubqueryAlias("t", baseRelation)
    )
    for plan <- plans do assertPlanRoundtrip(makeArtifactWithPlan(plan, simpleSchema), plan)

  test("roundtrip Join with all types"):
    for jt <- JoinType.values do
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
      assertPlanRoundtrip(makeArtifactWithPlan(plan, simpleSchema), plan)

  test("roundtrip Union, Intersect, Except"):
    val plans = List(
      ProtoLogicalPlan
        .Union(Vector(baseRelation, baseRelation), byName = false, allowMissingColumns = false),
      ProtoLogicalPlan.Intersect(baseRelation, baseRelation, isAll = false),
      ProtoLogicalPlan.Intersect(baseRelation, baseRelation, isAll = true),
      ProtoLogicalPlan.Except(baseRelation, baseRelation, isAll = false),
      ProtoLogicalPlan.Except(baseRelation, baseRelation, isAll = true)
    )
    for plan <- plans do assertPlanRoundtrip(makeArtifactWithPlan(plan, simpleSchema), plan)

  test("roundtrip Window plan"):
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
    assertPlanRoundtrip(makeArtifactWithPlan(plan, simpleSchema), plan)

  test("roundtrip Values"):
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("a", ProtoType.IntegerType, nullable = false),
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
    assertPlanRoundtrip(makeArtifactWithPlan(plan, schema), plan)

  test("roundtrip With (CTE)"):
    val ctePlan = ProtoLogicalPlan.Project(
      Vector(ProtoExpr.ColumnRef("id", None, ProtoType.LongType, nullable = false)),
      baseRelation
    )
    val plan = ProtoLogicalPlan.With(
      Vector(("cte1", ctePlan)),
      recursive = false,
      ProtoLogicalPlan.RelationRef(
        "cte1",
        None,
        SchemaContract("cte1", Vector.empty, SchemaFingerprint.fromLong(0L))
      )
    )
    assertPlanRoundtrip(makeArtifactWithPlan(plan, simpleSchema), plan)

  test("roundtrip Pivot"):
    val plan = ProtoLogicalPlan.Pivot(
      Vector(ProtoExpr.ColumnRef("dept", None, ProtoType.StringType, nullable = false)),
      ProtoExpr.ColumnRef("quarter", None, ProtoType.StringType, nullable = false),
      Vector(ProtoExpr.lit("Q1"), ProtoExpr.lit("Q2")),
      Vector(
        ProtoExpr.Sum(ProtoExpr.ColumnRef("revenue", None, ProtoType.DoubleType, nullable = false))
      ),
      baseRelation
    )
    assertPlanRoundtrip(makeArtifactWithPlan(plan, simpleSchema), plan)

  test("roundtrip Unpivot"):
    val plan = ProtoLogicalPlan.Unpivot(
      "value",
      "metric",
      Vector(
        (ProtoExpr.ColumnRef("q1", None, ProtoType.DoubleType, nullable = true), Some("Q1")),
        (ProtoExpr.ColumnRef("q2", None, ProtoType.DoubleType, nullable = true), None)
      ),
      includeNulls = true,
      baseRelation
    )
    assertPlanRoundtrip(makeArtifactWithPlan(plan, simpleSchema), plan)

  test("roundtrip LateralJoin"):
    val plan = ProtoLogicalPlan.LateralJoin(
      baseRelation,
      ProtoLogicalPlan.Project(Vector(ProtoExpr.lit(1)), baseRelation),
      Some(ProtoExpr.lit(true))
    )
    assertPlanRoundtrip(makeArtifactWithPlan(plan, simpleSchema), plan)

  test("roundtrip Generate"):
    val plan = ProtoLogicalPlan.Generate(
      ProtoExpr.Explode(
        ProtoExpr.ColumnRef(
          "arr",
          None,
          ProtoType.ArrayType(ProtoType.IntegerType, true),
          nullable = false
        )
      ),
      Vector("col"),
      outer = true,
      baseRelation
    )
    assertPlanRoundtrip(makeArtifactWithPlan(plan, simpleSchema), plan)

  test("roundtrip ResolvedHint"):
    val plan = ProtoLogicalPlan.ResolvedHint(
      Vector(
        PlanHint("BROADCAST", Vector(HintParam.StringVal("t1"))),
        PlanHint("COALESCE", Vector(HintParam.IntVal(4)))
      ),
      baseRelation
    )
    assertPlanRoundtrip(makeArtifactWithPlan(plan, simpleSchema), plan)

  // === Artifact Metadata Tests ===

  test("roundtrip full artifact with all metadata"):
    val contracts = Vector(
      SchemaContract(
        "users",
        Vector(
          FieldContract("id", ProtoType.LongType, expectedNullable = false, position = 0),
          FieldContract("name", ProtoType.StringType, expectedNullable = true, position = 1)
        ),
        SchemaFingerprint.fromLong(11111L)
      )
    )
    val artifact = CompiledArtifact(
      formatVersion = ArtifactVersion(2, 3, 4),
      protocatalystVersion = "0.1.0-test",
      compiledAt = 1700000000000L,
      contentHash = 12345L,
      schemaContracts = contracts,
      plan =
        ProtoLogicalPlan.Filter(ProtoExpr.Gt(ProtoExpr.lit(1), ProtoExpr.lit(0)), baseRelation),
      outputSchema = simpleSchema,
      sourceInfo = Some(SourceInfo("test.scala", 42, Some("SELECT * FROM users")))
    )
    val bytes = codec.serialize(artifact)
    val result = codec.deserialize(bytes)
    assert(result.isRight)
    val restored = result.toOption.get
    assertEquals(restored.formatVersion, ArtifactVersion(2, 3, 4))
    assertEquals(restored.protocatalystVersion, "0.1.0-test")
    assertEquals(restored.compiledAt, 1700000000000L)
    assertEquals(restored.contentHash, 12345L)
    assertEquals(restored.schemaContracts, contracts)
    assertEquals(restored.outputSchema, simpleSchema)
    assert(restored.sourceInfo.isDefined)
    assertEquals(restored.sourceInfo.get.sourceFile, "test.scala")
    assertEquals(restored.sourceInfo.get.lineNumber, 42)
    assertEquals(restored.sourceInfo.get.originalSql, Some("SELECT * FROM users"))

  test("roundtrip artifact without source info"):
    val artifact = makeArtifactWithPlan(baseRelation, simpleSchema)
    val bytes = codec.serialize(artifact)
    val result = codec.deserialize(bytes)
    assert(result.isRight)
    assertEquals(result.toOption.get.sourceInfo, None)

  // === Cross-format tests ===

  test("protobuf format byte 0x02 works through deserializeWithHeader"):
    val artifact = makeArtifact(ProtoExpr.lit(42), simpleSchema)
    val bytes =
      protocatalyst.codec.ArtifactCodec.serializeWithHeader(artifact, ProtobufArtifactCodec)
    val result = protocatalyst.codec.ArtifactCodec.deserializeWithHeader(bytes)
    assert(result.isRight)
    assertEquals(extractFirstExpr(result.toOption.get), ProtoExpr.lit(42))

  test("protobuf payload is smaller than JSON"):
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.And(
        Vector(
          ProtoExpr.Gt(
            ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, nullable = false),
            ProtoExpr.lit(18)
          ),
          ProtoExpr.Lt(
            ProtoExpr.ColumnRef("salary", None, ProtoType.DoubleType, nullable = false),
            ProtoExpr.lit(100000.0)
          )
        )
      ),
      baseRelation
    )
    val artifact = makeArtifactWithPlan(plan, simpleSchema)
    val jsonBytes = JsonArtifactCodec.serialize(artifact)
    val protoBytes = codec.serialize(artifact)
    assert(
      protoBytes.length < jsonBytes.length,
      s"Protobuf (${protoBytes.length}) should be smaller than JSON (${jsonBytes.length})"
    )

  // === Error Handling ===

  test("deserialize invalid protobuf bytes returns Left"):
    val result = codec.deserialize("not valid protobuf".getBytes("UTF-8"))
    assert(result.isLeft)
    assert(result.left.getOrElse("").contains("Protobuf deserialization failed"))

  test("deserialize empty bytes returns Left"):
    // Empty protobuf bytes produce a message with all defaults, which leads to
    // errors when trying to access unset oneof fields (plan, type, etc.)
    val result = codec.deserialize(Array.emptyByteArray)
    assert(result.isLeft)

  test("format returns protobuf"):
    assertEquals(codec.format, "protobuf")

  // === Deeply nested plan test ===

  test("roundtrip deeply nested plan"):
    // filter(filter(filter(sort(limit(distinct(project(relation)))))))
    var plan: ProtoLogicalPlan = baseRelation
    plan = ProtoLogicalPlan.Project(Vector(ProtoExpr.lit(1)), plan)
    plan = ProtoLogicalPlan.Distinct(plan)
    plan = ProtoLogicalPlan.Limit(10, plan)
    plan = ProtoLogicalPlan.Sort(
      Vector(SortOrder(ProtoExpr.lit(1), SortDirection.Ascending, NullOrdering.NullsFirst)),
      plan
    )
    plan = ProtoLogicalPlan.Filter(ProtoExpr.lit(true), plan)
    plan = ProtoLogicalPlan.Filter(ProtoExpr.Gt(ProtoExpr.lit(1), ProtoExpr.lit(0)), plan)
    plan = ProtoLogicalPlan.Filter(ProtoExpr.IsNotNull(ProtoExpr.lit("x")), plan)
    assertPlanRoundtrip(makeArtifactWithPlan(plan, simpleSchema), plan)

  // === Helper Methods ===

  private val simpleSchema = ProtoSchema(
    Vector(
      ProtoStructField("id", ProtoType.LongType, nullable = false),
      ProtoStructField("value", ProtoType.IntegerType, nullable = true)
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
    val bytes = codec.serialize(artifact)
    val result = codec.deserialize(bytes)
    assert(result.isRight, s"Deserialization failed: ${result.left.getOrElse("")}")
    val resultExpr = extractFirstExpr(result.toOption.get)
    assertEquals(resultExpr, expected)

  private def assertPlanRoundtrip(artifact: CompiledArtifact, expected: ProtoLogicalPlan): Unit =
    val bytes = codec.serialize(artifact)
    val result = codec.deserialize(bytes)
    assert(result.isRight, s"Deserialization failed: ${result.left.getOrElse("")}")
    assertEquals(result.toOption.get.plan, expected)
