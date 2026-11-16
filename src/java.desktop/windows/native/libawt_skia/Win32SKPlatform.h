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
#ifndef Win32SKPlatform_h_Included
#define Win32SKPlatform_h_Included

// The Windows platform layer of the Skia backend (SKPlatform.h), independent of the
// graphics API Graphite renders through. There is no host pipeline to wrap on
// Windows: the backend owns device, textures and swap chains itself. The JNI code
// (Win32SKGraphicsConfig.cpp, Win32SKSurfaceData.cpp, Win32SKEnvironment.cpp)
// calls only this layer: the selected graphics API (Win32SKGraphicsAPI) makes the
// configurations, a configuration its surfaces, and each implements SKPlatform.h's
// classes for its API. Graphite's API is Dawn (DawnSKPlatform.cpp, over Direct3D 11):
// Win32SKEnvironment selects it, and the backend does not start when it has no adapter
// or its device does not start. The APIs depend on this layer, not this layer on
// them. Text is the same for every API (SKTextPipe.cpp, shared).
//
// Java holds a configuration as the address of its SKPlatformConfig and a platform
// surface as the address of its SKPlatformSurface, as the shared backend reads them;
// every one of them on Windows is a Win32SKConfigInfo or a Win32SKSurface (the JNI
// code converts with static_cast).
//
// With no API selected (before Win32SKEnvironment's start) nothing can be observed:
// createConfig makes no configuration, so there is no surface either, and what takes
// neither (simulateDeviceLoss) does nothing.

#include "SKPlatform.h"

#include <windows.h>

// ReSharper disable once CppUnusedIncludeDirective - uint32_t is used here
#include <cstdint>
#include <optional>
#include <string>

class Win32SKGraphicsAPI;

// The graphics APIs Graphite renders through.
enum class Win32SKAPI { Dawn };

class Win32SKSurface;

// A configuration (one per Win32SKGraphicsConfig), made by the selected graphics API on its
// shared device (Win32SKGraphicsAPI::createConfig).
class Win32SKConfigInfo : public SKPlatformConfig {
public:
    // "<adapter> (D3D11)", for SKGraphicsConfig.SKContextCapabilities.
    [[nodiscard]] virtual std::string description() const = 0;

    // The texture Skia renders into, plus the swap chain of the window's client area if
    // hwnd is non-null; null on failure. A window surface takes the swap chain over from
    // `previous`, the surface it replaces (DXGI allows one flip-model swap chain per HWND
    // and AWT replaces a window's surface on every bounds change); null or a surface of
    // the same API (one API renders per process).
    [[nodiscard]] virtual Win32SKSurface *createSurface(HWND hwnd, uint32_t width, uint32_t height,
                                                        Win32SKSurface *previous, bool opaque) = 0;
};

// A surface (one per Win32SKSurfaceData), made by its configuration.
class Win32SKSurface : public SKPlatformSurface {
public:
    // The API that made it.
    [[nodiscard]] virtual Win32SKAPI api() const = 0;
};

// Where a window's client area lies in the surface that spans the window: a surface
// spans the whole window (AWT's window bounds) and Java paints at the insets, while
// the swap chain covers the client area. The insets are what AwtFrame::GetInsets
// computes too: client origin minus window origin. The window may be mid-resize (AWT
// replaces the surface on every bounds change); whatever does not fit is cut, and the
// next replacement gets the final size. Empty if the HWND is gone.
struct Win32SKClientArea {
    uint32_t insetX = 0;        // where the client area starts in the surface
    uint32_t insetY = 0;
    uint32_t clientWidth = 0;   // the swap chain's size
    uint32_t clientHeight = 0;
};

// A graphics API Graphite renders through: what holds for the whole API, and its configurations
// (which make its surfaces). Implemented by DawnSKGraphicsAPI (DawnSKPlatform.cpp), one instance for
// the life of the process.
class Win32SKGraphicsAPI {
public:
    virtual ~Win32SKGraphicsAPI() = default;

    [[nodiscard]] virtual Win32SKAPI api() const = 0;
    // The device's features as the API reports them (SKEnvironment.deviceFeatures); empty if it reports none.
    [[nodiscard]] virtual std::string deviceFeatures() = 0;

    // A configuration on the API's shared device; null if none can be made.
    [[nodiscard]] virtual Win32SKConfigInfo *createConfig() = 0;
    // Tests: Win32SKPlatform::simulateDeviceLoss.
    virtual void simulateDeviceLoss(bool persistent) = 0;
};

namespace Win32SKPlatform {

// Before anything else (Win32SKEnvironment.startNative): the API of every later
// configuration; none before (no configuration can be made, see above).
void selectGraphicsAPI(Win32SKGraphicsAPI *graphicsAPI);
[[nodiscard]] Win32SKGraphicsAPI *selectedGraphicsAPI();

// "Dawn": the API's name in what SKEnvironment.runningOn reports.
const char *apiName(Win32SKAPI api);

// A configuration on the selected API's shared device; null if none can be made (or no API
// is selected). Deleted by its Java configuration's disposal.
[[nodiscard]] Win32SKConfigInfo *createConfig();

// Tests: mark the devices lost as a driver reset would; persistent also loses every new device
// once it has a window surface (Win32SKGraphicsConfig.simulateDeviceLoss).
void simulateDeviceLoss(bool persistent);

// Where the client area of the window lies in a surface of the given size (Win32SKClientArea); empty if the
// HWND is gone.
[[nodiscard]] std::optional<Win32SKClientArea> clientArea(HWND hwnd, uint32_t surfaceWidth, uint32_t surfaceHeight);

} // namespace Win32SKPlatform

#endif /* Win32SKPlatform_h_Included */
