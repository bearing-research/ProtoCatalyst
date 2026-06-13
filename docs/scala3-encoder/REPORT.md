# Replacing Spark's Reflective Encoder Derivation: A Compile-Time Path to Scala 3

Apache Spark cannot run on Scala 3, and the reason is, overwhelmingly, one function. Spark's typed-`Dataset[T]`
API derives every encoder through `ScalaReflection.encoderFor[T: TypeTag]`, which is built on Scala
2's runtime reflection (`scala.reflect.runtime.universe`). That reflection does not work for Scala
3 types — and, separately, it is costly: a ~1 s per-JVM reflective cold-start, plus a global lock on
every subtype check that caps concurrent derivation.

This report shows that the reflective derivation can be replaced, wholesale, by **compile-time
Scala 3 `Mirror` derivation that produces Spark's own `AgnosticEncoder`** — so the entire rest of
Spark's encoder pipeline is reused unchanged. The replacement is:

- **Faithful** — the derived `AgnosticEncoder` is byte-identical to what `ScalaReflection.encoderFor`
  produces, across the common type surface (§8).
- **Far cheaper** — ~389× faster to derive single-threaded, and it eliminates a **~1 s per-JVM
  reflective cold-start** that short-lived drivers (serverless/Glue/CI) pay on every run (§9). (It
  also removes a global derivation lock that caps concurrent typed-API throughput — a real but
  narrower, latent benefit; §9d.)
- **Strictly more capable** — it encodes Scala 3 features Spark's reflection cannot (`enum`s,
  sealed-trait ADTs), and marks where Spark's encoder model would have to grow (§7).
- **Proven end-to-end** — the execution wall that stops stock Spark on Scala 3 is a *two-line* change
  to `ScalaReflection`; with it, our compile-time-derived encoders round-trip real values through
  Spark's unmodified codegen ser/deser from a Scala 3 process (§3).

Everything is backed by code in this repository and validated against stock Spark, which serves as
both the correctness oracle and the benchmark baseline. The companion design documents are
[`REFLECTION_REPLACEMENT.md`](REFLECTION_REPLACEMENT.md) (the bridge design and full parity surface),
[`SCALA3_SUPERSET.md`](SCALA3_SUPERSET.md) (the Scala-3-beyond-Spark catalog), and
[`INFRASTRUCTURE.md`](INFRASTRUCTURE.md) (the cross-version build topology, how to run everything, and
the measurement-validity rationale behind §9).

> **Scope note.** A *separate, more ambitious* line of work — replacing Spark's per-row *serializer*
> codegen with compile-time-specialized `UnsafeRow`/Arrow encoders — also beats Spark on the hot
> path. That is the achievable *ceiling*, not the migration's headline, and is summarized in §10 and
> documented in full in the archived [`REPORT_encoder_perf.md`](archive/REPORT_encoder_perf.md). It is not
> required for, and does not follow from, the derivation replacement.

---

# Part 1 — The blocker

## §1. Spark is stuck on Scala 2.13, and the encoder derivation is why

Spark 4.x publishes only Scala 2.13 artifacts. The obstacle is not the bulk of the codebase — it is
the typed-encoder layer. `Dataset[T]`'s `ExpressionEncoder[T]` is derived from a `TypeTag[T]` via
`ScalaReflection`, and `TypeTag` plus `scala.reflect.runtime.universe` are Scala-2 constructs with
no Scala-3 equivalent. A Scala 3 port of Spark cannot keep `ScalaReflection`; it has to derive
encoders some other way. That single requirement is the structural Scala-3 blocker for the typed
API, and it is what this report removes.

The good news is that the blocker is *contained*. Spark's encoder pipeline already has a
reflection-free seam — `AgnosticEncoder` — and only the derivation in front of it is reflective.

## §2. The pipeline, and where the reflection lives

```
T  ──ScalaReflection.encoderFor[T: TypeTag]──►  AgnosticEncoder[T]  ──ExpressionEncoder(_)──►  Catalyst Expression trees  ──►  whole-stage codegen
   ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^      (pure ADT)            (no reflection)               (no reflection)
       the only reflective step
```

`AgnosticEncoder[T]` is a pure algebraic data type — field names, nullability, sub-encoders,
`DataType`, `ClassTag`. It carries no reflection and no Catalyst expressions; it is purely a
*description*. Everything downstream of it (`ExpressionEncoder`, `SerializerBuildHelper` /
`DeserializerBuildHelper`, codegen) is already reflection-free. The reflection is confined to
producing the `AgnosticEncoder` in the first place:

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
Spark's downstream are reused verbatim. **Replacing `encoderFor` is the whole job.**

A surrounding cohort of ~16 files (`Encoders`, `Dataset`, `SparkSession`, `functions`,
`UDFRegistration`, `literals`, …) merely *thread a `TypeTag`* into `encoderFor`. On Scala 3 those
become `[T]`-with-a-derived-given signature swaps — forced anyway, since `TypeTag` doesn't exist —
and require no real reimplementation. The one function that does is `encoderFor`.

**Is it really just `encoderFor`? An honesty check across all of Spark.** A scan of all 22 Spark
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

## §3. The wall: stock Spark cannot even run on Scala 3

It is tempting to think one could simply call Spark's `ExpressionEncoder` from a Scala 3 process and
sidestep the derivation. You cannot: stock Spark's codegen path touches `ScalaReflection`, and the
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

This is the wall in miniature: not only is `encoderFor` reflective, but the `ScalaReflection`
*object* is poisoned for Scala 3 by one eager field. A scan of the entire expression/encoder layer
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

We verify this concretely. `spark-reflection-patch` is a verbatim copy of Spark 4.1.2's
`ScalaReflection` with only those two lines changed, compiled on Scala 2.13 and placed ahead of
`spark-catalyst` on the test classpath so it shadows Spark's copy. With it, `ExecutionWallSpec` (in
`encoder-spark`, **a Scala 3 module**) round-trips real values — flat and nested products, all
primitive widths, `java.lang` boxed types, `Some`/`None`, maps, collections including `Array`,
collection/map/option *of* a case class, and tuples — through Spark's **unmodified** codegen
serializer and deserializer. Nine cases, all green.

This upgrades the report's correctness argument from structural to **observed**: the
`AgnosticEncoder` we derive at compile time (no `TypeTag`, no reflection, §6) drives Spark's actual
ser/deser and reproduces the input. Structural parity (§8) remains the broad oracle across the full
type surface; the end-to-end spec is the existence proof that identical structure does yield
identical runtime behavior — and that the wall is a two-line change, not a rewrite.

## §4. The hidden tax: a global lock serializes all derivation

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
analysis at `scalac` time, so there is no runtime `<:<` and no lock at all. The lock's worth is
quantified in §9d (a latent scalability hazard); the broadly-applicable wins are single-thread cost
and cold-start (§9).

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

## §7. Strictly more capable: Scala 3 types Spark cannot encode

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
precision/scale, `lenientSerialization` flags, and `clsTag`s — so Spark's unchanged downstream
behaves identically.

`encoderFor[T: TypeTag]` exists only in Scala 2.13, so goldens are generated there
(`AgnosticParityFixtures`) and compared on the Scala 3 side (`AgnosticEncoderBridgeSpec`) via a
canonical, class-name-normalized structural dump — the cross-compile fixture pattern this project
uses throughout (the same approach validates the Arrow wire format byte-for-byte). For example,
Spark and our bridge both produce, for a flat `Person(id: Int, name: String, active: Boolean, score:
Double)`:

```
Product[Person](id:PrimitiveIntEncoder!, name:StringEncoder?, active:PrimitiveBooleanEncoder!, score:PrimitiveDoubleEncoder!)
```

Every case in the corpus is byte-identical to Spark's:

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

## §9. Derivation cost: the headline measurement

Two JMH suites, Throughput mode, derive the *same* `AgnosticEncoder[Lineitem]` (16 fields) — an
apples-to-apples "type → encoder description" comparison. `SparkEncoderDerivationBenchmarks` (Scala
2.13) times `ScalaReflection.encoderFor[Lineitem]`; `EncoderDerivationBenchmarks` (Scala 3) times
`AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[Lineitem])`.

| Threads | Spark `encoderFor` (ops/s) | Compile-time (ops/s) | Speedup |
|---:|---:|---:|---:|
| 1 | 2,304 ± 9 | 896,924 ± 7k | **~389×** |
| 8 | 1,660 ± 26 | 3,926,237 ± 125k | ~2,365× |

Single-threaded, compile-time derivation is ~400× faster — no reflection walk, just object
construction. This is the **broadly-applicable** win: it holds for *every* typed-`Dataset` program,
no concurrency assumed. (The 8-thread row hints at a second effect — Spark gets *slower* with more
threads while ours scales — but that only matters for concurrent derivation, a narrower case treated
honestly in §9d; do not read it as a headline.)

**Cold-start: the bigger everyday cost.** The first reflective derivation in a *fresh* JVM forces
`scala.reflect.runtime.universe` to build its symbol table — a one-time cost paid once per JVM, on
top of the derivation. Measured (`ColdStartProbe`, 3 fresh JVMs): the first `ExpressionEncoder[T]()`
takes **~1.05 s**, versus a warm steady-state of ~1.4 ms — i.e. **~1 s of pure cold-start**.
Compile-time derivation pays none of it (the analysis already happened at `scalac`). This is the
robust performance case and it needs no concurrency: **short-lived drivers** — serverless Spark, AWS
Glue, CI jobs, frequent small batches — restart constantly and never amortize that second. For a
30 s job it is ~3% overhead paid every run; for a 5 s job, ~20%.

**Honest scope.** Local Apple-M1, publication time-axis fidelity (`-f3 -wi5 -i10`, 30 iterations;
cross-architecture sweep pending, §13). These measure *derivation* (building the encoder
description), not execution; the compile-time side is *not* "zero runtime" — it is lock-free object
construction, and a user who hoists an encoder into a `val` amortizes either path to once per type.
The compile-time path is also not free at build: it costs `scalac` time, measured in §9c.

## §9b. This is a real code path, not a micro — and it is uncached

The natural objection to §9 is "encoders are cached, so derivation runs once and the lock never
matters." At the level of a single held `val`, true. But the *typed-API surface* re-derives on every
call, with no cache anywhere in Spark. The chain is short and verifiable (Spark 4.0.0):

```scala
// SQLImplicits.scala:306 — an implicit *def*, so it materializes fresh on every summon
implicit def newProductEncoder[T <: Product: TypeTag]: Encoder[T] = Encoders.product[T]
// Encoders.scala:319
def product[T <: Product: TypeTag]: Encoder[T] = ScalaReflection.encoderFor[T]   // → the global lock
```

Every context-bound `Encoder[T]` on the public typed ops is satisfied by that `def`, uncached:
`Dataset.as[U: Encoder]` (`Dataset.scala:522`), `map[U: Encoder]` (`:1392`),
`groupByKey[K: Encoder]` (`:962`), `SparkSession.createDataset[T: Encoder]` (`:359`). A grep of every
Spark SQL source that references `ExpressionEncoder` for `ConcurrentHashMap | computeIfAbsent |
getOrElseUpdate | memoiz | CacheBuilder` returns **zero hits** — Spark memoizes encoder derivation
nowhere. So `import spark.implicits._` plus any typed operation re-runs `encoderFor` under the global
`ScalaSubtypeLock` every time.

This has two consequences. **First** — the one that matters most — the per-derivation cost of §9 and
the ~1 s cold-start are *not* one-time accidents a cache quietly hides: they recur on **every** typed
op unless the user manually hoists the encoder into a `val`. **Second**, because `ScalaSubtypeLock`
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

The result is unambiguous, and the *shape* is the point: as threads rise 1→2→4→8, Spark's throughput
moves **538 → 437 → 403 → 402** ops/s — it *falls* and then flatlines below its single-threaded rate,
because every worker queues on one monitor regardless of type. The compile-time path, doing the
identical work, climbs **208k → 416k → 751k → 908k** (4.36× over the 8 cores). This confirms the
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
  the compile-time path does not pay either. The headline ratios therefore understate the
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
  compile-time path is a wash-to-slight-loss on pure derivation time. The win is real because the
  realistic paths don't derive once: the typed-API surface re-derives *per call, uncached* (§9b) so
  the compile cost is paid once against many runtime derivations; the **~1 s per-JVM cold start (§9)**
  is gone, which alone dwarfs the build delta for short-lived drivers; and — the actual point — Scala
  3 works at all. We flag the `scalac` delta as the genuine debit; TASTy/bytecode-size impact is not
  yet measured.

- **Fidelity caveat (unchanged from §9).** The numbers here are local Apple-M1 at `-f3 -wi5 -i10`
  (3 forks × 10 measured iterations = 30 data points, inter-JVM variance captured per JMH's ≥3-fork
  guidance; tight CIs, see the tables). The remaining gap to publication is the **cross-architecture
  sweep** (Graviton/Intel/AMD) noted in §13 — to confirm the ratios are not an Apple-silicon artifact.

## §9d. A latent scalability hazard: concurrent typed-plan construction (secondary)

This is the concurrency story, kept honest and deliberately *secondary* to §9. **Where it does not
apply:** the Spark Connect server (the client derives the encoder and ships it; the server only
deserializes — `SparkConnectPlanner` calls `encoderFor(agnosticEncoder)`, never `encoderFor[T: TypeTag]`)
and Thrift/JDBC (untyped SQL). **Where it does:** a long-lived, multi-threaded JVM that *constructs
typed `Dataset`s* concurrently — a query-serving service on a shared `SparkSession`, or threaded
(FAIR-scheduler) job submission. And even there it is material only for *high-rate, short* typed
requests, since derivation is microseconds-to-ms against query execution measured in seconds. With
those scope limits stated, the effect is real and worth quantifying.

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

Two operator-relevant failures of the reflective path show up at once. **Throughput never scales** —
it sits flat at ~3k/s and even *declines* (3,612 → 2,825) as sessions grow, because the global lock
serializes every derivation; adding sessions (or cores) buys nothing. And **latency degrades without
bound** — p50 grows linearly with session count (258 µs → 9.5 ms, 37×) and p99 explodes
637 µs → **37.6 ms** (59×), since each request waits behind every other on one monitor. The
compile-time path scales throughput 4.1× to the core count (then plateaus on an 8-core box) and holds
p99 **sub-millisecond** throughout. At 8 sessions that is **47× the aggregate throughput** and **33×
lower p99**; at 32 sessions, **69× lower p99**.

So *for the application shape it applies to*, the §4 lock turns into a hard throughput ceiling and a
tail-latency cliff that the compile-time path removes. We present it as a latent hazard rather than a
headline precisely because that shape — concurrent, high-rate, short typed requests in one JVM — is a
slice of Spark usage, not the common case; the broadly-applicable wins remain single-thread cost and
cold-start (§9). (Caveats: 8 physical cores, so *S* = 16/32 mix lock contention with CPU
oversubscription — the divergence is already clear at *S* ≤ 8; and this is a custom harness, not JMH,
so it carries no inter-fork CIs, though the effect size dwarfs run-to-run noise.)

## §10. The ceiling (secondary): specialized serializers

The derivation replacement reuses Spark's serializer codegen, so it does **not** change per-row
speed. A separate, more ambitious change does: emitting specialized, monomorphic per-row code
instead of routing through Catalyst's `DataType` machinery. This project implements two such
encoders and they beat Spark on the hot path:

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
  compile-time specialization matches or beats Spark's runtime codegen per row, and for the
  interpreted-fallback path (wide schemas above `spark.sql.codegen.maxFields`). Capturing its per-row
  win *inside* fused Dataset codegen would mean replacing whole-stage codegen itself — a separate,
  far larger AOT project.

Full data, methodology (Georges et al., OOPSLA 2007), cross-arch validation, and the end-to-end
query tax that motivates per-row optimization are in the archived
[`REPORT_encoder_perf.md`](archive/REPORT_encoder_perf.md).

---

# Part 4 — Migration

## §11. The argument, and a concrete proposal

The migration argument is narrower and stronger than "our encoder is faster." The load-bearing
claim is about the *derivation*: it is the structural Scala-3 blocker (§1–§3) and a globally-locked
bottleneck (§4), and it can be replaced faithfully (§8), far more cheaply (§9), and without touching
anything else in Spark.

> **Proposal: a `spark-sql-encoder-3` module**, conditionally compiled for Scala 3 builds. It
> provides `deriveAgnosticEncoder[T]` as the Scala-3 implementation of `ExpressionEncoder`'s
> `apply[T]()` factory; the existing reflective derivation stays for Scala 2.13 builds. Same return
> type (`ExpressionEncoder[T]`), same downstream, no change to the public `Dataset` surface. Users
> opt in by depending on the Scala 3 jars.

Implementation sketch:

- `ExpressionEncoder.apply[T]()` is conditionally implemented per Scala version:
  `ScalaReflection.encoderFor` on 2.13, the `Mirror`-based `deriveAgnosticEncoder` on Scala 3 — same
  `AgnosticEncoder` output.
- The eager `val universe` and the two expression-layer utilities (`encodeFieldNameToIdentifier` →
  `NameTransformer.encode`; `findConstructor`'s scala-reflect fallback) are de-reflected so the
  `ScalaReflection` object loads on Scala 3.
- Spark Connect's wire protocol is unaffected — it already uses `AgnosticEncoder` as the interchange
  shape, which the macro produces from `T` exactly as `encoderFor` does.
- Frameless's `TypedEncoder` continues to work on 2.13 and could port to Scala 3 by delegating to the
  macro.

The migration becomes incremental rather than coordinated: ship the Scala 3 encoder module, users
compile typed `Dataset` code against the Scala 3 jars, and the per-user cross-version workarounds
disappear one user at a time.

## §11b. Concrete mapping and migration checklist

The proposal touches a small, enumerable surface. The mapping below is deliberately narrow — it
replaces the *derivation* and de-reflects the `ScalaReflection` object, and **reuses everything
downstream of `AgnosticEncoder` unchanged** (the opposite of replacing `ExpressionEncoder` wholesale):

| This project | Replaces / touches in Spark | Role |
|---|---|---|
| `ProtoEncoder.derived[T]` (Scala 3 `Mirror`/`inline`) | the type-analysis half of `ScalaReflection.encoderFor[T]` | compile-time type → IR |
| `AgnosticEncoderBridge.toAgnostic` | the node-building half of `encoderFor` | lower IR → Spark's `AgnosticEncoder` |
| *(unchanged)* | `ExpressionEncoder`, `Serializer`/`DeserializerBuildHelper`, whole-stage codegen | downstream, already reflection-free |
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

A reflection-free path also changes one user-visible behavior for the better: an unsupported type is a
**compile error** (`Cannot find or derive ProtoEncoder for type …`) rather than a runtime exception
from `encoderFor`. (Earlier integration sketches that routed through a bespoke `InlineRowSerializer`
instead of Spark's `AgnosticEncoder` are superseded by the bridge approach above.)

## §12. What this unlocks — and what it doesn't

**Unlocks.** Spark can begin publishing Scala 3 artifacts for the typed API; the community can use
Scala 3 language features (enums, opaque types, better inlining) in `Dataset` code; the global
derivation lock and the per-JVM reflection cold-start go away; and a native-image **Spark Connect
client** becomes viable (the client's encoding is closure-based and now reflection-free).

**Does not unlock.** The encoder is one of ~15 AOT blockers in Spark 4.1; the structural one —
Catalyst whole-stage codegen via Janino — is untouched by this work, so full Spark is not close to a
native-image binary. Capturing the per-row *serializer* win inside Spark core would require replacing
that codegen, a separate and much larger project. This report's claim is deliberately bounded: the
*derivation* is the Scala-3 blocker, and replacing it is surgical, faithful, and cheap.

## §13. Future work

- **Publication-fidelity derivation benchmark** (`-f3`) plus a cross-architecture sweep
  (Graviton/Intel/AMD), to confirm the §9 numbers generalize beyond the development laptop.
- **Tail of the type surface**: the faithful Scala subset is now complete through tuple-of-case-class
  (§8), and the bridge adds the beyond-Spark extensions UUID/OffsetDateTime/ZonedDateTime as
  String-backed `TransformingEncoder`s (§7). What remains is *out of scope*: Java-bean and Java-enum
  inference (`JavaTypeInference`, Java reflection — already works on Scala 3), UDTs, and a handful of
  types Spark itself cannot encode either (`java.util.Date`, `LocalTime`).
- **Upstream**: prototype the `spark-sql-encoder-3` module against a Scala-3 build of `spark-sql-api`
  and run the typed-`Dataset` test suite — the definitive end-to-end validation that §3's wall is
  the last derivation-side obstacle.
