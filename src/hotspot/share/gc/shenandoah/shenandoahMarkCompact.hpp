/*
 * Copyright (c) 2014, 2015, Red Hat, Inc. and/or its affiliates.
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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHMARKCOMPACT_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHMARKCOMPACT_HPP

#include "gc/serial/genMarkSweep.hpp"
#include "gc/shared/taskqueue.hpp"
#include "gc/shared/workgroup.hpp"
#include "gc/shenandoah/shenandoahHeap.hpp"

class HeapWord;

/**
 * This implements full-GC (e.g. when invoking System.gc() ) using a
 * mark-compact algorithm. It's implemented in four phases:
 *
 * 1. Mark all live objects of the heap by traversing objects starting at GC roots.
 * 2. Calculate the new location of each live object. This is done by sequentially scanning
 *    the heap, keeping track of a next-location-pointer, which is then written to each
 *    object's brooks ptr field.
 * 3. Update all references. This is implemented by another scan of the heap, and updates
 *    all references in live objects by what's stored in the target object's brooks ptr.
 * 3. Compact the heap by copying all live objects to their new location.
 */

class ShenandoahMarkCompact: AllStatic {
private:
  static STWGCTimer* _gc_timer;

public:
  static void initialize();
  static void do_mark_compact(GCCause::Cause gc_cause);

  static GCTimer* gc_timer() {
    assert(_gc_timer != NULL, "Timer not yet initialized");
    return _gc_timer;
  }

private:

  static void phase1_mark_heap();
  static void phase2_calculate_target_addresses(ShenandoahHeapRegionSet** copy_queues);
  static void phase3_update_references();
  static void phase4_compact_objects(ShenandoahHeapRegionSet** copy_queues);

};

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHMARKCOMPACT_HPP
