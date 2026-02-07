package protocatalyst.ml.optimizer.rules

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph.ComputeGraph
import protocatalyst.ml.optimizer.{MLRule, MLTreeTransform}

/** Fuses MatMul + Add into Gemm (General Matrix Multiply).
  *
  * Pattern: Add(MatMul(a, b, false, false), c) → Gemm(a, b, Some(c), 1.0, 1.0, false, false)
  *
  * This is a common optimization in ML frameworks — Gemm fuses the multiply and add into a single
  * BLAS call.
  */
object MatMulAddFusion extends MLRule:
  override def apply(graph: ComputeGraph): ComputeGraph =
    MLTreeTransform.transformGraph(graph) {
      case TensorExpr.Add(TensorExpr.MatMul(a, b, false, false), c) =>
        TensorExpr.Gemm(a, b, Some(c), 1.0, 1.0, transA = false, transB = false)
      case TensorExpr.Add(c, TensorExpr.MatMul(a, b, false, false)) =>
        TensorExpr.Gemm(a, b, Some(c), 1.0, 1.0, transA = false, transB = false)
    }
