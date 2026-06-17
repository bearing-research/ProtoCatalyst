# Cross-Backend Execution: One Plan, Two Engines

This document describes the cross-backend TPC-H harness — a concrete demonstration of the
*engine-independent compiler* thesis. A query is compiled **once** (SQL → `ProtoLogicalPlan`) and the
**same** plan is executed on two unrelated engines — the standalone Local Arrow executor and Apache
DataFusion — with their results compared. If the IR is a genuine engine-neutral interchange (the
"LLVM-for-queries" analogy), the two engines must agree.

The harness lives in
[`executor/.../tpch/TpchCrossBackendSuite.scala`](../../executor/src/test/scala/protocatalyst/executor/tpch/TpchCrossBackendSuite.scala).

## The two paths from one plan

Both backends start from the identical compiled plan:

```
SQL text
  │  SqlParser.parse(sql): Either[String, SqlStatement]
  ▼
SqlStatement (AST)
  │  AstToProtoTransform.transformStmt(stmt, schema, table)      ── single-table
  │  AstToProtoTransform.transformStmt(stmt, catalog)            ── multi-table (joins)
  ▼
ProtoLogicalPlan
  │  Optimizer.optimize(plan)                                    ── 41-rule SQL optimizer
  ▼
ProtoLogicalPlan  ◄─────────── the engine-neutral artifact ───────────►
  │                                              │
  │ Local Arrow backend                          │ DataFusion backend
  ▼                                              ▼
PhysicalPlanner.plan(plan)                  SqlGenerator.generate(plan)  ── ProtoLogicalPlan → SQL
  │  ProtoPhysicalPlan                          │  SQL text
  ▼                                              ▼
PhysicalPlanExecutor.execute(physical)      AdbcSqlBackend.executeSql(...)  ── ADBC Flight SQL
  │  Arrow VectorSchemaRoot                     │  gRPC → datafusion-flight-sql-server
  ▼                                              ▼
Batch  ─────────────────►  compare rows  ◄─────────────────  Batch
```

- **Local Arrow** — `PhysicalPlanner` lowers the logical plan to a `ProtoPhysicalPlan`, and
  `PhysicalPlanExecutor` interprets it directly over Arrow vectors (scan → filter/project → hash or
  sort-merge join → hash aggregate → sort). Decimal arithmetic uses arbitrary-precision `BigDecimal`.
- **DataFusion** — `SqlGenerator` transpiles the *same* `ProtoLogicalPlan` back to SQL, which
  `AdbcSqlBackend` ships over ADBC Flight SQL to a small Rust server
  ([`tools/datafusion-server`](../../tools/datafusion-server)) that runs DataFusion.
  `DataFusionBackend.execute(plan)` is literally `executeSql(SqlGenerator.generate(plan))`.

Because the comparison runs the *same compiled plan* through both, any disagreement is a real
semantic gap in the compiler (transpiler or executor), not a difference in how the query was written.

## Running it

The Local-only tests need just the TPC-H parquet; the cross-backend comparisons additionally need the
DataFusion server. Both degrade gracefully via `assume(...)` when their prerequisite is absent.

```bash
# 1. TPC-H parquet at data/tpch/sf-0.01-parquet/ (gitignored — TPC license; regenerate via scripts).
#    Each table is a *directory* of Spark-style part files; the harness concatenates the parts.

# 2. Start the DataFusion Flight SQL server, pointed at the data dir so it pre-registers the tables.
#    (datafusion-flight-sql-server has no DDL over the wire, so tables can't be CREATEd remotely.)
cd tools/datafusion-server && cargo run -- ../../data/tpch/sf-0.01-parquet

# 3. Run the harness.
sbt 'executor/testOnly protocatalyst.executor.tpch.TpchCrossBackendSuite'
```

## What it covers (12 cases)

| Case | Shape | Check |
|---|---|---|
| Q6 selection | project + filter (`BETWEEN`, `AND`, `<`) | Local; Local == DataFusion (row count) |
| Q6 global aggregate | `COUNT(*)` with no `GROUP BY` | Local == DataFusion |
| Q6 full (Local) | `DATE '…'` predicates + `SUM(extendedprice*discount)` | Local (exact decimal) |
| Q6 full revenue (double-cast) | `SUM(CAST(… AS DOUBLE) * CAST(… AS DOUBLE))` | Local == DataFusion (row-for-row) |
| Q1 grouped aggregate | `GROUP BY` 2 keys, 2 aggregates | Local == DataFusion (group count) |
| Q1 grouped + ORDER BY | adds deterministic order | Local == DataFusion (**row-for-row**) |
| Join (2-table) | `nation ⋈ region` | Local == DataFusion |
| Capstone | join ⋈ `GROUP BY` ⋈ `ORDER BY`, **ambiguous `name`** | Local == DataFusion (row-for-row) |
| Join³ (3-table) | `region ⋈ nation ⋈ customer`, group by region | Local == DataFusion (row-for-row) |
| LEFT OUTER | `customer ⟕ orders`, null right side | Local == DataFusion (row-for-row) |
| RIGHT OUTER | `orders ⟖ customer` mirror | Local == DataFusion (row-for-row) |

Row-for-row comparison normalizes numerics to `Double` (Local sums in `Double`, DataFusion in
`Decimal` — same value, different carrier) and compares with a small relative tolerance.

## Engineering findings (gaps the harness surfaced and closed)

Building the harness exercised paths that single-table, single-engine tests never did. Each gap below
was a real bug found by cross-backend disagreement, then fixed:

- **`DATE '…'` typed literals** — the lexer reads `DATE` as an identifier; the parser now recognizes
  `DATE` + string-literal → `SqlExpr.DateLit`, threaded through the AST, the macro lifter, and the
  transform (→ `LiteralValue.DateValue`).
- **Global aggregate** — `SUM`/`COUNT(*)` with no `GROUP BY` produced a `Project`; the transform now
  emits an `Aggregate` with empty grouping, and `SqlGenerator` omits the empty `GROUP BY`.
- **Per-table schema catalog** — the transform applied one schema to every table in the `FROM`, so
  joins over tables with shared column names couldn't resolve. `transformStmt` now threads a
  `catalog: Map[String, ProtoSchema]`; each table gets its own schema.
- **`aggregateExprs` contract** — the transform put grouping keys *and* aggregates in
  `Aggregate.aggregateExprs`, but both consumers (`SqlGenerator`, which prepends `groupingExprs`, and
  the executor's `AggregateOp`, which builds one accumulator per entry) expect aggregates **only**.
  Fixed to emit aggregates only.
- **Joins across subquery boundaries (transpiler)** — `SqlGenerator` wrapped a join child as
  `(SELECT * FROM a JOIN b …) AS __subquery`, which flattens table aliases, so `n.name` in the
  parent's SELECT/WHERE/GROUP BY no longer resolved. Joins are now *inlined* as bare FROM fragments
  (`renderFromSource`/`renderJoin`), keeping aliases in scope. `ORDER BY` over an aggregate likewise
  merges into a single `… GROUP BY n.name ORDER BY n.name` rather than wrapping.
- **Qualified columns across a join (executor)** — the Local join output carried bare column names,
  so `n.name` couldn't be disambiguated from another joined table's `name`. A scan under an explicit
  alias now qualifies its columns (`c.col`) via a schema-only view over the shared Arrow root, and
  `ExprEvaluator.resolveColumn` prefers an exact `qualifier.name` match (falling back to an
  unqualified-suffix match so unqualified references still resolve). Join *keys* already resolved
  correctly, since hash/sort-merge joins evaluate `leftKeys`/`rightKeys` against each input
  separately.

## Known divergences (engine-side, not compiler)

- **Decimal precision.** DataFusion's strict decimal arithmetic overflows on Q6's
  `SUM(extendedprice * discount)` — both operands are wide `DECIMAL(38,18)` and the product exceeds
  the max decimal precision; Local's arbitrary-precision `BigDecimal` does not. Computing the revenue
  in floating point (`CAST(… AS DOUBLE)`) sidesteps it, so the double-cast Q6 agrees across both
  backends; the exact-decimal form is asserted on Local only.
- **Unaliased same-named join columns.** Disambiguation relies on an explicit table alias. An
  *unaliased* join whose tables share a column name still resolves by bare suffix (first match) on the
  Local backend. Using aliases (the common case) resolves correctly.

## Why this matters

The harness is the operational evidence for the project's broader thesis: a single compile-time IR
that is genuinely engine-independent. The same `ProtoLogicalPlan` drives a hand-written Arrow
interpreter and, via SQL transpilation over ADBC, a completely separate production engine — and they
agree across selection, aggregation, multi-table and outer joins, ordering, and column-qualifier
resolution. See also [DESIGN.md](DESIGN.md) and the ADRs on
[an independent IR](../decisions/ADR-002-independent-ir.md).
