package protocatalyst.ml.expr

import java.io.Serializable

import protocatalyst.ml.types._

/** Compile-time tensor expression/operation representation.
  *
  * Analogous to ProtoExpr for SQL. Each variant is a node in a computation DAG. Nodes reference
  * inputs by TensorExpr (structural sharing through references makes the graph a DAG).
  */
enum TensorExpr extends Serializable:

  // ═══════════════════════════════════════════════
  // Leaf nodes (data sources)
  // ═══════════════════════════════════════════════

  /** Named input placeholder (function parameter). */
  case Input(name: String, tensorType: TensorType)

  /** Constant tensor with known data. */
  case Constant(name: String, tensorType: TensorType, data: TensorData)

  /** Trainable parameter/weight. */
  case Parameter(name: String, tensorType: TensorType, initializer: Option[Initializer])

  // ═══════════════════════════════════════════════
  // Linear algebra
  // ═══════════════════════════════════════════════

  /** Matrix multiply: (M, K) x (K, N) -> (M, N), generalized to batched. */
  case MatMul(left: TensorExpr, right: TensorExpr, transA: Boolean, transB: Boolean)

  /** General matrix multiply: alpha * A @ B + beta * C */
  case Gemm(
      a: TensorExpr,
      b: TensorExpr,
      c: Option[TensorExpr],
      alpha: Double,
      beta: Double,
      transA: Boolean,
      transB: Boolean
  )

  /** Linear (dense) layer: x @ W^T + bias */
  case Linear(input: TensorExpr, weight: TensorExpr, bias: Option[TensorExpr])

  // ═══════════════════════════════════════════════
  // Convolution
  // ═══════════════════════════════════════════════

  case Conv(
      input: TensorExpr,
      weight: TensorExpr,
      bias: Option[TensorExpr],
      strides: Vector[Int],
      pads: Vector[Int],
      dilations: Vector[Int],
      group: Int
  )

  case ConvTranspose(
      input: TensorExpr,
      weight: TensorExpr,
      bias: Option[TensorExpr],
      strides: Vector[Int],
      pads: Vector[Int],
      outputPads: Vector[Int],
      dilations: Vector[Int],
      group: Int
  )

  // ═══════════════════════════════════════════════
  // Activation functions
  // ═══════════════════════════════════════════════

  case Relu(input: TensorExpr)
  case LeakyRelu(input: TensorExpr, alpha: Double)
  case Sigmoid(input: TensorExpr)
  case Tanh(input: TensorExpr)
  case Softmax(input: TensorExpr, axis: Int)
  case LogSoftmax(input: TensorExpr, axis: Int)
  case Gelu(input: TensorExpr, approximate: Boolean)
  case Silu(input: TensorExpr)
  case Elu(input: TensorExpr, alpha: Double)
  case HardSwish(input: TensorExpr)

  // ═══════════════════════════════════════════════
  // Element-wise arithmetic
  // ═══════════════════════════════════════════════

  case Add(left: TensorExpr, right: TensorExpr)
  case Sub(left: TensorExpr, right: TensorExpr)
  case Mul(left: TensorExpr, right: TensorExpr)
  case Div(left: TensorExpr, right: TensorExpr)
  case Pow(base: TensorExpr, exponent: TensorExpr)
  case Sqrt(input: TensorExpr)
  case Neg(input: TensorExpr)
  case Abs(input: TensorExpr)
  case Exp(input: TensorExpr)
  case Log(input: TensorExpr)
  case Clip(input: TensorExpr, min: Option[Double], max: Option[Double])

  // ═══════════════════════════════════════════════
  // Pooling
  // ═══════════════════════════════════════════════

  case MaxPool(
      input: TensorExpr,
      kernelSize: Vector[Int],
      strides: Vector[Int],
      pads: Vector[Int]
  )

  case AvgPool(
      input: TensorExpr,
      kernelSize: Vector[Int],
      strides: Vector[Int],
      pads: Vector[Int],
      countIncludePad: Boolean
  )

  case GlobalAvgPool(input: TensorExpr)

  case AdaptiveAvgPool(input: TensorExpr, outputSize: Vector[Int])

  // ═══════════════════════════════════════════════
  // Normalization
  // ═══════════════════════════════════════════════

  case BatchNorm(
      input: TensorExpr,
      scale: TensorExpr,
      bias: TensorExpr,
      runningMean: TensorExpr,
      runningVar: TensorExpr,
      epsilon: Double,
      momentum: Double,
      training: Boolean
  )

  case LayerNorm(
      input: TensorExpr,
      normalizedShape: Vector[Int],
      weight: Option[TensorExpr],
      bias: Option[TensorExpr],
      epsilon: Double
  )

  case GroupNorm(
      input: TensorExpr,
      numGroups: Int,
      weight: Option[TensorExpr],
      bias: Option[TensorExpr],
      epsilon: Double
  )

  case InstanceNorm(
      input: TensorExpr,
      scale: TensorExpr,
      bias: TensorExpr,
      epsilon: Double
  )

  // ═══════════════════════════════════════════════
  // Reduction
  // ═══════════════════════════════════════════════

  case ReduceSum(input: TensorExpr, axes: Vector[Int], keepDims: Boolean)
  case ReduceMean(input: TensorExpr, axes: Vector[Int], keepDims: Boolean)
  case ReduceMax(input: TensorExpr, axes: Vector[Int], keepDims: Boolean)
  case ReduceMin(input: TensorExpr, axes: Vector[Int], keepDims: Boolean)
  case ReduceProd(input: TensorExpr, axes: Vector[Int], keepDims: Boolean)

  // ═══════════════════════════════════════════════
  // Shape manipulation
  // ═══════════════════════════════════════════════

  case Reshape(input: TensorExpr, shape: Vector[Int])
  case Transpose(input: TensorExpr, perm: Vector[Int])
  case Flatten(input: TensorExpr, axis: Int)
  case Squeeze(input: TensorExpr, axes: Vector[Int])
  case Unsqueeze(input: TensorExpr, axes: Vector[Int])
  case TensorConcat(inputs: Vector[TensorExpr], axis: Int)
  case Split(input: TensorExpr, axis: Int, splitSizes: Vector[Int])
  case TensorSlice(
      input: TensorExpr,
      starts: Vector[Int],
      ends: Vector[Int],
      axes: Vector[Int],
      steps: Vector[Int]
  )
  case Gather(input: TensorExpr, indices: TensorExpr, axis: Int)
  case Scatter(input: TensorExpr, indices: TensorExpr, updates: TensorExpr, axis: Int)
  case Pad(input: TensorExpr, pads: Vector[Int], mode: PadMode, constantValue: Double)
  case Expand(input: TensorExpr, shape: Vector[Int])

  // ═══════════════════════════════════════════════
  // Recurrent
  // ═══════════════════════════════════════════════

  case LSTM(
      input: TensorExpr,
      weightIh: TensorExpr,
      weightHh: TensorExpr,
      bias: Option[TensorExpr],
      hiddenSize: Int,
      numLayers: Int,
      bidirectional: Boolean,
      dropout: Double
  )

  case GRU(
      input: TensorExpr,
      weightIh: TensorExpr,
      weightHh: TensorExpr,
      bias: Option[TensorExpr],
      hiddenSize: Int,
      numLayers: Int,
      bidirectional: Boolean,
      dropout: Double
  )

  // ═══════════════════════════════════════════════
  // Attention
  // ═══════════════════════════════════════════════

  case ScaledDotProductAttention(
      query: TensorExpr,
      key: TensorExpr,
      value: TensorExpr,
      mask: Option[TensorExpr],
      dropout: Double,
      isCausal: Boolean
  )

  case MultiHeadAttention(
      query: TensorExpr,
      key: TensorExpr,
      value: TensorExpr,
      wQ: TensorExpr,
      wK: TensorExpr,
      wV: TensorExpr,
      wO: TensorExpr,
      numHeads: Int,
      mask: Option[TensorExpr],
      dropout: Double
  )

  // ═══════════════════════════════════════════════
  // Embedding
  // ═══════════════════════════════════════════════

  case Embedding(indices: TensorExpr, weight: TensorExpr, paddingIdx: Option[Int])

  // ═══════════════════════════════════════════════
  // Regularization
  // ═══════════════════════════════════════════════

  case Dropout(input: TensorExpr, ratio: Double, training: Boolean)

  // ═══════════════════════════════════════════════
  // Loss functions
  // ═══════════════════════════════════════════════

  case CrossEntropyLoss(
      input: TensorExpr,
      target: TensorExpr,
      weight: Option[TensorExpr],
      reduction: Reduction
  )

  case MSELoss(input: TensorExpr, target: TensorExpr, reduction: Reduction)
  case L1Loss(input: TensorExpr, target: TensorExpr, reduction: Reduction)

  case BCELoss(
      input: TensorExpr,
      target: TensorExpr,
      weight: Option[TensorExpr],
      reduction: Reduction
  )

  case BCEWithLogitsLoss(
      input: TensorExpr,
      target: TensorExpr,
      weight: Option[TensorExpr],
      reduction: Reduction
  )

  // ═══════════════════════════════════════════════
  // Comparison / logical
  // ═══════════════════════════════════════════════

  case Equal(left: TensorExpr, right: TensorExpr)
  case Greater(left: TensorExpr, right: TensorExpr)
  case Less(left: TensorExpr, right: TensorExpr)
  case Where(condition: TensorExpr, x: TensorExpr, y: TensorExpr)

  // ═══════════════════════════════════════════════
  // Type casting
  // ═══════════════════════════════════════════════

  case Cast(input: TensorExpr, targetDType: TensorDType)

  // ═══════════════════════════════════════════════
  // Opaque / custom op
  // ═══════════════════════════════════════════════

  case OpaqueOp(
      name: String,
      inputs: Vector[TensorExpr],
      attributes: Map[String, OpAttribute],
      outputType: TensorType
  )
