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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHBARRIERSET_INLINE_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHBARRIERSET_INLINE_HPP

#include "gc/shenandoah/shenandoahBarrierSet.hpp"
#include "gc/shenandoah/shenandoahConnectionMatrix.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"

inline oop ShenandoahBarrierSet::resolve_oop_static_not_null(oop p) {
  return BrooksPointer::forwardee(p);
}

inline oop ShenandoahBarrierSet::resolve_oop_static(oop p) {
  if (((HeapWord*) p) != NULL) {
    return resolve_oop_static_not_null(p);
  } else {
    return p;
  }
}

template <DecoratorSet decorators, typename BarrierSetT>
template <typename T>
inline oop ShenandoahBarrierSet::AccessBarrier<decorators, BarrierSetT>::oop_atomic_cmpxchg_in_heap(oop new_value, T* addr, oop compare_value) {
  oop res;
  oop expected = compare_value;
  do {
    compare_value = expected;
    res = Raw::oop_atomic_cmpxchg(new_value, addr, compare_value);
    expected = res;
  } while ((! oopDesc::unsafe_equals(compare_value, expected)) && oopDesc::unsafe_equals(BarrierSet::barrier_set()->read_barrier(compare_value), BarrierSet::barrier_set()->read_barrier(expected)));
  if (oopDesc::unsafe_equals(expected, compare_value)) {
    if (ShenandoahSATBBarrier && !oopDesc::is_null(compare_value)) {
      ShenandoahBarrierSet::enqueue(compare_value);
    }
    if (UseShenandoahMatrix && ! oopDesc::is_null(new_value)) {
      ShenandoahConnectionMatrix* matrix = ShenandoahHeap::heap()->connection_matrix();
      matrix->set_connected(addr, new_value);
    }
  }
  return res;
}

template <DecoratorSet decorators, typename BarrierSetT>
template <typename T>
inline oop ShenandoahBarrierSet::AccessBarrier<decorators, BarrierSetT>::oop_atomic_xchg_in_heap(oop new_value, T* addr) {
  oop previous = Raw::oop_atomic_xchg(new_value, addr);
  if (ShenandoahSATBBarrier) {
    if (!oopDesc::is_null(previous)) {
      ShenandoahBarrierSet::enqueue(previous);
    }
  }
  if (UseShenandoahMatrix && ! oopDesc::is_null(new_value)) {
    ShenandoahConnectionMatrix* matrix = ShenandoahHeap::heap()->connection_matrix();
    matrix->set_connected(addr, new_value);
  }
  return previous;
}

#endif //SHARE_VM_GC_SHENANDOAH_SHENANDOAHBARRIERSET_INLINE_HPP
