package edu.berkeley.cs.chippy

import chisel3._
import chisel3.util._
import chisel3.experimental.{IntParam}

import org.chipsalliance.cde.config.{Parameters, Field}
import os.Path

class SerialIO(val w: Int) extends Bundle {
  val in = Flipped(Decoupled(UInt(w.W)))
  val out = Decoupled(UInt(w.W))

  def flipConnect(other: SerialIO) {
    in <> other.out
    other.in <> out
  }
}

object TSI {
  val WIDTH = 32 // hardcoded in FESVR
}

class TSIIO extends SerialIO(TSI.WIDTH)

object TSIIO {
  def apply(ser: SerialIO): TSIIO = {
    require(ser.w == TSI.WIDTH)
    val wire = Wire(new TSIIO)
    wire <> ser
    wire
  }
}

object SimTSI {
  def connect(
      tsi: Option[TSIIO],
      clock: Clock,
      reset: Reset,
      binaryPath: Path,
      plusArgs: Seq[String] = Seq.empty
  ): Bool = {
    val exit = tsi
      .map { s =>
        val sim = Module(new SimTSI(binaryPath, plusArgs = plusArgs))
        sim.io.clock := clock
        sim.io.reset := reset
        sim.io.tsi <> s
        sim.io.exit
      }
      .getOrElse(0.U)

    val success = exit === 1.U
    val error = exit >= 2.U
    assert(!error, "*** FAILED *** (exit code = %d)\n", exit >> 1.U)
    success
  }
}

// TODO: Handle escaping
class SimTSI(binaryPath: Path, plusArgs: Seq[String] = Seq.empty)
    extends BlackBox(
      Map(
        "argc" -> IntParam(2 + plusArgs.length),
        "argv" -> RawParam(
          s"'{${plusArgs.reverse.map(arg => s"\"${arg}\", ").mkString("")}\"${binaryPath.toString}\", \"placeholder\"}"
        )
      )
    )
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val tsi = Flipped(new TSIIO)
    val exit = Output(UInt(32.W))
  })

  addResource("/vsrc/SimTSI.sv")
  addResource("/csrc/SimTSI.cc")
  addResource("/csrc/testchip_htif.cc")
  addResource("/csrc/testchip_htif.h")
  addResource("/csrc/testchip_tsi.cc")
  addResource("/csrc/testchip_tsi.h")
}
