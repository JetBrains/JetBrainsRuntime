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

#define VK_USE_PLATFORM_WAYLAND_KHR
#include <jni_util.h>
#include "VKEnv.h"
#include "VKUtil.h"
#include "VKSurfaceData.h"

#define PLATFORM_FUNCTION_TABLE(ENTRY, ...) \
ENTRY(__VA_ARGS__, vkGetPhysicalDeviceWaylandPresentationSupportKHR); \
ENTRY(__VA_ARGS__, vkCreateWaylandSurfaceKHR); \

PLATFORM_FUNCTION_TABLE(DECL_PFN)
static struct wl_display* wl_display;

static VkBool32 WLVK_InitFunctions(VKEnv* vk, PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr) {
    VkBool32 missingAPI = JNI_FALSE;
    PLATFORM_FUNCTION_TABLE(CHECK_PROC_ADDR, missingAPI, vkGetInstanceProcAddr, vk->instance,)
    if (missingAPI) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Required API is missing:");
        PLATFORM_FUNCTION_TABLE(LOG_MISSING_PFN,)
    }
    return !missingAPI;
}

static VkBool32 WLVK_CheckPresentationSupport(VKEnv* vk, VkPhysicalDevice device, uint32_t family) {
    return vkGetPhysicalDeviceWaylandPresentationSupportKHR(device, family, wl_display);
}

static VKPlatformData platformData = {
    .surfaceExtensionName = VK_KHR_WAYLAND_SURFACE_EXTENSION_NAME,
    .initFunctions = WLVK_InitFunctions,
    .checkPresentationSupport = WLVK_CheckPresentationSupport
};

/*
 * Class:     sun_java2d_vulkan_VKEnv
 * Method:    initPlatform
 * Signature: (J)[Lsun/java2d/vulkan/VKDevice;
 */
JNIEXPORT jlong JNICALL
Java_sun_java2d_vulkan_VKEnv_initPlatform(JNIEnv* env, jclass vkenv, jlong nativePtr) {
    wl_display = jlong_to_ptr(nativePtr);
    return ptr_to_jlong(&platformData);
}

static void WLVK_InitSurfaceData(VKWinSDOps* surface, void* data) {
    if (data != NULL) {
        struct wl_surface* wl_surface = data;
        VKEnv* vk = VKEnv_GetInstance();
        VkWaylandSurfaceCreateInfoKHR surfaceCreateInfo = {
            .sType = VK_STRUCTURE_TYPE_WAYLAND_SURFACE_CREATE_INFO_KHR,
            .display = wl_display,
            .surface = wl_surface
        };
        VK_IF_ERROR(vkCreateWaylandSurfaceKHR(vk->instance, &surfaceCreateInfo, NULL, &surface->surface)) {
            VK_UNHANDLED_ERROR();
        }
    }
}

static void WLVK_OnSurfaceResize(VKWinSDOps* surface, VkExtent2D extent) {
    JNIEnv* env = JNU_GetEnv(jvm, JNI_VERSION_1_2);
    JNU_CallMethodByName(env, NULL, surface->vksdOps.sdOps.sdObject, "bufferAttached", "()V");
}

/*
 * Class:     sun_java2d_vulkan_WLVKSurfaceData_WLVKWindowSurfaceData
 * Method:    initOps
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_sun_java2d_vulkan_WLVKWindowSurfaceData_initOps(
        JNIEnv *env, jobject vksd, jint format, jint backgroundRGB) {
    VKSD_CreateSurface(env, vksd, VKSD_WINDOW, format, backgroundRGB, WLVK_OnSurfaceResize);
}

/*
 * Class:     sun_java2d_vulkan_WLVKSurfaceData_WLVKWindowSurfaceData
 * Method:    assignWlSurface
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_sun_java2d_vulkan_WLVKWindowSurfaceData_assignWlSurface(
        JNIEnv *env, jobject vksd, jlong wlSurfacePtr) {
    VKSD_InitWindowSurface(env, vksd, WLVK_InitSurfaceData, jlong_to_ptr(wlSurfacePtr));
}

