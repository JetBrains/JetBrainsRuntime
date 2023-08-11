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

class VKSurfaceData : private SurfaceDataOps {
    std::recursive_mutex   _mutex;
    uint32_t               _width;
    uint32_t               _height;
    uint32_t               _scale;
    uint32_t               _bg_color;
protected:
    VKDevice*              _device;
public:
    VKSurfaceData(JNIEnv *env, jobject javaSurfaceData, uint32_t w, uint32_t h, uint32_t s, uint32_t bgc);
    // No need to move, as object must only be created with "new".
    VKSurfaceData(VKSurfaceData&&) = delete;
    VKSurfaceData& operator=(VKSurfaceData&&) = delete;

    const VKDevice& device() const {
        return *_device;
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
            update();
        }
    }

    virtual ~VKSurfaceData() = default;

    virtual void revalidate(uint32_t w, uint32_t h, uint32_t s) {
        _width = w;
        _height = h;
        _scale = s;
    }

    virtual void update() = 0;
};

class VKSwapchainSurfaceData : public VKSurfaceData {
    vk::raii::SurfaceKHR   _surface;
    vk::raii::SwapchainKHR _swapchain;
    std::vector<vk::Image> _images;
protected:
    void reset(VKDevice& device, vk::raii::SurfaceKHR surface) {
        _images.clear();
        _swapchain = nullptr;
        _surface = std::move(surface);
        _device = &device;
    }
public:
    VKSwapchainSurfaceData(JNIEnv *env, jobject javaSurfaceData, uint32_t w, uint32_t h, uint32_t s, uint32_t bgc)
            : VKSurfaceData(env, javaSurfaceData, w, h, s, bgc), _surface(nullptr), _swapchain(nullptr) {};

    const vk::raii::SurfaceKHR& surface() const {
        return _surface;
    }

    const vk::raii::SwapchainKHR& swapchain() const {
        return _swapchain;
    }

    const std::vector<vk::Image>& images() const {
        return _images;
    }

    virtual void revalidate(uint32_t w, uint32_t h, uint32_t s);

    virtual void update();
};

#endif /* VKSurfaceData_h_Included */
