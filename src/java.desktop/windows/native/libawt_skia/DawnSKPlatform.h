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
#ifndef DawnSKPlatform_h_Included
#define DawnSKPlatform_h_Included

// The Dawn objects behind SKPlatform.h on Windows (Graphite on Dawn over
// Direct3D). There is no host pipeline to wrap here: the Skia backend owns
// device, textures and swap chains itself. SKPlatformConfig and
// SKPlatformSurface alias DawnSKConfigInfo and DawnSKSurface.

#include <windows.h>
#include "webgpu/webgpu_cpp.h"

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>

// The GPU side of a Direct3D backend, shared by all its configurations and
// their surfaces (reference counted), so a surface never dereferences a
// configuration that the Disposer has already freed; `lost` is set by Dawn's
// device-lost callback and ends all rendering and presentation on it, and the
// next configuration replaces the device.
struct DawnSKDevice {
    wgpu::Adapter adapter;
    wgpu::Device device;
    wgpu::Queue queue;
    uint32_t maxTextureSize = 0;
    std::shared_ptr<std::atomic<bool>> lost = std::make_shared<std::atomic<bool>>(false);
};

// One per Win32SKGraphicsConfig.
struct DawnSKConfigInfo {
    std::shared_ptr<DawnSKDevice> gpu;
    std::string description;   // "<adapter> (D3D11)"
};

// One per Win32SKSurfaceData: the texture Skia renders into and, for window
// surfaces, the swap chain of the HWND's client area that the texture is
// copied to on present. The texture is persistent (Java2D repaints only dirty
// regions), so it is never a swap chain image itself. Like every AWT window
// surface it spans the whole window; the client area starts at the insets.
struct DawnSKSurface {
    DawnSKConfigInfo *config = nullptr;   // identity for the shared backend's context lookup; not dereferenced
    std::shared_ptr<DawnSKDevice> gpu;
    HWND hwnd = nullptr;
    wgpu::Surface window;      // null for off-screen surfaces
    wgpu::SurfaceConfiguration windowConfiguration;   // to reconfigure after Outdated
    wgpu::Texture texture;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t insetX = 0;       // where the client area starts in the texture
    uint32_t insetY = 0;
    uint32_t clientWidth = 0;  // swap chain size
    uint32_t clientHeight = 0;
    bool noImageReported = false;   // the swap chain failure is logged once per surface
};

// The process-wide Dawn instance; created on first use, null if Dawn cannot start.
wgpu::Instance DawnSK_Instance();

// Name of the adapter Dawn would use ("<adapter> (D3D11)"), empty if none; no device is created.
std::string DawnSK_DescribeAdapter();

// A configuration on the shared device of the first of Dawn's Direct3D
// backends that yields one (D3D11 first, D3D12 as fallback); a lost device is
// replaced. Null if none.
DawnSKConfigInfo *DawnSK_CreateConfig();
void DawnSK_DisposeConfig(DawnSKConfigInfo *config);
bool DawnSK_IsLost(const DawnSKConfigInfo *config);

// Texture (plus the configured swap chain of the window's client area if hwnd
// is non-null); null on failure, including a lost device, a texture beyond the
// adapter's limit or a window Dawn refuses. DXGI allows one flip-model swap
// chain per HWND and AWT replaces a window's surface on every bounds change,
// so a window surface takes the swap chain over from `previous`, the surface
// it replaces (the caller keeps the flusher idle meanwhile), and reconfigures
// it.
DawnSKSurface *DawnSK_CreateSurface(DawnSKConfigInfo *config, HWND hwnd, uint32_t width, uint32_t height,
                                    DawnSKSurface *previous, bool opaque);

#endif /* DawnSKPlatform_h_Included */
