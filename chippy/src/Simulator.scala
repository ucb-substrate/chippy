package edu.berkeley.cs.chippy

import os.Path
import os.Shellable
import java.io.BufferedWriter
import java.io.FileWriter
import java.io.File

object Simulator {
  case class VerilatorCmdFiles(
      sourceFilesList: Path,
      simScript: Path
  )

  /** Writes a source file list, prefixing .h files with a `-FI` flag to force inclusion.
   *
   *  Intended to be included in a Verilator invocation using `-F`
   */
  def writeVerilatorSourceFilesList(path: Path, sourceFiles: Seq[Path]) {
    os.makeDir.all(path / os.up)
    val src = new LineWriter(path.toString)
    try {
      for (file <- sourceFiles) {
        if (file.last.endsWith(".h")) {
          src("-FI ", file.toString)
        } else {
          src(file.toString)
        }
      }
    } finally {
      src.close()
    }
  }

  /** Writes a script that compiles source files using Verilator then runs the simulation binary. */
  def writeVerilatorSimScript(
      path: Path,
      topModule: String,
      sourceFilesList: Path,
      incDirs: Seq[Path] = Seq.empty,
      verilatorCmd: String = "verilator",
      enableAssertions: Boolean = true,
      enableTiming: Boolean = true,
      maxNumWidth: Option[Int] = Some(1048576),
      parallelJobs: Option[Int] = Some(0),
      timescale: Option[String] = Some("1ns/100ps"),
      vpi: Boolean = true,
      noFatal: Boolean = true,
      // TODO: Figure out how to do escaping more cleanly
      cflags: Option[String] = Some("\"$CXXFLAGS -O3 -std=c++17 -DVERILATOR -I$RISCV/include\""),
      ldflags: Option[String] = Some("\"$LDFLAGS -L$RISCV/lib -Wl,-rpath,$RISCV/lib -lriscv -lfesvr\""),
      preprocessorDefines: Seq[String] = Seq(
        "layer$Verification$Assert$Temporal",
        "layer$Verification$Assume$Temporal",
        "layer$Verification$Cover$Temporal",
        "VERILATOR"
      ),
      additionalCompilationLines: Seq[String] = Seq.empty,
      additionalSimulationLines: Seq[String] = Seq.empty,
  ) = {
    os.makeDir.all(path / os.up)
    val cmd = new LineWriter(path.toString)
    try {
      cmd("#!/bin/bash")
      cmd("set -exo pipefail")
      cmd(verilatorCmd, " \\")
      cmd(" --cc \\")
      cmd(" --exe \\")
      cmd(" --build \\")
      cmd(" --main \\")
      cmd(" -o ../simulation \\")
      cmd(" --top-module ", topModule, " \\")
      cmd(" --Mdir verilated-sources \\")
      if (enableAssertions) {
        cmd(" --assert \\")
      }
      if (enableTiming) {
        cmd(" --timing \\")
      }
      maxNumWidth match {
        case Some(v) => cmd(s" --max-num-width $v \\")
        case None =>
      }
      parallelJobs match {
        case Some(v) => cmd(s" -j $v \\")
        case None =>
      }
      for (dir <- incDirs) {
        cmd("  +incdir+", dir.toString, " \\")
      }
      timescale match {
        case Some(v) => cmd(s" --timescale $v \\")
        case None =>
      }
      if (vpi) {
        cmd(" --vpi \\")
      }
      for (define <- preprocessorDefines) {
        cmd(s" +define+$define \\")
      }
      if (noFatal) {
        cmd(" -Wno-fatal \\")
      }
      cflags match {
        case Some(v) => cmd(s" -CFLAGS $v \\")
        case None =>
      }
      ldflags match {
        case Some(v) => cmd(s" -LDFLAGS $v \\")
        case None =>
      }
      cmd(" -F ", sourceFilesList.toString, if (additionalCompilationLines.isEmpty) { "" } else { " \\" })
      if (additionalCompilationLines.nonEmpty) {
        cmd(" ", additionalCompilationLines.mkString(" \\\n "))
      }
      if (additionalSimulationLines.isEmpty) {
        cmd("./simulation")
      } else {
        cmd("./simulation \\")
        cmd(" ", additionalSimulationLines.mkString(" \\\n "))
      }
    } finally {
      cmd.file.setExecutable(true)
      // `BufferedWriter` closes the underlying `FileWriter` when closed.
      cmd.close()
    }
  }

  /** Finds source files within a given source directory with the given file extensions. */
  def getSourceFiles(
      sourceDir: Path,
      fileExtensions: Seq[String] = Seq(".v", ".sv", ".cc", ".h", ".vams")
  ): Seq[Path] = {
    os
      .walk(sourceDir)
      .filter(os.isFile)
      .filter(path => fileExtensions.exists(ext => path.last.endsWith(ext)))
  }
}

/** A micro-DSL for writing files. */
private class LineWriter(val path: String) {
  val file = new File(path)
  private val wrapped = new BufferedWriter(new FileWriter(file, false))
  def apply(components: String*) = {
    components.foreach(wrapped.write)
    wrapped.newLine()
  }
  def close() = wrapped.close()
}
