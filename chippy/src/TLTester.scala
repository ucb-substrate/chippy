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

class SerialTLTesterIO(params: TLTesterParams) extends Bundle {
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
    clock.step(1)
    // do not allow any requests to go out before we get a response for this one 
    req.valid.poke(false.B) 
    clock.stepUntil(resp.valid, 1, timeout)
    resp.valid.expect(true.B, "timeout waiting for response")
  }
}

case class TLRequestDescriptor(
  address: BigInt,
  isWrite: Boolean,
  data: BigInt = 0,
  size: Int = 3 // log2(8) = 3 => 8 bytes by default
)

class TLDriver(
    reqs: Seq[TLRequestDescriptor],
    maxInflight: Int = 1
)(implicit p: Parameters) extends LazyModule {
  val node = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLClientParameters(
    name = "offchip_router_test_driver", sourceId = IdRange(0, maxInflight))))))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val start = Input(Bool())
      val finished = Output(Bool())
    })

    val (tl, edge) = node.out(0)
    val nReqs = reqs.size

    val addresses = VecInit(reqs.map(r => r.address.U(edge.bundle.addressBits.W)))
    val writes    = VecInit(reqs.map(r => r.isWrite.B))
    val datas     = VecInit(reqs.map(r => r.data.U(edge.bundle.dataBits.W)))
    val sizes     = VecInit(reqs.map(r => r.size.U(edge.bundle.sizeBits.W)))

    val started        = RegInit(false.B)
    val issueIdx       = RegInit(0.U(log2Ceil(nReqs + 1).W))
    val completedCount = RegInit(0.U(log2Ceil(nReqs + 1).W))

    when (io.start) { started := true.B }

    // Free pool: bit s = 1 means source ID s is currently free.
    val freeMask = RegInit(((BigInt(1) << maxInflight) - 1).U(maxInflight.W))
    // Scoreboard: source ID -> request index currently owning that source.
    val scoreboard = Reg(Vec(maxInflight, UInt(log2Up(nReqs).W)))

    val freeBools  = freeMask.asBools
    val hasFreeSrc = freeBools.reduce(_ || _)
    val pickedSrc  = PriorityEncoder(freeBools)

    val canIssue = started && (issueIdx < nReqs.U) && hasFreeSrc

    val currAddr  = addresses(issueIdx)
    val currWrite = writes(issueIdx)
    val currData  = datas(issueIdx)
    val currSize  = sizes(issueIdx)

    val putBits = edge.Put(pickedSrc, currAddr, currSize, currData)._2
    val getBits = edge.Get(pickedSrc, currAddr, currSize)._2

    tl.a.valid := canIssue
    tl.a.bits  := Mux(currWrite, putBits, getBits)
    tl.d.ready := true.B

    // Update freeMask: clear the picked bit on a.fire, set the response bit on
    // d.fire. Same-cycle a.fire+d.fire on the same source (combinational ack)
    // results in the bit being set (free), which is correct: the source is
    // immediately released back to the pool.
    val allocBit = Mux(tl.a.fire, UIntToOH(pickedSrc, maxInflight), 0.U(maxInflight.W))
    val freeBit  = Mux(tl.d.fire, UIntToOH(tl.d.bits.source, maxInflight), 0.U(maxInflight.W))
    freeMask := (freeMask & ~allocBit) | freeBit

    when (tl.a.fire) {
      scoreboard(pickedSrc) := issueIdx
      issueIdx := issueIdx + 1.U
    }

    when (tl.d.fire) {
      // For combinational ack on the source we're issuing this same cycle,
      // the registered scoreboard entry hasn't been written yet — use the
      // current issueIdx wire instead.
      val sameCycleAck = tl.a.fire && (pickedSrc === tl.d.bits.source)
      val respIdx   = Mux(sameCycleAck, issueIdx, scoreboard(tl.d.bits.source))
      val respWrite = writes(respIdx)
      val respData  = datas(respIdx)
      val respAddr  = addresses(respIdx)

      when (!respWrite) {
        assert(tl.d.bits.data === respData,
          "TLDriver read mismatch: idx=%d src=%d addr=0x%x expected=0x%x got=0x%x",
          respIdx, tl.d.bits.source, respAddr, respData, tl.d.bits.data)
      }

      completedCount := completedCount + 1.U
    }

    tl.b.ready := false.B
    tl.c.valid := false.B
    tl.c.bits  := DontCare
    tl.e.valid := false.B
    tl.e.bits  := DontCare

    io.finished := started && (completedCount === nReqs.U)
  }
}

class TLBackpressureTestWidget(
    cycles: Int,
    managerStall: Boolean = true,
    clientStall: Boolean = true
)(implicit p: Parameters) extends LazyModule {
  require(cycles >= 0, s"cycles must be non-negative, got $cycles")

  val node = new TLAdapterNode(
    clientFn = { case c => c },
    managerFn = { case m => m }
  ) {
    override def circuitIdentity = cycles == 0 || (!managerStall && !clientStall)
  }

  override lazy val desiredName = s"TLBackpressureTestWidget$cycles"

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    (node.in zip node.out).foreach { case ((in, _), (out, _)) =>
      out <> in

      if (cycles > 0 && (managerStall || clientStall)) {
        val counter = RegInit(cycles.U(log2Up(cycles + 1).W))
        val stalling = counter =/= 0.U
        when (stalling) { counter := counter - 1.U }

        // Manager-driven readies live on client->manager channels (A, C, E).
        // Lower the matching valid in lockstep with ready so a beat can't fire
        // on the manager side while the upstream side is held back.
        if (managerStall) {
          when (stalling) {
            in.a.ready := false.B
            out.a.valid := false.B
            in.c.ready := false.B
            out.c.valid := false.B
            in.e.ready := false.B
            out.e.valid := false.B
          }
        }

        // Client-driven readies live on manager->client channels (B, D).
        if (clientStall) {
          when (stalling) {
            out.b.ready := false.B
            in.b.valid := false.B
            out.d.ready := false.B
            in.d.valid := false.B
          }
        }
      }
    }
  }
}

object TLBackpressureTestWidget {
  def apply(
      cycles: Int,
      managerStall: Boolean = true,
      clientStall: Boolean = true
  )(implicit p: Parameters): TLAdapterNode = {
    LazyModule(new TLBackpressureTestWidget(cycles, managerStall, clientStall)).node
  }
}
