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
  def verilatorCmdFiles(
      topModule: String,
      workDir: Path,
      sourceFiles: Seq[Path] = Seq.empty,
      incDirs: Seq[Path] = Seq.empty
  ) = {
    os.makeDir.all(workDir)
    val sourceFilesList = workDir / "sourceFiles.F"
    val cmdFile = workDir / "simulation.sh"
    val src = new LineWriter(sourceFilesList.toString)
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
    val cmd = new LineWriter(cmdFile.toString)
    try {
      cmd("#!/bin/bash")
      cmd("verilator \\")
      cmd(" --cc \\")
      cmd(" --exe \\")
      cmd(" --build \\")
      cmd(" -o ../simulation \\")
      cmd("  --top-module ", topModule, " \\")
      cmd(" --Mdir verilated-sources \\")
      cmd(" --assert \\")
      cmd(" --timing \\")
      cmd(" --max-num-width 1048576 \\")
      cmd(" -j 0 \\")
      for (dir <- incDirs) {
        cmd("  +incdir+", dir.toString, " \\")
      }
      cmd(" --timescale 1ns/100ps \\")
      cmd(" --trace \\")
      cmd(" --trace-underscore \\")
      cmd(" --trace-structs \\")
      cmd(" --trace-max-array 1024 \\")
      cmd(" --trace-max-width 1024 \\")
      cmd(" --trace-depth 1024 \\")
      cmd(" -Wno-fatal \\")
      cmd(
        " -CFLAGS \"-std=c++17 -DSVSIM_ENABLE_VERILATOR_SUPPORT -DSVSIM_VERILATOR_TRACE_ENABLED  -I$RISCV/include\" \\"
      )
      cmd(" -LDFLAGS \"-L$RISCV/lib -lfesvr\" \\")
      cmd(" -F ", sourceFilesList.toString)
      // "+define+ASSERT_VERBOSE_COND=!svsimTestbench.reset",
      // "+define+PRINTF_COND=!svsimTestbench.reset",
      // "+define+STOP_COND=!svsimTestbench.reset",
      // "+define+layer$Verification$Assert$Temporal",
      // "+define+layer$Verification$Assume$Temporal",
      // "+define+layer$Verification$Cover$Temporal",
      // "+define+RANDOMIZE_REG_INIT",
      // "+define+RANDOMIZE_MEM_INIT",
      // "+define+RANDOMIZE_DELAY=1",
      // "+define+RANDOM=$urandom",
      // "+define+SVSIM_ENABLE_VCD_TRACING_SUPPORT",
    } finally {
      cmd.file.setExecutable(true)
      // `BufferedWriter` closes the underlying `FileWriter` when closed.
      cmd.close()
    }
    VerilatorCmdFiles(
      sourceFilesList,
      cmdFile
    )
  }

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

/** A micro-DSL for writing files.
  */
private class LineWriter(val path: String) {
  val file = new File(path)
  private val wrapped = new BufferedWriter(new FileWriter(file, false))
  def apply(components: String*) = {
    components.foreach(wrapped.write)
    wrapped.newLine()
  }
  def close() = wrapped.close()
}
