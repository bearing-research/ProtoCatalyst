# Migrating to Spark — a concrete checklist

A step-by-step for actually upstreaming this: *what file you take, where it goes, what lines you
change.* The argument for *why* is [REPORT §11/§11b](REPORT.md); this is the operational version.

Module/package facts below are verified against the Spark 4.1.2 jars this repo builds on (`AgnosticEncoder`
and `ScalaReflection` are in `sql-api`; `ExpressionEncoder` is in `catalyst`). Exact source-directory
conventions for a Scala-3 build are Spark's to decide; everything else is concrete.

> **Prerequisite.** This is the *encoder-derivation* piece of a Scala-3 Spark, not a whole Scala-3 port.
> It assumes Spark is being cross-built for Scala 3 (the broader effort); encoders are *the* structural
> derivation blocker, and this is the piece that unblocks it.

## What you take — and what you don't

- **Take: one file** —
  [`encoder-spark/…/AgnosticDerivation.scala`](../../encoder-spark/src/main/scala/protocatalyst/encoder/spark/AgnosticDerivation.scala).
  Its entire dependency surface is Spark's own encoder model (`AgnosticEncoder`, `AgnosticEncoders.*`,
  `Codec`, `DecimalType`, `Metadata`) plus the Scala standard library
  (`scala.compiletime`/`deriving.Mirror`/`reflect.ClassTag`). **Zero `protocatalyst` types.**
- **Do *not* take:** `ProtoEncoder`/`ProtoType`, `AgnosticEncoderBridge`, `SparkTypeMapping` (the repo's
  engine-independent IR + its bridge — they exist only because the IR also targets non-Spark backends),
  nor the per-row serializers (`UnsafeRowSerializer`, the `arrow/*` ser/deser — separate work, REPORT §10).

---

## Step 1 — drop the file into `sql-api`

Place `AgnosticDerivation.scala` in the **`sql-api`** module (where `AgnosticEncoder` already lives),
package **`org.apache.spark.sql.catalyst.encoders`**, as a **Scala-3-only** source. The *only* edit to
the file is its package line:

```diff
- package protocatalyst.encoder.spark
+ package org.apache.spark.sql.catalyst.encoders
```

That this file compiles as a member of `org.apache.spark.sql.catalyst.encoders` is checked on every
build of this repo (the `encoderSpark` `sourceGenerator` in `build.sbt` does exactly this rename and
compiles the result against the real `spark-catalyst` jar). Its entry point:

```scala
inline def deriveAgnosticEncoder[T](using m: Mirror.Of[T]): AgnosticEncoder[T]
```

## Step 2 — call it from `ExpressionEncoder.apply[T]()` (`catalyst`)

Today `ExpressionEncoder.apply[T: TypeTag]()` builds the encoder via `ScalaReflection.encoderFor[T]`.
On the Scala 3 build, call the derivation instead — through the existing no-`TypeTag` overload
`ExpressionEncoder.apply(enc: AgnosticEncoder[T])`, which is the seam:

```scala
// Scala 3 build:
def apply[T](using Mirror.Of[T]): ExpressionEncoder[T] =
  ExpressionEncoder(deriveAgnosticEncoder[T])     // was: ExpressionEncoder(ScalaReflection.encoderFor[T])
// Scala 2.13 build: unchanged (ScalaReflection.encoderFor).
```

Same return type, same downstream (`Serializer`/`DeserializerBuildHelper`, whole-stage codegen, Spark
Connect) — they already consume `AgnosticEncoder`.

## Step 3 — de-reflect `ScalaReflection` (`sql-api`) — the down-payment PR

This is the small, self-contained change that fixes the initialization crash
([scala/scala3#25896](https://github.com/scala/scala3/issues/25896)); it can land on its own, ahead of
everything else. The reference diff is
[`spark-reflection-patch/…/ScalaReflection.scala`](../../spark-reflection-patch/src/main/scala/org/apache/spark/sql/catalyst/ScalaReflection.scala)
— a verbatim copy of Spark's `ScalaReflection` with the changes marked `PROTOCATALYST PATCH`:

1. **`val universe` → `lazy val universe`.** The object's `<clinit>` no longer forces
   `scala.reflect.runtime.universe`, so merely *touching* `ScalaReflection` (which the serializer codegen
   does, via `Invoke.encodedFunctionName`) no longer crashes on Scala 3.8+.
2. **`encodeFieldNameToIdentifier(name)` → `scala.reflect.NameTransformer.encode(name)`** — identical
   identifier mangling, from scala-library, with no runtime reflection.
3. **`findConstructor`'s scala-reflect fallback.** The primary path is already Java reflection
   (`ConstructorUtils.getMatchingAccessibleConstructor`); only the `None` branch
   (`mirror.staticClass(...).companion …`) uses the runtime universe. Drop it, or replace with a
   Java-reflection companion-`apply` lookup.

After (1)–(2) the object loads on Scala 3 even before the derivation is wired (the in-repo
`ExecutionWallSpec` runs Spark's unmodified ser/deser codegen from Scala 3 with exactly these two
changes).

## Step 4 — drop the `~16` `TypeTag` bounds (`sql-api` / `catalyst` / `sql-core`)

`TypeTag` is a scala-reflect construct that does not exist on Scala 3, so the public encoder-producing
signatures that carry `[T: TypeTag]` must change *regardless of how encoders are derived* — this is
Scala-3-inherent, not added by Steps 1–2. Enumerate them in Spark's tree:

```bash
grep -rn "TypeTag" sql/api sql/catalyst sql/core | grep -i "encoder\|product\|Encoders\|implicits"
```

They are roughly: `Encoders.*`, `SparkSession.implicits`/`SQLImplicits`, `ExpressionEncoder.apply`, and
the `Dataset`/`functions`/`Aggregator`/`KeyValueGroupedDataset` methods that thread one. Replace
`[T: TypeTag]` with the derived form — either per-version source directories, or by moving the bound to
`[T: Encoder]` uniformly so the call sites stay single-source (the version-specific code then lives only
in how the `Encoder` is materialized). See [REPORT §11](REPORT.md) for that design lever. The end-user
`Dataset` usage surface (`spark.createDataset`, `ds.map`, `as[T]`) is unchanged.

## Step 5 — validate against Spark's own tests

On the Scala 3 build:

```bash
build/sbt "catalyst/testOnly *Encoder*"   # Spark's existing encoder suite
# … plus the typed-Dataset suite — the definitive proof Step 3's wall was the last derivation obstacle.
```

These are Spark's *own* tests; passing them is the bar. (In this repo the same property is checked by
`AgnosticDerivationSpec` — the derived encoder is structurally identical to goldens generated from
Spark's reflective `ScalaReflection.encoderFor` — and `ExecutionWallSpec` — those encoders round-trip
real values through Spark's unmodified serializer/deserializer codegen.)

---

## At a glance

| Change | Spark module | Size |
|---|---|---|
| Add `AgnosticDerivation.scala` (rename package only) | `sql-api` | one file (~210 lines) |
| Wire `ExpressionEncoder.apply[T]()` → `deriveAgnosticEncoder[T]` (Scala 3) | `catalyst` | a few lines |
| De-reflect `ScalaReflection` (the #25896 down-payment) | `sql-api` | ~3 lines |
| Drop the `~16` `TypeTag` bounds (forced by Scala 3) | `sql-api` / `catalyst` / `sql-core` | enumerable |

The **derivation + de-reflection — the part unique to this proposal — is localized to `sql-api`.** The
`TypeTag` spread is the part Scala 3 forces regardless.
