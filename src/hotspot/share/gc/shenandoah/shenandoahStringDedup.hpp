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
#include "gc/g1/g1StringDedup.hpp"

class ShenandoahStringDedup : public G1StringDedup {
public:
  // Initialize string deduplication.
  static void initialize();
  static void try_dedup(oop java_string);

  static void enqueue_from_safepoint(oop java_string, uint worker_id);

  static void parallel_full_gc_update_or_unlink();

  static void parallel_update_or_unlink();

  static void parallel_partial_update_or_unlink();

  static inline bool is_candidate(oop java_string) {
    assert(java_lang_String::is_instance_inlined(java_string), "Must be a String object");
    return (java_string->age() == StringDeduplicationAgeThreshold);
  }
};

#endif // SHARE_VM_GC_SHENANDOAH_SHENANDOAHSTRINGDEDUP_HPP
