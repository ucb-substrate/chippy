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

class DigitalSystem(implicit p: Parameters) extends chipyard.DigitalTop
  // Support instantiating a global NoC interconnect
  with constellation.soc.CanHaveGlobalNoC
  // Enables optionally having a chip-to-chip communication
  // TODO: Instantiate CTC in config
  with testchipip.ctc.CanHavePeripheryCTC 

class DigitalChipTopIO(implicit p: Parameters) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(AsyncReset())
  val jtag = new JTAGChipIO(false)
  val serial_tl = new DecoupledExternalSyncPhitIO(1)
  val uart = new UARTPortIO(p(PeripheryUARTKey)(0))
}

class DigitalChipTop(implicit p: Parameters) extends LazyModule with BindingScope {
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

class DigitalChipConfig extends Config (

  //==================================
  // Set up buses
  //==================================
  new constellation.soc.WithSbusNoC(constellation.protocol.SplitACDxBETLNoCParams(
    constellation.protocol.DiplomaticNetworkNodeMapping(
      inNodeMapping = ListMap(
        "Core 0 ICache"   -> 0,  // Shuttle 0 (left)
        "Core 1 ICache"   -> 1,  // Shuttle 1 (right)
        "debug[0]"        -> 2,  // Front BUS
        "Core 2 DCache"   -> 4,  // RocketTile
        ),
      outNodeMapping = ListMap(
        "Core 0 TCM"           -> 0,  // Shuttle 0 TCM (left)
        "Core 1 TCM"           -> 1,  // Shuttle 1 TCM (right)
        "ctrls[0]"             -> 2,  // PBUS
        "ram[2],serdesser[2]|" -> 3,  // L2   (top)
        "ram[3],serdesser[3]|" -> 3,  // L2   (top)
        "ram[1],serdesser[1]|" -> 3,  // L2   (bottom)
        "ram[0],serdesser[0]|" -> 3,  // L2   (bottom)
        "ram[0]|"              -> 6,  // SBUS SPAD (?)
        "ram[1]|"              -> 6,  // MBUS SPAD (?)
        )
      ),
    acdNoCParams = NoCParams(
      topology        = BidirectionalTorus1D(7),
      channelParamGen = (a, b) => UserChannelParams(Seq.fill(6) { UserVirtualChannelParams(5) }, unifiedBuffer = false),
      routerParams    = (i) => UserRouterParams(combineRCVA=true, combineSAST=true),
      routingRelation = BlockingVirtualSubnetworksRouting(BidirectionalTorus1DShortestRouting(), 3, 2)
    ),
  beNoCParams = NoCParams(
    topology        = UnidirectionalTorus1D(7),
    channelParamGen = (a, b) => UserChannelParams(Seq.fill(4) { UserVirtualChannelParams(5) }, unifiedBuffer = false),
    routerParams    = (i) => UserRouterParams(combineRCVA=true, combineSAST=true),
    routingRelation = BlockingVirtualSubnetworksRouting(UnidirectionalTorus1DDatelineRouting(), 2, 2)
  ),
beDivision=8,
), inlineNoC=true) ++
  new chipyard.config.WithSystemBusWidth(256) ++


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
  // Saturn DMA
  // ICache
  new freechips.rocketchip.rocket.WithL1ICacheWays(2) ++
  new freechips.rocketchip.rocket.WithL1ICacheSets(128) ++
  new freechips.rocketchip.rocket.WithL1ICacheBlockBytes(64) ++
  new freechips.rocketchip.rocket.WithNBigCores(1) ++
  // DCache
  new freechips.rocketchip.rocket.WithL1DCacheBlockBytes(64) ++
  new freechips.rocketchip.rocket.WithL1DCacheSets(128) ++
  new freechips.rocketchip.rocket.WithL1DCacheWays(4) ++

  //==================================
  // Shuttle Tile + Saturn Cores
  //==================================
  // new shuttle.common.WithAsynchronousShuttleTiles(3, 3, location=InCluster(0)) ++ // Add async crossings between RocketTile and uncore
  new saturn.shuttle.WithShuttleVectorUnit(512, 256, VectorParams.opuParams) ++
  new shuttle.common.WithShuttleTileBeatBytes(16) ++
  new shuttle.common.WithTCM(size=128L << 10, banks=2) ++
  new shuttle.common.WithShuttleTileBoundaryBuffers() ++
  // ICache
  new shuttle.common.WithL1ICacheWays(2) ++
  new shuttle.common.WithL1ICacheSets(64) ++
  // DCache
  new shuttle.common.WithL1DCacheWays(2) ++
  // new shuttle.common.WithL1DCacheSets(256) ++
  new shuttle.common.WithL1DCacheBanks(1) ++
  new shuttle.common.WithL1DCacheTagBanks(1) ++
  new shuttle.common.WithShuttleTileBeatBytes(16) ++
  new shuttle.common.WithNShuttleCores(2) ++

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
      phyParams = testchipip.serdes.DecoupledExternalSyncSerialPhyParams(phitWidth=1, flitWidth=16)
    ),
  )) ++
  // Remove axi4 mem port
  new freechips.rocketchip.subsystem.WithNoMemPort ++


  //==================================
  // Set up memory
  //==================================
  new chipyard.config.WithInclusiveCacheInteriorBuffer ++
  new chipyard.config.WithInclusiveCacheExteriorBuffer ++
  new freechips.rocketchip.subsystem.WithInclusiveCache(nWays=4, capacityKB=512, outerLatencyCycles=4) ++
  new freechips.rocketchip.subsystem.WithNBanks(4) ++
  new testchipip.soc.WithNoScratchpadMonitors ++
  new testchipip.soc.WithScratchpad(base=0x580000000L,
    size=(1L << 17), // 128KB
    banks=2,
    partitions=1,
    buffer=BufferParams.default,
    outerBuffer=BufferParams.default) ++

  //==================================
  // Set up clock./reset
  //==================================
  // Create the uncore clock group
  new chipyard.clocking.WithClockGroupsCombinedByName(
    ("uncore", Seq("implicit", "sbus", "mbus", "cbus",
      "system_bus", "fbus", "pbus"), Nil)) ++
new chipyard.clocking.WithPassthroughClockGenerator ++
new chipyard.config.AbstractConfig
)

