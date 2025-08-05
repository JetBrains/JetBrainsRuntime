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

#ifndef HEADLESS

#include "jlong.h"
#include "SurfaceData.h"
#include "VKSurfaceData.h"
#include "VKImage.h"
#include "VKRenderer.h"
#include <Trace.h>

void VKSD_InitImageSurface(VKLogicalDevice* logicalDevice, VKSDOps *vksdo) {
    if (vksdo->image != VK_NULL_HANDLE) {
        return;
    }

    vksdo->device = logicalDevice;
    vksdo->image = VKImage_Create(logicalDevice, vksdo->width, vksdo->height, VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_TILING_LINEAR,
                                  VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                                  VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (!vksdo->image)
    {
        J2dRlsTrace(J2D_TRACE_ERROR, "Cannot create image\n");
        return;
    }

    if (!VKImage_CreateFramebuffer(logicalDevice, vksdo->image, logicalDevice->fillTexturePoly->renderPass)) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Cannot create framebuffer for window surface");
        return;
    }
}

void VKSD_InitWindowSurface(VKLogicalDevice* logicalDevice, VKWinSDOps *vkwinsdo) {
    VKGraphicsEnvironment* ge = VKGE_graphics_environment();
    VkPhysicalDevice physicalDevice = logicalDevice->physicalDevice;

    if (vkwinsdo->swapchainKhr == VK_NULL_HANDLE) {
        ge->vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, vkwinsdo->surface, &vkwinsdo->capabilitiesKhr);
        ge->vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, vkwinsdo->surface, &vkwinsdo->formatsKhrCount, NULL);
        if (vkwinsdo->formatsKhrCount == 0) {
            J2dRlsTrace(J2D_TRACE_ERROR, "No formats for swapchain found\n");
            return;
        }
        vkwinsdo->formatsKhr = calloc(vkwinsdo->formatsKhrCount, sizeof(VkSurfaceFormatKHR));
        ge->vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, vkwinsdo->surface, &vkwinsdo->formatsKhrCount,
                                                 vkwinsdo->formatsKhr);

        ge->vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, vkwinsdo->surface,
                                                      &vkwinsdo->presentModeKhrCount, NULL);

        if (vkwinsdo->presentModeKhrCount == 0) {
            J2dRlsTrace(J2D_TRACE_ERROR, "No present modes found\n");
            return;
        }

        vkwinsdo->presentModesKhr = calloc(vkwinsdo->presentModeKhrCount, sizeof(VkPresentModeKHR));
        ge->vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, vkwinsdo->surface, &vkwinsdo->presentModeKhrCount,
                                                      vkwinsdo->presentModesKhr);

        VkExtent2D extent = {
                (uint32_t) (vkwinsdo->vksdOps.width),
                (uint32_t) (vkwinsdo->vksdOps.height)
        };

        uint32_t imageCount = vkwinsdo->capabilitiesKhr.minImageCount + 1;
        if (vkwinsdo->capabilitiesKhr.maxImageCount > 0 && imageCount > vkwinsdo->capabilitiesKhr.maxImageCount) {
            imageCount = vkwinsdo->capabilitiesKhr.maxImageCount;
        }
        VkSwapchainCreateInfoKHR createInfoKhr = {
                .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
                .surface = vkwinsdo->surface,
                .minImageCount = imageCount,
                .imageFormat = vkwinsdo->formatsKhr[0].format,
                .imageColorSpace = vkwinsdo->formatsKhr[0].colorSpace,
                .imageExtent = extent,
                .imageArrayLayers = 1,
                .imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
                .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
                .queueFamilyIndexCount = 0,
                .pQueueFamilyIndices = NULL,
                .preTransform = vkwinsdo->capabilitiesKhr.currentTransform,
                .compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
                .presentMode = VK_PRESENT_MODE_FIFO_KHR,
                .clipped = VK_TRUE
        };

        if (logicalDevice->vkCreateSwapchainKHR(logicalDevice->device, &createInfoKhr, NULL, &vkwinsdo->swapchainKhr) != VK_SUCCESS) {
            J2dRlsTrace(J2D_TRACE_ERROR, "Cannot create swapchain\n");
            return;
        }

        vkwinsdo->swapChainImages = VKImage_CreateImageArrayFromSwapChain(
                                        logicalDevice, vkwinsdo->swapchainKhr,
                                        logicalDevice->fillTexturePoly->renderPass,
                                        vkwinsdo->formatsKhr[0].format, extent);

        if (!vkwinsdo->swapChainImages) {
          J2dRlsTraceLn(J2D_TRACE_ERROR, "Cannot get swapchain images");
          return;
        }
    }
}

#endif /* !HEADLESS */
