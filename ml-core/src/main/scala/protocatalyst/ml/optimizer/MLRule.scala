package protocatalyst.ml.optimizer

import protocatalyst.ml.graph.ComputeGraph

/** Base trait for ML graph optimization rules.
  *
  * Each rule transforms a compute graph into an equivalent but optimized graph. Rules should be
  * idempotent: applying a rule twice should produce the same result.
  */
trait MLRule:
  val ruleName: String = getClass.getSimpleName.stripSuffix("$")
  def apply(graph: ComputeGraph): ComputeGraph

object MLRule:
  def apply(name: String)(f: ComputeGraph => ComputeGraph): MLRule =
    new MLRule:
      override val ruleName: String = name
      override def apply(graph: ComputeGraph): ComputeGraph = f(graph)
