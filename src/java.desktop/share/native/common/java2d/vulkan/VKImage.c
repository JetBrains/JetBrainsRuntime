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
#include "VKBuffer.h"
#include "VKImage.h"


VkBool32 VKImage_CreateView(VKDevice* device, VKImage* image) {
    VkImageViewCreateInfo viewInfo = {
            .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            .image = image->image,
            .viewType = VK_IMAGE_VIEW_TYPE_2D,
            .format = image->format,
            .subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
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

VKImage* VKImage_Create(VKDevice* device, uint32_t width, uint32_t height,
                        VkFormat format, VkImageTiling tiling,
                        VkImageUsageFlags usage,
                        VkMemoryPropertyFlags properties)
{
    VKImage* image = calloc(1, sizeof(VKImage));
    VK_RUNTIME_ASSERT(image);

    image->format = format;
    image->extent = (VkExtent2D) {width, height};
    image->layout = VK_IMAGE_LAYOUT_UNDEFINED;
    image->lastStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
    image->lastAccess = 0;

    VkImageCreateInfo imageInfo = {
            .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
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
            .samples = VK_SAMPLE_COUNT_1_BIT,
            .sharingMode = VK_SHARING_MODE_EXCLUSIVE
    };

    VK_IF_ERROR(device->vkCreateImage(device->handle, &imageInfo, NULL, &image->image)) {
        VKImage_free(device, image);
        return NULL;
    }

    VkMemoryRequirements memRequirements;
    device->vkGetImageMemoryRequirements(device->handle, image->image, &memRequirements);

    uint32_t memoryType;
    VK_IF_ERROR(VKBuffer_FindMemoryType(device->physicalDevice,
                                memRequirements.memoryTypeBits,
                                properties, &memoryType))
    {
        VKImage_free(device, image);
        return NULL;
    }

    VkMemoryAllocateInfo allocInfo = {
            .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
            .allocationSize = memRequirements.size,
            .memoryTypeIndex = memoryType
    };

    VK_IF_ERROR(device->vkAllocateMemory(device->handle, &allocInfo, NULL, &image->memory)) {
        VKImage_free(device, image);
        return NULL;
    }

    VK_IF_ERROR(device->vkBindImageMemory(device->handle, image->image, image->memory, 0)) {
        VKImage_free(device, image);
        return NULL;
    }

    if (!VKImage_CreateView(device, image)) {
        VKImage_free(device, image);
        return NULL;
    }

    return image;
}

void VKImage_free(VKDevice* device, VKImage* image) {
    if (!image) return;

    if (image->view != VK_NULL_HANDLE) {
        device->vkDestroyImageView(device->handle, image->view, NULL);
        image->view = VK_NULL_HANDLE;
    }

    if (image->memory != VK_NULL_HANDLE) {
        device->vkFreeMemory(device->handle, image->memory, NULL);
        image->memory = VK_NULL_HANDLE;
    }

    if (image->image != VK_NULL_HANDLE) {
        device->vkDestroyImage(device->handle, image->image, NULL);
        image->image = VK_NULL_HANDLE;
    }

    free(image);
}