package protocatalyst.ml.optimizer.rules

import java.util.IdentityHashMap

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph.{ComputeGraph, NamedNode}
import protocatalyst.ml.optimizer.MLRule

/** Eliminates common subexpressions by deduplicating structurally identical TensorExpr subtrees.
  *
  * Walks the graph and builds a canonical mapping from structural equality to a single
  * representative instance. All references to structurally identical expressions are replaced with
  * the canonical representative, enabling DAG sharing and reducing redundant computation.
  */
object CommonSubexprElimination extends MLRule:

  override def apply(graph: ComputeGraph): ComputeGraph =
    if graph.nodes.isEmpty then return graph

    // Map from structural equality (via equals/hashCode) to canonical representative
    val canonical = collection.mutable.HashMap[TensorExpr, TensorExpr]()
    // Track identity for change detection
    val memo = new IdentityHashMap[TensorExpr, TensorExpr]()

    def canonicalize(expr: TensorExpr): TensorExpr =
      val cached = memo.get(expr)
      if cached != null then return cached

      // First canonicalize children
      val withCanonicalChildren = canonicalizeChildren(expr, canonicalize)

      // Then look up or register the canonical form
      val result = canonical.getOrElseUpdate(withCanonicalChildren, withCanonicalChildren)
      memo.put(expr, result)
      result

    var changed = false
    val newNodes = graph.nodes.map { node =>
      val newExpr = canonicalize(node.expr)
      if newExpr.eq(node.expr) then node
      else
        changed = true
        NamedNode(node.name, newExpr, node.outputType)
    }

    if changed then graph.copy(nodes = newNodes) else graph

  /** Recursively canonicalize children of a TensorExpr. */
  private def canonicalizeChildren(
      expr: TensorExpr,
      f: TensorExpr => TensorExpr
  ): TensorExpr =
    import TensorExpr._

    expr match
      // Leaf nodes — no children
      case _: Input | _: Constant | _: Parameter => expr

      // Linear algebra
      case MatMul(l, r, ta, tb) =>
        val (nl, nr) = (f(l), f(r))
        if (nl eq l) && (nr eq r) then expr else MatMul(nl, nr, ta, tb)
      case Gemm(a, b, c, alpha, beta, ta, tb) =>
        val (na, nb) = (f(a), f(b))
        val nc = c.map(f)
        if (na eq a) && (nb eq b) && optEq(nc, c) then expr
        else Gemm(na, nb, nc, alpha, beta, ta, tb)
      case Linear(i, w, b) =>
        val (ni, nw) = (f(i), f(w))
        val nb = b.map(f)
        if (ni eq i) && (nw eq w) && optEq(nb, b) then expr
        else Linear(ni, nw, nb)

      // Activations (unary)
      case Relu(i)           => unary(expr, i, f, Relu.apply)
      case Sigmoid(i)        => unary(expr, i, f, Sigmoid.apply)
      case Tanh(i)           => unary(expr, i, f, Tanh.apply)
      case Silu(i)           => unary(expr, i, f, Silu.apply)
      case HardSwish(i)      => unary(expr, i, f, HardSwish.apply)
      case Gelu(i, approx)   => unary(expr, i, f, x => Gelu(x, approx))
      case LeakyRelu(i, a)   => unary(expr, i, f, x => LeakyRelu(x, a))
      case Elu(i, a)         => unary(expr, i, f, x => Elu(x, a))
      case Softmax(i, ax)    => unary(expr, i, f, x => Softmax(x, ax))
      case LogSoftmax(i, ax) => unary(expr, i, f, x => LogSoftmax(x, ax))

      // Element-wise arithmetic
      case Add(l, r)       => binary(expr, l, r, f, Add.apply)
      case Sub(l, r)       => binary(expr, l, r, f, Sub.apply)
      case Mul(l, r)       => binary(expr, l, r, f, Mul.apply)
      case Div(l, r)       => binary(expr, l, r, f, Div.apply)
      case Pow(b, e)       => binary(expr, b, e, f, Pow.apply)
      case Sqrt(i)         => unary(expr, i, f, Sqrt.apply)
      case Neg(i)          => unary(expr, i, f, Neg.apply)
      case Abs(i)          => unary(expr, i, f, Abs.apply)
      case Exp(i)          => unary(expr, i, f, Exp.apply)
      case Log(i)          => unary(expr, i, f, Log.apply)
      case Clip(i, mn, mx) => unary(expr, i, f, x => Clip(x, mn, mx))

      // Shape manipulation
      case Reshape(i, s)    => unary(expr, i, f, x => Reshape(x, s))
      case Transpose(i, p)  => unary(expr, i, f, x => Transpose(x, p))
      case Flatten(i, ax)   => unary(expr, i, f, x => Flatten(x, ax))
      case Squeeze(i, ax)   => unary(expr, i, f, x => Squeeze(x, ax))
      case Unsqueeze(i, ax) => unary(expr, i, f, x => Unsqueeze(x, ax))

      // Regularization
      case Dropout(i, r, t) => unary(expr, i, f, x => Dropout(x, r, t))
      case Cast(i, dt)      => unary(expr, i, f, x => Cast(x, dt))

      // For all other nodes, delegate to the full MLTreeTransform
      case _ =>
        import protocatalyst.ml.optimizer.MLTreeTransform
        MLTreeTransform.transformExprChildren(expr)(f)

  private inline def unary(
      expr: TensorExpr,
      child: TensorExpr,
      f: TensorExpr => TensorExpr,
      mk: TensorExpr => TensorExpr
  ): TensorExpr =
    val nc = f(child)
    if nc eq child then expr else mk(nc)

  private inline def binary(
      expr: TensorExpr,
      l: TensorExpr,
      r: TensorExpr,
      f: TensorExpr => TensorExpr,
      mk: (TensorExpr, TensorExpr) => TensorExpr
  ): TensorExpr =
    val (nl, nr) = (f(l), f(r))
    if (nl eq l) && (nr eq r) then expr else mk(nl, nr)

  private def optEq(a: Option[TensorExpr], b: Option[TensorExpr]): Boolean =
    (a, b) match
      case (Some(x), Some(y)) => x eq y
      case (None, None)       => true
      case _                  => false
