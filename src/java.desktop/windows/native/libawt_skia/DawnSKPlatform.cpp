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

// SKPlatform on Dawn: Graphite/Dawn contexts on the Dawn device shared by the
// Win32SKGraphicsConfigs of a backend, Skia surfaces over textures this file
// creates, presentation by copying the texture into the HWND's swap chain.

#include "DawnSKPlatform.h"
#include "SKPlatform.h"
#include "SKPipelineCache.h"
#include "SKStats.h"

#include "Trace.h"
#include "include/core/SkColorType.h"
#include <memory>
#include <cmath>
#include <mutex>
#include <unordered_map>
#include <vector>
#include "include/gpu/graphite/dawn/DawnBackendContext.h"
#include "include/gpu/graphite/dawn/DawnGraphiteTypes.h"
#include "dawn/dawn_proc.h"
#include "dawn/native/DawnNative.h"

#include <algorithm>
#include <cstdint>
#include <mutex>
#include <span>
#include <string>
#include <string_view>

using namespace skgpu::graphite;

// SKPlatformConfig and SKPlatformSurface alias Win32SKConfig and Win32SKSurface (Win32SKPlatform.h),
// which Win32SKPlatform.cpp has checked to be Dawn's before calling here.
static inline DawnSKConfigInfo *configOf(SKPlatformConfig *config) {
    return static_cast<DawnSKConfigInfo *>(reinterpret_cast<Win32SKConfig *>(config));
}

static inline DawnSKSurface *surfaceOf(SKPlatformSurface *surface) {
    return static_cast<DawnSKSurface *>(reinterpret_cast<Win32SKSurface *>(surface));
}

static std::string toString(wgpu::StringView s) {
    return std::string(std::string_view(s));
}

static const char *backendName(wgpu::BackendType type) {
    switch (type) {
        case wgpu::BackendType::D3D11: return "D3D11";
        case wgpu::BackendType::D3D12: return "D3D12";
        default: return "unknown backend";
    }
}

// The surfaces are BGRA8 like the Direct3D swap chains they are copied to.
static constexpr wgpu::TextureFormat kSurfaceFormat = wgpu::TextureFormat::BGRA8Unorm;
static constexpr SkColorType kSurfaceColorType = kBGRA_8888_SkColorType;

// D3D11 first: the lowest common denominator of Windows GPUs and VMs.
static const wgpu::BackendType kBackends[] = { wgpu::BackendType::D3D11, wgpu::BackendType::D3D12 };
static constexpr size_t kBackendCount = sizeof(kBackends) / sizeof(kBackends[0]);
// sun.java2d.skia.dawn.backend=d3d12 tries D3D12 first (an evaluation of the backend Chrome ships
// WebGPU on; Graphite is tested on D3D11 first). Set once, before the first adapter request.
static size_t gBackendOrder[kBackendCount] = { 0, 1 };

void DawnSK_PreferBackend(bool d3d12) {
    gBackendOrder[0] = d3d12 ? 1 : 0;
    gBackendOrder[1] = d3d12 ? 0 : 1;
}

// One device per backend, shared by the configurations (as the D3D pipeline
// keeps its device across configurations): a display change (resolution, DPI,
// a monitor plugged) recreates the configurations, and a device per
// configuration left every texture of the old one unusable on the new
// (DawnSK_SameDevice) and cost a device each time. A lost device is
// dropped, so recovery gets a new one.
static std::mutex gDevicesLock;
static std::shared_ptr<DawnSKDevice> gDevices[kBackendCount];

// ---------------------------------------------------------------------------
// Instance, adapter, device
// ---------------------------------------------------------------------------

// J2D_SKIA_STATS: the render passes, texture copies, uploads and submits Graphite
// issues, counted where they enter Dawn (its process-wide proc table, wrapped
// once before the instance exists). Without the statistics the table is Dawn's own.
static DawnProcTable gDawnProcs;

static WGPURenderPassEncoder countingBeginRenderPass(WGPUCommandEncoder encoder, const WGPURenderPassDescriptor *descriptor) {
    SKStats_Count(SK_COUNT_RENDER_PASSES);
    return gDawnProcs.commandEncoderBeginRenderPass(encoder, descriptor);
}

static void countingCopyTextureToTexture(WGPUCommandEncoder encoder, const WGPUTexelCopyTextureInfo *source,
                                         const WGPUTexelCopyTextureInfo *destination, const WGPUExtent3D *size) {
    SKStats_Count(SK_COUNT_TEXTURE_COPIES);
    gDawnProcs.commandEncoderCopyTextureToTexture(encoder, source, destination, size);
}

static void countingCopyBufferToTexture(WGPUCommandEncoder encoder, const WGPUTexelCopyBufferInfo *source,
                                        const WGPUTexelCopyTextureInfo *destination, const WGPUExtent3D *size) {
    SKStats_Count(SK_COUNT_BUFFER_UPLOADS);
    gDawnProcs.commandEncoderCopyBufferToTexture(encoder, source, destination, size);
}

static void countingQueueSubmit(WGPUQueue queue, size_t commandCount, const WGPUCommandBuffer *commands) {
    SKStats_Count(SK_COUNT_QUEUE_SUBMITS);
    gDawnProcs.queueSubmit(queue, commandCount, commands);
}

static const DawnProcTable *dawnProcs() {
    if (!SKStats_IsEnabled()) {
        return &dawn::native::GetProcs();
    }
    static DawnProcTable counting = [] {
        gDawnProcs = dawn::native::GetProcs();
        DawnProcTable table = gDawnProcs;
        table.commandEncoderBeginRenderPass = countingBeginRenderPass;
        table.commandEncoderCopyTextureToTexture = countingCopyTextureToTexture;
        table.commandEncoderCopyBufferToTexture = countingCopyBufferToTexture;
        table.queueSubmit = countingQueueSubmit;
        return table;
    }();
    return &counting;
}

wgpu::Instance DawnSK_Instance() {
    static wgpu::Instance instance = [] {
        // Dawn's C entry points dispatch through a process-wide proc table that
        // the native implementation has to be installed in first.
        dawnProcSetProcs(dawnProcs());
        wgpu::InstanceFeatureName timedWait = wgpu::InstanceFeatureName::TimedWaitAny;
        wgpu::InstanceDescriptor descriptor;
        descriptor.requiredFeatureCount = 1;
        descriptor.requiredFeatures = &timedWait;   // lets us wait for adapter/device requests synchronously
        wgpu::Instance created = wgpu::CreateInstance(&descriptor);
        if (created == nullptr) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "DawnSK_Instance: cannot create the Dawn instance");
        }
        return created;
    }();
    return instance;
}

static wgpu::Adapter requestAdapter(const wgpu::Instance &instance, wgpu::BackendType backendType) {
    wgpu::RequestAdapterOptions options;
    options.backendType = backendType;
    options.powerPreference = wgpu::PowerPreference::HighPerformance;
    wgpu::Adapter adapter;
    wgpu::Future request = instance.RequestAdapter(&options, wgpu::CallbackMode::WaitAnyOnly,
            [&adapter, backendType](wgpu::RequestAdapterStatus status, wgpu::Adapter result, wgpu::StringView message) {
                if (status == wgpu::RequestAdapterStatus::Success) {
                    adapter = std::move(result);
                } else {
                    J2dRlsTraceLn(J2D_TRACE_INFO, "DawnSK: no %s adapter: %s", backendName(backendType),
                                  toString(message).c_str());
                }
            });
    instance.WaitAny(request, UINT64_MAX);
    return adapter;
}

// The first Direct3D backend with an adapter, or Undefined.
static wgpu::Adapter firstAdapter(const wgpu::Instance &instance, wgpu::BackendType *backendType) {
    for (size_t order : gBackendOrder) {
        const wgpu::BackendType type = kBackends[order];
        wgpu::Adapter adapter = requestAdapter(instance, type);
        if (adapter != nullptr) {
            *backendType = type;
            return adapter;
        }
    }
    *backendType = wgpu::BackendType::Undefined;
    return nullptr;
}

static std::string describe(const wgpu::Adapter &adapter, wgpu::BackendType backendType) {
    wgpu::AdapterInfo info;
    adapter.GetInfo(&info);
    return toString(info.device) + " (" + backendName(backendType) + ")";
}

std::string DawnSK_DescribeAdapter() {
    wgpu::Instance instance = DawnSK_Instance();
    wgpu::BackendType backendType;
    wgpu::Adapter adapter = instance != nullptr ? firstAdapter(instance, &backendType) : nullptr;
    return adapter != nullptr ? describe(adapter, backendType) : std::string();
}

static wgpu::Device requestDevice(const wgpu::Instance &instance, const wgpu::Adapter &adapter,
                                  const std::shared_ptr<std::atomic<bool>> &lost, const std::string &description) {
    wgpu::DeviceDescriptor descriptor;
    // Off-screen surfaces are created on the EDT while the flusher records and
    // submits on the same device; Dawn serializes device access with this.
    wgpu::FeatureName implicitSync = wgpu::FeatureName::ImplicitDeviceSynchronization;
    if (adapter.HasFeature(implicitSync)) {
        descriptor.requiredFeatureCount = 1;
        descriptor.requiredFeatures = &implicitSync;
    } else {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "DawnSK: adapter lacks ImplicitDeviceSynchronization");
    }
    // The adapter's limits, not WebGPU's defaults: a device gets textures of
    // 8192 pixels at most otherwise, while Direct3D 11 adapters allow 16384,
    // and a tall off-screen image of a HiDPI screen (a 2x scaled 4608-pixel
    // sheet) would fall back to software.
    wgpu::Limits limits;
    if (adapter.GetLimits(&limits) == wgpu::Status::Success) {
        descriptor.requiredLimits = &limits;
    }
    // sun.java2d.skia.pipelineCache: Dawn asks for the shaders and pipelines it compiled in
    // earlier sessions by its own key (adapter, driver, toggles and Dawn version are part of
    // it) and hands over new ones; the store lives as long as the process (SKPipelineCache).
    wgpu::DawnCacheDeviceDescriptor cache;
    if (SKBlobStore *store = SKPipelineCache_BlobStore("dawn-" + description)) {
        cache.isolationKey = "jbr-skia";
        cache.SetDawnLoadCacheDataCallback(
                [](std::span<const std::byte> key, std::span<std::byte> value, SKBlobStore *s) {
                    return s->load(key.data(), key.size(), value.data(), value.size());
                }, store);
        cache.SetDawnStoreCacheDataCallback(
                [](std::span<const std::byte> key, std::span<const std::byte> value, SKBlobStore *s) {
                    s->store(key.data(), key.size(), value.data(), value.size());
                }, store);
        descriptor.nextInChain = &cache;
    }
    descriptor.SetUncapturedErrorCallback(
            [](const wgpu::Device &, wgpu::ErrorType type, wgpu::StringView message) {
                J2dRlsTraceLn(J2D_TRACE_ERROR, "DawnSK: device error %d: %s", (int) type, toString(message).c_str());
            });
    // The flag outlives device and configuration (shared with every surface),
    // so a callback after their disposal is harmless.
    descriptor.SetDeviceLostCallback(wgpu::CallbackMode::AllowSpontaneous,
            [lost](const wgpu::Device &, wgpu::DeviceLostReason reason, wgpu::StringView message) {
                if (reason != wgpu::DeviceLostReason::Destroyed) {
                    J2dRlsTraceLn(J2D_TRACE_ERROR, "DawnSK: device lost (%d): %s", (int) reason,
                                  toString(message).c_str());
                    lost->store(true);
                }
            });
    wgpu::Device device;
    wgpu::Future request = adapter.RequestDevice(&descriptor, wgpu::CallbackMode::WaitAnyOnly,
            [&device](wgpu::RequestDeviceStatus status, wgpu::Device result, wgpu::StringView message) {
                if (status == wgpu::RequestDeviceStatus::Success) {
                    device = std::move(result);
                } else {
                    J2dRlsTraceLn(J2D_TRACE_ERROR, "DawnSK: cannot create a device: %s", toString(message).c_str());
                }
            });
    instance.WaitAny(request, UINT64_MAX);
    return device;
}

// Tests (Win32SKGraphicsConfig.simulateDeviceLoss): every device counts as lost, as after a
// driver reset; with persistent also every device created afterwards once its first window
// surface exists, as on a driver that keeps failing.
static std::atomic<bool> gLoseNewDevices{false};

void DawnSK_SimulateDeviceLoss(bool persistent) {
    gLoseNewDevices.store(persistent);
    std::lock_guard<std::mutex> lock(gDevicesLock);
    for (auto &gpu : gDevices) {
        if (gpu != nullptr) {
            gpu->lost->store(true);
        }
    }
}

DawnSKConfigInfo *DawnSK_CreateConfig() {
    wgpu::Instance instance = DawnSK_Instance();
    if (instance == nullptr) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(gDevicesLock);
    for (size_t i : gBackendOrder) {
        const wgpu::BackendType backendType = kBackends[i];
        std::shared_ptr<DawnSKDevice> gpu = gDevices[i];
        if (gpu != nullptr && gpu->lost->load()) {
            gDevices[i].reset();
            gpu.reset();
        }
        const bool shared = gpu != nullptr;
        if (!shared) {
            wgpu::Adapter adapter = requestAdapter(instance, backendType);
            if (adapter == nullptr) {
                continue;
            }
            gpu = std::make_shared<DawnSKDevice>();
            gpu->device = requestDevice(instance, adapter, gpu->lost, describe(adapter, backendType));
            if (gpu->device == nullptr) {
                continue;
            }
            gpu->adapter = std::move(adapter);
            gpu->queue = gpu->device.GetQueue();
            wgpu::Limits limits;   // the device's limits (the adapter's were required at creation)
            gpu->maxTextureSize = gpu->device.GetLimits(&limits) == wgpu::Status::Success ? limits.maxTextureDimension2D : 8192;
            gDevices[i] = gpu;
        }
        auto *config = new DawnSKConfigInfo();
        config->gpu = std::move(gpu);
        config->description = describe(config->gpu->adapter, backendType);
        J2dRlsTraceLn(J2D_TRACE_INFO, "DawnSK_CreateConfig: %s, max texture %u, %s device", config->description.c_str(),
                      config->gpu->maxTextureSize, shared ? "shared" : "new");
        return config;
    }
    J2dRlsTraceLn(J2D_TRACE_ERROR, "DawnSK_CreateConfig: no Direct3D adapter yields a Dawn device");
    return nullptr;
}

void DawnSK_DisposeConfig(DawnSKConfigInfo *config) {
    delete config;
}

bool DawnSK_IsLost(const DawnSKConfigInfo *config) {
    return config == nullptr || config->gpu == nullptr || config->gpu->lost->load();
}

// ---------------------------------------------------------------------------
// Surfaces
// ---------------------------------------------------------------------------

// Dawn returns error objects rather than null for failed creation and
// configuration; an error scope around the calls turns them into a result.
// Only validation and out-of-memory errors: a lost device reports through its
// own callback.
static void pushErrorScopes(const wgpu::Device &device) {
    device.PushErrorScope(wgpu::ErrorFilter::OutOfMemory);
    device.PushErrorScope(wgpu::ErrorFilter::Validation);
}

static bool popErrorScopes(const wgpu::Device &device, const char *what) {
    bool ok = true;
    for (int i = 0; i < 2; i++) {
        wgpu::Future pop = device.PopErrorScope(wgpu::CallbackMode::WaitAnyOnly,
                [&ok, what](wgpu::PopErrorScopeStatus status, wgpu::ErrorType type, wgpu::StringView message) {
                    if (status == wgpu::PopErrorScopeStatus::Success && type != wgpu::ErrorType::NoError) {
                        J2dRlsTraceLn(J2D_TRACE_WARNING, "DawnSK: %s failed (%d): %s", what, (int) type,
                                      toString(message).c_str());
                        ok = false;
                    }
                });
        DawnSK_Instance().WaitAny(pop, UINT64_MAX);
    }
    return ok;
}

// Swap chain configuration for the surface's window from what the swap chain
// supports; false if it cannot take our BGRA8 copy.
static bool configureWindow(DawnSKSurface *surface, bool opaque) {
    wgpu::SurfaceCapabilities capabilities;
    surface->window.GetCapabilities(surface->gpu->adapter, &capabilities);
    bool formatOk = false, opaqueSupported = false;
    for (size_t i = 0; i < capabilities.formatCount; i++) {
        formatOk |= capabilities.formats[i] == kSurfaceFormat;
    }
    // Presents happen per queue flush, so they must not wait for a vblank:
    // Mailbox, else Immediate, else Fifo (the only mode always offered).
    wgpu::PresentMode presentMode = wgpu::PresentMode::Fifo;
    for (size_t i = 0; i < capabilities.presentModeCount; i++) {
        wgpu::PresentMode m = capabilities.presentModes[i];
        if (m == wgpu::PresentMode::Mailbox
                || (m == wgpu::PresentMode::Immediate && presentMode != wgpu::PresentMode::Mailbox)) {
            presentMode = m;
        }
    }
    for (size_t i = 0; i < capabilities.alphaModeCount; i++) {
        opaqueSupported |= capabilities.alphaModes[i] == wgpu::CompositeAlphaMode::Opaque;
    }
    bool copyDst = (capabilities.usages & wgpu::TextureUsage::CopyDst) == wgpu::TextureUsage::CopyDst;
    if (!formatOk || !copyDst) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "DawnSK: the swap chain cannot take a BGRA8 copy (BGRA8 %d, copyDst %d)",
                      formatOk, copyDst);
        return false;
    }
    wgpu::SurfaceConfiguration &configuration = surface->windowConfiguration;
    configuration.device = surface->gpu->device;
    configuration.format = kSurfaceFormat;
    configuration.usage = wgpu::TextureUsage::RenderAttachment | wgpu::TextureUsage::CopyDst;
    configuration.width = surface->clientWidth;
    configuration.height = surface->clientHeight;
    configuration.alphaMode = opaque && opaqueSupported ? wgpu::CompositeAlphaMode::Opaque
                                                        : wgpu::CompositeAlphaMode::Auto;
    configuration.presentMode = presentMode;
    pushErrorScopes(surface->gpu->device);
    surface->window.Configure(&configuration);
    J2dTraceLn(J2D_TRACE_VERBOSE, "DawnSK: swap chain %ux%u presentMode=%d alphaMode=%d", configuration.width,
               configuration.height, (int) presentMode, (int) configuration.alphaMode);
    return popErrorScopes(surface->gpu->device, "swap chain configuration");
}

DawnSKSurface *DawnSK_CreateSurface(DawnSKConfigInfo *config, HWND hwnd, uint32_t width, uint32_t height,
                                    DawnSKSurface *previous, bool opaque) {
    if (DawnSK_IsLost(config) || width == 0 || height == 0) {
        return nullptr;
    }
    const std::shared_ptr<DawnSKDevice> &gpu = config->gpu;
    if (width > gpu->maxTextureSize || height > gpu->maxTextureSize) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "DawnSK_CreateSurface: %ux%u exceeds the adapter's %u", width, height,
                      gpu->maxTextureSize);
        return nullptr;
    }
    auto *surface = new DawnSKSurface();
    surface->config = config;
    surface->gpu = gpu;
    surface->hwnd = hwnd;
    surface->width = width;
    surface->height = height;
    if (hwnd != nullptr) {
        // The surface spans the window (AWT's window bounds) and Java paints
        // at the insets; the swap chain covers the client area. Insets are
        // what AwtFrame::GetInsets computes too: client origin minus window
        // origin. The window may be mid-resize (AWT replaces the surface on
        // every bounds change); whatever does not fit is cut, and the next
        // replacement gets the final size.
        RECT window = {}, client = {};
        POINT origin = { 0, 0 };
        if (!GetWindowRect(hwnd, &window) || !GetClientRect(hwnd, &client) || !ClientToScreen(hwnd, &origin)) {
            J2dRlsTraceLn(J2D_TRACE_WARNING, "DawnSK_CreateSurface: HWND %p is gone", hwnd);
            delete surface;
            return nullptr;
        }
        LONG insetX = origin.x - window.left, insetY = origin.y - window.top;
        surface->insetX = insetX > 0 && (uint32_t) insetX < width ? (uint32_t) insetX : 0;
        surface->insetY = insetY > 0 && (uint32_t) insetY < height ? (uint32_t) insetY : 0;
        surface->clientWidth = client.right > 0 ? (uint32_t) client.right : width - surface->insetX;
        surface->clientHeight = client.bottom > 0 ? (uint32_t) client.bottom : height - surface->insetY;
    }

    wgpu::TextureDescriptor texture;
    texture.label = "Java2D Skia surface";
    texture.dimension = wgpu::TextureDimension::e2D;
    texture.size = { width, height, 1 };
    texture.format = kSurfaceFormat;
    // render target for Graphite, sampled when drawn into another surface,
    // copy source for presentation and readback, copy destination for uploads
    texture.usage = wgpu::TextureUsage::RenderAttachment | wgpu::TextureUsage::TextureBinding
                  | wgpu::TextureUsage::CopySrc | wgpu::TextureUsage::CopyDst;
    pushErrorScopes(gpu->device);
    surface->texture = gpu->device.CreateTexture(&texture);
    if (!popErrorScopes(gpu->device, "texture creation") || surface->texture == nullptr) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "DawnSK_CreateSurface: cannot create a %ux%u texture", width, height);
        delete surface;
        return nullptr;
    }

    if (hwnd != nullptr) {
        if (previous != nullptr && previous->hwnd == hwnd && previous->window != nullptr) {
            // the surface being replaced can no longer present; its texture stays
            // valid until it is disposed
            J2dTraceLn(J2D_TRACE_VERBOSE, "DawnSK_CreateSurface: taking over the swap chain of %p", previous);
            surface->window = std::move(previous->window);
            previous->window = nullptr;
        } else {
            wgpu::SurfaceSourceWindowsHWND source;
            source.hinstance = GetModuleHandleW(nullptr);
            source.hwnd = hwnd;
            wgpu::SurfaceDescriptor descriptor;
            descriptor.nextInChain = &source;
            surface->window = DawnSK_Instance().CreateSurface(&descriptor);
        }
        if (surface->window == nullptr || !configureWindow(surface, opaque)) {
            J2dRlsTraceLn(J2D_TRACE_WARNING, "DawnSK_CreateSurface: no swap chain for HWND %p", hwnd);
            if (surface->window != nullptr) {
                surface->window.Unconfigure();
            }
            delete surface;
            return nullptr;
        }
    }
    if (hwnd != nullptr && gLoseNewDevices.load()) {
        surface->gpu->lost->store(true);   // DawnSK_SimulateDeviceLoss(persistent): as a driver that does not come back
    }
    J2dTraceLn(J2D_TRACE_INFO, "DawnSK_CreateSurface: %p %ux%u hwnd=%p client=%ux%u at (%u,%u)", surface, width,
               height, hwnd, surface->clientWidth, surface->clientHeight, surface->insetX, surface->insetY);
    return surface;
}

// ---------------------------------------------------------------------------
// SKPlatform
// ---------------------------------------------------------------------------

std::unique_ptr<Context> DawnSK_MakeContext(SKPlatformConfig *config, const ContextOptions &options) {
    DawnSKConfigInfo *info = configOf(config);
    if (DawnSK_IsLost(info)) {
        return nullptr;
    }
    DawnBackendContext backend;
    backend.fInstance = DawnSK_Instance();
    backend.fDevice = info->gpu->device;
    backend.fQueue = info->gpu->queue;
    // fTick stays DawnNativeProcessEventsFunction: Graphite polls the instance
    // for GPU progress, so SyncToCpu submits and readbacks work.
    return ContextFactory::MakeDawn(backend, options);
}

SKPlatformConfig *DawnSK_ConfigOf(SKPlatformSurface *surface) {
    DawnSKSurface *s = surfaceOf(surface);
    return s != nullptr ? reinterpret_cast<SKPlatformConfig *>(static_cast<Win32SKConfig *>(s->config)) : nullptr;
}

bool DawnSK_SameDevice(SKPlatformConfig *a, SKPlatformConfig *b) {
    DawnSKConfigInfo *ia = configOf(a);
    DawnSKConfigInfo *ib = configOf(b);
    return ia != nullptr && ib != nullptr && ia->gpu != nullptr && ib->gpu != nullptr
           && ia->gpu->device.Get() == ib->gpu->device.Get();
}

void *DawnSK_TextureHandle(SKPlatformSurface *surface) {
    DawnSKSurface *s = surfaceOf(surface);
    return s != nullptr && !s->gpu->lost->load() ? s->texture.Get() : nullptr;
}

bool DawnSK_BackendTexture(SKPlatformSurface *surface, BackendTexture *texture, SkColorType *colorType) {
    DawnSKSurface *s = surfaceOf(surface);
    if (s == nullptr || s->texture == nullptr || s->gpu->lost->load()) {
        return false;
    }
    *texture = BackendTextures::MakeDawn(s->texture.Get());
    *colorType = kSurfaceColorType;
    return texture->isValid();
}

bool DawnSK_IsWindow(SKPlatformSurface *surface) {
    DawnSKSurface *s = surfaceOf(surface);
    return s != nullptr && s->window != nullptr;
}

// Copies the surface texture into the swap chain's current image and presents
// it. Runs on the flusher thread after Graphite's submit; the copy goes to the
// same queue, so it is ordered after the rendering without a CPU wait.
bool DawnSK_RequestRedraw(SKPlatformSurface *surface) {
    DawnSKSurface *s = surfaceOf(surface);
    if (s == nullptr || s->window == nullptr || s->gpu->lost->load()) {
        // a replaced window surface (its swap chain moved on), not a window, or a lost device
        J2dTraceLn(J2D_TRACE_VERBOSE, "DawnSK_RequestRedraw: nothing to present for %p", surface);
        return false;
    }
    wgpu::SurfaceTexture current;
    {
        SKStatsScope acquireStats(SK_SPAN_PRESENT_ACQUIRE);
        s->window.GetCurrentTexture(&current);
    }
    if (current.status == wgpu::SurfaceGetCurrentTextureStatus::Outdated) {
        // the window changed under the swap chain; AWT replaces the surface on
        // its next bounds event, until then present at the configured size
        s->window.Configure(&s->windowConfiguration);
        s->window.GetCurrentTexture(&current);
    }
    if (current.status != wgpu::SurfaceGetCurrentTextureStatus::SuccessOptimal
            && current.status != wgpu::SurfaceGetCurrentTextureStatus::SuccessSuboptimal) {
        if (!s->noImageReported) {
            s->noImageReported = true;
            J2dRlsTraceLn(J2D_TRACE_WARNING, "DawnSK_RequestRedraw: no swap chain image (status %d)",
                          (int) current.status);
        }
        DawnSK_Instance().ProcessEvents();   // deliver the error message behind the status
        return false;
    }
    s->noImageReported = false;
    {
        SKStatsScope copyStats(SK_SPAN_PRESENT_COPY);
        // the client-area part of the texture, as much of it as both sides have
        wgpu::CommandEncoder encoder = s->gpu->device.CreateCommandEncoder();
        wgpu::TexelCopyTextureInfo source;
        source.texture = s->texture;
        source.origin = { s->insetX, s->insetY, 0 };
        wgpu::TexelCopyTextureInfo destination;
        destination.texture = current.texture;
        wgpu::Extent3D extent = { std::min(s->width - s->insetX, s->clientWidth),
                                  std::min(s->height - s->insetY, s->clientHeight), 1 };
        encoder.CopyTextureToTexture(&source, &destination, &extent);
        wgpu::CommandBuffer commands = encoder.Finish();
        s->gpu->queue.Submit(1, &commands);
    }
    {
        SKStatsScope presentStats(SK_SPAN_PRESENT_CALL);
        s->window.Present();
    }
    J2dTraceLn(J2D_TRACE_VERBOSE, "DawnSK_RequestRedraw: presented %p", surface);
    return true;
}

void DawnSK_DisposeSurface(SKPlatformSurface *surface) {
    DawnSKSurface *s = surfaceOf(surface);
    if (s == nullptr) {
        return;
    }
    J2dTraceLn(J2D_TRACE_VERBOSE, "DawnSK_DisposeSurface: %p", surface);
    if (s->window != nullptr) {   // not handed over to a replacing surface
        s->window.Unconfigure();
    }
    delete s;
}

