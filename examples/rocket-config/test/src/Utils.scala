package examples.rocketconfig

import os.Path
import circt.stage.ChiselStage
import java.nio.file.Paths
import org.chipsalliance.cde.config.Parameters

object Utils {
  val root = Path(
    Paths.get(sys.env("MILL_TEST_RESOURCE_DIR")).toAbsolutePath
  ) / os.up / os.up
  val buildRoot = root / "build"

  def writeSourceFilesList(path: Path, sourceFiles: Seq[Path]) = {
    os.makeDir.all(path / os.up)
    os.write.over(path, sourceFiles.map(_.toString).mkString("\n"))
  }

  def writeVcsSimScript(
      path: Path,
      topModule: String,
      sourceFilesList: Path,
      incDirs: Seq[Path] = Seq.empty,
      loadmem: Option[Path] = None,
      debug: Boolean = false
  ) = {
    val dramsim_ini = root / os.up / os.up / os.up / "testchipip" / "src" / "main" / "resources" / "dramsim2_ini"
    // When debug is on, build with FSDB recording instrumentation and pass
    // +fsdbfile=... at runtime so TestDriver opens the dump file. `+define+FSDB`
    // is already in the base CFLAGS so the existing `ifdef FSDB` blocks compile
    // either way; `+define+DEBUG` is what gates the actual fsdbDump calls.
    val debugCompileFlags =
      if (debug) " +define+DEBUG -debug_access+all -kdb -lca" else ""
    val debugRuntimeFlag =
      if (debug) " +fsdbfile=waveform.fsdb" else ""
    os.makeDir.all(path / os.up)
    os.write.over(
      path,
      s"""#!/bin/bash
set -ex -o pipefail
vcs \\
  -full64\\
  -CFLAGS "$$CXXFLAGS -O3 -std=c++17 -I$$RISCV/include -I${(root / os.up / os.up / os.up / "DRAMSim2").toString}${incDirs.map(dir => s" -I$dir").mkString("")}" \\
  -LDFLAGS "$$LDFLAGS -L$$RISCV/lib -Wl,-rpath,$$RISCV/lib" \\
  -lriscv -lfesvr -ldramsim \\
  -notice -line +lint=all,noVCDE,noONGS,noUI -error=PCWM-L -error=noZMMCM \\
  -timescale=1ns/10ps -quiet -q +rad +vcs+lic+wait +vc+list \\
  -f ${sourceFilesList.toString} -sverilog +systemverilogext+.sv+.svi+.svh+.svt -assert svaext +libext+.sv +v2k +verilog2001ext+.v95+.vt+.vp +libext+.v \\
  -debug_pp \\
  -top $topModule \\${incDirs.map(dir => s"\n  +incdir+$dir \\").mkString("")}
  +define+layer$$Verification$$Assert$$Temporal \\
  +define+layer$$Verification$$Assume$$Temporal \\
  +define+layer$$Verification$$Cover$$Temporal \\
  +define+VCS +define+FSDB +define+RANDOMIZE_MEM_INIT +define+RANDOMIZE_REG_INIT +define+RANDOMIZE_GARBAGE_ASSIGN +define+RANDOMIZE_INVALID_ASSIGN$debugCompileFlags \\
  -o simulation -Mdir=vcs-sources
script -f -c "./simulation +permissive +dramsim +dramsim_ini_dir=${dramsim_ini.toString}${loadmem.map(p => s" +loadmem=${p.toString}").getOrElse("")}$debugRuntimeFlag +permissive-off placeholder-binary </dev/null 2> >(spike-dasm > simulation.out)" simulation.log
"""
    )
    path.toIO.setExecutable(true)
  }

  /** Finds source files within a given source directory with the given file
    * extensions.
    */
  def getSourceFiles(
      sourceDir: Path,
      fileExtensions: Seq[String] = Seq(".v", ".sv", ".cc", ".vams")
  ): Seq[Path] = {
    os
      .walk(sourceDir)
      .filter(os.isFile)
      .filter(path => fileExtensions.exists(ext => path.last.endsWith(ext)))
  }

  def simulateTopWithBinary(
      workDir: Path,
      binaryPath: Path,
      plusArgs: Seq[String] = Seq.empty,
      fast: Boolean = false,
      debug: Boolean = false
  )(implicit p: Parameters) = {
    assert(
      os.exists(binaryPath),
      s"The provided binary $binaryPath does not exist. You may have to run `make` in the `software/` directory to make the binary first"
    )
    os.remove.all(workDir)
    os.makeDir.all(workDir)
    val sourceDir = workDir / "src"
    val simDir = workDir / "sim"
    ChiselStage.emitSystemVerilogFile(
      new SimTop(binaryPath, plusArgs, fast),
      args = Array(
        "--target-dir",
        sourceDir.toString
      )
    )
    val sourceFiles = getSourceFiles(sourceDir)

    val sourceFilesList = simDir / "sourceFiles.F"
    val simScript = simDir / "simulate.sh"

    writeSourceFilesList(sourceFilesList, sourceFiles)

    writeVcsSimScript(
      simScript,
      "SimTop",
      sourceFilesList,
      incDirs = os.walk(sourceDir).filter(os.isDir) ++ Seq(sourceDir),
      loadmem = if (fast) Some(binaryPath) else None,
      debug = debug,
    )

    os.proc(
      "/bin/bash",
      simScript
    ).call(stdout = os.Inherit, stderr = os.Inherit, cwd = simDir)
  }
}
