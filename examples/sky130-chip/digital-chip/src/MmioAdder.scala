package examples.mmioadder

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.{Parameters, Field, Config}
import edu.berkeley.cs.chippy._
import freechips.rocketchip.regmapper.{RegField, RegWriteFn, RegFieldDesc}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.prci._
import freechips.rocketchip.diplomacy.{SimpleDevice, AddressSet}
import org.chipsalliance.diplomacy._
import org.chipsalliance.diplomacy.lazymodule._

import sys.process._

import chisel3.experimental.{IntParam, BaseModule}
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.subsystem.{BaseSubsystem, PBUS}
import freechips.rocketchip.regmapper.{HasRegMap, RegField}
import freechips.rocketchip.util._

// TODO: Integrate with full system.

case class MmioAdderParams(
  address: BigInt = 0x4000,
  externallyClocked: Boolean = false
) {
}

case object AdderKey extends Field[Option[MmioAdderParams]](None)

class MmioAdder(params: MmioAdderParams, beatBytes: Int)(implicit
    p: Parameters
) extends ClockSinkDomain(ClockSinkParameters())(p) {
  def toRegFieldRw[T <: Data](r: T, name: String): RegField = {
    RegField(
      r.getWidth,
      r.asUInt,
      RegWriteFn((valid, data) => {
        when(valid) {
          r := data.asTypeOf(r)
        }
        true.B
      }),
      Some(RegFieldDesc(name, ""))
    )
  }
  def toRegFieldR[T <: Data](r: T, name: String): RegField = {
    RegField.r(r.getWidth, r.asUInt, RegFieldDesc(name, ""))
  }
  val device = new SimpleDevice("mmio_addr", Seq("examples,mmio_addr"))
  val node = TLRegisterNode(
    Seq(AddressSet(params.address, 0xfff)),
    device,
    "reg/control",
    beatBytes = beatBytes
  )

  override lazy val module = new MmioAdderImpl
  class MmioAdderImpl extends Impl {
    val io = IO(new Bundle {})

    withClockAndReset(clock, reset) {
      val a = RegInit(0.U(63.W))
      val b = RegInit(0.U(63.W))
      val o = a +& b

      node.regmap(
        0x0 -> Seq(RegField.w(a.getWidth, a, RegFieldDesc("a", ""))),
        0x8 -> Seq(RegField.w(b.getWidth, b, RegFieldDesc("b", ""))),
        0x10 -> Seq(RegField.r(o.getWidth, o, RegFieldDesc("o", "")))
      )
    }
  }
}

/** CanHavePeripheryAddder and WithAdder adapted from Chipyard documentation's MMIO Peripherals example
  * https://chipyard.readthedocs.io/en/latest/Customization/MMIO-Peripherals.html#mmio-accelerators
  */

trait CanHavePeripheryAdder { this: BaseSubsystem =>
  private val portName = "adder"

  private val pbus = locateTLBusWrapper(PBUS)

  val (adder_clock) = p(AdderKey) match {
    case Some(params) => {

      val adder_clock = Option.when(params.externallyClocked) {
        InModuleBody { IO(Input(Clock())).suggestName("adder_clock_in") }
      }
      val adderClockNode = if (params.externallyClocked) {
        val adderSourceClockNode = ClockSourceNode(Seq(ClockSourceParameters()))
        InModuleBody {
          adderSourceClockNode.out(0)._1.clock := adder_clock.get
          adderSourceClockNode.out(0)._1.reset := ResetCatchAndSync(adder_clock.get, pbus.module.reset.asBool)
        }
        adderSourceClockNode
      } else {
        pbus.fixedClockNode
      }
      val adderCrossing = if (params.externallyClocked) {
        AsynchronousCrossing()
      } else {
        SynchronousCrossing()
      }

     val adder = LazyModule(new MmioAdder(params, pbus.beatBytes)(p))
        adder.clockNode := adderClockNode
        pbus.coupleTo(portName) {
          TLInwardClockCrossingHelper("adder_crossing", adder, adder.node)(adderCrossing) :=
          TLFragmenter(pbus.beatBytes, pbus.blockBytes) := _
        }
      (adder_clock)
    }
    case None => (None)
  }
}

class WithAdder(address: BigInt = 0x4000, externallyClocked: Boolean = false) extends Config((site, here, up) => {
  case AdderKey => {
    Some(MmioAdderParams(address = address, externallyClocked = externallyClocked))
  }
})