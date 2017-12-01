/*
 * Copyright (c) 2013, 2015, Red Hat, Inc. and/or its affiliates.
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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHCONCURRENTTHREAD_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHCONCURRENTTHREAD_HPP

#include "gc/shenandoah/shenandoahHeap.hpp"
#include "gc/shenandoah/shenandoahSharedVariables.hpp"
#include "gc/shared/concurrentGCThread.hpp"
#include "gc/shared/gcCause.hpp"
#include "memory/resourceArea.hpp"
#include "runtime/task.hpp"

// Periodic task is useful for doing asynchronous things that do not require (heap) locks,
// or synchronization with other parts of collector. These could run even when ShenandoahConcurrentThread
// is busy driving the GC cycle.
class ShenandoahPeriodicTask : public PeriodicTask {
private:
  ShenandoahConcurrentThread* _thread;
public:
  ShenandoahPeriodicTask(ShenandoahConcurrentThread* thread) :
          PeriodicTask(100), _thread(thread) {}
  virtual void task();
};

class ShenandoahConcurrentThread: public ConcurrentGCThread {
  friend class VMStructs;

private:
  // While we could have a single lock for these, it may risk unblocking
  // full GC waiters when concurrent cycle finishes.
  Monitor _full_gc_lock;
  Monitor _conc_gc_lock;
  ShenandoahPeriodicTask _periodic_task;

public:
  void run_service();
  void stop_service();

private:
  ShenandoahSharedFlag _do_concurrent_gc;
  ShenandoahSharedFlag _do_full_gc;
  ShenandoahSharedFlag _graceful_shutdown;
  ShenandoahSharedFlag _do_counters_update;
  ShenandoahSharedFlag _force_counters_update;
  GCCause::Cause _full_gc_cause;

  bool check_cancellation();
  void service_normal_cycle();
  void service_fullgc_cycle();
  void service_partial_cycle();

public:
  // Constructor
  ShenandoahConcurrentThread();
  ~ShenandoahConcurrentThread();

  // Printing
  void print_on(outputStream* st) const;
  void print() const;

  void do_conc_gc();
  void do_full_gc(GCCause::Cause cause);

  bool try_set_full_gc();
  void reset_full_gc();
  bool is_full_gc();

  bool is_conc_gc_requested();
  void reset_conc_gc_requested();

  void handle_counters_update();
  void trigger_counters_update();
  void handle_force_counters_update();
  void set_forced_counters_update(bool value);

  char* name() const { return (char*)"ShenandoahConcurrentThread";}
  void start();

  void prepare_for_graceful_shutdown();
  bool in_graceful_shutdown();
};

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHCONCURRENTTHREAD_HPP
