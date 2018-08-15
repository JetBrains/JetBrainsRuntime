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

#include "gc/shared/workerDataArray.inline.hpp"
#include "gc/shenandoah/shenandoahCollectorPolicy.hpp"
#include "gc/shenandoah/shenandoahPhaseTimings.hpp"
#include "gc/shenandoah/shenandoahHeap.hpp"
#include "gc/shenandoah/shenandoahHeuristics.hpp"
#include "utilities/ostream.hpp"

ShenandoahPhaseTimings::ShenandoahPhaseTimings() : _policy(NULL) {
  uint max_workers = MAX2(ConcGCThreads, ParallelGCThreads);
  _worker_times = new ShenandoahWorkerTimings(max_workers);
  _termination_times = new ShenandoahTerminationTimings(max_workers);
  _policy = ShenandoahHeap::heap()->shenandoahPolicy();
  assert(_policy != NULL, "Can not be NULL");
  init_phase_names();
}

void ShenandoahPhaseTimings::record_phase_start(Phase phase) {
  _timing_data[phase]._start = os::elapsedTime();
}

void ShenandoahPhaseTimings::record_phase_end(Phase phase) {
  assert(_policy != NULL, "Not yet initialized");
  double end = os::elapsedTime();
  double elapsed = end - _timing_data[phase]._start;
  if (!_policy->is_at_shutdown()) {
    _timing_data[phase]._secs.add(elapsed);
  }
  ShenandoahHeap::heap()->heuristics()->record_phase_time(phase, elapsed);
}

void ShenandoahPhaseTimings::record_phase_time(Phase phase, jint time_us) {
  assert(_policy != NULL, "Not yet initialized");
  if (!_policy->is_at_shutdown()) {
    _timing_data[phase]._secs.add((double)time_us / 1000 / 1000);
  }
}

void ShenandoahPhaseTimings::record_workers_start(Phase phase) {
  for (uint i = 0; i < GCParPhasesSentinel; i++) {
    _worker_times->reset(i);
  }
}

void ShenandoahPhaseTimings::record_workers_end(Phase phase) {
  if (_policy->is_at_shutdown()) {
    // Do not record the past-shutdown events
    return;
  }

  guarantee(phase == init_evac ||
            phase == scan_roots ||
            phase == update_roots ||
            phase == init_traversal_gc_work ||
            phase == final_traversal_gc_work ||
            phase == final_traversal_update_roots ||
            phase == final_update_refs_roots ||
            phase == full_gc_roots ||
            phase == _num_phases,
            "only in these phases we can add per-thread phase times");
  if (phase != _num_phases) {
    // Merge _phase_time to counters below the given phase.
    for (uint i = 0; i < GCParPhasesSentinel; i++) {
      double t = _worker_times->average(i);
      _timing_data[phase + i + 1]._secs.add(t);
    }
  }
}

void ShenandoahPhaseTimings::print_on(outputStream* out) const {
  out->cr();
  out->print_cr("GC STATISTICS:");
  out->print_cr("  \"(G)\" (gross) pauses include VM time: time to notify and block threads, do the pre-");
  out->print_cr("        and post-safepoint housekeeping. Use -XX:+PrintSafepointStatistics to dissect.");
  out->print_cr("  \"(N)\" (net) pauses are the times spent in the actual GC code.");
  out->print_cr("  \"a\" is average time for each phase, look at levels to see if average makes sense.");
  out->print_cr("  \"lvls\" are quantiles: 0%% (minimum), 25%%, 50%% (median), 75%%, 100%% (maximum).");
  out->cr();

  for (uint i = 0; i < _num_phases; i++) {
    if (_timing_data[i]._secs.maximum() != 0) {
      print_summary_sd(out, _phase_names[i], &(_timing_data[i]._secs));
    }
  }
}

void ShenandoahPhaseTimings::print_summary_sd(outputStream* out, const char* str, const HdrSeq* seq) const {
  out->print_cr("%-27s = %8.2lf s (a = %8.0lf us) (n = " INT32_FORMAT_W(5) ") (lvls, us = %8.0lf, %8.0lf, %8.0lf, %8.0lf, %8.0lf)",
          str,
          seq->sum(),
          seq->avg() * 1000000.0,
          seq->num(),
          seq->percentile(0)  * 1000000.0,
          seq->percentile(25) * 1000000.0,
          seq->percentile(50) * 1000000.0,
          seq->percentile(75) * 1000000.0,
          seq->maximum() * 1000000.0
  );
}

void ShenandoahPhaseTimings::init_phase_names() {
  _phase_names[total_pause]                     = "Total Pauses (N)";
  _phase_names[total_pause_gross]               = "Total Pauses (G)";
  _phase_names[init_mark]                       = "Pause Init Mark (N)";
  _phase_names[init_mark_gross]                 = "Pause Init Mark (G)";
  _phase_names[final_mark]                      = "Pause Final Mark (N)";
  _phase_names[final_mark_gross]                = "Pause Final Mark (G)";
  _phase_names[final_evac]                      = "Pause Final Evac (N)";
  _phase_names[final_evac_gross]                = "Pause Final Evac (G)";
  _phase_names[accumulate_stats]                = "  Accumulate Stats";
  _phase_names[make_parsable]                   = "  Make Parsable";
  _phase_names[clear_liveness]                  = "  Clear Liveness";
  _phase_names[resize_tlabs]                    = "  Resize TLABs";
  _phase_names[finish_queues]                   = "  Finish Queues";
  _phase_names[termination]                     = "    Termination";
  _phase_names[weakrefs]                        = "  Weak References";
  _phase_names[weakrefs_process]                = "    Process";
  _phase_names[weakrefs_termination]            = "      Termination";
  _phase_names[purge]                           = "  System Purge";
  _phase_names[purge_class_unload]              = "    Unload Classes";
  _phase_names[purge_par]                       = "    Parallel Cleanup";
  _phase_names[purge_par_codecache]             = "      Code Cache";
  _phase_names[purge_par_symbstring]            = "      String/Symbol Tables";
  _phase_names[purge_par_rmt]                   = "      Resolved Methods";
  _phase_names[purge_par_classes]               = "      Clean Classes";
  _phase_names[purge_par_sync]                  = "      Synchronization";
  _phase_names[purge_string_dedup]              = "    String Dedup";
  _phase_names[purge_cldg]                      = "    CLDG";
  _phase_names[complete_liveness]               = "  Complete Liveness";
  _phase_names[prepare_evac]                    = "  Prepare Evacuation";

  _phase_names[scan_roots]                      = "  Scan Roots";
  _phase_names[scan_thread_roots]               = "    S: Thread Roots";
  _phase_names[scan_code_roots]                 = "    S: Code Cache Roots";
  _phase_names[scan_string_table_roots]         = "    S: String Table Roots";
  _phase_names[scan_universe_roots]             = "    S: Universe Roots";
  _phase_names[scan_jni_roots]                  = "    S: JNI Roots";
  _phase_names[scan_jni_weak_roots]             = "    S: JNI Weak Roots";
  _phase_names[scan_synchronizer_roots]         = "    S: Synchronizer Roots";
  _phase_names[scan_flat_profiler_roots]        = "    S: Flat Profiler Roots";
  _phase_names[scan_management_roots]           = "    S: Management Roots";
  _phase_names[scan_system_dictionary_roots]    = "    S: System Dict Roots";
  _phase_names[scan_cldg_roots]                 = "    S: CLDG Roots";
  _phase_names[scan_jvmti_roots]                = "    S: JVMTI Roots";
  _phase_names[scan_string_dedup_table_roots]   = "    S: Dedup Table Roots";
  _phase_names[scan_string_dedup_queue_roots]   = "    S: Dedup Queue Roots";
  _phase_names[scan_finish_queues]              = "    S: Finish Queues";

  _phase_names[update_roots]                    = "  Update Roots";
  _phase_names[update_thread_roots]             = "    U: Thread Roots";
  _phase_names[update_code_roots]               = "    U: Code Cache Roots";
  _phase_names[update_string_table_roots]       = "    U: String Table Roots";
  _phase_names[update_universe_roots]           = "    U: Universe Roots";
  _phase_names[update_jni_roots]                = "    U: JNI Roots";
  _phase_names[update_jni_weak_roots]           = "    U: JNI Weak Roots";
  _phase_names[update_synchronizer_roots]       = "    U: Synchronizer Roots";
  _phase_names[update_flat_profiler_roots]      = "    U: Flat Profiler Roots";
  _phase_names[update_management_roots]         = "    U: Management Roots";
  _phase_names[update_system_dictionary_roots]  = "    U: System Dict Roots";
  _phase_names[update_cldg_roots]               = "    U: CLDG Roots";
  _phase_names[update_jvmti_roots]              = "    U: JVMTI Roots";
  _phase_names[update_string_dedup_table_roots] = "    U: Dedup Table Roots";
  _phase_names[update_string_dedup_queue_roots] = "    U: Dedup Queue Roots";
  _phase_names[update_finish_queues]            = "    U: Finish Queues";

  _phase_names[init_evac]                       = "  Initial Evacuation";
  _phase_names[evac_thread_roots]               = "    E: Thread Roots";
  _phase_names[evac_code_roots]                 = "    E: Code Cache Roots";
  _phase_names[evac_string_table_roots]         = "    E: String Table Roots";
  _phase_names[evac_universe_roots]             = "    E: Universe Roots";
  _phase_names[evac_jni_roots]                  = "    E: JNI Roots";
  _phase_names[evac_jni_weak_roots]             = "    E: JNI Weak Roots";
  _phase_names[evac_synchronizer_roots]         = "    E: Synchronizer Roots";
  _phase_names[evac_flat_profiler_roots]        = "    E: Flat Profiler Roots";
  _phase_names[evac_management_roots]           = "    E: Management Roots";
  _phase_names[evac_system_dictionary_roots]    = "    E: System Dict Roots";
  _phase_names[evac_cldg_roots]                 = "    E: CLDG Roots";
  _phase_names[evac_jvmti_roots]                = "    E: JVMTI Roots";
  _phase_names[evac_string_dedup_table_roots]   = "    E: String Dedup Table Roots";
  _phase_names[evac_string_dedup_queue_roots]   = "    E: String Dedup Queue Roots";
  _phase_names[evac_finish_queues]              = "    E: Finish Queues";

  _phase_names[recycle_regions]                 = "  Recycle regions";

  _phase_names[degen_gc_gross]                  = "Pause Degenerated GC (G)";
  _phase_names[degen_gc]                        = "Pause Degenerated GC (N)";

  _phase_names[full_gc_gross]                   = "Pause Full GC (G)";
  _phase_names[full_gc]                         = "Pause Full GC (N)";
  _phase_names[full_gc_heapdumps]               = "  Heap Dumps";
  _phase_names[full_gc_prepare]                 = "  Prepare";
  _phase_names[full_gc_roots]                   = "  Roots";
  _phase_names[full_gc_thread_roots]            = "    F: Thread Roots";
  _phase_names[full_gc_code_roots]              = "    F: Code Cache Roots";
  _phase_names[full_gc_string_table_roots]      = "    F: String Table Roots";
  _phase_names[full_gc_universe_roots]          = "    F: Universe Roots";
  _phase_names[full_gc_jni_roots]               = "    F: JNI Roots";
  _phase_names[full_gc_jni_weak_roots]          = "    F: JNI Weak Roots";
  _phase_names[full_gc_synchronizer_roots]      = "    F: Synchronizer Roots";
  _phase_names[full_gc_flat_profiler_roots]     = "    F: Flat Profiler Roots";
  _phase_names[full_gc_management_roots]        = "    F: Management Roots";
  _phase_names[full_gc_system_dictionary_roots] = "    F: System Dict Roots";
  _phase_names[full_gc_cldg_roots]              = "    F: CLDG Roots";
  _phase_names[full_gc_jvmti_roots]             = "    F: JVMTI Roots";
  _phase_names[full_gc_string_dedup_table_roots]
                                                = "    F: Dedup Table Roots";
  _phase_names[full_gc_string_dedup_queue_roots]
                                                = "    F: Dedup Queue Roots";
  _phase_names[full_gc_finish_queues]           = "    F: Finish Queues";
  _phase_names[full_gc_mark]                    = "  Mark";
  _phase_names[full_gc_mark_finish_queues]      = "    Finish Queues";
  _phase_names[full_gc_mark_termination]        = "      Termination";
  _phase_names[full_gc_weakrefs]                = "    Weak References";
  _phase_names[full_gc_weakrefs_process]        = "      Process";
  _phase_names[full_gc_weakrefs_termination]    = "        Termination";
  _phase_names[full_gc_purge]                   = "    System Purge";
  _phase_names[full_gc_purge_class_unload]      = "      Unload Classes";
  _phase_names[full_gc_purge_par]               = "    Parallel Cleanup";
  _phase_names[full_gc_purge_par_codecache]     = "      Code Cache";
  _phase_names[full_gc_purge_par_symbstring]    = "      String/Symbol Tables";
  _phase_names[full_gc_purge_par_rmt]           = "      Resolved Methods";
  _phase_names[full_gc_purge_par_classes]       = "      Clean Classes";
  _phase_names[full_gc_purge_par_sync]          = "      Synchronization";
  _phase_names[full_gc_purge_cldg]              = "    CLDG";
  _phase_names[full_gc_purge_string_dedup]
                                                = "    String Dedup";
  _phase_names[full_gc_calculate_addresses]     = "  Calculate Addresses";
  _phase_names[full_gc_calculate_addresses_regular] = "    Regular Objects";
  _phase_names[full_gc_calculate_addresses_humong]  = "    Humongous Objects";
  _phase_names[full_gc_adjust_pointers]         = "  Adjust Pointers";
  _phase_names[full_gc_copy_objects]            = "  Copy Objects";
  _phase_names[full_gc_copy_objects_regular]    = "    Regular Objects";
  _phase_names[full_gc_copy_objects_humong]     = "    Humongous Objects";
  _phase_names[full_gc_copy_objects_reset_next]     = "    Reset Next Bitmap";
  _phase_names[full_gc_copy_objects_reset_complete] = "    Reset Complete Bitmap";
  _phase_names[full_gc_copy_objects_rebuild]     = "    Rebuild Region Sets";
  _phase_names[full_gc_resize_tlabs]            = "  Resize TLABs";

  _phase_names[init_traversal_gc_gross]           = "Pause Init Traversal (G)";
  _phase_names[init_traversal_gc]                 = "Pause Init Traversal (N)";
  _phase_names[traversal_gc_prepare]              = "  Prepare";
  _phase_names[traversal_gc_accumulate_stats]     = "    Accumulate Stats";
  _phase_names[traversal_gc_make_parsable]        = "    Make Parsable";
  _phase_names[traversal_gc_resize_tlabs]         = "    Resize TLABs";
  _phase_names[init_traversal_gc_work]            = "  Work";
  _phase_names[init_traversal_gc_thread_roots]        = "    TI: Thread Roots";
  _phase_names[init_traversal_gc_code_roots]          = "    TI: Code Cache Roots";
  _phase_names[init_traversal_gc_string_table_roots]  = "    TI: String Table Roots";
  _phase_names[init_traversal_gc_universe_roots]      = "    TI: Universe Roots";
  _phase_names[init_traversal_gc_jni_roots]           = "    TI: JNI Roots";
  _phase_names[init_traversal_gc_jni_weak_roots]      = "    TI: JNI Weak Roots";
  _phase_names[init_traversal_gc_synchronizer_roots]  = "    TI: Synchronizer Roots";
  _phase_names[init_traversal_gc_flat_profiler_roots] = "    TI: Flat Profiler Roots";
  _phase_names[init_traversal_gc_management_roots]    = "    TI: Management Roots";
  _phase_names[init_traversal_gc_system_dict_roots]   = "    TI: System Dict Roots";
  _phase_names[init_traversal_gc_cldg_roots]          = "    TI: CLDG Roots";
  _phase_names[init_traversal_gc_jvmti_roots]         = "    TI: JVMTI Roots";
  _phase_names[init_traversal_gc_string_dedup_table_roots]
                                                      = "    TI: Dedup Table Roots";
  _phase_names[init_traversal_gc_string_dedup_queue_roots]
                                                      = "    TI: Dedup Queue Roots";
  _phase_names[init_traversal_gc_finish_queues]       = "    TI: Finish Queues";
  _phase_names[final_traversal_gc_gross]          = "Pause Final Traversal (G)";
  _phase_names[final_traversal_gc]                = "Pause Final Traversal (N)";
  _phase_names[final_traversal_gc_work]           = "  Work";
  _phase_names[final_traversal_gc_thread_roots]        = "    TF: Thread Roots";
  _phase_names[final_traversal_gc_code_roots]          = "    TF: Code Cache Roots";
  _phase_names[final_traversal_gc_string_table_roots]  = "    TF: String Table Roots";
  _phase_names[final_traversal_gc_universe_roots]      = "    TF: Universe Roots";
  _phase_names[final_traversal_gc_jni_roots]           = "    TF: JNI Roots";
  _phase_names[final_traversal_gc_jni_weak_roots]      = "    TF: JNI Weak Roots";
  _phase_names[final_traversal_gc_synchronizer_roots]  = "    TF: Synchronizer Roots";
  _phase_names[final_traversal_gc_flat_profiler_roots] = "    TF: Flat Profiler Roots";
  _phase_names[final_traversal_gc_management_roots]    = "    TF: Management Roots";
  _phase_names[final_traversal_gc_system_dict_roots]   = "    TF: System Dict Roots";
  _phase_names[final_traversal_gc_cldg_roots]          = "    TF: CLDG Roots";
  _phase_names[final_traversal_gc_jvmti_roots]         = "    TF: JVMTI Roots";
  _phase_names[final_traversal_gc_string_dedup_table_roots]
                                                       = "    TF: Dedup Table Roots";
  _phase_names[final_traversal_gc_string_dedup_queue_roots]
                                                       = "    TF: Dedup Queue Roots";
  _phase_names[final_traversal_gc_finish_queues]       = "    TF: Finish Queues";
  _phase_names[final_traversal_gc_termination]         = "    TF:   Termination";
  _phase_names[final_traversal_update_roots]           = "  Update Roots";
  _phase_names[final_traversal_update_thread_roots]        = "    TU: Thread Roots";
  _phase_names[final_traversal_update_code_roots]          = "    TU: Code Cache Roots";
  _phase_names[final_traversal_update_string_table_roots]  = "    TU: String Table Roots";
  _phase_names[final_traversal_update_universe_roots]      = "    TU: Universe Roots";
  _phase_names[final_traversal_update_jni_roots]           = "    TU: JNI Roots";
  _phase_names[final_traversal_update_jni_weak_roots]      = "    TU: JNI Weak Roots";
  _phase_names[final_traversal_update_synchronizer_roots]  = "    TU: Synchronizer Roots";
  _phase_names[final_traversal_update_flat_profiler_roots] = "    TU: Flat Profiler Roots";
  _phase_names[final_traversal_update_management_roots]    = "    TU: Management Roots";
  _phase_names[final_traversal_update_system_dict_roots]   = "    TU: System Dict Roots";
  _phase_names[final_traversal_update_cldg_roots]          = "    TU: CLDG Roots";
  _phase_names[final_traversal_update_jvmti_roots]         = "    TU: JVMTI Roots";
  _phase_names[final_traversal_update_string_dedup_table_roots]
                                                           = "    TU: Dedup Table Roots";
  _phase_names[final_traversal_update_string_dedup_queue_roots]
                                                           = "    TU: Dedup Queue Roots";
  _phase_names[final_traversal_update_finish_queues]       = "    TU: Finish Queues";

  _phase_names[traversal_gc_cleanup]              = "  Cleanup";

  _phase_names[pause_other]                     = "Pause Other";

  _phase_names[conc_mark]                       = "Concurrent Marking";
  _phase_names[conc_termination]                = "  Termination";
  _phase_names[conc_preclean]                   = "Concurrent Precleaning";
  _phase_names[conc_evac]                       = "Concurrent Evacuation";
  _phase_names[conc_cleanup]                    = "Concurrent Cleanup";
  _phase_names[conc_cleanup_recycle]            = "  Recycle";
  _phase_names[conc_cleanup_reset_bitmaps]      = "  Reset Bitmaps";
  _phase_names[conc_other]                      = "Concurrent Other";
  _phase_names[conc_traversal]                  = "Concurrent Traversal";
  _phase_names[conc_traversal_termination]      = "  Termination";

  _phase_names[conc_uncommit]                   = "Concurrent Uncommit";

  _phase_names[init_update_refs_gross]          = "Pause Init  Update Refs (G)";
  _phase_names[init_update_refs]                = "Pause Init  Update Refs (N)";
  _phase_names[conc_update_refs]                = "Concurrent Update Refs";
  _phase_names[final_update_refs_gross]         = "Pause Final Update Refs (G)";
  _phase_names[final_update_refs]               = "Pause Final Update Refs (N)";

  _phase_names[final_update_refs_finish_work]          = "  Finish Work";
  _phase_names[final_update_refs_roots]                = "  Update Roots";
  _phase_names[final_update_refs_thread_roots]         = "    UR: Thread Roots";
  _phase_names[final_update_refs_code_roots]           = "    UR: Code Cache Roots";
  _phase_names[final_update_refs_string_table_roots]   = "    UR: String Table Roots";
  _phase_names[final_update_refs_universe_roots]       = "    UR: Universe Roots";
  _phase_names[final_update_refs_jni_roots]            = "    UR: JNI Roots";
  _phase_names[final_update_refs_jni_weak_roots]       = "    UR: JNI Weak Roots";
  _phase_names[final_update_refs_synchronizer_roots]   = "    UR: Synchronizer Roots";
  _phase_names[final_update_refs_flat_profiler_roots]  = "    UR: Flat Profiler Roots";
  _phase_names[final_update_refs_management_roots]     = "    UR: Management Roots";
  _phase_names[final_update_refs_system_dict_roots]    = "    UR: System Dict Roots";
  _phase_names[final_update_refs_cldg_roots]           = "    UR: CLDG Roots";
  _phase_names[final_update_refs_jvmti_roots]          = "    UR: JVMTI Roots";
  _phase_names[final_update_refs_string_dedup_table_roots]
                                                       = "    UR: Dedup Table Roots";
  _phase_names[final_update_refs_string_dedup_queue_roots]
                                                       = "    UR: Dedup Queue Roots";
  _phase_names[final_update_refs_finish_queues]        = "    UR: Finish Queues";
  _phase_names[final_update_refs_recycle]              = "  Recycle";
}

ShenandoahWorkerTimings::ShenandoahWorkerTimings(uint max_gc_threads) :
        _max_gc_threads(max_gc_threads)
{
  assert(max_gc_threads > 0, "Must have some GC threads");

  // Root scanning phases
  _gc_par_phases[ShenandoahPhaseTimings::ThreadRoots]             = new WorkerDataArray<double>(max_gc_threads, "Thread Roots (ms):");
  _gc_par_phases[ShenandoahPhaseTimings::CodeCacheRoots]          = new WorkerDataArray<double>(max_gc_threads, "CodeCache Roots (ms):");
  _gc_par_phases[ShenandoahPhaseTimings::StringTableRoots]        = new WorkerDataArray<double>(max_gc_threads, "StringTable Roots (ms):");
  _gc_par_phases[ShenandoahPhaseTimings::UniverseRoots]           = new WorkerDataArray<double>(max_gc_threads, "Universe Roots (ms):");
  _gc_par_phases[ShenandoahPhaseTimings::JNIRoots]                = new WorkerDataArray<double>(max_gc_threads, "JNI Handles Roots (ms):");
  _gc_par_phases[ShenandoahPhaseTimings::JNIWeakRoots]            = new WorkerDataArray<double>(max_gc_threads, "JNI Weak Roots (ms):");
  _gc_par_phases[ShenandoahPhaseTimings::ObjectSynchronizerRoots] = new WorkerDataArray<double>(max_gc_threads, "ObjectSynchronizer Roots (ms):");
  _gc_par_phases[ShenandoahPhaseTimings::FlatProfilerRoots]       = new WorkerDataArray<double>(max_gc_threads, "FlatProfiler Roots (ms):");
  _gc_par_phases[ShenandoahPhaseTimings::ManagementRoots]         = new WorkerDataArray<double>(max_gc_threads, "Management Roots (ms):");
  _gc_par_phases[ShenandoahPhaseTimings::SystemDictionaryRoots]   = new WorkerDataArray<double>(max_gc_threads, "SystemDictionary Roots (ms):");
  _gc_par_phases[ShenandoahPhaseTimings::CLDGRoots]               = new WorkerDataArray<double>(max_gc_threads, "CLDG Roots (ms):");
  _gc_par_phases[ShenandoahPhaseTimings::JVMTIRoots]              = new WorkerDataArray<double>(max_gc_threads, "JVMTI Roots (ms):");
  _gc_par_phases[ShenandoahPhaseTimings::StringDedupTableRoots]   = new WorkerDataArray<double>(max_gc_threads, "String Dedup Table Roots (ms):");
  _gc_par_phases[ShenandoahPhaseTimings::StringDedupQueueRoots]   = new WorkerDataArray<double>(max_gc_threads, "String Dedup Queue Roots (ms):");
  _gc_par_phases[ShenandoahPhaseTimings::FinishQueues]            = new WorkerDataArray<double>(max_gc_threads, "Finish Queues (ms):");
}

// record the time a phase took in seconds
void ShenandoahWorkerTimings::record_time_secs(ShenandoahPhaseTimings::GCParPhases phase, uint worker_i, double secs) {
  _gc_par_phases[phase]->set(worker_i, secs);
}

double ShenandoahWorkerTimings::average(uint i) const {
  return _gc_par_phases[i]->average();
}

void ShenandoahWorkerTimings::reset(uint i) {
  _gc_par_phases[i]->reset();
}

void ShenandoahWorkerTimings::print() const {
  for (uint i = 0; i < ShenandoahPhaseTimings::GCParPhasesSentinel; i++) {
    _gc_par_phases[i]->print_summary_on(tty);
  }
}

ShenandoahWorkerTimingsTracker::ShenandoahWorkerTimingsTracker(ShenandoahWorkerTimings* worker_times,
                                                               ShenandoahPhaseTimings::GCParPhases phase, uint worker_id) :
        _phase(phase), _worker_times(worker_times), _worker_id(worker_id) {
  if (_worker_times != NULL) {
    _start_time = os::elapsedTime();
  }
}

ShenandoahWorkerTimingsTracker::~ShenandoahWorkerTimingsTracker() {
  if (_worker_times != NULL) {
    _worker_times->record_time_secs(_phase, _worker_id, os::elapsedTime() - _start_time);
  }
}

ShenandoahTerminationTimings::ShenandoahTerminationTimings(uint max_gc_threads) {
  _gc_termination_phase = new WorkerDataArray<double>(max_gc_threads, "Task Termination (ms):");
}

void ShenandoahTerminationTimings::record_time_secs(uint worker_id, double secs) {
  if (_gc_termination_phase->get(worker_id) == WorkerDataArray<double>::uninitialized()) {
    _gc_termination_phase->set(worker_id, secs);
  } else {
    // worker may re-enter termination phase
    _gc_termination_phase->add(worker_id, secs);
  }
}

void ShenandoahTerminationTimings::print() const {
  _gc_termination_phase->print_summary_on(tty);
}

ShenandoahTerminationTimingsTracker::ShenandoahTerminationTimingsTracker(uint worker_id) :
  _worker_id(worker_id)  {
  if (ShenandoahTerminationTrace) {
    _start_time = os::elapsedTime();
  }
}

ShenandoahTerminationTimingsTracker::~ShenandoahTerminationTimingsTracker() {
  if (ShenandoahTerminationTrace) {
    ShenandoahHeap::heap()->phase_timings()->termination_times()->record_time_secs(_worker_id, os::elapsedTime() - _start_time);
  }
}

ShenandoahPhaseTimings::Phase ShenandoahTerminationTracker::currentPhase = ShenandoahPhaseTimings::_num_phases;

ShenandoahTerminationTracker::ShenandoahTerminationTracker(ShenandoahPhaseTimings::Phase phase) : _phase(phase) {
  assert(currentPhase == ShenandoahPhaseTimings::_num_phases, "Should be invalid");
  assert(phase == ShenandoahPhaseTimings::termination ||
         phase == ShenandoahPhaseTimings::final_traversal_gc_termination ||
         phase == ShenandoahPhaseTimings::full_gc_mark_termination ||
         phase == ShenandoahPhaseTimings::conc_termination ||
         phase == ShenandoahPhaseTimings::conc_traversal_termination ||
         phase == ShenandoahPhaseTimings::weakrefs_termination ||
         phase == ShenandoahPhaseTimings::full_gc_weakrefs_termination,
         "Only these phases");

  assert(Thread::current()->is_VM_thread() || Thread::current()->is_ConcurrentGC_thread(),
    "Called from wrong thread");

  currentPhase = phase;
  ShenandoahHeap::heap()->phase_timings()->termination_times()->reset();
}

ShenandoahTerminationTracker::~ShenandoahTerminationTracker() {
  assert(_phase == currentPhase, "Can not change phase");
  ShenandoahPhaseTimings* phase_times = ShenandoahHeap::heap()->phase_timings();

  double t = phase_times->termination_times()->average();
  phase_times->record_phase_time(_phase, t * 1000 * 1000);
  debug_only(currentPhase = ShenandoahPhaseTimings::_num_phases;)
}
