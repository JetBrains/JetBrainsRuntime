/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2025, JetBrains s.r.o.. All rights reserved.
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
#include "VKSurfaceData.h"
#include "VKImage.h"
#include "VKEnv.h"

/**
 * Release VKSDOps resources & reset to initial state.
 */
static void VKSD_ResetImageSurface(VKSDOps* vksdo) {
    if (vksdo == NULL) return;

    // DestroyRenderPass also waits while the surface resources are being used by device.
    VKRenderer_DestroyRenderPass(vksdo);

    if (vksdo->device != NULL) {
        VKImage_Destroy(vksdo->device, vksdo->stencil);
        VKImage_Destroy(vksdo->device, vksdo->image);
        vksdo->image = vksdo->stencil = NULL;
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
            VKEnv* vk = VKEnv_GetInstance();
            vk->vkDestroySurfaceKHR(vk->instance, vkwinsdo->surface, NULL);
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
    VKDevice* device = VKEnv_GetInstance()->currentDevice;
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
        // Currently, we only support *_SRGB and *_UNORM formats,
        // as other types may not be trivial to alias for logicOp rendering.
        VkFormat format = VK_FORMAT_B8G8R8A8_UNORM;

        VKImage* image = VKImage_Create(device, vksdo->requestedExtent.width, vksdo->requestedExtent.height,
                                        0, format, VK_IMAGE_TILING_OPTIMAL,
                                        VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                                        VK_IMAGE_USAGE_SAMPLED_BIT,
                                        VK_SAMPLE_COUNT_1_BIT, VKSD_FindImageSurfaceMemoryType);
        VK_RUNTIME_ASSERT(image);
        VKSD_ResetImageSurface(vksdo);
        vksdo->image = image;
        J2dRlsTraceLn(J2D_TRACE_INFO, "VKSD_ConfigureImageSurface(%p): image updated %dx%d", vksdo, image->extent.width, image->extent.height);
    }
    return vksdo->image != NULL;
}

VkBool32 VKSD_ConfigureImageSurfaceStencil(VKSDOps* vksdo) {
    // Check that image is ready.
    if (vksdo->image == NULL) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSD_ConfigureImageSurfaceStencil(%p): image is not ready", vksdo);
        return VK_FALSE;
    }
    // Initialize stencil image.
    if (vksdo->stencil == NULL) {
        vksdo->stencil = VKImage_Create(vksdo->device, vksdo->image->extent.width, vksdo->image->extent.height,
                                        0, VK_FORMAT_S8_UINT, VK_IMAGE_TILING_OPTIMAL,
                                        VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
                                        VK_SAMPLE_COUNT_1_BIT, VKSD_FindImageSurfaceMemoryType);
        VK_RUNTIME_ASSERT(vksdo->stencil);
        J2dRlsTraceLn(J2D_TRACE_INFO, "VKSD_ConfigureImageSurfaceStencil(%p): stencil image updated %dx%d",
                      vksdo, vksdo->stencil->extent.width, vksdo->stencil->extent.height);
    }
    return vksdo->stencil != NULL;
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

    VKEnv* vk = VKEnv_GetInstance();
    VKDevice* device = vkwinsdo->vksdOps.device;
    VkPhysicalDevice physicalDevice = device->physicalDevice;

    VkSurfaceCapabilitiesKHR capabilities;
    VK_IF_ERROR(vk->vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, vkwinsdo->surface, &capabilities)) {
        return VK_FALSE;
    }

    // currentExtent is the current width and height of the surface, or the special value (0xFFFFFFFF, 0xFFFFFFFF)
    // indicating that the surface size will be determined by the extent of a swapchain targeting the surface.
    // https://registry.khronos.org/vulkan/specs/1.3-extensions/man/html/VkSurfaceCapabilitiesKHR.html
    // The behavior is platform-dependent if the image extent does not match the surface’s currentExtent
    // as returned by vkGetPhysicalDeviceSurfaceCapabilitiesKHR.
    // https://registry.khronos.org/vulkan/specs/1.3-extensions/man/html/VkSwapchainCreateInfoKHR.html
    if ((vkwinsdo->vksdOps.image->extent.width != capabilities.currentExtent.width ||
         vkwinsdo->vksdOps.image->extent.height != capabilities.currentExtent.height) &&
         (capabilities.currentExtent.width != 0xFFFFFFFF || capabilities.currentExtent.height != 0xFFFFFFFF)) {
        J2dRlsTraceLn(J2D_TRACE_WARNING,
                      "VKSD_ConfigureWindowSurface(%p): surface size doesn't match, expected=%dx%d, capabilities.currentExtent=%dx%d",
                      vkwinsdo, vkwinsdo->vksdOps.image->extent.width, vkwinsdo->vksdOps.image->extent.height,
                      capabilities.currentExtent.width, capabilities.currentExtent.height);
        return VK_FALSE;
    }

    if (vkwinsdo->vksdOps.image->extent.width  < capabilities.minImageExtent.width  ||
        vkwinsdo->vksdOps.image->extent.height < capabilities.minImageExtent.height ||
        vkwinsdo->vksdOps.image->extent.width  > capabilities.maxImageExtent.width  ||
        vkwinsdo->vksdOps.image->extent.height > capabilities.maxImageExtent.height) {
        J2dRlsTraceLn(J2D_TRACE_WARNING,
                      "VKSD_ConfigureWindowSurface(%p): surface size doesn't fit, expected=%dx%d, "
                      "capabilities.minImageExtent=%dx%d, capabilities.minImageExtent=%dx%d",
                      vkwinsdo, vkwinsdo->vksdOps.image->extent.width, vkwinsdo->vksdOps.image->extent.height,
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
    VK_IF_ERROR(vk->vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, vkwinsdo->surface, &formatCount, NULL)) {
        return VK_FALSE;
    }
    VkSurfaceFormatKHR formats[formatCount];
    VK_IF_ERROR(vk->vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, vkwinsdo->surface, &formatCount, formats)) {
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

    uint32_t presentModeCount;
    VK_IF_ERROR(vk->vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, vkwinsdo->surface, &presentModeCount, NULL)) {
        return VK_FALSE;
    }
    VkPresentModeKHR presentModes[presentModeCount];
    VK_IF_ERROR(vk->vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, vkwinsdo->surface, &presentModeCount, presentModes)) {
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
            .imageExtent = vkwinsdo->vksdOps.image->extent,
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
    J2dRlsTraceLn(J2D_TRACE_INFO, "VKSD_ConfigureWindowSurface(%p): swapchain created, format=%d, presentMode=%d, imageCount=%d, compositeAlpha=%d",
                  vkwinsdo, format->format, presentMode, imageCount, compositeAlpha);
    vkwinsdo->resizeCallback(vkwinsdo, vkwinsdo->vksdOps.image->extent);

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

/*
 * Class:     sun_java2d_vulkan_VKOffScreenSurfaceData
 * Method:    initOps
 * Signature: (II)V
 */
JNIEXPORT void JNICALL Java_sun_java2d_vulkan_VKOffScreenSurfaceData_initOps
        (JNIEnv *env, jobject vksd, jint width, jint height) {
    VKSDOps * sd = (VKSDOps*)SurfaceData_InitOps(env, vksd, sizeof(VKSDOps));
    J2dTraceLn(J2D_TRACE_VERBOSE, "VKOffScreenSurfaceData_initOps(%p)", sd);
    if (sd == NULL) {
        JNU_ThrowOutOfMemoryError(env, "Initialization of SurfaceData failed.");
        return;
    }
    sd->drawableType = VKSD_RT_TEXTURE;
    sd->background = VKUtil_DecodeJavaColor(0);
    VKSD_ResetSurface(sd);
    VKRenderer_ConfigureSurface(sd, (VkExtent2D){width, height});
}

#endif /* !HEADLESS */
