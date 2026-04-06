#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

targets=(
  rocketchip.macros.publishLocal
  rocketchip.dependencies.cde.publishLocal
  rocketchip.dependencies.diplomacy.publishLocal
  rocketchip.dependencies.hardfloat.publishLocal
  rocketchip.publishLocal
  rocketchip-blocks.publishLocal
  rocketchip-inclusive-cache.publishLocal
  testchipip.publishLocal
  constellation.publishLocal
  chippy.publishLocal
  chipyard.publishLocal
)

for target in "${targets[@]}"; do
  ./mill "$target" --doc false
done
