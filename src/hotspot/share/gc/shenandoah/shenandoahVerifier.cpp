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

#include "gc/shenandoah/brooksPointer.hpp"
#include "gc/shenandoah/shenandoahAsserts.hpp"
#include "gc/shenandoah/shenandoahConnectionMatrix.hpp"
#include "gc/shenandoah/shenandoahPhaseTimings.hpp"
#include "gc/shenandoah/shenandoahHeap.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahRootProcessor.hpp"
#include "gc/shenandoah/shenandoahTaskqueue.hpp"
#include "gc/shenandoah/shenandoahVerifier.hpp"
#include "memory/allocation.hpp"

class ShenandoahVerifyOopClosure : public ExtendedOopClosure {
private:
  const char* _phase;
  ShenandoahVerifier::VerifyOptions _options;
  ShenandoahVerifierStack* _stack;
  ShenandoahHeap* _heap;
  MarkBitMap* _map;
  ShenandoahLivenessData* _ld;
  void* _interior_loc;
  oop _loc;

public:
  ShenandoahVerifyOopClosure(ShenandoahVerifierStack* stack, MarkBitMap* map, ShenandoahLivenessData* ld,
                             const char* phase, ShenandoahVerifier::VerifyOptions options) :
          _stack(stack), _heap(ShenandoahHeap::heap()), _map(map), _ld(ld), _loc(NULL), _interior_loc(NULL),
          _phase(phase), _options(options) {};

private:
  void verify(ShenandoahAsserts::SafeLevel level, oop obj, bool test, const char* label) {
    if (!test) {
      ShenandoahAsserts::print_failure(level, obj, _interior_loc, _loc, _phase, label, __FILE__, __LINE__);
    }
  }

  template <class T>
  void do_oop_work(T* p) {
    T o = oopDesc::load_heap_oop(p);
    if (!oopDesc::is_null(o)) {
      oop obj = oopDesc::decode_heap_oop_not_null(o);

      // Single threaded verification can use faster non-atomic stack and bitmap
      // methods.
      //
      // For performance reasons, only fully verify non-marked field values.
      // We are here when the host object for *p is already marked. If field value
      // is marked already, then we have to verify the matrix connection between
      // host object and field value, regardless.

      HeapWord* addr = (HeapWord*) obj;
      if (_map->parMark(addr)) {
        verify_oop_at(p, obj);
        _stack->push(ShenandoahVerifierTask(obj));
      } else {
        verify_matrix(p, obj);
      }
    }
  }

  void verify_oop(oop obj) {
    // Perform consistency checks with gradually decreasing safety level. This guarantees
    // that failure report would not try to touch something that was not yet verified to be
    // safe to process.

    verify(ShenandoahAsserts::_safe_unknown, obj, _heap->is_in(obj),
              "oop must be in heap");
    verify(ShenandoahAsserts::_safe_unknown, obj, check_obj_alignment(obj),
              "oop must be aligned");

    ShenandoahHeapRegion *obj_reg = _heap->heap_region_containing(obj);
    Klass* obj_klass = obj->klass_or_null();

    // Verify that obj is not in dead space:
    {
      // Do this before touching obj->size()
      verify(ShenandoahAsserts::_safe_unknown, obj, obj_klass != NULL,
             "Object klass pointer should not be NULL");
      verify(ShenandoahAsserts::_safe_unknown, obj, Metaspace::contains(obj_klass),
             "Object klass pointer must go to metaspace");

      HeapWord *obj_addr = (HeapWord *) obj;
      verify(ShenandoahAsserts::_safe_unknown, obj, obj_addr < obj_reg->top(),
             "Object start should be within the region");

      if (!obj_reg->is_humongous()) {
        verify(ShenandoahAsserts::_safe_unknown, obj, (obj_addr + obj->size()) <= obj_reg->top(),
               "Object end should be within the region");
      } else {
        size_t humongous_start = obj_reg->region_number();
        size_t humongous_end = humongous_start + (obj->size() >> ShenandoahHeapRegion::region_size_words_shift());
        for (size_t idx = humongous_start + 1; idx < humongous_end; idx++) {
          verify(ShenandoahAsserts::_safe_unknown, obj, _heap->regions()->get(idx)->is_humongous_continuation(),
                 "Humongous object is in continuation that fits it");
        }
      }

      // ------------ obj is safe at this point --------------

      verify(ShenandoahAsserts::_safe_oop, obj, obj_reg->is_active(),
            "Object should be in active region");

      switch (_options._verify_liveness) {
        case ShenandoahVerifier::_verify_liveness_disable:
          // skip
          break;
        case ShenandoahVerifier::_verify_liveness_complete:
          Atomic::add(obj->size() + BrooksPointer::word_size(), &_ld[obj_reg->region_number()]);
          // fallthrough for fast failure for un-live regions:
        case ShenandoahVerifier::_verify_liveness_conservative:
          verify(ShenandoahAsserts::_safe_oop, obj, obj_reg->has_live(),
                   "Object must belong to region with live data");
          break;
        default:
          assert(false, "Unhandled liveness verification");
      }
    }

    oop fwd = (oop) BrooksPointer::get_raw(obj);

    ShenandoahHeapRegion* fwd_reg = NULL;

    if (!oopDesc::unsafe_equals(obj, fwd)) {
      verify(ShenandoahAsserts::_safe_oop, obj, _heap->is_in(fwd),
             "Forwardee must be in heap");
      verify(ShenandoahAsserts::_safe_oop, obj, !oopDesc::is_null(fwd),
             "Forwardee is set");
      verify(ShenandoahAsserts::_safe_oop, obj, check_obj_alignment(fwd),
             "Forwardee must be aligned");

      // Do this before touching fwd->size()
      Klass* fwd_klass = fwd->klass_or_null();
      verify(ShenandoahAsserts::_safe_oop, obj, fwd_klass != NULL,
             "Forwardee klass pointer should not be NULL");
      verify(ShenandoahAsserts::_safe_oop, obj, Metaspace::contains(fwd_klass),
             "Forwardee klass pointer must go to metaspace");
      verify(ShenandoahAsserts::_safe_oop, obj, obj_klass == fwd_klass,
             "Forwardee klass pointer must go to metaspace");

      fwd_reg = _heap->heap_region_containing(fwd);

      // Verify that forwardee is not in the dead space:
      verify(ShenandoahAsserts::_safe_oop, obj, !fwd_reg->is_humongous(),
             "Should have no humongous forwardees");

      HeapWord *fwd_addr = (HeapWord *) fwd;
      verify(ShenandoahAsserts::_safe_oop, obj, fwd_addr < fwd_reg->top(),
             "Forwardee start should be within the region");
      verify(ShenandoahAsserts::_safe_oop, obj, (fwd_addr + fwd->size()) <= fwd_reg->top(),
             "Forwardee end should be within the region");

      oop fwd2 = (oop) BrooksPointer::get_raw(fwd);
      verify(ShenandoahAsserts::_safe_oop, obj, oopDesc::unsafe_equals(fwd, fwd2),
             "Double forwarding");
    } else {
      fwd_reg = obj_reg;
    }

    // ------------ obj and fwd are safe at this point --------------

    switch (_options._verify_marked) {
      case ShenandoahVerifier::_verify_marked_disable:
        // skip
        break;
      case ShenandoahVerifier::_verify_marked_next:
        verify(ShenandoahAsserts::_safe_all, obj, _heap->is_marked_next(obj),
               "Must be marked in next bitmap");
        break;
      case ShenandoahVerifier::_verify_marked_complete:
        verify(ShenandoahAsserts::_safe_all, obj, _heap->is_marked_complete(obj),
               "Must be marked in complete bitmap");
        break;
      default:
        assert(false, "Unhandled mark verification");
    }

    switch (_options._verify_forwarded) {
      case ShenandoahVerifier::_verify_forwarded_disable:
        // skip
        break;
      case ShenandoahVerifier::_verify_forwarded_none: {
        verify(ShenandoahAsserts::_safe_all, obj, oopDesc::unsafe_equals(obj, fwd),
               "Should not be forwarded");
        break;
      }
      case ShenandoahVerifier::_verify_forwarded_allow: {
        if (!oopDesc::unsafe_equals(obj, fwd)) {
          verify(ShenandoahAsserts::_safe_all, obj, obj_reg != fwd_reg,
                 "Forwardee should be in another region");
        }
        break;
      }
      default:
        assert(false, "Unhandled forwarding verification");
    }

    switch (_options._verify_cset) {
      case ShenandoahVerifier::_verify_cset_disable:
        // skip
        break;
      case ShenandoahVerifier::_verify_cset_none:
        verify(ShenandoahAsserts::_safe_all, obj, !_heap->in_collection_set(obj),
               "Should not have references to collection set");
        break;
      case ShenandoahVerifier::_verify_cset_forwarded:
        if (_heap->in_collection_set(obj)) {
          verify(ShenandoahAsserts::_safe_all, obj, !oopDesc::unsafe_equals(obj, fwd),
                 "Object in collection set, should have forwardee");
        }
        break;
      default:
        assert(false, "Unhandled cset verification");
    }

    verify_matrix(_interior_loc, obj);
  }

  void verify_matrix(void* interior, oop obj) {
    if (!UseShenandoahMatrix || !_heap->is_in(interior)) return;
    switch (_options._verify_matrix) {
      case ShenandoahVerifier::_verify_matrix_conservative: {
        size_t from_idx = _heap->heap_region_index_containing(interior);
        size_t to_idx   = _heap->heap_region_index_containing(obj);
        _interior_loc = interior;
        verify(ShenandoahAsserts::_safe_all, obj, _heap->connection_matrix()->is_connected(from_idx, to_idx),
               "Must be connected");
        _interior_loc = NULL;
        break;
      }
      case ShenandoahVerifier::_verify_matrix_disable:
        break;
      default:
        assert(false, "Unhandled matrix verification");
    }
  }

public:

  /**
   * Verify object with known interior reference.
   * @param p interior reference where the object is referenced from; can be off-heap
   * @param obj verified object
   */
  template <class T>
  void verify_oop_at(T* p, oop obj) {
    _interior_loc = p;
    verify_oop(obj);
    _interior_loc = NULL;
  }

  /**
   * Verify object without known interior reference.
   * Useful when picking up the object at known offset in heap,
   * but without knowing what objects reference it.
   * @param obj verified object
   */
  void verify_oop_standalone(oop obj) {
    _interior_loc = NULL;
    verify_oop(obj);
    _interior_loc = NULL;
  }

  /**
   * Verify oop fields from this object.
   * @param obj host object for verified fields
   */
  void verify_oops_from(oop obj) {
    _loc = obj;
    obj->oop_iterate(this);
    _loc = NULL;
  }

  void do_oop(oop* p) { do_oop_work(p); }
  void do_oop(narrowOop* p) { do_oop_work(p); }
};

class ShenandoahCalculateRegionStatsClosure : public ShenandoahHeapRegionClosure {
private:
  size_t _used, _committed, _garbage;
public:
  ShenandoahCalculateRegionStatsClosure() : _used(0), _committed(0), _garbage(0) {};

  bool heap_region_do(ShenandoahHeapRegion* r) {
    _used += r->used();
    _garbage += r->garbage();
    _committed += r->is_committed() ? ShenandoahHeapRegion::region_size_bytes() : 0;
    return false;
  }

  size_t used() { return _used; }
  size_t committed() { return _committed; }
  size_t garbage() { return _garbage; }
};

class ShenandoahVerifyHeapRegionClosure : public ShenandoahHeapRegionClosure {
private:
  ShenandoahHeap* _heap;
  const char* _phase;
  ShenandoahVerifier::VerifyRegions _regions;
public:
  ShenandoahVerifyHeapRegionClosure(const char* phase, ShenandoahVerifier::VerifyRegions regions) :
          _heap(ShenandoahHeap::heap()), _regions(regions), _phase(phase) {};

  void print_failure(ShenandoahHeapRegion* r, const char* label) {
    ResourceMark rm;

    ShenandoahMessageBuffer msg("Shenandoah verification failed; %s: %s\n\n", _phase, label);

    stringStream ss;
    r->print_on(&ss);
    msg.append("%s", ss.as_string());

    report_vm_error(__FILE__, __LINE__, msg.buffer());
  }

  void verify(ShenandoahHeapRegion* r, bool test, const char* msg) {
    if (!test) {
      print_failure(r, msg);
    }
  }

  bool heap_region_do(ShenandoahHeapRegion* r) {
    switch (_regions) {
      case ShenandoahVerifier::_verify_regions_disable:
        break;
      case ShenandoahVerifier::_verify_regions_notrash:
        verify(r, !r->is_trash(),
               "Should not have trash regions");
        break;
      case ShenandoahVerifier::_verify_regions_nocset:
        verify(r, !r->is_cset(),
               "Should not have cset regions");
        break;
      case ShenandoahVerifier::_verify_regions_notrash_nocset:
        verify(r, !r->is_trash(),
               "Should not have trash regions");
        verify(r, !r->is_cset(),
               "Should not have cset regions");
        break;
      default:
        ShouldNotReachHere();
    }

    verify(r, r->capacity() == ShenandoahHeapRegion::region_size_bytes(),
           "Capacity should match region size");

    verify(r, r->bottom() <= _heap->complete_top_at_mark_start(r->bottom()),
           "Region top should not be less than bottom");

    verify(r, _heap->complete_top_at_mark_start(r->bottom()) <= r->top(),
           "Complete TAMS should not be larger than top");

    verify(r, r->get_live_data_bytes() <= r->capacity(),
           "Live data cannot be larger than capacity");

    verify(r, r->garbage() <= r->capacity(),
           "Garbage cannot be larger than capacity");

    verify(r, r->used() <= r->capacity(),
           "Used cannot be larger than capacity");

    verify(r, r->get_shared_allocs() <= r->capacity(),
           "Shared alloc count should not be larger than capacity");

    verify(r, r->get_tlab_allocs() <= r->capacity(),
           "TLAB alloc count should not be larger than capacity");

    verify(r, r->get_gclab_allocs() <= r->capacity(),
           "GCLAB alloc count should not be larger than capacity");

    verify(r, r->get_shared_allocs() + r->get_tlab_allocs() + r->get_gclab_allocs() == r->used(),
           "Accurate accounting: shared + TLAB + GCLAB = used");

    verify(r, !r->is_empty() || !r->has_live(),
           "Empty regions should not have live data");

    verify(r, r->is_cset() == r->in_collection_set(),
           "Transitional: region flags and collection set agree");

    verify(r, r->is_empty() || r->first_alloc_seq_num() != 0,
           "Non-empty regions should have first timestamp set");

    verify(r, r->is_empty() || r->last_alloc_seq_num() != 0,
           "Non-empty regions should have last timestamp set");

    verify(r, r->first_alloc_seq_num() <= r->last_alloc_seq_num(),
           "First timestamp should not be greater than last timestamp");

    return false;
  }
};

class ShenandoahVerifierReachableTask : public AbstractGangTask {
private:
  const char* _label;
  ShenandoahRootProcessor* _rp;
  ShenandoahVerifier::VerifyOptions _options;
  ShenandoahHeap* _heap;
  ShenandoahLivenessData* _ld;
  MarkBitMap* _bitmap;
  volatile size_t _processed;

public:
  ShenandoahVerifierReachableTask(MarkBitMap* bitmap,
                                  ShenandoahLivenessData* ld,
                                  ShenandoahRootProcessor* rp,
                                  const char* label,
                                  ShenandoahVerifier::VerifyOptions options) :
          AbstractGangTask("Shenandoah Parallel Verifier Reachable Task"),
          _heap(ShenandoahHeap::heap()), _rp(rp), _ld(ld), _bitmap(bitmap), _processed(0),
          _label(label), _options(options) {};

  size_t processed() {
    return _processed;
  }

  virtual void work(uint worker_id) {
    ResourceMark rm;
    ShenandoahVerifierStack stack;

    // On level 2, we need to only check the roots once.
    // On level 3, we want to check the roots, and seed the local stack.
    // It is a lesser evil to accept multiple root scans at level 3, because
    // extended parallelism would buy us out.
    if (((ShenandoahVerifyLevel == 2) && (worker_id == 0))
        || (ShenandoahVerifyLevel >= 3)) {
        ShenandoahVerifyOopClosure cl(&stack, _bitmap, _ld,
                                      ShenandoahMessageBuffer("Shenandoah verification failed; %s, Roots", _label),
                                      _options);
        _rp->process_all_roots_slow(&cl);
    }

    size_t processed = 0;

    if (ShenandoahVerifyLevel >= 3) {
      ShenandoahVerifyOopClosure cl(&stack, _bitmap, _ld,
                                    ShenandoahMessageBuffer("Shenandoah verification failed; %s, Reachable", _label),
                                    _options);
      while (!stack.is_empty()) {
        processed++;
        ShenandoahVerifierTask task = stack.pop();
        cl.verify_oops_from(task.obj());
      }
    }

    Atomic::add(processed, &_processed);
  }
};

class ShenandoahVerifierMarkedRegionTask : public AbstractGangTask {
private:
  const char* _label;
  ShenandoahVerifier::VerifyOptions _options;
  ShenandoahHeap *_heap;
  ShenandoahHeapRegionSet* _regions;
  MarkBitMap* _bitmap;
  ShenandoahLivenessData* _ld;
  volatile size_t _claimed;
  volatile size_t _processed;

public:
  ShenandoahVerifierMarkedRegionTask(ShenandoahHeapRegionSet* regions, MarkBitMap* bitmap,
                                     ShenandoahLivenessData* ld,
                                     const char* label,
                                     ShenandoahVerifier::VerifyOptions options) :
          AbstractGangTask("Shenandoah Parallel Verifier Marked Region"),
          _heap(ShenandoahHeap::heap()), _regions(regions), _ld(ld), _bitmap(bitmap), _claimed(0), _processed(0),
          _label(label), _options(options) {};

  size_t processed() {
    return _processed;
  }

  virtual void work(uint worker_id) {
    ShenandoahVerifierStack stack;
    ShenandoahVerifyOopClosure cl(&stack, _bitmap, _ld,
                                  ShenandoahMessageBuffer("Shenandoah verification failed; %s, Marked", _label),
                                  _options);

    while (true) {
      size_t v = Atomic::add(1u, &_claimed) - 1;
      if (v < _heap->num_regions()) {
        ShenandoahHeapRegion* r = _regions->get(v);
        if (!r->is_humongous() && !r->is_trash()) {
          work_regular(r, stack, cl);
        } else if (r->is_humongous_start()) {
          work_humongous(r, stack, cl);
        }
      } else {
        break;
      }
    }
  }

  virtual void work_humongous(ShenandoahHeapRegion *r, ShenandoahVerifierStack& stack, ShenandoahVerifyOopClosure& cl) {
    size_t processed = 0;
    HeapWord* obj = r->bottom() + BrooksPointer::word_size();
    if (_heap->is_marked_complete((oop)obj)) {
      verify_and_follow(obj, stack, cl, &processed);
    }
    Atomic::add(processed, &_processed);
  }

  virtual void work_regular(ShenandoahHeapRegion *r, ShenandoahVerifierStack &stack, ShenandoahVerifyOopClosure &cl) {
    size_t processed = 0;
    MarkBitMap* mark_bit_map = _heap->complete_mark_bit_map();
    HeapWord* tams = _heap->complete_top_at_mark_start(r->bottom());

    // Bitmaps, before TAMS
    if (tams > r->bottom()) {
      HeapWord* start = r->bottom() + BrooksPointer::word_size();
      HeapWord* addr = mark_bit_map->getNextMarkedWordAddress(start, tams);

      while (addr < tams) {
        verify_and_follow(addr, stack, cl, &processed);
        addr += BrooksPointer::word_size();
        if (addr < tams) {
          addr = mark_bit_map->getNextMarkedWordAddress(addr, tams);
        }
      }
    }

    // Size-based, after TAMS
    {
      HeapWord* limit = r->top();
      HeapWord* addr = tams + BrooksPointer::word_size();

      while (addr < limit) {
        verify_and_follow(addr, stack, cl, &processed);
        addr += oop(addr)->size() + BrooksPointer::word_size();
      }
    }

    Atomic::add(processed, &_processed);
  }

  void verify_and_follow(HeapWord *addr, ShenandoahVerifierStack &stack, ShenandoahVerifyOopClosure &cl, size_t *processed) {
    if (!_bitmap->parMark(addr)) return;

    // Verify the object itself:
    oop obj = oop(addr);
    cl.verify_oop_standalone(obj);

    // Verify everything reachable from that object too, hopefully realizing
    // everything was already marked, and never touching further:
    cl.verify_oops_from(obj);
    (*processed)++;

    while (!stack.is_empty()) {
      ShenandoahVerifierTask task = stack.pop();
      cl.verify_oops_from(task.obj());
      (*processed)++;
    }
  }
};

void ShenandoahVerifier::verify_at_safepoint(const char *label,
                                             VerifyForwarded forwarded, VerifyMarked marked,
                                             VerifyMatrix matrix, VerifyCollectionSet cset,
                                             VerifyLiveness liveness, VerifyRegions regions) {
  guarantee(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "only when nothing else happens");
  guarantee(ShenandoahVerify, "only when enabled, and bitmap is initialized in ShenandoahHeap::initialize");

  // Avoid side-effect of changing workers' active thread count, but bypass concurrent/parallel protocol check
  ShenandoahPushWorkerScope verify_worker_scope(_heap->workers(), _heap->max_workers(), false /*bypass check*/);

  log_info(gc,start)("Verify %s, Level " INTX_FORMAT, label, ShenandoahVerifyLevel);

  // Heap size checks
  {
    ShenandoahHeapLocker lock(_heap->lock());

    ShenandoahCalculateRegionStatsClosure cl;
    _heap->heap_region_iterate(&cl);
    size_t heap_used = _heap->used();
    guarantee(cl.used() == heap_used,
              "%s: heap used size must be consistent: heap-used = " SIZE_FORMAT "K, regions-used = " SIZE_FORMAT "K",
              label, heap_used/K, cl.used()/K);

    size_t heap_committed = _heap->committed();
    guarantee(cl.committed() == heap_committed,
              "%s: heap committed size must be consistent: heap-committed = " SIZE_FORMAT "K, regions-committed = " SIZE_FORMAT "K",
              label, heap_committed/K, cl.committed()/K);
  }

  // Internal heap region checks
  if (ShenandoahVerifyLevel >= 1) {
    ShenandoahVerifyHeapRegionClosure cl(label, regions);
    _heap->heap_region_iterate(&cl,
            /* skip_cset = */ false,
            /* skip_humongous_cont = */ false);
  }

  OrderAccess::fence();
  _heap->make_tlabs_parsable(false);

  // Allocate temporary bitmap for storing marking wavefront:
  MemRegion mr = MemRegion(_verification_bit_map->startWord(), _verification_bit_map->endWord());
  _verification_bit_map->clear_range_large(mr);

  // Allocate temporary array for storing liveness data
  ShenandoahLivenessData* ld = NEW_C_HEAP_ARRAY(ShenandoahLivenessData, _heap->num_regions(), mtGC);
  Copy::fill_to_bytes((void*)ld, _heap->num_regions()*sizeof(ShenandoahLivenessData), 0);

  const VerifyOptions& options = ShenandoahVerifier::VerifyOptions(forwarded, marked, matrix, cset, liveness, regions);

  // Steps 1-2. Scan root set to get initial reachable set. Finish walking the reachable heap.
  // This verifies what application can see, since it only cares about reachable objects.
  size_t count_reachable = 0;
  if (ShenandoahVerifyLevel >= 2) {
    ShenandoahRootProcessor rp(_heap, _heap->workers()->active_workers(),
                               ShenandoahPhaseTimings::_num_phases); // no need for stats

    ShenandoahVerifierReachableTask task(_verification_bit_map, ld, &rp, label, options);
    _heap->workers()->run_task(&task);
    count_reachable = task.processed();
  }

  // Step 3. Walk marked objects. Marked objects might be unreachable. This verifies what collector,
  // not the application, can see during the region scans. There is no reason to process the objects
  // that were already verified, e.g. those marked in verification bitmap. There is interaction with TAMS:
  // before TAMS, we verify the bitmaps, if available; after TAMS, we walk until the top(). It mimics
  // what marked_object_iterate is doing, without calling into that optimized (and possibly incorrect)
  // version

  size_t count_marked = 0;
  if (ShenandoahVerifyLevel >= 4 && marked == _verify_marked_complete) {
    ShenandoahVerifierMarkedRegionTask task(_heap->regions(), _verification_bit_map, ld, label, options);
    _heap->workers()->run_task(&task);
    count_marked = task.processed();
  } else {
    guarantee(ShenandoahVerifyLevel < 4 || marked == _verify_marked_next || marked == _verify_marked_disable, "Should be");
  }

  // Step 4. Verify accumulated liveness data, if needed. Only reliable if verification level includes
  // marked objects.

  if (ShenandoahVerifyLevel >= 4 && marked == _verify_marked_complete && liveness == _verify_liveness_complete) {
    ShenandoahHeapRegionSet* set = _heap->regions();
    for (size_t i = 0; i < _heap->num_regions(); i++) {
      ShenandoahHeapRegion* r = set->get(i);

      juint verf_live = 0;
      if (r->is_humongous()) {
        // For humongous objects, test if start region is marked live, and if so,
        // all humongous regions in that chain have live data equal to their "used".
        juint start_live = OrderAccess::load_acquire(&ld[r->humongous_start_region()->region_number()]);
        if (start_live > 0) {
          verf_live = (juint)(r->used() / HeapWordSize);
        }
      } else {
        verf_live = OrderAccess::load_acquire(&ld[r->region_number()]);
      }

      size_t reg_live = r->get_live_data_words();
      if (reg_live != verf_live) {
        ResourceMark rm;
        stringStream ss;
        r->print_on(&ss);
        fatal("%s: Live data should match: region-live = " SIZE_FORMAT ", verifier-live = " UINT32_FORMAT "\n%s",
              label, reg_live, verf_live, ss.as_string());
      }
    }
  }

  log_info(gc)("Verify %s, Level " INTX_FORMAT " (" SIZE_FORMAT " reachable, " SIZE_FORMAT " marked)",
               label, ShenandoahVerifyLevel, count_reachable, count_marked);

  FREE_C_HEAP_ARRAY(ShenandoahLivenessData, ld);
}

void ShenandoahVerifier::verify_generic(VerifyOption vo) {
  verify_at_safepoint(
          "Generic Verification",
          _verify_forwarded_allow,     // conservatively allow forwarded
          _verify_marked_disable,      // do not verify marked: lots ot time wasted checking dead allocations
          _verify_matrix_disable,      // matrix can be inconsistent here
          _verify_cset_disable,        // cset may be inconsistent
          _verify_liveness_disable,    // no reliable liveness data
          _verify_regions_disable      // no reliable region data
  );
}

void ShenandoahVerifier::verify_before_concmark() {
  if (_heap->has_forwarded_objects()) {
    verify_at_safepoint(
            "Before Mark",
            _verify_forwarded_allow,     // may have forwarded references
            _verify_marked_disable,      // do not verify marked: lots ot time wasted checking dead allocations
            _verify_matrix_disable,      // matrix is foobared
            _verify_cset_forwarded,      // allow forwarded references to cset
            _verify_liveness_disable,    // no reliable liveness data
            _verify_regions_notrash      // no trash regions
    );
  } else {
    verify_at_safepoint(
            "Before Mark",
            _verify_forwarded_none,      // UR should have fixed up
            _verify_marked_disable,      // do not verify marked: lots ot time wasted checking dead allocations
            _verify_matrix_conservative, // UR should have fixed matrix
            _verify_cset_none,           // UR should have fixed this
            _verify_liveness_disable,    // no reliable liveness data
            _verify_regions_notrash      // no trash regions
    );
  }
}

void ShenandoahVerifier::verify_after_concmark() {
  verify_at_safepoint(
          "After Mark",
          _verify_forwarded_none,      // no forwarded references
          _verify_marked_complete,     // bitmaps as precise as we can get
          _verify_matrix_disable,      // matrix might be foobared
          _verify_cset_none,           // no references to cset anymore
          _verify_liveness_complete,   // liveness data must be complete here
          _verify_regions_disable      // trash regions not yet recycled
  );
}

void ShenandoahVerifier::verify_before_evacuation() {
  // Evacuation is always preceded by mark, but we want to have a sanity check after
  // selecting the collection set, and (immediate) regions recycling
  verify_at_safepoint(
          "Before Evacuation",
          _verify_forwarded_none,    // no forwarded references
          _verify_marked_complete,   // walk over marked objects too
          _verify_matrix_disable,    // skip, verified after mark
          _verify_cset_disable,      // skip, verified after mark
          _verify_liveness_disable,  // skip, verified after mark
          _verify_regions_disable    // trash regions not yet recycled
  );
}

void ShenandoahVerifier::verify_after_evacuation() {
  verify_at_safepoint(
          "After Evacuation",
          _verify_forwarded_allow,     // objects are still forwarded
          _verify_marked_complete,     // bitmaps might be stale, but alloc-after-mark should be well
          _verify_matrix_disable,      // matrix is inconsistent here
          _verify_cset_forwarded,      // all cset refs are fully forwarded
          _verify_liveness_disable,    // no reliable liveness data anymore
          _verify_regions_notrash      // trash regions have been recycled already
  );
}

void ShenandoahVerifier::verify_before_updaterefs() {
  verify_at_safepoint(
          "Before Updating References",
          _verify_forwarded_allow,     // forwarded references allowed
          _verify_marked_complete,     // bitmaps might be stale, but alloc-after-mark should be well
          _verify_matrix_disable,      // matrix is inconsistent here
          _verify_cset_forwarded,      // all cset refs are fully forwarded
          _verify_liveness_disable,    // no reliable liveness data anymore
          _verify_regions_notrash      // trash regions have been recycled already
  );
}

void ShenandoahVerifier::verify_after_updaterefs() {
  verify_at_safepoint(
          "After Updating References",
          _verify_forwarded_none,      // no forwarded references
          _verify_marked_complete,     // bitmaps might be stale, but alloc-after-mark should be well
          _verify_matrix_conservative, // matrix is conservatively consistent
          _verify_cset_none,           // no cset references, all updated
          _verify_liveness_disable,    // no reliable liveness data anymore
          _verify_regions_nocset       // no cset regions, trash regions have appeared
  );
}

void ShenandoahVerifier::verify_after_degenerated() {
  verify_at_safepoint(
          "After Degenerated GC",
          _verify_forwarded_none,      // all objects are non-forwarded
          _verify_marked_complete,     // all objects are marked in complete bitmap
          _verify_matrix_conservative, // matrix is conservatively consistent
          _verify_cset_none,           // no cset references
          _verify_liveness_disable,    // no reliable liveness data anymore
          _verify_regions_notrash_nocset // no trash, no cset
  );
}

void ShenandoahVerifier::verify_before_partial() {
  verify_at_safepoint(
          "Before Partial",
          _verify_forwarded_none,      // cannot have forwarded objects
          _verify_marked_complete,     // bitmaps might be stale, but alloc-after-mark should be well
          _verify_matrix_conservative, // matrix is conservatively consistent
          _verify_cset_none,           // no cset references before partial
          _verify_liveness_disable,    // no reliable liveness data anymore
          _verify_regions_notrash_nocset // no trash and no cset regions
  );
}

void ShenandoahVerifier::verify_after_partial() {
  verify_at_safepoint(
          "After Partial",
          _verify_forwarded_none,      // cannot have forwarded objects
          _verify_marked_complete,     // bitmaps might be stale, but alloc-after-mark should be well
          _verify_matrix_conservative, // matrix is conservatively consistent
          _verify_cset_none,           // no cset references left after partial
          _verify_liveness_disable,    // no reliable liveness data anymore
          _verify_regions_nocset       // no cset regions, trash regions allowed
  );
}

void ShenandoahVerifier::verify_before_traversal() {
  verify_at_safepoint(
          "Before Traversal",
          _verify_forwarded_none,      // cannot have forwarded objects
          _verify_marked_disable,      // bitmaps are not relevant before traversal
          _verify_matrix_disable,      // matrix is not used in traversal
          _verify_cset_none,           // no cset references before traversal
          _verify_liveness_disable,    // no reliable liveness data anymore
          _verify_regions_notrash_nocset // no trash and no cset regions
  );
}

void ShenandoahVerifier::verify_after_traversal() {
  verify_at_safepoint(
          "After Traversal",
          _verify_forwarded_none,      // cannot have forwarded objects
          _verify_marked_next,         // marking should be complete in next bitmap
          _verify_matrix_disable,      // matrix is conservatively consistent
          _verify_cset_none,           // no cset references left after traversal
          _verify_liveness_complete,   // liveness data must be complete here
          _verify_regions_nocset       // no cset regions, trash regions allowed
  );
}

void ShenandoahVerifier::verify_before_fullgc() {
  verify_at_safepoint(
          "Before Full GC",
          _verify_forwarded_allow,     // can have forwarded objects
          _verify_marked_disable,      // do not verify marked: lots ot time wasted checking dead allocations
          _verify_matrix_disable,      // matrix might be foobared
          _verify_cset_disable,        // cset might be foobared
          _verify_liveness_disable,    // no reliable liveness data anymore
          _verify_regions_disable      // no reliable region data here
  );
}

void ShenandoahVerifier::verify_after_fullgc() {
  verify_at_safepoint(
          "After Full GC",
          _verify_forwarded_none,      // all objects are non-forwarded
          _verify_marked_complete,     // all objects are marked in complete bitmap
          _verify_matrix_conservative, // matrix is conservatively consistent
          _verify_cset_none,           // no cset references
          _verify_liveness_disable,    // no reliable liveness data anymore
          _verify_regions_notrash_nocset // no trash, no cset
  );
}

