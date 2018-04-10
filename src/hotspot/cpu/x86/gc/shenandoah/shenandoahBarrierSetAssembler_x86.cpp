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
#include "gc/shenandoah/shenandoahBarrierSetAssembler.hpp"
#include "gc/shenandoah/shenandoahBarrierSet.hpp"
#include "runtime/thread.hpp"

#define __ masm->

void ShenandoahBarrierSetAssembler::arraycopy_prologue(MacroAssembler* masm, DecoratorSet decorators, BasicType type,
                                                       Register src, Register dst, Register count) {

  bool checkcast = (decorators & ARRAYCOPY_CHECKCAST) != 0;
  bool disjoint = (decorators & ARRAYCOPY_DISJOINT) != 0;
  bool obj_int = type == T_OBJECT LP64_ONLY(&& UseCompressedOops);
  bool dest_uninitialized = (decorators & AS_DEST_NOT_INITIALIZED) != 0;

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

    if (!dest_uninitialized) {
      Register thread = NOT_LP64(rax) LP64_ONLY(r15_thread);
#ifndef _LP64
      __ push(thread);
      __ get_thread(thread);
#endif

      Label filtered;
      Address in_progress(thread, in_bytes(JavaThread::satb_mark_queue_offset() +
                                           SATBMarkQueue::byte_offset_of_active()));
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
        __ call_VM_leaf(CAST_FROM_FN_PTR(address, ShenandoahBarrierSet::write_ref_array_pre_narrow_oop_entry), 2);
      } else {
        __ call_VM_leaf(CAST_FROM_FN_PTR(address, ShenandoahBarrierSet::write_ref_array_pre_oop_entry), 2);
      }
#else
      __ call_VM_leaf(CAST_FROM_FN_PTR(address, ShenandoahBarrierSet::write_ref_array_pre_oop_entry),
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
    __ call_VM_leaf(CAST_FROM_FN_PTR(address, ShenandoahBarrierSet::write_ref_array_post_entry), 2);
#else
    __ call_VM_leaf(CAST_FROM_FN_PTR(address, ShenandoahBarrierSet::write_ref_array_post_entry),
                    dst, count);
#endif
    __ popa();
  }
}
