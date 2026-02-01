package protocatalyst.mock

import protocatalyst.expr._
import protocatalyst.plan._
import protocatalyst.schema._
import protocatalyst.types._

class ConverterSuite extends munit.FunSuite:

  // ============================================
  // Expression Converter Tests
  // ============================================

  test("convert literal int"):
    val proto = ProtoExpr.lit(42)
    val mock = ExpressionConverter.toMock(proto)

    mock match
      case MockExpression.Literal(42, MockDataType.IntegerType) => () // ok
      case other => fail(s"Expected Literal(42, IntegerType), got $other")

    // Roundtrip
    val back = ExpressionConverter.fromMock(mock)
    assertEquals(back, proto)

  test("convert literal string"):
    val proto = ProtoExpr.lit("hello")
    val mock = ExpressionConverter.toMock(proto)

    mock match
      case MockExpression.Literal("hello", MockDataType.StringType) => () // ok
      case other => fail(s"Expected Literal('hello', StringType), got $other")

  test("convert literal null"):
    val proto = ProtoExpr.litNull(ProtoType.IntType)
    val mock = ExpressionConverter.toMock(proto)

    mock match
      case MockExpression.Literal(null, MockDataType.IntegerType) => () // ok
      case other => fail(s"Expected Literal(null, IntegerType), got $other")

  test("convert column reference"):
    val proto = ProtoExpr.ColumnRef("name", Some("users"), ProtoType.StringType, false)
    val mock = ExpressionConverter.toMock(proto)

    mock match
      case MockExpression.UnresolvedAttribute(Seq("users", "name")) => () // ok
      case other => fail(s"Expected UnresolvedAttribute, got $other")

  test("convert comparison operators"):
    val left = ProtoExpr.lit(10)
    val right = ProtoExpr.lit(5)

    assertEquals(
      ExpressionConverter.toMock(ProtoExpr.Eq(left, right)).isInstanceOf[MockExpression.EqualTo],
      true
    )
    assertEquals(
      ExpressionConverter.toMock(ProtoExpr.Lt(left, right)).isInstanceOf[MockExpression.LessThan],
      true
    )
    assertEquals(
      ExpressionConverter
        .toMock(ProtoExpr.GtEq(left, right))
        .isInstanceOf[MockExpression.GreaterThanOrEqual],
      true
    )

  test("convert And expression"):
    val proto = ProtoExpr.And(
      Vector(
        ProtoExpr.lit(true),
        ProtoExpr.lit(false)
      )
    )
    val mock = ExpressionConverter.toMock(proto)

    mock match
      case MockExpression.And(_, _) => () // ok
      case other                    => fail(s"Expected And, got $other")

  test("convert Or expression"):
    val proto = ProtoExpr.Or(
      Vector(
        ProtoExpr.lit(true),
        ProtoExpr.lit(false)
      )
    )
    val mock = ExpressionConverter.toMock(proto)

    mock match
      case MockExpression.Or(_, _) => () // ok
      case other                   => fail(s"Expected Or, got $other")

  test("convert Not expression"):
    val proto = ProtoExpr.Not(ProtoExpr.lit(true))
    val mock = ExpressionConverter.toMock(proto)

    mock match
      case MockExpression.Not(_) => () // ok
      case other                 => fail(s"Expected Not, got $other")

  test("convert IsNull and IsNotNull"):
    val col = ProtoExpr.ColumnRef("x", None, ProtoType.IntType, true)

    val isNull = ExpressionConverter.toMock(ProtoExpr.IsNull(col))
    val isNotNull = ExpressionConverter.toMock(ProtoExpr.IsNotNull(col))

    assert(isNull.isInstanceOf[MockExpression.IsNull])
    assert(isNotNull.isInstanceOf[MockExpression.IsNotNull])

  test("convert arithmetic expressions"):
    val a = ProtoExpr.lit(10)
    val b = ProtoExpr.lit(3)

    assert(ExpressionConverter.toMock(ProtoExpr.Add(a, b)).isInstanceOf[MockExpression.Add])
    assert(
      ExpressionConverter.toMock(ProtoExpr.Subtract(a, b)).isInstanceOf[MockExpression.Subtract]
    )
    assert(
      ExpressionConverter.toMock(ProtoExpr.Multiply(a, b)).isInstanceOf[MockExpression.Multiply]
    )
    assert(ExpressionConverter.toMock(ProtoExpr.Divide(a, b)).isInstanceOf[MockExpression.Divide])

  test("convert aggregate expressions"):
    val col = ProtoExpr.ColumnRef("amount", None, ProtoType.DoubleType, false)

    assert(
      ExpressionConverter.toMock(ProtoExpr.Count(col, false)).isInstanceOf[MockExpression.Count]
    )
    assert(ExpressionConverter.toMock(ProtoExpr.Sum(col)).isInstanceOf[MockExpression.Sum])
    assert(ExpressionConverter.toMock(ProtoExpr.Avg(col)).isInstanceOf[MockExpression.Avg])
    assert(ExpressionConverter.toMock(ProtoExpr.Min(col)).isInstanceOf[MockExpression.Min])
    assert(ExpressionConverter.toMock(ProtoExpr.Max(col)).isInstanceOf[MockExpression.Max])

  test("convert CaseWhen"):
    val proto = ProtoExpr.CaseWhen(
      Vector(
        (ProtoExpr.lit(true), ProtoExpr.lit("yes")),
        (ProtoExpr.lit(false), ProtoExpr.lit("no"))
      ),
      Some(ProtoExpr.lit("maybe"))
    )
    val mock = ExpressionConverter.toMock(proto)

    mock match
      case cw: MockExpression.CaseWhen =>
        assertEquals(cw.branches.size, 2)
        assert(cw.elseValue.isDefined)
      case other => fail(s"Expected CaseWhen, got $other")

  test("convert Cast"):
    val proto = ProtoExpr.Cast(ProtoExpr.lit("123"), ProtoType.IntType)
    val mock = ExpressionConverter.toMock(proto)

    mock match
      case MockExpression.Cast(_, MockDataType.IntegerType) => () // ok
      case other => fail(s"Expected Cast to IntegerType, got $other")

  test("convert Alias"):
    val proto = ProtoExpr.Alias(ProtoExpr.lit(42), "answer")
    val mock = ExpressionConverter.toMock(proto)

    mock match
      case MockExpression.Alias(_, "answer") => () // ok
      case other => fail(s"Expected Alias with name 'answer', got $other")

  test("convert window functions"):
    assert(ExpressionConverter.toMock(ProtoExpr.RowNumber()).isInstanceOf[MockExpression.RowNumber])
    assert(ExpressionConverter.toMock(ProtoExpr.Rank()).isInstanceOf[MockExpression.Rank])
    assert(ExpressionConverter.toMock(ProtoExpr.DenseRank()).isInstanceOf[MockExpression.DenseRank])

  test("expression roundtrip - complex expression"):
    val proto = ProtoExpr.And(
      Vector(
        ProtoExpr.Eq(
          ProtoExpr.ColumnRef("age", None, ProtoType.IntType, false),
          ProtoExpr.lit(30)
        ),
        ProtoExpr.Or(
          Vector(
            ProtoExpr.IsNotNull(ProtoExpr.ColumnRef("name", None, ProtoType.StringType, true)),
            ProtoExpr.GtEq(
              ProtoExpr.ColumnRef("salary", None, ProtoType.DoubleType, false),
              ProtoExpr.lit(50000.0)
            )
          )
        )
      )
    )

    val mock = ExpressionConverter.toMock(proto)
    val back = ExpressionConverter.fromMock(mock)

    // Structure should be preserved (though exact equality may differ due to flattening)
    back match
      case ProtoExpr.And(children) =>
        assertEquals(children.size, 2)
      case other =>
        fail(s"Expected And expression, got $other")

  // ============================================
  // Plan Converter Tests
  // ============================================

  test("convert simple Project"):
    val schema = SchemaContract(
      "users",
      Vector(
        FieldContract("name", ProtoType.StringType, false, 0),
        FieldContract("age", ProtoType.IntType, false, 1)
      ),
      SchemaFingerprint.fromLong(0L)
    )
    val proto = ProtoLogicalPlan.Project(
      Vector(ProtoExpr.ColumnRef("name", None, ProtoType.StringType, false)),
      ProtoLogicalPlan.RelationRef("users", None, schema)
    )

    val mock = PlanConverter.toMock(proto)

    mock match
      case MockLogicalPlan.Project(projectList, child) =>
        assertEquals(projectList.size, 1)
        assert(child.isInstanceOf[MockLogicalPlan.LogicalRelation])
      case other =>
        fail(s"Expected Project, got $other")

  test("convert Filter"):
    val schema = SchemaContract("users", Vector.empty, SchemaFingerprint.fromLong(0L))
    val proto = ProtoLogicalPlan.Filter(
      ProtoExpr.Gt(ProtoExpr.ColumnRef("age", None, ProtoType.IntType, false), ProtoExpr.lit(18)),
      ProtoLogicalPlan.RelationRef("users", None, schema)
    )

    val mock = PlanConverter.toMock(proto)

    mock match
      case MockLogicalPlan.Filter(condition, _) =>
        assert(condition.isInstanceOf[MockExpression.GreaterThan])
      case other =>
        fail(s"Expected Filter, got $other")

  test("convert Aggregate"):
    val schema = SchemaContract("orders", Vector.empty, SchemaFingerprint.fromLong(0L))
    val proto = ProtoLogicalPlan.Aggregate(
      Vector(ProtoExpr.ColumnRef("category", None, ProtoType.StringType, false)),
      Vector(
        ProtoExpr.ColumnRef("category", None, ProtoType.StringType, false),
        ProtoExpr.Sum(ProtoExpr.ColumnRef("amount", None, ProtoType.DoubleType, false))
      ),
      ProtoLogicalPlan.RelationRef("orders", None, schema)
    )

    val mock = PlanConverter.toMock(proto)

    mock match
      case MockLogicalPlan.Aggregate(grouping, aggs, _) =>
        assertEquals(grouping.size, 1)
        assertEquals(aggs.size, 2)
      case other =>
        fail(s"Expected Aggregate, got $other")

  test("convert Join"):
    val usersSchema = SchemaContract("users", Vector.empty, SchemaFingerprint.fromLong(0L))
    val ordersSchema = SchemaContract("orders", Vector.empty, SchemaFingerprint.fromLong(0L))

    val proto = ProtoLogicalPlan.Join(
      ProtoLogicalPlan.RelationRef("users", None, usersSchema),
      ProtoLogicalPlan.RelationRef("orders", None, ordersSchema),
      JoinType.Inner,
      Some(
        ProtoExpr.Eq(
          ProtoExpr.ColumnRef("id", Some("users"), ProtoType.IntType, false),
          ProtoExpr.ColumnRef("user_id", Some("orders"), ProtoType.IntType, false)
        )
      )
    )

    val mock = PlanConverter.toMock(proto)

    mock match
      case MockLogicalPlan.Join(_, _, joinType, condition) =>
        assertEquals(joinType, MockLogicalPlan.JoinType.Inner)
        assert(condition.isDefined)
      case other =>
        fail(s"Expected Join, got $other")

  test("convert Sort"):
    val schema = SchemaContract("users", Vector.empty, SchemaFingerprint.fromLong(0L))
    val proto = ProtoLogicalPlan.Sort(
      Vector(
        SortOrder(
          ProtoExpr.ColumnRef("age", None, ProtoType.IntType, false),
          SortDirection.Descending,
          NullOrdering.NullsLast
        )
      ),
      true,
      ProtoLogicalPlan.RelationRef("users", None, schema)
    )

    val mock = PlanConverter.toMock(proto)

    mock match
      case MockLogicalPlan.Sort(order, global, _) =>
        assertEquals(order.size, 1)
        assertEquals(global, true)
        assertEquals(order.head.direction, MockExpression.SortDirection.Descending)
      case other =>
        fail(s"Expected Sort, got $other")

  test("convert Limit"):
    val schema = SchemaContract("users", Vector.empty, SchemaFingerprint.fromLong(0L))
    val proto = ProtoLogicalPlan.Limit(100, ProtoLogicalPlan.RelationRef("users", None, schema))

    val mock = PlanConverter.toMock(proto)

    mock match
      case MockLogicalPlan.GlobalLimit(MockExpression.Literal(100, _), _) => () // ok
      case other                                                          =>
        fail(s"Expected GlobalLimit, got $other")

  test("convert Union"):
    val schema1 = SchemaContract("table1", Vector.empty, SchemaFingerprint.fromLong(0L))
    val schema2 = SchemaContract("table2", Vector.empty, SchemaFingerprint.fromLong(0L))

    val proto = ProtoLogicalPlan.Union(
      Vector(
        ProtoLogicalPlan.RelationRef("table1", None, schema1),
        ProtoLogicalPlan.RelationRef("table2", None, schema2)
      ),
      false,
      false
    )

    val mock = PlanConverter.toMock(proto)

    mock match
      case MockLogicalPlan.Union(children, _, _) =>
        assertEquals(children.size, 2)
      case other =>
        fail(s"Expected Union, got $other")

  test("convert SubqueryAlias"):
    val schema = SchemaContract("users", Vector.empty, SchemaFingerprint.fromLong(0L))
    val proto = ProtoLogicalPlan.SubqueryAlias(
      "u",
      ProtoLogicalPlan.RelationRef("users", None, schema)
    )

    val mock = PlanConverter.toMock(proto)

    mock match
      case MockLogicalPlan.SubqueryAlias("u", _) => () // ok
      case other                                 =>
        fail(s"Expected SubqueryAlias with alias 'u', got $other")

  test("plan roundtrip - simple query"):
    val schema = SchemaContract(
      "users",
      Vector(
        FieldContract("name", ProtoType.StringType, false, 0),
        FieldContract("age", ProtoType.IntType, false, 1)
      ),
      SchemaFingerprint.fromLong(0L)
    )
    val proto = ProtoLogicalPlan.Project(
      Vector(
        ProtoExpr.ColumnRef("name", None, ProtoType.StringType, false)
      ),
      ProtoLogicalPlan.Filter(
        ProtoExpr.Gt(ProtoExpr.ColumnRef("age", None, ProtoType.IntType, false), ProtoExpr.lit(18)),
        ProtoLogicalPlan.RelationRef("users", None, schema)
      )
    )

    val mock = PlanConverter.toMock(proto)
    val back = PlanConverter.fromMock(mock)

    // Structure should be preserved
    back match
      case ProtoLogicalPlan.Project(_, ProtoLogicalPlan.Filter(_, _)) => () // ok
      case other                                                      =>
        fail(s"Expected Project(Filter(...)), got $other")

  // ============================================
  // Type Converter Tests
  // ============================================

  test("convert primitive types"):
    assertEquals(TypeConverter.toMock(ProtoType.BooleanType), MockDataType.BooleanType)
    assertEquals(TypeConverter.toMock(ProtoType.IntType), MockDataType.IntegerType)
    assertEquals(TypeConverter.toMock(ProtoType.LongType), MockDataType.LongType)
    assertEquals(TypeConverter.toMock(ProtoType.DoubleType), MockDataType.DoubleType)
    assertEquals(TypeConverter.toMock(ProtoType.StringType), MockDataType.StringType)

  test("convert complex types"):
    val arrayType = ProtoType.ArrayType(ProtoType.IntType, false)
    val mockArray = TypeConverter.toMock(arrayType)

    mockArray match
      case MockDataType.ArrayType(MockDataType.IntegerType, false) => () // ok
      case other => fail(s"Expected ArrayType[Int], got $other")

  test("type roundtrip"):
    val types = Seq(
      ProtoType.BooleanType,
      ProtoType.IntType,
      ProtoType.StringType,
      ProtoType.ArrayType(ProtoType.DoubleType, true),
      ProtoType.MapType(ProtoType.StringType, ProtoType.IntType, false)
    )

    types.foreach { pt =>
      val mock = TypeConverter.toMock(pt)
      val back = TypeConverter.fromMock(mock)
      assertEquals(back, pt, s"Type roundtrip failed for $pt")
    }
