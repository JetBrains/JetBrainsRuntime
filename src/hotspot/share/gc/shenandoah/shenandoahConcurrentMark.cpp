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
#include "classfile/stringTable.hpp"
#include "gc/shared/gcTimer.hpp"
#include "gc/shared/parallelCleaning.hpp"
#include "gc/shared/referenceProcessor.hpp"
#include "gc/shared/referenceProcessorPhaseTimes.hpp"
#include "gc/shared/strongRootsScope.hpp"
#include "gc/shared/suspendibleThreadSet.hpp"
#include "gc/shenandoah/brooksPointer.hpp"
#include "gc/shenandoah/shenandoahBarrierSet.inline.hpp"
#include "gc/shenandoah/shenandoahCollectorPolicy.hpp"
#include "gc/shenandoah/shenandoahConcurrentMark.inline.hpp"
#include "gc/shenandoah/shenandoahOopClosures.inline.hpp"
#include "gc/shenandoah/shenandoahMarkCompact.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahRootProcessor.hpp"
#include "gc/shenandoah/shenandoahOopClosures.hpp"
#include "gc/shenandoah/shenandoahTaskqueue.hpp"
#include "gc/shenandoah/shenandoahUtils.hpp"
#include "gc/shared/weakProcessor.hpp"
#include "code/codeCache.hpp"
#include "classfile/symbolTable.hpp"
#include "classfile/systemDictionary.hpp"
#include "memory/iterator.inline.hpp"
#include "oops/oop.inline.hpp"
#include "gc/shared/taskqueue.inline.hpp"
#include "logging/logStream.hpp"

template<UpdateRefsMode UPDATE_REFS>
class ShenandoahInitMarkRootsClosure : public OopClosure {
private:
  ShenandoahObjToScanQueue* _queue;
  ShenandoahHeap* _heap;

  template <class T>
  inline void do_oop_work(T* p) {
    ShenandoahConcurrentMark::mark_through_ref<T, UPDATE_REFS, false /* string dedup */>(p, _heap, _queue);
  }

public:
  ShenandoahInitMarkRootsClosure(ShenandoahObjToScanQueue* q) :
    _queue(q), _heap(ShenandoahHeap::heap()) {};

  void do_oop(narrowOop* p) { do_oop_work(p); }
  void do_oop(oop* p)       { do_oop_work(p); }
};

ShenandoahMarkRefsSuperClosure::ShenandoahMarkRefsSuperClosure(ShenandoahObjToScanQueue* q, ReferenceProcessor* rp) :
  MetadataVisitingOopIterateClosure(rp),
  _queue(q),
  _heap(ShenandoahHeap::heap())
{ }


template<UpdateRefsMode UPDATE_REFS>
class ShenandoahInitMarkRootsTask : public AbstractGangTask {
private:
  ShenandoahRootProcessor* _rp;
  bool _process_refs;
public:
  ShenandoahInitMarkRootsTask(ShenandoahRootProcessor* rp, bool process_refs) :
    AbstractGangTask("Shenandoah init mark roots task"),
    _rp(rp),
    _process_refs(process_refs) {
  }

  void work(uint worker_id) {
    assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Must be at a safepoint");
    ShenandoahWorkerSession worker_session(worker_id);

    ShenandoahHeap* heap = ShenandoahHeap::heap();
    ShenandoahObjToScanQueueSet* queues = heap->concurrentMark()->task_queues();
    assert(queues->get_reserved() > worker_id, "Queue has not been reserved for worker id: %d", worker_id);

    ShenandoahObjToScanQueue* q = queues->queue(worker_id);
    ShenandoahInitMarkRootsClosure<UPDATE_REFS> mark_cl(q);
    CLDToOopClosure cldCl(&mark_cl);
    MarkingCodeBlobClosure blobsCl(&mark_cl, ! CodeBlobToOopClosure::FixRelocations);

    // The rationale for selecting the roots to scan is as follows:
    //   a. With unload_classes = true, we only want to scan the actual strong roots from the
    //      code cache. This will allow us to identify the dead classes, unload them, *and*
    //      invalidate the relevant code cache blobs. This could be only done together with
    //      class unloading.
    //   b. With unload_classes = false, we have to nominally retain all the references from code
    //      cache, because there could be the case of embedded class/oop in the generated code,
    //      which we will never visit during mark. Without code cache invalidation, as in (a),
    //      we risk executing that code cache blob, and crashing.
    //   c. With ShenandoahConcurrentScanCodeRoots, we avoid scanning the entire code cache here,
    //      and instead do that in concurrent phase under the relevant lock. This saves init mark
    //      pause time.

    ResourceMark m;
    if (heap->concurrentMark()->unload_classes()) {
      _rp->process_strong_roots(&mark_cl, _process_refs ? NULL : &mark_cl, &cldCl, NULL, &blobsCl, NULL, worker_id);
    } else {
      if (ShenandoahConcurrentScanCodeRoots) {
        CodeBlobClosure* code_blobs = NULL;
#ifdef ASSERT
        ShenandoahAssertToSpaceClosure assert_to_space_oops;
        CodeBlobToOopClosure assert_to_space(&assert_to_space_oops, !CodeBlobToOopClosure::FixRelocations);
        // If conc code cache evac is disabled, code cache should have only to-space ptrs.
        // Otherwise, it should have to-space ptrs only if mark does not update refs.
        if (!ShenandoahConcurrentEvacCodeRoots && !heap->has_forwarded_objects()) {
          code_blobs = &assert_to_space;
        }
#endif
        _rp->process_all_roots(&mark_cl, _process_refs ? NULL : &mark_cl, &cldCl, code_blobs, NULL, worker_id);
      } else {
        _rp->process_all_roots(&mark_cl, _process_refs ? NULL : &mark_cl, &cldCl, &blobsCl, NULL, worker_id);
      }
    }
  }
};

class ShenandoahUpdateRootsTask : public AbstractGangTask {
private:
  ShenandoahRootProcessor* _rp;
  const bool _update_code_cache;
public:
  ShenandoahUpdateRootsTask(ShenandoahRootProcessor* rp, bool update_code_cache) :
    AbstractGangTask("Shenandoah update roots task"),
    _rp(rp),
    _update_code_cache(update_code_cache) {
  }

  void work(uint worker_id) {
    assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Must be at a safepoint");
    ShenandoahWorkerSession worker_session(worker_id);

    ShenandoahHeap* heap = ShenandoahHeap::heap();
    ShenandoahUpdateRefsClosure cl;
    CLDToOopClosure cldCl(&cl);

    CodeBlobClosure* code_blobs;
    CodeBlobToOopClosure update_blobs(&cl, CodeBlobToOopClosure::FixRelocations);
#ifdef ASSERT
    ShenandoahAssertToSpaceClosure assert_to_space_oops;
    CodeBlobToOopClosure assert_to_space(&assert_to_space_oops, !CodeBlobToOopClosure::FixRelocations);
#endif
    if (_update_code_cache) {
      code_blobs = &update_blobs;
    } else {
      code_blobs =
        DEBUG_ONLY(&assert_to_space)
        NOT_DEBUG(NULL);
    }
    _rp->process_all_roots(&cl, &cl, &cldCl, code_blobs, NULL, worker_id);
  }
};

class ShenandoahConcurrentMarkingTask : public AbstractGangTask {
private:
  ShenandoahConcurrentMark* _cm;
  ParallelTaskTerminator* _terminator;
  bool _update_refs;

public:
  ShenandoahConcurrentMarkingTask(ShenandoahConcurrentMark* cm, ParallelTaskTerminator* terminator, bool update_refs) :
    AbstractGangTask("Root Region Scan"), _cm(cm), _terminator(terminator), _update_refs(update_refs) {
  }


  void work(uint worker_id) {
    ShenandoahWorkerSession worker_session(worker_id);
    SuspendibleThreadSetJoiner stsj(ShenandoahSuspendibleWorkers);
    ShenandoahObjToScanQueue* q = _cm->get_queue(worker_id);
    jushort* live_data = _cm->get_liveness(worker_id);
    ReferenceProcessor* rp;
    if (_cm->process_references()) {
      rp = ShenandoahHeap::heap()->ref_processor();
      shenandoah_assert_rp_isalive_installed();
    } else {
      rp = NULL;
    }

    _cm->concurrent_scan_code_roots(worker_id, rp, _update_refs);
    _cm->mark_loop(worker_id, _terminator, rp,
                   true, // cancellable
                   _cm->unload_classes(),
                   _update_refs,
                   ShenandoahStringDedup::is_enabled()); // perform string dedup
  }
};

class ShenandoahSATBThreadsClosure : public ThreadClosure {
  ShenandoahSATBBufferClosure* _satb_cl;
  int _thread_parity;

 public:
  ShenandoahSATBThreadsClosure(ShenandoahSATBBufferClosure* satb_cl) :
    _satb_cl(satb_cl),
    _thread_parity(Threads::thread_claim_parity()) {}

  void do_thread(Thread* thread) {
    if (thread->is_Java_thread()) {
      if (thread->claim_oops_do(true, _thread_parity)) {
        JavaThread* jt = (JavaThread*)thread;
        ShenandoahThreadLocalData::satb_mark_queue(jt).apply_closure_and_empty(_satb_cl);
      }
    } else if (thread->is_VM_thread()) {
      if (thread->claim_oops_do(true, _thread_parity)) {
        ShenandoahBarrierSet::satb_mark_queue_set().shared_satb_queue()->apply_closure_and_empty(_satb_cl);
      }
    }
  }
};

class ShenandoahFinalMarkingTask : public AbstractGangTask {
private:
  ShenandoahConcurrentMark* _cm;
  ParallelTaskTerminator* _terminator;
  bool _update_refs;
  bool _unload_classes;
  bool _dedup_string;

public:
  ShenandoahFinalMarkingTask(ShenandoahConcurrentMark* cm, ParallelTaskTerminator* terminator,
                             bool update_refs, bool unload_classes, bool dedup_string) :
    AbstractGangTask("Shenandoah Final Marking"), _cm(cm), _terminator(terminator),
    _update_refs(update_refs), _unload_classes(unload_classes), _dedup_string(dedup_string) {
  }

  void work(uint worker_id) {
    ShenandoahWorkerSession worker_session(worker_id);
    // First drain remaining SATB buffers.
    // Notice that this is not strictly necessary for mark-compact. But since
    // it requires a StrongRootsScope around the task, we need to claim the
    // threads, and performance-wise it doesn't really matter. Adds about 1ms to
    // full-gc.
    {
      ShenandoahObjToScanQueue* q = _cm->get_queue(worker_id);
      ShenandoahSATBBufferClosure cl(q);
      SATBMarkQueueSet& satb_mq_set = ShenandoahBarrierSet::satb_mark_queue_set();
      while (satb_mq_set.apply_closure_to_completed_buffer(&cl));
      ShenandoahSATBThreadsClosure tc(&cl);
      Threads::threads_do(&tc);
    }

    ReferenceProcessor* rp;
    if (_cm->process_references()) {
      rp = ShenandoahHeap::heap()->ref_processor();
      shenandoah_assert_rp_isalive_installed();
    } else {
      rp = NULL;
    }

    // Degenerated cycle may bypass concurrent cycle, so code roots might not be scanned,
    // let's check here.
    _cm->concurrent_scan_code_roots(worker_id, rp, _update_refs);
    _cm->mark_loop(worker_id, _terminator, rp,
                   false, // not cancellable
                   _unload_classes,
                   _update_refs,
                   _dedup_string);

    assert(_cm->task_queues()->is_empty(), "Should be empty");
  }
};

void ShenandoahConcurrentMark::mark_roots(ShenandoahPhaseTimings::Phase root_phase) {
  assert(Thread::current()->is_VM_thread(), "can only do this in VMThread");
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Must be at a safepoint");

  ShenandoahHeap* heap = ShenandoahHeap::heap();

  ShenandoahGCPhase phase(root_phase);

  WorkGang* workers = heap->workers();
  uint nworkers = workers->active_workers();

  assert(nworkers <= task_queues()->size(), "Just check");

  ShenandoahRootProcessor root_proc(heap, nworkers, root_phase);
  TASKQUEUE_STATS_ONLY(reset_taskqueue_stats());
  task_queues()->reserve(nworkers);

  if (heap->has_forwarded_objects()) {
    ShenandoahInitMarkRootsTask<RESOLVE> mark_roots(&root_proc, process_references());
    workers->run_task(&mark_roots);
  } else {
    // No need to update references, which means the heap is stable.
    // Can save time not walking through forwarding pointers.
    ShenandoahInitMarkRootsTask<NONE> mark_roots(&root_proc, process_references());
    workers->run_task(&mark_roots);
  }

  if (ShenandoahConcurrentScanCodeRoots) {
    clear_claim_codecache();
  }
}

void ShenandoahConcurrentMark::init_mark_roots() {
  assert(Thread::current()->is_VM_thread(), "can only do this in VMThread");
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Must be at a safepoint");

  mark_roots(ShenandoahPhaseTimings::scan_roots);
}

void ShenandoahConcurrentMark::update_roots(ShenandoahPhaseTimings::Phase root_phase) {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Must be at a safepoint");

  bool update_code_cache = true; // initialize to safer value
  switch (root_phase) {
    case ShenandoahPhaseTimings::update_roots:
    case ShenandoahPhaseTimings::final_update_refs_roots:
      // If code cache was evacuated concurrently, we need to update code cache roots.
      update_code_cache = ShenandoahConcurrentEvacCodeRoots;
      break;
    case ShenandoahPhaseTimings::full_gc_roots:
      update_code_cache = true;
      break;
    default:
      ShouldNotReachHere();
  }

  ShenandoahHeap* heap = ShenandoahHeap::heap();

  ShenandoahGCPhase phase(root_phase);

#if defined(COMPILER2) || INCLUDE_JVMCI
  DerivedPointerTable::clear();
#endif

  uint nworkers = heap->workers()->active_workers();

  ShenandoahRootProcessor root_proc(heap, nworkers, root_phase);
  ShenandoahUpdateRootsTask update_roots(&root_proc, update_code_cache);
  heap->workers()->run_task(&update_roots);

#if defined(COMPILER2) || INCLUDE_JVMCI
  DerivedPointerTable::update_pointers();
#endif
}

void ShenandoahConcurrentMark::initialize(uint workers) {
  _heap = ShenandoahHeap::heap();

  uint num_queues = MAX2(workers, 1U);

  _task_queues = new ShenandoahObjToScanQueueSet((int) num_queues);

  for (uint i = 0; i < num_queues; ++i) {
    ShenandoahObjToScanQueue* task_queue = new ShenandoahObjToScanQueue();
    task_queue->initialize();
    _task_queues->register_queue(i, task_queue);
  }

  ShenandoahBarrierSet::satb_mark_queue_set().set_buffer_size(ShenandoahSATBBufferSize);

  size_t num_regions = ShenandoahHeap::heap()->num_regions();
  _liveness_local = NEW_C_HEAP_ARRAY(jushort*, workers, mtGC);
  for (uint worker = 0; worker < workers; worker++) {
     _liveness_local[worker] = NEW_C_HEAP_ARRAY(jushort, num_regions, mtGC);
  }
}

void ShenandoahConcurrentMark::concurrent_scan_code_roots(uint worker_id, ReferenceProcessor* rp, bool update_refs) {
  if (ShenandoahConcurrentScanCodeRoots && claim_codecache()) {
    ShenandoahObjToScanQueue* q = task_queues()->queue(worker_id);
    if (!unload_classes()) {
      MutexLockerEx mu(CodeCache_lock, Mutex::_no_safepoint_check_flag);
      if (update_refs) {
        ShenandoahMarkResolveRefsClosure cl(q, rp);
        CodeBlobToOopClosure blobs(&cl, !CodeBlobToOopClosure::FixRelocations);
        CodeCache::blobs_do(&blobs);
      } else {
        ShenandoahMarkRefsClosure cl(q, rp);
        CodeBlobToOopClosure blobs(&cl, !CodeBlobToOopClosure::FixRelocations);
        CodeCache::blobs_do(&blobs);
      }
    }
  }
}

void ShenandoahConcurrentMark::mark_from_roots() {
  ShenandoahHeap* sh = ShenandoahHeap::heap();
  WorkGang* workers = sh->workers();
  uint nworkers = workers->active_workers();

  bool update_refs = sh->has_forwarded_objects();

  ShenandoahGCPhase conc_mark_phase(ShenandoahPhaseTimings::conc_mark);

  if (process_references()) {
    ReferenceProcessor* rp = sh->ref_processor();
    rp->set_active_mt_degree(nworkers);

    // enable ("weak") refs discovery
    rp->enable_discovery(true /*verify_no_refs*/);
    rp->setup_policy(sh->is_full_gc_in_progress()); // snapshot the soft ref policy to be used in this cycle
  }

  shenandoah_assert_rp_isalive_not_installed();
  ReferenceProcessorIsAliveMutator fix_isalive(sh->ref_processor(), sh->is_alive_closure());

  task_queues()->reserve(nworkers);

  if (UseShenandoahOWST) {
    ShenandoahTaskTerminator terminator(nworkers, task_queues());
    ShenandoahConcurrentMarkingTask task(this, &terminator, update_refs);
    workers->run_task(&task);
  } else {
    ParallelTaskTerminator terminator(nworkers, task_queues());
    ShenandoahConcurrentMarkingTask task(this, &terminator, update_refs);
    workers->run_task(&task);
  }

  assert(task_queues()->is_empty() || sh->cancelled_gc(), "Should be empty when not cancelled");
}

void ShenandoahConcurrentMark::finish_mark_from_roots() {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Must be at a safepoint");

  ShenandoahHeap* sh = ShenandoahHeap::heap();

  shared_finish_mark_from_roots(/* full_gc = */ false);

  if (sh->has_forwarded_objects()) {
    update_roots(ShenandoahPhaseTimings::update_roots);
  }

  TASKQUEUE_STATS_ONLY(print_taskqueue_stats());
  TASKQUEUE_STATS_ONLY(reset_taskqueue_stats());
}

void ShenandoahConcurrentMark::shared_finish_mark_from_roots(bool full_gc) {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Must be at a safepoint");

  ShenandoahHeap* sh = ShenandoahHeap::heap();

  uint nworkers = sh->workers()->active_workers();

  // Finally mark everything else we've got in our queues during the previous steps.
  // It does two different things for concurrent vs. mark-compact GC:
  // - For concurrent GC, it starts with empty task queues, drains the remaining
  //   SATB buffers, and then completes the marking closure.
  // - For mark-compact GC, it starts out with the task queues seeded by initial
  //   root scan, and completes the closure, thus marking through all live objects
  // The implementation is the same, so it's shared here.
  {
    ShenandoahGCPhase phase(full_gc ?
                               ShenandoahPhaseTimings::full_gc_mark_finish_queues :
                               ShenandoahPhaseTimings::finish_queues);
    task_queues()->reserve(nworkers);

    shenandoah_assert_rp_isalive_not_installed();
    ReferenceProcessorIsAliveMutator fix_isalive(sh->ref_processor(), sh->is_alive_closure());

    StrongRootsScope scope(nworkers);
    if (UseShenandoahOWST) {
      ShenandoahTaskTerminator terminator(nworkers, task_queues());
      ShenandoahFinalMarkingTask task(this, &terminator, sh->has_forwarded_objects(),
        unload_classes(), full_gc && ShenandoahStringDedup::is_enabled());
      sh->workers()->run_task(&task);
    } else {
      ParallelTaskTerminator terminator(nworkers, task_queues());
      ShenandoahFinalMarkingTask task(this, &terminator, sh->has_forwarded_objects(),
        unload_classes(), full_gc && ShenandoahStringDedup::is_enabled());
      sh->workers()->run_task(&task);
    }
  }

  assert(task_queues()->is_empty(), "Should be empty");

  // When we're done marking everything, we process weak references.
  if (process_references()) {
    weak_refs_work(full_gc);
  }

  // And finally finish class unloading
  if (unload_classes()) {
    sh->unload_classes_and_cleanup_tables(full_gc);
  }

  assert(task_queues()->is_empty(), "Should be empty");

}

#if TASKQUEUE_STATS
void ShenandoahConcurrentMark::print_taskqueue_stats_hdr(outputStream* const st) {
  st->print_raw_cr("GC Task Stats");
  st->print_raw("thr "); TaskQueueStats::print_header(1, st); st->cr();
  st->print_raw("--- "); TaskQueueStats::print_header(2, st); st->cr();
}

void ShenandoahConcurrentMark::print_taskqueue_stats() const {
  if (!log_develop_is_enabled(Trace, gc, task, stats)) {
    return;
  }
  Log(gc, task, stats) log;
  ResourceMark rm;
  LogStream ls(log.trace());
  outputStream* st = &ls;
  print_taskqueue_stats_hdr(st);

  TaskQueueStats totals;
  const uint n = _task_queues->size();
  for (uint i = 0; i < n; ++i) {
    st->print(UINT32_FORMAT_W(3), i);
    _task_queues->queue(i)->stats.print(st);
    st->cr();
    totals += _task_queues->queue(i)->stats;
  }
  st->print("tot "); totals.print(st); st->cr();
  DEBUG_ONLY(totals.verify());

}

void ShenandoahConcurrentMark::reset_taskqueue_stats() {
  const uint n = task_queues()->size();
  for (uint i = 0; i < n; ++i) {
    task_queues()->queue(i)->stats.reset();
  }
}
#endif // TASKQUEUE_STATS

// Weak Reference Closures
class ShenandoahCMDrainMarkingStackClosure: public VoidClosure {
  uint _worker_id;
  ParallelTaskTerminator* _terminator;
  bool _reset_terminator;

public:
  ShenandoahCMDrainMarkingStackClosure(uint worker_id, ParallelTaskTerminator* t, bool reset_terminator = false):
    _worker_id(worker_id),
    _terminator(t),
    _reset_terminator(reset_terminator) {
  }

  void do_void() {
    assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Must be at a safepoint");

    ShenandoahHeap* sh = ShenandoahHeap::heap();
    ShenandoahConcurrentMark* scm = sh->concurrentMark();
    assert(scm->process_references(), "why else would we be here?");
    ReferenceProcessor* rp = sh->ref_processor();

    shenandoah_assert_rp_isalive_installed();

    scm->mark_loop(_worker_id, _terminator, rp,
                   false, // not cancellable
                   scm->unload_classes(),
                   sh->has_forwarded_objects(),
                   false);  // do not do strdedup

    if (_reset_terminator) {
      _terminator->reset_for_reuse();
    }
  }
};


class ShenandoahCMKeepAliveClosure : public OopClosure {
private:
  ShenandoahObjToScanQueue* _queue;
  ShenandoahHeap* _heap;

  template <class T>
  inline void do_oop_work(T* p) {
    ShenandoahConcurrentMark::mark_through_ref<T, NONE, false /* string dedup */>(p, _heap, _queue);
  }

public:
  ShenandoahCMKeepAliveClosure(ShenandoahObjToScanQueue* q) :
    _queue(q), _heap(ShenandoahHeap::heap()) {}

  void do_oop(narrowOop* p) { do_oop_work(p); }
  void do_oop(oop* p)       { do_oop_work(p); }
};

class ShenandoahCMKeepAliveUpdateClosure : public OopClosure {
private:
  ShenandoahObjToScanQueue* _queue;
  ShenandoahHeap* _heap;

  template <class T>
  inline void do_oop_work(T* p) {
    ShenandoahConcurrentMark::mark_through_ref<T, SIMPLE, false /* string dedup */>(p, _heap, _queue);
  }

public:
  ShenandoahCMKeepAliveUpdateClosure(ShenandoahObjToScanQueue* q) :
    _queue(q), _heap(ShenandoahHeap::heap()) {}

  void do_oop(narrowOop* p) { do_oop_work(p); }
  void do_oop(oop* p)       { do_oop_work(p); }
};

class ShenandoahRefProcTaskProxy : public AbstractGangTask {

private:
  AbstractRefProcTaskExecutor::ProcessTask& _proc_task;
  ParallelTaskTerminator* _terminator;
public:

  ShenandoahRefProcTaskProxy(AbstractRefProcTaskExecutor::ProcessTask& proc_task,
                             ParallelTaskTerminator* t) :
    AbstractGangTask("Process reference objects in parallel"),
    _proc_task(proc_task),
    _terminator(t) {
  }

  void work(uint worker_id) {
    ResourceMark rm;
    HandleMark hm;
    assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Must be at a safepoint");
    ShenandoahHeap* heap = ShenandoahHeap::heap();
    ShenandoahCMDrainMarkingStackClosure complete_gc(worker_id, _terminator);
    if (heap->has_forwarded_objects()) {
      ShenandoahForwardedIsAliveClosure is_alive;
      ShenandoahCMKeepAliveUpdateClosure keep_alive(heap->concurrentMark()->get_queue(worker_id));
      _proc_task.work(worker_id, is_alive, keep_alive, complete_gc);
    } else {
      ShenandoahIsAliveClosure is_alive;
      ShenandoahCMKeepAliveClosure keep_alive(heap->concurrentMark()->get_queue(worker_id));
      _proc_task.work(worker_id, is_alive, keep_alive, complete_gc);
    }
  }
};

class ShenandoahRefProcTaskExecutor : public AbstractRefProcTaskExecutor {

private:
  WorkGang* _workers;

public:

  ShenandoahRefProcTaskExecutor(WorkGang* workers) :
    _workers(workers) {
  }

  // Executes a task using worker threads.
  void execute(ProcessTask& task, uint ergo_workers) {
    assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Must be at a safepoint");

    ShenandoahHeap* heap = ShenandoahHeap::heap();
    ShenandoahConcurrentMark* cm = heap->concurrentMark();
    ShenandoahPushWorkerScope scope(_workers,
                                    ergo_workers,
                                    /* do_check = */ false);
    uint nworkers = _workers->active_workers();
    cm->task_queues()->reserve(nworkers);
    if (UseShenandoahOWST) {
      ShenandoahTaskTerminator terminator(nworkers, cm->task_queues());
      ShenandoahRefProcTaskProxy proc_task_proxy(task, &terminator);
      _workers->run_task(&proc_task_proxy);
    } else {
      ParallelTaskTerminator terminator(nworkers, cm->task_queues());
      ShenandoahRefProcTaskProxy proc_task_proxy(task, &terminator);
      _workers->run_task(&proc_task_proxy);
    }
  }
};


void ShenandoahConcurrentMark::weak_refs_work(bool full_gc) {
  assert(process_references(), "sanity");

  ShenandoahHeap* sh = ShenandoahHeap::heap();

  ShenandoahPhaseTimings::Phase phase_root =
          full_gc ?
          ShenandoahPhaseTimings::full_gc_weakrefs :
          ShenandoahPhaseTimings::weakrefs;

  ShenandoahGCPhase phase(phase_root);

  ReferenceProcessor* rp = sh->ref_processor();

  // NOTE: We cannot shortcut on has_discovered_references() here, because
  // we will miss marking JNI Weak refs then, see implementation in
  // ReferenceProcessor::process_discovered_references.
  weak_refs_work_doit(full_gc);

  rp->verify_no_references_recorded();
  assert(!rp->discovery_enabled(), "Post condition");

}

void ShenandoahConcurrentMark::weak_refs_work_doit(bool full_gc) {
  ShenandoahHeap* sh = ShenandoahHeap::heap();

  ReferenceProcessor* rp = sh->ref_processor();

  ShenandoahPhaseTimings::Phase phase_process =
          full_gc ?
          ShenandoahPhaseTimings::full_gc_weakrefs_process :
          ShenandoahPhaseTimings::weakrefs_process;

  shenandoah_assert_rp_isalive_not_installed();
  ReferenceProcessorIsAliveMutator fix_isalive(rp, sh->is_alive_closure());

  WorkGang* workers = sh->workers();
  uint nworkers = workers->active_workers();

  // Setup collector policy for softref cleaning.
  bool clear_soft_refs = sh->soft_ref_policy()->use_should_clear_all_soft_refs(true /* bogus arg*/);
  log_develop_debug(gc, ref)("clearing soft refs: %s", BOOL_TO_STR(clear_soft_refs));
  rp->setup_policy(clear_soft_refs);
  rp->set_active_mt_degree(nworkers);

  assert(task_queues()->is_empty(), "Should be empty");

  // complete_gc and keep_alive closures instantiated here are only needed for
  // single-threaded path in RP. They share the queue 0 for tracking work, which
  // simplifies implementation. Since RP may decide to call complete_gc several
  // times, we need to be able to reuse the terminator.
  uint serial_worker_id = 0;
  ParallelTaskTerminator terminator(1, task_queues());
  ShenandoahCMDrainMarkingStackClosure complete_gc(serial_worker_id, &terminator, /* reset_terminator = */ true);

  ShenandoahRefProcTaskExecutor executor(workers);

  ReferenceProcessorPhaseTimes pt(sh->gc_timer(), rp->num_queues());

  {
    ShenandoahGCPhase phase(phase_process);

    if (sh->has_forwarded_objects()) {
      ShenandoahForwardedIsAliveClosure is_alive;
      ShenandoahCMKeepAliveUpdateClosure keep_alive(get_queue(serial_worker_id));
      rp->process_discovered_references(&is_alive, &keep_alive,
                                        &complete_gc, &executor,
                                        &pt);

      WeakProcessor::weak_oops_do(&is_alive, &keep_alive);
    } else {
      ShenandoahIsAliveClosure is_alive;
      ShenandoahCMKeepAliveClosure keep_alive(get_queue(serial_worker_id));
      rp->process_discovered_references(&is_alive, &keep_alive,
                                        &complete_gc, &executor,
                                        &pt);

      WeakProcessor::weak_oops_do(&is_alive, &keep_alive);
    }
    pt.print_all_references();

    assert(task_queues()->is_empty(), "Should be empty");
  }
}

class ShenandoahCancelledGCYieldClosure : public YieldClosure {
private:
  ShenandoahHeap* const _heap;
public:
  ShenandoahCancelledGCYieldClosure() : _heap(ShenandoahHeap::heap()) {};
  virtual bool should_return() { return _heap->cancelled_gc(); }
};

class ShenandoahPrecleanCompleteGCClosure : public VoidClosure {
public:
  void do_void() {
    ShenandoahHeap* sh = ShenandoahHeap::heap();
    ShenandoahConcurrentMark* scm = sh->concurrentMark();
    assert(scm->process_references(), "why else would we be here?");
    ParallelTaskTerminator terminator(1, scm->task_queues());

    ReferenceProcessor* rp = sh->ref_processor();
    shenandoah_assert_rp_isalive_installed();

    scm->mark_loop(0, &terminator, rp,
                   false, // not cancellable
                   scm->unload_classes(),
                   sh->has_forwarded_objects(),
                   false); // do not do strdedup
  }
};

class ShenandoahPrecleanKeepAliveUpdateClosure : public OopClosure {
private:
  ShenandoahObjToScanQueue* _queue;
  ShenandoahHeap* _heap;

  template <class T>
  inline void do_oop_work(T* p) {
    ShenandoahConcurrentMark::mark_through_ref<T, CONCURRENT, false /* string dedup */>(p, _heap, _queue);
  }

public:
  ShenandoahPrecleanKeepAliveUpdateClosure(ShenandoahObjToScanQueue* q) :
    _queue(q), _heap(ShenandoahHeap::heap()) {}

  void do_oop(narrowOop* p) { do_oop_work(p); }
  void do_oop(oop* p)       { do_oop_work(p); }
};

void ShenandoahConcurrentMark::preclean_weak_refs() {
  // Pre-cleaning weak references before diving into STW makes sense at the
  // end of concurrent mark. This will filter out the references which referents
  // are alive. Note that ReferenceProcessor already filters out these on reference
  // discovery, and the bulk of work is done here. This phase processes leftovers
  // that missed the initial filtering, i.e. when referent was marked alive after
  // reference was discovered by RP.

  assert(process_references(), "sanity");

  ShenandoahHeap* sh = ShenandoahHeap::heap();
  ReferenceProcessor* rp = sh->ref_processor();

  // Shortcut if no references were discovered to avoid winding up threads.
  if (!rp->has_discovered_references()) {
    return;
  }

  ReferenceProcessorMTDiscoveryMutator fix_mt_discovery(rp, false);

  shenandoah_assert_rp_isalive_not_installed();
  ReferenceProcessorIsAliveMutator fix_isalive(rp, sh->is_alive_closure());

  // Interrupt on cancelled GC
  ShenandoahCancelledGCYieldClosure yield;

  assert(task_queues()->is_empty(), "Should be empty");

  ShenandoahPrecleanCompleteGCClosure complete_gc;
  if (sh->has_forwarded_objects()) {
    ShenandoahForwardedIsAliveClosure is_alive;
    ShenandoahPrecleanKeepAliveUpdateClosure keep_alive(get_queue(0));
    ResourceMark rm;
    rp->preclean_discovered_references(&is_alive, &keep_alive,
                                       &complete_gc, &yield,
                                       NULL);
  } else {
    ShenandoahIsAliveClosure is_alive;
    ShenandoahCMKeepAliveClosure keep_alive(get_queue(0));
    ResourceMark rm;
    rp->preclean_discovered_references(&is_alive, &keep_alive,
                                       &complete_gc, &yield,
                                       NULL);
  }

  assert(task_queues()->is_empty(), "Should be empty");
}

void ShenandoahConcurrentMark::cancel() {
  // Clean up marking stacks.
  ShenandoahObjToScanQueueSet* queues = task_queues();
  queues->clear();

  // Cancel SATB buffers.
  ShenandoahBarrierSet::satb_mark_queue_set().abandon_partial_marking();
}

ShenandoahObjToScanQueue* ShenandoahConcurrentMark::get_queue(uint worker_id) {
  assert(task_queues()->get_reserved() > worker_id, "No reserved queue for worker id: %d", worker_id);
  return _task_queues->queue(worker_id);
}

void ShenandoahConcurrentMark::clear_queue(ShenandoahObjToScanQueue *q) {
  q->set_empty();
  q->overflow_stack()->clear();
  q->clear_buffer();
}

template <bool CANCELLABLE>
void ShenandoahConcurrentMark::mark_loop_prework(uint w, ParallelTaskTerminator *t, ReferenceProcessor *rp,
                                                 bool class_unload, bool update_refs, bool strdedup) {
  ShenandoahObjToScanQueue* q = get_queue(w);

  jushort* ld = get_liveness(w);
  Copy::fill_to_bytes(ld, _heap->num_regions() * sizeof(jushort));

  // TODO: We can clean up this if we figure out how to do templated oop closures that
  // play nice with specialized_oop_iterators.
  if (class_unload) {
    if (update_refs) {
      if (strdedup) {
        ShenandoahMarkUpdateRefsMetadataDedupClosure cl(q, rp);
        mark_loop_work<ShenandoahMarkUpdateRefsMetadataDedupClosure, CANCELLABLE>(&cl, ld, w, t);
      } else {
        ShenandoahMarkUpdateRefsMetadataClosure cl(q, rp);
        mark_loop_work<ShenandoahMarkUpdateRefsMetadataClosure, CANCELLABLE>(&cl, ld, w, t);
      }
    } else {
      if (strdedup) {
        ShenandoahMarkRefsMetadataDedupClosure cl(q, rp);
        mark_loop_work<ShenandoahMarkRefsMetadataDedupClosure, CANCELLABLE>(&cl, ld, w, t);
      } else {
        ShenandoahMarkRefsMetadataClosure cl(q, rp);
        mark_loop_work<ShenandoahMarkRefsMetadataClosure, CANCELLABLE>(&cl, ld, w, t);
      }
    }
  } else {
    if (update_refs) {
      if (strdedup) {
        ShenandoahMarkUpdateRefsDedupClosure cl(q, rp);
        mark_loop_work<ShenandoahMarkUpdateRefsDedupClosure, CANCELLABLE>(&cl, ld, w, t);
      } else {
        ShenandoahMarkUpdateRefsClosure cl(q, rp);
        mark_loop_work<ShenandoahMarkUpdateRefsClosure, CANCELLABLE>(&cl, ld, w, t);
      }
    } else {
      if (strdedup) {
        ShenandoahMarkRefsDedupClosure cl(q, rp);
        mark_loop_work<ShenandoahMarkRefsDedupClosure, CANCELLABLE>(&cl, ld, w, t);
      } else {
        ShenandoahMarkRefsClosure cl(q, rp);
        mark_loop_work<ShenandoahMarkRefsClosure, CANCELLABLE>(&cl, ld, w, t);
      }
    }
  }


  for (uint i = 0; i < _heap->num_regions(); i++) {
    ShenandoahHeapRegion* r = _heap->get_region(i);
    jushort live = ld[i];
    if (live > 0) {
      r->increase_live_data_gc_words(live);
    }
  }
}

template <class T, bool CANCELLABLE>
void ShenandoahConcurrentMark::mark_loop_work(T* cl, jushort* live_data, uint worker_id, ParallelTaskTerminator *terminator) {
  int seed = 17;
  uintx stride = ShenandoahMarkLoopStride;

  ShenandoahHeap* heap = ShenandoahHeap::heap();
  ShenandoahObjToScanQueueSet* queues = task_queues();
  ShenandoahObjToScanQueue* q;
  ShenandoahMarkTask t;

  /*
   * Process outstanding queues, if any.
   *
   * There can be more queues than workers. To deal with the imbalance, we claim
   * extra queues first. Since marking can push new tasks into the queue associated
   * with this worker id, we come back to process this queue in the normal loop.
   */
  assert(queues->get_reserved() == heap->workers()->active_workers(),
    "Need to reserve proper number of queues");

  q = queues->claim_next();
  while (q != NULL) {
    if (CANCELLABLE && heap->check_cancelled_gc_and_yield()) {
      ShenandoahCancelledTerminatorTerminator tt;
      while (!terminator->offer_termination(&tt));
      return;
    }

    for (uint i = 0; i < stride; i++) {
      if (try_queue(q, t)) {
        do_task<T>(q, cl, live_data, &t);
      } else {
        assert(q->is_empty(), "Must be empty");
        q = queues->claim_next();
        break;
      }
    }
  }
  q = get_queue(worker_id);

  ShenandoahSATBBufferClosure drain_satb(q);
  SATBMarkQueueSet& satb_mq_set = ShenandoahBarrierSet::satb_mark_queue_set();

  /*
   * Normal marking loop:
   */
  while (true) {
    if (CANCELLABLE && heap->check_cancelled_gc_and_yield()) {
      ShenandoahCancelledTerminatorTerminator tt;
      while (!terminator->offer_termination(&tt));
      return;
    }

    while (satb_mq_set.completed_buffers_num() > 0) {
      satb_mq_set.apply_closure_to_completed_buffer(&drain_satb);
    }

    uint work = 0;
    for (uint i = 0; i < stride; i++) {
      if (try_queue(q, t) ||
          queues->steal(worker_id, &seed, t)) {
        do_task<T>(q, cl, live_data, &t);
        work++;
      } else {
        break;
      }
    }

    if (work == 0) {
      // No work encountered in current stride, try to terminate.
      // Need to leave the STS here otherwise it might block safepoints.
      SuspendibleThreadSetLeaver stsl(CANCELLABLE && ShenandoahSuspendibleWorkers);
      if (terminator->offer_termination()) return;
    }
  }
}

bool ShenandoahConcurrentMark::process_references() const {
  return _heap->process_references();
}

bool ShenandoahConcurrentMark::unload_classes() const {
  return _heap->unload_classes();
}

bool ShenandoahConcurrentMark::claim_codecache() {
  assert(ShenandoahConcurrentScanCodeRoots, "must not be called otherwise");
  return _claimed_codecache.try_set();
}

void ShenandoahConcurrentMark::clear_claim_codecache() {
  assert(ShenandoahConcurrentScanCodeRoots, "must not be called otherwise");
  _claimed_codecache.unset();
}

jushort* ShenandoahConcurrentMark::get_liveness(uint worker_id) {
  return _liveness_local[worker_id];
}

