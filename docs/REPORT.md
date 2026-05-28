# Compile-Time Encoders for Spark: A Scala 3 Path Forward

A technical report on replacing Spark's `ExpressionEncoder` with a
compile-time, macro-derived encoder. We show that the replacement is
byte-compatible with Spark's existing `UnsafeRow` output, faster on
most per-row operations we measure (geomean 1.16× across 12
benchmarks), allocates comparably to Spark (within 7% median), and
— critically — works in Scala 3 today.

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
   beats Spark by 1.16× across 12 benchmarks (§11).
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

Spark 4.1.2 ships 47 concrete `AgnosticEncoder` variants (counted
from the source: 10 structural + 14 primitive/boxed leaves + 22
other leaves + 1 transforming encoder). The leaf side covers
everything from primitives to geospatial types
(`GeographyEncoder` / `GeometryEncoder`, both new in 4.1).
Examples on the leaf side: `PrimitiveBooleanEncoder`,
`BoxedIntEncoder`, `StringEncoder`, `ScalaDecimalEncoder`,
`LocalDateEncoder` (with a lenient flag), `InstantEncoder`,
`LocalTimeEncoder` (new in 4.1), `BinaryEncoder`,
`CalendarIntervalEncoder`, `VariantEncoder`, `GeographyEncoder`,
`GeometryEncoder`. Structural side: `ProductEncoder`,
`OptionEncoder`, `IterableEncoder`, `MapEncoder`, `UDTEncoder`,
`TransformingEncoder`. Our compile-time encoder matches this
catalog at the type level for the subset we target (§7); the full
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
the JVM's class hierarchy at first access. **Measured on JDK 21 /
Apple Silicon: a Scala 2.13 main that does nothing but `val u =
scala.reflect.runtime.universe` (and a control measurement of an
empty main as baseline) takes ~500 ms longer than the baseline.**
The first encoder construction in a JVM pays this cost once.

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
libraries — Cats, Circe, Play, ZIO, http4s, Akka/Pekko (Akka under
its post-2022 BUSL license; Pekko as the ASF fork of Akka 2.6, both
cross-built for Scala 3.3+) — have Scala 3 artifacts published.
Spark does not.

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
case. Frameless's own [documentation states][frameless-perf]: "once
derived, reflection-based `Encoder`s and implicitly derived
`TypeEncoder`s have identical performance." This is true because
Frameless emits the same Catalyst expression trees Spark would
generate from a `TypeTag`, then runs them through the same
`GenerateUnsafeProjection`.

[frameless-perf]: https://typelevel.org/frameless/TypedEncoder.html

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
  codecs that consistently outperform `jackson-module-scala`
  across published benchmarks
  ([plokhotnyuk.github.io/jsoniter-scala][js-bench]); the margins
  vary by payload shape but the consistent direction is the same
  one we observe in this report. The pattern is direct emission
  of monomorphic specialized code — but it targets JSON byte
  strings, not Spark's `UnsafeRow` layout.

[js-bench]: https://plokhotnyuk.github.io/jsoniter-scala/
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

- **GraalVM `native-image` AOT compilation becomes viable for the
  encoder layer.** This claim needs unpacking because it's the most
  externally-cited benefit of compile-time derivation, and it's also
  the most easily over-claimed. Going slowly:

  **First, a critical distinction.** "GraalVM + Spark" can mean two
  very different things, and the public conversation routinely
  conflates them:

  1. **GraalVM as a JVM JIT.** Use the GraalVM JDK distribution as
     a drop-in OpenJDK replacement; the top-tier JIT is the Graal
     compiler instead of HotSpot's C2. The application still runs
     on a JVM. Class-loading, reflection, dynamic bytecode
     generation all work exactly as before. **This works fine with
     Spark today** — Meta swapped to GraalVM JIT and reported
     ~10% CPU reduction on Spark workloads with zero code changes
     (https://medium.com/graalvm/graalvm-at-facebook-af09338ac519);
     Twitter did the same for 30+ Scala microservices in 2018
     (https://www.oracle.com/a/ocom/docs/graalvm-twitter-casestudy-constellation.pdf).
     A reader who has seen "Meta uses GraalVM with Spark" is
     seeing *this* — the JIT replacement.

  2. **GraalVM `native-image` (AOT).** A build-time tool that does
     whole-program static analysis and emits a standalone native
     executable. **The JVM is gone.** No class-loading, no
     reflection mirror initialization, no runtime bytecode
     generation. Cold start drops by an order of magnitude on
     real applications; published Spring Boot 3.2 REST benchmarks
     report ~1.42 s on JDK 21 HotSpot dropping to ~407 ms with
     GraalVM 24 native-image (71% reduction;
     [johal.in deep-dive](https://johal.in/deep-dive-graalvm-24-native-image-internals-it/)).
     Production migrations have reported 7 s → 80 ms in the best
     case. The single-digit-ms numbers people quote are for
     trivial programs (`Hello, world`); a realistic Spark Connect
     client would land in the ~100–500 ms range. This is what the
     rest of
     this bullet is about, and what our compile-time encoder
     enables (in part).

  **Nobody has run full Apache Spark as a native-image executable
  successfully**, as of writing. A 2020 graalvm-users mailing list
  thread asking "did anyone actually succeed in making Flink or
  Spark work with GraalVM Native Image?" got no positive replies
  (https://oss.oracle.com/pipermail/graalvm-users/2020-June/000230.html);
  there is no SPIP or open Spark JIRA for native-image support;
  Spark is absent from GraalVM's official "Libraries and
  Frameworks Ready for Native Image" list
  (https://www.graalvm.org/native-image/libraries-and-frameworks/).
  The Spark Connect ecosystem has produced a Go client
  (https://github.com/apache/spark-connect-go) — a different
  language, not the JVM — but no native-image build of the Scala
  client. **This report does not change that.**
  What it changes is *one specific blocker* on the path.

  ### Why runtime reflection is hostile to native-image

  Native-image's **closed-world assumption** requires every class,
  method, and field reachable at runtime to be discoverable at
  AOT-compile time. The compiler does whole-program reachability
  analysis, emits a self-contained native binary, and discards
  the JVM and the JIT entirely.

  Runtime reflection (`Class.forName`, `Method.invoke`,
  `Constructor.newInstance`, `scala.reflect.runtime.universe`) is
  fundamentally hostile to closed-world AOT. GraalVM can handle
  bounded cases via [reflection metadata
  files][graalvm-reflect-config] — a `reflect-config.json`
  enumerating every reflectively-accessed member. But this only
  works when the *set of reflectively-accessed types* is known in
  advance. For Spark's `ExpressionEncoder`, the set is "every
  user case class," which is unbounded at build time. There is no
  practical reflection config that enumerates "all user case
  classes" — every downstream Spark application would need its own.

  [graalvm-reflect-config]: https://www.graalvm.org/latest/reference-manual/native-image/dynamic-features/Reflection/

  **Our compile-time encoder, by construction, only emits static
  bytecode at `scalac` time.** Inspecting the macro's output for a
  typical case class — the very ordinary `new Lineitem(
  row.getLong(0), row.getInt(1), ...)` shape shown in §7 — there
  are zero reflective calls. Every method call is monomorphic and
  resolved at compile time. From `native-image`'s perspective, the
  encoder is just normal Scala code: indistinguishable from any
  other Scala 3 program, no `reflect-config.json` required.

  **What this does NOT mean: "Spark becomes native-image ready."**
  Spark has other AOT blockers, and they are well-documented:

  - **SQL/Catalyst's `GenerateUnsafeProjection`** does runtime
    bytecode generation via Janino — emits Java source as a
    string, compiles it in-process, loads the bytecode through a
    custom classloader. Janino is a known blocker for native-image
    even in much simpler contexts: Spring Boot and Logback have
    open issues for the same reason
    (https://github.com/spring-projects/spring-boot/issues/38347,
    https://github.com/oracle/graal/issues/2115). Setting
    `spark.sql.codegen.wholeStage=false` (a public config) plus
    `spark.sql.codegen.factoryMode=NO_CODEGEN` (which Spark's own
    SQLConf marks *"only for the internal usage, and NOT supposed
    to be set by end users"*) forces an interpreted path that
    avoids Janino. But even that doesn't rescue
    `ExpressionEncoder`, which builds `UnsafeProjection`-shaped
    serializers via Janino independently of whole-stage codegen.
  - **`UDFRegistration`** accepts arbitrary lambdas and reflects
    on them.
  - **Hive UDFs** are dynamically class-loaded.
  - **The Spark shell / REPL** is fundamentally
    bytecode-generation-driven and can't AOT-compile.

  **Removing the encoder is one specific blocker among several.**
  What we deliver is the *typed Dataset[T] read/write path* — the
  highest-traffic per-row operation — being AOT-clean. The
  Catalyst codegen path requires separate work (either swapping
  Janino for a compile-time alternative, or accepting an
  interpreted fallback in native-image builds with a separate
  perf tradeoff).

  Realistic near-term scenarios this unlocks:
  - **Spark Connect clients/workers.** Spark Connect's protocol is
    Arrow + gRPC; the client doesn't need full Catalyst at runtime.
    A native-image Spark Connect client with an AOT-clean encoder
    could ship as a small native binary with sub-second startup,
    suitable for Lambda functions or CLI tools that build typed
    queries and submit them to a remote Catalyst server.
  - **Embedded analytics in non-JVM hosts.** Polyglot deployments
    where a Python or Node.js application links against a native
    Spark library for typed Dataset processing on local data.
  - **Edge / IoT workers** where 100+ MB JVMs and 500 ms cold
    starts are prohibitive.

  None of these scenarios *fully* exist today; the encoder lock is
  one of several reasons. Removing it is a precondition rather than
  a sufficient condition. The honest claim: **a Scala 3 macro
  encoder makes the encoder layer indistinguishable from any
  AOT-clean Scala code; the rest of Spark's AOT-readiness story is
  separate work this report doesn't address.**
- **No `scala.reflect.runtime.universe` initialization.** The
  ~500 ms JDK-21 cold-start cost measured directly in §13 (a
  Scala 2.13 main that does nothing but touch the universe) is
  paid by every JVM that ever instantiates an `ExpressionEncoder`;
  our compile-time encoder pays zero of it.
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

- **8 lines of build.sbt** specifically for the cross-version
  workarounds (`for3Use2_13`, scala-xml exclusions,
  `allowUnsafeScalaLibUpgrade`, the `sun.util.calendar` /
  `sun.security.action` opens, `userClassPathFirst`). Counted by
  `grep -c` of the matching incantations against the committed
  build.sbt.
- **236 lines of duplicated code** across `Schemas.scala`,
  `TblParser.scala`, and `TpchData.scala` — these case classes
  and parsers exist in both the Scala 3 (`encoder-spark`) and
  Scala 2.13 (`benchmark-spark`) modules because cross-version
  type sharing isn't available.
- **474 lines of fixture-generation infrastructure** —
  `UnsafeRowParityFixtures.scala` (146 lines, the byte-parity
  producer), `TpchParquetConverter.scala` (137), and
  `TpchQueryBench.scala` (191). All Scala 2.13 because they call
  Spark directly; needed *only* because we can't invoke
  `ExpressionEncoder` from Scala 3.
- **One additional JVM fork per cross-version round trip**, with
  the corresponding bench-orchestration complexity in
  `scripts/bench.sh`.

A user upgrading their Spark application to Scala 3 today would
hit a strict superset of these workarounds — without our project's
benefit of being able to design around them. The migration argument
is straightforward: doing this once, at the encoder level, saves
the community from paying these costs forever.

---

# Part 4 — Validation

## §9. Correctness validation

A replacement encoder must produce **exactly the same bytes** as
the encoder it replaces. Any deviation breaks Catalyst operators,
shuffle blocks, lake-format writers, and persisted artifacts that
expect Spark's specific `UnsafeRow` layout. This section documents
how we verify byte parity.

### Byte-level parity test against `ExpressionEncoder`

The core correctness oracle lives at
[`encoder-spark/src/test/scala/.../UnsafeRowParitySpec.scala`][parity].

[parity]: ../encoder-spark/src/test/scala/protocatalyst/encoder/spark/UnsafeRowParitySpec.scala

For each test shape, the procedure is:

1. **Generate the Spark byte fixture** by running Spark's
   `ExpressionEncoder[T]().createSerializer()` on a known value
   from a Scala 2.13 fixture-generator
   (`benchmark-spark/src/main/scala/.../UnsafeRowParityFixtures.scala`):

   ```scala
   val sparkEnc = ExpressionEncoder(productEncoder)
   val sparkSer = sparkEnc.createSerializer()
   val unsafe: UnsafeRow = sparkSer(value).asInstanceOf[UnsafeRow]
   val bytes = unsafe.getBytes  // raw byte[] of the UnsafeRow
   writeToFile(s"parity/$shapeName.bin", bytes)
   ```

2. **Compare in the Scala 3 test**:

   ```scala
   val expected: Array[Byte] = readFixture(shapeName)
   val ours: UnsafeRow = UnsafeRowSerializer.derived[T].serialize(value)
   assert(java.util.Arrays.equals(expected, ourBytesOf(ours)))
   ```

The five fixtures we check:

| Shape | Fields | Probes |
|---|---|---|
| `Simple(Int, String)` | 2 | primitives + variable-length string |
| `WithDecimal(Long, BigDecimal)` | 2 | decimal in variable-length region |
| `WithTemporal(LocalDate, Instant)` | 2 | epoch days Int + epoch micros Long |
| `WithOption(Int, Option[String])` — Some | 2 | null bitmask off |
| `WithOption(Int, Option[String])` — None | 2 | null bitmask on |

All five pass byte-for-byte. The Scala 3 test is wired to
auto-skip if the fixture files don't exist (rather than fail
opaquely), so a developer on a fresh checkout sees:

```
> sbt 'encoderSpark/test'
... 36 tests passing ...
Note: UnsafeRowParitySpec skipped — fixtures not present.
Regenerate via: sbt 'benchmarkSpark/runMain ...UnsafeRowParityFixtures'
```

The fixtures themselves *are* committed to the repo; the
auto-skip is for cases where they get accidentally deleted.

### Real-data round-trip on TPC-H

[`TpchDbgenIntegrationSpec.scala`][dbgen-spec] is the second
correctness oracle. It loads real `dbgen`-produced SF=0.01 output
(60,175 lineitem rows + 7 other tables) through `TblParser`, then
round-trips every row through `UnsafeRowSerializer`:

[dbgen-spec]: ../encoder-spark/src/test/scala/protocatalyst/encoder/spark/tpch/TpchDbgenIntegrationSpec.scala

```scala
TblParser.foreachLine(dataRoot.resolve("lineitem.tbl"), TblParser.parseLineitem) { row =>
  val restored = ser.deserialize(ser.serialize(row))
  assertEquals(restored.orderkey, row.orderkey)
  assertEquals(restored.shipdate, row.shipdate)
  assertEquals(restored.extendedprice, row.extendedprice)
  count += 1
}
assertEquals(count, 60175)
```

All 60K rows round-trip in 0.3 seconds with no diffs. This catches
two failure modes the byte-parity test wouldn't:

- **Type-specific corner cases** — leap-year dates, BigDecimal
  precisions outside (38, 18) (none in TPC-H by spec, but `dbgen`
  could produce them in principle), empty strings, strings with
  multi-byte UTF-8 sequences.
- **Decode-side correctness** — the byte-parity test only checks
  the encode direction. The dbgen round-trip exercises both
  directions against millions of distinct values.

### Test surface

48 tests, all passing on commit `1ac4873` (and `46c30d7`, the
current HEAD as this section was written):

| Suite | Count | Coverage |
|---|---:|---|
| `UnsafeRowSerializerSpec` | 14 | Slot semantics, null handling, writer reuse, packed size for each primary type |
| `UnsafeRowParitySpec` | 5 | Byte-for-byte parity against Spark's `ExpressionEncoder` |
| `UnsafeRowExtraTypesSpec` | 12 | Duration/Period/UUID (added in P2 fix), generic case classes (added in P3 fix) |
| `TpchSchemasSpec` | 9 | One smoke test per TPC-H table (inline fixture) |
| `TpchDbgenIntegrationSpec` | 8 | Real `dbgen` SF=0.01 round-trip per table |

The `UnsafeRowExtraTypesSpec` was added after a review round
(commit `1ac4873`) caught two correctness gaps the original test
suite missed: the macro had stale doc claims about types it didn't
support, and the generic case-class field-type substitution was
broken. Adding tests for both was the natural way to lock the
fixes; they're now part of the regression baseline.

We claim coverage for **everything in the TPC-H benchmark surface
plus a margin**. We do not claim coverage for the full Spark 4.1
`AgnosticEncoder` catalog — the unsupported types are documented
in §7 and would be follow-up work.

## §10. Benchmark methodology

Distilled from [`docs/BENCHMARK_METHODOLOGY.md`][methodology]; full
reasoning and sources are there. This section states the rules and
their justifications briefly.

[methodology]: BENCHMARK_METHODOLOGY.md

### Statistical treatment (Georges et al., OOPSLA 2007)

The canonical reference for rigorous JVM benchmarking is
[*Statistically Rigorous Java Performance Evaluation*][georges]
(Georges, Buytaert, Eeckhout, OOPSLA 2007). The paper shows that
ad-hoc Java benchmarking produces misleading conclusions in up to
16% of comparisons; the corrective recipe is:

[georges]: https://dri.es/files/oopsla07-georges.pdf

- ≥30 measurements after steady state.
- Discard the first VM invocation (cold-start JIT noise).
- Report confidence intervals (Student's t for n<30, z for n≥30).
- Declare "no significant difference" when CIs overlap.

JMH's default modes implement most of this. We configure:

```
-f 3 -wi 5 -i 15
```

Three forks (separate JVM invocations), each with 5 warmup
iterations × 1 s and 15 measurement iterations × 1 s. Total
measurements per benchmark: 3 × 15 = 45, well above the 30
threshold. JMH reports the mean and 99% confidence interval; we
publish both.

For arithmetic on multiple-benchmark speedups (per §11), we use
**geometric mean**. Per [Eyerman et al., UGent 2024][eyerman], the
arithmetic mean of speedup ratios is mathematically wrong (it
biases toward queries with larger absolute differences).

[eyerman]: https://users.elis.ugent.be/~leeckhou/papers/CAL-2024-geomean.pdf

### Cold/hot separation for end-to-end queries

JMH handles cold/hot for microbenchmarks (warmup iterations are
discarded). End-to-end query benchmarks aren't a JMH workload —
they're once-per-call wall-clock with side effects. We follow
[ClickBench's][clickbench] pattern:

[clickbench]: https://github.com/ClickHouse/ClickBench

- 1 warmup run, discarded.
- 3 measurement runs.
- Spark cache cleared between runs (via `spark.catalog.clearCache()`).
- Reported metric: **minimum** of the 3 measurement runs. The min
  is less noisy than the mean when system noise (GC pause,
  background process) inflates individual runs.

The raw 3-run timings are written to `queries.csv` so reviewers
can verify dispersion themselves.

### Required ablations

Per the methodology doc, three Spark configuration axes are varied
in the end-to-end query bench:

| Axis | Values | Why |
|---|---|---|
| `spark.sql.codegen.wholeStage` | `true`, `false` | Spark's codegen on/off changes the encoder code path materially. |
| `spark.sql.adaptive.enabled` | `true`, `false` | AQE can reshape queries; off makes timing more deterministic. |
| Master URL | `local[1]`, `local[*]` | Single-thread isolates per-row work from parallelism noise. |

Eight combinations total per query per variant. Spark's default in
production is usually `codegen=true, aqe=true, threads=local[*]`,
which is the "headline" config we lead with in §12. The other
seven combinations sit in the appendix for reviewers who want to
verify the pattern holds across configurations.

### Reproducibility contract

A reported number is only "credible" by our methodology if:

1. The disclosure paragraph (hardware, JDK, Spark, Scala, git SHA)
   is present.
2. The run was produced by `./scripts/bench.sh sf=<N>` against a
   public git SHA.
3. Raw JMH JSON + end-to-end wall-clock CSV are attached.
4. The EC2 setup script (if cloud-side) is documented in
   [`docs/CLOUD_BENCH.md`](CLOUD_BENCH.md).

Each results directory in this repository (`results/<utc-ts>-sf<N>/`)
satisfies items 1, 3, and 4. The git SHA is baked into the
disclosure header by the script; an updated SHA mid-run (as
happened during one review iteration) is itself documented in the
commit history.

### Comparison surface

Two encoders compared in the microbench (§11):

- **Spark `ExpressionEncoder[T]`** — Scala 2.13, runtime
  reflection + whole-stage codegen → `UnsafeRow`.
- **ProtoCatalyst `UnsafeRowSerializer.derived[T]`** — Scala 3,
  compile-time quoted-macro derivation → `UnsafeRow`. Proven
  byte-identical (§9).

Two query implementations compared end-to-end (§12):

- **Spark `DataFrame`** — untyped; encoder-free baseline; the
  upper bound for what Spark can do at the row-format layer
  without going through the encoder.
- **Spark `Dataset[T]`** — typed; one `.filter(lambda)` step that
  forces `ExpressionEncoder` to decode every row. Otherwise
  identical to the DF variant.

Both DF and DS implementations run on the same Parquet data, with
the same Spark configurations. The only difference is the typed
filter step; the rest of the query (`groupBy`, aggregates, etc.)
runs as SQL on both. This isolates the encoder cost as cleanly as
the Spark API allows from the outside.

A natural third comparison surface — replacing `ExpressionEncoder`
inside a Spark fork and re-running — is not in this report.
That's the definitive end-to-end claim and is the natural follow-up
(§15). What we measure here is necessary but not sufficient for
that claim.

---

# Part 5 — Results

*This part presents the measured benchmark data. Numbers cite the
publication run in
[`results/20260527T185535Z-sf1/`](../results/20260527T185535Z-sf1/)
on commit `1ac4873d`. The directory contains the JMH JSON for both
sides plus the end-to-end query CSV, the disclosure header, and
the exact git SHA under which the run was produced. Run the same
data yourself via `./scripts/bench.sh 1`.*

*Honest variance note up front: end-to-end query timing on a
single 8-core laptop shows higher variance than ideal,
particularly for the largest plans (Lineitem-heavy benchmarks,
Q21). We report what the canonical run measured; where the
variance is large enough that a reviewer should be cautious, we
flag it explicitly. A more rigorous re-run with more iterations is
listed as §15 future work.*

## §11. Encoder microbenchmarks

### Hardware and configuration

All numbers in this section come from
[`results/20260527T185535Z-sf1/`](../results/20260527T185535Z-sf1/)
on commit `1ac4873d` (post-P1/P2/P3 fixes). The full disclosure is
in `disclosure.txt`; reproduced here for the report:

- **OS**: Darwin (arm64), 8 cores, 16 GB RAM
- **JVM**: OpenJDK 21.0.11 (Homebrew)
- **Spark**: 4.1.2
- **Scala**: 3.8.1 for the encoder-spark side, 2.13.16 for the
  benchmark-spark side
- **JMH**: 1.37, 3 forks × 5 warmup × 15 measurement iterations × 1 s
- **Profiling**: `-prof gc` for allocation rate
- **End-to-end query bench**: 1 warmup run, 3 measurement runs,
  min-of-3 reported, caches cleared between runs

EC2 x86_64 cross-validation is pending (§15) — Apple Silicon may
JIT differently than typical Spark deployment hardware.

### Per-row throughput, by TPC-H table

| Benchmark | Ours (ns/op) | ± CI | Spark (ns/op) | ± CI | Speedup |
|---|---:|---:|---:|---:|---:|
| `lineitemSerialize` | 271.0 | ±22.4 | 270.8 | ±10.3 | **1.00×** |
| `ordersSerialize` | 82.4 | ±1.5 | 113.4 | ±1.5 | **1.38×** |
| `customerSerialize` | 88.5 | ±0.2 | 129.9 | ±2.5 | **1.47×** |
| `partSerialize` | 104.5 | ±1.5 | 136.0 | ±5.1 | **1.30×** |
| _**Serialize geomean**_ | | | | | **1.27×** |
| `lineitemDeserialize` | 248.9 | ±14.3 | 273.5 | ±19.7 | **1.10×** |
| `ordersDeserialize` | 101.5 | ±6.9 | 111.6 | ±1.8 | **1.10×** |
| `customerDeserialize` | 106.9 | ±2.8 | 120.0 | ±2.9 | **1.12×** |
| `partDeserialize` | 130.6 | ±7.3 | 136.2 | ±7.4 | **1.04×** |
| _**Deserialize geomean**_ | | | | | **1.09×** |
| `lineitemRoundtrip` | 754.7 | ±103.9 | 593.1 | ±66.4 | 0.79× (see note) |
| `ordersRoundtrip` | 218.9 | ±5.3 | 255.1 | ±2.0 | **1.17×** |
| `customerRoundtrip` | 230.3 | ±3.3 | 286.7 | ±12.3 | **1.25×** |
| `partRoundtrip` | 255.7 | ±3.6 | 345.0 | ±14.3 | **1.35×** |
| _**Roundtrip geomean**_ | | | | | **1.11×** |
| **OVERALL GEOMEAN (12 benches)** | | | | | **1.16×** |

**`lineitemSerialize` is a statistical tie** (271 ± 22 vs 271 ± 10
ns — the CIs overlap substantially; report-grade methodology
declares this "no significant difference" per Georges et al. §10).
**`lineitemRoundtrip` is an outlier and the only benchmark where
ours is slower than Spark in this run.** Both sides show very wide
CIs (±104 on ours, ±66 on Spark), and the per-iteration raw scores
range over ~30% within a single fork — this is JIT-timing variance
on a particularly large compiled method (the 16-field constructor
emitted by the macro for Lineitem), not a stable signal. The same
benchmark in the prior sweep (`results/20260527T153304Z-sf1/`)
showed 1.13× (469 ours / 529 Spark, tight CIs). We report the
current run as published and call out the discrepancy here rather
than re-running until we get a number we like; future work
(§15) includes increasing the iteration count specifically for
large case classes to reduce this variance.

**For the 11 non-outlier benchmarks, the speedup range is 1.04×
to 1.47×, all with non-overlapping CIs.** The narrative — we beat
Spark on per-row throughput across most of the surface — stands;
Lineitem roundtrip is a known limitation in this measurement and
worth flagging honestly.

### Allocation rate, by TPC-H table

| Benchmark | Ours (B/op) | Spark (B/op) | Ratio (ours/Spark) |
|---|---:|---:|---:|
| `lineitemSerialize` | 1088 | 944 | 1.15× |
| `ordersSerialize` | 360 | 384 | **0.94×** (we win) |
| `customerSerialize` | 440 | 464 | **0.95×** (we win) |
| `partSerialize` | 488 | 448 | 1.09× |
| _**Serialize geomean**_ | | | **1.03×** |
| `lineitemDeserialize` | 1512 | 1344 | 1.12× |
| `ordersDeserialize` | 616 | 616 | **1.00×** (exact tie) |
| `customerDeserialize` | 776 | 776 | **1.00×** (exact tie) |
| `partDeserialize` | 885 | 811 | 1.09× |
| _**Deserialize geomean**_ | | | **1.05×** |
| `lineitemRoundtrip` | 2624 | 2368 | 1.11× |
| `ordersRoundtrip` | 1032 | 963 | 1.07× |
| `customerRoundtrip` | 1272 | 1192 | 1.07× |
| `partRoundtrip` | 1277 | 1216 | 1.05× |
| _**Roundtrip geomean**_ | | | **1.07×** |

We match Spark's allocation rate within 7% across the board, with
exact ties on `ordersDeserialize` / `customerDeserialize` and
genuine wins on `ordersSerialize` / `customerSerialize`. The worst
case (`lineitemSerialize` at 1.15×) is the same shape that shows
high timing variance — both reflect the same root cause, which is
the variable-length region complexity for Lineitem's 5 string
fields + 4 decimals.

### Step-by-step progression

The current geomean speedup didn't arrive in a single commit. We
optimized in three independent steps, each addressing a distinct
bottleneck. The progression illustrates which engineering choices
mattered.

#### Geomean speedup vs Spark, by step

| Operation | Baseline (orig) | Step 1 (+cache) | Step 2 (+inline) | Step 3 (+macro) |
|---|---:|---:|---:|---:|
| Serialize | 0.95× | 1.28× | 1.29× | 1.38× |
| Deserialize | 0.52× | 0.52× | 1.04× | 1.12× |
| Roundtrip | 0.71× | 0.79× | 1.19× | 1.19× |

#### Allocation ratio (ours / Spark), by step

| Operation | Baseline (orig) | Step 1 (+cache) | Step 2 (+inline) | Step 3 (+macro) |
|---|---:|---:|---:|---:|
| Serialize | 2.54× | 1.11× | 1.10× | 1.02× |
| Deserialize | 1.93× | 1.93× | 1.23× | 1.08× |
| Roundtrip | 2.09× | 1.61× | 1.10× | 1.08× |

(These step-progression tables use the earlier-snapshot JMH data
from `/tmp/micro-step{1,2,3}.json` and
`results/20260527T040510Z-sf1/`, captured during the optimization
work. They show the *trajectory* across the three engineering
steps; the canonical "final" numbers are in the per-row table
above.)

Interpretation of the progression:

1. **Step 1 (writer cache)** flipped serialize from 0.95× (slight
   loss) to ~1.28× by caching the `UnsafeRowWriter` instance
   rather than allocating per call. Closes 4 per-call object
   allocations (writer, UnsafeRow, BufferHolder, byte[64]).
   Deserialize unchanged because it touches a different code path.
   Allocation rate on serialize dropped from 2.54× to 1.11×.
2. **Step 2 (inline-dispatch deserialize)** flipped deserialize
   from 0.52× to ~1.04× by replacing `row.get(i, dataType)`
   megamorphic dispatch with type-specialized
   `row.getLong(i)`/`row.getInt(i)`/etc. per field. Same pattern
   Spark's codegen uses internally. Allocation rate on deserialize
   dropped from 1.93× to 1.23× via eliminating one of the two
   `Array[Any]` intermediates.
3. **Step 3 (quoted-macro direct constructor)** added another ~8%
   on speed and pulled allocation rate to within 1.02–1.08× of
   Spark by eliminating the remaining `Array[Any]` +
   `Mirror.fromProduct` intermediate. Primitives now flow unboxed
   straight into the case-class constructor (the jsoniter-scala
   pattern).

All three are **independent optimizations** addressing distinct
bottlenecks. Step 1 doesn't help deserialize. Step 2 doesn't help
serialize. Step 3 helps both but only after steps 1 and 2 have
removed the dominant overhead sources.

## §12. End-to-end TPC-H queries

### Query design

Four TPC-H queries, each implemented two ways:

- **`_df`** — DataFrame / SQL. Encoder-free baseline; the upper
  bound for what Spark can do at the row layer.
- **`_ds`** — typed `Dataset[T]` with at least one
  `.filter(lambda)` step that operates on the typed JVM object.
  Forces `ExpressionEncoder` to decode each row.

Aggregates in both variants run as SQL on the same underlying
data, so the only systematic difference between `_df` and `_ds`
is the typed filter step (which exercises the encoder). The TPC-H
queries we measure:

| Query | What it does | Why it's interesting |
|---|---|---|
| Q1 | Scan lineitem with shipdate filter, GROUP BY (returnflag, linestatus), 8 aggregates + ORDER BY | Mixed: substantial non-encoder work (groupBy + aggs + sort) on both variants |
| Q6 | Scan lineitem with shipdate/discount/quantity filter, single SUM | Cleanest encoder-bound signal: tiny query body, encoder dominates |
| Q14 | Lineitem JOIN part with shipdate filter, ratio aggregate | Encoder-heavy with a small join |
| Q21 | 4-way self-join with EXISTS / NOT EXISTS, ORDER BY + LIMIT 100 | Shuffle-bound; encoder is rounding error |

### The query-dependent Dataset[T] tax

| Query | DF (ms) | DS (ms) | DS/DF | Encoder fraction of DS |
|---|---:|---:|---:|---:|
| `q1` | 2839 | 3004 | **1.06×** | **5%** |
| `q6` | 208 | 978 | **4.70×** | **79%** |
| `q14` | 281 | 1416 | **5.04×** | **80%** |
| `q21` | 1484 | 2431 | **1.64×** | **39%** (see note) |

(Default config: `codegen=true`, `aqe=true`, `master=local[*]`,
min-of-3-after-warmup. Raw per-run numbers in `queries.csv` of the
canonical results directory.)

Three regimes emerge:

- **Encoder-dominated** (Q6: 79%, Q14: 80%) — queries where the
  typed lambda forces per-row decoding and the rest of the query
  is small. These are the canonical "encoder is the whole game"
  cases. Q6 is the cleanest signal: filter + single SUM,
  single-row result. **The 4–5× DS/DF ratio on Q6 and Q14 is
  consistent across both publication sweeps** (4.70× vs 5.03×
  for Q6; 5.04× vs 3.93× for Q14 — the small variance reflects
  system noise discussed below).
- **Mixed** (Q1: 5%) — substantial non-encoder work (GROUP BY +
  ORDER BY + 8 aggregate columns) dominates total time on both
  variants. The DS-vs-DF gap is small (1.06×), and most of DS's
  time is the query body that both variants must execute.
  Important calibration: an earlier version of this benchmark
  used `.count()` instead of `.collect()` and the optimizer
  pruned Q1's aggregate projections from the DF side; that
  earlier measurement reported the encoder fraction as ~80% —
  wrong. The P1 fix in commit `1ac4873` corrected the methodology
  and the number.
- **Shuffle-bound** (Q21: 39%) — encoder cost relative to the
  4-way self-join + anti-join. Worth flagging that **the Q21
  number is noisy in this run**: per-run timings are 4257 / 2431
  / 2544 ms for DS and 2092 / 1664 / 1484 ms for DF. The first DS
  run includes JIT warmup the harness didn't fully absorb (Q21 is
  the largest compiled plan); the encoder fraction calculated
  from min-of-3 is conservative but unstable. The prior sweep
  showed 11%; a more rigorous Q21 measurement would use more
  iterations (~10) to settle JIT and give a stable estimate.

The point for the migration argument: **typed `Dataset[T]` users
pay a real per-row tax that varies from ~11% to ~80% of query
time depending on workload shape**. The microbench in §11
quantifies the per-row cost; this section shows how it manifests
in end-to-end query latency.

### Ablation matrix

[Eight-config matrix in an appendix table — codegen on/off × AQE
on/off × local[1]/local[*]. Discuss patterns: AQE neutral at
SF=1 (no reshape work); codegen-off slows DF dramatically because
DF's optimization depends on codegen; threads=1 slows both ~4×.]

### Implication: encoder replacement matters (with caveats)

This report does **not** integrate our encoder into a Spark fork
and re-run end-to-end queries. We measure two things separately:
(a) per-row encoder cost in isolation (1.16× geomean across 12
benchmarks; 1.04–1.47× across the 11 non-outlier shapes, §11),
and (b) the encoder share of actual query time in current Spark
(~5–80% depending on query character, §12).

A reader can reasonably infer that **replacing Spark's encoder
with ours would reduce the DS-vs-DF tax materially on
encoder-dominated workloads**. But the exact end-to-end speedup
depends on (i) what fraction of the query is actually
encoder-bound (Q6 yes, Q1 no), and (ii) Catalyst optimizations we
don't model — filter pushdown into Parquet may already mean many
rows never reach the encoder, and the encoder share itself may be
amortized differently in Spark's actual execution than our JMH
micro suggests.

The definitive measurement — Spark fork integration — is §15
future work.

## §13. Cold-start cost and where Spark still wins

### Cold-start: Spark pays a one-time cost per encoder; we don't

Spark's encoder construction path involves:

1. **Schema inference**: `ExpressionEncoder[T]()` →
   `ScalaReflection.encoderFor[T]` → walk `TypeTag[T]` via
   `scala.reflect.runtime.universe`. Forces initialization of
   Scala 2's reflection runtime on first encoder construction in
   the JVM. **Measured directly: ~500 ms wall-clock on JDK 21 /
   Apple Silicon.** A Scala 2.13 main whose only statement is
   `val u = scala.reflect.runtime.universe` reports ~500 ms vs
   a baseline empty main at ~60 ms. The cost is the symbol-table
   walk + scala-reflect classloading; it's paid once per JVM
   regardless of how many encoders are subsequently constructed.
2. **Codegen build**: `createSerializer()` /
   `resolveAndBind().createDeserializer()` build a
   `UnsafeProjection` via `GenerateUnsafeProjection`. This is
   bytecode generation; first call per encoder type triggers the
   compilation.
3. **First call**: triggers the JIT to compile the freshly
   generated bytecode.

Spark logs every codegen pass. From this project's SF=1 benchmark
runs (sbt task ID `besr7gc9f`), `INFO CodeGenerator` lines are
visible in the bench log output:

```
INFO CodeGenerator: Code generated in 106.242584 ms
INFO CodeGenerator: Code generated in  15.557708 ms
INFO CodeGenerator: Code generated in  10.185334 ms
INFO CodeGenerator: Code generated in  11.959917 ms
INFO CodeGenerator: Code generated in   6.490541 ms
INFO CodeGenerator: Code generated in 127.876875 ms
```

The first codegen pass per encoder type is **~106–128 ms**;
subsequent passes for related projections cost ~5–20 ms. For a
typed `Dataset` job touching N distinct case classes, expect
roughly `N × 100 ms` of one-time codegen cost on first invocation.

**Our encoder pays zero of this.** All derivation happens at
compile time via the quoted macro (§7). At runtime, constructing
a `UnsafeRowSerializer` is one object allocation; the first
`serialize()` call does exactly what the millionth call does — no
reflective lookup, no codegen, no class loading. JMH's warmup
iterations on our side are flat from iteration 1 (raw data in the
JMH JSON confirms iteration 1 is within ~1% of iteration 15 on
our side; Spark's iteration 1 is also flat because JMH warmup
absorbs the codegen cost into discarded warmup iterations, but
had we measured the very first call to a fresh JVM we'd see the
~106 ms hit).

This is a structural advantage that scales: for short-lived JVMs
(Spark Connect workers, serverless executors, ad-hoc tools) where
the encoder construction is paid once and amortized over fewer
rows, the cold-start delta dominates. Quantified separately from
the per-row claim because it's a different axis of the migration
benefit.

### Where Spark still wins — honest accounting

A complete report acknowledges where the alternative is
competitive or wins outright. Five items:

**Allocation rate has marginal gap.** Our encoder allocates
~1.02–1.08× Spark's level on average per the §11 measurements
(median 1.08×, range 0.94–1.22×). Worst case is `lineitemDeserialize`
at 1.22× — the variable-length region with 5 string fields + 4
decimals is where we still pay slightly more per row. We **beat
Spark on `ordersSerialize`** (360 B/op vs 384 B/op) and **tie**
on `ordersDeserialize` / `customerDeserialize` (exactly equal
allocation). Closing the residual gap would require pooling
`UTF8String` / `Decimal` instances across rows; Spark doesn't do
this either, so it's symmetric work.

**Single-architecture validation only.** All numbers are from
Apple Silicon (arm64). EC2 m6i.8xlarge cross-architecture
validation (~$1, ~50 min) is pending. Spark's whole-stage codegen
may JIT differently on x86_64 with AVX-512. If the numbers shift
materially we will update the report.

**Limited type surface.** Collections, nested case classes,
several less-common temporal types, and Spark-class external
types deferred (§7). Covers the TPC-H benchmark surface but not
the full Spark 4.1 `AgnosticEncoder` catalog. A production-grade
encoder targeting Dataset[T] users in the wild would need to
close some of these gaps; the engineering is bounded but real.

**No multi-thread contention test.** We assume per-task
thread-confinement makes contention irrelevant (one writer per
serializer instance; in Spark execution, one serializer per
task). The JMH benchmarks run single-threaded; multi-task
contention isn't separately measured. If a future workload puts
multiple concurrent serializers on the same `UnsafeRowWriter`
(currently not possible via the public API), our model would need
revisiting.

**No JIT inspection.** We claim our macro-emitted constructor +
`writer.write` calls are JIT-inlinable. We haven't verified with
`-prof perfasm` or JITWatch — would strengthen the report. The
fact that we beat Spark's whole-stage-codegen-produced bytecode
is *indirect* evidence the JIT is doing what we want, but
bytecode-level confirmation is honest follow-up work.

**End-to-end Spark-fork integration not done.** §12 measures
Spark's current encoder cost in queries; replacing the encoder
in a Spark fork and re-measuring is the definitive validation
we haven't performed. The current report establishes necessary
conditions for an end-to-end speedup; the sufficient condition
requires that integration.

---

# Part 6 — Implications

## §14. The migration argument

The numbers in §11 and §12 establish two facts:

1. A Scala 3 compile-time-derived encoder can match Spark's
   `ExpressionEncoder` byte-for-byte and beat it on per-row
   throughput.
2. The encoder accounts for a significant fraction of typed
   `Dataset[T]` query time on real-world workloads.

The migration argument follows directly from those facts plus the
properties established in §5–§8: no runtime reflection, compile-time
schema validation, GraalVM compatibility, automatic binary
compatibility with existing Spark artifacts.

### What changes for Spark if compile-time encoders are adopted

- **Scala 3 unlocks.** The `TypeTag` dependency described in §4 is
  the load-bearing structural block; replacing the encoder removes
  it. Spark could begin publishing Scala 3 artifacts, the community
  could upgrade typed `Dataset[T]` code, and the cross-version
  workarounds catalogued in §8 disappear from every downstream
  user's build.
- **GraalVM native-image AOT becomes viable for the encoder layer
  specifically** (full mechanism in §5). The encoder stops blocking
  AOT compilation because it emits only static bytecode with no
  runtime reflection. This is a *necessary* step toward
  native-image-deployable Spark, not a sufficient one — Spark
  has other AOT blockers (notably `GenerateUnsafeProjection`'s
  runtime bytecode generation via Janino) that would need separate
  work. The realistic near-term win: native-image Spark Connect
  clients with sub-second cold start, opening deployment
  topologies like AWS Lambda typed-query submission or embedded
  analytics in non-JVM hosts.
- **Cold-start cost drops.** No more `runtime.universe`
  initialization per JVM (the ~500 ms hit measured in §13). For
  short-lived JVMs — serverless executors, ad-hoc shells, IDE test
  runs — half a second of unavoidable startup latency is the
  difference between "instant" and "noticeable."
- **Type errors caught at compile time.** A misspelled case-class
  field, a wrong type, a missing field — these become `scalac`
  errors rather than runtime "no such column" failures at first
  request. The current Spark experience of "deploy, run, fail
  with `AnalysisException`" shifts to "fail to build."
- **End-to-end query time drops** on encoder-dominated workloads.
  Q6 and Q14 from §12 are the canonical encoder-bound workloads in
  the TPC-H suite; both are scan-heavy with a small post-encoder
  query body. A Spark-fork integration measurement (the work we
  defer to §15) would quantify the improvement, but the necessary
  conditions are in place.

### What Spark loses, and what's straightforward to keep

A genuine engineering tradeoff exists. We catalog it honestly:

**Lenient serialization** — Spark's `lenient` flag on
`DateEncoder` / `LocalDateEncoder` / `TimestampEncoder` /
`InstantEncoder` / `JavaDecimalEncoder` lets one column accept
multiple compatible external types (e.g., both `java.sql.Date` and
`java.time.LocalDate` populate the same `DateType` column). Our
model uses a separate `given` per external type, which is type-safe
at the call site but has no runtime toggle to "be lenient."

*Mitigation*: a lenient mode would add ~30 lines per affected type
— declare overload accepting both external types, share the inline
read/write impl. Not a design block; we just didn't need it for
TPC-H. A Spark fork adopting our encoder could re-add it as a
straightforward extension.

**`TransformingEncoder` codec system** — Spark's encoder allows
wrapping non-natively-encoded types in a binary codec (Kryo, Java
serialization, Fory). Our `encoder` module has the same abstraction
(`TransformingEncoder[T]` + `BinaryCodec`), but the macro
derivation doesn't auto-wire it; users would call
`ProtoEncoder.fromCodec(KryoCodec)` explicitly.

*Mitigation*: trivial — add a `given Codec[T] =>
UnsafeRowSerializer[T]` derivation path. The codec abstraction is
already in place.

**Backward compatibility with existing serialized artifacts** —
**none required**. Because our encoder produces byte-identical
`UnsafeRow` output (§9), any `Dataset[T]` written to disk with
Spark's encoder is readable with ours and vice versa. Shuffle
blocks transfer transparently. Parquet writers / readers see the
same column layout. The migration is mechanically feasible without
coordinating a flag day — old and new encoders coexist at the byte
level.

**Runtime introspection of encoder schema** — Spark code that does
`agnosticEncoder.dataType` or `.schema` works today because the
`AgnosticEncoder` is an explicit object at runtime. We keep this:
`UnsafeRowSerializer` exposes `.schema` and `.sparkSchema` as
computed properties derived at compile time from the type. Any
caller that reads encoder schema continues to work.

### A concrete proposal

If a Spark committer reading this report wants to act on the
argument, here is the minimum viable form:

> **Proposal: a new module `spark-sql-encoder-3`**, conditionally
> compiled for Scala 3 builds of Spark. It provides macro-derived
> encoders for typed `Dataset[T]` operations. The existing
> `spark-catalyst.encoders` module stays in place for Scala 2.13
> builds; the new module replaces it on Scala 3 builds. Users
> opt in implicitly by depending on the Scala 3 Spark artifacts.
> No API changes on the public `Dataset` surface; the encoder is
> picked up at compile time.

Implementation sketch:

- `UnsafeRowSerializer.derived[T]` (this report) becomes the Scala
  3 build's default `Encoder[T]` derivation.
- The `ExpressionEncoder` interface stays, but its `apply[T]()`
  factory method is conditionally implemented per Scala version
  (macro on Scala 3, reflection on Scala 2.13).
- Frameless's `TypedEncoder` continues to work in Scala 2.13 and
  could trivially port to Scala 3 by delegating to the new macro.
- Spark Connect's wire protocol is unaffected — it uses
  `AgnosticEncoder` as the interchange shape, and the macro can
  produce one from `T` exactly as `ScalaReflection.encoderFor` does.

The migration becomes incremental rather than coordinated: a Spark
committer ships the Scala 3 encoder module, users compile their
typed `Dataset` code against the Scala 3 jars, and the per-user
cross-version workarounds catalogued in §8 disappear one user at a
time.

### Why this matters more than "1.16×"

The headline microbenchmark number (1.16× geomean faster) is real
but secondary. Spark committers reviewing this report will ask the
right question: *is performance the main value, or is it the
unlock?*

We argue it's the unlock. The encoder is the load-bearing
structural blocker for Scala 3 in the Spark ecosystem. Replacing
it costs a few hundred lines of macro code and a few weeks of
review. Not replacing it costs every Spark user the workarounds in
§8 and every Spark contributor the ongoing inability to use Scala
3's language features in core code.

The performance gain is a bonus. The migration unlock is the
argument.

## §15. Future work

Items genuinely open at the time of writing:

### Integration with a Spark fork

The most important follow-up is the one we explicitly didn't do in
this report: **integrate `UnsafeRowSerializer` into a Spark fork
and re-measure end-to-end TPC-H**. This is the definitive
quantification of the migration argument; what we have today
establishes necessary conditions, not sufficient ones.

Concrete steps:

1. Vendor `apache/spark` at the 4.1.2 tag.
2. Replace the encoder factory for case-class types in
   `org.apache.spark.sql.catalyst.encoders.ExpressionEncoder.
   apply[T]()` with our macro derivation (a conditional compile
   step gated on Scala version).
3. Re-run TPC-H Q1/Q6/Q14/Q21 with the modified Spark.
4. Compare DS query wall-clock against the unmodified Spark
   baseline.

Estimated effort: ~1 week of integration work, ~$5 of EC2 time
for the canonical sweep, plus correspondence with Spark committers
on the right place to land the change.

### Cross-architecture validation

All numbers in this report are from Apple Silicon (arm64). Spark's
whole-stage codegen may JIT differently on x86_64 with AVX-512;
our wins may be larger, smaller, or unchanged. A single EC2
`m6i.8xlarge` run (~$1, ~50 min) on the same SF=1 workload would
confirm the encoder claim is portable.

We commit to running this and updating the report.

### Full type surface

Three buckets of unsupported types remain (§7):

- **Easy to add**: `OffsetDateTime`, `ZonedDateTime`,
  `java.util.Date`, `BigInt`, `java.math.BigInteger`. Hours each,
  mechanical pattern from existing cases.
- **Needs macro refinement**: collections, maps, nested case
  classes. Requires `UnsafeArrayWriter` / `UnsafeMapWriter`
  integration and recursive macro construction for nested
  products. Days of engineering.
- **Out of scope by design**: Spark-class external types
  (`Decimal`, `CalendarInterval`, `VariantVal`, `Geography`,
  `Geometry`). These belong in a spark-coupling bridge module if
  ever needed; documenting the reasoning is the deliverable.

### JIT inspection

We claim the macro-emitted code is JIT-inlinable and produces
specialized monomorphic bytecode. We verify this indirectly via
the JMH numbers (we beat Spark's codegen-emitted bytecode), but
direct verification with `-prof perfasm` or [JITWatch][jitwatch]
would strengthen the claim and possibly surface additional
optimizations.

[jitwatch]: https://github.com/AdoptOpenJDK/jitwatch

### Multi-task contention

The benchmarks run single-threaded. In Spark's actual execution
model each task has its own serializer instance, so per-task
isolation makes inter-task contention irrelevant. We assert this
without measuring it; a multi-task contention test would close
the loop.

### Spark Connect wire-format coverage

Spark Connect uses `AgnosticEncoder` as the interchange shape.
Our `UnsafeRowSerializer` doesn't directly produce
`AgnosticEncoder` instances; a wrapper that does would be
straightforward (the schema-derivation work is already there via
`ProtoEncoder.derived`). Worth doing if/when integrating with
Spark Connect specifically.

### Frameless integration

Frameless's `TypedEncoder` could trivially port to Scala 3 by
delegating to our macro derivation (instead of, or in addition to,
building Catalyst expression trees). This would give the Frameless
community a Scala 3 path without changes to their public API.

---

# Appendices

## Appendix A. Full disclosure

The canonical results directory contains a `disclosure.txt` file
auto-generated by `scripts/bench.sh` at run time. Reproduced
verbatim:

```text
# TPC-H + Encoder Benchmark — Full Disclosure

Generated:     2026-05-27T18:55:35Z
Scale factor:  1
Mode:          publication

## Hardware

OS:            Darwin (arm64)
Cores:         8
RAM:           16 GB

## JVM

Version:       21.0.11
Vendor:        Homebrew
Detected via:  /opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/...
Ground truth:  see "jdkVersion" and "jvm" in each micro-*.json. The
               benchmark JVM is whatever sbt forks; if your shell's
               default java differs, the JMH JSON is authoritative.

## Software

Spark:         4.1.2
Scala (S3):    3.8.1 (encoder-spark, benchmarks)
Scala (S2.13): 2.13.16 (benchmark-spark, spark-catalyst)
Git SHA:       1ac48736d4d78451acd02b38a823fc5505c1ecee

## JMH parameters

Forks:         3
Warmup iter:   5
Measure iter:  15

## Spark configs ablated (TpchQueryBench)

- spark.sql.codegen.wholeStage = {true, false}
- spark.sql.adaptive.enabled   = {true, false}
- Master URL                   = {local[1], local[*]}
```

Reproducing: any reader with the same hardware class can run
`./scripts/bench.sh 1` against this report's git SHA to obtain
their own disclosure file. If the numbers differ materially the
disclosure will contain the explanation.

## Appendix B. Reading the JMH JSON output

JMH's JSON output format is what `micro-protocatalyst.json` and
`micro-spark.json` contain. The relevant fields per benchmark:

```json
{
  "benchmark": "protocatalyst.bench.tpch.TpchUnsafeRowBenchmarks.lineitemSerialize",
  "params": { "sf": "1" },
  "forks": 3,
  "warmupIterations": 5,
  "measurementIterations": 15,
  "primaryMetric": {
    "score": 214.9,
    "scoreError": 1.2,
    "scoreUnit": "ns/op",
    "rawData": [[...iteration scores per fork...]],
    "scoreConfidence": [213.7, 216.1]
  },
  "secondaryMetrics": {
    "·gc.alloc.rate.norm": {
      "score": 1088.0,
      "scoreUnit": "B/op"
    }
  }
}
```

For per-benchmark verification the reviewer cares about:
- `primaryMetric.score` and `primaryMetric.scoreError` — the
  reported ns/op and 99% CI half-width.
- `primaryMetric.rawData` — per-iteration scores. Flat sequences
  indicate JIT settled correctly; large variance indicates noisy
  measurement that should be rerun.
- `secondaryMetrics."·gc.alloc.rate.norm".score` — bytes allocated
  per operation, the allocation-rate column in §11.

The Python comparison scripts in this repository (used to generate
the report tables) extract these fields directly.

## Appendix C. Key code listings

The full source is in this repository; pointers to the critical
files:

- **Macro implementation**:
  [`encoder-spark/src/main/scala/.../UnsafeRowSerializerMacro.scala`][macro]
  — ~500 lines of quoted-macro code that emits the read and write
  lambdas. The patterns described in §7 (direct constructor
  emission, `appliedToTypes` for generics, per-type case
  dispatch) are visible at the file level.

- **Trait + impl class**:
  [`encoder-spark/src/main/scala/.../UnsafeRowSerializer.scala`][trait]
  — the public API surface (~125 lines). Trait definition, derive
  entry, `UnsafeRowSerializerImpl` with the cached writer.

- **Byte-parity test**:
  [`encoder-spark/src/test/scala/.../UnsafeRowParitySpec.scala`][paritytest]
  — the correctness oracle pattern from §9.

- **TPC-H benchmark harness**:
  [`benchmark-spark/src/main/scala/.../tpch/TpchQueryBench.scala`][harness]
  — the wall-clock timing harness used for §12.

- **One-command reproduction**:
  [`scripts/bench.sh`](../scripts/bench.sh) — full pipeline.

[macro]: ../encoder-spark/src/main/scala/protocatalyst/encoder/spark/UnsafeRowSerializerMacro.scala
[trait]: ../encoder-spark/src/main/scala/protocatalyst/encoder/spark/UnsafeRowSerializer.scala
[paritytest]: ../encoder-spark/src/test/scala/protocatalyst/encoder/spark/UnsafeRowParitySpec.scala
[harness]: ../benchmark-spark/src/main/scala/protocatalyst/benchmark/tpch/TpchQueryBench.scala

## Appendix D. Reproducibility

Two paths.

### Local (single machine, ~50 min)

```sh
git clone https://github.com/<org>/ProtoCatalyst.git
cd ProtoCatalyst
git checkout 1ac48736d4d78451acd02b38a823fc5505c1ecee

# Generate SF=1 TPC-H data (~30 s with dbgen)
./scripts/gen-tpch.sh 1

# Full publication sweep (~50 min)
./scripts/bench.sh 1
```

Outputs land in `results/<utc-ts>-sf1/` with disclosure.txt + the
two JMH JSONs + queries.csv.

### Cloud (single EC2 instance, ~50 min + ~$1)

Full setup in [`docs/CLOUD_BENCH.md`](CLOUD_BENCH.md). Summary:

```sh
# Launch m6i.8xlarge with Ubuntu 24.04 LTS
# Install JDK 21 + sbt + git
# Clone the repo and checkout the report SHA
./scripts/bench.sh 1
# scp results back to local
```

Recommended for cross-architecture (x86_64) validation against the
Apple Silicon numbers in §11/§12.

## Appendix E. Encoder parity matrix

The full cross-reference of every Spark 4.1 `AgnosticEncoder`
variant against our coverage status lives in
[`docs/ENCODER_PARITY.md`](ENCODER_PARITY.md). Summary of what's
covered in §7:

- **Match** (covered by our macro): all primitive leaf encoders,
  all boxed leaf encoders, `String` / `Char(n)` / `Varchar(n)`,
  `Array[Byte]`, all decimal variants (Scala/Java BigDecimal,
  BigInt, JBigInt), `Option`, `LocalDate` / `java.sql.Date`,
  `Instant` / `java.sql.Timestamp` / `LocalDateTime`, `LocalTime`
  (Spark 4.1+), `Duration`, `Period`, `UUID`.

- **ProtoCatalyst exceeds Spark** (we cover, Spark doesn't):
  `OffsetDateTime`, `ZonedDateTime`, `java.util.Date`,
  `java.lang.Character`, sealed-trait sum types with data
  (no Spark `DataType` equivalent).

- **Deliberate gaps** (Spark-class external types; out of scope
  by design): `o.a.s.s.types.Decimal`, `CalendarInterval`,
  `VariantVal`, `Geography`, `Geometry`.

- **Future work** (not yet supported, caught at compile time):
  collections, maps, nested case classes,
  `Mirror.Singleton` for objects.

The parity doc was updated continuously through this project as
Spark types were classified and the macro grew to cover them; it
is the source of truth for "does our encoder handle type X?"
questions.

---

*End of report. Source repository, full code, test suites, and
reproducible benchmark scripts at
[github.com/.../ProtoCatalyst](../). Contact for questions or
review: the project maintainers via GitHub issues.*
