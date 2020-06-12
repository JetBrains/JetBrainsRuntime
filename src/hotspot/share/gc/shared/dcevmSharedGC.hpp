/*
 * Copyright (c) 1997, 2018, Oracle and/or its affiliates. All rights reserved.
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

#ifndef SHARE_GC_DCEVM_SHARED_GC_HPP
#define SHARE_GC_DCEVM_SHARED_GC_HPP

#include "gc/shared/collectedHeap.hpp"
#include "gc/shared/genOopClosures.hpp"
#include "gc/shared/taskqueue.hpp"
#include "memory/iterator.hpp"
#include "oops/markOop.hpp"
#include "oops/oop.hpp"
#include "runtime/timer.hpp"
#include "utilities/growableArray.hpp"
#include "utilities/stack.hpp"

// Shared GC code used from different GC (Serial, CMS, G1) on enhanced redefinition
class DcevmSharedGC : AllStatic {
 public:
  static void copy_rescued_objects_back(GrowableArray<HeapWord*>* rescued_oops, bool must_be_new);
  static void copy_rescued_objects_back(GrowableArray<HeapWord*>* rescued_oops, int from, int to, bool must_be_new);
  static void clear_rescued_objects_resource(GrowableArray<HeapWord*>* rescued_oops);
  static void clear_rescued_objects_heap(GrowableArray<HeapWord*>* rescued_oops);
  static void update_fields(oop q, oop new_location);
  static void update_fields(oop new_location, oop tmp_obj, int *cur);
};

#endif
