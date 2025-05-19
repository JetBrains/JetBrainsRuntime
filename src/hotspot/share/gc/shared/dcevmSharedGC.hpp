/*
 * Copyright (c) 1997, 2018, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_GC_DCEVM_SHARED_GC_HPP
#define SHARE_GC_DCEVM_SHARED_GC_HPP

#include "gc/shared/collectedHeap.hpp"
#include "gc/shared/taskqueue.hpp"
#include "memory/iterator.hpp"
#include "oops/markWord.hpp"
#include "oops/oop.hpp"
#include "runtime/timer.hpp"
#include "utilities/growableArray.hpp"
#include "utilities/stack.hpp"
#include "utilities/resourceHash.hpp"

// Shared GC code used from different GC (Serial, CMS, G1) on enhanced redefinition
class DcevmSharedGC : public CHeapObj<mtInternal>  {
private:
  struct SymbolKey {
    InstanceKlass* ik;
    Symbol*        dst_sig;
  };
  static unsigned symbol_hash(const SymbolKey& k) {
    return (uintptr_t)k.ik ^ (uintptr_t)k.dst_sig;
  }
  static bool symbol_eq(const SymbolKey& a, const SymbolKey& b) {
    return a.ik == b.ik && a.dst_sig == b.dst_sig;
  }
  typedef ResourceHashtable<SymbolKey, bool, 512,
          AnyObj::C_HEAP, mtInternal,
          &symbol_hash, &symbol_eq> CompatTable;

  struct OffsetKey {
    InstanceKlass* ik;
    int            offset;
  };
  static unsigned offset_hash(const OffsetKey& k) {
    return uintptr_t(k.ik) ^ k.offset;
  }
  static bool offset_eq(const OffsetKey& a, const OffsetKey& b) {
    return a.ik == b.ik && a.offset == b.offset;
  }
  typedef ResourceHashtable<OffsetKey, Symbol*, 512,
          AnyObj::C_HEAP, mtInternal,
          &offset_hash, &offset_eq> FieldSigTable;

  CompatTable*          _compat_table;
  FieldSigTable*        _field_sig_table;
  static DcevmSharedGC* _static_instance;

public:
  // ------------------------------------------------------------------
  //  update info flags
  //
  //  bit 31 : sign bit  (<0 = fill, >0 = copy)
  //  bit 30 : UpdateInfoCompatFlag – copy segment requires per-oop compatibility check
  //  bits 0-29 : raw byte length of the segment
  // ------------------------------------------------------------------
  static const int UpdateInfoCompatFlag  = 1U << 30;
  static const int UpdateInfoLengthMask  = ~(1U << 31 | UpdateInfoCompatFlag);

  DcevmSharedGC() {
    _compat_table = new (mtInternal) CompatTable();
    _field_sig_table = new (mtInternal) FieldSigTable();
  }

  ~DcevmSharedGC() {
    delete _compat_table;
    delete _field_sig_table;
  }

  static void create_static_instance();
  static void destroy_static_instance();

  static void copy_rescued_objects_back(GrowableArray<HeapWord*>* rescued_oops, bool must_be_new);
  static void copy_rescued_objects_back(GrowableArray<HeapWord*>* rescued_oops, int from, int to, bool must_be_new);
  static void clear_rescued_objects_resource(GrowableArray<HeapWord*>* rescued_oops);
  static void clear_rescued_objects_heap(GrowableArray<HeapWord*>* rescued_oops);
  static void update_fields(oop q, oop new_location);

  bool is_compatible(oop fld_holder, int fld_offset, oop fld_val);
  void update_fields(oop new_obj, oop old_obj, int *cur, bool do_compat_check);
  void update_fields_in_old(oop old_obj, int *cur);
};

#endif
