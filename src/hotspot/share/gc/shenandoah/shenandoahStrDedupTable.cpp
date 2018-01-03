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
#include "classfile/javaClasses.inline.hpp"
#include "gc/shenandoah/shenandoahHeap.hpp"
#include "gc/shenandoah/shenandoahHeap.inline.hpp"
#include "gc/shenandoah/shenandoahStrDedupTable.hpp"
#include "memory/allocation.hpp"
#include "runtime/atomic.hpp"
#include "runtime/safepoint.hpp"


const size_t  ShenandoahStrDedupTable::_min_size = (1 << 10);   // 1024
const size_t  ShenandoahStrDedupTable::_max_size = (1 << 24);   // 16777216
const double  ShenandoahStrDedupTable::_grow_load_factor = 2.0; // Grow table at 200% load
const double  ShenandoahStrDedupTable::_shrink_load_factor = _grow_load_factor / 3.0; // Shrink table at 67% load
const double  ShenandoahStrDedupTable::_max_cache_factor = 0.1; // Cache a maximum of 10% of the table size
const uintx   ShenandoahStrDedupTable::_rehash_multiple = 60;   // Hash bucket has 60 times more collisions than expected
const uintx   ShenandoahStrDedupTable::_rehash_threshold = (uintx)(_rehash_multiple * _grow_load_factor);


bool ShenandoahStrDedupEntry::cas_set_next(ShenandoahStrDedupEntry* next) {
  return Atomic::replace_if_null(next, &_next);
}

ShenandoahStrDedupTable::ShenandoahStrDedupTable(size_t size, jint hash_seed)  :
  _size(size), _hash_seed(hash_seed), _entries(0), _claimed(0), _partition_size(0),
  _rehash_needed(false), _shrink_threshold((uintx)(size * _shrink_load_factor)),
  _grow_threshold((uintx)(size * _grow_load_factor))
{
  assert(size >= _min_size && size <= _max_size, "Invalid table size");
  _buckets = NEW_C_HEAP_ARRAY(ShenandoahStrDedupEntry* volatile, size, mtGC);
  for (size_t index = 0; index < size; index ++) {
    _buckets[index] = NULL;
  }
}

ShenandoahStrDedupTable::~ShenandoahStrDedupTable() {
  for (size_t index = 0; index < size(); index ++) {
    ShenandoahStrDedupEntry* volatile head = bucket(index);
    ShenandoahStrDedupEntry* volatile tmp;
    while (head != NULL) {
      tmp = head;
      head = head->next();
      release_entry(tmp);
    }
  }
}

typeArrayOop ShenandoahStrDedupTable::lookup_or_add(typeArrayOop value, bool latin1, unsigned int hash, uintx& count) {
  ShenandoahStrDedupEntry* volatile* head_addr = bucket_addr(hash_to_index(hash));
  count = 0;
  ShenandoahStrDedupEntry* new_entry = NULL;
  if (*head_addr == NULL) {
    new_entry = allocate_entry(value, latin1, hash);
    if (Atomic::replace_if_null(new_entry, head_addr)) {
      Atomic::inc(&_entries);
      return value;
    }
  }

  ShenandoahStrDedupEntry* volatile head = *head_addr;
  assert(head != NULL, "Should not be null");

  while (head != NULL) {
    if (head->equals(value, latin1, hash)) {
      if (new_entry != NULL) release_entry(new_entry);
      return head->obj();
    } else if (head->next() == NULL) {
      if (new_entry == NULL) new_entry = allocate_entry(value, latin1, hash);
      if (head->cas_set_next(new_entry)) {
        Atomic::inc(&_entries);
        return value;
      }
    }

    count ++;
    head = head->next();
    assert(head != NULL, "Should not be null");
  }

  // Should have found existing one or added new one
  ShouldNotReachHere();
  return NULL;
}

void ShenandoahStrDedupTable::add(ShenandoahStrDedupEntry* entry) {
  assert(SafepointSynchronize::is_at_safepoint(), "Only at a safepoint");
  assert(!use_java_hash(), "Only used when rehashing the table");
  unsigned int hash = alt_hash_code(entry->obj(), entry->latin1());
  entry->set_hash(hash);

  ShenandoahStrDedupEntry* volatile* head_addr = bucket_addr(hash_to_index(hash));
  if (*head_addr == NULL) {
    if (Atomic::replace_if_null(entry, head_addr)) {
      return;
    }
  }

  ShenandoahStrDedupEntry* volatile head = *head_addr;
  assert(head != NULL, "Should not be null");

  while (head != NULL) {
    if (head->next() == NULL && (head->cas_set_next(entry))) {
      return;
    }

    head = head->next();
    // Some one beats us
    assert(head != NULL, "Should not be null");
  }
}

bool ShenandoahStrDedupTable::deduplicate(oop java_string) {
  assert(java_lang_String::is_instance(java_string), "Must be a string");
  NoSafepointVerifier nsv;

  typeArrayOop value = java_lang_String::value(java_string);
  if (value == NULL) {
    return false;
  }

  bool latin1 = java_lang_String::is_latin1(java_string);
  unsigned int hash = hash_code(java_string, value, latin1);

  uintx count = 0;
  typeArrayOop existing_value = lookup_or_add(value, latin1, hash, count);
  assert(existing_value != NULL, "Must have found or added");
  if (count > _rehash_threshold) {
    _rehash_needed = true;
  }

  if (oopDesc::equals(existing_value, value)) {
    return false;
  }

  // Enqueue the reference to make sure it is kept alive. Concurrent mark might
  // otherwise declare it dead if there are no other strong references to this object.
  oopDesc::bs()->keep_alive_barrier(existing_value);

  // Existing value found, deduplicate string
  java_lang_String::set_value(java_string, typeArrayOop(existing_value));
  return true;
}


void ShenandoahStrDedupTable::clear_claimed() {
  _claimed = 0;
  _partition_size = size() / (ShenandoahHeap::heap()->max_workers() * 4);
  _partition_size = MAX2(_partition_size, size_t(1));
}

size_t ShenandoahStrDedupTable::claim() {
  return Atomic::add(_partition_size, &_claimed) - _partition_size;
}

void ShenandoahStrDedupTable::parallel_oops_do(OopClosure* cl) {
  assert(SafepointSynchronize::is_at_safepoint(), "Must be at a safepoint");

  size_t index;
  size_t end_index;
  do {
    index = claim();
    end_index = MIN2(index + partition_size(), size());

    for (; index < end_index; index ++) {
      ShenandoahStrDedupEntry* volatile p = bucket(index);
      while (p != NULL) {
        p->do_oop(cl);
        p = p->next();
      }
    }
  } while (index < size());
}

ShenandoahStrDedupEntry* ShenandoahStrDedupTable::allocate_entry(typeArrayOop value, bool latin1, unsigned int hash) {
  ShenandoahStrDedupEntry* entry = new ShenandoahStrDedupEntry();
  entry->set_hash(hash);
  entry->set_latin1(latin1);
  entry->set_obj(value);
  return entry;
}

void ShenandoahStrDedupTable::release_entry(ShenandoahStrDedupEntry* entry) {
  assert(entry != NULL, "null entry");
  delete entry;
}

unsigned int ShenandoahStrDedupTable::hash_code(oop java_string, typeArrayOop value, bool latin1) {
  if (use_java_hash()) {
    unsigned int hash = java_lang_String::hash(java_string);
    if (hash == 0) {
      hash = java_hash_code(value, latin1);
      java_lang_String::set_hash(java_string, hash);
    }
    return hash;
  } else {
    return alt_hash_code(value, latin1);
  }
}

unsigned int ShenandoahStrDedupTable::java_hash_code(typeArrayOop value, bool latin1) {
  assert(use_java_hash(), "Must use java hash code");
  int length = value->length();
  if (latin1) {
    const jbyte* data = (jbyte*)value->base(T_BYTE);
    return java_lang_String::hash_code(data, length);
  } else {
    length /= sizeof(jchar) / sizeof(jbyte);
    const jchar* data = (jchar*)value->base(T_CHAR);
    return java_lang_String::hash_code(data, length);
  }
}

unsigned int ShenandoahStrDedupTable::alt_hash_code(typeArrayOop value, bool latin1) {
  assert(hash_seed() != 0, "Must have hash seed");
  int length = value->length();
  if (latin1) {
    const jbyte* data = (jbyte*)value->base(T_BYTE);
    return AltHashing::murmur3_32(hash_seed(), data, length);
  } else {
    length /= sizeof(jchar) / sizeof(jbyte);
    const jchar* data = (jchar*)value->base(T_CHAR);
    return AltHashing::murmur3_32(hash_seed(), data, length);
  }
}

void ShenandoahStrDedupTable::print_statistics(outputStream* out) const {
  out->print_cr("ShenandoahStrDedupTable: buckets: " SIZE_FORMAT " entries: " SIZE_FORMAT,
    size(), _entries);
}

#ifdef ASSERT
void ShenandoahStrDedupTable::verify() {
  assert(SafepointSynchronize::is_at_safepoint(), "Must be at a safepoint");
  assert(Thread::current() == VMThread::vm_thread(), "only by vm thread");
  ShenandoahHeap* heap = ShenandoahHeap::heap();

  size_t num_entries = 0;

  for (size_t index = 0; index < size(); index ++) {
    ShenandoahStrDedupEntry* volatile head = bucket(index);
    while (head != NULL) {
      assert(heap->is_marked_next(head->obj()), "Must be marked");

      if (use_java_hash()) {
        assert(head->hash() == java_hash_code(head->obj(), head->latin1()), "Wrong hash code");
      } else {
        assert(head->hash() == alt_hash_code(head->obj(), head->latin1()), "Wrong alt hash code");
      }

      assert(index == hash_to_index(head->hash()), "Wrong bucket");
      num_entries ++;
      head = head->next();
    }
  }
  assert(num_entries == _entries, "The number of entries does not match");
}

#endif

ShenandoahStrDedupTableCleanupTask::ShenandoahStrDedupTableCleanupTask() :
  _heap(ShenandoahHeap::heap()) {
}

bool ShenandoahStrDedupTableCleanupTask::is_alive(oop obj) const {
  return _heap->is_marked_next(obj);
}

ShenandoahStrDedupTableUnlinkTask::ShenandoahStrDedupTableUnlinkTask(ShenandoahStrDedupTable* const table) :
  _table(table) {
  log_debug(gc, stringdedup)("Cleanup StringDedup table");
  table->clear_claimed();
}

void ShenandoahStrDedupTableUnlinkTask::do_parallel_cleanup() {
  ShenandoahStrDedupTable* const table = _table;
  size_t partition = table->partition_size();
  size_t removed = 0;
  size_t table_end = table->size();

  size_t index;
  size_t end_index;
  do {
    index = table->claim();
    end_index = MIN2(index + partition, table_end);
    for (; index < end_index; index ++) {
      ShenandoahStrDedupEntry* volatile* head_addr = table->bucket_addr(index);
      ShenandoahStrDedupEntry* volatile head;
      while (*head_addr != NULL) {
        head = *head_addr;
        if (!is_alive(head->obj())) {
          *head_addr = head->next();
          table->release_entry(head);
          removed ++;
        } else {
          head_addr = head->next_addr();
        }
      }
    }
  } while (index < table_end);

  Atomic::sub(removed, &table->_entries);
}

ShenandoahStrDedupTableRemapTask::ShenandoahStrDedupTableRemapTask(ShenandoahStrDedupTable* const src,
                                                                   ShenandoahStrDedupTable* const dest) :
  _src_table(src), _dest_table(dest) {
  src->clear_claimed();
}

ShenandoahStrDedupTableRehashTask::ShenandoahStrDedupTableRehashTask(
  ShenandoahStrDedupTable* const src, ShenandoahStrDedupTable* const dest) :
  ShenandoahStrDedupTableRemapTask(src, dest) {
  log_debug(gc, stringdedup)("Rehash StringDedup table");
}

void ShenandoahStrDedupTableRehashTask::do_parallel_cleanup() {
  size_t partition = src_table()->partition_size();

  size_t added = 0;
  size_t table_end = src_table()->size();
  size_t index;
  size_t end_index;
  do {
    index = src_table()->claim();
    end_index = MIN2(index + partition, table_end);
    for (; index < end_index; index ++) {
      ShenandoahStrDedupEntry* volatile * head_addr = src_table()->bucket_addr(index);
      ShenandoahStrDedupEntry* volatile head = *head_addr;
      *head_addr = NULL;

      ShenandoahStrDedupEntry* tmp;
      while(head != NULL) {
        tmp = head;
        head = head->next();
        tmp->set_next(NULL);
        if (is_alive(tmp->obj())) {
          dest_table()->add(tmp);
          added ++;
        } else {
          src_table()->release_entry(tmp);
        }
      }
    }
  } while (index < table_end);

  Atomic::add(added, &dest_table()->_entries);
}


ShenandoahStrDedupShrinkTableTask::ShenandoahStrDedupShrinkTableTask(
  ShenandoahStrDedupTable* const src, ShenandoahStrDedupTable* const dest) :
  ShenandoahStrDedupTableRemapTask(src, dest) {
  assert(is_power_of_2(src->size()), "Source table size must be a power of 2");
  assert(is_power_of_2(dest->size()), "Destination table size must be a power of 2");
  assert(src->size() / dest->size() == 2, "Shrink in half");
  log_debug(gc, stringdedup)("Shrink StringDedup table");
}

void ShenandoahStrDedupShrinkTableTask::do_parallel_cleanup() {
  size_t partition = src_table()->partition_size();
  size_t transferred = 0;

  size_t half_size = src_table()->size() / 2;
  // Only scan first half of table.
  // To shrink the table in half, we merge buckets at index and (index + half_size)
  size_t table_end = src_table()->size() / 2;

  size_t index;
  size_t end_index;
  do {
    index = src_table()->claim();
    end_index = MIN2(index + partition, table_end);
    for (; index < end_index; index ++) {
      ShenandoahStrDedupEntry* volatile * src_head_addr = src_table()->bucket_addr(index);
      ShenandoahStrDedupEntry* volatile * dest_head_addr = dest_table()->bucket_addr(index);
      ShenandoahStrDedupEntry* volatile   src_head = *src_head_addr;
      *src_head_addr = NULL;
      // transfer entries at index
      transferred += transfer_bucket(src_head, dest_head_addr);

      // transfer entries at index + half_size
      src_head_addr = src_table()->bucket_addr(index + half_size);
      src_head = *src_head_addr;
      *src_head_addr = NULL;
      transferred += transfer_bucket(src_head, dest_head_addr);
    }
  } while (index < table_end);

  Atomic::add(transferred, &dest_table()->_entries);
}


size_t ShenandoahStrDedupShrinkTableTask::transfer_bucket(ShenandoahStrDedupEntry* volatile src,
  ShenandoahStrDedupEntry* volatile * dest) {
  ShenandoahStrDedupEntry* tmp;
  size_t transferred = 0;

  while (src != NULL) {
    tmp = src;
    src = src->next();
    tmp->set_next(NULL);
    if (is_alive(tmp->obj())) {
      if (*dest != NULL) {
        tmp->set_next(*dest);
      }
      *dest = tmp;
      transferred ++;
    } else {
      src_table()->release_entry(tmp);
    }
  }

  return transferred;
}

ShenandoahStrDedupExpandTableTask::ShenandoahStrDedupExpandTableTask(
  ShenandoahStrDedupTable* const src, ShenandoahStrDedupTable* const dest) :
  ShenandoahStrDedupTableRemapTask(src, dest) {
  assert(is_power_of_2(src->size()), "Source table size must be a power of 2");
  assert(is_power_of_2(dest->size()), "Destination table size must be a power of 2");
  assert(dest->size() == 2 * src->size(), "Double the size");

  log_debug(gc, stringdedup)("Expand StringDedup table");

  int n = exact_log2_long(src->size());
  _bit_mask = nth_bit(n);
}

void ShenandoahStrDedupExpandTableTask::do_parallel_cleanup() {
  size_t partition = src_table()->partition_size();
  size_t table_end = src_table()->size();

  size_t transferred = 0;
  size_t index;
  size_t end_index;
  do {
    index = src_table()->claim();
    end_index = MIN2(index + partition, table_end);
    for (; index < end_index; index ++) {
      // split current source bucket into bucket[index] and bucket[index + half_size]
      // in destination table
      ShenandoahStrDedupEntry* volatile * src_head_addr = src_table()->bucket_addr(index);
      ShenandoahStrDedupEntry* volatile   src_head = *src_head_addr;
      ShenandoahStrDedupEntry* volatile * dest_low_addr =  dest_table()->bucket_addr(index);
      ShenandoahStrDedupEntry* volatile * dest_high_addr = dest_table()->bucket_addr(index + src_table()->size());
      *src_head_addr = NULL;

      transferred += split_bucket(src_head, dest_low_addr, dest_high_addr);
    }
  } while (index < table_end);
  Atomic::add(transferred, &dest_table()->_entries);
}

size_t ShenandoahStrDedupExpandTableTask::split_bucket(ShenandoahStrDedupEntry* volatile src,
    ShenandoahStrDedupEntry* volatile * dest_low,
    ShenandoahStrDedupEntry* volatile * dest_high) {
  size_t transferred = 0;

  ShenandoahStrDedupEntry* volatile tmp;
  ShenandoahStrDedupEntry* volatile * target;
  while (src != NULL) {
    tmp = src;
    src = src->next();

    if (is_alive(tmp->obj())) {
      tmp->set_next(NULL);
      unsigned int hash = tmp->hash();
      if ((hash & _bit_mask) == 0) {
        target = dest_low;
      } else {
        target = dest_high;
      }

      if (*target != NULL) {
        tmp->set_next(*target);
      }

      *target = tmp;
      transferred ++;
    } else {
      src_table()->release_entry(tmp);
    }
  }
  return transferred;
}
