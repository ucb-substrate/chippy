package examples.sky130chip.digitalchip

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

import org.scalatest.funspec.AnyFunSpec
import freechips.rocketchip.diplomacy._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.prci._
import edu.berkeley.cs.chippy._
import chisel3.simulator.ChiselSim
import chisel3.simulator.HasSimulator.simulators.verilator
import svsim.verilator.Backend.CompilationSettings

import org.chipsalliance.cde.config.Parameters

import freechips.rocketchip.devices.tilelink.{DevNullParams, TLTestRAM, TLROM, TLError}
import freechips.rocketchip.subsystem.CacheBlockBytes
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import scala.math.max
import scala.util.Random

import testchipip.iceblk._
import testchipip.serdes._
import testchipip.soc._
import testchipip.util._
import testchipip.tsi._
import testchipip.ctc._

import testchipip.uart.UARTAdapter
import freechips.rocketchip.jtag.JTAGIO
import freechips.rocketchip.devices.debug.SimJTAG

class DigitalChipWithAdderTestHarness(implicit p: Parameters) extends LazyModule {
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
        address = Seq(AddressSet(0x10040000, 0xfff)),
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
    
    /** arbitrate 5 flit streams single phit stream with headers */
    val outArb = Module(new PhitArbiter(phitWidth, flitWidth, channels))

    /** convert 5 flit streams (one for each TL channel) to phit streams */
    serdes.module.io.ser.zipWithIndex.foreach { case (serChannel, i) =>
      outArb.io.in(i) <> FlitToPhit(serChannel.out, phitWidth)
    }

    chiptop.serial_tl.in.valid      := outArb.io.out.valid
    outArb.io.out.ready             := chiptop.serial_tl.in.ready
    chiptop.serial_tl.in.bits.phit  := outArb.io.out.bits.phit

    /** demux phit stream back into 5 phit streams based on headers */
    val inDemux = Module(new PhitDemux(phitWidth, flitWidth, channels))

    inDemux.io.in.valid         := chiptop.serial_tl.out.valid
    chiptop.serial_tl.out.ready  := inDemux.io.in.ready
    inDemux.io.in.bits.phit     := chiptop.serial_tl.out.bits.phit

    /** convert 5 phit streams back into flit streams */
    serdes.module.io.ser.zipWithIndex.foreach { case (serChannel, i) =>
      serChannel.in <> PhitToFlit(inDemux.io.out(i), flitWidth)
    }
  }
}

class DigitalChipWithAdderSpec extends AnyFunSpec with ChiselSim {
  describe("DigitalChipWithAdder") {
    it("should add numbers written to input registers of on-chip MMIO adder through DigitalChipTop's SerialTL port") {
      implicit val p = new DigitalChipWithAdderConfig(sim = true)

      val dut = new DigitalChipWithAdderTestHarness()

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

        c.io.write(c.clock, "h10040000".U, 1.U)
        c.io.write(c.clock, "h10040008".U, 2.U)
        c.io.expect(c.clock, "h10040010".U, 3.U)

        println("[TEST] Success")
      }
    }
  }
}