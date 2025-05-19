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

#include "gc/shared/dcevmSharedGC.hpp"
#include "oops/compressedOops.inline.hpp"
#include "oops/instanceKlass.inline.hpp"
#include "gc/shared/fullGCForwarding.inline.hpp"
#include "utilities/copy.hpp"
#include "runtime/fieldDescriptor.hpp"
#include "runtime/fieldDescriptor.inline.hpp"

DcevmSharedGC* DcevmSharedGC::_static_instance = nullptr;

void DcevmSharedGC::create_static_instance() {
  _static_instance = new DcevmSharedGC();
}

void DcevmSharedGC::destroy_static_instance() {
  if (_static_instance != nullptr) {
    delete _static_instance;
    _static_instance = nullptr;
  }
}

void DcevmSharedGC::copy_rescued_objects_back(GrowableArray<HeapWord*>* rescued_oops, bool must_be_new) {
  if (rescued_oops != nullptr) {
    copy_rescued_objects_back(rescued_oops, 0, rescued_oops->length(), must_be_new);
  }
}

// (DCEVM) Copy the rescued objects to their destination address after compaction.
void DcevmSharedGC::copy_rescued_objects_back(GrowableArray<HeapWord*>* rescued_oops, int from, int to, bool must_be_new) {
  if (rescued_oops != nullptr) {
    ResourceMark rm;
    for (int i=from; i < to; i++) {
      HeapWord* rescued_ptr = rescued_oops->at(i);
      oop rescued_obj = cast_to_oop(rescued_ptr);

      size_t size = rescued_obj->size();
      oop new_obj = FullGCForwarding::forwardee(rescued_obj);

      assert(!must_be_new || rescued_obj->klass()->new_version() != nullptr, "Just checking");
      Klass* new_klass = rescued_obj->klass()->new_version();
      if (new_klass!= nullptr) {
        if (new_klass->update_information() != nullptr) {
          DcevmSharedGC::update_fields(rescued_obj, new_obj);
        } else {
          rescued_obj->set_klass(new_klass);
          Copy::aligned_disjoint_words(cast_from_oop<HeapWord*>(rescued_obj), cast_from_oop<HeapWord*>(new_obj), size);
        }
      } else {
        Copy::aligned_disjoint_words(cast_from_oop<HeapWord*>(rescued_obj), cast_from_oop<HeapWord*>(new_obj), size);
      }

      new_obj->init_mark();
      assert(oopDesc::is_oop(new_obj), "must be a valid oop");
    }
  }
}

void DcevmSharedGC::clear_rescued_objects_resource(GrowableArray<HeapWord*>* rescued_oops) {
  if (rescued_oops != nullptr) {
    for (int i=0; i < rescued_oops->length(); i++) {
      HeapWord* rescued_ptr = rescued_oops->at(i);
      size_t size = cast_to_oop(rescued_ptr)->size();
      FREE_RESOURCE_ARRAY(HeapWord, rescued_ptr, size);
    }
    rescued_oops->clear();
  }
}

void DcevmSharedGC::clear_rescued_objects_heap(GrowableArray<HeapWord*>* rescued_oops) {
  if (rescued_oops != nullptr) {
    for (int i=0; i < rescued_oops->length(); i++) {
      HeapWord* rescued_ptr = rescued_oops->at(i);
      FREE_C_HEAP_ARRAY(HeapWord, rescued_ptr);
    }
    rescued_oops->clear();
  }
}

// (DCEVM) Update instances of a class whose fields changed.
void DcevmSharedGC::update_fields(oop q, oop new_location) {

  assert(q->klass()->new_version() != nullptr, "class of old object must have new version");

  Klass* old_klass_oop = q->klass();
  Klass* new_klass_oop = q->klass()->new_version();

  InstanceKlass *old_klass = InstanceKlass::cast(old_klass_oop);
  InstanceKlass *new_klass = InstanceKlass::cast(new_klass_oop);

  size_t size = q->size_given_klass(old_klass);
  size_t new_size = q->size_given_klass(new_klass);

  HeapWord* tmp = nullptr;
  oop tmp_obj = q;

  // Save object somewhere, there is an overlap in fields
  if (new_klass_oop->is_copying_backwards()) {
    if ((cast_from_oop<HeapWord*>(q) >= cast_from_oop<HeapWord*>(new_location) && cast_from_oop<HeapWord*>(q) < cast_from_oop<HeapWord*>(new_location) + new_size) ||
        (cast_from_oop<HeapWord*>(new_location) >= cast_from_oop<HeapWord*>(q) && cast_from_oop<HeapWord*>(new_location) < cast_from_oop<HeapWord*>(q) + size)) {
       tmp = NEW_RESOURCE_ARRAY(HeapWord, size);
       q = cast_to_oop(tmp);
       Copy::aligned_disjoint_words(cast_from_oop<HeapWord*>(tmp_obj), cast_from_oop<HeapWord*>(q), size);
    }
  }

  q->set_klass(new_klass_oop);
  int *cur = new_klass_oop->update_information();
  assert(cur != nullptr, "just checking");
  _static_instance->update_fields(new_location, q, cur, false);

  if (tmp != nullptr) {
    FREE_RESOURCE_ARRAY(HeapWord, tmp, size);
  }
}

void DcevmSharedGC::update_fields(oop new_obj, oop old_obj, int* cur, bool do_compat_check) {
  assert(cur != nullptr, "just checking");
  char* to = (char*)cast_from_oop<HeapWord*>(new_obj);
  char* src_base = (char *)cast_from_oop<HeapWord*>(old_obj);
  while (*cur != 0) {
    int raw = *cur;
    if (raw > 0) {
      cur++;
      int src_offset = *cur;
      HeapWord* from = (HeapWord*)(src_base + src_offset);

      bool compat_check = do_compat_check && ((raw & UpdateInfoCompatFlag) != 0);
      int size = (raw & UpdateInfoLengthMask);

      if (!compat_check) {
        if (size == HeapWordSize) {
          *((HeapWord *) to) = *from;
        } else if (size == HeapWordSize * 2) {
          *((HeapWord *) to) = *from;
          *(((HeapWord *) to) + 1) = *(from + 1);
        } else {
          Copy::conjoint_jbytes(from, to, size);
        }
      } else {
        assert(size == heapOopSize, "Must be one oop");
        int dst_offset = (int)(to - (char*)cast_from_oop<HeapWord*>(new_obj));

        oop obj = old_obj->obj_field(src_offset);

        if (obj == nullptr) {
          new_obj->obj_field_put(dst_offset, nullptr);
        } else {
          bool compatible = is_compatible(new_obj, dst_offset, obj);
          new_obj->obj_field_put(dst_offset, compatible ? obj : (oop)nullptr);
        }
      }
      to += size;
      cur++;
    } else {
      Copy::fill_to_bytes(to, -raw, 0);
      to += -raw;
      cur++;
    }
  }
}

void DcevmSharedGC::update_fields_in_old(oop old_obj, int* cur) {
  assert(cur != nullptr, "just checking");
  int dst_offset = 0;
  while (*cur != 0) {
    int raw = *cur;
    if (raw > 0) {
      cur++;
      int size = (raw & UpdateInfoLengthMask);

      if ((raw & UpdateInfoCompatFlag) != 0) {
        assert(size == heapOopSize, "Must be one oop");
        int src_offset = *cur;
        oop obj = old_obj->obj_field(src_offset);
        if (obj != nullptr) {
          bool compatible = is_compatible(old_obj, dst_offset, obj);
          if (!compatible) {
            old_obj->obj_field_put(src_offset, nullptr);
          }
        }
      }
      dst_offset += size;
      cur++;
    } else {
      dst_offset += -raw;
      cur++;
    }
  }
}

static inline bool signature_matches_name(Symbol* sig, Symbol* name) {
  const int sig_len  = sig->utf8_length();
  const int name_len = name->utf8_length();
  if (sig_len != name_len + 2) {
    return false;
  }
  const u1* s = sig ->bytes();
  const u1* n = name->bytes();
  return (s[0] == 'L' && s[sig_len - 1] == ';' && memcmp(s + 1, n, name_len) == 0);
}

bool DcevmSharedGC::is_compatible(oop fld_holder, int fld_offset, oop fld_val) {
  assert(oopDesc::is_oop(fld_val), "val has corrupted header");

  bool result = false;
  Symbol *sig_wanted;

  InstanceKlass* holder_ik = InstanceKlass::cast(fld_holder->klass()->newest_version());
  Symbol** sig_cached = _field_sig_table->get({holder_ik, fld_offset});

  if (sig_cached != nullptr) {
    sig_wanted = *sig_cached;
  } else {
    fieldDescriptor fd_new;
    bool ok = holder_ik->find_field_from_offset(fld_offset, false, &fd_new);
    assert(ok, "Must exist");
    sig_wanted = fd_new.signature();
    _field_sig_table->put({holder_ik, fld_offset}, sig_wanted);
  }

  InstanceKlass *ik = InstanceKlass::cast(fld_val->klass()->newest_version());
  bool* hit = _compat_table->get({ik, sig_wanted });

  if (hit != nullptr) {
    result = *hit;
  } else {
    InstanceKlass* scan = ik;
    while (scan != nullptr && !result) {
      if (signature_matches_name(sig_wanted, scan->name())) {
        result = true;
        break;
      }
      Array<InstanceKlass*>* ifaces = scan->local_interfaces();
      for (int j = 0; j < ifaces->length(); ++j) {
        if (signature_matches_name(sig_wanted, ifaces->at(j)->name())) {
          result = true;
          break;
        }
      }
      scan = (scan->super() != nullptr) ? InstanceKlass::cast(scan->super()) : nullptr;
    }
    _compat_table->put({ik, sig_wanted }, result);
  }
  return result;
}
