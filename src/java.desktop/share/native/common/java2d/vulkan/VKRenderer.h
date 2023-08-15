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

#ifndef VKRenderer_h_Included
#define VKRenderer_h_Included
#ifdef __cplusplus

#include "VKBase.h"
#include "VKSurfaceData.h"

static std::vector<vk::raii::CommandBuffer> usedCommandBuffers; // TODO We just dump the buffers there, but we need to reuse them

class VKRecorder{
    const VKDevice                     *_device;
    vk::raii::CommandBuffer             _commandBuffer = nullptr;
    std::vector<vk::Semaphore>          _waitSemaphores, _signalSemaphores;
    std::vector<vk::PipelineStageFlags> _waitSemaphoreStages;
    VKSurfaceData                      *_currentlyRendering = nullptr;

    const vk::raii::CommandBuffer& getCommandBuffer() {
        if (!*_commandBuffer) {
            _commandBuffer = std::move(_device->allocateCommandBuffers({
                *_device->commandPool(), vk::CommandBufferLevel::ePrimary, 1
            })[0]);
            _commandBuffer.begin({ vk::CommandBufferUsageFlagBits::eOneTimeSubmit });
        }
        return _commandBuffer;
    }

protected:
    const VKDevice* setDevice(const VKDevice *device) {
        if (device != _device) {
            if (_device != nullptr) {
                flush();
            }
            std::swap(_device, device);
        }
        return device;
    }

public:
    void waitSemaphore(vk::Semaphore semaphore, vk::PipelineStageFlags stage) {
        _waitSemaphores.push_back(semaphore);
        _waitSemaphoreStages.push_back(stage);
    }
    void signalSemaphore(vk::Semaphore semaphore) {
        _signalSemaphores.push_back(semaphore);
    }

    const vk::raii::CommandBuffer& record() { // Prepare for ordinary commands
        if (_currentlyRendering != nullptr) {
            _commandBuffer.endRendering();
            _currentlyRendering = nullptr;
            return _commandBuffer;
        } else return getCommandBuffer();
    }

    const vk::raii::CommandBuffer& render(VKSurfaceData& surface,
                                          vk::ClearColorValue* clear = nullptr) { // Prepare for render pass commands
        if (_currentlyRendering != &surface) {
            const vk::raii::CommandBuffer& cb = record();
            VKSurfaceImage i = surface.access(*this,
                                               vk::PipelineStageFlagBits2::eColorAttachmentOutput,
                                               vk::AccessFlagBits2::eColorAttachmentWrite,
                                               vk::ImageLayout::eColorAttachmentOptimal);
            vk::RenderingAttachmentInfo colorAttachmentInfo {
                    i.view, vk::ImageLayout::eColorAttachmentOptimal,
                    vk::ResolveModeFlagBits::eNone, {}, {},
                    clear != nullptr ? vk::AttachmentLoadOp::eClear : vk::AttachmentLoadOp::eLoad,
                    vk::AttachmentStoreOp::eStore, clear != nullptr ? *clear : vk::ClearColorValue()
            };
            cb.beginRendering(vk::RenderingInfo {
                    {}, vk::Rect2D {{0, 0}, {surface.width(), surface.height()}},
                    1, 0, colorAttachmentInfo, {}, {}
            });
            _currentlyRendering = &surface;
            return cb;
        } else {
            const vk::raii::CommandBuffer& cb = getCommandBuffer();
            if (clear != nullptr) {
                cb.clearAttachments(vk::ClearAttachment {vk::ImageAspectFlagBits::eColor, 0, *clear},
                                    vk::ClearRect {vk::Rect2D {{0, 0}, {surface.width(), surface.height()}}, 0, 1});
            }
            return cb;
        }
    }

    void flush() {
        const vk::raii::CommandBuffer& cb = record();
        cb.end();
        _device->queue().submit(vk::SubmitInfo {
                _waitSemaphores, _waitSemaphoreStages, *cb, _signalSemaphores
        }, nullptr);
        usedCommandBuffers.emplace_back(std::move(_commandBuffer));
        _signalSemaphores.clear();
        _waitSemaphores.clear();
        _waitSemaphoreStages.clear();
    }
};

class VKRenderer : private VKRecorder{
    VKSurfaceData *_srcSurface, *_dstSurface;
    float         color[4]; // RGBA

public:
    void setSurfaces(VKSurfaceData& src, VKSurfaceData& dst) {
        if (&src.device() != &dst.device()) {
            throw std::runtime_error("src and dst surfaces use different devices!");
        }
        setDevice(&dst.device());
        _dstSurface = &dst;
        _srcSurface = &src;
    }

    void flushSurface(VKSurfaceData& surface) {
        const VKDevice* old = setDevice(&surface.device());
        surface.flush(*this);
        setDevice(old);
    }

    void fillRect(jint x, jint y, jint w, jint h) {
        // TODO "fill paralellogram" means "fill rect with purple color"
        auto& cb = render(*_dstSurface);
        vk::ClearColorValue clear {color[0], color[1], color[2], color[3]};
        cb.clearAttachments(vk::ClearAttachment {vk::ImageAspectFlagBits::eColor, 0, clear},
                            vk::ClearRect {vk::Rect2D {{x, y}, {(uint32_t) w, (uint32_t) h}}, 0, 1});
    }

    void fillParalellogram(jfloat x11, jfloat y11,
                           jfloat dx21, jfloat dy21,
                           jfloat dx12, jfloat dy12) {}

    void setColor(uint32_t pixel) {
        color[0] = (float) ((pixel >> 16) & 0xff) / 255.0f;
        color[1] = (float) ((pixel >> 8) & 0xff) / 255.0f;
        color[2] = (float) (pixel & 0xff) / 255.0f;
        color[3] = (float) ((pixel >> 24) & 0xff) / 255.0f;
    }
};

#endif //__cplusplus
#endif //VKRenderer_h_Included
