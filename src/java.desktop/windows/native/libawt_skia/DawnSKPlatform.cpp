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

// SKPlatform on Dawn (DawnSKPlatform.h): Graphite/Dawn contexts on the Dawn device shared by the
// Win32SKGraphicsConfigs of a Direct3D version, Skia surfaces over textures this
// file creates, presentation by copying the texture into the HWND's swap chain.

#include "DawnSKPlatform.h"
#include "SKNeverDestroyed.h"
#include "SKPlatform.h"
#include "SKPipelineCache.h"

#include "Trace.h"
#include "include/core/SkColorType.h"
#include "include/gpu/graphite/dawn/DawnBackendContext.h"
#include "include/gpu/graphite/dawn/DawnGraphiteTypes.h"
#include "dawn/dawn_proc.h"
#include "dawn/native/DawnNative.h"
#include "webgpu/webgpu_cpp.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

using namespace skgpu::graphite;

namespace {

// The GPU side of one Direct3D version (11 or 12), shared by all its configurations
// and their surfaces (reference counted), so a surface never dereferences a
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
struct DawnSKConfigInfo : Win32SKConfigInfo {
    DawnSKConfigInfo() : Win32SKConfigInfo{&DawnSKPlatform::graphicsAPI()} {}
    std::shared_ptr<DawnSKDevice> gpu;
    std::string description;   // "<adapter> (D3D11)" or "<adapter> (D3D12)"
};

// One per Win32SKSurfaceData: the texture Skia renders into and, for window
// surfaces, the swap chain of the HWND's client area that the texture is
// copied to on present. The texture is persistent (Java2D repaints only dirty
// regions), so it is never a swap chain image itself. Like every AWT window
// surface it spans the whole window; the client area starts at the insets
// (Win32SKPlatform::clientArea).
struct DawnSKSurface : Win32SKSurface {
    DawnSKSurface() : Win32SKSurface{&DawnSKPlatform::graphicsAPI()} {}
    DawnSKConfigInfo *config = nullptr;   // identity for the shared backend's context lookup; not dereferenced
    std::shared_ptr<DawnSKDevice> gpu;
    HWND hwnd = nullptr;
    wgpu::Surface window;      // null for off-screen surfaces
    wgpu::SurfaceConfiguration windowConfiguration;   // to reconfigure after Outdated
    wgpu::Texture texture;
    uint32_t width = 0;
    uint32_t height = 0;
    Win32SKClientArea clientArea;   // the insets and the swap chain's size (zero for off-screen surfaces)
    bool noImageReported = false;   // the swap chain failure is logged once per surface
};

// Dawn as a Win32SKGraphicsAPI, the API of every Dawn configuration and surface (see there); the
// configurations and surfaces it is given are Dawn's.
class DawnSKGraphicsAPI final : public Win32SKGraphicsAPI {
public:
    Win32SKAPI api() const override { return Win32SKAPI::Dawn; }
    // "<adapter> (D3D11)"; no device is created.
    std::string describeAdapter() override;
    // The preferred device features (PREFERRED_FEATURES) of the last device created, as "name=o/g ..." with
    // o whether the adapter offers it and g whether the device has it; empty before the first device.
    std::string deviceFeatures() override;

    // A configuration on the shared device of the first Direct3D version that yields
    // one (11 first, 12 as fallback, unless DawnSKPlatform::preferD3D12); a lost device
    // is replaced. Null if none.
    Win32SKConfigInfo *createConfig() override;
    void disposeConfig(Win32SKConfigInfo *config) override;
    bool isLost(const Win32SKConfigInfo *config) override;
    std::string configDescription(const Win32SKConfigInfo *config) override;
    void simulateDeviceLoss(bool persistent) override;
    // Texture (plus the configured swap chain of the window's client area if hwnd
    // is non-null); null on failure, including a lost device, a texture beyond the
    // adapter's limit or a window Dawn refuses. DXGI allows one flip-model swap
    // chain per HWND and AWT replaces a window's surface on every bounds change,
    // so a window surface takes the swap chain over from `previous`, the surface
    // it replaces (the caller keeps the flusher idle meanwhile), and reconfigures
    // it.
    Win32SKSurface *createSurface(Win32SKConfigInfo *config, HWND hwnd, uint32_t width, uint32_t height,
                                  Win32SKSurface *previous, bool opaque) override;

    std::unique_ptr<Context> makeContext(Win32SKConfigInfo *config, const ContextOptions &options) override;
    Win32SKConfigInfo *configOf(Win32SKSurface *surface) override;
    bool sameDevice(Win32SKConfigInfo *first, Win32SKConfigInfo *second) override;
    void *textureHandle(Win32SKSurface *surface) override;
    bool backendTexture(Win32SKSurface *surface, BackendTexture *texture, SkColorType *colorType) override;
    bool requestRedraw(Win32SKSurface *surface) override;
    // Dawn presents itself (requestRedraw).
    bool acquirePresentTarget(Win32SKSurface *surface, SKPlatformPresentTarget *target) override { return false; }
    bool presentTarget(Win32SKSurface *surface) override { return false; }
    void disposeSurface(Win32SKSurface *surface) override;
};

// The configurations and surfaces the API is given are the ones it made (Win32SKGraphicsAPI).
DawnSKConfigInfo *dawnConfigOf(Win32SKConfigInfo *config) {
    return static_cast<DawnSKConfigInfo *>(config);
}

const DawnSKConfigInfo *dawnConfigOf(const Win32SKConfigInfo *config) {
    return static_cast<const DawnSKConfigInfo *>(config);
}

DawnSKSurface *dawnSurfaceOf(Win32SKSurface *surface) {
    return static_cast<DawnSKSurface *>(surface);
}

std::string toString(wgpu::StringView view) {
    return std::string(std::string_view(view));
}

// Dawn calls the Direct3D version it runs on its backend (wgpu::BackendType: D3D11, D3D12);
// in this code base "backend" is the Skia backend as a whole, so here it is the Direct3D
// version. Its name in the adapter descriptions.
const char *direct3DVersionName(wgpu::BackendType direct3DVersion) {
    switch (direct3DVersion) {
        case wgpu::BackendType::D3D11: return "D3D11";
        case wgpu::BackendType::D3D12: return "D3D12";
        default: return "unknown Direct3D version";
    }
}

// The surfaces are BGRA8 like the Direct3D swap chains they are copied to.
constexpr wgpu::TextureFormat SURFACE_FORMAT = wgpu::TextureFormat::BGRA8Unorm;
constexpr SkColorType SURFACE_COLOR_TYPE = kBGRA_8888_SkColorType;

// The Direct3D versions Dawn can run on here, Direct3D 11 first: the lowest common
// denominator of Windows GPUs and VMs. The index into this array is also the device slot.
constexpr std::array<wgpu::BackendType, 2> DIRECT3D_VERSIONS = { wgpu::BackendType::D3D11,
                                                                  wgpu::BackendType::D3D12 };
// The order the versions are tried in, as indices into DIRECT3D_VERSIONS.
// sun.java2d.skia.dawn.api=d3d12 tries Direct3D 12 first (for triage: the version Chrome ships
// WebGPU on; Graphite is tested on Direct3D 11 first). Set once, before the first adapter request.
std::array<size_t, DIRECT3D_VERSIONS.size()> gDirect3DOrder = { 0, 1 };

} // namespace

void DawnSKPlatform::preferD3D12(bool preferD3D12) {
    gDirect3DOrder = preferD3D12 ? std::array<size_t, 2>{ 1, 0 } : std::array<size_t, 2>{ 0, 1 };
}

namespace {

// WebGPU's default maxTextureDimension2D, the limit a device gets when the adapter's limits
// cannot be read (they are required at creation, so this is only a fallback).
constexpr uint32_t WEBGPU_DEFAULT_MAX_TEXTURE_SIZE = 8192;

// wgpu::Instance::WaitAny's timeout for the requests this file makes synchronously: none.
constexpr uint64_t WAIT_WITHOUT_TIMEOUT = UINT64_MAX;

// One device per Direct3D version, shared by the configurations (as the D3D pipeline
// keeps its device across configurations): a display change (resolution, DPI,
// a monitor plugged) recreates the configurations, and a device per
// configuration left every texture of the old one unusable on the new
// (DawnSKGraphicsAPI::sameDevice) and cost a device each time. A lost device is
// dropped, so recovery gets a new one.
//
// The devices and the instance are never destroyed (SKNeverDestroyed.h): at process exit their
// destructors would run during DLL detach, under the loader lock, and Dawn's
// D3D12 backend fails fast releasing its device there (SkiaDawnBackendTest,
// dawn.api=d3d12: exit code 0xC0000409 after the test passed).
std::mutex gDevicesLock;
using DawnSKDeviceSlots = std::array<std::shared_ptr<DawnSKDevice>, DIRECT3D_VERSIONS.size()>;
SKNeverDestroyed<DawnSKDeviceSlots> gDevices;   // indexed like DIRECT3D_VERSIONS

// ---------------------------------------------------------------------------
// Instance, adapter, device
// ---------------------------------------------------------------------------

// The process-wide Dawn instance; created on first use, null if Dawn cannot start.
wgpu::Instance dawnInstance() {
    static SKNeverDestroyed<wgpu::Instance> instance([] {   // never destroyed (gDevices)
        // Dawn's C entry points dispatch through a process-wide proc table that
        // the native implementation has to be installed in first.
        dawnProcSetProcs(&dawn::native::GetProcs());
        wgpu::InstanceFeatureName timedWait = wgpu::InstanceFeatureName::TimedWaitAny;
        wgpu::InstanceDescriptor descriptor;
        descriptor.requiredFeatureCount = 1;
        descriptor.requiredFeatures = &timedWait;   // lets us wait for adapter/device requests synchronously
        wgpu::Instance created = wgpu::CreateInstance(&descriptor);
        if (created == nullptr) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "DawnSK: cannot create the Dawn instance");
        }
        return created;
    }());
    return *instance;
}

wgpu::Adapter requestAdapter(const wgpu::Instance &instance, wgpu::BackendType direct3DVersion) {
    wgpu::RequestAdapterOptions options;
    options.backendType = direct3DVersion;
    options.powerPreference = wgpu::PowerPreference::HighPerformance;
    wgpu::Adapter adapter;
    wgpu::Future request = instance.RequestAdapter(&options, wgpu::CallbackMode::WaitAnyOnly,
            [&adapter, direct3DVersion](wgpu::RequestAdapterStatus status, wgpu::Adapter result, wgpu::StringView message) {
                if (status == wgpu::RequestAdapterStatus::Success) {
                    adapter = std::move(result);
                } else {
                    J2dRlsTraceLn(J2D_TRACE_INFO, "DawnSK: no %s adapter: %s", direct3DVersionName(direct3DVersion),
                                  toString(message).c_str());
                }
            });
    instance.WaitAny(request, WAIT_WITHOUT_TIMEOUT);
    return adapter;
}

// The adapter of the first Direct3D version (in gDirect3DOrder) that has one, with that version;
// null and Undefined if none has.
wgpu::Adapter firstAdapter(const wgpu::Instance &instance, wgpu::BackendType *direct3DVersion) {
    for (size_t slot : gDirect3DOrder) {
        const wgpu::BackendType candidate = DIRECT3D_VERSIONS[slot];
        wgpu::Adapter adapter = requestAdapter(instance, candidate);
        if (adapter != nullptr) {
            *direct3DVersion = candidate;
            return adapter;
        }
    }
    *direct3DVersion = wgpu::BackendType::Undefined;
    return nullptr;
}

// "<adapter> (D3D11)": the adapter's name and the Direct3D version.
std::string adapterDescription(const wgpu::Adapter &adapter, wgpu::BackendType direct3DVersion) {
    wgpu::AdapterInfo info;
    adapter.GetInfo(&info);
    return toString(info.device) + " (" + direct3DVersionName(direct3DVersion) + ")";
}

} // namespace

std::string DawnSKGraphicsAPI::describeAdapter() {
    wgpu::Instance instance = dawnInstance();
    wgpu::BackendType direct3DVersion;
    wgpu::Adapter adapter = instance != nullptr ? firstAdapter(instance, &direct3DVersion) : nullptr;
    return adapter != nullptr ? adapterDescription(adapter, direct3DVersion) : std::string();
}

namespace {

// A WebGPU device has the features it was created with, not the adapter's: Graphite enables what it
// finds on the device (DawnCaps), e.g. dual-source blending, without which it blends coverage for
// modes such as Src and Clear, and its own LCD text, in the shader from a copy of the destination
// per draw. The features Skia's own Dawn harness asks for wherever the adapter has them
// (tools/graphite/dawn/GraphiteDawnToggles.cpp, AddPreferredFeatures), but timestamp queries, which
// Graphite only uses for GPU timings.
struct DawnSKPreferredFeature {
    wgpu::FeatureName feature;
    const char *name;
};
constexpr std::array<DawnSKPreferredFeature, 16> PREFERRED_FEATURES = {{
    {wgpu::FeatureName::ImplicitDeviceSynchronization, "ImplicitDeviceSynchronization"},
    {wgpu::FeatureName::DualSourceBlending, "DualSourceBlending"},
    {wgpu::FeatureName::FramebufferFetch, "FramebufferFetch"},
    {wgpu::FeatureName::BufferMapExtendedUsages, "BufferMapExtendedUsages"},
    {wgpu::FeatureName::DawnAllowUndefinedLoadStoreOp, "DawnAllowUndefinedLoadStoreOp"},
    {wgpu::FeatureName::DawnLoadResolveTexture, "DawnLoadResolveTexture"},
    {wgpu::FeatureName::DawnPartialLoadResolveTexture, "DawnPartialLoadResolveTexture"},
    {wgpu::FeatureName::DawnTexelCopyBufferRowAlignment, "DawnTexelCopyBufferRowAlignment"},
    {wgpu::FeatureName::MSAARenderToSingleSampled, "MSAARenderToSingleSampled"},
    {wgpu::FeatureName::ShaderF16, "ShaderF16"},
    {wgpu::FeatureName::TextureCompressionBC, "TextureCompressionBC"},
    {wgpu::FeatureName::TextureCompressionETC2, "TextureCompressionETC2"},
    {wgpu::FeatureName::TextureFormatsTier1, "TextureFormatsTier1"},
    {wgpu::FeatureName::TransientAttachments, "TransientAttachments"},
    {wgpu::FeatureName::Unorm16TextureFormats, "Unorm16TextureFormats"},
    {wgpu::FeatureName::RenderPassRenderArea, "RenderPassRenderArea"},
}};

std::mutex gFeatureReportLock;
std::string gFeatureReport;   // of the last device created (DawnSKGraphicsAPI::deviceFeatures)

} // namespace

std::string DawnSKGraphicsAPI::deviceFeatures() {
    std::lock_guard<std::mutex> lock(gFeatureReportLock);
    return gFeatureReport;
}

namespace {

wgpu::Device requestDevice(const wgpu::Instance &instance, const wgpu::Adapter &adapter,
                           const std::shared_ptr<std::atomic<bool>> &lost, const std::string &description) {
    wgpu::DeviceDescriptor descriptor;
    // Off-screen surfaces are created on the EDT while the flusher records and
    // submits on the same device; Dawn serializes device access with this.
    if (!adapter.HasFeature(wgpu::FeatureName::ImplicitDeviceSynchronization)) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "DawnSK: adapter lacks ImplicitDeviceSynchronization");
    }
    // The features Graphite would use (PREFERRED_FEATURES, above), wherever the adapter has them.
    std::vector<wgpu::FeatureName> features;
    for (const DawnSKPreferredFeature &preferred : PREFERRED_FEATURES) {
        if (adapter.HasFeature(preferred.feature)) {
            features.push_back(preferred.feature);
        }
    }
    descriptor.requiredFeatureCount = features.size();
    descriptor.requiredFeatures = features.data();
    // The adapter's limits, not WebGPU's defaults: a device gets textures of
    // WEBGPU_DEFAULT_MAX_TEXTURE_SIZE pixels at most otherwise, while Direct3D 11
    // adapters allow 16384, and a tall off-screen image of a HiDPI screen (a 2x
    // scaled 4608-pixel sheet) would fall back to software.
    wgpu::Limits limits;
    if (adapter.GetLimits(&limits) == wgpu::Status::Success) {
        descriptor.requiredLimits = &limits;
    }
    // sun.java2d.skia.pipelineCache: Dawn asks for the shaders and pipelines it compiled in
    // earlier sessions by its own key (adapter, driver, toggles and Dawn version are part of
    // it) and hands over new ones; the store lives as long as the process (SKPipelineCache).
    wgpu::DawnCacheDeviceDescriptor cache;
    if (SKBlobStore *store = SKPipelineCache::blobStore("dawn-" + description)) {
        cache.isolationKey = "jbr-skia";
        cache.SetDawnLoadCacheDataCallback(
                [](std::span<const std::byte> key, std::span<std::byte> value, SKBlobStore *blobStore) {
                    return blobStore->load(key.data(), key.size(), value.data(), value.size());
                }, store);
        cache.SetDawnStoreCacheDataCallback(
                [](std::span<const std::byte> key, std::span<const std::byte> value, SKBlobStore *blobStore) {
                    blobStore->store(key.data(), key.size(), value.data(), value.size());
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
    instance.WaitAny(request, WAIT_WITHOUT_TIMEOUT);
    if (device != nullptr) {
        std::string report;
        for (const DawnSKPreferredFeature &preferred : PREFERRED_FEATURES) {
            report += std::string(report.empty() ? "" : " ") + preferred.name + "="
                      + (adapter.HasFeature(preferred.feature) ? "1" : "0") + "/"
                      + (device.HasFeature(preferred.feature) ? "1" : "0");
        }
        J2dRlsTraceLn(J2D_TRACE_INFO, "DawnSK: device features (offered/granted) %s", report.c_str());
        std::lock_guard<std::mutex> lock(gFeatureReportLock);
        gFeatureReport = report;
    }
    return device;
}

// Tests (Win32SKGraphicsConfig.simulateDeviceLoss): every device counts as lost, as after a
// driver reset; with persistent also every device created afterwards once its first window
// surface exists, as on a driver that keeps failing.
std::atomic<bool> gLoseNewDevices{false};

} // namespace

void DawnSKGraphicsAPI::simulateDeviceLoss(bool persistent) {
    gLoseNewDevices.store(persistent);
    std::lock_guard<std::mutex> lock(gDevicesLock);
    for (const std::shared_ptr<DawnSKDevice> &gpu : *gDevices) {
        if (gpu != nullptr) {
            gpu->lost->store(true);
        }
    }
}

Win32SKConfigInfo *DawnSKGraphicsAPI::createConfig() {
    wgpu::Instance instance = dawnInstance();
    if (instance == nullptr) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(gDevicesLock);
    for (size_t slot : gDirect3DOrder) {
        const wgpu::BackendType direct3DVersion = DIRECT3D_VERSIONS[slot];
        std::shared_ptr<DawnSKDevice> gpu = (*gDevices)[slot];
        if (gpu != nullptr && gpu->lost->load()) {
            (*gDevices)[slot].reset();
            gpu.reset();
        }
        const bool shared = gpu != nullptr;
        if (!shared) {
            wgpu::Adapter adapter = requestAdapter(instance, direct3DVersion);
            if (adapter == nullptr) {
                continue;
            }
            gpu = std::make_shared<DawnSKDevice>();
            gpu->device = requestDevice(instance, adapter, gpu->lost, adapterDescription(adapter, direct3DVersion));
            if (gpu->device == nullptr) {
                continue;
            }
            gpu->adapter = std::move(adapter);
            gpu->queue = gpu->device.GetQueue();
            wgpu::Limits limits;   // the device's limits (the adapter's were required at creation)
            gpu->maxTextureSize = gpu->device.GetLimits(&limits) == wgpu::Status::Success
                                  ? limits.maxTextureDimension2D : WEBGPU_DEFAULT_MAX_TEXTURE_SIZE;
            (*gDevices)[slot] = gpu;
        }
        auto *config = new DawnSKConfigInfo();
        config->gpu = std::move(gpu);
        config->description = adapterDescription(config->gpu->adapter, direct3DVersion);
        J2dRlsTraceLn(J2D_TRACE_INFO, "DawnSKGraphicsAPI::createConfig: %s, max texture %u, %s device",
                      config->description.c_str(), config->gpu->maxTextureSize, shared ? "shared" : "new");
        return config;
    }
    J2dRlsTraceLn(J2D_TRACE_ERROR, "DawnSKGraphicsAPI::createConfig: no Direct3D adapter yields a Dawn device");
    return nullptr;
}

void DawnSKGraphicsAPI::disposeConfig(Win32SKConfigInfo *config) {
    delete dawnConfigOf(config);
}

bool DawnSKGraphicsAPI::isLost(const Win32SKConfigInfo *config) {
    const DawnSKConfigInfo *dawnConfig = dawnConfigOf(config);
    return dawnConfig == nullptr || dawnConfig->gpu == nullptr || dawnConfig->gpu->lost->load();
}

std::string DawnSKGraphicsAPI::configDescription(const Win32SKConfigInfo *config) {
    return dawnConfigOf(config)->description;
}

// ---------------------------------------------------------------------------
// Surfaces
// ---------------------------------------------------------------------------

namespace {

// Dawn returns error objects rather than null for failed creation and
// configuration; an error scope around the calls turns them into a result.
// Only validation and out-of-memory errors: a lost device reports through its
// own callback.
constexpr int ERROR_SCOPE_COUNT = 2;   // pushErrorScopes pushes two, popErrorScopes pops both

void pushErrorScopes(const wgpu::Device &device) {
    device.PushErrorScope(wgpu::ErrorFilter::OutOfMemory);
    device.PushErrorScope(wgpu::ErrorFilter::Validation);
}

bool popErrorScopes(const wgpu::Device &device, const char *what) {
    bool succeeded = true;
    for (int scope = 0; scope < ERROR_SCOPE_COUNT; scope++) {
        wgpu::Future pop = device.PopErrorScope(wgpu::CallbackMode::WaitAnyOnly,
                [&succeeded, what](wgpu::PopErrorScopeStatus status, wgpu::ErrorType type, wgpu::StringView message) {
                    if (status == wgpu::PopErrorScopeStatus::Success && type != wgpu::ErrorType::NoError) {
                        J2dRlsTraceLn(J2D_TRACE_WARNING, "DawnSK: %s failed (%d): %s", what, (int) type,
                                      toString(message).c_str());
                        succeeded = false;
                    }
                });
        dawnInstance().WaitAny(pop, WAIT_WITHOUT_TIMEOUT);
    }
    return succeeded;
}

// Swap chain configuration for the surface's window from what the swap chain
// supports; false if it cannot take our BGRA8 copy.
bool configureWindow(DawnSKSurface *surface, bool opaque) {
    wgpu::SurfaceCapabilities capabilities;
    if (surface->window.GetCapabilities(surface->gpu->adapter, &capabilities) != wgpu::Status::Success) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "DawnSK: no swap chain capabilities for the window");
        return false;
    }
    const std::span<const wgpu::TextureFormat> formats(capabilities.formats, capabilities.formatCount);
    const bool formatSupported = std::find(formats.begin(), formats.end(), SURFACE_FORMAT) != formats.end();
    // Presents happen per queue flush, so they must not wait for a vblank:
    // Mailbox, else Immediate, else Fifo (the only mode always offered).
    wgpu::PresentMode presentMode = wgpu::PresentMode::Fifo;
    for (wgpu::PresentMode offered : std::span(capabilities.presentModes, capabilities.presentModeCount)) {
        if (offered == wgpu::PresentMode::Mailbox
                || (offered == wgpu::PresentMode::Immediate && presentMode != wgpu::PresentMode::Mailbox)) {
            presentMode = offered;
        }
    }
    const std::span<const wgpu::CompositeAlphaMode> alphaModes(capabilities.alphaModes, capabilities.alphaModeCount);
    const bool opaqueSupported = std::find(alphaModes.begin(), alphaModes.end(), wgpu::CompositeAlphaMode::Opaque)
                                 != alphaModes.end();
    const bool copyDestination = (capabilities.usages & wgpu::TextureUsage::CopyDst) == wgpu::TextureUsage::CopyDst;
    if (!formatSupported || !copyDestination) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "DawnSK: the swap chain cannot take a BGRA8 copy (BGRA8 %d, copyDst %d)",
                      formatSupported, copyDestination);
        return false;
    }
    wgpu::SurfaceConfiguration &configuration = surface->windowConfiguration;
    configuration.device = surface->gpu->device;
    configuration.format = SURFACE_FORMAT;
    configuration.usage = wgpu::TextureUsage::RenderAttachment | wgpu::TextureUsage::CopyDst;
    configuration.width = surface->clientArea.clientWidth;
    configuration.height = surface->clientArea.clientHeight;
    configuration.alphaMode = opaque && opaqueSupported ? wgpu::CompositeAlphaMode::Opaque
                                                        : wgpu::CompositeAlphaMode::Auto;
    configuration.presentMode = presentMode;
    pushErrorScopes(surface->gpu->device);
    surface->window.Configure(&configuration);
    J2dTraceLn(J2D_TRACE_VERBOSE, "DawnSK: swap chain %ux%u presentMode=%d alphaMode=%d", configuration.width,
               configuration.height, (int) presentMode, (int) configuration.alphaMode);
    return popErrorScopes(surface->gpu->device, "swap chain configuration");
}

} // namespace

Win32SKSurface *DawnSKGraphicsAPI::createSurface(Win32SKConfigInfo *win32Config, HWND hwnd, uint32_t width,
                                                 uint32_t height, Win32SKSurface *win32Previous, bool opaque) {
    DawnSKConfigInfo *config = dawnConfigOf(win32Config);
    DawnSKSurface *previous = dawnSurfaceOf(win32Previous);
    if (isLost(config) || width == 0 || height == 0) {
        return nullptr;
    }
    const std::shared_ptr<DawnSKDevice> &gpu = config->gpu;
    if (width > gpu->maxTextureSize || height > gpu->maxTextureSize) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "DawnSKGraphicsAPI::createSurface: %ux%u exceeds the adapter's %u", width, height,
                      gpu->maxTextureSize);
        return nullptr;
    }
    auto surface = std::make_unique<DawnSKSurface>();   // deleted on every failure path below
    surface->config = config;
    surface->gpu = gpu;
    surface->hwnd = hwnd;
    surface->width = width;
    surface->height = height;
    if (hwnd != nullptr) {
        const std::optional<Win32SKClientArea> clientArea = Win32SKPlatform::clientArea(hwnd, width, height);
        if (!clientArea) {
            J2dRlsTraceLn(J2D_TRACE_WARNING, "DawnSKGraphicsAPI::createSurface: HWND %p is gone", hwnd);
            return nullptr;
        }
        surface->clientArea = *clientArea;
    }

    wgpu::TextureDescriptor textureDescriptor;
    textureDescriptor.label = "Java2D Skia surface";
    textureDescriptor.dimension = wgpu::TextureDimension::e2D;
    textureDescriptor.size = { width, height, 1 };
    textureDescriptor.format = SURFACE_FORMAT;
    // render target for Graphite, sampled when drawn into another surface,
    // copy source for presentation and readback, copy destination for uploads
    textureDescriptor.usage = wgpu::TextureUsage::RenderAttachment | wgpu::TextureUsage::TextureBinding
                            | wgpu::TextureUsage::CopySrc | wgpu::TextureUsage::CopyDst;
    pushErrorScopes(gpu->device);
    surface->texture = gpu->device.CreateTexture(&textureDescriptor);
    if (!popErrorScopes(gpu->device, "texture creation") || surface->texture == nullptr) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "DawnSKGraphicsAPI::createSurface: cannot create a %ux%u texture", width, height);
        return nullptr;
    }

    if (hwnd != nullptr) {
        if (previous != nullptr && previous->hwnd == hwnd && previous->window != nullptr
                && previous->gpu->device.Get() != gpu->device.Get()) {
            // A swap chain of another device (the one lost before this configuration's):
            // DXGI swap chains cannot move between Direct3D devices ("D3D SwapChain cannot
            // switch between D3D Devices"), so it is released and the window gets a new one.
            J2dTraceLn(J2D_TRACE_VERBOSE, "DawnSKGraphicsAPI::createSurface: releasing the swap chain of %p (another device)",
                       previous);
            previous->window.Unconfigure();
            previous->window = nullptr;
        }
        if (previous != nullptr && previous->hwnd == hwnd && previous->window != nullptr) {
            // the surface being replaced can no longer present; its texture stays
            // valid until it is disposed
            J2dTraceLn(J2D_TRACE_VERBOSE, "DawnSKGraphicsAPI::createSurface: taking over the swap chain of %p", previous);
            surface->window = std::move(previous->window);
            previous->window = nullptr;
        } else {
            wgpu::SurfaceSourceWindowsHWND source;
            source.hinstance = GetModuleHandleW(nullptr);
            source.hwnd = hwnd;
            wgpu::SurfaceDescriptor descriptor;
            descriptor.nextInChain = &source;
            surface->window = dawnInstance().CreateSurface(&descriptor);
        }
        if (surface->window == nullptr || !configureWindow(surface.get(), opaque)) {
            J2dRlsTraceLn(J2D_TRACE_WARNING, "DawnSKGraphicsAPI::createSurface: no swap chain for HWND %p", hwnd);
            if (surface->window != nullptr) {
                surface->window.Unconfigure();
            }
            return nullptr;
        }
    }
    if (hwnd != nullptr && gLoseNewDevices.load()) {
        surface->gpu->lost->store(true);   // simulateDeviceLoss(persistent): as a driver that does not come back
    }
    J2dTraceLn(J2D_TRACE_INFO, "DawnSKGraphicsAPI::createSurface: %p %ux%u hwnd=%p client=%ux%u at (%u,%u)", surface.get(),
               width, height, hwnd, surface->clientArea.clientWidth, surface->clientArea.clientHeight,
               surface->clientArea.insetX, surface->clientArea.insetY);
    return surface.release();
}

// ---------------------------------------------------------------------------
// SKPlatform
// ---------------------------------------------------------------------------

std::unique_ptr<Context> DawnSKGraphicsAPI::makeContext(Win32SKConfigInfo *config, const ContextOptions &options) {
    DawnSKConfigInfo *configInfo = dawnConfigOf(config);
    if (isLost(configInfo)) {
        return nullptr;
    }
    DawnBackendContext backendContext;
    backendContext.fInstance = dawnInstance();
    backendContext.fDevice = configInfo->gpu->device;
    backendContext.fQueue = configInfo->gpu->queue;
    // fTick stays DawnNativeProcessEventsFunction: Graphite polls the instance
    // for GPU progress, so SyncToCpu submits and readbacks work.
    return ContextFactory::MakeDawn(backendContext, options);
}

Win32SKConfigInfo *DawnSKGraphicsAPI::configOf(Win32SKSurface *surface) {
    DawnSKSurface *dawnSurface = dawnSurfaceOf(surface);
    return dawnSurface != nullptr ? dawnSurface->config : nullptr;
}

bool DawnSKGraphicsAPI::sameDevice(Win32SKConfigInfo *first, Win32SKConfigInfo *second) {
    DawnSKConfigInfo *firstInfo = dawnConfigOf(first);
    DawnSKConfigInfo *secondInfo = dawnConfigOf(second);
    return firstInfo != nullptr && secondInfo != nullptr && firstInfo->gpu != nullptr && secondInfo->gpu != nullptr
           && firstInfo->gpu->device.Get() == secondInfo->gpu->device.Get();
}

void *DawnSKGraphicsAPI::textureHandle(Win32SKSurface *surface) {
    DawnSKSurface *dawnSurface = dawnSurfaceOf(surface);
    return dawnSurface != nullptr && !dawnSurface->gpu->lost->load() ? dawnSurface->texture.Get() : nullptr;
}

bool DawnSKGraphicsAPI::backendTexture(Win32SKSurface *surface, BackendTexture *texture, SkColorType *colorType) {
    DawnSKSurface *dawnSurface = dawnSurfaceOf(surface);
    if (dawnSurface == nullptr || dawnSurface->texture == nullptr || dawnSurface->gpu->lost->load()) {
        return false;
    }
    *texture = BackendTextures::MakeDawn(dawnSurface->texture.Get());
    *colorType = SURFACE_COLOR_TYPE;
    return texture->isValid();
}

// Copies the surface texture into the swap chain's current image and presents
// it. Runs on the flusher thread after Graphite's submit; the copy goes to the
// same queue, so it is ordered after the rendering without a CPU wait.
bool DawnSKGraphicsAPI::requestRedraw(Win32SKSurface *surface) {
    DawnSKSurface *dawnSurface = dawnSurfaceOf(surface);
    if (dawnSurface == nullptr || dawnSurface->window == nullptr || dawnSurface->gpu->lost->load()) {
        // a replaced window surface (its swap chain moved on), not a window, or a lost device
        J2dTraceLn(J2D_TRACE_VERBOSE, "DawnSKGraphicsAPI::requestRedraw: nothing to present for %p", surface);
        return false;
    }
    wgpu::SurfaceTexture current;
    dawnSurface->window.GetCurrentTexture(&current);
    if (current.status == wgpu::SurfaceGetCurrentTextureStatus::Outdated) {
        // the window changed under the swap chain; AWT replaces the surface on
        // its next bounds event, until then present at the configured size
        dawnSurface->window.Configure(&dawnSurface->windowConfiguration);
        dawnSurface->window.GetCurrentTexture(&current);
    }
    if (current.status != wgpu::SurfaceGetCurrentTextureStatus::SuccessOptimal
            && current.status != wgpu::SurfaceGetCurrentTextureStatus::SuccessSuboptimal) {
        if (!dawnSurface->noImageReported) {
            dawnSurface->noImageReported = true;
            J2dRlsTraceLn(J2D_TRACE_WARNING, "DawnSKGraphicsAPI::requestRedraw: no swap chain image (status %d)",
                          (int) current.status);
        }
        dawnInstance().ProcessEvents();   // deliver the error message behind the status
        return false;
    }
    dawnSurface->noImageReported = false;
    // the client-area part of the texture, as much of it as both sides have
    const Win32SKClientArea &clientArea = dawnSurface->clientArea;
    wgpu::CommandEncoder encoder = dawnSurface->gpu->device.CreateCommandEncoder();
    wgpu::TexelCopyTextureInfo source;
    source.texture = dawnSurface->texture;
    source.origin = { clientArea.insetX, clientArea.insetY, 0 };
    wgpu::TexelCopyTextureInfo destination;
    destination.texture = current.texture;
    wgpu::Extent3D extent = { std::min(dawnSurface->width - clientArea.insetX, clientArea.clientWidth),
                              std::min(dawnSurface->height - clientArea.insetY, clientArea.clientHeight), 1 };
    encoder.CopyTextureToTexture(&source, &destination, &extent);
    wgpu::CommandBuffer commands = encoder.Finish();
    dawnSurface->gpu->queue.Submit(1, &commands);
    dawnSurface->window.Present();
    J2dTraceLn(J2D_TRACE_VERBOSE, "DawnSKGraphicsAPI::requestRedraw: presented %p", surface);
    return true;
}

void DawnSKGraphicsAPI::disposeSurface(Win32SKSurface *surface) {
    DawnSKSurface *dawnSurface = dawnSurfaceOf(surface);
    if (dawnSurface == nullptr) {
        return;
    }
    J2dTraceLn(J2D_TRACE_VERBOSE, "DawnSKGraphicsAPI::disposeSurface: %p", surface);
    if (dawnSurface->window != nullptr) {   // not handed over to a replacing surface
        dawnSurface->window.Unconfigure();
    }
    delete dawnSurface;
}

// ---------------------------------------------------------------------------
// Dawn as a Win32SKGraphicsAPI
// ---------------------------------------------------------------------------

Win32SKGraphicsAPI &DawnSKPlatform::graphicsAPI() {
    static SKNeverDestroyed<DawnSKGraphicsAPI> graphicsAPI;   // never destroyed, as the instance and devices are not
    return *graphicsAPI;
}
