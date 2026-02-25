//******************************************************************************
// Copyright (c) 2019 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

package edu.berkeley.cs.chippy

import chisel3._

import freechips.rocketchip.prci._
import org.chipsalliance.cde.config.{Field, Parameters}
import freechips.rocketchip.devices.debug.{
  HasPeripheryDebug,
  ExportDebug,
  DebugModuleKey
}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.util._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.devices.tilelink._

class ChippySubsystem(implicit p: Parameters)
    extends BaseSubsystem
    with InstantiatesHierarchicalElements
    with HasTileNotificationSinks
    with HasTileInputConstants
    with CanHavePeripheryCLINT
    with CanHavePeripheryPLIC
    with HasPeripheryDebug
    with HasHierarchicalElementsRootContext
    with HasHierarchicalElements {
  def coreMonitorBundles = totalTiles.values.map { case r: RocketTile =>
    r.module.core.rocketImpl.coreMonitorBundle
  }.toList

  // No-tile configs have to be handled specially.
  if (totalTiles.size == 0) {
    // no PLIC, so sink interrupts to nowhere
    require(!p(PLICKey).isDefined)
    val intNexus = IntNexusNode(sourceFn = x => x.head, sinkFn = x => x.head)
    val intSink = IntSinkNode(IntSinkPortSimple())
    intSink := intNexus :=* ibus.toPLIC

    // avoids a bug when there are no interrupt sources
    ibus { ibus.fromAsync := NullIntSource() }

    // Need to have at least 1 driver to the tile notification sinks
    tileHaltXbarNode := IntSourceNode(IntSourcePortSimple())
    tileWFIXbarNode := IntSourceNode(IntSourcePortSimple())
    tileCeaseXbarNode := IntSourceNode(IntSourcePortSimple())
  }

  // Relying on [[TLBusWrapperConnection]].driveClockFromMaster for
  // bus-couplings that are not asynchronous strips the bus name from the sink
  // ClockGroup. This makes it impossible to determine which clocks are driven
  // by which bus based on the member names, which is problematic when there is
  // a rational crossing between two buses. Instead, provide all bus clocks
  // directly from the allClockGroupsNode in the subsystem to ensure bus
  // names are always preserved in the top-level clock names.
  //
  // For example, using a RationalCrossing between the Sbus and Cbus, and
  // driveClockFromMaster = Some(true) results in all cbus-attached device and
  // bus clocks to be given names of the form "subsystem_sbus_[0-9]*".
  // Conversly, if an async crossing is used, they instead receive names of the
  // form "subsystem_cbus_[0-9]*". The assignment below provides the latter names in all cases.
  Seq(PBUS, FBUS, MBUS, CBUS).foreach { loc =>
    tlBusWrapperLocationMap.lift(loc).foreach {
      _.clockGroupNode := allClockGroupsNode
    }
  }
  override lazy val module = new ChippySubsystemModuleImp(this)
}

class ChippySubsystemModuleImp[+L <: ChippySubsystem](_outer: L)
    extends BaseSubsystemModuleImp(_outer)
    with HasHierarchicalElementsRootContextModuleImp {
  override lazy val outer = _outer
}
