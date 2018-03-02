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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHCOLLECTORPOLICY_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHCOLLECTORPOLICY_HPP

#include "gc/shared/gcTrace.hpp"
#include "gc/shared/gcTimer.hpp"
#include "gc/shared/collectorPolicy.hpp"
#include "gc/shenandoah/shenandoahPhaseTimings.hpp"
#include "gc/shenandoah/shenandoahHeap.hpp"
#include "runtime/arguments.hpp"
#include "utilities/numberSeq.hpp"

class ShenandoahCollectionSet;
class ShenandoahConnectionMatrix;
class ShenandoahFreeSet;
class ShenandoahHeap;
class ShenandoahHeuristics;
class outputStream;

class ShenandoahCollectorPolicy: public CollectorPolicy {
private:
  size_t _success_partial_gcs;
  size_t _success_concurrent_gcs;
  size_t _success_degenerated_gcs;
  size_t _success_full_gcs;
  size_t _alloc_failure_degenerated;
  size_t _alloc_failure_degenerated_upgrade_to_full;
  size_t _alloc_failure_full;
  size_t _explicit_concurrent;
  size_t _explicit_full;

  ShenandoahSharedFlag _in_shutdown;

  ShenandoahHeuristics* _heuristics;
  ShenandoahHeuristics* _minor_heuristics;
  ShenandoahTracer* _tracer;

  size_t _cycle_counter;


public:
  ShenandoahCollectorPolicy();

  virtual ShenandoahCollectorPolicy* as_pgc_policy();

  BarrierSet::Name barrier_set_name();

  HeapWord* mem_allocate_work(size_t size,
                              bool is_tlab,
                              bool* gc_overhead_limit_was_exceeded);

  HeapWord* satisfy_failed_allocation(size_t size, bool is_tlab);

  void initialize_alignments();

  void post_heap_initialize();

  void record_gc_start();
  void record_gc_end();
  ShenandoahHeuristics* heuristics() { return _heuristics;}
  // TODO: This is different from gc_end: that one encompasses one VM operation.
  // These two encompass the entire cycle.
  void record_cycle_start();
  void record_cycle_end();

  void record_phase_time(ShenandoahPhaseTimings::Phase phase, double secs);

  void record_success_partial();
  void record_success_concurrent();
  void record_success_degenerated();
  void record_success_full();
  void record_alloc_failure_to_degenerated();
  void record_alloc_failure_to_full();
  void record_degenerated_upgrade_to_full();
  void record_explicit_to_concurrent();
  void record_explicit_to_full();

  bool should_start_concurrent_mark(size_t used, size_t capacity);
  bool should_start_partial_gc();
  bool can_do_partial_gc();
  bool should_start_traversal_gc();
  bool can_do_traversal_gc();

  // Returns true when there should be a separate concurrent reference
  // updating phase after evacuation.
  bool should_start_update_refs();
  bool update_refs();

  bool should_degenerate_cycle();


  void record_peak_occupancy();

  void record_shutdown();
  bool is_at_shutdown();

  void choose_collection_set(ShenandoahCollectionSet* collection_set,
                             bool minor = false);
  void choose_free_set(ShenandoahFreeSet* free_set);

  bool process_references();
  bool unload_classes();

  ShenandoahTracer* tracer() {return _tracer;}

  size_t cycle_counter() const;

  void print_gc_stats(outputStream* out) const;
};


#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHCOLLECTORPOLICY_HPP
