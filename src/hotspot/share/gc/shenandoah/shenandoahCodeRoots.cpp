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


#include "precompiled.hpp"
#include "code/codeCache.hpp"
#include "code/nmethod.hpp"
#include "gc/shenandoah/shenandoahHeap.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahCodeRoots.hpp"

class ShenandoahNMethodOopDetector : public OopClosure {
private:
  ShenandoahHeap* _heap;
  GrowableArray<oop*> _oops;

public:
  ShenandoahNMethodOopDetector() : _heap(ShenandoahHeap::heap()), _oops(10) {};

  void do_oop(oop* o) {
    _oops.append(o);
  }
  void do_oop(narrowOop* o) {
    fatal("NMethods should not have compressed oops embedded.");
  }

  GrowableArray<oop*>* oops() {
    return &_oops;
  }

  bool has_oops() {
    return !_oops.is_empty();
  }
};

class ShenandoahNMethodOopInitializer : public OopClosure {
public:
  ShenandoahNMethodOopInitializer() {};

private:
  template <class T>
  inline void do_oop_work(T* p) {
    T o = RawAccess<>::oop_load(p);
    if (! CompressedOops::is_null(o)) {
      oop obj1 = CompressedOops::decode_not_null(o);
      oop obj2 = ShenandoahBarrierSet::barrier_set()->write_barrier(obj1);
      if (! oopDesc::unsafe_equals(obj1, obj2)) {
        assert (!ShenandoahHeap::heap()->in_collection_set(obj2), "sanity");
        RawAccess<IS_NOT_NULL>::oop_store(p, obj2);
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

volatile int ShenandoahCodeRoots::_recorded_nms_lock;
GrowableArray<ShenandoahNMethod*>* ShenandoahCodeRoots::_recorded_nms;

void ShenandoahCodeRoots::initialize() {
  _recorded_nms_lock = 0;
  _recorded_nms = new (ResourceObj::C_HEAP, mtGC) GrowableArray<ShenandoahNMethod*>(100, true, mtGC);
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
      ShenandoahNMethodOopDetector detector;
      nm->oops_do(&detector);

      if (detector.has_oops()) {
        ShenandoahNMethodOopInitializer init;
        nm->oops_do(&init);
        nm->fix_oop_relocations();

        ShenandoahNMethod* nmr = new ShenandoahNMethod(nm, detector.oops());
        nmr->assert_alive_and_correct();

        ShenandoahCodeRootsLock lock(true);

        int idx = _recorded_nms->find(nm, ShenandoahNMethod::find_with_nmethod);
        if (idx != -1) {
          ShenandoahNMethod* old = _recorded_nms->at(idx);
          _recorded_nms->delete_at(idx);
          delete old;
        }
        _recorded_nms->append(nmr);
      }
      break;
    }
    default:
      ShouldNotReachHere();
  }
};

void ShenandoahCodeRoots::remove_nmethod(nmethod* nm) {
  switch (ShenandoahCodeRootsStyle) {
    case 0:
    case 1: {
      break;
    }
    case 2: {
      ShenandoahNMethodOopDetector detector;
      nm->oops_do(&detector, /* allow_zombie = */ true);

      if (detector.has_oops()) {
        ShenandoahCodeRootsLock lock(true);

        int idx = _recorded_nms->find(nm, ShenandoahNMethod::find_with_nmethod);
        assert(idx != -1, "nmethod " PTR_FORMAT " should be registered", p2i(nm));
        ShenandoahNMethod* old = _recorded_nms->at(idx);
        old->assert_same_oops(detector.oops());
        _recorded_nms->delete_at(idx);
        delete old;
      }
      break;
    }
    default:
      ShouldNotReachHere();
  }
}

ShenandoahCodeRootsIterator::ShenandoahCodeRootsIterator() :
        _claimed(0), _heap(ShenandoahHeap::heap()),
        _par_iterator(CodeCache::parallel_iterator()) {
  switch (ShenandoahCodeRootsStyle) {
    case 0:
    case 1: {
      // No need to do anything here
      break;
    }
    case 2: {
      ShenandoahCodeRoots::acquire_lock(false);
      break;
    }
    default:
      ShouldNotReachHere();
  }
}

ShenandoahCodeRootsIterator::~ShenandoahCodeRootsIterator() {
  switch (ShenandoahCodeRootsStyle) {
    case 0:
    case 1: {
      // No need to do anything here
      break;
    }
    case 2: {
      ShenandoahCodeRoots::release_lock(false);
      break;
    }
    default:
      ShouldNotReachHere();
  }
}

template<bool CSET_FILTER>
void ShenandoahCodeRootsIterator::dispatch_parallel_blobs_do(CodeBlobClosure *f) {
  switch (ShenandoahCodeRootsStyle) {
    case 0: {
      if (_seq_claimed.try_set()) {
        CodeCache::blobs_do(f);
      }
      break;
    }
    case 1: {
      _par_iterator.parallel_blobs_do(f);
      break;
    }
    case 2: {
      ShenandoahCodeRootsIterator::fast_parallel_blobs_do<CSET_FILTER>(f);
      break;
    }
    default:
      ShouldNotReachHere();
  }
}

ShenandoahAllCodeRootsIterator ShenandoahCodeRoots::iterator() {
  return ShenandoahAllCodeRootsIterator();
}

ShenandoahCsetCodeRootsIterator ShenandoahCodeRoots::cset_iterator() {
  return ShenandoahCsetCodeRootsIterator();
}

void ShenandoahAllCodeRootsIterator::possibly_parallel_blobs_do(CodeBlobClosure *f) {
  ShenandoahCodeRootsIterator::dispatch_parallel_blobs_do<false>(f);
}

void ShenandoahCsetCodeRootsIterator::possibly_parallel_blobs_do(CodeBlobClosure *f) {
  ShenandoahCodeRootsIterator::dispatch_parallel_blobs_do<true>(f);
}

template <bool CSET_FILTER>
void ShenandoahCodeRootsIterator::fast_parallel_blobs_do(CodeBlobClosure *f) {
  assert(SafepointSynchronize::is_at_safepoint(), "Must be at safepoint");

  size_t stride = 256; // educated guess

  GrowableArray<ShenandoahNMethod*>* list = ShenandoahCodeRoots::_recorded_nms;

  size_t max = (size_t)list->length();
  while (_claimed < max) {
    size_t cur = Atomic::add(stride, &_claimed) - stride;
    size_t start = cur;
    size_t end = MIN2(cur + stride, max);
    if (start >= max) break;

    for (size_t idx = start; idx < end; idx++) {
      ShenandoahNMethod* nmr = list->at((int) idx);
      nmr->assert_alive_and_correct();

      if (CSET_FILTER && !nmr->has_cset_oops(_heap)) {
        continue;
      }

      f->do_code_blob(nmr->nm());
    }
  }
}

ShenandoahNMethod::ShenandoahNMethod(nmethod* nm, GrowableArray<oop*>* oops) {
  _nm = nm;
  _oops = NEW_C_HEAP_ARRAY(oop*, oops->length(), mtGC);
  _oops_count = oops->length();
  for (int c = 0; c < _oops_count; c++) {
    _oops[c] = oops->at(c);
  }
}

ShenandoahNMethod::~ShenandoahNMethod() {
  if (_oops != NULL) {
    FREE_C_HEAP_ARRAY(oop*, _oops);
  }
}

bool ShenandoahNMethod::has_cset_oops(ShenandoahHeap *heap) {
  for (int c = 0; c < _oops_count; c++) {
    oop o = RawAccess<>::oop_load(_oops[c]);
    if (heap->in_collection_set(o)) {
      return true;
    }
  }
  return false;
}

#ifdef ASSERT
void ShenandoahNMethod::assert_alive_and_correct() {
  assert(_nm->is_alive(), "only alive nmethods here");
  assert(_oops_count > 0, "should have filtered nmethods without oops before");
  ShenandoahHeap* heap = ShenandoahHeap::heap();
  for (int c = 0; c < _oops_count; c++) {
    oop o = RawAccess<>::oop_load(_oops[c]);
    shenandoah_assert_correct_except(NULL, o, o == NULL || heap->is_full_gc_move_in_progress());
    assert(_nm->code_contains((address)_oops[c]) || _nm->oops_contains(_oops[c]), "nmethod should contain the oop*");
  }
}

void ShenandoahNMethod::assert_same_oops(GrowableArray<oop*>* oops) {
  assert(_oops_count == oops->length(), "should have the same number of oop*");
  for (int c = 0; c < _oops_count; c++) {
    assert(_oops[c] == oops->at(c), "should be the same oop*");
  }
}
#endif
