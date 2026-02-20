package examples.sky130_chip

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.{Config, Parameters}
import examples.sky130_chip.digital_chip.{DigitalChipTop, DigitalChipTopIO}
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

class Sky130ChipTop(implicit p: Parameters) extends RawModule {
  val io = IO(new DigitalChipTopIO)

  val chiptop0_lazy = LazyModule(new DigitalChipTop)
  val chiptop0 = Module(chiptop0_lazy.module)
}
