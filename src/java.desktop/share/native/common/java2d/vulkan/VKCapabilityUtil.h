/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
 */

#ifndef VKCapabilityUtil_h_Included
#define VKCapabilityUtil_h_Included
#include "VKUtil.h"

#define VK_KHR_VALIDATION_LAYER_NAME "VK_LAYER_KHRONOS_validation"

// Named entries are layers or extensions, arragned into on-stack linked list.
typedef struct VKNamedEntry {
    pchar       name;
    const void* found; // Pointer to the found struct.
    struct VKNamedEntry* next; // Pointer to the next entry.
} VKNamedEntry;

#define DEF_NAMED_ENTRY(LIST, NAME) VKNamedEntry NAME = { NAME ## _NAME, NULL, (LIST) }; \
    if (NAME.name != NULL) (LIST) = &(NAME)

static void VKNamedEntry_LogAll(pchar what, pchar all, uint32_t count, size_t stride) {
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "    Supported %s:", what);
    for (uint32_t i = 0; i < count; i++) {
        if (i == 0) J2dRlsTrace(J2D_TRACE_VERBOSE, "            ");
        else J2dRlsTrace(J2D_TRACE_VERBOSE, ", ");
        J2dRlsTrace(J2D_TRACE_VERBOSE, all);
        all += stride;
    }
    J2dRlsTrace(J2D_TRACE_VERBOSE, "\n");
}

static void VKNamedEntry_LogFound(const VKNamedEntry* list) {
    for (; list != NULL; list = list->next) {
        J2dRlsTraceLn(J2D_TRACE_INFO, "    %s = %s", list->name, list->found ? "true" : "false");
    }
}

static void VKNamedEntry_Match(VKNamedEntry* list, pchar all, uint32_t count, size_t stride) {
    for (; list != NULL; list = list->next) {
        pchar check = all;
        for (uint32_t i = 0; i < count; i++) {
            if (strcmp(list->name, check) == 0) {
                list->found = check;
                break;
            }
            check += stride;
        }
    }
}

static pchar_array_t VKNamedEntry_CollectNames(const VKNamedEntry* list) {
    pchar_array_t result = {0};
    for (; list != NULL; list = list->next) {
        if (list->found) ARRAY_PUSH_BACK(result) = list->name;
    }
    return result;
}

static void VKCapabilityUtil_LogErrors(int level, pchar_array_t errors) {
    for (uint32_t i = 0; i < errors.size; i++) {
        J2dRlsTraceLn(level, "        %s", errors.data[i]);
    }
}

#endif //VKCapabilityUtil_h_Included
