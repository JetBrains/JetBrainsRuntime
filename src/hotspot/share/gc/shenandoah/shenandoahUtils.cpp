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

#include "gc/shared/gcTimer.hpp"
#include "gc/shenandoah/shenandoahAllocTracker.hpp"
#include "gc/shenandoah/shenandoahCollectorPolicy.hpp"
#include "gc/shenandoah/shenandoahMarkCompact.hpp"
#include "gc/shenandoah/shenandoahHeap.hpp"
#include "gc/shenandoah/shenandoahHeuristics.hpp"
#include "gc/shenandoah/shenandoahUtils.hpp"


ShenandoahGCSession::ShenandoahGCSession() {
  ShenandoahHeap* sh = ShenandoahHeap::heap();
  _timer = sh->gc_timer();
  _timer->register_gc_start();
  sh->shenandoahPolicy()->record_cycle_start();
  sh->heuristics()->record_cycle_start();
  _trace_cycle.initialize(sh->cycle_memory_manager(), sh->gc_cause(),
          /* allMemoryPoolsAffected */    true,
          /* recordGCBeginTime = */       true,
          /* recordPreGCUsage = */        true,
          /* recordPeakUsage = */         true,
          /* recordPostGCUsage = */       true,
          /* recordAccumulatedGCTime = */ true,
          /* recordGCEndTime = */         true,
          /* countCollection = */         true
  );
}

ShenandoahGCSession::~ShenandoahGCSession() {
  ShenandoahHeap::heap()->heuristics()->record_cycle_end();
  _timer->register_gc_end();
}

ShenandoahGCPauseMark::ShenandoahGCPauseMark(uint gc_id, SvcGCMarker::reason_type type) :
  _gc_id_mark(gc_id), _svc_gc_mark(type), _is_gc_active_mark() {
  ShenandoahHeap* sh = ShenandoahHeap::heap();

  _trace_pause.initialize(sh->stw_memory_manager(), sh->gc_cause(),
          /* allMemoryPoolsAffected */    true,
          /* recordGCBeginTime = */       true,
          /* recordPreGCUsage = */        false,
          /* recordPeakUsage = */         false,
          /* recordPostGCUsage = */       false,
          /* recordAccumulatedGCTime = */ true,
          /* recordGCEndTime = */         true,
          /* countCollection = */         true
  );

  sh->heuristics()->record_gc_start();
}

ShenandoahGCPauseMark::~ShenandoahGCPauseMark() {
  ShenandoahHeap* sh = ShenandoahHeap::heap();
  sh->heuristics()->record_gc_end();
}

ShenandoahGCPhase::ShenandoahGCPhase(const ShenandoahPhaseTimings::Phase phase) :
  _phase(phase) {
  ShenandoahHeap::heap()->phase_timings()->record_phase_start(_phase);
}

ShenandoahGCPhase::~ShenandoahGCPhase() {
  ShenandoahHeap::heap()->phase_timings()->record_phase_end(_phase);
}

ShenandoahAllocTrace::ShenandoahAllocTrace(size_t words_size, ShenandoahHeap::AllocType alloc_type) {
  if (ShenandoahAllocationTrace) {
    _start = os::elapsedTime();
    _size = words_size;
    _alloc_type = alloc_type;
  } else {
    _start = 0;
    _size = 0;
    _alloc_type = ShenandoahHeap::AllocType(0);
  }
}

ShenandoahAllocTrace::~ShenandoahAllocTrace() {
  if (ShenandoahAllocationTrace) {
    double stop = os::elapsedTime();
    double duration_sec = stop - _start;
    double duration_us = duration_sec * 1000000;
    ShenandoahAllocTracker* tracker = ShenandoahHeap::heap()->alloc_tracker();
    assert(tracker != NULL, "Must be");
    tracker->record_alloc_latency(_size, _alloc_type, duration_us);
    if (duration_us > ShenandoahAllocationStallThreshold) {
      log_warning(gc)("Allocation stall: %.0f us (threshold: " INTX_FORMAT " us)",
                      duration_us, ShenandoahAllocationStallThreshold);
    }
  }
}

ShenandoahWorkerSession::ShenandoahWorkerSession(uint worker_id) {
  Thread* thr = Thread::current();
  assert(ShenandoahThreadLocalData::worker_id(thr) == ShenandoahThreadLocalData::INVALID_WORKER_ID, "Already set");
  ShenandoahThreadLocalData::set_worker_id(thr, worker_id);
}

ShenandoahWorkerSession::~ShenandoahWorkerSession() {
#ifdef ASSERT
  Thread* thr = Thread::current();
  assert(ShenandoahThreadLocalData::worker_id(thr) != ShenandoahThreadLocalData::INVALID_WORKER_ID, "Must be set");
  ShenandoahThreadLocalData::set_worker_id(thr, ShenandoahThreadLocalData::INVALID_WORKER_ID);
#endif
}

