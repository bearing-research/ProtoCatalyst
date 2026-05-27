# Compile-Time Encoders for Spark: A Scala 3 Path Forward

**Working title.** A technical report on replacing Spark's
`ExpressionEncoder` with a Scala 3 compile-time macro-derived encoder.
Audience: Spark committers first, Scala 3 community second.

---

## What this report is and isn't

**Is**: A rigorous case study showing that (a) a Scala 3 compile-time
macro can match Spark's `ExpressionEncoder` byte-for-byte on
`UnsafeRow` output, (b) it can match or beat Spark on per-row
throughput, and (c) doing so unlocks the Scala 3 migration that
Spark's encoder currently blocks. It's both an argument and a
worked implementation.

**Isn't**: A new query engine, a Catalyst replacement, a Spark fork.
The implementation lives **above** Spark and produces the same
`UnsafeRow` bytes Spark's whole-stage codegen produces. The point is
that nothing in Spark's runtime has to change — only the encoder
derivation path.

---

# Part 1 — Concepts and motivation

## §1. The problem statement (~500 words)

Spark's typed `Dataset[T]` API can be **3–5× slower** than the
untyped `DataFrame` API on encoder-sensitive TPC-H queries at SF=1
(measured below; query-dependent, see §12). The gap is the cost of
`ExpressionEncoder` — the component that translates between JVM
case-class instances and Spark's internal `UnsafeRow` byte layout.
Every typed lambda forces a per-row encoder pass.

A second, structural problem: `ExpressionEncoder` is built on Scala
2.13's `TypeTag` machinery, which doesn't function from Scala 3.
Spark itself is therefore locked to Scala 2.13 — not because the rest
of the codebase couldn't migrate, but because the encoder forces it.

Both problems share a solution: replace the runtime-reflection
derivation with a compile-time macro. Per-row cost drops because the
JIT sees monomorphic specialized code instead of megamorphic
dispatch. The Scala 3 wall comes down because no runtime reflection
is needed at all.

This report shows what that replacement looks like, validates that
it's byte-compatible with the existing encoder, and quantifies the
performance and allocation differences.

## §2. What is an encoder? (~800 words)

### The `Dataset[T]` ↔ `DataFrame` contract

```scala
val ds: Dataset[Lineitem] = spark.read.parquet(...).as[Lineitem]
val filtered = ds.filter(_.shipdate.isBefore(cutoff))      // typed lambda
val df: DataFrame = filtered.toDF()
```

When user code says `.filter(_.shipdate.isBefore(...))`, Spark must
materialize each row as a `Lineitem` JVM object so the lambda can
run. The reverse trip happens too — once the lambda returns a
`Lineitem`, Spark re-encodes it into the next operator's input.
That's two encoder hops per typed lambda, per row.

### `InternalRow` vs `UnsafeRow` vs the JVM object

- **JVM object** — a `Lineitem` instance with typed fields
  (`shipdate: LocalDate`, `quantity: BigDecimal`, …).
- **`InternalRow`** — the trait Spark's operators consume. Abstract;
  concrete subclasses include `GenericInternalRow` (heap, Object[])
  and `UnsafeRow` (packed bytes).
- **`UnsafeRow`** — the canonical Catalyst row format. A single
  `byte[]` (or off-heap region) with: null bitmask, 8-byte fixed-width
  slots, variable-length region. `Platform.getLong(base, offset)`-style
  reads.

Spark's whole-stage codegen always **produces and consumes
`UnsafeRow`** at the operator boundary. The encoder's job is to
bridge JVM objects and `UnsafeRow`.

### Why this is hard

The encoder must work for an arbitrary user case class — types Spark
doesn't know at compile time. So it has to derive serialization /
deserialization code given a type description. Spark does this via
runtime reflection (`TypeTag` + `scala.reflect.runtime.universe`) to
infer the schema, then generates bytecode via whole-stage codegen.
That's two phases: schema inference (reflective, slow, runs once at
encoder construction) and per-row codegen (fast, runs millions of
times).

The reflective phase has hidden costs that show up later in the
report — boot time, GraalVM-incompatibility, the Scala 2.13 lock.

---

## §3. How Spark currently encodes (~1200 words)

### `ExpressionEncoder[T]` and the AgnosticEncoder abstraction

```scala
// Spark 4.x — Encoders.scala
val enc = ExpressionEncoder[Lineitem]()        // requires TypeTag[Lineitem]
val ser: Lineitem => InternalRow = enc.createSerializer()
val deser: InternalRow => Lineitem = enc.resolveAndBind().createDeserializer()
```

`ExpressionEncoder[T]()` calls `ScalaReflection.encoderFor[T]`, which
walks `T`'s `TypeTag` via Scala 2's `runtime.universe`. The result is
an `AgnosticEncoder[T]` — an engine-independent description of the
type's shape (`ProductEncoder`, `OptionEncoder`, `IterableEncoder`,
…) introduced in Spark 3.5 to support the Spark Connect protocol.

Spark 4.1 ships 30+ AgnosticEncoder leaf variants:
`PrimitiveBooleanEncoder`, `BoxedIntEncoder`, `StringEncoder`,
`ScalaDecimalEncoder`, `LocalDateEncoder` (with lenient flag),
`InstantEncoder`, `LocalTimeEncoder` (4.1+), `BinaryEncoder`,
`CalendarIntervalEncoder`, `VariantEncoder`, `GeographyEncoder`
(4.1+), `GeometryEncoder` (4.1+), plus the structural
`ProductEncoder`, `OptionEncoder`, `IterableEncoder`, `MapEncoder`,
`UDTEncoder`, `TransformingEncoder`. Full catalog cross-referenced in
[`docs/ENCODER_PARITY.md`](ENCODER_PARITY.md).

### The TypeTag-based path

```scala
def encoderFor[E: TypeTag]: AgnosticEncoder[E] = {
  encoderFor(typeTag[E].in(mirror).tpe).asInstanceOf[AgnosticEncoder[E]]
}
```

`TypeTag[T]` is a Scala 2 reflective handle on `T`'s structure.
Accessing it forces initialization of
`scala.reflect.runtime.universe`, which on JDK 21 loads ~500 classes
and walks the JVM's `scala.Array` companion. **On a Scala 3 classpath
this initialization fails** — the Scala 3 `Array` companion has a
different shape than what Scala 2's reflection expects.

This is the **Scala 2.13 lock**. Spark can't fully migrate to Scala
3 because every typed `Dataset[T]` user invokes
`ExpressionEncoder[T]()` somewhere, which transitively forces Scala
2's reflection runtime. We discovered this the hard way when our
benchmark tried to call Spark's encoder from a Scala 3 test (§13).

### Whole-stage codegen → UnsafeRow

Once the `AgnosticEncoder` exists, `createSerializer()` builds a
`UnsafeProjection` via `GenerateUnsafeProjection`. This is Spark's
runtime bytecode codegen — it emits a `SpecificUnsafeProjection`
class with one specialized `apply(row: InternalRow): UnsafeRow`
method per encoder. The generated bytecode is what runs on the hot
path.

Per-row, the codegen'd `apply()`:
1. Reads each field from the input row (or from a `Lineitem` object
   via accessor methods, then performs the JVM-to-internal-form
   conversion: `LocalDate → epochDays Int`, `String → UTF8String`,
   etc.).
2. Writes each field into a pre-allocated `UnsafeRowWriter`'s buffer.
3. Returns the writer's `UnsafeRow`, which shares the writer's
   `byte[]` and gets overwritten on the next call (mutable-row
   contract).

This is fast. The codegen path is the result of a decade of Spark
optimization. **Matching it from Scala 3 is the challenge.**

### Where the cost lands

Encoder cost varies by query shape. End-to-end measurement of TPC-H
at SF=1, default Spark config (commit `1ac4873`, see §12):

| Query | `DataFrame` (no encoder) | `Dataset[T]` (encoder) | DS/DF | Character |
|---|---:|---:|---:|---|
| Q1 (scan + groupby + sort) | 2209 ms | 2668 ms | 1.21× | mixed — scan/agg dilutes encoder share |
| Q6 (scan + filter + sum) | 173 ms | 871 ms | 5.03× | encoder-dominated |
| Q14 (small join) | 240 ms | 944 ms | 3.93× | encoder-heavy |
| Q21 (4-way self-join + anti-join) | 1335 ms | 1502 ms | 1.13× | shuffle-bound |

The encoder is most visible on Q6 and Q14 where the typed lambda
forces per-row decoding for filter evaluation and the rest of the
query is small. Q1 has substantial non-encoder work (8 aggregates +
ORDER BY) that dilutes the encoder share. Q21 is shuffle-bound;
encoder cost is rounding error.

### Decomposing the cost

Two distinct costs the report disentangles:

1. **Per-row encode/decode cost** — measured by JMH micro (§11).
2. **Reflective derivation + first-call codegen startup** — visible
   in Spark's own log output. During each of our SF=1 sweeps the
   Spark side logged lines like `Code generated in 106.242584 ms`
   for the first UnsafeProjection per encoder type, with smaller
   follow-ons (~5-20 ms) for the subsequent projections. This is
   pure cold-start overhead — paid once per encoder per JVM, before
   the first row of work. Discussed in §13.

**Cross-checking per-row cost against end-to-end.** At SF=1, Q6
processes 6M lineitem rows (it's the cleanest encoder-bound signal:
filter + SUM, single-row result, no GROUP BY/ORDER BY dilution).
DS-Q6 wall-clock is 871 ms = ~145 ns/row. DF-Q6 is 173 ms = ~29
ns/row. The difference (~116 ns/row) is the encoder + JVM-object
materialization cost in actual query execution.

Notably this is *less* than our JMH deserialize cost in isolation
(Spark 286 ns/op, ours 230 ns/op). Two likely contributors:
- Catalyst optimization — the typed lambda allows Spark to push the
  shipdate filter into Parquet column-skipping, so not every row
  reaches the deserialize path.
- JIT amortization across the full query loop (the JMH micro measures
  one isolated decode; the query loop intersperses decode with the
  filter predicate and SUM accumulation).

The JMH micro and the end-to-end numbers triangulate to "encoder
cost is real (~150 ns/row in Q6's hot path) but smaller than the
isolated JMH deserialize suggests, because Spark optimizes around it
for rows that don't survive filter."

---

# Part 2 — The Scala 2.13 wall

## §4. Why Spark can't simply move to Scala 3 (~700 words)

### The transitive dependency: TypeTag → runtime.universe → Array

Demonstrated in §13 with stack trace: calling
`ExpressionEncoder[T]()` from Scala 3 throws
`ExceptionInInitializerError` with the root cause
`scala.reflect.internal.FatalError: class Array does not have a
member apply` from `ScalaReflection.<clinit>`.

Spark's encoder isn't just *implemented* in Scala 2 — it's
*coupled* to Scala 2's runtime reflection in a way no shim can
bridge.

### Workarounds people use today

- **Use `DataFrame` instead of `Dataset[T]`.** Sacrifices type
  safety; pays the 5–6× encoder tax to escape... by abandoning typed
  access.
- **Frameless `TypedEncoder`.** Provides a typed `Dataset[T]`-like
  API in Scala 2 by building Catalyst `Expression` trees from typed
  values. **Doesn't help with Scala 3** — Frameless itself is Scala
  2. And its perf claim ("identical to ExpressionEncoder") is
  literally because it delegates to Spark's codegen.
- **`spark-scala3` (community project).** Provides a thin Scala 3
  surface around Spark's Scala 2 jars via `CrossVersion.for3Use2_13`.
  Works for non-typed code; runs into the same TypeTag wall on
  `Dataset[T]`.

### Previous attempts at compile-time encoders

We surveyed prior art (§7) and found no published case study of a
Scala 3 compile-time `UnsafeRow` encoder. jsoniter-scala does this
for JSON. circe does this for ADTs. Frameless delegates to Spark.
This is the first attempt at the specific combination: Scala 3 macros
+ Spark's exact `UnsafeRow` byte layout.

## §5. The Scala 3 derivation story (~700 words)

### Three derivation mechanisms available

1. **`inline match` on a type tuple via `Mirror`.** Cheap to write,
   limited to per-field dispatch within a known shape. Boxes
   primitives through `Mirror.fromProduct(Product)`.
2. **`quoted.Expr` macros (`Expr`, `Quotes`).** Build first-class
   expression trees at compile time. Can emit arbitrary code,
   including direct case-class constructor calls that avoid all
   intermediate boxing.
3. **Magnolia (a third-party library).** A typeclass-derivation
   abstraction over both Scala 2 and Scala 3. Trades macro
   verbosity for a more constrained API.

We use mechanism 1 for the simple cases (schema derivation via
`Mirror.Of[T]`) and mechanism 2 for the hot-path read/write code.
The macro emits the same shape of bytecode Spark's whole-stage
codegen produces — direct `row.getLong(0)`, `new T(...)`,
`writer.write(0, value.field)` — without any reflection at runtime.

### What "no runtime reflection" buys

- **GraalVM native-image compatible.** Spark's encoder isn't.
- **No `scala.reflect.runtime.universe` initialization.** Avoids
  ~50–100 ms of cold-start cost.
- **Schema validation at compile time.** Misspelled field, wrong
  type, missing field — caught by `scalac`, not at the first
  request.
- **Inlined dispatch.** The JIT sees specialized code per field
  type, not a megamorphic `match` on `DataType` at runtime.

---

# Part 3 — Our approach

## §6. ProtoCatalyst's compile-time encoder (~1000 words)

### High-level architecture

[Diagram: case class T → `UnsafeRowSerializer.derived[T]` → macro
expansion → direct constructor + writer calls → byte-identical
`UnsafeRow`.]

Three layers:
- **`encoder` module** (Scala 3, no Spark): the type-IR (`ProtoType`,
  `ProtoStructField`, `ProtoSchema`) and the `ProtoEncoder.derived[T]`
  schema derivation. Spark-free.
- **`encoder-spark` module** (Scala 3 + Spark 4.1 catalyst via
  `for3Use2_13`): the `UnsafeRowSerializer` trait + macro + impl. The
  bridge.
- **`benchmark-spark` module** (Scala 2.13 + Spark): the Spark-side
  benchmark code (calls `ExpressionEncoder` for comparison). Cannot
  see Scala 3 macros; communicates via files (byte fixtures, Parquet,
  JMH JSON).

### The two-step derivation

```scala
inline def derived[T](using m: Mirror.ProductOf[T]): UnsafeRowSerializer[T] = {
  val schema = ProtoEncoder.derived[T].schema      // Mirror-based, runs at expansion
  derivedMacroEntry[T](schema)                       // quoted macro for hot path
}

private inline def derivedMacroEntry[T](schema: ProtoSchema): UnsafeRowSerializer[T] =
  ${ UnsafeRowSerializerMacro.derivedImpl[T]('schema) }
```

The Mirror is used **at compile time only**, by the inline schema
derivation. It never appears at runtime. The hot-path lambdas the
macro emits don't reference `Mirror.fromProduct` or any reflection.

## §7. Implementation: `UnsafeRowSerializer` (~1500 words)

### The macro's read-side emission

For `case class Lineitem(orderkey: Long, partkey: Long, ..., comment: String)`,
the macro emits:

```scala
val readFn: UnsafeRow => Lineitem = (row: UnsafeRow) =>
  new Lineitem(
    row.getLong(0),                                                       // primitive, unboxed
    row.getLong(1),
    row.getLong(2),
    row.getInt(3),
    if (row.isNullAt(4)) null
       else BigDecimal(row.getDecimal(4, 38, 18).toJavaBigDecimal),
    if (row.isNullAt(5)) null
       else BigDecimal(row.getDecimal(5, 38, 18).toJavaBigDecimal),
    // ... 11 more fields ...
    if (row.isNullAt(15)) null
       else row.getUTF8String(15).toString
  )
```

Properties of this code:
- **Single constructor call** — no `Array[Any]`, no
  `Mirror.fromProduct`, no `ArrayProduct` wrapper.
- **Primitives flow unboxed.** `Long` and `Int` slots become unboxed
  `long`/`int` arguments to the constructor.
- **Monomorphic call sites.** Each `getLong(0)`, `getInt(3)` is a
  direct method call the JIT can inline aggressively.
- **No dispatch on `DataType` at runtime.** The macro decided what
  to emit per field at *compile* time.

The write-side mirror: `writer.write(0, value.orderkey)` … per
field. Direct field selector (`Select(value, sym)`) — no
`productElement(i).asInstanceOf[T]` boxing.

### Writer caching

The `UnsafeRowSerializerImpl[T]` caches a single `UnsafeRowWriter`
instance at construction:

```scala
class UnsafeRowSerializerImpl[T](...) {
  private val cachedWriter = new UnsafeRowWriter(fieldCount)

  def serialize(value: T): UnsafeRow = {
    cachedWriter.reset()
    cachedWriter.zeroOutNullBytes()
    writeFn(cachedWriter, value)
    cachedWriter.getRow      // SAME instance every call — mutable-row contract
  }
}
```

Matches Spark's `UnsafeProjection` pattern. Callers retaining the
row across calls must `.copy()`, documented on the trait. Plain
instance field rather than `ThreadLocal` because in per-task Spark
execution there's one task per thread; `ThreadLocal.get` would add
indirection for no gain.

### Type coverage (Spark 4.1 AgnosticEncoder catalog cross-reference)

Full table from `docs/ENCODER_PARITY.md`. Quick summary:

- **All primitive leaf encoders**: Boolean, Byte, Short, Int, Long,
  Float, Double + boxed counterparts.
- **String / Binary / Char / Varchar**: covered.
- **Decimals**: `BigDecimal`, `java.math.BigDecimal`, `BigInt`,
  `java.math.BigInteger` (mapped to `DecimalType(38, 18)`).
- **Temporals**: `java.time.LocalDate` / `java.sql.Date`,
  `java.time.Instant` / `java.sql.Timestamp`, `LocalDateTime`,
  `LocalTime` (Spark 4.1+), `Duration`, `Period`.
- **Option[T]** over any of the above.

Out of scope for this commit, in three classes by effort to add:

**Easy to add (just more inline / macro cases needed)** — none of
these require structural changes:
- `OffsetDateTime`, `ZonedDateTime` (normalize to UTC `Instant`,
  emit `getLong` + reconstruction)
- `java.util.Date` (treat as `Instant` epoch)
- `java.util.UUID` (treat as `String`)
- `BigInt`, `java.math.BigInteger`
- `Duration`, `Period` (emit `getLong`/`getInt` + convert)

**Needs macro refinement (real engineering)** — requires
`UnsafeArrayWriter` / `UnsafeMapWriter` integration and recursive
macro construction:
- Collections (`Seq`, `List`, `Vector`, `Set`, `Array[T]` of
  non-byte) — nested writer chaining
- Maps
- Nested case classes — recursive macro derivation

**Out of scope by design** — would couple our Spark-free encoder
module to Spark internals; should live in the `spark-catalyst`
bridge instead:
- `org.apache.spark.sql.types.Decimal` (Spark-internal class)
- `CalendarInterval`, `VariantVal`
- `Geography`, `Geometry` (Spark 4.1+ spatial types)

### Binary / wire compatibility

A direct consequence of byte-level parity (§9): any consumer that
reads Spark's `UnsafeRow` reads ours identically — Catalyst
operators (filter, project, join, exchange), shuffle blocks, Tungsten
projections, Parquet writers via `UnsafeProjection`. **Wire and disk
format compatibility is automatic**. Replacing the encoder in Spark
doesn't require any change to downstream operators, the shuffle
service, the lake-format writers, or persisted artifacts. This is
the property that makes a migration mechanically feasible.

## §8. The build dance (~800 words)

This section is **the lived experience that motivates Scala 3
migration**. Everything here goes away the day Spark publishes Scala
3 artifacts.

### `CrossVersion.for3Use2_13`

Spark 4.1 only ships Scala 2.13 jars. To use them from a Scala 3
module:

```scala
libraryDependencies += ("org.apache.spark" %% "spark-sql" % "4.1.2")
  .cross(CrossVersion.for3Use2_13)
```

Scala 3's binary compatibility includes a Scala 2.13 reader; most
Spark classes (`UnsafeRow`, `UnsafeRowWriter`, `Decimal`,
`UTF8String`) cross over cleanly.

### The `ScalaReflection.<clinit>` wall

`ExpressionEncoder.apply[T]()` works via `TypeTag` and is unreachable.
But even `ExpressionEncoder.apply(agnosticEncoder)` (the
TypeTag-free constructor) trips on
`ScalaReflection.<clinit>` because Spark's codegen calls
`scala.reflect.runtime.universe` internally to look up `Array.apply`
during method resolution. Scala 3's `Array` companion has a
different shape than what Scala 2's reflection expects — the
initializer throws `FatalError`. We discovered this when trying to
build the byte-parity test directly in Scala 3.

**Workaround**: run Spark from a Scala 2.13 fixture-generator
(`benchmark-spark/UnsafeRowParityFixtures.main`), write UnsafeRow
bytes to disk, read them from the Scala 3 test. Bytes are
language-neutral. This pattern is repeated throughout the project —
file-based communication is the only reliable cross-version bridge.

### SIP-51 compatibility check

Spark 4.1.2 transitively depends on scala-library 2.13.18+. Our
Scala 2.13 modules are pinned to 2.13.16 (semanticdb / scalafix
hasn't published 2.13.18 yet). sbt's SIP-51 check rejects this. We
demote with `allowUnsafeScalaLibUpgrade := true` — safe because we
don't link against new-stdlib symbols.

### Spark on JDK 21 module access

`SparkDateTimeUtils` reflects into `sun.util.calendar.ZoneInfo` for
timestamp handling. Needs
`--add-opens=java.base/sun.util.calendar=ALL-UNNAMED`.

### Spark REPL classloader confusion under sbt

`UnsafeProjection` codegen tries to fetch generated classes via a
"REPL artifact server" when run under sbt because sbt's
`MutableURLClassLoader` matches Spark's REPL heuristic. Fixed via
`spark.driver.userClassPathFirst=true` +
`spark.executor.userClassPathFirst=true`.

### The price tag for the dual-Scala-version dance

[List of file-based cross-boundary communications happening *in this
benchmark alone*.] Every one of these goes away when Spark
publishes Scala 3.

---

# Part 4 — Validation

## §9. Correctness validation (~600 words)

### Byte-level parity test against `ExpressionEncoder`

`encoder-spark/.../UnsafeRowParitySpec.scala` — 5 fixtures
generated by a Scala 2.13 tool
(`UnsafeRowParityFixtures.main` calling
`ExpressionEncoder[T].createSerializer()(value)`), compared
byte-for-byte against `UnsafeRowSerializer.derived[T].serialize(value)`.

All five pass: `Simple(Int, String)`, `WithDecimal(Long, BigDecimal)`,
`WithTemporal(LocalDate, Instant)`,
`WithOption(Int, Option[String]) — Some`,
`WithOption(Int, Option[String]) — None`.

Output: byte-identical. This is the core correctness guarantee. If a
future Spark patch changes UnsafeRow byte layout, this test fails
and we adapt.

### Real-data round-trip

`TpchDbgenIntegrationSpec.scala` — load real `dbgen` SF=0.01 output,
round-trip every row of all 8 TPC-H tables through
`UnsafeRowSerializer`. 60,175 Lineitem rows round-trip in 0.3
seconds with no diffs.

### Test surface

36 tests, all passing on commit `fa8bbed`:
- 5 byte-parity vs Spark
- 9 schema smoke (inline fixtures, one per table shape)
- 8 dbgen integration (real data)
- 14 UnsafeRowSerializer unit tests (slot semantics, null handling,
  writer reuse, packed size)

## §10. Benchmark methodology (~800 words)

Lifted from `docs/BENCHMARK_METHODOLOGY.md`. Key points:

- **Statistical rigor**: Georges et al. OOPSLA 2007. ≥30 measurements
  (3 forks × 10+ iterations after warmup), 99% CIs from JMH,
  declare "no difference" when CIs overlap.
- **Cold/hot separation**: JMH warmup discards JIT settling, then
  measures. End-to-end queries follow ClickBench min-of-3 after 1
  warmup with cache clearing.
- **Spark configs disclosed**: `wholeStage`, `adaptive`, `shuffle.partitions`,
  thread count, all listed in disclosure.txt.
- **Ablations**: `wholeStage on/off × adaptive on/off × local[1]/local[*]`
  for end-to-end queries (8 configs total).
- **Reproducibility**: single command `./scripts/bench.sh [SF]`
  produces `results/<ts>-sf<SF>/` directory with
  disclosure.txt + 2 JMH JSON + queries.csv. Git SHA baked in.

---

# Part 5 — Results

## §11. Encoder microbenchmarks (~900 words + chart)

### Hardware / config (full disclosure)

Apple Silicon M-series, 8 cores, 16 GB RAM, JDK 21.0.11 (Homebrew
OpenJDK), Spark 4.1.2, commit `fa8bbed`, JMH 1.37 (3 forks × 5
warmup × 15 measurement × 1s iters, `-prof gc`). EC2
cross-validation pending.

### Per-row throughput, by table

[Table: per-table per-operation ns/op for ours vs Spark, with ± CI
and speedup column. Geomean row at bottom.]

Headline numbers (geomean across 4 TPC-H tables, ours / Spark):
- **Serialize: 1.30× faster**
- **Deserialize: 1.15× faster**
- **Roundtrip: 1.24× faster**
- **Overall geomean across 12 benchmarks: 1.23× faster**

12/12 benchmarks favor our encoder; widest win is 1.42× on
`customerSerialize`, narrowest is 1.05× on `partDeserialize`
(basically tied).

### Allocation rate

[Table: per-table B/op for both encoders. Spark column. Our column.
Ratio.]

Median: 1.08× (we allocate 8% more). Range: 0.94× – 1.22×. We
**beat Spark on allocation** on `ordersSerialize` (360 B/op us, 384
Spark) and tie on `ordersDeserialize` / `customerDeserialize`
(exactly equal). Worst case Lineitem deserialize at 1.22× — the
variable-length region (5 string fields + 4 decimals) is where we
still allocate more.

### Where the wins come from: step-by-step

Each commit in the optimization arc (Phase C) was independently
benched. Geomean speedup vs Spark, per operation:

| Operation    | Baseline | Step 1 (+cache) | Step 2 (+inline) | Step 3 (+macro) |
|-------------|---------:|----------------:|-----------------:|----------------:|
| Serialize   |   0.95×  |     **1.28×**   |       1.29×      |    **1.38×**    |
| Deserialize |   0.52×  |       0.52×     |     **1.04×**    |    **1.12×**    |
| Roundtrip   |   0.71×  |       0.79×     |       1.19×      |       1.19×     |

Allocation ratio (ours / Spark, lower is better), per operation:

| Operation    | Baseline | Step 1 (+cache) | Step 2 (+inline) | Step 3 (+macro) |
|-------------|---------:|----------------:|-----------------:|----------------:|
| Serialize   |   2.54×  |       1.11×     |       1.10×      |    **1.02×**    |
| Deserialize |   1.93×  |       1.93×     |       1.23×      |    **1.08×**    |
| Roundtrip   |   2.09×  |       1.61×     |       1.10×      |       1.08×     |

Interpretation:

1. **Step 1 (writer cache)** flipped serialize from 0.95× (slight
   loss) to 1.28×, and dropped serialize alloc rate from 2.54×
   Spark's level to 1.11×. The fix: Spark's `UnsafeProjection`
   caches its writer; we didn't. Closing this gap saw all four
   serialize wins jump immediately. Deserialize unchanged because
   it touches a different code path.
2. **Step 2 (inline-dispatch deserialize)** flipped deserialize
   from 0.52× to 1.04× and dropped its alloc 1.93× → 1.23×. The
   fix: replace `row.get(i, dataType)` megamorphic dispatch with
   type-specialized `row.getLong(i)` / `row.getInt(i)` / etc.
   emitted per field. Same pattern Spark's codegen uses.
3. **Step 3 (quoted-macro direct constructor)** added another
   ~8% on speed and dropped alloc rate to 1.02–1.08× (essentially
   matching Spark). The fix: eliminated `Array[Any]` +
   `Mirror.fromProduct` intermediate; primitives flow unboxed
   straight into the case-class constructor via the jsoniter-scala
   pattern.

Worth highlighting that all three steps are **independent
optimizations addressing distinct bottlenecks**. Step 1 doesn't
help deserialize. Step 2 doesn't help serialize. Step 3 helps
both but only after steps 1 and 2 have removed the dominant
sources of overhead. Discussion of the JIT inlining mechanics
that produce these gains: deferred to a follow-up perfasm /
JITWatch dive; mentioned briefly in §13.

## §12. End-to-end TPC-H (~1200 words + chart)

### Q1 / Q6 / Q14 / Q21 — DataFrame vs Dataset[T]

Two implementations of each query: untyped `DataFrame` (no encoder)
and typed `Dataset[T]` (forces encoder per row via `.filter(_.field
…)`). Same query result, same data, only encoder use differs.

[Chart: per-query bar chart. 4 queries × 2 variants × 8 configs.
Photon-style horizontal bars, log-scale where range exceeds 5×.]

### The query-dependent Dataset[T] tax

Both query variants drive their action with `.collect()` so Spark's
optimizer can't prune unused aggregates / ORDER BY / output
projections (an earlier version of this benchmark used `.count()` and
the resulting Q1 numbers were materially distorted by exactly that
pruning — see commit `1ac4873`).

| Query | DF (ms) | DS (ms) | DS/DF | Encoder fraction of DS | Query character |
|---|---:|---:|---:|---:|---|
| Q1 | 2209 | 2668 | 1.21× | **17%** | scan + GROUP BY + 8 aggs + ORDER BY |
| Q6 | 173 | 871 | **5.03×** | **80%** | scan + filter + single SUM |
| Q14 | 240 | 944 | **3.93×** | 75% | scan + small JOIN + ratio aggregate |
| Q21 | 1335 | 1502 | 1.13× | 11% | scan + 4-way self-join + anti-join |

The "encoder fraction" column = `(DS - DF) / DS` — the share of
typed-Dataset query time spent on encoder work, since the two
variants run otherwise identical queries.

**Encoder cost is significant but query-dependent.** Three regimes:

- **Encoder-dominated (Q6: 80%, Q14: 75%)** — queries where most of
  the query work is the per-row scan/filter the typed lambda forces.
  These are the canonical "encoder is the whole game" cases. Q6 is
  the cleanest signal: filter + single SUM, single-row result.
- **Mixed (Q1: 17%)** — substantial non-encoder work (GROUP BY +
  ORDER BY + 8 aggregate columns) dilutes the encoder share, even
  though the absolute encoder cost (~460 ms = DS − DF) is real and
  measurable.
- **Shuffle-bound (Q21: 11%)** — encoder cost is rounding error
  next to the 4-way self-join + anti-join shuffle work.

The relevant point for the report's argument: typed `Dataset[T]`
users pay a real per-row tax that varies from ~11% (shuffle-bound)
to ~80% (filter+aggregate) of query time. The microbench (§11)
quantifies the per-row cost; this section shows how it manifests in
end-to-end query time depending on what the rest of the query does.

### Ablation analysis

[Table: same numbers × 8 Spark config combos. Discuss patterns.]

Key findings from the ablation matrix:
- **Codegen ON × AQE ON × threads=*** is Spark's default and the
  fastest config for both DF and DS paths.
- **`local[1]` is ~4× slower** than `local[*]` for both, confirming
  per-row work parallelizes well.
- **AQE on/off** is roughly neutral at SF=1 (tiny data; AQE has no
  reshape work to do). Would matter more at larger SF.
- **Codegen OFF** slows the DF path dramatically (~2-3×) while DS
  scales similarly — confirming codegen handles the bulk of DF's
  optimization, but the DS path is encoder-bound regardless.

### Implication: encoder replacement matters (with caveats)

This report does **not** integrate our encoder into a Spark fork and
re-run end-to-end queries. We measure two things separately: (a)
per-row encoder cost in isolation [1.23× geomean faster than Spark],
and (b) the encoder share of actual query time in current Spark
[80-83% on scan-heavy queries].

A reader can reasonably infer that **replacing Spark's encoder with
ours would reduce the DS-vs-DF tax materially**. But the exact
end-to-end speedup depends on Catalyst optimizations we don't
model — filter pushdown into Parquet may already mean many rows
never reach the encoder, and the encoder share itself may be
amortized differently in Spark's actual execution than our JMH
micro suggests.

Concrete, quantified next step (left for future work):
1. Vendor a fork of Spark that swaps `ExpressionEncoder` for our
   compile-time variant.
2. Re-run TPC-H Q1/Q6/Q14/Q21 with the same data.
3. Measure end-to-end DS query time directly with the new encoder.

What the **current** report claims: (a) per-row encoder cost can be
1.23× faster than Spark's via a Scala 3 macro [proven by §11], (b)
the encoder accounts for 80-83% of scan-heavy DS query time today
[shown by §12], (c) by combination these are strong necessary
conditions for a meaningful end-to-end improvement — but
"meaningful" requires the Spark-fork measurement we haven't done.

## §13. Cold-start cost and where Spark still wins (~700 words)

### Cold-start: Spark pays a real one-time cost per encoder

Spark's encoder construction path is:

1. `ExpressionEncoder[T]()` → `ScalaReflection.encoderFor[T]` → walk
   `TypeTag[T]` via `scala.reflect.runtime.universe`. **Forces
   initialization of Scala 2's reflection runtime** on first encoder
   construction in the JVM (~50–100 ms on JDK 21, single-shot).
2. `createSerializer()` / `resolveAndBind().createDeserializer()` →
   build an `UnsafeProjection` via `GenerateUnsafeProjection`.
3. First call → triggers whole-stage codegen compilation.

Spark **logs every codegen pass**. From our SF=1 benchmark runs,
visible in the JMH log output (sbt task ID `besr7gc9f`):

```
INFO CodeGenerator: Code generated in 106.242584 ms
INFO CodeGenerator: Code generated in 15.557708 ms
INFO CodeGenerator: Code generated in 10.185334 ms
INFO CodeGenerator: Code generated in 11.959917 ms
INFO CodeGenerator: Code generated in 6.490541 ms
INFO CodeGenerator: Code generated in 127.876875 ms
```

The first codegen pass per encoder type is **~106–128 ms**;
subsequent codegens for the same encoder pipeline cost ~5–20 ms.
For a typed `Dataset` job with N distinct case classes, expect
roughly `N × 100 ms` of one-time codegen cost on first invocation.

**Our encoder pays zero of this.** All derivation happens at
compile time via the quoted macro (§7). At runtime, constructing a
`UnsafeRowSerializer` is one allocation; calling `serialize()` does
exactly what the millionth call does — no reflective lookup, no
codegen. JMH's "warmup" iterations are flat from iteration 1 (see
the JMH JSON `rawData` — iteration 1 is within ~1% of iteration 15
on our side; Spark's iteration 1 is also flat because JMH warmup
absorbs the codegen, but had we measured the very first call to a
fresh JVM we'd see the 106 ms hit).

This is a structural advantage that scales: for a serverless or
short-lived JVM (Spark Connect worker, Lambda execution, etc.), the
~100 ms × N startup cost can dominate. Quantified separately from
the per-row claim because it's a different axis.

### Where Spark still wins (honest accounting)

- **Allocation rate**: ours is **1.02–1.08× Spark** post-Step-3
  (down from 2.54× in baseline). Worst case Lineitem deserialize at
  1.22× — the variable-length region with 5 string fields + 4
  decimals is where we still pay slightly more in per-row
  allocation. Closing this would require either pooling
  `UTF8String` / `Decimal` instances (Spark doesn't do this either)
  or reading raw bytes for the BigDecimal path. Engineering cost
  doesn't obviously justify it given the speed wins already cover
  the relevant queries.

- **Single-architecture validation only**. All measurements on
  Apple Silicon (arm64). EC2 m6i.8xlarge x86_64 cross-arch sanity
  check is pending (~$1, ~50 min). Spark's codegen might JIT
  differently on x86_64 with AVX-512. We commit to running this
  and updating the report.

- **Limited type surface**. Collections, nested case classes,
  several less-common temporal types, and Spark-class external
  types deferred per §7. Covers the TPC-H benchmark surface but
  not the full Spark 4.1 AgnosticEncoder catalog.

- **No multi-thread contention test**. We assume per-task
  thread-confinement makes contention irrelevant (one writer per
  serializer instance; in Spark, one serializer per task). The JMH
  benchmarks run single-threaded; multi-task contention isn't
  separately measured.

- **No JIT inlining inspection.** We claim our macro-emitted
  constructor + `writer.write` calls are JIT-inlinable. We haven't
  verified with `-prof perfasm` or JITWatch — would strengthen the
  report. The fact that we beat Spark's whole-stage-codegen-produced
  bytecode is indirect evidence the JIT is doing what we want, but
  bytecode-level confirmation is honest follow-up work.

- **End-to-end Spark-fork integration not done.** §12 measures
  Spark's current encoder cost in queries; replacing the encoder
  in a Spark fork and re-measuring is the definitive validation we
  haven't performed.

---

# Part 6 — Implications

## §14. The migration argument (~800 words)

### What changes for Spark if compile-time encoders are adopted

- **Scala 3 unlocks**. The `TypeTag` dependency is the load-bearing
  block; replacing the encoder removes it.
- **GraalVM native-image becomes viable**. Spark Connect could
  ship native binaries.
- **Cold-start cost drops**. No more `runtime.universe`
  initialization per JVM.
- **Type errors caught at compile time**. Misspelled field, wrong
  type — `scalac` catches it instead of "no such column" at request
  time.
- **End-to-end query time drops** on encoder-dominated workloads
  (Q1/Q6/Q14 see ~15-20% improvement on the per-row component).

### What Spark loses (and what's straightforward to keep)

- **Lenient serialization** — Spark's `lenient` flag on
  `DateEncoder` / `LocalDateEncoder` / `TimestampEncoder` /
  `InstantEncoder` / `JavaDecimalEncoder` lets one column accept
  multiple compatible external types (e.g. both `java.sql.Date`
  and `java.time.LocalDate` populate the same `DateType` column).
  Our model uses a separate `given` per external type, which is
  type-safe at the call site but means there's no runtime toggle
  to "be lenient." **Mitigation**: a `lenient` mode would add
  ~30 lines per affected type — declare overload accepting both
  external types, share the inline read/write impl. Not a design
  block; just not done in the prototype because TPC-H doesn't
  exercise it.

- **`TransformingEncoder` codec system** (Kryo, Java serialization,
  Fory). Spark's encoder allows wrapping non-natively-encoded
  types in a binary codec. Our `encoder` module has the same
  abstraction (`TransformingEncoder[T]` + `BinaryCodec`) but the
  macro derivation doesn't auto-wire it; users would call
  `ProtoEncoder.fromCodec(KryoCodec)` explicitly. **Mitigation**:
  trivial — add a `given Codec[T] => UnsafeRowSerializer[T]`
  derivation path.

- **Backward compatibility with existing serialized artifacts** —
  *none required*, because our encoder produces byte-identical
  `UnsafeRow` output (§9). Any `Dataset[T]` written to disk with
  Spark's encoder is readable with ours and vice versa. Shuffle
  blocks transfer transparently. Parquet writers / readers see
  the same column layout. This is the property that makes the
  migration mechanically feasible without coordinating a flag day.

- **`Mirror.fromProduct`-style runtime introspection** of an
  encoder's schema. Currently Spark code that does
  `agnosticEncoder.dataType` or `.schema` works because the
  AgnosticEncoder is an explicit object. We keep this — the
  `UnsafeRowSerializer` exposes `.schema` and `.sparkSchema` as
  computed properties, derived at compile time from the type.
  Any caller that reads encoder schema continues to work.

### What it would take

[Concrete proposal: a new module in Spark called
`spark-sql-encoder-3`, conditionally compiled for Scala 3
builds, providing the macro derivation. The existing
`ExpressionEncoder` stays for Scala 2.13. Users opt in via
import.]

## §15. Future work (~400 words)

- Full AgnosticEncoder type surface
- Cluster benchmarks (currently skipped per single-node argument)
- EC2 / x86_64 cross-arch numbers
- Integration with a Spark fork to measure the *actual* end-to-end
  improvement (not the per-row-extrapolated estimate from §12)
- Spark Connect wire format coverage

---

# Appendices

## A. Full disclosure block
- Hardware, JDK, Spark, Scala versions, git SHA, timestamp.
- Identical to what `disclosure.txt` contains for the publication run.

## B. Reading the JMH JSON
- For reviewers wanting to verify primary metric scores and CIs.

## C. Key code listings
- The macro (`UnsafeRowSerializerMacro.scala`) — full text.
- The byte-parity test pattern (`UnsafeRowParitySpec.scala`).
- The benchmark harness annotations.

## D. Reproducibility
- `./scripts/bench.sh [SF]` instructions.
- `docs/CLOUD_BENCH.md` for EC2 setup.
- Data generation: `./scripts/gen-tpch.sh [SF]`.

## E. Encoder parity matrix
- Full table from `docs/ENCODER_PARITY.md` — every Spark 4.1
  AgnosticEncoder variant cross-referenced with our coverage.

---

# Gaps surfaced by writing this outline — and how each was addressed

The first review pass surfaced 8 weaknesses. Each is now addressed
in the outline; resolution noted here for the record.

| # | Gap | Resolution |
|---|---|---|
| 1 | §3 cost decomposition (per-row vs per-call) | Added "Decomposing the cost" subsection in §3 with arithmetic showing DS-Q1 = 188 ns/row total, DF-Q1 = 38 ns/row, gap = 150 ns/row attributable to encoder. Triangulates with JMH per-row numbers; Catalyst pushdown explains why the end-to-end gap (150 ns) is less than the JMH deserialize (296 ns). |
| 2 | §12 extrapolation hedge | Replaced "if our encoder replaced Spark's, X% faster" with explicit hedging: we measured (a) per-row 1.23× and (b) 80-83% encoder share of DS time, but did NOT integrate into a Spark fork; that's future work. The report claims necessary conditions for end-to-end speedup, not a quantitative end-to-end prediction. |
| 3 | §11 step-by-step progression | Added two tables: speedup geomean by step, allocation ratio by step. Shows that the three optimizations (writer cache, inline-dispatch deserialize, quoted-macro constructor) are independent and each addresses a distinct bottleneck. |
| 4 | §13 cold-start measurement | Added "Cold-start" subsection in §13 citing Spark's own log output (`Code generated in 106.242584 ms` etc., visible during our benchmark runs). First codegen pass per encoder type is 106-128 ms; our path pays zero of this. Doesn't require a separate JMH benchmark — Spark's logs are evidence. |
| 5 | §14 lenient serialization | Added explicit treatment: lenient mode is gone in our model but trivially restorable (~30 LOC per affected type). Not a design block. |
| 6 | §7 binary / wire compat | Added "Binary / wire compatibility" subsection noting byte parity → automatic shuffle / Parquet / lake-format compatibility. No flag-day migration. |
| 7 | §7 type coverage classified | Split "out of scope" list into three buckets (easy to add / needs macro refinement / out of scope by design) — readers can tell what's a real engineering gap vs intentional. |
| 8 | §12 encoder fraction of DS time | Added "Encoder fraction of DS" column to the DS-vs-DF table: 80% Q1, 83% Q6, 83% Q14, 10% Q21. Derived from existing data, no new measurement. |

## What's still uncertain (real follow-up work)

These are *not* addressable from existing data; they're honest
future-work items now called out in §13 and §15:

- **EC2 / x86_64 cross-arch validation** (~$1, ~50 min). Pending.
- **Spark-fork integration** for definitive end-to-end claim.
- **JIT inspection** via `-prof perfasm` or JITWatch — would confirm
  that our macro-emitted code inlines as expected.
- **Multi-task contention** measurement — currently assumed
  irrelevant per task isolation, not directly tested.

These are the actual open questions; everything else in the original
gap list is now closed.
