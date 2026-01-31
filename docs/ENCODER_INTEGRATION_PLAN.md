# Spark Encoder Integration Plan

## Executive Summary

This document analyzes Spark's encoder architecture and the Scala 2-specific reflection it depends on, then describes how ProtoCatalyst implementations will be **directly ported into Spark** for Scala 3 compatibility.

**Strategy**: ProtoCatalyst is a development/testing ground. When Spark is ready for Scala 3, the code will be ported directly into Spark's codebase - not used as an external library or bridge.

**Key Insight**: Spark's encoder system has a **two-layer architecture**:
1. **AgnosticEncoder** - Type information layer (similar to our `ProtoEncoder`)
2. **ExpressionEncoder** - Catalyst expression layer (serializer/deserializer generation)

ProtoCatalyst implements the Scala 3 equivalent using Mirror-based derivation. This code will replace Spark's TypeTag-based `ScalaReflection.scala`.

---

## Part 1: Spark's Current Encoder Architecture

### 1.1 Scala 2 Dependencies (What We're Replacing)

| Feature | Spark Usage | Scala 3 Status |
|---------|-------------|----------------|
| `TypeTag[T]` | `Encoders.product[T: TypeTag]` | Removed - use `Mirror` |
| `scala.reflect.runtime.universe` | Schema inference at runtime | Not available - use compile-time |
| Runtime Mirror | `universe.runtimeMirror(classLoader)` | Not available |
| `Type.typeSymbol` | Inspect class structure | Use `Mirror.ProductOf` |
| `Symbol.paramLists` | Get constructor parameters | Use `Mirror.MirroredElemLabels` |
| `tpe1 <:< tpe2` | Subtype checking (thread-unsafe) | Use `summonFrom` |

### 1.2 Key Files in Spark

```
sql/api/src/main/scala/org/apache/spark/sql/
├── Encoder.scala              # Public trait: schema + clsTag
├── Encoders.scala             # Factory: product[T: TypeTag], bean(), etc.
└── catalyst/
    ├── ScalaReflection.scala  # TypeTag → AgnosticEncoder (WILL BE REPLACED)
    └── encoders/
        └── AgnosticEncoder.scala  # Type-agnostic encoder hierarchy

sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/
├── encoders/
│   └── ExpressionEncoder.scala  # Catalyst-specific with Serializer/Deserializer
├── SerializerBuildHelper.scala  # AgnosticEncoder → serializer Expressions
└── DeserializerBuildHelper.scala # AgnosticEncoder → deserializer Expressions
```

### 1.3 Encoder Flow (Current Scala 2)

```
User Code:
  case class Person(name: String, age: Int)
  spark.implicits._
  Seq(Person("Alice", 30)).toDS()  // Uses TypeTag
            │
            ▼
SQLImplicits.newProductEncoder[T: TypeTag]
            │
            ▼
Encoders.product[T: TypeTag]
            │
            ▼
ScalaReflection.encoderFor[E: TypeTag]     ← WILL BE REPLACED
  ├── typeTag[E].in(mirror).tpe            ← TypeTag materialization
  ├── Runtime mirror inspection             ← Runtime reflection
  ├── getConstructorParameters()            ← Symbol API
  └── Recursive field analysis              ← Type API
            │
            ▼
AgnosticEncoder[T]
            │
            ▼
ExpressionEncoder.apply(agnosticEncoder)
            │
            ▼
ExpressionEncoder[T]
```

### 1.4 AgnosticEncoder Hierarchy

```scala
AgnosticEncoder[T]
├── LeafEncoder[T]
│   ├── PrimitiveLeafEncoder (Boolean, Byte, Short, Int, Long, Float, Double)  ✅
│   ├── BoxedLeafEncoder (java.lang.Integer, etc.)  ✅
│   ├── StringEncoder, BinaryEncoder  ✅
│   ├── DateEncoder, TimestampEncoder, InstantEncoder  ✅
│   └── ScalaDecimalEncoder, JavaDecimalEncoder  ✅
├── OptionEncoder[E]  ✅
├── ArrayEncoder[E]  ✅
├── IterableEncoder[C, E]  (Seq, List, Set, etc.)  ✅
├── MapEncoder[C, K, V]  ✅
├── StructEncoder[K]
│   ├── ProductEncoder[K]   ← Case classes  ✅
│   ├── JavaBeanEncoder[K]  ← Java beans  ⏳
│   └── RowEncoder          ← Row type  ⏳
├── EnumEncoder
│   ├── ScalaEnumEncoder  ✅ (Scala 3 via Mirror.SumOf)
│   └── JavaEnumEncoder  ✅ (via type bound E <: Enum[E])
├── UDTEncoder  ⏳
└── TransformingEncoder (Kryo, Java serialization)  ⏳
```

---

## Part 2: ProtoCatalyst Current State

### 2.1 ProtoEncoder (Replaces ScalaReflection.encoderFor)

```scala
trait ProtoEncoder[T]:
  def schema: ProtoSchema
  def catalystType: ProtoType
  def nullable: Boolean
  def clsTag: ClassTag[T]                    // Required by Spark's Encoder
  def fields: Vector[FieldEncoder[?]]

// Derivation using Scala 3 mirrors (NO TypeTag)
object ProtoEncoder:
  inline def derived[T](using m: Mirror.Of[T]): ProtoEncoder[T] =
    inline m match
      case p: Mirror.ProductOf[T] => deriveProduct[T](p, summonInline[ClassTag[T]])
      case s: Mirror.SumOf[T]     => deriveEnum[T](s, summonInline[ClassTag[T]])
```

**Mapping to Spark**:

| Spark AgnosticEncoder | ProtoCatalyst ProtoEncoder |
|-----------------------|---------------------------|
| `ProductEncoder[K](fields, clsTag)` | `makeProductEncoder[T](fields, schema, clsTag)` |
| `EncoderField(name, enc, nullable)` | `FieldEncoder[T](name, encoder, nullable)` |
| `PrimitiveIntEncoder` | `ProtoEncoder[Int]` (given) |
| `StringEncoder` | `ProtoEncoder[String]` (given) |
| `OptionEncoder[E]` | `optionEncoder[T]` (given) |
| `IterableEncoder[C, E]` | `seqEncoder[T]`, etc. (given) |
| `MapEncoder[C, K, V]` | `mapEncoder[K, V]` (given) |
| `ScalaEnumEncoder` | `makeEnumEncoder[T](clsTag)` → StringType |
| `JavaEnumEncoder` | `javaEnumEncoder[E <: Enum[E]]` → StringType |

### 2.2 RowSerializer (InternalRow Serialization)

```scala
trait RowSerializer[T]:
  def schema: Vector[FieldSchema]
  def serialize(value: T)(using InternalTypeConverter): Array[Any]
  def deserialize(row: Array[Any])(using InternalTypeConverter): T
```

### 2.3 UnsafeRowSerializer (Binary Format)

```scala
trait UnsafeRowSerializer[T]:
  def schema: MockDataType.StructType
  def serialize(value: T): MockUnsafeRow
  def deserialize(row: MockUnsafeRow): T
```

---

## Part 3: Gap Analysis

### 3.1 What's Complete

| Feature | ProtoCatalyst Implementation | Status |
|---------|------------------------------|--------|
| Compile-time schema derivation | `ProtoEncoder.derived[T]` using Scala 3 mirrors | ✅ |
| Type system | `ProtoType` hierarchy matching Spark's `DataType` | ✅ |
| Struct schema | `ProtoSchema` with `ProtoStructField` | ✅ |
| Primitive encoders | Boolean, Byte, Short, Int, Long, Float, Double | ✅ |
| String/Binary | `ProtoEncoder[String]`, `ProtoEncoder[Array[Byte]]` | ✅ |
| Temporal types | LocalDate, Instant, LocalDateTime | ✅ |
| Option handling | `OptionEncoder[T]` with nullable=true | ✅ |
| Collections | Seq, List, Vector, Set, Array, Map | ✅ |
| Tuples | Tuple2 through Tuple5 | ✅ |
| Nested structs | Recursive derivation | ✅ |
| Row serialization | `RowSerializer[T]` with InternalTypeConverter | ✅ |
| UnsafeRow format | `UnsafeRowSerializer[T]` with binary layout | ✅ |
| **ClassTag support** | `clsTag: ClassTag[T]` via `summonInline[ClassTag[T]]` | ✅ |
| **Scala 3 enum support** | `Mirror.SumOf[T]` → `StringType` encoding | ✅ |
| **Java enum support** | `E <: Enum[E]` → `StringType` encoding | ✅ |
| **Boxed primitives** | java.lang.Integer, java.lang.Boolean, etc. (nullable) | ✅ |
| **Java BigDecimal/BigInteger** | `java.math.BigDecimal`, `java.math.BigInteger` | ✅ |

### 3.2 What's Needed Before Porting

| Feature | Description | Priority | Status |
|---------|-------------|----------|--------|
| **ClassTag in ProtoEncoder** | Spark's `Encoder` requires `clsTag: ClassTag[T]` | P0 | ✅ Done |
| **Scala 3 enum support** | `Mirror.SumOf[T]` derivation → `StringType` | P2 | ✅ Done |
| **Java enum support** | `E <: Enum[E]` type bound → `StringType` | P2 | ✅ Done |
| **Boxed primitives** | java.lang.Integer, java.lang.Boolean, etc. | P2 | ✅ Done |
| **Java Bean support** | `Encoders.bean(Class[T])` equivalent | P2 | Pending |
| **Additional temporal types** | java.sql.Date, java.sql.Timestamp, Duration | P2 | Pending |
| **UDT support** | UserDefinedType integration | P3 | Pending |
| **Kryo/Java serialization** | TransformingEncoder fallback | P3 | Pending |
| **Lenient serialization** | Multiple input types for dates/timestamps | P3 | Pending |

---

## Part 4: Porting Strategy

### 4.1 How Porting Will Work

When Spark is ready for Scala 3, the following will be ported:

```
ProtoCatalyst                          Apache Spark (Scala 3)
─────────────────────────────────────────────────────────────────────────
ProtoEncoder derivation logic    →     ScalaReflection.scala (rewritten)
  - deriveProduct[T]                     - encoderFor[T] using Mirror
  - deriveFields[Types, Labels]          - field extraction at compile time
  - summonEncoder[T]                     - encoder summoning

ProtoType                        →     Works alongside existing DataType
  - Type mapping logic                   - Conversion utilities

RowSerializer                    →     ExpressionEncoder internals
  - serialize/deserialize                - InternalRow conversion

UnsafeRowSerializer              →     UnsafeRow codegen paths
  - Binary format handling               - Tungsten memory format
```

### 4.2 ScalaReflection.scala Replacement

Current Spark (Scala 2):
```scala
object ScalaReflection {
  def encoderFor[E: TypeTag]: AgnosticEncoder[E] = {
    val tpe = typeTag[E].in(mirror).tpe
    // ... runtime reflection to build encoder
  }
}
```

After porting (Scala 3):
```scala
object ScalaReflection {
  // New Scala 3 API using Mirror
  inline def encoderFor[E](using m: Mirror.Of[E]): AgnosticEncoder[E] =
    inline m match
      case p: Mirror.ProductOf[E] => deriveProductEncoder[E](p)
      case s: Mirror.SumOf[E] => deriveEnumEncoder[E](s)

  private inline def deriveProductEncoder[E](m: Mirror.ProductOf[E]): ProductEncoder[E] =
    // Port from ProtoEncoder.deriveProduct
    val fields = deriveFields[m.MirroredElemTypes, m.MirroredElemLabels]
    ProductEncoder(classTag[E], fields, None)
}
```

### 4.3 Encoders.scala Changes

Current Spark (Scala 2):
```scala
object Encoders {
  def product[T <: Product: TypeTag]: Encoder[T] =
    ExpressionEncoder(ScalaReflection.encoderFor[T])
}
```

After porting (Scala 3):
```scala
object Encoders {
  // Deprecated Scala 2 API (if cross-compiling)
  @deprecated("Use derived instead", "4.0")
  def product[T <: Product: TypeTag]: Encoder[T] = ???

  // New Scala 3 API
  inline def product[T <: Product](using Mirror.ProductOf[T]): Encoder[T] =
    ExpressionEncoder(ScalaReflection.encoderFor[T])
}
```

### 4.4 SQLImplicits Changes

Current Spark (Scala 2):
```scala
trait SQLImplicits {
  implicit def newProductEncoder[T <: Product: TypeTag]: Encoder[T] =
    Encoders.product[T]
}
```

After porting (Scala 3):
```scala
trait SQLImplicits {
  // New Scala 3 given
  inline given productEncoder[T <: Product](using Mirror.ProductOf[T]): Encoder[T] =
    Encoders.product[T]
}
```

---

## Part 5: Type Mapping

### 5.1 ProtoType → Spark DataType

| ProtoType | Spark DataType | Notes |
|-----------|----------------|-------|
| `ProtoType.BooleanType` | `BooleanType` | Direct |
| `ProtoType.ByteType` | `ByteType` | Direct |
| `ProtoType.ShortType` | `ShortType` | Direct |
| `ProtoType.IntType` | `IntegerType` | Name differs |
| `ProtoType.LongType` | `LongType` | Direct |
| `ProtoType.FloatType` | `FloatType` | Direct |
| `ProtoType.DoubleType` | `DoubleType` | Direct |
| `ProtoType.StringType` | `StringType` | Direct |
| `ProtoType.BinaryType` | `BinaryType` | Direct |
| `ProtoType.DateType` | `DateType` | Direct |
| `ProtoType.TimestampType` | `TimestampType` | Direct |
| `ProtoType.TimestampNTZType` | `TimestampNTZType` | Direct |
| `ProtoType.DecimalType(p, s)` | `DecimalType(p, s)` | Direct |
| `ProtoType.ArrayType(e, n)` | `ArrayType(e, n)` | Direct |
| `ProtoType.MapType(k, v, n)` | `MapType(k, v, n)` | Direct |
| `ProtoType.StructType(fields)` | `StructType(fields)` | Direct |

### 5.2 Encoder Mapping

| Spark AgnosticEncoder | ProtoCatalyst Equivalent |
|-----------------------|--------------------------|
| `PrimitiveBooleanEncoder` | `ProtoEncoder[Boolean]` |
| `PrimitiveIntEncoder` | `ProtoEncoder[Int]` |
| `StringEncoder` | `ProtoEncoder[String]` |
| `BinaryEncoder` | `ProtoEncoder[Array[Byte]]` |
| `STRICT_LOCAL_DATE_ENCODER` | `ProtoEncoder[LocalDate]` |
| `STRICT_INSTANT_ENCODER` | `ProtoEncoder[Instant]` |
| `OptionEncoder[E]` | `optionEncoder[T]` |
| `IterableEncoder[Seq, E]` | `seqEncoder[T]` |
| `MapEncoder[Map, K, V]` | `mapEncoder[K, V]` |
| `ProductEncoder[K]` | `ProtoEncoder.derived[T]` (Mirror.ProductOf) |
| `ScalaEnumEncoder` | `ProtoEncoder.derived[T]` (Mirror.SumOf → StringType) |
| `JavaEnumEncoder` | `javaEnumEncoder[E <: Enum[E]]` → StringType |
| `BoxedBooleanEncoder` | `boxedBooleanEncoder` → BooleanType (nullable) |
| `BoxedIntEncoder` | `boxedIntEncoder` → IntType (nullable) |
| `BoxedLongEncoder` | `boxedLongEncoder` → LongType (nullable) |
| etc. | All boxed primitives → same type as unboxed (nullable) |

---

## Part 6: Implementation Roadmap

### Phase 1: Core Encoder Infrastructure ✅ COMPLETE

1. **ClassTag in ProtoEncoder** ✅
   ```scala
   trait ProtoEncoder[T]:
     def schema: ProtoSchema
     def catalystType: ProtoType
     def nullable: Boolean
     def clsTag: ClassTag[T]  // Captured via summonInline[ClassTag[T]]
   ```

2. **Scala 3 enum support** ✅
   ```scala
   inline def derived[T](using m: Mirror.Of[T]): ProtoEncoder[T] =
     inline m match
       case p: Mirror.ProductOf[T] => deriveProduct[T](p, ct)
       case s: Mirror.SumOf[T]     => deriveEnum[T](s, ct)  // Enums → StringType
   ```

3. **Type coverage verification** ✅
   - Primitives, temporal types, collections, tuples, nested structs
   - 74 encoder tests passing

4. **Java enum support** ✅
   ```scala
   given javaEnumEncoder[E <: java.lang.Enum[E]](using ct: ClassTag[E]): ProtoEncoder[E] =
     JavaEnumEncoder(ct)  // Stores as StringType
   ```

5. **Boxed primitives** ✅
   ```scala
   given boxedIntEncoder: ProtoEncoder[java.lang.Integer] =
     BoxedPrimitiveEncoder(ProtoType.IntType, classTag[java.lang.Integer])
   // nullable = true for all boxed types
   // Also: java.math.BigDecimal, java.math.BigInteger
   ```

### Phase 2: Extended Type Support (Current)

6. **Additional temporal types** (P2)
   - java.sql.Date, java.sql.Timestamp (legacy compatibility)
   - java.time.Duration, java.time.Period

7. **Java Bean support** (P2)
   - Runtime introspection for Java classes
   - Generate encoder without compile-time derivation

### Phase 3: Port to Spark

8. **When Spark Scala 3 is ready**
   - Copy ProtoEncoder derivation logic to ScalaReflection.scala
   - Adapt to use Spark's AgnosticEncoder types directly
   - Update Encoders.scala and SQLImplicits
   - Run Spark's existing encoder test suite

---

## Part 7: API Compatibility

### 7.1 User-Facing Changes

**Scala 2 (Current)**:
```scala
import spark.implicits._
case class Person(name: String, age: Int)
val ds = Seq(Person("Alice", 30)).toDS()  // Uses TypeTag
```

**Scala 3 (After Porting)**:
```scala
import spark.implicits.given
case class Person(name: String, age: Int)
val ds = Seq(Person("Alice", 30)).toDS()  // Uses Mirror (automatic)
```

For most users, the change is **transparent** - `toDS()` will work without any code changes. The `derives` clause is optional for case classes (Mirror is auto-derived).

### 7.2 Explicit Encoder Derivation

If users need explicit encoders:

```scala
// Scala 2
implicit val personEncoder: Encoder[Person] = Encoders.product[Person]

// Scala 3
given personEncoder: Encoder[Person] = Encoders.product[Person]
// Or with derives:
case class Person(name: String, age: Int) derives Encoder
```

---

## Part 8: Testing Strategy

### 8.1 ProtoCatalyst Tests (Current)

- Unit tests for each encoder type
- Roundtrip serialization tests
- Schema generation validation
- UnsafeRow binary format correctness

### 8.2 Spark Compatibility Tests (After Porting)

- Run Spark's existing `EncoderSuite` with new implementation
- Compare schema output with Scala 2 version
- Verify serialization produces identical `InternalRow` data
- Performance benchmarks vs. Scala 2 encoders

---

## Part 9: Risk Assessment

| Risk | Impact | Mitigation |
|------|--------|------------|
| Spark 4.0 Scala 3 timeline uncertain | Blocker | ProtoCatalyst is standalone useful |
| Semantic differences in Mirror vs TypeTag | Medium | Comprehensive testing |
| Performance regression | Medium | Benchmark early, profile hotpaths |
| Edge cases in nested/complex types | Medium | Extensive test coverage |

---

## Appendix: Spark's Expression Patterns (Reference)

When porting RowSerializer logic, these are the Catalyst expressions used:

### A.1 Serializer Expression Examples

```scala
// For case class Person(name: String, age: Int)
CreateNamedStruct(
  Literal("name"),
  Invoke(
    BoundReference(0, ObjectType(classOf[Person]), false),
    "name",
    StringType
  ),
  Literal("age"),
  Invoke(
    BoundReference(0, ObjectType(classOf[Person]), false),
    "age",
    IntegerType
  )
)
```

### A.2 Deserializer Expression Examples

```scala
// For case class Person(name: String, age: Int)
NewInstance(
  classOf[Person],
  Seq(
    GetStructField(BoundReference(0, schema, false), 0),  // name
    GetStructField(BoundReference(0, schema, false), 1)   // age
  ),
  ObjectType(classOf[Person])
)
```

### A.3 Option Handling

```scala
// Serializer: Option → nullable
If(
  Invoke(input, "isEmpty", BooleanType),
  Literal(null, StringType),
  Invoke(Invoke(input, "get", ObjectType(classOf[String])), ...)
)

// Deserializer: nullable → Option
If(
  IsNull(input),
  Literal(None, ObjectType(classOf[Option[String]])),
  NewInstance(classOf[Some[String]], Seq(input), ...)
)
```
