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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHARRAYCOPYTASKQUEUE_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHARRAYCOPYTASKQUEUE_HPP

#include "utilities/growableArray.hpp"

class HeapWord;
class Mutex;

class ShenandoahArrayCopyTask {
private:
  // For clone this is actually an oop, and _count == 0.
  // For arraycopy, this is the start of range, and _count is the number of elements.
  // Converst to oop* or narrowOop* as appropriate.
  HeapWord* _start;

  // For arraycopy, this is the number of elements. For clone, this == 0.
  size_t _count;

public:
  ShenandoahArrayCopyTask() :
    _start(NULL), _count(0) {}

  ShenandoahArrayCopyTask(const ShenandoahArrayCopyTask& o) :
    _start(o._start), _count(o._count) {}

  ShenandoahArrayCopyTask(HeapWord* start) :
    _start(start), _count(0) {}

  ShenandoahArrayCopyTask(HeapWord* start, size_t count) :
    _start(start), _count(count) {}

  HeapWord* start() { return _start; }
  size_t    count() { return _count; }

};

class ShenandoahArrayCopyTaskQueue {
private:
  GrowableArray<ShenandoahArrayCopyTask>* _buffer;
  Mutex* _lock;

public:
  ShenandoahArrayCopyTaskQueue();

  ~ShenandoahArrayCopyTaskQueue();

  void push(HeapWord* obj);
  void push(HeapWord* start, size_t count);
  ShenandoahArrayCopyTask pop();
  size_t length() const;
  void clear();
};

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHARRAYCOPYTASKQUEUE_HPP
