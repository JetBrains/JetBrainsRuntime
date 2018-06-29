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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHCONNECTIONMATRIX_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHCONNECTIONMATRIX_HPP

#include "memory/allocation.hpp"

class ShenandoahConnectionMatrix : public CHeapObj<mtGC> {
private:
  const size_t    _stride;
  const size_t    _region_shift;
  jbyte*          _matrix;
  uintptr_t       _magic_offset;

  inline size_t index_of(size_t from_idx, size_t to_idx) const;
  inline jbyte* compute_address(const void* from_addr, const void* to_addr) const;

public:
  ShenandoahConnectionMatrix(size_t max_regions);

  inline bool is_connected(size_t from_idx, size_t to_idx) const;
  inline uint count_connected_to(size_t to_idx, size_t count) const;

  /**
   * Enumerate regions connected to this one, and terminates early if more
   * than from_idx_max connections are found.
   *
   * @param to_idx            to region to scan
   * @param count             scan from zero to this regions
   * @param from_idxs         array of from-idx indices
   * @param from_idx_count    (out) number of from-idx indices
   * @param from_idx_max      max number of from-inx indices
   * @return true if from_idx_count < from_idx_max
   */
  inline bool enumerate_connected_to(size_t to_idx, size_t count, size_t* from_idxs, size_t& from_idx_count, size_t from_idx_max) const;

  inline void clear_connected(size_t from_idx, size_t to_idx);
  inline void set_connected(const void* from_addr, const void* to_addr);
  inline void clear_region_inbound(size_t idx);
  inline void clear_region_outbound(size_t idx);
  inline void clear_region(size_t idx);
  void clear_all();

  address matrix_addr() const { return (address) _matrix; }
  size_t stride()       const { return _stride; }
  jint stride_jint()    const {
    assert (_stride <= max_jint, "sanity");
    return (jint)_stride;
  }

  uintptr_t magic_offset() const {
    return _magic_offset;
  }

  void print_on(outputStream* st) const;
};

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHCONNECTIONMATRIX_HPP
