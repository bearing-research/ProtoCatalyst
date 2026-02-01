package protocatalyst.expr

import protocatalyst.plan._
import protocatalyst.types._

class ProtoExprSuite extends munit.FunSuite:

  // === Literal Convenience Constructors ===

  test("lit(Boolean) creates BooleanValue"):
    val expr = ProtoExpr.lit(true)
    expr match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(v)) => assertEquals(v, true)
      case _ => fail(s"Expected Literal(BooleanValue), got $expr")

  test("lit(Int) creates IntValue"):
    val expr = ProtoExpr.lit(42)
    expr match
      case ProtoExpr.Literal(LiteralValue.IntValue(v)) => assertEquals(v, 42)
      case _ => fail(s"Expected Literal(IntValue), got $expr")

  test("lit(Long) creates LongValue"):
    val expr = ProtoExpr.lit(9876543210L)
    expr match
      case ProtoExpr.Literal(LiteralValue.LongValue(v)) => assertEquals(v, 9876543210L)
      case _ => fail(s"Expected Literal(LongValue), got $expr")

  test("lit(Double) creates DoubleValue"):
    val expr = ProtoExpr.lit(3.14159)
    expr match
      case ProtoExpr.Literal(LiteralValue.DoubleValue(v)) => assertEquals(v, 3.14159)
      case _ => fail(s"Expected Literal(DoubleValue), got $expr")

  test("lit(String) creates StringValue"):
    val expr = ProtoExpr.lit("hello")
    expr match
      case ProtoExpr.Literal(LiteralValue.StringValue(v)) => assertEquals(v, "hello")
      case _ => fail(s"Expected Literal(StringValue), got $expr")

  test("litNull creates NullValue with specified type"):
    val expr = ProtoExpr.litNull(ProtoType.StringType)
    expr match
      case ProtoExpr.Literal(LiteralValue.NullValue(dt)) => assertEquals(dt, ProtoType.StringType)
      case _ => fail(s"Expected Literal(NullValue), got $expr")

  // === ColumnRef Construction ===

  test("ColumnRef with qualifier"):
    val expr = ProtoExpr.ColumnRef("id", Some("users"), ProtoType.LongType, nullable = false)
    expr match
      case ProtoExpr.ColumnRef(name, qual, dt, null_) =>
        assertEquals(name, "id")
        assertEquals(qual, Some("users"))
        assertEquals(dt, ProtoType.LongType)
        assertEquals(null_, false)
      case _ => fail(s"Expected ColumnRef, got $expr")

  test("ColumnRef without qualifier"):
    val expr = ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true)
    expr match
      case ProtoExpr.ColumnRef(name, qual, dt, null_) =>
        assertEquals(name, "name")
        assertEquals(qual, None)
        assertEquals(dt, ProtoType.StringType)
        assertEquals(null_, true)
      case _ => fail(s"Expected ColumnRef, got $expr")

  // === BoundRef Construction ===

  test("BoundRef creation"):
    val expr = ProtoExpr.BoundRef(5, ProtoType.IntType, nullable = false)
    expr match
      case ProtoExpr.BoundRef(idx, dt, null_) =>
        assertEquals(idx, 5)
        assertEquals(dt, ProtoType.IntType)
        assertEquals(null_, false)
      case _ => fail(s"Expected BoundRef, got $expr")

  // === Comparison Expressions ===

  test("Eq construction"):
    val left = ProtoExpr.lit(10)
    val right = ProtoExpr.lit(20)
    val expr = ProtoExpr.Eq(left, right)
    expr match
      case ProtoExpr.Eq(l, r) =>
        assertEquals(l, left)
        assertEquals(r, right)
      case _ => fail(s"Expected Eq, got $expr")

  test("all comparison operators construct correctly"):
    val left = ProtoExpr.lit(1)
    val right = ProtoExpr.lit(2)
    val comparisons = List(
      (ProtoExpr.Eq(left, right), "Eq"),
      (ProtoExpr.NotEq(left, right), "NotEq"),
      (ProtoExpr.Lt(left, right), "Lt"),
      (ProtoExpr.LtEq(left, right), "LtEq"),
      (ProtoExpr.Gt(left, right), "Gt"),
      (ProtoExpr.GtEq(left, right), "GtEq")
    )
    for (expr, name) <- comparisons do
      assert(expr.isInstanceOf[ProtoExpr], s"$name should be a ProtoExpr")

  // === Logical Expressions ===

  test("And with multiple children"):
    val a = ProtoExpr.lit(true)
    val b = ProtoExpr.lit(false)
    val c = ProtoExpr.lit(true)
    val expr = ProtoExpr.And(Vector(a, b, c))
    expr match
      case ProtoExpr.And(children) =>
        assertEquals(children.size, 3)
      case _ => fail(s"Expected And, got $expr")

  test("Or with multiple children"):
    val a = ProtoExpr.lit(true)
    val b = ProtoExpr.lit(false)
    val expr = ProtoExpr.Or(Vector(a, b))
    expr match
      case ProtoExpr.Or(children) =>
        assertEquals(children.size, 2)
      case _ => fail(s"Expected Or, got $expr")

  test("Not construction"):
    val child = ProtoExpr.lit(true)
    val expr = ProtoExpr.Not(child)
    expr match
      case ProtoExpr.Not(c) => assertEquals(c, child)
      case _                => fail(s"Expected Not, got $expr")

  // === Null Handling Expressions ===

  test("IsNull construction"):
    val col = ProtoExpr.ColumnRef("x", None, ProtoType.IntType, nullable = true)
    val expr = ProtoExpr.IsNull(col)
    expr match
      case ProtoExpr.IsNull(c) => assertEquals(c, col)
      case _                   => fail(s"Expected IsNull, got $expr")

  test("IsNotNull construction"):
    val col = ProtoExpr.ColumnRef("x", None, ProtoType.IntType, nullable = true)
    val expr = ProtoExpr.IsNotNull(col)
    expr match
      case ProtoExpr.IsNotNull(c) => assertEquals(c, col)
      case _                      => fail(s"Expected IsNotNull, got $expr")

  test("Coalesce construction"):
    val a = ProtoExpr.ColumnRef("x", None, ProtoType.IntType, nullable = true)
    val b = ProtoExpr.lit(0)
    val expr = ProtoExpr.Coalesce(Vector(a, b))
    expr match
      case ProtoExpr.Coalesce(children) =>
        assertEquals(children.size, 2)
      case _ => fail(s"Expected Coalesce, got $expr")

  test("NullIf construction"):
    val left = ProtoExpr.lit("empty")
    val right = ProtoExpr.lit("")
    val expr = ProtoExpr.NullIf(left, right)
    expr match
      case ProtoExpr.NullIf(l, r) =>
        assertEquals(l, left)
        assertEquals(r, right)
      case _ => fail(s"Expected NullIf, got $expr")

  // === Arithmetic Expressions ===

  test("arithmetic operators construct correctly"):
    val left = ProtoExpr.lit(10)
    val right = ProtoExpr.lit(5)
    val arithmetic = List(
      ProtoExpr.Add(left, right),
      ProtoExpr.Subtract(left, right),
      ProtoExpr.Multiply(left, right),
      ProtoExpr.Divide(left, right)
    )
    for expr <- arithmetic do assert(expr.isInstanceOf[ProtoExpr])

  // === Math Functions ===

  test("Abs construction"):
    val child = ProtoExpr.lit(-5)
    val expr = ProtoExpr.Abs(child)
    expr match
      case ProtoExpr.Abs(c) => assertEquals(c, child)
      case _                => fail(s"Expected Abs, got $expr")

  test("Round construction"):
    val child = ProtoExpr.lit(3.14159)
    val scale = ProtoExpr.lit(2)
    val expr = ProtoExpr.Round(child, scale)
    expr match
      case ProtoExpr.Round(c, s) =>
        assertEquals(c, child)
        assertEquals(s, scale)
      case _ => fail(s"Expected Round, got $expr")

  test("math functions construct correctly"):
    val child = ProtoExpr.lit(4.0)
    val mathFuncs = List(
      ProtoExpr.Ceil(child),
      ProtoExpr.Floor(child),
      ProtoExpr.Sqrt(child),
      ProtoExpr.Cbrt(child),
      ProtoExpr.Sign(child),
      ProtoExpr.Exp(child)
    )
    for expr <- mathFuncs do assert(expr.isInstanceOf[ProtoExpr])

  test("Pow construction"):
    val base = ProtoExpr.lit(2.0)
    val exp = ProtoExpr.lit(3.0)
    val expr = ProtoExpr.Pow(base, exp)
    expr match
      case ProtoExpr.Pow(b, e) =>
        assertEquals(b, base)
        assertEquals(e, exp)
      case _ => fail(s"Expected Pow, got $expr")

  test("Log construction with base"):
    val child = ProtoExpr.lit(8.0)
    val base = ProtoExpr.lit(2.0)
    val expr = ProtoExpr.Log(child, Some(base))
    expr match
      case ProtoExpr.Log(c, Some(b)) =>
        assertEquals(c, child)
        assertEquals(b, base)
      case _ => fail(s"Expected Log with base, got $expr")

  test("Log construction without base"):
    val child = ProtoExpr.lit(2.71828)
    val expr = ProtoExpr.Log(child, None)
    expr match
      case ProtoExpr.Log(c, None) => assertEquals(c, child)
      case _                      => fail(s"Expected Log without base, got $expr")

  // === String Expressions ===

  test("Concat construction"):
    val strs = Vector(ProtoExpr.lit("hello"), ProtoExpr.lit(" "), ProtoExpr.lit("world"))
    val expr = ProtoExpr.Concat(strs)
    expr match
      case ProtoExpr.Concat(children) =>
        assertEquals(children.size, 3)
      case _ => fail(s"Expected Concat, got $expr")

  test("Substring construction"):
    val str = ProtoExpr.lit("hello")
    val pos = ProtoExpr.lit(1)
    val len = ProtoExpr.lit(3)
    val expr = ProtoExpr.Substring(str, pos, len)
    expr match
      case ProtoExpr.Substring(s, p, l) =>
        assertEquals(s, str)
        assertEquals(p, pos)
        assertEquals(l, len)
      case _ => fail(s"Expected Substring, got $expr")

  test("Upper and Lower construction"):
    val str = ProtoExpr.lit("Hello")
    val upper = ProtoExpr.Upper(str)
    val lower = ProtoExpr.Lower(str)
    assertEquals(upper.isInstanceOf[ProtoExpr.Upper], true)
    assertEquals(lower.isInstanceOf[ProtoExpr.Lower], true)

  test("Trim with different types"):
    val str = ProtoExpr.lit(" hello ")
    val trimBoth = ProtoExpr.Trim(str, None, TrimType.Both)
    val trimLeading = ProtoExpr.Trim(str, Some(ProtoExpr.lit(" ")), TrimType.Leading)
    val trimTrailing = ProtoExpr.Trim(str, None, TrimType.Trailing)

    trimBoth match
      case ProtoExpr.Trim(_, _, TrimType.Both) => ()
      case _                                   => fail("Expected TrimType.Both")

    trimLeading match
      case ProtoExpr.Trim(_, Some(_), TrimType.Leading) => ()
      case _ => fail("Expected TrimType.Leading with trimStr")

    trimTrailing match
      case ProtoExpr.Trim(_, _, TrimType.Trailing) => ()
      case _                                       => fail("Expected TrimType.Trailing")

  test("Length construction"):
    val str = ProtoExpr.lit("hello")
    val expr = ProtoExpr.Length(str)
    expr match
      case ProtoExpr.Length(c) => assertEquals(c, str)
      case _                   => fail(s"Expected Length, got $expr")

  test("Replace construction"):
    val str = ProtoExpr.lit("hello world")
    val search = ProtoExpr.lit("world")
    val replace = ProtoExpr.lit("scala")
    val expr = ProtoExpr.Replace(str, search, replace)
    expr match
      case ProtoExpr.Replace(s, se, re) =>
        assertEquals(s, str)
        assertEquals(se, search)
        assertEquals(re, replace)
      case _ => fail(s"Expected Replace, got $expr")

  test("StringLocate construction"):
    val substr = ProtoExpr.lit("lo")
    val str = ProtoExpr.lit("hello")
    val expr = ProtoExpr.StringLocate(substr, str, Some(ProtoExpr.lit(1)))
    expr match
      case ProtoExpr.StringLocate(_, _, Some(_)) => ()
      case _                                     => fail(s"Expected StringLocate, got $expr")

  // === Aggregate Expressions ===

  test("Count with distinct flag"):
    val col = ProtoExpr.ColumnRef("id", None, ProtoType.LongType, nullable = false)
    val countAll = ProtoExpr.Count(col, distinct = false)
    val countDistinct = ProtoExpr.Count(col, distinct = true)

    countAll match
      case ProtoExpr.Count(_, false) => ()
      case _                         => fail("Expected Count with distinct=false")

    countDistinct match
      case ProtoExpr.Count(_, true) => ()
      case _                        => fail("Expected Count with distinct=true")

  test("aggregate functions construct correctly"):
    val col = ProtoExpr.ColumnRef("value", None, ProtoType.DoubleType, nullable = false)
    val aggregates = List(
      ProtoExpr.Sum(col),
      ProtoExpr.Avg(col),
      ProtoExpr.Min(col),
      ProtoExpr.Max(col)
    )
    for expr <- aggregates do assert(expr.isInstanceOf[ProtoExpr])

  // === Control Flow Expressions ===

  test("CaseWhen with else"):
    val cond = ProtoExpr.Eq(ProtoExpr.lit(1), ProtoExpr.lit(1))
    val thenValue = ProtoExpr.lit("yes")
    val elseValue = ProtoExpr.lit("no")
    val expr = ProtoExpr.CaseWhen(Vector((cond, thenValue)), Some(elseValue))
    expr match
      case ProtoExpr.CaseWhen(branches, Some(els)) =>
        assertEquals(branches.size, 1)
        assertEquals(els, elseValue)
      case _ => fail(s"Expected CaseWhen with else, got $expr")

  test("CaseWhen without else"):
    val cond = ProtoExpr.Eq(ProtoExpr.lit(1), ProtoExpr.lit(1))
    val thenValue = ProtoExpr.lit("yes")
    val expr = ProtoExpr.CaseWhen(Vector((cond, thenValue)), None)
    expr match
      case ProtoExpr.CaseWhen(branches, None) =>
        assertEquals(branches.size, 1)
      case _ => fail(s"Expected CaseWhen without else, got $expr")

  test("CaseWhen with multiple branches"):
    val branches = Vector(
      (ProtoExpr.Eq(ProtoExpr.lit(1), ProtoExpr.lit(1)), ProtoExpr.lit("one")),
      (ProtoExpr.Eq(ProtoExpr.lit(2), ProtoExpr.lit(2)), ProtoExpr.lit("two")),
      (ProtoExpr.Eq(ProtoExpr.lit(3), ProtoExpr.lit(3)), ProtoExpr.lit("three"))
    )
    val expr = ProtoExpr.CaseWhen(branches, Some(ProtoExpr.lit("other")))
    expr match
      case ProtoExpr.CaseWhen(bs, _) =>
        assertEquals(bs.size, 3)
      case _ => fail(s"Expected CaseWhen with 3 branches, got $expr")

  test("If construction"):
    val pred = ProtoExpr.lit(true)
    val trueVal = ProtoExpr.lit(1)
    val falseVal = ProtoExpr.lit(0)
    val expr = ProtoExpr.If(pred, trueVal, falseVal)
    expr match
      case ProtoExpr.If(p, t, f) =>
        assertEquals(p, pred)
        assertEquals(t, trueVal)
        assertEquals(f, falseVal)
      case _ => fail(s"Expected If, got $expr")

  test("In construction"):
    val value = ProtoExpr.lit(1)
    val list = Vector(ProtoExpr.lit(1), ProtoExpr.lit(2), ProtoExpr.lit(3))
    val expr = ProtoExpr.In(value, list)
    expr match
      case ProtoExpr.In(v, l) =>
        assertEquals(v, value)
        assertEquals(l.size, 3)
      case _ => fail(s"Expected In, got $expr")

  // === Pattern Matching ===

  test("Like construction"):
    val value = ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true)
    val pattern = ProtoExpr.lit("%test%")
    val expr = ProtoExpr.Like(value, pattern, None)
    expr match
      case ProtoExpr.Like(v, p, None) =>
        assertEquals(v, value)
        assertEquals(p, pattern)
      case _ => fail(s"Expected Like, got $expr")

  test("Like with escape"):
    val value = ProtoExpr.lit("test_string")
    val pattern = ProtoExpr.lit("%\\_string")
    val escape = ProtoExpr.lit("\\")
    val expr = ProtoExpr.Like(value, pattern, Some(escape))
    expr match
      case ProtoExpr.Like(_, _, Some(e)) => assertEquals(e, escape)
      case _                             => fail(s"Expected Like with escape, got $expr")

  // === Cast and Alias ===

  test("Cast construction"):
    val child = ProtoExpr.lit(42)
    val expr = ProtoExpr.Cast(child, ProtoType.StringType)
    expr match
      case ProtoExpr.Cast(c, t) =>
        assertEquals(c, child)
        assertEquals(t, ProtoType.StringType)
      case _ => fail(s"Expected Cast, got $expr")

  test("Alias construction"):
    val child = ProtoExpr.Add(ProtoExpr.lit(1), ProtoExpr.lit(2))
    val expr = ProtoExpr.Alias(child, "sum_result")
    expr match
      case ProtoExpr.Alias(c, name) =>
        assertEquals(c, child)
        assertEquals(name, "sum_result")
      case _ => fail(s"Expected Alias, got $expr")

  // === Subquery Expressions ===

  test("ScalarSubquery construction"):
    val plan = ProtoLogicalPlan.RelationRef("t", None, emptyContract)
    val expr = ProtoExpr.ScalarSubquery(plan)
    expr match
      case ProtoExpr.ScalarSubquery(p) => assertEquals(p, plan)
      case _                           => fail(s"Expected ScalarSubquery, got $expr")

  test("Exists construction"):
    val plan = ProtoLogicalPlan.RelationRef("t", None, emptyContract)
    val expr = ProtoExpr.Exists(plan)
    expr match
      case ProtoExpr.Exists(p) => assertEquals(p, plan)
      case _                   => fail(s"Expected Exists, got $expr")

  test("InSubquery construction"):
    val value = ProtoExpr.lit(1)
    val plan = ProtoLogicalPlan.RelationRef("t", None, emptyContract)
    val expr = ProtoExpr.InSubquery(value, plan)
    expr match
      case ProtoExpr.InSubquery(v, p) =>
        assertEquals(v, value)
        assertEquals(p, plan)
      case _ => fail(s"Expected InSubquery, got $expr")

  // === Window Functions ===

  test("RowNumber construction"):
    val expr = ProtoExpr.RowNumber()
    assert(expr.isInstanceOf[ProtoExpr.RowNumber])

  test("Rank and DenseRank construction"):
    val rank = ProtoExpr.Rank()
    val denseRank = ProtoExpr.DenseRank()
    assert(rank.isInstanceOf[ProtoExpr.Rank])
    assert(denseRank.isInstanceOf[ProtoExpr.DenseRank])

  test("Ntile construction"):
    val n = ProtoExpr.lit(4)
    val expr = ProtoExpr.Ntile(n)
    expr match
      case ProtoExpr.Ntile(num) => assertEquals(num, n)
      case _                    => fail(s"Expected Ntile, got $expr")

  test("Lead construction"):
    val input = ProtoExpr.ColumnRef("value", None, ProtoType.IntType, nullable = true)
    val offset = ProtoExpr.lit(1)
    val default = ProtoExpr.lit(0)
    val expr = ProtoExpr.Lead(input, offset, Some(default))
    expr match
      case ProtoExpr.Lead(i, o, Some(d)) =>
        assertEquals(i, input)
        assertEquals(o, offset)
        assertEquals(d, default)
      case _ => fail(s"Expected Lead, got $expr")

  test("Lag construction"):
    val input = ProtoExpr.ColumnRef("value", None, ProtoType.IntType, nullable = true)
    val offset = ProtoExpr.lit(1)
    val expr = ProtoExpr.Lag(input, offset, None)
    expr match
      case ProtoExpr.Lag(i, o, None) =>
        assertEquals(i, input)
        assertEquals(o, offset)
      case _ => fail(s"Expected Lag, got $expr")

  test("FirstValue and LastValue construction"):
    val input = ProtoExpr.ColumnRef("value", None, ProtoType.IntType, nullable = true)
    val first = ProtoExpr.FirstValue(input, ignoreNulls = true)
    val last = ProtoExpr.LastValue(input, ignoreNulls = false)

    first match
      case ProtoExpr.FirstValue(_, true) => ()
      case _                             => fail(s"Expected FirstValue with ignoreNulls=true")

    last match
      case ProtoExpr.LastValue(_, false) => ()
      case _                             => fail(s"Expected LastValue with ignoreNulls=false")

  test("NthValue construction"):
    val input = ProtoExpr.ColumnRef("value", None, ProtoType.IntType, nullable = true)
    val n = ProtoExpr.lit(3)
    val expr = ProtoExpr.NthValue(input, n)
    expr match
      case ProtoExpr.NthValue(i, num) =>
        assertEquals(i, input)
        assertEquals(num, n)
      case _ => fail(s"Expected NthValue, got $expr")

  test("WindowExpr construction"):
    val func = ProtoExpr.RowNumber()
    val partition = Vector(ProtoExpr.ColumnRef("dept", None, ProtoType.StringType, nullable = true))
    val order = Vector(
      SortOrder(
        ProtoExpr.ColumnRef("date", None, ProtoType.DateType, nullable = false),
        SortDirection.Ascending,
        NullOrdering.NullsFirst
      )
    )
    val frame = WindowFrame(FrameType.Rows, FrameBound.UnboundedPreceding, FrameBound.CurrentRow)
    val expr = ProtoExpr.WindowExpr(func, partition, order, Some(frame))

    expr match
      case ProtoExpr.WindowExpr(f, p, o, Some(fr)) =>
        assertEquals(f, func)
        assertEquals(p, partition)
        assertEquals(o, order)
        assertEquals(fr, frame)
      case _ => fail(s"Expected WindowExpr, got $expr")

  // === OpaqueCall ===

  test("OpaqueCall construction"):
    val expr = ProtoExpr.OpaqueCall(
      "my_udf",
      Vector(ProtoExpr.lit(1), ProtoExpr.lit("test")),
      Some(ProtoType.StringType),
      deterministic = false
    )
    expr match
      case ProtoExpr.OpaqueCall(name, args, retType, det) =>
        assertEquals(name, "my_udf")
        assertEquals(args.size, 2)
        assertEquals(retType, Some(ProtoType.StringType))
        assertEquals(det, false)
      case _ => fail(s"Expected OpaqueCall, got $expr")

  test("OpaqueCall without return type"):
    val expr = ProtoExpr.OpaqueCall(
      "void_udf",
      Vector.empty,
      None,
      deterministic = true
    )
    expr match
      case ProtoExpr.OpaqueCall(_, _, None, true) => ()
      case _ => fail(s"Expected OpaqueCall without return type")

  // === WindowFrame Tests ===

  test("WindowFrame with Rows"):
    val frame = WindowFrame(FrameType.Rows, FrameBound.UnboundedPreceding, FrameBound.CurrentRow)
    assertEquals(frame.frameType, FrameType.Rows)
    assertEquals(frame.lower, FrameBound.UnboundedPreceding)
    assertEquals(frame.upper, FrameBound.CurrentRow)

  test("WindowFrame with Range"):
    val frame = WindowFrame(FrameType.Range, FrameBound.Preceding(10), FrameBound.Following(5))
    assertEquals(frame.frameType, FrameType.Range)
    frame.lower match
      case FrameBound.Preceding(n) => assertEquals(n, 10L)
      case _                       => fail("Expected Preceding(10)")
    frame.upper match
      case FrameBound.Following(n) => assertEquals(n, 5L)
      case _                       => fail("Expected Following(5)")

  test("all FrameBound variants"):
    val bounds = List(
      FrameBound.UnboundedPreceding,
      FrameBound.UnboundedFollowing,
      FrameBound.CurrentRow,
      FrameBound.Preceding(100),
      FrameBound.Following(50)
    )
    for bound <- bounds do assert(bound.isInstanceOf[FrameBound])

  // === Helper ===

  private val emptyContract = protocatalyst.schema.SchemaContract(
    "t",
    Vector.empty,
    protocatalyst.schema.SchemaFingerprint.fromLong(0L)
  )
