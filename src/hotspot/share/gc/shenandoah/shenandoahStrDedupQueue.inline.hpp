/*
 * Copyright (c) 2017, 2018, Red Hat, Inc. and/or its affiliates.
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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHSTRINGDEDUPQUEUE_INLINE_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHSTRINGDEDUPQUEUE_INLINE_HPP

#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahStrDedupQueue.hpp"

void ShenandoahStrDedupQueue::push(oop java_string) {
  if (_current_list == NULL) {
    _current_list = _queue_set->allocate_chunked_list();
  } else if (_current_list->is_full()) {
    _current_list = _queue_set->push_and_get_atomic(_current_list, queue_num());
  }

  assert(_current_list != NULL && !_current_list->is_full(), "Sanity");
  _current_list->push(java_string);
}


template <class T>
void ShenandoahStrDedupQueueCleanupClosure::do_oop_work(T* p) {
  T o = oopDesc::load_heap_oop(p);
  if (! oopDesc::is_null(o)) {
    oop obj = oopDesc::decode_heap_oop_not_null(o);
    assert(_heap->is_in(obj), "Must be in the heap");
    if (!_heap->is_marked_next(obj)) {
      oopDesc::encode_store_heap_oop(p, oop());
    }
  }
}

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHSTRINGDEDUPQUEUE_INLINE_HPP
