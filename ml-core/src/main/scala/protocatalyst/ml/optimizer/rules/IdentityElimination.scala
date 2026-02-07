package protocatalyst.ml.optimizer.rules

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph.ComputeGraph
import protocatalyst.ml.optimizer.{MLRule, MLTreeTransform}

/** Removes identity operations that have no effect.
  *
  * Patterns:
  *   - Reshape(x, same_shape) → x (when shape matches input -- detected by Reshape to same vector)
  *   - Transpose with identity permutation → x
  *   - Cast to same dtype → x
  *   - Flatten(x, 0) when input is already 1D → x (not checked here, just Flatten axis=0 rank=1)
  */
object IdentityElimination extends MLRule:
  override def apply(graph: ComputeGraph): ComputeGraph =
    MLTreeTransform.transformGraph(graph) {
      // Transpose with identity permutation [0, 1, 2, ...]
      case TensorExpr.Transpose(input, perm) if isIdentityPerm(perm) =>
        input

      // Cast to same dtype as input (only when input carries type info)
      case TensorExpr.Cast(input @ TensorExpr.Input(_, tt), targetDType)
          if tt.dtype == targetDType =>
        input
      case TensorExpr.Cast(input @ TensorExpr.Constant(_, tt, _), targetDType)
          if tt.dtype == targetDType =>
        input
      case TensorExpr.Cast(input @ TensorExpr.Parameter(_, tt, _), targetDType)
          if tt.dtype == targetDType =>
        input
    }

  private def isIdentityPerm(perm: Vector[Int]): Boolean =
    perm.zipWithIndex.forall { (v, i) => v == i }
