/*
 * Copyright (c) 1997, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#include "precompiled.hpp"
#include "jvm.h"
#include "asm/assembler.hpp"
#include "asm/assembler.inline.hpp"
#include "compiler/disassembler.hpp"
#include "gc/shared/barrierSet.hpp"
#include "gc/shared/barrierSetAssembler.hpp"
#include "gc/shared/collectedHeap.inline.hpp"
#include "interpreter/interpreter.hpp"
#include "memory/resourceArea.hpp"
#include "memory/universe.hpp"
#include "oops/accessDecorators.hpp"
#include "oops/klass.inline.hpp"
#include "prims/methodHandles.hpp"
#include "runtime/biasedLocking.hpp"
#include "runtime/flags/flagSetting.hpp"
#include "runtime/interfaceSupport.inline.hpp"
#include "runtime/objectMonitor.hpp"
#include "runtime/os.hpp"
#include "runtime/safepoint.hpp"
#include "runtime/safepointMechanism.hpp"
#include "runtime/sharedRuntime.hpp"
#include "runtime/stubRoutines.hpp"
#include "runtime/thread.hpp"
#include "utilities/macros.hpp"
#include "crc32c.h"
#ifdef COMPILER2
#include "opto/intrinsicnode.hpp"
#endif

#ifdef PRODUCT
#define BLOCK_COMMENT(str) /* nothing */
#define STOP(error) stop(error)
#else
#define BLOCK_COMMENT(str) block_comment(str)
#define STOP(error) block_comment(error); stop(error)
#endif

#define BIND(label) bind(label); BLOCK_COMMENT(#label ":")

#ifdef ASSERT
bool AbstractAssembler::pd_check_instruction_mark() { return true; }
#endif

static Assembler::Condition reverse[] = {
    Assembler::noOverflow     /* overflow      = 0x0 */ ,
    Assembler::overflow       /* noOverflow    = 0x1 */ ,
    Assembler::aboveEqual     /* carrySet      = 0x2, below         = 0x2 */ ,
    Assembler::below          /* aboveEqual    = 0x3, carryClear    = 0x3 */ ,
    Assembler::notZero        /* zero          = 0x4, equal         = 0x4 */ ,
    Assembler::zero           /* notZero       = 0x5, notEqual      = 0x5 */ ,
    Assembler::above          /* belowEqual    = 0x6 */ ,
    Assembler::belowEqual     /* above         = 0x7 */ ,
    Assembler::positive       /* negative      = 0x8 */ ,
    Assembler::negative       /* positive      = 0x9 */ ,
    Assembler::noParity       /* parity        = 0xa */ ,
    Assembler::parity         /* noParity      = 0xb */ ,
    Assembler::greaterEqual   /* less          = 0xc */ ,
    Assembler::less           /* greaterEqual  = 0xd */ ,
    Assembler::greater        /* lessEqual     = 0xe */ ,
    Assembler::lessEqual      /* greater       = 0xf, */

};


// Implementation of MacroAssembler

// First all the versions that have distinct versions depending on 32/64 bit
// Unless the difference is trivial (1 line or so).

#ifndef _LP64

// 32bit versions

Address MacroAssembler::as_Address(AddressLiteral adr) {
  return Address(adr.target(), adr.rspec());
}

Address MacroAssembler::as_Address(ArrayAddress adr) {
  return Address::make_array(adr);
}

void MacroAssembler::call_VM_leaf_base(address entry_point,
                                       int number_of_arguments) {
  call(RuntimeAddress(entry_point));
  increment(rsp, number_of_arguments * wordSize);
}

void MacroAssembler::cmpklass(Address src1, Metadata* obj) {
  cmp_literal32(src1, (int32_t)obj, metadata_Relocation::spec_for_immediate());
}

void MacroAssembler::cmpklass(Register src1, Metadata* obj) {
  cmp_literal32(src1, (int32_t)obj, metadata_Relocation::spec_for_immediate());
}

void MacroAssembler::cmpoop_raw(Address src1, jobject obj) {
  cmp_literal32(src1, (int32_t)obj, oop_Relocation::spec_for_immediate());
}

void MacroAssembler::cmpoop_raw(Register src1, jobject obj) {
  cmp_literal32(src1, (int32_t)obj, oop_Relocation::spec_for_immediate());
}

void MacroAssembler::cmpoop(Address src1, jobject obj) {
  BarrierSetAssembler* bs = BarrierSet::barrier_set()->barrier_set_assembler();
  bs->obj_equals(this, src1, obj);
}

void MacroAssembler::cmpoop(Register src1, jobject obj) {
  BarrierSetAssembler* bs = BarrierSet::barrier_set()->barrier_set_assembler();
  bs->obj_equals(this, src1, obj);
}

void MacroAssembler::extend_sign(Register hi, Register lo) {
  // According to Intel Doc. AP-526, "Integer Divide", p.18.
  if (VM_Version::is_P6() && hi == rdx && lo == rax) {
    cdql();
  } else {
    movl(hi, lo);
    sarl(hi, 31);
  }
}

void MacroAssembler::jC2(Register tmp, Label& L) {
  // set parity bit if FPU flag C2 is set (via rax)
  save_rax(tmp);
  fwait(); fnstsw_ax();
  sahf();
  restore_rax(tmp);
  // branch
  jcc(Assembler::parity, L);
}

void MacroAssembler::jnC2(Register tmp, Label& L) {
  // set parity bit if FPU flag C2 is set (via rax)
  save_rax(tmp);
  fwait(); fnstsw_ax();
  sahf();
  restore_rax(tmp);
  // branch
  jcc(Assembler::noParity, L);
}

// 32bit can do a case table jump in one instruction but we no longer allow the base
// to be installed in the Address class
void MacroAssembler::jump(ArrayAddress entry) {
  jmp(as_Address(entry));
}

// Note: y_lo will be destroyed
void MacroAssembler::lcmp2int(Register x_hi, Register x_lo, Register y_hi, Register y_lo) {
  // Long compare for Java (semantics as described in JVM spec.)
  Label high, low, done;

  cmpl(x_hi, y_hi);
  jcc(Assembler::less, low);
  jcc(Assembler::greater, high);
  // x_hi is the return register
  xorl(x_hi, x_hi);
  cmpl(x_lo, y_lo);
  jcc(Assembler::below, low);
  jcc(Assembler::equal, done);

  bind(high);
  xorl(x_hi, x_hi);
  increment(x_hi);
  jmp(done);

  bind(low);
  xorl(x_hi, x_hi);
  decrementl(x_hi);

  bind(done);
}

void MacroAssembler::lea(Register dst, AddressLiteral src) {
    mov_literal32(dst, (int32_t)src.target(), src.rspec());
}

void MacroAssembler::lea(Address dst, AddressLiteral adr) {
  // leal(dst, as_Address(adr));
  // see note in movl as to why we must use a move
  mov_literal32(dst, (int32_t) adr.target(), adr.rspec());
}

void MacroAssembler::leave() {
  mov(rsp, rbp);
  pop(rbp);
}

void MacroAssembler::lmul(int x_rsp_offset, int y_rsp_offset) {
  // Multiplication of two Java long values stored on the stack
  // as illustrated below. Result is in rdx:rax.
  //
  // rsp ---> [  ??  ] \               \
  //            ....    | y_rsp_offset  |
  //          [ y_lo ] /  (in bytes)    | x_rsp_offset
  //          [ y_hi ]                  | (in bytes)
  //            ....                    |
  //          [ x_lo ]                 /
  //          [ x_hi ]
  //            ....
  //
  // Basic idea: lo(result) = lo(x_lo * y_lo)
  //             hi(result) = hi(x_lo * y_lo) + lo(x_hi * y_lo) + lo(x_lo * y_hi)
  Address x_hi(rsp, x_rsp_offset + wordSize); Address x_lo(rsp, x_rsp_offset);
  Address y_hi(rsp, y_rsp_offset + wordSize); Address y_lo(rsp, y_rsp_offset);
  Label quick;
  // load x_hi, y_hi and check if quick
  // multiplication is possible
  movl(rbx, x_hi);
  movl(rcx, y_hi);
  movl(rax, rbx);
  orl(rbx, rcx);                                 // rbx, = 0 <=> x_hi = 0 and y_hi = 0
  jcc(Assembler::zero, quick);                   // if rbx, = 0 do quick multiply
  // do full multiplication
  // 1st step
  mull(y_lo);                                    // x_hi * y_lo
  movl(rbx, rax);                                // save lo(x_hi * y_lo) in rbx,
  // 2nd step
  movl(rax, x_lo);
  mull(rcx);                                     // x_lo * y_hi
  addl(rbx, rax);                                // add lo(x_lo * y_hi) to rbx,
  // 3rd step
  bind(quick);                                   // note: rbx, = 0 if quick multiply!
  movl(rax, x_lo);
  mull(y_lo);                                    // x_lo * y_lo
  addl(rdx, rbx);                                // correct hi(x_lo * y_lo)
}

void MacroAssembler::lneg(Register hi, Register lo) {
  negl(lo);
  adcl(hi, 0);
  negl(hi);
}

void MacroAssembler::lshl(Register hi, Register lo) {
  // Java shift left long support (semantics as described in JVM spec., p.305)
  // (basic idea for shift counts s >= n: x << s == (x << n) << (s - n))
  // shift value is in rcx !
  assert(hi != rcx, "must not use rcx");
  assert(lo != rcx, "must not use rcx");
  const Register s = rcx;                        // shift count
  const int      n = BitsPerWord;
  Label L;
  andl(s, 0x3f);                                 // s := s & 0x3f (s < 0x40)
  cmpl(s, n);                                    // if (s < n)
  jcc(Assembler::less, L);                       // else (s >= n)
  movl(hi, lo);                                  // x := x << n
  xorl(lo, lo);
  // Note: subl(s, n) is not needed since the Intel shift instructions work rcx mod n!
  bind(L);                                       // s (mod n) < n
  shldl(hi, lo);                                 // x := x << s
  shll(lo);
}


void MacroAssembler::lshr(Register hi, Register lo, bool sign_extension) {
  // Java shift right long support (semantics as described in JVM spec., p.306 & p.310)
  // (basic idea for shift counts s >= n: x >> s == (x >> n) >> (s - n))
  assert(hi != rcx, "must not use rcx");
  assert(lo != rcx, "must not use rcx");
  const Register s = rcx;                        // shift count
  const int      n = BitsPerWord;
  Label L;
  andl(s, 0x3f);                                 // s := s & 0x3f (s < 0x40)
  cmpl(s, n);                                    // if (s < n)
  jcc(Assembler::less, L);                       // else (s >= n)
  movl(lo, hi);                                  // x := x >> n
  if (sign_extension) sarl(hi, 31);
  else                xorl(hi, hi);
  // Note: subl(s, n) is not needed since the Intel shift instructions work rcx mod n!
  bind(L);                                       // s (mod n) < n
  shrdl(lo, hi);                                 // x := x >> s
  if (sign_extension) sarl(hi);
  else                shrl(hi);
}

void MacroAssembler::movoop(Register dst, jobject obj) {
  mov_literal32(dst, (int32_t)obj, oop_Relocation::spec_for_immediate());
}

void MacroAssembler::movoop(Address dst, jobject obj) {
  mov_literal32(dst, (int32_t)obj, oop_Relocation::spec_for_immediate());
}

void MacroAssembler::mov_metadata(Register dst, Metadata* obj) {
  mov_literal32(dst, (int32_t)obj, metadata_Relocation::spec_for_immediate());
}

void MacroAssembler::mov_metadata(Address dst, Metadata* obj) {
  mov_literal32(dst, (int32_t)obj, metadata_Relocation::spec_for_immediate());
}

void MacroAssembler::movptr(Register dst, AddressLiteral src, Register scratch) {
  // scratch register is not used,
  // it is defined to match parameters of 64-bit version of this method.
  if (src.is_lval()) {
    mov_literal32(dst, (intptr_t)src.target(), src.rspec());
  } else {
    movl(dst, as_Address(src));
  }
}

void MacroAssembler::movptr(ArrayAddress dst, Register src) {
  movl(as_Address(dst), src);
}

void MacroAssembler::movptr(Register dst, ArrayAddress src) {
  movl(dst, as_Address(src));
}

// src should NEVER be a real pointer. Use AddressLiteral for true pointers
void MacroAssembler::movptr(Address dst, intptr_t src) {
  movl(dst, src);
}


void MacroAssembler::pop_callee_saved_registers() {
  pop(rcx);
  pop(rdx);
  pop(rdi);
  pop(rsi);
}

void MacroAssembler::pop_fTOS() {
  fld_d(Address(rsp, 0));
  addl(rsp, 2 * wordSize);
}

void MacroAssembler::push_callee_saved_registers() {
  push(rsi);
  push(rdi);
  push(rdx);
  push(rcx);
}

void MacroAssembler::push_fTOS() {
  subl(rsp, 2 * wordSize);
  fstp_d(Address(rsp, 0));
}


void MacroAssembler::pushoop(jobject obj) {
  push_literal32((int32_t)obj, oop_Relocation::spec_for_immediate());
}

void MacroAssembler::pushklass(Metadata* obj) {
  push_literal32((int32_t)obj, metadata_Relocation::spec_for_immediate());
}

void MacroAssembler::pushptr(AddressLiteral src) {
  if (src.is_lval()) {
    push_literal32((int32_t)src.target(), src.rspec());
  } else {
    pushl(as_Address(src));
  }
}

void MacroAssembler::set_word_if_not_zero(Register dst) {
  xorl(dst, dst);
  set_byte_if_not_zero(dst);
}

static void pass_arg0(MacroAssembler* masm, Register arg) {
  masm->push(arg);
}

static void pass_arg1(MacroAssembler* masm, Register arg) {
  masm->push(arg);
}

static void pass_arg2(MacroAssembler* masm, Register arg) {
  masm->push(arg);
}

static void pass_arg3(MacroAssembler* masm, Register arg) {
  masm->push(arg);
}

#ifndef PRODUCT
extern "C" void findpc(intptr_t x);
#endif

void MacroAssembler::debug32(int rdi, int rsi, int rbp, int rsp, int rbx, int rdx, int rcx, int rax, int eip, char* msg) {
  // In order to get locks to work, we need to fake a in_VM state
  JavaThread* thread = JavaThread::current();
  JavaThreadState saved_state = thread->thread_state();
  thread->set_thread_state(_thread_in_vm);
  if (ShowMessageBoxOnError) {
    JavaThread* thread = JavaThread::current();
    JavaThreadState saved_state = thread->thread_state();
    thread->set_thread_state(_thread_in_vm);
    if (CountBytecodes || TraceBytecodes || StopInterpreterAt) {
      ttyLocker ttyl;
      BytecodeCounter::print();
    }
    // To see where a verify_oop failed, get $ebx+40/X for this frame.
    // This is the value of eip which points to where verify_oop will return.
    if (os::message_box(msg, "Execution stopped, print registers?")) {
      print_state32(rdi, rsi, rbp, rsp, rbx, rdx, rcx, rax, eip);
      BREAKPOINT;
    }
  } else {
    ttyLocker ttyl;
    ::tty->print_cr("=============== DEBUG MESSAGE: %s ================\n", msg);
  }
  // Don't assert holding the ttyLock
    assert(false, "DEBUG MESSAGE: %s", msg);
  ThreadStateTransition::transition(thread, _thread_in_vm, saved_state);
}

void MacroAssembler::print_state32(int rdi, int rsi, int rbp, int rsp, int rbx, int rdx, int rcx, int rax, int eip) {
  ttyLocker ttyl;
  FlagSetting fs(Debugging, true);
  tty->print_cr("eip = 0x%08x", eip);
#ifndef PRODUCT
  if ((WizardMode || Verbose) && PrintMiscellaneous) {
    tty->cr();
    findpc(eip);
    tty->cr();
  }
#endif
#define PRINT_REG(rax) \
  { tty->print("%s = ", #rax); os::print_location(tty, rax); }
  PRINT_REG(rax);
  PRINT_REG(rbx);
  PRINT_REG(rcx);
  PRINT_REG(rdx);
  PRINT_REG(rdi);
  PRINT_REG(rsi);
  PRINT_REG(rbp);
  PRINT_REG(rsp);
#undef PRINT_REG
  // Print some words near top of staack.
  int* dump_sp = (int*) rsp;
  for (int col1 = 0; col1 < 8; col1++) {
    tty->print("(rsp+0x%03x) 0x%08x: ", (int)((intptr_t)dump_sp - (intptr_t)rsp), (intptr_t)dump_sp);
    os::print_location(tty, *dump_sp++);
  }
  for (int row = 0; row < 16; row++) {
    tty->print("(rsp+0x%03x) 0x%08x: ", (int)((intptr_t)dump_sp - (intptr_t)rsp), (intptr_t)dump_sp);
    for (int col = 0; col < 8; col++) {
      tty->print(" 0x%08x", *dump_sp++);
    }
    tty->cr();
  }
  // Print some instructions around pc:
  Disassembler::decode((address)eip-64, (address)eip);
  tty->print_cr("--------");
  Disassembler::decode((address)eip, (address)eip+32);
}

void MacroAssembler::stop(const char* msg) {
  ExternalAddress message((address)msg);
  // push address of message
  pushptr(message.addr());
  { Label L; call(L, relocInfo::none); bind(L); }     // push eip
  pusha();                                            // push registers
  call(RuntimeAddress(CAST_FROM_FN_PTR(address, MacroAssembler::debug32)));
  hlt();
}

void MacroAssembler::warn(const char* msg) {
  push_CPU_state();

  ExternalAddress message((address) msg);
  // push address of message
  pushptr(message.addr());

  call(RuntimeAddress(CAST_FROM_FN_PTR(address, warning)));
  addl(rsp, wordSize);       // discard argument
  pop_CPU_state();
}

void MacroAssembler::print_state() {
  { Label L; call(L, relocInfo::none); bind(L); }     // push eip
  pusha();                                            // push registers

  push_CPU_state();
  call(RuntimeAddress(CAST_FROM_FN_PTR(address, MacroAssembler::print_state32)));
  pop_CPU_state();

  popa();
  addl(rsp, wordSize);
}

#else // _LP64

// 64 bit versions

Address MacroAssembler::as_Address(AddressLiteral adr) {
  // amd64 always does this as a pc-rel
  // we can be absolute or disp based on the instruction type
  // jmp/call are displacements others are absolute
  assert(!adr.is_lval(), "must be rval");
  assert(reachable(adr), "must be");
  return Address((int32_t)(intptr_t)(adr.target() - pc()), adr.target(), adr.reloc());

}

Address MacroAssembler::as_Address(ArrayAddress adr) {
  AddressLiteral base = adr.base();
  lea(rscratch1, base);
  Address index = adr.index();
  assert(index._disp == 0, "must not have disp"); // maybe it can?
  Address array(rscratch1, index._index, index._scale, index._disp);
  return array;
}

void MacroAssembler::call_VM_leaf_base(address entry_point, int num_args) {
  Label L, E;

#ifdef _WIN64
  // Windows always allocates space for it's register args
  assert(num_args <= 4, "only register arguments supported");
  subq(rsp,  frame::arg_reg_save_area_bytes);
#endif

  // Align stack if necessary
  testl(rsp, 15);
  jcc(Assembler::zero, L);

  subq(rsp, 8);
  {
    call(RuntimeAddress(entry_point));
  }
  addq(rsp, 8);
  jmp(E);

  bind(L);
  {
    call(RuntimeAddress(entry_point));
  }

  bind(E);

#ifdef _WIN64
  // restore stack pointer
  addq(rsp, frame::arg_reg_save_area_bytes);
#endif

}

void MacroAssembler::cmp64(Register src1, AddressLiteral src2) {
  assert(!src2.is_lval(), "should use cmpptr");

  if (reachable(src2)) {
    cmpq(src1, as_Address(src2));
  } else {
    lea(rscratch1, src2);
    Assembler::cmpq(src1, Address(rscratch1, 0));
  }
}

int MacroAssembler::corrected_idivq(Register reg) {
  // Full implementation of Java ldiv and lrem; checks for special
  // case as described in JVM spec., p.243 & p.271.  The function
  // returns the (pc) offset of the idivl instruction - may be needed
  // for implicit exceptions.
  //
  //         normal case                           special case
  //
  // input : rax: dividend                         min_long
  //         reg: divisor   (may not be eax/edx)   -1
  //
  // output: rax: quotient  (= rax idiv reg)       min_long
  //         rdx: remainder (= rax irem reg)       0
  assert(reg != rax && reg != rdx, "reg cannot be rax or rdx register");
  static const int64_t min_long = 0x8000000000000000;
  Label normal_case, special_case;

  // check for special case
  cmp64(rax, ExternalAddress((address) &min_long));
  jcc(Assembler::notEqual, normal_case);
  xorl(rdx, rdx); // prepare rdx for possible special case (where
                  // remainder = 0)
  cmpq(reg, -1);
  jcc(Assembler::equal, special_case);

  // handle normal case
  bind(normal_case);
  cdqq();
  int idivq_offset = offset();
  idivq(reg);

  // normal and special case exit
  bind(special_case);

  return idivq_offset;
}

void MacroAssembler::decrementq(Register reg, int value) {
  if (value == min_jint) { subq(reg, value); return; }
  if (value <  0) { incrementq(reg, -value); return; }
  if (value == 0) {                        ; return; }
  if (value == 1 && UseIncDec) { decq(reg) ; return; }
  /* else */      { subq(reg, value)       ; return; }
}

void MacroAssembler::decrementq(Address dst, int value) {
  if (value == min_jint) { subq(dst, value); return; }
  if (value <  0) { incrementq(dst, -value); return; }
  if (value == 0) {                        ; return; }
  if (value == 1 && UseIncDec) { decq(dst) ; return; }
  /* else */      { subq(dst, value)       ; return; }
}

void MacroAssembler::incrementq(AddressLiteral dst) {
  if (reachable(dst)) {
    incrementq(as_Address(dst));
  } else {
    lea(rscratch1, dst);
    incrementq(Address(rscratch1, 0));
  }
}

void MacroAssembler::incrementq(Register reg, int value) {
  if (value == min_jint) { addq(reg, value); return; }
  if (value <  0) { decrementq(reg, -value); return; }
  if (value == 0) {                        ; return; }
  if (value == 1 && UseIncDec) { incq(reg) ; return; }
  /* else */      { addq(reg, value)       ; return; }
}

void MacroAssembler::incrementq(Address dst, int value) {
  if (value == min_jint) { addq(dst, value); return; }
  if (value <  0) { decrementq(dst, -value); return; }
  if (value == 0) {                        ; return; }
  if (value == 1 && UseIncDec) { incq(dst) ; return; }
  /* else */      { addq(dst, value)       ; return; }
}

// 32bit can do a case table jump in one instruction but we no longer allow the base
// to be installed in the Address class
void MacroAssembler::jump(ArrayAddress entry) {
  lea(rscratch1, entry.base());
  Address dispatch = entry.index();
  assert(dispatch._base == noreg, "must be");
  dispatch._base = rscratch1;
  jmp(dispatch);
}

void MacroAssembler::lcmp2int(Register x_hi, Register x_lo, Register y_hi, Register y_lo) {
  ShouldNotReachHere(); // 64bit doesn't use two regs
  cmpq(x_lo, y_lo);
}

void MacroAssembler::lea(Register dst, AddressLiteral src) {
    mov_literal64(dst, (intptr_t)src.target(), src.rspec());
}

void MacroAssembler::lea(Address dst, AddressLiteral adr) {
  mov_literal64(rscratch1, (intptr_t)adr.target(), adr.rspec());
  movptr(dst, rscratch1);
}

void MacroAssembler::leave() {
  // %%% is this really better? Why not on 32bit too?
  emit_int8((unsigned char)0xC9); // LEAVE
}

void MacroAssembler::lneg(Register hi, Register lo) {
  ShouldNotReachHere(); // 64bit doesn't use two regs
  negq(lo);
}

void MacroAssembler::movoop(Register dst, jobject obj) {
  mov_literal64(dst, (intptr_t)obj, oop_Relocation::spec_for_immediate());
}

void MacroAssembler::movoop(Address dst, jobject obj) {
  mov_literal64(rscratch1, (intptr_t)obj, oop_Relocation::spec_for_immediate());
  movq(dst, rscratch1);
}

void MacroAssembler::mov_metadata(Register dst, Metadata* obj) {
  mov_literal64(dst, (intptr_t)obj, metadata_Relocation::spec_for_immediate());
}

void MacroAssembler::mov_metadata(Address dst, Metadata* obj) {
  mov_literal64(rscratch1, (intptr_t)obj, metadata_Relocation::spec_for_immediate());
  movq(dst, rscratch1);
}

void MacroAssembler::movptr(Register dst, AddressLiteral src, Register scratch) {
  if (src.is_lval()) {
    mov_literal64(dst, (intptr_t)src.target(), src.rspec());
  } else {
    if (reachable(src)) {
      movq(dst, as_Address(src));
    } else {
      lea(scratch, src);
      movq(dst, Address(scratch, 0));
    }
  }
}

void MacroAssembler::movptr(ArrayAddress dst, Register src) {
  movq(as_Address(dst), src);
}

void MacroAssembler::movptr(Register dst, ArrayAddress src) {
  movq(dst, as_Address(src));
}

// src should NEVER be a real pointer. Use AddressLiteral for true pointers
void MacroAssembler::movptr(Address dst, intptr_t src) {
  mov64(rscratch1, src);
  movq(dst, rscratch1);
}

// These are mostly for initializing NULL
void MacroAssembler::movptr(Address dst, int32_t src) {
  movslq(dst, src);
}

void MacroAssembler::movptr(Register dst, int32_t src) {
  mov64(dst, (intptr_t)src);
}

void MacroAssembler::pushoop(jobject obj) {
  movoop(rscratch1, obj);
  push(rscratch1);
}

void MacroAssembler::pushklass(Metadata* obj) {
  mov_metadata(rscratch1, obj);
  push(rscratch1);
}

void MacroAssembler::pushptr(AddressLiteral src) {
  lea(rscratch1, src);
  if (src.is_lval()) {
    push(rscratch1);
  } else {
    pushq(Address(rscratch1, 0));
  }
}

void MacroAssembler::reset_last_Java_frame(bool clear_fp) {
  // we must set sp to zero to clear frame
  movptr(Address(r15_thread, JavaThread::last_Java_sp_offset()), NULL_WORD);
  // must clear fp, so that compiled frames are not confused; it is
  // possible that we need it only for debugging
  if (clear_fp) {
    movptr(Address(r15_thread, JavaThread::last_Java_fp_offset()), NULL_WORD);
  }

  // Always clear the pc because it could have been set by make_walkable()
  movptr(Address(r15_thread, JavaThread::last_Java_pc_offset()), NULL_WORD);
  vzeroupper();
}

void MacroAssembler::set_last_Java_frame(Register last_java_sp,
                                         Register last_java_fp,
                                         address  last_java_pc) {
  vzeroupper();
  // determine last_java_sp register
  if (!last_java_sp->is_valid()) {
    last_java_sp = rsp;
  }

  // last_java_fp is optional
  if (last_java_fp->is_valid()) {
    movptr(Address(r15_thread, JavaThread::last_Java_fp_offset()),
           last_java_fp);
  }

  // last_java_pc is optional
  if (last_java_pc != NULL) {
    Address java_pc(r15_thread,
                    JavaThread::frame_anchor_offset() + JavaFrameAnchor::last_Java_pc_offset());
    lea(rscratch1, InternalAddress(last_java_pc));
    movptr(java_pc, rscratch1);
  }

  movptr(Address(r15_thread, JavaThread::last_Java_sp_offset()), last_java_sp);
}

static void pass_arg0(MacroAssembler* masm, Register arg) {
  if (c_rarg0 != arg ) {
    masm->mov(c_rarg0, arg);
  }
}

static void pass_arg1(MacroAssembler* masm, Register arg) {
  if (c_rarg1 != arg ) {
    masm->mov(c_rarg1, arg);
  }
}

static void pass_arg2(MacroAssembler* masm, Register arg) {
  if (c_rarg2 != arg ) {
    masm->mov(c_rarg2, arg);
  }
}

static void pass_arg3(MacroAssembler* masm, Register arg) {
  if (c_rarg3 != arg ) {
    masm->mov(c_rarg3, arg);
  }
}

void MacroAssembler::stop(const char* msg) {
  if (ShowMessageBoxOnError) {
    address rip = pc();
    pusha(); // get regs on stack
    lea(c_rarg1, InternalAddress(rip));
    movq(c_rarg2, rsp); // pass pointer to regs array
  }
  lea(c_rarg0, ExternalAddress((address) msg));
  andq(rsp, -16); // align stack as required by ABI
  call(RuntimeAddress(CAST_FROM_FN_PTR(address, MacroAssembler::debug64)));
  hlt();
}

void MacroAssembler::warn(const char* msg) {
  push(rbp);
  movq(rbp, rsp);
  andq(rsp, -16);     // align stack as required by push_CPU_state and call
  push_CPU_state();   // keeps alignment at 16 bytes
  lea(c_rarg0, ExternalAddress((address) msg));
  lea(rax, ExternalAddress(CAST_FROM_FN_PTR(address, warning)));
  call(rax);
  pop_CPU_state();
  mov(rsp, rbp);
  pop(rbp);
}

void MacroAssembler::print_state() {
  address rip = pc();
  pusha();            // get regs on stack
  push(rbp);
  movq(rbp, rsp);
  andq(rsp, -16);     // align stack as required by push_CPU_state and call
  push_CPU_state();   // keeps alignment at 16 bytes

  lea(c_rarg0, InternalAddress(rip));
  lea(c_rarg1, Address(rbp, wordSize)); // pass pointer to regs array
  call_VM_leaf(CAST_FROM_FN_PTR(address, MacroAssembler::print_state64), c_rarg0, c_rarg1);

  pop_CPU_state();
  mov(rsp, rbp);
  pop(rbp);
  popa();
}

#ifndef PRODUCT
extern "C" void findpc(intptr_t x);
#endif

void MacroAssembler::debug64(char* msg, int64_t pc, int64_t regs[]) {
  // In order to get locks to work, we need to fake a in_VM state
  if (ShowMessageBoxOnError) {
    JavaThread* thread = JavaThread::current();
    JavaThreadState saved_state = thread->thread_state();
    thread->set_thread_state(_thread_in_vm);
#ifndef PRODUCT
    if (CountBytecodes || TraceBytecodes || StopInterpreterAt) {
      ttyLocker ttyl;
      BytecodeCounter::print();
    }
#endif
    // To see where a verify_oop failed, get $ebx+40/X for this frame.
    // XXX correct this offset for amd64
    // This is the value of eip which points to where verify_oop will return.
    if (os::message_box(msg, "Execution stopped, print registers?")) {
      print_state64(pc, regs);
      BREAKPOINT;
      assert(false, "start up GDB");
    }
    ThreadStateTransition::transition(thread, _thread_in_vm, saved_state);
  } else {
    ttyLocker ttyl;
    ::tty->print_cr("=============== DEBUG MESSAGE: %s ================\n",
                    msg);
    assert(false, "DEBUG MESSAGE: %s", msg);
  }
}

void MacroAssembler::print_state64(int64_t pc, int64_t regs[]) {
  ttyLocker ttyl;
  FlagSetting fs(Debugging, true);
  tty->print_cr("rip = 0x%016lx", (intptr_t)pc);
#ifndef PRODUCT
  tty->cr();
  findpc(pc);
  tty->cr();
#endif
#define PRINT_REG(rax, value) \
  { tty->print("%s = ", #rax); os::print_location(tty, value); }
  PRINT_REG(rax, regs[15]);
  PRINT_REG(rbx, regs[12]);
  PRINT_REG(rcx, regs[14]);
  PRINT_REG(rdx, regs[13]);
  PRINT_REG(rdi, regs[8]);
  PRINT_REG(rsi, regs[9]);
  PRINT_REG(rbp, regs[10]);
  PRINT_REG(rsp, regs[11]);
  PRINT_REG(r8 , regs[7]);
  PRINT_REG(r9 , regs[6]);
  PRINT_REG(r10, regs[5]);
  PRINT_REG(r11, regs[4]);
  PRINT_REG(r12, regs[3]);
  PRINT_REG(r13, regs[2]);
  PRINT_REG(r14, regs[1]);
  PRINT_REG(r15, regs[0]);
#undef PRINT_REG
  // Print some words near top of staack.
  int64_t* rsp = (int64_t*) regs[11];
  int64_t* dump_sp = rsp;
  for (int col1 = 0; col1 < 8; col1++) {
    tty->print("(rsp+0x%03x) 0x%016lx: ", (int)((intptr_t)dump_sp - (intptr_t)rsp), (intptr_t)dump_sp);
    os::print_location(tty, *dump_sp++);
  }
  for (int row = 0; row < 25; row++) {
    tty->print("(rsp+0x%03x) 0x%016lx: ", (int)((intptr_t)dump_sp - (intptr_t)rsp), (intptr_t)dump_sp);
    for (int col = 0; col < 4; col++) {
      tty->print(" 0x%016lx", (intptr_t)*dump_sp++);
    }
    tty->cr();
  }
  // Print some instructions around pc:
  Disassembler::decode((address)pc-64, (address)pc);
  tty->print_cr("--------");
  Disassembler::decode((address)pc, (address)pc+32);
}

#endif // _LP64

// Now versions that are common to 32/64 bit

void MacroAssembler::addptr(Register dst, int32_t imm32) {
  LP64_ONLY(addq(dst, imm32)) NOT_LP64(addl(dst, imm32));
}

void MacroAssembler::addptr(Register dst, Register src) {
  LP64_ONLY(addq(dst, src)) NOT_LP64(addl(dst, src));
}

void MacroAssembler::addptr(Address dst, Register src) {
  LP64_ONLY(addq(dst, src)) NOT_LP64(addl(dst, src));
}

void MacroAssembler::addsd(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    Assembler::addsd(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    Assembler::addsd(dst, Address(rscratch1, 0));
  }
}

void MacroAssembler::addss(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    addss(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    addss(dst, Address(rscratch1, 0));
  }
}

void MacroAssembler::addpd(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    Assembler::addpd(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    Assembler::addpd(dst, Address(rscratch1, 0));
  }
}

void MacroAssembler::align(int modulus) {
  align(modulus, offset());
}

void MacroAssembler::align(int modulus, int target) {
  if (target % modulus != 0) {
    nop(modulus - (target % modulus));
  }
}

void MacroAssembler::andpd(XMMRegister dst, AddressLiteral src, Register scratch_reg) {
  // Used in sign-masking with aligned address.
  assert((UseAVX > 0) || (((intptr_t)src.target() & 15) == 0), "SSE mode requires address alignment 16 bytes");
  if (reachable(src)) {
    Assembler::andpd(dst, as_Address(src));
  } else {
    lea(scratch_reg, src);
    Assembler::andpd(dst, Address(scratch_reg, 0));
  }
}

void MacroAssembler::andps(XMMRegister dst, AddressLiteral src, Register scratch_reg) {
  // Used in sign-masking with aligned address.
  assert((UseAVX > 0) || (((intptr_t)src.target() & 15) == 0), "SSE mode requires address alignment 16 bytes");
  if (reachable(src)) {
    Assembler::andps(dst, as_Address(src));
  } else {
    lea(scratch_reg, src);
    Assembler::andps(dst, Address(scratch_reg, 0));
  }
}

void MacroAssembler::andptr(Register dst, int32_t imm32) {
  LP64_ONLY(andq(dst, imm32)) NOT_LP64(andl(dst, imm32));
}

void MacroAssembler::atomic_incl(Address counter_addr) {
  if (os::is_MP())
    lock();
  incrementl(counter_addr);
}

void MacroAssembler::atomic_incl(AddressLiteral counter_addr, Register scr) {
  if (reachable(counter_addr)) {
    atomic_incl(as_Address(counter_addr));
  } else {
    lea(scr, counter_addr);
    atomic_incl(Address(scr, 0));
  }
}

#ifdef _LP64
void MacroAssembler::atomic_incq(Address counter_addr) {
  if (os::is_MP())
    lock();
  incrementq(counter_addr);
}

void MacroAssembler::atomic_incq(AddressLiteral counter_addr, Register scr) {
  if (reachable(counter_addr)) {
    atomic_incq(as_Address(counter_addr));
  } else {
    lea(scr, counter_addr);
    atomic_incq(Address(scr, 0));
  }
}
#endif

// Writes to stack successive pages until offset reached to check for
// stack overflow + shadow pages.  This clobbers tmp.
void MacroAssembler::bang_stack_size(Register size, Register tmp) {
  movptr(tmp, rsp);
  // Bang stack for total size given plus shadow page size.
  // Bang one page at a time because large size can bang beyond yellow and
  // red zones.
  Label loop;
  bind(loop);
  movl(Address(tmp, (-os::vm_page_size())), size );
  subptr(tmp, os::vm_page_size());
  subl(size, os::vm_page_size());
  jcc(Assembler::greater, loop);

  // Bang down shadow pages too.
  // At this point, (tmp-0) is the last address touched, so don't
  // touch it again.  (It was touched as (tmp-pagesize) but then tmp
  // was post-decremented.)  Skip this address by starting at i=1, and
  // touch a few more pages below.  N.B.  It is important to touch all
  // the way down including all pages in the shadow zone.
  for (int i = 1; i < ((int)JavaThread::stack_shadow_zone_size() / os::vm_page_size()); i++) {
    // this could be any sized move but this is can be a debugging crumb
    // so the bigger the better.
    movptr(Address(tmp, (-i*os::vm_page_size())), size );
  }
}

void MacroAssembler::reserved_stack_check() {
    // testing if reserved zone needs to be enabled
    Label no_reserved_zone_enabling;
    Register thread = NOT_LP64(rsi) LP64_ONLY(r15_thread);
    NOT_LP64(get_thread(rsi);)

    cmpptr(rsp, Address(thread, JavaThread::reserved_stack_activation_offset()));
    jcc(Assembler::below, no_reserved_zone_enabling);

    call_VM_leaf(CAST_FROM_FN_PTR(address, SharedRuntime::enable_stack_reserved_zone), thread);
    jump(RuntimeAddress(StubRoutines::throw_delayed_StackOverflowError_entry()));
    should_not_reach_here();

    bind(no_reserved_zone_enabling);
}

int MacroAssembler::biased_locking_enter(Register lock_reg,
                                         Register obj_reg,
                                         Register swap_reg,
                                         Register tmp_reg,
                                         bool swap_reg_contains_mark,
                                         Label& done,
                                         Label* slow_case,
                                         BiasedLockingCounters* counters) {
  assert(UseBiasedLocking, "why call this otherwise?");
  assert(swap_reg == rax, "swap_reg must be rax for cmpxchgq");
  assert(tmp_reg != noreg, "tmp_reg must be supplied");
  assert_different_registers(lock_reg, obj_reg, swap_reg, tmp_reg);
  assert(markOopDesc::age_shift == markOopDesc::lock_bits + markOopDesc::biased_lock_bits, "biased locking makes assumptions about bit layout");
  Address mark_addr      (obj_reg, oopDesc::mark_offset_in_bytes());
  NOT_LP64( Address saved_mark_addr(lock_reg, 0); )

  if (PrintBiasedLockingStatistics && counters == NULL) {
    counters = BiasedLocking::counters();
  }
  // Biased locking
  // See whether the lock is currently biased toward our thread and
  // whether the epoch is still valid
  // Note that the runtime guarantees sufficient alignment of JavaThread
  // pointers to allow age to be placed into low bits
  // First check to see whether biasing is even enabled for this object
  Label cas_label;
  int null_check_offset = -1;
  if (!swap_reg_contains_mark) {
    null_check_offset = offset();
    movptr(swap_reg, mark_addr);
  }
  movptr(tmp_reg, swap_reg);
  andptr(tmp_reg, markOopDesc::biased_lock_mask_in_place);
  cmpptr(tmp_reg, markOopDesc::biased_lock_pattern);
  jcc(Assembler::notEqual, cas_label);
  // The bias pattern is present in the object's header. Need to check
  // whether the bias owner and the epoch are both still current.
#ifndef _LP64
  // Note that because there is no current thread register on x86_32 we
  // need to store off the mark word we read out of the object to
  // avoid reloading it and needing to recheck invariants below. This
  // store is unfortunate but it makes the overall code shorter and
  // simpler.
  movptr(saved_mark_addr, swap_reg);
#endif
  if (swap_reg_contains_mark) {
    null_check_offset = offset();
  }
  load_prototype_header(tmp_reg, obj_reg);
#ifdef _LP64
  orptr(tmp_reg, r15_thread);
  xorptr(tmp_reg, swap_reg);
  Register header_reg = tmp_reg;
#else
  xorptr(tmp_reg, swap_reg);
  get_thread(swap_reg);
  xorptr(swap_reg, tmp_reg);
  Register header_reg = swap_reg;
#endif
  andptr(header_reg, ~((int) markOopDesc::age_mask_in_place));
  if (counters != NULL) {
    cond_inc32(Assembler::zero,
               ExternalAddress((address) counters->biased_lock_entry_count_addr()));
  }
  jcc(Assembler::equal, done);

  Label try_revoke_bias;
  Label try_rebias;

  // At this point we know that the header has the bias pattern and
  // that we are not the bias owner in the current epoch. We need to
  // figure out more details about the state of the header in order to
  // know what operations can be legally performed on the object's
  // header.

  // If the low three bits in the xor result aren't clear, that means
  // the prototype header is no longer biased and we have to revoke
  // the bias on this object.
  testptr(header_reg, markOopDesc::biased_lock_mask_in_place);
  jcc(Assembler::notZero, try_revoke_bias);

  // Biasing is still enabled for this data type. See whether the
  // epoch of the current bias is still valid, meaning that the epoch
  // bits of the mark word are equal to the epoch bits of the
  // prototype header. (Note that the prototype header's epoch bits
  // only change at a safepoint.) If not, attempt to rebias the object
  // toward the current thread. Note that we must be absolutely sure
  // that the current epoch is invalid in order to do this because
  // otherwise the manipulations it performs on the mark word are
  // illegal.
  testptr(header_reg, markOopDesc::epoch_mask_in_place);
  jccb(Assembler::notZero, try_rebias);

  // The epoch of the current bias is still valid but we know nothing
  // about the owner; it might be set or it might be clear. Try to
  // acquire the bias of the object using an atomic operation. If this
  // fails we will go in to the runtime to revoke the object's bias.
  // Note that we first construct the presumed unbiased header so we
  // don't accidentally blow away another thread's valid bias.
  NOT_LP64( movptr(swap_reg, saved_mark_addr); )
  andptr(swap_reg,
         markOopDesc::biased_lock_mask_in_place | markOopDesc::age_mask_in_place | markOopDesc::epoch_mask_in_place);
#ifdef _LP64
  movptr(tmp_reg, swap_reg);
  orptr(tmp_reg, r15_thread);
#else
  get_thread(tmp_reg);
  orptr(tmp_reg, swap_reg);
#endif
  if (os::is_MP()) {
    lock();
  }
  cmpxchgptr(tmp_reg, mark_addr); // compare tmp_reg and swap_reg
  // If the biasing toward our thread failed, this means that
  // another thread succeeded in biasing it toward itself and we
  // need to revoke that bias. The revocation will occur in the
  // interpreter runtime in the slow case.
  if (counters != NULL) {
    cond_inc32(Assembler::zero,
               ExternalAddress((address) counters->anonymously_biased_lock_entry_count_addr()));
  }
  if (slow_case != NULL) {
    jcc(Assembler::notZero, *slow_case);
  }
  jmp(done);

  bind(try_rebias);
  // At this point we know the epoch has expired, meaning that the
  // current "bias owner", if any, is actually invalid. Under these
  // circumstances _only_, we are allowed to use the current header's
  // value as the comparison value when doing the cas to acquire the
  // bias in the current epoch. In other words, we allow transfer of
  // the bias from one thread to another directly in this situation.
  //
  // FIXME: due to a lack of registers we currently blow away the age
  // bits in this situation. Should attempt to preserve them.
  load_prototype_header(tmp_reg, obj_reg);
#ifdef _LP64
  orptr(tmp_reg, r15_thread);
#else
  get_thread(swap_reg);
  orptr(tmp_reg, swap_reg);
  movptr(swap_reg, saved_mark_addr);
#endif
  if (os::is_MP()) {
    lock();
  }
  cmpxchgptr(tmp_reg, mark_addr); // compare tmp_reg and swap_reg
  // If the biasing toward our thread failed, then another thread
  // succeeded in biasing it toward itself and we need to revoke that
  // bias. The revocation will occur in the runtime in the slow case.
  if (counters != NULL) {
    cond_inc32(Assembler::zero,
               ExternalAddress((address) counters->rebiased_lock_entry_count_addr()));
  }
  if (slow_case != NULL) {
    jcc(Assembler::notZero, *slow_case);
  }
  jmp(done);

  bind(try_revoke_bias);
  // The prototype mark in the klass doesn't have the bias bit set any
  // more, indicating that objects of this data type are not supposed
  // to be biased any more. We are going to try to reset the mark of
  // this object to the prototype value and fall through to the
  // CAS-based locking scheme. Note that if our CAS fails, it means
  // that another thread raced us for the privilege of revoking the
  // bias of this particular object, so it's okay to continue in the
  // normal locking code.
  //
  // FIXME: due to a lack of registers we currently blow away the age
  // bits in this situation. Should attempt to preserve them.
  NOT_LP64( movptr(swap_reg, saved_mark_addr); )
  load_prototype_header(tmp_reg, obj_reg);
  if (os::is_MP()) {
    lock();
  }
  cmpxchgptr(tmp_reg, mark_addr); // compare tmp_reg and swap_reg
  // Fall through to the normal CAS-based lock, because no matter what
  // the result of the above CAS, some thread must have succeeded in
  // removing the bias bit from the object's header.
  if (counters != NULL) {
    cond_inc32(Assembler::zero,
               ExternalAddress((address) counters->revoked_lock_entry_count_addr()));
  }

  bind(cas_label);

  return null_check_offset;
}

void MacroAssembler::biased_locking_exit(Register obj_reg, Register temp_reg, Label& done) {
  assert(UseBiasedLocking, "why call this otherwise?");

  // Check for biased locking unlock case, which is a no-op
  // Note: we do not have to check the thread ID for two reasons.
  // First, the interpreter checks for IllegalMonitorStateException at
  // a higher level. Second, if the bias was revoked while we held the
  // lock, the object could not be rebiased toward another thread, so
  // the bias bit would be clear.
  movptr(temp_reg, Address(obj_reg, oopDesc::mark_offset_in_bytes()));
  andptr(temp_reg, markOopDesc::biased_lock_mask_in_place);
  cmpptr(temp_reg, markOopDesc::biased_lock_pattern);
  jcc(Assembler::equal, done);
}

#ifdef COMPILER2

#if INCLUDE_RTM_OPT

// Update rtm_counters based on abort status
// input: abort_status
//        rtm_counters (RTMLockingCounters*)
// flags are killed
void MacroAssembler::rtm_counters_update(Register abort_status, Register rtm_counters) {

  atomic_incptr(Address(rtm_counters, RTMLockingCounters::abort_count_offset()));
  if (PrintPreciseRTMLockingStatistics) {
    for (int i = 0; i < RTMLockingCounters::ABORT_STATUS_LIMIT; i++) {
      Label check_abort;
      testl(abort_status, (1<<i));
      jccb(Assembler::equal, check_abort);
      atomic_incptr(Address(rtm_counters, RTMLockingCounters::abortX_count_offset() + (i * sizeof(uintx))));
      bind(check_abort);
    }
  }
}

// Branch if (random & (count-1) != 0), count is 2^n
// tmp, scr and flags are killed
void MacroAssembler::branch_on_random_using_rdtsc(Register tmp, Register scr, int count, Label& brLabel) {
  assert(tmp == rax, "");
  assert(scr == rdx, "");
  rdtsc(); // modifies EDX:EAX
  andptr(tmp, count-1);
  jccb(Assembler::notZero, brLabel);
}

// Perform abort ratio calculation, set no_rtm bit if high ratio
// input:  rtm_counters_Reg (RTMLockingCounters* address)
// tmpReg, rtm_counters_Reg and flags are killed
void MacroAssembler::rtm_abort_ratio_calculation(Register tmpReg,
                                                 Register rtm_counters_Reg,
                                                 RTMLockingCounters* rtm_counters,
                                                 Metadata* method_data) {
  Label L_done, L_check_always_rtm1, L_check_always_rtm2;

  if (RTMLockingCalculationDelay > 0) {
    // Delay calculation
    movptr(tmpReg, ExternalAddress((address) RTMLockingCounters::rtm_calculation_flag_addr()), tmpReg);
    testptr(tmpReg, tmpReg);
    jccb(Assembler::equal, L_done);
  }
  // Abort ratio calculation only if abort_count > RTMAbortThreshold
  //   Aborted transactions = abort_count * 100
  //   All transactions = total_count *  RTMTotalCountIncrRate
  //   Set no_rtm bit if (Aborted transactions >= All transactions * RTMAbortRatio)

  movptr(tmpReg, Address(rtm_counters_Reg, RTMLockingCounters::abort_count_offset()));
  cmpptr(tmpReg, RTMAbortThreshold);
  jccb(Assembler::below, L_check_always_rtm2);
  imulptr(tmpReg, tmpReg, 100);

  Register scrReg = rtm_counters_Reg;
  movptr(scrReg, Address(rtm_counters_Reg, RTMLockingCounters::total_count_offset()));
  imulptr(scrReg, scrReg, RTMTotalCountIncrRate);
  imulptr(scrReg, scrReg, RTMAbortRatio);
  cmpptr(tmpReg, scrReg);
  jccb(Assembler::below, L_check_always_rtm1);
  if (method_data != NULL) {
    // set rtm_state to "no rtm" in MDO
    mov_metadata(tmpReg, method_data);
    if (os::is_MP()) {
      lock();
    }
    orl(Address(tmpReg, MethodData::rtm_state_offset_in_bytes()), NoRTM);
  }
  jmpb(L_done);
  bind(L_check_always_rtm1);
  // Reload RTMLockingCounters* address
  lea(rtm_counters_Reg, ExternalAddress((address)rtm_counters));
  bind(L_check_always_rtm2);
  movptr(tmpReg, Address(rtm_counters_Reg, RTMLockingCounters::total_count_offset()));
  cmpptr(tmpReg, RTMLockingThreshold / RTMTotalCountIncrRate);
  jccb(Assembler::below, L_done);
  if (method_data != NULL) {
    // set rtm_state to "always rtm" in MDO
    mov_metadata(tmpReg, method_data);
    if (os::is_MP()) {
      lock();
    }
    orl(Address(tmpReg, MethodData::rtm_state_offset_in_bytes()), UseRTM);
  }
  bind(L_done);
}

// Update counters and perform abort ratio calculation
// input:  abort_status_Reg
// rtm_counters_Reg, flags are killed
void MacroAssembler::rtm_profiling(Register abort_status_Reg,
                                   Register rtm_counters_Reg,
                                   RTMLockingCounters* rtm_counters,
                                   Metadata* method_data,
                                   bool profile_rtm) {

  assert(rtm_counters != NULL, "should not be NULL when profiling RTM");
  // update rtm counters based on rax value at abort
  // reads abort_status_Reg, updates flags
  lea(rtm_counters_Reg, ExternalAddress((address)rtm_counters));
  rtm_counters_update(abort_status_Reg, rtm_counters_Reg);
  if (profile_rtm) {
    // Save abort status because abort_status_Reg is used by following code.
    if (RTMRetryCount > 0) {
      push(abort_status_Reg);
    }
    assert(rtm_counters != NULL, "should not be NULL when profiling RTM");
    rtm_abort_ratio_calculation(abort_status_Reg, rtm_counters_Reg, rtm_counters, method_data);
    // restore abort status
    if (RTMRetryCount > 0) {
      pop(abort_status_Reg);
    }
  }
}

// Retry on abort if abort's status is 0x6: can retry (0x2) | memory conflict (0x4)
// inputs: retry_count_Reg
//       : abort_status_Reg
// output: retry_count_Reg decremented by 1
// flags are killed
void MacroAssembler::rtm_retry_lock_on_abort(Register retry_count_Reg, Register abort_status_Reg, Label& retryLabel) {
  Label doneRetry;
  assert(abort_status_Reg == rax, "");
  // The abort reason bits are in eax (see all states in rtmLocking.hpp)
  // 0x6 = conflict on which we can retry (0x2) | memory conflict (0x4)
  // if reason is in 0x6 and retry count != 0 then retry
  andptr(abort_status_Reg, 0x6);
  jccb(Assembler::zero, doneRetry);
  testl(retry_count_Reg, retry_count_Reg);
  jccb(Assembler::zero, doneRetry);
  pause();
  decrementl(retry_count_Reg);
  jmp(retryLabel);
  bind(doneRetry);
}

// Spin and retry if lock is busy,
// inputs: box_Reg (monitor address)
//       : retry_count_Reg
// output: retry_count_Reg decremented by 1
//       : clear z flag if retry count exceeded
// tmp_Reg, scr_Reg, flags are killed
void MacroAssembler::rtm_retry_lock_on_busy(Register retry_count_Reg, Register box_Reg,
                                            Register tmp_Reg, Register scr_Reg, Label& retryLabel) {
  Label SpinLoop, SpinExit, doneRetry;
  int owner_offset = OM_OFFSET_NO_MONITOR_VALUE_TAG(owner);

  testl(retry_count_Reg, retry_count_Reg);
  jccb(Assembler::zero, doneRetry);
  decrementl(retry_count_Reg);
  movptr(scr_Reg, RTMSpinLoopCount);

  bind(SpinLoop);
  pause();
  decrementl(scr_Reg);
  jccb(Assembler::lessEqual, SpinExit);
  movptr(tmp_Reg, Address(box_Reg, owner_offset));
  testptr(tmp_Reg, tmp_Reg);
  jccb(Assembler::notZero, SpinLoop);

  bind(SpinExit);
  jmp(retryLabel);
  bind(doneRetry);
  incrementl(retry_count_Reg); // clear z flag
}

// Use RTM for normal stack locks
// Input: objReg (object to lock)
void MacroAssembler::rtm_stack_locking(Register objReg, Register tmpReg, Register scrReg,
                                       Register retry_on_abort_count_Reg,
                                       RTMLockingCounters* stack_rtm_counters,
                                       Metadata* method_data, bool profile_rtm,
                                       Label& DONE_LABEL, Label& IsInflated) {
  assert(UseRTMForStackLocks, "why call this otherwise?");
  assert(!UseBiasedLocking, "Biased locking is not supported with RTM locking");
  assert(tmpReg == rax, "");
  assert(scrReg == rdx, "");
  Label L_rtm_retry, L_decrement_retry, L_on_abort;

  if (RTMRetryCount > 0) {
    movl(retry_on_abort_count_Reg, RTMRetryCount); // Retry on abort
    bind(L_rtm_retry);
  }
  movptr(tmpReg, Address(objReg, oopDesc::mark_offset_in_bytes()));
  testptr(tmpReg, markOopDesc::monitor_value);  // inflated vs stack-locked|neutral|biased
  jcc(Assembler::notZero, IsInflated);

  if (PrintPreciseRTMLockingStatistics || profile_rtm) {
    Label L_noincrement;
    if (RTMTotalCountIncrRate > 1) {
      // tmpReg, scrReg and flags are killed
      branch_on_random_using_rdtsc(tmpReg, scrReg, RTMTotalCountIncrRate, L_noincrement);
    }
    assert(stack_rtm_counters != NULL, "should not be NULL when profiling RTM");
    atomic_incptr(ExternalAddress((address)stack_rtm_counters->total_count_addr()), scrReg);
    bind(L_noincrement);
  }
  xbegin(L_on_abort);
  movptr(tmpReg, Address(objReg, oopDesc::mark_offset_in_bytes()));       // fetch markword
  andptr(tmpReg, markOopDesc::biased_lock_mask_in_place); // look at 3 lock bits
  cmpptr(tmpReg, markOopDesc::unlocked_value);            // bits = 001 unlocked
  jcc(Assembler::equal, DONE_LABEL);        // all done if unlocked

  Register abort_status_Reg = tmpReg; // status of abort is stored in RAX
  if (UseRTMXendForLockBusy) {
    xend();
    movptr(abort_status_Reg, 0x2);   // Set the abort status to 2 (so we can retry)
    jmp(L_decrement_retry);
  }
  else {
    xabort(0);
  }
  bind(L_on_abort);
  if (PrintPreciseRTMLockingStatistics || profile_rtm) {
    rtm_profiling(abort_status_Reg, scrReg, stack_rtm_counters, method_data, profile_rtm);
  }
  bind(L_decrement_retry);
  if (RTMRetryCount > 0) {
    // retry on lock abort if abort status is 'can retry' (0x2) or 'memory conflict' (0x4)
    rtm_retry_lock_on_abort(retry_on_abort_count_Reg, abort_status_Reg, L_rtm_retry);
  }
}

// Use RTM for inflating locks
// inputs: objReg (object to lock)
//         boxReg (on-stack box address (displaced header location) - KILLED)
//         tmpReg (ObjectMonitor address + markOopDesc::monitor_value)
void MacroAssembler::rtm_inflated_locking(Register objReg, Register boxReg, Register tmpReg,
                                          Register scrReg, Register retry_on_busy_count_Reg,
                                          Register retry_on_abort_count_Reg,
                                          RTMLockingCounters* rtm_counters,
                                          Metadata* method_data, bool profile_rtm,
                                          Label& DONE_LABEL) {
  assert(UseRTMLocking, "why call this otherwise?");
  assert(tmpReg == rax, "");
  assert(scrReg == rdx, "");
  Label L_rtm_retry, L_decrement_retry, L_on_abort;
  int owner_offset = OM_OFFSET_NO_MONITOR_VALUE_TAG(owner);

  // Without cast to int32_t a movptr will destroy r10 which is typically obj
  movptr(Address(boxReg, 0), (int32_t)intptr_t(markOopDesc::unused_mark()));
  movptr(boxReg, tmpReg); // Save ObjectMonitor address

  if (RTMRetryCount > 0) {
    movl(retry_on_busy_count_Reg, RTMRetryCount);  // Retry on lock busy
    movl(retry_on_abort_count_Reg, RTMRetryCount); // Retry on abort
    bind(L_rtm_retry);
  }
  if (PrintPreciseRTMLockingStatistics || profile_rtm) {
    Label L_noincrement;
    if (RTMTotalCountIncrRate > 1) {
      // tmpReg, scrReg and flags are killed
      branch_on_random_using_rdtsc(tmpReg, scrReg, RTMTotalCountIncrRate, L_noincrement);
    }
    assert(rtm_counters != NULL, "should not be NULL when profiling RTM");
    atomic_incptr(ExternalAddress((address)rtm_counters->total_count_addr()), scrReg);
    bind(L_noincrement);
  }
  xbegin(L_on_abort);
  movptr(tmpReg, Address(objReg, oopDesc::mark_offset_in_bytes()));
  movptr(tmpReg, Address(tmpReg, owner_offset));
  testptr(tmpReg, tmpReg);
  jcc(Assembler::zero, DONE_LABEL);
  if (UseRTMXendForLockBusy) {
    xend();
    jmp(L_decrement_retry);
  }
  else {
    xabort(0);
  }
  bind(L_on_abort);
  Register abort_status_Reg = tmpReg; // status of abort is stored in RAX
  if (PrintPreciseRTMLockingStatistics || profile_rtm) {
    rtm_profiling(abort_status_Reg, scrReg, rtm_counters, method_data, profile_rtm);
  }
  if (RTMRetryCount > 0) {
    // retry on lock abort if abort status is 'can retry' (0x2) or 'memory conflict' (0x4)
    rtm_retry_lock_on_abort(retry_on_abort_count_Reg, abort_status_Reg, L_rtm_retry);
  }

  movptr(tmpReg, Address(boxReg, owner_offset)) ;
  testptr(tmpReg, tmpReg) ;
  jccb(Assembler::notZero, L_decrement_retry) ;

  // Appears unlocked - try to swing _owner from null to non-null.
  // Invariant: tmpReg == 0.  tmpReg is EAX which is the implicit cmpxchg comparand.
#ifdef _LP64
  Register threadReg = r15_thread;
#else
  get_thread(scrReg);
  Register threadReg = scrReg;
#endif
  if (os::is_MP()) {
    lock();
  }
  cmpxchgptr(threadReg, Address(boxReg, owner_offset)); // Updates tmpReg

  if (RTMRetryCount > 0) {
    // success done else retry
    jccb(Assembler::equal, DONE_LABEL) ;
    bind(L_decrement_retry);
    // Spin and retry if lock is busy.
    rtm_retry_lock_on_busy(retry_on_busy_count_Reg, boxReg, tmpReg, scrReg, L_rtm_retry);
  }
  else {
    bind(L_decrement_retry);
  }
}

#endif //  INCLUDE_RTM_OPT

// Fast_Lock and Fast_Unlock used by C2

// Because the transitions from emitted code to the runtime
// monitorenter/exit helper stubs are so slow it's critical that
// we inline both the stack-locking fast-path and the inflated fast path.
//
// See also: cmpFastLock and cmpFastUnlock.
//
// What follows is a specialized inline transliteration of the code
// in slow_enter() and slow_exit().  If we're concerned about I$ bloat
// another option would be to emit TrySlowEnter and TrySlowExit methods
// at startup-time.  These methods would accept arguments as
// (rax,=Obj, rbx=Self, rcx=box, rdx=Scratch) and return success-failure
// indications in the icc.ZFlag.  Fast_Lock and Fast_Unlock would simply
// marshal the arguments and emit calls to TrySlowEnter and TrySlowExit.
// In practice, however, the # of lock sites is bounded and is usually small.
// Besides the call overhead, TrySlowEnter and TrySlowExit might suffer
// if the processor uses simple bimodal branch predictors keyed by EIP
// Since the helper routines would be called from multiple synchronization
// sites.
//
// An even better approach would be write "MonitorEnter()" and "MonitorExit()"
// in java - using j.u.c and unsafe - and just bind the lock and unlock sites
// to those specialized methods.  That'd give us a mostly platform-independent
// implementation that the JITs could optimize and inline at their pleasure.
// Done correctly, the only time we'd need to cross to native could would be
// to park() or unpark() threads.  We'd also need a few more unsafe operators
// to (a) prevent compiler-JIT reordering of non-volatile accesses, and
// (b) explicit barriers or fence operations.
//
// TODO:
//
// *  Arrange for C2 to pass "Self" into Fast_Lock and Fast_Unlock in one of the registers (scr).
//    This avoids manifesting the Self pointer in the Fast_Lock and Fast_Unlock terminals.
//    Given TLAB allocation, Self is usually manifested in a register, so passing it into
//    the lock operators would typically be faster than reifying Self.
//
// *  Ideally I'd define the primitives as:
//       fast_lock   (nax Obj, nax box, EAX tmp, nax scr) where box, tmp and scr are KILLED.
//       fast_unlock (nax Obj, EAX box, nax tmp) where box and tmp are KILLED
//    Unfortunately ADLC bugs prevent us from expressing the ideal form.
//    Instead, we're stuck with a rather awkward and brittle register assignments below.
//    Furthermore the register assignments are overconstrained, possibly resulting in
//    sub-optimal code near the synchronization site.
//
// *  Eliminate the sp-proximity tests and just use "== Self" tests instead.
//    Alternately, use a better sp-proximity test.
//
// *  Currently ObjectMonitor._Owner can hold either an sp value or a (THREAD *) value.
//    Either one is sufficient to uniquely identify a thread.
//    TODO: eliminate use of sp in _owner and use get_thread(tr) instead.
//
// *  Intrinsify notify() and notifyAll() for the common cases where the
//    object is locked by the calling thread but the waitlist is empty.
//    avoid the expensive JNI call to JVM_Notify() and JVM_NotifyAll().
//
// *  use jccb and jmpb instead of jcc and jmp to improve code density.
//    But beware of excessive branch density on AMD Opterons.
//
// *  Both Fast_Lock and Fast_Unlock set the ICC.ZF to indicate success
//    or failure of the fast-path.  If the fast-path fails then we pass
//    control to the slow-path, typically in C.  In Fast_Lock and
//    Fast_Unlock we often branch to DONE_LABEL, just to find that C2
//    will emit a conditional branch immediately after the node.
//    So we have branches to branches and lots of ICC.ZF games.
//    Instead, it might be better to have C2 pass a "FailureLabel"
//    into Fast_Lock and Fast_Unlock.  In the case of success, control
//    will drop through the node.  ICC.ZF is undefined at exit.
//    In the case of failure, the node will branch directly to the
//    FailureLabel


// obj: object to lock
// box: on-stack box address (displaced header location) - KILLED
// rax,: tmp -- KILLED
// scr: tmp -- KILLED
void MacroAssembler::fast_lock(Register objReg, Register boxReg, Register tmpReg,
                               Register scrReg, Register cx1Reg, Register cx2Reg,
                               BiasedLockingCounters* counters,
                               RTMLockingCounters* rtm_counters,
                               RTMLockingCounters* stack_rtm_counters,
                               Metadata* method_data,
                               bool use_rtm, bool profile_rtm) {
  // Ensure the register assignments are disjoint
  assert(tmpReg == rax, "");

  if (use_rtm) {
    assert_different_registers(objReg, boxReg, tmpReg, scrReg, cx1Reg, cx2Reg);
  } else {
    assert(cx1Reg == noreg, "");
    assert(cx2Reg == noreg, "");
    assert_different_registers(objReg, boxReg, tmpReg, scrReg);
  }

  if (counters != NULL) {
    atomic_incl(ExternalAddress((address)counters->total_entry_count_addr()), scrReg);
  }
  if (EmitSync & 1) {
      // set box->dhw = markOopDesc::unused_mark()
      // Force all sync thru slow-path: slow_enter() and slow_exit()
      movptr (Address(boxReg, 0), (int32_t)intptr_t(markOopDesc::unused_mark()));
      cmpptr (rsp, (int32_t)NULL_WORD);
  } else {
    // Possible cases that we'll encounter in fast_lock
    // ------------------------------------------------
    // * Inflated
    //    -- unlocked
    //    -- Locked
    //       = by self
    //       = by other
    // * biased
    //    -- by Self
    //    -- by other
    // * neutral
    // * stack-locked
    //    -- by self
    //       = sp-proximity test hits
    //       = sp-proximity test generates false-negative
    //    -- by other
    //

    Label IsInflated, DONE_LABEL;

    // it's stack-locked, biased or neutral
    // TODO: optimize away redundant LDs of obj->mark and improve the markword triage
    // order to reduce the number of conditional branches in the most common cases.
    // Beware -- there's a subtle invariant that fetch of the markword
    // at [FETCH], below, will never observe a biased encoding (*101b).
    // If this invariant is not held we risk exclusion (safety) failure.
    if (UseBiasedLocking && !UseOptoBiasInlining) {
      biased_locking_enter(boxReg, objReg, tmpReg, scrReg, false, DONE_LABEL, NULL, counters);
    }

#if INCLUDE_RTM_OPT
    if (UseRTMForStackLocks && use_rtm) {
      rtm_stack_locking(objReg, tmpReg, scrReg, cx2Reg,
                        stack_rtm_counters, method_data, profile_rtm,
                        DONE_LABEL, IsInflated);
    }
#endif // INCLUDE_RTM_OPT

    movptr(tmpReg, Address(objReg, oopDesc::mark_offset_in_bytes()));          // [FETCH]
    testptr(tmpReg, markOopDesc::monitor_value); // inflated vs stack-locked|neutral|biased
    jccb(Assembler::notZero, IsInflated);

    // Attempt stack-locking ...
    orptr (tmpReg, markOopDesc::unlocked_value);
    movptr(Address(boxReg, 0), tmpReg);          // Anticipate successful CAS
    if (os::is_MP()) {
      lock();
    }
    cmpxchgptr(boxReg, Address(objReg, oopDesc::mark_offset_in_bytes()));      // Updates tmpReg
    if (counters != NULL) {
      cond_inc32(Assembler::equal,
                 ExternalAddress((address)counters->fast_path_entry_count_addr()));
    }
    jcc(Assembler::equal, DONE_LABEL);           // Success

    // Recursive locking.
    // The object is stack-locked: markword contains stack pointer to BasicLock.
    // Locked by current thread if difference with current SP is less than one page.
    subptr(tmpReg, rsp);
    // Next instruction set ZFlag == 1 (Success) if difference is less then one page.
    andptr(tmpReg, (int32_t) (NOT_LP64(0xFFFFF003) LP64_ONLY(7 - os::vm_page_size())) );
    movptr(Address(boxReg, 0), tmpReg);
    if (counters != NULL) {
      cond_inc32(Assembler::equal,
                 ExternalAddress((address)counters->fast_path_entry_count_addr()));
    }
    jmp(DONE_LABEL);

    bind(IsInflated);
    // The object is inflated. tmpReg contains pointer to ObjectMonitor* + markOopDesc::monitor_value

#if INCLUDE_RTM_OPT
    // Use the same RTM locking code in 32- and 64-bit VM.
    if (use_rtm) {
      rtm_inflated_locking(objReg, boxReg, tmpReg, scrReg, cx1Reg, cx2Reg,
                           rtm_counters, method_data, profile_rtm, DONE_LABEL);
    } else {
#endif // INCLUDE_RTM_OPT

#ifndef _LP64
    // The object is inflated.

    // boxReg refers to the on-stack BasicLock in the current frame.
    // We'd like to write:
    //   set box->_displaced_header = markOopDesc::unused_mark().  Any non-0 value suffices.
    // This is convenient but results a ST-before-CAS penalty.  The following CAS suffers
    // additional latency as we have another ST in the store buffer that must drain.

    if (EmitSync & 8192) {
       movptr(Address(boxReg, 0), 3);            // results in ST-before-CAS penalty
       get_thread (scrReg);
       movptr(boxReg, tmpReg);                    // consider: LEA box, [tmp-2]
       movptr(tmpReg, NULL_WORD);                 // consider: xor vs mov
       if (os::is_MP()) {
         lock();
       }
       cmpxchgptr(scrReg, Address(boxReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(owner)));
    } else
    if ((EmitSync & 128) == 0) {                      // avoid ST-before-CAS
       // register juggle because we need tmpReg for cmpxchgptr below
       movptr(scrReg, boxReg);
       movptr(boxReg, tmpReg);                   // consider: LEA box, [tmp-2]

       // Using a prefetchw helps avoid later RTS->RTO upgrades and cache probes
       if ((EmitSync & 2048) && VM_Version::supports_3dnow_prefetch() && os::is_MP()) {
          // prefetchw [eax + Offset(_owner)-2]
          prefetchw(Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(owner)));
       }

       if ((EmitSync & 64) == 0) {
         // Optimistic form: consider XORL tmpReg,tmpReg
         movptr(tmpReg, NULL_WORD);
       } else {
         // Can suffer RTS->RTO upgrades on shared or cold $ lines
         // Test-And-CAS instead of CAS
         movptr(tmpReg, Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(owner)));   // rax, = m->_owner
         testptr(tmpReg, tmpReg);                   // Locked ?
         jccb  (Assembler::notZero, DONE_LABEL);
       }

       // Appears unlocked - try to swing _owner from null to non-null.
       // Ideally, I'd manifest "Self" with get_thread and then attempt
       // to CAS the register containing Self into m->Owner.
       // But we don't have enough registers, so instead we can either try to CAS
       // rsp or the address of the box (in scr) into &m->owner.  If the CAS succeeds
       // we later store "Self" into m->Owner.  Transiently storing a stack address
       // (rsp or the address of the box) into  m->owner is harmless.
       // Invariant: tmpReg == 0.  tmpReg is EAX which is the implicit cmpxchg comparand.
       if (os::is_MP()) {
         lock();
       }
       cmpxchgptr(scrReg, Address(boxReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(owner)));
       movptr(Address(scrReg, 0), 3);          // box->_displaced_header = 3
       // If we weren't able to swing _owner from NULL to the BasicLock
       // then take the slow path.
       jccb  (Assembler::notZero, DONE_LABEL);
       // update _owner from BasicLock to thread
       get_thread (scrReg);                    // beware: clobbers ICCs
       movptr(Address(boxReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(owner)), scrReg);
       xorptr(boxReg, boxReg);                 // set icc.ZFlag = 1 to indicate success

       // If the CAS fails we can either retry or pass control to the slow-path.
       // We use the latter tactic.
       // Pass the CAS result in the icc.ZFlag into DONE_LABEL
       // If the CAS was successful ...
       //   Self has acquired the lock
       //   Invariant: m->_recursions should already be 0, so we don't need to explicitly set it.
       // Intentional fall-through into DONE_LABEL ...
    } else {
       movptr(Address(boxReg, 0), intptr_t(markOopDesc::unused_mark()));  // results in ST-before-CAS penalty
       movptr(boxReg, tmpReg);

       // Using a prefetchw helps avoid later RTS->RTO upgrades and cache probes
       if ((EmitSync & 2048) && VM_Version::supports_3dnow_prefetch() && os::is_MP()) {
          // prefetchw [eax + Offset(_owner)-2]
          prefetchw(Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(owner)));
       }

       if ((EmitSync & 64) == 0) {
         // Optimistic form
         xorptr  (tmpReg, tmpReg);
       } else {
         // Can suffer RTS->RTO upgrades on shared or cold $ lines
         movptr(tmpReg, Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(owner)));   // rax, = m->_owner
         testptr(tmpReg, tmpReg);                   // Locked ?
         jccb  (Assembler::notZero, DONE_LABEL);
       }

       // Appears unlocked - try to swing _owner from null to non-null.
       // Use either "Self" (in scr) or rsp as thread identity in _owner.
       // Invariant: tmpReg == 0.  tmpReg is EAX which is the implicit cmpxchg comparand.
       get_thread (scrReg);
       if (os::is_MP()) {
         lock();
       }
       cmpxchgptr(scrReg, Address(boxReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(owner)));

       // If the CAS fails we can either retry or pass control to the slow-path.
       // We use the latter tactic.
       // Pass the CAS result in the icc.ZFlag into DONE_LABEL
       // If the CAS was successful ...
       //   Self has acquired the lock
       //   Invariant: m->_recursions should already be 0, so we don't need to explicitly set it.
       // Intentional fall-through into DONE_LABEL ...
    }
#else // _LP64
    // It's inflated
    movq(scrReg, tmpReg);
    xorq(tmpReg, tmpReg);

    if (os::is_MP()) {
      lock();
    }
    cmpxchgptr(r15_thread, Address(scrReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(owner)));
    // Unconditionally set box->_displaced_header = markOopDesc::unused_mark().
    // Without cast to int32_t movptr will destroy r10 which is typically obj.
    movptr(Address(boxReg, 0), (int32_t)intptr_t(markOopDesc::unused_mark()));
    // Intentional fall-through into DONE_LABEL ...
    // Propagate ICC.ZF from CAS above into DONE_LABEL.
#endif // _LP64
#if INCLUDE_RTM_OPT
    } // use_rtm()
#endif
    // DONE_LABEL is a hot target - we'd really like to place it at the
    // start of cache line by padding with NOPs.
    // See the AMD and Intel software optimization manuals for the
    // most efficient "long" NOP encodings.
    // Unfortunately none of our alignment mechanisms suffice.
    bind(DONE_LABEL);

    // At DONE_LABEL the icc ZFlag is set as follows ...
    // Fast_Unlock uses the same protocol.
    // ZFlag == 1 -> Success
    // ZFlag == 0 -> Failure - force control through the slow-path
  }
}

// obj: object to unlock
// box: box address (displaced header location), killed.  Must be EAX.
// tmp: killed, cannot be obj nor box.
//
// Some commentary on balanced locking:
//
// Fast_Lock and Fast_Unlock are emitted only for provably balanced lock sites.
// Methods that don't have provably balanced locking are forced to run in the
// interpreter - such methods won't be compiled to use fast_lock and fast_unlock.
// The interpreter provides two properties:
// I1:  At return-time the interpreter automatically and quietly unlocks any
//      objects acquired the current activation (frame).  Recall that the
//      interpreter maintains an on-stack list of locks currently held by
//      a frame.
// I2:  If a method attempts to unlock an object that is not held by the
//      the frame the interpreter throws IMSX.
//
// Lets say A(), which has provably balanced locking, acquires O and then calls B().
// B() doesn't have provably balanced locking so it runs in the interpreter.
// Control returns to A() and A() unlocks O.  By I1 and I2, above, we know that O
// is still locked by A().
//
// The only other source of unbalanced locking would be JNI.  The "Java Native Interface:
// Programmer's Guide and Specification" claims that an object locked by jni_monitorenter
// should not be unlocked by "normal" java-level locking and vice-versa.  The specification
// doesn't specify what will occur if a program engages in such mixed-mode locking, however.
// Arguably given that the spec legislates the JNI case as undefined our implementation
// could reasonably *avoid* checking owner in Fast_Unlock().
// In the interest of performance we elide m->Owner==Self check in unlock.
// A perfectly viable alternative is to elide the owner check except when
// Xcheck:jni is enabled.

void MacroAssembler::fast_unlock(Register objReg, Register boxReg, Register tmpReg, bool use_rtm) {
  assert(boxReg == rax, "");
  assert_different_registers(objReg, boxReg, tmpReg);

  if (EmitSync & 4) {
    // Disable - inhibit all inlining.  Force control through the slow-path
    cmpptr (rsp, 0);
  } else {
    Label DONE_LABEL, Stacked, CheckSucc;

    // Critically, the biased locking test must have precedence over
    // and appear before the (box->dhw == 0) recursive stack-lock test.
    if (UseBiasedLocking && !UseOptoBiasInlining) {
       biased_locking_exit(objReg, tmpReg, DONE_LABEL);
    }

#if INCLUDE_RTM_OPT
    if (UseRTMForStackLocks && use_rtm) {
      assert(!UseBiasedLocking, "Biased locking is not supported with RTM locking");
      Label L_regular_unlock;
      movptr(tmpReg, Address(objReg, oopDesc::mark_offset_in_bytes()));           // fetch markword
      andptr(tmpReg, markOopDesc::biased_lock_mask_in_place); // look at 3 lock bits
      cmpptr(tmpReg, markOopDesc::unlocked_value);            // bits = 001 unlocked
      jccb(Assembler::notEqual, L_regular_unlock);  // if !HLE RegularLock
      xend();                                       // otherwise end...
      jmp(DONE_LABEL);                              // ... and we're done
      bind(L_regular_unlock);
    }
#endif

    cmpptr(Address(boxReg, 0), (int32_t)NULL_WORD); // Examine the displaced header
    jcc   (Assembler::zero, DONE_LABEL);            // 0 indicates recursive stack-lock
    movptr(tmpReg, Address(objReg, oopDesc::mark_offset_in_bytes()));             // Examine the object's markword
    testptr(tmpReg, markOopDesc::monitor_value);    // Inflated?
    jccb  (Assembler::zero, Stacked);

    // It's inflated.
#if INCLUDE_RTM_OPT
    if (use_rtm) {
      Label L_regular_inflated_unlock;
      int owner_offset = OM_OFFSET_NO_MONITOR_VALUE_TAG(owner);
      movptr(boxReg, Address(tmpReg, owner_offset));
      testptr(boxReg, boxReg);
      jccb(Assembler::notZero, L_regular_inflated_unlock);
      xend();
      jmpb(DONE_LABEL);
      bind(L_regular_inflated_unlock);
    }
#endif

    // Despite our balanced locking property we still check that m->_owner == Self
    // as java routines or native JNI code called by this thread might
    // have released the lock.
    // Refer to the comments in synchronizer.cpp for how we might encode extra
    // state in _succ so we can avoid fetching EntryList|cxq.
    //
    // I'd like to add more cases in fast_lock() and fast_unlock() --
    // such as recursive enter and exit -- but we have to be wary of
    // I$ bloat, T$ effects and BP$ effects.
    //
    // If there's no contention try a 1-0 exit.  That is, exit without
    // a costly MEMBAR or CAS.  See synchronizer.cpp for details on how
    // we detect and recover from the race that the 1-0 exit admits.
    //
    // Conceptually Fast_Unlock() must execute a STST|LDST "release" barrier
    // before it STs null into _owner, releasing the lock.  Updates
    // to data protected by the critical section must be visible before
    // we drop the lock (and thus before any other thread could acquire
    // the lock and observe the fields protected by the lock).
    // IA32's memory-model is SPO, so STs are ordered with respect to
    // each other and there's no need for an explicit barrier (fence).
    // See also http://gee.cs.oswego.edu/dl/jmm/cookbook.html.
#ifndef _LP64
    get_thread (boxReg);
    if ((EmitSync & 4096) && VM_Version::supports_3dnow_prefetch() && os::is_MP()) {
      // prefetchw [ebx + Offset(_owner)-2]
      prefetchw(Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(owner)));
    }

    // Note that we could employ various encoding schemes to reduce
    // the number of loads below (currently 4) to just 2 or 3.
    // Refer to the comments in synchronizer.cpp.
    // In practice the chain of fetches doesn't seem to impact performance, however.
    xorptr(boxReg, boxReg);
    if ((EmitSync & 65536) == 0 && (EmitSync & 256)) {
       // Attempt to reduce branch density - AMD's branch predictor.
       orptr(boxReg, Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(recursions)));
       orptr(boxReg, Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(EntryList)));
       orptr(boxReg, Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(cxq)));
       jccb  (Assembler::notZero, DONE_LABEL);
       movptr(Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(owner)), NULL_WORD);
       jmpb  (DONE_LABEL);
    } else {
       orptr(boxReg, Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(recursions)));
       jccb  (Assembler::notZero, DONE_LABEL);
       movptr(boxReg, Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(EntryList)));
       orptr(boxReg, Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(cxq)));
       jccb  (Assembler::notZero, CheckSucc);
       movptr(Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(owner)), NULL_WORD);
       jmpb  (DONE_LABEL);
    }

    // The Following code fragment (EmitSync & 65536) improves the performance of
    // contended applications and contended synchronization microbenchmarks.
    // Unfortunately the emission of the code - even though not executed - causes regressions
    // in scimark and jetstream, evidently because of $ effects.  Replacing the code
    // with an equal number of never-executed NOPs results in the same regression.
    // We leave it off by default.

    if ((EmitSync & 65536) != 0) {
       Label LSuccess, LGoSlowPath ;

       bind  (CheckSucc);

       // Optional pre-test ... it's safe to elide this
       cmpptr(Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(succ)), (int32_t)NULL_WORD);
       jccb(Assembler::zero, LGoSlowPath);

       // We have a classic Dekker-style idiom:
       //    ST m->_owner = 0 ; MEMBAR; LD m->_succ
       // There are a number of ways to implement the barrier:
       // (1) lock:andl &m->_owner, 0
       //     is fast, but mask doesn't currently support the "ANDL M,IMM32" form.
       //     LOCK: ANDL [ebx+Offset(_Owner)-2], 0
       //     Encodes as 81 31 OFF32 IMM32 or 83 63 OFF8 IMM8
       // (2) If supported, an explicit MFENCE is appealing.
       //     In older IA32 processors MFENCE is slower than lock:add or xchg
       //     particularly if the write-buffer is full as might be the case if
       //     if stores closely precede the fence or fence-equivalent instruction.
       //     See https://blogs.oracle.com/dave/entry/instruction_selection_for_volatile_fences
       //     as the situation has changed with Nehalem and Shanghai.
       // (3) In lieu of an explicit fence, use lock:addl to the top-of-stack
       //     The $lines underlying the top-of-stack should be in M-state.
       //     The locked add instruction is serializing, of course.
       // (4) Use xchg, which is serializing
       //     mov boxReg, 0; xchgl boxReg, [tmpReg + Offset(_owner)-2] also works
       // (5) ST m->_owner = 0 and then execute lock:orl &m->_succ, 0.
       //     The integer condition codes will tell us if succ was 0.
       //     Since _succ and _owner should reside in the same $line and
       //     we just stored into _owner, it's likely that the $line
       //     remains in M-state for the lock:orl.
       //
       // We currently use (3), although it's likely that switching to (2)
       // is correct for the future.

       movptr(Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(owner)), NULL_WORD);
       if (os::is_MP()) {
         lock(); addptr(Address(rsp, 0), 0);
       }
       // Ratify _succ remains non-null
       cmpptr(Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(succ)), 0);
       jccb  (Assembler::notZero, LSuccess);

       xorptr(boxReg, boxReg);                  // box is really EAX
       if (os::is_MP()) { lock(); }
       cmpxchgptr(rsp, Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(owner)));
       // There's no successor so we tried to regrab the lock with the
       // placeholder value. If that didn't work, then another thread
       // grabbed the lock so we're done (and exit was a success).
       jccb  (Assembler::notEqual, LSuccess);
       // Since we're low on registers we installed rsp as a placeholding in _owner.
       // Now install Self over rsp.  This is safe as we're transitioning from
       // non-null to non=null
       get_thread (boxReg);
       movptr(Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(owner)), boxReg);
       // Intentional fall-through into LGoSlowPath ...

       bind  (LGoSlowPath);
       orptr(boxReg, 1);                      // set ICC.ZF=0 to indicate failure
       jmpb  (DONE_LABEL);

       bind  (LSuccess);
       xorptr(boxReg, boxReg);                 // set ICC.ZF=1 to indicate success
       jmpb  (DONE_LABEL);
    }

    bind (Stacked);
    // It's not inflated and it's not recursively stack-locked and it's not biased.
    // It must be stack-locked.
    // Try to reset the header to displaced header.
    // The "box" value on the stack is stable, so we can reload
    // and be assured we observe the same value as above.
    movptr(tmpReg, Address(boxReg, 0));
    if (os::is_MP()) {
      lock();
    }
    cmpxchgptr(tmpReg, Address(objReg, oopDesc::mark_offset_in_bytes())); // Uses RAX which is box
    // Intention fall-thru into DONE_LABEL

    // DONE_LABEL is a hot target - we'd really like to place it at the
    // start of cache line by padding with NOPs.
    // See the AMD and Intel software optimization manuals for the
    // most efficient "long" NOP encodings.
    // Unfortunately none of our alignment mechanisms suffice.
    if ((EmitSync & 65536) == 0) {
       bind (CheckSucc);
    }
#else // _LP64
    // It's inflated
    if (EmitSync & 1024) {
      // Emit code to check that _owner == Self
      // We could fold the _owner test into subsequent code more efficiently
      // than using a stand-alone check, but since _owner checking is off by
      // default we don't bother. We also might consider predicating the
      // _owner==Self check on Xcheck:jni or running on a debug build.
      movptr(boxReg, Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(owner)));
      xorptr(boxReg, r15_thread);
    } else {
      xorptr(boxReg, boxReg);
    }
    orptr(boxReg, Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(recursions)));
    jccb  (Assembler::notZero, DONE_LABEL);
    movptr(boxReg, Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(cxq)));
    orptr(boxReg, Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(EntryList)));
    jccb  (Assembler::notZero, CheckSucc);
    movptr(Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(owner)), (int32_t)NULL_WORD);
    jmpb  (DONE_LABEL);

    if ((EmitSync & 65536) == 0) {
      // Try to avoid passing control into the slow_path ...
      Label LSuccess, LGoSlowPath ;
      bind  (CheckSucc);

      // The following optional optimization can be elided if necessary
      // Effectively: if (succ == null) goto SlowPath
      // The code reduces the window for a race, however,
      // and thus benefits performance.
      cmpptr(Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(succ)), (int32_t)NULL_WORD);
      jccb  (Assembler::zero, LGoSlowPath);

      xorptr(boxReg, boxReg);
      if ((EmitSync & 16) && os::is_MP()) {
        xchgptr(boxReg, Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(owner)));
      } else {
        movptr(Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(owner)), (int32_t)NULL_WORD);
        if (os::is_MP()) {
          // Memory barrier/fence
          // Dekker pivot point -- fulcrum : ST Owner; MEMBAR; LD Succ
          // Instead of MFENCE we use a dummy locked add of 0 to the top-of-stack.
          // This is faster on Nehalem and AMD Shanghai/Barcelona.
          // See https://blogs.oracle.com/dave/entry/instruction_selection_for_volatile_fences
          // We might also restructure (ST Owner=0;barrier;LD _Succ) to
          // (mov box,0; xchgq box, &m->Owner; LD _succ) .
          lock(); addl(Address(rsp, 0), 0);
        }
      }
      cmpptr(Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(succ)), (int32_t)NULL_WORD);
      jccb  (Assembler::notZero, LSuccess);

      // Rare inopportune interleaving - race.
      // The successor vanished in the small window above.
      // The lock is contended -- (cxq|EntryList) != null -- and there's no apparent successor.
      // We need to ensure progress and succession.
      // Try to reacquire the lock.
      // If that fails then the new owner is responsible for succession and this
      // thread needs to take no further action and can exit via the fast path (success).
      // If the re-acquire succeeds then pass control into the slow path.
      // As implemented, this latter mode is horrible because we generated more
      // coherence traffic on the lock *and* artifically extended the critical section
      // length while by virtue of passing control into the slow path.

      // box is really RAX -- the following CMPXCHG depends on that binding
      // cmpxchg R,[M] is equivalent to rax = CAS(M,rax,R)
      if (os::is_MP()) { lock(); }
      cmpxchgptr(r15_thread, Address(tmpReg, OM_OFFSET_NO_MONITOR_VALUE_TAG(owner)));
      // There's no successor so we tried to regrab the lock.
      // If that didn't work, then another thread grabbed the
      // lock so we're done (and exit was a success).
      jccb  (Assembler::notEqual, LSuccess);
      // Intentional fall-through into slow-path

      bind  (LGoSlowPath);
      orl   (boxReg, 1);                      // set ICC.ZF=0 to indicate failure
      jmpb  (DONE_LABEL);

      bind  (LSuccess);
      testl (boxReg, 0);                      // set ICC.ZF=1 to indicate success
      jmpb  (DONE_LABEL);
    }

    bind  (Stacked);
    movptr(tmpReg, Address (boxReg, 0));      // re-fetch
    if (os::is_MP()) { lock(); }
    cmpxchgptr(tmpReg, Address(objReg, oopDesc::mark_offset_in_bytes())); // Uses RAX which is box

    if (EmitSync & 65536) {
       bind (CheckSucc);
    }
#endif
    bind(DONE_LABEL);
  }
}
#endif // COMPILER2

void MacroAssembler::c2bool(Register x) {
  // implements x == 0 ? 0 : 1
  // note: must only look at least-significant byte of x
  //       since C-style booleans are stored in one byte
  //       only! (was bug)
  andl(x, 0xFF);
  setb(Assembler::notZero, x);
}

// Wouldn't need if AddressLiteral version had new name
void MacroAssembler::call(Label& L, relocInfo::relocType rtype) {
  Assembler::call(L, rtype);
}

void MacroAssembler::call(Register entry) {
  Assembler::call(entry);
}

void MacroAssembler::call(AddressLiteral entry) {
  if (reachable(entry)) {
    Assembler::call_literal(entry.target(), entry.rspec());
  } else {
    lea(rscratch1, entry);
    Assembler::call(rscratch1);
  }
}

void MacroAssembler::ic_call(address entry, jint method_index) {
  RelocationHolder rh = virtual_call_Relocation::spec(pc(), method_index);
  movptr(rax, (intptr_t)Universe::non_oop_word());
  call(AddressLiteral(entry, rh));
}

// Implementation of call_VM versions

void MacroAssembler::call_VM(Register oop_result,
                             address entry_point,
                             bool check_exceptions) {
  Label C, E;
  call(C, relocInfo::none);
  jmp(E);

  bind(C);
  call_VM_helper(oop_result, entry_point, 0, check_exceptions);
  ret(0);

  bind(E);
}

void MacroAssembler::call_VM(Register oop_result,
                             address entry_point,
                             Register arg_1,
                             bool check_exceptions) {
  Label C, E;
  call(C, relocInfo::none);
  jmp(E);

  bind(C);
  pass_arg1(this, arg_1);
  call_VM_helper(oop_result, entry_point, 1, check_exceptions);
  ret(0);

  bind(E);
}

void MacroAssembler::call_VM(Register oop_result,
                             address entry_point,
                             Register arg_1,
                             Register arg_2,
                             bool check_exceptions) {
  Label C, E;
  call(C, relocInfo::none);
  jmp(E);

  bind(C);

  LP64_ONLY(assert(arg_1 != c_rarg2, "smashed arg"));

  pass_arg2(this, arg_2);
  pass_arg1(this, arg_1);
  call_VM_helper(oop_result, entry_point, 2, check_exceptions);
  ret(0);

  bind(E);
}

void MacroAssembler::call_VM(Register oop_result,
                             address entry_point,
                             Register arg_1,
                             Register arg_2,
                             Register arg_3,
                             bool check_exceptions) {
  Label C, E;
  call(C, relocInfo::none);
  jmp(E);

  bind(C);

  LP64_ONLY(assert(arg_1 != c_rarg3, "smashed arg"));
  LP64_ONLY(assert(arg_2 != c_rarg3, "smashed arg"));
  pass_arg3(this, arg_3);

  LP64_ONLY(assert(arg_1 != c_rarg2, "smashed arg"));
  pass_arg2(this, arg_2);

  pass_arg1(this, arg_1);
  call_VM_helper(oop_result, entry_point, 3, check_exceptions);
  ret(0);

  bind(E);
}

void MacroAssembler::call_VM(Register oop_result,
                             Register last_java_sp,
                             address entry_point,
                             int number_of_arguments,
                             bool check_exceptions) {
  Register thread = LP64_ONLY(r15_thread) NOT_LP64(noreg);
  call_VM_base(oop_result, thread, last_java_sp, entry_point, number_of_arguments, check_exceptions);
}

void MacroAssembler::call_VM(Register oop_result,
                             Register last_java_sp,
                             address entry_point,
                             Register arg_1,
                             bool check_exceptions) {
  pass_arg1(this, arg_1);
  call_VM(oop_result, last_java_sp, entry_point, 1, check_exceptions);
}

void MacroAssembler::call_VM(Register oop_result,
                             Register last_java_sp,
                             address entry_point,
                             Register arg_1,
                             Register arg_2,
                             bool check_exceptions) {

  LP64_ONLY(assert(arg_1 != c_rarg2, "smashed arg"));
  pass_arg2(this, arg_2);
  pass_arg1(this, arg_1);
  call_VM(oop_result, last_java_sp, entry_point, 2, check_exceptions);
}

void MacroAssembler::call_VM(Register oop_result,
                             Register last_java_sp,
                             address entry_point,
                             Register arg_1,
                             Register arg_2,
                             Register arg_3,
                             bool check_exceptions) {
  LP64_ONLY(assert(arg_1 != c_rarg3, "smashed arg"));
  LP64_ONLY(assert(arg_2 != c_rarg3, "smashed arg"));
  pass_arg3(this, arg_3);
  LP64_ONLY(assert(arg_1 != c_rarg2, "smashed arg"));
  pass_arg2(this, arg_2);
  pass_arg1(this, arg_1);
  call_VM(oop_result, last_java_sp, entry_point, 3, check_exceptions);
}

void MacroAssembler::super_call_VM(Register oop_result,
                                   Register last_java_sp,
                                   address entry_point,
                                   int number_of_arguments,
                                   bool check_exceptions) {
  Register thread = LP64_ONLY(r15_thread) NOT_LP64(noreg);
  MacroAssembler::call_VM_base(oop_result, thread, last_java_sp, entry_point, number_of_arguments, check_exceptions);
}

void MacroAssembler::super_call_VM(Register oop_result,
                                   Register last_java_sp,
                                   address entry_point,
                                   Register arg_1,
                                   bool check_exceptions) {
  pass_arg1(this, arg_1);
  super_call_VM(oop_result, last_java_sp, entry_point, 1, check_exceptions);
}

void MacroAssembler::super_call_VM(Register oop_result,
                                   Register last_java_sp,
                                   address entry_point,
                                   Register arg_1,
                                   Register arg_2,
                                   bool check_exceptions) {

  LP64_ONLY(assert(arg_1 != c_rarg2, "smashed arg"));
  pass_arg2(this, arg_2);
  pass_arg1(this, arg_1);
  super_call_VM(oop_result, last_java_sp, entry_point, 2, check_exceptions);
}

void MacroAssembler::super_call_VM(Register oop_result,
                                   Register last_java_sp,
                                   address entry_point,
                                   Register arg_1,
                                   Register arg_2,
                                   Register arg_3,
                                   bool check_exceptions) {
  LP64_ONLY(assert(arg_1 != c_rarg3, "smashed arg"));
  LP64_ONLY(assert(arg_2 != c_rarg3, "smashed arg"));
  pass_arg3(this, arg_3);
  LP64_ONLY(assert(arg_1 != c_rarg2, "smashed arg"));
  pass_arg2(this, arg_2);
  pass_arg1(this, arg_1);
  super_call_VM(oop_result, last_java_sp, entry_point, 3, check_exceptions);
}

void MacroAssembler::call_VM_base(Register oop_result,
                                  Register java_thread,
                                  Register last_java_sp,
                                  address  entry_point,
                                  int      number_of_arguments,
                                  bool     check_exceptions) {
  // determine java_thread register
  if (!java_thread->is_valid()) {
#ifdef _LP64
    java_thread = r15_thread;
#else
    java_thread = rdi;
    get_thread(java_thread);
#endif // LP64
  }
  // determine last_java_sp register
  if (!last_java_sp->is_valid()) {
    last_java_sp = rsp;
  }
  // debugging support
  assert(number_of_arguments >= 0   , "cannot have negative number of arguments");
  LP64_ONLY(assert(java_thread == r15_thread, "unexpected register"));
#ifdef ASSERT
  // TraceBytecodes does not use r12 but saves it over the call, so don't verify
  // r12 is the heapbase.
  LP64_ONLY(if ((UseCompressedOops || UseCompressedClassPointers) && !TraceBytecodes) verify_heapbase("call_VM_base: heap base corrupted?");)
#endif // ASSERT

  assert(java_thread != oop_result  , "cannot use the same register for java_thread & oop_result");
  assert(java_thread != last_java_sp, "cannot use the same register for java_thread & last_java_sp");

  // push java thread (becomes first argument of C function)

  NOT_LP64(push(java_thread); number_of_arguments++);
  LP64_ONLY(mov(c_rarg0, r15_thread));

  // set last Java frame before call
  assert(last_java_sp != rbp, "can't use ebp/rbp");

  // Only interpreter should have to set fp
  set_last_Java_frame(java_thread, last_java_sp, rbp, NULL);

  // do the call, remove parameters
  MacroAssembler::call_VM_leaf_base(entry_point, number_of_arguments);

  // restore the thread (cannot use the pushed argument since arguments
  // may be overwritten by C code generated by an optimizing compiler);
  // however can use the register value directly if it is callee saved.
  if (LP64_ONLY(true ||) java_thread == rdi || java_thread == rsi) {
    // rdi & rsi (also r15) are callee saved -> nothing to do
#ifdef ASSERT
    guarantee(java_thread != rax, "change this code");
    push(rax);
    { Label L;
      get_thread(rax);
      cmpptr(java_thread, rax);
      jcc(Assembler::equal, L);
      STOP("MacroAssembler::call_VM_base: rdi not callee saved?");
      bind(L);
    }
    pop(rax);
#endif
  } else {
    get_thread(java_thread);
  }
  // reset last Java frame
  // Only interpreter should have to clear fp
  reset_last_Java_frame(java_thread, true);

   // C++ interp handles this in the interpreter
  check_and_handle_popframe(java_thread);
  check_and_handle_earlyret(java_thread);

  if (check_exceptions) {
    // check for pending exceptions (java_thread is set upon return)
    cmpptr(Address(java_thread, Thread::pending_exception_offset()), (int32_t) NULL_WORD);
#ifndef _LP64
    jump_cc(Assembler::notEqual,
            RuntimeAddress(StubRoutines::forward_exception_entry()));
#else
    // This used to conditionally jump to forward_exception however it is
    // possible if we relocate that the branch will not reach. So we must jump
    // around so we can always reach

    Label ok;
    jcc(Assembler::equal, ok);
    jump(RuntimeAddress(StubRoutines::forward_exception_entry()));
    bind(ok);
#endif // LP64
  }

  // get oop result if there is one and reset the value in the thread
  if (oop_result->is_valid()) {
    get_vm_result(oop_result, java_thread);
  }
}

void MacroAssembler::call_VM_helper(Register oop_result, address entry_point, int number_of_arguments, bool check_exceptions) {

  // Calculate the value for last_Java_sp
  // somewhat subtle. call_VM does an intermediate call
  // which places a return address on the stack just under the
  // stack pointer as the user finsihed with it. This allows
  // use to retrieve last_Java_pc from last_Java_sp[-1].
  // On 32bit we then have to push additional args on the stack to accomplish
  // the actual requested call. On 64bit call_VM only can use register args
  // so the only extra space is the return address that call_VM created.
  // This hopefully explains the calculations here.

#ifdef _LP64
  // We've pushed one address, correct last_Java_sp
  lea(rax, Address(rsp, wordSize));
#else
  lea(rax, Address(rsp, (1 + number_of_arguments) * wordSize));
#endif // LP64

  call_VM_base(oop_result, noreg, rax, entry_point, number_of_arguments, check_exceptions);

}

// Use this method when MacroAssembler version of call_VM_leaf_base() should be called from Interpreter.
void MacroAssembler::call_VM_leaf0(address entry_point) {
  MacroAssembler::call_VM_leaf_base(entry_point, 0);
}

void MacroAssembler::call_VM_leaf(address entry_point, int number_of_arguments) {
  call_VM_leaf_base(entry_point, number_of_arguments);
}

void MacroAssembler::call_VM_leaf(address entry_point, Register arg_0) {
  pass_arg0(this, arg_0);
  call_VM_leaf(entry_point, 1);
}

void MacroAssembler::call_VM_leaf(address entry_point, Register arg_0, Register arg_1) {

  LP64_ONLY(assert(arg_0 != c_rarg1, "smashed arg"));
  pass_arg1(this, arg_1);
  pass_arg0(this, arg_0);
  call_VM_leaf(entry_point, 2);
}

void MacroAssembler::call_VM_leaf(address entry_point, Register arg_0, Register arg_1, Register arg_2) {
  LP64_ONLY(assert(arg_0 != c_rarg2, "smashed arg"));
  LP64_ONLY(assert(arg_1 != c_rarg2, "smashed arg"));
  pass_arg2(this, arg_2);
  LP64_ONLY(assert(arg_0 != c_rarg1, "smashed arg"));
  pass_arg1(this, arg_1);
  pass_arg0(this, arg_0);
  call_VM_leaf(entry_point, 3);
}

void MacroAssembler::super_call_VM_leaf(address entry_point, Register arg_0) {
  pass_arg0(this, arg_0);
  MacroAssembler::call_VM_leaf_base(entry_point, 1);
}

void MacroAssembler::super_call_VM_leaf(address entry_point, Register arg_0, Register arg_1) {

  LP64_ONLY(assert(arg_0 != c_rarg1, "smashed arg"));
  pass_arg1(this, arg_1);
  pass_arg0(this, arg_0);
  MacroAssembler::call_VM_leaf_base(entry_point, 2);
}

void MacroAssembler::super_call_VM_leaf(address entry_point, Register arg_0, Register arg_1, Register arg_2) {
  LP64_ONLY(assert(arg_0 != c_rarg2, "smashed arg"));
  LP64_ONLY(assert(arg_1 != c_rarg2, "smashed arg"));
  pass_arg2(this, arg_2);
  LP64_ONLY(assert(arg_0 != c_rarg1, "smashed arg"));
  pass_arg1(this, arg_1);
  pass_arg0(this, arg_0);
  MacroAssembler::call_VM_leaf_base(entry_point, 3);
}

void MacroAssembler::super_call_VM_leaf(address entry_point, Register arg_0, Register arg_1, Register arg_2, Register arg_3) {
  LP64_ONLY(assert(arg_0 != c_rarg3, "smashed arg"));
  LP64_ONLY(assert(arg_1 != c_rarg3, "smashed arg"));
  LP64_ONLY(assert(arg_2 != c_rarg3, "smashed arg"));
  pass_arg3(this, arg_3);
  LP64_ONLY(assert(arg_0 != c_rarg2, "smashed arg"));
  LP64_ONLY(assert(arg_1 != c_rarg2, "smashed arg"));
  pass_arg2(this, arg_2);
  LP64_ONLY(assert(arg_0 != c_rarg1, "smashed arg"));
  pass_arg1(this, arg_1);
  pass_arg0(this, arg_0);
  MacroAssembler::call_VM_leaf_base(entry_point, 4);
}

void MacroAssembler::get_vm_result(Register oop_result, Register java_thread) {
  movptr(oop_result, Address(java_thread, JavaThread::vm_result_offset()));
  movptr(Address(java_thread, JavaThread::vm_result_offset()), NULL_WORD);
  verify_oop(oop_result, "broken oop in call_VM_base");
}

void MacroAssembler::get_vm_result_2(Register metadata_result, Register java_thread) {
  movptr(metadata_result, Address(java_thread, JavaThread::vm_result_2_offset()));
  movptr(Address(java_thread, JavaThread::vm_result_2_offset()), NULL_WORD);
}

void MacroAssembler::check_and_handle_earlyret(Register java_thread) {
}

void MacroAssembler::check_and_handle_popframe(Register java_thread) {
}

void MacroAssembler::cmp32(AddressLiteral src1, int32_t imm) {
  if (reachable(src1)) {
    cmpl(as_Address(src1), imm);
  } else {
    lea(rscratch1, src1);
    cmpl(Address(rscratch1, 0), imm);
  }
}

void MacroAssembler::cmp32(Register src1, AddressLiteral src2) {
  assert(!src2.is_lval(), "use cmpptr");
  if (reachable(src2)) {
    cmpl(src1, as_Address(src2));
  } else {
    lea(rscratch1, src2);
    cmpl(src1, Address(rscratch1, 0));
  }
}

void MacroAssembler::cmp32(Register src1, int32_t imm) {
  Assembler::cmpl(src1, imm);
}

void MacroAssembler::cmp32(Register src1, Address src2) {
  Assembler::cmpl(src1, src2);
}

void MacroAssembler::cmpsd2int(XMMRegister opr1, XMMRegister opr2, Register dst, bool unordered_is_less) {
  ucomisd(opr1, opr2);

  Label L;
  if (unordered_is_less) {
    movl(dst, -1);
    jcc(Assembler::parity, L);
    jcc(Assembler::below , L);
    movl(dst, 0);
    jcc(Assembler::equal , L);
    increment(dst);
  } else { // unordered is greater
    movl(dst, 1);
    jcc(Assembler::parity, L);
    jcc(Assembler::above , L);
    movl(dst, 0);
    jcc(Assembler::equal , L);
    decrementl(dst);
  }
  bind(L);
}

void MacroAssembler::cmpss2int(XMMRegister opr1, XMMRegister opr2, Register dst, bool unordered_is_less) {
  ucomiss(opr1, opr2);

  Label L;
  if (unordered_is_less) {
    movl(dst, -1);
    jcc(Assembler::parity, L);
    jcc(Assembler::below , L);
    movl(dst, 0);
    jcc(Assembler::equal , L);
    increment(dst);
  } else { // unordered is greater
    movl(dst, 1);
    jcc(Assembler::parity, L);
    jcc(Assembler::above , L);
    movl(dst, 0);
    jcc(Assembler::equal , L);
    decrementl(dst);
  }
  bind(L);
}


void MacroAssembler::cmp8(AddressLiteral src1, int imm) {
  if (reachable(src1)) {
    cmpb(as_Address(src1), imm);
  } else {
    lea(rscratch1, src1);
    cmpb(Address(rscratch1, 0), imm);
  }
}

void MacroAssembler::cmpptr(Register src1, AddressLiteral src2) {
#ifdef _LP64
  if (src2.is_lval()) {
    movptr(rscratch1, src2);
    Assembler::cmpq(src1, rscratch1);
  } else if (reachable(src2)) {
    cmpq(src1, as_Address(src2));
  } else {
    lea(rscratch1, src2);
    Assembler::cmpq(src1, Address(rscratch1, 0));
  }
#else
  if (src2.is_lval()) {
    cmp_literal32(src1, (int32_t) src2.target(), src2.rspec());
  } else {
    cmpl(src1, as_Address(src2));
  }
#endif // _LP64
}

void MacroAssembler::cmpptr(Address src1, AddressLiteral src2) {
  assert(src2.is_lval(), "not a mem-mem compare");
#ifdef _LP64
  // moves src2's literal address
  movptr(rscratch1, src2);
  Assembler::cmpq(src1, rscratch1);
#else
  cmp_literal32(src1, (int32_t) src2.target(), src2.rspec());
#endif // _LP64
}

void MacroAssembler::cmpoop(Register src1, Register src2) {
  BarrierSetAssembler* bs = BarrierSet::barrier_set()->barrier_set_assembler();
  bs->obj_equals(this, src1, src2);
}

void MacroAssembler::cmpoop(Register src1, Address src2) {
  BarrierSetAssembler* bs = BarrierSet::barrier_set()->barrier_set_assembler();
  bs->obj_equals(this, src1, src2);
}

#ifdef _LP64
void MacroAssembler::cmpoop(Register src1, jobject src2) {
  movoop(rscratch1, src2);
  BarrierSetAssembler* bs = BarrierSet::barrier_set()->barrier_set_assembler();
  bs->obj_equals(this, src1, rscratch1);
}
#endif

void MacroAssembler::locked_cmpxchgptr(Register reg, AddressLiteral adr) {
  if (reachable(adr)) {
    if (os::is_MP())
      lock();
    cmpxchgptr(reg, as_Address(adr));
  } else {
    lea(rscratch1, adr);
    if (os::is_MP())
      lock();
    cmpxchgptr(reg, Address(rscratch1, 0));
  }
}

void MacroAssembler::cmpxchgptr(Register reg, Address adr) {
  LP64_ONLY(cmpxchgq(reg, adr)) NOT_LP64(cmpxchgl(reg, adr));
}

void MacroAssembler::comisd(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    Assembler::comisd(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    Assembler::comisd(dst, Address(rscratch1, 0));
  }
}

void MacroAssembler::comiss(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    Assembler::comiss(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    Assembler::comiss(dst, Address(rscratch1, 0));
  }
}


void MacroAssembler::cond_inc32(Condition cond, AddressLiteral counter_addr) {
  Condition negated_cond = negate_condition(cond);
  Label L;
  jcc(negated_cond, L);
  pushf(); // Preserve flags
  atomic_incl(counter_addr);
  popf();
  bind(L);
}

int MacroAssembler::corrected_idivl(Register reg) {
  // Full implementation of Java idiv and irem; checks for
  // special case as described in JVM spec., p.243 & p.271.
  // The function returns the (pc) offset of the idivl
  // instruction - may be needed for implicit exceptions.
  //
  //         normal case                           special case
  //
  // input : rax,: dividend                         min_int
  //         reg: divisor   (may not be rax,/rdx)   -1
  //
  // output: rax,: quotient  (= rax, idiv reg)       min_int
  //         rdx: remainder (= rax, irem reg)       0
  assert(reg != rax && reg != rdx, "reg cannot be rax, or rdx register");
  const int min_int = 0x80000000;
  Label normal_case, special_case;

  // check for special case
  cmpl(rax, min_int);
  jcc(Assembler::notEqual, normal_case);
  xorl(rdx, rdx); // prepare rdx for possible special case (where remainder = 0)
  cmpl(reg, -1);
  jcc(Assembler::equal, special_case);

  // handle normal case
  bind(normal_case);
  cdql();
  int idivl_offset = offset();
  idivl(reg);

  // normal and special case exit
  bind(special_case);

  return idivl_offset;
}



void MacroAssembler::decrementl(Register reg, int value) {
  if (value == min_jint) {subl(reg, value) ; return; }
  if (value <  0) { incrementl(reg, -value); return; }
  if (value == 0) {                        ; return; }
  if (value == 1 && UseIncDec) { decl(reg) ; return; }
  /* else */      { subl(reg, value)       ; return; }
}

void MacroAssembler::decrementl(Address dst, int value) {
  if (value == min_jint) {subl(dst, value) ; return; }
  if (value <  0) { incrementl(dst, -value); return; }
  if (value == 0) {                        ; return; }
  if (value == 1 && UseIncDec) { decl(dst) ; return; }
  /* else */      { subl(dst, value)       ; return; }
}

void MacroAssembler::division_with_shift (Register reg, int shift_value) {
  assert (shift_value > 0, "illegal shift value");
  Label _is_positive;
  testl (reg, reg);
  jcc (Assembler::positive, _is_positive);
  int offset = (1 << shift_value) - 1 ;

  if (offset == 1) {
    incrementl(reg);
  } else {
    addl(reg, offset);
  }

  bind (_is_positive);
  sarl(reg, shift_value);
}

void MacroAssembler::divsd(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    Assembler::divsd(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    Assembler::divsd(dst, Address(rscratch1, 0));
  }
}

void MacroAssembler::divss(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    Assembler::divss(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    Assembler::divss(dst, Address(rscratch1, 0));
  }
}

// !defined(COMPILER2) is because of stupid core builds
#if !defined(_LP64) || defined(COMPILER1) || !defined(COMPILER2) || INCLUDE_JVMCI
void MacroAssembler::empty_FPU_stack() {
  if (VM_Version::supports_mmx()) {
    emms();
  } else {
    for (int i = 8; i-- > 0; ) ffree(i);
  }
}
#endif // !LP64 || C1 || !C2 || INCLUDE_JVMCI


void MacroAssembler::enter() {
  push(rbp);
  mov(rbp, rsp);
}

// A 5 byte nop that is safe for patching (see patch_verified_entry)
void MacroAssembler::fat_nop() {
  if (UseAddressNop) {
    addr_nop_5();
  } else {
    emit_int8(0x26); // es:
    emit_int8(0x2e); // cs:
    emit_int8(0x64); // fs:
    emit_int8(0x65); // gs:
    emit_int8((unsigned char)0x90);
  }
}

void MacroAssembler::fcmp(Register tmp) {
  fcmp(tmp, 1, true, true);
}

void MacroAssembler::fcmp(Register tmp, int index, bool pop_left, bool pop_right) {
  assert(!pop_right || pop_left, "usage error");
  if (VM_Version::supports_cmov()) {
    assert(tmp == noreg, "unneeded temp");
    if (pop_left) {
      fucomip(index);
    } else {
      fucomi(index);
    }
    if (pop_right) {
      fpop();
    }
  } else {
    assert(tmp != noreg, "need temp");
    if (pop_left) {
      if (pop_right) {
        fcompp();
      } else {
        fcomp(index);
      }
    } else {
      fcom(index);
    }
    // convert FPU condition into eflags condition via rax,
    save_rax(tmp);
    fwait(); fnstsw_ax();
    sahf();
    restore_rax(tmp);
  }
  // condition codes set as follows:
  //
  // CF (corresponds to C0) if x < y
  // PF (corresponds to C2) if unordered
  // ZF (corresponds to C3) if x = y
}

void MacroAssembler::fcmp2int(Register dst, bool unordered_is_less) {
  fcmp2int(dst, unordered_is_less, 1, true, true);
}

void MacroAssembler::fcmp2int(Register dst, bool unordered_is_less, int index, bool pop_left, bool pop_right) {
  fcmp(VM_Version::supports_cmov() ? noreg : dst, index, pop_left, pop_right);
  Label L;
  if (unordered_is_less) {
    movl(dst, -1);
    jcc(Assembler::parity, L);
    jcc(Assembler::below , L);
    movl(dst, 0);
    jcc(Assembler::equal , L);
    increment(dst);
  } else { // unordered is greater
    movl(dst, 1);
    jcc(Assembler::parity, L);
    jcc(Assembler::above , L);
    movl(dst, 0);
    jcc(Assembler::equal , L);
    decrementl(dst);
  }
  bind(L);
}

void MacroAssembler::fld_d(AddressLiteral src) {
  fld_d(as_Address(src));
}

void MacroAssembler::fld_s(AddressLiteral src) {
  fld_s(as_Address(src));
}

void MacroAssembler::fld_x(AddressLiteral src) {
  Assembler::fld_x(as_Address(src));
}

void MacroAssembler::fldcw(AddressLiteral src) {
  Assembler::fldcw(as_Address(src));
}

void MacroAssembler::mulpd(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    Assembler::mulpd(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    Assembler::mulpd(dst, Address(rscratch1, 0));
  }
}

void MacroAssembler::increase_precision() {
  subptr(rsp, BytesPerWord);
  fnstcw(Address(rsp, 0));
  movl(rax, Address(rsp, 0));
  orl(rax, 0x300);
  push(rax);
  fldcw(Address(rsp, 0));
  pop(rax);
}

void MacroAssembler::restore_precision() {
  fldcw(Address(rsp, 0));
  addptr(rsp, BytesPerWord);
}

void MacroAssembler::fpop() {
  ffree();
  fincstp();
}

void MacroAssembler::load_float(Address src) {
  if (UseSSE >= 1) {
    movflt(xmm0, src);
  } else {
    LP64_ONLY(ShouldNotReachHere());
    NOT_LP64(fld_s(src));
  }
}

void MacroAssembler::store_float(Address dst) {
  if (UseSSE >= 1) {
    movflt(dst, xmm0);
  } else {
    LP64_ONLY(ShouldNotReachHere());
    NOT_LP64(fstp_s(dst));
  }
}

void MacroAssembler::load_double(Address src) {
  if (UseSSE >= 2) {
    movdbl(xmm0, src);
  } else {
    LP64_ONLY(ShouldNotReachHere());
    NOT_LP64(fld_d(src));
  }
}

void MacroAssembler::store_double(Address dst) {
  if (UseSSE >= 2) {
    movdbl(dst, xmm0);
  } else {
    LP64_ONLY(ShouldNotReachHere());
    NOT_LP64(fstp_d(dst));
  }
}

void MacroAssembler::fremr(Register tmp) {
  save_rax(tmp);
  { Label L;
    bind(L);
    fprem();
    fwait(); fnstsw_ax();
#ifdef _LP64
    testl(rax, 0x400);
    jcc(Assembler::notEqual, L);
#else
    sahf();
    jcc(Assembler::parity, L);
#endif // _LP64
  }
  restore_rax(tmp);
  // Result is in ST0.
  // Note: fxch & fpop to get rid of ST1
  // (otherwise FPU stack could overflow eventually)
  fxch(1);
  fpop();
}

// dst = c = a * b + c
void MacroAssembler::fmad(XMMRegister dst, XMMRegister a, XMMRegister b, XMMRegister c) {
  Assembler::vfmadd231sd(c, a, b);
  if (dst != c) {
    movdbl(dst, c);
  }
}

// dst = c = a * b + c
void MacroAssembler::fmaf(XMMRegister dst, XMMRegister a, XMMRegister b, XMMRegister c) {
  Assembler::vfmadd231ss(c, a, b);
  if (dst != c) {
    movflt(dst, c);
  }
}

// dst = c = a * b + c
void MacroAssembler::vfmad(XMMRegister dst, XMMRegister a, XMMRegister b, XMMRegister c, int vector_len) {
  Assembler::vfmadd231pd(c, a, b, vector_len);
  if (dst != c) {
    vmovdqu(dst, c);
  }
}

// dst = c = a * b + c
void MacroAssembler::vfmaf(XMMRegister dst, XMMRegister a, XMMRegister b, XMMRegister c, int vector_len) {
  Assembler::vfmadd231ps(c, a, b, vector_len);
  if (dst != c) {
    vmovdqu(dst, c);
  }
}

// dst = c = a * b + c
void MacroAssembler::vfmad(XMMRegister dst, XMMRegister a, Address b, XMMRegister c, int vector_len) {
  Assembler::vfmadd231pd(c, a, b, vector_len);
  if (dst != c) {
    vmovdqu(dst, c);
  }
}

// dst = c = a * b + c
void MacroAssembler::vfmaf(XMMRegister dst, XMMRegister a, Address b, XMMRegister c, int vector_len) {
  Assembler::vfmadd231ps(c, a, b, vector_len);
  if (dst != c) {
    vmovdqu(dst, c);
  }
}

void MacroAssembler::incrementl(AddressLiteral dst) {
  if (reachable(dst)) {
    incrementl(as_Address(dst));
  } else {
    lea(rscratch1, dst);
    incrementl(Address(rscratch1, 0));
  }
}

void MacroAssembler::incrementl(ArrayAddress dst) {
  incrementl(as_Address(dst));
}

void MacroAssembler::incrementl(Register reg, int value) {
  if (value == min_jint) {addl(reg, value) ; return; }
  if (value <  0) { decrementl(reg, -value); return; }
  if (value == 0) {                        ; return; }
  if (value == 1 && UseIncDec) { incl(reg) ; return; }
  /* else */      { addl(reg, value)       ; return; }
}

void MacroAssembler::incrementl(Address dst, int value) {
  if (value == min_jint) {addl(dst, value) ; return; }
  if (value <  0) { decrementl(dst, -value); return; }
  if (value == 0) {                        ; return; }
  if (value == 1 && UseIncDec) { incl(dst) ; return; }
  /* else */      { addl(dst, value)       ; return; }
}

void MacroAssembler::jump(AddressLiteral dst) {
  if (reachable(dst)) {
    jmp_literal(dst.target(), dst.rspec());
  } else {
    lea(rscratch1, dst);
    jmp(rscratch1);
  }
}

void MacroAssembler::jump_cc(Condition cc, AddressLiteral dst) {
  if (reachable(dst)) {
    InstructionMark im(this);
    relocate(dst.reloc());
    const int short_size = 2;
    const int long_size = 6;
    int offs = (intptr_t)dst.target() - ((intptr_t)pc());
    if (dst.reloc() == relocInfo::none && is8bit(offs - short_size)) {
      // 0111 tttn #8-bit disp
      emit_int8(0x70 | cc);
      emit_int8((offs - short_size) & 0xFF);
    } else {
      // 0000 1111 1000 tttn #32-bit disp
      emit_int8(0x0F);
      emit_int8((unsigned char)(0x80 | cc));
      emit_int32(offs - long_size);
    }
  } else {
#ifdef ASSERT
    warning("reversing conditional branch");
#endif /* ASSERT */
    Label skip;
    jccb(reverse[cc], skip);
    lea(rscratch1, dst);
    Assembler::jmp(rscratch1);
    bind(skip);
  }
}

void MacroAssembler::ldmxcsr(AddressLiteral src) {
  if (reachable(src)) {
    Assembler::ldmxcsr(as_Address(src));
  } else {
    lea(rscratch1, src);
    Assembler::ldmxcsr(Address(rscratch1, 0));
  }
}

int MacroAssembler::load_signed_byte(Register dst, Address src) {
  int off;
  if (LP64_ONLY(true ||) VM_Version::is_P6()) {
    off = offset();
    movsbl(dst, src); // movsxb
  } else {
    off = load_unsigned_byte(dst, src);
    shll(dst, 24);
    sarl(dst, 24);
  }
  return off;
}

// Note: load_signed_short used to be called load_signed_word.
// Although the 'w' in x86 opcodes refers to the term "word" in the assembler
// manual, which means 16 bits, that usage is found nowhere in HotSpot code.
// The term "word" in HotSpot means a 32- or 64-bit machine word.
int MacroAssembler::load_signed_short(Register dst, Address src) {
  int off;
  if (LP64_ONLY(true ||) VM_Version::is_P6()) {
    // This is dubious to me since it seems safe to do a signed 16 => 64 bit
    // version but this is what 64bit has always done. This seems to imply
    // that users are only using 32bits worth.
    off = offset();
    movswl(dst, src); // movsxw
  } else {
    off = load_unsigned_short(dst, src);
    shll(dst, 16);
    sarl(dst, 16);
  }
  return off;
}

int MacroAssembler::load_unsigned_byte(Register dst, Address src) {
  // According to Intel Doc. AP-526, "Zero-Extension of Short", p.16,
  // and "3.9 Partial Register Penalties", p. 22).
  int off;
  if (LP64_ONLY(true || ) VM_Version::is_P6() || src.uses(dst)) {
    off = offset();
    movzbl(dst, src); // movzxb
  } else {
    xorl(dst, dst);
    off = offset();
    movb(dst, src);
  }
  return off;
}

// Note: load_unsigned_short used to be called load_unsigned_word.
int MacroAssembler::load_unsigned_short(Register dst, Address src) {
  // According to Intel Doc. AP-526, "Zero-Extension of Short", p.16,
  // and "3.9 Partial Register Penalties", p. 22).
  int off;
  if (LP64_ONLY(true ||) VM_Version::is_P6() || src.uses(dst)) {
    off = offset();
    movzwl(dst, src); // movzxw
  } else {
    xorl(dst, dst);
    off = offset();
    movw(dst, src);
  }
  return off;
}

void MacroAssembler::load_sized_value(Register dst, Address src, size_t size_in_bytes, bool is_signed, Register dst2) {
  switch (size_in_bytes) {
#ifndef _LP64
  case  8:
    assert(dst2 != noreg, "second dest register required");
    movl(dst,  src);
    movl(dst2, src.plus_disp(BytesPerInt));
    break;
#else
  case  8:  movq(dst, src); break;
#endif
  case  4:  movl(dst, src); break;
  case  2:  is_signed ? load_signed_short(dst, src) : load_unsigned_short(dst, src); break;
  case  1:  is_signed ? load_signed_byte( dst, src) : load_unsigned_byte( dst, src); break;
  default:  ShouldNotReachHere();
  }
}

void MacroAssembler::store_sized_value(Address dst, Register src, size_t size_in_bytes, Register src2) {
  switch (size_in_bytes) {
#ifndef _LP64
  case  8:
    assert(src2 != noreg, "second source register required");
    movl(dst,                        src);
    movl(dst.plus_disp(BytesPerInt), src2);
    break;
#else
  case  8:  movq(dst, src); break;
#endif
  case  4:  movl(dst, src); break;
  case  2:  movw(dst, src); break;
  case  1:  movb(dst, src); break;
  default:  ShouldNotReachHere();
  }
}

void MacroAssembler::mov32(AddressLiteral dst, Register src) {
  if (reachable(dst)) {
    movl(as_Address(dst), src);
  } else {
    lea(rscratch1, dst);
    movl(Address(rscratch1, 0), src);
  }
}

void MacroAssembler::mov32(Register dst, AddressLiteral src) {
  if (reachable(src)) {
    movl(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    movl(dst, Address(rscratch1, 0));
  }
}

// C++ bool manipulation

void MacroAssembler::movbool(Register dst, Address src) {
  if(sizeof(bool) == 1)
    movb(dst, src);
  else if(sizeof(bool) == 2)
    movw(dst, src);
  else if(sizeof(bool) == 4)
    movl(dst, src);
  else
    // unsupported
    ShouldNotReachHere();
}

void MacroAssembler::movbool(Address dst, bool boolconst) {
  if(sizeof(bool) == 1)
    movb(dst, (int) boolconst);
  else if(sizeof(bool) == 2)
    movw(dst, (int) boolconst);
  else if(sizeof(bool) == 4)
    movl(dst, (int) boolconst);
  else
    // unsupported
    ShouldNotReachHere();
}

void MacroAssembler::movbool(Address dst, Register src) {
  if(sizeof(bool) == 1)
    movb(dst, src);
  else if(sizeof(bool) == 2)
    movw(dst, src);
  else if(sizeof(bool) == 4)
    movl(dst, src);
  else
    // unsupported
    ShouldNotReachHere();
}

void MacroAssembler::movbyte(ArrayAddress dst, int src) {
  movb(as_Address(dst), src);
}

void MacroAssembler::movdl(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    movdl(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    movdl(dst, Address(rscratch1, 0));
  }
}

void MacroAssembler::movq(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    movq(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    movq(dst, Address(rscratch1, 0));
  }
}

#ifdef COMPILER2
void MacroAssembler::setvectmask(Register dst, Register src) {
  guarantee(PostLoopMultiversioning, "must be");
  Assembler::movl(dst, 1);
  Assembler::shlxl(dst, dst, src);
  Assembler::decl(dst);
  Assembler::kmovdl(k1, dst);
  Assembler::movl(dst, src);
}

void MacroAssembler::restorevectmask() {
  guarantee(PostLoopMultiversioning, "must be");
  Assembler::knotwl(k1, k0);
}
#endif // COMPILER2

void MacroAssembler::movdbl(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    if (UseXmmLoadAndClearUpper) {
      movsd (dst, as_Address(src));
    } else {
      movlpd(dst, as_Address(src));
    }
  } else {
    lea(rscratch1, src);
    if (UseXmmLoadAndClearUpper) {
      movsd (dst, Address(rscratch1, 0));
    } else {
      movlpd(dst, Address(rscratch1, 0));
    }
  }
}

void MacroAssembler::movflt(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    movss(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    movss(dst, Address(rscratch1, 0));
  }
}

void MacroAssembler::movptr(Register dst, Register src) {
  LP64_ONLY(movq(dst, src)) NOT_LP64(movl(dst, src));
}

void MacroAssembler::movptr(Register dst, Address src) {
  LP64_ONLY(movq(dst, src)) NOT_LP64(movl(dst, src));
}

// src should NEVER be a real pointer. Use AddressLiteral for true pointers
void MacroAssembler::movptr(Register dst, intptr_t src) {
  LP64_ONLY(mov64(dst, src)) NOT_LP64(movl(dst, src));
}

void MacroAssembler::movptr(Address dst, Register src) {
  LP64_ONLY(movq(dst, src)) NOT_LP64(movl(dst, src));
}

void MacroAssembler::movdqu(Address dst, XMMRegister src) {
    assert(((src->encoding() < 16) || VM_Version::supports_avx512vl()),"XMM register should be 0-15");
    Assembler::movdqu(dst, src);
}

void MacroAssembler::movdqu(XMMRegister dst, Address src) {
    assert(((dst->encoding() < 16) || VM_Version::supports_avx512vl()),"XMM register should be 0-15");
    Assembler::movdqu(dst, src);
}

void MacroAssembler::movdqu(XMMRegister dst, XMMRegister src) {
    assert(((dst->encoding() < 16  && src->encoding() < 16) || VM_Version::supports_avx512vl()),"XMM register should be 0-15");
    Assembler::movdqu(dst, src);
}

void MacroAssembler::movdqu(XMMRegister dst, AddressLiteral src, Register scratchReg) {
  if (reachable(src)) {
    movdqu(dst, as_Address(src));
  } else {
    lea(scratchReg, src);
    movdqu(dst, Address(scratchReg, 0));
  }
}

void MacroAssembler::vmovdqu(Address dst, XMMRegister src) {
    assert(((src->encoding() < 16) || VM_Version::supports_avx512vl()),"XMM register should be 0-15");
    Assembler::vmovdqu(dst, src);
}

void MacroAssembler::vmovdqu(XMMRegister dst, Address src) {
    assert(((dst->encoding() < 16) || VM_Version::supports_avx512vl()),"XMM register should be 0-15");
    Assembler::vmovdqu(dst, src);
}

void MacroAssembler::vmovdqu(XMMRegister dst, XMMRegister src) {
    assert(((dst->encoding() < 16  && src->encoding() < 16) || VM_Version::supports_avx512vl()),"XMM register should be 0-15");
    Assembler::vmovdqu(dst, src);
}

void MacroAssembler::vmovdqu(XMMRegister dst, AddressLiteral src, Register scratch_reg) {
  if (reachable(src)) {
    vmovdqu(dst, as_Address(src));
  }
  else {
    lea(scratch_reg, src);
    vmovdqu(dst, Address(scratch_reg, 0));
  }
}

void MacroAssembler::evmovdquq(XMMRegister dst, AddressLiteral src, int vector_len, Register rscratch) {
  if (reachable(src)) {
    Assembler::evmovdquq(dst, as_Address(src), vector_len);
  } else {
    lea(rscratch, src);
    Assembler::evmovdquq(dst, Address(rscratch, 0), vector_len);
  }
}

void MacroAssembler::movdqa(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    Assembler::movdqa(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    Assembler::movdqa(dst, Address(rscratch1, 0));
  }
}

void MacroAssembler::movsd(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    Assembler::movsd(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    Assembler::movsd(dst, Address(rscratch1, 0));
  }
}

void MacroAssembler::movss(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    Assembler::movss(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    Assembler::movss(dst, Address(rscratch1, 0));
  }
}

void MacroAssembler::mulsd(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    Assembler::mulsd(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    Assembler::mulsd(dst, Address(rscratch1, 0));
  }
}

void MacroAssembler::mulss(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    Assembler::mulss(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    Assembler::mulss(dst, Address(rscratch1, 0));
  }
}

void MacroAssembler::null_check(Register reg, int offset) {
  if (needs_explicit_null_check(offset)) {
    // provoke OS NULL exception if reg = NULL by
    // accessing M[reg] w/o changing any (non-CC) registers
    // NOTE: cmpl is plenty here to provoke a segv
    cmpptr(rax, Address(reg, 0));
    // Note: should probably use testl(rax, Address(reg, 0));
    //       may be shorter code (however, this version of
    //       testl needs to be implemented first)
  } else {
    // nothing to do, (later) access of M[reg + offset]
    // will provoke OS NULL exception if reg = NULL
  }
}

void MacroAssembler::os_breakpoint() {
  // instead of directly emitting a breakpoint, call os:breakpoint for better debugability
  // (e.g., MSVC can't call ps() otherwise)
  call(RuntimeAddress(CAST_FROM_FN_PTR(address, os::breakpoint)));
}

void MacroAssembler::unimplemented(const char* what) {
  const char* buf = NULL;
  {
    ResourceMark rm;
    stringStream ss;
    ss.print("unimplemented: %s", what);
    buf = code_string(ss.as_string());
  }
  stop(buf);
}

#ifdef _LP64
#define XSTATE_BV 0x200
#endif

void MacroAssembler::pop_CPU_state() {
  pop_FPU_state();
  pop_IU_state();
}

void MacroAssembler::pop_FPU_state() {
#ifndef _LP64
  frstor(Address(rsp, 0));
#else
  fxrstor(Address(rsp, 0));
#endif
  addptr(rsp, FPUStateSizeInWords * wordSize);
}

void MacroAssembler::pop_IU_state() {
  popa();
  LP64_ONLY(addq(rsp, 8));
  popf();
}

// Save Integer and Float state
// Warning: Stack must be 16 byte aligned (64bit)
void MacroAssembler::push_CPU_state() {
  push_IU_state();
  push_FPU_state();
}

void MacroAssembler::push_FPU_state() {
  subptr(rsp, FPUStateSizeInWords * wordSize);
#ifndef _LP64
  fnsave(Address(rsp, 0));
  fwait();
#else
  fxsave(Address(rsp, 0));
#endif // LP64
}

void MacroAssembler::push_IU_state() {
  // Push flags first because pusha kills them
  pushf();
  // Make sure rsp stays 16-byte aligned
  LP64_ONLY(subq(rsp, 8));
  pusha();
}

void MacroAssembler::reset_last_Java_frame(Register java_thread, bool clear_fp) { // determine java_thread register
  if (!java_thread->is_valid()) {
    java_thread = rdi;
    get_thread(java_thread);
  }
  // we must set sp to zero to clear frame
  movptr(Address(java_thread, JavaThread::last_Java_sp_offset()), NULL_WORD);
  if (clear_fp) {
    movptr(Address(java_thread, JavaThread::last_Java_fp_offset()), NULL_WORD);
  }

  // Always clear the pc because it could have been set by make_walkable()
  movptr(Address(java_thread, JavaThread::last_Java_pc_offset()), NULL_WORD);

  vzeroupper();
}

void MacroAssembler::restore_rax(Register tmp) {
  if (tmp == noreg) pop(rax);
  else if (tmp != rax) mov(rax, tmp);
}

void MacroAssembler::round_to(Register reg, int modulus) {
  addptr(reg, modulus - 1);
  andptr(reg, -modulus);
}

void MacroAssembler::save_rax(Register tmp) {
  if (tmp == noreg) push(rax);
  else if (tmp != rax) mov(tmp, rax);
}

// Write serialization page so VM thread can do a pseudo remote membar.
// We use the current thread pointer to calculate a thread specific
// offset to write to within the page. This minimizes bus traffic
// due to cache line collision.
void MacroAssembler::serialize_memory(Register thread, Register tmp) {
  movl(tmp, thread);
  shrl(tmp, os::get_serialize_page_shift_count());
  andl(tmp, (os::vm_page_size() - sizeof(int)));

  Address index(noreg, tmp, Address::times_1);
  ExternalAddress page(os::get_memory_serialize_page());

  // Size of store must match masking code above
  movl(as_Address(ArrayAddress(page, index)), tmp);
}

void MacroAssembler::safepoint_poll(Label& slow_path, Register thread_reg, Register temp_reg) {
  if (SafepointMechanism::uses_thread_local_poll()) {
#ifdef _LP64
    assert(thread_reg == r15_thread, "should be");
#else
    if (thread_reg == noreg) {
      thread_reg = temp_reg;
      get_thread(thread_reg);
    }
#endif
    testb(Address(thread_reg, Thread::polling_page_offset()), SafepointMechanism::poll_bit());
    jcc(Assembler::notZero, slow_path); // handshake bit set implies poll
  } else {
    cmp32(ExternalAddress(SafepointSynchronize::address_of_state()),
        SafepointSynchronize::_not_synchronized);
    jcc(Assembler::notEqual, slow_path);
  }
}

// Calls to C land
//
// When entering C land, the rbp, & rsp of the last Java frame have to be recorded
// in the (thread-local) JavaThread object. When leaving C land, the last Java fp
// has to be reset to 0. This is required to allow proper stack traversal.
void MacroAssembler::set_last_Java_frame(Register java_thread,
                                         Register last_java_sp,
                                         Register last_java_fp,
                                         address  last_java_pc) {
  vzeroupper();
  // determine java_thread register
  if (!java_thread->is_valid()) {
    java_thread = rdi;
    get_thread(java_thread);
  }
  // determine last_java_sp register
  if (!last_java_sp->is_valid()) {
    last_java_sp = rsp;
  }

  // last_java_fp is optional

  if (last_java_fp->is_valid()) {
    movptr(Address(java_thread, JavaThread::last_Java_fp_offset()), last_java_fp);
  }

  // last_java_pc is optional

  if (last_java_pc != NULL) {
    lea(Address(java_thread,
                 JavaThread::frame_anchor_offset() + JavaFrameAnchor::last_Java_pc_offset()),
        InternalAddress(last_java_pc));

  }
  movptr(Address(java_thread, JavaThread::last_Java_sp_offset()), last_java_sp);
}

void MacroAssembler::shlptr(Register dst, int imm8) {
  LP64_ONLY(shlq(dst, imm8)) NOT_LP64(shll(dst, imm8));
}

void MacroAssembler::shrptr(Register dst, int imm8) {
  LP64_ONLY(shrq(dst, imm8)) NOT_LP64(shrl(dst, imm8));
}

void MacroAssembler::sign_extend_byte(Register reg) {
  if (LP64_ONLY(true ||) (VM_Version::is_P6() && reg->has_byte_register())) {
    movsbl(reg, reg); // movsxb
  } else {
    shll(reg, 24);
    sarl(reg, 24);
  }
}

void MacroAssembler::sign_extend_short(Register reg) {
  if (LP64_ONLY(true ||) VM_Version::is_P6()) {
    movswl(reg, reg); // movsxw
  } else {
    shll(reg, 16);
    sarl(reg, 16);
  }
}

void MacroAssembler::testl(Register dst, AddressLiteral src) {
  assert(reachable(src), "Address should be reachable");
  testl(dst, as_Address(src));
}

void MacroAssembler::pcmpeqb(XMMRegister dst, XMMRegister src) {
  assert(((dst->encoding() < 16 && src->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::pcmpeqb(dst, src);
}

void MacroAssembler::pcmpeqw(XMMRegister dst, XMMRegister src) {
  assert(((dst->encoding() < 16 && src->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::pcmpeqw(dst, src);
}

void MacroAssembler::pcmpestri(XMMRegister dst, Address src, int imm8) {
  assert((dst->encoding() < 16),"XMM register should be 0-15");
  Assembler::pcmpestri(dst, src, imm8);
}

void MacroAssembler::pcmpestri(XMMRegister dst, XMMRegister src, int imm8) {
  assert((dst->encoding() < 16 && src->encoding() < 16),"XMM register should be 0-15");
  Assembler::pcmpestri(dst, src, imm8);
}

void MacroAssembler::pmovzxbw(XMMRegister dst, XMMRegister src) {
  assert(((dst->encoding() < 16 && src->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::pmovzxbw(dst, src);
}

void MacroAssembler::pmovzxbw(XMMRegister dst, Address src) {
  assert(((dst->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::pmovzxbw(dst, src);
}

void MacroAssembler::pmovmskb(Register dst, XMMRegister src) {
  assert((src->encoding() < 16),"XMM register should be 0-15");
  Assembler::pmovmskb(dst, src);
}

void MacroAssembler::ptest(XMMRegister dst, XMMRegister src) {
  assert((dst->encoding() < 16 && src->encoding() < 16),"XMM register should be 0-15");
  Assembler::ptest(dst, src);
}

void MacroAssembler::sqrtsd(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    Assembler::sqrtsd(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    Assembler::sqrtsd(dst, Address(rscratch1, 0));
  }
}

void MacroAssembler::sqrtss(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    Assembler::sqrtss(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    Assembler::sqrtss(dst, Address(rscratch1, 0));
  }
}

void MacroAssembler::subsd(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    Assembler::subsd(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    Assembler::subsd(dst, Address(rscratch1, 0));
  }
}

void MacroAssembler::subss(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    Assembler::subss(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    Assembler::subss(dst, Address(rscratch1, 0));
  }
}

void MacroAssembler::ucomisd(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    Assembler::ucomisd(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    Assembler::ucomisd(dst, Address(rscratch1, 0));
  }
}

void MacroAssembler::ucomiss(XMMRegister dst, AddressLiteral src) {
  if (reachable(src)) {
    Assembler::ucomiss(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    Assembler::ucomiss(dst, Address(rscratch1, 0));
  }
}

void MacroAssembler::xorpd(XMMRegister dst, AddressLiteral src, Register scratch_reg) {
  // Used in sign-bit flipping with aligned address.
  assert((UseAVX > 0) || (((intptr_t)src.target() & 15) == 0), "SSE mode requires address alignment 16 bytes");
  if (reachable(src)) {
    Assembler::xorpd(dst, as_Address(src));
  } else {
    lea(scratch_reg, src);
    Assembler::xorpd(dst, Address(scratch_reg, 0));
  }
}

void MacroAssembler::xorpd(XMMRegister dst, XMMRegister src) {
  if (UseAVX > 2 && !VM_Version::supports_avx512dq() && (dst->encoding() == src->encoding())) {
    Assembler::vpxor(dst, dst, src, Assembler::AVX_512bit);
  }
  else {
    Assembler::xorpd(dst, src);
  }
}

void MacroAssembler::xorps(XMMRegister dst, XMMRegister src) {
  if (UseAVX > 2 && !VM_Version::supports_avx512dq() && (dst->encoding() == src->encoding())) {
    Assembler::vpxor(dst, dst, src, Assembler::AVX_512bit);
  } else {
    Assembler::xorps(dst, src);
  }
}

void MacroAssembler::xorps(XMMRegister dst, AddressLiteral src, Register scratch_reg) {
  // Used in sign-bit flipping with aligned address.
  assert((UseAVX > 0) || (((intptr_t)src.target() & 15) == 0), "SSE mode requires address alignment 16 bytes");
  if (reachable(src)) {
    Assembler::xorps(dst, as_Address(src));
  } else {
    lea(scratch_reg, src);
    Assembler::xorps(dst, Address(scratch_reg, 0));
  }
}

void MacroAssembler::pshufb(XMMRegister dst, AddressLiteral src) {
  // Used in sign-bit flipping with aligned address.
  bool aligned_adr = (((intptr_t)src.target() & 15) == 0);
  assert((UseAVX > 0) || aligned_adr, "SSE mode requires address alignment 16 bytes");
  if (reachable(src)) {
    Assembler::pshufb(dst, as_Address(src));
  } else {
    lea(rscratch1, src);
    Assembler::pshufb(dst, Address(rscratch1, 0));
  }
}

// AVX 3-operands instructions

void MacroAssembler::vaddsd(XMMRegister dst, XMMRegister nds, AddressLiteral src) {
  if (reachable(src)) {
    vaddsd(dst, nds, as_Address(src));
  } else {
    lea(rscratch1, src);
    vaddsd(dst, nds, Address(rscratch1, 0));
  }
}

void MacroAssembler::vaddss(XMMRegister dst, XMMRegister nds, AddressLiteral src) {
  if (reachable(src)) {
    vaddss(dst, nds, as_Address(src));
  } else {
    lea(rscratch1, src);
    vaddss(dst, nds, Address(rscratch1, 0));
  }
}

void MacroAssembler::vpaddd(XMMRegister dst, XMMRegister nds, AddressLiteral src, int vector_len, Register rscratch) {
  assert(UseAVX > 0, "requires some form of AVX");
  if (reachable(src)) {
    Assembler::vpaddd(dst, nds, as_Address(src), vector_len);
  } else {
    lea(rscratch, src);
    Assembler::vpaddd(dst, nds, Address(rscratch, 0), vector_len);
  }
}

void MacroAssembler::vabsss(XMMRegister dst, XMMRegister nds, XMMRegister src, AddressLiteral negate_field, int vector_len) {
  assert(((dst->encoding() < 16 && src->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vldq()),"XMM register should be 0-15");
  vandps(dst, nds, negate_field, vector_len);
}

void MacroAssembler::vabssd(XMMRegister dst, XMMRegister nds, XMMRegister src, AddressLiteral negate_field, int vector_len) {
  assert(((dst->encoding() < 16 && src->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vldq()),"XMM register should be 0-15");
  vandpd(dst, nds, negate_field, vector_len);
}

void MacroAssembler::vpaddb(XMMRegister dst, XMMRegister nds, XMMRegister src, int vector_len) {
  assert(((dst->encoding() < 16 && src->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::vpaddb(dst, nds, src, vector_len);
}

void MacroAssembler::vpaddb(XMMRegister dst, XMMRegister nds, Address src, int vector_len) {
  assert(((dst->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vlbw()), "XMM register should be 0-15");
  Assembler::vpaddb(dst, nds, src, vector_len);
}

void MacroAssembler::vpaddw(XMMRegister dst, XMMRegister nds, XMMRegister src, int vector_len) {
  assert(((dst->encoding() < 16 && src->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::vpaddw(dst, nds, src, vector_len);
}

void MacroAssembler::vpaddw(XMMRegister dst, XMMRegister nds, Address src, int vector_len) {
  assert(((dst->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::vpaddw(dst, nds, src, vector_len);
}

void MacroAssembler::vpand(XMMRegister dst, XMMRegister nds, AddressLiteral src, int vector_len, Register scratch_reg) {
  if (reachable(src)) {
    Assembler::vpand(dst, nds, as_Address(src), vector_len);
  } else {
    lea(scratch_reg, src);
    Assembler::vpand(dst, nds, Address(scratch_reg, 0), vector_len);
  }
}

void MacroAssembler::vpbroadcastw(XMMRegister dst, XMMRegister src, int vector_len) {
  assert(((dst->encoding() < 16 && src->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::vpbroadcastw(dst, src, vector_len);
}

void MacroAssembler::vpcmpeqb(XMMRegister dst, XMMRegister nds, XMMRegister src, int vector_len) {
  assert(((dst->encoding() < 16 && src->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::vpcmpeqb(dst, nds, src, vector_len);
}

void MacroAssembler::vpcmpeqw(XMMRegister dst, XMMRegister nds, XMMRegister src, int vector_len) {
  assert(((dst->encoding() < 16 && src->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::vpcmpeqw(dst, nds, src, vector_len);
}

void MacroAssembler::vpmovzxbw(XMMRegister dst, Address src, int vector_len) {
  assert(((dst->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::vpmovzxbw(dst, src, vector_len);
}

void MacroAssembler::vpmovmskb(Register dst, XMMRegister src) {
  assert((src->encoding() < 16),"XMM register should be 0-15");
  Assembler::vpmovmskb(dst, src);
}

void MacroAssembler::vpmullw(XMMRegister dst, XMMRegister nds, XMMRegister src, int vector_len) {
  assert(((dst->encoding() < 16 && src->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::vpmullw(dst, nds, src, vector_len);
}

void MacroAssembler::vpmullw(XMMRegister dst, XMMRegister nds, Address src, int vector_len) {
  assert(((dst->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::vpmullw(dst, nds, src, vector_len);
}

void MacroAssembler::vpsubb(XMMRegister dst, XMMRegister nds, XMMRegister src, int vector_len) {
  assert(((dst->encoding() < 16 && src->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::vpsubb(dst, nds, src, vector_len);
}

void MacroAssembler::vpsubb(XMMRegister dst, XMMRegister nds, Address src, int vector_len) {
  assert(((dst->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::vpsubb(dst, nds, src, vector_len);
}

void MacroAssembler::vpsubw(XMMRegister dst, XMMRegister nds, XMMRegister src, int vector_len) {
  assert(((dst->encoding() < 16 && src->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::vpsubw(dst, nds, src, vector_len);
}

void MacroAssembler::vpsubw(XMMRegister dst, XMMRegister nds, Address src, int vector_len) {
  assert(((dst->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::vpsubw(dst, nds, src, vector_len);
}

void MacroAssembler::vpsraw(XMMRegister dst, XMMRegister nds, XMMRegister shift, int vector_len) {
  assert(((dst->encoding() < 16 && shift->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::vpsraw(dst, nds, shift, vector_len);
}

void MacroAssembler::vpsraw(XMMRegister dst, XMMRegister nds, int shift, int vector_len) {
  assert(((dst->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::vpsraw(dst, nds, shift, vector_len);
}

void MacroAssembler::evpsraq(XMMRegister dst, XMMRegister nds, XMMRegister shift, int vector_len) {
  assert(UseAVX > 2,"");
  if (!VM_Version::supports_avx512vl() && vector_len < 2) {
     vector_len = 2;
  }
  Assembler::evpsraq(dst, nds, shift, vector_len);
}

void MacroAssembler::evpsraq(XMMRegister dst, XMMRegister nds, int shift, int vector_len) {
  assert(UseAVX > 2,"");
  if (!VM_Version::supports_avx512vl() && vector_len < 2) {
     vector_len = 2;
  }
  Assembler::evpsraq(dst, nds, shift, vector_len);
}

void MacroAssembler::vpsrlw(XMMRegister dst, XMMRegister nds, XMMRegister shift, int vector_len) {
  assert(((dst->encoding() < 16 && shift->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::vpsrlw(dst, nds, shift, vector_len);
}

void MacroAssembler::vpsrlw(XMMRegister dst, XMMRegister nds, int shift, int vector_len) {
  assert(((dst->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::vpsrlw(dst, nds, shift, vector_len);
}

void MacroAssembler::vpsllw(XMMRegister dst, XMMRegister nds, XMMRegister shift, int vector_len) {
  assert(((dst->encoding() < 16 && shift->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::vpsllw(dst, nds, shift, vector_len);
}

void MacroAssembler::vpsllw(XMMRegister dst, XMMRegister nds, int shift, int vector_len) {
  assert(((dst->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::vpsllw(dst, nds, shift, vector_len);
}

void MacroAssembler::vptest(XMMRegister dst, XMMRegister src) {
  assert((dst->encoding() < 16 && src->encoding() < 16),"XMM register should be 0-15");
  Assembler::vptest(dst, src);
}

void MacroAssembler::punpcklbw(XMMRegister dst, XMMRegister src) {
  assert(((dst->encoding() < 16 && src->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::punpcklbw(dst, src);
}

void MacroAssembler::pshufd(XMMRegister dst, Address src, int mode) {
  assert(((dst->encoding() < 16) || VM_Version::supports_avx512vl()),"XMM register should be 0-15");
  Assembler::pshufd(dst, src, mode);
}

void MacroAssembler::pshuflw(XMMRegister dst, XMMRegister src, int mode) {
  assert(((dst->encoding() < 16 && src->encoding() < 16) || VM_Version::supports_avx512vlbw()),"XMM register should be 0-15");
  Assembler::pshuflw(dst, src, mode);
}

void MacroAssembler::vandpd(XMMRegister dst, XMMRegister nds, AddressLiteral src, int vector_len, Register scratch_reg) {
  if (reachable(src)) {
    vandpd(dst, nds, as_Address(src), vector_len);
  } else {
    lea(scratch_reg, src);
    vandpd(dst, nds, Address(scratch_reg, 0), vector_len);
  }
}

void MacroAssembler::vandps(XMMRegister dst, XMMRegister nds, AddressLiteral src, int vector_len, Register scratch_reg) {
  if (reachable(src)) {
    vandps(dst, nds, as_Address(src), vector_len);
  } else {
    lea(scratch_reg, src);
    vandps(dst, nds, Address(scratch_reg, 0), vector_len);
  }
}

void MacroAssembler::vdivsd(XMMRegister dst, XMMRegister nds, AddressLiteral src) {
  if (reachable(src)) {
    vdivsd(dst, nds, as_Address(src));
  } else {
    lea(rscratch1, src);
    vdivsd(dst, nds, Address(rscratch1, 0));
  }
}

void MacroAssembler::vdivss(XMMRegister dst, XMMRegister nds, AddressLiteral src) {
  if (reachable(src)) {
    vdivss(dst, nds, as_Address(src));
  } else {
    lea(rscratch1, src);
    vdivss(dst, nds, Address(rscratch1, 0));
  }
}

void MacroAssembler::vmulsd(XMMRegister dst, XMMRegister nds, AddressLiteral src) {
  if (reachable(src)) {
    vmulsd(dst, nds, as_Address(src));
  } else {
    lea(rscratch1, src);
    vmulsd(dst, nds, Address(rscratch1, 0));
  }
}

void MacroAssembler::vmulss(XMMRegister dst, XMMRegister nds, AddressLiteral src) {
  if (reachable(src)) {
    vmulss(dst, nds, as_Address(src));
  } else {
    lea(rscratch1, src);
    vmulss(dst, nds, Address(rscratch1, 0));
  }
}

void MacroAssembler::vsubsd(XMMRegister dst, XMMRegister nds, AddressLiteral src) {
  if (reachable(src)) {
    vsubsd(dst, nds, as_Address(src));
  } else {
    lea(rscratch1, src);
    vsubsd(dst, nds, Address(rscratch1, 0));
  }
}

void MacroAssembler::vsubss(XMMRegister dst, XMMRegister nds, AddressLiteral src) {
  if (reachable(src)) {
    vsubss(dst, nds, as_Address(src));
  } else {
    lea(rscratch1, src);
    vsubss(dst, nds, Address(rscratch1, 0));
  }
}

void MacroAssembler::vnegatess(XMMRegister dst, XMMRegister nds, AddressLiteral src) {
  assert(((dst->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vldq()),"XMM register should be 0-15");
  vxorps(dst, nds, src, Assembler::AVX_128bit);
}

void MacroAssembler::vnegatesd(XMMRegister dst, XMMRegister nds, AddressLiteral src) {
  assert(((dst->encoding() < 16 && nds->encoding() < 16) || VM_Version::supports_avx512vldq()),"XMM register should be 0-15");
  vxorpd(dst, nds, src, Assembler::AVX_128bit);
}

void MacroAssembler::vxorpd(XMMRegister dst, XMMRegister nds, AddressLiteral src, int vector_len, Register scratch_reg) {
  if (reachable(src)) {
    vxorpd(dst, nds, as_Address(src), vector_len);
  } else {
    lea(scratch_reg, src);
    vxorpd(dst, nds, Address(scratch_reg, 0), vector_len);
  }
}

void MacroAssembler::vxorps(XMMRegister dst, XMMRegister nds, AddressLiteral src, int vector_len, Register scratch_reg) {
  if (reachable(src)) {
    vxorps(dst, nds, as_Address(src), vector_len);
  } else {
    lea(scratch_reg, src);
    vxorps(dst, nds, Address(scratch_reg, 0), vector_len);
  }
}

void MacroAssembler::vpxor(XMMRegister dst, XMMRegister nds, AddressLiteral src, int vector_len, Register scratch_reg) {
  if (UseAVX > 1 || (vector_len < 1)) {
    if (reachable(src)) {
      Assembler::vpxor(dst, nds, as_Address(src), vector_len);
    } else {
      lea(scratch_reg, src);
      Assembler::vpxor(dst, nds, Address(scratch_reg, 0), vector_len);
    }
  }
  else {
    MacroAssembler::vxorpd(dst, nds, src, vector_len, scratch_reg);
  }
}

//-------------------------------------------------------------------------------------------
#ifdef COMPILER2
// Generic instructions support for use in .ad files C2 code generation

void MacroAssembler::vabsnegd(int opcode, XMMRegister dst, Register scr) {
  if (opcode == Op_AbsVD) {
    andpd(dst, ExternalAddress(StubRoutines::x86::vector_double_sign_mask()), scr);
  } else {
    assert((opcode == Op_NegVD),"opcode should be Op_NegD");
    xorpd(dst, ExternalAddress(StubRoutines::x86::vector_double_sign_flip()), scr);
  }
}

void MacroAssembler::vabsnegd(int opcode, XMMRegister dst, XMMRegister src, int vector_len, Register scr) {
  if (opcode == Op_AbsVD) {
    vandpd(dst, src, ExternalAddress(StubRoutines::x86::vector_double_sign_mask()), vector_len, scr);
  } else {
    assert((opcode == Op_NegVD),"opcode should be Op_NegD");
    vxorpd(dst, src, ExternalAddress(StubRoutines::x86::vector_double_sign_flip()), vector_len, scr);
  }
}

void MacroAssembler::vabsnegf(int opcode, XMMRegister dst, Register scr) {
  if (opcode == Op_AbsVF) {
    andps(dst, ExternalAddress(StubRoutines::x86::vector_float_sign_mask()), scr);
  } else {
    assert((opcode == Op_NegVF),"opcode should be Op_NegF");
    xorps(dst, ExternalAddress(StubRoutines::x86::vector_float_sign_flip()), scr);
  }
}

void MacroAssembler::vabsnegf(int opcode, XMMRegister dst, XMMRegister src, int vector_len, Register scr) {
  if (opcode == Op_AbsVF) {
    vandps(dst, src, ExternalAddress(StubRoutines::x86::vector_float_sign_mask()), vector_len, scr);
  } else {
    assert((opcode == Op_NegVF),"opcode should be Op_NegF");
    vxorps(dst, src, ExternalAddress(StubRoutines::x86::vector_float_sign_flip()), vector_len, scr);
  }
}

void MacroAssembler::vextendbw(bool sign, XMMRegister dst, XMMRegister src) {
  if (sign) {
    pmovsxbw(dst, src);
  } else {
    pmovzxbw(dst, src);
  }
}

void MacroAssembler::vextendbw(bool sign, XMMRegister dst, XMMRegister src, int vector_len) {
  if (sign) {
    vpmovsxbw(dst, src, vector_len);
  } else {
    vpmovzxbw(dst, src, vector_len);
  }
}

void MacroAssembler::vshiftd(int opcode, XMMRegister dst, XMMRegister src) {
  if (opcode == Op_RShiftVI) {
    psrad(dst, src);
  } else if (opcode == Op_LShiftVI) {
    pslld(dst, src);
  } else {
    assert((opcode == Op_URShiftVI),"opcode should be Op_URShiftVI");
    psrld(dst, src);
  }
}

void MacroAssembler::vshiftd(int opcode, XMMRegister dst, XMMRegister nds, XMMRegister src, int vector_len) {
  if (opcode == Op_RShiftVI) {
    vpsrad(dst, nds, src, vector_len);
  } else if (opcode == Op_LShiftVI) {
    vpslld(dst, nds, src, vector_len);
  } else {
    assert((opcode == Op_URShiftVI),"opcode should be Op_URShiftVI");
    vpsrld(dst, nds, src, vector_len);
  }
}

void MacroAssembler::vshiftw(int opcode, XMMRegister dst, XMMRegister src) {
  if ((opcode == Op_RShiftVS) || (opcode == Op_RShiftVB)) {
    psraw(dst, src);
  } else if ((opcode == Op_LShiftVS) || (opcode == Op_LShiftVB)) {
    psllw(dst, src);
  } else {
    assert(((opcode == Op_URShiftVS) || (opcode == Op_URShiftVB)),"opcode should be one of Op_URShiftVS or Op_URShiftVB");
    psrlw(dst, src);
  }
}

void MacroAssembler::vshiftw(int opcode, XMMRegister dst, XMMRegister nds, XMMRegister src, int vector_len) {
  if ((opcode == Op_RShiftVS) || (opcode == Op_RShiftVB)) {
    vpsraw(dst, nds, src, vector_len);
  } else if ((opcode == Op_LShiftVS) || (opcode == Op_LShiftVB)) {
    vpsllw(dst, nds, src, vector_len);
  } else {
    assert(((opcode == Op_URShiftVS) || (opcode == Op_URShiftVB)),"opcode should be one of Op_URShiftVS or Op_URShiftVB");
    vpsrlw(dst, nds, src, vector_len);
  }
}

void MacroAssembler::vshiftq(int opcode, XMMRegister dst, XMMRegister src) {
  if (opcode == Op_RShiftVL) {
    psrlq(dst, src);  // using srl to implement sra on pre-avs512 systems
  } else if (opcode == Op_LShiftVL) {
    psllq(dst, src);
  } else {
    assert((opcode == Op_URShiftVL),"opcode should be Op_URShiftVL");
    psrlq(dst, src);
  }
}

void MacroAssembler::vshiftq(int opcode, XMMRegister dst, XMMRegister nds, XMMRegister src, int vector_len) {
  if (opcode == Op_RShiftVL) {
    evpsraq(dst, nds, src, vector_len);
  } else if (opcode == Op_LShiftVL) {
    vpsllq(dst, nds, src, vector_len);
  } else {
    assert((opcode == Op_URShiftVL),"opcode should be Op_URShiftVL");
    vpsrlq(dst, nds, src, vector_len);
  }
}
#endif
//-------------------------------------------------------------------------------------------

void MacroAssembler::clear_jweak_tag(Register possibly_jweak) {
  const int32_t inverted_jweak_mask = ~static_cast<int32_t>(JNIHandles::weak_tag_mask);
  STATIC_ASSERT(inverted_jweak_mask == -2); // otherwise check this code
  // The inverted mask is sign-extended
  andptr(possibly_jweak, inverted_jweak_mask);
}

void MacroAssembler::resolve_jobject(Register value,
                                     Register thread,
                                     Register tmp) {
  assert_different_registers(value, thread, tmp);
  Label done, not_weak;
  testptr(value, value);
  jcc(Assembler::zero, done);                // Use NULL as-is.
  testptr(value, JNIHandles::weak_tag_mask); // Test for jweak tag.
  jcc(Assembler::zero, not_weak);
  // Resolve jweak.
  access_load_at(T_OBJECT, IN_NATIVE | ON_PHANTOM_OOP_REF,
                 value, Address(value, -JNIHandles::weak_tag_value), tmp, thread);
  verify_oop(value);
  jmp(done);
  bind(not_weak);
  // Resolve (untagged) jobject.
  access_load_at(T_OBJECT, IN_NATIVE, value, Address(value, 0), tmp, thread);
  verify_oop(value);
  bind(done);
}

void MacroAssembler::subptr(Register dst, int32_t imm32) {
  LP64_ONLY(subq(dst, imm32)) NOT_LP64(subl(dst, imm32));
}

// Force generation of a 4 byte immediate value even if it fits into 8bit
void MacroAssembler::subptr_imm32(Register dst, int32_t imm32) {
  LP64_ONLY(subq_imm32(dst, imm32)) NOT_LP64(subl_imm32(dst, imm32));
}

void MacroAssembler::subptr(Register dst, Register src) {
  LP64_ONLY(subq(dst, src)) NOT_LP64(subl(dst, src));
}

// C++ bool manipulation
void MacroAssembler::testbool(Register dst) {
  if(sizeof(bool) == 1)
    testb(dst, 0xff);
  else if(sizeof(bool) == 2) {
    // testw implementation needed for two byte bools
    ShouldNotReachHere();
  } else if(sizeof(bool) == 4)
    testl(dst, dst);
  else
    // unsupported
    ShouldNotReachHere();
}

void MacroAssembler::testptr(Register dst, Register src) {
  LP64_ONLY(testq(dst, src)) NOT_LP64(testl(dst, src));
}

// Defines obj, preserves var_size_in_bytes, okay for t2 == var_size_in_bytes.
void MacroAssembler::tlab_allocate(Register thread, Register obj,
                                   Register var_size_in_bytes,
                                   int con_size_in_bytes,
                                   Register t1,
                                   Register t2,
                                   Label& slow_case) {
  BarrierSetAssembler* bs = BarrierSet::barrier_set()->barrier_set_assembler();
  bs->tlab_allocate(this, thread, obj, var_size_in_bytes, con_size_in_bytes, t1, t2, slow_case);
}

// Defines obj, preserves var_size_in_bytes
void MacroAssembler::eden_allocate(Register thread, Register obj,
                                   Register var_size_in_bytes,
                                   int con_size_in_bytes,
                                   Register t1,
                                   Label& slow_case) {
  BarrierSetAssembler* bs = BarrierSet::barrier_set()->barrier_set_assembler();
  bs->eden_allocate(this, thread, obj, var_size_in_bytes, con_size_in_bytes, t1, slow_case);
}

// Preserves the contents of address, destroys the contents length_in_bytes and temp.
void MacroAssembler::zero_memory(Register address, Register length_in_bytes, int offset_in_bytes, Register temp) {
  assert(address != length_in_bytes && address != temp && temp != length_in_bytes, "registers must be different");
  assert((offset_in_bytes & (BytesPerWord - 1)) == 0, "offset must be a multiple of BytesPerWord");
  Label done;

  testptr(length_in_bytes, length_in_bytes);
  jcc(Assembler::zero, done);

  // initialize topmost word, divide index by 2, check if odd and test if zero
  // note: for the remaining code to work, index must be a multiple of BytesPerWord
#ifdef ASSERT
  {
    Label L;
    testptr(length_in_bytes, BytesPerWord - 1);
    jcc(Assembler::zero, L);
    stop("length must be a multiple of BytesPerWord");
    bind(L);
  }
#endif
  Register index = length_in_bytes;
  xorptr(temp, temp);    // use _zero reg to clear memory (shorter code)
  if (UseIncDec) {
    shrptr(index, 3);  // divide by 8/16 and set carry flag if bit 2 was set
  } else {
    shrptr(index, 2);  // use 2 instructions to avoid partial flag stall
    shrptr(index, 1);
  }
#ifndef _LP64
  // index could have not been a multiple of 8 (i.e., bit 2 was set)
  {
    Label even;
    // note: if index was a multiple of 8, then it cannot
    //       be 0 now otherwise it must have been 0 before
    //       => if it is even, we don't need to check for 0 again
    jcc(Assembler::carryClear, even);
    // clear topmost word (no jump would be needed if conditional assignment worked here)
    movptr(Address(address, index, Address::times_8, offset_in_bytes - 0*BytesPerWord), temp);
    // index could be 0 now, must check again
    jcc(Assembler::zero, done);
    bind(even);
  }
#endif // !_LP64
  // initialize remaining object fields: index is a multiple of 2 now
  {
    Label loop;
    bind(loop);
    movptr(Address(address, index, Address::times_8, offset_in_bytes - 1*BytesPerWord), temp);
    NOT_LP64(movptr(Address(address, index, Address::times_8, offset_in_bytes - 2*BytesPerWord), temp);)
    decrement(index);
    jcc(Assembler::notZero, loop);
  }

  bind(done);
}

// Look up the method for a megamorphic invokeinterface call.
// The target method is determined by <intf_klass, itable_index>.
// The receiver klass is in recv_klass.
// On success, the result will be in method_result, and execution falls through.
// On failure, execution transfers to the given label.
void MacroAssembler::lookup_interface_method(Register recv_klass,
                                             Register intf_klass,
                                             RegisterOrConstant itable_index,
                                             Register method_result,
                                             Register scan_temp,
                                             Label& L_no_such_interface,
                                             bool return_method) {
  assert_different_registers(recv_klass, intf_klass, scan_temp);
  assert_different_registers(method_result, intf_klass, scan_temp);
  assert(recv_klass != method_result || !return_method,
         "recv_klass can be destroyed when method isn't needed");

  assert(itable_index.is_constant() || itable_index.as_register() == method_result,
         "caller must use same register for non-constant itable index as for method");

  // Compute start of first itableOffsetEntry (which is at the end of the vtable)
  int vtable_base = in_bytes(Klass::vtable_start_offset());
  int itentry_off = itableMethodEntry::method_offset_in_bytes();
  int scan_step   = itableOffsetEntry::size() * wordSize;
  int vte_size    = vtableEntry::size_in_bytes();
  Address::ScaleFactor times_vte_scale = Address::times_ptr;
  assert(vte_size == wordSize, "else adjust times_vte_scale");

  movl(scan_temp, Address(recv_klass, Klass::vtable_length_offset()));

  // %%% Could store the aligned, prescaled offset in the klassoop.
  lea(scan_temp, Address(recv_klass, scan_temp, times_vte_scale, vtable_base));

  if (return_method) {
    // Adjust recv_klass by scaled itable_index, so we can free itable_index.
    assert(itableMethodEntry::size() * wordSize == wordSize, "adjust the scaling in the code below");
    lea(recv_klass, Address(recv_klass, itable_index, Address::times_ptr, itentry_off));
  }

  // for (scan = klass->itable(); scan->interface() != NULL; scan += scan_step) {
  //   if (scan->interface() == intf) {
  //     result = (klass + scan->offset() + itable_index);
  //   }
  // }
  Label search, found_method;

  for (int peel = 1; peel >= 0; peel--) {
    movptr(method_result, Address(scan_temp, itableOffsetEntry::interface_offset_in_bytes()));
    cmpptr(intf_klass, method_result);

    if (peel) {
      jccb(Assembler::equal, found_method);
    } else {
      jccb(Assembler::notEqual, search);
      // (invert the test to fall through to found_method...)
    }

    if (!peel)  break;

    bind(search);

    // Check that the previous entry is non-null.  A null entry means that
    // the receiver class doesn't implement the interface, and wasn't the
    // same as when the caller was compiled.
    testptr(method_result, method_result);
    jcc(Assembler::zero, L_no_such_interface);
    addptr(scan_temp, scan_step);
  }

  bind(found_method);

  if (return_method) {
    // Got a hit.
    movl(scan_temp, Address(scan_temp, itableOffsetEntry::offset_offset_in_bytes()));
    movptr(method_result, Address(recv_klass, scan_temp, Address::times_1));
  }
}


// virtual method calling
void MacroAssembler::lookup_virtual_method(Register recv_klass,
                                           RegisterOrConstant vtable_index,
                                           Register method_result) {
  const int base = in_bytes(Klass::vtable_start_offset());
  assert(vtableEntry::size() * wordSize == wordSize, "else adjust the scaling in the code below");
  Address vtable_entry_addr(recv_klass,
                            vtable_index, Address::times_ptr,
                            base + vtableEntry::method_offset_in_bytes());
  movptr(method_result, vtable_entry_addr);
}


void MacroAssembler::check_klass_subtype(Register sub_klass,
                           Register super_klass,
                           Register temp_reg,
                           Label& L_success) {
  Label L_failure;
  check_klass_subtype_fast_path(sub_klass, super_klass, temp_reg,        &L_success, &L_failure, NULL);
  check_klass_subtype_slow_path(sub_klass, super_klass, temp_reg, noreg, &L_success, NULL);
  bind(L_failure);
}


void MacroAssembler::check_klass_subtype_fast_path(Register sub_klass,
                                                   Register super_klass,
                                                   Register temp_reg,
                                                   Label* L_success,
                                                   Label* L_failure,
                                                   Label* L_slow_path,
                                        RegisterOrConstant super_check_offset) {
  assert_different_registers(sub_klass, super_klass, temp_reg);
  bool must_load_sco = (super_check_offset.constant_or_zero() == -1);
  if (super_check_offset.is_register()) {
    assert_different_registers(sub_klass, super_klass,
                               super_check_offset.as_register());
  } else if (must_load_sco) {
    assert(temp_reg != noreg, "supply either a temp or a register offset");
  }

  Label L_fallthrough;
  int label_nulls = 0;
  if (L_success == NULL)   { L_success   = &L_fallthrough; label_nulls++; }
  if (L_failure == NULL)   { L_failure   = &L_fallthrough; label_nulls++; }
  if (L_slow_path == NULL) { L_slow_path = &L_fallthrough; label_nulls++; }
  assert(label_nulls <= 1, "at most one NULL in the batch");

  int sc_offset = in_bytes(Klass::secondary_super_cache_offset());
  int sco_offset = in_bytes(Klass::super_check_offset_offset());
  Address super_check_offset_addr(super_klass, sco_offset);

  // Hacked jcc, which "knows" that L_fallthrough, at least, is in
  // range of a jccb.  If this routine grows larger, reconsider at
  // least some of these.
#define local_jcc(assembler_cond, label)                                \
  if (&(label) == &L_fallthrough)  jccb(assembler_cond, label);         \
  else                             jcc( assembler_cond, label) /*omit semi*/

  // Hacked jmp, which may only be used just before L_fallthrough.
#define final_jmp(label)                                                \
  if (&(label) == &L_fallthrough) { /*do nothing*/ }                    \
  else                            jmp(label)                /*omit semi*/

  // If the pointers are equal, we are done (e.g., String[] elements).
  // This self-check enables sharing of secondary supertype arrays among
  // non-primary types such as array-of-interface.  Otherwise, each such
  // type would need its own customized SSA.
  // We move this check to the front of the fast path because many
  // type checks are in fact trivially successful in this manner,
  // so we get a nicely predicted branch right at the start of the check.
  cmpptr(sub_klass, super_klass);
  local_jcc(Assembler::equal, *L_success);

  // Check the supertype display:
  if (must_load_sco) {
    // Positive movl does right thing on LP64.
    movl(temp_reg, super_check_offset_addr);
    super_check_offset = RegisterOrConstant(temp_reg);
  }
  Address super_check_addr(sub_klass, super_check_offset, Address::times_1, 0);
  cmpptr(super_klass, super_check_addr); // load displayed supertype

  // This check has worked decisively for primary supers.
  // Secondary supers are sought in the super_cache ('super_cache_addr').
  // (Secondary supers are interfaces and very deeply nested subtypes.)
  // This works in the same check above because of a tricky aliasing
  // between the super_cache and the primary super display elements.
  // (The 'super_check_addr' can address either, as the case requires.)
  // Note that the cache is updated below if it does not help us find
  // what we need immediately.
  // So if it was a primary super, we can just fail immediately.
  // Otherwise, it's the slow path for us (no success at this point).

  if (super_check_offset.is_register()) {
    local_jcc(Assembler::equal, *L_success);
    cmpl(super_check_offset.as_register(), sc_offset);
    if (L_failure == &L_fallthrough) {
      local_jcc(Assembler::equal, *L_slow_path);
    } else {
      local_jcc(Assembler::notEqual, *L_failure);
      final_jmp(*L_slow_path);
    }
  } else if (super_check_offset.as_constant() == sc_offset) {
    // Need a slow path; fast failure is impossible.
    if (L_slow_path == &L_fallthrough) {
      local_jcc(Assembler::equal, *L_success);
    } else {
      local_jcc(Assembler::notEqual, *L_slow_path);
      final_jmp(*L_success);
    }
  } else {
    // No slow path; it's a fast decision.
    if (L_failure == &L_fallthrough) {
      local_jcc(Assembler::equal, *L_success);
    } else {
      local_jcc(Assembler::notEqual, *L_failure);
      final_jmp(*L_success);
    }
  }

  bind(L_fallthrough);

#undef local_jcc
#undef final_jmp
}


void MacroAssembler::check_klass_subtype_slow_path(Register sub_klass,
                                                   Register super_klass,
                                                   Register temp_reg,
                                                   Register temp2_reg,
                                                   Label* L_success,
                                                   Label* L_failure,
                                                   bool set_cond_codes) {
  assert_different_registers(sub_klass, super_klass, temp_reg);
  if (temp2_reg != noreg)
    assert_different_registers(sub_klass, super_klass, temp_reg, temp2_reg);
#define IS_A_TEMP(reg) ((reg) == temp_reg || (reg) == temp2_reg)

  Label L_fallthrough;
  int label_nulls = 0;
  if (L_success == NULL)   { L_success   = &L_fallthrough; label_nulls++; }
  if (L_failure == NULL)   { L_failure   = &L_fallthrough; label_nulls++; }
  assert(label_nulls <= 1, "at most one NULL in the batch");

  // a couple of useful fields in sub_klass:
  int ss_offset = in_bytes(Klass::secondary_supers_offset());
  int sc_offset = in_bytes(Klass::secondary_super_cache_offset());
  Address secondary_supers_addr(sub_klass, ss_offset);
  Address super_cache_addr(     sub_klass, sc_offset);

  // Do a linear scan of the secondary super-klass chain.
  // This code is rarely used, so simplicity is a virtue here.
  // The repne_scan instruction uses fixed registers, which we must spill.
  // Don't worry too much about pre-existing connections with the input regs.

  assert(sub_klass != rax, "killed reg"); // killed by mov(rax, super)
  assert(sub_klass != rcx, "killed reg"); // killed by lea(rcx, &pst_counter)

  // Get super_klass value into rax (even if it was in rdi or rcx).
  bool pushed_rax = false, pushed_rcx = false, pushed_rdi = false;
  if (super_klass != rax || UseCompressedOops) {
    if (!IS_A_TEMP(rax)) { push(rax); pushed_rax = true; }
    mov(rax, super_klass);
  }
  if (!IS_A_TEMP(rcx)) { push(rcx); pushed_rcx = true; }
  if (!IS_A_TEMP(rdi)) { push(rdi); pushed_rdi = true; }

#ifndef PRODUCT
  int* pst_counter = &SharedRuntime::_partial_subtype_ctr;
  ExternalAddress pst_counter_addr((address) pst_counter);
  NOT_LP64(  incrementl(pst_counter_addr) );
  LP64_ONLY( lea(rcx, pst_counter_addr) );
  LP64_ONLY( incrementl(Address(rcx, 0)) );
#endif //PRODUCT

  // We will consult the secondary-super array.
  movptr(rdi, secondary_supers_addr);
  // Load the array length.  (Positive movl does right thing on LP64.)
  movl(rcx, Address(rdi, Array<Klass*>::length_offset_in_bytes()));
  // Skip to start of data.
  addptr(rdi, Array<Klass*>::base_offset_in_bytes());

  // Scan RCX words at [RDI] for an occurrence of RAX.
  // Set NZ/Z based on last compare.
  // Z flag value will not be set by 'repne' if RCX == 0 since 'repne' does
  // not change flags (only scas instruction which is repeated sets flags).
  // Set Z = 0 (not equal) before 'repne' to indicate that class was not found.

    testptr(rax,rax); // Set Z = 0
    repne_scan();

  // Unspill the temp. registers:
  if (pushed_rdi)  pop(rdi);
  if (pushed_rcx)  pop(rcx);
  if (pushed_rax)  pop(rax);

  if (set_cond_codes) {
    // Special hack for the AD files:  rdi is guaranteed non-zero.
    assert(!pushed_rdi, "rdi must be left non-NULL");
    // Also, the condition codes are properly set Z/NZ on succeed/failure.
  }

  if (L_failure == &L_fallthrough)
        jccb(Assembler::notEqual, *L_failure);
  else  jcc(Assembler::notEqual, *L_failure);

  // Success.  Cache the super we found and proceed in triumph.
  movptr(super_cache_addr, super_klass);

  if (L_success != &L_fallthrough) {
    jmp(*L_success);
  }

#undef IS_A_TEMP

  bind(L_fallthrough);
}


void MacroAssembler::cmov32(Condition cc, Register dst, Address src) {
  if (VM_Version::supports_cmov()) {
    cmovl(cc, dst, src);
  } else {
    Label L;
    jccb(negate_condition(cc), L);
    movl(dst, src);
    bind(L);
  }
}

void MacroAssembler::cmov32(Condition cc, Register dst, Register src) {
  if (VM_Version::supports_cmov()) {
    cmovl(cc, dst, src);
  } else {
    Label L;
    jccb(negate_condition(cc), L);
    movl(dst, src);
    bind(L);
  }
}

void MacroAssembler::verify_oop(Register reg, const char* s) {
  if (!VerifyOops) return;

  // Pass register number to verify_oop_subroutine
  const char* b = NULL;
  {
    ResourceMark rm;
    stringStream ss;
    ss.print("verify_oop: %s: %s", reg->name(), s);
    b = code_string(ss.as_string());
  }
  BLOCK_COMMENT("verify_oop {");
#ifdef _LP64
  push(rscratch1);                    // save r10, trashed by movptr()
#endif
  push(rax);                          // save rax,
  push(reg);                          // pass register argument
  ExternalAddress buffer((address) b);
  // avoid using pushptr, as it modifies scratch registers
  // and our contract is not to modify anything
  movptr(rax, buffer.addr());
  push(rax);
  // call indirectly to solve generation ordering problem
  movptr(rax, ExternalAddress(StubRoutines::verify_oop_subroutine_entry_address()));
  call(rax);
  // Caller pops the arguments (oop, message) and restores rax, r10
  BLOCK_COMMENT("} verify_oop");
}


RegisterOrConstant MacroAssembler::delayed_value_impl(intptr_t* delayed_value_addr,
                                                      Register tmp,
                                                      int offset) {
  intptr_t value = *delayed_value_addr;
  if (value != 0)
    return RegisterOrConstant(value + offset);

  // load indirectly to solve generation ordering problem
  movptr(tmp, ExternalAddress((address) delayed_value_addr));

#ifdef ASSERT
  { Label L;
    testptr(tmp, tmp);
    if (WizardMode) {
      const char* buf = NULL;
      {
        ResourceMark rm;
        stringStream ss;
        ss.print("DelayedValue=" INTPTR_FORMAT, delayed_value_addr[1]);
        buf = code_string(ss.as_string());
      }
      jcc(Assembler::notZero, L);
      STOP(buf);
    } else {
      jccb(Assembler::notZero, L);
      hlt();
    }
    bind(L);
  }
#endif

  if (offset != 0)
    addptr(tmp, offset);

  return RegisterOrConstant(tmp);
}


Address MacroAssembler::argument_address(RegisterOrConstant arg_slot,
                                         int extra_slot_offset) {
  // cf. TemplateTable::prepare_invoke(), if (load_receiver).
  int stackElementSize = Interpreter::stackElementSize;
  int offset = Interpreter::expr_offset_in_bytes(extra_slot_offset+0);
#ifdef ASSERT
  int offset1 = Interpreter::expr_offset_in_bytes(extra_slot_offset+1);
  assert(offset1 - offset == stackElementSize, "correct arithmetic");
#endif
  Register             scale_reg    = noreg;
  Address::ScaleFactor scale_factor = Address::no_scale;
  if (arg_slot.is_constant()) {
    offset += arg_slot.as_constant() * stackElementSize;
  } else {
    scale_reg    = arg_slot.as_register();
    scale_factor = Address::times(stackElementSize);
  }
  offset += wordSize;           // return PC is on stack
  return Address(rsp, scale_reg, scale_factor, offset);
}


void MacroAssembler::verify_oop_addr(Address addr, const char* s) {
  if (!VerifyOops) return;

  // Address adjust(addr.base(), addr.index(), addr.scale(), addr.disp() + BytesPerWord);
  // Pass register number to verify_oop_subroutine
  const char* b = NULL;
  {
    ResourceMark rm;
    stringStream ss;
    ss.print("verify_oop_addr: %s", s);
    b = code_string(ss.as_string());
  }
#ifdef _LP64
  push(rscratch1);                    // save r10, trashed by movptr()
#endif
  push(rax);                          // save rax,
  // addr may contain rsp so we will have to adjust it based on the push
  // we just did (and on 64 bit we do two pushes)
  // NOTE: 64bit seemed to have had a bug in that it did movq(addr, rax); which
  // stores rax into addr which is backwards of what was intended.
  if (addr.uses(rsp)) {
    lea(rax, addr);
    pushptr(Address(rax, LP64_ONLY(2 *) BytesPerWord));
  } else {
    pushptr(addr);
  }

  ExternalAddress buffer((address) b);
  // pass msg argument
  // avoid using pushptr, as it modifies scratch registers
  // and our contract is not to modify anything
  movptr(rax, buffer.addr());
  push(rax);

  // call indirectly to solve generation ordering problem
  movptr(rax, ExternalAddress(StubRoutines::verify_oop_subroutine_entry_address()));
  call(rax);
  // Caller pops the arguments (addr, message) and restores rax, r10.
}

void MacroAssembler::verify_tlab() {
#ifdef ASSERT
  if (UseTLAB && VerifyOops) {
    Label next, ok;
    Register t1 = rsi;
    Register thread_reg = NOT_LP64(rbx) LP64_ONLY(r15_thread);

    push(t1);
    NOT_LP64(push(thread_reg));
    NOT_LP64(get_thread(thread_reg));

    movptr(t1, Address(thread_reg, in_bytes(JavaThread::tlab_top_offset())));
    cmpptr(t1, Address(thread_reg, in_bytes(JavaThread::tlab_start_offset())));
    jcc(Assembler::aboveEqual, next);
    STOP("assert(top >= start)");
    should_not_reach_here();

    bind(next);
    movptr(t1, Address(thread_reg, in_bytes(JavaThread::tlab_end_offset())));
    cmpptr(t1, Address(thread_reg, in_bytes(JavaThread::tlab_top_offset())));
    jcc(Assembler::aboveEqual, ok);
    STOP("assert(top <= end)");
    should_not_reach_here();

    bind(ok);
    NOT_LP64(pop(thread_reg));
    pop(t1);
  }
#endif
}

class ControlWord {
 public:
  int32_t _value;

  int  rounding_control() const        { return  (_value >> 10) & 3      ; }
  int  precision_control() const       { return  (_value >>  8) & 3      ; }
  bool precision() const               { return ((_value >>  5) & 1) != 0; }
  bool underflow() const               { return ((_value >>  4) & 1) != 0; }
  bool overflow() const                { return ((_value >>  3) & 1) != 0; }
  bool zero_divide() const             { return ((_value >>  2) & 1) != 0; }
  bool denormalized() const            { return ((_value >>  1) & 1) != 0; }
  bool invalid() const                 { return ((_value >>  0) & 1) != 0; }

  void print() const {
    // rounding control
    const char* rc;
    switch (rounding_control()) {
      case 0: rc = "round near"; break;
      case 1: rc = "round down"; break;
      case 2: rc = "round up  "; break;
      case 3: rc = "chop      "; break;
    };
    // precision control
    const char* pc;
    switch (precision_control()) {
      case 0: pc = "24 bits "; break;
      case 1: pc = "reserved"; break;
      case 2: pc = "53 bits "; break;
      case 3: pc = "64 bits "; break;
    };
    // flags
    char f[9];
    f[0] = ' ';
    f[1] = ' ';
    f[2] = (precision   ()) ? 'P' : 'p';
    f[3] = (underflow   ()) ? 'U' : 'u';
    f[4] = (overflow    ()) ? 'O' : 'o';
    f[5] = (zero_divide ()) ? 'Z' : 'z';
    f[6] = (denormalized()) ? 'D' : 'd';
    f[7] = (invalid     ()) ? 'I' : 'i';
    f[8] = '\x0';
    // output
    printf("%04x  masks = %s, %s, %s", _value & 0xFFFF, f, rc, pc);
  }

};

class StatusWord {
 public:
  int32_t _value;

  bool busy() const                    { return ((_value >> 15) & 1) != 0; }
  bool C3() const                      { return ((_value >> 14) & 1) != 0; }
  bool C2() const                      { return ((_value >> 10) & 1) != 0; }
  bool C1() const                      { return ((_value >>  9) & 1) != 0; }
  bool C0() const                      { return ((_value >>  8) & 1) != 0; }
  int  top() const                     { return  (_value >> 11) & 7      ; }
  bool error_status() const            { return ((_value >>  7) & 1) != 0; }
  bool stack_fault() const             { return ((_value >>  6) & 1) != 0; }
  bool precision() const               { return ((_value >>  5) & 1) != 0; }
  bool underflow() const               { return ((_value >>  4) & 1) != 0; }
  bool overflow() const                { return ((_value >>  3) & 1) != 0; }
  bool zero_divide() const             { return ((_value >>  2) & 1) != 0; }
  bool denormalized() const            { return ((_value >>  1) & 1) != 0; }
  bool invalid() const                 { return ((_value >>  0) & 1) != 0; }

  void print() const {
    // condition codes
    char c[5];
    c[0] = (C3()) ? '3' : '-';
    c[1] = (C2()) ? '2' : '-';
    c[2] = (C1()) ? '1' : '-';
    c[3] = (C0()) ? '0' : '-';
    c[4] = '\x0';
    // flags
    char f[9];
    f[0] = (error_status()) ? 'E' : '-';
    f[1] = (stack_fault ()) ? 'S' : '-';
    f[2] = (precision   ()) ? 'P' : '-';
    f[3] = (underflow   ()) ? 'U' : '-';
    f[4] = (overflow    ()) ? 'O' : '-';
    f[5] = (zero_divide ()) ? 'Z' : '-';
    f[6] = (denormalized()) ? 'D' : '-';
    f[7] = (invalid     ()) ? 'I' : '-';
    f[8] = '\x0';
    // output
    printf("%04x  flags = %s, cc =  %s, top = %d", _value & 0xFFFF, f, c, top());
  }

};

class TagWord {
 public:
  int32_t _value;

  int tag_at(int i) const              { return (_value >> (i*2)) & 3; }

  void print() const {
    printf("%04x", _value & 0xFFFF);
  }

};

class FPU_Register {
 public:
  int32_t _m0;
  int32_t _m1;
  int16_t _ex;

  bool is_indefinite() const           {
    return _ex == -1 && _m1 == (int32_t)0xC0000000 && _m0 == 0;
  }

  void print() const {
    char  sign = (_ex < 0) ? '-' : '+';
    const char* kind = (_ex == 0x7FFF || _ex == (int16_t)-1) ? "NaN" : "   ";
    printf("%c%04hx.%08x%08x  %s", sign, _ex, _m1, _m0, kind);
  };

};

class FPU_State {
 public:
  enum {
    register_size       = 10,
    number_of_registers =  8,
    register_mask       =  7
  };

  ControlWord  _control_word;
  StatusWord   _status_word;
  TagWord      _tag_word;
  int32_t      _error_offset;
  int32_t      _error_selector;
  int32_t      _data_offset;
  int32_t      _data_selector;
  int8_t       _register[register_size * number_of_registers];

  int tag_for_st(int i) const          { return _tag_word.tag_at((_status_word.top() + i) & register_mask); }
  FPU_Register* st(int i) const        { return (FPU_Register*)&_register[register_size * i]; }

  const char* tag_as_string(int tag) const {
    switch (tag) {
      case 0: return "valid";
      case 1: return "zero";
      case 2: return "special";
      case 3: return "empty";
    }
    ShouldNotReachHere();
    return NULL;
  }

  void print() const {
    // print computation registers
    { int t = _status_word.top();
      for (int i = 0; i < number_of_registers; i++) {
        int j = (i - t) & register_mask;
        printf("%c r%d = ST%d = ", (j == 0 ? '*' : ' '), i, j);
        st(j)->print();
        printf(" %s\n", tag_as_string(_tag_word.tag_at(i)));
      }
    }
    printf("\n");
    // print control registers
    printf("ctrl = "); _control_word.print(); printf("\n");
    printf("stat = "); _status_word .print(); printf("\n");
    printf("tags = "); _tag_word    .print(); printf("\n");
  }

};

class Flag_Register {
 public:
  int32_t _value;

  bool overflow() const                { return ((_value >> 11) & 1) != 0; }
  bool direction() const               { return ((_value >> 10) & 1) != 0; }
  bool sign() const                    { return ((_value >>  7) & 1) != 0; }
  bool zero() const                    { return ((_value >>  6) & 1) != 0; }
  bool auxiliary_carry() const         { return ((_value >>  4) & 1) != 0; }
  bool parity() const                  { return ((_value >>  2) & 1) != 0; }
  bool carry() const                   { return ((_value >>  0) & 1) != 0; }

  void print() const {
    // flags
    char f[8];
    f[0] = (overflow       ()) ? 'O' : '-';
    f[1] = (direction      ()) ? 'D' : '-';
    f[2] = (sign           ()) ? 'S' : '-';
    f[3] = (zero           ()) ? 'Z' : '-';
    f[4] = (auxiliary_carry()) ? 'A' : '-';
    f[5] = (parity         ()) ? 'P' : '-';
    f[6] = (carry          ()) ? 'C' : '-';
    f[7] = '\x0';
    // output
    printf("%08x  flags = %s", _value, f);
  }

};

class IU_Register {
 public:
  int32_t _value;

  void print() const {
    printf("%08x  %11d", _value, _value);
  }

};

class IU_State {
 public:
  Flag_Register _eflags;
  IU_Register   _rdi;
  IU_Register   _rsi;
  IU_Register   _rbp;
  IU_Register   _rsp;
  IU_Register   _rbx;
  IU_Register   _rdx;
  IU_Register   _rcx;
  IU_Register   _rax;

  void print() const {
    // computation registers
    printf("rax,  = "); _rax.print(); printf("\n");
    printf("rbx,  = "); _rbx.print(); printf("\n");
    printf("rcx  = "); _rcx.print(); printf("\n");
    printf("rdx  = "); _rdx.print(); printf("\n");
    printf("rdi  = "); _rdi.print(); printf("\n");
    printf("rsi  = "); _rsi.print(); printf("\n");
    printf("rbp,  = "); _rbp.print(); printf("\n");
    printf("rsp  = "); _rsp.print(); printf("\n");
    printf("\n");
    // control registers
    printf("flgs = "); _eflags.print(); printf("\n");
  }
};


class CPU_State {
 public:
  FPU_State _fpu_state;
  IU_State  _iu_state;

  void print() const {
    printf("--------------------------------------------------\n");
    _iu_state .print();
    printf("\n");
    _fpu_state.print();
    printf("--------------------------------------------------\n");
  }

};


static void _print_CPU_state(CPU_State* state) {
  state->print();
};


void MacroAssembler::print_CPU_state() {
  push_CPU_state();
  push(rsp);                // pass CPU state
  call(RuntimeAddress(CAST_FROM_FN_PTR(address, _print_CPU_state)));
  addptr(rsp, wordSize);       // discard argument
  pop_CPU_state();
}


static bool _verify_FPU(int stack_depth, char* s, CPU_State* state) {
  static int counter = 0;
  FPU_State* fs = &state->_fpu_state;
  counter++;
  // For leaf calls, only verify that the top few elements remain empty.
  // We only need 1 empty at the top for C2 code.
  if( stack_depth < 0 ) {
    if( fs->tag_for_st(7) != 3 ) {
      printf("FPR7 not empty\n");
      state->print();
      assert(false, "error");
      return false;
    }
    return true;                // All other stack states do not matter
  }

  assert((fs->_control_word._value & 0xffff) == StubRoutines::_fpu_cntrl_wrd_std,
         "bad FPU control word");

  // compute stack depth
  int i = 0;
  while (i < FPU_State::number_of_registers && fs->tag_for_st(i)  < 3) i++;
  int d = i;
  while (i < FPU_State::number_of_registers && fs->tag_for_st(i) == 3) i++;
  // verify findings
  if (i != FPU_State::number_of_registers) {
    // stack not contiguous
    printf("%s: stack not contiguous at ST%d\n", s, i);
    state->print();
    assert(false, "error");
    return false;
  }
  // check if computed stack depth corresponds to expected stack depth
  if (stack_depth < 0) {
    // expected stack depth is -stack_depth or less
    if (d > -stack_depth) {
      // too many elements on the stack
      printf("%s: <= %d stack elements expected but found %d\n", s, -stack_depth, d);
      state->print();
      assert(false, "error");
      return false;
    }
  } else {
    // expected stack depth is stack_depth
    if (d != stack_depth) {
      // wrong stack depth
      printf("%s: %d stack elements expected but found %d\n", s, stack_depth, d);
      state->print();
      assert(false, "error");
      return false;
    }
  }
  // everything is cool
  return true;
}


void MacroAssembler::verify_FPU(int stack_depth, const char* s) {
  if (!VerifyFPU) return;
  push_CPU_state();
  push(rsp);                // pass CPU state
  ExternalAddress msg((address) s);
  // pass message string s
  pushptr(msg.addr());
  push(stack_depth);        // pass stack depth
  call(RuntimeAddress(CAST_FROM_FN_PTR(address, _verify_FPU)));
  addptr(rsp, 3 * wordSize);   // discard arguments
  // check for error
  { Label L;
    testl(rax, rax);
    jcc(Assembler::notZero, L);
    int3();                  // break if error condition
    bind(L);
  }
  pop_CPU_state();
}

void MacroAssembler::restore_cpu_control_state_after_jni() {
  // Either restore the MXCSR register after returning from the JNI Call
  // or verify that it wasn't changed (with -Xcheck:jni flag).
  if (VM_Version::supports_sse()) {
    if (RestoreMXCSROnJNICalls) {
      ldmxcsr(ExternalAddress(StubRoutines::addr_mxcsr_std()));
    } else if (CheckJNICalls) {
      call(RuntimeAddress(StubRoutines::x86::verify_mxcsr_entry()));
    }
  }
  // Clear upper bits of YMM registers to avoid SSE <-> AVX transition penalty.
  vzeroupper();
  // Reset k1 to 0xffff.

#ifdef COMPILER2
  if (PostLoopMultiversioning && VM_Version::supports_evex()) {
    push(rcx);
    movl(rcx, 0xffff);
    kmovwl(k1, rcx);
    pop(rcx);
  }
#endif // COMPILER2

#ifndef _LP64
  // Either restore the x87 floating pointer control word after returning
  // from the JNI call or verify that it wasn't changed.
  if (CheckJNICalls) {
    call(RuntimeAddress(StubRoutines::x86::verify_fpu_cntrl_wrd_entry()));
  }
#endif // _LP64
}

// ((OopHandle)result).resolve();
void MacroAssembler::resolve_oop_handle(Register result, Register tmp) {
  assert_different_registers(result, tmp);

  // Only 64 bit platforms support GCs that require a tmp register
  // Only IN_HEAP loads require a thread_tmp register
  // OopHandle::resolve is an indirection like jobject.
  access_load_at(T_OBJECT, IN_NATIVE,
                 result, Address(result, 0), tmp, /*tmp_thread*/noreg);
}

void MacroAssembler::load_mirror(Register mirror, Register method, Register tmp) {
  // get mirror
  const int mirror_offset = in_bytes(Klass::java_mirror_offset());
  movptr(mirror, Address(method, Method::const_offset()));
  movptr(mirror, Address(mirror, ConstMethod::constants_offset()));
  movptr(mirror, Address(mirror, ConstantPool::pool_holder_offset_in_bytes()));
  movptr(mirror, Address(mirror, mirror_offset));
  resolve_oop_handle(mirror, tmp);
}

void MacroAssembler::load_klass(Register dst, Register src) {
#ifdef _LP64
  if (UseCompressedClassPointers) {
    movl(dst, Address(src, oopDesc::klass_offset_in_bytes()));
    decode_klass_not_null(dst);
  } else
#endif
    movptr(dst, Address(src, oopDesc::klass_offset_in_bytes()));
}

void MacroAssembler::load_prototype_header(Register dst, Register src) {
  load_klass(dst, src);
  movptr(dst, Address(dst, Klass::prototype_header_offset()));
}

void MacroAssembler::store_klass(Register dst, Register src) {
#ifdef _LP64
  if (UseCompressedClassPointers) {
    encode_klass_not_null(src);
    movl(Address(dst, oopDesc::klass_offset_in_bytes()), src);
  } else
#endif
    movptr(Address(dst, oopDesc::klass_offset_in_bytes()), src);
}

void MacroAssembler::access_load_at(BasicType type, DecoratorSet decorators, Register dst, Address src,
                                    Register tmp1, Register thread_tmp) {
  BarrierSetAssembler* bs = BarrierSet::barrier_set()->barrier_set_assembler();
  decorators = AccessInternal::decorator_fixup(decorators);
  bool as_raw = (decorators & AS_RAW) != 0;
  if (as_raw) {
    bs->BarrierSetAssembler::load_at(this, decorators, type, dst, src, tmp1, thread_tmp);
  } else {
    bs->load_at(this, decorators, type, dst, src, tmp1, thread_tmp);
  }
}

void MacroAssembler::access_store_at(BasicType type, DecoratorSet decorators, Address dst, Register src,
                                     Register tmp1, Register tmp2) {
  BarrierSetAssembler* bs = BarrierSet::barrier_set()->barrier_set_assembler();
  decorators = AccessInternal::decorator_fixup(decorators);
  bool as_raw = (decorators & AS_RAW) != 0;
  if (as_raw) {
    bs->BarrierSetAssembler::store_at(this, decorators, type, dst, src, tmp1, tmp2);
  } else {
    bs->store_at(this, decorators, type, dst, src, tmp1, tmp2);
  }
}

void MacroAssembler::load_heap_oop(Register dst, Address src, Register tmp1,
                                   Register thread_tmp, DecoratorSet decorators) {
  access_load_at(T_OBJECT, IN_HEAP | decorators, dst, src, tmp1, thread_tmp);
}

// Doesn't do verfication, generates fixed size code
void MacroAssembler::load_heap_oop_not_null(Register dst, Address src, Register tmp1,
                                            Register thread_tmp, DecoratorSet decorators) {
  access_load_at(T_OBJECT, IN_HEAP | IS_NOT_NULL | decorators, dst, src, tmp1, thread_tmp);
}

void MacroAssembler::store_heap_oop(Address dst, Register src, Register tmp1,
                                    Register tmp2, DecoratorSet decorators) {
  access_store_at(T_OBJECT, IN_HEAP | decorators, dst, src, tmp1, tmp2);
}

// Used for storing NULLs.
void MacroAssembler::store_heap_oop_null(Address dst) {
  access_store_at(T_OBJECT, IN_HEAP, dst, noreg, noreg, noreg);
}

#ifdef _LP64
void MacroAssembler::store_klass_gap(Register dst, Register src) {
  if (UseCompressedClassPointers) {
    // Store to klass gap in destination
    movl(Address(dst, oopDesc::klass_gap_offset_in_bytes()), src);
  }
}

#ifdef ASSERT
void MacroAssembler::verify_heapbase(const char* msg) {
  assert (UseCompressedOops, "should be compressed");
  assert (Universe::heap() != NULL, "java heap should be initialized");
  if (CheckCompressedOops) {
    Label ok;
    push(rscratch1); // cmpptr trashes rscratch1
    cmpptr(r12_heapbase, ExternalAddress((address)Universe::narrow_ptrs_base_addr()));
    jcc(Assembler::equal, ok);
    STOP(msg);
    bind(ok);
    pop(rscratch1);
  }
}
#endif

// Algorithm must match oop.inline.hpp encode_heap_oop.
void MacroAssembler::encode_heap_oop(Register r) {
#ifdef ASSERT
  verify_heapbase("MacroAssembler::encode_heap_oop: heap base corrupted?");
#endif
  verify_oop(r, "broken oop in encode_heap_oop");
  if (Universe::narrow_oop_base() == NULL) {
    if (Universe::narrow_oop_shift() != 0) {
      assert (LogMinObjAlignmentInBytes == Universe::narrow_oop_shift(), "decode alg wrong");
      shrq(r, LogMinObjAlignmentInBytes);
    }
    return;
  }
  testq(r, r);
  cmovq(Assembler::equal, r, r12_heapbase);
  subq(r, r12_heapbase);
  shrq(r, LogMinObjAlignmentInBytes);
}

void MacroAssembler::encode_heap_oop_not_null(Register r) {
#ifdef ASSERT
  verify_heapbase("MacroAssembler::encode_heap_oop_not_null: heap base corrupted?");
  if (CheckCompressedOops) {
    Label ok;
    testq(r, r);
    jcc(Assembler::notEqual, ok);
    STOP("null oop passed to encode_heap_oop_not_null");
    bind(ok);
  }
#endif
  verify_oop(r, "broken oop in encode_heap_oop_not_null");
  if (Universe::narrow_oop_base() != NULL) {
    subq(r, r12_heapbase);
  }
  if (Universe::narrow_oop_shift() != 0) {
    assert (LogMinObjAlignmentInBytes == Universe::narrow_oop_shift(), "decode alg wrong");
    shrq(r, LogMinObjAlignmentInBytes);
  }
}

void MacroAssembler::encode_heap_oop_not_null(Register dst, Register src) {
#ifdef ASSERT
  verify_heapbase("MacroAssembler::encode_heap_oop_not_null2: heap base corrupted?");
  if (CheckCompressedOops) {
    Label ok;
    testq(src, src);
    jcc(Assembler::notEqual, ok);
    STOP("null oop passed to encode_heap_oop_not_null2");
    bind(ok);
  }
#endif
  verify_oop(src, "broken oop in encode_heap_oop_not_null2");
  if (dst != src) {
    movq(dst, src);
  }
  if (Universe::narrow_oop_base() != NULL) {
    subq(dst, r12_heapbase);
  }
  if (Universe::narrow_oop_shift() != 0) {
    assert (LogMinObjAlignmentInBytes == Universe::narrow_oop_shift(), "decode alg wrong");
    shrq(dst, LogMinObjAlignmentInBytes);
  }
}

void  MacroAssembler::decode_heap_oop(Register r) {
#ifdef ASSERT
  verify_heapbase("MacroAssembler::decode_heap_oop: heap base corrupted?");
#endif
  if (Universe::narrow_oop_base() == NULL) {
    if (Universe::narrow_oop_shift() != 0) {
      assert (LogMinObjAlignmentInBytes == Universe::narrow_oop_shift(), "decode alg wrong");
      shlq(r, LogMinObjAlignmentInBytes);
    }
  } else {
    Label done;
    shlq(r, LogMinObjAlignmentInBytes);
    jccb(Assembler::equal, done);
    addq(r, r12_heapbase);
    bind(done);
  }
  verify_oop(r, "broken oop in decode_heap_oop");
}

void  MacroAssembler::decode_heap_oop_not_null(Register r) {
  // Note: it will change flags
  assert (UseCompressedOops, "should only be used for compressed headers");
  assert (Universe::heap() != NULL, "java heap should be initialized");
  // Cannot assert, unverified entry point counts instructions (see .ad file)
  // vtableStubs also counts instructions in pd_code_size_limit.
  // Also do not verify_oop as this is called by verify_oop.
  if (Universe::narrow_oop_shift() != 0) {
    assert(LogMinObjAlignmentInBytes == Universe::narrow_oop_shift(), "decode alg wrong");
    shlq(r, LogMinObjAlignmentInBytes);
    if (Universe::narrow_oop_base() != NULL) {
      addq(r, r12_heapbase);
    }
  } else {
    assert (Universe::narrow_oop_base() == NULL, "sanity");
  }
}

void  MacroAssembler::decode_heap_oop_not_null(Register dst, Register src) {
  // Note: it will change flags
  assert (UseCompressedOops, "should only be used for compressed headers");
  assert (Universe::heap() != NULL, "java heap should be initialized");
  // Cannot assert, unverified entry point counts instructions (see .ad file)
  // vtableStubs also counts instructions in pd_code_size_limit.
  // Also do not verify_oop as this is called by verify_oop.
  if (Universe::narrow_oop_shift() != 0) {
    assert(LogMinObjAlignmentInBytes == Universe::narrow_oop_shift(), "decode alg wrong");
    if (LogMinObjAlignmentInBytes == Address::times_8) {
      leaq(dst, Address(r12_heapbase, src, Address::times_8, 0));
    } else {
      if (dst != src) {
        movq(dst, src);
      }
      shlq(dst, LogMinObjAlignmentInBytes);
      if (Universe::narrow_oop_base() != NULL) {
        addq(dst, r12_heapbase);
      }
    }
  } else {
    assert (Universe::narrow_oop_base() == NULL, "sanity");
    if (dst != src) {
      movq(dst, src);
    }
  }
}

void MacroAssembler::encode_klass_not_null(Register r) {
  if (Universe::narrow_klass_base() != NULL) {
    // Use r12 as a scratch register in which to temporarily load the narrow_klass_base.
    assert(r != r12_heapbase, "Encoding a klass in r12");
    mov64(r12_heapbase, (int64_t)Universe::narrow_klass_base());
    subq(r, r12_heapbase);
  }
  if (Universe::narrow_klass_shift() != 0) {
    assert (LogKlassAlignmentInBytes == Universe::narrow_klass_shift(), "decode alg wrong");
    shrq(r, LogKlassAlignmentInBytes);
  }
  if (Universe::narrow_klass_base() != NULL) {
    reinit_heapbase();
  }
}

void MacroAssembler::encode_klass_not_null(Register dst, Register src) {
  if (dst == src) {
    encode_klass_not_null(src);
  } else {
    if (Universe::narrow_klass_base() != NULL) {
      mov64(dst, (int64_t)Universe::narrow_klass_base());
      negq(dst);
      addq(dst, src);
    } else {
      movptr(dst, src);
    }
    if (Universe::narrow_klass_shift() != 0) {
      assert (LogKlassAlignmentInBytes == Universe::narrow_klass_shift(), "decode alg wrong");
      shrq(dst, LogKlassAlignmentInBytes);
    }
  }
}

// Function instr_size_for_decode_klass_not_null() counts the instructions
// generated by decode_klass_not_null(register r) and reinit_heapbase(),
// when (Universe::heap() != NULL).  Hence, if the instructions they
// generate change, then this method needs to be updated.
int MacroAssembler::instr_size_for_decode_klass_not_null() {
  assert (UseCompressedClassPointers, "only for compressed klass ptrs");
  if (Universe::narrow_klass_base() != NULL) {
    // mov64 + addq + shlq? + mov64  (for reinit_heapbase()).
    return (Universe::narrow_klass_shift() == 0 ? 20 : 24);
  } else {
    // longest load decode klass function, mov64, leaq
    return 16;
  }
}

// !!! If the instructions that get generated here change then function
// instr_size_for_decode_klass_not_null() needs to get updated.
void  MacroAssembler::decode_klass_not_null(Register r) {
  // Note: it will change flags
  assert (UseCompressedClassPointers, "should only be used for compressed headers");
  assert(r != r12_heapbase, "Decoding a klass in r12");
  // Cannot assert, unverified entry point counts instructions (see .ad file)
  // vtableStubs also counts instructions in pd_code_size_limit.
  // Also do not verify_oop as this is called by verify_oop.
  if (Universe::narrow_klass_shift() != 0) {
    assert(LogKlassAlignmentInBytes == Universe::narrow_klass_shift(), "decode alg wrong");
    shlq(r, LogKlassAlignmentInBytes);
  }
  // Use r12 as a scratch register in which to temporarily load the narrow_klass_base.
  if (Universe::narrow_klass_base() != NULL) {
    mov64(r12_heapbase, (int64_t)Universe::narrow_klass_base());
    addq(r, r12_heapbase);
    reinit_heapbase();
  }
}

void  MacroAssembler::decode_klass_not_null(Register dst, Register src) {
  // Note: it will change flags
  assert (UseCompressedClassPointers, "should only be used for compressed headers");
  if (dst == src) {
    decode_klass_not_null(dst);
  } else {
    // Cannot assert, unverified entry point counts instructions (see .ad file)
    // vtableStubs also counts instructions in pd_code_size_limit.
    // Also do not verify_oop as this is called by verify_oop.
    mov64(dst, (int64_t)Universe::narrow_klass_base());
    if (Universe::narrow_klass_shift() != 0) {
      assert(LogKlassAlignmentInBytes == Universe::narrow_klass_shift(), "decode alg wrong");
      assert(LogKlassAlignmentInBytes == Address::times_8, "klass not aligned on 64bits?");
      leaq(dst, Address(dst, src, Address::times_8, 0));
    } else {
      addq(dst, src);
    }
  }
}

void  MacroAssembler::set_narrow_oop(Register dst, jobject obj) {
  assert (UseCompressedOops, "should only be used for compressed headers");
  assert (Universe::heap() != NULL, "java heap should be initialized");
  assert (oop_recorder() != NULL, "this assembler needs an OopRecorder");
  int oop_index = oop_recorder()->find_index(obj);
  RelocationHolder rspec = oop_Relocation::spec(oop_index);
  mov_narrow_oop(dst, oop_index, rspec);
}

void  MacroAssembler::set_narrow_oop(Address dst, jobject obj) {
  assert (UseCompressedOops, "should only be used for compressed headers");
  assert (Universe::heap() != NULL, "java heap should be initialized");
  assert (oop_recorder() != NULL, "this assembler needs an OopRecorder");
  int oop_index = oop_recorder()->find_index(obj);
  RelocationHolder rspec = oop_Relocation::spec(oop_index);
  mov_narrow_oop(dst, oop_index, rspec);
}

void  MacroAssembler::set_narrow_klass(Register dst, Klass* k) {
  assert (UseCompressedClassPointers, "should only be used for compressed headers");
  assert (oop_recorder() != NULL, "this assembler needs an OopRecorder");
  int klass_index = oop_recorder()->find_index(k);
  RelocationHolder rspec = metadata_Relocation::spec(klass_index);
  mov_narrow_oop(dst, Klass::encode_klass(k), rspec);
}

void  MacroAssembler::set_narrow_klass(Address dst, Klass* k) {
  assert (UseCompressedClassPointers, "should only be used for compressed headers");
  assert (oop_recorder() != NULL, "this assembler needs an OopRecorder");
  int klass_index = oop_recorder()->find_index(k);
  RelocationHolder rspec = metadata_Relocation::spec(klass_index);
  mov_narrow_oop(dst, Klass::encode_klass(k), rspec);
}

void  MacroAssembler::cmp_narrow_oop(Register dst, jobject obj) {
  assert (UseCompressedOops, "should only be used for compressed headers");
  assert (Universe::heap() != NULL, "java heap should be initialized");
  assert (oop_recorder() != NULL, "this assembler needs an OopRecorder");
  int oop_index = oop_recorder()->find_index(obj);
  RelocationHolder rspec = oop_Relocation::spec(oop_index);
  Assembler::cmp_narrow_oop(dst, oop_index, rspec);
}

void  MacroAssembler::cmp_narrow_oop(Address dst, jobject obj) {
  assert (UseCompressedOops, "should only be used for compressed headers");
  assert (Universe::heap() != NULL, "java heap should be initialized");
  assert (oop_recorder() != NULL, "this assembler needs an OopRecorder");
  int oop_index = oop_recorder()->find_index(obj);
  RelocationHolder rspec = oop_Relocation::spec(oop_index);
  Assembler::cmp_narrow_oop(dst, oop_index, rspec);
}

void  MacroAssembler::cmp_narrow_klass(Register dst, Klass* k) {
  assert (UseCompressedClassPointers, "should only be used for compressed headers");
  assert (oop_recorder() != NULL, "this assembler needs an OopRecorder");
  int klass_index = oop_recorder()->find_index(k);
  RelocationHolder rspec = metadata_Relocation::spec(klass_index);
  Assembler::cmp_narrow_oop(dst, Klass::encode_klass(k), rspec);
}

void  MacroAssembler::cmp_narrow_klass(Address dst, Klass* k) {
  assert (UseCompressedClassPointers, "should only be used for compressed headers");
  assert (oop_recorder() != NULL, "this assembler needs an OopRecorder");
  int klass_index = oop_recorder()->find_index(k);
  RelocationHolder rspec = metadata_Relocation::spec(klass_index);
  Assembler::cmp_narrow_oop(dst, Klass::encode_klass(k), rspec);
}

void MacroAssembler::reinit_heapbase() {
  if (UseCompressedOops || UseCompressedClassPointers) {
    if (Universe::heap() != NULL) {
      if (Universe::narrow_oop_base() == NULL) {
        MacroAssembler::xorptr(r12_heapbase, r12_heapbase);
      } else {
        mov64(r12_heapbase, (int64_t)Universe::narrow_ptrs_base());
      }
    } else {
      movptr(r12_heapbase, ExternalAddress((address)Universe::narrow_ptrs_base_addr()));
    }
  }
}

#endif // _LP64

// C2 compiled method's prolog code.
void MacroAssembler::verified_entry(int framesize, int stack_bang_size, bool fp_mode_24b) {

  // WARNING: Initial instruction MUST be 5 bytes or longer so that
  // NativeJump::patch_verified_entry will be able to patch out the entry
  // code safely. The push to verify stack depth is ok at 5 bytes,
  // the frame allocation can be either 3 or 6 bytes. So if we don't do
  // stack bang then we must use the 6 byte frame allocation even if
  // we have no frame. :-(
  assert(stack_bang_size >= framesize || stack_bang_size <= 0, "stack bang size incorrect");

  assert((framesize & (StackAlignmentInBytes-1)) == 0, "frame size not aligned");
  // Remove word for return addr
  framesize -= wordSize;
  stack_bang_size -= wordSize;

  // Calls to C2R adapters often do not accept exceptional returns.
  // We require that their callers must bang for them.  But be careful, because
  // some VM calls (such as call site linkage) can use several kilobytes of
  // stack.  But the stack safety zone should account for that.
  // See bugs 4446381, 4468289, 4497237.
  if (stack_bang_size > 0) {
    generate_stack_overflow_check(stack_bang_size);

    // We always push rbp, so that on return to interpreter rbp, will be
    // restored correctly and we can correct the stack.
    push(rbp);
    // Save caller's stack pointer into RBP if the frame pointer is preserved.
    if (PreserveFramePointer) {
      mov(rbp, rsp);
    }
    // Remove word for ebp
    framesize -= wordSize;

    // Create frame
    if (framesize) {
      subptr(rsp, framesize);
    }
  } else {
    // Create frame (force generation of a 4 byte immediate value)
    subptr_imm32(rsp, framesize);

    // Save RBP register now.
    framesize -= wordSize;
    movptr(Address(rsp, framesize), rbp);
    // Save caller's stack pointer into RBP if the frame pointer is preserved.
    if (PreserveFramePointer) {
      movptr(rbp, rsp);
      if (framesize > 0) {
        addptr(rbp, framesize);
      }
    }
  }

  if (VerifyStackAtCalls) { // Majik cookie to verify stack depth
    framesize -= wordSize;
    movptr(Address(rsp, framesize), (int32_t)0xbadb100d);
  }

#ifndef _LP64
  // If method sets FPU control word do it now
  if (fp_mode_24b) {
    fldcw(ExternalAddress(StubRoutines::addr_fpu_cntrl_wrd_24()));
  }
  if (UseSSE >= 2 && VerifyFPU) {
    verify_FPU(0, "FPU stack must be clean on entry");
  }
#endif

#ifdef ASSERT
  if (VerifyStackAtCalls) {
    Label L;
    push(rax);
    mov(rax, rsp);
    andptr(rax, StackAlignmentInBytes-1);
    cmpptr(rax, StackAlignmentInBytes-wordSize);
    pop(rax);
    jcc(Assembler::equal, L);
    STOP("Stack is not properly aligned!");
    bind(L);
  }
#endif

}

// clear memory of size 'cnt' qwords, starting at 'base' using XMM/YMM registers
void MacroAssembler::xmm_clear_mem(Register base, Register cnt, XMMRegister xtmp) {
  // cnt - number of qwords (8-byte words).
  // base - start address, qword aligned.
  Label L_zero_64_bytes, L_loop, L_sloop, L_tail, L_end;
  if (UseAVX >= 2) {
    vpxor(xtmp, xtmp, xtmp, AVX_256bit);
  } else {
    pxor(xtmp, xtmp);
  }
  jmp(L_zero_64_bytes);

  BIND(L_loop);
  if (UseAVX >= 2) {
    vmovdqu(Address(base,  0), xtmp);
    vmovdqu(Address(base, 32), xtmp);
  } else {
    movdqu(Address(base,  0), xtmp);
    movdqu(Address(base, 16), xtmp);
    movdqu(Address(base, 32), xtmp);
    movdqu(Address(base, 48), xtmp);
  }
  addptr(base, 64);

  BIND(L_zero_64_bytes);
  subptr(cnt, 8);
  jccb(Assembler::greaterEqual, L_loop);
  addptr(cnt, 4);
  jccb(Assembler::less, L_tail);
  // Copy trailing 32 bytes
  if (UseAVX >= 2) {
    vmovdqu(Address(base, 0), xtmp);
  } else {
    movdqu(Address(base,  0), xtmp);
    movdqu(Address(base, 16), xtmp);
  }
  addptr(base, 32);
  subptr(cnt, 4);

  BIND(L_tail);
  addptr(cnt, 4);
  jccb(Assembler::lessEqual, L_end);
  decrement(cnt);

  BIND(L_sloop);
  movq(Address(base, 0), xtmp);
  addptr(base, 8);
  decrement(cnt);
  jccb(Assembler::greaterEqual, L_sloop);
  BIND(L_end);
}

void MacroAssembler::clear_mem(Register base, Register cnt, Register tmp, XMMRegister xtmp, bool is_large) {
  // cnt - number of qwords (8-byte words).
  // base - start address, qword aligned.
  // is_large - if optimizers know cnt is larger than InitArrayShortSize
  assert(base==rdi, "base register must be edi for rep stos");
  assert(tmp==rax,   "tmp register must be eax for rep stos");
  assert(cnt==rcx,   "cnt register must be ecx for rep stos");
  assert(InitArrayShortSize % BytesPerLong == 0,
    "InitArrayShortSize should be the multiple of BytesPerLong");

  Label DONE;

  if (!is_large || !UseXMMForObjInit) {
    xorptr(tmp, tmp);
  }

  if (!is_large) {
    Label LOOP, LONG;
    cmpptr(cnt, InitArrayShortSize/BytesPerLong);
    jccb(Assembler::greater, LONG);

    NOT_LP64(shlptr(cnt, 1);) // convert to number of 32-bit words for 32-bit VM

    decrement(cnt);
    jccb(Assembler::negative, DONE); // Zero length

    // Use individual pointer-sized stores for small counts:
    BIND(LOOP);
    movptr(Address(base, cnt, Address::times_ptr), tmp);
    decrement(cnt);
    jccb(Assembler::greaterEqual, LOOP);
    jmpb(DONE);

    BIND(LONG);
  }

  // Use longer rep-prefixed ops for non-small counts:
  if (UseFastStosb) {
    shlptr(cnt, 3); // convert to number of bytes
    rep_stosb();
  } else if (UseXMMForObjInit) {
    movptr(tmp, base);
    xmm_clear_mem(tmp, cnt, xtmp);
  } else {
    NOT_LP64(shlptr(cnt, 1);) // convert to number of 32-bit words for 32-bit VM
    rep_stos();
  }

  BIND(DONE);
}

#ifdef COMPILER2

// IndexOf for constant substrings with size >= 8 chars
// which don't need to be loaded through stack.
void MacroAssembler::string_indexofC8(Register str1, Register str2,
                                      Register cnt1, Register cnt2,
                                      int int_cnt2,  Register result,
                                      XMMRegister vec, Register tmp,
                                      int ae) {
  ShortBranchVerifier sbv(this);
  assert(UseSSE42Intrinsics, "SSE4.2 intrinsics are required");
  assert(ae != StrIntrinsicNode::LU, "Invalid encoding");

  // This method uses the pcmpestri instruction with bound registers
  //   inputs:
  //     xmm - substring
  //     rax - substring length (elements count)
  //     mem - scanned string
  //     rdx - string length (elements count)
  //     0xd - mode: 1100 (substring search) + 01 (unsigned shorts)
  //     0xc - mode: 1100 (substring search) + 00 (unsigned bytes)
  //   outputs:
  //     rcx - matched index in string
  assert(cnt1 == rdx && cnt2 == rax && tmp == rcx, "pcmpestri");
  int mode   = (ae == StrIntrinsicNode::LL) ? 0x0c : 0x0d; // bytes or shorts
  int stride = (ae == StrIntrinsicNode::LL) ? 16 : 8; //UU, UL -> 8
  Address::ScaleFactor scale1 = (ae == StrIntrinsicNode::LL) ? Address::times_1 : Address::times_2;
  Address::ScaleFactor scale2 = (ae == StrIntrinsicNode::UL) ? Address::times_1 : scale1;

  Label RELOAD_SUBSTR, SCAN_TO_SUBSTR, SCAN_SUBSTR,
        RET_FOUND, RET_NOT_FOUND, EXIT, FOUND_SUBSTR,
        MATCH_SUBSTR_HEAD, RELOAD_STR, FOUND_CANDIDATE;

  // Note, inline_string_indexOf() generates checks:
  // if (substr.count > string.count) return -1;
  // if (substr.count == 0) return 0;
  assert(int_cnt2 >= stride, "this code is used only for cnt2 >= 8 chars");

  // Load substring.
  if (ae == StrIntrinsicNode::UL) {
    pmovzxbw(vec, Address(str2, 0));
  } else {
    movdqu(vec, Address(str2, 0));
  }
  movl(cnt2, int_cnt2);
  movptr(result, str1); // string addr

  if (int_cnt2 > stride) {
    jmpb(SCAN_TO_SUBSTR);

    // Reload substr for rescan, this code
    // is executed only for large substrings (> 8 chars)
    bind(RELOAD_SUBSTR);
    if (ae == StrIntrinsicNode::UL) {
      pmovzxbw(vec, Address(str2, 0));
    } else {
      movdqu(vec, Address(str2, 0));
    }
    negptr(cnt2); // Jumped here with negative cnt2, convert to positive

    bind(RELOAD_STR);
    // We came here after the beginning of the substring was
    // matched but the rest of it was not so we need to search
    // again. Start from the next element after the previous match.

    // cnt2 is number of substring reminding elements and
    // cnt1 is number of string reminding elements when cmp failed.
    // Restored cnt1 = cnt1 - cnt2 + int_cnt2
    subl(cnt1, cnt2);
    addl(cnt1, int_cnt2);
    movl(cnt2, int_cnt2); // Now restore cnt2

    decrementl(cnt1);     // Shift to next element
    cmpl(cnt1, cnt2);
    jcc(Assembler::negative, RET_NOT_FOUND);  // Left less then substring

    addptr(result, (1<<scale1));

  } // (int_cnt2 > 8)

  // Scan string for start of substr in 16-byte vectors
  bind(SCAN_TO_SUBSTR);
  pcmpestri(vec, Address(result, 0), mode);
  jccb(Assembler::below, FOUND_CANDIDATE);   // CF == 1
  subl(cnt1, stride);
  jccb(Assembler::lessEqual, RET_NOT_FOUND); // Scanned full string
  cmpl(cnt1, cnt2);
  jccb(Assembler::negative, RET_NOT_FOUND);  // Left less then substring
  addptr(result, 16);
  jmpb(SCAN_TO_SUBSTR);

  // Found a potential substr
  bind(FOUND_CANDIDATE);
  // Matched whole vector if first element matched (tmp(rcx) == 0).
  if (int_cnt2 == stride) {
    jccb(Assembler::overflow, RET_FOUND);    // OF == 1
  } else { // int_cnt2 > 8
    jccb(Assembler::overflow, FOUND_SUBSTR);
  }
  // After pcmpestri tmp(rcx) contains matched element index
  // Compute start addr of substr
  lea(result, Address(result, tmp, scale1));

  // Make sure string is still long enough
  subl(cnt1, tmp);
  cmpl(cnt1, cnt2);
  if (int_cnt2 == stride) {
    jccb(Assembler::greaterEqual, SCAN_TO_SUBSTR);
  } else { // int_cnt2 > 8
    jccb(Assembler::greaterEqual, MATCH_SUBSTR_HEAD);
  }
  // Left less then substring.

  bind(RET_NOT_FOUND);
  movl(result, -1);
  jmp(EXIT);

  if (int_cnt2 > stride) {
    // This code is optimized for the case when whole substring
    // is matched if its head is matched.
    bind(MATCH_SUBSTR_HEAD);
    pcmpestri(vec, Address(result, 0), mode);
    // Reload only string if does not match
    jcc(Assembler::noOverflow, RELOAD_STR); // OF == 0

    Label CONT_SCAN_SUBSTR;
    // Compare the rest of substring (> 8 chars).
    bind(FOUND_SUBSTR);
    // First 8 chars are already matched.
    negptr(cnt2);
    addptr(cnt2, stride);

    bind(SCAN_SUBSTR);
    subl(cnt1, stride);
    cmpl(cnt2, -stride); // Do not read beyond substring
    jccb(Assembler::lessEqual, CONT_SCAN_SUBSTR);
    // Back-up strings to avoid reading beyond substring:
    // cnt1 = cnt1 - cnt2 + 8
    addl(cnt1, cnt2); // cnt2 is negative
    addl(cnt1, stride);
    movl(cnt2, stride); negptr(cnt2);
    bind(CONT_SCAN_SUBSTR);
    if (int_cnt2 < (int)G) {
      int tail_off1 = int_cnt2<<scale1;
      int tail_off2 = int_cnt2<<scale2;
      if (ae == StrIntrinsicNode::UL) {
        pmovzxbw(vec, Address(str2, cnt2, scale2, tail_off2));
      } else {
        movdqu(vec, Address(str2, cnt2, scale2, tail_off2));
      }
      pcmpestri(vec, Address(result, cnt2, scale1, tail_off1), mode);
    } else {
      // calculate index in register to avoid integer overflow (int_cnt2*2)
      movl(tmp, int_cnt2);
      addptr(tmp, cnt2);
      if (ae == StrIntrinsicNode::UL) {
        pmovzxbw(vec, Address(str2, tmp, scale2, 0));
      } else {
        movdqu(vec, Address(str2, tmp, scale2, 0));
      }
      pcmpestri(vec, Address(result, tmp, scale1, 0), mode);
    }
    // Need to reload strings pointers if not matched whole vector
    jcc(Assembler::noOverflow, RELOAD_SUBSTR); // OF == 0
    addptr(cnt2, stride);
    jcc(Assembler::negative, SCAN_SUBSTR);
    // Fall through if found full substring

  } // (int_cnt2 > 8)

  bind(RET_FOUND);
  // Found result if we matched full small substring.
  // Compute substr offset
  subptr(result, str1);
  if (ae == StrIntrinsicNode::UU || ae == StrIntrinsicNode::UL) {
    shrl(result, 1); // index
  }
  bind(EXIT);

} // string_indexofC8

// Small strings are loaded through stack if they cross page boundary.
void MacroAssembler::string_indexof(Register str1, Register str2,
                                    Register cnt1, Register cnt2,
                                    int int_cnt2,  Register result,
                                    XMMRegister vec, Register tmp,
                                    int ae) {
  ShortBranchVerifier sbv(this);
  assert(UseSSE42Intrinsics, "SSE4.2 intrinsics are required");
  assert(ae != StrIntrinsicNode::LU, "Invalid encoding");

  //
  // int_cnt2 is length of small (< 8 chars) constant substring
  // or (-1) for non constant substring in which case its length
  // is in cnt2 register.
  //
  // Note, inline_string_indexOf() generates checks:
  // if (substr.count > string.count) return -1;
  // if (substr.count == 0) return 0;
  //
  int stride = (ae == StrIntrinsicNode::LL) ? 16 : 8; //UU, UL -> 8
  assert(int_cnt2 == -1 || (0 < int_cnt2 && int_cnt2 < stride), "should be != 0");
  // This method uses the pcmpestri instruction with bound registers
  //   inputs:
  //     xmm - substring
  //     rax - substring length (elements count)
  //     mem - scanned string
  //     rdx - string length (elements count)
  //     0xd - mode: 1100 (substring search) + 01 (unsigned shorts)
  //     0xc - mode: 1100 (substring search) + 00 (unsigned bytes)
  //   outputs:
  //     rcx - matched index in string
  assert(cnt1 == rdx && cnt2 == rax && tmp == rcx, "pcmpestri");
  int mode = (ae == StrIntrinsicNode::LL) ? 0x0c : 0x0d; // bytes or shorts
  Address::ScaleFactor scale1 = (ae == StrIntrinsicNode::LL) ? Address::times_1 : Address::times_2;
  Address::ScaleFactor scale2 = (ae == StrIntrinsicNode::UL) ? Address::times_1 : scale1;

  Label RELOAD_SUBSTR, SCAN_TO_SUBSTR, SCAN_SUBSTR, ADJUST_STR,
        RET_FOUND, RET_NOT_FOUND, CLEANUP, FOUND_SUBSTR,
        FOUND_CANDIDATE;

  { //========================================================
    // We don't know where these strings are located
    // and we can't read beyond them. Load them through stack.
    Label BIG_STRINGS, CHECK_STR, COPY_SUBSTR, COPY_STR;

    movptr(tmp, rsp); // save old SP

    if (int_cnt2 > 0) {     // small (< 8 chars) constant substring
      if (int_cnt2 == (1>>scale2)) { // One byte
        assert((ae == StrIntrinsicNode::LL || ae == StrIntrinsicNode::UL), "Only possible for latin1 encoding");
        load_unsigned_byte(result, Address(str2, 0));
        movdl(vec, result); // move 32 bits
      } else if (ae == StrIntrinsicNode::LL && int_cnt2 == 3) {  // Three bytes
        // Not enough header space in 32-bit VM: 12+3 = 15.
        movl(result, Address(str2, -1));
        shrl(result, 8);
        movdl(vec, result); // move 32 bits
      } else if (ae != StrIntrinsicNode::UL && int_cnt2 == (2>>scale2)) {  // One char
        load_unsigned_short(result, Address(str2, 0));
        movdl(vec, result); // move 32 bits
      } else if (ae != StrIntrinsicNode::UL && int_cnt2 == (4>>scale2)) { // Two chars
        movdl(vec, Address(str2, 0)); // move 32 bits
      } else if (ae != StrIntrinsicNode::UL && int_cnt2 == (8>>scale2)) { // Four chars
        movq(vec, Address(str2, 0));  // move 64 bits
      } else { // cnt2 = { 3, 5, 6, 7 } || (ae == StrIntrinsicNode::UL && cnt2 ={2, ..., 7})
        // Array header size is 12 bytes in 32-bit VM
        // + 6 bytes for 3 chars == 18 bytes,
        // enough space to load vec and shift.
        assert(HeapWordSize*TypeArrayKlass::header_size() >= 12,"sanity");
        if (ae == StrIntrinsicNode::UL) {
          int tail_off = int_cnt2-8;
          pmovzxbw(vec, Address(str2, tail_off));
          psrldq(vec, -2*tail_off);
        }
        else {
          int tail_off = int_cnt2*(1<<scale2);
          movdqu(vec, Address(str2, tail_off-16));
          psrldq(vec, 16-tail_off);
        }
      }
    } else { // not constant substring
      cmpl(cnt2, stride);
      jccb(Assembler::aboveEqual, BIG_STRINGS); // Both strings are big enough

      // We can read beyond string if srt+16 does not cross page boundary
      // since heaps are aligned and mapped by pages.
      assert(os::vm_page_size() < (int)G, "default page should be small");
      movl(result, str2); // We need only low 32 bits
      andl(result, (os::vm_page_size()-1));
      cmpl(result, (os::vm_page_size()-16));
      jccb(Assembler::belowEqual, CHECK_STR);

      // Move small strings to stack to allow load 16 bytes into vec.
      subptr(rsp, 16);
      int stk_offset = wordSize-(1<<scale2);
      push(cnt2);

      bind(COPY_SUBSTR);
      if (ae == StrIntrinsicNode::LL || ae == StrIntrinsicNode::UL) {
        load_unsigned_byte(result, Address(str2, cnt2, scale2, -1));
        movb(Address(rsp, cnt2, scale2, stk_offset), result);
      } else if (ae == StrIntrinsicNode::UU) {
        load_unsigned_short(result, Address(str2, cnt2, scale2, -2));
        movw(Address(rsp, cnt2, scale2, stk_offset), result);
      }
      decrement(cnt2);
      jccb(Assembler::notZero, COPY_SUBSTR);

      pop(cnt2);
      movptr(str2, rsp);  // New substring address
    } // non constant

    bind(CHECK_STR);
    cmpl(cnt1, stride);
    jccb(Assembler::aboveEqual, BIG_STRINGS);

    // Check cross page boundary.
    movl(result, str1); // We need only low 32 bits
    andl(result, (os::vm_page_size()-1));
    cmpl(result, (os::vm_page_size()-16));
    jccb(Assembler::belowEqual, BIG_STRINGS);

    subptr(rsp, 16);
    int stk_offset = -(1<<scale1);
    if (int_cnt2 < 0) { // not constant
      push(cnt2);
      stk_offset += wordSize;
    }
    movl(cnt2, cnt1);

    bind(COPY_STR);
    if (ae == StrIntrinsicNode::LL) {
      load_unsigned_byte(result, Address(str1, cnt2, scale1, -1));
      movb(Address(rsp, cnt2, scale1, stk_offset), result);
    } else {
      load_unsigned_short(result, Address(str1, cnt2, scale1, -2));
      movw(Address(rsp, cnt2, scale1, stk_offset), result);
    }
    decrement(cnt2);
    jccb(Assembler::notZero, COPY_STR);

    if (int_cnt2 < 0) { // not constant
      pop(cnt2);
    }
    movptr(str1, rsp);  // New string address

    bind(BIG_STRINGS);
    // Load substring.
    if (int_cnt2 < 0) { // -1
      if (ae == StrIntrinsicNode::UL) {
        pmovzxbw(vec, Address(str2, 0));
      } else {
        movdqu(vec, Address(str2, 0));
      }
      push(cnt2);       // substr count
      push(str2);       // substr addr
      push(str1);       // string addr
    } else {
      // Small (< 8 chars) constant substrings are loaded already.
      movl(cnt2, int_cnt2);
    }
    push(tmp);  // original SP

  } // Finished loading

  //========================================================
  // Start search
  //

  movptr(result, str1); // string addr

  if (int_cnt2  < 0) {  // Only for non constant substring
    jmpb(SCAN_TO_SUBSTR);

    // SP saved at sp+0
    // String saved at sp+1*wordSize
    // Substr saved at sp+2*wordSize
    // Substr count saved at sp+3*wordSize

    // Reload substr for rescan, this code
    // is executed only for large substrings (> 8 chars)
    bind(RELOAD_SUBSTR);
    movptr(str2, Address(rsp, 2*wordSize));
    movl(cnt2, Address(rsp, 3*wordSize));
    if (ae == StrIntrinsicNode::UL) {
      pmovzxbw(vec, Address(str2, 0));
    } else {
      movdqu(vec, Address(str2, 0));
    }
    // We came here after the beginning of the substring was
    // matched but the rest of it was not so we need to search
    // again. Start from the next element after the previous match.
    subptr(str1, result); // Restore counter
    if (ae == StrIntrinsicNode::UU || ae == StrIntrinsicNode::UL) {
      shrl(str1, 1);
    }
    addl(cnt1, str1);
    decrementl(cnt1);   // Shift to next element
    cmpl(cnt1, cnt2);
    jcc(Assembler::negative, RET_NOT_FOUND);  // Left less then substring

    addptr(result, (1<<scale1));
  } // non constant

  // Scan string for start of substr in 16-byte vectors
  bind(SCAN_TO_SUBSTR);
  assert(cnt1 == rdx && cnt2 == rax && tmp == rcx, "pcmpestri");
  pcmpestri(vec, Address(result, 0), mode);
  jccb(Assembler::below, FOUND_CANDIDATE);   // CF == 1
  subl(cnt1, stride);
  jccb(Assembler::lessEqual, RET_NOT_FOUND); // Scanned full string
  cmpl(cnt1, cnt2);
  jccb(Assembler::negative, RET_NOT_FOUND);  // Left less then substring
  addptr(result, 16);

  bind(ADJUST_STR);
  cmpl(cnt1, stride); // Do not read beyond string
  jccb(Assembler::greaterEqual, SCAN_TO_SUBSTR);
  // Back-up string to avoid reading beyond string.
  lea(result, Address(result, cnt1, scale1, -16));
  movl(cnt1, stride);
  jmpb(SCAN_TO_SUBSTR);

  // Found a potential substr
  bind(FOUND_CANDIDATE);
  // After pcmpestri tmp(rcx) contains matched element index

  // Make sure string is still long enough
  subl(cnt1, tmp);
  cmpl(cnt1, cnt2);
  jccb(Assembler::greaterEqual, FOUND_SUBSTR);
  // Left less then substring.

  bind(RET_NOT_FOUND);
  movl(result, -1);
  jmpb(CLEANUP);

  bind(FOUND_SUBSTR);
  // Compute start addr of substr
  lea(result, Address(result, tmp, scale1));
  if (int_cnt2 > 0) { // Constant substring
    // Repeat search for small substring (< 8 chars)
    // from new point without reloading substring.
    // Have to check that we don't read beyond string.
    cmpl(tmp, stride-int_cnt2);
    jccb(Assembler::greater, ADJUST_STR);
    // Fall through if matched whole substring.
  } else { // non constant
    assert(int_cnt2 == -1, "should be != 0");

    addl(tmp, cnt2);
    // Found result if we matched whole substring.
    cmpl(tmp, stride);
    jccb(Assembler::lessEqual, RET_FOUND);

    // Repeat search for small substring (<= 8 chars)
    // from new point 'str1' without reloading substring.
    cmpl(cnt2, stride);
    // Have to check that we don't read beyond string.
    jccb(Assembler::lessEqual, ADJUST_STR);

    Label CHECK_NEXT, CONT_SCAN_SUBSTR, RET_FOUND_LONG;
    // Compare the rest of substring (> 8 chars).
    movptr(str1, result);

    cmpl(tmp, cnt2);
    // First 8 chars are already matched.
    jccb(Assembler::equal, CHECK_NEXT);

    bind(SCAN_SUBSTR);
    pcmpestri(vec, Address(str1, 0), mode);
    // Need to reload strings pointers if not matched whole vector
    jcc(Assembler::noOverflow, RELOAD_SUBSTR); // OF == 0

    bind(CHECK_NEXT);
    subl(cnt2, stride);
    jccb(Assembler::lessEqual, RET_FOUND_LONG); // Found full substring
    addptr(str1, 16);
    if (ae == StrIntrinsicNode::UL) {
      addptr(str2, 8);
    } else {
      addptr(str2, 16);
    }
    subl(cnt1, stride);
    cmpl(cnt2, stride); // Do not read beyond substring
    jccb(Assembler::greaterEqual, CONT_SCAN_SUBSTR);
    // Back-up strings to avoid reading beyond substring.

    if (ae == StrIntrinsicNode::UL) {
      lea(str2, Address(str2, cnt2, scale2, -8));
      lea(str1, Address(str1, cnt2, scale1, -16));
    } else {
      lea(str2, Address(str2, cnt2, scale2, -16));
      lea(str1, Address(str1, cnt2, scale1, -16));
    }
    subl(cnt1, cnt2);
    movl(cnt2, stride);
    addl(cnt1, stride);
    bind(CONT_SCAN_SUBSTR);
    if (ae == StrIntrinsicNode::UL) {
      pmovzxbw(vec, Address(str2, 0));
    } else {
      movdqu(vec, Address(str2, 0));
    }
    jmp(SCAN_SUBSTR);

    bind(RET_FOUND_LONG);
    movptr(str1, Address(rsp, wordSize));
  } // non constant

  bind(RET_FOUND);
  // Compute substr offset
  subptr(result, str1);
  if (ae == StrIntrinsicNode::UU || ae == StrIntrinsicNode::UL) {
    shrl(result, 1); // index
  }
  bind(CLEANUP);
  pop(rsp); // restore SP

} // string_indexof

void MacroAssembler::string_indexof_char(Register str1, Register cnt1, Register ch, Register result,
                                         XMMRegister vec1, XMMRegister vec2, XMMRegister vec3, Register tmp) {
  ShortBranchVerifier sbv(this);
  assert(UseSSE42Intrinsics, "SSE4.2 intrinsics are required");

  int stride = 8;

  Label FOUND_CHAR, SCAN_TO_CHAR, SCAN_TO_CHAR_LOOP,
        SCAN_TO_8_CHAR, SCAN_TO_8_CHAR_LOOP, SCAN_TO_16_CHAR_LOOP,
        RET_NOT_FOUND, SCAN_TO_8_CHAR_INIT,
        FOUND_SEQ_CHAR, DONE_LABEL;

  movptr(result, str1);
  if (UseAVX >= 2) {
    cmpl(cnt1, stride);
    jcc(Assembler::less, SCAN_TO_CHAR);
    cmpl(cnt1, 2*stride);
    jcc(Assembler::less, SCAN_TO_8_CHAR_INIT);
    movdl(vec1, ch);
    vpbroadcastw(vec1, vec1, Assembler::AVX_256bit);
    vpxor(vec2, vec2);
    movl(tmp, cnt1);
    andl(tmp, 0xFFFFFFF0);  //vector count (in chars)
    andl(cnt1,0x0000000F);  //tail count (in chars)

    bind(SCAN_TO_16_CHAR_LOOP);
    vmovdqu(vec3, Address(result, 0));
    vpcmpeqw(vec3, vec3, vec1, 1);
    vptest(vec2, vec3);
    jcc(Assembler::carryClear, FOUND_CHAR);
    addptr(result, 32);
    subl(tmp, 2*stride);
    jccb(Assembler::notZero, SCAN_TO_16_CHAR_LOOP);
    jmp(SCAN_TO_8_CHAR);
    bind(SCAN_TO_8_CHAR_INIT);
    movdl(vec1, ch);
    pshuflw(vec1, vec1, 0x00);
    pshufd(vec1, vec1, 0);
    pxor(vec2, vec2);
  }
  bind(SCAN_TO_8_CHAR);
  cmpl(cnt1, stride);
  jcc(Assembler::less, SCAN_TO_CHAR);
  if (UseAVX < 2) {
    movdl(vec1, ch);
    pshuflw(vec1, vec1, 0x00);
    pshufd(vec1, vec1, 0);
    pxor(vec2, vec2);
  }
  movl(tmp, cnt1);
  andl(tmp, 0xFFFFFFF8);  //vector count (in chars)
  andl(cnt1,0x00000007);  //tail count (in chars)

  bind(SCAN_TO_8_CHAR_LOOP);
  movdqu(vec3, Address(result, 0));
  pcmpeqw(vec3, vec1);
  ptest(vec2, vec3);
  jcc(Assembler::carryClear, FOUND_CHAR);
  addptr(result, 16);
  subl(tmp, stride);
  jccb(Assembler::notZero, SCAN_TO_8_CHAR_LOOP);
  bind(SCAN_TO_CHAR);
  testl(cnt1, cnt1);
  jcc(Assembler::zero, RET_NOT_FOUND);
  bind(SCAN_TO_CHAR_LOOP);
  load_unsigned_short(tmp, Address(result, 0));
  cmpl(ch, tmp);
  jccb(Assembler::equal, FOUND_SEQ_CHAR);
  addptr(result, 2);
  subl(cnt1, 1);
  jccb(Assembler::zero, RET_NOT_FOUND);
  jmp(SCAN_TO_CHAR_LOOP);

  bind(RET_NOT_FOUND);
  movl(result, -1);
  jmpb(DONE_LABEL);

  bind(FOUND_CHAR);
  if (UseAVX >= 2) {
    vpmovmskb(tmp, vec3);
  } else {
    pmovmskb(tmp, vec3);
  }
  bsfl(ch, tmp);
  addptr(result, ch);

  bind(FOUND_SEQ_CHAR);
  subptr(result, str1);
  shrl(result, 1);

  bind(DONE_LABEL);
} // string_indexof_char

// helper function for string_compare
void MacroAssembler::load_next_elements(Register elem1, Register elem2, Register str1, Register str2,
                                        Address::ScaleFactor scale, Address::ScaleFactor scale1,
                                        Address::ScaleFactor scale2, Register index, int ae) {
  if (ae == StrIntrinsicNode::LL) {
    load_unsigned_byte(elem1, Address(str1, index, scale, 0));
    load_unsigned_byte(elem2, Address(str2, index, scale, 0));
  } else if (ae == StrIntrinsicNode::UU) {
    load_unsigned_short(elem1, Address(str1, index, scale, 0));
    load_unsigned_short(elem2, Address(str2, index, scale, 0));
  } else {
    load_unsigned_byte(elem1, Address(str1, index, scale1, 0));
    load_unsigned_short(elem2, Address(str2, index, scale2, 0));
  }
}

// Compare strings, used for char[] and byte[].
void MacroAssembler::string_compare(Register str1, Register str2,
                                    Register cnt1, Register cnt2, Register result,
                                    XMMRegister vec1, int ae) {
  ShortBranchVerifier sbv(this);
  Label LENGTH_DIFF_LABEL, POP_LABEL, DONE_LABEL, WHILE_HEAD_LABEL;
  Label COMPARE_WIDE_VECTORS_LOOP_FAILED;  // used only _LP64 && AVX3
  int stride, stride2, adr_stride, adr_stride1, adr_stride2;
  int stride2x2 = 0x40;
  Address::ScaleFactor scale = Address::no_scale;
  Address::ScaleFactor scale1 = Address::no_scale;
  Address::ScaleFactor scale2 = Address::no_scale;

  if (ae != StrIntrinsicNode::LL) {
    stride2x2 = 0x20;
  }

  if (ae == StrIntrinsicNode::LU || ae == StrIntrinsicNode::UL) {
    shrl(cnt2, 1);
  }
  // Compute the minimum of the string lengths and the
  // difference of the string lengths (stack).
  // Do the conditional move stuff
  movl(result, cnt1);
  subl(cnt1, cnt2);
  push(cnt1);
  cmov32(Assembler::lessEqual, cnt2, result);    // cnt2 = min(cnt1, cnt2)

  // Is the minimum length zero?
  testl(cnt2, cnt2);
  jcc(Assembler::zero, LENGTH_DIFF_LABEL);
  if (ae == StrIntrinsicNode::LL) {
    // Load first bytes
    load_unsigned_byte(result, Address(str1, 0));  // result = str1[0]
    load_unsigned_byte(cnt1, Address(str2, 0));    // cnt1   = str2[0]
  } else if (ae == StrIntrinsicNode::UU) {
    // Load first characters
    load_unsigned_short(result, Address(str1, 0));
    load_unsigned_short(cnt1, Address(str2, 0));
  } else {
    load_unsigned_byte(result, Address(str1, 0));
    load_unsigned_short(cnt1, Address(str2, 0));
  }
  subl(result, cnt1);
  jcc(Assembler::notZero,  POP_LABEL);

  if (ae == StrIntrinsicNode::UU) {
    // Divide length by 2 to get number of chars
    shrl(cnt2, 1);
  }
  cmpl(cnt2, 1);
  jcc(Assembler::equal, LENGTH_DIFF_LABEL);

  // Check if the strings start at the same location and setup scale and stride
  if (ae == StrIntrinsicNode::LL || ae == StrIntrinsicNode::UU) {
    cmpptr(str1, str2);
    jcc(Assembler::equal, LENGTH_DIFF_LABEL);
    if (ae == StrIntrinsicNode::LL) {
      scale = Address::times_1;
      stride = 16;
    } else {
      scale = Address::times_2;
      stride = 8;
    }
  } else {
    scale1 = Address::times_1;
    scale2 = Address::times_2;
    // scale not used
    stride = 8;
  }

  if (UseAVX >= 2 && UseSSE42Intrinsics) {
    Label COMPARE_WIDE_VECTORS, VECTOR_NOT_EQUAL, COMPARE_WIDE_TAIL, COMPARE_SMALL_STR;
    Label COMPARE_WIDE_VECTORS_LOOP, COMPARE_16_CHARS, COMPARE_INDEX_CHAR;
    Label COMPARE_WIDE_VECTORS_LOOP_AVX2;
    Label COMPARE_TAIL_LONG;
    Label COMPARE_WIDE_VECTORS_LOOP_AVX3;  // used only _LP64 && AVX3

    int pcmpmask = 0x19;
    if (ae == StrIntrinsicNode::LL) {
      pcmpmask &= ~0x01;
    }

    // Setup to compare 16-chars (32-bytes) vectors,
    // start from first character again because it has aligned address.
    if (ae == StrIntrinsicNode::LL) {
      stride2 = 32;
    } else {
      stride2 = 16;
    }
    if (ae == StrIntrinsicNode::LL || ae == StrIntrinsicNode::UU) {
      adr_stride = stride << scale;
    } else {
      adr_stride1 = 8;  //stride << scale1;
      adr_stride2 = 16; //stride << scale2;
    }

    assert(result == rax && cnt2 == rdx && cnt1 == rcx, "pcmpestri");
    // rax and rdx are used by pcmpestri as elements counters
    movl(result, cnt2);
    andl(cnt2, ~(stride2-1));   // cnt2 holds the vector count
    jcc(Assembler::zero, COMPARE_TAIL_LONG);

    // fast path : compare first 2 8-char vectors.
    bind(COMPARE_16_CHARS);
    if (ae == StrIntrinsicNode::LL || ae == StrIntrinsicNode::UU) {
      movdqu(vec1, Address(str1, 0));
    } else {
      pmovzxbw(vec1, Address(str1, 0));
    }
    pcmpestri(vec1, Address(str2, 0), pcmpmask);
    jccb(Assembler::below, COMPARE_INDEX_CHAR);

    if (ae == StrIntrinsicNode::LL || ae == StrIntrinsicNode::UU) {
      movdqu(vec1, Address(str1, adr_stride));
      pcmpestri(vec1, Address(str2, adr_stride), pcmpmask);
    } else {
      pmovzxbw(vec1, Address(str1, adr_stride1));
      pcmpestri(vec1, Address(str2, adr_stride2), pcmpmask);
    }
    jccb(Assembler::aboveEqual, COMPARE_WIDE_VECTORS);
    addl(cnt1, stride);

    // Compare the characters at index in cnt1
    bind(COMPARE_INDEX_CHAR); // cnt1 has the offset of the mismatching character
    load_next_elements(result, cnt2, str1, str2, scale, scale1, scale2, cnt1, ae);
    subl(result, cnt2);
    jmp(POP_LABEL);

    // Setup the registers to start vector comparison loop
    bind(COMPARE_WIDE_VECTORS);
    if (ae == StrIntrinsicNode::LL || ae == StrIntrinsicNode::UU) {
      lea(str1, Address(str1, result, scale));
      lea(str2, Address(str2, result, scale));
    } else {
      lea(str1, Address(str1, result, scale1));
      lea(str2, Address(str2, result, scale2));
    }
    subl(result, stride2);
    subl(cnt2, stride2);
    jcc(Assembler::zero, COMPARE_WIDE_TAIL);
    negptr(result);

    //  In a loop, compare 16-chars (32-bytes) at once using (vpxor+vptest)
    bind(COMPARE_WIDE_VECTORS_LOOP);

#ifdef _LP64
    if ((AVX3Threshold == 0) && VM_Version::supports_avx512vlbw()) { // trying 64 bytes fast loop
      cmpl(cnt2, stride2x2);
      jccb(Assembler::below, COMPARE_WIDE_VECTORS_LOOP_AVX2);
      testl(cnt2, stride2x2-1);   // cnt2 holds the vector count
      jccb(Assembler::notZero, COMPARE_WIDE_VECTORS_LOOP_AVX2);   // means we cannot subtract by 0x40

      bind(COMPARE_WIDE_VECTORS_LOOP_AVX3); // the hottest loop
      if (ae == StrIntrinsicNode::LL || ae == StrIntrinsicNode::UU) {
        evmovdquq(vec1, Address(str1, result, scale), Assembler::AVX_512bit);
        evpcmpeqb(k7, vec1, Address(str2, result, scale), Assembler::AVX_512bit); // k7 == 11..11, if operands equal, otherwise k7 has some 0
      } else {
        vpmovzxbw(vec1, Address(str1, result, scale1), Assembler::AVX_512bit);
        evpcmpeqb(k7, vec1, Address(str2, result, scale2), Assembler::AVX_512bit); // k7 == 11..11, if operands equal, otherwise k7 has some 0
      }
      kortestql(k7, k7);
      jcc(Assembler::aboveEqual, COMPARE_WIDE_VECTORS_LOOP_FAILED);     // miscompare
      addptr(result, stride2x2);  // update since we already compared at this addr
      subl(cnt2, stride2x2);      // and sub the size too
      jccb(Assembler::notZero, COMPARE_WIDE_VECTORS_LOOP_AVX3);

      vpxor(vec1, vec1);
      jmpb(COMPARE_WIDE_TAIL);
    }//if (VM_Version::supports_avx512vlbw())
#endif // _LP64


    bind(COMPARE_WIDE_VECTORS_LOOP_AVX2);
    if (ae == StrIntrinsicNode::LL || ae == StrIntrinsicNode::UU) {
      vmovdqu(vec1, Address(str1, result, scale));
      vpxor(vec1, Address(str2, result, scale));
    } else {
      vpmovzxbw(vec1, Address(str1, result, scale1), Assembler::AVX_256bit);
      vpxor(vec1, Address(str2, result, scale2));
    }
    vptest(vec1, vec1);
    jcc(Assembler::notZero, VECTOR_NOT_EQUAL);
    addptr(result, stride2);
    subl(cnt2, stride2);
    jcc(Assembler::notZero, COMPARE_WIDE_VECTORS_LOOP);
    // clean upper bits of YMM registers
    vpxor(vec1, vec1);

    // compare wide vectors tail
    bind(COMPARE_WIDE_TAIL);
    testptr(result, result);
    jcc(Assembler::zero, LENGTH_DIFF_LABEL);

    movl(result, stride2);
    movl(cnt2, result);
    negptr(result);
    jmp(COMPARE_WIDE_VECTORS_LOOP_AVX2);

    // Identifies the mismatching (higher or lower)16-bytes in the 32-byte vectors.
    bind(VECTOR_NOT_EQUAL);
    // clean upper bits of YMM registers
    vpxor(vec1, vec1);
    if (ae == StrIntrinsicNode::LL || ae == StrIntrinsicNode::UU) {
      lea(str1, Address(str1, result, scale));
      lea(str2, Address(str2, result, scale));
    } else {
      lea(str1, Address(str1, result, scale1));
      lea(str2, Address(str2, result, scale2));
    }
    jmp(COMPARE_16_CHARS);

    // Compare tail chars, length between 1 to 15 chars
    bind(COMPARE_TAIL_LONG);
    movl(cnt2, result);
    cmpl(cnt2, stride);
    jcc(Assembler::less, COMPARE_SMALL_STR);

    if (ae == StrIntrinsicNode::LL || ae == StrIntrinsicNode::UU) {
      movdqu(vec1, Address(str1, 0));
    } else {
      pmovzxbw(vec1, Address(str1, 0));
    }
    pcmpestri(vec1, Address(str2, 0), pcmpmask);
    jcc(Assembler::below, COMPARE_INDEX_CHAR);
    subptr(cnt2, stride);
    jcc(Assembler::zero, LENGTH_DIFF_LABEL);
    if (ae == StrIntrinsicNode::LL || ae == StrIntrinsicNode::UU) {
      lea(str1, Address(str1, result, scale));
      lea(str2, Address(str2, result, scale));
    } else {
      lea(str1, Address(str1, result, scale1));
      lea(str2, Address(str2, result, scale2));
    }
    negptr(cnt2);
    jmpb(WHILE_HEAD_LABEL);

    bind(COMPARE_SMALL_STR);
  } else if (UseSSE42Intrinsics) {
    Label COMPARE_WIDE_VECTORS, VECTOR_NOT_EQUAL, COMPARE_TAIL;
    int pcmpmask = 0x19;
    // Setup to compare 8-char (16-byte) vectors,
    // start from first character again because it has aligned address.
    movl(result, cnt2);
    andl(cnt2, ~(stride - 1));   // cnt2 holds the vector count
    if (ae == StrIntrinsicNode::LL) {
      pcmpmask &= ~0x01;
    }
    jcc(Assembler::zero, COMPARE_TAIL);
    if (ae == StrIntrinsicNode::LL || ae == StrIntrinsicNode::UU) {
      lea(str1, Address(str1, result, scale));
      lea(str2, Address(str2, result, scale));
    } else {
      lea(str1, Address(str1, result, scale1));
      lea(str2, Address(str2, result, scale2));
    }
    negptr(result);

    // pcmpestri
    //   inputs:
    //     vec1- substring
    //     rax - negative string length (elements count)
    //     mem - scanned string
    //     rdx - string length (elements count)
    //     pcmpmask - cmp mode: 11000 (string compare with negated result)
    //               + 00 (unsigned bytes) or  + 01 (unsigned shorts)
    //   outputs:
    //     rcx - first mismatched element index
    assert(result == rax && cnt2 == rdx && cnt1 == rcx, "pcmpestri");

    bind(COMPARE_WIDE_VECTORS);
    if (ae == StrIntrinsicNode::LL || ae == StrIntrinsicNode::UU) {
      movdqu(vec1, Address(str1, result, scale));
      pcmpestri(vec1, Address(str2, result, scale), pcmpmask);
    } else {
      pmovzxbw(vec1, Address(str1, result, scale1));
      pcmpestri(vec1, Address(str2, result, scale2), pcmpmask);
    }
    // After pcmpestri cnt1(rcx) contains mismatched element index

    jccb(Assembler::below, VECTOR_NOT_EQUAL);  // CF==1
    addptr(result, stride);
    subptr(cnt2, stride);
    jccb(Assembler::notZero, COMPARE_WIDE_VECTORS);

    // compare wide vectors tail
    testptr(result, result);
    jcc(Assembler::zero, LENGTH_DIFF_LABEL);

    movl(cnt2, stride);
    movl(result, stride);
    negptr(result);
    if (ae == StrIntrinsicNode::LL || ae == StrIntrinsicNode::UU) {
      movdqu(vec1, Address(str1, result, scale));
      pcmpestri(vec1, Address(str2, result, scale), pcmpmask);
    } else {
      pmovzxbw(vec1, Address(str1, result, scale1));
      pcmpestri(vec1, Address(str2, result, scale2), pcmpmask);
    }
    jccb(Assembler::aboveEqual, LENGTH_DIFF_LABEL);

    // Mismatched characters in the vectors
    bind(VECTOR_NOT_EQUAL);
    addptr(cnt1, result);
    load_next_elements(result, cnt2, str1, str2, scale, scale1, scale2, cnt1, ae);
    subl(result, cnt2);
    jmpb(POP_LABEL);

    bind(COMPARE_TAIL); // limit is zero
    movl(cnt2, result);
    // Fallthru to tail compare
  }
  // Shift str2 and str1 to the end of the arrays, negate min
  if (ae == StrIntrinsicNode::LL || ae == StrIntrinsicNode::UU) {
    lea(str1, Address(str1, cnt2, scale));
    lea(str2, Address(str2, cnt2, scale));
  } else {
    lea(str1, Address(str1, cnt2, scale1));
    lea(str2, Address(str2, cnt2, scale2));
  }
  decrementl(cnt2);  // first character was compared already
  negptr(cnt2);

  // Compare the rest of the elements
  bind(WHILE_HEAD_LABEL);
  load_next_elements(result, cnt1, str1, str2, scale, scale1, scale2, cnt2, ae);
  subl(result, cnt1);
  jccb(Assembler::notZero, POP_LABEL);
  increment(cnt2);
  jccb(Assembler::notZero, WHILE_HEAD_LABEL);

  // Strings are equal up to min length.  Return the length difference.
  bind(LENGTH_DIFF_LABEL);
  pop(result);
  if (ae == StrIntrinsicNode::UU) {
    // Divide diff by 2 to get number of chars
    sarl(result, 1);
  }
  jmpb(DONE_LABEL);

#ifdef _LP64
  if (VM_Version::supports_avx512vlbw()) {

    bind(COMPARE_WIDE_VECTORS_LOOP_FAILED);

    kmovql(cnt1, k7);
    notq(cnt1);
    bsfq(cnt2, cnt1);
    if (ae != StrIntrinsicNode::LL) {
      // Divide diff by 2 to get number of chars
      sarl(cnt2, 1);
    }
    addq(result, cnt2);
    if (ae == StrIntrinsicNode::LL) {
      load_unsigned_byte(cnt1, Address(str2, result));
      load_unsigned_byte(result, Address(str1, result));
    } else if (ae == StrIntrinsicNode::UU) {
      load_unsigned_short(cnt1, Address(str2, result, scale));
      load_unsigned_short(result, Address(str1, result, scale));
    } else {
      load_unsigned_short(cnt1, Address(str2, result, scale2));
      load_unsigned_byte(result, Address(str1, result, scale1));
    }
    subl(result, cnt1);
    jmpb(POP_LABEL);
  }//if (VM_Version::supports_avx512vlbw())
#endif // _LP64

  // Discard the stored length difference
  bind(POP_LABEL);
  pop(cnt1);

  // That's it
  bind(DONE_LABEL);
  if(ae == StrIntrinsicNode::UL) {
    negl(result);
  }

}

// Search for Non-ASCII character (Negative byte value) in a byte array,
// return true if it has any and false otherwise.
//   ..\jdk\src\java.base\share\classes\java\lang\StringCoding.java
//   @HotSpotIntrinsicCandidate
//   private static boolean hasNegatives(byte[] ba, int off, int len) {
//     for (int i = off; i < off + len; i++) {
//       if (ba[i] < 0) {
//         return true;
//       }
//     }
//     return false;
//   }
void MacroAssembler::has_negatives(Register ary1, Register len,
  Register result, Register tmp1,
  XMMRegister vec1, XMMRegister vec2) {
  // rsi: byte array
  // rcx: len
  // rax: result
  ShortBranchVerifier sbv(this);
  assert_different_registers(ary1, len, result, tmp1);
  assert_different_registers(vec1, vec2);
  Label TRUE_LABEL, FALSE_LABEL, DONE, COMPARE_CHAR, COMPARE_VECTORS, COMPARE_BYTE;

  // len == 0
  testl(len, len);
  jcc(Assembler::zero, FALSE_LABEL);

  if ((AVX3Threshold == 0) && (UseAVX > 2) && // AVX512
    VM_Version::supports_avx512vlbw() &&
    VM_Version::supports_bmi2()) {

    Label test_64_loop, test_tail;
    Register tmp3_aliased = len;

    movl(tmp1, len);
    vpxor(vec2, vec2, vec2, Assembler::AVX_512bit);

    andl(tmp1, 64 - 1);   // tail count (in chars) 0x3F
    andl(len, ~(64 - 1));    // vector count (in chars)
    jccb(Assembler::zero, test_tail);

    lea(ary1, Address(ary1, len, Address::times_1));
    negptr(len);

    bind(test_64_loop);
    // Check whether our 64 elements of size byte contain negatives
    evpcmpgtb(k2, vec2, Address(ary1, len, Address::times_1), Assembler::AVX_512bit);
    kortestql(k2, k2);
    jcc(Assembler::notZero, TRUE_LABEL);

    addptr(len, 64);
    jccb(Assembler::notZero, test_64_loop);


    bind(test_tail);
    // bail out when there is nothing to be done
    testl(tmp1, -1);
    jcc(Assembler::zero, FALSE_LABEL);

    // ~(~0 << len) applied up to two times (for 32-bit scenario)
#ifdef _LP64
    mov64(tmp3_aliased, 0xFFFFFFFFFFFFFFFF);
    shlxq(tmp3_aliased, tmp3_aliased, tmp1);
    notq(tmp3_aliased);
    kmovql(k3, tmp3_aliased);
#else
    Label k_init;
    jmp(k_init);

    // We could not read 64-bits from a general purpose register thus we move
    // data required to compose 64 1's to the instruction stream
    // We emit 64 byte wide series of elements from 0..63 which later on would
    // be used as a compare targets with tail count contained in tmp1 register.
    // Result would be a k register having tmp1 consecutive number or 1
    // counting from least significant bit.
    address tmp = pc();
    emit_int64(0x0706050403020100);
    emit_int64(0x0F0E0D0C0B0A0908);
    emit_int64(0x1716151413121110);
    emit_int64(0x1F1E1D1C1B1A1918);
    emit_int64(0x2726252423222120);
    emit_int64(0x2F2E2D2C2B2A2928);
    emit_int64(0x3736353433323130);
    emit_int64(0x3F3E3D3C3B3A3938);

    bind(k_init);
    lea(len, InternalAddress(tmp));
    // create mask to test for negative byte inside a vector
    evpbroadcastb(vec1, tmp1, Assembler::AVX_512bit);
    evpcmpgtb(k3, vec1, Address(len, 0), Assembler::AVX_512bit);

#endif
    evpcmpgtb(k2, k3, vec2, Address(ary1, 0), Assembler::AVX_512bit);
    ktestq(k2, k3);
    jcc(Assembler::notZero, TRUE_LABEL);

    jmp(FALSE_LABEL);
  } else {
    movl(result, len); // copy

    if (UseAVX >= 2 && UseSSE >= 2) {
      // With AVX2, use 32-byte vector compare
      Label COMPARE_WIDE_VECTORS, COMPARE_TAIL;

      // Compare 32-byte vectors
      andl(result, 0x0000001f);  //   tail count (in bytes)
      andl(len, 0xffffffe0);   // vector count (in bytes)
      jccb(Assembler::zero, COMPARE_TAIL);

      lea(ary1, Address(ary1, len, Address::times_1));
      negptr(len);

      movl(tmp1, 0x80808080);   // create mask to test for Unicode chars in vector
      movdl(vec2, tmp1);
      vpbroadcastd(vec2, vec2, Assembler::AVX_256bit);

      bind(COMPARE_WIDE_VECTORS);
      vmovdqu(vec1, Address(ary1, len, Address::times_1));
      vptest(vec1, vec2);
      jccb(Assembler::notZero, TRUE_LABEL);
      addptr(len, 32);
      jcc(Assembler::notZero, COMPARE_WIDE_VECTORS);

      testl(result, result);
      jccb(Assembler::zero, FALSE_LABEL);

      vmovdqu(vec1, Address(ary1, result, Address::times_1, -32));
      vptest(vec1, vec2);
      jccb(Assembler::notZero, TRUE_LABEL);
      jmpb(FALSE_LABEL);

      bind(COMPARE_TAIL); // len is zero
      movl(len, result);
      // Fallthru to tail compare
    } else if (UseSSE42Intrinsics) {
      // With SSE4.2, use double quad vector compare
      Label COMPARE_WIDE_VECTORS, COMPARE_TAIL;

      // Compare 16-byte vectors
      andl(result, 0x0000000f);  //   tail count (in bytes)
      andl(len, 0xfffffff0);   // vector count (in bytes)
      jccb(Assembler::zero, COMPARE_TAIL);

      lea(ary1, Address(ary1, len, Address::times_1));
      negptr(len);

      movl(tmp1, 0x80808080);
      movdl(vec2, tmp1);
      pshufd(vec2, vec2, 0);

      bind(COMPARE_WIDE_VECTORS);
      movdqu(vec1, Address(ary1, len, Address::times_1));
      ptest(vec1, vec2);
      jccb(Assembler::notZero, TRUE_LABEL);
      addptr(len, 16);
      jcc(Assembler::notZero, COMPARE_WIDE_VECTORS);

      testl(result, result);
      jccb(Assembler::zero, FALSE_LABEL);

      movdqu(vec1, Address(ary1, result, Address::times_1, -16));
      ptest(vec1, vec2);
      jccb(Assembler::notZero, TRUE_LABEL);
      jmpb(FALSE_LABEL);

      bind(COMPARE_TAIL); // len is zero
      movl(len, result);
      // Fallthru to tail compare
    }
  }
  // Compare 4-byte vectors
  andl(len, 0xfffffffc); // vector count (in bytes)
  jccb(Assembler::zero, COMPARE_CHAR);

  lea(ary1, Address(ary1, len, Address::times_1));
  negptr(len);

  bind(COMPARE_VECTORS);
  movl(tmp1, Address(ary1, len, Address::times_1));
  andl(tmp1, 0x80808080);
  jccb(Assembler::notZero, TRUE_LABEL);
  addptr(len, 4);
  jcc(Assembler::notZero, COMPARE_VECTORS);

  // Compare trailing char (final 2 bytes), if any
  bind(COMPARE_CHAR);
  testl(result, 0x2);   // tail  char
  jccb(Assembler::zero, COMPARE_BYTE);
  load_unsigned_short(tmp1, Address(ary1, 0));
  andl(tmp1, 0x00008080);
  jccb(Assembler::notZero, TRUE_LABEL);
  subptr(result, 2);
  lea(ary1, Address(ary1, 2));

  bind(COMPARE_BYTE);
  testl(result, 0x1);   // tail  byte
  jccb(Assembler::zero, FALSE_LABEL);
  load_unsigned_byte(tmp1, Address(ary1, 0));
  andl(tmp1, 0x00000080);
  jccb(Assembler::notEqual, TRUE_LABEL);
  jmpb(FALSE_LABEL);

  bind(TRUE_LABEL);
  movl(result, 1);   // return true
  jmpb(DONE);

  bind(FALSE_LABEL);
  xorl(result, result); // return false

  // That's it
  bind(DONE);
  if (UseAVX >= 2 && UseSSE >= 2) {
    // clean upper bits of YMM registers
    vpxor(vec1, vec1);
    vpxor(vec2, vec2);
  }
}
// Compare char[] or byte[] arrays aligned to 4 bytes or substrings.
void MacroAssembler::arrays_equals(bool is_array_equ, Register ary1, Register ary2,
                                   Register limit, Register result, Register chr,
                                   XMMRegister vec1, XMMRegister vec2, bool is_char) {
  ShortBranchVerifier sbv(this);
  Label TRUE_LABEL, FALSE_LABEL, DONE, COMPARE_VECTORS, COMPARE_CHAR, COMPARE_BYTE;

  int length_offset  = arrayOopDesc::length_offset_in_bytes();
  int base_offset    = arrayOopDesc::base_offset_in_bytes(is_char ? T_CHAR : T_BYTE);

  if (is_array_equ) {
    // Check the input args
    cmpoop(ary1, ary2);
    jcc(Assembler::equal, TRUE_LABEL);

    // Need additional checks for arrays_equals.
    testptr(ary1, ary1);
    jcc(Assembler::zero, FALSE_LABEL);
    testptr(ary2, ary2);
    jcc(Assembler::zero, FALSE_LABEL);

    // Check the lengths
    movl(limit, Address(ary1, length_offset));
    cmpl(limit, Address(ary2, length_offset));
    jcc(Assembler::notEqual, FALSE_LABEL);
  }

  // count == 0
  testl(limit, limit);
  jcc(Assembler::zero, TRUE_LABEL);

  if (is_array_equ) {
    // Load array address
    lea(ary1, Address(ary1, base_offset));
    lea(ary2, Address(ary2, base_offset));
  }

  if (is_array_equ && is_char) {
    // arrays_equals when used for char[].
    shll(limit, 1);      // byte count != 0
  }
  movl(result, limit); // copy

  if (UseAVX >= 2) {
    // With AVX2, use 32-byte vector compare
    Label COMPARE_WIDE_VECTORS, COMPARE_TAIL;

    // Compare 32-byte vectors
    andl(result, 0x0000001f);  //   tail count (in bytes)
    andl(limit, 0xffffffe0);   // vector count (in bytes)
    jcc(Assembler::zero, COMPARE_TAIL);

    lea(ary1, Address(ary1, limit, Address::times_1));
    lea(ary2, Address(ary2, limit, Address::times_1));
    negptr(limit);

#ifdef _LP64
    if ((AVX3Threshold == 0) && VM_Version::supports_avx512vlbw()) { // trying 64 bytes fast loop
      Label COMPARE_WIDE_VECTORS_LOOP_AVX2, COMPARE_WIDE_VECTORS_LOOP_AVX3;

      cmpl(limit, -64);
      jcc(Assembler::greater, COMPARE_WIDE_VECTORS_LOOP_AVX2);

      bind(COMPARE_WIDE_VECTORS_LOOP_AVX3); // the hottest loop

      evmovdquq(vec1, Address(ary1, limit, Address::times_1), Assembler::AVX_512bit);
      evpcmpeqb(k7, vec1, Address(ary2, limit, Address::times_1), Assembler::AVX_512bit);
      kortestql(k7, k7);
      jcc(Assembler::aboveEqual, FALSE_LABEL);     // miscompare
      addptr(limit, 64);  // update since we already compared at this addr
      cmpl(limit, -64);
      jccb(Assembler::lessEqual, COMPARE_WIDE_VECTORS_LOOP_AVX3);

      // At this point we may still need to compare -limit+result bytes.
      // We could execute the next two instruction and just continue via non-wide path:
      //  cmpl(limit, 0);
      //  jcc(Assembler::equal, COMPARE_TAIL);  // true
      // But since we stopped at the points ary{1,2}+limit which are
      // not farther than 64 bytes from the ends of arrays ary{1,2}+result
      // (|limit| <= 32 and result < 32),
      // we may just compare the last 64 bytes.
      //
      addptr(result, -64);   // it is safe, bc we just came from this area
      evmovdquq(vec1, Address(ary1, result, Address::times_1), Assembler::AVX_512bit);
      evpcmpeqb(k7, vec1, Address(ary2, result, Address::times_1), Assembler::AVX_512bit);
      kortestql(k7, k7);
      jcc(Assembler::aboveEqual, FALSE_LABEL);     // miscompare

      jmp(TRUE_LABEL);

      bind(COMPARE_WIDE_VECTORS_LOOP_AVX2);

    }//if (VM_Version::supports_avx512vlbw())
#endif //_LP64
    bind(COMPARE_WIDE_VECTORS);
    vmovdqu(vec1, Address(ary1, limit, Address::times_1));
    vmovdqu(vec2, Address(ary2, limit, Address::times_1));
    vpxor(vec1, vec2);

    vptest(vec1, vec1);
    jcc(Assembler::notZero, FALSE_LABEL);
    addptr(limit, 32);
    jcc(Assembler::notZero, COMPARE_WIDE_VECTORS);

    testl(result, result);
    jcc(Assembler::zero, TRUE_LABEL);

    vmovdqu(vec1, Address(ary1, result, Address::times_1, -32));
    vmovdqu(vec2, Address(ary2, result, Address::times_1, -32));
    vpxor(vec1, vec2);

    vptest(vec1, vec1);
    jccb(Assembler::notZero, FALSE_LABEL);
    jmpb(TRUE_LABEL);

    bind(COMPARE_TAIL); // limit is zero
    movl(limit, result);
    // Fallthru to tail compare
  } else if (UseSSE42Intrinsics) {
    // With SSE4.2, use double quad vector compare
    Label COMPARE_WIDE_VECTORS, COMPARE_TAIL;

    // Compare 16-byte vectors
    andl(result, 0x0000000f);  //   tail count (in bytes)
    andl(limit, 0xfffffff0);   // vector count (in bytes)
    jcc(Assembler::zero, COMPARE_TAIL);

    lea(ary1, Address(ary1, limit, Address::times_1));
    lea(ary2, Address(ary2, limit, Address::times_1));
    negptr(limit);

    bind(COMPARE_WIDE_VECTORS);
    movdqu(vec1, Address(ary1, limit, Address::times_1));
    movdqu(vec2, Address(ary2, limit, Address::times_1));
    pxor(vec1, vec2);

    ptest(vec1, vec1);
    jcc(Assembler::notZero, FALSE_LABEL);
    addptr(limit, 16);
    jcc(Assembler::notZero, COMPARE_WIDE_VECTORS);

    testl(result, result);
    jcc(Assembler::zero, TRUE_LABEL);

    movdqu(vec1, Address(ary1, result, Address::times_1, -16));
    movdqu(vec2, Address(ary2, result, Address::times_1, -16));
    pxor(vec1, vec2);

    ptest(vec1, vec1);
    jccb(Assembler::notZero, FALSE_LABEL);
    jmpb(TRUE_LABEL);

    bind(COMPARE_TAIL); // limit is zero
    movl(limit, result);
    // Fallthru to tail compare
  }

  // Compare 4-byte vectors
  andl(limit, 0xfffffffc); // vector count (in bytes)
  jccb(Assembler::zero, COMPARE_CHAR);

  lea(ary1, Address(ary1, limit, Address::times_1));
  lea(ary2, Address(ary2, limit, Address::times_1));
  negptr(limit);

  bind(COMPARE_VECTORS);
  movl(chr, Address(ary1, limit, Address::times_1));
  cmpl(chr, Address(ary2, limit, Address::times_1));
  jccb(Assembler::notEqual, FALSE_LABEL);
  addptr(limit, 4);
  jcc(Assembler::notZero, COMPARE_VECTORS);

  // Compare trailing char (final 2 bytes), if any
  bind(COMPARE_CHAR);
  testl(result, 0x2);   // tail  char
  jccb(Assembler::zero, COMPARE_BYTE);
  load_unsigned_short(chr, Address(ary1, 0));
  load_unsigned_short(limit, Address(ary2, 0));
  cmpl(chr, limit);
  jccb(Assembler::notEqual, FALSE_LABEL);

  if (is_array_equ && is_char) {
    bind(COMPARE_BYTE);
  } else {
    lea(ary1, Address(ary1, 2));
    lea(ary2, Address(ary2, 2));

    bind(COMPARE_BYTE);
    testl(result, 0x1);   // tail  byte
    jccb(Assembler::zero, TRUE_LABEL);
    load_unsigned_byte(chr, Address(ary1, 0));
    load_unsigned_byte(limit, Address(ary2, 0));
    cmpl(chr, limit);
    jccb(Assembler::notEqual, FALSE_LABEL);
  }
  bind(TRUE_LABEL);
  movl(result, 1);   // return true
  jmpb(DONE);

  bind(FALSE_LABEL);
  xorl(result, result); // return false

  // That's it
  bind(DONE);
  if (UseAVX >= 2) {
    // clean upper bits of YMM registers
    vpxor(vec1, vec1);
    vpxor(vec2, vec2);
  }
}

#endif

void MacroAssembler::generate_fill(BasicType t, bool aligned,
                                   Register to, Register value, Register count,
                                   Register rtmp, XMMRegister xtmp) {
  ShortBranchVerifier sbv(this);
  assert_different_registers(to, value, count, rtmp);
  Label L_exit, L_skip_align1, L_skip_align2, L_fill_byte;
  Label L_fill_2_bytes, L_fill_4_bytes;

  int shift = -1;
  switch (t) {
    case T_BYTE:
      shift = 2;
      break;
    case T_SHORT:
      shift = 1;
      break;
    case T_INT:
      shift = 0;
      break;
    default: ShouldNotReachHere();
  }

  if (t == T_BYTE) {
    andl(value, 0xff);
    movl(rtmp, value);
    shll(rtmp, 8);
    orl(value, rtmp);
  }
  if (t == T_SHORT) {
    andl(value, 0xffff);
  }
  if (t == T_BYTE || t == T_SHORT) {
    movl(rtmp, value);
    shll(rtmp, 16);
    orl(value, rtmp);
  }

  cmpl(count, 2<<shift); // Short arrays (< 8 bytes) fill by element
  jcc(Assembler::below, L_fill_4_bytes); // use unsigned cmp
  if (!UseUnalignedLoadStores && !aligned && (t == T_BYTE || t == T_SHORT)) {
    // align source address at 4 bytes address boundary
    if (t == T_BYTE) {
      // One byte misalignment happens only for byte arrays
      testptr(to, 1);
      jccb(Assembler::zero, L_skip_align1);
      movb(Address(to, 0), value);
      increment(to);
      decrement(count);
      BIND(L_skip_align1);
    }
    // Two bytes misalignment happens only for byte and short (char) arrays
    testptr(to, 2);
    jccb(Assembler::zero, L_skip_align2);
    movw(Address(to, 0), value);
    addptr(to, 2);
    subl(count, 1<<(shift-1));
    BIND(L_skip_align2);
  }
  if (UseSSE < 2) {
    Label L_fill_32_bytes_loop, L_check_fill_8_bytes, L_fill_8_bytes_loop, L_fill_8_bytes;
    // Fill 32-byte chunks
    subl(count, 8 << shift);
    jcc(Assembler::less, L_check_fill_8_bytes);
    align(16);

    BIND(L_fill_32_bytes_loop);

    for (int i = 0; i < 32; i += 4) {
      movl(Address(to, i), value);
    }

    addptr(to, 32);
    subl(count, 8 << shift);
    jcc(Assembler::greaterEqual, L_fill_32_bytes_loop);
    BIND(L_check_fill_8_bytes);
    addl(count, 8 << shift);
    jccb(Assembler::zero, L_exit);
    jmpb(L_fill_8_bytes);

    //
    // length is too short, just fill qwords
    //
    BIND(L_fill_8_bytes_loop);
    movl(Address(to, 0), value);
    movl(Address(to, 4), value);
    addptr(to, 8);
    BIND(L_fill_8_bytes);
    subl(count, 1 << (shift + 1));
    jcc(Assembler::greaterEqual, L_fill_8_bytes_loop);
    // fall through to fill 4 bytes
  } else {
    Label L_fill_32_bytes;
    if (!UseUnalignedLoadStores) {
      // align to 8 bytes, we know we are 4 byte aligned to start
      testptr(to, 4);
      jccb(Assembler::zero, L_fill_32_bytes);
      movl(Address(to, 0), value);
      addptr(to, 4);
      subl(count, 1<<shift);
    }
    BIND(L_fill_32_bytes);
    {
      assert( UseSSE >= 2, "supported cpu only" );
      Label L_fill_32_bytes_loop, L_check_fill_8_bytes, L_fill_8_bytes_loop, L_fill_8_bytes;
      movdl(xtmp, value);
      if (UseAVX >= 2 && UseUnalignedLoadStores) {
        Label L_check_fill_32_bytes;
        if (UseAVX > 2) {
          // Fill 64-byte chunks
          Label L_fill_64_bytes_loop_avx3, L_check_fill_64_bytes_avx2;

          // If number of bytes to fill < AVX3Threshold, perform fill using AVX2
          cmpl(count, AVX3Threshold);
          jccb(Assembler::below, L_check_fill_64_bytes_avx2);

          vpbroadcastd(xtmp, xtmp, Assembler::AVX_512bit);

          subl(count, 16 << shift);
          jccb(Assembler::less, L_check_fill_32_bytes);
          align(16);

          BIND(L_fill_64_bytes_loop_avx3);
          evmovdqul(Address(to, 0), xtmp, Assembler::AVX_512bit);
          addptr(to, 64);
          subl(count, 16 << shift);
          jcc(Assembler::greaterEqual, L_fill_64_bytes_loop_avx3);
          jmpb(L_check_fill_32_bytes);

          BIND(L_check_fill_64_bytes_avx2);
        }
        // Fill 64-byte chunks
        Label L_fill_64_bytes_loop;
        vpbroadcastd(xtmp, xtmp, Assembler::AVX_256bit);

        subl(count, 16 << shift);
        jcc(Assembler::less, L_check_fill_32_bytes);
        align(16);

        BIND(L_fill_64_bytes_loop);
        vmovdqu(Address(to, 0), xtmp);
        vmovdqu(Address(to, 32), xtmp);
        addptr(to, 64);
        subl(count, 16 << shift);
        jcc(Assembler::greaterEqual, L_fill_64_bytes_loop);

        BIND(L_check_fill_32_bytes);
        addl(count, 8 << shift);
        jccb(Assembler::less, L_check_fill_8_bytes);
        vmovdqu(Address(to, 0), xtmp);
        addptr(to, 32);
        subl(count, 8 << shift);

        BIND(L_check_fill_8_bytes);
        // clean upper bits of YMM registers
        movdl(xtmp, value);
        pshufd(xtmp, xtmp, 0);
      } else {
        // Fill 32-byte chunks
        pshufd(xtmp, xtmp, 0);

        subl(count, 8 << shift);
        jcc(Assembler::less, L_check_fill_8_bytes);
        align(16);

        BIND(L_fill_32_bytes_loop);

        if (UseUnalignedLoadStores) {
          movdqu(Address(to, 0), xtmp);
          movdqu(Address(to, 16), xtmp);
        } else {
          movq(Address(to, 0), xtmp);
          movq(Address(to, 8), xtmp);
          movq(Address(to, 16), xtmp);
          movq(Address(to, 24), xtmp);
        }

        addptr(to, 32);
        subl(count, 8 << shift);
        jcc(Assembler::greaterEqual, L_fill_32_bytes_loop);

        BIND(L_check_fill_8_bytes);
      }
      addl(count, 8 << shift);
      jccb(Assembler::zero, L_exit);
      jmpb(L_fill_8_bytes);

      //
      // length is too short, just fill qwords
      //
      BIND(L_fill_8_bytes_loop);
      movq(Address(to, 0), xtmp);
      addptr(to, 8);
      BIND(L_fill_8_bytes);
      subl(count, 1 << (shift + 1));
      jcc(Assembler::greaterEqual, L_fill_8_bytes_loop);
    }
  }
  // fill trailing 4 bytes
  BIND(L_fill_4_bytes);
  testl(count, 1<<shift);
  jccb(Assembler::zero, L_fill_2_bytes);
  movl(Address(to, 0), value);
  if (t == T_BYTE || t == T_SHORT) {
    addptr(to, 4);
    BIND(L_fill_2_bytes);
    // fill trailing 2 bytes
    testl(count, 1<<(shift-1));
    jccb(Assembler::zero, L_fill_byte);
    movw(Address(to, 0), value);
    if (t == T_BYTE) {
      addptr(to, 2);
      BIND(L_fill_byte);
      // fill trailing byte
      testl(count, 1);
      jccb(Assembler::zero, L_exit);
      movb(Address(to, 0), value);
    } else {
      BIND(L_fill_byte);
    }
  } else {
    BIND(L_fill_2_bytes);
  }
  BIND(L_exit);
}

// encode char[] to byte[] in ISO_8859_1
   //@HotSpotIntrinsicCandidate
   //private static int implEncodeISOArray(byte[] sa, int sp,
   //byte[] da, int dp, int len) {
   //  int i = 0;
   //  for (; i < len; i++) {
   //    char c = StringUTF16.getChar(sa, sp++);
   //    if (c > '\u00FF')
   //      break;
   //    da[dp++] = (byte)c;
   //  }
   //  return i;
   //}
void MacroAssembler::encode_iso_array(Register src, Register dst, Register len,
  XMMRegister tmp1Reg, XMMRegister tmp2Reg,
  XMMRegister tmp3Reg, XMMRegister tmp4Reg,
  Register tmp5, Register result) {

  // rsi: src
  // rdi: dst
  // rdx: len
  // rcx: tmp5
  // rax: result
  ShortBranchVerifier sbv(this);
  assert_different_registers(src, dst, len, tmp5, result);
  Label L_done, L_copy_1_char, L_copy_1_char_exit;

  // set result
  xorl(result, result);
  // check for zero length
  testl(len, len);
  jcc(Assembler::zero, L_done);

  movl(result, len);

  // Setup pointers
  lea(src, Address(src, len, Address::times_2)); // char[]
  lea(dst, Address(dst, len, Address::times_1)); // byte[]
  negptr(len);

  if (UseSSE42Intrinsics || UseAVX >= 2) {
    Label L_chars_8_check, L_copy_8_chars, L_copy_8_chars_exit;
    Label L_chars_16_check, L_copy_16_chars, L_copy_16_chars_exit;

    if (UseAVX >= 2) {
      Label L_chars_32_check, L_copy_32_chars, L_copy_32_chars_exit;
      movl(tmp5, 0xff00ff00);   // create mask to test for Unicode chars in vector
      movdl(tmp1Reg, tmp5);
      vpbroadcastd(tmp1Reg, tmp1Reg, Assembler::AVX_256bit);
      jmp(L_chars_32_check);

      bind(L_copy_32_chars);
      vmovdqu(tmp3Reg, Address(src, len, Address::times_2, -64));
      vmovdqu(tmp4Reg, Address(src, len, Address::times_2, -32));
      vpor(tmp2Reg, tmp3Reg, tmp4Reg, /* vector_len */ 1);
      vptest(tmp2Reg, tmp1Reg);       // check for Unicode chars in  vector
      jccb(Assembler::notZero, L_copy_32_chars_exit);
      vpackuswb(tmp3Reg, tmp3Reg, tmp4Reg, /* vector_len */ 1);
      vpermq(tmp4Reg, tmp3Reg, 0xD8, /* vector_len */ 1);
      vmovdqu(Address(dst, len, Address::times_1, -32), tmp4Reg);

      bind(L_chars_32_check);
      addptr(len, 32);
      jcc(Assembler::lessEqual, L_copy_32_chars);

      bind(L_copy_32_chars_exit);
      subptr(len, 16);
      jccb(Assembler::greater, L_copy_16_chars_exit);

    } else if (UseSSE42Intrinsics) {
      movl(tmp5, 0xff00ff00);   // create mask to test for Unicode chars in vector
      movdl(tmp1Reg, tmp5);
      pshufd(tmp1Reg, tmp1Reg, 0);
      jmpb(L_chars_16_check);
    }

    bind(L_copy_16_chars);
    if (UseAVX >= 2) {
      vmovdqu(tmp2Reg, Address(src, len, Address::times_2, -32));
      vptest(tmp2Reg, tmp1Reg);
      jcc(Assembler::notZero, L_copy_16_chars_exit);
      vpackuswb(tmp2Reg, tmp2Reg, tmp1Reg, /* vector_len */ 1);
      vpermq(tmp3Reg, tmp2Reg, 0xD8, /* vector_len */ 1);
    } else {
      if (UseAVX > 0) {
        movdqu(tmp3Reg, Address(src, len, Address::times_2, -32));
        movdqu(tmp4Reg, Address(src, len, Address::times_2, -16));
        vpor(tmp2Reg, tmp3Reg, tmp4Reg, /* vector_len */ 0);
      } else {
        movdqu(tmp3Reg, Address(src, len, Address::times_2, -32));
        por(tmp2Reg, tmp3Reg);
        movdqu(tmp4Reg, Address(src, len, Address::times_2, -16));
        por(tmp2Reg, tmp4Reg);
      }
      ptest(tmp2Reg, tmp1Reg);       // check for Unicode chars in  vector
      jccb(Assembler::notZero, L_copy_16_chars_exit);
      packuswb(tmp3Reg, tmp4Reg);
    }
    movdqu(Address(dst, len, Address::times_1, -16), tmp3Reg);

    bind(L_chars_16_check);
    addptr(len, 16);
    jcc(Assembler::lessEqual, L_copy_16_chars);

    bind(L_copy_16_chars_exit);
    if (UseAVX >= 2) {
      // clean upper bits of YMM registers
      vpxor(tmp2Reg, tmp2Reg);
      vpxor(tmp3Reg, tmp3Reg);
      vpxor(tmp4Reg, tmp4Reg);
      movdl(tmp1Reg, tmp5);
      pshufd(tmp1Reg, tmp1Reg, 0);
    }
    subptr(len, 8);
    jccb(Assembler::greater, L_copy_8_chars_exit);

    bind(L_copy_8_chars);
    movdqu(tmp3Reg, Address(src, len, Address::times_2, -16));
    ptest(tmp3Reg, tmp1Reg);
    jccb(Assembler::notZero, L_copy_8_chars_exit);
    packuswb(tmp3Reg, tmp1Reg);
    movq(Address(dst, len, Address::times_1, -8), tmp3Reg);
    addptr(len, 8);
    jccb(Assembler::lessEqual, L_copy_8_chars);

    bind(L_copy_8_chars_exit);
    subptr(len, 8);
    jccb(Assembler::zero, L_done);
  }

  bind(L_copy_1_char);
  load_unsigned_short(tmp5, Address(src, len, Address::times_2, 0));
  testl(tmp5, 0xff00);      // check if Unicode char
  jccb(Assembler::notZero, L_copy_1_char_exit);
  movb(Address(dst, len, Address::times_1, 0), tmp5);
  addptr(len, 1);
  jccb(Assembler::less, L_copy_1_char);

  bind(L_copy_1_char_exit);
  addptr(result, len); // len is negative count of not processed elements

  bind(L_done);
}

#ifdef _LP64
/**
 * Helper for multiply_to_len().
 */
void MacroAssembler::add2_with_carry(Register dest_hi, Register dest_lo, Register src1, Register src2) {
  addq(dest_lo, src1);
  adcq(dest_hi, 0);
  addq(dest_lo, src2);
  adcq(dest_hi, 0);
}

/**
 * Multiply 64 bit by 64 bit first loop.
 */
void MacroAssembler::multiply_64_x_64_loop(Register x, Register xstart, Register x_xstart,
                                           Register y, Register y_idx, Register z,
                                           Register carry, Register product,
                                           Register idx, Register kdx) {
  //
  //  jlong carry, x[], y[], z[];
  //  for (int idx=ystart, kdx=ystart+1+xstart; idx >= 0; idx-, kdx--) {
  //    huge_128 product = y[idx] * x[xstart] + carry;
  //    z[kdx] = (jlong)product;
  //    carry  = (jlong)(product >>> 64);
  //  }
  //  z[xstart] = carry;
  //

  Label L_first_loop, L_first_loop_exit;
  Label L_one_x, L_one_y, L_multiply;

  decrementl(xstart);
  jcc(Assembler::negative, L_one_x);

  movq(x_xstart, Address(x, xstart, Address::times_4,  0));
  rorq(x_xstart, 32); // convert big-endian to little-endian

  bind(L_first_loop);
  decrementl(idx);
  jcc(Assembler::negative, L_first_loop_exit);
  decrementl(idx);
  jcc(Assembler::negative, L_one_y);
  movq(y_idx, Address(y, idx, Address::times_4,  0));
  rorq(y_idx, 32); // convert big-endian to little-endian
  bind(L_multiply);
  movq(product, x_xstart);
  mulq(y_idx); // product(rax) * y_idx -> rdx:rax
  addq(product, carry);
  adcq(rdx, 0);
  subl(kdx, 2);
  movl(Address(z, kdx, Address::times_4,  4), product);
  shrq(product, 32);
  movl(Address(z, kdx, Address::times_4,  0), product);
  movq(carry, rdx);
  jmp(L_first_loop);

  bind(L_one_y);
  movl(y_idx, Address(y,  0));
  jmp(L_multiply);

  bind(L_one_x);
  movl(x_xstart, Address(x,  0));
  jmp(L_first_loop);

  bind(L_first_loop_exit);
}

/**
 * Multiply 64 bit by 64 bit and add 128 bit.
 */
void MacroAssembler::multiply_add_128_x_128(Register x_xstart, Register y, Register z,
                                            Register yz_idx, Register idx,
                                            Register carry, Register product, int offset) {
  //     huge_128 product = (y[idx] * x_xstart) + z[kdx] + carry;
  //     z[kdx] = (jlong)product;

  movq(yz_idx, Address(y, idx, Address::times_4,  offset));
  rorq(yz_idx, 32); // convert big-endian to little-endian
  movq(product, x_xstart);
  mulq(yz_idx);     // product(rax) * yz_idx -> rdx:product(rax)
  movq(yz_idx, Address(z, idx, Address::times_4,  offset));
  rorq(yz_idx, 32); // convert big-endian to little-endian

  add2_with_carry(rdx, product, carry, yz_idx);

  movl(Address(z, idx, Address::times_4,  offset+4), product);
  shrq(product, 32);
  movl(Address(z, idx, Address::times_4,  offset), product);

}

/**
 * Multiply 128 bit by 128 bit. Unrolled inner loop.
 */
void MacroAssembler::multiply_128_x_128_loop(Register x_xstart, Register y, Register z,
                                             Register yz_idx, Register idx, Register jdx,
                                             Register carry, Register product,
                                             Register carry2) {
  //   jlong carry, x[], y[], z[];
  //   int kdx = ystart+1;
  //   for (int idx=ystart-2; idx >= 0; idx -= 2) { // Third loop
  //     huge_128 product = (y[idx+1] * x_xstart) + z[kdx+idx+1] + carry;
  //     z[kdx+idx+1] = (jlong)product;
  //     jlong carry2  = (jlong)(product >>> 64);
  //     product = (y[idx] * x_xstart) + z[kdx+idx] + carry2;
  //     z[kdx+idx] = (jlong)product;
  //     carry  = (jlong)(product >>> 64);
  //   }
  //   idx += 2;
  //   if (idx > 0) {
  //     product = (y[idx] * x_xstart) + z[kdx+idx] + carry;
  //     z[kdx+idx] = (jlong)product;
  //     carry  = (jlong)(product >>> 64);
  //   }
  //

  Label L_third_loop, L_third_loop_exit, L_post_third_loop_done;

  movl(jdx, idx);
  andl(jdx, 0xFFFFFFFC);
  shrl(jdx, 2);

  bind(L_third_loop);
  subl(jdx, 1);
  jcc(Assembler::negative, L_third_loop_exit);
  subl(idx, 4);

  multiply_add_128_x_128(x_xstart, y, z, yz_idx, idx, carry, product, 8);
  movq(carry2, rdx);

  multiply_add_128_x_128(x_xstart, y, z, yz_idx, idx, carry2, product, 0);
  movq(carry, rdx);
  jmp(L_third_loop);

  bind (L_third_loop_exit);

  andl (idx, 0x3);
  jcc(Assembler::zero, L_post_third_loop_done);

  Label L_check_1;
  subl(idx, 2);
  jcc(Assembler::negative, L_check_1);

  multiply_add_128_x_128(x_xstart, y, z, yz_idx, idx, carry, product, 0);
  movq(carry, rdx);

  bind (L_check_1);
  addl (idx, 0x2);
  andl (idx, 0x1);
  subl(idx, 1);
  jcc(Assembler::negative, L_post_third_loop_done);

  movl(yz_idx, Address(y, idx, Address::times_4,  0));
  movq(product, x_xstart);
  mulq(yz_idx); // product(rax) * yz_idx -> rdx:product(rax)
  movl(yz_idx, Address(z, idx, Address::times_4,  0));

  add2_with_carry(rdx, product, yz_idx, carry);

  movl(Address(z, idx, Address::times_4,  0), product);
  shrq(product, 32);

  shlq(rdx, 32);
  orq(product, rdx);
  movq(carry, product);

  bind(L_post_third_loop_done);
}

/**
 * Multiply 128 bit by 128 bit using BMI2. Unrolled inner loop.
 *
 */
void MacroAssembler::multiply_128_x_128_bmi2_loop(Register y, Register z,
                                                  Register carry, Register carry2,
                                                  Register idx, Register jdx,
                                                  Register yz_idx1, Register yz_idx2,
                                                  Register tmp, Register tmp3, Register tmp4) {
  assert(UseBMI2Instructions, "should be used only when BMI2 is available");

  //   jlong carry, x[], y[], z[];
  //   int kdx = ystart+1;
  //   for (int idx=ystart-2; idx >= 0; idx -= 2) { // Third loop
  //     huge_128 tmp3 = (y[idx+1] * rdx) + z[kdx+idx+1] + carry;
  //     jlong carry2  = (jlong)(tmp3 >>> 64);
  //     huge_128 tmp4 = (y[idx]   * rdx) + z[kdx+idx] + carry2;
  //     carry  = (jlong)(tmp4 >>> 64);
  //     z[kdx+idx+1] = (jlong)tmp3;
  //     z[kdx+idx] = (jlong)tmp4;
  //   }
  //   idx += 2;
  //   if (idx > 0) {
  //     yz_idx1 = (y[idx] * rdx) + z[kdx+idx] + carry;
  //     z[kdx+idx] = (jlong)yz_idx1;
  //     carry  = (jlong)(yz_idx1 >>> 64);
  //   }
  //

  Label L_third_loop, L_third_loop_exit, L_post_third_loop_done;

  movl(jdx, idx);
  andl(jdx, 0xFFFFFFFC);
  shrl(jdx, 2);

  bind(L_third_loop);
  subl(jdx, 1);
  jcc(Assembler::negative, L_third_loop_exit);
  subl(idx, 4);

  movq(yz_idx1,  Address(y, idx, Address::times_4,  8));
  rorxq(yz_idx1, yz_idx1, 32); // convert big-endian to little-endian
  movq(yz_idx2, Address(y, idx, Address::times_4,  0));
  rorxq(yz_idx2, yz_idx2, 32);

  mulxq(tmp4, tmp3, yz_idx1);  //  yz_idx1 * rdx -> tmp4:tmp3
  mulxq(carry2, tmp, yz_idx2); //  yz_idx2 * rdx -> carry2:tmp

  movq(yz_idx1,  Address(z, idx, Address::times_4,  8));
  rorxq(yz_idx1, yz_idx1, 32);
  movq(yz_idx2, Address(z, idx, Address::times_4,  0));
  rorxq(yz_idx2, yz_idx2, 32);

  if (VM_Version::supports_adx()) {
    adcxq(tmp3, carry);
    adoxq(tmp3, yz_idx1);

    adcxq(tmp4, tmp);
    adoxq(tmp4, yz_idx2);

    movl(carry, 0); // does not affect flags
    adcxq(carry2, carry);
    adoxq(carry2, carry);
  } else {
    add2_with_carry(tmp4, tmp3, carry, yz_idx1);
    add2_with_carry(carry2, tmp4, tmp, yz_idx2);
  }
  movq(carry, carry2);

  movl(Address(z, idx, Address::times_4, 12), tmp3);
  shrq(tmp3, 32);
  movl(Address(z, idx, Address::times_4,  8), tmp3);

  movl(Address(z, idx, Address::times_4,  4), tmp4);
  shrq(tmp4, 32);
  movl(Address(z, idx, Address::times_4,  0), tmp4);

  jmp(L_third_loop);

  bind (L_third_loop_exit);

  andl (idx, 0x3);
  jcc(Assembler::zero, L_post_third_loop_done);

  Label L_check_1;
  subl(idx, 2);
  jcc(Assembler::negative, L_check_1);

  movq(yz_idx1, Address(y, idx, Address::times_4,  0));
  rorxq(yz_idx1, yz_idx1, 32);
  mulxq(tmp4, tmp3, yz_idx1); //  yz_idx1 * rdx -> tmp4:tmp3
  movq(yz_idx2, Address(z, idx, Address::times_4,  0));
  rorxq(yz_idx2, yz_idx2, 32);

  add2_with_carry(tmp4, tmp3, carry, yz_idx2);

  movl(Address(z, idx, Address::times_4,  4), tmp3);
  shrq(tmp3, 32);
  movl(Address(z, idx, Address::times_4,  0), tmp3);
  movq(carry, tmp4);

  bind (L_check_1);
  addl (idx, 0x2);
  andl (idx, 0x1);
  subl(idx, 1);
  jcc(Assembler::negative, L_post_third_loop_done);
  movl(tmp4, Address(y, idx, Address::times_4,  0));
  mulxq(carry2, tmp3, tmp4);  //  tmp4 * rdx -> carry2:tmp3
  movl(tmp4, Address(z, idx, Address::times_4,  0));

  add2_with_carry(carry2, tmp3, tmp4, carry);

  movl(Address(z, idx, Address::times_4,  0), tmp3);
  shrq(tmp3, 32);

  shlq(carry2, 32);
  orq(tmp3, carry2);
  movq(carry, tmp3);

  bind(L_post_third_loop_done);
}

/**
 * Code for BigInteger::multiplyToLen() instrinsic.
 *
 * rdi: x
 * rax: xlen
 * rsi: y
 * rcx: ylen
 * r8:  z
 * r11: zlen
 * r12: tmp1
 * r13: tmp2
 * r14: tmp3
 * r15: tmp4
 * rbx: tmp5
 *
 */
void MacroAssembler::multiply_to_len(Register x, Register xlen, Register y, Register ylen, Register z, Register zlen,
                                     Register tmp1, Register tmp2, Register tmp3, Register tmp4, Register tmp5) {
  ShortBranchVerifier sbv(this);
  assert_different_registers(x, xlen, y, ylen, z, zlen, tmp1, tmp2, tmp3, tmp4, tmp5, rdx);

  push(tmp1);
  push(tmp2);
  push(tmp3);
  push(tmp4);
  push(tmp5);

  push(xlen);
  push(zlen);

  const Register idx = tmp1;
  const Register kdx = tmp2;
  const Register xstart = tmp3;

  const Register y_idx = tmp4;
  const Register carry = tmp5;
  const Register product  = xlen;
  const Register x_xstart = zlen;  // reuse register

  // First Loop.
  //
  //  final static long LONG_MASK = 0xffffffffL;
  //  int xstart = xlen - 1;
  //  int ystart = ylen - 1;
  //  long carry = 0;
  //  for (int idx=ystart, kdx=ystart+1+xstart; idx >= 0; idx-, kdx--) {
  //    long product = (y[idx] & LONG_MASK) * (x[xstart] & LONG_MASK) + carry;
  //    z[kdx] = (int)product;
  //    carry = product >>> 32;
  //  }
  //  z[xstart] = (int)carry;
  //

  movl(idx, ylen);      // idx = ylen;
  movl(kdx, zlen);      // kdx = xlen+ylen;
  xorq(carry, carry);   // carry = 0;

  Label L_done;

  movl(xstart, xlen);
  decrementl(xstart);
  jcc(Assembler::negative, L_done);

  multiply_64_x_64_loop(x, xstart, x_xstart, y, y_idx, z, carry, product, idx, kdx);

  Label L_second_loop;
  testl(kdx, kdx);
  jcc(Assembler::zero, L_second_loop);

  Label L_carry;
  subl(kdx, 1);
  jcc(Assembler::zero, L_carry);

  movl(Address(z, kdx, Address::times_4,  0), carry);
  shrq(carry, 32);
  subl(kdx, 1);

  bind(L_carry);
  movl(Address(z, kdx, Address::times_4,  0), carry);

  // Second and third (nested) loops.
  //
  // for (int i = xstart-1; i >= 0; i--) { // Second loop
  //   carry = 0;
  //   for (int jdx=ystart, k=ystart+1+i; jdx >= 0; jdx--, k--) { // Third loop
  //     long product = (y[jdx] & LONG_MASK) * (x[i] & LONG_MASK) +
  //                    (z[k] & LONG_MASK) + carry;
  //     z[k] = (int)product;
  //     carry = product >>> 32;
  //   }
  //   z[i] = (int)carry;
  // }
  //
  // i = xlen, j = tmp1, k = tmp2, carry = tmp5, x[i] = rdx

  const Register jdx = tmp1;

  bind(L_second_loop);
  xorl(carry, carry);    // carry = 0;
  movl(jdx, ylen);       // j = ystart+1

  subl(xstart, 1);       // i = xstart-1;
  jcc(Assembler::negative, L_done);

  push (z);

  Label L_last_x;
  lea(z, Address(z, xstart, Address::times_4, 4)); // z = z + k - j
  subl(xstart, 1);       // i = xstart-1;
  jcc(Assembler::negative, L_last_x);

  if (UseBMI2Instructions) {
    movq(rdx,  Address(x, xstart, Address::times_4,  0));
    rorxq(rdx, rdx, 32); // convert big-endian to little-endian
  } else {
    movq(x_xstart, Address(x, xstart, Address::times_4,  0));
    rorq(x_xstart, 32);  // convert big-endian to little-endian
  }

  Label L_third_loop_prologue;
  bind(L_third_loop_prologue);

  push (x);
  push (xstart);
  push (ylen);


  if (UseBMI2Instructions) {
    multiply_128_x_128_bmi2_loop(y, z, carry, x, jdx, ylen, product, tmp2, x_xstart, tmp3, tmp4);
  } else { // !UseBMI2Instructions
    multiply_128_x_128_loop(x_xstart, y, z, y_idx, jdx, ylen, carry, product, x);
  }

  pop(ylen);
  pop(xlen);
  pop(x);
  pop(z);

  movl(tmp3, xlen);
  addl(tmp3, 1);
  movl(Address(z, tmp3, Address::times_4,  0), carry);
  subl(tmp3, 1);
  jccb(Assembler::negative, L_done);

  shrq(carry, 32);
  movl(Address(z, tmp3, Address::times_4,  0), carry);
  jmp(L_second_loop);

  // Next infrequent code is moved outside loops.
  bind(L_last_x);
  if (UseBMI2Instructions) {
    movl(rdx, Address(x,  0));
  } else {
    movl(x_xstart, Address(x,  0));
  }
  jmp(L_third_loop_prologue);

  bind(L_done);

  pop(zlen);
  pop(xlen);

  pop(tmp5);
  pop(tmp4);
  pop(tmp3);
  pop(tmp2);
  pop(tmp1);
}

void MacroAssembler::vectorized_mismatch(Register obja, Register objb, Register length, Register log2_array_indxscale,
  Register result, Register tmp1, Register tmp2, XMMRegister rymm0, XMMRegister rymm1, XMMRegister rymm2){
  assert(UseSSE42Intrinsics, "SSE4.2 must be enabled.");
  Label VECTOR64_LOOP, VECTOR64_TAIL, VECTOR64_NOT_EQUAL, VECTOR32_TAIL;
  Label VECTOR32_LOOP, VECTOR16_LOOP, VECTOR8_LOOP, VECTOR4_LOOP;
  Label VECTOR16_TAIL, VECTOR8_TAIL, VECTOR4_TAIL;
  Label VECTOR32_NOT_EQUAL, VECTOR16_NOT_EQUAL, VECTOR8_NOT_EQUAL, VECTOR4_NOT_EQUAL;
  Label SAME_TILL_END, DONE;
  Label BYTES_LOOP, BYTES_TAIL, BYTES_NOT_EQUAL;

  //scale is in rcx in both Win64 and Unix
  ShortBranchVerifier sbv(this);

  shlq(length);
  xorq(result, result);

  if ((AVX3Threshold == 0) && (UseAVX > 2) &&
      VM_Version::supports_avx512vlbw()) {
    cmpq(length, 64);
    jcc(Assembler::less, VECTOR32_TAIL);

    movq(tmp1, length);
    andq(tmp1, 0x3F);      // tail count
    andq(length, ~(0x3F)); //vector count

    bind(VECTOR64_LOOP);
    // AVX512 code to compare 64 byte vectors.
    evmovdqub(rymm0, Address(obja, result), Assembler::AVX_512bit);
    evpcmpeqb(k7, rymm0, Address(objb, result), Assembler::AVX_512bit);
    kortestql(k7, k7);
    jcc(Assembler::aboveEqual, VECTOR64_NOT_EQUAL);     // mismatch
    addq(result, 64);
    subq(length, 64);
    jccb(Assembler::notZero, VECTOR64_LOOP);

    //bind(VECTOR64_TAIL);
    testq(tmp1, tmp1);
    jcc(Assembler::zero, SAME_TILL_END);

    bind(VECTOR64_TAIL);
    // AVX512 code to compare upto 63 byte vectors.
    mov64(tmp2, 0xFFFFFFFFFFFFFFFF);
    shlxq(tmp2, tmp2, tmp1);
    notq(tmp2);
    kmovql(k3, tmp2);

    evmovdqub(rymm0, k3, Address(obja, result), Assembler::AVX_512bit);
    evpcmpeqb(k7, k3, rymm0, Address(objb, result), Assembler::AVX_512bit);

    ktestql(k7, k3);
    jcc(Assembler::below, SAME_TILL_END);     // not mismatch

    bind(VECTOR64_NOT_EQUAL);
    kmovql(tmp1, k7);
    notq(tmp1);
    tzcntq(tmp1, tmp1);
    addq(result, tmp1);
    shrq(result);
    jmp(DONE);
    bind(VECTOR32_TAIL);
  }

  cmpq(length, 8);
  jcc(Assembler::equal, VECTOR8_LOOP);
  jcc(Assembler::less, VECTOR4_TAIL);

  if (UseAVX >= 2) {

    cmpq(length, 16);
    jcc(Assembler::equal, VECTOR16_LOOP);
    jcc(Assembler::less, VECTOR8_LOOP);

    cmpq(length, 32);
    jccb(Assembler::less, VECTOR16_TAIL);

    subq(length, 32);
    bind(VECTOR32_LOOP);
    vmovdqu(rymm0, Address(obja, result));
    vmovdqu(rymm1, Address(objb, result));
    vpxor(rymm2, rymm0, rymm1, Assembler::AVX_256bit);
    vptest(rymm2, rymm2);
    jcc(Assembler::notZero, VECTOR32_NOT_EQUAL);//mismatch found
    addq(result, 32);
    subq(length, 32);
    jccb(Assembler::greaterEqual, VECTOR32_LOOP);
    addq(length, 32);
    jcc(Assembler::equal, SAME_TILL_END);
    //falling through if less than 32 bytes left //close the branch here.

    bind(VECTOR16_TAIL);
    cmpq(length, 16);
    jccb(Assembler::less, VECTOR8_TAIL);
    bind(VECTOR16_LOOP);
    movdqu(rymm0, Address(obja, result));
    movdqu(rymm1, Address(objb, result));
    vpxor(rymm2, rymm0, rymm1, Assembler::AVX_128bit);
    ptest(rymm2, rymm2);
    jcc(Assembler::notZero, VECTOR16_NOT_EQUAL);//mismatch found
    addq(result, 16);
    subq(length, 16);
    jcc(Assembler::equal, SAME_TILL_END);
    //falling through if less than 16 bytes left
  } else {//regular intrinsics

    cmpq(length, 16);
    jccb(Assembler::less, VECTOR8_TAIL);

    subq(length, 16);
    bind(VECTOR16_LOOP);
    movdqu(rymm0, Address(obja, result));
    movdqu(rymm1, Address(objb, result));
    pxor(rymm0, rymm1);
    ptest(rymm0, rymm0);
    jcc(Assembler::notZero, VECTOR16_NOT_EQUAL);//mismatch found
    addq(result, 16);
    subq(length, 16);
    jccb(Assembler::greaterEqual, VECTOR16_LOOP);
    addq(length, 16);
    jcc(Assembler::equal, SAME_TILL_END);
    //falling through if less than 16 bytes left
  }

  bind(VECTOR8_TAIL);
  cmpq(length, 8);
  jccb(Assembler::less, VECTOR4_TAIL);
  bind(VECTOR8_LOOP);
  movq(tmp1, Address(obja, result));
  movq(tmp2, Address(objb, result));
  xorq(tmp1, tmp2);
  testq(tmp1, tmp1);
  jcc(Assembler::notZero, VECTOR8_NOT_EQUAL);//mismatch found
  addq(result, 8);
  subq(length, 8);
  jcc(Assembler::equal, SAME_TILL_END);
  //falling through if less than 8 bytes left

  bind(VECTOR4_TAIL);
  cmpq(length, 4);
  jccb(Assembler::less, BYTES_TAIL);
  bind(VECTOR4_LOOP);
  movl(tmp1, Address(obja, result));
  xorl(tmp1, Address(objb, result));
  testl(tmp1, tmp1);
  jcc(Assembler::notZero, VECTOR4_NOT_EQUAL);//mismatch found
  addq(result, 4);
  subq(length, 4);
  jcc(Assembler::equal, SAME_TILL_END);
  //falling through if less than 4 bytes left

  bind(BYTES_TAIL);
  bind(BYTES_LOOP);
  load_unsigned_byte(tmp1, Address(obja, result));
  load_unsigned_byte(tmp2, Address(objb, result));
  xorl(tmp1, tmp2);
  testl(tmp1, tmp1);
  jccb(Assembler::notZero, BYTES_NOT_EQUAL);//mismatch found
  decq(length);
  jccb(Assembler::zero, SAME_TILL_END);
  incq(result);
  load_unsigned_byte(tmp1, Address(obja, result));
  load_unsigned_byte(tmp2, Address(objb, result));
  xorl(tmp1, tmp2);
  testl(tmp1, tmp1);
  jccb(Assembler::notZero, BYTES_NOT_EQUAL);//mismatch found
  decq(length);
  jccb(Assembler::zero, SAME_TILL_END);
  incq(result);
  load_unsigned_byte(tmp1, Address(obja, result));
  load_unsigned_byte(tmp2, Address(objb, result));
  xorl(tmp1, tmp2);
  testl(tmp1, tmp1);
  jccb(Assembler::notZero, BYTES_NOT_EQUAL);//mismatch found
  jmpb(SAME_TILL_END);

  if (UseAVX >= 2) {
    bind(VECTOR32_NOT_EQUAL);
    vpcmpeqb(rymm2, rymm2, rymm2, Assembler::AVX_256bit);
    vpcmpeqb(rymm0, rymm0, rymm1, Assembler::AVX_256bit);
    vpxor(rymm0, rymm0, rymm2, Assembler::AVX_256bit);
    vpmovmskb(tmp1, rymm0);
    bsfq(tmp1, tmp1);
    addq(result, tmp1);
    shrq(result);
    jmpb(DONE);
  }

  bind(VECTOR16_NOT_EQUAL);
  if (UseAVX >= 2) {
    vpcmpeqb(rymm2, rymm2, rymm2, Assembler::AVX_128bit);
    vpcmpeqb(rymm0, rymm0, rymm1, Assembler::AVX_128bit);
    pxor(rymm0, rymm2);
  } else {
    pcmpeqb(rymm2, rymm2);
    pxor(rymm0, rymm1);
    pcmpeqb(rymm0, rymm1);
    pxor(rymm0, rymm2);
  }
  pmovmskb(tmp1, rymm0);
  bsfq(tmp1, tmp1);
  addq(result, tmp1);
  shrq(result);
  jmpb(DONE);

  bind(VECTOR8_NOT_EQUAL);
  bind(VECTOR4_NOT_EQUAL);
  bsfq(tmp1, tmp1);
  shrq(tmp1, 3);
  addq(result, tmp1);
  bind(BYTES_NOT_EQUAL);
  shrq(result);
  jmpb(DONE);

  bind(SAME_TILL_END);
  mov64(result, -1);

  bind(DONE);
}

//Helper functions for square_to_len()

/**
 * Store the squares of x[], right shifted one bit (divided by 2) into z[]
 * Preserves x and z and modifies rest of the registers.
 */
void MacroAssembler::square_rshift(Register x, Register xlen, Register z, Register tmp1, Register tmp3, Register tmp4, Register tmp5, Register rdxReg, Register raxReg) {
  // Perform square and right shift by 1
  // Handle odd xlen case first, then for even xlen do the following
  // jlong carry = 0;
  // for (int j=0, i=0; j < xlen; j+=2, i+=4) {
  //     huge_128 product = x[j:j+1] * x[j:j+1];
  //     z[i:i+1] = (carry << 63) | (jlong)(product >>> 65);
  //     z[i+2:i+3] = (jlong)(product >>> 1);
  //     carry = (jlong)product;
  // }

  xorq(tmp5, tmp5);     // carry
  xorq(rdxReg, rdxReg);
  xorl(tmp1, tmp1);     // index for x
  xorl(tmp4, tmp4);     // index for z

  Label L_first_loop, L_first_loop_exit;

  testl(xlen, 1);
  jccb(Assembler::zero, L_first_loop); //jump if xlen is even

  // Square and right shift by 1 the odd element using 32 bit multiply
  movl(raxReg, Address(x, tmp1, Address::times_4, 0));
  imulq(raxReg, raxReg);
  shrq(raxReg, 1);
  adcq(tmp5, 0);
  movq(Address(z, tmp4, Address::times_4, 0), raxReg);
  incrementl(tmp1);
  addl(tmp4, 2);

  // Square and  right shift by 1 the rest using 64 bit multiply
  bind(L_first_loop);
  cmpptr(tmp1, xlen);
  jccb(Assembler::equal, L_first_loop_exit);

  // Square
  movq(raxReg, Address(x, tmp1, Address::times_4,  0));
  rorq(raxReg, 32);    // convert big-endian to little-endian
  mulq(raxReg);        // 64-bit multiply rax * rax -> rdx:rax

  // Right shift by 1 and save carry
  shrq(tmp5, 1);       // rdx:rax:tmp5 = (tmp5:rdx:rax) >>> 1
  rcrq(rdxReg, 1);
  rcrq(raxReg, 1);
  adcq(tmp5, 0);

  // Store result in z
  movq(Address(z, tmp4, Address::times_4, 0), rdxReg);
  movq(Address(z, tmp4, Address::times_4, 8), raxReg);

  // Update indices for x and z
  addl(tmp1, 2);
  addl(tmp4, 4);
  jmp(L_first_loop);

  bind(L_first_loop_exit);
}


/**
 * Perform the following multiply add operation using BMI2 instructions
 * carry:sum = sum + op1*op2 + carry
 * op2 should be in rdx
 * op2 is preserved, all other registers are modified
 */
void MacroAssembler::multiply_add_64_bmi2(Register sum, Register op1, Register op2, Register carry, Register tmp2) {
  // assert op2 is rdx
  mulxq(tmp2, op1, op1);  //  op1 * op2 -> tmp2:op1
  addq(sum, carry);
  adcq(tmp2, 0);
  addq(sum, op1);
  adcq(tmp2, 0);
  movq(carry, tmp2);
}

/**
 * Perform the following multiply add operation:
 * carry:sum = sum + op1*op2 + carry
 * Preserves op1, op2 and modifies rest of registers
 */
void MacroAssembler::multiply_add_64(Register sum, Register op1, Register op2, Register carry, Register rdxReg, Register raxReg) {
  // rdx:rax = op1 * op2
  movq(raxReg, op2);
  mulq(op1);

  //  rdx:rax = sum + carry + rdx:rax
  addq(sum, carry);
  adcq(rdxReg, 0);
  addq(sum, raxReg);
  adcq(rdxReg, 0);

  // carry:sum = rdx:sum
  movq(carry, rdxReg);
}

/**
 * Add 64 bit long carry into z[] with carry propogation.
 * Preserves z and carry register values and modifies rest of registers.
 *
 */
void MacroAssembler::add_one_64(Register z, Register zlen, Register carry, Register tmp1) {
  Label L_fourth_loop, L_fourth_loop_exit;

  movl(tmp1, 1);
  subl(zlen, 2);
  addq(Address(z, zlen, Address::times_4, 0), carry);

  bind(L_fourth_loop);
  jccb(Assembler::carryClear, L_fourth_loop_exit);
  subl(zlen, 2);
  jccb(Assembler::negative, L_fourth_loop_exit);
  addq(Address(z, zlen, Address::times_4, 0), tmp1);
  jmp(L_fourth_loop);
  bind(L_fourth_loop_exit);
}

/**
 * Shift z[] left by 1 bit.
 * Preserves x, len, z and zlen registers and modifies rest of the registers.
 *
 */
void MacroAssembler::lshift_by_1(Register x, Register len, Register z, Register zlen, Register tmp1, Register tmp2, Register tmp3, Register tmp4) {

  Label L_fifth_loop, L_fifth_loop_exit;

  // Fifth loop
  // Perform primitiveLeftShift(z, zlen, 1)

  const Register prev_carry = tmp1;
  const Register new_carry = tmp4;
  const Register value = tmp2;
  const Register zidx = tmp3;

  // int zidx, carry;
  // long value;
  // carry = 0;
  // for (zidx = zlen-2; zidx >=0; zidx -= 2) {
  //    (carry:value)  = (z[i] << 1) | carry ;
  //    z[i] = value;
  // }

  movl(zidx, zlen);
  xorl(prev_carry, prev_carry); // clear carry flag and prev_carry register

  bind(L_fifth_loop);
  decl(zidx);  // Use decl to preserve carry flag
  decl(zidx);
  jccb(Assembler::negative, L_fifth_loop_exit);

  if (UseBMI2Instructions) {
     movq(value, Address(z, zidx, Address::times_4, 0));
     rclq(value, 1);
     rorxq(value, value, 32);
     movq(Address(z, zidx, Address::times_4,  0), value);  // Store back in big endian form
  }
  else {
    // clear new_carry
    xorl(new_carry, new_carry);

    // Shift z[i] by 1, or in previous carry and save new carry
    movq(value, Address(z, zidx, Address::times_4, 0));
    shlq(value, 1);
    adcl(new_carry, 0);

    orq(value, prev_carry);
    rorq(value, 0x20);
    movq(Address(z, zidx, Address::times_4,  0), value);  // Store back in big endian form

    // Set previous carry = new carry
    movl(prev_carry, new_carry);
  }
  jmp(L_fifth_loop);

  bind(L_fifth_loop_exit);
}


/**
 * Code for BigInteger::squareToLen() intrinsic
 *
 * rdi: x
 * rsi: len
 * r8:  z
 * rcx: zlen
 * r12: tmp1
 * r13: tmp2
 * r14: tmp3
 * r15: tmp4
 * rbx: tmp5
 *
 */
void MacroAssembler::square_to_len(Register x, Register len, Register z, Register zlen, Register tmp1, Register tmp2, Register tmp3, Register tmp4, Register tmp5, Register rdxReg, Register raxReg) {

  Label L_second_loop, L_second_loop_exit, L_third_loop, L_third_loop_exit, fifth_loop, fifth_loop_exit, L_last_x, L_multiply;
  push(tmp1);
  push(tmp2);
  push(tmp3);
  push(tmp4);
  push(tmp5);

  // First loop
  // Store the squares, right shifted one bit (i.e., divided by 2).
  square_rshift(x, len, z, tmp1, tmp3, tmp4, tmp5, rdxReg, raxReg);

  // Add in off-diagonal sums.
  //
  // Second, third (nested) and fourth loops.
  // zlen +=2;
  // for (int xidx=len-2,zidx=zlen-4; xidx > 0; xidx-=2,zidx-=4) {
  //    carry = 0;
  //    long op2 = x[xidx:xidx+1];
  //    for (int j=xidx-2,k=zidx; j >= 0; j-=2) {
  //       k -= 2;
  //       long op1 = x[j:j+1];
  //       long sum = z[k:k+1];
  //       carry:sum = multiply_add_64(sum, op1, op2, carry, tmp_regs);
  //       z[k:k+1] = sum;
  //    }
  //    add_one_64(z, k, carry, tmp_regs);
  // }

  const Register carry = tmp5;
  const Register sum = tmp3;
  const Register op1 = tmp4;
  Register op2 = tmp2;

  push(zlen);
  push(len);
  addl(zlen,2);
  bind(L_second_loop);
  xorq(carry, carry);
  subl(zlen, 4);
  subl(len, 2);
  push(zlen);
  push(len);
  cmpl(len, 0);
  jccb(Assembler::lessEqual, L_second_loop_exit);

  // Multiply an array by one 64 bit long.
  if (UseBMI2Instructions) {
    op2 = rdxReg;
    movq(op2, Address(x, len, Address::times_4,  0));
    rorxq(op2, op2, 32);
  }
  else {
    movq(op2, Address(x, len, Address::times_4,  0));
    rorq(op2, 32);
  }

  bind(L_third_loop);
  decrementl(len);
  jccb(Assembler::negative, L_third_loop_exit);
  decrementl(len);
  jccb(Assembler::negative, L_last_x);

  movq(op1, Address(x, len, Address::times_4,  0));
  rorq(op1, 32);

  bind(L_multiply);
  subl(zlen, 2);
  movq(sum, Address(z, zlen, Address::times_4,  0));

  // Multiply 64 bit by 64 bit and add 64 bits lower half and upper 64 bits as carry.
  if (UseBMI2Instructions) {
    multiply_add_64_bmi2(sum, op1, op2, carry, tmp2);
  }
  else {
    multiply_add_64(sum, op1, op2, carry, rdxReg, raxReg);
  }

  movq(Address(z, zlen, Address::times_4, 0), sum);

  jmp(L_third_loop);
  bind(L_third_loop_exit);

  // Fourth loop
  // Add 64 bit long carry into z with carry propogation.
  // Uses offsetted zlen.
  add_one_64(z, zlen, carry, tmp1);

  pop(len);
  pop(zlen);
  jmp(L_second_loop);

  // Next infrequent code is moved outside loops.
  bind(L_last_x);
  movl(op1, Address(x, 0));
  jmp(L_multiply);

  bind(L_second_loop_exit);
  pop(len);
  pop(zlen);
  pop(len);
  pop(zlen);

  // Fifth loop
  // Shift z left 1 bit.
  lshift_by_1(x, len, z, zlen, tmp1, tmp2, tmp3, tmp4);

  // z[zlen-1] |= x[len-1] & 1;
  movl(tmp3, Address(x, len, Address::times_4, -4));
  andl(tmp3, 1);
  orl(Address(z, zlen, Address::times_4,  -4), tmp3);

  pop(tmp5);
  pop(tmp4);
  pop(tmp3);
  pop(tmp2);
  pop(tmp1);
}

/**
 * Helper function for mul_add()
 * Multiply the in[] by int k and add to out[] starting at offset offs using
 * 128 bit by 32 bit multiply and return the carry in tmp5.
 * Only quad int aligned length of in[] is operated on in this function.
 * k is in rdxReg for BMI2Instructions, for others it is in tmp2.
 * This function preserves out, in and k registers.
 * len and offset point to the appropriate index in "in" & "out" correspondingly
 * tmp5 has the carry.
 * other registers are temporary and are modified.
 *
 */
void MacroAssembler::mul_add_128_x_32_loop(Register out, Register in,
  Register offset, Register len, Register tmp1, Register tmp2, Register tmp3,
  Register tmp4, Register tmp5, Register rdxReg, Register raxReg) {

  Label L_first_loop, L_first_loop_exit;

  movl(tmp1, len);
  shrl(tmp1, 2);

  bind(L_first_loop);
  subl(tmp1, 1);
  jccb(Assembler::negative, L_first_loop_exit);

  subl(len, 4);
  subl(offset, 4);

  Register op2 = tmp2;
  const Register sum = tmp3;
  const Register op1 = tmp4;
  const Register carry = tmp5;

  if (UseBMI2Instructions) {
    op2 = rdxReg;
  }

  movq(op1, Address(in, len, Address::times_4,  8));
  rorq(op1, 32);
  movq(sum, Address(out, offset, Address::times_4,  8));
  rorq(sum, 32);
  if (UseBMI2Instructions) {
    multiply_add_64_bmi2(sum, op1, op2, carry, raxReg);
  }
  else {
    multiply_add_64(sum, op1, op2, carry, rdxReg, raxReg);
  }
  // Store back in big endian from little endian
  rorq(sum, 0x20);
  movq(Address(out, offset, Address::times_4,  8), sum);

  movq(op1, Address(in, len, Address::times_4,  0));
  rorq(op1, 32);
  movq(sum, Address(out, offset, Address::times_4,  0));
  rorq(sum, 32);
  if (UseBMI2Instructions) {
    multiply_add_64_bmi2(sum, op1, op2, carry, raxReg);
  }
  else {
    multiply_add_64(sum, op1, op2, carry, rdxReg, raxReg);
  }
  // Store back in big endian from little endian
  rorq(sum, 0x20);
  movq(Address(out, offset, Address::times_4,  0), sum);

  jmp(L_first_loop);
  bind(L_first_loop_exit);
}

/**
 * Code for BigInteger::mulAdd() intrinsic
 *
 * rdi: out
 * rsi: in
 * r11: offs (out.length - offset)
 * rcx: len
 * r8:  k
 * r12: tmp1
 * r13: tmp2
 * r14: tmp3
 * r15: tmp4
 * rbx: tmp5
 * Multiply the in[] by word k and add to out[], return the carry in rax
 */
void MacroAssembler::mul_add(Register out, Register in, Register offs,
   Register len, Register k, Register tmp1, Register tmp2, Register tmp3,
   Register tmp4, Register tmp5, Register rdxReg, Register raxReg) {

  Label L_carry, L_last_in, L_done;

// carry = 0;
// for (int j=len-1; j >= 0; j--) {
//    long product = (in[j] & LONG_MASK) * kLong +
//                   (out[offs] & LONG_MASK) + carry;
//    out[offs--] = (int)product;
//    carry = product >>> 32;
// }
//
  push(tmp1);
  push(tmp2);
  push(tmp3);
  push(tmp4);
  push(tmp5);

  Register op2 = tmp2;
  const Register sum = tmp3;
  const Register op1 = tmp4;
  const Register carry =  tmp5;

  if (UseBMI2Instructions) {
    op2 = rdxReg;
    movl(op2, k);
  }
  else {
    movl(op2, k);
  }

  xorq(carry, carry);

  //First loop

  //Multiply in[] by k in a 4 way unrolled loop using 128 bit by 32 bit multiply
  //The carry is in tmp5
  mul_add_128_x_32_loop(out, in, offs, len, tmp1, tmp2, tmp3, tmp4, tmp5, rdxReg, raxReg);

  //Multiply the trailing in[] entry using 64 bit by 32 bit, if any
  decrementl(len);
  jccb(Assembler::negative, L_carry);
  decrementl(len);
  jccb(Assembler::negative, L_last_in);

  movq(op1, Address(in, len, Address::times_4,  0));
  rorq(op1, 32);

  subl(offs, 2);
  movq(sum, Address(out, offs, Address::times_4,  0));
  rorq(sum, 32);

  if (UseBMI2Instructions) {
    multiply_add_64_bmi2(sum, op1, op2, carry, raxReg);
  }
  else {
    multiply_add_64(sum, op1, op2, carry, rdxReg, raxReg);
  }

  // Store back in big endian from little endian
  rorq(sum, 0x20);
  movq(Address(out, offs, Address::times_4,  0), sum);

  testl(len, len);
  jccb(Assembler::zero, L_carry);

  //Multiply the last in[] entry, if any
  bind(L_last_in);
  movl(op1, Address(in, 0));
  movl(sum, Address(out, offs, Address::times_4,  -4));

  movl(raxReg, k);
  mull(op1); //tmp4 * eax -> edx:eax
  addl(sum, carry);
  adcl(rdxReg, 0);
  addl(sum, raxReg);
  adcl(rdxReg, 0);
  movl(carry, rdxReg);

  movl(Address(out, offs, Address::times_4,  -4), sum);

  bind(L_carry);
  //return tmp5/carry as carry in rax
  movl(rax, carry);

  bind(L_done);
  pop(tmp5);
  pop(tmp4);
  pop(tmp3);
  pop(tmp2);
  pop(tmp1);
}
#endif

/**
 * Emits code to update CRC-32 with a byte value according to constants in table
 *
 * @param [in,out]crc   Register containing the crc.
 * @param [in]val       Register containing the byte to fold into the CRC.
 * @param [in]table     Register containing the table of crc constants.
 *
 * uint32_t crc;
 * val = crc_table[(val ^ crc) & 0xFF];
 * crc = val ^ (crc >> 8);
 *
 */
void MacroAssembler::update_byte_crc32(Register crc, Register val, Register table) {
  xorl(val, crc);
  andl(val, 0xFF);
  shrl(crc, 8); // unsigned shift
  xorl(crc, Address(table, val, Address::times_4, 0));
}

/**
 * Fold 128-bit data chunk
 */
void MacroAssembler::fold_128bit_crc32(XMMRegister xcrc, XMMRegister xK, XMMRegister xtmp, Register buf, int offset) {
  if (UseAVX > 0) {
    vpclmulhdq(xtmp, xK, xcrc); // [123:64]
    vpclmulldq(xcrc, xK, xcrc); // [63:0]
    vpxor(xcrc, xcrc, Address(buf, offset), 0 /* vector_len */);
    pxor(xcrc, xtmp);
  } else {
    movdqa(xtmp, xcrc);
    pclmulhdq(xtmp, xK);   // [123:64]
    pclmulldq(xcrc, xK);   // [63:0]
    pxor(xcrc, xtmp);
    movdqu(xtmp, Address(buf, offset));
    pxor(xcrc, xtmp);
  }
}

void MacroAssembler::fold_128bit_crc32(XMMRegister xcrc, XMMRegister xK, XMMRegister xtmp, XMMRegister xbuf) {
  if (UseAVX > 0) {
    vpclmulhdq(xtmp, xK, xcrc);
    vpclmulldq(xcrc, xK, xcrc);
    pxor(xcrc, xbuf);
    pxor(xcrc, xtmp);
  } else {
    movdqa(xtmp, xcrc);
    pclmulhdq(xtmp, xK);
    pclmulldq(xcrc, xK);
    pxor(xcrc, xbuf);
    pxor(xcrc, xtmp);
  }
}

/**
 * 8-bit folds to compute 32-bit CRC
 *
 * uint64_t xcrc;
 * timesXtoThe32[xcrc & 0xFF] ^ (xcrc >> 8);
 */
void MacroAssembler::fold_8bit_crc32(XMMRegister xcrc, Register table, XMMRegister xtmp, Register tmp) {
  movdl(tmp, xcrc);
  andl(tmp, 0xFF);
  movdl(xtmp, Address(table, tmp, Address::times_4, 0));
  psrldq(xcrc, 1); // unsigned shift one byte
  pxor(xcrc, xtmp);
}

/**
 * uint32_t crc;
 * timesXtoThe32[crc & 0xFF] ^ (crc >> 8);
 */
void MacroAssembler::fold_8bit_crc32(Register crc, Register table, Register tmp) {
  movl(tmp, crc);
  andl(tmp, 0xFF);
  shrl(crc, 8);
  xorl(crc, Address(table, tmp, Address::times_4, 0));
}

/**
 * @param crc   register containing existing CRC (32-bit)
 * @param buf   register pointing to input byte buffer (byte*)
 * @param len   register containing number of bytes
 * @param table register that will contain address of CRC table
 * @param tmp   scratch register
 */
void MacroAssembler::kernel_crc32(Register crc, Register buf, Register len, Register table, Register tmp) {
  assert_different_registers(crc, buf, len, table, tmp, rax);

  Label L_tail, L_tail_restore, L_tail_loop, L_exit, L_align_loop, L_aligned;
  Label L_fold_tail, L_fold_128b, L_fold_512b, L_fold_512b_loop, L_fold_tail_loop;

  // For EVEX with VL and BW, provide a standard mask, VL = 128 will guide the merge
  // context for the registers used, where all instructions below are using 128-bit mode
  // On EVEX without VL and BW, these instructions will all be AVX.
  lea(table, ExternalAddress(StubRoutines::crc_table_addr()));
  notl(crc); // ~crc
  cmpl(len, 16);
  jcc(Assembler::less, L_tail);

  // Align buffer to 16 bytes
  movl(tmp, buf);
  andl(tmp, 0xF);
  jccb(Assembler::zero, L_aligned);
  subl(tmp,  16);
  addl(len, tmp);

  align(4);
  BIND(L_align_loop);
  movsbl(rax, Address(buf, 0)); // load byte with sign extension
  update_byte_crc32(crc, rax, table);
  increment(buf);
  incrementl(tmp);
  jccb(Assembler::less, L_align_loop);

  BIND(L_aligned);
  movl(tmp, len); // save
  shrl(len, 4);
  jcc(Assembler::zero, L_tail_restore);

  // Fold crc into first bytes of vector
  movdqa(xmm1, Address(buf, 0));
  movdl(rax, xmm1);
  xorl(crc, rax);
  if (VM_Version::supports_sse4_1()) {
    pinsrd(xmm1, crc, 0);
  } else {
    pinsrw(xmm1, crc, 0);
    shrl(crc, 16);
    pinsrw(xmm1, crc, 1);
  }
  addptr(buf, 16);
  subl(len, 4); // len > 0
  jcc(Assembler::less, L_fold_tail);

  movdqa(xmm2, Address(buf,  0));
  movdqa(xmm3, Address(buf, 16));
  movdqa(xmm4, Address(buf, 32));
  addptr(buf, 48);
  subl(len, 3);
  jcc(Assembler::lessEqual, L_fold_512b);

  // Fold total 512 bits of polynomial on each iteration,
  // 128 bits per each of 4 parallel streams.
  movdqu(xmm0, ExternalAddress(StubRoutines::x86::crc_by128_masks_addr() + 32));

  align(32);
  BIND(L_fold_512b_loop);
  fold_128bit_crc32(xmm1, xmm0, xmm5, buf,  0);
  fold_128bit_crc32(xmm2, xmm0, xmm5, buf, 16);
  fold_128bit_crc32(xmm3, xmm0, xmm5, buf, 32);
  fold_128bit_crc32(xmm4, xmm0, xmm5, buf, 48);
  addptr(buf, 64);
  subl(len, 4);
  jcc(Assembler::greater, L_fold_512b_loop);

  // Fold 512 bits to 128 bits.
  BIND(L_fold_512b);
  movdqu(xmm0, ExternalAddress(StubRoutines::x86::crc_by128_masks_addr() + 16));
  fold_128bit_crc32(xmm1, xmm0, xmm5, xmm2);
  fold_128bit_crc32(xmm1, xmm0, xmm5, xmm3);
  fold_128bit_crc32(xmm1, xmm0, xmm5, xmm4);

  // Fold the rest of 128 bits data chunks
  BIND(L_fold_tail);
  addl(len, 3);
  jccb(Assembler::lessEqual, L_fold_128b);
  movdqu(xmm0, ExternalAddress(StubRoutines::x86::crc_by128_masks_addr() + 16));

  BIND(L_fold_tail_loop);
  fold_128bit_crc32(xmm1, xmm0, xmm5, buf,  0);
  addptr(buf, 16);
  decrementl(len);
  jccb(Assembler::greater, L_fold_tail_loop);

  // Fold 128 bits in xmm1 down into 32 bits in crc register.
  BIND(L_fold_128b);
  movdqu(xmm0, ExternalAddress(StubRoutines::x86::crc_by128_masks_addr()));
  if (UseAVX > 0) {
    vpclmulqdq(xmm2, xmm0, xmm1, 0x1);
    vpand(xmm3, xmm0, xmm2, 0 /* vector_len */);
    vpclmulqdq(xmm0, xmm0, xmm3, 0x1);
  } else {
    movdqa(xmm2, xmm0);
    pclmulqdq(xmm2, xmm1, 0x1);
    movdqa(xmm3, xmm0);
    pand(xmm3, xmm2);
    pclmulqdq(xmm0, xmm3, 0x1);
  }
  psrldq(xmm1, 8);
  psrldq(xmm2, 4);
  pxor(xmm0, xmm1);
  pxor(xmm0, xmm2);

  // 8 8-bit folds to compute 32-bit CRC.
  for (int j = 0; j < 4; j++) {
    fold_8bit_crc32(xmm0, table, xmm1, rax);
  }
  movdl(crc, xmm0); // mov 32 bits to general register
  for (int j = 0; j < 4; j++) {
    fold_8bit_crc32(crc, table, rax);
  }

  BIND(L_tail_restore);
  movl(len, tmp); // restore
  BIND(L_tail);
  andl(len, 0xf);
  jccb(Assembler::zero, L_exit);

  // Fold the rest of bytes
  align(4);
  BIND(L_tail_loop);
  movsbl(rax, Address(buf, 0)); // load byte with sign extension
  update_byte_crc32(crc, rax, table);
  increment(buf);
  decrementl(len);
  jccb(Assembler::greater, L_tail_loop);

  BIND(L_exit);
  notl(crc); // ~c
}

#ifdef _LP64
// Helper function for AVX 512 CRC32
// Fold 512-bit data chunks
void MacroAssembler::fold512bit_crc32_avx512(XMMRegister xcrc, XMMRegister xK, XMMRegister xtmp, Register buf,
                                             Register pos, int offset) {
  evmovdquq(xmm3, Address(buf, pos, Address::times_1, offset), Assembler::AVX_512bit);
  evpclmulqdq(xtmp, xcrc, xK, 0x10, Assembler::AVX_512bit); // [123:64]
  evpclmulqdq(xmm2, xcrc, xK, 0x01, Assembler::AVX_512bit); // [63:0]
  evpxorq(xcrc, xtmp, xmm2, Assembler::AVX_512bit /* vector_len */);
  evpxorq(xcrc, xcrc, xmm3, Assembler::AVX_512bit /* vector_len */);
}

// Helper function for AVX 512 CRC32
// Compute CRC32 for < 256B buffers
void MacroAssembler::kernel_crc32_avx512_256B(Register crc, Register buf, Register len, Register key, Register pos,
                                              Register tmp1, Register tmp2, Label& L_barrett, Label& L_16B_reduction_loop,
                                              Label& L_get_last_two_xmms, Label& L_128_done, Label& L_cleanup) {

  Label L_less_than_32, L_exact_16_left, L_less_than_16_left;
  Label L_less_than_8_left, L_less_than_4_left, L_less_than_2_left, L_zero_left;
  Label L_only_less_than_4, L_only_less_than_3, L_only_less_than_2;

  // check if there is enough buffer to be able to fold 16B at a time
  cmpl(len, 32);
  jcc(Assembler::less, L_less_than_32);

  // if there is, load the constants
  movdqu(xmm10, Address(key, 1 * 16));    //rk1 and rk2 in xmm10
  movdl(xmm0, crc);                        // get the initial crc value
  movdqu(xmm7, Address(buf, pos, Address::times_1, 0 * 16)); //load the plaintext
  pxor(xmm7, xmm0);

  // update the buffer pointer
  addl(pos, 16);
  //update the counter.subtract 32 instead of 16 to save one instruction from the loop
  subl(len, 32);
  jmp(L_16B_reduction_loop);

  bind(L_less_than_32);
  //mov initial crc to the return value. this is necessary for zero - length buffers.
  movl(rax, crc);
  testl(len, len);
  jcc(Assembler::equal, L_cleanup);

  movdl(xmm0, crc);                        //get the initial crc value

  cmpl(len, 16);
  jcc(Assembler::equal, L_exact_16_left);
  jcc(Assembler::less, L_less_than_16_left);

  movdqu(xmm7, Address(buf, pos, Address::times_1, 0 * 16)); //load the plaintext
  pxor(xmm7, xmm0);                       //xor the initial crc value
  addl(pos, 16);
  subl(len, 16);
  movdqu(xmm10, Address(key, 1 * 16));    // rk1 and rk2 in xmm10
  jmp(L_get_last_two_xmms);

  bind(L_less_than_16_left);
  //use stack space to load data less than 16 bytes, zero - out the 16B in memory first.
  pxor(xmm1, xmm1);
  movptr(tmp1, rsp);
  movdqu(Address(tmp1, 0 * 16), xmm1);

  cmpl(len, 4);
  jcc(Assembler::less, L_only_less_than_4);

  //backup the counter value
  movl(tmp2, len);
  cmpl(len, 8);
  jcc(Assembler::less, L_less_than_8_left);

  //load 8 Bytes
  movq(rax, Address(buf, pos, Address::times_1, 0 * 16));
  movq(Address(tmp1, 0 * 16), rax);
  addptr(tmp1, 8);
  subl(len, 8);
  addl(pos, 8);

  bind(L_less_than_8_left);
  cmpl(len, 4);
  jcc(Assembler::less, L_less_than_4_left);

  //load 4 Bytes
  movl(rax, Address(buf, pos, Address::times_1, 0));
  movl(Address(tmp1, 0 * 16), rax);
  addptr(tmp1, 4);
  subl(len, 4);
  addl(pos, 4);

  bind(L_less_than_4_left);
  cmpl(len, 2);
  jcc(Assembler::less, L_less_than_2_left);

  // load 2 Bytes
  movw(rax, Address(buf, pos, Address::times_1, 0));
  movl(Address(tmp1, 0 * 16), rax);
  addptr(tmp1, 2);
  subl(len, 2);
  addl(pos, 2);

  bind(L_less_than_2_left);
  cmpl(len, 1);
  jcc(Assembler::less, L_zero_left);

  // load 1 Byte
  movb(rax, Address(buf, pos, Address::times_1, 0));
  movb(Address(tmp1, 0 * 16), rax);

  bind(L_zero_left);
  movdqu(xmm7, Address(rsp, 0));
  pxor(xmm7, xmm0);                       //xor the initial crc value

  lea(rax, ExternalAddress(StubRoutines::x86::shuf_table_crc32_avx512_addr()));
  movdqu(xmm0, Address(rax, tmp2));
  pshufb(xmm7, xmm0);
  jmp(L_128_done);

  bind(L_exact_16_left);
  movdqu(xmm7, Address(buf, pos, Address::times_1, 0));
  pxor(xmm7, xmm0);                       //xor the initial crc value
  jmp(L_128_done);

  bind(L_only_less_than_4);
  cmpl(len, 3);
  jcc(Assembler::less, L_only_less_than_3);

  // load 3 Bytes
  movb(rax, Address(buf, pos, Address::times_1, 0));
  movb(Address(tmp1, 0), rax);

  movb(rax, Address(buf, pos, Address::times_1, 1));
  movb(Address(tmp1, 1), rax);

  movb(rax, Address(buf, pos, Address::times_1, 2));
  movb(Address(tmp1, 2), rax);

  movdqu(xmm7, Address(rsp, 0));
  pxor(xmm7, xmm0);                     //xor the initial crc value

  pslldq(xmm7, 0x5);
  jmp(L_barrett);
  bind(L_only_less_than_3);
  cmpl(len, 2);
  jcc(Assembler::less, L_only_less_than_2);

  // load 2 Bytes
  movb(rax, Address(buf, pos, Address::times_1, 0));
  movb(Address(tmp1, 0), rax);

  movb(rax, Address(buf, pos, Address::times_1, 1));
  movb(Address(tmp1, 1), rax);

  movdqu(xmm7, Address(rsp, 0));
  pxor(xmm7, xmm0);                     //xor the initial crc value

  pslldq(xmm7, 0x6);
  jmp(L_barrett);

  bind(L_only_less_than_2);
  //load 1 Byte
  movb(rax, Address(buf, pos, Address::times_1, 0));
  movb(Address(tmp1, 0), rax);

  movdqu(xmm7, Address(rsp, 0));
  pxor(xmm7, xmm0);                     //xor the initial crc value

  pslldq(xmm7, 0x7);
}

/**
* Compute CRC32 using AVX512 instructions
* param crc   register containing existing CRC (32-bit)
* param buf   register pointing to input byte buffer (byte*)
* param len   register containing number of bytes
* param tmp1  scratch register
* param tmp2  scratch register
* return rax  result register
*/
void MacroAssembler::kernel_crc32_avx512(Register crc, Register buf, Register len, Register key, Register tmp1, Register tmp2) {
  assert_different_registers(crc, buf, len, key, tmp1, tmp2, rax);

  Label L_tail, L_tail_restore, L_tail_loop, L_exit, L_align_loop, L_aligned;
  Label L_fold_tail, L_fold_128b, L_fold_512b, L_fold_512b_loop, L_fold_tail_loop;
  Label L_less_than_256, L_fold_128_B_loop, L_fold_256_B_loop;
  Label L_fold_128_B_register, L_final_reduction_for_128, L_16B_reduction_loop;
  Label L_128_done, L_get_last_two_xmms, L_barrett, L_cleanup;

  const Register pos = r12;
  push(r12);
  subptr(rsp, 16 * 2 + 8);

  // For EVEX with VL and BW, provide a standard mask, VL = 128 will guide the merge
  // context for the registers used, where all instructions below are using 128-bit mode
  // On EVEX without VL and BW, these instructions will all be AVX.
  lea(key, ExternalAddress(StubRoutines::x86::crc_table_avx512_addr()));
  notl(crc);
  movl(pos, 0);

  // check if smaller than 256B
  cmpl(len, 256);
  jcc(Assembler::less, L_less_than_256);

  // load the initial crc value
  movdl(xmm10, crc);

  // receive the initial 64B data, xor the initial crc value
  evmovdquq(xmm0, Address(buf, pos, Address::times_1, 0 * 64), Assembler::AVX_512bit);
  evmovdquq(xmm4, Address(buf, pos, Address::times_1, 1 * 64), Assembler::AVX_512bit);
  evpxorq(xmm0, xmm0, xmm10, Assembler::AVX_512bit);
  evbroadcasti32x4(xmm10, Address(key, 2 * 16), Assembler::AVX_512bit); //zmm10 has rk3 and rk4

  subl(len, 256);
  cmpl(len, 256);
  jcc(Assembler::less, L_fold_128_B_loop);

  evmovdquq(xmm7, Address(buf, pos, Address::times_1, 2 * 64), Assembler::AVX_512bit);
  evmovdquq(xmm8, Address(buf, pos, Address::times_1, 3 * 64), Assembler::AVX_512bit);
  evbroadcasti32x4(xmm16, Address(key, 0 * 16), Assembler::AVX_512bit); //zmm16 has rk-1 and rk-2
  subl(len, 256);

  bind(L_fold_256_B_loop);
  addl(pos, 256);
  fold512bit_crc32_avx512(xmm0, xmm16, xmm1, buf, pos, 0 * 64);
  fold512bit_crc32_avx512(xmm4, xmm16, xmm1, buf, pos, 1 * 64);
  fold512bit_crc32_avx512(xmm7, xmm16, xmm1, buf, pos, 2 * 64);
  fold512bit_crc32_avx512(xmm8, xmm16, xmm1, buf, pos, 3 * 64);

  subl(len, 256);
  jcc(Assembler::greaterEqual, L_fold_256_B_loop);

  // Fold 256 into 128
  addl(pos, 256);
  evpclmulqdq(xmm1, xmm0, xmm10, 0x01, Assembler::AVX_512bit);
  evpclmulqdq(xmm2, xmm0, xmm10, 0x10, Assembler::AVX_512bit);
  vpternlogq(xmm7, 0x96, xmm1, xmm2, Assembler::AVX_512bit); // xor ABC

  evpclmulqdq(xmm5, xmm4, xmm10, 0x01, Assembler::AVX_512bit);
  evpclmulqdq(xmm6, xmm4, xmm10, 0x10, Assembler::AVX_512bit);
  vpternlogq(xmm8, 0x96, xmm5, xmm6, Assembler::AVX_512bit); // xor ABC

  evmovdquq(xmm0, xmm7, Assembler::AVX_512bit);
  evmovdquq(xmm4, xmm8, Assembler::AVX_512bit);

  addl(len, 128);
  jmp(L_fold_128_B_register);

  // at this section of the code, there is 128 * x + y(0 <= y<128) bytes of buffer.The fold_128_B_loop
  // loop will fold 128B at a time until we have 128 + y Bytes of buffer

  // fold 128B at a time.This section of the code folds 8 xmm registers in parallel
  bind(L_fold_128_B_loop);
  addl(pos, 128);
  fold512bit_crc32_avx512(xmm0, xmm10, xmm1, buf, pos, 0 * 64);
  fold512bit_crc32_avx512(xmm4, xmm10, xmm1, buf, pos, 1 * 64);

  subl(len, 128);
  jcc(Assembler::greaterEqual, L_fold_128_B_loop);

  addl(pos, 128);

  // at this point, the buffer pointer is pointing at the last y Bytes of the buffer, where 0 <= y < 128
  // the 128B of folded data is in 8 of the xmm registers : xmm0, xmm1, xmm2, xmm3, xmm4, xmm5, xmm6, xmm7
  bind(L_fold_128_B_register);
  evmovdquq(xmm16, Address(key, 5 * 16), Assembler::AVX_512bit); // multiply by rk9-rk16
  evmovdquq(xmm11, Address(key, 9 * 16), Assembler::AVX_512bit); // multiply by rk17-rk20, rk1,rk2, 0,0
  evpclmulqdq(xmm1, xmm0, xmm16, 0x01, Assembler::AVX_512bit);
  evpclmulqdq(xmm2, xmm0, xmm16, 0x10, Assembler::AVX_512bit);
  // save last that has no multiplicand
  vextracti64x2(xmm7, xmm4, 3);

  evpclmulqdq(xmm5, xmm4, xmm11, 0x01, Assembler::AVX_512bit);
  evpclmulqdq(xmm6, xmm4, xmm11, 0x10, Assembler::AVX_512bit);
  // Needed later in reduction loop
  movdqu(xmm10, Address(key, 1 * 16));
  vpternlogq(xmm1, 0x96, xmm2, xmm5, Assembler::AVX_512bit); // xor ABC
  vpternlogq(xmm1, 0x96, xmm6, xmm7, Assembler::AVX_512bit); // xor ABC

  // Swap 1,0,3,2 - 01 00 11 10
  evshufi64x2(xmm8, xmm1, xmm1, 0x4e, Assembler::AVX_512bit);
  evpxorq(xmm8, xmm8, xmm1, Assembler::AVX_256bit);
  vextracti128(xmm5, xmm8, 1);
  evpxorq(xmm7, xmm5, xmm8, Assembler::AVX_128bit);

  // instead of 128, we add 128 - 16 to the loop counter to save 1 instruction from the loop
  // instead of a cmp instruction, we use the negative flag with the jl instruction
  addl(len, 128 - 16);
  jcc(Assembler::less, L_final_reduction_for_128);

  bind(L_16B_reduction_loop);
  vpclmulqdq(xmm8, xmm7, xmm10, 0x1);
  vpclmulqdq(xmm7, xmm7, xmm10, 0x10);
  vpxor(xmm7, xmm7, xmm8, Assembler::AVX_128bit);
  movdqu(xmm0, Address(buf, pos, Address::times_1, 0 * 16));
  vpxor(xmm7, xmm7, xmm0, Assembler::AVX_128bit);
  addl(pos, 16);
  subl(len, 16);
  jcc(Assembler::greaterEqual, L_16B_reduction_loop);

  bind(L_final_reduction_for_128);
  addl(len, 16);
  jcc(Assembler::equal, L_128_done);

  bind(L_get_last_two_xmms);
  movdqu(xmm2, xmm7);
  addl(pos, len);
  movdqu(xmm1, Address(buf, pos, Address::times_1, -16));
  subl(pos, len);

  // get rid of the extra data that was loaded before
  // load the shift constant
  lea(rax, ExternalAddress(StubRoutines::x86::shuf_table_crc32_avx512_addr()));
  movdqu(xmm0, Address(rax, len));
  addl(rax, len);

  vpshufb(xmm7, xmm7, xmm0, Assembler::AVX_128bit);
  //Change mask to 512
  vpxor(xmm0, xmm0, ExternalAddress(StubRoutines::x86::crc_by128_masks_avx512_addr() + 2 * 16), Assembler::AVX_128bit, tmp2);
  vpshufb(xmm2, xmm2, xmm0, Assembler::AVX_128bit);

  blendvpb(xmm2, xmm2, xmm1, xmm0, Assembler::AVX_128bit);
  vpclmulqdq(xmm8, xmm7, xmm10, 0x1);
  vpclmulqdq(xmm7, xmm7, xmm10, 0x10);
  vpxor(xmm7, xmm7, xmm8, Assembler::AVX_128bit);
  vpxor(xmm7, xmm7, xmm2, Assembler::AVX_128bit);

  bind(L_128_done);
  // compute crc of a 128-bit value
  movdqu(xmm10, Address(key, 3 * 16));
  movdqu(xmm0, xmm7);

  // 64b fold
  vpclmulqdq(xmm7, xmm7, xmm10, 0x0);
  vpsrldq(xmm0, xmm0, 0x8, Assembler::AVX_128bit);
  vpxor(xmm7, xmm7, xmm0, Assembler::AVX_128bit);

  // 32b fold
  movdqu(xmm0, xmm7);
  vpslldq(xmm7, xmm7, 0x4, Assembler::AVX_128bit);
  vpclmulqdq(xmm7, xmm7, xmm10, 0x10);
  vpxor(xmm7, xmm7, xmm0, Assembler::AVX_128bit);
  jmp(L_barrett);

  bind(L_less_than_256);
  kernel_crc32_avx512_256B(crc, buf, len, key, pos, tmp1, tmp2, L_barrett, L_16B_reduction_loop, L_get_last_two_xmms, L_128_done, L_cleanup);

  //barrett reduction
  bind(L_barrett);
  vpand(xmm7, xmm7, ExternalAddress(StubRoutines::x86::crc_by128_masks_avx512_addr() + 1 * 16), Assembler::AVX_128bit, tmp2);
  movdqu(xmm1, xmm7);
  movdqu(xmm2, xmm7);
  movdqu(xmm10, Address(key, 4 * 16));

  pclmulqdq(xmm7, xmm10, 0x0);
  pxor(xmm7, xmm2);
  vpand(xmm7, xmm7, ExternalAddress(StubRoutines::x86::crc_by128_masks_avx512_addr()), Assembler::AVX_128bit, tmp2);
  movdqu(xmm2, xmm7);
  pclmulqdq(xmm7, xmm10, 0x10);
  pxor(xmm7, xmm2);
  pxor(xmm7, xmm1);
  pextrd(crc, xmm7, 2);

  bind(L_cleanup);
  notl(crc); // ~c
  addptr(rsp, 16 * 2 + 8);
  pop(r12);
}

// S. Gueron / Information Processing Letters 112 (2012) 184
// Algorithm 4: Computing carry-less multiplication using a precomputed lookup table.
// Input: A 32 bit value B = [byte3, byte2, byte1, byte0].
// Output: the 64-bit carry-less product of B * CONST
void MacroAssembler::crc32c_ipl_alg4(Register in, uint32_t n,
                                     Register tmp1, Register tmp2, Register tmp3) {
  lea(tmp3, ExternalAddress(StubRoutines::crc32c_table_addr()));
  if (n > 0) {
    addq(tmp3, n * 256 * 8);
  }
  //    Q1 = TABLEExt[n][B & 0xFF];
  movl(tmp1, in);
  andl(tmp1, 0x000000FF);
  shll(tmp1, 3);
  addq(tmp1, tmp3);
  movq(tmp1, Address(tmp1, 0));

  //    Q2 = TABLEExt[n][B >> 8 & 0xFF];
  movl(tmp2, in);
  shrl(tmp2, 8);
  andl(tmp2, 0x000000FF);
  shll(tmp2, 3);
  addq(tmp2, tmp3);
  movq(tmp2, Address(tmp2, 0));

  shlq(tmp2, 8);
  xorq(tmp1, tmp2);

  //    Q3 = TABLEExt[n][B >> 16 & 0xFF];
  movl(tmp2, in);
  shrl(tmp2, 16);
  andl(tmp2, 0x000000FF);
  shll(tmp2, 3);
  addq(tmp2, tmp3);
  movq(tmp2, Address(tmp2, 0));

  shlq(tmp2, 16);
  xorq(tmp1, tmp2);

  //    Q4 = TABLEExt[n][B >> 24 & 0xFF];
  shrl(in, 24);
  andl(in, 0x000000FF);
  shll(in, 3);
  addq(in, tmp3);
  movq(in, Address(in, 0));

  shlq(in, 24);
  xorq(in, tmp1);
  //    return Q1 ^ Q2 << 8 ^ Q3 << 16 ^ Q4 << 24;
}

void MacroAssembler::crc32c_pclmulqdq(XMMRegister w_xtmp1,
                                      Register in_out,
                                      uint32_t const_or_pre_comp_const_index, bool is_pclmulqdq_supported,
                                      XMMRegister w_xtmp2,
                                      Register tmp1,
                                      Register n_tmp2, Register n_tmp3) {
  if (is_pclmulqdq_supported) {
    movdl(w_xtmp1, in_out); // modified blindly

    movl(tmp1, const_or_pre_comp_const_index);
    movdl(w_xtmp2, tmp1);
    pclmulqdq(w_xtmp1, w_xtmp2, 0);

    movdq(in_out, w_xtmp1);
  } else {
    crc32c_ipl_alg4(in_out, const_or_pre_comp_const_index, tmp1, n_tmp2, n_tmp3);
  }
}

// Recombination Alternative 2: No bit-reflections
// T1 = (CRC_A * U1) << 1
// T2 = (CRC_B * U2) << 1
// C1 = T1 >> 32
// C2 = T2 >> 32
// T1 = T1 & 0xFFFFFFFF
// T2 = T2 & 0xFFFFFFFF
// T1 = CRC32(0, T1)
// T2 = CRC32(0, T2)
// C1 = C1 ^ T1
// C2 = C2 ^ T2
// CRC = C1 ^ C2 ^ CRC_C
void MacroAssembler::crc32c_rec_alt2(uint32_t const_or_pre_comp_const_index_u1, uint32_t const_or_pre_comp_const_index_u2, bool is_pclmulqdq_supported, Register in_out, Register in1, Register in2,
                                     XMMRegister w_xtmp1, XMMRegister w_xtmp2, XMMRegister w_xtmp3,
                                     Register tmp1, Register tmp2,
                                     Register n_tmp3) {
  crc32c_pclmulqdq(w_xtmp1, in_out, const_or_pre_comp_const_index_u1, is_pclmulqdq_supported, w_xtmp3, tmp1, tmp2, n_tmp3);
  crc32c_pclmulqdq(w_xtmp2, in1, const_or_pre_comp_const_index_u2, is_pclmulqdq_supported, w_xtmp3, tmp1, tmp2, n_tmp3);
  shlq(in_out, 1);
  movl(tmp1, in_out);
  shrq(in_out, 32);
  xorl(tmp2, tmp2);
  crc32(tmp2, tmp1, 4);
  xorl(in_out, tmp2); // we don't care about upper 32 bit contents here
  shlq(in1, 1);
  movl(tmp1, in1);
  shrq(in1, 32);
  xorl(tmp2, tmp2);
  crc32(tmp2, tmp1, 4);
  xorl(in1, tmp2);
  xorl(in_out, in1);
  xorl(in_out, in2);
}

// Set N to predefined value
// Subtract from a lenght of a buffer
// execute in a loop:
// CRC_A = 0xFFFFFFFF, CRC_B = 0, CRC_C = 0
// for i = 1 to N do
//  CRC_A = CRC32(CRC_A, A[i])
//  CRC_B = CRC32(CRC_B, B[i])
//  CRC_C = CRC32(CRC_C, C[i])
// end for
// Recombine
void MacroAssembler::crc32c_proc_chunk(uint32_t size, uint32_t const_or_pre_comp_const_index_u1, uint32_t const_or_pre_comp_const_index_u2, bool is_pclmulqdq_supported,
                                       Register in_out1, Register in_out2, Register in_out3,
                                       Register tmp1, Register tmp2, Register tmp3,
                                       XMMRegister w_xtmp1, XMMRegister w_xtmp2, XMMRegister w_xtmp3,
                                       Register tmp4, Register tmp5,
                                       Register n_tmp6) {
  Label L_processPartitions;
  Label L_processPartition;
  Label L_exit;

  bind(L_processPartitions);
  cmpl(in_out1, 3 * size);
  jcc(Assembler::less, L_exit);
    xorl(tmp1, tmp1);
    xorl(tmp2, tmp2);
    movq(tmp3, in_out2);
    addq(tmp3, size);

    bind(L_processPartition);
      crc32(in_out3, Address(in_out2, 0), 8);
      crc32(tmp1, Address(in_out2, size), 8);
      crc32(tmp2, Address(in_out2, size * 2), 8);
      addq(in_out2, 8);
      cmpq(in_out2, tmp3);
      jcc(Assembler::less, L_processPartition);
    crc32c_rec_alt2(const_or_pre_comp_const_index_u1, const_or_pre_comp_const_index_u2, is_pclmulqdq_supported, in_out3, tmp1, tmp2,
            w_xtmp1, w_xtmp2, w_xtmp3,
            tmp4, tmp5,
            n_tmp6);
    addq(in_out2, 2 * size);
    subl(in_out1, 3 * size);
    jmp(L_processPartitions);

  bind(L_exit);
}
#else
void MacroAssembler::crc32c_ipl_alg4(Register in_out, uint32_t n,
                                     Register tmp1, Register tmp2, Register tmp3,
                                     XMMRegister xtmp1, XMMRegister xtmp2) {
  lea(tmp3, ExternalAddress(StubRoutines::crc32c_table_addr()));
  if (n > 0) {
    addl(tmp3, n * 256 * 8);
  }
  //    Q1 = TABLEExt[n][B & 0xFF];
  movl(tmp1, in_out);
  andl(tmp1, 0x000000FF);
  shll(tmp1, 3);
  addl(tmp1, tmp3);
  movq(xtmp1, Address(tmp1, 0));

  //    Q2 = TABLEExt[n][B >> 8 & 0xFF];
  movl(tmp2, in_out);
  shrl(tmp2, 8);
  andl(tmp2, 0x000000FF);
  shll(tmp2, 3);
  addl(tmp2, tmp3);
  movq(xtmp2, Address(tmp2, 0));

  psllq(xtmp2, 8);
  pxor(xtmp1, xtmp2);

  //    Q3 = TABLEExt[n][B >> 16 & 0xFF];
  movl(tmp2, in_out);
  shrl(tmp2, 16);
  andl(tmp2, 0x000000FF);
  shll(tmp2, 3);
  addl(tmp2, tmp3);
  movq(xtmp2, Address(tmp2, 0));

  psllq(xtmp2, 16);
  pxor(xtmp1, xtmp2);

  //    Q4 = TABLEExt[n][B >> 24 & 0xFF];
  shrl(in_out, 24);
  andl(in_out, 0x000000FF);
  shll(in_out, 3);
  addl(in_out, tmp3);
  movq(xtmp2, Address(in_out, 0));

  psllq(xtmp2, 24);
  pxor(xtmp1, xtmp2); // Result in CXMM
  //    return Q1 ^ Q2 << 8 ^ Q3 << 16 ^ Q4 << 24;
}

void MacroAssembler::crc32c_pclmulqdq(XMMRegister w_xtmp1,
                                      Register in_out,
                                      uint32_t const_or_pre_comp_const_index, bool is_pclmulqdq_supported,
                                      XMMRegister w_xtmp2,
                                      Register tmp1,
                                      Register n_tmp2, Register n_tmp3) {
  if (is_pclmulqdq_supported) {
    movdl(w_xtmp1, in_out);

    movl(tmp1, const_or_pre_comp_const_index);
    movdl(w_xtmp2, tmp1);
    pclmulqdq(w_xtmp1, w_xtmp2, 0);
    // Keep result in XMM since GPR is 32 bit in length
  } else {
    crc32c_ipl_alg4(in_out, const_or_pre_comp_const_index, tmp1, n_tmp2, n_tmp3, w_xtmp1, w_xtmp2);
  }
}

void MacroAssembler::crc32c_rec_alt2(uint32_t const_or_pre_comp_const_index_u1, uint32_t const_or_pre_comp_const_index_u2, bool is_pclmulqdq_supported, Register in_out, Register in1, Register in2,
                                     XMMRegister w_xtmp1, XMMRegister w_xtmp2, XMMRegister w_xtmp3,
                                     Register tmp1, Register tmp2,
                                     Register n_tmp3) {
  crc32c_pclmulqdq(w_xtmp1, in_out, const_or_pre_comp_const_index_u1, is_pclmulqdq_supported, w_xtmp3, tmp1, tmp2, n_tmp3);
  crc32c_pclmulqdq(w_xtmp2, in1, const_or_pre_comp_const_index_u2, is_pclmulqdq_supported, w_xtmp3, tmp1, tmp2, n_tmp3);

  psllq(w_xtmp1, 1);
  movdl(tmp1, w_xtmp1);
  psrlq(w_xtmp1, 32);
  movdl(in_out, w_xtmp1);

  xorl(tmp2, tmp2);
  crc32(tmp2, tmp1, 4);
  xorl(in_out, tmp2);

  psllq(w_xtmp2, 1);
  movdl(tmp1, w_xtmp2);
  psrlq(w_xtmp2, 32);
  movdl(in1, w_xtmp2);

  xorl(tmp2, tmp2);
  crc32(tmp2, tmp1, 4);
  xorl(in1, tmp2);
  xorl(in_out, in1);
  xorl(in_out, in2);
}

void MacroAssembler::crc32c_proc_chunk(uint32_t size, uint32_t const_or_pre_comp_const_index_u1, uint32_t const_or_pre_comp_const_index_u2, bool is_pclmulqdq_supported,
                                       Register in_out1, Register in_out2, Register in_out3,
                                       Register tmp1, Register tmp2, Register tmp3,
                                       XMMRegister w_xtmp1, XMMRegister w_xtmp2, XMMRegister w_xtmp3,
                                       Register tmp4, Register tmp5,
                                       Register n_tmp6) {
  Label L_processPartitions;
  Label L_processPartition;
  Label L_exit;

  bind(L_processPartitions);
  cmpl(in_out1, 3 * size);
  jcc(Assembler::less, L_exit);
    xorl(tmp1, tmp1);
    xorl(tmp2, tmp2);
    movl(tmp3, in_out2);
    addl(tmp3, size);

    bind(L_processPartition);
      crc32(in_out3, Address(in_out2, 0), 4);
      crc32(tmp1, Address(in_out2, size), 4);
      crc32(tmp2, Address(in_out2, size*2), 4);
      crc32(in_out3, Address(in_out2, 0+4), 4);
      crc32(tmp1, Address(in_out2, size+4), 4);
      crc32(tmp2, Address(in_out2, size*2+4), 4);
      addl(in_out2, 8);
      cmpl(in_out2, tmp3);
      jcc(Assembler::less, L_processPartition);

        push(tmp3);
        push(in_out1);
        push(in_out2);
        tmp4 = tmp3;
        tmp5 = in_out1;
        n_tmp6 = in_out2;

      crc32c_rec_alt2(const_or_pre_comp_const_index_u1, const_or_pre_comp_const_index_u2, is_pclmulqdq_supported, in_out3, tmp1, tmp2,
            w_xtmp1, w_xtmp2, w_xtmp3,
            tmp4, tmp5,
            n_tmp6);

        pop(in_out2);
        pop(in_out1);
        pop(tmp3);

    addl(in_out2, 2 * size);
    subl(in_out1, 3 * size);
    jmp(L_processPartitions);

  bind(L_exit);
}
#endif //LP64

#ifdef _LP64
// Algorithm 2: Pipelined usage of the CRC32 instruction.
// Input: A buffer I of L bytes.
// Output: the CRC32C value of the buffer.
// Notations:
// Write L = 24N + r, with N = floor (L/24).
// r = L mod 24 (0 <= r < 24).
// Consider I as the concatenation of A|B|C|R, where A, B, C, each,
// N quadwords, and R consists of r bytes.
// A[j] = I [8j+7:8j], j= 0, 1, ..., N-1
// B[j] = I [N + 8j+7:N + 8j], j= 0, 1, ..., N-1
// C[j] = I [2N + 8j+7:2N + 8j], j= 0, 1, ..., N-1
// if r > 0 R[j] = I [3N +j], j= 0, 1, ...,r-1
void MacroAssembler::crc32c_ipl_alg2_alt2(Register in_out, Register in1, Register in2,
                                          Register tmp1, Register tmp2, Register tmp3,
                                          Register tmp4, Register tmp5, Register tmp6,
                                          XMMRegister w_xtmp1, XMMRegister w_xtmp2, XMMRegister w_xtmp3,
                                          bool is_pclmulqdq_supported) {
  uint32_t const_or_pre_comp_const_index[CRC32C_NUM_PRECOMPUTED_CONSTANTS];
  Label L_wordByWord;
  Label L_byteByByteProlog;
  Label L_byteByByte;
  Label L_exit;

  if (is_pclmulqdq_supported ) {
    const_or_pre_comp_const_index[1] = *(uint32_t *)StubRoutines::_crc32c_table_addr;
    const_or_pre_comp_const_index[0] = *((uint32_t *)StubRoutines::_crc32c_table_addr+1);

    const_or_pre_comp_const_index[3] = *((uint32_t *)StubRoutines::_crc32c_table_addr + 2);
    const_or_pre_comp_const_index[2] = *((uint32_t *)StubRoutines::_crc32c_table_addr + 3);

    const_or_pre_comp_const_index[5] = *((uint32_t *)StubRoutines::_crc32c_table_addr + 4);
    const_or_pre_comp_const_index[4] = *((uint32_t *)StubRoutines::_crc32c_table_addr + 5);
    assert((CRC32C_NUM_PRECOMPUTED_CONSTANTS - 1 ) == 5, "Checking whether you declared all of the constants based on the number of \"chunks\"");
  } else {
    const_or_pre_comp_const_index[0] = 1;
    const_or_pre_comp_const_index[1] = 0;

    const_or_pre_comp_const_index[2] = 3;
    const_or_pre_comp_const_index[3] = 2;

    const_or_pre_comp_const_index[4] = 5;
    const_or_pre_comp_const_index[5] = 4;
   }
  crc32c_proc_chunk(CRC32C_HIGH, const_or_pre_comp_const_index[0], const_or_pre_comp_const_index[1], is_pclmulqdq_supported,
                    in2, in1, in_out,
                    tmp1, tmp2, tmp3,
                    w_xtmp1, w_xtmp2, w_xtmp3,
                    tmp4, tmp5,
                    tmp6);
  crc32c_proc_chunk(CRC32C_MIDDLE, const_or_pre_comp_const_index[2], const_or_pre_comp_const_index[3], is_pclmulqdq_supported,
                    in2, in1, in_out,
                    tmp1, tmp2, tmp3,
                    w_xtmp1, w_xtmp2, w_xtmp3,
                    tmp4, tmp5,
                    tmp6);
  crc32c_proc_chunk(CRC32C_LOW, const_or_pre_comp_const_index[4], const_or_pre_comp_const_index[5], is_pclmulqdq_supported,
                    in2, in1, in_out,
                    tmp1, tmp2, tmp3,
                    w_xtmp1, w_xtmp2, w_xtmp3,
                    tmp4, tmp5,
                    tmp6);
  movl(tmp1, in2);
  andl(tmp1, 0x00000007);
  negl(tmp1);
  addl(tmp1, in2);
  addq(tmp1, in1);

  BIND(L_wordByWord);
  cmpq(in1, tmp1);
  jcc(Assembler::greaterEqual, L_byteByByteProlog);
    crc32(in_out, Address(in1, 0), 4);
    addq(in1, 4);
    jmp(L_wordByWord);

  BIND(L_byteByByteProlog);
  andl(in2, 0x00000007);
  movl(tmp2, 1);

  BIND(L_byteByByte);
  cmpl(tmp2, in2);
  jccb(Assembler::greater, L_exit);
    crc32(in_out, Address(in1, 0), 1);
    incq(in1);
    incl(tmp2);
    jmp(L_byteByByte);

  BIND(L_exit);
}
#else
void MacroAssembler::crc32c_ipl_alg2_alt2(Register in_out, Register in1, Register in2,
                                          Register tmp1, Register  tmp2, Register tmp3,
                                          Register tmp4, Register  tmp5, Register tmp6,
                                          XMMRegister w_xtmp1, XMMRegister w_xtmp2, XMMRegister w_xtmp3,
                                          bool is_pclmulqdq_supported) {
  uint32_t const_or_pre_comp_const_index[CRC32C_NUM_PRECOMPUTED_CONSTANTS];
  Label L_wordByWord;
  Label L_byteByByteProlog;
  Label L_byteByByte;
  Label L_exit;

  if (is_pclmulqdq_supported) {
    const_or_pre_comp_const_index[1] = *(uint32_t *)StubRoutines::_crc32c_table_addr;
    const_or_pre_comp_const_index[0] = *((uint32_t *)StubRoutines::_crc32c_table_addr + 1);

    const_or_pre_comp_const_index[3] = *((uint32_t *)StubRoutines::_crc32c_table_addr + 2);
    const_or_pre_comp_const_index[2] = *((uint32_t *)StubRoutines::_crc32c_table_addr + 3);

    const_or_pre_comp_const_index[5] = *((uint32_t *)StubRoutines::_crc32c_table_addr + 4);
    const_or_pre_comp_const_index[4] = *((uint32_t *)StubRoutines::_crc32c_table_addr + 5);
  } else {
    const_or_pre_comp_const_index[0] = 1;
    const_or_pre_comp_const_index[1] = 0;

    const_or_pre_comp_const_index[2] = 3;
    const_or_pre_comp_const_index[3] = 2;

    const_or_pre_comp_const_index[4] = 5;
    const_or_pre_comp_const_index[5] = 4;
  }
  crc32c_proc_chunk(CRC32C_HIGH, const_or_pre_comp_const_index[0], const_or_pre_comp_const_index[1], is_pclmulqdq_supported,
                    in2, in1, in_out,
                    tmp1, tmp2, tmp3,
                    w_xtmp1, w_xtmp2, w_xtmp3,
                    tmp4, tmp5,
                    tmp6);
  crc32c_proc_chunk(CRC32C_MIDDLE, const_or_pre_comp_const_index[2], const_or_pre_comp_const_index[3], is_pclmulqdq_supported,
                    in2, in1, in_out,
                    tmp1, tmp2, tmp3,
                    w_xtmp1, w_xtmp2, w_xtmp3,
                    tmp4, tmp5,
                    tmp6);
  crc32c_proc_chunk(CRC32C_LOW, const_or_pre_comp_const_index[4], const_or_pre_comp_const_index[5], is_pclmulqdq_supported,
                    in2, in1, in_out,
                    tmp1, tmp2, tmp3,
                    w_xtmp1, w_xtmp2, w_xtmp3,
                    tmp4, tmp5,
                    tmp6);
  movl(tmp1, in2);
  andl(tmp1, 0x00000007);
  negl(tmp1);
  addl(tmp1, in2);
  addl(tmp1, in1);

  BIND(L_wordByWord);
  cmpl(in1, tmp1);
  jcc(Assembler::greaterEqual, L_byteByByteProlog);
    crc32(in_out, Address(in1,0), 4);
    addl(in1, 4);
    jmp(L_wordByWord);

  BIND(L_byteByByteProlog);
  andl(in2, 0x00000007);
  movl(tmp2, 1);

  BIND(L_byteByByte);
  cmpl(tmp2, in2);
  jccb(Assembler::greater, L_exit);
    movb(tmp1, Address(in1, 0));
    crc32(in_out, tmp1, 1);
    incl(in1);
    incl(tmp2);
    jmp(L_byteByByte);

  BIND(L_exit);
}
#endif // LP64
#undef BIND
#undef BLOCK_COMMENT

// Compress char[] array to byte[].
//   ..\jdk\src\java.base\share\classes\java\lang\StringUTF16.java
//   @HotSpotIntrinsicCandidate
//   private static int compress(char[] src, int srcOff, byte[] dst, int dstOff, int len) {
//     for (int i = 0; i < len; i++) {
//       int c = src[srcOff++];
//       if (c >>> 8 != 0) {
//         return 0;
//       }
//       dst[dstOff++] = (byte)c;
//     }
//     return len;
//   }
void MacroAssembler::char_array_compress(Register src, Register dst, Register len,
  XMMRegister tmp1Reg, XMMRegister tmp2Reg,
  XMMRegister tmp3Reg, XMMRegister tmp4Reg,
  Register tmp5, Register result) {
  Label copy_chars_loop, return_length, return_zero, done;

  // rsi: src
  // rdi: dst
  // rdx: len
  // rcx: tmp5
  // rax: result

  // rsi holds start addr of source char[] to be compressed
  // rdi holds start addr of destination byte[]
  // rdx holds length

  assert(len != result, "");

  // save length for return
  push(len);

  if ((AVX3Threshold == 0) && (UseAVX > 2) && // AVX512
    VM_Version::supports_avx512vlbw() &&
    VM_Version::supports_bmi2()) {

    Label copy_32_loop, copy_loop_tail, below_threshold;

    // alignment
    Label post_alignment;

    // if length of the string is less than 16, handle it in an old fashioned way
    testl(len, -32);
    jcc(Assembler::zero, below_threshold);

    // First check whether a character is compressable ( <= 0xFF).
    // Create mask to test for Unicode chars inside zmm vector
    movl(result, 0x00FF);
    evpbroadcastw(tmp2Reg, result, Assembler::AVX_512bit);

    testl(len, -64);
    jcc(Assembler::zero, post_alignment);

    movl(tmp5, dst);
    andl(tmp5, (32 - 1));
    negl(tmp5);
    andl(tmp5, (32 - 1));

    // bail out when there is nothing to be done
    testl(tmp5, 0xFFFFFFFF);
    jcc(Assembler::zero, post_alignment);

    // ~(~0 << len), where len is the # of remaining elements to process
    movl(result, 0xFFFFFFFF);
    shlxl(result, result, tmp5);
    notl(result);
    kmovdl(k3, result);

    evmovdquw(tmp1Reg, k3, Address(src, 0), Assembler::AVX_512bit);
    evpcmpuw(k2, k3, tmp1Reg, tmp2Reg, Assembler::le, Assembler::AVX_512bit);
    ktestd(k2, k3);
    jcc(Assembler::carryClear, return_zero);

    evpmovwb(Address(dst, 0), k3, tmp1Reg, Assembler::AVX_512bit);

    addptr(src, tmp5);
    addptr(src, tmp5);
    addptr(dst, tmp5);
    subl(len, tmp5);

    bind(post_alignment);
    // end of alignment

    movl(tmp5, len);
    andl(tmp5, (32 - 1));    // tail count (in chars)
    andl(len, ~(32 - 1));    // vector count (in chars)
    jcc(Assembler::zero, copy_loop_tail);

    lea(src, Address(src, len, Address::times_2));
    lea(dst, Address(dst, len, Address::times_1));
    negptr(len);

    bind(copy_32_loop);
    evmovdquw(tmp1Reg, Address(src, len, Address::times_2), Assembler::AVX_512bit);
    evpcmpuw(k2, tmp1Reg, tmp2Reg, Assembler::le, Assembler::AVX_512bit);
    kortestdl(k2, k2);
    jcc(Assembler::carryClear, return_zero);

    // All elements in current processed chunk are valid candidates for
    // compression. Write a truncated byte elements to the memory.
    evpmovwb(Address(dst, len, Address::times_1), tmp1Reg, Assembler::AVX_512bit);
    addptr(len, 32);
    jcc(Assembler::notZero, copy_32_loop);

    bind(copy_loop_tail);
    // bail out when there is nothing to be done
    testl(tmp5, 0xFFFFFFFF);
    jcc(Assembler::zero, return_length);

    movl(len, tmp5);

    // ~(~0 << len), where len is the # of remaining elements to process
    movl(result, 0xFFFFFFFF);
    shlxl(result, result, len);
    notl(result);

    kmovdl(k3, result);

    evmovdquw(tmp1Reg, k3, Address(src, 0), Assembler::AVX_512bit);
    evpcmpuw(k2, k3, tmp1Reg, tmp2Reg, Assembler::le, Assembler::AVX_512bit);
    ktestd(k2, k3);
    jcc(Assembler::carryClear, return_zero);

    evpmovwb(Address(dst, 0), k3, tmp1Reg, Assembler::AVX_512bit);
    jmp(return_length);

    bind(below_threshold);
  }

  if (UseSSE42Intrinsics) {
    Label copy_32_loop, copy_16, copy_tail;

    movl(result, len);

    movl(tmp5, 0xff00ff00);   // create mask to test for Unicode chars in vectors

    // vectored compression
    andl(len, 0xfffffff0);    // vector count (in chars)
    andl(result, 0x0000000f);    // tail count (in chars)
    testl(len, len);
    jccb(Assembler::zero, copy_16);

    // compress 16 chars per iter
    movdl(tmp1Reg, tmp5);
    pshufd(tmp1Reg, tmp1Reg, 0);   // store Unicode mask in tmp1Reg
    pxor(tmp4Reg, tmp4Reg);

    lea(src, Address(src, len, Address::times_2));
    lea(dst, Address(dst, len, Address::times_1));
    negptr(len);

    bind(copy_32_loop);
    movdqu(tmp2Reg, Address(src, len, Address::times_2));     // load 1st 8 characters
    por(tmp4Reg, tmp2Reg);
    movdqu(tmp3Reg, Address(src, len, Address::times_2, 16)); // load next 8 characters
    por(tmp4Reg, tmp3Reg);
    ptest(tmp4Reg, tmp1Reg);       // check for Unicode chars in next vector
    jcc(Assembler::notZero, return_zero);
    packuswb(tmp2Reg, tmp3Reg);    // only ASCII chars; compress each to 1 byte
    movdqu(Address(dst, len, Address::times_1), tmp2Reg);
    addptr(len, 16);
    jcc(Assembler::notZero, copy_32_loop);

    // compress next vector of 8 chars (if any)
    bind(copy_16);
    movl(len, result);
    andl(len, 0xfffffff8);    // vector count (in chars)
    andl(result, 0x00000007);    // tail count (in chars)
    testl(len, len);
    jccb(Assembler::zero, copy_tail);

    movdl(tmp1Reg, tmp5);
    pshufd(tmp1Reg, tmp1Reg, 0);   // store Unicode mask in tmp1Reg
    pxor(tmp3Reg, tmp3Reg);

    movdqu(tmp2Reg, Address(src, 0));
    ptest(tmp2Reg, tmp1Reg);       // check for Unicode chars in vector
    jccb(Assembler::notZero, return_zero);
    packuswb(tmp2Reg, tmp3Reg);    // only LATIN1 chars; compress each to 1 byte
    movq(Address(dst, 0), tmp2Reg);
    addptr(src, 16);
    addptr(dst, 8);

    bind(copy_tail);
    movl(len, result);
  }
  // compress 1 char per iter
  testl(len, len);
  jccb(Assembler::zero, return_length);
  lea(src, Address(src, len, Address::times_2));
  lea(dst, Address(dst, len, Address::times_1));
  negptr(len);

  bind(copy_chars_loop);
  load_unsigned_short(result, Address(src, len, Address::times_2));
  testl(result, 0xff00);      // check if Unicode char
  jccb(Assembler::notZero, return_zero);
  movb(Address(dst, len, Address::times_1), result);  // ASCII char; compress to 1 byte
  increment(len);
  jcc(Assembler::notZero, copy_chars_loop);

  // if compression succeeded, return length
  bind(return_length);
  pop(result);
  jmpb(done);

  // if compression failed, return 0
  bind(return_zero);
  xorl(result, result);
  addptr(rsp, wordSize);

  bind(done);
}

// Inflate byte[] array to char[].
//   ..\jdk\src\java.base\share\classes\java\lang\StringLatin1.java
//   @HotSpotIntrinsicCandidate
//   private static void inflate(byte[] src, int srcOff, char[] dst, int dstOff, int len) {
//     for (int i = 0; i < len; i++) {
//       dst[dstOff++] = (char)(src[srcOff++] & 0xff);
//     }
//   }
void MacroAssembler::byte_array_inflate(Register src, Register dst, Register len,
  XMMRegister tmp1, Register tmp2) {
  Label copy_chars_loop, done, below_threshold, avx3_threshold;
  // rsi: src
  // rdi: dst
  // rdx: len
  // rcx: tmp2

  // rsi holds start addr of source byte[] to be inflated
  // rdi holds start addr of destination char[]
  // rdx holds length
  assert_different_registers(src, dst, len, tmp2);
  movl(tmp2, len);
  if ((UseAVX > 2) && // AVX512
    VM_Version::supports_avx512vlbw() &&
    VM_Version::supports_bmi2()) {

    Label copy_32_loop, copy_tail;
    Register tmp3_aliased = len;

    // if length of the string is less than 16, handle it in an old fashioned way
    testl(len, -16);
    jcc(Assembler::zero, below_threshold);

    testl(len, -1 * AVX3Threshold);
    jcc(Assembler::zero, avx3_threshold);

    // In order to use only one arithmetic operation for the main loop we use
    // this pre-calculation
    andl(tmp2, (32 - 1)); // tail count (in chars), 32 element wide loop
    andl(len, -32);     // vector count
    jccb(Assembler::zero, copy_tail);

    lea(src, Address(src, len, Address::times_1));
    lea(dst, Address(dst, len, Address::times_2));
    negptr(len);


    // inflate 32 chars per iter
    bind(copy_32_loop);
    vpmovzxbw(tmp1, Address(src, len, Address::times_1), Assembler::AVX_512bit);
    evmovdquw(Address(dst, len, Address::times_2), tmp1, Assembler::AVX_512bit);
    addptr(len, 32);
    jcc(Assembler::notZero, copy_32_loop);

    bind(copy_tail);
    // bail out when there is nothing to be done
    testl(tmp2, -1); // we don't destroy the contents of tmp2 here
    jcc(Assembler::zero, done);

    // ~(~0 << length), where length is the # of remaining elements to process
    movl(tmp3_aliased, -1);
    shlxl(tmp3_aliased, tmp3_aliased, tmp2);
    notl(tmp3_aliased);
    kmovdl(k2, tmp3_aliased);
    evpmovzxbw(tmp1, k2, Address(src, 0), Assembler::AVX_512bit);
    evmovdquw(Address(dst, 0), k2, tmp1, Assembler::AVX_512bit);

    jmp(done);
    bind(avx3_threshold);
  }
  if (UseSSE42Intrinsics) {
    Label copy_16_loop, copy_8_loop, copy_bytes, copy_new_tail, copy_tail;

    if (UseAVX > 1) {
      andl(tmp2, (16 - 1));
      andl(len, -16);
      jccb(Assembler::zero, copy_new_tail);
    } else {
      andl(tmp2, 0x00000007);   // tail count (in chars)
      andl(len, 0xfffffff8);    // vector count (in chars)
      jccb(Assembler::zero, copy_tail);
    }

    // vectored inflation
    lea(src, Address(src, len, Address::times_1));
    lea(dst, Address(dst, len, Address::times_2));
    negptr(len);

    if (UseAVX > 1) {
      bind(copy_16_loop);
      vpmovzxbw(tmp1, Address(src, len, Address::times_1), Assembler::AVX_256bit);
      vmovdqu(Address(dst, len, Address::times_2), tmp1);
      addptr(len, 16);
      jcc(Assembler::notZero, copy_16_loop);

      bind(below_threshold);
      bind(copy_new_tail);
      movl(len, tmp2);
      andl(tmp2, 0x00000007);
      andl(len, 0xFFFFFFF8);
      jccb(Assembler::zero, copy_tail);

      pmovzxbw(tmp1, Address(src, 0));
      movdqu(Address(dst, 0), tmp1);
      addptr(src, 8);
      addptr(dst, 2 * 8);

      jmp(copy_tail, true);
    }

    // inflate 8 chars per iter
    bind(copy_8_loop);
    pmovzxbw(tmp1, Address(src, len, Address::times_1));  // unpack to 8 words
    movdqu(Address(dst, len, Address::times_2), tmp1);
    addptr(len, 8);
    jcc(Assembler::notZero, copy_8_loop);

    bind(copy_tail);
    movl(len, tmp2);

    cmpl(len, 4);
    jccb(Assembler::less, copy_bytes);

    movdl(tmp1, Address(src, 0));  // load 4 byte chars
    pmovzxbw(tmp1, tmp1);
    movq(Address(dst, 0), tmp1);
    subptr(len, 4);
    addptr(src, 4);
    addptr(dst, 8);

    bind(copy_bytes);
  } else {
    bind(below_threshold);
  }

  testl(len, len);
  jccb(Assembler::zero, done);
  lea(src, Address(src, len, Address::times_1));
  lea(dst, Address(dst, len, Address::times_2));
  negptr(len);

  // inflate 1 char per iter
  bind(copy_chars_loop);
  load_unsigned_byte(tmp2, Address(src, len, Address::times_1));  // load byte char
  movw(Address(dst, len, Address::times_2), tmp2);  // inflate byte char to word
  increment(len);
  jcc(Assembler::notZero, copy_chars_loop);

  bind(done);
}

Assembler::Condition MacroAssembler::negate_condition(Assembler::Condition cond) {
  switch (cond) {
    // Note some conditions are synonyms for others
    case Assembler::zero:         return Assembler::notZero;
    case Assembler::notZero:      return Assembler::zero;
    case Assembler::less:         return Assembler::greaterEqual;
    case Assembler::lessEqual:    return Assembler::greater;
    case Assembler::greater:      return Assembler::lessEqual;
    case Assembler::greaterEqual: return Assembler::less;
    case Assembler::below:        return Assembler::aboveEqual;
    case Assembler::belowEqual:   return Assembler::above;
    case Assembler::above:        return Assembler::belowEqual;
    case Assembler::aboveEqual:   return Assembler::below;
    case Assembler::overflow:     return Assembler::noOverflow;
    case Assembler::noOverflow:   return Assembler::overflow;
    case Assembler::negative:     return Assembler::positive;
    case Assembler::positive:     return Assembler::negative;
    case Assembler::parity:       return Assembler::noParity;
    case Assembler::noParity:     return Assembler::parity;
  }
  ShouldNotReachHere(); return Assembler::overflow;
}

SkipIfEqual::SkipIfEqual(
    MacroAssembler* masm, const bool* flag_addr, bool value) {
  _masm = masm;
  _masm->cmp8(ExternalAddress((address)flag_addr), value);
  _masm->jcc(Assembler::equal, _label);
}

SkipIfEqual::~SkipIfEqual() {
  _masm->bind(_label);
}

// 32-bit Windows has its own fast-path implementation
// of get_thread
#if !defined(WIN32) || defined(_LP64)

// This is simply a call to Thread::current()
void MacroAssembler::get_thread(Register thread) {
  if (thread != rax) {
    push(rax);
  }
  LP64_ONLY(push(rdi);)
  LP64_ONLY(push(rsi);)
  push(rdx);
  push(rcx);
#ifdef _LP64
  push(r8);
  push(r9);
  push(r10);
  push(r11);
#endif

  MacroAssembler::call_VM_leaf_base(CAST_FROM_FN_PTR(address, Thread::current), 0);

#ifdef _LP64
  pop(r11);
  pop(r10);
  pop(r9);
  pop(r8);
#endif
  pop(rcx);
  pop(rdx);
  LP64_ONLY(pop(rsi);)
  LP64_ONLY(pop(rdi);)
  if (thread != rax) {
    mov(thread, rax);
    pop(rax);
  }
}

#endif
