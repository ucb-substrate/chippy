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
        // TODO: Improve MMIO read/write API
        enableWaves()
        c.io.resp.ready.poke(true.B)
        c.clock.stepUntil(c.io.req.ready, 1, 5)
        c.io.req.valid.poke(true.B)

        c.io.req.bits.addr.poke("h4000".U)
        c.io.req.bits.data.poke(1.U)
        c.io.req.bits.is_write.poke(true.B)
        c.clock.stepUntil(c.io.req.ready, 1, 5)
        c.clock.stepUntil(c.io.resp.valid, 1, 5)
        c.clock.step()

        c.io.req.bits.addr.poke("h4008".U)
        c.io.req.bits.data.poke(2.U)
        c.io.req.bits.is_write.poke(true.B)
        c.clock.stepUntil(c.io.req.ready, 1, 5)
        c.clock.stepUntil(c.io.resp.valid, 1, 5)
        c.clock.step()

        c.io.req.bits.addr.poke("h4010".U)
        c.io.req.bits.is_write.poke(false.B)
        c.clock.stepUntil(c.io.req.ready, 1, 5)
        c.clock.stepUntil(c.io.resp.valid, 1, 5)
        c.io.resp.bits.data.expect(3.U)

        c.clock.step(cycles = 5)
        println("[TEST] Success")
      }
    }
  }
}

