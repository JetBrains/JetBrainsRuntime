/*
 * Copyright (c) 2016, 2019, Red Hat, Inc. All rights reserved.
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

#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahTaskqueue.inline.hpp"
#include "logging/log.hpp"
#include "logging/logStream.hpp"

void ShenandoahObjToScanQueueSet::clear() {
  uint size = GenericTaskQueueSet<ShenandoahObjToScanQueue, mtGC>::size();
  for (uint index = 0; index < size; index ++) {
    ShenandoahObjToScanQueue* q = queue(index);
    assert(q != NULL, "Sanity");
    q->clear();
  }
}

bool ShenandoahObjToScanQueueSet::is_empty() {
  uint size = GenericTaskQueueSet<ShenandoahObjToScanQueue, mtGC>::size();
  for (uint index = 0; index < size; index ++) {
    ShenandoahObjToScanQueue* q = queue(index);
    assert(q != NULL, "Sanity");
    if (!q->is_empty()) {
      return false;
    }
  }
  return true;
}

bool ShenandoahTaskTerminator::offer_termination(ShenandoahTerminatorTerminator* terminator) {
  assert(_n_threads > 0, "Initialization is incorrect");
  assert(_offered_termination < _n_threads, "Invariant");
  assert(_blocker != NULL, "Invariant");

  // single worker, done
  if (_n_threads == 1) {
    return true;
  }

  _blocker->lock_without_safepoint_check();
  // all arrived, done
  if (++ _offered_termination == _n_threads) {
    _blocker->notify_all();
    _blocker->unlock();
    return true;
  }

  Thread* the_thread = Thread::current();
  while (true) {
    if (_spin_master == NULL) {
      _spin_master = the_thread;

      _blocker->unlock();

      if (do_spin_master_work(terminator)) {
        assert(_offered_termination == _n_threads, "termination condition");
        return true;
      } else {
        _blocker->lock_without_safepoint_check();
      }
    } else {
      _blocker->wait(true, WorkStealingSleepMillis);

      if (_offered_termination == _n_threads) {
        _blocker->unlock();
        return true;
      }
    }

    if (peek_in_queue_set() || (terminator != NULL && terminator->should_exit_termination())) {
      _offered_termination --;
      _blocker->unlock();
      return false;
    }
  }
}

#if TASKQUEUE_STATS
void ShenandoahObjToScanQueueSet::print_taskqueue_stats_hdr(outputStream* const st) {
  st->print_raw_cr("GC Task Stats");
  st->print_raw("thr "); TaskQueueStats::print_header(1, st); st->cr();
  st->print_raw("--- "); TaskQueueStats::print_header(2, st); st->cr();
}

void ShenandoahObjToScanQueueSet::print_taskqueue_stats() const {
  if (!log_develop_is_enabled(Trace, gc, task, stats)) {
    return;
  }
  Log(gc, task, stats) log;
  ResourceMark rm;
  LogStream ls(log.trace());
  outputStream* st = &ls;
  print_taskqueue_stats_hdr(st);

  ShenandoahObjToScanQueueSet* queues = const_cast<ShenandoahObjToScanQueueSet*>(this);
  TaskQueueStats totals;
  const uint n = size();
  for (uint i = 0; i < n; ++i) {
    st->print(UINT32_FORMAT_W(3), i);
    queues->queue(i)->stats.print(st);
    st->cr();
    totals += queues->queue(i)->stats;
  }
  st->print("tot "); totals.print(st); st->cr();
  DEBUG_ONLY(totals.verify());

}

void ShenandoahObjToScanQueueSet::reset_taskqueue_stats() {
  const uint n = size();
  for (uint i = 0; i < n; ++i) {
    queue(i)->stats.reset();
  }
}
#endif // TASKQUEUE_STATS

bool ShenandoahTerminatorTerminator::should_exit_termination() {
  return _heap->cancelled_gc();
}

bool ShenandoahTaskTerminator::do_spin_master_work(ShenandoahTerminatorTerminator* terminator) {
  uint yield_count = 0;
  // Number of hard spin loops done since last yield
  uint hard_spin_count = 0;
  // Number of iterations in the hard spin loop.
  uint hard_spin_limit = WorkStealingHardSpins;

  // If WorkStealingSpinToYieldRatio is 0, no hard spinning is done.
  // If it is greater than 0, then start with a small number
  // of spins and increase number with each turn at spinning until
  // the count of hard spins exceeds WorkStealingSpinToYieldRatio.
  // Then do a yield() call and start spinning afresh.
  if (WorkStealingSpinToYieldRatio > 0) {
    hard_spin_limit = WorkStealingHardSpins >> WorkStealingSpinToYieldRatio;
    hard_spin_limit = MAX2(hard_spin_limit, 1U);
  }
  // Remember the initial spin limit.
  uint hard_spin_start = hard_spin_limit;

  // Loop waiting for all threads to offer termination or
  // more work.
  while (true) {
    // Look for more work.
    // Periodically sleep() instead of yield() to give threads
    // waiting on the cores the chance to grab this code
    if (yield_count <= WorkStealingYieldsBeforeSleep) {
      // Do a yield or hardspin.  For purposes of deciding whether
      // to sleep, count this as a yield.
      yield_count++;

      // Periodically call yield() instead spinning
      // After WorkStealingSpinToYieldRatio spins, do a yield() call
      // and reset the counts and starting limit.
      if (hard_spin_count > WorkStealingSpinToYieldRatio) {
        yield();
        hard_spin_count = 0;
        hard_spin_limit = hard_spin_start;
#ifdef TRACESPINNING
        _total_yields++;
#endif
      } else {
        // Hard spin this time
        // Increase the hard spinning period but only up to a limit.
        hard_spin_limit = MIN2(2*hard_spin_limit,
                               (uint) WorkStealingHardSpins);
        for (uint j = 0; j < hard_spin_limit; j++) {
          SpinPause();
        }
        hard_spin_count++;
#ifdef TRACESPINNING
        _total_spins++;
#endif
      }
    } else {
      log_develop_trace(gc, task)("ShenanddoahTaskTerminator::do_spin_master_work() thread " PTR_FORMAT " sleeps after %u yields",
                                  p2i(Thread::current()), yield_count);
      yield_count = 0;

      MonitorLockerEx locker(_blocker, Mutex::_no_safepoint_check_flag);   // no safepoint check
      _spin_master = NULL;
      locker.wait(Mutex::_no_safepoint_check_flag, WorkStealingSleepMillis);
      if (_spin_master == NULL) {
        _spin_master = Thread::current();
      } else {
        return false;
      }
    }

#ifdef TRACESPINNING
      _total_peeks++;
#endif
    size_t tasks = tasks_in_queue_set();
    if (tasks > 0 || (terminator != NULL && terminator->should_exit_termination())) {
      MonitorLockerEx locker(_blocker, Mutex::_no_safepoint_check_flag);   // no safepoint check

      if (tasks >= _offered_termination - 1) {
        locker.notify_all();
      } else {
        for (; tasks > 1; tasks --) {
          locker.notify();
        }
      }
      _spin_master = NULL;
      return false;
    } else if (_offered_termination == _n_threads) {
      return true;
    }
  }
}
