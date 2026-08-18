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

Versions are managed by [release-please](https://github.com/googleapis/release-please) and are shared
by every published package. `version.txt` is the single source of truth; `build.mill` reads it, so no
version numbers are hardcoded in the build.

The flow is:

1. Land changes on `main` using [Conventional Commits](https://www.conventionalcommits.org/) — `fix:`
   bumps the patch version, `feat:` bumps the minor version, and `feat!:` / a `BREAKING CHANGE:`
   footer also bumps the minor version while the project is pre-1.0. Commits with any other prefix
   (`chore:`, `docs:`, ...) do not trigger a release. Since the repository squash-merges, the **PR
   title** is what ends up in the commit history and therefore what release-please parses.
2. release-please keeps a `chore(main): release X.Y.Z` PR open with the pending version bump.
3. Merging that PR publishes every package to Maven Central and then tags the release.

### Publishing credentials

Every package goes to Central as a single signed bundle, uploaded by
`mill.javalib.SonatypeCentralPublishModule/publishAll`, so a version is either published whole or not
at all. Unlike the GitHub Pages repository this replaced, that needs four repository secrets, which
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

Central refuses a version it already holds, so a release cannot be re-published over itself. The
`workflow_dispatch` escape hatch therefore recovers a run that failed *before* the bundle was
accepted; one that failed after that is finished by re-running the `tag` job alone.

### Examples

The projects under `examples/` are consumers, not published packages. Each is its own nested build
that depends on Chippy the way an external project would — by released coordinate, resolved from
Maven Central — rather than via `moduleDeps`. None of them extends `PublishModule`, which is what
keeps them out of the wildcards the release and ci jobs resolve. **Do not make an example a
`PublishModule`**: it would be picked up by the release, and because its dependencies are the
artifacts that same job is producing, the build would fail to resolve them on a clean checkout.

Their pinned versions are bumped automatically. Every `io.github.ucb-substrate` dependency line
carries an `x-release-please-version` comment, and the snippets in this README are wrapped in the block form of
the same annotation, so the release PR updates them alongside `version.txt`. The build files use the
per-line form rather than the block form on purpose: a block rewrites every semver-looking literal it
spans, which would also catch neighbouring lines such as the ScalaTest dependency.

Note that release-please scans this file too, so avoid writing the literal block-annotation markers
in prose — an unmatched opening marker turns the rest of the file into a replacement zone.

Because the release PR bumps the examples to the version it is about to publish, the examples briefly
reference a version that does not exist yet — from the moment the release PR is opened until the
publish job finishes after it is merged. Central takes a few more minutes to propagate a published
bundle to the mirrors coursier fetches from, so a run started immediately after a release may extend
that window slightly.
