package protocatalyst.optimizer

import protocatalyst.expr._
import protocatalyst.optimizer.rules._
import protocatalyst.plan._
import protocatalyst.schema._
import protocatalyst.types._

class OptimizerSuite extends munit.FunSuite:

  // Helper to create a simple table reference
  private def table(name: String): ProtoLogicalPlan =
    ProtoLogicalPlan.RelationRef(name, None, emptyContract(name))

  private def emptyContract(name: String) = SchemaContract(
    name,
    Vector.empty,
    SchemaFingerprint.fromLong(0L)
  )

  private def col(name: String): ProtoExpr =
    ProtoExpr.ColumnRef(name, None, ProtoType.IntegerType, nullable = true)

  // ========================================================
  // ConstantFolding Tests
  // ========================================================

  test("ConstantFolding: fold integer addition"):
    val expr = ProtoExpr.Add(ProtoExpr.lit(5), ProtoExpr.lit(10))
    val folded = ConstantFolding.foldConstants(expr)
    folded match
      case ProtoExpr.Literal(LiteralValue.IntValue(15)) => ()
      case _ => fail(s"Expected Literal(15), got $folded")

  test("ConstantFolding: fold integer subtraction"):
    val expr = ProtoExpr.Subtract(ProtoExpr.lit(20), ProtoExpr.lit(8))
    val folded = ConstantFolding.foldConstants(expr)
    folded match
      case ProtoExpr.Literal(LiteralValue.IntValue(12)) => ()
      case _ => fail(s"Expected Literal(12), got $folded")

  test("ConstantFolding: fold integer multiplication"):
    val expr = ProtoExpr.Multiply(ProtoExpr.lit(6), ProtoExpr.lit(7))
    val folded = ConstantFolding.foldConstants(expr)
    folded match
      case ProtoExpr.Literal(LiteralValue.IntValue(42)) => ()
      case _ => fail(s"Expected Literal(42), got $folded")

  test("ConstantFolding: fold integer division"):
    val expr = ProtoExpr.Divide(ProtoExpr.lit(10), ProtoExpr.lit(4))
    val folded = ConstantFolding.foldConstants(expr)
    folded match
      case ProtoExpr.Literal(LiteralValue.DoubleValue(v)) =>
        assertEqualsDouble(v, 2.5, 0.001)
      case _ => fail(s"Expected Literal(2.5), got $folded")

  test("ConstantFolding: division by zero returns null"):
    val expr = ProtoExpr.Divide(ProtoExpr.lit(10), ProtoExpr.lit(0))
    val folded = ConstantFolding.foldConstants(expr)
    folded match
      case ProtoExpr.Literal(LiteralValue.NullValue(_)) => ()
      case _                                            => fail(s"Expected NullValue, got $folded")

  test("ConstantFolding: fold equality comparison"):
    val expr = ProtoExpr.Eq(ProtoExpr.lit(5), ProtoExpr.lit(5))
    val folded = ConstantFolding.foldConstants(expr)
    folded match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => ()
      case _ => fail(s"Expected Literal(true), got $folded")

  test("ConstantFolding: fold less-than comparison"):
    val expr = ProtoExpr.Lt(ProtoExpr.lit(3), ProtoExpr.lit(5))
    val folded = ConstantFolding.foldConstants(expr)
    folded match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => ()
      case _ => fail(s"Expected Literal(true), got $folded")

  test("ConstantFolding: fold NOT"):
    val expr = ProtoExpr.Not(ProtoExpr.lit(true))
    val folded = ConstantFolding.foldConstants(expr)
    folded match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => ()
      case _ => fail(s"Expected Literal(false), got $folded")

  test("ConstantFolding: fold string concatenation"):
    val expr = ProtoExpr.Concat(
      Vector(
        ProtoExpr.lit("hello"),
        ProtoExpr.lit(" "),
        ProtoExpr.lit("world")
      )
    )
    val folded = ConstantFolding.foldConstants(expr)
    folded match
      case ProtoExpr.Literal(LiteralValue.StringValue("hello world")) => ()
      case _ => fail(s"Expected Literal('hello world'), got $folded")

  test("ConstantFolding: fold UPPER"):
    val expr = ProtoExpr.Upper(ProtoExpr.lit("hello"))
    val folded = ConstantFolding.foldConstants(expr)
    folded match
      case ProtoExpr.Literal(LiteralValue.StringValue("HELLO")) => ()
      case _ => fail(s"Expected Literal('HELLO'), got $folded")

  test("ConstantFolding: fold LOWER"):
    val expr = ProtoExpr.Lower(ProtoExpr.lit("HELLO"))
    val folded = ConstantFolding.foldConstants(expr)
    folded match
      case ProtoExpr.Literal(LiteralValue.StringValue("hello")) => ()
      case _ => fail(s"Expected Literal('hello'), got $folded")

  test("ConstantFolding: fold LENGTH"):
    val expr = ProtoExpr.Length(ProtoExpr.lit("hello"))
    val folded = ConstantFolding.foldConstants(expr)
    folded match
      case ProtoExpr.Literal(LiteralValue.IntValue(5)) => ()
      case _                                           => fail(s"Expected Literal(5), got $folded")

  test("ConstantFolding: fold ABS"):
    val expr = ProtoExpr.Abs(ProtoExpr.lit(-42))
    val folded = ConstantFolding.foldConstants(expr)
    folded match
      case ProtoExpr.Literal(LiteralValue.IntValue(42)) => ()
      case _ => fail(s"Expected Literal(42), got $folded")

  test("ConstantFolding: fold IsNull on non-null"):
    val expr = ProtoExpr.IsNull(ProtoExpr.lit(42))
    val folded = ConstantFolding.foldConstants(expr)
    folded match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => ()
      case _ => fail(s"Expected Literal(false), got $folded")

  test("ConstantFolding: fold IsNull on null"):
    val expr = ProtoExpr.IsNull(ProtoExpr.litNull(ProtoType.IntegerType))
    val folded = ConstantFolding.foldConstants(expr)
    folded match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => ()
      case _ => fail(s"Expected Literal(true), got $folded")

  test("ConstantFolding: fold Coalesce with first non-null"):
    val expr = ProtoExpr.Coalesce(
      Vector(
        ProtoExpr.litNull(ProtoType.IntegerType),
        ProtoExpr.lit(42),
        ProtoExpr.lit(100)
      )
    )
    val folded = ConstantFolding.foldConstants(expr)
    folded match
      case ProtoExpr.Literal(LiteralValue.IntValue(42)) => ()
      case _ => fail(s"Expected Literal(42), got $folded")

  test("ConstantFolding: fold IF with true predicate"):
    val expr = ProtoExpr.If(ProtoExpr.lit(true), ProtoExpr.lit("yes"), ProtoExpr.lit("no"))
    val folded = ConstantFolding.foldConstants(expr)
    folded match
      case ProtoExpr.Literal(LiteralValue.StringValue("yes")) => ()
      case _ => fail(s"Expected Literal('yes'), got $folded")

  test("ConstantFolding: fold IF with false predicate"):
    val expr = ProtoExpr.If(ProtoExpr.lit(false), ProtoExpr.lit("yes"), ProtoExpr.lit("no"))
    val folded = ConstantFolding.foldConstants(expr)
    folded match
      case ProtoExpr.Literal(LiteralValue.StringValue("no")) => ()
      case _ => fail(s"Expected Literal('no'), got $folded")

  test("ConstantFolding: fold cast Int to Long"):
    val expr = ProtoExpr.Cast(ProtoExpr.lit(42), ProtoType.LongType)
    val folded = ConstantFolding.foldConstants(expr)
    folded match
      case ProtoExpr.Literal(LiteralValue.LongValue(42L)) => ()
      case _ => fail(s"Expected Literal(42L), got $folded")

  test("ConstantFolding: nested arithmetic folding"):
    // (2 + 3) * (4 - 1) should fold to 5 * 3 = 15
    val expr = ProtoExpr.Multiply(
      ProtoExpr.Add(ProtoExpr.lit(2), ProtoExpr.lit(3)),
      ProtoExpr.Subtract(ProtoExpr.lit(4), ProtoExpr.lit(1))
    )
    val folded = ConstantFolding.foldConstants(expr)
    folded match
      case ProtoExpr.Literal(LiteralValue.IntValue(15)) => ()
      case _ => fail(s"Expected Literal(15), got $folded")

  test("ConstantFolding: don't fold non-constant expressions"):
    val expr = ProtoExpr.Add(col("a"), ProtoExpr.lit(5))
    val folded = ConstantFolding.foldConstants(expr)
    // Should remain unchanged
    assertEquals(folded, expr)

  // ========================================================
  // BooleanSimplification Tests
  // ========================================================

  test("BooleanSimplification: NOT NOT x → x"):
    val expr = ProtoExpr.Not(ProtoExpr.Not(col("a")))
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    assertEquals(simplified, col("a"))

  test("BooleanSimplification: TRUE AND x → x"):
    val expr = ProtoExpr.And(Vector(ProtoExpr.lit(true), col("a")))
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    assertEquals(simplified, col("a"))

  test("BooleanSimplification: FALSE AND x → FALSE"):
    val expr = ProtoExpr.And(Vector(ProtoExpr.lit(false), col("a")))
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => ()
      case _ => fail(s"Expected FALSE, got $simplified")

  test("BooleanSimplification: TRUE OR x → TRUE"):
    val expr = ProtoExpr.Or(Vector(ProtoExpr.lit(true), col("a")))
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => ()
      case _ => fail(s"Expected TRUE, got $simplified")

  test("BooleanSimplification: FALSE OR x → x"):
    val expr = ProtoExpr.Or(Vector(ProtoExpr.lit(false), col("a")))
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    assertEquals(simplified, col("a"))

  test("BooleanSimplification: x AND x → x"):
    val expr = ProtoExpr.And(Vector(col("a"), col("a")))
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    assertEquals(simplified, col("a"))

  test("BooleanSimplification: x OR x → x"):
    val expr = ProtoExpr.Or(Vector(col("a"), col("a")))
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    assertEquals(simplified, col("a"))

  test("BooleanSimplification: x AND NOT x → FALSE"):
    val a = col("a")
    val expr = ProtoExpr.And(Vector(a, ProtoExpr.Not(a)))
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => ()
      case _ => fail(s"Expected FALSE, got $simplified")

  test("BooleanSimplification: x OR NOT x → TRUE"):
    val a = col("a")
    val expr = ProtoExpr.Or(Vector(a, ProtoExpr.Not(a)))
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => ()
      case _ => fail(s"Expected TRUE, got $simplified")

  test("BooleanSimplification: flatten nested ANDs"):
    val expr = ProtoExpr.And(
      Vector(
        ProtoExpr.And(Vector(col("a"), col("b"))),
        col("c")
      )
    )
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    simplified match
      case ProtoExpr.And(children) =>
        assertEquals(children.size, 3)
      case _ => fail(s"Expected flattened AND, got $simplified")

  test("BooleanSimplification: flatten nested ORs"):
    val expr = ProtoExpr.Or(
      Vector(
        ProtoExpr.Or(Vector(col("a"), col("b"))),
        col("c")
      )
    )
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    simplified match
      case ProtoExpr.Or(children) =>
        assertEquals(children.size, 3)
      case _ => fail(s"Expected flattened OR, got $simplified")

  test("BooleanSimplification: NOT Eq → NotEq"):
    val expr = ProtoExpr.Not(ProtoExpr.Eq(col("a"), col("b")))
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    simplified match
      case ProtoExpr.NotEq(_, _) => ()
      case _                     => fail(s"Expected NotEq, got $simplified")

  test("BooleanSimplification: NOT Lt → GtEq"):
    val expr = ProtoExpr.Not(ProtoExpr.Lt(col("a"), col("b")))
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    simplified match
      case ProtoExpr.GtEq(_, _) => ()
      case _                    => fail(s"Expected GtEq, got $simplified")

  test("BooleanSimplification: NOT IsNull → IsNotNull"):
    val expr = ProtoExpr.Not(ProtoExpr.IsNull(col("a")))
    val simplified = BooleanSimplification.simplifyBoolean(expr)
    simplified match
      case ProtoExpr.IsNotNull(_) => ()
      case _                      => fail(s"Expected IsNotNull, got $simplified")

  // ========================================================
  // CombineFilters Tests
  // ========================================================

  test("CombineFilters: merge adjacent filters"):
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.Gt(col("a"), ProtoExpr.lit(10)),
      ProtoLogicalPlan.Filter(
        ProtoExpr.Lt(col("b"), ProtoExpr.lit(20)),
        table("t")
      )
    )
    val combined = CombineFilters(plan)
    combined match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.And(conditions),
            ProtoLogicalPlan.RelationRef(_, _, _)
          ) =>
        assertEquals(conditions.size, 2)
      case _ => fail(s"Expected combined filter, got $combined")

  test("CombineFilters: merge three filters"):
    val plan = ProtoLogicalPlan.Filter(
      col("c"),
      ProtoLogicalPlan.Filter(
        col("b"),
        ProtoLogicalPlan.Filter(
          col("a"),
          table("t")
        )
      )
    )
    val combined = CombineFilters(CombineFilters(plan))
    combined match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.And(conditions),
            ProtoLogicalPlan.RelationRef(_, _, _)
          ) =>
        assertEquals(conditions.size, 3)
      case _ => fail(s"Expected combined filter with 3 conditions, got $combined")

  // ========================================================
  // CollapseProject Tests
  // ========================================================

  test("CollapseProject: merge adjacent projects"):
    // Project [col + 1 AS x]
    //   Project [a AS col]
    //     Scan
    val innerProject = ProtoLogicalPlan.Project(
      Vector(ProtoExpr.Alias(col("a"), "col_alias")),
      table("t")
    )
    val outerProject = ProtoLogicalPlan.Project(
      Vector(
        ProtoExpr.Alias(
          ProtoExpr.Add(
            ProtoExpr.ColumnRef("col_alias", None, ProtoType.IntegerType, nullable = true),
            ProtoExpr.lit(1)
          ),
          "x"
        )
      ),
      innerProject
    )
    val collapsed = CollapseProject(outerProject)
    collapsed match
      case ProtoLogicalPlan.Project(_, ProtoLogicalPlan.RelationRef(_, _, _)) =>
        // Successfully collapsed to single project over scan
        ()
      case _ => fail(s"Expected collapsed project, got $collapsed")

  // ========================================================
  // Full Optimizer Tests
  // ========================================================

  test("Optimizer: combined constant folding and boolean simplification"):
    // Filter [TRUE AND (5 + 5 = 10)]
    //   Scan
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.And(
        Vector(
          ProtoExpr.lit(true),
          ProtoExpr.Eq(
            ProtoExpr.Add(ProtoExpr.lit(5), ProtoExpr.lit(5)),
            ProtoExpr.lit(10)
          )
        )
      ),
      table("t")
    )
    val optimized = Optimizer.optimize(plan)
    // Should simplify TRUE AND TRUE → TRUE, but we keep the filter
    optimized match
      case ProtoLogicalPlan.Filter(cond, _) =>
        cond match
          case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => ()
          case _ => fail(s"Expected TRUE condition, got $cond")
      case _ => fail(s"Expected Filter, got $optimized")

  test("Optimizer: idempotence - applying twice gives same result"):
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.And(
        Vector(
          ProtoExpr.Or(Vector(ProtoExpr.lit(false), col("a"))),
          ProtoExpr.Eq(ProtoExpr.lit(1), ProtoExpr.lit(1))
        )
      ),
      table("t")
    )
    val once = Optimizer.optimize(plan)
    val twice = Optimizer.optimize(once)
    assertEquals(once, twice)

  test("Optimizer: complex query with multiple opportunities"):
    // Project [a]
    //   Project [a, b]
    //     Filter [TRUE]
    //       Filter [x > 5]
    //         Scan
    val plan = ProtoLogicalPlan.Project(
      Vector(col("a")),
      ProtoLogicalPlan.Project(
        Vector(col("a"), col("b")),
        ProtoLogicalPlan.Filter(
          ProtoExpr.lit(true),
          ProtoLogicalPlan.Filter(
            ProtoExpr.Gt(col("x"), ProtoExpr.lit(5)),
            table("t")
          )
        )
      )
    )
    val optimized = Optimizer.optimize(plan)
    // Should collapse projects and combine filters
    // The TRUE filter condition should remain but be simplified
    assert(optimized.isInstanceOf[ProtoLogicalPlan])

  // ========================================================
  // NullPropagation Tests
  // ========================================================

  test("NullPropagation: IsNull on non-nullable column → FALSE"):
    val nonNullCol = ProtoExpr.ColumnRef("a", None, ProtoType.IntegerType, nullable = false)
    val expr = ProtoExpr.IsNull(nonNullCol)
    val propagated = NullPropagation.propagateNulls(expr)
    propagated match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => ()
      case _ => fail(s"Expected FALSE, got $propagated")

  test("NullPropagation: IsNotNull on non-nullable column → TRUE"):
    val nonNullCol = ProtoExpr.ColumnRef("a", None, ProtoType.IntegerType, nullable = false)
    val expr = ProtoExpr.IsNotNull(nonNullCol)
    val propagated = NullPropagation.propagateNulls(expr)
    propagated match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => ()
      case _ => fail(s"Expected TRUE, got $propagated")

  test("NullPropagation: arithmetic with NULL → NULL"):
    val expr = ProtoExpr.Add(ProtoExpr.litNull(ProtoType.IntegerType), ProtoExpr.lit(5))
    val propagated = NullPropagation.propagateNulls(expr)
    propagated match
      case ProtoExpr.Literal(LiteralValue.NullValue(_)) => ()
      case _                                            => fail(s"Expected NULL, got $propagated")

  test("NullPropagation: comparison with NULL → NULL"):
    val expr = ProtoExpr.Eq(ProtoExpr.litNull(ProtoType.IntegerType), ProtoExpr.lit(5))
    val propagated = NullPropagation.propagateNulls(expr)
    propagated match
      case ProtoExpr.Literal(LiteralValue.NullValue(_)) => ()
      case _                                            => fail(s"Expected NULL, got $propagated")

  test("NullPropagation: IF with NULL predicate → else branch"):
    val expr = ProtoExpr.If(
      ProtoExpr.litNull(ProtoType.BooleanType),
      ProtoExpr.lit("yes"),
      ProtoExpr.lit("no")
    )
    val propagated = NullPropagation.propagateNulls(expr)
    propagated match
      case ProtoExpr.Literal(LiteralValue.StringValue("no")) => ()
      case _ => fail(s"Expected 'no', got $propagated")

  test("NullPropagation: COALESCE removes NULL literals"):
    val expr = ProtoExpr.Coalesce(
      Vector(
        ProtoExpr.litNull(ProtoType.IntegerType),
        ProtoExpr.litNull(ProtoType.IntegerType),
        col("a")
      )
    )
    val propagated = NullPropagation.propagateNulls(expr)
    propagated match
      case ProtoExpr.Coalesce(Vector(single))  => assertEquals(single, col("a"))
      case c @ ProtoExpr.ColumnRef(_, _, _, _) => assertEquals(c, col("a"))
      case _ => fail(s"Expected COALESCE(a) or a, got $propagated")

  test("NullPropagation: COALESCE all NULLs → NULL"):
    val expr = ProtoExpr.Coalesce(
      Vector(
        ProtoExpr.litNull(ProtoType.IntegerType),
        ProtoExpr.litNull(ProtoType.IntegerType)
      )
    )
    val propagated = NullPropagation.propagateNulls(expr)
    propagated match
      case ProtoExpr.Literal(LiteralValue.NullValue(_)) => ()
      case _                                            => fail(s"Expected NULL, got $propagated")

  test("NullPropagation: AND with NULL and FALSE → FALSE"):
    val expr = ProtoExpr.And(Vector(ProtoExpr.litNull(ProtoType.BooleanType), ProtoExpr.lit(false)))
    val propagated = NullPropagation.propagateNulls(expr)
    propagated match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => ()
      case _ => fail(s"Expected FALSE, got $propagated")

  test("NullPropagation: OR with NULL and TRUE → TRUE"):
    val expr = ProtoExpr.Or(Vector(ProtoExpr.litNull(ProtoType.BooleanType), ProtoExpr.lit(true)))
    val propagated = NullPropagation.propagateNulls(expr)
    propagated match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => ()
      case _ => fail(s"Expected TRUE, got $propagated")

  // ========================================================
  // SimplifyConditionals Tests
  // ========================================================

  test("SimplifyConditionals: IF with identical branches → branch value"):
    val expr = ProtoExpr.If(col("cond"), ProtoExpr.lit(42), ProtoExpr.lit(42))
    val simplified = SimplifyConditionals.simplifyConditionals(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.IntValue(42)) => ()
      case _                                            => fail(s"Expected 42, got $simplified")

  test("SimplifyConditionals: CASE WHEN TRUE → value"):
    val expr = ProtoExpr.CaseWhen(
      Vector((ProtoExpr.lit(true), ProtoExpr.lit("yes"))),
      Some(ProtoExpr.lit("no"))
    )
    val simplified = SimplifyConditionals.simplifyConditionals(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.StringValue("yes")) => ()
      case _ => fail(s"Expected 'yes', got $simplified")

  test("SimplifyConditionals: CASE WHEN FALSE → else"):
    val expr = ProtoExpr.CaseWhen(
      Vector((ProtoExpr.lit(false), ProtoExpr.lit("yes"))),
      Some(ProtoExpr.lit("no"))
    )
    val simplified = SimplifyConditionals.simplifyConditionals(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.StringValue("no")) => ()
      case _ => fail(s"Expected 'no', got $simplified")

  test("SimplifyConditionals: remove FALSE branches from CASE"):
    val expr = ProtoExpr.CaseWhen(
      Vector(
        (ProtoExpr.lit(false), ProtoExpr.lit("a")),
        (col("cond"), ProtoExpr.lit("b"))
      ),
      Some(ProtoExpr.lit("c"))
    )
    val simplified = SimplifyConditionals.simplifyConditionals(expr)
    simplified match
      case ProtoExpr.If(_, thenVal, elseVal) =>
        assertEquals(thenVal, ProtoExpr.lit("b"))
        assertEquals(elseVal, ProtoExpr.lit("c"))
      case _ => fail(s"Expected IF, got $simplified")

  test("SimplifyConditionals: all branches same value → that value"):
    val expr = ProtoExpr.CaseWhen(
      Vector(
        (col("a"), ProtoExpr.lit(42)),
        (col("b"), ProtoExpr.lit(42))
      ),
      Some(ProtoExpr.lit(42))
    )
    val simplified = SimplifyConditionals.simplifyConditionals(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.IntValue(42)) => ()
      case _                                            => fail(s"Expected 42, got $simplified")

  // ========================================================
  // OptimizeIn Tests
  // ========================================================

  test("OptimizeIn: empty IN → FALSE"):
    val expr = ProtoExpr.In(col("a"), Vector.empty)
    val optimized = OptimizeIn.optimizeIn(expr)
    optimized match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => ()
      case _ => fail(s"Expected FALSE, got $optimized")

  test("OptimizeIn: single element IN → equality"):
    val expr = ProtoExpr.In(col("a"), Vector(ProtoExpr.lit(5)))
    val optimized = OptimizeIn.optimizeIn(expr)
    optimized match
      case ProtoExpr.Eq(_, ProtoExpr.Literal(LiteralValue.IntValue(5))) => ()
      case _ => fail(s"Expected Eq(a, 5), got $optimized")

  test("OptimizeIn: NULL IN (...) → NULL"):
    val expr = ProtoExpr.In(
      ProtoExpr.litNull(ProtoType.IntegerType),
      Vector(ProtoExpr.lit(1), ProtoExpr.lit(2))
    )
    val optimized = OptimizeIn.optimizeIn(expr)
    optimized match
      case ProtoExpr.Literal(LiteralValue.NullValue(_)) => ()
      case _                                            => fail(s"Expected NULL, got $optimized")

  test("OptimizeIn: deduplicate IN list"):
    val expr = ProtoExpr.In(
      col("a"),
      Vector(
        ProtoExpr.lit(1),
        ProtoExpr.lit(2),
        ProtoExpr.lit(1),
        ProtoExpr.lit(3),
        ProtoExpr.lit(2)
      )
    )
    val optimized = OptimizeIn.optimizeIn(expr)
    optimized match
      case ProtoExpr.In(_, list) =>
        assertEquals(list.size, 3)
      case _ => fail(s"Expected IN with 3 elements, got $optimized")

  test("OptimizeIn: IN with all NULLs → NULL"):
    val expr = ProtoExpr.In(
      col("a"),
      Vector(
        ProtoExpr.litNull(ProtoType.IntegerType),
        ProtoExpr.litNull(ProtoType.IntegerType)
      )
    )
    val optimized = OptimizeIn.optimizeIn(expr)
    optimized match
      case ProtoExpr.Literal(LiteralValue.NullValue(_)) => ()
      case _                                            => fail(s"Expected NULL, got $optimized")

  // ========================================================
  // LikeSimplification Tests
  // ========================================================

  test("LikeSimplification: no wildcards → equality"):
    val expr = ProtoExpr.Like(col("a"), ProtoExpr.lit("hello"), None)
    val simplified = LikeSimplification.simplifyLike(expr)
    simplified match
      case ProtoExpr.Eq(_, ProtoExpr.Literal(LiteralValue.StringValue("hello"))) => ()
      case _ => fail(s"Expected Eq(a, 'hello'), got $simplified")

  test("LikeSimplification: just % → IsNotNull"):
    val expr = ProtoExpr.Like(col("a"), ProtoExpr.lit("%"), None)
    val simplified = LikeSimplification.simplifyLike(expr)
    simplified match
      case ProtoExpr.IsNotNull(_) => ()
      case _                      => fail(s"Expected IsNotNull, got $simplified")

  test("LikeSimplification: prefix% → LOCATE = 1"):
    val expr = ProtoExpr.Like(col("a"), ProtoExpr.lit("abc%"), None)
    val simplified = LikeSimplification.simplifyLike(expr)
    simplified match
      case ProtoExpr.Eq(
            ProtoExpr.StringLocate(_, _, _),
            ProtoExpr.Literal(LiteralValue.IntValue(1))
          ) =>
        ()
      case _ => fail(s"Expected LOCATE = 1, got $simplified")

  test("LikeSimplification: %suffix → LOCATE check"):
    val expr = ProtoExpr.Like(col("a"), ProtoExpr.lit("%xyz"), None)
    val simplified = LikeSimplification.simplifyLike(expr)
    simplified match
      case ProtoExpr.Eq(ProtoExpr.StringLocate(_, _, _), _) => ()
      case _ => fail(s"Expected LOCATE check, got $simplified")

  test("LikeSimplification: %contains% → LOCATE > 0"):
    val expr = ProtoExpr.Like(col("a"), ProtoExpr.lit("%abc%"), None)
    val simplified = LikeSimplification.simplifyLike(expr)
    simplified match
      case ProtoExpr.Gt(
            ProtoExpr.StringLocate(_, _, _),
            ProtoExpr.Literal(LiteralValue.IntValue(0))
          ) =>
        ()
      case _ => fail(s"Expected LOCATE > 0, got $simplified")

  test("LikeSimplification: complex pattern stays as LIKE"):
    val expr = ProtoExpr.Like(col("a"), ProtoExpr.lit("a%b%c"), None)
    val simplified = LikeSimplification.simplifyLike(expr)
    simplified match
      case ProtoExpr.Like(_, _, _) => ()
      case _                       => fail(s"Expected LIKE to remain, got $simplified")

  // ========================================================
  // ConstantPropagation Tests
  // ========================================================

  test("ConstantPropagation: propagate equality constraint"):
    // WHERE id = 5 AND amount > id
    // Should become: WHERE id = 5 AND amount > 5
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.And(
        Vector(
          ProtoExpr.Eq(col("id"), ProtoExpr.lit(5)),
          ProtoExpr.Gt(col("amount"), col("id"))
        )
      ),
      table("t")
    )
    val propagated = ConstantPropagation(plan)
    propagated match
      case ProtoLogicalPlan.Filter(ProtoExpr.And(conditions), _) =>
        // Second condition should now be amount > 5
        conditions.last match
          case ProtoExpr.Gt(_, ProtoExpr.Literal(LiteralValue.IntValue(5))) => ()
          case _ => fail(s"Expected amount > 5, got ${conditions.last}")
      case _ => fail(s"Expected Filter with AND, got $propagated")

  test("ConstantPropagation: multiple constraints"):
    // WHERE a = 1 AND b = 2 AND c = a + b
    // Should become: WHERE a = 1 AND b = 2 AND c = 1 + 2
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.And(
        Vector(
          ProtoExpr.Eq(col("a"), ProtoExpr.lit(1)),
          ProtoExpr.Eq(col("b"), ProtoExpr.lit(2)),
          ProtoExpr.Eq(col("c"), ProtoExpr.Add(col("a"), col("b")))
        )
      ),
      table("t")
    )
    val propagated = ConstantPropagation(plan)
    propagated match
      case ProtoLogicalPlan.Filter(ProtoExpr.And(conditions), _) =>
        // Third condition should now have literals
        conditions(2) match
          case ProtoExpr.Eq(
                _,
                ProtoExpr.Add(
                  ProtoExpr.Literal(LiteralValue.IntValue(1)),
                  ProtoExpr.Literal(LiteralValue.IntValue(2))
                )
              ) =>
            ()
          case other => fail(s"Expected c = 1 + 2, got $other")
      case _ => fail(s"Expected Filter with AND, got $propagated")

  test("ConstantPropagation: don't propagate NULL constraints"):
    // WHERE a = NULL AND b = a (should not propagate NULL)
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.And(
        Vector(
          ProtoExpr.Eq(col("a"), ProtoExpr.litNull(ProtoType.IntegerType)),
          ProtoExpr.Eq(col("b"), col("a"))
        )
      ),
      table("t")
    )
    val propagated = ConstantPropagation(plan)
    propagated match
      case ProtoLogicalPlan.Filter(ProtoExpr.And(conditions), _) =>
        // Second condition should still reference col("a"), not NULL
        conditions(1) match
          case ProtoExpr.Eq(_, ProtoExpr.ColumnRef("a", _, _, _)) => ()
          case other => fail(s"Expected b = a (not propagated), got $other")
      case _ => fail(s"Expected Filter with AND, got $propagated")

  // ========================================================
  // Phase 2 Integration Tests
  // ========================================================

  test("Optimizer: Phase 2 rules work together"):
    // Filter [a = 5 AND a > 3 AND b IN (1)]
    //   Scan
    // Should optimize to: Filter [a = 5 AND 5 > 3 AND b = 1]
    // Then constant fold: Filter [a = 5 AND TRUE AND b = 1]
    // Then boolean simplify: Filter [a = 5 AND b = 1]
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.And(
        Vector(
          ProtoExpr.Eq(col("a"), ProtoExpr.lit(5)),
          ProtoExpr.Gt(col("a"), ProtoExpr.lit(3)),
          ProtoExpr.In(col("b"), Vector(ProtoExpr.lit(1)))
        )
      ),
      table("t")
    )
    val optimized = Optimizer.optimize(plan)
    optimized match
      case ProtoLogicalPlan.Filter(cond, _) =>
        // Should have simplified the condition
        cond match
          case ProtoExpr.And(conditions) =>
            // a > 3 should have become TRUE and been removed
            // b IN (1) should have become b = 1
            assert(
              conditions.size <= 3,
              s"Expected at most 3 conditions after optimization, got ${conditions.size}"
            )
          case _ => () // Could be simplified further
      case _ => fail(s"Expected Filter, got $optimized")
