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
// configuration or surface (Win32SKGraphicsAPI). The one place that converts
// between SKPlatform.h's opaque types and the configurations and surfaces.

#include "Win32SKPlatform.h"

#include "SKPlatform.h"

#include <atomic>
#include <optional>
#include <string>

using namespace skgpu::graphite;

namespace {

std::atomic<Win32SKGraphicsAPI *> gGraphicsAPI{nullptr};

// SKPlatformConfig and SKPlatformSurface alias Win32SKConfigInfo and Win32SKSurface (Win32SKPlatform.h).
Win32SKConfigInfo *win32ConfigOf(SKPlatformConfig *config) {
    return reinterpret_cast<Win32SKConfigInfo *>(config);
}

SKPlatformConfig *platformConfigOf(Win32SKConfigInfo *config) {
    return reinterpret_cast<SKPlatformConfig *>(config);
}

Win32SKSurface *win32SurfaceOf(SKPlatformSurface *surface) {
    return reinterpret_cast<Win32SKSurface *>(surface);
}

} // namespace

// ---- the JNI code's entry points -------------------------------------------

void Win32SKPlatform::selectGraphicsAPI(Win32SKGraphicsAPI *graphicsAPI) {
    gGraphicsAPI.store(graphicsAPI);
}

Win32SKGraphicsAPI *Win32SKPlatform::selectedGraphicsAPI() {
    return gGraphicsAPI.load();
}

const char *Win32SKPlatform::apiName(Win32SKAPI api) {
    switch (api) {
        case Win32SKAPI::Dawn: return "Dawn";
        case Win32SKAPI::Vulkan: return "Vulkan";
    }
    return "?";
}

Win32SKConfigInfo *Win32SKPlatform::createConfig() {
    Win32SKGraphicsAPI *graphicsAPI = gGraphicsAPI.load();
    return graphicsAPI != nullptr ? graphicsAPI->createConfig() : nullptr;
}

void Win32SKPlatform::disposeConfig(Win32SKConfigInfo *config) {
    if (config != nullptr) {
        config->graphicsAPI->disposeConfig(config);
    }
}

bool Win32SKPlatform::isLost(const Win32SKConfigInfo *config) {
    return config == nullptr || config->graphicsAPI->isLost(config);
}

std::string Win32SKPlatform::configDescription(const Win32SKConfigInfo *config) {
    return config != nullptr ? config->graphicsAPI->configDescription(config) : std::string();
}

void Win32SKPlatform::simulateDeviceLoss(bool persistent) {
    if (Win32SKGraphicsAPI *graphicsAPI = gGraphicsAPI.load(); graphicsAPI != nullptr) {
        graphicsAPI->simulateDeviceLoss(persistent);
    }
}

std::optional<Win32SKClientArea> Win32SKPlatform::clientArea(HWND hwnd, uint32_t surfaceWidth, uint32_t surfaceHeight) {
    RECT windowRect = {}, clientRect = {};
    POINT clientOrigin = { 0, 0 };
    if (!GetWindowRect(hwnd, &windowRect) || !GetClientRect(hwnd, &clientRect) || !ClientToScreen(hwnd, &clientOrigin)) {
        return std::nullopt;
    }
    const LONG insetX = clientOrigin.x - windowRect.left;
    const LONG insetY = clientOrigin.y - windowRect.top;
    Win32SKClientArea area;
    area.insetX = insetX > 0 && (uint32_t) insetX < surfaceWidth ? (uint32_t) insetX : 0;
    area.insetY = insetY > 0 && (uint32_t) insetY < surfaceHeight ? (uint32_t) insetY : 0;
    area.clientWidth = clientRect.right > 0 ? (uint32_t) clientRect.right : surfaceWidth - area.insetX;
    area.clientHeight = clientRect.bottom > 0 ? (uint32_t) clientRect.bottom : surfaceHeight - area.insetY;
    return area;
}

Win32SKSurface *Win32SKPlatform::createSurface(Win32SKConfigInfo *config, HWND hwnd, uint32_t width, uint32_t height,
                                              Win32SKSurface *previous, bool opaque) {
    if (config == nullptr || (previous != nullptr && previous->graphicsAPI != config->graphicsAPI)) {
        return nullptr;   // a swap chain cannot move between APIs; never happens, one API per process
    }
    return config->graphicsAPI->createSurface(config, hwnd, width, height, previous, opaque);
}

// ---- the GPU side of SKPlatform.h ------------------------------------------

std::unique_ptr<Context> SKPlatform::makeContext(SKPlatformConfig *config, const ContextOptions &options) {
    Win32SKConfigInfo *configInfo = win32ConfigOf(config);
    return configInfo != nullptr ? configInfo->graphicsAPI->makeContext(configInfo, options) : nullptr;
}

SKPlatformConfig *SKPlatform::configOf(SKPlatformSurface *surface) {
    Win32SKSurface *win32Surface = win32SurfaceOf(surface);
    return win32Surface != nullptr ? platformConfigOf(win32Surface->graphicsAPI->configOf(win32Surface)) : nullptr;
}

bool SKPlatform::sameDevice(SKPlatformConfig *first, SKPlatformConfig *second) {
    Win32SKConfigInfo *firstInfo = win32ConfigOf(first);
    Win32SKConfigInfo *secondInfo = win32ConfigOf(second);
    if (firstInfo == nullptr || secondInfo == nullptr || firstInfo->graphicsAPI != secondInfo->graphicsAPI) {
        return false;
    }
    return firstInfo->graphicsAPI->sameDevice(firstInfo, secondInfo);
}

bool SKPlatform::isLost(SKPlatformConfig *config) {
    return Win32SKPlatform::isLost(win32ConfigOf(config));
}

void *SKPlatform::textureHandle(SKPlatformSurface *surface) {
    Win32SKSurface *win32Surface = win32SurfaceOf(surface);
    return win32Surface != nullptr ? win32Surface->graphicsAPI->textureHandle(win32Surface) : nullptr;
}

bool SKPlatform::backendTexture(SKPlatformSurface *surface, BackendTexture *texture, SkColorType *colorType) {
    Win32SKSurface *win32Surface = win32SurfaceOf(surface);
    return win32Surface != nullptr && win32Surface->graphicsAPI->backendTexture(win32Surface, texture, colorType);
}

bool SKPlatform::requestRedraw(SKPlatformSurface *surface) {
    Win32SKSurface *win32Surface = win32SurfaceOf(surface);
    return win32Surface != nullptr && win32Surface->graphicsAPI->requestRedraw(win32Surface);
}

bool SKPlatform::acquirePresentTarget(SKPlatformSurface *surface, SKPlatformPresentTarget *target) {
    Win32SKSurface *win32Surface = win32SurfaceOf(surface);
    return win32Surface != nullptr && win32Surface->graphicsAPI->acquirePresentTarget(win32Surface, target);
}

bool SKPlatform::presentTarget(SKPlatformSurface *surface) {
    Win32SKSurface *win32Surface = win32SurfaceOf(surface);
    return win32Surface != nullptr && win32Surface->graphicsAPI->presentTarget(win32Surface);
}

void SKPlatform::disposeSurface(SKPlatformSurface *surface) {
    Win32SKSurface *win32Surface = win32SurfaceOf(surface);
    if (win32Surface != nullptr) {
        win32Surface->graphicsAPI->disposeSurface(win32Surface);
    }
}

SKPlatformScope::SKPlatformScope() : m_handle(nullptr) {}

SKPlatformScope::~SKPlatformScope() {}

// The ClearType tuner's sub-pixel orientation (winuser.h, present from Windows XP on;
// older SDK headers lack the definitions).
#ifndef SPI_GETFONTSMOOTHINGORIENTATION
#define SPI_GETFONTSMOOTHINGORIENTATION 0x2012
#endif
#ifndef FE_FONTSMOOTHINGORIENTATIONBGR
#define FE_FONTSMOOTHINGORIENTATIONBGR 0x0000
#define FE_FONTSMOOTHINGORIENTATIONRGB 0x0001
#endif

// The display's sub-pixel order: the ClearType tuner's orientation
// (SPI_GETFONTSMOOTHINGORIENTATION), as the JDK's lcdglyph.c and the desktop
// hints read it; RGB when the query fails.
bool SKPlatform::lcdOrderBGR() {
    static const bool bgr = [] {
        UINT orientation = FE_FONTSMOOTHINGORIENTATIONRGB;
        return SystemParametersInfo(SPI_GETFONTSMOOTHINGORIENTATION, 0, &orientation, 0)
               && orientation == FE_FONTSMOOTHINGORIENTATIONBGR;
    }();
    return bgr;
}
