# Chippy

A mini, modular version of [Chipyard](https://github.com/ucb-bar/chipyard).

Individual packages can be easily compiled, tested, and used in other projects without setting up a complex development environment.

## Usage

Chippy releases are published under the `edu.berkeley.cs` organization to a Maven repository hosted
at <https://ucb-substrate.github.io/chippy>. No credentials are required to resolve from it.

To include a package in a new project, add the repository and the dependency. If you are using Mill
1.1.2, for example, add the following to your `build.mill` to use the `diplomacy` package:

```scala
def repositories = Seq("https://ucb-substrate.github.io/chippy")

def mvnDeps = Seq(
    mvn"edu.berkeley.cs::diplomacy:0.0.1",
)
```

The equivalent for sbt:

```scala
resolvers += "chippy" at "https://ucb-substrate.github.io/chippy"
libraryDependencies += "edu.berkeley.cs" %% "diplomacy" % "0.0.1"
```

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

### How the repository is hosted

The Maven repository is a static file tree served by GitHub Pages. Because a Pages deployment
replaces the entire site, but a Maven repository is cumulative, the accumulated state is kept as a
`repo.tar.gz` asset on the `maven-repo` release. Each publish downloads that tarball, adds the new
version to it, re-uploads it, and deploys the result to Pages.

The `maven-repo` release is infrastructure, not a real release — it is deliberately not marked
"Latest" and **must not be deleted**, since it is the only copy of previously published versions.
Deleting a published version is a matter of removing its directory from the tarball.

This requires the repository's Pages source to be set to **GitHub Actions** under
Settings → Pages.
