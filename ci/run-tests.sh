#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

./scripts/publish-local.sh

targets=(
  chipyard.test.testForked
  constellation.test.testForked
  examples.mmio-adder.test.testForked
  examples.sky130-chip.digital-chip.test.testForked
  rocketchip.dependencies.cde.test.testForked
  rocketchip.dependencies.hardfloat.test.testForked
)

for target in "${targets[@]}"; do
  ./mill "$target"
done
