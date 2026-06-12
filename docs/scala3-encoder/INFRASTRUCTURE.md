# Infrastructure: cross-version build & how to run the reflection-replacement work

This document explains how a single sbt build runs **two Scala versions at once** — the mechanism
that lets a Scala 3 compile-time encoder be compared against, and validated against, Spark's Scala
2.13 reflective encoder — and how to drive every piece of the reflection-replacement system
(`REFLECTION_REPLACEMENT.md`, `REPORT.md`). For the per-row / end-to-end query benchmark
methodology (statistics, ablations, disclosure), see `BENCHMARKS.md`.

## 1. Why two Scala versions

The whole thesis is "replace Spark's Scala-2.13 reflective encoder derivation with Scala 3
compile-time derivation." Measuring and validating that requires both sides present in one repo:

- **The baseline only exists on 2.13.** `ScalaReflection.encoderFor[T: TypeTag]` needs `TypeTag` and
  `scala.reflect.runtime` — neither exists on Scala 3. So the *baseline* (and the golden encoders we
  check against, and the patched `ScalaReflection` demonstrator) must be compiled as genuine 2.13.
- **The replacement only exists on 3.** `ProtoEncoder.derived[T]` is built on Scala 3 `Mirror` /
  `inline` / quotes. So the *replacement* must be compiled as Scala 3.

A single build with per-module `scalaVersion` lets the two interoperate on the same JVM, hardware,
and JMH harness — the precondition for a fair comparison.

## 2. Module topology

`ThisBuild / scalaVersion := "3.8.1"`; individual modules override to `2.13.16` where they must
touch Spark's reflection.

| Module | Scala | Role | Spark dependency |
|---|---|---|---|
| `core`, `proto` | 3.8.1 | IR, protobuf (proto is Java-only) | none |
| `encoder` | 3.8.1 | `ProtoEncoder` (compile-time derivation engine), `InlineRowSerializer` | none |
| `encoder-spark` | 3.8.1 | **`AgnosticEncoderBridge`** (ProtoEncoder → Spark `AgnosticEncoder`), Arrow path | spark-sql/-catalyst 4.1.2 **via `for3Use2_13`** |
| `arrow`, `query` | 3.8.1 | Arrow IPC, query DSL | none / via encoder-spark |
| `benchmarks` | 3.8.1 | JMH: our side (`EncoderDerivationBenchmarks`, …) | — |
| `benchmark-spark` | **2.13.16** | JMH: Spark baseline (`SparkEncoderDerivationBenchmarks`) **and** the parity-golden generator (`AgnosticParityFixtures`) | spark-sql/-catalyst 4.1.2 (native 2.13) |
| `spark-catalyst` | **2.13.16** | Spark-side integration / e2e parity harness | native 2.13 |
| `spark-reflection-patch` | **2.13.16** | The 2-line patched `ScalaReflection` (execution-wall demonstrator, `REFLECTION_REPLACEMENT.md` §2.1.1) | native 2.13 |

## 3. How the cross-version wiring works

### 3.1 Scala 3 modules consume Spark's 2.13 jars (`for3Use2_13`)

`encoder-spark` is Scala 3 but depends on Spark, which only publishes `_2.13` artifacts:

```scala
("org.apache.spark" %% "spark-sql" % "4.1.2").cross(CrossVersion.for3Use2_13)
```

`for3Use2_13` tells the resolver to fetch the `_2.13` artifact for a Scala 3 build. This works
because Scala 3 runs on the **2.13 standard library** and can consume 2.13 bytecode. The constraint
it imposes — and the entire design point — is that we may only call Spark through APIs that do *not*
need a Scala-3 `TypeTag`. The seam is the no-`TypeTag` overload:

```scala
ExpressionEncoder.apply[T](enc: AgnosticEncoder[T])   // reflection-free; callable from Scala 3
// (NOT  ExpressionEncoder.apply[T: TypeTag]()  — that routes through ScalaReflection.encoderFor)
```

`AgnosticEncoderBridge.toAgnostic` produces the `AgnosticEncoder[T]`; from there Spark's pipeline is
reused unchanged.

### 3.2 The SIP-51 escape hatch

Spark 4.1.2 transitively pulls `scala-library` 2.13.17/18, while the 2.13 modules pin
`scalaVersion := "2.13.16"` (the newest with a published `semanticdb-scalac` for Scalafix). SIP-51
flags this as a backward-compatibility risk; we demote it:

```scala
allowUnsafeScalaLibUpgrade := true
```

Safe here because we don't link against symbols that exist only in the newer stdlib. This is a real
migration cost worth disclosing (toolchain lag), not hidden — see `BENCHMARKS.md` §8.

### 3.3 The classpath-shadow demonstrator

`spark-reflection-patch` compiles a verbatim copy of Spark 4.1.2's `ScalaReflection` with two lines
changed, as 2.13 bytecode. It is prepended to `encoder-spark`'s **test** classpath so the JVM loads
*our* `ScalaReflection` instead of Spark's (a class is resolved by the first match on the classpath):

```scala
// build.sbt, encoderSpark settings
Test / fullClasspath := {
  val patched = (sparkReflectionPatch / Compile / products).value.map(Attributed.blank)
  patched ++ (Test / fullClasspath).value
}
```

This lets `ExecutionWallSpec` run Spark's real ser/deser from a Scala 3 process. Details and the
why-it's-not-a-cheat argument: `REFLECTION_REPLACEMENT.md` §2.1.1.

### 3.4 Cross-compile parity (the correctness oracle)

Because `encoderFor[T: TypeTag]` is 2.13-only, the goldens are generated on the 2.13 side and
asserted on the 3 side:

- `benchmark-spark` (2.13) — `AgnosticParityFixtures` runs `ScalaReflection.encoderFor[T]`, dumps a
  canonical (class-name-normalized) structure, writes `*.agnostic` files into
  `encoder-spark/src/test/resources/agnostic-parity/`.
- `encoder-spark` (3) — `AgnosticEncoderBridgeSpec` derives the same type with `ProtoEncoder.derived`
  + the bridge and asserts its canonical dump equals the golden.

The user case class lives in different packages on each side, so the canonical dump compares
`ClassTag`s by *simple* name. This is the same cross-compile fixture pattern the Arrow wire-format
parity uses.

## 4. How to run things

All commands are from the repo root. `Jmh / fork := true` and `Test / fork := true` are set, so each
run is a fresh JVM (see §5 on why that matters for the numbers).

### 4.1 Build & test

```bash
sbt compile                                   # all modules (both Scala versions)
sbt test                                       # full suite
sbt encoderSpark/test                          # bridge parity + execution-wall e2e (Scala 3 side)
sbt 'encoderSpark/testOnly *AgnosticEncoderBridgeSpec'   # structural parity only
sbt 'encoderSpark/testOnly *ExecutionWallSpec'           # end-to-end round-trips (uses the patch)
```

### 4.2 Derivation benchmarks (the headline)

Two suites, same JMH config, compared across thread counts. `deriveLineitem`/`deriveOrders` are
single-type; `deriveMixed` derives 8 distinct types per op (no repeat — defeats any "it's cached"
objection):

```bash
# Spark baseline (reflective, Scala 2.13) — note throughput DROPS as threads rise (global lock)
sbt 'benchmarkSpark/Jmh/run -f 1 -wi 3 -i 5 -t 1  SparkEncoderDerivationBenchmarks'
sbt 'benchmarkSpark/Jmh/run -f 1 -wi 3 -i 5 -t 8  SparkEncoderDerivationBenchmarks'

# Ours (compile-time, Scala 3) — scales with cores
sbt 'benchmarks/Jmh/run -f 1 -wi 3 -i 5 -t 1  EncoderDerivationBenchmarks'
sbt 'benchmarks/Jmh/run -f 1 -wi 3 -i 5 -t 8  EncoderDerivationBenchmarks'

# Publication fidelity: -f 3 -wi 5 -i 10 (the @Fork/@Warmup/@Measurement defaults on the classes)
```

### 4.2b Multi-tenant experiment (operator view: throughput + tail latency)

Models a multi-threaded application that builds typed `Dataset`s concurrently (a query-serving
service on a shared `SparkSession`, or threaded job submission — *not* the Connect server, which
ships client-derived encoders) — `S` threads each deriving an `ExpressionEncoder` via the real public
API, cycling the 8 TPC-H types — reporting throughput + p50/p99 as `S` grows (REPORT §9d). Custom load
generator, not JMH:

```bash
sbt 'benchmarkSpark/runMain org.apache.spark.sql.protocatalyst.MultiTenantDerivation'  # reflective
sbt 'benchmarks/runMain protocatalyst.bench.MultiTenantDerivation'                      # compile-time
```

### 4.2c Cold-start probe (the everyday, concurrency-free cost)

First reflective derivation in a fresh JVM (forces `scala.reflect.runtime.universe`) vs warm
steady-state — the ~1 s per-JVM cold start short-lived drivers pay every run (REPORT §9). One number
per fresh JVM, so run it a few times:

```bash
sbt 'benchmarkSpark/runMain org.apache.spark.sql.protocatalyst.ColdStartProbe'
```

### 4.3 Regenerate the parity goldens

After adding a type to the corpus (both `AgnosticParityFixtures` on 2.13 and the spec on 3):

```bash
sbt 'benchmarkSpark/runMain org.apache.spark.sql.protocatalyst.AgnosticParityFixtures'
sbt 'encoderSpark/testOnly *AgnosticEncoderBridgeSpec'    # assert against the new goldens
```

### 4.4 Use the bridge from code

```scala
import protocatalyst.encoder.ProtoEncoder
import protocatalyst.encoder.spark.AgnosticEncoderBridge
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder

val agnostic = AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[MyCaseClass]) // no TypeTag
val enc      = ExpressionEncoder(agnostic).resolveAndBind()
val row      = enc.createSerializer()(value)      // executes only with the §3.3 patch on a Scala 3 JVM
```

## 5. Does the harness affect the numbers?

A reviewer's first question. Short answer: the sbt/JMH harness is *excluded* from the measurement,
and the cross-version setup is a fair same-contract comparison — with one asymmetry we state openly.
Full treatment is in `REPORT.md` §9 ("Measurement validity"); summary:

- **sbt is not in the measured JVM.** JMH forks a fresh JVM per `-f`; sbt only launches it. Harness
  and class-loading overhead land in warmup, not measurement.
- **Warmup excludes JIT + one-time init.** Spark's ~500 ms `scala.reflect.runtime.universe` cold
  start happens during warmup, so the reported per-op Spark number *understates* real-world cost — a
  conservative bias against our own result.
- **Same JVM/hardware/JMH config on both sides.** The two suites differ only in the thing under
  test (reflective vs compile-time derivation of the same `AgnosticEncoder`).
- **The honest asymmetry.** `ProtoEncoder.derived` moves the *type analysis* to `scalac`, so our
  runtime number measures only residual construction; Spark does it all at runtime. That is the
  thesis, not a measurement trick — but the compile-time cost is real and is a `scalac`-time
  tradeoff, discussed in §9.

## See also

- `REPORT.md` — the writeup (blocker → replacement → results → migration), incl. §9 validity.
- `REFLECTION_REPLACEMENT.md` — bridge design, decisions, milestones, §2.1.1 wall patch.
- `SCALA3_SUPERSET.md` — behaviors beyond Spark's encoder model.
- `BENCHMARKS.md` — the benchmark suite, methodology (statistics/ablations/disclosure), and EC2 /
  cross-arch runs (this absorbed the former `BENCHMARK_METHODOLOGY.md` and `CLOUD_BENCH.md`).
- `archive/REPORT_encoder_perf.md` — the archived per-row "ceiling" study (UnsafeRow/Arrow serializers).
