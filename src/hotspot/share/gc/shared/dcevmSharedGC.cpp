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

#include "precompiled.hpp"
#include "gc/shared/dcevmSharedGC.hpp"
#include "memory/iterator.inline.hpp"
#include "oops/access.inline.hpp"
#include "oops/compressedOops.inline.hpp"
#include "oops/instanceClassLoaderKlass.inline.hpp"
#include "oops/instanceKlass.inline.hpp"
#include "oops/instanceMirrorKlass.inline.hpp"
#include "oops/instanceRefKlass.inline.hpp"
#include "oops/oop.inline.hpp"
#include "utilities/macros.hpp"

void DcevmSharedGC::copy_rescued_objects_back(GrowableArray<HeapWord*>* rescued_oops, bool must_be_new) {
  if (rescued_oops != NULL) {
    copy_rescued_objects_back(rescued_oops, 0, rescued_oops->length(), must_be_new);
  }
}

// (DCEVM) Copy the rescued objects to their destination address after compaction.
void DcevmSharedGC::copy_rescued_objects_back(GrowableArray<HeapWord*>* rescued_oops, int from, int to, bool must_be_new) {

  if (rescued_oops != NULL) {
    for (int i=from; i < to; i++) {
      HeapWord* rescued_ptr = rescued_oops->at(i);
      oop rescued_obj = (oop) rescued_ptr;

      int size = rescued_obj->size();
      oop new_obj = rescued_obj->forwardee();

      assert(!must_be_new || rescued_obj->klass()->new_version() != NULL, "Just checking");
      Klass* new_klass = rescued_obj->klass()->new_version();
      if (new_klass!= NULL) {
        if (new_klass->update_information() != NULL) {
          DcevmSharedGC::update_fields(rescued_obj, new_obj);
        } else {
          rescued_obj->set_klass(new_klass);
          Copy::aligned_disjoint_words((HeapWord*)rescued_obj, (HeapWord*)new_obj, size);
        }
      } else {
        Copy::aligned_disjoint_words((HeapWord*)rescued_obj, (HeapWord*)new_obj, size);
      }

      new_obj->init_mark_raw();
      assert(oopDesc::is_oop(new_obj), "must be a valid oop");
    }
  }

}

void DcevmSharedGC::clear_rescued_objects_resource(GrowableArray<HeapWord*>* rescued_oops) {
  if (rescued_oops != NULL) {
    for (int i=0; i < rescued_oops->length(); i++) {
      HeapWord* rescued_ptr = rescued_oops->at(i);
      int size = ((oop) rescued_ptr)->size();
      FREE_RESOURCE_ARRAY(HeapWord, rescued_ptr, size);
    }
    rescued_oops->clear();
  }
}

void DcevmSharedGC::clear_rescued_objects_heap(GrowableArray<HeapWord*>* rescued_oops) {
  if (rescued_oops != NULL) {
    for (int i=0; i < rescued_oops->length(); i++) {
      HeapWord* rescued_ptr = rescued_oops->at(i);
      FREE_C_HEAP_ARRAY(HeapWord, rescued_ptr);
    }
    rescued_oops->clear();
  }
}

// (DCEVM) Update instances of a class whose fields changed.
void DcevmSharedGC::update_fields(oop q, oop new_location) {

  assert(q->klass()->new_version() != NULL, "class of old object must have new version");

  Klass* old_klass_oop = q->klass();
  Klass* new_klass_oop = q->klass()->new_version();

  InstanceKlass *old_klass = InstanceKlass::cast(old_klass_oop);
  InstanceKlass *new_klass = InstanceKlass::cast(new_klass_oop);

  int size = q->size_given_klass(old_klass);
  int new_size = q->size_given_klass(new_klass);

  HeapWord* tmp = NULL;
  oop tmp_obj = q;

  // Save object somewhere, there is an overlap in fields
  if (new_klass_oop->is_copying_backwards()) {
    if (((HeapWord *)q >= (HeapWord *)new_location && (HeapWord *)q < (HeapWord *)new_location + new_size) ||
        ((HeapWord *)new_location >= (HeapWord *)q && (HeapWord *)new_location < (HeapWord *)q + size)) {
       tmp = NEW_RESOURCE_ARRAY(HeapWord, size);
       q = (oop) tmp;
       Copy::aligned_disjoint_words((HeapWord*)tmp_obj, (HeapWord*)q, size);
    }
  }

  q->set_klass(new_klass_oop);
  int *cur = new_klass_oop->update_information();
  assert(cur != NULL, "just checking");
  DcevmSharedGC::update_fields(new_location, q, cur);

  if (tmp != NULL) {
    FREE_RESOURCE_ARRAY(HeapWord, tmp, size);
  }
}

void DcevmSharedGC::update_fields(oop new_location, oop tmp_obj, int *cur) {
  assert(cur != NULL, "just checking");
  char* to = (char*)(HeapWord*)new_location;
  while (*cur != 0) {
    int size = *cur;
    if (size > 0) {
      cur++;
      int offset = *cur;
      HeapWord* from = (HeapWord*)(((char *)(HeapWord*)tmp_obj) + offset);
      if (size == HeapWordSize) {
        *((HeapWord*)to) = *from;
      } else if (size == HeapWordSize * 2) {
        *((HeapWord*)to) = *from;
        *(((HeapWord*)to) + 1) = *(from + 1);
      } else {
        Copy::conjoint_jbytes(from, to, size);
      }
      to += size;
      cur++;
    } else {
      assert(size < 0, "");
      int skip = -*cur;
      Copy::fill_to_bytes(to, skip, 0);
      to += skip;
      cur++;
    }
  }
}
