#!/usr/bin/env bash
# Real-query encoder benchmark: what the encoder change is worth to an end user running an
# actual Spark job, rather than to a microbenchmark of derivation.
#
# Two halves, same data, same queries, differing in exactly one thing — where Encoder[T] comes from:
#   baseline : Scala 2.13, stock Spark, ScalaReflection.encoderFor       (benchmark-spark)
#   proto    : Scala 3,    stock Spark, deriveAgnosticEncoder            (benchmarks + the patch)
#
# Each half runs as a SHORT-LIVED DRIVER in a fresh JVM, because the costs at issue (reflective
# universe init, per-type derivation) are paid once per JVM and are invisible to a warmed-up
# in-process harness. Per run we record a phase breakdown plus the headline
# `total_to_first_result` (JVM start -> first query result), and a steady-state median so any
# execution-throughput regression would show up rather than being assumed away.
#
# Usage:
#   ./scripts/query-bench.sh [SF] [REPS] [STEADY_ITERS]
#
# Examples:
#   ./scripts/query-bench.sh 0.01 3 5     # smoke (~3 min)
#   ./scripts/query-bench.sh 1 5 10       # the citable run
#
# Output: results/<timestamp>-query-sf<SF>/{raw.csv,summary.txt,disclosure.txt}
#
# NOTE: sbt is used only to resolve classpaths and is stopped before any measurement; the measured
# JVMs are bare `java` processes. Do not run another sbt task against these modules concurrently.

set -euo pipefail

SF="${1:-1}"
REPS="${2:-5}"
STEADY="${3:-10}"

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${PROJECT_ROOT}"

: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@21}"
export JAVA_HOME
JAVA="${JAVA_HOME}/bin/java"

TS="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_DIR="results/${TS}-query-sf${SF}"
mkdir -p "${OUT_DIR}"
RAW="${OUT_DIR}/raw.csv"

PARQUET_DIR="data/tpch/sf-${SF}-parquet"
if [[ ! -d "${PARQUET_DIR}" ]]; then
  echo "Parquet dir not found: ${PARQUET_DIR}" >&2
  echo "Generate it first:" >&2
  echo "  ./scripts/gen-tpch.sh ${SF}" >&2
  echo "  sbt 'benchmarkSpark/runMain protocatalyst.benchmark.tpch.TpchParquetConverter ${SF}'" >&2
  exit 1
fi

# Spark's own module-access flags (org.apache.spark.launcher.JavaModuleOptions) — required on JDK 21.
JAVA_MODULE_OPTS=(
  -XX:+IgnoreUnrecognizedVMOptions
  --add-opens=java.base/java.lang=ALL-UNNAMED
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED
  --add-opens=java.base/java.io=ALL-UNNAMED
  --add-opens=java.base/java.net=ALL-UNNAMED
  --add-opens=java.base/java.nio=ALL-UNNAMED
  --add-opens=java.base/java.util=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED
  --add-opens=java.base/jdk.internal.ref=ALL-UNNAMED
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
  --add-opens=java.base/sun.nio.cs=ALL-UNNAMED
  --add-opens=java.base/sun.security.action=ALL-UNNAMED
  --add-opens=java.base/sun.util.calendar=ALL-UNNAMED
  --add-opens=java.base/sun.nio.fs=ALL-UNNAMED
  -Djdk.reflect.useDirectMethodHandle=false
  -Xmx4g
)

echo "==============================================================="
echo "  Real-query encoder benchmark"
echo "  SF=${SF}  reps=${REPS}  steady-iters=${STEADY}"
echo "  out=${OUT_DIR}"
echo "==============================================================="

echo "==> Building and resolving classpaths (sbt)"
sbt -batch \
  'sparkReflectionPatch/compile' \
  'benchmarkSpark/compile' \
  'benchmarks/compile' \
  'export benchmarkSpark/Runtime/fullClasspath' \
  'export benchmarks/Runtime/fullClasspath' \
  > "${OUT_DIR}/sbt-classpath.log" 2>&1

# `export <task>` prints the resolved classpath on its own line; sbt runs the tasks in the order
# given, so line 1 is benchmarkSpark and line 2 is benchmarks.
CP_LINES="$(grep -E '^/[^ ]*\.jar' "${OUT_DIR}/sbt-classpath.log" || true)"
CP_BASELINE="$(printf '%s\n' "${CP_LINES}" | sed -n '1p')"
CP_PROTO="$(printf '%s\n' "${CP_LINES}" | sed -n '2p')"

if [[ -z "${CP_BASELINE}" || -z "${CP_PROTO}" ]]; then
  echo "Failed to resolve classpaths; see ${OUT_DIR}/sbt-classpath.log" >&2
  exit 1
fi

# The patched ScalaReflection + SparkSession must shadow Spark's copies for the Scala 3 half.
PATCH_CLASSES="${PROJECT_ROOT}/spark-reflection-patch/target/scala-2.13/classes"
if [[ ! -d "${PATCH_CLASSES}" ]]; then
  echo "Patch classes not found at ${PATCH_CLASSES}" >&2
  exit 1
fi
CP_PROTO="${PATCH_CLASSES}:${CP_PROTO}"

# sbt (-batch) has exited by now. A background sbt/Metals/Bloop server belonging to the user may
# still be running; we deliberately do not kill it, but a concurrent build in these modules would
# both perturb the timings and risk corrupting the run (see CLAUDE.md).
echo "==> Measurement starts (bare JVMs, no sbt in the measured process)"

echo "side,workload,sf,metric,ms,rep" > "${RAW}"

run_one() {
  local side="$1" cp="$2" mainclass="$3" workload="$4" rep="$5"
  local log="${OUT_DIR}/${side}-${workload}-rep${rep}.log"
  if "${JAVA}" "${JAVA_MODULE_OPTS[@]}" -cp "${cp}" "${mainclass}" "${SF}" "${workload}" "${STEADY}" \
      > "${log}" 2> "${log}.err"; then
    grep '^RESULT,' "${log}" | sed "s/^RESULT,//" | awk -v r="${rep}" -F, '{print $0","r}' >> "${RAW}"
    tail -1 "${log}.err" | sed 's/^/    /'
  else
    echo "    RUN FAILED (${side}/${workload} rep ${rep}) — see ${log}.err" >&2
    tail -5 "${log}.err" | sed 's/^/    /' >&2
    return 1
  fi
}

# One discarded run per side before recording anything. Its real job is to let the machine settle:
# the sbt/Bloop daemon that just resolved these classpaths is still finishing background work, and a
# run landing in that window has every phase inflated — including JVM boot, which nothing in this
# benchmark can affect. Recorded as rep 0 in raw.csv so the noise is visible but excluded from the
# summary rather than silently dropped.
echo "==> Settling run (discarded from the summary, kept in raw.csv as rep 0)"
run_one baseline "${CP_BASELINE}" protocatalyst.benchmark.tpch.QueryColdStartBaseline q6 0 || true
run_one proto    "${CP_PROTO}"    protocatalyst.bench.tpch.QueryColdStartProto        q6 0 || true

for workload in q6 wide; do
  for rep in $(seq 1 "${REPS}"); do
    echo "==> ${workload} rep ${rep}/${REPS}"
    # Interleaved so any slow drift in machine state hits both sides equally.
    run_one baseline "${CP_BASELINE}" protocatalyst.benchmark.tpch.QueryColdStartBaseline "${workload}" "${rep}"
    run_one proto    "${CP_PROTO}"    protocatalyst.bench.tpch.QueryColdStartProto        "${workload}" "${rep}"
  done
done

echo "==> Aggregating"
python3 scripts/query_bench_report.py "${RAW}" > "${OUT_DIR}/summary.txt"
cat "${OUT_DIR}/summary.txt"

cat > "${OUT_DIR}/disclosure.txt" <<EOF
# Real-query encoder benchmark — disclosure

Generated:     $(date -u +%Y-%m-%dT%H:%M:%SZ)
Scale factor:  ${SF}
Reps:          ${REPS} fresh JVMs per (side x workload)
Steady iters:  ${STEADY} in-JVM repeats after the first result

## Hardware / JVM

OS:            $(uname -s) ($(uname -m))
Cores:         $(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu)
JDK:           $("${JAVA}" -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/')
JAVA_HOME:     ${JAVA_HOME}

## Software

Spark:         4.1.2 (unmodified, except the demonstrator patch below)
Scala:         baseline 2.13.16 / proto 3.8.1
Git SHA:       $(git rev-parse HEAD 2>/dev/null || echo unknown)

## What differs between the two halves

Only the source of Encoder[T]:
  baseline = ScalaReflection.encoderFor via spark.implicits (TypeTag, runtime reflection)
  proto    = deriveAgnosticEncoder[T]   (compile-time derivation, no TypeTag)
Queries, data, Spark configuration and execution path are identical.

## Confounds to state when citing

- The two halves run on different Scala versions (2.13 vs 3) because the baseline API only exists
  on 2.13 and the replacement only on 3. Driver-side stdlib differences are therefore inside the
  measurement. This is inherent to the comparison, not a choice.
- The Scala 3 half needs spark-reflection-patch on the classpath (patched ScalaReflection +
  SparkSession); without it a Scala 3 driver cannot start a session at all.
- local[*] on one machine: this measures driver-side cost, not cluster behaviour.

## Methodology source

docs/scala3-encoder/BENCHMARKS.md
EOF

echo ""
echo "==> Wrote ${OUT_DIR}/{raw.csv,summary.txt,disclosure.txt}"
