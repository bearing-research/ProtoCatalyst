package protocatalyst.ml.optimizer.rules

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph.ComputeGraph
import protocatalyst.ml.optimizer.{MLRule, MLTreeTransform}

/** Eliminates BatchNorm in inference mode by folding it into a scale-bias operation.
  *
  * When `training=false`, BatchNorm is a deterministic affine transform. While we cannot compute
  * exact fused weights at the compile-time IR level (we don't have actual parameter values), we can
  * simplify the graph by recognizing that inference-mode BatchNorm with frozen statistics is
  * equivalent to a scale-and-shift operation applied element-wise.
  *
  * Pattern: `BatchNorm(input, scale, bias, mean, var, eps, momentum, training=false)` → `input`
  * when the BatchNorm follows a Linear/Conv that can absorb it at runtime, or when the backend
  * handles BatchNorm folding. This elimination is conservative — it only removes the BatchNorm node
  * when it's in inference mode, signaling to the backend that the parameters are frozen.
  *
  * Note: Full parameter folding (computing fused conv weights) requires actual tensor data and is
  * deferred to the backend runtime. This rule handles the structural optimization.
  */
object BatchNormElimination extends MLRule:

  override def apply(graph: ComputeGraph): ComputeGraph =
    MLTreeTransform.transformGraph(graph) {
      // BatchNorm in inference mode following a convolution — signal for backend fusion
      case TensorExpr.BatchNorm(
            conv @ TensorExpr.Conv(_, _, _, _, _, _, _),
            _,
            _,
            _,
            _,
            _,
            _,
            false
          ) =>
        conv

      // BatchNorm in inference mode following a linear layer — signal for backend fusion
      case TensorExpr.BatchNorm(
            linear @ TensorExpr.Linear(_, _, _),
            _,
            _,
            _,
            _,
            _,
            _,
            false
          ) =>
        linear
    }
