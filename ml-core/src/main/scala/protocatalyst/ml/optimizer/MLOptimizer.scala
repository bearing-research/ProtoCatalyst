package protocatalyst.ml.optimizer

import protocatalyst.ml.graph.ComputeGraph
import protocatalyst.ml.optimizer.rules._

/** The main compile-time optimizer for ML compute graphs.
  *
  * Organized into batches following the Catalyst pattern:
  *
  *   - Batch 1: Inference Simplification (Once) — DropoutElimination, BatchNormElimination
  *   - Batch 2: Operator Fusion (FixedPoint) — MatMulAddFusion, TransposeFusion
  *   - Batch 3: Algebraic Simplification (FixedPoint) — AlgebraicSimplification
  *   - Batch 4: Cleanup (FixedPoint) — IdentityElimination
  *   - Batch 5: Common Subexpression Elimination (Once)
  *   - Batch 6: Final Cleanup (Once) — DeadNodeElimination
  */
object MLOptimizer extends MLRuleExecutor:

  override def batches: Seq[MLBatch] = Seq(
    // ═══════════════════════════════════════════════════════════════
    // BATCH 1: Inference Simplification (Once)
    // Remove training-only nodes (Dropout in eval mode, BN folding)
    // ═══════════════════════════════════════════════════════════════
    MLBatch(
      "Inference Simplification",
      MLStrategy.Once,
      Seq(DropoutElimination, BatchNormElimination)
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 2: Operator Fusion (FixedPoint)
    // Fuse operations for better runtime performance
    // ═══════════════════════════════════════════════════════════════
    MLBatch(
      "Operator Fusion",
      MLStrategy.FixedPoint(10),
      Seq(MatMulAddFusion, TransposeFusion)
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 3: Algebraic Simplification (FixedPoint)
    // Simplify algebraic patterns (double negation, idempotent ops)
    // ═══════════════════════════════════════════════════════════════
    MLBatch(
      "Algebraic Simplification",
      MLStrategy.FixedPoint(10),
      Seq(AlgebraicSimplification)
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 4: Cleanup (FixedPoint)
    // Remove identity ops
    // ═══════════════════════════════════════════════════════════════
    MLBatch(
      "Cleanup",
      MLStrategy.FixedPoint(10),
      Seq(IdentityElimination)
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 5: Common Subexpression Elimination (Once)
    // Deduplicate structurally identical subexpressions
    // ═══════════════════════════════════════════════════════════════
    MLBatch(
      "Common Subexpression Elimination",
      MLStrategy.Once,
      Seq(CommonSubexprElimination)
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 6: Final Cleanup (Once)
    // Remove unreachable nodes
    // ═══════════════════════════════════════════════════════════════
    MLBatch(
      "Final Cleanup",
      MLStrategy.Once,
      Seq(DeadNodeElimination)
    )
  )

  def optimize(graph: ComputeGraph): ComputeGraph = execute(graph)
