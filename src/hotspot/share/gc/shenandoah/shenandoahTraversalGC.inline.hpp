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
      oop forw = ShenandoahBarrierSet::resolve_oop_static_not_null(obj);
      if (oopDesc::unsafe_equals(obj, forw)) {
        bool evacuated = false;
        forw = _heap->evacuate_object(obj, thread, evacuated);
      }
      assert(! oopDesc::unsafe_equals(obj, forw) || _heap->cancelled_concgc(), "must be evacuated");
      // Update reference.
      _heap->atomic_compare_exchange_oop(forw, p, obj);
      obj = forw;
    }

    if ((!_bitmap->isMarked((HeapWord*) obj)) && _bitmap->parMark((HeapWord*) obj)) {
      bool succeeded = queue->push(ShenandoahMarkTask(obj));
      assert(succeeded, "must succeed to push to task queue");

      if (STRING_DEDUP && ShenandoahStringDedup::is_candidate(obj)) {
        assert(ShenandoahStringDedup::is_enabled(), "Must be enabled");
        assert(dq != NULL, "Dedup queue not set");
        ShenandoahStringDedup::enqueue_candidate(obj, dq);
      }
    }
  }
}

template <class T>
void ShenandoahTraversalGC::do_task(ShenandoahObjToScanQueue* q, T* cl, jushort* live_data, ShenandoahMarkTask* task) {
  oop obj = task->obj();

  assert(obj != NULL, "expect non-null object");
  assert(oopDesc::unsafe_equals(obj, ShenandoahBarrierSet::resolve_oop_static_not_null(obj)), "expect forwarded obj in queue");
  assert(_heap->cancelled_concgc()
         || BarrierSet::barrier_set()->is_safe(obj),
         "we don't want to mark objects in from-space");
  assert(_heap->is_in(obj), "referenced objects must be in the heap. No?");
  assert(_heap->is_marked_next(obj), "only marked objects on task queue");

  if (task->is_not_chunked()) {
    count_liveness(live_data, obj);
    if (obj->is_instance()) {
      // Case 1: Normal oop, process as usual.
      obj->oop_iterate(cl);
    } else if (obj->is_objArray()) {
      // Case 2: Object array instance and no chunk is set. Must be the first
      // time we visit it, start the chunked processing.
      do_chunked_array_start(q, cl, obj);
    } else {
      // Case 3: Primitive array. Do nothing, no oops there. We use the same
      // performance tweak TypeArrayKlass::oop_oop_iterate_impl is using:
      // We skip iterating over the klass pointer since we know that
      // Universe::TypeArrayKlass never moves.
      assert (obj->is_typeArray(), "should be type array");
    }
  } else {
    // Case 4: Array chunk, has sensible chunk id. Process it.
    do_chunked_array(q, cl, obj, task->chunk(), task->pow());
  }
}

inline void ShenandoahTraversalGC::count_liveness(jushort* live_data, oop obj) {
  size_t region_idx = _heap->heap_region_index_containing(obj);
  jushort cur = live_data[region_idx];
  int size = obj->size() + BrooksPointer::word_size();
  int max = (1 << (sizeof(jushort) * 8)) - 1;
  if (size >= max) {
    // too big, add to region data directly
    _heap->regions()->get(region_idx)->increase_live_data_words(size);
  } else {
    int new_val = cur + size;
    if (new_val >= max) {
      // overflow, flush to region data
      _heap->regions()->get(region_idx)->increase_live_data_words(new_val);
      live_data[region_idx] = 0;
    } else {
      // still good, remember in locals
      live_data[region_idx] = (jushort) new_val;
    }
  }
}

template <class T>
inline void ShenandoahTraversalGC::do_chunked_array_start(ShenandoahObjToScanQueue* q, T* cl, oop obj) {
  assert(obj->is_objArray(), "expect object array");
  objArrayOop array = objArrayOop(obj);
  int len = array->length();

  if (len <= (int) ObjArrayMarkingStride*2) {
    // A few slices only, process directly
    array->oop_iterate_range(cl, 0, len);
  } else {
    int bits = log2_long(len);
    // Compensate for non-power-of-two arrays, cover the array in excess:
    if (len != (1 << bits)) bits++;

    // Only allow full chunks on the queue. This frees do_chunked_array() from checking from/to
    // boundaries against array->length(), touching the array header on every chunk.
    //
    // To do this, we cut the prefix in full-sized chunks, and submit them on the queue.
    // If the array is not divided in chunk sizes, then there would be an irregular tail,
    // which we will process separately.

    int last_idx = 0;

    int chunk = 1;
    int pow = bits;

    // Handle overflow
    if (pow >= 31) {
      assert (pow == 31, "sanity");
      pow--;
      chunk = 2;
      last_idx = (1 << pow);
      bool pushed = q->push(ShenandoahMarkTask(array, 1, pow));
      assert(pushed, "overflow queue should always succeed pushing");
    }

    // Split out tasks, as suggested in ObjArrayChunkedTask docs. Record the last
    // successful right boundary to figure out the irregular tail.
    while ((1 << pow) > (int)ObjArrayMarkingStride &&
           (chunk*2 < ShenandoahMarkTask::chunk_size())) {
      pow--;
      int left_chunk = chunk*2 - 1;
      int right_chunk = chunk*2;
      int left_chunk_end = left_chunk * (1 << pow);
      if (left_chunk_end < len) {
        bool pushed = q->push(ShenandoahMarkTask(array, left_chunk, pow));
        assert(pushed, "overflow queue should always succeed pushing");
        chunk = right_chunk;
        last_idx = left_chunk_end;
      } else {
        chunk = left_chunk;
      }
    }

    // Process the irregular tail, if present
    int from = last_idx;
    if (from < len) {
      array->oop_iterate_range(cl, from, len);
    }
  }
}

template <class T>
inline void ShenandoahTraversalGC::do_chunked_array(ShenandoahObjToScanQueue* q, T* cl, oop obj, int chunk, int pow) {
  assert(obj->is_objArray(), "expect object array");
  objArrayOop array = objArrayOop(obj);

  assert (ObjArrayMarkingStride > 0, "sanity");

  // Split out tasks, as suggested in ObjArrayChunkedTask docs. Avoid pushing tasks that
  // are known to start beyond the array.
  while ((1 << pow) > (int)ObjArrayMarkingStride && (chunk*2 < ShenandoahMarkTask::chunk_size())) {
    pow--;
    chunk *= 2;
    bool pushed = q->push(ShenandoahMarkTask(array, chunk - 1, pow));
    assert(pushed, "overflow queue should always succeed pushing");
  }

  int chunk_size = 1 << pow;

  int from = (chunk - 1) * chunk_size;
  int to = chunk * chunk_size;

#ifdef ASSERT
  int len = array->length();
  assert (0 <= from && from < len, "from is sane: %d/%d", from, len);
  assert (0 < to && to <= len, "to is sane: %d/%d", to, len);
#endif

  array->oop_iterate_range(cl, from, to);
}

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHTRAVERSALGC_INLINE_HPP
