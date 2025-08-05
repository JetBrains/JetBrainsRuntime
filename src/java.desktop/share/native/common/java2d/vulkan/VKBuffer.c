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

#include <string.h>
#include <Trace.h>
#include "CArrayUtil.h"
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

VKBuffer* VKBuffer_Create(VKLogicalDevice* logicalDevice, VkDeviceSize size,
                          VkBufferUsageFlags usage, VkMemoryPropertyFlags properties)
{
    VKBuffer* buffer = malloc(sizeof (VKBuffer));

    VkBufferCreateInfo bufferInfo = {
            .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
            .size = size,
            .usage = usage,
            .sharingMode = VK_SHARING_MODE_EXCLUSIVE
    };

    if (logicalDevice->vkCreateBuffer(logicalDevice->device, &bufferInfo, NULL, &buffer->buffer) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "failed to allocate descriptor sets!");
        return NULL;
    }

    buffer->size = size;

    VkMemoryRequirements memRequirements;
    logicalDevice->vkGetBufferMemoryRequirements(logicalDevice->device, buffer->buffer, &memRequirements);

    uint32_t memoryType;

    if (VKBuffer_FindMemoryType(logicalDevice->physicalDevice,
                                memRequirements.memoryTypeBits,
                                properties, &memoryType) != VK_SUCCESS)
    {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "failed to find memory!");
        return NULL;
    }

    VkMemoryAllocateInfo allocInfo = {
            .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
            .allocationSize = memRequirements.size,
            .memoryTypeIndex = memoryType
    };

    if (logicalDevice->vkAllocateMemory(logicalDevice->device, &allocInfo, NULL, &buffer->memory) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "failed to allocate buffer memory!");
        return NULL;
    }

    if (logicalDevice->vkBindBufferMemory(logicalDevice->device, buffer->buffer, buffer->memory, 0) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "failed to bind buffer memory!");
        return NULL;
    }
    return buffer;
}

VKBuffer* VKBuffer_CreateFromData(VKLogicalDevice* logicalDevice, void* vertices, VkDeviceSize bufferSize)
{
    VKBuffer* buffer = VKBuffer_Create(logicalDevice, bufferSize,
                                       VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                                       VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                       VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

    void* data;
    if (logicalDevice->vkMapMemory(logicalDevice->device, buffer->memory, 0, bufferSize, 0, &data) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "failed to map memory!");
        return NULL;
    }
    memcpy(data, vertices, bufferSize);

    VkMappedMemoryRange memoryRange = {
            .sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
            .pNext = NULL,
            .memory = buffer->memory,
            .offset = 0,
            .size = VK_WHOLE_SIZE
    };


    if (logicalDevice->vkFlushMappedMemoryRanges(logicalDevice->device, 1, &memoryRange) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "failed to flush memory!");
        return NULL;
    }
    logicalDevice->vkUnmapMemory(logicalDevice->device, buffer->memory);
    buffer->size = bufferSize;

    return buffer;
}

void VKBuffer_free(VKLogicalDevice* logicalDevice, VKBuffer* buffer) {
    if (buffer != NULL) {
        if (buffer->buffer != VK_NULL_HANDLE) {
            logicalDevice->vkDestroyBuffer(logicalDevice->device, buffer->buffer, NULL);
        }
        if (buffer->memory != VK_NULL_HANDLE) {
            logicalDevice->vkFreeMemory(logicalDevice->device, buffer->memory, NULL);
        }
        free(buffer);
    }
}