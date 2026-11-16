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
// The Windows platform layer (Win32SKPlatform.h): the selected graphics API, the JNI
// code's entry points to it, and what SKPlatform.h needs of the whole platform.

#include "Win32SKPlatform.h"

#include "SKPlatform.h"

#include <atomic>
#include <optional>

namespace {

std::atomic<Win32SKGraphicsAPI *> gGraphicsAPI{nullptr};

} // namespace

// ---- the JNI code's entry points -------------------------------------------

void Win32SKPlatform::selectGraphicsAPI(Win32SKGraphicsAPI *graphicsAPI) {
    gGraphicsAPI.store(graphicsAPI);
}

Win32SKGraphicsAPI *Win32SKPlatform::selectedGraphicsAPI() {
    return gGraphicsAPI.load();
}

const char *Win32SKPlatform::apiName(const Win32SKAPI api) {
    switch (api) {
        case Win32SKAPI::Dawn: return "Dawn";
    }
    return "?";
}

Win32SKConfigInfo *Win32SKPlatform::createConfig() {
    Win32SKGraphicsAPI *graphicsAPI = gGraphicsAPI.load();
    return graphicsAPI != nullptr ? graphicsAPI->createConfig() : nullptr;
}

void Win32SKPlatform::simulateDeviceLoss(const bool persistent) {
    if (Win32SKGraphicsAPI *graphicsAPI = gGraphicsAPI.load(); graphicsAPI != nullptr) {
        graphicsAPI->simulateDeviceLoss(persistent);
    }
}

std::optional<Win32SKClientArea> Win32SKPlatform::clientArea(const HWND hwnd, const uint32_t surfaceWidth,
                                                             const uint32_t surfaceHeight) {
    RECT windowRect = {}, clientRect = {};
    POINT clientOrigin = {.x = 0, .y = 0};
    if (!GetWindowRect(hwnd, &windowRect) || !GetClientRect(hwnd, &clientRect) || !ClientToScreen(hwnd, &clientOrigin)) {
        return std::nullopt;
    }
    const LONG insetX = clientOrigin.x - windowRect.left;
    const LONG insetY = clientOrigin.y - windowRect.top;
    Win32SKClientArea area;
    area.insetX = insetX > 0 && static_cast<uint32_t>(insetX) < surfaceWidth ? static_cast<uint32_t>(insetX) : 0;
    area.insetY = insetY > 0 && static_cast<uint32_t>(insetY) < surfaceHeight ? static_cast<uint32_t>(insetY) : 0;
    area.clientWidth = clientRect.right > 0 ? static_cast<uint32_t>(clientRect.right) : surfaceWidth - area.insetX;
    area.clientHeight = clientRect.bottom > 0 ? static_cast<uint32_t>(clientRect.bottom) : surfaceHeight - area.insetY;
    return area;
}

// ---- what SKPlatform.h needs of the whole platform ---------------------------

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
