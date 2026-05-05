package examples.rocketconfig

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.{Config, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.prci._
import freechips.rocketchip.devices.debug._
import freechips.rocketchip.devices.tilelink.BootROMLocated
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import sifive.blocks.devices.uart._
import testchipip._
import testchipip.serdes._
import testchipip.boot._
import testchipip.soc._

// Rocketchip's JTAGIO exposes the oe signal, which doesn't go off-chip
class JTAGChipIO(hasReset: Boolean) extends Bundle {
  val TCK = Input(Clock())
  val TMS = Input(Bool())
  val TDI = Input(Bool())
  val TDO = Output(Bool())
  val reset = Option.when(hasReset)(Input(Bool()))
}

class RocketSystem(implicit p: Parameters)
    extends edu.berkeley.cs.chippy.ChippySystem
    with testchipip.soc.CanHavePeripheryChipIdPin
    with testchipip.serdes.CanHavePeripheryTLSerial
    with sifive.blocks.devices.uart.HasPeripheryUART
    with edu.berkeley.cs.chippy.clocking.HasChippyPRCI

class RocketChipTop(implicit p: Parameters)
    extends LazyModule
    with BindingScope {
  val system = LazyModule(new RocketSystem)
  val clockGroupsSourceNode = ClockGroupSourceNode(
    Seq(ClockGroupSourceParameters())
  )
  system.chiptopClockGroupsNode := clockGroupsSourceNode
  val debugClockSinkNode = ClockSinkNode(Seq(ClockSinkParameters()))
  debugClockSinkNode := system
    .locateTLBusWrapper(p(ExportDebug).slaveWhere)
    .fixedClockNode
  def debugClockBundle = debugClockSinkNode.in.head._1

  override lazy val module = new RocketChipTopImpl
  class RocketChipTopImpl extends LazyRawModuleImp(this) with DontTouch {
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

    // Tie off interrupts and chip ID
    system.module.interrupts := DontCare

    val serial_tl = IO(
      new DecoupledExternalSyncPhitIO(p(SerialTLKey)(0).phyParams.phitWidth)
    )
    serial_tl <> system.serial_tls(0)
    val uart = IO(chiselTypeOf(system.uart(0)))
    uart <> system.uart(0)

    val chip_id_pin = system.chip_id_pin.get
    val chip_id = IO(Input(UInt(p(ChipIdPinKey).get.width.W)))
    chip_id_pin := chip_id
  }
}

/** Rocket chip configuration.
  *
  * Single huge Rocket core, mirroring chipyard's RocketConfig
  * (WithNHugeCores(1) ++ AbstractConfig), with AbstractConfig's contents
  * expanded inline in chippy's idiom.
  *
  * Simulation flag expands tilelink bus to allow faster binary loading.
  */
class RocketChipConfig(sim: Boolean = false)
    extends Config(
      // ==================================
      // Set up buses
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
        new freechips.rocketchip.rocket.WithNHugeCores(1) ++

        // Chip ID Pin
        new testchipip.soc.WithChipIdPin ++

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
              client = Some(
                testchipip.serdes.SerialTLClientParams(totalIdBits = 4)
              ),
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
        // Set up clock/reset
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
        /** increase debug module data word capacity */
        new Config((site, here, up) => { case DebugModuleKey =>
          up(DebugModuleKey).map(_.copy(nAbstractDataWords = 8))
        }) ++

        /** set the debug module to expose a JTAG port */
        new freechips.rocketchip.subsystem.WithJtagDTM ++

        /** add a boot-addr-reg for configurable boot address */
        new testchipip.boot.WithBootAddrReg ++
        // CLINT and PLIC related settings goes here
        /** no external interrupts */
        new freechips.rocketchip.subsystem.WithNExtTopInterrupts(0) ++

        /** custom device name for DTS (embedded in BootROM) */
        new freechips.rocketchip.subsystem.WithDTS("ucb-bar,chipyard", Nil) ++
        /** use default bootrom */
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
        /** add 64 KiB on-chip scratchpad */
        new testchipip.soc.WithMbusScratchpad(
          base = 0x08000000,
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
