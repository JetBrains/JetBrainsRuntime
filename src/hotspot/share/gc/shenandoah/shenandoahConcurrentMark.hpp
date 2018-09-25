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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHCONCURRENTMARK_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHCONCURRENTMARK_HPP

#include "gc/shared/taskqueue.hpp"
#include "gc/shenandoah/shenandoahOopClosures.hpp"
#include "gc/shenandoah/shenandoahPhaseTimings.hpp"
#include "gc/shenandoah/shenandoahTaskqueue.hpp"

class ShenandoahStrDedupQueue;

class ShenandoahConcurrentMark: public CHeapObj<mtGC> {
  friend class ShenandoahTraversalGC;
private:
  ShenandoahHeap* _heap;

  // The per-worker-thread work queues
  ShenandoahObjToScanQueueSet* _task_queues;

  ShenandoahSharedFlag _claimed_codecache;

private:
  template <class T>
  inline void do_task(ShenandoahObjToScanQueue* q, T* cl, jushort* live_data, ShenandoahMarkTask* task);

  template <class T>
  inline void do_chunked_array_start(ShenandoahObjToScanQueue* q, T* cl, oop array);

  template <class T>
  inline void do_chunked_array(ShenandoahObjToScanQueue* q, T* cl, oop array, int chunk, int pow);

  inline void count_liveness(jushort* live_data, oop obj);

  // Actual mark loop with closures set up
  template <class T, bool CANCELLABLE>
  void mark_loop_work(T* cl, jushort* live_data, uint worker_id, ParallelTaskTerminator *t);

  template <bool CANCELLABLE>
  void mark_loop_prework(uint worker_id, ParallelTaskTerminator *terminator, ReferenceProcessor *rp, bool strdedup);

public:
  // Mark loop entry.
  // Translates dynamic arguments to template parameters with progressive currying.
  void mark_loop(uint worker_id, ParallelTaskTerminator* terminator, ReferenceProcessor *rp,
                 bool cancellable, bool strdedup) {
    if (cancellable) {
      mark_loop_prework<true>(worker_id, terminator, rp, strdedup);
    } else {
      mark_loop_prework<false>(worker_id, terminator, rp, strdedup);
    }
  }

  // We need to do this later when the heap is already created.
  void initialize(uint workers);

  bool claim_codecache();
  void clear_claim_codecache();

  template<class T, UpdateRefsMode UPDATE_REFS, StringDedupMode STRING_DEDUP>
  static inline void mark_through_ref(T* p, ShenandoahHeap* heap, ShenandoahObjToScanQueue* q, ShenandoahMarkingContext* const mark_context);

  void mark_from_roots();

  // Prepares unmarked root objects by marking them and putting
  // them into the marking task queue.
  void init_mark_roots();
  void mark_roots(ShenandoahPhaseTimings::Phase root_phase);
  void update_roots(ShenandoahPhaseTimings::Phase root_phase);

  void shared_finish_mark_from_roots(bool full_gc);
  void finish_mark_from_roots();
  // Those are only needed public because they're called from closures.

  ShenandoahObjToScanQueue* get_queue(uint worker_id);

  ShenandoahObjToScanQueueSet* task_queues() { return _task_queues;}

  void cancel();

  void preclean_weak_refs();

  void concurrent_scan_code_roots(uint worker_id, ReferenceProcessor* rp);
private:

  void weak_refs_work(bool full_gc);
  void weak_refs_work_doit(bool full_gc);
};

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHCONCURRENTMARK_HPP
