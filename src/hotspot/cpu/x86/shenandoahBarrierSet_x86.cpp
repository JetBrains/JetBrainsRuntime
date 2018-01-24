/*
 * Copyright (c) 2015, Red Hat, Inc. and/or its affiliates.
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
#include "gc/shenandoah/brooksPointer.hpp"
#include "gc/shenandoah/shenandoahBarrierSet.inline.hpp"

#include "asm/macroAssembler.hpp"
#include "interpreter/interpreter.hpp"

#define __ masm->

#ifndef CC_INTERP

void ShenandoahBarrierSet::interpreter_read_barrier(MacroAssembler* masm, Register dst) {
  if (ShenandoahReadBarrier) {
    interpreter_read_barrier_impl(masm, dst);
  }
}

void ShenandoahBarrierSet::interpreter_read_barrier_impl(MacroAssembler* masm, Register dst) {
  assert(UseShenandoahGC && (ShenandoahReadBarrier || ShenandoahStoreValReadBarrier), "should be enabled");
  Label is_null;
  __ testptr(dst, dst);
  __ jcc(Assembler::zero, is_null);
  interpreter_read_barrier_not_null_impl(masm, dst);
  __ bind(is_null);
}

void ShenandoahBarrierSet::interpreter_read_barrier_not_null(MacroAssembler* masm, Register dst) {
  if (ShenandoahReadBarrier) {
    interpreter_read_barrier_not_null_impl(masm, dst);
  }
}

void ShenandoahBarrierSet::interpreter_read_barrier_not_null_impl(MacroAssembler* masm, Register dst) {
  assert(UseShenandoahGC && (ShenandoahReadBarrier || ShenandoahStoreValReadBarrier), "should be enabled");
  __ movptr(dst, Address(dst, BrooksPointer::byte_offset()));
}

void ShenandoahBarrierSet::interpreter_write_barrier(MacroAssembler* masm, Register dst) {
  if (ShenandoahWriteBarrier) {
    interpreter_write_barrier_impl(masm, dst);
  }
}

void ShenandoahBarrierSet::interpreter_write_barrier_impl(MacroAssembler* masm, Register dst) {
  assert(UseShenandoahGC && (ShenandoahWriteBarrier || ShenandoahStoreValWriteBarrier || ShenandoahStoreValEnqueueBarrier), "should be enabled");
#ifdef _LP64
  assert(dst != rscratch1, "different regs");

  Label done;

  Address gc_state(r15_thread, in_bytes(JavaThread::gc_state_offset()));
  __ testb(gc_state, ShenandoahHeap::EVACUATION);

  // Now check if evacuation is in progress.
  interpreter_read_barrier_not_null(masm, dst);

  __ jcc(Assembler::zero, done);
  __ push(rscratch1);
  __ push(rscratch2);

  __ movptr(rscratch1, dst);
  __ shrptr(rscratch1, ShenandoahHeapRegion::region_size_bytes_shift_jint());
  __ movptr(rscratch2, (intptr_t) ShenandoahHeap::in_cset_fast_test_addr());
  __ movbool(rscratch2, Address(rscratch2, rscratch1, Address::times_1));
  __ testb(rscratch2, 0x1);

  __ pop(rscratch2);
  __ pop(rscratch1);

  __ jcc(Assembler::zero, done);

  __ push(rscratch1);

  // Save possibly live regs.
  if (dst != rax) {
    __ push(rax);
  }
  if (dst != rbx) {
    __ push(rbx);
  }
  if (dst != rcx) {
    __ push(rcx);
  }
  if (dst != rdx) {
    __ push(rdx);
  }
  if (dst != c_rarg1) {
    __ push(c_rarg1);
  }

  __ subptr(rsp, 2 * wordSize);
  __ movdbl(Address(rsp, 0), xmm0);

  // Call into runtime
  __ super_call_VM_leaf(CAST_FROM_FN_PTR(address, ShenandoahBarrierSet::write_barrier_IRT), dst);
  __ mov(rscratch1, rax);

  // Restore possibly live regs.
  __ movdbl(xmm0, Address(rsp, 0));
  __ addptr(rsp, 2 * Interpreter::stackElementSize);

  if (dst != c_rarg1) {
    __ pop(c_rarg1);
  }
  if (dst != rdx) {
    __ pop(rdx);
  }
  if (dst != rcx) {
    __ pop(rcx);
  }
  if (dst != rbx) {
    __ pop(rbx);
  }
  if (dst != rax) {
    __ pop(rax);
  }

  // Move result into dst reg.
  __ mov(dst, rscratch1);

  __ pop(rscratch1);

  __ bind(done);
#else
  Unimplemented();
#endif
}

void ShenandoahBarrierSet::interpreter_storeval_barrier(MacroAssembler* masm, Register dst, Register tmp, Register thread) {
  if (ShenandoahStoreValWriteBarrier || ShenandoahStoreValEnqueueBarrier) {
    Label is_null;
    __ testptr(dst, dst);
    __ jcc(Assembler::zero, is_null);
    interpreter_write_barrier_impl(masm, dst);
    __ bind(is_null);
  }

  if (ShenandoahStoreValEnqueueBarrier) {
    // The set of registers to be saved+restored is the same as in the write-barrier above.
    // Those are the commonly used registers in the interpreter.
    __ push(rbx);
    __ push(rcx);
    __ push(rdx);
    __ push(c_rarg1);
    __ subptr(rsp, 2 * Interpreter::stackElementSize);
    __ movdbl(Address(rsp, 0), xmm0);

    __ g1_write_barrier_pre(noreg, dst, r15_thread, tmp, true, false);
    __ movdbl(xmm0, Address(rsp, 0));
    __ addptr(rsp, 2 * Interpreter::stackElementSize);
    __ pop(c_rarg1);
    __ pop(rdx);
    __ pop(rcx);
    __ pop(rbx);
  }
  if (ShenandoahStoreValReadBarrier) {
    interpreter_read_barrier_impl(masm, dst);
  }
}

void ShenandoahBarrierSet::asm_acmp_barrier(MacroAssembler* masm, Register op1, Register op2) {
  if (ShenandoahAcmpBarrier) {
    Label done;
    __ jccb(Assembler::equal, done);
    interpreter_read_barrier(masm, op1);
    interpreter_read_barrier(masm, op2);
    __ cmpptr(op1, op2);
    __ bind(done);
  }
}

void ShenandoahHeap::compile_prepare_oop(MacroAssembler* masm, Register obj) {
#ifdef _LP64
  __ incrementq(obj, BrooksPointer::byte_size());
#else
  __ incrementl(obj, BrooksPointer::byte_size());
#endif
  __ movptr(Address(obj, BrooksPointer::byte_offset()), obj);
}
#endif
