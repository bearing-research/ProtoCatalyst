package protocatalyst.testutil

import protocatalyst.optimizer._
import protocatalyst.plan.ProtoLogicalPlan

/** Enhanced base trait for optimizer rule tests.
  *
  * Provides:
  *   - Mini-optimizer pattern (custom RuleExecutor per test)
  *   - Rule-specific assertion methods
  *   - Convenient imports for test DSL
  *
  * Usage:
  * {{{
  * class MyRuleSuite extends munit.FunSuite with RuleTestBase:
  *   import PlanDsl._
  *
  *   // Create mini-optimizer with specific rules
  *   val optimizer = createOptimizer(ConstantFolding, BooleanSimplification)
  *
  *   test("my rule test"):
  *     val input = relation("t").where(lit(true))
  *     val expected = relation("t")
  *     checkOptimizer(optimizer)(input)(expected)
  * }}}
  */
trait RuleTestBase extends PlanTestBase:

  /** Create a mini-optimizer that applies the given rules with fixed-point iteration.
    *
    * @param rules
    *   The rules to apply (in order)
    * @param maxIterations
    *   Maximum iterations for fixed-point (default 10)
    * @return
    *   A RuleExecutor that applies only these rules
    */
  def createOptimizer(rules: Rule*)(maxIterations: Int = 10): RuleExecutor =
    new RuleExecutor:
      override def batches: Seq[Batch] = Seq(
        Batch("Test Rules", Strategy.FixedPoint(maxIterations), rules)
      )

  /** Create a mini-optimizer that applies rules exactly once (no iteration). */
  def createOnceOptimizer(rules: Rule*): RuleExecutor =
    new RuleExecutor:
      override def batches: Seq[Batch] = Seq(
        Batch("Test Rules (Once)", Strategy.Once, rules)
      )

  /** Assert that a rule transforms the plan as expected.
    *
    * @param rule
    *   The rule to apply
    * @param original
    *   The input plan
    * @param expected
    *   The expected output plan
    */
  def checkRule(rule: Rule)(original: ProtoLogicalPlan)(expected: ProtoLogicalPlan): Unit =
    comparePlans(rule(original), expected)

  /** Assert that a rule leaves the plan unchanged (no-op case).
    *
    * @param rule
    *   The rule to apply
    * @param plan
    *   The plan that should remain unchanged
    */
  def checkRuleUnchanged(rule: Rule)(plan: ProtoLogicalPlan): Unit =
    comparePlans(rule(plan), plan)

  /** Assert that an optimizer transforms the plan as expected.
    *
    * @param optimizer
    *   The optimizer to apply
    * @param original
    *   The input plan
    * @param expected
    *   The expected output plan
    */
  def checkOptimizer(optimizer: RuleExecutor)(original: ProtoLogicalPlan)(
      expected: ProtoLogicalPlan
  ): Unit =
    comparePlans(optimizer.execute(original), expected)

  /** Assert that an optimizer leaves the plan unchanged.
    *
    * @param optimizer
    *   The optimizer to apply
    * @param plan
    *   The plan that should remain unchanged
    */
  def checkOptimizerUnchanged(optimizer: RuleExecutor)(plan: ProtoLogicalPlan): Unit =
    comparePlans(optimizer.execute(plan), plan)
