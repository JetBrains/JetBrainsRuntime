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

#include "Trace.h"
#include "include/core/SkColorType.h"
#include "include/core/SkFontMgr.h"
#include "include/core/SkMatrix.h"
#include "include/core/SkStream.h"
#include "include/core/SkTypeface.h"
#include "include/ports/SkTypeface_win.h"
// the DirectWrite glyph masks (SKPlatform_SmoothedGlyphMask)
#include <d2d1.h>
#include <dwrite_2.h>   // the JDK builds for Windows 8 (_WIN32_WINNT 0x0602): no dwrite_3 interfaces
#include <wrl/client.h>
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
#include <string>
#include <string_view>

using namespace skgpu::graphite;

static inline DawnSKConfigInfo *configOf(SKPlatformConfig *config) {
    return reinterpret_cast<DawnSKConfigInfo *>(config);
}

static inline DawnSKSurface *surfaceOf(SKPlatformSurface *surface) {
    return reinterpret_cast<DawnSKSurface *>(surface);
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

// One device per backend, shared by the configurations (as the D3D pipeline
// keeps its device across configurations): a display change (resolution, DPI,
// a monitor plugged) recreates the configurations, and a device per
// configuration left every texture of the old one unusable on the new
// (SKPlatform_SameDevice) and cost a device each time. A lost device is
// dropped, so recovery gets a new one.
static std::mutex gDevicesLock;
static std::shared_ptr<DawnSKDevice> gDevices[kBackendCount];

// ---------------------------------------------------------------------------
// Instance, adapter, device
// ---------------------------------------------------------------------------

wgpu::Instance DawnSK_Instance() {
    static wgpu::Instance instance = [] {
        // Dawn's C entry points dispatch through a process-wide proc table that
        // the native implementation has to be installed in first.
        dawnProcSetProcs(&dawn::native::GetProcs());
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
    for (wgpu::BackendType type : kBackends) {
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
                                  const std::shared_ptr<std::atomic<bool>> &lost) {
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

DawnSKConfigInfo *DawnSK_CreateConfig() {
    wgpu::Instance instance = DawnSK_Instance();
    if (instance == nullptr) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(gDevicesLock);
    for (size_t i = 0; i < kBackendCount; i++) {
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
            gpu->device = requestDevice(instance, adapter, gpu->lost);
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
    J2dTraceLn(J2D_TRACE_INFO, "DawnSK_CreateSurface: %p %ux%u hwnd=%p client=%ux%u at (%u,%u)", surface, width,
               height, hwnd, surface->clientWidth, surface->clientHeight, surface->insetX, surface->insetY);
    return surface;
}

// ---------------------------------------------------------------------------
// SKPlatform
// ---------------------------------------------------------------------------

std::unique_ptr<Context> SKPlatform_MakeContext(SKPlatformConfig *config, const ContextOptions &options) {
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

SKPlatformConfig *SKPlatform_ConfigOf(SKPlatformSurface *surface) {
    DawnSKSurface *s = surfaceOf(surface);
    return s != nullptr ? reinterpret_cast<SKPlatformConfig *>(s->config) : nullptr;
}

bool SKPlatform_SameDevice(SKPlatformConfig *a, SKPlatformConfig *b) {
    DawnSKConfigInfo *ia = configOf(a);
    DawnSKConfigInfo *ib = configOf(b);
    return ia != nullptr && ib != nullptr && ia->gpu != nullptr && ib->gpu != nullptr
           && ia->gpu->device.Get() == ib->gpu->device.Get();
}

void *SKPlatform_TextureHandle(SKPlatformSurface *surface) {
    DawnSKSurface *s = surfaceOf(surface);
    return s != nullptr && !s->gpu->lost->load() ? s->texture.Get() : nullptr;
}

bool SKPlatform_BackendTexture(SKPlatformSurface *surface, BackendTexture *texture, SkColorType *colorType) {
    DawnSKSurface *s = surfaceOf(surface);
    if (s == nullptr || s->texture == nullptr || s->gpu->lost->load()) {
        return false;
    }
    *texture = BackendTextures::MakeDawn(s->texture.Get());
    *colorType = kSurfaceColorType;
    return texture->isValid();
}

bool SKPlatform_IsWindow(SKPlatformSurface *surface) {
    DawnSKSurface *s = surfaceOf(surface);
    return s != nullptr && s->window != nullptr;
}

// Copies the surface texture into the swap chain's current image and presents
// it. Runs on the flusher thread after Graphite's submit; the copy goes to the
// same queue, so it is ordered after the rendering without a CPU wait.
void SKPlatform_RequestRedraw(SKPlatformSurface *surface) {
    DawnSKSurface *s = surfaceOf(surface);
    if (s == nullptr || s->window == nullptr || s->gpu->lost->load()) {
        // a replaced window surface (its swap chain moved on), not a window, or a lost device
        J2dTraceLn(J2D_TRACE_VERBOSE, "SKPlatform_RequestRedraw: nothing to present for %p", surface);
        return;
    }
    wgpu::SurfaceTexture current;
    s->window.GetCurrentTexture(&current);
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
            J2dRlsTraceLn(J2D_TRACE_WARNING, "SKPlatform_RequestRedraw: no swap chain image (status %d)",
                          (int) current.status);
        }
        DawnSK_Instance().ProcessEvents();   // deliver the error message behind the status
        return;
    }
    s->noImageReported = false;
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
    s->window.Present();
    J2dTraceLn(J2D_TRACE_VERBOSE, "SKPlatform_RequestRedraw: presented %p", surface);
}

void SKPlatform_DisposeSurface(SKPlatformSurface *surface) {
    DawnSKSurface *s = surfaceOf(surface);
    if (s == nullptr) {
        return;
    }
    J2dTraceLn(J2D_TRACE_VERBOSE, "SKPlatform_DisposeSurface: %p", surface);
    if (s->window != nullptr) {   // not handed over to a replacing surface
        s->window.Unconfigure();
    }
    delete s;
}

SKPlatformScope::SKPlatformScope() : handle(nullptr) {}

SKPlatformScope::~SKPlatformScope() {}

// ---- text ------------------------------------------------------------------

// The DirectWrite host: the font manager the platform's own text stack uses,
// over DirectWrite's own factory (no COM initialization needed). The JDK's
// Windows fonts are all file fonts, so this is the only way a typeface is
// made here; the manager is shared and lives as long as the library.
sk_sp<SkFontMgr> SKPlatform_FontManager() {
    static sk_sp<SkFontMgr> manager = SkFontMgr_New_DirectWrite();
    return manager;
}

sk_sp<SkTypeface> SKPlatform_TypefaceFromNativeFont(jlong) {
    return nullptr;
}

sk_sp<SkTypeface> SKPlatform_FallbackTypeface(jlong, int, uint16_t *) {
    return nullptr;   // Windows resolves fallback through composite fonts, with non-negative codes
}

// ---- DirectWrite glyph masks ------------------------------------------------
//
// The platform's own rasterizer for the Skia scaler's glyph images, in the two
// forms the pipes can use. Linear coverage (luminance -1): the alpha texture of
// a glyph run analysis, which DirectWrite produces before any gamma or contrast
// (those belong to its blend, IDWriteGlyphRunAnalysis::GetAlphaBlendParams), so
// the pipes apply their own gamma to it as they do to FreeType's masks. The
// native look (luminance 0..255): the glyph drawn by Direct2D with the system's
// rendering parameters, text of that luminance on the opposite paper, and the
// coverage recovered from what the paper lost. DirectWrite's weight depends on
// the text luminance alone (measured on this project: equal for black text on
// white and on mid-gray, and so on), so the mask is exact on any background.
// Rendering mode and pixel geometry are DirectWrite's recommendation for the
// size and the system parameters, i.e. vertical hinting at text sizes, which
// is what snaps x-heights the way Windows 11 text has them.
//
// The typeface's font face comes from its font data through an in-memory
// loader: the Skia host keeps its own face private, and this way a font the JDK
// created from a file or stream at run time renders exactly like an installed
// one, which is what the DirectWrite font model is designed for (no registry).

namespace {

using Microsoft::WRL::ComPtr;

// A font file loader over the typefaces' font data (DirectWrite 1, so it builds
// against the JDK's Windows 8 headers; IDWriteInMemoryFontFileLoader needs
// Windows 10). The key of a font file reference is the typeface's id.
struct MemoryFontStream : IDWriteFontFileStream {
    std::shared_ptr<std::vector<uint8_t>> data;
    ULONG refs = 1;
    explicit MemoryFontStream(std::shared_ptr<std::vector<uint8_t>> d) : data(std::move(d)) {}
    HRESULT __stdcall QueryInterface(REFIID riid, void **out) override {
        if (riid == __uuidof(IUnknown) || riid == __uuidof(IDWriteFontFileStream)) { *out = this; AddRef(); return S_OK; }
        *out = nullptr; return E_NOINTERFACE;
    }
    ULONG __stdcall AddRef() override { return ++refs; }
    ULONG __stdcall Release() override { ULONG n = --refs; if (n == 0) delete this; return n; }
    HRESULT __stdcall ReadFileFragment(const void **start, UINT64 offset, UINT64 size, void **context) override {
        if (offset + size > data->size()) { *start = nullptr; *context = nullptr; return E_INVALIDARG; }
        *start = data->data() + offset; *context = nullptr; return S_OK;
    }
    void __stdcall ReleaseFileFragment(void *) override {}
    HRESULT __stdcall GetFileSize(UINT64 *size) override { *size = data->size(); return S_OK; }
    HRESULT __stdcall GetLastWriteTime(UINT64 *time) override { *time = 0; return S_OK; }
};

struct MemoryFontLoader : IDWriteFontFileLoader {
    std::unordered_map<SkTypefaceID, std::shared_ptr<std::vector<uint8_t>>> fonts;
    ULONG refs = 1;
    HRESULT __stdcall QueryInterface(REFIID riid, void **out) override {
        if (riid == __uuidof(IUnknown) || riid == __uuidof(IDWriteFontFileLoader)) { *out = this; AddRef(); return S_OK; }
        *out = nullptr; return E_NOINTERFACE;
    }
    ULONG __stdcall AddRef() override { return ++refs; }
    ULONG __stdcall Release() override { return --refs; }   // owned by DWriteText for the process lifetime
    HRESULT __stdcall CreateStreamFromKey(const void *key, UINT32 keySize, IDWriteFontFileStream **stream) override {
        if (key == nullptr || keySize != sizeof(SkTypefaceID)) { return E_INVALIDARG; }
        auto found = fonts.find(*(const SkTypefaceID *) key);
        if (found == fonts.end()) { return E_INVALIDARG; }
        *stream = new MemoryFontStream(found->second);
        return S_OK;
    }
};

struct DWriteText {
    std::mutex lock;
    ComPtr<IDWriteFactory2> factory;
    MemoryFontLoader loader;
    ComPtr<IDWriteRenderingParams> params;   // the primary monitor's system parameters
    ComPtr<ID2D1Factory> d2d;
    ComPtr<ID2D1DCRenderTarget> target;
    std::unordered_map<SkTypefaceID, ComPtr<IDWriteFontFace>> faces;
    bool initialized = false, failed = false;

    bool init() {
        if (initialized) {
            return !failed;
        }
        initialized = true;
        failed = true;
        if (FAILED(DWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED, __uuidof(IDWriteFactory2), (IUnknown **) factory.GetAddressOf()))
            || FAILED(factory->RegisterFontFileLoader(&loader))
            || FAILED(factory->CreateRenderingParams(&params))
            || FAILED(D2D1CreateFactory(D2D1_FACTORY_TYPE_MULTI_THREADED, d2d.GetAddressOf()))) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "SKPlatform: DirectWrite is unavailable for glyph masks");
            return false;
        }
        const D2D1_RENDER_TARGET_PROPERTIES properties = D2D1::RenderTargetProperties(
            D2D1_RENDER_TARGET_TYPE_SOFTWARE, D2D1::PixelFormat(DXGI_FORMAT_B8G8R8A8_UNORM, D2D1_ALPHA_MODE_IGNORE), 96, 96);
        if (FAILED(d2d->CreateDCRenderTarget(&properties, &target))) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "SKPlatform: Direct2D is unavailable for glyph masks");
            return false;
        }
        target->SetTextRenderingParams(params.Get());
        failed = false;
        return true;
    }

    // The typeface's face, made once from its font data.
    ComPtr<IDWriteFontFace> faceFor(const SkTypeface *typeface) {
        auto found = faces.find(typeface->uniqueID());
        if (found != faces.end()) {
            return found->second;
        }
        ComPtr<IDWriteFontFace> face;
        int index = 0;
        std::unique_ptr<SkStreamAsset> stream = typeface->openStream(&index);
        if (stream && stream->getLength() > 0) {
            auto data = std::make_shared<std::vector<uint8_t>>(stream->getLength());
            if (stream->read(data->data(), data->size()) == data->size()) {
                const SkTypefaceID key = typeface->uniqueID();
                loader.fonts[key] = data;
                ComPtr<IDWriteFontFile> file;
                BOOL supported = FALSE;
                DWRITE_FONT_FILE_TYPE fileType;
                DWRITE_FONT_FACE_TYPE faceType = DWRITE_FONT_FACE_TYPE_UNKNOWN;
                UINT32 faceCount = 0;
                if (SUCCEEDED(factory->CreateCustomFontFileReference(&key, sizeof key, &loader, &file))
                    && SUCCEEDED(file->Analyze(&supported, &fileType, &faceType, &faceCount)) && supported) {
                    IDWriteFontFile *files[] = {file.Get()};
                    if (FAILED(factory->CreateFontFace(faceType, 1, files, (UINT32) index, DWRITE_FONT_SIMULATIONS_NONE, &face))) {
                        face.Reset();
                    }
                }
            }
        }
        if (!face) {
            J2dRlsTraceLn(J2D_TRACE_WARNING, "SKPlatform: DirectWrite cannot load the font data of typeface %u", typeface->uniqueID());
        }
        faces[typeface->uniqueID()] = face;   // a null entry remembers the failure
        return face;
    }
};

DWriteText &dwriteText() {
    static DWriteText text;
    return text;
}

// The run and its transform: a unit-em glyph through glyphToDevice, split into
// the pixel size (for DirectWrite's rendering-mode and hinting decisions) and
// the remaining shape; the mask origin goes into the transform's translation.
// With pixelsPerDip above 1 the em size and the origin are in DIPs of a target
// at that DPI (Direct2D's DPI for the smoothed look on a scaled display); the
// pixel size stays the device one.
struct GlyphRunSetup {
    UINT16 index;
    FLOAT advance = 0;
    DWRITE_GLYPH_OFFSET offset = {0, 0};
    DWRITE_GLYPH_RUN run = {};
    DWRITE_MATRIX matrix = {};
    float pixelSize = 0;

    GlyphRunSetup(IDWriteFontFace *face, uint16_t glyph, const SkMatrix &glyphToDevice, float originX, float originY,
                  float pixelsPerDip = 1) : index(glyph) {
        pixelSize = std::sqrt(std::fabs(glyphToDevice.getScaleX() * glyphToDevice.getScaleY()
                                        - glyphToDevice.getSkewX() * glyphToDevice.getSkewY()));
        if (pixelSize <= 0) {
            pixelSize = 1;
        }
        if (!(pixelsPerDip > 0)) {
            pixelsPerDip = 1;
        }
        run.fontFace = face;
        run.fontEmSize = pixelSize / pixelsPerDip;
        run.glyphCount = 1;
        run.glyphIndices = &index;
        run.glyphAdvances = &advance;
        run.glyphOffsets = &offset;
        run.isSideways = FALSE;
        run.bidiLevel = 0;
        matrix.m11 = glyphToDevice.getScaleX() / pixelSize;
        matrix.m12 = glyphToDevice.getSkewY() / pixelSize;
        matrix.m21 = glyphToDevice.getSkewX() / pixelSize;
        matrix.m22 = glyphToDevice.getScaleY() / pixelSize;
        matrix.dx = originX / pixelsPerDip;
        matrix.dy = originY / pixelsPerDip;
    }
};

DWRITE_RENDERING_MODE recommendedMode(DWriteText &text, IDWriteFontFace *face, float pixelSize) {
    DWRITE_RENDERING_MODE mode = DWRITE_RENDERING_MODE_NATURAL;
    if (FAILED(face->GetRecommendedRenderingMode(pixelSize, 1.0f, DWRITE_MEASURING_MODE_NATURAL, text.params.Get(), &mode))
        || mode == DWRITE_RENDERING_MODE_DEFAULT || mode == DWRITE_RENDERING_MODE_OUTLINE) {
        mode = DWRITE_RENDERING_MODE_NATURAL;
    }
    return mode;
}

// Linear coverage: the analysis' alpha texture, grayscale or ClearType 3x1.
bool coverageMask(DWriteText &text, IDWriteFontFace *face, uint16_t glyph, const SkMatrix &glyphToDevice, bool lcd,
                  float strokeWidth, float originX, float originY, int width, int height, uint8_t *mask)
{
    const int channels = lcd ? 3 : 1;
    memset(mask, 0, (size_t) width * height * channels);
    const int passes = strokeWidth > 0 ? 2 : 1;   // synthetic bold: the glyph again, shifted by the emboldening
    for (int pass = 0; pass < passes; pass++) {
        GlyphRunSetup setup(face, glyph, glyphToDevice, originX + (pass == 1 ? strokeWidth : 0), originY);
        ComPtr<IDWriteGlyphRunAnalysis> analysis;
        if (FAILED(text.factory->CreateGlyphRunAnalysis(&setup.run, &setup.matrix, recommendedMode(text, face, setup.pixelSize),
                                                       DWRITE_MEASURING_MODE_NATURAL, DWRITE_GRID_FIT_MODE_DEFAULT,
                                                       lcd ? DWRITE_TEXT_ANTIALIAS_MODE_CLEARTYPE : DWRITE_TEXT_ANTIALIAS_MODE_GRAYSCALE,
                                                       0, 0, &analysis))) {
            return false;
        }
        const DWRITE_TEXTURE_TYPE type = lcd ? DWRITE_TEXTURE_CLEARTYPE_3x1 : DWRITE_TEXTURE_ALIASED_1x1;
        RECT bounds = {};
        if (FAILED(analysis->GetAlphaTextureBounds(type, &bounds)) || bounds.right <= bounds.left || bounds.bottom <= bounds.top) {
            continue;   // nothing inked (a space)
        }
        const int tw = bounds.right - bounds.left, th = bounds.bottom - bounds.top;
        std::vector<uint8_t> texture((size_t) tw * th * channels);
        if (FAILED(analysis->CreateAlphaTexture(type, &bounds, texture.data(), (UINT32) texture.size()))) {
            return false;
        }
        for (int y = 0; y < th; y++) {
            const int my = bounds.top + y;
            if (my < 0 || my >= height) {
                continue;
            }
            for (int x = 0; x < tw; x++) {
                const int mx = bounds.left + x;
                if (mx < 0 || mx >= width) {
                    continue;
                }
                for (int c = 0; c < channels; c++) {
                    uint8_t &dst = mask[((size_t) my * width + mx) * channels + c];
                    const uint8_t src = texture[((size_t) y * tw + x) * channels + c];
                    dst = (uint8_t) std::max<int>(dst, src);   // the union of the two bold passes
                }
            }
        }
    }
    return true;
}

// The native look: Direct2D with the system's parameters, text of the given
// luminance on the opposite paper, coverage = what the paper lost. The target
// runs at the display's DPI and the glyph is drawn in DIPs there, as every
// Windows application draws its text: from a display scale of 1.8 on, Direct2D
// samples natural-symmetric coverage on a 2 x 2 grid per device pixel instead
// of 4 x 4, so a 13 DIP glyph at 192 dpi has 4 coverage steps per pixel where a
// 26 px glyph at 96 dpi has 16 (dwref --dpi, --scale: same total ink, same
// blend parameters, the natural mode and DirectWrite's own analysis textures
// are DPI-invariant).
bool smoothedMask(DWriteText &text, IDWriteFontFace *face, uint16_t glyph, const SkMatrix &glyphToDevice, float displayScale,
                  int luminance, bool lcd, float strokeWidth, float originX, float originY, int width, int height, uint8_t *mask)
{
    if (!(displayScale > 0)) {
        displayScale = 1;
    }
    const bool lightText = luminance >= 128;
    const int paperByte = lightText ? 0 : 255, inkByte = luminance;
    const int range = std::abs(paperByte - inkByte);
    if (range == 0) {
        return false;
    }
    BITMAPINFO info = {};
    info.bmiHeader.biSize = sizeof(info.bmiHeader);
    info.bmiHeader.biWidth = width;
    info.bmiHeader.biHeight = -height;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biCompression = BI_RGB;
    void *bits = nullptr;
    HDC dc = CreateCompatibleDC(nullptr);
    HBITMAP dib = CreateDIBSection(dc, &info, DIB_RGB_COLORS, &bits, nullptr, 0);
    if (dib == nullptr || bits == nullptr) {
        DeleteDC(dc);
        return false;
    }
    HGDIOBJ previous = SelectObject(dc, dib);
    const RECT rect = {0, 0, width, height};
    bool ok = false;
    text.target->SetDpi(96 * displayScale, 96 * displayScale);
    if (SUCCEEDED(text.target->BindDC(dc, &rect))) {
        ComPtr<ID2D1SolidColorBrush> brush;
        const float ink = inkByte / 255.0f;
        if (SUCCEEDED(text.target->CreateSolidColorBrush(D2D1::ColorF(ink, ink, ink), &brush))) {
            text.target->SetTextAntialiasMode(lcd ? D2D1_TEXT_ANTIALIAS_MODE_CLEARTYPE : D2D1_TEXT_ANTIALIAS_MODE_GRAYSCALE);
            text.target->BeginDraw();
            const float paper = paperByte / 255.0f;
            text.target->SetTransform(D2D1::Matrix3x2F::Identity());
            text.target->Clear(D2D1::ColorF(paper, paper, paper));
            const int passes = strokeWidth > 0 ? 2 : 1;
            for (int pass = 0; pass < passes; pass++) {
                GlyphRunSetup setup(face, glyph, glyphToDevice, originX + (pass == 1 ? strokeWidth : 0), originY, displayScale);
                text.target->SetTransform(D2D1::Matrix3x2F(setup.matrix.m11, setup.matrix.m12, setup.matrix.m21, setup.matrix.m22,
                                                           setup.matrix.dx, setup.matrix.dy));
                text.target->DrawGlyphRun(D2D1::Point2F(0, 0), &setup.run, brush.Get(), DWRITE_MEASURING_MODE_NATURAL);
            }
            ok = SUCCEEDED(text.target->EndDraw());
        }
    }
    GdiFlush();
    if (ok) {
        const int channels = lcd ? 3 : 1;
        for (int y = 0; y < height; y++) {
            const uint8_t *row = (const uint8_t *) bits + (size_t) y * width * 4;   // B, G, R, x
            uint8_t *dst = mask + (size_t) y * width * channels;
            for (int x = 0; x < width; x++) {
                if (lcd) {
                    for (int c = 0; c < 3; c++) {
                        const int value = row[x * 4 + (2 - c)];   // R, G, B order in the mask
                        dst[x * 3 + c] = (uint8_t) std::min(255, std::abs(value - paperByte) * 255 / range);
                    }
                } else {
                    dst[x] = (uint8_t) std::min(255, std::abs((int) row[x * 4 + 1] - paperByte) * 255 / range);
                }
            }
        }
    }
    SelectObject(dc, previous);
    DeleteObject(dib);
    DeleteDC(dc);
    return ok;
}

} // namespace

// The display scale from which DirectWrite's symmetric mode halves its placement
// and coverage sampling (measured between 1.792 and 1.8125; 173 dpi).
const float HIGH_DPI_SCALE = 1.8f;

// Measured with dwref --grid (one glyph at 64 offsets across a pixel, through the
// glyph run analysis and Direct2D alike): the natural-symmetric mode positions
// glyphs on quarter pixels, the natural mode on eighths, both to the nearest;
// aliased and GDI-compatible modes on whole pixels. The grid is per device
// pixel at every display scale, but the symmetric mode has two regimes by the
// display scale (dwref --scale, Direct2D targets from 96 to 384 dpi): quarter
// pixels up to a scale of 1.79, half pixels from 1.81 on (and 2 x 2 coverage
// samples per pixel instead of 4 x 4), whatever the pixel size; the natural
// mode keeps its eighths at every scale. ClearType (dwref --scale-lcd) positions
// on sixths of a pixel - half a colour sub-pixel - in both modes at every scale.
int SKPlatform_GlyphPlacementGrid(const SkTypeface *typeface, float pixelSize, float displayScale, bool lcd)
{
    if (typeface == nullptr || pixelSize <= 0) {
        return 0;
    }
    if (!(displayScale > 0)) {
        displayScale = 1;
    }
    DWriteText &text = dwriteText();
    std::lock_guard<std::mutex> guard(text.lock);
    if (!text.init()) {
        return 0;
    }
    ComPtr<IDWriteFontFace> face = text.faceFor(typeface);
    if (!face) {
        return 0;
    }
    switch (recommendedMode(text, face.Get(), pixelSize)) {
        case DWRITE_RENDERING_MODE_NATURAL: return lcd ? 6 : 8;
        case DWRITE_RENDERING_MODE_NATURAL_SYMMETRIC: return lcd ? 6 : displayScale >= HIGH_DPI_SCALE ? 2 : 4;
        default: return 1;
    }
}

bool SKPlatform_SmoothedGlyphMask(const SkTypeface *typeface, uint16_t glyph, const SkMatrix &glyphToDevice, float displayScale,
                                  int luminance, bool lcd, float strokeWidth, float originX, float originY, int width, int height,
                                  uint8_t *mask)
{
    if (typeface == nullptr || mask == nullptr || width <= 0 || height <= 0) {
        return false;
    }
    DWriteText &text = dwriteText();
    std::lock_guard<std::mutex> guard(text.lock);
    if (!text.init()) {
        return false;
    }
    ComPtr<IDWriteFontFace> face = text.faceFor(typeface);
    if (!face) {
        return false;
    }
    return luminance < 0 ? coverageMask(text, face.Get(), glyph, glyphToDevice, lcd, strokeWidth, originX, originY, width, height, mask)
                         : smoothedMask(text, face.Get(), glyph, glyphToDevice, displayScale, luminance, lcd, strokeWidth, originX, originY,
                                        width, height, mask);
}
