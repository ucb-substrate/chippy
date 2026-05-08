package examples.rocketconfig

import chisel3._
import chisel3.util._
import chisel3.experimental.{DoubleParam, ExtModule}
import chisel3.experimental.dataview._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._

import freechips.rocketchip.jtag.JTAGIO
import freechips.rocketchip.devices.debug.SimJTAG
import sifive.blocks.devices.uart.UARTPortIO
import testchipip.uart.UARTAdapter
import testchipip.dram.FastRAM
import testchipip.tsi.SerialRAM
import testchipip.serdes.{
  DecoupledExternalSyncPhitIO,
  SerialTLKey,
  TLSerdesser
}
import edu.berkeley.cs.chippy.{SimTSI, TSIIO}

/** Drives a clock at a specified frequency in MHz with optional power-down /
  * gating. Used by simulation harnesses for any chip top that wants a
  * source-synchronous-style clock distribution.
  */
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

/** Drives reset and detects success — finishes the simulation when the harness
  * raises `success` and aborts via `$fatal` after a 1 s timeout.
  *
  * When the simulator is built with `+define+DEBUG` *and* `+define+FSDB` and
  * the harness is started with `+fsdbfile=<path>`, this driver opens the FSDB
  * at simulation start and closes it on success — matching the IrisSpec
  * pattern.
  */
class TestDriver extends ExtModule {
  val success = IO(Input(Bool()))
  val reset = IO(Output(Bool()))
  setInline(
    "TestDriver.v",
    """module TestDriver(
      | input success,
      | output reg reset
      |);
      |`ifdef DEBUG
      | reg [2047:0] fsdbfile = 0;
      |`endif
      | initial begin
      |`ifdef DEBUG
      |   if ($value$plusargs("fsdbfile=%s", fsdbfile)) begin
      |`ifdef FSDB
      |     $fsdbDumpfile(fsdbfile);
      |     $fsdbDumpvars(0, SimTop, "+all");
      |`else
      |     $fdisplay(32'h80000002, "Error: +fsdbfile passed but compile did not enable +define+FSDB");
      |     $fatal;
      |`endif
      |   end
      |`endif
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
      |`ifdef DEBUG
      |`ifdef FSDB
      |   $fsdbDumpoff;
      |`endif
      |`endif
      |   $finish;
      | end
      |endmodule
    """.stripMargin
  )
}

/** Reusable wiring helpers for any `RocketChipTop` (or subclass) sim harness:
  * connect UART to UARTAdapter, JTAG to SimJTAG, and load a RISC-V binary over
  * the SerialTL link via TSI (or `+loadmem` via FastRAM when `fast = true`).
  *
  * Each helper takes individual top-level IOs so it stays type-correct across
  * subclasses of `RocketChipTop` whose inner `RocketChipTopImpl` types differ.
  */
object RocketChipHarness {
  implicit val tsiView: DataView[testchipip.tsi.TSIIO, TSIIO] =
    DataView(_ => new TSIIO, _.in -> _.in, _.out -> _.out)

  /** Instantiate a `ClockSourceAtFreqMHz` and return its always-on output
    * clock. Useful for bypass/reference clocks that need to free-run
    * independently of the chip's own digital clock. */
  def freeRunningClock(freqMHz: Double): Clock = {
    val src = Module(new ClockSourceAtFreqMHz(freqMHz))
    src.io.power := true.B
    src.io.gate := false.B
    src.io.clk
  }

  /** Drive the chip's UART through a `UARTAdapter` so stdout from the binary
    * is forwarded to the simulation log. */
  def connectUart(uart: UARTPortIO, digitalFreqMHz: Int = 500): Unit = {
    val div =
      (digitalFreqMHz.toDouble * 1000000 / uart.c.initBaudRate.toDouble).toInt
    UARTAdapter.connect(Seq(uart), div, false)
  }

  /** Drive the chip's JTAG through a `SimJTAG`. Returns the `dtm_success`
    * signal, which goes high when the debug module reports completion. */
  def connectJtag(jtag: JTAGChipIO, clock: Clock, reset: Reset): Bool = {
    val dtm_success = WireInit(false.B)
    val jtag_wire = Wire(new JTAGIO)
    jtag_wire.TDO.data := jtag.TDO
    jtag_wire.TDO.driven := true.B
    jtag.TCK := jtag_wire.TCK
    jtag.TMS := jtag_wire.TMS
    jtag.TDI := jtag_wire.TDI
    val sim_jtag = Module(new SimJTAG(tickDelay = 3))
    sim_jtag.connect(
      jtag_wire,
      clock,
      reset.asBool,
      ~(reset.asBool),
      dtm_success
    )
    dtm_success
  }

  /** Connect a `SerialRAM` (or `FastRAM` when `fast = true`) and `SimTSI` to
    * the chip's serial-TL port, loading the given binary. Returns the
    * `tsi_success` signal, which goes high when `SimTSI` exits with a
    * success code. */
  def connectSerialTLAndBoot(
      serial_tl: DecoupledExternalSyncPhitIO,
      serdesser: TLSerdesser,
      clock: Clock,
      reset: Reset,
      binaryPath: os.Path,
      plusArgs: Seq[String] = Seq.empty,
      fast: Boolean = false,
      chipId: Int = 0
  )(implicit p: Parameters): Bool = {
    val allPlusArgs =
      if (fast) plusArgs :+ s"+loadmem=${binaryPath.toString}" else plusArgs

    if (fast) {
      val ram = Module(LazyModule(new FastRAM(
        serdesser,
        p(SerialTLKey)(0),
        chipId = chipId
      )(serdesser.p)).module)
      ram.io.ser.in <> serial_tl.out
      serial_tl.in <> ram.io.ser.out
      SimTSI.connect(
        ram.io.tsi.map(_.viewAs[TSIIO]),
        clock,
        reset,
        binaryPath,
        allPlusArgs
      )
    } else {
      val ram = Module(LazyModule(new SerialRAM(
        serdesser,
        p(SerialTLKey)(0)
      )(serdesser.p)).module)
      ram.io.ser.in <> serial_tl.out
      serial_tl.in <> ram.io.ser.out
      SimTSI.connect(
        ram.io.tsi.map(_.viewAs[TSIIO]),
        clock,
        reset,
        binaryPath,
        allPlusArgs
      )
    }
  }
}
