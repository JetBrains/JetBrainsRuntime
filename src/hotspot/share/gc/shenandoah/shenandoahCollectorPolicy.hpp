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
  size_t _user_requested_gcs;
  size_t _allocation_failure_gcs;
  size_t _degenerated_cm;
  size_t _successful_cm;

  size_t _degenerated_uprefs;
  size_t _successful_uprefs;

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

  void report_concgc_cancelled();

  void record_user_requested_gc();
  void record_allocation_failure_gc();

  void record_bytes_allocated(size_t bytes);
  void record_bytes_reclaimed(size_t bytes);
  void record_bytes_start_CM(size_t bytes);
  void record_bytes_end_CM(size_t bytes);
  bool should_start_concurrent_mark(size_t used, size_t capacity);
  bool should_start_partial_gc();
  bool can_do_partial_gc();

  // Returns true when there should be a separate concurrent reference
  // updating phase after evacuation.
  bool should_start_update_refs();
  bool update_refs();

  bool handover_cancelled_marking();
  bool handover_cancelled_uprefs();

  void record_cm_cancelled();
  void record_cm_success();
  void record_cm_degenerated();
  void record_uprefs_cancelled();
  void record_uprefs_success();
  void record_uprefs_degenerated();

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
