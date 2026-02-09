package protocatalyst.executor.exec

import scala.collection.mutable

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector._

import protocatalyst.arrow.ArrowSchemaConverter
import protocatalyst.executor.Catalog
import protocatalyst.executor.exec.operators._
import protocatalyst.plan._

/** Recursive plan interpreter. Walks the ProtoLogicalPlan tree and produces Batch results.
  *
  * Each plan node is evaluated by recursively evaluating its children, then applying the operator
  * logic. The CTE context is mutable to support WITH clauses.
  */
class PlanExecutor(
    catalog: Catalog,
    allocator: BufferAllocator,
    cteContext: mutable.Map[String, Batch] = mutable.Map.empty
):
  val evaluator: ExprEvaluator = ExprEvaluator(allocator)

  // Wire up subquery evaluation so ExprEvaluator can handle ScalarSubquery, Exists, InSubquery
  evaluator.subqueryEvaluator = Some(subPlan => execute(subPlan))

  def execute(plan: ProtoLogicalPlan): Batch = plan match
    // === Leaf nodes ===
    case ProtoLogicalPlan.RelationRef(name, alias, _) =>
      // Check CTE context first, then catalog
      cteContext.getOrElse(
        name,
        catalog
          .getTable(name)
          .getOrElse(
            throw ExecutionException(s"Table not found: $name")
          )
      )

    case ProtoLogicalPlan.Values(rows, schema) =>
      Batch.fromValues(rows, schema, allocator)

    // === Unary operators ===
    case ProtoLogicalPlan.Project(projectList, child) =>
      ProjectOp.execute(execute(child), projectList, evaluator, allocator)

    case ProtoLogicalPlan.Filter(condition, child) =>
      FilterOp.execute(execute(child), condition, evaluator, allocator)

    case ProtoLogicalPlan.Sort(order, child) =>
      SortOp.execute(execute(child), order, evaluator, allocator)

    case ProtoLogicalPlan.Limit(n, child) =>
      limitBatch(execute(child), n)

    case ProtoLogicalPlan.Distinct(child) =>
      distinctBatch(execute(child))

    case ProtoLogicalPlan.SubqueryAlias(_, child) =>
      execute(child) // Alias is metadata only — no runtime effect

    case ProtoLogicalPlan.ResolvedHint(_, child) =>
      execute(child) // Hints are no-op in the local executor

    // === Aggregate ===
    case ProtoLogicalPlan.Aggregate(groups, aggs, child) =>
      AggregateOp.execute(execute(child), groups, aggs, evaluator, allocator)

    // === Binary operators ===
    case ProtoLogicalPlan.Join(left, right, joinType, condition) =>
      JoinOp.execute(execute(left), execute(right), joinType, condition, evaluator, allocator)

    case ProtoLogicalPlan.Intersect(left, right, isAll) =>
      SetOps.intersect(execute(left), execute(right), isAll, allocator)

    case ProtoLogicalPlan.Except(left, right, isAll) =>
      SetOps.except(execute(left), execute(right), isAll, allocator)

    // === Multi-child ===
    case ProtoLogicalPlan.Union(children, byName, _) =>
      SetOps.union(children.map(execute), byName, allocator)

    // === Window ===
    case ProtoLogicalPlan.Window(wExprs, partition, order, child) =>
      WindowOp.execute(execute(child), wExprs, partition, order, evaluator, allocator)

    // === CTE ===
    case ProtoLogicalPlan.With(ctes, recursive, child) =>
      if recursive then throw ExecutionException("Recursive CTEs not yet implemented")
      for (name, ctePlan) <- ctes do cteContext(name) = execute(ctePlan)
      execute(child)

    // === Advanced operators ===
    case ProtoLogicalPlan.Pivot(groups, pivotCol, pivotVals, aggs, child) =>
      AdvancedOps.pivot(execute(child), groups, pivotCol, pivotVals, aggs, evaluator, allocator)

    case ProtoLogicalPlan.Unpivot(valCol, varCol, cols, nulls, child) =>
      AdvancedOps.unpivot(execute(child), valCol, varCol, cols, nulls, evaluator, allocator)

    case ProtoLogicalPlan.LateralJoin(left, lateral, cond) =>
      AdvancedOps.lateralJoin(execute(left), () => execute(lateral), cond, evaluator, allocator)

    case ProtoLogicalPlan.Generate(gen, output, outer, child) =>
      AdvancedOps.generate(execute(child), gen, output, outer, evaluator, allocator)

    // === ML operators (not supported in logical executor — use physical executor) ===
    case _: ProtoLogicalPlan.Predict =>
      throw ExecutionException("Predict requires the physical plan executor")
    case _: ProtoLogicalPlan.Fit =>
      throw ExecutionException("Fit requires the physical plan executor")

  /** Limit: take only the first n rows from a batch. */
  private def limitBatch(batch: Batch, n: Int): Batch =
    if n >= batch.rowCount then batch
    else
      val arrowSchema = ArrowSchemaConverter.toArrowSchema(batch.schema)
      val root = VectorSchemaRoot.create(arrowSchema, allocator)
      root.allocateNew()

      for colIdx <- 0 until batch.numColumns do
        val src = batch.root.getVector(colIdx)
        val dst = root.getVector(colIdx)
        for i <- 0 until n do evaluator.copyValue(src, i, dst, i)

      root.setRowCount(n)
      Batch.fromRoot(root, batch.schema)

  /** Distinct: remove duplicate rows using hash-based deduplication. */
  private def distinctBatch(batch: Batch): Batch =
    if batch.rowCount <= 1 then return batch

    val seen = mutable.HashSet[Vector[Any]]()
    val indices = mutable.ArrayBuffer[Int]()

    for i <- 0 until batch.rowCount do
      val row =
        (0 until batch.numColumns).map(c => Batch.getValue(batch.root.getVector(c), i)).toVector
      if seen.add(row) then indices += i

    if indices.size == batch.rowCount then batch
    else
      val arrowSchema = ArrowSchemaConverter.toArrowSchema(batch.schema)
      val root = VectorSchemaRoot.create(arrowSchema, allocator)
      root.allocateNew()

      for (srcIdx, dstIdx) <- indices.zipWithIndex do
        for colIdx <- 0 until batch.numColumns do
          evaluator.copyValue(batch.root.getVector(colIdx), srcIdx, root.getVector(colIdx), dstIdx)

      root.setRowCount(indices.size)
      Batch.fromRoot(root, batch.schema)
