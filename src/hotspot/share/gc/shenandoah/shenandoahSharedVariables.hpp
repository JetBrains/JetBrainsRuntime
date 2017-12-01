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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHSHAREDFLAG_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHSHAREDFLAG_HPP

#include "memory/allocation.hpp"

typedef jbyte ShenandoahSharedValue;

// Needed for cooperation with generated code.
STATIC_ASSERT(sizeof(ShenandoahSharedValue) == 1);

typedef struct ShenandoahSharedFlag {
  enum {
    UNSET = 0,
    SET = 1,
  };

  DEFINE_PAD_MINUS_SIZE(0, DEFAULT_CACHE_LINE_SIZE, sizeof(volatile ShenandoahSharedValue));
  volatile ShenandoahSharedValue value;
  DEFINE_PAD_MINUS_SIZE(1, DEFAULT_CACHE_LINE_SIZE, 0);

  ShenandoahSharedFlag() {
    unset();
  }

  void set() {
    OrderAccess::release_store_fence(&value, (ShenandoahSharedValue)SET);
  }

  void unset() {
    OrderAccess::release_store_fence(&value, (ShenandoahSharedValue)UNSET);
  }

  bool is_set() const {
    return OrderAccess::load_acquire(&value) == SET;
  }

  bool is_unset() const {
    return OrderAccess::load_acquire(&value) == UNSET;
  }

  void set_cond(bool value) {
    if (value) {
      set();
    } else {
      unset();
    }
  }

  bool try_set() {
    if (is_set()) {
      return false;
    }
    ShenandoahSharedValue old = Atomic::cmpxchg((ShenandoahSharedValue)SET, &value, (ShenandoahSharedValue)UNSET);
    return old == UNSET; // success
  }

  bool try_unset() {
    if (!is_set()) {
      return false;
    }
    ShenandoahSharedValue old = Atomic::cmpxchg((ShenandoahSharedValue)UNSET, &value, (ShenandoahSharedValue)SET);
    return old == SET; // success
  }

  volatile ShenandoahSharedValue* addr_of() {
    return &value;
  }

private:
  volatile ShenandoahSharedValue* operator&() {
    fatal("Use addr_of() instead");
    return NULL;
  }

  bool operator==(ShenandoahSharedFlag& other) { fatal("Use is_set() instead"); return false; }
  bool operator!=(ShenandoahSharedFlag& other) { fatal("Use is_set() instead"); return false; }
  bool operator> (ShenandoahSharedFlag& other) { fatal("Use is_set() instead"); return false; }
  bool operator>=(ShenandoahSharedFlag& other) { fatal("Use is_set() instead"); return false; }
  bool operator< (ShenandoahSharedFlag& other) { fatal("Use is_set() instead"); return false; }
  bool operator<=(ShenandoahSharedFlag& other) { fatal("Use is_set() instead"); return false; }

} ShenandoahSharedFlag;

template<class T>
struct ShenandoahSharedEnumFlag {
  DEFINE_PAD_MINUS_SIZE(0, DEFAULT_CACHE_LINE_SIZE, sizeof(volatile ShenandoahSharedValue));
  volatile ShenandoahSharedValue value;
  DEFINE_PAD_MINUS_SIZE(1, DEFAULT_CACHE_LINE_SIZE, 0);

  ShenandoahSharedEnumFlag() {
    value = 0;
  }

  void set(T v) {
    assert (v >= 0, "sanity");
    assert (v < (1 << sizeof(ShenandoahSharedValue)), "sanity");
    OrderAccess::release_store_fence(&value, (ShenandoahSharedValue)v);
  }

  T get() const {
    return (T)OrderAccess::load_acquire(&value);
  }

  T cmpxchg(T new_value, T expected) {
    assert (new_value >= 0, "sanity");
    assert (new_value < (1 << sizeof(ShenandoahSharedValue)), "sanity");
    return (T)Atomic::cmpxchg((ShenandoahSharedValue)new_value, &value, (ShenandoahSharedValue)expected);
  }

  volatile ShenandoahSharedValue* addr_of() {
    return &value;
  }

private:
  volatile T* operator&() {
    fatal("Use addr_of() instead");
    return NULL;
  }

  bool operator==(ShenandoahSharedEnumFlag& other) { fatal("Use get() instead"); return false; }
  bool operator!=(ShenandoahSharedEnumFlag& other) { fatal("Use get() instead"); return false; }
  bool operator> (ShenandoahSharedEnumFlag& other) { fatal("Use get() instead"); return false; }
  bool operator>=(ShenandoahSharedEnumFlag& other) { fatal("Use get() instead"); return false; }
  bool operator< (ShenandoahSharedEnumFlag& other) { fatal("Use get() instead"); return false; }
  bool operator<=(ShenandoahSharedEnumFlag& other) { fatal("Use get() instead"); return false; }

};


#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHSHAREDFLAG_HPP
