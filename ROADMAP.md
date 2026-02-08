# ProtoCatalyst Roadmap

## Current State

ProtoCatalyst moves safe, deterministic parts of Spark SQL/Catalyst from runtime to compile time. The project is feature-complete for its core mission — compile-time encoder derivation, query optimization, and Spark execution of pre-optimized plans.

### By the Numbers

| Metric | Count |
|--------|------:|
| Source lines | ~33,000 |
| Test lines | ~30,000 |
| Test cases | ~1,948 |
| Modules | 10 |
| Optimizer rules | 48 |
| IR expression types | 93 |
| IR plan node types | 20 |
| Protobuf definitions | 4 files (176 generated Java classes) |
| Commits | 101 |

---

## Completed Phases

### Phase 1: Core Encoder System ✅

- ProtoEncoder with compile-time derivation (Scala 3 `Mirror`)
- ProtoType enum covering all Spark DataTypes
- Full type support: primitives, BigInt/BigDecimal, Date/Time, timezone-aware types, intervals, collections, tuples (2–22), Option, case classes, enums
- InlineRowSerializer with compile-time specialization (3–27x faster than Spark)
- InternalTypeConverter abstraction with mock runtime
- Arrow integration (InlineArrowWriter, ArrowBatchBuilder)
- 268 unit tests, 26 Spark parity tests

### Phase 2: Spark Integration Readiness ✅

- 100% parity with Spark's AgnosticEncoder types
- Performance benchmarks: 10–27x faster roundtrip vs ExpressionEncoder
- Migration documentation for Spark team

### Phase 3: Compile-Time IR and Optimizer ✅

- ProtoExpr (93 expression variants) — complete expression IR
- ProtoLogicalPlan (20 plan node types) — full logical plan IR
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
- 4 `.proto` files: `types.proto`, `schema.proto`, `plan.proto`, `artifact.proto`
- 176 generated Java classes
- ProtoConverter — bidirectional Scala IR ↔ Java protobuf conversion (~1,400 lines)
- ProtobufArtifactCodec implementing ArtifactCodec trait
- PCAT binary container: `"PCAT" + format byte (0x01=JSON, 0x02=Protobuf) + payload`
- ~3–10x smaller than JSON for typical plans
- 52 core roundtrip tests, 41 spark-catalyst decoder tests

### Phase 7: Spark Execution Bridge ✅

- SparkQueryRunner — `execute(bytes, spark)` → DataFrame
- ArtifactParser dispatches JSON (0x01) and Protobuf (0x02) formats
- Protobuf decoders: ProtobufTypeDecoder, ProtobufExpressionDecoder, ProtobufPlanDecoder
- JSON decoders: TypeDecoder, ExpressionDecoder, PlanDecoder (full coverage of all 93 expr + 20 plan types)
- SparkPlanEncoder — bidirectional Spark → ProtoCatalyst JSON encoding
- SchemaValidator — validates compile-time schema contracts against Spark catalog at runtime
- Pivot, Unpivot, Generate converters (not stubs)
- Hint passthrough (BROADCAST, SHUFFLE_MERGE, COALESCE, REPARTITION, etc.)
- 136 parity tests, 289 total spark-catalyst tests

### Phase 8: End-to-End Integration ✅

- E2EArtifactGenerator — produces PCAT artifacts from `quote { }` and `CompiledQuery.sqlOptimized`
- E2EIntegrationSuite — full compile → serialize → deserialize → Spark execute pipeline
- SparkQueryRunner tests with real query execution against test data

---

## Vision: A Query Compiler, Not Another Engine

ProtoCatalyst started as a Spark optimization layer. It is evolving into something more general: **a query compiler** — an engine-independent system that validates, optimizes, and serializes query plans at compile time, then lowers them to any execution backend.

The analogy is LLVM: LLVM doesn't compete with x86, ARM, or RISC-V — it targets all of them. ProtoCatalyst doesn't compete with Spark, DataFusion, or Velox — it sits above them. The crowded space is runtimes. The compiler layer above them is nearly empty.

### What makes this unique

No other system combines these properties:

1. **Compile-time query validation** — schema mismatches, type errors, and malformed queries caught before deployment. Every other engine validates at runtime.
2. **Engine-independent IR** — `ProtoLogicalPlan` and `ProtoExpr` are self-contained, serializable via protobuf, and not coupled to any runtime.
3. **Unified SQL + ML** — `ProtoLogicalPlan` (relational) and `ComputeGraph` (tensor/ML) live in the same IR with shared types. The path to native ML-in-SQL operators is short.
4. **Built-in optimizer** — 48 rules that run at compile time, before any runtime engine sees the plan.

### Why not Substrait?

We evaluated Substrait as an interchange format and decided to stay independent (see [ADR-002](docs/decisions/ADR-002-independent-ir.md)). Key reasons:

- **Substrait export would be lossy.** Pivot, Unpivot, Generate, LateralJoin, CTEs, schema contracts, and ML compute graphs have no Substrait equivalent.
- **Fundamental model clash.** Substrait uses extension-based functions (URI + YAML registry). ProtoCatalyst uses explicit enum variants. Converting between them adds complexity for zero benefit.
- **Substrait is an exchange format, not a compiler IR.** It has no optimizer, no compile-time validation, no schema contracts. Our IR is richer and purpose-built.

Instead, we treat our protobuf schema as the canonical format and build **direct backend lowerings** per engine — the same way LLVM has separate backends for x86 and ARM rather than converting to an intermediate ISA.

---

## Future Work

### Phase 9: Physical Plan Layer

Add `ProtoPhysicalPlan` below the logical plan, introducing execution strategy choices:

- [ ] Physical plan enum: `HashJoin`, `SortMergeJoin`, `BroadcastHashJoin`, `HashAggregate`, `SortAggregate`, `Exchange`
- [ ] Physical planner: pattern-match `ProtoLogicalPlan` → `ProtoPhysicalPlan` based on statistics/heuristics
- [ ] Statistics propagation: row counts, column cardinality, size in bytes — bottom-up through the plan
- [ ] Cost model: estimate CPU/IO/network cost per physical operator

### Phase 10: Backend Lowerings

Build direct lowerings from `ProtoLogicalPlan` to target runtimes. Each backend handles what it supports and raises clear errors for what it doesn't:

- [ ] **Spark backend** (exists today) — `ProtoLogicalPlan` → Spark `LogicalPlan` via `SparkQueryRunner`
- [ ] **DataFusion backend** — `ProtoLogicalPlan` → DataFusion `LogicalPlan` directly (Rust FFI or Arrow Flight SQL)
- [ ] **Velox backend** — `ProtoPhysicalPlan` → Velox `PlanNode` (C++ FFI)
- [ ] **Local executor** — single-node Arrow-columnar pipeline for development/testing

### Phase 11: ML as a Plan Operator

Bridge `ProtoLogicalPlan` and `ComputeGraph` into a unified execution model:

- [ ] `ProtoLogicalPlan.Predict(model: ComputeGraph, input: ProtoLogicalPlan)` — native ML inference in query plans
- [ ] Batch inference optimization: vectorized model evaluation over Arrow `RecordBatch`
- [ ] Model versioning and schema validation at compile time
- [ ] ONNX Runtime integration for model execution

### Ongoing: Spark Integration

- [ ] `toAgnosticEncoder[T]`: ProtoEncoder → AgnosticEncoder bridge (pending Spark 4.0 Scala 3 support)
- [ ] `protocatalyst.spark.implicits` for implicit encoder derivation
- [ ] Runtime statistics collection for cost-based optimization
- [ ] Delta Lake / Iceberg integration via Spark backend

### Ongoing: Ecosystem

- [ ] Arrow IPC for language interop (Python, Rust clients)
- [ ] Parquet reader/writer using ProtoCatalyst schemas (enables non-Spark execution)
- [ ] CLI tool for plan inspection, optimization, and format conversion

---

## Architecture Decisions

### Why compile-time?

- Zero runtime reflection overhead
- Type errors caught at compile time
- Inline expansion for primitive types (3–27x faster serialization)
- 48 optimizer rules execute during compilation, not at query time
- Compatible with GraalVM native-image

### Why engine-independent?

- The IR is not coupled to any runtime — test without Spark (mock-runtime, 279 tests)
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
