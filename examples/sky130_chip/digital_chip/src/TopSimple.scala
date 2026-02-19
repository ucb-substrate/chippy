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
      phyParams = testchipip.serdes.DecoupledExternalSyncSerialPhyParams(phitWidth=1, flitWidth=16)
    ),
  )) ++
  // Remove axi4 mem port
  new freechips.rocketchip.subsystem.WithNoMemPort ++


  new chipyard.clocking.WithPassthroughClockGenerator ++
  new chipyard.config.AbstractConfig
)

