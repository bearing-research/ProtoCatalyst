package protocatalyst.optimizer

import protocatalyst.plan.ProtoLogicalPlan

/** Base trait for optimization rules.
  *
  * Each rule transforms a logical plan into an equivalent but optimized plan. Rules should be
  * idempotent: applying a rule twice should produce the same result.
  */
trait Rule:
  /** Human-readable name for this rule (used in logging/debugging). */
  val ruleName: String = getClass.getSimpleName.stripSuffix("$")

  /** Apply this rule to a plan.
    *
    * @param plan
    *   The input plan to optimize
    * @return
    *   The optimized plan (may be the same instance if no changes)
    */
  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan

object Rule:
  /** Create a rule from a function. */
  def apply(name: String)(f: ProtoLogicalPlan => ProtoLogicalPlan): Rule =
    new Rule:
      override val ruleName: String = name
      override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan = f(plan)
