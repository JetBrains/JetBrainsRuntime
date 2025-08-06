/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2024, JetBrains s.r.o.. All rights reserved.
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

#ifndef VKAllocator_h_Included
#define VKAllocator_h_Included

#include "VKTypes.h"

#define VK_NO_MEMORY_TYPE (~0U)
#define VK_ALL_MEMORY_PROPERTIES ((VkMemoryPropertyFlags) (~0U))

typedef struct {
    VKAllocator*                  allocator;
    VkMemoryRequirements2         requirements;
    VkMemoryDedicatedRequirements dedicatedRequirements;
    uint32_t                      memoryType;
} VKMemoryRequirements;

VKAllocator* VKAllocator_Create(VKDevice* device);

void VKAllocator_Destroy(VKAllocator* allocator);

/**
 * Get generic, most permissive memory requirements with size=0, alignment=1.
 */
VKMemoryRequirements VKAllocator_NoRequirements(VKAllocator* allocator);
/**
 * Get buffer memory requirements. Required size may not be multiple of alignment, see VKAllocator_PadToAlignment.
 */
VKMemoryRequirements VKAllocator_BufferRequirements(VKAllocator* allocator, VkBuffer buffer);
/**
 * Get image memory requirements. Required size may not be multiple of alignment, see VKAllocator_PadToAlignment.
 */
VKMemoryRequirements VKAllocator_ImageRequirements(VKAllocator* allocator, VkImage image);

/**
 * Buffer and image memory requirements doesn't enforce size to be multiple of alignment.
 * This needs to be enforced manually if resources are going to be sub-allocated in array manner.
 * This also resets dedicated requirement flags, as for dedicated allocations size must
 * be strictly equal to the one returned by resource memory requirements.
 */
void VKAllocator_PadToAlignment(VKMemoryRequirements* requirements);

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

/**
 * Callback used to find appropriate memory type within given requirements.
 * Found memory type must be set in requirements->memoryType.
 * See VKAllocator_FindMemoryType.
 */
typedef void (*VKAllocator_FindMemoryTypeCallback)(VKMemoryRequirements* requirements);

/**
 * Allocate memory within given requirements.
 * Requirements must have been initialized with VKAllocator_*Requirements
 * and memory type found with VKAllocator_FindMemoryType.
 */
VKMemory VKAllocator_Allocate(VKMemoryRequirements* requirements);

/**
 * Allocate memory within given requirements and bind it to the image.
 * Requirements must have been initialized with VKAllocator_*Requirements
 * and memory type found with VKAllocator_FindMemoryType.
 */
VKMemory VKAllocator_AllocateForImage(VKMemoryRequirements* requirements, VkImage image);

/**
 * Allocate memory within given requirements and bind it to the buffer.
 * Requirements must have been initialized with VKAllocator_*Requirements
 * and memory type found with VKAllocator_FindMemoryType.
 */
VKMemory VKAllocator_AllocateForBuffer(VKMemoryRequirements* requirements, VkBuffer buffer);

/**
 * Release allocated memory.
 */
void VKAllocator_Free(VKAllocator* allocator, VKMemory memory);

/**
 * Get underlying memory range for the memory handle.
 * - VkMappedMemoryRange.memory is a raw VkDeviceMemory given memory was allocated from.
 * - VkMappedMemoryRange.offset is the starting offset of the given memory within VkMappedMemoryRange.memory.
 * - VkMappedMemoryRange.size is the size of the given memory block, or VK_WHOLE_SIZE.
 *   VK_WHOLE_SIZE is returned in cases, when exact memory block size is not known,
 *   but it is guaranteed, that in such cases memory block covers the entire VkMappedMemoryRange.memory.
 */
VkMappedMemoryRange VKAllocator_GetMemoryRange(VKAllocator* allocator, VKMemory memory);

/**
 * Map allocated memory. Returns pointer to the beginning of mapped memory block.
 */
void* VKAllocator_Map(VKAllocator* allocator, VKMemory memory);

/**
 * Unmap allocated memory.
 */
void VKAllocator_Unmap(VKAllocator* allocator, VKMemory memory);

/**
 * Flush mapped memory range. size may be VK_WHOLE_SIZE. See vkFlushMappedMemoryRanges.
 */
void VKAllocator_Flush(VKAllocator* allocator, VKMemory memory, VkDeviceSize offset, VkDeviceSize size);

/**
 * Invalidate mapped memory range. size may be VK_WHOLE_SIZE. See vkInvalidateMappedMemoryRanges.
 */
void VKAllocator_Invalidate(VKAllocator* allocator, VKMemory memory, VkDeviceSize offset, VkDeviceSize size);

#endif //VKAllocator_h_Included
