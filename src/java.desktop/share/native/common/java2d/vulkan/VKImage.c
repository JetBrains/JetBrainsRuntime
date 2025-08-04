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
#include "VKDevice.h"
#include "VKImage.h"

static size_t viewKeyHash(const void* ptr) {
    const VKImageViewKey* k = ptr;
    return (size_t) k->format | ((size_t) k->swizzle << 32);
}
static bool viewKeyEquals(const void* ap, const void* bp) {
    const VKImageViewKey *a = ap, *b = bp;
    return a->format == b->format && a->swizzle == b->swizzle;
}

static VkImageView VKImage_CreateView(VKDevice* device, VkImage image, VkFormat format, VkComponentMapping swizzle) {
    VkImageViewCreateInfo viewInfo = {
            .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            .image = image,
            .viewType = VK_IMAGE_VIEW_TYPE_2D,
            .format = format,
            .components = swizzle,
            .subresourceRange.aspectMask = VKUtil_GetFormatGroup(format).aspect,
            .subresourceRange.baseMipLevel = 0,
            .subresourceRange.levelCount = 1,
            .subresourceRange.baseArrayLayer = 0,
            .subresourceRange.layerCount = 1,
    };
    VkImageView view;
    VK_IF_ERROR(device->vkCreateImageView(device->handle, &viewInfo, NULL, &view)) {
        return VK_NULL_HANDLE;
    }
    return view;
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

    HASH_MAP_REHASH(image->viewMap, linear_probing, &viewKeyEquals, &viewKeyHash, 1, 10, 0.75);

    return image;
}

void VKImage_Destroy(VKDevice* device, VKImage* image) {
    assert(device != NULL && device->allocator != NULL);
    if (image == NULL) return;
    if (image->viewMap != NULL) {
        for (const VKImageViewKey* k = NULL; (k = MAP_NEXT_KEY(image->viewMap, k)) != NULL;) {
            const VKImageViewInfo* viewInfo = MAP_FIND(image->viewMap, *k);
            if (viewInfo->descriptorSet != VK_NULL_HANDLE) {
                device->vkFreeDescriptorSets(device->handle, viewInfo->descriptorPool, 1, &viewInfo->descriptorSet);
            }
            device->vkDestroyImageView(device->handle, viewInfo->view, NULL);
        }
        MAP_FREE(image->viewMap);
    }
    device->vkDestroyImage(device->handle, image->handle, NULL);
    VKAllocator_Free(device->allocator, image->memory);
    free(image);
}

static VKImageViewInfo* VKImage_GetViewInfo(VKDevice* device, VKImage* image, VkFormat format, VKPackedSwizzle swizzle) {
    VKImageViewKey key = { format, swizzle };
    VKImageViewInfo* viewInfo = MAP_FIND(image->viewMap, key);
    if (viewInfo == NULL || viewInfo->view == VK_NULL_HANDLE) {
        if (viewInfo == NULL) viewInfo = &MAP_AT(image->viewMap, key);
        viewInfo->view = VKImage_CreateView(device, image->handle, format, VK_UNPACK_SWIZZLE(swizzle));
    }
    return viewInfo;
}

VkImageView VKImage_GetView(VKDevice* device, VKImage* image, VkFormat format, VKPackedSwizzle swizzle) {
    return VKImage_GetViewInfo(device, image, format, swizzle)->view;
}

VkDescriptorSetLayout VKRenderer_GetImageDescriptorSetLayout(VKRenderer* renderer);

#define IMAGE_DESCRIPTOR_POOL_SIZE 64
static VkDescriptorSet VKImage_AllocateDescriptorSet(VKDevice* device, VkDescriptorPool descriptorPool) {
    VkDescriptorSetLayout descriptorSetLayout = VKRenderer_GetImageDescriptorSetLayout(device->renderer);
    VkDescriptorSetAllocateInfo allocateInfo = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = descriptorPool,
        .descriptorSetCount = 1,
        .pSetLayouts = &descriptorSetLayout
    };
    VkDescriptorSet set;
    VkResult result = device->vkAllocateDescriptorSets(device->handle, &allocateInfo, &set);
    if (result == VK_SUCCESS) return set;
    if (result != VK_ERROR_OUT_OF_POOL_MEMORY && result != VK_ERROR_FRAGMENTED_POOL) {
        VK_IF_ERROR(result) VK_UNHANDLED_ERROR();
    }
    return VK_NULL_HANDLE;
}

static void VKImage_CreateDescriptorSet(VKDevice* device, VkDescriptorPool* descriptorPool, VkDescriptorSet* set) {
    for (int i = ARRAY_SIZE(device->imageDescriptorPools) - 1; i >= 0; i--) {
        *set = VKImage_AllocateDescriptorSet(device, device->imageDescriptorPools[i]);
        if (*set != VK_NULL_HANDLE) {
            *descriptorPool = device->imageDescriptorPools[i];
            return;
        }
    }
    VkDescriptorPoolSize poolSize = {
        .type = VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
        .descriptorCount = IMAGE_DESCRIPTOR_POOL_SIZE
    };
    VkDescriptorPoolCreateInfo poolInfo = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT,
        .poolSizeCount = 1,
        .pPoolSizes = &poolSize,
        .maxSets = IMAGE_DESCRIPTOR_POOL_SIZE
    };
    VK_IF_ERROR(device->vkCreateDescriptorPool(device->handle, &poolInfo, NULL, descriptorPool)) VK_UNHANDLED_ERROR();
    ARRAY_PUSH_BACK(device->imageDescriptorPools) = *descriptorPool;
    *set = VKImage_AllocateDescriptorSet(device, *descriptorPool);
}

VkDescriptorSet VKImage_GetDescriptorSet(VKDevice* device, VKImage* image, VkFormat format, VKPackedSwizzle swizzle) {
    VKImageViewInfo* info = VKImage_GetViewInfo(device, image, format, swizzle);
    if (info->descriptorSet == VK_NULL_HANDLE) {
        VKImage_CreateDescriptorSet(device, &info->descriptorPool, &info->descriptorSet);
        VkDescriptorImageInfo imageInfo = {
            .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            .imageView = info->view
        };
        VkWriteDescriptorSet descriptorWrites = {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = info->descriptorSet,
            .dstBinding = 0,
            .dstArrayElement = 0,
            .descriptorType = VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
            .descriptorCount = 1,
            .pImageInfo = &imageInfo
        };
        device->vkUpdateDescriptorSets(device->handle, 1, &descriptorWrites, 0, NULL);
    }
    return info->descriptorSet;
}

void VKImage_AddBarrier(VkImageMemoryBarrier* barriers, VKBarrierBatch* batch,
                        VKImage* image, VkPipelineStageFlags stage, VkAccessFlags access, VkImageLayout layout) {
    assert(barriers != NULL && batch != NULL && image != NULL);
    // TODO Even if stage, access and layout didn't change, we may still need a barrier against WaW hazard.
    if (stage != image->lastStage || access != image->lastAccess || layout != image->layout) {
        barriers[batch->barrierCount] = (VkImageMemoryBarrier) {
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
            .srcAccessMask = image->lastAccess,
            .dstAccessMask = access,
            .oldLayout = image->layout,
            .newLayout = layout,
            .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .image = image->handle,
            .subresourceRange = { VKUtil_GetFormatGroup(image->format).aspect, 0, 1, 0, 1 }
        };
        batch->barrierCount++;
        batch->srcStages |= image->lastStage;
        batch->dstStages |= stage;
        image->lastStage = stage;
        image->lastAccess = access;
        image->layout = layout;
    }
}
