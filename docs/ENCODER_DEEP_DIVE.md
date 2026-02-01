# Spark Encoder Deep Dive: From TypeTag to Mirror

## Table of Contents

1. [What is an Encoder?](#1-what-is-an-encoder)
2. [Why Encoders Matter](#2-why-encoders-matter)
3. [Spark's Current Approach (Scala 2 / TypeTag)](#3-sparks-current-approach-scala-2--typetag)
4. [The Mirror-Based Approach (Scala 3)](#4-the-mirror-based-approach-scala-3)
5. [Technical Deep Dive](#5-technical-deep-dive)
6. [Feature Comparison](#6-feature-comparison)
7. [Performance Analysis](#7-performance-analysis)
8. [Migration Path](#8-migration-path)

---

## 1. What is an Encoder?

An **Encoder** is Spark's mechanism for converting between JVM objects (like case classes) and Spark's internal row format (InternalRow/UnsafeRow). It serves three critical purposes:

```
┌─────────────────┐       Encoder        ┌─────────────────┐
│   JVM Object    │ ──────────────────▶  │  InternalRow    │
│  (case class)   │ ◀──────────────────  │  (Spark format) │
└─────────────────┘    serialize /       └─────────────────┘
                      deserialize
```

### Understanding InternalRow

Spark's `InternalRow` is an abstract interface. The most common implementation, `GenericInternalRow`, is simply a wrapper around `Array[Any]`:

```scala
// Spark's GenericInternalRow (simplified)
class GenericInternalRow(val values: Array[Any]) extends InternalRow {
  def numFields: Int = values.length
  def get(ordinal: Int, dataType: DataType): Any = values(ordinal)
  def getInt(ordinal: Int): Int = values(ordinal).asInstanceOf[Int]
  def getLong(ordinal: Int): Long = values(ordinal).asInstanceOf[Long]
  // ...typed getters that cast from the underlying array
}
```

**Key insight**: The actual data lives in `Array[Any]`. The `InternalRow` class just provides typed accessors.

ProtoCatalyst's `InlineRowSerializer` produces exactly what goes inside that array:

```scala
// ProtoCatalyst output
val row: Array[Any] = serializer.serialize(Person("Alice", 30, address))
// row = Array("Alice", 30, Array("123 Main St", "NYC", "10001"))

// To create Spark's InternalRow - just wrap it:
val internalRow = new GenericInternalRow(row)  // Direct compatibility!
```

The internal type representations are also identical:

| External Type | Internal Format | Spark | ProtoCatalyst |
|--------------|-----------------|-------|---------------|
| `String` | UTF-8 bytes | `UTF8String` | `MockUTF8String` |
| `Seq[T]` | Array wrapper | `ArrayData` | `MockArrayData` |
| `Map[K,V]` | Key/value arrays | `MapData` | `MockMapData` |
| Nested struct | Nested row | `InternalRow` | `MockRow` |
| `LocalDate` | `Int` (epoch days) | Same | Same |
| `Instant` | `Long` (microseconds) | Same | Same |

The `InternalTypeConverter` trait provides the pluggable backend - implement `SparkInternalTypeConverter` when integrating with actual Spark code.

### 1.1 The Three Responsibilities

1. **Schema Derivation**: Extract the `StructType` schema from a Scala type
   ```scala
   case class Person(name: String, age: Int)
   // → StructType(StructField("name", StringType), StructField("age", IntegerType))
   ```

2. **Serialization**: Convert objects to Spark's columnar row format
   ```scala
   Person("Alice", 30) → InternalRow(UTF8String("Alice"), 30)
   ```

3. **Deserialization**: Reconstruct objects from row format
   ```scala
   InternalRow(UTF8String("Alice"), 30) → Person("Alice", 30)
   ```

### 1.2 Where Encoders Are Used

```scala
// Dataset creation
spark.createDataset(Seq(Person("Alice", 30)))  // needs Encoder[Person]

// DataFrame operations that return typed data
df.as[Person]                                   // needs Encoder[Person]

// User-defined functions
udf((p: Person) => p.age * 2)                  // needs Encoder[Person], Encoder[Int]

// Aggregations
ds.groupByKey(_.name).mapValues(_.age)         // needs Encoder[String], Encoder[Int]
```

---

## 2. Why Encoders Matter

### 2.1 Performance: The Tungsten Advantage

Spark's Tungsten engine uses off-heap memory with a compact binary format:

```
Standard Java Object (Person):
┌──────────────────────────────────────────────────┐
│ Object Header (16 bytes)                         │
│ name: String reference (8 bytes) ──────┐        │
│ age: int (4 bytes, + 4 padding)        │        │
└──────────────────────────────────────────────────┘
                                          ▼
┌──────────────────────────────────────────────────┐
│ String Object Header (16 bytes)                  │
│ char[] reference (8 bytes) ──────┐              │
│ hash: int (4 bytes)              │              │
└──────────────────────────────────────────────────┘
                                    ▼
┌──────────────────────────────────────────────────┐
│ char[] Header (16 bytes)                         │
│ length (4 bytes)                                 │
│ "Alice" (10 bytes for UTF-16)                    │
└──────────────────────────────────────────────────┘

Total: ~80+ bytes, 3 objects, 2 pointer dereferences


Tungsten UnsafeRow (Person):
┌──────────────────────────────────────────────────┐
│ Null bitmap (8 bytes)                            │
│ name offset+length (8 bytes)                     │
│ age value (8 bytes)                              │
│ "Alice" UTF-8 (5 bytes + padding)                │
└──────────────────────────────────────────────────┘

Total: 32 bytes, 1 contiguous memory block, 0 dereferences
```

**Benefits**:
- 60-80% less memory usage
- Better CPU cache locality
- No garbage collection pressure
- Enables vectorized processing

### 2.2 Type Safety

Without encoders, Spark operates on `Row` objects with runtime type checking:

```scala
// Untyped (DataFrame) - runtime errors
df.select("naem")  // typo not caught until runtime

// Typed (Dataset with Encoder) - compile-time errors
ds.map(p => p.naem)  // compile error: value naem is not a member of Person
```

---

## 3. Spark's Current Approach (Scala 2 / TypeTag)

### 3.1 How It Works

Spark uses Scala 2's `TypeTag` for runtime reflection:

```scala
// User code
import spark.implicits._
case class Person(name: String, age: Int)
val ds = Seq(Person("Alice", 30)).toDS()

// What happens under the hood:
// 1. SQLImplicits provides implicit conversion
implicit def newProductEncoder[T <: Product : TypeTag]: Encoder[T] =
  Encoders.product[T]

// 2. Encoders.product delegates to ScalaReflection
def product[T <: Product : TypeTag]: Encoder[T] =
  ExpressionEncoder(ScalaReflection.encoderFor[T])

// 3. ScalaReflection uses TypeTag to inspect the type at runtime
def encoderFor[E : TypeTag]: AgnosticEncoder[E] = {
  val tpe = typeTag[E].in(mirror).tpe
  // ... runtime reflection to build encoder
}
```

### 3.2 The ScalaReflection.scala Deep Dive

Located at `sql/api/src/main/scala/org/apache/spark/sql/catalyst/ScalaReflection.scala`, this ~1000 line file is the heart of Spark's encoder derivation:

```scala
object ScalaReflection extends ScalaReflection {
  val universe: scala.reflect.runtime.universe.type = scala.reflect.runtime.universe
  val mirror: universe.Mirror = universe.runtimeMirror(getClass.getClassLoader)

  def encoderFor[E : TypeTag]: AgnosticEncoder[E] = {
    encoderFor(typeTag[E].in(mirror).tpe, Set.empty)
  }

  private def encoderFor(
      tpe: Type,
      seenTypeSet: Set[Type]): AgnosticEncoder[_] = {

    // Step 1: Get the dealiased type
    val clsName = getClassNameFromType(tpe)
    val walkedTypePath = WalkedTypePath().recordRoot(clsName)

    // Step 2: Pattern match on type structure
    tpe.dealias match {
      case t if isSubtype(t, localTypeOf[Option[_]]) =>
        val TypeRef(_, _, Seq(optType)) = t
        OptionEncoder(encoderFor(optType, seenTypeSet))

      case t if isSubtype(t, localTypeOf[Product]) =>
        val params = getConstructorParameters(t)  // Uses runtime Symbol API
        val fields = params.map { case (name, fieldType) =>
          val encoder = encoderFor(fieldType, seenTypeSet)
          EncoderField(name, encoder, encoder.nullable, Metadata.empty)
        }
        ProductEncoder(classTag[E], fields, None)

      case t if isSubtype(t, localTypeOf[Seq[_]]) =>
        val TypeRef(_, _, Seq(elementType)) = t
        IterableEncoder(classTag[Seq[_]], encoderFor(elementType, seenTypeSet), ...)

      // ... ~30 more type patterns
    }
  }

  private def getConstructorParameters(tpe: Type): Seq[(String, Type)] = {
    val constructor = tpe.member(termNames.CONSTRUCTOR).asMethod
    constructor.paramLists.flatten.map { param =>
      (param.name.toString, param.typeSignature)
    }
  }
}
```

### 3.3 Problems with the TypeTag Approach

#### Problem 1: Runtime Overhead

Every encoder derivation happens at runtime, even for the same type:

```scala
// Each call to toDS() potentially re-derives the encoder
(1 to 1000).foreach { _ =>
  Seq(Person("Alice", 30)).toDS()  // ScalaReflection.encoderFor runs each time
}
```

**Measured overhead**: 100-700μs per derivation (see benchmarks below).

#### Problem 2: Thread Safety Issues

The `<:<` (subtype check) operator in `scala.reflect.runtime.universe` is **not thread-safe**:

```scala
// From Spark's ScalaReflection.scala - actual workaround code:
private def isSubtype(tpe1: Type, tpe2: Type): Boolean = {
  // Scala's reflection API is not thread-safe, need to synchronize
  ScalaReflection.universe.synchronized {
    tpe1 <:< tpe2
  }
}
```

This synchronization creates a **global lock** that limits parallelism.

#### Problem 3: No Compile-Time Verification

Type errors are only caught at runtime:

```scala
case class BadType(fn: Int => Int)  // Functions can't be serialized

// This compiles successfully:
val ds = spark.createDataset(Seq(BadType(_ + 1)))

// Runtime explosion:
// java.lang.UnsupportedOperationException:
//   No Encoder found for Int => Int
```

#### Problem 4: Limited Type Support

TypeTag cannot introspect certain type patterns:

```scala
// Cannot enumerate subtypes of sealed traits
sealed trait Event
case class Click(x: Int, y: Int) extends Event
case class View(page: String) extends Event

spark.createDataset(Seq[Event](Click(1, 2)))
// Error: Unable to find encoder for type Event
// Spark CANNOT automatically derive encoders for sealed traits
```

#### Problem 5: Scala 3 Incompatibility

`TypeTag` is **removed in Scala 3**. There is no migration path:

```scala
// Scala 2
import scala.reflect.runtime.universe._
def encoderFor[T: TypeTag]: Encoder[T] = ...

// Scala 3 - TypeTag doesn't exist
// This is why Spark cannot fully support Scala 3
```

---

## 4. The Mirror-Based Approach (Scala 3)

### 4.1 Core Concept: Compile-Time Derivation

Scala 3 replaces `TypeTag` with `Mirror`, which enables **compile-time** type inspection:

```scala
import scala.deriving.Mirror

// Mirror.ProductOf provides compile-time type information for case classes
inline def derived[T](using m: Mirror.ProductOf[T]): ProtoEncoder[T] =
  // MirroredElemTypes: Tuple of field types (String, Int) for Person
  // MirroredElemLabels: Tuple of field names ("name", "age") as literal types
  // These are computed at compile time, not runtime!
```

### 4.2 How ProtoCatalyst Implements It

```scala
trait ProtoEncoder[T]:
  def schema: ProtoSchema       // The StructType equivalent
  def catalystType: ProtoType   // The DataType
  def nullable: Boolean         // Is root nullable?
  def clsTag: ClassTag[T]       // For runtime class access
  def fields: Vector[FieldEncoder[?]]     // For products
  def variants: Vector[VariantEncoder[?]] // For sum types

object ProtoEncoder:
  /** Main entry point - dispatches based on Mirror type */
  inline def derived[T](using m: Mirror.Of[T]): ProtoEncoder[T] =
    inline m match
      case p: Mirror.ProductOf[T] =>
        val ct = summonInline[ClassTag[T]]
        deriveProduct[T](p, ct)
      case s: Mirror.SumOf[T] =>
        val ct = summonInline[ClassTag[T]]
        deriveEnum[T](s, ct)
```

### 4.3 Product (Case Class) Derivation

```scala
private inline def deriveProduct[T](m: Mirror.ProductOf[T], ct: ClassTag[T]): ProtoEncoder[T] =
  // Extract field encoders from the Mirror's type information
  val fieldEncoders = deriveFields[m.MirroredElemTypes, m.MirroredElemLabels]
  val structFields = fieldEncoders.map { fe =>
    ProtoStructField(fe.name, fe.encoder.catalystType, fe.nullable)
  }
  makeProductEncoder[T](fieldEncoders, ProtoSchema(structFields), ct)

private inline def deriveFields[Types <: Tuple, Labels <: Tuple]: Vector[FieldEncoder[?]] =
  inline (erasedValue[Types], erasedValue[Labels]) match
    case (_: EmptyTuple, _: EmptyTuple) =>
      Vector.empty
    case (_: (t *: ts), _: (l *: ls)) =>
      val name = constValue[l].toString     // Field name from literal type
      val enc = summonEncoder[t]             // Recursively derive/summon encoder
      FieldEncoder[t](name, enc, enc.nullable) +: deriveFields[ts, ls]
```

**Key insight**: `MirroredElemTypes` and `MirroredElemLabels` are **compile-time tuples**. The `inline` keyword and `constValue` extract information at compile time.

### 4.4 Sum Type (Sealed Trait) Derivation

This is where Mirror **significantly outperforms** TypeTag:

```scala
sealed trait Event derives ProtoEncoder
case class Click(x: Int, y: Int) extends Event
case class View(page: String) extends Event
case object Close extends Event

// Mirror.SumOf provides:
// - MirroredElemTypes: (Click, View, Close.type)
// - MirroredElemLabels: ("Click", "View", "Close")
// - ordinal(value: Event): Int  // Runtime dispatch
```

Implementation:

```scala
private inline def deriveEnum[T](m: Mirror.SumOf[T], ct: ClassTag[T]): ProtoEncoder[T] =
  val variantEncoders = deriveVariants[m.MirroredElemTypes, m.MirroredElemLabels](0)
  val allSingletons = variantEncoders.forall(_.isSingleton)

  if allSingletons then
    // Simple enum (all case objects) → StringType
    makeEnumEncoder[T](ct)
  else
    // Sealed trait with data → SumType
    makeSumEncoder[T](m, variantEncoders, ct)

private inline def deriveVariantEncoder[T](name: String, ordinal: Int): VariantEncoder[T] =
  summonFrom {
    case m: Mirror.ProductOf[T] =>
      inline erasedValue[m.MirroredElemTypes] match
        case _: EmptyTuple =>
          // Zero fields = case object (singleton)
          VariantEncoder[T](name, ordinal, None, isSingleton = true)
        case _ =>
          // Has fields = case class with data
          val enc = derived[T]
          VariantEncoder[T](name, ordinal, Some(enc), isSingleton = false)
    case _ =>
      VariantEncoder[T](name, ordinal, None, isSingleton = true)
  }
```

**Why Spark can't do this with TypeTag**:
- `TypeTag` cannot enumerate the subtypes of a sealed trait
- `TypeTag.tpe.typeSymbol.asClass.knownDirectSubclasses` is unreliable (returns empty before subclasses are compiled)
- No way to get ordinal/dispatch information

### 4.5 Schema Representation

The derived encoder produces a `SumType` schema for sealed traits:

```scala
sealed trait Event
case class Click(x: Int, y: Int) extends Event
case class View(page: String) extends Event
case object Close extends Event

// Resulting schema:
SumType(
  discriminatorField = "_type",
  variants = Vector(
    SumVariant("Click", 0, Some(StructType([("x", IntType), ("y", IntType)]))),
    SumVariant("View", 1, Some(StructType([("page", StringType)]))),
    SumVariant("Close", 2, None)  // case object has no data
  )
)
```

---

## 5. Technical Deep Dive

### 5.1 The `summonFrom` Macro

`summonFrom` is a compile-time pattern matcher for implicit resolution:

```scala
private inline def summonEncoder[T]: ProtoEncoder[T] =
  summonFrom {
    // First: Try to find an existing encoder in scope
    case enc: ProtoEncoder[T] => enc

    // Second: If T is a product (case class), derive it
    case _: Mirror.ProductOf[T] => derived[T]

    // Third: If T is a sum (enum/sealed trait), derive it
    case _: Mirror.SumOf[T] => derived[T]

    // Fourth: Compile error with helpful message
    case _ => error("Cannot find or derive ProtoEncoder...")
  }
```

This replaces TypeTag's runtime pattern matching with compile-time resolution.

### 5.2 The `inline` Keyword's Role

```scala
// WITHOUT inline: Runtime execution
def derived[T](using m: Mirror.Of[T]): ProtoEncoder[T] = ...
// Mirror information computed at runtime (slower)

// WITH inline: Compile-time execution
inline def derived[T](using m: Mirror.Of[T]): ProtoEncoder[T] = ...
// Mirror information inlined at call site (zero runtime cost)
```

The compiler literally copies the method body to each call site, specializing it for the specific type.

### 5.3 Field Name Extraction via Literal Types

```scala
// MirroredElemLabels for Person(name: String, age: Int)
type Labels = ("name", "age")  // Tuple of literal string types

// constValue extracts the string at compile time
inline def deriveFields[Types <: Tuple, Labels <: Tuple]: Vector[FieldEncoder[?]] =
  inline (erasedValue[Types], erasedValue[Labels]) match
    case (_: (t *: ts), _: (l *: ls)) =>
      val name = constValue[l].toString  // "name" extracted at compile time!
```

### 5.4 ClassTag Handling

Spark's `Encoder` requires a `ClassTag[T]` for runtime class information:

```scala
// Spark's Encoder trait
trait Encoder[T] extends Serializable {
  def schema: StructType
  def clsTag: ClassTag[T]  // Required for Dataset operations
}

// Our approach: Summon ClassTag at compile time
inline def derived[T](using m: Mirror.Of[T]): ProtoEncoder[T] =
  inline m match
    case p: Mirror.ProductOf[T] =>
      val ct = summonInline[ClassTag[T]]  // Compile-time summoning
      deriveProduct[T](p, ct)
```

### 5.5 Recursive Type Handling

For nested case classes:

```scala
case class Address(street: String, city: String)
case class Person(name: String, address: Address)

// Derivation flow:
// 1. derived[Person] called
// 2. deriveFields extracts (String, Address)
// 3. summonEncoder[String] returns PrimitiveEncoder
// 4. summonEncoder[Address] recursively calls derived[Address]
// 5. Address encoder returned, Person encoder completed
```

The recursion happens at **compile time**, producing fully specialized code.

---

## 6. Feature Comparison

### 6.1 Type Support Matrix

| Type | Spark (TypeTag) | ProtoCatalyst (Mirror) |
|------|-----------------|------------------------|
| Primitives (Int, Long, etc.) | ✅ | ✅ |
| String, Binary | ✅ | ✅ |
| BigDecimal | ✅ | ✅ |
| java.time types | ✅ | ✅ |
| java.sql types | ✅ | ✅ |
| Option[T] | ✅ | ✅ |
| Seq/List/Vector/Set | ✅ | ✅ |
| Map[K, V] | ✅ | ✅ |
| Array[T] | ✅ | ✅ |
| Case classes | ✅ | ✅ |
| Tuples | ✅ | ✅ |
| Nested structs | ✅ | ✅ |
| Java enums | ✅ | ✅ |
| **Scala 3 enums** | ❌ | ✅ |
| **Sealed traits (ADTs)** | ❌ | ✅ |
| Java Beans | ✅ (runtime) | ✅ (runtime) |
| UDTs | ✅ | ✅ |
| Boxed primitives | ✅ | ✅ |

### 6.2 Capability Comparison

| Capability | Spark (TypeTag) | ProtoCatalyst (Mirror) |
|------------|-----------------|------------------------|
| Derivation timing | Runtime | **Compile-time** |
| Thread safety | Requires synchronization | **Lock-free** |
| Error detection | Runtime | **Compile-time** |
| Scala 3 support | ❌ | ✅ |
| Sum type enumeration | ❌ | ✅ |
| Ordinal dispatch | ❌ | ✅ |
| Zero-overhead derivation | ❌ | ✅ |

### 6.3 API Comparison

**Spark (Scala 2)**:
```scala
import spark.implicits._

case class Person(name: String, age: Int)

// Implicit encoder derivation
val ds1 = Seq(Person("Alice", 30)).toDS()

// Explicit encoder
val encoder: Encoder[Person] = Encoders.product[Person]
val ds2 = spark.createDataset(Seq(Person("Bob", 25)))(encoder)
```

**ProtoCatalyst (Scala 3)**:
```scala
import protocatalyst.encoder.ProtoEncoder

case class Person(name: String, age: Int) derives ProtoEncoder

// Automatic derivation
val encoder = summon[ProtoEncoder[Person]]

// Or explicit
val encoder = ProtoEncoder.derived[Person]

// Sealed traits just work
sealed trait Event derives ProtoEncoder
case class Click(x: Int, y: Int) extends Event
case object Close extends Event

val eventEncoder = summon[ProtoEncoder[Event]]  // Works!
```

---

## 7. Performance Analysis

### 7.1 Schema Derivation Cost

Environment: JDK 21, Apple Silicon, JMH 1.37

| Type | Spark (μs) | ProtoCatalyst |
|------|-----------|---------------|
| Simple | 228 | **0 (compile-time)** |
| Person | 960 | **0 (compile-time)** |
| Team (List[Person]) | 832 | **0 (compile-time)** |

**Why the massive difference?**

- **Proto**: Encoder already exists (compile-time). Zero runtime cost.
- **Spark**: Full reflection pipeline runs every time:
  1. TypeTag materialization
  2. Runtime mirror creation
  3. Type symbol lookup
  4. Constructor parameter extraction
  5. Recursive type analysis
  6. Expression tree generation

### 7.2 Serialization Benchmarks (ns/op, lower is better)

| Type | Spark | ProtoCatalyst | Speedup |
|------|-------|---------------|---------|
| Simple (2 fields) | 26 | 6.8 | **3.8x** |
| Person (nested struct) | 109 | 61.2 | **1.8x** |
| Team (List[Person]) | 654 | 382 | **1.7x** |

### 7.3 Deserialization Benchmarks (ns/op, lower is better)

| Type | Spark | ProtoCatalyst | Speedup |
|------|-------|---------------|---------|
| Simple | 23 | 3.4 | **6.8x** |
| Person | 74 | 8.6 | **8.6x** |
| Team | 546 | 74.6 | **7.3x** |

### 7.4 Roundtrip Benchmarks (ns/op, lower is better)

| Type | Spark | ProtoCatalyst | Speedup |
|------|-------|---------------|---------|
| Simple | 158 | 5.9 | **26.8x** |
| Person | 590 | 60.3 | **9.8x** |
| Team | 1,281 | 390 | **3.3x** |

### 7.5 Scaling Analysis

The speedup holds at larger batch sizes:

| Batch Size | Deserialization Speedup | Serialization Speedup |
|------------|------------------------|----------------------|
| 10 | 49x | 1.6x |
| 100 | 42x | 1.2x |
| 1,000 | 25x | 1.6x |
| 10,000 | 32x | 1.1x |

### 7.7 Why Serialization is Also Faster

Even though serialization happens at runtime, Proto is faster because:

1. **No expression interpretation**: Spark's ExpressionEncoder interprets Catalyst expression trees
2. **Compile-time specialization**: Scala 3 `inline` generates specialized code at compile time
3. **No null-checking overhead**: Compile-time nullability analysis eliminates unnecessary checks
4. **Better inlining**: Simpler code structure allows JIT optimization

### 7.8 Why We Don't Use Runtime Code Generation

Spark uses **Janino** to generate specialized Java bytecode at runtime. We considered this approach but decided against it. See [ADR-001: No Runtime Code Generation](decisions/ADR-001-no-runtime-codegen.md) for the full decision record.

**Key reasons:**

1. **Already faster than Spark**: As shown above, ProtoCatalyst outperforms Spark 1.7-3.8x on serialization and 6.8-8.6x on deserialization without codegen
2. **Scala 3 inline is sufficient**: Compile-time `inline` expansion provides similar benefits to runtime codegen
3. **Simpler codebase**: No Janino dependency, easier debugging, no generated bytecode to inspect
4. **JIT optimizes hot paths**: The `productElement(i)` pattern is optimized by the JVM after warmup

**Comparison of approaches:**

| Aspect | Spark Runtime Codegen | Scala 3 Inline |
|--------|----------------------|----------------|
| When code is generated | First use (runtime) | Compile time |
| Startup overhead | JIT warmup needed | Zero |
| Debugging | Generated bytecode (hard) | Source visible (easy) |
| Dependencies | Janino library | None |

### 7.9 Inline Type Specialization (Implemented)

We've implemented **inline type specialization** in `InlineRowSerializer`, which eliminates runtime type dispatch by generating specialized code for each field type at compile time.

**How it works:**

```scala
// Compile-time type dispatch - generates specialized code per field type
inline def serializeFields[Types <: Tuple](product: Product, arr: Array[Any], idx: Int): Unit =
  inline erasedValue[Types] match
    case _: EmptyTuple => ()
    case _: (Int *: ts) =>
      arr(idx) = product.productElement(idx)  // Known to be Int at compile time
      serializeFields[ts](product, arr, idx + 1)
    case _: (Option[t] *: ts) =>
      // Specialized Option handling - no runtime isInstanceOf check
      product.productElement(idx) match
        case Some(v) => arr(idx) = serializeValue[t](v)
        case None => arr(idx) = null
      serializeFields[ts](product, arr, idx + 1)
```

**Benchmark results (InlineRowSerializer vs generic RowSerializer):**

| Benchmark | Generic (ns) | Inline (ns) | Speedup |
|-----------|-------------|-------------|---------|
| Serialize Simple | 15.1 | 4.1 | **3.7x** |
| Serialize Person (nested) | 23.1 | 6.7 | **3.4x** |
| Serialize Wide (20 fields) | 82.3 | 28.9 | **2.8x** |
| Deserialize Simple | 19.1 | 3.3 | **5.7x** |
| Deserialize Person | 28.8 | 7.4 | **3.9x** |
| Roundtrip Simple | 37.2 | 4.3 | **8.7x** |
| Roundtrip Person | 43.8 | 7.1 | **6.2x** |

`InlineRowSerializer` is now the default implementation used by `RowSerializer.derived`.

---

## 8. Migration Path

### 8.1 For Spark Users (When Spark Adopts Scala 3)

The user-facing API remains compatible:

```scala
// Scala 2 (current)
import spark.implicits._
val ds = Seq(Person("Alice", 30)).toDS()

// Scala 3 (future) - same code, different implementation
import spark.implicits.given
val ds = Seq(Person("Alice", 30)).toDS()
```

### 8.2 For Spark Developers (Porting to Scala 3)

The ProtoCatalyst code will be ported to replace `ScalaReflection.scala`:

```scala
// Current Spark (Scala 2)
object ScalaReflection {
  def encoderFor[E: TypeTag]: AgnosticEncoder[E] = {
    val tpe = typeTag[E].in(mirror).tpe
    // ... runtime reflection
  }
}

// Future Spark (Scala 3, ported from ProtoCatalyst)
object ScalaReflection {
  inline def encoderFor[E](using m: Mirror.Of[E]): AgnosticEncoder[E] =
    inline m match
      case p: Mirror.ProductOf[E] => deriveProductEncoder[E](p)
      case s: Mirror.SumOf[E] => deriveSumEncoder[E](s)
}
```

### 8.3 New Capabilities Enabled

With sealed trait support, patterns that were impossible become trivial:

```scala
// Event sourcing
sealed trait Event derives ProtoEncoder
case class UserCreated(id: String, email: String) extends Event
case class UserDeleted(id: String, reason: Option[String]) extends Event
case object SystemShutdown extends Event

val events: Dataset[Event] = spark.createDataset(Seq(
  UserCreated("u1", "alice@example.com"),
  UserDeleted("u2", Some("inactive")),
  SystemShutdown
))

// Pattern matching in Spark SQL
events.filter {
  case UserCreated(_, email) => email.endsWith("@company.com")
  case _ => false
}
```

---

## Appendix A: Type Flow Diagrams

### A.1 Spark TypeTag Flow (Runtime)

```
User Code                    SQLImplicits                 ScalaReflection
    │                            │                              │
    │ Seq(Person(...)).toDS()    │                              │
    │────────────────────────────▶│                              │
    │                            │ newProductEncoder[T: TypeTag] │
    │                            │──────────────────────────────▶│
    │                            │                              │ TypeTag.tpe
    │                            │                              │ ────────┐
    │                            │                              │         │
    │                            │                              │ ◀───────┘
    │                            │                              │ runtimeMirror
    │                            │                              │ ────────┐
    │                            │                              │         │
    │                            │                              │ ◀───────┘
    │                            │                              │ getConstructorParameters
    │                            │                              │ ────────┐
    │                            │                              │         │
    │                            │                              │ ◀───────┘
    │                            │     AgnosticEncoder[Person]  │
    │                            │◀──────────────────────────────│
    │     Dataset[Person]        │                              │
    │◀────────────────────────────│                              │
```

### A.2 ProtoCatalyst Mirror Flow (Compile-Time)

```
User Code (compile time)          Compiler                    Generated Code
    │                                │                              │
    │ case class Person(...) derives │                              │
    │ ProtoEncoder                   │                              │
    │───────────────────────────────▶│                              │
    │                                │ Mirror.ProductOf[Person]     │
    │                                │ extracted                    │
    │                                │                              │
    │                                │ MirroredElemTypes: (String, Int)
    │                                │ MirroredElemLabels: ("name", "age")
    │                                │                              │
    │                                │ Inline expansion             │
    │                                │─────────────────────────────▶│
    │                                │                              │ val personEncoder =
    │                                │                              │   new ProtoEncoder[Person]:
    │                                │                              │     val catalystType = StructType(...)
    │                                │                              │     val fields = Vector(...)
    │                                │                              │     ...

User Code (runtime)
    │
    │ summon[ProtoEncoder[Person]]
    │───────────────────────────────▶ Returns pre-built instance (0.1μs)
```

---

## Appendix B: Glossary

| Term | Definition |
|------|------------|
| **TypeTag** | Scala 2's mechanism for preserving type information at runtime |
| **Mirror** | Scala 3's compile-time type introspection mechanism |
| **Mirror.ProductOf** | Mirror for product types (case classes, tuples) |
| **Mirror.SumOf** | Mirror for sum types (enums, sealed traits) |
| **MirroredElemTypes** | Tuple type containing all field/variant types |
| **MirroredElemLabels** | Tuple type containing all field/variant names as literal types |
| **inline** | Scala 3 keyword for compile-time method expansion |
| **summonInline** | Compile-time implicit/given resolution |
| **summonFrom** | Compile-time pattern matching on available implicits |
| **constValue** | Extract literal type value at compile time |
| **erasedValue** | Create a phantom value for type-level computation |
| **AgnosticEncoder** | Spark's type-agnostic encoder hierarchy |
| **ExpressionEncoder** | Spark's encoder that uses Catalyst expressions |
| **InternalRow** | Spark's generic row representation |
| **UnsafeRow** | Spark's optimized binary row format |

---

## Appendix C: References

1. [Spark Encoders Documentation](https://spark.apache.org/docs/latest/sql-programming-guide.html#datasets-and-dataframes)
2. [Scala 3 Mirror Documentation](https://docs.scala-lang.org/scala3/reference/contextual/derivation.html)
3. [Spark ScalaReflection.scala](https://github.com/apache/spark/blob/master/sql/api/src/main/scala/org/apache/spark/sql/catalyst/ScalaReflection.scala)
4. [SIP-28: Inline](https://docs.scala-lang.org/sips/inline-meta.html)
5. [Tungsten Memory Format](https://databricks.com/blog/2015/04/28/project-tungsten-bringing-spark-closer-to-bare-metal.html)
