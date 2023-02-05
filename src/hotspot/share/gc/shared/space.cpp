/*
 * Copyright (c) 1997, 2022, Oracle and/or its affiliates. All rights reserved.
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
 *
 */

#include "precompiled.hpp"
#include "classfile/vmClasses.hpp"
#include "classfile/vmSymbols.hpp"
#include "gc/shared/collectedHeap.inline.hpp"
#include "gc/shared/genCollectedHeap.hpp"
#include "gc/shared/genOopClosures.inline.hpp"
#include "gc/shared/space.hpp"
#include "gc/shared/space.inline.hpp"
#include "gc/shared/spaceDecorator.inline.hpp"
#include "gc/shared/dcevmSharedGC.hpp"
#include "memory/iterator.inline.hpp"
#include "memory/universe.hpp"
#include "oops/oop.inline.hpp"
#include "runtime/atomic.hpp"
#include "runtime/java.hpp"
#include "runtime/prefetch.inline.hpp"
#include "runtime/safepoint.hpp"
#include "utilities/align.hpp"
#include "utilities/copy.hpp"
#include "utilities/globalDefinitions.hpp"
#include "utilities/macros.hpp"
#if INCLUDE_SERIALGC
#include "gc/serial/serialBlockOffsetTable.inline.hpp"
#include "gc/serial/defNewGeneration.hpp"
#endif

HeapWord* DirtyCardToOopClosure::get_actual_top(HeapWord* top,
                                                HeapWord* top_obj) {
  if (top_obj != NULL && top_obj < (_sp->toContiguousSpace())->top()) {
    if (cast_to_oop(top_obj)->is_objArray() || cast_to_oop(top_obj)->is_typeArray()) {
      // An arrayOop is starting on the dirty card - since we do exact
      // store checks for objArrays we are done.
    } else {
      // Otherwise, it is possible that the object starting on the dirty
      // card spans the entire card, and that the store happened on a
      // later card.  Figure out where the object ends.
      assert(_sp->block_size(top_obj) == cast_to_oop(top_obj)->size(),
             "Block size and object size mismatch");
      top = top_obj + cast_to_oop(top_obj)->size();
    }
  } else {
    top = (_sp->toContiguousSpace())->top();
  }
  return top;
}

void DirtyCardToOopClosure::walk_mem_region(MemRegion mr,
                                            HeapWord* bottom,
                                            HeapWord* top) {
  // Note that this assumption won't hold if we have a concurrent
  // collector in this space, which may have freed up objects after
  // they were dirtied and before the stop-the-world GC that is
  // examining cards here.
  assert(bottom < top, "ought to be at least one obj on a dirty card.");

  walk_mem_region_with_cl(mr, bottom, top, _cl);
}

// We get called with "mr" representing the dirty region
// that we want to process. Because of imprecise marking,
// we may need to extend the incoming "mr" to the right,
// and scan more. However, because we may already have
// scanned some of that extended region, we may need to
// trim its right-end back some so we do not scan what
// we (or another worker thread) may already have scanned
// or planning to scan.
void DirtyCardToOopClosure::do_MemRegion(MemRegion mr) {
  HeapWord* bottom = mr.start();
  HeapWord* last = mr.last();
  HeapWord* top = mr.end();
  HeapWord* bottom_obj;
  HeapWord* top_obj;

  assert(_last_bottom == NULL || top <= _last_bottom,
         "Not decreasing");
  NOT_PRODUCT(_last_bottom = mr.start());

  bottom_obj = _sp->block_start(bottom);
  top_obj    = _sp->block_start(last);

  assert(bottom_obj <= bottom, "just checking");
  assert(top_obj    <= top,    "just checking");

  // Given what we think is the top of the memory region and
  // the start of the object at the top, get the actual
  // value of the top.
  top = get_actual_top(top, top_obj);

  // If the previous call did some part of this region, don't redo.
  if (_min_done != NULL && _min_done < top) {
    top = _min_done;
  }

  // Top may have been reset, and in fact may be below bottom,
  // e.g. the dirty card region is entirely in a now free object
  // -- something that could happen with a concurrent sweeper.
  bottom = MIN2(bottom, top);
  MemRegion extended_mr = MemRegion(bottom, top);
  assert(bottom <= top &&
         (_min_done == NULL || top <= _min_done),
         "overlap!");

  // Walk the region if it is not empty; otherwise there is nothing to do.
  if (!extended_mr.is_empty()) {
    walk_mem_region(extended_mr, bottom_obj, top);
  }

  _min_done = bottom;
}

void DirtyCardToOopClosure::walk_mem_region_with_cl(MemRegion mr,
                                                    HeapWord* bottom,
                                                    HeapWord* top,
                                                    OopIterateClosure* cl) {
  bottom += cast_to_oop(bottom)->oop_iterate_size(cl, mr);
  if (bottom < top) {
    HeapWord* next_obj = bottom + cast_to_oop(bottom)->size();
    while (next_obj < top) {
      /* Bottom lies entirely below top, so we can call the */
      /* non-memRegion version of oop_iterate below. */
      cast_to_oop(bottom)->oop_iterate(cl);
      bottom = next_obj;
      next_obj = bottom + cast_to_oop(bottom)->size();
    }
    /* Last object. */
    cast_to_oop(bottom)->oop_iterate(cl, mr);
  }
}

void Space::initialize(MemRegion mr,
                       bool clear_space,
                       bool mangle_space) {
  HeapWord* bottom = mr.start();
  HeapWord* end    = mr.end();
  assert(Universe::on_page_boundary(bottom) && Universe::on_page_boundary(end),
         "invalid space boundaries");
  set_bottom(bottom);
  set_end(end);
  if (clear_space) clear(mangle_space);
}

void Space::clear(bool mangle_space) {
  if (ZapUnusedHeapArea && mangle_space) {
    mangle_unused_area();
  }
}

ContiguousSpace::ContiguousSpace(): CompactibleSpace(), _top(NULL) {
  _mangler = new GenSpaceMangler(this);
}

ContiguousSpace::~ContiguousSpace() {
  delete _mangler;
}

void ContiguousSpace::initialize(MemRegion mr,
                                 bool clear_space,
                                 bool mangle_space)
{
  CompactibleSpace::initialize(mr, clear_space, mangle_space);
}

void ContiguousSpace::clear(bool mangle_space) {
  set_top(bottom());
  set_saved_mark();
  CompactibleSpace::clear(mangle_space);
}

bool ContiguousSpace::is_free_block(const HeapWord* p) const {
  return p >= _top;
}

#if INCLUDE_SERIALGC
void TenuredSpace::clear(bool mangle_space) {
  ContiguousSpace::clear(mangle_space);
  _offsets.initialize_threshold();
}

void TenuredSpace::set_bottom(HeapWord* new_bottom) {
  Space::set_bottom(new_bottom);
  _offsets.set_bottom(new_bottom);
}

void TenuredSpace::set_end(HeapWord* new_end) {
  // Space should not advertise an increase in size
  // until after the underlying offset table has been enlarged.
  _offsets.resize(pointer_delta(new_end, bottom()));
  Space::set_end(new_end);
}
#endif // INCLUDE_SERIALGC

#ifndef PRODUCT

void ContiguousSpace::set_top_for_allocations(HeapWord* v) {
  mangler()->set_top_for_allocations(v);
}
void ContiguousSpace::set_top_for_allocations() {
  mangler()->set_top_for_allocations(top());
}
void ContiguousSpace::check_mangled_unused_area(HeapWord* limit) {
  mangler()->check_mangled_unused_area(limit);
}

void ContiguousSpace::check_mangled_unused_area_complete() {
  mangler()->check_mangled_unused_area_complete();
}

// Mangled only the unused space that has not previously
// been mangled and that has not been allocated since being
// mangled.
void ContiguousSpace::mangle_unused_area() {
  mangler()->mangle_unused_area();
}
void ContiguousSpace::mangle_unused_area_complete() {
  mangler()->mangle_unused_area_complete();
}
#endif  // NOT_PRODUCT

void CompactibleSpace::initialize(MemRegion mr,
                                  bool clear_space,
                                  bool mangle_space) {
  Space::initialize(mr, clear_space, mangle_space);
  set_compaction_top(bottom());
  _next_compaction_space = NULL;
}

void CompactibleSpace::clear(bool mangle_space) {
  Space::clear(mangle_space);
  _compaction_top = bottom();
}

// (DCEVM) Calculates the compact_top that will be used for placing the next object with the giving size on the heap.
HeapWord* CompactibleSpace::forward_compact_top(size_t size, CompactPoint* cp, HeapWord* compact_top) {
  // First check if we should switch compaction space
  assert(this == cp->space, "'this' should be current compaction space.");
  size_t compaction_max_size = pointer_delta(end(), compact_top);
  while (size > compaction_max_size) {
    // switch to next compaction space
    cp->space->set_compaction_top(compact_top);
    cp->space = cp->space->next_compaction_space();
    if (cp->space == NULL) {
      cp->gen = GenCollectedHeap::heap()->young_gen();
      assert(cp->gen != NULL, "compaction must succeed");
      cp->space = cp->gen->first_compaction_space();
      assert(cp->space != NULL, "generation must have a first compaction space");
    }
    compact_top = cp->space->bottom();
    cp->space->set_compaction_top(compact_top);
    cp->space->initialize_threshold();
    compaction_max_size = pointer_delta(cp->space->end(), compact_top);
  }

  return compact_top;
}

HeapWord* CompactibleSpace::forward(oop q, size_t size,
                                    CompactPoint* cp, HeapWord* compact_top, bool force_forward) {
  compact_top = forward_compact_top(size, cp, compact_top);

  // store the forwarding pointer into the mark word
  if (force_forward || cast_from_oop<HeapWord*>(q) != compact_top || (size_t)q->size() != size) {
    q->forward_to(cast_to_oop(compact_top));
    assert(q->is_gc_marked(), "encoding the pointer should preserve the mark");
  } else {
    // if the object isn't moving we can just set the mark to the default
    // mark and handle it specially later on.
    q->init_mark();
    assert(!q->is_forwarded(), "should not be forwarded");
  }

  compact_top += size;

  // We need to update the offset table so that the beginnings of objects can be
  // found during scavenge.  Note that we are updating the offset table based on
  // where the object will be once the compaction phase finishes.
  cp->space->alloc_block(compact_top - size, compact_top);
  return compact_top;
}

#if INCLUDE_SERIALGC

void ContiguousSpace::prepare_for_compaction(CompactPoint* cp) {
  bool redefinition_run = Universe::is_redefining_gc_run();

  // Compute the new addresses for the live objects and store it in the mark
  // Used by universe::mark_sweep_phase2()

  // We're sure to be here before any objects are compacted into this
  // space, so this is a good time to initialize this:
  set_compaction_top(bottom());

  if (cp->space == NULL) {
    assert(cp->gen != NULL, "need a generation");
    assert(cp->gen->first_compaction_space() == this, "just checking");
    cp->space = cp->gen->first_compaction_space();
    cp->space->initialize_threshold();
    cp->space->set_compaction_top(cp->space->bottom());
  }

  HeapWord* compact_top = cp->space->compaction_top(); // This is where we are currently compacting to.

  DeadSpacer dead_spacer(this);

  HeapWord*  end_of_live = bottom();  // One byte beyond the last byte of the last live object.
  HeapWord*  first_dead = NULL; // The first dead object.

  const intx interval = PrefetchScanIntervalInBytes;

  HeapWord* cur_obj = bottom();
  HeapWord* scan_limit = top();

  bool force_forward = false;

  while (cur_obj < scan_limit) {
    if (cast_to_oop(cur_obj)->is_gc_marked()) {
      // prefetch beyond cur_obj
      Prefetch::write(cur_obj, interval);

      size_t size = cast_to_oop(cur_obj)->size();

      if (redefinition_run) {
        compact_top = cp->space->forward_with_rescue(cur_obj, size, cp, compact_top, force_forward);
        if (first_dead == NULL && cast_to_oop(cur_obj)->is_gc_marked()) {
          /* Was moved (otherwise, forward would reset mark),
             set first_dead to here */
          first_dead = cur_obj;
          force_forward = true;
        }
      } else {
        compact_top = cp->space->forward(cast_to_oop(cur_obj), size, cp, compact_top, false);
      }

      cur_obj += size;
      end_of_live = cur_obj;
    } else {
      // run over all the contiguous dead objects
      HeapWord* end = cur_obj;
      do {
        // prefetch beyond end
        Prefetch::write(end, interval);
        end += cast_to_oop(end)->size();
      } while (end < scan_limit && !cast_to_oop(end)->is_gc_marked());

      // see if we might want to pretend this object is alive so that
      // we don't have to compact quite as often.
      if (!redefinition_run && cur_obj == compact_top && dead_spacer.insert_deadspace(cur_obj, end)) {
        oop obj = cast_to_oop(cur_obj);
        compact_top = cp->space->forward(obj, obj->size(), cp, compact_top, force_forward);
        end_of_live = end;
      } else {
        // otherwise, it really is a free region.

        // cur_obj is a pointer to a dead object. Use this dead memory to store a pointer to the next live object.
        *(HeapWord**)cur_obj = end;

        // see if this is the first dead region.
        if (first_dead == NULL) {
          first_dead = cur_obj;
          if (redefinition_run) {
	          force_forward = true;
	        }
        }
      }

      // move on to the next object
      cur_obj = end;
    }
  }

  if (redefinition_run) {
    compact_top = forward_rescued(cp, compact_top);
  }

  assert(cur_obj == scan_limit, "just checking");
  _end_of_live = end_of_live;
  if (first_dead != NULL) {
    _first_dead = first_dead;
  } else {
    _first_dead = end_of_live;
  }

  // save the compaction_top of the compaction space.
  cp->space->set_compaction_top(compact_top);
}



#ifdef ASSERT

int CompactibleSpace::space_index(oop obj) {
  GenCollectedHeap* heap = GenCollectedHeap::heap();

  //if (heap->is_in_permanent(obj)) {
  //  return -1;
  //}

  int index = 0;
  CompactibleSpace* space = heap->old_gen()->first_compaction_space();
  while (space != NULL) {
    if (space->is_in_reserved(obj)) {
      return index;
    }
    space = space->next_compaction_space();
    index++;
  }

  space = heap->young_gen()->first_compaction_space();
  while (space != NULL) {
    if (space->is_in_reserved(obj)) {
      return index;
    }
    space = space->next_compaction_space();
    index++;
  }

  tty->print_cr("could not compute space_index for " INTPTR_FORMAT, p2i(cast_from_oop<HeapWord*>(obj)));
  index = 0;

  Generation* gen = heap->old_gen();
  tty->print_cr("  generation %s: " INTPTR_FORMAT " - " INTPTR_FORMAT, gen->name(), p2i(gen->reserved().start()), p2i(gen->reserved().end()));

  space = gen->first_compaction_space();
  while (space != NULL) {
    tty->print_cr("    %2d space " INTPTR_FORMAT " - " INTPTR_FORMAT, index, p2i(space->bottom()), p2i(space->end()));
    space = space->next_compaction_space();
    index++;
  }

  gen = heap->young_gen();
  tty->print_cr("  generation %s: " INTPTR_FORMAT " - " INTPTR_FORMAT, gen->name(), p2i(gen->reserved().start()), p2i(gen->reserved().end()));

  space = gen->first_compaction_space();
  while (space != NULL) {
    tty->print_cr("    %2d space " INTPTR_FORMAT " - " INTPTR_FORMAT, index, p2i(space->bottom()), p2i(space->end()));
    space = space->next_compaction_space();
    index++;
  }

  ShouldNotReachHere();
  return 0;
}
#endif

bool CompactibleSpace::must_rescue(oop old_obj, oop new_obj) {
  // Only redefined objects can have the need to be rescued.
  if (oop(old_obj)->klass()->new_version() == NULL) return false;

  //if (old_obj->is_perm()) {
  //  // This object is in perm gen: Always rescue to satisfy invariant obj->klass() <= obj.
  //  return true;
  //}

  int new_size = old_obj->size_given_klass(oop(old_obj)->klass()->new_version());
  int original_size = old_obj->size();

  Generation* tenured_gen = GenCollectedHeap::heap()->old_gen();
  bool old_in_tenured = tenured_gen->is_in_reserved(old_obj);
  bool new_in_tenured = tenured_gen->is_in_reserved(new_obj);
  if (old_in_tenured == new_in_tenured) {
    // Rescue if object may overlap with a higher memory address.
    bool overlap = (cast_from_oop<HeapWord*>(old_obj) + original_size < cast_from_oop<HeapWord*>(new_obj) + new_size);
    if (old_in_tenured) {
      // Old and new address are in same space, so just compare the address.
      // Must rescue if object moves towards the top of the space.
      assert(space_index(old_obj) == space_index(new_obj), "old_obj and new_obj must be in same space");
    } else {
      // In the new generation, eden is located before the from space, so a
      // simple pointer comparison is sufficient.
      assert(GenCollectedHeap::heap()->young_gen()->is_in_reserved(old_obj), "old_obj must be in DefNewGeneration");
      assert(GenCollectedHeap::heap()->young_gen()->is_in_reserved(new_obj), "new_obj must be in DefNewGeneration");
      assert(overlap == (space_index(old_obj) < space_index(new_obj)), "slow and fast computation must yield same result");
    }
    return overlap;

  } else {
    assert(space_index(old_obj) != space_index(new_obj), "old_obj and new_obj must be in different spaces");
    if (new_in_tenured) {
      // Must never rescue when moving from the new into the old generation.
      assert(GenCollectedHeap::heap()->young_gen()->is_in_reserved(old_obj), "old_obj must be in DefNewGeneration");
      assert(space_index(old_obj) > space_index(new_obj), "must be");
      return false;

    } else /* if (tenured_gen->is_in_reserved(old_obj)) */ {
      // Must always rescue when moving from the old into the new generation.
      assert(GenCollectedHeap::heap()->young_gen()->is_in_reserved(new_obj), "new_obj must be in DefNewGeneration");
      assert(space_index(old_obj) < space_index(new_obj), "must be");
      return true;
    }
  }
}

HeapWord* CompactibleSpace::rescue(HeapWord* old_obj) {
  assert(must_rescue(cast_to_oop(old_obj), cast_to_oop(old_obj)->forwardee()), "do not call otherwise");

  int size = cast_to_oop(old_obj)->size();
  HeapWord* rescued_obj = NEW_RESOURCE_ARRAY(HeapWord, size);
  Copy::aligned_disjoint_words(old_obj, rescued_obj, size);

  if (MarkSweep::_rescued_oops == NULL) {
    MarkSweep::_rescued_oops = new GrowableArray<HeapWord*>(128);
  }

  MarkSweep::_rescued_oops->append(rescued_obj);
  return rescued_obj;
}

void CompactibleSpace::adjust_pointers() {
  // Check first is there is any work to do.
  if (used() == 0) {
    return;   // Nothing to do.
  }

  // adjust all the interior pointers to point at the new locations of objects
  // Used by MarkSweep::mark_sweep_phase3()

  HeapWord* cur_obj = bottom();
  HeapWord* const end_of_live = _end_of_live;  // Established by prepare_for_compaction().
  HeapWord* const first_dead = _first_dead;    // Established by prepare_for_compaction().

  assert(first_dead <= end_of_live, "Stands to reason, no?");

  const intx interval = PrefetchScanIntervalInBytes;

  debug_only(HeapWord* prev_obj = NULL);
  while (cur_obj < end_of_live) {
    Prefetch::write(cur_obj, interval);
    if (cur_obj < first_dead || cast_to_oop(cur_obj)->is_gc_marked()) {
      // cur_obj is alive
      // point all the oops to the new location
      size_t size = MarkSweep::adjust_pointers(cast_to_oop(cur_obj));
      debug_only(prev_obj = cur_obj);
      cur_obj += size;
    } else {
      debug_only(prev_obj = cur_obj);
      // cur_obj is not a live object, instead it points at the next live object
      cur_obj = *(HeapWord**)cur_obj;
      assert(cur_obj > prev_obj, "we should be moving forward through memory, cur_obj: " PTR_FORMAT ", prev_obj: " PTR_FORMAT, p2i(cur_obj), p2i(prev_obj));
    }
  }

  assert(cur_obj == end_of_live, "just checking");
}

void CompactibleSpace::compact() {

  bool redefinition_run = Universe::is_redefining_gc_run();

  // Copy all live objects to their new location
  // Used by MarkSweep::mark_sweep_phase4()

  verify_up_to_first_dead(this);

  HeapWord* const start = bottom();
  HeapWord* const end_of_live = _end_of_live;

  assert(_first_dead <= end_of_live, "Invariant. _first_dead: " PTR_FORMAT " <= end_of_live: " PTR_FORMAT, p2i(_first_dead), p2i(end_of_live));
  if (_first_dead == end_of_live && (start == end_of_live || !cast_to_oop(start)->is_gc_marked())) {
    // Nothing to compact. The space is either empty or all live object should be left in place.
    clear_empty_region(this);
    return;
  }

  const intx scan_interval = PrefetchScanIntervalInBytes;
  const intx copy_interval = PrefetchCopyIntervalInBytes;

  assert(start < end_of_live, "bottom: " PTR_FORMAT " should be < end_of_live: " PTR_FORMAT, p2i(start), p2i(end_of_live));
  HeapWord* cur_obj = start;
  if (_first_dead > cur_obj && !cast_to_oop(cur_obj)->is_gc_marked()) {
    // All object before _first_dead can be skipped. They should not be moved.
    // A pointer to the first live object is stored at the memory location for _first_dead.
    if (redefinition_run) {
      // _first_dead could be living redefined object
      cur_obj = _first_dead;
    } else {
      cur_obj = *(HeapWord**)(_first_dead);
    }
  }

  debug_only(HeapWord* prev_obj = NULL);
  while (cur_obj < end_of_live) {
    if (!cast_to_oop(cur_obj)->is_forwarded()) {
      debug_only(prev_obj = cur_obj);
      // The first word of the dead object contains a pointer to the next live object or end of space.
      cur_obj = *(HeapWord**)cur_obj;
      assert(cur_obj > prev_obj, "we should be moving forward through memory");
    } else {
      // prefetch beyond q
      Prefetch::read(cur_obj, scan_interval);

      // size and destination
      size_t size = cast_to_oop(cur_obj)->size();
      HeapWord* compaction_top = cast_from_oop<HeapWord*>(cast_to_oop(cur_obj)->forwardee());

      if (redefinition_run &&  must_rescue(cast_to_oop(cur_obj), cast_to_oop(cur_obj)->forwardee())) {
        rescue(cur_obj);
        debug_only(Copy::fill_to_words(cur_obj, size, 0));
        cur_obj += size;
        continue;
      }

      // prefetch beyond compaction_top
      Prefetch::write(compaction_top, copy_interval);

      // copy object and reinit its mark
      assert(redefinition_run || cur_obj != compaction_top, "everything in this pass should be moving");
      if (redefinition_run && cast_to_oop(cur_obj)->klass()->new_version() != NULL) {
        Klass* new_version = cast_to_oop(cur_obj)->klass()->new_version();
        if (new_version->update_information() == NULL) {
          Copy::aligned_conjoint_words(cur_obj, compaction_top, size);
          cast_to_oop(compaction_top)->set_klass(new_version);
        } else {
          DcevmSharedGC::update_fields(cast_to_oop(cur_obj), cast_to_oop(compaction_top));
        }
      } else {
        Copy::aligned_conjoint_words(cur_obj, compaction_top, size);
      }

      oop new_obj = cast_to_oop(compaction_top);

      ContinuationGCSupport::transform_stack_chunk(new_obj);

      new_obj->init_mark();
      assert(new_obj->klass() != NULL, "should have a class");

      debug_only(prev_obj = cur_obj);
      cur_obj += size;
    }
  }

  clear_empty_region(this);
}


#endif // INCLUDE_SERIALGC

void Space::print_short() const { print_short_on(tty); }

void Space::print_short_on(outputStream* st) const {
  st->print(" space " SIZE_FORMAT "K, %3d%% used", capacity() / K,
              (int) ((double) used() * 100 / capacity()));
}

void Space::print() const { print_on(tty); }

void Space::print_on(outputStream* st) const {
  print_short_on(st);
  st->print_cr(" [" PTR_FORMAT ", " PTR_FORMAT ")",
                p2i(bottom()), p2i(end()));
}

void ContiguousSpace::print_on(outputStream* st) const {
  print_short_on(st);
  st->print_cr(" [" PTR_FORMAT ", " PTR_FORMAT ", " PTR_FORMAT ")",
                p2i(bottom()), p2i(top()), p2i(end()));
}

#if INCLUDE_SERIALGC
void TenuredSpace::print_on(outputStream* st) const {
  print_short_on(st);
  st->print_cr(" [" PTR_FORMAT ", " PTR_FORMAT ", "
                PTR_FORMAT ", " PTR_FORMAT ")",
              p2i(bottom()), p2i(top()), p2i(_offsets.threshold()), p2i(end()));
}
#endif

void ContiguousSpace::verify() const {
  HeapWord* p = bottom();
  HeapWord* t = top();
  HeapWord* prev_p = NULL;
  while (p < t) {
    oopDesc::verify(cast_to_oop(p));
    prev_p = p;
    p += cast_to_oop(p)->size();
  }
  guarantee(p == top(), "end of last object must match end of space");
  if (top() != end()) {
    guarantee(top() == block_start_const(end()-1) &&
              top() == block_start_const(top()),
              "top should be start of unallocated block, if it exists");
  }
}

void Space::oop_iterate(OopIterateClosure* blk) {
  ObjectToOopClosure blk2(blk);
  object_iterate(&blk2);
}

bool Space::obj_is_alive(const HeapWord* p) const {
  assert (block_is_obj(p), "The address should point to an object");
  return true;
}

void ContiguousSpace::oop_iterate(OopIterateClosure* blk) {
  if (is_empty()) return;
  HeapWord* obj_addr = bottom();
  HeapWord* t = top();
  // Could call objects iterate, but this is easier.
  while (obj_addr < t) {
    obj_addr += cast_to_oop(obj_addr)->oop_iterate_size(blk);
  }
}

void ContiguousSpace::object_iterate(ObjectClosure* blk) {
  if (is_empty()) return;
  object_iterate_from(bottom(), blk);
}

void ContiguousSpace::object_iterate_from(HeapWord* mark, ObjectClosure* blk) {
  while (mark < top()) {
    blk->do_object(cast_to_oop(mark));
    mark += cast_to_oop(mark)->size();
  }
}

// Very general, slow implementation.
HeapWord* ContiguousSpace::block_start_const(const void* p) const {
  assert(MemRegion(bottom(), end()).contains(p),
         "p (" PTR_FORMAT ") not in space [" PTR_FORMAT ", " PTR_FORMAT ")",
         p2i(p), p2i(bottom()), p2i(end()));
  if (p >= top()) {
    return top();
  } else {
    HeapWord* last = bottom();
    HeapWord* cur = last;
    while (cur <= p) {
      last = cur;
      cur += cast_to_oop(cur)->size();
    }
    assert(oopDesc::is_oop(cast_to_oop(last)), PTR_FORMAT " should be an object start", p2i(last));
    return last;
  }
}

size_t ContiguousSpace::block_size(const HeapWord* p) const {
  assert(MemRegion(bottom(), end()).contains(p),
         "p (" PTR_FORMAT ") not in space [" PTR_FORMAT ", " PTR_FORMAT ")",
         p2i(p), p2i(bottom()), p2i(end()));
  HeapWord* current_top = top();
  assert(p <= current_top,
         "p > current top - p: " PTR_FORMAT ", current top: " PTR_FORMAT,
         p2i(p), p2i(current_top));
  assert(p == current_top || oopDesc::is_oop(cast_to_oop(p)),
         "p (" PTR_FORMAT ") is not a block start - "
         "current_top: " PTR_FORMAT ", is_oop: %s",
         p2i(p), p2i(current_top), BOOL_TO_STR(oopDesc::is_oop(cast_to_oop(p))));
  if (p < current_top) {
    return cast_to_oop(p)->size();
  } else {
    assert(p == current_top, "just checking");
    return pointer_delta(end(), (HeapWord*) p);
  }
}

// This version requires locking.
inline HeapWord* ContiguousSpace::allocate_impl(size_t size) {
  assert(Heap_lock->owned_by_self() ||
         (SafepointSynchronize::is_at_safepoint() && Thread::current()->is_VM_thread()),
         "not locked");
  HeapWord* obj = top();
  if (pointer_delta(end(), obj) >= size) {
    HeapWord* new_top = obj + size;
    set_top(new_top);
    assert(is_aligned(obj) && is_aligned(new_top), "checking alignment");
    return obj;
  } else {
    return NULL;
  }
}

// This version is lock-free.
inline HeapWord* ContiguousSpace::par_allocate_impl(size_t size) {
  do {
    HeapWord* obj = top();
    if (pointer_delta(end(), obj) >= size) {
      HeapWord* new_top = obj + size;
      HeapWord* result = Atomic::cmpxchg(top_addr(), obj, new_top);
      // result can be one of two:
      //  the old top value: the exchange succeeded
      //  otherwise: the new value of the top is returned.
      if (result == obj) {
        assert(is_aligned(obj) && is_aligned(new_top), "checking alignment");
        return obj;
      }
    } else {
      return NULL;
    }
  } while (true);
}

// Requires locking.
HeapWord* ContiguousSpace::allocate(size_t size) {
  return allocate_impl(size);
}

// Lock-free.
HeapWord* ContiguousSpace::par_allocate(size_t size) {
  return par_allocate_impl(size);
}

#if INCLUDE_SERIALGC
void TenuredSpace::initialize_threshold() {
  _offsets.initialize_threshold();
}

void TenuredSpace::alloc_block(HeapWord* start, HeapWord* end) {
  _offsets.alloc_block(start, end);
}

TenuredSpace::TenuredSpace(BlockOffsetSharedArray* sharedOffsetArray,
                           MemRegion mr) :
  _offsets(sharedOffsetArray, mr),
  _par_alloc_lock(Mutex::safepoint, "TenuredSpaceParAlloc_lock", true)
{
  _offsets.set_contig_space(this);
  initialize(mr, SpaceDecorator::Clear, SpaceDecorator::Mangle);
}

#define OBJ_SAMPLE_INTERVAL 0
#define BLOCK_SAMPLE_INTERVAL 100

void TenuredSpace::verify() const {
  HeapWord* p = bottom();
  HeapWord* prev_p = NULL;
  int objs = 0;
  int blocks = 0;

  if (VerifyObjectStartArray) {
    _offsets.verify();
  }

  while (p < top()) {
    size_t size = cast_to_oop(p)->size();
    // For a sampling of objects in the space, find it using the
    // block offset table.
    if (blocks == BLOCK_SAMPLE_INTERVAL) {
      guarantee(p == block_start_const(p + (size/2)),
                "check offset computation");
      blocks = 0;
    } else {
      blocks++;
    }

    if (objs == OBJ_SAMPLE_INTERVAL) {
      oopDesc::verify(cast_to_oop(p));
      objs = 0;
    } else {
      objs++;
    }
    prev_p = p;
    p += size;
  }
  guarantee(p == top(), "end of last object must match end of space");
}


size_t TenuredSpace::allowed_dead_ratio() const {
  return MarkSweepDeadRatio;
}
#endif // INCLUDE_SERIALGC

// Compute the forward sizes and leave out objects whose position could
// possibly overlap other objects.
HeapWord* CompactibleSpace::forward_with_rescue(HeapWord* q, size_t size,
                                                CompactPoint* cp, HeapWord* compact_top, bool force_forward) {
  size_t forward_size = size;

  // (DCEVM) There is a new version of the class of q => different size
  if (cast_to_oop(q)->klass()->new_version() != NULL) {

    size_t new_size = cast_to_oop(q)->size_given_klass(cast_to_oop(q)->klass()->new_version());
    // assert(size != new_size, "instances without changed size have to be updated prior to GC run");
    forward_size = new_size;
  }

  compact_top = forward_compact_top(forward_size, cp, compact_top);

  if (must_rescue(cast_to_oop(q), cast_to_oop(compact_top))) {
    if (MarkSweep::_rescued_oops == NULL) {
      MarkSweep::_rescued_oops = new GrowableArray<HeapWord*>(128);
    }
    MarkSweep::_rescued_oops->append(q);
    return compact_top;
  }

  return forward(cast_to_oop(q), forward_size, cp, compact_top, force_forward);
}

// Compute the forwarding addresses for the objects that need to be rescued.
HeapWord* CompactibleSpace::forward_rescued(CompactPoint* cp, HeapWord* compact_top) {
  // TODO: empty the _rescued_oops after ALL spaces are compacted!
  if (MarkSweep::_rescued_oops != NULL) {
    for (int i=0; i<MarkSweep::_rescued_oops->length(); i++) {
      HeapWord* q = MarkSweep::_rescued_oops->at(i);

      size_t size = block_size(q);

      // (DCEVM) There is a new version of the class of q => different size
      if (cast_to_oop(q)->klass()->new_version() != NULL) {
        size_t new_size = cast_to_oop(q)->size_given_klass(cast_to_oop(q)->klass()->new_version());
        // assert(size != new_size, "instances without changed size have to be updated prior to GC run");
        size = new_size;
      }

      compact_top = cp->space->forward(cast_to_oop(q), size, cp, compact_top, true);
      assert(compact_top <= end(), "must not write over end of space!");
    }
    MarkSweep::_rescued_oops->clear();
    MarkSweep::_rescued_oops = NULL;
  }
  return compact_top;
}

