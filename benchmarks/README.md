# ProtoCatalyst Encoder Benchmarks

This module contains JMH benchmarks for comparing ProtoCatalyst's compile-time encoder derivation
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
sbt 'benchmarks/Jmh/run ProtoEncoderBenchmarks'
sbt 'benchmarks/Jmh/run CodecBenchmarks'

# With custom options
sbt 'benchmarks/Jmh/run -i 20 -wi 10 -f 2 -t 1 ProtoEncoderBenchmarks'
```

### Spark Benchmarks (Scala 2.13)

```bash
# Run all Spark benchmarks
sbt benchmarkSpark/Jmh/run

# Run specific benchmark class
sbt 'benchmarkSpark/Jmh/run SparkEncoderBenchmarks'
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

Example with JSON output:
```bash
sbt 'benchmarks/Jmh/run -rf json -rff proto-results.json'
sbt 'benchmarkSpark/Jmh/run -rf json -rff spark-results.json'
```

## Benchmark Categories

### ProtoEncoderBenchmarks

Tests ProtoCatalyst's compile-time encoder derivation and RowSerializer performance:

| Benchmark | Description |
|-----------|-------------|
| `deriveSimple` | Derive encoder for 2-field case class |
| `derivePerson` | Derive encoder for nested case class |
| `deriveComplex` | Derive encoder for complex case class |
| `serializeSimple` | Serialize simple case class to Array[Any] |
| `deserializeSimple` | Deserialize Array[Any] to case class |
| `roundtripSimple` | Full serialize→deserialize cycle |

### CodecBenchmarks

Compares TransformingEncoder codec performance:

| Benchmark | Description |
|-----------|-------------|
| `javaEncode*` | Java serialization encode |
| `kryoEncode*` | Kryo encode (~10x faster) |
| `foryEncode*` | Fory encode (~170x faster) |
| `*Decode*` | Corresponding decode operations |
| `*Roundtrip*` | Full encode→decode cycle |

### SparkEncoderBenchmarks

Tests Spark's ExpressionEncoder (runtime TypeTag reflection):

| Benchmark | Description |
|-----------|-------------|
| `deriveSimple` | Create ExpressionEncoder[Simple] |
| `serializeSimple` | Serialize to InternalRow |
| `deserializeSimple` | Deserialize from InternalRow |
| `roundtripSimple` | Full serialize→deserialize cycle |

## Benchmark Results (2026-01-31)

Environment: JDK 21.0.10, Apple Silicon, JMH 1.37

### Schema Derivation: Proto vs Spark

| Benchmark | Proto (μs) | Spark (μs) | Speedup |
|-----------|-----------|-----------|---------|
| deriveSimple | 0.135 | 105.6 | **782x** |
| derivePerson | 0.384 | 254.4 | **662x** |
| deriveComplex | 0.891 | 664.8 | **746x** |
| deriveWide | 1.299 | - | - |

**Key insight**: Proto uses compile-time Mirror derivation; Spark uses runtime TypeTag reflection + code generation.

### Serialization: Proto vs Spark

| Benchmark | Proto (μs) | Spark (μs) | Speedup |
|-----------|-----------|-----------|---------|
| serializeSimple | 0.015 | 0.021 | 1.4x |
| serializePerson | 0.028 | 0.073 | **2.6x** |
| serializeComplex | 0.035 | 0.253 | **7.2x** |
| serializeWide | 0.080 | - | - |

### Deserialization: Proto vs Spark

| Benchmark | Proto (μs) | Spark (μs) | Speedup |
|-----------|-----------|-----------|---------|
| deserializeSimple | 0.017 | 0.024 | 1.4x |
| deserializePerson | 0.022 | 0.084 | **3.8x** |
| deserializeComplex | 0.034 | 0.285 | **8.4x** |

### Roundtrip: Proto vs Spark

| Benchmark | Proto (μs) | Spark (μs) | Speedup |
|-----------|-----------|-----------|---------|
| roundtripSimple | 0.038 | 0.054 | 1.4x |
| roundtripPerson | 0.041 | 0.328 | **8.0x** |
| roundtripComplex | 0.072 | 0.633 | **8.8x** |

### Codec Comparison (TransformingEncoder)

| Codec | Encode (ops/s) | Decode (ops/s) | vs Java Encode | vs Java Decode |
|-------|---------------|---------------|----------------|----------------|
| Java | 16,151 | 2,742 | 1x | 1x |
| Kryo | 571,179 | 38,871 | **35x** | **14x** |
| Fory | 331,683 | 522,011 | **21x** | **190x** |

**Codec insights**:
- Kryo excels at encoding (35x faster than Java)
- Fory excels at decoding (190x faster than Java)
- For roundtrip, Kryo slightly faster (373K ops/s vs Fory 126K ops/s)

### Summary

| Category | Proto Advantage |
|----------|----------------|
| Schema Derivation | **660-780x faster** |
| Serialization | 1.4-7.2x faster |
| Deserialization | 1.4-8.4x faster |
| Roundtrip | 1.4-8.8x faster |

The massive schema derivation speedup (660-780x) demonstrates the value of
migrating Spark's encoder from runtime reflection (TypeTag) to compile-time
derivation (Mirror) when Spark moves to Scala 3.

## Test Data Classes

Both modules use equivalent data structures:

```scala
case class Simple(name: String, age: Int)
case class Address(street: String, city: String, zip: String)
case class Person(name: String, age: Int, address: Address)
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
sbt 'benchmarks/Jmh/run -rf json -rff proto.json ProtoEncoderBenchmarks'
sbt 'benchmarkSpark/Jmh/run -rf json -rff spark.json SparkEncoderBenchmarks'

# Compare JSON results (use jq or similar)
jq -s '.[0] + .[1] | sort_by(.benchmark)' proto.json spark.json
```

Key metrics to compare:
- `deriveSimple` vs `deriveSimple` (schema derivation)
- `serializeSimple` vs `serializeSimple` (serialization)
- `roundtripSimple` vs `roundtripSimple` (full cycle)
