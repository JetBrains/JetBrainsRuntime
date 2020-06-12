/*
 * Copyright (c) 2017, 2018, Oracle and/or its affiliates. All rights reserved.
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

#include "precompiled.hpp"
#include "gc/g1/g1CollectedHeap.hpp"
#include "gc/g1/g1ConcurrentMarkBitMap.inline.hpp"
#include "gc/g1/g1FullCollector.hpp"
#include "gc/g1/g1FullGCCompactionPoint.hpp"
#include "gc/g1/g1FullGCCompactTask.hpp"
#include "gc/g1/heapRegion.inline.hpp"
#include "gc/shared/gcTraceTime.inline.hpp"
#include "gc/shared/dcevmSharedGC.hpp"
#include "logging/log.hpp"
#include "oops/oop.inline.hpp"
#include "utilities/ticks.hpp"

class G1ResetHumongousClosure : public HeapRegionClosure {
  G1CMBitMap* _bitmap;

public:
  G1ResetHumongousClosure(G1CMBitMap* bitmap) :
      _bitmap(bitmap) { }

  bool do_heap_region(HeapRegion* current) {
    if (current->is_humongous()) {
      if (current->is_starts_humongous()) {
        oop obj = oop(current->bottom());
        if (_bitmap->is_marked(obj)) {
          // Clear bitmap and fix mark word.
          _bitmap->clear(obj);
          obj->init_mark_raw();
        } else {
          assert(current->is_empty(), "Should have been cleared in phase 2.");
        }
      }
      current->reset_during_compaction();
    }
    return false;
  }
};

size_t G1FullGCCompactTask::G1CompactRegionClosure::apply(oop obj) {
  size_t size = obj->size();
  HeapWord* destination = (HeapWord*)obj->forwardee();
  if (destination == NULL) {
    // Object not moving
    return size;
  }

  // copy object and reinit its mark
  HeapWord* obj_addr = (HeapWord*) obj;
  assert(obj_addr != destination, "everything in this pass should be moving");
  Copy::aligned_conjoint_words(obj_addr, destination, size);
  oop(destination)->init_mark_raw();
  assert(oop(destination)->klass() != NULL, "should have a class");

  return size;
}

void G1FullGCCompactTask::compact_region(HeapRegion* hr) {
  assert(!hr->is_humongous(), "Should be no humongous regions in compaction queue");
  G1CompactRegionClosure compact(collector()->mark_bitmap());
  hr->apply_to_marked_objects(collector()->mark_bitmap(), &compact);
  // Once all objects have been moved the liveness information
  // needs be cleared.
  collector()->mark_bitmap()->clear_region(hr);
  hr->complete_compaction();
}

void G1FullGCCompactTask::work(uint worker_id) {
  Ticks start = Ticks::now();
  GrowableArray<HeapRegion*>* compaction_queue = collector()->compaction_point(worker_id)->regions();

  if (!Universe::is_redefining_gc_run()) {
    for (GrowableArrayIterator<HeapRegion*> it = compaction_queue->begin();
         it != compaction_queue->end();
         ++it) {
      compact_region(*it);
    }
  } else {
    GrowableArrayIterator<HeapWord*> rescue_oops_it = collector()->compaction_point(worker_id)->rescued_oops()->begin();
    GrowableArray<HeapWord*>* rescued_oops_values = collector()->compaction_point(worker_id)->rescued_oops_values();

    for (GrowableArrayIterator<HeapRegion*> it = compaction_queue->begin();
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

  // TODO: (DCEV) check it
  G1ResetHumongousClosure hc(collector()->mark_bitmap());
  G1CollectedHeap::heap()->heap_region_par_iterate_from_worker_offset(&hc, &_claimer, worker_id);
  log_task("Compaction task", worker_id, start);
}

void G1FullGCCompactTask::serial_compaction() {
  GCTraceTime(Debug, gc, phases) tm("Phase 4: Serial Compaction", collector()->scope()->timer());
  GrowableArray<HeapRegion*>* compaction_queue = collector()->serial_compaction_point()->regions();
  for (GrowableArrayIterator<HeapRegion*> it = compaction_queue->begin();
       it != compaction_queue->end();
       ++it) {
    compact_region(*it);
  }
}

void G1FullGCCompactTask::compact_region_dcevm(HeapRegion* hr, GrowableArray<HeapWord*>* rescued_oops_values,
    GrowableArrayIterator<HeapWord*>* rescue_oops_it) {
  assert(!hr->is_humongous(), "Should be no humongous regions in compaction queue");
  ResourceMark rm; //

  G1CompactRegionClosureDcevm compact(collector()->mark_bitmap(), rescued_oops_values, rescue_oops_it);
  hr->apply_to_marked_objects(collector()->mark_bitmap(), &compact);
  // Once all objects have been moved the liveness information
  // needs be cleared.
  collector()->mark_bitmap()->clear_region(hr);
  hr->complete_compaction();
}

void G1FullGCCompactTask::serial_compaction_dcevm() {
  GCTraceTime(Debug, gc, phases) tm("Phase 4: Serial Compaction", collector()->scope()->timer());

  // compact remaining, not parallel compacted rescued oops using serial compact point

  for (uint i = 0; i < collector()->workers(); i++) {
    G1FullGCCompactionPoint* cp = collector()->compaction_point(i);
    DcevmSharedGC::clear_rescued_objects_heap(cp->rescued_oops_values());
  }

}

size_t G1FullGCCompactTask::G1CompactRegionClosureDcevm::apply(oop obj) {
  size_t size = obj->size();
  HeapWord* destination = (HeapWord*)obj->forwardee();
  if (destination == NULL) {
    // Object not moving
    return size;
  }

  // copy object and reinit its mark
  HeapWord* obj_addr = (HeapWord*) obj;

  if (!_rescue_oops_it->at_end() && **_rescue_oops_it == obj_addr) {
    ++(*_rescue_oops_it);
    HeapWord* rescued_obj = NEW_C_HEAP_ARRAY(HeapWord, size, mtInternal);
    Copy::aligned_disjoint_words(obj_addr, rescued_obj, size);
    _rescued_oops_values->append(rescued_obj);
    debug_only(Copy::fill_to_words(obj_addr, size, 0));
    return size;
  }

  if (obj->klass()->new_version() != NULL) {
    Klass* new_version = obj->klass()->new_version();
    if (new_version->update_information() == NULL) {
      Copy::aligned_conjoint_words(obj_addr, destination, size);
      oop(destination)->set_klass(new_version);
    } else {
      DcevmSharedGC::update_fields(obj, oop(destination));
    }
    oop(destination)->init_mark_raw();
    assert(oop(destination)->klass() != NULL, "should have a class");
    return size;
  }

  Copy::aligned_conjoint_words(obj_addr, destination, size);
  oop(destination)->init_mark_raw();
  assert(oop(destination)->klass() != NULL, "should have a class");

  return size;
}
