/*
 * Copyright (c) 2001, 2015, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_VM_GC_SHARED_BARRIERSET_INLINE_HPP
#define SHARE_VM_GC_SHARED_BARRIERSET_INLINE_HPP

#include "gc/shared/barrierSet.hpp"
#include "gc/shared/cardTableModRefBS.inline.hpp"
#include "utilities/align.hpp"

// Inline functions of BarrierSet, which de-virtualize certain
// performance-critical calls when the barrier is the most common
// card-table kind.

inline bool BarrierSet::devirtualize_reference_writes() const {
  switch (kind()) {
  case CardTableForRS:
  case CardTableExtension:
    return true;
  default:
    return false;
  }
}

template <class T> void BarrierSet::write_ref_field_pre(T* field, oop new_val) {
  if (devirtualize_reference_writes()) {
    barrier_set_cast<CardTableModRefBS>(this)->inline_write_ref_field_pre(field, new_val);
  } else {
    write_ref_field_pre_work(field, new_val);
  }
}

void BarrierSet::write_ref_field(void* field, oop new_val, bool release) {
  if (devirtualize_reference_writes()) {
    barrier_set_cast<CardTableModRefBS>(this)->inline_write_ref_field(field, new_val, release);
  } else {
    write_ref_field_work(field, new_val, release);
  }
}

inline void BarrierSet::write_region(MemRegion mr) {
  if (devirtualize_reference_writes()) {
    barrier_set_cast<CardTableModRefBS>(this)->inline_write_region(mr);
  } else {
    write_region_work(mr);
  }
}

#endif // SHARE_VM_GC_SHARED_BARRIERSET_INLINE_HPP
