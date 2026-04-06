#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

mode="--test"
if [[ "${1:-}" == "--write" ]]; then
  mode=""
fi

mapfile -d '' files < <(
  python3 - <<'PY'
import os
import subprocess
import sys

root = os.getcwd()
submodules = set()
gitmodules = os.path.join(root, ".gitmodules")
if os.path.exists(gitmodules):
    output = subprocess.check_output(
        ["git", "config", "--file", ".gitmodules", "--get-regexp", "path"],
        text=True,
    )
    for line in output.splitlines():
        _, path = line.split(" ", 1)
        submodules.add(path.strip())

output = subprocess.check_output(
    [
        "git",
        "ls-files",
        "-z",
        "--",
        "*.scala",
        "*.sc",
        "*.sbt",
    ]
)

for path in output.decode().split("\0"):
    if not path:
        continue
    if any(path == submodule or path.startswith(submodule + "/") for submodule in submodules):
        continue
    sys.stdout.buffer.write(path.encode())
    sys.stdout.buffer.write(b"\0")
PY
)

if [[ ${#files[@]} -eq 0 ]]; then
  exit 0
fi

if command -v cs >/dev/null 2>&1; then
  coursier_cmd=(cs)
elif command -v coursier >/dev/null 2>&1; then
  coursier_cmd=(coursier)
else
  echo "Neither 'cs' nor 'coursier' is installed." >&2
  exit 127
fi

cmd=(
  "${coursier_cmd[@]}"
  launch
  org.scalameta:scalafmt-cli_2.13:3.8.4
  --
  --config
  .scalafmt.conf
)

if [[ -n "$mode" ]]; then
  cmd+=("$mode")
fi

"${cmd[@]}" "${files[@]}"
