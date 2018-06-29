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

#include "precompiled.hpp"
#include "gc/shenandoah/shenandoahConnectionMatrix.hpp"
#include "gc/shenandoah/shenandoahConnectionMatrix.inline.hpp"
#include "gc/shenandoah/shenandoahHeap.hpp"
#include "gc/shenandoah/shenandoahHeapRegion.hpp"
#include "gc/shenandoah/shenandoahHeapRegionSet.hpp"
#include "memory/allocation.inline.hpp"
#include "utilities/copy.hpp"
#include "utilities/align.hpp"
#include "runtime/atomic.hpp"
#include "runtime/os.hpp"

ShenandoahConnectionMatrix::ShenandoahConnectionMatrix(size_t max_regions) :
  _stride(max_regions),
  _region_shift(ShenandoahHeapRegion::region_size_bytes_shift())
{
  // Use 1-byte data type
  STATIC_ASSERT(sizeof(jbyte) == 1);
  assert(UseShenandoahMatrix, "Call only when matrix is enabled");

  size_t page_size = UseLargePages ? (size_t)os::large_page_size() : (size_t)os::vm_page_size();
  size_t granularity = os::vm_allocation_granularity();
  granularity = MAX2(granularity, page_size);
  size_t matrix_size = align_up(max_regions * max_regions, granularity);

  ReservedSpace matrix_bitmap(matrix_size, page_size);
  os::commit_memory_or_exit(matrix_bitmap.base(), matrix_bitmap.size(), false,
                              "couldn't allocate matrix bitmap");
  MemTracker::record_virtual_memory_type(matrix_bitmap.base(), mtGC);

  _matrix = (jbyte*)matrix_bitmap.base();
  _magic_offset = ((uintptr_t) _matrix) - ( ((uintptr_t) ShenandoahHeap::heap()->base()) >> _region_shift) * (_stride + 1);
  clear_all();
}

void ShenandoahConnectionMatrix::clear_all() {
  assert (UseShenandoahMatrix, "call only when matrix is enabled");
  size_t count = sizeof(char) * _stride * _stride;
  Copy::zero_to_bytes(_matrix, count);
}

void ShenandoahConnectionMatrix::print_on(outputStream* st) const {
  assert (UseShenandoahMatrix, "call only when matrix is enabled");
  st->print_cr("Connection Matrix:");
  st->print_cr("%8s, %10s, %10s, %10s, %8s, %8s, %8s, %8s",
               "Region", "Live", "Used", "Garbage",
               "TS_Start", "TS_End", "Refcnt", "Referenced by");

  ShenandoahHeap* heap = ShenandoahHeap::heap();
  for (uint from_idx = 0; from_idx < heap->num_regions(); from_idx++) {
    ShenandoahHeapRegion* r = heap->get_region(from_idx);
    if (r->is_active()) {
      uint count = 0;
      for (uint to_idx = 0; to_idx < _stride; to_idx++) {
        if (is_connected(to_idx, from_idx)) {
          count++;
        }
      }

      if (count > 0) {
        st->print("%8u, " SIZE_FORMAT_W(10) ", " SIZE_FORMAT_W(10) ", " SIZE_FORMAT_W(10) ", "
          UINT64_FORMAT_W(8) ", " UINT64_FORMAT_W(8) ", %8u, {",
                from_idx, r->get_live_data_bytes(), r->used(), r->garbage(),
                  r->seqnum_first_alloc(), r->seqnum_last_alloc(), count);
        for (uint to_idx = 0; to_idx < _stride; to_idx++) {
          if (is_connected(to_idx, from_idx)) {
            st->print("%u, ", to_idx);
          }
        }
        st->print_cr("}");
      }
    }
  }
}

