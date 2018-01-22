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
#include "gc/shenandoah/shenandoahConcurrentMark.inline.hpp"
#include "gc/shenandoah/shenandoahConcurrentThread.hpp"
#include "gc/shenandoah/shenandoahCollectorPolicy.hpp"
#include "gc/shenandoah/shenandoahPhaseTimings.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahMonitoringSupport.hpp"
#include "gc/shenandoah/shenandoahPartialGC.hpp"
#include "gc/shenandoah/shenandoahUtils.hpp"
#include "gc/shenandoah/shenandoahWorkerPolicy.hpp"
#include "gc/shenandoah/vm_operations_shenandoah.hpp"
#include "memory/iterator.hpp"
#include "memory/universe.hpp"
#include "runtime/vmThread.hpp"

ShenandoahConcurrentThread::ShenandoahConcurrentThread() :
  ConcurrentGCThread(),
  _alloc_failure_waiters_lock(Mutex::leaf, "ShenandoahAllocFailureGC_lock", true, Monitor::_safepoint_check_always),
  _explicit_gc_waiters_lock(Mutex::leaf, "ShenandoahExplicitGC_lock", true, Monitor::_safepoint_check_always),
  _periodic_task(this),
  _explicit_gc_cause(GCCause::_no_cause_specified)
{
  create_and_start();
  _periodic_task.enroll();
}

ShenandoahConcurrentThread::~ShenandoahConcurrentThread() {
  // This is here so that super is called.
}

void ShenandoahPeriodicTask::task() {
  _thread->handle_force_counters_update();
  _thread->handle_counters_update();
}

void ShenandoahConcurrentThread::run_service() {
  ShenandoahHeap* heap = ShenandoahHeap::heap();

  double last_shrink_time = os::elapsedTime();

  // Shrink period avoids constantly polling regions for shrinking.
  // Having a period 10x lower than the delay would mean we hit the
  // shrinking with lag of less than 1/10-th of true delay.
  // ShenandoahUncommitDelay is in msecs, but shrink_period is in seconds.
  double shrink_period = (double)ShenandoahUncommitDelay / 1000 / 10;

  ShenandoahCollectorPolicy* policy = heap->shenandoahPolicy();

  while (!in_graceful_shutdown() && !should_terminate()) {
    // Figure out if we have pending requests.
    bool alloc_failure_pending = _alloc_failure_gc.is_set();
    bool explicit_gc_requested = _explicit_gc.is_set();

    // Choose which GC mode to run in. The block below should select a single mode.
    GCMode mode = none;
    GCCause::Cause cause = GCCause::_last_gc_cause;

    if (alloc_failure_pending) {
      // Allocation failure takes precedence: we have to deal with it first thing
      mode = stw_full;
      cause = GCCause::_allocation_failure;
      policy->record_allocation_failure_gc();
    } else if (explicit_gc_requested) {
      // Honor explicit GC requests
      mode = ExplicitGCInvokesConcurrent ? concurrent_normal : stw_full;
      cause = _explicit_gc_cause;
      policy->record_explicit_gc();
    } else {
      // Potential normal cycle: ask heuristics if it wants to act
      if (policy->should_start_partial_gc()) {
        mode = concurrent_partial;
        cause = GCCause::_shenandoah_partial_gc;
      } else if (policy->should_start_concurrent_mark(heap->used(), heap->capacity())) {
        mode = concurrent_normal;
        cause = GCCause::_shenandoah_concurrent_gc;
      }
    }

    bool gc_requested = (mode != none);
    assert (!gc_requested || cause != GCCause::_last_gc_cause, "GC cause should be set");

    if (gc_requested) {
      // If GC was requested, we are sampling the counters even without actual triggers
      // from allocation machinery. This captures GC phases more accurately.
      set_forced_counters_update(true);
    }

    switch (mode) {
      case none:
        break;
      case concurrent_partial:
        service_concurrent_partial_cycle(cause);
        break;
      case concurrent_normal:
        service_concurrent_normal_cycle(cause);
        break;
      case stw_full:
        service_stw_full_cycle(cause);
        break;
      default:
        ShouldNotReachHere();
    }

    if (gc_requested) {
      heap->set_used_at_last_gc();

      // Coming out of (cancelled) concurrent GC, reset these for sanity
      if (heap->is_evacuation_in_progress() || heap->is_concurrent_partial_in_progress()) {
        heap->set_evacuation_in_progress_concurrently(false);
      }

      // If this was the explicit GC cycle, notify waiters about it
      if (explicit_gc_requested) {
        notify_explicit_gc_waiters();
      }

      // If this was the allocation failure GC cycle, notify waiters about it
      if (alloc_failure_pending) {
        notify_alloc_failure_waiters();
      }

      // Disable forced counters update, and update counters one more time
      // to capture the state at the end of GC session.
      handle_force_counters_update();
      set_forced_counters_update(false);
    }

    // Try to uncommit stale regions
    double current = os::elapsedTime();
    if (current - last_shrink_time > shrink_period) {
      heap->handle_heap_shrinkage();
      last_shrink_time = current;
    }

    // Wait before performing the next action
    Thread::current()->_ParkEvent->park(ShenandoahControlLoopInterval);

    // Make sure the _do_full_gc flag changes are seen.
    OrderAccess::storeload();
  }

  // Wait for the actual stop(), can't leave run_service() earlier.
  while (!should_terminate()) {
    Thread::current()->_ParkEvent->park(10);
  }
}

void ShenandoahConcurrentThread::service_concurrent_partial_cycle(GCCause::Cause cause) {
  GCIdMark gc_id_mark;
  ShenandoahGCSession session;

  ShenandoahHeap* heap = ShenandoahHeap::heap();
  ShenandoahPartialGC* partial_gc = heap->partial_gc();
  TraceCollectorStats tcs(heap->monitoring_support()->partial_collection_counters());

  heap->vmop_entry_init_partial();

  if (!partial_gc->has_work()) return;
  if (check_cancellation()) return;

  heap->entry_partial();

  if (check_cancellation()) return;

  heap->vmop_entry_final_partial();

  if (check_cancellation()) return;

  heap->entry_cleanup();
}

void ShenandoahConcurrentThread::service_concurrent_normal_cycle(GCCause::Cause cause) {
  // Normal cycle goes via all concurrent phases. If allocation failure (af) happens during
  // any of the concurrent phases, it optionally degenerates to nearest STW operation, and
  // then continues the cycle. Otherwise, allocation failure leads to Full GC.
  //
  // If second allocation failure happens during STW cycle (for example, when GC tries to evac
  // something and no memory is available, cycle degrades to Full GC.
  //
  // There are also two shortcuts through the normal cycle: a) immediate garbage shortcut, when
  // heuristics says there are no regions to compact, and all the collection comes from immediately
  // reclaimable regions; b) coalesced UR shortcut, when heuristics decides to coalesce UR with the
  // mark from the next cycle.
  //
  //                                    (immediate garbage shortcut)
  //                             /-------------------------------------------\
  //                             |                       (coalesced UR)      v
  //                             |                  /------------------------\
  //                             |                  |                        v
  // [START] ----> Conc Mark ----o----> Conc Evac --o--> Conc Update-Refs ---o----> [END]
  //                   |         ^          |                 |              |
  //                   |         |          |                 |              |
  //                   | (af)    |          | (af)            | (af)         |
  //                   |         |          |                 |              |
  //                   v         |          |                 |              |
  //               STW Mark -----/          |          STW Update-Refs ----->o
  //                   |                    |                 |              ^
  //                   | (af)               |                 | (af)         |
  //                   |                    v                 |              |
  //                   \------------------->o<----------------/              |
  //                                        |                                |
  //                                        v                                |
  //                                      Full GC  --------------------------/

  ShenandoahHeap* heap = ShenandoahHeap::heap();

  GCIdMark gc_id_mark;
  ShenandoahGCSession session;

  if (check_cancellation()) return;

  // Cycle started
  heap->shenandoahPolicy()->record_cycle_start();

  // Capture peak occupancy right after starting the cycle
  heap->shenandoahPolicy()->record_peak_occupancy();

  TraceCollectorStats tcs(heap->monitoring_support()->concurrent_collection_counters());
  TraceMemoryManagerStats tmms(false, cause);

  // Start initial mark under STW
  heap->vmop_entry_init_mark();

  if (check_cancellation()) return;

  // Continue concurrent mark
  heap->entry_mark();

  // Possibly hand over remaining marking work to degenerated final-mark phase
  if (heap->cancelled_concgc()) {
    heap->shenandoahPolicy()->record_cm_cancelled();
    if (_alloc_failure_gc.is_set() &&
        heap->shenandoahPolicy()->handover_cancelled_marking()) {
      heap->clear_cancelled_concgc();
      heap->shenandoahPolicy()->record_cm_degenerated();
    } else {
      return;
    }
  } else {
    heap->shenandoahPolicy()->record_cm_success();

    // If not cancelled, can try to concurrently pre-clean
    heap->entry_preclean();
  }

  // Complete marking under STW, and start evacuation
  heap->vmop_entry_final_mark();

  if (check_cancellation()) return;

  // Final mark had reclaimed some immediate garbage, kick cleanup to reclaim the space
  heap->entry_cleanup();

  // Perform concurrent evacuation, if required.
  // This phase can be skipped if there is nothing to evacuate.
  // If so, evac_in_progress would be unset by collection set preparation code.
  if (heap->is_evacuation_in_progress()) {
    heap->entry_evac();

    if (check_cancellation()) return;
  }

  // Perform update-refs phase, if required.
  // This phase can be skipped if there was nothing evacuated. If so, has_forwarded would be unset
  // by collection set preparation code. However, adaptive heuristics need to record "success" when
  // this phase is skipped. Therefore, we conditionally execute all ops, leaving heuristics adjustments
  // intact.
  if (heap->shenandoahPolicy()->should_start_update_refs()) {

    bool do_it = heap->has_forwarded_objects();
    if (do_it) {
      heap->vmop_entry_init_updaterefs();
      heap->entry_updaterefs();

      if (heap->cancelled_concgc()) {
        heap->shenandoahPolicy()->record_uprefs_cancelled();
        if (_alloc_failure_gc.is_set() &&
            heap->shenandoahPolicy()->handover_cancelled_uprefs()) {
          heap->shenandoahPolicy()->record_uprefs_degenerated();
        } else {
          return;
        }
      } else {
        heap->shenandoahPolicy()->record_uprefs_success();
      }

      heap->vmop_entry_final_updaterefs();
    } else {
      heap->shenandoahPolicy()->record_uprefs_success();
    }
  } else {
    // If update-refs were skipped, need to do another verification pass after evacuation.
    heap->vmop_entry_verify_after_evac();
  }

  // Reclaim space and prepare for the next normal cycle:
  heap->entry_cleanup_bitmaps();

  // Cycle is complete
  heap->shenandoahPolicy()->record_cycle_end();

  heap->shenandoahPolicy()->record_success_gc();
}

bool ShenandoahConcurrentThread::check_cancellation() {
  ShenandoahHeap* heap = ShenandoahHeap::heap();
  if (heap->cancelled_concgc()) {
    assert (is_alloc_failure_gc() || in_graceful_shutdown(), "Cancel GC either for alloc failure GC, or gracefully exiting");
    heap->shenandoahPolicy()->record_cancelled_gc();
    return true;
  }
  return false;
}


void ShenandoahConcurrentThread::stop_service() {
  // Nothing to do here.
}

void ShenandoahConcurrentThread::service_stw_full_cycle(GCCause::Cause cause) {
  GCIdMark gc_id_mark;
  ShenandoahGCSession session(/* is_full_gc */true);

  ShenandoahHeap::heap()->vmop_entry_full(cause);
}

void ShenandoahConcurrentThread::handle_explicit_gc(GCCause::Cause cause) {
  assert(GCCause::is_user_requested_gc(cause) || GCCause::is_serviceability_requested_gc(cause),
         "only requested GCs here");
  if (!DisableExplicitGC) {
    _explicit_gc_cause = cause;

    _explicit_gc.set();
    MonitorLockerEx ml(&_explicit_gc_waiters_lock);
    while (_explicit_gc.is_set()) {
      ml.wait();
    }
  }
}

void ShenandoahConcurrentThread::handle_alloc_failure() {
  ShenandoahHeap::heap()->collector_policy()->set_should_clear_all_soft_refs(true);
  assert(current()->is_Java_thread(), "expect Java thread here");

  if (try_set_alloc_failure_gc()) {
    // Now that alloc failure GC is scheduled, we can abort everything else
    ShenandoahHeap::heap()->cancel_concgc(GCCause::_allocation_failure);
  }

  MonitorLockerEx ml(&_alloc_failure_waiters_lock);
  while (is_alloc_failure_gc()) {
    ml.wait();
  }
  assert(!is_alloc_failure_gc(), "expect alloc failure GC to have completed");
}

void ShenandoahConcurrentThread::handle_alloc_failure_evac() {
  log_develop_trace(gc)("Out of memory during evacuation, cancel evacuation, schedule GC by thread %d",
                        Thread::current()->osthread()->thread_id());

  // We ran out of memory during evacuation. Cancel evacuation, and schedule a GC.

  ShenandoahHeap* heap = ShenandoahHeap::heap();
  heap->collector_policy()->set_should_clear_all_soft_refs(true);
  try_set_alloc_failure_gc();
  heap->cancel_concgc(GCCause::_shenandoah_allocation_failure_evac);

  if ((! Thread::current()->is_GC_task_thread()) && (! Thread::current()->is_ConcurrentGC_thread())) {
    assert(! Threads_lock->owned_by_self()
           || SafepointSynchronize::is_at_safepoint(), "must not hold Threads_lock here");
    ResourceMark rm;
    log_info(gc)("%s. Thread \"%s\" waits until evacuation finishes.",
                 GCCause::to_string(GCCause::_shenandoah_allocation_failure_evac),
                 Thread::current()->name());
    while (heap->is_evacuation_in_progress()) { // wait.
      Thread::current()->_ParkEvent->park(1);
    }
  }
}

void ShenandoahConcurrentThread::notify_alloc_failure_waiters() {
  _alloc_failure_gc.unset();
  MonitorLockerEx ml(&_alloc_failure_waiters_lock);
  ml.notify_all();
}

bool ShenandoahConcurrentThread::try_set_alloc_failure_gc() {
  return _alloc_failure_gc.try_set();
}

bool ShenandoahConcurrentThread::is_alloc_failure_gc() {
  return _alloc_failure_gc.is_set();
}

void ShenandoahConcurrentThread::notify_explicit_gc_waiters() {
  _explicit_gc.unset();
  MonitorLockerEx ml(&_explicit_gc_waiters_lock);
  ml.notify_all();
}

void ShenandoahConcurrentThread::handle_counters_update() {
  if (_do_counters_update.is_set()) {
    _do_counters_update.unset();
    ShenandoahHeap::heap()->monitoring_support()->update_counters();
  }
}

void ShenandoahConcurrentThread::handle_force_counters_update() {
  if (_force_counters_update.is_set()) {
    _do_counters_update.unset(); // reset these too, we do update now!
    ShenandoahHeap::heap()->monitoring_support()->update_counters();
  }
}

void ShenandoahConcurrentThread::trigger_counters_update() {
  if (_do_counters_update.is_unset()) {
    _do_counters_update.set();
  }
}

void ShenandoahConcurrentThread::set_forced_counters_update(bool value) {
  _force_counters_update.set_cond(value);
}

void ShenandoahConcurrentThread::print() const {
  print_on(tty);
}

void ShenandoahConcurrentThread::print_on(outputStream* st) const {
  st->print("Shenandoah Concurrent Thread");
  Thread::print_on(st);
  st->cr();
}

void ShenandoahConcurrentThread::start() {
  create_and_start();
}

void ShenandoahConcurrentThread::prepare_for_graceful_shutdown() {
  _graceful_shutdown.set();
}

bool ShenandoahConcurrentThread::in_graceful_shutdown() {
  return _graceful_shutdown.is_set();
}
