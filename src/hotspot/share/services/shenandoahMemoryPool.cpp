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

#include "precompiled.hpp"
#include "services/shenandoahMemoryPool.hpp"

ShenandoahDummyMemoryPool::ShenandoahDummyMemoryPool() :
        CollectedMemoryPool("Shenandoah Dummy",
                            MemoryPool::Heap,
                            0,
                            0,
                            false /* support_usage_threshold */) {}

ShenandoahMemoryPool::ShenandoahMemoryPool(ShenandoahHeap* heap) :
        CollectedMemoryPool("Shenandoah",
                            MemoryPool::Heap,
                            heap->capacity(),
                            heap->max_capacity(),
                            false /* support_usage_threshold */),
                            _heap(heap) {}

MemoryUsage ShenandoahMemoryPool::get_memory_usage() {
  size_t maxSize   = max_size();
  size_t used      = used_in_bytes();
  size_t committed = _heap->committed();

  return MemoryUsage(initial_size(), used, committed, maxSize);
}
