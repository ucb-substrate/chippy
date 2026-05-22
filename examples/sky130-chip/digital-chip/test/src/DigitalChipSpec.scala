package examples.sky130chip.digitalchip

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

import org.chipsalliance.cde.config.Config
import org.scalatest.funspec.AnyFunSpec
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.subsystem._
import freechips.rocketchip.prci._
import svsim.verilator.Backend.CompilationSettings
import svsim.Workspace.getProjectRootOrCwd
import _root_.circt.stage.ChiselStage
import testchipip.uart.UARTAdapter
import freechips.rocketchip.jtag.JTAGIO
import freechips.rocketchip.util._
import freechips.rocketchip.devices.debug.SimJTAG
import edu.berkeley.cs.chippy._
// import testchipip.tsi._
import testchipip.dram.FastRAM
import testchipip.tsi.SerialRAM
import testchipip.serdes.SerialTLKey
import chisel3.simulator.stimulus.RunUntilSuccess
import chisel3.testing.HasTestingDirectory
import java.nio.file.Paths
import os.RelPath
import os.Path
import chisel3.experimental.dataview._

import testchipip.serdes._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.diplomacy._
import chisel3.simulator.ChiselSim
import chisel3.simulator.HasSimulator.simulators.verilator

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

class SimTop(chip0BinaryPath: Path, chip1BinaryPath: Path, chip0PlusArgs: Seq[String] = Seq.empty, chip1PlusArgs: Seq[String] = Seq.empty, fast: Boolean = false)(implicit
    p: Parameters
) extends RawModule {
  val driver = Module(new TestDriver)
  val harness = Module(new TestHarness(chip0BinaryPath, chip1BinaryPath, chip0PlusArgs, chip1PlusArgs, fast))
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

class TestHarness(chip0BinaryPath: Path, chip1BinaryPath: Path, chip0PlusArgs: Seq[String] = Seq.empty, chip1PlusArgs: Seq[String] = Seq.empty, fast: Boolean = false)(implicit
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

  def connectChip(binaryPath: Path, plusArgs: Seq[String], chipId: Int): Unit = {
    val allPlusArgs = if (fast) plusArgs :+ s"+loadmem=${binaryPath.toString}" else plusArgs

    val chiptop_lazy = LazyModule(new DigitalChipTop)
    val chiptop = Module(chiptop_lazy.module)
    chiptop.io.clock := digitalClock
    chiptop.io.reset := io.reset.asAsyncReset

    val div =
      (digitalFreqMHz.toDouble * 1000000 / chiptop.uart.c.initBaudRate.toDouble).toInt
    UARTAdapter.connect(Seq(chiptop.uart), div, false)

    val dtm_success = WireInit(false.B)
    when(dtm_success) { io.success := true.B }
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

    chiptop.chip_id := chipId.U

    val success = if (fast) {
      val ram = Module(LazyModule(new FastRAM(chiptop_lazy.system.serdessers(0), p(SerialTLKey)(0), chipId = chipId)(
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
    when(success) { io.success := true.B }
  }

  withClockAndReset(digitalClock, io.reset) {
    io.success := false.B
    connectChip(chip0BinaryPath, chip0PlusArgs, chipId = 0)
    connectChip(chip1BinaryPath, chip1PlusArgs, chipId = 1)
  }
}

class DigitalChipAdderTestHarness(implicit p: Parameters) extends LazyModule {
  val tltParams = TLTesterParams()
  val beatBytes = 8
  val flitWidth = 32 
  val phitWidth = 32 
  val channels = 5

  val tlt = LazyModule(new TLTester(tltParams, beatBytes))
  val serdes = LazyModule(new TLSerdesser(
    flitWidth = flitWidth,
    clientPortParams = None,
    managerPortParams = Some(TLSlavePortParameters.v1(
      beatBytes = beatBytes,
      managers = Seq(TLSlaveParameters.v1(
        address = Seq(AddressSet(0x4000, 0xfff)),
        regionType = RegionType.UNCACHED,
        supportsGet = TransferSizes(1, beatBytes),
        supportsPutFull = TransferSizes(1, beatBytes)
      ))
    ))
  ))

  serdes.managerNode.get := TLBuffer() := tlt.node
  
  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new SerialTLTesterIO(tltParams))
    io <> tlt.module.io

    val chiptop = Module(LazyModule(new DigitalChipTop).module)
    chiptop.io.clock := clock
    chiptop.io.reset := reset.asAsyncReset
    
    /** tie-off unused signals */
    chiptop.uart.rxd := 1.U
    chiptop.jtag.TCK := false.B.asClock
    chiptop.jtag.TMS := false.B
    chiptop.jtag.TDI := false.B
    chiptop.chip_id  := 0.U
    chiptop.serial_tl.clock_in := clock

    val phyParams = DecoupledInternalSyncSerialPhyParams(
      phitWidth = phitWidth,
      flitWidth = flitWidth,
      flitBufferSz = 8 
    )

    /** set up PHY for arbiting serdes streams to chiptop */
    val serialPhy = Module(new DecoupledSerialPhy(channels, phyParams))

    /** set PHY clocks + resets */
    serialPhy.io.outer_clock := clock
    serialPhy.io.outer_reset := reset
    serialPhy.io.inner_clock := clock
    serialPhy.io.inner_reset := reset

    /** hook up serdes and chiptop to PHY */
    serialPhy.io.inner_ser <> serdes.module.io.ser

    chiptop.serial_tl.in <> serialPhy.io.outer_ser.out
    serialPhy.io.outer_ser.in <> chiptop.serial_tl.out
  }
}

class DigitalChipSpec extends AnyFunSpec with ChiselSim {
  describe("DigitalChip") {
    it("should generate valid System Verilog") {
      implicit val p = new DigitalChipConfig
      ChiselStage.emitSystemVerilogFile(
        LazyModule(new DigitalChipTop).module,
        args = Array(
          "--target-dir",
          (Utils.buildRoot / "DigitalChip_should_generate_valid_System_Verilog")
            .toString()
        )
      )
    }

    it("should run hello.riscv") {
      implicit val p = new DigitalChipConfig(sim = true)
      val workDir = Utils.buildRoot / "DigitalChip_should_run_hello_riscv"

      // TODO: Figure out why this passes even when simulation errors.
      Utils.simulateTopWithBinaries(
        workDir,
        Utils.softwareDir / "hello0.riscv",
        Utils.softwareDir / "hello1.riscv"
      )
    }

    it("should run hello.riscv with FastRAM") {
      implicit val p = new DigitalChipConfig(sim = true)
      val workDir = Utils.buildRoot / "DigitalChip_should_run_hello_riscv_fast"

      Utils.simulateTopWithBinaries(
        workDir,
        Utils.softwareDir / "hello.riscv",
        Utils.softwareDir / "hello.riscv",
        fast = true
      )
    }

    it("should be able to be probed over SerialTL by an external manager") {
      implicit val p = new DigitalChipConfig(sim = true)

      val dut = new DigitalChipAdderTestHarness()

      implicit val simulator = verilator(
        verilatorSettings = CompilationSettings.default
          .withDisableFatalExitOnWarnings(true)
          .withTraceStyle(
            Some(
              svsim.verilator.Backend.CompilationSettings.TraceStyle(
                svsim.verilator.Backend.CompilationSettings.TraceKind.Vcd,
                traceUnderscore = true,
                maxArraySize = Some(1024),
                maxWidth = Some(1024),
                traceDepth = Some(1024)
              )
            )
          )
      )

      simulate(LazyModule(dut).module, additionalResetCycles = 5) { c =>
        enableWaves()

        c.io.write(c.clock, "h4000".U, 1.U)
        c.io.write(c.clock, "h4008".U, 2.U)
        c.io.expect(c.clock, "h4010".U, 3.U)

        println("[TEST] Success")
      }
    }
  }
}
