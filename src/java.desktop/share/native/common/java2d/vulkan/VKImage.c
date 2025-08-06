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
#include "VKUtil.h"
#include "VKAllocator.h"
#include "VKBuffer.h"
#include "VKDevice.h"
#include "VKImage.h"
#include "VKRenderer.h"
#include "VKSurfaceData.h"


static VkBool32 VKImage_CreateView(VKDevice* device, VKImage* image) {
    VkImageViewCreateInfo viewInfo = {
            .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            .image = image->handle,
            .viewType = VK_IMAGE_VIEW_TYPE_2D,
            .format = image->format,
            .subresourceRange.aspectMask = VKImage_GetAspect(image),
            .subresourceRange.baseMipLevel = 0,
            .subresourceRange.levelCount = 1,
            .subresourceRange.baseArrayLayer = 0,
            .subresourceRange.layerCount = 1,
    };

    VK_IF_ERROR(device->vkCreateImageView(device->handle, &viewInfo, NULL, &image->view)) {
        return VK_FALSE;
    }
    return VK_TRUE;
}

VkImageAspectFlagBits VKImage_GetAspect(VKImage* image) {
    return VKUtil_GetFormatGroup(image->format).bytes == 0 ? // Unknown format group means stencil.
           VK_IMAGE_ASPECT_STENCIL_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
}

VKImage* VKImage_Create(VKDevice* device, uint32_t width, uint32_t height,
                        VkImageCreateFlags flags, VkFormat format,
                        VkImageTiling tiling, VkImageUsageFlags usage, VkSampleCountFlagBits samples,
                        VKAllocator_FindMemoryTypeCallback findMemoryTypeCallback) {
    assert(device != NULL && device->allocator != NULL);
    VKImage* image = calloc(1, sizeof(VKImage));
    VK_RUNTIME_ASSERT(image);

    image->format = format;
    image->extent = (VkExtent2D) {width, height};
    image->layout = VK_IMAGE_LAYOUT_UNDEFINED;
    image->lastStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
    image->lastAccess = 0;

    VkImageCreateInfo createInfo = {
            .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
            .flags = flags,
            .imageType = VK_IMAGE_TYPE_2D,
            .extent.width = width,
            .extent.height = height,
            .extent.depth = 1,
            .mipLevels = 1,
            .arrayLayers = 1,
            .format = format,
            .tiling = tiling,
            .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
            .usage = usage,
            .samples = samples,
            .sharingMode = VK_SHARING_MODE_EXCLUSIVE
    };
    VK_IF_ERROR(device->vkCreateImage(device->handle, &createInfo, NULL, &image->handle)) {
        VKImage_Destroy(device, image);
        return NULL;
    }

    VKMemoryRequirements requirements = VKAllocator_ImageRequirements(device->allocator, image->handle);
    findMemoryTypeCallback(&requirements);
    if (requirements.memoryType == VK_NO_MEMORY_TYPE) {
        VKImage_Destroy(device, image);
        return NULL;
    }

    image->memory = VKAllocator_AllocateForImage(&requirements, image->handle);
    if (image->memory == VK_NULL_HANDLE) {
        VKImage_Destroy(device, image);
        return NULL;
    }

    if (!VKImage_CreateView(device, image)) {
        VKImage_Destroy(device, image);
        return NULL;
    }

    return image;
}

void VKImage_LoadBuffer(VKDevice* device, VKImage* image, VKBuffer* buffer,
                        uint32_t x0, uint32_t y0, uint32_t width, uint32_t height) {
    VkCommandBuffer cb = VKRenderer_Record(device->renderer);
    VkBufferImageCopy region = (VkBufferImageCopy){
            .bufferOffset = 0,
            .bufferRowLength = 0,
            .bufferImageHeight = 0,
            .imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .imageSubresource.mipLevel = 0,
            .imageSubresource.baseArrayLayer = 0,
            .imageSubresource.layerCount = 1,
            .imageOffset = {x0, y0, 0},
            .imageExtent = {
                    .width = width,
                    .height = height,
                    .depth = 1
            }
    };
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKImage_LoadBuffer(device=%p, commandBuffer=%p, buffer=%p, image=%p)",
                  device, cb, buffer->handle, image->handle);
    device->vkCmdCopyBufferToImage(cb, buffer->handle, image->handle,
                                          VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                          1, &region);
}

void VKImage_Destroy(VKDevice* device, VKImage* image) {
    assert(device != NULL && device->allocator != NULL);
    if (image == NULL) return;
    device->vkDestroyImageView(device->handle, image->view, NULL);
    device->vkDestroyImage(device->handle, image->handle, NULL);
    VKAllocator_Free(device->allocator, image->memory);
    free(image);
}