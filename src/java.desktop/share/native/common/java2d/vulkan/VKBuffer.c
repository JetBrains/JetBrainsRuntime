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

#include <assert.h>
#include <string.h>
#include "VKUtil.h"
#include "VKBase.h"
#include "VKBuffer.h"

VkResult VKBuffer_FindMemoryType(VkPhysicalDevice physicalDevice, uint32_t typeFilter,
                                 VkMemoryPropertyFlags properties, uint32_t* pMemoryType) {
    VKGraphicsEnvironment* ge = VKGE_graphics_environment();
    VkPhysicalDeviceMemoryProperties memProperties;
    ge->vkGetPhysicalDeviceMemoryProperties(physicalDevice, &memProperties);

    for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) && (memProperties.memoryTypes[i].propertyFlags & properties) == properties) {
            *pMemoryType = i;
            return VK_SUCCESS;
        }
    }

    return VK_ERROR_UNKNOWN;
}

static VkDeviceMemory VKBuffer_DestroyBuffersOnFailure(VKDevice* device, VkDeviceMemory page, uint32_t bufferCount, VKBuffer* buffers) {
    for (uint32_t i = 0; i < bufferCount; i++) {
        device->vkDestroyBuffer(device->handle, buffers[i].handle, NULL);
    }
    device->vkFreeMemory(device->handle, page, NULL);
    return VK_NULL_HANDLE;
}

VkDeviceMemory VKBuffer_CreateBuffers(VKDevice* device, VkBufferUsageFlags usageFlags,
                                      VkMemoryPropertyFlags requiredMemoryProperties,
                                      VkDeviceSize bufferSize, VkDeviceSize pageSize,
                                      uint32_t* bufferCount, VKBuffer* buffers) {
    assert(device != NULL);
    assert(bufferCount != NULL && buffers != NULL);
    assert(pageSize == 0 || pageSize >= bufferSize);
    if (*bufferCount == 0 || bufferSize == 0) return VK_NULL_HANDLE;

    // Create a single buffer.
    VkBufferCreateInfo bufferInfo = {
            .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
            .size = bufferSize,
            .usage = usageFlags,
            .sharingMode = VK_SHARING_MODE_EXCLUSIVE
    };
    VK_IF_ERROR(device->vkCreateBuffer(device->handle, &bufferInfo, NULL, &buffers[0].handle)) return VK_NULL_HANDLE;

    // Check memory requirements. We aim to create maxBufferCount buffers,
    // but due to implementation-specific alignment requirements this number can be lower (unlikely though).
    VkMemoryRequirements memRequirements;
    device->vkGetBufferMemoryRequirements(device->handle, buffers[0].handle, &memRequirements);
    // Required size may not be multiple of alignment, fix.
    memRequirements.size = ((memRequirements.size + memRequirements.alignment - 1) /
                            memRequirements.alignment) * memRequirements.alignment;
    VkDeviceSize realBufferSize = memRequirements.size;
    if (pageSize == 0) pageSize = (*bufferCount) * realBufferSize;
    uint32_t realBufferCount = pageSize / realBufferSize;
    if (realBufferCount > *bufferCount) realBufferCount = *bufferCount;
    if (realBufferCount == 0) return VKBuffer_DestroyBuffersOnFailure(device, VK_NULL_HANDLE, 1, buffers);

    // Find memory type.
    uint32_t memoryType;
    VK_IF_ERROR(VKBuffer_FindMemoryType(device->physicalDevice, memRequirements.memoryTypeBits,
                                        requiredMemoryProperties, &memoryType)) {
        return VKBuffer_DestroyBuffersOnFailure(device, VK_NULL_HANDLE, 1, buffers);
    }

    // Allocate new memory page.
    VkMemoryAllocateInfo allocateInfo = {
            .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
            .allocationSize = pageSize,
            .memoryTypeIndex = memoryType
    };
    VkDeviceMemory page;
    VK_IF_ERROR(device->vkAllocateMemory(device->handle, &allocateInfo, NULL, &page)) {
        return VKBuffer_DestroyBuffersOnFailure(device, VK_NULL_HANDLE, 1, buffers);
    }
    void* data;
    VK_IF_ERROR(device->vkMapMemory(device->handle, page, 0, VK_WHOLE_SIZE, 0, &data)) {
        return VKBuffer_DestroyBuffersOnFailure(device, page, 1, buffers);
    }

    // Create remaining buffers and bind memory.
    for (uint32_t i = 0;;) {
        VKBuffer* b = &buffers[i];
        b->range = (VkMappedMemoryRange) {
            .sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
            .memory = page,
            .offset = realBufferSize * i,
            .size = realBufferSize
        };
        b->data = (void*) (((uint8_t*) data) + realBufferSize * i);
        VK_IF_ERROR(device->vkBindBufferMemory(device->handle, b->handle, page, b->range.offset)) {
            return VKBuffer_DestroyBuffersOnFailure(device, page, i + 1, buffers);
        }
        if ((++i) >= realBufferCount) break;
        VK_IF_ERROR(device->vkCreateBuffer(device->handle, &bufferInfo, NULL, &buffers[i].handle)) {
            return VKBuffer_DestroyBuffersOnFailure(device, page, i, buffers);
        }
    }

    *bufferCount = realBufferCount;
    return page;
}

VKBuffer* VKBuffer_Create(VKDevice* device, VkDeviceSize size,
                          VkBufferUsageFlags usage, VkMemoryPropertyFlags properties)
{
    VKBuffer* buffer = calloc(1, sizeof(VKBuffer));
    VK_RUNTIME_ASSERT(buffer);

    VkBufferCreateInfo bufferInfo = {
            .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
            .size = size,
            .usage = usage,
            .sharingMode = VK_SHARING_MODE_EXCLUSIVE
    };

    VK_IF_ERROR(device->vkCreateBuffer(device->handle, &bufferInfo, NULL, &buffer->handle)) {
        VKBuffer_Destroy(device, buffer);
        return NULL;
    }

    buffer->range.offset = 0;
    buffer->range.size = size;

    VkMemoryRequirements memRequirements;
    device->vkGetBufferMemoryRequirements(device->handle, buffer->handle, &memRequirements);

    uint32_t memoryType;

    VK_IF_ERROR(VKBuffer_FindMemoryType(device->physicalDevice,
                                        memRequirements.memoryTypeBits,
                                        properties, &memoryType)) {
        VKBuffer_Destroy(device, buffer);
        return NULL;
    }

    VkMemoryAllocateInfo allocInfo = {
            .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
            .allocationSize = memRequirements.size,
            .memoryTypeIndex = memoryType
    };

    VK_IF_ERROR(device->vkAllocateMemory(device->handle, &allocInfo, NULL, &buffer->range.memory)) {
        VKBuffer_Destroy(device, buffer);
        return NULL;
    }

    VK_IF_ERROR(device->vkBindBufferMemory(device->handle, buffer->handle, buffer->range.memory, 0)) {
        VKBuffer_Destroy(device, buffer);
        return NULL;
    }
    return buffer;
}

VKBuffer* VKBuffer_CreateFromData(VKDevice* device, void* vertices, VkDeviceSize bufferSize)
{
    VKBuffer* buffer = VKBuffer_Create(device, bufferSize,
                                       VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                                       VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                       VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

    void* data;
    VK_IF_ERROR(device->vkMapMemory(device->handle, buffer->range.memory, 0, VK_WHOLE_SIZE, 0, &data)) {
        VKBuffer_Destroy(device, buffer);
        return NULL;
    }
    memcpy(data, vertices, bufferSize);

    VkMappedMemoryRange memoryRange = {
            .sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
            .pNext = NULL,
            .memory = buffer->range.memory,
            .offset = 0,
            .size = VK_WHOLE_SIZE
    };


    VK_IF_ERROR(device->vkFlushMappedMemoryRanges(device->handle, 1, &memoryRange)) {
        VKBuffer_Destroy(device, buffer);
        return NULL;
    }
    device->vkUnmapMemory(device->handle, buffer->range.memory);

    return buffer;
}

void VKBuffer_Destroy(VKDevice* device, VKBuffer* buffer) {
    if (buffer != NULL) {
        if (buffer->handle != VK_NULL_HANDLE) {
            device->vkDestroyBuffer(device->handle, buffer->handle, NULL);
        }
        if (buffer->range.memory != VK_NULL_HANDLE) {
            device->vkFreeMemory(device->handle, buffer->range.memory, NULL);
        }
        free(buffer);
    }
}
