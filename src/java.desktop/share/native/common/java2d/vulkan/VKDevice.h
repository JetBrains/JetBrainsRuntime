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

#ifndef VKDevice_h_Included
#define VKDevice_h_Included
#include "VKTexturePool.h"
#include "VKUtil.h"

struct VKDevice {
    VkDevice         handle;
    VkPhysicalDevice physicalDevice;
    char*            name;
    uint32_t         queueFamily;
    ARRAY(pchar)     enabledLayers;
    ARRAY(pchar)     enabledExtensions;
    VkQueue          queue;

    VKAllocator*     allocator;
    VKRenderer*      renderer;
    VKTexturePool*   texturePool;

#define DEVICE_FUNCTION_TABLE_ENTRY(NAME) PFN_ ## NAME NAME
#include "VKFunctionTable.inl"
};

#endif //VKDevice_h_Included
