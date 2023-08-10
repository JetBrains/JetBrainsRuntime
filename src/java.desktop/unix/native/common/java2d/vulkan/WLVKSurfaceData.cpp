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

/**
 * This is the implementation of the general surface LockFunc defined in
 * SurfaceData.h.
 */
jint
WLVKSD_Lock(JNIEnv *env,
          SurfaceDataOps *ops,
          SurfaceDataRasInfo *pRasInfo,
          jint lockflags)
{
#ifndef HEADLESS
    VKSDOps *vsdo = (VKSDOps*)ops;
    J2dTrace1(J2D_TRACE_INFO, "WLVKSD_Unlock: %p\n", ops);
    pthread_mutex_lock(&((WLVKSDOps*)vsdo->privOps)->lock);
#endif
    return SD_SUCCESS;
}


static void
WLVKSD_GetRasInfo(JNIEnv *env,
                SurfaceDataOps *ops,
                SurfaceDataRasInfo *pRasInfo)
{
#ifndef HEADLESS
    VKSDOps *vsdo = (VKSDOps*)ops;
#endif
}

static void
WLVKSD_Unlock(JNIEnv *env,
            SurfaceDataOps *ops,
            SurfaceDataRasInfo *pRasInfo)
{
#ifndef HEADLESS
    VKSDOps *vsdo = (VKSDOps*)ops;
    J2dTrace1(J2D_TRACE_INFO, "WLVKSD_Unlock: %p\n", ops);
    pthread_mutex_unlock(&((WLVKSDOps*)vsdo->privOps)->lock);
#endif
}

static void
WLVKSD_Dispose(JNIEnv *env, SurfaceDataOps *ops)
{
#ifndef HEADLESS
    /* ops is assumed non-null as it is checked in SurfaceData_DisposeOps */
    VKSDOps *vsdo = (VKSDOps*)ops;
    J2dTrace1(J2D_TRACE_INFO, "WLSD_Dispose %p\n", ops);
    WLVKSDOps *wlvksdOps = (WLVKSDOps*)vsdo->privOps;
    pthread_mutex_destroy(&wlvksdOps->lock);
    if (wlvksdOps->wlvkSD !=nullptr) {
        delete wlvksdOps->wlvkSD;
        wlvksdOps->wlvkSD = nullptr;
    }
#endif
}
extern "C" JNIEXPORT void JNICALL Java_sun_java2d_vulkan_WLVKSurfaceData_initOps
        (JNIEnv *env, jclass vksd, jint width, jint height, jint scale, jint backgroundRGB) {
#ifndef HEADLESS
    VKSDOps *vsdo = (VKSDOps*)SurfaceData_InitOps(env, vksd, sizeof(VKSDOps));
    J2dRlsTraceLn1(J2D_TRACE_INFO, "WLVKSurfaceData_initOps: %p", vsdo);
    jboolean hasException;
    if (vsdo == NULL) {
        JNU_ThrowOutOfMemoryError(env, "Initialization of SurfaceData failed.");
        return;
    }

    if (width <= 0) {
        width = 1;
    }
    if (height <= 0) {
        height = 1;
    }

    WLVKSDOps *wlvksdOps = (WLVKSDOps *)malloc(sizeof(WLVKSDOps));

    if (wlvksdOps == NULL) {
        JNU_ThrowOutOfMemoryError(env, "creating native WLVK ops");
        return;
    }

    vsdo->privOps = wlvksdOps;
    vsdo->sdOps.Lock = WLVKSD_Lock;
    vsdo->sdOps.Unlock = WLVKSD_Unlock;
    vsdo->sdOps.GetRasInfo = WLVKSD_GetRasInfo;
    vsdo->sdOps.Dispose = WLVKSD_Dispose;
    pthread_mutexattr_t attr;
    pthread_mutexattr_init(&attr);
    // Recursive mutex is required because blit can be done with both source
    // and destination being the same surface (during scrolling, for example).
    // So WLSD_Lock() should be able to lock the same surface twice in a row.
    pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE_NP);
    pthread_mutex_init(&wlvksdOps->lock, &attr);
    wlvksdOps->wlvkSD = new WLVKSurfaceData(width, height, scale, backgroundRGB);
#endif /* !HEADLESS */
}

extern "C" JNIEXPORT void JNICALL
Java_sun_java2d_vulkan_WLVKSurfaceData_assignSurface(JNIEnv *env, jobject wsd,
                                             jlong wlSurfacePtr)
{
#ifndef HEADLESS
    VKSDOps *vsdo = (VKSDOps*)SurfaceData_GetOps(env, wsd);
    if (vsdo == NULL) {
        return;
    }

    WLVKSDOps *wlvksdOps = (WLVKSDOps*)vsdo->privOps;

    wl_surface* wlSurface = (struct wl_surface*)jlong_to_ptr(wlSurfacePtr);
    J2dTraceLn2(J2D_TRACE_INFO, "WLVKSurfaceData_assignSurface wl_surface(%p) wl_display(%p)",
                   wlSurface, wl_display);

    try {
        wlvksdOps->wlvkSD->validate(wlSurface);
    } catch (std::exception& e) {
        J2dRlsTrace1(J2D_TRACE_ERROR, "WLVKSurfaceData_assignSurface: %s\n", e.what());
    }
#endif /* !HEADLESS */
}

extern "C" JNIEXPORT void JNICALL
Java_sun_java2d_vulkan_WLVKSurfaceData_flush(JNIEnv *env, jobject wsd)
{
#ifndef HEADLESS
    J2dTrace(J2D_TRACE_INFO, "WLVKSurfaceData_flush\n");
#endif /* !HEADLESS */
}

extern "C" JNIEXPORT void JNICALL
Java_sun_java2d_vulkan_WLVKSurfaceData_revalidate(JNIEnv *env, jobject wsd,
                                             jint width, jint height, jint scale)
{
#ifndef HEADLESS
    VKSDOps *vsdo = (VKSDOps*)SurfaceData_GetOps(env, wsd);
    if (vsdo == NULL) {
        return;
    }
    J2dTrace3(J2D_TRACE_INFO, "WLVKSurfaceData_revalidate to size %d x %d and scale %d\n", width, height, scale);

    WLVKSDOps *wlvksdOps = (WLVKSDOps*)vsdo->privOps;
    try {
        wlvksdOps->wlvkSD->revalidate(width, height, scale);
        wlvksdOps->wlvkSD->update();
    } catch (std::exception& e) {
        J2dRlsTrace1(J2D_TRACE_ERROR, "WLVKSurfaceData_revalidate: %s\n", e.what());
    }

#endif /* !HEADLESS */
}

extern "C" JNIEXPORT void JNICALL
JNI_OnUnload(JavaVM *vm, void *reserved) {
#ifndef HEADLESS
    VKGraphicsEnvironment::dispose();
#endif /* !HEADLESS */
}


WLVKSurfaceData::WLVKSurfaceData(uint32_t w, uint32_t h, uint32_t s, uint32_t bgc)
        : VKSwapchainSurfaceData(w, h, s, bgc), _wl_surface(nullptr)
{
    J2dTrace3(J2D_TRACE_INFO, "Create WLVKSurfaceData with size %d x %d and scale %d\n", w, h, s);
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
    update();
}
