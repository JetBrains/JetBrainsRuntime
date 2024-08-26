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

#include "CArrayUtil.h"
#include "VKUtil.h"
#include "VKAllocator.h"
#include "VKBase.h"
#include "VKSurfaceData.h"

/**
 * Release VKSDOps resources & reset to initial state.
 */
static void VKSD_ResetImageSurface(VKSDOps* vksdo) {
    if (vksdo == NULL) return;

    // ReleaseRenderPass also waits while the surface resources are being used by device.
    VKRenderer_DestroyRenderPass(vksdo);

    DESTROY_FORMAT_ALIASED_HANDLE(vksdo->view, i) {
        assert(vksdo->device != NULL);
        vksdo->device->vkDestroyImageView(vksdo->device->handle, vksdo->view[i], NULL);
        vksdo->view[i] = VK_NULL_HANDLE;
    }

    if (vksdo->image.handle != VK_NULL_HANDLE) {
        assert(vksdo->device != NULL && vksdo->device->allocator != NULL);
        VKAllocator_DestroyImage(vksdo->device->allocator, vksdo->image);
        vksdo->image = VK_NULL_IMAGE;
    }
}

void VKSD_ResetSurface(VKSDOps* vksdo) {
    VKSD_ResetImageSurface(vksdo);

    // Release VKWinSDOps resources, if applicable.
    if (vksdo->drawableType == VKSD_WINDOW) {
        VKWinSDOps* vkwinsdo = (VKWinSDOps*) vksdo;
        ARRAY_FREE(vkwinsdo->swapchainImages);
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

static void VKSD_FindImageSurfaceMemoryType(VKMemoryRequirements* requirements) {
    VKAllocator_FindMemoryType(requirements, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, VK_ALL_MEMORY_PROPERTIES);
}

VkBool32 VKSD_ConfigureImageSurface(VKSDOps* vksdo) {
    // Initialize the device. currentDevice can be changed on the fly, and we must reconfigure surfaces accordingly.
    VKDevice* device = VKGE_graphics_environment()->currentDevice;
    if (device != vksdo->device) {
        VKSD_ResetImageSurface(vksdo);
        vksdo->device = device;
        J2dRlsTraceLn1(J2D_TRACE_INFO, "VKSD_ConfigureImageSurface(%p): device updated", vksdo);
    }
    // Initialize image.
    if (vksdo->requestedExtent.width > 0 && vksdo->requestedExtent.height > 0 &&
            (vksdo->requestedExtent.width != vksdo->extent.width ||
             vksdo->requestedExtent.height != vksdo->extent.height)) {

        // VK_FORMAT_B8G8R8A8_SRGB is the most widely-supported format for our use.
        // We can also use normalized high-bit-count formats, like R10G10B10A2_UNORM, or R16G16B16A16_UNORM.
        // The only thing to keep in mind is that we must not use 32-bit normalized formats, like B8G8R8A8_UNORM,
        // because they would result in precision loss when blitting to the swapchain (explained further).
        // Currently, we only support *_SRGB and *_UNORM formats,
        // as other types may not be trivial to alias for logicOp rendering.
        VkFormat requestedFormat = VK_FORMAT_B8G8R8A8_SRGB;
        if (VK_DEBUG_RANDOM(50)) requestedFormat = VK_FORMAT_R16G16B16A16_UNORM;

        VkFormat format[FORMAT_ALIAS_COUNT];
        SET_ALIASED_FORMAT_FROM_REAL(format, requestedFormat);
        VKImage image = VKAllocator_CreateImage(device->allocator,
                                                format[FORMAT_ALIAS_REAL] != format[FORMAT_ALIAS_UNORM] ?
                                                VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT : 0,
                                                vksdo->requestedExtent, format[FORMAT_ALIAS_REAL], VK_IMAGE_TILING_OPTIMAL,
                                                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
                                                VK_SAMPLE_COUNT_1_BIT, VKSD_FindImageSurfaceMemoryType);
        VK_RUNTIME_ASSERT(image.handle);

        VkImageView view[FORMAT_ALIAS_COUNT];
        VkImageViewCreateInfo viewInfo = {
                .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
                .image = image.handle,
                .viewType = VK_IMAGE_VIEW_TYPE_2D,
                .subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .subresourceRange.baseMipLevel = 0,
                .subresourceRange.levelCount = 1,
                .subresourceRange.baseArrayLayer = 0,
                .subresourceRange.layerCount = 1,
        };
        INIT_FORMAT_ALIASED_HANDLE(view, format, i) {
            viewInfo.format = format[i];
            VK_IF_ERROR(device->vkCreateImageView(device->handle, &viewInfo, NULL, &view[i])) VK_UNHANDLED_ERROR();
        }

        VKSD_ResetImageSurface(vksdo);
        vksdo->image = image;
        vksdo->extent = vksdo->requestedExtent;
        FOR_EACH_FORMAT_ALIAS(i) {
            vksdo->view[i] = view[i];
            vksdo->format[i] = format[i];
        }
        J2dRlsTraceLn4(J2D_TRACE_INFO, "VKSD_ConfigureImageSurface(%p): image updated %dx%d, format=%d",
                       vksdo, vksdo->extent.width, vksdo->extent.height, requestedFormat);
    }
    return vksdo->image.handle != VK_NULL_HANDLE;
}

VkBool32 VKSD_ConfigureWindowSurface(VKWinSDOps* vkwinsdo) {
    // Check that image is ready.
    if (vkwinsdo->vksdOps.image.handle == VK_NULL_HANDLE) {
        J2dRlsTraceLn1(J2D_TRACE_WARNING, "VKSD_ConfigureWindowSurface(%p): image is not ready", vkwinsdo);
        return VK_FALSE;
    }
    // Check for changes.
    if (vkwinsdo->swapchain != VK_NULL_HANDLE && vkwinsdo->swapchainDevice == vkwinsdo->vksdOps.device &&
            vkwinsdo->swapchainExtent.width == vkwinsdo->vksdOps.extent.width &&
            vkwinsdo->swapchainExtent.height == vkwinsdo->vksdOps.extent.height) return VK_TRUE;
    // Check that surface is ready.
    if (vkwinsdo->surface == VK_NULL_HANDLE) {
        J2dRlsTraceLn1(J2D_TRACE_WARNING, "VKSD_ConfigureWindowSurface(%p): surface is not ready", vkwinsdo);
        return VK_FALSE;
    }

    VKGraphicsEnvironment* ge = VKGE_graphics_environment();
    VKDevice* device = vkwinsdo->vksdOps.device;
    VkPhysicalDevice physicalDevice = device->physicalDevice;

    VkSurfaceCapabilitiesKHR capabilities;
    VK_IF_ERROR(ge->vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, vkwinsdo->surface, &capabilities)) {
        return VK_FALSE;
    }

    // currentExtent is the current width and height of the surface, or the special value (0xFFFFFFFF, 0xFFFFFFFF)
    // indicating that the surface size will be determined by the extent of a swapchain targeting the surface.
    // https://registry.khronos.org/vulkan/specs/1.3-extensions/man/html/VkSurfaceCapabilitiesKHR.html
    // The behavior is platform-dependent if the image extent does not match the surface’s currentExtent
    // as returned by vkGetPhysicalDeviceSurfaceCapabilitiesKHR.
    // https://registry.khronos.org/vulkan/specs/1.3-extensions/man/html/VkSwapchainCreateInfoKHR.html
    if ((vkwinsdo->vksdOps.extent.width != capabilities.currentExtent.width ||
         vkwinsdo->vksdOps.extent.height != capabilities.currentExtent.height) &&
         (capabilities.currentExtent.width != 0xFFFFFFFF || capabilities.currentExtent.height != 0xFFFFFFFF)) {
        J2dRlsTraceLn5(J2D_TRACE_WARNING,
                       "VKSD_ConfigureWindowSurface(%p): surface size doesn't match, expected=%dx%d, capabilities.currentExtent=%dx%d",
                       vkwinsdo, vkwinsdo->vksdOps.extent.width, vkwinsdo->vksdOps.extent.height,
                       capabilities.currentExtent.width, capabilities.currentExtent.height);
        return VK_FALSE;
    }

    if (vkwinsdo->vksdOps.extent.width  < capabilities.minImageExtent.width  ||
        vkwinsdo->vksdOps.extent.height < capabilities.minImageExtent.height ||
        vkwinsdo->vksdOps.extent.width  > capabilities.maxImageExtent.width  ||
        vkwinsdo->vksdOps.extent.height > capabilities.maxImageExtent.height) {
        J2dRlsTraceLn7(J2D_TRACE_WARNING,
                       "VKSD_ConfigureWindowSurface(%p): surface size doesn't fit, expected=%dx%d, "
                       "capabilities.minImageExtent=%dx%d, capabilities.minImageExtent=%dx%d",
                       vkwinsdo, vkwinsdo->vksdOps.extent.width, vkwinsdo->vksdOps.extent.height,
                       capabilities.minImageExtent.width, capabilities.minImageExtent.height,
                       capabilities.maxImageExtent.width, capabilities.maxImageExtent.height);
        return VK_FALSE;
    }

    // Our surfaces use pre-multiplied alpha, try to match.
    // This allows us to have semi-transparent windows.
    VkCompositeAlphaFlagBitsKHR compositeAlpha;
    if (capabilities.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR) {
        compositeAlpha = VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR;
    } else if (capabilities.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR) {
        // This is wrong, but at least some transparency is better than none, right?
        compositeAlpha = VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR;
    } else if (capabilities.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR) {
        compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    } else compositeAlpha = (VkCompositeAlphaFlagBitsKHR) capabilities.supportedCompositeAlpha;

    uint32_t formatCount;
    VK_IF_ERROR(ge->vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, vkwinsdo->surface, &formatCount, NULL)) {
        return VK_FALSE;
    }
    VkSurfaceFormatKHR formats[formatCount];
    VK_IF_ERROR(ge->vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, vkwinsdo->surface, &formatCount, formats)) {
        return VK_FALSE;
    }

    VkSurfaceFormatKHR* format = NULL;
    J2dRlsTraceLn1(J2D_TRACE_INFO, "VKSD_ConfigureWindowSurface(%p): available swapchain formats:", vkwinsdo);
    for (uint32_t i = 0; i < formatCount; i++) {
        J2dRlsTraceLn2(J2D_TRACE_INFO, "    format=%d, colorSpace=%d", formats[i].format, formats[i].colorSpace);
        // In Vulkan we always work with LINEAR colors (see VKUtil_DecodeJavaColor()).
        // Currently, we only support default VK_COLOR_SPACE_SRGB_NONLINEAR_KHR color space, which means
        // that we must use SRGB image formats, unless we want to do linear -> SRGB conversion manually
        // before presenting the image.
        // This only applies to the swapchain image, and we can use any format for the intermediate rendering
        // target (VKSDOps.image). But in practice this means that for VKSDOps.image we would want to either
        // use *_SRGB, or high-bit-count images, as even R8G8B8A8_UNORM will result in precision loss.

        // WE MUST NOT USE "UNORM" FORMATS WITH "NONLINEAR" COLOR SPACE
        if (formats[i].colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR && (
                formats[i].format == VK_FORMAT_A8B8G8R8_SRGB_PACK32 ||
                formats[i].format == VK_FORMAT_B8G8R8A8_SRGB ||
                formats[i].format == VK_FORMAT_R8G8B8A8_SRGB ||
                formats[i].format == VK_FORMAT_B8G8R8_SRGB ||
                formats[i].format == VK_FORMAT_R8G8B8_SRGB
            )) {
            format = &formats[i];
        }
    }
    if (format == NULL) {
        J2dRlsTraceLn1(J2D_TRACE_ERROR, "VKSD_ConfigureWindowSurface(%p): no suitable format found", vkwinsdo);
        return VK_FALSE;
    }

    uint32_t presentModeCount;
    VK_IF_ERROR(ge->vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, vkwinsdo->surface, &presentModeCount, NULL)) {
        return VK_FALSE;
    }
    VkPresentModeKHR presentModes[presentModeCount];
    VK_IF_ERROR(ge->vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, vkwinsdo->surface, &presentModeCount, presentModes)) {
        return VK_FALSE;
    }
    // FIFO mode is guaranteed to be supported.
    // https://registry.khronos.org/vulkan/specs/1.3-extensions/man/html/VkPresentModeKHR.html
    uint32_t imageCount = 2;
    VkPresentModeKHR presentMode = VK_PRESENT_MODE_FIFO_KHR;
    // MAILBOX makes no sense without at least 3 images and using less memory
    // for swapchain images may be more beneficial than having unlimited FPS.
    // However, if minImageCount is too big anyway, why not use MAILBOX.
    if (capabilities.minImageCount >= 3) {
        for (uint32_t i = 0; i < presentModeCount; i++) {
            if (presentModes[i] == VK_PRESENT_MODE_MAILBOX_KHR) {
                presentMode = VK_PRESENT_MODE_MAILBOX_KHR;
                imageCount = 3;
                break;
            }
        }
    }
    if (imageCount > capabilities.maxImageCount && capabilities.maxImageCount != 0) imageCount = capabilities.maxImageCount;
    else if (imageCount < capabilities.minImageCount) imageCount = capabilities.minImageCount;

    VkSwapchainKHR swapchain;
    VkSwapchainCreateInfoKHR createInfoKhr = {
            .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
            .surface = vkwinsdo->surface,
            .minImageCount = imageCount,
            .imageFormat = format->format,
            .imageColorSpace = format->colorSpace,
            .imageExtent = vkwinsdo->vksdOps.extent,
            .imageArrayLayers = 1,
            .imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT,
            .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
            .queueFamilyIndexCount = 0,
            .pQueueFamilyIndices = NULL,
            .preTransform = capabilities.currentTransform,
            .compositeAlpha = compositeAlpha,
            .presentMode = presentMode,
            .clipped = VK_TRUE,
            .oldSwapchain = vkwinsdo->swapchain
    };

    VK_IF_ERROR(device->vkCreateSwapchainKHR(device->handle, &createInfoKhr, NULL, &swapchain)) {
        return VK_FALSE;
    }
    J2dRlsTraceLn5(J2D_TRACE_INFO, "VKSD_ConfigureWindowSurface(%p): swapchain created, format=%d, presentMode=%d, imageCount=%d, compositeAlpha=%d",
                   vkwinsdo, format->format, presentMode, imageCount, compositeAlpha);

    if (vkwinsdo->swapchain != VK_NULL_HANDLE) {
        // Destroy old swapchain.
        // TODO is it possible that old swapchain is still being presented, can we destroy it right now?
        device->vkDestroySwapchainKHR(device->handle, vkwinsdo->swapchain, NULL);
        J2dRlsTraceLn1(J2D_TRACE_INFO, "VKSD_ConfigureWindowSurface(%p): old swapchain destroyed", vkwinsdo);
    }
    vkwinsdo->swapchain = swapchain;
    vkwinsdo->swapchainDevice = device;
    vkwinsdo->swapchainExtent = vkwinsdo->vksdOps.extent;

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
