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

## Future Work

### Spark Dataset[T] API (pending Spark 4.0 Scala 3 support)

When Spark publishes Scala 3 artifacts:
- [ ] `toAgnosticEncoder[T]`: ProtoEncoder → AgnosticEncoder bridge
- [ ] `protocatalyst.spark.implicits` for implicit encoder derivation
- [ ] Integration tests for Dataset operations (map, filter, groupBy, join)

### Catalog-Aware Optimization

- [ ] Runtime statistics collection via SparkQueryRunner
- [ ] Cost-based join reordering (complement compile-time rule-based optimization)

### Ecosystem Integration

- [ ] Parquet reader/writer using ProtoCatalyst schemas
- [ ] Delta Lake / Iceberg integration
- [ ] Structured Streaming support
- [ ] Arrow IPC for language interop (Python, Rust)

---

## Architecture Decisions

### Why compile-time?

- Zero runtime reflection overhead
- Type errors caught at compile time
- Inline expansion for primitive types (3–27x faster serialization)
- 48 optimizer rules execute during compilation, not at query time
- Compatible with GraalVM native-image

### Why separate from Spark?

- Test without Spark dependency (mock-runtime, 279 tests)
- Scala 3 and 2.13 modules coexist cleanly
- Support Arrow independently
- Cleaner module boundaries
- Easier to maintain across Spark versions

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
