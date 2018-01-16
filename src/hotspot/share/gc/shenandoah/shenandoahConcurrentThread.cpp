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
  _full_gc_lock(Mutex::leaf, "ShenandoahFullGC_lock", true, Monitor::_safepoint_check_always),
  _conc_gc_lock(Mutex::leaf, "ShenandoahConcGC_lock", true, Monitor::_safepoint_check_always),
  _periodic_task(this),
  _full_gc_cause(GCCause::_no_cause_specified)
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

  while (!in_graceful_shutdown() && !should_terminate()) {
    bool partial_gc_requested = heap->shenandoahPolicy()->should_start_partial_gc();
    bool conc_gc_requested = is_conc_gc_requested() || heap->shenandoahPolicy()->should_start_concurrent_mark(heap->used(), heap->capacity());
    bool full_gc_requested = is_full_gc();
    bool gc_requested = partial_gc_requested || conc_gc_requested || full_gc_requested;

    if (gc_requested) {
      // If GC was requested, we are sampling the counters even without actual triggers
      // from allocation machinery. This captures GC phases more accurately.
      set_forced_counters_update(true);
    }

    if (full_gc_requested) {
      service_fullgc_cycle();
    } else if (partial_gc_requested) {
      service_partial_cycle();
    } else if (conc_gc_requested) {
      service_normal_cycle();
    }

    if (gc_requested) {
      heap->set_used_at_last_gc();

      // Coming out of (cancelled) concurrent GC, reset these for sanity
      if (heap->is_evacuation_in_progress() || heap->is_concurrent_partial_in_progress()) {
        heap->set_evacuation_in_progress_concurrently(false);
      }

      if (heap->is_update_refs_in_progress()) {
        heap->set_update_refs_in_progress_concurrently(false);
      }

      reset_conc_gc_requested();

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

void ShenandoahConcurrentThread::service_partial_cycle() {
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

void ShenandoahConcurrentThread::service_normal_cycle() {
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
  TraceMemoryManagerStats tmms(false, GCCause::_no_cause_specified);

  // Start initial mark under STW
  heap->vmop_entry_init_mark();

  if (check_cancellation()) return;

  // Continue concurrent mark
  heap->entry_mark();

  // Possibly hand over remaining marking work to degenerated final-mark phase
  bool clear_full_gc = false;
  if (heap->cancelled_concgc()) {
    heap->shenandoahPolicy()->record_cm_cancelled();
    if (_full_gc_cause == GCCause::_allocation_failure &&
        heap->shenandoahPolicy()->handover_cancelled_marking()) {
      heap->clear_cancelled_concgc();
      clear_full_gc = true;
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

  // If we degenerated mark work above, we need to kick off waiting Java threads, now that
  // more space is available
  if (clear_full_gc) {
    reset_full_gc();
  }

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

      clear_full_gc = false;
      if (heap->cancelled_concgc()) {
        heap->shenandoahPolicy()->record_uprefs_cancelled();
        if (_full_gc_cause == GCCause::_allocation_failure &&
            heap->shenandoahPolicy()->handover_cancelled_uprefs()) {
          clear_full_gc = true;
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

  if (check_cancellation()) return;

  // If we degenerated update-refs above, we need to kick off waiting Java threads, now that
  // more space is available
  if (clear_full_gc) {
    reset_full_gc();
  }

  // Cycle is complete
  heap->shenandoahPolicy()->record_cycle_end();
}

bool ShenandoahConcurrentThread::check_cancellation() {
  ShenandoahHeap* heap = ShenandoahHeap::heap();
  if (heap->cancelled_concgc()) {
    assert (is_full_gc() || in_graceful_shutdown(), "Cancel GC either for Full GC, or gracefully exiting");
    return true;
  }
  return false;
}


void ShenandoahConcurrentThread::stop_service() {
  // Nothing to do here.
}

void ShenandoahConcurrentThread::service_fullgc_cycle() {
  GCIdMark gc_id_mark;
  ShenandoahGCSession session(/* is_full_gc */true);

  ShenandoahHeap* heap = ShenandoahHeap::heap();

  {
    if (_full_gc_cause == GCCause::_allocation_failure) {
      heap->shenandoahPolicy()->record_allocation_failure_gc();
    } else {
      heap->shenandoahPolicy()->record_user_requested_gc();
    }

    heap->vmop_entry_full(_full_gc_cause);
  }

  reset_full_gc();
}

void ShenandoahConcurrentThread::do_full_gc(GCCause::Cause cause) {
  assert(Thread::current()->is_Java_thread(), "expect Java thread here");

  if (try_set_full_gc()) {
    _full_gc_cause = cause;

    // Now that full GC is scheduled, we can abort everything else
    ShenandoahHeap::heap()->cancel_concgc(cause);
  } else {
    GCCause::Cause last_cause = _full_gc_cause;
    if (last_cause != cause) {
      switch (cause) {
        // These GC causes take precedence:
        case GCCause::_allocation_failure:
          log_info(gc)("Full GC was already pending with cause: %s; new cause is %s, overwriting",
                       GCCause::to_string(last_cause),
                       GCCause::to_string(cause));
          _full_gc_cause = cause;
          break;
        // Other GC causes can be ignored
        default:
          log_info(gc)("Full GC is already pending with cause: %s; new cause was %s, ignoring",
                       GCCause::to_string(last_cause),
                       GCCause::to_string(cause));
          break;
      }
    }
  }

  MonitorLockerEx ml(&_full_gc_lock);
  while (is_full_gc()) {
    ml.wait();
  }
  assert(!is_full_gc(), "expect full GC to have completed");
}

void ShenandoahConcurrentThread::reset_full_gc() {
  _do_full_gc.unset();
  MonitorLockerEx ml(&_full_gc_lock);
  ml.notify_all();
}

bool ShenandoahConcurrentThread::try_set_full_gc() {
  return _do_full_gc.try_set();
}

bool ShenandoahConcurrentThread::is_full_gc() {
  return _do_full_gc.is_set();
}

bool ShenandoahConcurrentThread::is_conc_gc_requested() {
  return _do_concurrent_gc.is_set();
}

void ShenandoahConcurrentThread::do_conc_gc() {
  _do_concurrent_gc.set();
  MonitorLockerEx ml(&_conc_gc_lock);
  ml.wait();
}

void ShenandoahConcurrentThread::reset_conc_gc_requested() {
  _do_concurrent_gc.unset();
  MonitorLockerEx ml(&_conc_gc_lock);
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
