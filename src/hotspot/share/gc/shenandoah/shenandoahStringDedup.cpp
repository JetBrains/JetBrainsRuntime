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

#include "precompiled.hpp"

#include "classfile/javaClasses.inline.hpp"
#include "gc/g1/g1StringDedup.hpp"
#include "gc/g1/g1StringDedupQueue.hpp"
#include "gc/g1/g1StringDedupTable.hpp"
#include "gc/g1/g1StringDedupThread.hpp"
#include "gc/shared/workgroup.hpp"
#include "gc/shenandoah/brooksPointer.hpp"
#include "gc/shenandoah/shenandoahCollectionSet.hpp"
#include "gc/shenandoah/shenandoahCollectionSet.inline.hpp"
#include "gc/shenandoah/shenandoahHeap.hpp"
#include "gc/shenandoah/shenandoahStringDedup.hpp"
#include "gc/shenandoah/shenandoahUtils.hpp"
#include "logging/log.hpp"
#include "runtime/safepoint.hpp"


// This closure is only used during full gc, after references are adjusted,
// and before heap compaction. No oop verification can be performed.
class ShenandoahIsAliveCompleteClosure: public BoolObjectClosure {
private:
  ShenandoahHeap* _heap;
public:
  ShenandoahIsAliveCompleteClosure() : _heap(ShenandoahHeap::heap()) {
  }

  bool do_object_b(oop obj) {
    assert(!oopDesc::is_null(obj), "null");
    return _heap->is_marked_complete(obj);
  }
};

// Same as above
class ShenandoahUpdateLiveRefsClosure: public OopClosure {
private:
  ShenandoahHeap* _heap;

  template <class T>
  inline void do_oop_work(T* p) {
    T o = oopDesc::load_heap_oop(p);
    if (! oopDesc::is_null(o)) {
      oop obj = oopDesc::decode_heap_oop_not_null(o);
      assert(_heap->is_in(obj), "Must be in the heap");
      HeapWord* ptr = BrooksPointer::get_raw(obj);
      oop forw = oop(ptr);

      assert(_heap->is_in(forw), "Must be in the heap");
      assert(!oopDesc::is_null(forw), "Can not be null");
      if (!oopDesc::unsafe_equals(forw, obj)) {
        oopDesc::encode_store_heap_oop(p, forw);
      }
    } else {
      assert(false, "NULL oop");
    }
  }

public:
  ShenandoahUpdateLiveRefsClosure() : _heap(ShenandoahHeap::heap()) {
  }

  inline void do_oop(oop* p)        { do_oop_work(p); }
  inline void do_oop(narrowOop* p)  { do_oop_work(p); }
};


// Perform String Dedup update and/or cleanup task
class ShenandoahStringDedupTableUpdateOrUnlinkTask : public AbstractGangTask {
private:
  G1StringDedupUnlinkOrOopsDoClosure  _dedup_closure;
public:
  ShenandoahStringDedupTableUpdateOrUnlinkTask(BoolObjectClosure* is_alive_closure, OopClosure* keep_alive_closure)
  : AbstractGangTask("Shenandoah Dedup unlink task"),
    _dedup_closure(is_alive_closure, keep_alive_closure, true) {
  }

  void work(uint worker_id) {
    G1StringDedup::parallel_unlink(&_dedup_closure, worker_id);
  }
};


void ShenandoahStringDedup::initialize() {
  assert(UseShenandoahGC, "String deduplication available with ShenandoahGC");
  if (UseStringDeduplication) {
    _enabled = true;
    G1StringDedupQueue::create(ShenandoahHeap::heap()->max_workers());
    G1StringDedupTable::create();
    G1StringDedupThread::create("Shenandoah StrDedup");
  }
}


void ShenandoahStringDedup::try_dedup(oop java_string) {
  if (is_candidate(java_string)) {
    G1StringDedup::deduplicate(java_string);
  }
}

void ShenandoahStringDedup::enqueue_from_safepoint(oop java_string, uint worker_id) {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Must be at a safepoint");
  assert(Thread::current()->is_Worker_thread(), "Must be a worker thread");
  assert(worker_id < ShenandoahHeap::heap()->max_workers(), "Sanity");

  G1StringDedupQueue::push(worker_id, java_string);
}


void ShenandoahStringDedup::parallel_full_gc_update_or_unlink() {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Must be at a safepoint");
  assert(ShenandoahHeap::heap()->is_full_gc_in_progress(), "Must be during full gc");
  log_debug(gc, stringdedup)("Clean/update string dedup table after full GC");

  ShenandoahUpdateLiveRefsClosure  update_refs_closure;
  ShenandoahIsAliveCompleteClosure is_alive_closure;
  ShenandoahStringDedupTableUpdateOrUnlinkTask task(&is_alive_closure, &update_refs_closure);
  ShenandoahHeap::heap()->workers()->run_task(&task);
}


void ShenandoahStringDedup::parallel_update_or_unlink() {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Must be at a safepoint");
  log_debug(gc, stringdedup)("Update string dedup table references");
  ShenandoahUpdateLiveRefsClosure update_refs_closure;
  ShenandoahIsAliveCompleteClosure is_alive_closure;
  ShenandoahStringDedupTableUpdateOrUnlinkTask task(&is_alive_closure, &update_refs_closure);
  ShenandoahHeap::heap()->workers()->run_task(&task);
}


// Only used during partial GC after evacuation.
// We don't have accurate bitmap during partial GC to determine liveness of
// an object. So after evacuation, if the object is in collection set but not
// evacuated, it is dead.
class ShenandoahPartialIsAliveClosure: public BoolObjectClosure {
private:
  ShenandoahHeap* _heap;
public:
  ShenandoahPartialIsAliveClosure() : _heap(ShenandoahHeap::heap()) {
  }

  bool do_object_b(oop obj);
};

bool ShenandoahPartialIsAliveClosure::do_object_b(oop obj) {
  if (_heap->in_collection_set(obj)) {
    oop forw = ShenandoahBarrierSet::resolve_oop_static_not_null(obj);
    return !oopDesc::unsafe_equals(forw, obj);
  }

  return true;
}


void ShenandoahStringDedup::parallel_partial_update_or_unlink() {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Must be at a safepoint");
  log_debug(gc, stringdedup)("Clean/update string dedup table after partial GC");
  ShenandoahUpdateLiveRefsClosure update_refs_closure;
  ShenandoahPartialIsAliveClosure is_alive_closure;
  ShenandoahStringDedupTableUpdateOrUnlinkTask task(&is_alive_closure, &update_refs_closure);
  ShenandoahHeap::heap()->workers()->run_task(&task);
}
