# ADR-003: ProtoCatalyst IR vs Substrait IR — Detailed Technical Comparison

## Status

**Informational** — February 2026

Companion to [ADR-002](ADR-002-independent-ir.md), which records the decision to stay independent. This document provides a detailed structural comparison of the two IRs, informed by our Phase 11c implementation experience integrating with `io.substrait:core:0.78.0`.

---

## 1. Design Philosophy

| | ProtoCatalyst | Substrait |
|---|---|---|
| **Role** | Compiler IR — owns the entire pipeline from DSL/SQL to execution | Exchange format — "engine A produces, engine B consumes" |
| **Extensibility** | Explicit enum variants — adding a new expression means adding a new case to `ProtoExpr` | Extension-based — functions are URI references to YAML definitions; the core schema is intentionally minimal |
| **Optimization** | Built-in (56 compile-time rules) | Consumer's responsibility |
| **Validation** | Compile-time (macros, schema contracts) | Runtime only |
| **Scope** | SQL + ML (ComputeGraph, TensorExpr, autograd) | SQL/relational only |
| **Serialization** | Own protobuf schema (canonical) | Substrait protobuf schema (industry standard) |

### Why this matters

ProtoCatalyst's explicit-variant design makes the compiler IR self-describing: pattern matching is exhaustive, and every expression's semantics are visible in the type signature. Substrait's extension-based design makes the exchange format open-ended: any engine can define new functions without changing the core schema, at the cost of requiring a function registry to interpret plans.

These are not competing designs — they serve different goals. A compiler needs closed, exhaustive variants for safe transformation. An exchange format needs open, extensible variants for interop.

---

## 2. Type System

### Primitive Types

| Type | ProtoCatalyst | Substrait |
|------|--------------|-----------|
| Boolean | `BooleanType` | `Bool` |
| 8-bit integer | `ByteType` | `I8` |
| 16-bit integer | `ShortType` | `I16` |
| 32-bit integer | `IntegerType` | `I32` |
| 64-bit integer | `LongType` | `I64` |
| 32-bit float | `FloatType` | `FP32` |
| 64-bit float | `DoubleType` | `FP64` |
| Decimal | `DecimalType(precision, scale)` | `Decimal(precision, scale)` |
| String | `StringType` | `Str` |
| Binary | `BinaryType` | `Binary` |
| Date | `DateType` | `Date` |
| Timestamp | `TimestampType` | `Timestamp` (no TZ), `TimestampTZ` (with TZ) |
| Timestamp (no TZ) | `TimestampNTZType` | `Timestamp` |
| Time | `TimeType(precision)` | `Time` |
| Fixed-length string | `CharType(length)` | `FixedChar(length)` |
| Variable-length string | `VarcharType(length)` | `VarChar(length)` |
| Fixed-length binary | — | `FixedBinary(length)` |
| UUID | — | `UUID` |

### Interval Types

| Type | ProtoCatalyst | Substrait |
|------|--------------|-----------|
| Year-month | `YearMonthIntervalType` | `IntervalYear` |
| Day-time | `DayTimeIntervalType` | `IntervalDay` |
| Calendar interval | `CalendarIntervalType` (months, days, microseconds) | `IntervalCompound` (0.78.0+) |

### Complex Types

| Type | ProtoCatalyst | Substrait |
|------|--------------|-----------|
| Array | `ArrayType(elementType, containsNull)` | `ListType(elementType)` |
| Map | `MapType(keyType, valueType, valueContainsNull)` | `Map(keyType, valueType)` |
| Struct | `StructType(fields: Vector[ProtoStructField])` | `Struct(fields)` |

### Types Unique to ProtoCatalyst (No Substrait Equivalent)

| Type | Purpose |
|------|---------|
| `SumType(discriminatorField, variants)` | Discriminated unions / sealed trait ADTs |
| `VariantType` | Semi-structured data (JSON-like) |
| `UDTType(udtClassName, sqlType)` | User-defined types with SQL backing |
| `UnresolvedType(hint)` | Placeholder for error reporting |
| `NullType` | Null-only column (java.lang.Void) |

### Types Unique to Substrait (No ProtoCatalyst Equivalent)

| Type | Purpose |
|------|---------|
| `UUID` | UUID values |
| `FixedBinary(length)` | Fixed-length binary |
| `PrecisionTimestamp(precision)` | Configurable-precision timestamp |
| `PrecisionTimestampTZ(precision)` | Configurable-precision timestamp with TZ |
| `UserDefined(urn, params)` | URN-referenced custom types |

### Nullability Model

This is a fundamental design difference:

- **ProtoCatalyst**: Nullability lives on the field, not the type. `ProtoStructField(name, dataType, nullable, metadata)`. A `StringType` is neither nullable nor non-nullable — that's a property of the field that contains it.
- **Substrait**: Nullability lives on the type itself. `TypeCreator.NULLABLE.STRING` vs `TypeCreator.REQUIRED.STRING`. Every type instance carries its own nullability.

**Implication**: Converting between these models requires restructuring — a ProtoCatalyst `ArrayType(StringType, containsNull = true)` becomes a Substrait `ListType(NULLABLE.STRING)`. The nullability moves from the container's flag to the element type itself.

---

## 3. Expression System

### ProtoCatalyst: 93 Explicit Enum Variants

```scala
enum ProtoExpr:
  case Add(left: ProtoExpr, right: ProtoExpr)
  case Upper(child: ProtoExpr)
  case Count(child: ProtoExpr, distinct: Boolean)
  case CaseWhen(branches: Vector[(ProtoExpr, ProtoExpr)], elseValue: Option[ProtoExpr])
  // ... 89 more
```

Every expression is an explicit case class. The compiler enforces exhaustive pattern matching — if you add a new expression, every match statement must handle it.

### Substrait: Extension-Based Function References

```
ScalarFunction {
  function_reference: 42,          // numeric anchor
  arguments: [left, right],        // positional
  output_type: I32                  // declared return type
}
```

Every function (add, upper, concat, abs, ...) is represented as the same `ScalarFunction` message with a numeric reference that maps to a YAML definition via the extension system.

### Comparison

| Aspect | ProtoCatalyst | Substrait |
|--------|--------------|-----------|
| Adding a new function | New enum variant → recompile | New YAML entry → no schema change |
| Pattern matching | Exhaustive, compiler-enforced | Numeric dispatch, runtime resolution |
| Self-describing | Yes — function semantics in type signature | No — must look up function definition in YAML |
| Function options (overflow, rounding) | Encoded in variant semantics | Explicit `FunctionOption` map per invocation |
| Type safety | Compile-time — `Add(left, right)` can only have 2 children | Runtime — must validate argument count against YAML |

### Expression Categories Side-by-Side

**Literals**:
- ProtoCatalyst: `Literal(value: LiteralValue)` where `LiteralValue` is a 15-variant enum
- Substrait: 25+ literal types as inner classes (`I32Literal`, `StrLiteral`, `ListLiteral`, `MapLiteral`, etc.)

**Column References**:
- ProtoCatalyst: `ColumnRef(name, qualifier, resolvedType, nullable)` — name-based
- Substrait: `FieldReference` with `ReferenceSegment` — ordinal-based, supports nested dereferencing (`struct.field.subfield`, `list[3]`, `map["key"]`)

**Aggregates**:
- ProtoCatalyst: Explicit variants (`Count`, `Sum`, `Avg`, `Min`, `Max`) with parameters like `distinct: Boolean`
- Substrait: `AggregateFunction` with `AggregationPhase` (INITIAL_TO_INTERMEDIATE, etc.) for distributed aggregation decomposition

**Window Functions**:
- ProtoCatalyst: Explicit variants (`RowNumber`, `Rank`, `Lead`, `Lag`, etc.) wrapped in `WindowExpr` with partition/order/frame
- Substrait: `WindowFunctionInvocation` — same extension-based model as scalar functions, with window specification attached

**Control Flow**:
- ProtoCatalyst: `CaseWhen(branches, elseValue)`, `If(predicate, true, false)`, `In(value, list)`
- Substrait: `IfThen(clauses)`, `Switch(clauses)`, `InPredicate`, `SetPredicate`

### Expressions Unique to ProtoCatalyst

| Expression | Purpose |
|-----------|---------|
| `Alias(child, name)` | Named expression (Substrait handles at plan level) |
| `ScalarSubquery(plan)` | Embed full logical plan as scalar value |
| `Exists(plan)` | EXISTS subquery |
| `InSubquery(value, plan)` | IN subquery |
| `WindowExpr(fn, partition, order, frame)` | Window specification wrapper |
| `Explode(child)` | Array/map explosion |
| `PosExplode(child)` | Explode with position |
| `Inline(child)` | Array-of-struct explosion |
| `Stack(numRows, children)` | Row generator |
| `Grouping(columns)` | GROUPING SETS support |
| `OpaqueCall(name, args, returnType, deterministic)` | UDF escape hatch |

### Expressions Unique to Substrait

| Expression | Purpose |
|-----------|---------|
| `NestedStruct` / `NestedList` | Anonymous struct/list construction |
| `MultiOrList` / `MultiOrListRecord` | Multi-column OR predicates |
| `Switch` | Switch/case expression (ProtoCatalyst uses `CaseWhen`) |
| `SetPredicate` | Set membership predicates |

---

## 4. Plan Nodes (Relations)

### ProtoCatalyst: 22 Logical Plan Nodes

```scala
enum ProtoLogicalPlan:
  case Project(projectList, child)
  case Filter(condition, child)
  case Join(left, right, joinType, condition)
  case Aggregate(groupingExprs, aggregateExprs, child)
  // ... 18 more
```

### Substrait: ~20 Core Relation Types + Physical Variants

```
Filter { input: Rel, condition: Expression }
Project { input: Rel, expressions: [Expression] }
Join { left: Rel, right: Rel, condition: Expression, type: JoinType }
Aggregate { input: Rel, groupings: [Grouping], measures: [Measure] }
// ...
```

### Plan Node Comparison

| Operation | ProtoCatalyst | Substrait |
|-----------|--------------|-----------|
| Table scan | `RelationRef(name, alias, schemaContract)` | `NamedScan(names)`, `VirtualTableScan`, `LocalFiles` |
| Inline values | `Values(rows, schema)` | `VirtualTableScan(values)` |
| Projection | `Project(projectList, child)` | `Project(expressions, input)` |
| Filter | `Filter(condition, child)` | `Filter(condition, input)` |
| Join | `Join(left, right, joinType, condition)` | `Join(left, right, type, condition, postJoinFilter)` |
| Aggregate | `Aggregate(groupingExprs, aggregateExprs, child)` | `Aggregate(groupings, measures, input)` |
| Sort | `Sort(order, child)` | `Sort(sortFields, input)` |
| Limit | `Limit(limit, child)` | `Fetch(offset, count, input)` |
| Distinct | `Distinct(child)` | — (use Aggregate with all columns) |
| Union | `Union(children, byName, allowMissingColumns)` | `Set(inputs, SetOp.UNION_ALL)` |
| Intersect | `Intersect(left, right, isAll)` | `Set(inputs, SetOp.INTERSECTION_PRIMARY)` |
| Except | `Except(left, right, isAll)` | `Set(inputs, SetOp.MINUS_PRIMARY)` |
| CTE | `With(cteRelations, recursive, child)` | — (no equivalent) |
| Subquery alias | `SubqueryAlias(alias, child)` | — (no equivalent, naming is implicit) |
| Window | `Window(windowExprs, partitionSpec, orderSpec, child)` | `ConsistentPartitionWindow` |
| Cross join | `Join(..., Cross, ...)` | `Cross(left, right)` |
| Hints | `ResolvedHint(hints, child)` | `AdvancedExtension` (opaque metadata) |

### Plan Nodes Unique to ProtoCatalyst

| Node | Purpose |
|------|---------|
| `Pivot` | SQL PIVOT operation |
| `Unpivot` | SQL UNPIVOT operation |
| `Generate` | LATERAL VIEW (explode, inline, stack) |
| `LateralJoin` | Correlated subquery in FROM clause |
| `With` | CTEs (recursive and non-recursive) |
| `Distinct` | Explicit distinct as a plan node |
| `SubqueryAlias` | Named subquery alias |
| `ResolvedHint` | Cross-backend optimizer hints |
| `Predict` | ML inference in query plan |
| `Fit` | ML training in query plan |

### Plan Nodes Unique to Substrait

| Node | Purpose |
|------|---------|
| `LocalFiles` | File-based scan with format (Parquet, CSV, ORC) |
| `Fetch` | Offset + limit (ProtoCatalyst has `Limit` without offset) |
| `Cross` | Explicit cross join (ProtoCatalyst uses `Join(..., Cross)`) |
| `HashJoin` / `MergeJoin` / `NestedLoopJoin` | Physical join strategies |
| `BroadcastExchange` / `RoundRobinExchange` / etc. | Physical data exchange |
| `NamedWrite` | Write to named table |
| `NamedDdl` / `ExtensionDdl` | DDL operations |
| `ExtensionLeaf` / `ExtensionSingle` / `ExtensionMulti` | Custom relation extension points |

Note: ProtoCatalyst has its own physical plan layer (`ProtoPhysicalPlan` with 25 variants including `HashJoin`, `SortMergeJoin`, `BroadcastHashJoin`, `Exchange`, etc.) but these are separate from the logical plan IR.

---

## 5. Function System

This is the most significant architectural difference.

### ProtoCatalyst: Closed, Explicit Functions

```scala
// Every function is a first-class enum variant
ProtoExpr.Add(left, right)     // addition
ProtoExpr.Upper(child)          // uppercase
ProtoExpr.Count(child, true)    // COUNT(DISTINCT x)
```

Benefits:
- Exhaustive pattern matching in optimizer rules
- Function semantics visible in type signature
- No runtime function lookup
- Compile-time type checking of arguments

Cost:
- Adding a new function requires adding an enum variant and recompiling
- Fixed function set — no user-defined functions without `OpaqueCall`

### Substrait: Open, Extension-Based Functions

```yaml
# functions_arithmetic.yaml
- name: add
  impls:
    - args:
        - value: i32
        - value: i32
      return: i32
      options:
        overflow: [SILENT, SATURATE, ERROR]
    - args:
        - value: i64
        - value: i64
      return: i64
    # ... 14 more variants
```

```
ScalarFunction(
  function_reference = 42,  // resolved to "add:i32_i32"
  arguments = [left, right],
  output_type = I32,
  options = { overflow: ERROR }
)
```

Benefits:
- Open-ended — any engine can define new functions via YAML
- No schema changes needed for new functions
- Function options (overflow, rounding, null handling) are first-class
- Variant resolution by argument types

Cost:
- Requires function registry at both producer and consumer
- Function semantics not visible in the plan without YAML lookup
- Runtime validation of arguments against YAML definitions
- Complex signature matching (500+ function variants across 18 YAML files)

### Substrait Function Categories (18 YAML Files)

| File | Example Functions | Count |
|------|-------------------|-------|
| `functions_arithmetic.yaml` | add, subtract, multiply, divide, negate | ~30 variants |
| `functions_arithmetic_decimal.yaml` | add, subtract, multiply, divide (decimal) | ~15 variants |
| `functions_comparison.yaml` | equal, not_equal, lt, lte, gt, gte, between, is_nan | ~25 variants |
| `functions_boolean.yaml` | and, or, not, xor, and_not | ~15 variants |
| `functions_string.yaml` | concat, substring, upper, lower, trim, like, strpos | ~40 variants |
| `functions_datetime.yaml` | extract, add_days, date_trunc, current_date | ~50 variants |
| `functions_logarithmic.yaml` | ln, log10, log2, logb | ~10 variants |
| `functions_rounding.yaml` | abs, sign, round, ceil, floor, sqrt, exp | ~30 variants |
| `functions_rounding_decimal.yaml` | abs, round, ceil, floor (decimal) | ~10 variants |
| `functions_aggregate_generic.yaml` | count, sum, avg, min, max, any_value | ~30 variants |
| `functions_aggregate_approx.yaml` | approx_count_distinct | ~5 variants |
| `functions_aggregate_decimal_output.yaml` | avg, sum (decimal output) | ~10 variants |
| `functions_list.yaml` | transform (map), filter | Higher-order functions |
| `functions_set.yaml` | index_in | Set operations |
| `functions_geometry.yaml` | Spatial functions | Geometry |

Total: **500+ function variants** across all YAML files.

### Phase 11c Implementation Experience

Our attempt to integrate with Substrait's function system revealed the practical complexity:

1. `SimpleExtension.load(List<String>)` loads YAML files from URIs
2. `ExtensionCollection.getScalarFunction(FunctionAnchor)` resolves function variants (not by name+types)
3. `ExtensionCollector` assigns numeric anchors to functions and manages the plan-level extension declarations
4. `ExpressionCreator.scalarFunction(ScalarFunctionVariant, Type, List<Expression>)` requires the resolved variant

This 4-step process (load YAMLs → resolve variant → assign anchor → create expression) is why our `SubstraitFunctionRegistry` remains a stub. It's not just a mapping table — it's a stateful resolution system.

---

## 6. Column Reference Model

### ProtoCatalyst: Name-Based

```scala
ColumnRef(
  name = "age",
  qualifier = Some("users"),
  resolvedType = IntegerType,
  nullable = false
)
```

- Columns identified by `(name, qualifier)`
- Type and nullability resolved at construction time (SQL parser or macro DSL)
- No separate resolution pass needed
- Human-readable plan output

### Substrait: Ordinal-Based

```
FieldReference(
  root_reference: 0,          // first field of input
  reference_segment: StructField(2)  // third field of that struct
)
```

- Columns identified by zero-indexed position in parent's output schema
- Supports nested dereferencing: `struct.field`, `list[index]`, `map[key]`
- Requires schema tracking to interpret (field 0 in which struct?)
- Compact representation

### Conversion Challenge

Converting ProtoCatalyst's name-based references to Substrait's ordinal references requires tracking the schema through every plan node to resolve `(name, qualifier)` to a position index. This is doable but adds a traversal pass that serves no purpose in ProtoCatalyst's own pipeline.

---

## 7. Aggregate Decomposition

Substrait has a concept that ProtoCatalyst does not: **aggregate decomposition** for distributed execution.

### Substrait: AggregationPhase

```
Measure {
  function: AggregateFunction("sum"),
  phase: INITIAL_TO_INTERMEDIATE  // first pass: partial sum
}

Measure {
  function: AggregateFunction("sum"),
  phase: INTERMEDIATE_TO_RESULT   // second pass: combine
}
```

Phases:
- `INITIAL_TO_INTERMEDIATE` — first pass, produces intermediate state
- `INTERMEDIATE_TO_INTERMEDIATE` — combine intermediate states
- `INITIAL_TO_RESULT` — single-pass (non-distributed)
- `INTERMEDIATE_TO_RESULT` — final pass, produces result

### ProtoCatalyst: No Phase Decomposition

```scala
Aggregate(
  groupingExprs = Vector(ColumnRef("dept")),
  aggregateExprs = Vector(Sum(ColumnRef("salary"))),
  child = scan
)
```

ProtoCatalyst's physical plan layer handles distribution via `Exchange` operators, but the aggregate itself doesn't carry phase information. Each execution backend decides how to decompose aggregates.

---

## 8. ML Integration (ProtoCatalyst Only)

Substrait has no ML equivalent. ProtoCatalyst's ML IR includes:

### TensorExpr (83 variants)
- Leaf: `Input`, `Constant`, `Parameter`
- Linear algebra: `MatMul`, `Gemm`, `Linear`
- Activation: `Relu`, `Sigmoid`, `Softmax`, `Gelu`, etc.
- Normalization: `BatchNorm`, `LayerNorm`, `GroupNorm`
- Recurrent: `LSTM`, `GRU`
- Attention: `ScaledDotProductAttention`, `MultiHeadAttention`
- Loss: `CrossEntropyLoss`, `MSELoss`, `L1Loss`

### ComputeGraph
- DAG-based computation graph (not a tree like SQL plans)
- Named nodes for debugging and analysis
- ONNX import/export for model interop
- Autograd support (reverse-mode automatic differentiation)

### SQL-ML Bridge
- `Predict(model: ComputeGraph, inputMapping, child)` — inference in query plan
- `Fit(model: ComputeGraph, inputMapping, labelMapping, trainConfig, child)` — training in query plan

### Tensor Type System
- `TensorType(dtype, shape, layout)` with `Dim.Static` / `Dim.Dynamic`
- 12 dtypes: Float16/32/64, BFloat16, Int8/16/32/64, UInt8, Bool, Complex64/128
- Layout support: Default, NCHW, NHWC

---

## 9. Summary: When to Use Which

| Use Case | Best Choice |
|----------|------------|
| Cross-engine query plan exchange | Substrait |
| Compile-time query validation | ProtoCatalyst |
| Engine-independent optimizer | ProtoCatalyst |
| ML model integration in SQL | ProtoCatalyst |
| Function extensibility without recompilation | Substrait |
| Schema contracts for deployment safety | ProtoCatalyst |
| Targeting DataFusion/DuckDB/Velox/Acero | Substrait (native consumers exist) |
| Targeting Spark with full feature support | ProtoCatalyst (direct lowering) |
| Distributed aggregate decomposition | Substrait (built-in phases) |

### Our Position

ProtoCatalyst is a **compiler IR** that produces optimized, validated query plans. Substrait is an **exchange format** that moves plans between engines. They serve different layers of the stack.

We use **direct backend lowerings** (Spark, DataFusion via SQL transpiler, local Arrow executor) rather than routing through Substrait, because:

1. Our IR is richer (CTEs, Pivot, ML, schema contracts)
2. Direct lowerings exploit engine-specific features
3. The SQL transpiler approach (Phase 11b) works with any SQL-compatible engine without Substrait's function registry complexity

A Substrait exporter remains a valid future option for engines that only accept Substrait input, but it would be one of many backend lowerings — not the primary or only export path.

---

## References

- [ADR-002: Independent IR Over Substrait Export](ADR-002-independent-ir.md)
- [Substrait specification](https://substrait.io/)
- [Substrait Java library](https://github.com/substrait-io/substrait-java) (io.substrait:core:0.78.0)
- [Substrait extension YAML files](https://github.com/substrait-io/substrait/tree/main/extensions/)
- [ROADMAP.md](../../ROADMAP.md) — Phase 11c implementation notes
