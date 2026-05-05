package examples.rocketconfig

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

import org.chipsalliance.cde.config.Config
import org.scalatest.funspec.AnyFunSpec
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.prci._
import _root_.circt.stage.ChiselStage
import testchipip.uart.UARTAdapter
import freechips.rocketchip.jtag.JTAGIO
import freechips.rocketchip.util._
import freechips.rocketchip.devices.debug.SimJTAG
import edu.berkeley.cs.chippy._
import testchipip.dram.FastRAM
import testchipip.tsi.SerialRAM
import testchipip.serdes.SerialTLKey
import os.Path
import chisel3.experimental.dataview._

import testchipip.serdes._
import freechips.rocketchip.diplomacy._
import chisel3.simulator.ChiselSim

class ClockSourceIO extends Bundle {
  val power = Input(Bool())
  val gate = Input(Bool())
  val clk = Output(Clock())
}

class ClockSourceAtFreqMHz(val freqMHz: Double)
    extends BlackBox(
      Map(
        "PERIOD" -> DoubleParam(1000 / freqMHz)
      )
    )
    with HasBlackBoxInline {
  val io = IO(new ClockSourceIO)
  val moduleName = this.getClass.getSimpleName

  setInline(
    s"$moduleName.v",
    s"""
      |module $moduleName #(parameter PERIOD="") (
      |    input power,
      |    input gate,
      |    output clk);
      |  timeunit 1ns/1ps;
      |  reg clk_i = 1'b0;
      |  always #(PERIOD/2.0) clk_i = ~clk_i & (power & ~gate);
      |  assign clk = clk_i;
      |endmodule
      |""".stripMargin
  )
}

class SimTop(binaryPath: Path, plusArgs: Seq[String] = Seq.empty, fast: Boolean = false)(implicit
    p: Parameters
) extends RawModule {
  val driver = Module(new TestDriver)
  val harness = Module(new TestHarness(binaryPath, plusArgs, fast))
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

class TestHarnessIO extends Bundle {
  val success = Output(Bool())
  val reset = Input(Bool())
}

class TestHarness(binaryPath: Path, plusArgs: Seq[String] = Seq.empty, fast: Boolean = false)(implicit
    p: Parameters
) extends RawModule {
  val io = IO(new Bundle {
    val success = Output(Bool())
    val reset = Input(Bool())
  })

  val digitalFreqMHz = 500

  val digitalClock = Wire(Clock())
  val source = Module(new ClockSourceAtFreqMHz(digitalFreqMHz))
  source.io.power := true.B
  source.io.gate := false.B
  digitalClock := source.io.clk

  implicit def view[A <: Data, B <: Data]
      : DataView[testchipip.tsi.TSIIO, TSIIO] =
    DataView(
      _ => new TSIIO,
      _.in -> _.in,
      _.out -> _.out
    )

  withClockAndReset(digitalClock, io.reset) {
    val allPlusArgs = if (fast) plusArgs :+ s"+loadmem=${binaryPath.toString}" else plusArgs

    val chiptop_lazy = LazyModule(new RocketChipTop)
    val chiptop = Module(chiptop_lazy.module)
    chiptop.io.clock := digitalClock
    chiptop.io.reset := io.reset.asAsyncReset

    val div =
      (digitalFreqMHz.toDouble * 1000000 / chiptop.uart.c.initBaudRate.toDouble).toInt
    UARTAdapter.connect(Seq(chiptop.uart), div, false)

    val dtm_success = WireInit(false.B)
    val jtag_wire = Wire(new JTAGIO)
    jtag_wire.TDO.data := chiptop.jtag.TDO
    jtag_wire.TDO.driven := true.B
    chiptop.jtag.TCK := jtag_wire.TCK
    chiptop.jtag.TMS := jtag_wire.TMS
    chiptop.jtag.TDI := jtag_wire.TDI
    val jtag = Module(new SimJTAG(tickDelay = 3))
    jtag.connect(
      jtag_wire,
      digitalClock,
      io.reset,
      ~(io.reset),
      dtm_success
    )

    chiptop.serial_tl.clock_in := digitalClock
    chiptop.chip_id := 0.U

    val tsi_success = if (fast) {
      val ram = Module(LazyModule(new FastRAM(chiptop_lazy.system.serdessers(0), p(SerialTLKey)(0), chipId = 0)(
        chiptop_lazy.system.serdessers(0).p
      )).module)
      ram.io.ser.in <> chiptop.serial_tl.out
      chiptop.serial_tl.in <> ram.io.ser.out
      SimTSI.connect(ram.io.tsi.map(_.viewAs[TSIIO]), digitalClock, io.reset, binaryPath, allPlusArgs)
    } else {
      val ram = Module(LazyModule(new SerialRAM(chiptop_lazy.system.serdessers(0), p(SerialTLKey)(0))(
        chiptop_lazy.system.serdessers(0).p
      )).module)
      ram.io.ser.in <> chiptop.serial_tl.out
      chiptop.serial_tl.in <> ram.io.ser.out
      SimTSI.connect(ram.io.tsi.map(_.viewAs[TSIIO]), digitalClock, io.reset, binaryPath, allPlusArgs)
    }

    io.success := dtm_success || tsi_success
  }
}

class RocketChipSpec extends AnyFunSpec with ChiselSim {
  describe("RocketChip") {
    it("should generate valid System Verilog") {
      implicit val p = new RocketChipConfig
      ChiselStage.emitSystemVerilogFile(
        LazyModule(new RocketChipTop).module,
        args = Array(
          "--target-dir",
          (Utils.buildRoot / "RocketChip_should_generate_valid_System_Verilog")
            .toString()
        )
      )
    }

    it("should run hello.riscv") {
      implicit val p = new RocketChipConfig(sim = true)
      val workDir = Utils.buildRoot / "RocketChip_should_run_hello_riscv"

      Utils.simulateTopWithBinary(
        workDir,
        Utils.root / "software/hello.riscv"
      )
    }
  }
}
