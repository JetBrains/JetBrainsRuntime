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
#include "gc/shenandoah/shenandoahBarrierSetAssembler.hpp"
#include "gc/shenandoah/shenandoahConnectionMatrix.hpp"
#include "gc/shenandoah/shenandoahHeap.hpp"
#include "gc/shenandoah/shenandoahHeapRegion.hpp"
#include "gc/shenandoah/shenandoahHeuristics.hpp"
#include "gc/shenandoah/shenandoahRuntime.hpp"
#include "gc/shenandoah/shenandoahThreadLocalData.hpp"
#include "interpreter/interpreter.hpp"
#include "interpreter/interp_masm.hpp"
#include "runtime/sharedRuntime.hpp"
#include "runtime/thread.hpp"
#include "utilities/macros.hpp"
#ifdef COMPILER1
#include "c1/c1_LIRAssembler.hpp"
#include "c1/c1_MacroAssembler.hpp"
#include "gc/shenandoah/c1/shenandoahBarrierSetC1.hpp"
#endif

#define __ masm->

address ShenandoahBarrierSetAssembler::_shenandoah_wb = NULL;
address ShenandoahBarrierSetAssembler::_shenandoah_wb_C = NULL;

void ShenandoahBarrierSetAssembler::arraycopy_prologue(MacroAssembler* masm, DecoratorSet decorators, BasicType type,
                                                       Register src, Register dst, Register count) {

  bool checkcast = (decorators & ARRAYCOPY_CHECKCAST) != 0;
  bool disjoint = (decorators & ARRAYCOPY_DISJOINT) != 0;
  bool obj_int = type == T_OBJECT LP64_ONLY(&& UseCompressedOops);
  bool dest_uninitialized = (decorators & IS_DEST_UNINITIALIZED) != 0;

  if (type == T_OBJECT || type == T_ARRAY) {
#ifdef _LP64
    if (!checkcast && !obj_int) {
      // Save count for barrier
      __ movptr(r11, count);
    } else if (disjoint && obj_int) {
      // Save dst in r11 in the disjoint case
      __ movq(r11, dst);
    }
#else
    if (disjoint) {
      __ mov(rdx, dst);          // save 'to'
    }
#endif

    if (!dest_uninitialized && !ShenandoahHeap::heap()->heuristics()->can_do_traversal_gc()) {
      Register thread = NOT_LP64(rax) LP64_ONLY(r15_thread);
#ifndef _LP64
      __ push(thread);
      __ get_thread(thread);
#endif

      Label filtered;
      Address in_progress(thread, in_bytes(ShenandoahThreadLocalData::satb_mark_queue_active_offset()));
      // Is marking active?
      if (in_bytes(SATBMarkQueue::byte_width_of_active()) == 4) {
        __ cmpl(in_progress, 0);
      } else {
        assert(in_bytes(SATBMarkQueue::byte_width_of_active()) == 1, "Assumption");
        __ cmpb(in_progress, 0);
      }

      NOT_LP64(__ pop(thread);)

        __ jcc(Assembler::equal, filtered);

      __ pusha();                      // push registers
#ifdef _LP64
      if (count == c_rarg0) {
        if (dst == c_rarg1) {
          // exactly backwards!!
          __ xchgptr(c_rarg1, c_rarg0);
        } else {
          __ movptr(c_rarg1, count);
          __ movptr(c_rarg0, dst);
        }
      } else {
        __ movptr(c_rarg0, dst);
        __ movptr(c_rarg1, count);
      }
      if (UseCompressedOops) {
        __ call_VM_leaf(CAST_FROM_FN_PTR(address, ShenandoahRuntime::write_ref_array_pre_narrow_oop_entry), 2);
      } else {
        __ call_VM_leaf(CAST_FROM_FN_PTR(address, ShenandoahRuntime::write_ref_array_pre_oop_entry), 2);
      }
#else
      __ call_VM_leaf(CAST_FROM_FN_PTR(address, ShenandoahRuntime::write_ref_array_pre_oop_entry),
                      dst, count);
#endif
      __ popa();
      __ bind(filtered);
    }
  }

}

void ShenandoahBarrierSetAssembler::arraycopy_epilogue(MacroAssembler* masm, DecoratorSet decorators, BasicType type,
                                                       Register src, Register dst, Register count) {
  bool checkcast = (decorators & ARRAYCOPY_CHECKCAST) != 0;
  bool disjoint = (decorators & ARRAYCOPY_DISJOINT) != 0;
  bool obj_int = type == T_OBJECT LP64_ONLY(&& UseCompressedOops);
  Register tmp = rax;

  if (type == T_OBJECT || type == T_ARRAY) {
#ifdef _LP64
    if (!checkcast && !obj_int) {
      // Save count for barrier
      count = r11;
    } else if (disjoint && obj_int) {
      // Use the saved dst in the disjoint case
      dst = r11;
    } else if (checkcast) {
      tmp = rscratch1;
    }
#else
    if (disjoint) {
      __ mov(dst, rdx); // restore 'to'
    }
#endif

    __ pusha();             // push registers (overkill)
#ifdef _LP64
    if (c_rarg0 == count) { // On win64 c_rarg0 == rcx
      assert_different_registers(c_rarg1, dst);
      __ mov(c_rarg1, count);
      __ mov(c_rarg0, dst);
    } else {
      assert_different_registers(c_rarg0, count);
      __ mov(c_rarg0, dst);
      __ mov(c_rarg1, count);
    }
    __ call_VM_leaf(CAST_FROM_FN_PTR(address, ShenandoahRuntime::write_ref_array_post_entry), 2);
#else
    __ call_VM_leaf(CAST_FROM_FN_PTR(address, ShenandoahRuntime::write_ref_array_post_entry),
                    dst, count);
#endif
    __ popa();
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

#ifdef _LP64
  assert(thread == r15_thread, "must be");
#endif // _LP64

  Label done;
  Label runtime;

  assert(pre_val != noreg, "check this code");

  if (obj != noreg) {
    assert_different_registers(obj, pre_val, tmp);
    assert(pre_val != rax, "check this code");
  }

  Address in_progress(thread, in_bytes(ShenandoahThreadLocalData::satb_mark_queue_active_offset()));
  Address index(thread, in_bytes(ShenandoahThreadLocalData::satb_mark_queue_index_offset()));
  Address buffer(thread, in_bytes(ShenandoahThreadLocalData::satb_mark_queue_buffer_offset()));

  Address gc_state(thread, in_bytes(ShenandoahThreadLocalData::gc_state_offset()));
  __ testb(gc_state, ShenandoahHeap::MARKING | ShenandoahHeap::TRAVERSAL);
  __ jcc(Assembler::zero, done);

  // Do we need to load the previous value?
  if (obj != noreg) {
    __ load_heap_oop(pre_val, Address(obj, 0), noreg, noreg, AS_RAW);
  }

  // Is the previous value null?
  __ cmpptr(pre_val, (int32_t) NULL_WORD);
  __ jcc(Assembler::equal, done);

  // Can we store original value in the thread's buffer?
  // Is index == 0?
  // (The index field is typed as size_t.)

  __ movptr(tmp, index);                   // tmp := *index_adr
  __ cmpptr(tmp, 0);                       // tmp == 0?
  __ jcc(Assembler::equal, runtime);       // If yes, goto runtime

  __ subptr(tmp, wordSize);                // tmp := tmp - wordSize
  __ movptr(index, tmp);                   // *index_adr := tmp
  __ addptr(tmp, buffer);                  // tmp := tmp + *buffer_adr

  // Record the previous value
  __ movptr(Address(tmp, 0), pre_val);
  __ jmp(done);

  __ bind(runtime);
  // save the live input values
  if(tosca_live) __ push(rax);

  if (obj != noreg && obj != rax)
    __ push(obj);

  if (pre_val != rax)
    __ push(pre_val);

  // Calling the runtime using the regular call_VM_leaf mechanism generates
  // code (generated by InterpreterMacroAssember::call_VM_leaf_base)
  // that checks that the *(ebp+frame::interpreter_frame_last_sp) == NULL.
  //
  // If we care generating the pre-barrier without a frame (e.g. in the
  // intrinsified Reference.get() routine) then ebp might be pointing to
  // the caller frame and so this check will most likely fail at runtime.
  //
  // Expanding the call directly bypasses the generation of the check.
  // So when we do not have have a full interpreter frame on the stack
  // expand_call should be passed true.

  NOT_LP64( __ push(thread); )

#ifdef _LP64
  // We move pre_val into c_rarg0 early, in order to avoid smashing it, should
  // pre_val be c_rarg1 (where the call prologue would copy thread argument).
  // Note: this should not accidentally smash thread, because thread is always r15.
  assert(thread != c_rarg0, "smashed arg");
  if (c_rarg0 != pre_val) {
    __ mov(c_rarg0, pre_val);
  }
#endif

  if (expand_call) {
    LP64_ONLY( assert(pre_val != c_rarg1, "smashed arg"); )
#ifdef _LP64
    if (c_rarg1 != thread) {
      __ mov(c_rarg1, thread);
    }
    // Already moved pre_val into c_rarg0 above
#else
    __ push(thread);
    __ push(pre_val);
#endif
    __ MacroAssembler::call_VM_leaf_base(CAST_FROM_FN_PTR(address, ShenandoahRuntime::write_ref_field_pre_entry), 2);
  } else {
    __ call_VM_leaf(CAST_FROM_FN_PTR(address, ShenandoahRuntime::write_ref_field_pre_entry), LP64_ONLY(c_rarg0) NOT_LP64(pre_val), thread);
  }

  NOT_LP64( __ pop(thread); )

  // save the live input values
  if (pre_val != rax)
    __ pop(pre_val);

  if (obj != noreg && obj != rax)
    __ pop(obj);

  if(tosca_live) __ pop(rax);

  __ bind(done);
}

void ShenandoahBarrierSetAssembler::shenandoah_write_barrier_post(MacroAssembler* masm,
                                                                  Register store_addr,
                                                                  Register new_val,
                                                                  Register thread,
                                                                  Register tmp,
                                                                  Register tmp2) {
  assert(UseShenandoahGC, "why else should we be here?");

  if (! UseShenandoahMatrix) {
    // No need for that barrier if not using matrix.
    return;
  }

  Label done;
  __ testptr(new_val, new_val);
  __ jcc(Assembler::zero, done);
  ShenandoahConnectionMatrix* matrix = ShenandoahHeap::heap()->connection_matrix();
  address matrix_addr = matrix->matrix_addr();
  __ movptr(rscratch1, (intptr_t) ShenandoahHeap::heap()->base());
  // Compute to-region index
  __ movptr(tmp, new_val);
  __ subptr(tmp, rscratch1);
  __ shrptr(tmp, ShenandoahHeapRegion::region_size_bytes_shift_jint());
  // Compute from-region index
  __ movptr(tmp2, store_addr);
  __ subptr(tmp2, rscratch1);
  __ shrptr(tmp2, ShenandoahHeapRegion::region_size_bytes_shift_jint());
  // Compute matrix index
  __ imulptr(tmp, tmp, matrix->stride_jint());
  __ addptr(tmp, tmp2);
  // Address is _matrix[to * stride + from]
  __ movptr(rscratch1, (intptr_t) matrix_addr);
  // Test if the element is already set.
  __ cmpb(Address(rscratch1, tmp, Address::times_1), 0);
  __ jcc(Assembler::notEqual, done);
  // Store true, if not yet set.
  __ movb(Address(rscratch1, tmp, Address::times_1), 1);
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
  __ testptr(dst, dst);
  __ jcc(Assembler::zero, is_null);
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
  __ movptr(dst, Address(dst, BrooksPointer::byte_offset()));
}


void ShenandoahBarrierSetAssembler::write_barrier(MacroAssembler* masm, Register dst) {
  if (ShenandoahWriteBarrier) {
    write_barrier_impl(masm, dst);
  }
}

void ShenandoahBarrierSetAssembler::write_barrier_impl(MacroAssembler* masm, Register dst) {
  assert(UseShenandoahGC && (ShenandoahWriteBarrier || ShenandoahStoreValEnqueueBarrier), "Should be enabled");
#ifdef _LP64
  Label done;

  Address gc_state(r15_thread, in_bytes(ShenandoahThreadLocalData::gc_state_offset()));
  __ testb(gc_state, ShenandoahHeap::HAS_FORWARDED | ShenandoahHeap::EVACUATION | ShenandoahHeap::TRAVERSAL);
  __ jccb(Assembler::zero, done);

  // Heap is unstable, need to perform the read-barrier even if WB is inactive
  if (ShenandoahWriteBarrierRB) {
    read_barrier_not_null(masm, dst);
  }

  __ testb(gc_state, ShenandoahHeap::EVACUATION | ShenandoahHeap::TRAVERSAL);
  __ jccb(Assembler::zero, done);

   if (dst != rax) {
     __ xchgptr(dst, rax); // Move obj into rax and save rax into obj.
   }

   __ call(RuntimeAddress(CAST_FROM_FN_PTR(address, ShenandoahBarrierSetAssembler::shenandoah_wb())));

   if (dst != rax) {
     __ xchgptr(rax, dst); // Swap back obj with rax.
   }

  __ bind(done);
#else
  Unimplemented();
#endif
}

void ShenandoahBarrierSetAssembler::storeval_barrier(MacroAssembler* masm, Register dst, Register tmp) {
  if (ShenandoahStoreValReadBarrier || ShenandoahStoreValEnqueueBarrier) {
    storeval_barrier_impl(masm, dst, tmp);
  }
}

void ShenandoahBarrierSetAssembler::storeval_barrier_impl(MacroAssembler* masm, Register dst, Register tmp) {
  assert(UseShenandoahGC && (ShenandoahStoreValReadBarrier || ShenandoahStoreValEnqueueBarrier), "should be enabled");

  if (dst == noreg) return;

#ifdef _LP64
  if (ShenandoahStoreValEnqueueBarrier) {
    Label is_null;
    __ testptr(dst, dst);
    __ jcc(Assembler::zero, is_null);
    write_barrier_impl(masm, dst);
    __ bind(is_null);

    // The set of registers to be saved+restored is the same as in the write-barrier above.
    // Those are the commonly used registers in the interpreter.
    __ pusha();
    // __ push_callee_saved_registers();
    __ subptr(rsp, 2 * Interpreter::stackElementSize);
    __ movdbl(Address(rsp, 0), xmm0);

    satb_write_barrier_pre(masm, noreg, dst, r15_thread, tmp, true, false);
    __ movdbl(xmm0, Address(rsp, 0));
    __ addptr(rsp, 2 * Interpreter::stackElementSize);
    //__ pop_callee_saved_registers();
    __ popa();
  }
  if (ShenandoahStoreValReadBarrier) {
    read_barrier_impl(masm, dst);
  }
#else
  Unimplemented();
#endif
}

void ShenandoahBarrierSetAssembler::load_at(MacroAssembler* masm, DecoratorSet decorators, BasicType type,
             Register dst, Address src, Register tmp1, Register tmp_thread) {
  bool on_oop = type == T_OBJECT || type == T_ARRAY;
  bool in_heap = (decorators & IN_HEAP) != 0;
  bool on_weak = (decorators & ON_WEAK_OOP_REF) != 0;
  bool on_phantom = (decorators & ON_PHANTOM_OOP_REF) != 0;
  bool on_reference = on_weak || on_phantom;
  if (in_heap) {
    read_barrier_not_null(masm, src.base());
  }
  BarrierSetAssembler::load_at(masm, decorators, type, dst, src, tmp1, tmp_thread);
  if (ShenandoahKeepAliveBarrier && on_oop && on_reference) {
    const Register thread = NOT_LP64(tmp_thread) LP64_ONLY(r15_thread);
    NOT_LP64(__ get_thread(thread));

    // Generate the SATB pre-barrier code to log the value of
    // the referent field in an SATB buffer.
    shenandoah_write_barrier_pre(masm /* masm */,
                                 noreg /* obj */,
                                 dst /* pre_val */,
                                 thread /* thread */,
                                 tmp1 /* tmp */,
                                 true /* tosca_live */,
                                 true /* expand_call */);
  }
}

void ShenandoahBarrierSetAssembler::store_at(MacroAssembler* masm, DecoratorSet decorators, BasicType type,
              Address dst, Register val, Register tmp1, Register tmp2) {

  bool in_heap = (decorators & IN_HEAP) != 0;
  bool as_normal = (decorators & AS_NORMAL) != 0;
  if (in_heap) {
    write_barrier(masm, dst.base());
  }
  if (type == T_OBJECT || type == T_ARRAY) {
    bool needs_pre_barrier = as_normal;
    bool needs_post_barrier = val != noreg && in_heap && UseShenandoahMatrix;

    Register tmp3 = LP64_ONLY(r8) NOT_LP64(rsi);
    Register rthread = LP64_ONLY(r15_thread) NOT_LP64(rcx);
    // flatten object address if needed
    // We do it regardless of precise because we need the registers
    if (dst.index() == noreg && dst.disp() == 0) {
      if (dst.base() != tmp1) {
        __ movptr(tmp1, dst.base());
      }
    } else {
      __ lea(tmp1, dst);
    }

#ifndef _LP64
    InterpreterMacroAssembler *imasm = static_cast<InterpreterMacroAssembler*>(masm);
#endif

    NOT_LP64(__ get_thread(rcx));
    NOT_LP64(imasm->save_bcp());

    if (needs_pre_barrier) {
      shenandoah_write_barrier_pre(masm /*masm*/,
                                   tmp1 /* obj */,
                                   tmp2 /* pre_val */,
                                   rthread /* thread */,
                                   tmp3  /* tmp */,
                                   val != noreg /* tosca_live */,
                                   false /* expand_call */);
    }
    if (val == noreg) {
      BarrierSetAssembler::store_at(masm, decorators, type, Address(tmp1, 0), val, noreg, noreg);
    } else {
      storeval_barrier(masm, val, tmp3);
      Register new_val = val;
      if (needs_post_barrier) {
        if (UseCompressedOops) {
          new_val = tmp2;
          __ movptr(new_val, val);
        }
      }
      BarrierSetAssembler::store_at(masm, decorators, type, Address(tmp1, 0), val, noreg, noreg);

      if (needs_post_barrier) {
        shenandoah_write_barrier_post(masm /*masm*/,
                                      tmp1 /* store_adr */,
                                      new_val /* new_val */,
                                      rthread /* thread */,
                                      tmp3 /* tmp */,
                                      tmp2 /* tmp2 */);
      }
    }
    NOT_LP64(imasm->restore_bcp());

  } else {
    BarrierSetAssembler::store_at(masm, decorators, type, dst, val, tmp1, tmp2);
  }
}

#ifndef _LP64
void ShenandoahBarrierSetAssembler::obj_equals(MacroAssembler* masm,
                                               Address obj1, jobject obj2) {
  Unimplemented();
}

void ShenandoahBarrierSetAssembler::obj_equals(MacroAssembler* masm,
                                               Register obj1, jobject obj2) {
  Unimplemented();
}
#endif


void ShenandoahBarrierSetAssembler::obj_equals(MacroAssembler* masm, Register op1, Register op2) {
  __ cmpptr(op1, op2);
  if (ShenandoahAcmpBarrier) {
    Label done;
    __ jccb(Assembler::equal, done);
    read_barrier(masm, op1);
    read_barrier(masm, op2);
    __ cmpptr(op1, op2);
    __ bind(done);
  }
}

void ShenandoahBarrierSetAssembler::obj_equals(MacroAssembler* masm, Register src1, Address src2) {
  __ cmpptr(src1, src2);
  if (ShenandoahAcmpBarrier) {
    Label done;
    __ jccb(Assembler::equal, done);
    __ movptr(rscratch2, src2);
    read_barrier(masm, src1);
    read_barrier(masm, rscratch2);
    __ cmpptr(src1, rscratch2);
    __ bind(done);
  }
}

void ShenandoahBarrierSetAssembler::tlab_allocate(MacroAssembler* masm,
                                                  Register thread, Register obj,
                                                  Register var_size_in_bytes,
                                                  int con_size_in_bytes,
                                                  Register t1, Register t2,
                                                  Label& slow_case) {
  assert_different_registers(obj, t1, t2);
  assert_different_registers(obj, var_size_in_bytes, t1);
  Register end = t2;
  if (!thread->is_valid()) {
#ifdef _LP64
    thread = r15_thread;
#else
    assert(t1->is_valid(), "need temp reg");
    thread = t1;
    __ get_thread(thread);
#endif
  }

  __ verify_tlab();

  __ movptr(obj, Address(thread, JavaThread::tlab_top_offset()));
  if (var_size_in_bytes == noreg) {
    __ lea(end, Address(obj, con_size_in_bytes + BrooksPointer::byte_size()));
  } else {
    __ addptr(var_size_in_bytes, BrooksPointer::byte_size());
    __ lea(end, Address(obj, var_size_in_bytes, Address::times_1));
  }
  __ cmpptr(end, Address(thread, JavaThread::tlab_end_offset()));
  __ jcc(Assembler::above, slow_case);

  // update the tlab top pointer
  __ movptr(Address(thread, JavaThread::tlab_top_offset()), end);

  // Initialize brooks pointer
#ifdef _LP64
  __ incrementq(obj, BrooksPointer::byte_size());
#else
  __ incrementl(obj, BrooksPointer::byte_size());
#endif
  __ movptr(Address(obj, BrooksPointer::byte_offset()), obj);

  // recover var_size_in_bytes if necessary
  if (var_size_in_bytes == end) {
    __ subptr(var_size_in_bytes, obj);
  }
  __ verify_tlab();
}

void ShenandoahBarrierSetAssembler::resolve_for_read(MacroAssembler* masm, DecoratorSet decorators, Register obj) {
  bool oop_not_null = (decorators & IS_NOT_NULL) != 0;
  if (oop_not_null) {
    read_barrier_not_null(masm, obj);
  } else {
    read_barrier(masm, obj);
  }
}

void ShenandoahBarrierSetAssembler::resolve_for_write(MacroAssembler* masm, DecoratorSet decorators, Register obj) {
  write_barrier(masm, obj);
}

// Special Shenandoah CAS implementation that handles false negatives
// due to concurrent evacuation.
#ifndef _LP64
void ShenandoahBarrierSetAssembler::cmpxchg_oop(MacroAssembler* masm, DecoratorSet decorators,
                                                Register res, Address addr, Register oldval, Register newval,
                                                bool exchange, bool encode, Register tmp1, Register tmp2) {
  // Shenandoah has no 32-bit version for this.
  Unimplemented();
}
#else
void ShenandoahBarrierSetAssembler::cmpxchg_oop(MacroAssembler* masm, DecoratorSet decorators,
                                                Register res, Address addr, Register oldval, Register newval,
                                                bool exchange, bool encode, Register tmp1, Register tmp2) {

  if (!ShenandoahCASBarrier) {
    BarrierSetAssembler::cmpxchg_oop(masm, decorators, res, addr, oldval, newval, exchange, encode, tmp1, tmp2);
    return;
  }

  assert(ShenandoahCASBarrier, "Should only be used when CAS barrier is enabled");
  assert(oldval == rax, "must be in rax for implicit use in cmpxchg");

  Label retry, done;

  // Apply storeval barrier to newval.
  if (encode) {
    storeval_barrier(masm, newval, tmp1);
  }

  if (UseCompressedOops) {
    if (encode) {
      __ encode_heap_oop(oldval);
      __ mov(rscratch1, newval);
      __ encode_heap_oop(rscratch1);
      newval = rscratch1;
    }
  }

  // Remember oldval for retry logic below
  if (UseCompressedOops) {
    __ movl(tmp1, oldval);
  } else {
    __ movptr(tmp1, oldval);
  }

  // Step 1. Try to CAS with given arguments. If successful, then we are done,
  // and can safely return.
  if (os::is_MP()) __ lock();
  if (UseCompressedOops) {
    __ cmpxchgl(newval, addr);
  } else {
    __ cmpxchgptr(newval, addr);
  }
  __ jcc(Assembler::equal, done, true);

  // Step 2. CAS had failed. This may be a false negative.
  //
  // The trouble comes when we compare the to-space pointer with the from-space
  // pointer to the same object. To resolve this, it will suffice to read both
  // oldval and the value from memory through the read barriers -- this will give
  // both to-space pointers. If they mismatch, then it was a legitimate failure.
  //
  if (UseCompressedOops) {
    __ decode_heap_oop(tmp1);
  }
  __ resolve_for_read(0, tmp1);

  if (UseCompressedOops) {
    __ movl(tmp2, oldval);
    __ decode_heap_oop(tmp2);
  } else {
    __ movptr(tmp2, oldval);
  }
  __ resolve_for_read(0, tmp2);

  __ cmpptr(tmp1, tmp2);
  __ jcc(Assembler::notEqual, done, true);

  // Step 3. Try to CAS again with resolved to-space pointers.
  //
  // Corner case: it may happen that somebody stored the from-space pointer
  // to memory while we were preparing for retry. Therefore, we can fail again
  // on retry, and so need to do this in loop, always re-reading the failure
  // witness through the read barrier.
  __ bind(retry);
  if (os::is_MP()) __ lock();
  if (UseCompressedOops) {
    __ cmpxchgl(newval, addr);
  } else {
    __ cmpxchgptr(newval, addr);
  }
  __ jcc(Assembler::equal, done, true);

  if (UseCompressedOops) {
    __ movl(tmp2, oldval);
    __ decode_heap_oop(tmp2);
  } else {
    __ movptr(tmp2, oldval);
  }
  __ resolve_for_read(0, tmp2);

  __ cmpptr(tmp1, tmp2);
  __ jcc(Assembler::equal, retry, true);

  // Step 4. If we need a boolean result out of CAS, check the flag again,
  // and promote the result. Note that we handle the flag from both the CAS
  // itself and from the retry loop.
  __ bind(done);
  if (!exchange) {
    assert(res != NULL, "need result register");
    __ setb(Assembler::equal, res);
    __ movzbl(res, res);
  }
}
#endif // LP64

void ShenandoahBarrierSetAssembler::xchg_oop(MacroAssembler* masm, DecoratorSet decorators,
                                             Register obj, Address addr, Register tmp) {
  storeval_barrier(masm, obj, tmp);
  BarrierSetAssembler::xchg_oop(masm, decorators, obj, addr, tmp);
}

#ifdef COMPILER1

#undef __
#define __ ce->masm()->

void ShenandoahBarrierSetAssembler::gen_pre_barrier_stub(LIR_Assembler* ce, ShenandoahPreBarrierStub* stub) {
  ShenandoahBarrierSetC1* bs = (ShenandoahBarrierSetC1*)BarrierSet::barrier_set()->barrier_set_c1();
  // At this point we know that marking is in progress.
  // If do_load() is true then we have to emit the
  // load of the previous value; otherwise it has already
  // been loaded into _pre_val.

  __ bind(*stub->entry());
  assert(stub->pre_val()->is_register(), "Precondition.");

  Register pre_val_reg = stub->pre_val()->as_register();

  if (stub->do_load()) {
    ce->mem2reg(stub->addr(), stub->pre_val(), T_OBJECT, stub->patch_code(), stub->info(), false /*wide*/, false /*unaligned*/);
  }

  __ cmpptr(pre_val_reg, (int32_t)NULL_WORD);
  __ jcc(Assembler::equal, *stub->continuation());
  ce->store_parameter(stub->pre_val()->as_register(), 0);
  __ call(RuntimeAddress(bs->pre_barrier_c1_runtime_code_blob()->code_begin()));
  __ jmp(*stub->continuation());

}

void ShenandoahBarrierSetAssembler::gen_write_barrier_stub(LIR_Assembler* ce, ShenandoahWriteBarrierStub* stub) {
  __ bind(*stub->entry());

  Label done;
  Register obj = stub->obj()->as_register();
  Register res = stub->result()->as_register();

  if (res != obj) {
    __ mov(res, obj);
  }

  // Check for null.
  if (stub->needs_null_check()) {
    __ testptr(res, res);
    __ jcc(Assembler::zero, done);
  }

  write_barrier(ce->masm(), res);

  __ bind(done);
  __ jmp(*stub->continuation());
}

#undef __

#define __ sasm->

void ShenandoahBarrierSetAssembler::generate_c1_pre_barrier_runtime_stub(StubAssembler* sasm) {
  __ prologue("shenandoah_pre_barrier", false);
  // arg0 : previous value of memory

  __ push(rax);
  __ push(rdx);

  const Register pre_val = rax;
  const Register thread = NOT_LP64(rax) LP64_ONLY(r15_thread);
  const Register tmp = rdx;

  NOT_LP64(__ get_thread(thread);)

  Address queue_index(thread, in_bytes(ShenandoahThreadLocalData::satb_mark_queue_index_offset()));
  Address buffer(thread, in_bytes(ShenandoahThreadLocalData::satb_mark_queue_buffer_offset()));

  Label done;
  Label runtime;

  // Is SATB still active?
  Address gc_state(thread, in_bytes(ShenandoahThreadLocalData::gc_state_offset()));
  __ testb(gc_state, ShenandoahHeap::MARKING | ShenandoahHeap::TRAVERSAL);
  __ jcc(Assembler::zero, done);

  // Can we store original value in the thread's buffer?

  __ movptr(tmp, queue_index);
  __ testptr(tmp, tmp);
  __ jcc(Assembler::zero, runtime);
  __ subptr(tmp, wordSize);
  __ movptr(queue_index, tmp);
  __ addptr(tmp, buffer);

  // prev_val (rax)
  __ load_parameter(0, pre_val);
  __ movptr(Address(tmp, 0), pre_val);
  __ jmp(done);

  __ bind(runtime);

  __ save_live_registers_no_oop_map(true);

  // load the pre-value
  __ load_parameter(0, rcx);
  __ call_VM_leaf(CAST_FROM_FN_PTR(address, ShenandoahRuntime::write_ref_field_pre_entry), rcx, thread);

  __ restore_live_registers(true);

  __ bind(done);

  __ pop(rdx);
  __ pop(rax);

  __ epilogue();
}

#undef __

#endif // COMPILER1

address ShenandoahBarrierSetAssembler::shenandoah_wb() {
  assert(_shenandoah_wb != NULL, "need write barrier stub");
  return _shenandoah_wb;
}

address ShenandoahBarrierSetAssembler::shenandoah_wb_C() {
  assert(_shenandoah_wb_C != NULL, "need write barrier stub");
  return _shenandoah_wb_C;
}

#define __ cgen->assembler()->

address ShenandoahBarrierSetAssembler::generate_shenandoah_wb(StubCodeGenerator* cgen, bool c_abi, bool do_cset_test) {
  __ align(CodeEntryAlignment);
  StubCodeMark mark(cgen, "StubRoutines", "shenandoah_wb");
  address start = __ pc();

#ifdef _LP64
  Label not_done;

  // We use RDI, which also serves as argument register for slow call.
  // RAX always holds the src object ptr, except after the slow call and
  // the cmpxchg, then it holds the result.
  // R8 and RCX are used as temporary registers.
  if (!c_abi) {
    __ push(rdi);
    __ push(r8);
  }

  // Check for object beeing in the collection set.
  // TODO: Can we use only 1 register here?
  // The source object arrives here in rax.
  // live: rax
  // live: rdi
  if (!c_abi) {
    __ mov(rdi, rax);
  } else {
    if (rax != c_rarg0) {
      __ mov(rax, c_rarg0);
    }
  }
  if (do_cset_test) {
    __ shrptr(rdi, ShenandoahHeapRegion::region_size_bytes_shift_jint());
    // live: r8
    __ movptr(r8, (intptr_t) ShenandoahHeap::in_cset_fast_test_addr());
    __ movbool(r8, Address(r8, rdi, Address::times_1));
    // unlive: rdi
    __ testbool(r8);
    // unlive: r8
    __ jccb(Assembler::notZero, not_done);

    if (!c_abi) {
      __ pop(r8);
      __ pop(rdi);
    }
    __ ret(0);

    __ bind(not_done);
  }

  if (!c_abi) {
    __ push(rcx);
  }

  if (!c_abi) {
    __ push(rdx);
    __ push(rdi);
    __ push(rsi);
    __ push(r8);
    __ push(r9);
    __ push(r10);
    __ push(r11);
    __ push(r12);
    __ push(r13);
    __ push(r14);
    __ push(r15);
  }
  __ save_vector_registers();
  __ movptr(rdi, rax);
  __ call_VM_leaf(CAST_FROM_FN_PTR(address, ShenandoahRuntime::write_barrier_JRT), rdi);
  __ restore_vector_registers();
  if (!c_abi) {
    __ pop(r15);
    __ pop(r14);
    __ pop(r13);
    __ pop(r12);
    __ pop(r11);
    __ pop(r10);
    __ pop(r9);
    __ pop(r8);
    __ pop(rsi);
    __ pop(rdi);
    __ pop(rdx);

    __ pop(rcx);
    __ pop(r8);
    __ pop(rdi);
  }
  __ ret(0);
#else
  ShouldNotReachHere();
#endif
  return start;
}

#undef __

void ShenandoahBarrierSetAssembler::barrier_stubs_init() {
  if (ShenandoahWriteBarrier || ShenandoahStoreValEnqueueBarrier) {
    int stub_code_size = 4096;
    ResourceMark rm;
    BufferBlob* bb = BufferBlob::create("shenandoah_barrier_stubs", stub_code_size);
    CodeBuffer buf(bb);
    StubCodeGenerator cgen(&buf);
    _shenandoah_wb = generate_shenandoah_wb(&cgen, false, true);
    _shenandoah_wb_C = generate_shenandoah_wb(&cgen, true, !ShenandoahWriteBarrierCsetTestInIR);
  }
}
