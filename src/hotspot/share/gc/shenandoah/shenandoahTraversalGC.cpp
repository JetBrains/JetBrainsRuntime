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

#include "precompiled.hpp"

#include "gc/shared/gcTraceTime.inline.hpp"
#include "gc/shared/markBitMap.inline.hpp"
#include "gc/shared/workgroup.hpp"
#include "gc/shared/taskqueue.inline.hpp"
#include "gc/shenandoah/shenandoahBarrierSet.hpp"
#include "gc/shenandoah/shenandoahCollectionSet.hpp"
#include "gc/shenandoah/shenandoahCollectorPolicy.hpp"
#include "gc/shenandoah/shenandoahFreeSet.hpp"
#include "gc/shenandoah/shenandoahPhaseTimings.hpp"
#include "gc/shenandoah/shenandoahHeapRegionSet.hpp"
#include "gc/shenandoah/shenandoahHeap.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahOopClosures.inline.hpp"
#include "gc/shenandoah/shenandoahTraversalGC.hpp"
#include "gc/shenandoah/shenandoahRootProcessor.hpp"
#include "gc/shenandoah/shenandoahStringDedup.hpp"
#include "gc/shenandoah/shenandoahStrDedupQueue.inline.hpp"
#include "gc/shenandoah/shenandoahTaskqueue.hpp"
#include "gc/shenandoah/shenandoahUtils.hpp"
#include "gc/shenandoah/shenandoahVerifier.hpp"
#include "gc/shenandoah/shenandoahWorkGroup.hpp"
#include "gc/shenandoah/shenandoahWorkerPolicy.hpp"

#include "memory/iterator.hpp"

/**
 * NOTE: We are using the SATB buffer in thread.hpp and satbMarkQueue.hpp, however, it is not an SATB algorithm.
 * We're using the buffer as generic oop buffer to enqueue new values in concurrent oop stores, IOW, the algorithm
 * is incremental-update-based.
 */
class ShenandoahTraversalSATBBufferClosure : public SATBBufferClosure {
private:
  ShenandoahObjToScanQueue* _queue;
  ShenandoahTraversalGC* _traversal_gc;
  ShenandoahHeap* _heap;
  MarkBitMap* _bitmap;
public:
  ShenandoahTraversalSATBBufferClosure(ShenandoahObjToScanQueue* q) :
    _queue(q), _traversal_gc(ShenandoahHeap::heap()->traversal_gc()),
    _heap(ShenandoahHeap::heap()), _bitmap(ShenandoahHeap::heap()->next_mark_bit_map())
 { }

  void do_buffer(void** buffer, size_t size) {
    for (size_t i = 0; i < size; ++i) {
      oop* p = (oop*) &buffer[i];
      oop obj = oopDesc::load_heap_oop(p);
      assert(!oopDesc::is_null(obj), "no NULL refs in oop queue");
      assert(oopDesc::unsafe_equals(obj, ShenandoahBarrierSet::resolve_oop_static_not_null(obj)), "only to-space objs");
      if ((!_bitmap->isMarked((HeapWord*) obj)) && _bitmap->parMark((HeapWord*) obj)) {
        _queue->push(ShenandoahMarkTask(obj));
      }
    }
  }
};

class ShenandoahTraversalSATBThreadsClosure : public ThreadClosure {
  ShenandoahTraversalSATBBufferClosure* _satb_cl;

 public:
  ShenandoahTraversalSATBThreadsClosure(ShenandoahTraversalSATBBufferClosure* satb_cl) :
    _satb_cl(satb_cl) {}

  void do_thread(Thread* thread) {
    if (thread->is_Java_thread()) {
      JavaThread* jt = (JavaThread*)thread;
      jt->satb_mark_queue().apply_closure_and_empty(_satb_cl);
    } else if (thread->is_VM_thread()) {
      JavaThread::satb_mark_queue_set().shared_satb_queue()->apply_closure_and_empty(_satb_cl);
    }
  }
};

// Like CLDToOopClosure, but clears has_modified_oops, so that we can record modified CLDs during traversal
// and remark them later during final-traversal.
class ShenandoahMarkCLDClosure : public CLDClosure {
private:
  OopClosure* _cl;
public:
  ShenandoahMarkCLDClosure(OopClosure* cl) : _cl(cl) {}
  void do_cld(ClassLoaderData* cld) {
    cld->oops_do(_cl, true, true);
  }
};

// Like CLDToOopClosure, but only process modified CLDs
class ShenandoahRemarkCLDClosure : public CLDClosure {
private:
  OopClosure* _cl;
public:
  ShenandoahRemarkCLDClosure(OopClosure* cl) : _cl(cl) {}
  void do_cld(ClassLoaderData* cld) {
    if (cld->has_modified_oops()) {
      cld->oops_do(_cl, true, true);
    }
  }
};

class ShenandoahInitTraversalCollectionTask : public AbstractGangTask {
private:
  ShenandoahRootProcessor* _rp;
  ShenandoahHeap* _heap;
public:
  ShenandoahInitTraversalCollectionTask(ShenandoahRootProcessor* rp) :
    AbstractGangTask("Shenandoah Init Traversal Collection"),
    _rp(rp),
    _heap(ShenandoahHeap::heap()) {}

  void work(uint worker_id) {
    ShenandoahObjToScanQueueSet* queues = _heap->traversal_gc()->task_queues();
    ShenandoahObjToScanQueue* q = queues->queue(worker_id);

    // Initialize live data.
    jushort* ld = _heap->traversal_gc()->get_liveness(worker_id);
    Copy::fill_to_bytes(ld, _heap->num_regions() * sizeof(jushort));

    bool process_refs = _heap->shenandoahPolicy()->process_references();
    bool unload_classes = _heap->shenandoahPolicy()->unload_classes();
    ReferenceProcessor* rp = NULL;
    if (process_refs) {
      rp = _heap->ref_processor();
    }

    // Step 1: Process ordinary GC roots.
    {
      ShenandoahTraversalClosure roots_cl(q, rp);
      ShenandoahMarkCLDClosure cld_cl(&roots_cl);
      MarkingCodeBlobClosure code_cl(&roots_cl, CodeBlobToOopClosure::FixRelocations);
      if (unload_classes) {
        _rp->process_strong_roots(&roots_cl, process_refs ? NULL : &roots_cl, &cld_cl, NULL, &code_cl, NULL, worker_id);
      } else {
        _rp->process_all_roots(&roots_cl, process_refs ? NULL : &roots_cl, &cld_cl, &code_cl, NULL, worker_id);
      }
    }
  }
};

class ShenandoahConcurrentTraversalCollectionTask : public AbstractGangTask {
private:
  ParallelTaskTerminator* _terminator;
  ShenandoahHeap* _heap;
public:
  ShenandoahConcurrentTraversalCollectionTask(ParallelTaskTerminator* terminator) :
    AbstractGangTask("Shenandoah Concurrent Traversal Collection"),
    _terminator(terminator),
    _heap(ShenandoahHeap::heap()) {}

  void work(uint worker_id) {
    ShenandoahTraversalGC* traversal_gc = _heap->traversal_gc();
    ShenandoahObjToScanQueueSet* queues = traversal_gc->task_queues();
    ShenandoahObjToScanQueue* q = queues->queue(worker_id);

    // Drain all outstanding work in queues.
    traversal_gc->main_loop(worker_id, _terminator, true);
  }
};

class ShenandoahFinalTraversalCollectionTask : public AbstractGangTask {
private:
  ShenandoahRootProcessor* _rp;
  ParallelTaskTerminator* _terminator;
  ShenandoahHeap* _heap;
public:
  ShenandoahFinalTraversalCollectionTask(ShenandoahRootProcessor* rp, ParallelTaskTerminator* terminator) :
    AbstractGangTask("Shenandoah Final Traversal Collection"),
    _rp(rp),
    _terminator(terminator),
    _heap(ShenandoahHeap::heap()) {}

  void work(uint worker_id) {
    ShenandoahTraversalGC* traversal_gc = _heap->traversal_gc();

    ShenandoahObjToScanQueueSet* queues = traversal_gc->task_queues();
    ShenandoahObjToScanQueue* q = queues->queue(worker_id);

    bool process_refs = _heap->shenandoahPolicy()->process_references();
    bool unload_classes = _heap->shenandoahPolicy()->unload_classes();
    ReferenceProcessor* rp = NULL;
    if (process_refs) {
      rp = _heap->ref_processor();
    }

    // Step 1: Drain outstanding SATB queues.
    // NOTE: we piggy-back draining of remaining thread SATB buffers on the final root scan below.
    ShenandoahTraversalSATBBufferClosure satb_cl(q);
    {
      // Process remaining finished SATB buffers.
      SATBMarkQueueSet& satb_mq_set = JavaThread::satb_mark_queue_set();
      while (satb_mq_set.apply_closure_to_completed_buffer(&satb_cl));
      // Process remaining threads SATB buffers below.
    }

    // Step 1: Process ordinary GC roots.
    {
      ShenandoahTraversalClosure roots_cl(q, rp);
      CLDToOopClosure cld_cl(&roots_cl);
      MarkingCodeBlobClosure code_cl(&roots_cl, CodeBlobToOopClosure::FixRelocations);
      ShenandoahTraversalSATBThreadsClosure tc(&satb_cl);
      if (unload_classes) {
        ShenandoahRemarkCLDClosure weak_cld_cl(&roots_cl);
        _rp->process_strong_roots(&roots_cl, process_refs ? NULL : &roots_cl, &cld_cl, &weak_cld_cl, &code_cl, &tc, worker_id);
      } else {
        _rp->process_all_roots(&roots_cl, process_refs ? NULL : &roots_cl, &cld_cl, &code_cl, &tc, worker_id);
      }
    }

    {
      ShenandoahWorkerTimings *worker_times = _heap->phase_timings()->worker_times();
      ShenandoahWorkerTimingsTracker timer(worker_times, ShenandoahPhaseTimings::FinishQueues, worker_id);

      // Step 3: Finally drain all outstanding work in queues.
      traversal_gc->main_loop(worker_id, _terminator, false);
    }

    // Flush remaining liveness data.
    traversal_gc->flush_liveness(worker_id);

  }
};

void ShenandoahTraversalGC::flush_liveness(uint worker_id) {
  jushort* ld = get_liveness(worker_id);
  for (uint i = 0; i < _heap->regions()->active_regions(); i++) {
    ShenandoahHeapRegion* r = _heap->regions()->get(i);
    jushort live = ld[i];
    if (live > 0) {
      r->increase_live_data_words(live);
      ld[i] = 0;
    }
  }
}

ShenandoahTraversalGC::ShenandoahTraversalGC(ShenandoahHeap* heap, size_t num_regions) :
  _heap(heap),
  _task_queues(new ShenandoahObjToScanQueueSet(heap->max_workers())) {

  uint num_queues = heap->max_workers();
  for (uint i = 0; i < num_queues; ++i) {
    ShenandoahObjToScanQueue* task_queue = new ShenandoahObjToScanQueue();
    task_queue->initialize();
    _task_queues->register_queue(i, task_queue);
  }

  uint workers = heap->max_workers();
  _liveness_local = NEW_C_HEAP_ARRAY(jushort*, workers, mtGC);
  for (uint worker = 0; worker < workers; worker++) {
     _liveness_local[worker] = NEW_C_HEAP_ARRAY(jushort, num_regions, mtGC);
  }

}

ShenandoahTraversalGC::~ShenandoahTraversalGC() {
}

void ShenandoahTraversalGC::prepare() {
  _heap->collection_set()->clear();
  assert(_heap->collection_set()->count() == 0, "collection set not clear");

  _heap->make_tlabs_parsable(true);

  assert(_heap->is_next_bitmap_clear(), "need clean mark bitmap");
  _bitmap = _heap->next_mark_bit_map();

  ShenandoahHeapRegionSet* regions = _heap->regions();
  ShenandoahCollectionSet* collection_set = _heap->collection_set();
  size_t num_regions = _heap->num_regions();

  // Find collection set
  _heap->shenandoahPolicy()->choose_collection_set(collection_set, false);

  // Rebuild free set
  ShenandoahFreeSet* _free_regions = _heap->free_regions();
  _free_regions->clear();

  for (uint from_idx = 0; from_idx < num_regions; from_idx++) {
    ShenandoahHeapRegion* r = regions->get(from_idx);
    if (r->is_alloc_allowed()) {
      _free_regions->add_region(r);
    }
  }

  log_info(gc,ergo)("Got "SIZE_FORMAT" collection set regions", collection_set->count());
}

void ShenandoahTraversalGC::init_traversal_collection() {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "STW traversal GC");

  _heap->set_alloc_seq_gc_start();

  if (ShenandoahVerify) {
    _heap->verifier()->verify_before_traversal();
  }

  {
    ShenandoahGCPhase phase_prepare(ShenandoahPhaseTimings::traversal_gc_prepare);
    ShenandoahHeapLocker lock(_heap->lock());
    prepare();
  }

  _heap->set_concurrent_traversal_in_progress(true);

  bool process_refs = _heap->shenandoahPolicy()->process_references();
  if (process_refs) {
    ReferenceProcessor* rp = _heap->ref_processor();
    rp->enable_discovery(true /*verify_no_refs*/);
    rp->setup_policy(false);
  }

  {
    ShenandoahGCPhase phase_work(ShenandoahPhaseTimings::init_traversal_gc_work);
    assert(_task_queues->is_empty(), "queues must be empty before traversal GC");

#if defined(COMPILER2) || INCLUDE_JVMCI
    DerivedPointerTable::clear();
#endif

    {
      uint nworkers = _heap->workers()->active_workers();
      task_queues()->reserve(nworkers);
      ShenandoahRootProcessor rp(_heap, nworkers, ShenandoahPhaseTimings::init_traversal_gc_work);

      if (UseShenandoahOWST) {
        ShenandoahTaskTerminator terminator(nworkers, task_queues());
        ShenandoahInitTraversalCollectionTask traversal_task(&rp);
        _heap->workers()->run_task(&traversal_task);
      } else {
        ParallelTaskTerminator terminator(nworkers, task_queues());
        ShenandoahInitTraversalCollectionTask traversal_task(&rp);
        _heap->workers()->run_task(&traversal_task);
      }
    }

#if defined(COMPILER2) || INCLUDE_JVMCI
    DerivedPointerTable::update_pointers();
#endif
    if (_heap->cancelled_concgc()) {
      _heap->fixup_roots();
      reset();
      _heap->set_concurrent_traversal_in_progress(false);
    }
  }
}

void ShenandoahTraversalGC::main_loop(uint worker_id, ParallelTaskTerminator* terminator, bool do_satb) {
  if (do_satb) {
    main_loop_prework<true>(worker_id, terminator);
  } else {
    main_loop_prework<false>(worker_id, terminator);
  }
}

template <bool DO_SATB>
void ShenandoahTraversalGC::main_loop_prework(uint w, ParallelTaskTerminator* t) {
  ShenandoahObjToScanQueue* q = task_queues()->queue(w);
  jushort* ld = get_liveness(w);

  ReferenceProcessor* rp = NULL;
  if (_heap->shenandoahPolicy()->process_references()) {
    rp = _heap->ref_processor();
  }
  if (_heap->shenandoahPolicy()->unload_classes()) {
    if (ShenandoahStringDedup::is_enabled()) {
      ShenandoahStrDedupQueue* dq = ShenandoahStringDedup::queue(w);
      ShenandoahTraversalMetadataDedupClosure cl(q, rp, dq);
      main_loop_work<ShenandoahTraversalMetadataDedupClosure, DO_SATB>(&cl, ld, w, t);
    } else {
      ShenandoahTraversalMetadataClosure cl(q, rp);
      main_loop_work<ShenandoahTraversalMetadataClosure, DO_SATB>(&cl, ld, w, t);
    }
  } else {
    if (ShenandoahStringDedup::is_enabled()) {
      ShenandoahStrDedupQueue* dq = ShenandoahStringDedup::queue(w);
      ShenandoahTraversalDedupClosure cl(q, rp, dq);
      main_loop_work<ShenandoahTraversalDedupClosure, DO_SATB>(&cl, ld, w, t);
    } else {
      ShenandoahTraversalClosure cl(q, rp);
      main_loop_work<ShenandoahTraversalClosure, DO_SATB>(&cl, ld, w, t);
    }
  }
}

template <class T, bool DO_SATB>
void ShenandoahTraversalGC::main_loop_work(T* cl, jushort* live_data, uint worker_id, ParallelTaskTerminator* terminator) {
  ShenandoahObjToScanQueueSet* queues = task_queues();
  ShenandoahObjToScanQueue* q = queues->queue(worker_id);

  uintx stride = ShenandoahMarkLoopStride;

  ShenandoahMarkTask task;

  // Process outstanding queues, if any.
  q = queues->claim_next();
  while (q != NULL) {
    if (_heap->check_cancelled_concgc_and_yield()) {
      ShenandoahCancelledTerminatorTerminator tt;
      while (!terminator->offer_termination(&tt));
      return;
    }

    for (uint i = 0; i < stride; i++) {
      if (q->pop_buffer(task) ||
          q->pop_local(task) ||
          q->pop_overflow(task)) {
        do_task(q, cl, live_data, &task);
      } else {
        assert(q->is_empty(), "Must be empty");
        q = queues->claim_next();
        break;
      }
    }
  }
  // Normal loop.
  q = queues->queue(worker_id);
  ShenandoahTraversalSATBBufferClosure satb_cl(q);
  SATBMarkQueueSet& satb_mq_set = JavaThread::satb_mark_queue_set();

  int seed = 17;

  while (true) {
    if (check_and_handle_cancelled_gc(terminator)) return;

    for (uint i = 0; i < stride; i++) {
      if ((q->pop_buffer(task) ||
           q->pop_local(task) ||
           q->pop_overflow(task) ||
           (DO_SATB && satb_mq_set.apply_closure_to_completed_buffer(&satb_cl) && q->pop_buffer(task)) ||
           queues->steal(worker_id, &seed, task))) {
        do_task(q, cl, live_data, &task);
      } else {
        if (terminator->offer_termination()) return;
      }
    }
  }
}

bool ShenandoahTraversalGC::check_and_handle_cancelled_gc(ParallelTaskTerminator* terminator) {
  if (_heap->cancelled_concgc()) {
    ShenandoahCancelledTerminatorTerminator tt;
    while (! terminator->offer_termination(&tt));
    return true;
  }
  return false;
}

void ShenandoahTraversalGC::concurrent_traversal_collection() {
  ClassLoaderDataGraph::clear_claimed_marks();

  ShenandoahGCPhase phase_work(ShenandoahPhaseTimings::conc_traversal);
  if (!_heap->cancelled_concgc()) {
    uint nworkers = _heap->workers()->active_workers();
    task_queues()->reserve(nworkers);
    if (UseShenandoahOWST) {
      ShenandoahTaskTerminator terminator(nworkers, task_queues());
      ShenandoahConcurrentTraversalCollectionTask traversal_task(&terminator);
      _heap->workers()->run_task(&traversal_task);
    } else {
      ParallelTaskTerminator terminator(nworkers, task_queues());
      ShenandoahConcurrentTraversalCollectionTask traversal_task(&terminator);
      _heap->workers()->run_task(&traversal_task);
    }
  }

  if (!_heap->cancelled_concgc() && ShenandoahPreclean && _heap->shenandoahPolicy()->process_references()) {
    preclean_weak_refs();
  }

  if (_heap->cancelled_concgc()) {
    _task_queues->clear();
  }
  assert(_task_queues->is_empty(), "queues must be empty after traversal GC");
}

void ShenandoahTraversalGC::final_traversal_collection() {

  _heap->make_tlabs_parsable(true);

  if (!_heap->cancelled_concgc()) {
#if defined(COMPILER2) || INCLUDE_JVMCI
    DerivedPointerTable::clear();
#endif
    ShenandoahGCPhase phase_work(ShenandoahPhaseTimings::final_traversal_gc_work);
    uint nworkers = _heap->workers()->active_workers();
    task_queues()->reserve(nworkers);

    // Finish traversal
    ShenandoahRootProcessor rp(_heap, nworkers, ShenandoahPhaseTimings::final_traversal_gc_work);
    if (UseShenandoahOWST) {
      ShenandoahTaskTerminator terminator(nworkers, task_queues());
      ShenandoahFinalTraversalCollectionTask traversal_task(&rp, &terminator);
      _heap->workers()->run_task(&traversal_task);
    } else {
      ParallelTaskTerminator terminator(nworkers, task_queues());
      ShenandoahFinalTraversalCollectionTask traversal_task(&rp, &terminator);
      _heap->workers()->run_task(&traversal_task);
    }
#if defined(COMPILER2) || INCLUDE_JVMCI
    DerivedPointerTable::update_pointers();
#endif
  }

  if (!_heap->cancelled_concgc() && _heap->shenandoahPolicy()->process_references()) {
    weak_refs_work();
  }

  if (!_heap->cancelled_concgc() && _heap->shenandoahPolicy()->unload_classes()) {
    _heap->unload_classes_and_cleanup_tables(false);
    _heap->concurrentMark()->update_roots(ShenandoahPhaseTimings::final_traversal_update_roots);
  }

  if (!_heap->cancelled_concgc()) {
    // Still good? We can now trash the cset, and make final verification
    {
      ShenandoahGCPhase phase_cleanup(ShenandoahPhaseTimings::traversal_gc_cleanup);
      ShenandoahHeapLocker lock(_heap->lock());

      // Trash everything
      // Clear immediate garbage regions.
      ShenandoahHeapRegionSet* regions = _heap->regions();
      size_t active = regions->active_regions();
      ShenandoahFreeSet* free_regions = _heap->free_regions();
      free_regions->clear();
      for (size_t i = 0; i < active; i++) {
        ShenandoahHeapRegion* r = regions->get(i);
        if (r->is_humongous_start() && !r->has_live()) {
          HeapWord* humongous_obj = r->bottom() + BrooksPointer::word_size();
          assert(!_bitmap->isMarked(humongous_obj), "must not be marked");
          r->make_trash();
          while (i + 1 < active && regions->get(i + 1)->is_humongous_continuation()) {
            i++;
            r = regions->get(i);
            assert(r->is_humongous_continuation(), "must be humongous continuation");
            r->make_trash();
          }
        } else if (!r->is_empty() && !r->has_live()) {
          if (r->is_humongous()) {
            r->print_on(tty);
          }
          assert(!r->is_humongous(), "handled above");
          assert(!r->is_trash(), "must not already be trashed");
          r->make_trash();
        } else if (r->is_alloc_allowed()) {
          free_regions->add_region(r);
        }
      }
      _heap->collection_set()->clear();
      reset();
    }

    if (ShenandoahVerify) {
      _heap->verifier()->verify_after_traversal();
    }
  } else {
    // On cancellation path, fixup roots to make them consistent
    _heap->fixup_roots();
    reset();
  }

  assert(_task_queues->is_empty(), "queues must be empty after traversal GC");
  _heap->set_concurrent_traversal_in_progress(false);
}

void ShenandoahTraversalGC::reset() {
  _task_queues->clear();
}

ShenandoahObjToScanQueueSet* ShenandoahTraversalGC::task_queues() {
  return _task_queues;
}

jushort* ShenandoahTraversalGC::get_liveness(uint worker_id) {
  return _liveness_local[worker_id];
}

class ShenandoahTraversalCancelledGCYieldClosure : public YieldClosure {
private:
  ShenandoahHeap* const _heap;
public:
  ShenandoahTraversalCancelledGCYieldClosure() : _heap(ShenandoahHeap::heap()) {};
  virtual bool should_return() { return _heap->cancelled_concgc(); }
};

class ShenandoahTraversalPrecleanCompleteGCClosure : public VoidClosure {
public:
  void do_void() {
    ShenandoahHeap* sh = ShenandoahHeap::heap();
    ShenandoahTraversalGC* traversal_gc = sh->traversal_gc();
    assert(sh->shenandoahPolicy()->process_references(), "why else would we be here?");
    ReferenceProcessor* rp = sh->ref_processor();
    ParallelTaskTerminator terminator(1, traversal_gc->task_queues());
    ReferenceProcessorIsAliveMutator fix_alive(rp, sh->is_alive_closure());
    traversal_gc->main_loop((uint) 0, &terminator, false);
  }
};

class ShenandoahTraversalKeepAliveUpdateClosure : public OopClosure {
private:
  ShenandoahObjToScanQueue* _queue;
  Thread* _thread;
  ShenandoahTraversalGC* _traversal_gc;
  template <class T>
  inline void do_oop_nv(T* p) {
    _traversal_gc->process_oop<T, false /* string dedup */>(p, _thread, _queue);
  }

public:
  ShenandoahTraversalKeepAliveUpdateClosure(ShenandoahObjToScanQueue* q) :
    _queue(q), _thread(Thread::current()),
    _traversal_gc(ShenandoahHeap::heap()->traversal_gc()) {}

  void do_oop(narrowOop* p) { do_oop_nv(p); }
  void do_oop(oop* p)       { do_oop_nv(p); }
};

void ShenandoahTraversalGC::preclean_weak_refs() {
  // Pre-cleaning weak references before diving into STW makes sense at the
  // end of concurrent mark. This will filter out the references which referents
  // are alive. Note that ReferenceProcessor already filters out these on reference
  // discovery, and the bulk of work is done here. This phase processes leftovers
  // that missed the initial filtering, i.e. when referent was marked alive after
  // reference was discovered by RP.

  assert(_heap->shenandoahPolicy()->process_references(), "sanity");

  ShenandoahHeap* sh = ShenandoahHeap::heap();
  ReferenceProcessor* rp = sh->ref_processor();

  // Shortcut if no references were discovered to avoid winding up threads.
  if (!rp->has_discovered_references()) {
    return;
  }

  ReferenceProcessorMTDiscoveryMutator fix_mt_discovery(rp, false);
  ReferenceProcessorIsAliveMutator fix_alive(rp, sh->is_alive_closure());

  // Interrupt on cancelled GC
  ShenandoahTraversalCancelledGCYieldClosure yield;

  assert(task_queues()->is_empty(), "Should be empty");

  ShenandoahTraversalPrecleanCompleteGCClosure complete_gc;
  ShenandoahForwardedIsAliveClosure is_alive;
  ShenandoahTraversalKeepAliveUpdateClosure keep_alive(task_queues()->queue(0));
  ResourceMark rm;
  rp->preclean_discovered_references(&is_alive, &keep_alive,
                                     &complete_gc, &yield,
                                     NULL);
  assert(!sh->cancelled_concgc() || task_queues()->is_empty(), "Should be empty");
}

// Weak Reference Closures
class ShenandoahTraversalDrainMarkingStackClosure: public VoidClosure {
  uint _worker_id;
  ParallelTaskTerminator* _terminator;
  bool _reset_terminator;

public:
  ShenandoahTraversalDrainMarkingStackClosure(uint worker_id, ParallelTaskTerminator* t, bool reset_terminator = false):
    _worker_id(worker_id),
    _terminator(t),
    _reset_terminator(reset_terminator) {
  }

  void do_void() {
    assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Must be at a safepoint");

    ShenandoahHeap* sh = ShenandoahHeap::heap();
    ShenandoahTraversalGC* traversal_gc = sh->traversal_gc();
    assert(sh->shenandoahPolicy()->process_references(), "why else would we be here?");
    ReferenceProcessor* rp = sh->ref_processor();
    ReferenceProcessorIsAliveMutator fix_alive(rp, sh->is_alive_closure());

    traversal_gc->main_loop(_worker_id, _terminator, false);
    traversal_gc->flush_liveness(_worker_id);

    if (_reset_terminator) {
      _terminator->reset_for_reuse();
    }
  }
};

void ShenandoahTraversalGC::weak_refs_work() {
  assert(_heap->shenandoahPolicy()->process_references(), "sanity");

  ShenandoahHeap* sh = ShenandoahHeap::heap();

  ShenandoahPhaseTimings::Phase phase_root = ShenandoahPhaseTimings::weakrefs;

  ShenandoahGCPhase phase(phase_root);

  ReferenceProcessor* rp = sh->ref_processor();

  // NOTE: We cannot shortcut on has_discovered_references() here, because
  // we will miss marking JNI Weak refs then, see implementation in
  // ReferenceProcessor::process_discovered_references.
  weak_refs_work_doit();

  rp->verify_no_references_recorded();
  assert(!rp->discovery_enabled(), "Post condition");

}

class ShenandoahTraversalRefProcTaskProxy : public AbstractGangTask {

private:
  AbstractRefProcTaskExecutor::ProcessTask& _proc_task;
  ParallelTaskTerminator* _terminator;
public:

  ShenandoahTraversalRefProcTaskProxy(AbstractRefProcTaskExecutor::ProcessTask& proc_task,
                             ParallelTaskTerminator* t) :
    AbstractGangTask("Process reference objects in parallel"),
    _proc_task(proc_task),
    _terminator(t) {
  }

  void work(uint worker_id) {
    assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Must be at a safepoint");
    ShenandoahHeap* heap = ShenandoahHeap::heap();
    ShenandoahTraversalDrainMarkingStackClosure complete_gc(worker_id, _terminator);

    ShenandoahForwardedIsAliveClosure is_alive;
    ShenandoahTraversalKeepAliveUpdateClosure keep_alive(heap->traversal_gc()->task_queues()->queue(worker_id));
    _proc_task.work(worker_id, is_alive, keep_alive, complete_gc);
  }
};

class ShenandoahTraversalRefEnqueueTaskProxy : public AbstractGangTask {

private:
  AbstractRefProcTaskExecutor::EnqueueTask& _enqueue_task;

public:

  ShenandoahTraversalRefEnqueueTaskProxy(AbstractRefProcTaskExecutor::EnqueueTask& enqueue_task) :
    AbstractGangTask("Enqueue reference objects in parallel"),
    _enqueue_task(enqueue_task) {
  }

  void work(uint worker_id) {
    _enqueue_task.work(worker_id);
  }
};

class ShenandoahTraversalRefProcTaskExecutor : public AbstractRefProcTaskExecutor {

private:
  WorkGang* _workers;

public:

  ShenandoahTraversalRefProcTaskExecutor(WorkGang* workers) :
    _workers(workers) {
  }

  // Executes a task using worker threads.
  void execute(ProcessTask& task) {
    assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "Must be at a safepoint");

    // Shortcut execution if task is empty.
    // This should be replaced with the generic ReferenceProcessor shortcut,
    // see JDK-8181214, JDK-8043575, JDK-6938732.
    if (task.is_empty()) {
      return;
    }

    ShenandoahHeap* heap = ShenandoahHeap::heap();
    ShenandoahTraversalGC* traversal_gc = heap->traversal_gc();
    uint nworkers = _workers->active_workers();
    traversal_gc->task_queues()->reserve(nworkers);
    if (UseShenandoahOWST) {
      ShenandoahTaskTerminator terminator(nworkers, traversal_gc->task_queues());
      ShenandoahTraversalRefProcTaskProxy proc_task_proxy(task, &terminator);
      _workers->run_task(&proc_task_proxy);
    } else {
      ParallelTaskTerminator terminator(nworkers, traversal_gc->task_queues());
      ShenandoahTraversalRefProcTaskProxy proc_task_proxy(task, &terminator);
      _workers->run_task(&proc_task_proxy);
    }
  }

  void execute(EnqueueTask& task) {
    ShenandoahTraversalRefEnqueueTaskProxy enqueue_task_proxy(task);
    _workers->run_task(&enqueue_task_proxy);
  }
};

void ShenandoahTraversalGC::weak_refs_work_doit() {
  ShenandoahHeap* sh = ShenandoahHeap::heap();

  ReferenceProcessor* rp = sh->ref_processor();

  ShenandoahPhaseTimings::Phase phase_process = ShenandoahPhaseTimings::weakrefs_process;
  ShenandoahPhaseTimings::Phase phase_enqueue = ShenandoahPhaseTimings::weakrefs_enqueue;

  ReferenceProcessorIsAliveMutator fix_alive(rp, sh->is_alive_closure());

  WorkGang* workers = sh->workers();
  uint nworkers = workers->active_workers();

  // Setup collector policy for softref cleaning.
  bool clear_soft_refs = sh->collector_policy()->use_should_clear_all_soft_refs(true /* bogus arg*/);
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
  ShenandoahTraversalDrainMarkingStackClosure complete_gc(serial_worker_id, &terminator, /* reset_terminator = */ true);

  ShenandoahTraversalRefProcTaskExecutor executor(workers);

  ReferenceProcessorPhaseTimes pt(sh->gc_timer(), rp->num_q());

  {
    ShenandoahGCPhase phase(phase_process);

    ShenandoahForwardedIsAliveClosure is_alive;
    ShenandoahTraversalKeepAliveUpdateClosure keep_alive(task_queues()->queue(serial_worker_id));
    rp->process_discovered_references(&is_alive, &keep_alive,
                                      &complete_gc, &executor,
                                      &pt);
    pt.print_all_references();

    assert(!_heap->cancelled_concgc() || task_queues()->is_empty(), "Should be empty");
  }

  if (_heap->cancelled_concgc()) return;

  {
    ShenandoahGCPhase phase(phase_enqueue);
    rp->enqueue_discovered_references(&executor, &pt);
    pt.print_enqueue_phase();
  }
}
