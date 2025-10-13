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

#define VK_USE_PLATFORM_XLIB_KHR
#include "VKEnv.h"
#include "VKUtil.h"
#include "VKSurfaceData.h"
#include "sun_java2d_vulkan_VKEnv.h"
#include "sun_java2d_vulkan_X11VKWindowSurfaceData.h"

#include <jni_util.h>
#include <X11/Xlib.h>

#define PLATFORM_FUNCTION_TABLE(ENTRY, ...) \
ENTRY(__VA_ARGS__, vkGetPhysicalDeviceXlibPresentationSupportKHR); \
ENTRY(__VA_ARGS__, vkCreateXlibSurfaceKHR); \

PLATFORM_FUNCTION_TABLE(DECL_PFN)

static Display* dpy;

static VkBool32 X11VK_InitFunctions(VKEnv* vk, PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr) {
    VkBool32 missingAPI = JNI_FALSE;
    PLATFORM_FUNCTION_TABLE(CHECK_PROC_ADDR, missingAPI, vkGetInstanceProcAddr, vk->instance,)
    if (missingAPI) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Required API is missing:");
        PLATFORM_FUNCTION_TABLE(LOG_MISSING_PFN,)
    }
    return !missingAPI;
}

static VkBool32 X11VK_CheckPresentationSupport(VKEnv* vk, VkPhysicalDevice device, uint32_t family) {
    VisualID visual = DefaultVisualOfScreen(DefaultScreenOfDisplay(dpy))->visualid;
    return vkGetPhysicalDeviceXlibPresentationSupportKHR(device, family, dpy, visual);
}

static VKPlatformData platformData = {
    .surfaceExtensionName = VK_KHR_XLIB_SURFACE_EXTENSION_NAME,
    .initFunctions = X11VK_InitFunctions,
    .checkPresentationSupport = X11VK_CheckPresentationSupport,
};

static void X11VK_OnSurfaceResize(VKWinSDOps* surface, VkExtent2D extent) {
    JNIEnv* env = JNU_GetEnv(jvm, JNI_VERSION_1_2);
    JNU_CallMethodByName(env, NULL, surface->vksdOps.sdOps.sdObject, "onResize", "()V");
}

static void X11VK_InitSurfaceData(VKWinSDOps* surface, void* data) {
    if (data != NULL) {
        Window window = (Window)((uintptr_t)data);
        VKEnv* vk = VKEnv_GetInstance();
        VkXlibSurfaceCreateInfoKHR surfaceCreateInfo = {
            .sType = VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR,
            .dpy = dpy,
            .window = window,
        };
        VK_IF_ERROR(vkCreateXlibSurfaceKHR(vk->instance, &surfaceCreateInfo, NULL, &surface->surface)) {
            VK_UNHANDLED_ERROR();
        }
    }
}

/*
 * Class:     sun_java2d_vulkan_VKEnv
 * Method:    initPlatformX11
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL Java_sun_java2d_vulkan_VKEnv_initPlatformX11
  (JNIEnv* env, jclass clazz, jlong nativePtr) {
    dpy = jlong_to_ptr(nativePtr);
    return ptr_to_jlong(&platformData);
}

/*
 * Class:     sun_java2d_vulkan_X11VKWindowSurfaceData
 * Method:    initOps
 * Signature: (II)V
 */
JNIEXPORT void JNICALL Java_sun_java2d_vulkan_X11VKWindowSurfaceData_initOps
  (JNIEnv* env, jobject vksd, jint format, jint backgroundRGB) {
    VKSD_CreateSurface(env, vksd, VKSD_WINDOW, format, backgroundRGB, X11VK_OnSurfaceResize);
}

/*
 * Class:     sun_java2d_vulkan_X11VKWindowSurfaceData
 * Method:    initAsReplacement
 * Signature: (Lsun/java2d/vulkan/X11VKWindowSurfaceData;)V
 */
JNIEXPORT void JNICALL Java_sun_java2d_vulkan_X11VKWindowSurfaceData_initAsReplacement
  (JNIEnv* env, jobject vksd, jobject prev) {
    VKWinSDOps* prevOps = (VKWinSDOps*)SurfaceData_GetOps(env, prev);
    VKWinSDOps* ops = (VKWinSDOps*)SurfaceData_InitOps(env, vksd, sizeof(VKWinSDOps));
    if (ops == NULL) {
        JNU_ThrowOutOfMemoryError(env, "Initialization of VKSDOps failed");
        return;
    }

    VKWinSDOps newOps = *prevOps;
    newOps.vksdOps.sdOps = ops->vksdOps.sdOps;

    VKWinSDOps newPrevOps = {0};
    newPrevOps.vksdOps.sdOps = prevOps->vksdOps.sdOps;

    *prevOps = newPrevOps;
    *ops = newOps;
}

/*
 * Class:     sun_java2d_vulkan_X11VKWindowSurfaceData
 * Method:    assignWindow
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_sun_java2d_vulkan_X11VKWindowSurfaceData_assignWindow
  (JNIEnv* env, jobject vksd, jint window) {
    VKSD_InitWindowSurface(env, vksd, X11VK_InitSurfaceData, (void*)((uintptr_t)window));
}
