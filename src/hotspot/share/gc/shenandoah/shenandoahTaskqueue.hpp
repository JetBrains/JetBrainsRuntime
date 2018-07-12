/*
 * Copyright (c) 2016, Red Hat, Inc. and/or its affiliates.
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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHTASKQUEUE_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHTASKQUEUE_HPP

#include "gc/shared/taskqueue.hpp"
#include "gc/shared/taskqueue.inline.hpp"
#include "runtime/mutex.hpp"
#include "runtime/thread.hpp"

typedef ObjArrayChunkedTask ShenandoahMarkTask;
typedef BufferedOverflowTaskQueue<ShenandoahMarkTask, mtGC> ShenandoahBufferedOverflowTaskQueue;
typedef Padded<ShenandoahBufferedOverflowTaskQueue> ShenandoahObjToScanQueue;

template <class T, MEMFLAGS F>
class ParallelClaimableQueueSet: public GenericTaskQueueSet<T, F> {
private:
  volatile jint     _claimed_index;
  debug_only(uint   _reserved;  )

public:
  using GenericTaskQueueSet<T, F>::size;

public:
  ParallelClaimableQueueSet(int n) : GenericTaskQueueSet<T, F>(n), _claimed_index(0) {
    debug_only(_reserved = 0; )
  }

  void clear_claimed() { _claimed_index = 0; }
  T*   claim_next();

  // reserve queues that not for parallel claiming
  void reserve(uint n) {
    assert(n <= size(), "Sanity");
    _claimed_index = (jint)n;
    debug_only(_reserved = n;)
  }

  debug_only(uint get_reserved() const { return (uint)_reserved; })
};


template <class T, MEMFLAGS F>
T* ParallelClaimableQueueSet<T, F>::claim_next() {
  jint size = (jint)GenericTaskQueueSet<T, F>::size();

  if (_claimed_index >= size) {
    return NULL;
  }

  jint index = Atomic::add(1, &_claimed_index);

  if (index <= size) {
    return GenericTaskQueueSet<T, F>::queue((uint)index - 1);
  } else {
    return NULL;
  }
}

class ShenandoahObjToScanQueueSet: public ParallelClaimableQueueSet<ShenandoahObjToScanQueue, mtGC> {

public:
  ShenandoahObjToScanQueueSet(int n) : ParallelClaimableQueueSet<ShenandoahObjToScanQueue, mtGC>(n) {
  }

  bool is_empty();
  void clear();

#if TASKQUEUE_STATS
  static void print_taskqueue_stats_hdr(outputStream* const st);
  void print_taskqueue_stats() const;
  void reset_taskqueue_stats();
#endif // TASKQUEUE_STATS
};


/*
 * This is an enhanced implementation of Google's work stealing
 * protocol, which is described in the paper:
 * Understanding and improving JVM GC work stealing at the data center scale
 * (http://dl.acm.org/citation.cfm?id=2926706)
 *
 * Instead of a dedicated spin-master, our implementation will let spin-master to relinquish
 * the role before it goes to sleep/wait, so allows newly arrived thread to compete for the role.
 * The intention of above enhancement, is to reduce spin-master's latency on detecting new tasks
 * for stealing and termination condition.
 */

class ShenandoahTaskTerminator: public ParallelTaskTerminator {
private:
  Monitor*    _blocker;
  Thread*     _spin_master;


public:
  ShenandoahTaskTerminator(uint n_threads, TaskQueueSetSuper* queue_set) :
    ParallelTaskTerminator(n_threads, queue_set), _spin_master(NULL) {
    _blocker = new Monitor(Mutex::leaf, "ShenandoahTaskTerminator", false, Monitor::_safepoint_check_never);
  }

  ~ShenandoahTaskTerminator() {
    assert(_blocker != NULL, "Can not be NULL");
    delete _blocker;
  }

  bool offer_termination(TerminatorTerminator* terminator);

private:
  size_t tasks_in_queue_set() { return _queue_set->tasks(); }


  /*
   * Perform spin-master task.
   * return true if termination condition is detected
   * otherwise, return false
   */
  bool do_spin_master_work(TerminatorTerminator* terminator);
};

class ShenandoahCancelledTerminatorTerminator : public TerminatorTerminator {
  virtual bool should_exit_termination() {
    return false;
  }
  virtual bool should_force_termination() {
    return true;
  }
};

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHTASKQUEUE_HPP
