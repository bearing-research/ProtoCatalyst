#!/usr/bin/env python3
"""Aggregate scripts/query-bench.sh raw output into a side-by-side comparison.

Reads raw.csv (side,workload,sf,metric,ms,rep) and prints, per workload, the median across
fresh-JVM reps for each phase plus the baseline/proto delta. Median (not mean) because a
short-lived-driver population is right-skewed by occasional OS/page-cache noise, and min is
reported alongside it as the "best achievable" bound (Georges et al. OOPSLA 2007 argues for
reporting a distribution, not a single number).
"""
import csv
import statistics
import sys
from collections import defaultdict

METRIC_ORDER = [
    ("jvm_to_main", "JVM boot -> main()"),
    ("encoder_cold_first_type", "encoder: 1st type (cold JVM)"),
    ("session_create", "SparkSession.builder -> ready"),
    ("encoder_after_session", "encoder: 1st after session*"),
    ("encoder_remaining_types", "encoder: remaining types"),
    ("encoder_per_additional_type", "encoder: per additional type"),
    ("query_build", "query construction"),
    ("first_action", "first action -> result"),
    ("total_to_first_result", "TOTAL to first result"),
    ("steady_median", "steady-state (per iteration)"),
]

# Phases where a ratio is meaningful because the phase is dominated by the thing under test.
RATIO_METRICS = {
    "encoder_cold_first_type",
    "encoder_remaining_types",
    "encoder_per_additional_type",
}

# `encoder_after_session` is deliberately NOT a ratio metric: it is dominated by Spark's one-time
# init on first encoder use in a live session, which both sides pay identically. Reporting it as a
# derivation ratio would be reading JVM/Spark warmup as an encoder result.


def main(path):
    rows = defaultdict(list)  # (workload, side, metric) -> [ms]
    sf = None
    with open(path) as fh:
        for r in csv.DictReader(fh):
            if int(r["rep"]) == 0:
                continue  # settling run: recorded in raw.csv, excluded from the summary
            rows[(r["workload"], r["side"], r["metric"])].append(float(r["ms"]))
            sf = r["sf"]

    workloads = sorted({k[0] for k in rows})
    print("Real-query encoder benchmark — median of fresh-JVM reps (ms), SF=%s" % sf)
    print()

    for wl in workloads:
        reps = len(rows.get((wl, "baseline", "total_to_first_result"), []))
        print("=" * 78)
        print("workload: %s   (%d reps per side)" % (wl, reps))
        if reps < 3:
            print("NOT CITABLE: %d rep(s). A single short-lived-driver run is dominated by machine"
                  % reps)
            print("state; use >= 5 reps before quoting any of these numbers.")
        print("=" * 78)
        print("%-32s %12s %12s %10s" % ("phase", "baseline", "proto", "delta"))
        print("%-32s %12s %12s %10s" % ("", "(reflect)", "(compile)", ""))
        print("-" * 78)
        for metric, label in METRIC_ORDER:
            b = rows.get((wl, "baseline", metric), [])
            p = rows.get((wl, "proto", metric), [])
            if not b or not p:
                continue
            bm, pm = statistics.median(b), statistics.median(p)
            delta = bm - pm
            # A speedup ratio is only meaningful where the phase is dominated by the thing that
            # differs; for whole-process numbers report absolute ms saved instead.
            if metric in RATIO_METRICS and pm > 0 and bm > 0:
                note = "%.1fx" % (bm / pm)
            elif abs(delta) < 1.0:
                note = "~equal"
            else:
                note = "%+.0f ms" % (-delta)
            marker = "  <-- headline" if metric == "total_to_first_result" else ""
            print("%-32s %12.1f %12.1f %10s%s" % (label, bm, pm, note, marker))
        print("-" * 78)
        for side in ("baseline", "proto"):
            vals = rows.get((wl, side, "total_to_first_result"), [])
            if vals:
                print(
                    "%-9s total-to-first-result: median %.0f  min %.0f  max %.0f ms"
                    % (side, statistics.median(vals), min(vals), max(vals))
                )
        # How much of the job-level delta is actually attributable to the encoder? Everything else
        # in the job (session startup, parquet reads, execution) is the same code on both sides, so
        # its delta is run-to-run noise. Stating the attributable share keeps the headline honest
        # when the totals happen to diverge more than the encoder phases can explain.
        enc_delta = 0.0
        for metric in ("encoder_cold_first_type", "encoder_remaining_types"):
            b = rows.get((wl, "baseline", metric), [])
            p_ = rows.get((wl, "proto", metric), [])
            if b and p_:
                enc_delta += statistics.median(b) - statistics.median(p_)
        tb = rows.get((wl, "baseline", "total_to_first_result"), [])
        tp = rows.get((wl, "proto", "total_to_first_result"), [])
        if tb and tp:
            total_delta = statistics.median(tb) - statistics.median(tp)
            print(
                "attributable to encoder derivation: %.0f ms of the %.0f ms total improvement"
                % (enc_delta, total_delta)
            )
            residual = total_delta - enc_delta
            print(
                "  (remaining %.0f ms is shared-path variance, not a claim: same Spark code both sides)"
                % residual
            )

        bs = rows.get((wl, "baseline", "steady_median"), [])
        ps = rows.get((wl, "proto", "steady_median"), [])
        if bs and ps:
            bm, pm = statistics.median(bs), statistics.median(ps)
            ratio = bm / pm if pm else float("nan")
            if reps < 3:
                verdict = "too few reps to judge"
            elif 0.94 <= ratio <= 1.06:
                verdict = "at parity (expected: identical execution path)"
            else:
                verdict = "outside the parity band — see the caveat below"

            print("steady-state ratio baseline/proto: %.3f  — %s" % (ratio, verdict))
            print(
                "  (the typed lambda bodies are compiled by different Scala compilers on the two"
            )
            print(
                "   sides, so a few % either way is that, not the encoder: the row path is"
            )
            print("   byte-identical by construction and verified in UnsafeRowParitySpec.)")
        print()

    print("* encoder: 1st after session — Spark's one-time init on first encoder use in a live")
    print("  session. Both sides pay it; it is not a derivation cost. Use 'per additional type'.")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "raw.csv")
