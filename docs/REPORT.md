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
