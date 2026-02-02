# Spark Integration Guide

This document describes how ProtoCatalyst integrates with Apache Spark and the architecture of Spark's encoder system.

## Spark Encoder Architecture (Spark 4.0)

Spark uses a **two-tier encoder architecture**:

```
User Code
    ↓
Dataset[T] with Encoder[T]
    ↓
┌─────────────────────────────────────────┐
│  AgnosticEncoder[T] (API layer)         │
│  - dataType: DataType                   │
│  - isPrimitive, nullable, isStruct      │
│  - clsTag: ClassTag[T]                  │
└─────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────┐
│  ExpressionEncoder[T] (Catalyst layer)  │
│  - objSerializer: Expression            │
│  - objDeserializer: Expression          │
└─────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────┐
│  Runtime Functions                      │
│  - Serializer: T → InternalRow          │
│  - Deserializer: InternalRow → T        │
└─────────────────────────────────────────┘
```

### Tier 1: AgnosticEncoder

Platform-independent encoder that describes HOW to encode/decode data:

```scala
trait AgnosticEncoder[T] extends Encoder[T] {
  def isPrimitive: Boolean
  def nullable: Boolean = !isPrimitive
  def dataType: DataType
  def clsTag: ClassTag[T]
}
```

Key implementations:
- **Leaf encoders**: `PrimitiveIntEncoder`, `StringEncoder`, `InstantEncoder`, etc.
- **Collection encoders**: `ArrayEncoder[E]`, `IterableEncoder[C, E]`, `MapEncoder[C, K, V]`
- **Structure encoders**: `ProductEncoder[K]` (case classes), `JavaBeanEncoder[K]`
- **Wrapper encoders**: `OptionEncoder[E]`, `TransformingEncoder[I, O]`

### Tier 2: ExpressionEncoder

Converts `AgnosticEncoder[T]` into Catalyst expressions for code generation:

```scala
case class ExpressionEncoder[T](
    encoder: AgnosticEncoder[T],
    objSerializer: Expression,      // T → InternalRow
    objDeserializer: Expression     // InternalRow → T
)
```

Uses `SerializerBuildHelper` and `DeserializerBuildHelper` to generate expression trees.

### InternalRow

Spark's internal row format with specialized getters:

```scala
abstract class InternalRow {
  def numFields: Int
  def getBoolean(ordinal: Int): Boolean
  def getInt(ordinal: Int): Int
  def getLong(ordinal: Int): Long
  def getUTF8String(ordinal: Int): UTF8String
  def getStruct(ordinal: Int, numFields: Int): InternalRow
  def getArray(ordinal: Int): ArrayData
  def getMap(ordinal: Int): MapData
}
```

Implementations:
- `GenericInternalRow`: Simple array-based (used during serialization)
- `UnsafeRow`: Optimized off-heap binary format (used in execution)

## Integration Paths for ProtoCatalyst

### Option A: TransformingEncoder (Recommended for Binary Format)

For encoding case classes as binary (protobuf-style):

```scala
import org.apache.spark.sql.catalyst.encoders.{AgnosticEncoders, ExpressionEncoder}
import org.apache.spark.sql.catalyst.encoders.AgnosticEncoders.{TransformingEncoder, BinaryEncoder}

case class Person(name: String, age: Int)

val protoEncoder = TransformingEncoder(
  classTag[Person],
  BinaryEncoder,  // Intermediate type
  { () => new Codec[Person, Array[Byte]] {
    def encode(p: Person): Array[Byte] = serialize(p)
    def decode(bytes: Array[Byte]): Person = deserialize(bytes)
  }},
  nullable = false
)

val encoder = ExpressionEncoder(protoEncoder)
```

**Pros**: Simple, well-supported, binary format
**Cons**: Opaque to Spark optimizer, no predicate pushdown

### Option B: Custom AgnosticEncoder (Recommended for Struct Format)

Map ProtoCatalyst's `ProtoEncoder` to Spark's `ProductEncoder`:

```scala
def toAgnosticEncoder[T](proto: ProtoEncoder[T]): AgnosticEncoder[T] = {
  val fields = proto.schema.fields.map { f =>
    EncoderField(
      f.name,
      toAgnosticEncoder(f.dataType),  // Recursive
      f.nullable,
      Metadata.empty
    )
  }
  ProductEncoder(proto.clsTag, fields, outerPointerGetter = None)
}
```

**Pros**: Full Spark optimizer integration, predicate pushdown works
**Cons**: More complex, must handle all types

### Option C: Direct InternalRow Conversion

Use `InlineRowSerializer` directly with Spark's Row API:

```scala
import protocatalyst.encoder.InlineRowSerializer

def toInternalRow[T: InlineRowSerializer](value: T): InternalRow = {
  val serializer = summon[InlineRowSerializer[T]]
  val arr = serializer.serialize(value)
  new GenericInternalRow(arr)
}

// Usage with Dataset
val rdd: RDD[Person] = ...
val rows = rdd.map(p => toInternalRow(p))
val df = spark.createDataFrame(rows, SchemaConverter.toSparkSchema(encoder.schema))
```

**Pros**: Leverages existing compile-time work, simple
**Cons**: Manual conversion, limited API integration

## Recommended Integration Strategy

### Phase 1: Schema + Direct Conversion (Current)

```
ProtoCatalyst                    Spark
─────────────                    ─────
ProtoEncoder[T]  ───────────→  StructType (schema)
InlineRowSerializer[T] ─────→  GenericInternalRow
```

- Use `SchemaConverter` for schema
- Use `InlineRowSerializer` for T → InternalRow
- Works with `DataFrame` API

### Phase 2: Full AgnosticEncoder Integration

```
ProtoCatalyst                    Spark
─────────────                    ─────
ProtoEncoder[T]  ───────────→  AgnosticEncoder[T]
                               ↓
                          ExpressionEncoder[T]
                               ↓
                          Full Dataset[T] API
```

Implementation:

```scala
// protocatalyst-spark module
object ProtoEncoders {

  /** Convert ProtoEncoder to Spark's AgnosticEncoder */
  def toAgnostic[T](proto: ProtoEncoder[T]): AgnosticEncoder[T] = {
    proto.catalystType match {
      case ProtoType.StructType(fields) =>
        ProductEncoder(
          proto.clsTag,
          fields.map(f => EncoderField(f.name, typeToAgnostic(f.dataType), f.nullable)),
          outerPointerGetter = None
        )
      case other =>
        leafEncoder(other, proto.clsTag)
    }
  }

  /** Convert ProtoType to AgnosticEncoder */
  private def typeToAgnostic(pt: ProtoType): AgnosticEncoder[?] = pt match {
    case ProtoType.IntType => PrimitiveIntEncoder
    case ProtoType.LongType => PrimitiveLongEncoder
    case ProtoType.StringType => StringEncoder
    case ProtoType.ArrayType(elem, containsNull) =>
      IterableEncoder(classTag[Seq[?]], typeToAgnostic(elem), containsNull)
    case ProtoType.MapType(k, v, valueContainsNull) =>
      MapEncoder(classTag[Map[?, ?]], typeToAgnostic(k), typeToAgnostic(v), valueContainsNull)
    case ProtoType.StructType(fields) =>
      // Nested struct - need to handle recursively
      ???
    // ... etc
  }

  /** Create Spark Encoder from ProtoEncoder */
  def encoder[T](using proto: ProtoEncoder[T]): Encoder[T] = {
    ExpressionEncoder(toAgnostic(proto))
  }
}
```

Usage:

```scala
import protocatalyst.spark.ProtoEncoders.encoder

case class Person(name: String, age: Int) derives ProtoEncoder

val ds: Dataset[Person] = spark.createDataset(Seq(
  Person("Alice", 30),
  Person("Bob", 25)
))

// Full Dataset API works
ds.filter(_.age > 25).map(p => p.copy(name = p.name.toUpperCase))
```

## Type Mapping

| ProtoCatalyst Type | Spark AgnosticEncoder |
|--------------------|-----------------------|
| `ProtoType.BooleanType` | `PrimitiveBooleanEncoder` |
| `ProtoType.ByteType` | `PrimitiveByteEncoder` |
| `ProtoType.ShortType` | `PrimitiveShortEncoder` |
| `ProtoType.IntType` | `PrimitiveIntEncoder` |
| `ProtoType.LongType` | `PrimitiveLongEncoder` |
| `ProtoType.FloatType` | `PrimitiveFloatEncoder` |
| `ProtoType.DoubleType` | `PrimitiveDoubleEncoder` |
| `ProtoType.StringType` | `StringEncoder` |
| `ProtoType.BinaryType` | `BinaryEncoder` |
| `ProtoType.DateType` | `LocalDateEncoder` |
| `ProtoType.TimestampType` | `InstantEncoder` |
| `ProtoType.TimestampNTZType` | `LocalDateTimeEncoder` |
| `ProtoType.DecimalType(p,s)` | `DecimalEncoder(p,s)` |
| `ProtoType.DayTimeIntervalType` | `DayTimeIntervalEncoder` |
| `ProtoType.YearMonthIntervalType` | `YearMonthIntervalEncoder` |
| `ProtoType.ArrayType(e,n)` | `IterableEncoder(Seq, e, n)` |
| `ProtoType.MapType(k,v,n)` | `MapEncoder(Map, k, v, n)` |
| `ProtoType.StructType(fs)` | `ProductEncoder(clsTag, fs)` |

## Key Files

### Spark (sql module)

| Component | Path |
|-----------|------|
| Encoder API | `sql/api/src/main/scala/org/apache/spark/sql/Encoder.scala` |
| AgnosticEncoder | `sql/api/src/main/scala/.../encoders/AgnosticEncoder.scala` |
| ExpressionEncoder | `sql/catalyst/src/main/scala/.../encoders/ExpressionEncoder.scala` |
| SerializerBuildHelper | `sql/catalyst/src/main/scala/.../SerializerBuildHelper.scala` |
| DeserializerBuildHelper | `sql/catalyst/src/main/scala/.../DeserializerBuildHelper.scala` |
| ScalaReflection | `sql/api/src/main/scala/.../catalyst/ScalaReflection.scala` |
| InternalRow | `sql/catalyst/src/main/scala/.../catalyst/InternalRow.scala` |

### ProtoCatalyst

| Component | Path |
|-----------|------|
| ProtoEncoder | `encoder/src/main/scala/protocatalyst/encoder/ProtoEncoder.scala` |
| InlineRowSerializer | `encoder/src/main/scala/protocatalyst/encoder/InlineRowSerializer.scala` |
| ProtoType | `core/src/main/scala/protocatalyst/types/ProtoType.scala` |
| SchemaConverter | `spark/src/main/scala/protocatalyst/spark/SchemaConverter.scala` |
| Encoders (WIP) | `spark/src/main/scala/protocatalyst/spark/Encoders.scala` |

## Parity Testing

ProtoCatalyst uses golden file testing to verify serialization matches Spark's `ExpressionEncoder`:

```
benchmark-spark/           → Generates golden files from Spark
mock-runtime/              → Verifies ProtoCatalyst output matches
```

Current parity coverage (14 types):
- Simple, Address, Person, WithCollections, Team, Directory, Complex
- WithDuration, WithPeriod, WithBigInt, WithBigDecimal
- WithTuple2, WithTuple3, WithTuple5

## Next Steps

1. **Implement `toAgnosticEncoder`**: Convert ProtoEncoder → AgnosticEncoder
2. **Handle nested structs**: Recursive encoder generation
3. **Add deserialization**: InternalRow → T conversion
4. **Integration tests**: Verify with actual Spark Dataset operations
5. **Performance benchmarks**: Compare with native ExpressionEncoder
