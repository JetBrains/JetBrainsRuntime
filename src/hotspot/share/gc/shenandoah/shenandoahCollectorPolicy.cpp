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
#include "gc/shared/gcPolicyCounters.hpp"
#include "gc/shenandoah/shenandoahCollectionSet.hpp"
#include "gc/shenandoah/shenandoahConnectionMatrix.hpp"
#include "gc/shenandoah/shenandoahFreeSet.hpp"
#include "gc/shenandoah/shenandoahCollectorPolicy.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahPartialGC.hpp"
#include "runtime/os.hpp"

class ShenandoahHeuristics : public CHeapObj<mtGC> {

  NumberSeq _allocation_rate_bytes;
  NumberSeq _reclamation_rate_bytes;

  size_t _bytes_allocated_since_CM;
  size_t _bytes_reclaimed_this_cycle;

protected:
  bool _update_refs_early;
  bool _update_refs_adaptive;

  typedef struct {
    ShenandoahHeapRegion* _region;
    size_t _garbage;
  } RegionData;

  static int compare_by_garbage(RegionData a, RegionData b) {
    if (a._garbage > b._garbage)
      return -1;
    else if (a._garbage < b._garbage)
      return 1;
    else return 0;
  }

  static int compare_by_alloc_seq_ascending(ShenandoahHeapRegion* a, ShenandoahHeapRegion* b) {
    if (a->last_alloc_seq_num() == b->last_alloc_seq_num())
      return 0;
    else if (a->last_alloc_seq_num() < b->last_alloc_seq_num())
      return -1;
    else return 1;
  }

  static int compare_by_alloc_seq_descending(ShenandoahHeapRegion* a, ShenandoahHeapRegion* b) {
    return -compare_by_alloc_seq_ascending(a, b);
  }

  RegionData* get_region_data_cache(size_t num) {
    RegionData* res = _region_data;
    if (res == NULL) {
      res = NEW_C_HEAP_ARRAY(RegionData, num, mtGC);
      _region_data = res;
      _region_data_size = num;
    } else if (_region_data_size < num) {
      res = REALLOC_C_HEAP_ARRAY(RegionData, _region_data, num, mtGC);
      _region_data = res;
      _region_data_size = num;
    }
    return res;
  }

  RegionData* _region_data;
  size_t _region_data_size;

  typedef struct {
    ShenandoahHeapRegion* _region;
    size_t _connections;
  } RegionConnections;

  RegionConnections* get_region_connects_cache(size_t num) {
    RegionConnections* res = _region_connects;
    if (res == NULL) {
      res = NEW_C_HEAP_ARRAY(RegionConnections, num, mtGC);
      _region_connects = res;
      _region_connects_size = num;
    } else if (_region_connects_size < num) {
      res = REALLOC_C_HEAP_ARRAY(RegionConnections, _region_connects, num, mtGC);
      _region_connects = res;
      _region_connects_size = num;
    }
    return res;
  }

  static int compare_by_connects(RegionConnections a, RegionConnections b) {
    if (a._connections == b._connections)
      return 0;
    else if (a._connections < b._connections)
      return -1;
    else return 1;
  }

  RegionConnections* _region_connects;
  size_t _region_connects_size;

  size_t _bytes_allocated_start_CM;
  size_t _bytes_allocated_during_CM;

  uint _degenerated_cycles_in_a_row;
  uint _successful_cycles_in_a_row;

  size_t _bytes_in_cset;

  double _last_cycle_end;

public:

  ShenandoahHeuristics();
  virtual ~ShenandoahHeuristics();

  void record_bytes_allocated(size_t bytes);
  void record_bytes_reclaimed(size_t bytes);
  void record_bytes_start_CM(size_t bytes);
  void record_bytes_end_CM(size_t bytes);

  void record_gc_start() {
    ShenandoahHeap::heap()->set_alloc_seq_gc_start();
  }

  void record_gc_end() {
    ShenandoahHeap::heap()->set_alloc_seq_gc_end();
    ShenandoahHeap::heap()->set_used_at_last_gc();
  }

  virtual void record_cycle_start() {
    // Do nothing
  }

  virtual void record_cycle_end() {
    _last_cycle_end = os::elapsedTime();
  }

  virtual void record_phase_time(ShenandoahPhaseTimings::Phase phase, double secs) {
    // Do nothing
  }

  virtual void print_thresholds() {
  }

  virtual bool should_start_concurrent_mark(size_t used, size_t capacity) const=0;

  virtual bool should_start_update_refs() {
    return _update_refs_early;
  }

  virtual bool update_refs() const {
    return _update_refs_early;
  }

  virtual bool should_start_partial_gc() {
    return false;
  }

  virtual bool can_do_partial_gc() {
    return false;
  }

  virtual bool should_degenerate_cycle() {
    return _degenerated_cycles_in_a_row <= ShenandoahFullGCThreshold;
  }

  virtual void record_success_concurrent() {
    _degenerated_cycles_in_a_row = 0;
    _successful_cycles_in_a_row++;
  }

  virtual void record_success_degenerated() {
    _degenerated_cycles_in_a_row++;
    _successful_cycles_in_a_row = 0;
  }

  virtual void record_success_full() {
    _degenerated_cycles_in_a_row = 0;
    _successful_cycles_in_a_row++;
  }

  virtual void record_allocation_failure_gc() {
    _bytes_in_cset = 0;
  }

  virtual void record_explicit_gc() {
    _bytes_in_cset = 0;
  }

  virtual void record_peak_occupancy() {
  }

  virtual void choose_collection_set(ShenandoahCollectionSet* collection_set);
  virtual void choose_free_set(ShenandoahFreeSet* free_set);

  virtual bool process_references() {
    if (ShenandoahRefProcFrequency == 0) return false;
    size_t cycle = ShenandoahHeap::heap()->shenandoahPolicy()->cycle_counter();
    // Process references every Nth GC cycle.
    return cycle % ShenandoahRefProcFrequency == 0;
  }

  virtual bool unload_classes() {
    if (ShenandoahUnloadClassesFrequency == 0) return false;
    size_t cycle = ShenandoahHeap::heap()->shenandoahPolicy()->cycle_counter();
    // Unload classes every Nth GC cycle.
    // This should not happen in the same cycle as process_references to amortize costs.
    // Offsetting by one is enough to break the rendezvous when periods are equal.
    // When periods are not equal, offsetting by one is just as good as any other guess.
    return (cycle + 1) % ShenandoahUnloadClassesFrequency == 0;
  }

  bool maybe_add_heap_region(ShenandoahHeapRegion* hr,
                        ShenandoahCollectionSet* cs);

  virtual const char* name() = 0;
  virtual bool is_diagnostic() = 0;
  virtual bool is_experimental() = 0;
  virtual void initialize() {}

protected:
  virtual void choose_collection_set_from_regiondata(ShenandoahCollectionSet* set,
                                                     RegionData* data, size_t data_size,
                                                     size_t trash, size_t free) = 0;
};

ShenandoahHeuristics::ShenandoahHeuristics() :
  _bytes_allocated_since_CM(0),
  _bytes_reclaimed_this_cycle(0),
  _bytes_allocated_start_CM(0),
  _bytes_allocated_during_CM(0),
  _bytes_in_cset(0),
  _degenerated_cycles_in_a_row(0),
  _successful_cycles_in_a_row(0),
  _region_data(NULL),
  _region_data_size(0),
  _region_connects(NULL),
  _region_connects_size(0),
  _update_refs_early(false),
  _update_refs_adaptive(false),
  _last_cycle_end(0)
{
  if (strcmp(ShenandoahUpdateRefsEarly, "on") == 0 ||
      strcmp(ShenandoahUpdateRefsEarly, "true") == 0 ) {
    _update_refs_early = true;
  } else if (strcmp(ShenandoahUpdateRefsEarly, "off") == 0 ||
             strcmp(ShenandoahUpdateRefsEarly, "false") == 0 ) {
    _update_refs_early = false;
  } else if (strcmp(ShenandoahUpdateRefsEarly, "adaptive") == 0) {
    _update_refs_adaptive = true;
    _update_refs_early = true;
  } else {
    vm_exit_during_initialization("Unknown -XX:ShenandoahUpdateRefsEarly option: %s", ShenandoahUpdateRefsEarly);
  }
}

ShenandoahHeuristics::~ShenandoahHeuristics() {
  if (_region_data != NULL) {
    FREE_C_HEAP_ARRAY(RegionGarbage, _region_data);
  }
  if (_region_connects != NULL) {
    FREE_C_HEAP_ARRAY(RegionConnections, _region_connects);
  }
}

bool ShenandoahHeuristics::maybe_add_heap_region(ShenandoahHeapRegion* hr,
                                                 ShenandoahCollectionSet* collection_set) {
    if (hr->is_regular() && hr->has_live() && !collection_set->is_in(hr)) {
      collection_set->add_region(hr);
      return true;
    }
    return false;
}

void ShenandoahHeuristics::choose_collection_set(ShenandoahCollectionSet* collection_set) {
  assert(collection_set->count() == 0, "Must be empty");

  ShenandoahHeap* heap = ShenandoahHeap::heap();

  // Poll this before populating collection set.
  size_t total_garbage = heap->garbage();

  // Step 1. Build up the region candidates we care about, rejecting losers and accepting winners right away.

  ShenandoahHeapRegionSet* regions = heap->regions();
  size_t active = regions->active_regions();

  RegionData* candidates = get_region_data_cache(active);

  size_t cand_idx = 0;

  size_t immediate_garbage = 0;
  size_t immediate_regions = 0;

  size_t free = 0;
  size_t free_regions = 0;

  for (size_t i = 0; i < active; i++) {
    ShenandoahHeapRegion* region = regions->get(i);

    if (region->is_empty()) {
      free_regions++;
      free += ShenandoahHeapRegion::region_size_bytes();
    } else if (region->is_regular()) {
      if (!region->has_live()) {
        // We can recycle it right away and put it in the free set.
        immediate_regions++;
        immediate_garbage += region->garbage();
        region->make_trash();
      } else {
        // This is our candidate for later consideration.
        candidates[cand_idx]._region = region;
        candidates[cand_idx]._garbage = region->garbage();
        cand_idx++;
      }
    } else if (region->is_humongous_start()) {
        // Reclaim humongous regions here, and count them as the immediate garbage
#ifdef ASSERT
        bool reg_live = region->has_live();
        bool bm_live = heap->is_marked_complete(oop(region->bottom() + BrooksPointer::word_size()));
        assert(reg_live == bm_live,
               "Humongous liveness and marks should agree. Region live: %s; Bitmap live: %s; Region Live Words: " SIZE_FORMAT,
               BOOL_TO_STR(reg_live), BOOL_TO_STR(bm_live), region->get_live_data_words());
#endif
        if (!region->has_live()) {
          size_t reclaimed = heap->trash_humongous_region_at(region);
          immediate_regions += reclaimed;
          immediate_garbage += reclaimed * ShenandoahHeapRegion::region_size_bytes();
        }
    } else if (region->is_trash()) {
      // Count in just trashed collection set, during coalesced CM-with-UR
      immediate_regions++;
      immediate_garbage += ShenandoahHeapRegion::region_size_bytes();
    }
  }

  // Step 2. Process the remaining candidates, if any.

  if (cand_idx > 0) {
    choose_collection_set_from_regiondata(collection_set, candidates, cand_idx, immediate_garbage, free);
  }

  // Step 3. Look back at collection set, and see if it's worth it to collect,
  // given the amount of immediately reclaimable garbage.

  log_info(gc, ergo)("Total Garbage: "SIZE_FORMAT"M",
                     total_garbage / M);

  size_t total_garbage_regions = immediate_regions + collection_set->count();
  size_t immediate_percent = total_garbage_regions == 0 ? 0 : (immediate_regions * 100 / total_garbage_regions);

  log_info(gc, ergo)("Immediate Garbage: "SIZE_FORMAT"M, "SIZE_FORMAT" regions ("SIZE_FORMAT"%% of total)",
                     immediate_garbage / M, immediate_regions, immediate_percent);

  if (immediate_percent > ShenandoahImmediateThreshold) {
    collection_set->clear();
  } else {
    log_info(gc, ergo)("Garbage to be collected: "SIZE_FORMAT"M ("SIZE_FORMAT"%% of total), "SIZE_FORMAT" regions",
                       collection_set->garbage() / M, collection_set->garbage() * 100 / MAX2(total_garbage, (size_t)1), collection_set->count());
    log_info(gc, ergo)("Live objects to be evacuated: "SIZE_FORMAT"M",
                       collection_set->live_data() / M);
    log_info(gc, ergo)("Live/garbage ratio in collected regions: "SIZE_FORMAT"%%",
                       collection_set->live_data() * 100 / MAX2(collection_set->garbage(), (size_t)1));
    log_info(gc, ergo)("Free: "SIZE_FORMAT"M, "SIZE_FORMAT" regions ("SIZE_FORMAT"%% of total)",
                       free / M, free_regions, free_regions * 100 / active);
  }

  collection_set->update_region_status();
}

void ShenandoahHeuristics::choose_free_set(ShenandoahFreeSet* free_set) {
  ShenandoahHeapRegionSet* ordered_regions = ShenandoahHeap::heap()->regions();
  for (size_t i = 0; i < ordered_regions->active_regions(); i++) {
    ShenandoahHeapRegion* region = ordered_regions->get(i);
    if (region->is_alloc_allowed()) {
      free_set->add_region(region);
    }
  }
}


void ShenandoahCollectorPolicy::record_gc_start() {
  _heuristics->record_gc_start();
}

void ShenandoahCollectorPolicy::record_gc_end() {
  _heuristics->record_gc_end();
}

void ShenandoahHeuristics::record_bytes_allocated(size_t bytes) {
  _bytes_allocated_since_CM = bytes;
  _bytes_allocated_start_CM = bytes;
  _allocation_rate_bytes.add(bytes);
}

void ShenandoahHeuristics::record_bytes_reclaimed(size_t bytes) {
  _bytes_reclaimed_this_cycle = bytes;
  _reclamation_rate_bytes.add(bytes);
}

void ShenandoahHeuristics::record_bytes_start_CM(size_t bytes) {
  _bytes_allocated_start_CM = bytes;
}

void ShenandoahHeuristics::record_bytes_end_CM(size_t bytes) {
  _bytes_allocated_during_CM = (bytes > _bytes_allocated_start_CM) ? (bytes - _bytes_allocated_start_CM)
                                                                   : bytes;
}

#define SHENANDOAH_PASSIVE_OVERRIDE_FLAG(name)                              \
  do {                                                                      \
    if (FLAG_IS_DEFAULT(name) && (name)) {                                  \
      log_info(gc)("Passive heuristics implies -XX:-" #name " by default"); \
      FLAG_SET_DEFAULT(name, false);                                        \
    }                                                                       \
  } while (0)

class ShenandoahPassiveHeuristics : public ShenandoahHeuristics {
public:
  ShenandoahPassiveHeuristics() : ShenandoahHeuristics() {
    // Do not allow concurrent cycles.
    FLAG_SET_DEFAULT(ExplicitGCInvokesConcurrent, false);

    // Disable known barriers by default.
    SHENANDOAH_PASSIVE_OVERRIDE_FLAG(ShenandoahSATBBarrier);
    SHENANDOAH_PASSIVE_OVERRIDE_FLAG(ShenandoahConditionalSATBBarrier);
    SHENANDOAH_PASSIVE_OVERRIDE_FLAG(ShenandoahKeepAliveBarrier);
    SHENANDOAH_PASSIVE_OVERRIDE_FLAG(ShenandoahWriteBarrier);
    SHENANDOAH_PASSIVE_OVERRIDE_FLAG(ShenandoahReadBarrier);
    SHENANDOAH_PASSIVE_OVERRIDE_FLAG(ShenandoahStoreValWriteBarrier);
    SHENANDOAH_PASSIVE_OVERRIDE_FLAG(ShenandoahStoreValReadBarrier);
    SHENANDOAH_PASSIVE_OVERRIDE_FLAG(ShenandoahCASBarrier);
    SHENANDOAH_PASSIVE_OVERRIDE_FLAG(ShenandoahAcmpBarrier);
    SHENANDOAH_PASSIVE_OVERRIDE_FLAG(ShenandoahCloneBarrier);
    SHENANDOAH_PASSIVE_OVERRIDE_FLAG(UseShenandoahMatrix);
  }

  virtual void choose_collection_set_from_regiondata(ShenandoahCollectionSet* cset,
                                                     RegionData* data, size_t size,
                                                     size_t trash, size_t free) {
    for (size_t idx = 0; idx < size; idx++) {
      ShenandoahHeapRegion* r = data[idx]._region;
      if (r->garbage() > 0) {
        cset->add_region(r);
      }
    }
  }

  virtual bool should_start_concurrent_mark(size_t used, size_t capacity) const {
    // Never do concurrent GCs.
    return false;
  }

  virtual bool process_references() {
    if (ShenandoahRefProcFrequency == 0) return false;
    // Always process references.
    return true;
  }

  virtual bool unload_classes() {
    if (ShenandoahUnloadClassesFrequency == 0) return false;
    // Always unload classes.
    return true;
  }

  virtual bool should_degenerate_cycle() {
    // Always fail to Full GC
    return false;
  }

  virtual const char* name() {
    return "passive";
  }

  virtual bool is_diagnostic() {
    return true;
  }

  virtual bool is_experimental() {
    return false;
  }
};

class ShenandoahAggressiveHeuristics : public ShenandoahHeuristics {
public:
  ShenandoahAggressiveHeuristics() : ShenandoahHeuristics() {
    // Do not shortcut evacuation
    if (FLAG_IS_DEFAULT(ShenandoahImmediateThreshold)) {
      FLAG_SET_DEFAULT(ShenandoahImmediateThreshold, 100);
    }
  }

  virtual void choose_collection_set_from_regiondata(ShenandoahCollectionSet* cset,
                                                     RegionData* data, size_t size,
                                                     size_t trash, size_t free) {
    for (size_t idx = 0; idx < size; idx++) {
      ShenandoahHeapRegion* r = data[idx]._region;
      if (r->garbage() > 0) {
        cset->add_region(r);
      }
    }
  }

  virtual bool should_start_concurrent_mark(size_t used, size_t capacity) const {
    return true;
  }

  virtual bool process_references() {
    if (ShenandoahRefProcFrequency == 0) return false;
    // Randomly process refs with 50% chance.
    return (os::random() & 1) == 1;
  }

  virtual bool unload_classes() {
    if (ShenandoahUnloadClassesFrequency == 0) return false;
    // Randomly unload classes with 50% chance.
    return (os::random() & 1) == 1;
  }

  virtual const char* name() {
    return "aggressive";
  }

  virtual bool is_diagnostic() {
    return true;
  }

  virtual bool is_experimental() {
    return false;
  }
};

class ShenandoahStaticHeuristics : public ShenandoahHeuristics {
public:
  ShenandoahStaticHeuristics() : ShenandoahHeuristics() {
    // Static heuristics may degrade to continuous if live data is larger
    // than free threshold. ShenandoahAllocationThreshold is supposed to break this,
    // but it only works if it is non-zero.
    if (FLAG_IS_DEFAULT(ShenandoahAllocationThreshold) && (ShenandoahAllocationThreshold == 0)) {
      FLAG_SET_DEFAULT(ShenandoahAllocationThreshold, 1);
    }
  }

  void print_thresholds() {
    log_info(gc, init)("Shenandoah heuristics thresholds: allocation "SIZE_FORMAT", free "SIZE_FORMAT", garbage "SIZE_FORMAT,
                       ShenandoahAllocationThreshold,
                       ShenandoahFreeThreshold,
                       ShenandoahGarbageThreshold);
  }

  virtual ~ShenandoahStaticHeuristics() {}

  virtual bool should_start_concurrent_mark(size_t used, size_t capacity) const {
    ShenandoahHeap* heap = ShenandoahHeap::heap();

    size_t available = heap->free_regions()->available();
    size_t threshold_available = (capacity * ShenandoahFreeThreshold) / 100;
    size_t threshold_bytes_allocated = heap->capacity() * ShenandoahAllocationThreshold / 100;
    size_t bytes_allocated = heap->bytes_allocated_since_cm();

    double last_time_ms = (os::elapsedTime() - _last_cycle_end) * 1000;
    bool periodic_gc = (last_time_ms > ShenandoahGuaranteedGCInterval);

    if (available < threshold_available &&
            bytes_allocated > threshold_bytes_allocated) {
      // Need to check that an appropriate number of regions have
      // been allocated since last concurrent mark too.
      log_info(gc,ergo)("Concurrent marking triggered. Free: " SIZE_FORMAT "M, Free Threshold: " SIZE_FORMAT
                                "M; Allocated: " SIZE_FORMAT "M, Alloc Threshold: " SIZE_FORMAT "M",
                        available / M, threshold_available / M, bytes_allocated / M, threshold_bytes_allocated / M);
      return true;
    } else if (periodic_gc) {
      log_info(gc,ergo)("Periodic GC triggered. Time since last GC: %.0f ms, Guaranteed Interval: " UINTX_FORMAT " ms",
                        last_time_ms, ShenandoahGuaranteedGCInterval);
      return true;
    }

    return false;
  }

  virtual void choose_collection_set_from_regiondata(ShenandoahCollectionSet* cset,
                                                     RegionData* data, size_t size,
                                                     size_t trash, size_t free) {
    size_t threshold = ShenandoahHeapRegion::region_size_bytes() * ShenandoahGarbageThreshold / 100;

    for (size_t idx = 0; idx < size; idx++) {
      ShenandoahHeapRegion* r = data[idx]._region;
      if (r->garbage() > threshold) {
        cset->add_region(r);
      }
    }
  }

  virtual const char* name() {
    return "dynamic";
  }

  virtual bool is_diagnostic() {
    return false;
  }

  virtual bool is_experimental() {
    return false;
  }
};

class ShenandoahContinuousHeuristics : public ShenandoahHeuristics {
public:
  virtual bool should_start_concurrent_mark(size_t used, size_t capacity) const {
    // Start the cycle, unless completely idle.
    return ShenandoahHeap::heap()->bytes_allocated_since_cm() > 0;
  }

  virtual void choose_collection_set_from_regiondata(ShenandoahCollectionSet* cset,
                                                     RegionData* data, size_t size,
                                                     size_t trash, size_t free) {
    size_t threshold = ShenandoahHeapRegion::region_size_bytes() * ShenandoahGarbageThreshold / 100;
    for (size_t idx = 0; idx < size; idx++) {
      ShenandoahHeapRegion* r = data[idx]._region;
      if (r->garbage() > threshold) {
        cset->add_region(r);
      }
    }
  }

  virtual const char* name() {
    return "continuous";
  }

  virtual bool is_diagnostic() {
    return false;
  }

  virtual bool is_experimental() {
    return false;
  }
};

class ShenandoahAdaptiveHeuristics : public ShenandoahHeuristics {
private:
  uintx _free_threshold;
  TruncatedSeq* _cset_history;
  size_t _peak_occupancy;
  TruncatedSeq* _cycle_gap_history;
  TruncatedSeq* _conc_mark_duration_history;
  TruncatedSeq* _conc_uprefs_duration_history;
public:
  ShenandoahAdaptiveHeuristics() :
    ShenandoahHeuristics(),
    _free_threshold(ShenandoahInitFreeThreshold),
    _peak_occupancy(0),
    _conc_mark_duration_history(new TruncatedSeq(5)),
    _conc_uprefs_duration_history(new TruncatedSeq(5)),
    _cycle_gap_history(new TruncatedSeq(5)),
    _cset_history(new TruncatedSeq((uint)ShenandoahHappyCyclesThreshold)) {

    _cset_history->add((double) ShenandoahCSetThreshold);
    _cset_history->add((double) ShenandoahCSetThreshold);
  }

  virtual ~ShenandoahAdaptiveHeuristics() {
    delete _cset_history;
  }

  virtual void choose_collection_set_from_regiondata(ShenandoahCollectionSet* cset,
                                                     RegionData* data, size_t size,
                                                     size_t trash, size_t free) {
    size_t garbage_threshold = ShenandoahHeapRegion::region_size_bytes() * ShenandoahGarbageThreshold / 100;

    // The logic for cset selection in adaptive is as follows:
    //
    //   1. We cannot get cset larger than available free space. Otherwise we guarantee OOME
    //      during evacuation, and thus guarantee full GC. In practice, we also want to let
    //      application to allocate something. This is why we limit CSet to some fraction of
    //      available space. In non-overloaded heap, max_cset would contain all plausible candidates
    //      over garbage threshold.
    //
    //   2. We should not get cset too low so that free threshold would not be met right
    //      after the cycle. Otherwise we get back-to-back cycles for no reason if heap is
    //      too fragmented. In non-overloaded non-fragmented heap min_cset would be around zero.
    //
    // Therefore, we start by sorting the regions by garbage. Then we unconditionally add the best candidates
    // before we meet min_cset. Then we add all candidates that fit with a garbage threshold before
    // we hit max_cset. When max_cset is hit, we terminate the cset selection. Note that in this scheme,
    // ShenandoahGarbageThreshold is the soft threshold which would be ignored until min_cset is hit.

    size_t free_target = MIN2<size_t>(_free_threshold + MaxNormalStep, 100) * ShenandoahHeap::heap()->capacity() / 100;
    size_t actual_free = free + trash;
    size_t min_cset = free_target > actual_free ? (free_target - actual_free) : 0;
    size_t max_cset = actual_free * 3 / 4;
    min_cset = MIN2(min_cset, max_cset);

    log_info(gc, ergo)("Adaptive CSet selection: free target = " SIZE_FORMAT "M, actual free = "
                               SIZE_FORMAT "M; min cset = " SIZE_FORMAT "M, max cset = " SIZE_FORMAT "M",
                       free_target / M, actual_free / M, min_cset / M, max_cset / M);

    // Better select garbage-first regions
    QuickSort::sort<RegionData>(data, (int)size, compare_by_garbage, false);

    size_t live_cset = 0;
    _bytes_in_cset = 0;
    for (size_t idx = 0; idx < size; idx++) {
      ShenandoahHeapRegion* r = data[idx]._region;

      size_t new_cset = live_cset + r->get_live_data_bytes();

      if (new_cset < min_cset) {
        cset->add_region(r);
        _bytes_in_cset += r->used();
        live_cset = new_cset;
      } else if (new_cset <= max_cset) {
        if (r->garbage() > garbage_threshold) {
          cset->add_region(r);
          _bytes_in_cset += r->used();
          live_cset = new_cset;
        }
      } else {
        break;
      }
    }
  }

  static const intx MaxNormalStep = 5;      // max step towards goal under normal conditions
  static const intx DegeneratedGC_Hit = 10; // how much to step on degenerated GC
  static const intx AllocFailure_Hit = 20;  // how much to step on allocation failure full GC
  static const intx UserRequested_Hit = 0;  // how much to step on user requested full GC

  void handle_cycle_success() {
    ShenandoahHeap* heap = ShenandoahHeap::heap();
    size_t capacity = heap->capacity();

    size_t current_threshold = (capacity - _peak_occupancy) * 100 / capacity;
    size_t min_threshold = ShenandoahMinFreeThreshold;
    intx step = min_threshold - current_threshold;
    step = MAX2(step, (intx) -MaxNormalStep);
    step = MIN2(step, (intx) MaxNormalStep);

    log_info(gc, ergo)("Capacity: " SIZE_FORMAT "M, Peak Occupancy: " SIZE_FORMAT
                              "M, Lowest Free: " SIZE_FORMAT "M, Free Threshold: " UINTX_FORMAT "M",
                       capacity / M, _peak_occupancy / M,
                       (capacity - _peak_occupancy) / M, ShenandoahMinFreeThreshold * capacity / 100 / M);

    if (step > 0) {
      // Pessimize
      adjust_free_threshold(step);
    } else if (step < 0) {
      // Optimize, if enough happy cycles happened
      if (_successful_cycles_in_a_row > ShenandoahHappyCyclesThreshold &&
          _free_threshold > 0) {
        adjust_free_threshold(step);
        _successful_cycles_in_a_row = 0;
      }
    } else {
      // do nothing
    }
    _peak_occupancy = 0;
  }

  void record_cycle_start() {
    ShenandoahHeuristics::record_cycle_start();
    double last_cycle_gap = (os::elapsedTime() - _last_cycle_end);
    _cycle_gap_history->add(last_cycle_gap);
  }

  virtual void record_phase_time(ShenandoahPhaseTimings::Phase phase, double secs) {
    if (phase == ShenandoahPhaseTimings::conc_mark) {
      _conc_mark_duration_history->add(secs);
    } else if (phase == ShenandoahPhaseTimings::conc_update_refs) {
      _conc_uprefs_duration_history->add(secs);
    } // Else ignore
  }

  void adjust_free_threshold(intx adj) {
    intx new_value = adj + _free_threshold;
    uintx new_threshold = (uintx)MAX2<intx>(new_value, 0);
    new_threshold = MAX2(new_threshold, ShenandoahMinFreeThreshold);
    new_threshold = MIN2(new_threshold, ShenandoahMaxFreeThreshold);
    if (new_threshold != _free_threshold) {
      _free_threshold = new_threshold;
      log_info(gc,ergo)("Adjusting free threshold to: " UINTX_FORMAT "%% (" SIZE_FORMAT "M)",
                        _free_threshold, _free_threshold * ShenandoahHeap::heap()->capacity() / 100 / M);
    }
  }

  virtual void record_success_concurrent() {
    ShenandoahHeuristics::record_success_concurrent();
    handle_cycle_success();
  }

  virtual void record_success_degenerated() {
    ShenandoahHeuristics::record_success_degenerated();
    adjust_free_threshold(DegeneratedGC_Hit);
  }

  virtual void record_success_full() {
    ShenandoahHeuristics::record_success_full();
    adjust_free_threshold(AllocFailure_Hit);
  }

  virtual void record_explicit_gc() {
    ShenandoahHeuristics::record_explicit_gc();
    adjust_free_threshold(UserRequested_Hit);
  }

  virtual void record_peak_occupancy() {
    _peak_occupancy = MAX2(_peak_occupancy, ShenandoahHeap::heap()->used());
  }

  virtual bool should_start_concurrent_mark(size_t used, size_t capacity) const {
    if (! ShenandoahConcMarkGC) return false;
    bool shouldStartConcurrentMark = false;
    ShenandoahHeap* heap = ShenandoahHeap::heap();
    size_t available = heap->free_regions()->available();
    uintx factor = _free_threshold;
    size_t cset_threshold = 0;
    if (! update_refs()) {
      // Count in the memory available after cset reclamation.
      cset_threshold = (size_t) _cset_history->davg();
      size_t cset = MIN2(_bytes_in_cset, (cset_threshold * capacity) / 100);
      available += cset;
      factor += cset_threshold;
    }

    double last_time_ms = (os::elapsedTime() - _last_cycle_end) * 1000;
    bool periodic_gc = (last_time_ms > ShenandoahGuaranteedGCInterval);
    size_t threshold_available = (capacity * factor) / 100;
    size_t bytes_allocated = heap->bytes_allocated_since_cm();
    size_t threshold_bytes_allocated = heap->capacity() * ShenandoahAllocationThreshold / 100;

    if (available < threshold_available &&
            bytes_allocated > threshold_bytes_allocated) {
      log_info(gc,ergo)("Concurrent marking triggered. Free: " SIZE_FORMAT "M, Free Threshold: " SIZE_FORMAT
                                "M; Allocated: " SIZE_FORMAT "M, Alloc Threshold: " SIZE_FORMAT "M",
                        available / M, threshold_available / M, bytes_allocated / M, threshold_bytes_allocated / M);
      // Need to check that an appropriate number of regions have
      // been allocated since last concurrent mark too.
      shouldStartConcurrentMark = true;
    } else if (periodic_gc) {
      log_info(gc,ergo)("Periodic GC triggered. Time since last GC: %.0f ms, Guaranteed Interval: " UINTX_FORMAT " ms",
          last_time_ms, ShenandoahGuaranteedGCInterval);
      shouldStartConcurrentMark = true;
    }

    if (shouldStartConcurrentMark) {
      if (! update_refs()) {
        log_info(gc,ergo)("Predicted cset threshold: " SIZE_FORMAT ", " SIZE_FORMAT "K CSet ("SIZE_FORMAT"%%)",
                          cset_threshold, _bytes_in_cset / K, _bytes_in_cset * 100 / capacity);
        _cset_history->add((double) (_bytes_in_cset * 100 / capacity));
      }
    }
    return shouldStartConcurrentMark;
  }

  virtual bool should_start_update_refs() {
    if (! _update_refs_adaptive) {
      return _update_refs_early;
    }

    double cycle_gap_avg = _cycle_gap_history->avg();
    double conc_mark_avg = _conc_mark_duration_history->avg();
    double conc_uprefs_avg = _conc_uprefs_duration_history->avg();

    if (_update_refs_early) {
      double threshold = ShenandoahMergeUpdateRefsMinGap / 100.0;
      if (conc_mark_avg + conc_uprefs_avg > cycle_gap_avg * threshold) {
        _update_refs_early = false;
      }
    } else {
      double threshold = ShenandoahMergeUpdateRefsMaxGap / 100.0;
      if (conc_mark_avg + conc_uprefs_avg < cycle_gap_avg * threshold) {
        _update_refs_early = true;
      }
    }
    return _update_refs_early;
  }

  virtual const char* name() {
    return "adaptive";
  }

  virtual bool is_diagnostic() {
    return false;
  }

  virtual bool is_experimental() {
    return false;
  }
};

class ShenandoahPartialHeuristics : public ShenandoahAdaptiveHeuristics {
protected:
  size_t* _from_idxs;

public:
  ShenandoahPartialHeuristics() : ShenandoahAdaptiveHeuristics() {
    FLAG_SET_DEFAULT(UseShenandoahMatrix, true);

    // Set up special barriers for concurrent partial GC.
    FLAG_SET_DEFAULT(ShenandoahConditionalSATBBarrier, true);
    FLAG_SET_DEFAULT(ShenandoahSATBBarrier, false);
    FLAG_SET_DEFAULT(ShenandoahStoreValWriteBarrier, true);
    FLAG_SET_DEFAULT(ShenandoahStoreValReadBarrier, false);
    FLAG_SET_DEFAULT(ShenandoahAsmWB, false);

    if (FLAG_IS_DEFAULT(ShenandoahRefProcFrequency)) {
       FLAG_SET_DEFAULT(ShenandoahRefProcFrequency,1);
    }
    // TODO: Disable this optimization for now, as it also requires the matrix barriers.
#ifdef COMPILER2
    FLAG_SET_DEFAULT(ArrayCopyLoadStoreMaxElem, 0);
#endif
  }

  void initialize() {
    _from_idxs = NEW_C_HEAP_ARRAY(size_t, ShenandoahHeap::heap()->num_regions(), mtGC);
  }

  virtual ~ShenandoahPartialHeuristics() {
    FREE_C_HEAP_ARRAY(size_t, _from_idxs);
  }

  bool should_start_update_refs() {
    return true;
  }

  bool update_refs() const {
    return true;
  }

  bool can_do_partial_gc() {
    return true;
  }

  bool should_start_concurrent_mark(size_t used, size_t capacity) const {
    return false;
  }

  virtual bool is_diagnostic() {
    return false;
  }

  virtual bool is_experimental() {
    return true;
  }

  virtual bool should_start_partial_gc() = 0;
  virtual void choose_collection_set(ShenandoahCollectionSet* collection_set) = 0;

};

class ShenandoahPartialConnectedHeuristics : public ShenandoahPartialHeuristics {
public:
  virtual const char* name() {
    return "connectedness";
  }

  bool should_start_partial_gc() {
    ShenandoahHeap* heap = ShenandoahHeap::heap();

    if (heap->has_forwarded_objects()) {
      // Cannot start partial if heap is not completely updated.
      return false;
    }

    size_t capacity  = heap->capacity();
    size_t used      = heap->used();
    size_t prev_used = heap->used_at_last_gc();

    if (used < prev_used) {
      // Major collection must have happened, "used" data is unreliable, wait for update.
      return false;
    }

    size_t active    = heap->regions()->active_regions() * ShenandoahHeapRegion::region_size_bytes();
    size_t threshold = active * ShenandoahConnectednessPercentage / 100;
    size_t allocated = used - prev_used;
    bool result = allocated > threshold;

    FormatBuffer<> msg("%s. Capacity: " SIZE_FORMAT "M, Used: " SIZE_FORMAT "M, Previous Used: " SIZE_FORMAT
                       "M, Allocated: " SIZE_FORMAT "M, Threshold: " SIZE_FORMAT "M",
                       result ? "Partial cycle triggered" : "Partial cycle skipped",
                       capacity/M, used/M, prev_used/M, allocated/M, threshold/M);

    if (result) {
      log_info(gc,ergo)("%s", msg.buffer());
    } else {
      log_trace(gc,ergo)("%s", msg.buffer());
    }
    return result;
  }

  void choose_collection_set(ShenandoahCollectionSet* collection_set) {
    ShenandoahHeap* heap = ShenandoahHeap::heap();
    ShenandoahConnectionMatrix* matrix = heap->connection_matrix();
    ShenandoahHeapRegionSet* regions = heap->regions();
    size_t num_regions = heap->num_regions();

    RegionConnections* connects = get_region_connects_cache(num_regions);
    size_t connect_cnt = 0;

    for (uint to_idx = 0; to_idx < num_regions; to_idx++) {
      ShenandoahHeapRegion* region = regions->get(to_idx);
      region->set_root(false);
      if (!region->is_regular()) continue;

      uint count = matrix->count_connected_to(to_idx, num_regions);
      if (count < ShenandoahPartialInboundThreshold) {
        connects[connect_cnt]._region = region;
        connects[connect_cnt]._connections = count;
        connect_cnt++;
      }
    }

    QuickSort::sort<RegionConnections>(connects, (int)connect_cnt, compare_by_connects, false);

    // Heuristics triggered partial when allocated was larger than a threshold.
    // New allocations might have happened while we were preparing for GC,
    // capture all them in this cycle. This "adjusts" the threshold automatically.
    size_t used      = heap->used();
    size_t prev_used = heap->used_at_last_gc();
    guarantee(used >= prev_used, "Invariant");
    size_t target = MIN3(ShenandoahHeapRegion::required_regions(used - prev_used), num_regions, connect_cnt);

    for (size_t c = 0; c < target; c++) {
      assert (c == 0 || connects[c]._connections >= connects[c-1]._connections, "monotonicity");

      ShenandoahHeapRegion* region = connects[c]._region;
      size_t to_idx = region->region_number();
      assert(region->is_regular(), "filtered before");
      assert(! heap->region_in_collection_set(to_idx), "must not be in cset yet");

      size_t from_idx_count = 0;
      if (matrix->enumerate_connected_to(to_idx, num_regions,
                                         _from_idxs, from_idx_count,
                                         ShenandoahPartialInboundThreshold)) {
        maybe_add_heap_region(region, collection_set);
        for (size_t i = 0; i < from_idx_count; i++) {
          ShenandoahHeapRegion* r = regions->get(_from_idxs[i]);
          if (!r->is_root()) {
            r->set_root(true);
          }
        }
      }
    }

    collection_set->update_region_status();
  }
};

class ShenandoahGenerationalPartialHeuristics : public ShenandoahPartialHeuristics {
public:

  ShenandoahGenerationalPartialHeuristics() : ShenandoahPartialHeuristics() {
     if (FLAG_IS_DEFAULT(ShenandoahPartialInboundThreshold)) {
       FLAG_SET_DEFAULT(ShenandoahPartialInboundThreshold, 100);
     }
  }

  virtual const char* name() {
    return "generational";
  }

  virtual void choose_collection_set(ShenandoahCollectionSet* collection_set) {
    ShenandoahHeap* heap = ShenandoahHeap::heap();
    ShenandoahConnectionMatrix* matrix = heap->connection_matrix();
    uint64_t alloc_seq_at_last_gc_end   = heap->alloc_seq_at_last_gc_end();
    uint64_t alloc_seq_at_last_gc_start = heap->alloc_seq_at_last_gc_start();

    ShenandoahHeapRegionSet* regions = heap->regions();
    size_t active = regions->active_regions();
    ShenandoahHeapRegionSet sorted_regions(active);

    for (size_t i = 0; i < active; i++) {
      sorted_regions.add_region(regions->get(i));
    }

    sorted_regions.sort(compare_by_alloc_seq_descending);

    // Heuristics triggered partial when allocated was larger than a threshold.
    // New allocations might have happened while we were preparing for GC,
    // capture all them in this cycle. This "adjusts" the threshold automatically.
    size_t used      = heap->used();
    size_t prev_used = heap->used_at_last_gc();
    guarantee(used >= prev_used, "Invariant");
    size_t target = MIN2(ShenandoahHeapRegion::required_regions(used - prev_used), sorted_regions.active_regions());

    for (uint to_idx = 0; to_idx < active; to_idx++) {
      ShenandoahHeapRegion* region = regions->get(to_idx);
      region->set_root(false);
    }

    uint count = 0;
    size_t sorted_active = sorted_regions.active_regions();

    for (uint i = 0; (i < sorted_active) && (count < target); i++) {
      ShenandoahHeapRegion* contender = sorted_regions.get(i);
      if (contender->last_alloc_seq_num() <= alloc_seq_at_last_gc_end) {
        break;
      }

      size_t index = contender->region_number();
      size_t num_regions = heap->num_regions();
      size_t from_idx_count = 0;
      if (matrix->enumerate_connected_to(index, num_regions, _from_idxs, from_idx_count,
                                         ShenandoahPartialInboundThreshold)) {
        if (maybe_add_heap_region(contender, collection_set)) {
          count++;
        }

        for (uint f = 0; f < from_idx_count; f++) {
          ShenandoahHeapRegion* r = regions->get(_from_idxs[f]);
          if (!r->is_root()) {
            r->set_root(true);
          }
        }
      }
    }
    collection_set->update_region_status();

    log_info(gc,ergo)("Regions: Active: " SIZE_FORMAT ", Target: " SIZE_FORMAT " (" SIZE_FORMAT "%%), In CSet: " SIZE_FORMAT,
                      active, target, ShenandoahGenerationalYoungGenPercentage, collection_set->count());
  }

  bool should_start_partial_gc() {
    ShenandoahHeap* heap = ShenandoahHeap::heap();

    if (heap->has_forwarded_objects()) {
      // Cannot start partial if heap is not completely updated.
      return false;
    }

    size_t capacity  = heap->capacity();
    size_t used      = heap->used();
    size_t prev_used = heap->used_at_last_gc();

    if (used < prev_used) {
      // Major collection must have happened, "used" data is unreliable, wait for update.
      return false;
    }

    size_t active    = heap->regions()->active_regions() * ShenandoahHeapRegion::region_size_bytes();
    size_t threshold = active * ShenandoahGenerationalYoungGenPercentage / 100;
    size_t allocated = used - prev_used;

    // Start the next young gc after we've allocated percentage_young of the heap.
    bool result = allocated > threshold;

    FormatBuffer<> msg("%s. Capacity: " SIZE_FORMAT "M, Used: " SIZE_FORMAT "M, Previous Used: " SIZE_FORMAT
                       "M, Allocated: " SIZE_FORMAT "M, Threshold: " SIZE_FORMAT "M",
                       result ? "Partial cycle triggered" : "Partial cycle skipped",
                       capacity/M, used/M, prev_used/M, allocated/M, threshold/M);

    if (result) {
      log_info(gc,ergo)("%s", msg.buffer());
    } else {
      log_trace(gc,ergo)("%s", msg.buffer());
    }
    return result;
  }
};

class ShenandoahLRUPartialHeuristics : public ShenandoahPartialHeuristics {
public:
  ShenandoahLRUPartialHeuristics() : ShenandoahPartialHeuristics() {
    if (FLAG_IS_DEFAULT(ShenandoahPartialInboundThreshold)) {
      FLAG_SET_DEFAULT(ShenandoahPartialInboundThreshold, 100);
    }
  }

  virtual const char* name() {
    return "LRU";
  }

  virtual void choose_collection_set(ShenandoahCollectionSet* collection_set) {
    ShenandoahHeap* heap = ShenandoahHeap::heap();
    ShenandoahConnectionMatrix* matrix = heap->connection_matrix();
    uint64_t alloc_seq_at_last_gc_start = heap->alloc_seq_at_last_gc_start();

    ShenandoahHeapRegionSet* regions = ShenandoahHeap::heap()->regions();
    size_t active = regions->active_regions();
    ShenandoahHeapRegionSet sorted_regions(active);

    for (size_t i = 0; i < active; i++) {
      ShenandoahHeapRegion* r = regions->get(i);
      if (r->is_regular() && (r->last_alloc_seq_num() > 0)) {
        sorted_regions.add_region(regions->get(i));
      }
    }

    sorted_regions.sort(compare_by_alloc_seq_ascending);

    // Heuristics triggered partial when allocated was larger than a threshold.
    // New allocations might have happened while we were preparing for GC,
    // capture all them in this cycle. This "adjusts" the threshold automatically.
    size_t used      = heap->used();
    size_t prev_used = heap->used_at_last_gc();
    guarantee(used >= prev_used, "Invariant");
    size_t target = MIN2(ShenandoahHeapRegion::required_regions(used - prev_used), sorted_regions.active_regions());

    for (uint to_idx = 0; to_idx < active; to_idx++) {
      ShenandoahHeapRegion* region = regions->get(to_idx);
      region->set_root(false);
    }
    uint count = 0;
    size_t sorted_active = sorted_regions.active_regions();

    for (uint i = 0; (i < sorted_active) && (count < target); i++) {
      ShenandoahHeapRegion* contender = sorted_regions.get(i);
      if (contender->last_alloc_seq_num() >= alloc_seq_at_last_gc_start) {
        break;
      }

      size_t index = contender->region_number();
      size_t num_regions = heap->num_regions();
      size_t from_idx_count = 0;
      if (matrix->enumerate_connected_to(index, num_regions,_from_idxs, from_idx_count,
                                         ShenandoahPartialInboundThreshold)) {
        if (maybe_add_heap_region(contender, collection_set)) {
          count++;
        }
        for (uint f = 0; f < from_idx_count; f++) {
          ShenandoahHeapRegion* r = regions->get(_from_idxs[f]);
          if (!r->is_root()) {
            r->set_root(true);
          }
        }
      }
    }
    collection_set->update_region_status();

    log_info(gc,ergo)("Regions: Active: " SIZE_FORMAT ", Target: " SIZE_FORMAT " (" SIZE_FORMAT "%%), In CSet: " SIZE_FORMAT,
                      active, target, ShenandoahLRUOldGenPercentage, collection_set->count());
  }

  bool should_start_partial_gc() {
    ShenandoahHeap* heap = ShenandoahHeap::heap();

    if (heap->has_forwarded_objects()) {
      // Cannot start partial if heap is not completely updated.
      return false;
    }

    size_t capacity  = heap->capacity();
    size_t used      = heap->used();
    size_t prev_used = heap->used_at_last_gc();

    if (used < prev_used) {
      // Major collection must have happened, "used" data is unreliable, wait for update.
      return false;
    }

    // For now don't start until we are 40% full
    size_t allocated = used - prev_used;
    size_t active    = heap->regions()->active_regions() * ShenandoahHeapRegion::region_size_bytes();
    size_t threshold = active * ShenandoahLRUOldGenPercentage / 100;
    size_t minimum   = active * 0.4;

    bool result = ((used > minimum) && (allocated > threshold));

    FormatBuffer<> msg("%s. Capacity: " SIZE_FORMAT "M, Used: " SIZE_FORMAT "M, Previous Used: " SIZE_FORMAT
                       "M, Allocated: " SIZE_FORMAT "M, Threshold: " SIZE_FORMAT "M, Minimum: " SIZE_FORMAT "M",
                       result ? "Partial cycle triggered" : "Partial cycle skipped",
                       capacity/M, used/M, prev_used/M, allocated/M, threshold/M, minimum/M);

    if (result) {
      log_info(gc,ergo)("%s", msg.buffer());
    } else {
      log_trace(gc,ergo)("%s", msg.buffer());
    }
    return result;
   }

};


ShenandoahCollectorPolicy::ShenandoahCollectorPolicy() :
  _cycle_counter(0),
  _success_concurrent_gcs(0),
  _success_partial_gcs(0),
  _success_degenerated_gcs(0),
  _success_full_gcs(0),
  _explicit_concurrent(0),
  _explicit_full(0),
  _alloc_failure_degenerated(0),
  _alloc_failure_full(0),
  _alloc_failure_degenerated_upgrade_to_full(0)
{

  ShenandoahHeapRegion::setup_heap_region_size(initial_heap_byte_size(), max_heap_byte_size());

  initialize_all();

  _tracer = new (ResourceObj::C_HEAP, mtGC) ShenandoahTracer();

  if (ShenandoahGCHeuristics != NULL) {
    _minor_heuristics = NULL;
    if (strcmp(ShenandoahGCHeuristics, "aggressive") == 0) {
      _heuristics = new ShenandoahAggressiveHeuristics();
    } else if (strcmp(ShenandoahGCHeuristics, "static") == 0) {
      _heuristics = new ShenandoahStaticHeuristics();
    } else if (strcmp(ShenandoahGCHeuristics, "adaptive") == 0) {
      _heuristics = new ShenandoahAdaptiveHeuristics();
    } else if (strcmp(ShenandoahGCHeuristics, "passive") == 0) {
      _heuristics = new ShenandoahPassiveHeuristics();
    } else if (strcmp(ShenandoahGCHeuristics, "continuous") == 0) {
      _heuristics = new ShenandoahContinuousHeuristics();
    } else if (strcmp(ShenandoahGCHeuristics, "connected") == 0) {
      _heuristics = new ShenandoahAdaptiveHeuristics();
      _minor_heuristics = new ShenandoahPartialConnectedHeuristics();
    } else if (strcmp(ShenandoahGCHeuristics, "generational") == 0) {
      _heuristics = new ShenandoahAdaptiveHeuristics();
      _minor_heuristics = new ShenandoahGenerationalPartialHeuristics();
    } else if (strcmp(ShenandoahGCHeuristics, "LRU") == 0) {
      _heuristics = new ShenandoahAdaptiveHeuristics();
      _minor_heuristics = new ShenandoahLRUPartialHeuristics();
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
    if (_minor_heuristics != NULL && _minor_heuristics->is_diagnostic() && !UnlockDiagnosticVMOptions) {
      vm_exit_during_initialization(
              err_msg("Heuristics \"%s\" is diagnostic, and must be enabled via -XX:+UnlockDiagnosticVMOptions.",
                      _minor_heuristics->name()));
    }
    if (_minor_heuristics != NULL && _minor_heuristics->is_experimental() && !UnlockExperimentalVMOptions) {
      vm_exit_during_initialization(
              err_msg("Heuristics \"%s\" is experimental, and must be enabled via -XX:+UnlockExperimentalVMOptions.",
                      _minor_heuristics->name()));
    }

    if (ShenandoahConditionalSATBBarrier && ShenandoahSATBBarrier) {
      vm_exit_during_initialization("Cannot use both ShenandoahSATBBarrier and ShenandoahConditionalSATBBarrier");
    }
    if (ShenandoahStoreValWriteBarrier && ShenandoahStoreValReadBarrier) {
      vm_exit_during_initialization("Cannot use both ShenandoahStoreValWriteBarrier and ShenandoahStoreValReadBarrier");
    }

    if (_minor_heuristics != NULL) {
      log_info(gc, init)("Shenandoah heuristics: %s minor with %s major",
                         _minor_heuristics->name(), _heuristics->name());
    } else {
      log_info(gc, init)("Shenandoah heuristics: %s",
                         _heuristics->name());
    }
    _heuristics->print_thresholds();
  } else {
      ShouldNotReachHere();
  }
}

ShenandoahCollectorPolicy* ShenandoahCollectorPolicy::as_pgc_policy() {
  return this;
}

BarrierSet::Name ShenandoahCollectorPolicy::barrier_set_name() {
  return BarrierSet::ShenandoahBarrierSet;
}

HeapWord* ShenandoahCollectorPolicy::mem_allocate_work(size_t size,
                                                       bool is_tlab,
                                                       bool* gc_overhead_limit_was_exceeded) {
  guarantee(false, "Not using this policy feature yet.");
  return NULL;
}

HeapWord* ShenandoahCollectorPolicy::satisfy_failed_allocation(size_t size, bool is_tlab) {
  guarantee(false, "Not using this policy feature yet.");
  return NULL;
}

void ShenandoahCollectorPolicy::initialize_alignments() {

  // This is expected by our algorithm for ShenandoahHeap::heap_region_containing().
  _space_alignment = ShenandoahHeapRegion::region_size_bytes();
  _heap_alignment = ShenandoahHeapRegion::region_size_bytes();
}

void ShenandoahCollectorPolicy::post_heap_initialize() {
  _heuristics->initialize();
  if (_minor_heuristics != NULL) {
    _minor_heuristics->initialize();
  }
}

void ShenandoahCollectorPolicy::record_bytes_allocated(size_t bytes) {
  _heuristics->record_bytes_allocated(bytes);
}

void ShenandoahCollectorPolicy::record_bytes_start_CM(size_t bytes) {
  _heuristics->record_bytes_start_CM(bytes);
}

void ShenandoahCollectorPolicy::record_bytes_end_CM(size_t bytes) {
  _heuristics->record_bytes_end_CM(bytes);
}

void ShenandoahCollectorPolicy::record_bytes_reclaimed(size_t bytes) {
  _heuristics->record_bytes_reclaimed(bytes);
}

void ShenandoahCollectorPolicy::record_explicit_to_concurrent() {
  _heuristics->record_explicit_gc();
  _explicit_concurrent++;
}

void ShenandoahCollectorPolicy::record_explicit_to_full() {
  _heuristics->record_explicit_gc();
  _explicit_full++;
}

void ShenandoahCollectorPolicy::record_alloc_failure_to_full() {
  _heuristics->record_allocation_failure_gc();
  _alloc_failure_full++;
}

void ShenandoahCollectorPolicy::record_alloc_failure_to_degenerated() {
  _heuristics->record_allocation_failure_gc();
  _alloc_failure_degenerated++;
}

void ShenandoahCollectorPolicy::record_degenerated_upgrade_to_full() {
  _alloc_failure_degenerated_upgrade_to_full++;
}

void ShenandoahCollectorPolicy::record_success_concurrent() {
  _heuristics->record_success_concurrent();
  _success_concurrent_gcs++;
}

void ShenandoahCollectorPolicy::record_success_partial() {
  _success_partial_gcs++;
}

void ShenandoahCollectorPolicy::record_success_degenerated() {
  _heuristics->record_success_degenerated();
  _success_degenerated_gcs++;
}

void ShenandoahCollectorPolicy::record_success_full() {
  _heuristics->record_success_full();
  _success_full_gcs++;
}

bool ShenandoahCollectorPolicy::should_start_concurrent_mark(size_t used,
                                                             size_t capacity) {
  return _heuristics->should_start_concurrent_mark(used, capacity);
}

bool ShenandoahCollectorPolicy::should_degenerate_cycle() {
  return _heuristics->should_degenerate_cycle();
}

bool ShenandoahCollectorPolicy::update_refs() {
  if (_minor_heuristics != NULL && _minor_heuristics->update_refs()) {
    return true;
  }
  return _heuristics->update_refs();
}

bool ShenandoahCollectorPolicy::should_start_update_refs() {
  if (_minor_heuristics != NULL && _minor_heuristics->should_start_update_refs()) {
    return true;
  }
  return _heuristics->should_start_update_refs();
}

void ShenandoahCollectorPolicy::record_peak_occupancy() {
  _heuristics->record_peak_occupancy();
}

void ShenandoahCollectorPolicy::choose_collection_set(ShenandoahCollectionSet* collection_set,
                                                      bool minor) {
  if (minor)
    _minor_heuristics->choose_collection_set(collection_set);
  else
    _heuristics->choose_collection_set(collection_set);
}

void ShenandoahCollectorPolicy::choose_free_set(ShenandoahFreeSet* free_set) {
   _heuristics->choose_free_set(free_set);
}


bool ShenandoahCollectorPolicy::process_references() {
  return _heuristics->process_references();
}

bool ShenandoahCollectorPolicy::unload_classes() {
  return _heuristics->unload_classes();
}

size_t ShenandoahCollectorPolicy::cycle_counter() const {
  return _cycle_counter;
}

void ShenandoahCollectorPolicy::record_phase_time(ShenandoahPhaseTimings::Phase phase, double secs) {
  _heuristics->record_phase_time(phase, secs);
}

bool ShenandoahCollectorPolicy::should_start_partial_gc() {
  if (_minor_heuristics != NULL) {
    return _minor_heuristics->should_start_partial_gc();
  } else {
    return false; // no minor heuristics -> no partial gc
  }
}

bool ShenandoahCollectorPolicy::can_do_partial_gc() {
  if (_minor_heuristics != NULL) {
    return _minor_heuristics->can_do_partial_gc();
  } else {
    return false; // no minor heuristics -> no partial gc
  }
}

void ShenandoahCollectorPolicy::record_cycle_start() {
  _cycle_counter++;
  _heuristics->record_cycle_start();
}

void ShenandoahCollectorPolicy::record_cycle_end() {
  _heuristics->record_cycle_end();
}

void ShenandoahCollectorPolicy::record_shutdown() {
  _in_shutdown.set();
}

bool ShenandoahCollectorPolicy::is_at_shutdown() {
  return _in_shutdown.is_set();
}

void ShenandoahCollectorPolicy::print_gc_stats(outputStream* out) const {
  out->print_cr("Under allocation pressure, concurrent cycles will cancel, and either continue cycle");
  out->print_cr("under stop-the-world pause or result in stop-the-world Full GC. Increase heap size,");
  out->print_cr("tune GC heuristics, or lower allocation rate to avoid degenerated and Full GC cycles.");
  out->cr();

  out->print_cr(SIZE_FORMAT_W(5) " successful partial concurrent GCs", _success_partial_gcs);
  out->cr();

  out->print_cr(SIZE_FORMAT_W(5) " successful concurrent GCs",         _success_concurrent_gcs);
  out->print_cr("  " SIZE_FORMAT_W(5) " invoked explicitly",           _explicit_concurrent);
  out->cr();

  out->print_cr(SIZE_FORMAT_W(5) " Degenerated GCs",                   _success_degenerated_gcs);
  out->print_cr("  " SIZE_FORMAT_W(5) " caused by allocation failure", _alloc_failure_degenerated);
  out->print_cr("  " SIZE_FORMAT_W(5) " upgraded to Full GC",          _alloc_failure_degenerated_upgrade_to_full);
  out->cr();

  out->print_cr(SIZE_FORMAT_W(5) " Full GCs",                          _success_full_gcs + _alloc_failure_degenerated_upgrade_to_full);
  out->print_cr("  " SIZE_FORMAT_W(5) " invoked explicitly",           _explicit_full);
  out->print_cr("  " SIZE_FORMAT_W(5) " caused by allocation failure", _alloc_failure_full);
  out->print_cr("  " SIZE_FORMAT_W(5) " upgraded from Degenerated GC", _alloc_failure_degenerated_upgrade_to_full);
}
