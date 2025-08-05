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
    J2dTrace(J2D_TRACE_INFO, "Create WLVKSurfaceData with size %d x %d and scale %d\n", width, height, scale);
    VKWinSDOps *vkwinsdo = (VKWinSDOps *)SurfaceData_InitOps(env, vksd, sizeof(VKWinSDOps));
    vkwinsdo->vksdOps.drawableType = VKSD_WINDOW;

    if (vkwinsdo == NULL) {
        JNU_ThrowOutOfMemoryError(env, "Initialization of SurfaceData failed.");
        return;
    }

    WLVKSDOps *wlvksdo = (WLVKSDOps *)malloc(sizeof(WLVKSDOps));

    if (wlvksdo == NULL) {
        JNU_ThrowOutOfMemoryError(env, "creating native WLVK ops");
        return;
    }

    vkwinsdo->privOps = wlvksdo;
    wlvksdo->wl_surface = NULL;
    width /= scale; // TODO This is incorrect, but we'll deal with this later, we probably need to do something on Wayland side for app-controlled scaling
    height /= scale; // TODO This is incorrect, but we'll deal with this later, we probably need to do something on Wayland side for app-controlled scaling
    vkwinsdo->vksdOps.width = width;
    vkwinsdo->vksdOps.height = height;
    vkwinsdo->vksdOps.scale = scale;
    vkwinsdo->vksdOps.bgColor = backgroundRGB;
    vkwinsdo->vksdOps.bgColorUpdated = VK_TRUE;
#endif /* !HEADLESS */
}

JNIEXPORT void JNICALL
Java_sun_java2d_vulkan_WLVKSurfaceData_assignSurface(JNIEnv *env, jobject wsd, jlong wlSurfacePtr)
{
#ifndef HEADLESS
    VKWinSDOps* vkwinsdo = (VKWinSDOps *)SurfaceData_GetOps(env, wsd);
    if (vkwinsdo == NULL) {
        J2dRlsTrace(J2D_TRACE_ERROR, "WLVKSurfaceData_assignSurface: VKSDOps is NULL");
        return;
    }
    WLVKSDOps* wlvksdo = (WLVKSDOps *)vkwinsdo->privOps;
    if (wlvksdo == NULL) {
        J2dRlsTrace(J2D_TRACE_ERROR, "WLVKSurfaceData_assignSurface: WLVKSDOps is NULL");
        return;
    }
    VKGraphicsEnvironment* ge = VKGE_graphics_environment();
    VKLogicalDevice* logicalDevice = ge->currentDevice;
    wlvksdo->wl_surface = (struct wl_surface*)jlong_to_ptr(wlSurfacePtr);

    if (vkwinsdo->surface == VK_NULL_HANDLE) {
        VkWaylandSurfaceCreateInfoKHR surfaceCreateInfo = {};
        surfaceCreateInfo.sType = VK_STRUCTURE_TYPE_WAYLAND_SURFACE_CREATE_INFO_KHR;
        surfaceCreateInfo.display = wl_display;
        surfaceCreateInfo.surface = wlvksdo->wl_surface;

        if (ge->vkCreateWaylandSurfaceKHR(ge->vkInstance,
                                          &surfaceCreateInfo,
                                          NULL,
                                          &vkwinsdo->surface) != VK_SUCCESS) {
            J2dRlsTrace(J2D_TRACE_ERROR, "WLVKSurfaceData_assignSurface: WLVKSDOps is NULL");
            return;
        }
    }

    VKSD_InitImageSurface(logicalDevice, &vkwinsdo->vksdOps);
    VKSD_InitWindowSurface(logicalDevice, vkwinsdo);
    J2dRlsTraceLn(J2D_TRACE_INFO, "WLVKSurfaceData_assignSurface: Created WaylandSurfaceKHR");

    J2dTraceLn(J2D_TRACE_INFO, "WLVKSurfaceData_assignSurface wl_surface(%p) wl_display(%p)", wlvksdo->wl_surface, wl_display);

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
    J2dTrace(J2D_TRACE_INFO, "WLVKSurfaceData_revalidate to size %d x %d and scale %d\n", width, height, scale);
    vksdo->width = width;
    vksdo->height = height;
    vksdo->scale = scale;
#endif /* !HEADLESS */
}
