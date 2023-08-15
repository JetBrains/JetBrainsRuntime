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

#include "VKRenderer.h"

VKDevice* VKRecorder::setDevice(VKDevice *device) {
    if (device != _device) {
        if (_device != nullptr) {
            flush();
        }
        std::swap(_device, device);
    }
    return device;
}

void VKRecorder::waitSemaphore(vk::Semaphore semaphore, vk::PipelineStageFlags stage) {
    _waitSemaphores.push_back(semaphore);
    _waitSemaphoreStages.push_back(stage);
}

void VKRecorder::signalSemaphore(vk::Semaphore semaphore) {
    _signalSemaphores.push_back(semaphore);
}

const vk::raii::CommandBuffer& VKRecorder::getCommandBuffer() {
    if (!*_commandBuffer) {
        _commandBuffer = _device->getCommandBuffer();
        _commandBuffer.begin({ vk::CommandBufferUsageFlagBits::eOneTimeSubmit });
    }
    return _commandBuffer;
}

const vk::raii::CommandBuffer& VKRecorder::record() {
    if (_currentlyRendering != nullptr) {
        _commandBuffer.endRendering();
        _currentlyRendering = nullptr;
        return _commandBuffer;
    } else return getCommandBuffer();
}

const vk::raii::CommandBuffer& VKRecorder::render(VKSurfaceData& surface, vk::ClearColorValue* clear) {
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

void VKRecorder::flush() {
    if (!*_commandBuffer) {
        return;
    }
    record().end();
    _device->submitCommandBuffer(std::move(_commandBuffer), _waitSemaphores, _waitSemaphoreStages, _signalSemaphores);
    _signalSemaphores.clear();
    _waitSemaphores.clear();
    _waitSemaphoreStages.clear();
}

// draw ops

void VKRenderer::drawLine(jint x1, jint y1, jint x2, jint y2) {/*TODO*/}
void VKRenderer::drawRect(jint x, jint y, jint w, jint h) {/*TODO*/}
void VKRenderer::drawPoly(/*TODO*/) {/*TODO*/}
void VKRenderer::drawPixel(jint x, jint y) {/*TODO*/}
void VKRenderer::drawScanlines(/*TODO*/) {/*TODO*/}
void VKRenderer::drawParallelogram(jfloat x11, jfloat y11,
                                   jfloat dx21, jfloat dy21,
                                   jfloat dx12, jfloat dy12,
                                   jfloat lwr21, jfloat lwr12) {/*TODO*/}
void VKRenderer::drawAAParallelogram(jfloat x11, jfloat y11,
                                     jfloat dx21, jfloat dy21,
                                     jfloat dx12, jfloat dy12,
                                     jfloat lwr21, jfloat lwr12) {/*TODO*/}

// fill ops

void VKRenderer::fillRect(jint x, jint y, jint w, jint h) {
    // TODO
    auto& cb = render(*_dstSurface);
    cb.clearAttachments(vk::ClearAttachment {vk::ImageAspectFlagBits::eColor, 0, color},
                        vk::ClearRect {vk::Rect2D {{x, y}, {(uint32_t) w, (uint32_t) h}}, 0, 1});
}
void VKRenderer::fillSpans(/*TODO*/) {/*TODO*/}
void VKRenderer::fillParallelogram(jfloat x11, jfloat y11,
                                   jfloat dx21, jfloat dy21,
                                   jfloat dx12, jfloat dy12) {/*TODO*/}
void VKRenderer::fillAAParallelogram(jfloat x11, jfloat y11,
                                     jfloat dx21, jfloat dy21,
                                     jfloat dx12, jfloat dy12) {/*TODO*/}

// text-related ops

void VKRenderer::drawGlyphList(/*TODO*/) {/*TODO*/}

// copy-related ops

void VKRenderer::copyArea(jint x, jint y, jint w, jint h, jint dx, jint dy) {/*TODO*/}
void VKRenderer::blit(/*TODO*/) {/*TODO*/}
void VKRenderer::surfaceToSwBlit(/*TODO*/) {/*TODO*/}
void VKRenderer::maskFill(/*TODO*/) {/*TODO*/}
void VKRenderer::maskBlit(/*TODO*/) {/*TODO*/}

// state-related ops

void VKRenderer::setRectClip(jint x1, jint y1, jint x2, jint y2) {/*TODO*/}
void VKRenderer::beginShapeClip() {/*TODO*/}
void VKRenderer::setShapeClipSpans(/*TODO*/) {/*TODO*/}
void VKRenderer::endShapeClip() {/*TODO*/}
void VKRenderer::resetClip() {/*TODO*/}
void VKRenderer::setAlphaComposite(/*TODO*/) {/*TODO*/}
void VKRenderer::setXorComposite(/*TODO*/) {/*TODO*/}
void VKRenderer::resetComposite() {/*TODO*/}
void VKRenderer::setTransform(jdouble m00, jdouble m10,
                              jdouble m01, jdouble m11,
                              jdouble m02, jdouble m12) {/*TODO*/}
void VKRenderer::resetTransform() {/*TODO*/}

// context-related ops

void VKRenderer::setSurfaces(VKSurfaceData& src, VKSurfaceData& dst) {
    if (&src.device() != &dst.device()) {
        throw std::runtime_error("src and dst surfaces use different devices!");
    }
    setDevice(&dst.device());
    _dstSurface = &dst;
    _srcSurface = &src;
}
void VKRenderer::setScratchSurface(/*TODO*/) {/*TODO*/}
void VKRenderer::flushSurface(VKSurfaceData& surface) {
    VKDevice* old = setDevice(&surface.device());
    surface.flush(*this);
    setDevice(old);
}
void VKRenderer::disposeSurface(/*TODO*/) {/*TODO*/}
void VKRenderer::disposeConfig(/*TODO*/) {/*TODO*/}
void VKRenderer::invalidateContext() {/*TODO*/}
void VKRenderer::sync() {/*TODO*/}

// multibuffering ops

void VKRenderer::swapBuffers(/*TODO*/) {/*TODO*/}

// paint-related ops

void VKRenderer::resetPaint() {/*TODO*/}
void VKRenderer::setColor(uint32_t pixel) {
    color = pixel;
}
void VKRenderer::setGradientPaint(/*TODO*/) {/*TODO*/}
void VKRenderer::setLinearGradientPaint(/*TODO*/) {/*TODO*/}
void VKRenderer::setRadialGradientPaint(/*TODO*/) {/*TODO*/}
void VKRenderer::setTexturePaint(/*TODO*/) {/*TODO*/}

// BufferedImageOp-related ops

void VKRenderer::enableConvolveOp(/*TODO*/) {/*TODO*/}
void VKRenderer::disableConvolveOp() {/*TODO*/}
void VKRenderer::enableRescaleOp(/*TODO*/) {/*TODO*/}
void VKRenderer::disableRescaleOp() {/*TODO*/}
void VKRenderer::enableLookupOp() {/*TODO*/}
void VKRenderer::disableLookupOp() {/*TODO*/}
