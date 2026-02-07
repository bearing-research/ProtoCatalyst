package protocatalyst.ml.optimizer

import protocatalyst.ml.graph.ComputeGraph

/** Execution strategy for a batch of ML rules. */
enum MLStrategy:
  case Once
  case FixedPoint(maxIterations: Int)

/** A batch of ML rules with an execution strategy. */
case class MLBatch(name: String, strategy: MLStrategy, rules: Seq[MLRule])

/** Base class for ML rule-based graph transformers. */
abstract class MLRuleExecutor:
  def batches: Seq[MLBatch]

  def execute(graph: ComputeGraph): ComputeGraph =
    batches.foldLeft(graph) { (currentGraph, batch) =>
      executeBatch(currentGraph, batch)
    }

  protected def executeBatch(graph: ComputeGraph, batch: MLBatch): ComputeGraph =
    batch.strategy match
      case MLStrategy.Once =>
        batch.rules.foldLeft(graph) { (g, rule) => rule(g) }

      case MLStrategy.FixedPoint(maxIterations) =>
        var currentGraph = graph
        var iteration = 0
        var changed = true
        while changed && iteration < maxIterations do
          val before = currentGraph
          currentGraph = batch.rules.foldLeft(currentGraph) { (g, rule) => rule(g) }
          changed = currentGraph != before
          iteration += 1
        currentGraph
