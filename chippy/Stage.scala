package edu.berkeley.cs.chippy

import chisel3._
import firrtl.AnnotationSeq
import circt.stage.ChiselStage
import circt.stage.FirtoolOption
import chisel3.stage.ChiselGeneratorAnnotation

class ChippyStage extends ChiselStage {
  def chippyFirtoolOpts = Array(
    "--export-module-hierarchy",
    "--verify-each=true",
    "--warn-on-unprocessed-annotations",
    "--disable-annotation-classless",
    "--disable-annotation-unknown",
    "--mlir-timing"
  )

  override def run(annotations: AnnotationSeq): AnnotationSeq = {
    super.run(annotations ++ chippyFirtoolOpts.map(FirtoolOption(_)))
  }
}

object ChippyStage {

  /** Compile a Chisel circuit to multiple SystemVerilog files.
    *
    * @param gen
    *   a call-by-name Chisel module
    * @param args
    *   additional command line arguments to pass to Chisel
    * @param firtoolOpts
    *   additional command line options to pass to firtool
    * @return
    *   the annotations that exist after compilation
    */
  def emitSystemVerilogFile(
      gen: => RawModule,
      args: Array[String] = Array.empty,
      firtoolOpts: Array[String] = Array.empty
  ): AnnotationSeq =
    (new ChippyStage).execute(
      Array("--target", "systemverilog", "--split-verilog") ++ args,
      Seq(ChiselGeneratorAnnotation(() => gen)) ++ firtoolOpts.map(
        FirtoolOption(_)
      )
    )

}
