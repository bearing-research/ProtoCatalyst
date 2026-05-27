#!/usr/bin/env bash
# One-command TPC-H + encoder benchmark.
#
# Wraps: gen-tpch.sh → TpchParquetConverter → JMH microbench (both sides) →
# TpchQueryBench, into a single artifact-producing run. Outputs go to
# `results/<timestamp>/` along with a full-disclosure header per
# docs/BENCHMARK_METHODOLOGY.md. Spark committers ask for this directory.
#
# Usage:
#   ./scripts/bench.sh [SF] [--quick] [--skip-data] [--skip-micro] [--skip-queries]
#
# Examples:
#   ./scripts/bench.sh 0.01 --quick           # CI smoke (~1 min)
#   ./scripts/bench.sh 1                       # publication microbench (~30 min)
#   ./scripts/bench.sh 10                      # local-Mac headline E2E (~60 min)
#   ./scripts/bench.sh 100 --skip-data         # cloud m6i.8xlarge headline (data pre-staged)
#
# Flags:
#   --quick        1 fork × 1 warmup × 2 measurement iterations (smoke; not citable).
#   --skip-data    Assume data/tpch/sf-<SF>/ and the Parquet dir already exist.
#   --skip-micro   Don't run JMH micro benchmarks.
#   --skip-queries Don't run end-to-end query benchmarks.

set -euo pipefail

SF="${1:-1}"
shift || true

QUICK=0
SKIP_DATA=0
SKIP_MICRO=0
SKIP_QUERIES=0

for arg in "$@"; do
  case "$arg" in
    --quick)        QUICK=1 ;;
    --skip-data)    SKIP_DATA=1 ;;
    --skip-micro)   SKIP_MICRO=1 ;;
    --skip-queries) SKIP_QUERIES=1 ;;
    *) echo "Unknown flag: $arg" >&2; exit 1 ;;
  esac
done

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${PROJECT_ROOT}"

TS="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_DIR="results/${TS}-sf${SF}"
mkdir -p "${OUT_DIR}"

# JMH iteration counts.
if [[ ${QUICK} -eq 1 ]]; then
  JMH_FORKS=1
  JMH_WARMUP=1
  JMH_MEASURE=2
else
  JMH_FORKS=3
  JMH_WARMUP=5
  JMH_MEASURE=15
fi

# === Disclosure header ===

write_disclosure() {
  local out="${OUT_DIR}/disclosure.txt"
  local jdk vendor os_name os_arch cores git_sha mem_gb java_cmd
  # Prefer JAVA_HOME (matches what sbt forks the benchmark JVMs with); fall back to PATH.
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    java_cmd="${JAVA_HOME}/bin/java"
  else
    java_cmd="$(command -v java || echo java)"
  fi
  jdk="$("${java_cmd}" -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/')"
  vendor="$("${java_cmd}" -XshowSettings:properties -version 2>&1 | grep 'java.vendor =' | head -1 | awk -F'= ' '{print $2}')"
  os_name="$(uname -s)"
  os_arch="$(uname -m)"
  cores="$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo unknown)"
  git_sha="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
  if [[ "${os_name}" == "Darwin" ]]; then
    mem_gb="$(($(sysctl -n hw.memsize) / 1024 / 1024 / 1024))"
  else
    mem_gb="$(awk '/MemTotal/ { printf "%.0f\n", $2 / 1024 / 1024 }' /proc/meminfo 2>/dev/null || echo unknown)"
  fi

  cat > "${out}" <<EOF
# TPC-H + Encoder Benchmark — Full Disclosure

Generated:     $(date -u +%Y-%m-%dT%H:%M:%SZ)
Scale factor:  ${SF}
Mode:          $([[ ${QUICK} -eq 1 ]] && echo "QUICK (not citable)" || echo "publication")

## Hardware

OS:            ${os_name} (${os_arch})
Cores:         ${cores}
RAM:           ${mem_gb} GB

## JVM

Version:       ${jdk}
Vendor:        ${vendor}
Detected via:  ${java_cmd}
Ground truth:  see "jdkVersion" and "jvm" in each micro-*.json. The
               benchmark JVM is whatever sbt forks; if your shell's
               default java differs, the JMH JSON is authoritative.

## Software

Spark:         4.1.2
Scala (S3):    3.8.1 (encoder-spark, benchmarks)
Scala (S2.13): 2.13.16 (benchmark-spark, spark-catalyst)
Git SHA:       ${git_sha}

## JMH parameters

Forks:         ${JMH_FORKS}
Warmup iter:   ${JMH_WARMUP}
Measure iter:  ${JMH_MEASURE}

## Spark configs ablated (TpchQueryBench)

- spark.sql.codegen.wholeStage = {true, false}
- spark.sql.adaptive.enabled   = {true, false}
- Master URL                   = {local[1], local[*]}

## Notes

- Data and tpch-dbgen are not committed; regenerated locally per run.
- TimeType benchmark deferred — Spark 4.1.2 has LocalTimeEncoder but
  SerializerBuildHelper throws UNSUPPORTED_TIME_TYPE.
- Encoder byte-level parity verified separately in
  encoder-spark/.../UnsafeRowParitySpec.scala.

## Methodology source

See \`docs/BENCHMARK_METHODOLOGY.md\` for the rules (Georges OOPSLA 2007,
Photon SIGMOD 2022, ClickBench, DuckDB benchmarking guide).
EOF
  echo "==> Disclosure: ${out}"
}

# === Banner ===

echo "==============================================================="
echo "  TPC-H + Encoder Benchmark"
echo "  SF=${SF}  mode=$([[ ${QUICK} -eq 1 ]] && echo quick || echo publication)"
echo "  out=${OUT_DIR}"
echo "==============================================================="
write_disclosure

# === Step 1: data ===

if [[ ${SKIP_DATA} -eq 0 ]]; then
  echo ""
  echo "==> Step 1: generating TPC-H data at SF=${SF}"
  ./scripts/gen-tpch.sh "${SF}"

  echo ""
  echo "==> Step 1b: converting .tbl → Parquet"
  if [[ ! -d "data/tpch/sf-${SF}-parquet" ]]; then
    sbt "benchmarkSpark/runMain protocatalyst.benchmark.tpch.TpchParquetConverter ${SF}"
  else
    echo "    Parquet already exists, skipping."
  fi
else
  echo "==> Step 1: skipped (--skip-data)"
fi

# === Step 2: JMH microbenchmarks ===

if [[ ${SKIP_MICRO} -eq 0 ]]; then
  echo ""
  echo "==> Step 2a: JMH encoder microbench (ProtoCatalyst / Scala 3)"
  sbt "benchmarks/Jmh/run \
    -f ${JMH_FORKS} -wi ${JMH_WARMUP} -i ${JMH_MEASURE} \
    -p sf=${SF} \
    -rf json -rff ${OUT_DIR}/micro-protocatalyst.json \
    -prof gc \
    TpchUnsafeRowBenchmarks"
  echo "==> wrote ${OUT_DIR}/micro-protocatalyst.json"

  echo ""
  echo "==> Step 2b: JMH encoder microbench (Spark ExpressionEncoder / Scala 2.13)"
  sbt "benchmarkSpark/Jmh/run \
    -f ${JMH_FORKS} -wi ${JMH_WARMUP} -i ${JMH_MEASURE} \
    -p sf=${SF} \
    -rf json -rff ${OUT_DIR}/micro-spark.json \
    -prof gc \
    TpchSparkEncoderBenchmarks"
  echo "==> wrote ${OUT_DIR}/micro-spark.json"
else
  echo "==> Step 2: skipped (--skip-micro)"
fi

# === Step 3: end-to-end query bench ===

if [[ ${SKIP_QUERIES} -eq 0 ]]; then
  echo ""
  echo "==> Step 3: end-to-end TPC-H queries"
  sbt "benchmarkSpark/runMain protocatalyst.benchmark.tpch.TpchQueryBench ${SF}"
  # TpchQueryBench writes to results/tpch-queries-sf<SF>-<its-own-ts>.csv.
  # Move the most recently produced one into our output dir.
  latest="$(ls -t results/tpch-queries-sf${SF}-*.csv 2>/dev/null | head -1 || true)"
  if [[ -n "${latest}" ]]; then
    mv "${latest}" "${OUT_DIR}/queries.csv"
    echo "==> moved to ${OUT_DIR}/queries.csv"
  fi
else
  echo "==> Step 3: skipped (--skip-queries)"
fi

# === Summary ===

echo ""
echo "==============================================================="
echo "  Done.  ${OUT_DIR}/"
ls -la "${OUT_DIR}/" 2>/dev/null
echo "==============================================================="
