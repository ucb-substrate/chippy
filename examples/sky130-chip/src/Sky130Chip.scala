package examples.sky130chip

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.{Config, Parameters}
import testchipip.serdes._
import examples.sky130chip.digitalchip.{JTAGChipIO, DigitalChipTop}
import sifive.blocks.devices.uart._
import freechips.rocketchip.diplomacy._

/** Dummy SKY130 GPIO cell.
  *
  * TODO: Use the Chippy SKY130 PDK with actual IO cells in the future.
  */
class Sky130GPIOCellIO(input: Boolean) extends Bundle {
  val signal = if (input) { Input(Bool()) }
  else { Output(Bool()) }
  val pad = if (input) { Output(Bool()) }
  else { Input(Bool()) }
  val control = Input(Bool())
}
class Sky130GPIOCell(input: Boolean) extends ExtModule {
  val io = IO(new Sky130GPIOCellIO(input))

  /** TODO: Set name to actual name of IO cell. */
  override val desiredName = "sky130_gpio_cell"
}
object Sky130GPIOCell {
  def apply(input: Boolean, signal: Bool): Bool = {
    val io_cell = Module(new Sky130GPIOCell(input))
    io_cell.io.control := input.B
    if (input) {
      signal := io_cell.io.signal
    } else {
      io_cell.io.signal := signal
    }
    io_cell.io.pad
  }
}

class Sky130ChipIO(implicit p: Parameters) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(AsyncReset())
  val jtag = new JTAGChipIO(false)
  val serial_tl = new DecoupledExternalSyncPhitIO(
    p(SerialTLKey)(0).phyParams.phitWidth
  )
  val uart = new UARTPortIO(p(PeripheryUARTKey)(0))
}

class Sky130ChipTop(implicit p: Parameters) extends RawModule {
  val io = IO(new Sky130ChipIO)

  val chiptop0_lazy = LazyModule(new DigitalChipTop)
  val chiptop0 = Module(chiptop0_lazy.module)

  chiptop0.io.clock := Sky130GPIOCell(true, io.clock.asBool).asClock
  chiptop0.io.reset := Sky130GPIOCell(true, io.reset.asBool)

  chiptop0.jtag.TCK := Sky130GPIOCell(true, io.jtag.TCK.asBool).asClock
  chiptop0.jtag.TMS := Sky130GPIOCell(true, io.jtag.TMS)
  chiptop0.jtag.TDI := Sky130GPIOCell(true, io.jtag.TDI)
  Sky130GPIOCell(false, io.jtag.TDO) := chiptop0.jtag.TDO

  chiptop0.serial_tl.clock_in := Sky130GPIOCell(
    true,
    io.serial_tl.clock_in.asBool
  ).asClock
  chiptop0.serial_tl.in.valid := Sky130GPIOCell(true, io.serial_tl.in.valid)
  for (i <- 0 until io.serial_tl.in.bits.getWidth) {
    chiptop0.serial_tl.in.bits.phit(i) := Sky130GPIOCell(
      true,
      io.serial_tl.in.bits.phit(i)
    )
  }
  Sky130GPIOCell(false, io.serial_tl.in.ready) := chiptop0.serial_tl.in.ready
  Sky130GPIOCell(false, io.serial_tl.out.valid) := chiptop0.serial_tl.out.valid
  for (i <- 0 until io.serial_tl.out.bits.getWidth) {
    Sky130GPIOCell(
      false,
      io.serial_tl.out.bits.phit(i)
    ) := chiptop0.serial_tl.out.bits.phit(i)
  }
  chiptop0.serial_tl.out.ready := Sky130GPIOCell(true, io.serial_tl.out.ready)

  chiptop0.uart.rxd := Sky130GPIOCell(true, io.uart.rxd)
  Sky130GPIOCell(false, io.uart.txd) := chiptop0.uart.txd
}
