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

#include "gc/shared/gcTraceTime.inline.hpp"
#include "gc/shared/workgroup.hpp"
#include "gc/shared/taskqueue.inline.hpp"
#include "gc/shenandoah/shenandoahBarrierSet.hpp"
#include "gc/shenandoah/shenandoahCollectionSet.hpp"
#include "gc/shenandoah/shenandoahCollectorPolicy.hpp"
#include "gc/shenandoah/shenandoahConnectionMatrix.inline.hpp"
#include "gc/shenandoah/shenandoahFreeSet.hpp"
#include "gc/shenandoah/shenandoahPhaseTimings.hpp"
#include "gc/shenandoah/shenandoahHeapRegionSet.hpp"
#include "gc/shenandoah/shenandoahHeap.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahOopClosures.inline.hpp"
#include "gc/shenandoah/shenandoahPartialGC.hpp"
#include "gc/shenandoah/shenandoahRootProcessor.hpp"
#include "gc/shenandoah/shenandoahTaskqueue.hpp"
#include "gc/shenandoah/shenandoahUtils.hpp"
#include "gc/shenandoah/shenandoahVerifier.hpp"
#include "gc/shenandoah/shenandoahWorkGroup.hpp"
#include "gc/shenandoah/shenandoahWorkerPolicy.hpp"

#include "memory/iterator.hpp"
#include "runtime/safepoint.hpp"

class ShenandoahPartialEvacuateUpdateRootsClosure : public OopClosure {
  ShenandoahPartialGC* _partial_gc;
  Thread* _thread;
  ShenandoahObjToScanQueue* _queue;
private:
  template <class T>
  void do_oop_work(T* p) { _partial_gc->process_oop<T, false>(p, _thread, _queue); }
public:
  ShenandoahPartialEvacuateUpdateRootsClosure(ShenandoahObjToScanQueue* q) :
    _partial_gc(ShenandoahHeap::heap()->partial_gc()),
    _thread(Thread::current()), _queue(q) {}
  void do_oop(oop* p) {
    assert(! ShenandoahHeap::heap()->is_in_reserved(p), "sanity");
    do_oop_work(p);
  }
  void do_oop(narrowOop* p) { do_oop_work(p); }
};

class ShenandoahPartialSATBBufferClosure : public SATBBufferClosure {
private:
  ShenandoahObjToScanQueue* _queue;
  ShenandoahPartialGC* _partial_gc;
  Thread* _thread;
public:
  ShenandoahPartialSATBBufferClosure(ShenandoahObjToScanQueue* q) :
    _queue(q), _partial_gc(ShenandoahHeap::heap()->partial_gc()), _thread(Thread::current()) { }

  void do_buffer(void** buffer, size_t size) {
    for (size_t i = 0; i < size; ++i) {
      oop* p = (oop*) &buffer[i];
      oop obj = oopDesc::load_heap_oop(p);
      _queue->push(obj);
    }
  }
};

class ShenandoahPartialSATBThreadsClosure : public ThreadClosure {
  ShenandoahPartialSATBBufferClosure* _satb_cl;
  int _thread_parity;

 public:
  ShenandoahPartialSATBThreadsClosure(ShenandoahPartialSATBBufferClosure* satb_cl) :
    _satb_cl(satb_cl),
    _thread_parity(Threads::thread_claim_parity()) {}

  void do_thread(Thread* thread) {
    if (thread->is_Java_thread()) {
      if (thread->claim_oops_do(true, _thread_parity)) {
        JavaThread* jt = (JavaThread*)thread;
        jt->satb_mark_queue().apply_closure_and_empty(_satb_cl);
      }
    } else if (thread->is_VM_thread()) {
      if (thread->claim_oops_do(true, _thread_parity)) {
        JavaThread::satb_mark_queue_set().shared_satb_queue()->apply_closure_and_empty(_satb_cl);
      }
    }
  }
};

class ShenandoahInitPartialCollectionTask : public AbstractGangTask {
private:
  ShenandoahRootProcessor* _rp;
  ShenandoahHeap* _heap;
public:
  ShenandoahInitPartialCollectionTask(ShenandoahRootProcessor* rp) :
    AbstractGangTask("Shenandoah Init Partial Collection"),
    _rp(rp),
    _heap(ShenandoahHeap::heap()) {}

  void work(uint worker_id) {
    ShenandoahObjToScanQueueSet* queues = _heap->partial_gc()->task_queues();
    ShenandoahObjToScanQueue* q = queues->queue(worker_id);

    // Step 1: Process ordinary GC roots.
    {
      ShenandoahPartialEvacuateUpdateRootsClosure roots_cl(q);
      CLDToOopClosure cld_cl(&roots_cl);
      MarkingCodeBlobClosure code_cl(&roots_cl, CodeBlobToOopClosure::FixRelocations);
      _rp->process_all_roots(&roots_cl, &roots_cl, &cld_cl, &code_cl, worker_id);
    }
  }
};

class ShenandoahConcurrentPartialCollectionTask : public AbstractGangTask {
private:
  ParallelTaskTerminator* _terminator;
  ShenandoahHeapRegionSet* _root_regions;
  ShenandoahHeap* _heap;
public:
  ShenandoahConcurrentPartialCollectionTask(ParallelTaskTerminator* terminator,
                                            ShenandoahHeapRegionSet* root_regions) :
    AbstractGangTask("Shenandoah Concurrent Partial Collection"),
    _terminator(terminator), _root_regions(root_regions),
    _heap(ShenandoahHeap::heap()) {}

  void work(uint worker_id) {
    ShenandoahPartialGC* partial_gc = _heap->partial_gc();
    ShenandoahObjToScanQueueSet* queues = partial_gc->task_queues();
    ShenandoahObjToScanQueue* q = queues->queue(worker_id);

    if (partial_gc->check_and_handle_cancelled_gc(_terminator)) return;

    ShenandoahPartialEvacuateUpdateHeapClosure cl(q);

    // Step 2: Process all root regions.
    {
      ShenandoahHeapRegion* r = _root_regions->claim_next();
      while (r != NULL) {
        assert(r->is_root(), "must be root region");
        _heap->marked_object_oop_safe_iterate(r, &cl);
        if (partial_gc->check_and_handle_cancelled_gc(_terminator)) return;
        r = _root_regions->claim_next();
      }
    }
    if (partial_gc->check_and_handle_cancelled_gc(_terminator)) return;

    // Step 3: Drain all outstanding work in queues.
    partial_gc->main_loop<true>(worker_id, _terminator);
  }
};

class ShenandoahFinalPartialCollectionTask : public AbstractGangTask {
private:
  ParallelTaskTerminator* _terminator;
  ShenandoahHeap* _heap;
public:
  ShenandoahFinalPartialCollectionTask(ParallelTaskTerminator* terminator) :
    AbstractGangTask("Shenandoah Final Partial Collection"),
    _terminator(terminator),
    _heap(ShenandoahHeap::heap()) {}

  void work(uint worker_id) {
    ShenandoahPartialGC* partial_gc = _heap->partial_gc();

    ShenandoahObjToScanQueueSet* queues = partial_gc->task_queues();
    ShenandoahObjToScanQueue* q = queues->queue(worker_id);

    // Drain outstanding SATB queues.
    {
      ShenandoahPartialSATBBufferClosure satb_cl(q);
      // Process remaining finished SATB buffers.
      SATBMarkQueueSet& satb_mq_set = JavaThread::satb_mark_queue_set();
      while (satb_mq_set.apply_closure_to_completed_buffer(&satb_cl));
      // Process remaining threads SATB buffers.
      ShenandoahPartialSATBThreadsClosure tc(&satb_cl);
      Threads::threads_do(&tc);
    }

    // Finally drain all outstanding work in queues.
    partial_gc->main_loop<false>(worker_id, _terminator);

  }
};

class ShenandoahPartialCollectionCleanupTask : public AbstractGangTask {
private:
  ShenandoahHeap* _heap;
public:
  ShenandoahPartialCollectionCleanupTask() :
          AbstractGangTask("Shenandoah Partial Collection Cleanup"),
          _heap(ShenandoahHeap::heap()) {
    _heap->collection_set()->clear_current_index();
  }

  void work(uint worker_id) {
    ShenandoahCollectionSet* cset = _heap->collection_set();
    ShenandoahHeapRegion* r = cset->claim_next();
    while (r != NULL) {
      HeapWord* bottom = r->bottom();
      HeapWord* top = _heap->complete_top_at_mark_start(r->bottom());
      if (top > bottom) {
        _heap->complete_mark_bit_map()->clear_range_large(MemRegion(bottom, top));
      }
      r = cset->claim_next();
    }
  }

};

ShenandoahPartialGC::ShenandoahPartialGC(ShenandoahHeap* heap, size_t num_regions) :
  _heap(heap),
  _matrix(heap->connection_matrix()),
  _root_regions(new ShenandoahHeapRegionSet(num_regions)),
  _task_queues(new ShenandoahObjToScanQueueSet(heap->max_workers())) {

  assert(_matrix != NULL, "need matrix");

  uint num_queues = heap->max_workers();
  for (uint i = 0; i < num_queues; ++i) {
    ShenandoahObjToScanQueue* task_queue = new ShenandoahObjToScanQueue();
    task_queue->initialize();
    _task_queues->register_queue(i, task_queue);
  }

  from_idxs = NEW_C_HEAP_ARRAY(size_t, ShenandoahPartialInboundThreshold, mtGC);
  set_has_work(false);
}

ShenandoahPartialGC::~ShenandoahPartialGC() {
  FREE_C_HEAP_ARRAY(size_t, from_idxs);
}

bool ShenandoahPartialGC::prepare() {
  _heap->collection_set()->clear();
  assert(_heap->collection_set()->count() == 0, "collection set not clear");

  _heap->make_tlabs_parsable(true);

  ShenandoahConnectionMatrix* matrix = _heap->connection_matrix();

  if (UseShenandoahMatrix && PrintShenandoahMatrix) {
    LogTarget(Info, gc) lt;
    LogStream ls(lt);
    _heap->connection_matrix()->print_on(&ls);
  }

  ShenandoahHeapRegionSet* regions = _heap->regions();
  ShenandoahCollectionSet* collection_set = _heap->collection_set();
  size_t num_regions = _heap->num_regions();

  // First pass: reset all roots
  for (uint to_idx = 0; to_idx < num_regions; to_idx++) {
    ShenandoahHeapRegion* r = regions->get(to_idx);
    r->set_root(false);
  }

  // Second pass: find collection set, and mark root candidates
  _heap->shenandoahPolicy()->choose_collection_set(collection_set, true);

  // Shortcut: no cset, bail
  size_t num_cset = collection_set->count();

  if (num_cset == 0) {
    log_info(gc, ergo)("No regions with fewer inbound connections than threshold (" UINTX_FORMAT ")",
                       ShenandoahPartialInboundThreshold);
    return false;
  }

  // Final pass: rebuild free set and region set.
  ShenandoahFreeSet* _free_regions = _heap->free_regions();
  _root_regions->clear();
  _free_regions->clear();

  assert(_root_regions->count() == 0, "must be cleared");

  for (uint from_idx = 0; from_idx < num_regions; from_idx++) {
    ShenandoahHeapRegion* r = regions->get(from_idx);
    if (r->is_alloc_allowed()) {
      _free_regions->add_region(r);
    }
    if (r->is_root() && !r->in_collection_set()) {
      _root_regions->add_region(r);
      matrix->clear_region_outbound(from_idx);

      // Since root region can be allocated at, we should bound the scans
      // in it at current top. Otherwise, one thread may evacuate objects
      // to that root region, while another would try to scan newly evac'ed
      // objects under the race.
      r->set_concurrent_iteration_safe_limit(r->top());
    }
  }

  log_info(gc,ergo)("Got "SIZE_FORMAT" collection set regions, "SIZE_FORMAT" root regions",
                     collection_set->count(), _root_regions->count());

  return true;
}

void ShenandoahPartialGC::init_partial_collection() {
  assert(ShenandoahSafepoint::is_at_shenandoah_safepoint(), "STW partial GC");

  _heap->set_alloc_seq_gc_start();

  if (ShenandoahVerify) {
    _heap->verifier()->verify_before_partial();
  }

  {
    ShenandoahGCPhase phase_prepare(ShenandoahPhaseTimings::partial_gc_prepare);
    ShenandoahHeapLocker lock(_heap->lock());
    bool has_work = prepare();
    set_has_work(has_work);
  }

  if (!has_work()) {
    reset();
    return;
  }

  _heap->set_concurrent_partial_in_progress(true);

  {
    ShenandoahGCPhase phase_work(ShenandoahPhaseTimings::init_partial_gc_work);
    assert(_task_queues->is_empty(), "queues must be empty before partial GC");

#if defined(COMPILER2) || INCLUDE_JVMCI
    DerivedPointerTable::clear();
#endif

    {
      uint nworkers = _heap->workers()->active_workers();
      ShenandoahRootProcessor rp(_heap, nworkers, ShenandoahPhaseTimings::init_partial_gc_work);

      if (UseShenandoahOWST) {
        ShenandoahTaskTerminator terminator(nworkers, task_queues());
        ShenandoahInitPartialCollectionTask partial_task(&rp);
        _heap->workers()->run_task(&partial_task);
      } else {
        ParallelTaskTerminator terminator(nworkers, task_queues());
        ShenandoahInitPartialCollectionTask partial_task(&rp);
        _heap->workers()->run_task(&partial_task);
      }
    }

#if defined(COMPILER2) || INCLUDE_JVMCI
    DerivedPointerTable::update_pointers();
#endif
    if (_heap->cancelled_concgc()) {
      _heap->fixup_roots();
      reset();
      _heap->set_concurrent_partial_in_progress(false);
    }
  }
}

template <bool DO_SATB>
void ShenandoahPartialGC::main_loop(uint worker_id, ParallelTaskTerminator* terminator) {
  ShenandoahObjToScanQueueSet* queues = task_queues();
  ShenandoahObjToScanQueue* q = queues->queue(worker_id);

  uintx stride = ShenandoahMarkLoopStride;
  ShenandoahPartialEvacuateUpdateHeapClosure cl(q);
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
        oop obj = task.obj();
        assert(!oopDesc::is_null(obj), "must not be null");
        obj->oop_iterate(&cl);
      } else {
        assert(q->is_empty(), "Must be empty");
        q = queues->claim_next();
        break;
      }
    }
  }

  // Normal loop.
  q = queues->queue(worker_id);
  ShenandoahPartialSATBBufferClosure satb_cl(q);
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
        oop obj = task.obj();
        assert(!oopDesc::is_null(obj), "must not be null");
        obj->oop_iterate(&cl);
      } else {
        if (terminator->offer_termination()) return;
      }
    }
  }
}

bool ShenandoahPartialGC::check_and_handle_cancelled_gc(ParallelTaskTerminator* terminator) {
  if (_heap->cancelled_concgc()) {
    ShenandoahCancelledTerminatorTerminator tt;
    while (! terminator->offer_termination(&tt));
    return true;
  }
  return false;
}

void ShenandoahPartialGC::concurrent_partial_collection() {
  assert(has_work(), "Performance: should only be here when there is work");

  ShenandoahGCPhase phase_work(ShenandoahPhaseTimings::conc_partial);
  if (!_heap->cancelled_concgc()) {
    uint nworkers = _heap->workers()->active_workers();
    task_queues()->reserve(nworkers);
    if (UseShenandoahOWST) {
      ShenandoahTaskTerminator terminator(nworkers, task_queues());
      ShenandoahConcurrentPartialCollectionTask partial_task(&terminator, _root_regions);
      _heap->workers()->run_task(&partial_task);
    } else {
      ParallelTaskTerminator terminator(nworkers, task_queues());
      ShenandoahConcurrentPartialCollectionTask partial_task(&terminator, _root_regions);
      _heap->workers()->run_task(&partial_task);
    }
  }

  if (_heap->cancelled_concgc()) {
    _task_queues->clear();
  }
  assert(_task_queues->is_empty(), "queues must be empty after partial GC");
}

void ShenandoahPartialGC::final_partial_collection() {
  assert(has_work(), "Performance: should only be here when there is work");

  if (!_heap->cancelled_concgc()) {
    ShenandoahGCPhase phase_work(ShenandoahPhaseTimings::final_partial_gc_work);
    uint nworkers = _heap->workers()->active_workers();
    task_queues()->reserve(nworkers);

    StrongRootsScope scope(nworkers);
    if (UseShenandoahOWST) {
      ShenandoahTaskTerminator terminator(nworkers, task_queues());
      ShenandoahFinalPartialCollectionTask partial_task(&terminator);
      _heap->workers()->run_task(&partial_task);
    } else {
      ParallelTaskTerminator terminator(nworkers, task_queues());
      ShenandoahFinalPartialCollectionTask partial_task(&terminator);
      _heap->workers()->run_task(&partial_task);
    }
  }

  if (!_heap->cancelled_concgc()) {
    // Still good? Update the roots then
    _heap->concurrentMark()->update_roots(ShenandoahPhaseTimings::final_partial_gc_work);
  }

  if (!_heap->cancelled_concgc()) {
    // Still good? We can now trash the cset, and make final verification
    {
      ShenandoahGCPhase phase_cleanup(ShenandoahPhaseTimings::partial_gc_cleanup);
      ShenandoahCollectionSet* cset = _heap->collection_set();
      ShenandoahHeapLocker lock(_heap->lock());

      ShenandoahPartialCollectionCleanupTask cleanup;
      _heap->workers()->run_task(&cleanup);

      // Trash everything when bitmaps are cleared.
      cset->clear_current_index();
      ShenandoahHeapRegion* r;
      while((r = cset->next()) != NULL) {
        r->make_trash();
      }
      cset->clear();

      reset();
    }

    if (ShenandoahVerify) {
      _heap->verifier()->verify_after_partial();
    }
  } else {
    // On cancellation path, fixup roots to make them consistent
    _heap->fixup_roots();
    reset();
  }

  assert(_task_queues->is_empty(), "queues must be empty after partial GC");
  _heap->set_concurrent_partial_in_progress(false);
}

void ShenandoahPartialGC::reset() {
  _task_queues->clear();

  _root_regions->clear_current_index();
  ShenandoahHeapRegion* r;
  while((r = _root_regions->claim_next()) != NULL) {
    r->set_root(false);
  }
  _root_regions->clear();

  set_has_work(false);
}

void ShenandoahPartialGC::set_has_work(bool value) {
  _has_work.set_cond(value);
}

bool ShenandoahPartialGC::has_work() {
  return _has_work.is_set();
}

ShenandoahObjToScanQueueSet* ShenandoahPartialGC::task_queues() {
  return _task_queues;
}
