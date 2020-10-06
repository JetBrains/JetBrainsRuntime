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
#include "gc/g1/g1FullGCCompactionPoint.hpp"
#include "gc/g1/heapRegion.hpp"
#include "oops/oop.inline.hpp"
#include "utilities/debug.hpp"

G1FullGCCompactionPoint::G1FullGCCompactionPoint() :
    _current_region(NULL),
    _threshold(NULL),
    _compaction_top(NULL),
    _last_rescued_oop(0)
{
  _compaction_regions = new (ResourceObj::C_HEAP, mtGC) GrowableArray<HeapRegion*>(32, true, mtGC);
  _compaction_region_iterator = _compaction_regions->begin();
  _rescued_oops = new (ResourceObj::C_HEAP, mtGC) GrowableArray<HeapWord*>(128, true, mtGC);
  _rescued_oops_values = new (ResourceObj::C_HEAP, mtGC) GrowableArray<HeapWord*>(128, true, mtGC);
}

G1FullGCCompactionPoint::~G1FullGCCompactionPoint() {
  delete _compaction_regions;
  delete _rescued_oops;
  delete _rescued_oops_values;
}

void G1FullGCCompactionPoint::update() {
  if (is_initialized()) {
    _current_region->set_compaction_top(_compaction_top);
  }
}

void G1FullGCCompactionPoint::initialize_values(bool init_threshold) {
  _compaction_top = _current_region->compaction_top();
  if (init_threshold) {
    _threshold = _current_region->initialize_threshold();
  }
}

bool G1FullGCCompactionPoint::has_regions() {
  return !_compaction_regions->is_empty();
}

bool G1FullGCCompactionPoint::is_initialized() {
  return _current_region != NULL;
}

void G1FullGCCompactionPoint::initialize(HeapRegion* hr, bool init_threshold) {
  _current_region = hr;
  initialize_values(init_threshold);
}

HeapRegion* G1FullGCCompactionPoint::current_region() {
  return *_compaction_region_iterator;
}

HeapRegion* G1FullGCCompactionPoint::next_region() {
  HeapRegion* next = *(++_compaction_region_iterator);
  assert(next != NULL, "Must return valid region");
  return next;
}

GrowableArray<HeapRegion*>* G1FullGCCompactionPoint::regions() {
  return _compaction_regions;
}

GrowableArray<HeapWord*>* G1FullGCCompactionPoint::rescued_oops() {
  return _rescued_oops;
}

GrowableArray<HeapWord*>* G1FullGCCompactionPoint::rescued_oops_values() {
  return _rescued_oops_values;
}

bool G1FullGCCompactionPoint::object_will_fit(size_t size) {
  size_t space_left = pointer_delta(_current_region->end(), _compaction_top);
  return size <= space_left;
}

void G1FullGCCompactionPoint::switch_region() {
  // Save compaction top in the region.
  _current_region->set_compaction_top(_compaction_top);
  // Get the next region and re-initialize the values.
  _current_region = next_region();
  initialize_values(true);
}

void G1FullGCCompactionPoint::forward(oop object, size_t size) {
  assert(_current_region != NULL, "Must have been initialized");

  // Ensure the object fit in the current region.
  while (!object_will_fit(size)) {
    switch_region();
  }

  // Store a forwarding pointer if the object should be moved.
  if ((HeapWord*)object != _compaction_top) {
    object->forward_to(oop(_compaction_top));
  } else {
    if (object->forwardee() != NULL) {
      // Object should not move but mark-word is used so it looks like the
      // object is forwarded. Need to clear the mark and it's no problem
      // since it will be restored by preserved marks. There is an exception
      // with BiasedLocking, in this case forwardee() will return NULL
      // even if the mark-word is used. This is no problem since
      // forwardee() will return NULL in the compaction phase as well.
      object->init_mark_raw();
    } else {
      // Make sure object has the correct mark-word set or that it will be
      // fixed when restoring the preserved marks.
      assert(object->mark_raw() == markOopDesc::prototype_for_object(object) || // Correct mark
             object->mark_raw()->must_be_preserved(object) || // Will be restored by PreservedMarksSet
             (UseBiasedLocking && object->has_bias_pattern_raw()), // Will be restored by BiasedLocking
             "should have correct prototype obj: " PTR_FORMAT " mark: " PTR_FORMAT " prototype: " PTR_FORMAT,
             p2i(object), p2i(object->mark_raw()), p2i(markOopDesc::prototype_for_object(object)));
    }
    assert(object->forwardee() == NULL, "should be forwarded to NULL");
  }

  // Update compaction values.
  _compaction_top += size;
  if (_compaction_top > _threshold) {
    _threshold = _current_region->cross_threshold(_compaction_top - size, _compaction_top);
  }
}

void G1FullGCCompactionPoint::add(HeapRegion* hr) {
  _compaction_regions->append(hr);
}

void G1FullGCCompactionPoint::merge(G1FullGCCompactionPoint* other) {
   _compaction_regions->appendAll(other->regions());
}

HeapRegion* G1FullGCCompactionPoint::remove_last() {
  return _compaction_regions->pop();
}

HeapWord* G1FullGCCompactionPoint::forward_compact_top(size_t size) {
  assert(_current_region != NULL, "Must have been initialized");
  // Ensure the object fit in the current region.
  while (!object_will_fit(size)) {
    if (!_compaction_region_iterator.has_next()) {
      return NULL;
    }
    switch_region();
  }
  return _compaction_top;
}

void G1FullGCCompactionPoint::forward_dcevm(oop object, size_t size, bool force_forward) {
  assert(_current_region != NULL, "Must have been initialized");

  // Store a forwarding pointer if the object should be moved.
  if ((HeapWord*)object != _compaction_top || force_forward) {
    object->forward_to(oop(_compaction_top));
  } else {
    if (object->forwardee() != NULL) {
      // Object should not move but mark-word is used so it looks like the
      // object is forwarded. Need to clear the mark and it's no problem
      // since it will be restored by preserved marks. There is an exception
      // with BiasedLocking, in this case forwardee() will return NULL
      // even if the mark-word is used. This is no problem since
      // forwardee() will return NULL in the compaction phase as well.
      object->init_mark_raw();
    } else {
      // Make sure object has the correct mark-word set or that it will be
      // fixed when restoring the preserved marks.
      assert(object->mark_raw() == markOopDesc::prototype_for_object(object) || // Correct mark
             object->mark_raw()->must_be_preserved(object) || // Will be restored by PreservedMarksSet
             (UseBiasedLocking && object->has_bias_pattern_raw()), // Will be restored by BiasedLocking
             "should have correct prototype obj: " PTR_FORMAT " mark: " PTR_FORMAT " prototype: " PTR_FORMAT,
             p2i(object), p2i(object->mark_raw()), p2i(markOopDesc::prototype_for_object(object)));
    }
    assert(object->forwardee() == NULL, "should be forwarded to NULL");
  }

  // Update compaction values.
  _compaction_top += size;
  if (_compaction_top > _threshold) {
    _threshold = _current_region->cross_threshold(_compaction_top - size, _compaction_top);
  }
}

void G1FullGCCompactionPoint::forward_rescued() {
  int i;

  i = _last_rescued_oop;

  for (;i<rescued_oops()->length(); i++) {
    HeapWord* q = rescued_oops()->at(i);

    size_t size = oop(q)->size();

    // (DCEVM) There is a new version of the class of q => different size
    if (oop(q)->klass()->new_version() != NULL) {
      // assert(size != new_size, "instances without changed size have to be updated prior to GC run");
      size = oop(q)->size_given_klass(oop(q)->klass()->new_version());
    }
    if (forward_compact_top(size) == NULL) {
      break;
    }
    forward_dcevm(oop(q), size, true);
  }
  _last_rescued_oop = i;
}
