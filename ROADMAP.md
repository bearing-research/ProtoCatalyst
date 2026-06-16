# ProtoCatalyst Roadmap

## Current State

ProtoCatalyst moves safe, deterministic parts of Spark SQL/Catalyst from runtime to compile time. There are two bodies of work:

1. **The query compiler (Phases 1–11, feature-complete; built through Feb 2026).** Compile-time encoder derivation, an engine-independent IR (`ProtoLogicalPlan` / `ProtoExpr` / `ProtoPhysicalPlan`), a 41-rule SQL optimizer, a SQL parser and `quote { }` DSL, protobuf/JSON serialization, and three execution backends (Spark, a standalone Arrow engine, and DataFusion via SQL transpilation). It also includes an ML compute-graph IR with autograd and ONNX interop and native ML-in-SQL `Predict`/`Fit` operators, plus Parquet I/O for the standalone executor. This layer is stable and largely unchanged since February.

2. **The Scala 3 reflection-replacement thesis (current focus).** Replacing Spark's runtime reflection-based encoder derivation (`ScalaReflection.encoderFor[T: TypeTag]`) with Scala 3 compile-time `Mirror` derivation that produces Spark's own `AgnosticEncoder`, to unblock Spark's Scala 3 migration. This is the active line of work — see [`docs/scala3-encoder/REPORT.md`](docs/scala3-encoder/REPORT.md) and the [docs index](docs/README.md).

### By the Numbers

| Metric | Count |
|--------|------:|
| Source lines | ~51,000 |
| Test lines | ~42,000 |
| Test cases | ~2,700 |
| Modules | 15 |
| Optimizer rules | 49 (41 SQL + 8 ML) |
| IR expression types | 100 |
| IR logical plan node types | 27 |
| IR physical plan node types | 30 |
| Protobuf definitions | 8 files |
| Execution backends | 3 (Spark, Local Arrow, DataFusion); Velox not started |
| Spark target | 4.1.2 |

---

## Completed Phases

### Phase 1: Core Encoder System ✅

- ProtoEncoder with compile-time derivation (Scala 3 `Mirror`)
- ProtoType enum covering all Spark DataTypes
- Full type support: primitives, BigInt/BigDecimal, Date/Time, timezone-aware types, intervals, collections, tuples (2–22), Option, case classes, enums
- InlineRowSerializer with compile-time specialization (per-row results in the encoder report)
- InternalTypeConverter abstraction with mock runtime
- Arrow integration (InlineArrowWriter, ArrowBatchBuilder)
- 268 unit tests, 26 Spark parity tests

### Phase 2: Spark Integration Readiness ✅

- 100% parity with Spark's AgnosticEncoder types
- Performance benchmarks vs ExpressionEncoder (see [`docs/scala3-encoder/REPORT.md`](docs/scala3-encoder/REPORT.md) for current, caveated numbers)
- Migration documentation for Spark team

### Phase 3: Compile-Time IR and Optimizer ✅

- ProtoExpr (100 expression variants) — complete expression IR
- ProtoLogicalPlan (27 plan node types) — full logical plan IR
- 48 optimizer rules at compile time:
  - Constant folding, boolean simplification, predicate canonicalization
  - Filter combining, predicate pushdown, column pruning
  - Limit pushdown, sort elimination, distinct elimination
  - CTE inlining, correlated subquery rewriting
  - Join optimization, set operation rewriting
- ProtoLiftables — all IR types liftable as compile-time Expr constants
- JSON serialization via upickle with full roundtrip support

### Phase 4: Compile-Time SQL Parser ✅

- Full SQL lexer and parser (recursive descent)
- AST → ProtoLogicalPlan transformation
- `SqlMacro.compileOptimized` — SQL parsed and optimized at compile time
- SELECT, WHERE, JOIN, GROUP BY, ORDER BY, LIMIT, DISTINCT, UNION, INTERSECT, EXCEPT
- Window functions, CTEs, PIVOT/UNPIVOT, LATERAL VIEW, subqueries, hints
- 344 parser tests

### Phase 5: Type-Safe DSL (`quote { }` Macro) ✅

- `quote { }` macro — Scala DSL compiled to ProtoLogicalPlan at compile time
- Typed FieldSelector via `transparent inline` macro — `_.name` returns `Column[User, String]`
- Multi-column lambda select: `.select(u => (u.name, u.age))`
- Full operation support:
  - Filter, select, orderBy, limit, distinct, as (alias)
  - Join (inner, left, right, cross) with typed conditions
  - GroupBy + aggregates (count, sum, avg, min, max)
  - Union, intersect, except
  - Subqueries (IN, NOT IN, EXISTS, NOT EXISTS, scalar subquery)
  - Correlated subqueries with outer schema resolution
  - Window functions (rowNumber, rank, denseRank, lead, lag, etc.)
  - Hints (broadcast, coalesce, repartition)
- Comparison, boolean, arithmetic, null-check, string operators
- 67 macro tests

### Phase 6: Protobuf Serialization Format ✅

- `proto` module — pure Java, consumable by both Scala 3 and 2.13
- 8 `.proto` files: `types.proto`, `schema.proto`, `plan.proto`, `physical_plan.proto`, `artifact.proto`, `ml_types.proto`, `ml_graph.proto`, plus a bundled `onnx.proto`
- ~390 generated Java classes
- ProtoConverter — bidirectional Scala IR ↔ Java protobuf conversion (~1,400 lines)
- ProtobufArtifactCodec implementing ArtifactCodec trait
- PCAT binary container: `"PCAT" + format byte (0x01=JSON, 0x02=Protobuf) + payload`
- ~3–10x smaller than JSON for typical plans
- 52 core roundtrip tests, 41 spark-catalyst decoder tests

### Phase 7: Spark Execution Bridge ✅

- SparkQueryRunner — `execute(bytes, spark)` → DataFrame
- ArtifactParser dispatches JSON (0x01) and Protobuf (0x02) formats
- Protobuf decoders: ProtobufTypeDecoder, ProtobufExpressionDecoder, ProtobufPlanDecoder
- JSON decoders: TypeDecoder, ExpressionDecoder, PlanDecoder (full coverage of all 100 expr + 27 plan types)
- SparkPlanEncoder — bidirectional Spark → ProtoCatalyst JSON encoding
- SchemaValidator — validates compile-time schema contracts against Spark catalog at runtime
- Pivot, Unpivot, Generate converters (not stubs)
- Hint passthrough (BROADCAST, SHUFFLE_MERGE, COALESCE, REPARTITION, etc.)
- 136 parity tests, 289 total spark-catalyst tests

### Phase 8: End-to-End Integration ✅

- E2EArtifactGenerator — produces PCAT artifacts from `quote { }` and `CompiledQuery.sqlOptimized`
- E2EIntegrationSuite — full compile → serialize → deserialize → Spark execute pipeline
- SparkQueryRunner tests with real query execution against test data

### Phase 9: Physical Plan Layer ✅

- `ProtoPhysicalPlan` enum (30 variants) — physical plan IR with execution strategy choices
- `PhysicalPlanner` — converts `ProtoLogicalPlan` → `ProtoPhysicalPlan` based on statistics, heuristics, and hints
- Join strategies: `HashJoin`, `SortMergeJoin`, `BroadcastHashJoin`, `NestedLoopJoin` with automatic selection
- Aggregate strategies: `HashAggregate`, `SortAggregate`
- `Exchange` operator for data redistribution; `BuildSide`, `Partitioning` enums
- `Statistics` + `ColumnStatistics` propagation — row counts, sizes, cardinality estimates
- `Cost` + `CostEstimator` — CPU/IO/memory cost per physical operator
- `HashJoinOp` — build/probe hash join with all 7 join types, null key exclusion, residual conditions
- `SortMergeJoinOp` — sort-merge join with duplicate key runs, all 7 join types
- `PhysicalPlanExecutor` — replaces `PlanExecutor` as sole execution entry point
- `CompiledArtifact.physicalPlan: Option[ProtoPhysicalPlan]` — optional pre-planned physical plan
- Protobuf serialization for all physical plan types (`physical_plan.proto`)
- `QueryRunner` now uses physical pipeline: logical → physical → execute
- 93 executor tests, 44 planner tests

### Phase 9.5: ML Compute Graph IR ✅

- `TensorExpr` enum (50+ tensor operations) — MatMul, Conv, Relu, BatchNorm, Dropout, etc.
- `TensorType` with static shape inference and broadcasting rules
- `ComputeGraph` — DAG-based computation graph (analogous to `ProtoLogicalPlan`)
- 8 ML optimizer rules: algebraic simplification, batch norm elimination, CSE, dead node elimination, dropout elimination, identity elimination, matmul-add fusion, transpose fusion
- Symbolic reverse-mode autograd (VJP rules)
- ONNX export/import for model interop
- Typed tensor DSL with compile-time shape checking (`ml-query` module)
- 200+ ML tests across 13 test files

### Phase 10: ML as Plan Operators (Predict + Fit) ✅

- `ProtoLogicalPlan.Predict` — native ML inference in query plans, maps model inputs to SQL expressions
- `ProtoLogicalPlan.Fit` — native ML training in query plans with configurable optimizer and loss
- `ProtoPhysicalPlan.PhysicalPredict` / `PhysicalFit` — physical plan nodes (1:1 lowering)
- `TrainConfig` + `OptimizerType` (SGD, Adam) — training configuration types
- `GraphEvaluator` — forward-pass tensor evaluator for `ComputeGraph` on `Array[Float]`, ~30 supported ops
- `PredictOp` — Arrow ↔ tensor bridge for inference: evaluates input expressions → runs forward pass → appends output columns
- `FitOp` — training loop: extracts features/labels → builds gradient graph via `Autograd.backward()` → trains with SGD or Adam → returns `(epoch, loss)` metrics
- Protobuf serialization for Predict/Fit plan nodes (`plan.proto`, `physical_plan.proto`)
- `PhysicalPlanner` support — `Predict` → `PhysicalPredict`, `Fit` → `PhysicalFit`
- 22 ML executor tests (GraphEvaluator, PredictOp, FitOp)

### Phase 11a: Parquet Reader/Writer ✅

- `ParquetSchemaConverter` — bidirectional `ProtoSchema` ↔ Parquet `MessageType` conversion with full type mapping
- `ParquetIO` — read/write Parquet files as Arrow `VectorSchemaRoot` with configurable compression (Snappy, Gzip, Zstd, Uncompressed)
- `ParquetIO.readSchema` / `readRowCount` / `readStatistics` — metadata-only operations from Parquet footer
- `ParquetSupport.readBatch` / `writeBatch` — `Batch`-aware wrappers for Parquet I/O
- `Catalog.registerParquetTable` extension — one-liner to register Parquet files with auto-inferred schema and statistics
- Supports all primitive types, decimals (INT32/INT64/FIXED_LEN), temporal types, arrays, maps, structs
- 40 Parquet tests (schema conversion, read/write roundtrip, catalog integration)

### Phase 11b: SQL Transpiler + DataFusion Backend ✅

- **SQL Transpiler** — converts ProtoCatalyst IR to executable SQL (ANSI SQL / DataFusion compatible)
  - `SqlGenerator` — `ProtoLogicalPlan` → SQL query strings (22 supported plan node types)
  - `ExprSqlGenerator` — `ProtoExpr` → SQL expression fragments (100 expression variants)
  - `TypeSqlGenerator` — `ProtoType` → SQL type names
  - Supports SELECT, WHERE, JOIN (all 7 types), GROUP BY, ORDER BY, LIMIT, DISTINCT, UNION, INTERSECT, EXCEPT, CTEs, subqueries, window functions
  - Defensive design: throws clear exceptions for unsupported features (Pivot, Unpivot, Generate, LateralJoin, ML nodes)
  - 138 transpiler tests (33 TypeSqlGenerator + 77 ExprSqlGenerator + 28 SqlGenerator)
- **DataFusion Backend** — third execution backend via ADBC Flight SQL driver
  - `DataFusionBackend` — executes `ProtoLogicalPlan` by transpiling to SQL and sending to DataFusion Flight SQL server via ADBC
  - `FlightSqlConfig` — connection configuration (host, port, TLS, authentication)
  - Arrow-native data transfer (zero-copy RecordBatches → `VectorSchemaRoot` → `Batch`)
  - ADBC dependencies: `adbc-core`, `adbc-driver-manager`, `adbc-driver-flight-sql` (0.15.0)
  - 17 integration tests (gracefully skip when server unavailable — no CI failures)
- **Why SQL Transpilation?** — Engine-independent approach works for any SQL-compatible backend (DataFusion, DuckDB, PostgreSQL, Trino, Snowflake)
- **Why ADBC?** — Arrow-native API, 33% faster than JDBC, stable specification (v1.1.0), pure JVM (no native code management)

### Phase 11c: Substrait Converter (Partial) 🚧

- **Substrait Module** — new `substrait` module with Substrait Java bindings (io.substrait:core:0.78.0)
- **SubstraitTypeConverter** — `ProtoType` → Substrait Type conversion (fully working)
  - Handles all primitive types (Boolean, Int, Long, Float, Double, String, Binary, Date, Timestamp, Decimal)
  - Supports complex types (Array, Map, Struct) with nested structures
  - 19 type converter tests passing
- **SubstraitExprConverter** — `ProtoExpr` → Substrait Expression conversion (partially working)
  - **Literals**: All types working (Boolean, Int, Long, Float, Double, String, Binary, Date, Timestamp, Decimal)
  - **Cast**: Fully working with proper failure behavior
  - **Alias**: Working (passes through child expression)
  - **Functions**: Documented but blocked by Substrait extension system complexity
  - 24 expression converter tests passing
- **SubstraitFunctionRegistry** — Function mapping stub (documents required integration work)
  - Maps ProtoCatalyst function names → Substrait function names
  - Documents Substrait's YAML-based function extension system
  - Full implementation requires loading extension files and resolving function anchors (~500-1000 lines)
- **Blocker**: Substrait's function extension system is complex and requires substantial integration work beyond current scope
- **Alternative**: SQL transpiler backend (Phase 11b) works today without function mapping complexity
- **Status**: Types + literals + cast working; functions, aggregates, window functions, and plan conversion deferred
- **Tests**: 43 Substrait tests passing (19 type + 24 expression)

---

## Vision: A Query Compiler, Not Another Engine

ProtoCatalyst started as a Spark optimization layer. It is evolving into something more general: **a query compiler** — an engine-independent system that validates, optimizes, and serializes query plans at compile time, then lowers them to any execution backend.

The analogy is LLVM: LLVM doesn't compete with x86, ARM, or RISC-V — it targets all of them. ProtoCatalyst doesn't compete with Spark, DataFusion, or Velox — it sits above them. The crowded space is runtimes. The compiler layer above them is nearly empty.

### What makes this unique

No other system combines these properties:

1. **Compile-time query validation** — schema mismatches, type errors, and malformed queries caught before deployment. Every other engine validates at runtime.
2. **Engine-independent IR** — `ProtoLogicalPlan` and `ProtoExpr` are self-contained, serializable via protobuf, and not coupled to any runtime.
3. **Unified SQL + ML** — `ProtoLogicalPlan` (relational) and `ComputeGraph` (tensor/ML) live in the same IR with shared types. Native `Predict` and `Fit` operators bridge SQL query plans with ML model inference and training.
4. **Built-in optimizer** — 49 rules (41 SQL + 8 ML) that run at compile time, before any runtime engine sees the plan.

### Why not Substrait?

We evaluated Substrait as an interchange format and decided to stay independent (see [ADR-002](docs/decisions/ADR-002-independent-ir.md)). Key reasons:

- **Substrait export would be lossy.** Pivot, Unpivot, Generate, LateralJoin, CTEs, schema contracts, and ML compute graphs have no Substrait equivalent.
- **Fundamental model clash.** Substrait uses extension-based functions (URI + YAML registry). ProtoCatalyst uses explicit enum variants. Converting between them adds complexity for zero benefit.
- **Substrait is an exchange format, not a compiler IR.** It has no optimizer, no compile-time validation, no schema contracts. Our IR is richer and purpose-built.

Instead, we treat our protobuf schema as the canonical format and build **direct backend lowerings** per engine — the same way LLVM has separate backends for x86 and ARM rather than converting to an intermediate ISA.

---

## Future Work

### Phase 11: Backend Lowerings

Build direct lowerings from `ProtoLogicalPlan` / `ProtoPhysicalPlan` to target runtimes:

- [x] **Spark backend** — `ProtoLogicalPlan` → Spark `LogicalPlan` via `SparkQueryRunner`
- [x] **Local executor** — single-node Arrow-columnar pipeline via `PhysicalPlanExecutor`
- [x] **DataFusion backend** — `ProtoLogicalPlan` → SQL transpiler → ADBC Flight SQL → DataFusion server
- [ ] **Velox backend** — `ProtoPhysicalPlan` → Velox `PlanNode` (C++ FFI)

### Ongoing: Spark Integration

- [x] `toAgnosticEncoder[T]`: ProtoEncoder → AgnosticEncoder bridge — **done** (`encoder-spark` module, Spark 4.1.2; see [`docs/scala3-encoder/REPORT.md`](docs/scala3-encoder/REPORT.md))
- [ ] `protocatalyst.spark.implicits` for implicit encoder derivation
- [ ] Runtime statistics collection for cost-based optimization
- [ ] Delta Lake / Iceberg integration via Spark backend

### Ongoing: Ecosystem

- [ ] Arrow IPC for language interop (Python, Rust clients)
- [x] Parquet reader/writer using ProtoCatalyst schemas (enables non-Spark execution)
- [ ] CLI tool for plan inspection, optimization, and format conversion

---

## Architecture Decisions

### Why compile-time?

- Zero runtime reflection overhead
- Type errors caught at compile time
- Inline expansion for primitive types (no runtime reflection on the hot path)
- 49 optimizer rules execute during compilation, not at query time
- Compatible with GraalVM native-image

### Why engine-independent?

- The IR is not coupled to any runtime — the `core` / `encoder` / `query` modules build and test with no Spark dependency
- Backend lowerings can target any engine, not just Spark
- Protobuf serialization makes the IR language-neutral
- Cleaner module boundaries; easier to add new backends

### Why dual serialization (JSON + Protobuf)?

- JSON: human-readable, easy debugging, existing tooling
- Protobuf: 3–10x smaller, faster serialization, schema evolution
- PCAT container format supports both transparently via format byte
- `proto` module is pure Java — works with both Scala 3 and 2.13

### Why golden file / parity testing?

- 136 parity tests guarantee Spark-compatible plan structure
- Catches subtle serialization differences
- Documents expected behavior
- Enables testing without Spark runtime
