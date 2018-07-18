
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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHPHASETIMEINGS_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHPHASETIMEINGS_HPP

#include "gc/shared/workerDataArray.hpp"
#include "memory/allocation.hpp"
#include "utilities/numberSeq.hpp"

class ShenandoahCollectorPolicy;
class ShenandoahWorkerTimings;
class ShenandoahTerminationTimings;
class outputStream;

class ShenandoahPhaseTimings : public CHeapObj<mtGC> {
public:
  enum Phase {
    total_pause_gross,
    total_pause,

    init_mark_gross,
    init_mark,
    accumulate_stats,
    make_parsable,
    clear_liveness,

    // Per-thread timer block, should have "roots" counters in consistent order
    scan_roots,
    scan_thread_roots,
    scan_code_roots,
    scan_string_table_roots,
    scan_universe_roots,
    scan_jni_roots,
    scan_jni_weak_roots,
    scan_synchronizer_roots,
    scan_flat_profiler_roots,
    scan_management_roots,
    scan_system_dictionary_roots,
    scan_cldg_roots,
    scan_jvmti_roots,
    scan_string_dedup_table_roots,
    scan_string_dedup_queue_roots,
    scan_finish_queues,

    resize_tlabs,

    final_mark_gross,
    final_mark,

    // Per-thread timer block, should have "roots" counters in consistent order
    update_roots,
    update_thread_roots,
    update_code_roots,
    update_string_table_roots,
    update_universe_roots,
    update_jni_roots,
    update_jni_weak_roots,
    update_synchronizer_roots,
    update_flat_profiler_roots,
    update_management_roots,
    update_system_dictionary_roots,
    update_cldg_roots,
    update_jvmti_roots,
    update_string_dedup_table_roots,
    update_string_dedup_queue_roots,
    update_finish_queues,

    finish_queues,
    termination,
    weakrefs,
    weakrefs_process,
    weakrefs_termination,
    purge,
    purge_class_unload,
    purge_par,
    purge_par_codecache,
    purge_par_symbstring,
    purge_par_rmt,
    purge_par_classes,
    purge_par_sync,
    purge_cldg,
    purge_string_dedup,
    complete_liveness,
    prepare_evac,
    recycle_regions,

    // Per-thread timer block, should have "roots" counters in consistent order
    init_evac,
    evac_thread_roots,
    evac_code_roots,
    evac_string_table_roots,
    evac_universe_roots,
    evac_jni_roots,
    evac_jni_weak_roots,
    evac_synchronizer_roots,
    evac_flat_profiler_roots,
    evac_management_roots,
    evac_system_dictionary_roots,
    evac_cldg_roots,
    evac_jvmti_roots,
    evac_string_dedup_table_roots,
    evac_string_dedup_queue_roots,
    evac_finish_queues,

    final_evac_gross,
    final_evac,

    init_update_refs_gross,
    init_update_refs,

    final_update_refs_gross,
    final_update_refs,
    final_update_refs_finish_work,

    // Per-thread timer block, should have "roots" counters in consistent order
    final_update_refs_roots,
    final_update_refs_thread_roots,
    final_update_refs_code_roots,
    final_update_refs_string_table_roots,
    final_update_refs_universe_roots,
    final_update_refs_jni_roots,
    final_update_refs_jni_weak_roots,
    final_update_refs_synchronizer_roots,
    final_update_refs_flat_profiler_roots,
    final_update_refs_management_roots,
    final_update_refs_system_dict_roots,
    final_update_refs_cldg_roots,
    final_update_refs_jvmti_roots,
    final_update_refs_string_dedup_table_roots,
    final_update_refs_string_dedup_queue_roots,
    final_update_refs_finish_queues,

    final_update_refs_recycle,

    degen_gc_gross,
    degen_gc,

    init_traversal_gc_gross,
    init_traversal_gc,
    traversal_gc_prepare,
    traversal_gc_accumulate_stats,
    traversal_gc_make_parsable,
    traversal_gc_resize_tlabs,

    // Per-thread timer block, should have "roots" counters in consistent order
    init_traversal_gc_work,
    init_traversal_gc_thread_roots,
    init_traversal_gc_code_roots,
    init_traversal_gc_string_table_roots,
    init_traversal_gc_universe_roots,
    init_traversal_gc_jni_roots,
    init_traversal_gc_jni_weak_roots,
    init_traversal_gc_synchronizer_roots,
    init_traversal_gc_flat_profiler_roots,
    init_traversal_gc_management_roots,
    init_traversal_gc_system_dict_roots,
    init_traversal_gc_cldg_roots,
    init_traversal_gc_jvmti_roots,
    init_traversal_gc_string_dedup_table_roots,
    init_traversal_gc_string_dedup_queue_roots,
    init_traversal_gc_finish_queues,

    final_traversal_gc_gross,
    final_traversal_gc,

    // Per-thread timer block, should have "roots" counters in consistent order
    final_traversal_gc_work,
    final_traversal_gc_thread_roots,
    final_traversal_gc_code_roots,
    final_traversal_gc_string_table_roots,
    final_traversal_gc_universe_roots,
    final_traversal_gc_jni_roots,
    final_traversal_gc_jni_weak_roots,
    final_traversal_gc_synchronizer_roots,
    final_traversal_gc_flat_profiler_roots,
    final_traversal_gc_management_roots,
    final_traversal_gc_system_dict_roots,
    final_traversal_gc_cldg_roots,
    final_traversal_gc_jvmti_roots,
    final_traversal_gc_string_dedup_table_roots,
    final_traversal_gc_string_dedup_queue_roots,
    final_traversal_gc_finish_queues,
    final_traversal_gc_termination,

    // Per-thread timer block, should have "roots" counters in consistent order
    final_traversal_update_roots,
    final_traversal_update_thread_roots,
    final_traversal_update_code_roots,
    final_traversal_update_string_table_roots,
    final_traversal_update_universe_roots,
    final_traversal_update_jni_roots,
    final_traversal_update_jni_weak_roots,
    final_traversal_update_synchronizer_roots,
    final_traversal_update_flat_profiler_roots,
    final_traversal_update_management_roots,
    final_traversal_update_system_dict_roots,
    final_traversal_update_cldg_roots,
    final_traversal_update_jvmti_roots,
    final_traversal_update_string_dedup_table_roots,
    final_traversal_update_string_dedup_queue_roots,
    final_traversal_update_finish_queues,

    traversal_gc_cleanup,

    full_gc_gross,
    full_gc,
    full_gc_heapdumps,
    full_gc_prepare,

    // Per-thread timer block, should have "roots" counters in consistent order
    full_gc_roots,
    full_gc_thread_roots,
    full_gc_code_roots,
    full_gc_string_table_roots,
    full_gc_universe_roots,
    full_gc_jni_roots,
    full_gc_jni_weak_roots,
    full_gc_synchronizer_roots,
    full_gc_flat_profiler_roots,
    full_gc_management_roots,
    full_gc_system_dictionary_roots,
    full_gc_cldg_roots,
    full_gc_jvmti_roots,
    full_gc_string_dedup_table_roots,
    full_gc_string_dedup_queue_roots,
    full_gc_finish_queues,

    full_gc_mark,
    full_gc_mark_finish_queues,
    full_gc_mark_termination,
    full_gc_weakrefs,
    full_gc_weakrefs_process,
    full_gc_weakrefs_termination,
    full_gc_purge,
    full_gc_purge_class_unload,
    full_gc_purge_par,
    full_gc_purge_par_codecache,
    full_gc_purge_par_symbstring,
    full_gc_purge_par_rmt,
    full_gc_purge_par_classes,
    full_gc_purge_par_sync,
    full_gc_purge_cldg,
    full_gc_purge_string_dedup,
    full_gc_calculate_addresses,
    full_gc_calculate_addresses_regular,
    full_gc_calculate_addresses_humong,
    full_gc_adjust_pointers,
    full_gc_copy_objects,
    full_gc_copy_objects_regular,
    full_gc_copy_objects_humong,
    full_gc_copy_objects_reset_next,
    full_gc_copy_objects_reset_complete,
    full_gc_copy_objects_rebuild,
    full_gc_resize_tlabs,

    // Longer concurrent phases at the end
    conc_mark,
    conc_termination,
    conc_preclean,
    conc_evac,
    conc_update_refs,
    conc_cleanup,
    conc_cleanup_recycle,
    conc_cleanup_reset_bitmaps,
    conc_traversal,
    conc_traversal_termination,

    conc_uncommit,

    // Unclassified
    pause_other,
    conc_other,

    _num_phases
  };


  // These are the subphases of GC phases (scan_roots, update_roots,
  // init_evac, final_update_refs_roots and full_gc_roots).
  // Make sure they are following this order.
  enum GCParPhases {
    ThreadRoots,
    CodeCacheRoots,
    StringTableRoots,
    UniverseRoots,
    JNIRoots,
    JNIWeakRoots,
    ObjectSynchronizerRoots,
    FlatProfilerRoots,
    ManagementRoots,
    SystemDictionaryRoots,
    CLDGRoots,
    JVMTIRoots,
    StringDedupTableRoots,
    StringDedupQueueRoots,
    FinishQueues,
    GCParPhasesSentinel
  };

private:
  struct TimingData {
    HdrSeq _secs;
    double _start;
  };

private:
  TimingData _timing_data[_num_phases];
  const char* _phase_names[_num_phases];

  ShenandoahWorkerTimings*      _worker_times;
  ShenandoahTerminationTimings* _termination_times;

  ShenandoahCollectorPolicy* _policy;

public:
  ShenandoahPhaseTimings();

  ShenandoahWorkerTimings* const worker_times() const { return _worker_times; }
  ShenandoahTerminationTimings* const termination_times() const { return _termination_times; }

  // record phase start
  void record_phase_start(Phase phase);
  // record phase end and return elapsed time in seconds for the phase
  void record_phase_end(Phase phase);
  // record an elapsed time in microseconds for the phase
  void record_phase_time(Phase phase, jint time_us);

  void record_workers_start(Phase phase);
  void record_workers_end(Phase phase);

  void print_on(outputStream* out) const;

private:
  void init_phase_names();
  void print_summary_sd(outputStream* out, const char* str, const HdrSeq* seq) const;
};

class ShenandoahWorkerTimings : public CHeapObj<mtGC> {
private:
  uint _max_gc_threads;
  WorkerDataArray<double>* _gc_par_phases[ShenandoahPhaseTimings::GCParPhasesSentinel];

public:
  ShenandoahWorkerTimings(uint max_gc_threads);

  // record the time a phase took in seconds
  void record_time_secs(ShenandoahPhaseTimings::GCParPhases phase, uint worker_i, double secs);

  double average(uint i) const;
  void reset(uint i);
  void print() const;
};

class ShenandoahWorkerTimingsTracker : public StackObj {
  double _start_time;
  ShenandoahPhaseTimings::GCParPhases _phase;
  ShenandoahWorkerTimings* _worker_times;
  uint _worker_id;
public:
  ShenandoahWorkerTimingsTracker(ShenandoahWorkerTimings* worker_times, ShenandoahPhaseTimings::GCParPhases phase, uint worker_id);
  ~ShenandoahWorkerTimingsTracker();
};

class ShenandoahTerminationTimings : public CHeapObj<mtGC> {
private:
  WorkerDataArray<double>* _gc_termination_phase;
public:
  ShenandoahTerminationTimings(uint max_gc_threads);

  // record the time a phase took in seconds
  void record_time_secs(uint worker_i, double secs);

  double average() const { return _gc_termination_phase->average(); }
  void reset() { _gc_termination_phase->reset(); }

  void print() const;
};

class ShenandoahTerminationTimingsTracker : public StackObj {
private:
  double _start_time;
  uint   _worker_id;

public:
  ShenandoahTerminationTimingsTracker(uint worker_id);
  ~ShenandoahTerminationTimingsTracker();
};


// Tracking termination time in specific GC phase
class ShenandoahTerminationTracker : public StackObj {
private:
  ShenandoahPhaseTimings::Phase _phase;

  static ShenandoahPhaseTimings::Phase currentPhase;
public:
  ShenandoahTerminationTracker(ShenandoahPhaseTimings::Phase phase);
  ~ShenandoahTerminationTracker();
};

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHGCPHASETIMEINGS_HPP
