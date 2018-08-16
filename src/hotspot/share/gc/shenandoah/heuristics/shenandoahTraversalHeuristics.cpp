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
#include "gc/shenandoah/shenandoahHeuristics.hpp"
#include "gc/shenandoah/shenandoahTraversalGC.hpp"
#include "utilities/quickSort.hpp"

ShenandoahTraversalHeuristics::ShenandoahTraversalHeuristics() : ShenandoahHeuristics(),
  _free_threshold(ShenandoahInitFreeThreshold),
  _peak_occupancy(0),
  _last_cset_select(0)
 {
  FLAG_SET_DEFAULT(UseShenandoahMatrix,              false);
  FLAG_SET_DEFAULT(ShenandoahSATBBarrier,            false);
  FLAG_SET_DEFAULT(ShenandoahStoreValReadBarrier,    false);
  FLAG_SET_DEFAULT(ShenandoahStoreValEnqueueBarrier, true);
  FLAG_SET_DEFAULT(ShenandoahKeepAliveBarrier,       false);
  FLAG_SET_DEFAULT(ShenandoahWriteBarrierRB,         false);
  FLAG_SET_DEFAULT(ShenandoahAllowMixedAllocs,       false);
  FLAG_SET_DEFAULT(ShenandoahRecycleClearsBitmap,    true);

  SHENANDOAH_ERGO_OVERRIDE_DEFAULT(ShenandoahRefProcFrequency, 1);

  // Adjust class unloading settings only if globally enabled.
  if (ClassUnloadingWithConcurrentMark) {
    SHENANDOAH_ERGO_OVERRIDE_DEFAULT(ShenandoahUnloadClassesFrequency, 1);
  }

  // Workaround the bug in degen-traversal that evac assists expose.
  //
  // During traversal cycle, we can evacuate some object from region R1 (CS) to R2 (R).
  // Then degen-traversal happens, drops the cset, and finishes up the fixups.
  // Then next cycle happens to put both R1 and R2 into CS, and then R2 evacuates to R3.
  // It creates the double forwarding for that object: R1 (CS) -> R2 (CS) -> R3 (R).
  //
  // It is likely at that point that no references to R1 copy are left after the degen,
  // so this double forwarding is not exposed. *Unless* we have evac assists, that touch
  // the adjacent objects while evacuating live objects from R1, step on "bad" R1 copy,
  // and fail the internal asserts when getting oop sizes to walk the heap, or touching
  // its fwdptrs. The same thing would probably happen if we do size-based iteration
  // somewhere else.
  //
  // AllocHumongousFragment test exposes it nicely, always running into degens.
  //
  // TODO: Fix this properly
  // There are two alternatives: fix it in degen so that it never leaves double forwarding,
  // or make sure we only use raw accessors in evac assist path when getting oop_size,
  // including all exotic shapes like instanceMirrorKlass, and touching fwdptrs. The second
  // option is partly done in jdk12, but not in earlier jdks.
  FLAG_SET_DEFAULT(ShenandoahEvacAssist, 0);
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

  RegionData *data = get_region_data_cache(heap->num_regions());
  size_t cnt = 0;

  // Step 0. Prepare all regions

  for (size_t i = 0; i < heap->num_regions(); i++) {
    ShenandoahHeapRegion* r = heap->get_region(i);
    if (r->used() > 0) {
      if (r->is_regular()) {
        data[cnt]._region = r;
        data[cnt]._garbage = r->garbage();
        data[cnt]._seqnum_last_alloc = r->seqnum_last_alloc_mutator();
        cnt++;
      }
      traversal_set->add_region(r);
    }
  }

  // The logic for cset selection is similar to that of adaptive:
  //
  //   1. We cannot get cset larger than available free space. Otherwise we guarantee OOME
  //      during evacuation, and thus guarantee full GC. In practice, we also want to let
  //      application to allocate something. This is why we limit CSet to some fraction of
  //      available space. In non-overloaded heap, max_cset would contain all plausible candidates
  //      over garbage threshold.
  //
  //   2. We should not get cset too low so that free threshold would not be met right
  //      after the cycle. Otherwise we get back-to-back cycles for no reason if heap is
  //      too fragmented. In non-overloaded non-fragmented heap min_garbage would be around zero.
  //
  // Therefore, we start by sorting the regions by garbage. Then we unconditionally add the best candidates
  // before we meet min_garbage. Then we add all candidates that fit with a garbage threshold before
  // we hit max_cset. When max_cset is hit, we terminate the cset selection. Note that in this scheme,
  // ShenandoahGarbageThreshold is the soft threshold which would be ignored until min_garbage is hit.
  //
  // The significant complication is that liveness data was collected at the previous cycle, and only
  // for those regions that were allocated before previous cycle started.

  size_t actual_free = heap->free_set()->available();
  size_t free_target = MIN2<size_t>(_free_threshold + MaxNormalStep, 100) * ShenandoahHeap::heap()->capacity() / 100;
  size_t min_garbage = free_target > actual_free ? (free_target - actual_free) : 0;
  size_t max_cset    = actual_free * 3 / 4;

  log_info(gc, ergo)("Adaptive CSet Selection. Target Free: " SIZE_FORMAT "M, Actual Free: "
                     SIZE_FORMAT "M, Max CSet: " SIZE_FORMAT "M, Min Garbage: " SIZE_FORMAT "M",
                     free_target / M, actual_free / M, max_cset / M, min_garbage / M);

  // Better select garbage-first regions, and then older ones
  QuickSort::sort<RegionData>(data, (int) cnt, compare_by_garbage_then_alloc_seq_ascending, false);

  size_t cur_cset = 0;
  size_t cur_garbage = 0;

  size_t garbage_threshold = ShenandoahHeapRegion::region_size_bytes() / 100 * ShenandoahGarbageThreshold;

  // Step 1. Add trustworthy regions to collection set.
  //
  // We can trust live/garbage data from regions that were fully traversed during
  // previous cycle. Even if actual liveness is different now, we can only have _less_
  // live objects, because dead objects are not resurrected. Which means we can undershoot
  // the collection set, but not overshoot it.

  for (size_t i = 0; i < cnt; i++) {
    if (data[i]._seqnum_last_alloc > _last_cset_select) continue;

    ShenandoahHeapRegion* r = data[i]._region;
    assert (r->is_regular(), "should have been filtered before");

    size_t new_garbage = cur_garbage + r->garbage();
    size_t new_cset    = cur_cset    + r->get_live_data_bytes();

    if (new_cset > max_cset) {
      break;
    }

    if ((new_garbage < min_garbage) || (r->garbage() > garbage_threshold)) {
      assert(!collection_set->is_in(r), "must not yet be in cset");
      collection_set->add_region(r);
      cur_cset = new_cset;
      cur_garbage = new_garbage;
    }
  }

  // Step 2. Try to catch some recently allocated regions for evacuation ride.
  //
  // Pessimistically assume we are going to evacuate the entire region. While this
  // is very pessimistic and in most cases undershoots the collection set when regions
  // are mostly dead, it also provides more safety against running into allocation
  // failure when newly allocated regions are fully live.

  for (size_t i = 0; i < cnt; i++) {
    if (data[i]._seqnum_last_alloc <= _last_cset_select) continue;

    ShenandoahHeapRegion* r = data[i]._region;
    assert (r->is_regular(), "should have been filtered before");

    // size_t new_garbage = cur_garbage + 0; (implied)
    size_t new_cset = cur_cset + r->used();

    if (new_cset > max_cset) {
      break;
    }

    assert(!collection_set->is_in(r), "must not yet be in cset");
    collection_set->add_region(r);
    cur_cset = new_cset;
  }

  // Step 3. Clear liveness data
  // TODO: Merge it with step 0, but save live data in RegionData before.
  for (size_t i = 0; i < heap->num_regions(); i++) {
    ShenandoahHeapRegion* r = heap->get_region(i);
    if (r->used() > 0) {
      r->clear_live_data();
    }
  }

  collection_set->update_region_status();

  _last_cset_select = ShenandoahHeapRegion::seqnum_current_alloc();
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
  } else if (ShenandoahHeuristics::should_start_normal_gc()) {
    return ShenandoahHeap::MAJOR;
  }

  return ShenandoahHeap::NONE;
}

void ShenandoahTraversalHeuristics::choose_collection_set_from_regiondata(ShenandoahCollectionSet* set,
                                                                          RegionData* data, size_t data_size,
                                                                          size_t free) {
  ShouldNotReachHere();
}
