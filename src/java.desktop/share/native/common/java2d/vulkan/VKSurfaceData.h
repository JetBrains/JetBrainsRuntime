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

#ifndef VKSurfaceData_h_Included
#define VKSurfaceData_h_Included

#include <mutex>
#include "jni.h"
#include "SurfaceData.h"
#include "VKBase.h"

/**
 * These are shorthand names for the surface type constants defined in
 * VKSurfaceData.java.
 */
#define VKSD_UNDEFINED       sun_java2d_pipe_hw_AccelSurface_UNDEFINED
#define VKSD_WINDOW          sun_java2d_pipe_hw_AccelSurface_WINDOW
#define VKSD_TEXTURE         sun_java2d_pipe_hw_AccelSurface_TEXTURE
#define VKSD_RT_TEXTURE      sun_java2d_pipe_hw_AccelSurface_RT_TEXTURE

class VKRecorder;

struct VKSurfaceImage {
    vk::Image       image;
    vk::ImageView   view;
    vk::Framebuffer framebuffer; // Only if dynamic rendering is off.
};

class VKSurfaceData : private SurfaceDataOps {
    std::recursive_mutex   _mutex;
    uint32_t               _width;
    uint32_t               _height;
    uint32_t               _scale; // TODO Is it needed there at all?
    uint32_t               _bg_color;
protected:
    VKDevice*              _device;
    vk::Format             _format;

    vk::ImageLayout         _layout = vk::ImageLayout::eUndefined;
    // We track any access and write access separately, as read-read access does not need synchronization.
    vk::PipelineStageFlags _lastStage = vk::PipelineStageFlagBits::eTopOfPipe;
    vk::PipelineStageFlags _lastWriteStage = vk::PipelineStageFlagBits::eTopOfPipe;
    vk::AccessFlags        _lastAccess = {};
    vk::AccessFlags        _lastWriteAccess = {};

    /// Insert barrier if needed for given access and layout.
    bool barrier(VKRecorder& recorder, vk::Image image,
                 vk::PipelineStageFlags stage, vk::AccessFlags access, vk::ImageLayout layout);
public:
    VKSurfaceData(uint32_t w, uint32_t h, uint32_t s, uint32_t bgc);
    // No need to move, as object must only be created with "new".
    VKSurfaceData(VKSurfaceData&&) = delete;
    VKSurfaceData& operator=(VKSurfaceData&&) = delete;

    void attachToJavaSurface(JNIEnv *env, jobject javaSurfaceData);

    VKDevice& device() const {
        return *_device;
    }

    vk::Format format() const {
        return _format;
    }

    uint32_t width() const {
        return _width;
    }

    uint32_t height() const {
        return _height;
    }

    uint32_t scale() const {
        return _scale;
    }

    uint32_t bg_color() const {
        return _bg_color;
    }

    void set_bg_color(uint32_t bg_color) {
        if (_bg_color != bg_color) {
            _bg_color = bg_color;
            // TODO now we need to repaint the surface???
        }
    }

    virtual ~VKSurfaceData() = default;

    virtual void revalidate(uint32_t w, uint32_t h, uint32_t s) {
        _width = w;
        _height = h;
        _scale = s;
    }

    /// Prepare image for access (necessary barriers & layout transitions).
    virtual VKSurfaceImage access(VKRecorder& recorder,
                                  vk::PipelineStageFlags stage,
                                  vk::AccessFlags access,
                                  vk::ImageLayout layout) = 0;
    /// Flush all pending changes to the surface, including screen presentation for on-screen surfaces.
    virtual void flush(VKRecorder& recorder) = 0;
};

class VKSwapchainSurfaceData : public VKSurfaceData {
    struct Image {
        vk::Image             image;
        vk::raii::ImageView   view;
        vk::raii::Framebuffer framebuffer = nullptr; // Only if dynamic rendering is off.
        vk::raii::Semaphore   semaphore = nullptr;
    };

    vk::raii::SurfaceKHR   _surface = nullptr;
    vk::raii::SwapchainKHR _swapchain = nullptr;
    std::vector<Image>     _images;
    uint32_t               _currentImage = (uint32_t) -1;
    vk::raii::Semaphore    _freeSemaphore = nullptr;

protected:
    void reset(VKDevice& device, vk::raii::SurfaceKHR surface) {
        _images.clear();
        _swapchain = nullptr;
        _surface = std::move(surface);
        _device = &device;
    }
public:
    VKSwapchainSurfaceData(uint32_t w, uint32_t h, uint32_t s, uint32_t bgc)
            : VKSurfaceData(w, h, s, bgc) {};

    virtual void revalidate(uint32_t w, uint32_t h, uint32_t s);

    virtual VKSurfaceImage access(VKRecorder& recorder,
                                  vk::PipelineStageFlags stage,
                                  vk::AccessFlags access,
                                  vk::ImageLayout layout);
    virtual void flush(VKRecorder& recorder);
};

#endif /* VKSurfaceData_h_Included */
