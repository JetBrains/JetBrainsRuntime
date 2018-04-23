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

#ifndef SHARE_GC_SHENANDOAH_SHENANDOAHTHREADLOCALDATA_HPP
#define SHARE_GC_SHENANDOAH_SHENANDOAHTHREADLOCALDATA_HPP

#include "gc/g1/satbMarkQueue.hpp"
#include "gc/shenandoah/shenandoahBarrierSet.hpp"
#include "runtime/thread.hpp"
#include "utilities/debug.hpp"
#include "utilities/sizes.hpp"

class ShenandoahThreadLocalData {
private:
  char _gc_state;
  char _oom_during_evac;
  SATBMarkQueue  _satb_mark_queue;

  ShenandoahThreadLocalData() :
    _gc_state(0),
    _oom_during_evac(0),
    _satb_mark_queue(&ShenandoahBarrierSet::satb_mark_queue_set()) {}

  static ShenandoahThreadLocalData* data(Thread* thread) {
    assert(UseShenandoahGC, "Sanity");
    return thread->gc_data<ShenandoahThreadLocalData>();
  }

  static ByteSize satb_mark_queue_offset() {
    return Thread::gc_data_offset() + byte_offset_of(ShenandoahThreadLocalData, _satb_mark_queue);
  }

public:
  static void create(Thread* thread) {
    new (data(thread)) ShenandoahThreadLocalData();
  }

  static void destroy(Thread* thread) {
    data(thread)->~ShenandoahThreadLocalData();
  }

  static SATBMarkQueue& satb_mark_queue(Thread* thread) {
    return data(thread)->_satb_mark_queue;
  }

  static bool is_oom_during_evac(Thread* thread) {
    return (data(thread)->_oom_during_evac & 1) == 1;
  }

  static void set_oom_during_evac(Thread* thread, bool oom) {
    if (oom) {
      data(thread)->_oom_during_evac |= 1;
    } else {
      data(thread)->_oom_during_evac &= ~1;
    }
  }

  static void set_gc_state(Thread* thread, char gc_state) {
    data(thread)->_gc_state = gc_state;
  }

#ifdef ASSERT
  static void set_evac_allowed(Thread* thread, bool evac_allowed) {
    if (evac_allowed) {
      data(thread)->_oom_during_evac |= 2;
    } else {
      data(thread)->_oom_during_evac &= ~2;
    }
  }

  static bool is_evac_allowed(Thread* thread) {
    return (data(thread)->_oom_during_evac & 2) == 2;
  }
#endif

  // Offsets
  static ByteSize satb_mark_queue_active_offset() {
    return satb_mark_queue_offset() + SATBMarkQueue::byte_offset_of_active();
  }

  static ByteSize satb_mark_queue_index_offset() {
    return satb_mark_queue_offset() + SATBMarkQueue::byte_offset_of_index();
  }

  static ByteSize satb_mark_queue_buffer_offset() {
    return satb_mark_queue_offset() + SATBMarkQueue::byte_offset_of_buf();
  }

  static ByteSize gc_state_offset() {
    return Thread::gc_data_offset() + byte_offset_of(ShenandoahThreadLocalData, _gc_state);
  }

};

#endif // SHARE_GC_SHENANDOAH_SHENANDOAHTHREADLOCALDATA_HPP
