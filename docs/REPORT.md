# Compile-Time Encoders for Spark: A Scala 3 Path Forward

A technical report on replacing Spark's `ExpressionEncoder` with a
compile-time, macro-derived encoder. We show that the replacement is
byte-compatible with Spark's existing `UnsafeRow` output, faster on
every operation we measure, and — critically — works in Scala 3 today.

The report is structured as a case study with a worked implementation:
every claim is backed by code in this repository, every number is
reproducible via the one-command harness in
[`scripts/bench.sh`](../scripts/bench.sh).

---

# Part 1 — Concepts and motivation

## §1. The problem statement

Apache Spark's typed `Dataset[T]` API is, on the workloads where users
choose it, **3–5× slower than the untyped `DataFrame` API** (measured
at TPC-H SF=1; precise figures in §12). The gap is the cost of
`ExpressionEncoder` — the component that translates between JVM
case-class instances and Spark's internal `UnsafeRow` byte layout.
Every typed lambda forces a per-row encoder pass.

There is a second, structural problem with the same root cause:
`ExpressionEncoder` is built on Scala 2.13's `TypeTag` runtime
reflection, which doesn't function from Scala 3. Spark itself is
therefore locked to Scala 2.13 — not because the rest of the codebase
couldn't migrate, but because the encoder forces it. Any community
attempt to use Spark from Scala 3 hits this wall.

Both problems share a solution: replace the runtime-reflection
derivation with a compile-time macro. The per-row cost drops because
the JIT sees specialized, monomorphic code instead of megamorphic
dispatch through Catalyst's `DataType` machinery. The Scala 3 lock
comes down because no runtime reflection is needed at all — the
encoder is generated at `scalac` time, exactly like
[`jsoniter-scala`'s][js] codecs.

This report presents that replacement. We implement
`UnsafeRowSerializer[T]` — a Scala 3 macro-derived encoder targeting
Spark's exact `UnsafeRow` byte layout — and validate it three ways:

1. **Byte-level parity** with `ExpressionEncoder`'s output, on every
   fixture we test (§9).
2. **JMH microbenchmarks** at TPC-H SF=1, where our encoder geomean
   beats Spark by 1.23× across 12 benchmarks (§11).
3. **End-to-end TPC-H queries** quantifying where the encoder cost
   lands in real Spark workloads (§12).

We do not integrate our encoder into a Spark fork; that is left as
the natural quantified-end-to-end follow-up. What we do prove is that
**the encoder layer is the structural blocker for Scala 3, and that
replacing it can be done without changing anything else in Spark.**

[js]: https://github.com/plokhotnyuk/jsoniter-scala

## §2. What is an encoder?

For readers new to Spark internals, this section establishes
vocabulary. Readers familiar with `ExpressionEncoder` can skip ahead
to §3.

### The `Dataset[T]` ↔ `DataFrame` contract

A `Dataset[T]` is Spark's typed alternative to the untyped
`DataFrame`. The two APIs share storage (Catalyst's columnar
representation) but differ at the user-facing layer:

```scala
// Untyped: column expressions, schema verified at the query layer.
val df: DataFrame = spark.read.parquet("lineitem.parquet")
val filtered = df.filter($"shipdate" < lit("1998-12-01"))

// Typed: case class objects, lambdas verified at the Scala layer.
val ds: Dataset[Lineitem] = df.as[Lineitem]
val filtered = ds.filter(_.shipdate.isBefore(cutoff))
```

When the user writes `.filter(_.shipdate.isBefore(...))`, Spark must
materialize each row as a `Lineitem` JVM object so the lambda can
run. Once the lambda returns, the row gets re-encoded for the next
operator. That's **two encoder hops per typed lambda, per row**.

This is what `ExpressionEncoder` does, and where the per-row cost
this report measures lives.

### `InternalRow`, `GenericInternalRow`, and `UnsafeRow`

Spark's operators consume rows through a trait called `InternalRow`.
The trait has two notable concrete implementations:

- **`GenericInternalRow`** — a heap-allocated `Array[Object]`. Each
  cell holds a boxed value (`java.lang.Long` for `Long`, `UTF8String`
  for `String`, `Decimal` for `BigDecimal`, etc.). Simple, slow,
  used in tests and interpreted-mode codepaths.
- **`UnsafeRow`** — a single `byte[]` (or off-heap memory region)
  with a null bitmask, fixed-width 8-byte slots for each field, and
  a variable-length region for strings, binary, and nested
  structures. Reads go through `sun.misc.Unsafe.getLong(base,
  offset)`-style primitive memory access. This is Spark's canonical
  row format and what whole-stage codegen always produces.

The encoder's job is to bridge `JVM object T` ↔ `UnsafeRow`. Going
forward, when we say "Spark's row format" we mean `UnsafeRow`
specifically.

### Why this is hard at all

Spark needs to encode case classes it doesn't know in advance. So
the encoder isn't a fixed function — it's a derivation:

> Given a Scala type `T`, produce a serializer `T => UnsafeRow` and
> a deserializer `UnsafeRow => T` that round-trip without loss.

In current Spark, this derivation has two phases:

1. **Schema inference** — walk the type structurally (recurse into
   case-class fields, `Option`s, collections, etc.) and emit an
   `AgnosticEncoder[T]`. This phase uses Scala 2's runtime
   reflection (`TypeTag` + `scala.reflect.runtime.universe`) to read
   the type's shape. It runs once per encoder construction.
2. **Per-row codegen** — given the `AgnosticEncoder`, generate
   bytecode that reads/writes one `UnsafeRow` per call. This uses
   Spark's whole-stage codegen (a runtime bytecode compiler). The
   first call to a serializer triggers this compilation; subsequent
   calls reuse the cached bytecode.

The schema-inference phase carries hidden costs that the report will
later quantify (§13): JVM cold-start initialization of Scala's
reflection runtime, blocked GraalVM native-image, and the Scala 3
lock. The per-row codegen phase is the hot path measured by §11.

## §3. How Spark currently encodes

### `ExpressionEncoder[T]` and the AgnosticEncoder catalog

The public entry point is `org.apache.spark.sql.catalyst.encoders
.ExpressionEncoder`:

```scala
// Spark 4.1.2 — Encoders.scala
val enc = ExpressionEncoder[Lineitem]()           // requires TypeTag[Lineitem]
val ser: Lineitem => InternalRow = enc.createSerializer()
val deser: InternalRow => Lineitem =
  enc.resolveAndBind().createDeserializer()
```

`ExpressionEncoder[Lineitem]()` calls `ScalaReflection.encoderFor[T]`,
which walks `Lineitem`'s `TypeTag` and produces an `AgnosticEncoder[
Lineitem]` — an engine-independent description of the type's shape.
The `AgnosticEncoder` abstraction was introduced in Spark 3.5 to
support the Spark Connect wire protocol; it decouples the type
description from the in-process execution engine.

Spark 4.1 ships ~30 concrete `AgnosticEncoder` leaf and structural
variants: `PrimitiveBooleanEncoder`, `BoxedIntEncoder`,
`StringEncoder`, `ScalaDecimalEncoder`, `LocalDateEncoder` (with a
lenient flag), `InstantEncoder`, `LocalTimeEncoder` (new in 4.1),
`BinaryEncoder`, `CalendarIntervalEncoder`, `VariantEncoder`,
`GeographyEncoder` (4.1), `GeometryEncoder` (4.1), plus structural
encoders: `ProductEncoder`, `OptionEncoder`, `IterableEncoder`,
`MapEncoder`, `UDTEncoder`, `TransformingEncoder`. Our compile-time
encoder matches this catalog at the type level; the full
cross-reference lives in [`docs/ENCODER_PARITY.md`](ENCODER_PARITY.md).

### The TypeTag-based derivation

`ScalaReflection.encoderFor` is the type-walking phase:

```scala
def encoderFor[E: TypeTag]: AgnosticEncoder[E] = {
  encoderFor(typeTag[E].in(mirror).tpe).asInstanceOf[AgnosticEncoder[E]]
}
```

`TypeTag[T]` is Scala 2's reflective handle on the structural shape
of `T`. To access it, the JVM must initialize
`scala.reflect.runtime.universe` — a 500+ class subsystem that walks
the JVM's class hierarchy at first access. On JDK 21, this
initialization takes 50–100 ms wall-clock and tens of megabytes of
heap.

It also walks the `scala.Array` companion to populate its symbol
table. **On a Scala 3 classpath, this walk fails** — the Scala 3
`Array` companion has a different shape than what Scala 2's
reflection expects to introspect, and `ScalaReflection.<clinit>`
throws `FatalError: class Array does not have a member apply`. This
is the concrete mechanism of the **Scala 2.13 lock**: Spark can't
fully migrate to Scala 3 because every `ExpressionEncoder[T]()` call
transitively forces Scala 2's reflection runtime to initialize, and
on a Scala 3 classpath that initializer fails. We discovered this
the hard way when building this report's byte-parity test directly
in Scala 3 (details in §8).

### Whole-stage codegen → UnsafeRow

Once the `AgnosticEncoder` exists, `createSerializer()` builds a
`UnsafeProjection` via `GenerateUnsafeProjection`. This is Spark's
runtime bytecode codegen: it emits a `SpecificUnsafeProjection`
class with one specialized `apply(row: InternalRow): UnsafeRow`
method per encoder. The generated bytecode is what runs on the hot
path.

Per row, the codegen'd `apply()`:

1. Reads each field from the input object via the case-class
   accessor methods.
2. Performs the JVM-to-internal-form conversion per type
   (`LocalDate → epochDays Int`, `String → UTF8String`, etc.).
3. Writes each converted value into a pre-allocated
   `UnsafeRowWriter`'s buffer.
4. Returns the writer's `UnsafeRow`, which shares the writer's
   `byte[]` and gets overwritten on the next call (Spark's
   mutable-row contract — callers retaining the row must `.copy()`).

This is fast. The codegen path is the result of more than a decade
of Spark optimization. **Matching it from Scala 3 — without runtime
reflection or runtime bytecode generation — is the technical
challenge this report addresses.**

### Decomposing the cost

Two distinct costs that the report later disentangles:

1. **Per-row encode/decode cost** — measured by JMH micro (§11).
   Hot-path, runs millions of times per query, what most
   throughput-sensitive workloads pay.
2. **Reflective derivation + first-call codegen** — visible in
   Spark's own log output. During each of our SF=1 benchmark runs
   the Spark side logged lines like `Code generated in
   106.242584 ms` for the first `UnsafeProjection` per encoder
   type, with smaller follow-ons (~5–20 ms) for subsequent
   projections. Pure cold-start overhead — paid once per encoder
   per JVM, before the first row of work. Discussed in §13.

**Cross-checking per-row cost against end-to-end.** At SF=1, Q6
processes 6M lineitem rows. It is the cleanest encoder-bound signal:
filter + SUM, single-row result, no GROUP BY or ORDER BY dilution.
DS-Q6 wall-clock is 871 ms = ~145 ns/row. DF-Q6 is 173 ms =
~29 ns/row. The difference (~116 ns/row) is the encoder +
JVM-object materialization cost in actual query execution.

Notably this is *less* than our isolated JMH deserialize cost
(Spark 286 ns/op, ours 230 ns/op). Two likely contributors: (i)
Catalyst optimization pushes the `shipdate` filter into Parquet
column-skipping, so not every row reaches the deserialize path;
(ii) JIT amortization within the query loop intersperses decode
with filter and SUM accumulation, achieving better instruction-level
parallelism than the isolated JMH single-operation benchmark.

The JMH micro and end-to-end numbers triangulate to **"encoder cost
is real and material in queries where the typed lambda forces
per-row materialization, smaller than isolated JMH suggests because
Spark optimizes around it for filterable rows."** The next sections
make this precise.

---

# Part 2 — The Scala 2.13 wall

## §4. Why Spark can't simply move to Scala 3

The Scala community has shipped Scala 3 since May 2021. Most major
libraries — Akka, Cats, Circe, Play, ZIO, http4s — have Scala 3
artifacts published. Spark does not.

The reason is the encoder. Specifically: `ExpressionEncoder[T]()`
requires a `TypeTag[T]`, and `TypeTag` requires Scala 2's
`scala.reflect.runtime.universe`, and that runtime universe will not
initialize cleanly on a Scala 3 classpath. We describe the failure
mode concretely because the structural reason matters for the
migration argument.

### The transitive dependency chain

```
ExpressionEncoder[T]()                       # TypeTag[T] required
  └─ ScalaReflection.encoderFor[T]
      └─ typeTag[T].in(mirror).tpe           # forces runtime.universe
          └─ scala.reflect.runtime.JavaUniverse.init()
              └─ JavaUniverseForce.force()
                  └─ getMember(Array, "apply")   # FAILS on Scala 3
```

The last step looks up `scala.Array.apply` via reflective member
lookup. On a Scala 2.13 classpath, `Array.apply` is a method of the
`scala.Array` companion that constructs primitive-specialized arrays.
On a Scala 3 classpath, the same companion exists but its shape
differs subtly enough that Scala 2's symbol-table walker throws:

```
scala.reflect.internal.FatalError: class Array does not have a
member apply
        at scala.reflect.internal.Definitions$DefinitionsClass
                  .fatalMissingSymbol(Definitions.scala:1426)
        at scala.reflect.runtime.JavaUniverseForce.force(...)
        at scala.reflect.runtime.JavaUniverse.init(...)
        at org.apache.spark.sql.catalyst.ScalaReflection
                  .<clinit>(ScalaReflection.scala:43)
```

This is the same `ExceptionInInitializerError` we encountered when
trying to invoke `ExpressionEncoder` from Scala 3 during this
project's byte-parity test (§9). It is not a build-system
inconvenience — it is a runtime initialization failure that no
classpath manipulation, `--add-opens`, or `For3Use2_13` shim can
fix. The Scala 3 stdlib's `Array` companion is different at the
JVM level, and Scala 2's reflection cannot introspect it.

The encoder isn't merely *implemented* in Scala 2. It is
*structurally coupled* to Scala 2's runtime reflection. Any code
path that calls `ExpressionEncoder[T]()`, directly or
transitively, will fail on Scala 3.

### What this means for typical Spark code

A typed `Dataset[T]` operation reaches the encoder in many places:

```scala
val ds = spark.read.parquet(path).as[Lineitem]   // ExpressionEncoder
ds.filter(_.shipdate.isBefore(cutoff))            // ExpressionEncoder
ds.groupByKey(_.returnflag)                       // ExpressionEncoder
ds.map(l => (l.partkey, l.quantity))              // ExpressionEncoder
ds.collect()                                       // ExpressionEncoder
```

Any line in that fragment, on a Scala 3 classpath, throws on the
first execution. The user-facing error message is the unhelpful
`FatalError: class Array does not have a member apply` deep in a
stack trace. Removing `.as[Lineitem]` (working in untyped
`DataFrame` land) is the only escape.

### Workarounds people use today

Three patterns appear in the Spark+Scala 3 community:

**Use `DataFrame` instead of `Dataset[T]`.** Sacrifices type safety:
column-existence and column-type errors move from `scalac` time to
runtime. Pays the encoder tax to escape... by abandoning the encoder.
The largest user population, because it's the only path that just
works.

**Use Frameless's [`TypedEncoder`][frameless].** A community library
that provides a typed `Dataset`-like API in Scala 2 by building
Catalyst `Expression` trees from typed values. Avoids `TypeTag` on
its own surface but is itself Scala 2.13 and delegates to Spark's
codegen — so it works *in Scala 2.13* and doesn't help the Scala 3
case. Frameless's documentation claims "identical performance" to
`ExpressionEncoder`; this is literally true because Frameless emits
the same Catalyst expression trees Spark would generate from a
`TypeTag`, then runs them through the same `GenerateUnsafeProjection`.

**Use [`spark-scala3`][sparkscala3].** A thin Scala 3 surface around
Spark's Scala 2.13 jars via sbt's `CrossVersion.for3Use2_13`. Works
for non-`Dataset` code (DataFrame, SQL strings, RDD APIs). Runs
into the same `TypeTag` wall on any `Dataset[T]` operation.

[frameless]: https://typelevel.org/frameless/TypedEncoder.html
[sparkscala3]: https://github.com/vincenzobaz/spark-scala3

### Previous attempts at compile-time encoders

We surveyed the prior art (commit `ff1610a`'s research agent looked
at jsoniter-scala, circe, Frameless, magnolia, chimney) and found
**no published case study** of a Scala 3 compile-time `UnsafeRow`
encoder. The closest neighbors:

- [`jsoniter-scala`][js] uses Scala 3 quoted macros to derive JSON
  codecs that beat Jackson by ~1.5–2× on similar workloads. The
  pattern is direct — but it targets JSON byte strings, not Spark's
  `UnsafeRow` layout.
- [`circe`][circe] uses Magnolia-derived encoders for JSON ASTs.
  Same family of techniques.
- Frameless (above) — delegates to Spark, isn't independent
  evidence that the technique works without Spark's runtime
  reflection.

Spark's specific output format combined with Scala 3 macro
derivation is novel territory; nobody has published numbers showing
that a from-scratch compile-time encoder can match Spark's
codegen-emitted bytecode on `UnsafeRow`. This report fills that gap.

[circe]: https://github.com/circe/circe

## §5. The Scala 3 derivation story

Scala 3 offers three mechanisms for compile-time-derived code. We
use two of them.

### Mechanism 1 — `inline match` on a type tuple via `Mirror`

`Mirror.ProductOf[T]` exposes the structural shape of a case class
at the type level. With `inline`, we can pattern-match on the field
types and emit specialized code per case at the macro-expansion
site:

```scala
inline def deriveFields[Types <: Tuple]: Vector[FieldEncoder[?]] =
  inline erasedValue[Types] match
    case _: EmptyTuple => Vector.empty
    case _: (Long *: ts)   => ... // specialized to Long
    case _: (String *: ts) => ... // specialized to String
    case _: (t *: ts)      => deriveFields[ts]
```

Cheap to write, JIT-friendly, and the inline-expansion produces
straight-line code with no runtime dispatch. Limitations: the field
types must be known at expansion site (no recursion through
non-case-class wrappers), and any final construction goes through
`Mirror.fromProduct(Product)` — which forces every primitive
argument through `java.lang.Long`/`Integer` **boxing**.

We use this mechanism for schema derivation
(`ProtoEncoder.derived[T]`), where the boxing isn't on the hot path.

### Mechanism 2 — `quoted.Expr` macros

The full Scala 3 macro reflection API. The macro receives a `Type[T]`
and a `Quotes` context, walks the type structure via the reflection
API (`TypeRepr`, `Symbol`, `caseFields`), and emits arbitrary code
trees:

```scala
def derivedImpl[T: Type](using Quotes): Expr[Encoder[T]] = {
  import quotes.reflect.*
  val classSym = TypeRepr.of[T].classSymbol.get
  val ctor = classSym.primaryConstructor

  // Emits the literal expression `new T(arg0, arg1, ...)`
  // where each arg has its own concrete type — no boxing.
  val args = classSym.caseFields.zipWithIndex.map { (fSym, i) =>
    emitFieldReader(fSym, i)
  }
  '{ new MyEncoder[T] {
    override def read(row: UnsafeRow): T =
      ${ Apply(Select(New(TypeTree.of[T]), ctor), args).asExprOf[T] }
  }}
}
```

Critical property: the emitted code can call the case-class
constructor *directly*, with each argument's static type matching
the constructor's expected parameter type. **Primitives stay
unboxed.** This is the pattern `jsoniter-scala`'s `JsonCodecMaker`
uses; we adopt it.

We use this mechanism for the per-row encode/decode path
(`UnsafeRowSerializer.derived[T]`), where avoiding boxing on every
field on every row matters for throughput.

### Mechanism 3 — Magnolia and similar typeclass-derivation libraries

[Magnolia][magnolia] (Scala-2 and Scala-3) and its Scala-3-native
heir, [chimney][chimney]'s `PartialTransformer.derive`, abstract
over typeclass derivation. They trade macro verbosity for a
constrained API. We do not use them here because the constrained
API is harder to reconcile with `UnsafeRow`'s field-by-field
emission requirement, but the technique is viable in principle.

[magnolia]: https://github.com/softwaremill/magnolia
[chimney]: https://chimney.readthedocs.io/

### What "no runtime reflection" buys

Replacing `TypeTag` + `runtime.universe` with compile-time macros
delivers concrete benefits:

- **GraalVM `native-image` becomes viable.** Spark's current encoder
  is one of the standard blockers for native-image; replacing it
  removes that block. Spark Connect workers could ship as native
  binaries with sub-100-ms cold start.
- **No `scala.reflect.runtime.universe` initialization.** The
  ~50–100 ms JDK-21 cold-start cost we measure indirectly via
  Spark's own codegen log lines (§13) disappears entirely.
- **Schema validation at compile time.** A misspelled case-class
  field, a wrong type, a missing field — these become `scalac`
  errors rather than runtime "no such column" failures at first
  request.
- **Inlined dispatch on the hot path.** The JIT sees specialized
  code per field type — `row.getLong(0)`, `row.getInt(3)`,
  `row.getUTF8String(13).toString` — rather than a megamorphic
  `match` on `DataType` at runtime. We confirm this manifests as
  measurable per-row speedup in §11.
- **The Scala 3 lock comes down.** Zero `scala.reflect.runtime`
  references means Spark can publish Scala 3 jars and a typed
  `Dataset[T]` user can finally upgrade.

These are the properties we set out to deliver. The next part of
the report describes how.

---

# Part 3 — Our approach

## §6. ProtoCatalyst's compile-time encoder

### High-level architecture

The implementation spans three modules:

```
┌─────────────────────────────────────────────────────────────┐
│ encoder/  (Scala 3, no Spark)                                │
│                                                              │
│   ProtoType        — engine-independent type IR              │
│   ProtoSchema      — schema description                      │
│   ProtoEncoder     — Mirror-based schema derivation          │
│   InlineRowSerializer — inline-match Array[Any] serializer   │
└─────────────────────────────────────────────────────────────┘
                            │
                            │ (depends on)
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ encoder-spark/  (Scala 3 + Spark 4.1.2 via for3Use2_13)     │
│                                                              │
│   UnsafeRowSerializer        — public trait + macro entry    │
│   UnsafeRowSerializerMacro   — quoted-macro emission         │
│   SparkTypeMapping            — ProtoType ↔ Spark DataType   │
│   SparkInternalTypeConverter  — legacy strategy (retained    │
│                                  for the simpler InlineRow   │
│                                  variant)                    │
└─────────────────────────────────────────────────────────────┘
                            │
                            │ (consumes Spark's Scala 2.13 jars
                            │  via CrossVersion.for3Use2_13)
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ Spark 4.1.2 catalyst — UnsafeRow, UnsafeRowWriter,           │
│                          Decimal, UTF8String, DateTimeUtils  │
└─────────────────────────────────────────────────────────────┘
```

The split is deliberate:

- **`encoder/`** is Spark-free. It's the schema IR layer — what a
  case class "looks like" structurally. This module is reusable by
  other engines (we have a Substrait module and a standalone Arrow
  executor that also consume `ProtoType`).
- **`encoder-spark/`** is the bridge. It uses Scala 3 macros over
  Spark's Scala 2.13 row classes. This is the module that produces
  byte-identical `UnsafeRow` output and is the subject of this
  report.
- **`benchmark-spark/`** (not shown) is Scala 2.13 + native Spark.
  It runs the Spark-side comparison (`ExpressionEncoder`) and
  writes byte fixtures for the parity test. We explain the
  cross-version communication pattern in §8.

### The two-step derivation

`UnsafeRowSerializer.derived[T]` is the public API:

```scala
// encoder-spark/src/main/scala/.../UnsafeRowSerializer.scala
inline def derived[T](using m: Mirror.ProductOf[T]): UnsafeRowSerializer[T] =
  val schema = ProtoEncoder.derived[T].schema      // step 1
  derivedMacroEntry[T](schema)                      // step 2

private inline def derivedMacroEntry[T](schema: ProtoSchema): UnsafeRowSerializer[T] =
  ${ UnsafeRowSerializerMacro.derivedImpl[T]('schema) }
```

**Step 1** uses `Mirror.ProductOf[T]` (available at the call site
via the implicit) to derive the `ProtoSchema` — field names, field
types, nullability. This is the `inline-match` mechanism described
in §5; it runs at scalac time and bakes the schema as a literal
value.

**Step 2** is the quoted-macro that emits the read/write lambdas.
The `'{ schema }` splice passes the schema as a runtime value; the
macro reflects on `T` to emit a direct constructor call for the
read path and direct `writer.write(idx, value.field)` calls for the
write path.

Critically, **the `Mirror` is used only at compile time**. Nothing
in the runtime code path references `Mirror.fromProduct`,
`Tuple.fromArray`, `ArrayProduct`, or any reflection. The compiled
output is pure JVM bytecode constructing `T` instances directly.

## §7. Implementation: `UnsafeRowSerializer`

### The macro's read-side emission

For `case class Lineitem(orderkey: Long, partkey: Long, ...,
shipdate: LocalDate, ..., comment: String)`, the macro emits:

```scala
val readFn: UnsafeRow => Lineitem = (row: UnsafeRow) =>
  new Lineitem(
    row.getLong(0),                                       // orderkey
    row.getLong(1),                                       // partkey
    row.getLong(2),                                       // suppkey
    row.getInt(3),                                        // linenumber
    if (row.isNullAt(4)) null                             // quantity
       else BigDecimal(row.getDecimal(4, 38, 18).toJavaBigDecimal),
    if (row.isNullAt(5)) null                             // extendedprice
       else BigDecimal(row.getDecimal(5, 38, 18).toJavaBigDecimal),
    // ... 6 more fields ...
    if (row.isNullAt(10)) null                            // shipdate
       else DateTimeUtils.daysToLocalDate(row.getInt(10)),
    // ... 4 more fields ...
    if (row.isNullAt(15)) null                            // comment
       else row.getUTF8String(15).toString
  )
```

Properties of this code:

- **Single constructor call.** No `Array[Any]` allocation, no
  `Mirror.fromProduct`, no `ArrayProduct` wrapper.
- **Primitives flow unboxed.** `Long` and `Int` slots become
  unboxed `long`/`int` values passed directly to the constructor.
- **Monomorphic call sites.** Each `getLong(0)`, `getInt(3)` is a
  direct method call the JIT can inline aggressively.
- **No dispatch on `DataType` at runtime.** The macro decided what
  to emit at *scalac* time. Spark's codegen approach reaches the
  same result via runtime bytecode generation; we reach it via
  compile-time emission.

The write-side mirror: `writer.write(0, value.orderkey)` per field.
Direct field selector (`Select(value, sym)`) — no
`productElement(i).asInstanceOf[T]` boxing as the old
`InlineRowSerializer` did.

### Writer caching

`UnsafeRowSerializerImpl[T]` caches a single `UnsafeRowWriter`
instance at construction:

```scala
class UnsafeRowSerializerImpl[T](
    val schema: ProtoSchema,
    val fieldCount: Int,
    writeFn: (UnsafeRowWriter, T) => Unit,
    readFn: UnsafeRow => T
) extends UnsafeRowSerializer[T]:

  private val cachedWriter = new UnsafeRowWriter(fieldCount)

  def serialize(value: T): UnsafeRow =
    cachedWriter.reset()
    cachedWriter.zeroOutNullBytes()
    writeFn(cachedWriter, value)
    cachedWriter.getRow      // SAME instance every call
```

This mirrors Spark's `UnsafeProjection` pattern. The returned
`UnsafeRow` is the same instance on every call; only the byte buffer
contents change. Callers retaining the row must `.copy()` —
documented on the trait. A plain instance field rather than
`ThreadLocal` because in Spark's per-task execution model there is
one task per thread, and the `ThreadLocal.get` indirection would
add cost for no gain.

For callers that want explicit writer control (e.g., iterating with
a single writer across multiple serializers), `writeTo(writer:
UnsafeRowWriter, value: T)` exposes the inner write function
directly.

### Macro construction details for generic case classes

A subtlety the prototype originally got wrong: `case class Box[A]
(id: Int, value: A)` derived as `Box[String]` should see the field
`value` as `String`, not as the type variable `A`. The naïve
implementation reads field types from the field's declaration tree
(`ValDef.tpt.tpe`), which preserves type parameters.

The correct pattern uses `tpe.memberType(fieldSym)` where `tpe` is
the applied receiver type `TypeRepr.of[T]`:

```scala
val ctorArgs = caseFields.zipWithIndex.map { (fieldSym, idx) =>
  val fieldTpe = tpe.memberType(fieldSym)    // substitutes A → String
  buildReadExpr(fieldTpe, idx, rowExpr).asTerm
}
```

The constructor invocation also requires care: `New(TypeIdent
(classSym))` strips type arguments, producing a call like `new Box(
...)` that fails type-checking for polymorphic ctors with
"constructor Box in class Box does not take parameters." The fix is
to use the fully-applied type tree and pipe type args through
`appliedToTypes`:

```scala
New(TypeTree.of[T])
  .select(classSym.primaryConstructor)
  .appliedToTypes(typeArgs)     // List(TypeRepr.of[String]) for Box[String]
  .appliedToArgs(ctorArgs)
```

Both fixes are exercised by `UnsafeRowExtraTypesSpec` —
`Box[String]`, `Box[Long]`, `Box[BigDecimal]`, and `Pair[Int,
String]` all round-trip correctly. We mention these explicitly
because they're the kind of detail that breaks silently in macro
work and would manifest as confusing "type variable" errors for
end users.

### Type coverage

The macro supports the TPC-H-relevant type surface plus a margin:

- **Primitives**: `Boolean`, `Byte`, `Short`, `Int`, `Long`,
  `Float`, `Double`.
- **Variable-length leaves**: `String`, `Array[Byte]`,
  `BigDecimal`, `java.math.BigDecimal`.
- **Temporal**: `java.time.LocalDate`, `java.sql.Date`,
  `java.time.Instant`, `java.sql.Timestamp`,
  `java.time.LocalDateTime`, `java.time.LocalTime` (Spark 4.1+),
  `java.time.Duration`, `java.time.Period`.
- **Identifier**: `java.util.UUID` (stored as `StringType`,
  canonical 36-char form).
- **Optionality**: `Option[T]` over any of the above.

Not yet supported, classified by effort to add (the macro rejects
at compile time with a clear message):

| Category | Types | Effort |
|---|---|---|
| Just need more inline cases | `OffsetDateTime`, `ZonedDateTime`, `java.util.Date`, `BigInt`, `java.math.BigInteger` | Hours |
| Needs `UnsafeArrayWriter` / `UnsafeMapWriter` / recursive ctor emission | Collections (`Seq` / `List` / `Vector` / `Set` / `Array[T]` of non-byte), Maps, nested case classes | Days |
| Out of scope by design | `Decimal`, `CalendarInterval`, `VariantVal`, `Geography`, `Geometry` (Spark-internal classes) | Belongs in a spark-coupling bridge module |

The cross-reference against Spark 4.1's full `AgnosticEncoder`
catalog is in [`docs/ENCODER_PARITY.md`](ENCODER_PARITY.md).

### Binary / wire compatibility

A direct consequence of byte-level parity (proven in §9): any
consumer that reads Spark's `UnsafeRow` reads ours identically —
Catalyst operators (filter, project, join, exchange), shuffle
blocks, Tungsten projections, Parquet writers via
`UnsafeProjection`. **Wire and disk format compatibility is
automatic.** Replacing the encoder in a Spark fork doesn't require
changes to downstream operators, the shuffle service,
lake-format writers, or any persisted artifacts. This is the
property that makes the migration mechanically feasible without
a flag day.

## §8. The build dance

This section documents the operational cost of running Scala 3
code against a Scala 2.13 Spark in 2026. Each item here is real
friction we encountered in this project; each one disappears the
day Spark publishes Scala 3 artifacts. **The accumulated workaround
list is itself the strongest single argument for the migration.**

### `CrossVersion.for3Use2_13`

Spark 4.1 ships only Scala 2.13 jars. To use them from a Scala 3
module:

```scala
// build.sbt — encoderSpark module
libraryDependencies ++= Seq(
  ("org.apache.spark" %% "spark-sql" % "4.1.2")
    .cross(CrossVersion.for3Use2_13)
    .exclude("org.scala-lang.modules", "scala-xml_2.13"),
  ("org.apache.spark" %% "spark-catalyst" % "4.1.2")
    .cross(CrossVersion.for3Use2_13)
    .exclude("org.scala-lang.modules", "scala-xml_2.13")
)
```

`CrossVersion.for3Use2_13` tells sbt to fetch the `_2.13` artifact
but link it onto a Scala 3 classpath. Most Spark classes
(`UnsafeRow`, `UnsafeRowWriter`, `Decimal`, `UTF8String`) cross
over cleanly because they don't rely on Scala 2-specific runtime
features. The exclude is for transitive `scala-xml_2.13` that
otherwise duplicates with a Scala 3 build.

### The `ScalaReflection.<clinit>` wall

Even bypassing `TypeTag` (via the
`ExpressionEncoder.apply(agnosticEncoder)` constructor that
doesn't need it), Spark's codegen internally calls
`scala.reflect.runtime.universe` to look up `Array.apply` during
method resolution. Scala 3's `Array` companion is shaped
differently than what Scala 2's reflection expects — the
`<clinit>` of `ScalaReflection` throws `FatalError` and the class
becomes permanently unusable in that JVM.

We discovered this when first writing the byte-parity test
directly in Scala 3:

```
ExceptionInInitializerError: null
  at o.a.s.s.c.expressions.objects.Invoke.encodedFunctionName...
  at o.a.s.s.c.encoders.ExpressionEncoder$Serializer.apply...
  at protocatalyst.encoder.spark.UnsafeRowParitySpec...
Caused by: scala.reflect.internal.FatalError: class Array does
not have a member apply
  at scala.reflect.internal.Definitions$DefinitionsClass...
  at scala.reflect.runtime.JavaUniverseForce.force...
  at o.a.s.s.catalyst.ScalaReflection.<clinit>...
```

**Workaround**: run Spark from a Scala 2.13 fixture-generator and
have the Scala 3 test consume byte files. The fixtures are produced
once by `protocatalyst.benchmark.UnsafeRowParityFixtures.main`
(Scala 2.13), written to
`encoder-spark/src/test/resources/parity/*.bin`, and consumed by
the Scala 3 `UnsafeRowParitySpec` for byte-level comparison.
Bytes are language-neutral.

This pattern — produce-in-Scala-2.13, consume-in-Scala-3 via files
— is used in three places in this project: the byte fixtures, the
TPC-H Parquet data, and the JMH JSON results that get merged in
the report. Every one of these intermediations is overhead that
the migration would eliminate.

### SIP-51 compatibility check

Spark 4.1.2 transitively pulls in `scala-library:2.13.18+`. Our
Scala 2.13 modules pin to 2.13.16 (because the
`semanticdb-scalac:4.13.x` plugin hasn't been published for 2.13.18
yet, and we use semanticdb for scalafix). sbt's SIP-51 check
rejects this combination by default. We demote with:

```scala
allowUnsafeScalaLibUpgrade := true
```

Safe because we don't link against new-stdlib symbols ourselves —
Spark does, internally, and it's tested against the newer
stdlib. But it's a workaround for a real cross-version constraint.

### Spark on JDK 21 module access

`SparkDateTimeUtils` reflects into `sun.util.calendar.ZoneInfo`
for timestamp handling on JDK 17+. JDK strong encapsulation
requires explicit opt-in:

```scala
run / javaOptions ++= Seq(
  "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
  ...
)
```

Not Scala-3-specific (Spark needs this on Scala 2.13 too on JDK
21+), but worth noting as part of the operational picture.

### Spark REPL classloader confusion under sbt

`UnsafeProjection` codegen tries to fetch generated classes via a
"REPL artifact server" because sbt's `MutableURLClassLoader`
triggers Spark's REPL heuristic. The fetch goes to a non-existent
HTTP server and fails. Fix:

```scala
.config("spark.driver.userClassPathFirst", "true")
.config("spark.executor.userClassPathFirst", "true")
```

Documented [in Spark's runtime classloader logic][spark-classloader];
this incantation appears in many community recipes for running
Spark under sbt or in IDE test runners.

[spark-classloader]: https://github.com/apache/spark/blob/master/core/src/main/scala/org/apache/spark/executor/Executor.scala

### Cross-module file-based communication

Because Scala 2.13 cannot call into Scala 3 macros, the project has
three intentional file-based intermediations:

| Producer | Consumer | File |
|---|---|---|
| Scala 2.13 (`UnsafeRowParityFixtures`) | Scala 3 (`UnsafeRowParitySpec`) | `encoder-spark/src/test/resources/parity/*.bin` |
| Scala 2.13 (`TpchParquetConverter`) | both via Spark | `data/tpch/sf-N-parquet/*.parquet/` |
| Each JMH side independently | report (eventually charts) | `results/<ts>-sfN/micro-*.json` |

Each one is a wedge between code that wants to share types but
cannot, working around it via byte arrays. Each one disappears
when the wall comes down.

### Cumulative cost

Tracking concretely for this project:

- **~12 lines of build.sbt** specifically for the cross-version
  workarounds (`for3Use2_13`, exclusions,
  `allowUnsafeScalaLibUpgrade`, the extra `--add-opens` flags).
- **~150 lines of duplicated code** (case classes and the `.tbl`
  parser exist in both Scala 3 and Scala 2.13 modules because they
  can't share).
- **~300 lines of fixture-generation infrastructure**
  (`UnsafeRowParityFixtures`, `TpchParquetConverter`,
  `TpchQueryBench`) — needed because we can't simply call
  `ExpressionEncoder` from Scala 3 tests.
- **One additional JVM fork per cross-version round trip**, with
  the corresponding bench-orchestration complexity in
  `scripts/bench.sh`.

A user upgrading their Spark application to Scala 3 today would
hit a strict superset of these workarounds — without our project's
benefit of being able to design around them. The migration argument
is straightforward: doing this once, at the encoder level, saves
the community from paying these costs forever.
