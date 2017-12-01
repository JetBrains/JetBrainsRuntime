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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHPARTIALGC_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHPARTIALGC_HPP

#include "memory/allocation.hpp"
#include "gc/shenandoah/shenandoahTaskqueue.hpp"

class Thread;
class ShenandoahHeapRegionSet;
class ShenandoahHeap;

class ShenandoahPartialGC : public CHeapObj<mtGC> {
private:
  ShenandoahHeapRegionSet* _root_regions;
  ShenandoahHeap* _heap;
  ShenandoahConnectionMatrix* _matrix;
  ShenandoahObjToScanQueueSet* _task_queues;
  size_t* from_idxs;
  ShenandoahSharedFlag _has_work;

  void set_has_work(bool value);

public:
  ShenandoahPartialGC(ShenandoahHeap* heap, size_t num_regions);
  ~ShenandoahPartialGC();

  bool has_work();

  void reset();
  bool prepare();
  void init_partial_collection();
  void concurrent_partial_collection();
  void final_partial_collection();

  template <class T, bool UPDATE_MATRIX>
  void process_oop(T* p, Thread* thread, ShenandoahObjToScanQueue* queue);

  template <bool DO_SATB>
  void main_loop(uint worker_id, ParallelTaskTerminator* terminator);

  bool check_and_handle_cancelled_gc(ParallelTaskTerminator* terminator);

  ShenandoahObjToScanQueueSet* task_queues();
};

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHPARTIALGC_HPP
