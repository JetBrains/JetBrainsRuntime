/*
 * Copyright (c) 2017, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
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

#include "gc/g1/g1CollectedHeap.hpp"
#include "gc/g1/g1ConcurrentMarkBitMap.inline.hpp"
#include "gc/g1/g1FullCollector.inline.hpp"
#include "gc/g1/g1FullGCCompactionPoint.hpp"
#include "gc/g1/g1FullGCCompactTask.hpp"
#include "gc/g1/g1HeapRegion.inline.hpp"
#include "gc/shared/fullGCForwarding.inline.hpp"
#include "gc/shared/gcTraceTime.inline.hpp"
#include "gc/shared/dcevmSharedGC.hpp"
#include "logging/log.hpp"
#include "oops/oop.inline.hpp"
#include "utilities/ticks.hpp"

void G1FullGCCompactTask::G1CompactRegionClosure::clear_in_bitmap(oop obj) {
  assert(_bitmap->is_marked(obj), "Should only compact marked objects");
  _bitmap->clear(obj);
}

size_t G1FullGCCompactTask::G1CompactRegionClosure::apply(oop obj) {
  size_t size = obj->size();
  if (FullGCForwarding::is_forwarded(obj)) {
    G1FullGCCompactTask::copy_object_to_new_location(obj);
  }

  // Clear the mark for the compacted object to allow reuse of the
  // bitmap without an additional clearing step.
  clear_in_bitmap(obj);
  return size;
}

void G1FullGCCompactTask::copy_object_to_new_location(oop obj) {
  assert(FullGCForwarding::is_forwarded(obj), "Sanity!");
  assert(FullGCForwarding::forwardee(obj) != obj, "Object must have a new location");

  size_t size = obj->size();
  // Copy object and reinit its mark.
  HeapWord* obj_addr = cast_from_oop<HeapWord*>(obj);
  HeapWord* destination = cast_from_oop<HeapWord*>(FullGCForwarding::forwardee(obj));
  Copy::aligned_conjoint_words(obj_addr, destination, size);

  // There is no need to transform stack chunks - marking already did that.
  cast_to_oop(destination)->init_mark();
  assert(cast_to_oop(destination)->klass() != nullptr, "should have a class");
}

void G1FullGCCompactTask::compact_region(G1HeapRegion* hr) {
  assert(!hr->has_pinned_objects(), "Should be no region with pinned objects in compaction queue");
  assert(!hr->is_humongous(), "Should be no humongous regions in compaction queue");

  if (!collector()->is_free(hr->hrm_index())) {
    // The compaction closure not only copies the object to the new
    // location, but also clears the bitmap for it. This is needed
    // for bitmap verification and to be able to use the bitmap
    // for evacuation failures in the next young collection. Testing
    // showed that it was better overall to clear bit by bit, compared
    // to clearing the whole region at the end. This difference was
    // clearly seen for regions with few marks.
    G1CompactRegionClosure compact(collector()->mark_bitmap());
    hr->apply_to_marked_objects(collector()->mark_bitmap(), &compact);
  }

  hr->reset_compacted_after_full_gc(_collector->compaction_top(hr));
}

void G1FullGCCompactTask::work(uint worker_id) {
  Ticks start = Ticks::now();
  GrowableArray<G1HeapRegion*>* compaction_queue = collector()->compaction_point(worker_id)->regions();

  if (!Universe::is_redefining_gc_run()) {
    GrowableArray<G1HeapRegion*>* compaction_queue = collector()->compaction_point(worker_id)->regions();
    for (GrowableArrayIterator<G1HeapRegion*> it = compaction_queue->begin();
         it != compaction_queue->end();
         ++it) {
      compact_region(*it);
    }
  } else {
    GrowableArrayIterator<HeapWord*> rescue_oops_it = collector()->compaction_point(worker_id)->rescued_oops()->begin();
    GrowableArray<HeapWord*>* rescued_oops_values = collector()->compaction_point(worker_id)->rescued_oops_values();

    for (GrowableArrayIterator<G1HeapRegion*> it = compaction_queue->begin();
         it != compaction_queue->end();
         ++it) {
      compact_region_dcevm(*it, rescued_oops_values, &rescue_oops_it);
    }
    assert(rescue_oops_it.at_end(), "Must be at end");
    G1FullGCCompactionPoint* cp = collector()->compaction_point(worker_id);
    if (cp->last_rescued_oop() > 0) {
      DcevmSharedGC::copy_rescued_objects_back(rescued_oops_values, 0, cp->last_rescued_oop(), false);
    }
  }
}

void G1FullGCCompactTask::serial_compaction() {
  GCTraceTime(Debug, gc, phases) tm("Phase 4: Serial Compaction", collector()->scope()->timer());
  GrowableArray<G1HeapRegion*>* compaction_queue = collector()->serial_compaction_point()->regions();
  for (GrowableArrayIterator<G1HeapRegion*> it = compaction_queue->begin();
       it != compaction_queue->end();
       ++it) {
    compact_region(*it);
  }
}

void G1FullGCCompactTask::humongous_compaction() {
  GCTraceTime(Debug, gc, phases) tm("Phase 4: Humonguous Compaction", collector()->scope()->timer());

  for (G1HeapRegion* hr : collector()->humongous_compaction_regions()) {
    assert(collector()->is_compaction_target(hr->hrm_index()), "Sanity");
    compact_humongous_obj(hr);
  }
}

void G1FullGCCompactTask::compact_humongous_obj(G1HeapRegion* src_hr) {
  assert(src_hr->is_starts_humongous(), "Should be start region of the humongous object");

  oop obj = cast_to_oop(src_hr->bottom());
  size_t word_size = obj->size();

  uint num_regions = (uint)G1CollectedHeap::humongous_obj_size_in_regions(word_size);
  HeapWord* destination = cast_from_oop<HeapWord*>(FullGCForwarding::forwardee(obj));

  assert(collector()->mark_bitmap()->is_marked(obj), "Should only compact marked objects");
  collector()->mark_bitmap()->clear(obj);

  copy_object_to_new_location(obj);

  uint dest_start_idx = _g1h->addr_to_region(destination);
  // Update the metadata for the destination regions.
  _g1h->set_humongous_metadata(_g1h->region_at(dest_start_idx), num_regions, word_size, false);

  // Free the source regions that do not overlap with the destination regions.
  uint src_start_idx = src_hr->hrm_index();
  free_non_overlapping_regions(src_start_idx, dest_start_idx, num_regions);
}

void G1FullGCCompactTask::free_non_overlapping_regions(uint src_start_idx, uint dest_start_idx, uint num_regions) {
  uint dest_end_idx = dest_start_idx + num_regions -1;
  uint src_end_idx  = src_start_idx + num_regions - 1;

  uint non_overlapping_start = dest_end_idx < src_start_idx ?
                               src_start_idx :
                               dest_end_idx + 1;

  for (uint i = non_overlapping_start; i <= src_end_idx; ++i) {
    G1HeapRegion* hr = _g1h->region_at(i);
    _g1h->free_humongous_region(hr, nullptr);
  }
void G1FullGCCompactTask::compact_region_dcevm(G1HeapRegion* hr, GrowableArray<HeapWord*>* rescued_oops_values,
    GrowableArrayIterator<HeapWord*>* rescue_oops_it) {
  assert(!hr->is_humongous(), "Should be no humongous regions in compaction queue");
  ResourceMark rm; //

  if (!collector()->is_free(hr->hrm_index())) {
    // The compaction closure not only copies the object to the new
    // location, but also clears the bitmap for it. This is needed
    // for bitmap verification and to be able to use the bitmap
    // for evacuation failures in the next young collection. Testing
    // showed that it was better overall to clear bit by bit, compared
    // to clearing the whole region at the end. This difference was
    // clearly seen for regions with few marks.
    G1CompactRegionClosureDcevm compact(collector()->mark_bitmap(), rescued_oops_values, rescue_oops_it);
    hr->apply_to_marked_objects(collector()->mark_bitmap(), &compact);
  }

  hr->reset_compacted_after_full_gc(_collector->compaction_top(hr));
}


void G1FullGCCompactTask::serial_compaction_dcevm() {
  GCTraceTime(Debug, gc, phases) tm("Phase 4: Serial Compaction", collector()->scope()->timer());

  // Clear allocated resources at compact points now, since all rescued oops are copied to destination.
  for (uint i = 0; i < collector()->workers(); i++) {
    G1FullGCCompactionPoint* cp = collector()->compaction_point(i);
    DcevmSharedGC::clear_rescued_objects_heap(cp->rescued_oops_values());
  }

}

void G1FullGCCompactTask::G1CompactRegionClosureDcevm::clear_in_bitmap(oop obj) {
  assert(_bitmap->is_marked(obj), "Should only compact marked objects");
  _bitmap->clear(obj);
}

size_t G1FullGCCompactTask::G1CompactRegionClosureDcevm::apply(oop obj) {
  size_t size = obj->size();
  if (obj->is_forwarded()) {
    HeapWord* destination = cast_from_oop<HeapWord*>(obj->forwardee());
    // copy object and reinit its mark
    HeapWord *obj_addr = cast_from_oop<HeapWord *>(obj);

    if (!_rescue_oops_it->at_end() && **_rescue_oops_it == obj_addr) {
      ++(*_rescue_oops_it);
      HeapWord *rescued_obj = NEW_C_HEAP_ARRAY(HeapWord, size, mtInternal);
      Copy::aligned_disjoint_words(obj_addr, rescued_obj, size);
      _rescued_oops_values->append(rescued_obj);
      DEBUG_ONLY(Copy::fill_to_words(obj_addr, size, 0));
      return size;
    }

    if (obj->klass()->new_version() != NULL) {
      Klass *new_version = obj->klass()->new_version();
      if (new_version->update_information() == NULL) {
        Copy::aligned_conjoint_words(obj_addr, destination, size);
        cast_to_oop(destination)->set_klass(new_version);
      } else {
        DcevmSharedGC::update_fields(obj, cast_to_oop(destination));
      }
      cast_to_oop(destination)->init_mark();
      assert(cast_to_oop(destination)->klass() != NULL, "should have a class");
      return size;
    }

    Copy::aligned_conjoint_words(obj_addr, destination, size);
    cast_to_oop(destination)->init_mark();
    assert(cast_to_oop(destination)->klass() != NULL, "should have a class");
  }
  clear_in_bitmap(obj);
  return size;
}
