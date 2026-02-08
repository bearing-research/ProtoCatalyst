package protocatalyst.ml.optimizer

import java.nio.{ByteBuffer, ByteOrder}

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.types._

/** Vector-Jacobian product rules for reverse-mode automatic differentiation.
  *
  * Each rule takes a forward expression and its upstream gradient and returns a gradient for each
  * child input, in the same order as `MLTreeTransform.childExprs`. `None` means no gradient flows
  * to that input (e.g., target in loss functions, indices in Gather).
  */
object VJPRules:
  import TensorExpr._

  /** Compute per-child gradients for a forward expression.
    *
    * @param expr
    *   the forward expression
    * @param grad
    *   upstream gradient (same shape as expr's output)
    * @param typeOf
    *   lookup function for expression types
    * @return
    *   gradients for each child, aligned with `MLTreeTransform.childExprs` order
    */
  def vjp(
      expr: TensorExpr,
      grad: TensorExpr,
      typeOf: TensorExpr => TensorType
  ): Vector[Option[TensorExpr]] = expr match

    // ── Leaf nodes should never be visited ──────────────────────────────────
    case _: Input | _: Constant | _: Parameter =>
      throw Autograd.AutogradException("VJP called on leaf node")

    // ── Element-wise arithmetic ─────────────────────────────────────────────
    case Add(left, right) =>
      val (lt, rt, ot) = (typeOf(left), typeOf(right), typeOf(expr))
      Vector(Some(unbroadcast(grad, ot, lt)), Some(unbroadcast(grad, ot, rt)))

    case Sub(left, right) =>
      val (lt, rt, ot) = (typeOf(left), typeOf(right), typeOf(expr))
      Vector(Some(unbroadcast(grad, ot, lt)), Some(unbroadcast(Neg(grad), ot, rt)))

    case Mul(left, right) =>
      val (lt, rt, ot) = (typeOf(left), typeOf(right), typeOf(expr))
      Vector(
        Some(unbroadcast(Mul(grad, right), ot, lt)),
        Some(unbroadcast(Mul(grad, left), ot, rt))
      )

    case Div(left, right) =>
      val (lt, rt, ot) = (typeOf(left), typeOf(right), typeOf(expr))
      Vector(
        Some(unbroadcast(Div(grad, right), ot, lt)),
        Some(unbroadcast(Neg(Div(Mul(grad, left), Mul(right, right))), ot, rt))
      )

    case Pow(base, exponent) =>
      val (bt, et, ot) = (typeOf(base), typeOf(exponent), typeOf(expr))
      val one = scalarConst(1.0, bt.dtype)
      val dBase = Mul(Mul(grad, exponent), Pow(base, Sub(exponent, one)))
      val dExp = Mul(Mul(grad, Pow(base, exponent)), Log(base))
      Vector(Some(unbroadcast(dBase, ot, bt)), Some(unbroadcast(dExp, ot, et)))

    case Neg(input)  => Vector(Some(Neg(grad)))
    case Sqrt(input) =>
      val two = scalarConst(2.0, typeOf(input).dtype)
      Vector(Some(Div(grad, Mul(two, Sqrt(input)))))
    case Exp(input) => Vector(Some(Mul(grad, Exp(input))))
    case Log(input) => Vector(Some(Div(grad, input)))
    case Abs(input) =>
      val zero = scalarConst(0.0, typeOf(input).dtype)
      val one = scalarConst(1.0, typeOf(input).dtype)
      Vector(Some(Mul(grad, Where(Greater(input, zero), one, Neg(one)))))
    case Clip(input, min, max) =>
      val dtype = typeOf(input).dtype
      val aboveMin = min match
        case Some(v) => Greater(input, scalarConst(v, dtype))
        case None    => greaterTrue(input, dtype)
      val belowMax = max match
        case Some(v) => Less(input, scalarConst(v, dtype))
        case None    => greaterTrue(input, dtype)
      // grad * (input > min && input < max) — expressed via Where
      val mask =
        Where(aboveMin, Where(belowMax, grad, scalarConst(0.0, dtype)), scalarConst(0.0, dtype))
      Vector(Some(mask))

    // ── Activations ─────────────────────────────────────────────────────────
    case Relu(input) =>
      val zero = scalarConst(0.0, typeOf(input).dtype)
      Vector(Some(Where(Greater(input, zero), grad, zero)))

    case LeakyRelu(input, alpha) =>
      val zero = scalarConst(0.0, typeOf(input).dtype)
      val alphaConst = scalarConst(alpha, typeOf(input).dtype)
      Vector(Some(Where(Greater(input, zero), grad, Mul(grad, alphaConst))))

    case Sigmoid(input) =>
      val s = Sigmoid(input)
      val one = scalarConst(1.0, typeOf(input).dtype)
      Vector(Some(Mul(grad, Mul(s, Sub(one, s)))))

    case Tanh(input) =>
      val t = Tanh(input)
      val one = scalarConst(1.0, typeOf(input).dtype)
      Vector(Some(Mul(grad, Sub(one, Mul(t, t)))))

    case Softmax(input, axis) =>
      val s = Softmax(input, axis)
      val sumGradS = ReduceSum(Mul(grad, s), Vector(axis), keepDims = true)
      Vector(Some(Mul(s, Sub(grad, sumGradS))))

    case LogSoftmax(input, axis) =>
      val s = Softmax(input, axis)
      val sumGrad = ReduceSum(grad, Vector(axis), keepDims = true)
      Vector(Some(Sub(grad, Mul(s, sumGrad))))

    case Silu(input) =>
      val s = Sigmoid(input)
      val one = scalarConst(1.0, typeOf(input).dtype)
      // silu'(x) = sigmoid(x) * (1 + x * (1 - sigmoid(x)))
      Vector(Some(Mul(grad, Mul(s, Add(one, Mul(input, Sub(one, s)))))))

    case Elu(input, alpha) =>
      val zero = scalarConst(0.0, typeOf(input).dtype)
      val alphaConst = scalarConst(alpha, typeOf(input).dtype)
      Vector(Some(Where(Greater(input, zero), grad, Mul(grad, Mul(alphaConst, Exp(input))))))

    case Gelu(_, _)   => notSupported("Gelu")
    case HardSwish(_) => notSupported("HardSwish")

    // ── Linear algebra ──────────────────────────────────────────────────────
    case MatMul(left, right, transA, transB) =>
      // 4 combinations of transpose flags
      val (dLeft, dRight) = (transA, transB) match
        case (false, false) =>
          (MatMul(grad, right, false, true), MatMul(left, grad, true, false))
        case (true, false) =>
          (MatMul(right, grad, false, true), MatMul(left, grad, false, false))
        case (false, true) =>
          (MatMul(grad, right, false, false), MatMul(grad, left, true, false))
        case (true, true) =>
          (MatMul(right, grad, true, true), MatMul(grad, left, true, true))
      Vector(Some(dLeft), Some(dRight))

    case Linear(input, weight, bias) =>
      // Linear: output = input @ weight^T + bias
      val dInput = MatMul(grad, weight, false, false) // grad @ weight
      val dWeight = MatMul(grad, input, true, false) // grad^T @ input
      val dBias = bias.map { _ =>
        val gradRank = typeOf(grad).shape.size
        if gradRank <= 1 then grad
        else
          val batchAxes = (0 until gradRank - 1).toVector
          ReduceSum(grad, batchAxes, keepDims = false)
      }
      Vector(Some(dInput), Some(dWeight), dBias).take(MLTreeTransform.childExprs(expr).size)

    case Gemm(a, b, c, alpha, beta, transA, transB) =>
      val alphaConst = scalarConst(alpha, typeOf(a).dtype)
      val betaConst = scalarConst(beta, typeOf(a).dtype)
      val scaledGrad = Mul(grad, alphaConst)
      val (dA, dB) = (transA, transB) match
        case (false, false) =>
          (MatMul(scaledGrad, b, false, true), MatMul(a, scaledGrad, true, false))
        case (true, false) =>
          (MatMul(b, scaledGrad, false, true), MatMul(a, scaledGrad, false, false))
        case (false, true) =>
          (MatMul(scaledGrad, b, false, false), MatMul(scaledGrad, a, true, false))
        case (true, true) =>
          (MatMul(b, scaledGrad, true, true), MatMul(scaledGrad, a, true, true))
      val dC = c.map { cExpr =>
        val ct = typeOf(cExpr)
        val ot = typeOf(expr)
        unbroadcast(Mul(grad, betaConst), ot, ct)
      }
      Vector(Some(dA), Some(dB), dC).take(MLTreeTransform.childExprs(expr).size)

    // ── Convolution ──────────────────────────────────────────────────────────
    case _: Conv          => notSupported("Conv")
    case _: ConvTranspose => notSupported("ConvTranspose")

    // ── Pooling ──────────────────────────────────────────────────────────────
    case _: MaxPool         => notSupported("MaxPool")
    case _: AvgPool         => notSupported("AvgPool")
    case _: GlobalAvgPool   => notSupported("GlobalAvgPool")
    case _: AdaptiveAvgPool => notSupported("AdaptiveAvgPool")

    // ── Normalization ────────────────────────────────────────────────────────
    case _: BatchNorm    => notSupported("BatchNorm")
    case _: LayerNorm    => notSupported("LayerNorm")
    case _: GroupNorm    => notSupported("GroupNorm")
    case _: InstanceNorm => notSupported("InstanceNorm")

    // ── Reduction ────────────────────────────────────────────────────────────
    case ReduceSum(input, axes, keepDims) =>
      val it = typeOf(input)
      val restored = if keepDims then grad else Unsqueeze(grad, axes)
      val inputShape = staticShape(it)
      Vector(Some(Expand(restored, inputShape)))

    case ReduceMean(input, axes, keepDims) =>
      val it = typeOf(input)
      val restored = if keepDims then grad else Unsqueeze(grad, axes)
      val numReduced =
        axes.map(a => dimStaticValue(it.shape(normalizeAxis(a, it.shape.size)))).product
      val scale = scalarConst(1.0 / numReduced, it.dtype)
      val inputShape = staticShape(it)
      Vector(Some(Expand(Mul(restored, scale), inputShape)))

    case _: ReduceMax  => notSupported("ReduceMax")
    case _: ReduceMin  => notSupported("ReduceMin")
    case _: ReduceProd => notSupported("ReduceProd")

    // ── Shape manipulation ──────────────────────────────────────────────────
    case Reshape(input, _) =>
      val inputShape = staticShape(typeOf(input))
      Vector(Some(Reshape(grad, inputShape)))

    case Transpose(input, perm) =>
      val inversePerm = invertPermutation(perm)
      Vector(Some(Transpose(grad, inversePerm)))

    case Flatten(input, _) =>
      val inputShape = staticShape(typeOf(input))
      Vector(Some(Reshape(grad, inputShape)))

    case Squeeze(input, axes) =>
      Vector(Some(Unsqueeze(grad, axes)))

    case Unsqueeze(input, axes) =>
      Vector(Some(Squeeze(grad, axes)))

    case Expand(input, _) =>
      val it = typeOf(input)
      val ot = typeOf(expr)
      // ReduceSum along axes that were expanded
      val reduceAxes = ot.shape.indices.collect {
        case i
            if i < it.shape.size && it.shape(i) == Dim.Static(1) && ot.shape(i) != Dim.Static(1) =>
          i
        case i if i >= it.shape.size => i // leading dims from rank expansion
      }.toVector
      if reduceAxes.isEmpty then Vector(Some(grad))
      else
        val reduced = ReduceSum(grad, reduceAxes, keepDims = true)
        val inputShape = staticShape(it)
        Vector(Some(Reshape(reduced, inputShape)))

    case _: TensorConcat => notSupported("TensorConcat")
    case _: Split        => notSupported("Split")
    case _: TensorSlice  => notSupported("TensorSlice")
    case _: Gather       => notSupported("Gather")
    case _: Scatter      => notSupported("Scatter")
    case _: Pad          => notSupported("Pad")

    // ── Recurrent ────────────────────────────────────────────────────────────
    case _: LSTM => notSupported("LSTM")
    case _: GRU  => notSupported("GRU")

    // ── Attention ────────────────────────────────────────────────────────────
    case _: ScaledDotProductAttention => notSupported("ScaledDotProductAttention")
    case _: MultiHeadAttention        => notSupported("MultiHeadAttention")

    // ── Embedding ────────────────────────────────────────────────────────────
    case _: Embedding => notSupported("Embedding")

    // ── Regularization ───────────────────────────────────────────────────────
    case Dropout(_, _, training) =>
      if training then notSupported("Dropout(training=true)")
      else Vector(Some(grad)) // inference mode: identity

    // ── Loss functions ───────────────────────────────────────────────────────
    case MSELoss(input, target, reduction) =>
      val dtype = typeOf(input).dtype
      val diff = Sub(input, target)
      val two = scalarConst(2.0, dtype)
      val dInput = reduction match
        case Reduction.Mean =>
          val n = totalElements(typeOf(input))
          Mul(Div(two, scalarConst(n.toDouble, dtype)), diff)
        case Reduction.Sum  => Mul(two, diff)
        case Reduction.None => Mul(Mul(grad, two), diff)
      // target gets no gradient
      Vector(Some(dInput), None)

    case L1Loss(input, target, reduction) =>
      val dtype = typeOf(input).dtype
      val zero = scalarConst(0.0, dtype)
      val one = scalarConst(1.0, dtype)
      val sign = Where(Greater(input, target), one, Neg(one))
      val dInput = reduction match
        case Reduction.Mean =>
          val n = totalElements(typeOf(input))
          Div(sign, scalarConst(n.toDouble, dtype))
        case Reduction.Sum  => sign
        case Reduction.None => Mul(grad, sign)
      Vector(Some(dInput), None)

    case CrossEntropyLoss(input, target, weight, reduction) =>
      val dtype = typeOf(input).dtype
      val softmaxed = Softmax(input, -1)
      // dInput = softmax(input) - one_hot(target)
      // For the simplified case: gradient is (softmax - one_hot) / N
      // We express one_hot using Scatter
      val inputType = typeOf(input)
      val numClasses = inputType.shape.last match
        case Dim.Static(n) => n
        case _ => throw Autograd.AutogradException("CrossEntropyLoss requires static class dim")
      val batchShape = inputType.shape.dropRight(1)
      val zerosType = inputType
      // one_hot via scatter: zeros.scatter(axis=-1, indices=target.unsqueeze(-1), src=ones)
      val targetUnsqueezed = Unsqueeze(target, Vector(inputType.shape.size - 1))
      val ones = scalarConst(1.0, dtype)
      val zeros = scalarConst(0.0, dtype)
      val zerosExpanded = Expand(zeros, staticShape(inputType))
      val oneHot = Scatter(
        zerosExpanded,
        targetUnsqueezed,
        Expand(ones, staticShape(typeOf(targetUnsqueezed))),
        inputType.shape.size - 1
      )
      val diff = Sub(softmaxed, oneHot)
      val dInput = reduction match
        case Reduction.Mean =>
          val batchSize = batchShape.map(dimStaticValue).product
          Div(diff, scalarConst(batchSize.toDouble, dtype))
        case Reduction.Sum  => diff
        case Reduction.None => Mul(grad, diff)
      // target and weight get no gradient
      val result = Vector(Some(dInput), None)
      if weight.isDefined then result :+ None else result

    case _: BCELoss           => notSupported("BCELoss")
    case _: BCEWithLogitsLoss => notSupported("BCEWithLogitsLoss")

    // ── Comparison / logical (non-differentiable) ────────────────────────────
    case _: Equal   => nonDifferentiable("Equal")
    case _: Greater => nonDifferentiable("Greater")
    case _: Less    => nonDifferentiable("Less")

    case Where(condition, x, y) =>
      val zero = scalarConst(0.0, typeOf(x).dtype)
      // condition gets no gradient; x gets grad where condition is true; y gets grad where false
      Vector(None, Some(Where(condition, grad, zero)), Some(Where(condition, zero, grad)))

    // ── Type casting ─────────────────────────────────────────────────────────
    case Cast(input, targetDType) =>
      val inputDType = typeOf(input).dtype
      if isFloatDType(inputDType) && isFloatDType(targetDType) then
        Vector(Some(Cast(grad, inputDType)))
      else nonDifferentiable("Cast(non-float)")

    // ── Opaque / custom op ───────────────────────────────────────────────────
    case _: OpaqueOp =>
      throw Autograd.AutogradException("Cannot differentiate through OpaqueOp")

  // ════════════════════════════════════════════════════════════════════════════
  // Helpers
  // ════════════════════════════════════════════════════════════════════════════

  /** Create a scalar constant TensorExpr. */
  private[optimizer] def scalarConst(value: Double, dtype: TensorDType): TensorExpr =
    val bytes = dtype match
      case TensorDType.Float32 =>
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putFloat(value.toFloat)
        buf.array()
      case TensorDType.Float64 =>
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putDouble(value)
        buf.array()
      case _ =>
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putFloat(value.toFloat)
        buf.array()
    Constant(
      s"const_$value",
      TensorType(dtype, Vector.empty),
      TensorData(dtype, Vector.empty, bytes)
    )

  /** Reduce gradient to match target shape by summing along broadcast axes. */
  private[optimizer] def unbroadcast(
      grad: TensorExpr,
      gradType: TensorType,
      targetType: TensorType
  ): TensorExpr =
    if gradType.shape == targetType.shape then return grad

    val rankDiff = gradType.shape.size - targetType.shape.size
    val paddedTarget = Vector.fill(rankDiff)(Dim.Static(1)) ++ targetType.shape

    val reduceAxes = gradType.shape.indices.collect {
      case i if paddedTarget(i) == Dim.Static(1) && gradType.shape(i) != Dim.Static(1) => i
    }.toVector

    val leadingAxes = (0 until rankDiff).toVector
    val allAxes = (leadingAxes ++ reduceAxes).distinct.sorted

    if allAxes.isEmpty then grad
    else
      val reduced = ReduceSum(grad, allAxes, keepDims = true)
      Reshape(reduced, staticShape(targetType))

  /** Extract static shape as Vector[Int] for use in Reshape/Expand. */
  private def staticShape(tt: TensorType): Vector[Int] =
    tt.shape.map(dimStaticValue)

  /** Extract static dimension value. */
  private def dimStaticValue(d: Dim): Int = d match
    case Dim.Static(s)  => s
    case Dim.Dynamic(_) =>
      throw Autograd.AutogradException("Autograd requires static dimensions")

  /** Total number of elements in a tensor type. */
  private def totalElements(tt: TensorType): Long =
    tt.shape.map(dimStaticValue(_).toLong).product

  /** Normalize a possibly-negative axis. */
  private def normalizeAxis(axis: Int, rank: Int): Int =
    if axis >= 0 then axis else rank + axis

  /** Invert a permutation vector. */
  private def invertPermutation(perm: Vector[Int]): Vector[Int] =
    val inv = Array.ofDim[Int](perm.size)
    perm.zipWithIndex.foreach { (p, i) => inv(p) = i }
    inv.toVector

  /** Check if a dtype is floating point. */
  private def isFloatDType(dtype: TensorDType): Boolean = dtype match
    case TensorDType.Float16 | TensorDType.Float32 | TensorDType.Float64 | TensorDType.BFloat16 =>
      true
    case TensorDType.Complex64 | TensorDType.Complex128 => true
    case _                                              => false

  /** Helper: create a "constant true" comparison for Clip when min/max is None. */
  private def greaterTrue(input: TensorExpr, dtype: TensorDType): TensorExpr =
    // input > -inf is always true; just use a very negative number
    Greater(input, scalarConst(-1e38, dtype))

  private def notSupported(op: String): Nothing =
    throw Autograd.AutogradException(s"VJP not yet implemented for $op")

  private def nonDifferentiable(op: String): Nothing =
    throw Autograd.AutogradException(s"Cannot differentiate through $op")
