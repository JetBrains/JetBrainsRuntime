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
#include "gc/shenandoah/heuristics/shenandoahStaticHeuristics.hpp"
#include "gc/shenandoah/shenandoahCollectionSet.hpp"
#include "gc/shenandoah/shenandoahFreeSet.hpp"
#include "gc/shenandoah/shenandoahHeapRegion.hpp"

ShenandoahStaticHeuristics::ShenandoahStaticHeuristics() : ShenandoahHeuristics() {
  // Static heuristics may degrade to continuous if live data is larger
  // than free threshold. ShenandoahAllocationThreshold is supposed to break this,
  // but it only works if it is non-zero.
  SHENANDOAH_ERGO_OVERRIDE_DEFAULT(ShenandoahImmediateThreshold, 1);
}

void ShenandoahStaticHeuristics::print_thresholds() {
  log_info(gc, init)("Shenandoah heuristics thresholds: allocation " SIZE_FORMAT ", free " SIZE_FORMAT ", garbage " SIZE_FORMAT,
                     ShenandoahAllocationThreshold,
                     ShenandoahFreeThreshold,
                     ShenandoahGarbageThreshold);
}

ShenandoahStaticHeuristics::~ShenandoahStaticHeuristics() {}

bool ShenandoahStaticHeuristics::should_start_normal_gc() const {
  ShenandoahHeap* heap = ShenandoahHeap::heap();

  size_t capacity = heap->capacity();
  size_t available = heap->free_set()->available();
  size_t threshold_available = (capacity * ShenandoahFreeThreshold) / 100;
  size_t threshold_bytes_allocated = heap->capacity() * ShenandoahAllocationThreshold / 100;
  size_t bytes_allocated = heap->bytes_allocated_since_gc_start();

  double last_time_ms = (os::elapsedTime() - _last_cycle_end) * 1000;
  bool periodic_gc = (last_time_ms > ShenandoahGuaranteedGCInterval);

  if (available < threshold_available &&
      bytes_allocated > threshold_bytes_allocated) {
    // Need to check that an appropriate number of regions have
    // been allocated since last concurrent mark too.
    log_info(gc,ergo)("Concurrent marking triggered. Free: " SIZE_FORMAT "M, Free Threshold: " SIZE_FORMAT
                      "M; Allocated: " SIZE_FORMAT "M, Alloc Threshold: " SIZE_FORMAT "M",
                      available / M, threshold_available / M, bytes_allocated / M, threshold_bytes_allocated / M);
    return true;
  } else if (periodic_gc) {
    log_info(gc,ergo)("Periodic GC triggered. Time since last GC: %.0f ms, Guaranteed Interval: " UINTX_FORMAT " ms",
                      last_time_ms, ShenandoahGuaranteedGCInterval);
    return true;
  }

  return false;
}

void ShenandoahStaticHeuristics::choose_collection_set_from_regiondata(ShenandoahCollectionSet* cset,
                                                                       RegionData* data, size_t size,
                                                                       size_t free) {
  size_t threshold = ShenandoahHeapRegion::region_size_bytes() * ShenandoahGarbageThreshold / 100;

  for (size_t idx = 0; idx < size; idx++) {
    ShenandoahHeapRegion* r = data[idx]._region;
    if (r->garbage() > threshold) {
      cset->add_region(r);
    }
  }
}

const char* ShenandoahStaticHeuristics::name() {
  return "static";
}

bool ShenandoahStaticHeuristics::is_diagnostic() {
  return false;
}

bool ShenandoahStaticHeuristics::is_experimental() {
  return false;
}
