/*
 * Copyright 2026 JetBrains s.r.o.
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

// The Windows platform layer (Win32SKPlatform.h): the JNI code's entry points and
// the GPU side of SKPlatform.h, each forwarded to the graphics API that made the
// configuration or surface.

#include "Win32SKPlatform.h"

#include "DawnSKPlatform.h"
#include "SKPlatform.h"
#include "VKSKPlatform.h"

#include <atomic>

using namespace skgpu::graphite;

static std::atomic<Win32SKApi> gApi{Win32SKApi::Dawn};

static inline Win32SKConfigInfo *configOf(SKPlatformConfig *config) {
    return reinterpret_cast<Win32SKConfigInfo *>(config);
}

static inline Win32SKSurface *surfaceOf(SKPlatformSurface *surface) {
    return reinterpret_cast<Win32SKSurface *>(surface);
}

// ---- the JNI code's entry points -------------------------------------------

void Win32SK_SelectApi(Win32SKApi api) {
    gApi.store(api);
}

Win32SKApi Win32SK_SelectedApi() {
    return gApi.load();
}

const char *Win32SK_ApiName(Win32SKApi api) {
    switch (api) {
        case Win32SKApi::Dawn: return "Dawn";
        case Win32SKApi::Vulkan: return "Vulkan";
    }
    return "?";
}

std::string Win32SK_DescribeAdapter() {
    switch (gApi.load()) {
        case Win32SKApi::Dawn: return DawnSK_DescribeAdapter();
        case Win32SKApi::Vulkan: return VKSK_DescribeAdapter();
    }
    return std::string();
}

Win32SKConfigInfo *Win32SK_CreateConfig() {
    switch (gApi.load()) {
        case Win32SKApi::Dawn: return DawnSK_CreateConfig();
        case Win32SKApi::Vulkan: return VKSK_CreateConfig();
    }
    return nullptr;
}

void Win32SK_DisposeConfig(Win32SKConfigInfo *config) {
    if (config == nullptr) {
        return;
    }
    switch (config->api) {
        case Win32SKApi::Dawn: DawnSK_DisposeConfig(static_cast<DawnSKConfigInfo *>(config)); return;
        case Win32SKApi::Vulkan: VKSK_DisposeConfig(static_cast<VKSKConfigInfo *>(config)); return;
    }
}

bool Win32SK_IsLost(const Win32SKConfigInfo *config) {
    if (config == nullptr) {
        return true;
    }
    switch (config->api) {
        case Win32SKApi::Dawn: return DawnSK_IsLost(static_cast<const DawnSKConfigInfo *>(config));
        case Win32SKApi::Vulkan: return VKSK_IsLost(static_cast<const VKSKConfigInfo *>(config));
    }
    return true;
}

std::string Win32SK_ConfigDescription(const Win32SKConfigInfo *config) {
    if (config == nullptr) {
        return std::string();
    }
    switch (config->api) {
        case Win32SKApi::Dawn: return static_cast<const DawnSKConfigInfo *>(config)->description;
        case Win32SKApi::Vulkan: return static_cast<const VKSKConfigInfo *>(config)->description;
    }
    return std::string();
}

void Win32SK_SimulateDeviceLoss(bool persistent) {
    switch (gApi.load()) {
        case Win32SKApi::Dawn: DawnSK_SimulateDeviceLoss(persistent); return;
        case Win32SKApi::Vulkan: VKSK_SimulateDeviceLoss(persistent); return;
    }
}

Win32SKSurface *Win32SK_CreateSurface(Win32SKConfigInfo *config, HWND hwnd, uint32_t width, uint32_t height,
                                      Win32SKSurface *previous, bool opaque) {
    if (config == nullptr || (previous != nullptr && previous->api != config->api)) {
        return nullptr;   // a swap chain cannot move between APIs; never happens, one API per process
    }
    switch (config->api) {
        case Win32SKApi::Dawn:
            return DawnSK_CreateSurface(static_cast<DawnSKConfigInfo *>(config), hwnd, width, height,
                                        static_cast<DawnSKSurface *>(previous), opaque);
        case Win32SKApi::Vulkan:
            return VKSK_CreateSurface(static_cast<VKSKConfigInfo *>(config), hwnd, width, height,
                                      static_cast<VKSKSurface *>(previous), opaque);
    }
    return nullptr;
}

// ---- the GPU side of SKPlatform.h ------------------------------------------

std::unique_ptr<Context> SKPlatform_MakeContext(SKPlatformConfig *config, const ContextOptions &options) {
    Win32SKConfigInfo *c = configOf(config);
    if (c == nullptr) {
        return nullptr;
    }
    switch (c->api) {
        case Win32SKApi::Dawn: return DawnSK_MakeContext(config, options);
        case Win32SKApi::Vulkan: return VKSK_MakeContext(config, options);
    }
    return nullptr;
}

SKPlatformConfig *SKPlatform_ConfigOf(SKPlatformSurface *surface) {
    Win32SKSurface *s = surfaceOf(surface);
    if (s == nullptr) {
        return nullptr;
    }
    switch (s->api) {
        case Win32SKApi::Dawn: return DawnSK_ConfigOf(surface);
        case Win32SKApi::Vulkan: return VKSK_ConfigOf(surface);
    }
    return nullptr;
}

bool SKPlatform_SameDevice(SKPlatformConfig *a, SKPlatformConfig *b) {
    Win32SKConfigInfo *ca = configOf(a);
    Win32SKConfigInfo *cb = configOf(b);
    if (ca == nullptr || cb == nullptr || ca->api != cb->api) {
        return false;
    }
    switch (ca->api) {
        case Win32SKApi::Dawn: return DawnSK_SameDevice(a, b);
        case Win32SKApi::Vulkan: return VKSK_SameDevice(a, b);
    }
    return false;
}

void *SKPlatform_TextureHandle(SKPlatformSurface *surface) {
    Win32SKSurface *s = surfaceOf(surface);
    if (s == nullptr) {
        return nullptr;
    }
    switch (s->api) {
        case Win32SKApi::Dawn: return DawnSK_TextureHandle(surface);
        case Win32SKApi::Vulkan: return VKSK_TextureHandle(surface);
    }
    return nullptr;
}

bool SKPlatform_BackendTexture(SKPlatformSurface *surface, BackendTexture *texture, SkColorType *colorType) {
    Win32SKSurface *s = surfaceOf(surface);
    if (s == nullptr) {
        return false;
    }
    switch (s->api) {
        case Win32SKApi::Dawn: return DawnSK_BackendTexture(surface, texture, colorType);
        case Win32SKApi::Vulkan: return VKSK_BackendTexture(surface, texture, colorType);
    }
    return false;
}

bool SKPlatform_IsWindow(SKPlatformSurface *surface) {
    Win32SKSurface *s = surfaceOf(surface);
    if (s == nullptr) {
        return false;
    }
    switch (s->api) {
        case Win32SKApi::Dawn: return DawnSK_IsWindow(surface);
        case Win32SKApi::Vulkan: return VKSK_IsWindow(surface);
    }
    return false;
}

bool SKPlatform_RequestRedraw(SKPlatformSurface *surface) {
    Win32SKSurface *s = surfaceOf(surface);
    if (s == nullptr) {
        return false;
    }
    switch (s->api) {
        case Win32SKApi::Dawn: return DawnSK_RequestRedraw(surface);
        case Win32SKApi::Vulkan: return false;   // Graphite presents (SKPlatform_AcquirePresentTarget)
    }
    return false;
}

bool SKPlatform_AcquirePresentTarget(SKPlatformSurface *surface, SKPlatformPresentTarget *target) {
    Win32SKSurface *s = surfaceOf(surface);
    if (s == nullptr) {
        return false;
    }
    switch (s->api) {
        case Win32SKApi::Dawn: return false;   // Dawn presents itself (SKPlatform_RequestRedraw)
        case Win32SKApi::Vulkan: return VKSK_AcquirePresentTarget(surface, target);
    }
    return false;
}

bool SKPlatform_PresentTarget(SKPlatformSurface *surface) {
    Win32SKSurface *s = surfaceOf(surface);
    if (s == nullptr) {
        return false;
    }
    switch (s->api) {
        case Win32SKApi::Dawn: return false;
        case Win32SKApi::Vulkan: return VKSK_PresentTarget(surface);
    }
    return false;
}

void SKPlatform_DisposeSurface(SKPlatformSurface *surface) {
    Win32SKSurface *s = surfaceOf(surface);
    if (s == nullptr) {
        return;
    }
    switch (s->api) {
        case Win32SKApi::Dawn: DawnSK_DisposeSurface(surface); return;
        case Win32SKApi::Vulkan: VKSK_DisposeSurface(surface); return;
    }
}

SKPlatformScope::SKPlatformScope() : handle(nullptr) {}

SKPlatformScope::~SKPlatformScope() {}
