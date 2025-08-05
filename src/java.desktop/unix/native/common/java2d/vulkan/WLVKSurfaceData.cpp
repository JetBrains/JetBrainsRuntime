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
    J2dTrace(J2D_TRACE_INFO, "WLVKSD_Unlock: %p\n", ops);
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
    J2dTrace(J2D_TRACE_INFO, "WLVKSD_Unlock: %p\n", ops);
    pthread_mutex_unlock(&((WLVKSDOps*)vsdo->privOps)->lock);
#endif
}

static void
WLVKSD_Dispose(JNIEnv *env, SurfaceDataOps *ops)
{
#ifndef HEADLESS
    /* ops is assumed non-null as it is checked in SurfaceData_DisposeOps */
    VKSDOps *vsdo = (VKSDOps*)ops;
    J2dTrace(J2D_TRACE_INFO, "WLSD_Dispose %p\n", ops);
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
    J2dRlsTraceLn(J2D_TRACE_INFO, "WLVKSurfaceData_initOps: %p", vsdo);
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
    J2dTraceLn(J2D_TRACE_INFO, "WLVKSurfaceData_assignSurface wl_surface(%p) wl_display(%p)",
               wlSurface, wl_display);

    try {
        wlvksdOps->wlvkSD->validate(wlSurface);
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
    J2dTrace(J2D_TRACE_INFO, "WLVKSurfaceData_revalidate to size %d x %d and scale %d\n", width, height, scale);

    WLVKSDOps *wlvksdOps = (WLVKSDOps*)vsdo->privOps;
    try {
        wlvksdOps->wlvkSD->revalidate(width, height, scale);
        wlvksdOps->wlvkSD->update();
    } catch (std::exception& e) {
        J2dRlsTrace(J2D_TRACE_ERROR, "WLVKSurfaceData_revalidate: %s\n", e.what());
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
        :VKSurfaceData(w, h, s, bgc), _wl_surface(nullptr), _surface_khr(nullptr), _swapchain_khr(nullptr)
{
    J2dTrace(J2D_TRACE_INFO, "Create WLVKSurfaceData with size %d x %d and scale %d\n", w, h, s);
}

void WLVKSurfaceData::validate(wl_surface* wls)
{
    if (wls ==_wl_surface) {
        return;
    }

    _wl_surface = wls;

    vk::WaylandSurfaceCreateInfoKHR createInfoKhr = {
            {}, wl_display, _wl_surface
    };

    _surface_khr =
            VKGraphicsEnvironment::graphics_environment()->vk_instance().createWaylandSurfaceKHR(
                    createInfoKhr);
    revalidate(width(), height(), scale());
    update();
}

void WLVKSurfaceData::revalidate(uint32_t w, uint32_t h, uint32_t s)
{
    if (s == scale() && w == width() && h == height() ) {
        if (!*_surface_khr || *_swapchain_khr) {
            J2dTraceLn(J2D_TRACE_INFO,
                       "WLVKSurfaceData_revalidate is skipped: surface_khr(%p) swapchain_khr(%p)",
                       *_surface_khr, *_swapchain_khr);
            return;
        }
    } else {
        VKSurfaceData::revalidate(w, h, s);
        if (!*_surface_khr) {
            J2dTraceLn(J2D_TRACE_INFO, "WLVKSurfaceData_revalidate is skipped: surface_khr(%p)",
                       *_surface_khr);
            return;
        }
    }

    vk::SwapchainCreateInfoKHR swapchainCreateInfoKhr{
            {},
            *_surface_khr,
            1, vk::Format::eB8G8R8A8Unorm,
            vk::ColorSpaceKHR::eVkColorspaceSrgbNonlinear,
            {width()/scale(), height()/scale()},
            1,
            vk::ImageUsageFlagBits::eColorAttachment | vk::ImageUsageFlagBits::eTransferDst,
            vk::SharingMode::eExclusive,
            0,
            nullptr,
            vk::SurfaceTransformFlagBitsKHR::eIdentity,
            vk::CompositeAlphaFlagBitsKHR::eOpaque,
            vk::PresentModeKHR::eImmediate,
            false, *_swapchain_khr
    };

    auto& device = VKGraphicsEnvironment::graphics_environment()->default_device();
    _swapchain_khr = device.createSwapchainKHR(swapchainCreateInfoKhr);
}

void WLVKSurfaceData::set_bg_color(uint32_t bgc)
{
    if (bg_color() == bgc) {
        return;
    }
    VKSurfaceData::set_bg_color(bgc);
    update();
}

void WLVKSurfaceData::update()
{
    if (!*_swapchain_khr) {
        return;
    }
    auto& device = VKGraphicsEnvironment::graphics_environment()->default_device();
    vk::SemaphoreCreateInfo semInfo = {};
    vk::raii::Semaphore presentCompleteSem = device.createSemaphore(semInfo);

    std::pair<vk::Result, uint32_t> img = _swapchain_khr.acquireNextImage(0, *presentCompleteSem, nullptr);
    if (img.first!=vk::Result::eSuccess) {
        J2dRlsTraceLn(J2D_TRACE_INFO, "failed to get image");
    }
    else {
        J2dRlsTraceLn(J2D_TRACE_INFO, "obtained image");
    }
    auto images = _swapchain_khr.getImages();

    vk::CommandPoolCreateInfo poolInfo{
            {vk::CommandPoolCreateFlagBits::eResetCommandBuffer},
            static_cast<uint32_t>(device.queue_family())
    };

    auto pool = device.createCommandPool(poolInfo);

    vk::CommandBufferAllocateInfo buffInfo{
            *pool, vk::CommandBufferLevel::ePrimary, (uint32_t) 1, nullptr
    };

    auto buffers = device.allocateCommandBuffers(buffInfo);

    vk::CommandBufferBeginInfo begInfo{
    };

    uint32_t alpha = (bg_color() >> 24) & 0xFF;
    uint32_t red = (bg_color() >> 16) & 0xFF;
    uint32_t green = (bg_color() >> 8) & 0xFF;
    uint32_t blue = bg_color() & 0xFF;
    vk::ClearColorValue color = {
            static_cast<float>(red)/255.0f,
            static_cast<float>(green)/255.0f,
            static_cast<float>(blue)/255.0f,
            static_cast<float>(alpha)/255.0f
    };

    std::vector<vk::ImageSubresourceRange> range = {{
                                                            vk::ImageAspectFlagBits::eColor,
                                                            0, 1, 0, 1}};
    buffers[0].begin(begInfo);
    buffers[0].clearColorImage(images[img.second], vk::ImageLayout::eSharedPresentKHR, color, range);
    buffers[0].end();

    vk::SubmitInfo submitInfo{
            nullptr, nullptr, *buffers[0], nullptr
    };

    auto queue = device.getQueue(device.queue_family(), 0);

    queue.submit(submitInfo, nullptr);
    queue.waitIdle();
    vk::PresentInfoKHR presentInfo;
    presentInfo.swapchainCount = 1;
    presentInfo.pSwapchains = &*_swapchain_khr;
    presentInfo.pImageIndices = &(img.second);

    queue.presentKHR(presentInfo);
    queue.waitIdle();
    device.waitIdle();
}