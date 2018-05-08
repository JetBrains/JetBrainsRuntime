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
#include "gc/shenandoah/heuristics/shenandoahPartialConnectedHeuristics.hpp"
#include "gc/shenandoah/shenandoahTraversalGC.hpp"
#include "utilities/quickSort.hpp"

const char* ShenandoahPartialConnectedHeuristics::name() {
  return "connectedness";
}

ShenandoahHeap::GCCycleMode ShenandoahPartialConnectedHeuristics::should_start_traversal_gc() {
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

  size_t threshold = heap->capacity() * ShenandoahConnectednessPercentage / 100;
  size_t allocated = used - prev_used;
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

void ShenandoahPartialConnectedHeuristics::choose_collection_set(ShenandoahCollectionSet* collection_set) {
  if (!is_minor_gc()) {
    return ShenandoahPartialHeuristics::choose_collection_set(collection_set);
  }

  ShenandoahHeap* heap = ShenandoahHeap::heap();
  ShenandoahTraversalGC* traversal_gc = heap->traversal_gc();
  ShenandoahConnectionMatrix* matrix = heap->connection_matrix();
  ShenandoahHeapRegionSet* root_regions = traversal_gc->root_regions();
  root_regions->clear();
  size_t num_regions = heap->num_regions();

  RegionConnections* connects = get_region_connects_cache(num_regions);
  size_t connect_cnt = 0;

  for (uint to_idx = 0; to_idx < num_regions; to_idx++) {
    ShenandoahHeapRegion* region = heap->get_region(to_idx);
    if (!region->is_regular()) continue;

    uint count = matrix->count_connected_to(to_idx, num_regions);
    if (count < ShenandoahPartialInboundThreshold) {
      connects[connect_cnt]._region = region;
      connects[connect_cnt]._connections = count;
      connect_cnt++;
    }
  }

  QuickSort::sort<RegionConnections>(connects, (int)connect_cnt, compare_by_connects, false);

  // Heuristics triggered partial when allocated was larger than a threshold.
  // New allocations might have happened while we were preparing for GC,
  // capture all them in this cycle. This "adjusts" the threshold automatically.
  size_t used      = heap->used();
  size_t prev_used = heap->used_at_last_gc();
  guarantee(used >= prev_used, "Invariant");
  size_t target = MIN3(ShenandoahHeapRegion::required_regions(used - prev_used), num_regions, connect_cnt);

  for (size_t c = 0; c < target; c++) {
    assert (c == 0 || connects[c]._connections >= connects[c-1]._connections, "monotonicity");

    ShenandoahHeapRegion* region = connects[c]._region;
    size_t to_idx = region->region_number();
    assert(region->is_regular(), "filtered before");
    assert(! heap->region_in_collection_set(to_idx), "must not be in cset yet");

    size_t from_idx_count = 0;
    if (matrix->enumerate_connected_to(to_idx, num_regions,
                                       _from_idxs, from_idx_count,
                                       ShenandoahPartialInboundThreshold)) {
      maybe_add_heap_region(region, collection_set);
      for (size_t i = 0; i < from_idx_count; i++) {
        ShenandoahHeapRegion* r = heap->get_region(_from_idxs[i]);
        root_regions->add_region_check_for_duplicates(r);
      }
    }
  }
  filter_regions();
  collection_set->update_region_status();
}
