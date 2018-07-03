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

#include "gc/shared/stringdedup/stringDedup.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahStrDedupQueue.hpp"
#include "gc/shenandoah/shenandoahStrDedupQueue.inline.hpp"
#include "gc/shenandoah/shenandoahStringDedup.hpp"
#include "logging/log.hpp"
#include "runtime/mutex.hpp"

ShenandoahStrDedupQueue::ShenandoahStrDedupQueue() :
  _consumer_queue(NULL), _free_list(NULL), _published_queues(NULL), _cancel(false),
  _num_producer_queue(ShenandoahHeap::heap()->max_workers()), _num_free_buffer(0),
  _max_free_buffer(ShenandoahHeap::heap()->max_workers() * 2),
  _total_buffers(0) {
  ShenandoahHeap* heap = ShenandoahHeap::heap();
  _producer_queues = NEW_C_HEAP_ARRAY(ShenandoahQueueBuffer*, _num_producer_queue, mtGC);
  for (size_t index = 0; index < _num_producer_queue; index ++) {
    _producer_queues[index] = NULL;
  }
}

ShenandoahStrDedupQueue::~ShenandoahStrDedupQueue() {
  MonitorLockerEx ml(StringDedupQueue_lock, Mutex::_no_safepoint_check_flag);
  for (size_t index = 0; index < num_queues(); index ++) {
    release_buffers(queue_at(index));
  }

  release_buffers(_free_list);
  FREE_C_HEAP_ARRAY(ShenandoahQueueBuffer*, _producer_queues);
}

void ShenandoahStrDedupQueue::wait_impl() {
  MonitorLockerEx ml(StringDedupQueue_lock, Mutex::_no_safepoint_check_flag);
  while (_consumer_queue == NULL && !_cancel) {
    ml.wait(Mutex::_no_safepoint_check_flag);
    _consumer_queue = _published_queues;
    _published_queues = NULL;
  }
}

void ShenandoahStrDedupQueue::cancel_wait_impl() {
  MonitorLockerEx ml(StringDedupQueue_lock, Mutex::_no_safepoint_check_flag);
  _cancel = true;
  ml.notify();
}

void ShenandoahStrDedupQueue::unlink_or_oops_do_impl(StringDedupUnlinkOrOopsDoClosure* cl, size_t queue) {
  ShenandoahQueueBuffer* q = queue_at(queue);
  while (q != NULL) {
    q->unlink_or_oops_do(cl);
    q = q->next();
  }
}

ShenandoahQueueBuffer* ShenandoahStrDedupQueue::queue_at(size_t queue_id) const {
  assert(queue_id <= num_queues(), "Invalid queue id");
  if (queue_id < _num_producer_queue) {
    return _producer_queues[queue_id];
  } else if (queue_id == _num_producer_queue) {
    return _consumer_queue;
  } else {
    assert(queue_id == _num_producer_queue + 1, "Must be");
    return _published_queues;
  }
}

void ShenandoahStrDedupQueue::set_producer_buffer(ShenandoahQueueBuffer* buf, size_t queue_id) {
  assert(queue_id < _num_producer_queue, "Not a producer queue id");
  _producer_queues[queue_id] = buf;
}

void ShenandoahStrDedupQueue::push_impl(uint worker_id, oop string_oop) {
  assert(worker_id < _num_producer_queue, "Invalid queue id. Can only push to producer queue");
  assert(ShenandoahStringDedup::is_candidate(string_oop), "Not a candidate");

  ShenandoahQueueBuffer* buf = queue_at((size_t)worker_id);

  if (buf == NULL) {
    MonitorLockerEx ml(StringDedupQueue_lock, Mutex::_no_safepoint_check_flag);
    buf = new_buffer();
    set_producer_buffer(buf, worker_id);
  } else if (buf->is_full()) {
    MonitorLockerEx ml(StringDedupQueue_lock, Mutex::_no_safepoint_check_flag);
    buf->set_next(_published_queues);
    _published_queues = buf;
    buf = new_buffer();
    set_producer_buffer(buf, worker_id);
    ml.notify();
  }

  assert(!buf->is_full(), "Sanity");
  buf->push(string_oop);
}

oop ShenandoahStrDedupQueue::pop_impl() {
  if (_consumer_queue == NULL) {
    MonitorLockerEx ml(StringDedupQueue_lock, Mutex::_no_safepoint_check_flag);
    _consumer_queue = _published_queues;
    _published_queues = NULL;
  }

  // there is nothing
  if (_consumer_queue == NULL) {
    return NULL;
  }

  assert(!_consumer_queue->is_empty(), "Should not be empty");
  oop obj = _consumer_queue->pop();
  if (_consumer_queue->is_empty()) {
    ShenandoahQueueBuffer* buf = _consumer_queue;
    _consumer_queue = _consumer_queue->next();
    buf->set_next(NULL);
    {
      MonitorLockerEx ml(StringDedupQueue_lock, Mutex::_no_safepoint_check_flag);
      release_buffers(buf);
    }
  }

  assert(obj == NULL || ShenandoahStringDedup::is_candidate(obj), "Must be a candidate");
  return obj;
}

ShenandoahQueueBuffer* ShenandoahStrDedupQueue::new_buffer() {
  assert_lock_strong(StringDedupQueue_lock);
  if (_free_list != NULL) {
    assert(_num_free_buffer > 0, "Sanity");
    ShenandoahQueueBuffer* buf = _free_list;
    _free_list = _free_list->next();
    _num_free_buffer --;
    buf->reset();
    return buf;
  } else {
    assert(_num_free_buffer == 0, "Sanity");
    _total_buffers ++;
    return new ShenandoahQueueBuffer;
  }
}

void ShenandoahStrDedupQueue::release_buffers(ShenandoahQueueBuffer* list) {
  assert_lock_strong(StringDedupQueue_lock);
  while (list != NULL) {
    ShenandoahQueueBuffer* tmp = list;
    list = list->next();
    if (_num_free_buffer < _max_free_buffer) {
      tmp->set_next(_free_list);
      _free_list = tmp;
      _num_free_buffer ++;
    } else {
      _total_buffers --;
      delete tmp;
    }
  }
}

void ShenandoahStrDedupQueue::print_statistics_impl() {
  Log(gc, stringdedup) log;
  log.debug("  Queue:");
  log.debug("    Total buffers: " SIZE_FORMAT " (" SIZE_FORMAT " K). " SIZE_FORMAT " buffers are on free list",
    _total_buffers, (_total_buffers * sizeof(ShenandoahQueueBuffer) / K), _num_free_buffer);
}


class VerifyQueueClosure : public OopClosure {
private:
  ShenandoahHeap* _heap;
public:
  VerifyQueueClosure();

  void do_oop(oop* o);
  void do_oop(narrowOop* o) {
    ShouldNotCallThis();
  }
};

VerifyQueueClosure::VerifyQueueClosure() :
  _heap(ShenandoahHeap::heap()) {
}

void VerifyQueueClosure::do_oop(oop* o) {
  if (*o != NULL) {
    assert(!_heap->is_in((void*)o), "off heap location");
    oop obj = *o;
    assert(_heap->is_in(obj), "Object must be on the heap");
    assert(java_lang_String::is_instance(obj), "Object must be a String");
  }
}

void ShenandoahStrDedupQueue::verify_impl() {
  VerifyQueueClosure vcl;
  for (size_t index = 0; index < num_queues(); index ++) {
    ShenandoahQueueBuffer* buf = queue_at(index);
    while (buf != NULL) {
      buf->oops_do(&vcl);
      buf = buf->next();
    }
  }
}



