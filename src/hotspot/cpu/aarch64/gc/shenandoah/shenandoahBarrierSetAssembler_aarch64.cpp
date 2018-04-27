/*
 * Copyright (c) 2018, Red Hat, Inc. and/or its affiliates.
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
#include "gc/shenandoah/shenandoahBarrierSet.hpp"
#include "gc/shenandoah/shenandoahBarrierSetAssembler.hpp"
#include "gc/shenandoah/shenandoahConnectionMatrix.hpp"
#include "gc/shenandoah/shenandoahHeapRegion.hpp"
#include "gc/shenandoah/shenandoahThreadLocalData.hpp"
#include "interpreter/interpreter.hpp"
#include "interpreter/interp_masm.hpp"
#include "runtime/sharedRuntime.hpp"
#include "runtime/thread.hpp"

#define __ masm->

void ShenandoahBarrierSetAssembler::arraycopy_prologue(MacroAssembler* masm, DecoratorSet decorators, bool is_oop,
                                                       Register addr, Register count, RegSet saved_regs) {
  if (is_oop) {
    bool dest_uninitialized = (decorators & AS_DEST_NOT_INITIALIZED) != 0;
    if (!dest_uninitialized) {
      __ push(saved_regs, sp);
      if (count == c_rarg0) {
        if (addr == c_rarg1) {
          // exactly backwards!!
          __ mov(rscratch1, c_rarg0);
          __ mov(c_rarg0, c_rarg1);
          __ mov(c_rarg1, rscratch1);
        } else {
          __ mov(c_rarg1, count);
          __ mov(c_rarg0, addr);
        }
      } else {
        __ mov(c_rarg0, addr);
        __ mov(c_rarg1, count);
      }
      if (UseCompressedOops) {
        __ call_VM_leaf(CAST_FROM_FN_PTR(address, ShenandoahBarrierSet::write_ref_array_pre_narrow_oop_entry), 2);
      } else {
        __ call_VM_leaf(CAST_FROM_FN_PTR(address, ShenandoahBarrierSet::write_ref_array_pre_oop_entry), 2);
      }
      __ pop(saved_regs, sp);
    }
  }
}

void ShenandoahBarrierSetAssembler::arraycopy_epilogue(MacroAssembler* masm, DecoratorSet decorators, bool is_oop,
                                                       Register start, Register end, Register scratch, RegSet saved_regs) {
  if (is_oop) {
    __ push(saved_regs, sp);
    // must compute element count unless barrier set interface is changed (other platforms supply count)
    assert_different_registers(start, end, scratch);
    __ lea(scratch, Address(end, BytesPerHeapOop));
    __ sub(scratch, scratch, start);               // subtract start to get #bytes
    __ lsr(scratch, scratch, LogBytesPerHeapOop);  // convert to element count
    __ mov(c_rarg0, start);
    __ mov(c_rarg1, scratch);
    __ call_VM_leaf(CAST_FROM_FN_PTR(address, ShenandoahBarrierSet::write_ref_array_post_entry), 2);
    __ pop(saved_regs, sp);
  }
}

void ShenandoahBarrierSetAssembler::shenandoah_write_barrier_pre(MacroAssembler* masm,
                                                                 Register obj,
                                                                 Register pre_val,
                                                                 Register thread,
                                                                 Register tmp,
                                                                 bool tosca_live,
                                                                 bool expand_call) {
  if (ShenandoahSATBBarrier) {
    satb_write_barrier_pre(masm, obj, pre_val, thread, tmp, tosca_live, expand_call);
  }
}

void ShenandoahBarrierSetAssembler::satb_write_barrier_pre(MacroAssembler* masm,
                                                           Register obj,
                                                           Register pre_val,
                                                           Register thread,
                                                           Register tmp,
                                                           bool tosca_live,
                                                           bool expand_call) {
  // If expand_call is true then we expand the call_VM_leaf macro
  // directly to skip generating the check by
  // InterpreterMacroAssembler::call_VM_leaf_base that checks _last_sp.

  assert(thread == rthread, "must be");

  Label done;
  Label runtime;

  assert_different_registers(obj, pre_val, tmp, rscratch1);
  assert(pre_val != noreg &&  tmp != noreg, "expecting a register");

  Address in_progress(thread, in_bytes(ShenandoahThreadLocalData::satb_mark_queue_active_offset()));
  Address index(thread, in_bytes(ShenandoahThreadLocalData::satb_mark_queue_index_offset()));
  Address buffer(thread, in_bytes(ShenandoahThreadLocalData::satb_mark_queue_buffer_offset()));

  // Is marking active?
  if (in_bytes(SATBMarkQueue::byte_width_of_active()) == 4) {
    __ ldrw(tmp, in_progress);
  } else {
    assert(in_bytes(SATBMarkQueue::byte_width_of_active()) == 1, "Assumption");
    __ ldrb(tmp, in_progress);
  }
  __ cbzw(tmp, done);

  // Do we need to load the previous value?
  if (obj != noreg) {
    __ load_heap_oop(pre_val, Address(obj, 0));
  }

  // Is the previous value null?
  __ cbz(pre_val, done);

  // Can we store original value in the thread's buffer?
  // Is index == 0?
  // (The index field is typed as size_t.)

  __ ldr(tmp, index);                      // tmp := *index_adr
  __ cbz(tmp, runtime);                    // tmp == 0?
                                        // If yes, goto runtime

  __ sub(tmp, tmp, wordSize);              // tmp := tmp - wordSize
  __ str(tmp, index);                      // *index_adr := tmp
  __ ldr(rscratch1, buffer);
  __ add(tmp, tmp, rscratch1);             // tmp := tmp + *buffer_adr

  // Record the previous value
  __ str(pre_val, Address(tmp, 0));
  __ b(done);

  __ bind(runtime);
  // save the live input values
  RegSet saved = RegSet::of(pre_val);
  if (tosca_live) saved += RegSet::of(r0);
  if (obj != noreg) saved += RegSet::of(obj);

  __ push(saved, sp);

  // Calling the runtime using the regular call_VM_leaf mechanism generates
  // code (generated by InterpreterMacroAssember::call_VM_leaf_base)
  // that checks that the *(rfp+frame::interpreter_frame_last_sp) == NULL.
  //
  // If we care generating the pre-barrier without a frame (e.g. in the
  // intrinsified Reference.get() routine) then ebp might be pointing to
  // the caller frame and so this check will most likely fail at runtime.
  //
  // Expanding the call directly bypasses the generation of the check.
  // So when we do not have have a full interpreter frame on the stack
  // expand_call should be passed true.

  if (expand_call) {
    assert(pre_val != c_rarg1, "smashed arg");
    __ super_call_VM_leaf(CAST_FROM_FN_PTR(address, SharedRuntime::g1_wb_pre), pre_val, thread);
  } else {
    __ call_VM_leaf(CAST_FROM_FN_PTR(address, SharedRuntime::g1_wb_pre), pre_val, thread);
  }

  __ pop(saved, sp);

  __ bind(done);
}

void ShenandoahBarrierSetAssembler::shenandoah_write_barrier_post(MacroAssembler* masm,
                                                                  Register store_addr,
                                                                  Register new_val,
                                                                  Register thread,
                                                                  Register tmp,
                                                                  Register tmp2) {
  assert(thread == rthread, "must be");
  assert(UseShenandoahGC, "expect Shenandoah GC");

  if (! UseShenandoahMatrix) {
    // No need for that barrier if not using matrix.
    return;
  }

  assert_different_registers(store_addr, new_val, thread, tmp, tmp2, rscratch1);

  Label done;
  __ cbz(new_val, done);

  ShenandoahConnectionMatrix* matrix = ShenandoahHeap::heap()->connection_matrix();

  // Compute to-region index
  __ lsr(tmp, new_val, ShenandoahHeapRegion::region_size_bytes_shift_jint());

  // Compute from-region index
  __ lsr(tmp2, store_addr, ShenandoahHeapRegion::region_size_bytes_shift_jint());

  // Compute matrix index
  __ mov(rscratch1, matrix->stride_jint());
  // Address is _matrix[to * stride + from]
  __ madd(tmp, tmp, rscratch1, tmp2);
  __ mov(rscratch1, matrix->magic_offset());
  Address loc(tmp, rscratch1);

  __ ldrb(tmp2, loc);
  __ cbnz(tmp2, done);
  __ mov(tmp2, 1);
  __ strb(tmp2, loc);
  __ bind(done);
}

void ShenandoahBarrierSetAssembler::read_barrier(MacroAssembler* masm, Register dst) {
  if (ShenandoahReadBarrier) {
    read_barrier_impl(masm, dst);
  }
}

void ShenandoahBarrierSetAssembler::read_barrier_impl(MacroAssembler* masm, Register dst) {
  assert(UseShenandoahGC && (ShenandoahReadBarrier || ShenandoahStoreValReadBarrier), "should be enabled");
  Label is_null;
  __ cbz(dst, is_null);
  read_barrier_not_null_impl(masm, dst);
  __ bind(is_null);
}

void ShenandoahBarrierSetAssembler::read_barrier_not_null(MacroAssembler* masm, Register dst) {
  if (ShenandoahReadBarrier) {
    read_barrier_not_null_impl(masm, dst);
  }
}


void ShenandoahBarrierSetAssembler::read_barrier_not_null_impl(MacroAssembler* masm, Register dst) {
  assert(UseShenandoahGC && (ShenandoahReadBarrier || ShenandoahStoreValReadBarrier), "should be enabled");
  __ ldr(dst, Address(dst, BrooksPointer::byte_offset()));
}

void ShenandoahBarrierSetAssembler::write_barrier(MacroAssembler* masm, Register dst) {
  if (ShenandoahWriteBarrier) {
    write_barrier_impl(masm, dst);
  }
}

void ShenandoahBarrierSetAssembler::write_barrier_impl(MacroAssembler* masm, Register dst) {
  assert(UseShenandoahGC && (ShenandoahWriteBarrier || ShenandoahStoreValEnqueueBarrier), "should be enabled");
  assert(dst != rscratch1, "different regs");
  assert(dst != rscratch2, "Need rscratch2");

  Label done;

  Address gc_state(rthread, in_bytes(ShenandoahThreadLocalData::gc_state_offset()));
  __ ldrb(rscratch1, gc_state);
  __ membar(Assembler::LoadLoad);

  // Now check if evacuation is in progress.
  read_barrier_not_null(masm, dst);

  __ mov(rscratch2, ShenandoahHeap::EVACUATION | ShenandoahHeap::TRAVERSAL);
  __ tst(rscratch1, rscratch2);
  __ br(Assembler::EQ, done);

  __ lsr(rscratch1, dst, ShenandoahHeapRegion::region_size_bytes_shift_jint());
  __ mov(rscratch2,  ShenandoahHeap::in_cset_fast_test_addr());
  __ ldrb(rscratch2, Address(rscratch2, rscratch1));
  __ tst(rscratch2, 0x1);
  __ br(Assembler::EQ, done);

  // Save possibly live regs.
  RegSet live_regs = RegSet::range(r0, r4) - dst;
  __ push(live_regs, sp);
  __ strd(v0, __ pre(sp, 2 * -wordSize));

  // Call into runtime
  __ super_call_VM_leaf(CAST_FROM_FN_PTR(address, ShenandoahBarrierSet::write_barrier_IRT), dst);

  // Move result into dst reg.
  __ mov(dst, r0);

  // Restore possibly live regs.
  __ ldrd(v0, __ post(sp, 2 * wordSize));
  __ pop(live_regs, sp);

  __ bind(done);
}

void ShenandoahBarrierSetAssembler::storeval_barrier(MacroAssembler* masm, Register dst, Register tmp) {
  if (ShenandoahStoreValEnqueueBarrier) {
    Label is_null;
    __ cbz(dst, is_null);
    write_barrier_impl(masm, dst);
    __ bind(is_null);
    // Save possibly live regs.
    RegSet live_regs = RegSet::range(r0, r4) - dst;
    __ push(live_regs, sp);
    __ strd(v0, __ pre(sp, 2 * -wordSize));

    satb_write_barrier_pre(masm, noreg, dst, rthread, tmp, true, false);

    // Restore possibly live regs.
    __ ldrd(v0, __ post(sp, 2 * wordSize));
    __ pop(live_regs, sp);
  }
  if (ShenandoahStoreValReadBarrier) {
    read_barrier_impl(masm, dst);
  }
}

void ShenandoahBarrierSetAssembler::load_at(MacroAssembler* masm, DecoratorSet decorators, BasicType type,
                                            Register dst, Address src, Register tmp1, Register tmp_thread) {
  bool on_oop = type == T_OBJECT || type == T_ARRAY;
  bool in_heap = (decorators & IN_HEAP) != 0;
  bool on_weak = (decorators & ON_WEAK_OOP_REF) != 0;
  bool on_phantom = (decorators & ON_PHANTOM_OOP_REF) != 0;
  bool on_reference = on_weak || on_phantom;
  BarrierSetAssembler::load_at(masm, decorators, type, dst, src, tmp1, tmp_thread);
  if (ShenandoahKeepAliveBarrier && on_oop && on_reference) {
    satb_write_barrier_pre(masm /* masm */,
                           noreg /* obj */,
                           dst /* pre_val */,
                           rthread /* thread */,
                           tmp1 /* tmp */,
                           true /* tosca_live */,
                           true /* expand_call */);
  }
}

void ShenandoahBarrierSetAssembler::store_at(MacroAssembler* masm, DecoratorSet decorators, BasicType type,
                                             Address dst, Register val, Register tmp1, Register tmp2) {
  bool on_oop = type == T_OBJECT || type == T_ARRAY;
  if (!on_oop) {
    BarrierSetAssembler::store_at(masm, decorators, type, dst, val, tmp1, tmp2);
    return;
  }

  // flatten object address if needed
  if (dst.index() == noreg && dst.offset() == 0) {
    if (dst.base() != r3) {
      __ mov(r3, dst.base());
    }
  } else {
    __ lea(r3, dst);
  }

  shenandoah_write_barrier_pre(masm,
                               r3 /* obj */,
                               tmp2 /* pre_val */,
                               rthread /* thread */,
                               tmp1  /* tmp */,
                               val != noreg /* tosca_live */,
                               false /* expand_call */);

  if (val == noreg) {
    __ store_heap_oop_null(Address(r3, 0));
  } else {
    storeval_barrier(masm, val, tmp1);
    // G1 barrier needs uncompressed oop for region cross check.
    Register new_val = val;
    if (UseCompressedOops) {
      new_val = rscratch2;
      __ mov(new_val, val);
    }
    __ store_heap_oop(Address(r3, 0), val);
    shenandoah_write_barrier_post(masm,
                                  r3 /* store_adr */,
                                  new_val /* new_val */,
                                  rthread /* thread */,
                                  tmp1 /* tmp */,
                                  tmp2 /* tmp2 */);
  }

}

void ShenandoahBarrierSetAssembler::obj_equals(MacroAssembler* masm, DecoratorSet decorators, Register op1, Register op2) {
  __ cmp(op1, op2);
  if (ShenandoahAcmpBarrier) {
    Label done;
    __ br(Assembler::EQ, done);
    // The object may have been evacuated, but we won't see it without a
    // membar here.
    __ membar(Assembler::LoadStore| Assembler::LoadLoad);
    read_barrier(masm, op1);
    read_barrier(masm, op2);
    __ cmp(op1, op2);
    __ bind(done);
  }
}

void ShenandoahBarrierSetAssembler::resolve_for_read(MacroAssembler* masm, DecoratorSet decorators, Register obj) {
  bool oop_not_null = (decorators & OOP_NOT_NULL) != 0;
  if (oop_not_null) {
    read_barrier_not_null(masm, obj);
  } else {
    read_barrier(masm, obj);
  }
}

void ShenandoahBarrierSetAssembler::resolve_for_write(MacroAssembler* masm, DecoratorSet decorators, Register obj) {
  write_barrier(masm, obj);
}
