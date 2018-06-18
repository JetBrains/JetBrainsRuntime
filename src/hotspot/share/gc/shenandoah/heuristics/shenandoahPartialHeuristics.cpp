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
#include "gc/shenandoah/heuristics/shenandoahPartialHeuristics.hpp"
#include "gc/shenandoah/shenandoahTraversalGC.hpp"

bool ShenandoahPartialHeuristics::is_minor_gc() const {
  return ShenandoahHeap::heap()->is_minor_gc();
}

// Utility method to remove any cset regions from root set and
// add all cset regions to the traversal set.
void ShenandoahPartialHeuristics::filter_regions() {
  ShenandoahHeap* heap = ShenandoahHeap::heap();
  ShenandoahTraversalGC* traversal_gc = heap->traversal_gc();
  size_t num_regions = heap->num_regions();
  ShenandoahCollectionSet* collection_set = heap->collection_set();
  ShenandoahHeapRegionSet* root_regions = traversal_gc->root_regions();
  ShenandoahHeapRegionSet* traversal_set = traversal_gc->traversal_set();
  traversal_set->clear();

  for (size_t i = 0; i < num_regions; i++) {
    ShenandoahHeapRegion* region = heap->get_region(i);
    if (collection_set->is_in(i)) {
      if (root_regions->is_in(i)) {
        root_regions->remove_region(region);
      }
      traversal_set->add_region_check_for_duplicates(region);
      assert(traversal_set->is_in(i), "must be in traversal set now");
    }
  }
}

ShenandoahPartialHeuristics::ShenandoahPartialHeuristics() :
  ShenandoahTraversalHeuristics() {

  FLAG_SET_DEFAULT(UseShenandoahMatrix, true);

  // TODO: Disable this optimization for now, as it also requires the matrix barriers.
#ifdef COMPILER2
  FLAG_SET_DEFAULT(ArrayCopyLoadStoreMaxElem, 0);
#endif
}

void ShenandoahPartialHeuristics::initialize() {
  _from_idxs = NEW_C_HEAP_ARRAY(size_t, ShenandoahHeap::heap()->num_regions(), mtGC);
}

ShenandoahPartialHeuristics::~ShenandoahPartialHeuristics() {
  FREE_C_HEAP_ARRAY(size_t, _from_idxs);
}

bool ShenandoahPartialHeuristics::should_start_update_refs() {
  return false;
}

bool ShenandoahPartialHeuristics::should_unload_classes() {
  return ShenandoahUnloadClassesFrequency != 0;
}

bool ShenandoahPartialHeuristics::should_process_references() {
  return ShenandoahRefProcFrequency != 0;
}

bool ShenandoahPartialHeuristics::should_start_normal_gc() const {
  return false;
}

bool ShenandoahPartialHeuristics::is_diagnostic() {
  return false;
}

bool ShenandoahPartialHeuristics::is_experimental() {
  return true;
}
