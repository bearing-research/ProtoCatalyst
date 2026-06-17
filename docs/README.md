# ProtoCatalyst documentation

The docs are split into two tracks.

- **[`scala3-encoder/`](scala3-encoder/)** — the headline initiative: replacing Spark's
  runtime reflection-based encoder derivation with Scala 3 compile-time derivation, to unblock
  Spark's Scala 3 migration. This is the publication/upstreaming work.
- **[`compiler/`](compiler/)** — the broader query-compiler project: the compile-time Catalyst /
  optimizer, the query DSL, the SQL parser, and Spark-internals references.

Architecture decision records ([`decisions/`](decisions/)) are cross-cutting and apply to both.

---

## Scala 3 / compile-time encoder (the reflection-replacement thesis)

Start with the report; the rest are companions.

| Doc | What it is |
|---|---|
| **[scala3-encoder/REPORT.md](scala3-encoder/REPORT.md)** | The writeup: blocker → replacement → results → migration. **The artifact.** |
| [scala3-encoder/REFLECTION_REPLACEMENT.md](scala3-encoder/REFLECTION_REPLACEMENT.md) | Bridge design, decisions, milestones; §2.1.1 = the 2-line execution-wall patch. |
| [scala3-encoder/SCALA3_SUPERSET.md](scala3-encoder/SCALA3_SUPERSET.md) | Behaviors beyond Spark's encoder model (enums, ADTs, extensions). |
| [scala3-encoder/ENCODER_PARITY.md](scala3-encoder/ENCODER_PARITY.md) | `ProtoEncoder` ↔ Spark `AgnosticEncoder` variant-by-variant parity. |
| [scala3-encoder/ENCODER_DEEP_DIVE.md](scala3-encoder/ENCODER_DEEP_DIVE.md) | Beginner-friendly guide to what encoders are and how we derive them. |
| [scala3-encoder/INFRASTRUCTURE.md](scala3-encoder/INFRASTRUCTURE.md) | Cross-version (Scala 3 ↔ 2.13) build topology + **how to run everything** + measurement validity. |
| [scala3-encoder/BENCHMARKS.md](scala3-encoder/BENCHMARKS.md) | Benchmark suite, methodology, and EC2 / cross-arch runs (merged from the former methodology + cloud docs). |
| [scala3-encoder/AOT_ROADMAP.md](scala3-encoder/AOT_ROADMAP.md) | GraalVM `native-image` follow-up — what else blocks AOT beyond the encoder. |
| [scala3-encoder/archive/REPORT_encoder_perf.md](scala3-encoder/archive/REPORT_encoder_perf.md) | **Archived.** The original per-row "ceiling" study (UnsafeRow/Arrow serializers). Superseded as the headline by REPORT.md; kept for its per-row data. |

## General query-compiler

| Doc | What it is |
|---|---|
| [compiler/DESIGN.md](compiler/DESIGN.md) | Overall architecture and design decisions. |
| [compiler/OPTIMIZER_PLAN.md](compiler/OPTIMIZER_PLAN.md) | The compile-time Catalyst optimizer (rules + implementation). |
| [compiler/COMPILE_TIME_DSL.md](compiler/COMPILE_TIME_DSL.md) | How compile-time query optimization works. |
| [compiler/DSL_REFERENCE.md](compiler/DSL_REFERENCE.md) | The query DSL / IR / optimizer API, with Spark Catalyst comparison. |
| [compiler/SQL_PARSER.md](compiler/SQL_PARSER.md) | The compile-time SQL parser. |
| [compiler/CROSS_BACKEND.md](compiler/CROSS_BACKEND.md) | One plan, two engines: the cross-backend TPC-H harness (Local Arrow vs DataFusion) — evidence for the engine-independent-compiler thesis. |
| [compiler/SPARK_CATALYST_REFERENCE.md](compiler/SPARK_CATALYST_REFERENCE.md) | Spark Catalyst internals reference. |
| [compiler/SPARK_TEST_PATTERNS_ANALYSIS.md](compiler/SPARK_TEST_PATTERNS_ANALYSIS.md) | Spark Catalyst test patterns vs ours. |

## Decisions (ADRs)

| ADR | Decision |
|---|---|
| [decisions/ADR-001-no-runtime-codegen.md](decisions/ADR-001-no-runtime-codegen.md) | Compile-time derivation over runtime codegen. |
| [decisions/ADR-002-independent-ir.md](decisions/ADR-002-independent-ir.md) | An independent IR rather than building on Substrait. |
| [decisions/ADR-003-protocatalyst-vs-substrait-ir.md](decisions/ADR-003-protocatalyst-vs-substrait-ir.md) | ProtoCatalyst IR vs Substrait, in depth. |
