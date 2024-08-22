// Copyright 2024 JetBrains s.r.o.
// DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
//
// This code is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License version 2 only, as
// published by the Free Software Foundation.  Oracle designates this
// particular file as subject to the "Classpath" exception as provided
// by Oracle in the LICENSE file that accompanied this code.
//
// This code is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
// FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
// version 2 for more details (a copy is included in the LICENSE file that
// accompanied this code).
//
// You should have received a copy of the GNU General Public License version
// 2 along with this work; if not, write to the Free Software Foundation,
// Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
//
// Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
// or visit www.oracle.com if you need additional information or have any
// questions.

#ifndef VKAllocator_h_Included
#define VKAllocator_h_Included

#include "VKTypes.h"

#define VK_NO_MEMORY_TYPE (~0U)
#define VK_ALL_MEMORY_PROPERTIES ((VkMemoryPropertyFlags) (~0U))

VKAllocator* VKAllocator_Create(VKDevice* device);

void VKAllocator_Destroy(VKAllocator* allocator);

/**
 * Find memory type with properties not less than requiredProperties and not more than allowedProperties.
 * @param typeFilter         bitmask type filter, only memory types passing the filter can be returned
 * @param requiredProperties minimal required set of properties
 * @param allowedProperties  maximal allowed set of properties, implicitly includes requiredProperties, can be 0
 * @return memory type index, or VK_NO_MEMORY_TYPE
 */
uint32_t VKAllocator_FindMemoryType(VKAllocator* allocator, uint32_t typeFilter,
                                    VkMemoryPropertyFlags requiredProperties, VkMemoryPropertyFlags allowedProperties);

#endif //VKAllocator_h_Included
