#!/usr/bin/env bash
# Generate TPC-H data at a given scale factor using `dbgen`.
#
# Usage:
#   ./scripts/gen-tpch.sh [SF]      # default SF=1
#   ./scripts/gen-tpch.sh 0.01      # smoke / CI
#   ./scripts/gen-tpch.sh 10        # local-Mac headline
#   ./scripts/gen-tpch.sh 100       # cloud m6i.8xlarge
#
# On first run, fetches and builds the TPC-H `dbgen` tool. Output goes to
# `data/tpch/sf-${SF}/*.tbl` at the project root.
#
# TPC-H data is not committed — re-run this script on a fresh checkout. The TPC
# license restricts redistribution of generated data.

set -euo pipefail

SF="${1:-1}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DBGEN_DIR="${PROJECT_ROOT}/tools/tpch-dbgen"
OUT_DIR="${PROJECT_ROOT}/data/tpch/sf-${SF}"

# Public mirror of the TPC-H dbgen tool. MIT-licensed C source. The official tool from tpc.org
# requires accepting an EULA; the electrum mirror is the standard convenient redistribution used
# by DuckDB, ClickHouse, and most academic benchmarks.
DBGEN_REPO="${DBGEN_REPO:-https://github.com/electrum/tpch-dbgen.git}"

mkdir -p "$(dirname "${DBGEN_DIR}")"

# === Fetch + build dbgen ===

if [[ ! -d "${DBGEN_DIR}" ]]; then
  echo "==> Cloning ${DBGEN_REPO} → ${DBGEN_DIR}"
  git clone --depth=1 "${DBGEN_REPO}" "${DBGEN_DIR}"
fi

if [[ ! -x "${DBGEN_DIR}/dbgen" ]]; then
  echo "==> Building dbgen"
  pushd "${DBGEN_DIR}" >/dev/null

  # The dbgen Makefile uses MACHINE/DATABASE/WORKLOAD as -D defines that the C source switches on
  # for typedefs (DSS_HUGE etc). MACOS is the right define on Apple Silicon and Intel Macs alike.
  case "$(uname)" in
    Darwin) MACHINE=MAC ;;
    Linux)  MACHINE=LINUX ;;
    *)      echo "Unsupported platform: $(uname)" >&2; exit 1 ;;
  esac

  # Modern compilers fail on the legacy C in dbgen. Add suppressions via EXTRA_CFLAGS so the
  # Makefile's internal -D defines (which set DSS_HUGE etc) survive.
  make MACHINE="${MACHINE}" DATABASE=ORACLE WORKLOAD=TPCH \
       CC="cc -Wno-implicit-function-declaration -Wno-implicit-int -Wno-format-security \
              -Wno-incompatible-pointer-types -Wno-return-type -Wno-int-conversion" >/dev/null
  popd >/dev/null
fi

# === Generate data ===

echo "==> Generating SF=${SF} → ${OUT_DIR}"
mkdir -p "${OUT_DIR}"

# dbgen writes .tbl files into its own dir; move them to OUT_DIR after.
pushd "${DBGEN_DIR}" >/dev/null
./dbgen -s "${SF}" -f
popd >/dev/null

mv "${DBGEN_DIR}"/*.tbl "${OUT_DIR}/"

echo "==> Done. Files in ${OUT_DIR}:"
ls -lh "${OUT_DIR}"
