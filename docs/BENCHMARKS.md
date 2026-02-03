# ProtoCatalyst Benchmarks

This document describes the benchmark suite and provides guidance on running and interpreting results.

## Benchmark Modules

### `benchmarks/` (Scala 3)

ProtoCatalyst's own encoder benchmarks using `InlineRowSerializer`.

| Benchmark | Description |
|-----------|-------------|
| `InlineSerializerBenchmarks` | Core serialization/deserialization performance |
| `ProtoEncoderBenchmarks` | Schema derivation (compile-time, measures zero) |
| `CollectionBenchmarks` | Collection scaling (10, 100, 1000 elements) |
| `AllocationBenchmarks` | Memory allocation profiling |
| `ScalingBenchmarks` | Row count scaling |
| `ArrowBenchmarks` | Arrow batch writing |
| `CodecBenchmarks` | TransformingEncoder codecs (Java, Kryo, Fory) |

### `benchmark-spark/` (Scala 2.13)

Spark's `ExpressionEncoder` benchmarks for comparison.

| Benchmark | Description |
|-----------|-------------|
| `SparkEncoderBenchmarks` | ExpressionEncoder performance |
| `SparkScalingBenchmarks` | Spark serialization scaling |
| `SparkArrowBenchmarks` | Spark Arrow integration |

## Running Benchmarks

### Quick Run (Single Benchmark)

```bash
# Run specific benchmark class
sbt "benchmarks/Jmh/run -i 5 -wi 3 -f 1 InlineSerializerBenchmarks"

# Run specific method
sbt "benchmarks/Jmh/run -i 5 -wi 3 -f 1 InlineSerializerBenchmarks.serializeSimple"
```

### Full Run (Publication Quality)

```bash
# ProtoCatalyst benchmarks
sbt "benchmarks/Jmh/run -i 20 -wi 10 -f 2"

# Spark comparison benchmarks
sbt "benchmarkSpark/Jmh/run -i 20 -wi 10 -f 2"
```

### Memory Profiling

```bash
# Run with GC profiler
sbt "benchmarks/Jmh/run -prof gc AllocationBenchmarks"

# Output includes:
#   gc.alloc.rate.norm - bytes allocated per operation
#   gc.count - number of GC events
```

### Collection Scaling

```bash
sbt "benchmarks/Jmh/run CollectionBenchmarks"
```

## Benchmark Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `-i` | 20 | Measurement iterations |
| `-wi` | 10 | Warmup iterations |
| `-f` | 2 | Forks (JVM restarts) |
| `-t` | 1 | Threads |
| `-tu` | ns | Time unit (ns, us, ms, s) |
| `-prof` | none | Profiler (gc, stack, async) |

## Expected Results

### Serialization Performance

Based on JMH benchmarks comparing ProtoCatalyst vs Spark ExpressionEncoder:

| Operation | Spark (ns) | ProtoCatalyst (ns) | Speedup |
|-----------|------------|-------------------|---------|
| Simple serialize | 73 | 28 | 2.6x |
| Simple deserialize | 157 | 23 | 6.8x |
| Nested serialize | 186 | 101 | 1.8x |
| Nested deserialize | 548 | 64 | 8.6x |
| Wide (20 fields) serialize | ~300 | ~150 | ~2x |

### Roundtrip Performance

| Type | Spark (ns) | ProtoCatalyst (ns) | Speedup |
|------|------------|-------------------|---------|
| Simple | 230 | 51 | 4.5x |
| Nested (Person) | 734 | 27 | 27x |
| Wide (20 fields) | ~550 | ~180 | ~3x |
| Team (List[Person]) | ~2000 | ~400 | ~5x |

### Schema Derivation

| Operation | Spark (μs) | ProtoCatalyst (μs) | Notes |
|-----------|------------|-------------------|-------|
| Simple | 228 | 0 | Compile-time |
| Person | 447 | 0 | Compile-time |
| Complex | 960 | 0 | Compile-time |

### Collection Scaling

Expected scaling characteristics for `List[String]`:

| Size | Serialize (ns) | Deserialize (ns) |
|------|---------------|-----------------|
| 10 | ~200 | ~150 |
| 100 | ~1,500 | ~1,200 |
| 1000 | ~15,000 | ~12,000 |

Scaling is approximately linear with collection size.

### Memory Allocation

Typical allocation per operation (with `-prof gc`):

| Operation | Bytes/op |
|-----------|----------|
| Serialize Simple | ~80 |
| Serialize Person | ~200 |
| Serialize Collection (100) | ~2,000 |
| Deserialize Simple | ~120 |
| Deserialize Person | ~300 |

## Interpreting Results

### Key Metrics

- **Score (ns/op)**: Lower is better - nanoseconds per operation
- **Error (±)**: 95% confidence interval - smaller is more reliable
- **Throughput (ops/s)**: Higher is better - operations per second

### Sample Output

```
Benchmark                          Mode  Cnt   Score   Error  Units
InlineSerializerBenchmarks.serializeSimple   avgt   40   28.3 ±  0.5  ns/op
InlineSerializerBenchmarks.serializePerson   avgt   40  101.2 ±  2.1  ns/op
InlineSerializerBenchmarks.deserializeSimple avgt   40   23.1 ±  0.4  ns/op
InlineSerializerBenchmarks.deserializePerson avgt   40   64.5 ±  1.2  ns/op
```

### Comparison Guidelines

When comparing ProtoCatalyst vs Spark:

1. **Run on same machine** - Results vary by hardware
2. **Use same JVM settings** - Heap size, GC algorithm
3. **Warm up sufficiently** - JIT compilation affects results
4. **Multiple forks** - Reduces JVM startup variance

## Test Data Classes

### Simple (2 fields)
```scala
case class Simple(name: String, age: Int)
```

### Person (nested, 3 levels)
```scala
case class Address(street: String, city: String, zip: String)
case class Person(name: String, age: Int, address: Address)
```

### Wide (20 fields)
```scala
case class Wide(
  f1: Int, f2: Int, ..., f10: Int,
  f11: String, ..., f15: String,
  f16: Double, ..., f20: Double
)
```

### Collection Tests
```scala
case class WithStringList(items: List[String])
case class WithIntMap(data: Map[String, Int])
case class WithPersonList(members: List[Person])
```

## Why ProtoCatalyst is Faster

### 1. Compile-Time Type Specialization

Spark:
```scala
// Runtime type dispatch
value match {
  case s: String => UTF8String.fromString(s)
  case i: Int => i
  case l: List[_] => ArrayData.toArrayData(l.map(convert))
  // ... more runtime checks
}
```

ProtoCatalyst:
```scala
// Compile-time specialization via inline
inline erasedValue[Types] match
  case _: (String *: ts) =>
    result(idx) = conv.toInternal(value, StringType)  // Direct call
  case _: (Int *: ts) =>
    result(idx) = value  // No conversion needed
```

### 2. No Expression Tree Interpretation

Spark builds expression trees that are interpreted at runtime:
```scala
// Spark creates Expression objects
CreateNamedStruct(
  Literal("name") :: GetExternalRowField(input, 0) ::
  Literal("age") :: GetExternalRowField(input, 1) :: Nil
)
```

ProtoCatalyst generates direct field access:
```scala
// Direct product element access
result(0) = conv.toInternal(product.productElement(0), StringType)
result(1) = product.productElement(1)  // Int passes through
```

### 3. Zero Schema Derivation Cost

Spark derives schema at runtime using TypeTag reflection:
```scala
// Runtime reflection
val tpe = typeTag[T].in(mirror).tpe
val fields = tpe.decls.collect { case m: MethodSymbol if m.isCaseAccessor => ... }
```

ProtoCatalyst derives schema at compile time:
```scala
// Compile-time via Mirror
inline m match
  case p: Mirror.ProductOf[T] =>
    // Field types and names known at compile time
```

## Adding New Benchmarks

1. Create benchmark class in `benchmarks/src/main/scala/protocatalyst/bench/`
2. Add test data to `BenchmarkData.scala` if needed
3. Use JMH annotations:

```scala
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 20, time = 1)
class MyBenchmarks:
  @Benchmark
  def myOperation(): Unit = ...
```

## CI Integration

Benchmarks are not run in CI by default (too slow). For regression testing:

```bash
# Quick smoke test
sbt "benchmarks/Jmh/run -i 1 -wi 1 -f 1 InlineSerializerBenchmarks.serializeSimple"
```
