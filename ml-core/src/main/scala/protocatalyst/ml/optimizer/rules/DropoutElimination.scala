package protocatalyst.ml.optimizer.rules

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph.ComputeGraph
import protocatalyst.ml.optimizer.{MLRule, MLTreeTransform}

/** Removes Dropout nodes when training=false (inference mode).
  *
  * Pattern: Dropout(x, _, training=false) → x
  */
object DropoutElimination extends MLRule:
  override def apply(graph: ComputeGraph): ComputeGraph =
    MLTreeTransform.transformGraph(graph) { case TensorExpr.Dropout(input, _, false) =>
      input
    }
