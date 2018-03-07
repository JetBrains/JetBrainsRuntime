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
#include "gc/shenandoah/shenandoahStringDedup.hpp"
#include "gc/shenandoah/shenandoahTraversalGC.hpp"
#include "gc/shenandoah/shenandoahTaskqueue.hpp"
#include "memory/iterator.inline.hpp"
#include "oops/oop.inline.hpp"

template <class T, bool STRING_DEDUP>
void ShenandoahTraversalGC::process_oop(T* p, Thread* thread, ShenandoahObjToScanQueue* queue, ShenandoahStrDedupQueue* dq) {
  T o = oopDesc::load_heap_oop(p);
  if (! oopDesc::is_null(o)) {
    oop obj = oopDesc::decode_heap_oop_not_null(o);
    if (_heap->in_collection_set(obj)) {
      oop forw = ShenandoahBarrierSet::resolve_forwarded_not_null(obj);
      if (oopDesc::unsafe_equals(obj, forw)) {
        bool evacuated = false;
        forw = _heap->evacuate_object(obj, thread, evacuated);
      }
      assert(! oopDesc::unsafe_equals(obj, forw) || _heap->cancelled_concgc(), "must be evacuated");
      // Update reference.
      _heap->atomic_compare_exchange_oop(forw, p, obj);
      obj = forw;
    }

    if (!_heap->is_marked_next(obj) && _heap->mark_next(obj)) {
      bool succeeded = queue->push(ShenandoahMarkTask(obj));
      assert(succeeded, "must succeed to push to task queue");

      if (STRING_DEDUP && ShenandoahStringDedup::is_candidate(obj)) {
        assert(ShenandoahStringDedup::is_enabled(), "Must be enabled");
        assert(dq != NULL, "Dedup queue not set");
        ShenandoahEvacOOMScopeLeaver oom_scope_leaver;
        ShenandoahStringDedup::enqueue_candidate(obj, dq);
      }
    }
  }
}

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHTRAVERSALGC_INLINE_HPP
