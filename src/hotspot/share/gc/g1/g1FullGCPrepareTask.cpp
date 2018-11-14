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

#include "gc/g1/g1CollectedHeap.inline.hpp"
#include "gc/g1/g1ConcurrentMarkBitMap.inline.hpp"
#include "gc/g1/g1FullCollector.inline.hpp"
#include "gc/g1/g1FullGCCompactionPoint.hpp"
#include "gc/g1/g1FullGCMarker.hpp"
#include "gc/g1/g1FullGCOopClosures.inline.hpp"
#include "gc/g1/g1FullGCPrepareTask.inline.hpp"
#include "gc/g1/g1HeapRegion.inline.hpp"
#include "gc/shared/gcTraceTime.inline.hpp"
#include "gc/shared/referenceProcessor.hpp"
#include "logging/log.hpp"
#include "memory/iterator.inline.hpp"
#include "oops/oop.inline.hpp"
#include "utilities/ticks.hpp"

G1DetermineCompactionQueueClosure::G1DetermineCompactionQueueClosure(G1FullCollector* collector) :
  _g1h(G1CollectedHeap::heap()),
  _collector(collector),
  _cur_worker(0) { }

bool G1FullGCPrepareTask::G1CalculatePointersClosure::do_heap_region(G1HeapRegion* hr) {
  uint region_idx = hr->hrm_index();
  assert(_collector->is_compaction_target(region_idx), "must be");
  hr->set_processing_order(_region_processing_order++);

  assert(!hr->is_humongous(), "must be");

  prepare_for_compaction(hr);

  return false;
}

G1FullGCPrepareTask::G1FullGCPrepareTask(G1FullCollector* collector) :
    G1FullGCTask("G1 Prepare Compact Task", collector),
    _has_free_compaction_targets(false),
    _hrclaimer(collector->workers()) {
}

void G1FullGCPrepareTask::set_has_free_compaction_targets() {
  if (!_has_free_compaction_targets) {
    _has_free_compaction_targets = true;
  }
}

bool G1FullGCPrepareTask::has_free_compaction_targets() {
  return _has_free_compaction_targets;
}

void G1FullGCPrepareTask::work(uint worker_id) {
  Ticks start = Ticks::now();
  // Calculate the target locations for the objects in the non-free regions of
  // the compaction queues provided by the associate compaction point.
  {
    G1FullGCCompactionPoint* compaction_point = collector()->compaction_point(worker_id);
    G1CalculatePointersClosure closure(collector(), compaction_point);

    for (GrowableArrayIterator<G1HeapRegion*> it = compaction_point->regions()->begin();
         it != compaction_point->regions()->end();
         ++it) {
      closure.do_heap_region(*it);
    }
    if (Universe::is_redefining_gc_run()) {
      compaction_point->forward_rescued();
    }
    compaction_point->update();
    // Determine if there are any unused compaction targets. This is only the case if
    // there are
    // - any regions in queue, so no free ones either.
    // - and the current region is not the last one in the list.
    if (compaction_point->has_regions() &&
        compaction_point->current_region() != compaction_point->regions()->last()) {
      set_has_free_compaction_targets();
    }
  }
  log_task("Prepare compaction task", worker_id, start);
}

G1FullGCPrepareTask::G1CalculatePointersClosure::G1CalculatePointersClosure(G1FullCollector* collector,
                                                                            G1FullGCCompactionPoint* cp) :
  _g1h(G1CollectedHeap::heap()),
  _collector(collector),
  _bitmap(collector->mark_bitmap()),
  _cp(cp),
  _region_processing_order(0) { }


G1FullGCPrepareTask::G1PrepareCompactLiveClosure::G1PrepareCompactLiveClosure(G1FullGCCompactionPoint* cp) :
    _cp(cp) { }

size_t G1FullGCPrepareTask::G1PrepareCompactLiveClosure::apply(oop object) {
  size_t size = object->size();
  _cp->forward(object, size);
  return size;
}

void G1FullGCPrepareTask::G1CalculatePointersClosure::prepare_for_compaction(G1HeapRegion* hr) {
  if (!_collector->is_free(hr->hrm_index())) {
    if (!Universe::is_redefining_gc_run()) {
      G1PrepareCompactLiveClosure prepare_compact(_cp);
      hr->apply_to_marked_objects(_bitmap, &prepare_compact);
    } else {
      G1PrepareCompactLiveClosureDcevm prepare_compact(_cp, hr->processing_order());
      hr->apply_to_marked_objects(_bitmap, &prepare_compact);
    }
  }
}

G1FullGCPrepareTask::G1PrepareCompactLiveClosureDcevm::G1PrepareCompactLiveClosureDcevm(G1FullGCCompactionPoint* cp,
                                                                                        uint region_processing_order) :
    _cp(cp),
    _region_processing_order(region_processing_order) { }

size_t G1FullGCPrepareTask::G1PrepareCompactLiveClosureDcevm::apply(oop object) {
  size_t size = object->size();
  size_t forward_size = size;

  // (DCEVM) There is a new version of the class of q => different size
  if (object->klass()->new_version() != NULL) {
    forward_size = object->size_given_klass(object->klass()->new_version());
  }

  HeapWord* compact_top = _cp->forward_compact_top(forward_size);

  if (compact_top == NULL || must_rescue(object, cast_to_oop(compact_top))) {
    _cp->rescued_oops()->append(cast_from_oop<HeapWord*>(object));
  } else {
    _cp->forward_dcevm(object, forward_size, (size != forward_size));
  }

  return size;
}

bool G1FullGCPrepareTask::G1PrepareCompactLiveClosureDcevm::must_rescue(oop old_obj, oop new_obj) {
  // Only redefined objects can have the need to be rescued.
  if (old_obj->klass()->new_version() == NULL) {
    return false;
  }

  if (_region_processing_order > _cp->current_region()->processing_order()) {
    return false;
  }

  if (_region_processing_order < _cp->current_region()->processing_order()) {
    return true;
  }

  // old obj and new obj are within same region
  size_t new_size = old_obj->size_given_klass(oop(old_obj)->klass()->new_version());
  size_t original_size = old_obj->size();

  // what if old_obj > new_obj ?
  bool overlap = (cast_from_oop<HeapWord*>(old_obj) + original_size < cast_from_oop<HeapWord*>(new_obj) + new_size);

  return overlap;
}
