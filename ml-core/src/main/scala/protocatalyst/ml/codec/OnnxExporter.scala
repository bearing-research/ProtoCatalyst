package protocatalyst.ml.codec

import java.util.IdentityHashMap

import scala.collection.mutable

import com.google.protobuf.ByteString
import onnx.{Onnx => ox}

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph._
import protocatalyst.ml.types._

/** Exports a ProtoCatalyst ComputeGraph to ONNX ModelProto binary format (opset 17). */
object OnnxExporter:

  private val DefaultOpset = 17
  private val IrVersion = 8L // ONNX IR version supporting opset 17

  // ============================================================================
  // Public API
  // ============================================================================

  /** Export a ComputeGraph as ONNX model binary bytes. */
  def toBytes(graph: ComputeGraph): Array[Byte] =
    toOnnxModel(graph).toByteArray

  /** Export a ComputeGraph as an ONNX ModelProto (for inspection/testing). */
  def toOnnxModel(graph: ComputeGraph): ox.ModelProto =
    val ctx = ExportContext()
    val onnxGraph = buildGraph(graph, ctx)

    val mb = ox.ModelProto.newBuilder()
    mb.setIrVersion(IrVersion)
    mb.addOpsetImport(
      ox.OperatorSetIdProto.newBuilder().setDomain("").setVersion(DefaultOpset)
    )
    if ctx.hasCustomDomain then
      mb.addOpsetImport(
        ox.OperatorSetIdProto.newBuilder().setDomain("protocatalyst.custom").setVersion(1)
      )
    mb.setProducerName("ProtoCatalyst")
    mb.setGraph(onnxGraph)
    mb.build()

  // ============================================================================
  // Export context
  // ============================================================================

  private class ExportContext:
    /** Maps TensorExpr identity → ONNX output tensor name. */
    val exprNames = new IdentityHashMap[TensorExpr, String]()

    /** Accumulated ONNX nodes in topological order. */
    val onnxNodes = mutable.Buffer.empty[ox.NodeProto]

    /** Accumulated initializer tensors. */
    val initializers = mutable.Buffer.empty[ox.TensorProto]

    /** Counter for generating unique intermediate names. */
    private var nameCounter = 0

    /** Whether any OpaqueOp with custom domain was encountered. */
    var hasCustomDomain = false

    def freshName(prefix: String): String =
      val n = s"${prefix}_$nameCounter"
      nameCounter += 1
      n

  // ============================================================================
  // Graph-level conversion
  // ============================================================================

  private def buildGraph(graph: ComputeGraph, ctx: ExportContext): ox.GraphProto =
    // Phase 1: Register all leaf expressions from NamedNodes so we know their names
    for node <- graph.nodes do registerLeaves(node.expr, ctx)

    // Phase 2: Register NamedNode output names (for resolving cross-references)
    for node <- graph.nodes do ctx.exprNames.put(node.expr, node.name)

    // Phase 3: Convert each NamedNode in topological order
    for node <- graph.nodes do convertExpr(node.expr, node.name, ctx)

    // Phase 4: Assemble GraphProto
    val gb = ox.GraphProto.newBuilder()
    gb.setName(graph.name)

    // Graph inputs: all declared inputs + initializer ValueInfoProtos
    for io <- graph.inputs do gb.addInput(toValueInfoProto(io))

    // Also add initializers as inputs (ONNX convention for opset < 13 compat)
    for init <- ctx.initializers do gb.addInput(initializerToValueInfo(init))

    // Graph outputs
    for io <- graph.outputs do gb.addOutput(toValueInfoProto(io))

    // Initializers
    ctx.initializers.foreach(gb.addInitializer)

    // Nodes
    ctx.onnxNodes.foreach(gb.addNode)

    gb.build()

  // ============================================================================
  // Leaf node registration
  // ============================================================================

  /** Recursively register leaf nodes (Input, Constant, Parameter) so they have names before we
    * start converting operations.
    */
  private def registerLeaves(expr: TensorExpr, ctx: ExportContext): Unit =
    if ctx.exprNames.containsKey(expr) then return
    expr match
      case TensorExpr.Input(name, _) =>
        ctx.exprNames.put(expr, name)

      case TensorExpr.Constant(name, tensorType, data) =>
        ctx.exprNames.put(expr, name)
        ctx.initializers += toTensorProto(name, tensorType, Some(data))

      case TensorExpr.Parameter(name, tensorType, _) =>
        ctx.exprNames.put(expr, name)
        ctx.initializers += toTensorProto(name, tensorType, None)

      case _ =>
        // Recurse into children
        children(expr).foreach(registerLeaves(_, ctx))

  // ============================================================================
  // Expression conversion — the core of the exporter
  // ============================================================================

  private def convertExpr(
      expr: TensorExpr,
      outputName: String,
      ctx: ExportContext
  ): String =
    // If already converted, return the existing name
    if ctx.exprNames.containsKey(expr) && ctx.exprNames.get(expr) != outputName then
      return ctx.exprNames.get(expr)

    val result = expr match
      // --- Leaf nodes: no ONNX op ---
      case _: TensorExpr.Input     => outputName
      case _: TensorExpr.Constant  => outputName
      case _: TensorExpr.Parameter => outputName

      // --- Category A: Direct 1:1 mappings ---

      // Linear algebra
      case TensorExpr.MatMul(left, right, transA, transB) =>
        val lName = resolve(left, ctx)
        val rName = resolve(right, ctx)
        if !transA && !transB then
          ctx.onnxNodes += makeNode("MatMul", Seq(lName, rName), Seq(outputName))
        else
          val actualL = if transA then
            val tn = ctx.freshName("transpose")
            ctx.onnxNodes += makeNode("Transpose", Seq(lName), Seq(tn))
            tn
          else lName
          val actualR = if transB then
            val tn = ctx.freshName("transpose")
            ctx.onnxNodes += makeNode("Transpose", Seq(rName), Seq(tn))
            tn
          else rName
          ctx.onnxNodes += makeNode("MatMul", Seq(actualL, actualR), Seq(outputName))
        outputName

      case TensorExpr.Gemm(a, b, c, alpha, beta, transA, transB) =>
        val aName = resolve(a, ctx)
        val bName = resolve(b, ctx)
        val inputs = c match
          case Some(cExpr) => Seq(aName, bName, resolve(cExpr, ctx))
          case None        => Seq(aName, bName)
        ctx.onnxNodes += makeNode(
          "Gemm",
          inputs,
          Seq(outputName),
          floatAttr("alpha", alpha.toFloat),
          floatAttr("beta", beta.toFloat),
          intAttr("transA", if transA then 1 else 0),
          intAttr("transB", if transB then 1 else 0)
        )
        outputName

      // Convolution
      case TensorExpr.Conv(input, weight, bias, strides, pads, dilations, group) =>
        val inputs = Seq(resolve(input, ctx), resolve(weight, ctx)) ++
          bias.map(b => resolve(b, ctx))
        ctx.onnxNodes += makeNode(
          "Conv",
          inputs,
          Seq(outputName),
          intsAttr("strides", strides),
          intsAttr("pads", pads),
          intsAttr("dilations", dilations),
          intAttr("group", group)
        )
        outputName

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
        val inputs = Seq(resolve(input, ctx), resolve(weight, ctx)) ++
          bias.map(b => resolve(b, ctx))
        ctx.onnxNodes += makeNode(
          "ConvTranspose",
          inputs,
          Seq(outputName),
          intsAttr("strides", strides),
          intsAttr("pads", pads),
          intsAttr("output_padding", outputPads),
          intsAttr("dilations", dilations),
          intAttr("group", group)
        )
        outputName

      // Activations
      case TensorExpr.Relu(input) =>
        unaryOp("Relu", input, outputName, ctx)

      case TensorExpr.LeakyRelu(input, alpha) =>
        val iName = resolve(input, ctx)
        ctx.onnxNodes += makeNode(
          "LeakyRelu",
          Seq(iName),
          Seq(outputName),
          floatAttr("alpha", alpha.toFloat)
        )
        outputName

      case TensorExpr.Sigmoid(input) =>
        unaryOp("Sigmoid", input, outputName, ctx)

      case TensorExpr.Tanh(input) =>
        unaryOp("Tanh", input, outputName, ctx)

      case TensorExpr.Softmax(input, axis) =>
        val iName = resolve(input, ctx)
        ctx.onnxNodes += makeNode("Softmax", Seq(iName), Seq(outputName), intAttr("axis", axis))
        outputName

      case TensorExpr.LogSoftmax(input, axis) =>
        val iName = resolve(input, ctx)
        ctx.onnxNodes += makeNode("LogSoftmax", Seq(iName), Seq(outputName), intAttr("axis", axis))
        outputName

      case TensorExpr.Elu(input, alpha) =>
        val iName = resolve(input, ctx)
        ctx.onnxNodes += makeNode(
          "Elu",
          Seq(iName),
          Seq(outputName),
          floatAttr("alpha", alpha.toFloat)
        )
        outputName

      // Element-wise arithmetic
      case TensorExpr.Add(left, right) => binaryOp("Add", left, right, outputName, ctx)
      case TensorExpr.Sub(left, right) => binaryOp("Sub", left, right, outputName, ctx)
      case TensorExpr.Mul(left, right) => binaryOp("Mul", left, right, outputName, ctx)
      case TensorExpr.Div(left, right) => binaryOp("Div", left, right, outputName, ctx)
      case TensorExpr.Pow(base, exp)   => binaryOp("Pow", base, exp, outputName, ctx)

      case TensorExpr.Sqrt(input) => unaryOp("Sqrt", input, outputName, ctx)
      case TensorExpr.Neg(input)  => unaryOp("Neg", input, outputName, ctx)
      case TensorExpr.Abs(input)  => unaryOp("Abs", input, outputName, ctx)
      case TensorExpr.Exp(input)  => unaryOp("Exp", input, outputName, ctx)
      case TensorExpr.Log(input)  => unaryOp("Log", input, outputName, ctx)

      case TensorExpr.Clip(input, min, max) =>
        val iName = resolve(input, ctx)
        val minName =
          min.map(v => float32Const(ctx.freshName("clip_min"), v.toFloat, ctx)).getOrElse("")
        val maxName =
          max.map(v => float32Const(ctx.freshName("clip_max"), v.toFloat, ctx)).getOrElse("")
        ctx.onnxNodes += makeNode("Clip", Seq(iName, minName, maxName), Seq(outputName))
        outputName

      // Pooling
      case TensorExpr.MaxPool(input, kernelSize, strides, pads) =>
        val iName = resolve(input, ctx)
        ctx.onnxNodes += makeNode(
          "MaxPool",
          Seq(iName),
          Seq(outputName),
          intsAttr("kernel_shape", kernelSize),
          intsAttr("strides", strides),
          intsAttr("pads", pads)
        )
        outputName

      case TensorExpr.AvgPool(input, kernelSize, strides, pads, countIncludePad) =>
        val iName = resolve(input, ctx)
        ctx.onnxNodes += makeNode(
          "AveragePool",
          Seq(iName),
          Seq(outputName),
          intsAttr("kernel_shape", kernelSize),
          intsAttr("strides", strides),
          intsAttr("pads", pads),
          intAttr("count_include_pad", if countIncludePad then 1 else 0)
        )
        outputName

      case TensorExpr.GlobalAvgPool(input) =>
        unaryOp("GlobalAveragePool", input, outputName, ctx)

      // Normalization
      case TensorExpr.BatchNorm(
            input,
            scale,
            bias,
            runningMean,
            runningVar,
            epsilon,
            momentum,
            _
          ) =>
        val inputs = Seq(
          resolve(input, ctx),
          resolve(scale, ctx),
          resolve(bias, ctx),
          resolve(runningMean, ctx),
          resolve(runningVar, ctx)
        )
        ctx.onnxNodes += makeNode(
          "BatchNormalization",
          inputs,
          Seq(outputName),
          floatAttr("epsilon", epsilon.toFloat),
          floatAttr("momentum", momentum.toFloat)
        )
        outputName

      case TensorExpr.LayerNorm(input, normalizedShape, weight, bias, epsilon) =>
        val iName = resolve(input, ctx)
        val inputs = Seq(iName) ++
          weight.map(w => resolve(w, ctx)) ++
          bias.map(b => resolve(b, ctx))
        // axis = rank - len(normalizedShape)
        ctx.onnxNodes += makeNode(
          "LayerNormalization",
          inputs,
          Seq(outputName),
          floatAttr("epsilon", epsilon.toFloat)
        )
        outputName

      case TensorExpr.InstanceNorm(input, scale, bias, epsilon) =>
        val inputs = Seq(resolve(input, ctx), resolve(scale, ctx), resolve(bias, ctx))
        ctx.onnxNodes += makeNode(
          "InstanceNormalization",
          inputs,
          Seq(outputName),
          floatAttr("epsilon", epsilon.toFloat)
        )
        outputName

      // Reduction (opset 13+: axes as tensor input)
      case TensorExpr.ReduceSum(input, axes, keepDims) =>
        reduceOp("ReduceSum", input, axes, keepDims, outputName, ctx)

      case TensorExpr.ReduceMean(input, axes, keepDims) =>
        reduceOp("ReduceMean", input, axes, keepDims, outputName, ctx)

      case TensorExpr.ReduceMax(input, axes, keepDims) =>
        reduceOp("ReduceMax", input, axes, keepDims, outputName, ctx)

      case TensorExpr.ReduceMin(input, axes, keepDims) =>
        reduceOp("ReduceMin", input, axes, keepDims, outputName, ctx)

      case TensorExpr.ReduceProd(input, axes, keepDims) =>
        reduceOp("ReduceProd", input, axes, keepDims, outputName, ctx)

      // Shape manipulation
      case TensorExpr.Reshape(input, shape) =>
        val iName = resolve(input, ctx)
        val shapeName = int64Const(ctx.freshName("shape"), shape.map(_.toLong), ctx)
        ctx.onnxNodes += makeNode("Reshape", Seq(iName, shapeName), Seq(outputName))
        outputName

      case TensorExpr.Transpose(input, perm) =>
        val iName = resolve(input, ctx)
        ctx.onnxNodes += makeNode("Transpose", Seq(iName), Seq(outputName), intsAttr("perm", perm))
        outputName

      case TensorExpr.Flatten(input, axis) =>
        val iName = resolve(input, ctx)
        ctx.onnxNodes += makeNode("Flatten", Seq(iName), Seq(outputName), intAttr("axis", axis))
        outputName

      case TensorExpr.Squeeze(input, axes) =>
        val iName = resolve(input, ctx)
        val axesName = int64Const(ctx.freshName("axes"), axes.map(_.toLong), ctx)
        ctx.onnxNodes += makeNode("Squeeze", Seq(iName, axesName), Seq(outputName))
        outputName

      case TensorExpr.Unsqueeze(input, axes) =>
        val iName = resolve(input, ctx)
        val axesName = int64Const(ctx.freshName("axes"), axes.map(_.toLong), ctx)
        ctx.onnxNodes += makeNode("Unsqueeze", Seq(iName, axesName), Seq(outputName))
        outputName

      case TensorExpr.TensorConcat(inputs, axis) =>
        val inputNames = inputs.map(resolve(_, ctx))
        ctx.onnxNodes += makeNode("Concat", inputNames, Seq(outputName), intAttr("axis", axis))
        outputName

      case TensorExpr.Split(input, axis, splitSizes) =>
        val iName = resolve(input, ctx)
        val splitName = int64Const(ctx.freshName("split"), splitSizes.map(_.toLong), ctx)
        ctx.onnxNodes += makeNode(
          "Split",
          Seq(iName, splitName),
          Seq(outputName),
          intAttr("axis", axis)
        )
        outputName

      case TensorExpr.TensorSlice(input, starts, ends, axes, steps) =>
        val iName = resolve(input, ctx)
        val startsName = int64Const(ctx.freshName("starts"), starts.map(_.toLong), ctx)
        val endsName = int64Const(ctx.freshName("ends"), ends.map(_.toLong), ctx)
        val axesName = int64Const(ctx.freshName("axes"), axes.map(_.toLong), ctx)
        val stepsName = int64Const(ctx.freshName("steps"), steps.map(_.toLong), ctx)
        ctx.onnxNodes += makeNode(
          "Slice",
          Seq(iName, startsName, endsName, axesName, stepsName),
          Seq(outputName)
        )
        outputName

      case TensorExpr.Gather(input, indices, axis) =>
        val iName = resolve(input, ctx)
        val idxName = resolve(indices, ctx)
        ctx.onnxNodes += makeNode(
          "Gather",
          Seq(iName, idxName),
          Seq(outputName),
          intAttr("axis", axis)
        )
        outputName

      case TensorExpr.Scatter(input, indices, updates, axis) =>
        val iName = resolve(input, ctx)
        val idxName = resolve(indices, ctx)
        val updName = resolve(updates, ctx)
        ctx.onnxNodes += makeNode(
          "ScatterElements",
          Seq(iName, idxName, updName),
          Seq(outputName),
          intAttr("axis", axis)
        )
        outputName

      case TensorExpr.Pad(input, pads, mode, constantValue) =>
        val iName = resolve(input, ctx)
        val padsName = int64Const(ctx.freshName("pads"), pads.map(_.toLong), ctx)
        val cvName = float32Const(ctx.freshName("constant_value"), constantValue.toFloat, ctx)
        val modeStr = mode match
          case PadMode.Constant => "constant"
          case PadMode.Reflect  => "reflect"
          case PadMode.Edge     => "edge"
        ctx.onnxNodes += makeNode(
          "Pad",
          Seq(iName, padsName, cvName),
          Seq(outputName),
          stringAttr("mode", modeStr)
        )
        outputName

      case TensorExpr.Expand(input, shape) =>
        val iName = resolve(input, ctx)
        val shapeName = int64Const(ctx.freshName("shape"), shape.map(_.toLong), ctx)
        ctx.onnxNodes += makeNode("Expand", Seq(iName, shapeName), Seq(outputName))
        outputName

      // Recurrent
      case TensorExpr.LSTM(input, weightIh, weightHh, bias, hiddenSize, _, bidirectional, _) =>
        val iName = resolve(input, ctx)
        val wName = resolve(weightIh, ctx)
        val rName = resolve(weightHh, ctx)
        val inputs = Seq(iName, wName, rName) ++ bias.map(b => resolve(b, ctx))
        val direction = if bidirectional then "bidirectional" else "forward"
        ctx.onnxNodes += makeNode(
          "LSTM",
          inputs,
          Seq(outputName),
          intAttr("hidden_size", hiddenSize),
          stringAttr("direction", direction)
        )
        outputName

      case TensorExpr.GRU(input, weightIh, weightHh, bias, hiddenSize, _, bidirectional, _) =>
        val iName = resolve(input, ctx)
        val wName = resolve(weightIh, ctx)
        val rName = resolve(weightHh, ctx)
        val inputs = Seq(iName, wName, rName) ++ bias.map(b => resolve(b, ctx))
        val direction = if bidirectional then "bidirectional" else "forward"
        ctx.onnxNodes += makeNode(
          "GRU",
          inputs,
          Seq(outputName),
          intAttr("hidden_size", hiddenSize),
          stringAttr("direction", direction)
        )
        outputName

      // Comparison / logical
      case TensorExpr.Equal(left, right)   => binaryOp("Equal", left, right, outputName, ctx)
      case TensorExpr.Greater(left, right) => binaryOp("Greater", left, right, outputName, ctx)
      case TensorExpr.Less(left, right)    => binaryOp("Less", left, right, outputName, ctx)

      case TensorExpr.Where(condition, x, y) =>
        val cName = resolve(condition, ctx)
        val xName = resolve(x, ctx)
        val yName = resolve(y, ctx)
        ctx.onnxNodes += makeNode("Where", Seq(cName, xName, yName), Seq(outputName))
        outputName

      // Type casting
      case TensorExpr.Cast(input, targetDType) =>
        val iName = resolve(input, ctx)
        ctx.onnxNodes += makeNode(
          "Cast",
          Seq(iName),
          Seq(outputName),
          intAttr("to", toOnnxDataType(targetDType))
        )
        outputName

      // Dropout — at inference, emit Identity; at training, emit Dropout
      case TensorExpr.Dropout(input, ratio, training) =>
        val iName = resolve(input, ctx)
        if !training then ctx.onnxNodes += makeNode("Identity", Seq(iName), Seq(outputName))
        else
          val ratioName = float32Const(ctx.freshName("dropout_ratio"), ratio.toFloat, ctx)
          ctx.onnxNodes += makeNode("Dropout", Seq(iName, ratioName), Seq(outputName))
        outputName

      // --- Category B: Decomposition ---

      case TensorExpr.Linear(input, weight, bias) =>
        // Linear(x, w, b) → Gemm(x, w, b, transB=1)
        val xName = resolve(input, ctx)
        val wName = resolve(weight, ctx)
        val inputs = Seq(xName, wName) ++ bias.map(b => resolve(b, ctx))
        ctx.onnxNodes += makeNode(
          "Gemm",
          inputs,
          Seq(outputName),
          floatAttr("alpha", 1.0f),
          floatAttr("beta", 1.0f),
          intAttr("transA", 0),
          intAttr("transB", 1)
        )
        outputName

      case TensorExpr.Silu(input) =>
        // Silu(x) = x * sigmoid(x)
        val iName = resolve(input, ctx)
        val sigName = ctx.freshName("silu_sigmoid")
        ctx.onnxNodes += makeNode("Sigmoid", Seq(iName), Seq(sigName))
        ctx.onnxNodes += makeNode("Mul", Seq(iName, sigName), Seq(outputName))
        outputName

      case TensorExpr.Gelu(input, approximate) =>
        val iName = resolve(input, ctx)
        if !approximate then
          // Exact: x * 0.5 * (1 + erf(x / sqrt(2)))
          val sqrt2Name = float32Const(ctx.freshName("sqrt2"), math.sqrt(2.0).toFloat, ctx)
          val halfName = float32Const(ctx.freshName("half"), 0.5f, ctx)
          val oneName = float32Const(ctx.freshName("one"), 1.0f, ctx)
          val divName = ctx.freshName("gelu_div")
          val erfName = ctx.freshName("gelu_erf")
          val addName = ctx.freshName("gelu_add")
          val mulHalfName = ctx.freshName("gelu_mul_half")
          ctx.onnxNodes += makeNode("Div", Seq(iName, sqrt2Name), Seq(divName))
          ctx.onnxNodes += makeNode("Erf", Seq(divName), Seq(erfName))
          ctx.onnxNodes += makeNode("Add", Seq(erfName, oneName), Seq(addName))
          ctx.onnxNodes += makeNode("Mul", Seq(addName, halfName), Seq(mulHalfName))
          ctx.onnxNodes += makeNode("Mul", Seq(iName, mulHalfName), Seq(outputName))
        else
          // Approximate: 0.5 * x * (1 + tanh(sqrt(2/pi) * (x + 0.044715 * x^3)))
          val halfName = float32Const(ctx.freshName("half"), 0.5f, ctx)
          val oneName = float32Const(ctx.freshName("one"), 1.0f, ctx)
          val coeffName = float32Const(ctx.freshName("coeff"), 0.044715f, ctx)
          val sqrt2piName =
            float32Const(ctx.freshName("sqrt2pi"), math.sqrt(2.0 / math.Pi).toFloat, ctx)
          val threeName = float32Const(ctx.freshName("three"), 3.0f, ctx)
          val powName = ctx.freshName("gelu_pow")
          val mulCoeffName = ctx.freshName("gelu_mul_coeff")
          val addXName = ctx.freshName("gelu_add_x")
          val mulSqrtName = ctx.freshName("gelu_mul_sqrt")
          val tanhName = ctx.freshName("gelu_tanh")
          val addOneName = ctx.freshName("gelu_add_one")
          val mulXName = ctx.freshName("gelu_mul_x")
          ctx.onnxNodes += makeNode("Pow", Seq(iName, threeName), Seq(powName))
          ctx.onnxNodes += makeNode("Mul", Seq(powName, coeffName), Seq(mulCoeffName))
          ctx.onnxNodes += makeNode("Add", Seq(iName, mulCoeffName), Seq(addXName))
          ctx.onnxNodes += makeNode("Mul", Seq(addXName, sqrt2piName), Seq(mulSqrtName))
          ctx.onnxNodes += makeNode("Tanh", Seq(mulSqrtName), Seq(tanhName))
          ctx.onnxNodes += makeNode("Add", Seq(tanhName, oneName), Seq(addOneName))
          ctx.onnxNodes += makeNode("Mul", Seq(iName, addOneName), Seq(mulXName))
          ctx.onnxNodes += makeNode("Mul", Seq(mulXName, halfName), Seq(outputName))
        outputName

      case TensorExpr.HardSwish(input) =>
        // HardSwish(x) = x * clip(x + 3, 0, 6) / 6
        val iName = resolve(input, ctx)
        val threeName = float32Const(ctx.freshName("three"), 3.0f, ctx)
        val zeroName = float32Const(ctx.freshName("zero"), 0.0f, ctx)
        val sixName = float32Const(ctx.freshName("six"), 6.0f, ctx)
        val addName = ctx.freshName("hardswish_add")
        val clipName = ctx.freshName("hardswish_clip")
        val mulName = ctx.freshName("hardswish_mul")
        ctx.onnxNodes += makeNode("Add", Seq(iName, threeName), Seq(addName))
        ctx.onnxNodes += makeNode("Clip", Seq(addName, zeroName, sixName), Seq(clipName))
        ctx.onnxNodes += makeNode("Mul", Seq(iName, clipName), Seq(mulName))
        ctx.onnxNodes += makeNode("Div", Seq(mulName, sixName), Seq(outputName))
        outputName

      case TensorExpr.GroupNorm(input, numGroups, weight, bias, epsilon) =>
        // GroupNorm: reshape → InstanceNorm → reshape back → scale + shift
        // This is a simplified decomposition using InstanceNorm on reshaped groups
        val iName = resolve(input, ctx)
        // For GroupNorm, we reshape [N, C, ...] → [N*G, C/G, ...], apply InstanceNorm,
        // then reshape back. Since we may not know the full shape statically,
        // we use a simplified approach: emit an InstanceNorm-based decomposition.
        val inputs = Seq(iName) ++
          weight.map(w => resolve(w, ctx)) ++
          bias.map(b => resolve(b, ctx))
        // Emit as custom GroupNorm node — many ONNX runtimes support this as custom op
        ctx.hasCustomDomain = true
        ctx.onnxNodes += makeNodeWithDomain(
          "GroupNorm",
          "protocatalyst.custom",
          inputs,
          Seq(outputName),
          intAttr("num_groups", numGroups),
          floatAttr("epsilon", epsilon.toFloat)
        )
        outputName

      case TensorExpr.AdaptiveAvgPool(input, outputSize) =>
        // AdaptiveAvgPool → AveragePool with computed kernel/strides
        // This requires static input shapes; emit as custom op if shapes unknown
        val iName = resolve(input, ctx)
        ctx.hasCustomDomain = true
        ctx.onnxNodes += makeNodeWithDomain(
          "AdaptiveAveragePool",
          "protocatalyst.custom",
          Seq(iName),
          Seq(outputName),
          intsAttr("output_size", outputSize)
        )
        outputName

      case TensorExpr.ScaledDotProductAttention(query, key, value, mask, _, isCausal) =>
        // SDPA: softmax(Q @ K^T / sqrt(dk)) @ V
        val qName = resolve(query, ctx)
        val kName = resolve(key, ctx)
        val vName = resolve(value, ctx)
        // Transpose K
        val ktName = ctx.freshName("sdpa_kt")
        ctx.onnxNodes += makeNode("Transpose", Seq(kName), Seq(ktName))
        // Q @ K^T
        val qkName = ctx.freshName("sdpa_qk")
        ctx.onnxNodes += makeNode("MatMul", Seq(qName, ktName), Seq(qkName))
        // / sqrt(dk) — use a placeholder scale; actual dk depends on input dimensions
        val scaleName = float32Const(ctx.freshName("sdpa_scale"), 1.0f, ctx)
        val scaledName = ctx.freshName("sdpa_scaled")
        ctx.onnxNodes += makeNode("Div", Seq(qkName, scaleName), Seq(scaledName))
        // + mask (if present)
        val maskedName = mask match
          case Some(m) =>
            val mName = resolve(m, ctx)
            val mn = ctx.freshName("sdpa_masked")
            ctx.onnxNodes += makeNode("Add", Seq(scaledName, mName), Seq(mn))
            mn
          case None => scaledName
        // Softmax
        val attnName = ctx.freshName("sdpa_attn")
        ctx.onnxNodes += makeNode("Softmax", Seq(maskedName), Seq(attnName), intAttr("axis", -1))
        // @ V
        ctx.onnxNodes += makeNode("MatMul", Seq(attnName, vName), Seq(outputName))
        outputName

      case TensorExpr.MultiHeadAttention(query, key, value, wQ, wK, wV, wO, numHeads, mask, _) =>
        // MHA: project Q/K/V, apply SDPA, project output
        val qName = resolve(query, ctx)
        val kName = resolve(key, ctx)
        val vName = resolve(value, ctx)
        val wqName = resolve(wQ, ctx)
        val wkName = resolve(wK, ctx)
        val wvName = resolve(wV, ctx)
        val woName = resolve(wO, ctx)
        // Project Q, K, V
        val projQ = ctx.freshName("mha_proj_q")
        val projK = ctx.freshName("mha_proj_k")
        val projV = ctx.freshName("mha_proj_v")
        ctx.onnxNodes += makeNode("MatMul", Seq(qName, wqName), Seq(projQ))
        ctx.onnxNodes += makeNode("MatMul", Seq(kName, wkName), Seq(projK))
        ctx.onnxNodes += makeNode("MatMul", Seq(vName, wvName), Seq(projV))
        // Transpose K^T for attention
        val ktName = ctx.freshName("mha_kt")
        ctx.onnxNodes += makeNode("Transpose", Seq(projK), Seq(ktName))
        // Q @ K^T
        val qkName = ctx.freshName("mha_qk")
        ctx.onnxNodes += makeNode("MatMul", Seq(projQ, ktName), Seq(qkName))
        // Scale
        val scaleName = float32Const(ctx.freshName("mha_scale"), 1.0f, ctx)
        val scaledName = ctx.freshName("mha_scaled")
        ctx.onnxNodes += makeNode("Div", Seq(qkName, scaleName), Seq(scaledName))
        // Mask
        val maskedName = mask match
          case Some(m) =>
            val mName = resolve(m, ctx)
            val mn = ctx.freshName("mha_masked")
            ctx.onnxNodes += makeNode("Add", Seq(scaledName, mName), Seq(mn))
            mn
          case None => scaledName
        // Softmax + @ V
        val attnName = ctx.freshName("mha_attn")
        ctx.onnxNodes += makeNode("Softmax", Seq(maskedName), Seq(attnName), intAttr("axis", -1))
        val attnOut = ctx.freshName("mha_attn_out")
        ctx.onnxNodes += makeNode("MatMul", Seq(attnName, projV), Seq(attnOut))
        // Output projection
        ctx.onnxNodes += makeNode("MatMul", Seq(attnOut, woName), Seq(outputName))
        outputName

      case TensorExpr.Embedding(indices, weight, _) =>
        // Embedding → Gather(weight, indices, axis=0)
        val wName = resolve(weight, ctx)
        val iName = resolve(indices, ctx)
        ctx.onnxNodes += makeNode("Gather", Seq(wName, iName), Seq(outputName), intAttr("axis", 0))
        outputName

      // Loss functions
      case TensorExpr.CrossEntropyLoss(input, target, weight, reduction) =>
        val iName = resolve(input, ctx)
        val tName = resolve(target, ctx)
        val inputs = Seq(iName, tName) ++ weight.map(w => resolve(w, ctx))
        ctx.onnxNodes += makeNode(
          "SoftmaxCrossEntropyLoss",
          inputs,
          Seq(outputName),
          stringAttr("reduction", reductionStr(reduction))
        )
        outputName

      case TensorExpr.MSELoss(input, target, reduction) =>
        // Sub → Mul(self) → Reduce
        val iName = resolve(input, ctx)
        val tName = resolve(target, ctx)
        val subName = ctx.freshName("mse_sub")
        val sqName = ctx.freshName("mse_sq")
        ctx.onnxNodes += makeNode("Sub", Seq(iName, tName), Seq(subName))
        ctx.onnxNodes += makeNode("Mul", Seq(subName, subName), Seq(sqName))
        emitReduction(sqName, reduction, outputName, ctx)
        outputName

      case TensorExpr.L1Loss(input, target, reduction) =>
        // Sub → Abs → Reduce
        val iName = resolve(input, ctx)
        val tName = resolve(target, ctx)
        val subName = ctx.freshName("l1_sub")
        val absName = ctx.freshName("l1_abs")
        ctx.onnxNodes += makeNode("Sub", Seq(iName, tName), Seq(subName))
        ctx.onnxNodes += makeNode("Abs", Seq(subName), Seq(absName))
        emitReduction(absName, reduction, outputName, ctx)
        outputName

      case TensorExpr.BCELoss(input, target, weight, reduction) =>
        // -[target * log(input) + (1 - target) * log(1 - input)]
        val iName = resolve(input, ctx)
        val tName = resolve(target, ctx)
        val oneName = float32Const(ctx.freshName("one"), 1.0f, ctx)
        val logI = ctx.freshName("bce_log_i")
        val tLogI = ctx.freshName("bce_t_log_i")
        val oneMinusT = ctx.freshName("bce_1mt")
        val oneMinusI = ctx.freshName("bce_1mi")
        val logOneMinusI = ctx.freshName("bce_log_1mi")
        val term2 = ctx.freshName("bce_term2")
        val sumTerms = ctx.freshName("bce_sum")
        val negName = ctx.freshName("bce_neg")
        ctx.onnxNodes += makeNode("Log", Seq(iName), Seq(logI))
        ctx.onnxNodes += makeNode("Mul", Seq(tName, logI), Seq(tLogI))
        ctx.onnxNodes += makeNode("Sub", Seq(oneName, tName), Seq(oneMinusT))
        ctx.onnxNodes += makeNode("Sub", Seq(oneName, iName), Seq(oneMinusI))
        ctx.onnxNodes += makeNode("Log", Seq(oneMinusI), Seq(logOneMinusI))
        ctx.onnxNodes += makeNode("Mul", Seq(oneMinusT, logOneMinusI), Seq(term2))
        ctx.onnxNodes += makeNode("Add", Seq(tLogI, term2), Seq(sumTerms))
        ctx.onnxNodes += makeNode("Neg", Seq(sumTerms), Seq(negName))
        // Apply optional weight
        val weightedName = weight match
          case Some(w) =>
            val wName = resolve(w, ctx)
            val wn = ctx.freshName("bce_weighted")
            ctx.onnxNodes += makeNode("Mul", Seq(negName, wName), Seq(wn))
            wn
          case None => negName
        emitReduction(weightedName, reduction, outputName, ctx)
        outputName

      case TensorExpr.BCEWithLogitsLoss(input, target, weight, reduction) =>
        // Sigmoid → BCELoss decomposition
        val iName = resolve(input, ctx)
        val sigName = ctx.freshName("bcel_sigmoid")
        ctx.onnxNodes += makeNode("Sigmoid", Seq(iName), Seq(sigName))
        // Now compute BCE on sigmoid output
        val tName = resolve(target, ctx)
        val oneName = float32Const(ctx.freshName("one"), 1.0f, ctx)
        val logSig = ctx.freshName("bcel_log_sig")
        val tLogSig = ctx.freshName("bcel_t_log_sig")
        val oneMinusT = ctx.freshName("bcel_1mt")
        val oneMinusSig = ctx.freshName("bcel_1ms")
        val logOneMinusSig = ctx.freshName("bcel_log_1ms")
        val term2 = ctx.freshName("bcel_term2")
        val sumTerms = ctx.freshName("bcel_sum")
        val negName = ctx.freshName("bcel_neg")
        ctx.onnxNodes += makeNode("Log", Seq(sigName), Seq(logSig))
        ctx.onnxNodes += makeNode("Mul", Seq(tName, logSig), Seq(tLogSig))
        ctx.onnxNodes += makeNode("Sub", Seq(oneName, tName), Seq(oneMinusT))
        ctx.onnxNodes += makeNode("Sub", Seq(oneName, sigName), Seq(oneMinusSig))
        ctx.onnxNodes += makeNode("Log", Seq(oneMinusSig), Seq(logOneMinusSig))
        ctx.onnxNodes += makeNode("Mul", Seq(oneMinusT, logOneMinusSig), Seq(term2))
        ctx.onnxNodes += makeNode("Add", Seq(tLogSig, term2), Seq(sumTerms))
        ctx.onnxNodes += makeNode("Neg", Seq(sumTerms), Seq(negName))
        val weightedName = weight match
          case Some(w) =>
            val wName = resolve(w, ctx)
            val wn = ctx.freshName("bcel_weighted")
            ctx.onnxNodes += makeNode("Mul", Seq(negName, wName), Seq(wn))
            wn
          case None => negName
        emitReduction(weightedName, reduction, outputName, ctx)
        outputName

      // --- Category D: Custom domain ---
      case TensorExpr.OpaqueOp(name, inputs, attributes, _) =>
        ctx.hasCustomDomain = true
        val inputNames = inputs.map(resolve(_, ctx))
        val attrs = attributes.map { case (k, v) => toOnnxAttribute(k, v) }.toSeq
        ctx.onnxNodes += makeNodeWithDomain(
          name,
          "protocatalyst.custom",
          inputNames,
          Seq(outputName),
          attrs*
        )
        outputName

    ctx.exprNames.put(expr, result)
    result

  // ============================================================================
  // Type mapping
  // ============================================================================

  private def toOnnxDataType(dt: TensorDType): Int = dt match
    case TensorDType.Float16    => 10
    case TensorDType.Float32    => 1
    case TensorDType.Float64    => 11
    case TensorDType.BFloat16   => 16
    case TensorDType.Int8       => 3
    case TensorDType.Int16      => 5
    case TensorDType.Int32      => 6
    case TensorDType.Int64      => 7
    case TensorDType.UInt8      => 2
    case TensorDType.Bool       => 9
    case TensorDType.Complex64  => 14
    case TensorDType.Complex128 => 15

  private def toValueInfoProto(io: GraphIO): ox.ValueInfoProto =
    val vb = ox.ValueInfoProto.newBuilder()
    vb.setName(io.name)
    vb.setType(toTypeProto(io.tensorType))
    vb.build()

  private def toTypeProto(tt: TensorType): ox.TypeProto =
    val tb = ox.TypeProto.Tensor.newBuilder()
    tb.setElemType(toOnnxDataType(tt.dtype))
    val sb = ox.TensorShapeProto.newBuilder()
    for dim <- tt.shape do
      dim match
        case Dim.Static(size) =>
          sb.addDim(ox.TensorShapeProto.Dimension.newBuilder().setDimValue(size.toLong))
        case Dim.Dynamic(name) =>
          val db = ox.TensorShapeProto.Dimension.newBuilder()
          name.foreach(db.setDimParam)
          sb.addDim(db)
    tb.setShape(sb)
    ox.TypeProto.newBuilder().setTensorType(tb).build()

  private def initializerToValueInfo(init: ox.TensorProto): ox.ValueInfoProto =
    val tb = ox.TypeProto.Tensor.newBuilder()
    tb.setElemType(init.getDataType)
    val sb = ox.TensorShapeProto.newBuilder()
    for i <- 0 until init.getDimsCount do
      sb.addDim(ox.TensorShapeProto.Dimension.newBuilder().setDimValue(init.getDims(i)))
    tb.setShape(sb)
    ox.ValueInfoProto
      .newBuilder()
      .setName(init.getName)
      .setType(ox.TypeProto.newBuilder().setTensorType(tb))
      .build()

  // ============================================================================
  // Node construction helpers
  // ============================================================================

  private def makeNode(
      opType: String,
      inputs: Seq[String],
      outputs: Seq[String],
      attrs: ox.AttributeProto*
  ): ox.NodeProto =
    val nb = ox.NodeProto.newBuilder()
    nb.setOpType(opType)
    inputs.foreach(nb.addInput)
    outputs.foreach(nb.addOutput)
    attrs.foreach(nb.addAttribute)
    nb.build()

  private def makeNodeWithDomain(
      opType: String,
      domain: String,
      inputs: Seq[String],
      outputs: Seq[String],
      attrs: ox.AttributeProto*
  ): ox.NodeProto =
    val nb = ox.NodeProto.newBuilder()
    nb.setOpType(opType)
    nb.setDomain(domain)
    inputs.foreach(nb.addInput)
    outputs.foreach(nb.addOutput)
    attrs.foreach(nb.addAttribute)
    nb.build()

  private def intAttr(name: String, value: Long): ox.AttributeProto =
    ox.AttributeProto
      .newBuilder()
      .setName(name)
      .setType(ox.AttributeProto.AttributeType.INT)
      .setI(value)
      .build()

  private def floatAttr(name: String, value: Float): ox.AttributeProto =
    ox.AttributeProto
      .newBuilder()
      .setName(name)
      .setType(ox.AttributeProto.AttributeType.FLOAT)
      .setF(value)
      .build()

  private def intsAttr(name: String, values: Seq[Int]): ox.AttributeProto =
    val ab = ox.AttributeProto.newBuilder()
    ab.setName(name)
    ab.setType(ox.AttributeProto.AttributeType.INTS)
    values.foreach(v => ab.addInts(v.toLong))
    ab.build()

  private def stringAttr(name: String, value: String): ox.AttributeProto =
    ox.AttributeProto
      .newBuilder()
      .setName(name)
      .setType(ox.AttributeProto.AttributeType.STRING)
      .setS(ByteString.copyFromUtf8(value))
      .build()

  // ============================================================================
  // Constant tensor helpers
  // ============================================================================

  private def int64Const(name: String, values: Seq[Long], ctx: ExportContext): String =
    val tb = ox.TensorProto.newBuilder()
    tb.setName(name)
    tb.setDataType(7) // INT64
    tb.addDims(values.size.toLong)
    values.foreach(tb.addInt64Data)
    ctx.initializers += tb.build()
    name

  private def float32Const(name: String, value: Float, ctx: ExportContext): String =
    val tb = ox.TensorProto.newBuilder()
    tb.setName(name)
    tb.setDataType(1) // FLOAT
    tb.addFloatData(value)
    ctx.initializers += tb.build()
    name

  private def toTensorProto(
      name: String,
      tensorType: TensorType,
      data: Option[TensorData]
  ): ox.TensorProto =
    val tb = ox.TensorProto.newBuilder()
    tb.setName(name)
    tb.setDataType(toOnnxDataType(tensorType.dtype))
    for dim <- tensorType.shape do
      dim match
        case Dim.Static(size) => tb.addDims(size.toLong)
        case Dim.Dynamic(_)   => tb.addDims(-1L) // Unknown dim
    data.foreach(d => tb.setRawData(ByteString.copyFrom(d.rawBytes)))
    tb.build()

  // ============================================================================
  // Resolve & traversal helpers
  // ============================================================================

  /** Resolve a sub-expression to its ONNX tensor name. */
  private def resolve(expr: TensorExpr, ctx: ExportContext): String =
    if ctx.exprNames.containsKey(expr) then ctx.exprNames.get(expr)
    else
      // Sub-expression not yet registered — it's a leaf embedded in a NamedNode's expr
      val name = expr match
        case TensorExpr.Input(n, _)        => n
        case TensorExpr.Constant(n, _, _)  => n
        case TensorExpr.Parameter(n, _, _) => n
        case _                             =>
          throw IllegalStateException(
            s"Cannot resolve unnamed non-leaf TensorExpr: ${expr.getClass.getSimpleName}"
          )
      ctx.exprNames.put(expr, name)
      name

  /** Extract direct children of a TensorExpr. */
  private def children(expr: TensorExpr): Seq[TensorExpr] = expr match
    case _: TensorExpr.Input | _: TensorExpr.Constant | _: TensorExpr.Parameter => Seq.empty
    case TensorExpr.MatMul(l, r, _, _)                                          => Seq(l, r)
    case TensorExpr.Gemm(a, b, c, _, _, _, _)                                   => Seq(a, b) ++ c
    case TensorExpr.Linear(i, w, b)                                             => Seq(i, w) ++ b
    case TensorExpr.Conv(i, w, b, _, _, _, _)                                   => Seq(i, w) ++ b
    case TensorExpr.ConvTranspose(i, w, b, _, _, _, _, _)                       => Seq(i, w) ++ b
    case TensorExpr.Add(l, r)                                                   => Seq(l, r)
    case TensorExpr.Sub(l, r)                                                   => Seq(l, r)
    case TensorExpr.Mul(l, r)                                                   => Seq(l, r)
    case TensorExpr.Div(l, r)                                                   => Seq(l, r)
    case TensorExpr.Pow(b, e)                                                   => Seq(b, e)
    case TensorExpr.Equal(l, r)                                                 => Seq(l, r)
    case TensorExpr.Greater(l, r)                                               => Seq(l, r)
    case TensorExpr.Less(l, r)                                                  => Seq(l, r)
    case TensorExpr.Where(c, x, y)                                              => Seq(c, x, y)
    case TensorExpr.Scatter(i, idx, u, _)                                       => Seq(i, idx, u)
    case TensorExpr.Gather(i, idx, _)                                           => Seq(i, idx)
    case TensorExpr.TensorConcat(inputs, _)                                     => inputs
    case TensorExpr.BatchNorm(i, s, b, rm, rv, _, _, _)                  => Seq(i, s, b, rm, rv)
    case TensorExpr.LayerNorm(i, _, w, b, _)                             => Seq(i) ++ w ++ b
    case TensorExpr.GroupNorm(i, _, w, b, _)                             => Seq(i) ++ w ++ b
    case TensorExpr.InstanceNorm(i, s, b, _)                             => Seq(i, s, b)
    case TensorExpr.LSTM(i, wh, wc, b, _, _, _, _)                       => Seq(i, wh, wc) ++ b
    case TensorExpr.GRU(i, wh, wc, b, _, _, _, _)                        => Seq(i, wh, wc) ++ b
    case TensorExpr.ScaledDotProductAttention(q, k, v, m, _, _)          => Seq(q, k, v) ++ m
    case TensorExpr.MultiHeadAttention(q, k, v, wq, wk, wv, wo, _, m, _) =>
      Seq(q, k, v, wq, wk, wv, wo) ++ m
    case TensorExpr.Embedding(idx, w, _)          => Seq(idx, w)
    case TensorExpr.CrossEntropyLoss(i, t, w, _)  => Seq(i, t) ++ w
    case TensorExpr.MSELoss(i, t, _)              => Seq(i, t)
    case TensorExpr.L1Loss(i, t, _)               => Seq(i, t)
    case TensorExpr.BCELoss(i, t, w, _)           => Seq(i, t) ++ w
    case TensorExpr.BCEWithLogitsLoss(i, t, w, _) => Seq(i, t) ++ w
    case TensorExpr.OpaqueOp(_, inputs, _, _)     => inputs
    // All single-input ops
    case e =>
      // Use reflection-free approach: match remaining single-input variants
      singleChild(e).toSeq

  /** Extract the single child from a unary TensorExpr. */
  private def singleChild(expr: TensorExpr): Option[TensorExpr] = expr match
    case TensorExpr.Relu(i)                    => Some(i)
    case TensorExpr.LeakyRelu(i, _)            => Some(i)
    case TensorExpr.Sigmoid(i)                 => Some(i)
    case TensorExpr.Tanh(i)                    => Some(i)
    case TensorExpr.Softmax(i, _)              => Some(i)
    case TensorExpr.LogSoftmax(i, _)           => Some(i)
    case TensorExpr.Gelu(i, _)                 => Some(i)
    case TensorExpr.Silu(i)                    => Some(i)
    case TensorExpr.Elu(i, _)                  => Some(i)
    case TensorExpr.HardSwish(i)               => Some(i)
    case TensorExpr.Sqrt(i)                    => Some(i)
    case TensorExpr.Neg(i)                     => Some(i)
    case TensorExpr.Abs(i)                     => Some(i)
    case TensorExpr.Exp(i)                     => Some(i)
    case TensorExpr.Log(i)                     => Some(i)
    case TensorExpr.Clip(i, _, _)              => Some(i)
    case TensorExpr.MaxPool(i, _, _, _)        => Some(i)
    case TensorExpr.AvgPool(i, _, _, _, _)     => Some(i)
    case TensorExpr.GlobalAvgPool(i)           => Some(i)
    case TensorExpr.AdaptiveAvgPool(i, _)      => Some(i)
    case TensorExpr.ReduceSum(i, _, _)         => Some(i)
    case TensorExpr.ReduceMean(i, _, _)        => Some(i)
    case TensorExpr.ReduceMax(i, _, _)         => Some(i)
    case TensorExpr.ReduceMin(i, _, _)         => Some(i)
    case TensorExpr.ReduceProd(i, _, _)        => Some(i)
    case TensorExpr.Reshape(i, _)              => Some(i)
    case TensorExpr.Transpose(i, _)            => Some(i)
    case TensorExpr.Flatten(i, _)              => Some(i)
    case TensorExpr.Squeeze(i, _)              => Some(i)
    case TensorExpr.Unsqueeze(i, _)            => Some(i)
    case TensorExpr.Split(i, _, _)             => Some(i)
    case TensorExpr.TensorSlice(i, _, _, _, _) => Some(i)
    case TensorExpr.Pad(i, _, _, _)            => Some(i)
    case TensorExpr.Expand(i, _)               => Some(i)
    case TensorExpr.Dropout(i, _, _)           => Some(i)
    case TensorExpr.Cast(i, _)                 => Some(i)
    case _                                     => None

  // ============================================================================
  // Op emission helpers
  // ============================================================================

  private def unaryOp(
      opType: String,
      input: TensorExpr,
      outputName: String,
      ctx: ExportContext
  ): String =
    val iName = resolve(input, ctx)
    ctx.onnxNodes += makeNode(opType, Seq(iName), Seq(outputName))
    outputName

  private def binaryOp(
      opType: String,
      left: TensorExpr,
      right: TensorExpr,
      outputName: String,
      ctx: ExportContext
  ): String =
    val lName = resolve(left, ctx)
    val rName = resolve(right, ctx)
    ctx.onnxNodes += makeNode(opType, Seq(lName, rName), Seq(outputName))
    outputName

  private def reduceOp(
      opType: String,
      input: TensorExpr,
      axes: Vector[Int],
      keepDims: Boolean,
      outputName: String,
      ctx: ExportContext
  ): String =
    val iName = resolve(input, ctx)
    val axesName = int64Const(ctx.freshName("axes"), axes.map(_.toLong), ctx)
    ctx.onnxNodes += makeNode(
      opType,
      Seq(iName, axesName),
      Seq(outputName),
      intAttr("keepdims", if keepDims then 1 else 0)
    )
    outputName

  private def emitReduction(
      inputName: String,
      reduction: Reduction,
      outputName: String,
      ctx: ExportContext
  ): Unit =
    reduction match
      case Reduction.Mean =>
        ctx.onnxNodes += makeNode("ReduceMean", Seq(inputName), Seq(outputName))
      case Reduction.Sum =>
        ctx.onnxNodes += makeNode("ReduceSum", Seq(inputName), Seq(outputName))
      case Reduction.None =>
        ctx.onnxNodes += makeNode("Identity", Seq(inputName), Seq(outputName))

  private def reductionStr(r: Reduction): String = r match
    case Reduction.Mean => "mean"
    case Reduction.Sum  => "sum"
    case Reduction.None => "none"

  // ============================================================================
  // OpAttribute → ONNX AttributeProto
  // ============================================================================

  private def toOnnxAttribute(name: String, attr: OpAttribute): ox.AttributeProto = attr match
    case OpAttribute.IntAttr(v)    => intAttr(name, v)
    case OpAttribute.FloatAttr(v)  => floatAttr(name, v.toFloat)
    case OpAttribute.StringAttr(v) => stringAttr(name, v)
    case OpAttribute.IntsAttr(vs)  =>
      val ab = ox.AttributeProto.newBuilder()
      ab.setName(name).setType(ox.AttributeProto.AttributeType.INTS)
      vs.foreach(ab.addInts)
      ab.build()
    case OpAttribute.FloatsAttr(vs) =>
      val ab = ox.AttributeProto.newBuilder()
      ab.setName(name).setType(ox.AttributeProto.AttributeType.FLOATS)
      vs.foreach(v => ab.addFloats(v.toFloat))
      ab.build()
    case OpAttribute.TensorAttr(td) =>
      val tp = toTensorProto(name, TensorType(td.dtype, td.shape.map(Dim(_))), Some(td))
      ox.AttributeProto
        .newBuilder()
        .setName(name)
        .setType(ox.AttributeProto.AttributeType.TENSOR)
        .setT(tp)
        .build()
