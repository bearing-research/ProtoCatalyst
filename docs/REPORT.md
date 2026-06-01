# Replacing Spark's Reflective Encoder Derivation: A Compile-Time Path to Scala 3

Apache Spark cannot run on Scala 3, and the reason is one function. Spark's typed-`Dataset[T]`
API derives every encoder through `ScalaReflection.encoderFor[T: TypeTag]`, which is built on Scala
2's runtime reflection (`scala.reflect.runtime.universe`). That reflection does not work for Scala
3 types — and, separately, it serializes *all* encoder derivation in a JVM on a single global lock.

This report shows that the reflective derivation can be replaced, wholesale, by **compile-time
Scala 3 `Mirror` derivation that produces Spark's own `AgnosticEncoder`** — so the entire rest of
Spark's encoder pipeline is reused unchanged. The replacement is:

- **Faithful** — the derived `AgnosticEncoder` is byte-identical to what `ScalaReflection.encoderFor`
  produces, across the common type surface (§8).
- **Far cheaper** — ~391× faster to derive single-threaded, ~2,595× at 8 threads; and where Spark's
  reflective derivation *degrades* under concurrency (the global lock), the compile-time path scales
  with cores (§9).
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
> documented in full in the archived [`REPORT_encoder_perf.md`](REPORT_encoder_perf.md). It is not
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
on one monitor. This is invisible in a single-threaded micro but devastating under concurrency —
multi-tenant Spark Connect servers, REPL/notebook sessions, jobs touching many distinct case
classes. Compile-time Scala 3 derivation does the type analysis at `scalac` time; there is no
runtime `<:<` and no lock. §9 measures exactly what that is worth; §9b shows the lock is on a real,
uncached code path — every typed `.as[T]`/`.map`/`createDataset` re-derives — not just a micro.

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
| 1 | 2,309 ± 69 | 902,906 ± 11k | **~391×** |
| 8 | **1,580 ± 65** | 4,099,836 ± 144k | **~2,595×** |
| scaling 1→8 | **0.68× (slower)** | 4.5× | — |

Two things stand out. Single-threaded, compile-time derivation is ~400× faster — no reflection walk,
just object construction. And, more telling: **Spark's derivation gets *slower* with more threads**
(2,309 → 1,580 ops/s as contention on `ScalaSubtypeLock` rises), while ours scales with cores (4.5×
on this 4-performance-core machine). On a many-core server the gap only widens.

**Honest scope.** These are local Apple-M1 numbers at directional fidelity (`-f1 -wi3 -i5`; a
publication run is `-f3` plus a cross-architecture sweep). They measure *derivation* (building the
encoder description), not execution, and the compile-time side is *not* "zero runtime" — it is
lock-free object construction. A user who hoists the encoder into a `val` amortizes either path to
once per type; the lock bites in the paths that *can't* hoist — which, as §9b shows, are the
realistic concurrent ones. There is also a one-time cold-start the replacement avoids: the
first `scala.reflect.runtime.universe` initialization in a JVM is ~500 ms (measured), and Spark's
first per-encoder codegen pass is ~100 ms — both paid once per JVM, both zero for compile-time
derivation.

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

`ScalaSubtypeLock` is a single JVM-static monitor, so this serializes wherever one driver JVM builds
typed plans concurrently: a **Spark Connect or Thrift server** (one shared JVM, many sessions, each
request deserializing a typed relation server-side), **parallel job submission** (the documented
FAIR-scheduler pattern, `Future { df.as[T]… }` fanned out over distinct datasets), and concurrent
**notebook/REPL** cells.

To measure that path with the "it's cached" loophole closed, a second benchmark pair
(`deriveMixed`) derives **all 8 distinct TPC-H encoders per op, no type ever repeating** — so even a
hypothetical per-type cache could not help; the only thing shared across threads is the lock itself:

| Threads | Spark `encoderFor`, 8 distinct types (ops/s) | Compile-time (ops/s) | Speedup |
|---:|---:|---:|---:|
| 1 | 552.7 ± 27 | 215,343 ± 13k | **~390×** |
| 8 | **403.8 ± 11** | 886,382 ± 287k | **~2,195×** |
| scaling 1→8 | **0.73× (slower)** | 4.1× | — |

The result is unambiguous: with eight cores deriving eight *different* case classes, Spark's
throughput *drops* below its single-threaded rate — adding workers makes it slower because they
queue on one monitor regardless of type. The compile-time path, doing the identical work, scales 4.1×.
This is the "many encoders derived concurrently and uncached" case made concrete, and it is exactly
the shape of a multi-tenant Connect server.

## §9c. Measurement validity

The two suites live in different modules and Scala versions (the baseline `encoderFor[T: TypeTag]`
exists only on 2.13; the compile-time path only on Scala 3 — see `docs/INFRASTRUCTURE.md`), so it is
worth stating why the comparison is sound and what the harness does and does not contribute.

- **The harness is excluded from the measurement.** Both suites are JMH `Throughput`, run with
  `Jmh / fork := true`. JMH launches a *fresh* JVM per fork; sbt only starts it. The warmup
  iterations (`-wi`) run before any timed iteration, so class-loading, JIT compilation to
  steady-state, and one-time initialization are not counted. The numbers are steady-state per-op
  throughput, not wall-clock that includes launcher latency.

- **The cold-start exclusion is conservative *against* us.** Spark's first
  `scala.reflect.runtime.universe` initialization (~500 ms, measured) and first codegen pass happen
  during warmup, so they do not appear in the reported Spark per-op figure. Real workloads pay them;
  the compile-time path does not pay either. The headline ratios therefore understate the
  end-to-end difference rather than inflate it.

- **Same machine, JDK, and JMH parameters on both sides.** The only intended difference between the
  two forks is the operation under test — reflective vs compile-time production of the *same*
  `AgnosticEncoder`. Both return the constructed encoder (an `AnyRef` / `Array[AnyRef]`) so the JIT
  cannot dead-code-eliminate the work; no `Blackhole` is needed. Thread scaling uses `-t N` within a
  single fork, so the global `ScalaSubtypeLock` contention (§4) is genuinely exercised.

- **The one asymmetry, stated plainly.** `ProtoEncoder.derived[T]` performs the *type analysis* at
  `scalac` time; the runtime benchmark on our side therefore measures only the residual work — the
  bridge's recursive lowering plus `AgnosticEncoder` object construction — whereas Spark's
  `encoderFor[T]` does the whole walk at runtime. This is not a measurement trick; it *is* the thesis
  ("move the analysis to compile time"). The cost does not vanish — it shifts to compilation
  (`inline` expansion adds to `scalac` time, paid once per build, not per run). A complete accounting
  would report that `scalac` delta; we have not yet measured it, and flag it as the honest debit
  against the runtime win.

- **Fidelity caveat (unchanged from §9).** The numbers here are local Apple-M1, `-f1 -wi3 -i5`
  (directional). A single fork does not capture inter-JVM variance, which is exactly what JMH's
  ≥3-fork guidance addresses; publication requires `-f3` (the value baked into the suites'
  `@Fork`/`@Warmup`/`@Measurement`) plus the cross-architecture sweep noted in §13.

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
[`REPORT_encoder_perf.md`](REPORT_encoder_perf.md).

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
