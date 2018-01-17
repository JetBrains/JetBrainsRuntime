/*
 * Copyright (c) 2013, 2017, Red Hat, Inc. and/or its affiliates.
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
#include "memory/allocation.hpp"

#include "gc/shared/gcTimer.hpp"
#include "gc/shared/gcTraceTime.inline.hpp"
#include "gc/shared/parallelCleaning.hpp"

#include "gc/shenandoah/brooksPointer.hpp"
#include "gc/shenandoah/shenandoahAllocTracker.hpp"
#include "gc/shenandoah/shenandoahBarrierSet.hpp"
#include "gc/shenandoah/shenandoahCollectionSet.hpp"
#include "gc/shenandoah/shenandoahCollectorPolicy.hpp"
#include "gc/shenandoah/shenandoahConcurrentMark.hpp"
#include "gc/shenandoah/shenandoahConcurrentMark.inline.hpp"
#include "gc/shenandoah/shenandoahConcurrentThread.hpp"
#include "gc/shenandoah/shenandoahFreeSet.hpp"
#include "gc/shenandoah/shenandoahPhaseTimings.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahHeapRegion.hpp"
#include "gc/shenandoah/shenandoahHeapRegionSet.hpp"
#include "gc/shenandoah/shenandoahMarkCompact.hpp"
#include "gc/shenandoah/shenandoahMonitoringSupport.hpp"
#include "gc/shenandoah/shenandoahOopClosures.inline.hpp"
#include "gc/shenandoah/shenandoahPartialGC.hpp"
#include "gc/shenandoah/shenandoahRootProcessor.hpp"
#include "gc/shenandoah/shenandoahStringDedup.hpp"
#include "gc/shenandoah/shenandoahUtils.hpp"
#include "gc/shenandoah/shenandoahVerifier.hpp"
#include "gc/shenandoah/shenandoahCodeRoots.hpp"
#include "gc/shenandoah/shenandoahWorkerPolicy.hpp"
#include "gc/shenandoah/vm_operations_shenandoah.hpp"

#include "runtime/vmThread.hpp"
#include "services/mallocTracker.hpp"

ShenandoahUpdateRefsClosure::ShenandoahUpdateRefsClosure() : _heap(ShenandoahHeap::heap()) {}

#ifdef ASSERT
template <class T>
void ShenandoahAssertToSpaceClosure::do_oop_nv(T* p) {
  T o = oopDesc::load_heap_oop(p);
  if (! oopDesc::is_null(o)) {
    oop obj = oopDesc::decode_heap_oop_not_null(o);
    assert(oopDesc::unsafe_equals(obj, ShenandoahBarrierSet::resolve_oop_static_not_null(obj)),
           "need to-space object here obj: "PTR_FORMAT" , rb(obj): "PTR_FORMAT", p: "PTR_FORMAT,
           p2i(obj), p2i(ShenandoahBarrierSet::resolve_oop_static_not_null(obj)), p2i(p));
  }
}

void ShenandoahAssertToSpaceClosure::do_oop(narrowOop* p) { do_oop_nv(p); }
void ShenandoahAssertToSpaceClosure::do_oop(oop* p)       { do_oop_nv(p); }
#endif

const char* ShenandoahHeap::name() const {
  return "Shenandoah";
}

class ShenandoahPretouchTask : public AbstractGangTask {
private:
  ShenandoahHeapRegionSet* _regions;
  const size_t _bitmap_size;
  const size_t _page_size;
  char* _bitmap0_base;
  char* _bitmap1_base;
public:
  ShenandoahPretouchTask(ShenandoahHeapRegionSet* regions,
                         char* bitmap0_base, char* bitmap1_base, size_t bitmap_size,
                         size_t page_size) :
    AbstractGangTask("Shenandoah PreTouch",
                     Universe::is_fully_initialized() ? GCId::current_raw() :
                                                        // During VM initialization there is
                                                        // no GC cycle that this task can be
                                                        // associated with.
                                                        GCId::undefined()),
    _bitmap0_base(bitmap0_base),
    _bitmap1_base(bitmap1_base),
    _regions(regions),
    _bitmap_size(bitmap_size),
    _page_size(page_size) {
    _regions->clear_current_index();
  };

  virtual void work(uint worker_id) {
    ShenandoahHeapRegion* r = _regions->claim_next();
    while (r != NULL) {
      log_trace(gc, heap)("Pretouch region " SIZE_FORMAT ": " PTR_FORMAT " -> " PTR_FORMAT,
                          r->region_number(), p2i(r->bottom()), p2i(r->end()));
      os::pretouch_memory(r->bottom(), r->end(), _page_size);

      size_t start = r->region_number()       * ShenandoahHeapRegion::region_size_bytes() / MarkBitMap::heap_map_factor();
      size_t end   = (r->region_number() + 1) * ShenandoahHeapRegion::region_size_bytes() / MarkBitMap::heap_map_factor();
      assert (end <= _bitmap_size, "end is sane: " SIZE_FORMAT " < " SIZE_FORMAT, end, _bitmap_size);

      log_trace(gc, heap)("Pretouch bitmap under region " SIZE_FORMAT ": " PTR_FORMAT " -> " PTR_FORMAT,
                          r->region_number(), p2i(_bitmap0_base + start), p2i(_bitmap0_base + end));
      os::pretouch_memory(_bitmap0_base + start, _bitmap0_base + end, _page_size);

      log_trace(gc, heap)("Pretouch bitmap under region " SIZE_FORMAT ": " PTR_FORMAT " -> " PTR_FORMAT,
                          r->region_number(), p2i(_bitmap1_base + start), p2i(_bitmap1_base + end));
      os::pretouch_memory(_bitmap1_base + start, _bitmap1_base + end, _page_size);

      r = _regions->claim_next();
    }
  }
};

jint ShenandoahHeap::initialize() {
  CollectedHeap::pre_initialize();

  BrooksPointer::initial_checks();

  size_t init_byte_size = collector_policy()->initial_heap_byte_size();
  size_t max_byte_size = collector_policy()->max_heap_byte_size();
  size_t heap_alignment = collector_policy()->heap_alignment();

  if (ShenandoahAlwaysPreTouch) {
    // Enabled pre-touch means the entire heap is committed right away.
    init_byte_size = max_byte_size;
  }

  Universe::check_alignment(max_byte_size,
                            ShenandoahHeapRegion::region_size_bytes(),
                            "shenandoah heap");
  Universe::check_alignment(init_byte_size,
                            ShenandoahHeapRegion::region_size_bytes(),
                            "shenandoah heap");

  ReservedSpace heap_rs = Universe::reserve_heap(max_byte_size,
                                                 heap_alignment);
  initialize_reserved_region((HeapWord*)heap_rs.base(), (HeapWord*) (heap_rs.base() + heap_rs.size()));

  set_barrier_set(new ShenandoahBarrierSet(this));
  ReservedSpace pgc_rs = heap_rs.first_part(max_byte_size);

  _num_regions = max_byte_size / ShenandoahHeapRegion::region_size_bytes();
  size_t num_committed_regions = init_byte_size / ShenandoahHeapRegion::region_size_bytes();
  _initial_size = num_committed_regions * ShenandoahHeapRegion::region_size_bytes();
  _committed = _initial_size;

  log_info(gc, heap)("Initialize Shenandoah heap with initial size " SIZE_FORMAT " bytes", init_byte_size);
  if (!os::commit_memory(pgc_rs.base(), _initial_size, false)) {
    vm_exit_out_of_memory(_initial_size, OOM_MMAP_ERROR, "Shenandoah failed to initialize heap");
  }

  size_t reg_size_words = ShenandoahHeapRegion::region_size_words();
  size_t reg_size_bytes = ShenandoahHeapRegion::region_size_bytes();

  _ordered_regions = new ShenandoahHeapRegionSet(_num_regions);
  _free_regions = new ShenandoahFreeSet(_ordered_regions, _num_regions);

  _collection_set = new ShenandoahCollectionSet(this, (HeapWord*)pgc_rs.base());

  _next_top_at_mark_starts_base = NEW_C_HEAP_ARRAY(HeapWord*, _num_regions, mtGC);
  _next_top_at_mark_starts = _next_top_at_mark_starts_base -
               ((uintx) pgc_rs.base() >> ShenandoahHeapRegion::region_size_bytes_shift());

  _complete_top_at_mark_starts_base = NEW_C_HEAP_ARRAY(HeapWord*, _num_regions, mtGC);
  _complete_top_at_mark_starts = _complete_top_at_mark_starts_base -
               ((uintx) pgc_rs.base() >> ShenandoahHeapRegion::region_size_bytes_shift());

  {
    ShenandoahHeapLocker locker(lock());
    for (size_t i = 0; i < _num_regions; i++) {
      ShenandoahHeapRegion* r = new ShenandoahHeapRegion(this,
                                                         (HeapWord*) pgc_rs.base() + reg_size_words * i,
                                                         reg_size_words,
                                                         i,
                                                         i < num_committed_regions);

      _complete_top_at_mark_starts_base[i] = r->bottom();
      _next_top_at_mark_starts_base[i] = r->bottom();

      // Add to ordered regions first.
      // We use the active size of ordered regions as the number of active regions in heap,
      // free set and collection set use the number to assert the correctness of incoming regions.
      _ordered_regions->add_region(r);
      _free_regions->add_region(r);
      assert(!collection_set()->is_in(i), "New region should not be in collection set");
    }
  }

  assert(_ordered_regions->active_regions() == _num_regions, "Must match");
  assert((((size_t) base()) & ShenandoahHeapRegion::region_size_bytes_mask()) == 0,
         "misaligned heap: "PTR_FORMAT, p2i(base()));

  LogTarget(Trace, gc, region) lt;
  if (lt.is_enabled()) {
    ResourceMark rm;
    LogStream ls(lt);
    log_trace(gc, region)("All Regions");
    _ordered_regions->print_on(&ls);
    log_trace(gc, region)("Free Regions");
    _free_regions->print_on(&ls);
  }

  // The call below uses stuff (the SATB* things) that are in G1, but probably
  // belong into a shared location.
  JavaThread::satb_mark_queue_set().initialize(SATB_Q_CBL_mon,
                                               SATB_Q_FL_lock,
                                               20 /*G1SATBProcessCompletedThreshold */,
                                               Shared_SATB_Q_lock);

  // Reserve space for prev and next bitmap.
  _bitmap_size = MarkBitMap::compute_size(heap_rs.size());
  _heap_region = MemRegion((HeapWord*) heap_rs.base(), heap_rs.size() / HeapWordSize);

  size_t bitmap_bytes_per_region = reg_size_bytes / MarkBitMap::heap_map_factor();

  guarantee(bitmap_bytes_per_region != 0,
            "Bitmap bytes per region should not be zero");
  guarantee(is_power_of_2(bitmap_bytes_per_region),
            "Bitmap bytes per region should be power of two: " SIZE_FORMAT, bitmap_bytes_per_region);

  size_t bitmap_page_size = UseLargePages ? (size_t)os::large_page_size() : (size_t)os::vm_page_size();

  if (bitmap_page_size > bitmap_bytes_per_region) {
    _bitmap_regions_per_slice = bitmap_page_size / bitmap_bytes_per_region;
    _bitmap_bytes_per_slice = bitmap_page_size;
  } else {
    _bitmap_regions_per_slice = 1;
    _bitmap_bytes_per_slice = bitmap_bytes_per_region;
  }

  guarantee(_bitmap_regions_per_slice >= 1,
            "Should have at least one region per slice: " SIZE_FORMAT,
            _bitmap_regions_per_slice);

  guarantee(((_bitmap_bytes_per_slice) % bitmap_page_size) == 0,
            "Bitmap slices should be page-granular: bps = " SIZE_FORMAT ", page size = " SIZE_FORMAT,
            _bitmap_bytes_per_slice, bitmap_page_size);

  ReservedSpace bitmap0(_bitmap_size, bitmap_page_size);
  MemTracker::record_virtual_memory_type(bitmap0.base(), mtGC);
  _bitmap0_region = MemRegion((HeapWord*) bitmap0.base(), bitmap0.size() / HeapWordSize);

  ReservedSpace bitmap1(_bitmap_size, bitmap_page_size);
  MemTracker::record_virtual_memory_type(bitmap1.base(), mtGC);
  _bitmap1_region = MemRegion((HeapWord*) bitmap1.base(), bitmap1.size() / HeapWordSize);

  size_t bitmap_init_commit = _bitmap_bytes_per_slice *
                              align_up(num_committed_regions, _bitmap_regions_per_slice) / _bitmap_regions_per_slice;
  bitmap_init_commit = MIN2(_bitmap_size, bitmap_init_commit);
  os::commit_memory_or_exit((char *) (_bitmap0_region.start()), bitmap_init_commit, false,
                            "couldn't allocate initial bitmap");
  os::commit_memory_or_exit((char *) (_bitmap1_region.start()), bitmap_init_commit, false,
                            "couldn't allocate initial bitmap");

  size_t page_size = UseLargePages ? (size_t)os::large_page_size() : (size_t)os::vm_page_size();

  if (ShenandoahVerify) {
    ReservedSpace verify_bitmap(_bitmap_size, page_size);
    os::commit_memory_or_exit(verify_bitmap.base(), verify_bitmap.size(), false,
                              "couldn't allocate verification bitmap");
    MemTracker::record_virtual_memory_type(verify_bitmap.base(), mtGC);
    MemRegion verify_bitmap_region = MemRegion((HeapWord *) verify_bitmap.base(), verify_bitmap.size() / HeapWordSize);
    _verification_bit_map.initialize(_heap_region, verify_bitmap_region);
    _verifier = new ShenandoahVerifier(this, &_verification_bit_map);
  }

  if (ShenandoahAlwaysPreTouch) {
    assert (!AlwaysPreTouch, "Should have been overridden");

    // For NUMA, it is important to pre-touch the storage under bitmaps with worker threads,
    // before initialize() below zeroes it with initializing thread. For any given region,
    // we touch the region and the corresponding bitmaps from the same thread.

    log_info(gc, heap)("Parallel pretouch " SIZE_FORMAT " regions with " SIZE_FORMAT " byte pages",
                       _ordered_regions->count(), page_size);
    ShenandoahPretouchTask cl(_ordered_regions, bitmap0.base(), bitmap1.base(), _bitmap_size, page_size);
    _workers->run_task(&cl);
  }

  _mark_bit_map0.initialize(_heap_region, _bitmap0_region);
  _complete_mark_bit_map = &_mark_bit_map0;

  _mark_bit_map1.initialize(_heap_region, _bitmap1_region);
  _next_mark_bit_map = &_mark_bit_map1;

  // Reserve aux bitmap for use in object_iterate(). We don't commit it here.
  ReservedSpace aux_bitmap(_bitmap_size, bitmap_page_size);
  MemTracker::record_virtual_memory_type(aux_bitmap.base(), mtGC);
  _aux_bitmap_region = MemRegion((HeapWord*) aux_bitmap.base(), aux_bitmap.size() / HeapWordSize);
  _aux_bit_map.initialize(_heap_region, _aux_bitmap_region);

  if (UseShenandoahMatrix) {
    _connection_matrix = new ShenandoahConnectionMatrix(_num_regions);
  } else {
    _connection_matrix = NULL;
  }

  _partial_gc = _shenandoah_policy->can_do_partial_gc() ?
                new ShenandoahPartialGC(this, _num_regions) :
                NULL;

  _monitoring_support = new ShenandoahMonitoringSupport(this);

  _phase_timings = new ShenandoahPhaseTimings();

  if (ShenandoahAllocationTrace) {
    _alloc_tracker = new ShenandoahAllocTracker();
  }

  ShenandoahStringDedup::initialize();

  _concurrent_gc_thread = new ShenandoahConcurrentThread();

  ShenandoahCodeRoots::initialize();

  return JNI_OK;
}

ShenandoahHeap::ShenandoahHeap(ShenandoahCollectorPolicy* policy) :
  CollectedHeap(),
  _shenandoah_policy(policy),
  _free_regions(NULL),
  _collection_set(NULL),
  _bytes_allocated_since_cm(0),
  _bytes_allocated_during_cm(0),
  _allocated_last_gc(0),
  _used_start_gc(0),
  _max_workers(MAX2(ConcGCThreads, ParallelGCThreads)),
  _ref_processor(NULL),
  _next_top_at_mark_starts(NULL),
  _next_top_at_mark_starts_base(NULL),
  _complete_top_at_mark_starts(NULL),
  _complete_top_at_mark_starts_base(NULL),
  _mark_bit_map0(),
  _mark_bit_map1(),
  _aux_bit_map(),
  _connection_matrix(NULL),
  _verifier(NULL),
  _used_at_last_gc(0),
  _alloc_seq_at_last_gc_start(0),
  _alloc_seq_at_last_gc_end(0),
  _safepoint_workers(NULL),
#ifdef ASSERT
  _heap_expansion_count(0),
#endif
  _gc_timer(new (ResourceObj::C_HEAP, mtGC) ConcurrentGCTimer()),
  _phase_timings(NULL),
  _alloc_tracker(NULL)
{
  log_info(gc, init)("Parallel GC threads: "UINT32_FORMAT, ParallelGCThreads);
  log_info(gc, init)("Concurrent GC threads: "UINT32_FORMAT, ConcGCThreads);
  log_info(gc, init)("Parallel reference processing enabled: %s", BOOL_TO_STR(ParallelRefProcEnabled));

  _scm = new ShenandoahConcurrentMark();
  _full_gc = new ShenandoahMarkCompact();
  _used = 0;

  _max_workers = MAX2(_max_workers, 1U);
  _workers = new ShenandoahWorkGang("Shenandoah GC Threads", _max_workers,
                            /* are_GC_task_threads */true,
                            /* are_ConcurrentGC_threads */false);
  if (_workers == NULL) {
    vm_exit_during_initialization("Failed necessary allocation.");
  } else {
    _workers->initialize_workers();
  }

  if (ParallelSafepointCleanupThreads > 1) {
    _safepoint_workers = new ShenandoahWorkGang("Safepoint Cleanup Thread",
                                                ParallelSafepointCleanupThreads,
                                                false, false);
    _safepoint_workers->initialize_workers();
  }
}

class ShenandoahResetNextBitmapTask : public AbstractGangTask {
private:
  ShenandoahHeapRegionSet* _regions;

public:
  ShenandoahResetNextBitmapTask(ShenandoahHeapRegionSet* regions) :
    AbstractGangTask("Parallel Reset Bitmap Task"),
    _regions(regions) {
    _regions->clear_current_index();
  }

  void work(uint worker_id) {
    ShenandoahHeapRegion* region = _regions->claim_next();
    ShenandoahHeap* heap = ShenandoahHeap::heap();
    while (region != NULL) {
      if (heap->is_bitmap_slice_committed(region)) {
        HeapWord* bottom = region->bottom();
        HeapWord* top = heap->next_top_at_mark_start(region->bottom());
        if (top > bottom) {
          heap->next_mark_bit_map()->clear_range_large(MemRegion(bottom, top));
        }
        assert(heap->is_next_bitmap_clear_range(bottom, region->end()), "must be clear");
      }
      region = _regions->claim_next();
    }
  }
};

void ShenandoahHeap::reset_next_mark_bitmap() {
  assert_gc_workers(_workers->active_workers());

  ShenandoahResetNextBitmapTask task(_ordered_regions);
  _workers->run_task(&task);
}

class ShenandoahResetCompleteBitmapTask : public AbstractGangTask {
private:
  ShenandoahHeapRegionSet* _regions;

public:
  ShenandoahResetCompleteBitmapTask(ShenandoahHeapRegionSet* regions) :
    AbstractGangTask("Parallel Reset Bitmap Task"),
    _regions(regions) {
    _regions->clear_current_index();
  }

  void work(uint worker_id) {
    ShenandoahHeapRegion* region = _regions->claim_next();
    ShenandoahHeap* heap = ShenandoahHeap::heap();
    while (region != NULL) {
      if (heap->is_bitmap_slice_committed(region)) {
        HeapWord* bottom = region->bottom();
        HeapWord* top = heap->complete_top_at_mark_start(region->bottom());
        if (top > bottom) {
          heap->complete_mark_bit_map()->clear_range_large(MemRegion(bottom, top));
        }
        assert(heap->is_complete_bitmap_clear_range(bottom, region->end()), "must be clear");
      }
      region = _regions->claim_next();
    }
  }
};

void ShenandoahHeap::reset_complete_mark_bitmap() {
  assert_gc_workers(_workers->active_workers());

  ShenandoahResetCompleteBitmapTask task(_ordered_regions);
  _workers->run_task(&task);
}

bool ShenandoahHeap::is_next_bitmap_clear() {
  for (size_t idx = 0; idx < _num_regions; idx++) {
    ShenandoahHeapRegion* r = _ordered_regions->get(idx);
    if (is_bitmap_slice_committed(r) && !is_next_bitmap_clear_range(r->bottom(), r->end())) {
      return false;
    }
  }
  return true;
}

bool ShenandoahHeap::is_next_bitmap_clear_range(HeapWord* start, HeapWord* end) {
  return _next_mark_bit_map->getNextMarkedWordAddress(start, end) == end;
}

bool ShenandoahHeap::is_complete_bitmap_clear_range(HeapWord* start, HeapWord* end) {
  return _complete_mark_bit_map->getNextMarkedWordAddress(start, end) == end;
}

void ShenandoahHeap::print_on(outputStream* st) const {
  st->print_cr("Shenandoah Heap");
  st->print_cr(" " SIZE_FORMAT "K total, " SIZE_FORMAT "K committed, " SIZE_FORMAT "K used",
               capacity() / K, committed() / K, used() / K);
  st->print_cr(" " SIZE_FORMAT " x " SIZE_FORMAT"K regions",
               num_regions(), ShenandoahHeapRegion::region_size_bytes() / K);

  st->print("Status: ");
  if (has_forwarded_objects())                st->print("has forwarded objects, ");
  if (is_concurrent_mark_in_progress())    st->print("marking, ");
  if (is_evacuation_in_progress())         st->print("evacuating, ");
  if (is_update_refs_in_progress())        st->print("updating refs, ");
  if (is_concurrent_partial_in_progress()) st->print("partial, ");
  if (is_full_gc_in_progress())            st->print("full gc, ");
  if (is_full_gc_move_in_progress())       st->print("full gc move, ");

  if (cancelled_concgc()) {
    st->print("conc gc cancelled");
  } else {
    st->print("not cancelled");
  }
  st->cr();

  st->print_cr("Reserved region:");
  st->print_cr(" - [" PTR_FORMAT ", " PTR_FORMAT ") ",
               p2i(reserved_region().start()),
               p2i(reserved_region().end()));

  if (UseShenandoahMatrix) {
    st->print_cr("Matrix:");

    ShenandoahConnectionMatrix* matrix = connection_matrix();
    if (matrix != NULL) {
      st->print_cr(" - base: " PTR_FORMAT, p2i(matrix->matrix_addr()));
      st->print_cr(" - stride: " SIZE_FORMAT, matrix->stride());
      st->print_cr(" - magic: " PTR_FORMAT, matrix->magic_offset());
    } else {
      st->print_cr(" No matrix.");
    }
  }

  if (Verbose) {
    print_heap_regions_on(st);
  }
}

class ShenandoahInitGCLABClosure : public ThreadClosure {
public:
  void do_thread(Thread* thread) {
    thread->gclab().initialize(true);
  }
};

void ShenandoahHeap::post_initialize() {
  if (UseTLAB) {
    MutexLocker ml(Threads_lock);

    ShenandoahInitGCLABClosure init_gclabs;
    Threads::java_threads_do(&init_gclabs);
    gc_threads_do(&init_gclabs);

    // gclab can not be initialized early during VM startup, as it can not determinate its max_size.
    // Now, we will let WorkGang to initialize gclab when new worker is created.
    _workers->set_initialize_gclab();
  }

  _scm->initialize(_max_workers);
  _full_gc->initialize();

  ref_processing_init();

  _shenandoah_policy->post_heap_initialize();
}

size_t ShenandoahHeap::used() const {
  OrderAccess::acquire();
  return _used;
}

size_t ShenandoahHeap::committed() const {
  OrderAccess::acquire();
  return _committed;
}

void ShenandoahHeap::increase_committed(size_t bytes) {
  assert_heaplock_or_safepoint();
  _committed += bytes;
}

void ShenandoahHeap::decrease_committed(size_t bytes) {
  assert_heaplock_or_safepoint();
  _committed -= bytes;
}

void ShenandoahHeap::increase_used(size_t bytes) {
  assert_heaplock_or_safepoint();
  _used += bytes;
}

void ShenandoahHeap::set_used(size_t bytes) {
  assert_heaplock_or_safepoint();
  _used = bytes;
}

void ShenandoahHeap::decrease_used(size_t bytes) {
  assert_heaplock_or_safepoint();
  assert(_used >= bytes, "never decrease heap size by more than we've left");
  _used -= bytes;
}

size_t ShenandoahHeap::capacity() const {
  return num_regions() * ShenandoahHeapRegion::region_size_bytes();
}

bool ShenandoahHeap::is_maximal_no_gc() const {
  Unimplemented();
  return true;
}

size_t ShenandoahHeap::max_capacity() const {
  return _num_regions * ShenandoahHeapRegion::region_size_bytes();
}

size_t ShenandoahHeap::initial_capacity() const {
  return _initial_size;
}

bool ShenandoahHeap::is_in(const void* p) const {
  HeapWord* heap_base = (HeapWord*) base();
  HeapWord* last_region_end = heap_base + ShenandoahHeapRegion::region_size_words() * num_regions();
  return p >= heap_base && p < last_region_end;
}

bool ShenandoahHeap::is_scavengable(oop p) {
  return true;
}

void ShenandoahHeap::handle_heap_shrinkage() {
  ShenandoahHeapLocker locker(lock());

  ShenandoahHeapRegionSet* set = regions();

  size_t count = 0;
  double current = os::elapsedTime();
  for (size_t i = 0; i < num_regions(); i++) {
    ShenandoahHeapRegion* r = set->get(i);
    if (r->is_empty_committed() &&
            (current - r->empty_time()) * 1000 > ShenandoahUncommitDelay) {
      r->make_uncommitted();
      count++;
    }
  }

  if (count > 0) {
    log_info(gc)("Uncommitted " SIZE_FORMAT "M. Heap: " SIZE_FORMAT "M reserved, " SIZE_FORMAT "M committed, " SIZE_FORMAT "M used",
                 count * ShenandoahHeapRegion::region_size_bytes() / M, capacity() / M, committed() / M, used() / M);
    _concurrent_gc_thread->trigger_counters_update();
  }
}

HeapWord* ShenandoahHeap::allocate_from_gclab_slow(Thread* thread, size_t size) {
  // Retain tlab and allocate object in shared space if
  // the amount free in the tlab is too large to discard.
  if (thread->gclab().free() > thread->gclab().refill_waste_limit()) {
    thread->gclab().record_slow_allocation(size);
    return NULL;
  }

  // Discard gclab and allocate a new one.
  // To minimize fragmentation, the last GCLAB may be smaller than the rest.
  size_t new_gclab_size = thread->gclab().compute_size(size);

  thread->gclab().clear_before_allocation();

  if (new_gclab_size == 0) {
    return NULL;
  }

  // Allocate a new GCLAB...
  HeapWord* obj = allocate_new_gclab(new_gclab_size);
  if (obj == NULL) {
    return NULL;
  }

  if (ZeroTLAB) {
    // ..and clear it.
    Copy::zero_to_words(obj, new_gclab_size);
  } else {
    // ...and zap just allocated object.
#ifdef ASSERT
    // Skip mangling the space corresponding to the object header to
    // ensure that the returned space is not considered parsable by
    // any concurrent GC thread.
    size_t hdr_size = oopDesc::header_size();
    Copy::fill_to_words(obj + hdr_size, new_gclab_size - hdr_size, badHeapWordVal);
#endif // ASSERT
  }
  thread->gclab().fill(obj, obj + size, new_gclab_size);
  return obj;
}

HeapWord* ShenandoahHeap::allocate_new_tlab(size_t word_size) {
#ifdef ASSERT
  log_debug(gc, alloc)("Allocate new tlab, requested size = " SIZE_FORMAT " bytes", word_size * HeapWordSize);
#endif
  return allocate_new_lab(word_size, _alloc_tlab);
}

HeapWord* ShenandoahHeap::allocate_new_gclab(size_t word_size) {
#ifdef ASSERT
  log_debug(gc, alloc)("Allocate new gclab, requested size = " SIZE_FORMAT " bytes", word_size * HeapWordSize);
#endif
  return allocate_new_lab(word_size, _alloc_gclab);
}

HeapWord* ShenandoahHeap::allocate_new_lab(size_t word_size, AllocType type) {
  HeapWord* result = allocate_memory(word_size, type);

  if (result != NULL) {
    assert(! in_collection_set(result), "Never allocate in collection set");
    _bytes_allocated_since_cm += word_size * HeapWordSize;

    log_develop_trace(gc, tlab)("allocating new tlab of size "SIZE_FORMAT" at addr "PTR_FORMAT, word_size, p2i(result));

  }
  return result;
}

ShenandoahHeap* ShenandoahHeap::heap() {
  CollectedHeap* heap = Universe::heap();
  assert(heap != NULL, "Unitialized access to ShenandoahHeap::heap()");
  assert(heap->kind() == CollectedHeap::ShenandoahHeap, "not a shenandoah heap");
  return (ShenandoahHeap*) heap;
}

ShenandoahHeap* ShenandoahHeap::heap_no_check() {
  CollectedHeap* heap = Universe::heap();
  return (ShenandoahHeap*) heap;
}

HeapWord* ShenandoahHeap::allocate_memory(size_t word_size, AllocType type) {
  ShenandoahAllocTrace trace_alloc(word_size, type);

  bool in_new_region = false;
  HeapWord* result = allocate_memory_under_lock(word_size, type, in_new_region);

  if (type == _alloc_tlab || type == _alloc_shared) {
    // Allocation failed, try full-GC, then retry allocation.
    //
    // It might happen that one of the threads requesting allocation would unblock
    // way later after full-GC happened, only to fail the second allocation, because
    // other threads have already depleted the free storage. In this case, a better
    // strategy would be to try full-GC again.
    //
    // Lacking the way to detect progress from "collect" call, we are left with blindly
    // retrying for some bounded number of times.
    // TODO: Poll if Full GC made enough progress to warrant retry.
    int tries = 0;
    while ((result == NULL) && (tries++ < ShenandoahAllocGCTries)) {
      log_debug(gc)("[" PTR_FORMAT " Failed to allocate " SIZE_FORMAT " bytes, doing GC, try %d",
                    p2i(Thread::current()), word_size * HeapWordSize, tries);
      concurrent_thread()->handle_alloc_failure();
      result = allocate_memory_under_lock(word_size, type, in_new_region);
    }
  } else {
    assert(type == _alloc_gclab || type == _alloc_shared_gc, "Can only accept these types here");
    // Do not call handle_alloc_failure() here, because we cannot block.
    // The allocation failure would be handled by the WB slowpath with handle_alloc_failure_evac().
  }

  if (in_new_region) {
    // Update monitoring counters when we took a new region. This amortizes the
    // update costs on slow path.
    concurrent_thread()->trigger_counters_update();
  }

  log_develop_trace(gc, alloc)("allocate memory chunk of size "SIZE_FORMAT" at addr "PTR_FORMAT " by thread %d ",
                               word_size, p2i(result), Thread::current()->osthread()->thread_id());

  return result;
}

HeapWord* ShenandoahHeap::allocate_memory_under_lock(size_t word_size, AllocType type, bool& in_new_region) {
  ShenandoahHeapLocker locker(lock());
  return _free_regions->allocate(word_size, type, in_new_region);
}

HeapWord*  ShenandoahHeap::mem_allocate(size_t size,
                                        bool*  gc_overhead_limit_was_exceeded) {
  HeapWord* filler = allocate_memory(size + BrooksPointer::word_size(), _alloc_shared);
  HeapWord* result = filler + BrooksPointer::word_size();
  if (filler != NULL) {
    BrooksPointer::initialize(oop(result));
    _bytes_allocated_since_cm += size * HeapWordSize;

    assert(! in_collection_set(result), "never allocate in targetted region");
    return result;
  } else {
    return NULL;
  }
}

class ShenandoahEvacuateUpdateRootsClosure: public ExtendedOopClosure {
private:
  ShenandoahHeap* _heap;
  Thread* _thread;
public:
  ShenandoahEvacuateUpdateRootsClosure() :
    _heap(ShenandoahHeap::heap()), _thread(Thread::current()) {
  }

private:
  template <class T>
  void do_oop_work(T* p) {
    assert(_heap->is_evacuation_in_progress(), "Only do this when evacuation is in progress");

    T o = oopDesc::load_heap_oop(p);
    if (! oopDesc::is_null(o)) {
      oop obj = oopDesc::decode_heap_oop_not_null(o);
      if (_heap->in_collection_set(obj)) {
        assert(_heap->is_marked_complete(obj), "only evacuate marked objects %d %d",
               _heap->is_marked_complete(obj), _heap->is_marked_complete(ShenandoahBarrierSet::resolve_oop_static_not_null(obj)));
        oop resolved = ShenandoahBarrierSet::resolve_oop_static_not_null(obj);
        if (oopDesc::unsafe_equals(resolved, obj)) {
          bool evac;
          resolved = _heap->evacuate_object(obj, _thread, evac);
        }
        oopDesc::encode_store_heap_oop(p, resolved);
      }
    }
  }

public:
  void do_oop(oop* p) {
    do_oop_work(p);
  }
  void do_oop(narrowOop* p) {
    do_oop_work(p);
  }
};

class ShenandoahEvacuateRootsClosure: public ExtendedOopClosure {
private:
  ShenandoahHeap* _heap;
  Thread* _thread;
public:
  ShenandoahEvacuateRootsClosure() :
          _heap(ShenandoahHeap::heap()), _thread(Thread::current()) {
  }

private:
  template <class T>
  void do_oop_work(T* p) {
    T o = oopDesc::load_heap_oop(p);
    if (! oopDesc::is_null(o)) {
      oop obj = oopDesc::decode_heap_oop_not_null(o);
      if (_heap->in_collection_set(obj)) {
        oop resolved = ShenandoahBarrierSet::resolve_oop_static_not_null(obj);
        if (oopDesc::unsafe_equals(resolved, obj)) {
          bool evac;
          _heap->evacuate_object(obj, _thread, evac);
        }
      }
    }
  }

public:
  void do_oop(oop* p) {
    do_oop_work(p);
  }
  void do_oop(narrowOop* p) {
    do_oop_work(p);
  }
};

class ShenandoahParallelEvacuateRegionObjectClosure : public ObjectClosure {
private:
  ShenandoahHeap* const _heap;
  Thread* const _thread;
public:
  ShenandoahParallelEvacuateRegionObjectClosure(ShenandoahHeap* heap) :
    _heap(heap), _thread(Thread::current()) {}

  void do_object(oop p) {
    assert(_heap->is_marked_complete(p), "expect only marked objects");
    if (oopDesc::unsafe_equals(p, ShenandoahBarrierSet::resolve_oop_static_not_null(p))) {
      bool evac;
      _heap->evacuate_object(p, _thread, evac);
    }
  }
};

class ShenandoahParallelEvacuationTask : public AbstractGangTask {
private:
  ShenandoahHeap* const _sh;
  ShenandoahCollectionSet* const _cs;
  ShenandoahSharedFlag _claimed_codecache;

public:
  ShenandoahParallelEvacuationTask(ShenandoahHeap* sh,
                         ShenandoahCollectionSet* cs) :
    AbstractGangTask("Parallel Evacuation Task"),
    _cs(cs),
    _sh(sh)
  {}

  void work(uint worker_id) {

    SuspendibleThreadSetJoiner stsj(ShenandoahSuspendibleWorkers);

    // If concurrent code cache evac is enabled, evacuate it here.
    // Note we cannot update the roots here, because we risk non-atomic stores to the alive
    // nmethods. The update would be handled elsewhere.
    if (ShenandoahConcurrentEvacCodeRoots && _claimed_codecache.try_set()) {
      ShenandoahEvacuateRootsClosure cl;
      MutexLockerEx mu(CodeCache_lock, Mutex::_no_safepoint_check_flag);
      CodeBlobToOopClosure blobs(&cl, !CodeBlobToOopClosure::FixRelocations);
      CodeCache::blobs_do(&blobs);
    }

    ShenandoahParallelEvacuateRegionObjectClosure cl(_sh);
    ShenandoahHeapRegion* r;
    while ((r =_cs->claim_next()) != NULL) {
      log_develop_trace(gc, region)("Thread "INT32_FORMAT" claimed Heap Region "SIZE_FORMAT,
                                    worker_id,
                                    r->region_number());

      assert(r->has_live(), "all-garbage regions are reclaimed early");
      _sh->marked_object_iterate(r, &cl);

      if (_sh->check_cancelled_concgc_and_yield()) {
        log_develop_trace(gc, region)("Cancelled concgc while evacuating region " SIZE_FORMAT, r->region_number());
        break;
      }
    }
  }
};

void ShenandoahHeap::trash_cset_regions() {
  ShenandoahHeapLocker locker(lock());

  ShenandoahCollectionSet* set = collection_set();
  ShenandoahHeapRegion* r;
  set->clear_current_index();
  while ((r = set->next()) != NULL) {
    r->make_trash();
  }
  collection_set()->clear();
}

void ShenandoahHeap::print_heap_regions_on(outputStream* st) const {
  st->print_cr("Heap Regions:");
  st->print_cr("EU=empty-uncommitted, EC=empty-committed, R=regular, H=humongous start, HC=humongous continuation, CS=collection set, T=trash, P=pinned");
  st->print_cr("BTE=bottom/top/end, U=used, T=TLAB allocs, G=GCLAB allocs, S=shared allocs, L=live data");
  st->print_cr("R=root, CP=critical pins, TAMS=top-at-mark-start (previous, next)");
  st->print_cr("FTS=first use timestamp, LTS=last use timestamp");

  _ordered_regions->print_on(st);
}

size_t ShenandoahHeap::trash_humongous_region_at(ShenandoahHeapRegion* start) {
  assert(start->is_humongous_start(), "reclaim regions starting with the first one");

  oop humongous_obj = oop(start->bottom() + BrooksPointer::word_size());
  size_t size = humongous_obj->size() + BrooksPointer::word_size();
  size_t required_regions = ShenandoahHeapRegion::required_regions(size * HeapWordSize);
  size_t index = start->region_number() + required_regions - 1;

  assert(!start->has_live(), "liveness must be zero");
  log_trace(gc, humongous)("Reclaiming "SIZE_FORMAT" humongous regions for object of size: "SIZE_FORMAT" words", required_regions, size);

  for(size_t i = 0; i < required_regions; i++) {
    // Reclaim from tail. Otherwise, assertion fails when printing region to trace log,
    // as it expects that every region belongs to a humongous region starting with a humongous start region.
    ShenandoahHeapRegion* region = _ordered_regions->get(index --);

    LogTarget(Trace, gc, humongous) lt;
    if (lt.is_enabled()) {
      ResourceMark rm;
      LogStream ls(lt);
      region->print_on(&ls);
    }

    assert(region->is_humongous(), "expect correct humongous start or continuation");
    assert(!in_collection_set(region), "Humongous region should not be in collection set");

    region->make_trash();
  }
  return required_regions;
}

#ifdef ASSERT
class ShenandoahCheckCollectionSetClosure: public ShenandoahHeapRegionClosure {
  bool heap_region_do(ShenandoahHeapRegion* r) {
    assert(! ShenandoahHeap::heap()->in_collection_set(r), "Should have been cleared by now");
    return false;
  }
};
#endif

void ShenandoahHeap::prepare_for_concurrent_evacuation() {
  assert(_ordered_regions->get(0)->region_number() == 0, "FIXME CHF. FIXME CHF!");

  log_develop_trace(gc)("Thread %d started prepare_for_concurrent_evacuation", Thread::current()->osthread()->thread_id());

  if (!cancelled_concgc()) {
    // Allocations might have happened before we STWed here, record peak:
    shenandoahPolicy()->record_peak_occupancy();

    make_tlabs_parsable(true);

    if (ShenandoahVerify) {
      verifier()->verify_after_concmark();
    }

    trash_cset_regions();

    // NOTE: This needs to be done during a stop the world pause, because
    // putting regions into the collection set concurrently with Java threads
    // will create a race. In particular, acmp could fail because when we
    // resolve the first operand, the containing region might not yet be in
    // the collection set, and thus return the original oop. When the 2nd
    // operand gets resolved, the region could be in the collection set
    // and the oop gets evacuated. If both operands have originally been
    // the same, we get false negatives.

    {
      ShenandoahHeapLocker locker(lock());
      _collection_set->clear();
      _free_regions->clear();

#ifdef ASSERT
      ShenandoahCheckCollectionSetClosure ccsc;
      _ordered_regions->heap_region_iterate(&ccsc);
#endif

      _shenandoah_policy->choose_collection_set(_collection_set);

      _shenandoah_policy->choose_free_set(_free_regions);
    }

    _bytes_allocated_since_cm = 0;

    Universe::update_heap_info_at_gc();

    if (ShenandoahVerify) {
      verifier()->verify_before_evacuation();
    }
  }
}


class ShenandoahRetireTLABClosure : public ThreadClosure {
private:
  bool _retire;

public:
  ShenandoahRetireTLABClosure(bool retire) : _retire(retire) {}

  void do_thread(Thread* thread) {
    assert(thread->gclab().is_initialized(), "GCLAB should be initialized for %s", thread->name());
    thread->gclab().make_parsable(_retire);
  }
};

void ShenandoahHeap::make_tlabs_parsable(bool retire_tlabs) {
  if (UseTLAB) {
    CollectedHeap::ensure_parsability(retire_tlabs);
    ShenandoahRetireTLABClosure cl(retire_tlabs);
    Threads::java_threads_do(&cl);
    gc_threads_do(&cl);
  }
}


class ShenandoahEvacuateUpdateRootsTask : public AbstractGangTask {
  ShenandoahRootEvacuator* _rp;
public:

  ShenandoahEvacuateUpdateRootsTask(ShenandoahRootEvacuator* rp) :
    AbstractGangTask("Shenandoah evacuate and update roots"),
    _rp(rp)
  {
    // Nothing else to do.
  }

  void work(uint worker_id) {
    ShenandoahEvacuateUpdateRootsClosure cl;

    if (ShenandoahConcurrentEvacCodeRoots) {
      _rp->process_evacuate_roots(&cl, NULL, worker_id);
    } else {
      MarkingCodeBlobClosure blobsCl(&cl, CodeBlobToOopClosure::FixRelocations);
      _rp->process_evacuate_roots(&cl, &blobsCl, worker_id);
    }
  }
};

class ShenandoahFixRootsTask : public AbstractGangTask {
  ShenandoahRootEvacuator* _rp;
public:

  ShenandoahFixRootsTask(ShenandoahRootEvacuator* rp) :
    AbstractGangTask("Shenandoah update roots"),
    _rp(rp)
  {
    // Nothing else to do.
  }

  void work(uint worker_id) {
    ShenandoahUpdateRefsClosure cl;
    MarkingCodeBlobClosure blobsCl(&cl, CodeBlobToOopClosure::FixRelocations);

    _rp->process_evacuate_roots(&cl, &blobsCl, worker_id);
  }
};

void ShenandoahHeap::evacuate_and_update_roots() {

#if defined(COMPILER2) || INCLUDE_JVMCI
  DerivedPointerTable::clear();
#endif
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Only iterate roots while world is stopped");

  {
    ShenandoahRootEvacuator rp(this, workers()->active_workers(), ShenandoahPhaseTimings::init_evac);
    ShenandoahEvacuateUpdateRootsTask roots_task(&rp);
    workers()->run_task(&roots_task);
  }

#if defined(COMPILER2) || INCLUDE_JVMCI
  DerivedPointerTable::update_pointers();
#endif
  if (cancelled_concgc()) {
    fixup_roots();
  }
}

void ShenandoahHeap::fixup_roots() {
    assert(cancelled_concgc(), "Only after concurrent cycle failed");

    // If initial evacuation has been cancelled, we need to update all references
    // after all workers have finished. Otherwise we might run into the following problem:
    // GC thread 1 cannot allocate anymore, thus evacuation fails, leaves from-space ptr of object X.
    // GC thread 2 evacuates the same object X to to-space
    // which leaves a truly dangling from-space reference in the first root oop*. This must not happen.
    // clear() and update_pointers() must always be called in pairs,
    // cannot nest with above clear()/update_pointers().
#if defined(COMPILER2) || INCLUDE_JVMCI
    DerivedPointerTable::clear();
#endif
    ShenandoahRootEvacuator rp(this, workers()->active_workers(), ShenandoahPhaseTimings::init_evac);
    ShenandoahFixRootsTask update_roots_task(&rp);
    workers()->run_task(&update_roots_task);
#if defined(COMPILER2) || INCLUDE_JVMCI
    DerivedPointerTable::update_pointers();
#endif
}

void ShenandoahHeap::roots_iterate(OopClosure* cl) {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Only iterate roots while world is stopped");

  CodeBlobToOopClosure blobsCl(cl, false);
  CLDToOopClosure cldCl(cl);

  ShenandoahRootProcessor rp(this, 1, ShenandoahPhaseTimings::_num_phases);
  rp.process_all_roots(cl, NULL, &cldCl, &blobsCl, 0);
}

bool ShenandoahHeap::supports_tlab_allocation() const {
  return true;
}

size_t  ShenandoahHeap::unsafe_max_tlab_alloc(Thread *thread) const {
  return MIN2(_free_regions->unsafe_peek_free(), max_tlab_size());
}

size_t ShenandoahHeap::max_tlab_size() const {
  return ShenandoahHeapRegion::max_tlab_size_bytes();
}

class ShenandoahResizeGCLABClosure : public ThreadClosure {
public:
  void do_thread(Thread* thread) {
    assert(thread->gclab().is_initialized(), "GCLAB should be initialized for %s", thread->name());
    thread->gclab().resize();
  }
};

void ShenandoahHeap::resize_all_tlabs() {
  CollectedHeap::resize_all_tlabs();

  ShenandoahResizeGCLABClosure cl;
  Threads::java_threads_do(&cl);
  gc_threads_do(&cl);
}

class ShenandoahAccumulateStatisticsGCLABClosure : public ThreadClosure {
public:
  void do_thread(Thread* thread) {
    assert(thread->gclab().is_initialized(), "GCLAB should be initialized for %s", thread->name());
    thread->gclab().accumulate_statistics();
    thread->gclab().initialize_statistics();
  }
};

void ShenandoahHeap::accumulate_statistics_all_gclabs() {
  ShenandoahAccumulateStatisticsGCLABClosure cl;
  Threads::java_threads_do(&cl);
  gc_threads_do(&cl);
}

bool  ShenandoahHeap::can_elide_tlab_store_barriers() const {
  return true;
}

oop ShenandoahHeap::new_store_pre_barrier(JavaThread* thread, oop new_obj) {
  // Overridden to do nothing.
  return new_obj;
}

bool  ShenandoahHeap::can_elide_initializing_store_barrier(oop new_obj) {
  return true;
}

bool ShenandoahHeap::card_mark_must_follow_store() const {
  return false;
}

void ShenandoahHeap::collect(GCCause::Cause cause) {
  _concurrent_gc_thread->handle_explicit_gc(cause);
}

void ShenandoahHeap::do_full_collection(bool clear_all_soft_refs) {
  //assert(false, "Shouldn't need to do full collections");
}

AdaptiveSizePolicy* ShenandoahHeap::size_policy() {
  Unimplemented();
  return NULL;

}

CollectorPolicy* ShenandoahHeap::collector_policy() const {
  return _shenandoah_policy;
}


HeapWord* ShenandoahHeap::block_start(const void* addr) const {
  Space* sp = heap_region_containing(addr);
  if (sp != NULL) {
    return sp->block_start(addr);
  }
  return NULL;
}

size_t ShenandoahHeap::block_size(const HeapWord* addr) const {
  Space* sp = heap_region_containing(addr);
  assert(sp != NULL, "block_size of address outside of heap");
  return sp->block_size(addr);
}

bool ShenandoahHeap::block_is_obj(const HeapWord* addr) const {
  Space* sp = heap_region_containing(addr);
  return sp->block_is_obj(addr);
}

jlong ShenandoahHeap::millis_since_last_gc() {
  return 0;
}

void ShenandoahHeap::prepare_for_verify() {
  if (SafepointSynchronize::is_at_safepoint() || ! UseTLAB) {
    make_tlabs_parsable(false);
  }
}

void ShenandoahHeap::print_gc_threads_on(outputStream* st) const {
  workers()->print_worker_threads_on(st);
  if (ShenandoahStringDedup::is_enabled()) {
    ShenandoahStringDedup::print_worker_threads_on(st);
  }
}

void ShenandoahHeap::gc_threads_do(ThreadClosure* tcl) const {
  workers()->threads_do(tcl);
  if (ShenandoahStringDedup::is_enabled()) {
    ShenandoahStringDedup::threads_do(tcl);
  }
}

void ShenandoahHeap::print_tracing_info() const {
  LogTarget(Info, gc, stats) lt;
  if (lt.is_enabled()) {
    ResourceMark rm;
    LogStream ls(lt);

    phase_timings()->print_on(&ls);

    ls.cr();
    ls.cr();

    shenandoahPolicy()->print_gc_stats(&ls);

    ls.cr();
    ls.cr();

    if (ShenandoahAllocationTrace) {
      assert(alloc_tracker() != NULL, "Must be");
      alloc_tracker()->print_on(&ls);
    } else {
      ls.print_cr("  Allocation tracing is disabled, use -XX:+ShenandoahAllocationTrace to enable.");
    }
  }
}

void ShenandoahHeap::verify(VerifyOption vo) {
  if (ShenandoahSafepoint::is_at_shenandoah_safepoint()) {
    if (ShenandoahVerify) {
      verifier()->verify_generic(vo);
    } else {
      // TODO: Consider allocating verification bitmaps on demand,
      // and turn this on unconditionally.
    }
  }
}
size_t ShenandoahHeap::tlab_capacity(Thread *thr) const {
  return _free_regions->capacity();
}

class ObjectIterateScanRootClosure : public ExtendedOopClosure {
private:
  MarkBitMap* _bitmap;
  Stack<oop,mtGC>* _oop_stack;

  template <class T>
  void do_oop_work(T* p) {
    T o = oopDesc::load_heap_oop(p);
    if (!oopDesc::is_null(o)) {
      oop obj = oopDesc::decode_heap_oop_not_null(o);
      obj = ShenandoahBarrierSet::resolve_oop_static_not_null(obj);
      assert(oopDesc::is_oop(obj), "must be a valid oop");
      if (!_bitmap->isMarked((HeapWord*) obj)) {
        _bitmap->mark((HeapWord*) obj);
        _oop_stack->push(obj);
      }
    }
  }
public:
  ObjectIterateScanRootClosure(MarkBitMap* bitmap, Stack<oop,mtGC>* oop_stack) :
    _bitmap(bitmap), _oop_stack(oop_stack) {}
  void do_oop(oop* p)       { do_oop_work(p); }
  void do_oop(narrowOop* p) { do_oop_work(p); }
};

/*
 * This is public API, used in preparation of object_iterate().
 * Since we don't do linear scan of heap in object_iterate() (see comment below), we don't
 * need to make the heap parsable. For Shenandoah-internal linear heap scans that we can
 * control, we call SH::make_tlabs_parsable().
 */
void ShenandoahHeap::ensure_parsability(bool retire_tlabs) {
  // No-op.
}

/*
 * Iterates objects in the heap. This is public API, used for, e.g., heap dumping.
 *
 * We cannot safely iterate objects by doing a linear scan at random points in time. Linear
 * scanning needs to deal with dead objects, which may have dead Klass* pointers (e.g.
 * calling oopDesc::size() would crash) or dangling reference fields (crashes) etc. Linear
 * scanning therefore depends on having a valid marking bitmap to support it. However, we only
 * have a valid marking bitmap after successful marking. In particular, we *don't* have a valid
 * marking bitmap during marking, after aborted marking or during/after cleanup (when we just
 * wiped the bitmap in preparation for next marking).
 *
 * For all those reasons, we implement object iteration as a single marking traversal, reporting
 * objects as we mark+traverse through the heap, starting from GC roots. JVMTI IterateThroughHeap
 * is allowed to report dead objects, but is not required to do so.
 */
void ShenandoahHeap::object_iterate(ObjectClosure* cl) {
  assert(SafepointSynchronize::is_at_safepoint(), "safe iteration is only available during safepoints");
  if (!os::commit_memory((char*)_aux_bitmap_region.start(), _aux_bitmap_region.byte_size(), false)) {
    log_warning(gc)("Could not commit native memory for auxiliary marking bitmap for heap iteration");
    return;
  }

  Stack<oop,mtGC> oop_stack;

  // First, we process all GC roots. This populates the work stack with initial objects.
  ShenandoahRootProcessor rp(this, 1, ShenandoahPhaseTimings::_num_phases);
  ObjectIterateScanRootClosure oops(&_aux_bit_map, &oop_stack);
  CLDToOopClosure clds(&oops, false);
  CodeBlobToOopClosure blobs(&oops, false);
  rp.process_all_roots(&oops, &oops, &clds, &blobs, 0);

  // Work through the oop stack to traverse heap.
  while (! oop_stack.is_empty()) {
    oop obj = oop_stack.pop();
    assert(oopDesc::is_oop(obj), "must be a valid oop");
    cl->do_object(obj);
    obj->oop_iterate(&oops);
  }

  assert(oop_stack.is_empty(), "should be empty");

  if (!os::uncommit_memory((char*)_aux_bitmap_region.start(), _aux_bitmap_region.byte_size())) {
    log_warning(gc)("Could not uncommit native memory for auxiliary marking bitmap for heap iteration");
  }
}

void ShenandoahHeap::safe_object_iterate(ObjectClosure* cl) {
  assert(SafepointSynchronize::is_at_safepoint(), "safe iteration is only available during safepoints");
  object_iterate(cl);
}

// Apply blk->heap_region_do() on all committed regions in address order,
// terminating the iteration early if heap_region_do() returns true.
void ShenandoahHeap::heap_region_iterate(ShenandoahHeapRegionClosure* blk, bool skip_cset_regions, bool skip_humongous_continuation) const {
  for (size_t i = 0; i < num_regions(); i++) {
    ShenandoahHeapRegion* current  = _ordered_regions->get(i);
    if (skip_humongous_continuation && current->is_humongous_continuation()) {
      continue;
    }
    if (skip_cset_regions && in_collection_set(current)) {
      continue;
    }
    if (blk->heap_region_do(current)) {
      return;
    }
  }
}

class ShenandoahClearLivenessClosure : public ShenandoahHeapRegionClosure {
private:
  ShenandoahHeap* sh;
public:
  ShenandoahClearLivenessClosure(ShenandoahHeap* heap) : sh(heap) {}

  bool heap_region_do(ShenandoahHeapRegion* r) {
    r->clear_live_data();
    sh->set_next_top_at_mark_start(r->bottom(), r->top());
    return false;
  }
};

void ShenandoahHeap::op_init_mark() {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Should be at safepoint");

  assert(is_next_bitmap_clear(), "need clear marking bitmap");

  if (ShenandoahVerify) {
    verifier()->verify_before_concmark();
  }

  {
    ShenandoahGCPhase phase(ShenandoahPhaseTimings::accumulate_stats);
    accumulate_statistics_all_tlabs();
  }

  set_concurrent_mark_in_progress(true);
  // We need to reset all TLABs because we'd lose marks on all objects allocated in them.
  if (UseTLAB) {
    ShenandoahGCPhase phase(ShenandoahPhaseTimings::make_parsable);
    make_tlabs_parsable(true);
  }

  _shenandoah_policy->record_bytes_allocated(_bytes_allocated_since_cm);
  _used_start_gc = used();

  {
    ShenandoahGCPhase phase(ShenandoahPhaseTimings::clear_liveness);
    ShenandoahClearLivenessClosure clc(this);
    heap_region_iterate(&clc);
  }

  // Make above changes visible to worker threads
  OrderAccess::fence();

  concurrentMark()->init_mark_roots();

  if (UseTLAB) {
    ShenandoahGCPhase phase(ShenandoahPhaseTimings::resize_tlabs);
    resize_all_tlabs();
  }
}

void ShenandoahHeap::op_mark() {
  concurrentMark()->mark_from_roots();

  // Allocations happen during concurrent mark, record peak after the phase:
  shenandoahPolicy()->record_peak_occupancy();
}

void ShenandoahHeap::op_final_mark() {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Should be at safepoint");

  ShenandoahHeap *sh = ShenandoahHeap::heap();

  // It is critical that we
  // evacuate roots right after finishing marking, so that we don't
  // get unmarked objects in the roots.

  if (! sh->cancelled_concgc()) {
    sh->concurrentMark()->finish_mark_from_roots();
    sh->stop_concurrent_marking();

    {
      ShenandoahGCPhase prepare_evac(ShenandoahPhaseTimings::prepare_evac);
      sh->prepare_for_concurrent_evacuation();
    }

    // If collection set has candidates, start evacuation.
    // Otherwise, bypass the rest of the cycle.
    if (!sh->collection_set()->is_empty()) {
      sh->set_evacuation_in_progress_at_safepoint(true);
      // From here on, we need to update references.
      sh->set_has_forwarded_objects(true);

      ShenandoahGCPhase init_evac(ShenandoahPhaseTimings::init_evac);
      sh->evacuate_and_update_roots();
    }
  } else {
    sh->concurrentMark()->cancel();
    sh->stop_concurrent_marking();
  }
}

void ShenandoahHeap::op_evac() {

  LogTarget(Trace, gc, region) lt_region;
  LogTarget(Trace, gc, cset) lt_cset;

  if (lt_region.is_enabled()) {
    ResourceMark rm;
    LogStream ls(lt_region);
    ls.print_cr("All available regions:");
    print_heap_regions_on(&ls);
  }

  if (lt_cset.is_enabled()) {
    ResourceMark rm;
    LogStream ls(lt_cset);
    ls.print_cr("Collection set ("SIZE_FORMAT" regions):", _collection_set->count());
    _collection_set->print_on(&ls);

    ls.print_cr("Free set:");
    _free_regions->print_on(&ls);
  }

  ShenandoahParallelEvacuationTask task(this, _collection_set);
  workers()->run_task(&task);

  if (lt_cset.is_enabled()) {
    ResourceMark rm;
    LogStream ls(lt_cset);
    ls.print_cr("After evacuation collection set ("SIZE_FORMAT" regions):",
                _collection_set->count());
    _collection_set->print_on(&ls);

    ls.print_cr("After evacuation free set:");
    _free_regions->print_on(&ls);
  }

  if (lt_region.is_enabled()) {
    ResourceMark rm;
    LogStream ls(lt_region);
    ls.print_cr("All regions after evacuation:");
    print_heap_regions_on(&ls);
  }

  // Allocations happen during evacuation, record peak after the phase:
  shenandoahPolicy()->record_peak_occupancy();
}

void ShenandoahHeap::op_verify_after_evac() {
  verifier()->verify_after_evacuation();
}

void ShenandoahHeap::op_updaterefs() {
  ShenandoahHeapRegionSet* update_regions = regions();
  update_regions->clear_current_index();
  update_heap_references(update_regions, true);

  // Allocations happen during update-refs, record peak after the phase:
  shenandoahPolicy()->record_peak_occupancy();
}

void ShenandoahHeap::op_cleanup() {
  ShenandoahGCPhase phase_recycle(ShenandoahPhaseTimings::conc_cleanup_recycle);
  recycle_trash();

  // Allocations happen during cleanup, record peak after the phase:
  shenandoahPolicy()->record_peak_occupancy();
}

void ShenandoahHeap::op_cleanup_bitmaps() {
  op_cleanup();

  ShenandoahGCPhase phase_reset(ShenandoahPhaseTimings::conc_cleanup_reset_bitmaps);
  reset_next_mark_bitmap();

  // Allocations happen during bitmap cleanup, record peak after the phase:
  shenandoahPolicy()->record_peak_occupancy();
}

void ShenandoahHeap::op_preclean() {
  if (ShenandoahPreclean && concurrentMark()->process_references()) {
    concurrentMark()->preclean_weak_refs();

    // Allocations happen during concurrent preclean, record peak after the phase:
    shenandoahPolicy()->record_peak_occupancy();
  }
}

void ShenandoahHeap::op_init_partial() {
  partial_gc()->init_partial_collection();
}

void ShenandoahHeap::op_partial() {
  partial_gc()->concurrent_partial_collection();
}

void ShenandoahHeap::op_final_partial() {
  partial_gc()->final_partial_collection();
}

void ShenandoahHeap::op_full(GCCause::Cause cause) {
  full_gc()->do_it(cause);
}

void ShenandoahHeap::swap_mark_bitmaps() {
  // Swap bitmaps.
  MarkBitMap* tmp1 = _complete_mark_bit_map;
  _complete_mark_bit_map = _next_mark_bit_map;
  _next_mark_bit_map = tmp1;

  // Swap top-at-mark-start pointers
  HeapWord** tmp2 = _complete_top_at_mark_starts;
  _complete_top_at_mark_starts = _next_top_at_mark_starts;
  _next_top_at_mark_starts = tmp2;

  HeapWord** tmp3 = _complete_top_at_mark_starts_base;
  _complete_top_at_mark_starts_base = _next_top_at_mark_starts_base;
  _next_top_at_mark_starts_base = tmp3;
}


void ShenandoahHeap::stop_concurrent_marking() {
  assert(is_concurrent_mark_in_progress(), "How else could we get here?");
  if (! cancelled_concgc()) {
    // If we needed to update refs, and concurrent marking has been cancelled,
    // we need to finish updating references.
    set_has_forwarded_objects(false);
    swap_mark_bitmaps();
  }
  set_concurrent_mark_in_progress(false);

  LogTarget(Trace, gc, region) lt;
  if (lt.is_enabled()) {
    ResourceMark rm;
    LogStream ls(lt);
    ls.print_cr("Regions at stopping the concurrent mark:");
    print_heap_regions_on(&ls);
  }
}

void ShenandoahHeap::set_gc_state_bit(uint bit, bool value) {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Should really be Shenandoah safepoint");
  _gc_state.set_cond(bit, value);
  JavaThread::set_gc_state_all_threads(_gc_state.raw_value());
}

void ShenandoahHeap::set_gc_state_bit_concurrently(uint bit, bool value) {
  _gc_state.set_cond(bit, value);
  MutexLocker mu(Threads_lock);
  JavaThread::set_gc_state_all_threads(_gc_state.raw_value());
}

void ShenandoahHeap::set_concurrent_mark_in_progress(bool in_progress) {
  set_gc_state_bit(MARKING_BITPOS, in_progress);
  JavaThread::satb_mark_queue_set().set_active_all_threads(in_progress, !in_progress);
}

void ShenandoahHeap::set_concurrent_partial_in_progress(bool in_progress) {
  set_gc_state_bit(PARTIAL_BITPOS, in_progress);
  JavaThread::satb_mark_queue_set().set_active_all_threads(in_progress, !in_progress);
  set_evacuation_in_progress_at_safepoint(in_progress);
}

void ShenandoahHeap::set_evacuation_in_progress_concurrently(bool in_progress) {
  // Note: it is important to first release the _evacuation_in_progress flag here,
  // so that Java threads can get out of oom_during_evacuation() and reach a safepoint,
  // in case a VM task is pending.
  set_gc_state_bit_concurrently(EVACUATION_BITPOS, in_progress);
}

void ShenandoahHeap::set_evacuation_in_progress_at_safepoint(bool in_progress) {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Only call this at safepoint");
  set_gc_state_bit(EVACUATION_BITPOS, in_progress);
}

HeapWord* ShenandoahHeap::tlab_post_allocation_setup(HeapWord* obj) {
  // Initialize Brooks pointer for the next object
  HeapWord* result = obj + BrooksPointer::word_size();
  BrooksPointer::initialize(oop(result));
  return result;
}

uint ShenandoahHeap::oop_extra_words() {
  return BrooksPointer::word_size();
}

ShenandoahForwardedIsAliveClosure::ShenandoahForwardedIsAliveClosure() :
  _heap(ShenandoahHeap::heap_no_check()) {
}

bool ShenandoahForwardedIsAliveClosure::do_object_b(oop obj) {
  assert(_heap != NULL, "sanity");
  obj = ShenandoahBarrierSet::resolve_oop_static_not_null(obj);
#ifdef ASSERT
  if (_heap->is_concurrent_mark_in_progress()) {
    assert(oopDesc::unsafe_equals(obj, ShenandoahBarrierSet::resolve_oop_static_not_null(obj)), "only query to-space");
  }
#endif
  assert(!oopDesc::is_null(obj), "null");
  return _heap->is_marked_next(obj);
}

ShenandoahIsAliveClosure::ShenandoahIsAliveClosure() :
  _heap(ShenandoahHeap::heap_no_check()) {
}

bool ShenandoahIsAliveClosure::do_object_b(oop obj) {
  assert(_heap != NULL, "sanity");
  assert(!oopDesc::is_null(obj), "null");
  assert(oopDesc::unsafe_equals(obj, ShenandoahBarrierSet::resolve_oop_static_not_null(obj)), "only query to-space");
  return _heap->is_marked_next(obj);
}

BoolObjectClosure* ShenandoahHeap::is_alive_closure() {
  return has_forwarded_objects() ?
         (BoolObjectClosure*) &_forwarded_is_alive :
         (BoolObjectClosure*) &_is_alive;
}

void ShenandoahHeap::ref_processing_init() {
  MemRegion mr = reserved_region();

  _forwarded_is_alive.init(ShenandoahHeap::heap());
  _is_alive.init(ShenandoahHeap::heap());
  assert(_max_workers > 0, "Sanity");

  _ref_processor =
    new ReferenceProcessor(mr,    // span
                           ParallelRefProcEnabled,  // MT processing
                           _max_workers,            // Degree of MT processing
                           true,                    // MT discovery
                           _max_workers,            // Degree of MT discovery
                           false,                   // Reference discovery is not atomic
                           &_forwarded_is_alive);   // Pessimistically assume "forwarded"
}


GCTracer* ShenandoahHeap::tracer() {
  return shenandoahPolicy()->tracer();
}

size_t ShenandoahHeap::tlab_used(Thread* thread) const {
  return _free_regions->used();
}

void ShenandoahHeap::cancel_concgc(GCCause::Cause cause) {
  if (try_cancel_concgc()) {
    log_info(gc)("Cancelling concurrent GC: %s", GCCause::to_string(cause));
    _shenandoah_policy->report_concgc_cancelled();
  }
}

uint ShenandoahHeap::max_workers() {
  return _max_workers;
}

void ShenandoahHeap::stop() {
  // The shutdown sequence should be able to terminate when GC is running.

  // Step 0. Notify policy to disable event recording.
  _shenandoah_policy->record_shutdown();

  // Step 1. Notify control thread that we are in shutdown.
  // Note that we cannot do that with stop(), because stop() is blocking and waits for the actual shutdown.
  // Doing stop() here would wait for the normal GC cycle to complete, never falling through to cancel below.
  _concurrent_gc_thread->prepare_for_graceful_shutdown();

  // Step 2. Notify GC workers that we are cancelling GC.
  cancel_concgc(GCCause::_shenandoah_stop_vm);

  // Step 3. Wait until GC worker exits normally.
  _concurrent_gc_thread->stop();

  // Step 4. Stop String Dedup thread if it is active
  if (ShenandoahStringDedup::is_enabled()) {
    ShenandoahStringDedup::stop();
  }
}

void ShenandoahHeap::unload_classes_and_cleanup_tables(bool full_gc) {
  ShenandoahPhaseTimings::Phase phase_root =
          full_gc ?
          ShenandoahPhaseTimings::full_gc_purge :
          ShenandoahPhaseTimings::purge;

  ShenandoahPhaseTimings::Phase phase_unload =
          full_gc ?
          ShenandoahPhaseTimings::full_gc_purge_class_unload :
          ShenandoahPhaseTimings::purge_class_unload;

  ShenandoahPhaseTimings::Phase phase_cldg =
          full_gc ?
          ShenandoahPhaseTimings::full_gc_purge_cldg :
          ShenandoahPhaseTimings::purge_cldg;

  ShenandoahPhaseTimings::Phase phase_par =
          full_gc ?
          ShenandoahPhaseTimings::full_gc_purge_par :
          ShenandoahPhaseTimings::purge_par;

  ShenandoahPhaseTimings::Phase phase_par_classes =
          full_gc ?
          ShenandoahPhaseTimings::full_gc_purge_par_classes :
          ShenandoahPhaseTimings::purge_par_classes;

  ShenandoahPhaseTimings::Phase phase_par_codecache =
          full_gc ?
          ShenandoahPhaseTimings::full_gc_purge_par_codecache :
          ShenandoahPhaseTimings::purge_par_codecache;

  ShenandoahPhaseTimings::Phase phase_par_rmt =
          full_gc ?
          ShenandoahPhaseTimings::full_gc_purge_par_rmt :
          ShenandoahPhaseTimings::purge_par_rmt;

  ShenandoahPhaseTimings::Phase phase_par_symbstring =
          full_gc ?
          ShenandoahPhaseTimings::full_gc_purge_par_symbstring :
          ShenandoahPhaseTimings::purge_par_symbstring;

  ShenandoahPhaseTimings::Phase phase_par_sync =
          full_gc ?
          ShenandoahPhaseTimings::full_gc_purge_par_sync :
          ShenandoahPhaseTimings::purge_par_sync;

  ShenandoahGCPhase root_phase(phase_root);

  BoolObjectClosure* is_alive = is_alive_closure();

  bool purged_class;

  // Unload classes and purge SystemDictionary.
  {
    ShenandoahGCPhase phase(phase_unload);
    purged_class = SystemDictionary::do_unloading(is_alive,
                                                  full_gc ? this->full_gc()->gc_timer() : gc_timer(),
                                                  false /* defer cleaning */);
  }

  {
    ShenandoahGCPhase phase(phase_par);
    uint active = _workers->active_workers();
    ParallelCleaningTask unlink_task(is_alive, true, true, active, purged_class);
    _workers->run_task(&unlink_task);

    ShenandoahPhaseTimings* p = ShenandoahHeap::heap()->phase_timings();
    ParallelCleaningTimes times = unlink_task.times();

    // "times" report total time, phase_tables_cc reports wall time. Divide total times
    // by active workers to get average time per worker, that would add up to wall time.
    p->record_phase_time(phase_par_classes,    times.klass_work_us() / active);
    p->record_phase_time(phase_par_codecache,  times.codecache_work_us() / active);
    p->record_phase_time(phase_par_rmt,        times.rmt_work_us() / active);
    p->record_phase_time(phase_par_symbstring, times.tables_work_us() / active);
    p->record_phase_time(phase_par_sync,       times.sync_us() / active);
  }

  if (ShenandoahStringDedup::is_enabled()) {
    ShenandoahPhaseTimings::Phase phase_par_string_dedup =
            full_gc ?
            ShenandoahPhaseTimings::full_gc_purge_par_string_dedup :
            ShenandoahPhaseTimings::purge_par_string_dedup;
    ShenandoahGCPhase phase(phase_par_string_dedup);
    ShenandoahStringDedup::parallel_cleanup();
  }


  {
    ShenandoahGCPhase phase(phase_cldg);
    ClassLoaderDataGraph::purge();
  }
}

void ShenandoahHeap::set_has_forwarded_objects(bool cond) {
  set_gc_state_bit(HAS_FORWARDED_BITPOS, cond);
}

//fixme this should be in heapregionset
ShenandoahHeapRegion* ShenandoahHeap::next_compaction_region(const ShenandoahHeapRegion* r) {
  size_t region_idx = r->region_number() + 1;
  ShenandoahHeapRegion* next = _ordered_regions->get(region_idx);
  guarantee(next->region_number() == region_idx, "region number must match");
  while (next->is_humongous()) {
    region_idx = next->region_number() + 1;
    next = _ordered_regions->get(region_idx);
    guarantee(next->region_number() == region_idx, "region number must match");
  }
  return next;
}

ShenandoahMonitoringSupport* ShenandoahHeap::monitoring_support() {
  return _monitoring_support;
}

MarkBitMap* ShenandoahHeap::complete_mark_bit_map() {
  return _complete_mark_bit_map;
}

MarkBitMap* ShenandoahHeap::next_mark_bit_map() {
  return _next_mark_bit_map;
}

void ShenandoahHeap::add_free_region(ShenandoahHeapRegion* r) {
  _free_regions->add_region(r);
}

void ShenandoahHeap::clear_free_regions() {
  _free_regions->clear();
}

address ShenandoahHeap::in_cset_fast_test_addr() {
  ShenandoahHeap* heap = ShenandoahHeap::heap();
  assert(heap->collection_set() != NULL, "Sanity");
  return (address) heap->collection_set()->biased_map_address();
}

address ShenandoahHeap::cancelled_concgc_addr() {
  return (address) ShenandoahHeap::heap()->_cancelled_concgc.addr_of();
}

address ShenandoahHeap::gc_state_addr() {
  return (address) ShenandoahHeap::heap()->_gc_state.addr_of();
}

size_t ShenandoahHeap::conservative_max_heap_alignment() {
  return ShenandoahMaxRegionSize;
}

size_t ShenandoahHeap::bytes_allocated_since_cm() {
  return _bytes_allocated_since_cm;
}

void ShenandoahHeap::set_bytes_allocated_since_cm(size_t bytes) {
  _bytes_allocated_since_cm = bytes;
}

void ShenandoahHeap::set_next_top_at_mark_start(HeapWord* region_base, HeapWord* addr) {
  uintx index = ((uintx) region_base) >> ShenandoahHeapRegion::region_size_bytes_shift();
  _next_top_at_mark_starts[index] = addr;
}

HeapWord* ShenandoahHeap::next_top_at_mark_start(HeapWord* region_base) {
  uintx index = ((uintx) region_base) >> ShenandoahHeapRegion::region_size_bytes_shift();
  return _next_top_at_mark_starts[index];
}

void ShenandoahHeap::set_complete_top_at_mark_start(HeapWord* region_base, HeapWord* addr) {
  uintx index = ((uintx) region_base) >> ShenandoahHeapRegion::region_size_bytes_shift();
  _complete_top_at_mark_starts[index] = addr;
}

HeapWord* ShenandoahHeap::complete_top_at_mark_start(HeapWord* region_base) {
  uintx index = ((uintx) region_base) >> ShenandoahHeapRegion::region_size_bytes_shift();
  return _complete_top_at_mark_starts[index];
}

void ShenandoahHeap::set_full_gc_in_progress(bool in_progress) {
  _full_gc_in_progress.set_cond(in_progress);
}

void ShenandoahHeap::set_full_gc_move_in_progress(bool in_progress) {
  assert (is_full_gc_in_progress(), "should be");
  _full_gc_move_in_progress.set_cond(in_progress);
}

void ShenandoahHeap::set_update_refs_in_progress_at_safepoint(bool in_progress) {
  set_gc_state_bit(UPDATEREFS_BITPOS, in_progress);
}

void ShenandoahHeap::set_update_refs_in_progress_concurrently(bool in_progress) {
  set_gc_state_bit_concurrently(UPDATEREFS_BITPOS, in_progress);
}

void ShenandoahHeap::register_nmethod(nmethod* nm) {
  ShenandoahCodeRoots::add_nmethod(nm);
}

void ShenandoahHeap::unregister_nmethod(nmethod* nm) {
  ShenandoahCodeRoots::remove_nmethod(nm);
}

void ShenandoahHeap::pin_object(oop o) {
  ShenandoahHeapLocker locker(lock());
  heap_region_containing(o)->make_pinned();
}

void ShenandoahHeap::unpin_object(oop o) {
  ShenandoahHeapLocker locker(lock());
  heap_region_containing(o)->make_unpinned();
}

GCTimer* ShenandoahHeap::gc_timer() const {
  return _gc_timer;
}

#ifdef ASSERT
void ShenandoahHeap::assert_gc_workers(uint nworkers) {
  assert(nworkers > 0 && nworkers <= max_workers(), "Sanity");

  if (ShenandoahSafepoint::is_at_shenandoah_safepoint()) {
    if (UseDynamicNumberOfGCThreads ||
        (FLAG_IS_DEFAULT(ParallelGCThreads) && ForceDynamicNumberOfGCThreads)) {
      assert(nworkers <= ParallelGCThreads, "Cannot use more than it has");
    } else {
      // Use ParallelGCThreads inside safepoints
      assert(nworkers == ParallelGCThreads, "Use ParalleGCThreads within safepoints");
    }
  } else {
    if (UseDynamicNumberOfGCThreads ||
        (FLAG_IS_DEFAULT(ConcGCThreads) && ForceDynamicNumberOfGCThreads)) {
      assert(nworkers <= ConcGCThreads, "Cannot use more than it has");
    } else {
      // Use ConcGCThreads outside safepoints
      assert(nworkers == ConcGCThreads, "Use ConcGCThreads outside safepoints");
    }
  }
}
#endif

class ShenandoahCountGarbageClosure : public ShenandoahHeapRegionClosure {
private:
  size_t            _garbage;
public:
  ShenandoahCountGarbageClosure() : _garbage(0) {
  }

  bool heap_region_do(ShenandoahHeapRegion* r) {
    if (r->is_regular()) {
      _garbage += r->garbage();
    }
    return false;
  }

  size_t garbage() {
    return _garbage;
  }
};

size_t ShenandoahHeap::garbage() {
  ShenandoahCountGarbageClosure cl;
  heap_region_iterate(&cl);
  return cl.garbage();
}

ShenandoahConnectionMatrix* ShenandoahHeap::connection_matrix() const {
  return _connection_matrix;
}

ShenandoahPartialGC* ShenandoahHeap::partial_gc() {
  return _partial_gc;
}

ShenandoahVerifier* ShenandoahHeap::verifier() {
  guarantee(ShenandoahVerify, "Should be enabled");
  assert (_verifier != NULL, "sanity");
  return _verifier;
}

template<class T>
class ShenandoahUpdateHeapRefsTask : public AbstractGangTask {
private:
  T cl;
  ShenandoahHeap* _heap;
  ShenandoahHeapRegionSet* _regions;
  bool _concurrent;
public:
  ShenandoahUpdateHeapRefsTask(ShenandoahHeapRegionSet* regions, bool concurrent) :
    AbstractGangTask("Concurrent Update References Task"),
    cl(T()),
    _heap(ShenandoahHeap::heap()),
    _regions(regions),
    _concurrent(concurrent) {
  }

  void work(uint worker_id) {
    SuspendibleThreadSetJoiner stsj(_concurrent && ShenandoahSuspendibleWorkers);
    ShenandoahHeapRegion* r = _regions->claim_next();
    while (r != NULL) {
      if (_heap->in_collection_set(r)) {
        HeapWord* bottom = r->bottom();
        HeapWord* top = _heap->complete_top_at_mark_start(r->bottom());
        if (top > bottom) {
          _heap->complete_mark_bit_map()->clear_range_large(MemRegion(bottom, top));
        }
      } else {
        if (r->is_active()) {
          _heap->marked_object_oop_safe_iterate(r, &cl);
        }
      }
      if (_heap->check_cancelled_concgc_and_yield(_concurrent)) {
        return;
      }
      r = _regions->claim_next();
    }
  }
};

void ShenandoahHeap::update_heap_references(ShenandoahHeapRegionSet* update_regions, bool concurrent) {
  if (UseShenandoahMatrix) {
    ShenandoahUpdateHeapRefsTask<ShenandoahUpdateHeapRefsMatrixClosure> task(update_regions, concurrent);
    workers()->run_task(&task);
  } else {
    ShenandoahUpdateHeapRefsTask<ShenandoahUpdateHeapRefsClosure> task(update_regions, concurrent);
    workers()->run_task(&task);
  }
}

void ShenandoahHeap::op_init_updaterefs() {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "must be at safepoint");

  if (ShenandoahVerify) {
    verifier()->verify_before_updaterefs();
  }

  set_evacuation_in_progress_at_safepoint(false);
  set_update_refs_in_progress_at_safepoint(true);
  make_tlabs_parsable(true);
  if (UseShenandoahMatrix) {
    connection_matrix()->clear_all();
  }
  for (uint i = 0; i < num_regions(); i++) {
    ShenandoahHeapRegion* r = _ordered_regions->get(i);
    r->set_concurrent_iteration_safe_limit(r->top());
  }
}

void ShenandoahHeap::op_final_updaterefs() {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "must be at safepoint");

  if (cancelled_concgc()) {
    ShenandoahGCPhase final_work(ShenandoahPhaseTimings::final_update_refs_finish_work);

    // Finish updating references where we left off.
    clear_cancelled_concgc();
    ShenandoahHeapRegionSet* update_regions = regions();
    update_heap_references(update_regions, false);
  }

  assert(! cancelled_concgc(), "Should have been done right before");
  concurrentMark()->update_roots(ShenandoahPhaseTimings::final_update_refs_roots);

  // Allocations might have happened before we STWed here, record peak:
  shenandoahPolicy()->record_peak_occupancy();

  ShenandoahGCPhase final_update_refs(ShenandoahPhaseTimings::final_update_refs_recycle);

  trash_cset_regions();
  set_has_forwarded_objects(false);

  if (ShenandoahVerify) {
    verifier()->verify_after_updaterefs();
  }

  {
    // Rebuild the free set
    ShenandoahHeapLocker locker(lock());
    _free_regions->clear();
    size_t end = _ordered_regions->active_regions();
    for (size_t i = 0; i < end; i++) {
      ShenandoahHeapRegion* r = _ordered_regions->get(i);
      if (r->is_alloc_allowed()) {
        assert (!in_collection_set(r), "collection set should be clear");
        _free_regions->add_region(r);
      }
    }
  }

  set_update_refs_in_progress_at_safepoint(false);
}

void ShenandoahHeap::set_alloc_seq_gc_start() {
  // Take next number, the start seq number is inclusive
  _alloc_seq_at_last_gc_start = ShenandoahHeapRegion::alloc_seq_num() + 1;
}

void ShenandoahHeap::set_alloc_seq_gc_end() {
  // Take current number, the end seq number is also inclusive
  _alloc_seq_at_last_gc_end = ShenandoahHeapRegion::alloc_seq_num();
}


#ifdef ASSERT
void ShenandoahHeap::assert_heaplock_owned_by_current_thread() {
  _lock.assert_owned_by_current_thread();
}

void ShenandoahHeap::assert_heaplock_not_owned_by_current_thread() {
  _lock.assert_not_owned_by_current_thread();
}

void ShenandoahHeap::assert_heaplock_or_safepoint() {
  _lock.assert_owned_by_current_thread_or_safepoint();
}
#endif

void ShenandoahHeap::recycle_trash_assist(size_t limit) {
  assert_heaplock_owned_by_current_thread();

  size_t count = 0;
  for (size_t i = 0; (i < num_regions()) && (count < limit); i++) {
    ShenandoahHeapRegion *r = _ordered_regions->get(i);
    if (r->is_trash()) {
      decrease_used(r->used());
      r->recycle();
      _free_regions->add_region(r);
      count++;
    }
  }
}

void ShenandoahHeap::recycle_trash() {
  // lock is not reentrable, check we don't have it
  assert_heaplock_not_owned_by_current_thread();

  size_t bytes_reclaimed = 0;

  for (size_t i = 0; i < num_regions(); i++) {
    ShenandoahHeapRegion* r = _ordered_regions->get(i);
    if (r->is_trash()) {
      ShenandoahHeapLocker locker(lock());
      if (r->is_trash()) {
        bytes_reclaimed += r->used();
        decrease_used(r->used());
        r->recycle();
        _free_regions->add_region(r);
      }
    }
    SpinPause(); // allow allocators to take the lock
  }

  _shenandoah_policy->record_bytes_reclaimed(bytes_reclaimed);
}

void ShenandoahHeap::print_extended_on(outputStream *st) const {
  print_on(st);
  print_heap_regions_on(st);
}

bool ShenandoahHeap::is_bitmap_slice_committed(ShenandoahHeapRegion* r, bool skip_self) {
  size_t slice = r->region_number() / _bitmap_regions_per_slice;

  size_t regions_from = _bitmap_regions_per_slice * slice;
  size_t regions_to   = MIN2(num_regions(), _bitmap_regions_per_slice * (slice + 1));
  for (size_t g = regions_from; g < regions_to; g++) {
    assert (g / _bitmap_regions_per_slice == slice, "same slice");
    if (skip_self && g == r->region_number()) continue;
    if (_ordered_regions->get(g)->is_committed()) {
      return true;
    }
  }
  return false;
}

bool ShenandoahHeap::commit_bitmap_slice(ShenandoahHeapRegion* r) {
  assert_heaplock_owned_by_current_thread();

  if (is_bitmap_slice_committed(r, true)) {
    // Some other region from the group is already committed, meaning the bitmap
    // slice is already committed, we exit right away.
    return true;
  }

  // Commit the bitmap slice:
  size_t slice = r->region_number() / _bitmap_regions_per_slice;
  size_t off = _bitmap_bytes_per_slice * slice;
  size_t len = _bitmap_bytes_per_slice;
  if (!os::commit_memory((char*)_bitmap0_region.start() + off, len, false)) {
    return false;
  }
  if (!os::commit_memory((char*)_bitmap1_region.start() + off, len, false)) {
    return false;
  }
  return true;
}

bool ShenandoahHeap::uncommit_bitmap_slice(ShenandoahHeapRegion *r) {
  assert_heaplock_owned_by_current_thread();

  if (is_bitmap_slice_committed(r, true)) {
    // Some other region from the group is still committed, meaning the bitmap
    // slice is should stay committed, exit right away.
    return true;
  }

  // Uncommit the bitmap slice:
  size_t slice = r->region_number() / _bitmap_regions_per_slice;
  size_t off = _bitmap_bytes_per_slice * slice;
  size_t len = _bitmap_bytes_per_slice;
  if (!os::uncommit_memory((char*)_bitmap0_region.start() + off, len)) {
    return false;
  }
  if (!os::uncommit_memory((char*)_bitmap1_region.start() + off, len)) {
    return false;
  }
  return true;
}

void ShenandoahHeap::safepoint_synchronize_begin() {
  if (ShenandoahSuspendibleWorkers || UseStringDeduplication) {
    SuspendibleThreadSet::synchronize();
  }
}

void ShenandoahHeap::safepoint_synchronize_end() {
  if (ShenandoahSuspendibleWorkers || UseStringDeduplication) {
    SuspendibleThreadSet::desynchronize();
  }
}

void ShenandoahHeap::vmop_entry_init_mark() {
  TraceCollectorStats tcs(monitoring_support()->stw_collection_counters());
  ShenandoahGCPhase total(ShenandoahPhaseTimings::total_pause_gross);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::init_mark_gross);

  VM_ShenandoahInitMark op;
  VMThread::execute(&op); // jump to entry_init_mark() under safepoint
}

void ShenandoahHeap::vmop_entry_final_mark() {
  TraceCollectorStats tcs(monitoring_support()->stw_collection_counters());
  ShenandoahGCPhase total(ShenandoahPhaseTimings::total_pause_gross);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::final_mark_gross);

  VM_ShenandoahFinalMarkStartEvac op;
  VMThread::execute(&op); // jump to entry_final_mark under safepoint
}

void ShenandoahHeap::vmop_entry_init_updaterefs() {
  TraceCollectorStats tcs(monitoring_support()->stw_collection_counters());
  ShenandoahGCPhase total(ShenandoahPhaseTimings::total_pause_gross);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::init_update_refs_gross);

  VM_ShenandoahInitUpdateRefs op;
  VMThread::execute(&op);
}

void ShenandoahHeap::vmop_entry_final_updaterefs() {
  TraceCollectorStats tcs(monitoring_support()->stw_collection_counters());
  ShenandoahGCPhase total(ShenandoahPhaseTimings::total_pause_gross);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::final_update_refs_gross);

  VM_ShenandoahFinalUpdateRefs op;
  VMThread::execute(&op);
}

void ShenandoahHeap::vmop_entry_init_partial() {
  TraceCollectorStats tcs(monitoring_support()->stw_collection_counters());
  ShenandoahGCPhase total(ShenandoahPhaseTimings::total_pause_gross);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::init_partial_gc_gross);

  VM_ShenandoahInitPartialGC op;
  VMThread::execute(&op);
}

void ShenandoahHeap::vmop_entry_final_partial() {
  TraceCollectorStats tcs(monitoring_support()->stw_collection_counters());
  ShenandoahGCPhase total(ShenandoahPhaseTimings::total_pause_gross);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::final_partial_gc_gross);

  VM_ShenandoahFinalPartialGC op;
  VMThread::execute(&op);
}

void ShenandoahHeap::vmop_entry_verify_after_evac() {
  if (ShenandoahVerify) {
    ShenandoahGCPhase total(ShenandoahPhaseTimings::total_pause_gross);

    VM_ShenandoahVerifyHeapAfterEvacuation op;
    VMThread::execute(&op);
  }
}

void ShenandoahHeap::vmop_entry_full(GCCause::Cause cause) {
  TraceCollectorStats tcs(monitoring_support()->full_stw_collection_counters());
  ShenandoahGCPhase total(ShenandoahPhaseTimings::total_pause_gross);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::full_gc_gross);

  TraceMemoryManagerStats tmms(true, cause);
  VM_ShenandoahFullGC op(cause);
  VMThread::execute(&op);
}

void ShenandoahHeap::entry_init_mark() {
  ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::init_mark);
  GCTraceTime(Info, gc) time("Pause Init Mark", gc_timer());

  ShenandoahWorkerScope scope(workers(), ShenandoahWorkerPolicy::calc_workers_for_init_marking());

  op_init_mark();
}

void ShenandoahHeap::entry_final_mark() {
  ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::final_mark);
  GCTraceTime(Info, gc) time("Pause Final Mark", gc_timer());

  ShenandoahWorkerScope scope(workers(), ShenandoahWorkerPolicy::calc_workers_for_final_marking());

  op_final_mark();
}

void ShenandoahHeap::entry_init_updaterefs() {
  ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::init_update_refs);
  GCTraceTime(Info, gc) time("Pause Init Update Refs", gc_timer());

  // No workers used in this phase, no setup required

  op_init_updaterefs();
}

void ShenandoahHeap::entry_final_updaterefs() {
  ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::final_update_refs);
  GCTraceTime(Info, gc) time("Pause Final Update Refs", gc_timer());

  ShenandoahWorkerScope scope(workers(), ShenandoahWorkerPolicy::calc_workers_for_final_update_ref());

  op_final_updaterefs();
}

void ShenandoahHeap::entry_init_partial() {
  ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::init_partial_gc);
  GCTraceTime(Info, gc) time("Pause Init Partial", gc_timer());

  ShenandoahWorkerScope scope(workers(), ShenandoahWorkerPolicy::calc_workers_for_stw_partial());

  op_init_partial();
}

void ShenandoahHeap::entry_final_partial() {
  ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::final_partial_gc);
  GCTraceTime(Info, gc) time("Pause Final Partial", gc_timer());

  ShenandoahWorkerScope scope(workers(), ShenandoahWorkerPolicy::calc_workers_for_stw_partial());

  op_final_partial();
}

void ShenandoahHeap::entry_full(GCCause::Cause cause) {
  ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::full_gc);
  GCTraceTime(Info, gc) time("Pause Full", full_gc()->gc_timer(), cause, true);

  ShenandoahWorkerScope scope(workers(), ShenandoahWorkerPolicy::calc_workers_for_fullgc());

  op_full(cause);
}

void ShenandoahHeap::entry_verify_after_evac() {
  ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::pause_other);
  GCTraceTime(Info, gc) time("Pause Verify After Evac", gc_timer());

  op_verify_after_evac();
}

void ShenandoahHeap::entry_mark() {
  TraceCollectorStats tcs(monitoring_support()->concurrent_collection_counters());
  GCTraceTime(Info, gc) time("Concurrent marking", gc_timer(), GCCause::_no_gc, true);

  ShenandoahWorkerScope scope(workers(), ShenandoahWorkerPolicy::calc_workers_for_conc_marking());

  op_mark();
}

void ShenandoahHeap::entry_evac() {
  ShenandoahGCPhase conc_evac_phase(ShenandoahPhaseTimings::conc_evac);
  TraceCollectorStats tcs(monitoring_support()->concurrent_collection_counters());
  GCTraceTime(Info, gc) time("Concurrent evacuation", gc_timer(), GCCause::_no_gc, true);

  ShenandoahWorkerScope scope(workers(), ShenandoahWorkerPolicy::calc_workers_for_conc_evac());

  op_evac();
}

void ShenandoahHeap::entry_updaterefs() {
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::conc_update_refs);
  GCTraceTime(Info, gc) time("Concurrent update references", gc_timer(), GCCause::_no_gc, true);

  ShenandoahWorkerScope scope(workers(), ShenandoahWorkerPolicy::calc_workers_for_conc_update_ref());

  op_updaterefs();
}
void ShenandoahHeap::entry_cleanup() {
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::conc_cleanup);
  GCTraceTime(Info, gc) time("Concurrent cleanup", gc_timer(), GCCause::_no_gc, true);

  // This phase does not use workers, no need for setup

  op_cleanup();
}

void ShenandoahHeap::entry_cleanup_bitmaps() {
  GCTraceTime(Info, gc) time("Concurrent cleanup", gc_timer(), GCCause::_no_gc, true);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::conc_cleanup);

  ShenandoahWorkerScope scope(workers(), ShenandoahWorkerPolicy::calc_workers_for_conc_cleanup());

  op_cleanup_bitmaps();
}

void ShenandoahHeap::entry_preclean() {
  GCTraceTime(Info, gc) time("Concurrent precleaning", gc_timer(), GCCause::_no_gc, true);
  ShenandoahGCPhase conc_preclean(ShenandoahPhaseTimings::conc_preclean);

  ShenandoahWorkerScope scope(workers(), ShenandoahWorkerPolicy::calc_workers_for_conc_preclean());

  op_preclean();
}

void ShenandoahHeap::entry_partial() {
  GCTraceTime(Info, gc) time("Concurrent partial", gc_timer(), GCCause::_no_gc, true);
  TraceCollectorStats tcs(monitoring_support()->concurrent_collection_counters());

  ShenandoahWorkerScope scope(workers(), ShenandoahWorkerPolicy::calc_workers_for_conc_partial());

  op_partial();
}
