# Chippy

A mini, modular version of [Chipyard](https://github.com/ucb-bar/chipyard).

Individual packages can be easily compiled, tested, and used in other projects without setting up a complex development environment.

## Usage

Chippy releases are published under the `edu.berkeley.cs` organization to a Maven repository hosted
at <https://ucb-substrate.github.io/chippy>. No credentials are required to resolve from it.

To include a package in a new project, add the repository and the dependency. If you are using Mill
1.1.2, for example, add the following to your `build.mill` to use the `diplomacy` package:

<!-- x-release-please-start-version -->
```scala
def repositories = Seq("https://ucb-substrate.github.io/chippy")

def mvnDeps = Seq(
    mvn"edu.berkeley.cs::diplomacy:0.1.0",
)
```
<!-- x-release-please-end -->

The equivalent for sbt:

<!-- x-release-please-start-version -->
```scala
resolvers += "chippy" at "https://ucb-substrate.github.io/chippy"
libraryDependencies += "edu.berkeley.cs" %% "diplomacy" % "0.1.0"
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
3. Merging that PR tags the release and publishes all packages.

`.github/workflows/release.yml` runs every step with the built-in `GITHUB_TOKEN`, so no additional
secrets are required.

### Examples

The projects under `examples/` are consumers, not published packages. Each is its own nested build
that depends on Chippy the way an external project would — by released coordinate, resolved from the
public repository — rather than via `moduleDeps`. None of them extends `PublishModule`, which is what
keeps them out of the `__.publishM2Local` wildcard the release job uses. **Do not make an example a
`PublishModule`**: it would be picked up by the release, and because its dependencies are the
artifacts that same job is producing, the build would fail to resolve them on a clean checkout.

Their pinned versions are bumped automatically. Every `edu.berkeley.cs` dependency line carries an
`x-release-please-version` comment, and the snippets in this README are wrapped in the block form of
the same annotation, so the release PR updates them alongside `version.txt`. The build files use the
per-line form rather than the block form on purpose: a block rewrites every semver-looking literal it
spans, which would also catch neighbouring lines such as the ScalaTest dependency.

Note that release-please scans this file too, so avoid writing the literal block-annotation markers
in prose — an unmatched opening marker turns the rest of the file into a replacement zone.

Because the release PR bumps the examples to the version it is about to publish, the examples briefly
reference a version that does not exist yet — from the moment the release PR is opened until the
publish job finishes after it is merged.
