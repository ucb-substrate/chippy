package edu.berkeley.cs.chippy

import chisel3._
import chisel3.util._
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.cde.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.IdRange
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._

case class TLTesterParams(
    maxInflight: Int = 1,
    addrWidth: Int = 64,
    dataWidth: Int = 64
) {
  val idBits = log2Ceil(maxInflight)
}

class TLTesterReq(params: TLTesterParams) extends Bundle {
  val addr = UInt(params.addrWidth.W)
  val data = UInt(params.dataWidth.W)
  val id = UInt(params.idBits.W)
  val is_write = Bool()
}

class TLTesterResp(params: TLTesterParams) extends Bundle {
  val data = UInt(params.dataWidth.W)
  val id = UInt(params.idBits.W)
}

class TLTesterIO(params: TLTesterParams) extends Bundle {
  val req = Flipped(new DecoupledIO(new TLTesterReq(params)))
  val resp = new DecoupledIO(new TLTesterResp(params))
}

class TLTester(params: TLTesterParams, beatBytes: Int)(implicit p: Parameters)
    extends LazyModule {
  val lgBeatBytes = log2Ceil(beatBytes)

  val node = TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        clients = Seq(
          TLMasterParameters.v1(
            name = "tester-node",
            sourceId = IdRange(0, params.maxInflight)
          )
        )
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new TLTesterIO(params))

    val (out, edge) = node.out(0)

    io.req.ready := out.a.ready
    out.d.ready := io.resp.ready

    out.a.valid := io.req.valid
    // First arg is the source id
    out.a.bits := Mux(
      io.req.bits.is_write,
      edge
        .Put(io.req.bits.id, io.req.bits.addr, lgBeatBytes.U, io.req.bits.data)
        ._2,
      edge.Get(io.req.bits.id, io.req.bits.addr, lgBeatBytes.U)._2
    )

    io.resp.valid := out.d.valid
    io.resp.bits.data := out.d.bits.data
    io.resp.bits.id := out.d.bits.source
  }
}
