package protocatalyst.ml.codec

import com.google.protobuf.ByteString

import protocatalyst.ml.artifact._
import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph._
import protocatalyst.ml.types._

import io.protocatalyst.proto.v1 as pb

/** Bidirectional conversion between Scala ML IR types and generated Java protobuf types. */
object MLProtoConverter:

  // ============================================================================
  // Top-level: CompiledMLArtifact
  // ============================================================================

  def toProto(artifact: CompiledMLArtifact): pb.CompiledMLArtifactMsg =
    val b = pb.CompiledMLArtifactMsg.newBuilder()
    b.setFormatVersion(toProtoVersion(artifact.formatVersion))
    b.setProtocatalystVersion(artifact.protocatalystVersion)
    b.setCompiledAt(artifact.compiledAt)
    b.setContentHash(artifact.contentHash)
    b.setGraph(toProtoGraph(artifact.graph))
    artifact.sourceInfo.foreach(si => b.setSourceInfo(toProtoMLSourceInfo(si)))
    b.build()

  def fromProto(msg: pb.CompiledMLArtifactMsg): CompiledMLArtifact =
    CompiledMLArtifact(
      formatVersion = fromProtoVersion(msg.getFormatVersion),
      protocatalystVersion = msg.getProtocatalystVersion,
      compiledAt = msg.getCompiledAt,
      contentHash = msg.getContentHash,
      graph = fromProtoGraph(msg.getGraph),
      sourceInfo =
        if msg.hasSourceInfo then Some(fromProtoMLSourceInfo(msg.getSourceInfo)) else None
    )

  // ============================================================================
  // Version / SourceInfo
  // ============================================================================

  private def toProtoVersion(v: MLArtifactVersion): pb.MLArtifactVersionMsg =
    pb.MLArtifactVersionMsg
      .newBuilder()
      .setMajor(v.major)
      .setMinor(v.minor)
      .setPatch(v.patch)
      .build()

  private def fromProtoVersion(msg: pb.MLArtifactVersionMsg): MLArtifactVersion =
    MLArtifactVersion(msg.getMajor, msg.getMinor, msg.getPatch)

  private def toProtoMLSourceInfo(si: MLSourceInfo): pb.MLSourceInfoMsg =
    pb.MLSourceInfoMsg
      .newBuilder()
      .setSourceFile(si.sourceFile)
      .setLineNumber(si.lineNumber)
      .build()

  private def fromProtoMLSourceInfo(msg: pb.MLSourceInfoMsg): MLSourceInfo =
    MLSourceInfo(msg.getSourceFile, msg.getLineNumber)

  // ============================================================================
  // ComputeGraph
  // ============================================================================

  def toProtoGraph(graph: ComputeGraph): pb.ComputeGraphMsg =
    val b = pb.ComputeGraphMsg.newBuilder()
    b.setName(graph.name)
    graph.inputs.foreach(io => b.addInputs(toProtoGraphIO(io)))
    graph.outputs.foreach(io => b.addOutputs(toProtoGraphIO(io)))
    graph.nodes.foreach(n => b.addNodes(toProtoNamedNode(n)))
    b.setOpsetVersion(graph.opsetVersion)
    b.build()

  def fromProtoGraph(msg: pb.ComputeGraphMsg): ComputeGraph =
    ComputeGraph(
      name = msg.getName,
      inputs = (0 until msg.getInputsCount).map(i => fromProtoGraphIO(msg.getInputs(i))).toVector,
      outputs =
        (0 until msg.getOutputsCount).map(i => fromProtoGraphIO(msg.getOutputs(i))).toVector,
      nodes = (0 until msg.getNodesCount).map(i => fromProtoNamedNode(msg.getNodes(i))).toVector,
      opsetVersion = msg.getOpsetVersion
    )

  private def toProtoGraphIO(io: GraphIO): pb.GraphIOMsg =
    pb.GraphIOMsg
      .newBuilder()
      .setName(io.name)
      .setTensorType(toProtoTensorType(io.tensorType))
      .build()

  private def fromProtoGraphIO(msg: pb.GraphIOMsg): GraphIO =
    GraphIO(msg.getName, fromProtoTensorType(msg.getTensorType))

  private def toProtoNamedNode(node: NamedNode): pb.NamedNodeMsg =
    pb.NamedNodeMsg
      .newBuilder()
      .setName(node.name)
      .setExpr(toProtoExpr(node.expr))
      .setOutputType(toProtoTensorType(node.outputType))
      .build()

  private def fromProtoNamedNode(msg: pb.NamedNodeMsg): NamedNode =
    NamedNode(msg.getName, fromProtoExpr(msg.getExpr), fromProtoTensorType(msg.getOutputType))

  // ============================================================================
  // TensorType
  // ============================================================================

  def toProtoTensorType(tt: TensorType): pb.TensorTypeMsg =
    val b = pb.TensorTypeMsg.newBuilder()
    b.setDtype(toProtoDType(tt.dtype))
    tt.shape.foreach(d => b.addShape(toProtoDim(d)))
    b.setLayout(toProtoLayout(tt.layout))
    b.build()

  def fromProtoTensorType(msg: pb.TensorTypeMsg): TensorType =
    TensorType(
      dtype = fromProtoDType(msg.getDtype),
      shape = (0 until msg.getShapeCount).map(i => fromProtoDim(msg.getShape(i))).toVector,
      layout = fromProtoLayout(msg.getLayout)
    )

  // ============================================================================
  // TensorDType
  // ============================================================================

  def toProtoDType(dt: TensorDType): pb.TensorDTypeEnum = dt match
    case TensorDType.Float16    => pb.TensorDTypeEnum.TENSOR_DTYPE_FLOAT16
    case TensorDType.Float32    => pb.TensorDTypeEnum.TENSOR_DTYPE_FLOAT32
    case TensorDType.Float64    => pb.TensorDTypeEnum.TENSOR_DTYPE_FLOAT64
    case TensorDType.BFloat16   => pb.TensorDTypeEnum.TENSOR_DTYPE_BFLOAT16
    case TensorDType.Int8       => pb.TensorDTypeEnum.TENSOR_DTYPE_INT8
    case TensorDType.Int16      => pb.TensorDTypeEnum.TENSOR_DTYPE_INT16
    case TensorDType.Int32      => pb.TensorDTypeEnum.TENSOR_DTYPE_INT32
    case TensorDType.Int64      => pb.TensorDTypeEnum.TENSOR_DTYPE_INT64
    case TensorDType.UInt8      => pb.TensorDTypeEnum.TENSOR_DTYPE_UINT8
    case TensorDType.Bool       => pb.TensorDTypeEnum.TENSOR_DTYPE_BOOL
    case TensorDType.Complex64  => pb.TensorDTypeEnum.TENSOR_DTYPE_COMPLEX64
    case TensorDType.Complex128 => pb.TensorDTypeEnum.TENSOR_DTYPE_COMPLEX128

  def fromProtoDType(dt: pb.TensorDTypeEnum): TensorDType = dt match
    case pb.TensorDTypeEnum.TENSOR_DTYPE_FLOAT16    => TensorDType.Float16
    case pb.TensorDTypeEnum.TENSOR_DTYPE_FLOAT32    => TensorDType.Float32
    case pb.TensorDTypeEnum.TENSOR_DTYPE_FLOAT64    => TensorDType.Float64
    case pb.TensorDTypeEnum.TENSOR_DTYPE_BFLOAT16   => TensorDType.BFloat16
    case pb.TensorDTypeEnum.TENSOR_DTYPE_INT8       => TensorDType.Int8
    case pb.TensorDTypeEnum.TENSOR_DTYPE_INT16      => TensorDType.Int16
    case pb.TensorDTypeEnum.TENSOR_DTYPE_INT32      => TensorDType.Int32
    case pb.TensorDTypeEnum.TENSOR_DTYPE_INT64      => TensorDType.Int64
    case pb.TensorDTypeEnum.TENSOR_DTYPE_UINT8      => TensorDType.UInt8
    case pb.TensorDTypeEnum.TENSOR_DTYPE_BOOL       => TensorDType.Bool
    case pb.TensorDTypeEnum.TENSOR_DTYPE_COMPLEX64  => TensorDType.Complex64
    case pb.TensorDTypeEnum.TENSOR_DTYPE_COMPLEX128 => TensorDType.Complex128
    case pb.TensorDTypeEnum.UNRECOGNIZED => throw new IllegalArgumentException("Unrecognized dtype")

  // ============================================================================
  // Dim / DataLayout
  // ============================================================================

  private def toProtoDim(d: Dim): pb.DimMsg = d match
    case Dim.Static(size)  => pb.DimMsg.newBuilder().setStaticSize(size).build()
    case Dim.Dynamic(name) =>
      val dd = pb.DynamicDimMsg.newBuilder()
      name.foreach(dd.setName)
      pb.DimMsg.newBuilder().setDynamic(dd).build()

  private def fromProtoDim(msg: pb.DimMsg): Dim =
    msg.getDimCase match
      case pb.DimMsg.DimCase.STATIC_SIZE => Dim.Static(msg.getStaticSize)
      case pb.DimMsg.DimCase.DYNAMIC     =>
        val dd = msg.getDynamic
        Dim.Dynamic(if dd.hasName then Some(dd.getName) else None)
      case pb.DimMsg.DimCase.DIM_NOT_SET =>
        throw new IllegalArgumentException("Dim not set")

  private def toProtoLayout(layout: DataLayout): pb.DataLayoutEnum = layout match
    case DataLayout.Default => pb.DataLayoutEnum.DATA_LAYOUT_DEFAULT
    case DataLayout.NCHW    => pb.DataLayoutEnum.DATA_LAYOUT_NCHW
    case DataLayout.NHWC    => pb.DataLayoutEnum.DATA_LAYOUT_NHWC

  private def fromProtoLayout(layout: pb.DataLayoutEnum): DataLayout = layout match
    case pb.DataLayoutEnum.DATA_LAYOUT_DEFAULT => DataLayout.Default
    case pb.DataLayoutEnum.DATA_LAYOUT_NCHW    => DataLayout.NCHW
    case pb.DataLayoutEnum.DATA_LAYOUT_NHWC    => DataLayout.NHWC
    case pb.DataLayoutEnum.UNRECOGNIZED        =>
      throw new IllegalArgumentException("Unrecognized layout")

  // ============================================================================
  // TensorData / Initializer / Reduction / PadMode / OpAttribute
  // ============================================================================

  def toProtoTensorData(td: TensorData): pb.TensorDataMsg =
    val b = pb.TensorDataMsg.newBuilder()
    b.setDtype(toProtoDType(td.dtype))
    td.shape.foreach(b.addShape(_))
    b.setRawBytes(ByteString.copyFrom(td.rawBytes))
    b.build()

  def fromProtoTensorData(msg: pb.TensorDataMsg): TensorData =
    TensorData(
      dtype = fromProtoDType(msg.getDtype),
      shape = (0 until msg.getShapeCount).map(msg.getShape).toVector,
      rawBytes = msg.getRawBytes.toByteArray
    )

  def toProtoInitializer(init: Initializer): pb.InitializerMsg = init match
    case Initializer.Zeros =>
      pb.InitializerMsg.newBuilder().setZeros(pb.EmptyMsg.getDefaultInstance).build()
    case Initializer.Ones =>
      pb.InitializerMsg.newBuilder().setOnes(pb.EmptyMsg.getDefaultInstance).build()
    case Initializer.Xavier(gain) =>
      pb.InitializerMsg
        .newBuilder()
        .setXavier(pb.XavierInitMsg.newBuilder().setGain(gain))
        .build()
    case Initializer.Kaiming(mode, nl) =>
      pb.InitializerMsg
        .newBuilder()
        .setKaiming(pb.KaimingInitMsg.newBuilder().setMode(mode).setNonlinearity(nl))
        .build()
    case Initializer.Normal(mean, std) =>
      pb.InitializerMsg
        .newBuilder()
        .setNormal(pb.NormalInitMsg.newBuilder().setMean(mean).setStd(std))
        .build()
    case Initializer.Uniform(low, high) =>
      pb.InitializerMsg
        .newBuilder()
        .setUniform(pb.UniformInitMsg.newBuilder().setLow(low).setHigh(high))
        .build()

  def fromProtoInitializer(msg: pb.InitializerMsg): Initializer =
    msg.getInitializerCase match
      case pb.InitializerMsg.InitializerCase.ZEROS   => Initializer.Zeros
      case pb.InitializerMsg.InitializerCase.ONES    => Initializer.Ones
      case pb.InitializerMsg.InitializerCase.XAVIER  => Initializer.Xavier(msg.getXavier.getGain)
      case pb.InitializerMsg.InitializerCase.KAIMING =>
        val k = msg.getKaiming; Initializer.Kaiming(k.getMode, k.getNonlinearity)
      case pb.InitializerMsg.InitializerCase.NORMAL =>
        val n = msg.getNormal; Initializer.Normal(n.getMean, n.getStd)
      case pb.InitializerMsg.InitializerCase.UNIFORM =>
        val u = msg.getUniform; Initializer.Uniform(u.getLow, u.getHigh)
      case pb.InitializerMsg.InitializerCase.INITIALIZER_NOT_SET =>
        throw new IllegalArgumentException("Initializer not set")

  private def toProtoReduction(r: Reduction): pb.ReductionEnum = r match
    case Reduction.Mean => pb.ReductionEnum.REDUCTION_MEAN
    case Reduction.Sum  => pb.ReductionEnum.REDUCTION_SUM
    case Reduction.None => pb.ReductionEnum.REDUCTION_NONE

  private def fromProtoReduction(r: pb.ReductionEnum): Reduction = r match
    case pb.ReductionEnum.REDUCTION_MEAN => Reduction.Mean
    case pb.ReductionEnum.REDUCTION_SUM  => Reduction.Sum
    case pb.ReductionEnum.REDUCTION_NONE => Reduction.None
    case pb.ReductionEnum.UNRECOGNIZED   =>
      throw new IllegalArgumentException("Unrecognized reduction")

  private def toProtoPadMode(m: PadMode): pb.PadModeEnum = m match
    case PadMode.Constant => pb.PadModeEnum.PAD_MODE_CONSTANT
    case PadMode.Reflect  => pb.PadModeEnum.PAD_MODE_REFLECT
    case PadMode.Edge     => pb.PadModeEnum.PAD_MODE_EDGE

  private def fromProtoPadMode(m: pb.PadModeEnum): PadMode = m match
    case pb.PadModeEnum.PAD_MODE_CONSTANT => PadMode.Constant
    case pb.PadModeEnum.PAD_MODE_REFLECT  => PadMode.Reflect
    case pb.PadModeEnum.PAD_MODE_EDGE     => PadMode.Edge
    case pb.PadModeEnum.UNRECOGNIZED      =>
      throw new IllegalArgumentException("Unrecognized pad mode")

  private def toProtoOpAttribute(attr: OpAttribute): pb.OpAttributeMsg = attr match
    case OpAttribute.IntAttr(v) =>
      pb.OpAttributeMsg.newBuilder().setIntAttr(v).build()
    case OpAttribute.FloatAttr(v) =>
      pb.OpAttributeMsg.newBuilder().setFloatAttr(v).build()
    case OpAttribute.StringAttr(v) =>
      pb.OpAttributeMsg.newBuilder().setStringAttr(v).build()
    case OpAttribute.IntsAttr(vs) =>
      val b = pb.IntsAttrMsg.newBuilder(); vs.foreach(b.addValues(_))
      pb.OpAttributeMsg.newBuilder().setIntsAttr(b).build()
    case OpAttribute.FloatsAttr(vs) =>
      val b = pb.FloatsAttrMsg.newBuilder(); vs.foreach(v => b.addValues(v))
      pb.OpAttributeMsg.newBuilder().setFloatsAttr(b).build()
    case OpAttribute.TensorAttr(td) =>
      pb.OpAttributeMsg.newBuilder().setTensorAttr(toProtoTensorData(td)).build()

  private def fromProtoOpAttribute(msg: pb.OpAttributeMsg): OpAttribute =
    msg.getAttrCase match
      case pb.OpAttributeMsg.AttrCase.INT_ATTR    => OpAttribute.IntAttr(msg.getIntAttr)
      case pb.OpAttributeMsg.AttrCase.FLOAT_ATTR  => OpAttribute.FloatAttr(msg.getFloatAttr)
      case pb.OpAttributeMsg.AttrCase.STRING_ATTR => OpAttribute.StringAttr(msg.getStringAttr)
      case pb.OpAttributeMsg.AttrCase.INTS_ATTR   =>
        val m = msg.getIntsAttr
        OpAttribute.IntsAttr((0 until m.getValuesCount).map(m.getValues).toVector)
      case pb.OpAttributeMsg.AttrCase.FLOATS_ATTR =>
        val m = msg.getFloatsAttr
        OpAttribute.FloatsAttr((0 until m.getValuesCount).map(i => m.getValues(i)).toVector)
      case pb.OpAttributeMsg.AttrCase.TENSOR_ATTR =>
        OpAttribute.TensorAttr(fromProtoTensorData(msg.getTensorAttr))
      case pb.OpAttributeMsg.AttrCase.ATTR_NOT_SET =>
        throw new IllegalArgumentException("OpAttribute not set")

  // ============================================================================
  // TensorExpr — toProto
  // ============================================================================

  def toProtoExpr(expr: TensorExpr): pb.TensorExprMsg =
    import TensorExpr._
    val b = pb.TensorExprMsg.newBuilder()
    expr match
      // Leaf nodes
      case Input(name, tt) =>
        b.setInput(
          pb.InputExprMsg.newBuilder().setName(name).setTensorType(toProtoTensorType(tt))
        )
      case Constant(name, tt, data) =>
        b.setConstant(
          pb.ConstantExprMsg
            .newBuilder()
            .setName(name)
            .setTensorType(toProtoTensorType(tt))
            .setData(toProtoTensorData(data))
        )
      case Parameter(name, tt, init) =>
        val pb2 = pb.ParameterExprMsg
          .newBuilder()
          .setName(name)
          .setTensorType(toProtoTensorType(tt))
        init.foreach(i => pb2.setInitializer(toProtoInitializer(i)))
        b.setParameter(pb2)

      // Linear algebra
      case MatMul(l, r, tA, tB) =>
        b.setMatMul(
          pb.MatMulExprMsg
            .newBuilder()
            .setLeft(toProtoExpr(l))
            .setRight(toProtoExpr(r))
            .setTransA(tA)
            .setTransB(tB)
        )
      case Gemm(a, bx, c, alpha, beta, tA, tB) =>
        val g = pb.GemmExprMsg
          .newBuilder()
          .setA(toProtoExpr(a))
          .setB(toProtoExpr(bx))
          .setAlpha(alpha)
          .setBeta(beta)
          .setTransA(tA)
          .setTransB(tB)
        c.foreach(cx => g.setC(toProtoExpr(cx)))
        b.setGemm(g)
      case Linear(input, weight, bias) =>
        val l = pb.LinearExprMsg
          .newBuilder()
          .setInput(toProtoExpr(input))
          .setWeight(toProtoExpr(weight))
        bias.foreach(bx => l.setBias(toProtoExpr(bx)))
        b.setLinear(l)

      // Convolution
      case Conv(input, weight, bias, strides, pads, dilations, group) =>
        val c = pb.ConvExprMsg
          .newBuilder()
          .setInput(toProtoExpr(input))
          .setWeight(toProtoExpr(weight))
          .setGroup(group)
        bias.foreach(bx => c.setBias(toProtoExpr(bx)))
        strides.foreach(c.addStrides(_)); pads.foreach(c.addPads(_))
        dilations.foreach(c.addDilations(_))
        b.setConv(c)
      case ConvTranspose(input, weight, bias, strides, pads, outputPads, dilations, group) =>
        val c = pb.ConvTransposeExprMsg
          .newBuilder()
          .setInput(toProtoExpr(input))
          .setWeight(toProtoExpr(weight))
          .setGroup(group)
        bias.foreach(bx => c.setBias(toProtoExpr(bx)))
        strides.foreach(c.addStrides(_)); pads.foreach(c.addPads(_))
        outputPads.foreach(c.addOutputPads(_)); dilations.foreach(c.addDilations(_))
        b.setConvTranspose(c)

      // Activations
      case Relu(i)         => b.setRelu(unaryMsg(i))
      case LeakyRelu(i, a) =>
        b.setLeakyRelu(
          pb.LeakyReluExprMsg
            .newBuilder()
            .setInput(toProtoExpr(i))
            .setAlpha(a)
        )
      case Sigmoid(i)       => b.setSigmoid(unaryMsg(i))
      case Tanh(i)          => b.setTanhAct(unaryMsg(i))
      case Softmax(i, ax)   => b.setSoftmax(axisMsg(i, ax))
      case LogSoftmax(i, a) => b.setLogSoftmax(axisMsg(i, a))
      case Gelu(i, approx)  =>
        b.setGelu(
          pb.GeluExprMsg
            .newBuilder()
            .setInput(toProtoExpr(i))
            .setApproximate(approx)
        )
      case Silu(i)   => b.setSilu(unaryMsg(i))
      case Elu(i, a) =>
        b.setElu(
          pb.AlphaTensorMsg
            .newBuilder()
            .setInput(toProtoExpr(i))
            .setAlpha(a)
        )
      case HardSwish(i) => b.setHardSwish(unaryMsg(i))

      // Element-wise arithmetic
      case Add(l, r)         => b.setAdd(binaryMsg(l, r))
      case Sub(l, r)         => b.setSub(binaryMsg(l, r))
      case Mul(l, r)         => b.setMul(binaryMsg(l, r))
      case Div(l, r)         => b.setDiv(binaryMsg(l, r))
      case Pow(bx, e)        => b.setPow(binaryMsg(bx, e))
      case Sqrt(i)           => b.setSqrt(unaryMsg(i))
      case Neg(i)            => b.setNeg(unaryMsg(i))
      case Abs(i)            => b.setAbs(unaryMsg(i))
      case Exp(i)            => b.setExp(unaryMsg(i))
      case Log(i)            => b.setLogExpr(unaryMsg(i))
      case Clip(i, min, max) =>
        val c = pb.ClipExprMsg.newBuilder().setInput(toProtoExpr(i))
        min.foreach(c.setMin(_)); max.foreach(c.setMax(_))
        b.setClip(c)

      // Pooling
      case MaxPool(i, ks, st, pa) =>
        val p = pb.PoolExprMsg.newBuilder().setInput(toProtoExpr(i))
        ks.foreach(p.addKernelSize(_)); st.foreach(p.addStrides(_)); pa.foreach(p.addPads(_))
        b.setMaxPool(p)
      case AvgPool(i, ks, st, pa, cip) =>
        val p = pb.AvgPoolExprMsg.newBuilder().setInput(toProtoExpr(i)).setCountIncludePad(cip)
        ks.foreach(p.addKernelSize(_)); st.foreach(p.addStrides(_)); pa.foreach(p.addPads(_))
        b.setAvgPool(p)
      case GlobalAvgPool(i)       => b.setGlobalAvgPool(unaryMsg(i))
      case AdaptiveAvgPool(i, os) =>
        val p = pb.AdaptiveAvgPoolExprMsg.newBuilder().setInput(toProtoExpr(i))
        os.foreach(p.addOutputSize(_))
        b.setAdaptiveAvgPool(p)

      // Normalization
      case BatchNorm(i, s, bi, m, v, eps, mom, tr) =>
        b.setBatchNorm(
          pb.BatchNormExprMsg
            .newBuilder()
            .setInput(toProtoExpr(i))
            .setScale(toProtoExpr(s))
            .setBias(toProtoExpr(bi))
            .setRunningMean(toProtoExpr(m))
            .setRunningVar(toProtoExpr(v))
            .setEpsilon(eps)
            .setMomentum(mom)
            .setTraining(tr)
        )
      case LayerNorm(i, ns, w, bi, eps) =>
        val l = pb.LayerNormExprMsg.newBuilder().setInput(toProtoExpr(i)).setEpsilon(eps)
        ns.foreach(l.addNormalizedShape(_))
        w.foreach(wx => l.setWeight(toProtoExpr(wx)))
        bi.foreach(bx => l.setBias(toProtoExpr(bx)))
        b.setLayerNorm(l)
      case GroupNorm(i, ng, w, bi, eps) =>
        val g = pb.GroupNormExprMsg
          .newBuilder()
          .setInput(toProtoExpr(i))
          .setNumGroups(ng)
          .setEpsilon(eps)
        w.foreach(wx => g.setWeight(toProtoExpr(wx)))
        bi.foreach(bx => g.setBias(toProtoExpr(bx)))
        b.setGroupNorm(g)
      case InstanceNorm(i, s, bi, eps) =>
        b.setInstanceNorm(
          pb.InstanceNormExprMsg
            .newBuilder()
            .setInput(toProtoExpr(i))
            .setScale(toProtoExpr(s))
            .setBias(toProtoExpr(bi))
            .setEpsilon(eps)
        )

      // Reduction
      case ReduceSum(i, ax, kd)  => b.setReduceSum(reduceMsg(i, ax, kd))
      case ReduceMean(i, ax, kd) => b.setReduceMean(reduceMsg(i, ax, kd))
      case ReduceMax(i, ax, kd)  => b.setReduceMax(reduceMsg(i, ax, kd))
      case ReduceMin(i, ax, kd)  => b.setReduceMin(reduceMsg(i, ax, kd))
      case ReduceProd(i, ax, kd) => b.setReduceProd(reduceMsg(i, ax, kd))

      // Shape manipulation
      case Reshape(i, sh) =>
        val r = pb.ReshapeExprMsg.newBuilder().setInput(toProtoExpr(i))
        sh.foreach(r.addShape(_))
        b.setReshape(r)
      case Transpose(i, perm) =>
        val t = pb.TransposeExprMsg.newBuilder().setInput(toProtoExpr(i))
        perm.foreach(t.addPerm(_))
        b.setTranspose(t)
      case Flatten(i, ax) =>
        b.setFlatten(pb.FlattenExprMsg.newBuilder().setInput(toProtoExpr(i)).setAxis(ax))
      case Squeeze(i, axes) =>
        val s = pb.AxesTensorMsg.newBuilder().setInput(toProtoExpr(i))
        axes.foreach(s.addAxes(_))
        b.setSqueeze(s)
      case Unsqueeze(i, axes) =>
        val s = pb.AxesTensorMsg.newBuilder().setInput(toProtoExpr(i))
        axes.foreach(s.addAxes(_))
        b.setUnsqueeze(s)
      case TensorConcat(inputs, ax) =>
        val c = pb.ConcatExprMsg.newBuilder().setAxis(ax)
        inputs.foreach(i => c.addInputs(toProtoExpr(i)))
        b.setConcat(c)
      case Split(i, ax, sizes) =>
        val s = pb.SplitExprMsg.newBuilder().setInput(toProtoExpr(i)).setAxis(ax)
        sizes.foreach(s.addSplitSizes(_))
        b.setSplit(s)
      case TensorSlice(i, starts, ends, axes, steps) =>
        val s = pb.SliceExprMsg.newBuilder().setInput(toProtoExpr(i))
        starts.foreach(s.addStarts(_)); ends.foreach(s.addEnds(_))
        axes.foreach(s.addAxes(_)); steps.foreach(s.addSteps(_))
        b.setSlice(s)
      case Gather(i, idx, ax) =>
        b.setGather(
          pb.GatherExprMsg
            .newBuilder()
            .setInput(toProtoExpr(i))
            .setIndices(toProtoExpr(idx))
            .setAxis(ax)
        )
      case Scatter(i, idx, u, ax) =>
        b.setScatter(
          pb.ScatterExprMsg
            .newBuilder()
            .setInput(toProtoExpr(i))
            .setIndices(toProtoExpr(idx))
            .setUpdates(toProtoExpr(u))
            .setAxis(ax)
        )
      case Pad(i, pads, mode, cv) =>
        val p = pb.PadExprMsg
          .newBuilder()
          .setInput(toProtoExpr(i))
          .setMode(toProtoPadMode(mode))
          .setConstantValue(cv)
        pads.foreach(p.addPads(_))
        b.setPad(p)
      case Expand(i, sh) =>
        val e = pb.ExpandExprMsg.newBuilder().setInput(toProtoExpr(i))
        sh.foreach(e.addShape(_))
        b.setExpand(e)

      // Recurrent
      case LSTM(i, wih, whh, bias, hs, nl, bidir, drop) =>
        val l = pb.LSTMExprMsg
          .newBuilder()
          .setInput(toProtoExpr(i))
          .setWeightIh(toProtoExpr(wih))
          .setWeightHh(toProtoExpr(whh))
          .setHiddenSize(hs)
          .setNumLayers(nl)
          .setBidirectional(bidir)
          .setDropout(drop)
        bias.foreach(bx => l.setBias(toProtoExpr(bx)))
        b.setLstm(l)
      case GRU(i, wih, whh, bias, hs, nl, bidir, drop) =>
        val g = pb.GRUExprMsg
          .newBuilder()
          .setInput(toProtoExpr(i))
          .setWeightIh(toProtoExpr(wih))
          .setWeightHh(toProtoExpr(whh))
          .setHiddenSize(hs)
          .setNumLayers(nl)
          .setBidirectional(bidir)
          .setDropout(drop)
        bias.foreach(bx => g.setBias(toProtoExpr(bx)))
        b.setGru(g)

      // Attention
      case ScaledDotProductAttention(q, k, v, mask, drop, causal) =>
        val a = pb.ScaledDotProductAttentionExprMsg
          .newBuilder()
          .setQuery(toProtoExpr(q))
          .setKey(toProtoExpr(k))
          .setValue(toProtoExpr(v))
          .setDropout(drop)
          .setIsCausal(causal)
        mask.foreach(mx => a.setMask(toProtoExpr(mx)))
        b.setScaledDotProductAttention(a)
      case MultiHeadAttention(q, k, v, wQ, wK, wV, wO, nh, mask, drop) =>
        val a = pb.MultiHeadAttentionExprMsg
          .newBuilder()
          .setQuery(toProtoExpr(q))
          .setKey(toProtoExpr(k))
          .setValue(toProtoExpr(v))
          .setWQ(toProtoExpr(wQ))
          .setWK(toProtoExpr(wK))
          .setWV(toProtoExpr(wV))
          .setWO(toProtoExpr(wO))
          .setNumHeads(nh)
          .setDropout(drop)
        mask.foreach(mx => a.setMask(toProtoExpr(mx)))
        b.setMultiHeadAttention(a)

      // Embedding
      case Embedding(idx, w, padIdx) =>
        val e = pb.EmbeddingExprMsg
          .newBuilder()
          .setIndices(toProtoExpr(idx))
          .setWeight(toProtoExpr(w))
        padIdx.foreach(e.setPaddingIdx(_))
        b.setEmbedding(e)

      // Regularization
      case Dropout(i, ratio, tr) =>
        b.setDropout(
          pb.DropoutExprMsg
            .newBuilder()
            .setInput(toProtoExpr(i))
            .setRatio(ratio)
            .setTraining(tr)
        )

      // Loss functions
      case CrossEntropyLoss(i, t, w, red) =>
        val l = pb.CrossEntropyLossExprMsg
          .newBuilder()
          .setInput(toProtoExpr(i))
          .setTarget(toProtoExpr(t))
          .setReduction(toProtoReduction(red))
        w.foreach(wx => l.setWeight(toProtoExpr(wx)))
        b.setCrossEntropyLoss(l)
      case MSELoss(i, t, red) =>
        b.setMseLoss(
          pb.LossExprMsg
            .newBuilder()
            .setInput(toProtoExpr(i))
            .setTarget(toProtoExpr(t))
            .setReduction(toProtoReduction(red))
        )
      case L1Loss(i, t, red) =>
        b.setL1Loss(
          pb.LossExprMsg
            .newBuilder()
            .setInput(toProtoExpr(i))
            .setTarget(toProtoExpr(t))
            .setReduction(toProtoReduction(red))
        )
      case BCELoss(i, t, w, red) =>
        val l = pb.CrossEntropyLossExprMsg
          .newBuilder()
          .setInput(toProtoExpr(i))
          .setTarget(toProtoExpr(t))
          .setReduction(toProtoReduction(red))
        w.foreach(wx => l.setWeight(toProtoExpr(wx)))
        b.setBceLoss(l)
      case BCEWithLogitsLoss(i, t, w, red) =>
        val l = pb.CrossEntropyLossExprMsg
          .newBuilder()
          .setInput(toProtoExpr(i))
          .setTarget(toProtoExpr(t))
          .setReduction(toProtoReduction(red))
        w.foreach(wx => l.setWeight(toProtoExpr(wx)))
        b.setBceWithLogitsLoss(l)

      // Comparison / logical
      case Equal(l, r)    => b.setEqual(binaryMsg(l, r))
      case Greater(l, r)  => b.setGreater(binaryMsg(l, r))
      case Less(l, r)     => b.setLess(binaryMsg(l, r))
      case Where(c, x, y) =>
        b.setWhereExpr(
          pb.WhereTensorMsg
            .newBuilder()
            .setCondition(toProtoExpr(c))
            .setX(toProtoExpr(x))
            .setY(toProtoExpr(y))
        )

      // Type casting
      case Cast(i, targetDType) =>
        b.setCastTensor(
          pb.TensorCastExprMsg
            .newBuilder()
            .setInput(toProtoExpr(i))
            .setTargetDtype(toProtoDType(targetDType))
        )

      // Opaque / custom op
      case OpaqueOp(name, inputs, attrs, outType) =>
        val o = pb.OpaqueOpExprMsg
          .newBuilder()
          .setName(name)
          .setOutputType(toProtoTensorType(outType))
        inputs.foreach(i => o.addInputs(toProtoExpr(i)))
        attrs.foreach { (k, v) =>
          o.addAttributes(
            pb.OpAttributeEntryMsg.newBuilder().setKey(k).setValue(toProtoOpAttribute(v))
          )
        }
        b.setOpaqueOp(o)

    b.build()

  // ============================================================================
  // TensorExpr — fromProto
  // ============================================================================

  def fromProtoExpr(msg: pb.TensorExprMsg): TensorExpr =
    import pb.TensorExprMsg.ExprCase._
    msg.getExprCase match
      // Leaf nodes
      case INPUT =>
        val m = msg.getInput
        TensorExpr.Input(m.getName, fromProtoTensorType(m.getTensorType))
      case CONSTANT =>
        val m = msg.getConstant
        TensorExpr.Constant(
          m.getName,
          fromProtoTensorType(m.getTensorType),
          fromProtoTensorData(m.getData)
        )
      case PARAMETER =>
        val m = msg.getParameter
        TensorExpr.Parameter(
          m.getName,
          fromProtoTensorType(m.getTensorType),
          if m.hasInitializer then Some(fromProtoInitializer(m.getInitializer)) else None
        )

      // Linear algebra
      case MAT_MUL =>
        val m = msg.getMatMul
        TensorExpr.MatMul(
          fromProtoExpr(m.getLeft),
          fromProtoExpr(m.getRight),
          m.getTransA,
          m.getTransB
        )
      case GEMM =>
        val m = msg.getGemm
        TensorExpr.Gemm(
          fromProtoExpr(m.getA),
          fromProtoExpr(m.getB),
          if m.hasC then Some(fromProtoExpr(m.getC)) else None,
          m.getAlpha,
          m.getBeta,
          m.getTransA,
          m.getTransB
        )
      case LINEAR =>
        val m = msg.getLinear
        TensorExpr.Linear(
          fromProtoExpr(m.getInput),
          fromProtoExpr(m.getWeight),
          if m.hasBias then Some(fromProtoExpr(m.getBias)) else None
        )

      // Convolution
      case CONV =>
        val m = msg.getConv
        TensorExpr.Conv(
          fromProtoExpr(m.getInput),
          fromProtoExpr(m.getWeight),
          if m.hasBias then Some(fromProtoExpr(m.getBias)) else None,
          ints(m.getStridesList),
          ints(m.getPadsList),
          ints(m.getDilationsList),
          m.getGroup
        )
      case CONV_TRANSPOSE =>
        val m = msg.getConvTranspose
        TensorExpr.ConvTranspose(
          fromProtoExpr(m.getInput),
          fromProtoExpr(m.getWeight),
          if m.hasBias then Some(fromProtoExpr(m.getBias)) else None,
          ints(m.getStridesList),
          ints(m.getPadsList),
          ints(m.getOutputPadsList),
          ints(m.getDilationsList),
          m.getGroup
        )

      // Activations
      case RELU       => TensorExpr.Relu(fromUnary(msg.getRelu))
      case LEAKY_RELU =>
        val m = msg.getLeakyRelu
        TensorExpr.LeakyRelu(fromProtoExpr(m.getInput), m.getAlpha)
      case SIGMOID  => TensorExpr.Sigmoid(fromUnary(msg.getSigmoid))
      case TANH_ACT => TensorExpr.Tanh(fromUnary(msg.getTanhAct))
      case SOFTMAX  =>
        val m = msg.getSoftmax
        TensorExpr.Softmax(fromProtoExpr(m.getInput), m.getAxis)
      case LOG_SOFTMAX =>
        val m = msg.getLogSoftmax
        TensorExpr.LogSoftmax(fromProtoExpr(m.getInput), m.getAxis)
      case GELU =>
        val m = msg.getGelu
        TensorExpr.Gelu(fromProtoExpr(m.getInput), m.getApproximate)
      case SILU => TensorExpr.Silu(fromUnary(msg.getSilu))
      case ELU  =>
        val m = msg.getElu
        TensorExpr.Elu(fromProtoExpr(m.getInput), m.getAlpha)
      case HARD_SWISH => TensorExpr.HardSwish(fromUnary(msg.getHardSwish))

      // Element-wise arithmetic
      case ADD      => fromBinary(msg.getAdd, TensorExpr.Add.apply)
      case SUB      => fromBinary(msg.getSub, TensorExpr.Sub.apply)
      case MUL      => fromBinary(msg.getMul, TensorExpr.Mul.apply)
      case DIV      => fromBinary(msg.getDiv, TensorExpr.Div.apply)
      case POW      => fromBinary(msg.getPow, TensorExpr.Pow.apply)
      case SQRT     => TensorExpr.Sqrt(fromUnary(msg.getSqrt))
      case NEG      => TensorExpr.Neg(fromUnary(msg.getNeg))
      case ABS      => TensorExpr.Abs(fromUnary(msg.getAbs))
      case EXP      => TensorExpr.Exp(fromUnary(msg.getExp))
      case LOG_EXPR => TensorExpr.Log(fromUnary(msg.getLogExpr))
      case CLIP     =>
        val m = msg.getClip
        TensorExpr.Clip(
          fromProtoExpr(m.getInput),
          if m.hasMin then Some(m.getMin) else None,
          if m.hasMax then Some(m.getMax) else None
        )

      // Pooling
      case MAX_POOL =>
        val m = msg.getMaxPool
        TensorExpr.MaxPool(
          fromProtoExpr(m.getInput),
          ints(m.getKernelSizeList),
          ints(m.getStridesList),
          ints(m.getPadsList)
        )
      case AVG_POOL =>
        val m = msg.getAvgPool
        TensorExpr.AvgPool(
          fromProtoExpr(m.getInput),
          ints(m.getKernelSizeList),
          ints(m.getStridesList),
          ints(m.getPadsList),
          m.getCountIncludePad
        )
      case GLOBAL_AVG_POOL   => TensorExpr.GlobalAvgPool(fromUnary(msg.getGlobalAvgPool))
      case ADAPTIVE_AVG_POOL =>
        val m = msg.getAdaptiveAvgPool
        TensorExpr.AdaptiveAvgPool(fromProtoExpr(m.getInput), ints(m.getOutputSizeList))

      // Normalization
      case BATCH_NORM =>
        val m = msg.getBatchNorm
        TensorExpr.BatchNorm(
          fromProtoExpr(m.getInput),
          fromProtoExpr(m.getScale),
          fromProtoExpr(m.getBias),
          fromProtoExpr(m.getRunningMean),
          fromProtoExpr(m.getRunningVar),
          m.getEpsilon,
          m.getMomentum,
          m.getTraining
        )
      case LAYER_NORM =>
        val m = msg.getLayerNorm
        TensorExpr.LayerNorm(
          fromProtoExpr(m.getInput),
          ints(m.getNormalizedShapeList),
          if m.hasWeight then Some(fromProtoExpr(m.getWeight)) else None,
          if m.hasBias then Some(fromProtoExpr(m.getBias)) else None,
          m.getEpsilon
        )
      case GROUP_NORM =>
        val m = msg.getGroupNorm
        TensorExpr.GroupNorm(
          fromProtoExpr(m.getInput),
          m.getNumGroups,
          if m.hasWeight then Some(fromProtoExpr(m.getWeight)) else None,
          if m.hasBias then Some(fromProtoExpr(m.getBias)) else None,
          m.getEpsilon
        )
      case INSTANCE_NORM =>
        val m = msg.getInstanceNorm
        TensorExpr.InstanceNorm(
          fromProtoExpr(m.getInput),
          fromProtoExpr(m.getScale),
          fromProtoExpr(m.getBias),
          m.getEpsilon
        )

      // Reduction
      case REDUCE_SUM  => fromReduce(msg.getReduceSum, TensorExpr.ReduceSum.apply)
      case REDUCE_MEAN => fromReduce(msg.getReduceMean, TensorExpr.ReduceMean.apply)
      case REDUCE_MAX  => fromReduce(msg.getReduceMax, TensorExpr.ReduceMax.apply)
      case REDUCE_MIN  => fromReduce(msg.getReduceMin, TensorExpr.ReduceMin.apply)
      case REDUCE_PROD => fromReduce(msg.getReduceProd, TensorExpr.ReduceProd.apply)

      // Shape manipulation
      case RESHAPE =>
        val m = msg.getReshape
        TensorExpr.Reshape(fromProtoExpr(m.getInput), ints(m.getShapeList))
      case TRANSPOSE =>
        val m = msg.getTranspose
        TensorExpr.Transpose(fromProtoExpr(m.getInput), ints(m.getPermList))
      case FLATTEN =>
        val m = msg.getFlatten
        TensorExpr.Flatten(fromProtoExpr(m.getInput), m.getAxis)
      case SQUEEZE =>
        val m = msg.getSqueeze
        TensorExpr.Squeeze(fromProtoExpr(m.getInput), ints(m.getAxesList))
      case UNSQUEEZE =>
        val m = msg.getUnsqueeze
        TensorExpr.Unsqueeze(fromProtoExpr(m.getInput), ints(m.getAxesList))
      case CONCAT =>
        val m = msg.getConcat
        TensorExpr.TensorConcat(
          (0 until m.getInputsCount).map(i => fromProtoExpr(m.getInputs(i))).toVector,
          m.getAxis
        )
      case SPLIT =>
        val m = msg.getSplit
        TensorExpr.Split(fromProtoExpr(m.getInput), m.getAxis, ints(m.getSplitSizesList))
      case SLICE =>
        val m = msg.getSlice
        TensorExpr.TensorSlice(
          fromProtoExpr(m.getInput),
          ints(m.getStartsList),
          ints(m.getEndsList),
          ints(m.getAxesList),
          ints(m.getStepsList)
        )
      case GATHER =>
        val m = msg.getGather
        TensorExpr.Gather(fromProtoExpr(m.getInput), fromProtoExpr(m.getIndices), m.getAxis)
      case SCATTER =>
        val m = msg.getScatter
        TensorExpr.Scatter(
          fromProtoExpr(m.getInput),
          fromProtoExpr(m.getIndices),
          fromProtoExpr(m.getUpdates),
          m.getAxis
        )
      case PAD =>
        val m = msg.getPad
        TensorExpr.Pad(
          fromProtoExpr(m.getInput),
          ints(m.getPadsList),
          fromProtoPadMode(m.getMode),
          m.getConstantValue
        )
      case EXPAND =>
        val m = msg.getExpand
        TensorExpr.Expand(fromProtoExpr(m.getInput), ints(m.getShapeList))

      // Recurrent
      case LSTM =>
        val m = msg.getLstm
        TensorExpr.LSTM(
          fromProtoExpr(m.getInput),
          fromProtoExpr(m.getWeightIh),
          fromProtoExpr(m.getWeightHh),
          if m.hasBias then Some(fromProtoExpr(m.getBias)) else None,
          m.getHiddenSize,
          m.getNumLayers,
          m.getBidirectional,
          m.getDropout
        )
      case GRU =>
        val m = msg.getGru
        TensorExpr.GRU(
          fromProtoExpr(m.getInput),
          fromProtoExpr(m.getWeightIh),
          fromProtoExpr(m.getWeightHh),
          if m.hasBias then Some(fromProtoExpr(m.getBias)) else None,
          m.getHiddenSize,
          m.getNumLayers,
          m.getBidirectional,
          m.getDropout
        )

      // Attention
      case SCALED_DOT_PRODUCT_ATTENTION =>
        val m = msg.getScaledDotProductAttention
        TensorExpr.ScaledDotProductAttention(
          fromProtoExpr(m.getQuery),
          fromProtoExpr(m.getKey),
          fromProtoExpr(m.getValue),
          if m.hasMask then Some(fromProtoExpr(m.getMask)) else None,
          m.getDropout,
          m.getIsCausal
        )
      case MULTI_HEAD_ATTENTION =>
        val m = msg.getMultiHeadAttention
        TensorExpr.MultiHeadAttention(
          fromProtoExpr(m.getQuery),
          fromProtoExpr(m.getKey),
          fromProtoExpr(m.getValue),
          fromProtoExpr(m.getWQ),
          fromProtoExpr(m.getWK),
          fromProtoExpr(m.getWV),
          fromProtoExpr(m.getWO),
          m.getNumHeads,
          if m.hasMask then Some(fromProtoExpr(m.getMask)) else None,
          m.getDropout
        )

      // Embedding
      case EMBEDDING =>
        val m = msg.getEmbedding
        TensorExpr.Embedding(
          fromProtoExpr(m.getIndices),
          fromProtoExpr(m.getWeight),
          if m.hasPaddingIdx then Some(m.getPaddingIdx) else None
        )

      // Regularization
      case DROPOUT =>
        val m = msg.getDropout
        TensorExpr.Dropout(fromProtoExpr(m.getInput), m.getRatio, m.getTraining)

      // Loss functions
      case CROSS_ENTROPY_LOSS =>
        val m = msg.getCrossEntropyLoss
        TensorExpr.CrossEntropyLoss(
          fromProtoExpr(m.getInput),
          fromProtoExpr(m.getTarget),
          if m.hasWeight then Some(fromProtoExpr(m.getWeight)) else None,
          fromProtoReduction(m.getReduction)
        )
      case MSE_LOSS =>
        val m = msg.getMseLoss
        TensorExpr.MSELoss(
          fromProtoExpr(m.getInput),
          fromProtoExpr(m.getTarget),
          fromProtoReduction(m.getReduction)
        )
      case L1_LOSS =>
        val m = msg.getL1Loss
        TensorExpr.L1Loss(
          fromProtoExpr(m.getInput),
          fromProtoExpr(m.getTarget),
          fromProtoReduction(m.getReduction)
        )
      case BCE_LOSS =>
        val m = msg.getBceLoss
        TensorExpr.BCELoss(
          fromProtoExpr(m.getInput),
          fromProtoExpr(m.getTarget),
          if m.hasWeight then Some(fromProtoExpr(m.getWeight)) else None,
          fromProtoReduction(m.getReduction)
        )
      case BCE_WITH_LOGITS_LOSS =>
        val m = msg.getBceWithLogitsLoss
        TensorExpr.BCEWithLogitsLoss(
          fromProtoExpr(m.getInput),
          fromProtoExpr(m.getTarget),
          if m.hasWeight then Some(fromProtoExpr(m.getWeight)) else None,
          fromProtoReduction(m.getReduction)
        )

      // Comparison / logical
      case EQUAL      => fromBinary(msg.getEqual, TensorExpr.Equal.apply)
      case GREATER    => fromBinary(msg.getGreater, TensorExpr.Greater.apply)
      case LESS       => fromBinary(msg.getLess, TensorExpr.Less.apply)
      case WHERE_EXPR =>
        val m = msg.getWhereExpr
        TensorExpr.Where(
          fromProtoExpr(m.getCondition),
          fromProtoExpr(m.getX),
          fromProtoExpr(m.getY)
        )

      // Type casting
      case CAST_TENSOR =>
        val m = msg.getCastTensor
        TensorExpr.Cast(fromProtoExpr(m.getInput), fromProtoDType(m.getTargetDtype))

      // Opaque / custom op
      case OPAQUE_OP =>
        val m = msg.getOpaqueOp
        val inputs = (0 until m.getInputsCount).map(i => fromProtoExpr(m.getInputs(i))).toVector
        val attrs = (0 until m.getAttributesCount).map { i =>
          val entry = m.getAttributes(i)
          entry.getKey -> fromProtoOpAttribute(entry.getValue)
        }.toMap
        TensorExpr.OpaqueOp(m.getName, inputs, attrs, fromProtoTensorType(m.getOutputType))

      case EXPR_NOT_SET =>
        throw new IllegalArgumentException("TensorExpr not set")

  // ============================================================================
  // Helpers
  // ============================================================================

  private def unaryMsg(input: TensorExpr): pb.UnaryTensorMsg.Builder =
    pb.UnaryTensorMsg.newBuilder().setInput(toProtoExpr(input))

  private def binaryMsg(left: TensorExpr, right: TensorExpr): pb.BinaryTensorMsg.Builder =
    pb.BinaryTensorMsg.newBuilder().setLeft(toProtoExpr(left)).setRight(toProtoExpr(right))

  private def axisMsg(input: TensorExpr, axis: Int): pb.AxisTensorMsg.Builder =
    pb.AxisTensorMsg.newBuilder().setInput(toProtoExpr(input)).setAxis(axis)

  private def reduceMsg(
      input: TensorExpr,
      axes: Vector[Int],
      keepDims: Boolean
  ): pb.ReduceExprMsg.Builder =
    val b = pb.ReduceExprMsg.newBuilder().setInput(toProtoExpr(input)).setKeepDims(keepDims)
    axes.foreach(b.addAxes(_))
    b

  private def fromUnary(msg: pb.UnaryTensorMsg): TensorExpr =
    fromProtoExpr(msg.getInput)

  private def fromBinary(
      msg: pb.BinaryTensorMsg,
      f: (TensorExpr, TensorExpr) => TensorExpr
  ): TensorExpr =
    f(fromProtoExpr(msg.getLeft), fromProtoExpr(msg.getRight))

  private def fromReduce(
      msg: pb.ReduceExprMsg,
      f: (TensorExpr, Vector[Int], Boolean) => TensorExpr
  ): TensorExpr =
    f(fromProtoExpr(msg.getInput), ints(msg.getAxesList), msg.getKeepDims)

  private def ints(javaList: java.util.List[java.lang.Integer]): Vector[Int] =
    val buf = Vector.newBuilder[Int]
    javaList.forEach(i => buf += i.intValue())
    buf.result()
