package protocatalyst.truffle.backend

import munit.FunSuite

import protocatalyst.truffle.typed.*

/** Layer-1 demonstration (see `docs/compiler/TRUFFLE_EXPLORATION.md`): the typed, null-aware node
  * library built on the Truffle DSL. Validates the three things that distinguish Layer 1 from the
  * double-only backend:
  *
  *   1. **Typed values via `@Specialization`** — `long` and `double` paths, with `long→double`
  *      implicit promotion for mixed-type expressions.
  *   2. **Null propagation** — a NULL operand makes arithmetic/comparison NULL (the row fails WHERE).
  *   3. **Three-valued AND/OR** — `TRUE AND NULL = NULL` and `FALSE OR NULL = NULL` both fail the
  *      filter, exactly as SQL/Catalyst require.
  *
  * The predicate is run by `TFilterCountRoot`, which counts rows where the predicate is *exactly* TRUE.
  */
class TypedNodesSpec extends FunSuite:

  // a: long [1,2,3,4,5] (no nulls);  b: double [10,20,30,NULL,50] (null at row 3).
  private def columns: TypedColumns =
    val a: Array[Long] = Array(1L, 2L, 3L, 4L, 5L)
    val b: Array[Double] = Array(10.0, 20.0, 30.0, 0.0, 50.0)
    val data: Array[Object] = Array(a, b)
    val nulls: Array[Array[Boolean]] =
      Array(null, Array(false, false, false, true, false))
    new TypedColumns(data, nulls, 5)

  private def aCol = new TLongColumn(0)
  private def bCol = new TDoubleColumn(1)
  private def lng(v: Long): TExpr = new TLit.Long(v)
  private def dbl(v: Double): TExpr = new TLit.Double(v)
  private def ge(l: TExpr, r: TExpr): TExpr = TCompareFactory.GeNodeGen.create(l, r)
  private def lt(l: TExpr, r: TExpr): TExpr = TCompareFactory.LtNodeGen.create(l, r)
  private def gt(l: TExpr, r: TExpr): TExpr = TCompareFactory.GtNodeGen.create(l, r)
  private def add(l: TExpr, r: TExpr): TExpr = TArithFactory.AddNodeGen.create(l, r)
  private def and(l: TExpr, r: TExpr): TExpr = new TLogic.And(l, r)
  private def or(l: TExpr, r: TExpr): TExpr = new TLogic.Or(l, r)

  private def countWhere(predicate: TExpr): Long =
    val root = new TFilterCountRoot(null, predicate)
    root.getCallTarget.call(new TRow(columns)).asInstanceOf[java.lang.Long].longValue

  test("typed long comparison: a >= 3 → 3 rows"):
    assertEquals(countWhere(ge(aCol, lng(3))), 3L)

  test("null propagation: b < 25 excludes the NULL row → 2 rows"):
    // b = 10,20,30,NULL,50 → 10<25 T, 20<25 T, 30<25 F, NULL→NULL(excluded), 50<25 F
    assertEquals(countWhere(lt(bCol, dbl(25.0))), 2L)

  test("long→double promotion: (a + 0.5) > 3.0 → a in {3,4,5} → 3 rows"):
    assertEquals(countWhere(gt(add(aCol, dbl(0.5)), dbl(3.0))), 3L)

  test("3VL AND: (a >= 2) AND (b < 35) — TRUE AND NULL = NULL excluded → 2 rows"):
    // idx0 a<2 →F; idx1 T&T(20); idx2 T&T(30); idx3 T&NULL→NULL excluded; idx4 T&F(50)
    assertEquals(countWhere(and(ge(aCol, lng(2)), lt(bCol, dbl(35.0)))), 2L)

  test("3VL OR: (a >= 5) OR (b < 25) — FALSE OR NULL = NULL excluded → 3 rows"):
    // idx0 F|T; idx1 F|T; idx2 F|F; idx3 F|NULL→NULL excluded; idx4 T|F
    assertEquals(countWhere(or(ge(aCol, lng(5)), lt(bCol, dbl(25.0)))), 3L)

end TypedNodesSpec
