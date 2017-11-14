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
#include "gc/shenandoah/shenandoahConnectionMatrix.hpp"
#include "gc/shenandoah/shenandoahPhaseTimings.hpp"
#include "gc/shenandoah/shenandoahHeap.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahHeapRegion.hpp"
#include "gc/shenandoah/shenandoahHeapRegion.inline.hpp"
#include "gc/shenandoah/shenandoahRootProcessor.hpp"
#include "gc/shenandoah/shenandoahTaskqueue.hpp"
#include "gc/shenandoah/shenandoahVerifier.hpp"
#include "gc/shenandoah/shenandoahWorkGroup.hpp"
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
  void print_obj(ShenandoahMessageBuffer& msg, oop obj) {
    ShenandoahHeapRegion *r = _heap->heap_region_containing(obj);
    stringStream ss;
    r->print_on(&ss);

    msg.append("  " PTR_FORMAT " - klass " PTR_FORMAT " %s\n", p2i(obj), p2i(obj->klass()), obj->klass()->external_name());
    msg.append("    %3s allocated after complete mark start\n", _heap->allocated_after_complete_mark_start((HeapWord *) obj) ? "" : "not");
    msg.append("    %3s allocated after next mark start\n",     _heap->allocated_after_next_mark_start((HeapWord *) obj)     ? "" : "not");
    msg.append("    %3s marked complete\n",      _heap->is_marked_complete(obj) ? "" : "not");
    msg.append("    %3s marked next\n",          _heap->is_marked_next(obj) ? "" : "not");
    msg.append("    %3s in collection set\n",    _heap->in_collection_set(obj) ? "" : "not");
    msg.append("  region: %s", ss.as_string());
  }

  void print_non_obj(ShenandoahMessageBuffer& msg, void* loc) {
    msg.append("  outside of Java heap\n");
    stringStream ss;
    os::print_location(&ss, (intptr_t) loc, false);
    msg.append("  %s\n", ss.as_string());
  }

  void print_obj_safe(ShenandoahMessageBuffer& msg, void* loc) {
    msg.append("  " PTR_FORMAT " - safe print, no details\n", p2i(loc));
    if (_heap->is_in(loc)) {
      ShenandoahHeapRegion* r = _heap->heap_region_containing(loc);
      if (r != NULL) {
        stringStream ss;
        r->print_on(&ss);
        msg.append("  region: %s", ss.as_string());
      }
    }
  }

  enum SafeLevel {
    _safe_unknown,
    _safe_oop,
    _safe_oop_fwd,
    _safe_all,
  };

  void print_failure(SafeLevel level, oop obj, const char* label) {
    ResourceMark rm;

    bool loc_in_heap = (_loc != NULL && _heap->is_in(_loc));
    bool interior_loc_in_heap = (_interior_loc != NULL && _heap->is_in(_interior_loc));

    ShenandoahMessageBuffer msg("Shenandoah verification failed; %s: %s\n\n", _phase, label);

    msg.append("Referenced from:\n");
    if (_interior_loc != NULL) {
      msg.append("  interior location: " PTR_FORMAT "\n", p2i(_interior_loc));
      if (loc_in_heap) {
        print_obj(msg, _loc);
      } else {
        print_non_obj(msg, _interior_loc);
      }
    } else {
      msg.append("  no location recorded, probably a plain heap scan\n");
    }
    msg.append("\n");

    msg.append("Object:\n");
    if (level >= _safe_oop) {
      print_obj(msg, obj);
    } else {
      print_obj_safe(msg, obj);
    }
    msg.append("\n");

    if (level >= _safe_oop) {
      oop fwd = (oop) BrooksPointer::get_raw(obj);
      if (!oopDesc::unsafe_equals(obj, fwd)) {
        msg.append("Forwardee:\n");
        if (level >= _safe_oop_fwd) {
          print_obj(msg, fwd);
        } else {
          print_obj_safe(msg, fwd);
        }
        msg.append("\n");
      }
    }

    if (level >= _safe_oop_fwd) {
      oop fwd = (oop) BrooksPointer::get_raw(obj);
      oop fwd2 = (oop) BrooksPointer::get_raw(fwd);
      if (!oopDesc::unsafe_equals(fwd, fwd2)) {
        msg.append("Second forwardee:\n");
        print_obj_safe(msg, fwd2);
        msg.append("\n");
      }
    }

    if (loc_in_heap && UseShenandoahMatrix && (level == _safe_all)) {
      msg.append("Matrix connections:\n");

      oop fwd_to = (oop) BrooksPointer::get_raw(obj);
      oop fwd_from = (oop) BrooksPointer::get_raw(_loc);

      size_t from_idx = _heap->heap_region_index_containing(_loc);
      size_t to_idx = _heap->heap_region_index_containing(obj);
      size_t fwd_from_idx = _heap->heap_region_index_containing(fwd_from);
      size_t fwd_to_idx = _heap->heap_region_index_containing(fwd_to);

      ShenandoahConnectionMatrix* matrix = _heap->connection_matrix();
      msg.append("  %35s %3s connected\n",
                 "reference and object",
                 matrix->is_connected(from_idx, to_idx) ? "" : "not");
      msg.append("  %35s %3s connected\n",
                 "fwd(reference) and object",
                 matrix->is_connected(fwd_from_idx, to_idx) ? "" : "not");
      msg.append("  %35s %3s connected\n",
                 "reference and fwd(object)",
                 matrix->is_connected(from_idx, fwd_to_idx) ? "" : "not");
      msg.append("  %35s %3s connected\n",
                 "fwd(reference) and fwd(object)",
                 matrix->is_connected(fwd_from_idx, fwd_to_idx) ? "" : "not");

      if (interior_loc_in_heap) {
        size_t from_interior_idx = _heap->heap_region_index_containing(_interior_loc);
        msg.append("  %35s %3s connected\n",
                   "interior-reference and object",
                   matrix->is_connected(from_interior_idx, to_idx) ? "" : "not");
        msg.append("  %35s %3s connected\n",
                   "interior-reference and fwd(object)",
                   matrix->is_connected(from_interior_idx, fwd_to_idx) ? "" : "not");
      }
    }

    report_vm_error(__FILE__, __LINE__, msg.buffer());
  }

  void verify(SafeLevel level, oop obj, bool test, const char* label) {
    if (!test) {
      print_failure(level, obj, label);
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

    verify(_safe_unknown, obj, _heap->is_in(obj),
              "oop must be in heap");
    verify(_safe_unknown, obj, check_obj_alignment(obj),
              "oop must be aligned");

    // Verify that obj is not in dead space:
    {
      ShenandoahHeapRegion *obj_reg = _heap->heap_region_containing(obj);

      HeapWord *obj_addr = (HeapWord *) obj;
      verify(_safe_unknown, obj, obj_addr < obj_reg->top(),
             "Object start should be within the region");

      if (!obj_reg->is_humongous()) {
        verify(_safe_unknown, obj, (obj_addr + obj->size()) <= obj_reg->top(),
               "Object end should be within the region");
      } else {
        size_t humongous_start = obj_reg->region_number();
        size_t humongous_end = humongous_start + (obj->size() >> ShenandoahHeapRegion::region_size_words_shift());
        for (size_t idx = humongous_start + 1; idx < humongous_end; idx++) {
          verify(_safe_unknown, obj, _heap->regions()->get(idx)->is_humongous_continuation(),
                 "Humongous object is in continuation that fits it");
        }
      }

      verify(_safe_unknown, obj, Metaspace::contains(obj->klass()),
             "klass pointer must go to metaspace");

      // ------------ obj is safe at this point --------------

      verify(_safe_oop, obj, obj_reg->is_active(),
            "Object should be in active region");

      switch (_options._verify_liveness) {
        case ShenandoahVerifier::_verify_liveness_disable:
          // skip
          break;
        case ShenandoahVerifier::_verify_liveness_complete:
          Atomic::add(obj->size() + BrooksPointer::word_size(), &_ld[obj_reg->region_number()]);
          // fallthrough for fast failure for un-live regions:
        case ShenandoahVerifier::_verify_liveness_conservative:
          verify(_safe_oop, obj, obj_reg->has_live(),
                   "Object must belong to region with live data");
          break;
        default:
          assert(false, "Unhandled liveness verification");
      }
    }

    oop fwd = (oop) BrooksPointer::get_raw(obj);
    if (!oopDesc::unsafe_equals(obj, fwd)) {
      verify(_safe_oop, obj, _heap->is_in(fwd),
             "Forwardee must be in heap");
      verify(_safe_oop, obj, !oopDesc::is_null(fwd),
             "Forwardee is set");
      verify(_safe_oop, obj, check_obj_alignment(fwd),
             "Forwardee must be aligned");

      // Verify that forwardee is not in the dead space:
      if (!oopDesc::unsafe_equals(obj, fwd)) {
        ShenandoahHeapRegion *fwd_reg = _heap->heap_region_containing(fwd);
        verify(_safe_oop, obj, !fwd_reg->is_humongous(),
               "Should have no humongous forwardees");

        HeapWord *fwd_addr = (HeapWord *) fwd;
        verify(_safe_oop, obj, fwd_addr < fwd_reg->top(),
               "Forwardee start should be within the region");
        verify(_safe_oop, obj, (fwd_addr + fwd->size()) <= fwd_reg->top(),
               "Forwardee end should be within the region");
      }

      verify(_safe_oop, obj, Metaspace::contains(fwd->klass()),
             "Forwardee klass pointer must go to metaspace");
      verify(_safe_oop, obj, obj->klass() == fwd->klass(),
             "Forwardee klass pointer must go to metaspace");

      oop fwd2 = (oop) BrooksPointer::get_raw(fwd);
      verify(_safe_oop, obj, oopDesc::unsafe_equals(fwd, fwd2),
             "Double forwarding");
    }

    // ------------ obj and fwd are safe at this point --------------

    switch (_options._verify_marked) {
      case ShenandoahVerifier::_verify_marked_disable:
        // skip
        break;
      case ShenandoahVerifier::_verify_marked_next:
        verify(_safe_all, obj, _heap->is_marked_next(obj),
               "Must be marked in next bitmap");
        break;
      case ShenandoahVerifier::_verify_marked_complete:
        verify(_safe_all, obj, _heap->is_marked_complete(obj),
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
        verify(_safe_all, obj, oopDesc::unsafe_equals(obj, fwd),
               "Should not be forwarded");
        break;
      }
      case ShenandoahVerifier::_verify_forwarded_allow: {
        if (!oopDesc::unsafe_equals(obj, fwd)) {
          verify(_safe_all, obj, _heap->heap_region_containing(obj) != _heap->heap_region_containing(fwd),
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
        verify(_safe_all, obj, !_heap->in_collection_set(obj),
               "Should not have references to collection set");
        break;
      case ShenandoahVerifier::_verify_cset_forwarded:
        if (_heap->in_collection_set(obj)) {
          verify(_safe_all, obj, !oopDesc::unsafe_equals(obj, fwd),
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
        verify(_safe_all, obj, _heap->connection_matrix()->is_connected(from_idx, to_idx),
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
                                      ShenandoahMessageBuffer("%s, Roots", _label),
                                      _options);
        _rp->process_all_roots_slow(&cl);
    }

    size_t processed = 0;

    if (ShenandoahVerifyLevel >= 3) {
      ShenandoahVerifyOopClosure cl(&stack, _bitmap, _ld,
                                    ShenandoahMessageBuffer("%s, Reachable", _label),
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
                                  ShenandoahMessageBuffer("%s, Marked", _label),
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
  guarantee(SafepointSynchronize::is_at_safepoint(), "only when nothing else happens");
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
  if (_heap->need_update_refs()) {
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

void ShenandoahVerifier::verify_oop_fwdptr(oop obj, oop fwd) {
  guarantee(UseShenandoahGC, "must only be called when Shenandoah is used");

  ShenandoahHeap* heap = ShenandoahHeap::heap();

  guarantee(obj != NULL, "oop is not NULL");
  guarantee(fwd != NULL, "forwardee is not NULL");

  // Step 1. Check that both obj and its fwdptr are in heap.
  // After this step, it is safe to call heap_region_containing().
  guarantee(heap->is_in(obj), "oop must point to a heap address: " PTR_FORMAT, p2i(obj));

  if (!heap->is_in(fwd)) {
    ResourceMark rm;
    ShenandoahHeapRegion* r = heap->heap_region_containing(obj);
    stringStream obj_region;
    r->print_on(&obj_region);

    fatal("forwardee must point to a heap address: " PTR_FORMAT " -> " PTR_FORMAT "\n"
          "region(obj): %s",
          p2i(obj), p2i(fwd), obj_region.as_string());
  }

  // Step 2. Check that forwardee points to correct region.
  if (!oopDesc::unsafe_equals(fwd, obj) &&
      (heap->heap_region_containing(fwd) ==
       heap->heap_region_containing(obj))) {
    ResourceMark rm;

    ShenandoahHeapRegion* ro = heap->heap_region_containing(obj);
    stringStream obj_region;
    ro->print_on(&obj_region);

    ShenandoahHeapRegion* rf = heap->heap_region_containing(fwd);
    stringStream fwd_region;
    rf->print_on(&fwd_region);

    fatal("forwardee should be self, or another region: " PTR_FORMAT " -> " PTR_FORMAT "\n"
          "region(obj):    %s"
          "region(fwdptr): %s",
          p2i(obj), p2i(fwd),
          obj_region.as_string(), fwd_region.as_string());
  }

  // Step 3. Check for multiple forwardings
  if (!oopDesc::unsafe_equals(obj, fwd)) {
    oop fwd2 = oop(BrooksPointer::get_raw(fwd));
    if (!oopDesc::unsafe_equals(fwd, fwd2)) {
      ResourceMark rm;

      ShenandoahHeapRegion* ro = heap->heap_region_containing(obj);
      stringStream obj_region;
      ro->print_on(&obj_region);

      ShenandoahHeapRegion* rf = heap->heap_region_containing(fwd);
      stringStream fwd_region;
      rf->print_on(&fwd_region);

      // Second fwdptr had not been checked yet, cannot ask for its heap region
      // without a check. Do it now.
      stringStream fwd2_region;
      if (heap->is_in(fwd2)) {
        ShenandoahHeapRegion* rf2 = heap->heap_region_containing(fwd2);
        rf2->print_on(&fwd2_region);
      } else {
        fwd2_region.print_cr("Ptr is out of heap");
      }

      fatal("Multiple forwardings: " PTR_FORMAT " -> " PTR_FORMAT " -> " PTR_FORMAT "\n"
            "region(obj):     %s"
            "region(fwdptr):  %s"
            "region(fwdptr2): %s",
            p2i(obj), p2i(fwd), p2i(fwd2),
            obj_region.as_string(), fwd_region.as_string(), fwd2_region.as_string());
    }
  }
}

void ShenandoahVerifier::verify_oop(oop obj) {
  oop fwd = oop(BrooksPointer::get_raw(obj));
  verify_oop_fwdptr(obj, fwd);
}
