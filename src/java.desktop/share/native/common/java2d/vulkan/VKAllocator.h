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

typedef struct {
    VKAllocator* allocator;
    VkMemoryRequirements2 requirements;
    VkMemoryDedicatedRequirements dedicatedRequirements;
    uint32_t memoryType;
} VKMemoryRequirements;

VKAllocator* VKAllocator_Create(VKDevice* device);

void VKAllocator_Destroy(VKAllocator* allocator);

VKMemoryRequirements VKAllocator_NoRequirements(VKAllocator* allocator);
VKMemoryRequirements VKAllocator_BufferRequirements(VKAllocator* allocator, VkBuffer buffer);
VKMemoryRequirements VKAllocator_ImageRequirements(VKAllocator* allocator, VkImage image);

/**
 * Find memory type with properties not less than requiredProperties and not more than allowedProperties,
 * allowed by given memory requirements.
 * Found memory type is set in requirements->memoryType, if it is not set already.
 * If requirements->memoryType is already set, this function does nothing.
 * @param requiredProperties minimal required set of properties
 * @param allowedProperties  maximal allowed set of properties, implicitly includes requiredProperties, can be 0
 */
void VKAllocator_FindMemoryType(VKMemoryRequirements* requirements,
                                VkMemoryPropertyFlags requiredProperties, VkMemoryPropertyFlags allowedProperties);

VKMemory VKAllocator_Allocate(VKMemoryRequirements* requirements);
/**
 * This also binds memory for the image.
 */
VKMemory VKAllocator_AllocateForImage(VKMemoryRequirements* requirements, VkImage image);
/**
 * This also binds memory for the buffer.
 */
VKMemory VKAllocator_AllocateForBuffer(VKMemoryRequirements* requirements, VkBuffer buffer);

void VKAllocator_Free(VKAllocator* allocator, VKMemory memory);

VkMappedMemoryRange VKAllocator_GetMemoryRange(VKAllocator* allocator, VKMemory memory);

void* VKAllocator_Map(VKAllocator* allocator, VKMemory memory);
void VKAllocator_Unmap(VKAllocator* allocator, VKMemory memory);
void VKAllocator_Flush(VKAllocator* allocator, VKMemory memory);
void VKAllocator_Invalidate(VKAllocator* allocator, VKMemory memory);

#endif //VKAllocator_h_Included
