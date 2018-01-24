/*
 * Copyright (c) 2013, 2015, Red Hat, Inc. and/or its affiliates.
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
#include "gc/g1/g1SATBCardTableModRefBS.hpp"
#include "gc/shenandoah/shenandoahBarrierSet.hpp"
#include "gc/shenandoah/shenandoahCollectorPolicy.hpp"
#include "gc/shenandoah/shenandoahConnectionMatrix.inline.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "runtime/interfaceSupport.hpp"

template <bool UPDATE_MATRIX, bool STOREVAL_WRITE_BARRIER, bool ALWAYS_ENQUEUE>
class ShenandoahUpdateRefsForOopClosure: public ExtendedOopClosure {
private:
  ShenandoahHeap* _heap;
  template <class T>
  inline void do_oop_work(T* p) {
    oop o;
    if (STOREVAL_WRITE_BARRIER) {
      bool evac;
      o = _heap->evac_update_oop_ref(p, evac);
      if ((ALWAYS_ENQUEUE || evac) && !oopDesc::is_null(o)) {
        ShenandoahBarrierSet::enqueue(o);
      }
    } else {
      o = _heap->maybe_update_oop_ref(p);
    }
    if (UPDATE_MATRIX && !oopDesc::is_null(o)) {
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
  BarrierSet(BarrierSet::FakeRtti(BarrierSet::ShenandoahBarrierSet)),
  _heap(heap)
{
}

void ShenandoahBarrierSet::print_on(outputStream* st) const {
  st->print("ShenandoahBarrierSet");
}

bool ShenandoahBarrierSet::is_a(BarrierSet::Name bsn) {
  return bsn == BarrierSet::ShenandoahBarrierSet;
}

bool ShenandoahBarrierSet::has_read_prim_array_opt() {
  return true;
}

bool ShenandoahBarrierSet::has_read_prim_barrier() {
  return false;
}

bool ShenandoahBarrierSet::has_read_ref_array_opt() {
  return true;
}

bool ShenandoahBarrierSet::has_read_ref_barrier() {
  return false;
}

bool ShenandoahBarrierSet::has_read_region_opt() {
  return true;
}

bool ShenandoahBarrierSet::has_write_prim_array_opt() {
  return true;
}

bool ShenandoahBarrierSet::has_write_prim_barrier() {
  return false;
}

bool ShenandoahBarrierSet::has_write_ref_array_opt() {
  return true;
}

bool ShenandoahBarrierSet::has_write_ref_barrier() {
  return true;
}

bool ShenandoahBarrierSet::has_write_ref_pre_barrier() {
  return true;
}

bool ShenandoahBarrierSet::has_write_region_opt() {
  return true;
}

bool ShenandoahBarrierSet::is_aligned(HeapWord* hw) {
  return true;
}

void ShenandoahBarrierSet::read_prim_array(MemRegion mr) {
  Unimplemented();
}

void ShenandoahBarrierSet::read_prim_field(HeapWord* hw, size_t s){
  Unimplemented();
}

bool ShenandoahBarrierSet::read_prim_needs_barrier(HeapWord* hw, size_t s) {
  return false;
}

void ShenandoahBarrierSet::read_ref_array(MemRegion mr) {
  Unimplemented();
}

void ShenandoahBarrierSet::read_ref_field(void* v) {
  //    tty->print_cr("read_ref_field: v = "PTR_FORMAT, v);
  // return *v;
}

bool ShenandoahBarrierSet::read_ref_needs_barrier(void* v) {
  Unimplemented();
  return false;
}

void ShenandoahBarrierSet::read_region(MemRegion mr) {
  Unimplemented();
}

void ShenandoahBarrierSet::resize_covered_region(MemRegion mr) {
  Unimplemented();
}

void ShenandoahBarrierSet::write_prim_array(MemRegion mr) {
  Unimplemented();
}

void ShenandoahBarrierSet::write_prim_field(HeapWord* hw, size_t s , juint x, juint y) {
  Unimplemented();
}

bool ShenandoahBarrierSet::write_prim_needs_barrier(HeapWord* hw, size_t s, juint x, juint y) {
  Unimplemented();
  return false;
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

template <class T, bool UPDATE_MATRIX, bool STOREVAL_WRITE_BARRIER, bool ALWAYS_ENQUEUE>
void ShenandoahBarrierSet::write_ref_array_loop(HeapWord* start, size_t count) {
  assert(UseShenandoahGC && ShenandoahCloneBarrier, "should be enabled");
  ShenandoahUpdateRefsForOopClosure<UPDATE_MATRIX, STOREVAL_WRITE_BARRIER, ALWAYS_ENQUEUE> cl;
  T* dst = (T*) start;
  for (size_t i = 0; i < count; i++) {
    cl.do_oop(dst++);
  }
}

void ShenandoahBarrierSet::write_ref_array(HeapWord* start, size_t count) {
  assert(UseShenandoahGC, "should be enabled");
  if (!ShenandoahCloneBarrier) return;
  if (!need_update_refs_barrier()) return;

  if (UseCompressedOops) {
    if (UseShenandoahMatrix) {
      if (_heap->is_concurrent_partial_in_progress()) {
        write_ref_array_loop<narrowOop, true, true, false>(start, count);
      } else {
        write_ref_array_loop<narrowOop, true, false, false>(start, count);
      }
    } else {
      assert(! _heap->is_concurrent_partial_in_progress(), "partial GC needs matrix");
      if (!_heap->is_concurrent_traversal_in_progress()) {
        write_ref_array_loop<narrowOop, false, true, true>(start, count);
      } else {
        write_ref_array_loop<narrowOop, false, false, false>(start, count);
      }
    }
  } else {
    if (UseShenandoahMatrix) {
      if (_heap->is_concurrent_partial_in_progress()) {
        write_ref_array_loop<oop, true, true, false>(start, count);
      } else {
        write_ref_array_loop<oop, true, false, false>(start, count);
      }
    } else {
      assert(! _heap->is_concurrent_partial_in_progress(), "partial GC needs matrix");
      if (_heap->is_concurrent_traversal_in_progress()) {
        write_ref_array_loop<oop, false, true, true>(start, count);
      } else {
        write_ref_array_loop<oop, false, false, false>(start, count);
      }
    }
  }
}

template <class T>
void ShenandoahBarrierSet::write_ref_array_pre_work(T* dst, int count) {

#ifdef ASSERT
    if (_heap->is_in(dst) &&
        _heap->in_collection_set(dst) &&
        ! _heap->cancelled_concgc()) {
      tty->print_cr("dst = "PTR_FORMAT, p2i(dst));
      _heap->heap_region_containing((HeapWord*) dst)->print();
      assert(false, "We should have fixed this earlier");
    }
#endif

  if (ShenandoahSATBBarrier ||
      (ShenandoahConditionalSATBBarrier && _heap->is_concurrent_mark_in_progress())) {
    T* elem_ptr = dst;
    for (int i = 0; i < count; i++, elem_ptr++) {
      T heap_oop = oopDesc::load_heap_oop(elem_ptr);
      if (!oopDesc::is_null(heap_oop)) {
        enqueue(oopDesc::decode_heap_oop_not_null(heap_oop));
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
#ifdef ASSERT
  {
    if (_heap->is_in(field) &&
        _heap->in_collection_set(field) &&
        ! _heap->cancelled_concgc()) {
      tty->print_cr("field = "PTR_FORMAT, p2i(field));
      tty->print_cr("in_cset: %s", BOOL_TO_STR(_heap->in_collection_set(field)));
      _heap->heap_region_containing((HeapWord*)field)->print();
      tty->print_cr("marking: %s, evacuating: %s",
                    BOOL_TO_STR(_heap->is_concurrent_mark_in_progress()),
                    BOOL_TO_STR(_heap->is_evacuation_in_progress()));
      assert(false, "We should have fixed this earlier");
    }
  }
#endif

  if (_heap->is_concurrent_mark_in_progress()) {
    T heap_oop = oopDesc::load_heap_oop(field);
    if (!oopDesc::is_null(heap_oop)) {
      enqueue(oopDesc::decode_heap_oop(heap_oop));
    }
  }
  if (UseShenandoahMatrix && ! oopDesc::is_null(new_val)) {
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
#ifdef ASSERT
  ShenandoahHeap* heap = ShenandoahHeap::heap();
  if (!(heap->cancelled_concgc() || !heap->in_collection_set(v))) {
    tty->print_cr("field not in collection set: "PTR_FORMAT, p2i(v));
    tty->print_cr("containing heap region:");
    ShenandoahHeap::heap()->heap_region_containing(v)->print();
  }
  assert(heap->cancelled_concgc() || !heap->in_collection_set(v), "only write to to-space");
  if (_heap->is_concurrent_mark_in_progress()) {
    assert(o == NULL || oopDesc::unsafe_equals(o, resolve_oop_static(o)), "only write to-space values");
    assert(o == NULL || !heap->in_collection_set(o), "only write to-space values");
  }
#endif
}

void ShenandoahBarrierSet::write_region_work(MemRegion mr) {
  assert(UseShenandoahGC, "should be enabled");
  if (!ShenandoahCloneBarrier) return;
  if (! need_update_refs_barrier()) return;

  // This is called for cloning an object (see jvm.cpp) after the clone
  // has been made. We are not interested in any 'previous value' because
  // it would be NULL in any case. But we *are* interested in any oop*
  // that potentially need to be updated.

  oop obj = oop(mr.start());
  assert(oopDesc::is_oop(obj), "must be an oop");
  if (UseShenandoahMatrix) {
    if (_heap->is_concurrent_partial_in_progress()) {
      ShenandoahUpdateRefsForOopClosure<true, true, false> cl;
      obj->oop_iterate(&cl);
    } else {
      ShenandoahUpdateRefsForOopClosure<true, false, false> cl;
      obj->oop_iterate(&cl);
    }
  } else {
    assert(! _heap->is_concurrent_partial_in_progress(), "partial GC needs matrix");
    if (_heap->is_concurrent_traversal_in_progress()) {
      ShenandoahUpdateRefsForOopClosure<false, true, true> cl;
      obj->oop_iterate(&cl);
    } else {
      ShenandoahUpdateRefsForOopClosure<false, false, false> cl;
      obj->oop_iterate(&cl);
    }
  }
}

oop ShenandoahBarrierSet::read_barrier(oop src) {
  if (ShenandoahReadBarrier) {
    return ShenandoahBarrierSet::resolve_oop_static(src);
  } else {
    return src;
  }
}

bool ShenandoahBarrierSet::obj_equals(oop obj1, oop obj2) {
  bool eq = oopDesc::unsafe_equals(obj1, obj2);
  if (! eq && ShenandoahAcmpBarrier) {
    OrderAccess::loadload();
    obj1 = resolve_oop_static(obj1);
    obj2 = resolve_oop_static(obj2);
    eq = oopDesc::unsafe_equals(obj1, obj2);
  }
  return eq;
}

bool ShenandoahBarrierSet::obj_equals(narrowOop obj1, narrowOop obj2) {
  return obj_equals(oopDesc::decode_heap_oop(obj1), oopDesc::decode_heap_oop(obj2));
}

#ifdef ASSERT
bool ShenandoahBarrierSet::is_safe(oop o) {
  if (o == NULL) return true;
  if (_heap->in_collection_set(o) && ! _heap->cancelled_concgc()) {
    return false;
  }
  if (! oopDesc::unsafe_equals(o, read_barrier(o))) {
    return false;
  }
  return true;
}

bool ShenandoahBarrierSet::is_safe(narrowOop o) {
  return is_safe(oopDesc::decode_heap_oop(o));
}
#endif

JRT_LEAF(oopDesc*, ShenandoahBarrierSet::write_barrier_JRT(oopDesc* src))
  oop result = ((ShenandoahBarrierSet*)oopDesc::bs())->write_barrier(src);
  return (oopDesc*) result;
JRT_END

IRT_LEAF(oopDesc*, ShenandoahBarrierSet::write_barrier_IRT(oopDesc* src))
  oop result = ((ShenandoahBarrierSet*)oopDesc::bs())->write_barrier(src);
  return (oopDesc*) result;
IRT_END

oop ShenandoahBarrierSet::write_barrier_impl(oop obj) {
  assert(UseShenandoahGC && (ShenandoahWriteBarrier || ShenandoahStoreValWriteBarrier), "should be enabled");
  if (!oopDesc::is_null(obj)) {
    bool evac_in_progress = _heap->is_evacuation_in_progress();
    OrderAccess::loadload();
    oop fwd = resolve_oop_static_not_null(obj);
    if (evac_in_progress &&
        _heap->in_collection_set(obj) &&
        oopDesc::unsafe_equals(obj, fwd)) {
      bool evac;
      oop copy = _heap->evacuate_object(obj, Thread::current(), evac, true /* from write barrier */);
      if (evac && _heap->is_concurrent_partial_in_progress()) {
        enqueue(copy);
      }
      return copy;
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
  if (ShenandoahStoreValWriteBarrier || ShenandoahStoreValEnqueueBarrier) {
    obj = write_barrier(obj);
  }
  if (ShenandoahStoreValEnqueueBarrier && !oopDesc::is_null(obj)) {
    enqueue(obj);
  }
  if (ShenandoahStoreValReadBarrier) {
    obj = resolve_oop_static(obj);
  }
  return obj;
}

void ShenandoahBarrierSet::keep_alive_barrier(oop obj) {
  if (ShenandoahKeepAliveBarrier) {
    if (_heap->is_concurrent_mark_in_progress()) {
      enqueue(obj);
    } else if (_heap->is_concurrent_partial_in_progress()) {
      write_barrier_impl(obj);
    }
  }
}

void ShenandoahBarrierSet::enqueue(oop obj) {

#ifdef ASSERT
  if (ShenandoahHeap::heap()->is_concurrent_traversal_in_progress()) {
    assert(oopDesc::unsafe_equals(obj, ShenandoahBarrierSet::resolve_oop_static_not_null(obj)), "only enqueue to-space oops");
    assert(!ShenandoahHeap::heap()->in_collection_set(obj), "no cset objects please");
  }
#endif

  G1SATBCardTableModRefBS::enqueue(obj);
}

#ifdef ASSERT
void ShenandoahBarrierSet::verify_safe_oop(oop p) {
  ShenandoahHeap* heap = ShenandoahHeap::heap();
  if (p == NULL) return;
  if (heap->in_collection_set(p) &&
      ! heap->cancelled_concgc()) {
    tty->print_cr("oop = "PTR_FORMAT", resolved: "PTR_FORMAT", marked-next %s, marked-complete: %s",
                  p2i(p),
                  p2i(read_barrier(p)),
                  BOOL_TO_STR(heap->is_marked_next(p)),
                  BOOL_TO_STR(heap->is_marked_complete(p)));
    tty->print_cr("in_cset: %s", BOOL_TO_STR(heap->in_collection_set(p)));
    heap->heap_region_containing((HeapWord*) p)->print();
    tty->print_cr("top-at-mark-start: %p", heap->next_top_at_mark_start((HeapWord*) p));
    tty->print_cr("top-at-prev-mark-start: %p", heap->complete_top_at_mark_start((HeapWord*) p));
    tty->print_cr("marking: %s, evacuating: %s", BOOL_TO_STR(heap->is_concurrent_mark_in_progress()), BOOL_TO_STR(heap->is_evacuation_in_progress()));
    assert(false, "We should have fixed this earlier");
  }
}
#endif
