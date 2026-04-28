package edu.berkeley.cs.chippy

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals
import chisel3.experimental.VecLiterals._
import chisel3.simulator.{AnySimulatedModule, PeekPokeAPI}
import chisel3.experimental.{SourceInfo, SourceLine}
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.cde.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.IdRange
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import firrtl.options.StageUtils.dramaticMessage
import svsim._

import scala.util.control.NoStackTrace
import scala.language.implicitConversions

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
  import chisel3.simulator.PeekPokeAPI._
  val req = Flipped(new DecoupledIO(new TLTesterReq(params)))
  val resp = new DecoupledIO(new TLTesterResp(params))

  final def write(
      clock: Clock,
      addr: UInt,
      data: UInt,
      timeout: Int = 1000
  )(implicit sourceInfo: SourceInfo): Unit = {
    op(clock, addr, data, true.B, timeout = timeout)
    clock.step()
  }

  final def read(
      clock: Clock,
      addr: UInt,
      timeout: Int = 1000
  )(implicit sourceInfo: SourceInfo): UInt = {
    op(clock, addr, 0.U, false.B, timeout = timeout)
    val out = resp.bits.data.peek()
    clock.step()
    out
  }

  final def expect(
      clock: Clock,
      addr: UInt,
      expected: UInt,
      timeout: Int = 1000,
      message: String = ""
  )(implicit sourceInfo: SourceInfo): Unit = {
    op(clock, addr, 0.U, false.B, timeout = timeout)
    resp.bits.data.expect(expected, message)
    clock.step()
  }

  final def op(
      clock: Clock,
      addr: UInt,
      data: UInt,
      is_write: Bool,
      timeout: Int = 1000
  )(implicit sourceInfo: SourceInfo): Unit = {
    resp.ready.poke(true.B)
    clock.stepUntil(req.ready, 1, timeout)
    req.ready.expect(true.B, "timeout waiting for request to be ready")
    req.valid.poke(true.B)
    req.bits.addr.poke(addr)
    req.bits.data.poke(data)
    req.bits.is_write.poke(is_write)
    clock.stepUntil(resp.valid, 1, timeout)
    req.ready.expect(true.B, "timeout waiting for response to be valid")
  }
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

case class TLRequestDescriptor(
  address: BigInt,
  isWrite: Boolean,
  data: BigInt = 0,
  size: Int = 3 // log2(8) = 3 => 8 bytes by default
)

class TLDriver(reqs: Seq[TLRequestDescriptor])(implicit p: Parameters) extends LazyModule {
  val node = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLClientParameters(
    name = "offchip_router_test_driver", sourceId = IdRange(0, 1))))))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val start = Input(Bool())
      val finished = Output(Bool())
    })

    val (tl, edge) = node.out(0)
    val nReqs = reqs.size

    val reqIdx = RegInit(0.U(log2Ceil(nReqs + 1).W))

    val addresses = VecInit(reqs.map(r => r.address.U(edge.bundle.addressBits.W)))
    val writes    = VecInit(reqs.map(r => r.isWrite.B))
    val datas     = VecInit(reqs.map(r => r.data.U(edge.bundle.dataBits.W)))
    val sizes     = VecInit(reqs.map(r => r.size.U(edge.bundle.sizeBits.W)))

    val (s_idle :: s_req :: s_resp :: s_done :: Nil) = Enum(4)
    val state = RegInit(s_idle)

    when (state === s_idle && io.start) { state := s_req }

    // A channel: send requests
    val currAddr  = addresses(reqIdx)
    val currWrite = writes(reqIdx)
    val currData  = datas(reqIdx)
    val currSize  = sizes(reqIdx)

    val putBits = edge.Put(0.U, currAddr, currSize, currData)._2
    val getBits = edge.Get(0.U, currAddr, currSize)._2

    tl.a.valid := state === s_req
    tl.a.bits  := Mux(currWrite, putBits, getBits)

    // D channel: accept responses. Held high in s_req as well so a manager
    // that produces a combinational AccessAck (e.g. TLRegisterNode) can drain
    // its response on the same cycle as a.fire — otherwise it gates a.ready
    // on d.ready and we deadlock.
    tl.d.ready := state === s_req || state === s_resp

    when (tl.a.fire) { state := s_resp }
    when (tl.d.fire) {
      // For reads, the descriptor's `data` field is the expected response.
      when (!currWrite) {
        assert(tl.d.bits.data === currData,
          "TLDriver read mismatch: idx=%d addr=0x%x expected=0x%x got=0x%x",
          reqIdx, currAddr, currData, tl.d.bits.data)
      }
      reqIdx := reqIdx + 1.U
      when (reqIdx === (nReqs - 1).U) {
        state := s_done
      }.otherwise {
        state := s_req
      }
    }

    tl.b.ready := false.B
    tl.c.valid := false.B
    tl.c.bits  := DontCare
    tl.e.valid := false.B
    tl.e.bits  := DontCare

    io.finished := state === s_done
  }
}
