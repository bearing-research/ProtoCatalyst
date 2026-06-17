# Design: A compile-time replacement for Spark's Scala reflection surface

**Status:** proposed (for review — no implementation yet)

**Goal.** Demonstrate that Spark's Scala *type*-reflection surface can be replaced by **compile-time
derivation** (Scala 3 `Mirror`/`inline`/quotes), **benchmark** it against Spark's reflective
originals, and **write a report** showing Spark maintainers that Scala 3 is a viable, faster path.
Porting upstream is the *final* step, out of scope here.

**This is not a plugin.** Stock Spark is used only as a **correctness oracle** and **benchmark
baseline** (its Scala 2.13 jars, callable from Scala 3 via `CrossVersion.for3Use2_13`).

---

## 0. Decisions (resolved 2026-05-30)

- **D1 — lowering approach = (A).** Expose `ProtoEncoder`'s Option/Collection/Map inner encoders
  (sealed ADT / accessors); the bridge is a Spark-side recursive function. Refinement: every
  `ProtoEncoder` node carries `clsTag`, which disambiguates normalized leaves (`BigInt` vs
  `Decimal`, `UUID` vs `String`, `OffsetDateTime` vs `Instant`), so **only those three composite
  kinds need exposing** — leaves (`clsTag` + `catalystType`), products (`fields`), and sums
  (`variants`) are already public.
- **D2 — parity target = structural.** The lowered `AgnosticEncoder` must `==` Spark's on the
  **Spark-supported corpus** (primary bar); round-trip is the backstop. We construct Spark's actual
  `spark-sql-api` case classes, so `==` is genuinely structural.
  - **Corpus split:** extension types Spark's `encoderFor` *rejects* (`UUID`, `OffsetDateTime`,
    `ZonedDateTime`, `java.util.Date`, `LocalTime`/`TimeType`, data-carrying sum types) have no
    golden — excluded from structural parity, validated by round-trip only. A "beyond Spark" beat
    for the report.
  - **Sharp edges to tune in M3:** corpus is **top-level types only** (`outerPointerGetter` is
    `None` for non-inner classes; inner-class `() => AnyRef` getters won't compare equal);
    collection `clsTag` must match Spark's `createIterableEncoder` runtimeClass choice; defaults
    (Decimal 38/18, strict dates, `lenientSerialization=false`) must align.
- **D3 — extension-mismatch (two layers).** Types neither `ProtoEncoder` nor Spark supports already
  fail at **compile time** via `ProtoEncoder.derived`'s `inline error`. Types `ProtoEncoder`
  supports but Spark cannot represent are handled by the **runtime** bridge: lowered through a real
  Spark `TransformingEncoder` + `Codec` when a sound conversion exists (e.g. `UUID ↔ String`), or
  **rejected at bridge invocation** (a runtime `IllegalArgumentException`) otherwise — never mapped
  directly onto a leaf encoder whose serializer expects a different runtime type. The
  structural-parity corpus excludes all of these.
- **D4 — bridge form/placement:** a plain recursive **runtime function** `ProtoEncoder[T] →
  AgnosticEncoder[T]` (no macro), in **`encoder-spark`** (already has Spark deps +
  `CrossVersion.for3Use2_13` + `SparkTypeMapping`). Rejection is therefore *runtime* (D3); a
  later `inline` wrapper could surface it at compile time if the report wants that claim.
- **D5 — derivation scope:** case classes + tuples + existing `ProtoEncoder` givens; **concrete
  types only** (abstract `T` → compile error); non-case-class `DefinedByConstructorParams`
  deferred; **Scala 3 `enum` + Java enum in, `scala.Enumeration#Value` out**.
- **D6 — parity oracle:** goldens from a Scala 2.13 helper (`encoderFor[T: TypeTag]`), structural
  compare (case-class `==`; `ClassTag` by `runtimeClass`).

---

## 1. We already have most of the engine — `encoder/`

A substantial compile-time encoder framework exists in the `encoder` module. It is **engine
independent** (the project's "LLVM IR" thesis): it derives into the project's own
`ProtoEncoder`/`ProtoType`/`ProtoSchema`, and a separate Spark-facing bridge lowers that IR to
Spark types. It deliberately mirrors Spark's `AgnosticEncoder` surface.

| Prior art (`encoder/src`) | Mirrors in Spark |
|---|---|
| `ProtoEncoder.derived[T]` (`Mirror`/`inline`/`summonFrom`) | `ScalaReflection.encoderFor` (the reflective derivation) |
| `ProtoEncoder` givens: primitives, boxed, all temporal (+UUID, OffsetDateTime, ZonedDateTime, java.util.Date), Char/Varchar, BigInt | the leaf `AgnosticEncoder`s |
| tuples 2–22; case classes (`Mirror.ProductOf`) | `ProductEncoder` |
| Option; Seq/List/Vector/Set/Array; Map | `OptionEncoder`/`ArrayEncoder`/`IterableEncoder`/`MapEncoder` |
| Scala 3 enums + sealed traits (`SumEncoder`); Java enums | `ScalaEnumEncoder`/`JavaEnumEncoder` (+ a sum-type extension Spark lacks) |
| `ProtoUDT` | `UDTEncoder` |
| `RowEncoder` | `RowEncoder`/`UnboundRowEncoder` |
| `JavaBeanEncoder` | `JavaBeanEncoder` |
| `TransformingEncoder` + `codec/` (Fory/Java/Kryo) | `TransformingEncoder` (+ Kryo/Java codecs) |
| `InlineRowSerializer` / `InlineSumRowSerializer` | the object↔row serializer functions |
| `SparkTypeMapping` (`ProtoType → Spark DataType`) | `schemaFor` / `toArrowType`-style lowering |

**Implication:** the compile-time *derivation* that replaces `ScalaReflection.encoderFor`'s
reflection **already exists** (`ProtoEncoder.derived`), and the **schema** half of the bridge
(`ProtoType → DataType`) exists (`SparkTypeMapping`). The reflection-replacement story is therefore
mostly about *connecting* this engine to Spark's encoder ADT — not re-deriving from scratch.

---

## 2. The seam and the one missing bridge

Spark's entire typed-encoder pipeline funnels through one reflective function
(`ScalaReflection`, `spark-sql-api`):

```scala
val universe = scala.reflect.runtime.universe          // runtime reflection (Scala-3-impossible)
def encoderFor[E: TypeTag]: AgnosticEncoder[E]          // the only reflective entry point
def schemaFor[T: TypeTag]: Schema                       // = (encoderFor[T].dataType, .nullable)
```

`encoderFor` returns `AgnosticEncoder[E]` — a pure ADT — and the downstream (`ExpressionEncoder` →
Catalyst expressions → codegen) is **almost** reflection-free: there is a small **secondary
surface** (§2.1, found in M0) that must be handled too. ~16 other files merely *thread a `TypeTag`*
into `encoderFor`; on Scala 3 those become `[T]`-with-a-derived-given signature swaps (forced —
`TypeTag` doesn't exist).

So the whole replacement reduces to producing `AgnosticEncoder[T]` at compile time. We already
produce `ProtoEncoder[T]` at compile time. **The missing piece is a lowering:**

```scala
ProtoEncoder[T]  ──(new bridge)──►  AgnosticEncoder[T]  ──►  ExpressionEncoder  ──►  codegen
   (exists)                            (Spark's ADT)            (unchanged Spark)
```

This is the encoder-level analog of what `SparkTypeMapping` already does at the schema level
(`ProtoType → DataType`).

### 2.1 Secondary reflection surface (found in M0)

M0 surfaced that the downstream is not *entirely* reflection-free. Building an `AgnosticEncoder` and
running its serializer/deserializer through Spark's codegen from a Scala 3 process **crashes**:
`Invoke.encodedFunctionName` calls `ScalaReflection.encodeFieldNameToIdentifier`
(`TermName(name).encodedName` — a `scala.reflect.runtime` use), and merely *touching* the
`ScalaReflection` object eagerly initializes its `val universe = scala.reflect.runtime.universe`,
which cannot initialize against the Scala 3 stdlib (`FatalError: class Array does not have a member
apply`).

A scan of the whole expression/encoder layer for references to the `ScalaReflection` object (each of
which trips that eager `val universe`) finds it in **only 3 files, via 5 members**:

| Member | Site | Kind | Replacement |
|---|---|---|---|
| `encoderFor` | `ExpressionEncoder` | the primary blocker | `ProtoEncoder` + this bridge |
| `schemaFor` | `literals` (typed `Literal`) | derivation family | falls out (`deriveSchema`) |
| `Schema` | `ExpressionEncoder` | type reference | trivial |
| `encodeFieldNameToIdentifier` | `Invoke` | genuine scala-reflect (name-mangling) | `scala.reflect.NameTransformer.encode` |
| `findConstructor` | `NewInstance` | **Java** reflection + a scala-reflect *fallback* | keep the Java path; replace the companion-`apply` fallback |

The **root cause is the single eager field** `val universe = scala.reflect.runtime.universe` on the
`ScalaReflection` object — it poisons every co-located utility, including ones whose own logic is
pure Java reflection (`findConstructor`'s primary path) or pure string work
(`encodeFieldNameToIdentifier` is just Scala identifier mangling). Removing that field in a Scala-3
port de-poisons them; the two expression-layer utilities are then mechanical to port. So the surface
is: **`encoderFor`/`schemaFor` (the real work) + two trivial expression-layer utilities.**

#### 2.1.1 The wall is removable — demonstrated (`spark-reflection-patch`)

The claim above is now backed by a working demonstrator rather than reasoning alone. The module
`spark-reflection-patch` is a **verbatim copy of Spark 4.1.2's `ScalaReflection`** (the `sql-api`
copy) with exactly **two lines** changed:

1. `val universe` → **`lazy val universe`** — the object's `<clinit>` no longer forces runtime
   reflection. The universe is forced only if `encoderFor`/`schemaFor`/`findConstructor`'s fallback
   is *invoked*; the ser/deser execution path invokes none of them.
2. `encodeFieldNameToIdentifier` body → **`scala.reflect.NameTransformer.encode(fieldName)`**
   (scala-library, identical name-mangling) instead of `universe.TermName(_).encodedName`.

`findConstructor` needed no change: its primary `ConstructorUtils` (Java-reflection) path covers the
case classes exercised, and the scala-reflect fallback is never reached for them.

The module is compiled on Scala 2.13 and prepended to `encoder-spark`'s test classpath so it
**shadows** Spark's `ScalaReflection`. With it, `encoder-spark`'s `ExecutionWallSpec` — running in a
**Scala 3** module — builds Spark's serializer/deserializer from our compile-time-derived
`AgnosticEncoder` and round-trips real values (flat/nested products, all primitive widths,
`java.lang` boxed types, `Some`/`None`, maps, collections incl. `Array`, collection/map/option *of* a
case class, tuples). 11 cases, all green; the full `encoder-spark` suite stays green (the
patch is behavior-preserving). This is the M0 finding's resolution: the wall is a two-line change,
and structural parity (D6) is thereby upgraded to *observed* behavioral parity for the executed slice.

This is strong report material: it's a concrete, minimal proof that *stock Spark cannot run on
Scala 3*, and it pinpoints the entire surface.

---

## 3. The key design decision (resolved — see §0, D1)

A faithful `AgnosticEncoder` cannot be rebuilt from `ProtoType` alone — Spark's nodes need a
`ClassTag` at every level (`ProductEncoder`/`IterableEncoder`/`MapEncoder`) and need to preserve
distinctions `ProtoType` collapses (`Option[X]` vs nullable-`X`; `Array` vs `Seq` vs `Set`). That
information lives in the `ProtoEncoder` *structure*, but today the recursive inner encoders for
Option/Collection/Map are **private** to their impl classes, so an external bridge can't traverse
them. Three ways to fix that:

- **(A) Expose `ProtoEncoder`'s shape** — make it a sealed ADT (or add structural accessors for
  inner encoders) so a Spark-facing `AgnosticEncoderBridge` can pattern-match it, exactly as
  `SparkTypeMapping` pattern-matches the sealed `ProtoType`. *Keeps `encoder/` Spark-free; consistent
  with the existing layering.* **(recommended)**
- **(B) Parallel derivation** — a separate `inline` derivation in the Spark-facing module that emits
  `AgnosticEncoder` directly. Never lossy, but duplicates ~the whole givens table / dispatch.
- **(C) `def toAgnostic` on `ProtoEncoder`** — co-locate the lowering with each node (total, minimal),
  but couples the engine-independent module to Spark, breaking the IR layering.

**Resolved: (A)** (see §0) — preserves the engine-independent thesis and mirrors how `ProtoType` +
`SparkTypeMapping` already work. The `clsTag` refinement bounds it to exposing the three composite
kinds only.

---

## 4. Output shapes & rules (verified against Spark source)

The bridge maps each `ProtoEncoder` node to an `AgnosticEncoder` node:

| ProtoEncoder node | AgnosticEncoder |
|---|---|
| primitive / leaf | the matching case object (`PrimitiveIntEncoder`, `StringEncoder`, …) |
| boxed | `Boxed*Encoder` |
| Option(inner) | `OptionEncoder(bridge(inner))` |
| Collection(kind, inner) | `ArrayEncoder` (Array) or `IterableEncoder(clsTag, …, lenient=false)` (Seq/Set) |
| Map(k, v) | `MapEncoder(clsTag, bridge(k), bridge(v), v.nullable)` |
| Product(fields) / tuple | `ProductEncoder(clsTag, fields.map(EncoderField(name, bridge(enc), nullable, Metadata.empty)), None)` |

Rules to match exactly (source-verified): **dispatch order** (Null → primitives → boxed →
`Array[Byte]`→Binary → enums → leaf refs → UDT → Option → Array → Seq → Set → Map → Product);
**nullable = `!isPrimitive`**; **Map detection symbol-based** (`baseType`, not `<:<` — key-invariant);
defaults **Decimal (38,18)**, **strict** date encoders, collections `lenientSerialization = false`;
cycles → compile error.

**Extension mismatches (per D3 — no naive leaf substitution).** `ProtoEncoder` has nodes Spark
lacks. A direct map like `UUID → StringEncoder` is **unsafe**: Spark's `StringEncoder` serializer
expects a `String` at runtime and would fail on a `UUID` field. So each extension is either:
- **lowered via a real `TransformingEncoder(leafEnc, Codec[Ext, Spark])`** when a sound bijective
  conversion exists. Implemented: `UUID`, `OffsetDateTime`, `ZonedDateTime` — all over **`StringEncoder`**
  (ISO-8601 `toString`/`parse`, lossless: an `Instant`/`Timestamp` base would collapse the offset/zone
  to a UTC instant). `java.util.Date` is currently **rejected** (not yet bridged). The project's existing
  `TransformingEncoder` + `codec/` machinery is the vehicle; the codec performs the runtime conversion
  the leaf serializer needs. *(A `TransformingEncoder` golden has no `ScalaReflection` counterpart, so
  these are validated by round-trip, not structural parity.)*
- **or rejected** (D3 runtime error) when no faithful Spark representation exists — `LocalTime`/
  `TimeType` (Spark 4.1 rejects it outright) and the data-carrying **sum-type** encoder (no
  `AgnosticEncoder` equivalent; `SparkTypeMapping` already flags `ProtoType.SumType` as un-lowerable).

Whether to *implement* the codec-wrapped extensions or simply reject all extensions in M1 is an
implementation choice; the corpus and structural-parity claim are unaffected either way.

---

## 5. Oracle harness (stock Spark as ground truth, not integration)

From Scala 3 against Spark's 2.13 jars (`CrossVersion.for3Use2_13`, already used by `encoder-spark`):

1. **Structural parity (primary — and now the behavioral guarantee):**
   `bridge(ProtoEncoder.derived[T])` vs a golden `ScalaReflection.encoderFor[T]` (golden generated by
   a Scala 2.13 helper — the cross-compile fixture pattern we use for Arrow), compared via a
   canonical, class-name-normalized structural dump (the user case class lives in different packages
   on each side, so `ClassTag`s compare by *simple* name). Because our `AgnosticEncoder` is
   *identical* to Spark's, feeding it to `ExpressionEncoder` yields identical expressions/codegen —
   so **structural parity *is* the behavioral guarantee**; we don't need to execute to know it
   round-trips.
2. **Build + schema sanity:** `ExpressionEncoder(ours)` constructs and its `dataType`/schema is
   correct — reflection-free, so it runs fine from Scala 3.
3. **In-process end-to-end execution (now available — §2.1.1):** with the two-line
   `spark-reflection-patch` shadowing `ScalaReflection`, `ExecutionWallSpec` runs Spark's *unmodified*
   ser/deser from a Scala 3 process and round-trips real values. Structural parity is thereby
   *observed*, not just argued, for the executed slice.

> **In-process execution oracle (M0 finding RESOLVED, §2.1.1).** Running ser/deser from a Scala 3
> process originally crashed on stock Spark's residual `scala.reflect.runtime` (`Invoke`/`NewInstance`
> → the poisoned `ScalaReflection` object). A two-line patch (lazy `universe`; `NameTransformer`)
> removes the wall, and end-to-end execution now runs against those patched 2.13 jars from a Scala 3
> JVM — no full Scala-3 port of Spark required to demonstrate it. Structural parity remains the broad
> oracle across the full type surface.

---

## 6. Scope (confirmed: Scala core in, Java beans out)

**In:** the Scala type-reflection core — `ProtoEncoder.derived` coverage lowered to `AgnosticEncoder`
(~33/38 variants) + `schemaFor`. Transitively covers Encoders/Dataset/UDF inference (all call
`encoderFor`).

**Out:** `JavaBeanEncoder`/`JavaTypeInference` (Java reflection — works on Scala 3, *not* a blocker —
even though a `JavaBeanEncoder` analog exists in `encoder/`, it's excluded from the claim);
`Row`/`UnboundRowEncoder` (already reflection-free); `UDTEncoder` (annotation lookup — deferrable);
inner-class `outerPointerGetter`.

---

## 7. Benchmark plan

- **Headline — derivation cost.** Reflective `ScalaReflection.encoderFor[T]()` /
  `ExpressionEncoder[T]()` first-call cost and **global `ScalaReflectionLock` contention** under N
  threads, vs our path. The honest claim is **no Scala reflection, no `TypeTag`, no
  `ScalaReflectionLock`** — *not* "zero runtime": `ProtoEncoder.derived` + the bridge still do
  plain object construction at runtime (cheap, allocation-bounded, lock-free, and amortized to once
  per type when held in a `given`/`val`, exactly as Spark caches `ExpressionEncoder`). Benchmark
  that exact path (first-call and cached) rather than claiming it away.
- **Support — serialization throughput/allocation.** The existing §11 (UnsafeRow) / §11b (Arrow)
  microbenchmarks (the lambda encoders), cross-arch (EC2 work) for credibility.
- **Corpus.** TPC-H schemas + nested fixtures (Struct/List/Map/Option) + primitives/boxed/temporal/
  decimal/enum.

### 7.1 Results (M5) — derivation throughput, `Lineitem` (16 fields)

The exact lock is `ScalaSubtypeLock.synchronized { t1 <:< t2 }` in `ScalaReflection.isSubtype`,
taken on **every** subtype check in `encoderFor`'s dispatch — because Scala 2's `<:<` is not
thread-safe (scala/bug#10766). So Spark serializes all encoder derivation on one global lock;
compile-time Scala 3 derivation has no runtime `<:<` and no lock.

`SparkEncoderDerivationBenchmarks` (`ScalaReflection.encoderFor[Lineitem]`, Scala 2.13) vs
`EncoderDerivationBenchmarks` (`toAgnostic(ProtoEncoder.derived[Lineitem])`, Scala 3), Throughput:

| Threads | Spark (reflective) ops/s | Ours (compile-time) ops/s | Speedup |
|---:|---:|---:|---:|
| 1 | 2,304 ± 9 | 896,924 ± 7k | **~389×** |
| 8 | **1,660 ± 26** | 3,926,237 ± 125k | **~2,365×** |
| scaling 1→8 | **0.72× (degrades)** | 4.4× | |

**Spark's derivation gets *slower* with more threads** (lock contention); ours scales with cores.
*Caveats:* local M1 (8-core: 4 perf + 4 efficiency, hence ~4.4× not 8×), `-f3 -wi5 -i10` (publication
time-axis fidelity; cross-arch EC2 sweep still pending). This measures **derivation** (building the
encoder), not execution; and it's *not* "zero runtime" — ours is plain lock-free object construction.
The `deriveMixed` variant (8 distinct types/op, no repeat) closes the "it's cached" loophole and
shows the same shape: Spark 538→437→403→402 ops/s across 1→2→4→8 threads (degrades), ours
208k→416k→751k→908k (scales). Compile-time cost of derivation: ~1.0s fixed + ~20ms/type of `scalac`
(REPORT §9c). On a many-core server the gap widens: Spark stays flat/degrades, ours keeps scaling.

---

## 8. Milestones (each independently reviewable)

- **M0 — Bridge spike — ✅ DONE.** `AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[Person])`
  is **structurally identical** to Spark's `ScalaReflection.encoderFor[Person]` (golden:
  `Product[Person](id:PrimitiveIntEncoder!,name:StringEncoder?,active:PrimitiveBooleanEncoder!,score:PrimitiveDoubleEncoder!)`).
  Two findings: (a) `ProtoEncoder`'s `PrimitiveEncoder.nullable` is hardcoded `false` for *all*
  leaves (so `String` came out non-nullable) — the bridge takes nullability from the lowered child's
  `.nullable` (Spark's `!isPrimitive` rule) instead; the `ProtoEncoder` bug should be fixed at source
  in M1. (b) the §2.1 secondary-surface / no-in-process-execution finding (**since resolved** — see
  M6 and §2.1.1).
- **M1 — Leaf coverage — ✅ DONE.** All leaf nodes lowered with `clsTag` disambiguation, structural
  parity green for `Primitives` (7 unboxed), `Boxed` (7 boxed), `Scalars` (String/Binary/Scala+Java
  Decimal at `(38,18)`/BigInt), `Temporal` (Date/LocalDate/Timestamp/Instant/LocalDateTime/Duration/
  Period, all `lenient=false`). The canonical dump now captures decimal precision/scale and
  `lenientSerialization`. Extensions `UUID`/`OffsetDateTime`/`ZonedDateTime` are now lowered as
  String-backed `TransformingEncoder`s (M4, done); `java.util.Date`, `LocalTime`, boxed `Char`, and
  Java enums remain rejected (D3 — no faithful Spark target).
  *Deferred:* fixing `ProtoEncoder.PrimitiveEncoder.nullable` at source — the bridge takes
  nullability from the lowered child's `.nullable` (Spark-correct) regardless, and changing the core
  `encoder/` module carries UnsafeRow/Arrow regression risk; tracked as a separate follow-up.
- **M2 — Recursive — ✅ DONE (partial).** D1=A landed: `ProtoEncoder` now exposes `optionElement`/
  `collectionElement`/`mapKeyValue` (additive; no regressions — `encoder` 268, `encoder-spark` 157).
  Bridge lowers Option (checked first — its `catalystType` hides it), Array→`ArrayEncoder`,
  Seq/List/Vector/Set→`IterableEncoder` (clsTag simple names matched Spark's with no alignment
  needed), Map→`MapEncoder`, nested Product, and combinations (`Seq[Option[Int]]`). containsNull/
  valueContainsNull taken from the lowered child's `.nullable`. Structural parity green for
  Opts/Colls/Maps/Nested/Wrapped.
  **Engine gap — ✅ FIXED.** `ProtoEncoder` now derives *collection/option/map of a case class*
  (`Seq[Address]`, `Map[String,Address]`, `Option[Address]`). The collection/option/map givens were
  changed from `given X(using ProtoEncoder[element])` to **`inline given`** that resolve the element
  via the existing inline `summonEncoder` — which already handles case classes through its
  `Mirror.ProductOf → derived` branch (a plain `using` can't reach that, since case classes have no
  summonable given). The previous design only worked when the element used `derives ProtoEncoder`
  (companion given); the bridge derives via `ProtoEncoder.derived[T]` (no companion givens), so it
  hit the gap. Because the inline givens construct private encoder classes, construction is routed
  through public `makeOptionEncoder`/`makeCollectionEncoder`/`makeMapEncoder` factories (else the
  private class isn't accessible when the given inlines at an external call site). `Deep`
  (Seq/Map/Option of `Address`) now passes structural parity. No regressions: `encoder` 268,
  `encoder-spark` 158, `arrow` 158, `query` 166. *(Tuple-of-case-class — e.g. a field `(String,
  Address)` — also works: the generic `Mirror.ProductOf` path derives it as `Product[Tuple2]` with
  `_1`/`_2`, byte-identical to Spark's golden; the explicit tuple givens are bypassed for it.
  Locked in by `HasTupleCC` parity + an end-to-end round-trip in `ExecutionWallSpec`.)*
- **M3 — Parity harness:** structural + schema parity vs `ScalaReflection.encoderFor` over the corpus.
- **M4 — Tail / Scala-3 superset — partial.** Total parity was never the bar: we reproduce Spark
  *where Spark has an encoder*, and **define** sensible behavior where it doesn't (a Scala-3
  capability Spark's reflection lacks) — validated by self-consistency, not a Spark golden.
  - ✅ **Scala 3 `enum` (simple, all-singleton)** → `TransformingEncoder(clsTag, StringEncoder,
    valueOf-codec, nullable=true)` — faithful (round-trips the case name via the companion's
    `valueOf`, Java reflection only; a bare `StringEncoder` would be lossy). Test asserts
    `Transforming[Color,StringEncoder]`.
  - ✅ **Data-carrying Scala 3 enum / sealed-trait ADT** → clean rejection: Spark's `AgnosticEncoder`
    has **no** sum-type representation (a genuine Scala-3 gap in Spark's model, not a bridge limit).
  - ✅ **String-backed extensions** `UUID`, `OffsetDateTime`, `ZonedDateTime` → `TransformingEncoder`
    over `StringEncoder`, validated by round-trip (no `ScalaReflection` oracle; not Scala-3-specific).
    Still deferred: Java enums (`JavaEnumEncoder` — parity-testable but needs a `.java` enum shared
    across `benchmark-spark`/`encoder-spark`) and `java.util.Date`.
  - The full superset catalog (every Scala-3-unique behavior + its implementation) lives in
    [`SCALA3_SUPERSET.md`](SCALA3_SUPERSET.md).
- **M5 — Benchmark — ✅ DONE (`-f3`, local).** `SparkEncoderDerivationBenchmarks` (2.13) +
  `EncoderDerivationBenchmarks` (Scala 3). Result (§7.1, `-f3 -wi5 -i10`): ~389× single-threaded,
  ~2,365× at 8 threads; Spark *degrades* under concurrency (`ScalaSubtypeLock`), ours scales 4.4×. A
  `deriveMixed` pair (8 distinct types/op, no repeat — closes the "encoders are cached" loophole)
  confirms the degradation is the lock, not per-type cost. Compile-time cost measured (REPORT §9c:
  ~1.0s fixed + ~20ms/type `scalac`). Remaining: cross-arch EC2 sweep.
- **M6 — Break the execution wall — ✅ DONE (§2.1.1).** `spark-reflection-patch` = Spark 4.1.2's
  `ScalaReflection` with two lines changed (lazy `universe`; `encodeFieldNameToIdentifier` via
  `NameTransformer.encode`), compiled on 2.13 and shadowing Spark's copy on `encoder-spark`'s test
  classpath. `ExecutionWallSpec` then round-trips real values (11 cases across the corpus) through
  Spark's **unmodified** codegen ser/deser **from a Scala 3 process** — turning structural parity
  into observed behavioral parity. Full `encoder-spark` suite stays green.
- **M7 — Single-macro upstream form — ✅ DONE (REPORT §11b).** The in-repo path is two layers
  (`ProtoEncoder.derived` → `AgnosticEncoderBridge.toAgnostic`) only because `ProtoEncoder` also
  targets non-Spark backends. Inside Spark there is no second IR, so the upstream artifact collapses to
  a *single* `Mirror`/`inline` macro: `AgnosticDerivation.deriveAgnosticEncoder[T]` (`encoder-spark`)
  emits `AgnosticEncoder` directly — no `ProtoType`, no bridge. `AgnosticDerivationSpec` validates it
  against the **same** §3.4 goldens the bridge passes (full corpus + Scala-3 enum + UUID/Offset/Zoned
  extensions, 20 cases), and `ExpressionEncoder` builds from it. One improvement the out-of-tree
  runtime bridge can't make: a data-carrying ADT is rejected at **compile time**
  (`scala.compiletime.error`), not at invocation. This is the file a `spark-sql-encoder-3` module would
  ship in place of `encoderFor`. (It still consumes Spark's `AgnosticEncoder` from the 2.13 jars via
  `for3Use2_13`; compiling `spark-sql-api` itself on Scala 3 — whose encoder closure couples through
  `DataType` to the SQL parser + json4s — is the larger, separate step in REPORT §13.)

---

## 9. Non-goals (this phase)

- **Not a plugin / not integration.** Stock Spark = oracle + baseline only.
- **Upstreaming / forking Spark** — the final, separate phase; M0–M5 stand against stock jars.
- **Java-bean inference, `Row` encoders** — out of scope (not Scala-3 blockers).
- The lambda (`InlineRowSerializer` / UnsafeRow / Arrow) encoders remain benchmark artifacts; this
  initiative adds the *derivation-to-Spark* bridge.

---

## 10. Residual questions (post-decisions)

All load-bearing decisions are resolved in §0. Remaining details to settle during implementation:
- Exact collection `clsTag` Spark's `createIterableEncoder` produces per declared type (Seq/List/
  Vector/Set) — align `ProtoEncoder`'s collection givens to match (M3).
- Scala 3 `enum` → `ScalaEnumEncoder` parent-`Class` capture specifics (M4).
