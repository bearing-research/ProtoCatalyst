package protocatalyst.ml.types

import java.util.IdentityHashMap

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph._

/** Shape inference for TensorExpr compute graphs.
  *
  * Computes the output TensorType for each operation based on its input types and
  * operation-specific shape rules. Supports static and dynamic dimensions, broadcasting, and
  * DAG-aware memoization.
  */
object ShapeInference:

  class ShapeInferenceException(message: String) extends IllegalArgumentException(message)

  private def error(op: String, msg: String): Nothing =
    throw ShapeInferenceException(s"Shape inference failed for $op: $msg")

  // ============================================================================
  // Public API
  // ============================================================================

  /** Infer the output TensorType of a single expression. Leaf nodes (Input, Constant, Parameter)
    * use their embedded types. Other nodes recursively resolve input types. The typeEnv provides
    * overrides for named inputs/parameters.
    */
  def inferType(expr: TensorExpr, typeEnv: Map[String, TensorType] = Map.empty): TensorType =
    val memo = new IdentityHashMap[TensorExpr, TensorType]()
    def go(e: TensorExpr): TensorType =
      val cached = memo.get(e)
      if cached != null then cached
      else
        val result = inferImpl(e, typeEnv, go)
        memo.put(e, result)
        result
    go(expr)

  /** Walk a ComputeGraph in topological order (nodes are already topo-sorted) and infer all
    * outputTypes. Returns a new graph with inferred types.
    */
  def inferGraphTypes(graph: ComputeGraph): ComputeGraph =
    val inputEnv: Map[String, TensorType] =
      graph.inputs.map(io => io.name -> io.tensorType).toMap

    val memo = new IdentityHashMap[TensorExpr, TensorType]()
    var env = inputEnv

    def go(e: TensorExpr): TensorType =
      val cached = memo.get(e)
      if cached != null then cached
      else
        val result = inferImpl(e, env, go)
        memo.put(e, result)
        result

    val newNodes = graph.nodes.map { node =>
      val inferredType = go(node.expr)
      env = env + (node.name -> inferredType)
      node.copy(outputType = inferredType)
    }

    val newOutputs = graph.outputs.map { io =>
      env.get(io.name) match
        case Some(tt) => io.copy(tensorType = tt)
        case None     => io
    }

    graph.copy(nodes = newNodes, outputs = newOutputs)

  /** Validate that existing outputTypes in a graph match inferred types. Returns a list of
    * (nodeName, declared, inferred) mismatches.
    */
  def validateGraphTypes(graph: ComputeGraph): Vector[(String, TensorType, TensorType)] =
    val inferred = inferGraphTypes(graph)
    graph.nodes
      .zip(inferred.nodes)
      .collect {
        case (orig, inf) if orig.outputType != inf.outputType =>
          (orig.name, orig.outputType, inf.outputType)
      }
      .toVector

  // ============================================================================
  // Core inference: exhaustive match over TensorExpr
  // ============================================================================

  private def inferImpl(
      expr: TensorExpr,
      env: Map[String, TensorType],
      resolve: TensorExpr => TensorType
  ): TensorType = expr match

    // ── Leaf nodes ──────────────────────────────────────────────────────────
    case TensorExpr.Input(name, tensorType)        => env.getOrElse(name, tensorType)
    case TensorExpr.Constant(_, tensorType, _)     => tensorType
    case TensorExpr.Parameter(name, tensorType, _) => env.getOrElse(name, tensorType)

    // ── Shape-preserving unary ops ──────────────────────────────────────────
    case TensorExpr.Relu(input)          => resolve(input)
    case TensorExpr.LeakyRelu(input, _)  => resolve(input)
    case TensorExpr.Sigmoid(input)       => resolve(input)
    case TensorExpr.Tanh(input)          => resolve(input)
    case TensorExpr.Softmax(input, _)    => resolve(input)
    case TensorExpr.LogSoftmax(input, _) => resolve(input)
    case TensorExpr.Gelu(input, _)       => resolve(input)
    case TensorExpr.Silu(input)          => resolve(input)
    case TensorExpr.Elu(input, _)        => resolve(input)
    case TensorExpr.HardSwish(input)     => resolve(input)
    case TensorExpr.Sqrt(input)          => resolve(input)
    case TensorExpr.Neg(input)           => resolve(input)
    case TensorExpr.Abs(input)           => resolve(input)
    case TensorExpr.Exp(input)           => resolve(input)
    case TensorExpr.Log(input)           => resolve(input)
    case TensorExpr.Clip(input, _, _)    => resolve(input)
    case TensorExpr.Dropout(input, _, _) => resolve(input)

    // ── Normalization (shape-preserving) ────────────────────────────────────
    case TensorExpr.BatchNorm(input, _, _, _, _, _, _, _) => resolve(input)
    case TensorExpr.LayerNorm(input, _, _, _, _)          => resolve(input)
    case TensorExpr.GroupNorm(input, _, _, _, _)          => resolve(input)
    case TensorExpr.InstanceNorm(input, _, _, _)          => resolve(input)

    // ── Binary broadcast ops ────────────────────────────────────────────────
    case TensorExpr.Add(left, right) => inferBinaryBroadcast(resolve(left), resolve(right))
    case TensorExpr.Sub(left, right) => inferBinaryBroadcast(resolve(left), resolve(right))
    case TensorExpr.Mul(left, right) => inferBinaryBroadcast(resolve(left), resolve(right))
    case TensorExpr.Div(left, right) => inferBinaryBroadcast(resolve(left), resolve(right))
    case TensorExpr.Pow(base, exp)   => inferBinaryBroadcast(resolve(base), resolve(exp))

    // ── Comparison (broadcast shape, Bool output) ───────────────────────────
    case TensorExpr.Equal(left, right) =>
      val shape = broadcastShapes(resolve(left).shape, resolve(right).shape)
      TensorType(TensorDType.Bool, shape)
    case TensorExpr.Greater(left, right) =>
      val shape = broadcastShapes(resolve(left).shape, resolve(right).shape)
      TensorType(TensorDType.Bool, shape)
    case TensorExpr.Less(left, right) =>
      val shape = broadcastShapes(resolve(left).shape, resolve(right).shape)
      TensorType(TensorDType.Bool, shape)

    // ── Where ───────────────────────────────────────────────────────────────
    case TensorExpr.Where(condition, x, y) =>
      val ct = resolve(condition)
      val xt = resolve(x)
      val yt = resolve(y)
      val xyShape = broadcastShapes(xt.shape, yt.shape)
      TensorType(xt.dtype, broadcastShapes(ct.shape, xyShape))

    // ── MatMul ──────────────────────────────────────────────────────────────
    case TensorExpr.MatMul(left, right, transA, transB) =>
      val lt = resolve(left)
      val rt = resolve(right)
      val lShape = maybeTransposeLast2(lt.shape, transA)
      val rShape = maybeTransposeLast2(rt.shape, transB)
      inferMatMul(lt.dtype, lShape, rShape)

    // ── Gemm ────────────────────────────────────────────────────────────────
    case TensorExpr.Gemm(a, b, _, _, _, transA, transB) =>
      val at = resolve(a)
      val bt = resolve(b)
      val aShape = maybeTransposeLast2(at.shape, transA)
      val bShape = maybeTransposeLast2(bt.shape, transB)
      // 2D only: (M, K) x (K, N) -> (M, N)
      TensorType(at.dtype, Vector(aShape(0), bShape(1)))

    // ── Linear ──────────────────────────────────────────────────────────────
    case TensorExpr.Linear(input, weight, _) =>
      val it = resolve(input)
      val wt = resolve(weight)
      // weight is (out_features, in_features)
      TensorType(it.dtype, it.shape.dropRight(1) :+ wt.shape(0))

    // ── Conv ────────────────────────────────────────────────────────────────
    case TensorExpr.Conv(input, weight, _, strides, pads, dilations, _) =>
      val it = resolve(input)
      val wt = resolve(weight)
      val n = it.shape(0)
      val cOut = wt.shape(0)
      val spatialRank = strides.size
      val spatialDims = (0 until spatialRank).map { i =>
        convOutputDim(
          it.shape(i + 2),
          dimValue(wt.shape(i + 2)).getOrElse(
            error("Conv", s"weight spatial dim $i must be static")
          ),
          strides(i),
          pads(i),
          pads(i + spatialRank),
          dilations(i)
        )
      }.toVector
      TensorType(it.dtype, Vector(n, cOut) ++ spatialDims)

    // ── ConvTranspose ───────────────────────────────────────────────────────
    case TensorExpr.ConvTranspose(input, weight, _, strides, pads, outputPads, dilations, _) =>
      val it = resolve(input)
      val wt = resolve(weight)
      val n = it.shape(0)
      val cOut = wt.shape(1) // ConvTranspose weight is (C_in, C_out, kH, kW)
      val spatialRank = strides.size
      val spatialDims = (0 until spatialRank).map { i =>
        convTransposeOutputDim(
          it.shape(i + 2),
          dimValue(wt.shape(i + 2)).getOrElse(
            error("ConvTranspose", s"weight spatial dim $i must be static")
          ),
          strides(i),
          pads(i),
          pads(i + spatialRank),
          dilations(i),
          outputPads(i)
        )
      }.toVector
      TensorType(it.dtype, Vector(n, cOut) ++ spatialDims)

    // ── Pooling ─────────────────────────────────────────────────────────────
    case TensorExpr.MaxPool(input, kernelSize, strides, pads) =>
      inferPooling(resolve(input), kernelSize, strides, pads)

    case TensorExpr.AvgPool(input, kernelSize, strides, pads, _) =>
      inferPooling(resolve(input), kernelSize, strides, pads)

    case TensorExpr.GlobalAvgPool(input) =>
      val it = resolve(input)
      val nc = it.shape.take(2)
      val ones = it.shape.drop(2).map(_ => Dim.Static(1))
      TensorType(it.dtype, nc ++ ones)

    case TensorExpr.AdaptiveAvgPool(input, outputSize) =>
      val it = resolve(input)
      val nc = it.shape.take(2)
      TensorType(it.dtype, nc ++ outputSize.map(Dim.Static(_)))

    // ── Reduction ───────────────────────────────────────────────────────────
    case TensorExpr.ReduceSum(input, axes, keepDims)  => inferReduce(resolve(input), axes, keepDims)
    case TensorExpr.ReduceMean(input, axes, keepDims) => inferReduce(resolve(input), axes, keepDims)
    case TensorExpr.ReduceMax(input, axes, keepDims)  => inferReduce(resolve(input), axes, keepDims)
    case TensorExpr.ReduceMin(input, axes, keepDims)  => inferReduce(resolve(input), axes, keepDims)
    case TensorExpr.ReduceProd(input, axes, keepDims) => inferReduce(resolve(input), axes, keepDims)

    // ── Reshape ─────────────────────────────────────────────────────────────
    case TensorExpr.Reshape(input, shape) =>
      val it = resolve(input)
      TensorType(it.dtype, resolveReshape(it.shape, shape))

    // ── Transpose ───────────────────────────────────────────────────────────
    case TensorExpr.Transpose(input, perm) =>
      val it = resolve(input)
      val newPerm = if perm.isEmpty then it.shape.indices.reverse.toVector else perm
      TensorType(it.dtype, newPerm.map(it.shape(_)))

    // ── Flatten ─────────────────────────────────────────────────────────────
    case TensorExpr.Flatten(input, axis) =>
      val it = resolve(input)
      val normAxis = if axis < 0 then it.shape.size + axis else axis
      val leftDim = productDim(it.shape.take(normAxis))
      val rightDim = productDim(it.shape.drop(normAxis))
      TensorType(it.dtype, Vector(leftDim, rightDim))

    // ── Squeeze ─────────────────────────────────────────────────────────────
    case TensorExpr.Squeeze(input, axes) =>
      val it = resolve(input)
      val normAxes = axes.map(a => if a < 0 then it.shape.size + a else a).toSet
      val newShape = it.shape.zipWithIndex.collect {
        case (d, i) if !normAxes.contains(i) => d
      }
      TensorType(it.dtype, newShape)

    // ── Unsqueeze ───────────────────────────────────────────────────────────
    case TensorExpr.Unsqueeze(input, axes) =>
      val it = resolve(input)
      val outputRank = it.shape.size + axes.size
      val normAxes = axes.map(a => if a < 0 then outputRank + a else a).toSet
      val newShape = Vector.newBuilder[Dim]
      var inputIdx = 0
      for i <- 0 until outputRank do
        if normAxes.contains(i) then newShape += Dim.Static(1)
        else
          newShape += it.shape(inputIdx)
          inputIdx += 1
      TensorType(it.dtype, newShape.result())

    // ── TensorConcat ────────────────────────────────────────────────────────
    case TensorExpr.TensorConcat(inputs, axis) =>
      val types = inputs.map(resolve)
      val firstType = types.head
      val normAxis = if axis < 0 then firstType.shape.size + axis else axis
      val axisDim = types.map(_.shape(normAxis)).reduce(addDims)
      val newShape = firstType.shape.updated(normAxis, axisDim)
      TensorType(firstType.dtype, newShape)

    // ── Split ───────────────────────────────────────────────────────────────
    case TensorExpr.Split(input, axis, splitSizes) =>
      val it = resolve(input)
      val normAxis = if axis < 0 then it.shape.size + axis else axis
      val axisDim =
        if splitSizes.nonEmpty then Dim.Static(splitSizes.head)
        else it.shape(normAxis)
      TensorType(it.dtype, it.shape.updated(normAxis, axisDim))

    // ── TensorSlice ─────────────────────────────────────────────────────────
    case TensorExpr.TensorSlice(input, starts, ends, axes, steps) =>
      val it = resolve(input)
      var newShape = it.shape
      for i <- axes.indices do
        val axis = if axes(i) < 0 then it.shape.size + axes(i) else axes(i)
        val start = starts(i)
        val end = ends(i)
        val step = steps(i)
        val sliceDim = it.shape(axis) match
          case Dim.Static(size) =>
            val clampedStart = math.max(0, math.min(start, size))
            val clampedEnd = math.max(0, math.min(end, size))
            val extent = clampedEnd - clampedStart
            Dim.Static(math.ceil(extent.toDouble / step.abs).toInt)
          case d: Dim.Dynamic => d
        newShape = newShape.updated(axis, sliceDim)
      TensorType(it.dtype, newShape)

    // ── Gather ──────────────────────────────────────────────────────────────
    case TensorExpr.Gather(input, indices, axis) =>
      val it = resolve(input)
      val idxType = resolve(indices)
      val normAxis = if axis < 0 then it.shape.size + axis else axis
      // Replace the axis dim with the indices shape
      val before = it.shape.take(normAxis)
      val after = it.shape.drop(normAxis + 1)
      TensorType(it.dtype, before ++ idxType.shape ++ after)

    // ── Scatter ─────────────────────────────────────────────────────────────
    case TensorExpr.Scatter(input, _, _, _) =>
      resolve(input) // output shape = input shape

    // ── Pad ─────────────────────────────────────────────────────────────────
    case TensorExpr.Pad(input, pads, _, _) =>
      val it = resolve(input)
      val rank = it.shape.size
      val newShape = it.shape.zipWithIndex.map { (d, i) =>
        val padBefore = pads(i)
        val padAfter = pads(i + rank)
        d match
          case Dim.Static(size) => Dim.Static(size + padBefore + padAfter)
          case dyn: Dim.Dynamic => dyn
      }
      TensorType(it.dtype, newShape)

    // ── Expand ──────────────────────────────────────────────────────────────
    case TensorExpr.Expand(input, shape) =>
      val it = resolve(input)
      val targetShape = shape.map(s => if s == -1 then Dim.Dynamic(None) else Dim.Static(s))
      TensorType(it.dtype, broadcastShapes(it.shape, targetShape))

    // ── Cast ────────────────────────────────────────────────────────────────
    case TensorExpr.Cast(input, targetDType) =>
      val it = resolve(input)
      TensorType(targetDType, it.shape)

    // ── LSTM ────────────────────────────────────────────────────────────────
    case TensorExpr.LSTM(input, _, _, _, hiddenSize, _, bidirectional, _) =>
      val it = resolve(input)
      val numDir = if bidirectional then 2 else 1
      // Input: (seq_len, batch, input_size) → Output: (seq_len, batch, hiddenSize * numDir)
      TensorType(it.dtype, Vector(it.shape(0), it.shape(1), Dim.Static(hiddenSize * numDir)))

    // ── GRU ─────────────────────────────────────────────────────────────────
    case TensorExpr.GRU(input, _, _, _, hiddenSize, _, bidirectional, _) =>
      val it = resolve(input)
      val numDir = if bidirectional then 2 else 1
      TensorType(it.dtype, Vector(it.shape(0), it.shape(1), Dim.Static(hiddenSize * numDir)))

    // ── ScaledDotProductAttention ────────────────────────────────────────────
    case TensorExpr.ScaledDotProductAttention(query, _, value, _, _, _) =>
      val qt = resolve(query)
      val vt = resolve(value)
      // Output: (..., seq_len_q, head_dim) — batch dims from q, last dim from v
      TensorType(qt.dtype, qt.shape.dropRight(1) :+ vt.shape.last)

    // ── MultiHeadAttention ──────────────────────────────────────────────────
    case TensorExpr.MultiHeadAttention(query, _, _, _, _, _, wO, _, _, _) =>
      val qt = resolve(query)
      val wOt = resolve(wO)
      // Output: (..., seq_len, embed_dim) — batch+seq from q, output dim from wO
      TensorType(qt.dtype, qt.shape.dropRight(1) :+ wOt.shape(0))

    // ── Embedding ───────────────────────────────────────────────────────────
    case TensorExpr.Embedding(indices, weight, _) =>
      val idxType = resolve(indices)
      val wt = resolve(weight)
      // Output: indices_shape + (embedding_dim,)
      TensorType(wt.dtype, idxType.shape :+ wt.shape(1))

    // ── Loss functions ──────────────────────────────────────────────────────
    case TensorExpr.CrossEntropyLoss(input, _, _, reduction) =>
      val it = resolve(input)
      reduction match
        case Reduction.None => TensorType(it.dtype, Vector(it.shape.head))
        case _              => TensorType(it.dtype, Vector.empty)

    case TensorExpr.MSELoss(input, _, reduction) =>
      inferLossShape(resolve(input), reduction)

    case TensorExpr.L1Loss(input, _, reduction) =>
      inferLossShape(resolve(input), reduction)

    case TensorExpr.BCELoss(input, _, _, reduction) =>
      inferLossShape(resolve(input), reduction)

    case TensorExpr.BCEWithLogitsLoss(input, _, _, reduction) =>
      inferLossShape(resolve(input), reduction)

    // ── OpaqueOp ────────────────────────────────────────────────────────────
    case TensorExpr.OpaqueOp(_, _, _, outputType) => outputType

  // ============================================================================
  // Shape computation helpers
  // ============================================================================

  private def inferBinaryBroadcast(lt: TensorType, rt: TensorType): TensorType =
    TensorType(lt.dtype, broadcastShapes(lt.shape, rt.shape))

  private def inferLossShape(it: TensorType, reduction: Reduction): TensorType =
    reduction match
      case Reduction.None => it
      case _              => TensorType(it.dtype, Vector.empty)

  /** Broadcast two shapes following numpy/ONNX rules. Right-aligns and pads with Static(1). */
  private[types] def broadcastShapes(a: Vector[Dim], b: Vector[Dim]): Vector[Dim] =
    val maxRank = math.max(a.size, b.size)
    val aPadded = Vector.fill(maxRank - a.size)(Dim.Static(1)) ++ a
    val bPadded = Vector.fill(maxRank - b.size)(Dim.Static(1)) ++ b
    aPadded.zip(bPadded).map { (da, db) => broadcastDim(da, db) }

  /** Broadcast a single dimension pair. */
  private[types] def broadcastDim(a: Dim, b: Dim): Dim = (a, b) match
    case (Dim.Static(sa), Dim.Static(sb)) if sa == sb => a
    case (Dim.Static(1), Dim.Static(_))               => b
    case (Dim.Static(_), Dim.Static(1))               => a
    case (Dim.Static(sa), Dim.Static(sb))             =>
      error("broadcast", s"incompatible dimensions: $sa vs $sb")
    case (Dim.Dynamic(_), Dim.Static(1))                => a
    case (Dim.Static(1), Dim.Dynamic(_))                => b
    case (Dim.Dynamic(na), Dim.Dynamic(nb)) if na == nb => a
    case (Dim.Dynamic(_), Dim.Static(_))                => b // assume graph is correct
    case (Dim.Static(_), Dim.Dynamic(_))                => a // assume graph is correct
    case (Dim.Dynamic(_), Dim.Dynamic(_))               => Dim.Dynamic(None)

  /** Compute conv output dimension. */
  private def convOutputDim(
      inputDim: Dim,
      kernelSize: Int,
      stride: Int,
      padBefore: Int,
      padAfter: Int,
      dilation: Int
  ): Dim = inputDim match
    case Dim.Static(size) =>
      Dim.Static((size + padBefore + padAfter - dilation * (kernelSize - 1) - 1) / stride + 1)
    case d: Dim.Dynamic => d

  /** Compute conv transpose output dimension. */
  private def convTransposeOutputDim(
      inputDim: Dim,
      kernelSize: Int,
      stride: Int,
      padBefore: Int,
      padAfter: Int,
      dilation: Int,
      outputPad: Int
  ): Dim = inputDim match
    case Dim.Static(size) =>
      Dim.Static(
        (size - 1) * stride - padBefore - padAfter + dilation * (kernelSize - 1) + outputPad + 1
      )
    case d: Dim.Dynamic => d

  /** Infer pooling output type (shared by MaxPool, AvgPool). */
  private def inferPooling(
      it: TensorType,
      kernelSize: Vector[Int],
      strides: Vector[Int],
      pads: Vector[Int]
  ): TensorType =
    val spatialRank = kernelSize.size
    val spatialDims = (0 until spatialRank).map { i =>
      convOutputDim(it.shape(i + 2), kernelSize(i), strides(i), pads(i), pads(i + spatialRank), 1)
    }.toVector
    TensorType(it.dtype, it.shape.take(2) ++ spatialDims)

  /** Infer reduction output type. */
  private def inferReduce(
      it: TensorType,
      axes: Vector[Int],
      keepDims: Boolean
  ): TensorType =
    val normAxes = axes.map(a => if a < 0 then it.shape.size + a else a).toSet
    if keepDims then
      val newShape = it.shape.zipWithIndex.map { (d, i) =>
        if normAxes.contains(i) then Dim.Static(1) else d
      }
      TensorType(it.dtype, newShape)
    else
      val newShape = it.shape.zipWithIndex.collect {
        case (d, i) if !normAxes.contains(i) => d
      }
      TensorType(it.dtype, newShape)

  /** Resolve reshape target, handling -1 dimension. */
  private[types] def resolveReshape(
      inputShape: Vector[Dim],
      targetShape: Vector[Int]
  ): Vector[Dim] =
    val negCount = targetShape.count(_ == -1)
    if negCount > 1 then error("Reshape", "at most one -1 dimension allowed")

    if negCount == 0 then targetShape.map(Dim.Static(_))
    else
      // Try to resolve -1 from total element count
      val totalElements = productDimValue(inputShape)
      val knownProduct = targetShape.filter(_ != -1).map(_.toLong).product
      totalElements match
        case Some(total) =>
          targetShape.map { d =>
            if d == -1 then Dim.Static((total / knownProduct).toInt) else Dim.Static(d)
          }.toVector
        case None =>
          targetShape.map { d =>
            if d == -1 then Dim.Dynamic(None) else Dim.Static(d)
          }.toVector

  /** Infer MatMul output shape. Handles 1D, 2D, and batched cases. */
  private def inferMatMul(
      dtype: TensorDType,
      lShape: Vector[Dim],
      rShape: Vector[Dim]
  ): TensorType =
    (lShape.size, rShape.size) match
      case (1, 1) =>
        // dot product → scalar
        TensorType(dtype, Vector.empty)
      case (1, 2) =>
        // (K,) x (K, N) → (N,)
        TensorType(dtype, Vector(rShape.last))
      case (2, 1) =>
        // (M, K) x (K,) → (M,)
        TensorType(dtype, Vector(lShape.head))
      case (l, r) if l >= 2 && r >= 2 =>
        // Batched: broadcast batch dims, matmul on last 2
        val lBatch = lShape.dropRight(2)
        val rBatch = rShape.dropRight(2)
        val batchDims = broadcastShapes(lBatch, rBatch)
        val m = lShape(lShape.size - 2)
        val n = rShape.last
        TensorType(dtype, batchDims ++ Vector(m, n))
      case _ =>
        error("MatMul", s"unsupported rank combination: ${lShape.size} x ${rShape.size}")

  /** Conditionally swap the last two dimensions. */
  private def maybeTransposeLast2(shape: Vector[Dim], transpose: Boolean): Vector[Dim] =
    if !transpose || shape.size < 2 then shape
    else
      val n = shape.size
      shape.updated(n - 2, shape(n - 1)).updated(n - 1, shape(n - 2))

  /** Extract static value from a dim, or None for dynamic. */
  private def dimValue(dim: Dim): Option[Int] = dim match
    case Dim.Static(size) => Some(size)
    case _                => None

  /** Compute the product of a sequence of dims. Returns Static if all static, else Dynamic. */
  private def productDim(dims: Vector[Dim]): Dim =
    if dims.isEmpty then Dim.Static(1)
    else if dims.forall { case Dim.Static(_) => true; case _ => false } then
      Dim.Static(dims.map { case Dim.Static(s) => s; case _ => 1 }.product)
    else Dim.Dynamic(None)

  /** Try to compute total element count from shape. Returns None if any dim is dynamic. */
  private def productDimValue(shape: Vector[Dim]): Option[Long] =
    if shape.forall { case Dim.Static(_) => true; case _ => false } then
      Some(shape.map { case Dim.Static(s) => s.toLong; case _ => 1L }.product)
    else None

  /** Add two dims (for concat axis). */
  private def addDims(a: Dim, b: Dim): Dim = (a, b) match
    case (Dim.Static(sa), Dim.Static(sb)) => Dim.Static(sa + sb)
    case _                                => Dim.Dynamic(None)
