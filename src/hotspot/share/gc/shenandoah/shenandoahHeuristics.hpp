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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHHEURISTICS_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHHEURISTICS_HPP

#include "gc/shenandoah/shenandoahHeap.hpp"
#include "gc/shenandoah/shenandoahPhaseTimings.hpp"
#include "memory/allocation.hpp"
#include "runtime/globals_extension.hpp"

#define SHENANDOAH_ERGO_DISABLE_FLAG(name)                                  \
  do {                                                                      \
    if (FLAG_IS_DEFAULT(name) && (name)) {                                  \
      log_info(gc)("Heuristics ergonomically sets -XX:-" #name);            \
      FLAG_SET_DEFAULT(name, false);                                        \
    }                                                                       \
  } while (0)

#define SHENANDOAH_ERGO_ENABLE_FLAG(name)                                   \
  do {                                                                      \
    if (FLAG_IS_DEFAULT(name) && !(name)) {                                 \
      log_info(gc)("Heuristics ergonomically sets -XX:+" #name);            \
      FLAG_SET_DEFAULT(name, true);                                         \
    }                                                                       \
  } while (0)

#define SHENANDOAH_ERGO_OVERRIDE_DEFAULT(name, value)                       \
  do {                                                                      \
    if (FLAG_IS_DEFAULT(name)) {                                            \
      log_info(gc)("Heuristics ergonomically sets -XX:" #name "=" #value);  \
      FLAG_SET_DEFAULT(name, value);                                        \
    }                                                                       \
  } while (0)


class ShenandoahCollectionSet;
class ShenandoahHeapRegion;

class ShenandoahHeuristics : public CHeapObj<mtGC> {
protected:
  typedef struct {
    ShenandoahHeapRegion* _region;
    size_t _garbage;
    uint64_t _seqnum_last_alloc;
  } RegionData;

  typedef struct {
    ShenandoahHeapRegion* _region;
    size_t _connections;
  } RegionConnections;

  bool _update_refs_early;
  bool _update_refs_adaptive;

  RegionData* _region_data;
  size_t _region_data_size;

  RegionConnections* _region_connects;
  size_t _region_connects_size;

  uint _degenerated_cycles_in_a_row;
  uint _successful_cycles_in_a_row;

  size_t _bytes_in_cset;

  double _last_cycle_end;

  static int compare_by_garbage(RegionData a, RegionData b);
  static int compare_by_garbage_then_alloc_seq_ascending(RegionData a, RegionData b);
  static int compare_by_alloc_seq_ascending(RegionData a, RegionData b);
  static int compare_by_alloc_seq_descending(RegionData a, RegionData b);
  static int compare_by_connects(RegionConnections a, RegionConnections b);

  RegionData* get_region_data_cache(size_t num);

  RegionConnections* get_region_connects_cache(size_t num);

  virtual void choose_collection_set_from_regiondata(ShenandoahCollectionSet* set,
                                                     RegionData* data, size_t data_size,
                                                     size_t free) = 0;

public:

  ShenandoahHeuristics();
  virtual ~ShenandoahHeuristics();

  void record_gc_start();

  void record_gc_end();

  virtual void record_cycle_start();

  virtual void record_cycle_end();

  virtual void record_phase_time(ShenandoahPhaseTimings::Phase phase, double secs);

  virtual void print_thresholds();

  virtual bool should_start_normal_gc() const;

  virtual bool should_start_update_refs();

  virtual ShenandoahHeap::GCCycleMode should_start_traversal_gc();

  virtual bool can_do_traversal_gc();

  virtual bool should_degenerate_cycle();

  virtual void record_success_concurrent();

  virtual void record_success_degenerated();

  virtual void record_success_full();

  virtual void record_allocation_failure_gc();

  virtual void record_explicit_gc();

  virtual void record_peak_occupancy();

  virtual void choose_collection_set(ShenandoahCollectionSet* collection_set);

  virtual bool should_process_references();

  virtual bool should_unload_classes();

  bool maybe_add_heap_region(ShenandoahHeapRegion* hr,
                        ShenandoahCollectionSet* cs);

  virtual const char* name() = 0;
  virtual bool is_diagnostic() = 0;
  virtual bool is_experimental() = 0;
  virtual void initialize();

};

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHHEURISTICS_HPP
