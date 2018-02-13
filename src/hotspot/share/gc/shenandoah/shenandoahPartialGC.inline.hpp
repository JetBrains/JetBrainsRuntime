/*
 * Copyright (c) 2017, Red Hat, Inc. and/or its affiliates.
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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHPARTIALGC_INLINE_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHPARTIALGC_INLINE_HPP

#include "gc/shenandoah/shenandoahAsserts.hpp"
#include "gc/shenandoah/shenandoahPartialGC.hpp"

template <class T, bool UPDATE_MATRIX>
void ShenandoahPartialGC::process_oop(T* p, Thread* thread, ShenandoahObjToScanQueue* queue) {
  T o = oopDesc::load_heap_oop(p);
  if (! oopDesc::is_null(o)) {
    oop obj = oopDesc::decode_heap_oop_not_null(o);
    if (_heap->in_collection_set(obj)) {
      oop forw = ShenandoahBarrierSet::resolve_oop_static_not_null(obj);
      if (oopDesc::unsafe_equals(obj, forw)) {
        bool evacuated = false;
        forw = _heap->evacuate_object(obj, thread, evacuated);

        // Only the thread that succeeded evacuating this object pushes it to its work queue.
        if (evacuated) {
          assert(oopDesc::is_oop(forw), "sanity");
          bool succeeded = queue->push(ShenandoahMarkTask(forw));
          assert(succeeded, "must succeed to push to task queue");
        }
      }
      assert(! oopDesc::unsafe_equals(obj, forw) || _heap->cancelled_concgc(), "must be evacuated");
      // Update reference.
      _heap->atomic_compare_exchange_oop(forw, p, obj);
      // TODO: No need to update matrix if other thread beat us.
      obj = forw; // For matrix update below.
    }
    if (UPDATE_MATRIX) {
      shenandoah_assert_not_forwarded_except(p, obj, _heap->cancelled_concgc());
      _matrix->set_connected(p, obj);
    }
  }
}

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHPARTIALGC_INLINE_HPP
