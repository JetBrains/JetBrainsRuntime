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

#ifndef SHARE_VM_GC_SHARED_CMBITMAP_HPP
#define SHARE_VM_GC_SHARED_CMBITMAP_HPP

#include "memory/memRegion.hpp"
#include "utilities/bitMap.hpp"
#include "utilities/globalDefinitions.hpp"

// A generic CM bit map.  This is essentially a wrapper around the BitMap
// class, with one bit per (1<<_shifter) HeapWords.

class MarkBitMapRO {
 protected:
  MemRegion _covered;      // The heap area covered by this bitmap.
  HeapWord* _bmStartWord;  // base address of range covered by map
  size_t    _bmWordSize;   // map size (in #HeapWords covered)
  const int _shifter;      // map to char or bit
  BitMapView _bm;          // the bit map itself

 public:
  // constructor
  MarkBitMapRO(int shifter);

  // inquiries
  HeapWord* startWord()   const { return _bmStartWord; }
  // the following is one past the last word in space
  HeapWord* endWord()     const { return _bmStartWord + _bmWordSize; }

  // read marks

  bool isMarked(HeapWord* addr) const {
    assert(_bmStartWord <= addr && addr < (_bmStartWord + _bmWordSize),
           "outside underlying space?");
    return _bm.at(heapWordToOffset(addr));
  }

  // iteration
  inline bool iterate(BitMapClosure* cl, MemRegion mr);

  // Return the address corresponding to the next marked bit at or after
  // "addr", and before "limit", if "limit" is non-NULL.  If there is no
  // such bit, returns "limit" if that is non-NULL, or else "endWord()".
  inline HeapWord* getNextMarkedWordAddress(const HeapWord* addr,
                                     const HeapWord* limit = NULL) const;

  // conversion utilities
  HeapWord* offsetToHeapWord(size_t offset) const {
    return _bmStartWord + (offset << _shifter);
  }
  size_t heapWordToOffset(const HeapWord* addr) const {
    return pointer_delta(addr, _bmStartWord) >> _shifter;
  }

  // The argument addr should be the start address of a valid object
  inline HeapWord* nextObject(HeapWord* addr);

  void print_on_error(outputStream* st, const char* prefix) const;

  // debugging
  NOT_PRODUCT(bool covers(MemRegion rs) const;)
};

class MarkBitMap : public MarkBitMapRO {
 private:
  // Clear bitmap range
  void do_clear(MemRegion mr, bool large);

 public:
  static size_t compute_size(size_t heap_size);
  // Returns the amount of bytes on the heap between two marks in the bitmap.
  static size_t mark_distance();
  // Returns how many bytes (or bits) of the heap a single byte (or bit) of the
  // mark bitmap corresponds to. This is the same as the mark distance above.  static size_t heap_map_factor() {
  static size_t heap_map_factor() {
    return mark_distance();
  }

  MarkBitMap() : MarkBitMapRO(LogMinObjAlignment) {}

  // Initializes the underlying BitMap to cover the given area.
  void initialize(MemRegion heap, MemRegion bitmap);

  // Write marks.
  inline void mark(HeapWord* addr);
  inline void clear(HeapWord* addr);
  inline bool parMark(HeapWord* addr);

  // Clear range. For larger regions, use *_large.
  void clear()                         { do_clear(_covered, true); }
  void clear_range(MemRegion mr)       { do_clear(mr, false);      }
  void clear_range_large(MemRegion mr) { do_clear(mr, true);       }
};

#endif // SHARE_VM_GC_SHARED_CMBITMAP_HPP
