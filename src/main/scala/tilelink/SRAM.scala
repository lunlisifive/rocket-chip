// See LICENSE.SiFive for license details.

package freechips.rocketchip.tilelink

import Chisel._
import chisel3.experimental.chiselName
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.diplomaticobjectmodel.logicaltree.{BusMemoryLogicalTreeNode, LogicalModuleTree, LogicalTreeNode}
import freechips.rocketchip.diplomaticobjectmodel.model.{OMECC, TL_UL}
import freechips.rocketchip.util._
import freechips.rocketchip.util.property._

class TLRAMErrors(val params: ECCParams, val addrBits: Int) extends Bundle with CanHaveErrors {
  val correctable   = (params.code.canCorrect && params.notifyErrors).option(Valid(UInt(addrBits.W)))
  val uncorrectable = (params.code.canDetect  && params.notifyErrors).option(Valid(UInt(addrBits.W)))
}

class TLRAM(
    address: AddressSet,
    parentLogicalTreeNode: Option[LogicalTreeNode] = None,
    cacheable: Boolean = true,
    executable: Boolean = true,
    atomics: Boolean = false,
    beatBytes: Int = 4,
    ecc: ECCParams = ECCParams(),
    val devName: Option[String] = None,
    val dtsCompat: Option[Seq[String]] = None
  )(implicit p: Parameters) extends DiplomaticSRAM(address, beatBytes, devName, dtsCompat)
{
  val eccBytes = ecc.bytes
  val code = ecc.code
  require (eccBytes  >= 1 && isPow2(eccBytes))
  require (beatBytes >= 1 && isPow2(beatBytes))
  require (eccBytes <= beatBytes, s"TLRAM eccBytes (${eccBytes}) > beatBytes (${beatBytes}). Use a WidthWidget=>Fragmenter=>SRAM if you need high density and narrow ECC; it will do bursts efficiently")

  val node = TLManagerNode(Seq(TLSlavePortParameters.v1(
    Seq(TLSlaveParameters.v1(
      address            = List(address),
      resources          = device.reg("mem"),
      regionType         = if (cacheable) RegionType.UNCACHED else RegionType.IDEMPOTENT,
      executable         = executable,
      supportsGet        = TransferSizes(1, beatBytes),
      supportsPutPartial = TransferSizes(1, beatBytes),
      supportsPutFull    = TransferSizes(1, beatBytes),
      supportsArithmetic = if (atomics) TransferSizes(1, beatBytes) else TransferSizes.none,
      supportsLogical    = if (atomics) TransferSizes(1, beatBytes) else TransferSizes.none,
      fifoId             = Some(0))), // requests are handled in order
    beatBytes  = beatBytes,
    minLatency = 1))) // no bypass needed for this device

  val indexBits = address.mask.bitCount - log2Ceil(beatBytes)
  val notifyNode = ecc.notifyErrors.option(BundleBridgeSource(() => new TLRAMErrors(ecc, indexBits).cloneType))

  lazy val module = new LazyModuleImp(this) {
    val (in, edge) = node.in(0)

    val width = code.width(eccBytes*8)
    val lanes = beatBytes/eccBytes
    val addrBits = (mask zip edge.addr_hi(in.a.bits).asBools).filter(_._1).map(_._2)
    val (mem, omSRAM, omMem) = makeSinglePortedByteWriteSeqMem(
      size = 1 << addrBits.size,
      lanes = lanes,
      bits = width)

    parentLogicalTreeNode.map {
      case parentLTN =>
        def sramLogicalTreeNode = new BusMemoryLogicalTreeNode(
          device = device,
          omSRAMs = Seq(omSRAM),
          busProtocol = new TL_UL(None),
          dataECC = Some(OMECC.fromCode(ecc.code)),
          hasAtomics = Some(atomics),
          busProtocolSpecification = None)
        LogicalModuleTree.add(parentLTN, sramLogicalTreeNode)
    }

    /* This block uses a two-stage pipeline; A=>D
     * Both stages vie for access to the single SRAM port.
     * Stage D has absolute priority over stage A.
     *   - read-modify-writeback for sub-lane access happens here
     *   - writeback of correctable data happens here
     *   - both actions may occur concurrently
     * Stage A has lower priority and will stall if blocked
     *   - read operations happen here
     *   - full-lane write operations happen here
     */

    // D stage registers from A
    val d_full      = RegInit(Bool(false))
    val d_ram_valid = RegInit(Bool(false)) // true if we just read-out from SRAM
    val d_size      = Reg(UInt())
    val d_source    = Reg(UInt())
    val d_read      = Reg(Bool())
    val d_atomic    = Reg(Bool())
    val d_address   = Reg(UInt(width = addrBits.size))
    val d_rmw_mask  = Reg(UInt(width = beatBytes))
    val d_rmw_data  = Reg(UInt(width = 8*beatBytes))
    val d_poison    = Reg(Bool())
    val d_lanes     = Reg(UInt(width = lanes))

    // Decode raw unregistered SRAM output
    val d_raw_data      = Wire(Vec(lanes, Bits(width = width)))
    val d_decoded       = d_raw_data.map(lane => code.decode(lane))
    val d_corrected     = Cat(d_decoded.map(_.corrected).reverse)
    val d_uncorrected   = Cat(d_decoded.map(_.uncorrected).reverse)
    val d_correctable   = d_decoded.map(_.correctable)
    val d_uncorrectable = d_decoded.map(_.uncorrectable)
    val d_need_fix      = d_correctable.reduce(_ || _)
    val d_lane_error    = Cat(d_uncorrectable.reverse) & d_lanes
    val d_error         = d_lane_error.orR

    notifyNode.foreach { nnode =>
      nnode.bundle.correctable.foreach { c =>
        c.valid := d_need_fix && d_ram_valid
        c.bits  := d_address
      }
      nnode.bundle.uncorrectable.foreach { u =>
        u.valid := d_error && d_ram_valid
        u.bits  := d_address
      }
    }

    // What does D-stage want to write-back?
    // Make an ALU if we need one
    val d_updated = if (atomics) {
      val alu = Module(new Atomics(edge.bundle))
      alu.io.write   := Bool(false)
      alu.io.a       := RegEnable(in.a.bits, in.a.fire())
      alu.io.a.data  := d_rmw_data // save a few flops
      alu.io.a.mask  := d_rmw_mask
      alu.io.data_in := d_corrected
      alu.io.data_out
    } else {
      Cat(Seq.tabulate(beatBytes) { i =>
        val upd = d_rmw_mask(i)
        val rmw = d_rmw_data (8*(i+1)-1, 8*i)
        val fix = d_corrected(8*(i+1)-1, 8*i) // safe to use, because D-stage write-back always wins arbitration
        Mux(upd, rmw, fix)
      }.reverse)
    }

    // Split into eccByte-sized chunks:
    val d_wb_data = Vec(Seq.tabulate(beatBytes/eccBytes) { i =>
      d_updated(8*eccBytes*(i+1)-1, 8*eccBytes*i)
    })
    val (d_wb_lanes, d_wb_poison) = Seq.tabulate(lanes) { i =>
      val upd = d_rmw_mask(eccBytes*(i+1)-1, eccBytes*i)
      (upd.orR || d_correctable(i),
       (!upd.andR && d_uncorrectable(i)) || d_poison) // sub-lane writes should not correct uncorrectable
    }.unzip
    val d_wb = d_rmw_mask.orR || (d_ram_valid && d_need_fix)

    // Extend the validity of SRAM read-out
    val d_held_data = RegEnable(d_corrected, d_ram_valid)
    val d_held_error = RegEnable(d_error, d_ram_valid)

    in.d.bits.opcode  := Mux(d_read || d_atomic, TLMessages.AccessAckData, TLMessages.AccessAck)
    in.d.bits.param   := UInt(0)
    in.d.bits.size    := d_size
    in.d.bits.source  := d_source
    in.d.bits.sink    := UInt(0)
    in.d.bits.denied  := Bool(false)
    // It is safe to use uncorrected data here because of d_pause
    in.d.bits.data    := Mux(d_ram_valid, d_uncorrected, d_held_data)
    in.d.bits.corrupt := Mux(d_ram_valid, d_error, d_held_error) && (d_read || d_atomic)
    
    val mem_active_valid = Seq(CoverBoolean(in.d.valid, Seq("mem_active")))
    val data_error = Seq(
      CoverBoolean(!d_need_fix && !d_error , Seq("no_data_error")),
      CoverBoolean(d_need_fix && !in.d.bits.corrupt, Seq("data_correctable_error_not_reported")),
      CoverBoolean(d_error && in.d.bits.corrupt, Seq("data_uncorrectable_error_reported")))

    val error_cross_covers = new CrossProperty(Seq(mem_active_valid, data_error), Seq(), "Ecc Covers")
    cover(error_cross_covers)


    // Formulate a response only when SRAM output is unused or correct
    val d_pause = (d_read || d_atomic) && d_ram_valid && d_need_fix
    in.d.valid := d_full && !d_pause
    in.a.ready := !d_full || (in.d.ready && !d_pause && !d_wb)

    val a_address = Cat(addrBits.reverse)
    val a_read = in.a.bits.opcode === TLMessages.Get
    val a_data = Vec(Seq.tabulate(lanes) { i => in.a.bits.data(eccBytes*8*(i+1)-1, eccBytes*8*i) })

/*
    val a_sublane = Seq.tabulate(lanes) { i =>
      val upd = in.a.bits.mask(eccBytes*(i+1)-1, eccBytes*i)
      upd.orR && !upd.andR
    }.reduce(_ || _)
*/
    val a_sublane = if (eccBytes == 1) Bool(false) else
      in.a.bits.opcode === TLMessages.PutPartialData ||
      in.a.bits.size < UInt(log2Ceil(eccBytes))
    val a_atomic = if (!atomics) Bool(false) else
      in.a.bits.opcode === TLMessages.ArithmeticData ||
      in.a.bits.opcode === TLMessages.LogicalData
    val a_ren = a_read || a_atomic || a_sublane
    val a_lanes = Seq.tabulate(lanes) { i => in.a.bits.mask(eccBytes*(i+1)-1, eccBytes*i).orR }

    when (in.d.fire()) { d_full := Bool(false) }
    d_ram_valid := Bool(false)
    d_rmw_mask  := UInt(0)
    when (in.a.fire()) {
      d_full      := Bool(true)
      d_ram_valid := a_ren
      d_size      := in.a.bits.size
      d_source    := in.a.bits.source
      d_read      := a_read
      d_atomic    := a_atomic
      d_address   := a_address
      d_rmw_mask  := UInt(0)
      d_poison    := in.a.bits.corrupt
      d_lanes     := Cat(a_lanes.reverse)
      when (!a_read && (a_sublane || a_atomic)) {
        d_rmw_mask := in.a.bits.mask
        d_rmw_data := in.a.bits.data
      }
      d_held_error:= Bool(false)
    }

    // SRAM arbitration
    val a_fire = in.a.fire()
    val wen =  d_wb || (a_fire && !a_ren)
//  val ren = !d_wb && (a_fire &&  a_ren)
    val ren = !wen && a_fire // help Chisel infer a RW-port

    val addr   = Mux(d_wb, d_address, a_address)
    val sel    = Mux(d_wb, Vec(d_wb_lanes), Vec(a_lanes))
    val dat    = Mux(d_wb, d_wb_data, a_data)
    val poison = Mux(d_wb, Vec(d_wb_poison), Vec.fill(lanes) { in.a.bits.corrupt })
    val coded  = Vec((dat zip poison) map { case (d, p) =>
      if (code.canDetect) code.encode(d, p) else code.encode(d)
    })

    d_raw_data := mem.read(addr, ren)
    when (wen) { mem.write(addr, coded, sel) }

    // Tie off unused channels
    in.b.valid := Bool(false)
    in.c.ready := Bool(true)
    in.e.ready := Bool(true)
  }
}

object TLRAM
{
  def apply(
    address: AddressSet,
    parentLogicalTreeNode: Option[LogicalTreeNode] = None,
    cacheable: Boolean = true,
    executable: Boolean = true,
    atomics: Boolean = false,
    beatBytes: Int = 4,
    ecc: ECCParams = ECCParams(),
    devName: Option[String] = None,
  )(implicit p: Parameters): TLInwardNode =
  {
    val ram = LazyModule(new TLRAM(address, parentLogicalTreeNode, cacheable, executable, atomics, beatBytes, ecc, devName))
    ram.node
  }
}

/** Synthesizeable unit testing */
import freechips.rocketchip.unittest._

class TLRAMSimple(ramBeatBytes: Int, txns: Int)(implicit p: Parameters) extends LazyModule {
  val fuzz = LazyModule(new TLFuzzer(txns))
  val model = LazyModule(new TLRAMModel("SRAMSimple"))
  val ram  = LazyModule(new TLRAM(AddressSet(0x0, 0x3ff), beatBytes = ramBeatBytes))

  ram.node := TLDelayer(0.25) := model.node := fuzz.node

  lazy val module = new LazyModuleImp(this) with UnitTestModule {
    io.finished := fuzz.module.io.finished
  }
}

class TLRAMSimpleTest(ramBeatBytes: Int, txns: Int = 5000, timeout: Int = 500000)(implicit p: Parameters) extends UnitTest(timeout) {
  val dut = Module(LazyModule(new TLRAMSimple(ramBeatBytes, txns)).module)
  io.finished := dut.io.finished
}

class TLRAMECC(ramBeatBytes: Int, eccBytes: Int, txns: Int)(implicit p: Parameters) extends LazyModule {
  val fuzz = LazyModule(new TLFuzzer(txns))
  val model = LazyModule(new TLRAMModel("SRAMSimple"))
  val ram  = LazyModule(new TLRAM(AddressSet(0x0, 0x3ff), atomics = true, beatBytes = ramBeatBytes, ecc = ECCParams(bytes = eccBytes, code = new SECDEDCode)))

  ram.node := TLDelayer(0.25) := model.node := fuzz.node

  lazy val module = new LazyModuleImp(this) with UnitTestModule {
    io.finished := fuzz.module.io.finished
  }
}

class TLRAMECCTest(ramBeatBytes: Int, eccBytes: Int, txns: Int = 5000, timeout: Int = 500000)(implicit p: Parameters) extends UnitTest(timeout) {
  val dut = Module(LazyModule(new TLRAMECC(ramBeatBytes, eccBytes, txns)).module)
  io.finished := dut.io.finished
}
