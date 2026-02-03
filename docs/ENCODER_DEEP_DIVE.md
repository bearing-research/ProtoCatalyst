# Understanding Encoders: A Beginner's Guide

This guide explains what encoders are, why they matter, and how ProtoCatalyst implements them using Scala 3. No prior knowledge of Spark or serialization is assumed.

## Table of Contents

1. [The Problem: Objects vs. Tabular Data](#1-the-problem-objects-vs-tabular-data)
2. [What is an Encoder?](#2-what-is-an-encoder)
3. [Why Encoders Matter](#3-why-encoders-matter)
4. [Getting Started with ProtoCatalyst](#4-getting-started-with-protocatalyst)
5. [How It Works Under the Hood](#5-how-it-works-under-the-hood)
6. [Advanced Topics](#6-advanced-topics)
7. [Spark's Approach vs ProtoCatalyst](#7-sparks-approach-vs-protocatalyst)
8. [Performance Analysis](#8-performance-analysis)
9. [Glossary](#9-glossary)

---

## 1. The Problem: Objects vs. Tabular Data

### The Two Worlds

When you work with data, you typically deal with two different representations:

**1. Objects in your code** - Structured types that are easy to work with:

```scala
case class Person(name: String, age: Int)
val alice = Person("Alice", 30)

// Easy to use in code
println(alice.name)  // "Alice"
println(alice.age)   // 30
```

**2. Tabular data** - Rows and columns for storage and processing:

```
┌─────────┬─────┐
│  name   │ age │
├─────────┼─────┤
│ "Alice" │ 30  │
│ "Bob"   │ 25  │
└─────────┴─────┘
```

The challenge: **How do you convert between these two formats efficiently?**

### Why This Matters

Data processing frameworks like Apache Spark store data in a tabular format called "rows" internally. This format is:
- Compact (uses less memory)
- Cache-friendly (data is contiguous)
- Optimized for columnar operations

But when you write application code, you want to use nice case classes, not raw arrays. You need something to bridge these two worlds.

### A Simple Example

Imagine you have a million `Person` objects to process:

```scala
val people: List[Person] = loadMillionPeople()

// You want to filter people over 18
val adults = people.filter(_.age >= 18)
```

Behind the scenes, a data processing engine needs to:
1. **Serialize**: Convert each `Person` → row format (for efficient storage)
2. **Process**: Filter the rows
3. **Deserialize**: Convert rows → `Person` objects (for your code to use)

This conversion happens billions of times in real applications. **Efficiency matters.**

---

## 2. What is an Encoder?

An **Encoder** is the component that converts between objects and row format. In Spark, this conversion layer is implemented by Catalyst's `ExpressionEncoder`, which translates JVM objects to Spark's internal row format. ProtoCatalyst provides a faster, compile-time alternative.

```
┌─────────────────┐       Encoder        ┌─────────────────┐
│   Your Object   │ ─────────────────▶   │   Row Format    │
│  Person("Alice",│    serialize()       │  ["Alice", 30]  │
│           30)   │ ◀─────────────────   │                 │
└─────────────────┘   deserialize()      └─────────────────┘
```

> **Note for Spark users**: Throughout this guide, we use `Array[Any]` as a conceptual stand-in for Spark's internal row representations (`InternalRow`, `UnsafeRow`). The ideas are the same even though the concrete data structures differ.

### The Three Jobs of an Encoder

1. **Schema Discovery** - Figure out what fields a type has
   ```scala
   Person has: name (String), age (Int)
   // → Schema: [("name", StringType), ("age", IntegerType)]
   ```

2. **Serialization** - Convert object → row
   ```scala
   Person("Alice", 30) → Array("Alice", 30)
   ```

3. **Deserialization** - Convert row → object
   ```scala
   Array("Alice", 30) → Person("Alice", 30)
   ```

> **Schema vs Encoder**: A *schema* describes what the data looks like (field names and types); an *encoder* describes how to move data between representations. ProtoCatalyst derives both from the same type information.

### Why Not Just Use JSON?

You might wonder: "Why not serialize to JSON or use Java serialization?"

The problem is **performance**:

| Format | Size for Person | Parse Time |
|--------|-----------------|------------|
| JSON | ~25 bytes | Slow (string parsing) |
| Java Serialization | ~200 bytes | Medium |
| **Row format** | **~13 bytes** | **Fast (direct memory access)** |

When processing billions of rows, the difference is massive.

---

## 3. Why Encoders Matter

### 3.1 Performance: Memory Efficiency

Let's see why row format is so much more efficient than regular Java objects.

**A Java Object uses a lot of hidden memory:**

```
Person("Alice", 30) in memory:
┌────────────────────────────────────────────┐
│ Person object header (16 bytes)            │
│ name: pointer to String (8 bytes)    ─────┼──┐
│ age: int (4 bytes) + padding (4 bytes)     │  │
└────────────────────────────────────────────┘  │
                                                │
        ┌───────────────────────────────────────┘
        ▼
┌────────────────────────────────────────────┐
│ String object header (16 bytes)            │
│ value: pointer to char[] (8 bytes)   ─────┼──┐
│ hash: int (4 bytes) + padding (4 bytes)    │  │
└────────────────────────────────────────────┘  │
                                                │
        ┌───────────────────────────────────────┘
        ▼
┌────────────────────────────────────────────┐
│ char[] header (16 bytes)                   │
│ length (4 bytes)                           │
│ "Alice" data (10 bytes in UTF-16)          │
└────────────────────────────────────────────┘

Total: ~80+ bytes scattered across 3 objects
```

**Row format is compact and contiguous:**

```
Person as Row:
┌────────────────────────────────────────────┐
│ ["Alice", 30]                              │
│ ├─ "Alice" in UTF-8 (5 bytes)              │
│ └─ 30 as integer (4 bytes)                 │
└────────────────────────────────────────────┘

Total: ~13 bytes in one contiguous block
```

> *Note: Spark's actual `UnsafeRow` layout includes offsets, length prefixes, and null bitmaps. The example focuses on payload size to illustrate the core benefit—real rows have slightly more overhead but remain far more compact than Java objects.*

**Why this matters:**
- **Less memory**: 5-6x reduction means more data fits in RAM
- **Faster access**: No pointer chasing, data is right next to each other
- **Better cache usage**: CPU caches work on contiguous memory blocks
- **Less garbage collection**: Fewer objects = less GC overhead

### 3.2 Type Safety

Encoders also provide compile-time type checking:

```scala
// Without encoder - errors happen at runtime (bad!)
val row: Array[Any] = Array("Alice", 30)
val name = row(0).asInstanceOf[String]  // What if it's not a String?
val age = row(1).asInstanceOf[Int]      // Runtime crash if wrong type

// With encoder - errors caught at compile time (good!)
case class Person(name: String, age: Int)
val person: Person = deserialize(row)   // Compiler knows the types
println(person.name)                     // Definitely a String
println(person.age)                      // Definitely an Int
```

---

## 4. Getting Started with ProtoCatalyst

### 4.1 Serialization (The Main Use Case)

Here's the simplest way to use ProtoCatalyst:

```scala
import protocatalyst.encoder.*

// Step 1: Define a plain case class
case class Person(name: String, age: Int)

// Step 2: Create a serializer (generated at compile time!)
val serializer = InlineRowSerializer.derived[Person]

// Step 3: Serialize and deserialize
val alice = Person("Alice", 30)
val row: Array[Any] = serializer.serialize(alice)
// row = Array("Alice", 30)

val restored: Person = serializer.deserialize(row)
// restored = Person("Alice", 30)

assert(alice == restored)  // They're equal!
```

That's it! `InlineRowSerializer.derived[T]` generates a specialized serializer at compile time. No annotations or `derives` clauses needed on your case class.

### 4.2 Nested Types

Serializers handle nested case classes automatically:

```scala
case class Address(street: String, city: String)
case class Person(name: String, address: Address)

val serializer = InlineRowSerializer.derived[Person]
val alice = Person("Alice", Address("123 Main St", "NYC"))

val row = serializer.serialize(alice)
// row = Array("Alice", Array("123 Main St", "NYC"))
//                      ^^^^^^^^^^^^^^^^^^^^^^^^^^^^
//                      Nested struct becomes nested array

val restored = serializer.deserialize(row)
assert(restored == alice)
```

### 4.3 Collections

Lists, Sets, Maps, and Arrays all work:

```scala
case class Team(name: String, members: List[String])

val serializer = InlineRowSerializer.derived[Team]
val team = Team("Engineers", List("Alice", "Bob", "Charlie"))

val row = serializer.serialize(team)
// row = Array("Engineers", List("Alice", "Bob", "Charlie"))
```

### 4.4 Optional Values

Use `Option[T]` for fields that might be missing:

```scala
case class User(name: String, email: Option[String])

val serializer = InlineRowSerializer.derived[User]

// With email
val user1 = User("Alice", Some("alice@example.com"))
val row1 = serializer.serialize(user1)
// row1 = Array("Alice", "alice@example.com")

// Without email
val user2 = User("Bob", None)
val row2 = serializer.serialize(user2)
// row2 = Array("Bob", null)
```

### 4.5 Schema Inspection

If you need to inspect the schema (field names and types) without serializing:

```scala
case class Person(name: String, age: Int)

val encoder = ProtoEncoder.derived[Person]
println(encoder.schema)
// Output: Vector(ProtoStructField(name,StringType,false), ProtoStructField(age,IntegerType,false))
```

> **Note**: `ProtoEncoder` provides schema information only. `InlineRowSerializer` provides serialization. They are independent—use whichever you need.

### 4.6 Enums and Sealed Traits

ProtoCatalyst handles Scala 3 enums and sealed traits (sum types):

```scala
// Simple enum
enum Color:
  case Red, Green, Blue

// Sealed trait with data
sealed trait Event
case class Click(x: Int, y: Int) extends Event
case class View(page: String) extends Event
case object Close extends Event

// Both can be serialized
val colorSerializer = InlineRowSerializer.derived[Color]
val eventSerializer = InlineSumRowSerializer.derived[Event]
```

---

## 5. How It Works Under the Hood

### 5.1 Compile-Time Derivation

When you call `InlineRowSerializer.derived[Person]`, the Scala 3 compiler:

1. **Analyzes the type at compile time** - Finds all fields and their types
2. **Generates specialized code** - Creates a custom serializer for `Person`
3. **Inlines the code at the call site** - No virtual dispatch, no reflection

This all happens **before your program runs**. Zero runtime overhead for type discovery.

> **Alternative: The `derives` clause**
>
> You can also write `case class Person(...) derives InlineRowSerializer` to create a given instance automatically. Then use `summon[InlineRowSerializer[Person]]` to access it. This is useful when you want the serializer available implicitly throughout your codebase.

### 5.2 Scala 3's Mirror System

Scala 3 provides a mechanism called `Mirror` that gives compile-time access to type information. You can think of Mirror as the compiler handing you a compile-time AST of your data type, with field names and types already extracted.

```scala
import scala.deriving.Mirror

// For case class Person(name: String, age: Int):
// The compiler provides a Mirror.ProductOf[Person] with:
//   - MirroredElemTypes = (String, Int)    // The field types
//   - MirroredElemLabels = ("name", "age") // The field names
```

ProtoCatalyst uses this Mirror information to generate encoders:

```scala
inline def derived[T](using m: Mirror.ProductOf[T]): ProtoEncoder[T] =
  // m.MirroredElemTypes tells us the field types
  // m.MirroredElemLabels tells us the field names
  // All resolved at compile time!
```

### 5.3 The Inline Keyword

The `inline` keyword is crucial. It tells the compiler to:
1. Copy the method body to each call site
2. Specialize the code for the specific type
3. Eliminate runtime type checks

```scala
// Without inline: Runtime type checking
def serialize(value: Any, dataType: Type): Any = dataType match
  case IntegerType => value        // Checked at runtime for every call
  case StringType => convert(value)
  // ... many more cases

// With inline: Compile-time type checking
inline def serialize[T](value: T): Any = inline erasedValue[T] match
  case _: Int => value         // Compiler knows it's Int, no runtime check
  case _: String => convert(value)
  // ... specialized code generated per type
```

### 5.4 Type Representations

When serializing, certain types need conversion to their "internal" format:

| Your Type | Internal Format | Why |
|-----------|-----------------|-----|
| `String` | `UTF8String` | UTF-8 is more compact than UTF-16 |
| `LocalDate` | `Int` | Days since 1970-01-01 |
| `Instant` | `Long` | Microseconds since 1970-01-01 |
| `Duration` | `Long` | Microseconds (DayTimeIntervalType) |
| `Period` | `Int` | Total months (YearMonthIntervalType) |
| `BigInt` | `Decimal` | DecimalType(38,0) |
| `BigDecimal` | `Decimal` | DecimalType(38,18) |
| `List[T]` | `ArrayData` | Optimized array wrapper |
| Nested case class | Nested `Array[Any]` | Recursive structure |

The `InternalTypeConverter` trait handles these conversions:

```scala
// Converting String → UTF8String (internal format)
def toInternal(value: Any, dataType: ProtoType): Any = dataType match
  case StringType => UTF8String.fromString(value.asInstanceOf[String])
  case DateType => value.asInstanceOf[LocalDate].toEpochDay.toInt
  // ...
```

> *Note: This pattern match is a conceptual API boundary for pluggable backends. In practice, ProtoCatalyst's `inline` serializers specialize these conversions at compile time, so the match does not appear in generated code for concrete types.*

---

## 6. Advanced Topics

### 6.1 Custom Types (UDT)

For types that don't have automatic encoding, create a User-Defined Type:

```scala
// Your custom type
case class Point(x: Double, y: Double)

// Define how to encode it
object PointUDT extends ProtoUDT[Point]:
  def sqlType = ProtoType.ArrayType(ProtoType.DoubleType, containsNull = false)
  def serialize(p: Point): Any = Array(p.x, p.y)
  def deserialize(datum: Any): Point =
    val arr = datum.asInstanceOf[Array[Double]]
    Point(arr(0), arr(1))
  def userClass = classOf[Point]

// Use it
val encoder = ProtoEncoder.fromUDT(PointUDT)
```

### 6.2 Java Beans

For Java classes with getter/setter patterns:

```scala
// Java class with getName/getAge methods
val encoder = JavaBeanEncoder(classOf[JavaPerson])
```

### 6.3 Fallback Encoding

For third-party types you can't modify:

```scala
// Uses Java serialization (slower but works with anything)
val encoder = TransformingEncoder.java[ThirdPartyClass]

// Or Kryo (faster)
val encoder = TransformingEncoder.kryo[ThirdPartyClass]
```

---

## 7. Spark's Approach vs ProtoCatalyst

This section is for readers who want to understand the technical differences between Spark's current encoder implementation and ProtoCatalyst.

### 7.1 How Spark Does It (Scala 2)

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

### 7.2 Problems with Spark's TypeTag Approach

| Problem | Description |
|---------|-------------|
| **Runtime overhead** | Encoder derivation happens at runtime, taking 100-700μs each time |
| **Not thread-safe** | Requires global synchronization, limiting parallelism |
| **Runtime errors** | Type errors only caught when code runs |
| **Can't do sealed traits** | `TypeTag` cannot enumerate subtypes of sealed traits |
| **Scala 3 incompatible** | `TypeTag` doesn't exist in Scala 3 |

### 7.3 How ProtoCatalyst Does It (Scala 3)

ProtoCatalyst uses Scala 3's `Mirror` for compile-time derivation:

```scala
import scala.deriving.Mirror

inline def derived[T](using m: Mirror.ProductOf[T]): ProtoEncoder[T] =
  // m.MirroredElemTypes = (String, Int) for Person
  // m.MirroredElemLabels = ("name", "age")
  // All computed at compile time!
```

**Key differences:**

| Aspect | Spark (TypeTag) | ProtoCatalyst (Mirror) |
|--------|-----------------|------------------------|
| When | Runtime | Compile-time |
| Cost | 100-700μs per derivation | 0 (already done) |
| Thread safety | Synchronized | Lock-free |
| Error timing | Runtime | Compile-time |
| Sealed traits | ❌ | ✅ |
| Scala 3 enums | ❌ | ✅ |

### 7.4 Supported Types Comparison

| Type | Spark | ProtoCatalyst |
|------|-------|---------------|
| Primitives (Int, Long, etc.) | ✅ | ✅ |
| String, Binary | ✅ | ✅ |
| BigDecimal, BigInt | ✅ | ✅ |
| java.time.LocalDate, Instant | ✅ | ✅ |
| java.time.Duration (DayTimeInterval) | ✅ | ✅ |
| java.time.Period (YearMonthInterval) | ✅ | ✅ |
| Option[T] | ✅ | ✅ |
| Collections (List, Map, etc.) | ✅ | ✅ |
| Tuples (Tuple2-Tuple22) | ✅ | ✅ |
| Case classes | ✅ | ✅ |
| Nested structs | ✅ | ✅ |
| Java enums | ✅ | ✅ |
| **Scala 3 enums** | ❌ | ✅ |
| **Sealed traits (ADTs)** | ❌ | ✅ |

---

## 8. Performance Analysis

All benchmarks run on JDK 21, Apple Silicon, using JMH 1.37.

### 8.1 Schema Derivation Cost

| Type | Spark (μs) | ProtoCatalyst |
|------|-----------|---------------|
| Simple (2 fields) | 228 | **0 (compile-time)** |
| Person (nested) | 960 | **0 (compile-time)** |
| Team (List[Person]) | 832 | **0 (compile-time)** |

**Why the difference?** ProtoCatalyst's encoder already exists at compile time. Spark must analyze the type structure at runtime every time.

### 8.2 Serialization and Deserialization (ns/op, lower is better)

| Operation | Type | Spark | ProtoCatalyst | Speedup |
|-----------|------|-------|---------------|---------|
| Serialize | Simple | 26 | 6.8 | **3.8x** |
| Serialize | Person | 109 | 61.2 | **1.8x** |
| Deserialize | Simple | 23 | 3.4 | **6.8x** |
| Deserialize | Person | 74 | 8.6 | **8.6x** |
| Roundtrip | Simple | 158 | 5.9 | **27x** |
| Roundtrip | Person | 590 | 60.3 | **10x** |

### 8.3 Why ProtoCatalyst is Faster

1. **No runtime type dispatch**: Code is specialized at compile time
2. **No runtime expression evaluation**: Spark builds Catalyst expression trees and may interpret or JIT-compile them at runtime; ProtoCatalyst emits direct, specialized JVM code at compile time
3. **Better JIT optimization**: Simpler code structure

### 8.4 Batch Processing Scaling

The speedup holds at larger batch sizes:

| Batch Size | Deserialize Speedup |
|------------|---------------------|
| 10 | 49x |
| 100 | 42x |
| 1,000 | 25x |
| 10,000 | 32x |

---

## 9. Glossary

Terms used in this document:

| Term | Definition |
|------|------------|
| **Encoder** | Component that converts between objects and row format |
| **Serialization** | Converting an object to row format |
| **Deserialization** | Converting row format back to an object |
| **Schema** | Description of field names and types |
| **Case class** | Scala's immutable data class (like `case class Person(name: String, age: Int)`) |
| **Row format** | Compact representation as `Array[Any]` |
| **TypeTag** | Scala 2's mechanism for runtime type information |
| **Mirror** | Scala 3's mechanism for compile-time type information |
| **inline** | Scala 3 keyword that expands code at compile time |
| **derives** | Scala 3 keyword for automatic typeclass derivation |
| **Product** | Scala term for case classes and tuples |
| **Sum type** | Sealed trait with variants (like `sealed trait Event`) |
| **ADT** | Algebraic Data Type - sum types and product types together |
| **InternalRow** | Spark's row representation |
| **UTF8String** | Spark's internal string representation (UTF-8 encoded) |

---

## Further Reading

- [Spark Migration Guide](SPARK_MIGRATION_GUIDE.md) - Integrating with Spark
- [ADR-001: No Runtime Codegen](decisions/ADR-001-no-runtime-codegen.md) - Design decision rationale
- [Scala 3 Derivation](https://docs.scala-lang.org/scala3/reference/contextual/derivation.html) - Official docs on `derives`
- [Tungsten Memory Format](https://databricks.com/blog/2015/04/28/project-tungsten-bringing-spark-closer-to-bare-metal.html) - Why row format matters
