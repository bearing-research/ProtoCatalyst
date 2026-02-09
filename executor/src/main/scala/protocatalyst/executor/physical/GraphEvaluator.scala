package protocatalyst.executor.physical

import java.nio.{ByteBuffer, ByteOrder}
import java.util.IdentityHashMap

import protocatalyst.executor.exec.ExecutionException
import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph._
import protocatalyst.ml.types._
import protocatalyst.plan.{OptimizerType, TrainConfig}

/** Forward-pass tensor evaluator for ComputeGraph on float arrays.
  *
  * Supports a core subset of TensorExpr ops sufficient for MLPs, logistic regression, and simple
  * neural networks. Used by both PredictOp (inference) and FitOp (training).
  */
object GraphEvaluator:

  /** A tensor value: flat float array + shape. Row-major layout. */
  case class Tensor(data: Array[Float], shape: Vector[Int]):
    def numElements: Int = if shape.isEmpty then 1 else shape.product
    def isScalar: Boolean = shape.isEmpty || shape == Vector(1)

  /** Evaluate a ComputeGraph forward pass.
    *
    * @param graph
    *   the computation graph
    * @param inputs
    *   named input tensors
    * @param params
    *   named parameter tensors (mutable — updated during training)
    * @return
    *   named output tensors
    */
  def forward(
      graph: ComputeGraph,
      inputs: Map[String, Tensor],
      params: Map[String, Tensor] = Map.empty
  ): Map[String, Tensor] =
    // Evaluate each node in topological order
    val env = new IdentityHashMap[TensorExpr, Tensor]()

    for node <- graph.nodes do
      val result = evalExpr(node.expr, inputs, params, env)
      env.put(node.expr, result)

    // Collect outputs
    graph.outputs.map { io =>
      val node = graph.nodeMap(io.name)
      val tensor = env.get(node.expr)
      if tensor == null then throw ExecutionException(s"Output '${io.name}' not evaluated")
      io.name -> tensor
    }.toMap

  /** Run one training epoch: forward → loss → backward → parameter update.
    *
    * @return
    *   the loss value for this epoch
    */
  def trainEpoch(
      graph: ComputeGraph,
      gradGraph: ComputeGraph,
      inputs: Map[String, Tensor],
      params: scala.collection.mutable.Map[String, Tensor],
      config: TrainConfig,
      adamState: Option[AdamState] = None
  ): Double =
    // Forward + backward pass through the gradient graph
    val env = new IdentityHashMap[TensorExpr, Tensor]()
    val paramTensors = params.toMap

    for node <- gradGraph.nodes do
      val result = evalExpr(node.expr, inputs, paramTensors, env)
      env.put(node.expr, result)

    // Extract loss value
    val lossNode = gradGraph.nodeMap.getOrElse(
      config.lossNode,
      throw ExecutionException(s"Loss node '${config.lossNode}' not found")
    )
    val lossTensor = env.get(lossNode.expr)
    if lossTensor == null then
      throw ExecutionException(s"Loss node '${config.lossNode}' not evaluated")
    val lossValue = lossTensor.data(0).toDouble

    // Extract gradients and update parameters
    for (paramName, paramTensor) <- params do
      val gradName = s"grad/$paramName"
      gradGraph.nodeMap.get(gradName).foreach { gradNode =>
        val gradTensor = env.get(gradNode.expr)
        if gradTensor != null then
          config.optimizer match
            case OptimizerType.SGD =>
              sgdUpdate(paramTensor.data, gradTensor.data, config.learningRate)
            case OptimizerType.Adam =>
              adamState match
                case Some(state) =>
                  adamUpdate(
                    paramName,
                    paramTensor.data,
                    gradTensor.data,
                    config.learningRate,
                    state
                  )
                case None =>
                  sgdUpdate(paramTensor.data, gradTensor.data, config.learningRate)
      }

    lossValue

  /** Adam optimizer state. */
  class AdamState(
      val beta1: Double = 0.9,
      val beta2: Double = 0.999,
      val epsilon: Double = 1e-8
  ):
    var step: Int = 0
    val m: scala.collection.mutable.Map[String, Array[Float]] = scala.collection.mutable.Map.empty
    val v: scala.collection.mutable.Map[String, Array[Float]] = scala.collection.mutable.Map.empty

  /** SGD parameter update: param -= lr * grad */
  private def sgdUpdate(param: Array[Float], grad: Array[Float], lr: Double): Unit =
    val lrf = lr.toFloat
    var i = 0
    while i < param.length && i < grad.length do
      param(i) -= lrf * grad(i)
      i += 1

  /** Adam parameter update. */
  private def adamUpdate(
      name: String,
      param: Array[Float],
      grad: Array[Float],
      lr: Double,
      state: AdamState
  ): Unit =
    state.step += 1
    val mArr = state.m.getOrElseUpdate(name, new Array[Float](param.length))
    val vArr = state.v.getOrElseUpdate(name, new Array[Float](param.length))

    val b1 = state.beta1.toFloat
    val b2 = state.beta2.toFloat
    val eps = state.epsilon.toFloat
    val lrf = lr.toFloat

    val b1CorrInv = 1.0f / (1.0f - math.pow(state.beta1, state.step).toFloat)
    val b2CorrInv = 1.0f / (1.0f - math.pow(state.beta2, state.step).toFloat)

    var i = 0
    while i < param.length && i < grad.length do
      mArr(i) = b1 * mArr(i) + (1.0f - b1) * grad(i)
      vArr(i) = b2 * vArr(i) + (1.0f - b2) * grad(i) * grad(i)
      val mHat = mArr(i) * b1CorrInv
      val vHat = vArr(i) * b2CorrInv
      param(i) -= lrf * mHat / (math.sqrt(vHat).toFloat + eps)
      i += 1

  /** Initialize parameter tensors from a graph's Parameter nodes. */
  def initializeParams(graph: ComputeGraph): scala.collection.mutable.Map[String, Tensor] =
    val params = scala.collection.mutable.Map[String, Tensor]()
    for node <- graph.nodes do collectParams(node.expr, params, new IdentityHashMap())
    params

  private def collectParams(
      expr: TensorExpr,
      params: scala.collection.mutable.Map[String, Tensor],
      visited: IdentityHashMap[TensorExpr, java.lang.Boolean]
  ): Unit =
    if visited.containsKey(expr) then return
    visited.put(expr, java.lang.Boolean.TRUE)

    expr match
      case TensorExpr.Parameter(name, tensorType, initializer) =>
        if !params.contains(name) then
          val shape = staticShape(tensorType)
          val size = if shape.isEmpty then 1 else shape.product
          val data = initializer match
            case Some(Initializer.Zeros) => new Array[Float](size)
            case Some(Initializer.Ones)  =>
              val arr = new Array[Float](size)
              java.util.Arrays.fill(arr, 1.0f)
              arr
            case Some(Initializer.Normal(mean, std)) =>
              val rng = new java.util.Random(name.hashCode.toLong)
              Array.fill(size)(mean.toFloat + std.toFloat * rng.nextGaussian().toFloat)
            case Some(Initializer.Xavier(gain)) =>
              val fanIn = if shape.size >= 2 then shape.last else size
              val fanOut = if shape.size >= 2 then shape.head else size
              val std = gain * math.sqrt(2.0 / (fanIn + fanOut))
              val rng = new java.util.Random(name.hashCode.toLong)
              Array.fill(size)((std * rng.nextGaussian()).toFloat)
            case Some(Initializer.Kaiming(_, _)) =>
              val fanIn = if shape.size >= 2 then shape.last else size
              val std = math.sqrt(2.0 / fanIn)
              val rng = new java.util.Random(name.hashCode.toLong)
              Array.fill(size)((std * rng.nextGaussian()).toFloat)
            case Some(Initializer.Uniform(low, high)) =>
              val rng = new java.util.Random(name.hashCode.toLong)
              Array.fill(size)(low.toFloat + (high.toFloat - low.toFloat) * rng.nextFloat())
            case None =>
              // Default: small random values
              val rng = new java.util.Random(name.hashCode.toLong)
              Array.fill(size)(0.01f * rng.nextGaussian().toFloat)
          params(name) = Tensor(data, shape)
      case _ =>
        childExprs(expr).foreach(collectParams(_, params, visited))

  // ════════════════════════════════════════════════════════════════════════════
  // Expression evaluation
  // ════════════════════════════════════════════════════════════════════════════

  private def evalExpr(
      expr: TensorExpr,
      inputs: Map[String, Tensor],
      params: Map[String, Tensor],
      env: IdentityHashMap[TensorExpr, Tensor]
  ): Tensor =
    // Check cache first
    val cached = env.get(expr)
    if cached != null then return cached

    val result = expr match
      // ── Leaf nodes ──
      case TensorExpr.Input(name, _) =>
        inputs.getOrElse(name, throw ExecutionException(s"Input '$name' not provided"))

      case TensorExpr.Constant(_, tensorType, data) =>
        val shape = staticShape(tensorType)
        Tensor(toFloatArray(data), shape)

      case TensorExpr.Parameter(name, tensorType, _) =>
        params.getOrElse(
          name, {
            val shape = staticShape(tensorType)
            Tensor(new Array[Float](if shape.isEmpty then 1 else shape.product), shape)
          }
        )

      // ── Element-wise arithmetic ──
      case TensorExpr.Add(left, right) =>
        val l = eval(left, inputs, params, env)
        val r = eval(right, inputs, params, env)
        elementWise(l, r, _ + _)

      case TensorExpr.Sub(left, right) =>
        val l = eval(left, inputs, params, env)
        val r = eval(right, inputs, params, env)
        elementWise(l, r, _ - _)

      case TensorExpr.Mul(left, right) =>
        val l = eval(left, inputs, params, env)
        val r = eval(right, inputs, params, env)
        elementWise(l, r, _ * _)

      case TensorExpr.Div(left, right) =>
        val l = eval(left, inputs, params, env)
        val r = eval(right, inputs, params, env)
        elementWise(l, r, _ / _)

      case TensorExpr.Pow(base, exponent) =>
        val b = eval(base, inputs, params, env)
        val e = eval(exponent, inputs, params, env)
        elementWise(b, e, (x, y) => math.pow(x, y).toFloat)

      case TensorExpr.Neg(input) =>
        val t = eval(input, inputs, params, env)
        Tensor(t.data.map(-_), t.shape)

      case TensorExpr.Abs(input) =>
        val t = eval(input, inputs, params, env)
        Tensor(t.data.map(math.abs), t.shape)

      case TensorExpr.Exp(input) =>
        val t = eval(input, inputs, params, env)
        Tensor(t.data.map(x => math.exp(x).toFloat), t.shape)

      case TensorExpr.Log(input) =>
        val t = eval(input, inputs, params, env)
        Tensor(t.data.map(x => math.log(x).toFloat), t.shape)

      case TensorExpr.Sqrt(input) =>
        val t = eval(input, inputs, params, env)
        Tensor(t.data.map(x => math.sqrt(x).toFloat), t.shape)

      // ── Activations ──
      case TensorExpr.Relu(input) =>
        val t = eval(input, inputs, params, env)
        Tensor(t.data.map(x => math.max(0.0f, x)), t.shape)

      case TensorExpr.Sigmoid(input) =>
        val t = eval(input, inputs, params, env)
        Tensor(t.data.map(x => (1.0 / (1.0 + math.exp(-x))).toFloat), t.shape)

      case TensorExpr.Tanh(input) =>
        val t = eval(input, inputs, params, env)
        Tensor(t.data.map(x => math.tanh(x).toFloat), t.shape)

      case TensorExpr.Softmax(input, axis) =>
        val t = eval(input, inputs, params, env)
        softmax(t, normalizeAxis(axis, t.shape.size))

      case TensorExpr.LeakyRelu(input, alpha) =>
        val t = eval(input, inputs, params, env)
        val a = alpha.toFloat
        Tensor(t.data.map(x => if x > 0 then x else a * x), t.shape)

      // ── Linear algebra ──
      case TensorExpr.MatMul(left, right, transA, transB) =>
        val l = eval(left, inputs, params, env)
        val r = eval(right, inputs, params, env)
        matMul(l, r, transA, transB)

      case TensorExpr.Linear(input, weight, bias) =>
        val x = eval(input, inputs, params, env)
        val w = eval(weight, inputs, params, env)
        // Linear: output = x @ w^T + bias
        val xw = matMul(x, w, transA = false, transB = true)
        bias match
          case Some(b) =>
            val bTensor = eval(b, inputs, params, env)
            elementWise(xw, bTensor, _ + _)
          case None => xw

      case TensorExpr.Gemm(a, b, c, alpha, beta, transA, transB) =>
        val at = eval(a, inputs, params, env)
        val bt = eval(b, inputs, params, env)
        val ab = matMul(at, bt, transA, transB)
        val scaled = Tensor(ab.data.map(_ * alpha.toFloat), ab.shape)
        c match
          case Some(cExpr) =>
            val ct = eval(cExpr, inputs, params, env)
            val betaScaled = Tensor(ct.data.map(_ * beta.toFloat), ct.shape)
            elementWise(scaled, betaScaled, _ + _)
          case None => scaled

      // ── Reduction ──
      case TensorExpr.ReduceSum(input, axes, keepDims) =>
        val t = eval(input, inputs, params, env)
        reduce(t, axes, keepDims, _.sum)

      case TensorExpr.ReduceMean(input, axes, keepDims) =>
        val t = eval(input, inputs, params, env)
        reduce(t, axes, keepDims, arr => arr.sum / arr.length)

      // ── Shape manipulation ──
      case TensorExpr.Reshape(input, shape) =>
        val t = eval(input, inputs, params, env)
        val resolvedShape = resolveReshape(t.numElements, shape)
        Tensor(t.data, resolvedShape)

      case TensorExpr.Transpose(input, perm) =>
        val t = eval(input, inputs, params, env)
        transpose(t, perm)

      case TensorExpr.Flatten(input, axis) =>
        val t = eval(input, inputs, params, env)
        val normAxis = normalizeAxis(axis, t.shape.size)
        val front = t.shape.take(normAxis).product
        val back = t.shape.drop(normAxis).product
        Tensor(t.data, Vector(front, back))

      case TensorExpr.Squeeze(input, axes) =>
        val t = eval(input, inputs, params, env)
        val normAxes = axes.map(normalizeAxis(_, t.shape.size)).toSet
        val newShape =
          t.shape.zipWithIndex.filterNot { (d, i) => normAxes.contains(i) && d == 1 }.map(_._1)
        Tensor(t.data, newShape)

      case TensorExpr.Unsqueeze(input, axes) =>
        val t = eval(input, inputs, params, env)
        var newShape = t.shape.toBuffer
        for ax <- axes.sorted do
          val normAx = normalizeAxis(ax, newShape.size + 1)
          newShape.insert(normAx, 1)
        Tensor(t.data, newShape.toVector)

      case TensorExpr.Expand(input, targetShape) =>
        val t = eval(input, inputs, params, env)
        broadcast(t, targetShape)

      // ── Loss functions ──
      case TensorExpr.MSELoss(input, target, reduction) =>
        val pred = eval(input, inputs, params, env)
        val tgt = eval(target, inputs, params, env)
        mseLoss(pred, tgt, reduction)

      case TensorExpr.L1Loss(input, target, reduction) =>
        val pred = eval(input, inputs, params, env)
        val tgt = eval(target, inputs, params, env)
        l1Loss(pred, tgt, reduction)

      case TensorExpr.CrossEntropyLoss(input, target, weight, reduction) =>
        val pred = eval(input, inputs, params, env)
        val tgt = eval(target, inputs, params, env)
        crossEntropyLoss(pred, tgt, reduction)

      // ── Comparison / logical ──
      case TensorExpr.Greater(left, right) =>
        val l = eval(left, inputs, params, env)
        val r = eval(right, inputs, params, env)
        elementWise(l, r, (a, b) => if a > b then 1.0f else 0.0f)

      case TensorExpr.Less(left, right) =>
        val l = eval(left, inputs, params, env)
        val r = eval(right, inputs, params, env)
        elementWise(l, r, (a, b) => if a < b then 1.0f else 0.0f)

      case TensorExpr.Equal(left, right) =>
        val l = eval(left, inputs, params, env)
        val r = eval(right, inputs, params, env)
        elementWise(l, r, (a, b) => if a == b then 1.0f else 0.0f)

      case TensorExpr.Where(condition, x, y) =>
        val c = eval(condition, inputs, params, env)
        val xTensor = eval(x, inputs, params, env)
        val yTensor = eval(y, inputs, params, env)
        whereTensor(c, xTensor, yTensor)

      // ── Normalization ──
      case TensorExpr.BatchNorm(input, scale, bias, runningMean, runningVar, epsilon, _, _) =>
        val x = eval(input, inputs, params, env)
        val s = eval(scale, inputs, params, env)
        val b2 = eval(bias, inputs, params, env)
        val rm = eval(runningMean, inputs, params, env)
        val rv = eval(runningVar, inputs, params, env)
        batchNorm(x, s, b2, rm, rv, epsilon.toFloat)

      // ── Scatter (needed for CrossEntropyLoss gradient) ──
      case TensorExpr.Scatter(input, indices, updates, axis) =>
        val inp = eval(input, inputs, params, env)
        val idx = eval(indices, inputs, params, env)
        val upd = eval(updates, inputs, params, env)
        scatter(inp, idx, upd, normalizeAxis(axis, inp.shape.size))

      // ── Cast ──
      case TensorExpr.Cast(input, _) =>
        // Float32 only — pass through
        eval(input, inputs, params, env)

      // ── Clip ──
      case TensorExpr.Clip(input, min, max) =>
        val t = eval(input, inputs, params, env)
        val minF = min.map(_.toFloat).getOrElse(Float.MinValue)
        val maxF = max.map(_.toFloat).getOrElse(Float.MaxValue)
        Tensor(t.data.map(x => math.max(minF, math.min(maxF, x))), t.shape)

      case TensorExpr.LogSoftmax(input, axis) =>
        val t = eval(input, inputs, params, env)
        val sm = softmax(t, normalizeAxis(axis, t.shape.size))
        Tensor(sm.data.map(x => math.log(math.max(x, 1e-12f)).toFloat), sm.shape)

      case TensorExpr.Dropout(input, _, _) =>
        // Inference mode — identity
        eval(input, inputs, params, env)

      case other =>
        throw ExecutionException(s"Unsupported tensor op: ${other.getClass.getSimpleName}")

    env.put(expr, result)
    result

  private def eval(
      expr: TensorExpr,
      inputs: Map[String, Tensor],
      params: Map[String, Tensor],
      env: IdentityHashMap[TensorExpr, Tensor]
  ): Tensor = evalExpr(expr, inputs, params, env)

  // ════════════════════════════════════════════════════════════════════════════
  // Tensor operations
  // ════════════════════════════════════════════════════════════════════════════

  /** Element-wise binary operation with broadcasting. */
  private def elementWise(
      a: Tensor,
      b: Tensor,
      op: (Float, Float) => Float
  ): Tensor =
    if a.shape == b.shape then Tensor(a.data.zip(b.data).map(op.tupled), a.shape)
    else if b.isScalar then
      val bVal = b.data(0)
      Tensor(a.data.map(op(_, bVal)), a.shape)
    else if a.isScalar then
      val aVal = a.data(0)
      Tensor(b.data.map(op(aVal, _)), b.shape)
    else
      // General broadcasting
      val outShape = broadcastShape(a.shape, b.shape)
      val outSize = outShape.product
      val result = new Array[Float](outSize)
      for i <- 0 until outSize do
        val aIdx = broadcastIndex(i, outShape, a.shape)
        val bIdx = broadcastIndex(i, outShape, b.shape)
        result(i) = op(a.data(aIdx), b.data(bIdx))
      Tensor(result, outShape)

  /** Compute broadcast output shape. */
  private def broadcastShape(a: Vector[Int], b: Vector[Int]): Vector[Int] =
    val maxRank = math.max(a.size, b.size)
    val paddedA = Vector.fill(maxRank - a.size)(1) ++ a
    val paddedB = Vector.fill(maxRank - b.size)(1) ++ b
    paddedA.zip(paddedB).map { (da, db) =>
      if da == db then da
      else if da == 1 then db
      else if db == 1 then da
      else throw ExecutionException(s"Cannot broadcast shapes $a and $b")
    }

  /** Map flat index in output to flat index in source (with broadcasting). */
  private def broadcastIndex(flatIdx: Int, outShape: Vector[Int], srcShape: Vector[Int]): Int =
    val rankDiff = outShape.size - srcShape.size
    val paddedSrc = Vector.fill(rankDiff)(1) ++ srcShape
    var idx = flatIdx
    var srcIdx = 0
    val strides = computeStrides(paddedSrc)
    for d <- outShape.indices do
      val coord = idx / computeStrides(outShape)(d) % outShape(d)
      val srcCoord = if paddedSrc(d) == 1 then 0 else coord
      srcIdx += srcCoord * strides(d)
      ()
    srcIdx

  private def computeStrides(shape: Vector[Int]): Vector[Int] =
    if shape.isEmpty then Vector.empty
    else
      val strides = Array.ofDim[Int](shape.size)
      strides(shape.size - 1) = 1
      for i <- (shape.size - 2) to 0 by -1 do strides(i) = strides(i + 1) * shape(i + 1)
      strides.toVector

  /** Matrix multiplication: (M, K) x (K, N) -> (M, N). */
  private def matMul(a: Tensor, b: Tensor, transA: Boolean, transB: Boolean): Tensor =
    val aShape = if transA then a.shape.reverse else a.shape
    val bShape = if transB then b.shape.reverse else b.shape

    // Handle 1D inputs
    val (m, k1) =
      if aShape.size == 1 then (1, aShape(0))
      else (aShape(aShape.size - 2), aShape(aShape.size - 1))
    val (k2, n) =
      if bShape.size == 1 then (bShape(0), 1)
      else (bShape(bShape.size - 2), bShape(bShape.size - 1))

    if k1 != k2 then throw ExecutionException(s"MatMul dimension mismatch: k=$k1 vs k=$k2")

    val result = new Array[Float](m * n)
    for i <- 0 until m do
      for j <- 0 until n do
        var sum = 0.0f
        for ki <- 0 until k1 do
          val aIdx =
            if transA then ki * m + i
            else i * k1 + ki
          val bIdx =
            if transB then j * k2 + ki
            else ki * n + j
          sum += a.data(aIdx) * b.data(bIdx)
        result(i * n + j) = sum

    // Determine output shape
    val outShape =
      if a.shape.size == 1 && b.shape.size == 1 then Vector(1)
      else if a.shape.size == 1 then Vector(n)
      else if b.shape.size == 1 then Vector(m)
      else Vector(m, n)
    Tensor(result, outShape)

  /** Softmax along axis. */
  private def softmax(t: Tensor, axis: Int): Tensor =
    if t.shape.isEmpty then return Tensor(Array(1.0f), t.shape)

    val normAxis = normalizeAxis(axis, t.shape.size)
    val outerSize = t.shape.take(normAxis).product
    val axisSize = t.shape(normAxis)
    val innerSize = t.shape.drop(normAxis + 1).product

    val result = new Array[Float](t.data.length)
    for outer <- 0 until outerSize do
      for inner <- 0 until innerSize do
        // Find max for numerical stability
        var maxVal = Float.MinValue
        for a <- 0 until axisSize do
          val idx = (outer * axisSize + a) * innerSize + inner
          if t.data(idx) > maxVal then maxVal = t.data(idx)

        // Compute exp and sum
        var sumExp = 0.0f
        for a <- 0 until axisSize do
          val idx = (outer * axisSize + a) * innerSize + inner
          val e = math.exp(t.data(idx) - maxVal).toFloat
          result(idx) = e
          sumExp += e

        // Normalize
        for a <- 0 until axisSize do
          val idx = (outer * axisSize + a) * innerSize + inner
          result(idx) /= sumExp

    Tensor(result, t.shape)

  /** Reduce along axes. */
  private def reduce(
      t: Tensor,
      axes: Vector[Int],
      keepDims: Boolean,
      reduceOp: Array[Float] => Float
  ): Tensor =
    if t.shape.isEmpty then return t

    val normAxes = axes.map(normalizeAxis(_, t.shape.size)).sorted

    // Compute output shape
    val outShape = t.shape.zipWithIndex.flatMap { (d, i) =>
      if normAxes.contains(i) then if keepDims then Some(1) else None
      else Some(d)
    }
    if outShape.isEmpty then
      // Full reduction to scalar
      return Tensor(Array(reduceOp(t.data)), Vector.empty)

    val outSize = if outShape.isEmpty then 1 else outShape.product
    val result = new Array[Float](outSize)
    val groups = Array.fill(outSize)(scala.collection.mutable.ArrayBuffer[Float]())

    val inStrides = computeStrides(t.shape)
    val outStrides = computeStrides(outShape)

    for i <- 0 until t.data.length do
      // Compute multi-index in input
      var remaining = i
      val coords = new Array[Int](t.shape.size)
      for d <- t.shape.indices do
        coords(d) = remaining / inStrides(d)
        remaining %= inStrides(d)

      // Map to output index
      var outIdx = 0
      var outDim = 0
      for d <- t.shape.indices do
        if !normAxes.contains(d) then
          outIdx += coords(d) * outStrides(outDim)
          outDim += 1

      groups(outIdx) += t.data(i)

    for i <- 0 until outSize do result(i) = reduceOp(groups(i).toArray)
    Tensor(result, outShape)

  /** Transpose tensor. */
  private def transpose(t: Tensor, perm: Vector[Int]): Tensor =
    if t.shape.size <= 1 then return t

    val outShape = perm.map(t.shape(_))
    val outSize = outShape.product
    val result = new Array[Float](outSize)
    val inStrides = computeStrides(t.shape)
    val outStrides = computeStrides(outShape)

    for i <- 0 until t.data.length do
      var remaining = i
      val coords = new Array[Int](t.shape.size)
      for d <- t.shape.indices do
        coords(d) = remaining / inStrides(d)
        remaining %= inStrides(d)

      var outIdx = 0
      for d <- perm.indices do outIdx += coords(perm(d)) * outStrides(d)
      result(outIdx) = t.data(i)

    Tensor(result, outShape)

  /** Broadcast tensor to target shape. */
  private def broadcast(t: Tensor, targetShape: Vector[Int]): Tensor =
    if t.shape == targetShape then return t
    val outSize = targetShape.product
    val result = new Array[Float](outSize)
    for i <- 0 until outSize do result(i) = t.data(broadcastIndex(i, targetShape, t.shape))
    Tensor(result, targetShape)

  /** Where: condition ? x : y (element-wise). */
  private def whereTensor(condition: Tensor, x: Tensor, y: Tensor): Tensor =
    val outShape = broadcastShape(broadcastShape(condition.shape, x.shape), y.shape)
    val outSize = if outShape.isEmpty then 1 else outShape.product
    val result = new Array[Float](outSize)
    for i <- 0 until outSize do
      val cIdx = broadcastIndex(i, outShape, condition.shape)
      val xIdx = broadcastIndex(i, outShape, x.shape)
      val yIdx = broadcastIndex(i, outShape, y.shape)
      result(i) = if condition.data(cIdx) != 0.0f then x.data(xIdx) else y.data(yIdx)
    Tensor(result, outShape)

  /** Scatter: replace elements at indices along axis. */
  private def scatter(
      input: Tensor,
      indices: Tensor,
      updates: Tensor,
      axis: Int
  ): Tensor =
    val result = input.data.clone()
    val inStrides = computeStrides(input.shape)
    val idxStrides = computeStrides(indices.shape)

    for i <- 0 until indices.data.length do
      // Compute multi-index in indices tensor
      var remaining = i
      val coords = new Array[Int](indices.shape.size)
      for d <- indices.shape.indices do
        coords(d) = remaining / idxStrides(d)
        remaining %= idxStrides(d)

      // Replace axis coordinate with the index value
      val idxVal = indices.data(i).toInt
      coords(axis) = idxVal

      // Compute flat index in input
      var flatIdx = 0
      for d <- input.shape.indices do flatIdx += coords(d) * inStrides(d)

      if flatIdx >= 0 && flatIdx < result.length then result(flatIdx) = updates.data(i)

    Tensor(result, input.shape)

  // ── Loss functions ──

  private def mseLoss(pred: Tensor, target: Tensor, reduction: Reduction): Tensor =
    val diff = pred.data.zip(target.data).map { (p, t) =>
      val d = p - t; d * d
    }
    reduction match
      case Reduction.Mean => Tensor(Array(diff.sum / diff.length), Vector.empty)
      case Reduction.Sum  => Tensor(Array(diff.sum), Vector.empty)
      case Reduction.None => Tensor(diff, pred.shape)

  private def l1Loss(pred: Tensor, target: Tensor, reduction: Reduction): Tensor =
    val diff = pred.data.zip(target.data).map { (p, t) => math.abs(p - t) }
    reduction match
      case Reduction.Mean => Tensor(Array(diff.sum / diff.length), Vector.empty)
      case Reduction.Sum  => Tensor(Array(diff.sum), Vector.empty)
      case Reduction.None => Tensor(diff, pred.shape)

  private def crossEntropyLoss(pred: Tensor, target: Tensor, reduction: Reduction): Tensor =
    // pred: [batch, numClasses], target: [batch] (class indices as floats)
    if pred.shape.size != 2 then
      throw ExecutionException(s"CrossEntropyLoss requires 2D input, got shape ${pred.shape}")
    val batch = pred.shape(0)
    val numClasses = pred.shape(1)

    val losses = new Array[Float](batch)
    for b <- 0 until batch do
      // Log-softmax for numerical stability
      var maxVal = Float.MinValue
      for c <- 0 until numClasses do
        val v = pred.data(b * numClasses + c)
        if v > maxVal then maxVal = v

      var sumExp = 0.0
      for c <- 0 until numClasses do sumExp += math.exp(pred.data(b * numClasses + c) - maxVal)
      val logSumExp = maxVal + math.log(sumExp)

      val targetClass = target.data(b).toInt
      losses(b) = (logSumExp - pred.data(b * numClasses + targetClass)).toFloat

    reduction match
      case Reduction.Mean => Tensor(Array(losses.sum / batch), Vector.empty)
      case Reduction.Sum  => Tensor(Array(losses.sum), Vector.empty)
      case Reduction.None => Tensor(losses, Vector(batch))

  /** Batch normalization (inference mode). */
  private def batchNorm(
      x: Tensor,
      scale: Tensor,
      bias: Tensor,
      mean: Tensor,
      variance: Tensor,
      epsilon: Float
  ): Tensor =
    // x: [N, C, ...], scale/bias/mean/var: [C]
    val channels = scale.data.length
    val result = x.data.clone()
    val spatialSize = x.data.length / (x.shape(0) * channels)

    for n <- 0 until x.shape(0) do
      for c <- 0 until channels do
        val std = math.sqrt(variance.data(c) + epsilon).toFloat
        for s <- 0 until spatialSize do
          val idx = (n * channels + c) * spatialSize + s
          result(idx) = scale.data(c) * (x.data(idx) - mean.data(c)) / std + bias.data(c)

    Tensor(result, x.shape)

  // ── Helpers ──

  /** Resolve -1 in reshape targets. */
  private def resolveReshape(totalElements: Int, shape: Vector[Int]): Vector[Int] =
    val negIdx = shape.indexOf(-1)
    if negIdx < 0 then shape
    else
      val known = shape.filter(_ >= 0).product
      val inferred = totalElements / known
      shape.updated(negIdx, inferred)

  private def normalizeAxis(axis: Int, rank: Int): Int =
    if axis >= 0 then axis else rank + axis

  private def staticShape(tt: TensorType): Vector[Int] =
    tt.shape.map {
      case Dim.Static(s)  => s
      case Dim.Dynamic(_) => 1 // Default dynamic dims to 1
    }

  private def toFloatArray(data: TensorData): Array[Float] =
    data.dtype match
      case TensorDType.Float32 =>
        val buf = ByteBuffer.wrap(data.rawBytes).order(ByteOrder.LITTLE_ENDIAN)
        val arr = new Array[Float](data.rawBytes.length / 4)
        buf.asFloatBuffer().get(arr)
        arr
      case TensorDType.Float64 =>
        val buf = ByteBuffer.wrap(data.rawBytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = data.rawBytes.length / 8
        val arr = new Array[Float](n)
        for i <- 0 until n do arr(i) = buf.getDouble().toFloat
        arr
      case TensorDType.Int32 =>
        val buf = ByteBuffer.wrap(data.rawBytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = data.rawBytes.length / 4
        val arr = new Array[Float](n)
        for i <- 0 until n do arr(i) = buf.getInt().toFloat
        arr
      case TensorDType.Int64 =>
        val buf = ByteBuffer.wrap(data.rawBytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = data.rawBytes.length / 8
        val arr = new Array[Float](n)
        for i <- 0 until n do arr(i) = buf.getLong().toFloat
        arr
      case _ =>
        if data.rawBytes.length == 0 then Array.empty
        else
          val buf = ByteBuffer.wrap(data.rawBytes).order(ByteOrder.LITTLE_ENDIAN)
          val arr = new Array[Float](data.rawBytes.length / 4)
          buf.asFloatBuffer().get(arr)
          arr

  /** Get child expressions (mirrors MLTreeTransform.childExprs). */
  private def childExprs(expr: TensorExpr): Vector[TensorExpr] =
    expr match
      case _: TensorExpr.Input | _: TensorExpr.Constant | _: TensorExpr.Parameter =>
        Vector.empty
      case TensorExpr.Add(l, r)                         => Vector(l, r)
      case TensorExpr.Sub(l, r)                         => Vector(l, r)
      case TensorExpr.Mul(l, r)                         => Vector(l, r)
      case TensorExpr.Div(l, r)                         => Vector(l, r)
      case TensorExpr.Pow(l, r)                         => Vector(l, r)
      case TensorExpr.Neg(x)                            => Vector(x)
      case TensorExpr.Abs(x)                            => Vector(x)
      case TensorExpr.Exp(x)                            => Vector(x)
      case TensorExpr.Log(x)                            => Vector(x)
      case TensorExpr.Sqrt(x)                           => Vector(x)
      case TensorExpr.Relu(x)                           => Vector(x)
      case TensorExpr.Sigmoid(x)                        => Vector(x)
      case TensorExpr.Tanh(x)                           => Vector(x)
      case TensorExpr.Softmax(x, _)                     => Vector(x)
      case TensorExpr.LeakyRelu(x, _)                   => Vector(x)
      case TensorExpr.LogSoftmax(x, _)                  => Vector(x)
      case TensorExpr.Dropout(x, _, _)                  => Vector(x)
      case TensorExpr.Clip(x, _, _)                     => Vector(x)
      case TensorExpr.Cast(x, _)                        => Vector(x)
      case TensorExpr.MatMul(l, r, _, _)                => Vector(l, r)
      case TensorExpr.Linear(x, w, b)                   => Vector(x, w) ++ b.toVector
      case TensorExpr.Gemm(a, b, c, _, _, _, _)         => Vector(a, b) ++ c.toVector
      case TensorExpr.ReduceSum(x, _, _)                => Vector(x)
      case TensorExpr.ReduceMean(x, _, _)               => Vector(x)
      case TensorExpr.Reshape(x, _)                     => Vector(x)
      case TensorExpr.Transpose(x, _)                   => Vector(x)
      case TensorExpr.Flatten(x, _)                     => Vector(x)
      case TensorExpr.Squeeze(x, _)                     => Vector(x)
      case TensorExpr.Unsqueeze(x, _)                   => Vector(x)
      case TensorExpr.Expand(x, _)                      => Vector(x)
      case TensorExpr.MSELoss(x, t, _)                  => Vector(x, t)
      case TensorExpr.L1Loss(x, t, _)                   => Vector(x, t)
      case TensorExpr.CrossEntropyLoss(x, t, w, _)      => Vector(x, t) ++ w.toVector
      case TensorExpr.Greater(l, r)                     => Vector(l, r)
      case TensorExpr.Less(l, r)                        => Vector(l, r)
      case TensorExpr.Equal(l, r)                       => Vector(l, r)
      case TensorExpr.Where(c, x, y)                    => Vector(c, x, y)
      case TensorExpr.Scatter(i, idx, u, _)             => Vector(i, idx, u)
      case TensorExpr.BatchNorm(x, s, b, m, v, _, _, _) => Vector(x, s, b, m, v)
      case _                                            => Vector.empty
