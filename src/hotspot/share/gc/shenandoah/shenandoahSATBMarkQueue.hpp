/*
 * Copyright (c) 2001, 2019, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_VM_GC_SHENANDOAH_SATBMARKQUEUE_HPP
#define SHARE_VM_GC_SHENANDOAH_SATBMARKQUEUE_HPP

#include "gc/g1/ptrQueue.hpp"
#include "memory/allocation.hpp"

class JavaThread;
class ShenandoahSATBMarkQueueSet;

// Base class for processing the contents of a SATB buffer.
class ShenandoahSATBBufferClosure : public StackObj {
protected:
  ~ShenandoahSATBBufferClosure() { }

public:
  // Process the SATB entries in the designated buffer range.
  virtual void do_buffer(void** buffer, size_t size) = 0;
};

// A PtrQueue whose elements are (possibly stale) pointers to object heads.
class ShenandoahSATBMarkQueue: public PtrQueue {
  friend class ShenandoahSATBMarkQueueSet;

private:
  // Filter out unwanted entries from the buffer.
  void filter();

  template <class HeapType>
  void filter_impl();

public:
  ShenandoahSATBMarkQueue(ShenandoahSATBMarkQueueSet* qset, bool permanent = false);

  // Process queue entries and free resources.
  void flush();

  // Apply cl to the active part of the buffer.
  // Prerequisite: Must be at a safepoint.
  void apply_closure_and_empty(ShenandoahSATBBufferClosure* cl);

  // Overrides PtrQueue::should_enqueue_buffer(). See the method's
  // definition for more information.
  virtual bool should_enqueue_buffer();

#ifndef PRODUCT
  // Helpful for debugging
  void print(const char* name);
#endif // PRODUCT

  // Compiler support.
  static ByteSize byte_offset_of_index() {
    return PtrQueue::byte_offset_of_index<ShenandoahSATBMarkQueue>();
  }
  using PtrQueue::byte_width_of_index;

  static ByteSize byte_offset_of_buf() {
    return PtrQueue::byte_offset_of_buf<ShenandoahSATBMarkQueue>();
  }
  using PtrQueue::byte_width_of_buf;

  static ByteSize byte_offset_of_active() {
    return PtrQueue::byte_offset_of_active<ShenandoahSATBMarkQueue>();
  }
  using PtrQueue::byte_width_of_active;

};

class ShenandoahSATBMarkQueueSet: public PtrQueueSet {
  ShenandoahSATBMarkQueue _shared_satb_queue;

#ifdef ASSERT
  void dump_active_states(bool expected_active);
  void verify_active_states(bool expected_active);
#endif // ASSERT

public:
  ShenandoahSATBMarkQueueSet();

  void initialize(Monitor* cbl_mon, Mutex* fl_lock,
                  int process_completed_threshold,
                  Mutex* lock);

  ShenandoahSATBMarkQueue& satb_queue_for_thread(Thread* t);

  // Apply "set_active(active)" to all SATB queues in the set. It should be
  // called only with the world stopped. The method will assert that the
  // SATB queues of all threads it visits, as well as the SATB queue
  // set itself, has an active value same as expected_active.
  void set_active_all_threads(bool active, bool expected_active);

  // Filter all the currently-active SATB buffers.
  void filter_thread_buffers();

  // If there exists some completed buffer, pop and process it, and
  // return true.  Otherwise return false.  Processing a buffer
  // consists of applying the closure to the active range of the
  // buffer; the leading entries may be excluded due to filtering.
  bool apply_closure_to_completed_buffer(ShenandoahSATBBufferClosure* cl);

#ifndef PRODUCT
  // Helpful for debugging
  void print_all(const char* msg);
#endif // PRODUCT

  ShenandoahSATBMarkQueue* shared_satb_queue() { return &_shared_satb_queue; }

  // If a marking is being abandoned, reset any unprocessed log buffers.
  void abandon_partial_marking();
};

#endif // SHARE_VM_GC_SHENANDOAH_SATBMARKQUEUE_HPP
