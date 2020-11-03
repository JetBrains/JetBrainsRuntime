/*
 * Copyright (c) 2015, 2020, Oracle and/or its affiliates. All rights reserved.
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

#include "precompiled.hpp"
#include "classfile/symbolTable.hpp"
#include "classfile/stringTable.hpp"
#include "code/codeCache.hpp"
#include "memory/resourceArea.hpp"
#include "prims/resolvedMethodTable.hpp"
#include "logging/log.hpp"
#include "gc/shared/gcCause.hpp"
#include "gc/shared/gcTraceTime.hpp"
#include "gc/shared/gcTraceTime.inline.hpp"
#include "gc/shenandoah/parallelCleaning.hpp"

StringSymbolTableUnlinkTask::StringSymbolTableUnlinkTask(BoolObjectClosure* is_alive, bool process_strings, bool process_symbols) :
  AbstractGangTask("String/Symbol Unlinking"),
  _is_alive(is_alive),
  _par_state_string(StringTable::weak_storage()),
  _process_strings(process_strings), _strings_processed(0), _strings_removed(0),
  _process_symbols(process_symbols), _symbols_processed(0), _symbols_removed(0) {

  _initial_string_table_size = (int) StringTable::the_table()->table_size();
  _initial_symbol_table_size = SymbolTable::the_table()->table_size();
  if (process_symbols) {
    SymbolTable::clear_parallel_claimed_index();
  }
  if (process_strings) {
    StringTable::reset_dead_counter();
  }
}

StringSymbolTableUnlinkTask::~StringSymbolTableUnlinkTask() {
  guarantee(!_process_symbols || SymbolTable::parallel_claimed_index() >= _initial_symbol_table_size,
            "claim value %d after unlink less than initial symbol table size %d",
            SymbolTable::parallel_claimed_index(), _initial_symbol_table_size);

  log_info(gc, stringtable)(
      "Cleaned string and symbol table, "
      "strings: " SIZE_FORMAT " processed, " SIZE_FORMAT " removed, "
      "symbols: " SIZE_FORMAT " processed, " SIZE_FORMAT " removed",
      strings_processed(), strings_removed(),
      symbols_processed(), symbols_removed());

  if (_process_strings) {
    StringTable::finish_dead_counter();
  }
}

void StringSymbolTableUnlinkTask::work(uint worker_id) {
  int strings_processed = 0;
  int strings_removed = 0;
  int symbols_processed = 0;
  int symbols_removed = 0;
  if (_process_strings) {
    StringTable::possibly_parallel_unlink(&_par_state_string, _is_alive, &strings_processed, &strings_removed);
    Atomic::add(strings_processed, &_strings_processed);
    Atomic::add(strings_removed, &_strings_removed);
  }
  if (_process_symbols) {
    SymbolTable::possibly_parallel_unlink(&symbols_processed, &symbols_removed);
    Atomic::add(symbols_processed, &_symbols_processed);
    Atomic::add(symbols_removed, &_symbols_removed);
  }
}

size_t StringSymbolTableUnlinkTask::strings_processed() const { return (size_t)_strings_processed; }
size_t StringSymbolTableUnlinkTask::strings_removed()   const { return (size_t)_strings_removed; }

size_t StringSymbolTableUnlinkTask::symbols_processed() const { return (size_t)_symbols_processed; }
size_t StringSymbolTableUnlinkTask::symbols_removed()   const { return (size_t)_symbols_removed; }


Monitor* CodeCacheUnloadingTask::_lock = new Monitor(Mutex::leaf, "Code Cache Unload lock", false, Monitor::_safepoint_check_never);

CodeCacheUnloadingTask::CodeCacheUnloadingTask(uint num_workers, BoolObjectClosure* is_alive, bool unloading_occurred) :
  _is_alive(is_alive),
  _unloading_occurred(unloading_occurred),
  _num_workers(num_workers),
  _first_nmethod(NULL),
  _claimed_nmethod(NULL),
  _postponed_list(NULL),
  _num_entered_barrier(0)
{
  CompiledMethod::increase_unloading_clock();
  // Get first alive nmethod
  CompiledMethodIterator iter = CompiledMethodIterator();
  if(iter.next_alive()) {
    _first_nmethod = iter.method();
  }
  _claimed_nmethod = (volatile CompiledMethod*)_first_nmethod;
}

CodeCacheUnloadingTask::~CodeCacheUnloadingTask() {
  CodeCache::verify_clean_inline_caches();

  CodeCache::set_needs_cache_clean(false);
  guarantee(CodeCache::scavenge_root_nmethods() == NULL, "Must be");

  CodeCache::verify_icholder_relocations();
}

void CodeCacheUnloadingTask::add_to_postponed_list(CompiledMethod* nm) {
  CompiledMethod* old;
  do {
    old = (CompiledMethod*)_postponed_list;
    nm->set_unloading_next(old);
  } while ((CompiledMethod*)Atomic::cmpxchg(nm, &_postponed_list, old) != old);
}

void CodeCacheUnloadingTask::clean_nmethod(CompiledMethod* nm) {
  bool postponed = nm->do_unloading_parallel(_is_alive, _unloading_occurred);

  if (postponed) {
    // This nmethod referred to an nmethod that has not been cleaned/unloaded yet.
    add_to_postponed_list(nm);
  }

  // Mark that this thread has been cleaned/unloaded.
  // After this call, it will be safe to ask if this nmethod was unloaded or not.
  nm->set_unloading_clock(CompiledMethod::global_unloading_clock());
}

void CodeCacheUnloadingTask::clean_nmethod_postponed(CompiledMethod* nm) {
  nm->do_unloading_parallel_postponed();
}

void CodeCacheUnloadingTask::claim_nmethods(CompiledMethod** claimed_nmethods, int *num_claimed_nmethods) {
  CompiledMethod* first;
  CompiledMethodIterator last;

  do {
    *num_claimed_nmethods = 0;

    first = (CompiledMethod*)_claimed_nmethod;
    last = CompiledMethodIterator(first);

    if (first != NULL) {

      for (int i = 0; i < MaxClaimNmethods; i++) {
        if (!last.next_alive()) {
          break;
        }
        claimed_nmethods[i] = last.method();
        (*num_claimed_nmethods)++;
      }
    }

  } while ((CompiledMethod*)Atomic::cmpxchg(last.method(), &_claimed_nmethod, first) != first);
}

CompiledMethod* CodeCacheUnloadingTask::claim_postponed_nmethod() {
  CompiledMethod* claim;
  CompiledMethod* next;

  do {
    claim = (CompiledMethod*)_postponed_list;
    if (claim == NULL) {
      return NULL;
    }

    next = claim->unloading_next();

  } while ((CompiledMethod*)Atomic::cmpxchg(next, &_postponed_list, claim) != claim);

  return claim;
}

// Mark that we're done with the first pass of nmethod cleaning.
void CodeCacheUnloadingTask::barrier_mark(uint worker_id) {
  MonitorLockerEx ml(_lock, Mutex::_no_safepoint_check_flag);
  _num_entered_barrier++;
  if (_num_entered_barrier == _num_workers) {
    ml.notify_all();
  }
}

// See if we have to wait for the other workers to
// finish their first-pass nmethod cleaning work.
void CodeCacheUnloadingTask::barrier_wait(uint worker_id) {
  if (_num_entered_barrier < _num_workers) {
    MonitorLockerEx ml(_lock, Mutex::_no_safepoint_check_flag);
    while (_num_entered_barrier < _num_workers) {
      ml.wait(Mutex::_no_safepoint_check_flag, 0, false);
    }
  }
}

// Cleaning and unloading of nmethods. Some work has to be postponed
// to the second pass, when we know which nmethods survive.
void CodeCacheUnloadingTask::work_first_pass(uint worker_id) {
  // The first nmethods is claimed by the first worker.
  if (worker_id == 0 && _first_nmethod != NULL) {
    clean_nmethod(_first_nmethod);
    _first_nmethod = NULL;
  }

  int num_claimed_nmethods;
  CompiledMethod* claimed_nmethods[MaxClaimNmethods];

  while (true) {
    claim_nmethods(claimed_nmethods, &num_claimed_nmethods);

    if (num_claimed_nmethods == 0) {
      break;
    }

    for (int i = 0; i < num_claimed_nmethods; i++) {
      clean_nmethod(claimed_nmethods[i]);
    }
  }
}

void CodeCacheUnloadingTask::work_second_pass(uint worker_id) {
  CompiledMethod* nm;
  // Take care of postponed nmethods.
  while ((nm = claim_postponed_nmethod()) != NULL) {
    clean_nmethod_postponed(nm);
  }
}

KlassCleaningTask::KlassCleaningTask(BoolObjectClosure* is_alive) :
  _is_alive(is_alive),
  _clean_klass_tree_claimed(0),
  _klass_iterator() {
}

bool KlassCleaningTask::claim_clean_klass_tree_task() {
  if (_clean_klass_tree_claimed) {
    return false;
  }

  return Atomic::cmpxchg(1, &_clean_klass_tree_claimed, 0) == 0;
}

InstanceKlass* KlassCleaningTask::claim_next_klass() {
  Klass* klass;
  do {
    klass =_klass_iterator.next_klass();
  } while (klass != NULL && !klass->is_instance_klass());

  // this can be null so don't call InstanceKlass::cast
  return static_cast<InstanceKlass*>(klass);
}

void KlassCleaningTask::clean_klass(InstanceKlass* ik) {
  ik->clean_weak_instanceklass_links();
}

void KlassCleaningTask::work() {
  ResourceMark rm;

  // One worker will clean the subklass/sibling klass tree.
  if (claim_clean_klass_tree_task()) {
    Klass::clean_subklass_tree();
  }

  // All workers will help cleaning the classes,
  InstanceKlass* klass;
  while ((klass = claim_next_klass()) != NULL) {
    clean_klass(klass);
  }
}

bool ResolvedMethodCleaningTask::claim_resolved_method_task() {
  if (_resolved_method_task_claimed) {
    return false;
  }
  return Atomic::cmpxchg(1, &_resolved_method_task_claimed, 0) == 0;
}

// These aren't big, one thread can do it all.
void ResolvedMethodCleaningTask::work() {
  if (claim_resolved_method_task()) {
    ResolvedMethodTable::unlink();
  }
}

ParallelCleaningTask::ParallelCleaningTask(ShenandoahPhaseTimings::Phase phase,
                                           BoolObjectClosure* is_alive,
                                           bool process_strings,
                                           bool process_symbols,
                                           uint num_workers,
                                           bool unloading_occurred) :
  AbstractGangTask("Parallel Cleaning"),
  _string_symbol_task(is_alive, process_strings, process_symbols),
  _code_cache_task(num_workers, is_alive, unloading_occurred),
  _klass_cleaning_task(is_alive),
  _resolved_method_cleaning_task(is_alive),
  _phase(phase)
{
}

// The parallel work done by all worker threads.
void ParallelCleaningTask::work(uint worker_id) {
  {
    ShenandoahWorkerTimingsTracker x(_phase, ShenandoahPhaseTimings::CodeCacheRoots, worker_id);
    // Do first pass of code cache cleaning.
    _code_cache_task.work_first_pass(worker_id);

    // Let the threads mark that the first pass is done.
    _code_cache_task.barrier_mark(worker_id);
  }

  {
    ShenandoahWorkerTimingsTracker x(_phase, ShenandoahPhaseTimings::StringTableRoots, worker_id);
    // Clean the Strings and Symbols.
    _string_symbol_task.work(worker_id);
  }

  {
    ShenandoahWorkerTimingsTracker x(_phase, ShenandoahPhaseTimings::ResolvedMethodTableRoots, worker_id);
    // Clean unreferenced things in the ResolvedMethodTable
    _resolved_method_cleaning_task.work();
  }

  {
    ShenandoahWorkerTimingsTracker x(_phase, ShenandoahPhaseTimings::CodeCacheRootsCleaning, worker_id);
    // Wait for all workers to finish the first code cache cleaning pass.
    _code_cache_task.barrier_wait(worker_id);

    // Do the second code cache cleaning work, which realize on
    // the liveness information gathered during the first pass.
    _code_cache_task.work_second_pass(worker_id);
  }

  {
    ShenandoahWorkerTimingsTracker x(_phase, ShenandoahPhaseTimings::CLDGRoots, worker_id);
    // Clean all klasses that were not unloaded.
    _klass_cleaning_task.work();
  }
}
