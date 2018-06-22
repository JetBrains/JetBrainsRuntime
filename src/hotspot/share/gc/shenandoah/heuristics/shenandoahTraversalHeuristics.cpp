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
#include "gc/shenandoah/shenandoahTraversalGC.hpp"

ShenandoahTraversalHeuristics::ShenandoahTraversalHeuristics() : ShenandoahHeuristics() {
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

ShenandoahHeap::GCCycleMode ShenandoahTraversalHeuristics::should_start_traversal_gc() {

  ShenandoahHeap* heap = ShenandoahHeap::heap();

  if (heap->has_forwarded_objects()) return ShenandoahHeap::NONE;

  double last_time_ms = (os::elapsedTime() - _last_cycle_end) * 1000;
  bool periodic_gc = (last_time_ms > ShenandoahGuaranteedGCInterval);
  if (periodic_gc) {
    log_info(gc,ergo)("Periodic GC triggered. Time since last GC: %.0f ms, Guaranteed Interval: " UINTX_FORMAT " ms",
                      last_time_ms, ShenandoahGuaranteedGCInterval);
    return ShenandoahHeap::MAJOR;
  }

  size_t capacity  = heap->capacity();
  size_t used      = heap->used();
  return 100 - (used * 100 / capacity) < ShenandoahFreeThreshold ? ShenandoahHeap::MAJOR : ShenandoahHeap::NONE;
}

void ShenandoahTraversalHeuristics::choose_collection_set_from_regiondata(ShenandoahCollectionSet* set,
                                                                          RegionData* data, size_t data_size,
                                                                          size_t free) {
  ShouldNotReachHere();
}
