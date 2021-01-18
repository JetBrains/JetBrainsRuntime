/*
 * Copyright (c) 2013, 2018, Red Hat, Inc. All rights reserved.
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
#include "gc/shenandoah/shenandoahAsserts.hpp"
#include "gc/shenandoah/shenandoahBarrierSet.hpp"
#include "gc/shenandoah/shenandoahBarrierSetClone.inline.hpp"
#include "gc/shenandoah/shenandoahBarrierSetAssembler.hpp"
#include "gc/shenandoah/shenandoahCollectorPolicy.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/heuristics/shenandoahHeuristics.hpp"
#include "memory/iterator.inline.hpp"
#include "runtime/interfaceSupport.inline.hpp"
#ifdef COMPILER1
#include "gc/shenandoah/c1/shenandoahBarrierSetC1.hpp"
#endif
#ifdef COMPILER2
#include "gc/shenandoah/c2/shenandoahBarrierSetC2.hpp"
#endif

class ShenandoahBarrierSetC1;
class ShenandoahBarrierSetC2;

ShenandoahSATBMarkQueueSet ShenandoahBarrierSet::_satb_mark_queue_set;

ShenandoahBarrierSet::ShenandoahBarrierSet(ShenandoahHeap* heap) :
  BarrierSet(make_barrier_set_assembler<ShenandoahBarrierSetAssembler>(),
             make_barrier_set_c1<ShenandoahBarrierSetC1>(),
             make_barrier_set_c2<ShenandoahBarrierSetC2>(),
             BarrierSet::FakeRtti(BarrierSet::ShenandoahBarrierSet)),
  _heap(heap)
{
}

ShenandoahBarrierSetAssembler* ShenandoahBarrierSet::assembler() {
  BarrierSetAssembler* const bsa = BarrierSet::barrier_set()->barrier_set_assembler();
  return reinterpret_cast<ShenandoahBarrierSetAssembler*>(bsa);
}

void ShenandoahBarrierSet::print_on(outputStream* st) const {
  st->print("ShenandoahBarrierSet");
}

bool ShenandoahBarrierSet::is_a(BarrierSet::Name bsn) {
  return bsn == BarrierSet::ShenandoahBarrierSet;
}

bool ShenandoahBarrierSet::is_aligned(HeapWord* hw) {
  return true;
}

oop ShenandoahBarrierSet::load_reference_barrier_not_null(oop obj) {
  if (ShenandoahLoadRefBarrier && _heap->has_forwarded_objects()) {
    return load_reference_barrier_impl(obj);
  } else {
    return obj;
  }
}

oop ShenandoahBarrierSet::load_reference_barrier(oop obj) {
  if (obj != NULL) {
    return load_reference_barrier_not_null(obj);
  } else {
    return obj;
  }
}

bool ShenandoahBarrierSet::need_load_reference_barrier(DecoratorSet decorators, BasicType type) {
  if (!ShenandoahLoadRefBarrier) return false;
  // Only needed for references
  return is_reference_type(type);
}

bool ShenandoahBarrierSet::need_keep_alive_barrier(DecoratorSet decorators,BasicType type) {
  if (!ShenandoahSATBBarrier) return false;
  // Only needed for references
  if (!is_reference_type(type)) return false;

  bool keep_alive = (decorators & AS_NO_KEEPALIVE) == 0;
  bool unknown = (decorators & ON_UNKNOWN_OOP_REF) != 0;
  bool on_weak_ref = (decorators & (ON_WEAK_OOP_REF | ON_PHANTOM_OOP_REF)) != 0;
  return (on_weak_ref || unknown) && keep_alive;
}

oop ShenandoahBarrierSet::load_reference_barrier_impl(oop obj) {
  assert(ShenandoahLoadRefBarrier, "should be enabled");
  if (!CompressedOops::is_null(obj)) {
    bool evac_in_progress = _heap->is_evacuation_in_progress();
    oop fwd = resolve_forwarded_not_null(obj);
    if (evac_in_progress &&
        _heap->in_collection_set(obj) &&
        obj == fwd) {
      Thread *t = Thread::current();
      ShenandoahEvacOOMScope oom_evac_scope;
      return _heap->evacuate_object(obj, t);
    } else {
      return fwd;
    }
  } else {
    return obj;
  }
}

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
  ShenandoahThreadLocalData::set_gc_state(thread, _heap->gc_state());
  ShenandoahThreadLocalData::initialize_gclab(thread);
}

void ShenandoahBarrierSet::on_thread_detach(JavaThread* thread) {
  ShenandoahThreadLocalData::satb_mark_queue(thread).flush();
  PLAB* gclab = ShenandoahThreadLocalData::gclab(thread);
  if (gclab != NULL) {
    gclab->retire();
  }
}

void ShenandoahBarrierSet::clone_barrier_runtime(oop src) {
  if (_heap->has_forwarded_objects() || (ShenandoahIUBarrier && _heap->is_concurrent_mark_in_progress())) {
    clone_barrier(src);
  }
}

