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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHSTRINGDEDUPTHREAD_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHSTRINGDEDUPTHREAD_HPP

#include "gc/shared/concurrentGCThread.hpp"
#include "gc/shenandoah/shenandoahStrDedupQueue.hpp"
#include "memory/iterator.hpp"

class ShenandoahStrDedupStats;

class ShenandoahStrDedupThread: public ConcurrentGCThread {
private:
  ShenandoahStrDedupQueueSet*   _queues;
  QueueChunkedList**            _work_list;
  volatile size_t               _claimed;
public:
  ShenandoahStrDedupThread(ShenandoahStrDedupQueueSet* queues);
  ~ShenandoahStrDedupThread();

  void clear_claimed() { _claimed = 0; }
  void parallel_oops_do(OopClosure* cl);
  void parallel_cleanup();

  // For verification only
  void oops_do_slow(OopClosure* cl);

  void run_service();
  void stop_service();

private:
  bool poll(ShenandoahStrDedupStats* stats);
  bool is_work_list_empty() const;

  ShenandoahStrDedupQueueSet* queues() const {
    return _queues;
  }

  size_t claim();
};

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHSTRINGDEDUPTHREAD_HPP
