package protocatalyst.query

import protocatalyst.expr.*
import protocatalyst.types.*

class CanonicalizerSuite extends munit.FunSuite:

  test("flatten nested AND"):
    val nested = ProtoExpr.And(
      Vector(
        ProtoExpr.ColumnRef("a", None, ProtoType.BooleanType, false),
        ProtoExpr.And(
          Vector(
            ProtoExpr.ColumnRef("b", None, ProtoType.BooleanType, false),
            ProtoExpr.ColumnRef("c", None, ProtoType.BooleanType, false)
          )
        )
      )
    )

    val flattened = Canonicalizer.flattenAndOr(nested)

    flattened match
      case ProtoExpr.And(children) =>
        assertEquals(children.size, 3)
      case _ =>
        fail("Expected flattened AND")

  test("fold integer constants"):
    val expr = ProtoExpr.Add(
      ProtoExpr.Literal(LiteralValue.IntValue(1)),
      ProtoExpr.Literal(LiteralValue.IntValue(2))
    )

    val folded = Canonicalizer.foldConstants(expr)

    folded match
      case ProtoExpr.Literal(LiteralValue.IntValue(v)) =>
        assertEquals(v, 3)
      case _ =>
        fail("Expected folded literal")

  test("simplify double negation"):
    val expr = ProtoExpr.Not(
      ProtoExpr.Not(
        ProtoExpr.ColumnRef("x", None, ProtoType.BooleanType, false)
      )
    )

    val simplified = Canonicalizer.simplifyBooleans(expr)

    simplified match
      case ProtoExpr.ColumnRef("x", _, _, _) => () // ok
      case _                                 => fail("Expected simplified to column ref")

  test("simplify AND with true literal"):
    val expr = ProtoExpr.And(
      Vector(
        ProtoExpr.Literal(LiteralValue.BooleanValue(true)),
        ProtoExpr.ColumnRef("x", None, ProtoType.BooleanType, false)
      )
    )

    val simplified = Canonicalizer.simplifyBooleans(expr)

    simplified match
      case ProtoExpr.ColumnRef("x", _, _, _) => () // ok
      case _                                 => fail(s"Expected column ref, got $simplified")

  test("simplify AND with false literal"):
    val expr = ProtoExpr.And(
      Vector(
        ProtoExpr.Literal(LiteralValue.BooleanValue(false)),
        ProtoExpr.ColumnRef("x", None, ProtoType.BooleanType, false)
      )
    )

    val simplified = Canonicalizer.simplifyBooleans(expr)

    simplified match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => () // ok
      case _ => fail(s"Expected false literal, got $simplified")

  test("fold string concatenation"):
    val expr = ProtoExpr.Concat(
      Vector(
        ProtoExpr.Literal(LiteralValue.StringValue("Hello, ")),
        ProtoExpr.Literal(LiteralValue.StringValue("World!"))
      )
    )

    val folded = Canonicalizer.foldConstants(expr)

    folded match
      case ProtoExpr.Literal(LiteralValue.StringValue(s)) =>
        assertEquals(s, "Hello, World!")
      case _ =>
        fail("Expected folded string literal")
