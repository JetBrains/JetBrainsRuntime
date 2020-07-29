/*
 * Copyright (c) 2019, Red Hat, Inc. All rights reserved.
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

#ifndef SHARE_GC_SHENANDOAH_SHENANDOAHPARALLELCLEANING_HPP
#define SHARE_GC_SHENANDOAH_SHENANDOAHPARALLELCLEANING_HPP

#include "gc/shared/workgroup.hpp"
#include "gc/shenandoah/parallelCleaning.hpp"
#include "gc/shenandoah/shenandoahRootProcessor.hpp"
#include "memory/iterator.hpp"

// Perform weak root cleaning at a pause
template <typename IsAlive, typename KeepAlive>
class ShenandoahParallelWeakRootsCleaningTask : public AbstractGangTask {
protected:
  ShenandoahPhaseTimings::Phase _phase;
  ShenandoahWeakRoots     _weak_roots;
  IsAlive*                _is_alive;
  KeepAlive*              _keep_alive;
public:
  ShenandoahParallelWeakRootsCleaningTask(ShenandoahPhaseTimings::Phase phase,
                                          IsAlive* is_alive,
                                          KeepAlive* keep_alive,
                                          uint num_workers);
  ~ShenandoahParallelWeakRootsCleaningTask();

  void work(uint worker_id);
};

#endif // SHARE_GC_SHENANDOAH_SHENANDOAHPARALLELCLEANING_HPP
