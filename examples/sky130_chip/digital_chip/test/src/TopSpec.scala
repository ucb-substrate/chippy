package examples.sky130_chip.digital_chip

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

import org.scalatest.funspec.AnyFunSpec
import edu.berkeley.cs.chippy.{ChippyStage, Simulator}
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.prci._
import edu.berkeley.cs.chippy.TLTesterParams
import edu.berkeley.cs.chippy.TLTester
import edu.berkeley.cs.chippy.TLTesterIO
import edu.berkeley.cs.chippy.TLTesterReq
import edu.berkeley.cs.chippy.TLTesterResp
import svsim.verilator.Backend.CompilationSettings
import svsim.Workspace.getProjectRootOrCwd
import _root_.circt.stage.ChiselStage
import testchipip.uart.UARTAdapter
import freechips.rocketchip.jtag.JTAGIO
import freechips.rocketchip.devices.debug.SimJTAG
import chipyard.harness.ClockSourceAtFreqMHz
import testchipip.tsi._
import testchipip.serdes.SerialTLKey
import chisel3.simulator.stimulus.RunUntilSuccess
import chisel3.testing.HasTestingDirectory
import java.nio.file.Paths
import os.RelPath
import os.Path

class TestDriver extends ExtModule {
  setInline(
    "TestDriver.v",
    """module TestDriver(
      |);
      | $finish
      |endmodule
    """.stripMargin
  )
}

class TestHarness extends Module {
  val io = IO(new Bundle {
    val success = Output(Bool())
  })

  implicit val p: Parameters = new DigitalChipConfig

  val digitalFreqMHz = 500

  val digitalClock = Wire(Clock())
  val source = Module(new ClockSourceAtFreqMHz(digitalFreqMHz))
  source.io.power := true.B
  source.io.gate := false.B
  digitalClock := source.io.clk

  val chiptop_lazy = LazyModule(new DigitalChipTop)
  val chiptop = Module(chiptop_lazy.module)
  chiptop.io.clock := digitalClock
  chiptop.io.reset := reset.asAsyncReset

  val div =
    (digitalFreqMHz.toDouble * 1000000 / chiptop.io.uart.c.initBaudRate.toDouble).toInt
  UARTAdapter.connect(Seq(chiptop.io.uart), div, false)

  io.success := false.B

  val dtm_success = WireInit(false.B)
  when(dtm_success) { io.success := true.B }
  val jtag_wire = Wire(new JTAGIO)
  jtag_wire.TDO.data := chiptop.io.jtag.TDO
  jtag_wire.TDO.driven := true.B
  chiptop.io.jtag.TCK := jtag_wire.TCK
  chiptop.io.jtag.TMS := jtag_wire.TMS
  chiptop.io.jtag.TDI := jtag_wire.TDI
  val jtag = Module(new SimJTAG(tickDelay = 3))
  jtag.connect(
    jtag_wire,
    digitalClock,
    reset.asBool,
    ~(reset.asBool),
    dtm_success
  )

  val driver = Module(new TestDriver)

  chiptop.io.serial_tl.clock_in := digitalClock
  withClock(digitalClock) {
    val ram = Module(
      LazyModule(
        new SerialRAM(chiptop_lazy.system.serdessers(0), p(SerialTLKey)(0))(
          chiptop_lazy.system.serdessers(0).p
        )
      ).module
    )
    ram.io.ser.in <> chiptop.io.serial_tl.out
    chiptop.io.serial_tl.in <> ram.io.ser.out

    val success =
      SimTSI.connect(ram.io.tsi, digitalClock, reset)
    when(success) { io.success := true.B }
  }
}

class DigitalChipTopSpec extends AnyFunSpec {
  val testRoot = Path(getProjectRootOrCwd.toAbsolutePath) / "build"

  describe("DigitalChipTop") {
    it("should generate valid System Verilog") {
      implicit val p = new DigitalChipConfig
      ChiselStage.emitCHIRRTLFile(
        LazyModule(new DigitalChipTop).module,
        args = Array(
          "--target-dir",
          (testRoot / "Top_should_generate_valid_System_Verilog").toString()
        )
      )
      ChiselStage.emitSystemVerilogFile(
        LazyModule(new DigitalChipTop).module,
        args = Array(
          "--target-dir",
          (testRoot / "Top_should_generate_valid_System_Verilog").toString()
        )
      )
    }

    it("should run hello.riscv") {
      implicit val p = new DigitalChipConfig
      val sourceDir = testRoot / "Top_should_run_hello_riscv/src"
      val workDir = testRoot / "Top_should_run_hello_riscv/work"
      ChiselStage.emitSystemVerilogFile(
        new TestHarness,
        args = Array(
          "--target-dir",
          sourceDir.toString
        )
      )
      val sourceFiles = Simulator.getSourceFiles(sourceDir)

      val cmdFiles = Simulator.verilatorCmdFiles(
        "TestDriver",
        workDir,
        sourceFiles = sourceFiles,
        incDirs = os.walk(sourceDir).filter(os.isDir) ++ Seq(sourceDir)
      )

      os.proc(
        "/bin/bash",
        "-c",
        cmdFiles.simScript
      ).call(cwd = workDir)

    }
  }
}
