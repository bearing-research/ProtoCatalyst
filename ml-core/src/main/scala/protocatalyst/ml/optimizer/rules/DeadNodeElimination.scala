package protocatalyst.ml.optimizer.rules

import java.util.IdentityHashMap

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph.ComputeGraph
import protocatalyst.ml.optimizer.MLRule

/** Removes nodes from the graph that are not reachable from outputs.
  *
  * This operates at the ComputeGraph level, removing NamedNodes whose expressions are not
  * transitively referenced by any output node.
  */
object DeadNodeElimination extends MLRule:
  override def apply(graph: ComputeGraph): ComputeGraph =
    if graph.nodes.isEmpty then return graph

    // Collect all TensorExpr instances reachable from the last node (the output)
    val reachable = new IdentityHashMap[TensorExpr, java.lang.Boolean]()
    graph.nodes.lastOption.foreach(n => collectReachable(n.expr, reachable))

    val newNodes = graph.nodes.filter(n => reachable.containsKey(n.expr))
    if newNodes.size == graph.nodes.size then graph
    else graph.copy(nodes = newNodes)

  private def collectReachable(
      expr: TensorExpr,
      visited: IdentityHashMap[TensorExpr, java.lang.Boolean]
  ): Unit =
    if visited.containsKey(expr) then return
    visited.put(expr, java.lang.Boolean.TRUE)
    childExprs(expr).foreach(collectReachable(_, visited))

  private def childExprs(expr: TensorExpr): Seq[TensorExpr] =
    import TensorExpr._
    expr match
      // Leaf nodes
      case _: Input | _: Constant | _: Parameter => Seq.empty

      // Linear algebra
      case MatMul(l, r, _, _)        => Seq(l, r)
      case Gemm(a, b, c, _, _, _, _) => Seq(a, b) ++ c.toSeq
      case Linear(i, w, b)           => Seq(i, w) ++ b.toSeq

      // Convolution
      case Conv(i, w, b, _, _, _, _)             => Seq(i, w) ++ b.toSeq
      case ConvTranspose(i, w, b, _, _, _, _, _) => Seq(i, w) ++ b.toSeq

      // Activations (unary)
      case Relu(i)          => Seq(i)
      case LeakyRelu(i, _)  => Seq(i)
      case Sigmoid(i)       => Seq(i)
      case Tanh(i)          => Seq(i)
      case Softmax(i, _)    => Seq(i)
      case LogSoftmax(i, _) => Seq(i)
      case Gelu(i, _)       => Seq(i)
      case Silu(i)          => Seq(i)
      case Elu(i, _)        => Seq(i)
      case HardSwish(i)     => Seq(i)

      // Element-wise arithmetic
      case Add(l, r)     => Seq(l, r)
      case Sub(l, r)     => Seq(l, r)
      case Mul(l, r)     => Seq(l, r)
      case Div(l, r)     => Seq(l, r)
      case Pow(b, e)     => Seq(b, e)
      case Sqrt(i)       => Seq(i)
      case Neg(i)        => Seq(i)
      case Abs(i)        => Seq(i)
      case Exp(i)        => Seq(i)
      case Log(i)        => Seq(i)
      case Clip(i, _, _) => Seq(i)

      // Pooling
      case MaxPool(i, _, _, _)    => Seq(i)
      case AvgPool(i, _, _, _, _) => Seq(i)
      case GlobalAvgPool(i)       => Seq(i)
      case AdaptiveAvgPool(i, _)  => Seq(i)

      // Normalization
      case BatchNorm(i, s, b, m, v, _, _, _) => Seq(i, s, b, m, v)
      case LayerNorm(i, _, w, b, _)          => Seq(i) ++ w.toSeq ++ b.toSeq
      case GroupNorm(i, _, w, b, _)          => Seq(i) ++ w.toSeq ++ b.toSeq
      case InstanceNorm(i, s, b, _)          => Seq(i, s, b)

      // Reduction
      case ReduceSum(i, _, _)  => Seq(i)
      case ReduceMean(i, _, _) => Seq(i)
      case ReduceMax(i, _, _)  => Seq(i)
      case ReduceMin(i, _, _)  => Seq(i)
      case ReduceProd(i, _, _) => Seq(i)

      // Shape manipulation
      case Reshape(i, _)              => Seq(i)
      case Transpose(i, _)            => Seq(i)
      case Flatten(i, _)              => Seq(i)
      case Squeeze(i, _)              => Seq(i)
      case Unsqueeze(i, _)            => Seq(i)
      case TensorConcat(inputs, _)    => inputs
      case Split(i, _, _)             => Seq(i)
      case TensorSlice(i, _, _, _, _) => Seq(i)
      case Gather(i, idx, _)          => Seq(i, idx)
      case Scatter(i, idx, u, _)      => Seq(i, idx, u)
      case Pad(i, _, _, _)            => Seq(i)
      case Expand(i, _)               => Seq(i)

      // Recurrent
      case LSTM(i, wih, whh, b, _, _, _, _) => Seq(i, wih, whh) ++ b.toSeq
      case GRU(i, wih, whh, b, _, _, _, _)  => Seq(i, wih, whh) ++ b.toSeq

      // Attention
      case ScaledDotProductAttention(q, k, v, m, _, _)          => Seq(q, k, v) ++ m.toSeq
      case MultiHeadAttention(q, k, v, wQ, wK, wV, wO, _, m, _) =>
        Seq(q, k, v, wQ, wK, wV, wO) ++ m.toSeq

      // Embedding
      case Embedding(i, w, _) => Seq(i, w)

      // Regularization
      case Dropout(i, _, _) => Seq(i)

      // Loss functions
      case CrossEntropyLoss(i, t, w, _)  => Seq(i, t) ++ w.toSeq
      case MSELoss(i, t, _)              => Seq(i, t)
      case L1Loss(i, t, _)               => Seq(i, t)
      case BCELoss(i, t, w, _)           => Seq(i, t) ++ w.toSeq
      case BCEWithLogitsLoss(i, t, w, _) => Seq(i, t) ++ w.toSeq

      // Comparison / logical
      case Equal(l, r)    => Seq(l, r)
      case Greater(l, r)  => Seq(l, r)
      case Less(l, r)     => Seq(l, r)
      case Where(c, x, y) => Seq(c, x, y)

      // Type casting
      case Cast(i, _) => Seq(i)

      // Opaque / custom op
      case OpaqueOp(_, inputs, _, _) => inputs
