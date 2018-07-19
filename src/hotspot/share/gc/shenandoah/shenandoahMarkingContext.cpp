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
#include "gc/shared/markBitMap.inline.hpp"
#include "gc/shenandoah/shenandoahHeap.hpp"
#include "gc/shenandoah/shenandoahHeapRegion.inline.hpp"
#include "gc/shenandoah/shenandoahMarkingContext.hpp"

ShenandoahMarkingContext::ShenandoahMarkingContext(MemRegion heap_region, MemRegion bitmap_region, size_t num_regions) :
  _top_at_mark_starts_base(NEW_C_HEAP_ARRAY(HeapWord*, num_regions, mtGC)),
  _top_at_mark_starts(_top_at_mark_starts_base -
                      ((uintx) heap_region.start() >> ShenandoahHeapRegion::region_size_bytes_shift())) {
  _mark_bit_map.initialize(heap_region, bitmap_region);
}

bool ShenandoahMarkingContext::is_bitmap_clear() const {
  ShenandoahHeap* heap = ShenandoahHeap::heap();
  size_t num_regions = heap->num_regions();
  for (size_t idx = 0; idx < num_regions; idx++) {
    ShenandoahHeapRegion* r = heap->get_region(idx);
    if (heap->is_bitmap_slice_committed(r) && !is_bitmap_clear_range(r->bottom(), r->end())) {
      return false;
    }
  }
  return true;
}

bool ShenandoahMarkingContext::is_bitmap_clear_range(HeapWord* start, HeapWord* end) const {
  return _mark_bit_map.getNextMarkedWordAddress(start, end) == end;
}

void ShenandoahMarkingContext::set_top_at_mark_start(size_t region_number, HeapWord* addr) {
  _top_at_mark_starts_base[region_number] = addr;
}

HeapWord* ShenandoahMarkingContext::top_at_mark_start(size_t region_number) const {
  return _top_at_mark_starts_base[region_number];
}

void ShenandoahMarkingContext::clear_bitmap(HeapWord* start, HeapWord* end) {
  _mark_bit_map.clear_range_large(MemRegion(start, end));
}
