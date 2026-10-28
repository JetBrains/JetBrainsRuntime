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
// calls only this layer; each configuration and surface records the API that made
// it, and the GPU side of SKPlatform.h dispatches on that (Win32SKPlatform.cpp).
// Graphite's API is Dawn (DawnSKPlatform.cpp, over Direct3D 11 or 12); text is the
// same for every API (Win32SKText.cpp). SKPlatformConfig and SKPlatformSurface
// alias Win32SKConfigInfo and Win32SKSurface.

#include <windows.h>

#include <cstdint>
#include <string>

// The graphics API Graphite renders through, one per process.
enum class Win32SKApi { Dawn };

// The first member of every API's configuration (one per Win32SKGraphicsConfig)
// and surface (one per Win32SKSurfaceData).
struct Win32SKConfigInfo {
    Win32SKApi api;
};

struct Win32SKSurface {
    Win32SKApi api;
};

// Name of the adapter the API would use ("<adapter> (D3D11)"), empty if none; no device is created.
std::string Win32SK_DescribeAdapter();

// A configuration on the API's shared device; null if none can be made.
Win32SKConfigInfo *Win32SK_CreateConfig();
void Win32SK_DisposeConfig(Win32SKConfigInfo *config);
bool Win32SK_IsLost(const Win32SKConfigInfo *config);
// "<adapter> (<backend>)", for SKGraphicsConfig.ContextCaps.
std::string Win32SK_ConfigDescription(const Win32SKConfigInfo *config);
// Tests: mark the devices lost as a driver reset would; persistent also loses every new device
// once it has a window surface (Win32SKGraphicsConfig.simulateDeviceLoss).
void Win32SK_SimulateDeviceLoss(bool persistent);

// The texture Skia renders into, plus the swap chain of the window's client area if
// hwnd is non-null; null on failure. A window surface takes the swap chain over from
// `previous`, the surface it replaces (DXGI allows one flip-model swap chain per HWND
// and AWT replaces a window's surface on every bounds change).
Win32SKSurface *Win32SK_CreateSurface(Win32SKConfigInfo *config, HWND hwnd, uint32_t width, uint32_t height,
                                      Win32SKSurface *previous, bool opaque);

#endif /* Win32SKPlatform_h_Included */
