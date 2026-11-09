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

#include <array>
#include <atomic>
#include <optional>
#include <stdio.h>
#include <string>

#include <d3d11.h>
#include <dxgi.h>
#include <wrl/client.h>

using namespace skgpu::graphite;
using Microsoft::WRL::ComPtr;

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

// ---- the adapter as SKGraphicsAPIFallback's GPU list sees it (SKGPUList.Adapter) ----------------------
// Chromium's lists compare the driver version DXGI reports (UMD version a.b.c.d, as Chromium's GPU
// info collection reads it) and the highest Direct3D 11 feature level of the adapter, both read
// here from the DXGI adapter with the PCI IDs the API's adapter has; Vulkan's packed driverVersion
// is not that version. The feature level is probed (D3D11CreateDevice without an output creates no
// device): Dawn's D3D11 device asks for 11.1 at most, Chromium's (ANGLE's) for 12.1.

namespace {

// The feature levels Chromium's GPU info collection probes, highest first.
constexpr std::array<D3D_FEATURE_LEVEL, 9> PROBED_FEATURE_LEVELS = {
    D3D_FEATURE_LEVEL_12_1, D3D_FEATURE_LEVEL_12_0, D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0,
    D3D_FEATURE_LEVEL_10_1, D3D_FEATURE_LEVEL_10_0, D3D_FEATURE_LEVEL_9_3, D3D_FEATURE_LEVEL_9_2,
    D3D_FEATURE_LEVEL_9_1,
};

// D3D_FEATURE_LEVEL packs major.minor as 0xMm00 (D3D_FEATURE_LEVEL_12_1 = 0xc100): the major in
// bits 12-15, the minor in bits 8-11.
constexpr int FEATURE_LEVEL_MAJOR_SHIFT = 12;
constexpr int FEATURE_LEVEL_MINOR_SHIFT = 8;
constexpr unsigned FEATURE_LEVEL_DIGIT_MASK = 0xf;

// The texts made here: "65535.65535.65535.65535" (a version's four 16-bit words), "15.15" (a feature
// level's two hexadecimal digits) and "vendor=0x%04x device=0x%04x" (two 32-bit IDs), with room to spare.
constexpr size_t VERSION_TEXT_SIZE = 64;
constexpr size_t FEATURE_LEVEL_TEXT_SIZE = 16;
constexpr size_t IDS_TEXT_SIZE = 64;

// The UMD version DXGI reports packs a.b.c.d into the two words of each half of a LARGE_INTEGER
// (IDXGIAdapter::CheckInterfaceSupport).
std::string dottedVersion(LARGE_INTEGER umdVersion) {
    char text[VERSION_TEXT_SIZE];
    snprintf(text, sizeof(text), "%u.%u.%u.%u", (unsigned) HIWORD(umdVersion.HighPart),
             (unsigned) LOWORD(umdVersion.HighPart), (unsigned) HIWORD(umdVersion.LowPart),
             (unsigned) LOWORD(umdVersion.LowPart));
    return text;
}

// The adapter's highest Direct3D 11 feature level as "major.minor"; empty if no device could be probed.
std::string featureLevelOf(IDXGIAdapter1 *adapter) {
    D3D_FEATURE_LEVEL level = (D3D_FEATURE_LEVEL) 0;
    const HRESULT result = D3D11CreateDevice(adapter, D3D_DRIVER_TYPE_UNKNOWN, nullptr, 0, PROBED_FEATURE_LEVELS.data(),
                                             (UINT) PROBED_FEATURE_LEVELS.size(), D3D11_SDK_VERSION, nullptr, &level,
                                             nullptr);
    if (FAILED(result)) {
        return std::string();
    }
    char text[FEATURE_LEVEL_TEXT_SIZE];
    snprintf(text, sizeof(text), "%u.%u", (level >> FEATURE_LEVEL_MAJOR_SHIFT) & FEATURE_LEVEL_DIGIT_MASK,
             (level >> FEATURE_LEVEL_MINOR_SHIFT) & FEATURE_LEVEL_DIGIT_MASK);
    return text;
}

// major.minor.build, as Chromium compares os versions; GetVersionEx would report the manifest's.
std::string windowsVersion() {
    using RtlGetVersionFunction = LONG (WINAPI *)(OSVERSIONINFOW *);
    auto rtlGetVersion = (RtlGetVersionFunction) GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "RtlGetVersion");
    OSVERSIONINFOW versionInfo = {};
    versionInfo.dwOSVersionInfoSize = sizeof(versionInfo);
    if (rtlGetVersion == nullptr || rtlGetVersion(&versionInfo) != 0) {   // 0: STATUS_SUCCESS
        return std::string();
    }
    return std::to_string(versionInfo.dwMajorVersion) + "." + std::to_string(versionInfo.dwMinorVersion) + "." +
           std::to_string(versionInfo.dwBuildNumber);
}

// The DXGI adapter with these PCI IDs: its driver version and feature level, both empty if DXGI does
// not list the adapter (or the factory cannot be made).
void dxgiDriverAndFeatureLevel(uint32_t vendorID, uint32_t deviceID, std::string *driverVersion,
                               std::string *featureLevel) {
    ComPtr<IDXGIFactory1> factory;
    if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory)))) {
        return;
    }
    ComPtr<IDXGIAdapter1> adapter;
    // SUCCEEDED, not "!= DXGI_ERROR_NOT_FOUND": any other failure (a broken or remote DXGI) ends the
    // search without an adapter to dereference
    for (UINT adapterIndex = 0; driverVersion->empty() && SUCCEEDED(factory->EnumAdapters1(adapterIndex, &adapter));
         adapterIndex++) {
        DXGI_ADAPTER_DESC1 adapterDescription;
        LARGE_INTEGER umdVersion;
        if (SUCCEEDED(adapter->GetDesc1(&adapterDescription)) && adapterDescription.VendorId == vendorID
                && adapterDescription.DeviceId == deviceID
                && SUCCEEDED(adapter->CheckInterfaceSupport(__uuidof(IDXGIDevice), &umdVersion))) {
            *driverVersion = dottedVersion(umdVersion);
            *featureLevel = featureLevelOf(adapter.Get());
        }
        adapter.Reset();
    }
}

} // namespace

std::string Win32SKPlatform::adapterForGPUList(Win32SKGraphicsAPI &graphicsAPI) {
    const std::optional<Win32SKAdapterIDs> adapter = graphicsAPI.adapterIDs();
    if (!adapter) {
        return std::string();
    }
    std::string driverVersion, featureLevel;
    dxgiDriverAndFeatureLevel(adapter->vendorID, adapter->deviceID, &driverVersion, &featureLevel);
    char ids[IDS_TEXT_SIZE];
    snprintf(ids, sizeof(ids), "vendor=0x%04x device=0x%04x", adapter->vendorID, adapter->deviceID);
    std::string line = ids;
    if (!driverVersion.empty()) {
        line += " driver=" + driverVersion;
    }
    const std::string osVersion = windowsVersion();
    if (!osVersion.empty()) {
        line += " os=" + osVersion;
    }
    if (!featureLevel.empty()) {
        line += " fl=" + featureLevel;
    }
    return line + " name=" + adapter->name;
}
