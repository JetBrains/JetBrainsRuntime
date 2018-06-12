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

#ifndef CPU_PPC_GC_SHENANDOAH_SHENANDOAHBARRIERSETASSEMBLER_PPC_HPP
#define CPU_PPC_GC_SHENANDOAH_SHENANDOAHBARRIERSETASSEMBLER_PPC_HPP

#include "asm/macroAssembler.hpp"
#include "gc/shared/barrierSetAssembler.hpp"
#ifdef COMPILER1
#include "c1/c1_LIRAssembler.hpp"
#include "gc/shenandoah/c1/shenandoahBarrierSetC1.hpp"
#endif

class ShenandoahBarrierSetAssembler: public BarrierSetAssembler {
public:
  virtual void arraycopy_prologue(MacroAssembler* masm, DecoratorSet decorators, BasicType type,
                                  Register src, Register dst, Register count, Register preserve1, Register preserve2) {
    Unimplemented();
  }

  virtual void arraycopy_epilogue(MacroAssembler* masm, DecoratorSet decorators, BasicType type,
                                  Register dst, Register count, Register preserve) {
    Unimplemented();
  }

  virtual void store_at(MacroAssembler* masm, DecoratorSet decorators, BasicType type,
                        Register base, RegisterOrConstant ind_or_offs, Register val,
                        Register tmp1, Register tmp2, Register tmp3, bool needs_frame) {
    Unimplemented();
  }

  virtual void load_at(MacroAssembler* masm, DecoratorSet decorators, BasicType type,
                       Register base, RegisterOrConstant ind_or_offs, Register dst,
                       Register tmp1, Register tmp2, bool needs_frame, Label *is_null = NULL) {
    Unimplemented();
  }

  virtual void resolve_jobject(MacroAssembler* masm, Register value, Register tmp1, Register tmp2, bool needs_frame) {
    Unimplemented();
  }

#ifdef COMPILER1
  void gen_pre_barrier_stub(LIR_Assembler* ce, ShenandoahPreBarrierStub* stub) {
    Unimplemented();
  }

  void generate_c1_pre_barrier_runtime_stub(StubAssembler* sasm) {
    Unimplemented();
  }
#endif
};

#endif // CPU_PPC_GC_SHENANDOAH_SHENANDOAHBARRIERSETASSEMBLER_PPC_HPP
