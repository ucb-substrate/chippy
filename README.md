# Chippy

A mini, modular version of [Chipyard](https://github.com/ucb-bar/chipyard).

Individual packages can be easily compiled, tested, and used in other projects without setting up a complex development environment.

## Usage

To use the latest Chippy version, the intended workflow is to publish the constituent packages to your local Ivy repository.
Published releases are also pushed to GitHub Packages after the release PR is merged.

```
git clone https://github.com/ucb-substrate/chippy.git
git submodule update --init --recursive
cd chippy
./scripts/publish-local.sh
```

To include a package in a new project, simply add it to your list of dependencies. If you are using Mill 1.1.2, for example, add the following to your `build.mill` to use the `diplomacy` package:

```scala
val mvnDeps = Seq(
    mvn"edu.berkeley.cs::diplomacy:<released-version>",
)
```

Usage examples can be found in the `examples/` folder.

## CI

GitHub Actions now checks formatting, bootstraps the local Ivy artifacts needed by
the examples, compiles the default Scala targets without warnings, runs the
default Scala test suite, and publishes the Maven packages to GitHub Packages
when a release created by Release Please is published.

The long-running `examples/sky130-chip/digital-chip` binary simulation test is
tagged as a long-running test and excluded from the default `digital-chip` test
module, so it is skipped by default in local runs and in CI. To run only that
long-running test path explicitly, use:

```bash
./scripts/publish-local.sh
./mill 'examples.sky130-chip.digital-chip.ignored.testOnly' '*DigitalChipSpec'
```

## Release Flow

- `versions/workspace/*.txt` are the source of truth for the locally published package versions used by `publishLocal`.
- `versions/latest-release/*.txt` track the latest released version of each published package and are the files that Release Please updates on merge.
- The default example modules continue to target the current workspace package versions through `publishLocal`.
- Parallel `latestRelease` example modules are available in:
  - `examples.mmio-adder.latestRelease`
  - `examples.sky130-chip.digital-chip.latestRelease`
- Those `latestRelease` modules use the release-tracked package versions instead of the workspace-local ones. Resolving them against GitHub Packages may require standard Coursier/GitHub Packages credentials, depending on repository visibility.
- When a package changes, bump only the corresponding file in `versions/workspace/`. Packages that were not modified should keep their existing workspace versions.
