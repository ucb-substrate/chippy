#!/usr/bin/env python3
"""Print the Mill selector for packages whose version is not on Maven Central yet.

Reads `mill show __.artifactMetadata` on stdin and writes `count=` / `selector=` lines
suitable for $GITHUB_OUTPUT, with a per-package report on stderr.

Which packages a release publishes is derived from Central rather than from
release-please's outputs, for two reasons. The publish job runs *before* the tagging half,
so those outputs do not exist yet; and asking Central makes the job idempotent — re-running
after a partial failure uploads exactly what is still missing, which is what the
workflow_dispatch escape hatch relies on.

Caveat: repo1 lags a Portal publish by a few minutes, so a re-run started immediately after
a successful upload can still see a package as missing and try to publish it again, which
Central rejects. Wait for the deployment to reach PUBLISHED before re-running.
"""
import json
import subprocess
import sys

BASE = "https://repo1.maven.org/maven2"


def published(group: str, artifact: str, version: str) -> bool:
    """True if the POM is already on Central. Uses curl rather than urllib so the
    system CA bundle is used; some Python installs do not have one configured."""
    path = f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"
    out = subprocess.run(
        ["curl", "-sS", "-I", "--max-time", "30", "-o", "/dev/null",
         "-w", "%{http_code}", f"{BASE}/{path}"],
        capture_output=True, text=True, check=True,
    ).stdout.strip()
    if out == "200":
        return True
    if out == "404":
        return False
    raise RuntimeError(f"unexpected HTTP {out} for {path}")


def main() -> int:
    meta = json.load(sys.stdin)
    missing = []
    for task, m in sorted(meta.items()):
        module = task[: -len(".artifactMetadata")]
        coord = f"{m['group']}:{m['id']}:{m['version']}"
        if published(m["group"], m["id"], m["version"]):
            print(f"  present  {coord}", file=sys.stderr)
        else:
            print(f"  MISSING  {coord}  ({module})", file=sys.stderr)
            missing.append(module)

    print(f"count={len(missing)}")
    if len(missing) == 1:
        # Mill's brace syntax needs at least two alternatives: `{a,b}` parses, `{a}` does not.
        print(f"selector={missing[0]}")
    elif missing:
        print("selector={" + ",".join(missing) + "}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
