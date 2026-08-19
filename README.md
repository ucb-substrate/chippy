# Chippy

A mini, modular version of [Chipyard](https://github.com/ucb-bar/chipyard).

Individual packages can be easily compiled, tested, and used in other projects without setting up a complex development environment.

## Usage

To include a package in a new project, add the dependency. If you are using Mill 1.1.2, for example,
add the following to your `build.mill` to use the `diplomacy` package:

<!-- x-release-please-start-version -->
```scala
def mvnDeps = Seq(
    mvn"io.github.ucb-substrate::diplomacy:0.1.2",
)
```
<!-- x-release-please-end -->

The equivalent for sbt:

<!-- x-release-please-start-version -->
```scala
libraryDependencies += "io.github.ucb-substrate" %% "diplomacy" % "0.1.2"
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


## Versioning

Versions are managed by [release-please](https://github.com/googleapis/release-please). Every
package shares one version, held in `version.txt`.
