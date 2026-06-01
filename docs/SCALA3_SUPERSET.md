# Scala 3 behaviors beyond Spark's encoder model

Companion to [`REFLECTION_REPLACEMENT.md`](REFLECTION_REPLACEMENT.md). That doc covers the
**faithful subset** — where our compile-time `ProtoEncoder → AgnosticEncoder` bridge reproduces
Spark's reflective `ScalaReflection.encoderFor` byte-for-byte. This doc catalogs the **Scala-3
superset**: behaviors that diverge from, or exceed, Spark's encoder model *because of Scala 3*.

**Why a superset exists at all.** The goal is to run Spark on Scala 3, and total parity with Spark's
Scala-2.13 reflection is impossible by construction: Scala 3 has language features (notably `enum`)
that the 2.13 type world cannot even reference, so there is nothing to be "parity" with. Where Spark
has an encoder we match it; where it doesn't, we **define** sensible behavior and validate it by
**self-consistency** (there can be no Spark golden). Each entry below states: what Spark does, what
we do, why, where it's implemented, and how it's validated.

---

## 1. The derivation mechanism itself (the root divergence)

| | |
|---|---|
| **Spark** | `ScalaReflection.encoderFor[T: TypeTag]`, driven by `scala.reflect.runtime.universe`. |
| **Ours** | `ProtoEncoder.derived[T]` via `scala.deriving.Mirror.Of[T]` + `inline` (`summonInline`, `erasedValue`, `constValue`). No `TypeTag`, no runtime reflection. |
| **Why Scala 3** | `TypeTag` and `scala.reflect.runtime.universe` **do not exist for Scala 3 types** — `ScalaReflection` cannot be ported, only re-derived via Mirrors. This is *the* structural blocker for Spark-on-Scala-3 and the reason the project exists. |
| **Impl** | `encoder/…/ProtoEncoder.scala` (`derived`, `deriveProduct`, `deriveEnum`, `summonEncoder`); `encoder-spark/…/AgnosticEncoderBridge.scala` (`toAgnostic`). |
| **Validation** | The entire faithful-subset parity suite (`AgnosticEncoderBridgeSpec`) — every byte-identical result is produced with zero `TypeTag`. |

> Corollary (see `REFLECTION_REPLACEMENT.md` §2.1): even *running* Spark's serializer codegen from a
> Scala 3 process crashes, because `Invoke`/`NewInstance` touch the `ScalaReflection` object whose
> eager `val universe` can't initialize against the Scala 3 stdlib. Stock Spark genuinely cannot run
> on Scala 3 today.

---

## 2. Scala 3 `enum` — simple (all-singleton)

```scala
enum Color { case Red, Green, Blue }
```

| | |
|---|---|
| **Spark** | **No encoder.** `ScalaReflection` knows only `scala.Enumeration#Value` (the Scala-2 enum); a Scala 3 `enum` is a different construct (sealed abstract class + singletons) the 2.13 type world can't reference. |
| **Ours** | `TransformingEncoder(clsTag, StringEncoder, valueOf-codec, nullable = true)`. |
| **Why this shape** | Faithful round-trip: a bare `StringEncoder` would deserialize to a `String`, losing the enum. The codec maps `Color ↔ "Red"` via `toString` / the companion's `valueOf`. The codec uses **Java** reflection (`Color$.MODULE$.valueOf`) — *not* the `scala.reflect.runtime` we eliminate — and runs only at ser/deser time. |
| **Impl** | `AgnosticEncoderBridge.scala`: `StringType` case detects `scala.reflect.Enum.isAssignableFrom(clsTag.runtimeClass)` → `scala3EnumEncoder`. `ProtoEncoder` side: `deriveEnum` → all-singleton → `makeEnumEncoder` (`StringType`). |
| **Validation** | Self-consistency: `canonical(bridge(Color)) == "Transforming[Color,StringEncoder]"`. No Spark golden possible. |

---

## 3. Scala 3 `enum` (data-carrying) / sealed-trait ADTs — sum types

```scala
enum Shape { case Circle(r: Double); case Square(s: Double) }   // or a sealed trait of case classes
```

| | |
|---|---|
| **Spark** | **No representation.** `AgnosticEncoder` has only `ProductEncoder` (struct) — there is *no sum-type encoder* in Spark's model at all. |
| **ProtoEncoder (own engine)** | **Does** encode them: `makeSumEncoder` → `ProtoType.SumType("_type", variants)` with a discriminated-union schema (`_type: String`, `_ordinal: Int`, plus per-variant data). This is a Scala-3 capability *beyond* Spark, usable by the project's own engine/serializers. |
| **Bridge → Spark** | **Clean rejection.** Lowering a `SumType` to `AgnosticEncoder` throws with: *"data-carrying Scala 3 enum / sealed-trait ADT — Spark's AgnosticEncoder has no sum-type representation (a Scala-3 capability Spark's encoder model would need to grow)."* |
| **Why reject (not coerce)** | Faking a sum type as a nullable struct-of-all-variants would be lossy/ambiguous. The honest signal is: this is a real gap *in Spark's model*, not in ours — a concrete item a Scala-3-ported Spark would add. |
| **Impl** | `AgnosticEncoderBridge.scala` `SumType` case (reject); `ProtoEncoder.makeSumEncoder` / `SumEncoder`. |
| **Validation** | Self-consistency: the bridge throws `IllegalArgumentException` containing `"sum-type"`. |

---

## 4. Collection / `Option` / `Map` of a case class (inline-given composition)

```scala
case class Deep(tags: Seq[Address], lookup: Map[String, Address], maybe: Option[Address])
```

| | |
|---|---|
| **Spark** | Handles `Seq[CaseClass]` etc. via runtime reflection on the element type. |
| **Ours (the subtlety)** | The element of a collection/option/map may be a *case class*, which has **no freely-summonable** `ProtoEncoder` given (case classes derive via `ProtoEncoder.derived`, not as a given). A plain `using ProtoEncoder[E]` can't reach derivation. The fix is a **Scala 3 metaprogramming** one: the collection/option/map givens are `inline given` that resolve the element via the inline `summonEncoder`, whose `Mirror.ProductOf → derived` branch composes the case class. |
| **Why Scala 3** | This "inline given that derives its type argument on demand" pattern is specific to Scala 3 inline metaprogramming; it replaces what Spark does with reflection. A blanket auto-derive given would collide with the tuple/collection givens, so the targeted `inline`-`summonEncoder` route is used. |
| **Impl** | `ProtoEncoder.scala`: `inline given seqEncoder/listEncoder/vectorEncoder/setEncoder/arrayEncoder/optionEncoder/mapEncoder`, constructing via public `makeOptionEncoder`/`makeCollectionEncoder`/`makeMapEncoder` factories (inline can't reference the private encoder classes at external call sites). |
| **Validation** | Faithful-subset parity (`Deep` test) — this one *does* have a Spark golden, since `Seq[CaseClass]` is in Spark's world. |

---

## 5. Types beyond Spark (superset, but not Scala-3-language-specific)

`ProtoEncoder` supports several types Spark's `encoderFor` outright rejects
(`cannotFindEncoderForTypeError`):

| Type | ProtoEncoder maps to | Spark | Bridge today |
|---|---|---|---|
| `java.util.UUID` | `StringType` | reject | **`TransformingEncoder` over String** (`uuid ↔ toString`/`fromString`) ✅ |
| `java.time.OffsetDateTime` | `TimestampType` | reject | **`TransformingEncoder` over String** (ISO-8601, lossless — preserves offset) ✅ |
| `java.time.ZonedDateTime` | `TimestampType` | reject | **`TransformingEncoder` over String** (ISO-8601, lossless — preserves zone id) ✅ |
| `java.util.Date` | `TimestampType` | reject | reject — could wrap `↔ Timestamp` (not yet bridged) |
| `java.time.LocalTime` | `TimeType(6)` | has `LocalTimeEncoder` in the ADT but **rejects `TimeType` at `ExpressionEncoder` build** (`[UNSUPPORTED_TIME_TYPE]`) | reject |

These are part of the superset (we encode more than Spark) but are **not** Scala-3 language features
— they'd be just as derivable in Scala 2.13. UUID/OffsetDateTime/ZonedDateTime are now bridged as
**String-backed `TransformingEncoder`s** that we *define* (no Spark oracle): the codec is a plain
`toString`/`parse` pair (no `scala.reflect.runtime`), and the String base makes the round-trip
lossless — a Timestamp base would collapse OffsetDateTime/ZonedDateTime to a UTC instant and drop the
offset/zone. Validated by **self-consistent end-to-end round-trips** through Spark's real ser/deser
(`ExecutionWallSpec`, enabled by the §2.1.1 patch) plus a structural shape assertion. `java.util.Date`
and `LocalTime` remain unbridged (the latter has no usable Spark encoder at all).

---

## Validation philosophy (recap)

- **Faithful subset** (Spark has an encoder): structural parity vs a Scala-2.13 golden generated by
  `ScalaReflection.encoderFor` (canonical class-name-normalized dump).
- **Superset** (Spark can't): **self-consistency** — assert the bridge produces the expected
  `AgnosticEncoder` shape (or rejects with the right message). No Spark golden can exist. With the
  §2.1.1 patch, the String-backed extensions are *also* validated by end-to-end round-trips through
  Spark's real ser/deser (`ExecutionWallSpec`), so "we defined it correctly" is observed, not just
  asserted structurally.

## Summary

| Behavior | Spark | Ours | Validation |
|---|---|---|---|
| Derivation engine | `TypeTag` + runtime reflection | `Mirror`/`inline`, no reflection | whole parity suite |
| Simple Scala 3 `enum` | none | `TransformingEncoder` over String (valueOf codec) | self-consistency |
| Data-carrying ADT / sum type | none | engine: discriminated union; bridge→Spark: reject | self-consistency |
| Collection/Option/Map of case class | reflection | `inline given` + `summonEncoder` | parity (has golden) |
| UUID / OffsetDateTime / ZonedDateTime | reject | **`TransformingEncoder` over String** (lossless codec) | self-consistency + e2e round-trip |
| java.util.Date / LocalTime | reject | reject (not bridged) | n/a |
