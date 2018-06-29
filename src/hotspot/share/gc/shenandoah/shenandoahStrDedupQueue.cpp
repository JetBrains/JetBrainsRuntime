/*
 * Copyright (c) 2017, 2018, Red Hat, Inc. and/or its affiliates.
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
#include "gc/shenandoah/shenandoahStrDedupQueue.hpp"
#include "gc/shenandoah/shenandoahStrDedupQueue.inline.hpp"
#include "gc/shenandoah/shenandoahStringDedup.hpp"
#include "runtime/atomic.hpp"

ShenandoahStrDedupQueue::ShenandoahStrDedupQueue(ShenandoahStrDedupQueueSet* queue_set, uint num) :
  _queue_set(queue_set), _current_list(NULL), _queue_num(num) {
  assert(num < _queue_set->num_queues(), "Not valid queue number");
}

ShenandoahStrDedupQueue::~ShenandoahStrDedupQueue() {
  if (_current_list != NULL) {
    delete _current_list;
  }
}

void ShenandoahStrDedupQueue::oops_do(OopClosure* cl) {
  if (_current_list != NULL) {
    _current_list->oops_do(cl);
  }
}

ShenandoahStrDedupQueueSet::ShenandoahStrDedupQueueSet(uint n) :
  _num_queues(n), _free_list(NULL), _num_free_queues(0), _terminated(false), _claimed(0) {
  _lock = new Monitor(Mutex::access, "ShenandoahStrDedupQueueLock", false, Monitor::_safepoint_check_never);

  _local_queues = NEW_C_HEAP_ARRAY(ShenandoahStrDedupQueue*, num_queues(), mtGC);
  _outgoing_work_list = NEW_C_HEAP_ARRAY(QueueChunkedList*, num_queues(), mtGC);

  for (uint index = 0; index < num_queues(); index ++) {
    _local_queues[index] = new ShenandoahStrDedupQueue(this, index);
    _outgoing_work_list[index] = NULL;
  }
}

ShenandoahStrDedupQueueSet::~ShenandoahStrDedupQueueSet() {
  QueueChunkedList* q;
  QueueChunkedList* tmp;

  for (uint index = 0; index < num_queues(); index ++) {
    if (_local_queues[index] != NULL) {
      delete _local_queues[index];
    }

    q = _outgoing_work_list[index];
    while (q != NULL) {
      tmp = q;
      q = q->next();
      delete tmp;
    }
  }

  q = _free_list;
  while (q != NULL) {
    tmp = q;
    q = tmp->next();
    delete tmp;
  }

  FREE_C_HEAP_ARRAY(ShenandoahStrDedupQueue*, _local_queues);
  FREE_C_HEAP_ARRAY(QueueChunkedList*, _outgoing_work_list);

  delete _lock;
}


size_t ShenandoahStrDedupQueueSet::claim() {
  size_t index = Atomic::add(size_t(1), &_claimed) - 1;
  return index;
}

void ShenandoahStrDedupQueueSet::parallel_oops_do(OopClosure* cl) {
  assert(cl != NULL, "No closure");
  assert(SafepointSynchronize::is_at_safepoint(), "Must be at a safepoint");
  size_t claimed_index;
  while ((claimed_index = claim()) < num_queues()) {
    queue_at(claimed_index)->oops_do(cl);
    QueueChunkedList* head = _outgoing_work_list[claimed_index];
    while (head != NULL) {
      head->oops_do(cl);
      head = head->next();
    }
  }
}

void ShenandoahStrDedupQueueSet::oops_do_slow(OopClosure* cl) {
  assert(cl != NULL, "No closure");
  assert(SafepointSynchronize::is_at_safepoint(), "Must be at a safepoint");
  for (size_t index = 0; index < num_queues(); index ++) {
    queue_at(index)->oops_do(cl);
    QueueChunkedList* head = _outgoing_work_list[index];
    while (head != NULL) {
      head->oops_do(cl);
      head = head->next();
    }
  }
}

void ShenandoahStrDedupQueueSet::terminate() {
  MonitorLockerEx locker(_lock, Mutex::_no_safepoint_check_flag);
  _terminated = true;
  locker.notify_all();
}

void ShenandoahStrDedupQueueSet::release_chunked_list(QueueChunkedList* q) {
  assert(q != NULL, "null queue");
  MutexLockerEx locker(lock(), Mutex::_no_safepoint_check_flag);
  if (_num_free_queues >= 2 * num_queues()) {
    delete q;
  } else {
    q->set_next(_free_list);
    _free_list = q;
    _num_free_queues ++;
  }
}

QueueChunkedList* ShenandoahStrDedupQueueSet::allocate_no_lock() {
  assert_lock_strong(lock());

  if (_free_list != NULL) {
    QueueChunkedList* q = _free_list;
    _free_list = _free_list->next();
    _num_free_queues --;
    q->reset();
    return q;
  } else {
    return new QueueChunkedList();
  }
}

QueueChunkedList* ShenandoahStrDedupQueueSet::allocate_chunked_list() {
  MutexLockerEx locker(_lock, Mutex::_no_safepoint_check_flag);
  return allocate_no_lock();
}

QueueChunkedList* ShenandoahStrDedupQueueSet::push_and_get_atomic(QueueChunkedList* q, uint queue_num) {
  QueueChunkedList* head = _outgoing_work_list[queue_num];
  QueueChunkedList* result;
  q->set_next(head);
  while ((result = Atomic::cmpxchg(q, &_outgoing_work_list[queue_num], head)) != head) {
    head = result;
    q->set_next(head);
  }

  {
    MutexLockerEx locker(lock(), Mutex::_no_safepoint_check_flag);
    q = allocate_no_lock();
    lock()->notify();
  }
  return q;
}

QueueChunkedList* ShenandoahStrDedupQueueSet::remove_work_list_atomic(uint queue_num) {
  assert(queue_num < num_queues(), "Invalid queue number");

  QueueChunkedList* list = _outgoing_work_list[queue_num];
  QueueChunkedList* result;
  while ((result = Atomic::cmpxchg((QueueChunkedList*)NULL, &_outgoing_work_list[queue_num], list)) != list) {
    list = result;
  }

  return list;
}

void ShenandoahStrDedupQueueSet::parallel_cleanup() {
  ShenandoahStrDedupQueueCleanupClosure cl;
  parallel_oops_do(&cl);
}
