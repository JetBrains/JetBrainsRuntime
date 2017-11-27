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


#include "precompiled.hpp"
#include "code/codeCache.hpp"
#include "code/nmethod.hpp"
#include "gc/shenandoah/shenandoahHeap.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahCodeRoots.hpp"

class ShenandoahNMethodCountOops : public OopClosure {
public:
  size_t _non_null_oops;
  ShenandoahNMethodCountOops() : _non_null_oops(0) {};

private:
  template <class T>
  inline void do_oop_work(T* p) {
    T o = oopDesc::load_heap_oop(p);
    if (! oopDesc::is_null(o)) {
      _non_null_oops++;
    }
  }

public:
  void do_oop(oop* o) {
    do_oop_work(o);
  }
  void do_oop(narrowOop* o) {
    do_oop_work(o);
  }
  bool has_oops() {
    return _non_null_oops > 0;
  }
};

class ShenandoahNMethodHasCSetOops : public OopClosure {
public:
  ShenandoahHeap* _heap;
  bool _has_cset_oops;
  ShenandoahNMethodHasCSetOops(ShenandoahHeap* heap) : _heap(heap), _has_cset_oops(false) {};

private:
  template <class T>
  inline void do_oop_work(T* p) {
    if (_has_cset_oops) return;
    T o = oopDesc::load_heap_oop(p);
    if (! oopDesc::is_null(o)) {
      oop obj1 = oopDesc::decode_heap_oop_not_null(o);
      if (_heap->in_collection_set(obj1)) {
        _has_cset_oops = true;
      }
    }
  }

public:
  void do_oop(oop* o) {
    do_oop_work(o);
  }
  void do_oop(narrowOop* o) {
    do_oop_work(o);
  }
  bool has_in_cset_oops() {
    return _has_cset_oops;
  }
};

class ShenandoahNMethodOopInitializer : public OopClosure {
public:
  ShenandoahNMethodOopInitializer() {};

private:
  template <class T>
  inline void do_oop_work(T* p) {
    T o = oopDesc::load_heap_oop(p);
    if (! oopDesc::is_null(o)) {
      oop obj1 = oopDesc::decode_heap_oop_not_null(o);
      oop obj2 = oopDesc::bs()->write_barrier(obj1);
      if (! oopDesc::unsafe_equals(obj1, obj2)) {
        assert (!ShenandoahHeap::heap()->in_collection_set(obj2), "sanity");
        oopDesc::encode_store_heap_oop(p, obj2);
      }
    }
  }

public:
  void do_oop(oop* o) {
    do_oop_work(o);
  }
  void do_oop(narrowOop* o) {
    do_oop_work(o);
  }
};

volatile jint ShenandoahCodeRoots::_recorded_nmethods_lock;
GrowableArray<nmethod*>* ShenandoahCodeRoots::_recorded_nmethods;

void ShenandoahCodeRoots::initialize() {
  ResourceMark rm;
  _recorded_nmethods_lock = 0;
  _recorded_nmethods = new (ResourceObj::C_HEAP, mtGC) GrowableArray<nmethod*>(100, true, mtGC);
}

void ShenandoahCodeRoots::add_nmethod(nmethod* nm) {
  switch (ShenandoahCodeRootsStyle) {
    case 0:
    case 1: {
      ShenandoahNMethodOopInitializer init;
      nm->oops_do(&init);
      nm->fix_oop_relocations();
      break;
    }
    case 2: {
      fast_add_nmethod(nm);
      break;
    }
    default:
      ShouldNotReachHere();
  }
};

void ShenandoahCodeRoots::remove_nmethod(nmethod* nm) {
  switch (ShenandoahCodeRootsStyle) {
    case 0:
    case 1:
      break;
    case 2: {
      fast_remove_nmethod(nm);
      break;
    }
    default:
      ShouldNotReachHere();
  }
}

void ShenandoahCodeRoots::fast_add_nmethod(nmethod *nm) {
  ShenandoahNMethodCountOops count;
  nm->oops_do(&count);
  if (count.has_oops()) {
    ShenandoahNMethodOopInitializer init;
    nm->oops_do(&init);
    nm->fix_oop_relocations();

    ShenandoahCodeRootsLock lock(true);
    if (_recorded_nmethods->find(nm) == -1) {
      // Record methods once.
      _recorded_nmethods->append(nm);
    }
  }
}

void ShenandoahCodeRoots::fast_remove_nmethod(nmethod* nm) {
  ShenandoahNMethodCountOops count;
  nm->oops_do(&count, /* allow_zombie = */ true);
  if (count.has_oops()) {
    ShenandoahCodeRootsLock lock(true);

    // GrowableArray::delete_at is O(1), which is exactly what we want.
    // TODO: Consider making _recorded_nmethods a HashTable to make find amortized O(1) too.
    int idx = _recorded_nmethods->find(nm);
    assert(idx != -1, "nmethod " PTR_FORMAT " should be registered", p2i(nm));
    _recorded_nmethods->delete_at(idx);
  }
};

ShenandoahAllCodeRootsIterator ShenandoahCodeRoots::iterator() {
  return ShenandoahAllCodeRootsIterator();
}

ShenandoahCsetCodeRootsIterator ShenandoahCodeRoots::cset_iterator() {
  return ShenandoahCsetCodeRootsIterator();
}

ShenandoahCodeRootsIterator::ShenandoahCodeRootsIterator() :
        _claimed(0), _heap(ShenandoahHeap::heap()),
        _par_iterator(CodeCache::parallel_iterator()), _seq_claimed(0) {
  switch (ShenandoahCodeRootsStyle) {
    case 0:
    case 1:
      break;
    case 2:
      ShenandoahCodeRoots::acquire_lock(false);
      break;
    default:
      ShouldNotReachHere();
  }
}

ShenandoahCodeRootsIterator::~ShenandoahCodeRootsIterator() {
  switch (ShenandoahCodeRootsStyle) {
    case 0:
    case 1:
      break;
    case 2:
      ShenandoahCodeRoots::release_lock(false);
      break;
    default:
      ShouldNotReachHere();
  }
}

template<bool CSET_FILTER>
void ShenandoahCodeRootsIterator::dispatch_parallel_blobs_do(CodeBlobClosure *f) {
  switch (ShenandoahCodeRootsStyle) {
    case 0:
      if (Atomic::cmpxchg((jbyte)1, &_seq_claimed, (jbyte)0) == 0) {
        CodeCache::blobs_do(f);
      }
      break;
    case 1:
      _par_iterator.parallel_blobs_do(f);
      break;
    case 2: {
      ShenandoahCodeRootsIterator::fast_parallel_blobs_do<false>(f);
      break;
    }
    default:
      ShouldNotReachHere();
  }
}

void ShenandoahAllCodeRootsIterator::possibly_parallel_blobs_do(CodeBlobClosure *f) {
  ShenandoahCodeRootsIterator::dispatch_parallel_blobs_do<false>(f);
}

void ShenandoahCsetCodeRootsIterator::possibly_parallel_blobs_do(CodeBlobClosure *f) {
  ShenandoahCodeRootsIterator::dispatch_parallel_blobs_do<true>(f);
}

template <bool CSET_FILTER>
void ShenandoahCodeRootsIterator::fast_parallel_blobs_do(CodeBlobClosure *f) {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Must be at safepoint");

  size_t stride = 256; // educated guess

  GrowableArray<nmethod *>* list = ShenandoahCodeRoots::_recorded_nmethods;

  size_t max = (size_t)list->length();
  while (_claimed < max) {
    size_t cur = Atomic::add(stride, &_claimed) - stride;
    size_t start = cur;
    size_t end = MIN2(cur + stride, max);
    if (start >= max) break;

    for (size_t idx = start; idx < end; idx++) {
      nmethod *nm = list->at((int) idx);
      assert (nm->is_alive(), "only alive nmethods here");

      if (CSET_FILTER) {
        ShenandoahNMethodHasCSetOops scan(_heap);
        nm->oops_do(&scan);
        if (!scan.has_in_cset_oops()) {
          continue;
        }
      }

      f->do_code_blob(nm);
    }
  }
}
