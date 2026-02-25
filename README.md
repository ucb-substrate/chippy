# Chippy

A mini, modular version of [Chipyard](https://github.com/ucb-bar/chipyard).

Individual packages can be easily compiled, tested, and used in other projects without setting up a complex development environment.

## Usage

To use the latest Chippy version, the intended workflow is to publish the constituent packages to your local Ivy repository.
In the future, Chippy releases will be accessible from a public Maven repository.

```
git clone https://github.com/ucb-substrate/chippy.git
git submodule update --init --recursive
cd chippy
./mill __.publishLocal
```

To include a package in a new project, simply add it to your list of dependencies. If you are using Mill 1.1.2, for example, add the following to your `build.mill` to use the `diplomacy` package:

```scala
val mvnDeps = Seq(
    mvn"edu.berkeley.cs::diplomacy:0.0.1",
)
```

Usage examples can be found in the `examples/` folder.
