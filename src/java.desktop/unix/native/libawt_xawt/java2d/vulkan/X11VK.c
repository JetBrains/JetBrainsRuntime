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
#include "VKSurfaceData.h"
#include "VKUtil.h"
#include <jni_util.h>

#include <X11/Xlib.h>

#define PLATFORM_FUNCTION_TABLE(ENTRY, ...)                                                        \
    ENTRY(__VA_ARGS__, vkGetPhysicalDeviceXlibPresentationSupportKHR);                             \
    ENTRY(__VA_ARGS__, vkCreateXlibSurfaceKHR);

PLATFORM_FUNCTION_TABLE(DECL_PFN)

static VkBool32
X11VK_InitFunctions(VKEnv* vk, PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr)
{
    VkBool32 missingAPI = JNI_FALSE;
    PLATFORM_FUNCTION_TABLE(CHECK_PROC_ADDR, missingAPI, vkGetInstanceProcAddr, vk->instance, )
    if (missingAPI) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Required API is missing:")
            PLATFORM_FUNCTION_TABLE(LOG_MISSING_PFN, )
    }
    return !missingAPI;
}

static Display* x11_dpy;

static VkBool32
X11VK_CheckPresentationSupport(VKEnv* vk, VkPhysicalDevice device, uint32_t family)
{
    VisualID visual = XVisualIDFromVisual(DefaultVisual(x11_dpy, DefaultScreen(x11_dpy)));
    return vkGetPhysicalDeviceXlibPresentationSupportKHR(device, family, x11_dpy, visual);
}

static VKPlatformData platformData = { .surfaceExtensionName = VK_KHR_XLIB_SURFACE_EXTENSION_NAME,
    .initFunctions = X11VK_InitFunctions,
    .checkPresentationSupport = X11VK_CheckPresentationSupport };

/*
 * Class:     sun_java2d_vulkan_VKEnv
 * Method:    initPlatformXlib
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL
Java_sun_java2d_vulkan_VKEnv_initPlatformXlib(JNIEnv* env, jclass vkenv, jlong xlibDisplay)
{
    x11_dpy = (Display*)jlong_to_ptr(xlibDisplay);
    return ptr_to_jlong(&platformData);
}

static void
X11VK_InitSurfaceData(VKWinSDOps* surface, void* data)
{
    Window window = (Window)((uintptr_t)data);

    VKEnv* vk = VKEnv_GetInstance();
    VkXlibSurfaceCreateInfoKHR surfaceCreateInfo = {
        .sType = VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR,
        .dpy = x11_dpy,
        .window = window,
    };
    fprintf(stderr, ">>>>>>>>>>>>> vkCreateXlibSurfaceKHR\n");
    VK_IF_ERROR(vkCreateXlibSurfaceKHR(vk->instance, &surfaceCreateInfo, NULL, &surface->surface))
    {
        VK_UNHANDLED_ERROR();
    }
}

static void
X11VK_OnSurfaceResize(VKWinSDOps* surface, VkExtent2D extent)
{
    JNIEnv* env = JNU_GetEnv(jvm, JNI_VERSION_1_2);
    JNU_CallMethodByName(env, NULL, surface->vksdOps.sdOps.sdObject, "onResize", "()V");
}

/*
 * Class:     sun_java2d_vulkan_X11VKSurfaceData_X11VKWindowSurfaceData
 * Method:    initOps
 * Signature: (JI)V
 */
JNIEXPORT void JNICALL
Java_sun_java2d_vulkan_X11VKWindowSurfaceData_initOps(
    JNIEnv* env, jobject vksd, jlong windowId, jint format)
{
    VKSD_CreateSurface(env, vksd, VKSD_WINDOW, format, 0, X11VK_OnSurfaceResize);
    VKSD_InitWindowSurface(env, vksd, X11VK_InitSurfaceData, (void*)windowId);
}
