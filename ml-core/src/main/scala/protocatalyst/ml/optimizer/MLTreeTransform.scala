package protocatalyst.ml.optimizer

import java.util.IdentityHashMap

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph.{ComputeGraph, NamedNode}

/** DAG-aware tree transform utilities for TensorExpr.
  *
  * Unlike the SQL TreeTransform, these methods use an IdentityHashMap for memoization. This is
  * necessary because TensorExpr nodes form a DAG (shared subexpressions), not a tree. Without
  * memoization, shared nodes would be visited multiple times, and worse, structural sharing would
  * be lost after transformation.
  */
object MLTreeTransform:

  /** Transform an expression DAG bottom-up (children first, then parent).
    *
    * Uses IdentityHashMap to ensure each node is transformed exactly once, preserving DAG
    * structure.
    */
  def transformExprUp(expr: TensorExpr)(
      rule: PartialFunction[TensorExpr, TensorExpr]
  ): TensorExpr =
    val memo = new IdentityHashMap[TensorExpr, TensorExpr]()
    def go(e: TensorExpr): TensorExpr =
      val cached = memo.get(e)
      if cached != null then cached
      else
        val withChildren = transformExprChildren(e)(go)
        val result = rule.applyOrElse(withChildren, identity[TensorExpr])
        memo.put(e, result)
        result
    go(expr)

  /** Transform an expression DAG top-down (parent first, then children).
    *
    * Uses IdentityHashMap to ensure each node is transformed exactly once, preserving DAG
    * structure.
    */
  def transformExprDown(expr: TensorExpr)(
      rule: PartialFunction[TensorExpr, TensorExpr]
  ): TensorExpr =
    val memo = new IdentityHashMap[TensorExpr, TensorExpr]()
    def go(e: TensorExpr): TensorExpr =
      val cached = memo.get(e)
      if cached != null then cached
      else
        val transformed = rule.applyOrElse(e, identity[TensorExpr])
        val result = transformExprChildren(transformed)(go)
        memo.put(e, result)
        result
    go(expr)

  /** Transform all child expressions of a TensorExpr.
    *
    * Returns the same instance if no children changed (identity-preserving).
    */
  def transformExprChildren(expr: TensorExpr)(transform: TensorExpr => TensorExpr): TensorExpr =
    import TensorExpr._

    expr match
      // ═══ Leaf nodes — no children ═══
      case _: Input | _: Constant | _: Parameter => expr

      // ═══ Linear algebra ═══
      case MatMul(left, right, transA, transB) =>
        val (nl, nr) = (transform(left), transform(right))
        if (nl.eq(left)) && (nr.eq(right)) then expr else MatMul(nl, nr, transA, transB)

      case Gemm(a, b, c, alpha, beta, transA, transB) =>
        val (na, nb) = (transform(a), transform(b))
        val nc = transformOption(c)(transform)
        if (na.eq(a)) && (nb.eq(b)) && (nc.eq(c)) then expr
        else Gemm(na, nb, nc, alpha, beta, transA, transB)

      case Linear(input, weight, bias) =>
        val (ni, nw) = (transform(input), transform(weight))
        val nb = transformOption(bias)(transform)
        if (ni.eq(input)) && (nw.eq(weight)) && (nb.eq(bias)) then expr
        else Linear(ni, nw, nb)

      // ═══ Convolution ═══
      case Conv(input, weight, bias, strides, pads, dilations, group) =>
        val (ni, nw) = (transform(input), transform(weight))
        val nb = transformOption(bias)(transform)
        if (ni.eq(input)) && (nw.eq(weight)) && (nb.eq(bias)) then expr
        else Conv(ni, nw, nb, strides, pads, dilations, group)

      case ConvTranspose(input, weight, bias, strides, pads, outputPads, dilations, group) =>
        val (ni, nw) = (transform(input), transform(weight))
        val nb = transformOption(bias)(transform)
        if (ni.eq(input)) && (nw.eq(weight)) && (nb.eq(bias)) then expr
        else ConvTranspose(ni, nw, nb, strides, pads, outputPads, dilations, group)

      // ═══ Activations (unary) ═══
      case Relu(input)           => transformUnary(expr, input, transform, Relu.apply)
      case LeakyRelu(input, a)   => transformUnary(expr, input, transform, i => LeakyRelu(i, a))
      case Sigmoid(input)        => transformUnary(expr, input, transform, Sigmoid.apply)
      case Tanh(input)           => transformUnary(expr, input, transform, Tanh.apply)
      case Softmax(input, axis)  => transformUnary(expr, input, transform, i => Softmax(i, axis))
      case LogSoftmax(input, ax) => transformUnary(expr, input, transform, i => LogSoftmax(i, ax))
      case Gelu(input, approx)   => transformUnary(expr, input, transform, i => Gelu(i, approx))
      case Silu(input)           => transformUnary(expr, input, transform, Silu.apply)
      case Elu(input, a)         => transformUnary(expr, input, transform, i => Elu(i, a))
      case HardSwish(input)      => transformUnary(expr, input, transform, HardSwish.apply)

      // ═══ Element-wise arithmetic ═══
      case Add(l, r)             => transformBinary(expr, l, r, transform, Add.apply)
      case Sub(l, r)             => transformBinary(expr, l, r, transform, Sub.apply)
      case Mul(l, r)             => transformBinary(expr, l, r, transform, Mul.apply)
      case Div(l, r)             => transformBinary(expr, l, r, transform, Div.apply)
      case Pow(b, e)             => transformBinary(expr, b, e, transform, Pow.apply)
      case Sqrt(input)           => transformUnary(expr, input, transform, Sqrt.apply)
      case Neg(input)            => transformUnary(expr, input, transform, Neg.apply)
      case Abs(input)            => transformUnary(expr, input, transform, Abs.apply)
      case Exp(input)            => transformUnary(expr, input, transform, Exp.apply)
      case Log(input)            => transformUnary(expr, input, transform, Log.apply)
      case Clip(input, min, max) =>
        transformUnary(expr, input, transform, i => Clip(i, min, max))

      // ═══ Pooling ═══
      case MaxPool(input, ks, st, pa) =>
        transformUnary(expr, input, transform, i => MaxPool(i, ks, st, pa))
      case AvgPool(input, ks, st, pa, cip) =>
        transformUnary(expr, input, transform, i => AvgPool(i, ks, st, pa, cip))
      case GlobalAvgPool(input) =>
        transformUnary(expr, input, transform, GlobalAvgPool.apply)
      case AdaptiveAvgPool(input, os) =>
        transformUnary(expr, input, transform, i => AdaptiveAvgPool(i, os))

      // ═══ Normalization ═══
      case BatchNorm(input, scale, bias, runningMean, runningVar, eps, mom, tr) =>
        val ni = transform(input)
        val ns = transform(scale)
        val nb = transform(bias)
        val nm = transform(runningMean)
        val nv = transform(runningVar)
        if (ni.eq(input)) && (ns.eq(scale)) && (nb.eq(bias)) && (nm.eq(runningMean)) && (nv.eq(
            runningVar
          ))
        then expr
        else BatchNorm(ni, ns, nb, nm, nv, eps, mom, tr)

      case LayerNorm(input, normShape, weight, bias, eps) =>
        val ni = transform(input)
        val nw = transformOption(weight)(transform)
        val nb = transformOption(bias)(transform)
        if (ni.eq(input)) && (nw.eq(weight)) && (nb.eq(bias)) then expr
        else LayerNorm(ni, normShape, nw, nb, eps)

      case GroupNorm(input, numGroups, weight, bias, eps) =>
        val ni = transform(input)
        val nw = transformOption(weight)(transform)
        val nb = transformOption(bias)(transform)
        if (ni.eq(input)) && (nw.eq(weight)) && (nb.eq(bias)) then expr
        else GroupNorm(ni, numGroups, nw, nb, eps)

      case InstanceNorm(input, scale, bias, eps) =>
        val ni = transform(input)
        val ns = transform(scale)
        val nb = transform(bias)
        if (ni.eq(input)) && (ns.eq(scale)) && (nb.eq(bias)) then expr
        else InstanceNorm(ni, ns, nb, eps)

      // ═══ Reduction ═══
      case ReduceSum(input, axes, kd) =>
        transformUnary(expr, input, transform, i => ReduceSum(i, axes, kd))
      case ReduceMean(input, axes, kd) =>
        transformUnary(expr, input, transform, i => ReduceMean(i, axes, kd))
      case ReduceMax(input, axes, kd) =>
        transformUnary(expr, input, transform, i => ReduceMax(i, axes, kd))
      case ReduceMin(input, axes, kd) =>
        transformUnary(expr, input, transform, i => ReduceMin(i, axes, kd))
      case ReduceProd(input, axes, kd) =>
        transformUnary(expr, input, transform, i => ReduceProd(i, axes, kd))

      // ═══ Shape manipulation ═══
      case Reshape(input, shape) =>
        transformUnary(expr, input, transform, i => Reshape(i, shape))
      case Transpose(input, perm) =>
        transformUnary(expr, input, transform, i => Transpose(i, perm))
      case Flatten(input, axis) =>
        transformUnary(expr, input, transform, i => Flatten(i, axis))
      case Squeeze(input, axes) =>
        transformUnary(expr, input, transform, i => Squeeze(i, axes))
      case Unsqueeze(input, axes) =>
        transformUnary(expr, input, transform, i => Unsqueeze(i, axes))
      case TensorConcat(inputs, axis) =>
        val ni = transformVector(inputs)(transform)
        if ni.eq(inputs) then expr else TensorConcat(ni, axis)
      case Split(input, axis, sizes) =>
        transformUnary(expr, input, transform, i => Split(i, axis, sizes))
      case TensorSlice(input, starts, ends, axes, steps) =>
        transformUnary(expr, input, transform, i => TensorSlice(i, starts, ends, axes, steps))
      case Gather(input, indices, axis) =>
        transformBinary(expr, input, indices, transform, (i, idx) => Gather(i, idx, axis))
      case Scatter(input, indices, updates, axis) =>
        val ni = transform(input)
        val nidx = transform(indices)
        val nu = transform(updates)
        if (ni.eq(input)) && (nidx.eq(indices)) && (nu.eq(updates)) then expr
        else Scatter(ni, nidx, nu, axis)
      case Pad(input, pads, mode, cv) =>
        transformUnary(expr, input, transform, i => Pad(i, pads, mode, cv))
      case Expand(input, shape) =>
        transformUnary(expr, input, transform, i => Expand(i, shape))

      // ═══ Recurrent ═══
      case LSTM(input, wih, whh, bias, hs, nl, bidir, drop) =>
        val ni = transform(input)
        val nwih = transform(wih)
        val nwhh = transform(whh)
        val nb = transformOption(bias)(transform)
        if (ni.eq(input)) && (nwih.eq(wih)) && (nwhh.eq(whh)) && (nb.eq(bias)) then expr
        else LSTM(ni, nwih, nwhh, nb, hs, nl, bidir, drop)

      case GRU(input, wih, whh, bias, hs, nl, bidir, drop) =>
        val ni = transform(input)
        val nwih = transform(wih)
        val nwhh = transform(whh)
        val nb = transformOption(bias)(transform)
        if (ni.eq(input)) && (nwih.eq(wih)) && (nwhh.eq(whh)) && (nb.eq(bias)) then expr
        else GRU(ni, nwih, nwhh, nb, hs, nl, bidir, drop)

      // ═══ Attention ═══
      case ScaledDotProductAttention(q, k, v, mask, drop, causal) =>
        val (nq, nk, nv) = (transform(q), transform(k), transform(v))
        val nm = transformOption(mask)(transform)
        if (nq.eq(q)) && (nk.eq(k)) && (nv.eq(v)) && (nm.eq(mask)) then expr
        else ScaledDotProductAttention(nq, nk, nv, nm, drop, causal)

      case MultiHeadAttention(q, k, v, wQ, wK, wV, wO, nh, mask, drop) =>
        val (nq, nk, nv) = (transform(q), transform(k), transform(v))
        val (nwQ, nwK, nwV, nwO) = (transform(wQ), transform(wK), transform(wV), transform(wO))
        val nm = transformOption(mask)(transform)
        if (nq.eq(q)) && (nk.eq(k)) && (nv.eq(v)) &&
          (nwQ.eq(wQ)) && (nwK.eq(wK)) && (nwV.eq(wV)) && (nwO.eq(wO)) && (nm.eq(mask))
        then expr
        else MultiHeadAttention(nq, nk, nv, nwQ, nwK, nwV, nwO, nh, nm, drop)

      // ═══ Embedding ═══
      case Embedding(indices, weight, paddingIdx) =>
        val (ni, nw) = (transform(indices), transform(weight))
        if (ni.eq(indices)) && (nw.eq(weight)) then expr
        else Embedding(ni, nw, paddingIdx)

      // ═══ Regularization ═══
      case Dropout(input, ratio, tr) =>
        transformUnary(expr, input, transform, i => Dropout(i, ratio, tr))

      // ═══ Loss functions ═══
      case CrossEntropyLoss(input, target, weight, reduction) =>
        val ni = transform(input)
        val nt = transform(target)
        val nw = transformOption(weight)(transform)
        if (ni.eq(input)) && (nt.eq(target)) && (nw.eq(weight)) then expr
        else CrossEntropyLoss(ni, nt, nw, reduction)

      case MSELoss(input, target, reduction) =>
        transformBinary(expr, input, target, transform, (i, t) => MSELoss(i, t, reduction))
      case L1Loss(input, target, reduction) =>
        transformBinary(expr, input, target, transform, (i, t) => L1Loss(i, t, reduction))

      case BCELoss(input, target, weight, reduction) =>
        val ni = transform(input)
        val nt = transform(target)
        val nw = transformOption(weight)(transform)
        if (ni.eq(input)) && (nt.eq(target)) && (nw.eq(weight)) then expr
        else BCELoss(ni, nt, nw, reduction)

      case BCEWithLogitsLoss(input, target, weight, reduction) =>
        val ni = transform(input)
        val nt = transform(target)
        val nw = transformOption(weight)(transform)
        if (ni.eq(input)) && (nt.eq(target)) && (nw.eq(weight)) then expr
        else BCEWithLogitsLoss(ni, nt, nw, reduction)

      // ═══ Comparison / logical ═══
      case Equal(l, r)       => transformBinary(expr, l, r, transform, Equal.apply)
      case Greater(l, r)     => transformBinary(expr, l, r, transform, Greater.apply)
      case Less(l, r)        => transformBinary(expr, l, r, transform, Less.apply)
      case Where(cond, x, y) =>
        val (nc, nx, ny) = (transform(cond), transform(x), transform(y))
        if (nc.eq(cond)) && (nx.eq(x)) && (ny.eq(y)) then expr
        else Where(nc, nx, ny)

      // ═══ Type casting ═══
      case Cast(input, targetDType) =>
        transformUnary(expr, input, transform, i => Cast(i, targetDType))

      // ═══ Opaque / custom op ═══
      case OpaqueOp(name, inputs, attrs, outType) =>
        val ni = transformVector(inputs)(transform)
        if ni.eq(inputs) then expr else OpaqueOp(name, ni, attrs, outType)

  /** Return the direct child TensorExpr nodes of an expression, in declaration order.
    *
    * Used by DeadNodeElimination for reachability analysis and by Autograd for gradient
    * propagation. The ordering is consistent with the VJP rules.
    */
  def childExprs(expr: TensorExpr): Seq[TensorExpr] =
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

  /** Transform all nodes in a ComputeGraph. */
  def transformGraph(graph: ComputeGraph)(
      rule: PartialFunction[TensorExpr, TensorExpr]
  ): ComputeGraph =
    var changed = false
    val newNodes = graph.nodes.map { node =>
      val newExpr = transformExprUp(node.expr)(rule)
      if newExpr.eq(node.expr) then node
      else
        changed = true
        NamedNode(node.name, newExpr, node.outputType)
    }
    if changed then graph.copy(nodes = newNodes) else graph

  // ── Helpers ──

  private inline def transformUnary(
      expr: TensorExpr,
      child: TensorExpr,
      transform: TensorExpr => TensorExpr,
      mk: TensorExpr => TensorExpr
  ): TensorExpr =
    val nc = transform(child)
    if nc.eq(child) then expr else mk(nc)

  private inline def transformBinary(
      expr: TensorExpr,
      left: TensorExpr,
      right: TensorExpr,
      transform: TensorExpr => TensorExpr,
      mk: (TensorExpr, TensorExpr) => TensorExpr
  ): TensorExpr =
    val (nl, nr) = (transform(left), transform(right))
    if (nl.eq(left)) && (nr.eq(right)) then expr else mk(nl, nr)

  private def transformOption(
      opt: Option[TensorExpr]
  )(transform: TensorExpr => TensorExpr): Option[TensorExpr] =
    opt match
      case Some(e) =>
        val ne = transform(e)
        if ne.eq(e) then opt else Some(ne)
      case None => opt

  private def transformVector(
      vec: Vector[TensorExpr]
  )(transform: TensorExpr => TensorExpr): Vector[TensorExpr] =
    var changed = false
    val newVec = vec.map { elem =>
      val newElem = transform(elem)
      if !newElem.eq(elem) then changed = true
      newElem
    }
    if changed then newVec else vec
