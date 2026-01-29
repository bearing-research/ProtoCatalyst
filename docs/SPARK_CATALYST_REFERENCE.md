# Spark Catalyst Reference for ProtoCatalyst

This document summarizes how Spark's Catalyst optimizer works, focusing on the aspects relevant to ProtoCatalyst's compile-time query compilation. Understanding this architecture helps identify which work can safely move from runtime to compile time.

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [RuleExecutor Pattern](#2-ruleexecutor-pattern)
3. [Analyzer: Name Resolution & Type Checking](#3-analyzer-name-resolution--type-checking)
4. [Optimizer: Rule-Based Transformations](#4-optimizer-rule-based-transformations)
5. [LogicalPlan Structure](#5-logicalplan-structure)
6. [Expression System](#6-expression-system)
7. [Always-Safe vs CBO-Dependent Work](#7-always-safe-vs-cbo-dependent-work)
8. [ProtoCatalyst Implications](#8-protocatalyst-implications)

---

## 1. Architecture Overview

Spark SQL query processing follows this pipeline:

```
SQL String → Parser → Unresolved LogicalPlan → Analyzer → Resolved LogicalPlan
                                                              ↓
                                                          Optimizer
                                                              ↓
                                                     Optimized LogicalPlan
                                                              ↓
                                                         Planner
                                                              ↓
                                                        PhysicalPlan
                                                              ↓
                                                        Execution
```

**Key Files in Spark:**
- `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/analysis/Analyzer.scala`
- `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/optimizer/Optimizer.scala`
- `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/plans/logical/LogicalPlan.scala`
- `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/expressions/Expression.scala`

---

## 2. RuleExecutor Pattern

Both Analyzer and Optimizer extend `RuleExecutor[LogicalPlan]`, which provides the core transformation framework.

### 2.1 Batch Execution Model

```scala
abstract class RuleExecutor[TreeType <: TreeNode[_]] {
  case class Batch(name: String, strategy: Strategy, rules: Rule[TreeType]*)

  // Execution strategies
  case object Once extends Strategy { val maxIterations = 1 }
  case class FixedPoint(maxIterations: Int) extends Strategy

  def execute(plan: TreeType): TreeType = {
    var curPlan = plan
    batches.foreach { batch =>
      var continue = true
      var iteration = 0
      while (continue && iteration < batch.strategy.maxIterations) {
        curPlan = batch.rules.foldLeft(curPlan) { (plan, rule) =>
          rule(plan)
        }
        if (curPlan.fastEquals(lastPlan)) continue = false
        iteration += 1
      }
    }
    curPlan
  }
}
```

**Key Concepts:**
- **Batches** execute serially, rules within a batch execute serially
- **Once**: Run exactly once, must be idempotent
- **FixedPoint**: Iterate until convergence or max iterations reached
- Convergence detected via `fastEquals()` (structural equality)

### 2.2 Rule Definition

```scala
abstract class Rule[TreeType <: TreeNode[_]] {
  def apply(plan: TreeType): TreeType
}
```

Rules typically use pattern matching on plan nodes and return transformed plans.

---

## 3. Analyzer: Name Resolution & Type Checking

The Analyzer transforms an unresolved logical plan (with placeholder names) into a resolved plan (with concrete attributes and types).

### 3.1 Analyzer Structure

```scala
class Analyzer(catalogManager: CatalogManager)
  extends RuleExecutor[LogicalPlan]
  with CheckAnalysis
```

**Location:** `Analyzer.scala:290-344`

### 3.2 Resolution Batch (Main Analysis)

The core resolution batch runs with `FixedPoint` strategy and includes:

| Rule | Purpose |
|------|---------|
| `ResolveCatalogs` | Resolve catalog names, namespaces |
| `ResolveRelations` | Convert `UnresolvedRelation` → concrete table relations |
| `ResolveReferences` | Resolve `UnresolvedAttribute` → `AttributeReference` |
| `ResolveFunctions` | Resolve function names to implementations |
| `ResolveAliases` | Handle column aliases |
| `ResolveSubquery` | Resolve subquery expressions |
| `TypeCoercion` rules | Implicit type conversions |

### 3.3 Resolution Process

**Before Analysis (Unresolved):**
```scala
UnresolvedRelation(Seq("users"))
  .select(
    UnresolvedAttribute("name"),
    UnresolvedAttribute("age")
  )
```

**After Analysis (Resolved):**
```scala
Project(
  Seq(
    AttributeReference("name", StringType, nullable=true)(exprId=1),
    AttributeReference("age", IntType, nullable=false)(exprId=2)
  ),
  Relation("users", schema=StructType(...))
)
```

### 3.4 Key Resolution Rules

#### ResolveRelations (`Analyzer.scala:973-1092`)
- Looks up table in catalog via `CatalogManager`
- Resolves views by expanding their stored plan
- Creates concrete `Relation` nodes with schema

#### ResolveReferences (`Analyzer.scala:1351-1550`)
- Matches column names against available output attributes
- Expands star (`*`) expressions
- Handles qualified names (`table.column`)
- Creates `AttributeReference` with unique `ExprId`

#### ResolveFunctions (`Analyzer.scala:2036-2159`)
- Checks built-in function registry
- Falls back to catalog function lookup
- Validates argument types and counts

### 3.5 Type Coercion

Type coercion rules (`TypeCoercion.scala:47-72`) handle implicit type conversions:

- `InConversion` - IN clause type alignment
- `PromoteStrings` - String to numeric promotion
- `DecimalPrecision` - Decimal type widening
- `ImplicitTypeCasts` - General implicit casts
- `CaseWhenCoercion` - CASE expression type unification

---

## 4. Optimizer: Rule-Based Transformations

The Optimizer applies semantic-preserving transformations to improve query performance.

### 4.1 Optimizer Structure

```scala
abstract class Optimizer(catalogManager: CatalogManager)
  extends RuleExecutor[LogicalPlan]
```

**Location:** `Optimizer.scala:51-52`

### 4.2 Optimization Batches

The optimizer has 26+ batches organized in phases:

| Phase | Batches | Purpose |
|-------|---------|---------|
| **Analysis Finalization** | Finish Analysis | Correctness rules |
| **Structural** | Union, CTE inlining | Normalize structure |
| **Operator Optimization** | Main optimization | Pushdown, pruning, folding |
| **CBO** | Join Reorder | Cost-based join ordering |
| **Cleanup** | Eliminate sorts, Subquery rewrite | Final cleanup |

### 4.3 Key Optimization Rules

#### Expression-Level Optimizations (`expressions.scala`)

**ConstantFolding** (Line 49-118):
```scala
// Before
Add(Literal(1), Literal(2))

// After
Literal(3)
```

**ConstantPropagation** (Line 134-234):
```scala
// Before: WHERE i = 5 AND j = i + 3
// After:  WHERE i = 5 AND j = 8
```

**BooleanSimplification** (Line 359+):
```scala
// Before: AND(true, x) → x
// Before: OR(false, x) → x
// Before: NOT(NOT(x)) → x
```

#### Plan-Level Optimizations (`Optimizer.scala`)

**PushDownPredicates** (Line 2038-2045):
```scala
// Before
Filter(cond, Project(cols, Scan(table)))

// After
Project(cols, Filter(cond, Scan(table)))
```

**ColumnPruning**:
```scala
// Before: SELECT a FROM (SELECT a, b, c FROM t)
// After:  SELECT a FROM (SELECT a FROM t)
```

**CollapseProject**:
```scala
// Before: Project(a, Project(a, b, c, child))
// After:  Project(a, child)
```

---

## 5. LogicalPlan Structure

### 5.1 Base Class Hierarchy

```scala
abstract class LogicalPlan
  extends QueryPlan[LogicalPlan]
  with AnalysisHelper
  with LogicalPlanStats

trait LeafNode extends LogicalPlan with LeafLike[LogicalPlan]
trait UnaryNode extends LogicalPlan with UnaryLike[LogicalPlan]
trait BinaryNode extends LogicalPlan with BinaryLike[LogicalPlan]
```

**Location:** `LogicalPlan.scala:37-43`

### 5.2 Key Node Types

| Category | Node | Purpose |
|----------|------|---------|
| **Leaf** | `LocalRelation` | In-memory data |
| **Leaf** | `Relation` | Table reference |
| **Unary** | `Project` | Column selection |
| **Unary** | `Filter` | Row filtering |
| **Unary** | `Aggregate` | GROUP BY + aggregates |
| **Unary** | `Sort` | ORDER BY |
| **Unary** | `Limit` | LIMIT clause |
| **Binary** | `Join` | All join types |
| **N-ary** | `Union` | UNION ALL |

### 5.3 Resolution Tracking

```scala
// In LogicalPlan
lazy val resolved: Boolean = expressions.forall(_.resolved) && childrenResolved

// Unresolved marker trait
trait UnresolvedNode extends LogicalPlan {
  override def output: Seq[Attribute] = Nil
  override lazy val resolved: Boolean = false
}
```

### 5.4 Schema Propagation

Each plan node defines its output schema:

```scala
// Project: output is the projected columns
case class Project(projectList: Seq[NamedExpression], child: LogicalPlan) {
  override def output: Seq[Attribute] = projectList.map(_.toAttribute)
}

// Filter: output is child's output (no schema change)
case class Filter(condition: Expression, child: LogicalPlan) {
  override def output: Seq[Attribute] = child.output
}

// Join: output depends on join type
case class Join(...) {
  override def output: Seq[Attribute] =
    Join.computeOutput(joinType, left.output, right.output)
}
```

### 5.5 Constraint Propagation

Plans propagate constraints for optimization:

```scala
// Filter adds its condition as a constraint
case class Filter(condition: Expression, child: LogicalPlan) {
  override protected lazy val validConstraints: ExpressionSet = {
    val predicates = splitConjunctivePredicates(condition)
    child.constraints.union(ExpressionSet(predicates))
  }
}
```

---

## 6. Expression System

### 6.1 Expression Base Class

```scala
abstract class Expression extends TreeNode[Expression] {
  def dataType: DataType        // Result type
  def nullable: Boolean         // Can produce null?
  def foldable: Boolean         // Statically evaluable?
  def deterministic: Boolean    // Same result for same input?

  def eval(input: InternalRow): Any                      // Interpret
  def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode  // Generate code
}
```

**Location:** `Expression.scala:91-407`

### 6.2 Key Expression Types

#### Literal (`literals.scala:414-616`)
```scala
case class Literal(value: Any, dataType: DataType) extends LeafExpression {
  override def foldable: Boolean = true
  override def nullable: Boolean = value == null
  override def eval(input: InternalRow): Any = value
}
```

#### AttributeReference (`namedExpressions.scala:285-400`)
```scala
case class AttributeReference(
    name: String,
    dataType: DataType,
    nullable: Boolean = true,
    metadata: Metadata = Metadata.empty)(
    val exprId: ExprId,           // Globally unique ID
    val qualifier: Seq[String])   // Table qualifier
  extends Attribute
```

**ExprId** is crucial for tracking attribute identity through transformations.

#### BinaryExpression (`Expression.scala:707-796`)
```scala
abstract class BinaryExpression extends Expression {
  def left: Expression
  def right: Expression

  override def foldable: Boolean = left.foldable && right.foldable
  override def nullable: Boolean = left.nullable || right.nullable

  override def eval(input: InternalRow): Any = {
    val v1 = left.eval(input)
    if (v1 == null) null
    else {
      val v2 = right.eval(input)
      if (v2 == null) null
      else nullSafeEval(v1, v2)
    }
  }
}
```

### 6.3 Nullability Rules

Different expression types have different nullability semantics:

| Expression Type | Nullability Rule |
|-----------------|------------------|
| `Literal` | `value == null` |
| `AttributeReference` | Stored in metadata |
| `UnaryExpression` | `child.nullable` |
| `BinaryExpression` | `left.nullable || right.nullable` |
| `Coalesce` | `children.forall(_.nullable)` |
| `If` | `trueValue.nullable || falseValue.nullable` |

---

## 7. Always-Safe vs CBO-Dependent Work

This section identifies which Catalyst work can safely move to compile time.

### 7.1 Always-Safe Analysis Work

These operations are deterministic and don't require runtime statistics:

| Operation | Why Safe |
|-----------|----------|
| **Name Resolution** | Schema known at compile time |
| **Type Checking** | Types derived from schema |
| **Type Coercion** | Deterministic rules |
| **Alias Resolution** | Static substitution |
| **Star Expansion** | Schema-based expansion |
| **Subquery Analysis** | Recursive analysis |

### 7.2 Always-Safe Optimizations

Non-excludable rules in Spark (`Optimizer.scala:290-307`):

```scala
// These rules are ALWAYS applied and cannot be disabled
FinishAnalysis
RewriteDistinctAggregates
ReplaceDeduplicateWithAggregate
ReplaceIntersectWithSemiJoin
ReplaceExceptWithFilter
ReplaceExceptWithAntiJoin
RewriteExceptAll
RewriteIntersectAll
ReplaceDistinctWithAggregate
PullupCorrelatedPredicates
RewriteCorrelatedScalarSubquery
RewritePredicateSubquery
NormalizeFloatingNumbers
ReplaceUpdateFieldsExpression
RewriteLateralSubquery
OptimizeSubqueries
```

Additional always-safe optimizations:

| Category | Rules | Why Safe |
|----------|-------|----------|
| **Constant Folding** | `ConstantFolding`, `ConstantPropagation` | Deterministic evaluation |
| **Boolean Simplification** | `BooleanSimplification`, `SimplifyConditionals` | Algebraic identities |
| **Structural** | `CollapseProject`, `CollapseWindow` | Plan equivalence |
| **Pushdown** | `PushDownPredicates`, `ColumnPruning` | Preserves semantics |
| **Elimination** | `EliminateLimits`, `EliminateOffsets` | Removes no-ops |

### 7.3 CBO-Dependent Work (DO NOT Move to Compile Time)

These require runtime statistics or adaptive decisions:

| Operation | Why Runtime-Only |
|-----------|------------------|
| **Join Reordering** | Requires row count statistics |
| **Scan Pushdown** | Data source capabilities vary |
| **Partition Pruning** | Runtime filter values |
| **Adaptive Query Execution** | Observes runtime data |
| **Broadcast Decision** | Table size statistics |

**Configuration Check in Spark:**
```scala
// CostBasedJoinReorder only runs if:
if (!conf.cboEnabled || !conf.joinReorderEnabled) {
  return plan  // Skip
}
if (items.size <= 2) {
  return plan  // Skip for simple joins
}
if (!items.forall(_.stats.rowCount.isDefined)) {
  return plan  // Skip without statistics
}
```

### 7.4 Canonicalization (Safe for Stability)

Canonicalization creates deterministic plan representations:

```scala
// Flatten nested AND/OR
And(a, And(b, c)) → And(a, b, c)

// Sort children for determinism
And(c, b, a) → And(a, b, c)

// Fold constants
Add(Literal(1), Literal(2)) → Literal(3)

// Simplify booleans
And(true, x) → x
Not(Not(x)) → x
```

---

## 8. ProtoCatalyst Implications

### 8.1 What ProtoCatalyst Can Do at Compile Time

Based on Spark's architecture, ProtoCatalyst can safely perform:

#### Analysis Phase
1. **Schema Resolution**: Given schema contracts, resolve column references
2. **Type Inference**: Derive expression types from resolved attributes
3. **Type Coercion**: Apply deterministic type conversion rules
4. **Alias Tracking**: Maintain attribute identity through aliases

#### Optimization Phase
1. **Constant Folding**: Evaluate compile-time constant expressions
2. **Boolean Simplification**: Apply algebraic identities
3. **Plan Canonicalization**: Normalize plan structure for stable fingerprints
4. **Structural Simplification**: Collapse adjacent operators

### 8.2 What Must Remain at Runtime

1. **CBO Decisions**: Join ordering, broadcast hints
2. **AQE**: Adaptive execution based on runtime data
3. **Actual Execution**: Data processing

### 8.3 Key Abstractions to Mirror

| Spark Concept | ProtoCatalyst Equivalent | Purpose |
|---------------|--------------------------|---------|
| `Expression` | `ProtoExpr` | Expression IR |
| `LogicalPlan` | `ProtoLogicalPlan` | Plan IR |
| `DataType` | `ProtoType` | Type representation |
| `Attribute` | Field in `ProtoSchema` | Schema element |
| `ExprId` | Not needed (compile-time) | Attribute identity |
| `Analyzer` | Compile-time type checker | Name resolution |
| `Optimizer` rules | `Canonicalizer` | Safe transformations |

### 8.4 Schema Contract Pattern

Instead of runtime catalog lookup, ProtoCatalyst uses compile-time schema contracts:

```scala
// Spark (runtime)
catalog.getTable("users")  // → Schema

// ProtoCatalyst (compile-time)
SchemaContract(
  name = "users",
  fingerprint = SchemaFingerprint(hash),
  fields = Vector(
    ProtoStructField("name", ProtoType.StringType, nullable = true),
    ProtoStructField("age", ProtoType.IntType, nullable = false)
  )
)
```

At runtime, ProtoCatalyst validates that actual schemas match the contracts.

### 8.5 Compilation Strategy

```
                    COMPILE TIME                           RUNTIME
                    ────────────                           ───────
                         │                                    │
SQL/DSL ──→ Parse ──→ Analyze ──→ Canonicalize ──→ [Artifact] ──→ Validate ──→ Execute
                         │              │                          Contracts
                         │              │
                  Schema Contracts   Stable Fingerprint
```

This approach:
- Moves parsing, analysis, and safe optimizations to compile time
- Produces stable artifacts with schema contracts
- Validates contracts at runtime before execution
- Lets Spark handle CBO and execution

---

## References

- Spark source: `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/`
- Key files examined:
  - `analysis/Analyzer.scala` (4,167 lines)
  - `optimizer/Optimizer.scala` (2,854 lines)
  - `plans/logical/LogicalPlan.scala` (506 lines)
  - `expressions/Expression.scala` (1,400+ lines)
  - `rules/RuleExecutor.scala` (326 lines)
