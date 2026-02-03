# Spark Scala 3 Migration Guide

This guide explains how ProtoCatalyst can serve as the foundation for Spark's Scala 3 encoder implementation.

## Overview

ProtoCatalyst provides a compile-time encoder derivation system using Scala 3's `Mirror` API, designed to replace Spark's current `TypeTag`-based `ScalaReflection.encoderFor[T]` method.

### Current Spark Encoder Pipeline (Scala 2)

```
TypeTag[T] → ScalaReflection.encoderFor → AgnosticEncoder → ExpressionEncoder
```

Key issues with the current approach:
- **Runtime reflection** - TypeTag requires runtime type inspection
- **Schema derivation cost** - 228-960μs per encoder creation
- **Not Scala 3 compatible** - TypeTag doesn't exist in Scala 3

### ProtoCatalyst Encoder Pipeline (Scala 3)

```
Mirror.Of[T] → ProtoEncoder.derived → InlineRowSerializer → InternalRow
```

Benefits:
- **Compile-time derivation** - Zero runtime cost for schema discovery
- **Type-specialized code** - Inline expansion eliminates type dispatch
- **10-27x faster roundtrip** - Benchmarked against ExpressionEncoder

## File Mapping

| ProtoCatalyst | Spark Equivalent | Purpose |
|---------------|------------------|---------|
| `ProtoEncoder.scala` | `ScalaReflection.scala` | Type derivation |
| `InlineRowSerializer.scala` | `ExpressionEncoder.scala` | Serialization |
| `ProtoType.scala` | `DataType.scala` | Type representation |
| `InternalTypeConverter.scala` | Internal to ExpressionEncoder | Type conversion |

## Type Coverage

ProtoCatalyst supports all Spark DataTypes:

### Primitive Types
| Scala Type | ProtoType | Spark DataType |
|------------|-----------|----------------|
| Boolean | BooleanType | BooleanType |
| Byte | ByteType | ByteType |
| Short | ShortType | ShortType |
| Int | IntegerType | IntegerType |
| Long | LongType | LongType |
| Float | FloatType | FloatType |
| Double | DoubleType | DoubleType |
| String | StringType | StringType |
| Array[Byte] | BinaryType | BinaryType |

### Boxed Primitives
| Java Type | ProtoType | Notes |
|-----------|-----------|-------|
| java.lang.Boolean | BooleanType | nullable = true |
| java.lang.Integer | IntegerType | nullable = true |
| java.lang.Long | LongType | nullable = true |
| (all boxed types) | Corresponding type | nullable = true |

### Decimal Types
| Scala Type | ProtoType |
|------------|-----------|
| BigDecimal | DecimalType(38, 18) |
| BigInt | DecimalType(38, 0) |
| java.math.BigDecimal | DecimalType(38, 18) |
| java.math.BigInteger | DecimalType(38, 0) |

### Temporal Types
| Java Type | ProtoType | Internal Representation |
|-----------|-----------|------------------------|
| java.time.LocalDate | DateType | Int (epoch days) |
| java.time.Instant | TimestampType | Long (microseconds) |
| java.time.LocalDateTime | TimestampNTZType | Long (microseconds) |
| java.time.LocalTime | TimeType(6) | Long (microseconds since midnight) |
| java.time.Duration | DayTimeIntervalType | Long (microseconds) |
| java.time.Period | YearMonthIntervalType | Int (total months) |
| java.time.OffsetDateTime | TimestampType | Long (microseconds, UTC) |
| java.time.ZonedDateTime | TimestampType | Long (microseconds, UTC) |
| java.sql.Date | DateType | Int (epoch days) |
| java.sql.Timestamp | TimestampType | Long (microseconds) |
| java.util.Date | TimestampType | Long (microseconds) |

### Collection Types
| Scala Type | ProtoType |
|------------|-----------|
| Seq[T] | ArrayType(T, containsNull) |
| List[T] | ArrayType(T, containsNull) |
| Vector[T] | ArrayType(T, containsNull) |
| Set[T] | ArrayType(T, containsNull) |
| Array[T] | ArrayType(T, containsNull) |
| Map[K, V] | MapType(K, V, valueContainsNull) |

### Composite Types
| Scala Type | ProtoType |
|------------|-----------|
| case class | StructType(fields) |
| Tuple2-22 | StructType with _1, _2, ... |
| Option[T] | T with nullable = true |
| Scala 3 enum | StringType (simple) or SumType (ADT) |
| Java enum | StringType |
| sealed trait | SumType (discriminated union) |

### Special Types
| Type | ProtoType | Notes |
|------|-----------|-------|
| java.util.UUID | StringType | 36-char canonical form |
| java.lang.Void | NullType | null-only columns |
| Char(n) | CharType(n) | Fixed-length string |
| Varchar(n) | VarcharType(n) | Variable-length string |

### Spark-Internal Types (Deferred)
| Type | Status | Reason |
|------|--------|--------|
| CalendarInterval | Deferred | Requires Spark internal type |
| VariantVal | Deferred | Requires Spark internal type |

## API Migration

### Before (Spark 2/3 with TypeTag)

```scala
import org.apache.spark.sql.catalyst.ScalaReflection
import scala.reflect.runtime.universe.TypeTag

def encoderFor[E: TypeTag]: AgnosticEncoder[E] = {
  val tpe = typeTag[E].in(mirror).tpe
  // Complex runtime reflection logic
  // Analyzes type structure at runtime
  encoderForType(tpe)
}

// Usage
val encoder = ExpressionEncoder[Person]()  // Runtime reflection
val serializer = encoder.createSerializer()
val row = serializer(person)
```

### After (ProtoCatalyst with Mirror)

```scala
import scala.deriving.Mirror
import protocatalyst.encoder.{ProtoEncoder, InlineRowSerializer}

inline def encoderFor[E](using m: Mirror.ProductOf[E]): ProtoEncoder[E] =
  ProtoEncoder.derived[E]  // Compile-time derivation

// Usage
val encoder = summon[ProtoEncoder[Person]]  // Compile-time, zero cost
val serializer = InlineRowSerializer.derived[Person]
val row = serializer.serialize(person)
```

### Key Differences

| Aspect | Spark (TypeTag) | ProtoCatalyst (Mirror) |
|--------|-----------------|------------------------|
| Schema derivation | Runtime | Compile-time |
| Type dispatch | Runtime isInstanceOf | Compile-time erasedValue |
| Code generation | Expression trees | Inline expansion |
| Error timing | Runtime exceptions | Compile-time errors |
| GraalVM native | Requires config | Works out of box |

## Integration Steps

### Step 1: Replace ScalaReflection

The core replacement is in `ScalaReflection.scala`:

```scala
// sql/api/src/main/scala/org/apache/spark/sql/catalyst/ScalaReflection.scala

// Before (Scala 2):
def encoderFor[E: TypeTag]: AgnosticEncoder[E] = {
  val tpe = typeTag[E].in(mirror).tpe
  encoderForType(tpe)
}

// After (Scala 3):
inline def encoderFor[E](using m: Mirror.Of[E]): AgnosticEncoder[E] =
  inline m match
    case p: Mirror.ProductOf[E] => productEncoderFor[E](p)
    case s: Mirror.SumOf[E]     => sumEncoderFor[E](s)

private inline def productEncoderFor[E](using m: Mirror.ProductOf[E]): ProductEncoder[E] =
  // Use ProtoCatalyst's derivation pattern
  val fields = deriveFields[m.MirroredElemTypes, m.MirroredElemLabels]
  ProductEncoder(fields, ClassTag[E])
```

### Step 2: Integrate InlineRowSerializer

The serialization logic moves to compile-time specialization:

```scala
// sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/encoders/ExpressionEncoder.scala

class ExpressionEncoder[T](
  val schema: StructType,
  val serializer: T => InternalRow,
  val deserializer: InternalRow => T
)

object ExpressionEncoder:
  inline def apply[T](using
    enc: ProtoEncoder[T],
    ser: InlineRowSerializer[T]
  ): ExpressionEncoder[T] =
    ExpressionEncoder(
      schema = toSparkSchema(enc.schema),
      serializer = t => InternalRow.fromSeq(ser.serialize(t).toSeq),
      deserializer = row => ser.deserialize(extractRow(row))
    )
```

### Step 3: Update Implicits

```scala
// sql/api/src/main/scala/org/apache/spark/sql/SQLImplicits.scala

// Before (Scala 2):
implicit def newProductEncoder[T <: Product : TypeTag]: Encoder[T] =
  ExpressionEncoder[T]()

// After (Scala 3):
given productEncoder[T <: Product](using
  m: Mirror.ProductOf[T]
): Encoder[T] =
  ExpressionEncoder[T]
```

## Performance Comparison

### Benchmark Results

| Operation | Spark (ns) | ProtoCatalyst (ns) | Speedup |
|-----------|------------|-------------------|---------|
| Schema derivation | 228,000-960,000 | 0 | ∞ (compile-time) |
| Simple serialize | 73 | 28 | 2.6x |
| Simple deserialize | 157 | 23 | 6.8x |
| Nested serialize | 186 | 101 | 1.8x |
| Nested deserialize | 548 | 64 | 8.6x |
| Nested roundtrip | 734 | 27 | 27x |

### Why Faster?

1. **No runtime type dispatch** - `inline erasedValue` generates specialized code
2. **No expression tree interpretation** - Direct field access
3. **No schema inference overhead** - Schema fixed at compile time
4. **No reflection overhead** - Mirror is compile-time only

## Testing Strategy

### 1. Golden File Parity Tests

ProtoCatalyst includes 26 golden file tests that verify byte-identical output with Spark's ExpressionEncoder:

```scala
// Generate golden files (Scala 2.13 + Spark)
sbt "benchmarkSpark/runMain protocatalyst.benchmark.GoldenFileGenerator"

// Run parity tests (Scala 3)
sbt "mockRuntime/test"
```

### 2. Run Spark Encoder Tests

After integration, run Spark's existing encoder test suite:

```bash
# In Spark repo
./build/sbt "catalyst/testOnly *EncoderSuite*"
```

### 3. Performance Regression Tests

```bash
# Run ProtoCatalyst benchmarks
sbt "benchmarks/Jmh/run -i 10 -wi 5 -f 2 InlineSerializerBenchmarks"

# Compare with Spark benchmarks
sbt "benchmarkSpark/Jmh/run -i 10 -wi 5 -f 2 SparkEncoderBenchmarks"
```

## Known Differences

### Error Handling

**Spark (TypeTag):** Unsupported types throw runtime exceptions
```scala
ExpressionEncoder[SomeUnsupportedType]()  // RuntimeException at runtime
```

**ProtoCatalyst (Mirror):** Unsupported types cause compile errors
```scala
ProtoEncoder.derived[SomeUnsupportedType]  // Compile error
// error: Cannot find or derive ProtoEncoder for type...
```

### Enum Handling

**Spark (Scala 2):** Limited enum support, uses EnumEncoder
```scala
// Java enums work, Scala enums require special handling
```

**ProtoCatalyst (Scala 3):** Full Scala 3 enum support via Mirror.SumOf
```scala
enum Color derives ProtoEncoder:
  case Red, Green, Blue

sealed trait Result derives ProtoEncoder
case class Success(value: Int) extends Result
case class Failure(error: String) extends Result
```

### Type Inference

**Spark:** Can derive encoders for generic types via TypeTag
```scala
def encodeList[T: TypeTag]: Encoder[List[T]] = ...  // Works
```

**ProtoCatalyst:** Requires concrete types at compile site
```scala
// Generic derivation requires inline + Mirror
inline def encodeList[T](using enc: ProtoEncoder[T]): ProtoEncoder[List[T]] =
  ProtoEncoder.listEncoder[T]  // Works
```

## Migration Checklist

- [ ] Replace `ScalaReflection.encoderFor` with Mirror-based derivation
- [ ] Update `ExpressionEncoder` to use `InlineRowSerializer`
- [ ] Migrate `SQLImplicits` to use `given` instances
- [ ] Update `Encoders.product` factory method
- [ ] Run encoder test suite
- [ ] Run performance benchmarks
- [ ] Update documentation

## Resources

- [ENCODER_DEEP_DIVE.md](ENCODER_DEEP_DIVE.md) - Technical details of the encoder system
- [BENCHMARKS.md](BENCHMARKS.md) - Performance benchmarks documentation
- [ProtoCatalyst Source](../encoder/src/main/scala/protocatalyst/encoder/) - Implementation
