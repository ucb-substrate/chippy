package examples.sky130_chip.digital_chip

import os.Path
import circt.stage.ChiselStage
import java.nio.file.Paths

object Utils {
  val root = Path(
    Paths.get(sys.env("MILL_TEST_RESOURCE_DIR")).toAbsolutePath
  ) / os.up / os.up
  val buildRoot = root / "build"

  def writeSourceFilesList(path: Path, sourceFiles: Seq[Path]) = {
    os.makeDir.all(path / os.up)
    os.write.over(path, sourceFiles.map(_.toString).mkString("\n"))
  }

  def writeVerilatorSimScript(
      path: Path,
      topModule: String,
      sourceFilesList: Path,
      binaryPath: Path,
      incDirs: Seq[Path] = Seq.empty,
      optLevel: Option[String] = None,
  ) = {
    os.makeDir.all(path / os.up)
    os.write.over(
      path,
      s"""#!/bin/bash
set -ex -o pipefail
verilator \\
  --cc \\
  --exe \\
  --build \\
  --main \\
  -o ../simulation \\
  --top-module ${topModule} \\
  --Mdir verilated-sources \\
  --assert \\
  --timing \\
  --max-num-width 1048576 \\${
  optLevel match {
    case Some(v) => s"\n  $v \\"
    case None => ""
  }
}${
  incDirs.map(dir => s"\n  +incdir+$dir \\").mkString("")
}
  --vpi \\
  +define+layer$$Verification$$Assert$$Temporal \\
  +define+layer$$Verification$$Assume$$Temporal \\
  +define+layer$$Verification$$Cover$$Temporal \\
  +define+VERILATOR \\
  -Wno-fatal \\
  -CFLAGS "$$CXXFLAGS -O3 -std=c++17 -DVERILATOR -I$$RISCV/include" \\
  -LDFLAGS "$$LDFLAGS -L$$RISCV/lib -Wl,-rpath,$$RISCV/lib -lriscv -lfesvr" \\
  -F ${sourceFilesList.toString}
script -f -c "./simulation ${binaryPath} </dev/null 2> >(spike-dasm > simulation.out)" simulation.log
"""
    )
  }

  def writeVcsSimScript(
      path: Path,
      topModule: String,
      sourceFilesList: Path,
      binaryPath: Path,
      incDirs: Seq[Path] = Seq.empty,
      optLevel: Option[String] = None,
  ) = {
    os.makeDir.all(path / os.up)
    os.write.over(
      path,
      s"""#!/bin/bash
set -ex -o pipefail
vcs \\
  -full64\\
  -CFLAGS "$$CXXFLAGS -O3 -std=c++17 -I$$RISCV/include" \\
  -LDFLAGS "$$LDFLAGS -L$$RISCV/lib -Wl,-rpath,$$RISCV/lib" \\
  -lriscv -lfesvr \\
  -notice -line +lint=all,noVCDE,noONGS,noUI -error=PCWM-L -error=noZMMCM \\
  -timescale=1ns/10ps -quiet -q +rad +vcs+lic+wait +vc+list \\
  -f ${sourceFilesList.toString} -sverilog +systemverilogext+.sv+.svi+.svh+.svt -assert svaext +libext+.sv +v2k +verilog2001ext+.v95+.vt+.vp +libext+.v \\
  -debug_pp \\
  -top $topModule \\${
  incDirs.map(dir => s"\n  +incdir+$dir \\").mkString("")
}
  +define+VCS +define+FSDB -o simulation -Mdir=vcs-sources
script -f -c "./simulation ${binaryPath} </dev/null 2> >(spike-dasm > simulation.out)" simulation.log
"""
    )
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

  def simulateTopWithBinary(workDir: Path, binaryPath: Path) = {
    assert(
      os.exists(binaryPath),
      "The provided binary does not exit. You may have to run `make` in the `software/` directory to make the binary first"
    )
    os.remove.all(workDir)
    os.makeDir.all(workDir)
    val sourceDir = workDir / "src"
    val simDir = workDir / "sim"
    ChiselStage.emitSystemVerilogFile(
      new SimTop(binaryPath),
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
      binaryPath,
      incDirs = os.walk(sourceDir).filter(os.isDir) ++ Seq(sourceDir)
    )

    os.proc(
      "/bin/bash",
      simScript
    ).call(stdout = os.Inherit, stderr = os.Inherit, cwd = simDir)
  }
}
