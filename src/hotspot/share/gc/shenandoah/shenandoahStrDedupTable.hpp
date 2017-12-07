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

#ifndef SHARE_VM_GC_SHENANDOAHSTRDEDUPTABLE_HPP
#define SHARE_VM_GC_SHENANDOAHSTRDEDUPTABLE_HPP

#include "utilities/ostream.hpp"

class ShenandoahStrDedupEntry : public CHeapObj<mtGC> {
private:

  ShenandoahStrDedupEntry* volatile    _next;
  unsigned int                         _hash;
  bool                                 _latin1;
  typeArrayOop                         _obj;

public:
  ShenandoahStrDedupEntry() : _next(NULL), _hash(0), _latin1(false), _obj(NULL) {
  }

  ShenandoahStrDedupEntry* volatile next() {
    return _next;
  }

  ShenandoahStrDedupEntry* volatile* next_addr() {
    return &_next;
  }

  void set_next(ShenandoahStrDedupEntry* next) {
    _next = static_cast<ShenandoahStrDedupEntry* volatile>(next);
  }

  bool cas_set_next(ShenandoahStrDedupEntry* next);

  unsigned int hash() const {
    return _hash;
  }

  void set_hash(unsigned int hash) {
    _hash = hash;
  }

  bool latin1() const {
    return _latin1;
  }

  void set_latin1(bool latin1) {
    _latin1 = latin1;
  }

  typeArrayOop obj() const {
    return _obj;
  }

  typeArrayOop* obj_addr() {
    return &_obj;
  }

  void set_obj(typeArrayOop obj) {
    _obj = obj;
  }


  bool equals(typeArrayOop value, bool latin1, unsigned int hash) const {
    return (hash == this->hash() &&
            latin1 == this->latin1() &&
            equals(value, obj()));
  }

  void do_oop(OopClosure* cl) {
    oop* p = (oop*)obj_addr();
    cl->do_oop(p);
  }

private:
  static bool equals(typeArrayOop value1, typeArrayOop value2) {
    return (oopDesc::equals(value1, value2) ||
            (value1->length() == value2->length() &&
            (!memcmp(value1->base(T_BYTE),
                     value2->base(T_BYTE),
                    value1->length() * sizeof(jbyte)))));
  }
};

/* ShenandoahStringDedupTable:
 *  - Lookup and add are lock free
 *  - Cleanup, resize and rehash are at safepoints
 */
class ShenandoahStrDedupTable : public CHeapObj<mtGC> {
  friend class ShenandoahStrDedupTableUnlinkTask;
  friend class ShenandoahStrDedupTableRehashTask;
  friend class ShenandoahStrDedupShrinkTableTask;
  friend class ShenandoahStrDedupExpandTableTask;

private:
  ShenandoahStrDedupEntry* volatile *    _buckets;
  size_t                                 _size;
  volatile size_t                        _entries;

  uintx                                  _shrink_threshold;
  uintx                                  _grow_threshold;
  bool                                   _rehash_needed;

  // The hash seed also dictates which hash function to use. A
  // zero hash seed means we will use the Java compatible hash
  // function (which doesn't use a seed), and a non-zero hash
  // seed means we use the murmur3 hash function.
  jint                            _hash_seed;

  // Constants governing table resize/rehash/cache.
  static const size_t             _min_size;
  static const size_t             _max_size;
  static const double             _grow_load_factor;
  static const double             _shrink_load_factor;
  static const uintx              _rehash_multiple;
  static const uintx              _rehash_threshold;
  static const double             _max_cache_factor;

  volatile size_t _claimed;
  size_t          _partition_size;

public:
  ShenandoahStrDedupTable(size_t size = _min_size, jint hash_seed = 0);
  ~ShenandoahStrDedupTable();

  jint hash_seed() const    { return _hash_seed; }
  size_t size()    const    { return _size; }
  bool need_rehash() const  { return _rehash_needed; }
  bool need_expand() const  { return _entries >= _grow_threshold && size() < max_size(); }
  bool need_shrink() const  { return _entries <= _shrink_threshold && size() > min_size(); }


  // parallel scanning the table
  void clear_claimed();
  size_t claim();
  void parallel_oops_do(OopClosure* cl);

  bool deduplicate(oop java_string);

  // Returns an existing character array in the table, or inserts a new
  // table entry if no matching character array exists.
  typeArrayOop lookup_or_add(typeArrayOop value, bool latin1, unsigned int hash, uintx& count);


  void print_statistics(outputStream* out) const;

  static size_t min_size() { return _min_size; }
  static size_t max_size() { return _max_size; }


  void verify() PRODUCT_RETURN;
private:
  inline bool use_java_hash() {
    return _hash_seed == 0;
  }

  // Returns the hash bucket index for the given hash code.
  size_t hash_to_index(unsigned int hash) {
    return (size_t)hash & (size() - 1);
  }

  ShenandoahStrDedupEntry* volatile * bucket_addr(size_t index) const {
    assert(index < size(), "Index out of bound");
    return &_buckets[index];
  }

  // Returns the hash bucket at the given index.
  ShenandoahStrDedupEntry* volatile bucket(size_t index) const {
    assert(index < size(), "Index out of bound");
    return _buckets[index];
  }

  size_t partition_size() const { return _partition_size; }

  ShenandoahStrDedupEntry* allocate_entry(typeArrayOop value, bool latin1, unsigned int hash);
  void release_entry(ShenandoahStrDedupEntry* entry);


  unsigned int hash_code(oop java_string, typeArrayOop value, bool latin1);
  unsigned int java_hash_code(typeArrayOop value, bool latin1);
  unsigned int alt_hash_code(typeArrayOop value, bool latin1);

  // Adds a new table entry to the given hash bucket.
  void add(ShenandoahStrDedupEntry* entry);

  // Clean up a bucket, return number of entries removed
  size_t cleanup_bucket(size_t index);
};

class ShenandoahHeap;

class ShenandoahStrDedupTableCleanupTask : public CHeapObj<mtGC> {
private:
  ShenandoahHeap* _heap;

public:
  ShenandoahStrDedupTableCleanupTask();
  virtual void do_parallel_cleanup() = 0;

protected:
  bool is_alive(oop obj) const;
};

// Cleanup current string dedup table, remove all dead entries
class ShenandoahStrDedupTableUnlinkTask : public ShenandoahStrDedupTableCleanupTask {
private:
  ShenandoahStrDedupTable* const  _table;

public:
  ShenandoahStrDedupTableUnlinkTask(ShenandoahStrDedupTable* const table);
  void do_parallel_cleanup();
};

// The task transfers live entries from source table to destination table
class ShenandoahStrDedupTableRemapTask : public ShenandoahStrDedupTableCleanupTask {
protected:
  ShenandoahStrDedupTable* const  _src_table;
  ShenandoahStrDedupTable* const  _dest_table;

public:
  ShenandoahStrDedupTableRemapTask(ShenandoahStrDedupTable* const src,
                                   ShenandoahStrDedupTable* const dest);
protected:
  ShenandoahStrDedupTable* const src_table()  const { return _src_table; }
  ShenandoahStrDedupTable* const dest_table() const { return _dest_table; }
};


// The task rehashes live entries from source table to destination table.
// Source and destination tables are not necessary the same size.
class ShenandoahStrDedupTableRehashTask : public ShenandoahStrDedupTableRemapTask {
public:
  ShenandoahStrDedupTableRehashTask(ShenandoahStrDedupTable* const src,
                                    ShenandoahStrDedupTable* const dest);
  void do_parallel_cleanup();
};

/* The task remaps live entries from source table into destination table of
 * the half size.
 * Hash function should *not* be changed during shrinking of the table,
 * so we can merge buckets from source table into destination table.
 *  bucket [index ] and bucket [index + half_table_size] -> bucket [index]
 */
class ShenandoahStrDedupShrinkTableTask : public ShenandoahStrDedupTableRemapTask {
public:
  ShenandoahStrDedupShrinkTableTask(ShenandoahStrDedupTable* const src,
                                    ShenandoahStrDedupTable* const dest);
  void do_parallel_cleanup();

protected:
  size_t transfer_bucket(ShenandoahStrDedupEntry* volatile src,
                         ShenandoahStrDedupEntry* volatile * dest);
};

/* The task remaps live entries from source table into destination table of
 * twice the size.
 * Hash function should *not* be changed during shrinking of the table,
 * so we can split buckets from source table into destination table.
 *  bucket [index ] -> bucket [index] or bucket [index + half_table_size]
 */
class ShenandoahStrDedupExpandTableTask : public ShenandoahStrDedupTableRemapTask {
private:
  int    _bit_mask;

public:
  ShenandoahStrDedupExpandTableTask(ShenandoahStrDedupTable* const src,
                                    ShenandoahStrDedupTable* const dest);
  void do_parallel_cleanup();

protected:
  size_t split_bucket(ShenandoahStrDedupEntry* volatile src,
    ShenandoahStrDedupEntry* volatile * dest_low,
    ShenandoahStrDedupEntry* volatile * dest_high);
};

#endif // SHARE_VM_GC_SHENANDOAHSTRDEDUPTABLE_HPP
