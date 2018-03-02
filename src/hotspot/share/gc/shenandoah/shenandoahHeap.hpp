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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHHEAP_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHHEAP_HPP

#include "gc/shared/markBitMap.hpp"
#include "gc/shenandoah/shenandoahHeapLock.hpp"
#include "gc/shenandoah/shenandoahSharedVariables.hpp"
#include "gc/shenandoah/shenandoahWorkGroup.hpp"
#include "services/memoryManager.hpp"

class ConcurrentGCTimer;
class ShenandoahAsserts;
class ShenandoahAllocTracker;
class ShenandoahCollectorPolicy;
class ShenandoahConnectionMatrix;
class ShenandoahPhaseTimings;
class ShenandoahHeap;
class ShenandoahHeapRegion;
class ShenandoahHeapRegionClosure;
class ShenandoahHeapRegionSet;
class ShenandoahCollectionSet;
class ShenandoahFreeSet;
class ShenandoahConcurrentMark;
class ShenandoahMarkCompact;
class ShenandoahPartialGC;
class ShenandoahTraversalGC;
class ShenandoahVerifier;
class ShenandoahConcurrentThread;
class ShenandoahMonitoringSupport;

class ShenandoahUpdateRefsClosure: public OopClosure {
private:
  ShenandoahHeap* _heap;

  template <class T>
  inline void do_oop_work(T* p);

public:
  ShenandoahUpdateRefsClosure();
  inline void do_oop(oop* p);
  inline void do_oop(narrowOop* p);
};

#ifdef ASSERT
class ShenandoahAssertToSpaceClosure : public OopClosure {
private:
  template <class T>
  void do_oop_nv(T* p);
public:
  void do_oop(narrowOop* p);
  void do_oop(oop* p);
};
#endif

class ShenandoahAlwaysTrueClosure : public BoolObjectClosure {
public:
  bool do_object_b(oop p) { return true; }
};

class ShenandoahForwardedIsAliveClosure: public BoolObjectClosure {
private:
  ShenandoahHeap* _heap;
public:
  ShenandoahForwardedIsAliveClosure();
  void init(ShenandoahHeap* heap) {
    _heap = heap;
  }
  bool do_object_b(oop obj);
};

class ShenandoahIsAliveClosure: public BoolObjectClosure {
private:
  ShenandoahHeap* _heap;
public:
  ShenandoahIsAliveClosure();
  void init(ShenandoahHeap* heap) {
    _heap = heap;
  }
  bool do_object_b(oop obj);
};

class VMStructs;

// // A "ShenandoahHeap" is an implementation of a java heap for HotSpot.
// // It uses a new pauseless GC algorithm based on Brooks pointers.
// // Derived from G1

// //
// // CollectedHeap
// //    SharedHeap
// //      ShenandoahHeap
class ShenandoahHeap : public CollectedHeap {
  friend class ShenandoahAsserts;
  friend class VMStructs;

  enum CancelState {

    // Normal state. GC has not been cancelled and is open for cancellation.
    // Worker threads can suspend for safepoint.
    CANCELLABLE,

    // GC has been cancelled. Worker threads can not suspend for
    // safepoint but must finish their work as soon as possible.
    CANCELLED,

    // GC has not been cancelled and must not be cancelled. At least
    // one worker thread checks for pending safepoint and may suspend
    // if a safepoint is pending.
    NOT_CANCELLED

  };

public:
  // GC state describes the important parts of collector state, that may be
  // used to make barrier selection decisions in the native and generated code.
  // Multiple bits can be set at once.
  //
  // Important invariant: when GC state is zero, the heap is stable, and no barriers
  // are required.
  enum GCStateBitPos {
    // Heap has forwarded objects: need RB, ACMP, CAS barriers.
    HAS_FORWARDED_BITPOS   = 0,

    // Heap is under marking: needs SATB barriers.
    MARKING_BITPOS    = 1,

    // Heap is under evacuation: needs WB barriers. (Set together with UNSTABLE)
    EVACUATION_BITPOS = 2,

    // Heap is under updating: needs SVRB/SVWB barriers.
    UPDATEREFS_BITPOS = 3,

    // Heap is under partial collection
    PARTIAL_BITPOS    = 4,

    // Heap is under traversal collection
    TRAVERSAL_BITPOS  = 5,
  };

  enum GCState {
    STABLE        = 0,
    HAS_FORWARDED = 1 << HAS_FORWARDED_BITPOS,
    MARKING       = 1 << MARKING_BITPOS,
    EVACUATION    = 1 << EVACUATION_BITPOS,
    UPDATEREFS    = 1 << UPDATEREFS_BITPOS,
    PARTIAL       = 1 << PARTIAL_BITPOS,
    TRAVERSAL     = 1 << TRAVERSAL_BITPOS,
  };

  enum ShenandoahDegenerationPoint {
    _degenerated_partial,
    _degenerated_traversal,
    _degenerated_outside_cycle,
    _degenerated_mark,
    _degenerated_evac,
    _degenerated_updaterefs,
  };

  static const char* degen_point_to_string(ShenandoahDegenerationPoint point) {
    switch (point) {
      case _degenerated_partial:
        return "Partial";
      case _degenerated_traversal:
        return "Traversal";
      case _degenerated_outside_cycle:
        return "Outside of Cycle";
      case _degenerated_mark:
        return "Mark";
      case _degenerated_evac:
        return "Evacuation";
      case _degenerated_updaterefs:
        return "Update Refs";
      default:
        ShouldNotReachHere();
        return "ERROR";
    }
  };

private:
  ShenandoahSharedBitmap _gc_state;
  ShenandoahHeapLock _lock;
  ShenandoahCollectorPolicy* _shenandoah_policy;
  size_t _bitmap_size;
  size_t _bitmap_regions_per_slice;
  size_t _bitmap_bytes_per_slice;
  MemRegion _heap_region;
  MemRegion _bitmap0_region;
  MemRegion _bitmap1_region;
  MemRegion _aux_bitmap_region;

  // Sortable array of regions
  ShenandoahHeapRegionSet* _ordered_regions;
  ShenandoahFreeSet* _free_regions;
  ShenandoahCollectionSet* _collection_set;

  ShenandoahConcurrentMark* _scm;
  ShenandoahMarkCompact* _full_gc;
  ShenandoahPartialGC* _partial_gc;
  ShenandoahTraversalGC* _traversal_gc;
  ShenandoahVerifier*  _verifier;

  ShenandoahConcurrentThread* _concurrent_gc_thread;

  ShenandoahMonitoringSupport* _monitoring_support;

  ShenandoahPhaseTimings*      _phase_timings;
  ShenandoahAllocTracker*      _alloc_tracker;

  size_t _num_regions;
  size_t _initial_size;

  uint _max_workers;
  ShenandoahWorkGang* _workers;
  ShenandoahWorkGang* _safepoint_workers;

  volatile size_t _used;
  volatile size_t _committed;

  MarkBitMap _verification_bit_map;
  MarkBitMap _mark_bit_map0;
  MarkBitMap _mark_bit_map1;
  MarkBitMap* _complete_mark_bit_map;
  MarkBitMap* _next_mark_bit_map;
  MarkBitMap _aux_bit_map;

  HeapWord** _complete_top_at_mark_starts;
  HeapWord** _complete_top_at_mark_starts_base;

  HeapWord** _next_top_at_mark_starts;
  HeapWord** _next_top_at_mark_starts_base;

  volatile size_t _bytes_allocated_since_gc_start;

  ShenandoahSharedFlag _degenerated_gc_in_progress;
  ShenandoahSharedFlag _full_gc_in_progress;
  ShenandoahSharedFlag _full_gc_move_in_progress;

  ShenandoahSharedFlag _inject_alloc_failure;

  ShenandoahSharedFlag _process_references;
  ShenandoahSharedFlag _unload_classes;

  ShenandoahSharedEnumFlag<CancelState> _cancelled_concgc;

  ReferenceProcessor* _ref_processor;

  ShenandoahForwardedIsAliveClosure _forwarded_is_alive;
  ShenandoahIsAliveClosure _is_alive;

  ConcurrentGCTimer* _gc_timer;

  ShenandoahConnectionMatrix* _connection_matrix;

  GCMemoryManager _minor_memory_manager;
  GCMemoryManager _major_memory_manager;

  MemoryPool* _memory_pool;
  MemoryPool* _dummy_pool;

#ifdef ASSERT
  int     _heap_expansion_count;
#endif

public:
  ShenandoahHeap(ShenandoahCollectorPolicy* policy);

  const char* name() const /* override */;
  HeapWord* allocate_new_tlab(size_t word_size) /* override */;
  void print_on(outputStream* st) const /* override */;
  void print_extended_on(outputStream *st) const /* override */;

  ShenandoahHeap::Name kind() const  /* override */{
    return CollectedHeap::ShenandoahHeap;
  }

  jint initialize() /* override */;
  void post_initialize() /* override */;
  size_t capacity() const /* override */;
  size_t used() const /* override */;
  size_t committed() const;
  bool is_maximal_no_gc() const /* override */;
  size_t max_capacity() const /* override */;
  size_t initial_capacity() const /* override */;
  bool is_in(const void* p) const /* override */;
  bool is_scavengable(oop obj) /* override */;
  HeapWord* mem_allocate(size_t size, bool* what) /* override */;
  bool can_elide_tlab_store_barriers() const /* override */;
  oop new_store_pre_barrier(JavaThread* thread, oop new_obj) /* override */;
  bool can_elide_initializing_store_barrier(oop new_obj) /* override */;
  bool card_mark_must_follow_store() const /* override */;
  void collect(GCCause::Cause cause) /* override */;
  void do_full_collection(bool clear_all_soft_refs) /* override */;
  AdaptiveSizePolicy* size_policy() /* override */;
  CollectorPolicy* collector_policy() const /* override */;
  void ensure_parsability(bool retire_tlabs) /* override */;
  HeapWord* block_start(const void* addr) const /* override */;
  size_t block_size(const HeapWord* addr) const /* override */;
  bool block_is_obj(const HeapWord* addr) const /* override */;
  jlong millis_since_last_gc() /* override */;
  void prepare_for_verify() /* override */;
  void print_gc_threads_on(outputStream* st) const /* override */;
  void gc_threads_do(ThreadClosure* tcl) const /* override */;
  void print_tracing_info() const /* override */;
  void verify(VerifyOption vo) /* override */;
  bool supports_tlab_allocation() const /* override */;
  size_t tlab_capacity(Thread *thr) const /* override */;
  void object_iterate(ObjectClosure* cl) /* override */;
  void safe_object_iterate(ObjectClosure* cl) /* override */;
  size_t unsafe_max_tlab_alloc(Thread *thread) const /* override */;
  size_t max_tlab_size() const /* override */;
  void resize_all_tlabs() /* override */;
  void accumulate_statistics_all_gclabs() /* override */;
  HeapWord* tlab_post_allocation_setup(HeapWord* obj) /* override */;
  uint oop_extra_words() /* override */;
  size_t tlab_used(Thread* ignored) const /* override */;
  void stop() /* override */;
  virtual void safepoint_synchronize_begin();
  virtual void safepoint_synchronize_end();

  WorkGang* get_safepoint_workers() { return _safepoint_workers; }

#ifndef CC_INTERP
  void compile_prepare_oop(MacroAssembler* masm, Register obj) /* override */;
#endif

  void register_nmethod(nmethod* nm);
  void unregister_nmethod(nmethod* nm);

  void pin_object(oop o) /* override */;
  void unpin_object(oop o) /* override */;

  static ShenandoahHeap* heap();
  static ShenandoahHeap* heap_no_check();
  static address in_cset_fast_test_addr();
  static address cancelled_concgc_addr();
  static address gc_state_addr();

  ShenandoahCollectorPolicy *shenandoahPolicy() const { return _shenandoah_policy; }
  ShenandoahPhaseTimings*   phase_timings()     const { return _phase_timings; }
  ShenandoahAllocTracker*   alloc_tracker()     const { return _alloc_tracker; }

  inline ShenandoahHeapRegion* heap_region_containing(const void* addr) const;
  inline size_t heap_region_index_containing(const void* addr) const;
  inline bool requires_marking(const void* entry) const;

  template <class T>
  inline oop evac_update_with_forwarded(T* p, bool &evac);

  template <class T>
  inline oop maybe_update_with_forwarded(T* p);

  template <class T>
  inline oop maybe_update_with_forwarded_not_null(T* p, oop obj);

  template <class T>
  inline oop update_with_forwarded_not_null(T* p, oop obj);

  void trash_cset_regions();

  void stop_concurrent_marking();

  void prepare_for_concurrent_evacuation();
  void evacuate_and_update_roots();
  // Fixup roots after concurrent cycle failed
  void fixup_roots();

  void update_heap_references(ShenandoahHeapRegionSet* regions, bool concurrent);

  void roots_iterate(OopClosure* cl);

private:
  void set_gc_state_mask(uint mask, bool value);
  void set_gc_state_mask_concurrently(uint mask, bool value);

public:
  void set_concurrent_mark_in_progress(bool in_progress);
  void set_evacuation_in_progress_concurrently(bool in_progress);
  void set_evacuation_in_progress_at_safepoint(bool in_progress);
  void set_update_refs_in_progress(bool in_progress);
  void set_degenerated_gc_in_progress(bool in_progress);
  void set_full_gc_in_progress(bool in_progress);
  void set_full_gc_move_in_progress(bool in_progress);
  void set_concurrent_partial_in_progress(bool in_progress);
  void set_concurrent_traversal_in_progress(bool in_progress);
  void set_has_forwarded_objects(bool cond);

  void set_process_references(bool pr);
  void set_unload_classes(bool uc);

  inline bool is_stable() const;
  inline bool is_idle() const;
  inline bool is_concurrent_mark_in_progress() const;
  inline bool is_update_refs_in_progress() const;
  inline bool is_evacuation_in_progress() const;
  inline bool is_degenerated_gc_in_progress() const;
  inline bool is_full_gc_in_progress() const;
  inline bool is_full_gc_move_in_progress() const;
  inline bool is_concurrent_partial_in_progress() const;
  inline bool is_concurrent_traversal_in_progress() const;
  inline bool has_forwarded_objects() const;
  inline bool is_gc_in_progress_mask(uint mask) const;

  bool process_references() const;
  bool unload_classes() const;

  inline bool region_in_collection_set(size_t region_index) const;

  // Mainly there to avoid accidentally calling the templated
  // method below with ShenandoahHeapRegion* which would be *wrong*.
  inline bool in_collection_set(ShenandoahHeapRegion* r) const;

  template <class T>
  inline bool in_collection_set(T obj) const;

  inline bool allocated_after_next_mark_start(HeapWord* addr) const;
  void set_next_top_at_mark_start(HeapWord* region_base, HeapWord* addr);
  HeapWord* next_top_at_mark_start(HeapWord* region_base);

  inline bool allocated_after_complete_mark_start(HeapWord* addr) const;
  void set_complete_top_at_mark_start(HeapWord* region_base, HeapWord* addr);
  HeapWord* complete_top_at_mark_start(HeapWord* region_base);

  // Evacuates object src. Returns the evacuated object if this thread
  // succeeded, otherwise rolls back the evacuation and returns the
  // evacuated object by the competing thread. 'succeeded' is an out
  // param and set to true if this thread succeeded, otherwise to false.
  inline oop  evacuate_object(oop src, Thread* thread, bool& evacuated, bool from_write_barrier = false);
  inline bool cancelled_concgc() const;
  inline bool check_cancelled_concgc_and_yield(bool sts_active = true);
  inline bool try_cancel_concgc();
  inline void clear_cancelled_concgc();

  ShenandoahHeapRegionSet* regions() const { return _ordered_regions;}
  ShenandoahFreeSet* free_regions() const  { return _free_regions; }
  ShenandoahCollectionSet* collection_set() const { return _collection_set; }
  void clear_free_regions();
  void add_free_region(ShenandoahHeapRegion* r);

  ShenandoahConnectionMatrix* connection_matrix() const;

  void increase_used(size_t bytes);
  void decrease_used(size_t bytes);

  void set_used(size_t bytes);

  void increase_committed(size_t bytes);
  void decrease_committed(size_t bytes);

  void increase_allocated(size_t bytes);

  void handle_heap_shrinkage();

  size_t garbage();

  void reset_next_mark_bitmap();

  MarkBitMap* complete_mark_bit_map();
  MarkBitMap* next_mark_bit_map();
  inline bool is_marked_complete(oop obj) const;
  inline bool mark_next(oop obj) const;
  inline bool is_marked_next(oop obj) const;
  bool is_next_bitmap_clear();
  bool is_next_bitmap_clear_range(HeapWord* start, HeapWord* end);
  bool is_complete_bitmap_clear_range(HeapWord* start, HeapWord* end);

  bool commit_bitmap_slice(ShenandoahHeapRegion *r);
  bool uncommit_bitmap_slice(ShenandoahHeapRegion *r);

  // Hint that the bitmap slice is not needed
  bool idle_bitmap_slice(ShenandoahHeapRegion* r);
  void activate_bitmap_slice(ShenandoahHeapRegion* r);

  bool is_bitmap_slice_committed(ShenandoahHeapRegion* r, bool skip_self = false);

  void print_heap_regions_on(outputStream* st) const;

  size_t bytes_allocated_since_gc_start();
  void reset_bytes_allocated_since_gc_start();

  size_t trash_humongous_region_at(ShenandoahHeapRegion *r);

  virtual GrowableArray<GCMemoryManager*> memory_managers();
  virtual GrowableArray<MemoryPool*> memory_pools();

  ShenandoahMonitoringSupport* monitoring_support();
  ShenandoahConcurrentMark* concurrentMark() { return _scm; }
  ShenandoahMarkCompact* full_gc() { return _full_gc; }
  ShenandoahPartialGC* partial_gc();
  ShenandoahTraversalGC* traversal_gc();
  ShenandoahVerifier* verifier();

  ReferenceProcessor* ref_processor() { return _ref_processor;}

  WorkGang* workers() const { return _workers;}

  uint max_workers();

  void assert_gc_workers(uint nworker) PRODUCT_RETURN;

  void do_evacuation();
  ShenandoahHeapRegion* next_compaction_region(const ShenandoahHeapRegion* r);

  void heap_region_iterate(ShenandoahHeapRegionClosure* blk, bool skip_cset_regions = false, bool skip_humongous_continuation = false) const;

  // Delete entries for dead interned string and clean up unreferenced symbols
  // in symbol table, possibly in parallel.
  void unload_classes_and_cleanup_tables(bool full_gc);

  inline size_t num_regions() const { return _num_regions; }

  BoolObjectClosure* is_alive_closure();

private:
  template<class T>
  inline void marked_object_iterate(ShenandoahHeapRegion* region, T* cl, HeapWord* limit);

  template<class T>
  inline void marked_object_oop_iterate(ShenandoahHeapRegion* region, T* cl, HeapWord* limit);

public:
  template<class T>
  inline void marked_object_iterate(ShenandoahHeapRegion* region, T* cl);

  template<class T>
  inline void marked_object_safe_iterate(ShenandoahHeapRegion* region, T* cl);

  template<class T>
  inline void marked_object_oop_iterate(ShenandoahHeapRegion* region, T* cl);

  template<class T>
  inline void marked_object_oop_safe_iterate(ShenandoahHeapRegion* region, T* cl);

  GCTimer* gc_timer() const;

  void swap_mark_bitmaps();

  void cancel_concgc(GCCause::Cause cause);

  ShenandoahHeapLock* lock() { return &_lock; }
  void assert_heaplock_owned_by_current_thread() PRODUCT_RETURN;
  void assert_heaplock_not_owned_by_current_thread() PRODUCT_RETURN;
  void assert_heaplock_or_safepoint() PRODUCT_RETURN;

public:
  typedef enum {
    _alloc_shared,      // Allocate common, outside of TLAB
    _alloc_shared_gc,   // Allocate common, outside of GCLAB
    _alloc_tlab,        // Allocate TLAB
    _alloc_gclab,       // Allocate GCLAB
    _ALLOC_LIMIT,
  } AllocType;

  static const char* alloc_type_to_string(AllocType type) {
    switch (type) {
      case _alloc_shared:
        return "Shared";
      case _alloc_shared_gc:
        return "Shared GC";
      case _alloc_tlab:
        return "TLAB";
      case _alloc_gclab:
        return "GCLAB";
      default:
        ShouldNotReachHere();
        return "";
    }
  }
private:

  virtual void initialize_serviceability();

  HeapWord* allocate_new_lab(size_t word_size, AllocType type);
  HeapWord* allocate_memory_under_lock(size_t word_size, AllocType type, bool &new_region);
  HeapWord* allocate_memory(size_t word_size, AllocType type);
  // Shenandoah functionality.
  inline HeapWord* allocate_from_gclab(Thread* thread, size_t size);
  HeapWord* allocate_from_gclab_slow(Thread* thread, size_t size);
  HeapWord* allocate_new_gclab(size_t word_size);

  template<class T>
  inline void do_marked_object(MarkBitMap* bitmap, T* cl, oop obj);

  ShenandoahConcurrentThread* concurrent_thread() { return _concurrent_gc_thread; }

  inline bool mark_next_no_checks(oop obj) const;

public:
  inline oop atomic_compare_exchange_oop(oop n, narrowOop* addr, oop c);
  inline oop atomic_compare_exchange_oop(oop n, oop* addr, oop c);

private:
  void ref_processing_init();

  GCTracer* tracer();

private:
  uint64_t _alloc_seq_at_last_gc_start;
  uint64_t _alloc_seq_at_last_gc_end;
  size_t _used_at_last_gc;

public:
  void recycle_trash_assist(size_t limit);
  void recycle_trash();

  uint64_t alloc_seq_at_last_gc_end()   const { return _alloc_seq_at_last_gc_end;  }
  uint64_t alloc_seq_at_last_gc_start() const { return _alloc_seq_at_last_gc_start;}
  size_t used_at_last_gc()              const { return _used_at_last_gc;}

  void set_alloc_seq_gc_start();
  void set_alloc_seq_gc_end();

  void set_used_at_last_gc() {_used_at_last_gc = used();}

  void make_tlabs_parsable(bool retire_tlabs) /* override */;

  GCMemoryManager* major_memory_manager() { return &_major_memory_manager; }
  GCMemoryManager* minor_memory_manager() { return &_minor_memory_manager; }

public:
  // Entry points to STW GC operations, these cause a related safepoint, that then
  // call the entry method below
  void vmop_entry_init_mark();
  void vmop_entry_final_mark();
  void vmop_entry_init_updaterefs();
  void vmop_entry_final_updaterefs();
  void vmop_entry_init_partial();
  void vmop_entry_final_partial();
  void vmop_entry_init_traversal();
  void vmop_entry_final_traversal();
  void vmop_entry_full(GCCause::Cause cause);
  void vmop_entry_verify_after_evac();
  void vmop_degenerated(ShenandoahDegenerationPoint point);

  // Entry methods to normally STW GC operations. These set up logging, monitoring
  // and workers for net VM operation
  void entry_init_mark();
  void entry_final_mark();
  void entry_init_updaterefs();
  void entry_final_updaterefs();
  void entry_init_partial();
  void entry_final_partial();
  void entry_init_traversal();
  void entry_final_traversal();
  void entry_full(GCCause::Cause cause);
  void entry_verify_after_evac();
  void entry_degenerated(int point);

  // Entry methods to normally concurrent GC operations. These set up logging, monitoring
  // for concurrent operation.
  void entry_mark();
  void entry_preclean();
  void entry_cleanup();
  void entry_cleanup_bitmaps();
  void entry_evac();
  void entry_updaterefs();
  void entry_partial();
  void entry_traversal();

private:
  // Actual work for the phases
  void op_init_mark();
  void op_final_mark();
  void op_init_updaterefs();
  void op_final_updaterefs();
  void op_init_partial();
  void op_final_partial();
  void op_init_traversal();
  void op_final_traversal();
  void op_full(GCCause::Cause cause);
  void op_verify_after_evac();
  void op_degenerated(ShenandoahDegenerationPoint point);
  void op_degenerated_fail();
  void op_degenerated_futile();

  void op_mark();
  void op_preclean();
  void op_cleanup();
  void op_evac();
  void op_updaterefs();
  void op_cleanup_bitmaps();
  void op_partial();
  void op_traversal();

private:
  void try_inject_alloc_failure();
  bool should_inject_alloc_failure();
};

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHHEAP_HPP
