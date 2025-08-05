/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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

#include "jni.h"
#include "WLVKSurfaceData.h"
#include <Trace.h>
#include <SurfaceData.h>
#include "VKSurfaceData.h"
#include "VKBase.h"
#include <jni_util.h>
#include <cstdlib>
#include <string>

extern struct wl_display *wl_display;

extern "C" JNIEXPORT void JNICALL Java_sun_java2d_vulkan_WLVKSurfaceData_initOps
        (JNIEnv *env, jobject vksd, jint width, jint height, jint scale, jint backgroundRGB) {
#ifndef HEADLESS
    J2dTrace(J2D_TRACE_INFO, "Create WLVKSurfaceData with size %d x %d and scale %d\n", width, height, scale);
    width /= scale; // TODO This is incorrect, but we'll deal with this later, we probably need to do something on Wayland side for app-controlled scaling
    height /= scale; // TODO This is incorrect, but we'll deal with this later, we probably need to do something on Wayland side for app-controlled scaling
    auto *sd = new WLVKSurfaceData(width, height, scale, backgroundRGB);
    sd->attachToJavaSurface(env, vksd);
#endif /* !HEADLESS */
}

extern "C" JNIEXPORT void JNICALL
Java_sun_java2d_vulkan_WLVKSurfaceData_assignSurface(JNIEnv *env, jobject wsd, jlong wlSurfacePtr)
{
#ifndef HEADLESS
    auto sd = (WLVKSurfaceData*)SurfaceData_GetOps(env, wsd);
    if (sd == nullptr) {
        return;
    }

    auto wlSurface = (struct wl_surface*)jlong_to_ptr(wlSurfacePtr);
    J2dTraceLn(J2D_TRACE_INFO, "WLVKSurfaceData_assignSurface wl_surface(%p) wl_display(%p)",
               wlSurface, wl_display);

    try {
        sd->validate(wlSurface);
    } catch (std::exception& e) {
        J2dRlsTrace(J2D_TRACE_ERROR, "WLVKSurfaceData_assignSurface: %s\n", e.what());
    }
#endif /* !HEADLESS */
}

extern "C" JNIEXPORT void JNICALL
Java_sun_java2d_vulkan_WLVKSurfaceData_flush(JNIEnv *env, jobject wsd)
{
#ifndef HEADLESS
    J2dTrace(J2D_TRACE_INFO, "WLVKSurfaceData_flush\n");
    // TODO?
#endif /* !HEADLESS */
}

extern "C" JNIEXPORT void JNICALL
Java_sun_java2d_vulkan_WLVKSurfaceData_revalidate(JNIEnv *env, jobject wsd,
                                             jint width, jint height, jint scale)
{
    width /= scale; // TODO This is incorrect, but we'll deal with this later, we probably need to do something on Wayland side for app-controlled scaling
    height /= scale; // TODO This is incorrect, but we'll deal with this later, we probably need to do something on Wayland side for app-controlled scaling
#ifndef HEADLESS
    auto sd = (WLVKSurfaceData*)SurfaceData_GetOps(env, wsd);
    if (sd == nullptr) {
        return;
    }
    J2dTrace(J2D_TRACE_INFO, "WLVKSurfaceData_revalidate to size %d x %d and scale %d\n", width, height, scale);

    try {
        sd->revalidate(width, height, scale);
    } catch (std::exception& e) {
        J2dRlsTrace(J2D_TRACE_ERROR, "WLVKSurfaceData_revalidate: %s\n", e.what());
    }

#endif /* !HEADLESS */
}

void WLVKSurfaceData::validate(wl_surface* wls)
{
    if (wls ==_wl_surface) {
        return;
    }

    auto& device = VKGraphicsEnvironment::graphics_environment()->default_device();
    device.waitIdle(); // TODO wait until device is done with old swapchain
    auto surface = VKGraphicsEnvironment::graphics_environment()->vk_instance()
            .createWaylandSurfaceKHR({{}, wl_display, wls});

    _wl_surface = wls;
    reset(device, std::move(surface));
    revalidate(width(), height(), scale());
}
