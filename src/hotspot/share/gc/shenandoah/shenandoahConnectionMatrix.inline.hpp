/*
 * Copyright (c) 2017, Red Hat, Inc. and/or its affiliates.
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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHCONNECTIONMATRIX_INLINE_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHCONNECTIONMATRIX_INLINE_HPP

#include "gc/shenandoah/shenandoahConnectionMatrix.hpp"
#include "gc/shenandoah/shenandoahHeap.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"

  /*
    Compute matrix index.

    Practically, we are most frequently scanning for all incoming connections to a particular
    region. I.e. we iterate from_idx for some to_idx. Makes sense to keep matrix grouped by to_idx.
    matrix subindex is the address minus heap base shifted by region size.

    This means we want to update the matrix element at:

      MATRIX_BASE + (from_addr - HEAP_BASE) >> RS) + ((to_addr - HEAP_BASE) >> RS) * STRIDE

    ...where:
      MATRIX_BASE is native matrix address
           STRIDE is matrix stride
        HEAP_BASE is lowest heap address
               RS is region size shift

    This is what interpreter and C1 are doing. But in C2, we can make it more aggressive
    by restructuring the expression like this:

      (from_addr >> RS) + (to_addr >> RS) * STRIDE + [MATRIX_BASE - (HEAP_BASE >> RS) * (STRIDE + 1)]

    Notice that first two parts can be computed out-of-order, and only then merged with addition,
    which helps scheduling. If STRIDE is a power of two, then from_addr computation can be folded with
    region size shift. The third constant can be folded at compile time.

    As long as STRIDE is less than 2^RS, we never overflow. As long as HEAP_BASE is aligned to
    region size, we are safe with doing RS shifts. Guarantee both:
  */
jbyte* ShenandoahConnectionMatrix::compute_address(const void* from_addr, const void* to_addr) const {
  intptr_t from_idx = (((uintptr_t) from_addr) >> _region_shift);
  intptr_t to_idx = (((uintptr_t) to_addr) >> _region_shift) * _stride;
  jbyte* addr = (jbyte*) (from_idx + to_idx + _magic_offset);

#ifdef ASSERT
  // Check that computed address matches the address that we would get with the slow path.
  ShenandoahHeap* heap = ShenandoahHeap::heap();
  assert(heap->is_in(from_addr), "from is in heap: " PTR_FORMAT, p2i(from_addr));
  assert(heap->is_in(to_addr), "to is in heap: " PTR_FORMAT, p2i(to_addr));
  size_t from_region_idx = heap->heap_region_index_containing(from_addr);
  size_t to_region_idx = heap->heap_region_index_containing(to_addr);
  size_t matrix_idx = index_of(from_region_idx, to_region_idx);
  assert(&_matrix[matrix_idx] == addr, "fast and slow matrix address must match slow: "PTR_FORMAT", fast: "PTR_FORMAT, p2i(&_matrix[matrix_idx]), p2i(addr));
#endif

  return addr;
}

size_t ShenandoahConnectionMatrix::index_of(size_t from_idx, size_t to_idx) const {
  assert (UseShenandoahMatrix, "call only when matrix is enabled");
  assert (from_idx < _stride, "from is sane: " SIZE_FORMAT, from_idx);
  assert (to_idx < _stride, "to is sane: " SIZE_FORMAT, to_idx);
  return from_idx + to_idx * _stride;
}

bool ShenandoahConnectionMatrix::is_connected(size_t from_idx, size_t to_idx) const {
  assert (UseShenandoahMatrix, "call only when matrix is enabled");
  return _matrix[index_of(from_idx, to_idx)] > 0;
}

uint ShenandoahConnectionMatrix::count_connected_to(size_t to_idx, size_t count) const {
  assert (UseShenandoahMatrix, "call only when matrix is enabled");
  uint num_incoming = 0;
  jbyte* matrix = _matrix;
  size_t start = to_idx * _stride;
  for (uint from_idx = 0; from_idx < count; from_idx++) {
    num_incoming += matrix[start + from_idx];
  }

#ifdef ASSERT
  {
    uint check_incoming = 0;
    for (uint from_idx = 0; from_idx < count; from_idx++) {
      if (is_connected(from_idx, to_idx)) {
        check_incoming++;
      }
    }
    assert(num_incoming == check_incoming,
           "fast path and slow path agree: %d vs %d", num_incoming, check_incoming);
  }
#endif

  return num_incoming;
}

inline bool ShenandoahConnectionMatrix::enumerate_connected_to(size_t to_idx, size_t count,
                                                               size_t* from_idxs, size_t& from_idx_count, size_t from_idx_max) const {
  assert (UseShenandoahMatrix, "call only when matrix is enabled");
  uint num = 0;
  jbyte *matrix = _matrix;
  size_t start = to_idx * _stride;
  for (size_t from_idx = 0; from_idx < count; from_idx++) {
    if (matrix[start + from_idx] > 0) {
      if (num < from_idx_max) {
        from_idxs[num] = from_idx;
        num++;
      } else {
        return false;
      }
    }
  }

#ifdef ASSERT
  {
    uint cnt = count_connected_to(to_idx, count);
    assert (num == cnt, "counted the correct number of regions: %d vs %d", num, cnt);
    for (uint i = 0; i < num; i++) {
      assert (is_connected(from_idxs[i], to_idx), "should be connected");
    }
  }
#endif

  from_idx_count = num;
  return true;
}


void ShenandoahConnectionMatrix::set_connected(const void* from, const void* to) {
  assert (UseShenandoahMatrix, "call only when matrix is enabled");
  jbyte* addr = compute_address(from, to);
  if (*addr == 0) {
    *addr = 1;
  }
}

inline void ShenandoahConnectionMatrix::clear_connected(size_t from_idx, size_t to_idx) {
  assert (UseShenandoahMatrix, "call only when matrix is enabled");
  _matrix[index_of(from_idx, to_idx)] = 0;
}

inline void ShenandoahConnectionMatrix::clear_region(size_t idx) {
  assert (UseShenandoahMatrix, "call only when matrix is enabled");
  clear_region_inbound(idx);
  clear_region_outbound(idx);

#ifdef ASSERT
  for (size_t c = 0; c < _stride; c++) {
    assert (!is_connected(c, idx), "should not be connected");
    assert (!is_connected(idx, c), "should not be connected");
  }
#endif
}

inline void ShenandoahConnectionMatrix::clear_region_outbound(size_t idx) {
  assert (UseShenandoahMatrix, "call only when matrix is enabled");
  jbyte* matrix = _matrix;
  size_t stride = _stride;
  size_t count = stride * stride;
  for (size_t i = idx; i < count; i += stride) {
    if (matrix[i] != 0) matrix[i] = 0;
  }

#ifdef ASSERT
  for (size_t c = 0; c < _stride; c++) {
    assert (!is_connected(idx, c), "should not be connected");
  }
#endif
}

inline void ShenandoahConnectionMatrix::clear_region_inbound(size_t idx) {
  assert (UseShenandoahMatrix, "call only when matrix is enabled");
  jbyte* matrix = _matrix;
  size_t stride = _stride;
  size_t start = idx * stride;
  size_t end = start + stride;
  for (size_t i = start; i < end; i++) {
    if (matrix[i] != 0) matrix[i] = 0;
  }

#ifdef ASSERT
  for (size_t c = 0; c < _stride; c++) {
    assert (!is_connected(c, idx), "should not be connected");
  }
#endif
}

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHCONNECTIONMATRIX_INLINE_HPP
