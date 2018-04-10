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
