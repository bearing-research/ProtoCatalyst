# Cross-engine TPC-H benchmark — results

Paper-oriented results for the Truffle execution backend, measured against **Spark 4.1 as the
correctness *and* performance reference**. Three engines consume the **same** optimizer-produced
`ProtoPhysicalPlan` over the **same** data, so the comparison is apples-to-apples:

- **interpreter** — the project's `PhysicalPlanExecutor` (Scala, column-at-a-time over Arrow).
- **truffle** — `TypedTruffleCompiler` → Truffle AST (partial evaluation on the JVM; interpreter-mode
  in the native image — see §3).
- **spark** — Apache Spark 4.1, whole-stage codegen on, AQE on, `local[*]`.

Workload: the slice that exercises filter, projection, multi-way joins (up to the 6-way Q5),
`SUM(expr)`, GROUP BY, HAVING-free aggregation, ORDER BY (incl. by alias), and LIMIT —
**TPC-H Q1, Q3, Q5, Q6, Q10**. Correctness of all five is validated against the Spark oracle
(`TpchSparkOracleSpec`); see §4.

Harnesses (reproducible):
- steady-state: `truffleBackend/runMain …bench.TpchBench --sf <sf>` (interpreter + truffle)
- spark: `benchmarkSpark/runMain …tpch.TpchCrossEngineSpark --sf <sf> --mode {steady|cold|oracle}`
- cold-start native: `native/build-tpch-native.sh` → `./native/tpch-native --query <Q> --sf <sf>`
- data → Arrow IPC (for the native driver): `truffleBackend/runMain …bench.TpchArrowConvert --sf <sf>`

Methodology: a fixed plan is compiled once per engine, then `warmup` untimed iterations (JIT / partial
evaluation warm up) followed by timed iterations; we report the **median** (and min / p90) per Georges
et al. (OOPSLA 2007). Cold start is whole-process wall time, fresh process per measurement.

Hardware/software for the numbers below: Apple Silicon (arm64), JDK 21 (Oracle GraalVM 21.0.11),
Spark 4.1.2 / Scala 2.13, Arrow 18.3. Single machine; disclose per run.

---

## 1. Steady-state (warm), SF = 0.01

Median execution latency, **lower is better**. `×interp` = interpreter / truffle.

| Query | interpreter | truffle | spark | truffle ×interp |
|------:|------------:|--------:|------:|----------------:|
| Q1    |   74.8 ms   | 37.0 ms | 96.9 ms |  2.02× |
| Q3    |  456 ms     | 123 ms  | 116 ms  |  3.70× |
| Q5    |  255 ms     |  73 ms  | 163 ms  |  3.48× |
| Q6    |   30.6 ms   | 22.7 ms |  38.4 ms |  1.35× |
| Q10   |  709 ms     |  97 ms  |  97.2 ms |  7.32× |

Reading: at this small scale the Truffle backend is **1.3–7.3× faster than the interpreter** (largest
gains on the join-heavy queries) and **competitive with or ahead of Spark** — Spark's fixed per-query
overhead dominates when the data is tiny. This is *not* a claim that the single-threaded Truffle
backend beats Spark at scale; see §2.

## 2. Steady-state (warm), SF = 1 — the scale crossover

Median execution latency. Spark for all five; Truffle/interpreter shown for Q6 (the cleanest single
scan) — the others are scan-bound the same way and the interpreter baseline is impractical to iterate
at SF=1 (it allocates Arrow intermediates per `execute` without freeing them, exhausting off-heap
memory; the Truffle path produces GC'd heap rows and does not leak, but is decode-bound — see below).

| Query | spark | truffle (JVM, PE) | interpreter | truffle ÷ spark |
|------:|------:|------------------:|------------:|----------------:|
| Q1    |  696 ms | **1343 ms** median (best 630) | — | 1.9× |
| Q3    |  689 ms | — | — | |
| Q5    | 1073 ms | — | — | |
| Q6    |  170 ms | **1118 ms** median (best 840) | 3503 ms | 6.6× |
| Q10   |  715 ms | — | — | |

Three optimizations narrowed the SF=1 gap from its original state, in order of impact. The Q6
progression: **6.1 s → 1.96 s → ~1.5 s → 1.12 s** (≈ 5.4× total); the Truffle backend is now ~1.9× off
Spark on Q1 and ~6.6× on Q6, down from ~36×.

1. **Column-pruned decode.** The leaf decoded *every* column; it now decodes **only the columns a query
   references** (Q6 touches 4 of lineitem's 16). Q6 **6.1 s → 1.96 s (3.1×)**, moving the Truffle
   backend from *slower* than the interpreter (which reads Arrow directly) to **faster** than it. PE
   accelerates the AST, not the decode — pruning the decode is what mattered.
2. **Parallel scan.** The leaf row-slices a large table across the fork/join pool (each slice with its
   own AST, since Truffle nodes aren't thread-safe). Best case ~0.72 s on Q6, but GC-noisy — because
   the money columns are `DECIMAL(38,18)` and the `DecimalVector` decode boxes **one `BigDecimal` per
   cell**, leaving the leaf allocation/GC-bound. Threads can't speed up GC.
3. **Decimal → double decode.** A compile-time analysis decodes a decimal column used *only* as
   `CAST(col AS DOUBLE)` straight into a `double[]` (no per-cell `BigDecimal`); exact-decimal columns
   are untouched. The TPC-H revenue columns are all cast-to-double, so this removed most of the churn:
   **Q1 2.49 s → 1.34 s** (both money columns cast → both `double`); **Q6 ~1.5 s → 1.12 s** (only
   extendedprice cast — discount/quantity stay decimal, used bare in the filter).

**Spark still leads** (parallel scan + WSCG vs a single JVM process), and there's residual GC variance
(p90 ~1.8–2.1 s) from the remaining boxed values (decimal filter columns, result rows, group maps). The
remaining headroom is executing directly over Arrow with no materialized arrays, and recognizing
"decimal compared against a double literal" as double-safe (would convert Q6's filter columns too).

None of this is the prototype's thesis: the contribution is the **cold-start / small-or-filtered-data**
regime (§3), where decode + single-thread costs are immaterial and startup dominates. But the SF=1 gap
going from ~36× to ~2–7× shows the backend is not architecturally far from Spark's warm throughput.

---

## 3. Cold start — the headline axis

Whole-process wall time to a user's **first result** (fresh process; includes everything: process
start, SQL parse, optimize, physical-plan, compile/execute; for Spark, SparkSession init + WSCG
codegen). The native driver runs the *entire* compiler at runtime — the `spark.sql(userInput)`
analogue — in a closed-world binary with **zero runtime bytecode generation**.

### SF = 0.01 (startup-dominated)

| Engine | Q6 | Q5 (6-way join) |
|---|---:|---:|
| Spark 4.1 (JVM + WSCG codegen) | 4.70 s | ~4.7 s |
| this compiler, on the JVM      | 1.08 s | 1.33 s |
| **this compiler, native-image** | **~0.10 s** | **~0.27 s** |

≈ **47× faster cold start than Spark**, ≈ **10× faster than the same code on the JVM** (JVM startup +
class-loading eliminated; the native binary's whole-process wall ≈ its in-process compute time).

### The regime boundary (SF = 1, execution-dominated)

The native binary is built in **Truffle interpreter mode** (the optimizing runtime is dropped to
sidestep the partial-evaluation blocklist — the honest config for *cold* start, whose single short
execution is interpreted before PE would engage). That is ideal when startup dominates, but at SF=1 a
single query scans 6 M rows single-threaded with no PE, so **execution** dominates and the native
binary is slow (Q6 ≈ 24 s, Q1 ≈ 34 s). Spark's parallel scan + the absence of in-binary PE flip the
result.

**Takeaway:** the AOT cold-start advantage is real and large for the **short-query / small-or-filtered
data** regime — serverless, edge, interactive first-result, dashboards over modest data — and does not
extend to heavy multi-GB scans. Steady-state, in-binary PE (the separate `Q6Native` result) is what
would close the execution gap at scale; wiring it into the full pipeline requires `@TruffleBoundary`
hardening of the node library (future work).

---

## 4. Correctness (Spark oracle)

All five queries produce **Spark-correct results** on both the interpreter and the Truffle backend
(`TpchSparkOracleSpec`, validated against committed Spark-generated fixtures). This is the check that
`truffle == interpreter` parity structurally cannot do — both share one front-end. It drove out a real
optimizer bug (`ConstantPropagation` was folding the `col = literal` predicate that *defines* a
constant into `TRUE`, silently dropping the filter — Q5 returned all 25 nations instead of the 5 in
ASIA; Q3/Q10's `mktsegment`/`returnflag` filters were dropped under `LIMIT`).

Known cosmetic gap (validated around, not a computation error): the front-end emits a grouped query's
columns as `[grouping keys…, aggregates…]` rather than SELECT-list order, so Q3/Q10 carry identical
values in a different column order than Spark.
