# ProtoCatalyst

A Scala 3 library that moves safe, deterministic parts of Spark SQL/Catalyst work from runtime to compile time — encoder derivation, query optimization, and plan serialization all happen during compilation, producing pre-optimized artifacts that Spark executes directly.

## Key Features

- **Compile-Time Query Optimization**: 48 optimizer rules run at compile time via `quote { }` DSL and `SqlMacro.compileOptimized`
- **Type-Safe Query DSL**: `quote { Table[User]("users").filter(_.age > 18).select(_.name) }` — fully type-checked at compile time
- **Compile-Time SQL Parsing**: SQL strings parsed, validated, and optimized during compilation
- **Compile-Time Encoder Derivation**: Type-safe encoder derivation using Scala 3 `inline` and `Mirror` — no runtime reflection
- **InlineRowSerializer**: 3–27x faster roundtrip serialization than Spark's ExpressionEncoder
- **Dual Serialization Formats**: JSON and Protobuf artifact formats with PCAT binary container
- **Spark Execution Bridge**: `SparkQueryRunner.execute(bytes, spark)` — pre-optimized plans run as DataFrames
- **Arrow Integration**: Compile-time specialized Arrow columnar format writes

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     COMPILE TIME (scalac)                        │
├─────────────────────────────────────────────────────────────────┤
│  DSL                              SQL                            │
│  quote {                          SqlMacro.compileOptimized(     │
│    Table[User]("users")             "SELECT name FROM users      │
│      .filter(_.age > 18)             WHERE age > 18"             │
│      .select(_.name)              )                              │
│  }                                                               │
│       │                                │                         │
│       └──────────┬─────────────────────┘                         │
│                  ▼                                                │
│          ProtoLogicalPlan (compile-time IR)                       │
│                  │                                                │
│                  ▼                                                │
│          Optimizer.optimize() — 48 rules at compile time         │
│                  │                                                │
│                  ▼                                                │
│          CompiledArtifact → PCAT bytes (JSON or Protobuf)        │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                     RUNTIME (Spark)                              │
├─────────────────────────────────────────────────────────────────┤
│  SparkQueryRunner.execute(bytes, spark)                          │
│       │                                                          │
│       ▼                                                          │
│  ArtifactParser → Spark LogicalPlan (pre-optimized)             │
│       │                                                          │
│       ▼                                                          │
│  DataFrame results (Spark handles physical planning + AQE)      │
└─────────────────────────────────────────────────────────────────┘
```

## Quick Start

### Type-Safe Query DSL

```scala
import protocatalyst.dsl.*
import protocatalyst.dsl.functions.*

case class User(name: String, age: Int, salary: Double) derives ProtoEncoder

// Compile-time optimized query
val query = quote {
  Table[User]("users")
    .filter(_.age > 18)
    .select(u => (u.name, u.salary))
}

// Serialize to bytes for Spark execution
val bytes: Array[Byte] = query.toBytes
```

### SQL Path

```scala
import protocatalyst.query.*

// SQL parsed and optimized at compile time
val query = CompiledQuery.sqlOptimized[User]("SELECT name FROM users WHERE age > 18 AND true")
// "AND true" folded away at compile time
```

### Spark Execution

```scala
// In Spark (Scala 2.13)
import protocatalyst.catalyst.SparkQueryRunner

val df: DataFrame = SparkQueryRunner.execute(bytes, spark)
df.show()
```

### Encoder Derivation

```scala
import protocatalyst.encoder.*

case class Person(name: String, age: Int, address: Address) derives ProtoEncoder

val serializer = InlineRowSerializer.derived[Person]
val row: Array[Any] = serializer.serialize(Person("Alice", 30, address))
val restored: Person = serializer.deserialize(row)
```

## DSL Operations

| Category | Operations |
|----------|-----------|
| **Filtering** | `.filter(_.age > 18)`, `.where(...)` |
| **Projection** | `.select(_.name)`, `.select(u => (u.name, u.age))` |
| **Sorting** | `.orderBy(_.salary.desc)` |
| **Limiting** | `.limit(10)`, `.distinct` |
| **Joins** | `.join(t2).on(...)`, `.leftJoin(...)`, `.rightJoin(...)`, `.crossJoin(...)` |
| **Aggregation** | `.groupBy(_.dept).agg(count, sum(_.salary), avg(_.age))` |
| **Set ops** | `.union(...)`, `.intersect(...)`, `.except(...)` |
| **Subqueries** | `.filter(_.id in subquery)`, `exists(...)`, `scalarSubquery(...)` |
| **Windows** | `rowNumber().over(Window.partitionBy(...).orderBy(...))` |
| **Hints** | `.broadcast`, `.coalesce(4)`, `.repartition(8)` |

See [DSL Reference](docs/DSL_REFERENCE.md) for the complete API.

## Performance Highlights

Benchmarked against Spark 4.0's ExpressionEncoder (JDK 21, Apple Silicon):

| Operation | Spark | ProtoCatalyst | Speedup |
|-----------|-------|---------------|---------|
| Roundtrip Simple | 158 ns | 5.9 ns | **27x** |
| Roundtrip Person (nested) | 590 ns | 60 ns | **10x** |
| Roundtrip Team (List[Person]) | 1,281 ns | 390 ns | **3.3x** |
| Deserialize (all types) | - | - | **7–49x** |
| Schema Derivation | 228–960 μs | **0** | **∞ (compile-time)** |

See [benchmarks/README.md](benchmarks/README.md) for details.

## Modules

| Module | Scala | Description |
|--------|-------|-------------|
| `proto` | Java | Protobuf schema definitions (`.proto` files, generated Java classes) |
| `core` | 3 | Types, schema, IR, optimizer (48 rules), codec (JSON + Protobuf) |
| `encoder` | 3 | Compile-time encoder derivation (ProtoEncoder, InlineRowSerializer) |
| `arrow` | 3 | Arrow columnar format integration |
| `query` | 3 | Type-safe DSL (`quote { }`), compiled query artifacts |
| `sql-parser` | 3 | Compile-time SQL parsing and AST transformation |
| `mock-runtime` | 3 | Testing without Spark dependency |
| `spark-catalyst` | 2.13 | Spark integration — plan decoders, SparkQueryRunner, parity tests |
| `benchmarks` | 3 | JMH benchmarks (Scala 3) |
| `benchmark-spark` | 2.13 | Spark comparison benchmarks |

## Project Stats

- **~33,000** lines of source code across 142 files
- **~1,948** tests across 74 test files
- **101** commits on main
- **93** expression types in the IR, **20** plan node types
- **48** optimizer rules (constant folding, predicate pushdown, filter combining, etc.)
- **4** protobuf schema files generating 176 Java classes

## Building

```bash
# Compile all modules
sbt compile

# Run all tests (~1,948 tests)
sbt test

# Run specific module tests
sbt "core/test"            # Core IR + optimizer (477 tests)
sbt "query/test"           # DSL + macro tests (166 tests)
sbt "sparkCatalyst/test"   # Spark integration (289 tests)

# Run benchmarks
sbt 'benchmarks/Jmh/run InlineSerializerBenchmarks'
sbt 'benchmarkSpark/Jmh/run SparkEncoderBenchmarks'
```

## Documentation

- **[DSL Reference](docs/DSL_REFERENCE.md)** — Complete query DSL API reference
- **[Compile-Time DSL](docs/COMPILE_TIME_DSL.md)** — How the compile-time query optimization works
- **[Understanding Encoders](docs/ENCODER_DEEP_DIVE.md)** — Beginner-friendly encoder guide
- **[Replacing Spark's Reflective Encoder](docs/REPORT.md)** — the reflection-replacement writeup (the Scala 3 thesis)
- **[Infrastructure](docs/INFRASTRUCTURE.md)** — cross-version (Scala 3 ↔ 2.13) build topology + how to run the reflection-replacement work
- [Design Document](docs/DESIGN.md) — Architecture and design decisions
- [Optimizer Plan](docs/OPTIMIZER_PLAN.md) — 48 optimizer rules and implementation
- [SQL Parser](docs/SQL_PARSER.md) — Compile-time SQL parsing
- [Spark Catalyst Reference](docs/SPARK_CATALYST_REFERENCE.md) — Spark internals reference
- [Spark Migration Guide](docs/SPARK_MIGRATION_GUIDE.md) — Integration with Spark Scala 3
- [Benchmarks](docs/BENCHMARKS.md) — Performance testing and results
- [ADR-001: No Runtime Codegen](docs/decisions/ADR-001-no-runtime-codegen.md) — Why compile-time over runtime

## Requirements

- Scala 3.8.1+ / 2.13.16 (Spark modules)
- JDK 21+
- sbt 1.12+

## License

Apache 2.0
