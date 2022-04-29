/*
 * Copyright (c) 1999, 2018, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2014, 2019, Red Hat Inc. All rights reserved.
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

#ifndef OS_CPU_BSD_AARCH64_VM_ATOMIC_BSD_AARCH64_HPP
#define OS_CPU_BSD_AARCH64_VM_ATOMIC_BSD_AARCH64_HPP

// Implementation of class atomic
// Note that memory_order_conservative requires a full barrier after atomic stores.
// See https://patchwork.kernel.org/patch/3575821/

#define FULL_MEM_BARRIER  __sync_synchronize()
#define READ_MEM_BARRIER  __atomic_thread_fence(__ATOMIC_ACQUIRE);
#define WRITE_MEM_BARRIER __atomic_thread_fence(__ATOMIC_RELEASE);

template<size_t byte_size>
struct Atomic::PlatformAdd
  : Atomic::AddAndFetch<Atomic::PlatformAdd<byte_size> >
{
  template<typename I, typename D>
  D add_and_fetch(I add_value, D volatile* dest, atomic_memory_order order) const {
    D res = __atomic_add_fetch(dest, add_value, __ATOMIC_RELEASE);
    FULL_MEM_BARRIER;
    return res;
  }
};

template<size_t byte_size>
template<typename T>
inline T Atomic::PlatformXchg<byte_size>::operator()(T exchange_value,
                                                     T volatile* dest,
                                                     atomic_memory_order order) const {
  STATIC_ASSERT(byte_size == sizeof(T));
  T res = __atomic_exchange_n(dest, exchange_value, __ATOMIC_RELEASE);
  FULL_MEM_BARRIER;
  return res;
}

template<size_t byte_size>
template<typename T>
inline T Atomic::PlatformCmpxchg<byte_size>::operator()(T exchange_value,
                                                        T volatile* dest,
                                                        T compare_value,
                                                        atomic_memory_order order) const {
  STATIC_ASSERT(byte_size == sizeof(T));
  if (order == memory_order_relaxed) {
    T value = compare_value;
    __atomic_compare_exchange(dest, &value, &exchange_value, /*weak*/false,
                              __ATOMIC_RELAXED, __ATOMIC_RELAXED);
    return value;
  } else {
    T value = compare_value;
    FULL_MEM_BARRIER;
    __atomic_compare_exchange(dest, &value, &exchange_value, /*weak*/false,
                              __ATOMIC_RELAXED, __ATOMIC_RELAXED);
    FULL_MEM_BARRIER;
    return value;
  }
}

#endif // OS_CPU_BSD_AARCH64_VM_ATOMIC_BSD_AARCH64_HPP
