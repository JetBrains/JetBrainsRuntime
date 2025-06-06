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

#ifndef VKBuffer_h_Included
#define VKBuffer_h_Included

#include "VKTypes.h"

#define ARRAY_TO_VERTEX_BUF(device, vertices)                                           \
    VKBuffer_CreateFromData(device, vertices, ARRAY_SIZE(vertices)*sizeof (vertices[0]))

struct VKBuffer {
    VkBuffer handle;
    // Buffer has no ownership over its memory.
    // Provided memory, offset and size must only be used to flush memory writes.
    // Allocation and freeing is done in pages.
    VkMappedMemoryRange range;
    // Only sequential writes and no reads from mapped memory!
    void* data;
};

struct VKTexelBuffer {
    VKBuffer buffer;
    VkBufferView view;
    VkDescriptorSet descriptorSet;
};

/**
 * Create buffers, allocate a memory page and bind them together.
 * 'pageSize' can be 0, meaning that page size is calculated based on buffer memory requirements.
 * It returns allocated memory page, or VK_NULL_HANDLE on failure.
 * 'bufferCount' is updated with actual number of created buffers.
 * Created buffers are written in 'buffers'.
 */
VKMemory VKBuffer_CreateBuffers(VKDevice* device, VkBufferUsageFlags usageFlags,
                                VKAllocator_FindMemoryTypeCallback findMemoryTypeCallback,
                                VkDeviceSize bufferSize, VkDeviceSize pageSize,
                                uint32_t* bufferCount, VKBuffer* buffers);

/**
 * Create texel buffers from existing array of buffers.
 * It returns created descriptor pool, or VK_NULL_HANDLE on failure.
 * Created texel buffers are written in 'texelBuffers',
 * original buffers are taken from 'buffers'.
 */
VkDescriptorPool VKBuffer_CreateTexelBuffers(VKDevice* device, VkFormat format,
                                             VkDescriptorType descriptorType, VkDescriptorSetLayout descriptorSetLayout,
                                             uint32_t bufferCount, VKBuffer* buffers, VKTexelBuffer* texelBuffers);

// TODO usage of this function is suboptimal, we need to avoid creating individual buffers.
VKBuffer* VKBuffer_Create(VKDevice* device, VkDeviceSize size,
                          VkBufferUsageFlags usage, VkMemoryPropertyFlags properties);

// TODO usage of this function is suboptimal, we need to avoid creating one-time buffers.
VKBuffer* VKBuffer_CreateFromData(VKDevice* device, void* vertices, VkDeviceSize bufferSize);

// TODO usage of this function is suboptimal, we need to avoid destroying individual buffers.
void VKBuffer_Destroy(VKDevice* device, VKBuffer* buffer);

#endif // VKBuffer_h_Included
