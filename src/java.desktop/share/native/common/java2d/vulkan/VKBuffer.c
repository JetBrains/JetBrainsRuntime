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

#include "VKUtil.h"
#include "VKBase.h"
#include "VKAllocator.h"
#include "VKBuffer.h"

VKBuffer* VKBuffer_Create(VKDevice* device, VkDeviceSize size, VkBufferUsageFlags usage,
                          VKBuffer_FindMemoryTypeCallback findMemoryTypeCallback) {
    VKBuffer* buffer = calloc(1, sizeof(VKBuffer));
    VK_RUNTIME_ASSERT(buffer);

    VkBufferCreateInfo bufferInfo = {
            .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
            .size = size,
            .usage = usage,
            .sharingMode = VK_SHARING_MODE_EXCLUSIVE
    };
    VK_IF_ERROR(device->vkCreateBuffer(device->device, &bufferInfo, NULL, &buffer->buffer)) {
        VKBuffer_Destroy(device, buffer);
        return NULL;
    }

    VKMemoryRequirements requirements = VKAllocator_BufferRequirements(device->allocator, buffer->buffer);
    findMemoryTypeCallback(&requirements);
    if (requirements.memoryType == VK_NO_MEMORY_TYPE) {
        VKBuffer_Destroy(device, buffer);
        return NULL;
    }
    buffer->memory = VKAllocator_AllocateForBuffer(&requirements, buffer->buffer);
    return buffer;
}

void VKBuffer_Destroy(VKDevice* device, VKBuffer* buffer) {
    if (buffer != NULL) {
        if (buffer->buffer != VK_NULL_HANDLE) {
            device->vkDestroyBuffer(device->device, buffer->buffer, NULL);
        }
        VKAllocator_Free(device->allocator, buffer->memory);
        free(buffer);
    }
}