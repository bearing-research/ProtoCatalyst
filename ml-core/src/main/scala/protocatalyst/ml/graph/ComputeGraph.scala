package protocatalyst.ml.graph

import java.io.Serializable

import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.types.TensorType

/** A complete computation graph.
  *
  * Analogous to ProtoLogicalPlan for SQL. Unlike SQL plans (which are trees), ML computation graphs
  * are DAGs — the same TensorExpr instance can appear as a child of multiple parents.
  */
case class ComputeGraph(
    name: String,
    inputs: Vector[GraphIO],
    outputs: Vector[GraphIO],
    nodes: Vector[NamedNode],
    opsetVersion: Int = 1
) extends Serializable:

  /** All named nodes, keyed by name. */
  def nodeMap: Map[String, NamedNode] = nodes.map(n => n.name -> n).toMap

  /** Number of operations (non-leaf nodes). */
  def numOps: Int = nodes.size

/** Named input or output of a computation graph. */
case class GraphIO(
    name: String,
    tensorType: TensorType
) extends Serializable

/** A named node in the computation graph with its output type. */
case class NamedNode(
    name: String,
    expr: TensorExpr,
    outputType: TensorType
) extends Serializable
