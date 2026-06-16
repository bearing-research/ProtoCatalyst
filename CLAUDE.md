# CLAUDE.md

Project-level guidance for Claude Code working in this repo. Read this first; deeper detail lives in
`docs/` (linked below).

## What this project is

ProtoCatalyst is a compile-time Spark SQL / Catalyst optimizer that has grown into an
engine-independent query compiler (LLVM-for-queries analogy). The **headline initiative** is the
*reflection replacement*: replace Spark's runtime reflection-based encoder derivation
(`ScalaReflection.encoderFor[T: TypeTag]`) with Scala 3 compile-time derivation (`ProtoEncoder`), to
unblock Spark's migration to Scala 3.

- **Primary goal:** push Spark toward Scala 3. Stock Spark is the correctness oracle + benchmark
  baseline only — we do not ship a plugin.
- **Strategy:** tech-report-first to gain traction, then upstream. The 2-line `ScalaReflection` patch
  is the scoped "down payment" ask.

Key docs (full index: `docs/README.md`; docs are split into `docs/scala3-encoder/` and
`docs/compiler/` tracks):
- `docs/scala3-encoder/REPORT.md` — the writeup (blocker → replacement → results → migration). The artifact.
- `docs/scala3-encoder/REFLECTION_REPLACEMENT.md` — bridge design, decisions, milestones; §2.1.1 = the wall patch.
- `docs/scala3-encoder/INFRASTRUCTURE.md` — cross-version build mechanics + **how to run every benchmark/test**.
- `docs/scala3-encoder/SCALA3_SUPERSET.md` — behaviors beyond Spark's encoder model.
- `docs/scala3-encoder/BENCHMARKS.md` — benchmark suite + methodology + EC2/cross-arch runs.

## Build & toolchain

- sbt **1.12.1**. `ThisBuild/scalaVersion := 3.8.1`; some modules pin **2.13.16** (see below).
- **JDK 21 is required** and is wired via `.sbtopts` (`-java-home /opt/homebrew/opt/openjdk@21`).
  The shell's default `java` is JDK 1.8 and will NOT run scalac/Spark 4.1. If you run scalac or a
  Scala 3 JVM directly (outside sbt), first `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`.
- Spark target: **4.1.2**.

### Cross-version layout (why two Scala versions)

The baseline (`encoderFor[T: TypeTag]`) only exists on 2.13; the replacement only on 3. Both live in
one build:

- **Scala 3 (3.8.1):** `core`, `proto`, `encoder`, `arrow`, `query`, `benchmarks`, `encoderSpark`
  (`encoderSpark` consumes Spark via `CrossVersion.for3Use2_13`).
- **Scala 2.13.16:** `benchmarkSpark` (Spark baseline + parity-golden generator), `sparkCatalyst`,
  `sparkReflectionPatch` (the 2-line patched `ScalaReflection`).

The seam that makes this work: `ExpressionEncoder.apply[T](enc: AgnosticEncoder[T])` — the no-`TypeTag`
overload, callable from Scala 3. `AgnosticEncoderBridge.toAgnostic` produces the `AgnosticEncoder`.

## Modules & status

Two bodies of work. The **Scala-3 reflection-replacement thesis** is the active focus; the broader
**query compiler** (Phases 1–11) is feature-complete and mostly **frozen since ~Feb 2026** — verify
against the code before assuming a frozen module is current. Full detail: `ROADMAP.md`; encoder docs:
`docs/scala3-encoder/`.

- **Active (encoder / Scala 3):** `encoder` (`ProtoEncoder` derivation, `InlineRowSerializer`),
  `encoder-spark` (the `AgnosticEncoderBridge`), `spark-reflection-patch` (the wall patch),
  `benchmarks` + `benchmark-spark`. `core` (shared IR) still moves with this work.
- **Frozen / feature-complete:**
  - `core` (3) — IR (ProtoExpr 100 / ProtoLogicalPlan 27 / ProtoPhysicalPlan 30), 41-rule SQL optimizer, JSON+protobuf codec.
  - `sql-parser`, `query` (`quote {}` DSL), `arrow` (+ Parquet) — Scala 3, complete.
  - `spark-catalyst` (2.13) — Spark execution bridge + parity tests.
  - `executor` (3) — standalone Arrow engine + DataFusion backend (SQL transpiler + ADBC Flight SQL).
  - `ml-core` / `ml-query` (3) — tensor IR, autograd, ONNX, 8 ML rules, ML-in-SQL `Predict`/`Fit`.
  - `proto` (Java) — protobuf schema, consumed by both Scala versions.
- **Removed:** the dead `spark/` module (superseded by `encoder-spark` + `spark-catalyst`); the
  `substrait` prototype (types/exprs only, never reached plan conversion — SQL transpiler is the
  chosen interchange path; see ADR-002/ADR-003).
- **Not started:** Velox backend.

## Common commands

```bash
sbt compile                                              # all modules, both Scala versions
sbt encoderSpark/test                                   # bridge parity + execution-wall e2e (Scala 3)
sbt 'encoderSpark/testOnly *AgnosticEncoderBridgeSpec'  # structural parity vs goldens
sbt 'encoderSpark/testOnly *ExecutionWallSpec'          # end-to-end round-trips (uses the patch)

# Derivation benchmarks (headline). deriveMixed = 8 distinct types/op (defeats "it's cached")
sbt 'benchmarkSpark/Jmh/run -f 1 -wi 3 -i 5 -t 8 SparkEncoderDerivationBenchmarks'  # reflective
sbt 'benchmarks/Jmh/run     -f 1 -wi 3 -i 5 -t 8 EncoderDerivationBenchmarks'        # compile-time

sbt 'benchmarkSpark/runMain org.apache.spark.sql.protocatalyst.ColdStartProbe'      # cold-start cost
```

Regenerate parity goldens after adding a corpus type (edit both 2.13 fixtures and the 3 spec):
```bash
sbt 'benchmarkSpark/runMain org.apache.spark.sql.protocatalyst.AgnosticParityFixtures'
sbt 'encoderSpark/testOnly *AgnosticEncoderBridgeSpec'
```

## Hard constraints (do not violate)

- **Never run a benchmark while another sbt process is touching the same module.** Concurrent
  recompilation corrupts/aborts the run. Don't edit a module's sources while its benchmark is running.
- **Commit messages** must end with:
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
- **Push only when explicitly told.** Branch first if on `main` and the user wants a PR.

## Status (2026-06)

Reflection-replacement engine + bridge work, structural/byte parity, the execution-wall patch, and the
benchmark suite (derivation cost, cold-start, multi-tenant) are done and reported in `docs/scala3-encoder/REPORT.md`.
Open directions: tech-report polish + related work, the dev@spark pitch, the clean 2-line-patch PR;
later — cross-arch EC2 sweep (parked on AWS creds), second backend for generality. There is one stale
JIRA ticket (only the user has updated it).
