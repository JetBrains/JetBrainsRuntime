/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2025, JetBrains s.r.o.. All rights reserved.
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

#include "VKEnv.h"

#define GET_VK_PROC(GETPROCADDR, STRUCT, HANDLE, NAME) (STRUCT)->NAME = (PFN_ ## NAME) GETPROCADDR(HANDLE, #NAME)
#define GET_VK_PROC_RET_NAME_IF_ERR(GETPROCADDR, STRUCT, HANDLE, NAME) do { \
    GET_VK_PROC(GETPROCADDR, STRUCT, HANDLE, NAME);                         \
    if ((STRUCT)->NAME == NULL) {                                           \
        return #NAME;                                                       \
    }                                                                       \
} while (0)

const char* VKFunctionTable_InitGlobal(PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr, VKFunctionTableGlobal* table) {
    #define GLOBAL_FUNCTION_TABLE_ENTRY(NAME) \
        GET_VK_PROC_RET_NAME_IF_ERR(vkGetInstanceProcAddr, table, NULL, NAME)
    #include "VKFunctionTable.inl"
    return NULL;
}

const char* VKFunctionTable_InitInstance(PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr, VKEnv* vk) {
    #define INSTANCE_FUNCTION_TABLE_ENTRY(NAME) \
        GET_VK_PROC_RET_NAME_IF_ERR(vkGetInstanceProcAddr, vk, vk->instance, NAME)
    #define OPTIONAL_INSTANCE_FUNCTION_TABLE_ENTRY(NAME) \
        GET_VK_PROC(vkGetInstanceProcAddr, vk, vk->instance, NAME)
    #include "VKFunctionTable.inl"
    return NULL;
}

const char* VKFunctionTable_InitDevice(VKEnv* instance, VKDevice* device) {
    #define DEVICE_FUNCTION_TABLE_ENTRY(NAME) \
        GET_VK_PROC_RET_NAME_IF_ERR(instance->vkGetDeviceProcAddr, device, device->handle, NAME)
    #include "VKFunctionTable.inl"
    return NULL;
}
