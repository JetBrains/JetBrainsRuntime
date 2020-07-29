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
 *
 */

#ifndef SHARE_VM_GC_SHARED_CMBITMAP_INLINE_HPP
#define SHARE_VM_GC_SHARED_CMBITMAP_INLINE_HPP

#include "gc/shenandoah/markBitMap.hpp"
#include "oops/oop.inline.hpp"
#include "utilities/bitMap.inline.hpp"
#include "utilities/align.hpp"

inline HeapWord* MarkBitMapRO::getNextMarkedWordAddress(const HeapWord* addr,
                                               const HeapWord* limit) const {
  // First we must round addr *up* to a possible object boundary.
  addr = (HeapWord*)align_up((intptr_t)addr,
                                  HeapWordSize << _shifter);
  size_t addrOffset = heapWordToOffset(addr);
  assert(limit != NULL, "limit must not be NULL");
  size_t limitOffset = heapWordToOffset(limit);
  size_t nextOffset = _bm.get_next_one_offset(addrOffset, limitOffset);
  HeapWord* nextAddr = offsetToHeapWord(nextOffset);
  assert(nextAddr >= addr, "get_next_one postcondition");
  assert(nextAddr == limit || isMarked(nextAddr),
         "get_next_one postcondition");
  return nextAddr;
}

inline bool MarkBitMapRO::iterate(BitMapClosure* cl, MemRegion mr) {
  HeapWord* start_addr = MAX2(startWord(), mr.start());
  HeapWord* end_addr = MIN2(endWord(), mr.end());

  if (end_addr > start_addr) {
    // Right-open interval [start-offset, end-offset).
    BitMap::idx_t start_offset = heapWordToOffset(start_addr);
    BitMap::idx_t end_offset = heapWordToOffset(end_addr);

    start_offset = _bm.get_next_one_offset(start_offset, end_offset);
    while (start_offset < end_offset) {
      if (!cl->do_bit(start_offset)) {
        return false;
      }
      HeapWord* next_addr = MIN2(nextObject(offsetToHeapWord(start_offset)), end_addr);
      BitMap::idx_t next_offset = heapWordToOffset(next_addr);
      start_offset = _bm.get_next_one_offset(next_offset, end_offset);
    }
  }
  return true;
}

// The argument addr should be the start address of a valid object
HeapWord* MarkBitMapRO::nextObject(HeapWord* addr) {
  oop obj = (oop) addr;
  HeapWord* res =  addr + obj->size();
  assert(offsetToHeapWord(heapWordToOffset(res)) == res, "sanity");
  return res;
}

#define check_mark(addr)                                                       \
  assert(_bmStartWord <= (addr) && (addr) < (_bmStartWord + _bmWordSize),      \
         "outside underlying space?");                                         \
  /* assert(G1CollectedHeap::heap()->is_in_exact(addr),                  \
         err_msg("Trying to access not available bitmap "PTR_FORMAT            \
                 " corresponding to "PTR_FORMAT" (%u)",                        \
                 p2i(this), p2i(addr), G1CollectedHeap::heap()->addr_to_region(addr))); */

inline void MarkBitMap::mark(HeapWord* addr) {
  check_mark(addr);
  _bm.set_bit(heapWordToOffset(addr));
}

inline void MarkBitMap::clear(HeapWord* addr) {
  check_mark(addr);
  _bm.clear_bit(heapWordToOffset(addr));
}

inline bool MarkBitMap::parMark(HeapWord* addr) {
  check_mark(addr);
  return _bm.par_set_bit(heapWordToOffset(addr));
}

#undef check_mark

#endif // SHARE_VM_GC_SHARED_CMBITMAP_INLINE_HPP
