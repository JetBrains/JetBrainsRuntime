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

#include "gc/shared/suspendibleThreadSet.hpp"
#include "gc/shenandoah/shenandoahStrDedupQueue.inline.hpp"
#include "gc/shenandoah/shenandoahStrDedupThread.hpp"
#include "gc/shenandoah/shenandoahStringDedup.hpp"
#include "logging/log.hpp"
#include "logging/logStream.hpp"

ShenandoahStrDedupThread::ShenandoahStrDedupThread(ShenandoahStrDedupQueueSet* queues) :
  ConcurrentGCThread(), _queues(queues), _claimed(0) {
  size_t num_queues = queues->num_queues();
  _work_list = NEW_C_HEAP_ARRAY(QueueChunkedList*, num_queues, mtGC);
  for (size_t index = 0; index < num_queues; index ++) {
    _work_list[index] = NULL;
  }

  set_name("%s", "ShenandoahStringDedupTherad");
  create_and_start();
}

ShenandoahStrDedupThread::~ShenandoahStrDedupThread() {
  ShouldNotReachHere();
}

void ShenandoahStrDedupThread::run_service() {
  for (;;) {
    ShenandoahStrDedupStats stats;

    stats.mark_idle();

    assert(is_work_list_empty(), "Work list must be empty");

    // Queue has been shutdown
    if (!poll()) {
      assert(_queues->has_terminated(), "Must be terminated");
      break;
    }

    // Include thread in safepoints
    SuspendibleThreadSetJoiner sts_join;
    // Process the queue
    for (uint queue_index = 0; queue_index < _queues->num_queues(); queue_index ++) {
      QueueChunkedList* cur_list = _work_list[queue_index];

      while (cur_list != NULL) {
        stats.mark_exec();

        while (!cur_list->is_empty()) {
          oop java_string = cur_list->pop();
          stats.inc_inspected();

          if (oopDesc::is_null(java_string) ||
              !ShenandoahStringDedup::is_candidate(java_string)) {
            stats.inc_skipped();
          } else {
            if (ShenandoahStringDedup::deduplicate(java_string, false /* update counter */)) {
              stats.inc_deduped();
            } else {
              stats.inc_known();
            }
          }

          // Safepoint this thread if needed
          if (sts_join.should_yield()) {
            stats.mark_block();
            sts_join.yield();
            stats.mark_unblock();
          }
        }

        // Advance list only after processed. Otherwise, we may miss scanning
        // during safepoints
        _work_list[queue_index] = cur_list->next();
        _queues->release_chunked_list(cur_list);
        cur_list = _work_list[queue_index];
      }
    }

    stats.mark_done();

    ShenandoahStringDedup::dedup_stats().update(stats);

    LogTarget(Debug, gc, stringdedup) lt;
    if (lt.is_enabled()) {
      ResourceMark rm;
      LogStream ls(lt);
      stats.print_statistics(&ls);
    }
  }

  LogTarget(Debug, gc, stringdedup) lt;
  if (lt.is_enabled()) {
    ResourceMark rm;
    LogStream ls(lt);
    ShenandoahStringDedup::print_statistics(&ls);
  }
}

void ShenandoahStrDedupThread::stop_service() {
  _queues->terminate();
}

void ShenandoahStrDedupThread::parallel_oops_do(OopClosure* cl) {
  assert(SafepointSynchronize::is_at_safepoint(), "Must be at a safepoint");
  size_t claimed_index;
  while ((claimed_index = claim()) < _queues->num_queues()) {
    QueueChunkedList* q = _work_list[claimed_index];
    while (q != NULL) {
      q->oops_do(cl);
      q = q->next();
    }
  }
}

bool ShenandoahStrDedupThread::is_work_list_empty() const {
  assert(Thread::current() == this, "Only from dedup thread");
  for (uint index = 0; index < _queues->num_queues(); index ++) {
    if (_work_list[index] != NULL) return false;
  }
  return true;
}

void ShenandoahStrDedupThread::parallel_cleanup() {
  ShenandoahStrDedupQueueCleanupClosure cl;
  parallel_oops_do(&cl);
}

bool ShenandoahStrDedupThread::poll() {
  assert(is_work_list_empty(), "Only poll when work list is empty");
  MonitorLockerEx locker(_queues->lock(), Mutex::_no_safepoint_check_flag);

  while (!_queues->has_terminated()) {
    bool has_work = false;
    for (uint index = 0; index < _queues->num_queues(); index ++) {
      _work_list[index] = _queues->remove_work_list(index);
      if (_work_list[index] != NULL) {
        has_work = true;
      }
    }

    if (has_work) return true;

    locker.wait(Mutex::_no_safepoint_check_flag);
  }
  return false;
}

size_t ShenandoahStrDedupThread::claim() {
  size_t index = Atomic::add(size_t(1), &_claimed) - 1;
  return index;
}
