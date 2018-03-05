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
#include "gc/shenandoah/shenandoahTraversalGC.hpp"
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
  _explicit_gc_cause(GCCause::_no_cause_specified),
  _degen_point(ShenandoahHeap::_degenerated_outside_cycle)
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
    ShenandoahHeap::ShenandoahDegenPoint degen_point = ShenandoahHeap::_degenerated_unset;

    if (alloc_failure_pending) {
      // Allocation failure takes precedence: we have to deal with it first thing
      cause = GCCause::_allocation_failure;

      // Consume the degen point, and seed it with default value
      degen_point = _degen_point;
      _degen_point = ShenandoahHeap::_degenerated_outside_cycle;

      if (ShenandoahDegeneratedGC && policy->should_degenerate_cycle()) {
        policy->record_alloc_failure_to_degenerated(degen_point);
        mode = stw_degenerated;
      } else {
        policy->record_alloc_failure_to_full();
        mode = stw_full;
      }

    } else if (explicit_gc_requested) {
      // Honor explicit GC requests
      if (ExplicitGCInvokesConcurrent) {
        policy->record_explicit_to_concurrent();
        mode = concurrent_normal;
      } else {
        policy->record_explicit_to_full();
        mode = stw_full;
      }
      cause = _explicit_gc_cause;
    } else {
      // Potential normal cycle: ask heuristics if it wants to act
      if (policy->should_start_partial_gc()) {
        mode = concurrent_partial;
        cause = GCCause::_shenandoah_partial_gc;
      } else if (policy->should_start_traversal_gc()) {
        mode = concurrent_traversal;
        cause = GCCause::_shenandoah_traversal_gc;
      } else if (policy->should_start_concurrent_mark(heap->used(), heap->capacity())) {
        mode = concurrent_normal;
        cause = GCCause::_shenandoah_concurrent_gc;
      }

      // Ask policy if this cycle wants to process references or unload classes
      heap->set_process_references(policy->should_process_references());
      heap->set_unload_classes(policy->should_unload_classes());
    }

    bool gc_requested = (mode != none);
    assert (!gc_requested || cause != GCCause::_last_gc_cause, "GC cause should be set");

    if (gc_requested) {
      heap->reset_bytes_allocated_since_gc_start();

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
      case concurrent_traversal:
        service_concurrent_traversal_cycle(cause);
        break;
      case concurrent_normal:
        service_concurrent_normal_cycle(cause);
        break;
      case stw_degenerated:
        service_stw_degenerated_cycle(cause, degen_point);
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

        // Explicit GC tries to uncommit everything
        heap->handle_heap_shrinkage(os::elapsedTime());
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
      heap->handle_heap_shrinkage(current - (ShenandoahUncommitDelay / 1000.0));
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
  ShenandoahHeap* heap = ShenandoahHeap::heap();
  ShenandoahPartialGC* partial_gc = heap->partial_gc();

  if (check_cancellation_or_degen(ShenandoahHeap::_degenerated_outside_cycle)) return;

  GCIdMark gc_id_mark;
  ShenandoahGCSession session;

  TraceCollectorStats tcs(heap->monitoring_support()->partial_collection_counters());

  heap->vmop_entry_init_partial();
  if (check_cancellation_or_degen(ShenandoahHeap::_degenerated_partial)) return;

  if (!partial_gc->has_work()) return;

  heap->entry_partial();
  if (check_cancellation_or_degen(ShenandoahHeap::_degenerated_partial)) return;

  heap->vmop_entry_final_partial();
  if (check_cancellation_or_degen(ShenandoahHeap::_degenerated_partial)) return;

  heap->entry_cleanup();

  heap->shenandoahPolicy()->record_success_partial();
}

void ShenandoahConcurrentThread::service_concurrent_traversal_cycle(GCCause::Cause cause) {
  GCIdMark gc_id_mark;
  ShenandoahGCSession session;

  ShenandoahHeap* heap = ShenandoahHeap::heap();
  TraceCollectorStats tcs(heap->monitoring_support()->concurrent_collection_counters());

  heap->vmop_entry_init_traversal();

  if (check_cancellation_or_degen(ShenandoahHeap::_degenerated_traversal)) return;

  heap->entry_traversal();

  if (check_cancellation_or_degen(ShenandoahHeap::_degenerated_traversal)) return;

  heap->vmop_entry_final_traversal();

  if (check_cancellation_or_degen(ShenandoahHeap::_degenerated_traversal)) return;

  heap->entry_cleanup_bitmaps();
}

void ShenandoahConcurrentThread::service_concurrent_normal_cycle(GCCause::Cause cause) {
  // Normal cycle goes via all concurrent phases. If allocation failure (af) happens during
  // any of the concurrent phases, it first degrades to Degenerated GC and completes GC there.
  // If second allocation failure happens during Degenerated GC cycle (for example, when GC
  // tries to evac something and no memory is available), cycle degrades to Full GC.
  //
  // The only current exception is allocation failure in Conc Evac: it goes straight to Full GC,
  // because we don't recover well from the case of incompletely evacuated heap in STW cycle.
  //
  // There are also two shortcuts through the normal cycle: a) immediate garbage shortcut, when
  // heuristics says there are no regions to compact, and all the collection comes from immediately
  // reclaimable regions; b) coalesced UR shortcut, when heuristics decides to coalesce UR with the
  // mark from the next cycle.
  //
  // ................................................................................................
  //
  //                                    (immediate garbage shortcut)                Concurrent GC
  //                             /-------------------------------------------\
  //                             |                       (coalesced UR)      v
  //                             |                  /----------------------->o
  //                             |                  |                        |
  //                             |                  |                        v
  // [START] ----> Conc Mark ----o----> Conc Evac --o--> Conc Update-Refs ---o----> [END]
  //                   |                    |                 |              ^
  //                   | (af)               | (af)            | (af)         |
  // ..................|....................|.................|..............|.......................
  //                   |                    |                 |              |
  //                   |          /---------/                 |              |      Degenerated GC
  //                   v          |                           v              |
  //               STW Mark ------+---> STW Evac ----> STW Update-Refs ----->o
  //                   |          |         |                 |              ^
  //                   | (af)     |         | (af)            | (af)         |
  // ..................|..........|.........|.................|..............|.......................
  //                   |          |         |                 |              |
  //                   |          v         v                 |              |      Full GC
  //                   \--------->o-------->o<----------------/              |
  //                                        |                                |
  //                                        v                                |
  //                                      Full GC  --------------------------/
  //
  ShenandoahHeap* heap = ShenandoahHeap::heap();

  if (check_cancellation_or_degen(ShenandoahHeap::_degenerated_outside_cycle)) return;

  GCIdMark gc_id_mark;
  ShenandoahGCSession session;

  // Capture peak occupancy right after starting the cycle
  heap->shenandoahPolicy()->record_peak_occupancy();

  TraceCollectorStats tcs(heap->monitoring_support()->concurrent_collection_counters());
  TraceMemoryManagerStats tmms(heap->minor_memory_manager(), cause);

  // Start initial mark under STW
  heap->vmop_entry_init_mark();

  // Continue concurrent mark
  heap->entry_mark();
  if (check_cancellation_or_degen(ShenandoahHeap::_degenerated_mark)) return;

  // If not cancelled, can try to concurrently pre-clean
  heap->entry_preclean();

  // Complete marking under STW, and start evacuation
  heap->vmop_entry_final_mark();

  // Final mark had reclaimed some immediate garbage, kick cleanup to reclaim the space
  heap->entry_cleanup();

  // Perform concurrent evacuation, if required.
  // This phase can be skipped if there is nothing to evacuate.
  // If so, evac_in_progress would be unset by collection set preparation code.
  if (heap->is_evacuation_in_progress()) {
    heap->entry_evac();
    if (check_cancellation_or_degen(ShenandoahHeap::_degenerated_evac)) return;
  }

  // Perform update-refs phase, if required.
  // This phase can be skipped if there was nothing evacuated. If so, has_forwarded would be unset
  // by collection set preparation code.
  if (heap->shenandoahPolicy()->should_start_update_refs()) {
    if (heap->has_forwarded_objects()) {
      heap->vmop_entry_init_updaterefs();
      heap->entry_updaterefs();
      if (check_cancellation_or_degen(ShenandoahHeap::_degenerated_updaterefs)) return;

      heap->vmop_entry_final_updaterefs();
    }
  } else {
    // If update-refs were skipped, need to do another verification pass after evacuation.
    heap->vmop_entry_verify_after_evac();
  }

  // Reclaim space and prepare for the next normal cycle:
  heap->entry_cleanup_bitmaps();

  // Cycle is complete
  heap->shenandoahPolicy()->record_success_concurrent();
}

bool ShenandoahConcurrentThread::check_cancellation_or_degen(ShenandoahHeap::ShenandoahDegenPoint point) {
  ShenandoahHeap* heap = ShenandoahHeap::heap();
  if (heap->cancelled_concgc()) {
    assert (is_alloc_failure_gc() || in_graceful_shutdown(), "Cancel GC either for alloc failure GC, or gracefully exiting");
    if (!in_graceful_shutdown()) {
      assert (_degen_point == ShenandoahHeap::_degenerated_outside_cycle,
              "Should not be set yet: %s", ShenandoahHeap::degen_point_to_string(_degen_point));
      _degen_point = point;
    }
    return true;
  }
  return false;
}

void ShenandoahConcurrentThread::stop_service() {
  // Nothing to do here.
}

void ShenandoahConcurrentThread::service_stw_full_cycle(GCCause::Cause cause) {
  GCIdMark gc_id_mark;
  ShenandoahGCSession session;

  ShenandoahHeap* heap = ShenandoahHeap::heap();
  heap->vmop_entry_full(cause);

  heap->shenandoahPolicy()->record_success_full();
}

void ShenandoahConcurrentThread::service_stw_degenerated_cycle(GCCause::Cause cause, ShenandoahHeap::ShenandoahDegenPoint point) {
  assert (point != ShenandoahHeap::_degenerated_unset, "Degenerated point should be set");

  GCIdMark gc_id_mark;
  ShenandoahGCSession session;

  ShenandoahHeap* heap = ShenandoahHeap::heap();
  heap->vmop_degenerated(point);

  heap->shenandoahPolicy()->record_success_degenerated();
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
