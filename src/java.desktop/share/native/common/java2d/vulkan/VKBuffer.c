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
#include "VKAllocator.h"
#include "VKBase.h"
#include "VKBuffer.h"

static VKMemory VKBuffer_DestroyBuffersOnFailure(VKDevice* device, VKMemory page, uint32_t bufferCount, VKBuffer* buffers) {
    assert(device != NULL && device->allocator != NULL);
    for (uint32_t i = 0; i < bufferCount; i++) {
        device->vkDestroyBuffer(device->handle, buffers[i].handle, NULL);
    }
    VKAllocator_Free(device->allocator, page);
    return VK_NULL_HANDLE;
}

VKMemory VKBuffer_CreateBuffers(VKDevice* device, VkBufferUsageFlags usageFlags,
                                VKAllocator_FindMemoryTypeCallback findMemoryTypeCallback,
                                VkDeviceSize bufferSize, VkDeviceSize pageSize,
                                uint32_t* bufferCount, VKBuffer* buffers) {
    assert(device != NULL && device->allocator != NULL);
    assert(bufferCount != NULL && buffers != NULL);
    assert(pageSize == 0 || pageSize >= bufferSize);
    if (*bufferCount == 0 || bufferSize == 0) return VK_NULL_HANDLE;
    VKAllocator* alloc = device->allocator;

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
    VKMemoryRequirements requirements = VKAllocator_BufferRequirements(alloc, buffers[0].handle);
    VKAllocator_PadToAlignment(&requirements); // Align for array-like allocation.
    VkDeviceSize realBufferSize = requirements.requirements.memoryRequirements.size;
    if (pageSize == 0) pageSize = (*bufferCount) * realBufferSize;
    uint32_t realBufferCount = pageSize / realBufferSize;
    if (realBufferCount > *bufferCount) realBufferCount = *bufferCount;
    if (realBufferCount == 0) return VKBuffer_DestroyBuffersOnFailure(device, VK_NULL_HANDLE, 1, buffers);
    requirements.requirements.memoryRequirements.size = pageSize;

    // Find memory type.
    findMemoryTypeCallback(&requirements);
    if (requirements.memoryType == VK_NO_MEMORY_TYPE) {
        return VKBuffer_DestroyBuffersOnFailure(device, VK_NULL_HANDLE, 1, buffers);
    }

    // Allocate new memory page.
    VKMemory page = VKAllocator_Allocate(&requirements);
    if (page == VK_NULL_HANDLE) return VKBuffer_DestroyBuffersOnFailure(device, VK_NULL_HANDLE, 1, buffers);
    void* data = VKAllocator_Map(alloc, page);
    if (data == NULL) return VKBuffer_DestroyBuffersOnFailure(device, page, 1, buffers);
    VkMappedMemoryRange range = VKAllocator_GetMemoryRange(alloc, page);

    // Create remaining buffers and bind memory.
    for (uint32_t i = 0;;) {
        VKBuffer* b = &buffers[i];
        b->range = (VkMappedMemoryRange) {
            .sType  = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
            .memory = range.memory,
            .offset = range.offset + realBufferSize * i,
            .size   = realBufferSize
        };
        b->data = (void*) (((uint8_t*) data) + realBufferSize * i);
        VK_IF_ERROR(device->vkBindBufferMemory(device->handle, b->handle, range.memory, b->range.offset)) {
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

static VkDescriptorPool VKBuffer_DestroyTexelBuffersOnFailure(VKDevice* device, VkDescriptorPool pool, uint32_t bufferCount, VKTexelBuffer* texelBuffers) {
    assert(device != NULL);
    for (uint32_t i = 0; i < bufferCount; i++) {
        device->vkDestroyBufferView(device->handle, texelBuffers[i].view, NULL);
    }
    device->vkDestroyDescriptorPool(device->handle, pool, NULL);
    return VK_NULL_HANDLE;
}

VkDescriptorPool VKBuffer_CreateTexelBuffers(VKDevice* device, VkFormat format,
                                             VkDescriptorType descriptorType, VkDescriptorSetLayout descriptorSetLayout,
                                             uint32_t bufferCount, VKBuffer* buffers, VKTexelBuffer* texelBuffers) {
    assert(device != NULL);

    // Create descriptor pool.
    VkDescriptorPoolSize poolSize = { .type = descriptorType, .descriptorCount = bufferCount };
    VkDescriptorPoolCreateInfo descriptorPoolCreateInfo = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
            .flags = 0,
            .maxSets = bufferCount,
            .poolSizeCount = 1,
            .pPoolSizes = &poolSize
    };
    VkDescriptorPool pool;
    VK_IF_ERROR(device->vkCreateDescriptorPool(device->handle, &descriptorPoolCreateInfo, NULL, &pool)) return VK_NULL_HANDLE;

    // Allocate descriptor sets.
    VkDescriptorSetLayout layouts[bufferCount];
    for (uint32_t i = 0; i < bufferCount; i++) layouts[i] = descriptorSetLayout;
    VkDescriptorSetAllocateInfo allocateInfo = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            .descriptorPool = pool,
            .descriptorSetCount = bufferCount,
            .pSetLayouts = layouts
    };
    VkDescriptorSet descriptorSets[bufferCount];
    VK_IF_ERROR(device->vkAllocateDescriptorSets(device->handle, &allocateInfo, descriptorSets)) {
        return VKBuffer_DestroyTexelBuffersOnFailure(device, pool, 0, texelBuffers);
    }

    // Create buffer views and record them into descriptors.
    VkBufferViewCreateInfo bufferViewCreateInfo = {
            .sType = VK_STRUCTURE_TYPE_BUFFER_VIEW_CREATE_INFO,
            .format = format,
            .offset = 0,
            .range = VK_WHOLE_SIZE
    };
    VkWriteDescriptorSet writeDescriptorSets[bufferCount];
    for (uint32_t i = 0; i < bufferCount; i++) {
        texelBuffers[i] = (VKTexelBuffer) {
            .buffer = buffers[i],
            .descriptorSet = descriptorSets[i]
        };
        bufferViewCreateInfo.buffer = buffers[i].handle;
        VK_IF_ERROR(device->vkCreateBufferView(device->handle, &bufferViewCreateInfo, NULL, &texelBuffers[i].view)) {
            return VKBuffer_DestroyTexelBuffersOnFailure(device, pool, i, texelBuffers);
        }
        writeDescriptorSets[i] = (VkWriteDescriptorSet) {
                .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                .dstSet = descriptorSets[i],
                .dstBinding = 0,
                .dstArrayElement = 0,
                .descriptorCount = 1,
                .descriptorType = descriptorType,
                .pTexelBufferView = &texelBuffers[i].view
        };
    }
    device->vkUpdateDescriptorSets(device->handle, bufferCount, writeDescriptorSets, 0, NULL);
    return pool;
}
