package protocatalyst.ml.optimizer.rules

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph.ComputeGraph
import protocatalyst.ml.optimizer.{MLRule, MLTreeTransform}

/** Algebraic simplifications for tensor expressions.
  *
  * Patterns:
  *   - Double negation: `Neg(Neg(x))` → `x`
  *   - Idempotent activations: `Relu(Relu(x))` → `Relu(x)`, `Abs(Abs(x))` → `Abs(x)`
  *   - Exp-Log cancellation: `Exp(Log(x))` → `x`, `Log(Exp(x))` → `x`
  *   - Double Sqrt/Abs via Pow: `Sqrt(Sqrt(x))` kept as-is (not algebraically reducible to single
  *     op)
  *   - Flatten of flatten: `Flatten(Flatten(x, a), a)` → `Flatten(x, a)` (same axis = idempotent)
  *   - Reshape chains: `Reshape(Reshape(x, _), s)` → `Reshape(x, s)` (intermediate shape is dead)
  *   - Softmax(LogSoftmax(x)) / LogSoftmax(Softmax(x)) not simplified (semantically different)
  */
object AlgebraicSimplification extends MLRule:

  override def apply(graph: ComputeGraph): ComputeGraph =
    MLTreeTransform.transformGraph(graph) {
      // ── Double negation ──
      case TensorExpr.Neg(TensorExpr.Neg(x)) => x

      // ── Idempotent activations ──
      case TensorExpr.Relu(inner @ TensorExpr.Relu(_))       => inner
      case TensorExpr.Abs(inner @ TensorExpr.Abs(_))         => inner
      case TensorExpr.Sigmoid(inner @ TensorExpr.Sigmoid(_)) => inner

      // ── Abs absorbs Neg ──
      case TensorExpr.Abs(TensorExpr.Neg(x)) => TensorExpr.Abs(x)

      // ── Relu absorbs Abs (all values from Abs are >= 0, so Relu is identity) ──
      case TensorExpr.Relu(inner @ TensorExpr.Abs(_)) => inner

      // ── Exp-Log cancellation ──
      case TensorExpr.Exp(TensorExpr.Log(x)) => x
      case TensorExpr.Log(TensorExpr.Exp(x)) => x

      // ── Reshape chains (intermediate shape is dead) ──
      case TensorExpr.Reshape(TensorExpr.Reshape(x, _), shape) =>
        TensorExpr.Reshape(x, shape)

      // ── Flatten of flatten with same axis ──
      case TensorExpr.Flatten(TensorExpr.Flatten(x, axis1), axis2) if axis1 == axis2 =>
        TensorExpr.Flatten(x, axis1)
    }
