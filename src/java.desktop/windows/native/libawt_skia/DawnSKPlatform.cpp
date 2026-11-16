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
// file creates, presentation by copying the texture into the window's swap chain.

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
    wgpu::Instance instance;   // the instance the adapter and the device were made on (createInstance)
    wgpu::Adapter adapter;
    wgpu::Device device;
    wgpu::Queue queue;
    wgpu::BackendType direct3DVersion = wgpu::BackendType::D3D11;   // the version the device runs on
    uint32_t maxTextureSize = 0;
    std::shared_ptr<std::atomic<bool>> lost = std::make_shared<std::atomic<bool>>(false);
};

// One per Win32SKGraphicsConfig, on the shared device of its Direct3D version.
struct DawnSKConfigInfo final : Win32SKConfigInfo {
    explicit DawnSKConfigInfo(std::shared_ptr<DawnSKDevice> device) : gpu(std::move(device)) {}

    std::unique_ptr<Context> makeContext(const ContextOptions &options) override;
    [[nodiscard]] bool isLost() const override;
    // "<adapter> (D3D11)" or "<adapter> (D3D12)".
    [[nodiscard]] std::string description() const override;
    // Texture, plus the configured swap chain of the window's client area if hwnd
    // is non-null. Null on failure, including a lost device, a texture beyond the
    // adapter's limit or a window Dawn refuses. DXGI allows one flip-model swap
    // chain per HWND and AWT replaces a window's surface on every change of bounds.
    // A window surface takes the swap chain over from `previous`, the surface it
    // replaces (the caller keeps the flusher idle meanwhile), and reconfigures it.
    Win32SKSurface *createSurface(HWND hwnd, uint32_t width, uint32_t height, Win32SKSurface *previous,
                                  bool opaque) override;

    const std::shared_ptr<DawnSKDevice> gpu;

protected:
    [[nodiscard]] const void *device() const override { return gpu != nullptr ? gpu->device.Get() : nullptr; }
};

// One per Win32SKSurfaceData: the texture Skia renders into and, for window
// surfaces, the swap chain of the window's client area that the texture is
// copied to on present. The texture is persistent (Java2D repaints only dirty
// regions), so it is never a swap chain image itself. Like every AWT window
// surface it spans the whole window; the client area starts at the insets
// (Win32SKPlatform::clientArea).
struct DawnSKSurface final : Win32SKSurface {
    DawnSKSurface() = default;
    // The swap chain goes unless a replacing surface took it over (DISPOSE_SURFACE, or a creation that failed).
    ~DawnSKSurface() override;

    [[nodiscard]] Win32SKAPI api() const override { return Win32SKAPI::Dawn; }
    [[nodiscard]] SKPlatformConfig *config() const override { return platformConfig; }
    [[nodiscard]] void *textureHandle() const override;
    bool backendTexture(BackendTexture *texture, SkColorType *colorType) const override;
    // Copies the surface texture into the swap chain's current image and presents
    // it. Runs on the flusher thread after Graphite's submit; the copy goes to the
    // same queue, so it is ordered after the rendering without a CPU wait.
    bool requestRedraw() override;
    // Dawn presents itself (requestRedraw).
    bool acquirePresentTarget(SKPlatformPresentTarget *) override { return false; }
    bool presentTarget() override { return false; }

    SKPlatformConfig *platformConfig = nullptr;   // identity for the shared backend's context lookup; not dereferenced
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

// Dawn as a Win32SKGraphicsAPI: the device all its configurations share.
class DawnSKGraphicsAPI final : public Win32SKGraphicsAPI {
public:
    [[nodiscard]] Win32SKAPI api() const override { return Win32SKAPI::Dawn; }
    std::optional<Win32SKAdapterIDs> adapterIDs() override;
    // The preferred device features (PREFERRED_FEATURES) of the last device created, as "name=o/g ..." with
    // o whether the adapter offers it and g whether the device has it; empty before the first device.
    std::string deviceFeatures() override;

    // A configuration on the shared device of the first Direct3D version that yields
    // one (11 first, 12 as fallback, unless DawnSKPlatform::preferD3D12); a lost device
    // is replaced. Null if none.
    Win32SKConfigInfo *createConfig() override;
    void simulateDeviceLoss(bool persistent) override;
};

std::string toString(const wgpu::StringView view) {
    return std::string(std::string_view(view));
}

// Dawn calls the Direct3D version it runs on its backend (wgpu::BackendType: D3D11, D3D12);
// in this code base "backend" is the Skia backend as a whole, so here it is the Direct3D
// version. Its name in the adapter descriptions.
const char *direct3DVersionName(const wgpu::BackendType direct3DVersion) {
    switch (direct3DVersion) {
        case wgpu::BackendType::D3D11: return "D3D11";
        case wgpu::BackendType::D3D12: return "D3D12";
        default: return "unknown Direct3D version";
    }
}

// The surfaces are BGRA8 like the Direct3D swap chains they are copied to.
constexpr auto SURFACE_FORMAT = wgpu::TextureFormat::BGRA8Unorm;
constexpr SkColorType SURFACE_COLOR_TYPE = kBGRA_8888_SkColorType;

// The Direct3D versions Dawn can run on here, Direct3D 11 first: the lowest common
// denominator of Windows GPUs and VMs. The index into this array is also the device slot.
constexpr std::array DIRECT3D_VERSIONS = { wgpu::BackendType::D3D11, wgpu::BackendType::D3D12 };
// The order the versions are tried in, as indices into DIRECT3D_VERSIONS.
// sun.java2d.skia.dawn.api=d3d12 tries Direct3D 12 first (for triage: the version Chrome ships
// WebGPU on; Graphite is tested on Direct3D 11 first). Set once, before the first adapter request.
std::array<size_t, DIRECT3D_VERSIONS.size()> gDirect3DOrder = { 0, 1 };

} // namespace

void DawnSKPlatform::preferD3D12(const bool preferD3D12) {
    gDirect3DOrder = preferD3D12 ? std::array<size_t, 2>{ 1, 0 } : std::array<size_t, 2>{ 0, 1 };
}

namespace {

// WebGPU's default maxTextureDimension2D, the limit a device gets when the adapter's limits
// cannot be read (they are required at creation, so this is only a fallback).
constexpr uint32_t WEBGPU_DEFAULT_MAX_TEXTURE_SIZE = 8192;

// wgpu::Instance::WaitAny's timeout for the requests this file makes synchronously: none.
constexpr uint64_t WAIT_WITHOUT_TIMEOUT = UINT64_MAX;

// The Microsoft Basic Render Driver (WARP) by its PCI ids, the way Microsoft documents to recognize it
// ("Considerations for the Microsoft Basic Render Driver"): Dawn did not report it as AdapterType::CPU
// when it stood in for a disabled display adapter.
constexpr uint32_t BASIC_RENDER_DRIVER_VENDOR_ID = 0x1414;
constexpr uint32_t BASIC_RENDER_DRIVER_DEVICE_ID = 0x8c;

// One device per Direct3D version, shared by the configurations (as the D3D pipeline
// keeps its device across configurations): a display change (resolution, DPI,
// a monitor plugged) recreates the configurations, and a device per
// configuration left every texture of the old one unusable on the new
// (SKPlatformConfig::sameDevice) and cost a device each time. A lost device is
// dropped, so recovery gets a new one.
//
// The devices and their instances are never destroyed (SKNeverDestroyed.h): at process exit their
// destructors would run during DLL detach, under the loader lock, and Dawn's
// D3D12 backend fails fast releasing its device there (SKDawnBackendTest,
// dawn.api=d3d12: exit code 0xC0000409 after the test passed). Each device has its own
// instance (createInstance), which lives as long as the device.
std::mutex gDevicesLock;
using DawnSKDeviceSlots = std::array<std::shared_ptr<DawnSKDevice>, DIRECT3D_VERSIONS.size()>;
SKNeverDestroyed<DawnSKDeviceSlots> gDevices;   // indexed like DIRECT3D_VERSIONS

// ---------------------------------------------------------------------------
// Instance, adapter, device
// ---------------------------------------------------------------------------

// Waits for a request this file makes synchronously (`what` names it). Dawn runs the request's
// callback during the wait, and the callbacks write to the caller's locals, so the wait must not
// end before it.
void waitFor(const wgpu::Instance &instance, const wgpu::Future future, const char *what) {
    if (const wgpu::WaitStatus status = instance.WaitAny(future, WAIT_WITHOUT_TIMEOUT);
            status != wgpu::WaitStatus::Success) {
        // never: the instance has TimedWaitAny and the wait no timeout
        J2dRlsTraceLn(J2D_TRACE_ERROR, "DawnSK: never: waiting for the %s ended with %d", what,
                      static_cast<int>(status));
    }
}

// A Dawn instance for a new device; null if Dawn cannot start. A new one per device: an instance
// keeps the adapters it enumerated when it was created, and after an adapter's removal and return
// (a driver update) that adapter yields DXGI_ERROR_DEVICE_REMOVED from every D3D11CreateDevice, so
// a device that replaces a lost one has to come from a new enumeration. Under gDevicesLock.
wgpu::Instance createInstance() {
    static bool procsSet = false;
    if (!procsSet) {
        // Dawn's C entry points dispatch through a process-wide proc table that
        // the native implementation has to be installed in first.
        dawnProcSetProcs(&dawn::native::GetProcs());
        procsSet = true;
    }
    constexpr auto timedWait = wgpu::InstanceFeatureName::TimedWaitAny;
    wgpu::InstanceDescriptor descriptor;
    descriptor.requiredFeatureCount = 1;
    descriptor.requiredFeatures = &timedWait;   // lets us wait for adapter/device requests synchronously
    wgpu::Instance instance = wgpu::CreateInstance(&descriptor);
    if (instance == nullptr) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "DawnSK: cannot create the Dawn instance");
    }
    return instance;
}

// The adapter of the Direct3D version, a hardware one; null if there is none. Dawn offers the
// software adapter (WARP, the Microsoft Basic Render Driver) when no hardware adapter is present, at
// startup on a machine without a GPU and while the only display adapter is disabled (a driver
// update); it is not used: like the Direct3D pipeline, which asks for a hardware device
// (D3DPipelineManager), the screens then stay with GDI, and the next configuration made after a
// display change looks for a hardware adapter again. Whether WARP would beat Java2D's software loops
// is unmeasured.
wgpu::Adapter requestAdapter(const wgpu::Instance &instance, wgpu::BackendType direct3DVersion) {
    wgpu::RequestAdapterOptions options;
    options.backendType = direct3DVersion;
    options.powerPreference = wgpu::PowerPreference::HighPerformance;
    wgpu::Adapter adapter;
    const wgpu::Future request = instance.RequestAdapter(&options, wgpu::CallbackMode::WaitAnyOnly,
            [&adapter, direct3DVersion](const wgpu::RequestAdapterStatus status, wgpu::Adapter result,
                                        const wgpu::StringView message) {
                if (status == wgpu::RequestAdapterStatus::Success) {
                    adapter = std::move(result);
                } else {
                    J2dRlsTraceLn(J2D_TRACE_INFO, "DawnSK: no %s adapter: %s", direct3DVersionName(direct3DVersion),
                                  toString(message).c_str());
                }
            });
    waitFor(instance, request, "adapter request");
    if (wgpu::AdapterInfo info; adapter != nullptr && adapter.GetInfo(&info) == wgpu::Status::Success
                                && (info.adapterType == wgpu::AdapterType::CPU
                                    || (info.vendorID == BASIC_RENDER_DRIVER_VENDOR_ID
                                        && info.deviceID == BASIC_RENDER_DRIVER_DEVICE_ID))) {
        J2dRlsTraceLn(J2D_TRACE_INFO, "DawnSK: the only %s adapter is the software one (%s), not used",
                      direct3DVersionName(direct3DVersion), toString(info.device).c_str());
        return nullptr;
    }
    return adapter;
}

// "<adapter> (D3D11)": the adapter's name and the Direct3D version.
std::string adapterDescription(const wgpu::Adapter &adapter, const wgpu::BackendType direct3DVersion) {
    wgpu::AdapterInfo info;
    adapter.GetInfo(&info);
    return toString(info.device) + " (" + direct3DVersionName(direct3DVersion) + ")";
}

// The adapter of the first Direct3D version (in gDirect3DOrder) that has one, with that version;
// null and Undefined if none has.
wgpu::Adapter firstAdapter(const wgpu::Instance &instance, wgpu::BackendType *direct3DVersion) {
    for (const size_t slot : gDirect3DOrder) {
        const wgpu::BackendType candidate = DIRECT3D_VERSIONS[slot];
        if (wgpu::Adapter adapter = requestAdapter(instance, candidate); adapter != nullptr) {
            *direct3DVersion = candidate;
            return adapter;
        }
    }
    *direct3DVersion = wgpu::BackendType::Undefined;
    return nullptr;
}

} // namespace

// On an instance of its own, released with the adapter: a device made later enumerates the adapters anew
// on its instance (createInstance).
std::optional<Win32SKAdapterIDs> DawnSKGraphicsAPI::adapterIDs() {
    std::lock_guard lock(gDevicesLock);
    const wgpu::Instance instance = createInstance();
    wgpu::BackendType direct3DVersion;
    const wgpu::Adapter adapter = instance != nullptr ? firstAdapter(instance, &direct3DVersion) : nullptr;
    if (adapter == nullptr) {
        return std::nullopt;
    }
    wgpu::AdapterInfo info;
    adapter.GetInfo(&info);
    Win32SKAdapterIDs ids;
    ids.vendorID = info.vendorID;
    ids.deviceID = info.deviceID;
    ids.name = toString(info.device);
    return ids;
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
    {.feature = wgpu::FeatureName::ImplicitDeviceSynchronization, .name = "ImplicitDeviceSynchronization"},
    {.feature = wgpu::FeatureName::DualSourceBlending, .name = "DualSourceBlending"},
    {.feature = wgpu::FeatureName::FramebufferFetch, .name = "FramebufferFetch"},
    {.feature = wgpu::FeatureName::BufferMapExtendedUsages, .name = "BufferMapExtendedUsages"},
    {.feature = wgpu::FeatureName::DawnAllowUndefinedLoadStoreOp, .name = "DawnAllowUndefinedLoadStoreOp"},
    {.feature = wgpu::FeatureName::DawnLoadResolveTexture, .name = "DawnLoadResolveTexture"},
    {.feature = wgpu::FeatureName::DawnPartialLoadResolveTexture, .name = "DawnPartialLoadResolveTexture"},
    {.feature = wgpu::FeatureName::DawnTexelCopyBufferRowAlignment, .name = "DawnTexelCopyBufferRowAlignment"},
    {.feature = wgpu::FeatureName::MSAARenderToSingleSampled, .name = "MSAARenderToSingleSampled"},
    {.feature = wgpu::FeatureName::ShaderF16, .name = "ShaderF16"},
    {.feature = wgpu::FeatureName::TextureCompressionBC, .name = "TextureCompressionBC"},
    {.feature = wgpu::FeatureName::TextureCompressionETC2, .name = "TextureCompressionETC2"},
    {.feature = wgpu::FeatureName::TextureFormatsTier1, .name = "TextureFormatsTier1"},
    {.feature = wgpu::FeatureName::TransientAttachments, .name = "TransientAttachments"},
    {.feature = wgpu::FeatureName::Unorm16TextureFormats, .name = "Unorm16TextureFormats"},
    {.feature = wgpu::FeatureName::RenderPassRenderArea, .name = "RenderPassRenderArea"},
}};

std::mutex gFeatureReportLock;
std::string gFeatureReport;   // of the last device created (DawnSKGraphicsAPI::deviceFeatures)

} // namespace

std::string DawnSKGraphicsAPI::deviceFeatures() {
    std::lock_guard lock(gFeatureReportLock);
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
    for (const auto &[feature, name] : PREFERRED_FEATURES) {
        if (adapter.HasFeature(feature)) {
            features.push_back(feature);
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
                [](const std::span<const std::byte> key, std::span<std::byte> value, SKBlobStore *blobStore) {
                    return blobStore->load(key.data(), key.size(), value.data(), value.size());
                }, store);
        cache.SetDawnStoreCacheDataCallback(
                [](const std::span<const std::byte> key, const std::span<const std::byte> value,
                   SKBlobStore *blobStore) {
                    blobStore->store(key.data(), key.size(), value.data(), value.size());
                }, store);
        descriptor.nextInChain = &cache;
    }
    descriptor.SetUncapturedErrorCallback(
            [](const wgpu::Device &, const wgpu::ErrorType type, const wgpu::StringView message) {
                J2dRlsTraceLn(J2D_TRACE_ERROR, "DawnSK: device error %d: %s", static_cast<int>(type),
                              toString(message).c_str());
            });
    // The flag outlives device and configuration (shared with every surface),
    // so a callback after their disposal is harmless.
    // A creation that failed is reported by the request's callback below, not as a loss.
    descriptor.SetDeviceLostCallback(wgpu::CallbackMode::AllowSpontaneous,
            [lost](const wgpu::Device &, const wgpu::DeviceLostReason reason, const wgpu::StringView message) {
                if (reason != wgpu::DeviceLostReason::Destroyed && reason != wgpu::DeviceLostReason::FailedCreation) {
                    J2dRlsTraceLn(J2D_TRACE_ERROR, "DawnSK: device lost (%d): %s", static_cast<int>(reason),
                                  toString(message).c_str());
                    lost->store(true);
                }
            });
    wgpu::Device device;
    const wgpu::Future request = adapter.RequestDevice(&descriptor, wgpu::CallbackMode::WaitAnyOnly,
            [&device](const wgpu::RequestDeviceStatus status, wgpu::Device result, const wgpu::StringView message) {
                if (status == wgpu::RequestDeviceStatus::Success) {
                    device = std::move(result);
                } else {
                    J2dRlsTraceLn(J2D_TRACE_ERROR, "DawnSK: cannot create a device: %s", toString(message).c_str());
                }
            });
    waitFor(instance, request, "device request");
    if (device != nullptr) {
        std::string report;
        for (const auto &[feature, name] : PREFERRED_FEATURES) {
            report += std::string(report.empty() ? "" : " ") + name + "="
                      + (adapter.HasFeature(feature) ? "1" : "0") + "/"
                      + (device.HasFeature(feature) ? "1" : "0");
        }
        J2dRlsTraceLn(J2D_TRACE_INFO, "DawnSK: device features (offered/granted) %s", report.c_str());
        std::lock_guard lock(gFeatureReportLock);
        gFeatureReport = report;
    }
    return device;
}

// Tests (Win32SKGraphicsConfig.simulateDeviceLoss): every device counts as lost, as after a
// driver reset; with persistent also every device created afterward once its first window
// surface exists, as on a driver that keeps failing.
std::atomic gLoseNewDevices{false};

} // namespace

void DawnSKGraphicsAPI::simulateDeviceLoss(const bool persistent) {
    gLoseNewDevices.store(persistent);
    std::lock_guard lock(gDevicesLock);
    for (const std::shared_ptr<DawnSKDevice> &gpu : *gDevices) {
        if (gpu != nullptr) {
            gpu->lost->store(true);
        }
    }
}

Win32SKConfigInfo *DawnSKGraphicsAPI::createConfig() {
    std::lock_guard lock(gDevicesLock);
    for (const size_t slot : gDirect3DOrder) {
        const wgpu::BackendType direct3DVersion = DIRECT3D_VERSIONS[slot];
        std::shared_ptr<DawnSKDevice> gpu = (*gDevices)[slot];
        if (gpu != nullptr && gpu->lost->load()) {
            (*gDevices)[slot].reset();
            gpu.reset();
        }
        const bool shared = gpu != nullptr;
        if (!shared) {
            const wgpu::Instance instance = createInstance();
            if (instance == nullptr) {
                return nullptr;
            }
            wgpu::Adapter adapter = requestAdapter(instance, direct3DVersion);
            if (adapter == nullptr) {
                continue;
            }
            gpu = std::make_shared<DawnSKDevice>();
            gpu->instance = instance;
            gpu->device = requestDevice(instance, adapter, gpu->lost, adapterDescription(adapter, direct3DVersion));
            if (gpu->device == nullptr) {
                continue;
            }
            gpu->adapter = std::move(adapter);
            gpu->direct3DVersion = direct3DVersion;
            gpu->queue = gpu->device.GetQueue();
            wgpu::Limits limits;   // the device's limits (the adapter's limits were required at creation)
            gpu->maxTextureSize = gpu->device.GetLimits(&limits) == wgpu::Status::Success
                                  ? limits.maxTextureDimension2D : WEBGPU_DEFAULT_MAX_TEXTURE_SIZE;
            (*gDevices)[slot] = gpu;
        }
        auto *config = new DawnSKConfigInfo(std::move(gpu));
        J2dRlsTraceLn(J2D_TRACE_INFO, "DawnSKGraphicsAPI::createConfig: %s, max texture %u, %s device",
                      config->description().c_str(), config->gpu->maxTextureSize, shared ? "shared" : "new");
        return config;
    }
    J2dRlsTraceLn(J2D_TRACE_ERROR, "DawnSKGraphicsAPI::createConfig: no Direct3D adapter yields a Dawn device");
    return nullptr;
}

// ---------------------------------------------------------------------------
// Configurations
// ---------------------------------------------------------------------------

bool DawnSKConfigInfo::isLost() const {
    if (gpu == nullptr) {
        // never: createConfig makes a configuration only with its device
        J2dRlsTraceLn(J2D_TRACE_ERROR, "DawnSKConfigInfo::isLost: a configuration without a device");
        return true;
    }
    return gpu->lost->load();
}

std::string DawnSKConfigInfo::description() const {
    return adapterDescription(gpu->adapter, gpu->direct3DVersion);
}

std::unique_ptr<Context> DawnSKConfigInfo::makeContext(const ContextOptions &options) {
    if (isLost()) {
        return nullptr;
    }
    DawnBackendContext backendContext;
    backendContext.fInstance = gpu->instance;
    backendContext.fDevice = gpu->device;
    backendContext.fQueue = gpu->queue;
    // fTick stays DawnNativeProcessEventsFunction: Graphite polls the instance
    // for GPU progress, so SyncToCpu submits and readbacks work.
    return ContextFactory::MakeDawn(backendContext, options);
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

bool popErrorScopes(const DawnSKDevice &gpu, const char *what) {
    bool succeeded = true;
    for (int scope = 0; scope < ERROR_SCOPE_COUNT; scope++) {
        const wgpu::Future pop = gpu.device.PopErrorScope(wgpu::CallbackMode::WaitAnyOnly,
                [&succeeded, what](const wgpu::PopErrorScopeStatus status, const wgpu::ErrorType type,
                                   const wgpu::StringView message) {
                    if (status == wgpu::PopErrorScopeStatus::Success && type != wgpu::ErrorType::NoError) {
                        J2dRlsTraceLn(J2D_TRACE_WARNING, "DawnSK: %s failed (%d): %s", what, static_cast<int>(type),
                                      toString(message).c_str());
                        succeeded = false;
                    }
                });
        waitFor(gpu.instance, pop, "error scope");
    }
    return succeeded;
}

// Swap chain configuration for the surface's window from what the swap chain
// supports; false if it cannot take our BGRA8 copy.
bool configureWindow(DawnSKSurface *surface, const bool opaque) {
    wgpu::SurfaceCapabilities capabilities;
    if (surface->window.GetCapabilities(surface->gpu->adapter, &capabilities) != wgpu::Status::Success) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "DawnSK: no swap chain capabilities for the window");
        return false;
    }
    const std::span formats(capabilities.formats, capabilities.formatCount);
    const bool formatSupported = std::ranges::find(formats, SURFACE_FORMAT) != formats.end();
    // Presents happen per queue flush, so they must not wait for a vblank:
    // Mailbox, else Immediate, else Fifo (the only mode always offered).
    auto presentMode = wgpu::PresentMode::Fifo;
    for (const wgpu::PresentMode offered : std::span(capabilities.presentModes, capabilities.presentModeCount)) {
        if (offered == wgpu::PresentMode::Mailbox
                || (offered == wgpu::PresentMode::Immediate && presentMode != wgpu::PresentMode::Mailbox)) {
            presentMode = offered;
        }
    }
    const std::span alphaModes(capabilities.alphaModes, capabilities.alphaModeCount);
    const bool opaqueSupported = std::ranges::find(alphaModes, wgpu::CompositeAlphaMode::Opaque) != alphaModes.end();
    if (const bool copyDestination = (capabilities.usages & wgpu::TextureUsage::CopyDst) == wgpu::TextureUsage::CopyDst;
            !formatSupported || !copyDestination) {
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
               configuration.height, static_cast<int>(presentMode), static_cast<int>(configuration.alphaMode));
    return popErrorScopes(*surface->gpu, "swap chain configuration");
}

} // namespace

// NOLINTNEXTLINE(misc-misplaced-const): the HWND passed by value is const as every by-value parameter (the window it names is not)
Win32SKSurface *DawnSKConfigInfo::createSurface(const HWND hwnd, const uint32_t width, const uint32_t height,
                                                Win32SKSurface *win32Previous, const bool opaque) {
    // the surface being replaced is Dawn's: one API renders per process
    DawnSKSurface *previous = nullptr;
    if (win32Previous != nullptr && win32Previous->api() == Win32SKAPI::Dawn) {
        // NOLINTNEXTLINE(cppcoreguidelines-pro-type-static-cast-downcast): checked by api()
        previous = static_cast<DawnSKSurface *>(win32Previous);
    }
    if (isLost() || width == 0 || height == 0) {
        return nullptr;
    }
    if (width > gpu->maxTextureSize || height > gpu->maxTextureSize) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "DawnSKConfigInfo::createSurface: %ux%u exceeds the adapter's %u", width, height,
                      gpu->maxTextureSize);
        return nullptr;
    }
    auto surface = std::make_unique<DawnSKSurface>();   // deleted on every failure path below
    surface->platformConfig = this;
    surface->gpu = gpu;
    surface->hwnd = hwnd;
    surface->width = width;
    surface->height = height;
    if (hwnd != nullptr) {
        const std::optional<Win32SKClientArea> clientArea = Win32SKPlatform::clientArea(hwnd, width, height);
        if (!clientArea) {
            J2dRlsTraceLn(J2D_TRACE_WARNING, "DawnSKConfigInfo::createSurface: HWND %p is gone", hwnd);
            return nullptr;
        }
        surface->clientArea = *clientArea;
    }

    wgpu::TextureDescriptor textureDescriptor;
    textureDescriptor.label = "Java2D Skia surface";
    textureDescriptor.dimension = wgpu::TextureDimension::e2D;
    textureDescriptor.size = {.width = width, .height = height, .depthOrArrayLayers = 1};
    textureDescriptor.format = SURFACE_FORMAT;
    // render target for Graphite, sampled when drawn into another surface,
    // copy source for presentation and readback, copy destination for uploads
    textureDescriptor.usage = wgpu::TextureUsage::RenderAttachment | wgpu::TextureUsage::TextureBinding
                            | wgpu::TextureUsage::CopySrc | wgpu::TextureUsage::CopyDst;
    pushErrorScopes(gpu->device);
    surface->texture = gpu->device.CreateTexture(&textureDescriptor);
    if (!popErrorScopes(*gpu, "texture creation") || surface->texture == nullptr) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "DawnSKConfigInfo::createSurface: cannot create a %ux%u texture", width, height);
        return nullptr;
    }

    if (hwnd != nullptr) {
        if (previous != nullptr && previous->hwnd == hwnd && previous->window != nullptr
                && previous->gpu->device.Get() != gpu->device.Get()) {
            // A swap chain of another device (the one lost before this configuration's):
            // DXGI swap chains cannot move between Direct3D devices ("D3D SwapChain cannot
            // switch between D3D Devices"), so it is released and the window gets a new one.
            J2dTraceLn(J2D_TRACE_VERBOSE, "DawnSKConfigInfo::createSurface: releasing the swap chain of %p (another device)",
                       previous);
            previous->window.Unconfigure();
            previous->window = nullptr;
        }
        if (previous != nullptr && previous->hwnd == hwnd && previous->window != nullptr) {
            // the surface being replaced can no longer present; its texture stays
            // valid until it is disposed
            J2dTraceLn(J2D_TRACE_VERBOSE, "DawnSKConfigInfo::createSurface: taking over the swap chain of %p", previous);
            surface->window = std::move(previous->window);
            previous->window = nullptr;
        } else {
            wgpu::SurfaceSourceWindowsHWND source;
            source.hinstance = GetModuleHandleW(nullptr);
            source.hwnd = hwnd;
            wgpu::SurfaceDescriptor descriptor;
            descriptor.nextInChain = &source;
            surface->window = gpu->instance.CreateSurface(&descriptor);
        }
        if (surface->window == nullptr || !configureWindow(surface.get(), opaque)) {
            J2dRlsTraceLn(J2D_TRACE_WARNING, "DawnSKConfigInfo::createSurface: no swap chain for HWND %p", hwnd);
            return nullptr;   // the surface's destructor releases the swap chain
        }
    }
    if (hwnd != nullptr && gLoseNewDevices.load()) {
        surface->gpu->lost->store(true);   // simulateDeviceLoss(persistent): as a driver that does not come back
    }
    J2dTraceLn(J2D_TRACE_INFO, "DawnSKConfigInfo::createSurface: %p %ux%u hwnd=%p client=%ux%u at (%u,%u)", surface.get(),
               width, height, hwnd, surface->clientArea.clientWidth, surface->clientArea.clientHeight,
               surface->clientArea.insetX, surface->clientArea.insetY);
    return surface.release();
}

// ---------------------------------------------------------------------------
// The surfaces as SKPlatformSurfaces
// ---------------------------------------------------------------------------

void *DawnSKSurface::textureHandle() const {
    return !gpu->lost->load() ? texture.Get() : nullptr;
}

bool DawnSKSurface::backendTexture(BackendTexture *backendTexture, SkColorType *colorType) const {
    if (texture == nullptr || gpu->lost->load()) {
        return false;
    }
    *backendTexture = BackendTextures::MakeDawn(texture.Get());
    *colorType = SURFACE_COLOR_TYPE;
    return backendTexture->isValid();
}

bool DawnSKSurface::requestRedraw() {
    if (window == nullptr || gpu->lost->load()) {
        // a replaced window surface (its swap chain moved on), not a window, or a lost device
        J2dTraceLn(J2D_TRACE_VERBOSE, "DawnSKSurface::requestRedraw: nothing to present for %p", this);
        return false;
    }
    wgpu::SurfaceTexture current;
    window.GetCurrentTexture(&current);
    if (current.status == wgpu::SurfaceGetCurrentTextureStatus::Outdated) {
        // the window changed under the swap chain; AWT replaces the surface on
        // its next bounds event, until then present at the configured size
        window.Configure(&windowConfiguration);
        window.GetCurrentTexture(&current);
    }
    if (current.status != wgpu::SurfaceGetCurrentTextureStatus::SuccessOptimal
            && current.status != wgpu::SurfaceGetCurrentTextureStatus::SuccessSuboptimal) {
        if (!noImageReported) {
            noImageReported = true;
            J2dRlsTraceLn(J2D_TRACE_WARNING, "DawnSKSurface::requestRedraw: no swap chain image (status %d)",
                          static_cast<int>(current.status));
        }
        gpu->instance.ProcessEvents();   // deliver the error message behind the status
        return false;
    }
    noImageReported = false;
    // the client-area part of the texture, as much of it as both sides have
    const wgpu::CommandEncoder encoder = gpu->device.CreateCommandEncoder();
    wgpu::TexelCopyTextureInfo source;
    source.texture = texture;
    source.origin = {.x = clientArea.insetX, .y = clientArea.insetY, .z = 0};
    wgpu::TexelCopyTextureInfo destination;
    destination.texture = current.texture;
    const wgpu::Extent3D extent = {.width = std::min(width - clientArea.insetX, clientArea.clientWidth),
                                   .height = std::min(height - clientArea.insetY, clientArea.clientHeight),
                                   .depthOrArrayLayers = 1};
    encoder.CopyTextureToTexture(&source, &destination, &extent);
    const wgpu::CommandBuffer commands = encoder.Finish();
    gpu->queue.Submit(1, &commands);
    // Dawn reports a failed present through its error callbacks; the next present tries again.
    (void) window.Present();
    J2dTraceLn(J2D_TRACE_VERBOSE, "DawnSKSurface::requestRedraw: presented %p", this);
    return true;
}

DawnSKSurface::~DawnSKSurface() {
    J2dTraceLn(J2D_TRACE_VERBOSE, "DawnSKSurface::~DawnSKSurface: %p", this);
    if (window != nullptr) {   // not handed over to a replacing surface
        window.Unconfigure();
    }
}

// ---------------------------------------------------------------------------
// Dawn as a Win32SKGraphicsAPI
// ---------------------------------------------------------------------------

Win32SKGraphicsAPI &DawnSKPlatform::graphicsAPI() {
    static SKNeverDestroyed<DawnSKGraphicsAPI> graphicsAPI;   // never destroyed, as the instance and devices are not
    return *graphicsAPI;
}
