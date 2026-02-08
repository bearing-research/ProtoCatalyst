package protocatalyst.ml.codec

import java.nio.{ByteBuffer, ByteOrder}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import onnx.{Onnx => ox}

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph._
import protocatalyst.ml.types._

/** Imports an ONNX ModelProto into a ProtoCatalyst ComputeGraph. */
object OnnxImporter:

  // ============================================================================
  // Public API
  // ============================================================================

  /** Import a ComputeGraph from ONNX model binary bytes. */
  def fromBytes(bytes: Array[Byte]): ComputeGraph =
    fromOnnxModel(ox.ModelProto.parseFrom(bytes))

  /** Import a ComputeGraph from an ONNX ModelProto. */
  def fromOnnxModel(model: ox.ModelProto): ComputeGraph =
    val opsetVersion =
      model.getOpsetImportList.asScala
        .find(_.getDomain.isEmpty)
        .map(_.getVersion.toInt)
        .getOrElse(17)
    val graph = importGraph(model.getGraph)
    graph.copy(opsetVersion = opsetVersion)

  // ============================================================================
  // Import context
  // ============================================================================

  private class ImportContext:
    /** Maps tensor name → TensorExpr (inputs, constants, and intermediate results). */
    val tensorExprs = mutable.Map.empty[String, TensorExpr]

    /** Maps tensor name → TensorType from ValueInfoProto/TensorProto. */
    val tensorTypes = mutable.Map.empty[String, TensorType]

    /** Accumulated NamedNodes in topological order. */
    val namedNodes = mutable.Buffer.empty[NamedNode]

    /** Names of initializers (to distinguish from graph inputs). */
    val initializerNames = mutable.Set.empty[String]

  // ============================================================================
  // Graph-level import
  // ============================================================================

  private def importGraph(graphProto: ox.GraphProto): ComputeGraph =
    val ctx = ImportContext()

    // Phase 1: Collect type information from all sources
    for vi <- graphProto.getInputList.asScala do ctx.tensorTypes(vi.getName) = extractTensorType(vi)
    for vi <- graphProto.getOutputList.asScala do
      ctx.tensorTypes(vi.getName) = extractTensorType(vi)
    for vi <- graphProto.getValueInfoList.asScala do
      ctx.tensorTypes(vi.getName) = extractTensorType(vi)
    for init <- graphProto.getInitializerList.asScala do
      ctx.tensorTypes(init.getName) = extractTensorTypeFromTensor(init)

    // Phase 2: Register initializers as TensorExpr.Constant
    for init <- graphProto.getInitializerList.asScala do
      val name = init.getName
      ctx.initializerNames += name
      val tensorType = ctx.tensorTypes(name)
      val tensorData = extractTensorData(init)
      ctx.tensorExprs(name) = TensorExpr.Constant(name, tensorType, tensorData)

    // Phase 3: Register graph inputs (those not in initializers) as TensorExpr.Input
    for vi <- graphProto.getInputList.asScala do
      val name = vi.getName
      if !ctx.initializerNames.contains(name) then
        ctx.tensorExprs(name) = TensorExpr.Input(name, ctx.tensorTypes(name))

    // Phase 4: Convert each NodeProto in topological order
    for node <- graphProto.getNodeList.asScala do convertNode(node, ctx)

    // Phase 5: Assemble ComputeGraph
    val inputs = graphProto.getInputList.asScala
      .filterNot(vi => ctx.initializerNames.contains(vi.getName))
      .map(vi => GraphIO(vi.getName, ctx.tensorTypes(vi.getName)))
      .toVector

    val outputs = graphProto.getOutputList.asScala
      .map(vi => GraphIO(vi.getName, ctx.tensorTypes(vi.getName)))
      .toVector

    ComputeGraph(
      name = graphProto.getName,
      inputs = inputs,
      outputs = outputs,
      nodes = ctx.namedNodes.toVector
    )

  // ============================================================================
  // Node conversion
  // ============================================================================

  private def convertNode(node: ox.NodeProto, ctx: ImportContext): Unit =
    val opType = node.getOpType
    val domain = node.getDomain
    val outputName = node.getOutput(0)

    // Resolve output type (from value_info, graph outputs, or infer from inputs)
    val outputType = ctx.tensorTypes.getOrElse(outputName, inferOutputType(node, ctx))

    // Handle Identity as passthrough
    if opType == "Identity" then
      val inputExpr = resolve(node.getInput(0), ctx)
      ctx.tensorExprs(outputName) = inputExpr
      return

    // Handle custom domain
    if domain.nonEmpty && domain != "protocatalyst.custom" then
      val expr = importOpaqueOp(node, domain, outputType, ctx)
      ctx.tensorExprs(outputName) = expr
      ctx.namedNodes += NamedNode(outputName, expr, outputType)
      return

    val expr: TensorExpr =
      if domain == "protocatalyst.custom" then importCustomDomainOp(node, outputType, ctx)
      else
        opType match
          // Activations (unary)
          case "Relu"    => TensorExpr.Relu(resolve(node.getInput(0), ctx))
          case "Sigmoid" => TensorExpr.Sigmoid(resolve(node.getInput(0), ctx))
          case "Tanh"    => TensorExpr.Tanh(resolve(node.getInput(0), ctx))
          case "Sqrt"    => TensorExpr.Sqrt(resolve(node.getInput(0), ctx))
          case "Neg"     => TensorExpr.Neg(resolve(node.getInput(0), ctx))
          case "Abs"     => TensorExpr.Abs(resolve(node.getInput(0), ctx))
          case "Exp"     => TensorExpr.Exp(resolve(node.getInput(0), ctx))
          case "Log"     => TensorExpr.Log(resolve(node.getInput(0), ctx))

          case "LeakyRelu" =>
            TensorExpr.LeakyRelu(resolve(node.getInput(0), ctx), getFloatAttr(node, "alpha", 0.01f))

          case "Elu" =>
            TensorExpr.Elu(resolve(node.getInput(0), ctx), getFloatAttr(node, "alpha", 1.0f))

          case "Softmax" =>
            TensorExpr.Softmax(resolve(node.getInput(0), ctx), getIntAttr(node, "axis", -1).toInt)

          case "LogSoftmax" =>
            TensorExpr.LogSoftmax(
              resolve(node.getInput(0), ctx),
              getIntAttr(node, "axis", -1).toInt
            )

          // Element-wise binary
          case "Add" =>
            TensorExpr.Add(resolve(node.getInput(0), ctx), resolve(node.getInput(1), ctx))
          case "Sub" =>
            TensorExpr.Sub(resolve(node.getInput(0), ctx), resolve(node.getInput(1), ctx))
          case "Mul" =>
            TensorExpr.Mul(resolve(node.getInput(0), ctx), resolve(node.getInput(1), ctx))
          case "Div" =>
            TensorExpr.Div(resolve(node.getInput(0), ctx), resolve(node.getInput(1), ctx))
          case "Pow" =>
            TensorExpr.Pow(resolve(node.getInput(0), ctx), resolve(node.getInput(1), ctx))
          case "Equal" =>
            TensorExpr.Equal(resolve(node.getInput(0), ctx), resolve(node.getInput(1), ctx))
          case "Greater" =>
            TensorExpr.Greater(resolve(node.getInput(0), ctx), resolve(node.getInput(1), ctx))
          case "Less" =>
            TensorExpr.Less(resolve(node.getInput(0), ctx), resolve(node.getInput(1), ctx))

          case "Where" =>
            TensorExpr.Where(
              resolve(node.getInput(0), ctx),
              resolve(node.getInput(1), ctx),
              resolve(node.getInput(2), ctx)
            )

          // Linear algebra
          case "MatMul" =>
            TensorExpr.MatMul(
              resolve(node.getInput(0), ctx),
              resolve(node.getInput(1), ctx),
              transA = false,
              transB = false
            )

          case "Gemm" => importGemm(node, ctx)

          // Convolution
          case "Conv" =>
            val bias = optionalInput(node, 2).map(resolve(_, ctx))
            TensorExpr.Conv(
              resolve(node.getInput(0), ctx),
              resolve(node.getInput(1), ctx),
              bias,
              getIntsAttr(node, "strides", Vector(1, 1)),
              getIntsAttr(node, "pads", Vector(0, 0, 0, 0)),
              getIntsAttr(node, "dilations", Vector(1, 1)),
              getIntAttr(node, "group", 1).toInt
            )

          case "ConvTranspose" =>
            val bias = optionalInput(node, 2).map(resolve(_, ctx))
            TensorExpr.ConvTranspose(
              resolve(node.getInput(0), ctx),
              resolve(node.getInput(1), ctx),
              bias,
              getIntsAttr(node, "strides", Vector(1, 1)),
              getIntsAttr(node, "pads", Vector(0, 0, 0, 0)),
              getIntsAttr(node, "output_padding", Vector(0, 0)),
              getIntsAttr(node, "dilations", Vector(1, 1)),
              getIntAttr(node, "group", 1).toInt
            )

          // Pooling
          case "MaxPool" =>
            TensorExpr.MaxPool(
              resolve(node.getInput(0), ctx),
              getIntsAttr(node, "kernel_shape", Vector(2, 2)),
              getIntsAttr(node, "strides", Vector(1, 1)),
              getIntsAttr(node, "pads", Vector(0, 0, 0, 0))
            )

          case "AveragePool" =>
            TensorExpr.AvgPool(
              resolve(node.getInput(0), ctx),
              getIntsAttr(node, "kernel_shape", Vector(2, 2)),
              getIntsAttr(node, "strides", Vector(1, 1)),
              getIntsAttr(node, "pads", Vector(0, 0, 0, 0)),
              getIntAttr(node, "count_include_pad", 0) != 0
            )

          case "GlobalAveragePool" =>
            TensorExpr.GlobalAvgPool(resolve(node.getInput(0), ctx))

          // Normalization
          case "BatchNormalization" =>
            TensorExpr.BatchNorm(
              resolve(node.getInput(0), ctx),
              resolve(node.getInput(1), ctx),
              resolve(node.getInput(2), ctx),
              resolve(node.getInput(3), ctx),
              resolve(node.getInput(4), ctx),
              epsilon = getFloatAttr(node, "epsilon", 1e-5f),
              momentum = getFloatAttr(node, "momentum", 0.9f),
              training = false
            )

          case "LayerNormalization" =>
            val weight = optionalInput(node, 1).map(resolve(_, ctx))
            val bias = optionalInput(node, 2).map(resolve(_, ctx))
            TensorExpr.LayerNorm(
              resolve(node.getInput(0), ctx),
              normalizedShape = Vector.empty, // Not stored in ONNX LayerNormalization
              weight,
              bias,
              epsilon = getFloatAttr(node, "epsilon", 1e-5f)
            )

          case "InstanceNormalization" =>
            TensorExpr.InstanceNorm(
              resolve(node.getInput(0), ctx),
              resolve(node.getInput(1), ctx),
              resolve(node.getInput(2), ctx),
              epsilon = getFloatAttr(node, "epsilon", 1e-5f)
            )

          // Reduction
          case "ReduceSum"  => importReduceOp(node, "ReduceSum", ctx)
          case "ReduceMean" => importReduceOp(node, "ReduceMean", ctx)
          case "ReduceMax"  => importReduceOp(node, "ReduceMax", ctx)
          case "ReduceMin"  => importReduceOp(node, "ReduceMin", ctx)
          case "ReduceProd" => importReduceOp(node, "ReduceProd", ctx)

          // Shape manipulation
          case "Reshape" =>
            val shape = resolveConstantInts(node.getInput(1), ctx)
            TensorExpr.Reshape(resolve(node.getInput(0), ctx), shape)

          case "Transpose" =>
            val perm = getIntsAttr(node, "perm", Vector.empty)
            TensorExpr.Transpose(resolve(node.getInput(0), ctx), perm)

          case "Flatten" =>
            TensorExpr.Flatten(resolve(node.getInput(0), ctx), getIntAttr(node, "axis", 1).toInt)

          case "Squeeze" =>
            val axes =
              if node.getInputCount > 1 then resolveConstantInts(node.getInput(1), ctx)
              else getIntsAttr(node, "axes", Vector.empty)
            TensorExpr.Squeeze(resolve(node.getInput(0), ctx), axes)

          case "Unsqueeze" =>
            val axes =
              if node.getInputCount > 1 then resolveConstantInts(node.getInput(1), ctx)
              else getIntsAttr(node, "axes", Vector.empty)
            TensorExpr.Unsqueeze(resolve(node.getInput(0), ctx), axes)

          case "Concat" =>
            val inputs =
              (0 until node.getInputCount).map(i => resolve(node.getInput(i), ctx)).toVector
            TensorExpr.TensorConcat(inputs, getIntAttr(node, "axis", 0).toInt)

          case "Split" =>
            val splitSizes =
              if node.getInputCount > 1 then resolveConstantInts(node.getInput(1), ctx)
              else getIntsAttr(node, "split", Vector.empty)
            TensorExpr.Split(
              resolve(node.getInput(0), ctx),
              getIntAttr(node, "axis", 0).toInt,
              splitSizes
            )

          case "Slice" =>
            val starts = resolveConstantInts(node.getInput(1), ctx)
            val ends = resolveConstantInts(node.getInput(2), ctx)
            val axes =
              if node.getInputCount > 3 && node.getInput(3).nonEmpty then
                resolveConstantInts(node.getInput(3), ctx)
              else (0 until starts.size).toVector
            val steps =
              if node.getInputCount > 4 && node.getInput(4).nonEmpty then
                resolveConstantInts(node.getInput(4), ctx)
              else Vector.fill(starts.size)(1)
            TensorExpr.TensorSlice(resolve(node.getInput(0), ctx), starts, ends, axes, steps)

          case "Gather" =>
            TensorExpr.Gather(
              resolve(node.getInput(0), ctx),
              resolve(node.getInput(1), ctx),
              getIntAttr(node, "axis", 0).toInt
            )

          case "ScatterElements" =>
            TensorExpr.Scatter(
              resolve(node.getInput(0), ctx),
              resolve(node.getInput(1), ctx),
              resolve(node.getInput(2), ctx),
              getIntAttr(node, "axis", 0).toInt
            )

          case "Pad" =>
            val pads = resolveConstantInts(node.getInput(1), ctx)
            val constantValue =
              if node.getInputCount > 2 && node.getInput(2).nonEmpty then
                resolveConstantFloat(node.getInput(2), ctx)
              else 0.0
            val mode = getStringAttr(node, "mode", "constant") match
              case "reflect" => PadMode.Reflect
              case "edge"    => PadMode.Edge
              case _         => PadMode.Constant
            TensorExpr.Pad(resolve(node.getInput(0), ctx), pads, mode, constantValue)

          case "Expand" =>
            val shape = resolveConstantInts(node.getInput(1), ctx)
            TensorExpr.Expand(resolve(node.getInput(0), ctx), shape)

          case "Clip" =>
            val min =
              if node.getInputCount > 1 && node.getInput(1).nonEmpty then
                Some(resolveConstantFloat(node.getInput(1), ctx))
              else None
            val max =
              if node.getInputCount > 2 && node.getInput(2).nonEmpty then
                Some(resolveConstantFloat(node.getInput(2), ctx))
              else None
            TensorExpr.Clip(resolve(node.getInput(0), ctx), min, max)

          // Type casting
          case "Cast" =>
            val targetDType = fromOnnxDataType(getIntAttr(node, "to", 1).toInt)
            TensorExpr.Cast(resolve(node.getInput(0), ctx), targetDType)

          // Recurrent
          case "LSTM" =>
            val bias = optionalInput(node, 3).map(resolve(_, ctx))
            val direction = getStringAttr(node, "direction", "forward")
            TensorExpr.LSTM(
              resolve(node.getInput(0), ctx),
              resolve(node.getInput(1), ctx),
              resolve(node.getInput(2), ctx),
              bias,
              hiddenSize = getIntAttr(node, "hidden_size", 1).toInt,
              numLayers = 1,
              bidirectional = direction == "bidirectional",
              dropout = 0.0
            )

          case "GRU" =>
            val bias = optionalInput(node, 3).map(resolve(_, ctx))
            val direction = getStringAttr(node, "direction", "forward")
            TensorExpr.GRU(
              resolve(node.getInput(0), ctx),
              resolve(node.getInput(1), ctx),
              resolve(node.getInput(2), ctx),
              bias,
              hiddenSize = getIntAttr(node, "hidden_size", 1).toInt,
              numLayers = 1,
              bidirectional = direction == "bidirectional",
              dropout = 0.0
            )

          // Loss
          case "SoftmaxCrossEntropyLoss" =>
            val weight = optionalInput(node, 2).map(resolve(_, ctx))
            val reduction = parseReduction(getStringAttr(node, "reduction", "mean"))
            TensorExpr.CrossEntropyLoss(
              resolve(node.getInput(0), ctx),
              resolve(node.getInput(1), ctx),
              weight,
              reduction
            )

          // Dropout (training mode)
          case "Dropout" =>
            val ratio =
              if node.getInputCount > 1 && node.getInput(1).nonEmpty then
                resolveConstantFloat(node.getInput(1), ctx)
              else 0.5
            TensorExpr.Dropout(resolve(node.getInput(0), ctx), ratio, training = true)

          // Unknown ops → OpaqueOp
          case other =>
            importOpaqueOp(node, domain, outputType, ctx)

    ctx.tensorExprs(outputName) = expr
    ctx.namedNodes += NamedNode(outputName, expr, outputType)

  // ============================================================================
  // Specialized import helpers
  // ============================================================================

  /** Import Gemm, recognizing Linear pattern (transB=1, alpha=1, beta=1). */
  private def importGemm(node: ox.NodeProto, ctx: ImportContext): TensorExpr =
    val transA = getIntAttr(node, "transA", 0) != 0
    val transB = getIntAttr(node, "transB", 0) != 0
    val alpha = getFloatAttr(node, "alpha", 1.0f)
    val beta = getFloatAttr(node, "beta", 1.0f)
    val bias = optionalInput(node, 2).map(resolve(_, ctx))

    // Recognize Linear: Gemm(x, w, b, transB=1, alpha=1, beta=1, transA=0)
    if transB && !transA && alpha == 1.0f && beta == 1.0f then
      TensorExpr.Linear(resolve(node.getInput(0), ctx), resolve(node.getInput(1), ctx), bias)
    else
      TensorExpr.Gemm(
        resolve(node.getInput(0), ctx),
        resolve(node.getInput(1), ctx),
        bias,
        alpha.toDouble,
        beta.toDouble,
        transA,
        transB
      )

  /** Import reduce ops (axes from tensor input in opset 13+, or from attribute). */
  private def importReduceOp(
      node: ox.NodeProto,
      opType: String,
      ctx: ImportContext
  ): TensorExpr =
    val input = resolve(node.getInput(0), ctx)
    val keepDims = getIntAttr(node, "keepdims", 1) != 0
    val axes =
      if node.getInputCount > 1 && node.getInput(1).nonEmpty then
        resolveConstantInts(node.getInput(1), ctx)
      else getIntsAttr(node, "axes", Vector.empty)

    opType match
      case "ReduceSum"  => TensorExpr.ReduceSum(input, axes, keepDims)
      case "ReduceMean" => TensorExpr.ReduceMean(input, axes, keepDims)
      case "ReduceMax"  => TensorExpr.ReduceMax(input, axes, keepDims)
      case "ReduceMin"  => TensorExpr.ReduceMin(input, axes, keepDims)
      case "ReduceProd" => TensorExpr.ReduceProd(input, axes, keepDims)
      case _            => throw IllegalArgumentException(s"Unknown reduce op: $opType")

  /** Import custom domain ops (protocatalyst.custom). */
  private def importCustomDomainOp(
      node: ox.NodeProto,
      outputType: TensorType,
      ctx: ImportContext
  ): TensorExpr =
    node.getOpType match
      case "GroupNorm" =>
        val weight = optionalInput(node, 1).map(resolve(_, ctx))
        val bias = optionalInput(node, 2).map(resolve(_, ctx))
        TensorExpr.GroupNorm(
          resolve(node.getInput(0), ctx),
          numGroups = getIntAttr(node, "num_groups", 1).toInt,
          weight,
          bias,
          epsilon = getFloatAttr(node, "epsilon", 1e-5f)
        )

      case "AdaptiveAveragePool" =>
        TensorExpr.AdaptiveAvgPool(
          resolve(node.getInput(0), ctx),
          getIntsAttr(node, "output_size", Vector(1, 1))
        )

      case _ => importOpaqueOp(node, "protocatalyst.custom", outputType, ctx)

  /** Import an unknown op as OpaqueOp. */
  private def importOpaqueOp(
      node: ox.NodeProto,
      domain: String,
      outputType: TensorType,
      ctx: ImportContext
  ): TensorExpr =
    val inputs = (0 until node.getInputCount)
      .filter(i => node.getInput(i).nonEmpty)
      .map(i => resolve(node.getInput(i), ctx))
      .toVector
    val attrs = node.getAttributeList.asScala.map { attr =>
      attr.getName -> fromOnnxAttribute(attr)
    }.toMap
    TensorExpr.OpaqueOp(node.getOpType, inputs, attrs, outputType)

  // ============================================================================
  // Type extraction
  // ============================================================================

  private def fromOnnxDataType(dt: Int): TensorDType = dt match
    case 1  => TensorDType.Float32
    case 2  => TensorDType.UInt8
    case 3  => TensorDType.Int8
    case 5  => TensorDType.Int16
    case 6  => TensorDType.Int32
    case 7  => TensorDType.Int64
    case 9  => TensorDType.Bool
    case 10 => TensorDType.Float16
    case 11 => TensorDType.Float64
    case 14 => TensorDType.Complex64
    case 15 => TensorDType.Complex128
    case 16 => TensorDType.BFloat16
    case _  => TensorDType.Float32 // fallback

  private def extractTensorType(vi: ox.ValueInfoProto): TensorType =
    val tt = vi.getType.getTensorType
    val dtype = fromOnnxDataType(tt.getElemType)
    val shape = tt.getShape.getDimList.asScala.map { dim =>
      if dim.hasDimValue then Dim.Static(dim.getDimValue.toInt)
      else if dim.hasDimParam && dim.getDimParam.nonEmpty then Dim.Dynamic(Some(dim.getDimParam))
      else Dim.Dynamic(None)
    }.toVector
    TensorType(dtype, shape)

  private def extractTensorTypeFromTensor(tp: ox.TensorProto): TensorType =
    val dtype = fromOnnxDataType(tp.getDataType)
    val shape = tp.getDimsList.asScala.map(d => Dim.Static(d.toInt)).toVector
    TensorType(dtype, shape)

  /** Extract TensorData from a TensorProto. */
  private def extractTensorData(tp: ox.TensorProto): TensorData =
    val dtype = fromOnnxDataType(tp.getDataType)
    val shape = tp.getDimsList.asScala.map(_.toInt).toVector
    val rawBytes =
      if tp.getRawData.size() > 0 then tp.getRawData.toByteArray
      else if tp.getFloatDataCount > 0 then
        val buf = ByteBuffer.allocate(tp.getFloatDataCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        tp.getFloatDataList.asScala.foreach(f => buf.putFloat(f))
        buf.array()
      else if tp.getInt64DataCount > 0 then
        val buf = ByteBuffer.allocate(tp.getInt64DataCount * 8).order(ByteOrder.LITTLE_ENDIAN)
        tp.getInt64DataList.asScala.foreach(v => buf.putLong(v))
        buf.array()
      else if tp.getInt32DataCount > 0 then
        val buf = ByteBuffer.allocate(tp.getInt32DataCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        tp.getInt32DataList.asScala.foreach(v => buf.putInt(v))
        buf.array()
      else if tp.getDoubleDataCount > 0 then
        val buf = ByteBuffer.allocate(tp.getDoubleDataCount * 8).order(ByteOrder.LITTLE_ENDIAN)
        tp.getDoubleDataList.asScala.foreach(v => buf.putDouble(v))
        buf.array()
      else Array.emptyByteArray
    TensorData(dtype, shape, rawBytes)

  // ============================================================================
  // Constant resolution helpers
  // ============================================================================

  /** Resolve a constant tensor to its int values (for shape, axes, etc.). */
  private def resolveConstantInts(name: String, ctx: ImportContext): Vector[Int] =
    ctx.tensorExprs.get(name) match
      case Some(TensorExpr.Constant(_, tt, data)) =>
        tt.dtype match
          case TensorDType.Int64 =>
            val buf = ByteBuffer.wrap(data.rawBytes).order(ByteOrder.LITTLE_ENDIAN)
            (0 until data.numElements.toInt).map(_ => buf.getLong.toInt).toVector
          case TensorDType.Int32 =>
            val buf = ByteBuffer.wrap(data.rawBytes).order(ByteOrder.LITTLE_ENDIAN)
            (0 until data.numElements.toInt).map(_ => buf.getInt).toVector
          case _ =>
            throw IllegalStateException(s"Expected int tensor for '$name', got ${tt.dtype}")
      case _ =>
        throw IllegalStateException(s"Cannot resolve constant ints for '$name'")

  /** Resolve a constant tensor to a single float value. */
  private def resolveConstantFloat(name: String, ctx: ImportContext): Double =
    ctx.tensorExprs.get(name) match
      case Some(TensorExpr.Constant(_, tt, data)) =>
        tt.dtype match
          case TensorDType.Float32 =>
            ByteBuffer.wrap(data.rawBytes).order(ByteOrder.LITTLE_ENDIAN).getFloat.toDouble
          case TensorDType.Float64 =>
            ByteBuffer.wrap(data.rawBytes).order(ByteOrder.LITTLE_ENDIAN).getDouble
          case _ =>
            throw IllegalStateException(s"Expected float tensor for '$name', got ${tt.dtype}")
      case _ =>
        throw IllegalStateException(s"Cannot resolve constant float for '$name'")

  // ============================================================================
  // Attribute helpers
  // ============================================================================

  private def resolve(name: String, ctx: ImportContext): TensorExpr =
    ctx.tensorExprs.getOrElse(
      name,
      throw IllegalStateException(s"Unresolved tensor: '$name'")
    )

  private def optionalInput(node: ox.NodeProto, index: Int): Option[String] =
    if index < node.getInputCount && node.getInput(index).nonEmpty then Some(node.getInput(index))
    else None

  private def getIntAttr(node: ox.NodeProto, name: String, default: Long): Long =
    node.getAttributeList.asScala
      .find(_.getName == name)
      .map(_.getI)
      .getOrElse(default)

  private def getFloatAttr(node: ox.NodeProto, name: String, default: Float): Float =
    node.getAttributeList.asScala
      .find(_.getName == name)
      .map(_.getF)
      .getOrElse(default)

  private def getStringAttr(node: ox.NodeProto, name: String, default: String): String =
    node.getAttributeList.asScala
      .find(_.getName == name)
      .map(_.getS.toStringUtf8)
      .getOrElse(default)

  private def getIntsAttr(node: ox.NodeProto, name: String, default: Vector[Int]): Vector[Int] =
    node.getAttributeList.asScala
      .find(_.getName == name)
      .map(_.getIntsList.asScala.map(_.toInt).toVector)
      .getOrElse(default)

  /** Convert an ONNX AttributeProto back to OpAttribute (for OpaqueOp). */
  private def fromOnnxAttribute(attr: ox.AttributeProto): OpAttribute =
    attr.getType match
      case ox.AttributeProto.AttributeType.INT    => OpAttribute.IntAttr(attr.getI)
      case ox.AttributeProto.AttributeType.FLOAT  => OpAttribute.FloatAttr(attr.getF.toDouble)
      case ox.AttributeProto.AttributeType.STRING => OpAttribute.StringAttr(attr.getS.toStringUtf8)
      case ox.AttributeProto.AttributeType.INTS   =>
        OpAttribute.IntsAttr(attr.getIntsList.asScala.map(_.toLong).toVector)
      case ox.AttributeProto.AttributeType.FLOATS =>
        OpAttribute.FloatsAttr(attr.getFloatsList.asScala.map(_.toDouble).toVector)
      case ox.AttributeProto.AttributeType.TENSOR =>
        val td = extractTensorData(attr.getT)
        OpAttribute.TensorAttr(td)
      case _ => OpAttribute.StringAttr(s"<unsupported: ${attr.getType}>")

  private def parseReduction(s: String): Reduction = s match
    case "mean" => Reduction.Mean
    case "sum"  => Reduction.Sum
    case "none" => Reduction.None
    case _      => Reduction.Mean

  // ============================================================================
  // Type inference (fallback when value_info is missing)
  // ============================================================================

  /** Infer output type from op type and input types. */
  private def inferOutputType(node: ox.NodeProto, ctx: ImportContext): TensorType =
    // For most ops, the output type matches the first input's type
    if node.getInputCount > 0 && node.getInput(0).nonEmpty then
      ctx.tensorExprs.get(node.getInput(0)) match
        case Some(TensorExpr.Input(_, tt))       => tt
        case Some(TensorExpr.Constant(_, tt, _)) => tt
        case _                                   =>
          ctx.tensorTypes.getOrElse(node.getInput(0), TensorType(TensorDType.Float32, Vector.empty))
    else TensorType(TensorDType.Float32, Vector.empty)
