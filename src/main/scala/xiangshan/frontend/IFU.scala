package xiangshan.frontend

import chisel3._
import chisel3.util._
import device.RAMHelper
import xiangshan._
import xiangshan.utils._

trait HasIFUConst { this: XSModule =>
  val resetVector = 0x80000000L//TODO: set reset vec

  val groupAlign = log2Up(FetchWidth * 4)
  def groupPC(pc: UInt): UInt = Cat(pc(VAddrBits-1, groupAlign), 0.U(groupAlign.W))
  
}

class FakeIcacheResp extends XSBundle {
  val icacheOut = Vec(FetchWidth, UInt(32.W))
  val predecode = new Predecode
}


class IFUIO extends XSBundle
{
    val fetchPacket = DecoupledIO(new FetchPacket)
    val redirectInfo = Input(new RedirectInfo)
    val icacheReq = DecoupledIO(UInt(VAddrBits.W))
    val icacheResp = Flipped(DecoupledIO(new FakeIcacheResp))
}

class FakeBPU extends XSModule{
  val io = IO(new Bundle() {
    val in = new Bundle { val pc = Flipped(Valid(UInt(VAddrBits.W))) }
    val btbOut = ValidIO(new BranchPrediction)
    val tageOut = ValidIO(new BranchPrediction)
    val predecode = Flipped(ValidIO(new Predecode))
  })

  io.btbOut.valid := false.B
  io.btbOut.bits <> DontCare
  io.tageOut.valid := false.B
  io.tageOut.bits <> DontCare
}



class IFU extends XSModule with HasIFUConst
{
    val io = IO(new IFUIO)
    //val bpu = Module(new BPU)
    val bpu = Module(new FakeBPU)

    //-------------------------
    //      IF1  PC update
    //-------------------------
    //local
    val if1_npc = WireInit(0.U(VAddrBits.W))
    val if1_valid = !reset.asBool //TODO:this is ugly
    val if1_pc = RegInit(resetVector.U(VAddrBits.W))
    //next
    val if2_ready = WireInit(false.B)
    val if2_snpc = Cat(if1_pc(VAddrBits-1, groupAlign) + 1.U, 0.U(groupAlign.W))
    val if1_ready = if2_ready

    //pipe fire
    val if1_fire = if1_valid && if1_ready 
    val if1_pcUpdate = io.redirectInfo.flush() || if1_fire

    when(RegNext(reset.asBool) && !reset.asBool){
      XSDebug("RESET....\n")
      if1_npc := resetVector.U(VAddrBits.W)
    } .otherwise{
      if1_npc := if2_snpc
    }

    when(if1_pcUpdate)
    { 
      if1_pc := if1_npc
    }

    bpu.io.in.pc.valid := if1_valid
    bpu.io.in.pc.bits := if1_npc

    XSDebug("[IF1]if1_valid:%d  ||  if1_npc:0x%x  || if1_pcUpdate:%d if1_pc:0x%x  || if2_ready:%d",if1_valid,if1_npc,if1_pcUpdate,if1_pc,if2_ready)
    XSDebug(false,if1_fire,"------IF1->fire!!!")
    XSDebug(false,true.B,"\n")

    //-------------------------
    //      IF2  btb resonse 
    //           icache visit
    //-------------------------
    //local
    val if2_valid = RegEnable(next=if1_valid,init=false.B,enable=if1_fire)
    val if2_pc = if1_pc
    val if2_btb_taken = bpu.io.btbOut.valid
    val if2_btb_insMask = bpu.io.btbOut.bits.instrValid
    val if2_btb_target = bpu.io.btbOut.bits.target

    //next
    val if3_ready = WireInit(false.B)

    //pipe fire
    val if2_fire = if2_valid && if3_ready && io.icacheReq.fire()
    if2_ready := (if2_fire) || !if2_valid

    io.icacheReq.valid := if2_valid
    io.icacheReq.bits := groupPC(if2_pc)

    when(if2_valid && if2_btb_taken)
    {
      if1_npc := if2_btb_target
    }

    XSDebug("[IF2]if2_valid:%d  ||  if2_pc:0x%x   || if3_ready:%d                                       ",if2_valid,if2_pc,if3_ready)
    //XSDebug("[IF2-BPU-out]if2_btbTaken:%d || if2_btb_insMask:%b || if2_btb_target:0x%x \n",if2_btb_taken,if2_btb_insMask.asUInt,if2_btb_target)
    XSDebug(false,if2_fire,"------IF2->fire!!!")
    XSDebug(false,true.B,"\n")
    XSDebug("[IF2-Icache-Req] icache_in_valid:%d  icache_in_ready:%d\n",io.icacheReq.valid,io.icacheReq.ready)

    //-------------------------
    //      IF3  icache hit check
    //-------------------------
    //local
    val if3_valid = RegEnable(next=if2_valid,init=false.B,enable=if2_fire)
    val if3_pc = RegEnable(if2_pc,if2_fire)
    val if3_btb_target = RegEnable(if2_btb_target,if2_fire)
    val if3_btb_taken = RegEnable(if2_btb_taken,if2_fire)

    //next
    val if4_ready = WireInit(false.B)

    //pipe fire
    val if3_fire = if3_valid && if4_ready
    if3_ready := if3_fire  || !if3_valid


    XSDebug("[IF3]if3_valid:%d  ||  if3_pc:0x%x   || if4_ready:%d                                       ",if3_valid,if3_pc,if4_ready)
    XSDebug(false,if3_fire,"------IF3->fire!!!")
    XSDebug(false,true.B,"\n")

    //-------------------------
    //      IF4  icache resonse   
    //           RAS result
    //           taget generate
    //-------------------------
    val if4_valid = RegEnable(next=if3_valid,init=false.B,enable=if3_fire)
    val if4_pc = RegEnable(if3_pc,if3_fire)
    val if4_btb_target = RegEnable(if3_btb_target,if3_fire)
    val if4_btb_taken = RegEnable(if3_btb_taken,if3_fire)
    val if4_tage_target = bpu.io.tageOut.bits.target
    val if4_tage_taken = bpu.io.tageOut.valid
    val if4_tage_insMask = bpu.io.tageOut.bits.instrValid
    XSDebug("[IF4]if4_valid:%d  ||  if4_pc:0x%x  \n",if4_valid,if4_pc)
    //XSDebug("[IF4-TAGE-out]if4_tage_taken:%d || if4_btb_insMask:%b || if4_tage_target:0x%x \n",if4_tage_taken,if4_tage_insMask.asUInt,if4_tage_target)
    XSDebug("[IF4-ICACHE-RESP]icacheResp.valid:%d   icacheResp.ready:%d\n",io.icacheResp.valid,io.icacheResp.ready)

    when(if4_valid && io.icacheResp.fire() && if4_tage_taken)
    {
      if1_npc := if4_tage_target
    }
    

    //redirect: miss predict
    when(io.redirectInfo.flush()){
      if1_npc := io.redirectInfo.redirect.target
      if3_valid := false.B
      if4_valid := false.B
    }
    XSDebug(io.redirectInfo.flush(),"[IFU-REDIRECT] target:0x%x  \n",io.redirectInfo.redirect.target.asUInt)

    //Output -> iBuffer
    io.fetchPacket <> DontCare
    if4_ready := io.fetchPacket.ready && (io.icacheResp.valid || !if4_valid)
    io.fetchPacket.valid := if4_valid && !io.redirectInfo.flush()
    io.fetchPacket.bits.instrs := io.icacheResp.bits.icacheOut
    io.fetchPacket.bits.mask := Fill(FetchWidth*2, 1.U(1.W)) << if4_pc(2+log2Up(FetchWidth)-1, 1)
    io.fetchPacket.bits.pc := if4_pc

    //to BPU
    bpu.io.predecode.valid := io.icacheResp.fire() && if4_valid
    bpu.io.predecode.bits <> io.icacheResp.bits.predecode
    bpu.io.predecode.bits.mask := Fill(FetchWidth, 1.U(1.W)) << if4_pc(2+log2Up(FetchWidth)-1, 2) //TODO: consider RVC

    io.icacheResp.ready := io.fetchPacket.ready 

}

