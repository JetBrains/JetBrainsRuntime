/*
 * Copyright (c) 1997, 2018, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2012, 2014 SAP SE. All rights reserved.
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

#ifndef OS_CPU_AIX_OJDKPPC_VM_ORDERACCESS_AIX_PPC_HPP
#define OS_CPU_AIX_OJDKPPC_VM_ORDERACCESS_AIX_PPC_HPP

// Included in orderAccess.hpp header file.

// Compiler version last used for testing: xlc 12
// Please update this information when this file changes

// Implementation of class OrderAccess.

//
// Machine barrier instructions:
//
// - sync            Two-way memory barrier, aka fence.
// - lwsync          orders  Store|Store,
//                            Load|Store,
//                            Load|Load,
//                   but not Store|Load
// - eieio           orders  Store|Store
// - isync           Invalidates speculatively executed instructions,
//                   but isync may complete before storage accesses
//                   associated with instructions preceding isync have
//                   been performed.
//
// Semantic barrier instructions:
// (as defined in orderAccess.hpp)
//
// - release         orders Store|Store,       (maps to lwsync)
//                           Load|Store
// - acquire         orders  Load|Store,       (maps to lwsync)
//                           Load|Load
// - fence           orders Store|Store,       (maps to sync)
//                           Load|Store,
//                           Load|Load,
//                          Store|Load
//

#define inlasm_sync()     __asm__ __volatile__ ("sync"   : : : "memory");
#define inlasm_lwsync()   __asm__ __volatile__ ("lwsync" : : : "memory");
#define inlasm_eieio()    __asm__ __volatile__ ("eieio"  : : : "memory");
#define inlasm_isync()    __asm__ __volatile__ ("isync"  : : : "memory");
// Use twi-isync for load_acquire (faster than lwsync).
// ATTENTION: seems like xlC 10.1 has problems with this inline assembler macro (VerifyMethodHandles found "bad vminfo in AMH.conv"):
// #define inlasm_acquire_reg(X) __asm__ __volatile__ ("twi 0,%0,0\n isync\n" : : "r" (X) : "memory");
#define inlasm_acquire_reg(X) inlasm_lwsync();

inline void OrderAccess::loadload()   { inlasm_lwsync(); }
inline void OrderAccess::storestore() { inlasm_lwsync(); }
inline void OrderAccess::loadstore()  { inlasm_lwsync(); }
inline void OrderAccess::storeload()  { inlasm_sync();   }

inline void OrderAccess::acquire()    { inlasm_lwsync(); }
inline void OrderAccess::release()    { inlasm_lwsync(); }
inline void OrderAccess::fence()      { inlasm_sync();   }

template<size_t byte_size>
struct OrderAccess::PlatformOrderedLoad<byte_size, X_ACQUIRE>
{
  template <typename T>
  T operator()(const volatile T* p) const { T t = Atomic::load(p); inlasm_acquire_reg(t); return t; }
};

#undef inlasm_sync
#undef inlasm_lwsync
#undef inlasm_eieio
#undef inlasm_isync

#endif // OS_CPU_AIX_OJDKPPC_VM_ORDERACCESS_AIX_PPC_HPP
