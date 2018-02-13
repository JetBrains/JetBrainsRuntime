/*
 * Copyright (c) 2013, 2015, Red Hat, Inc. and/or its affiliates.
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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHBARRIERSET_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHBARRIERSET_HPP

#include "gc/shenandoah/shenandoahHeap.hpp"
#include "gc/shared/barrierSet.hpp"

class ShenandoahBarrierSet: public BarrierSet {
private:

  ShenandoahHeap* _heap;

public:

  ShenandoahBarrierSet(ShenandoahHeap* heap);

  void print_on(outputStream* st) const;

  bool is_a(BarrierSet::Name bsn);

  bool has_read_prim_array_opt();
  bool has_read_prim_barrier();
  bool has_read_ref_array_opt();
  bool has_read_ref_barrier();
  bool has_read_region_opt();
  bool has_write_prim_array_opt();
  bool has_write_prim_barrier();
  bool has_write_ref_array_opt();
  bool has_write_ref_barrier();
  bool has_write_ref_pre_barrier();
  bool has_write_region_opt();
  bool is_aligned(HeapWord* hw);
  void read_prim_array(MemRegion mr);
  void read_prim_field(HeapWord* hw, size_t s);
  bool read_prim_needs_barrier(HeapWord* hw, size_t s);
  void read_ref_array(MemRegion mr);

  void read_ref_field(void* v);

  bool read_ref_needs_barrier(void* v);
  void read_region(MemRegion mr);
  void resize_covered_region(MemRegion mr);
  void write_prim_array(MemRegion mr);
  void write_prim_field(HeapWord* hw, size_t s , juint x, juint y);
  bool write_prim_needs_barrier(HeapWord* hw, size_t s, juint x, juint y);
  void write_ref_array(HeapWord* start, size_t count);
  void write_ref_array_work(MemRegion r);

  template <class T> void
  write_ref_array_pre_work(T* dst, int count);

  void write_ref_array_pre(oop* dst, int count, bool dest_uninitialized);

  void write_ref_array_pre(narrowOop* dst, int count, bool dest_uninitialized);


  // We export this to make it available in cases where the static
  // type of the barrier set is known.  Note that it is non-virtual.
  template <class T> inline void inline_write_ref_field_pre(T* field, oop newVal);

  // These are the more general virtual versions.
  void write_ref_field_pre_work(oop* field, oop new_val);
  void write_ref_field_pre_work(narrowOop* field, oop new_val);
  void write_ref_field_pre_work(void* field, oop new_val);

  void write_ref_field_work(void* v, oop o, bool release = false);
  void write_region_work(MemRegion mr);

  virtual oop read_barrier(oop src);

  static inline oop resolve_forwarded_not_null(oop p);
  static inline oop resolve_forwarded(oop p);

  virtual oop write_barrier(oop obj);
  static oopDesc* write_barrier_IRT(oopDesc* src);
  static oopDesc* write_barrier_JRT(oopDesc* src);

  virtual oop storeval_barrier(oop obj);

  virtual void keep_alive_barrier(oop obj);

  bool obj_equals(oop obj1, oop obj2);
  bool obj_equals(narrowOop obj1, narrowOop obj2);

#ifdef ASSERT
  virtual void verify_safe_oop(oop p);
  virtual void verify_safe_oop(narrowOop p);
#endif

  static void enqueue(oop obj);

private:
  bool need_update_refs_barrier();

  template <class T, bool UPDATE_MATRIX, bool STOREVAL_WRITE_BARRIER, bool ALWAYS_ENQUEUE>
  void write_ref_array_loop(HeapWord* start, size_t count);

  oop write_barrier_impl(oop obj);

#ifndef CC_INTERP
public:
  void interpreter_read_barrier(MacroAssembler* masm, Register dst);
  void interpreter_read_barrier_not_null(MacroAssembler* masm, Register dst);
  void interpreter_write_barrier(MacroAssembler* masm, Register dst);
  void interpreter_storeval_barrier(MacroAssembler* masm, Register dst, Register tmp);
  void asm_acmp_barrier(MacroAssembler* masm, Register op1, Register op2);

private:
  void interpreter_read_barrier_impl(MacroAssembler* masm, Register dst);
  void interpreter_read_barrier_not_null_impl(MacroAssembler* masm, Register dst);
  void interpreter_write_barrier_impl(MacroAssembler* masm, Register dst);

#endif
};

#endif //SHARE_VM_GC_SHENANDOAH_SHENANDOAHBARRIERSET_HPP
