package protocatalyst.ml.optimizer.rules

import java.util.IdentityHashMap

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph.ComputeGraph
import protocatalyst.ml.optimizer.{MLRule, MLTreeTransform}

/** Removes nodes from the graph that are not reachable from outputs.
  *
  * This operates at the ComputeGraph level, removing NamedNodes whose expressions are not
  * transitively referenced by any output node.
  */
object DeadNodeElimination extends MLRule:
  override def apply(graph: ComputeGraph): ComputeGraph =
    if graph.nodes.isEmpty then return graph

    // Collect all TensorExpr instances reachable from the last node (the output)
    val reachable = new IdentityHashMap[TensorExpr, java.lang.Boolean]()
    graph.nodes.lastOption.foreach(n => collectReachable(n.expr, reachable))

    val newNodes = graph.nodes.filter(n => reachable.containsKey(n.expr))
    if newNodes.size == graph.nodes.size then graph
    else graph.copy(nodes = newNodes)

  private def collectReachable(
      expr: TensorExpr,
      visited: IdentityHashMap[TensorExpr, java.lang.Boolean]
  ): Unit =
    if visited.containsKey(expr) then return
    visited.put(expr, java.lang.Boolean.TRUE)
    MLTreeTransform.childExprs(expr).foreach(collectReachable(_, visited))
