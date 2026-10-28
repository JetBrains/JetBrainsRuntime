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

// Graphite on native Vulkan, behind the Windows platform layer (Win32SKPlatform.h),
// selected with sun.java2d.skia.graphiteApi=vulkan. The Vulkan loader is the
// driver's vulkan-1.dll, loaded at run time (nothing links against it). One device
// on the first discrete adapter (else the first) serves every configuration. Each
// surface owns a VkImage Skia renders into; a window surface also owns the swap chain
// of its HWND, whose images Graphite draws the surface into on present
// (SKPlatform_AcquirePresentTarget), so Graphite tracks every image layout itself.

#include "SKPlatform.h"
#include "Win32SKPlatform.h"

#include "include/gpu/vk/VulkanBackendContext.h"
#include "include/gpu/vk/VulkanExtensions.h"
#include "include/third_party/vulkan/vulkan/vulkan_core.h"
#include "include/third_party/vulkan/vulkan/vulkan_win32.h"

#include <atomic>
#include <memory>
#include <string>
#include <vector>

struct VKSKDevice;

// One per Win32SKGraphicsConfig.
struct VKSKConfigInfo : Win32SKConfigInfo {
    VKSKConfigInfo() : Win32SKConfigInfo{Win32SKApi::Vulkan} {}
    std::shared_ptr<VKSKDevice> gpu;
    std::string description;   // "<adapter> (Vulkan)"
};

// One per Win32SKSurfaceData: the image Skia renders into (persistent: Java2D repaints
// only dirty regions) and, for a window, the swap chain of its client area.
struct VKSKSurface : Win32SKSurface {
    VKSKSurface() : Win32SKSurface{Win32SKApi::Vulkan} {}
    VKSKConfigInfo *config = nullptr;   // identity for the backend's context lookup; not dereferenced
    std::shared_ptr<VKSKDevice> gpu;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    // One BackendTexture for the surface's life: its copies share the layout state
    // Graphite keeps for the image, so every wrapper of the surface agrees on it.
    skgpu::graphite::BackendTexture texture;
    uint32_t width = 0;
    uint32_t height = 0;
    HWND hwnd = nullptr;
    uint32_t insetX = 0;         // where the client area starts in the image
    uint32_t insetY = 0;
    uint32_t clientWidth = 0;    // swap chain size
    uint32_t clientHeight = 0;
    // window surfaces
    VkSurfaceKHR window = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    std::vector<VkImage> images;                             // the swap chain's
    std::vector<skgpu::graphite::BackendTexture> imageTextures;
    std::vector<VkSemaphore> renderDone;                     // per image: the present waits for it
    uint32_t generation = 0;     // advanced with every swap chain, part of the image keys
    uint32_t current = UINT32_MAX;   // the image acquired last
    bool recreate = true;        // the swap chain is (re)made at the next acquire, on the flusher
    bool opaque = true;
    bool noImageReported = false;
};

// Name of the adapter Vulkan would use ("<adapter> (Vulkan)"), empty if none; no device is created.
std::string VKSK_DescribeAdapter();

VKSKConfigInfo *VKSK_CreateConfig();
void VKSK_DisposeConfig(VKSKConfigInfo *config);
bool VKSK_IsLost(const VKSKConfigInfo *config);
void VKSK_SimulateDeviceLoss(bool persistent);

// The image (and, for a window, its surface; the swap chain follows at the first present)
// on the configuration's device; null on failure. A window surface takes the swap chain
// over from `previous`, the surface it replaces.
VKSKSurface *VKSK_CreateSurface(VKSKConfigInfo *config, HWND hwnd, uint32_t width, uint32_t height,
                                VKSKSurface *previous, bool opaque);

// The GPU side of SKPlatform.h for Vulkan's configurations and surfaces (see there).
std::unique_ptr<skgpu::graphite::Context> VKSK_MakeContext(SKPlatformConfig *config,
                                                           const skgpu::graphite::ContextOptions &options);
SKPlatformConfig *VKSK_ConfigOf(SKPlatformSurface *surface);
bool VKSK_SameDevice(SKPlatformConfig *a, SKPlatformConfig *b);
void *VKSK_TextureHandle(SKPlatformSurface *surface);
bool VKSK_BackendTexture(SKPlatformSurface *surface, skgpu::graphite::BackendTexture *texture,
                         SkColorType *colorType);
bool VKSK_IsWindow(SKPlatformSurface *surface);
bool VKSK_AcquirePresentTarget(SKPlatformSurface *surface, SKPlatformPresentTarget *target);
bool VKSK_PresentTarget(SKPlatformSurface *surface);
void VKSK_DisposeSurface(SKPlatformSurface *surface);

#endif /* VKSKPlatform_h_Included */
