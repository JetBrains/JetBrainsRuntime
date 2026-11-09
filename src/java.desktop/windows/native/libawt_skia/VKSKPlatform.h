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
#ifndef VKSKPlatform_h_Included
#define VKSKPlatform_h_Included

// Graphite on native Vulkan, behind the Windows platform layer (Win32SKPlatform.h), as
// VKSKGraphicsAPI (VKSKPlatform.cpp, the Win32SKGraphicsAPI of Vulkan): the API
// SKGraphicsAPIFallback falls back to when Dawn is not usable, or tries first with
// sun.java2d.skia.graphite.api=vulkan. The Vulkan loader is the driver's vulkan-1.dll,
// loaded at run time (nothing links against it). One device on the first discrete
// adapter (else the first) serves every configuration. Each surface owns a VkImage
// Skia renders into; a window surface also owns the swap chain of its HWND, whose
// images Graphite draws the surface into on present (SKPlatform::acquirePresentTarget),
// so Graphite tracks every image layout itself. The instance and the devices are never
// destroyed (a lost device is dropped and stays alive while its configurations and
// surfaces hold it): their destruction at process exit would run during DLL detach,
// under the loader lock, possibly after the loader and the driver were unloaded (as
// DawnSKPlatform.cpp keeps its instance and devices).

#include "Win32SKPlatform.h"

namespace VKSKPlatform {

// Vulkan as a Win32SKGraphicsAPI, for the selection (Win32SKGraphicsAPIFallback.cpp); the API of every Vulkan
// configuration and surface. Lives for the process.
[[nodiscard]] Win32SKGraphicsAPI &graphicsAPI();

// For tests, sun.java2d.skia.test.rejectVulkanPreferredDevice: with `reject` the first attempt to create
// a device (the features Graphite prefers) counts as failed, so the device is the minimal one of the
// retry (VKSKPlatform.cpp, createDevice); call before the first device is created.
void rejectPreferredDevice(bool reject);

} // namespace VKSKPlatform

#endif /* VKSKPlatform_h_Included */
