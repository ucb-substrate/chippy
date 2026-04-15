package examples.mmioadder

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

import org.scalatest.funspec.AnyFunSpec
import edu.berkeley.cs.chippy.ChippyStage
import freechips.rocketchip.diplomacy._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.prci._
import edu.berkeley.cs.chippy.TLTesterParams
import edu.berkeley.cs.chippy.TLTester
import edu.berkeley.cs.chippy.TLTesterIO
import edu.berkeley.cs.chippy.TLTesterReq
import edu.berkeley.cs.chippy.TLTesterResp
import edu.berkeley.cs.chippy.SerialTLTester
import edu.berkeley.cs.chippy.SerialTLTesterIO
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

class SerialTestHarness(implicit p: Parameters) extends LazyModule {
  val idBits = 2
  val tltParams = TLTesterParams()
  val beatBytes = 8
  val serWidth = 16 

  val tlt = LazyModule(new TLTester(tltParams, beatBytes))

  val serdes = LazyModule(new TLSerdesser(
    flitWidth = serWidth,
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

  val desser = LazyModule(new TLSerdesser(
    flitWidth = serWidth,
    managerPortParams = None,
    clientPortParams = Some(TLMasterPortParameters.v1(
      clients = Seq(TLMasterParameters.v1(
        name = "tl-desser",
        sourceId = IdRange(0, 1 << idBits)
      ))
    ))
  ))

  val mmioAdder = LazyModule(new MmioAdder(MmioAdderParams(), beatBytes))
  val clockSource = ClockSourceNode(Seq(ClockSourceParameters()))

  serdes.managerNode.get := TLBuffer() := tlt.node
  mmioAdder.node := TLBuffer() := desser.clientNode.get
  mmioAdder.clockNode := clockSource

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new SerialTLTesterIO(tltParams))
    val qDepth = 5
    io <> tlt.module.io

    for (i <- 0 until 5) {
      desser.module.io.ser(i).in <> Queue(serdes.module.io.ser(i).out, qDepth)
      serdes.module.io.ser(i).in <> Queue(desser.module.io.ser(i).out, qDepth)
    }

    clockSource.out(0)._1.clock := clock
    clockSource.out(0)._1.reset := reset
  }
}

class SerialMmioAdderSpec extends AnyFunSpec with ChiselSim {
  describe("MmioAdder") {
    it("should add numbers written to input registers") {
      implicit val p = Parameters.empty
      val dut = new SerialTestHarness()
      implicit val simulator = verilator(verilatorSettings =
        CompilationSettings.default
          .withDisableFatalExitOnWarnings(true)
          .withTraceStyle(
            Some(
              svsim.verilator.Backend.CompilationSettings
                .TraceStyle(
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
