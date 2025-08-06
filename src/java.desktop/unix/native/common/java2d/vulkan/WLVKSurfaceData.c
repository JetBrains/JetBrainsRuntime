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

#include <jni_util.h>
#include <Trace.h>
#include "VKEnv.h"
#include "VKUtil.h"
#include "VKSurfaceData.h"

static void WLVKSurfaceData_OnResize(VKWinSDOps* surface, VkExtent2D extent) {
    JNIEnv* env = (JNIEnv*)JNU_GetEnv(jvm, JNI_VERSION_1_2);
    JNU_CallMethodByName(env, NULL, surface->vksdOps.sdOps.sdObject, "bufferAttached", "()V");
}

/*
 * Class:     sun_java2d_vulkan_WLVKSurfaceData_WLVKWindowSurfaceData
 * Method:    initOps
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_sun_java2d_vulkan_WLVKWindowSurfaceData_initOps(
        JNIEnv *env, jobject vksd, jint format, jint backgroundRGB) {
    J2dTraceLn(J2D_TRACE_VERBOSE, "WLVKWindowsSurfaceData_initOps(%p)", vksd);
    VKWinSDOps* sd = (VKWinSDOps*)SurfaceData_InitOps(env, vksd, sizeof(VKWinSDOps));
    if (sd == NULL) {
        JNU_ThrowOutOfMemoryError(env, "Initialization of SurfaceData failed.");
        return;
    }
    sd->vksdOps.drawableType = VKSD_WINDOW;
    sd->vksdOps.drawableFormat = format;
    sd->vksdOps.background = VKUtil_DecodeJavaColor(backgroundRGB, ALPHA_TYPE_STRAIGHT);
    sd->resizeCallback = WLVKSurfaceData_OnResize;
    VKSD_ResetSurface(&sd->vksdOps);
}

/*
 * Class:     sun_java2d_vulkan_WLVKSurfaceData_WLVKWindowSurfaceData
 * Method:    assignWlSurface
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_sun_java2d_vulkan_WLVKWindowSurfaceData_assignWlSurface(
        JNIEnv *env, jobject vksd, jlong wlSurfacePtr)
{
    VKWinSDOps* sd = (VKWinSDOps*)SurfaceData_GetOps(env, vksd);
    J2dRlsTraceLn(J2D_TRACE_INFO, "WLVKWindowsSurfaceData_assignWlSurface(%p): wl_surface=%p",
                  (void*)sd, wlSurfacePtr);

    if (sd == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
                      "WLVKWindowSurfaceData_assignWlSurface(%p): VKWinSDOps is NULL", vksd);
        VK_UNHANDLED_ERROR();
    }

    if (sd->surface != VK_NULL_HANDLE) {
        VKSD_ResetSurface(&sd->vksdOps);
        J2dRlsTraceLn(J2D_TRACE_INFO,
                      "WLVKWindowSurfaceData_assignWlSurface(%p): surface reset", vksd);
    }

    struct wl_surface* wl_surface = (struct wl_surface*)jlong_to_ptr(wlSurfacePtr);

    if (wl_surface != NULL) {
        VKEnv* vk = VKEnv_GetInstance();
        VkWaylandSurfaceCreateInfoKHR surfaceCreateInfo = {
                .sType = VK_STRUCTURE_TYPE_WAYLAND_SURFACE_CREATE_INFO_KHR,
                .display = vk->waylandDisplay,
                .surface = wl_surface
        };
        VK_IF_ERROR(vk->vkCreateWaylandSurfaceKHR(vk->instance, &surfaceCreateInfo, NULL, &sd->surface)) {
            VK_UNHANDLED_ERROR();
        }
        J2dRlsTraceLn(J2D_TRACE_INFO,
                      "WLVKWindowSurfaceData_assignWlSurface(%p): surface created", vksd);
        // Swapchain will be created later after CONFIGURE_SURFACE.
    }
}
