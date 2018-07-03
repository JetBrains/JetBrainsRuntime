/*
 * Copyright (c) 2018, Red Hat, Inc. and/or its affiliates.
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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHTRAVERSALGC_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHTRAVERSALGC_HPP

#include "memory/allocation.hpp"
#include "gc/shenandoah/shenandoahHeapRegionSet.hpp"
#include "gc/shenandoah/shenandoahTaskqueue.hpp"
#include "gc/shenandoah/shenandoahArrayCopyTaskQueue.hpp"

class Thread;
class ShenandoahHeap;
class ShenandoahStrDedupQueue;

class ShenandoahTraversalGC : public CHeapObj<mtGC> {
private:
  ShenandoahHeap* const _heap;
  ShenandoahObjToScanQueueSet* const _task_queues;

  // Used for buffering per-region liveness data.
  // Needed since ShenandoahHeapRegion uses atomics to update liveness.
  //
  // The array has max-workers elements, each of which is an array of
  // jushort * max_regions. The choice of jushort is not accidental:
  // there is a tradeoff between static/dynamic footprint that translates
  // into cache pressure (which is already high during marking), and
  // too many atomic updates. size_t/jint is too large, jbyte is too small.
  jushort** _liveness_local;

  ShenandoahHeapRegionSet _traversal_set;
  ShenandoahHeapRegionSet _root_regions;

  ShenandoahHeapRegionSetIterator _root_regions_iterator;

  ShenandoahConnectionMatrix* const _matrix;

  ShenandoahArrayCopyTaskQueue _arraycopy_task_queue;

public:
  ShenandoahTraversalGC(ShenandoahHeap* heap, size_t num_regions);
  ~ShenandoahTraversalGC();

  ShenandoahHeapRegionSet* traversal_set() { return &_traversal_set; }
  ShenandoahHeapRegionSet* root_regions()  { return &_root_regions;}

  void reset();
  void prepare();
  void init_traversal_collection();
  void concurrent_traversal_collection();
  void final_traversal_collection();

  template <class T, bool STRING_DEDUP, bool DEGEN, bool UPDATE_MATRIX>
  inline void process_oop(T* p, Thread* thread, ShenandoahObjToScanQueue* queue, oop base_obj);

  bool check_and_handle_cancelled_gc(ParallelTaskTerminator* terminator);

  ShenandoahObjToScanQueueSet* task_queues();

  jushort* get_liveness(uint worker_id);
  void flush_liveness(uint worker_id);

  void main_loop(uint worker_id, ParallelTaskTerminator* terminator);

  void push_arraycopy(HeapWord* start, size_t count);

private:

  void prepare_regions();

  template <class T>
  void main_loop_work(T* cl, jushort* live_data, uint worker_id, ParallelTaskTerminator* terminator);

  void preclean_weak_refs();
  void weak_refs_work();
  void weak_refs_work_doit();

  void fixup_roots();

  template <class T>
  bool process_arraycopy_task(T* cl);
};

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHTRAVERSALGC_HPP
