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

#include "Trace.h"
#include "VKSurfaceData.h"

VKSurfaceData::VKSurfaceData(JNIEnv *env, jobject javaSurfaceData, uint32_t w, uint32_t h, uint32_t s, uint32_t bgc)
        : SurfaceDataOps(), _width(w), _height(h), _scale(s), _bg_color(bgc), _device(nullptr) {
    SurfaceData_SetOps(env, javaSurfaceData, this);
    if (env->ExceptionCheck()) {
        throw std::runtime_error("VKSurfaceData creation error");
    }
    sdObject = env->NewWeakGlobalRef(javaSurfaceData);
    Lock = [](JNIEnv *env, SurfaceDataOps *ops, SurfaceDataRasInfo *rasInfo, jint lockFlags) {
        ((VKSurfaceData*) ops)->_mutex.lock();
        return SD_SUCCESS;
    };
    Unlock = [](JNIEnv *env, SurfaceDataOps *ops, SurfaceDataRasInfo *rasInfo) {
        ((VKSurfaceData*) ops)->_mutex.unlock();
    };
    Dispose = [](JNIEnv *env, SurfaceDataOps *ops) {
        delete (VKSurfaceData*) ops;
    };
}

void VKSwapchainSurfaceData::revalidate(uint32_t w, uint32_t h, uint32_t s) {
    if (*swapchain() && s == scale() && w == width() && h == height() ) {
        J2dTraceLn1(J2D_TRACE_INFO,
                    "VKSwapchainSurfaceData_revalidate is skipped: swapchain(%p)",
                    *swapchain());
        return;
    }
    VKSurfaceData::revalidate(w, h, s);
    if (!_device || !*surface()) {
        J2dTraceLn2(J2D_TRACE_ERROR,
                    "VKSwapchainSurfaceData_revalidate is skipped: device(%p) surface(%p)",
                    _device, *surface());
        return;
    }

    vk::SurfaceCapabilitiesKHR surfaceCapabilities = device().getSurfaceCapabilitiesKHR(*surface());

    // TODO all these parameters must be checked against device & surface capabilities
    vk::SwapchainCreateInfoKHR swapchainCreateInfo{
            {},
            *surface(),
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
            true, *swapchain()
    };

    device().waitIdle(); // TODO proper synchronization in case there are rendering operations for old swapchain in flight
    _swapchain = device().createSwapchainKHR(swapchainCreateInfo);
    _images = swapchain().getImages();
}

void VKSwapchainSurfaceData::update() {
    if (!*swapchain()) {
        return;
    }
    vk::raii::Semaphore acquireImageSemaphore = device().createSemaphore({});
    vk::raii::Semaphore clearImageSemaphore = device().createSemaphore({});

    auto img = swapchain().acquireNextImage(-1, *acquireImageSemaphore, nullptr);
    vk::resultCheck(img.first, "vk::SwapchainKHR::acquireNextImage");

    vk::CommandPoolCreateInfo poolInfo{ // TODO do not create pools in performance-sensitive places
            {}, static_cast<uint32_t>(device().queue_family())
    };
    auto pool = device().createCommandPool(poolInfo);

    vk::CommandBufferAllocateInfo buffInfo{
            *pool, vk::CommandBufferLevel::ePrimary, (uint32_t) 1, nullptr
    };
    auto buffers = device().allocateCommandBuffers(buffInfo);

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
            images()[img.second], vk::ImageSubresourceRange {vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1}
    }});
    buffers[0].clearColorImage(images()[img.second], vk::ImageLayout::eTransferDstOptimal, color,
                               vk::ImageSubresourceRange {vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1});
    buffers[0].pipelineBarrier(vk::PipelineStageFlagBits::eTransfer, vk::PipelineStageFlagBits::eBottomOfPipe, {}, {}, {}, {vk::ImageMemoryBarrier {
            vk::AccessFlagBits::eTransferWrite, {},
            vk::ImageLayout::eTransferDstOptimal, vk::ImageLayout::ePresentSrcKHR,
            VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED,
            images()[img.second], vk::ImageSubresourceRange {vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1}
    }});
    buffers[0].end();

    vk::PipelineStageFlags transferStage = vk::PipelineStageFlagBits::eTransfer;
    vk::SubmitInfo submitInfo{
            *acquireImageSemaphore, transferStage, *buffers[0], *clearImageSemaphore
    };

    device().queue().submit(submitInfo, nullptr);
    device().queue().presentKHR(vk::PresentInfoKHR {
        *clearImageSemaphore, *swapchain(), img.second
    });
    device().waitIdle();
}