package protocatalyst.optimizer

import protocatalyst.optimizer.rules._
import protocatalyst.plan.ProtoLogicalPlan

/** The main compile-time optimizer for ProtoCatalyst.
  *
  * This optimizer implements a subset of Spark Catalyst's optimization rules that can be applied at
  * compile time (i.e., rules that don't require runtime statistics or table metadata).
  *
  * The optimization is organized into batches, following Catalyst's pattern:
  *   - Each batch contains related rules
  *   - Batches are executed in order
  *   - Within a batch, rules are applied using the batch's strategy (Once or FixedPoint)
  *
  * Currently implemented rules:
  *
  * Phase 1 - Core Rules:
  *   - ConstantFolding: Evaluates constant expressions at compile time
  *   - BooleanSimplification: Simplifies boolean logic using identities
  *   - CollapseProject: Merges adjacent Project operators
  *   - CombineFilters: Merges adjacent Filter operators
  *
  * Phase 2 - Predicate Optimization:
  *   - NullPropagation: Propagates null knowledge through expressions
  *   - NullDownPropagation: Propagates null checks through expressions
  *   - ConstantPropagation: Replaces columns with known constant values
  *   - FoldablePropagation: Propagates foldable expressions through the plan
  *   - OptimizeIn: Simplifies IN clause expressions
  *   - SimplifyConditionals: Simplifies CASE/WHEN and IF expressions
  *   - LikeSimplification: Converts LIKE patterns to simpler operations
  *   - ReplaceNullWithFalseInPredicate: Replaces NULL with FALSE in predicates
  *
  * Phase 3 - Operator Pushdown:
  *   - PushDownPredicates: Moves filter conditions closer to data sources
  *   - ColumnPruning: Removes unused columns from intermediate operators
  *   - LimitPushdown: Pushes LIMIT operations closer to data sources
  *   - PushFoldableIntoBranches: Pushes constants into IF/CASE branches
  *
  * Phase 4 - Cleanup Rules:
  *   - EliminateDistinct: Removes redundant DISTINCT operations
  *   - EliminateSorts: Removes unnecessary Sort operations
  *   - EliminateLimits: Merges nested LIMIT operations
  *   - EliminateOffsets: Simplifies OFFSET operations
  *   - CombineUnions: Flattens nested Union operators
  *   - RemoveNoopOperators: Removes operators that have no effect
  *   - RemoveRedundantAliases: Removes aliases that don't change names
  *   - RemoveRedundantSorts: Removes duplicate Sort operations
  *   - PruneFilters: Removes always-true/false filters
  *   - PropagateEmptyRelation: Propagates empty relations through the plan
  *
  * Phase 5 - Subquery Optimization:
  *   - InlineCTE: Inlines CTEs that are referenced only once
  *   - RewriteCorrelatedSubquery: Decorrelates correlated subqueries to joins
  *
  * Phase 6 - Advanced Rules:
  *   - ReorderAssociativeOperator: Reorders associative ops to fold constants together
  *   - SimplifyCasts: Removes unnecessary cast operations
  *   - SimplifyBinaryComparison: Simplifies self-comparisons (x = x → TRUE)
  *   - SimplifyCaseConversionExpressions: Simplifies nested UPPER/LOWER calls
  *   - CombineConcats: Flattens nested CONCAT operations
  *   - EliminateOuterJoin: Converts outer joins to inner joins when safe
  *   - UnwrapCastInBinaryComparison: Removes casts in comparisons when possible
  *
  * Phase 7 - Aggregate Optimization:
  *   - RemoveLiteralFromGroupExpressions: Removes constants from GROUP BY
  *   - RemoveRepetitionFromGroupExpressions: Removes duplicate GROUP BY keys
  *   - ReplaceDistinctWithAggregate: Converts DISTINCT to GROUP BY
  *
  * Phase 8 - Set Operation Optimization:
  *   - ReplaceExceptWithFilter: Converts EXCEPT to left-anti join
  *   - ReplaceIntersectWithSemiJoin: Converts INTERSECT to left-semi join
  *
  * Phase 9 - Filter Inference:
  *   - InferFiltersFromConstraints: Derives additional filters from join constraints
  *
  * @see
  *   docs/compiler/OPTIMIZER_PLAN.md for the full optimization plan
  */
object Optimizer extends RuleExecutor:

  /** Core operator optimization rules.
    *
    * These rules form the main optimization pass and are run until the plan stops changing or the
    * iteration limit is reached.
    */
  private val operatorOptimizationRules: Seq[Rule] = Seq(
    // ─── Operator Pushdown ───
    PushDownPredicates,
    LimitPushdown,
    ColumnPruning,
    PushFoldableIntoBranches,

    // ─── Operator Combine ───
    CollapseProject,
    CombineFilters,
    CombineUnions,

    // ─── Operator Elimination ───
    EliminateDistinct,
    EliminateLimits,
    EliminateOffsets,
    EliminateOuterJoin,
    RemoveNoopOperators,
    RemoveRedundantAliases,
    RemoveRedundantSorts,
    PruneFilters,

    // ─── Null & Constant Propagation ───
    NullPropagation,
    NullDownPropagation,
    ConstantPropagation,
    FoldablePropagation,
    ReplaceNullWithFalseInPredicate,

    // ─── Constant Folding & Simplification ───
    ReorderAssociativeOperator,
    ConstantFolding,
    SimplifyCasts,
    SimplifyBinaryComparison,
    SimplifyCaseConversionExpressions,
    BooleanSimplification,
    SimplifyConditionals,
    UnwrapCastInBinaryComparison,

    // ─── Pattern & List Optimization ───
    OptimizeIn,
    LikeSimplification,
    CombineConcats
  )

  override def batches: Seq[Batch] = Seq(
    // ═══════════════════════════════════════════════════════════════
    // BATCH 1: Inline CTE (Once)
    // Inline CTEs that are referenced only once before main optimization
    // ═══════════════════════════════════════════════════════════════
    Batch(
      "Inline CTE",
      Strategy.Once,
      Seq(InlineCTE)
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 2: Rewrite Subqueries (Once)
    // Decorrelate correlated subqueries to enable join optimizations
    // ═══════════════════════════════════════════════════════════════
    Batch(
      "Rewrite Subqueries",
      Strategy.Once,
      Seq(RewriteCorrelatedSubquery)
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 3: Replace Operators (Once)
    // Convert set operations to join patterns
    // ═══════════════════════════════════════════════════════════════
    Batch(
      "Replace Operators",
      Strategy.Once,
      Seq(
        ReplaceDistinctWithAggregate,
        ReplaceExceptWithFilter,
        ReplaceIntersectWithSemiJoin
      )
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 4: Aggregate (FixedPoint)
    // Optimize GROUP BY expressions
    // ═══════════════════════════════════════════════════════════════
    Batch(
      "Aggregate",
      Strategy.FixedPoint(10),
      Seq(
        RemoveLiteralFromGroupExpressions,
        RemoveRepetitionFromGroupExpressions
      )
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 5: Propagate Empty Relation (FixedPoint)
    // Propagate empty relations through the plan
    // ═══════════════════════════════════════════════════════════════
    Batch(
      "Propagate Empty Relation",
      Strategy.FixedPoint(10),
      Seq(PropagateEmptyRelation)
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 6: Operator Optimization - First Pass (FixedPoint)
    // Run optimization rules until the plan stabilizes
    // ═══════════════════════════════════════════════════════════════
    Batch(
      "Operator Optimization",
      Strategy.FixedPoint(100),
      operatorOptimizationRules
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 7: Infer Filters (Once)
    // Derive additional filters from join constraints
    // ═══════════════════════════════════════════════════════════════
    Batch(
      "Infer Filters",
      Strategy.Once,
      Seq(InferFiltersFromConstraints)
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 8: Operator Optimization - Second Pass (FixedPoint)
    // Run again after filter inference
    // ═══════════════════════════════════════════════════════════════
    Batch(
      "Operator Optimization after Inferring Filters",
      Strategy.FixedPoint(100),
      operatorOptimizationRules
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 9: Eliminate Sorts (Once)
    // Remove unnecessary sort operations after main optimization
    // ═══════════════════════════════════════════════════════════════
    Batch(
      "Eliminate Sorts",
      Strategy.Once,
      Seq(EliminateSorts, RemoveRedundantSorts)
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 10: Final Cleanup (Once)
    // One final pass to clean up any remaining opportunities
    // ═══════════════════════════════════════════════════════════════
    Batch(
      "Final Cleanup",
      Strategy.Once,
      Seq(CollapseProject, RemoveNoopOperators, ConstantFolding, PropagateEmptyRelation)
    )
  )

  /** Optimize a logical plan.
    *
    * @param plan
    *   The unoptimized plan
    * @return
    *   The optimized plan
    */
  def optimize(plan: ProtoLogicalPlan): ProtoLogicalPlan = execute(plan)
