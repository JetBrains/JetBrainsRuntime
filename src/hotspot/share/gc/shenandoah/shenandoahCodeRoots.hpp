/*
 * Copyright (c) 2017, 2018, Red Hat, Inc. and/or its affiliates.
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


#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHCODEROOTS_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHCODEROOTS_HPP

#include "code/codeCache.hpp"
#include "memory/allocation.hpp"
#include "memory/iterator.hpp"

class ShenandoahHeap;
class ShenandoahHeapRegion;
class ShenandoahCodeRootsLock;

// ShenandoahNMethod tuple records the internal locations of oop slots within the nmethod.
// This allows us to quickly scan the oops without doing the nmethod-internal scans, that
// sometimes involves parsing the machine code. Note it does not record the oops themselves,
// because it would then require handling these tuples as the new class of roots.
class ShenandoahNMethod : public CHeapObj<mtGC> {
private:
  nmethod* _nm;
  oop**    _oops;
  int      _oops_count;

public:
  ShenandoahNMethod(nmethod *nm, GrowableArray<oop*>* oops);
  ~ShenandoahNMethod();

  nmethod* nm() {
    return _nm;
  }

  bool has_cset_oops(ShenandoahHeap* heap);

  void assert_alive_and_correct() PRODUCT_RETURN;
  void assert_same_oops(GrowableArray<oop*>* oops) PRODUCT_RETURN;

  static bool find_with_nmethod(void* nm, ShenandoahNMethod* other) {
    return other->_nm == nm;
  }
};

class ShenandoahCodeRootsIterator {
  friend class ShenandoahCodeRoots;
protected:
  ShenandoahHeap* _heap;
  ParallelCodeCacheIterator _par_iterator;
  ShenandoahSharedFlag _seq_claimed;
  volatile size_t _claimed;
protected:
  ShenandoahCodeRootsIterator();
  ~ShenandoahCodeRootsIterator();

  template<bool CSET_FILTER>
  void dispatch_parallel_blobs_do(CodeBlobClosure *f);

  template<bool CSET_FILTER>
  void fast_parallel_blobs_do(CodeBlobClosure *f);
};

class ShenandoahAllCodeRootsIterator : public ShenandoahCodeRootsIterator {
public:
  ShenandoahAllCodeRootsIterator() : ShenandoahCodeRootsIterator() {};
  void possibly_parallel_blobs_do(CodeBlobClosure *f);
};

class ShenandoahCsetCodeRootsIterator : public ShenandoahCodeRootsIterator {
public:
  ShenandoahCsetCodeRootsIterator() : ShenandoahCodeRootsIterator() {};
  void possibly_parallel_blobs_do(CodeBlobClosure* f);
};

class ShenandoahCodeRoots : public CHeapObj<mtGC> {
  friend class ShenandoahHeap;
  friend class ShenandoahCodeRootsLock;
  friend class ShenandoahCodeRootsIterator;

public:
  static void initialize();
  static void add_nmethod(nmethod* nm);
  static void remove_nmethod(nmethod* nm);

  /**
   * Provides the iterator over all nmethods in the code cache that have oops.
   * @return
   */
  static ShenandoahAllCodeRootsIterator iterator();

  /**
   * Provides the iterator over nmethods that have at least one oop in collection set.
   * @return
   */
  static ShenandoahCsetCodeRootsIterator cset_iterator();

private:
  static volatile int _recorded_nms_lock;
  static GrowableArray<ShenandoahNMethod*>* _recorded_nms;

  static void acquire_lock(bool write) {
    volatile int* loc = &_recorded_nms_lock;
    if (write) {
      while ((OrderAccess::load_acquire(loc) != 0) ||
             Atomic::cmpxchg(-1, loc, 0) != 0) {
        SpinPause();
      }
      assert (*loc == -1, "acquired for write");
    } else {
      while (true) {
        int cur = OrderAccess::load_acquire(loc);
        if (cur >= 0) {
          if (Atomic::cmpxchg(cur + 1, loc, cur) == cur) {
            // Success!
            return;
          }
        }
        SpinPause();
      }
      assert (*loc > 1, "acquired for read");
    }
  }

  static void release_lock(bool write) {
    volatile int* loc = &ShenandoahCodeRoots::_recorded_nms_lock;
    if (write) {
      OrderAccess::release_store_fence(loc, 0);
    } else {
      Atomic::dec(loc);
    }
  }
};

// Very simple unranked read-write lock
class ShenandoahCodeRootsLock : public StackObj {
  friend class ShenandoahCodeRoots;
private:
  const bool _write;
public:
  ShenandoahCodeRootsLock(bool write) : _write(write) {
    ShenandoahCodeRoots::acquire_lock(write);
  }

  ~ShenandoahCodeRootsLock() {
    ShenandoahCodeRoots::release_lock(_write);
  }
};

#endif //SHARE_VM_GC_SHENANDOAH_SHENANDOAHCODEROOTS_HPP
