# Benchmark Methodology

The rules every published number in the Phase A/B encoder benchmark + blog
post must follow. Locked in *before* numbers exist so we can't drift toward
convenient results later.

The audience priority is Spark committers first (rigorous, no
hand-waving), Scala 3 community second (qualitative wins backed by
numbers). These rules exist to make the comparison unimpeachable.

## 1. Hardware + environment disclosure

Every chart or table in the report is preceded by a full-disclosure
paragraph following the Photon paper format (SIGMOD 2022). Minimum
contents:

- Hardware: CPU model + core count + RAM + storage backend (NVMe SSD vs network).
- JDK: vendor + version + GC algorithm (e.g. `Temurin 21.0.7, G1GC`).
- Scala version + sbt version + project git SHA.
- Spark version (currently 4.1.2) + every non-default `spark.sql.*` config.
- Scale factor + data location (local FS vs S3) + file format (Parquet/text).
- Number of runs and which statistic is reported.
- Single-node or cluster + worker count.

Templates for both the local-Mac and EC2 m6i.8xlarge runs live in
`scripts/disclosure-template.md` (TBD in Task B.4).

## 2. Statistical treatment

Following Georges et al., OOPSLA 2007, "Statistically Rigorous Java
Performance Evaluation":

- **JMH microbenchmarks**: 3 forks, 5 warmup iterations + 15 measurement
  iterations, each 1s. JMH reports mean + 99% CI by default; we report
  both. Declare "no significant difference" when 99% CIs overlap.
- **End-to-end queries**: 1 warmup run discarded, then min-of-3 with
  caches dropped between runs (the ClickBench pattern). Report the min
  plus all three raw values in the appendix.
- **Aggregate speedup across queries**: geometric mean (Eyerman et al.
  CAL 2024 — arithmetic mean of ratios is mathematically wrong).
- **Allocation profiling**: `-prof gc` for JMH, report
  `gc.alloc.rate.norm` (bytes per op). For end-to-end, use
  `-XX:+UnlockDiagnosticVMOptions -XX:+PrintGCDetails` and report total
  GC time as a sidebar.

## 3. Required ablations

Each headline number is reported with at least these three axes
varied; the blog post discusses what changes:

| Axis | Values | Why |
|---|---|---|
| `spark.sql.codegen.wholeStage` | `true` (default), `false` | Spark's codegen ON is the realistic case; OFF isolates encoder cost from query-plan codegen. |
| `spark.sql.adaptive.enabled` | `true` (default), `false` | AQE can mask encoder gains by reshuffling. ON is what users run; OFF makes timing more deterministic. |
| Master URL | `local[1]`, `local[*]` | Single-thread isolates per-row encode cost; multi-thread shows realistic throughput. |

Also document but don't ablate: `spark.sql.shuffle.partitions`,
`spark.sql.autoBroadcastJoinThreshold`,
`spark.sql.files.maxPartitionBytes`, `spark.serializer`,
`spark.memory.offHeap.*`, `spark.sql.execution.arrow.*`.

## 4. Reporting format

- **Per-query bar chart**: Photon style. One bar per system per query.
  Log-scale y-axis when the range exceeds 5×. Error bars at 99% CI.
- **Raw timing matrix**: ClickBench style. Per-query, per-system, all
  runs visible. Sits under the chart in the same section.
- **Aggregate speedup**: geometric mean across queries, with the
  explicit caveat that geomean compresses outliers.
- **Anti-patterns** (do not do): single-query headline, "up to N× faster"
  without distribution, microbenchmark labeled as end-to-end,
  aggregate-only with no per-query breakdown, mean-with-no-CI, missing
  Spark config disclosure.

## 5. Scale factors

| SF | `lineitem` rows | Approx data size | Role in the report |
|---:|---:|---:|---|
| 0.01 | ~60K | ~10 MB | Smoke fixture, committed to repo. |
| 1 | ~6M | ~1 GB | JMH encoder microbenchmark. |
| 10 | ~60M | ~10 GB | Local-Mac headline end-to-end. |
| 100 | ~600M | ~100 GB | EC2 m6i.8xlarge headline. Publication-quality. |

We do not publish SF=1000+ unless we get a cluster.

## 6. Reproducibility contract

A run is only "credible" if:

1. The full disclosure paragraph exists (rule 1).
2. The run was produced by `./scripts/bench.sh sf=N` from a clean
   checkout, against a public git SHA (task B.4).
3. Raw JMH JSON + end-to-end wall-clock CSV are attached to the
   report or its appendix.
4. The EC2 setup script + AMI ID + instance type are documented so
   the cloud-side numbers reproduce on a fresh instance.

Without these four, a number does not appear in the blog post.

## 7. Comparison surface

Three systems compared end-to-end (B.3):

- **Spark `Dataset[T]`** — typed; exercises `ExpressionEncoder` per row.
  Realistic baseline, also the slowest in most reports.
- **Spark `DataFrame`** — untyped; encoder-free baseline, the
  upper-bound for what Spark can do at the row-format layer.
- **ProtoCatalyst `quote { }` → `SparkQueryRunner`** — compile-time IR
  + protobuf serialization + Spark execution. The point of the
  comparison.

Two encoders compared in the microbench (B.2):

- **Spark `ExpressionEncoder[T]`** — runtime reflection + whole-stage
  codegen → `UnsafeRow`.
- **ProtoCatalyst `UnsafeRowSerializer.derived[T]`** — compile-time
  Mirror derivation → `UnsafeRow` (proven byte-identical to Spark's
  output, see `UnsafeRowParitySpec`).

## 8. Honesty rules

- Cite Frameless's "implicit-derived encoders perform identically to
  reflection-based" claim and explicitly state what Phase A/B tests
  about it.
- Document at least one workload or scale factor where ProtoCatalyst
  doesn't win, if such exists. Credibility is built by acknowledging
  where the alternative is competitive.
- Migration path discussion (final section of the blog) must
  acknowledge real costs: Scala 3 stdlib migration, Spark's existing
  Scala 2.13 user base, the SemanticDB / Scalafix toolchain lag noted
  in the SIP-51 escape-hatch we applied to `build.sbt`.

## Sources

- Georges et al., *Statistically Rigorous Java Performance Evaluation*,
  OOPSLA 2007 — https://dri.es/files/oopsla07-georges.pdf
- Behm et al., *Photon: A Fast Query Engine for Lakehouse Systems*,
  SIGMOD 2022 — https://people.eecs.berkeley.edu/~matei/papers/2022/sigmod_photon.pdf
- ClickBench — https://github.com/ClickHouse/ClickBench
- DuckDB benchmarking guidelines —
  https://duckdb.org/docs/current/guides/performance/benchmarks
- TPC-H Standard Specification v3.0.1 —
  https://www.tpc.org/TPC_Documents_Current_Versions/pdf/TPC-H_v3.0.1.pdf
- Sinchenko, *Benchmarking Spark with JMH* —
  https://semyonsinchenko.github.io/ssinchenko/post/spark-and-jmh/
- Eyerman et al., *R.I.P. Geomean Speedup*, UGent CAL 2024 —
  https://users.elis.ugent.be/~leeckhou/papers/CAL-2024-geomean.pdf
