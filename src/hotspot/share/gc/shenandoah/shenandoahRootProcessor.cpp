/*
 * Copyright (c) 2015, Red Hat, Inc. and/or its affiliates.
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

#include "classfile/javaClasses.hpp"
#include "classfile/stringTable.hpp"
#include "classfile/systemDictionary.hpp"
#include "code/codeCache.hpp"
#include "gc/shenandoah/shenandoahRootProcessor.hpp"
#include "gc/shenandoah/shenandoahHeap.hpp"
#include "gc/shenandoah/shenandoahBarrierSet.hpp"
#include "gc/shenandoah/shenandoahPhaseTimings.hpp"
#include "gc/shenandoah/vm_operations_shenandoah.hpp"
#include "memory/allocation.inline.hpp"
#include "runtime/mutex.hpp"
#include "runtime/sweeper.hpp"
#include "runtime/vmThread.hpp"
#include "services/management.hpp"

ShenandoahRootProcessor::ShenandoahRootProcessor(ShenandoahHeap* heap, uint n_workers,
                                                 ShenandoahPhaseTimings::Phase phase) :
  _process_strong_tasks(new SubTasksDone(SHENANDOAH_RP_PS_NumElements)),
  _srs(n_workers),
  _phase(phase),
  _coderoots_all_iterator(ShenandoahCodeRoots::iterator()),
  _om_iterator(ObjectSynchronizer::parallel_iterator()),
  _threads_nmethods_cl(NULL)
{
  heap->phase_timings()->record_workers_start(_phase);
  VM_ShenandoahOperation* op = (VM_ShenandoahOperation*) VMThread::vm_operation();
  if (op == NULL || !op->_safepoint_cleanup_done) {
    _threads_nmethods_cl = NMethodSweeper::prepare_mark_active_nmethods();
  }
}

ShenandoahRootProcessor::~ShenandoahRootProcessor() {
  delete _process_strong_tasks;
  ShenandoahHeap::heap()->phase_timings()->record_workers_end(_phase);
  VM_ShenandoahOperation* op = (VM_ShenandoahOperation*) VMThread::vm_operation();
  if (op != NULL) {
    op->_safepoint_cleanup_done = true;
  }
}

void ShenandoahRootProcessor::process_all_roots_slow(OopClosure* oops) {
  ShenandoahAlwaysTrueClosure always_true;

  CLDToOopClosure clds(oops);
  CodeBlobToOopClosure blobs(oops, !CodeBlobToOopClosure::FixRelocations);

  Threads::possibly_parallel_oops_do(false, oops, &blobs);
  CodeCache::blobs_do(&blobs);
  ClassLoaderDataGraph::cld_do(&clds);
  Universe::oops_do(oops);
  Management::oops_do(oops);
  JvmtiExport::oops_do(oops);
  JNIHandles::oops_do(oops);
  JNIHandles::weak_oops_do(&always_true, oops);
  ObjectSynchronizer::oops_do(oops);
  SystemDictionary::roots_oops_do(oops, oops);
  StringTable::oops_do(oops);
}

void ShenandoahRootProcessor::process_strong_roots(OopClosure* oops,
                                                   OopClosure* weak_oops,
                                                   CLDClosure* clds,
                                                   CodeBlobClosure* blobs,
                                                   uint worker_id) {

  process_java_roots(oops, clds, NULL, blobs, _threads_nmethods_cl, worker_id);
  process_vm_roots(oops, NULL, weak_oops, worker_id);

  _process_strong_tasks->all_tasks_completed(n_workers());
}

void ShenandoahRootProcessor::process_all_roots(OopClosure* oops,
                                                OopClosure* weak_oops,
                                                CLDClosure* clds,
                                                CodeBlobClosure* blobs,
                                                uint worker_id) {

  ShenandoahWorkerTimings* worker_times = ShenandoahHeap::heap()->phase_timings()->worker_times();
  process_java_roots(oops, clds, clds, blobs, _threads_nmethods_cl, worker_id);
  process_vm_roots(oops, oops, weak_oops, worker_id);

  if (blobs != NULL) {
    ShenandoahWorkerTimingsTracker timer(worker_times, ShenandoahPhaseTimings::CodeCacheRoots, worker_id);
    _coderoots_all_iterator.possibly_parallel_blobs_do(blobs);
  }

  _process_strong_tasks->all_tasks_completed(n_workers());
}

void ShenandoahRootProcessor::process_java_roots(OopClosure* strong_roots,
                                                 CLDClosure* strong_clds,
                                                 CLDClosure* weak_clds,
                                                 CodeBlobClosure* strong_code,
                                                 CodeBlobClosure* nmethods_cl,
                                                 uint worker_id)
{
  ShenandoahWorkerTimings* worker_times = ShenandoahHeap::heap()->phase_timings()->worker_times();
  // Iterating over the CLDG and the Threads are done early to allow us to
  // first process the strong CLDs and nmethods and then, after a barrier,
  // let the thread process the weak CLDs and nmethods.
  {
    ShenandoahWorkerTimingsTracker timer(worker_times, ShenandoahPhaseTimings::CLDGRoots, worker_id);
    _cld_iterator.root_cld_do(strong_clds, weak_clds);
  }

  {
    ShenandoahWorkerTimingsTracker timer(worker_times, ShenandoahPhaseTimings::ThreadRoots, worker_id);
    bool is_par = n_workers() > 1;
    ResourceMark rm;
    Threads::possibly_parallel_oops_do(is_par, strong_roots, strong_code, nmethods_cl);
  }
}

void ShenandoahRootProcessor::process_vm_roots(OopClosure* strong_roots,
                                               OopClosure* weak_roots,
                                               OopClosure* jni_weak_roots,
                                               uint worker_id)
{
  ShenandoahWorkerTimings* worker_times = ShenandoahHeap::heap()->phase_timings()->worker_times();
  if (!_process_strong_tasks->is_task_claimed(SHENANDOAH_RP_PS_Universe_oops_do)) {
    ShenandoahWorkerTimingsTracker timer(worker_times, ShenandoahPhaseTimings::UniverseRoots, worker_id);
    Universe::oops_do(strong_roots);
  }

  if (!_process_strong_tasks->is_task_claimed(SHENANDOAH_RP_PS_JNIHandles_oops_do)) {
    ShenandoahWorkerTimingsTracker timer(worker_times, ShenandoahPhaseTimings::JNIRoots, worker_id);
    JNIHandles::oops_do(strong_roots);
  }
  if (!_process_strong_tasks->is_task_claimed(SHENANDOAH_RP_PS_Management_oops_do)) {
    ShenandoahWorkerTimingsTracker timer(worker_times, ShenandoahPhaseTimings::ManagementRoots, worker_id);
    Management::oops_do(strong_roots);
  }
  if (!_process_strong_tasks->is_task_claimed(SHENANDOAH_RP_PS_jvmti_oops_do)) {
    ShenandoahWorkerTimingsTracker timer(worker_times, ShenandoahPhaseTimings::JVMTIRoots, worker_id);
    JvmtiExport::oops_do(strong_roots);
  }
  if (!_process_strong_tasks->is_task_claimed(SHENANDOAH_RP_PS_SystemDictionary_oops_do)) {
    ShenandoahWorkerTimingsTracker timer(worker_times, ShenandoahPhaseTimings::SystemDictionaryRoots, worker_id);
    SystemDictionary::roots_oops_do(strong_roots, weak_roots);
  }
  if (jni_weak_roots != NULL) {
    if (!_process_strong_tasks->is_task_claimed(SHENANDOAH_RP_PS_JNIHandles_weak_oops_do)) {
      ShenandoahAlwaysTrueClosure always_true;
      ShenandoahWorkerTimingsTracker timer(worker_times, ShenandoahPhaseTimings::JNIWeakRoots, worker_id);
      JNIHandles::weak_oops_do(&always_true, jni_weak_roots);
    }
  }

  {
    ShenandoahWorkerTimingsTracker timer(worker_times, ShenandoahPhaseTimings::ObjectSynchronizerRoots, worker_id);
    if (ShenandoahFastSyncRoots && MonitorInUseLists) {
      if (!_process_strong_tasks->is_task_claimed(SHENANDOAH_RP_PS_ObjectSynchronizer_oops_do)) {
        ObjectSynchronizer::oops_do(strong_roots);
      }
    } else {
      while(_om_iterator.parallel_oops_do(strong_roots));
    }
  }

  // All threads execute the following. A specific chunk of buckets
  // from the StringTable are the individual tasks.
  if (weak_roots != NULL) {
    ShenandoahWorkerTimingsTracker timer(worker_times, ShenandoahPhaseTimings::StringTableRoots, worker_id);
    StringTable::possibly_parallel_oops_do(weak_roots);
  }
}

uint ShenandoahRootProcessor::n_workers() const {
  return _srs.n_threads();
}

ShenandoahRootEvacuator::ShenandoahRootEvacuator(ShenandoahHeap* heap, uint n_workers, ShenandoahPhaseTimings::Phase phase) :
  _process_strong_tasks(new SubTasksDone(SHENANDOAH_RP_PS_NumElements)),
  _srs(n_workers),
  _phase(phase),
  _coderoots_cset_iterator(ShenandoahCodeRoots::cset_iterator()),
  _threads_nmethods_cl(NULL)
{
  heap->phase_timings()->record_workers_start(_phase);
  VM_ShenandoahOperation* op = (VM_ShenandoahOperation*) VMThread::vm_operation();
  if (op == NULL || !op->_safepoint_cleanup_done) {
    _threads_nmethods_cl = NMethodSweeper::prepare_mark_active_nmethods();
  }
}

ShenandoahRootEvacuator::~ShenandoahRootEvacuator() {
  delete _process_strong_tasks;
  ShenandoahHeap::heap()->phase_timings()->record_workers_end(_phase);
  VM_ShenandoahOperation* op = (VM_ShenandoahOperation*) VMThread::vm_operation();
  if (op != NULL) {
    op->_safepoint_cleanup_done = true;
  }
}

void ShenandoahRootEvacuator::process_evacuate_roots(OopClosure* oops,
                                                     CodeBlobClosure* blobs,
                                                     uint worker_id) {

  ShenandoahWorkerTimings* worker_times = ShenandoahHeap::heap()->phase_timings()->worker_times();
  {
    bool is_par = n_workers() > 1;
    ResourceMark rm;
    ShenandoahWorkerTimingsTracker timer(worker_times, ShenandoahPhaseTimings::ThreadRoots, worker_id);

    Threads::possibly_parallel_oops_do(is_par, oops, NULL, _threads_nmethods_cl);
  }

  if (blobs != NULL) {
    ShenandoahWorkerTimingsTracker timer(worker_times, ShenandoahPhaseTimings::CodeCacheRoots, worker_id);
    _coderoots_cset_iterator.possibly_parallel_blobs_do(blobs);
  }

  _process_strong_tasks->all_tasks_completed(n_workers());
}

uint ShenandoahRootEvacuator::n_workers() const {
  return _srs.n_threads();
}

// Implemenation of ParallelCLDRootIterator
ParallelCLDRootIterator::ParallelCLDRootIterator() {
  assert(SafepointSynchronize::is_at_safepoint(), "Must at safepoint");
  ClassLoaderDataGraph::clear_claimed_marks();
}

void ParallelCLDRootIterator::root_cld_do(CLDClosure* strong, CLDClosure* weak) {
    ClassLoaderDataGraph::roots_cld_do(strong, weak);
}

