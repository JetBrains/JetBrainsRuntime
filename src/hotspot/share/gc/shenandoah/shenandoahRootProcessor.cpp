/*
 * Copyright (c) 2015, 2018, Red Hat, Inc. All rights reserved.
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

#include "classfile/classLoaderData.hpp"
#include "classfile/stringTable.hpp"
#include "classfile/systemDictionary.hpp"
#include "code/codeCache.hpp"
#include "gc/shenandoah/shenandoahClosures.inline.hpp"
#include "gc/shenandoah/shenandoahRootProcessor.inline.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahPhaseTimings.hpp"
#include "gc/shenandoah/shenandoahStringDedup.hpp"
#include "gc/shenandoah/shenandoahVMOperations.hpp"
#include "gc/shenandoah/heuristics/shenandoahHeuristics.hpp"
#include "gc/shared/weakProcessor.hpp"
#include "memory/allocation.inline.hpp"
#include "memory/iterator.hpp"
#include "memory/resourceArea.hpp"
#include "runtime/thread.hpp"
#include "services/management.hpp"

ShenandoahSerialRoot::ShenandoahSerialRoot(ShenandoahSerialRoot::OopsDo oops_do,
  ShenandoahPhaseTimings::Phase phase, ShenandoahPhaseTimings::ParPhase par_phase) :
  _oops_do(oops_do), _phase(phase), _par_phase(par_phase) {
}

void ShenandoahSerialRoot::oops_do(OopClosure* cl, uint worker_id) {
  if (_claimed.try_set()) {
    ShenandoahWorkerTimingsTracker timer(_phase, _par_phase, worker_id);
    _oops_do(cl);
  }
}

ShenandoahSerialRoots::ShenandoahSerialRoots(ShenandoahPhaseTimings::Phase phase) :
  _universe_root(&ShenandoahSerialRoots::universe_oops_do, phase, ShenandoahPhaseTimings::UniverseRoots),
  _object_synchronizer_root(&ObjectSynchronizer::oops_do, phase, ShenandoahPhaseTimings::ObjectSynchronizerRoots),
  _management_root(&Management::oops_do, phase, ShenandoahPhaseTimings::ManagementRoots),
  _system_dictionary_root(&SystemDictionary::oops_do, phase, ShenandoahPhaseTimings::SystemDictionaryRoots),
  _jvmti_root(&JvmtiExport::oops_do, phase, ShenandoahPhaseTimings::JVMTIRoots) {
}

void ShenandoahSerialRoots::oops_do(OopClosure* cl, uint worker_id) {
  _universe_root.oops_do(cl, worker_id);
  _object_synchronizer_root.oops_do(cl, worker_id);
  _management_root.oops_do(cl, worker_id);
  _system_dictionary_root.oops_do(cl, worker_id);
  _jvmti_root.oops_do(cl, worker_id);
}

ShenandoahJNIHandleRoots::ShenandoahJNIHandleRoots(ShenandoahPhaseTimings::Phase phase) :
  ShenandoahSerialRoot(&JNIHandles::oops_do, phase, ShenandoahPhaseTimings::JNIRoots) {
}

ShenandoahThreadRoots::ShenandoahThreadRoots(ShenandoahPhaseTimings::Phase phase, bool is_par) :
  _phase(phase), _is_par(is_par) {
  Threads::change_thread_claim_parity();
}

void ShenandoahThreadRoots::oops_do(OopClosure* oops_cl, CodeBlobClosure* code_cl, uint worker_id) {
  ShenandoahWorkerTimingsTracker timer(_phase, ShenandoahPhaseTimings::ThreadRoots, worker_id);
  ResourceMark rm;
  Threads::possibly_parallel_oops_do(_is_par, oops_cl, code_cl);
}

void ShenandoahThreadRoots::threads_do(ThreadClosure* tc, uint worker_id) {
  ShenandoahWorkerTimingsTracker timer(_phase, ShenandoahPhaseTimings::ThreadRoots, worker_id);
  ResourceMark rm;
  Threads::possibly_parallel_threads_do(_is_par, tc);
}

ShenandoahThreadRoots::~ShenandoahThreadRoots() {
  Threads::assert_all_threads_claimed();
}

ShenandoahWeakRoots::ShenandoahWeakRoots(ShenandoahPhaseTimings::Phase phase, uint n_workers) :
  _phase(phase),
  _par_state_string(StringTable::weak_storage()),
  _claimed(false) {
}

ShenandoahWeakRoots::~ShenandoahWeakRoots() {
}

ShenandoahStringDedupRoots::ShenandoahStringDedupRoots(ShenandoahPhaseTimings::Phase phase) : _phase(phase) {
  if (ShenandoahStringDedup::is_enabled()) {
    StringDedup::gc_prologue(false);
  }
}

ShenandoahStringDedupRoots::~ShenandoahStringDedupRoots() {
  if (ShenandoahStringDedup::is_enabled()) {
    StringDedup::gc_epilogue();
  }
}

void ShenandoahStringDedupRoots::oops_do(BoolObjectClosure* is_alive, OopClosure* keep_alive, uint worker_id) {
  if (ShenandoahStringDedup::is_enabled()) {
    ShenandoahStringDedup::parallel_oops_do(_phase, is_alive, keep_alive, worker_id);
  }
}

ShenandoahRootProcessor::ShenandoahRootProcessor(ShenandoahPhaseTimings::Phase phase) :
  _heap(ShenandoahHeap::heap()),
  _phase(phase),
  _worker_phase(phase) {
  assert(SafepointSynchronize::is_at_safepoint(), "Must at safepoint");
}

ShenandoahRootEvacuator::ShenandoahRootEvacuator(uint n_workers, ShenandoahPhaseTimings::Phase phase) :
  ShenandoahRootProcessor(phase),
  _serial_roots(phase),
  _jni_roots(phase),
  _cld_roots(phase, n_workers),
  _thread_roots(phase, n_workers > 1),
  _weak_roots(phase, n_workers),
  _dedup_roots(phase),
  _code_roots(phase) {
}

void ShenandoahRootEvacuator::roots_do(uint worker_id, OopClosure* oops) {
  MarkingCodeBlobClosure blobsCl(oops, CodeBlobToOopClosure::FixRelocations);
  CLDToOopClosure clds(oops);

  AlwaysTrueClosure always_true;

  // Process serial-claiming roots first
  _serial_roots.oops_do(oops, worker_id);
  _jni_roots.oops_do(oops, worker_id);

  // Process light-weight/limited parallel roots then
  _weak_roots.oops_do<AlwaysTrueClosure, OopClosure>(&always_true, oops, worker_id);
  _dedup_roots.oops_do(&always_true, oops, worker_id);
  _cld_roots.cld_do(&clds, worker_id);

  // Process heavy-weight/fully parallel roots the last
  _code_roots.code_blobs_do(&blobsCl, worker_id);
  _thread_roots.oops_do(oops, NULL, worker_id);
}

ShenandoahRootUpdater::ShenandoahRootUpdater(uint n_workers, ShenandoahPhaseTimings::Phase phase) :
  ShenandoahRootProcessor(phase),
  _serial_roots(phase),
  _jni_roots(phase),
  _cld_roots(phase, n_workers),
  _thread_roots(phase, n_workers > 1),
  _weak_roots(phase, n_workers),
  _dedup_roots(phase),
  _code_roots(phase) {
}

ShenandoahRootAdjuster::ShenandoahRootAdjuster(uint n_workers, ShenandoahPhaseTimings::Phase phase) :
  ShenandoahRootProcessor(phase),
  _serial_roots(phase),
  _jni_roots(phase),
  _cld_roots(phase, n_workers),
  _thread_roots(phase, n_workers > 1),
  _weak_roots(phase, n_workers),
  _dedup_roots(phase),
  _code_roots(phase) {
  assert(ShenandoahHeap::heap()->is_full_gc_in_progress(), "Full GC only");
}

void ShenandoahRootAdjuster::roots_do(uint worker_id, OopClosure* oops) {
  CodeBlobToOopClosure adjust_code_closure(oops, CodeBlobToOopClosure::FixRelocations);
  CLDToOopClosure adjust_cld_closure(oops);
  AlwaysTrueClosure always_true;

  // Process serial-claiming roots first
  _serial_roots.oops_do(oops, worker_id);
  _jni_roots.oops_do(oops, worker_id);

  // Process light-weight/limited parallel roots then
  _weak_roots.oops_do<AlwaysTrueClosure, OopClosure>(&always_true, oops, worker_id);
  _dedup_roots.oops_do(&always_true, oops, worker_id);
  _cld_roots.cld_do(&adjust_cld_closure, worker_id);

  // Process heavy-weight/fully parallel roots the last
  _code_roots.code_blobs_do(&adjust_code_closure, worker_id);
  _thread_roots.oops_do(oops, NULL, worker_id);
}

ShenandoahHeapIterationRootScanner::ShenandoahHeapIterationRootScanner() :
   ShenandoahRootProcessor(ShenandoahPhaseTimings::heap_iteration_roots),
   _serial_roots(ShenandoahPhaseTimings::heap_iteration_roots),
   _thread_roots(ShenandoahPhaseTimings::heap_iteration_roots, false /*is par*/),
   _jni_roots(ShenandoahPhaseTimings::heap_iteration_roots),
   _cld_roots(ShenandoahPhaseTimings::heap_iteration_roots, 1),
   _weak_roots(ShenandoahPhaseTimings::heap_iteration_roots, 1),
   _dedup_roots(ShenandoahPhaseTimings::heap_iteration_roots),
   _code_roots(ShenandoahPhaseTimings::heap_iteration_roots)
 { }

 void ShenandoahHeapIterationRootScanner::roots_do(OopClosure* oops) {
   assert(Thread::current()->is_VM_thread(), "Only by VM thread");
   // Must use _claim_none to avoid interfering with concurrent CLDG iteration
   CLDToOopClosure clds(oops, false);
   MarkingCodeBlobClosure code(oops, !CodeBlobToOopClosure::FixRelocations);
   ShenandoahParallelOopsDoThreadClosure tc_cl(oops, &code, NULL);
   AlwaysTrueClosure always_true;
   ResourceMark rm;

   // Process serial-claiming roots first
   _serial_roots.oops_do(oops, 0);
   _jni_roots.oops_do(oops, 0);

   // Process light-weight/limited parallel roots then
   _weak_roots.oops_do<AlwaysTrueClosure, OopClosure>(&always_true, oops, 0);
   _dedup_roots.oops_do(&always_true, oops, 0);
   _cld_roots.cld_do(&clds, 0);

   // Process heavy-weight/fully parallel roots the last
   _code_roots.code_blobs_do(&code, 0);
   _thread_roots.threads_do(&tc_cl, 0);

 }
