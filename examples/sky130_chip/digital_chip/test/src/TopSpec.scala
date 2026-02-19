package examples.sky130_chip.digital_chip

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

import org.scalatest.funspec.AnyFunSpec
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.prci._
import svsim.verilator.Backend.CompilationSettings
import svsim.Workspace.getProjectRootOrCwd
import _root_.circt.stage.ChiselStage
import testchipip.uart.UARTAdapter
import freechips.rocketchip.jtag.JTAGIO
import freechips.rocketchip.devices.debug.SimJTAG
import chipyard.harness.ClockSourceAtFreqMHz
import edu.berkeley.cs.chippyip.{SimTSI, TSIIO}
import testchipip.tsi.SerialRAM
import testchipip.serdes.SerialTLKey
import chisel3.simulator.stimulus.RunUntilSuccess
import chisel3.testing.HasTestingDirectory
import java.nio.file.Paths
import os.RelPath
import os.Path
import chisel3.experimental.dataview._

class SimTop(binaryPath: Path) extends RawModule {
  val driver = Module(new TestDriver)
  val harness = Module(new TestHarness(binaryPath))
  harness.io.reset := driver.reset
  driver.success := harness.io.success
}

class TestDriver extends ExtModule {
  val success = IO(Input(Bool()))
  val reset = IO(Output(Bool()))
  setInline(
    "TestDriver.v",
    """module TestDriver(
      | input success,
      | output reg reset
      |);
      | initial begin
      |   $display("Resetting chip for 10 ns");
      |   reset = 1'b1;
      |   #10;
      |   reset = 1'b0;
      |   $display("Running test for 1000 ns");
      |   #1000000000;
      |   $display("Test timed out!");
      |   $fatal;
      | end
      | always @(posedge success) begin
      |   $display("Test completed successfully.");
      |   $finish;
      | end
      |endmodule
    """.stripMargin
  )
}

class TestHarness(binaryPath: Path) extends RawModule {
  val io = IO(new Bundle {
    val success = Output(Bool())
    val reset = Input(Bool())
  })

  implicit val p: Parameters = new DigitalChipConfig

  val digitalFreqMHz = 500

  val digitalClock = Wire(Clock())
  val source = Module(new ClockSourceAtFreqMHz(digitalFreqMHz))
  source.io.power := true.B
  source.io.gate := false.B
  digitalClock := source.io.clk

  withClockAndReset(digitalClock, io.reset) {
    val chiptop_lazy = LazyModule(new DigitalChipTop)
    val chiptop = Module(chiptop_lazy.module)
    chiptop.io.clock := digitalClock
    chiptop.io.reset := io.reset.asAsyncReset

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
      io.reset,
      ~(io.reset),
      dtm_success
    )

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

      implicit def view[A <: Data, B <: Data]
          : DataView[testchipip.tsi.TSIIO, TSIIO] =
        DataView(
          _ => new TSIIO,
          _.in -> _.in,
          _.out -> _.out
        )
      val success =
        SimTSI.connect(
          ram.io.tsi.map(_.viewAs[TSIIO]),
          digitalClock,
          io.reset,
          binaryPath
        )
      when(success) { io.success := true.B }
    }
  }

}

class DigitalChipTopSpec extends AnyFunSpec {
  describe("DigitalChipTop") {
    it("should generate valid System Verilog") {
      implicit val p = new DigitalChipConfig
      ChiselStage.emitCHIRRTLFile(
        LazyModule(new DigitalChipTop).module,
        args = Array(
          "--target-dir",
          (Utils.buildRoot / "Top_should_generate_valid_System_Verilog")
            .toString()
        )
      )
      ChiselStage.emitSystemVerilogFile(
        LazyModule(new DigitalChipTop).module,
        args = Array(
          "--target-dir",
          (Utils.buildRoot / "Top_should_generate_valid_System_Verilog")
            .toString()
        )
      )
    }

    it("should run hello.riscv") {
      implicit val p = new DigitalChipConfig
      val workDir = Utils.buildRoot / "Top_should_run_hello_riscv"

      Utils.simulateTopWithBinary(workDir, Utils.root / "software/hello.riscv")
    }
  }
}
