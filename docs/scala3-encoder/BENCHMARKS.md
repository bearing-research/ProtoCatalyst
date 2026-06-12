# Benchmarks: suite, methodology, and cloud runs

One doc for everything benchmark-related: the JMH suites and how to run them, the methodology rules
every published number must follow, and the EC2 / cross-architecture procedure for
publication-quality numbers. The headline derivation-cost results and their measurement-validity
argument live in [`REPORT.md`](REPORT.md) §9–§9c; the per-row "ceiling" data is in the archived
[`REPORT_encoder_perf.md`](archive/REPORT_encoder_perf.md). This doc is the operational companion to
both.

The audience priority is **Spark committers first** (rigorous, no hand-waving), Scala 3 community
second. The rules below exist to make the comparison unimpeachable, and were locked in *before*
numbers existed so we can't drift toward convenient results later.

---

## 1. The benchmark suites

### `benchmarks/` (Scala 3) — our side

| Benchmark | Description |
|-----------|-------------|
| `EncoderDerivationBenchmarks` | Compile-time `AgnosticEncoder` derivation (the headline; REPORT §9) |
| `InlineSerializerBenchmarks` | Core per-row serialization/deserialization |
| `ProtoEncoderBenchmarks` | Schema derivation (compile-time, measures residual construction) |
| `CollectionBenchmarks` | Collection scaling (10, 100, 1000 elements) |
| `AllocationBenchmarks` | Memory allocation profiling |
| `ScalingBenchmarks` | Row-count scaling |
| `ArrowBenchmarks` | Arrow batch writing |
| `CodecBenchmarks` | `TransformingEncoder` codecs (Java, Kryo, Fory) |

### `benchmark-spark/` (Scala 2.13) — the Spark baseline

| Benchmark | Description |
|-----------|-------------|
| `SparkEncoderDerivationBenchmarks` | `ScalaReflection.encoderFor[T]` derivation (REPORT §9 baseline) |
| `SparkEncoderBenchmarks` | `ExpressionEncoder` per-row performance |
| `SparkScalingBenchmarks` | Spark serialization scaling |
| `SparkArrowBenchmarks` | Spark Arrow integration |

Why two Scala versions live in one build (`encoderFor[T: TypeTag]` is 2.13-only; the compile-time
path is Scala-3-only) is explained in [`INFRASTRUCTURE.md`](INFRASTRUCTURE.md).

---

## 2. Running benchmarks

All commands from the repo root. `Jmh / fork := true` is set, so each run is a fresh JVM.

### Quick run (single benchmark)

```bash
sbt "benchmarks/Jmh/run -i 5 -wi 3 -f 1 InlineSerializerBenchmarks"
sbt "benchmarks/Jmh/run -i 5 -wi 3 -f 1 InlineSerializerBenchmarks.serializeSimple"   # one method
```

### Derivation benchmarks (the headline)

Same JMH config on both sides, compared across thread counts. `deriveMixed` derives 8 distinct types
per op (no repeat — defeats any "it's cached" objection):

```bash
# Spark baseline (reflective, Scala 2.13) — throughput DROPS as threads rise (global lock)
sbt 'benchmarkSpark/Jmh/run -f 1 -wi 3 -i 5 -t 1 SparkEncoderDerivationBenchmarks'
sbt 'benchmarkSpark/Jmh/run -f 1 -wi 3 -i 5 -t 8 SparkEncoderDerivationBenchmarks'

# Ours (compile-time, Scala 3) — scales with cores
sbt 'benchmarks/Jmh/run -f 1 -wi 3 -i 5 -t 1 EncoderDerivationBenchmarks'
sbt 'benchmarks/Jmh/run -f 1 -wi 3 -i 5 -t 8 EncoderDerivationBenchmarks'
```

Cold-start (the per-JVM cost short-lived drivers pay) and the multi-tenant tail-latency experiment
are driven via `runMain` — see [`INFRASTRUCTURE.md`](INFRASTRUCTURE.md) §4.2b–§4.2c.

### Full run (publication quality)

The `@Fork(3) @Warmup(5,1s) @Measurement(10,1s)` defaults on the classes are the publication setting:

```bash
sbt "benchmarks/Jmh/run -f 3 -wi 5 -i 10"
sbt "benchmarkSpark/Jmh/run -f 3 -wi 5 -i 10"
```

### Memory profiling

```bash
sbt "benchmarks/Jmh/run -prof gc AllocationBenchmarks"
#   gc.alloc.rate.norm - bytes allocated per operation
#   gc.count           - number of GC events
```

### JMH parameter reference

| Parameter | Typical | Description |
|-----------|---------|-------------|
| `-i` | 10 | Measurement iterations |
| `-wi` | 5 | Warmup iterations |
| `-f` | 3 | Forks (JVM restarts) |
| `-t` | 1 | Threads |
| `-tu` | ns | Time unit (ns, us, ms, s) |
| `-prof` | none | Profiler (gc, stack, async) |

---

## 3. Methodology (the rules every published number follows)

### 3.1 Hardware + environment disclosure

Every chart or table in a published report is preceded by a full-disclosure paragraph (Photon paper
format, SIGMOD 2022). Minimum contents:

- Hardware: CPU model + core count + RAM + storage backend (NVMe SSD vs network).
- JDK: vendor + version + GC algorithm (e.g. `Temurin 21.0.7, G1GC`).
- Scala version + sbt version + project git SHA.
- Spark version (currently 4.1.2) + every non-default `spark.sql.*` config.
- Scale factor + data location (local FS vs S3) + file format (Parquet/text).
- Number of runs and which statistic is reported.
- Single-node or cluster + worker count.

### 3.2 Statistical treatment

Following Georges et al., OOPSLA 2007, *Statistically Rigorous Java Performance Evaluation*:

- **JMH microbenchmarks**: 3 forks, 5 warmup + 10–15 measurement iterations, each 1 s. JMH reports
  mean + CI; report both. Declare "no significant difference" when CIs overlap.
- **End-to-end queries**: 1 warmup run discarded, then min-of-3 with caches dropped between runs (the
  ClickBench pattern). Report the min plus all three raw values in the appendix.
- **Aggregate speedup across queries**: geometric mean (Eyerman et al. CAL 2024 — arithmetic mean of
  ratios is mathematically wrong).
- **Allocation profiling**: `-prof gc`, report `gc.alloc.rate.norm` (bytes per op).

### 3.3 Required ablations

Each headline end-to-end number is reported with at least these axes varied:

| Axis | Values | Why |
|---|---|---|
| `spark.sql.codegen.wholeStage` | `true` (default), `false` | ON is realistic; OFF isolates encoder cost from query-plan codegen. |
| `spark.sql.adaptive.enabled` | `true` (default), `false` | AQE can mask encoder gains by reshuffling. |
| Master URL | `local[1]`, `local[*]` | Single-thread isolates per-row cost; multi-thread shows realistic throughput. |

Document but don't ablate: `spark.sql.shuffle.partitions`, `autoBroadcastJoinThreshold`,
`files.maxPartitionBytes`, `spark.serializer`, `memory.offHeap.*`, `execution.arrow.*`.

### 3.4 Reporting format

- **Per-query bar chart** (Photon style): one bar per system per query; log-scale y when range > 5×;
  error bars at CI.
- **Raw timing matrix** (ClickBench style): per-query, per-system, all runs visible.
- **Aggregate speedup**: geometric mean, with the caveat that geomean compresses outliers.
- **Anti-patterns (do not do)**: single-query headline, "up to N× faster" without distribution,
  microbenchmark labeled as end-to-end, aggregate-only with no per-query breakdown, mean-with-no-CI,
  missing Spark config disclosure.

### 3.5 Honesty rules

- Cite Frameless's "implicit-derived encoders perform identically to reflection-based" claim and
  state exactly what this work tests about it.
- Document at least one workload or scale factor where ProtoCatalyst doesn't win, if one exists.
- The migration-path discussion must acknowledge real costs: Scala 3 stdlib migration, Spark's
  existing 2.13 user base, and the SemanticDB / Scalafix toolchain lag behind the SIP-51 escape hatch
  applied in `build.sbt` (see [`INFRASTRUCTURE.md`](INFRASTRUCTURE.md) §3.2).

### 3.6 Reproducibility contract

A run is "credible" only if: (1) the disclosure paragraph exists; (2) it was produced by
`./scripts/bench.sh sf=N` from a clean checkout at a public git SHA; (3) raw JMH JSON +
end-to-end CSV are attached; (4) for cloud runs, the EC2 setup + AMI ID + instance type are
documented so the numbers reproduce on a fresh instance.

### 3.7 Scale factors

| SF | `lineitem` rows | Approx size | Role |
|---:|---:|---:|---|
| 0.01 | ~60K | ~10 MB | Smoke fixture, committed. |
| 1 | ~6M | ~1 GB | JMH encoder microbenchmark. |
| 10 | ~60M | ~10 GB | Local-Mac headline end-to-end. |
| 100 | ~600M | ~100 GB | EC2 headline. Publication-quality. |

We don't publish SF≥1000 unless we get a cluster.

---

## 4. Cloud runs (EC2) for publication-quality numbers

Local-Mac numbers are fine for development and SF≤10. For SF=100 (~100 GB lineitem) and the
single-box headline numbers committers expect, use one EC2 `m6i.8xlarge` (32 vCPUs Ice Lake/AVX-512,
128 GB RAM, 12.5 Gbps). ~$1.54/hr; a full SF=100 sweep is ~90 min (~$2.50/run).

### 4.1 Instance setup

```sh
# m6i.8xlarge, Ubuntu 24.04 LTS (amd64), 500 GB gp3 root.
# AMI (us-east-1): ami-053b0d53c279acc90 — Canonical Ubuntu 24.04 amd64.
ssh -i ~/.ssh/your-key.pem ubuntu@<public-ip>

sudo apt-get update && sudo apt-get install -y openjdk-21-jdk git make gcc unzip

# sbt via Coursier
curl -fLo cs https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz
gunzip cs && chmod +x cs && ./cs setup -y && source ~/.profile
sbt --version   # confirm 1.12.x

git clone https://github.com/<org>/ProtoCatalyst.git && cd ProtoCatalyst
git checkout <commit-sha>
sbt 'encoderSpark/test'   # prime caches + verify
```

### 4.2 Running the benchmark

```sh
./scripts/bench.sh 100        # full SF=100 run, ~90 min
# results land in results/<timestamp>-sf100/:
#   disclosure.txt   full hardware/JVM/git disclosure
#   micro-*.json     JMH JSON for our serializer + Spark's encoder
#   queries.csv      end-to-end query timings

# Copy back from your laptop:
scp -i ~/.ssh/your-key.pem -r ubuntu@<public-ip>:~/ProtoCatalyst/results/<ts>-sf100 ./
```

Before publishing any cloud number: `disclosure.txt` shows JDK 21, `arch=x86_64`, `cores=32`,
`RAM=124+ GB`; git SHA matches; JMH JSON has non-zero scores; `queries.csv` has 0 `error` rows.

**Cost discipline.** Stop the instance immediately after copying results ($1.54/hr idle = $37
overnight). Spot is ~60% cheaper but a 90-minute run killed at minute 89 is expensive in time —
on-demand for the benchmark itself.

### 4.3 Cross-architecture validation (Graviton / Intel / AMD)

A reviewer's first objection to a microbenchmark is *"it doesn't generalize."* To answer that, run
the same `bench.sh` on three current-gen CPU families and confirm the ratios hold:

| Label | Instance | CPU | arch |
|----------|---------------|----------------------------|-------|
| graviton | `c7g.4xlarge` | AWS Graviton3 (Neoverse V1) | arm64 |
| intel | `c7i.4xlarge` | Intel Sapphire Rapids | amd64 |
| amd | `c7a.4xlarge` | AMD EPYC (Genoa) | amd64 |

`scripts/ec2-cross-arch.sh` provisions one instance, rsyncs the working tree (no private-repo clone
needed), installs JDK 21 + sbt, runs `bench.sh`, copies results back, and **always terminates the
instance** (EXIT trap). It auto-creates an EC2 key pair and a security group allowing SSH from your
current public IP only.

```sh
# Credentials + region configured (aws configure / AWS_PROFILE).
TS=$(date -u +%Y%m%dT%H%M%SZ)

# Dry-run first — prints the launch plan, spends nothing.
scripts/ec2-cross-arch.sh --label graviton --instance-type c7g.4xlarge --cpu-arch arm64 \
  --sf 1 --out results/cross-arch-$TS/graviton --dry-run

# Run all three (parallel — each is independent and self-terminating).
scripts/ec2-cross-arch.sh --label graviton --instance-type c7g.4xlarge --cpu-arch arm64 \
  --sf 1 --out results/cross-arch-$TS/graviton &
scripts/ec2-cross-arch.sh --label intel    --instance-type c7i.4xlarge --cpu-arch amd64 \
  --sf 1 --out results/cross-arch-$TS/intel &
scripts/ec2-cross-arch.sh --label amd      --instance-type c7a.4xlarge --cpu-arch amd64 \
  --sf 1 --out results/cross-arch-$TS/amd &
wait

scripts/cross_arch_report.py \
  graviton=results/cross-arch-$TS/graviton \
  intel=results/cross-arch-$TS/intel \
  amd=results/cross-arch-$TS/amd
```

Cost ~$0.58–0.73/instance-hour; a full SF=1 `bench.sh` is ~60–90 min, so a three-arch sweep is
roughly **$3–5 total**. What to look for: the derivation-cost ratios (REPORT §9) and the per-row
ceiling ratios (REPORT §10) should be stable across all three arches. Divergence >~15% on one arch is
a finding worth documenting, not hiding.

Cluster (shuffle-heavy SF≥1000) runs are future work; the encoder narrative published now doesn't
require them.

---

## 5. Interpreting results

- **Score**: lower ns/op is better; higher ops/s is better.
- **Error (±)**: CI half-width — smaller is more reliable. Overlapping CIs ⇒ "no significant
  difference."
- **Run on the same machine, same JVM settings, warm up sufficiently, use multiple forks** when
  comparing the two sides.

Sample output:

```
Benchmark                                     Mode  Cnt   Score   Error  Units
InlineSerializerBenchmarks.serializeSimple    avgt   30   28.3 ±  0.5  ns/op
InlineSerializerBenchmarks.serializePerson    avgt   30  101.2 ±  2.1  ns/op
```

> **Note on historical per-row tables.** Earlier drafts of this file carried hand-entered per-row
> speedup tables (simple/nested/wide serialize-deserialize). Those have been superseded by the
> measured, cross-arch-validated numbers in the archived
> [`REPORT_encoder_perf.md`](archive/REPORT_encoder_perf.md) (UnsafeRow geomean 1.16×, Arrow geomean
> 0.92× with ~43% less allocation). Treat that report — not memory — as the source of truth for
> per-row results, and `REPORT.md` §9 for derivation results.

---

## 6. Why the compile-time path is faster

1. **Compile-time type specialization** — `inline erasedValue` generates specialized code; no runtime
   type dispatch (`isInstanceOf` chains).
2. **No expression-tree interpretation** — direct `productElement` field access instead of building
   and walking Catalyst `Expression` trees.
3. **Zero runtime schema derivation** — the schema is fixed at compile time via `Mirror`, where Spark
   walks `TypeTag` reflection at runtime (and re-derives per call, uncached — REPORT §9b).

For the full mechanism and the measured `scalac`-time cost of moving the analysis to compile time,
see [`REPORT.md`](REPORT.md) §6 and §9c.

---

## 7. Adding new benchmarks

1. Create the class in `benchmarks/src/main/scala/protocatalyst/bench/`.
2. Add test data to `BenchmarkData.scala` if needed.
3. Use JMH annotations matching the publication config:

```scala
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3) @Warmup(iterations = 5, time = 1) @Measurement(iterations = 10, time = 1)
class MyBenchmarks:
  @Benchmark def myOperation(): Unit = ...
```

Benchmarks are not run in CI by default (too slow). Quick smoke test:

```bash
sbt "benchmarks/Jmh/run -i 1 -wi 1 -f 1 InlineSerializerBenchmarks.serializeSimple"
```

---

## Sources

- Georges et al., *Statistically Rigorous Java Performance Evaluation*, OOPSLA 2007 —
  https://dri.es/files/oopsla07-georges.pdf
- Behm et al., *Photon: A Fast Query Engine for Lakehouse Systems*, SIGMOD 2022 —
  https://people.eecs.berkeley.edu/~matei/papers/2022/sigmod_photon.pdf
- ClickBench — https://github.com/ClickHouse/ClickBench
- DuckDB benchmarking guidelines — https://duckdb.org/docs/current/guides/performance/benchmarks
- TPC-H Standard Specification v3.0.1 —
  https://www.tpc.org/TPC_Documents_Current_Versions/pdf/TPC-H_v3.0.1.pdf
- Sinchenko, *Benchmarking Spark with JMH* —
  https://semyonsinchenko.github.io/ssinchenko/post/spark-and-jmh/
- Eyerman et al., *R.I.P. Geomean Speedup*, UGent CAL 2024 —
  https://users.elis.ugent.be/~leeckhou/papers/CAL-2024-geomean.pdf
