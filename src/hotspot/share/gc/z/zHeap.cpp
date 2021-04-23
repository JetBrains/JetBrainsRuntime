/*
 * Copyright (c) 2015, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
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
 */

#include "precompiled.hpp"
#include "gc/shared/oopStorage.hpp"
#include "gc/z/zAddress.hpp"
#include "gc/z/zGlobals.hpp"
#include "gc/z/zHeap.inline.hpp"
#include "gc/z/zHeapIterator.hpp"
#include "gc/z/zList.inline.hpp"
#include "gc/z/zLock.inline.hpp"
#include "gc/z/zMark.inline.hpp"
#include "gc/z/zOopClosures.inline.hpp"
#include "gc/z/zPage.inline.hpp"
#include "gc/z/zPageTable.inline.hpp"
#include "gc/z/zRelocationSet.inline.hpp"
#include "gc/z/zResurrection.hpp"
#include "gc/z/zRootsIterator.hpp"
#include "gc/z/zStat.hpp"
#include "gc/z/zTask.hpp"
#include "gc/z/zThread.hpp"
#include "gc/z/zTracer.inline.hpp"
#include "gc/z/zVirtualMemory.inline.hpp"
#include "gc/z/zWorkers.inline.hpp"
#include "logging/log.hpp"
#include "memory/resourceArea.hpp"
#include "oops/oop.inline.hpp"
#include "runtime/safepoint.hpp"
#include "runtime/thread.hpp"
#include "utilities/align.hpp"
#include "utilities/debug.hpp"

static const ZStatSampler ZSamplerHeapUsedBeforeMark("Memory", "Heap Used Before Mark", ZStatUnitBytes);
static const ZStatSampler ZSamplerHeapUsedAfterMark("Memory", "Heap Used After Mark", ZStatUnitBytes);
static const ZStatSampler ZSamplerHeapUsedBeforeRelocation("Memory", "Heap Used Before Relocation", ZStatUnitBytes);
static const ZStatSampler ZSamplerHeapUsedAfterRelocation("Memory", "Heap Used After Relocation", ZStatUnitBytes);
static const ZStatCounter ZCounterUndoPageAllocation("Memory", "Undo Page Allocation", ZStatUnitOpsPerSecond);
static const ZStatCounter ZCounterOutOfMemory("Memory", "Out Of Memory", ZStatUnitOpsPerSecond);

ZHeap* ZHeap::_heap = NULL;

ZHeap::ZHeap() :
    _workers(),
    _object_allocator(_workers.nworkers()),
    _page_allocator(heap_min_size(), heap_max_size(), heap_max_reserve_size()),
    _pagetable(),
    _mark(&_workers, &_pagetable),
    _reference_processor(&_workers),
    _weak_roots_processor(&_workers),
    _relocate(&_workers),
    _relocation_set(),
    _serviceability(heap_min_size(), heap_max_size()) {
  // Install global heap instance
  assert(_heap == NULL, "Already initialized");
  _heap = this;

  // Update statistics
  ZStatHeap::set_at_initialize(heap_max_size(), heap_max_reserve_size());
}

size_t ZHeap::heap_min_size() const {
  const size_t aligned_min_size = align_up(InitialHeapSize, ZPageSizeMin);
  return MIN2(aligned_min_size, heap_max_size());
}

size_t ZHeap::heap_max_size() const {
  const size_t aligned_max_size = align_up(MaxHeapSize, ZPageSizeMin);
  return MIN2(aligned_max_size, ZAddressOffsetMax);
}

size_t ZHeap::heap_max_reserve_size() const {
  // Reserve one small page per worker plus one shared medium page. This is still just
  // an estimate and doesn't guarantee that we can't run out of memory during relocation.
  const size_t max_reserve_size = (_workers.nworkers() * ZPageSizeSmall) + ZPageSizeMedium;
  return MIN2(max_reserve_size, heap_max_size());
}

bool ZHeap::is_initialized() const {
  return _page_allocator.is_initialized();
}

size_t ZHeap::min_capacity() const {
  return heap_min_size();
}

size_t ZHeap::max_capacity() const {
  return _page_allocator.max_capacity();
}

size_t ZHeap::current_max_capacity() const {
  return _page_allocator.current_max_capacity();
}

size_t ZHeap::capacity() const {
  return _page_allocator.capacity();
}

size_t ZHeap::max_reserve() const {
  return _page_allocator.max_reserve();
}

size_t ZHeap::used_high() const {
  return _page_allocator.used_high();
}

size_t ZHeap::used_low() const {
  return _page_allocator.used_low();
}

size_t ZHeap::used() const {
  return _page_allocator.used();
}

size_t ZHeap::allocated() const {
  return _page_allocator.allocated();
}

size_t ZHeap::reclaimed() const {
  return _page_allocator.reclaimed();
}

size_t ZHeap::tlab_capacity() const {
  return capacity();
}

size_t ZHeap::tlab_used() const {
  return _object_allocator.used();
}

size_t ZHeap::max_tlab_size() const {
  return ZObjectSizeLimitSmall;
}

size_t ZHeap::unsafe_max_tlab_alloc() const {
  size_t size = _object_allocator.remaining();

  if (size < MinTLABSize) {
    // The remaining space in the allocator is not enough to
    // fit the smallest possible TLAB. This means that the next
    // TLAB allocation will force the allocator to get a new
    // backing page anyway, which in turn means that we can then
    // fit the largest possible TLAB.
    size = max_tlab_size();
  }

  return MIN2(size, max_tlab_size());
}

bool ZHeap::is_in(uintptr_t addr) const {
  if (addr < ZAddressReservedStart() || addr >= ZAddressReservedEnd()) {
    return false;
  }

  const ZPage* const page = _pagetable.get(addr);
  if (page != NULL) {
    return page->is_in(addr);
  }

  return false;
}

uintptr_t ZHeap::block_start(uintptr_t addr) const {
  const ZPage* const page = _pagetable.get(addr);
  return page->block_start(addr);
}

size_t ZHeap::block_size(uintptr_t addr) const {
  const ZPage* const page = _pagetable.get(addr);
  return page->block_size(addr);
}

bool ZHeap::block_is_obj(uintptr_t addr) const {
  const ZPage* const page = _pagetable.get(addr);
  return page->block_is_obj(addr);
}

uint ZHeap::nconcurrent_worker_threads() const {
  return _workers.nconcurrent();
}

uint ZHeap::nconcurrent_no_boost_worker_threads() const {
  return _workers.nconcurrent_no_boost();
}

void ZHeap::set_boost_worker_threads(bool boost) {
  _workers.set_boost(boost);
}

void ZHeap::worker_threads_do(ThreadClosure* tc) const {
  _workers.threads_do(tc);
}

void ZHeap::print_worker_threads_on(outputStream* st) const {
  _workers.print_threads_on(st);
}

void ZHeap::out_of_memory() {
  ResourceMark rm;

  ZStatInc(ZCounterOutOfMemory);
  log_info(gc)("Out Of Memory (%s)", Thread::current()->name());
}

ZPage* ZHeap::alloc_page(uint8_t type, size_t size, ZAllocationFlags flags) {
  ZPage* const page = _page_allocator.alloc_page(type, size, flags);
  if (page != NULL) {
    // Update pagetable
    _pagetable.insert(page);
  }

  return page;
}

void ZHeap::undo_alloc_page(ZPage* page) {
  assert(page->is_allocating(), "Invalid page state");

  ZStatInc(ZCounterUndoPageAllocation);
  log_trace(gc)("Undo page allocation, thread: " PTR_FORMAT " (%s), page: " PTR_FORMAT ", size: " SIZE_FORMAT,
                ZThread::id(), ZThread::name(), p2i(page), page->size());

  release_page(page, false /* reclaimed */);
}

bool ZHeap::retain_page(ZPage* page) {
  return page->inc_refcount();
}

void ZHeap::release_page(ZPage* page, bool reclaimed) {
  if (page->dec_refcount()) {
    _page_allocator.free_page(page, reclaimed);
  }
}

void ZHeap::flip_views() {
  // For debugging only
  if (ZUnmapBadViews) {
    // Flip pages
    ZPageTableIterator iter(&_pagetable);
    for (ZPage* page; iter.next(&page);) {
      if (!page->is_detached()) {
        _page_allocator.flip_page(page);
      }
    }

    // Flip pre-mapped memory
    _page_allocator.flip_pre_mapped();
  }
}

void ZHeap::mark_start() {
  assert(SafepointSynchronize::is_at_safepoint(), "Should be at safepoint");

  // Update statistics
  ZStatSample(ZSamplerHeapUsedBeforeMark, used());

  // Retire TLABs
  _object_allocator.retire_tlabs();

  // Flip address view
  ZAddressMasks::flip_to_marked();
  flip_views();

  // Reset allocated/reclaimed/used statistics
  _page_allocator.reset_statistics();

  // Reset encountered/dropped/enqueued statistics
  _reference_processor.reset_statistics();

  // Enter mark phase
  ZGlobalPhase = ZPhaseMark;

  // Reset marking information and mark roots
  _mark.start();

  // Update statistics
  ZStatHeap::set_at_mark_start(capacity(), used());
}

void ZHeap::mark() {
  _mark.mark();
}

void ZHeap::mark_flush_and_free(Thread* thread) {
  _mark.flush_and_free(thread);
}

class ZFixupPartialLoadsTask : public ZTask {
private:
  ZThreadRootsIterator _thread_roots;

public:
  ZFixupPartialLoadsTask() :
      ZTask("ZFixupPartialLoadsTask"),
      _thread_roots() {}

  virtual void work() {
    ZMarkRootOopClosure cl;
    _thread_roots.oops_do(&cl);
  }
};

void ZHeap::fixup_partial_loads() {
  ZFixupPartialLoadsTask task;
  _workers.run_parallel(&task);
}

bool ZHeap::mark_end() {
  assert(SafepointSynchronize::is_at_safepoint(), "Should be at safepoint");

  // C2 can generate code where a safepoint poll is inserted
  // between a load and the associated load barrier. To handle
  // this case we need to rescan the thread stack here to make
  // sure such oops are marked.
  fixup_partial_loads();

  // Try end marking
  if (!_mark.end()) {
    // Marking not completed, continue concurrent mark
    return false;
  }

  // Enter mark completed phase
  ZGlobalPhase = ZPhaseMarkCompleted;

  // Resize metaspace
  MetaspaceGC::compute_new_size();

  // Update statistics
  ZStatSample(ZSamplerHeapUsedAfterMark, used());
  ZStatHeap::set_at_mark_end(capacity(), allocated(), used());

  // Block resurrection of weak/phantom references
  ZResurrection::block();

  // Process weak roots
  _weak_roots_processor.process_weak_roots();

  // Verification
  if (VerifyBeforeGC || VerifyDuringGC || VerifyAfterGC) {
    Universe::verify();
  }

  return true;
}

void ZHeap::keep_alive(oop obj) {
  ZBarrier::keep_alive_barrier_on_oop(obj);
}

void ZHeap::set_soft_reference_policy(bool clear) {
  _reference_processor.set_soft_reference_policy(clear);
}

void ZHeap::process_non_strong_references() {
  // Process Soft/Weak/Final/PhantomReferences
  _reference_processor.process_references();

  // Process concurrent weak roots
  _weak_roots_processor.process_concurrent_weak_roots();

  // Unblock resurrection of weak/phantom references
  ZResurrection::unblock();

  // Enqueue Soft/Weak/Final/PhantomReferences. Note that this
  // must be done after unblocking resurrection. Otherwise the
  // Finalizer thread could call Reference.get() on the Finalizers
  // that were just enqueued, which would incorrectly return null
  // during the resurrection block window, since such referents
  // are only Finalizable marked.
  _reference_processor.enqueue_references();
}

void ZHeap::destroy_detached_pages() {
  ZList<ZPage> list;

  _page_allocator.flush_detached_pages(&list);

  for (ZPage* page = list.remove_first(); page != NULL; page = list.remove_first()) {
    // Remove pagetable entry
    _pagetable.remove(page);

    // Delete the page
    _page_allocator.destroy_page(page);
  }
}

void ZHeap::select_relocation_set() {
  // Register relocatable pages with selector
  ZRelocationSetSelector selector;
  ZPageTableIterator iter(&_pagetable);
  for (ZPage* page; iter.next(&page);) {
    if (!page->is_relocatable()) {
      // Not relocatable, don't register
      continue;
    }

    if (page->is_marked()) {
      // Register live page
      selector.register_live_page(page);
    } else {
      // Register garbage page
      selector.register_garbage_page(page);

      // Reclaim page immediately
      release_page(page, true /* reclaimed */);
    }
  }

  // Select pages to relocate
  selector.select(&_relocation_set);

  // Update statistics
  ZStatRelocation::set_at_select_relocation_set(selector.relocating());
  ZStatHeap::set_at_select_relocation_set(selector.live(),
                                          selector.garbage(),
                                          reclaimed());
}

void ZHeap::prepare_relocation_set() {
  ZRelocationSetIterator iter(&_relocation_set);
  for (ZPage* page; iter.next(&page);) {
    // Prepare for relocation
    page->set_forwarding();

    // Update pagetable
    _pagetable.set_relocating(page);
  }
}

void ZHeap::reset_relocation_set() {
  ZRelocationSetIterator iter(&_relocation_set);
  for (ZPage* page; iter.next(&page);) {
    // Reset relocation information
    page->reset_forwarding();

    // Update pagetable
    _pagetable.clear_relocating(page);
  }
}

void ZHeap::relocate_start() {
  assert(SafepointSynchronize::is_at_safepoint(), "Should be at safepoint");

  // Update statistics
  ZStatSample(ZSamplerHeapUsedBeforeRelocation, used());

  // Flip address view
  ZAddressMasks::flip_to_remapped();
  flip_views();

  // Remap TLABs
  _object_allocator.remap_tlabs();

  // Enter relocate phase
  ZGlobalPhase = ZPhaseRelocate;

  // Update statistics
  ZStatHeap::set_at_relocate_start(capacity(), allocated(), used());

  // Remap/Relocate roots
  _relocate.start();
}

uintptr_t ZHeap::relocate_object(uintptr_t addr) {
  assert(ZGlobalPhase == ZPhaseRelocate, "Relocate not allowed");
  ZPage* const page = _pagetable.get(addr);
  const bool retained = retain_page(page);
  const uintptr_t new_addr = page->relocate_object(addr);
  if (retained) {
    release_page(page, true /* reclaimed */);
  }

  return new_addr;
}

uintptr_t ZHeap::forward_object(uintptr_t addr) {
  assert(ZGlobalPhase == ZPhaseMark ||
         ZGlobalPhase == ZPhaseMarkCompleted, "Forward not allowed");
  ZPage* const page = _pagetable.get(addr);
  return page->forward_object(addr);
}

void ZHeap::relocate() {
  // Relocate relocation set
  const bool success = _relocate.relocate(&_relocation_set);

  // Update statistics
  ZStatSample(ZSamplerHeapUsedAfterRelocation, used());
  ZStatRelocation::set_at_relocate_end(success);
  ZStatHeap::set_at_relocate_end(capacity(), allocated(), reclaimed(),
                                 used(), used_high(), used_low());
}

void ZHeap::object_iterate(ObjectClosure* cl, bool visit_referents) {
  assert(SafepointSynchronize::is_at_safepoint(), "Should be at safepoint");

  ZHeapIterator iter(visit_referents);
  iter.objects_do(cl);
}

void ZHeap::serviceability_initialize() {
  _serviceability.initialize();
}

GCMemoryManager* ZHeap::serviceability_memory_manager() {
  return _serviceability.memory_manager();
}

MemoryPool* ZHeap::serviceability_memory_pool() {
  return _serviceability.memory_pool();
}

ZServiceabilityCounters* ZHeap::serviceability_counters() {
  return _serviceability.counters();
}

void ZHeap::print_on(outputStream* st) const {
  st->print_cr(" ZHeap           used " SIZE_FORMAT "M, capacity " SIZE_FORMAT "M, max capacity " SIZE_FORMAT "M",
               used() / M,
               capacity() / M,
               max_capacity() / M);
  MetaspaceUtils::print_on(st);
}

void ZHeap::print_extended_on(outputStream* st) const {
  print_on(st);
  st->cr();

  ZPageTableIterator iter(&_pagetable);
  for (ZPage* page; iter.next(&page);) {
    page->print_on(st);
  }

  st->cr();
}

class ZVerifyRootsTask : public ZTask {
private:
  ZRootsIterator     _strong_roots;
  ZWeakRootsIterator _weak_roots;

public:
  ZVerifyRootsTask() :
      ZTask("ZVerifyRootsTask"),
      _strong_roots(),
      _weak_roots() {}

  virtual void work() {
    ZVerifyRootOopClosure cl;
    _strong_roots.oops_do(&cl);
    _weak_roots.oops_do(&cl);
  }
};

void ZHeap::verify() {
  // Heap verification can only be done between mark end and
  // relocate start. This is the only window where all oop are
  // good and the whole heap is in a consistent state.
  guarantee(ZGlobalPhase == ZPhaseMarkCompleted, "Invalid phase");

  {
    ZVerifyRootsTask task;
    _workers.run_parallel(&task);
  }

  {
    ZVerifyObjectClosure cl;
    object_iterate(&cl, false /* visit_referents */);
  }
}
