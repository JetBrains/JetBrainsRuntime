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

#include "precompiled.hpp"
#include "gc/shenandoah/shenandoahArrayCopyTaskQueue.hpp"
#include "memory/allocation.hpp"
#include "runtime/mutex.hpp"
#include "runtime/mutexLocker.hpp"
#include "utilities/growableArray.hpp"

ShenandoahArrayCopyTaskQueue::ShenandoahArrayCopyTaskQueue() :
  _buffer(new (ResourceObj::C_HEAP, mtGC) GrowableArray<ShenandoahArrayCopyTask>(16, true, mtGC)),
  _lock(new Mutex(Mutex::leaf, "ShenandoahArrayCopyTaskQueueLock", false, Mutex::_safepoint_check_never)) {
}

ShenandoahArrayCopyTaskQueue::~ShenandoahArrayCopyTaskQueue() {
  delete _buffer;
  delete _lock;
}

void ShenandoahArrayCopyTaskQueue::push(HeapWord* obj) {
  MutexLockerEx locker(_lock, Mutex::_no_safepoint_check_flag);
  assert(obj != NULL, "no null obj");
  _buffer->push(ShenandoahArrayCopyTask(obj));
}

void ShenandoahArrayCopyTaskQueue::push(HeapWord* obj, size_t count) {
  MutexLockerEx locker(_lock, Mutex::_no_safepoint_check_flag);
  assert(obj != NULL, "no null obj");
  _buffer->push(ShenandoahArrayCopyTask(obj, count));
}

ShenandoahArrayCopyTask ShenandoahArrayCopyTaskQueue::pop() {
  MutexLockerEx locker(_lock, Mutex::_no_safepoint_check_flag);
  if (_buffer->length() == 0) {
    return ShenandoahArrayCopyTask(NULL); // no-more-work-indicator
  }
  ShenandoahArrayCopyTask task = _buffer->pop();
  assert(task.start() != NULL, "only non-NULL tasks in queue");
  return task;
}

size_t ShenandoahArrayCopyTaskQueue::length() const {
  return _buffer->length();
}

void ShenandoahArrayCopyTaskQueue::clear() {
  MutexLockerEx locker(_lock, Mutex::_no_safepoint_check_flag);
  return _buffer->clear();
}
