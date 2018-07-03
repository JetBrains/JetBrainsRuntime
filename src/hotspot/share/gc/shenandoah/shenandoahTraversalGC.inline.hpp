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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHTRAVERSALGC_INLINE_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHTRAVERSALGC_INLINE_HPP

#include "gc/shared/markBitMap.inline.hpp"
#include "gc/shenandoah/shenandoahBarrierSet.inline.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahHeapRegion.inline.hpp"
#include "gc/shenandoah/shenandoahHeapRegionSet.inline.hpp"
#include "gc/shenandoah/shenandoahStringDedup.hpp"
#include "gc/shenandoah/shenandoahTraversalGC.hpp"
#include "gc/shenandoah/shenandoahTaskqueue.hpp"
#include "memory/iterator.inline.hpp"
#include "oops/oop.inline.hpp"

template <class T, bool STRING_DEDUP, bool DEGEN, bool UPDATE_MATRIX>
void ShenandoahTraversalGC::process_oop(T* p, Thread* thread, ShenandoahObjToScanQueue* queue, oop base_obj) {
  T o = RawAccess<>::oop_load(p);
  if (!CompressedOops::is_null(o)) {
    oop obj = CompressedOops::decode_not_null(o);
    bool update_matrix = true;
    if (DEGEN) {
      oop forw = ShenandoahBarrierSet::resolve_forwarded_not_null(obj);
      if (!oopDesc::unsafe_equals(obj, forw)) {
        // Update reference.
        RawAccess<IS_NOT_NULL>::oop_store(p, forw);
      }
      obj = forw;
    } else if (_heap->in_collection_set(obj)) {
      oop forw = ShenandoahBarrierSet::resolve_forwarded_not_null(obj);
      if (oopDesc::unsafe_equals(obj, forw)) {
        forw = _heap->evacuate_object(obj, thread);
      }
      // tty->print_cr("NORMAL visit: "PTR_FORMAT", obj: "PTR_FORMAT" to "PTR_FORMAT, p2i(p), p2i(obj), p2i(forw));
      assert(! oopDesc::unsafe_equals(obj, forw) || _heap->cancelled_gc(), "must be evacuated");
      // Update reference.
      oop previous = _heap->atomic_compare_exchange_oop(forw, p, obj);
      if (UPDATE_MATRIX && !oopDesc::unsafe_equals(previous, obj)) {
        update_matrix = false;
      }
      obj = forw;
    }

    if (UPDATE_MATRIX && update_matrix) {
      shenandoah_assert_not_forwarded_except(p, obj, _heap->cancelled_gc());
      const void* src;
      if (!_heap->is_in_reserved(p)) {
        src = (const void*)(HeapWord*) obj;
      } else {
        src = p;
      }
      if (src != NULL) {
        _matrix->set_connected(src, obj);
      }
    }

    if (_traversal_set.is_in((HeapWord*) obj) && !_heap->is_marked_next(obj) && _heap->mark_next(obj)) {
      bool succeeded = queue->push(ShenandoahMarkTask(obj));
      assert(succeeded, "must succeed to push to task queue");

      if (STRING_DEDUP && ShenandoahStringDedup::is_candidate(obj) && !_heap->cancelled_gc()) {
        assert(ShenandoahStringDedup::is_enabled(), "Must be enabled");
        // Only dealing with to-space string, so that we can avoid evac-oom protocol, which is costly here.
        assert(!_heap->in_collection_set(obj), "Must be in to-space");
        ShenandoahStringDedup::enqueue_candidate(obj);
      }
    }
  }
}

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHTRAVERSALGC_INLINE_HPP
