package examples.sky130_chip.digital_chip

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
import chisel3.simulator.HasSimulator.simulators.verilator
import chisel3.simulator._
import svsim.verilator.Backend.CompilationSettings
import _root_.circt.stage.ChiselStage
import testchipip.uart.UARTAdapter
import freechips.rocketchip.jtag.JTAGIO
import freechips.rocketchip.devices.debug.SimJTAG
import chipyard.harness.ClockSourceAtFreqMHz
import testchipip.tsi._
import testchipip.serdes.SerialTLKey
import chisel3.simulator.stimulus.RunUntilSuccess
import chisel3.testing.HasTestingDirectory

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

class DigitalChipTopSpec extends AnyFunSpec with ChiselSim {
  describe("DigitalChipTop") {
    it("should generate valid System Verilog") {
      implicit val p = new DigitalChipConfig
      ChiselStage.emitCHIRRTLFile(
        LazyModule(new DigitalChipTop).module,
        args = Array(
          "--target-dir",
          "./test_run_dir/Top_should_generate_valid_System_Verilog"
        )
      )
      ChiselStage.emitSystemVerilogFile(
        LazyModule(new DigitalChipTop).module,
        args = Array(
          "--target-dir",
          "./test_run_dir/Top_should_generate_valid_System_Verilog"
        )
      )
    }

    it("should run hello.riscv") {
      implicit val p = new DigitalChipConfig
      implicit val simulator = new HasSimulator {
        override def getSimulator(implicit testingDirectory: HasTestingDirectory): Simulator[Backend] =
          new Simulator[Backend] {
            override val backend = Backend.initializeFromProcessEnvironment()
            override val tag = "verilator"
            override val commonCompilationSettings = svsim.commonCompilationSettings()
            override val backendSpecificCompilationSettings = 
        (Backend.CompilationSettings.default
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
            )))
            override val workspacePath = Files.createDirectories(testingDirectory.getDirectory).toString
          }
      }
      simulate(new TestHarness, additionalResetCycles = 5) { c =>
        RunUntilSuccess.module[TestHarness](1000, _.io.success)
      }
    }
  }
}
