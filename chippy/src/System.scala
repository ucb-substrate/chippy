//******************************************************************************
// Copyright (c) 2019 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

package edu.berkeley.cs.chippy

import chisel3._

import org.chipsalliance.cde.config.{Parameters, Field}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.devices.tilelink._
// import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util.{DontTouch}

// ---------------------------------------------------------------------
// Base system that uses the debug test module (dtm) to bringup the core
// ---------------------------------------------------------------------

/** Base top with periphery devices and ports, and a BOOM + Rocket subsystem
  */
class ChippySystem(implicit p: Parameters)
    extends ChippySubsystem
    with HasAsyncExtInterrupts {

  val bootROM = p(BootROMLocated(location)).map {
    BootROM.attach(_, this, CBUS)
  }
  val maskROMs = p(MaskROMLocated(location)).map {
    MaskROM.attach(_, this, CBUS)
  }

  override lazy val module = new ChippySystemModule(this)
}

/** Base top module implementation with periphery devices and ports, and a BOOM
  * + Rocket subsystem
  */
class ChippySystemModule(_outer: ChippySystem)
    extends ChippySubsystemModuleImp(_outer)
    with HasRTCModuleImp
    with HasExtInterruptsModuleImp
    with DontTouch
