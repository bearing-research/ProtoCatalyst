package protocatalyst.optimizer

import protocatalyst.optimizer.rules._
import protocatalyst.plan.ProtoLogicalPlan

/** The main compile-time optimizer for ProtoCatalyst.
  *
  * This optimizer implements a subset of Spark Catalyst's optimization rules that can be applied at
  * compile time (i.e., rules that don't require runtime statistics or table metadata).
  *
  * The optimization is organized into batches, following Catalyst's pattern:
  *   - Each batch contains related rules
  *   - Batches are executed in order
  *   - Within a batch, rules are applied using the batch's strategy (Once or FixedPoint)
  *
  * Currently implemented rules (Phase 1):
  *   - ConstantFolding: Evaluates constant expressions at compile time
  *   - BooleanSimplification: Simplifies boolean logic using identities
  *   - CollapseProject: Merges adjacent Project operators
  *   - CombineFilters: Merges adjacent Filter operators
  *
  * Future phases will add:
  *   - NullPropagation, ConstantPropagation, OptimizeIn (Phase 2)
  *   - PushDownPredicates, ColumnPruning, LimitPushdown (Phase 3)
  *   - EliminateDistinct, EliminateSorts, RemoveNoopOperators (Phase 4)
  *   - InlineCTE, RewriteCorrelatedSubquery (Phase 5)
  *
  * @see
  *   docs/OPTIMIZER_PLAN.md for the full optimization plan
  */
object Optimizer extends RuleExecutor:

  /** Core operator optimization rules.
    *
    * These rules form the main optimization pass and are run until the plan stops changing or the
    * iteration limit is reached.
    */
  private val operatorOptimizationRules: Seq[Rule] = Seq(
    // ─── Operator Combine ───
    CollapseProject,
    CombineFilters,

    // ─── Constant Folding & Simplification ───
    ConstantFolding,
    BooleanSimplification
  )

  override def batches: Seq[Batch] = Seq(
    // ═══════════════════════════════════════════════════════════════
    // BATCH 1: Operator Optimization (FixedPoint)
    // Run optimization rules until the plan stabilizes
    // ═══════════════════════════════════════════════════════════════
    Batch(
      "Operator Optimization",
      Strategy.FixedPoint(100),
      operatorOptimizationRules
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 2: Final Cleanup (Once)
    // One final pass to clean up any remaining opportunities
    // ═══════════════════════════════════════════════════════════════
    Batch(
      "Final Cleanup",
      Strategy.Once,
      Seq(CollapseProject, ConstantFolding)
    )
  )

  /** Optimize a logical plan.
    *
    * @param plan
    *   The unoptimized plan
    * @return
    *   The optimized plan
    */
  def optimize(plan: ProtoLogicalPlan): ProtoLogicalPlan = execute(plan)
