#!/usr/bin/env python3
"""Render cross-architecture comparison tables from per-arch benchmark result dirs.

Validates that the report's §11 (UnsafeRow) and §11b (Arrow) microbenchmark ratios
generalize across CPU architectures (e.g. AWS Graviton/arm64, Intel x86_64, AMD x86_64),
not just the local development machine.

Usage:
    ./scripts/cross_arch_report.py LABEL=DIR [LABEL=DIR ...]
    # e.g.
    ./scripts/cross_arch_report.py \
        graviton=results/cross-arch-<ts>/graviton \
        intel=results/cross-arch-<ts>/intel \
        amd=results/cross-arch-<ts>/amd

Each DIR is a `bench.sh` output directory containing:
    micro-arrow-protocatalyst.json / micro-arrow-spark.json   (§11b, Arrow path)
    micro-protocatalyst.json       / micro-spark.json          (§11,  UnsafeRow path)
    disclosure.txt                                             (hardware/JVM/git)

Conventions (matching the report):
    Arrow §11b ratio  = ours / spark   (<1.0 means our encoder is faster)
    UnsafeRow §11 speedup = spark / ours (>1.0 means our encoder is faster)
"""

import json
import math
import re
import sys
from pathlib import Path

TABLES = ["lineitem", "orders", "customer", "part"]
UNSAFE_OPS = ["Serialize", "Deserialize", "Roundtrip"]
SF = "1"  # publication scale factor


def load_jmh(path):
    """Return {(short_name, sf): (score, error, alloc_b_per_op)} or {} if absent."""
    p = Path(path)
    if not p.is_file():
        return {}
    with open(p) as f:
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
    xs = [x for x in xs if x and x > 0]
    return math.exp(sum(math.log(x) for x in xs) / len(xs)) if xs else 0.0


def disclosure_facts(d):
    """Pull (arch, cores, jdk, git_sha) from disclosure.txt; best-effort."""
    facts = {"arch": "?", "cores": "?", "jdk": "?", "git": "?"}
    p = Path(d) / "disclosure.txt"
    if not p.is_file():
        return facts
    text = p.read_text()
    if m := re.search(r"OS:\s*\S+\s*\(([^)]+)\)", text):
        facts["arch"] = m.group(1)
    if m := re.search(r"Cores:\s*(\S+)", text):
        facts["cores"] = m.group(1)
    if m := re.search(r"Version:\s*(\S+)", text):
        facts["jdk"] = m.group(1)
    if m := re.search(r"Git SHA:\s*(\S+)", text):
        facts["git"] = m.group(1)[:12]
    return facts


def arrow_metrics(d):
    """Return (per_table_ratio dict, throughput_geomean, lineitem_ratio, alloc_geomean)
    for the Arrow path, ours/spark convention. None if data missing."""
    ours = load_jmh(Path(d) / "micro-arrow-protocatalyst.json")
    spark = load_jmh(Path(d) / "micro-arrow-spark.json")
    if not ours or not spark:
        return None
    ratios, allocs, per_table = [], [], {}
    for t in TABLES:
        k = (f"{t}Batch", SF)
        if k in ours and k in spark and ours[k][0] > 0:
            r = ours[k][0] / spark[k][0]
            per_table[t] = r
            ratios.append(r)
            oa, sa = ours[k][2], spark[k][2]
            if oa and sa and sa > 0:
                allocs.append(oa / sa)
    return per_table, gmean(ratios), per_table.get("lineitem"), gmean(allocs)


def unsafe_geomean(d):
    """Return UnsafeRow throughput speedup geomean (spark/ours) over all 12 benches,
    plus alloc geomean (ours/spark). None if data missing."""
    ours = load_jmh(Path(d) / "micro-protocatalyst.json")
    spark = load_jmh(Path(d) / "micro-spark.json")
    if not ours or not spark:
        return None
    speed, allocs = [], []
    for op in UNSAFE_OPS:
        for t in TABLES:
            k = (f"{t}{op}", SF)
            if k in ours and k in spark and ours[k][0] > 0:
                speed.append(spark[k][0] / ours[k][0])
                oa, sa = ours[k][2], spark[k][2]
                if oa and sa and sa > 0:
                    allocs.append(oa / sa)
    return gmean(speed), gmean(allocs)


def main(argv):
    if len(argv) < 2 or any("=" not in a for a in argv[1:]):
        print(__doc__, file=sys.stderr)
        sys.exit(1)
    entries = []
    for a in argv[1:]:
        label, d = a.split("=", 1)
        entries.append((label, d, disclosure_facts(d)))

    print(f"# Cross-architecture benchmark validation (SF={SF})\n")
    print("## Machines\n")
    print("| Label | Arch | Cores | JDK | Git |")
    print("|---|---|---:|---|---|")
    for label, _, f in entries:
        print(f"| {label} | {f['arch']} | {f['cores']} | {f['jdk']} | `{f['git']}` |")

    print("\n## Arrow path — §11b (ratio = ours / Spark Connect; <1.0 = ours faster)\n")
    print("| Arch | Lineitem | Orders | Customer | Part | Throughput geomean | Alloc geomean |")
    print("|---|---:|---:|---:|---:|---:|---:|")
    for label, d, _ in entries:
        m = arrow_metrics(d)
        if not m:
            print(f"| {label} | — | — | — | — | _(no arrow data)_ | — |")
            continue
        pt, gm, _li, alloc = m
        cells = " | ".join(f"{pt.get(t, 0):.2f}×" for t in TABLES)
        print(f"| {label} | {cells} | **{gm:.2f}×** | {alloc:.2f}× |")

    print("\n## UnsafeRow path — §11 (speedup = Spark / ours; >1.0 = ours faster)\n")
    print("| Arch | Throughput geomean | Alloc geomean (ours/Spark) |")
    print("|---|---:|---:|")
    for label, d, _ in entries:
        u = unsafe_geomean(d)
        if not u:
            print(f"| {label} | _(no unsafe data)_ | — |")
            continue
        gm, alloc = u
        print(f"| {label} | **{gm:.2f}×** | {alloc:.2f}× |")

    print(
        "\n_Convention note: Arrow ratios are ours/Spark (lower is better, matching §11b); "
        "UnsafeRow is Spark/ours speedup (higher is better, matching §11)._"
    )


if __name__ == "__main__":
    main(sys.argv)
