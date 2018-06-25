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
#include "gc/shenandoah/heuristics/shenandoahTraversalHeuristics.hpp"
#include "gc/shenandoah/shenandoahFreeSet.hpp"
#include "gc/shenandoah/shenandoahTraversalGC.hpp"

ShenandoahTraversalHeuristics::ShenandoahTraversalHeuristics() : ShenandoahHeuristics(),
  _free_threshold(ShenandoahInitFreeThreshold),
  _peak_occupancy(0)
 {
  FLAG_SET_DEFAULT(UseShenandoahMatrix,              false);
  FLAG_SET_DEFAULT(ShenandoahSATBBarrier,            false);
  FLAG_SET_DEFAULT(ShenandoahStoreValReadBarrier,    false);
  FLAG_SET_DEFAULT(ShenandoahStoreValEnqueueBarrier, true);
  FLAG_SET_DEFAULT(ShenandoahKeepAliveBarrier,       false);
  FLAG_SET_DEFAULT(ShenandoahBarriersForConst,       true);
  FLAG_SET_DEFAULT(ShenandoahWriteBarrierRB,         false);
  FLAG_SET_DEFAULT(ShenandoahAllocImplicitLive,      false);
  FLAG_SET_DEFAULT(ShenandoahAllowMixedAllocs,       false);
  FLAG_SET_DEFAULT(ShenandoahRecycleClearsBitmap,    true);

  SHENANDOAH_ERGO_OVERRIDE_DEFAULT(ShenandoahRefProcFrequency, 1);

  // Adjust class unloading settings only if globally enabled.
  if (ClassUnloadingWithConcurrentMark) {
    SHENANDOAH_ERGO_OVERRIDE_DEFAULT(ShenandoahUnloadClassesFrequency, 1);
  }

}

bool ShenandoahTraversalHeuristics::should_start_normal_gc() const {
  return false;
}

bool ShenandoahTraversalHeuristics::is_experimental() {
  return true;
}

bool ShenandoahTraversalHeuristics::is_diagnostic() {
  return false;
}

bool ShenandoahTraversalHeuristics::can_do_traversal_gc() {
  return true;
}

const char* ShenandoahTraversalHeuristics::name() {
  return "traversal";
}

void ShenandoahTraversalHeuristics::choose_collection_set(ShenandoahCollectionSet* collection_set) {
  ShenandoahHeap* heap = ShenandoahHeap::heap();

  // No root regions in this mode.
  ShenandoahTraversalGC* traversal_gc = heap->traversal_gc();
  ShenandoahHeapRegionSet* root_regions = traversal_gc->root_regions();
  root_regions->clear();

  ShenandoahHeapRegionSet* traversal_set = traversal_gc->traversal_set();
  traversal_set->clear();
  for (size_t i = 0; i < heap->num_regions(); i++) {
    ShenandoahHeapRegion* r = heap->get_region(i);
    assert(!collection_set->is_in(r), "must not yet be in cset");
    if (r->is_regular() && r->used() > 0) {
      size_t garbage_percent = r->garbage() * 100 / ShenandoahHeapRegion::region_size_bytes();
      if (garbage_percent > ShenandoahGarbageThreshold) {
        collection_set->add_region(r);
      }
    }
    r->clear_live_data();
    traversal_set->add_region(r);
  }
  collection_set->update_region_status();
}

void ShenandoahTraversalHeuristics::handle_cycle_success() {
  ShenandoahHeap* heap = ShenandoahHeap::heap();
  size_t capacity = heap->capacity();

  size_t current_threshold = (capacity - _peak_occupancy) * 100 / capacity;
  size_t min_threshold = ShenandoahMinFreeThreshold;
  intx step = min_threshold - current_threshold;
  step = MAX2(step, (intx) -MaxNormalStep);
  step = MIN2(step, (intx) MaxNormalStep);

  log_info(gc, ergo)("Capacity: " SIZE_FORMAT "M, Peak Occupancy: " SIZE_FORMAT
                     "M, Lowest Free: " SIZE_FORMAT "M, Free Threshold: " UINTX_FORMAT "M",
                     capacity / M, _peak_occupancy / M,
                     (capacity - _peak_occupancy) / M, ShenandoahMinFreeThreshold * capacity / 100 / M);

  if (step > 0) {
    // Pessimize
    adjust_free_threshold(step);
  } else if (step < 0) {
    // Optimize, if enough happy cycles happened
    if (_successful_cycles_in_a_row > ShenandoahHappyCyclesThreshold &&
        _free_threshold > 0) {
      adjust_free_threshold(step);
      _successful_cycles_in_a_row = 0;
    }
  } else {
    // do nothing
  }
  _peak_occupancy = 0;
}

void ShenandoahTraversalHeuristics::adjust_free_threshold(intx adj) {
  intx new_value = adj + _free_threshold;
  uintx new_threshold = (uintx)MAX2<intx>(new_value, 0);
  new_threshold = MAX2(new_threshold, ShenandoahMinFreeThreshold);
  new_threshold = MIN2(new_threshold, ShenandoahMaxFreeThreshold);
  if (new_threshold != _free_threshold) {
    _free_threshold = new_threshold;
    log_info(gc,ergo)("Adjusting free threshold to: " UINTX_FORMAT "%% (" SIZE_FORMAT "M)",
                      _free_threshold, _free_threshold * ShenandoahHeap::heap()->capacity() / 100 / M);
  }
}

void ShenandoahTraversalHeuristics::record_success_concurrent() {
  ShenandoahHeuristics::record_success_concurrent();
  handle_cycle_success();
}

void ShenandoahTraversalHeuristics::record_success_degenerated() {
  ShenandoahHeuristics::record_success_degenerated();
  adjust_free_threshold(DegeneratedGC_Hit);
}

void ShenandoahTraversalHeuristics::record_success_full() {
  ShenandoahHeuristics::record_success_full();
  adjust_free_threshold(AllocFailure_Hit);
}

void ShenandoahTraversalHeuristics::record_explicit_gc() {
  ShenandoahHeuristics::record_explicit_gc();
  adjust_free_threshold(UserRequested_Hit);
}

void ShenandoahTraversalHeuristics::record_peak_occupancy() {
  _peak_occupancy = MAX2(_peak_occupancy, ShenandoahHeap::heap()->used());
}

ShenandoahHeap::GCCycleMode ShenandoahTraversalHeuristics::should_start_traversal_gc() {
  ShenandoahHeap* heap = ShenandoahHeap::heap();
  assert(!heap->has_forwarded_objects(), "no forwarded objects here");
  size_t capacity = heap->capacity();
  size_t available = heap->free_set()->available();

  double last_time_ms = (os::elapsedTime() - _last_cycle_end) * 1000;
  bool periodic_gc = (last_time_ms > ShenandoahGuaranteedGCInterval);
  size_t threshold_available = (capacity * _free_threshold) / 100;
  size_t bytes_allocated = heap->bytes_allocated_since_gc_start();
  size_t threshold_bytes_allocated = heap->capacity() * ShenandoahAllocationThreshold / 100;

  if (available < threshold_available &&
      bytes_allocated > threshold_bytes_allocated) {
    log_info(gc,ergo)("Concurrent traversal triggered. Free: " SIZE_FORMAT "M, Free Threshold: " SIZE_FORMAT
                      "M; Allocated: " SIZE_FORMAT "M, Alloc Threshold: " SIZE_FORMAT "M",
                      available / M, threshold_available / M, bytes_allocated / M, threshold_bytes_allocated / M);
    // Need to check that an appropriate number of regions have
    // been allocated since last concurrent mark too.
    return ShenandoahHeap::MAJOR;
  } else if (periodic_gc) {
    log_info(gc,ergo)("Periodic GC triggered. Time since last GC: %.0f ms, Guaranteed Interval: " UINTX_FORMAT " ms",
                      last_time_ms, ShenandoahGuaranteedGCInterval);
    return ShenandoahHeap::MAJOR;
  }
  return ShenandoahHeap::NONE;
}

void ShenandoahTraversalHeuristics::choose_collection_set_from_regiondata(ShenandoahCollectionSet* set,
                                                                          RegionData* data, size_t data_size,
                                                                          size_t free) {
  ShouldNotReachHere();
}
