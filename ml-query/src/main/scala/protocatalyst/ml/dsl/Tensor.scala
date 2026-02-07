package protocatalyst.ml.dsl

import protocatalyst.ml.dsl.Shape._
import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.types._

/** Type-safe tensor wrapper for compile-time shape checking.
  *
  * S is the shape as a tuple of literal Int types (e.g., `16 *: 784 *: EmptyTuple`). D is the
  * element data type. Operations on Tensor produce new Tensors with shapes computed by match types,
  * ensuring shape compatibility at compile time.
  *
  * Example:
  * {{{
  * import Shape._
  * val x = Tensor.input[Mat[16, 784], TensorDType.Float32.type]("x", ...)
  * val w = Tensor.parameter[Mat[256, 784], TensorDType.Float32.type]("w", ...)
  * val h = x.matmul(w.t) // shape: Mat[16, 256] — compile-time verified
  * }}}
  */
final class Tensor[S <: Tuple, D <: TensorDType] private[dsl] (
    private[dsl] val expr: TensorExpr,
    private[dsl] val tensorType: TensorType
):

  // ============================================================================
  // Linear algebra
  // ============================================================================

  /** Matrix multiply: (M, K) x (K, N) → (M, N). Compile error if inner dims mismatch. */
  def matmul[S2 <: Tuple](other: Tensor[S2, D]): Tensor[MatMulShape[S, S2], D] =
    new Tensor[MatMulShape[S, S2], D](
      TensorExpr.MatMul(this.expr, other.expr, transA = false, transB = false),
      other.tensorType
    )

  /** Linear (dense) layer: input @ weight^T + bias */
  def linear(weight: Tensor[?, D], bias: Option[Tensor[?, D]] = None): Tensor[S, D] =
    new Tensor(
      TensorExpr.Linear(this.expr, weight.expr, bias.map(_.expr)),
      this.tensorType
    )

  // ============================================================================
  // Element-wise arithmetic (same shape)
  // ============================================================================

  def +(other: Tensor[S, D]): Tensor[S, D] =
    new Tensor(TensorExpr.Add(this.expr, other.expr), this.tensorType)

  def -(other: Tensor[S, D]): Tensor[S, D] =
    new Tensor(TensorExpr.Sub(this.expr, other.expr), this.tensorType)

  def *(other: Tensor[S, D]): Tensor[S, D] =
    new Tensor(TensorExpr.Mul(this.expr, other.expr), this.tensorType)

  def /(other: Tensor[S, D]): Tensor[S, D] =
    new Tensor(TensorExpr.Div(this.expr, other.expr), this.tensorType)

  // ============================================================================
  // Activation functions (shape-preserving)
  // ============================================================================

  def relu: Tensor[S, D] =
    new Tensor(TensorExpr.Relu(this.expr), this.tensorType)

  def sigmoid: Tensor[S, D] =
    new Tensor(TensorExpr.Sigmoid(this.expr), this.tensorType)

  def tanh: Tensor[S, D] =
    new Tensor(TensorExpr.Tanh(this.expr), this.tensorType)

  def gelu(approximate: Boolean = false): Tensor[S, D] =
    new Tensor(TensorExpr.Gelu(this.expr, approximate), this.tensorType)

  def silu: Tensor[S, D] =
    new Tensor(TensorExpr.Silu(this.expr), this.tensorType)

  def leakyRelu(alpha: Double = 0.01): Tensor[S, D] =
    new Tensor(TensorExpr.LeakyRelu(this.expr, alpha), this.tensorType)

  def elu(alpha: Double = 1.0): Tensor[S, D] =
    new Tensor(TensorExpr.Elu(this.expr, alpha), this.tensorType)

  def hardSwish: Tensor[S, D] =
    new Tensor(TensorExpr.HardSwish(this.expr), this.tensorType)

  // ============================================================================
  // Unary element-wise (shape-preserving)
  // ============================================================================

  def sqrt: Tensor[S, D] =
    new Tensor(TensorExpr.Sqrt(this.expr), this.tensorType)

  def neg: Tensor[S, D] =
    new Tensor(TensorExpr.Neg(this.expr), this.tensorType)

  def abs: Tensor[S, D] =
    new Tensor(TensorExpr.Abs(this.expr), this.tensorType)

  def exp: Tensor[S, D] =
    new Tensor(TensorExpr.Exp(this.expr), this.tensorType)

  def log: Tensor[S, D] =
    new Tensor(TensorExpr.Log(this.expr), this.tensorType)

  def clip(min: Option[Double] = None, max: Option[Double] = None): Tensor[S, D] =
    new Tensor(TensorExpr.Clip(this.expr, min, max), this.tensorType)

  // ============================================================================
  // Shape manipulation
  // ============================================================================

  /** Reshape to a new shape. No compile-time size check (validated at runtime). */
  def reshape[S2 <: Tuple](shape: Vector[Int]): Tensor[S2, D] =
    new Tensor(TensorExpr.Reshape(this.expr, shape), this.tensorType)

  /** Transpose 2D: (M, N) → (N, M) */
  def t(using ev: S <:< (? *: ? *: EmptyTuple)): Tensor[Transpose2D[S], D] =
    new Tensor[Transpose2D[S], D](
      TensorExpr.Transpose(this.expr, Vector(1, 0)),
      this.tensorType
    )

  /** Flatten from axis. */
  def flatten(axis: Int = 1): Tensor[Tuple, D] =
    new Tensor(TensorExpr.Flatten(this.expr, axis), this.tensorType)

  // ============================================================================
  // Normalization
  // ============================================================================

  def layerNorm(normalizedShape: Vector[Int], epsilon: Double = 1e-5): Tensor[S, D] =
    new Tensor(
      TensorExpr.LayerNorm(this.expr, normalizedShape, None, None, epsilon),
      this.tensorType
    )

  // ============================================================================
  // Regularization
  // ============================================================================

  def dropout(ratio: Double, training: Boolean): Tensor[S, D] =
    new Tensor(TensorExpr.Dropout(this.expr, ratio, training), this.tensorType)

  // ============================================================================
  // Softmax / LogSoftmax
  // ============================================================================

  def softmax(axis: Int = -1): Tensor[S, D] =
    new Tensor(TensorExpr.Softmax(this.expr, axis), this.tensorType)

  def logSoftmax(axis: Int = -1): Tensor[S, D] =
    new Tensor(TensorExpr.LogSoftmax(this.expr, axis), this.tensorType)

object Tensor:

  /** Create a named input tensor. */
  def input[S <: Tuple, D <: TensorDType](name: String, tensorType: TensorType): Tensor[S, D] =
    new Tensor(TensorExpr.Input(name, tensorType), tensorType)

  /** Create a named trainable parameter. */
  def parameter[S <: Tuple, D <: TensorDType](
      name: String,
      tensorType: TensorType,
      initializer: Option[Initializer] = None
  ): Tensor[S, D] =
    new Tensor(TensorExpr.Parameter(name, tensorType, initializer), tensorType)

  /** Create a named constant tensor. */
  def constant[S <: Tuple, D <: TensorDType](
      name: String,
      tensorType: TensorType,
      data: TensorData
  ): Tensor[S, D] =
    new Tensor(TensorExpr.Constant(name, tensorType, data), tensorType)

// ============================================================================
// Loss function constructors (free functions since they combine two tensors)
// ============================================================================

/** Cross entropy loss. */
def crossEntropyLoss[S <: Tuple, D <: TensorDType](
    input: Tensor[S, D],
    target: Tensor[?, ?],
    reduction: Reduction = Reduction.Mean
): Tensor[Scalar, D] =
  new Tensor(
    TensorExpr.CrossEntropyLoss(input.expr, target.expr, None, reduction),
    TensorType(input.tensorType.dtype, Vector.empty)
  )

/** Mean squared error loss. */
def mseLoss[S <: Tuple, D <: TensorDType](
    input: Tensor[S, D],
    target: Tensor[?, ?],
    reduction: Reduction = Reduction.Mean
): Tensor[Scalar, D] =
  new Tensor(
    TensorExpr.MSELoss(input.expr, target.expr, reduction),
    TensorType(input.tensorType.dtype, Vector.empty)
  )
