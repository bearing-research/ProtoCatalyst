package protocatalyst.executor.physical

import scala.collection.mutable

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector._

import protocatalyst.arrow.ArrowSchemaConverter
import protocatalyst.executor.Catalog
import protocatalyst.executor.exec._
import protocatalyst.executor.exec.operators._
import protocatalyst.plan._
import protocatalyst.schema.ProtoSchema

/** Executes ProtoPhysicalPlan trees against in-memory Arrow batches.
  *
  * Dispatches each physical plan node to the appropriate operator:
  *   - HashJoin / BroadcastHashJoin → HashJoinOp (build/probe)
  *   - NestedLoopJoin → JoinOp (nested loop)
  *   - SortMergeJoin → JoinOp (falls back to nested loop for now)
  *   - HashAggregate / SortAggregate → AggregateOp (already hash-based)
  *   - Exchange → no-op (local executor)
  *   - Pass-through → delegate to existing operators
  */
class PhysicalPlanExecutor(
    catalog: Catalog,
    allocator: BufferAllocator,
    cteContext: mutable.Map[String, Batch] = mutable.Map.empty
):
  val evaluator: ExprEvaluator = ExprEvaluator(allocator)

  // Wire up subquery evaluation for ScalarSubquery, Exists, InSubquery.
  // Subqueries reference logical plans internally — we need a PlanExecutor for those.
  evaluator.subqueryEvaluator = Some(subPlan =>
    val logicalExecutor = PlanExecutor(catalog, allocator, cteContext)
    logicalExecutor.execute(subPlan)
  )

  def execute(plan: ProtoPhysicalPlan): Batch = plan match
    // ── Leaf nodes ──
    case ProtoPhysicalPlan.TableScan(name, alias, _, _) =>
      val base = cteContext.getOrElse(
        name,
        catalog
          .getTable(name)
          .getOrElse(throw ExecutionException(s"Table not found: $name"))
      )
      // When the table is scanned under an explicit alias, qualify its columns (`c.col`) so that
      // downstream references can disambiguate same-named columns across a join — e.g. `n.name` vs
      // another table's `name`. Column lookup (ExprEvaluator.resolveColumn) matches a bare reference
      // against the unqualified suffix, so unqualified references still resolve. Unaliased scans keep
      // their bare names (the common single-table case, and what most callers expect).
      alias match
        case Some(a) => qualifyFields(base, a)
        case None    => base

    case ProtoPhysicalPlan.PhysicalValues(rows, schema) =>
      Batch.fromValues(rows, schema, allocator)

    // ── Unary operators ──
    case ProtoPhysicalPlan.PhysicalProject(projectList, child) =>
      ProjectOp.execute(execute(child), projectList, evaluator, allocator)

    case ProtoPhysicalPlan.PhysicalFilter(condition, child) =>
      FilterOp.execute(execute(child), condition, evaluator, allocator)

    case ProtoPhysicalPlan.PhysicalSort(order, child) =>
      SortOp.execute(execute(child), order, evaluator, allocator)

    case ProtoPhysicalPlan.PhysicalLimit(n, child) =>
      limitBatch(execute(child), n)

    case ProtoPhysicalPlan.PhysicalDistinct(child) =>
      distinctBatch(execute(child))

    // ── Join strategies ──
    case ProtoPhysicalPlan.HashJoin(
          left,
          right,
          joinType,
          leftKeys,
          rightKeys,
          condition,
          buildSide
        ) =>
      HashJoinOp.execute(
        execute(left),
        execute(right),
        joinType,
        leftKeys,
        rightKeys,
        condition,
        buildSide,
        evaluator,
        allocator
      )

    case ProtoPhysicalPlan.BroadcastHashJoin(
          left,
          right,
          joinType,
          leftKeys,
          rightKeys,
          condition,
          buildSide
        ) =>
      // In local executor, broadcast is the same as regular hash join
      HashJoinOp.execute(
        execute(left),
        execute(right),
        joinType,
        leftKeys,
        rightKeys,
        condition,
        buildSide,
        evaluator,
        allocator
      )

    case ProtoPhysicalPlan.SortMergeJoin(left, right, joinType, leftKeys, rightKeys, condition) =>
      SortMergeJoinOp.execute(
        execute(left),
        execute(right),
        joinType,
        leftKeys,
        rightKeys,
        condition,
        evaluator,
        allocator
      )

    case ProtoPhysicalPlan.NestedLoopJoin(left, right, joinType, condition) =>
      JoinOp.execute(execute(left), execute(right), joinType, condition, evaluator, allocator)

    // ── Aggregate strategies ──
    case ProtoPhysicalPlan.HashAggregate(groupingExprs, aggregateExprs, child) =>
      AggregateOp.execute(execute(child), groupingExprs, aggregateExprs, evaluator, allocator)

    case ProtoPhysicalPlan.SortAggregate(groupingExprs, aggregateExprs, child) =>
      // AggregateOp is already hash-based, same result
      AggregateOp.execute(execute(child), groupingExprs, aggregateExprs, evaluator, allocator)

    // ── Data redistribution ──
    case ProtoPhysicalPlan.Exchange(_, child) =>
      execute(child) // No-op in local executor

    // ── Pass-through operators ──
    case ProtoPhysicalPlan.PhysicalWindow(wExprs, partition, order, child) =>
      WindowOp.execute(execute(child), wExprs, partition, order, evaluator, allocator)

    case ProtoPhysicalPlan.PhysicalUnion(children, byName, _) =>
      SetOps.union(children.map(execute), byName, allocator)

    case ProtoPhysicalPlan.PhysicalIntersect(left, right, isAll) =>
      SetOps.intersect(execute(left), execute(right), isAll, allocator)

    case ProtoPhysicalPlan.PhysicalExcept(left, right, isAll) =>
      SetOps.except(execute(left), execute(right), isAll, allocator)

    case ProtoPhysicalPlan.PhysicalWith(ctes, recursive, child) =>
      if recursive then throw ExecutionException("Recursive CTEs not yet implemented")
      for (name, ctePlan) <- ctes do cteContext(name) = execute(ctePlan)
      execute(child)

    case ProtoPhysicalPlan.PhysicalPivot(groups, pivotCol, pivotVals, aggs, child) =>
      AdvancedOps.pivot(execute(child), groups, pivotCol, pivotVals, aggs, evaluator, allocator)

    case ProtoPhysicalPlan.PhysicalUnpivot(valCol, varCol, cols, nulls, child) =>
      AdvancedOps.unpivot(execute(child), valCol, varCol, cols, nulls, evaluator, allocator)

    case ProtoPhysicalPlan.PhysicalLateralJoin(left, lateral, cond) =>
      AdvancedOps.lateralJoin(execute(left), () => execute(lateral), cond, evaluator, allocator)

    case ProtoPhysicalPlan.PhysicalGenerate(gen, output, outer, child) =>
      AdvancedOps.generate(execute(child), gen, output, outer, evaluator, allocator)

    // ── ML operators ──
    case ProtoPhysicalPlan.PhysicalPredict(model, inputMapping, child) =>
      PredictOp.execute(execute(child), model, inputMapping, evaluator, allocator)

    case ProtoPhysicalPlan.PhysicalFit(model, inputMapping, labelMapping, trainConfig, child) =>
      FitOp.execute(
        execute(child),
        model,
        inputMapping,
        labelMapping,
        trainConfig,
        evaluator,
        allocator
      )

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

  /** Return a view of `batch` whose schema field names are prefixed with `qualifier.` (unless already
    * qualified). Shares the underlying Arrow root — column access is by ordinal, so only the
    * `ProtoSchema` names change. Lets `resolveColumn` disambiguate same-named columns after a join. */
  private def qualifyFields(batch: Batch, qualifier: String): Batch =
    val qualified = ProtoSchema(batch.schema.fields.map { f =>
      if f.name.contains('.') then f else f.copy(name = s"$qualifier.${f.name}")
    })
    Batch.fromRoot(batch.root, qualified)
