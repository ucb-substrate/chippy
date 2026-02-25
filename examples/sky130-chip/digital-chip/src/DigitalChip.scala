package examples.sky130chip.digitalchip

import org.chipsalliance.cde.config.Config
import freechips.rocketchip.amba.axi4.{AXI4Bundle}
import testchipip.util.{ClockedIO}
import testchipip.soc._
import chisel3._
import chisel3.util._
import chisel3.reflect.DataMirror
import org.chipsalliance.cde.config.{Config, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._
import freechips.rocketchip.subsystem._
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
import freechips.rocketchip.subsystem._
import freechips.rocketchip.prci._
import freechips.rocketchip.devices.debug._
import freechips.rocketchip.devices.tilelink.BootROMLocated
import freechips.rocketchip.util._
import sifive.blocks.inclusivecache.{InclusiveCachePortParameters}

// Rocketchip's JTAGIO exposes the oe signal, which doesn't go off-chip
class JTAGChipIO(hasReset: Boolean) extends Bundle {
  val TCK = Input(Clock())
  val TMS = Input(Bool())
  val TDI = Input(Bool())
  val TDO = Output(Bool())
  val reset = Option.when(hasReset)(Input(Bool()))
}

class DigitalSystem(implicit p: Parameters)
    extends edu.berkeley.cs.chippy.ChippySystem
    with testchipip.soc.CanHaveSubsystemInjectors // Enables the subsystem injector API
    with testchipip.soc.CanHaveSwitchableOffchipBus // Enables optional off-chip-bus with interface-switch
    with testchipip.serdes.CanHavePeripheryTLSerial
    with sifive.blocks.devices.uart.HasPeripheryUART
    with edu.berkeley.cs.chippy.clocking.HasChippyPRCI
    with constellation.soc.CanHaveGlobalNoC

class DigitalChipTop(implicit p: Parameters)
    extends LazyModule
    with BindingScope {
  val system = LazyModule(new DigitalSystem)
  val clockGroupsSourceNode = ClockGroupSourceNode(
    Seq(ClockGroupSourceParameters())
  )
  system.chiptopClockGroupsNode := clockGroupsSourceNode
  val debugClockSinkNode = ClockSinkNode(Seq(ClockSinkParameters()))
  debugClockSinkNode := system
    .locateTLBusWrapper(p(ExportDebug).slaveWhere)
    .fixedClockNode
  def debugClockBundle = debugClockSinkNode.in.head._1

  override lazy val module = new DigitalChipTopImpl
  class DigitalChipTopImpl extends LazyRawModuleImp(this) with DontTouch {
    val io = IO(new Bundle {
      val clock = Input(Clock())
      val reset = Input(AsyncReset())
    })

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
    system.resetctrl.map { rcio =>
      rcio.hartIsInReset.map { _ := debugClockBundle.reset.asBool }
    }
    debug_io.extTrigger.foreach { t =>
      { t.in.req := false.B; t.out.ack := t.out.req; }
    } // Tie off extTrigger
    debug_io.disableDebug.foreach { d => d := false.B } // Tie off disableDebug
    // Drive JTAG on-chip IOs
    debug_io.systemjtag.map { j =>
      j.reset := ResetCatchAndSync(j.jtag.TCK, debugClockBundle.reset.asBool)
      j.mfr_id := p(JtagDTMKey).idcodeManufId.U(11.W)
      j.part_number := p(JtagDTMKey).idcodePartNum.U(16.W)
      j.version := p(JtagDTMKey).idcodeVersion.U(4.W)
    }

    val jtag = IO(new JTAGChipIO(false))
    debug_io.systemjtag.get.jtag.TCK := jtag.TCK
    debug_io.systemjtag.get.jtag.TMS := jtag.TMS
    debug_io.systemjtag.get.jtag.TDI := jtag.TDI
    jtag.TDO := debug_io.systemjtag.get.jtag.TDO.data

    // Tie off interupts and chip ID
    system.module.interrupts := DontCare

    val serial_tl = IO(
      new DecoupledExternalSyncPhitIO(p(SerialTLKey)(0).phyParams.phitWidth)
    )
    serial_tl <> system.serial_tls(0)
    val uart = IO(chiselTypeOf(system.uart(0)))
    uart <> system.uart(0)
  }
}

/** Digital chip configuration.
  *
  * Simulation flag expands tilelink bus to allow faster binary loading.
  */
class DigitalChipConfig(sim: Boolean = false)
    extends Config(
      // ==================================
      // Set up buses
      // ==================================
      new constellation.soc.WithSbusNoC(
        constellation.protocol.SplitACDxBETLNoCParams(
          constellation.protocol.DiplomaticNetworkNodeMapping(
            inNodeMapping = ListMap(
              "Core 0 ICache" -> 0, // Shuttle 0 (left)
              "Core 1 ICache" -> 1, // Shuttle 1 (right)
              "debug[0]" -> 2, // Front BUS
              "Core 2 DCache" -> 4 // RocketTile
            ),
            outNodeMapping = ListMap(
              "Core 0 TCM" -> 0, // Shuttle 0 TCM (left)
              "Core 1 TCM" -> 1, // Shuttle 1 TCM (right)
              "ctrls[0]" -> 2, // PBUS
              "ram[2],serdesser[2]|" -> 3, // L2   (top)
              "ram[3],serdesser[3]|" -> 3, // L2   (top)
              "ram[1],serdesser[1]|" -> 3, // L2   (bottom)
              "ram[0],serdesser[0]|" -> 3, // L2   (bottom)
              "ram[0]|" -> 6, // SBUS SPAD (?)
              "ram[1]|" -> 6 // MBUS SPAD (?)
            )
          ),
          acdNoCParams = NoCParams(
            topology = BidirectionalTorus1D(7),
            channelParamGen = (a, b) =>
              UserChannelParams(
                Seq.fill(6) { UserVirtualChannelParams(5) },
                unifiedBuffer = false
              ),
            routerParams =
              (i) => UserRouterParams(combineRCVA = true, combineSAST = true),
            routingRelation = BlockingVirtualSubnetworksRouting(
              BidirectionalTorus1DShortestRouting(),
              3,
              2
            )
          ),
          beNoCParams = NoCParams(
            topology = UnidirectionalTorus1D(7),
            channelParamGen = (a, b) =>
              UserChannelParams(
                Seq.fill(4) { UserVirtualChannelParams(5) },
                unifiedBuffer = false
              ),
            routerParams =
              (i) => UserRouterParams(combineRCVA = true, combineSAST = true),
            routingRelation = BlockingVirtualSubnetworksRouting(
              UnidirectionalTorus1DDatelineRouting(),
              2,
              2
            )
          ),
          beDivision = 8
        ),
        inlineNoC = true
      ) ++
        new Config((site, here, up) => { case SystemBusKey =>
          up(SystemBusKey, site).copy(beatBytes = 256 / 8)
        }) ++

        // ==================================
        // Set up peripherals
        // ==================================
        new Config((site, here, up) => {
          case SystemBusKey    => up(SystemBusKey).copy(errorDevice = None)
          case ControlBusKey   => up(ControlBusKey).copy(errorDevice = None)
          case PeripheryBusKey => up(PeripheryBusKey).copy(errorDevice = None)
          case MemoryBusKey    => up(MemoryBusKey).copy(errorDevice = None)
          case FrontBusKey     => up(FrontBusKey).copy(errorDevice = None)
        }) ++

        // ==================================
        // Rocket
        // ==================================
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

        // ==================================
        // Shuttle Tile + Saturn Cores
        // ==================================
        // new shuttle.common.WithAsynchronousShuttleTiles(3, 3, location=InCluster(0)) ++ // Add async crossings between RocketTile and uncore
        new saturn.shuttle.WithShuttleVectorUnit(
          512,
          256,
          VectorParams.opuParams
        ) ++
        new shuttle.common.WithShuttleTileBeatBytes(16) ++
        new shuttle.common.WithTCM(size = 128L << 10, banks = 2) ++
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
        new testchipip.serdes.WithSerialTL(
          Seq(
            testchipip.serdes.SerialTLParams(
              // port acts as a manager of offchip memory
              manager = Some(
                testchipip.serdes.SerialTLManagerParams(
                  memParams = Seq(
                    testchipip.serdes.ManagerRAMParams(
                      address = BigInt("80000000", 16),
                      size = BigInt("100000000", 16)
                    )
                  ),
                  isMemoryDevice = true,
                  slaveWhere = MBUS
                )
              ),
              // Allow an external manager to probe this chip
              client = Some(testchipip.serdes.SerialTLClientParams()),
              // 4-bit bidir interface, synced to an external clock
              phyParams = {
                val (phitWidth, flitWidth) = if (sim) {
                  (32, 32)
                } else {
                  (1, 16)
                }
                testchipip.serdes.DecoupledExternalSyncSerialPhyParams(
                  phitWidth = phitWidth,
                  flitWidth = flitWidth
                )
              }
            )
          )
        ) ++
        // Remove axi4 mem port
        new freechips.rocketchip.subsystem.WithNoMemPort ++

        // ==================================
        // Set up memory
        // ==================================
        // Adds buffers on the inclusive LLC, to improve PD
        new Config((site, here, up) => { case InclusiveCacheKey =>
          up(InclusiveCacheKey).copy(
            bufInnerInterior = InclusiveCachePortParameters.full,
            bufOuterInterior = InclusiveCachePortParameters.full,
            bufInnerExterior = InclusiveCachePortParameters.full,
            bufOuterExterior = InclusiveCachePortParameters.full
          )
        }) ++

        new freechips.rocketchip.subsystem.WithInclusiveCache(
          nWays = 4,
          capacityKB = 512,
          outerLatencyCycles = 4
        ) ++
        new freechips.rocketchip.subsystem.WithNBanks(4) ++
        new testchipip.soc.WithNoScratchpadMonitors ++
        new testchipip.soc.WithScratchpad(
          base = 0x580000000L,
          size = (1L << 17), // 128KB
          banks = 2,
          partitions = 1,
          buffer = BufferParams.default,
          outerBuffer = BufferParams.default
        ) ++

        // ==================================
        // Set up clock./reset
        // ==================================
        // Create the uncore clock group
        new edu.berkeley.cs.chippy.clocking.WithClockGroupsCombinedByName(
          (
            "uncore",
            Seq(
              "implicit",
              "sbus",
              "mbus",
              "cbus",
              "system_bus",
              "fbus",
              "pbus"
            ),
            Nil
          )
        ) ++
        new freechips.rocketchip.subsystem.WithNoMMIOPort ++
        new freechips.rocketchip.subsystem.WithNoSlavePort ++
        /** add a UART */
        new Config((site, here, up) => { case PeripheryUARTKey =>
          Seq(UARTParams(address = 0x10020000))
        }) ++

        /** enable the SBA (system-bus-access) feature of the debug module */
        new freechips.rocketchip.subsystem.WithDebugSBA ++
        new Config((site, here, up) => { case DebugModuleKey =>
          up(DebugModuleKey).map(_.copy(nAbstractDataWords = 8))
        }) ++

        /** increase debug module data word capacity */
        new freechips.rocketchip.subsystem.WithJtagDTM ++
        /** set the debug module to expose a JTAG port */

        new testchipip.boot.WithBootAddrReg ++
        /** add a boot-addr-reg for configurable boot address */
        // CLINT and PLIC related settings goes here
        new freechips.rocketchip.subsystem.WithNExtTopInterrupts(0) ++
        /** no external interrupts */

        new freechips.rocketchip.subsystem.WithDTS("ucb-bar,chipyard", Nil) ++
        /** custom device name for DTS (embedded in BootROM) */
        new Config((site, here, up) => { case BootROMLocated(x) =>
          up(BootROMLocated(x), site)
            .map(
              _.copy(
                address = 0x10000,
                size = 0x10000,
                hang = 0x10000,
                contentFileName = ResourceFileName(
                  s"/testchipip/bootrom/bootrom.rv${site(MaxXLen)}.img"
                )
              )
            )
        }) ++

        /** use default bootrom */
        new testchipip.soc.WithMbusScratchpad(
          base = 0x08000000,
          /** add 64 KiB on-chip scratchpad */
          size = 64 * 1024
        ) ++
        // Bus/interconnect settings
        /** hierarchical buses including sbus/mbus/pbus/fbus/cbus/l2 */
        new freechips.rocketchip.subsystem.WithCoherentBusTopology ++
        /** leave the bus clocks undriven by sbus */
        new freechips.rocketchip.subsystem.WithDontDriveBusClocksFromSBus ++
        /** add default EICG_wrapper clock gate model */
        new freechips.rocketchip.subsystem.WithClockGateModel ++
        new edu.berkeley.cs.chippy.clocking.WithPeripheryBusFrequency(500.0) ++
        new edu.berkeley.cs.chippy.clocking.WithMemoryBusFrequency(500.0) ++
        new edu.berkeley.cs.chippy.clocking.WithControlBusFrequency(500.0) ++
        new edu.berkeley.cs.chippy.clocking.WithSystemBusFrequency(500.0) ++
        new edu.berkeley.cs.chippy.clocking.WithFrontBusFrequency(500.0) ++
        new Config((site, here, up) => { case OffchipBusKey =>
          up(OffchipBusKey, site)
            .copy(dtsFrequency = Some(BigInt((500.0 * 1e6).toLong)))
        }) ++

        /** Unspecified clocks within a bus will receive the bus frequency if
          * set
          */
        new edu.berkeley.cs.chippy.clocking.WithInheritBusFrequencyAssignments ++
        /** drive the subsystem diplomatic clocks from ChipTop instead of using
          * implicit clocks
          */
        new edu.berkeley.cs.chippy.clocking.WithNoSubsystemClockIO ++

        new freechips.rocketchip.system.BaseConfig
    )
