# The Truffle execution backend — a case study

A consolidated write-up of the Truffle query-execution backend built on ProtoCatalyst: what it is, how
it works, where it stands, and where it could go. This is the narrative companion to two detail docs —
[`TRUFFLE_EXPLORATION.md`](TRUFFLE_EXPLORATION.md) (the phased design history and per-phase status notes)
and [`BENCHMARK_RESULTS.md`](BENCHMARK_RESULTS.md) (the full cross-engine numbers and methodology).

---

## 0. Positioning (read this first)

This backend is a **research prototype and case study**, **not** a Spark replacement and not a product.
It is the *secondary* track of ProtoCatalyst; the primary track is the reflection-replacement thesis
(Scala 3 compile-time encoder derivation to unblock Spark's own Scala 3 migration), which has a concrete
upstream path and does not depend on execution throughput.

The honest one-line claim: **a runtime-planned SQL query can execute inside a GraalVM native image with
zero runtime bytecode generation and partial-evaluation-quality code — something the Janino/whole-stage-
codegen approach that Spark, Trino, Drill, Flink, and Calcite all use cannot do.** The payoff is
**cold-start latency** (≈47× faster than Spark to first result at small scale), which matters for
serverless / edge / interactive-first-result workloads. It is explicitly **not** a steady-state
throughput claim — at scale a single-threaded interpreter loses to Spark's parallel, code-generated
execution, and competing there would be pointless.

Everything below is framed by that boundary.

---

## 1. Architecture

### 1.1 Where it sits

ProtoCatalyst compiles SQL to an engine-independent IR (`ProtoLogicalPlan` → optimizer →
`ProtoPhysicalPlan`). Multiple backends consume the *same* `ProtoPhysicalPlan`:

```
                         ┌─ PhysicalPlanExecutor   (Scala interpreter, column-at-a-time over Arrow)
SQL ─► parse ─► optimize ─► PhysicalPlanner ─► ProtoPhysicalPlan ─┼─ DataFusionBackend       (SQL transpile → ADBC Flight SQL)
                         └─ TypedTruffleCompiler  (this backend: → Truffle AST → execute over Arrow)
```

Because the backend is just another consumer of `ProtoPhysicalPlan`, it inherits the whole front end
(parser, 41-rule optimizer, planner) for free, and it can be validated against the interpreter and —
crucially — against **Spark as the correctness oracle**.

### 1.2 Two execution layers

The backend deliberately has two layers, because partial evaluation pays off in different ways for
single-table scans vs. multi-input operators:

- **The Truffle-PE leaf** (`truffle-exec` + `TypedTruffleCompiler`) — a single-table `Filter` /
  `Project` / global or grouped `Aggregate` over one `TableScan`. This is compiled to a Truffle AST and
  executed over decoded Arrow columns with **partial evaluation**: Graal specializes and compiles the
  AST to tight machine code. This is the part that "earns" Truffle.

- **The Scala composition layer** — joins, group-by-over-join, and `WHERE`/`HAVING`/`SELECT` *over* a
  join, plus window functions. These run row-at-a-time in plain Scala over boxed rows
  (`Array[AnyRef]`), evaluated by `RowEval` (a small SQL-3VL expression interpreter). This layer was
  built for **correctness and composition**, not throughput — and, as §3 shows, it is the current
  performance frontier.

A **table-catalog execution model** ties them together: every query is `run(tables: Map[String,
Batch])`. A leaf resolves its own table by name; a join runs both child queries against the catalog and
combines. This is what lets operators compose arbitrarily (a filter under a join, an aggregate over a
join, a join feeding a join).

### 1.3 The typed node library (`truffle-exec`, Java)

Truffle's DSL is a Java annotation processor, so the node library is Java; the compiler/driver is Scala
3. The seam:

- **`SqlTypes`** — a Truffle `@TypeSystem` over `{long, double, boolean, String, BigDecimal, SqlNull}`,
  with `@ImplicitCast`s `long → double` and `BigDecimal → double` encoding SQL's numeric widening (so a
  decimal column compared/combined with a double specializes to the double path, matching the
  interpreter's `(Number, Number) → doubleValue` rule).
- **`TExpr`** — the base expression node over a `VirtualFrame`; `@Specialization` methods per operand
  type give the uninitialized → typed → generic machinery the DSL generates.
- **Three-valued NULL logic** — a NULL operand is threaded via `UnexpectedResultException` on the typed
  path, falling back to a generic/NULL specialization; a predicate "holds" only when it is exactly TRUE.
- **`RootNode`s** — `TypedFilterProjectRoot`, `TFilterCountRoot`, `TypedGlobalAggRoot`,
  `TypedGroupedAggRoot` — the call targets the compiler builds.

Full DSL detail: [`DSL_REFERENCE.md`](DSL_REFERENCE.md); deeper rationale and the
language-choice/cross-version notes: [`TRUFFLE_EXPLORATION.md`](TRUFFLE_EXPLORATION.md) §2–§3.

---

## 2. How it works

### 2.1 Compilation and execution (the leaf)

```
ProtoPhysicalPlan
   │  TypedTruffleCompiler.compile
   ▼
TypedQuery  (a tree of leaves + composition operators)
   │  .run(tables)
   ▼
for a leaf:  decode Arrow ─► typed columns ─► callTarget.call ─► result rows
```

A leaf's `run`:

1. **Decode** the input `Batch` (Arrow `VectorSchemaRoot`) into the typed column model
   (`TypedColumnsDecoder`): integer/date vectors → `long[]`, floating → `double[]`, decimal →
   `BigDecimal[]` (or `double[]` — see §3.2), each with a null mask. Two compile-time analyses prune
   this: **only referenced columns** are decoded, and **decimal columns used only as `double`** decode
   straight into `double[]`.
2. **Execute** the Truffle call target over the typed columns. The DSL-generated dispatch specializes on
   first calls; Graal's partial evaluation then compiles the specialized AST. On the JVM this is
   JIT-quality; in a native image (§2.3) it can be PE-in-binary.
3. **Harvest** the column-major output into rows.

### 2.2 Composition (joins, group-by, window)

Above a leaf, the Scala composition layer operates on `Array[AnyRef]` rows:

- **`JoinQuery`** — inner + LEFT/RIGHT/FULL outer equi-hash-join + a residual condition (the condition
  is part of the match test, which is what makes outer joins correct); NULL keys never match;
  nested-loop / cross / non-equi joins are the keyless special case. Column references resolve
  qualifier-aware (`n.regionkey` vs `r.regionkey`), mirroring the executor's `resolveColumn`.
- **`RowFilterQuery` / `RowProjectQuery` / `RowAggregateQuery`** — WHERE/HAVING/SELECT/GROUP BY over a
  join, evaluating expressions per row through `RowEval`.
- **`WindowQuery`** — ranking, aggregate-`OVER`, `LAG`/`LEAD`/`NTILE`, `FIRST`/`LAST`/`NTH_VALUE`.

### 2.3 The native-image path (the point)

The whole pipeline — `SQL → parse → optimize → plan → Truffle AST → execute` — is native-imaged in
`TpchNativeDriver`. A query handed in as a *SQL string at runtime* (the `spark.sql(userInput)` analogue)
runs end-to-end in a closed-world binary with **no runtime bytecode generation**. Making this build
work required: reading Arrow IPC (parquet-mr/Hadoop don't native-image), stripping unreachable
Flight/ADBC/netty, build-time init of slf4j and Arrow's `MemoryUtil` (baking the `Buffer.address`
offset), and tracing-agent reflection config. The build script and config live in `native/`.

Two native results, kept distinct:
- **`Q6Native`** — a single hardcoded plan, built *with* the optimizing runtime, proving **partial
  evaluation survives into the binary** (`runtime : Oracle GraalVM`, not "Interpreted") — AOT-safe *and*
  JIT-quality at once.
- **`TpchNativeDriver`** — the full pipeline, built in Truffle **interpreter mode** (the optimizing
  runtime dropped to sidestep the PE blocklist). This is the honest config for *cold* start, whose
  single short execution is interpreted before PE would engage.

### 2.4 Correctness — Spark as oracle

`TpchSparkOracleSpec` validates **both** project engines (interpreter and Truffle) against Spark 4.1
results (pre-generated, committed TSV fixtures), order- and column-order-insensitively with numeric
tolerance. This is the check `truffle == interpreter` parity structurally *cannot* do — both share one
front end, so a front-end bug agrees with itself while disagreeing with Spark. It paid off: it (and
profiling) drove out **three optimizer correctness bugs**, each an "optimizer simplification returns
empty/true" of the same family:
1. **`ConstantPropagation`** folded the `col = literal` predicate that *defines* a constant into TRUE,
   silently dropping the filter (Q5 returned all 25 nations instead of ASIA's 5).
2. The transform dropped a single aggregate's **alias**, so `ORDER BY revenue` couldn't resolve.
3. **`PushDownPredicates.collectColumnRefs`** returned no columns for a base table, so filters never
   pushed through joins — Q3 built the full 6 M-row product before filtering to ~30 k.

All three fixes improve *every* engine that consumes the plan.

---

## 3. Where we are

### 3.1 Coverage

Feature-complete enough to run realistic multi-operator analytical SQL: long/double/string/decimal/date
types with 3VL nulls; ~40 expressions (arithmetic, comparisons, `CASE`/`COALESCE`/`NULLIF`, real
`CAST`, `IN`, `LIKE`, string/math/date functions); filter, projection, global + grouped aggregate,
`ORDER BY`/`LIMIT`/`DISTINCT`; inner + outer + cross/non-equi joins; the full window family; and full
composition (the TPC-H Q3/Q5/Q10 shape: filter → multi-way join → group-by → having → order-by →
limit). **75 backend parity tests** plus the Spark-oracle suite.

### 3.2 Performance — honestly

Two axes, two completely different stories. Full numbers and methodology in
[`BENCHMARK_RESULTS.md`](BENCHMARK_RESULTS.md).

**Cold start (the contribution).** Whole-process wall time to first result, SF=0.01:

| engine | Q6 |
|---|---:|
| Spark 4.1 (JVM + WSCG codegen) | 4.70 s |
| this compiler, on the JVM | 1.08 s |
| **this compiler, native-image** | **~0.10 s** |

≈ **47× faster cold start than Spark** (≈10× faster than the same code on the JVM — JVM startup +
class-loading eliminated). The advantage is **scale-dependent**: it is real and large when *startup*
dominates (short queries, small or filtered data — serverless/edge/interactive); at SF=1 *execution*
dominates and, since the cold native build has no PE, it loses. That boundary is the honest finding,
not a flaw to hide.

**Steady-state (not the contribution, but characterized).** Warm median, SF=1:

| query | Spark | this backend | vs Spark | bound by |
|---|---:|---:|---:|---|
| Q1 | 696 ms | 911 ms | ~1.3× | leaf (optimized) |
| Q6 | 170 ms | 538 ms | ~3.2× | leaf (optimized) |
| Q3 | 689 ms | ~7–9 s | ~10× | boxed-row join layer |
| Q5 | 1073 ms | ~10 s | ~9.5× | boxed-row join layer |
| Q10 | 715 ms | ~8 s | ~11× | boxed-row join layer |

The single-table aggregate queries are **within ~1.3–3× of Spark's warm, parallel, code-generated
throughput** — strong for a single-threaded interpreter — after four leaf optimizations took Q6 from
6.1 s → 0.54 s (~11×): column-pruned decode, parallel scan (row-slice across the fork/join pool, fresh
AST per slice), decimal→double decode, and decimal-vs-literal-is-double-safe. The join queries remain
~10× behind, **down from ~38–92×** after the pushdown fix; what's left is the boxed-row composition
layer (§3.3).

### 3.3 What we learned about the bottlenecks

Profiling (`-Dtruffle.profile`) localized every remaining cost:

- **Single-table queries are leaf-bound**, and the leaf cost was the *decode*, not the AST — pruning
  unused columns and avoiding per-cell `BigDecimal` boxing (decode-as-double) mattered far more than
  partial evaluation, which accelerates the AST.
- **Join queries are bound by the boxed-row composition layer**: the leaf materializes millions of rows
  of boxed `java.lang.Double` / `BigDecimal` cells, and *GC of those objects* dominates (Q3's warm
  variance is 5.8–9.5 s, pure GC noise). We confirmed this directly: switching the row container from
  `Vector[AnyRef]` to flat `Array[AnyRef]` (cheaper to build/index/concat) was correct and is a
  prerequisite for columnar work, but **did not move the join numbers** — the cost is the *boxing*, not
  the container.

The most important methodological lesson: **profile before optimizing, and validate against an external
oracle.** Both the "join layer is slow" assumption (it was actually a filter-pushdown planning bug) and
the three correctness bugs were found that way, not by intuition.

---

## 4. Future direction

In rough order of leverage, and with honest scope:

1. **Typed columns through the composition layer (the real remaining lever).** Eliminate the boxing
   that bounds the join queries: have the leaf emit `double[]`/`long[]`/`AnyRef[]` columns instead of
   boxed rows, and execute join + aggregate over typed columns with late materialization (gather by row
   index). This is the genuine "columnar join" and would likely take Q3 from ~9 s toward ~3–4 s — but it
   requires changing the Java Truffle output nodes (they currently box into `AnyRef[]`) and a parallel
   typed-columnar implementation of join/aggregate. **Substantial**, and the join would still be
   single-threaded, so it would close the gap, not erase it.

2. **PE in the native binary for the full pipeline.** Today the full-pipeline native image runs in
   Truffle interpreter mode (fine for cold start). Wiring the optimizing runtime in requires
   `@TruffleBoundary` hardening of the node library to satisfy the PE blocklist — which would give
   native *steady-state* (not just cold-start) at the AST level.

3. **Parallel + better join.** Parallelize the inner-join probe and the group-by-over-join (the
   partition-then-merge pattern already used by the parallel leaf aggregate); single-column key fast
   path to avoid the `List` key allocation. Independent of (1), additive.

4. **Broaden the regime evidence.** A serverless/FaaS cold-start harness (the regime where the
   contribution actually lives), and a second backend or more of TPC-H to generalize the case study.

### What this is *not* going to become

It will not become a throughput-competitive Spark alternative, and pursuing that would be a poor use of
effort — DuckDB/Velox/Spark already own warm analytical throughput, and fast *embeddable* analytics is
DuckDB's territory. The defensible, novel value is narrow and specific: **AOT-compiled, runtime-planned,
PE-quality query execution** and the **cold-start regime** it unlocks, demonstrated for the first time
for a big-data execution engine. That is a paper-grade contribution on its own axis; it is not a
product, and the steady-state numbers should never be framed as one.

The higher-leverage, more practical contribution of the surrounding project remains the
**reflection-replacement thesis** (compile-time encoder derivation to unblock Spark's Scala 3
migration) — see [`../scala3-encoder/REPORT.md`](../scala3-encoder/REPORT.md).
