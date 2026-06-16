# DSL Reference & Spark Comparison

Comprehensive inventory of ProtoCatalyst's DSL, IR, optimizer, and macro coverage, with Spark Catalyst comparison.

## Overview

ProtoCatalyst provides two query authoring paths:
1. **DSL path** (`quote { }`) - Type-safe Scala DSL compiled at compile time
2. **SQL path** (`SqlMacro.compileOptimized`) - SQL strings parsed and optimized at compile time

Both produce a `ProtoLogicalPlan` that is optimized and embedded as a bytecode constant.

---

## 1. IR Types

### ProtoLogicalPlan (20 variants)

| Plan Node | Description | Spark Equivalent | DSL Builder | `quote` Macro | Optimizer Rules |
|-----------|-------------|-----------------|-------------|---------------|-----------------|
| `RelationRef` | Table reference with schema contract | `UnresolvedRelation` / `LocalRelation` | `Table[A]("name")` | Yes | - |
| `Values` | Literal row data with schema | `LocalRelation` | - | - | `PropagateEmptyRelation` |
| `Project` | Column projection (SELECT) | `Project` | `.select(...)` | Yes | `CollapseProject`, `ColumnPruning` |
| `Filter` | Row filter (WHERE) | `Filter` | `.filter(...)` / `.where(...)` | Yes | `PushDownPredicates`, `CombineFilters`, `PruneFilters` |
| `Sort` | ORDER BY with direction/null ordering | `Sort` | `.orderBy(...)` | Yes | `EliminateSorts`, `RemoveRedundantSorts` |
| `Limit` | LIMIT N | `GlobalLimit` / `LocalLimit` | `.limit(n)` | Yes | `EliminateLimits`, `LimitPushdown` |
| `Distinct` | Remove duplicates | `Distinct` | `.distinct` | Yes | `EliminateDistinct`, `ReplaceDistinctWithAggregate` |
| `SubqueryAlias` | AS alias | `SubqueryAlias` | `.as("alias")` | Yes | `RemoveRedundantAliases` |
| `Window` | Window function computation | `Window` | `.over(Window.partitionBy(...))` | Yes | - |
| `Union` | UNION (with byName, allowMissing) | `Union` | `.union(other)` | Yes | `CombineUnions` |
| `Intersect` | INTERSECT / INTERSECT ALL | `Intersect` | `.intersect(other)` | Yes | `ReplaceIntersectWithSemiJoin` |
| `Except` | EXCEPT / EXCEPT ALL | `Except` | `.except(other)` | Yes | `ReplaceExceptWithFilter` |
| `Aggregate` | GROUP BY with aggregates | `Aggregate` | `.groupBy(...).agg(...)` | Yes | `RemoveLiteralFromGroupExprs`, `RemoveRepetitionFromGroupExprs` |
| `Join` | All join types (Inner/Left/Right/Full/Semi/Anti/Cross) | `Join` | `.join(...)`, `.leftJoin(...)`, `.rightJoin(...)`, `.crossJoin(...)` | Yes | `EliminateOuterJoin`, `InferFiltersFromConstraints` |
| `With` | Common Table Expressions (CTE) | `WithCTE` | - | - | `InlineCTE` |
| `LateralJoin` | Correlated subquery join | `LateralJoin` | - | - | `RewriteCorrelatedSubquery` |
| `Generate` | LATERAL VIEW / generator | `Generate` | - | - | - |
| `Pivot` | PIVOT operation | `Pivot` | - | - | - |
| `Unpivot` | UNPIVOT operation | `Unpivot` | - | - | - |
| `ResolvedHint` | Optimizer hints (Broadcast, etc.) | `ResolvedHint` | `.broadcast`, `.hint(...)`, `.coalesce(n)` | Yes | - |

**Spark has additional plans not in ProtoCatalyst:**
- `Offset`, `Tail`, `LimitAll` - Pagination variants
- `Expand` - For CUBE/ROLLUP/GROUPING SETS
- `Sample` - Random sampling
- `Repartition`, `RepartitionByExpression`, `RebalancePartitions` - Shuffle control
- `Deduplicate`, `DeduplicateWithinWatermark` - Streaming dedup
- `WindowGroupLimit` - Optimized window top-N
- `InsertIntoDir`, `InsertIntoStatement` - DML
- `AsOfJoin`, `DomainJoin` - Specialized joins
- `Transpose` - Column-to-row transformation
- `Range` - Sequence generation

### ProtoExpr (100 variants)

#### Leaf Nodes

| Expression | Description | Spark Equivalent | DSL Builder | `quote` Macro |
|-----------|-------------|-----------------|-------------|---------------|
| `Literal` | Constant value (Int/Long/Double/String/Boolean/Null) | `Literal` | `Expr.lit(v)` | Yes |
| `ColumnRef` | Column reference (name, qualifier, type, nullable) | `AttributeReference` | `_.fieldName`, `$"col"` | Yes |
| `BoundRef` | Index-based reference for compiled plans | `BoundReference` | - | - |

#### Comparison (6)

| Expression | DSL | `quote` Macro | Optimizer Rules |
|-----------|-----|---------------|-----------------|
| `Eq` | `===` | Yes | `SimplifyBinaryComparison` |
| `NotEq` | `=!=` | Yes | `SimplifyBinaryComparison` |
| `Lt` | `<` | Yes | `SimplifyBinaryComparison` |
| `LtEq` | `<=` | Yes | `SimplifyBinaryComparison` |
| `Gt` | `>` | Yes | `SimplifyBinaryComparison` |
| `GtEq` | `>=` | Yes | `SimplifyBinaryComparison` |

#### Logical (3)

| Expression | DSL | `quote` Macro | Optimizer Rules |
|-----------|-----|---------------|-----------------|
| `And` | `&&` | Yes | `BooleanSimplification` |
| `Or` | `\|\|` | Yes | `BooleanSimplification` |
| `Not` | `!` | Yes | `BooleanSimplification` |

#### Null Handling (4)

| Expression | DSL | `quote` Macro | Optimizer Rules |
|-----------|-----|---------------|-----------------|
| `IsNull` | `.isNull` | Yes | `NullPropagation` |
| `IsNotNull` | `.isNotNull` | Yes | `NullPropagation` |
| `Coalesce` | `functions.coalesce(...)` | - | `NullPropagation` |
| `NullIf` | - | - | `NullPropagation` |

#### Arithmetic (4)

| Expression | DSL | `quote` Macro | Optimizer Rules |
|-----------|-----|---------------|-----------------|
| `Add` | `+` | Yes | `ConstantFolding`, `ReorderAssociativeOperator` |
| `Subtract` | `-` | Yes | `ConstantFolding` |
| `Multiply` | `*` | Yes | `ConstantFolding`, `ReorderAssociativeOperator` |
| `Divide` | `/` | Yes | `ConstantFolding` |

#### Math Functions (12)

| Expression | DSL | `quote` Macro | Optimizer Rules |
|-----------|-----|---------------|-----------------|
| `Abs`, `Ceil`, `Floor`, `Round`, `Truncate`, `Sqrt`, `Cbrt`, `Pow`, `Pmod`, `Sign`, `Log`, `Exp` | - | - | `ConstantFolding` |

**Spark has many more:** `Sin`, `Cos`, `Tan`, `Asin`, `Acos`, `Atan`, `Atan2`, `Hex`, `Unhex`, `Logarithm`, `Factorial`, `Rand`, `Randn`, `Conv`, `Bin`, `Remainder` (mod), `IntegralDivide`, bitwise ops (`&`, `|`, `^`, `~`), `ShiftLeft`, `ShiftRight`, `ShiftRightUnsigned`, `Hypot`, `Degrees`, `Radians`, etc.

#### String Functions (11)

| Expression | DSL | `quote` Macro | Optimizer Rules |
|-----------|-----|---------------|-----------------|
| `Concat` | `++` | Yes | `CombineConcats` |
| `Upper` | `.upper` | Yes | `SimplifyCaseConversionExpressions` |
| `Lower` | `.lower` | Yes | `SimplifyCaseConversionExpressions` |
| `Substring` | `functions.substring(...)` | - | - |
| `Trim` | - | - | - |
| `Length` | - | - | - |
| `Replace` | - | - | - |
| `StringLocate` | - | - | - |
| `Lpad`, `Rpad` | - | - | - |
| `StringSplit` | - | - | - |
| `Reverse`, `StringRepeat` | - | - | - |

**Spark has many more:** `Like`, `RLike`, `Contains`, `StartsWith`, `EndsWith`, `Ascii`, `Base64`, `UnBase64`, `Decode`, `Encode`, `FormatNumber`, `FormatString`, `InitCap`, `StringInstr`, `Overlay`, `StringTranslate`, `StringTrim`, `StringTrimLeft`, `StringTrimRight`, `RegExpReplace`, `RegExpExtract`, `StringToMap`, `SoundEx`, `Levenshtein`, etc.

#### Aggregate Functions (5)

| Expression | DSL | `quote` Macro | Optimizer Rules |
|-----------|-----|---------------|-----------------|
| `Count` | `functions.count` / `functions.countDistinct` | Yes | - |
| `Sum` | `functions.sum(expr)` | Yes | - |
| `Avg` | `functions.avg(expr)` | Yes | - |
| `Min` | `functions.min(expr)` | Yes | - |
| `Max` | `functions.max(expr)` | Yes | - |

**Spark has many more:** `First`, `Last`, `AnyValue`, `CountIf`, `Product`, `Corr`, `Covariance`, `Variance`, `StdDev`, `Skewness`, `Kurtosis`, `ApproxCountDistinct`, `ApproximatePercentile`, `Percentile`, `Mode`, `MaxBy`, `MinBy`, `HistogramNumeric`, `BloomFilterAggregate`, `BitAndAgg`, `BitOrAgg`, `BitXorAgg`, `CollectList`, `CollectSet`, `PivotFirst`, `LinearRegression` aggregates.

#### Control Flow (3)

| Expression | DSL | `quote` Macro | Optimizer Rules |
|-----------|-----|---------------|-----------------|
| `CaseWhen` | `functions.when(...).otherwise(...)` | - | `SimplifyConditionals`, `PushFoldableIntoBranches` |
| `If` | - | - | `SimplifyConditionals` |
| `In` | - | - | `OptimizeIn` |

#### Pattern Matching (1)

| Expression | DSL | `quote` Macro | Optimizer Rules |
|-----------|-----|---------------|-----------------|
| `Like` | - | - | `LikeSimplification` |

#### Cast & Alias (2)

| Expression | DSL | `quote` Macro | Optimizer Rules |
|-----------|-----|---------------|-----------------|
| `Cast` | `functions.cast[T](expr)` | - | `SimplifyCasts`, `UnwrapCastInBinaryComparison` |
| `Alias` | `.as("name")` | Yes | `RemoveRedundantAliases` |

#### Subquery Expressions (3)

| Expression | DSL | `quote` Macro | Optimizer Rules |
|-----------|-----|---------------|-----------------|
| `ScalarSubquery` | `scalarSubquery(query)` | Yes | `RewriteCorrelatedSubquery` |
| `Exists` | `exists(query)` / `notExists(query)` | Yes | `RewriteCorrelatedSubquery` |
| `InSubquery` | `expr.in(query)` / `expr.notIn(query)` | Yes | `RewriteCorrelatedSubquery` |

#### Window Functions (9 + WindowExpr wrapper)

| Expression | DSL | `quote` Macro | Optimizer Rules |
|-----------|-----|---------------|-----------------|
| `RowNumber`, `Rank`, `DenseRank`, `Ntile` | `rowNumber()`, `rank()`, `denseRank()`, `ntile(n)` | Yes | - |
| `Lead`, `Lag` | `lead(expr, offset)`, `lag(expr, offset)` | Yes | - |
| `FirstValue`, `LastValue`, `NthValue` | `firstValue(expr)`, `lastValue(expr)` | Yes | - |
| `WindowExpr` (wrapper with partition/order/frame) | `.over(Window.partitionBy(...).orderBy(...))` | Yes | - |

**Spark has many more window capabilities:** `WindowSpecDefinition`, `WindowFrame` (row-based, range-based), `CumeDist`, `PercentRank`, `WindowGroupLimit`, specialized frame boundaries.

#### Date/Time Functions (14)

| Expression | DSL | `quote` Macro |
|-----------|-----|---------------|
| `CurrentDate`, `CurrentTimestamp`, `DateAdd`, `DateSub`, `DateDiff`, `Extract`, `DateTrunc`, `ToDate`, `ToTimestamp`, `Year`, `Month`, `DayOfMonth`, `Hour`, `Minute`, `Second` | - | - |

**Spark has many more:** `FromUnixTime`, `UnixTimestamp`, `DateFormatClass`, `NextDay`, `LastDay`, `AddMonths`, `MonthsBetween`, `Quarter`, `DayOfYear`, `DayOfWeek`, `WeekDay`, `WeekOfYear`, `MakeDate`, `MakeTimestamp`, `MakeInterval`, `ParseToDate`, `ParseToTimestamp`, `TruncDate`, `TruncTimestamp`, `FromUTCTimestamp`, `ToUTCTimestamp`, `TimeWindow`, `SessionWindow`, etc.

#### Generator Functions (4)

| Expression | DSL | `quote` Macro |
|-----------|-----|---------------|
| `Explode`, `PosExplode`, `Inline`, `Stack` | - | - |

**Spark has more:** `JsonTuple`, `ReplicateRows`, `CollectionGenerator`.

#### Grouping & Opaque (2)

| Expression | DSL | `quote` Macro |
|-----------|-----|---------------|
| `Grouping` | - | - |
| `OpaqueCall` (UDF) | - | - |

**Spark has:** `GroupingID`, `Cube`, `Rollup`, `GroupingSets` for advanced grouping, plus UDF infrastructure (`ScalaUDF`, `PythonUDF`, `JavaUDF`).

---

## 2. DSL Surface API

### Table[A]

Entry point for queries. Created with `Table[A]("tableName")` (requires `ProtoEncoder[A]`).

```scala
case class User(name: String, age: Int, salary: Double) derives ProtoEncoder
val users = Table[User]("users")
```

**Methods:** All `Query[A]` methods plus:
- `col[T](name: String): Column[A, T]` - Get typed column by name
- `columns: Vector[Column[A, ?]]` - All columns
- `row: FieldSelector[A]` - Lambda-style field selector
- `toQuery: Query[A]` - Convert to query

### Query[A]

The main query builder. All operations return a new `Query`.

| Category | Method | Signature |
|----------|--------|-----------|
| **Filter** | `filter` | `(Expr[Boolean]): Query[A]` |
| | `filter` (lambda) | `(FieldSelector[A] => Expr[Boolean]): Query[A]` |
| | `where` | Alias for `filter` |
| **Select** | `select` (lambda) | `(FieldSelector[A] => Expr[B]): Query[B]` |
| | `select` (varargs) | `(Expr[B]*): Query[B]` |
| | `select` (tuple2) | `(FieldSelector[A] => (Expr[B1], Expr[B2])): Query[(B1, B2)]` |
| | `select` (tuple3) | `(FieldSelector[A] => (Expr[B1], Expr[B2], Expr[B3])): Query[(B1, B2, B3)]` |
| | `select` (tuple4) | Same pattern for 4 columns |
| **Aggregate** | `groupBy` (lambda) | `(FieldSelector[A] => Expr[K]): GroupedQuery[A, K]` |
| | `groupBy` (varargs) | `(Expr[K]*): GroupedQuery[A, K]` |
| **Sort** | `orderBy` | `(SortExpr*): Query[A]` |
| **Limit** | `limit` | `(Int): Query[A]` |
| **Distinct** | `distinct` | `: Query[A]` |
| **Join** | `join` | `(Query[B]): JoinBuilder[A, B]` (inner) |
| | `leftJoin` | `(Query[B]): JoinBuilder[A, B]` |
| | `rightJoin` | `(Query[B]): JoinBuilder[A, B]` |
| | `crossJoin` | `(Query[B]): Query[(A, B)]` |
| **Set Ops** | `union` | `(Query[A]): Query[A]` |
| | `intersect` / `intersectAll` | `(Query[A]): Query[A]` |
| | `except` / `exceptAll` | `(Query[A]): Query[A]` |
| **Alias** | `as` | `(String): Query[A]` |
| **Compile** | `compile` | `: CompiledQuery[A]` |

### JoinBuilder[A, B]

Created by `.join()`, `.leftJoin()`, `.rightJoin()`.

- `on(condition: Expr[Boolean]): Query[(A, B)]`
- `on(f: (FieldSelector[A], FieldSelector[B]) => Expr[Boolean]): Query[(A, B)]`

### GroupedQuery[A, K]

Created by `.groupBy(...)`.

- `agg[B](aggExprs: Expr[B]*): Query[B]`

### Expr[A] Extensions

| Category | Method | Signature | Bound |
|----------|--------|-----------|-------|
| **Comparison** | `>`, `>=`, `<`, `<=` | `(Expr[A]): Expr[Boolean]` | `A <: Int\|Long\|Double\|Float\|String` |
| | `>`, `>=`, `<`, `<=` | `(A): Expr[Boolean]` | literal overloads |
| | `===`, `=!=` | `(Expr[A]): Expr[Boolean]` | typed equality |
| | `===`, `=!=` | `(A): Expr[Boolean]` | literal overloads |
| **Arithmetic** | `+`, `-`, `*`, `/` | `(Expr[A]): Expr[A]` | `A <: Int\|Long\|Double\|Float` |
| | `+`, `-`, `*`, `/` | `(A): Expr[A]` | literal overloads |
| **Boolean** | `&&`, `\|\|` | `(Expr[Boolean]): Expr[Boolean]` | `Expr[Boolean]` only |
| | `unary_!` | `: Expr[Boolean]` | `Expr[Boolean]` only |
| **String** | `++` | `(Expr[String]): Expr[String]` | `Expr[String]` only |
| | `upper`, `lower` | `: Expr[String]` | `Expr[String]` only |
| **Null** | `isNull`, `isNotNull` | `: Expr[Boolean]` | Any `Expr[A]` |
| **Alias** | `as` | `(String): Expr[A]` | Any `Expr[A]` |
| **Sort** | `asc`, `desc` | `: SortExpr` | Any `Expr[A]` |
| | `ascNullsFirst`, `descNullsLast` | `: SortExpr` | Any `Expr[A]` |

### functions Object

| Function | Signature | Notes |
|----------|-----------|-------|
| `count` | `: Expr[Long]` | Count all rows |
| `count(expr)` | `Expr[A] => Expr[Long]` | Count non-null |
| `countDistinct(expr)` | `Expr[A] => Expr[Long]` | Count distinct |
| `sum(expr)` | `Expr[A] => Expr[A]` | Requires `Numeric[A]` |
| `sumDouble(expr)` | `Expr[A] => Expr[Double]` | Sum as Double |
| `avg(expr)` | `Expr[A] => Expr[Double]` | Average |
| `min(expr)` | `Expr[A] => Expr[A]` | Minimum |
| `max(expr)` | `Expr[A] => Expr[A]` | Maximum |
| `when(cond, then)` | `=> WhenBuilder[A]` | CASE WHEN builder |
| `coalesce(exprs*)` | `Expr[A]* => Expr[A]` | First non-null |
| `concat(exprs*)` | `Expr[String]* => Expr[String]` | String concat |
| `substring(str, pos, len)` | `=> Expr[String]` | Substring |
| `upper(str)` | `Expr[String] => Expr[String]` | To uppercase |
| `lower(str)` | `Expr[String] => Expr[String]` | To lowercase |
| `cast[T](expr)` | `Expr[?] => Expr[T]` | Type cast |

### FieldSelector[A] (Type-safe field access)

Uses `transparent inline` macro via `Dynamic` trait. Field access is resolved at compile time with correct types:

```scala
users.filter(_.age > 18)      // _.age : Column[User, Int]
users.select(_.name)           // _.name : Column[User, String]
users.select(u => (u.name, u.age))  // tuple select
```

Invalid field names produce compile errors with available field names listed.

### SparkSyntax (Untyped compatibility)

```scala
import SparkSyntax.$
val query = users.filter($"age" > 18)   // Spark-style $"col" syntax
val col = SparkSyntax.col("name")       // col("name") function
```

`UntypedColumn` supports comparison operators with raw union-typed literals (`Int | Long | Double | String`).

---

## 3. Spark Catalyst DSL Comparison

Spark's internal test DSL (`org.apache.spark.sql.catalyst.dsl.package`) provides:

### Expression DSL (`dsl.expressions`)

| Feature | Spark | ProtoCatalyst | Notes |
|---------|-------|---------------|-------|
| `$"col"` column ref | Yes (`UnresolvedAttribute`) | Yes (`UntypedColumn`) | Both |
| Symbol-to-column (`'col`) | Yes | No | Deprecated in Spark too |
| Arithmetic (`+`,`-`,`*`,`/`,`%`) | Yes | Yes (no `%`) | Spark adds `div`, `%` |
| Bitwise (`&`,`\|`,`^`,`~`) | Yes | No | |
| Comparison (`>`,`>=`,`<`,`<=`,`===`,`<=>`,`=!=`) | Yes | Yes (no `<=>`) | Spark has null-safe `<=>` |
| Boolean (`&&`,`\|\|`,`!`) | Yes | Yes | |
| `in(...)` | Yes (including subquery) | No | |
| `like`, `rlike`, `contains`, `startsWith`, `endsWith` | Yes | No | |
| `isNull`, `isNotNull` | Yes | Yes | |
| `cast(DataType)` | Yes | `functions.cast[T]` | Different API |
| `asc`, `desc` | Yes | Yes | Spark adds `asc_nullsLast`, `desc_nullsFirst` |
| `as(alias)` | Yes | Yes | |
| `substr` / `substring` | Yes | `functions.substring` | |
| `getItem`, `getField` | Yes | No | Array/struct access |
| Implicit literal conversions | Yes (20+ types) | Yes (5 types) | Spark converts Date, Decimal, etc. |

### Aggregate DSL

| Function | Spark | ProtoCatalyst |
|----------|-------|---------------|
| `sum`, `count`, `avg`, `min`, `max` | Yes | Yes |
| `first`, `last` | Yes | No |
| `countDistinct` | Yes | Yes |
| `sumDistinct` | Yes | No |
| `approxCountDistinct` | Yes | No |
| `bitAnd`, `bitOr`, `bitXor` | Yes | No |
| `collectList`, `collectSet` | Yes | No |

### Plan DSL (`dsl.plans`)

| Operation | Spark | ProtoCatalyst |
|-----------|-------|---------------|
| `select(exprs*)` | Yes | Yes |
| `where(condition)` / `filter(...)` | Yes | Yes (both) |
| `limit(expr)` | Yes | Yes |
| `offset(expr)` | Yes | No |
| `orderBy(sortExprs*)` / `sortBy(...)` | Yes | Yes (`orderBy`) |
| `groupBy(exprs*)(aggExprs*)` | Yes | Yes (different syntax) |
| `having(exprs*)(aggExprs*)(condition)` | Yes | No |
| `join(plan, type, condition)` | Yes (single method) | Yes (typed builders) |
| `lateralJoin(plan, type, condition)` | Yes | No |
| `union(plan)` | Yes | Yes |
| `except(plan, isAll)` | Yes | Yes |
| `intersect(plan, isAll)` | Yes | Yes |
| `distinct` | No (separate node) | Yes |
| `as(alias)` / `subquery(alias)` | Yes | Yes |
| `window(exprs, partition, order)` | Yes | No |
| `generate(generator, ...)` | Yes | No |
| `hint(name, params*)` | Yes | No |
| `sample(...)` | Yes | No |
| `repartition(n)` / `coalesce(n)` | Yes | No |
| `deduplicate(cols*)` | Yes | No |
| `analyze` (run analyzer) | Yes | No (compile-time) |

### Key Design Differences

| Aspect | Spark | ProtoCatalyst |
|--------|-------|---------------|
| **Type safety** | Untyped (`Expression`) | Typed (`Expr[A]`) with compile-time checking |
| **When runs** | Runtime (per query execution) | Compile time (macro expansion) |
| **Column access** | `$"col"` returns `UnresolvedAttribute` | `_.field` returns `Column[A, T]` via transparent inline macro |
| **Optimization** | Runtime optimizer (100+ rules) | Compile-time optimizer (41 rules, same architecture) |
| **Output** | `LogicalPlan` tree | `CompiledArtifact` with protobuf serialization |
| **Schema** | Runtime schema resolution | Compile-time schema derivation via `ProtoEncoder` |
| **Error timing** | Runtime `AnalysisException` | Compile error |

---

## 4. Optimizer Rules (41 rules, 10 batches)

### Batch Structure

| # | Batch | Strategy | Rules |
|---|-------|----------|-------|
| 1 | Inline CTE | Once | `InlineCTE` |
| 2 | Rewrite Subqueries | Once | `RewriteCorrelatedSubquery` |
| 3 | Replace Operators | Once | `ReplaceDistinctWithAggregate`, `ReplaceExceptWithFilter`, `ReplaceIntersectWithSemiJoin` |
| 4 | Aggregate | FixedPoint(10) | `RemoveLiteralFromGroupExpressions`, `RemoveRepetitionFromGroupExpressions` |
| 5 | Propagate Empty Relation | FixedPoint(10) | `PropagateEmptyRelation` |
| 6 | Operator Optimization (1st) | FixedPoint(100) | 31 rules (see below) |
| 7 | Infer Filters | Once | `InferFiltersFromConstraints` |
| 8 | Operator Optimization (2nd) | FixedPoint(100) | Same 31 rules |
| 9 | Eliminate Sorts | Once | `EliminateSorts`, `RemoveRedundantSorts` |
| 10 | Final Cleanup | Once | `CollapseProject`, `RemoveNoopOperators`, `ConstantFolding`, `PropagateEmptyRelation` |

### Operator Optimization Rules (31)

**Pushdown:** `PushDownPredicates`, `LimitPushdown`, `ColumnPruning`, `PushFoldableIntoBranches`

**Combine:** `CollapseProject`, `CombineFilters`, `CombineUnions`

**Eliminate:** `EliminateDistinct`, `EliminateLimits`, `EliminateOffsets`, `EliminateOuterJoin`, `RemoveNoopOperators`, `RemoveRedundantAliases`, `RemoveRedundantSorts`, `PruneFilters`

**Propagation:** `NullPropagation`, `NullDownPropagation`, `ConstantPropagation`, `FoldablePropagation`, `ReplaceNullWithFalseInPredicate`

**Simplification:** `ReorderAssociativeOperator`, `ConstantFolding`, `SimplifyCasts`, `SimplifyBinaryComparison`, `SimplifyCaseConversionExpressions`, `BooleanSimplification`, `SimplifyConditionals`, `UnwrapCastInBinaryComparison`

**Pattern:** `OptimizeIn`, `LikeSimplification`, `CombineConcats`

### Spark Optimizer Comparison

Spark's optimizer has **~75 unique rules** across **~20 batches**. ProtoCatalyst implements 41 rules (55% coverage), focusing on rules that don't need runtime information.

**Rules Spark has that ProtoCatalyst doesn't:**
- Cost-based join reordering (`CostBasedJoinReorder`)
- Runtime filter injection (`InjectRuntimeFilter`)
- Window optimizations (`LimitPushDownThroughWindow`, `EliminateWindowPartitions`, `TransposeWindow`, `InferWindowGroupLimit`)
- Object serialization (`EliminateSerialization`, `EliminateMapObjects`, `CombineTypedFilters`, `ObjectSerializerPruning`)
- Partition pruning (`DynamicPartitionPruning`)
- Decimal optimizations (`DecimalAggregates`)
- Physical optimizations (`NormalizeFloatingNumbers`, `InsertMapSortExpression`)
- Distinct aggregate rewrite (`RewriteDistinctAggregates`)

---

## 5. `quote { }` Macro Coverage

### Plan Operations Handled

| Operation | Pattern Matched | Status |
|-----------|----------------|--------|
| `Table[A]("name")` | Table creation | Yes |
| `.toQuery` | Table conversion | Yes |
| `.filter(pred)` / `.where(pred)` | Filtering | Yes |
| `.select(exprs)` | Projection (single, multi, tuple-lambda) | Yes |
| `.orderBy(sorts)` | Sorting with `.asc`/`.desc`/`.ascNullsFirst`/`.descNullsLast` | Yes |
| `.limit(n)` | Limit | Yes |
| `.distinct` | Distinct | Yes |
| `.as("alias")` | Subquery alias | Yes |
| `.union(other)` | Union | Yes |
| `.intersect(other)` / `.intersectAll(other)` | Intersect | Yes |
| `.except(other)` / `.exceptAll(other)` | Except | Yes |
| `.crossJoin(other)` | Cross join | Yes |
| `.join(other).on(cond)` | Inner join | Yes |
| `.leftJoin(other).on(cond)` | Left join | Yes |
| `.rightJoin(other).on(cond)` | Right join | Yes |
| `.groupBy(keys).agg(aggExprs)` | Aggregate | Yes |
| `.in(subquery)` / `.notIn(subquery)` | IN subquery | Yes |
| `exists(subquery)` / `notExists(subquery)` | EXISTS subquery | Yes |
| `scalarSubquery(query)` | Scalar subquery | Yes |
| Correlated subqueries | Inner query referencing outer columns | Yes |
| `.over(Window.partitionBy(...))` | Window functions | Yes |
| `rowNumber()`, `rank()`, `lead()`, etc. | Window function constructors | Yes |
| `.broadcast`, `.coalesce(n)`, `.repartition(n)` | Optimizer hints | Yes |
| `.hint(PlanHint.Broadcast(...))` | Custom hints | Yes |

### Expression Operations Handled

| Category | Operations | Status |
|----------|-----------|--------|
| Literals | Int, Long, Double, Float, String, Boolean | Yes |
| Column refs | `_.field`, `typedColumn`, `selectDynamic`, `Column.apply` | Yes |
| Comparison | `>`, `>=`, `<`, `<=`, `===`, `=!=` | Yes |
| Arithmetic | `+`, `-`, `*`, `/`, `unary_-` | Yes |
| Boolean | `&&`, `\|\|`, `!` | Yes |
| String | `upper`, `lower`, `++` | Yes |
| Null | `isNull`, `isNotNull` | Yes |
| Alias | `.as("name")` | Yes |
| Aggregates | `count`, `countDistinct`, `sum`, `avg`, `min`, `max` | Yes |
| Subqueries | `in`, `notIn`, `exists`, `notExists`, `scalarSubquery` | Yes |
| Window | `rowNumber`, `rank`, `denseRank`, `ntile`, `lead`, `lag`, `firstValue`, `lastValue` | Yes |

### NOT Handled in `quote` (requires runtime DSL or SQL path)

- Math functions (abs, ceil, floor, etc.)
- Advanced string functions (substring, trim, replace, etc.)
- Date/time functions
- CASE WHEN / IF expressions
- LIKE expressions
- Cast operations
- Generator functions (explode, etc.)
- CTE (With)
- PIVOT / UNPIVOT

---

## 6. Test Coverage Summary

| Test Suite | Tests | Covers |
|-----------|-------|--------|
| `DslSuite` | ~50 | Runtime DSL: table, column, expr, filter, select, orderBy, limit, distinct, join, groupBy, set ops, subqueries, window functions, hints, serialization |
| `QuoteMacroSuite` | ~67 | Compile-time macro: all plan operations, expression types, joins, aggregates, subqueries (incl. correlated), window functions, hints, optimization, content hashing |
| Optimizer rule suites | ~40+ | Individual rule tests in `core/src/test/scala/protocatalyst/optimizer/` |
| `ParityTestSuite` | ~136 | Spark ↔ ProtoCatalyst plan parity across all expression and plan types |
| `ProtobufArtifactCodecSuite` | ~52 | Protobuf serialization roundtrip for all IR types |
| `ProtobufDecoderSuite` | ~41 | Protobuf → Spark LogicalPlan decoding |

### Notable Test Gaps

- Math functions, date/time functions, CASE WHEN, LIKE, Cast — handled by SQL parser but not `quote { }` macro
- Generator functions (explode, etc.), CTE, PIVOT/UNPIVOT — IR and SQL support exists but no DSL builders
