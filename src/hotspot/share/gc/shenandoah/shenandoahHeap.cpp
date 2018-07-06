/*
 * Copyright (c) 2013, 2018, Red Hat, Inc. and/or its affiliates.
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
#include "gc/shared/memAllocator.hpp"
#include "gc/shared/parallelCleaning.hpp"
#include "gc/shared/plab.hpp"

#include "gc/shenandoah/brooksPointer.hpp"
#include "gc/shenandoah/shenandoahAllocTracker.hpp"
#include "gc/shenandoah/shenandoahBarrierSet.hpp"
#include "gc/shenandoah/shenandoahCollectionSet.hpp"
#include "gc/shenandoah/shenandoahCollectorPolicy.hpp"
#include "gc/shenandoah/shenandoahConcurrentMark.hpp"
#include "gc/shenandoah/shenandoahConcurrentMark.inline.hpp"
#include "gc/shenandoah/shenandoahControlThread.hpp"
#include "gc/shenandoah/shenandoahFreeSet.hpp"
#include "gc/shenandoah/shenandoahPhaseTimings.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahHeapRegion.hpp"
#include "gc/shenandoah/shenandoahHeapRegionSet.hpp"
#include "gc/shenandoah/shenandoahMarkCompact.hpp"
#include "gc/shenandoah/shenandoahMemoryPool.hpp"
#include "gc/shenandoah/shenandoahMetrics.hpp"
#include "gc/shenandoah/shenandoahMonitoringSupport.hpp"
#include "gc/shenandoah/shenandoahOopClosures.inline.hpp"
#include "gc/shenandoah/shenandoahPacer.hpp"
#include "gc/shenandoah/shenandoahPacer.inline.hpp"
#include "gc/shenandoah/shenandoahRootProcessor.hpp"
#include "gc/shenandoah/shenandoahStringDedup.hpp"
#include "gc/shenandoah/shenandoahUtils.hpp"
#include "gc/shenandoah/shenandoahVerifier.hpp"
#include "gc/shenandoah/shenandoahCodeRoots.hpp"
#include "gc/shenandoah/shenandoahWorkerPolicy.hpp"
#include "gc/shenandoah/vm_operations_shenandoah.hpp"
#include "gc/shenandoah/heuristics/shenandoahAdaptiveHeuristics.hpp"
#include "gc/shenandoah/heuristics/shenandoahAggressiveHeuristics.hpp"
#include "gc/shenandoah/heuristics/shenandoahCompactHeuristics.hpp"
#include "gc/shenandoah/heuristics/shenandoahPartialConnectedHeuristics.hpp"
#include "gc/shenandoah/heuristics/shenandoahPartialGenerationalHeuristics.hpp"
#include "gc/shenandoah/heuristics/shenandoahPartialLRUHeuristics.hpp"
#include "gc/shenandoah/heuristics/shenandoahPassiveHeuristics.hpp"
#include "gc/shenandoah/heuristics/shenandoahStaticHeuristics.hpp"

#include "memory/metaspace.hpp"
#include "runtime/vmThread.hpp"
#include "services/mallocTracker.hpp"

ShenandoahUpdateRefsClosure::ShenandoahUpdateRefsClosure() : _heap(ShenandoahHeap::heap()) {}

#ifdef ASSERT
template <class T>
void ShenandoahAssertToSpaceClosure::do_oop_work(T* p) {
  T o = RawAccess<>::oop_load(p);
  if (! CompressedOops::is_null(o)) {
    oop obj = CompressedOops::decode_not_null(o);
    shenandoah_assert_not_forwarded(p, obj);
  }
}

void ShenandoahAssertToSpaceClosure::do_oop(narrowOop* p) { do_oop_work(p); }
void ShenandoahAssertToSpaceClosure::do_oop(oop* p)       { do_oop_work(p); }
#endif

const char* ShenandoahHeap::name() const {
  return "Shenandoah";
}

class ShenandoahPretouchTask : public AbstractGangTask {
private:
  ShenandoahRegionIterator _regions;
  const size_t _bitmap_size;
  const size_t _page_size;
  char* _bitmap0_base;
  char* _bitmap1_base;
public:
  ShenandoahPretouchTask(char* bitmap0_base, char* bitmap1_base, size_t bitmap_size,
                         size_t page_size) :
    AbstractGangTask("Shenandoah PreTouch"),
    _bitmap0_base(bitmap0_base),
    _bitmap1_base(bitmap1_base),
    _bitmap_size(bitmap_size),
    _page_size(page_size) {}

  virtual void work(uint worker_id) {
    ShenandoahHeapRegion* r = _regions.next();
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

      r = _regions.next();
    }
  }
};

jint ShenandoahHeap::initialize() {

  BrooksPointer::initial_checks();

  initialize_heuristics();

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

  BarrierSet::set_barrier_set(new ShenandoahBarrierSet(this));
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

  _regions = NEW_C_HEAP_ARRAY(ShenandoahHeapRegion*, _num_regions, mtGC);
  _free_set = new ShenandoahFreeSet(this, _num_regions);

  _collection_set = new ShenandoahCollectionSet(this, (HeapWord*)pgc_rs.base());

  _next_top_at_mark_starts_base = NEW_C_HEAP_ARRAY(HeapWord*, _num_regions, mtGC);
  _next_top_at_mark_starts = _next_top_at_mark_starts_base -
               ((uintx) pgc_rs.base() >> ShenandoahHeapRegion::region_size_bytes_shift());

  _complete_top_at_mark_starts_base = NEW_C_HEAP_ARRAY(HeapWord*, _num_regions, mtGC);
  _complete_top_at_mark_starts = _complete_top_at_mark_starts_base -
               ((uintx) pgc_rs.base() >> ShenandoahHeapRegion::region_size_bytes_shift());

  if (ShenandoahPacing) {
    _pacer = new ShenandoahPacer(this);
    _pacer->setup_for_idle();
  } else {
    _pacer = NULL;
  }

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
      _regions[i] = r;
      assert(!collection_set()->is_in(i), "New region should not be in collection set");
    }

    _free_set->rebuild();
  }

  assert((((size_t) base()) & ShenandoahHeapRegion::region_size_bytes_mask()) == 0,
         "misaligned heap: "PTR_FORMAT, p2i(base()));

  // The call below uses stuff (the SATB* things) that are in G1, but probably
  // belong into a shared location.
  ShenandoahBarrierSet::satb_mark_queue_set().initialize(SATB_Q_CBL_mon,
                                               SATB_Q_FL_lock,
                                               20 /*G1SATBProcessCompletedThreshold */,
                                               Shared_SATB_Q_lock);

  // Reserve space for prev and next bitmap.
  size_t bitmap_page_size = UseLargePages ? (size_t)os::large_page_size() : (size_t)os::vm_page_size();
  _bitmap_size = MarkBitMap::compute_size(heap_rs.size());
  _bitmap_size = align_up(_bitmap_size, bitmap_page_size);
  _heap_region = MemRegion((HeapWord*) heap_rs.base(), heap_rs.size() / HeapWordSize);

  size_t bitmap_bytes_per_region = reg_size_bytes / MarkBitMap::heap_map_factor();

  guarantee(bitmap_bytes_per_region != 0,
            "Bitmap bytes per region should not be zero");
  guarantee(is_power_of_2(bitmap_bytes_per_region),
            "Bitmap bytes per region should be power of two: " SIZE_FORMAT, bitmap_bytes_per_region);

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
    ShenandoahPushWorkerScope scope(workers(), _max_workers, false);

    log_info(gc, heap)("Parallel pretouch " SIZE_FORMAT " regions with " SIZE_FORMAT " byte pages",
                       _num_regions, page_size);
    ShenandoahPretouchTask cl(bitmap0.base(), bitmap1.base(), _bitmap_size, page_size);
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

  _traversal_gc = heuristics()->can_do_traversal_gc() ?
                new ShenandoahTraversalGC(this, _num_regions) :
                NULL;

  _monitoring_support = new ShenandoahMonitoringSupport(this);

  _phase_timings = new ShenandoahPhaseTimings();

  if (ShenandoahAllocationTrace) {
    _alloc_tracker = new ShenandoahAllocTracker();
  }

  ShenandoahStringDedup::initialize();

  _control_thread = new ShenandoahControlThread();

  ShenandoahCodeRoots::initialize();

  LogTarget(Trace, gc, region) lt;
  if (lt.is_enabled()) {
    ResourceMark rm;
    LogStream ls(lt);
    log_trace(gc, region)("All Regions");
    print_heap_regions_on(&ls);
    log_trace(gc, region)("Free Regions");
    _free_set->print_on(&ls);
  }

  log_info(gc, init)("Safepointing mechanism: %s",
                     SafepointMechanism::uses_thread_local_poll() ? "thread-local poll" :
                     (SafepointMechanism::uses_global_page_poll() ? "global-page poll" : "unknown"));

  return JNI_OK;
}

void ShenandoahHeap::initialize_heuristics() {
  if (ShenandoahGCHeuristics != NULL) {
    if (strcmp(ShenandoahGCHeuristics, "aggressive") == 0) {
      _heuristics = new ShenandoahAggressiveHeuristics();
    } else if (strcmp(ShenandoahGCHeuristics, "static") == 0) {
      _heuristics = new ShenandoahStaticHeuristics();
    } else if (strcmp(ShenandoahGCHeuristics, "adaptive") == 0) {
      _heuristics = new ShenandoahAdaptiveHeuristics();
    } else if (strcmp(ShenandoahGCHeuristics, "passive") == 0) {
      _heuristics = new ShenandoahPassiveHeuristics();
    } else if (strcmp(ShenandoahGCHeuristics, "compact") == 0) {
      _heuristics = new ShenandoahCompactHeuristics();
    } else if (strcmp(ShenandoahGCHeuristics, "connected") == 0) {
      _heuristics = new ShenandoahPartialConnectedHeuristics();
    } else if (strcmp(ShenandoahGCHeuristics, "generational") == 0) {
      _heuristics = new ShenandoahPartialGenerationalHeuristics();
    } else if (strcmp(ShenandoahGCHeuristics, "LRU") == 0) {
      _heuristics = new ShenandoahPartialLRUHeuristics();
    } else if (strcmp(ShenandoahGCHeuristics, "traversal") == 0) {
      _heuristics = new ShenandoahTraversalHeuristics();
    } else {
      vm_exit_during_initialization("Unknown -XX:ShenandoahGCHeuristics option");
    }

    if (_heuristics->is_diagnostic() && !UnlockDiagnosticVMOptions) {
      vm_exit_during_initialization(
              err_msg("Heuristics \"%s\" is diagnostic, and must be enabled via -XX:+UnlockDiagnosticVMOptions.",
                      _heuristics->name()));
    }
    if (_heuristics->is_experimental() && !UnlockExperimentalVMOptions) {
      vm_exit_during_initialization(
              err_msg("Heuristics \"%s\" is experimental, and must be enabled via -XX:+UnlockExperimentalVMOptions.",
                      _heuristics->name()));
    }

    if (ShenandoahStoreValEnqueueBarrier && ShenandoahStoreValReadBarrier) {
      vm_exit_during_initialization("Cannot use both ShenandoahStoreValEnqueueBarrier and ShenandoahStoreValReadBarrier");
    }
    log_info(gc, init)("Shenandoah heuristics: %s",
                       _heuristics->name());
    _heuristics->print_thresholds();
  } else {
      ShouldNotReachHere();
  }

}

#ifdef _MSC_VER
#pragma warning( push )
#pragma warning( disable:4355 ) // 'this' : used in base member initializer list
#endif

ShenandoahHeap::ShenandoahHeap(ShenandoahCollectorPolicy* policy) :
  CollectedHeap(),
  _shenandoah_policy(policy),
  _soft_ref_policy(),
  _regions(NULL),
  _free_set(NULL),
  _collection_set(NULL),
  _update_refs_iterator(this),
  _bytes_allocated_since_gc_start(0),
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
  _pacer(NULL),
  _used_at_last_gc(0),
  _alloc_seq_at_last_gc_start(0),
  _alloc_seq_at_last_gc_end(0),
  _safepoint_workers(NULL),
  _gc_cycle_mode(),
#ifdef ASSERT
  _heap_expansion_count(0),
#endif
  _gc_timer(new (ResourceObj::C_HEAP, mtGC) ConcurrentGCTimer()),
  _phase_timings(NULL),
  _alloc_tracker(NULL),
  _cycle_memory_manager("Shenandoah Cycles", "end of GC cycle"),
  _stw_memory_manager("Shenandoah Pauses", "end of GC pause"),
  _mutator_gclab_stats(new PLABStats("Shenandoah mutator GCLAB stats", OldPLABSize, PLABWeight)),
  _collector_gclab_stats(new PLABStats("Shenandoah collector GCLAB stats", YoungPLABSize, PLABWeight)),
  _memory_pool(NULL)
{
  log_info(gc, init)("Parallel GC threads: " UINT32_FORMAT, ParallelGCThreads);
  log_info(gc, init)("Concurrent GC threads: " UINT32_FORMAT, ConcGCThreads);
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

#ifdef _MSC_VER
#pragma warning( pop )
#endif

class ShenandoahResetNextBitmapTask : public AbstractGangTask {
private:
  ShenandoahRegionIterator _regions;

public:
  ShenandoahResetNextBitmapTask() :
    AbstractGangTask("Parallel Reset Bitmap Task") {}

  void work(uint worker_id) {
    ShenandoahHeapRegion* region = _regions.next();
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
      region = _regions.next();
    }
  }
};

void ShenandoahHeap::reset_next_mark_bitmap() {
  assert_gc_workers(_workers->active_workers());

  ShenandoahResetNextBitmapTask task;
  _workers->run_task(&task);
}

class ShenandoahResetNextBitmapTraversalTask : public AbstractGangTask {
private:
  ShenandoahRegionIterator _regions;

public:
  ShenandoahResetNextBitmapTraversalTask() :
    AbstractGangTask("Parallel Reset Bitmap Task for Traversal") {}

  void work(uint worker_id) {
    ShenandoahHeap* heap = ShenandoahHeap::heap();
    ShenandoahHeapRegionSet* traversal_set = heap->traversal_gc()->traversal_set();
    ShenandoahHeapRegion* region = _regions.next();
    while (region != NULL) {
      if (heap->is_bitmap_slice_committed(region)) {
        if (traversal_set->is_in(region) && !region->is_trash()) {
          ShenandoahHeapLocker locker(heap->lock());
          HeapWord* bottom = region->bottom();
          HeapWord* top = heap->next_top_at_mark_start(bottom);
          assert(top <= region->top(),
                 "TAMS must smaller/equals than top: TAMS: "PTR_FORMAT", top: "PTR_FORMAT,
                 p2i(top), p2i(region->top()));
          if (top > bottom) {
            heap->complete_mark_bit_map()->copy_from(heap->next_mark_bit_map(), MemRegion(bottom, top));
            heap->set_complete_top_at_mark_start(bottom, top);
            heap->next_mark_bit_map()->clear_range_large(MemRegion(bottom, top));
            heap->set_next_top_at_mark_start(bottom, bottom);
          }
        }
        assert(heap->is_next_bitmap_clear_range(region->bottom(), region->end()),
               "need clear next bitmap");
      }
      region = _regions.next();
    }
  }
};

void ShenandoahHeap::reset_next_mark_bitmap_traversal() {
  assert_gc_workers(_workers->active_workers());

  ShenandoahResetNextBitmapTraversalTask task;
  _workers->run_task(&task);
}

bool ShenandoahHeap::is_next_bitmap_clear() {
  for (size_t idx = 0; idx < _num_regions; idx++) {
    ShenandoahHeapRegion* r = get_region(idx);
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
  if (has_forwarded_objects())               st->print("has forwarded objects, ");
  if (is_concurrent_mark_in_progress())      st->print("marking, ");
  if (is_evacuation_in_progress())           st->print("evacuating, ");
  if (is_update_refs_in_progress())          st->print("updating refs, ");
  if (is_concurrent_traversal_in_progress()) st->print("traversal, ");
  if (is_degenerated_gc_in_progress())       st->print("degenerated gc, ");
  if (is_full_gc_in_progress())              st->print("full gc, ");
  if (is_full_gc_move_in_progress())         st->print("full gc move, ");

  if (cancelled_gc()) {
    st->print("cancelled");
  } else {
    st->print("not cancelled");
  }
  st->cr();

  st->print_cr("Reserved region:");
  st->print_cr(" - [" PTR_FORMAT ", " PTR_FORMAT ") ",
               p2i(reserved_region().start()),
               p2i(reserved_region().end()));

  st->cr();
  MetaspaceUtils::print_on(st);

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
    if (thread != NULL && (thread->is_Java_thread() || thread->is_Worker_thread() ||
                           thread->is_ConcurrentGC_thread())) {
      ShenandoahThreadLocalData::initialize_gclab(thread);
    }
  }
};

void ShenandoahHeap::post_initialize() {
  CollectedHeap::post_initialize();
  MutexLocker ml(Threads_lock);

  ShenandoahInitGCLABClosure init_gclabs;
  Threads::threads_do(&init_gclabs);
  gc_threads_do(&init_gclabs);

  // gclab can not be initialized early during VM startup, as it can not determinate its max_size.
  // Now, we will let WorkGang to initialize gclab when new worker is created.
  _workers->set_initialize_gclab();

  _scm->initialize(_max_workers);
  _full_gc->initialize(_gc_timer);

  ref_processing_init();

  _heuristics->initialize();
}

size_t ShenandoahHeap::used() const {
  return OrderAccess::load_acquire(&_used);
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
  Atomic::add(bytes, &_used);
}

void ShenandoahHeap::set_used(size_t bytes) {
  OrderAccess::release_store_fence(&_used, bytes);
}

void ShenandoahHeap::decrease_used(size_t bytes) {
  assert(used() >= bytes, "never decrease heap size by more than we've left");
  Atomic::sub(bytes, &_used);
}

void ShenandoahHeap::increase_allocated(size_t bytes) {
  Atomic::add(bytes, &_bytes_allocated_since_gc_start);
}

void ShenandoahHeap::notify_alloc(size_t words, bool waste) {
  size_t bytes = words * HeapWordSize;
  if (!waste) {
    increase_used(bytes);
  }
  increase_allocated(bytes);
  if (ShenandoahPacing) {
    control_thread()->pacing_notify_alloc(words);
    if (waste) {
      pacer()->claim_for_alloc(words, true);
    }
  }
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

void ShenandoahHeap::op_uncommit(double shrink_before) {
  assert (ShenandoahUncommit, "should be enabled");

  size_t count = 0;
  for (size_t i = 0; i < num_regions(); i++) {
    ShenandoahHeapRegion* r = get_region(i);
    if (r->is_empty_committed() && (r->empty_time() < shrink_before)) {
      ShenandoahHeapLocker locker(lock());
      if (r->is_empty_committed()) {
        r->make_uncommitted();
        count++;
      }
    }
    SpinPause(); // allow allocators to take the lock
  }

  if (count > 0) {
    log_info(gc)("Uncommitted " SIZE_FORMAT "M. Heap: " SIZE_FORMAT "M reserved, " SIZE_FORMAT "M committed, " SIZE_FORMAT "M used",
                 count * ShenandoahHeapRegion::region_size_bytes() / M, capacity() / M, committed() / M, used() / M);
    control_thread()->notify_heap_changed();
  }

  // Allocations happen during uncommits, record peak after the phase:
  heuristics()->record_peak_occupancy();
}

HeapWord* ShenandoahHeap::allocate_from_gclab_slow(Thread* thread, size_t size) {
  // Retain tlab and allocate object in shared space if
  // the amount free in the tlab is too large to discard.
  PLAB* gclab = ShenandoahThreadLocalData::gclab(thread);

  // Discard gclab and allocate a new one.
  // To minimize fragmentation, the last GCLAB may be smaller than the rest.
  gclab->retire();
  // Figure out size of new GCLAB
  size_t new_gclab_size;
  if (thread->is_Java_thread()) {
    new_gclab_size = _mutator_gclab_stats->desired_plab_sz(Threads::number_of_threads());
  } else {
    new_gclab_size = _collector_gclab_stats->desired_plab_sz(workers()->active_workers());
  }

  // Allocate a new GCLAB...
  HeapWord* gclab_buf = allocate_new_gclab(new_gclab_size);
  if (gclab_buf == NULL) {
    return NULL;
  }

  if (ZeroTLAB) {
    // ..and clear it.
    Copy::zero_to_words(gclab_buf, new_gclab_size);
  } else {
    // ...and zap just allocated object.
#ifdef ASSERT
    // Skip mangling the space corresponding to the object header to
    // ensure that the returned space is not considered parsable by
    // any concurrent GC thread.
    size_t hdr_size = oopDesc::header_size();
    Copy::fill_to_words(gclab_buf + hdr_size, new_gclab_size - hdr_size, badHeapWordVal);
#endif // ASSERT
  }
  gclab->set_buf(gclab_buf, new_gclab_size);
  return gclab->allocate(size);
}

HeapWord* ShenandoahHeap::allocate_new_tlab(size_t min_size,
                                            size_t requested_size,
                                            size_t* actual_size) {
#ifdef ASSERT
  log_debug(gc, alloc)("Allocate new tlab, requested size = " SIZE_FORMAT " bytes", requested_size * HeapWordSize);
#endif
  HeapWord* addr = allocate_new_lab(requested_size, _alloc_tlab);
  if (addr != NULL) {
    *actual_size = requested_size;
  } else {
    *actual_size = 0;
  }
  return addr;
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

    log_develop_trace(gc, tlab)("allocating new tlab of size " SIZE_FORMAT " at addr " PTR_FORMAT, word_size, p2i(result));

  }
  return result;
}

ShenandoahHeap* ShenandoahHeap::heap() {
  CollectedHeap* heap = Universe::heap();
  assert(heap != NULL, "Unitialized access to ShenandoahHeap::heap()");
  assert(heap->kind() == CollectedHeap::Shenandoah, "not a shenandoah heap");
  return (ShenandoahHeap*) heap;
}

ShenandoahHeap* ShenandoahHeap::heap_no_check() {
  CollectedHeap* heap = Universe::heap();
  return (ShenandoahHeap*) heap;
}

HeapWord* ShenandoahHeap::allocate_memory(size_t word_size, AllocType type) {
  ShenandoahAllocTrace trace_alloc(word_size, type);

  bool in_new_region = false;
  HeapWord* result = NULL;

  if (type == _alloc_tlab || type == _alloc_shared) {
    if (ShenandoahPacing) {
      pacer()->pace_for_alloc(word_size);
    }

    if (!ShenandoahAllocFailureALot || !should_inject_alloc_failure()) {
      result = allocate_memory_under_lock(word_size, type, in_new_region);
    }

    // Allocation failed, block until control thread reacted, then retry allocation.
    //
    // It might happen that one of the threads requesting allocation would unblock
    // way later after GC happened, only to fail the second allocation, because
    // other threads have already depleted the free storage. In this case, a better
    // strategy is to try again, as long as GC makes progress.
    //
    // Then, we need to make sure the allocation was retried after at least one
    // Full GC, which means we want to try more than ShenandoahFullGCThreshold times.

    size_t tries = 0;

    while (result == NULL && last_gc_made_progress()) {
      tries++;
      control_thread()->handle_alloc_failure(word_size);
      result = allocate_memory_under_lock(word_size, type, in_new_region);
    }

    while (result == NULL && tries <= ShenandoahFullGCThreshold) {
      tries++;
      control_thread()->handle_alloc_failure(word_size);
      result = allocate_memory_under_lock(word_size, type, in_new_region);
    }

  } else {
    assert(type == _alloc_gclab || type == _alloc_shared_gc, "Can only accept these types here");
    result = allocate_memory_under_lock(word_size, type, in_new_region);
    // Do not call handle_alloc_failure() here, because we cannot block.
    // The allocation failure would be handled by the WB slowpath with handle_alloc_failure_evac().
  }

  if (in_new_region) {
    control_thread()->notify_heap_changed();
  }

  if (result != NULL) {
    log_develop_trace(gc, alloc)("allocate memory chunk of size " SIZE_FORMAT " at addr " PTR_FORMAT " by thread %d ",
                                 word_size, p2i(result), Thread::current()->osthread()->thread_id());
    notify_alloc(word_size, false);
  }

  return result;
}

HeapWord* ShenandoahHeap::allocate_memory_under_lock(size_t word_size, AllocType type, bool& in_new_region) {
  ShenandoahHeapLocker locker(lock());
  return _free_set->allocate(word_size, type, in_new_region);
}

class ShenandoahObjAllocator : public ObjAllocator {
public:
  ShenandoahObjAllocator(Klass* klass, size_t word_size, Thread* thread) :
    ObjAllocator(klass, word_size, thread) {}

  virtual HeapWord* mem_allocate(Allocation& allocation) {
    // Allocate object.
    _word_size += BrooksPointer::word_size();
    HeapWord* result = ObjAllocator::mem_allocate(allocation);
    _word_size -= BrooksPointer::word_size();
    // Initialize brooks-pointer
    if (result != NULL) {
      result += BrooksPointer::word_size();
      BrooksPointer::initialize(oop(result));
      assert(! ShenandoahHeap::heap()->in_collection_set(result), "never allocate in targetted region");
    }
    return result;
  }
};

oop ShenandoahHeap::obj_allocate(Klass* klass, int size, TRAPS) {
  ShenandoahObjAllocator allocator(klass, size, THREAD);
  return allocator.allocate();
}

class ShenandoahObjArrayAllocator : public ObjArrayAllocator {
public:
  ShenandoahObjArrayAllocator(Klass* klass, size_t word_size, int length, bool do_zero,
                              Thread* thread) :
    ObjArrayAllocator(klass, word_size, length, do_zero, thread) {}

  virtual HeapWord* mem_allocate(Allocation& allocation) {
    // Allocate object.
    _word_size += BrooksPointer::word_size();
    HeapWord* result = ObjArrayAllocator::mem_allocate(allocation);
    _word_size -= BrooksPointer::word_size();
    if (result != NULL) {
      result += BrooksPointer::word_size();
      BrooksPointer::initialize(oop(result));
      assert(! ShenandoahHeap::heap()->in_collection_set(result), "never allocate in targetted region");
    }
    return result;
  }

};

oop ShenandoahHeap::array_allocate(Klass* klass, int size, int length, bool do_zero, TRAPS) {
  ShenandoahObjArrayAllocator allocator(klass, size, length, do_zero, THREAD);
  return allocator.allocate();
}

class ShenandoahClassAllocator : public ClassAllocator {
public:
  ShenandoahClassAllocator(Klass* klass, size_t word_size, Thread* thread) :
    ClassAllocator(klass, word_size, thread) {}

  virtual HeapWord* mem_allocate(Allocation& allocation) {
    _word_size += BrooksPointer::word_size();
    HeapWord* result = ClassAllocator::mem_allocate(allocation);
    _word_size -= BrooksPointer::word_size();
    if (result != NULL) {
      result += BrooksPointer::word_size();
      BrooksPointer::initialize(oop(result));
      assert(! ShenandoahHeap::heap()->in_collection_set(result), "never allocate in targetted region");
    }
    return result;
  }

};

oop ShenandoahHeap::class_allocate(Klass* klass, int size, TRAPS) {
  ShenandoahClassAllocator allocator(klass, size, THREAD);
  return allocator.allocate();
}

HeapWord* ShenandoahHeap::mem_allocate(size_t size,
                                        bool*  gc_overhead_limit_was_exceeded) {
  return  allocate_memory(size, _alloc_shared);
}

void ShenandoahHeap::fill_with_dummy_object(HeapWord* start, HeapWord* end, bool zap) {
  HeapWord* obj = tlab_post_allocation_setup(start);
  CollectedHeap::fill_with_object(obj, end);
}

class ShenandoahEvacuateUpdateRootsClosure: public BasicOopIterateClosure {
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

    T o = RawAccess<>::oop_load(p);
    if (! CompressedOops::is_null(o)) {
      oop obj = CompressedOops::decode_not_null(o);
      if (_heap->in_collection_set(obj)) {
        shenandoah_assert_marked_complete(p, obj);
        oop resolved = ShenandoahBarrierSet::resolve_forwarded_not_null(obj);
        if (oopDesc::unsafe_equals(resolved, obj)) {
          resolved = _heap->evacuate_object(obj, _thread);
        }
        RawAccess<IS_NOT_NULL>::oop_store(p, resolved);
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

class ShenandoahEvacuateRootsClosure: public BasicOopIterateClosure {
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
    T o = RawAccess<>::oop_load(p);
    if (! CompressedOops::is_null(o)) {
      oop obj = CompressedOops::decode_not_null(o);
      if (_heap->in_collection_set(obj)) {
        oop resolved = ShenandoahBarrierSet::resolve_forwarded_not_null(obj);
        if (oopDesc::unsafe_equals(resolved, obj)) {
          _heap->evacuate_object(obj, _thread);
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
    shenandoah_assert_marked_complete(NULL, p);
    if (oopDesc::unsafe_equals(p, ShenandoahBarrierSet::resolve_forwarded_not_null(p))) {
      _heap->evacuate_object(p, _thread);
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
    ShenandoahWorkerSession worker_session(worker_id);
    ShenandoahEvacOOMScope oom_evac_scope;
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
      log_develop_trace(gc, region)("Thread " INT32_FORMAT " claimed Heap Region " SIZE_FORMAT,
                                    worker_id,
                                    r->region_number());

      assert(r->has_live(), "all-garbage regions are reclaimed early");
      _sh->marked_object_iterate(r, &cl);

      if (ShenandoahPacing) {
        _sh->pacer()->report_evac(r->used() >> LogHeapWordSize);
      }

      if (_sh->check_cancelled_gc_and_yield()) {
        log_develop_trace(gc, region)("Cancelled GC while evacuating region " SIZE_FORMAT, r->region_number());
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
  st->print_cr("SN=alloc sequence numbers (first mutator, last mutator, first gc, last gc)");

  for (size_t i = 0; i < num_regions(); i++) {
    get_region(i)->print_on(st);
  }
}

void ShenandoahHeap::trash_humongous_region_at(ShenandoahHeapRegion* start) {
  assert(start->is_humongous_start(), "reclaim regions starting with the first one");

  oop humongous_obj = oop(start->bottom() + BrooksPointer::word_size());
  size_t size = humongous_obj->size() + BrooksPointer::word_size();
  size_t required_regions = ShenandoahHeapRegion::required_regions(size * HeapWordSize);
  size_t index = start->region_number() + required_regions - 1;

  assert(!start->has_live(), "liveness must be zero");
  log_trace(gc, humongous)("Reclaiming " SIZE_FORMAT " humongous regions for object of size: " SIZE_FORMAT " words", required_regions, size);

  for(size_t i = 0; i < required_regions; i++) {
    // Reclaim from tail. Otherwise, assertion fails when printing region to trace log,
    // as it expects that every region belongs to a humongous region starting with a humongous start region.
    ShenandoahHeapRegion* region = get_region(index --);

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
  log_develop_trace(gc)("Thread %d started prepare_for_concurrent_evacuation", Thread::current()->osthread()->thread_id());

  if (!cancelled_gc()) {
    // Allocations might have happened before we STWed here, record peak:
    heuristics()->record_peak_occupancy();

    make_parsable(true);

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
      _free_set->clear();

#ifdef ASSERT
      ShenandoahCheckCollectionSetClosure ccsc;
      heap_region_iterate(&ccsc);
#endif

      heuristics()->choose_collection_set(_collection_set);

      _free_set->rebuild();
    }

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
    PLAB* gclab = ShenandoahThreadLocalData::gclab(thread);
    assert(gclab != NULL, "GCLAB should be initialized for %s", thread->name());
    gclab->retire();
  }
};

void ShenandoahHeap::make_parsable(bool retire_tlabs) {
  if (UseTLAB) {
    CollectedHeap::ensure_parsability(retire_tlabs);
  }
  ShenandoahRetireTLABClosure cl(retire_tlabs);
  for (JavaThreadIteratorWithHandle jtiwh; JavaThread *t = jtiwh.next(); ) {
    cl.do_thread(t);
  }
  gc_threads_do(&cl);
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
    ShenandoahWorkerSession worker_session(worker_id);
    ShenandoahEvacOOMScope oom_evac_scope;
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
    ShenandoahWorkerSession worker_session(worker_id);
    ShenandoahEvacOOMScope oom_evac_scope;
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
  if (cancelled_gc()) {
    fixup_roots();
  }
}

void ShenandoahHeap::fixup_roots() {
    assert(cancelled_gc(), "Only after concurrent cycle failed");

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
  rp.process_all_roots(cl, NULL, &cldCl, &blobsCl, NULL, 0);
}

bool ShenandoahHeap::supports_tlab_allocation() const {
  return true;
}

size_t  ShenandoahHeap::unsafe_max_tlab_alloc(Thread *thread) const {
  // Returns size in bytes
  return MIN2(_free_set->unsafe_peek_free(), ShenandoahHeapRegion::max_tlab_size_bytes());
}

size_t ShenandoahHeap::max_tlab_size() const {
  // Returns size in words
  return ShenandoahHeapRegion::max_tlab_size_words();
}

class ShenandoahAccumulateStatisticsGCLABClosure : public ThreadClosure {
public:
  void do_thread(Thread* thread) {
    ShenandoahHeap* heap = ShenandoahHeap::heap();
    PLAB* gclab = ShenandoahThreadLocalData::gclab(thread);
    if (thread->is_Java_thread()) {
      gclab->flush_and_retire_stats(heap->mutator_gclab_stats());
    } else {
      gclab->flush_and_retire_stats(heap->collector_gclab_stats());
    }
  }
};

void ShenandoahHeap::accumulate_statistics_all_gclabs() {
  ShenandoahAccumulateStatisticsGCLABClosure cl;
  for (JavaThreadIteratorWithHandle jtiwh; JavaThread *t = jtiwh.next(); ) {
    cl.do_thread(t);
  }
  gc_threads_do(&cl);
  _mutator_gclab_stats->adjust_desired_plab_sz();
  _collector_gclab_stats->adjust_desired_plab_sz();
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
  control_thread()->handle_explicit_gc(cause);
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
    make_parsable(false);
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

    if (ShenandoahPacing) {
      pacer()->print_on(&ls);
    }

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
  return _free_set->capacity();
}

class ObjectIterateScanRootClosure : public BasicOopIterateClosure {
private:
  MarkBitMap* _bitmap;
  Stack<oop,mtGC>* _oop_stack;

  template <class T>
  void do_oop_work(T* p) {
    T o = RawAccess<>::oop_load(p);
    if (!CompressedOops::is_null(o)) {
      oop obj = CompressedOops::decode_not_null(o);
      obj = ShenandoahBarrierSet::resolve_forwarded_not_null(obj);
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

  // Reset bitmap
  MemRegion mr = MemRegion(_aux_bit_map.startWord(), _aux_bit_map.endWord());
  _aux_bit_map.clear_range_large(mr);

  Stack<oop,mtGC> oop_stack;

  // First, we process all GC roots. This populates the work stack with initial objects.
  ShenandoahRootProcessor rp(this, 1, ShenandoahPhaseTimings::_num_phases);
  ObjectIterateScanRootClosure oops(&_aux_bit_map, &oop_stack);
  CLDToOopClosure clds(&oops, false);
  CodeBlobToOopClosure blobs(&oops, false);
  rp.process_all_roots(&oops, &oops, &clds, &blobs, NULL, 0);

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
    ShenandoahHeapRegion* current  = get_region(i);
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
  {
    ShenandoahGCPhase phase(ShenandoahPhaseTimings::make_parsable);
    make_parsable(true);
  }

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

  if (ShenandoahPacing) {
    pacer()->setup_for_mark();
  }
}

void ShenandoahHeap::op_mark() {
  concurrentMark()->mark_from_roots();

  // Allocations happen during concurrent mark, record peak after the phase:
  heuristics()->record_peak_occupancy();
}

void ShenandoahHeap::op_final_mark() {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Should be at safepoint");

  // It is critical that we
  // evacuate roots right after finishing marking, so that we don't
  // get unmarked objects in the roots.

  if (!cancelled_gc()) {
    concurrentMark()->finish_mark_from_roots();
    stop_concurrent_marking();

    {
      ShenandoahGCPhase prepare_evac(ShenandoahPhaseTimings::prepare_evac);
      prepare_for_concurrent_evacuation();
    }

    // If collection set has candidates, start evacuation.
    // Otherwise, bypass the rest of the cycle.
    if (!collection_set()->is_empty()) {
      set_evacuation_in_progress(true);
      // From here on, we need to update references.
      set_has_forwarded_objects(true);

      ShenandoahGCPhase init_evac(ShenandoahPhaseTimings::init_evac);
      evacuate_and_update_roots();
    }

    if (ShenandoahPacing) {
      pacer()->setup_for_evac();
    }
  } else {
    concurrentMark()->cancel();
    stop_concurrent_marking();

    if (process_references()) {
      // Abandon reference processing right away: pre-cleaning must have failed.
      ReferenceProcessor *rp = ref_processor();
      rp->disable_discovery();
      rp->abandon_partial_discovery();
      rp->verify_no_references_recorded();
    }
  }
}

void ShenandoahHeap::op_final_evac() {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Should be at safepoint");

  accumulate_statistics_all_gclabs();
  set_evacuation_in_progress(false);
  if (ShenandoahVerify) {
    verifier()->verify_after_evacuation();
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
    ls.print_cr("Collection set (" SIZE_FORMAT " regions):", _collection_set->count());
    _collection_set->print_on(&ls);

    ls.print_cr("Free set:");
    _free_set->print_on(&ls);
  }

  ShenandoahParallelEvacuationTask task(this, _collection_set);
  workers()->run_task(&task);

  if (lt_cset.is_enabled()) {
    ResourceMark rm;
    LogStream ls(lt_cset);
    ls.print_cr("After evacuation collection set (" SIZE_FORMAT " regions):",
                _collection_set->count());
    _collection_set->print_on(&ls);

    ls.print_cr("After evacuation free set:");
    _free_set->print_on(&ls);
  }

  if (lt_region.is_enabled()) {
    ResourceMark rm;
    LogStream ls(lt_region);
    ls.print_cr("All regions after evacuation:");
    print_heap_regions_on(&ls);
  }

  // Allocations happen during evacuation, record peak after the phase:
  heuristics()->record_peak_occupancy();
}

void ShenandoahHeap::op_updaterefs() {
  update_heap_references(true);

  // Allocations happen during update-refs, record peak after the phase:
  heuristics()->record_peak_occupancy();
}

void ShenandoahHeap::op_cleanup() {
  ShenandoahGCPhase phase_recycle(ShenandoahPhaseTimings::conc_cleanup_recycle);
  free_set()->recycle_trash();

  // Allocations happen during cleanup, record peak after the phase:
  heuristics()->record_peak_occupancy();
}

void ShenandoahHeap::op_cleanup_bitmaps() {
  op_cleanup();

  ShenandoahGCPhase phase_reset(ShenandoahPhaseTimings::conc_cleanup_reset_bitmaps);
  reset_next_mark_bitmap();

  // Allocations happen during bitmap cleanup, record peak after the phase:
  heuristics()->record_peak_occupancy();
}

void ShenandoahHeap::op_cleanup_traversal() {

  {
    ShenandoahGCPhase phase_reset(ShenandoahPhaseTimings::conc_cleanup_reset_bitmaps);
    reset_next_mark_bitmap_traversal();
  }

  op_cleanup();

  // Allocations happen during bitmap cleanup, record peak after the phase:
  heuristics()->record_peak_occupancy();
}

void ShenandoahHeap::op_preclean() {
  concurrentMark()->preclean_weak_refs();

  // Allocations happen during concurrent preclean, record peak after the phase:
  heuristics()->record_peak_occupancy();
}

void ShenandoahHeap::op_init_traversal() {
  traversal_gc()->init_traversal_collection();
}

void ShenandoahHeap::op_traversal() {
  traversal_gc()->concurrent_traversal_collection();
}

void ShenandoahHeap::op_final_traversal() {
  traversal_gc()->final_traversal_collection();
}

void ShenandoahHeap::op_full(GCCause::Cause cause) {
  ShenandoahMetricsSnapshot metrics;
  metrics.snap_before();

  full_gc()->do_it(cause);
  if (UseTLAB) {
    ShenandoahGCPhase phase(ShenandoahPhaseTimings::full_gc_resize_tlabs);
    resize_all_tlabs();
  }

  metrics.snap_after();
  metrics.print();

  if (metrics.is_good_progress("Full GC")) {
    _progress_last_gc.set();
  } else {
    // Nothing to do. Tell the allocation path that we have failed to make
    // progress, and it can finally fail.
    _progress_last_gc.unset();
  }
}

void ShenandoahHeap::op_degenerated(ShenandoahDegenPoint point) {
  // Degenerated GC is STW, but it can also fail. Current mechanics communicates
  // GC failure via cancelled_concgc() flag. So, if we detect the failure after
  // some phase, we have to upgrade the Degenerate GC to Full GC.

  clear_cancelled_gc();

  ShenandoahMetricsSnapshot metrics;
  metrics.snap_before();

  switch (point) {
    case _degenerated_evac:
      // Not possible to degenerate from here, upgrade to Full GC right away.
      cancel_gc(GCCause::_shenandoah_upgrade_to_full_gc);
      op_degenerated_fail();
      return;

    // The cases below form the Duff's-like device: it describes the actual GC cycle,
    // but enters it at different points, depending on which concurrent phase had
    // degenerated.

    case _degenerated_traversal:
      {
        ShenandoahHeapLocker locker(lock());
        collection_set()->clear_current_index();
        for (size_t i = 0; i < collection_set()->count(); i++) {
          ShenandoahHeapRegion* r = collection_set()->next();
          r->make_regular_bypass();
        }
        collection_set()->clear();
      }
      op_final_traversal();
      op_cleanup_traversal();
      return;

    case _degenerated_outside_cycle:
      if (heuristics()->can_do_traversal_gc()) {
        // Not possible to degenerate from here, upgrade to Full GC right away.
        cancel_gc(GCCause::_shenandoah_upgrade_to_full_gc);
        op_degenerated_fail();
        return;
      }
      op_init_mark();
      if (cancelled_gc()) {
        op_degenerated_fail();
        return;
      }

    case _degenerated_mark:
      op_final_mark();
      if (cancelled_gc()) {
        op_degenerated_fail();
        return;
      }

      op_cleanup();

      // If heuristics thinks we should do the cycle, this flag would be set,
      // and we can do evacuation. Otherwise, it would be the shortcut cycle.
      if (is_evacuation_in_progress()) {
        op_evac();
        if (cancelled_gc()) {
          op_degenerated_fail();
          return;
        }
      }

      // If heuristics thinks we should do the cycle, this flag would be set,
      // and we need to do update-refs. Otherwise, it would be the shortcut cycle.
      if (has_forwarded_objects()) {
        op_init_updaterefs();
        if (cancelled_gc()) {
          op_degenerated_fail();
          return;
        }
      }

    case _degenerated_updaterefs:
      if (has_forwarded_objects()) {
        op_final_updaterefs();
        if (cancelled_gc()) {
          op_degenerated_fail();
          return;
        }
      }

      op_cleanup_bitmaps();
      break;

    default:
      ShouldNotReachHere();
  }

  if (ShenandoahVerify) {
    verifier()->verify_after_degenerated();
  }

  metrics.snap_after();
  metrics.print();

  // Check for futility and fail. There is no reason to do several back-to-back Degenerated cycles,
  // because that probably means the heap is overloaded and/or fragmented.
  if (!metrics.is_good_progress("Degenerated GC")) {
    _progress_last_gc.unset();
    cancel_gc(GCCause::_shenandoah_upgrade_to_full_gc);
    op_degenerated_futile();
  } else {
    _progress_last_gc.set();
  }
}

void ShenandoahHeap::op_degenerated_fail() {
  log_info(gc)("Cannot finish degeneration, upgrading to Full GC");
  shenandoahPolicy()->record_degenerated_upgrade_to_full();
  op_full(GCCause::_shenandoah_upgrade_to_full_gc);
}

void ShenandoahHeap::op_degenerated_futile() {
  shenandoahPolicy()->record_degenerated_upgrade_to_full();
  op_full(GCCause::_shenandoah_upgrade_to_full_gc);
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
  if (!cancelled_gc()) {
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

void ShenandoahHeap::force_satb_flush_all_threads() {
  if (!is_concurrent_mark_in_progress() && !is_concurrent_traversal_in_progress()) {
    // No need to flush SATBs
    return;
  }

  for (JavaThreadIteratorWithHandle jtiwh; JavaThread *t = jtiwh.next(); ) {
    ShenandoahThreadLocalData::set_force_satb_flush(t, true);
  }
  // The threads are not "acquiring" their thread-local data, but it does not
  // hurt to "release" the updates here anyway.
  OrderAccess::fence();
}

void ShenandoahHeap::set_gc_state_all_threads(char state) {
  for (JavaThreadIteratorWithHandle jtiwh; JavaThread *t = jtiwh.next(); ) {
    ShenandoahThreadLocalData::set_gc_state(t, state);
  }
}

void ShenandoahHeap::set_gc_state_mask(uint mask, bool value) {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Should really be Shenandoah safepoint");
  _gc_state.set_cond(mask, value);
  set_gc_state_all_threads(_gc_state.raw_value());
}

void ShenandoahHeap::set_concurrent_mark_in_progress(bool in_progress) {
  set_gc_state_mask(MARKING, in_progress);
  ShenandoahBarrierSet::satb_mark_queue_set().set_active_all_threads(in_progress, !in_progress);
}

void ShenandoahHeap::set_concurrent_traversal_in_progress(bool in_progress) {
   set_gc_state_mask(TRAVERSAL | HAS_FORWARDED, in_progress);
   ShenandoahBarrierSet::satb_mark_queue_set().set_active_all_threads(in_progress, !in_progress);
}

void ShenandoahHeap::set_evacuation_in_progress(bool in_progress) {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Only call this at safepoint");
  set_gc_state_mask(EVACUATION, in_progress);
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

ShenandoahIsAliveClosure::ShenandoahIsAliveClosure() :
  _heap(ShenandoahHeap::heap_no_check()) {
}

bool ShenandoahForwardedIsAliveClosure::do_object_b(oop obj) {
  if (CompressedOops::is_null(obj)) {
    return false;
  }
  obj = ShenandoahBarrierSet::resolve_forwarded_not_null(obj);
  shenandoah_assert_not_forwarded_if(NULL, obj, _heap->is_concurrent_mark_in_progress() || _heap->is_concurrent_traversal_in_progress())
  return _heap->is_marked_next(obj);
}

bool ShenandoahIsAliveClosure::do_object_b(oop obj) {
  if (CompressedOops::is_null(obj)) {
    return false;
  }
  shenandoah_assert_not_forwarded(NULL, obj);
  return _heap->is_marked_next(obj);
}

BoolObjectClosure* ShenandoahHeap::is_alive_closure() {
  return has_forwarded_objects() ?
         (BoolObjectClosure*) &_forwarded_is_alive :
         (BoolObjectClosure*) &_is_alive;
}

void ShenandoahHeap::ref_processing_init() {
  MemRegion mr = reserved_region();

  _forwarded_is_alive.init(this);
  _is_alive.init(this);
  assert(_max_workers > 0, "Sanity");

  _ref_processor =
    new ReferenceProcessor(&_subject_to_discovery,  // is_subject_to_discovery
                           ParallelRefProcEnabled,  // MT processing
                           _max_workers,            // Degree of MT processing
                           true,                    // MT discovery
                           _max_workers,            // Degree of MT discovery
                           false,                   // Reference discovery is not atomic
                           NULL);                   // No closure, should be installed before use

  shenandoah_assert_rp_isalive_not_installed();
}


GCTracer* ShenandoahHeap::tracer() {
  return shenandoahPolicy()->tracer();
}

size_t ShenandoahHeap::tlab_used(Thread* thread) const {
  return _free_set->used();
}

void ShenandoahHeap::cancel_gc(GCCause::Cause cause) {
  if (try_cancel_gc()) {
    FormatBuffer<> msg("Cancelling GC: %s", GCCause::to_string(cause));
    log_info(gc)("%s", msg.buffer());
    Events::log(Thread::current(), "%s", msg.buffer());
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
  control_thread()->prepare_for_graceful_shutdown();

  // Step 2. Notify GC workers that we are cancelling GC.
  cancel_gc(GCCause::_shenandoah_stop_vm);

  // Step 3. Wait until GC worker exits normally.
  control_thread()->stop();

  // Step 4. Stop String Dedup thread if it is active
  if (ShenandoahStringDedup::is_enabled()) {
    ShenandoahStringDedup::stop();
  }
}

void ShenandoahHeap::unload_classes_and_cleanup_tables(bool full_gc) {
  assert(ClassUnloading || full_gc, "Class unloading should be enabled");

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
    purged_class = SystemDictionary::do_unloading(gc_timer(),
                                                  full_gc /* do_cleaning*/ );
  }

  {
    ShenandoahGCPhase phase(phase_par);
    uint active = _workers->active_workers();
    ParallelCleaningTask unlink_task(is_alive, true, true, active, purged_class);
    _workers->run_task(&unlink_task);

    ShenandoahPhaseTimings* p = phase_timings();
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
    ShenandoahPhaseTimings::Phase phase_purge_dedup =
            full_gc ?
            ShenandoahPhaseTimings::full_gc_purge_string_dedup :
            ShenandoahPhaseTimings::purge_string_dedup;
    ShenandoahGCPhase phase(phase_purge_dedup);
    ShenandoahStringDedup::parallel_cleanup();
  }

  {
    ShenandoahGCPhase phase(phase_cldg);
    ClassLoaderDataGraph::purge();
  }
}

void ShenandoahHeap::set_has_forwarded_objects(bool cond) {
  set_gc_state_mask(HAS_FORWARDED, cond);
}

bool ShenandoahHeap::last_gc_made_progress() const {
  return _progress_last_gc.is_set();
}

void ShenandoahHeap::set_process_references(bool pr) {
  _process_references.set_cond(pr);
}

void ShenandoahHeap::set_unload_classes(bool uc) {
  _unload_classes.set_cond(uc);
}

bool ShenandoahHeap::process_references() const {
  return _process_references.is_set();
}

bool ShenandoahHeap::unload_classes() const {
  return _unload_classes.is_set();
}

//fixme this should be in heapregionset
ShenandoahHeapRegion* ShenandoahHeap::next_compaction_region(const ShenandoahHeapRegion* r) {
  size_t region_idx = r->region_number() + 1;
  ShenandoahHeapRegion* next = get_region(region_idx);
  guarantee(next->region_number() == region_idx, "region number must match");
  while (next->is_humongous()) {
    region_idx = next->region_number() + 1;
    next = get_region(region_idx);
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

address ShenandoahHeap::in_cset_fast_test_addr() {
  ShenandoahHeap* heap = ShenandoahHeap::heap();
  assert(heap->collection_set() != NULL, "Sanity");
  return (address) heap->collection_set()->biased_map_address();
}

address ShenandoahHeap::cancelled_gc_addr() {
  return (address) ShenandoahHeap::heap()->_cancelled_gc.addr_of();
}

address ShenandoahHeap::gc_state_addr() {
  return (address) ShenandoahHeap::heap()->_gc_state.addr_of();
}

size_t ShenandoahHeap::bytes_allocated_since_gc_start() {
  return OrderAccess::load_acquire(&_bytes_allocated_since_gc_start);
}

void ShenandoahHeap::reset_bytes_allocated_since_gc_start() {
  OrderAccess::release_store_fence(&_bytes_allocated_since_gc_start, (size_t)0);
}

ShenandoahPacer* ShenandoahHeap::pacer() const {
  assert (_pacer != NULL, "sanity");
  return _pacer;
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

void ShenandoahHeap::set_degenerated_gc_in_progress(bool in_progress) {
  _degenerated_gc_in_progress.set_cond(in_progress);
}

void ShenandoahHeap::set_full_gc_in_progress(bool in_progress) {
  _full_gc_in_progress.set_cond(in_progress);
}

void ShenandoahHeap::set_full_gc_move_in_progress(bool in_progress) {
  assert (is_full_gc_in_progress(), "should be");
  _full_gc_move_in_progress.set_cond(in_progress);
}

void ShenandoahHeap::set_update_refs_in_progress(bool in_progress) {
  set_gc_state_mask(UPDATEREFS, in_progress);
}

void ShenandoahHeap::register_nmethod(nmethod* nm) {
  ShenandoahCodeRoots::add_nmethod(nm);
}

void ShenandoahHeap::unregister_nmethod(nmethod* nm) {
  ShenandoahCodeRoots::remove_nmethod(nm);
}

oop ShenandoahHeap::pin_object(JavaThread* thr, oop o) {
  o = ShenandoahBarrierSet::barrier_set()->write_barrier(o);
  ShenandoahHeapLocker locker(lock());
  heap_region_containing(o)->make_pinned();
  return o;
}

void ShenandoahHeap::unpin_object(JavaThread* thr, oop o) {
  o = ShenandoahBarrierSet::barrier_set()->read_barrier(o);
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

ShenandoahConnectionMatrix* ShenandoahHeap::connection_matrix() const {
  return _connection_matrix;
}

ShenandoahTraversalGC* ShenandoahHeap::traversal_gc() {
  return _traversal_gc;
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
  ShenandoahRegionIterator* _regions;
  bool _concurrent;
public:
  ShenandoahUpdateHeapRefsTask(ShenandoahRegionIterator* regions, bool concurrent) :
    AbstractGangTask("Concurrent Update References Task"),
    cl(T()),
    _heap(ShenandoahHeap::heap()),
    _regions(regions),
    _concurrent(concurrent) {
  }

  void work(uint worker_id) {
    ShenandoahWorkerSession worker_session(worker_id);
    SuspendibleThreadSetJoiner stsj(_concurrent && ShenandoahSuspendibleWorkers);
    ShenandoahHeapRegion* r = _regions->next();
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
      if (ShenandoahPacing) {
        HeapWord* top_at_start_ur = r->concurrent_iteration_safe_limit();
        assert (top_at_start_ur >= r->bottom(), "sanity");
        _heap->pacer()->report_updaterefs(pointer_delta(top_at_start_ur, r->bottom()));
      }
      if (_heap->check_cancelled_gc_and_yield(_concurrent)) {
        return;
      }
      r = _regions->next();
    }
  }
};

void ShenandoahHeap::update_heap_references(bool concurrent) {
  if (UseShenandoahMatrix) {
    ShenandoahUpdateHeapRefsTask<ShenandoahUpdateHeapRefsMatrixClosure> task(&_update_refs_iterator, concurrent);
    workers()->run_task(&task);
  } else {
    ShenandoahUpdateHeapRefsTask<ShenandoahUpdateHeapRefsClosure> task(&_update_refs_iterator, concurrent);
    workers()->run_task(&task);
  }
}

void ShenandoahHeap::op_init_updaterefs() {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "must be at safepoint");

  accumulate_statistics_all_gclabs();
  set_evacuation_in_progress(false);

  if (ShenandoahVerify) {
    verifier()->verify_before_updaterefs();
  }

  set_update_refs_in_progress(true);
  make_parsable(true);
  if (UseShenandoahMatrix) {
    connection_matrix()->clear_all();
  }
  for (uint i = 0; i < num_regions(); i++) {
    ShenandoahHeapRegion* r = get_region(i);
    r->set_concurrent_iteration_safe_limit(r->top());
  }

  // Reset iterator.
  _update_refs_iterator.reset();

  if (ShenandoahPacing) {
    pacer()->setup_for_updaterefs();
  }
}

void ShenandoahHeap::op_final_updaterefs() {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "must be at safepoint");

  // Check if there is left-over work, and finish it
  if (_update_refs_iterator.has_next()) {
    ShenandoahGCPhase final_work(ShenandoahPhaseTimings::final_update_refs_finish_work);

    // Finish updating references where we left off.
    clear_cancelled_gc();
    update_heap_references(false);
  }

  // Clear cancelled GC, if set. On cancellation path, the block before would handle
  // everything. On degenerated paths, cancelled gc would not be set anyway.
  if (cancelled_gc()) {
    clear_cancelled_gc();
  }
  assert(!cancelled_gc(), "Should have been done right before");

  concurrentMark()->update_roots(ShenandoahPhaseTimings::final_update_refs_roots);

  // Allocations might have happened before we STWed here, record peak:
  heuristics()->record_peak_occupancy();

  ShenandoahGCPhase final_update_refs(ShenandoahPhaseTimings::final_update_refs_recycle);

  trash_cset_regions();
  set_has_forwarded_objects(false);
  set_update_refs_in_progress(false);

  if (ShenandoahVerify) {
    verifier()->verify_after_updaterefs();
  }

  {
    ShenandoahHeapLocker locker(lock());
    _free_set->rebuild();
  }
}

void ShenandoahHeap::set_alloc_seq_gc_start() {
  // Take next number, the start seq number is inclusive
  _alloc_seq_at_last_gc_start = ShenandoahHeapRegion::seqnum_current_alloc() + 1;
}

void ShenandoahHeap::set_alloc_seq_gc_end() {
  // Take current number, the end seq number is also inclusive
  _alloc_seq_at_last_gc_end = ShenandoahHeapRegion::seqnum_current_alloc();
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
    if (get_region(g)->is_committed()) {
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

bool ShenandoahHeap::idle_bitmap_slice(ShenandoahHeapRegion *r) {
  assert_heaplock_owned_by_current_thread();
  assert(ShenandoahUncommitWithIdle, "Must be enabled");

  if (is_bitmap_slice_committed(r, true)) {
    // Some other region from the group is still committed, meaning the bitmap
    // slice is should stay committed, exit right away.
    return true;
  }

  // Idle the bitmap slice:
  size_t slice = r->region_number() / _bitmap_regions_per_slice;
  size_t off = _bitmap_bytes_per_slice * slice;
  size_t len = _bitmap_bytes_per_slice;
  if (!os::idle_memory((char*)_bitmap0_region.start() + off, len)) {
    return false;
  }
  if (!os::idle_memory((char*)_bitmap1_region.start() + off, len)) {
    return false;
  }
  return true;
}

void ShenandoahHeap::activate_bitmap_slice(ShenandoahHeapRegion* r) {
  assert_heaplock_owned_by_current_thread();
  assert(ShenandoahUncommitWithIdle, "Must be enabled");
  size_t slice = r->region_number() / _bitmap_regions_per_slice;
  size_t off = _bitmap_bytes_per_slice * slice;
  size_t len = _bitmap_bytes_per_slice;
  os::activate_memory((char*)_bitmap0_region.start() + off, len);
  os::activate_memory((char*)_bitmap1_region.start() + off, len);
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

  try_inject_alloc_failure();
  VM_ShenandoahInitMark op;
  VMThread::execute(&op); // jump to entry_init_mark() under safepoint
}

void ShenandoahHeap::vmop_entry_final_mark() {
  TraceCollectorStats tcs(monitoring_support()->stw_collection_counters());
  ShenandoahGCPhase total(ShenandoahPhaseTimings::total_pause_gross);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::final_mark_gross);

  try_inject_alloc_failure();
  VM_ShenandoahFinalMarkStartEvac op;
  VMThread::execute(&op); // jump to entry_final_mark under safepoint
}

void ShenandoahHeap::vmop_entry_final_evac() {
  TraceCollectorStats tcs(monitoring_support()->stw_collection_counters());
  ShenandoahGCPhase total(ShenandoahPhaseTimings::total_pause_gross);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::final_evac_gross);

  VM_ShenandoahFinalEvac op;
  VMThread::execute(&op); // jump to entry_final_evac under safepoint
}

void ShenandoahHeap::vmop_entry_init_updaterefs() {
  TraceCollectorStats tcs(monitoring_support()->stw_collection_counters());
  ShenandoahGCPhase total(ShenandoahPhaseTimings::total_pause_gross);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::init_update_refs_gross);

  try_inject_alloc_failure();
  VM_ShenandoahInitUpdateRefs op;
  VMThread::execute(&op);
}

void ShenandoahHeap::vmop_entry_final_updaterefs() {
  TraceCollectorStats tcs(monitoring_support()->stw_collection_counters());
  ShenandoahGCPhase total(ShenandoahPhaseTimings::total_pause_gross);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::final_update_refs_gross);

  try_inject_alloc_failure();
  VM_ShenandoahFinalUpdateRefs op;
  VMThread::execute(&op);
}

void ShenandoahHeap::vmop_entry_init_traversal() {
  TraceCollectorStats tcs(monitoring_support()->stw_collection_counters());
  ShenandoahGCPhase total(ShenandoahPhaseTimings::total_pause_gross);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::init_traversal_gc_gross);

  try_inject_alloc_failure();
  VM_ShenandoahInitTraversalGC op;
  VMThread::execute(&op);
}

void ShenandoahHeap::vmop_entry_final_traversal() {
  TraceCollectorStats tcs(monitoring_support()->stw_collection_counters());
  ShenandoahGCPhase total(ShenandoahPhaseTimings::total_pause_gross);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::final_traversal_gc_gross);

  try_inject_alloc_failure();
  VM_ShenandoahFinalTraversalGC op;
  VMThread::execute(&op);
}

void ShenandoahHeap::vmop_entry_full(GCCause::Cause cause) {
  TraceCollectorStats tcs(monitoring_support()->full_stw_collection_counters());
  ShenandoahGCPhase total(ShenandoahPhaseTimings::total_pause_gross);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::full_gc_gross);

  try_inject_alloc_failure();
  VM_ShenandoahFullGC op(cause);
  VMThread::execute(&op);
}

void ShenandoahHeap::vmop_degenerated(ShenandoahDegenPoint point) {
  TraceCollectorStats tcs(monitoring_support()->full_stw_collection_counters());
  ShenandoahGCPhase total(ShenandoahPhaseTimings::total_pause_gross);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::degen_gc_gross);

  VM_ShenandoahDegeneratedGC degenerated_gc((int)point);
  VMThread::execute(&degenerated_gc);
}

void ShenandoahHeap::entry_init_mark() {
  ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::init_mark);

  FormatBuffer<> msg("Pause Init Mark%s%s%s",
                     has_forwarded_objects() ? " (update refs)"    : "",
                     process_references() ?    " (process refs)"   : "",
                     unload_classes() ?        " (unload classes)" : "");
  GCTraceTime(Info, gc) time(msg, gc_timer());
  EventMark em("%s", msg.buffer());

  ShenandoahWorkerScope scope(workers(),
                              ShenandoahWorkerPolicy::calc_workers_for_init_marking(),
                              "init marking");

  op_init_mark();
}

void ShenandoahHeap::entry_final_mark() {
  ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::final_mark);

  FormatBuffer<> msg("Pause Final Mark%s%s%s",
                     has_forwarded_objects() ? " (update refs)"    : "",
                     process_references() ?    " (process refs)"   : "",
                     unload_classes() ?        " (unload classes)" : "");
  GCTraceTime(Info, gc) time(msg, gc_timer());
  EventMark em("%s", msg.buffer());

  ShenandoahWorkerScope scope(workers(),
                              ShenandoahWorkerPolicy::calc_workers_for_final_marking(),
                              "final marking");

  op_final_mark();
}

void ShenandoahHeap::entry_final_evac() {
  ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::final_evac);

  FormatBuffer<> msg("Pause Final Evac");
  GCTraceTime(Info, gc) time(msg, gc_timer());
  EventMark em("%s", msg.buffer());

  op_final_evac();
}

void ShenandoahHeap::entry_init_updaterefs() {
  ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::init_update_refs);

  static const char* msg = "Pause Init Update Refs";
  GCTraceTime(Info, gc) time(msg, gc_timer());
  EventMark em("%s", msg);

  // No workers used in this phase, no setup required

  op_init_updaterefs();
}

void ShenandoahHeap::entry_final_updaterefs() {
  ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::final_update_refs);

  static const char* msg = "Pause Final Update Refs";
  GCTraceTime(Info, gc) time(msg, gc_timer());
  EventMark em("%s", msg);

  ShenandoahWorkerScope scope(workers(),
                              ShenandoahWorkerPolicy::calc_workers_for_final_update_ref(),
                              "final reference update");

  op_final_updaterefs();
}

void ShenandoahHeap::entry_init_traversal() {
  ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::init_traversal_gc);

  static const char* msg = "Pause Init Traversal";
  GCTraceTime(Info, gc) time(msg, gc_timer());
  EventMark em("%s", msg);

  ShenandoahWorkerScope scope(workers(),
                              ShenandoahWorkerPolicy::calc_workers_for_stw_traversal(),
                              "init traversal");

  op_init_traversal();
}

void ShenandoahHeap::entry_final_traversal() {
  ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::final_traversal_gc);

  static const char* msg = "Pause Final Traversal";
  GCTraceTime(Info, gc) time(msg, gc_timer());
  EventMark em("%s", msg);

  ShenandoahWorkerScope scope(workers(),
                              ShenandoahWorkerPolicy::calc_workers_for_stw_traversal(),
                              "final traversal");

  op_final_traversal();
}

void ShenandoahHeap::entry_full(GCCause::Cause cause) {
  ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::full_gc);

  static const char* msg = "Pause Full";
  GCTraceTime(Info, gc) time(msg, gc_timer(), cause, true);
  EventMark em("%s", msg);

  ShenandoahWorkerScope scope(workers(),
                              ShenandoahWorkerPolicy::calc_workers_for_fullgc(),
                              "full gc");

  op_full(cause);
}

void ShenandoahHeap::entry_degenerated(int point) {
  ShenandoahGCPhase total_phase(ShenandoahPhaseTimings::total_pause);
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::degen_gc);

  ShenandoahDegenPoint dpoint = (ShenandoahDegenPoint)point;
  FormatBuffer<> msg("Pause Degenerated GC (%s)", degen_point_to_string(dpoint));
  GCTraceTime(Info, gc) time(msg, gc_timer(), GCCause::_no_gc, true);
  EventMark em("%s", msg.buffer());

  ShenandoahWorkerScope scope(workers(),
                              ShenandoahWorkerPolicy::calc_workers_for_stw_degenerated(),
                              "stw degenerated gc");

  set_degenerated_gc_in_progress(true);
  op_degenerated(dpoint);
  set_degenerated_gc_in_progress(false);
}

void ShenandoahHeap::entry_mark() {
  TraceCollectorStats tcs(monitoring_support()->concurrent_collection_counters());

  FormatBuffer<> msg("Concurrent marking%s%s%s",
                     has_forwarded_objects() ? " (update refs)"    : "",
                     process_references() ?    " (process refs)"   : "",
                     unload_classes() ?        " (unload classes)" : "");
  GCTraceTime(Info, gc) time(msg, gc_timer(), GCCause::_no_gc, true);
  EventMark em("%s", msg.buffer());

  ShenandoahWorkerScope scope(workers(),
                              ShenandoahWorkerPolicy::calc_workers_for_conc_marking(),
                              "concurrent marking");

  try_inject_alloc_failure();
  op_mark();
}

void ShenandoahHeap::entry_evac() {
  ShenandoahGCPhase conc_evac_phase(ShenandoahPhaseTimings::conc_evac);
  TraceCollectorStats tcs(monitoring_support()->concurrent_collection_counters());

  static const char* msg = "Concurrent evacuation";
  GCTraceTime(Info, gc) time(msg, gc_timer(), GCCause::_no_gc, true);
  EventMark em("%s", msg);

  ShenandoahWorkerScope scope(workers(),
                              ShenandoahWorkerPolicy::calc_workers_for_conc_evac(),
                              "concurrent evacuation");

  try_inject_alloc_failure();
  op_evac();
}

void ShenandoahHeap::entry_updaterefs() {
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::conc_update_refs);

  static const char* msg = "Concurrent update references";
  GCTraceTime(Info, gc) time(msg, gc_timer(), GCCause::_no_gc, true);
  EventMark em("%s", msg);

  ShenandoahWorkerScope scope(workers(),
                              ShenandoahWorkerPolicy::calc_workers_for_conc_update_ref(),
                              "concurrent reference update");

  try_inject_alloc_failure();
  op_updaterefs();
}
void ShenandoahHeap::entry_cleanup() {
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::conc_cleanup);

  static const char* msg = "Concurrent cleanup";
  GCTraceTime(Info, gc) time(msg, gc_timer(), GCCause::_no_gc, true);
  EventMark em("%s", msg);

  // This phase does not use workers, no need for setup

  try_inject_alloc_failure();
  op_cleanup();
}

void ShenandoahHeap::entry_cleanup_traversal() {
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::conc_cleanup);

  static const char* msg = "Concurrent cleanup";
  GCTraceTime(Info, gc) time(msg, gc_timer(), GCCause::_no_gc, true);
  EventMark em("%s", msg);

  ShenandoahWorkerScope scope(workers(),
                              ShenandoahWorkerPolicy::calc_workers_for_conc_traversal(),
                              "concurrent traversal cleanup");

  try_inject_alloc_failure();
  op_cleanup_traversal();
}

void ShenandoahHeap::entry_cleanup_bitmaps() {
  ShenandoahGCPhase phase(ShenandoahPhaseTimings::conc_cleanup);

  static const char* msg = "Concurrent cleanup";
  GCTraceTime(Info, gc) time(msg, gc_timer(), GCCause::_no_gc, true);
  EventMark em("%s", msg);

  ShenandoahWorkerScope scope(workers(),
                              ShenandoahWorkerPolicy::calc_workers_for_conc_cleanup(),
                              "concurrent cleanup");

  try_inject_alloc_failure();
  op_cleanup_bitmaps();
}

void ShenandoahHeap::entry_preclean() {
  if (ShenandoahPreclean && process_references()) {
    static const char* msg = "Concurrent precleaning";
    GCTraceTime(Info, gc) time(msg, gc_timer(), GCCause::_no_gc, true);
    EventMark em("%s", msg);

    ShenandoahGCPhase conc_preclean(ShenandoahPhaseTimings::conc_preclean);

    ShenandoahWorkerScope scope(workers(),
                                ShenandoahWorkerPolicy::calc_workers_for_conc_preclean(),
                                "concurrent preclean");

    try_inject_alloc_failure();
    op_preclean();
  }
}

void ShenandoahHeap::entry_traversal() {
  static const char* msg = "Concurrent traversal";
  GCTraceTime(Info, gc) time(msg, gc_timer(), GCCause::_no_gc, true);
  EventMark em("%s", msg);

  TraceCollectorStats tcs(is_minor_gc() ? monitoring_support()->partial_collection_counters()
                                        : monitoring_support()->concurrent_collection_counters());

  ShenandoahWorkerScope scope(workers(),
                              ShenandoahWorkerPolicy::calc_workers_for_conc_traversal(),
                              "concurrent traversal");

  try_inject_alloc_failure();
  op_traversal();
}

void ShenandoahHeap::entry_uncommit(double shrink_before) {
  static const char *msg = "Concurrent uncommit";
  GCTraceTime(Info, gc) time(msg, gc_timer(), GCCause::_no_gc, true);
  EventMark em("%s", msg);

  ShenandoahGCPhase phase(ShenandoahPhaseTimings::conc_uncommit);

  op_uncommit(shrink_before);
}

void ShenandoahHeap::try_inject_alloc_failure() {
  if (ShenandoahAllocFailureALot && !cancelled_gc() && ((os::random() % 1000) > 950)) {
    _inject_alloc_failure.set();
    os::naked_short_sleep(1);
    if (cancelled_gc()) {
      log_info(gc)("Allocation failure was successfully injected");
    }
  }
}

bool ShenandoahHeap::should_inject_alloc_failure() {
  return _inject_alloc_failure.is_set() && _inject_alloc_failure.try_unset();
}

void ShenandoahHeap::initialize_serviceability() {
  _memory_pool = new ShenandoahMemoryPool(this);
  _cycle_memory_manager.add_pool(_memory_pool);
  _stw_memory_manager.add_pool(_memory_pool);
}

GrowableArray<GCMemoryManager*> ShenandoahHeap::memory_managers() {
  GrowableArray<GCMemoryManager*> memory_managers(2);
  memory_managers.append(&_cycle_memory_manager);
  memory_managers.append(&_stw_memory_manager);
  return memory_managers;
}

GrowableArray<MemoryPool*> ShenandoahHeap::memory_pools() {
  GrowableArray<MemoryPool*> memory_pools(1);
  memory_pools.append(_memory_pool);
  return memory_pools;
}

void ShenandoahHeap::enter_evacuation() {
  _oom_evac_handler.enter_evacuation();
}

void ShenandoahHeap::leave_evacuation() {
  _oom_evac_handler.leave_evacuation();
}

SoftRefPolicy* ShenandoahHeap::soft_ref_policy() {
  return &_soft_ref_policy;
}

ShenandoahRegionIterator::ShenandoahRegionIterator() :
  _index(0),
  _heap(ShenandoahHeap::heap()) {}

ShenandoahRegionIterator::ShenandoahRegionIterator(ShenandoahHeap* heap) :
  _index(0),
  _heap(heap) {}

void ShenandoahRegionIterator::reset() {
  _index = 0;
}

bool ShenandoahRegionIterator::has_next() const {
  return _index < _heap->num_regions();
}

void ShenandoahHeap::heap_region_iterate(ShenandoahHeapRegionClosure& cl) const {
  ShenandoahRegionIterator regions;
  ShenandoahHeapRegion* r = regions.next();
  while (r != NULL) {
    if (cl.heap_region_do(r)) {
      break;
    }
    r = regions.next();
  }
}

bool ShenandoahHeap::is_minor_gc() const {
  return _gc_cycle_mode.get() == MINOR;
}

bool ShenandoahHeap::is_major_gc() const {
  return _gc_cycle_mode.get() == MAJOR;
}

void ShenandoahHeap::set_cycle_mode(GCCycleMode gc_cycle_mode) {
  _gc_cycle_mode.set(gc_cycle_mode);
}

char ShenandoahHeap::gc_state() const {
  return _gc_state.raw_value();
}

void ShenandoahHeap::deduplicate_string(oop str) {
  assert(java_lang_String::is_instance(str), "invariant");

  if (ShenandoahStringDedup::is_enabled()) {
    ShenandoahStringDedup::deduplicate(str);
  }
}
