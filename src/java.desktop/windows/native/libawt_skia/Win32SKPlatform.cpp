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

#include <array>
#include <atomic>
#include <optional>
#include <stdio.h>
#include <string>

#include <d3d11.h>
#include <dxgi.h>
#include <wrl/client.h>

using Microsoft::WRL::ComPtr;

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
        case Win32SKAPI::Vulkan: return "Vulkan";
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

// ---- the adapter as SKGraphicsAPIFallback's GPU list sees it (SKGPUList.Adapter) ----------------------
// Chromium's lists compare the driver version DXGI reports (UMD version a.b.c.d, as Chromium's GPU
// info collection reads it) and the highest Direct3D 11 feature level of the adapter, both read
// here from the DXGI adapter with the PCI IDs the API's adapter has; Vulkan's packed driverVersion
// is not that version. The feature level is probed (D3D11CreateDevice without an output creates no
// device): Dawn's D3D11 device asks for 11.1 at most, Chromium's (ANGLE's) for 12.1.

namespace {

// The feature levels Chromium's GPU info collection probes, highest first.
constexpr std::array PROBED_FEATURE_LEVELS = {
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
std::string dottedVersion(const LARGE_INTEGER umdVersion) {
    char text[VERSION_TEXT_SIZE];
    snprintf(text, sizeof(text), "%u.%u.%u.%u", static_cast<unsigned>(HIWORD(umdVersion.HighPart)),
             static_cast<unsigned>(LOWORD(umdVersion.HighPart)), static_cast<unsigned>(HIWORD(umdVersion.LowPart)),
             static_cast<unsigned>(LOWORD(umdVersion.LowPart)));
    return text;
}

// The adapter's highest Direct3D 11 feature level as "major.minor"; empty if no device could be probed.
std::string featureLevelOf(IDXGIAdapter1 *adapter) {
    auto level = static_cast<D3D_FEATURE_LEVEL>(0);
    const HRESULT result = D3D11CreateDevice(adapter, D3D_DRIVER_TYPE_UNKNOWN, nullptr, 0, PROBED_FEATURE_LEVELS.data(),
                                             PROBED_FEATURE_LEVELS.size(), D3D11_SDK_VERSION, nullptr, &level,
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
    const auto rtlGetVersion = reinterpret_cast<RtlGetVersionFunction>(GetProcAddress(GetModuleHandleW(L"ntdll.dll"),
                                                                                      "RtlGetVersion"));
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
void dxgiDriverAndFeatureLevel(const uint32_t vendorID, const uint32_t deviceID, std::string *driverVersion,
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
    if (const std::string osVersion = windowsVersion(); !osVersion.empty()) {
        line += " os=" + osVersion;
    }
    if (!featureLevel.empty()) {
        line += " fl=" + featureLevel;
    }
    return line + " name=" + adapter->name;
}
