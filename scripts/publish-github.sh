#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

tag_name="${1:?usage: scripts/publish-github.sh <release-tag>}"

github_actor="${GITHUB_ACTOR:?GITHUB_ACTOR must be set}"
github_token="${GITHUB_TOKEN:?GITHUB_TOKEN must be set}"
github_repository="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY must be set}"
publish_uri="https://maven.pkg.github.com/${github_repository}"

case "$tag_name" in
  cde-v*) target="rocketchip.dependencies.cde" ;;
  chippy-v*) target="chippy" ;;
  chipyard-v*) target="chipyard" ;;
  constellation-v*) target="constellation" ;;
  diplomacy-v*) target="rocketchip.dependencies.diplomacy" ;;
  hardfloat-v*) target="rocketchip.dependencies.hardfloat" ;;
  rocketchip-blocks-v*) target="rocketchip-blocks" ;;
  rocketchip-inclusive-cache-v*) target="rocketchip-inclusive-cache" ;;
  rocketchip-macros-v*) target="rocketchip.macros" ;;
  rocketchip-v*) target="rocketchip" ;;
  testchipip-v*) target="testchipip" ;;
  *)
    echo "unrecognized release tag: ${tag_name}" >&2
    exit 1
    ;;
esac

./scripts/publish-local.sh
./mill "${target}.publish" \
  --username "${github_actor}" \
  --password "${github_token}" \
  --releaseUri "${publish_uri}" \
  --snapshotUri "${publish_uri}"
