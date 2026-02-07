package protocatalyst.ml.optimizer.rules

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph.ComputeGraph
import protocatalyst.ml.optimizer.{MLRule, MLTreeTransform}

/** Fuses and eliminates redundant transpose operations.
  *
  * Patterns:
  *   - Double transpose: `Transpose(Transpose(x, p), p_inv)` → `x` when `p_inv` is the inverse of
  *     `p` (i.e., applying both is identity)
  *   - MatMul transpose folding: `MatMul(Transpose(a, [1,0]), b)` → `MatMul(a, b, transA=true)` and
  *     `MatMul(a, Transpose(b, [1,0]))` → `MatMul(a, b, transB=true)` (only for 2D transposes when
  *     the MatMul has no existing transpose flags)
  */
object TransposeFusion extends MLRule:

  override def apply(graph: ComputeGraph): ComputeGraph =
    MLTreeTransform.transformGraph(graph) {
      // Double transpose cancellation: compose permutations and check for identity
      case TensorExpr.Transpose(TensorExpr.Transpose(inner, perm1), perm2) =>
        val composed = composePerm(perm1, perm2)
        if isIdentityPerm(composed) then inner
        else TensorExpr.Transpose(inner, composed)

      // MatMul with transposed left operand: fold into transA flag
      case TensorExpr.MatMul(TensorExpr.Transpose(a, perm), b, false, transB) if is2DSwap(perm) =>
        TensorExpr.MatMul(a, b, transA = true, transB)

      // MatMul with transposed right operand: fold into transB flag
      case TensorExpr.MatMul(a, TensorExpr.Transpose(b, perm), transA, false) if is2DSwap(perm) =>
        TensorExpr.MatMul(a, b, transA, transB = true)
    }

  /** Compose two permutations: result[i] = perm1[perm2[i]]. */
  private def composePerm(perm1: Vector[Int], perm2: Vector[Int]): Vector[Int] =
    perm2.map(perm1(_))

  /** Check if a permutation is the identity [0, 1, 2, ...]. */
  private def isIdentityPerm(perm: Vector[Int]): Boolean =
    perm.zipWithIndex.forall((v, i) => v == i)

  /** Check if permutation is a 2D swap [1, 0]. */
  private def is2DSwap(perm: Vector[Int]): Boolean =
    perm == Vector(1, 0)
