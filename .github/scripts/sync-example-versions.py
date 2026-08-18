#!/usr/bin/env python3
"""Pin the examples to the currently released version of each Chippy package.

The examples depend on Chippy by released coordinate rather than through `moduleDeps`, so
their version literals have to be maintained by hand. release-please cannot do it once
packages version independently: its generic updater has no component-scoped annotations
and keeps a single version context per file, while every example build file mixes packages
from several version lines. Annotating one would rewrite every coordinate in it with the
same version.

The artifact -> version mapping is read from the build rather than duplicated here, so it
cannot drift from `versionLine` in build.mill:

    ./mill show '__.artifactMetadata' 2>/dev/null | python3 .github/scripts/sync-example-versions.py
    ./mill show '__.artifactMetadata' 2>/dev/null | python3 .github/scripts/sync-example-versions.py --check

Run it *after* a release has published, not on the release PR: pinning a version that is
not on Maven Central yet fails the examples job for everyone until it is.
"""
import json
import pathlib
import re
import sys

COORD = re.compile(r'(mvn"io\.github\.ucb-substrate::)([A-Za-z0-9._-]+)(:)([^"]+)(")')
SKIP = ("saturn-vectors", "shuttle")


def main() -> int:
    check = "--check" in sys.argv[1:]
    meta = json.load(sys.stdin)
    # "cde_2.13" -> "cde"; the `::` in a coordinate is what appends the Scala suffix.
    versions = {
        m["id"].rsplit("_", 1)[0]: m["version"] for m in meta.values()
    }

    stale, unknown = [], []
    for f in sorted(pathlib.Path("examples").rglob("*.mill")):
        if any(s in str(f) for s in SKIP):
            continue
        text = f.read_text()

        def repl(m: re.Match) -> str:
            artifact, current = m.group(2), m.group(4)
            want = versions.get(artifact)
            if want is None:
                unknown.append((str(f), artifact))
                return m.group(0)
            if want != current:
                stale.append((str(f), artifact, current, want))
            return f"{m.group(1)}{artifact}{m.group(3)}{want}{m.group(5)}"

        updated = COORD.sub(repl, text)
        if updated != text and not check:
            f.write_text(updated)

    for path, artifact in unknown:
        print(f"unknown artifact {artifact!r} in {path}", file=sys.stderr)
    for path, artifact, current, want in stale:
        print(f"{'stale' if check else 'updated'}: {path}: {artifact} {current} -> {want}")

    if unknown:
        return 2
    if not stale:
        print("examples already match the released versions")
        return 0
    return 1 if check else 0


if __name__ == "__main__":
    raise SystemExit(main())
