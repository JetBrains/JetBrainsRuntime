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
#include "gc/shared/gcTraceTime.inline.hpp"
#include "gc/shared/vmGCOperations.hpp"
#include "gc/shenandoah/shenandoahConcurrentMark.inline.hpp"
#include "gc/shenandoah/shenandoahPhaseTimings.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahMarkCompact.hpp"
#include "gc/shenandoah/shenandoahPartialGC.hpp"
#include "gc/shenandoah/shenandoahUtils.hpp"
#include "gc/shenandoah/shenandoahVerifier.hpp"
#include "gc/shenandoah/shenandoahWorkGroup.hpp"
#include "gc/shenandoah/shenandoahWorkerPolicy.hpp"
#include "gc/shenandoah/vm_operations_shenandoah.hpp"

void VM_ShenandoahInitMark::doit() {
  ShenandoahGCPauseMark mark(_gc_id, ShenandoahPhaseTimings::init_mark, SvcGCMarker::OTHER);

  ShenandoahHeap* sh = ShenandoahHeap::heap();

  GCTraceTime(Info, gc) time("Pause Init Mark", sh->gc_timer());
  WorkGang* workers = sh->workers();

  // Calculate workers for initial marking
  uint nworkers = ShenandoahWorkerPolicy::calc_workers_for_init_marking();

  ShenandoahWorkerScope scope(workers, nworkers);

  assert(sh->is_next_bitmap_clear(), "need clear marking bitmap");

  sh->start_concurrent_marking();
}

void VM_ShenandoahFullGC::doit() {
  ShenandoahGCPauseMark mark(_gc_id, ShenandoahPhaseTimings::full_gc, SvcGCMarker::FULL);
  ShenandoahMarkCompact::do_mark_compact(_gc_cause);
}

bool VM_ShenandoahReferenceOperation::doit_prologue() {
  Heap_lock->lock();
  return true;
}

void VM_ShenandoahReferenceOperation::doit_epilogue() {
  if (Universe::has_reference_pending_list()) {
    Heap_lock->notify_all();
  }
  Heap_lock->unlock();
}

void VM_ShenandoahFinalMarkStartEvac::doit() {
  ShenandoahGCPauseMark mark(_gc_id, ShenandoahPhaseTimings::final_mark, SvcGCMarker::OTHER);

  ShenandoahHeap *sh = ShenandoahHeap::heap();

  // It is critical that we
  // evacuate roots right after finishing marking, so that we don't
  // get unmarked objects in the roots.
  // Setup workers for final marking
  WorkGang* workers = sh->workers();
  uint n_workers = ShenandoahWorkerPolicy::calc_workers_for_final_marking();
  ShenandoahWorkerScope scope(workers, n_workers);

  if (! sh->cancelled_concgc()) {
    GCTraceTime(Info, gc) time("Pause Final Mark", sh->gc_timer());
    sh->concurrentMark()->finish_mark_from_roots();
    sh->stop_concurrent_marking();

    {
      ShenandoahGCPhase prepare_evac(ShenandoahPhaseTimings::prepare_evac);
      sh->prepare_for_concurrent_evacuation();
    }

    // If collection set has candidates, start evacuation.
    // Otherwise, bypass the rest of the cycle.
    if (!sh->collection_set()->is_empty()) {
      sh->set_evacuation_in_progress_at_safepoint(true);
      // From here on, we need to update references.
      sh->set_need_update_refs(true);

      ShenandoahGCPhase init_evac(ShenandoahPhaseTimings::init_evac);
      sh->evacuate_and_update_roots();
    }
  } else {
    GCTraceTime(Info, gc) time("Cancel Concurrent Mark", sh->gc_timer(), GCCause::_no_gc, true);
    sh->concurrentMark()->cancel();
    sh->stop_concurrent_marking();
  }
}

void VM_ShenandoahInitPartialGC::doit() {
  ShenandoahGCPauseMark mark(_gc_id, ShenandoahPhaseTimings::init_partial_gc, SvcGCMarker::MINOR);

  ShenandoahHeap* sh = ShenandoahHeap::heap();
  GCTraceTime(Info, gc) time("Pause Init Partial", sh->gc_timer());

  sh->partial_gc()->init_partial_collection();
}

void VM_ShenandoahFinalPartialGC::doit() {
  ShenandoahGCPauseMark mark(_gc_id, ShenandoahPhaseTimings::final_partial_gc, SvcGCMarker::MINOR);

  ShenandoahHeap* sh = ShenandoahHeap::heap();
  GCTraceTime(Info, gc) time("Pause Final Partial", sh->gc_timer());

  sh->partial_gc()->final_partial_collection();
}

void VM_ShenandoahInitUpdateRefs::doit() {
  ShenandoahGCPauseMark mark(_gc_id, ShenandoahPhaseTimings::init_update_refs, SvcGCMarker::OTHER);

  ShenandoahHeap *sh = ShenandoahHeap::heap();
  GCTraceTime(Info, gc) time("Pause Init Update Refs", sh->gc_timer());

  sh->prepare_update_refs();
}

void VM_ShenandoahFinalUpdateRefs::doit() {
  ShenandoahGCPauseMark mark(_gc_id, ShenandoahPhaseTimings::final_update_refs, SvcGCMarker::OTHER);

  ShenandoahHeap *sh = ShenandoahHeap::heap();
  GCTraceTime(Info, gc) time("Pause Final Update Refs", sh->gc_timer());
  WorkGang* workers = sh->workers();
  uint n_workers = ShenandoahWorkerPolicy::calc_workers_for_final_update_ref();
  ShenandoahWorkerScope scope(workers, n_workers);

  sh->finish_update_refs();
}

void VM_ShenandoahVerifyHeapAfterEvacuation::doit() {
  ShenandoahGCPauseMark mark(_gc_id, ShenandoahPhaseTimings::pause_other, SvcGCMarker::OTHER);
  ShenandoahHeap::heap()->verifier()->verify_after_evacuation();
}
