/*
 * Copyright (c) 2013, 2018, Red Hat, Inc. and/or its affiliates.
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
#include "gc/g1/g1BarrierSet.hpp"
#include "gc/shenandoah/shenandoahAsserts.hpp"
#include "gc/shenandoah/shenandoahBarrierSet.hpp"
#include "gc/shenandoah/shenandoahBarrierSetAssembler.hpp"
#include "gc/shenandoah/shenandoahCollectorPolicy.hpp"
#include "gc/shenandoah/shenandoahConnectionMatrix.inline.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "runtime/interfaceSupport.inline.hpp"

ShenandoahSATBMarkQueueSet ShenandoahBarrierSet::_satb_mark_queue_set;

template <bool UPDATE_MATRIX, bool STOREVAL_WRITE_BARRIER>
class ShenandoahUpdateRefsForOopClosure: public ExtendedOopClosure {
private:
  ShenandoahHeap* _heap;
  template <class T>
  inline void do_oop_work(T* p) {
    oop o;
    if (STOREVAL_WRITE_BARRIER) {
      o = _heap->evac_update_with_forwarded(p);
      if (!CompressedOops::is_null(o)) {
        ShenandoahBarrierSet::enqueue(o);
      }
    } else {
      o = _heap->maybe_update_with_forwarded(p);
    }
    if (UPDATE_MATRIX && !CompressedOops::is_null(o)) {
      _heap->connection_matrix()->set_connected(p, o);
    }
  }
public:
  ShenandoahUpdateRefsForOopClosure() : _heap(ShenandoahHeap::heap()) {
    assert(UseShenandoahGC && ShenandoahCloneBarrier, "should be enabled");
  }
  void do_oop(oop* p)       { do_oop_work(p); }
  void do_oop(narrowOop* p) { do_oop_work(p); }
};

ShenandoahBarrierSet::ShenandoahBarrierSet(ShenandoahHeap* heap) :
  BarrierSet(make_barrier_set_assembler<ShenandoahBarrierSetAssembler>(),
             BarrierSet::FakeRtti(BarrierSet::Shenandoah)),
  _heap(heap)
{
}

void ShenandoahBarrierSet::print_on(outputStream* st) const {
  st->print("ShenandoahBarrierSet");
}

bool ShenandoahBarrierSet::is_a(BarrierSet::Name bsn) {
  return bsn == BarrierSet::Shenandoah;
}

bool ShenandoahBarrierSet::is_aligned(HeapWord* hw) {
  return true;
}

void ShenandoahBarrierSet::resize_covered_region(MemRegion mr) {
  Unimplemented();
}

bool ShenandoahBarrierSet::need_update_refs_barrier() {
  if (UseShenandoahMatrix || _heap->is_concurrent_traversal_in_progress()) {
    return true;
  }
  if (_heap->shenandoahPolicy()->update_refs()) {
    return _heap->is_update_refs_in_progress();
  } else {
    return _heap->is_concurrent_mark_in_progress() && _heap->has_forwarded_objects();
  }
}

void ShenandoahBarrierSet::write_ref_array_work(MemRegion r) {
  ShouldNotReachHere();
}

template <class T, bool UPDATE_MATRIX, bool STOREVAL_WRITE_BARRIER>
void ShenandoahBarrierSet::write_ref_array_loop(HeapWord* start, size_t count) {
  assert(UseShenandoahGC && ShenandoahCloneBarrier, "should be enabled");
  ShenandoahUpdateRefsForOopClosure<UPDATE_MATRIX, STOREVAL_WRITE_BARRIER> cl;
  ShenandoahEvacOOMScope oom_evac_scope;
  T* dst = (T*) start;
  for (size_t i = 0; i < count; i++) {
    cl.do_oop(dst++);
  }
}

void ShenandoahBarrierSet::write_ref_array(HeapWord* start, size_t count) {
  assert(UseShenandoahGC, "should be enabled");
  if (!ShenandoahCloneBarrier) return;
  if (!need_update_refs_barrier()) return;

  if (UseShenandoahMatrix) {
    if (_heap->is_concurrent_traversal_in_progress()) {
      if (UseCompressedOops) {
        write_ref_array_loop<narrowOop, /* matrix = */ true, /* wb = */ true>(start, count);
      } else {
        write_ref_array_loop<oop,       /* matrix = */ true, /* wb = */ true>(start, count);
      }
    } else {
      if (UseCompressedOops) {
        write_ref_array_loop<narrowOop, /* matrix = */ true, /* wb = */ false>(start, count);
      } else {
        write_ref_array_loop<oop,       /* matrix = */ true, /* wb = */ false>(start, count);
      }
    }
  } else {
    if (_heap->is_concurrent_traversal_in_progress()) {
      if (UseCompressedOops) {
        write_ref_array_loop<narrowOop,   /* matrix = */ false, /* wb = */ true>(start, count);
      } else {
        write_ref_array_loop<oop,         /* matrix = */ false, /* wb = */ true>(start, count);
      }
    } else {
      if (UseCompressedOops) {
        write_ref_array_loop<narrowOop,   /* matrix = */ false, /* wb = */ false>(start, count);
      } else {
        write_ref_array_loop<oop,         /* matrix = */ false, /* wb = */ false>(start, count);
      }
    }
  }
}

void ShenandoahBarrierSet::write_ref_array_pre_oop_entry(oop* dst, size_t length) {
  ShenandoahBarrierSet *bs = barrier_set_cast<ShenandoahBarrierSet>(BarrierSet::barrier_set());
  bs->write_ref_array_pre(dst, length, false);
}

void ShenandoahBarrierSet::write_ref_array_pre_narrow_oop_entry(narrowOop* dst, size_t length) {
  ShenandoahBarrierSet *bs = barrier_set_cast<ShenandoahBarrierSet>(BarrierSet::barrier_set());
  bs->write_ref_array_pre(dst, length, false);
}

void ShenandoahBarrierSet::write_ref_array_post_entry(HeapWord* dst, size_t length) {
  ShenandoahBarrierSet *bs = barrier_set_cast<ShenandoahBarrierSet>(BarrierSet::barrier_set());
  bs->ShenandoahBarrierSet::write_ref_array(dst, length);
}


template <class T>
void ShenandoahBarrierSet::write_ref_array_pre_work(T* dst, int count) {
  shenandoah_assert_not_in_cset_loc_except(dst, _heap->cancelled_concgc());
  if (ShenandoahSATBBarrier && _heap->is_concurrent_mark_in_progress()) {
    T* elem_ptr = dst;
    for (int i = 0; i < count; i++, elem_ptr++) {
      T heap_oop = RawAccess<>::oop_load(elem_ptr);
      if (!CompressedOops::is_null(heap_oop)) {
        enqueue(CompressedOops::decode_not_null(heap_oop));
      }
    }
  }
}

void ShenandoahBarrierSet::write_ref_array_pre(oop* dst, int count, bool dest_uninitialized) {
  if (! dest_uninitialized) {
    write_ref_array_pre_work(dst, count);
  }
}

void ShenandoahBarrierSet::write_ref_array_pre(narrowOop* dst, int count, bool dest_uninitialized) {
  if (! dest_uninitialized) {
    write_ref_array_pre_work(dst, count);
  }
}

template <class T>
inline void ShenandoahBarrierSet::inline_write_ref_field_pre(T* field, oop new_val) {
  shenandoah_assert_not_in_cset_loc_except(field, _heap->cancelled_concgc());
  if (_heap->is_concurrent_mark_in_progress()) {
    T heap_oop = RawAccess<>::oop_load(field);
    if (!CompressedOops::is_null(heap_oop)) {
      enqueue(CompressedOops::decode(heap_oop));
    }
  }
  if (UseShenandoahMatrix && ! CompressedOops::is_null(new_val)) {
    ShenandoahConnectionMatrix* matrix = _heap->connection_matrix();
    matrix->set_connected(field, new_val);
  }
}

// These are the more general virtual versions.
void ShenandoahBarrierSet::write_ref_field_pre_work(oop* field, oop new_val) {
  inline_write_ref_field_pre(field, new_val);
}

void ShenandoahBarrierSet::write_ref_field_pre_work(narrowOop* field, oop new_val) {
  inline_write_ref_field_pre(field, new_val);
}

void ShenandoahBarrierSet::write_ref_field_pre_work(void* field, oop new_val) {
  guarantee(false, "Not needed");
}

void ShenandoahBarrierSet::write_ref_field_work(void* v, oop o, bool release) {
  shenandoah_assert_not_in_cset_loc_except(v, _heap->cancelled_concgc());
  shenandoah_assert_not_forwarded_except  (v, o, o == NULL || _heap->cancelled_concgc() || !_heap->is_concurrent_mark_in_progress());
  shenandoah_assert_not_in_cset_except    (v, o, o == NULL || _heap->cancelled_concgc() || !_heap->is_concurrent_mark_in_progress());
}

void ShenandoahBarrierSet::write_region(MemRegion mr) {
  assert(UseShenandoahGC, "should be enabled");
  if (!ShenandoahCloneBarrier) return;
  if (! need_update_refs_barrier()) return;

  // This is called for cloning an object (see jvm.cpp) after the clone
  // has been made. We are not interested in any 'previous value' because
  // it would be NULL in any case. But we *are* interested in any oop*
  // that potentially need to be updated.

  ShenandoahEvacOOMScope oom_evac_scope;
  oop obj = oop(mr.start());
  assert(oopDesc::is_oop(obj), "must be an oop");
  if (UseShenandoahMatrix) {
    if (_heap->is_concurrent_traversal_in_progress()) {
      ShenandoahUpdateRefsForOopClosure<true, true> cl;
      obj->oop_iterate(&cl);
    } else {
      ShenandoahUpdateRefsForOopClosure<true, false> cl;
      obj->oop_iterate(&cl);
    }
  } else {
    if (_heap->is_concurrent_traversal_in_progress()) {
      ShenandoahUpdateRefsForOopClosure<false, true> cl;
      obj->oop_iterate(&cl);
    } else {
      ShenandoahUpdateRefsForOopClosure<false, false> cl;
      obj->oop_iterate(&cl);
    }
  }
}

oop ShenandoahBarrierSet::read_barrier(oop src) {
  // Check for forwarded objects, because on Full GC path we might deal with
  // non-trivial fwdptrs that contain Full GC specific metadata. We could check
  // for is_full_gc_in_progress(), but this also covers the case of stable heap,
  // which provides a bit of performance improvement.
  if (ShenandoahReadBarrier && _heap->has_forwarded_objects()) {
    return ShenandoahBarrierSet::resolve_forwarded(src);
  } else {
    return src;
  }
}

bool ShenandoahBarrierSet::obj_equals(oop obj1, oop obj2) {
  bool eq = oopDesc::unsafe_equals(obj1, obj2);
  if (! eq && ShenandoahAcmpBarrier) {
    OrderAccess::loadload();
    obj1 = resolve_forwarded(obj1);
    obj2 = resolve_forwarded(obj2);
    eq = oopDesc::unsafe_equals(obj1, obj2);
  }
  return eq;
}

JRT_LEAF(oopDesc*, ShenandoahBarrierSet::write_barrier_JRT(oopDesc* src))
  oop result = ((ShenandoahBarrierSet*)BarrierSet::barrier_set())->write_barrier(src);
  return (oopDesc*) result;
JRT_END

IRT_LEAF(oopDesc*, ShenandoahBarrierSet::write_barrier_IRT(oopDesc* src))
  oop result = ((ShenandoahBarrierSet*)BarrierSet::barrier_set())->write_barrier(src);
  return (oopDesc*) result;
IRT_END

oop ShenandoahBarrierSet::write_barrier_impl(oop obj) {
  assert(UseShenandoahGC && ShenandoahWriteBarrier, "should be enabled");
  if (!CompressedOops::is_null(obj)) {
    bool evac_in_progress = _heap->is_gc_in_progress_mask(ShenandoahHeap::EVACUATION | ShenandoahHeap::TRAVERSAL);
    oop fwd = resolve_forwarded_not_null(obj);
    if (evac_in_progress &&
        _heap->in_collection_set(obj) &&
        oopDesc::unsafe_equals(obj, fwd)) {
      ShenandoahEvacOOMScope oom_evac_scope;
      return _heap->evacuate_object(obj, Thread::current());
    } else {
      return fwd;
    }
  } else {
    return obj;
  }
}

oop ShenandoahBarrierSet::write_barrier(oop obj) {
  if (ShenandoahWriteBarrier) {
    return write_barrier_impl(obj);
  } else {
    return obj;
  }
}

oop ShenandoahBarrierSet::storeval_barrier(oop obj) {
  if (ShenandoahStoreValEnqueueBarrier) {
    if (!CompressedOops::is_null(obj)) {
      obj = write_barrier(obj);
      enqueue(obj);
    }
  }
  if (ShenandoahStoreValEnqueueBarrier && !CompressedOops::is_null(obj)) {
    enqueue(obj);
  }
  if (ShenandoahStoreValReadBarrier) {
    obj = resolve_forwarded(obj);
  }
  return obj;
}

void ShenandoahBarrierSet::keep_alive_barrier(oop obj) {
  if (ShenandoahKeepAliveBarrier && _heap->is_concurrent_mark_in_progress()) {
    enqueue(obj);
  }
}

void ShenandoahBarrierSet::enqueue(oop obj) {
  shenandoah_assert_not_forwarded_if(NULL, obj, ShenandoahHeap::heap()->is_concurrent_traversal_in_progress());
  // Nulls should have been already filtered.
  assert(oopDesc::is_oop(obj, true), "Error");

  if (!_satb_mark_queue_set.is_active()) return;
  Thread* thr = Thread::current();
  if (thr->is_Java_thread()) {
    ShenandoahThreadLocalData::satb_mark_queue(thr).enqueue(obj);
  } else {
    MutexLockerEx x(Shared_SATB_Q_lock, Mutex::_no_safepoint_check_flag);
    _satb_mark_queue_set.shared_satb_queue()->enqueue(obj);
  }
}

#ifdef ASSERT
void ShenandoahBarrierSet::verify_safe_oop(oop p) {
  shenandoah_assert_not_in_cset_except(NULL, p, (p == NULL) || ShenandoahHeap::heap()->cancelled_concgc());
}
#endif

void ShenandoahBarrierSet::on_thread_create(Thread* thread) {
  // Create thread local data
  ShenandoahThreadLocalData::create(thread);
}

void ShenandoahBarrierSet::on_thread_destroy(Thread* thread) {
  // Destroy thread local data
  ShenandoahThreadLocalData::destroy(thread);
}


void ShenandoahBarrierSet::on_thread_attach(JavaThread* thread) {
  assert(!SafepointSynchronize::is_at_safepoint(), "We should not be at a safepoint");
  assert(!ShenandoahThreadLocalData::satb_mark_queue(thread).is_active(), "SATB queue should not be active");
  assert(ShenandoahThreadLocalData::satb_mark_queue(thread).is_empty(), "SATB queue should be empty");
  if (ShenandoahBarrierSet::satb_mark_queue_set().is_active()) {
    ShenandoahThreadLocalData::satb_mark_queue(thread).set_active(true);
  }
  ShenandoahThreadLocalData::set_gc_state(thread, ShenandoahHeap::heap()->gc_state());
}

void ShenandoahBarrierSet::on_thread_detach(JavaThread* thread) {
  ShenandoahThreadLocalData::satb_mark_queue(thread).flush();
  if (UseTLAB && thread->gclab().is_initialized()) {
    thread->gclab().make_parsable(true);
  }
}
