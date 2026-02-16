package examples.mmio_adder

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._

import org.scalatest.funspec.AnyFunSpec
import edu.berkeley.cs.chippy.ChippyStage
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.prci._
import edu.berkeley.cs.chippy.TLTesterParams
import edu.berkeley.cs.chippy.TLTester
import edu.berkeley.cs.chippy.TLTesterIO
import edu.berkeley.cs.chippy.TLTesterReq
import edu.berkeley.cs.chippy.TLTesterResp
import chisel3.simulator.ChiselSim
import chisel3.simulator.HasSimulator.simulators.verilator
import svsim.verilator.Backend.CompilationSettings

class TestHarness(implicit p: Parameters) extends LazyModule {
  val tltParams = TLTesterParams()
  val mmioAdderParams = MmioAdderParams()
  // TODO: Currently only addresses that are aligned with beatBytes are addressable.
  val beatBytes = 8;

  val tlt = LazyModule(new TLTester(tltParams, beatBytes))

  val mmioAdder = LazyModule(new MmioAdder(mmioAdderParams, beatBytes))
  val clockSource = ClockSourceNode(Seq(ClockSourceParameters()))

  mmioAdder.clockNode := clockSource
  mmioAdder.node := tlt.node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new TLTesterIO(tltParams))

    io <> tlt.module.io

    clockSource.out(0)._1.clock := clock
    clockSource.out(0)._1.reset := reset
  }
}

class MmioAdderSpec extends AnyFunSpec with ChiselSim {
  describe("MmioAdder") {
    it("should add numbers written to input registers") {
      implicit val p = Parameters.empty
      val dut = new TestHarness()
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

