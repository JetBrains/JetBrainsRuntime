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
#include "gc/shenandoah/heuristics/shenandoahPartialGenerationalHeuristics.hpp"
#include "gc/shenandoah/shenandoahTraversalGC.hpp"
#include "utilities/quickSort.hpp"

ShenandoahPartialGenerationalHeuristics::ShenandoahPartialGenerationalHeuristics() : ShenandoahPartialHeuristics() {
  SHENANDOAH_ERGO_OVERRIDE_DEFAULT(ShenandoahPartialInboundThreshold, 100);
}

const char* ShenandoahPartialGenerationalHeuristics::name() {
  return "generational";
}

void ShenandoahPartialGenerationalHeuristics::choose_collection_set(ShenandoahCollectionSet* collection_set) {
  if (!is_minor_gc()) {
    return ShenandoahPartialHeuristics::choose_collection_set(collection_set);
  }

  ShenandoahHeap* heap = ShenandoahHeap::heap();
  ShenandoahTraversalGC* traversal_gc = heap->traversal_gc();
  ShenandoahConnectionMatrix* matrix = heap->connection_matrix();
  uint64_t alloc_seq_at_last_gc_end   = heap->alloc_seq_at_last_gc_end();
  uint64_t alloc_seq_at_last_gc_start = heap->alloc_seq_at_last_gc_start();

  size_t num_regions = heap->num_regions();

  RegionData* candidates = get_region_data_cache(num_regions);

  for (size_t i = 0; i < num_regions; i++) {
    candidates[i]._region = heap->get_region(i);
    candidates[i]._seqnum_last_alloc = heap->get_region(i)->seqnum_last_alloc();
  }

  QuickSort::sort<RegionData>(candidates, (int)num_regions, compare_by_alloc_seq_descending, false);

  // Heuristics triggered partial when allocated was larger than a threshold.
  // New allocations might have happened while we were preparing for GC,
  // capture all them in this cycle. This "adjusts" the threshold automatically.
  size_t used      = heap->used();
  size_t prev_used = heap->used_at_last_gc();
  guarantee(used >= prev_used, "Invariant");
  size_t target = MIN2(ShenandoahHeapRegion::required_regions(used - prev_used), num_regions);

  ShenandoahHeapRegionSet* root_regions = traversal_gc->root_regions();
  root_regions->clear();

  uint count = 0;

  for (uint i = 0; (i < num_regions) && (count < target); i++) {
    ShenandoahHeapRegion* contender = candidates[i]._region;
    if (contender->seqnum_last_alloc() <= alloc_seq_at_last_gc_end) {
      break;
    }

    size_t index = contender->region_number();
    size_t from_idx_count = 0;
    if (matrix->enumerate_connected_to(index, num_regions, _from_idxs, from_idx_count,
                                       ShenandoahPartialInboundThreshold)) {
      if (maybe_add_heap_region(contender, collection_set)) {
        count++;
      }

      for (uint f = 0; f < from_idx_count; f++) {
        ShenandoahHeapRegion* r = heap->get_region(_from_idxs[f]);
        root_regions->add_region_check_for_duplicates(r);
      }
    }
  }
  filter_regions();
  collection_set->update_region_status();

  log_info(gc,ergo)("Regions: Max: " SIZE_FORMAT ", Target: " SIZE_FORMAT " (" SIZE_FORMAT "%%), In CSet: " SIZE_FORMAT,
                    num_regions, target, ShenandoahGenerationalYoungGenPercentage, collection_set->count());
}

ShenandoahHeap::GCCycleMode ShenandoahPartialGenerationalHeuristics::should_start_traversal_gc() {
  ShenandoahHeap::GCCycleMode cycle_mode = ShenandoahPartialHeuristics::should_start_traversal_gc();
  if (cycle_mode != ShenandoahHeap::NONE) {
    return cycle_mode;
  }

  ShenandoahHeap* heap = ShenandoahHeap::heap();

  if (heap->has_forwarded_objects()) {
    // Cannot start partial if heap is not completely updated.
    return ShenandoahHeap::NONE;
  }

  size_t capacity  = heap->capacity();
  size_t used      = heap->used();
  size_t prev_used = heap->used_at_last_gc();

  if (used < prev_used) {
    // Major collection must have happened, "used" data is unreliable, wait for update.
    return ShenandoahHeap::NONE;
  }

  size_t threshold = heap->capacity() * ShenandoahGenerationalYoungGenPercentage / 100;
  size_t allocated = used - prev_used;

  // Start the next young gc after we've allocated percentage_young of the heap.
  bool result = allocated > threshold;

  FormatBuffer<> msg("%s. Capacity: " SIZE_FORMAT "M, Used: " SIZE_FORMAT "M, Previous Used: " SIZE_FORMAT
                     "M, Allocated: " SIZE_FORMAT "M, Threshold: " SIZE_FORMAT "M",
                     result ? "Partial cycle triggered" : "Partial cycle skipped",
                     capacity/M, used/M, prev_used/M, allocated/M, threshold/M);

  if (result) {
    log_info(gc,ergo)("%s", msg.buffer());
  } else {
    log_trace(gc,ergo)("%s", msg.buffer());
  }
  return result ? ShenandoahHeap::MINOR : ShenandoahHeap::NONE;
}
