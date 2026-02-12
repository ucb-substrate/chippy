import Tests._

val chiselVersion = "7.8.0"

ThisBuild / organization := "edu.berkeley.cs"
ThisBuild / version := "0.0.1-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.18"
ThisBuild / scalacOptions := Seq(
  "-deprecation",
  "-feature",
  "-language:reflectiveCalls",
  "-Xcheckinit",
  "-Xlint"
)

Compile / doc / scalacOptions += "-groups"

resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/service/local/repositories/snapshots/content"

lazy val chippy = (project in file("."))
  .settings(
    name := "chippy",
    scalacOptions ++= Seq(
      "-deprecation",
      "-unchecked",
      "-Ytasty-reader",
      "-Ymacro-annotations"
    ),
    libraryDependencies ++=
      Seq(
        "edu.berkeley.cs" %% "rocketchip-6.0.0" % "1.6-6.0.0-1b9f43352-SNAPSHOT",
        "org.chipsalliance" %% "chisel" % chiselVersion,
        "org.scalatest" %% "scalatest" % "3.2.18" % Test,
        "org.reflections" % "reflections" % "0.10.2",
        "com.lihaoyi" %% "sourcecode" % "0.3.1",
        "org.scala-lang" % "scala-reflect" % scalaVersion.value
      ),
    addCompilerPlugin(
      "org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full
    ),
    Test / fork := true,
    Test / testGrouping := (Test / testGrouping).value.flatMap { group =>
      import Tests._
      group.tests.map { test =>
        Group(test.name, Seq(test), SubProcess(ForkOptions()))
      }
    },
    concurrentRestrictions := Seq(Tags.limit(Tags.ForkedTestGroup, 72))
  )
