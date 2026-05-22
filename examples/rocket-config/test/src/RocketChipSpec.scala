package examples.rocketconfig

import chisel3._
import chisel3.util._

import org.scalatest.funspec.AnyFunSpec
import org.chipsalliance.cde.config.Parameters
import _root_.circt.stage.ChiselStage
import org.chipsalliance.diplomacy.lazymodule._
import os.Path
import chisel3.simulator.ChiselSim

class SimTop(binaryPath: Path, plusArgs: Seq[String] = Seq.empty, fast: Boolean = false)(implicit
    p: Parameters
) extends RawModule {
  val driver = Module(new TestDriver)
  val harness = Module(new TestHarness(binaryPath, plusArgs, fast))
  harness.io.reset := driver.reset
  driver.success := harness.io.success
}

class TestHarness(binaryPath: Path, plusArgs: Seq[String] = Seq.empty, fast: Boolean = false)(implicit
    p: Parameters
) extends RawModule {
  val io = IO(new Bundle {
    val success = Output(Bool())
    val reset = Input(Bool())
  })

  val digitalFreqMHz = 500
  val source = Module(new ClockSourceAtFreqMHz(digitalFreqMHz))
  source.io.power := true.B
  source.io.gate := false.B
  val digitalClock = source.io.clk

  withClockAndReset(digitalClock, io.reset) {
    val chiptop_lazy = LazyModule(new RocketChipTop)
    val chiptop = Module(chiptop_lazy.module)
    chiptop.io.clock := digitalClock
    chiptop.io.reset := io.reset.asAsyncReset
    chiptop.serial_tl.clock_in := digitalClock
    chiptop.chip_id := 0.U

    RocketChipHarness.connectUart(chiptop.uart, digitalFreqMHz)
    val dtm_success = RocketChipHarness.connectJtag(chiptop.jtag, digitalClock, io.reset)
    val tsi_success = RocketChipHarness.connectSerialTLAndBoot(
      chiptop.serial_tl,
      chiptop_lazy.system.serdessers(0),
      digitalClock,
      io.reset,
      binaryPath,
      plusArgs,
      fast,
    )

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
        Utils.softwareDir / "hello.riscv"
      )
    }

    it("should run hello.riscv with waveforms") {
      implicit val p = new RocketChipConfig(sim = true)
      val workDir =
        Utils.buildRoot / "RocketChip_should_run_hello_riscv_waveforms"

      Utils.simulateTopWithBinary(
        workDir,
        Utils.softwareDir / "hello.riscv",
        debug = true,
      )
    }
  }
}
