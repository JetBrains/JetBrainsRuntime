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
#include "classfile/dcevmDeoptExclude.hpp"
#include "oops/instanceKlass.hpp"
#include "utilities/macros.hpp"
#include "memory/allocation.hpp"
#include "utilities/growableArray.hpp"

GrowableArray<char*>* DcevmDeoptExclude::_excludes = NULL;

void DcevmDeoptExclude::setup_deoptimization_excl(InstanceKlass* ik) {
  GrowableArray<char*>* deopt_excludes = _excludes;

  if (deopt_excludes == NULL) {
    deopt_excludes = new (ResourceObj::C_HEAP, mtInternal) GrowableArray<char*>(10, true);
    const char* deopt_path = HotswapExcludeDeoptClassPath;
    const char* const end = deopt_path + strlen(deopt_path);
    while (deopt_path < end) {
      const char* tmp_end = strchr(deopt_path, ',');
      if (tmp_end == NULL) {
        tmp_end = end;
      }
      int size = tmp_end - deopt_path + 1;
      char* deopt_segm_path = NEW_C_HEAP_ARRAY(char, size, mtInternal);
      memcpy(deopt_segm_path, deopt_path, tmp_end - deopt_path);
      deopt_segm_path[tmp_end - deopt_path] = '\0';
      deopt_excludes->append(deopt_segm_path);
      deopt_path = tmp_end + 1;
    }
    _excludes = deopt_excludes;
  }

  for (GrowableArrayIterator<char*> it = deopt_excludes->begin(); it != deopt_excludes->end(); ++it) {
    char* s = *it;
    if (s[0] == '-' && strncmp(s+1, ik->external_name(), strlen(s+1)) == 0) {
      break;
    }
    if (strncmp(s, ik->external_name(), strlen(s)) == 0) {
      log_trace(redefine, class, load)("Excluding from deoptimization : %s", ik->external_name());
      ik->set_deoptimization_excl(true);
      break;
    }
  }
}
