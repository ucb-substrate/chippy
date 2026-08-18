# Chippy

A mini, modular version of [Chipyard](https://github.com/ucb-bar/chipyard).

Individual packages can be easily compiled, tested, and used in other projects without setting up a complex development environment.

## Usage

Chippy releases are published under the `io.github.ucb-substrate` namespace to
[Maven Central](https://central.sonatype.com/namespace/io.github.ucb-substrate). Mill, sbt and Maven
all resolve from Central out of the box, so there is no repository to add and no credentials to
configure — only the dependency itself.

The namespace is a coordinate, not a package name: the Scala packages are unchanged, so `chippy`
still lives in `edu.berkeley.cs.chippy` and rocket-chip in `freechips.rocketchip`. Nothing in an
`import` moves.

### Packages

Eleven artifacts are published, listed here roughly bottom-up. API documentation is rendered from the
published Scaladoc jars by [javadoc.io](https://javadoc.io); the first request for a given version
takes a moment while it unpacks the jar. The
[Maven Central namespace page](https://central.sonatype.com/namespace/io.github.ucb-substrate) is the
canonical listing.

| Package | Description | API docs |
| --- | --- | --- |
| `cde` | A Scala library for Context-Dependent Environments. | [docs](https://javadoc.io/doc/io.github.ucb-substrate/cde_2.13/latest) |
| `diplomacy` | A parameter negotiation framework for Chisel. | [docs](https://javadoc.io/doc/io.github.ucb-substrate/diplomacy_2.13/latest) |
| `hardfloat` | Hardware floating-point units written in Chisel. | [docs](https://javadoc.io/doc/io.github.ucb-substrate/hardfloat_2.13/latest) |
| `rocketchip-macros` | Scala macros used by the Rocket Chip generator. | [docs](https://javadoc.io/doc/io.github.ucb-substrate/rocketchip-macros_2.13/latest) |
| `rocketchip` | The Rocket Chip generator. | [docs](https://javadoc.io/doc/io.github.ucb-substrate/rocketchip_2.13/latest) |
| `rocketchip-blocks` | RTL blocks compatible with the Rocket Chip generator. | [docs](https://javadoc.io/doc/io.github.ucb-substrate/rocketchip-blocks_2.13/latest) |
| `rocketchip-inclusive-cache` | An RTL generator for a last-level shared inclusive TileLink cache controller. | [docs](https://javadoc.io/doc/io.github.ucb-substrate/rocketchip-inclusive-cache_2.13/latest) |
| `testchipip` | Useful IP components for chips. | [docs](https://javadoc.io/doc/io.github.ucb-substrate/testchipip_2.13/latest) |
| `constellation` | A Chisel NoC RTL generator framework designed to provide the core interconnect fabric for heterogeneous many-core, many-accelerator SoCs. | [docs](https://javadoc.io/doc/io.github.ucb-substrate/constellation_2.13/latest) |
| `chippy` | An SoC design framework for integrating cores, accelerators, and other peripherals. | [docs](https://javadoc.io/doc/io.github.ucb-substrate/chippy_2.13/latest) |
| `chipyard` | Prebuilt config fragments and full chip configs for Chippy. | [docs](https://javadoc.io/doc/io.github.ucb-substrate/chipyard_2.13/latest) |

To include a package in a new project, add the dependency. If you are using Mill 1.1.2, for example,
add the following to your `build.mill` to use the `diplomacy` package:

<!-- x-release-please-start-version -->
```scala
def mvnDeps = Seq(
    mvn"io.github.ucb-substrate::diplomacy:0.1.1",
)
```
<!-- x-release-please-end -->

The equivalent for sbt:

<!-- x-release-please-start-version -->
```scala
libraryDependencies += "io.github.ucb-substrate" %% "diplomacy" % "0.1.1"
```
<!-- x-release-please-end -->

Alternatively, you can build against unreleased changes by publishing to your local Ivy repository:

```
git clone https://github.com/ucb-substrate/chippy.git
cd chippy
git submodule update --init --recursive
./mill __.publishLocal
```

Usage examples can be found in the `examples/` folder.

## Releasing

Versions are managed by [release-please](https://github.com/googleapis/release-please) and are **per
package**, so a release publishes only what changed. `.release-please-manifest.json` is the single
source of truth; `build.mill` reads it, so no version numbers are hardcoded in the build.

There are seven version lines, one per submodule rather than one per artifact:

| Version line | Artifacts |
| --- | --- |
| `rocket-chip` | `rocketchip`, `rocketchip-macros`, `cde`, `diplomacy`, `hardfloat` |
| `rocket-chip-blocks` | `rocketchip-blocks` |
| `rocket-chip-inclusive-cache` | `rocketchip-inclusive-cache` |
| `testchipip` | `testchipip` |
| `constellation` | `constellation` |
| `chipyard` | `chipyard` |
| `chippy` | `chippy` |

The grouping is forced rather than chosen. release-please attributes a commit to a package by the
paths it touches, and every source tree above except `chippy/` is a git submodule — so bumping one
appears as a change to exactly one path. The five artifacts built out of `rocket-chip` share a
version because nothing distinguishes them in the commit history, which is also how they actually
change.

Version lines move independently, and a package is **not** republished when something it depends on
bumps: `chippy` at 0.1.1 keeps referencing whatever `rocketchip` version it was built against, which
is ordinary Maven behaviour. Mill derives each POM's dependency versions from the depended-on
module's own `publishVersion`, so this needs no bookkeeping.

The flow is:

1. Land changes on `main` using [Conventional Commits](https://www.conventionalcommits.org/) — `fix:`
   bumps the patch version, `feat:` bumps the minor version, and `feat!:` / a `BREAKING CHANGE:`
   footer also bumps the minor version while the project is pre-1.0. Commits with any other prefix
   (`chore:`, `docs:`, ...) do not trigger a release. Since the repository squash-merges, the **PR
   title** is what ends up in the commit history and therefore what release-please parses.
2. release-please keeps a single `chore(main): release` PR open covering every package with a pending
   bump.
3. Merging that PR publishes the packages whose versions are not on Maven Central yet, then tags
   each of them. Tags carry the component: `chippy-v0.1.2`, `rocketchip-v0.2.0`.

Tagging and the GitHub release for a package are one step, done by release-please: creating the tag
is a side effect of creating the release, and the same step clears the merged release PR's
`autorelease: pending` label, which release-please requires before it will propose anything new. Only
the packages a run actually bumped are tagged and released, not all seven.

The publish job works out what to upload by asking Central which versions already exist, rather than
by reading release-please's outputs — those do not exist yet, because tagging deliberately runs
afterwards. That also makes it idempotent: re-running uploads only what is still missing.

### Publishing credentials

Whatever a release publishes goes to Central as a single signed bundle, uploaded by
`mill.javalib.SonatypeCentralPublishModule/publishAll`, so it is accepted whole or not at all — and
costs one deployment against Central's publishing limits rather than one per package. Unlike the
GitHub Pages repository this replaced, that needs four repository secrets, which
`.github/workflows/release.yml` passes to Mill under its own `MILL_`-prefixed names:

- `SONATYPE_USERNAME` and `SONATYPE_PASSWORD` — a *user token*, generated from the account page of
  the [Central Portal](https://central.sonatype.com/). These are not the portal login itself.
- `PGP_SECRET_BASE64` and `PGP_PASSPHRASE` — the base64-encoded private key the artifacts are signed
  with, and its passphrase. Central rejects unsigned uploads, and the matching public key has to be
  on a public keyserver. `./mill mill.javalib.SonatypeCentralPublishModule/initGpgKeys` generates a
  key pair, publishes the public half and prints both values ready to paste in.

The `io.github.ucb-substrate` namespace also has to be verified against the account doing the
publishing before its first upload. Because `ucb-substrate` is a GitHub organization rather than a
personal account, Central does not grant it automatically on login: add the namespace in the portal,
then create a public repository in the organization named after the verification key it hands back.
Everything else still runs with the built-in `GITHUB_TOKEN`.

Central refuses a version it already holds, but the publish job selects on exactly that, so the
`workflow_dispatch` escape hatch is safe to re-run: it uploads only what is still missing and does
nothing once everything has landed. Give Central a few minutes to reach `PUBLISHED` before
re-running, though — `repo1` lags a publish, so a re-run started immediately after one can still see
a package as missing and try it again.

### Examples

The projects under `examples/` are consumers, not published packages. Each is its own nested build
that depends on Chippy the way an external project would — by released coordinate, resolved from
Maven Central — rather than via `moduleDeps`. None of them extends `PublishModule`, which is what
keeps them out of the wildcards the release and ci jobs resolve. **Do not make an example a
`PublishModule`**: it would be picked up by the release, and because its dependencies are the
artifacts that same job is producing, the build would fail to resolve them on a clean checkout.

Their pinned versions are bumped automatically, as part of the release PR. The `sync-examples` job in
`.github/workflows/release.yml` runs after release-please has opened or updated that PR, rewrites the
example coordinates to the versions it proposes, and pushes the result onto the same branch. A
release is therefore one pull request, and the examples always demonstrate the version being
published.

Those versions are not on Maven Central at that point — they are what the PR is proposing to publish.
That is exactly the window the ci workflow's "Build Chippy from source for a release" step covers,
which is keyed off release-please's branch name and off the `chore(main): release` commit that lands
when the PR merges.

release-please cannot do the rewrite itself. Its component-scoped updaters only handle
json/toml/yaml/xml, and the annotation-based generic updater keeps a single version context per file,
while every example build file mixes packages from several version lines — annotating one would
rewrite every coordinate in it with the same version. Keeping literal versions in the build files
rather than reading them from the manifest is also what keeps the examples copy-pasteable.

`.github/scripts/sync-example-versions.py` does the rewrite, taking the artifact-to-version mapping
from the build itself so it cannot drift from `versionLine`. To run it by hand:

```
./mill show '__.artifactMetadata' 2>/dev/null | python3 .github/scripts/sync-example-versions.py
./mill show '__.artifactMetadata' 2>/dev/null | python3 .github/scripts/sync-example-versions.py --check
```

The snippets in this README *are* still updated automatically: they only ever show `diplomacy`, so
the file is listed under the `rocket-chip` package's `extra-files` and the block annotations in it
have a single version context. Note that release-please scans this file, so avoid writing the literal
block-annotation markers in prose — an unmatched opening marker turns the rest of the file into a
replacement zone.
