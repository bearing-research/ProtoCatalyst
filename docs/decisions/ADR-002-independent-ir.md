# ADR-002: Independent IR Over Substrait Export

## Status

**Accepted** - February 2026

## Context

ProtoCatalyst maintains its own protobuf-serialized IR (ProtoType, ProtoExpr, ProtoLogicalPlan) for query plan representation. Substrait is a cross-engine standard for serializing query plans, adopted by DataFusion, DuckDB, Velox, and Acero. The question: should ProtoCatalyst export to Substrait as its interchange format, or stay independent?

### Substrait Overview

Substrait defines protobuf messages for types, expressions, and relations. Its core design choices:

- **Extension-based functions**: Every scalar, aggregate, and window function is a `ScalarFunction(function_reference, args, output_type)` where `function_reference` points to a YAML definition via URI/URN. There are no explicit protobuf variants for `add`, `upper`, `abs`, etc.
- **Positional column references**: Columns are addressed by zero-indexed ordinal within the child operator's output, not by name.
- **Exchange format, not compiler IR**: Substrait has no optimizer, no compile-time validation, and no schema contracts. It is designed for "engine A produces a plan, engine B consumes it."

### ProtoCatalyst's IR

ProtoCatalyst enumerates every expression as an explicit enum variant (100 variants in ProtoExpr) and identifies columns by `(name, qualifier)`. It includes an optimizer (41 rules), schema contracts with fingerprints, compile-time validation via macros, and ML compute graphs.

## Decision

**We will NOT export to Substrait.** ProtoCatalyst's protobuf schema is the canonical interchange format. Backend lowerings translate directly from `ProtoLogicalPlan` to each target engine's native representation.

## Rationale

### 1. Substrait Export Would Be Lossy

ProtoCatalyst plan nodes with no Substrait equivalent:

| Node | Purpose |
|------|---------|
| `Pivot` / `Unpivot` | SQL PIVOT/UNPIVOT operations |
| `Generate` | LATERAL VIEW (Explode, PosExplode, Inline, Stack) |
| `LateralJoin` | Correlated subquery in FROM clause |
| `With` | Common Table Expressions (recursive and non-recursive) |
| `ResolvedHint` | Optimizer hints (BROADCAST, COALESCE, etc.) |
| `Distinct` | Explicit DISTINCT as a plan node |
| `SubqueryAlias` | Named subquery alias |

ProtoCatalyst features with no Substrait representation:

| Feature | Purpose |
|---------|---------|
| `SchemaContract` + `SchemaFingerprint` | Compile-time schema validation |
| `CompiledArtifact` | Self-contained deployment artifact with versioning |
| `ComputeGraph` / `TensorExpr` | ML compute graphs with autograd |
| `SumType` | Discriminated union / ADT types |
| `VariantType` | Semi-structured data type |
| Union by name / allow missing columns | Flexible UNION semantics |

Exporting to Substrait would require either decomposing these into lower-level operations (lossy) or using extension stubs (opaque to consumers).

### 2. Fundamental Model Clash

**Functions**: Substrait represents all functions as extension references (`ScalarFunction(anchor=42, uri="...functions_arithmetic.yaml")`). ProtoCatalyst uses explicit enum variants (`ProtoExpr.Add`, `ProtoExpr.Upper`, `ProtoExpr.Abs`). Converting between these models adds an indirection layer and an extension registry for zero benefit — our expressions are already fully typed and self-contained.

**Column references**: Substrait uses positional (zero-indexed ordinal). ProtoCatalyst uses named `(name, qualifier)`. Converting requires schema tracking through the entire plan tree to resolve names to positions, only to throw away the name information.

**Nullability**: Substrait carries nullability per-type (`NULLABLE` / `REQUIRED` enum on each type). ProtoCatalyst carries it per-field (boolean on `ProtoStructField`). Different but not convertible without restructuring.

### 3. Substrait Is an Exchange Format, Not a Compiler IR

Substrait is designed for the use case: "engine A produces a plan, engine B consumes it." ProtoCatalyst is a compiler that owns the entire pipeline from DSL/SQL to execution. The appropriate model is direct backend lowerings (like LLVM backends), not an intermediate exchange format.

| Concern | Substrait | ProtoCatalyst |
|---------|-----------|---------------|
| Optimization | Consumer's responsibility | Built-in (41 compile-time rules) |
| Validation | Runtime | Compile-time (macros, schema contracts) |
| Schema contracts | Not supported | First-class (`SchemaContract`, `SchemaFingerprint`) |
| ML integration | Out of scope | `ComputeGraph`, `TensorExpr`, autograd |
| Artifact packaging | Not supported | `CompiledArtifact` with versioning, source info, content hash |

### 4. Direct Backend Lowerings Are More Expressive

Each backend can translate the full `ProtoLogicalPlan` to its native representation, using engine-specific features where available:

```
ProtoLogicalPlan → SparkBackend      (exists today)
ProtoLogicalPlan → DataFusionBackend (future)
ProtoLogicalPlan → VeloxBackend      (future)
```

A Substrait intermediary would be a lowest-common-denominator bottleneck. Direct lowerings can exploit engine-specific operators (e.g., Spark's native PIVOT support, DataFusion's streaming aggregation).

## Consequences

### Positive

- No lossy translation — all IR features preserved through to backends
- No extension registry complexity
- IR evolution is independent (add new ProtoExpr variants without coordinating with Substrait spec)
- Each backend lowering is purpose-built and can exploit engine-specific features
- Schema contracts, compile-time validation, and ML integration remain first-class

### Negative

- No automatic interop with Substrait-consuming engines — each backend must be built separately
- Cannot leverage Substrait's existing consumer implementations
- Must maintain our own protobuf schema as the canonical format
- Ecosystem adoption requires engines to understand ProtoCatalyst's format (or use a backend lowering)

### Mitigations

- A Substrait exporter can be added later as one of many backend lowerings if interop becomes important. The decision is to not make it the primary or only export format.
- The `proto` module (pure Java protobuf) makes it straightforward for other JVM systems to consume ProtoCatalyst plans directly.

## Alternatives Considered

### A. Substrait as Primary Export Format (Rejected)

Export all plans as Substrait, consuming engines use Substrait directly.

Rejected because: lossy (loses Pivot, CTEs, schema contracts, ML), adds extension registry complexity, fundamental model clash with function representation.

### B. Substrait as Secondary Export (Deferred)

Keep ProtoCatalyst IR as primary, add optional `SubstraitConverter` alongside `ProtoConverter`.

Deferred: can be added later if interop with Substrait consumers becomes a priority. Same shape of work as any other backend lowering.

### C. Adopt Substrait as Internal IR (Rejected)

Replace ProtoCatalyst's IR with Substrait's protobuf schema entirely.

Rejected because: would lose compile-time macro integration, schema contracts, ML compute graphs, and explicit expression typing. Substrait's extension-based function model is fundamentally incompatible with ProtoCatalyst's design.

## References

- [Substrait specification](https://substrait.io/)
- [Substrait protobuf definitions](https://github.com/substrait-io/substrait/tree/main/proto/substrait/)
- [ADR-001: No Runtime Code Generation](ADR-001-no-runtime-codegen.md)
- ProtoCatalyst protobuf definitions: `proto/src/main/protobuf/protocatalyst/v1/`
