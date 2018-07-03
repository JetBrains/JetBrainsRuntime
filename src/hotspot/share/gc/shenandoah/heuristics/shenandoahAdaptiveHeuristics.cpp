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
#include "gc/shenandoah/heuristics/shenandoahAdaptiveHeuristics.hpp"
#include "gc/shenandoah/shenandoahCollectionSet.hpp"
#include "gc/shenandoah/shenandoahFreeSet.hpp"
#include "gc/shenandoah/shenandoahHeapRegion.hpp"
#include "utilities/quickSort.hpp"

ShenandoahAdaptiveHeuristics::ShenandoahAdaptiveHeuristics() :
  ShenandoahHeuristics(),
  _free_threshold(ShenandoahInitFreeThreshold),
  _peak_occupancy(0),
  _conc_mark_duration_history(new TruncatedSeq(5)),
  _conc_uprefs_duration_history(new TruncatedSeq(5)),
  _cycle_gap_history(new TruncatedSeq(5)) {
}

ShenandoahAdaptiveHeuristics::~ShenandoahAdaptiveHeuristics() {}

void ShenandoahAdaptiveHeuristics::choose_collection_set_from_regiondata(ShenandoahCollectionSet* cset,
                                                                         RegionData* data, size_t size,
                                                                         size_t actual_free) {
  size_t garbage_threshold = ShenandoahHeapRegion::region_size_bytes() * ShenandoahGarbageThreshold / 100;

  // The logic for cset selection in adaptive is as follows:
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

  size_t free_target = MIN2<size_t>(_free_threshold + MaxNormalStep, 100) * ShenandoahHeap::heap()->capacity() / 100;
  size_t min_garbage = free_target > actual_free ? (free_target - actual_free) : 0;
  size_t max_cset    = actual_free * 3 / 4;

  log_info(gc, ergo)("Adaptive CSet Selection. Target Free: " SIZE_FORMAT "M, Actual Free: "
                     SIZE_FORMAT "M, Max CSet: " SIZE_FORMAT "M, Min Garbage: " SIZE_FORMAT "M",
                     free_target / M, actual_free / M, max_cset / M, min_garbage / M);

  // Better select garbage-first regions
  QuickSort::sort<RegionData>(data, (int)size, compare_by_garbage, false);

  size_t cur_cset = 0;
  size_t cur_garbage = 0;
  _bytes_in_cset = 0;

  for (size_t idx = 0; idx < size; idx++) {
    ShenandoahHeapRegion* r = data[idx]._region;

    size_t new_cset    = cur_cset + r->get_live_data_bytes();
    size_t new_garbage = cur_garbage + r->garbage();

    if (new_cset > max_cset) {
      break;
    }

    if ((new_garbage < min_garbage) || (r->garbage() > garbage_threshold)) {
      cset->add_region(r);
      _bytes_in_cset += r->used();
      cur_cset = new_cset;
      cur_garbage = new_garbage;
    }
  }
}

void ShenandoahAdaptiveHeuristics::handle_cycle_success() {
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

void ShenandoahAdaptiveHeuristics::record_cycle_start() {
  ShenandoahHeuristics::record_cycle_start();
  double last_cycle_gap = (os::elapsedTime() - _last_cycle_end);
  _cycle_gap_history->add(last_cycle_gap);
}

void ShenandoahAdaptiveHeuristics::record_phase_time(ShenandoahPhaseTimings::Phase phase, double secs) {
  if (phase == ShenandoahPhaseTimings::conc_mark) {
    _conc_mark_duration_history->add(secs);
  } else if (phase == ShenandoahPhaseTimings::conc_update_refs) {
    _conc_uprefs_duration_history->add(secs);
  } // Else ignore
}

void ShenandoahAdaptiveHeuristics::adjust_free_threshold(intx adj) {
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

void ShenandoahAdaptiveHeuristics::record_success_concurrent() {
  ShenandoahHeuristics::record_success_concurrent();
  handle_cycle_success();
}

void ShenandoahAdaptiveHeuristics::record_success_degenerated() {
  ShenandoahHeuristics::record_success_degenerated();
  adjust_free_threshold(DegeneratedGC_Hit);
}

void ShenandoahAdaptiveHeuristics::record_success_full() {
  ShenandoahHeuristics::record_success_full();
  adjust_free_threshold(AllocFailure_Hit);
}

void ShenandoahAdaptiveHeuristics::record_explicit_gc() {
  ShenandoahHeuristics::record_explicit_gc();
  adjust_free_threshold(UserRequested_Hit);
}

void ShenandoahAdaptiveHeuristics::record_peak_occupancy() {
  _peak_occupancy = MAX2(_peak_occupancy, ShenandoahHeap::heap()->used());
}

bool ShenandoahAdaptiveHeuristics::should_start_normal_gc() const {
  ShenandoahHeap* heap = ShenandoahHeap::heap();
  size_t capacity = heap->capacity();
  size_t available = heap->free_set()->available();

  double last_time_ms = (os::elapsedTime() - _last_cycle_end) * 1000;
  bool periodic_gc = (last_time_ms > ShenandoahGuaranteedGCInterval);
  size_t threshold_available = (capacity * _free_threshold) / 100;
  size_t bytes_allocated = heap->bytes_allocated_since_gc_start();
  size_t threshold_bytes_allocated = heap->capacity() * ShenandoahAllocationThreshold / 100;

  if (available < threshold_available &&
      bytes_allocated > threshold_bytes_allocated) {
    log_info(gc,ergo)("Concurrent marking triggered. Free: " SIZE_FORMAT "M, Free Threshold: " SIZE_FORMAT
                      "M; Allocated: " SIZE_FORMAT "M, Alloc Threshold: " SIZE_FORMAT "M",
                      available / M, threshold_available / M, bytes_allocated / M, threshold_bytes_allocated / M);
    // Need to check that an appropriate number of regions have
    // been allocated since last concurrent mark too.
    return true;
  } else if (periodic_gc) {
    log_info(gc,ergo)("Periodic GC triggered. Time since last GC: %.0f ms, Guaranteed Interval: " UINTX_FORMAT " ms",
                      last_time_ms, ShenandoahGuaranteedGCInterval);
    return true;
  }

  return false;
}

bool ShenandoahAdaptiveHeuristics::should_start_update_refs() {
  if (! _update_refs_adaptive) {
    return _update_refs_early;
  }

  double cycle_gap_avg = _cycle_gap_history->avg();
  double conc_mark_avg = _conc_mark_duration_history->avg();
  double conc_uprefs_avg = _conc_uprefs_duration_history->avg();

  if (_update_refs_early) {
    double threshold = ShenandoahMergeUpdateRefsMinGap / 100.0;
    if (conc_mark_avg + conc_uprefs_avg > cycle_gap_avg * threshold) {
      _update_refs_early = false;
    }
  } else {
    double threshold = ShenandoahMergeUpdateRefsMaxGap / 100.0;
    if (conc_mark_avg + conc_uprefs_avg < cycle_gap_avg * threshold) {
      _update_refs_early = true;
    }
  }
  return _update_refs_early;
}

const char* ShenandoahAdaptiveHeuristics::name() {
  return "adaptive";
}

bool ShenandoahAdaptiveHeuristics::is_diagnostic() {
  return false;
}

bool ShenandoahAdaptiveHeuristics::is_experimental() {
  return false;
}
