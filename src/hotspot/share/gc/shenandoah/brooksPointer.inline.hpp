/*
 * Copyright (c) 2015, Red Hat, Inc. and/or its affiliates.
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

#ifndef SHARE_VM_GC_SHENANDOAH_BROOKSPOINTER_INLINE_HPP
#define SHARE_VM_GC_SHENANDOAH_BROOKSPOINTER_INLINE_HPP

#include "gc/shenandoah/brooksPointer.hpp"
#include "gc/shenandoah/shenandoahVerifier.hpp"
#include "gc/shenandoah/shenandoahHeap.hpp"
#include "gc/shenandoah/shenandoahHeapRegion.hpp"
#include "runtime/atomic.hpp"

inline HeapWord** BrooksPointer::brooks_ptr_addr(oop obj) {
  return (HeapWord**)((HeapWord*) obj + word_offset());
}

inline void BrooksPointer::initialize(oop obj) {
#ifdef ASSERT
  assert(ShenandoahHeap::heap()->is_in(obj), "oop must point to a heap address");
#endif
  *brooks_ptr_addr(obj) = (HeapWord*) obj;
}

inline void BrooksPointer::set_raw(oop holder, HeapWord* update) {
  assert(UseShenandoahGC, "must only be called when Shenandoah is used.");
  *brooks_ptr_addr(holder) = update;
}

inline HeapWord* BrooksPointer::get_raw(oop holder) {
  assert(UseShenandoahGC, "must only be called when Shenandoah is used.");
  return *brooks_ptr_addr(holder);
}

inline oop BrooksPointer::forwardee(oop obj) {
#ifdef ASSERT
  ShenandoahVerifier::verify_oop(obj);
#endif
  return oop(*brooks_ptr_addr(obj));
}

inline oop BrooksPointer::try_update_forwardee(oop holder, oop update) {
#ifdef ASSERT
  ShenandoahVerifier::verify_oop_fwdptr(holder, update);
#endif

  oop result = (oop) Atomic::cmpxchg_ptr(update, brooks_ptr_addr(holder), holder);

#ifdef ASSERT
  assert(result != NULL, "CAS result is not NULL");
  ShenandoahVerifier::verify_oop(holder);
#endif

  return result;
}

#endif // SHARE_VM_GC_SHENANDOAH_BROOKSPOINTER_INLINE_HPP
