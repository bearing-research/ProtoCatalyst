# Replacing Spark's Reflective Encoder Derivation: A Compile-Time Path to Scala 3

Apache Spark 4.x publishes only Scala 2.13 artifacts. The typed-`Dataset[T]` API derives every
encoder through `ScalaReflection.encoderFor[T: TypeTag]`, which is built on Scala 2's runtime
reflection (`scala.reflect.runtime.universe`); `TypeTag` and that reflection API have no Scala 3
equivalent. The derivation also carries runtime cost: a per-JVM reflective initialization (~1 s on
first use) and a global lock taken on every subtype check during derivation.

This report describes replacing the reflective derivation with compile-time Scala 3 `Mirror`
derivation that produces Spark's existing `AgnosticEncoder`, so the remainder of the encoder pipeline
is reused unchanged. The properties of the replacement, with section references:

- **Correctness (§8).** The derived `AgnosticEncoder` is structurally identical to the one
  `ScalaReflection.encoderFor` produces — same node tree, field names, nullability, decimal
  precision/scale, and flags, with class names compared by normalized simple name — across the common
  type surface.
- **Derivation cost (§9).** Compile-time derivation is ~389× faster single-threaded in the
  microbenchmark, and avoids the ~1 s per-JVM reflective cold-start that short-lived drivers
  (serverless, Glue, CI) pay per run. It also removes the global derivation lock (§9d), which affects
  concurrent derivation in a single JVM.
- **Type coverage (§7).** It encodes simple Scala 3 `enum`s, which Spark's reflection does not, and
  detects and rejects data-carrying `enum`s / sealed-trait ADTs — a case Spark's `AgnosticEncoder`
  model cannot represent — rather than mis-encoding them.
- **End-to-end execution (§3).** Stock Spark's serializer codegen cannot initialize `ScalaReflection`
  on Scala 3. A two-line change to `ScalaReflection` removes that, after which compile-time-derived
  encoders round-trip values through Spark's unmodified codegen ser/deser from a Scala 3 process.

The claims are backed by code in this repository and checked against stock Spark, used as the
correctness oracle and benchmark baseline. Companion documents:
[`REFLECTION_REPLACEMENT.md`](REFLECTION_REPLACEMENT.md) (bridge design and parity surface),
[`SCALA3_SUPERSET.md`](SCALA3_SUPERSET.md) (Scala-3-specific behavior), and
[`INFRASTRUCTURE.md`](INFRASTRUCTURE.md) (cross-version build topology, how to run everything, and the
measurement-validity rationale behind §9).

> **Scope.** A separate change — replacing Spark's per-row *serializer* codegen with
> compile-time-specialized `UnsafeRow`/Arrow encoders — is faster on the per-row hot path, but is not
> required for, and does not follow from, the derivation replacement. It is summarized in §10 and
> documented in the archived [`REPORT_encoder_perf.md`](archive/REPORT_encoder_perf.md).
>
> **Broader context.** This encoder work sits inside a larger compile-time query compiler whose IR is
> designed to be engine-independent. That thesis is exercised separately by a cross-backend harness
> that compiles a query once and runs the same plan on two unrelated engines (a local Arrow executor
> and DataFusion), comparing results — see [`../compiler/CROSS_BACKEND.md`](../compiler/CROSS_BACKEND.md).
> It is independent of, and not load-bearing for, the encoder argument here.

---

# Part 1 — The blocker

## §1. The Scala-3 blocker: encoder derivation

Spark 4.x publishes only Scala 2.13 artifacts. The obstacle is not the bulk of the codebase — it is
the typed-encoder layer. `Dataset[T]`'s `ExpressionEncoder[T]` is derived from a `TypeTag[T]` via
`ScalaReflection`, and `TypeTag` plus `scala.reflect.runtime.universe` are Scala-2 constructs with
no Scala-3 equivalent. A Scala 3 port of Spark cannot keep `ScalaReflection`; it has to derive
encoders some other way. That single requirement is the structural Scala-3 blocker for the typed
API, and it is the subject of this report.

The blocker is contained: Spark's encoder pipeline already has a reflection-free seam —
`AgnosticEncoder` — and only the derivation in front of it is reflective.

## §2. The pipeline, and where the reflection lives

```
T  ──ScalaReflection.encoderFor[T: TypeTag]──►  AgnosticEncoder[T]  ──ExpressionEncoder(_)──►  Catalyst Expression trees  ──►  whole-stage codegen
   ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^      (pure ADT)            (no reflection)               (no reflection)
       the only reflective step
```

`AgnosticEncoder[T]` is a pure algebraic data type — field names, nullability, sub-encoders,
`DataType`, `ClassTag`. It carries no reflection and no Catalyst expressions; it is purely a
*description*. Everything downstream of it (`ExpressionEncoder`, `SerializerBuildHelper` /
`DeserializerBuildHelper`, codegen) does no reflective *derivation* — with one caveat the wall in §3
makes precise: the emitted ser/deser code calls small co-located `ScalaReflection` *utilities*
(`encodeFieldNameToIdentifier`, a name-mangler; `findConstructor`), so the `ScalaReflection` object
must still be de-reflected even though no type analysis happens downstream. The reflective derivation
itself is confined to producing the `AgnosticEncoder` in the first place:

```scala
// org.apache.spark.sql.catalyst.ScalaReflection  (spark-sql-api)
val universe = scala.reflect.runtime.universe              // Scala-2 runtime reflection
def encoderFor[E: TypeTag]: AgnosticEncoder[E] = { … }     // the only reflective entry point
```

Crucially, `AgnosticEncoder` is also where Spark accepts an encoder *without* a `TypeTag`:

```scala
object ExpressionEncoder {
  def apply[T](enc: AgnosticEncoder[T]): ExpressionEncoder[T] = { … }   // no TypeTag required
}
```

So if we can produce `AgnosticEncoder[T]` at compile time, `ExpressionEncoder(ours)` and all of
Spark's downstream are reused verbatim. `encoderFor` is therefore the substantive change.

A surrounding cohort of ~16 files (`Encoders`, `Dataset`, `SparkSession`, `functions`,
`UDFRegistration`, `literals`, …) merely *thread a `TypeTag`* into `encoderFor`. On Scala 3 those
become `[T]`-with-a-derived-given signature swaps — forced anyway, since `TypeTag` doesn't exist —
and require no real reimplementation. The one function that does is `encoderFor`.

**Is it really just `encoderFor`? A check across all of Spark.** A scan of all 22 Spark
modules' sources confirms how narrow the structural surface is. First, Spark SQL and core define
**no Scala 2 `def` macros at all** — the one Scala-2 construct that would force a genuine rewrite is
simply absent. Second, every other `scala.reflect.runtime` / `TypeTag` use is one of two
*non*-structural kinds:

- **Consumers that ride on this same fix.** MLlib's typed API is not a second derivation engine — it
  *calls* this one. `UnaryTransformer[IN: TypeTag, OUT: TypeTag]` carries those tags solely to feed
  `functions.udf(createTransformFunc)` (which routes into `encoderFor`), and `checkSchema[Data: TypeTag]`
  calls `ScalaReflection.schemaFor[Data]` directly. Both become the same mechanical context-bound →
  derived-given swap once `udf`/`schemaFor` are swapped — no new logic.
- **Two genuinely independent touchpoints, both mechanical (not structural).** `PhysicalDataType.tag`
  in Catalyst (~20 `typeTag[InternalType]` sites) exists only to produce a runtime `Class` for array
  allocation at a single call site (`collectionOperations`) and is replaceable with `ClassTag`; and
  three `runtimeMirror(...).staticClass(...)` class-loaders (the HBase token provider, MLlib
  `FPGrowth`/`PrefixSpan`) are replaceable with `Class.forName`. Neither is a second `encoderFor`.

So the derivation is the *only structural* Scala-3 refactor; everything else is mechanical, and the
worst-case hazard (def macros) does not exist in Spark at all.

## §3. Running Spark's serializer from Scala 3: the `ScalaReflection` initialization failure

One might try to call Spark's `ExpressionEncoder` from a Scala 3 process directly, bypassing the
derivation. This does not work: stock Spark's codegen path touches `ScalaReflection`, and the
`ScalaReflection` object cannot initialize against the Scala 3 standard library.

Building and running an encoder's serializer from a Scala 3 process crashes:

```
java.lang.ExceptionInInitializerError
  at o.a.s.s.catalyst.expressions.objects.Invoke.encodedFunctionName(objects.scala:468)
  at o.a.s.s.catalyst.encoders.ExpressionEncoder$Serializer.apply(...)
Caused by: scala.reflect.internal.FatalError: class Array does not have a member apply
  at scala.reflect.runtime.JavaUniverseForce.force(...)
  at o.a.s.s.catalyst.ScalaReflection.<clinit>(...)
```

The chain: `Invoke.encodedFunctionName` (emitted in every serializer/deserializer for String/object
fields) calls `ScalaReflection.encodeFieldNameToIdentifier`, which is `TermName(name).encodedName` —
a `scala.reflect.runtime.universe` use. Merely *touching* the `ScalaReflection` object eagerly
initializes its `val universe = scala.reflect.runtime.universe`, and forcing that universe against
the Scala 3 stdlib throws `FatalError: class Array does not have a member apply`.

This is not specific to our setup. The same failure is tracked upstream as
[scala/scala3#25896](https://github.com/scala/scala3/issues/25896) — an open, high-priority **regression**
in which `scala.reflect.runtime.universe` fails to initialize on the Scala 3.8+ standard library
(identical `JavaUniverseForce.force` / "class `Array` does not have a member `apply`"), explicitly
naming Apache Spark as an affected consumer. Being a *regression* (it worked on earlier Scala 3), it is
a moving target that worsens as the ecosystem advances to 3.8+, not a static incompatibility — which
sharpens the motivation: replacing the reflective path is not merely *enabling* Scala 3, it is the
practical workaround for a compiler regression that, as of writing, has no upstream fix.

The scope is narrow: not only is `encoderFor` reflective, but the `ScalaReflection` *object* fails to
initialize on Scala 3 because of one eager field. A scan of the entire expression/encoder layer
finds references to `ScalaReflection` in only **3 files via 5 members** — `encoderFor`, `schemaFor`,
`Schema`, `encodeFieldNameToIdentifier` (a name-mangling utility, replaceable by
`scala.reflect.NameTransformer.encode`), and `findConstructor` (Java reflection with a small
scala-reflect fallback). Removing the eager `val universe` de-poisons the trivial utilities; the
substantive work is `encoderFor`/`schemaFor`. (Detail: `REFLECTION_REPLACEMENT.md` §2.1.)

**The wall is removable — demonstrated.** Two lines of `ScalaReflection` cause the crash above, and
patching exactly those two removes it:

1. `val universe` → **`lazy val universe`** — the object's static initializer no longer forces
   runtime reflection (it is forced only if `encoderFor`/`schemaFor`/`findConstructor`'s fallback is
   actually *called*, none of which the ser/deser execution path does); and
2. `encodeFieldNameToIdentifier` uses **`scala.reflect.NameTransformer.encode`** (a scala-library
   function with identical name-mangling) instead of `universe.TermName(_).encodedName`.

The `lazy val` is a *deferral*, not a cure: it confines the crash to the universe-dependent members so
the ser/deser **execution** path runs, but any call into `encoderFor[T: TypeTag]`/`schemaFor[T]` would
still force the universe and still hit [scala/scala3#25896](https://github.com/scala/scala3/issues/25896).
The only **complete** fix is to not need the runtime universe at all — i.e. the compile-time derivation
(§6) that produces an `AgnosticEncoder` without ever touching `scala.reflect.runtime`. The patch
unblocks execution today; the derivation removes the dependency.

We verify this concretely. `spark-reflection-patch` is a verbatim copy of Spark 4.1.2's
`ScalaReflection` with only those two lines changed, compiled on Scala 2.13 and placed ahead of
`spark-catalyst` on the test classpath so it shadows Spark's copy. With it, `ExecutionWallSpec` (in
`encoder-spark`, **a Scala 3 module**) round-trips real values — flat and nested products, all
primitive widths, `java.lang` boxed types, `Some`/`None`, maps, collections including `Array`,
collection/map/option *of* a case class, and tuples — through Spark's **unmodified** codegen
serializer and deserializer. Eleven cases, all green.

This upgrades the report's correctness argument from structural to **observed**: the
`AgnosticEncoder` we derive at compile time (no `TypeTag`, no reflection, §6) drives Spark's actual
ser/deser and reproduces the input. Structural parity (§8) remains the broad oracle across the full
type surface; the end-to-end spec is the existence proof that identical structure does yield
identical runtime behavior — and that the wall is a two-line change, not a rewrite.

This patch is *transitional*: it exists only to run Scala 3 against Spark's stock **2.13** jars here.
A Scala-3 Spark has no eager `val universe` to defer and no `TermName` mangler to swap — that code
isn't written. So the migration does not "patch the wall"; the wall is an artifact of stock-2.13-jar
execution, and the Scala-3 end-state simply *deletes* the reflective surface (see §12, *End-state vs.
transitional*). The two lines measure how shallow the downstream coupling is, not a fix Spark must carry.

## §4. A global lock serializes encoder derivation

Even on Scala 2.13, `encoderFor` is expensive in a way that is easy to miss. Its dispatch is a long
chain of subtype checks — `case t if isSubtype(t, localTypeOf[X])`, one per leaf type — and **every
check takes a single global lock**:

```scala
// org.apache.spark.sql.catalyst.ScalaReflection
private[catalyst] object ScalaSubtypeLock
private[catalyst] def isSubtype(tpe1: Type, tpe2: Type): Boolean =
  ScalaSubtypeLock.synchronized { tpe1 <:< tpe2 }
//   ^ "This operator is not thread safe in any current version of scala" — scala/bug#10766
```

Because Scala 2's `<:<` is not thread-safe, Spark must serialize *all* encoder derivation in a JVM
on one monitor. Single-threaded this is a small constant tax (an uncontended lock); it only bites
when one JVM derives encoders on many threads at once. That is **narrower than it sounds** — the
Spark Connect *server* does not reflectively derive (the client builds the encoder and ships it; §9d),
and JDBC/Thrift traffic is untyped SQL with no encoders. The genuine case is a *long-lived,
multi-threaded application that builds typed `Dataset`s concurrently* — e.g. a query-serving service
on a shared `SparkSession`, or threaded job submission. Compile-time Scala 3 derivation does the type
analysis at `scalac` time, so there is no runtime `<:<` and no lock. The lock's effect is
quantified in §9d; the more broadly applicable costs are single-thread derivation and cold-start (§9).

---

# Part 2 — The replacement

## §5. The seam: replace only `encoderFor`

The replacement plugs in at the existing seam and nowhere else:

```scala
// today (Scala 2.13, reflective):
val enc: ExpressionEncoder[T] = ExpressionEncoder[T]()              // uses ScalaReflection.encoderFor

// proposed (Scala 3, compile-time):
val agnostic: AgnosticEncoder[T] = deriveAgnosticEncoder[T]        // our macro — no reflection, no TypeTag
val enc: ExpressionEncoder[T]    = ExpressionEncoder(agnostic)     // unchanged Spark code
```

Because `ExpressionEncoder.apply(AgnosticEncoder)`, `Dataset`, and `SparkSession` are ordinary JVM
classes, this is callable from Scala 3 against Spark's 2.13 jars (`CrossVersion.for3Use2_13`), which
is how we test it without forking Spark.

## §6. Compile-time derivation, and the bridge to `AgnosticEncoder`

The project already has a compile-time, engine-independent encoder — `ProtoEncoder` — derived via
Scala 3 `Mirror`/`inline` (`summonInline`, `erasedValue`, `constValue`), with its own
`ProtoType`/`ProtoSchema` IR. `deriveAgnosticEncoder[T]` is the composition of two pieces:

```scala
ProtoEncoder.derived[T]                 // compile-time Mirror derivation → ProtoEncoder[T] (own IR)
AgnosticEncoderBridge.toAgnostic(_)     // lowers ProtoEncoder[T] → Spark's AgnosticEncoder[T]
```

`toAgnostic` is a plain recursive function (no macro): it dispatches on the `ProtoType` shape plus
`clsTag.runtimeClass`, building Spark's own `AgnosticEncoder` nodes. Three details make it faithful:

- **`clsTag` disambiguates normalized leaves.** `ProtoEncoder` normalizes `BigInt` and `BigDecimal`
  both to a Decimal `ProtoType`, `UUID` and `String` both to String — but each node carries its
  `ClassTag`, so the bridge recovers the exact Spark node (`BigInt → ScalaBigIntEncoder`, not a
  generic Decimal). This matters: Spark's `ScalaBigIntEncoder` round-trips `BigInt ↔ Decimal`, where
  a plain Decimal node would mishandle a `BigInt` field.
- **Nullability follows Spark's rule.** Field nullability is taken from the *lowered child's*
  `.nullable` (`= !isPrimitive` in Spark's model), not from `ProtoEncoder`'s flag — exactly Spark's
  `EncoderField(name, enc, enc.nullable)`.
- **Collections compose case-class elements.** Deriving `Seq[Address]` requires the element to be a
  case class, which has no freely-summonable given. The collection/`Option`/`Map` givens are
  therefore `inline given`s that resolve the element through the inline `summonEncoder`, whose
  `Mirror.ProductOf → derived` branch composes the case class — a Scala 3 metaprogramming pattern
  that replaces what Spark does with reflection.

The dispatch order, leaf mapping, decimal/temporal defaults (`DecimalType(38,18)`, strict dates),
and cycle handling all mirror `ScalaReflection.encoderFor` exactly; the full design is in
`REFLECTION_REPLACEMENT.md`.

## §7. Scala 3 types outside Spark's encoder model

Parity is only the *subset* where Spark has an encoder. Because the derivation is Scala 3 native, it
also handles types Spark's 2.13 reflection cannot. Here we *define* behavior (there is no Spark to
match), and the bridge does so honestly:

- **Simple `enum`** (`enum Color { case Red, Green, Blue }`) →
  `TransformingEncoder(clsTag, StringEncoder, valueOf-codec, nullable = true)`: a faithful
  round-trip via the companion's `valueOf` (Java reflection, *not* the `scala.reflect.runtime` we
  remove). A bare `StringEncoder` would be lossy — deserialize would yield a `String`, not the enum.
- **Data-carrying `enum` / sealed-trait ADT** (`enum Shape { case Circle(r); case Square(s) }`) → a
  **clean rejection**: Spark's `AgnosticEncoder` model has *no sum-type representation at all*. This
  is a concrete gap in Spark's encoder model that a Scala-3 port would grow — surfaced, not hidden.

Separately, the bridge also encodes types Spark's reflection *rejects outright* even on 2.13 —
`java.util.UUID`, `java.time.OffsetDateTime`, `java.time.ZonedDateTime` — as String-backed
`TransformingEncoder`s with a plain `toString`/`parse` codec (ISO-8601, so the offset/zone survives;
a Timestamp base would not). These aren't Scala-3-specific, but they show the derivation is
*extensible* where Spark's is closed. Like the `enum`, they have no Spark golden, so they're
validated by self-consistent end-to-end round-trips (§3).

The full catalog of Scala-3 behaviors and their implementations is in `SCALA3_SUPERSET.md`.

---

# Part 3 — Results

## §8. Faithfulness: structural parity of the derived `AgnosticEncoder`

Correctness is established by showing the compile-time `AgnosticEncoder` is *structurally identical*
to the one Spark's reflective `encoderFor` produces — same nodes, field names, nullability, decimal
precision/scale, `lenientSerialization` flags, and `clsTag` *simple-names* (the canonical dump
normalizes class names, so this asserts node identity, not full `ClassTag` equality) — so Spark's
unchanged downstream behaves identically.

`encoderFor[T: TypeTag]` exists only in Scala 2.13, so goldens are generated there
(`AgnosticParityFixtures`) and compared on the Scala 3 side (`AgnosticEncoderBridgeSpec`) via a
canonical, class-name-normalized structural dump — the cross-compile fixture pattern this project
uses throughout (the same approach validates the Arrow wire format byte-for-byte). For example,
Spark and our bridge both produce, for a flat `Person(id: Int, name: String, active: Boolean, score:
Double)`:

```
Product[Person](id:PrimitiveIntEncoder!, name:StringEncoder?, active:PrimitiveBooleanEncoder!, score:PrimitiveDoubleEncoder!)
```

Every case in the corpus matches Spark's canonical structural dump (modulo class-name normalization):

| Group | Coverage |
|---|---|
| Primitives | 7 unboxed (`Primitive*Encoder`) + 7 boxed (`Boxed*Encoder`) |
| Scalars | `String`, `Array[Byte]`, Scala/Java `BigDecimal` at `DecimalType(38,18)`, Scala/Java `BigInt` at `(38,0)` |
| Temporal | `LocalDate`/`java.sql.Date`, `Instant`/`java.sql.Timestamp`, `LocalDateTime`, `Duration`, `Period` (all `lenient=false`) |
| Composite | `Option`, `Seq`/`List`/`Vector`/`Set`/`Array`, `Map`, nested case classes, tuples |
| Combinations | `Seq[Option[Int]]`, collection/map/option **of a case class** (`Seq[Address]`, `Map[String,Address]`, `Option[Address]`), and a **tuple of a case class** (`(String, Address)` → `Product[Tuple2]`) |

The disambiguations a reviewer reaches for first all hold: `BigInt` vs `BigDecimal` (same Decimal
type, different node), `LocalDate` vs `java.sql.Date`, `Instant` vs `java.sql.Timestamp`,
boxed vs unboxed, exact decimal precision/scale, and `Seq[CaseClass]`. (Full corpus and design:
`REFLECTION_REPLACEMENT.md`.)

Structural identity is the broad oracle here, but it is not the only evidence: §3 closes the loop by
*executing* these encoders. With the two-line `ScalaReflection` patch, `ExecutionWallSpec` drives a
representative slice of this same corpus through Spark's unmodified codegen ser/deser from a Scala 3
process and round-trips the values — confirming that identical structure does produce identical
runtime behavior.

## §9. Derivation cost

Two JMH suites, Throughput mode, derive the *same* `AgnosticEncoder[Lineitem]` (16 fields) — an
apples-to-apples "type → encoder description" comparison. `SparkEncoderDerivationBenchmarks` (Scala
2.13) times `ScalaReflection.encoderFor[Lineitem]`; `EncoderDerivationBenchmarks` (Scala 3) times
`AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[Lineitem])`.

| Threads | Spark `encoderFor` (ops/s) | Compile-time (ops/s) | Speedup |
|---:|---:|---:|---:|
| 1 | 2,304 ± 9 | 896,924 ± 7k | **~389×** |
| 8 | 1,660 ± 26 | 3,926,237 ± 125k | ~2,365× |

Single-threaded, compile-time derivation is ~400× faster: no reflection walk, only object
construction. This holds for every typed-`Dataset` program and assumes no concurrency. (The 8-thread
row shows a second effect — Spark's throughput falls with more threads while the compile-time path
scales — which applies only to concurrent derivation; see §9d.)

**Cold-start.** The first reflective derivation in a fresh JVM forces `scala.reflect.runtime.universe`
to build its symbol table, a one-time per-JVM cost on top of the derivation. Measured
(`ColdStartProbe`, 3 fresh JVMs): the first `ExpressionEncoder[T]()` takes ~1.05 s, versus a warm
steady-state of ~1.4 ms — about 1 s of cold-start. Compile-time derivation does not incur it (the
analysis happens at `scalac`). This cost applies without concurrency: short-lived drivers (serverless
Spark, AWS Glue, CI jobs, frequent small batches) restart frequently and do not amortize it. For a
30 s job it is ~3% overhead per run; for a 5 s job, ~20%.

**Scope and caveats.** Local Apple-M1, publication time-axis fidelity (`-f3 -wi5 -i10`, 30 iterations;
cross-architecture sweep pending, §13). These measure *derivation* (building the encoder
description), not execution; the compile-time side is *not* "zero runtime" — it is lock-free object
construction, and a user who hoists an encoder into a `val` amortizes either path to once per type.
The compile-time path is also not free at build: it costs `scalac` time, measured in §9c.

## §9b. The derivation path is uncached

The natural objection to §9 is "encoders are cached, so derivation runs once and the lock never
matters." At the level of a single held `val`, true. But the *typed-API surface* re-derives on every
call, with no cache anywhere in Spark. The chain is short and verifiable (line numbers from Spark
4.0.0 sources; structurally unchanged in the 4.1.2 build target):

```scala
// SQLImplicits.scala:306 — an implicit *def*, so it materializes fresh on every summon
implicit def newProductEncoder[T <: Product: TypeTag]: Encoder[T] = Encoders.product[T]
// Encoders.scala:319
def product[T <: Product: TypeTag]: Encoder[T] = ScalaReflection.encoderFor[T]   // → the global lock
```

This `def` satisfies the context-bound `Encoder[T]` on the public typed ops — `Dataset.as[U: Encoder]`
(`Dataset.scala:522`), `map[U: Encoder]` (`:1392`), `groupByKey[K: Encoder]` (`:962`),
`SparkSession.createDataset[T: Encoder]` (`:359`) — **whenever the element type routes through the
reflective implicits**: product case classes (`newProductEncoder`), plus the `Seq`/`Map`/`Set`,
product-array, and Java-enum implicits, all of which call `encoderFor` (`SQLImplicits.scala`). It does
*not* cover built-in scalars — primitives, boxed primitives, `String`, decimals, temporals, and
primitive arrays resolve to pre-built `Encoders.*` constants with no reflective walk and no lock. So
the recurrence is real for the **case-class / collection / map / set / Java-enum** surface — which is
exactly what §9 measures (all eight TPC-H types are products) — and not for, say, a `Dataset[Int]`.
Within that surface there is no memoization: a grep of every Spark SQL source referencing
`ExpressionEncoder` for `ConcurrentHashMap | computeIfAbsent | getOrElseUpdate | memoiz | CacheBuilder`
returns **zero hits**, so each such typed op re-runs `encoderFor` under the global `ScalaSubtypeLock`
every time.

This has two consequences. **First**, the per-derivation cost of §9 and
the ~1 s cold-start are *not* one-time accidents a cache quietly hides: they recur on **every** such
typed op (over a case class / collection / map / set / Java enum) unless the user manually hoists the
encoder into a `val`. **Second**, because `ScalaSubtypeLock`
is a single JVM-static monitor, concurrent derivation in one JVM serializes on it. That second effect
is **narrower than it first appears**, and earlier drafts of this report overstated it: the Spark
Connect *server* does not reflectively derive (the client builds the `AgnosticEncoder` and ships it in
the request; the server only deserializes it — verified in `SparkConnectPlanner`), and Thrift/JDBC is
untyped SQL with no encoders. The genuine concurrent case is a *multi-threaded application building
typed `Dataset`s* — a query-serving service on a shared `SparkSession`, or threaded job submission —
treated honestly in §9d.

To isolate that concurrency effect with the "it's cached" loophole closed, a second benchmark pair
(`deriveMixed`) derives **all 8 distinct TPC-H encoders per op, no type ever repeating** — so even a
hypothetical per-type cache could not help; the only thing shared across threads is the lock itself:

| Threads | Spark `encoderFor`, 8 distinct types (ops/s) | Compile-time (ops/s) | Speedup |
|---:|---:|---:|---:|
| 1 | 538.0 ± 10 | 208,153 ± 3.5k | **~387×** |
| 2 | 437.2 ± 10 | 416,343 ± 2.6k | **~952×** |
| 4 | 403.1 ± 4 | 750,628 ± 28k | **~1,862×** |
| 8 | **402.3 ± 5** | 907,782 ± 32k | **~2,256×** |
| scaling 1→8 | **0.75× (slower)** | 4.36× | — |

The trend is consistent: as threads rise 1→2→4→8, Spark's throughput moves 538 → 437 → 403 → 402
ops/s — it falls and then flatlines below its single-threaded rate, because every worker queues on one
monitor regardless of type. The compile-time path, doing the identical work, climbs
208k → 416k → 751k → 908k (4.36× over the 8 cores). This confirms the
degradation is the **lock**, not per-type cost — but it only manifests when a single JVM derives on
many threads, which (per above) is a specific application shape, not a universal one. §9d measures
that shape through the public API and adds the tail-latency view. (`-f3 -wi5 -i10`; the monotone
degradation is well outside the ±CIs.)

## §9c. Measurement validity

The two suites live in different modules and Scala versions (the baseline `encoderFor[T: TypeTag]`
exists only on 2.13; the compile-time path only on Scala 3 — see `INFRASTRUCTURE.md`), so it is
worth stating why the comparison is sound and what the harness does and does not contribute.

- **The harness is excluded from the measurement.** Both suites are JMH `Throughput`, run with
  `Jmh / fork := true`. JMH launches a *fresh* JVM per fork; sbt only starts it. The warmup
  iterations (`-wi`) run before any timed iteration, so class-loading, JIT compilation to
  steady-state, and one-time initialization are not counted. The numbers are steady-state per-op
  throughput, not wall-clock that includes launcher latency.

- **The cold-start exclusion is conservative *against* us.** Spark's first-derivation cold start
  (~1 s in a fresh JVM, dominated by `scala.reflect.runtime.universe` init; §9) happens during
  warmup, so it does not appear in the reported Spark per-op figure. Real workloads pay it;
  the compile-time path does not pay either. The reported ratios therefore understate the
  end-to-end difference rather than inflate it.

- **Same machine, JDK, and JMH parameters on both sides.** The only intended difference between the
  two forks is the operation under test — reflective vs compile-time production of the *same*
  `AgnosticEncoder`. Both return the constructed encoder (an `AnyRef` / `Array[AnyRef]`) so the JIT
  cannot dead-code-eliminate the work; no `Blackhole` is needed. Thread scaling uses `-t N` within a
  single fork, so the global `ScalaSubtypeLock` contention (§4) is genuinely exercised.

- **The one asymmetry, stated plainly, and its measured cost.** `ProtoEncoder.derived[T]` performs
  the *type analysis* at `scalac` time; the runtime benchmark on our side therefore measures only the
  residual work — the bridge's recursive lowering plus `AgnosticEncoder` object construction —
  whereas Spark's `encoderFor[T]` does the whole walk at runtime. This is not a measurement trick; it
  *is* the thesis ("move the analysis to compile time"). The cost does not vanish — it shifts to
  compilation. We measured it: against an identical no-derivation baseline (so JVM/`scalac` fixed
  cost cancels), deriving *k* 12-field case classes adds **~1.0 s fixed + ~20 ms per type** of
  `scalac` time (least-squares slope over *k* ∈ {16, 32, 64, 128}, min-of-5, this M1).

  The honest reading: ~20 ms of *compile* time per type is **larger than a single Spark runtime
  derivation** (~0.43 ms at 1 thread). If an encoder is derived exactly once per JVM, the
  compile-time path is roughly even-to-slightly-worse on pure derivation time. It is favorable on the
  realistic paths, which do not derive once: the typed-API surface re-derives *per call, uncached*
  (§9b), so the compile cost is paid once against many runtime derivations; the ~1 s per-JVM cold
  start (§9) is removed, which exceeds the build delta for short-lived drivers; and Scala 3 is
  supported at all. The `scalac` delta is the cost side of the tradeoff; TASTy/bytecode-size impact is not
  yet measured.

- **Fidelity caveat (unchanged from §9).** The numbers here are local Apple-M1 at `-f3 -wi5 -i10`
  (3 forks × 10 measured iterations = 30 data points, inter-JVM variance captured per JMH's ≥3-fork
  guidance; tight CIs, see the tables). The remaining gap to publication is the **cross-architecture
  sweep** (Graviton/Intel/AMD) noted in §13 — to confirm the ratios are not an Apple-silicon artifact.

## §9d. Concurrent typed-plan construction

This is the concurrency effect, secondary to §9. **Where it does not apply:** the Spark Connect
server (the client derives the encoder and ships it; the server only deserializes —
`SparkConnectPlanner` calls `encoderFor(agnosticEncoder)`, never `encoderFor[T: TypeTag]`) and
Thrift/JDBC (untyped SQL). **Where it does:** a long-lived, multi-threaded JVM that constructs typed
`Dataset`s concurrently — a query-serving service on a shared `SparkSession`, or threaded
(FAIR-scheduler) job submission. Even there it is material only for high-rate, short typed requests,
since derivation is microseconds-to-ms against query execution measured in seconds. Within those
limits, the effect is quantified below.

A small load generator (`MultiTenantDerivation`) runs `S` threads, each deriving an
`ExpressionEncoder[T]` via the **real public API** — Spark's `ExpressionEncoder[T: TypeTag]()`
(→ `encoderFor` → the lock) versus our `ExpressionEncoder(toAgnostic(derived[T]))` — cycling the 8
TPC-H types. After a 2 s warmup we measure throughput and request-latency percentiles over 5 s
(8-core M1):

| Sessions | Spark thrpt (op/s) | Ours thrpt | Spark p50 / p99 (µs) | Ours p50 / p99 (µs) |
|---:|---:|---:|---:|---:|
| 1 | 3,612 | 34,823 | 258 / 637 | 26 / 69 |
| 4 | 3,123 | 134,187 | 1,120 / 3,309 | 27 / 68 |
| 8 | 3,074 | 144,461 | 2,264 / 7,615 | 31 / 229 |
| 16 | 2,858 | 129,659 | 4,730 / 18,233 | 34 / 265 |
| 32 | 2,825 | 136,353 | 9,536 / 37,617 | 34 / 543 |

Two effects appear. Throughput does not scale: it stays near ~3k/s and declines slightly
(3,612 → 2,825) as sessions grow, because the global lock serializes every derivation, so adding
sessions or cores does not help. Latency rises with session count: p50 grows roughly linearly
(258 µs → 9.5 ms, 37×) and p99 from 637 µs to 37.6 ms (59×), since each request waits behind the
others on one monitor. The compile-time path scales throughput 4.1× to the core count (then plateaus
on the 8-core box) and keeps p99 sub-millisecond. At 8 sessions that is 47× the aggregate throughput
and 33× lower p99; at 32 sessions, 69× lower p99.

For this application shape, the §4 lock becomes a throughput ceiling and a source of growing tail
latency; the compile-time path has neither. The shape — concurrent, high-rate, short typed requests
in one JVM — is a subset of Spark usage rather than the common case, so the single-thread cost and
cold-start (§9) apply more broadly. (Caveats: 8 physical cores, so *S* = 16/32 mix lock contention
with CPU oversubscription — the divergence is already clear at *S* ≤ 8; and this is a custom harness,
not JMH, so it carries no inter-fork CIs, though the effect size exceeds run-to-run variance.)

## §10. Per-row serializers (separate work)

The derivation replacement reuses Spark's serializer codegen, so it does not change per-row
speed. A separate change does: emitting specialized, monomorphic per-row code instead of routing
through Catalyst's `DataType` machinery. This project implements two such encoders, both faster than
Spark on the hot path:

- **`UnsafeRowSerializer[T]`** — byte-identical to `ExpressionEncoder`'s `UnsafeRow` output, geomean
  **1.16×** faster across 12 microbenchmarks (validated cross-arch on EC2 x86_64).
- **Arrow `ArrowRowSerializer`/`Deserializer`** — byte-identical to Spark Connect's
  `ArrowSerializer` on the wire, geomean **0.92×** (≈8% faster) with **~43% less** per-row
  allocation.

Their adoptability for Spark differs, and conflating them overstates the case:

- The **Arrow encoder is directly adoptable by Spark Connect.** The client's `ArrowSerializer` /
  `ArrowDeserializers` are *closure-based*, not fused into Catalyst codegen, so a compile-time Arrow
  serializer drops in as a byte-identical, faster, lower-allocation, AOT-friendly replacement on the
  client.
- The **`UnsafeRow` serializer is *not* a core drop-in.** Spark's `UnsafeRow` path is fused into
  whole-stage codegen, and a hand-emitted lambda does not fuse. Its value is as *evidence* that
  compile-time specialization matches or exceeds Spark's runtime codegen per row, and for the
  interpreted-fallback path (wide schemas above `spark.sql.codegen.maxFields`). Capturing its per-row
  advantage *inside* fused Dataset codegen would mean replacing whole-stage codegen itself — a
  separate, far larger AOT project.

Full data, methodology (Georges et al., OOPSLA 2007), cross-arch validation, and the end-to-end
query tax that motivates per-row optimization are in the archived
[`REPORT_encoder_perf.md`](archive/REPORT_encoder_perf.md).

---

# Part 4 — Migration

## §11. Argument and proposal

> **Operational checklist** (which file, which module, which lines): [MIGRATION.md](MIGRATION.md). This section is the *argument*; that doc is the *how*.

The migration argument is narrower than "the encoder is faster." The central claim is about the
*derivation*: it is the structural Scala-3 blocker (§1–§3) and a globally-locked bottleneck (§4), and
it can be replaced faithfully (§8) at lower cost (§9). The change is scoped — but, importantly, the
larger part of it is **work Scala 3 forces regardless**, not cost this proposal adds (see below). It is
not "one tidy module"; it is three parts of very different size.

> **Proposal — three parts, in increasing size:**
>
> 1. **De-reflect the `ScalaReflection` object** so it loads on Scala 3: `val universe` → `lazy val`,
>    `encodeFieldNameToIdentifier` → `scala.reflect.NameTransformer.encode`, and replace
>    `findConstructor`'s scala-reflect fallback. A handful of lines; it fixes the initialization crash
>    (§3, upstream as [scala/scala3#25896](https://github.com/scala/scala3/issues/25896)) and is the
>    small, self-contained **down-payment** PR.
> 2. **Provide the Scala-3 derivation** — a single `Mirror`/`inline` macro, `deriveAgnosticEncoder[T]`,
>    emitting Spark's `AgnosticEncoder` directly (§11b). One Scala-3-only file; the reflective body
>    stays for 2.13. Because the output is the *same* `AgnosticEncoder`, **everything downstream of the
>    encoder — `ExpressionEncoder`, the serializer/deserializer codegen — is untouched.**
> 3. **Change the ~16 `TypeTag` context bounds** in the public encoder-producing signatures
>    (`Encoders`, `SparkSession.implicits`, `ExpressionEncoder.apply`, and the
>    `Dataset`/`functions`/`Aggregator` methods that thread one) from `[T: TypeTag]` to the derived
>    form. These span `sql-api` / `catalyst` / `sql-core`, handled with per-version sources.

**Part 3 is not cost this proposal introduces — it is inherent to Scala 3.** `TypeTag` is a
scala-reflect construct that does not exist on Scala 3, so *any* Scala-3 build of those APIs must drop
it from the signatures regardless of how encoders are derived. What this proposal uniquely supplies is
the thing the rewritten signatures derive *into*; without it you remove `TypeTag` and have **nothing**
to replace the derivation — which is exactly why encoders are *the* structural blocker (§1–§3), not one
among many. So the honest framing is "we supply the missing derivation that the inevitable signature
change needs," not "we add the change."

There is a design lever for how contained Part 3 is, and it is Spark's call:
(a) per-version source directories (`scala-2.13` / `scala-3`) in each affected module — the standard
cross-build mechanism Spark already uses; or (b) move the bound from `[T: TypeTag]` to `[T: Encoder]`
uniformly, keeping the call sites single-source and isolating all version-specific code to the
*materialization* of the `Encoder` (reflection-derive on 2.13, `Mirror`-derive on 3). (b) is cleaner
but a larger API-shape decision; either is bounded and enumerable.

The end-user `Dataset` *usage* surface is unchanged — `spark.createDataset(seq)`, `ds.map(f)`, `as[T]`
all keep compiling; what changes is the implicit derivation mechanism behind the context bound, not the
call. Spark Connect's wire protocol is unaffected — it already uses `AgnosticEncoder` as its interchange
shape, which the macro produces from `T` exactly as `encoderFor` does. Frameless's `TypedEncoder`
continues to work on 2.13 and could port to Scala 3 by delegating to the macro.

The migration is therefore incremental rather than coordinated: land the de-reflection (1) on its own;
ship the derivation (2) into the Scala-3 cross-build's already-changing signatures (3); and the
per-user cross-version workarounds disappear one user at a time.

## §11b. Concrete mapping and migration checklist

**The artifact upstream takes is a single self-contained file** — `AgnosticDerivation.scala`
(`encoder-spark`), ~210 lines, whose entire dependency surface is Spark's own `AgnosticEncoder` model
plus the Scala standard library (`scala.compiletime`/`deriving.Mirror`/`reflect.ClassTag`). It contains
**no ProtoCatalyst types** — no `ProtoEncoder`, no `ProtoType`, no bridge. Rename its package and it is
the reflection-free `encoderFor`. There is no IR to adopt.

The mapping below shows how the repo's *production* path (a two-layer IR) **collapses** to that single
file. The two layers exist in-repo only because `ProtoEncoder`/`ProtoType` is engine-independent and
also targets non-Spark backends; inside Spark there is no second backend, so the bridge's whole job —
re-splitting what `ProtoType` normalized away — disappears. The mapping is deliberately narrow: it
replaces the *derivation* and de-reflects the `ScalaReflection` object, and **reuses everything
downstream of `AgnosticEncoder` unchanged** (rather than replacing `ExpressionEncoder`):

| This project | Replaces / touches in Spark | Role |
|---|---|---|
| `ProtoEncoder.derived[T]` (Scala 3 `Mirror`/`inline`) | the type-analysis half of `ScalaReflection.encoderFor[T]` | compile-time type → IR |
| `AgnosticEncoderBridge.toAgnostic` | the node-building half of `encoderFor` | lower IR → Spark's `AgnosticEncoder` |
| *(unchanged)* | `ExpressionEncoder`, `Serializer`/`DeserializerBuildHelper`, whole-stage codegen | downstream — no reflective *derivation*; the small `ScalaReflection` utilities it calls are de-reflected separately (§2, §3) |
| `lazy val universe`; `NameTransformer.encode`; `findConstructor` fallback | the eager `val universe` + 2 utilities in `ScalaReflection` | de-poison the object for Scala 3 (§3) |

**One layer upstream, not two.** The first two rows are split only because this project is *out of
tree*: `ProtoEncoder`/`ProtoType` is an engine-independent IR (it also targets non-Spark backends)
and `AgnosticEncoderBridge` lowers it to Spark. Inside Spark there is no second backend and no reason
for a second IR — **`AgnosticEncoder` already *is* Spark's reflection-free encoder description**. So
the upstream `deriveAgnosticEncoder[T]` is a *single* `Mirror` macro that emits `AgnosticEncoder`
nodes directly, collapsing `ProtoType` + bridge into one pass. This is strictly *simpler* than the
in-repo code, not an extra step: the bridge only exists to recover what `ProtoType` normalized away —
re-splitting `BigInt`/`BigDecimal`, `UUID`/`String`, `Array`/`Seq` via `clsTag`, and overriding
nullability back to Spark's `EncoderField(name, enc, enc.nullable)` rule. Deriving `AgnosticEncoder`
straight from the `Mirror` never discards that information, so all of that recovery disappears. Spark
inherits one new file that maps 1:1 onto `encoderFor` — not a new type system. The reusable IP is the
*derivation algorithm* (the `Mirror`/`inline` walk, the inline-given that composes case-class
elements, cycle handling, decimal/temporal defaults); only its output target changes.

This collapsed form is implemented and validated in-repo, not just argued: `AgnosticDerivation`
(`encoder-spark`) is a single `Mirror`/`inline` macro, `deriveAgnosticEncoder[T]`, that emits Spark's
`AgnosticEncoder` directly — no `ProtoType`, no bridge. `AgnosticDerivationSpec` checks its output
against the **same** Scala-2.13 goldens (`ScalaReflection.encoderFor`) that the two-layer bridge passes
in §8, across the full corpus (primitives boxed/unboxed, decimals, temporal, `Option`, collections,
`Map`, nested products, tuples, tuple-of-case-class) plus the Scala-3 enum and extension types — 20
cases, all green. It also takes the one improvement the out-of-tree runtime bridge cannot: a
data-carrying ADT (§7) is rejected at **compile time** (`scala.compiletime.error`), not at invocation.
This is the artifact a `spark-sql-encoder-3` module would ship in place of `encoderFor`.

Migration checklist (the end-to-end upstream validation in §13 is the last two boxes):

- [ ] Implement `ExpressionEncoder.apply[T]()` on the Scala 3 build via `deriveAgnosticEncoder[T]` — a
      single `Mirror` macro emitting `AgnosticEncoder` directly (no separate `ProtoType`/bridge layer
      upstream, per above); keep the reflective body for 2.13.
- [ ] De-reflect the `ScalaReflection` object: `val universe` → `lazy val`;
      `encodeFieldNameToIdentifier` → `scala.reflect.NameTransformer.encode`; replace
      `findConstructor`'s scala-reflect fallback.
- [ ] Swap the ~16 `TypeTag`-threading signatures (`Encoders`, `Dataset`, `SparkSession`,
      `functions`, …) to derived-given signatures — forced anyway, since `TypeTag` is gone.
- [ ] Run Spark's existing encoder test suite (`catalyst/testOnly *Encoder*`) against the Scala 3 build.
- [ ] Run the typed-`Dataset` suite — the definitive proof that §3's wall is the last derivation-side
      obstacle.

A reflection-free path also moves most failures earlier: a type `ProtoEncoder` cannot derive is a
**compile error** (`Cannot find or derive ProtoEncoder for type …`) rather than a runtime exception
from `encoderFor`. The narrower case — a type that *derives* but that Spark's `AgnosticEncoder` model
cannot represent (a data-carrying ADT, §7) — is today a **runtime** rejection at the bridge
(`IllegalArgumentException`); an upstream macro could instead raise it at compile time
(`scala.compiletime.error`). (Earlier integration sketches that routed through a bespoke
`InlineRowSerializer` instead of Spark's `AgnosticEncoder` are superseded by the bridge approach above.)

## §12. What this unlocks — and what it doesn't

**Unlocks.** Spark can begin publishing Scala 3 artifacts for the typed API; the community can use
Scala 3 language features (enums, opaque types, better inlining) in `Dataset` code; and the global
derivation lock and the per-JVM reflection cold-start go away. It also clears the *first* of the
**Spark Connect client**'s native-image blockers — the realistic near-term AOT target (≈6 client-side
blockers in total, the remainder individually tractable; inventory in [`AOT_ROADMAP.md`](AOT_ROADMAP.md)).
That is "moves within reach," not "done."

**End-state vs. transitional.** The two-line de-reflected `ScalaReflection` (§3) is *transitional
scaffolding*: it exists only to run Scala-3-derived encoders against Spark's stock **2.13** jars
without forking. A Scala-3 Spark has no such object to patch — `scala.reflect.runtime.universe` is not
on the classpath, so `encoderFor`'s reflective body is *replaced* by the macro, and its two
non-derivation utilities relocate (`encodeFieldNameToIdentifier` is pure identifier mangling →
`NameTransformer`; `findConstructor` is *Java* reflection) — the `ScalaReflection` object is then
deleted. So the migration does not "patch Spark"; its end-state *removes* the object, fully decoupling
the typed pipeline from **Scala runtime** reflection. The scope of that decoupling is exactly that
much: *Java* reflection and Janino runtime codegen remain on the execution path.

**Does not unlock.** Removing those — the path to a native-image binary — is a separate, much larger
track. The encoder is one of ~15 native-image blockers in Spark 4.1 (`AOT_ROADMAP.md` §3); the
structural one, Catalyst whole-stage codegen via Janino, is untouched here and has **no** drop-in
workaround (interpreted-only is 3–10× slower; a compile-time-codegen rewrite is typed-query-only and
hits a hard cliff on dynamic SQL; a Truffle re-expression is research-grade). So full-Spark
native-image is **not** a near-term outcome of this work, and the report does not claim it. Likewise,
capturing the per-row *serializer* advantage (§10) inside Spark core would require replacing that same
codegen. This report's claim is bounded: the *derivation* is the Scala-3 blocker, and replacing it is
narrow in scope, faithful, and low-cost — the AOT/native gains are downstream and separately scoped.

## §12b. Related work

**Compile-time encoders for Spark.** Frameless's `TypedEncoder` is the closest prior art: a
type-class–based, compile-time derivation of Spark encoders that backs a type-safe `TypedDataset`
API. Two differences matter here. First, Frameless is built on Scala 2 with shapeless `Generic`, so it
does not address — and cannot itself run on — the Scala-3 blocker; it is gated on a Scala 3 port
rather than enabling one. Second, it sits *beside* Spark: it derives a `TypedEncoder` that ultimately
produces a stock `ExpressionEncoder`, layering safety on top of the reflective machinery rather than
replacing it. This work instead targets the upstream derivation seam (`encoderFor`) directly, is
Scala-3 native, and proposes the in-tree change that would also let Frameless drop its own derivation
and delegate (§11).

**Generic-derivation mechanisms.** The derivation uses Scala 3's `scala.deriving.Mirror` with
`inline`/`summonInline` directly, not a derivation library such as Magnolia or shapeless-3 (both of
which abstract derivation across Scala versions behind one API). That is deliberate: the bridge must
map onto Spark's *exact* `AgnosticEncoder` node set — re-splitting `BigInt`/`BigDecimal`, recovering
`ClassTag` leaves, matching Spark's `EncoderField` nullability rule (§6) — and a thin hand-written
`Mirror` walk keeps that mapping explicit and dependency-free. The reusable IP is the walk itself
(§11b), not a new abstraction layer over it.

**Spark's reflection-free seam.** `AgnosticEncoder` — the pure-ADT encoder description this work
targets — exists precisely to decouple the encoder *description* from reflective derivation, and is
what makes Spark Connect's "derive on the client, ship the ADT, deserialize on the server" model
possible (§9b, §9d). This report is a direct continuation of that decoupling: Spark already built the
reflection-free *downstream*; what is missing is a reflection-free *front end* so the seam is reachable
without a `TypeTag`. Spark Connect is therefore both the strongest evidence that the seam is real and
the first concrete beneficiary — a native-image Connect client (§12).

**Scala 3 migration of Spark.** Cross-building Spark for Scala 3 has been a standing community goal,
and the typed-encoder derivation is the structural obstacle repeatedly identified; the rest of the
`TypeTag`/reflection surface is mechanical (§2). This report scopes the problem to exactly that
obstacle and shows it is removable, rather than attempting the whole port.

**AOT / native-image.** Eliminating runtime reflection is the precondition for GraalVM `native-image`
and the broader ahead-of-time push in the JVM ecosystem (framework-level AOT in Quarkus, Spring, and
similar). Removing reflective derivation is one such step for Spark's typed API — necessary but not
sufficient, since Catalyst's Janino whole-stage codegen remains (§12). The wider blocker inventory is
in [`AOT_ROADMAP.md`](AOT_ROADMAP.md).

## §13. Future work

- **Cross-architecture benchmark sweep** (Graviton/Intel/AMD): the §9 figures are already at
  publication time-axis fidelity (`-f3 -wi5 -i10`, 30 data points), but on a single Apple-M1. A
  multi-arch sweep would confirm the ratios are not a silicon artifact; the sweep tooling is in place
  (`INFRASTRUCTURE.md`).
- **Tail of the type surface**: the faithful Scala subset is now complete through tuple-of-case-class
  (§8), and the bridge adds the beyond-Spark extensions UUID/OffsetDateTime/ZonedDateTime as
  String-backed `TransformingEncoder`s (§7). What remains is *out of scope*: Java-bean and Java-enum
  inference (`JavaTypeInference`, Java reflection — already works on Scala 3), UDTs, and types with no
  working Spark counterpart to match against — `java.util.Date`, and `LocalTime`, for which Spark
  4.1.2 *derives* a `LocalTimeEncoder` but its `ExpressionEncoder` then rejects `TimeType` at
  serializer-construction time (`[UNSUPPORTED_TIME_TYPE]`), so there is no Spark golden to compare.
- **Upstream**: the single-macro form the upstream module would ship is now built and golden-verified
  (`AgnosticDerivation`, §11b). The remaining step is to compile it *inside* a Scala-3 build of
  `spark-sql-api` (rather than against the 2.13 jars via `for3Use2_13`) and run the typed-`Dataset`
  test suite — the definitive end-to-end validation that §3's wall is the last derivation-side
  obstacle. (Scoping note: `spark-sql-api`'s encoder closure couples through `DataType` to the SQL
  parser and json4s, so compiling the whole module on Scala 3 is a larger task than the encoder
  derivation itself.)
