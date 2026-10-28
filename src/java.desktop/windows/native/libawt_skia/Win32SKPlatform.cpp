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

using namespace skgpu::graphite;

static inline Win32SKConfig *configOf(SKPlatformConfig *config) {
    return reinterpret_cast<Win32SKConfig *>(config);
}

static inline Win32SKSurface *surfaceOf(SKPlatformSurface *surface) {
    return reinterpret_cast<Win32SKSurface *>(surface);
}

// ---- the JNI code's entry points -------------------------------------------

std::string Win32SK_DescribeAdapter() {
    return DawnSK_DescribeAdapter();
}

Win32SKConfig *Win32SK_CreateConfig() {
    return DawnSK_CreateConfig();
}

void Win32SK_DisposeConfig(Win32SKConfig *config) {
    if (config != nullptr && config->api == Win32SKApi::Dawn) {
        DawnSK_DisposeConfig(static_cast<DawnSKConfigInfo *>(config));
    }
}

bool Win32SK_IsLost(const Win32SKConfig *config) {
    if (config == nullptr) {
        return true;
    }
    switch (config->api) {
        case Win32SKApi::Dawn: return DawnSK_IsLost(static_cast<const DawnSKConfigInfo *>(config));
    }
    return true;
}

std::string Win32SK_ConfigDescription(const Win32SKConfig *config) {
    if (config == nullptr) {
        return std::string();
    }
    switch (config->api) {
        case Win32SKApi::Dawn: return static_cast<const DawnSKConfigInfo *>(config)->description;
    }
    return std::string();
}

void Win32SK_SimulateDeviceLoss(bool persistent) {
    DawnSK_SimulateDeviceLoss(persistent);
}

Win32SKSurface *Win32SK_CreateSurface(Win32SKConfig *config, HWND hwnd, uint32_t width, uint32_t height,
                                      Win32SKSurface *previous, bool opaque) {
    if (config == nullptr || (previous != nullptr && previous->api != config->api)) {
        return nullptr;   // a swap chain cannot move between APIs; never happens, one API per process
    }
    switch (config->api) {
        case Win32SKApi::Dawn:
            return DawnSK_CreateSurface(static_cast<DawnSKConfigInfo *>(config), hwnd, width, height,
                                        static_cast<DawnSKSurface *>(previous), opaque);
    }
    return nullptr;
}

// ---- the GPU side of SKPlatform.h ------------------------------------------

std::unique_ptr<Context> SKPlatform_MakeContext(SKPlatformConfig *config, const ContextOptions &options) {
    Win32SKConfig *c = configOf(config);
    if (c == nullptr) {
        return nullptr;
    }
    switch (c->api) {
        case Win32SKApi::Dawn: return DawnSK_MakeContext(config, options);
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
    }
    return nullptr;
}

bool SKPlatform_SameDevice(SKPlatformConfig *a, SKPlatformConfig *b) {
    Win32SKConfig *ca = configOf(a);
    Win32SKConfig *cb = configOf(b);
    if (ca == nullptr || cb == nullptr || ca->api != cb->api) {
        return false;
    }
    switch (ca->api) {
        case Win32SKApi::Dawn: return DawnSK_SameDevice(a, b);
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
    }
}

SKPlatformScope::SKPlatformScope() : handle(nullptr) {}

SKPlatformScope::~SKPlatformScope() {}
