package protocatalyst.ml.macros

import scala.quoted._

import protocatalyst.ml.artifact._
import protocatalyst.ml.expr._
import protocatalyst.ml.graph._
import protocatalyst.ml.types._

/** ToExpr instances for lifting ML IR types to compile-time expressions.
  *
  * Analogous to ProtoLiftables for SQL. These instances enable embedding optimized compute graphs
  * as constants in bytecode during macro expansion.
  */
object MLProtoLiftables:

  // ============================================================
  // Collection helpers
  // ============================================================

  given vectorToExpr[T: ToExpr: Type]: ToExpr[Vector[T]] with
    def apply(v: Vector[T])(using Quotes): Expr[Vector[T]] =
      '{ Vector(${ Varargs(v.map(Expr(_))) }*) }

  given tuple2ToExpr[A: ToExpr: Type, B: ToExpr: Type]: ToExpr[(A, B)] with
    def apply(t: (A, B))(using Quotes): Expr[(A, B)] =
      '{ (${ Expr(t._1) }, ${ Expr(t._2) }) }

  given mapToExpr[K: ToExpr: Type, V: ToExpr: Type]: ToExpr[Map[K, V]] with
    def apply(m: Map[K, V])(using Quotes): Expr[Map[K, V]] =
      '{ Map(${ Varargs(m.toSeq.map(kv => Expr(kv))) }*) }

  // ============================================================
  // TensorDType
  // ============================================================

  given ToExpr[TensorDType] with
    def apply(dt: TensorDType)(using Quotes): Expr[TensorDType] = dt match
      case TensorDType.Float16    => '{ TensorDType.Float16 }
      case TensorDType.Float32    => '{ TensorDType.Float32 }
      case TensorDType.Float64    => '{ TensorDType.Float64 }
      case TensorDType.BFloat16   => '{ TensorDType.BFloat16 }
      case TensorDType.Int8       => '{ TensorDType.Int8 }
      case TensorDType.Int16      => '{ TensorDType.Int16 }
      case TensorDType.Int32      => '{ TensorDType.Int32 }
      case TensorDType.Int64      => '{ TensorDType.Int64 }
      case TensorDType.UInt8      => '{ TensorDType.UInt8 }
      case TensorDType.Bool       => '{ TensorDType.Bool }
      case TensorDType.Complex64  => '{ TensorDType.Complex64 }
      case TensorDType.Complex128 => '{ TensorDType.Complex128 }

  // ============================================================
  // Dim
  // ============================================================

  given ToExpr[Dim] with
    def apply(d: Dim)(using Quotes): Expr[Dim] = d match
      case Dim.Static(size)  => '{ Dim.Static(${ Expr(size) }) }
      case Dim.Dynamic(name) => '{ Dim.Dynamic(${ Expr(name) }) }

  // ============================================================
  // DataLayout
  // ============================================================

  given ToExpr[DataLayout] with
    def apply(dl: DataLayout)(using Quotes): Expr[DataLayout] = dl match
      case DataLayout.Default => '{ DataLayout.Default }
      case DataLayout.NCHW    => '{ DataLayout.NCHW }
      case DataLayout.NHWC    => '{ DataLayout.NHWC }

  // ============================================================
  // TensorType
  // ============================================================

  given ToExpr[TensorType] with
    def apply(tt: TensorType)(using Quotes): Expr[TensorType] =
      '{ TensorType(${ Expr(tt.dtype) }, ${ Expr(tt.shape) }, ${ Expr(tt.layout) }) }

  // ============================================================
  // TensorData
  // ============================================================

  given ToExpr[TensorData] with
    def apply(td: TensorData)(using Quotes): Expr[TensorData] =
      '{ TensorData(${ Expr(td.dtype) }, ${ Expr(td.shape) }, ${ Expr(td.rawBytes) }) }

  // ============================================================
  // Initializer
  // ============================================================

  given ToExpr[Initializer] with
    def apply(init: Initializer)(using Quotes): Expr[Initializer] = init match
      case Initializer.Zeros                 => '{ Initializer.Zeros }
      case Initializer.Ones                  => '{ Initializer.Ones }
      case Initializer.Xavier(gain)          => '{ Initializer.Xavier(${ Expr(gain) }) }
      case Initializer.Kaiming(mode, nonlin) =>
        '{ Initializer.Kaiming(${ Expr(mode) }, ${ Expr(nonlin) }) }
      case Initializer.Normal(mean, std) =>
        '{ Initializer.Normal(${ Expr(mean) }, ${ Expr(std) }) }
      case Initializer.Uniform(low, high) =>
        '{ Initializer.Uniform(${ Expr(low) }, ${ Expr(high) }) }

  // ============================================================
  // PadMode
  // ============================================================

  given ToExpr[PadMode] with
    def apply(pm: PadMode)(using Quotes): Expr[PadMode] = pm match
      case PadMode.Constant => '{ PadMode.Constant }
      case PadMode.Reflect  => '{ PadMode.Reflect }
      case PadMode.Edge     => '{ PadMode.Edge }

  // ============================================================
  // Reduction
  // ============================================================

  given ToExpr[Reduction] with
    def apply(r: Reduction)(using Quotes): Expr[Reduction] = r match
      case Reduction.Mean => '{ Reduction.Mean }
      case Reduction.Sum  => '{ Reduction.Sum }
      case Reduction.None => '{ Reduction.None }

  // ============================================================
  // OpAttribute
  // ============================================================

  given ToExpr[OpAttribute] with
    def apply(attr: OpAttribute)(using Quotes): Expr[OpAttribute] = attr match
      case OpAttribute.IntAttr(value)     => '{ OpAttribute.IntAttr(${ Expr(value) }) }
      case OpAttribute.FloatAttr(value)   => '{ OpAttribute.FloatAttr(${ Expr(value) }) }
      case OpAttribute.StringAttr(value)  => '{ OpAttribute.StringAttr(${ Expr(value) }) }
      case OpAttribute.IntsAttr(values)   => '{ OpAttribute.IntsAttr(${ Expr(values) }) }
      case OpAttribute.FloatsAttr(values) => '{ OpAttribute.FloatsAttr(${ Expr(values) }) }
      case OpAttribute.TensorAttr(value)  => '{ OpAttribute.TensorAttr(${ Expr(value) }) }

  // ============================================================
  // TensorExpr (~71 variants)
  // ============================================================

  given ToExpr[TensorExpr] with
    def apply(e: TensorExpr)(using Quotes): Expr[TensorExpr] = e match

      // --- Leaf nodes ---
      case TensorExpr.Input(name, tensorType) =>
        '{ TensorExpr.Input(${ Expr(name) }, ${ Expr(tensorType) }) }
      case TensorExpr.Constant(name, tensorType, data) =>
        '{ TensorExpr.Constant(${ Expr(name) }, ${ Expr(tensorType) }, ${ Expr(data) }) }
      case TensorExpr.Parameter(name, tensorType, initializer) =>
        '{ TensorExpr.Parameter(${ Expr(name) }, ${ Expr(tensorType) }, ${ Expr(initializer) }) }

      // --- Linear algebra ---
      case TensorExpr.MatMul(left, right, transA, transB) =>
        '{
          TensorExpr.MatMul(
            ${ Expr(left) },
            ${ Expr(right) },
            ${ Expr(transA) },
            ${ Expr(transB) }
          )
        }
      case TensorExpr.Gemm(a, b, c, alpha, beta, transA, transB) =>
        '{
          TensorExpr.Gemm(
            ${ Expr(a) },
            ${ Expr(b) },
            ${ Expr(c) },
            ${ Expr(alpha) },
            ${ Expr(beta) },
            ${ Expr(transA) },
            ${ Expr(transB) }
          )
        }
      case TensorExpr.Linear(input, weight, bias) =>
        '{ TensorExpr.Linear(${ Expr(input) }, ${ Expr(weight) }, ${ Expr(bias) }) }

      // --- Convolution ---
      case TensorExpr.Conv(input, weight, bias, strides, pads, dilations, group) =>
        '{
          TensorExpr.Conv(
            ${ Expr(input) },
            ${ Expr(weight) },
            ${ Expr(bias) },
            ${ Expr(strides) },
            ${ Expr(pads) },
            ${ Expr(dilations) },
            ${ Expr(group) }
          )
        }
      case TensorExpr.ConvTranspose(
            input,
            weight,
            bias,
            strides,
            pads,
            outputPads,
            dilations,
            group
          ) =>
        '{
          TensorExpr.ConvTranspose(
            ${ Expr(input) },
            ${ Expr(weight) },
            ${ Expr(bias) },
            ${ Expr(strides) },
            ${ Expr(pads) },
            ${ Expr(outputPads) },
            ${ Expr(dilations) },
            ${ Expr(group) }
          )
        }

      // --- Activations ---
      case TensorExpr.Relu(input) =>
        '{ TensorExpr.Relu(${ Expr(input) }) }
      case TensorExpr.LeakyRelu(input, alpha) =>
        '{ TensorExpr.LeakyRelu(${ Expr(input) }, ${ Expr(alpha) }) }
      case TensorExpr.Sigmoid(input) =>
        '{ TensorExpr.Sigmoid(${ Expr(input) }) }
      case TensorExpr.Tanh(input) =>
        '{ TensorExpr.Tanh(${ Expr(input) }) }
      case TensorExpr.Softmax(input, axis) =>
        '{ TensorExpr.Softmax(${ Expr(input) }, ${ Expr(axis) }) }
      case TensorExpr.LogSoftmax(input, axis) =>
        '{ TensorExpr.LogSoftmax(${ Expr(input) }, ${ Expr(axis) }) }
      case TensorExpr.Gelu(input, approximate) =>
        '{ TensorExpr.Gelu(${ Expr(input) }, ${ Expr(approximate) }) }
      case TensorExpr.Silu(input) =>
        '{ TensorExpr.Silu(${ Expr(input) }) }
      case TensorExpr.Elu(input, alpha) =>
        '{ TensorExpr.Elu(${ Expr(input) }, ${ Expr(alpha) }) }
      case TensorExpr.HardSwish(input) =>
        '{ TensorExpr.HardSwish(${ Expr(input) }) }

      // --- Element-wise arithmetic ---
      case TensorExpr.Add(left, right) =>
        '{ TensorExpr.Add(${ Expr(left) }, ${ Expr(right) }) }
      case TensorExpr.Sub(left, right) =>
        '{ TensorExpr.Sub(${ Expr(left) }, ${ Expr(right) }) }
      case TensorExpr.Mul(left, right) =>
        '{ TensorExpr.Mul(${ Expr(left) }, ${ Expr(right) }) }
      case TensorExpr.Div(left, right) =>
        '{ TensorExpr.Div(${ Expr(left) }, ${ Expr(right) }) }
      case TensorExpr.Pow(base, exponent) =>
        '{ TensorExpr.Pow(${ Expr(base) }, ${ Expr(exponent) }) }
      case TensorExpr.Sqrt(input) =>
        '{ TensorExpr.Sqrt(${ Expr(input) }) }
      case TensorExpr.Neg(input) =>
        '{ TensorExpr.Neg(${ Expr(input) }) }
      case TensorExpr.Abs(input) =>
        '{ TensorExpr.Abs(${ Expr(input) }) }
      case TensorExpr.Exp(input) =>
        '{ TensorExpr.Exp(${ Expr(input) }) }
      case TensorExpr.Log(input) =>
        '{ TensorExpr.Log(${ Expr(input) }) }
      case TensorExpr.Clip(input, min, max) =>
        '{ TensorExpr.Clip(${ Expr(input) }, ${ Expr(min) }, ${ Expr(max) }) }

      // --- Pooling ---
      case TensorExpr.MaxPool(input, kernelSize, strides, pads) =>
        '{
          TensorExpr.MaxPool(
            ${ Expr(input) },
            ${ Expr(kernelSize) },
            ${ Expr(strides) },
            ${ Expr(pads) }
          )
        }
      case TensorExpr.AvgPool(input, kernelSize, strides, pads, countIncludePad) =>
        '{
          TensorExpr.AvgPool(
            ${ Expr(input) },
            ${ Expr(kernelSize) },
            ${ Expr(strides) },
            ${ Expr(pads) },
            ${ Expr(countIncludePad) }
          )
        }
      case TensorExpr.GlobalAvgPool(input) =>
        '{ TensorExpr.GlobalAvgPool(${ Expr(input) }) }
      case TensorExpr.AdaptiveAvgPool(input, outputSize) =>
        '{ TensorExpr.AdaptiveAvgPool(${ Expr(input) }, ${ Expr(outputSize) }) }

      // --- Normalization ---
      case TensorExpr.BatchNorm(
            input,
            scale,
            bias,
            runningMean,
            runningVar,
            epsilon,
            momentum,
            training
          ) =>
        '{
          TensorExpr.BatchNorm(
            ${ Expr(input) },
            ${ Expr(scale) },
            ${ Expr(bias) },
            ${ Expr(runningMean) },
            ${ Expr(runningVar) },
            ${ Expr(epsilon) },
            ${ Expr(momentum) },
            ${ Expr(training) }
          )
        }
      case TensorExpr.LayerNorm(input, normalizedShape, weight, bias, epsilon) =>
        '{
          TensorExpr.LayerNorm(
            ${ Expr(input) },
            ${ Expr(normalizedShape) },
            ${ Expr(weight) },
            ${ Expr(bias) },
            ${ Expr(epsilon) }
          )
        }
      case TensorExpr.GroupNorm(input, numGroups, weight, bias, epsilon) =>
        '{
          TensorExpr.GroupNorm(
            ${ Expr(input) },
            ${ Expr(numGroups) },
            ${ Expr(weight) },
            ${ Expr(bias) },
            ${ Expr(epsilon) }
          )
        }
      case TensorExpr.InstanceNorm(input, scale, bias, epsilon) =>
        '{
          TensorExpr.InstanceNorm(
            ${ Expr(input) },
            ${ Expr(scale) },
            ${ Expr(bias) },
            ${ Expr(epsilon) }
          )
        }

      // --- Reduction ---
      case TensorExpr.ReduceSum(input, axes, keepDims) =>
        '{ TensorExpr.ReduceSum(${ Expr(input) }, ${ Expr(axes) }, ${ Expr(keepDims) }) }
      case TensorExpr.ReduceMean(input, axes, keepDims) =>
        '{ TensorExpr.ReduceMean(${ Expr(input) }, ${ Expr(axes) }, ${ Expr(keepDims) }) }
      case TensorExpr.ReduceMax(input, axes, keepDims) =>
        '{ TensorExpr.ReduceMax(${ Expr(input) }, ${ Expr(axes) }, ${ Expr(keepDims) }) }
      case TensorExpr.ReduceMin(input, axes, keepDims) =>
        '{ TensorExpr.ReduceMin(${ Expr(input) }, ${ Expr(axes) }, ${ Expr(keepDims) }) }
      case TensorExpr.ReduceProd(input, axes, keepDims) =>
        '{ TensorExpr.ReduceProd(${ Expr(input) }, ${ Expr(axes) }, ${ Expr(keepDims) }) }

      // --- Shape manipulation ---
      case TensorExpr.Reshape(input, shape) =>
        '{ TensorExpr.Reshape(${ Expr(input) }, ${ Expr(shape) }) }
      case TensorExpr.Transpose(input, perm) =>
        '{ TensorExpr.Transpose(${ Expr(input) }, ${ Expr(perm) }) }
      case TensorExpr.Flatten(input, axis) =>
        '{ TensorExpr.Flatten(${ Expr(input) }, ${ Expr(axis) }) }
      case TensorExpr.Squeeze(input, axes) =>
        '{ TensorExpr.Squeeze(${ Expr(input) }, ${ Expr(axes) }) }
      case TensorExpr.Unsqueeze(input, axes) =>
        '{ TensorExpr.Unsqueeze(${ Expr(input) }, ${ Expr(axes) }) }
      case TensorExpr.TensorConcat(inputs, axis) =>
        '{ TensorExpr.TensorConcat(${ Expr(inputs) }, ${ Expr(axis) }) }
      case TensorExpr.Split(input, axis, splitSizes) =>
        '{ TensorExpr.Split(${ Expr(input) }, ${ Expr(axis) }, ${ Expr(splitSizes) }) }
      case TensorExpr.TensorSlice(input, starts, ends, axes, steps) =>
        '{
          TensorExpr.TensorSlice(
            ${ Expr(input) },
            ${ Expr(starts) },
            ${ Expr(ends) },
            ${ Expr(axes) },
            ${ Expr(steps) }
          )
        }
      case TensorExpr.Gather(input, indices, axis) =>
        '{ TensorExpr.Gather(${ Expr(input) }, ${ Expr(indices) }, ${ Expr(axis) }) }
      case TensorExpr.Scatter(input, indices, updates, axis) =>
        '{
          TensorExpr.Scatter(
            ${ Expr(input) },
            ${ Expr(indices) },
            ${ Expr(updates) },
            ${ Expr(axis) }
          )
        }
      case TensorExpr.Pad(input, pads, mode, constantValue) =>
        '{
          TensorExpr.Pad(
            ${ Expr(input) },
            ${ Expr(pads) },
            ${ Expr(mode) },
            ${ Expr(constantValue) }
          )
        }
      case TensorExpr.Expand(input, shape) =>
        '{ TensorExpr.Expand(${ Expr(input) }, ${ Expr(shape) }) }

      // --- Recurrent ---
      case TensorExpr.LSTM(
            input,
            weightIh,
            weightHh,
            bias,
            hiddenSize,
            numLayers,
            bidirectional,
            dropout
          ) =>
        '{
          TensorExpr.LSTM(
            ${ Expr(input) },
            ${ Expr(weightIh) },
            ${ Expr(weightHh) },
            ${ Expr(bias) },
            ${ Expr(hiddenSize) },
            ${ Expr(numLayers) },
            ${ Expr(bidirectional) },
            ${ Expr(dropout) }
          )
        }
      case TensorExpr.GRU(
            input,
            weightIh,
            weightHh,
            bias,
            hiddenSize,
            numLayers,
            bidirectional,
            dropout
          ) =>
        '{
          TensorExpr.GRU(
            ${ Expr(input) },
            ${ Expr(weightIh) },
            ${ Expr(weightHh) },
            ${ Expr(bias) },
            ${ Expr(hiddenSize) },
            ${ Expr(numLayers) },
            ${ Expr(bidirectional) },
            ${ Expr(dropout) }
          )
        }

      // --- Attention ---
      case TensorExpr.ScaledDotProductAttention(query, key, value, mask, dropout, isCausal) =>
        '{
          TensorExpr.ScaledDotProductAttention(
            ${ Expr(query) },
            ${ Expr(key) },
            ${ Expr(value) },
            ${ Expr(mask) },
            ${ Expr(dropout) },
            ${ Expr(isCausal) }
          )
        }
      case TensorExpr.MultiHeadAttention(
            query,
            key,
            value,
            wQ,
            wK,
            wV,
            wO,
            numHeads,
            mask,
            dropout
          ) =>
        '{
          TensorExpr.MultiHeadAttention(
            ${ Expr(query) },
            ${ Expr(key) },
            ${ Expr(value) },
            ${ Expr(wQ) },
            ${ Expr(wK) },
            ${ Expr(wV) },
            ${ Expr(wO) },
            ${ Expr(numHeads) },
            ${ Expr(mask) },
            ${ Expr(dropout) }
          )
        }

      // --- Embedding ---
      case TensorExpr.Embedding(indices, weight, paddingIdx) =>
        '{ TensorExpr.Embedding(${ Expr(indices) }, ${ Expr(weight) }, ${ Expr(paddingIdx) }) }

      // --- Regularization ---
      case TensorExpr.Dropout(input, ratio, training) =>
        '{ TensorExpr.Dropout(${ Expr(input) }, ${ Expr(ratio) }, ${ Expr(training) }) }

      // --- Loss functions ---
      case TensorExpr.CrossEntropyLoss(input, target, weight, reduction) =>
        '{
          TensorExpr.CrossEntropyLoss(
            ${ Expr(input) },
            ${ Expr(target) },
            ${ Expr(weight) },
            ${ Expr(reduction) }
          )
        }
      case TensorExpr.MSELoss(input, target, reduction) =>
        '{ TensorExpr.MSELoss(${ Expr(input) }, ${ Expr(target) }, ${ Expr(reduction) }) }
      case TensorExpr.L1Loss(input, target, reduction) =>
        '{ TensorExpr.L1Loss(${ Expr(input) }, ${ Expr(target) }, ${ Expr(reduction) }) }
      case TensorExpr.BCELoss(input, target, weight, reduction) =>
        '{
          TensorExpr.BCELoss(
            ${ Expr(input) },
            ${ Expr(target) },
            ${ Expr(weight) },
            ${ Expr(reduction) }
          )
        }
      case TensorExpr.BCEWithLogitsLoss(input, target, weight, reduction) =>
        '{
          TensorExpr.BCEWithLogitsLoss(
            ${ Expr(input) },
            ${ Expr(target) },
            ${ Expr(weight) },
            ${ Expr(reduction) }
          )
        }

      // --- Comparison / logical ---
      case TensorExpr.Equal(left, right) =>
        '{ TensorExpr.Equal(${ Expr(left) }, ${ Expr(right) }) }
      case TensorExpr.Greater(left, right) =>
        '{ TensorExpr.Greater(${ Expr(left) }, ${ Expr(right) }) }
      case TensorExpr.Less(left, right) =>
        '{ TensorExpr.Less(${ Expr(left) }, ${ Expr(right) }) }
      case TensorExpr.Where(condition, x, y) =>
        '{ TensorExpr.Where(${ Expr(condition) }, ${ Expr(x) }, ${ Expr(y) }) }

      // --- Type casting ---
      case TensorExpr.Cast(input, targetDType) =>
        '{ TensorExpr.Cast(${ Expr(input) }, ${ Expr(targetDType) }) }

      // --- Opaque / custom op ---
      case TensorExpr.OpaqueOp(name, inputs, attributes, outputType) =>
        '{
          TensorExpr.OpaqueOp(
            ${ Expr(name) },
            ${ Expr(inputs) },
            ${ Expr(attributes) },
            ${ Expr(outputType) }
          )
        }

  // ============================================================
  // Graph types
  // ============================================================

  given ToExpr[GraphIO] with
    def apply(io: GraphIO)(using Quotes): Expr[GraphIO] =
      '{ GraphIO(${ Expr(io.name) }, ${ Expr(io.tensorType) }) }

  given ToExpr[NamedNode] with
    def apply(n: NamedNode)(using Quotes): Expr[NamedNode] =
      '{ NamedNode(${ Expr(n.name) }, ${ Expr(n.expr) }, ${ Expr(n.outputType) }) }

  given ToExpr[ComputeGraph] with
    def apply(g: ComputeGraph)(using Quotes): Expr[ComputeGraph] =
      '{
        ComputeGraph(
          ${ Expr(g.name) },
          ${ Expr(g.inputs) },
          ${ Expr(g.outputs) },
          ${ Expr(g.nodes) },
          ${ Expr(g.opsetVersion) }
        )
      }

  // ============================================================
  // Artifact types
  // ============================================================

  given ToExpr[MLArtifactVersion] with
    def apply(v: MLArtifactVersion)(using Quotes): Expr[MLArtifactVersion] =
      '{ MLArtifactVersion(${ Expr(v.major) }, ${ Expr(v.minor) }, ${ Expr(v.patch) }) }

  given ToExpr[MLSourceInfo] with
    def apply(si: MLSourceInfo)(using Quotes): Expr[MLSourceInfo] =
      '{ MLSourceInfo(${ Expr(si.sourceFile) }, ${ Expr(si.lineNumber) }) }

  given ToExpr[CompiledMLArtifact] with
    def apply(a: CompiledMLArtifact)(using Quotes): Expr[CompiledMLArtifact] =
      '{
        CompiledMLArtifact(
          ${ Expr(a.formatVersion) },
          ${ Expr(a.protocatalystVersion) },
          ${ Expr(a.compiledAt) },
          ${ Expr(a.contentHash) },
          ${ Expr(a.graph) },
          ${ Expr(a.sourceInfo) }
        )
      }
