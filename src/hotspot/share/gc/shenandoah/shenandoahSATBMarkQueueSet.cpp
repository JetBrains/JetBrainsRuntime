/*
 * Copyright (c) 2018, Red Hat, Inc. All rights reserved.
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

#include "gc/shenandoah/shenandoahSATBMarkQueueSet.hpp"
#include "gc/shenandoah/shenandoahThreadLocalData.hpp"

SATBMarkQueue& ShenandoahSATBMarkQueueSet::satb_queue_for_thread(Thread* t) {
  return ShenandoahThreadLocalData::satb_mark_queue(t);
}

bool ShenandoahSATBMarkQueue::should_enqueue_buffer() {
  bool should_enqueue = SATBMarkQueue::should_enqueue_buffer();
  size_t cap = capacity();
  Thread* t = Thread::current();
  if (ShenandoahThreadLocalData::is_force_satb_flush(t)) {
    if (!should_enqueue && cap != index()) {
      // Non-empty buffer is compacted, and we decided not to enqueue it.
      // We still want to know about leftover work in that buffer eventually.
      // This avoid dealing with these leftovers during the final-mark, after
      // the buffers are drained completely. See JDK-8205353 for more discussion.
      should_enqueue = true;
    }
    ShenandoahThreadLocalData::set_force_satb_flush(t, false);
  }
  return should_enqueue;
}
