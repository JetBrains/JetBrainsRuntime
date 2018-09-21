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

#include "precompiled.hpp"

#include "gc/shenandoah/heuristics/shenandoahPassiveHeuristics.hpp"
#include "logging/log.hpp"
#include "logging/logTag.hpp"

ShenandoahPassiveHeuristics::ShenandoahPassiveHeuristics() : ShenandoahAdaptiveHeuristics() {
  // Do not allow concurrent cycles.
  FLAG_SET_DEFAULT(ExplicitGCInvokesConcurrent, false);

  // Passive runs with max speed, reacts on allocation failure.
  FLAG_SET_DEFAULT(ShenandoahPacing, false);

  // No need for evacuation reserve with Full GC, only for Degenerated GC.
  if (!ShenandoahDegeneratedGC) {
    SHENANDOAH_ERGO_OVERRIDE_DEFAULT(ShenandoahEvacReserve, 0);
  }

  // Disable known barriers by default.
  SHENANDOAH_ERGO_DISABLE_FLAG(ShenandoahSATBBarrier);
  SHENANDOAH_ERGO_DISABLE_FLAG(ShenandoahKeepAliveBarrier);
  SHENANDOAH_ERGO_DISABLE_FLAG(ShenandoahWriteBarrier);
  SHENANDOAH_ERGO_DISABLE_FLAG(ShenandoahReadBarrier);
  SHENANDOAH_ERGO_DISABLE_FLAG(ShenandoahStoreValEnqueueBarrier);
  SHENANDOAH_ERGO_DISABLE_FLAG(ShenandoahStoreValReadBarrier);
  SHENANDOAH_ERGO_DISABLE_FLAG(ShenandoahCASBarrier);
  SHENANDOAH_ERGO_DISABLE_FLAG(ShenandoahAcmpBarrier);
  SHENANDOAH_ERGO_DISABLE_FLAG(ShenandoahCloneBarrier);
}

bool ShenandoahPassiveHeuristics::should_start_normal_gc() const {
  // Never do concurrent GCs.
  return false;
}

bool ShenandoahPassiveHeuristics::should_process_references() {
  if (ShenandoahRefProcFrequency == 0) return false;
  // Always process references.
  return true;
}

bool ShenandoahPassiveHeuristics::should_unload_classes() {
  if (ShenandoahUnloadClassesFrequency == 0) return false;
  // Always unload classes.
  return true;
}

bool ShenandoahPassiveHeuristics::should_degenerate_cycle() {
  // Always fail to Degenerated GC, if enabled
  return ShenandoahDegeneratedGC;
}

const char* ShenandoahPassiveHeuristics::name() {
  return "passive";
}

bool ShenandoahPassiveHeuristics::is_diagnostic() {
  return true;
}

bool ShenandoahPassiveHeuristics::is_experimental() {
  return false;
}
