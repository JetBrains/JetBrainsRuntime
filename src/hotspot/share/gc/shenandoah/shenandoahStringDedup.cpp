/*
 * Copyright (c) 2017, 2018, Red Hat, Inc. and/or its affiliates.
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

#include "classfile/altHashing.hpp"
#include "gc/shared/workgroup.hpp"
#include "gc/shenandoah/brooksPointer.hpp"
#include "gc/shenandoah/shenandoahCollectionSet.hpp"
#include "gc/shenandoah/shenandoahCollectionSet.inline.hpp"
#include "gc/shenandoah/shenandoahHeap.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahStrDedupQueue.inline.hpp"
#include "gc/shenandoah/shenandoahStrDedupTable.hpp"
#include "gc/shenandoah/shenandoahStrDedupThread.hpp"
#include "gc/shenandoah/shenandoahStringDedup.hpp"
#include "gc/shenandoah/shenandoahUtils.hpp"
#include "runtime/os.hpp"

ShenandoahStrDedupQueueSet* ShenandoahStringDedup::_queues = NULL;
ShenandoahStrDedupTable*    ShenandoahStringDedup::_table  = NULL;
ShenandoahStrDedupThread*   ShenandoahStringDedup::_thread = NULL;
ShenandoahStrDedupStats     ShenandoahStringDedup::_stats;
bool                        ShenandoahStringDedup::_enabled = false;

void ShenandoahStringDedup::initialize() {
  if (UseStringDeduplication) {
    _queues = new ShenandoahStrDedupQueueSet(ShenandoahHeap::heap()->max_workers());
    _table = new ShenandoahStrDedupTable();
    _thread = new ShenandoahStrDedupThread(_queues);
    _enabled = true;
  }
}

/* Enqueue candidates for deduplication.
 * The method should only be called by GC worker threads, during concurrent marking phase.
 */
void ShenandoahStringDedup::enqueue_candidate(oop java_string, ShenandoahStrDedupQueue* q) {
  assert(Thread::current()->is_Worker_thread(), "Only be GC worker thread");

  if (java_string->age() <= StringDeduplicationAgeThreshold) {
    const markOop mark = java_string->mark();

    // Having/had displaced header, too risk to deal with them, skip
    if (mark == markOopDesc::INFLATING() || mark->has_displaced_mark_helper()) {
      return;
    }

    // Increase string age and enqueue it when it rearches age threshold
    markOop new_mark = mark->incr_age();
    if (mark == java_string->cas_set_mark(new_mark, mark)) {
      if (mark->age() == StringDeduplicationAgeThreshold) {
        q->push(java_string);
      }
    }
  }
}

// Deduplicate a string, return true if it is deduplicated.
bool ShenandoahStringDedup::deduplicate(oop java_string, bool update_counter) {
  assert(is_candidate(java_string), "Not a candidate");
  assert(_table != NULL, "Shenandoah Dedup table not initialized");
  bool deduped = _table->deduplicate(java_string);

  if (update_counter) {
    dedup_stats().atomic_inc_inspected(1);
    if (deduped) {
      dedup_stats().atomic_inc_skipped(1);
    } else {
      dedup_stats().atomic_inc_known(1);
    }
  }
  return deduped;
}

ShenandoahStrDedupQueue* ShenandoahStringDedup::queue(uint worker_id) {
  assert(_queues != NULL, "QueueSet not initialized");
  return _queues->queue_at(worker_id);
}

void ShenandoahStringDedup::threads_do(ThreadClosure* tc) {
  assert(_thread != NULL, "Shenandoah Dedup Thread not initialized");
  tc->do_thread(_thread);
}

void ShenandoahStringDedup::parallel_oops_do(OopClosure* cl) {
  _queues->parallel_oops_do(cl);
  _table->parallel_oops_do(cl);
  _thread->parallel_oops_do(cl);
}


void ShenandoahStringDedup::oops_do_slow(OopClosure* cl) {
  _queues->oops_do_slow(cl);
  _table->oops_do_slow(cl);
  _thread->oops_do_slow(cl);
}

class ShenandoahStrDedupCleanupTask : public AbstractGangTask {
private:
  ShenandoahStrDedupQueueSet* _queues;
  ShenandoahStrDedupThread*   _thread;
  ShenandoahStrDedupTable**   _table;
  ShenandoahStrDedupTable*    _dest_table;

  ShenandoahStrDedupTableCleanupTask* _dedup_table_cleanup_task;

public:
  ShenandoahStrDedupCleanupTask(ShenandoahStrDedupQueueSet* qset,
    ShenandoahStrDedupThread* thread, ShenandoahStrDedupTable** table)
  : AbstractGangTask("Shenandoah dedup cleanup task"),
    _queues(qset), _table(table), _thread(thread), _dest_table(NULL) {

    ShenandoahStrDedupTable* the_table = *table;
    bool rehash = the_table->need_rehash();
    size_t table_size = the_table->size();
    if (the_table->need_expand()) {
      table_size *= 2;
      table_size = MIN2(table_size, ShenandoahStrDedupTable::max_size());
    } else if (the_table->need_shrink()) {
      table_size /= 2;
      table_size = MAX2(table_size, ShenandoahStrDedupTable::min_size());
    }

    if (rehash) {
      _dest_table = new ShenandoahStrDedupTable(table_size, AltHashing::compute_seed());
      _dedup_table_cleanup_task = new ShenandoahStrDedupTableRehashTask(the_table, _dest_table);
      ShenandoahStringDedup::dedup_stats().inc_table_rehashed();
    } else if (the_table->need_expand()) {
      _dest_table = new ShenandoahStrDedupTable(table_size, the_table->hash_seed());
      _dedup_table_cleanup_task = new ShenandoahStrDedupExpandTableTask(the_table, _dest_table);
      ShenandoahStringDedup::dedup_stats().inc_table_expanded();
    } else if (the_table->need_shrink()) {
      _dest_table = new ShenandoahStrDedupTable(table_size, the_table->hash_seed());
      _dedup_table_cleanup_task = new ShenandoahStrDedupShrinkTableTask(the_table, _dest_table);
      ShenandoahStringDedup::dedup_stats().inc_table_shrinked();
    } else {
      _dedup_table_cleanup_task = new ShenandoahStrDedupTableUnlinkTask(the_table);
    }
  }

  ~ShenandoahStrDedupCleanupTask() {
    assert(_dedup_table_cleanup_task != NULL, "Should not be null");
    delete _dedup_table_cleanup_task;

    // Install new table
    if (_dest_table != NULL) {
      delete *_table;
      *_table = _dest_table;
    }

    (*_table)->verify();
  }

  void work(uint worker_id) {
    _queues->parallel_cleanup();
    _thread->parallel_cleanup();
    _dedup_table_cleanup_task->do_parallel_cleanup();
  }
};

void ShenandoahStringDedup::parallel_cleanup() {
  assert(SafepointSynchronize::is_at_safepoint(), "Must be at a safepoint");
  log_debug(gc, stringdedup)("String dedup cleanup");
  ShenandoahStringDedup::clear_claimed();
  ShenandoahStrDedupCleanupTask task(_queues, _thread, &_table);
  ShenandoahHeap::heap()->workers()->run_task(&task);
}

void ShenandoahStringDedup::stop() {
  assert(ShenandoahStringDedup::is_enabled(), "Must be enabled");
  assert(_thread != NULL, "Not dedup thread");
  _thread->stop();
}

void ShenandoahStringDedup::clear_claimed() {
  assert(is_enabled(), "Must be enabled");
  _queues->clear_claimed();
  _table->clear_claimed();
  _thread->clear_claimed();
}

void ShenandoahStringDedup::print_statistics(outputStream* out) {
  assert(is_enabled(), "Must be enabled");

  out->print_cr("Shenandoah String Dedup Statistics:");
  dedup_stats().print_statistics(out);
  _table->print_statistics(out);
}

ShenandoahStrDedupStats::ShenandoahStrDedupStats() :
  _inspected(0), _deduped(0), _skipped(0), _known(0), _idle(0), _exec(0), _block(0),
  _idle_elapsed(0), _exec_elapsed(0), _block_elapsed(0),
  _start_phase(0), _start_concurrent(0), _end_concurrent(0),
  _table_expanded_count(0), _table_shrinked_count(0), _table_rehashed_count(0) {
}

void ShenandoahStrDedupStats::atomic_inc_inspected(size_t count) {
  Atomic::add(count, &_inspected);
}

void ShenandoahStrDedupStats::atomic_inc_skipped(size_t count) {
  Atomic::add(count, &_skipped);
}

void ShenandoahStrDedupStats::atomic_inc_deduped(size_t count) {
  Atomic::add(count, &_deduped);
}

void ShenandoahStrDedupStats::atomic_inc_known(size_t count) {
  Atomic::add(count, &_known);
}

void ShenandoahStrDedupStats::mark_idle() {
  assert_thread();
  _start_phase = os::elapsedTime();
  _idle++;
}

void ShenandoahStrDedupStats::mark_exec() {
  assert_thread();
  double now = os::elapsedTime();
  _idle_elapsed = now - _start_phase;
  _start_phase = now;
  _start_concurrent = now;
  _exec++;
}

void ShenandoahStrDedupStats::mark_block() {
  assert_thread();
  double now = os::elapsedTime();
  _exec_elapsed += now - _start_phase;
  _start_phase = now;
  _block++;
}

void ShenandoahStrDedupStats::mark_unblock() {
  assert_thread();
  double now = os::elapsedTime();
  _block_elapsed += now - _start_phase;
  _start_phase = now;
}

void ShenandoahStrDedupStats::mark_done() {
  assert_thread();
  double now = os::elapsedTime();
  _exec_elapsed += now - _start_phase;
  _end_concurrent = now;
}

void ShenandoahStrDedupStats::update(const ShenandoahStrDedupStats& sts) {
  assert_thread();
  // Counters
  atomic_inc_inspected(sts._inspected);
  atomic_inc_deduped(sts._deduped);
  atomic_inc_skipped(sts._skipped);
  atomic_inc_known(sts._known);

  _idle      += sts._idle;
  _exec      += sts._exec;
  _block     += sts._block;

  // Time spent by the deduplication thread in different phases
  _idle_elapsed  += sts._idle_elapsed;
  _exec_elapsed  += sts._exec_elapsed;
  _block_elapsed += sts._block_elapsed;
}

void ShenandoahStrDedupStats::inc_table_expanded() {
  assert(SafepointSynchronize::is_at_safepoint(), "Must be at a safepoint");
  assert(Thread::current() == VMThread::vm_thread(), "Only by VM thread");
  _table_expanded_count ++;
}

void ShenandoahStrDedupStats::inc_table_shrinked() {
  assert(SafepointSynchronize::is_at_safepoint(), "Must be at a safepoint");
  assert(Thread::current() == VMThread::vm_thread(), "Only by VM thread");
  _table_shrinked_count ++;
}

void ShenandoahStrDedupStats::inc_table_rehashed() {
  assert(SafepointSynchronize::is_at_safepoint(), "Must be at a safepoint");
  assert(Thread::current() == VMThread::vm_thread(), "Only by VM thread");
  _table_rehashed_count ++;
}

void ShenandoahStrDedupStats::print_statistics(outputStream* out) const {
  out->print_cr("  Inspected: " SIZE_FORMAT_W(12), _inspected);
  out->print_cr("    Skipped: " SIZE_FORMAT_W(12), _skipped);
  out->print_cr("    Deduped: " SIZE_FORMAT_W(12), _deduped);
  out->print_cr("      Known: " SIZE_FORMAT_W(12), _known);
  out->cr();
  out->print_cr(" Idle: " STRDEDUP_TIME_FORMAT_MS " Exec: " STRDEDUP_TIME_FORMAT_MS " Block: " STRDEDUP_TIME_FORMAT_MS,
    STRDEDUP_TIME_PARAM_MS(_idle_elapsed), STRDEDUP_TIME_PARAM_MS(_exec_elapsed), STRDEDUP_TIME_PARAM_MS(_block_elapsed));
  if (_table_expanded_count != 0 || _table_shrinked_count != 0 || _table_rehashed_count != 0) {
    out->print_cr(" Table expanded: " SIZE_FORMAT " shrinked: " SIZE_FORMAT " rehashed: " SIZE_FORMAT,
      _table_expanded_count, _table_shrinked_count, _table_rehashed_count);
  }

  out->cr();
}

#ifdef ASSERT
void ShenandoahStrDedupStats::assert_thread() {
  assert(Thread::current() == ShenandoahStringDedup::_thread, "Can only be done by dedup thread");
}

#endif
