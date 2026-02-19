package examples.sky130_chip.digital_chip

import org.chipsalliance.cde.config.Config
import testchipip.soc._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.{Config, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._
import freechips.rocketchip.subsystem._
import chipyard.harness.BuildTop
import chipyard.iobinders._
import sifive.blocks.devices.uart._
import testchipip._
import testchipip.serdes._
import testchipip.boot._
import scala.collection.immutable.ListMap
import constellation.channel._
import constellation.routing._
import constellation.router._
import constellation.topology._
import constellation.noc._
import constellation.soc.{GlobalNoCParams}
import shuttle.common._
import saturn.common.{VectorParams}
import freechips.rocketchip.util.{AsyncQueueParams}
import freechips.rocketchip.subsystem.WithoutTLMonitors
import freechips.rocketchip.prci._
import freechips.rocketchip.devices.debug._
import freechips.rocketchip.util._

class DigitalSystemSimple(implicit p: Parameters) extends chipyard.DigitalTop
  // Support instantiating a global NoC interconnect
  with constellation.soc.CanHaveGlobalNoC
  // Enables optionally having a chip-to-chip communication
  // TODO: Instantiate CTC in config
  with testchipip.ctc.CanHavePeripheryCTC 

class DigitalChipTopSimpleIO(implicit p: Parameters) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(AsyncReset())
  val jtag = new JTAGChipIO(false)
  val serial_tl = new DecoupledExternalSyncPhitIO(1)
  val uart = new UARTPortIO(p(PeripheryUARTKey)(0))
}

class DigitalChipTopSimple(implicit p: Parameters) extends LazyModule with BindingScope {
  val system = LazyModule(new DigitalSystem)
  val clockGroupsSourceNode = ClockGroupSourceNode(Seq(ClockGroupSourceParameters()))
  system.chiptopClockGroupsNode := clockGroupsSourceNode
  val debugClockSinkNode = ClockSinkNode(Seq(ClockSinkParameters()))
  debugClockSinkNode := system.locateTLBusWrapper(p(ExportDebug).slaveWhere).fixedClockNode
  def debugClockBundle = debugClockSinkNode.in.head._1

  override lazy val module = new DigitalChipTopImpl
  class DigitalChipTopImpl extends LazyModuleImp(this) with DontTouch{
      val io = IO(new DigitalChipTopIO)

      clockGroupsSourceNode.out.foreach { case (bundle, edge) =>
        bundle.member.data.foreach { b =>
          b.clock := io.clock
          b.reset := io.reset
        }
      }

      // Connect debug pins
      val debug_io = system.debug.get
      Debug.connectDebugClockAndReset(Some(debug_io), debugClockBundle.clock)

      // We never use the PSDIO, so tie it off on-chip
      system.psd.psd.foreach { _ <> 0.U.asTypeOf(new PSDTestMode) }
      system.resetctrl.map { rcio => rcio.hartIsInReset.map { _ := debugClockBundle.reset.asBool } }
      debug_io.extTrigger.foreach { t => { t.in.req := false.B; t.out.ack := t.out.req; }} // Tie off extTrigger
      debug_io.disableDebug.foreach { d => d := false.B } // Tie off disableDebug
      // Drive JTAG on-chip IOs
      debug_io.systemjtag.map { j =>
        j.reset       := ResetCatchAndSync(j.jtag.TCK, debugClockBundle.reset.asBool)
        j.mfr_id      := p(JtagDTMKey).idcodeManufId.U(11.W)
        j.part_number := p(JtagDTMKey).idcodePartNum.U(16.W)
        j.version     := p(JtagDTMKey).idcodeVersion.U(4.W)
      }

      debug_io.systemjtag.get.jtag.TCK := io.jtag.TCK
      debug_io.systemjtag.get.jtag.TMS := io.jtag.TMS
      debug_io.systemjtag.get.jtag.TDI := io.jtag.TDI
      io.jtag.TDO := debug_io.systemjtag.get.jtag.TDO.data

      // Tie off interupts and chip ID
      system.module.interrupts := DontCare
      system.chip_id_pin.get := DontCare

      io.serial_tl <> system.serial_tls(0)
      io.uart <> system.uart(0)
  }
}

class DigitalChipSimpleConfig extends Config (
  //==================================
  // Set up TestHarness
  //==================================
  new chipyard.harness.WithAbsoluteFreqHarnessClockInstantiator ++ // use absolute frequencies for simulations in the harness
  // NOTE: This only simulates properly in VCS
  new testchipip.soc.WithChipIdPin ++                               // Add pin to identify chips
  new chipyard.harness.WithSerialTLTiedOff(tieoffs=Some(Seq(1))) ++ // Tie-off the chip-to-chip link in single-chip sims
  new chipyard.harness.WithDriveChipIdPin ++

  //==================================
  // Set up peripherals
  //==================================
  new testchipip.boot.WithNoCustomBootPin ++
  new chipyard.config.WithNoBusErrorDevices ++

  //==================================
  // Rocket
  //==================================
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++

  // 1 serial tilelink port
  new testchipip.serdes.WithSerialTL(Seq(testchipip.serdes.SerialTLParams(
      // port acts as a manager of offchip memory
      manager = Some(testchipip.serdes.SerialTLManagerParams(
        memParams = Seq(testchipip.serdes.ManagerRAMParams(
          address = BigInt("80000000", 16),
          size    = BigInt("100000000", 16)
        )),
        isMemoryDevice = true,
        slaveWhere = MBUS
      )),
      // Allow an external manager to probe this chip
      client = Some(testchipip.serdes.SerialTLClientParams()),
      // 4-bit bidir interface, synced to an external clock
      phyParams = testchipip.serdes.DecoupledExternalSyncSerialPhyParams(phitWidth=16, flitWidth=16)
    ),
  )) ++
  // Remove axi4 mem port
  new freechips.rocketchip.subsystem.WithNoMemPort ++


  new chipyard.clocking.WithPassthroughClockGenerator ++
  new chipyard.config.AbstractConfig
)

