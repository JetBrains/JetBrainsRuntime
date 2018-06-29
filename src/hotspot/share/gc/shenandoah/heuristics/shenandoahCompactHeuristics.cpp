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
#include "gc/shenandoah/shenandoahCollectionSet.hpp"
#include "gc/shenandoah/heuristics/shenandoahCompactHeuristics.hpp"
#include "gc/shenandoah/shenandoahFreeSet.hpp"
#include "gc/shenandoah/shenandoahHeapRegion.hpp"

ShenandoahCompactHeuristics::ShenandoahCompactHeuristics() : ShenandoahHeuristics() {
  SHENANDOAH_ERGO_ENABLE_FLAG(ShenandoahUncommit);
  SHENANDOAH_ERGO_OVERRIDE_DEFAULT(ShenandoahAllocationThreshold,  10);
  SHENANDOAH_ERGO_OVERRIDE_DEFAULT(ShenandoahImmediateThreshold,   100);
  SHENANDOAH_ERGO_OVERRIDE_DEFAULT(ShenandoahUncommitDelay,        5000);
  SHENANDOAH_ERGO_OVERRIDE_DEFAULT(ShenandoahGuaranteedGCInterval, 30000);
  SHENANDOAH_ERGO_OVERRIDE_DEFAULT(ShenandoahGarbageThreshold,     20);
}

bool ShenandoahCompactHeuristics::should_start_normal_gc() const {
  ShenandoahHeap* heap = ShenandoahHeap::heap();

  size_t available = heap->free_set()->available();
  double last_time_ms = (os::elapsedTime() - _last_cycle_end) * 1000;
  bool periodic_gc = (last_time_ms > ShenandoahGuaranteedGCInterval);
  size_t bytes_allocated = heap->bytes_allocated_since_gc_start();
  size_t threshold_bytes_allocated = heap->capacity() * ShenandoahAllocationThreshold / 100;

  if (available < threshold_bytes_allocated || bytes_allocated > threshold_bytes_allocated) {
    log_info(gc,ergo)("Concurrent marking triggered. Free: " SIZE_FORMAT "M, Allocated: " SIZE_FORMAT "M, Alloc Threshold: " SIZE_FORMAT "M",
                      available / M, bytes_allocated / M, threshold_bytes_allocated / M);
    return true;
  } else if (periodic_gc) {
    log_info(gc,ergo)("Periodic GC triggered. Time since last GC: %.0f ms, Guaranteed Interval: " UINTX_FORMAT " ms",
                      last_time_ms, ShenandoahGuaranteedGCInterval);
    return true;
  }

  return false;
}

void ShenandoahCompactHeuristics::choose_collection_set_from_regiondata(ShenandoahCollectionSet* cset,
                                                                        RegionData* data, size_t size,
                                                                        size_t actual_free) {

  // Do not select too large CSet that would overflow the available free space
  size_t max_cset = actual_free * 3 / 4;

  log_info(gc, ergo)("CSet Selection. Actual Free: " SIZE_FORMAT "M, Max CSet: " SIZE_FORMAT "M",
                     actual_free / M, max_cset / M);

  size_t threshold = ShenandoahHeapRegion::region_size_bytes() * ShenandoahGarbageThreshold / 100;

  size_t live_cset = 0;
  for (size_t idx = 0; idx < size; idx++) {
    ShenandoahHeapRegion* r = data[idx]._region;
    size_t new_cset = live_cset + r->get_live_data_bytes();
    if (new_cset < max_cset && r->garbage() > threshold) {
      live_cset = new_cset;
      cset->add_region(r);
    }
  }
}

const char* ShenandoahCompactHeuristics::name() {
  return "compact";
}

bool ShenandoahCompactHeuristics::is_diagnostic() {
  return false;
}

bool ShenandoahCompactHeuristics::is_experimental() {
  return false;
}
