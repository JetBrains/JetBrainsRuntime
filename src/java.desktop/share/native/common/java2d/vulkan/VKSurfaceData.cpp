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

#include "jni_util.h"
#include "Disposer.h"
#include "Trace.h"
#include "VKSurfaceData.h"
#include "VKRenderer.h"

void VKSurfaceData::attachToJavaSurface(JNIEnv *env, jobject javaSurfaceData) {
    // SurfaceData utility functions operate on C structures and malloc/free,
    // But we are using C++ classes, so set up the disposer manually.
    // This is a C++ analogue of SurfaceData_InitOps / SurfaceData_SetOps.
    jboolean exception = false;
    if (JNU_GetFieldByName(env, &exception, javaSurfaceData, "pData", "J").j == 0 && !exception) {
        jlong ptr = ptr_to_jlong((SurfaceDataOps*) this);
        JNU_SetFieldByName(env, &exception, javaSurfaceData, "pData", "J", ptr);
        /* Register the data for disposal */
        Disposer_AddRecord(env, javaSurfaceData, [](JNIEnv *env, jlong ops) {
            if (ops != 0) {
                auto sd = (SurfaceDataOps*)jlong_to_ptr(ops);
                jobject sdObject = sd->sdObject;
                sd->Dispose(env, sd);
                if (sdObject != nullptr) {
                    env->DeleteWeakGlobalRef(sdObject);
                }
            }
        }, ptr);
    } else if (!exception) {
        throw std::runtime_error("Attempting to set SurfaceData ops twice");
    }
    if (exception) {
        throw std::runtime_error("VKSurfaceData::attachToJavaSurface error");
    }
    sdObject = env->NewWeakGlobalRef(javaSurfaceData);
}

VKSurfaceData::VKSurfaceData(uint32_t w, uint32_t h, uint32_t s, uint32_t bgc)
        : SurfaceDataOps(), _width(w), _height(h), _scale(s), _bg_color(bgc), _device(nullptr) {
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
                            vk::PipelineStageFlags stage, vk::AccessFlags access, vk::ImageLayout layout) {
    // TODO consider write/read access
    if (_lastStage != stage || _lastAccess != access || _layout != layout) {
        if (_device->synchronization2()) {
            vk::ImageMemoryBarrier2KHR barrier {
                    (vk::PipelineStageFlags2KHR) (VkFlags) _lastStage,
                    (vk::AccessFlags2KHR) (VkFlags) _lastAccess,
                    (vk::PipelineStageFlags2KHR) (VkFlags) stage,
                    (vk::AccessFlags2KHR) (VkFlags) access,
                    _layout, layout,
                    VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED,
                    image, vk::ImageSubresourceRange {vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1}
            };
            recorder.record(false).pipelineBarrier2KHR(vk::DependencyInfoKHR {{}, {}, {}, barrier});
        } else {
            vk::ImageMemoryBarrier barrier {
                    _lastAccess, access,
                    _layout, layout,
                    VK_QUEUE_FAMILY_IGNORED, VK_QUEUE_FAMILY_IGNORED,
                    image, vk::ImageSubresourceRange {vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1}
            };
            recorder.record(false).pipelineBarrier(_lastStage, stage, {}, {}, {}, barrier);
        }
        _lastStage = stage;
        _lastAccess = access;
        _layout = layout;
        // TODO check write access
        return true;
    } else return false;
}

void VKSwapchainSurfaceData::revalidate(uint32_t w, uint32_t h, uint32_t s) {
    if (*_swapchain && s == scale() && w == width() && h == height() ) {
        J2dTraceLn(J2D_TRACE_INFO,
                   "VKSwapchainSurfaceData_revalidate is skipped: swapchain(%p)",
                   *_swapchain);
        return;
    }
    VKSurfaceData::revalidate(w, h, s);
    if (!_device || !*_surface) {
        J2dTraceLn(J2D_TRACE_ERROR,
                   "VKSwapchainSurfaceData_revalidate is skipped: device(%p) surface(%p)",
                   _device, *_surface);
        return;
    }

    vk::SurfaceCapabilitiesKHR surfaceCapabilities = device().getSurfaceCapabilitiesKHR(*_surface);
    _format = vk::Format::eB8G8R8A8Unorm; // TODO?

    // TODO all these parameters must be checked against device & surface capabilities
    vk::SwapchainCreateInfoKHR swapchainCreateInfo{
            {},
            *_surface,
            surfaceCapabilities.minImageCount,
            format(),
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
                {}, image, vk::ImageViewType::e2D, format(), {},
                vk::ImageSubresourceRange {vk::ImageAspectFlagBits::eColor, 0, 1, 0, 1}
        })});
        if (!device().dynamicRendering()) {
            _images.back().framebuffer = device().createFramebuffer(vk::FramebufferCreateInfo{
                    /*flags*/           {},
                    /*renderPass*/      *device().pipelines().renderPass,
                    /*attachmentCount*/ 1,
                    /*pAttachments*/    &*_images.back().view,
                    /*width*/           width(),
                    /*height*/          height(),
                    /*layers*/          1
            });
        }
    }
    _currentImage = (uint32_t) -1;
    _freeSemaphore = nullptr;
    // TODO Now we need to repaint our surface... How is it done? No idea
}

VKSurfaceImage VKSwapchainSurfaceData::access(VKRecorder& recorder,
                                              vk::PipelineStageFlags stage,
                                              vk::AccessFlags access,
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
        _lastStage = _lastWriteStage = vk::PipelineStageFlagBits::eTopOfPipe;
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
    return {current.image, *current.view, *current.framebuffer};
}

void VKSwapchainSurfaceData::flush(VKRecorder& recorder) {
    if (_currentImage == (uint32_t) -1) {
        return; // Nothing to flush
    }
    recorder.record(true); // Force flush current render pass before accessing image with present layout.
    access(recorder, vk::PipelineStageFlagBits::eTopOfPipe, {}, vk::ImageLayout::ePresentSrcKHR);
    auto& current = _images[_currentImage];
    recorder.signalSemaphore(*current.semaphore);
    recorder.flush();
    device().queue().presentKHR(vk::PresentInfoKHR {
            *current.semaphore, *_swapchain, _currentImage
    });
    _currentImage = (uint32_t) -1;
}
