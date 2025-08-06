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

#ifndef VKSurfaceData_h_Included
#define VKSurfaceData_h_Included

#include "SurfaceData.h"
#include "sun_java2d_pipe_hw_AccelSurface.h"
#include "VKUtil.h"

#define VKSD_UNDEFINED       sun_java2d_pipe_hw_AccelSurface_UNDEFINED
#define VKSD_WINDOW          sun_java2d_pipe_hw_AccelSurface_WINDOW
#define VKSD_RT_TEXTURE      sun_java2d_pipe_hw_AccelSurface_RT_TEXTURE
#define VKSD_FORMAT_OPAQUE_BIT ((jint) 0x80000000)

/**
 * The VKSDOps structure describes a native Vulkan surface and contains all
 * information pertaining to the native surface.
 */
struct VKSDOps {
    SurfaceDataOps sdOps;
    jint           drawableType;
    jint           drawableFormat;
    VKDevice*      device;
    VKImage*       image;
    VKImage*       stencil;

    Color          background;
    VkExtent2D     requestedExtent;
    VKDevice*      requestedDevice;

    VKRenderPass*   renderPass;
    ARRAY(VKSDOps*) dependentSurfaces; // Whose pending render passes read from the surface.
    uint64_t        lastTimestamp; // When was this surface last used?
};

typedef void (*VKWinSD_SurfaceResizeCallback)(VKWinSDOps* surface, VkExtent2D extent);
typedef void (*VKWinSD_SurfaceInitCallback)(VKWinSDOps* surface, void* data);

/**
 * The VKWinSDOps structure describes a native Vulkan surface connected with a window.
 */
struct VKWinSDOps {
    VKSDOps        vksdOps;
    VkSurfaceKHR   surface;
    VkSwapchainKHR swapchain;
    ARRAY(VkImage) swapchainImages;
    VKDevice*      swapchainDevice;
    VkExtent2D     swapchainExtent;
    VKWinSD_SurfaceResizeCallback resizeCallback;
};

/**
 * Create a new surface with given parameters.
 * This is intended to be called via JNI and provided with a valid VKSurfaceData object handle.
 * Use SurfaceData_DisposeOps to destroy the surface.
 */
JNIEXPORT VKSDOps* VKSD_CreateSurface(JNIEnv* env, jobject vksd, jint drawableType, jint format, jint backgroundRGB,
                                      VKWinSD_SurfaceResizeCallback resizeCallback);

/**
 * [Re]initialize window surface with a given platform-specific initialization callback.
 * This is intended to be called via JNI and provided with a valid VKSurfaceData object handle.
 */
JNIEXPORT void VKSD_InitWindowSurface(JNIEnv *env, jobject vksd, VKWinSD_SurfaceInitCallback initCallback, void* data);

inline VkBool32 VKSD_IsOpaque(VKSDOps* vksdo) {
    return vksdo->drawableFormat & VKSD_FORMAT_OPAQUE_BIT ? VK_TRUE : VK_FALSE;
}

/**
 * Release all resources of the surface, resetting it to initial state.
 */
void VKSD_ResetSurface(VKSDOps* vksdo);

/**
 * Configure image surface. This [re]initializes the device and surface image.
 */
VkBool32 VKSD_ConfigureImageSurface(VKSDOps* vksdo);

/**
 * [Re]configure stencil attachment of the image surface.
 * VKSD_ConfigureImageSurface must have been called before.
 */
VkBool32 VKSD_ConfigureImageSurfaceStencil(VKSDOps* vksdo);

/**
 * Configure window surface. This [re]initializes the swapchain.
 * VKSD_ConfigureImageSurface must have been called before.
 */
VkBool32 VKSD_ConfigureWindowSurface(VKWinSDOps* vkwinsdo);

#endif /* VKSurfaceData_h_Included */
