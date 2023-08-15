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
#include "VKRenderer.h"

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

bool VKSurfaceData::barrier(VKRecorder& recorder, vk::Image image,
                            vk::PipelineStageFlags2 stage, vk::AccessFlags2 access, vk::ImageLayout layout) {
    // TODO consider write/read access
    if (_lastStage != stage || _lastAccess != access || _layout != layout) {
        vk::ImageMemoryBarrier2 barrier {
                _lastStage, _lastAccess,
                stage, access,
                _layout, layout,
                VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED,
                image, vk::ImageSubresourceRange {vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1}
        };
        recorder.record().pipelineBarrier2(vk::DependencyInfo {{}, {}, {}, barrier});
        _lastStage = stage;
        _lastAccess = access;
        _layout = layout;
        // TODO check write access
        return true;
    } else return false;
}

void VKSwapchainSurfaceData::revalidate(uint32_t w, uint32_t h, uint32_t s) {
    if (*_swapchain && s == scale() && w == width() && h == height() ) {
        J2dTraceLn1(J2D_TRACE_INFO,
                    "VKSwapchainSurfaceData_revalidate is skipped: swapchain(%p)",
                    *_swapchain);
        return;
    }
    VKSurfaceData::revalidate(w, h, s);
    if (!_device || !*_surface) {
        J2dTraceLn2(J2D_TRACE_ERROR,
                    "VKSwapchainSurfaceData_revalidate is skipped: device(%p) surface(%p)",
                    _device, *_surface);
        return;
    }

    vk::SurfaceCapabilitiesKHR surfaceCapabilities = device().getSurfaceCapabilitiesKHR(*_surface);
    vk::Format format = vk::Format::eB8G8R8A8Unorm; // TODO?

    // TODO all these parameters must be checked against device & surface capabilities
    vk::SwapchainCreateInfoKHR swapchainCreateInfo{
            {},
            *_surface,
            surfaceCapabilities.minImageCount,
            format,
            vk::ColorSpaceKHR::eVkColorspaceSrgbNonlinear,
            {width(), height()}, // TODO According to spec we need to use surfaceCapabilities.currentExtent, which is not available at this point (it gives -1)... We'll figure this out later
            1,
            vk::ImageUsageFlagBits::eColorAttachment | vk::ImageUsageFlagBits::eTransferDst,
            vk::SharingMode::eExclusive,
            0,
            nullptr,
            vk::SurfaceTransformFlagBitsKHR::eIdentity,
            vk::CompositeAlphaFlagBitsKHR::eOpaque,
            vk::PresentModeKHR::eImmediate,
            true, *_swapchain
    };

    device().waitIdle(); // TODO proper synchronization in case there are rendering operations for old swapchain in flight
    _images.clear();
    _swapchain = device().createSwapchainKHR(swapchainCreateInfo);
    for (vk::Image image : _swapchain.getImages()) {
        _images.push_back({image, device().createImageView(vk::ImageViewCreateInfo {
                {}, image, vk::ImageViewType::e2D, format, {},
                vk::ImageSubresourceRange {vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1}
        })});
    }
    _currentImage = (uint32_t) -1;
    _freeSemaphore = nullptr;
    // TODO Now we need to repaint our surface... How is it done? No idea
}

VKSurfaceImage VKSwapchainSurfaceData::access(VKRecorder& recorder,
                                              vk::PipelineStageFlags2 stage,
                                              vk::AccessFlags2 access,
                                              vk::ImageLayout layout) {
    // Acquire image
    bool semaphorePending = false;
    if (_currentImage == (uint32_t) -1) {
        if (!*_freeSemaphore) {
            _freeSemaphore = device().createSemaphore({});
        }
        auto img = _swapchain.acquireNextImage(-1, *_freeSemaphore, nullptr);
        vk::resultCheck(img.first, "vk::SwapchainKHR::acquireNextImage");
        _layout = vk::ImageLayout::eUndefined;
        _lastStage = _lastWriteStage = {};
        _lastAccess = _lastWriteAccess = {};
        _currentImage = (int) img.second;
        std::swap(_images[_currentImage].semaphore, _freeSemaphore);
        semaphorePending = true;
    }
    // Insert barrier & semaphore
    auto& current = _images[_currentImage];
    barrier(recorder, current.image, stage, access, layout);
    if (semaphorePending) {
        recorder.waitSemaphore(*current.semaphore,
                               vk::PipelineStageFlagBits::eColorAttachmentOutput | vk::PipelineStageFlagBits::eTransfer);
    }
    return {current.image, *current.view};
}

void VKSwapchainSurfaceData::flush(VKRecorder& recorder) {
    if (_currentImage == (uint32_t) -1) {
        return; // Nothing to flush
    }
    access(recorder, {}, {}, vk::ImageLayout::ePresentSrcKHR);
    auto& current = _images[_currentImage];
    recorder.signalSemaphore(*current.semaphore);
    recorder.flush();
    device().queue().presentKHR(vk::PresentInfoKHR {
            *current.semaphore, *_swapchain, _currentImage
    });
    _currentImage = (uint32_t) -1;
}
