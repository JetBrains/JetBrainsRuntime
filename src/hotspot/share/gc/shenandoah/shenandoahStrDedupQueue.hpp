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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHSTRINGDEDUPQUEUE_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHSTRINGDEDUPQUEUE_HPP

#include "gc/shenandoah/shenandoahHeap.hpp"
#include "memory/iterator.hpp"
#include "oops/oop.hpp"
#include "runtime/mutex.hpp"

template <size_t SIZE>
class ShenandoahStrDedupChunkedList : public CHeapObj<mtGC> {
private:
  oop                                   _oops[SIZE];
  ShenandoahStrDedupChunkedList<SIZE>*  _next;
  uint                                  _index;

public:
  ShenandoahStrDedupChunkedList() : _next(NULL), _index(0) { }

  inline bool is_full() const  { return _index == SIZE; }
  inline bool is_empty() const { return _index == 0; }
  inline void push(oop obj)    { assert(!is_full(), "List is full");  _oops[_index ++] = obj; }
  inline oop  pop()            { assert(!is_empty(), "List is empty"); return _oops[--_index]; }
  inline size_t size() const   { return _index; }
  inline void reset() {
    _index = 0;
    _next = NULL;
  }

  void set_next(ShenandoahStrDedupChunkedList<SIZE>* q) { _next = q; }
  ShenandoahStrDedupChunkedList<SIZE>* next() const { return _next; }

  void oops_do(OopClosure* cl) {
    assert(cl != NULL, "null closure");
    for (uint index = 0; index < size(); index ++) {
      cl->do_oop(&_oops[index]);
    }
  }
};

class ShenandoahStrDedupQueueSet;

typedef ShenandoahStrDedupChunkedList<64> QueueChunkedList;


class ShenandoahStrDedupQueue : public CHeapObj<mtGC> {
private:
  ShenandoahStrDedupQueueSet* _queue_set;
  QueueChunkedList*           _current_list;
  uint                        _queue_num;

public:
  ShenandoahStrDedupQueue(ShenandoahStrDedupQueueSet* queue_set, uint num);
  ~ShenandoahStrDedupQueue();

  uint queue_num() const { return _queue_num; }
  inline void push(oop java_string);
  void oops_do(OopClosure* cl);
};

class ShenandoahStrDedupThread;

class ShenandoahStrDedupQueueSet : public CHeapObj<mtGC> {
  friend class ShenandoahStrDedupQueue;
  friend class ShenandoahStrDedupThread;

private:
  ShenandoahStrDedupQueue**     _local_queues;
  uint                          _num_queues;
  QueueChunkedList* volatile *  _outgoing_work_list;

  QueueChunkedList*   _free_list;
  uint                _num_free_queues;

  Monitor*            _lock;

  bool                _terminated;

  volatile size_t     _claimed;

public:
  ShenandoahStrDedupQueueSet(uint n);
  ~ShenandoahStrDedupQueueSet();

  uint num_queues() const { return _num_queues; }

  ShenandoahStrDedupQueue* queue_at(size_t index) {
    assert(index < num_queues(), "Index out of bound");
    return _local_queues[index];
  }

  void clear_claimed() { _claimed = 0; }
  void parallel_cleanup();
  void parallel_oops_do(OopClosure* cl);

  // For verification only
  void oops_do_slow(OopClosure* cl);

  void terminate();
  bool has_terminated() {
    return _terminated;
  }

private:
  void release_chunked_list(QueueChunkedList* l);

  QueueChunkedList* allocate_chunked_list();
  QueueChunkedList* allocate_no_lock();

  // Atomic publish and retrieve outgoing work list.
  // We don't have ABA problem, since there is only one dedup thread.
  QueueChunkedList* push_and_get_atomic(QueueChunkedList* q, uint queue_num);
  QueueChunkedList* remove_work_list_atomic(uint queue_num);

  Monitor* lock() const { return _lock; }

  size_t claim();
};


class ShenandoahStrDedupQueueCleanupClosure : public OopClosure {
private:
  ShenandoahHeap*   _heap;

  template <class T>
  inline void do_oop_work(T* p);
public:
  ShenandoahStrDedupQueueCleanupClosure() : _heap(ShenandoahHeap::heap()) {
  }

  inline void do_oop(oop* p)        { do_oop_work(p); }
  inline void do_oop(narrowOop* p)  { do_oop_work(p); }
};

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHSTRINGDEDUPQUEUE_HPP
