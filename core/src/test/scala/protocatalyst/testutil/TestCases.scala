package protocatalyst.testutil

import protocatalyst.expr.ProtoExpr
import protocatalyst.plan.ProtoLogicalPlan

/** Data classes for parametrized testing.
  *
  * These allow tests to be written as data tables:
  * {{{
  * import protocatalyst.testutil.PlanDsl._
  *
  * val cases = Seq(
  *   ExprTestCase("5 + 10 = 15", lit(5) + lit(10), lit(15)),
  *   ExprTestCase("20 - 8 = 12", lit(20) - lit(8), lit(12))
  * )
  *
  * cases.foreach { tc =>
  *   test(s"ConstantFolding: ${tc.desc}"):
  *     compareExpressions(ConstantFolding.foldConstants(tc.input), tc.expected)
  * }
  * }}}
  */

/** Test case for expression transformations.
  *
  * @param desc
  *   Human-readable description of the test case
  * @param input
  *   The input expression to transform
  * @param expected
  *   The expected output expression
  */
case class ExprTestCase(
    desc: String,
    input: ProtoExpr,
    expected: ProtoExpr
)

/** Test case for plan transformations.
  *
  * @param desc
  *   Human-readable description of the test case
  * @param input
  *   The input plan to transform
  * @param expected
  *   The expected output plan
  */
case class PlanTestCase(
    desc: String,
    input: ProtoLogicalPlan,
    expected: ProtoLogicalPlan
)

/** Test case where a plan should remain unchanged (no-op).
  *
  * @param desc
  *   Human-readable description of the test case
  * @param input
  *   The plan that should not be modified
  */
case class NoOpTestCase(
    desc: String,
    input: ProtoLogicalPlan
)

/** Test case for expression transformations that should not change the input.
  *
  * @param desc
  *   Human-readable description of the test case
  * @param input
  *   The expression that should not be modified
  */
case class ExprNoOpTestCase(
    desc: String,
    input: ProtoExpr
)
