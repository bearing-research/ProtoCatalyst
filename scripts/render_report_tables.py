#!/usr/bin/env python3
"""Render the §11 (microbenchmark) and §12 (end-to-end) tables for REPORT.md
from a canonical results directory.

Usage:
    ./scripts/render_report_tables.py results/<utc-ts>-sf1/

Reads:
    micro-protocatalyst.json  — JMH output for our serializer
    micro-spark.json          — JMH output for Spark's ExpressionEncoder
    queries.csv               — end-to-end TPC-H query timings

Emits the markdown tables on stdout, ready to paste into REPORT.md
§11.2 (per-row throughput), §11.3 (allocation), §11.4 (step progression
— requires also reading historical /tmp/micro-step*.json files if
present), and §12 (DS-vs-DF table). Geomean rows included.
"""

import csv
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

TABLES = ["lineitem", "orders", "customer", "part"]
OPS = ["Serialize", "Deserialize", "Roundtrip"]


def load_jmh(path):
    """Return dict {(short_name, sf): (score, error, alloc_b_per_op)}."""
    with open(path) as f:
        blob = json.load(f)
    out = {}
    for b in blob:
        short = b["benchmark"].rsplit(".", 1)[-1]
        sf = b["params"]["sf"]
        score = b["primaryMetric"]["score"]
        err = b["primaryMetric"]["scoreError"]
        alloc = None
        for n, m in b.get("secondaryMetrics", {}).items():
            if "alloc.rate.norm" in n:
                alloc = m["score"]
                break
        out[(short, sf)] = (score, err, alloc)
    return out


def gmean(xs):
    return math.exp(sum(math.log(x) for x in xs) / len(xs)) if xs else 0.0


def emit_throughput_table(ours, spark, sf):
    """§11 per-row throughput table."""
    print("### Per-row throughput, by TPC-H table\n")
    print(f"| Benchmark | Ours (ns/op) | ± CI | Spark (ns/op) | ± CI | Speedup |")
    print(f"|---|---:|---:|---:|---:|---:|")
    for op in OPS:
        for tbl in TABLES:
            name = f"{tbl}{op}"
            k = (name, sf)
            if k not in ours or k not in spark:
                continue
            os_score, os_err, _ = ours[k]
            sp_score, sp_err, _ = spark[k]
            speedup = sp_score / os_score
            print(
                f"| `{name}` | {os_score:.1f} | ±{os_err:.1f} "
                f"| {sp_score:.1f} | ±{sp_err:.1f} "
                f"| **{speedup:.2f}×** |"
            )
        # geomean row per operation
        ratios = [
            spark[(f"{tbl}{op}", sf)][0] / ours[(f"{tbl}{op}", sf)][0]
            for tbl in TABLES
            if (f"{tbl}{op}", sf) in ours and (f"{tbl}{op}", sf) in spark
        ]
        print(f"| _**{op} geomean**_ | | | | | **{gmean(ratios):.2f}×** |")

    # Overall geomean
    all_ratios = []
    for op in OPS:
        for tbl in TABLES:
            k = (f"{tbl}{op}", sf)
            if k in ours and k in spark:
                all_ratios.append(spark[k][0] / ours[k][0])
    print(f"| **OVERALL GEOMEAN (12 benches)** | | | | | **{gmean(all_ratios):.2f}×** |")


def emit_allocation_table(ours, spark, sf):
    """§11 allocation-rate table."""
    print("\n\n### Allocation rate, by TPC-H table\n")
    print(f"| Benchmark | Ours (B/op) | Spark (B/op) | Ratio (ours/Spark) |")
    print(f"|---|---:|---:|---:|")
    for op in OPS:
        for tbl in TABLES:
            name = f"{tbl}{op}"
            k = (name, sf)
            if k not in ours or k not in spark:
                continue
            _, _, oa = ours[k]
            _, _, sa = spark[k]
            ratio = oa / sa if oa and sa else 0.0
            print(f"| `{name}` | {oa or 0:.0f} | {sa or 0:.0f} | {ratio:.2f}× |")
        # geomean per op
        ratios = []
        for tbl in TABLES:
            k = (f"{tbl}{op}", sf)
            if k in ours and k in spark:
                _, _, oa = ours[k]
                _, _, sa = spark[k]
                if oa and sa and sa > 0:
                    ratios.append(oa / sa)
        print(f"| _**{op} geomean**_ | | | **{gmean(ratios):.2f}×** |")


def emit_step_progression(sf):
    """§11 step progression table — requires the historical step JSON files
    if available. Skips silently if not present."""
    steps = [
        ("Baseline (orig)", "results/20260527T040510Z-sf1/micro-protocatalyst.json"),
        ("Step 1 (+cache)", "/tmp/micro-step1.json"),
        ("Step 2 (+inline)", "/tmp/micro-step2.json"),
        ("Step 3 (+macro)", "/tmp/micro-step3.json"),
    ]
    spark = load_jmh("results/20260527T040510Z-sf1/micro-spark.json")

    step_data = []
    for label, path in steps:
        if not Path(path).is_file():
            print(f"\n<!-- step-progression table skipped: {path} not present -->")
            return
        step_data.append((label, load_jmh(path)))

    print("\n\n### Step-by-step progression — geomean speedup vs Spark\n")
    header = "| Operation |"
    sep = "|---|"
    for label, _ in step_data:
        header += f" {label} |"
        sep += "---:|"
    print(header)
    print(sep)
    for op in OPS:
        row = f"| {op} |"
        for _, data in step_data:
            ratios = []
            for tbl in TABLES:
                k = (f"{tbl}{op}", sf)
                if k in data and k in spark and data[k][0] > 0:
                    ratios.append(spark[k][0] / data[k][0])
            row += f" {gmean(ratios):.2f}× |"
        print(row)

    print("\n\n### Step-by-step progression — allocation ratio (ours / Spark)\n")
    print(header)
    print(sep)
    for op in OPS:
        row = f"| {op} |"
        for _, data in step_data:
            ratios = []
            for tbl in TABLES:
                k = (f"{tbl}{op}", sf)
                if k in data and k in spark:
                    _, _, oa = data[k]
                    _, _, sa = spark[k]
                    if oa and sa and sa > 0:
                        ratios.append(oa / sa)
            row += f" {gmean(ratios):.2f}× |"
        print(row)


def emit_query_table(path):
    """§12 end-to-end query table — min-of-3 per (query, variant, config)."""
    print("\n\n### TPC-H end-to-end (default config: codegen=true aqe=true threads=*)\n")
    rows = defaultdict(list)
    with open(path) as f:
        for r in csv.reader(f):
            if not r or r[0].startswith("#") or r[0] == "query":
                continue
            if r[5] == "error":
                continue
            q, v, cg, aqe, th, _, ms = r
            rows[(q, v, cg, aqe, th)].append(int(ms))
    default = ("true", "true", "*")
    print(f"| Query | DF (ms) | DS (ms) | DS/DF | Encoder fraction of DS |")
    print(f"|---|---:|---:|---:|---:|")
    for q in ["q1", "q6", "q14", "q21"]:
        df = min(rows.get((q, "df", *default), [0]))
        ds = min(rows.get((q, "ds", *default), [0]))
        if df > 0 and ds > 0:
            ratio = ds / df
            frac = (ds - df) / ds * 100
            print(f"| `{q}` | {df} | {ds} | **{ratio:.2f}×** | **{frac:.0f}%** |")


def main(argv):
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        sys.exit(1)
    results_dir = Path(argv[1])
    if not results_dir.is_dir():
        sys.exit(f"Not a directory: {results_dir}")

    ours_path = results_dir / "micro-protocatalyst.json"
    spark_path = results_dir / "micro-spark.json"
    csv_path = results_dir / "queries.csv"

    ours = load_jmh(ours_path)
    spark = load_jmh(spark_path)
    sf = "1"  # publication SF

    emit_throughput_table(ours, spark, sf)
    emit_allocation_table(ours, spark, sf)
    emit_step_progression(sf)
    if csv_path.is_file():
        emit_query_table(csv_path)
    else:
        print(f"\n<!-- queries.csv not found; skipping §12 table -->")


if __name__ == "__main__":
    main(sys.argv)
