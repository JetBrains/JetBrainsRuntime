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

#include <Trace.h>
#include "CArrayUtil.h"
#include "VKBase.h"
#include "VKBuffer.h"
#include "VKImage.h"


VkBool32 VKImage_CreateView(VKLogicalDevice* logicalDevice, VKImage* image) {
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

    if (logicalDevice->vkCreateImageView(logicalDevice->device, &viewInfo, NULL, &image->view) != VK_SUCCESS) {
        J2dRlsTrace(J2D_TRACE_ERROR, "Cannot surface image view\n");
        return VK_FALSE;
    }
    return VK_TRUE;
}

VkBool32 VKImage_CreateFramebuffer(VKLogicalDevice* logicalDevice, VKImage *image, VkRenderPass renderPass) {
    VkImageView attachments[] = {
            image->view
    };

    VkFramebufferCreateInfo framebufferInfo = {
            .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
            .renderPass = renderPass,
            .attachmentCount = 1,
            .pAttachments = attachments,
            .width = image->extent.width,
            .height = image->extent.height,
            .layers = 1
    };

    if (logicalDevice->vkCreateFramebuffer(logicalDevice->device, &framebufferInfo, NULL,
                                &image->framebuffer) != VK_SUCCESS)
    {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "failed to create framebuffer!");
        return VK_FALSE;
    }
    return VK_TRUE;
}

VKImage* VKImage_Create(VKLogicalDevice* logicalDevice,
                        uint32_t width, uint32_t height,
                        VkFormat format, VkImageTiling tiling,
                        VkImageUsageFlags usage,
                        VkMemoryPropertyFlags properties)
{
    VKImage* image = malloc(sizeof (VKImage));

    if (!image) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Cannot allocate data for image");
        return NULL;
    }

    image->format = format;
    image->extent = (VkExtent2D) {width, height};
    image->framebuffer = VK_NULL_HANDLE;
    image->noImageDealloc = VK_FALSE;

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

    if (logicalDevice->vkCreateImage(logicalDevice->device, &imageInfo, NULL, &image->image) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Cannot create surface image");
        VKImage_free(logicalDevice, image);
        return NULL;
    }

    VkMemoryRequirements memRequirements;
    logicalDevice->vkGetImageMemoryRequirements(logicalDevice->device, image->image, &memRequirements);

    uint32_t memoryType;
    if (VKBuffer_FindMemoryType(logicalDevice->physicalDevice,
                                memRequirements.memoryTypeBits,
                                properties, &memoryType) != VK_SUCCESS)
    {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Failed to find memory");
        VKImage_free(logicalDevice, image);
        return NULL;
    }

    VkMemoryAllocateInfo allocInfo = {
            .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
            .allocationSize = memRequirements.size,
            .memoryTypeIndex = memoryType
    };

    if (logicalDevice->vkAllocateMemory(logicalDevice->device, &allocInfo, NULL, &image->memory) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Failed to allocate image memory");
        VKImage_free(logicalDevice, image);
        return NULL;
    }

    logicalDevice->vkBindImageMemory(logicalDevice->device, image->image, image->memory, 0);

    if (!VKImage_CreateView(logicalDevice, image)) {
        VKImage_free(logicalDevice, image);
        return NULL;
    }

    return image;
}

VKImage* VKImage_CreateImageArrayFromSwapChain(VKLogicalDevice* logicalDevice,
                                               VkSwapchainKHR swapchainKhr, VkRenderPass renderPass,
                                               VkFormat format, VkExtent2D extent)
{
    uint32_t swapChainImagesCount;
    if (logicalDevice->vkGetSwapchainImagesKHR(logicalDevice->device, swapchainKhr, &swapChainImagesCount,
                                    NULL) != VK_SUCCESS) {
        J2dRlsTrace(J2D_TRACE_ERROR, "Cannot get swapchain images\n");
        return NULL;
    }

    if (swapChainImagesCount == 0) {
        J2dRlsTrace(J2D_TRACE_ERROR, "No swapchain images found\n");
        return NULL;
    }
    VkImage swapChainImages[swapChainImagesCount];

    if (logicalDevice->vkGetSwapchainImagesKHR(logicalDevice->device, swapchainKhr, &swapChainImagesCount,
                                    swapChainImages) != VK_SUCCESS) {
        J2dRlsTrace(J2D_TRACE_ERROR, "Cannot get swapchain images\n");
        return NULL;
    }

    VKImage* images = ARRAY_ALLOC(VKImage, swapChainImagesCount);
    for (uint32_t i = 0; i < swapChainImagesCount; i++) {
        ARRAY_PUSH_BACK(&images, ((VKImage){
                .image = swapChainImages[i],
                .memory = VK_NULL_HANDLE,
                .format = format,
                .extent = extent,
                .noImageDealloc = VK_TRUE
        }));

        if (!VKImage_CreateView(logicalDevice, &ARRAY_LAST(images))) {
            ARRAY_APPLY_TRAILING(images, VKImage_dealloc, logicalDevice);
            ARRAY_FREE(images);
            return NULL;
        }

        if (!VKImage_CreateFramebuffer(logicalDevice, &ARRAY_LAST(images), renderPass)) {
            ARRAY_APPLY_TRAILING(images, VKImage_dealloc, logicalDevice);
            ARRAY_FREE(images);
            return NULL;
        }
    }

    return images;
}

void VKImage_dealloc(VKLogicalDevice* logicalDevice, VKImage* image) {
    if (!image) return;

    if (image->framebuffer != VK_NULL_HANDLE) {
        logicalDevice->vkDestroyFramebuffer(logicalDevice->device, image->framebuffer, NULL);
        image->framebuffer = VK_NULL_HANDLE;
    }

    if (image->view != VK_NULL_HANDLE) {
        logicalDevice->vkDestroyImageView(logicalDevice->device, image->view, NULL);
        image->view = VK_NULL_HANDLE;
    }

    if (image->memory != VK_NULL_HANDLE) {
        logicalDevice->vkFreeMemory(logicalDevice->device, image->memory, NULL);
        image->memory = VK_NULL_HANDLE;
    }

    if (image->image != VK_NULL_HANDLE && !image->noImageDealloc) {
        logicalDevice->vkDestroyImage(logicalDevice->device, image->image, NULL);
        image->image = VK_NULL_HANDLE;
    }
}

void VKImage_free(VKLogicalDevice* logicalDevice, VKImage* image) {
    VKImage_dealloc(logicalDevice, image);
    free(image);
}