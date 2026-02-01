# ProtoCatalyst Encoder Benchmarks

This module contains JMH benchmarks comparing ProtoCatalyst's compile-time encoder derivation
with Spark's runtime reflection-based ExpressionEncoder.

## Architecture

Two benchmark modules exist for cross-Scala-version comparison:

| Module | Scala Version | Purpose |
|--------|---------------|---------|
| `benchmarks` | 3.8.1 | ProtoCatalyst encoder benchmarks |
| `benchmark-spark` | 2.13.16 | Spark ExpressionEncoder benchmarks |

The benchmarks use identical data structures and test scenarios for fair comparison.

## Running Benchmarks

### ProtoCatalyst Benchmarks (Scala 3)

```bash
# Run all Proto benchmarks
sbt benchmarks/Jmh/run

# Run specific benchmark class
sbt 'benchmarks/Jmh/run InlineSerializerBenchmarks'
sbt 'benchmarks/Jmh/run ScalingBenchmarks'

# With custom options
sbt 'benchmarks/Jmh/run -i 20 -wi 10 -f 2 -t 1 InlineSerializerBenchmarks'
```

### Spark Benchmarks (Scala 2.13)

```bash
# Run all Spark benchmarks
sbt benchmarkSpark/Jmh/run

# Run specific benchmark class
sbt 'benchmarkSpark/Jmh/run SparkEncoderBenchmarks'
sbt 'benchmarkSpark/Jmh/run SparkScalingBenchmarks'
```

### JMH Options

| Option | Description | Default |
|--------|-------------|---------|
| `-i` | Measurement iterations | 20 |
| `-wi` | Warmup iterations | 10 |
| `-f` | Forks (JVM instances) | 2 |
| `-t` | Threads | 1 |
| `-rf` | Result format (json, csv) | text |
| `-rff` | Result file path | - |

## Benchmark Results (2026-02-01)

Environment: JDK 21.0.10, Apple Silicon (M-series), JMH 1.37

### InlineRowSerializer vs Spark ExpressionEncoder

ProtoCatalyst uses compile-time type specialization via `inline erasedValue[T] match` to eliminate
runtime type dispatch. Spark uses runtime reflection + code generation.

#### Serialization Performance (ns/op, lower is better)

| Type | Spark | ProtoCatalyst | Speedup |
|------|-------|---------------|---------|
| Simple (2 fields) | 26 | 6.8 | **3.8x** |
| Person (nested struct) | 109 | 61.2 | **1.8x** |
| Team (List[Person]) | 654 | 382 | **1.7x** |

#### Deserialization Performance (ns/op, lower is better)

| Type | Spark | ProtoCatalyst | Speedup |
|------|-------|---------------|---------|
| Simple | 23 | 3.4 | **6.8x** |
| Person | 74 | 8.6 | **8.6x** |
| Team | 546 | 74.6 | **7.3x** |

#### Roundtrip Performance (ns/op, lower is better)

| Type | Spark | ProtoCatalyst | Speedup |
|------|-------|---------------|---------|
| Simple | 158 | 5.9 | **26.8x** |
| Person | 590 | 60.3 | **9.8x** |
| Team | 1,281 | 390 | **3.3x** |

### Schema Derivation Cost

Spark pays a significant runtime cost to derive encoders via TypeTag reflection.
ProtoCatalyst does this at compile time (zero runtime cost).

| Type | Spark (μs) | ProtoCatalyst |
|------|-----------|---------------|
| Simple | 228 | **0 (compile-time)** |
| Person | 960 | **0 (compile-time)** |
| Team | 832 | **0 (compile-time)** |

### Scaling Analysis

Does the speedup hold at larger batch sizes? **Yes.**

#### Person Deserialization (batch processing, ops/s)

| Batch Size | ProtoCatalyst | Spark | Speedup |
|------------|--------------|-------|---------|
| 10 | 19,797,100 | 402,829 | **49x** |
| 100 | 2,003,629 | 47,928 | **42x** |
| 1,000 | 131,206 | 5,344 | **25x** |
| 10,000 | 16,985 | 534 | **32x** |

#### Person Serialization (batch processing, ops/s)

| Batch Size | ProtoCatalyst | Spark | Speedup |
|------------|--------------|-------|---------|
| 10 | 1,562,785 | 977,283 | **1.6x** |
| 100 | 131,447 | 109,993 | **1.2x** |
| 1,000 | 14,863 | 9,424 | **1.6x** |
| 10,000 | 1,186 | 1,089 | **1.1x** |

#### Team (List[Person]) Serialization (batch processing, ops/s)

| Batch Size | ProtoCatalyst | Spark | Speedup |
|------------|--------------|-------|---------|
| 10 | 263,388 | 145,550 | **1.8x** |
| 100 | 28,199 | 16,759 | **1.7x** |
| 1,000 | 2,705 | 1,452 | **1.9x** |

**Key finding**: The speedup is consistent across batch sizes, scaling linearly with data size.

### Summary

| Category | ProtoCatalyst Advantage |
|----------|------------------------|
| Schema Derivation | **∞ (compile-time vs runtime)** |
| Deserialization | **7-49x faster** |
| Serialization | **1.1-3.8x faster** |
| Roundtrip | **3-27x faster** |

**Why deserialization is faster**: ProtoCatalyst reconstructs case classes at compile time via
`Mirror.ProductOf[T].fromProduct`. Spark uses runtime reflection to instantiate objects.

**Why serialization is closer**: Both systems ultimately call similar field access patterns.
The advantage comes from eliminated type dispatch and specialized field handling.

## Benchmark Classes

### InlineSerializerBenchmarks

Tests ProtoCatalyst's compile-time specialized serializer:

| Benchmark | Description |
|-----------|-------------|
| `inlineSerialize*` | Serialize case class to Array[Any] |
| `inlineDeserialize*` | Deserialize Array[Any] to case class |
| `inlineRoundtrip*` | Full serialize→deserialize cycle |
| `currentSerialize*` | RowSerializer (uses InlineRowSerializer internally) |

### ScalingBenchmarks

Tests performance at different batch sizes (10, 100, 1000, 10000):

| Benchmark | Description |
|-----------|-------------|
| `serializePersonBatch*` | Batch serialize Person objects |
| `deserializePersonBatch*` | Batch deserialize Person objects |
| `serializeTeamBatch*` | Batch serialize Team (List[Person]) objects |

### SparkEncoderBenchmarks / SparkScalingBenchmarks

Spark baseline benchmarks for comparison.

## Test Data Classes

Both modules use equivalent data structures:

```scala
case class Simple(name: String, age: Int)
case class Address(street: String, city: String, zip: String)
case class Person(name: String, age: Int, address: Address)
case class Team(name: String, members: List[Person])  // Collections of custom types
case class Complex(
  id: Long,
  name: String,
  scores: List[Double],
  metadata: Map[String, String],
  created: Instant,
  nested: Option[Person]
)
```

## Comparing Results

Run both benchmarks and compare output:

```bash
# Generate comparable results
sbt 'benchmarks/Jmh/run -rf json -rff proto.json InlineSerializerBenchmarks'
sbt 'benchmarkSpark/Jmh/run -rf json -rff spark.json SparkEncoderBenchmarks'

# Compare JSON results
jq -s '.[0] + .[1] | sort_by(.benchmark)' proto.json spark.json
```
