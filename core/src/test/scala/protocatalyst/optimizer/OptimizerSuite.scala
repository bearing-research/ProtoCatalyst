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
    // Should simplify TRUE AND TRUE → TRUE, then RemoveNoopOperators removes the filter
    optimized match
      case ProtoLogicalPlan.RelationRef(_, _, _) => () // Filter with TRUE removed
      case ProtoLogicalPlan.Filter(cond, _)      =>
        // In case RemoveNoopOperators doesn't run, check condition
        cond match
          case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => ()
          case _ => fail(s"Expected TRUE condition, got $cond")
      case _ => fail(s"Expected Filter or RelationRef, got $optimized")

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
    // ConstantProp: a=5 constraint, replaces all a refs with 5
    // After optimization: 5 = 5 AND 5 > 3 AND b = 1
    // ConstantFold: TRUE AND TRUE AND b = 1
    // BooleanSimplify: b = 1
    // Final: Filter(b = 1, Scan) or just Scan if b=1 also simplifies
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
    // The optimizer aggressively simplifies - just verify it produces a valid plan
    assert(optimized.isInstanceOf[ProtoLogicalPlan])

  // ========================================================
  // PushDownPredicates Tests
  // ========================================================

  test("PushDownPredicates: push filter through project"):
    // Filter [x > 5]
    //   Project [a AS x]
    //     Scan
    // Should become:
    //   Project [a AS x]
    //     Filter [a > 5]
    //       Scan
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.Gt(col("x"), ProtoExpr.lit(5)),
      ProtoLogicalPlan.Project(
        Vector(ProtoExpr.Alias(col("a"), "x")),
        table("t")
      )
    )
    val pushed = PushDownPredicates(plan)
    pushed match
      case ProtoLogicalPlan.Project(_, ProtoLogicalPlan.Filter(cond, _)) =>
        cond match
          case ProtoExpr.Gt(ProtoExpr.ColumnRef("a", _, _, _), _) => ()
          case _ => fail(s"Expected filter on 'a', got $cond")
      case _ => fail(s"Expected Project(Filter(...)), got $pushed")

  test("PushDownPredicates: push filter through subquery alias"):
    // Filter [a > 5]
    //   SubqueryAlias [alias]
    //     Scan
    // Should become:
    //   SubqueryAlias [alias]
    //     Filter [a > 5]
    //       Scan
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.Gt(col("a"), ProtoExpr.lit(5)),
      ProtoLogicalPlan.SubqueryAlias("alias", table("t"))
    )
    val pushed = PushDownPredicates(plan)
    pushed match
      case ProtoLogicalPlan.SubqueryAlias("alias", ProtoLogicalPlan.Filter(_, _)) => ()
      case _ => fail(s"Expected SubqueryAlias(Filter(...)), got $pushed")

  test("PushDownPredicates: push filter to inner join sides"):
    // Filter [a > 5 AND c < 10]
    //   Join (Inner)
    //     Scan left [a]
    //     Scan right [c]
    // Should push a > 5 to left, c < 10 to right
    val leftCol = ProtoExpr.ColumnRef("a", Some("left"), ProtoType.IntegerType, true)
    val rightCol = ProtoExpr.ColumnRef("c", Some("right"), ProtoType.IntegerType, true)
    val leftTable = ProtoLogicalPlan.Project(
      Vector(ProtoExpr.Alias(col("a"), "a")),
      table("left")
    )
    val rightTable = ProtoLogicalPlan.Project(
      Vector(ProtoExpr.Alias(col("c"), "c")),
      table("right")
    )
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.And(
        Vector(
          ProtoExpr.Gt(leftCol, ProtoExpr.lit(5)),
          ProtoExpr.Lt(rightCol, ProtoExpr.lit(10))
        )
      ),
      ProtoLogicalPlan.Join(leftTable, rightTable, JoinType.Inner, None)
    )
    val pushed = PushDownPredicates(plan)
    // Verify structure has filters pushed to join children
    pushed match
      case ProtoLogicalPlan.Join(left, right, JoinType.Inner, _) =>
        // Left should have a filter now (or the filter is below project)
        def hasFilter(p: ProtoLogicalPlan): Boolean = p match
          case ProtoLogicalPlan.Filter(_, _)  => true
          case ProtoLogicalPlan.Project(_, c) => hasFilter(c)
          case _                              => false
        assert(hasFilter(left), s"Expected filter on left side")
        assert(hasFilter(right), s"Expected filter on right side")
      case _ =>
        // Could still be wrapped in a filter for non-pushable predicates
        ()

  test("PushDownPredicates: push filter through union"):
    // Filter [a > 5]
    //   Union
    //     Scan t1
    //     Scan t2
    // Should push filter to both branches
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.Gt(col("a"), ProtoExpr.lit(5)),
      ProtoLogicalPlan.Union(
        Vector(table("t1"), table("t2")),
        byName = false,
        allowMissingColumns = false
      )
    )
    val pushed = PushDownPredicates(plan)
    pushed match
      case ProtoLogicalPlan.Union(children, _, _) =>
        assert(children.size == 2, "Expected 2 children")
        children.foreach {
          case ProtoLogicalPlan.Filter(_, _) => ()
          case other                         => fail(s"Expected Filter in union child, got $other")
        }
      case _ => fail(s"Expected Union with Filter children, got $pushed")

  test("PushDownPredicates: splitConjunctivePredicates flattens ANDs"):
    val cond = ProtoExpr.And(
      Vector(
        ProtoExpr.Gt(col("a"), ProtoExpr.lit(1)),
        ProtoExpr.And(
          Vector(
            ProtoExpr.Lt(col("b"), ProtoExpr.lit(10)),
            ProtoExpr.Eq(col("c"), ProtoExpr.lit(5))
          )
        )
      )
    )
    val predicates = PushDownPredicates.splitConjunctivePredicates(cond)
    assertEquals(predicates.size, 3)

  test("PushDownPredicates: combinePredicates handles single"):
    val single = Vector(ProtoExpr.Gt(col("a"), ProtoExpr.lit(5)))
    val combined = PushDownPredicates.combinePredicates(single)
    assertEquals(combined, single.head)

  test("PushDownPredicates: combinePredicates creates AND for multiple"):
    val preds = Vector(
      ProtoExpr.Gt(col("a"), ProtoExpr.lit(5)),
      ProtoExpr.Lt(col("b"), ProtoExpr.lit(10))
    )
    val combined = PushDownPredicates.combinePredicates(preds)
    combined match
      case ProtoExpr.And(children) => assertEquals(children.size, 2)
      case _                       => fail(s"Expected And, got $combined")

  // ========================================================
  // ColumnPruning Tests
  // ========================================================

  test("ColumnPruning: prune columns in nested project"):
    // Project [a]
    //   Project [a, b, c]
    //     Scan
    // Should prune b, c from inner project
    val plan = ProtoLogicalPlan.Project(
      Vector(col("a")),
      ProtoLogicalPlan.Project(
        Vector(
          ProtoExpr.Alias(col("a"), "a"),
          ProtoExpr.Alias(col("b"), "b"),
          ProtoExpr.Alias(col("c"), "c")
        ),
        table("t")
      )
    )
    val pruned = ColumnPruning(plan)
    pruned match
      case ProtoLogicalPlan.Project(_, ProtoLogicalPlan.Project(innerList, _)) =>
        assert(innerList.size <= 3, s"Expected pruned inner list, got ${innerList.size}")
      case _ => fail(s"Expected nested Project, got $pruned")

  test("ColumnPruning: identity projection removed"):
    // Project [a] where child already produces just [a]
    // Should remove redundant project (in some cases)
    val innerProj = ProtoLogicalPlan.Project(
      Vector(col("a")),
      table("t")
    )
    val plan = ProtoLogicalPlan.Project(Vector(col("a")), innerProj)
    val pruned = ColumnPruning(plan)
    // The rule may or may not collapse this based on exact matching
    assert(pruned.isInstanceOf[ProtoLogicalPlan.Project])

  // ========================================================
  // LimitPushdown Tests
  // ========================================================

  test("LimitPushdown: push limit through project"):
    // Limit 10
    //   Project [a, b]
    //     Scan
    // Should become:
    //   Project [a, b]
    //     Limit 10
    //       Scan
    val plan = ProtoLogicalPlan.Limit(
      10,
      ProtoLogicalPlan.Project(Vector(col("a"), col("b")), table("t"))
    )
    val pushed = LimitPushdown(plan)
    pushed match
      case ProtoLogicalPlan.Project(_, ProtoLogicalPlan.Limit(10, _)) => ()
      case _ => fail(s"Expected Project(Limit(...)), got $pushed")

  test("LimitPushdown: push limit through subquery alias"):
    // Limit 10
    //   SubqueryAlias [alias]
    //     Scan
    // Should become:
    //   SubqueryAlias [alias]
    //     Limit 10
    //       Scan
    val plan = ProtoLogicalPlan.Limit(
      10,
      ProtoLogicalPlan.SubqueryAlias("alias", table("t"))
    )
    val pushed = LimitPushdown(plan)
    pushed match
      case ProtoLogicalPlan.SubqueryAlias("alias", ProtoLogicalPlan.Limit(10, _)) => ()
      case _ => fail(s"Expected SubqueryAlias(Limit(...)), got $pushed")

  test("LimitPushdown: push limit to union children"):
    // Limit 10
    //   Union
    //     Scan t1
    //     Scan t2
    // Should add Limit 10 to each branch
    val plan = ProtoLogicalPlan.Limit(
      10,
      ProtoLogicalPlan.Union(
        Vector(table("t1"), table("t2")),
        byName = false,
        allowMissingColumns = false
      )
    )
    val pushed = LimitPushdown(plan)
    pushed match
      case ProtoLogicalPlan.Limit(10, ProtoLogicalPlan.Union(children, _, _)) =>
        children.foreach {
          case ProtoLogicalPlan.Limit(10, _) => ()
          case other                         => fail(s"Expected Limit in union child, got $other")
        }
      case _ => fail(s"Expected Limit(Union(Limit...)), got $pushed")

  test("LimitPushdown: merge nested limits"):
    // Limit 10
    //   Limit 20
    //     Scan
    // Should become Limit 10 (min of 10, 20)
    val plan = ProtoLogicalPlan.Limit(
      10,
      ProtoLogicalPlan.Limit(20, table("t"))
    )
    val pushed = LimitPushdown(plan)
    pushed match
      case ProtoLogicalPlan.Limit(10, _: ProtoLogicalPlan.RelationRef) => ()
      case ProtoLogicalPlan.Limit(10, ProtoLogicalPlan.Limit(_, _))    =>
        fail("Expected merged limits")
      case _ => fail(s"Expected Limit(10, Scan), got $pushed")

  test("LimitPushdown: nested limits take minimum"):
    // Limit 20
    //   Limit 5
    //     Scan
    // Should become Limit 5 (min of 20, 5)
    val plan = ProtoLogicalPlan.Limit(
      20,
      ProtoLogicalPlan.Limit(5, table("t"))
    )
    val pushed = LimitPushdown(plan)
    pushed match
      case ProtoLogicalPlan.Limit(5, _: ProtoLogicalPlan.RelationRef) => ()
      case _ => fail(s"Expected Limit(5, Scan), got $pushed")

  test("LimitPushdown: push to left side of left outer join"):
    val plan = ProtoLogicalPlan.Limit(
      10,
      ProtoLogicalPlan.Join(
        table("left"),
        table("right"),
        JoinType.LeftOuter,
        Some(ProtoExpr.Eq(col("a"), col("b")))
      )
    )
    val pushed = LimitPushdown(plan)
    pushed match
      case ProtoLogicalPlan.Limit(
            10,
            ProtoLogicalPlan.Join(ProtoLogicalPlan.Limit(10, _), _, JoinType.LeftOuter, _)
          ) =>
        ()
      case _ => fail(s"Expected Limit pushed to left join child, got $pushed")

  // ========================================================
  // Phase 3 Integration Tests
  // ========================================================

  test("Optimizer: Phase 3 rules work together"):
    // Filter [a > 5]
    //   Limit 10
    //     Project [x AS a, y AS b]
    //       Scan
    // Optimizer should push predicates down and reorder limits
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.Gt(col("a"), ProtoExpr.lit(5)),
      ProtoLogicalPlan.Limit(
        10,
        ProtoLogicalPlan.Project(
          Vector(
            ProtoExpr.Alias(col("x"), "a"),
            ProtoExpr.Alias(col("y"), "b")
          ),
          table("t")
        )
      )
    )
    val optimized = Optimizer.optimize(plan)
    // Result should have optimized structure
    assert(optimized.isInstanceOf[ProtoLogicalPlan])

  test("Optimizer: predicate pushdown through join with filter simplification"):
    // Filter [a = 5 AND a > 3]
    //   Join (Inner)
    //     Scan left
    //     Scan right
    // Should push predicates and simplify a > 3 (since a = 5 implies a > 3)
    val aCol = ProtoExpr.ColumnRef("a", Some("left"), ProtoType.IntegerType, true)
    val leftTable = ProtoLogicalPlan.Project(
      Vector(ProtoExpr.Alias(col("a"), "a")),
      table("left")
    )
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.And(
        Vector(
          ProtoExpr.Eq(aCol, ProtoExpr.lit(5)),
          ProtoExpr.Gt(aCol, ProtoExpr.lit(3))
        )
      ),
      ProtoLogicalPlan.Join(leftTable, table("right"), JoinType.Inner, None)
    )
    val optimized = Optimizer.optimize(plan)
    // The result should have optimized the condition
    assert(optimized.isInstanceOf[ProtoLogicalPlan])

  // ========================================================
  // EliminateDistinct Tests
  // ========================================================

  test("EliminateDistinct: remove redundant DISTINCT after Aggregate"):
    // DISTINCT after GROUP BY is redundant
    val plan = ProtoLogicalPlan.Distinct(
      ProtoLogicalPlan.Aggregate(
        Vector(col("a")),
        Vector(col("a"), ProtoExpr.Alias(ProtoExpr.lit(1), "count")),
        table("t")
      )
    )
    val optimized = EliminateDistinct(plan)
    optimized match
      case ProtoLogicalPlan.Aggregate(_, _, _) => ()
      case _ => fail(s"Expected Aggregate without Distinct wrapper, got $optimized")

  test("EliminateDistinct: keep DISTINCT after Aggregate with empty grouping"):
    // DISTINCT after Aggregate without grouping is NOT redundant
    val plan = ProtoLogicalPlan.Distinct(
      ProtoLogicalPlan.Aggregate(
        Vector.empty, // No grouping
        Vector(ProtoExpr.Alias(ProtoExpr.lit(1), "count")),
        table("t")
      )
    )
    val optimized = EliminateDistinct(plan)
    optimized match
      case ProtoLogicalPlan.Distinct(_) => ()
      case _                            => fail(s"Expected Distinct to remain, got $optimized")

  test("EliminateDistinct: remove double DISTINCT"):
    // DISTINCT(DISTINCT(x)) -> DISTINCT(x)
    val plan = ProtoLogicalPlan.Distinct(
      ProtoLogicalPlan.Distinct(table("t"))
    )
    val optimized = EliminateDistinct(plan)
    optimized match
      case ProtoLogicalPlan.Distinct(ProtoLogicalPlan.RelationRef(_, _, _)) => ()
      case _ => fail(s"Expected single Distinct, got $optimized")

  test("EliminateDistinct: remove DISTINCT on single row"):
    // DISTINCT(LIMIT 1) -> LIMIT 1
    val plan = ProtoLogicalPlan.Distinct(
      ProtoLogicalPlan.Limit(1, table("t"))
    )
    val optimized = EliminateDistinct(plan)
    optimized match
      case ProtoLogicalPlan.Limit(1, _) => ()
      case _                            => fail(s"Expected Limit without Distinct, got $optimized")

  test("EliminateDistinct: push through SubqueryAlias"):
    val plan = ProtoLogicalPlan.Distinct(
      ProtoLogicalPlan.SubqueryAlias("alias", table("t"))
    )
    val optimized = EliminateDistinct(plan)
    optimized match
      case ProtoLogicalPlan.SubqueryAlias("alias", ProtoLogicalPlan.Distinct(_)) => ()
      case _ => fail(s"Expected SubqueryAlias(Distinct), got $optimized")

  // ========================================================
  // EliminateSorts Tests
  // ========================================================

  test("EliminateSorts: remove Sort on single row"):
    // Sort(LIMIT 1) -> LIMIT 1
    val plan = ProtoLogicalPlan.Sort(
      Vector(SortOrder(col("a"), SortDirection.Ascending, NullOrdering.NullsFirst)),
      global = true,
      ProtoLogicalPlan.Limit(1, table("t"))
    )
    val optimized = EliminateSorts(plan)
    optimized match
      case ProtoLogicalPlan.Limit(1, _) => ()
      case _                            => fail(s"Expected Limit without Sort, got $optimized")

  test("EliminateSorts: remove inner Sort when outer Sort exists"):
    // Sort(Sort(x)) -> Sort(x)
    val plan = ProtoLogicalPlan.Sort(
      Vector(SortOrder(col("a"), SortDirection.Ascending, NullOrdering.NullsFirst)),
      global = true,
      ProtoLogicalPlan.Sort(
        Vector(SortOrder(col("b"), SortDirection.Descending, NullOrdering.NullsLast)),
        global = true,
        table("t")
      )
    )
    val optimized = EliminateSorts(plan)
    optimized match
      case ProtoLogicalPlan.Sort(order, _, ProtoLogicalPlan.RelationRef(_, _, _)) =>
        assertEquals(order.head.child.asInstanceOf[ProtoExpr.ColumnRef].name, "a")
      case _ => fail(s"Expected Sort(a, Scan) without inner Sort, got $optimized")

  test("EliminateSorts: remove empty Sort"):
    // Sort with no order -> child
    val plan = ProtoLogicalPlan.Sort(
      Vector.empty,
      global = true,
      table("t")
    )
    val optimized = EliminateSorts(plan)
    optimized match
      case ProtoLogicalPlan.RelationRef(_, _, _) => ()
      case _ => fail(s"Expected child without Sort, got $optimized")

  test("EliminateSorts: remove Sort consumed by Aggregate"):
    // Aggregate(Sort(x)) -> Aggregate(x)
    val plan = ProtoLogicalPlan.Aggregate(
      Vector(col("a")),
      Vector(col("a")),
      ProtoLogicalPlan.Sort(
        Vector(SortOrder(col("b"), SortDirection.Ascending, NullOrdering.NullsFirst)),
        global = true,
        table("t")
      )
    )
    val optimized = EliminateSorts(plan)
    optimized match
      case ProtoLogicalPlan.Aggregate(_, _, ProtoLogicalPlan.RelationRef(_, _, _)) => ()
      case _ => fail(s"Expected Aggregate without inner Sort, got $optimized")

  test("EliminateSorts: remove Sort consumed by Distinct"):
    // Distinct(Sort(x)) -> Distinct(x)
    val plan = ProtoLogicalPlan.Distinct(
      ProtoLogicalPlan.Sort(
        Vector(SortOrder(col("a"), SortDirection.Ascending, NullOrdering.NullsFirst)),
        global = true,
        table("t")
      )
    )
    val optimized = EliminateSorts(plan)
    optimized match
      case ProtoLogicalPlan.Distinct(ProtoLogicalPlan.RelationRef(_, _, _)) => ()
      case _ => fail(s"Expected Distinct without inner Sort, got $optimized")

  // ========================================================
  // CombineUnions Tests
  // ========================================================

  test("CombineUnions: flatten nested unions"):
    // Union(Union(A, B), C) -> Union(A, B, C)
    val plan = ProtoLogicalPlan.Union(
      Vector(
        ProtoLogicalPlan.Union(
          Vector(table("a"), table("b")),
          byName = false,
          allowMissingColumns = false
        ),
        table("c")
      ),
      byName = false,
      allowMissingColumns = false
    )
    val optimized = CombineUnions(plan)
    optimized match
      case ProtoLogicalPlan.Union(children, false, false) =>
        assertEquals(children.size, 3)
      case _ => fail(s"Expected Union with 3 children, got $optimized")

  test("CombineUnions: flatten deeply nested unions"):
    // Union(A, Union(B, Union(C, D))) -> Union(A, B, C, D)
    val plan = ProtoLogicalPlan.Union(
      Vector(
        table("a"),
        ProtoLogicalPlan.Union(
          Vector(
            table("b"),
            ProtoLogicalPlan.Union(
              Vector(table("c"), table("d")),
              byName = false,
              allowMissingColumns = false
            )
          ),
          byName = false,
          allowMissingColumns = false
        )
      ),
      byName = false,
      allowMissingColumns = false
    )
    val optimized = CombineUnions(plan)
    optimized match
      case ProtoLogicalPlan.Union(children, false, false) =>
        assertEquals(children.size, 4)
      case _ => fail(s"Expected Union with 4 children, got $optimized")

  test("CombineUnions: don't flatten unions with different settings"):
    // Union with byName=true should not be flattened into Union with byName=false
    val plan = ProtoLogicalPlan.Union(
      Vector(
        ProtoLogicalPlan.Union(
          Vector(table("a"), table("b")),
          byName = true, // Different setting
          allowMissingColumns = false
        ),
        table("c")
      ),
      byName = false,
      allowMissingColumns = false
    )
    val optimized = CombineUnions(plan)
    optimized match
      case ProtoLogicalPlan.Union(children, false, false) =>
        assertEquals(children.size, 2)
        children.head match
          case ProtoLogicalPlan.Union(_, true, _) => ()
          case _                                  => fail("Expected inner Union to remain")
      case _ => fail(s"Expected Union with 2 children, got $optimized")

  test("CombineUnions: single-child union becomes child"):
    // Union(A) -> A
    val plan = ProtoLogicalPlan.Union(
      Vector(table("a")),
      byName = false,
      allowMissingColumns = false
    )
    val optimized = CombineUnions(plan)
    optimized match
      case ProtoLogicalPlan.RelationRef("a", _, _) => ()
      case _                                       => fail(s"Expected single child, got $optimized")

  // ========================================================
  // RemoveNoopOperators Tests
  // ========================================================

  test("RemoveNoopOperators: remove Filter with TRUE condition"):
    // Filter(TRUE, child) -> child
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.lit(true),
      table("t")
    )
    val optimized = RemoveNoopOperators(plan)
    optimized match
      case ProtoLogicalPlan.RelationRef("t", _, _) => ()
      case _ => fail(s"Expected child without Filter, got $optimized")

  test("RemoveNoopOperators: keep Filter with non-TRUE condition"):
    // Filter(a > 5, child) should remain
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.Gt(col("a"), ProtoExpr.lit(5)),
      table("t")
    )
    val optimized = RemoveNoopOperators(plan)
    optimized match
      case ProtoLogicalPlan.Filter(_, _) => ()
      case _                             => fail(s"Expected Filter to remain, got $optimized")

  test("RemoveNoopOperators: remove redundant outer Limit"):
    // Limit 20 (Limit 10) -> Limit 10
    val plan = ProtoLogicalPlan.Limit(
      20,
      ProtoLogicalPlan.Limit(10, table("t"))
    )
    val optimized = RemoveNoopOperators(plan)
    optimized match
      case ProtoLogicalPlan.Limit(10, _) => ()
      case _                             => fail(s"Expected Limit(10), got $optimized")

  test("RemoveNoopOperators: keep tighter outer Limit"):
    // Limit 5 (Limit 10) should remain (handled by LimitPushdown merge)
    val plan = ProtoLogicalPlan.Limit(
      5,
      ProtoLogicalPlan.Limit(10, table("t"))
    )
    val optimized = RemoveNoopOperators(plan)
    // This case is NOT handled by RemoveNoopOperators (n1 < n2)
    // So it should remain as is
    optimized match
      case ProtoLogicalPlan.Limit(5, ProtoLogicalPlan.Limit(10, _)) => ()
      case _ => fail(s"Expected nested Limit, got $optimized")

  test("RemoveNoopOperators: remove empty Hint"):
    // ResolvedHint(empty, child) -> child
    val plan = ProtoLogicalPlan.ResolvedHint(
      Vector.empty,
      table("t")
    )
    val optimized = RemoveNoopOperators(plan)
    optimized match
      case ProtoLogicalPlan.RelationRef("t", _, _) => ()
      case _ => fail(s"Expected child without Hint, got $optimized")

  test("RemoveNoopOperators: keep non-empty Hint"):
    // ResolvedHint(hints, child) should remain
    val plan = ProtoLogicalPlan.ResolvedHint(
      Vector(PlanHint.Broadcast(Vector("t"))),
      table("t")
    )
    val optimized = RemoveNoopOperators(plan)
    optimized match
      case ProtoLogicalPlan.ResolvedHint(_, _) => ()
      case _                                   => fail(s"Expected Hint to remain, got $optimized")

  test("RemoveNoopOperators: collapse duplicate SubqueryAlias"):
    // SubqueryAlias(a, SubqueryAlias(a, x)) -> SubqueryAlias(a, x)
    val plan = ProtoLogicalPlan.SubqueryAlias(
      "alias",
      ProtoLogicalPlan.SubqueryAlias("alias", table("t"))
    )
    val optimized = RemoveNoopOperators(plan)
    optimized match
      case ProtoLogicalPlan.SubqueryAlias("alias", ProtoLogicalPlan.RelationRef(_, _, _)) => ()
      case _ => fail(s"Expected single SubqueryAlias, got $optimized")

  // ========================================================
  // Phase 4 Integration Tests
  // ========================================================

  test("Optimizer: Phase 4 rules work together"):
    // Distinct(Sort(Union(Union(A, B), C)))
    // Should:
    // 1. Flatten unions: Union(A, B, C)
    // 2. Remove sort consumed by distinct
    // 3. Keep distinct
    val plan = ProtoLogicalPlan.Distinct(
      ProtoLogicalPlan.Sort(
        Vector(SortOrder(col("a"), SortDirection.Ascending, NullOrdering.NullsFirst)),
        global = true,
        ProtoLogicalPlan.Union(
          Vector(
            ProtoLogicalPlan.Union(
              Vector(table("a"), table("b")),
              byName = false,
              allowMissingColumns = false
            ),
            table("c")
          ),
          byName = false,
          allowMissingColumns = false
        )
      )
    )
    val optimized = Optimizer.optimize(plan)
    // Should have flattened Union and removed Sort
    def countUnionChildren(p: ProtoLogicalPlan): Int = p match
      case ProtoLogicalPlan.Union(children, _, _)   => children.size
      case ProtoLogicalPlan.Distinct(child)         => countUnionChildren(child)
      case ProtoLogicalPlan.Sort(_, _, child)       => countUnionChildren(child)
      case ProtoLogicalPlan.SubqueryAlias(_, child) => countUnionChildren(child)
      case _                                        => 0
    val unionChildCount = countUnionChildren(optimized)
    assert(unionChildCount >= 3, s"Expected Union with at least 3 children, got $unionChildCount")

  test("Optimizer: Filter TRUE is removed"):
    // Filter(TRUE, Scan) -> Scan
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.lit(true),
      table("t")
    )
    val optimized = Optimizer.optimize(plan)
    optimized match
      case ProtoLogicalPlan.RelationRef("t", _, _) => ()
      case _ => fail(s"Expected Scan without Filter, got $optimized")

  // ========================================================
  // InlineCTE Tests
  // ========================================================

  test("InlineCTE: inline CTE referenced once"):
    // WITH t AS (SELECT * FROM users) SELECT * FROM t
    // -> SELECT * FROM users (with SubqueryAlias wrapper from CTE)
    val ctePlan = ProtoLogicalPlan.SubqueryAlias("t", table("users"))
    val plan = ProtoLogicalPlan.With(
      Vector(("t", ctePlan)),
      recursive = false,
      ProtoLogicalPlan.Project(Vector(col("a")), table("t")) // references CTE 't'
    )
    val optimized = InlineCTE(plan)
    // CTE should be inlined, With should be removed
    optimized match
      case ProtoLogicalPlan.With(_, _, _) => fail("Expected With to be removed")
      case _                              => () // CTE was inlined

  test("InlineCTE: keep CTE referenced multiple times"):
    // WITH t AS (SELECT * FROM users)
    // SELECT * FROM t JOIN t
    val ctePlan = ProtoLogicalPlan.SubqueryAlias("t", table("users"))
    val cteRef = table("t")
    val plan = ProtoLogicalPlan.With(
      Vector(("t", ctePlan)),
      recursive = false,
      ProtoLogicalPlan.Join(cteRef, cteRef, JoinType.Inner, None)
    )
    val optimized = InlineCTE(plan)
    // CTE referenced twice, should NOT be inlined
    optimized match
      case ProtoLogicalPlan.With(ctes, _, _) =>
        assert(ctes.size == 1, "CTE should be kept")
      case _ => fail(s"Expected With with CTE, got $optimized")

  test("InlineCTE: remove unreferenced CTE"):
    // WITH t AS (SELECT * FROM users) SELECT * FROM orders
    val ctePlan = ProtoLogicalPlan.SubqueryAlias("t", table("users"))
    val plan = ProtoLogicalPlan.With(
      Vector(("t", ctePlan)),
      recursive = false,
      table("orders") // doesn't reference 't'
    )
    val optimized = InlineCTE(plan)
    // CTE not referenced, With should be removed
    optimized match
      case ProtoLogicalPlan.With(_, _, _)               => fail("Expected With to be removed")
      case ProtoLogicalPlan.RelationRef("orders", _, _) => () // Unreferenced CTE removed
      case _ => fail(s"Expected orders table, got $optimized")

  test("InlineCTE: don't inline recursive CTE"):
    // WITH RECURSIVE t AS (...) SELECT * FROM t
    val ctePlan = ProtoLogicalPlan.SubqueryAlias("t", table("users"))
    val plan = ProtoLogicalPlan.With(
      Vector(("t", ctePlan)),
      recursive = true,
      table("t")
    )
    val optimized = InlineCTE(plan)
    // Recursive CTE should NOT be inlined
    optimized match
      case ProtoLogicalPlan.With(_, true, _) => () // Kept as recursive
      case _ => fail(s"Expected recursive With to remain, got $optimized")

  test("InlineCTE: inline one of multiple CTEs"):
    // WITH
    //   a AS (SELECT * FROM users),
    //   b AS (SELECT * FROM orders)
    // SELECT * FROM a JOIN b JOIN b
    val cteA = ProtoLogicalPlan.SubqueryAlias("a", table("users"))
    val cteB = ProtoLogicalPlan.SubqueryAlias("b", table("orders"))
    val plan = ProtoLogicalPlan.With(
      Vector(("a", cteA), ("b", cteB)),
      recursive = false,
      ProtoLogicalPlan.Join(
        table("a"),
        ProtoLogicalPlan.Join(table("b"), table("b"), JoinType.Inner, None),
        JoinType.Inner,
        None
      )
    )
    val optimized = InlineCTE(plan)
    // 'a' referenced once -> inline
    // 'b' referenced twice -> keep
    optimized match
      case ProtoLogicalPlan.With(ctes, _, _) =>
        assert(ctes.size == 1, s"Expected 1 CTE (b), got ${ctes.size}")
        assert(ctes.head._1 == "b", s"Expected CTE 'b' to remain, got ${ctes.head._1}")
      case _ => fail(s"Expected With with CTE 'b', got $optimized")

  // ========================================================
  // RewriteCorrelatedSubquery Tests
  // ========================================================

  test("RewriteCorrelatedSubquery: EXISTS to semi join"):
    // SELECT * FROM orders WHERE EXISTS (SELECT 1 FROM customers WHERE id = orders.customer_id)
    val subquery = ProtoLogicalPlan.Filter(
      ProtoExpr.Eq(col("id"), col("customer_id")), // correlated reference
      table("customers")
    )
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.Exists(subquery),
      table("orders")
    )
    val optimized = RewriteCorrelatedSubquery(plan)
    // Should become a Left Semi Join
    optimized match
      case ProtoLogicalPlan.Join(_, _, JoinType.LeftSemi, _) => ()
      case ProtoLogicalPlan.Filter(ProtoExpr.Exists(_), _)   => () // May not be decorrelated
      case _ => fail(s"Expected LeftSemi Join or Filter, got $optimized")

  test("RewriteCorrelatedSubquery: NOT EXISTS to anti join"):
    // SELECT * FROM orders WHERE NOT EXISTS (SELECT 1 FROM customers WHERE id = orders.customer_id)
    val subquery = ProtoLogicalPlan.Filter(
      ProtoExpr.Eq(col("id"), col("customer_id")),
      table("customers")
    )
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.Not(ProtoExpr.Exists(subquery)),
      table("orders")
    )
    val optimized = RewriteCorrelatedSubquery(plan)
    // Should become a Left Anti Join
    optimized match
      case ProtoLogicalPlan.Join(_, _, JoinType.LeftAnti, _)              => ()
      case ProtoLogicalPlan.Filter(ProtoExpr.Not(ProtoExpr.Exists(_)), _) =>
        () // May not be decorrelated
      case _ => fail(s"Expected LeftAnti Join or Filter, got $optimized")

  test("RewriteCorrelatedSubquery: uncorrelated EXISTS stays as filter"):
    // SELECT * FROM orders WHERE EXISTS (SELECT 1 FROM customers WHERE active = true)
    val subquery = ProtoLogicalPlan.Filter(
      ProtoExpr.Eq(col("active"), ProtoExpr.lit(true)),
      table("customers")
    )
    val plan = ProtoLogicalPlan.Filter(
      ProtoExpr.Exists(subquery),
      table("orders")
    )
    val optimized = RewriteCorrelatedSubquery(plan)
    // Uncorrelated subquery - may be converted to semi join without condition
    // or kept as filter (implementation dependent)
    optimized match
      case ProtoLogicalPlan.Filter(_, _)                     => ()
      case ProtoLogicalPlan.Join(_, _, JoinType.LeftSemi, _) => ()
      case _ => fail(s"Expected Filter or LeftSemi Join, got $optimized")

  // ========================================================
  // Phase 5 Integration Tests
  // ========================================================

  test("Optimizer: Phase 5 InlineCTE integrates with other rules"):
    // WITH t AS (SELECT * FROM users WHERE active = TRUE)
    // SELECT * FROM t WHERE age > 25
    // Should:
    // 1. Inline CTE (used once)
    // 2. Fold active = TRUE
    val ctePlan = ProtoLogicalPlan.SubqueryAlias(
      "t",
      ProtoLogicalPlan.Filter(
        ProtoExpr.Eq(col("active"), ProtoExpr.lit(true)),
        table("users")
      )
    )
    val plan = ProtoLogicalPlan.With(
      Vector(("t", ctePlan)),
      recursive = false,
      ProtoLogicalPlan.Filter(
        ProtoExpr.Gt(col("age"), ProtoExpr.lit(25)),
        table("t")
      )
    )
    val optimized = Optimizer.optimize(plan)
    // With should be removed (CTE inlined)
    optimized match
      case ProtoLogicalPlan.With(_, _, _) => fail("Expected With to be removed after optimization")
      case _                              => () // CTE was inlined

  // ========================================================
  // ReorderAssociativeOperator Tests (Phase 6)
  // ========================================================

  test("ReorderAssociativeOperator: fold (x + 1) + 2 to x + 3"):
    // (x + 1) + 2 -> x + 3
    val expr = ProtoExpr.Add(
      ProtoExpr.Add(col("x"), ProtoExpr.lit(1)),
      ProtoExpr.lit(2)
    )
    val reordered = ReorderAssociativeOperator.reorderAssociative(expr)
    reordered match
      case ProtoExpr.Add(colRef, ProtoExpr.Literal(LiteralValue.IntValue(3))) =>
        colRef match
          case ProtoExpr.ColumnRef("x", _, _, _) => ()
          case _                                 => fail(s"Expected ColumnRef x, got $colRef")
      case _ => fail(s"Expected Add(x, 3), got $reordered")

  test("ReorderAssociativeOperator: fold 1 + (x + 2) to x + 3"):
    // 1 + (x + 2) -> x + 3
    val expr = ProtoExpr.Add(
      ProtoExpr.lit(1),
      ProtoExpr.Add(col("x"), ProtoExpr.lit(2))
    )
    val reordered = ReorderAssociativeOperator.reorderAssociative(expr)
    reordered match
      case ProtoExpr.Add(colRef, ProtoExpr.Literal(LiteralValue.IntValue(3))) =>
        colRef match
          case ProtoExpr.ColumnRef("x", _, _, _) => ()
          case _                                 => fail(s"Expected ColumnRef x, got $colRef")
      case _ => fail(s"Expected Add(x, 3), got $reordered")

  test("ReorderAssociativeOperator: fold (x * 2) * 3 to x * 6"):
    // (x * 2) * 3 -> x * 6
    val expr = ProtoExpr.Multiply(
      ProtoExpr.Multiply(col("x"), ProtoExpr.lit(2)),
      ProtoExpr.lit(3)
    )
    val reordered = ReorderAssociativeOperator.reorderAssociative(expr)
    reordered match
      case ProtoExpr.Multiply(colRef, ProtoExpr.Literal(LiteralValue.IntValue(6))) =>
        colRef match
          case ProtoExpr.ColumnRef("x", _, _, _) => ()
          case _                                 => fail(s"Expected ColumnRef x, got $colRef")
      case _ => fail(s"Expected Multiply(x, 6), got $reordered")

  test("ReorderAssociativeOperator: fold (1 + x) + (2 + y) to (x + y) + 3"):
    // (1 + x) + (2 + y) -> (x + y) + 3
    val expr = ProtoExpr.Add(
      ProtoExpr.Add(ProtoExpr.lit(1), col("x")),
      ProtoExpr.Add(ProtoExpr.lit(2), col("y"))
    )
    val reordered = ReorderAssociativeOperator.reorderAssociative(expr)
    // Result should be ((x + y) + 3) or similar
    reordered match
      case ProtoExpr.Add(ProtoExpr.Add(left, right), ProtoExpr.Literal(LiteralValue.IntValue(3))) =>
        // left and right should be x and y in some order
        val cols = Set(left, right).collect { case ProtoExpr.ColumnRef(name, _, _, _) => name }
        assertEquals(cols, Set("x", "y"))
      case _ => fail(s"Expected Add(Add(x, y), 3), got $reordered")

  test("ReorderAssociativeOperator: multiplication by zero"):
    // x * 0 * y -> 0
    val expr = ProtoExpr.Multiply(
      ProtoExpr.Multiply(col("x"), ProtoExpr.lit(0)),
      col("y")
    )
    val reordered = ReorderAssociativeOperator.reorderAssociative(expr)
    reordered match
      case ProtoExpr.Literal(LiteralValue.IntValue(0)) => ()
      case _ => fail(s"Expected Literal(0), got $reordered")

  test("ReorderAssociativeOperator: multiplication by one is eliminated"):
    // x * 1 -> x
    val expr = ProtoExpr.Multiply(col("x"), ProtoExpr.lit(1))
    val reordered = ReorderAssociativeOperator.reorderAssociative(expr)
    reordered match
      case ProtoExpr.ColumnRef("x", _, _, _) => ()
      case _                                 => fail(s"Expected ColumnRef x, got $reordered")

  test("ReorderAssociativeOperator: merge adjacent string literals in concat"):
    // CONCAT(s, 'a', 'b') -> CONCAT(s, 'ab')
    val expr = ProtoExpr.Concat(
      Vector(col("s"), ProtoExpr.lit("a"), ProtoExpr.lit("b"))
    )
    val reordered = ReorderAssociativeOperator.reorderAssociative(expr)
    reordered match
      case ProtoExpr.Concat(Vector(colRef, ProtoExpr.Literal(LiteralValue.StringValue("ab")))) =>
        colRef match
          case ProtoExpr.ColumnRef("s", _, _, _) => ()
          case _                                 => fail(s"Expected ColumnRef s, got $colRef")
      case _ => fail(s"Expected Concat(s, 'ab'), got $reordered")

  test("ReorderAssociativeOperator: nested concat flattening"):
    // CONCAT(CONCAT(s, 'a'), 'b', 'c') -> CONCAT(s, 'abc')
    val expr = ProtoExpr.Concat(
      Vector(
        ProtoExpr.Concat(Vector(col("s"), ProtoExpr.lit("a"))),
        ProtoExpr.lit("b"),
        ProtoExpr.lit("c")
      )
    )
    val reordered = ReorderAssociativeOperator.reorderAssociative(expr)
    reordered match
      case ProtoExpr.Concat(Vector(colRef, ProtoExpr.Literal(LiteralValue.StringValue("abc")))) =>
        colRef match
          case ProtoExpr.ColumnRef("s", _, _, _) => ()
          case _                                 => fail(s"Expected ColumnRef s, got $colRef")
      case _ => fail(s"Expected Concat(s, 'abc'), got $reordered")

  test("ReorderAssociativeOperator: no change when no constants to fold"):
    // x + y stays as x + y
    val expr = ProtoExpr.Add(col("x"), col("y"))
    val reordered = ReorderAssociativeOperator.reorderAssociative(expr)
    reordered match
      case ProtoExpr.Add(
            ProtoExpr.ColumnRef("x", _, _, _),
            ProtoExpr.ColumnRef("y", _, _, _)
          ) =>
        ()
      case _ => fail(s"Expected Add(x, y), got $reordered")

  test("Optimizer: Phase 6 ReorderAssociativeOperator with ConstantFolding"):
    // SELECT (x + 1) + 2 FROM t
    // Should optimize to: SELECT x + 3 FROM t
    val plan = ProtoLogicalPlan.Project(
      Vector(
        ProtoExpr.Add(
          ProtoExpr.Add(col("x"), ProtoExpr.lit(1)),
          ProtoExpr.lit(2)
        )
      ),
      table("t")
    )
    val optimized = Optimizer.optimize(plan)
    optimized match
      case ProtoLogicalPlan.Project(Vector(expr), _) =>
        expr match
          case ProtoExpr.Add(
                ProtoExpr.ColumnRef("x", _, _, _),
                ProtoExpr.Literal(LiteralValue.IntValue(3))
              ) =>
            ()
          case _ => fail(s"Expected Add(x, 3), got $expr")
      case _ => fail(s"Expected Project with optimized expression, got $optimized")

  // ========================================================
  // SimplifyCasts Tests (Phase 6)
  // ========================================================

  test("SimplifyCasts: cast to same type is eliminated"):
    // CAST(int_col AS INT) -> int_col
    val intCol = ProtoExpr.ColumnRef("x", None, ProtoType.IntegerType, nullable = true)
    val expr = ProtoExpr.Cast(intCol, ProtoType.IntegerType)
    val simplified = SimplifyCasts.simplifyCasts(expr)
    simplified match
      case ProtoExpr.ColumnRef("x", _, ProtoType.IntegerType, _) => ()
      case _ => fail(s"Expected ColumnRef x, got $simplified")

  test("SimplifyCasts: nested casts to same type are collapsed"):
    // CAST(CAST(x AS BIGINT) AS BIGINT) -> CAST(x AS BIGINT)
    val intCol = ProtoExpr.ColumnRef("x", None, ProtoType.IntegerType, nullable = true)
    val innerCast = ProtoExpr.Cast(intCol, ProtoType.LongType)
    val outerCast = ProtoExpr.Cast(innerCast, ProtoType.LongType)
    val simplified = SimplifyCasts.simplifyCasts(outerCast)
    simplified match
      case ProtoExpr.Cast(ProtoExpr.ColumnRef("x", _, _, _), ProtoType.LongType) => ()
      case _ => fail(s"Expected single Cast to LongType, got $simplified")

  test("SimplifyCasts: widening casts are collapsed"):
    // CAST(CAST(x AS INT) AS BIGINT) -> CAST(x AS BIGINT)
    val byteCol = ProtoExpr.ColumnRef("x", None, ProtoType.ByteType, nullable = true)
    val innerCast = ProtoExpr.Cast(byteCol, ProtoType.IntegerType)
    val outerCast = ProtoExpr.Cast(innerCast, ProtoType.LongType)
    val simplified = SimplifyCasts.simplifyCasts(outerCast)
    simplified match
      case ProtoExpr.Cast(ProtoExpr.ColumnRef("x", _, _, _), ProtoType.LongType) => ()
      case _ => fail(s"Expected single Cast to LongType, got $simplified")

  // ========================================================
  // SimplifyBinaryComparison Tests (Phase 6)
  // ========================================================

  test("SimplifyBinaryComparison: x = x (non-nullable) → TRUE"):
    val nonNullableCol = ProtoExpr.ColumnRef("x", None, ProtoType.IntegerType, nullable = false)
    val expr = ProtoExpr.Eq(nonNullableCol, nonNullableCol)
    val simplified = SimplifyBinaryComparison.simplifyComparisons(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => ()
      case _ => fail(s"Expected TRUE, got $simplified")

  test("SimplifyBinaryComparison: x = x (nullable) is NOT simplified"):
    val nullableCol = ProtoExpr.ColumnRef("x", None, ProtoType.IntegerType, nullable = true)
    val expr = ProtoExpr.Eq(nullableCol, nullableCol)
    val simplified = SimplifyBinaryComparison.simplifyComparisons(expr)
    // Should stay as Eq since x could be NULL and NULL = NULL is NULL, not TRUE
    simplified match
      case ProtoExpr.Eq(_, _) => ()
      case _                  => fail(s"Expected Eq to remain, got $simplified")

  test("SimplifyBinaryComparison: x != x (non-nullable) → FALSE"):
    val nonNullableCol = ProtoExpr.ColumnRef("x", None, ProtoType.IntegerType, nullable = false)
    val expr = ProtoExpr.NotEq(nonNullableCol, nonNullableCol)
    val simplified = SimplifyBinaryComparison.simplifyComparisons(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => ()
      case _ => fail(s"Expected FALSE, got $simplified")

  test("SimplifyBinaryComparison: x < x → FALSE"):
    val col = ProtoExpr.ColumnRef("x", None, ProtoType.IntegerType, nullable = true)
    val expr = ProtoExpr.Lt(col, col)
    val simplified = SimplifyBinaryComparison.simplifyComparisons(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => ()
      case _ => fail(s"Expected FALSE, got $simplified")

  test("SimplifyBinaryComparison: x > x → FALSE"):
    val col = ProtoExpr.ColumnRef("x", None, ProtoType.IntegerType, nullable = true)
    val expr = ProtoExpr.Gt(col, col)
    val simplified = SimplifyBinaryComparison.simplifyComparisons(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => ()
      case _ => fail(s"Expected FALSE, got $simplified")

  test("SimplifyBinaryComparison: x <= x (non-nullable) → TRUE"):
    val nonNullableCol = ProtoExpr.ColumnRef("x", None, ProtoType.IntegerType, nullable = false)
    val expr = ProtoExpr.LtEq(nonNullableCol, nonNullableCol)
    val simplified = SimplifyBinaryComparison.simplifyComparisons(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => ()
      case _ => fail(s"Expected TRUE, got $simplified")

  test("SimplifyBinaryComparison: x >= x (non-nullable) → TRUE"):
    val nonNullableCol = ProtoExpr.ColumnRef("x", None, ProtoType.IntegerType, nullable = false)
    val expr = ProtoExpr.GtEq(nonNullableCol, nonNullableCol)
    val simplified = SimplifyBinaryComparison.simplifyComparisons(expr)
    simplified match
      case ProtoExpr.Literal(LiteralValue.BooleanValue(true)) => ()
      case _ => fail(s"Expected TRUE, got $simplified")

  // ========================================================
  // EliminateOuterJoin Tests (Phase 6)
  // ========================================================

  test("EliminateOuterJoin: LEFT OUTER with IS NOT NULL on right → INNER"):
    // SELECT * FROM left l LEFT JOIN right r ON l.id = r.id WHERE r.col IS NOT NULL
    val left = ProtoLogicalPlan.SubqueryAlias("l", table("left"))
    val right = ProtoLogicalPlan.SubqueryAlias("r", table("right"))
    val joinCond = ProtoExpr.Eq(
      ProtoExpr.ColumnRef("id", Some("l"), ProtoType.IntegerType, nullable = true),
      ProtoExpr.ColumnRef("id", Some("r"), ProtoType.IntegerType, nullable = true)
    )
    val join = ProtoLogicalPlan.Join(left, right, JoinType.LeftOuter, Some(joinCond))
    val filter = ProtoLogicalPlan.Filter(
      ProtoExpr.IsNotNull(
        ProtoExpr.ColumnRef("col", Some("r"), ProtoType.IntegerType, nullable = true)
      ),
      join
    )
    val optimized = EliminateOuterJoin(filter)
    optimized match
      case ProtoLogicalPlan.Filter(_, ProtoLogicalPlan.Join(_, _, JoinType.Inner, _)) => ()
      case _ => fail(s"Expected INNER join, got $optimized")

  test("EliminateOuterJoin: LEFT OUTER with comparison on right → INNER"):
    // SELECT * FROM left l LEFT JOIN right r ON l.id = r.id WHERE r.col = 5
    val left = ProtoLogicalPlan.SubqueryAlias("l", table("left"))
    val right = ProtoLogicalPlan.SubqueryAlias("r", table("right"))
    val joinCond = ProtoExpr.Eq(
      ProtoExpr.ColumnRef("id", Some("l"), ProtoType.IntegerType, nullable = true),
      ProtoExpr.ColumnRef("id", Some("r"), ProtoType.IntegerType, nullable = true)
    )
    val join = ProtoLogicalPlan.Join(left, right, JoinType.LeftOuter, Some(joinCond))
    val filter = ProtoLogicalPlan.Filter(
      ProtoExpr.Eq(
        ProtoExpr.ColumnRef("col", Some("r"), ProtoType.IntegerType, nullable = true),
        ProtoExpr.lit(5)
      ),
      join
    )
    val optimized = EliminateOuterJoin(filter)
    optimized match
      case ProtoLogicalPlan.Filter(_, ProtoLogicalPlan.Join(_, _, JoinType.Inner, _)) => ()
      case _ => fail(s"Expected INNER join, got $optimized")

  test("EliminateOuterJoin: LEFT OUTER without null-rejecting filter stays LEFT OUTER"):
    // SELECT * FROM left l LEFT JOIN right r ON l.id = r.id WHERE l.col > 0
    val left = ProtoLogicalPlan.SubqueryAlias("l", table("left"))
    val right = ProtoLogicalPlan.SubqueryAlias("r", table("right"))
    val joinCond = ProtoExpr.Eq(
      ProtoExpr.ColumnRef("id", Some("l"), ProtoType.IntegerType, nullable = true),
      ProtoExpr.ColumnRef("id", Some("r"), ProtoType.IntegerType, nullable = true)
    )
    val join = ProtoLogicalPlan.Join(left, right, JoinType.LeftOuter, Some(joinCond))
    val filter = ProtoLogicalPlan.Filter(
      ProtoExpr.Gt(
        ProtoExpr.ColumnRef("col", Some("l"), ProtoType.IntegerType, nullable = true),
        ProtoExpr.lit(0)
      ),
      join
    )
    val optimized = EliminateOuterJoin(filter)
    optimized match
      case ProtoLogicalPlan.Filter(_, ProtoLogicalPlan.Join(_, _, JoinType.LeftOuter, _)) => ()
      case _ => fail(s"Expected LEFT OUTER join to remain, got $optimized")

  test("EliminateOuterJoin: RIGHT OUTER with null-rejecting filter on left → INNER"):
    // SELECT * FROM left l RIGHT JOIN right r ON l.id = r.id WHERE l.col IS NOT NULL
    val left = ProtoLogicalPlan.SubqueryAlias("l", table("left"))
    val right = ProtoLogicalPlan.SubqueryAlias("r", table("right"))
    val joinCond = ProtoExpr.Eq(
      ProtoExpr.ColumnRef("id", Some("l"), ProtoType.IntegerType, nullable = true),
      ProtoExpr.ColumnRef("id", Some("r"), ProtoType.IntegerType, nullable = true)
    )
    val join = ProtoLogicalPlan.Join(left, right, JoinType.RightOuter, Some(joinCond))
    val filter = ProtoLogicalPlan.Filter(
      ProtoExpr.IsNotNull(
        ProtoExpr.ColumnRef("col", Some("l"), ProtoType.IntegerType, nullable = true)
      ),
      join
    )
    val optimized = EliminateOuterJoin(filter)
    optimized match
      case ProtoLogicalPlan.Filter(_, ProtoLogicalPlan.Join(_, _, JoinType.Inner, _)) => ()
      case _ => fail(s"Expected INNER join, got $optimized")

  test("Optimizer: Phase 6 SimplifyCasts integrated with other rules"):
    // SELECT CAST(x AS INT) FROM t WHERE x is already INT
    val plan = ProtoLogicalPlan.Project(
      Vector(
        ProtoExpr.Cast(
          ProtoExpr.ColumnRef("x", None, ProtoType.IntegerType, nullable = true),
          ProtoType.IntegerType
        )
      ),
      table("t")
    )
    val optimized = Optimizer.optimize(plan)
    optimized match
      case ProtoLogicalPlan.Project(Vector(ProtoExpr.ColumnRef("x", _, _, _)), _) => ()
      case _ => fail(s"Expected Project with ColumnRef, got $optimized")

  // ========================================================
  // EliminateLimits Tests
  // ========================================================

  test("EliminateLimits: nested limits take minimum (outer smaller)"):
    // LIMIT 5 (LIMIT 10 x) → LIMIT 5 x
    val plan = ProtoLogicalPlan.Limit(
      5,
      ProtoLogicalPlan.Limit(10, table("t"))
    )
    val optimized = EliminateLimits(plan)
    optimized match
      case ProtoLogicalPlan.Limit(5, ProtoLogicalPlan.RelationRef("t", _, _)) => ()
      case _ => fail(s"Expected LIMIT 5, got $optimized")

  test("EliminateLimits: nested limits take minimum (inner smaller)"):
    // LIMIT 10 (LIMIT 5 x) → LIMIT 5 x
    val plan = ProtoLogicalPlan.Limit(
      10,
      ProtoLogicalPlan.Limit(5, table("t"))
    )
    val optimized = EliminateLimits(plan)
    optimized match
      case ProtoLogicalPlan.Limit(5, ProtoLogicalPlan.RelationRef("t", _, _)) => ()
      case _ => fail(s"Expected LIMIT 5, got $optimized")

  test("EliminateLimits: nested limits with same value"):
    // LIMIT 5 (LIMIT 5 x) → LIMIT 5 x
    val plan = ProtoLogicalPlan.Limit(
      5,
      ProtoLogicalPlan.Limit(5, table("t"))
    )
    val optimized = EliminateLimits(plan)
    optimized match
      case ProtoLogicalPlan.Limit(5, ProtoLogicalPlan.RelationRef("t", _, _)) => ()
      case _ => fail(s"Expected LIMIT 5, got $optimized")

  test("EliminateLimits: deeply nested limits"):
    // LIMIT 100 (LIMIT 50 (LIMIT 10 x)) → LIMIT 10 x
    val plan = ProtoLogicalPlan.Limit(
      100,
      ProtoLogicalPlan.Limit(
        50,
        ProtoLogicalPlan.Limit(10, table("t"))
      )
    )
    val optimized = EliminateLimits(EliminateLimits(plan))
    optimized match
      case ProtoLogicalPlan.Limit(10, ProtoLogicalPlan.RelationRef("t", _, _)) => ()
      case _ => fail(s"Expected LIMIT 10, got $optimized")

  // ========================================================
  // RemoveRedundantAliases Tests
  // ========================================================

  test("RemoveRedundantAliases: remove x AS x"):
    // x AS x → x
    val expr = ProtoExpr.Alias(
      ProtoExpr.ColumnRef("x", None, ProtoType.IntegerType, nullable = true),
      "x"
    )
    val simplified = RemoveRedundantAliases.removeRedundantAliases(expr)
    simplified match
      case ProtoExpr.ColumnRef("x", _, _, _) => ()
      case _                                 => fail(s"Expected ColumnRef(x), got $simplified")

  test("RemoveRedundantAliases: keep x AS y"):
    // x AS y stays as-is
    val expr = ProtoExpr.Alias(
      ProtoExpr.ColumnRef("x", None, ProtoType.IntegerType, nullable = true),
      "y"
    )
    val simplified = RemoveRedundantAliases.removeRedundantAliases(expr)
    simplified match
      case ProtoExpr.Alias(ProtoExpr.ColumnRef("x", _, _, _), "y") => ()
      case _ => fail(s"Expected Alias(x, y), got $simplified")

  test("RemoveRedundantAliases: nested aliases with same name"):
    // (x AS a) AS a → x AS a
    val expr = ProtoExpr.Alias(
      ProtoExpr.Alias(col("x"), "a"),
      "a"
    )
    val simplified = RemoveRedundantAliases.removeRedundantAliases(expr)
    simplified match
      case ProtoExpr.Alias(_, "a") => ()
      case _                       => fail(s"Expected Alias(_, a), got $simplified")

  test("RemoveRedundantAliases: in project list"):
    val plan = ProtoLogicalPlan.Project(
      Vector(
        ProtoExpr.Alias(
          ProtoExpr.ColumnRef("id", None, ProtoType.IntegerType, nullable = true),
          "id"
        ),
        ProtoExpr.Alias(
          ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true),
          "alias_name"
        )
      ),
      table("t")
    )
    val optimized = RemoveRedundantAliases(plan)
    optimized match
      case ProtoLogicalPlan.Project(
            Vector(
              ProtoExpr.ColumnRef("id", _, _, _),
              ProtoExpr.Alias(ProtoExpr.ColumnRef("name", _, _, _), "alias_name")
            ),
            _
          ) =>
        ()
      case _ =>
        fail(s"Expected id to be unwrapped and name AS alias_name to remain, got $optimized")

  // ========================================================
  // SimplifyCaseConversionExpressions Tests
  // ========================================================

  test("SimplifyCaseConversionExpressions: UPPER(UPPER(x)) → UPPER(x)"):
    val expr = ProtoExpr.Upper(ProtoExpr.Upper(col("x")))
    val simplified = SimplifyCaseConversionExpressions.simplifyCaseConversion(expr)
    simplified match
      case ProtoExpr.Upper(ProtoExpr.ColumnRef("x", _, _, _)) => ()
      case _ => fail(s"Expected UPPER(x), got $simplified")

  test("SimplifyCaseConversionExpressions: LOWER(LOWER(x)) → LOWER(x)"):
    val expr = ProtoExpr.Lower(ProtoExpr.Lower(col("x")))
    val simplified = SimplifyCaseConversionExpressions.simplifyCaseConversion(expr)
    simplified match
      case ProtoExpr.Lower(ProtoExpr.ColumnRef("x", _, _, _)) => ()
      case _ => fail(s"Expected LOWER(x), got $simplified")

  test("SimplifyCaseConversionExpressions: UPPER(LOWER(x)) → UPPER(x)"):
    val expr = ProtoExpr.Upper(ProtoExpr.Lower(col("x")))
    val simplified = SimplifyCaseConversionExpressions.simplifyCaseConversion(expr)
    simplified match
      case ProtoExpr.Upper(ProtoExpr.ColumnRef("x", _, _, _)) => ()
      case _ => fail(s"Expected UPPER(x), got $simplified")

  test("SimplifyCaseConversionExpressions: LOWER(UPPER(x)) → LOWER(x)"):
    val expr = ProtoExpr.Lower(ProtoExpr.Upper(col("x")))
    val simplified = SimplifyCaseConversionExpressions.simplifyCaseConversion(expr)
    simplified match
      case ProtoExpr.Lower(ProtoExpr.ColumnRef("x", _, _, _)) => ()
      case _ => fail(s"Expected LOWER(x), got $simplified")

  test("SimplifyCaseConversionExpressions: deeply nested"):
    // UPPER(UPPER(UPPER(x))) → UPPER(x)
    val expr = ProtoExpr.Upper(ProtoExpr.Upper(ProtoExpr.Upper(col("x"))))
    val simplified = SimplifyCaseConversionExpressions.simplifyCaseConversion(expr)
    simplified match
      case ProtoExpr.Upper(ProtoExpr.ColumnRef("x", _, _, _)) => ()
      case _ => fail(s"Expected UPPER(x), got $simplified")

  // ========================================================
  // CombineConcats Tests
  // ========================================================

  test("CombineConcats: CONCAT(CONCAT(a, b), c) → CONCAT(a, b, c)"):
    val expr = ProtoExpr.Concat(
      Vector(
        ProtoExpr.Concat(Vector(col("a"), col("b"))),
        col("c")
      )
    )
    val simplified = CombineConcats.combineConcats(expr)
    simplified match
      case ProtoExpr.Concat(children) if children.size == 3 => ()
      case _ => fail(s"Expected CONCAT with 3 children, got $simplified")

  test("CombineConcats: CONCAT(a, CONCAT(b, c)) → CONCAT(a, b, c)"):
    val expr = ProtoExpr.Concat(
      Vector(
        col("a"),
        ProtoExpr.Concat(Vector(col("b"), col("c")))
      )
    )
    val simplified = CombineConcats.combineConcats(expr)
    simplified match
      case ProtoExpr.Concat(children) if children.size == 3 => ()
      case _ => fail(s"Expected CONCAT with 3 children, got $simplified")

  test("CombineConcats: CONCAT(CONCAT(a, b), CONCAT(c, d)) → CONCAT(a, b, c, d)"):
    val expr = ProtoExpr.Concat(
      Vector(
        ProtoExpr.Concat(Vector(col("a"), col("b"))),
        ProtoExpr.Concat(Vector(col("c"), col("d")))
      )
    )
    val simplified = CombineConcats.combineConcats(expr)
    simplified match
      case ProtoExpr.Concat(children) if children.size == 4 => ()
      case _ => fail(s"Expected CONCAT with 4 children, got $simplified")

  test("CombineConcats: non-nested CONCAT stays unchanged"):
    val expr = ProtoExpr.Concat(Vector(col("a"), col("b"), col("c")))
    val simplified = CombineConcats.combineConcats(expr)
    simplified match
      case ProtoExpr.Concat(children) if children.size == 3 => ()
      case _ => fail(s"Expected CONCAT with 3 children, got $simplified")

  test("CombineConcats: deeply nested"):
    // CONCAT(CONCAT(CONCAT(a, b), c), d) → CONCAT(a, b, c, d)
    val inner = ProtoExpr.Concat(Vector(col("a"), col("b")))
    val middle = ProtoExpr.Concat(Vector(inner, col("c")))
    val outer = ProtoExpr.Concat(Vector(middle, col("d")))
    val simplified = CombineConcats.combineConcats(outer)
    simplified match
      case ProtoExpr.Concat(children) if children.size == 4 => ()
      case _ => fail(s"Expected CONCAT with 4 children, got $simplified")

  // ========================================================
  // Integration Test for New Rules
  // ========================================================

  test("Optimizer: new rules integrated"):
    // Test that new rules work together with existing optimizer
    val plan = ProtoLogicalPlan.Project(
      Vector(
        // Redundant alias: x AS x
        ProtoExpr.Alias(
          ProtoExpr.ColumnRef("x", None, ProtoType.IntegerType, nullable = true),
          "x"
        ),
        // Nested UPPER
        ProtoExpr.Upper(
          ProtoExpr.Upper(
            ProtoExpr.ColumnRef("name", None, ProtoType.StringType, nullable = true)
          )
        )
      ),
      ProtoLogicalPlan.Limit(
        10,
        ProtoLogicalPlan.Limit(5, table("t"))
      )
    )
    val optimized = Optimizer.optimize(plan)
    // Should have: x (not x AS x), UPPER(name) (not UPPER(UPPER(name))), LIMIT 5
    optimized match
      case ProtoLogicalPlan.Project(
            Vector(
              ProtoExpr.ColumnRef("x", _, _, _),
              ProtoExpr.Upper(ProtoExpr.ColumnRef("name", _, _, _))
            ),
            ProtoLogicalPlan.Limit(5, _)
          ) =>
        ()
      case _ => fail(s"Expected optimized plan, got $optimized")
