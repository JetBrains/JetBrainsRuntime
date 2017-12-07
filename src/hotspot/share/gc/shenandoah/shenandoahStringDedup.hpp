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

#ifndef SHARE_VM_GC_SHENANDOAH_SHENANDOAHSTRINGDEDUP_HPP
#define SHARE_VM_GC_SHENANDOAH_SHENANDOAHSTRINGDEDUP_HPP


#include "classfile/javaClasses.inline.hpp"
#include "memory/iterator.hpp"
#include "utilities/ostream.hpp"

#define STRDEDUP_TIME_FORMAT_MS         "%.3fms"
#define STRDEDUP_TIME_PARAM_MS(time)    ((time) * MILLIUNITS)

class ShenandoahStrDedupStats VALUE_OBJ_CLASS_SPEC {
private:
  // Counters
  volatile size_t  _inspected;
  volatile size_t  _deduped;
  volatile size_t  _skipped;
  volatile size_t  _known;

  size_t  _idle;
  size_t  _exec;
  size_t  _block;

  // Time spent by the deduplication thread in different phases
  double _start_concurrent;
  double _end_concurrent;
  double _start_phase;
  double _idle_elapsed;
  double _exec_elapsed;
  double _block_elapsed;

  size_t _table_expanded_count;
  size_t _table_shrinked_count;
  size_t _table_rehashed_count;

public:
  ShenandoahStrDedupStats();

  void inc_inspected() { assert_thread(); _inspected ++; }
  void inc_skipped()   { assert_thread(); _skipped ++; }
  void inc_known()     { assert_thread(); _known ++; }
  void inc_deduped() {
    assert_thread();
    _deduped ++;
  }

  void atomic_inc_inspected(size_t count);
  void atomic_inc_deduped(size_t count);
  void atomic_inc_skipped(size_t count);
  void atomic_inc_known(size_t count);

  void mark_idle();
  void mark_exec();
  void mark_block();
  void mark_unblock();
  void mark_done();

  void inc_table_expanded();
  void inc_table_shrinked();
  void inc_table_rehashed();

  void update(const ShenandoahStrDedupStats& sts);

  void print_statistics(outputStream* out) const;

private:
  void assert_thread() PRODUCT_RETURN;
};

class ShenandoahStrDedupQueue;
class ShenandoahStrDedupQueueSet;
class ShenandoahStrDedupTable;
class ShenandoahStrDedupThread;

class ShenandoahStringDedup : AllStatic {
  friend class ShenandoahStrDedupStats;

private:
  static ShenandoahStrDedupQueueSet* _queues;
  static ShenandoahStrDedupTable*    _table;
  static ShenandoahStrDedupThread*   _thread;
  static bool                        _enabled;
  static ShenandoahStrDedupStats     _stats;

public:
  // Initialize string deduplication.
  static void initialize();

  static bool is_enabled() { return _enabled; }

  // Enqueue a string to worker's local string dedup queue
  static void enqueue_candidate(oop java_string, ShenandoahStrDedupQueue* q);

  // Get string dedup queue associated to specific worker id
  static ShenandoahStrDedupQueue* queue(uint worker_id);

  // Deduplicate a string, the call is lock-free
  static bool deduplicate(oop java_string, bool update_counter = true);

  // Parallel scan string dedup queues/table
  static void clear_claimed();
  static void parallel_oops_do(OopClosure* cl);
  static void oops_do(OopClosure* cl, uint worker_id);

  static void threads_do(ThreadClosure* tc);

  static void print_worker_threads_on(outputStream* out) { }

  static ShenandoahStrDedupStats& dedup_stats() { return _stats; }

  // Parallel cleanup string dedup queues/table
  static void parallel_cleanup();

  static void stop();

  static inline bool is_candidate(oop obj) {
    return java_lang_String::is_instance_inlined(obj) &&
           java_lang_String::value(obj) != NULL;
  }

  static void print_statistics(outputStream* out);
};

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHSTRINGDEDUP_HPP
