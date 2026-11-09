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

// The Windows platform half of the Skia backend (SKPlatform.h), independent of the
// graphics API Graphite renders through. There is no host pipeline to wrap on
// Windows: the backend owns device, textures and swap chains itself. The JNI code
// (Win32SKGraphicsConfig.cpp, Win32SKSurfaceData.cpp, Win32SKEnvironment.cpp)
// calls only this layer; each configuration and surface carries the graphics API
// that made it (Win32SKGraphicsAPI), and SKPlatform.h's GPU side forwards to that
// (Win32SKPlatform.cpp). Graphite's API is Dawn (DawnSKPlatform.cpp, over Direct3D 11
// or 12) or Vulkan (VKSKPlatform.cpp), one per process: Win32SKEnvironment selects
// the one sun.java2d.skia.graphite.api names (Dawn by default), and the backend does
// not start when it has no adapter. The APIs depend on this layer, not this layer on
// them. Text is the same for every API (SKTextPipe.cpp, shared). SKPlatformConfig and
// SKPlatformSurface alias Win32SKConfigInfo and Win32SKSurface: Win32SKPlatform.cpp
// converts between them, the APIs see only the latter.
//
// With no API selected (before Win32SKEnvironment's start) nothing can be observed:
// createConfig makes no configuration, so there is no surface either, and what takes
// neither (simulateDeviceLoss) does nothing.

#include "SKPlatform.h"

#include <windows.h>

#include <cstdint>
#include <memory>
#include <optional>
#include <string>

class Win32SKGraphicsAPI;

// The graphics APIs Graphite renders through (sun.java2d.skia.graphite.api).
enum class Win32SKAPI { Dawn, Vulkan };

// The first member of every API's configuration (one per Win32SKGraphicsConfig)
// and surface (one per Win32SKSurfaceData): the API that made it.
struct Win32SKConfigInfo {
    Win32SKGraphicsAPI *graphicsAPI;
};

struct Win32SKSurface {
    Win32SKGraphicsAPI *graphicsAPI;
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

// A graphics API Graphite renders through: its configurations and surfaces, and the GPU side of
// SKPlatform.h for them (see there). Implemented by DawnSKGraphicsAPI (DawnSKPlatform.cpp) and
// VKSKGraphicsAPI (VKSKPlatform.cpp), one instance each for the life of the process; a configuration
// or surface it is given is one it made.
class Win32SKGraphicsAPI {
public:
    virtual ~Win32SKGraphicsAPI() = default;

    [[nodiscard]] virtual Win32SKAPI api() const = 0;
    // Name of the adapter the API would use ("<adapter> (D3D11)", "<adapter> (Vulkan)"), empty if
    // none; no device is created.
    [[nodiscard]] virtual std::string describeAdapter() = 0;
    // The device's features as the API reports them (SKEnvironment.deviceFeatures); empty if it reports none.
    [[nodiscard]] virtual std::string deviceFeatures() = 0;

    // A configuration on the API's shared device; null if none can be made.
    [[nodiscard]] virtual Win32SKConfigInfo *createConfig() = 0;
    virtual void disposeConfig(Win32SKConfigInfo *config) = 0;
    [[nodiscard]] virtual bool isLost(const Win32SKConfigInfo *config) = 0;
    // "<adapter> (D3D11|D3D12|Vulkan)".
    [[nodiscard]] virtual std::string configDescription(const Win32SKConfigInfo *config) = 0;
    // Tests: Win32SKPlatform::simulateDeviceLoss.
    virtual void simulateDeviceLoss(bool persistent) = 0;
    // Win32SKPlatform::createSurface; `previous` is null or a surface of this API.
    [[nodiscard]] virtual Win32SKSurface *createSurface(Win32SKConfigInfo *config, HWND hwnd, uint32_t width,
                                                        uint32_t height, Win32SKSurface *previous, bool opaque) = 0;

    // The GPU side of SKPlatform.h for the API's configurations and surfaces (see there).
    [[nodiscard]] virtual std::unique_ptr<skgpu::graphite::Context> makeContext(
            Win32SKConfigInfo *config, const skgpu::graphite::ContextOptions &options) = 0;
    [[nodiscard]] virtual Win32SKConfigInfo *configOf(Win32SKSurface *surface) = 0;
    [[nodiscard]] virtual bool sameDevice(Win32SKConfigInfo *first, Win32SKConfigInfo *second) = 0;
    [[nodiscard]] virtual void *textureHandle(Win32SKSurface *surface) = 0;
    [[nodiscard]] virtual bool backendTexture(Win32SKSurface *surface, skgpu::graphite::BackendTexture *texture,
                                              SkColorType *colorType) = 0;
    // False where Graphite presents (acquirePresentTarget).
    [[nodiscard]] virtual bool requestRedraw(Win32SKSurface *surface) = 0;
    // False where the API presents itself (requestRedraw).
    [[nodiscard]] virtual bool acquirePresentTarget(Win32SKSurface *surface, SKPlatformPresentTarget *target) = 0;
    [[nodiscard]] virtual bool presentTarget(Win32SKSurface *surface) = 0;
    virtual void disposeSurface(Win32SKSurface *surface) = 0;
};

namespace Win32SKPlatform {

// Before anything else (Win32SKEnvironment.startNative): the API of every later
// configuration; none before (no configuration can be made, see above).
void selectGraphicsAPI(Win32SKGraphicsAPI *graphicsAPI);
[[nodiscard]] Win32SKGraphicsAPI *selectedGraphicsAPI();

// "Dawn", "Vulkan": the API's name in what SKEnvironment.runningOn reports.
const char *apiName(Win32SKAPI api);

// A configuration on the selected API's shared device; null if none can be made.
[[nodiscard]] Win32SKConfigInfo *createConfig();
void disposeConfig(Win32SKConfigInfo *config);
[[nodiscard]] bool isLost(const Win32SKConfigInfo *config);

// "<adapter> (D3D11|D3D12|Vulkan)", for SKGraphicsConfig.SKContextCapabilities.
std::string configDescription(const Win32SKConfigInfo *config);

// Tests: mark the devices lost as a driver reset would; persistent also loses every new device
// once it has a window surface (Win32SKGraphicsConfig.simulateDeviceLoss).
void simulateDeviceLoss(bool persistent);

// Where the client area of the window lies in a surface of the given size (Win32SKClientArea); empty if the
// HWND is gone.
[[nodiscard]] std::optional<Win32SKClientArea> clientArea(HWND hwnd, uint32_t surfaceWidth, uint32_t surfaceHeight);

// The texture Skia renders into, plus the swap chain of the window's client area if
// hwnd is non-null; null on failure. A window surface takes the swap chain over from
// `previous`, the surface it replaces (DXGI allows one flip-model swap chain per HWND
// and AWT replaces a window's surface on every bounds change).
[[nodiscard]] Win32SKSurface *createSurface(Win32SKConfigInfo *config, HWND hwnd, uint32_t width, uint32_t height,
                                           Win32SKSurface *previous, bool opaque);

} // namespace Win32SKPlatform

#endif /* Win32SKPlatform_h_Included */
