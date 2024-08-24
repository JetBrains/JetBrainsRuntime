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
#include "VKImage.h"


static VkBool32 VKImage_CreateView(VKDevice* device, VKImage* image) {
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

    VK_IF_ERROR(device->vkCreateImageView(device->device, &viewInfo, NULL, &image->view)) {
        return VK_FALSE;
    }
    return VK_TRUE;
}

VKImage* VKImage_Create(VKDevice* device, VkExtent2D extent, VkFormat format, VkImageTiling tiling,
                        VkImageUsageFlags usage, VKImage_FindMemoryTypeCallback findMemoryTypeCallback) {
    VKImage* image = calloc(1, sizeof(VKImage));
    VK_RUNTIME_ASSERT(image);

    image->format = format;
    image->extent = extent;
    VkImageCreateInfo imageInfo = {
            .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
            .imageType = VK_IMAGE_TYPE_2D,
            .extent.width = extent.width,
            .extent.height = extent.height,
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
    VK_IF_ERROR(device->vkCreateImage(device->device, &imageInfo, NULL, &image->image)) {
        VKImage_Destroy(device, image);
        return NULL;
    }

    VKMemoryRequirements requirements = VKAllocator_ImageRequirements(device->allocator, image->image);
    findMemoryTypeCallback(&requirements);
    if (requirements.memoryType == VK_NO_MEMORY_TYPE) {
        VKImage_Destroy(device, image);
        return NULL;
    }
    image->memory = VKAllocator_AllocateForImage(&requirements, image->image);

    if (image->memory == VK_NULL_HANDLE || !VKImage_CreateView(device, image)) {
        VKImage_Destroy(device, image);
        return NULL;
    }
    return image;
}

void VKImage_Destroy(VKDevice* device, VKImage* image) {
    if (image == NULL) return;
    if (image->view != VK_NULL_HANDLE) {
        device->vkDestroyImageView(device->device, image->view, NULL);
        image->view = VK_NULL_HANDLE;
    }
    if (image->image != VK_NULL_HANDLE) {
        device->vkDestroyImage(device->device, image->image, NULL);
        image->image = VK_NULL_HANDLE;
    }
    VKAllocator_Free(device->allocator, image->memory);
    free(image);
}