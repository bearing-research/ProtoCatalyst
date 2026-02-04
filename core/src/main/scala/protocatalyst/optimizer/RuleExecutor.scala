package protocatalyst.optimizer

import protocatalyst.plan.ProtoLogicalPlan

/** Execution strategy for a batch of rules.
  */
enum Strategy:
  /** Run rules exactly once. Used for rules that are idempotent or one-shot. */
  case Once

  /** Run rules until the plan stops changing or maxIterations is reached. Used for rules that may
    * enable other rules in the same batch.
    */
  case FixedPoint(maxIterations: Int)

/** A batch of rules with an execution strategy.
  *
  * @param name
  *   Human-readable name for debugging/logging
  * @param strategy
  *   How to execute this batch
  * @param rules
  *   The rules in this batch, applied in order
  */
case class Batch(name: String, strategy: Strategy, rules: Seq[Rule])

/** Base class for rule-based plan transformers.
  *
  * Subclasses define batches of rules that are executed in order. This follows Spark Catalyst's
  * RuleExecutor pattern.
  */
abstract class RuleExecutor:
  /** The batches of rules to execute. */
  def batches: Seq[Batch]

  /** Execute all batches on the input plan.
    *
    * @param plan
    *   The plan to optimize
    * @return
    *   The optimized plan
    */
  def execute(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    batches.foldLeft(plan) { (currentPlan, batch) =>
      executeBatch(currentPlan, batch)
    }

  /** Execute a single batch on a plan.
    */
  protected def executeBatch(plan: ProtoLogicalPlan, batch: Batch): ProtoLogicalPlan =
    batch.strategy match
      case Strategy.Once =>
        batch.rules.foldLeft(plan) { (p, rule) =>
          rule(p)
        }

      case Strategy.FixedPoint(maxIterations) =>
        var currentPlan = plan
        var iteration = 0
        var changed = true

        while changed && iteration < maxIterations do
          val beforeIteration = currentPlan
          currentPlan = batch.rules.foldLeft(currentPlan) { (p, rule) =>
            rule(p)
          }
          changed = currentPlan != beforeIteration
          iteration += 1

        currentPlan
