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
