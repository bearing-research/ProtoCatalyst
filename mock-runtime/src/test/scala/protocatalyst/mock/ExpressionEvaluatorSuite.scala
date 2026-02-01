package protocatalyst.mock

import protocatalyst.expr._
import protocatalyst.types._

class ExpressionEvaluatorSuite extends munit.FunSuite:

  // === Literal Tests ===

  test("evaluates boolean literal"):
    assertEquals(ExpressionEvaluator.eval(ProtoExpr.lit(true)), ExpressionEvaluator.Success(true))
    assertEquals(ExpressionEvaluator.eval(ProtoExpr.lit(false)), ExpressionEvaluator.Success(false))

  test("evaluates integer literal"):
    assertEquals(ExpressionEvaluator.eval(ProtoExpr.lit(42)), ExpressionEvaluator.Success(42))

  test("evaluates long literal"):
    assertEquals(ExpressionEvaluator.eval(ProtoExpr.lit(42L)), ExpressionEvaluator.Success(42L))

  test("evaluates double literal"):
    assertEquals(ExpressionEvaluator.eval(ProtoExpr.lit(3.14)), ExpressionEvaluator.Success(3.14))

  test("evaluates string literal"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.lit("hello")),
      ExpressionEvaluator.Success("hello")
    )

  test("evaluates null literal"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.litNull(ProtoType.IntType)),
      ExpressionEvaluator.Success(null)
    )

  // === Arithmetic Tests ===

  test("evaluates addition"):
    val expr = ProtoExpr.Add(ProtoExpr.lit(1), ProtoExpr.lit(2))
    assertEquals(ExpressionEvaluator.eval(expr), ExpressionEvaluator.Success(3))

  test("evaluates subtraction"):
    val expr = ProtoExpr.Subtract(ProtoExpr.lit(5), ProtoExpr.lit(3))
    assertEquals(ExpressionEvaluator.eval(expr), ExpressionEvaluator.Success(2))

  test("evaluates multiplication"):
    val expr = ProtoExpr.Multiply(ProtoExpr.lit(4), ProtoExpr.lit(3))
    assertEquals(ExpressionEvaluator.eval(expr), ExpressionEvaluator.Success(12))

  test("evaluates division"):
    val expr = ProtoExpr.Divide(ProtoExpr.lit(10), ProtoExpr.lit(2))
    assertEquals(ExpressionEvaluator.eval(expr), ExpressionEvaluator.Success(5))

  test("division by zero returns null"):
    val expr = ProtoExpr.Divide(ProtoExpr.lit(10), ProtoExpr.lit(0))
    assertEquals(ExpressionEvaluator.eval(expr), ExpressionEvaluator.Success(null))

  test("arithmetic with mixed types uses double"):
    val expr = ProtoExpr.Add(ProtoExpr.lit(1), ProtoExpr.lit(2.5))
    ExpressionEvaluator.eval(expr) match
      case ExpressionEvaluator.Success(v: Double) => assertEquals(v, 3.5)
      case other                                  => fail(s"Expected Success(3.5), got $other")

  // === Comparison Tests ===

  test("evaluates greater than"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Gt(ProtoExpr.lit(5), ProtoExpr.lit(3))),
      ExpressionEvaluator.Success(true)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Gt(ProtoExpr.lit(3), ProtoExpr.lit(5))),
      ExpressionEvaluator.Success(false)
    )

  test("evaluates less than"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Lt(ProtoExpr.lit(3), ProtoExpr.lit(5))),
      ExpressionEvaluator.Success(true)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Lt(ProtoExpr.lit(5), ProtoExpr.lit(3))),
      ExpressionEvaluator.Success(false)
    )

  test("evaluates equality"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Eq(ProtoExpr.lit(5), ProtoExpr.lit(5))),
      ExpressionEvaluator.Success(true)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Eq(ProtoExpr.lit(5), ProtoExpr.lit(3))),
      ExpressionEvaluator.Success(false)
    )

  test("evaluates not equal"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.NotEq(ProtoExpr.lit(5), ProtoExpr.lit(3))),
      ExpressionEvaluator.Success(true)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.NotEq(ProtoExpr.lit(5), ProtoExpr.lit(5))),
      ExpressionEvaluator.Success(false)
    )

  // === Boolean Logic Tests ===

  test("evaluates AND"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.And(Vector(ProtoExpr.lit(true), ProtoExpr.lit(true)))),
      ExpressionEvaluator.Success(true)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.And(Vector(ProtoExpr.lit(true), ProtoExpr.lit(false)))),
      ExpressionEvaluator.Success(false)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.And(Vector(ProtoExpr.lit(false), ProtoExpr.lit(false)))),
      ExpressionEvaluator.Success(false)
    )

  test("evaluates OR"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Or(Vector(ProtoExpr.lit(true), ProtoExpr.lit(false)))),
      ExpressionEvaluator.Success(true)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Or(Vector(ProtoExpr.lit(false), ProtoExpr.lit(false)))),
      ExpressionEvaluator.Success(false)
    )

  test("evaluates NOT"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Not(ProtoExpr.lit(true))),
      ExpressionEvaluator.Success(false)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Not(ProtoExpr.lit(false))),
      ExpressionEvaluator.Success(true)
    )

  test("AND with null - three-valued logic"):
    // false AND null = false
    assertEquals(
      ExpressionEvaluator.eval(
        ProtoExpr.And(Vector(ProtoExpr.lit(false), ProtoExpr.litNull(ProtoType.BooleanType)))
      ),
      ExpressionEvaluator.Success(false)
    )
    // true AND null = null
    assertEquals(
      ExpressionEvaluator.eval(
        ProtoExpr.And(Vector(ProtoExpr.lit(true), ProtoExpr.litNull(ProtoType.BooleanType)))
      ),
      ExpressionEvaluator.Success(null)
    )

  test("OR with null - three-valued logic"):
    // true OR null = true
    assertEquals(
      ExpressionEvaluator.eval(
        ProtoExpr.Or(Vector(ProtoExpr.lit(true), ProtoExpr.litNull(ProtoType.BooleanType)))
      ),
      ExpressionEvaluator.Success(true)
    )
    // false OR null = null
    assertEquals(
      ExpressionEvaluator.eval(
        ProtoExpr.Or(Vector(ProtoExpr.lit(false), ProtoExpr.litNull(ProtoType.BooleanType)))
      ),
      ExpressionEvaluator.Success(null)
    )

  // === Null Handling Tests ===

  test("evaluates IsNull"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.IsNull(ProtoExpr.litNull(ProtoType.IntType))),
      ExpressionEvaluator.Success(true)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.IsNull(ProtoExpr.lit(42))),
      ExpressionEvaluator.Success(false)
    )

  test("evaluates IsNotNull"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.IsNotNull(ProtoExpr.lit(42))),
      ExpressionEvaluator.Success(true)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.IsNotNull(ProtoExpr.litNull(ProtoType.IntType))),
      ExpressionEvaluator.Success(false)
    )

  test("evaluates Coalesce"):
    val expr = ProtoExpr.Coalesce(
      Vector(
        ProtoExpr.litNull(ProtoType.IntType),
        ProtoExpr.lit(42),
        ProtoExpr.lit(100)
      )
    )
    assertEquals(ExpressionEvaluator.eval(expr), ExpressionEvaluator.Success(42))

  test("Coalesce returns null if all null"):
    val expr = ProtoExpr.Coalesce(
      Vector(
        ProtoExpr.litNull(ProtoType.IntType),
        ProtoExpr.litNull(ProtoType.IntType)
      )
    )
    assertEquals(ExpressionEvaluator.eval(expr), ExpressionEvaluator.Success(null))

  // === BoundRef Tests ===

  test("evaluates BoundRef against row"):
    val row = MockRow(10, "test", 3.14)
    val ref = ProtoExpr.BoundRef(0, ProtoType.IntType, false)
    assertEquals(ExpressionEvaluator.eval(ref, row), ExpressionEvaluator.Success(10))

  test("evaluates BoundRef for different ordinals"):
    val row = MockRow("Alice", 30, 75000.0)
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.BoundRef(0, ProtoType.StringType, false), row),
      ExpressionEvaluator.Success("Alice")
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.BoundRef(1, ProtoType.IntType, false), row),
      ExpressionEvaluator.Success(30)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.BoundRef(2, ProtoType.DoubleType, false), row),
      ExpressionEvaluator.Success(75000.0)
    )

  // === String Operation Tests ===

  test("evaluates Upper"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Upper(ProtoExpr.lit("hello"))),
      ExpressionEvaluator.Success("HELLO")
    )

  test("evaluates Lower"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Lower(ProtoExpr.lit("HELLO"))),
      ExpressionEvaluator.Success("hello")
    )

  test("evaluates Concat"):
    val expr = ProtoExpr.Concat(Vector(ProtoExpr.lit("Hello, "), ProtoExpr.lit("World!")))
    assertEquals(ExpressionEvaluator.eval(expr), ExpressionEvaluator.Success("Hello, World!"))

  test("Concat with null returns null"):
    val expr =
      ProtoExpr.Concat(Vector(ProtoExpr.lit("Hello"), ProtoExpr.litNull(ProtoType.StringType)))
    assertEquals(ExpressionEvaluator.eval(expr), ExpressionEvaluator.Success(null))

  test("evaluates Substring"):
    val expr = ProtoExpr.Substring(ProtoExpr.lit("Hello World"), ProtoExpr.lit(1), ProtoExpr.lit(5))
    assertEquals(ExpressionEvaluator.eval(expr), ExpressionEvaluator.Success("Hello"))

  // === Control Flow Tests ===

  test("evaluates If - true branch"):
    val expr = ProtoExpr.If(ProtoExpr.lit(true), ProtoExpr.lit("yes"), ProtoExpr.lit("no"))
    assertEquals(ExpressionEvaluator.eval(expr), ExpressionEvaluator.Success("yes"))

  test("evaluates If - false branch"):
    val expr = ProtoExpr.If(ProtoExpr.lit(false), ProtoExpr.lit("yes"), ProtoExpr.lit("no"))
    assertEquals(ExpressionEvaluator.eval(expr), ExpressionEvaluator.Success("no"))

  test("evaluates If - null predicate"):
    val expr = ProtoExpr.If(
      ProtoExpr.litNull(ProtoType.BooleanType),
      ProtoExpr.lit("yes"),
      ProtoExpr.lit("no")
    )
    assertEquals(ExpressionEvaluator.eval(expr), ExpressionEvaluator.Success(null))

  test("evaluates CaseWhen"):
    val expr = ProtoExpr.CaseWhen(
      Vector(
        (ProtoExpr.lit(false), ProtoExpr.lit("first")),
        (ProtoExpr.lit(true), ProtoExpr.lit("second"))
      ),
      Some(ProtoExpr.lit("else"))
    )
    assertEquals(ExpressionEvaluator.eval(expr), ExpressionEvaluator.Success("second"))

  test("evaluates CaseWhen - else branch"):
    val expr = ProtoExpr.CaseWhen(
      Vector(
        (ProtoExpr.lit(false), ProtoExpr.lit("first")),
        (ProtoExpr.lit(false), ProtoExpr.lit("second"))
      ),
      Some(ProtoExpr.lit("else"))
    )
    assertEquals(ExpressionEvaluator.eval(expr), ExpressionEvaluator.Success("else"))

  test("evaluates In"):
    val expr =
      ProtoExpr.In(ProtoExpr.lit(2), Vector(ProtoExpr.lit(1), ProtoExpr.lit(2), ProtoExpr.lit(3)))
    assertEquals(ExpressionEvaluator.eval(expr), ExpressionEvaluator.Success(true))

    val notIn =
      ProtoExpr.In(ProtoExpr.lit(5), Vector(ProtoExpr.lit(1), ProtoExpr.lit(2), ProtoExpr.lit(3)))
    assertEquals(ExpressionEvaluator.eval(notIn), ExpressionEvaluator.Success(false))

  // === Cast Tests ===

  test("evaluates Cast to String"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Cast(ProtoExpr.lit(42), ProtoType.StringType)),
      ExpressionEvaluator.Success("42")
    )

  test("evaluates Cast to Int"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Cast(ProtoExpr.lit(42L), ProtoType.IntType)),
      ExpressionEvaluator.Success(42)
    )

  // === Alias Tests ===

  test("Alias is transparent"):
    val expr = ProtoExpr.Alias(ProtoExpr.lit(42), "answer")
    assertEquals(ExpressionEvaluator.eval(expr), ExpressionEvaluator.Success(42))

  // === Error Cases ===

  test("unbound ColumnRef throws error"):
    val expr = ProtoExpr.ColumnRef("name", None, ProtoType.StringType, false)
    ExpressionEvaluator.eval(expr) match
      case ExpressionEvaluator.EvalError(msg) => assert(msg.contains("Unbound"))
      case other                              => fail(s"Expected EvalError, got $other")

  test("aggregate functions throw error"):
    val expr = ProtoExpr.Count(ProtoExpr.lit(1), false)
    ExpressionEvaluator.eval(expr) match
      case ExpressionEvaluator.EvalError(msg) => assert(msg.contains("Aggregate"))
      case other                              => fail(s"Expected EvalError, got $other")

  // === Phase 10: Math Function Tests ===

  test("evaluates ABS"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Abs(ProtoExpr.lit(-42))),
      ExpressionEvaluator.Success(42)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Abs(ProtoExpr.lit(42))),
      ExpressionEvaluator.Success(42)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Abs(ProtoExpr.lit(-3.14))),
      ExpressionEvaluator.Success(3.14)
    )

  test("evaluates CEIL"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Ceil(ProtoExpr.lit(3.1))),
      ExpressionEvaluator.Success(4L)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Ceil(ProtoExpr.lit(3.9))),
      ExpressionEvaluator.Success(4L)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Ceil(ProtoExpr.lit(-3.1))),
      ExpressionEvaluator.Success(-3L)
    )

  test("evaluates FLOOR"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Floor(ProtoExpr.lit(3.9))),
      ExpressionEvaluator.Success(3L)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Floor(ProtoExpr.lit(-3.1))),
      ExpressionEvaluator.Success(-4L)
    )

  test("evaluates ROUND"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Round(ProtoExpr.lit(3.456), ProtoExpr.lit(2))),
      ExpressionEvaluator.Success(3.46)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Round(ProtoExpr.lit(3.5), ProtoExpr.lit(0))),
      ExpressionEvaluator.Success(4.0)
    )

  test("evaluates SQRT"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Sqrt(ProtoExpr.lit(9.0))),
      ExpressionEvaluator.Success(3.0)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Sqrt(ProtoExpr.lit(2.0))),
      ExpressionEvaluator.Success(math.sqrt(2.0))
    )

  test("evaluates POW"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Pow(ProtoExpr.lit(2.0), ProtoExpr.lit(3.0))),
      ExpressionEvaluator.Success(8.0)
    )

  test("evaluates MOD (pmod)"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Pmod(ProtoExpr.lit(7.0), ProtoExpr.lit(3.0))),
      ExpressionEvaluator.Success(1.0)
    )

  test("evaluates SIGN"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Sign(ProtoExpr.lit(-42))),
      ExpressionEvaluator.Success(-1)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Sign(ProtoExpr.lit(42))),
      ExpressionEvaluator.Success(1)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Sign(ProtoExpr.lit(0))),
      ExpressionEvaluator.Success(0)
    )

  test("evaluates LOG"):
    val result = ExpressionEvaluator.eval(ProtoExpr.Log(ProtoExpr.lit(math.E), None))
    result match
      case ExpressionEvaluator.Success(v: Double) => assertEqualsDouble(v, 1.0, 0.0001)
      case other                                  => fail(s"Expected Success(1.0), got $other")

  test("evaluates EXP"):
    val result = ExpressionEvaluator.eval(ProtoExpr.Exp(ProtoExpr.lit(1.0)))
    result match
      case ExpressionEvaluator.Success(v: Double) => assertEqualsDouble(v, math.E, 0.0001)
      case other                                  => fail(s"Expected Success(e), got $other")

  // === Phase 10: String Function Tests ===

  test("evaluates LENGTH"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Length(ProtoExpr.lit("hello"))),
      ExpressionEvaluator.Success(5)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Length(ProtoExpr.lit(""))),
      ExpressionEvaluator.Success(0)
    )

  test("evaluates REPLACE"):
    val expr = ProtoExpr.Replace(
      ProtoExpr.lit("hello world"),
      ProtoExpr.lit("world"),
      ProtoExpr.lit("scala")
    )
    assertEquals(ExpressionEvaluator.eval(expr), ExpressionEvaluator.Success("hello scala"))

  test("evaluates REVERSE"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.Reverse(ProtoExpr.lit("hello"))),
      ExpressionEvaluator.Success("olleh")
    )

  test("evaluates StringRepeat"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.StringRepeat(ProtoExpr.lit("ab"), ProtoExpr.lit(3))),
      ExpressionEvaluator.Success("ababab")
    )

  test("evaluates StringLocate"):
    val expr = ProtoExpr.StringLocate(ProtoExpr.lit("o"), ProtoExpr.lit("hello"), None)
    assertEquals(ExpressionEvaluator.eval(expr), ExpressionEvaluator.Success(5)) // 1-based index

  test("evaluates LPAD"):
    val expr = ProtoExpr.Lpad(ProtoExpr.lit("hi"), ProtoExpr.lit(5), ProtoExpr.lit("0"))
    assertEquals(ExpressionEvaluator.eval(expr), ExpressionEvaluator.Success("000hi"))

  test("evaluates RPAD"):
    val expr = ProtoExpr.Rpad(ProtoExpr.lit("hi"), ProtoExpr.lit(5), ProtoExpr.lit("0"))
    assertEquals(ExpressionEvaluator.eval(expr), ExpressionEvaluator.Success("hi000"))

  // === Phase 10: Null Function Tests ===

  test("evaluates NULLIF"):
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.NullIf(ProtoExpr.lit(1), ProtoExpr.lit(1))),
      ExpressionEvaluator.Success(null)
    )
    assertEquals(
      ExpressionEvaluator.eval(ProtoExpr.NullIf(ProtoExpr.lit(1), ProtoExpr.lit(2))),
      ExpressionEvaluator.Success(1)
    )
