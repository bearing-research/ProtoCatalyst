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

## Expected Results

### Schema Derivation

Proto (compile-time Mirror) should be faster than Spark (runtime TypeTag):
- Proto: ~0.01-0.1 μs (already compiled)
- Spark: ~10-100 μs (runtime reflection)

### Serialization/Deserialization

Performance depends on object complexity:
- Simple objects: Comparable performance
- Complex/nested: Proto may be faster (no expression tree overhead)

### Codec Comparison

| Codec | Relative Speed | Notes |
|-------|----------------|-------|
| Java | 1x (baseline) | No dependencies |
| Kryo | ~10x | Thread-local pooling |
| Fory | ~170x | Scala 3 native support |

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
