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

// TODO: Integrate with full system.

case class MmioAdderParams(
    address: BigInt = 0x4000
)

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
    Seq(AddressSet(params.address, 256 - 1)),
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
