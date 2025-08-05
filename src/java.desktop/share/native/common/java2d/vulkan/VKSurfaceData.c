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

#include "VKUtil.h"
#include "VKBase.h"
#include "VKSurfaceData.h"
#include "VKImage.h"

/**
 * Release VKSDOps resources & reset to initial state.
 */
static void VKSD_ResetImageSurface(VKSDOps* vksdo) {
    if (vksdo == NULL) return;

    // DestroyRenderPass also waits while the surface resources are being used by device.
    VKRenderer_DestroyRenderPass(vksdo);

    if (vksdo->device != NULL && vksdo->image != NULL) {
        VKImage_free(vksdo->device, vksdo->image);
        vksdo->image = NULL;
    }
}

void VKSD_ResetSurface(VKSDOps* vksdo) {
    VKSD_ResetImageSurface(vksdo);

    // Release VKWinSDOps resources, if applicable.
    if (vksdo->drawableType == VKSD_WINDOW) {
        VKWinSDOps* vkwinsdo = (VKWinSDOps*) vksdo;
        ARRAY_FREE(vkwinsdo->swapchainImages);
        vkwinsdo->swapchainImages = NULL;
        if (vkwinsdo->vksdOps.device != NULL && vkwinsdo->swapchain != VK_NULL_HANDLE) {
            vkwinsdo->vksdOps.device->vkDestroySwapchainKHR(vkwinsdo->vksdOps.device->handle, vkwinsdo->swapchain, NULL);
        }
        if (vkwinsdo->surface != VK_NULL_HANDLE) {
            VKGraphicsEnvironment* ge = VKGE_graphics_environment();
            if (ge != NULL) ge->vkDestroySurfaceKHR(ge->vkInstance, vkwinsdo->surface, NULL);
        }
        vkwinsdo->swapchain = VK_NULL_HANDLE;
        vkwinsdo->surface = VK_NULL_HANDLE;
        vkwinsdo->swapchainDevice = NULL;
    }
}

VkBool32 VKSD_ConfigureImageSurface(VKSDOps* vksdo) {
    // Initialize the device. currentDevice can be changed on the fly, and we must reconfigure surfaces accordingly.
    VKDevice* device = VKGE_graphics_environment()->currentDevice;
    if (device != vksdo->device) {
        VKSD_ResetImageSurface(vksdo);
        vksdo->device = device;
        J2dRlsTraceLn(J2D_TRACE_INFO, "VKSD_ConfigureImageSurface(%p): device updated", vksdo);
    }
    // Initialize image.
    if (vksdo->requestedExtent.width > 0 && vksdo->requestedExtent.height > 0 && (vksdo->image == NULL ||
            vksdo->requestedExtent.width != vksdo->image->extent.width ||
            vksdo->requestedExtent.height != vksdo->image->extent.height)) {
        // VK_FORMAT_B8G8R8A8_UNORM is the most widely-supported format for our use.
        VKImage* image = VKImage_Create(device, vksdo->requestedExtent.width, vksdo->requestedExtent.height,
                                        VK_FORMAT_B8G8R8A8_UNORM, VK_IMAGE_TILING_OPTIMAL,
                                        VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
                                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (image == NULL) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "VKSD_ConfigureImageSurface(%p): cannot create image", vksdo);
            VK_UNHANDLED_ERROR();
        }
        VKSD_ResetImageSurface(vksdo);
        vksdo->image = image;
        J2dRlsTraceLn(J2D_TRACE_INFO, "VKSD_ConfigureImageSurface(%p): image updated %dx%d", vksdo, image->extent.width, image->extent.height);
    }
    return vksdo->image != NULL;
}

VkBool32 VKSD_ConfigureWindowSurface(VKWinSDOps* vkwinsdo) {
    // Check that image is ready.
    if (vkwinsdo->vksdOps.image == NULL) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSD_ConfigureWindowSurface(%p): image is not ready", vkwinsdo);
        return VK_FALSE;
    }
    // Check for changes.
    if (vkwinsdo->swapchain != VK_NULL_HANDLE && vkwinsdo->swapchainDevice == vkwinsdo->vksdOps.device &&
            vkwinsdo->swapchainExtent.width == vkwinsdo->vksdOps.image->extent.width &&
            vkwinsdo->swapchainExtent.height == vkwinsdo->vksdOps.image->extent.height) return VK_TRUE;
    // Check that surface is ready.
    if (vkwinsdo->surface == VK_NULL_HANDLE) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSD_ConfigureWindowSurface(%p): surface is not ready", vkwinsdo);
        return VK_FALSE;
    }

    VKGraphicsEnvironment* ge = VKGE_graphics_environment();
    VKDevice* device = vkwinsdo->vksdOps.device;
    VkPhysicalDevice physicalDevice = device->physicalDevice;

    VkSurfaceCapabilitiesKHR capabilities;
    VK_IF_ERROR(ge->vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, vkwinsdo->surface, &capabilities)) {
        return VK_FALSE;
    }

    uint32_t formatCount;
    VK_IF_ERROR(ge->vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, vkwinsdo->surface, &formatCount, NULL)) {
        return VK_FALSE;
    }
    VkSurfaceFormatKHR formats[formatCount];
    VK_IF_ERROR(ge->vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, vkwinsdo->surface, &formatCount, formats)) {
        return VK_FALSE;
    }

    VkSurfaceFormatKHR* format = NULL;
    J2dRlsTraceLn(J2D_TRACE_INFO, "VKSD_ConfigureWindowSurface(%p): available swapchain formats:", vkwinsdo);
    for (uint32_t i = 0; i < formatCount; i++) {
        J2dRlsTraceLn(J2D_TRACE_INFO, "    format=%d, colorSpace=%d", formats[i].format, formats[i].colorSpace);
        // We draw with sRGB colors (see VKUtil_DecodeJavaColor()), so we don't want Vulkan to do color space
        // conversions when drawing to surface. We use *_UNORM formats, so that colors are written "as is".
        // With VK_COLOR_SPACE_SRGB_NONLINEAR_KHR these colors will be interpreted by presentation engine as sRGB.
        if (formats[i].colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR && (
                formats[i].format == VK_FORMAT_A8B8G8R8_UNORM_PACK32 ||
                formats[i].format == VK_FORMAT_B8G8R8A8_UNORM ||
                formats[i].format == VK_FORMAT_R8G8B8A8_UNORM ||
                formats[i].format == VK_FORMAT_B8G8R8_UNORM ||
                formats[i].format == VK_FORMAT_R8G8B8_UNORM
            )) {
            format = &formats[i];
        }
    }
    if (format == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKSD_ConfigureWindowSurface(%p): no suitable format found", vkwinsdo);
        return VK_FALSE;
    }

    // TODO inspect and choose present mode
    uint32_t presentModeCount;
    VK_IF_ERROR(ge->vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, vkwinsdo->surface, &presentModeCount, NULL)) {
        return VK_FALSE;
    }
    VkPresentModeKHR presentModes[presentModeCount];
    VK_IF_ERROR(ge->vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, vkwinsdo->surface, &presentModeCount, presentModes)) {
        VK_UNHANDLED_ERROR();
    }

    uint32_t imageCount = capabilities.minImageCount + 1;
    if (capabilities.maxImageCount > 0 && imageCount > capabilities.maxImageCount) {
        imageCount = capabilities.maxImageCount;
    }

    VkSwapchainKHR swapchain;
    VkSwapchainCreateInfoKHR createInfoKhr = {
            .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
            .surface = vkwinsdo->surface,
            .minImageCount = imageCount,
            .imageFormat = format->format,
            .imageColorSpace = format->colorSpace,
            .imageExtent = vkwinsdo->vksdOps.image->extent, // TODO consider capabilities.currentExtent, capabilities.minImageExtent and capabilities.maxImageExtent
            .imageArrayLayers = 1,
            .imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT,
            .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
            .queueFamilyIndexCount = 0,
            .pQueueFamilyIndices = NULL,
            .preTransform = capabilities.currentTransform,
            .compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
            .presentMode = VK_PRESENT_MODE_FIFO_KHR, // TODO need more flexibility
            .clipped = VK_TRUE,
            .oldSwapchain = vkwinsdo->swapchain
    };

    VK_IF_ERROR(device->vkCreateSwapchainKHR(device->handle, &createInfoKhr, NULL, &swapchain)) {
        return VK_FALSE;
    }
    J2dRlsTraceLn(J2D_TRACE_INFO, "VKSD_ConfigureWindowSurface(%p): swapchain created", vkwinsdo);

    if (vkwinsdo->swapchain != VK_NULL_HANDLE) {
        // Destroy old swapchain.
        // TODO is it possible that old swapchain is still being presented, can we destroy it right now?
        device->vkDestroySwapchainKHR(device->handle, vkwinsdo->swapchain, NULL);
        J2dRlsTraceLn(J2D_TRACE_INFO, "VKSD_ConfigureWindowSurface(%p): old swapchain destroyed", vkwinsdo);
    }
    vkwinsdo->swapchain = swapchain;
    vkwinsdo->swapchainDevice = device;
    vkwinsdo->swapchainExtent = vkwinsdo->vksdOps.image->extent;

    uint32_t swapchainImageCount;
    VK_IF_ERROR(device->vkGetSwapchainImagesKHR(device->handle, vkwinsdo->swapchain, &swapchainImageCount, NULL)) {
        return VK_FALSE;
    }
    ARRAY_RESIZE(vkwinsdo->swapchainImages, swapchainImageCount);
    VK_IF_ERROR(device->vkGetSwapchainImagesKHR(device->handle, vkwinsdo->swapchain,
                                             &swapchainImageCount, vkwinsdo->swapchainImages)) {
        return VK_FALSE;
    }
    return VK_TRUE;
}

#endif /* !HEADLESS */
