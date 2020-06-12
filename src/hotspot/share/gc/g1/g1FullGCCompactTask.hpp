/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
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

#ifndef SHARE_GC_G1_G1FULLGCCOMPACTTASK_HPP
#define SHARE_GC_G1_G1FULLGCCOMPACTTASK_HPP

#include "gc/g1/g1FullGCCompactionPoint.hpp"
#include "gc/g1/g1FullGCScope.hpp"
#include "gc/g1/g1FullGCTask.hpp"
#include "gc/g1/g1StringDedup.hpp"
#include "gc/g1/heapRegionManager.hpp"
#include "gc/shared/referenceProcessor.hpp"

class G1CollectedHeap;
class G1CMBitMap;

class G1FullGCCompactTask : public G1FullGCTask {
protected:
  HeapRegionClaimer _claimer;

private:
  void compact_region(HeapRegion* hr);
  void compact_region_dcevm(HeapRegion* hr, GrowableArray<HeapWord*>* rescued_oops_values, 
    GrowableArrayIterator<HeapWord*>* rescue_oops_it);

public:
  G1FullGCCompactTask(G1FullCollector* collector) :
    G1FullGCTask("G1 Compact Task", collector),
    _claimer(collector->workers()) { }
  void work(uint worker_id);
  void serial_compaction();
  void serial_compaction_dcevm();

  class G1CompactRegionClosure : public StackObj {
    G1CMBitMap* _bitmap;

  public:
    G1CompactRegionClosure(G1CMBitMap* bitmap) : _bitmap(bitmap) { }
    size_t apply(oop object);
  };

  class G1CompactRegionClosureDcevm : public StackObj {
    G1CMBitMap* _bitmap;
    GrowableArray<HeapWord*>* _rescued_oops_values;
    GrowableArrayIterator<HeapWord*>* _rescue_oops_it;

  public:
    G1CompactRegionClosureDcevm(G1CMBitMap* bitmap,
                           GrowableArray<HeapWord*>* rescued_oops_values,
                           GrowableArrayIterator<HeapWord*>* rescue_oops_it) :
      _bitmap(bitmap),
      _rescued_oops_values(rescued_oops_values),
      _rescue_oops_it(rescue_oops_it)
      { }
    size_t apply(oop object);
  };
};

#endif // SHARE_GC_G1_G1FULLGCCOMPACTTASK_HPP
