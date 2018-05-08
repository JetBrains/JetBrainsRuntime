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
#include "gc/shared/gcPolicyCounters.hpp"
#include "gc/shenandoah/heuristics/shenandoahAdaptiveHeuristics.hpp"
#include "gc/shenandoah/heuristics/shenandoahAggressiveHeuristics.hpp"
#include "gc/shenandoah/heuristics/shenandoahCompactHeuristics.hpp"
#include "gc/shenandoah/heuristics/shenandoahPartialConnectedHeuristics.hpp"
#include "gc/shenandoah/heuristics/shenandoahPartialGenerationalHeuristics.hpp"
#include "gc/shenandoah/heuristics/shenandoahPartialLRUHeuristics.hpp"
#include "gc/shenandoah/heuristics/shenandoahPassiveHeuristics.hpp"
#include "gc/shenandoah/heuristics/shenandoahStaticHeuristics.hpp"
#include "gc/shenandoah/shenandoahCollectionSet.hpp"
#include "gc/shenandoah/shenandoahCollectorPolicy.hpp"
#include "gc/shenandoah/shenandoahConnectionMatrix.hpp"
#include "gc/shenandoah/shenandoahFreeSet.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahHeuristics.hpp"
#include "gc/shenandoah/shenandoahTraversalGC.hpp"

#include "runtime/os.hpp"

void ShenandoahCollectorPolicy::record_gc_start() {
  _heuristics->record_gc_start();
}

void ShenandoahCollectorPolicy::record_gc_end() {
  _heuristics->record_gc_end();
}

ShenandoahCollectorPolicy::ShenandoahCollectorPolicy() :
  _cycle_counter(0),
  _success_concurrent_gcs(0),
  _success_degenerated_gcs(0),
  _success_full_gcs(0),
  _explicit_concurrent(0),
  _explicit_full(0),
  _alloc_failure_degenerated(0),
  _alloc_failure_full(0),
  _alloc_failure_degenerated_upgrade_to_full(0)
{
  Copy::zero_to_bytes(_degen_points, sizeof(size_t) * ShenandoahHeap::_DEGENERATED_LIMIT);

  ShenandoahHeapRegion::setup_heap_region_size(initial_heap_byte_size(), max_heap_byte_size());

  initialize_all();

  _tracer = new (ResourceObj::C_HEAP, mtGC) ShenandoahTracer();

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

ShenandoahCollectorPolicy* ShenandoahCollectorPolicy::as_pgc_policy() {
  return this;
}

BarrierSet::Name ShenandoahCollectorPolicy::barrier_set_name() {
  return BarrierSet::Shenandoah;
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

void ShenandoahCollectorPolicy::record_alloc_failure_to_degenerated(ShenandoahHeap::ShenandoahDegenPoint point) {
  assert(point < ShenandoahHeap::_DEGENERATED_LIMIT, "sanity");
  _heuristics->record_allocation_failure_gc();
  _alloc_failure_degenerated++;
  _degen_points[point]++;
}

void ShenandoahCollectorPolicy::record_degenerated_upgrade_to_full() {
  _alloc_failure_degenerated_upgrade_to_full++;
}

void ShenandoahCollectorPolicy::record_success_concurrent() {
  _heuristics->record_success_concurrent();
  _success_concurrent_gcs++;
}

void ShenandoahCollectorPolicy::record_success_degenerated() {
  _heuristics->record_success_degenerated();
  _success_degenerated_gcs++;
}

void ShenandoahCollectorPolicy::record_success_full() {
  _heuristics->record_success_full();
  _success_full_gcs++;
}

bool ShenandoahCollectorPolicy::should_start_normal_gc() {
  return _heuristics->should_start_normal_gc();
}

bool ShenandoahCollectorPolicy::should_degenerate_cycle() {
  return _heuristics->should_degenerate_cycle();
}

bool ShenandoahCollectorPolicy::update_refs() {
  return _heuristics->update_refs();
}

bool ShenandoahCollectorPolicy::should_start_update_refs() {
  return _heuristics->should_start_update_refs();
}

void ShenandoahCollectorPolicy::record_peak_occupancy() {
  _heuristics->record_peak_occupancy();
}

void ShenandoahCollectorPolicy::choose_collection_set(ShenandoahCollectionSet* collection_set,
                                                      bool minor) {
  _heuristics->choose_collection_set(collection_set);
}

bool ShenandoahCollectorPolicy::should_process_references() {
  return _heuristics->should_process_references();
}

bool ShenandoahCollectorPolicy::should_unload_classes() {
  return _heuristics->should_unload_classes();
}

size_t ShenandoahCollectorPolicy::cycle_counter() const {
  return _cycle_counter;
}

void ShenandoahCollectorPolicy::record_phase_time(ShenandoahPhaseTimings::Phase phase, double secs) {
  _heuristics->record_phase_time(phase, secs);
}

ShenandoahHeap::GCCycleMode ShenandoahCollectorPolicy::should_start_traversal_gc() {
  return _heuristics->should_start_traversal_gc();
}

bool ShenandoahCollectorPolicy::can_do_traversal_gc() {
  return _heuristics->can_do_traversal_gc();
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
  out->print_cr("Under allocation pressure, concurrent cycles may cancel, and either continue cycle");
  out->print_cr("under stop-the-world pause or result in stop-the-world Full GC. Increase heap size,");
  out->print_cr("tune GC heuristics, set more aggressive pacing delay, or lower allocation rate");
  out->print_cr("to avoid Degenerated and Full GC cycles.");
  out->cr();

  out->print_cr(SIZE_FORMAT_W(5) " successful concurrent GCs",         _success_concurrent_gcs);
  out->print_cr("  " SIZE_FORMAT_W(5) " invoked explicitly",           _explicit_concurrent);
  out->cr();

  out->print_cr(SIZE_FORMAT_W(5) " Degenerated GCs",                   _success_degenerated_gcs);
  out->print_cr("  " SIZE_FORMAT_W(5) " caused by allocation failure", _alloc_failure_degenerated);
  for (int c = 0; c < ShenandoahHeap::_DEGENERATED_LIMIT; c++) {
    if (_degen_points[c] > 0) {
      const char* desc = ShenandoahHeap::degen_point_to_string((ShenandoahHeap::ShenandoahDegenPoint)c);
      out->print_cr("    " SIZE_FORMAT_W(5) " happened at %s",         _degen_points[c], desc);
    }
  }
  out->print_cr("  " SIZE_FORMAT_W(5) " upgraded to Full GC",          _alloc_failure_degenerated_upgrade_to_full);
  out->cr();

  out->print_cr(SIZE_FORMAT_W(5) " Full GCs",                          _success_full_gcs + _alloc_failure_degenerated_upgrade_to_full);
  out->print_cr("  " SIZE_FORMAT_W(5) " invoked explicitly",           _explicit_full);
  out->print_cr("  " SIZE_FORMAT_W(5) " caused by allocation failure", _alloc_failure_full);
  out->print_cr("  " SIZE_FORMAT_W(5) " upgraded from Degenerated GC", _alloc_failure_degenerated_upgrade_to_full);
}
