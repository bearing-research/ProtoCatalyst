package protocatalyst.substrait

import protocatalyst.expr.ProtoExpr
import protocatalyst.schema.ProtoSchema
import protocatalyst.types.{LiteralValue, ProtoType}

class SubstraitExprConverterSuite extends munit.FunSuite:
  import SubstraitExprConverter.toSubstrait

  // ========== Literals ==========

  test("BooleanValue literal"):
    val expr = ProtoExpr.Literal(LiteralValue.BooleanValue(true))
    val substrait = toSubstrait(expr)
    assert(substrait != null)

  test("IntValue literal"):
    val expr = ProtoExpr.Literal(LiteralValue.IntValue(42))
    val substrait = toSubstrait(expr)
    assert(substrait != null)

  test("LongValue literal"):
    val expr = ProtoExpr.Literal(LiteralValue.LongValue(123456789L))
    val substrait = toSubstrait(expr)
    assert(substrait != null)

  test("FloatValue literal"):
    val expr = ProtoExpr.Literal(LiteralValue.FloatValue(3.14f))
    val substrait = toSubstrait(expr)
    assert(substrait != null)

  test("DoubleValue literal"):
    val expr = ProtoExpr.Literal(LiteralValue.DoubleValue(2.718))
    val substrait = toSubstrait(expr)
    assert(substrait != null)

  test("StringValue literal"):
    val expr = ProtoExpr.Literal(LiteralValue.StringValue("hello"))
    val substrait = toSubstrait(expr)
    assert(substrait != null)

  test("BinaryValue literal"):
    val expr = ProtoExpr.Literal(LiteralValue.BinaryValue(scala.collection.immutable.ArraySeq(1, 2, 3)))
    val substrait = toSubstrait(expr)
    assert(substrait != null)

  test("DateValue literal"):
    val expr = ProtoExpr.Literal(LiteralValue.DateValue(18000)) // 2019-04-11
    val substrait = toSubstrait(expr)
    assert(substrait != null)

  test("TimestampValue literal"):
    val expr = ProtoExpr.Literal(LiteralValue.TimestampValue(1609459200000000L))
    val substrait = toSubstrait(expr)
    assert(substrait != null)

  test("DecimalValue literal"):
    val expr = ProtoExpr.Literal(LiteralValue.DecimalValue(BigDecimal("123.45")))
    val substrait = toSubstrait(expr)
    assert(substrait != null)

  // ========== Unsupported Literals ==========

  test("NullValue throws exception"):
    intercept[UnsupportedSubstraitFeatureException] {
      val expr = ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.IntegerType))
      toSubstrait(expr)
    }

  test("TimeValue throws exception"):
    intercept[UnsupportedSubstraitFeatureException] {
      val expr = ProtoExpr.Literal(LiteralValue.TimeValue(43200000000L)) // noon
      toSubstrait(expr)
    }

  test("CalendarIntervalValue throws exception"):
    intercept[UnsupportedSubstraitFeatureException] {
      val expr = ProtoExpr.Literal(LiteralValue.CalendarIntervalValue(1, 2, 3))
      toSubstrait(expr)
    }

  // ========== Column References ==========

  test("ColumnRef throws exception (requires schema context)"):
    intercept[UnsupportedSubstraitFeatureException] {
      val expr = ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true)
      toSubstrait(expr)
    }

  test("BoundRef throws exception (not yet implemented)"):
    intercept[UnsupportedSubstraitFeatureException] {
      val expr = ProtoExpr.BoundRef(0, ProtoType.IntegerType, nullable = false)
      toSubstrait(expr)
    }

  // ========== Comparison Operators ==========

  test("Eq throws exception (scalar functions not implemented)"):
    intercept[UnsupportedSubstraitFeatureException] {
      val left = ProtoExpr.lit(1)
      val right = ProtoExpr.lit(2)
      val expr = ProtoExpr.Eq(left, right)
      toSubstrait(expr)
    }

  // ========== Cast ==========

  test("Cast"):
    val child = ProtoExpr.lit(42)
    val expr = ProtoExpr.Cast(child, ProtoType.LongType)
    val substrait = toSubstrait(expr)
    assert(substrait != null)

  // ========== Alias ==========

  test("Alias returns child expression"):
    val child = ProtoExpr.lit(42)
    val expr = ProtoExpr.Alias(child, "foo")
    val substrait = toSubstrait(expr)
    assert(substrait != null)

  // ========== Aggregates ==========

  test("Count throws exception"):
    intercept[UnsupportedSubstraitFeatureException] {
      val expr = ProtoExpr.Count(ProtoExpr.lit(1), distinct = false)
      toSubstrait(expr)
    }

  test("Sum throws exception"):
    intercept[UnsupportedSubstraitFeatureException] {
      val expr = ProtoExpr.Sum(ProtoExpr.lit(1))
      toSubstrait(expr)
    }

  // ========== Window Functions ==========

  test("RowNumber throws exception"):
    intercept[UnsupportedSubstraitFeatureException] {
      val expr = ProtoExpr.RowNumber()
      toSubstrait(expr)
    }

  // ========== Subqueries ==========

  test("ScalarSubquery throws exception"):
    intercept[UnsupportedSubstraitFeatureException] {
      val plan = protocatalyst.plan.ProtoLogicalPlan.Values(
        rows = Vector.empty,
        schema = ProtoSchema(fields = Vector.empty)
      )
      val expr = ProtoExpr.ScalarSubquery(plan)
      toSubstrait(expr)
    }

  // ========== Generator Functions ==========

  test("Explode throws exception"):
    intercept[UnsupportedSubstraitFeatureException] {
      val expr = ProtoExpr.Explode(ProtoExpr.lit("foo"))
      toSubstrait(expr)
    }

  test("Grouping throws exception"):
    intercept[UnsupportedSubstraitFeatureException] {
      val expr = ProtoExpr.Grouping(Vector(ProtoExpr.lit(1)))
      toSubstrait(expr)
    }

end SubstraitExprConverterSuite
