#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

log_file="$(mktemp)"
trap 'rm -f "$log_file"' EXIT

./scripts/publish-local.sh
./mill \
  chipyard.test.compile \
  constellation.test.compile \
  examples.mmio-adder.test.compile \
  examples.sky130-chip.digital-chip.test.compile \
  rocketchip.dependencies.cde.test.compile \
  rocketchip.dependencies.hardfloat.test.compile | tee -a "$log_file"

if rg -n '\[warn\]| warning: ' "$log_file" >/dev/null; then
  echo "Compilation emitted warnings; see log above." >&2
  exit 1
fi
