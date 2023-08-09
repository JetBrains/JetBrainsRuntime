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
        :VKSurfaceData(w, h, s, bgc), _wl_surface(nullptr), _surface(nullptr), _swapchain(nullptr)
{
    J2dTrace3(J2D_TRACE_INFO, "Create WLVKSurfaceData with size %d x %d and scale %d\n", w, h, s);
}

void WLVKSurfaceData::validate(wl_surface* wls)
{
    if (wls ==_wl_surface) {
        return;
    }

    _wl_surface = wls;

    _surface = VKGraphicsEnvironment::graphics_environment()->vk_instance()
            .createWaylandSurfaceKHR({{}, wl_display, _wl_surface});
    revalidate(width(), height(), scale());
    update();
}

void WLVKSurfaceData::revalidate(uint32_t w, uint32_t h, uint32_t s)
{
    if (s == scale() && w == width() && h == height() ) {
        if (!*_surface || *_swapchain) {
            J2dTraceLn2(J2D_TRACE_INFO,
                        "WLVKSurfaceData_revalidate is skipped: surface_khr(%p) swapchain_khr(%p)",
                        *_surface, *_swapchain);
            return;
        }
    } else {
        VKSurfaceData::revalidate(w, h, s);
        if (!*_surface) {
            J2dTraceLn1(J2D_TRACE_INFO,"WLVKSurfaceData_revalidate is skipped: surface_khr(%p)",
                        *_surface);
            return;
        }
    }

    _device = &VKGraphicsEnvironment::graphics_environment()->default_device();

    vk::SurfaceCapabilitiesKHR surfaceCapabilities = _device->getSurfaceCapabilitiesKHR(*_surface);

    // TODO all these parameters must be checked against device & surface capabilities
    vk::SwapchainCreateInfoKHR swapchainCreateInfo{
            {},
            *_surface,
            surfaceCapabilities.minImageCount,
            vk::Format::eB8G8R8A8Unorm,
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
            false, *_swapchain
    };

    _swapchain = _device->createSwapchainKHR(swapchainCreateInfo);
    _images = _swapchain.getImages();
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
    if (!*_swapchain) {
        return;
    }
    vk::raii::Semaphore acquireImageSemaphore = _device->createSemaphore({});
    vk::raii::Semaphore clearImageSemaphore = _device->createSemaphore({});

    auto img = _swapchain.acquireNextImage(-1, *acquireImageSemaphore, nullptr);
    vk::resultCheck(img.first, "vk::SwapchainKHR::acquireNextImage");

    vk::CommandPoolCreateInfo poolInfo{ // TODO do not create pools in performance-sensitive places
            {}, static_cast<uint32_t>(_device->queue_family())
    };
    auto pool = _device->createCommandPool(poolInfo);

    vk::CommandBufferAllocateInfo buffInfo{
            *pool, vk::CommandBufferLevel::ePrimary, (uint32_t) 1, nullptr
    };
    auto buffers = _device->allocateCommandBuffers(buffInfo);

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

    buffers[0].begin({});
    buffers[0].pipelineBarrier(vk::PipelineStageFlagBits::eTopOfPipe, vk::PipelineStageFlagBits::eTransfer, {}, {}, {}, {vk::ImageMemoryBarrier {
            {}, vk::AccessFlagBits::eTransferWrite,
            vk::ImageLayout::eUndefined, vk::ImageLayout::eTransferDstOptimal,
            VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED,
            _images[img.second], vk::ImageSubresourceRange {vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1}
    }});
    buffers[0].clearColorImage(_images[img.second], vk::ImageLayout::eTransferDstOptimal, color,
                               vk::ImageSubresourceRange {vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1});
    buffers[0].pipelineBarrier(vk::PipelineStageFlagBits::eTransfer, vk::PipelineStageFlagBits::eBottomOfPipe, {}, {}, {}, {vk::ImageMemoryBarrier {
            vk::AccessFlagBits::eTransferWrite, {},
            vk::ImageLayout::eTransferDstOptimal, vk::ImageLayout::ePresentSrcKHR,
            VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED,
            _images[img.second], vk::ImageSubresourceRange {vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1}
    }});
    buffers[0].end();

    vk::PipelineStageFlags transferStage = vk::PipelineStageFlagBits::eTransfer;
    vk::SubmitInfo submitInfo{
            *acquireImageSemaphore, transferStage, *buffers[0], *clearImageSemaphore
    };

    _device->queue().submit(submitInfo, nullptr);
    _device->queue().presentKHR(vk::PresentInfoKHR {
        *clearImageSemaphore, *_swapchain, img.second
    });
    _device->waitIdle();
}