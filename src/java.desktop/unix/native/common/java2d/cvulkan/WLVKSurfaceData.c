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

#include <wayland-client.h>
#include <stdlib.h>
#include <string.h>
#include "jni.h"
#include <jni_util.h>
#include <Trace.h>
#include <SurfaceData.h>
#include "VKBase.h"
#include "VKSurfaceData.h"
#include "WLVKSurfaceData.h"

extern struct wl_display *wl_display;

JNIEXPORT void JNICALL Java_sun_java2d_vulkan_WLVKSurfaceData_initOps
(JNIEnv *env, jobject vksd, jint width, jint height, jint scale, jint backgroundRGB) {
#ifndef HEADLESS
    J2dTrace3(J2D_TRACE_INFO, "Create WLVKSurfaceData with size %d x %d and scale %d\n", width, height, scale);
    VKSDOps *vksdo = (VKSDOps *)SurfaceData_InitOps(env, vksd, sizeof(VKSDOps));
    if (vksdo == NULL) {
        JNU_ThrowOutOfMemoryError(env, "Initialization of SurfaceData failed.");
        return;
    }

    WLVKSDOps *wlvksdo = (WLVKSDOps *)malloc(sizeof(WLVKSDOps));

    if (wlvksdo == NULL) {
        JNU_ThrowOutOfMemoryError(env, "creating native WLVK ops");
        return;
    }

    vksdo->privOps = wlvksdo;
    width /= scale; // TODO This is incorrect, but we'll deal with this later, we probably need to do something on Wayland side for app-controlled scaling
    height /= scale; // TODO This is incorrect, but we'll deal with this later, we probably need to do something on Wayland side for app-controlled scaling
    vksdo->width = width;
    vksdo->height = height;
    vksdo->scale = scale;
    vksdo->bg_color = backgroundRGB;
#endif /* !HEADLESS */
}

JNIEXPORT void JNICALL
Java_sun_java2d_vulkan_WLVKSurfaceData_assignSurface(JNIEnv *env, jobject wsd, jlong wlSurfacePtr)
{
#ifndef HEADLESS
    VKSDOps* vksdo = (VKSDOps *)SurfaceData_GetOps(env, wsd);
    if (vksdo == NULL) {
        J2dRlsTrace(J2D_TRACE_ERROR, "WLVKSurfaceData_assignSurface: VKSDOps is NULL");
        return;
    }
    WLVKSDOps* wlvksdo = (WLVKSDOps *)vksdo->privOps;
    if (wlvksdo == NULL) {
        J2dRlsTrace(J2D_TRACE_ERROR, "WLVKSurfaceData_assignSurface: WLVKSDOps is NULL");
        return;
    }
    VKGraphicsEnvironment* ge = VKGE_graphics_environment();
    wlvksdo->wl_surface = (struct wl_surface*)jlong_to_ptr(wlSurfacePtr);
    VkWaylandSurfaceCreateInfoKHR surfaceCreateInfo = {};
    surfaceCreateInfo.sType = VK_STRUCTURE_TYPE_WAYLAND_SURFACE_CREATE_INFO_KHR;
    surfaceCreateInfo.display = wl_display;
    surfaceCreateInfo.surface = wlvksdo->wl_surface;

    if (ge->vkCreateWaylandSurfaceKHR(ge->vkInstance,
                                  &surfaceCreateInfo,
                                  NULL,
                                  &vksdo->surface) != VK_SUCCESS)
    {
        J2dRlsTrace(J2D_TRACE_ERROR, "WLVKSurfaceData_assignSurface: WLVKSDOps is NULL");
        return;
    }

    J2dRlsTraceLn(J2D_TRACE_INFO, "WLVKSurfaceData_assignSurface: Created WaylandSurfaceKHR");

    J2dTraceLn2(J2D_TRACE_INFO, "WLVKSurfaceData_assignSurface wl_surface(%p) wl_display(%p)", wlSurface, wl_display);

    VKLogicalDevice* logicalDevice = &ge->devices[ge->enabledDeviceNum];
    VkPhysicalDevice physicalDevice = logicalDevice->physicalDevice;

    ge->vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, vksdo->surface, &vksdo->capabilitiesKhr);
    ge->vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, vksdo->surface, &vksdo->formatsKhrCount, NULL);
    if (vksdo->formatsKhrCount == 0) {
        J2dRlsTrace(J2D_TRACE_ERROR, "No formats for swapchain found\n");
        return;
    }
    vksdo->formatsKhr = calloc(vksdo->formatsKhrCount, sizeof (VkSurfaceFormatKHR));
    ge->vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, vksdo->surface, &vksdo->formatsKhrCount,
                                             vksdo->formatsKhr);

    ge->vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, vksdo->surface,
                                                  &vksdo->presentModeKhrCount, NULL);

    if (vksdo->presentModeKhrCount == 0) {
        J2dRlsTrace(J2D_TRACE_ERROR, "No present modes found\n");
        return;
    }

    vksdo->presentModesKhr = calloc(vksdo->presentModeKhrCount, sizeof (VkPresentModeKHR));
    ge->vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, vksdo->surface, &vksdo->presentModeKhrCount,
                                              vksdo->presentModesKhr);

    VkExtent2D extent = {
            (uint32_t) (vksdo->width),
            (uint32_t) (vksdo->height)
    };

    uint32_t imageCount = vksdo->capabilitiesKhr.minImageCount + 1;
    if (vksdo->capabilitiesKhr.maxImageCount > 0 && imageCount > vksdo->capabilitiesKhr.maxImageCount) {
        imageCount = vksdo->capabilitiesKhr.maxImageCount;
    }
    VkSwapchainCreateInfoKHR  createInfoKhr = {};
    createInfoKhr.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    createInfoKhr.surface = vksdo->surface;
    createInfoKhr.minImageCount =imageCount;
    createInfoKhr.imageFormat = vksdo->formatsKhr[0].format;
    createInfoKhr.imageColorSpace = vksdo->formatsKhr[0].colorSpace;
    createInfoKhr.imageExtent = extent;
    createInfoKhr.imageArrayLayers = 1;
    createInfoKhr.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    createInfoKhr.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    createInfoKhr.queueFamilyIndexCount = 0;
    createInfoKhr.pQueueFamilyIndices = NULL;

    createInfoKhr.preTransform = vksdo->capabilitiesKhr.currentTransform;
    createInfoKhr.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    createInfoKhr.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    createInfoKhr.clipped = VK_TRUE;

    if (ge->vkCreateSwapchainKHR(logicalDevice->device, &createInfoKhr, NULL, &vksdo->swapchainKhr) != VK_SUCCESS) {
        J2dRlsTrace(J2D_TRACE_ERROR, "Cannot create swapchain\n");
        return;
    }

    if (ge->vkGetSwapchainImagesKHR(logicalDevice->device, vksdo->swapchainKhr, &vksdo->swapChainImagesCount, NULL) != VK_SUCCESS) {
        J2dRlsTrace(J2D_TRACE_ERROR, "Cannot get swapchain images\n");
        return;
    }

    if (vksdo->swapChainImagesCount == 0) {
        J2dRlsTrace(J2D_TRACE_ERROR, "No swapchain images found\n");
        return;
    }
    vksdo->swapChainImages = calloc(vksdo->swapChainImagesCount, sizeof (VkImage));
    if (ge->vkGetSwapchainImagesKHR(logicalDevice->device, vksdo->swapchainKhr, &vksdo->swapChainImagesCount, vksdo->swapChainImages) != VK_SUCCESS) {
        J2dRlsTrace(J2D_TRACE_ERROR, "Cannot get swapchain images\n");
        return;
    }
    vksdo->swapChainImageFormat = vksdo->formatsKhr[0].format;
    vksdo->swapChainExtent = extent;
#endif /* !HEADLESS */
}

JNIEXPORT void JNICALL
Java_sun_java2d_vulkan_WLVKSurfaceData_flush(JNIEnv *env, jobject wsd)
{
#ifndef HEADLESS
    J2dTrace(J2D_TRACE_INFO, "WLVKSurfaceData_flush\n");
// TODO?
#endif /* !HEADLESS */
}

JNIEXPORT void JNICALL
Java_sun_java2d_vulkan_WLVKSurfaceData_revalidate(JNIEnv *env, jobject wsd,
        jint width, jint height, jint scale)
{
    width /= scale; // TODO This is incorrect, but we'll deal with this later, we probably need to do something on Wayland side for app-controlled scaling
    height /= scale; // TODO This is incorrect, but we'll deal with this later, we probably need to do something on Wayland side for app-controlled scaling
#ifndef HEADLESS
    VKSDOps* vksdo = (VKSDOps*)SurfaceData_GetOps(env, wsd);
    if (vksdo == NULL) {
        return;
    }
    J2dTrace3(J2D_TRACE_INFO, "WLVKSurfaceData_revalidate to size %d x %d and scale %d\n", width, height, scale);
    vksdo->width = width;
    vksdo->height = height;
    vksdo->scale = scale;
#endif /* !HEADLESS */
}
