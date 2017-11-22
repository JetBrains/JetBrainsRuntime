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
  _do_full_gc(0),
  _do_concurrent_gc(0),
  _do_counters_update(0),
  _force_counters_update(0),
  _full_gc_cause(GCCause::_no_cause_specified),
  _graceful_shutdown(0)
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
      // Coming out of (cancelled) concurrent GC, reset these for sanity
      if (heap->is_evacuation_in_progress() || heap->is_concurrent_partial_in_progress()) {
        heap->set_evacuation_in_progress_concurrently(false);
      }

      if (heap->is_update_refs_in_progress()) {
        heap->set_update_refs_in_progress(false);
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

  {
    ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause_gross);
    ShenandoahGCPhase partial_phase(ShenandoahPhaseTimings::init_partial_gc_gross);
    VM_ShenandoahInitPartialGC init_partial_gc;
    VMThread::execute(&init_partial_gc);
  }

  if (!partial_gc->has_work()) return;
  if (check_cancellation()) return;

  {
    GCTraceTime(Info, gc) time("Concurrent partial", heap->gc_timer(), GCCause::_no_gc, true);
    TraceCollectorStats tcs(heap->monitoring_support()->concurrent_collection_counters());
    partial_gc->concurrent_partial_collection();
  }

  if (check_cancellation()) return;

  {
    ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause_gross);
    ShenandoahGCPhase partial_phase(ShenandoahPhaseTimings::final_partial_gc_gross);
    VM_ShenandoahFinalPartialGC final_partial_gc;
    VMThread::execute(&final_partial_gc);
  }

  if (check_cancellation()) return;

  {
    GCTraceTime(Info, gc) time("Concurrent cleanup", heap->gc_timer(), GCCause::_no_gc, true);
    ShenandoahGCPhase phase(ShenandoahPhaseTimings::conc_cleanup);
    ShenandoahGCPhase phase_recycle(ShenandoahPhaseTimings::conc_cleanup_recycle);
    heap->recycle_trash();
  }

  // TODO: Call this properly with Shenandoah*CycleMark
  heap->set_used_at_last_gc();
}

void ShenandoahConcurrentThread::service_normal_cycle() {
  if (check_cancellation()) return;

  ShenandoahHeap* heap = ShenandoahHeap::heap();

  GCTimer* gc_timer = heap->gc_timer();

  ShenandoahGCSession session;

  // Cycle started
  heap->shenandoahPolicy()->record_cycle_start();

  // Capture peak occupancy right after starting the cycle
  heap->shenandoahPolicy()->record_peak_occupancy();

  GCIdMark gc_id_mark;
  TraceCollectorStats tcs(heap->monitoring_support()->concurrent_collection_counters());
  TraceMemoryManagerStats tmms(false, GCCause::_no_cause_specified);

  // Start initial mark under STW:
  {
    // Workers are setup by VM_ShenandoahInitMark
    TraceCollectorStats tcs(heap->monitoring_support()->stw_collection_counters());
    ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause_gross);
    ShenandoahGCPhase init_mark_phase(ShenandoahPhaseTimings::init_mark_gross);
    VM_ShenandoahInitMark initMark;
    VMThread::execute(&initMark);
  }

  if (check_cancellation()) return;

  // Continue concurrent mark:
  {
    // Setup workers for concurrent marking phase
    WorkGang* workers = heap->workers();
    uint n_workers = ShenandoahWorkerPolicy::calc_workers_for_conc_marking();
    ShenandoahWorkerScope scope(workers, n_workers);

    GCTraceTime(Info, gc) time("Concurrent marking", gc_timer, GCCause::_no_gc, true);
    TraceCollectorStats tcs(heap->monitoring_support()->concurrent_collection_counters());
    ShenandoahHeap::heap()->concurrentMark()->mark_from_roots();
  }

  // Allocations happen during concurrent mark, record peak after the phase:
  heap->shenandoahPolicy()->record_peak_occupancy();

  // Possibly hand over remaining marking work to final-mark phase.
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
    if (ShenandoahPreclean) {
      if (heap->concurrentMark()->process_references()) {
        GCTraceTime(Info, gc) time("Concurrent precleaning", gc_timer, GCCause::_no_gc, true);
        ShenandoahGCPhase conc_preclean(ShenandoahPhaseTimings::conc_preclean);
        heap->concurrentMark()->preclean_weak_refs();

        // Allocations happen during concurrent preclean, record peak after the phase:
        heap->shenandoahPolicy()->record_peak_occupancy();
      }
    }
  }

  // Proceed to complete marking under STW, and start evacuation:
  {
    // Workers are setup by VM_ShenandoahFinalMarkStartEvac
    TraceCollectorStats tcs(heap->monitoring_support()->stw_collection_counters());
    ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause_gross);
    ShenandoahGCPhase final_mark_phase(ShenandoahPhaseTimings::final_mark_gross);
    VM_ShenandoahFinalMarkStartEvac finishMark;
    VMThread::execute(&finishMark);
  }

  if (check_cancellation()) return;

  // If we handed off remaining marking work above, we need to kick off waiting Java threads
  if (clear_full_gc) {
    reset_full_gc();
  }

  // Final mark had reclaimed some immediate garbage, kick cleanup to reclaim the space.
  {
    GCTraceTime(Info, gc) time("Concurrent cleanup", gc_timer, GCCause::_no_gc, true);
    ShenandoahGCPhase phase(ShenandoahPhaseTimings::conc_cleanup);
    ShenandoahGCPhase phase_recycle(ShenandoahPhaseTimings::conc_cleanup_recycle);
    heap->recycle_trash();
  }

  // Perform concurrent evacuation, if required.
  // This phase can be skipped if there is nothing to evacuate. If so, evac_in_progress would be unset
  // by collection set preparation code.
  if (heap->is_evacuation_in_progress()) {

    // Setup workers for concurrent evacuation phase
    WorkGang* workers = heap->workers();
    uint n_workers = ShenandoahWorkerPolicy::calc_workers_for_conc_evac();
    ShenandoahWorkerScope scope(workers, n_workers);

    GCTraceTime(Info, gc) time("Concurrent evacuation ", gc_timer, GCCause::_no_gc, true);
    TraceCollectorStats tcs(heap->monitoring_support()->concurrent_collection_counters());
    heap->do_evacuation();

    // Allocations happen during evacuation, record peak after the phase:
    heap->shenandoahPolicy()->record_peak_occupancy();

    if (check_cancellation()) return;
  }

  // Perform update-refs phase, if required.
  // This phase can be skipped if there was nothing evacuated. If so, need_update_refs would be unset
  // by collection set preparation code. However, adaptive heuristics need to record "success" when
  // this phase is skipped. Therefore, we conditionally execute all ops, leaving heuristics adjustments
  // intact.
  if (heap->shenandoahPolicy()->should_start_update_refs()) {

    bool do_it = heap->need_update_refs();
    if (do_it) {
      {
        TraceCollectorStats tcs(heap->monitoring_support()->stw_collection_counters());
        ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause_gross);
        ShenandoahGCPhase init_update_refs_phase(ShenandoahPhaseTimings::init_update_refs_gross);
        VM_ShenandoahInitUpdateRefs init_update_refs;
        VMThread::execute(&init_update_refs);
      }

      {
        GCTraceTime(Info, gc) time("Concurrent update references ", gc_timer, GCCause::_no_gc, true);
        WorkGang* workers = heap->workers();
        uint n_workers = ShenandoahWorkerPolicy::calc_workers_for_conc_update_ref();
        ShenandoahWorkerScope scope(workers, n_workers);
        heap->concurrent_update_heap_references();
      }
    }

    // Allocations happen during update-refs, record peak after the phase:
    heap->shenandoahPolicy()->record_peak_occupancy();

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

    if (do_it) {
      TraceCollectorStats tcs(heap->monitoring_support()->stw_collection_counters());
      ShenandoahGCPhase total(ShenandoahPhaseTimings::total_pause_gross);
      ShenandoahGCPhase final_update_refs_phase(ShenandoahPhaseTimings::final_update_refs_gross);
      VM_ShenandoahFinalUpdateRefs final_update_refs;
      VMThread::execute(&final_update_refs);
    }
  } else {
    // If update-refs were skipped, need to do another verification pass after evacuation.
    if (ShenandoahVerify && !check_cancellation()) {
      VM_ShenandoahVerifyHeapAfterEvacuation verify_after_evacuation;
      VMThread::execute(&verify_after_evacuation);
    }
  }

  // Prepare for the next normal cycle:
  if (check_cancellation()) return;

  if (clear_full_gc) {
    reset_full_gc();
  }

  {
    GCTraceTime(Info, gc) time("Concurrent cleanup", gc_timer, GCCause::_no_gc, true);
    ShenandoahGCPhase phase(ShenandoahPhaseTimings::conc_cleanup);

    {
      ShenandoahGCPhase phase_recycle(ShenandoahPhaseTimings::conc_cleanup_recycle);
      heap->recycle_trash();
    }

    {
      ShenandoahGCPhase phase_reset(ShenandoahPhaseTimings::conc_cleanup_reset_bitmaps);
      WorkGang *workers = heap->workers();
      ShenandoahPushWorkerScope scope(workers, ConcGCThreads);
      heap->reset_next_mark_bitmap(workers);
    }
  }

  // Allocations happen during bitmap cleanup, record peak after the phase:
  heap->shenandoahPolicy()->record_peak_occupancy();

  // Cycle is complete
  heap->shenandoahPolicy()->record_cycle_end();

  // TODO: Call this properly with Shenandoah*CycleMark
  heap->set_used_at_last_gc();
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
  ShenandoahHeap* heap = ShenandoahHeap::heap();

  {
    if (_full_gc_cause == GCCause::_allocation_failure) {
      heap->shenandoahPolicy()->record_allocation_failure_gc();
    } else {
      heap->shenandoahPolicy()->record_user_requested_gc();
    }

    TraceCollectorStats tcs(heap->monitoring_support()->full_stw_collection_counters());
    TraceMemoryManagerStats tmms(true, _full_gc_cause);
    VM_ShenandoahFullGC full_gc(_full_gc_cause);
    VMThread::execute(&full_gc);
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
  OrderAccess::release_store_fence(&_do_full_gc, (jbyte)0);
  MonitorLockerEx ml(&_full_gc_lock);
  ml.notify_all();
}

bool ShenandoahConcurrentThread::try_set_full_gc() {
  jbyte old = Atomic::cmpxchg((jbyte)1, &_do_full_gc, (jbyte)0);
  return old == 0; // success
}

bool ShenandoahConcurrentThread::is_full_gc() {
  return OrderAccess::load_acquire(&_do_full_gc) == 1;
}

bool ShenandoahConcurrentThread::is_conc_gc_requested() {
  return OrderAccess::load_acquire(&_do_concurrent_gc) == 1;
}

void ShenandoahConcurrentThread::do_conc_gc() {
  OrderAccess::release_store_fence(&_do_concurrent_gc, (jbyte)1);
  MonitorLockerEx ml(&_conc_gc_lock);
  ml.wait();
}

void ShenandoahConcurrentThread::reset_conc_gc_requested() {
  OrderAccess::release_store_fence(&_do_concurrent_gc, (jbyte)0);
  MonitorLockerEx ml(&_conc_gc_lock);
  ml.notify_all();
}

void ShenandoahConcurrentThread::handle_counters_update() {
  if (OrderAccess::load_acquire(&_do_counters_update) == 1) {
    OrderAccess::release_store(&_do_counters_update, (jbyte)0);
    ShenandoahHeap::heap()->monitoring_support()->update_counters();
  }
}

void ShenandoahConcurrentThread::handle_force_counters_update() {
  if (OrderAccess::load_acquire(&_force_counters_update) == 1) {
    OrderAccess::release_store(&_do_counters_update, (jbyte)0); // reset these too, we do update now!
    ShenandoahHeap::heap()->monitoring_support()->update_counters();
  }
}

void ShenandoahConcurrentThread::trigger_counters_update() {
  if (OrderAccess::load_acquire(&_do_counters_update) == 0) {
    OrderAccess::release_store(&_do_counters_update, (jbyte)1);
  }
}

void ShenandoahConcurrentThread::set_forced_counters_update(bool value) {
  OrderAccess::release_store(&_force_counters_update, (jbyte)(value ? 1 : 0));
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
  OrderAccess::release_store_fence(&_graceful_shutdown, (jbyte)1);
}

bool ShenandoahConcurrentThread::in_graceful_shutdown() {
  return OrderAccess::load_acquire(&_graceful_shutdown) == 1;
}
