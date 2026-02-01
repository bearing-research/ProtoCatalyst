# ProtoCatalyst

A Scala 3 library that moves safe, deterministic parts of Spark SQL/Catalyst work from runtime to compile time.

## Key Features

- **Compile-Time Encoder Derivation**: Type-safe encoder derivation using Scala 3 `inline` and `Mirror` - no runtime reflection
- **InlineRowSerializer**: 3-27x faster roundtrip serialization than Spark's ExpressionEncoder
- **Collections of Custom Types**: Full support for `List[CustomType]`, `Map[String, CustomType]`
- **Arrow Integration**: Compile-time specialized Arrow columnar format writes

## Performance Highlights

Benchmarked against Spark 4.0's ExpressionEncoder (JDK 21, Apple Silicon):

| Operation | Spark | ProtoCatalyst | Speedup |
|-----------|-------|---------------|---------|
| Roundtrip Simple | 158 ns | 5.9 ns | **27x** |
| Roundtrip Person (nested) | 590 ns | 60 ns | **10x** |
| Roundtrip Team (List[Person]) | 1,281 ns | 390 ns | **3.3x** |
| Deserialize (all types) | - | - | **7-49x** |
| Schema Derivation | 228-960 μs | **0** | **∞ (compile-time)** |

The speedup scales linearly with data size. See [benchmarks/README.md](benchmarks/README.md) for details.

## Modules

| Module | Description |
|--------|-------------|
| `core` | Types, schema, IR (ProtoType, ProtoSchema, ProtoLogicalPlan) |
| `encoder` | Compile-time encoder derivation (ProtoEncoder, InlineRowSerializer) |
| `arrow` | Arrow columnar format integration |
| `query` | Compiled query artifacts |
| `sql-parser` | Compile-time SQL parsing |
| `mock-runtime` | Testing without Spark dependency |
| `benchmarks` | JMH benchmarks (Scala 3) |
| `benchmark-spark` | Spark comparison benchmarks (Scala 2.13) |

## Quick Start

```scala
import protocatalyst.encoder.*

// Automatic compile-time encoder derivation
case class Person(name: String, age: Int, address: Address) derives ProtoEncoder

// Get the derived encoder
val encoder = summon[ProtoEncoder[Person]]
println(encoder.schema)  // Vector(name: String, age: Int, address: Struct)

// Serialize to row format (Spark-compatible)
val serializer = RowSerializer.derived[Person]
val row: Array[Any] = serializer.serialize(Person("Alice", 30, address))
val restored: Person = serializer.deserialize(row)
```

### Collections of Custom Types

```scala
case class User(name: String, age: Int) derives ProtoEncoder
case class Team(name: String, members: List[User]) derives ProtoEncoder

val serializer = RowSerializer.derived[Team]
val team = Team("Engineering", List(User("Alice", 30), User("Bob", 25)))
val row = serializer.serialize(team)  // Correctly handles nested List[User]
```

## How It Works

ProtoCatalyst uses Scala 3's compile-time metaprogramming to eliminate runtime overhead:

```scala
// Compile-time type dispatch (ProtoCatalyst)
inline erasedValue[Types] match
  case _: (Int *: ts) =>
    result(idx) = product.productElement(idx)  // Specialized at compile time
    serializeFieldsImpl[ts](...)

// Runtime type dispatch (Spark)
def createSerializer(dataType: DataType): Any => Any = dataType match
  case IntegerType => (v: Any) => v  // Checked at runtime for every row
```

This `inline erasedValue[T] match` pattern generates specialized code paths at compile time,
eliminating runtime type checks that Spark performs for every field of every row.

## Building

```bash
# Compile all modules
sbt compile

# Run tests
sbt test

# Run benchmarks
sbt 'benchmarks/Jmh/run InlineSerializerBenchmarks'
sbt 'benchmarkSpark/Jmh/run SparkEncoderBenchmarks'
```

## Documentation

- [Design Document](docs/DESIGN.md) - Architecture and design decisions
- [Encoder Deep Dive](docs/ENCODER_DEEP_DIVE.md) - How compile-time derivation works
- [Benchmarks](benchmarks/README.md) - Performance comparison with Spark
- [ADR-001: No Runtime Codegen](docs/decisions/ADR-001-no-runtime-codegen.md) - Why compile-time over runtime

## Requirements

- Scala 3.8.1+
- JDK 21+
- sbt 1.12+

## License

Apache 2.0
