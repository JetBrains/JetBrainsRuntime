/*
 * Copyright (c) 2018, Red Hat, Inc. and/or its affiliates.
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

#ifndef SHARE_VM_GC_SHENANDOAH_HEURISTICS_SHENANDOAHPARTIALHEURISTICS_HPP
#define SHARE_VM_GC_SHENANDOAH_HEURISTICS_SHENANDOAHPARTIALHEURISTICS_HPP

#include "gc/shenandoah/heuristics/shenandoahTraversalHeuristics.hpp"

class ShenandoahPartialHeuristics : public ShenandoahTraversalHeuristics {
protected:
  size_t* _from_idxs;

  bool is_minor_gc() const;

  // Utility method to remove any cset regions from root set and
  // add all cset regions to the traversal set.
  void filter_regions();

public:
  ShenandoahPartialHeuristics();

  void initialize();

  virtual ~ShenandoahPartialHeuristics();

  bool should_start_update_refs();

  virtual bool should_unload_classes();

  virtual bool should_process_references();

  bool should_start_normal_gc() const;

  virtual bool is_diagnostic();

  virtual bool is_experimental();

};

#endif // SHARE_VM_GC_SHENANDOAH_HEURISTICS_SHENANDOAHPARTIALHEURISTICS_HPP
