package protocatalyst.ml.optimizer

import java.nio.{ByteBuffer, ByteOrder}
import java.util.IdentityHashMap

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph._
import protocatalyst.ml.types._

/** Symbolic reverse-mode automatic differentiation for ComputeGraph.
  *
  * Transforms a forward-pass graph into a new graph with backward (gradient) nodes appended. The
  * result contains the original forward nodes plus gradient nodes for all trainable Parameters,
  * with new outputs named `grad/{param_name}`.
  */
object Autograd:

  class AutogradException(message: String) extends IllegalArgumentException(message)

  /** Build a backward graph: append gradient nodes for all trainable Parameters.
    *
    * @param graph
    *   the forward-pass computation graph
    * @param lossNodeName
    *   name of the NamedNode whose output is a scalar loss
    * @return
    *   a new graph with original forward nodes plus backward gradient nodes, with new outputs named
    *   `grad/{param_name}` for each Parameter
    */
  def backward(graph: ComputeGraph, lossNodeName: String): ComputeGraph =
    // 1. Find the loss node
    val lossNode = graph.nodeMap.getOrElse(
      lossNodeName,
      throw AutogradException(s"Node '$lossNodeName' not found in graph")
    )

    // 2. Validate scalar output
    if lossNode.outputType.shape.nonEmpty then
      throw AutogradException(
        s"Loss node '$lossNodeName' must be scalar (rank 0), got rank ${lossNode.outputType.shape.size}"
      )

    // 3. Topological sort of all expressions reachable from the loss
    val topoOrder = topoSort(lossNode.expr)

    // 4. Build type map using ShapeInference
    val inputEnv = graph.inputs.map(io => io.name -> io.tensorType).toMap
    val typeMap = buildTypeMap(topoOrder, inputEnv)

    // 5. Seed the gradient map with scalar 1.0
    val gradMap = new IdentityHashMap[TensorExpr, TensorExpr]()
    val seed = scalarOne(lossNode.outputType.dtype)
    gradMap.put(lossNode.expr, seed)

    // 6. Reverse topological traversal — propagate gradients
    for expr <- topoOrder.reverseIterator do
      val upstreamGrad = gradMap.get(expr)
      if upstreamGrad != null then
        expr match
          case _: TensorExpr.Input | _: TensorExpr.Constant | _: TensorExpr.Parameter =>
            () // Leaf: gradient accumulates here, nothing to propagate through
          case _ =>
            val children = MLTreeTransform.childExprs(expr)
            val childGrads = VJPRules.vjp(expr, upstreamGrad, resolveType(typeMap, inputEnv))
            children.zip(childGrads).foreach { (child, gradOpt) =>
              gradOpt.foreach { grad =>
                accumulateGrad(gradMap, child, grad)
              }
            }

    // 7. Collect parameter gradients
    val paramGrads = topoOrder.collect {
      case p @ TensorExpr.Parameter(name, _, _) if gradMap.containsKey(p) =>
        (name, p, gradMap.get(p))
    }

    if paramGrads.isEmpty then return graph

    // 8. Build gradient NamedNodes
    val forwardExprs = collectAllExprs(topoOrder)
    val gradNodes = linearizeGradNodes(paramGrads, forwardExprs, inputEnv)

    // 9. Assemble output graph
    val gradOutputs = paramGrads.map { (name, param, gradExpr) =>
      val gradType = ShapeInference.inferType(gradExpr, inputEnv)
      GraphIO(s"grad/$name", gradType)
    }

    graph.copy(
      nodes = graph.nodes ++ gradNodes,
      outputs = graph.outputs ++ gradOutputs.toVector
    )

  // ════════════════════════════════════════════════════════════════════════════
  // Internal methods
  // ════════════════════════════════════════════════════════════════════════════

  /** Create a type resolution function that checks the pre-built type map first, then falls back to
    * ShapeInference for newly constructed expressions (e.g., intermediate expressions created by
    * VJP rules).
    */
  private def resolveType(
      typeMap: IdentityHashMap[TensorExpr, TensorType],
      inputEnv: Map[String, TensorType]
  ): TensorExpr => TensorType =
    val cache = new IdentityHashMap[TensorExpr, TensorType]()
    (expr: TensorExpr) =>
      val fromMap = typeMap.get(expr)
      if fromMap != null then fromMap
      else
        val cached = cache.get(expr)
        if cached != null then cached
        else
          val inferred = ShapeInference.inferType(expr, inputEnv)
          cache.put(expr, inferred)
          inferred

  /** Build topological order of all TensorExpr reachable from `root`. Uses IdentityHashMap for
    * DAG-aware traversal (each node visited exactly once).
    */
  private def topoSort(root: TensorExpr): Vector[TensorExpr] =
    val visited = new IdentityHashMap[TensorExpr, java.lang.Boolean]()
    val result = Vector.newBuilder[TensorExpr]

    def visit(expr: TensorExpr): Unit =
      if visited.containsKey(expr) then return
      visited.put(expr, java.lang.Boolean.TRUE)
      MLTreeTransform.childExprs(expr).foreach(visit)
      result += expr

    visit(root)
    result.result()

  /** Build a type map for all expressions in topological order. */
  private def buildTypeMap(
      topoOrder: Vector[TensorExpr],
      inputEnv: Map[String, TensorType]
  ): IdentityHashMap[TensorExpr, TensorType] =
    val typeMap = new IdentityHashMap[TensorExpr, TensorType]()
    for expr <- topoOrder do
      val tt = ShapeInference.inferType(expr, inputEnv)
      typeMap.put(expr, tt)
    typeMap

  /** Accumulate a gradient contribution. If the expression already has a gradient, sum them. */
  private def accumulateGrad(
      gradMap: IdentityHashMap[TensorExpr, TensorExpr],
      expr: TensorExpr,
      grad: TensorExpr
  ): Unit =
    val existing = gradMap.get(expr)
    if existing == null then gradMap.put(expr, grad)
    else gradMap.put(expr, TensorExpr.Add(existing, grad))

  /** Create the scalar 1.0 seed gradient constant. */
  private def scalarOne(dtype: TensorDType): TensorExpr =
    val bytes = dtype match
      case TensorDType.Float32 =>
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putFloat(1.0f)
        buf.array()
      case TensorDType.Float64 =>
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putDouble(1.0)
        buf.array()
      case _ =>
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putFloat(1.0f)
        buf.array()
    TensorExpr.Constant(
      "grad/seed",
      TensorType(dtype, Vector.empty),
      TensorData(dtype, Vector.empty, bytes)
    )

  /** Collect all expression identities into a set (for distinguishing forward from backward). */
  private def collectAllExprs(
      topoOrder: Vector[TensorExpr]
  ): IdentityHashMap[TensorExpr, java.lang.Boolean] =
    val set = new IdentityHashMap[TensorExpr, java.lang.Boolean]()
    for expr <- topoOrder do set.put(expr, java.lang.Boolean.TRUE)
    set

  /** Linearize gradient expression DAGs into NamedNode entries.
    *
    * Walks the gradient expressions in topological order, skipping nodes that are part of the
    * forward graph. Assigns names and infers types for each new gradient node.
    */
  private def linearizeGradNodes(
      paramGrads: Vector[(String, TensorExpr.Parameter, TensorExpr)],
      forwardExprs: IdentityHashMap[TensorExpr, java.lang.Boolean],
      inputEnv: Map[String, TensorType]
  ): Vector[NamedNode] =
    // Collect all gradient expression roots
    val gradRoots = paramGrads.map(_._3)

    // Topo-sort all new (non-forward) gradient nodes
    val visited = new IdentityHashMap[TensorExpr, java.lang.Boolean]()
    val gradTopoOrder = Vector.newBuilder[TensorExpr]

    def visit(expr: TensorExpr): Unit =
      if visited.containsKey(expr) then return
      visited.put(expr, java.lang.Boolean.TRUE)
      MLTreeTransform.childExprs(expr).foreach(visit)
      if !forwardExprs.containsKey(expr) then gradTopoOrder += expr

    gradRoots.foreach(visit)
    val orderedGradExprs = gradTopoOrder.result()

    // Assign names and build NamedNodes
    val nameMap = new IdentityHashMap[TensorExpr, String]()
    // Map param grad roots to their final names
    paramGrads.foreach { (name, _, gradExpr) =>
      nameMap.put(gradExpr, s"grad/$name")
    }

    var counter = 0
    val nodeBuilder = Vector.newBuilder[NamedNode]
    for expr <- orderedGradExprs do
      val name =
        if nameMap.containsKey(expr) then nameMap.get(expr)
        else
          counter += 1
          val n = s"grad/op_$counter"
          nameMap.put(expr, n)
          n
      val tt = ShapeInference.inferType(expr, inputEnv)
      nodeBuilder += NamedNode(name, expr, tt)

    nodeBuilder.result()
