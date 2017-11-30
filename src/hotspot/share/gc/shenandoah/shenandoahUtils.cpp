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
#include "gc/shenandoah/shenandoahUtils.hpp"


ShenandoahGCSession::ShenandoahGCSession(bool is_full_gc) {
  _timer = is_full_gc ? ShenandoahHeap::heap()->full_gc()->gc_timer() :
                        ShenandoahHeap::heap()->gc_timer();

  _timer->register_gc_start();
}

ShenandoahGCSession::~ShenandoahGCSession() {
  _timer->register_gc_end();
}

ShenandoahGCPauseMark::ShenandoahGCPauseMark(uint gc_id, ShenandoahPhaseTimings::Phase phase, SvcGCMarker::reason_type type)
        : _gc_id_mark(gc_id), _svc_gc_mark(type), _is_gc_active_mark(),
          _phase_total(ShenandoahPhaseTimings::total_pause), _phase_this(phase) {
  ShenandoahHeap* sh = ShenandoahHeap::heap();
  sh->shenandoahPolicy()->record_gc_start();
}

ShenandoahGCPauseMark::~ShenandoahGCPauseMark() {
  ShenandoahHeap* sh = ShenandoahHeap::heap();
  sh->shenandoahPolicy()->record_gc_end();
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
