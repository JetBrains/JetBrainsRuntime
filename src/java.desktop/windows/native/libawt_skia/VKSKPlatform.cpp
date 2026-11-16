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

#include "VKSKPlatform.h"
#include "SKNeverDestroyed.h"

#include "Trace.h"
#include "include/gpu/MutableTextureState.h"
#include "include/gpu/graphite/BackendSemaphore.h"
#include "include/gpu/graphite/Context.h"
#include "include/gpu/graphite/vk/VulkanGraphiteContext.h"
#include "include/gpu/graphite/vk/VulkanGraphiteTypes.h"
#include "include/gpu/vk/VulkanBackendContext.h"
#include "include/gpu/vk/VulkanExtensions.h"
#include "include/gpu/vk/VulkanMemoryAllocator.h"
#include "include/gpu/vk/VulkanMutableTextureState.h"
#include "include/gpu/vk/VulkanPreferredFeatures.h"
#include "include/third_party/vulkan/vulkan/vulkan_core.h"
#include "include/third_party/vulkan/vulkan/vulkan_win32.h"

#include <algorithm>
#include <array>
#include <atomic>
// ReSharper disable once CppUnusedIncludeDirective - strcmp is used here
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

using namespace skgpu::graphite;

// Skia's VMA-based allocator, in the release's skia.lib without a public header
// (src/gpu/vk/vulkanmemoryallocator/VulkanMemoryAllocatorPriv.h declares it so).
namespace skgpu {
enum class ThreadSafe : bool;
namespace VulkanMemoryAllocators {
sk_sp<VulkanMemoryAllocator> Make(const VulkanBackendContext &, ThreadSafe);
}
}

namespace {

constexpr VkFormat SURFACE_FORMAT = VK_FORMAT_B8G8R8A8_UNORM;
constexpr SkColorType SURFACE_COLOR_TYPE = kBGRA_8888_SkColorType;

// The lowest Vulkan the loader and an adapter must offer (Graphite needs 1.1).
constexpr uint32_t LOWEST_API_VERSION = VK_API_VERSION_1_1;
// The highest Vulkan asked for: the instance is created with min(loader's, this), the
// device limited to min(instance's, adapter's) (VulkanBackendContext.fMaxAPIVersion). Not 1.4:
// the one 1.4 feature Graphite m156 reads (hostImageCopy, GRAPHITE_FEATURES) is enabled from
// VK_EXT_host_image_copy on 1.3 as well, so 1.4 would bring newer driver paths and nothing for Graphite.
constexpr uint32_t HIGHEST_API_VERSION = VK_API_VERSION_1_3;
// The timeout of vkWaitForFences where the caller waits for the work to complete: none.
constexpr uint64_t WAIT_WITHOUT_TIMEOUT = UINT64_MAX;
// The one queue's priority (VkDeviceQueueCreateInfo.pQueuePriorities, 0.0 to 1.0).
constexpr float QUEUE_PRIORITY = 1.0f;
// Swap chain images: three with Mailbox, so that one renders while one is on screen and
// one waits to replace it; two (double buffering) with the modes that present in order.
constexpr uint32_t MAILBOX_IMAGE_COUNT = 3;
constexpr uint32_t DOUBLE_BUFFER_IMAGE_COUNT = 2;

// ---------------------------------------------------------------------------
// Loader, instance, device
// ---------------------------------------------------------------------------

// The Vulkan entry points used here, from the driver's loader (vulkan-1.dll); the
// device-level ones through vkGetDeviceProcAddr of the one device.
struct VKSKFunctions {
    PFN_vkGetInstanceProcAddr getInstanceProcAddr = nullptr;
    PFN_vkCreateInstance createInstance = nullptr;
    PFN_vkEnumerateInstanceVersion enumerateInstanceVersion = nullptr;
    PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices = nullptr;
    PFN_vkGetPhysicalDeviceProperties getPhysicalDeviceProperties = nullptr;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties getPhysicalDeviceQueueFamilyProperties = nullptr;
    PFN_vkGetPhysicalDeviceFeatures2 getPhysicalDeviceFeatures2 = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties getPhysicalDeviceMemoryProperties = nullptr;
    PFN_vkEnumerateDeviceExtensionProperties enumerateDeviceExtensionProperties = nullptr;
    PFN_vkCreateDevice createDevice = nullptr;
    PFN_vkGetDeviceProcAddr getDeviceProcAddr = nullptr;
    PFN_vkGetPhysicalDeviceWin32PresentationSupportKHR getPhysicalDeviceWin32PresentationSupportKHR = nullptr;
    PFN_vkCreateWin32SurfaceKHR createWin32SurfaceKHR = nullptr;
    PFN_vkDestroySurfaceKHR destroySurfaceKHR = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR getPhysicalDeviceSurfaceCapabilitiesKHR = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR getPhysicalDeviceSurfaceFormatsKHR = nullptr;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR getPhysicalDeviceSurfacePresentModesKHR = nullptr;
};

// The GPU side shared by all configurations and their surfaces (reference counted).
struct VKSKDevice {
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    uint32_t maxImageSize = 0;
    std::string name;
    VkPhysicalDeviceMemoryProperties memory = {};
    // The device's features, as created; Graphite reads the chain whenever it makes a context
    // (backendContext.fDeviceFeatures2), so the structs live as long as the device: the fault
    // struct, and the ones Skia chains (preferredFeatures holds them, createDevice).
    VkPhysicalDeviceFeatures2 features = {};
    VkPhysicalDeviceFaultFeaturesEXT fault = {};
    skgpu::VulkanPreferredFeatures preferredFeatures;
    skgpu::VulkanExtensions extensions;
    skgpu::VulkanBackendContext backendContext;   // Graphite's view of the device
    std::shared_ptr<std::atomic<bool>> lost = std::make_shared<std::atomic<bool>>(false);

    PFN_vkGetDeviceQueue getDeviceQueue = nullptr;
    PFN_vkCreateImage createImage = nullptr;
    PFN_vkDestroyImage destroyImage = nullptr;
    PFN_vkGetImageMemoryRequirements getImageMemoryRequirements = nullptr;
    PFN_vkAllocateMemory allocateMemory = nullptr;
    PFN_vkFreeMemory freeMemory = nullptr;
    PFN_vkBindImageMemory bindImageMemory = nullptr;
    PFN_vkCreateSwapchainKHR createSwapchainKHR = nullptr;
    PFN_vkDestroySwapchainKHR destroySwapchainKHR = nullptr;
    PFN_vkGetSwapchainImagesKHR getSwapchainImagesKHR = nullptr;
    PFN_vkAcquireNextImageKHR acquireNextImageKHR = nullptr;
    PFN_vkQueuePresentKHR queuePresentKHR = nullptr;
    PFN_vkCreateSemaphore createSemaphore = nullptr;
    PFN_vkDestroySemaphore destroySemaphore = nullptr;
    PFN_vkQueueWaitIdle queueWaitIdle = nullptr;
    PFN_vkQueueSubmit queueSubmit = nullptr;
    PFN_vkCreateFence createFence = nullptr;
    PFN_vkDestroyFence destroyFence = nullptr;
    PFN_vkGetFenceStatus getFenceStatus = nullptr;
    PFN_vkWaitForFences waitForFences = nullptr;
    PFN_vkResetFences resetFences = nullptr;

    // The images of disposed off-screen surfaces that submitted work may still use, each with
    // the fence of an empty submit after that work; destroyed once it signals (retireImage).
    // Touched on the flusher under the entry lock, as the queue is.
    struct RetiredImage {
        VkFence fence;
        VkImage image;
        VkDeviceMemory memory;
    };
    std::deque<RetiredImage> retiredImages;
    std::vector<VkFence> idleFences;   // signaled and reset, for the next retirement
};

// One per Win32SKGraphicsConfig, on the shared device.
struct VKSKConfigInfo final : Win32SKConfigInfo {
    explicit VKSKConfigInfo(std::shared_ptr<VKSKDevice> device) : gpu(std::move(device)) {}

    std::unique_ptr<Context> makeContext(const ContextOptions &options) override;
    [[nodiscard]] bool isLost() const override;
    // "<adapter> (Vulkan)".
    [[nodiscard]] std::string description() const override { return gpu->name + " (Vulkan)"; }
    // The image (and, for a window, its surface; the swap chain follows at the first present)
    // on the configuration's device; null on failure. A window surface takes the swap chain
    // over from `previous`, the surface it replaces.
    Win32SKSurface *createSurface(HWND hwnd, uint32_t width, uint32_t height, Win32SKSurface *previous,
                                  bool opaque) override;

    const std::shared_ptr<VKSKDevice> gpu;

protected:
    [[nodiscard]] const void *device() const override { return gpu.get(); }
};

// No swap chain image is acquired (VKSKSurface.currentImage).
constexpr uint32_t VKSK_NO_IMAGE = UINT32_MAX;

// One per Win32SKSurfaceData: the image Skia renders into (persistent: Java2D repaints
// only dirty regions) and, for a window, the swap chain of its client area.
struct VKSKSurface final : Win32SKSurface {
    VKSKSurface() = default;
    // Graphite's submitted work may still use the image and the swap chain (the backend submits
    // before it disposes, without waiting). A swap chain still owned here (a window surface not
    // replaced: a closed window, a device loss) goes after the queue is idle, as its images and
    // semaphores have no fence; the image is retired until the GPU is done with it.
    ~VKSKSurface() override;

    [[nodiscard]] Win32SKAPI api() const override { return Win32SKAPI::Vulkan; }
    [[nodiscard]] SKPlatformConfig *config() const override { return platformConfig; }
    [[nodiscard]] void *textureHandle() const override;
    bool backendTexture(BackendTexture *texture, SkColorType *colorType) const override;
    // Graphite presents (acquirePresentTarget).
    bool requestRedraw() override { return false; }
    bool acquirePresentTarget(SKPlatformPresentTarget *target) override;
    bool presentTarget() override;

    SKPlatformConfig *platformConfig = nullptr;   // identity for the backend's context lookup; not dereferenced
    std::shared_ptr<VKSKDevice> gpu;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    // One BackendTexture for the surface's life: its copies share the layout state
    // Graphite keeps for the image, so every wrapper of the surface agrees on it.
    BackendTexture texture;
    uint32_t width = 0;
    uint32_t height = 0;
    HWND hwnd = nullptr;
    Win32SKClientArea clientArea;   // the insets and the swap chain's size (zero for off-screen surfaces)
    // window surfaces
    VkSurfaceKHR window = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    std::vector<VkImage> swapchainImages;
    std::vector<BackendTexture> swapchainTextures;   // one per swap chain image
    std::vector<VkSemaphore> renderDoneSemaphores;   // per image: the present waits for it
    uint32_t swapchainGeneration = 0;        // advanced with every swap chain, part of the image keys
    uint32_t currentImage = VKSK_NO_IMAGE;    // the image acquired last
    bool recreateSwapchain = true;           // the swap chain is (re)made at the next acquire, on the flusher
    bool opaque = true;
    bool noImageReported = false;
};

// Vulkan as a Win32SKGraphicsAPI: the instance and the device all its configurations share.
class VKSKGraphicsAPI final : public Win32SKGraphicsAPI {
public:
    [[nodiscard]] Win32SKAPI api() const override { return Win32SKAPI::Vulkan; }
    std::optional<Win32SKAdapterIDs> adapterIDs() override;
    // The features Graphite reads (GRAPHITE_FEATURES) of the last device created, as "name=o/g ..." with o
    // whether the adapter offers it in the queried chain and g whether the device has it; empty before the
    // first device and after a failed one.
    std::string deviceFeatures() override;

    // A configuration on the shared device; a lost device is replaced. Null if there is none.
    Win32SKConfigInfo *createConfig() override;
    void simulateDeviceLoss(bool persistent) override;
};

std::mutex gLock;                      // the instance, the device, simulated loss, the feature report
HMODULE gLoader = nullptr;
VKSKFunctions gVK;
VkInstance gInstance = VK_NULL_HANDLE;
uint32_t gInstanceVersion = LOWEST_API_VERSION;
std::vector<const char *> gInstanceExtensions;
// Never destroyed (SKNeverDestroyed.h), like the instance (VKSKPlatform.h): a static shared_ptr would
// release the device and Skia's memory allocator during DLL detach at process exit, under the loader
// lock and possibly after vulkan-1.dll and the driver are gone (Dawn's D3D12 backend fails fast doing
// the same, DawnSKPlatform.cpp).
SKNeverDestroyed<std::shared_ptr<VKSKDevice>> gDevice;
bool gLoseNewDevices = false;           // VKSKGraphicsAPI::simulateDeviceLoss(persistent)
std::string gFeatureReport;             // of the last device created (VKSKGraphicsAPI::deviceFeatures)
bool gRejectPreferredDevice = false;    // VKSKPlatform::rejectPreferredDevice

#define VKSKPlatform_INSTANCE_PROC(member, name) \
    gVK.member = (PFN_vk##name) gVK.getInstanceProcAddr(gInstance, "vk" #name)

// The loader and the instance, once; false if Vulkan is not installed or too old.
bool initInstance() {
    if (gInstance != VK_NULL_HANDLE) {
        return true;
    }
    if (gLoader == nullptr) {
        gLoader = LoadLibraryW(L"vulkan-1.dll");
        if (gLoader == nullptr) {
            J2dRlsTraceLn(J2D_TRACE_INFO, "VKSK: no Vulkan loader (vulkan-1.dll)");
            return false;
        }
        gVK.getInstanceProcAddr =
                reinterpret_cast<PFN_vkGetInstanceProcAddr>(GetProcAddress(gLoader, "vkGetInstanceProcAddr"));
    }
    if (gVK.getInstanceProcAddr == nullptr) {
        return false;
    }
    gVK.createInstance =
            reinterpret_cast<PFN_vkCreateInstance>(gVK.getInstanceProcAddr(VK_NULL_HANDLE, "vkCreateInstance"));
    gVK.enumerateInstanceVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
            gVK.getInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
    uint32_t loaderVersion = VK_API_VERSION_1_0;
    if (gVK.enumerateInstanceVersion == nullptr || gVK.enumerateInstanceVersion(&loaderVersion) != VK_SUCCESS
            || loaderVersion < LOWEST_API_VERSION) {
        J2dRlsTraceLn(J2D_TRACE_INFO, "VKSK: Vulkan %u.%u is needed (the loader offers %u.%u)",
                      VK_API_VERSION_MAJOR(LOWEST_API_VERSION), VK_API_VERSION_MINOR(LOWEST_API_VERSION),
                      VK_API_VERSION_MAJOR(loaderVersion), VK_API_VERSION_MINOR(loaderVersion));
        return false;
    }
    gInstanceVersion = std::min(loaderVersion, HIGHEST_API_VERSION);
    gInstanceExtensions = { VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_WIN32_SURFACE_EXTENSION_NAME };
    const VkApplicationInfo applicationInfo = {
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "Java2D Skia",
        .pEngineName = "Skia Graphite",
        .apiVersion = gInstanceVersion,
    };
    const VkInstanceCreateInfo instanceInfo = {
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &applicationInfo,
        .enabledExtensionCount = static_cast<uint32_t>(gInstanceExtensions.size()),
        .ppEnabledExtensionNames = gInstanceExtensions.data(),
    };
    if (gVK.createInstance == nullptr || gVK.createInstance(&instanceInfo, nullptr, &gInstance) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK: cannot create a Vulkan instance");
        gInstance = VK_NULL_HANDLE;
        return false;
    }
    VKSKPlatform_INSTANCE_PROC(enumeratePhysicalDevices, EnumeratePhysicalDevices);
    VKSKPlatform_INSTANCE_PROC(getPhysicalDeviceProperties, GetPhysicalDeviceProperties);
    VKSKPlatform_INSTANCE_PROC(getPhysicalDeviceQueueFamilyProperties, GetPhysicalDeviceQueueFamilyProperties);
    VKSKPlatform_INSTANCE_PROC(getPhysicalDeviceFeatures2, GetPhysicalDeviceFeatures2);
    VKSKPlatform_INSTANCE_PROC(getPhysicalDeviceMemoryProperties, GetPhysicalDeviceMemoryProperties);
    VKSKPlatform_INSTANCE_PROC(enumerateDeviceExtensionProperties, EnumerateDeviceExtensionProperties);
    VKSKPlatform_INSTANCE_PROC(createDevice, CreateDevice);
    VKSKPlatform_INSTANCE_PROC(getDeviceProcAddr, GetDeviceProcAddr);
    VKSKPlatform_INSTANCE_PROC(getPhysicalDeviceWin32PresentationSupportKHR, GetPhysicalDeviceWin32PresentationSupportKHR);
    VKSKPlatform_INSTANCE_PROC(createWin32SurfaceKHR, CreateWin32SurfaceKHR);
    VKSKPlatform_INSTANCE_PROC(destroySurfaceKHR, DestroySurfaceKHR);
    VKSKPlatform_INSTANCE_PROC(getPhysicalDeviceSurfaceCapabilitiesKHR, GetPhysicalDeviceSurfaceCapabilitiesKHR);
    VKSKPlatform_INSTANCE_PROC(getPhysicalDeviceSurfaceFormatsKHR, GetPhysicalDeviceSurfaceFormatsKHR);
    VKSKPlatform_INSTANCE_PROC(getPhysicalDeviceSurfacePresentModesKHR, GetPhysicalDeviceSurfacePresentModesKHR);
    return true;
}

// The adapter configurations will use, with the queue family that draws and presents.
struct VKSKAdapter {
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    VkPhysicalDeviceProperties properties = {};
};

// The first discrete adapter, else the first other hardware one, with its graphics queue family
// that can present to a window; empty if there is none. A software device (lavapipe, SwiftShader,
// WARP behind a mapping layer) is not used, as Dawn does not use WARP (DawnSKPlatform's requestAdapter).
std::optional<VKSKAdapter> pickAdapter() {
    uint32_t deviceCount = 0;
    if (gVK.enumeratePhysicalDevices(gInstance, &deviceCount, nullptr) != VK_SUCCESS) {
        return std::nullopt;
    }
    std::vector<VkPhysicalDevice> physicalDevices(deviceCount);
    if (gVK.enumeratePhysicalDevices(gInstance, &deviceCount, physicalDevices.data()) < VK_SUCCESS) {
        return std::nullopt;
    }
    physicalDevices.resize(deviceCount);
    enum class Pass { DiscreteOnly, Any };
    for (const Pass pass : { Pass::DiscreteOnly, Pass::Any }) {
        // NOLINTNEXTLINE(misc-misplaced-const): a Vulkan handle held by value is const as every unchanged local (the object it names is not)
        for (const VkPhysicalDevice candidate : physicalDevices) {
            VkPhysicalDeviceProperties properties;
            gVK.getPhysicalDeviceProperties(candidate, &properties);
            if (properties.apiVersion < LOWEST_API_VERSION || properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU
                    || (pass == Pass::DiscreteOnly && properties.deviceType != VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU)) {
                continue;
            }
            uint32_t familyCount = 0;
            gVK.getPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, nullptr);
            std::vector<VkQueueFamilyProperties> families(familyCount);
            gVK.getPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, families.data());
            for (uint32_t familyIndex = 0; familyIndex < familyCount; familyIndex++) {
                if (families[familyIndex].queueFlags & VK_QUEUE_GRAPHICS_BIT
                        && gVK.getPhysicalDeviceWin32PresentationSupportKHR(candidate, familyIndex)) {
                    return VKSKAdapter{.physical = candidate, .queueFamily = familyIndex, .properties = properties};
                }
            }
        }
    }
    return std::nullopt;
}

void deviceLost(void *context, const std::string &description, const std::vector<VkDeviceFaultAddressInfoEXT> &,
                const std::vector<VkDeviceFaultVendorInfoEXT> &, const std::vector<std::byte> &) {
    auto *lost = static_cast<std::atomic<bool> *>(context);
    J2dRlsTraceLn(J2D_TRACE_ERROR, "VKSK: device lost: %s", description.c_str());
    lost->store(true);
}

// A Vulkan device has only the extensions and features enabled when it was created, not the adapter's,
// and Graphite uses what it finds in the device's feature chain (backendContext.fDeviceFeatures2):
// dynamic state, graphics pipeline libraries, host image copies, coherent destination reads among them.
// Which extensions and feature structs that takes, in which form for the API version (promoted to core
// in 1.1, 1.2, 1.3, 1.4, which struct may be chained with which) and their dependencies is Skia's to
// know: skgpu::VulkanPreferredFeatures (include/gpu/vk/VulkanPreferredFeatures.h), as Skia's own Vulkan
// harness uses it (tools/gpu/vk/VkTestUtils.cpp), adds them to the query and then to the device.
//
// GRAPHITE_FEATURES checks the outcome: the flags Graphite m156 reads from the chain, as it reads them
// (VulkanCaps::getEnabledFeatures, src/gpu/graphite/vk/VulkanCaps.cpp; with every Skia bump compared
// with that function, jb/project/tools/common/SKIA.md), each offered (in the queried chain) and granted
// (in the device's chain); one offered but not granted fails SKVulkanFeaturesTest. Left out, as they
// stay off: protectedMemory, which Graphite only uses for a protected context and which needs protected
// queues (VulkanPreferredFeatures leaves it off, as the backend makes no protected context), and
// pipelineCreationCacheControl (withoutPipelineCreationCacheControl).
//
// "Offered" is what the chain VulkanPreferredFeatures::addFeaturesToQuery built holds, not what the adapter
// has: a feature Graphite reads but the helper does not query (a newer Graphite's, or one compiled out of
// the query by SK_DISABLE_HOST_IMAGE_COPY or SK_DISABLE_GRAPHICS_PIPELINE_LIBRARY) reports 0/0. So the
// report guards that nothing queried was dropped by the enable step or by the edits here, not that Graphite
// gets everything it reads; that is the bump's check of GRAPHITE_READ_FEATURE_COUNT (SKIA.md).
struct VKSKGraphiteFeatures {
    bool dualSrcBlend = false;
    bool occlusionQueryPrecise = false;
    bool samplerYcbcrConversion = false;
    bool hostImageCopy = false;
    bool deviceFault = false;
    bool blendOperationAdvanced = false;   // Graphite: the struct in the chain at all
    bool advancedBlendCoherentOperations = false;
    bool rasterizationOrderColorAttachmentAccess = false;
    bool extendedDynamicState = false;
    bool extendedDynamicState2 = false;
    bool vertexInputDynamicState = false;
    bool graphicsPipelineLibrary = false;
    bool multisampledRenderToSingleSampled = false;
    bool frameBoundary = false;
    bool formatRgba10x6WithoutYCbCrSampler = false;
};

struct VKSKGraphiteFeature {
    const char *name;
    bool VKSKGraphiteFeatures::*flag;
};

constexpr auto GRAPHITE_FEATURES = std::to_array<VKSKGraphiteFeature>({
    {.name = "dualSrcBlend", .flag = &VKSKGraphiteFeatures::dualSrcBlend},
    {.name = "occlusionQueryPrecise", .flag = &VKSKGraphiteFeatures::occlusionQueryPrecise},
    {.name = "samplerYcbcrConversion", .flag = &VKSKGraphiteFeatures::samplerYcbcrConversion},
    {.name = "hostImageCopy", .flag = &VKSKGraphiteFeatures::hostImageCopy},
    {.name = "deviceFault", .flag = &VKSKGraphiteFeatures::deviceFault},
    {.name = "blendOperationAdvanced", .flag = &VKSKGraphiteFeatures::blendOperationAdvanced},
    {.name = "advancedBlendCoherentOperations", .flag = &VKSKGraphiteFeatures::advancedBlendCoherentOperations},
    {.name = "rasterizationOrderColorAttachmentAccess",
     .flag = &VKSKGraphiteFeatures::rasterizationOrderColorAttachmentAccess},
    {.name = "extendedDynamicState", .flag = &VKSKGraphiteFeatures::extendedDynamicState},
    {.name = "extendedDynamicState2", .flag = &VKSKGraphiteFeatures::extendedDynamicState2},
    {.name = "vertexInputDynamicState", .flag = &VKSKGraphiteFeatures::vertexInputDynamicState},
    {.name = "graphicsPipelineLibrary", .flag = &VKSKGraphiteFeatures::graphicsPipelineLibrary},
    {.name = "multisampledRenderToSingleSampled", .flag = &VKSKGraphiteFeatures::multisampledRenderToSingleSampled},
    {.name = "frameBoundary", .flag = &VKSKGraphiteFeatures::frameBoundary},
    {.name = "formatRgba10x6WithoutYCbCrSampler", .flag = &VKSKGraphiteFeatures::formatRgba10x6WithoutYCbCrSampler},
});
static_assert(std::ranges::all_of(GRAPHITE_FEATURES,
                                  [](const VKSKGraphiteFeature &feature) {
                                      return feature.name != nullptr && feature.flag != nullptr;
                                  }),
              "every GRAPHITE_FEATURES entry names a flag");

// The distinct EnabledFeatures fields Graphite m156's VulkanCaps::getEnabledFeatures assigns (the
// `enabled.f<Name> =` lines): GRAPHITE_FEATURES and the two left out above. A bump whose count differs
// reads a feature this file does not know (SKIA.md, Bumping Skia).
constexpr size_t GRAPHITE_READ_FEATURE_COUNT = 17;
constexpr size_t GRAPHITE_FEATURES_LEFT_OUT_COUNT = 2;   // protectedMemory, pipelineCreationCacheControl
static_assert(GRAPHITE_FEATURES.size() + GRAPHITE_FEATURES_LEFT_OUT_COUNT == GRAPHITE_READ_FEATURE_COUNT,
              "GRAPHITE_FEATURES and the features left out are what Graphite reads");

// The flags of GRAPHITE_FEATURES in a feature chain, read as Graphite reads them on a device of `apiVersion`.
VKSKGraphiteFeatures graphiteFeaturesOf(const VkPhysicalDeviceFeatures2 &features, const uint32_t apiVersion) {
    VKSKGraphiteFeatures found;
    found.dualSrcBlend = features.features.dualSrcBlend;
    found.occlusionQueryPrecise = features.features.occlusionQueryPrecise;
    // core in 1.3 without a feature flag
    found.extendedDynamicState = found.extendedDynamicState2 = apiVersion >= VK_API_VERSION_1_3;
    for (auto *next = static_cast<const VkBaseInStructure *>(features.pNext); next != nullptr; next = next->pNext) {
        switch (next->sType) {
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES:
            found.samplerYcbcrConversion = reinterpret_cast<const VkPhysicalDeviceVulkan11Features *>(next)->samplerYcbcrConversion;
            break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_4_FEATURES:
            found.hostImageCopy = reinterpret_cast<const VkPhysicalDeviceVulkan14Features *>(next)->hostImageCopy;
            break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES:
            found.samplerYcbcrConversion =
                    reinterpret_cast<const VkPhysicalDeviceSamplerYcbcrConversionFeatures *>(next)->samplerYcbcrConversion;
            break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FAULT_FEATURES_EXT:
            found.deviceFault = reinterpret_cast<const VkPhysicalDeviceFaultFeaturesEXT *>(next)->deviceFault;
            break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BLEND_OPERATION_ADVANCED_FEATURES_EXT:
            found.blendOperationAdvanced = true;
            found.advancedBlendCoherentOperations =
                    reinterpret_cast<const VkPhysicalDeviceBlendOperationAdvancedFeaturesEXT *>(next)
                            ->advancedBlendCoherentOperations;
            break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RASTERIZATION_ORDER_ATTACHMENT_ACCESS_FEATURES_EXT:
            found.rasterizationOrderColorAttachmentAccess =
                    reinterpret_cast<const VkPhysicalDeviceRasterizationOrderAttachmentAccessFeaturesEXT *>(next)
                            ->rasterizationOrderColorAttachmentAccess;
            break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTENDED_DYNAMIC_STATE_FEATURES_EXT:
            found.extendedDynamicState =
                    reinterpret_cast<const VkPhysicalDeviceExtendedDynamicStateFeaturesEXT *>(next)->extendedDynamicState;
            break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTENDED_DYNAMIC_STATE_2_FEATURES_EXT:
            found.extendedDynamicState2 =
                    reinterpret_cast<const VkPhysicalDeviceExtendedDynamicState2FeaturesEXT *>(next)->extendedDynamicState2;
            break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VERTEX_INPUT_DYNAMIC_STATE_FEATURES_EXT:
            found.vertexInputDynamicState =
                    reinterpret_cast<const VkPhysicalDeviceVertexInputDynamicStateFeaturesEXT *>(next)->vertexInputDynamicState;
            break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_GRAPHICS_PIPELINE_LIBRARY_FEATURES_EXT:
            found.graphicsPipelineLibrary =
                    reinterpret_cast<const VkPhysicalDeviceGraphicsPipelineLibraryFeaturesEXT *>(next)->graphicsPipelineLibrary;
            break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MULTISAMPLED_RENDER_TO_SINGLE_SAMPLED_FEATURES_EXT:
            found.multisampledRenderToSingleSampled =
                    reinterpret_cast<const VkPhysicalDeviceMultisampledRenderToSingleSampledFeaturesEXT *>(next)
                            ->multisampledRenderToSingleSampled;
            break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_IMAGE_COPY_FEATURES:
            found.hostImageCopy = reinterpret_cast<const VkPhysicalDeviceHostImageCopyFeatures *>(next)->hostImageCopy;
            break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FRAME_BOUNDARY_FEATURES_EXT:
            found.frameBoundary = reinterpret_cast<const VkPhysicalDeviceFrameBoundaryFeaturesEXT *>(next)->frameBoundary;
            break;
        case VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RGBA10X6_FORMATS_FEATURES_EXT:
            found.formatRgba10x6WithoutYCbCrSampler =
                    reinterpret_cast<const VkPhysicalDeviceRGBA10X6FormatsFeaturesEXT *>(next)
                            ->formatRgba10x6WithoutYCbCrSampler;
            break;
        default:
            break;
        }
    }
    return found;
}

// Turns pipelineCreationCacheControl off in a feature chain (VulkanPreferredFeatures enables it). With it
// Graphite asks the driver for each pipeline without a compile first and counts its pipeline data as changed
// only after a compilation; where the driver's own shader cache already holds the pipelines (NVIDIA's, across
// sessions) nothing counts as new, so sun.java2d.skia.pipelineCache is neither filled nor replaced, a corrupt
// file not either (SKPipelineCacheCorruptTest, SKPipelineCacheExitTest). Without it Graphite stores the
// data whenever its size changed. Measured on an RTX 3080 Ti and a Radeon Vega iGPU, the features with it
// changed neither the first frame nor the frames that compile new pipelines. Below Vulkan 1.3 the feature
// is VK_EXT_pipeline_creation_cache_control's, and the extension stays in the enabled ones: an extension
// enabled with its feature off is valid (the feature struct says which of its features the device has).
// ReSharper disable once CppParameterMayBeConstPtrOrRef - edits the feature chain that features heads
void withoutPipelineCreationCacheControl(VkPhysicalDeviceFeatures2 &features) {
    for (auto *next = static_cast<VkBaseOutStructure *>(features.pNext); next != nullptr; next = next->pNext) {
        if (next->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES) {
            reinterpret_cast<VkPhysicalDeviceVulkan13Features *>(next)->pipelineCreationCacheControl = VK_FALSE;
        } else if (next->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PIPELINE_CREATION_CACHE_CONTROL_FEATURES) {
            reinterpret_cast<VkPhysicalDevicePipelineCreationCacheControlFeatures *>(next)->pipelineCreationCacheControl =
                    VK_FALSE;
        }
    }
}

// "name=o/g ..." over GRAPHITE_FEATURES (VKSKGraphicsAPI::deviceFeatures).
std::string featureReport(const VKSKGraphiteFeatures &offered, const VKSKGraphiteFeatures &granted) {
    std::string report;
    for (const auto &[name, flag] : GRAPHITE_FEATURES) {
        report += std::string(report.empty() ? "" : " ") + name + "=" + (offered.*flag ? "1" : "0") + "/"
                  + (granted.*flag ? "1" : "0");
    }
    return report;
}

#define VKSKPlatform_DEVICE_PROC(member, name) \
    gpu->member = (PFN_vk##name) gVK.getDeviceProcAddr(gpu->device, "vk" #name)

std::shared_ptr<VKSKDevice> createDevice() {
    gFeatureReport.clear();   // under gLock (createConfig); a failed creation reports no features
    const std::optional<VKSKAdapter> adapter = pickAdapter();
    if (!adapter) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK: no Vulkan adapter with a queue that draws and presents");
        return nullptr;
    }
    auto gpu = std::make_shared<VKSKDevice>();
    gpu->physical = adapter->physical;
    gpu->queueFamily = adapter->queueFamily;
    gpu->name = adapter->properties.deviceName;
    gpu->maxImageSize = adapter->properties.limits.maxImageDimension2D;
    gVK.getPhysicalDeviceMemoryProperties(gpu->physical, &gpu->memory);

    // The Vulkan the device is used with (the feature structs depend on it, VulkanPreferredFeatures).
    const uint32_t apiVersion = std::min(gInstanceVersion, adapter->properties.apiVersion);

    // The device extensions: the swap chain and the device fault report here, what Graphite uses below.
    uint32_t extensionCount = 0;
    std::vector<VkExtensionProperties> availableExtensions;
    if (gVK.enumerateDeviceExtensionProperties(gpu->physical, nullptr, &extensionCount, nullptr) == VK_SUCCESS) {
        availableExtensions.resize(extensionCount);
        if (gVK.enumerateDeviceExtensionProperties(gpu->physical, nullptr, &extensionCount, availableExtensions.data())
                < VK_SUCCESS) {
            extensionCount = 0;
        }
    }
    availableExtensions.resize(extensionCount);   // empty on failure: no swap chain extension, no device
    auto hasExtension = [&](const char *extensionName) {
        return std::ranges::any_of(availableExtensions, [extensionName](const VkExtensionProperties &extension) {
            return strcmp(extension.extensionName, extensionName) == 0;
        });
    };
    std::vector<const char *> enabledExtensions;
    for (const char *extensionName : { VK_KHR_SWAPCHAIN_EXTENSION_NAME, VK_EXT_DEVICE_FAULT_EXTENSION_NAME }) {
        if (hasExtension(extensionName)) {
            enabledExtensions.push_back(extensionName);
        }
    }
    if (!hasExtension(VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK: %s has no swap chain extension", gpu->name.c_str());
        return nullptr;
    }

    // The features, as Skia's own Vulkan harness enables them (VkTestUtils.cpp): every Vulkan 1.0 core
    // feature the adapter has, but robust buffer access, which costs performance on some GPUs; the device
    // fault report (an extension's features are enabled only through its struct in the chain; without it
    // VK_EXT_device_fault reports nothing on a device loss, VulkanBackendContext.h); and the extensions and
    // features Graphite uses (above GRAPHITE_FEATURES), queried and then enabled where the adapter has them
    // by VulkanPreferredFeatures. Skia m156 adds no instance extensions (addToInstanceExtensions is empty;
    // compared with every Skia bump, SKIA.md), so the instance, made once for every device, skips that step.
    const bool hasDeviceFault = hasExtension(VK_EXT_DEVICE_FAULT_EXTENSION_NAME);
    gpu->features = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    if (hasDeviceFault) {
        gpu->fault = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FAULT_FEATURES_EXT};
        gpu->features.pNext = &gpu->fault;
    }
    gpu->preferredFeatures.init(apiVersion);
    gpu->preferredFeatures.addFeaturesToQuery(availableExtensions.data(), availableExtensions.size(), gpu->features);
    gVK.getPhysicalDeviceFeatures2(gpu->physical, &gpu->features);
    const VKSKGraphiteFeatures offered = graphiteFeaturesOf(gpu->features, apiVersion);
    gpu->features.features.robustBufferAccess = VK_FALSE;
    // the minimal set of the retry below, before VulkanPreferredFeatures adds to it
    const VkPhysicalDeviceFeatures coreFeatures = gpu->features.features;
    const std::vector<const char *> minimalExtensions = enabledExtensions;
    gpu->preferredFeatures.addFeaturesToEnable(enabledExtensions, gpu->features);
    withoutPipelineCreationCacheControl(gpu->features);

    const VkDeviceQueueCreateInfo queueInfo = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = gpu->queueFamily,
        .queueCount = 1,
        .pQueuePriorities = &QUEUE_PRIORITY,
    };
    auto createWithEnabled = [&] {
        const VkDeviceCreateInfo deviceInfo = {
            .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
            .pNext = &gpu->features,
            .queueCreateInfoCount = 1,
            .pQueueCreateInfos = &queueInfo,
            .enabledExtensionCount = static_cast<uint32_t>(enabledExtensions.size()),
            .ppEnabledExtensionNames = enabledExtensions.data(),
        };
        return gVK.createDevice(gpu->physical, &deviceInfo, nullptr, &gpu->device);
    };
    // A driver may advertise an extension or feature and still fail the device that enables it; then the
    // device is created once more with what it was created with before VulkanPreferredFeatures: the swap
    // chain, the device fault report where offered, the core features. Graphite then runs without the rest.
    const VkResult preferredResult = gRejectPreferredDevice ? VK_ERROR_FEATURE_NOT_PRESENT : createWithEnabled();
    const bool minimal = preferredResult != VK_SUCCESS;
    if (minimal) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK: cannot create a device on %s with the features Graphite prefers "
                      "(VkResult %d%s), retrying with the swap chain, the device fault report and the core features",
                      gpu->name.c_str(), static_cast<int>(preferredResult),
                      gRejectPreferredDevice ? ", rejected for tests" : "");
        gpu->device = VK_NULL_HANDLE;
        enabledExtensions = minimalExtensions;
        gpu->features = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
        gpu->features.features = coreFeatures;
        if (hasDeviceFault) {
            gpu->fault.pNext = nullptr;   // VulkanPreferredFeatures may have chained its structs after it
            gpu->features.pNext = &gpu->fault;
        }
        if (const VkResult minimalResult = createWithEnabled(); minimalResult != VK_SUCCESS) {
            J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK: cannot create a device on %s with the minimal set either "
                          "(VkResult %d)", gpu->name.c_str(), static_cast<int>(minimalResult));
            return nullptr;
        }
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK: device on %s created with the minimal set", gpu->name.c_str());
    }
    // of the device as created: granted from the chain it was created with
    const std::string report = featureReport(offered, graphiteFeaturesOf(gpu->features, apiVersion));
    VKSKPlatform_DEVICE_PROC(getDeviceQueue, GetDeviceQueue);
    VKSKPlatform_DEVICE_PROC(createImage, CreateImage);
    VKSKPlatform_DEVICE_PROC(destroyImage, DestroyImage);
    VKSKPlatform_DEVICE_PROC(getImageMemoryRequirements, GetImageMemoryRequirements);
    VKSKPlatform_DEVICE_PROC(allocateMemory, AllocateMemory);
    VKSKPlatform_DEVICE_PROC(freeMemory, FreeMemory);
    VKSKPlatform_DEVICE_PROC(bindImageMemory, BindImageMemory);
    VKSKPlatform_DEVICE_PROC(createSwapchainKHR, CreateSwapchainKHR);
    VKSKPlatform_DEVICE_PROC(destroySwapchainKHR, DestroySwapchainKHR);
    VKSKPlatform_DEVICE_PROC(getSwapchainImagesKHR, GetSwapchainImagesKHR);
    VKSKPlatform_DEVICE_PROC(acquireNextImageKHR, AcquireNextImageKHR);
    VKSKPlatform_DEVICE_PROC(queuePresentKHR, QueuePresentKHR);
    VKSKPlatform_DEVICE_PROC(createSemaphore, CreateSemaphore);
    VKSKPlatform_DEVICE_PROC(destroySemaphore, DestroySemaphore);
    VKSKPlatform_DEVICE_PROC(queueWaitIdle, QueueWaitIdle);
    VKSKPlatform_DEVICE_PROC(queueSubmit, QueueSubmit);
    VKSKPlatform_DEVICE_PROC(createFence, CreateFence);
    VKSKPlatform_DEVICE_PROC(destroyFence, DestroyFence);
    VKSKPlatform_DEVICE_PROC(getFenceStatus, GetFenceStatus);
    VKSKPlatform_DEVICE_PROC(waitForFences, WaitForFences);
    VKSKPlatform_DEVICE_PROC(resetFences, ResetFences);
    gpu->getDeviceQueue(gpu->device, gpu->queueFamily, 0, &gpu->queue);   // the family's first queue

    // Graphite's view of it
    // NOLINTNEXTLINE(misc-misplaced-const): a Vulkan handle passed by value is const as every by-value parameter (the object it names is not)
    auto getProc = [](const char *name, const VkInstance instance, const VkDevice device) -> PFN_vkVoidFunction {
        return device != VK_NULL_HANDLE ? gVK.getDeviceProcAddr(device, name) : gVK.getInstanceProcAddr(instance, name);
    };
    gpu->extensions.init(getProc, gInstance, gpu->physical, static_cast<uint32_t>(gInstanceExtensions.size()),
                         gInstanceExtensions.data(), static_cast<uint32_t>(enabledExtensions.size()),
                         enabledExtensions.data());
    skgpu::VulkanBackendContext &backendContext = gpu->backendContext;
    backendContext.fInstance = gInstance;
    backendContext.fPhysicalDevice = gpu->physical;
    backendContext.fDevice = gpu->device;
    backendContext.fQueue = gpu->queue;
    backendContext.fGraphicsQueueIndex = gpu->queueFamily;
    backendContext.fMaxAPIVersion = apiVersion;
    backendContext.fVkExtensions = &gpu->extensions;
    backendContext.fDeviceFeatures2 = &gpu->features;
    backendContext.fGetProc = getProc;
    backendContext.fDeviceLostContext = gpu->lost.get();
    backendContext.fDeviceLostProc = deviceLost;
    backendContext.fMemoryAllocator = skgpu::VulkanMemoryAllocators::Make(backendContext,
                                                                          static_cast<skgpu::ThreadSafe>(true));
    if (!backendContext.fMemoryAllocator) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK: no memory allocator for %s", gpu->name.c_str());
        return nullptr;
    }
    std::string extensionNames;
    for (const char *extensionName : enabledExtensions) {
        extensionNames += std::string(extensionNames.empty() ? "" : " ") + extensionName;
    }
    J2dRlsTraceLn(J2D_TRACE_INFO, "VKSK: device on %s, Vulkan %u.%u, %s, device extensions %s", gpu->name.c_str(),
                  VK_API_VERSION_MAJOR(apiVersion), VK_API_VERSION_MINOR(apiVersion),
                  minimal ? "minimal set" : "the features Graphite prefers", extensionNames.c_str());
    J2dRlsTraceLn(J2D_TRACE_INFO, "VKSK: device features (offered/granted) %s", report.c_str());
    gFeatureReport = report;   // under gLock (createConfig)
    return gpu;
}

} // namespace

std::string VKSKGraphicsAPI::deviceFeatures() {
    std::lock_guard lock(gLock);
    return gFeatureReport;
}

std::optional<Win32SKAdapterIDs> VKSKGraphicsAPI::adapterIDs() {
    std::lock_guard lock(gLock);
    if (!initInstance()) {
        return std::nullopt;
    }
    const std::optional<VKSKAdapter> adapter = pickAdapter();
    if (!adapter) {
        return std::nullopt;
    }
    Win32SKAdapterIDs ids;
    ids.vendorID = adapter->properties.vendorID;
    ids.deviceID = adapter->properties.deviceID;
    ids.name = adapter->properties.deviceName;
    return ids;
}

Win32SKConfigInfo *VKSKGraphicsAPI::createConfig() {
    std::lock_guard lock(gLock);
    if (!initInstance()) {
        return nullptr;
    }
    std::shared_ptr<VKSKDevice> &device = *gDevice;
    if (device != nullptr && device->lost->load()) {
        device.reset();   // a lost device is replaced; its configurations and surfaces keep it until they go
    }
    const bool shared = device != nullptr;
    if (!shared) {
        device = createDevice();
        if (device == nullptr) {
            return nullptr;
        }
    }
    auto *config = new VKSKConfigInfo(device);
    J2dRlsTraceLn(J2D_TRACE_INFO, "VKSKGraphicsAPI::createConfig: %s, max image %u, %s device", config->description().c_str(),
                  device->maxImageSize, shared ? "shared" : "new");
    return config;
}

bool VKSKConfigInfo::isLost() const {
    if (gpu == nullptr) {
        // never: createConfig makes a configuration only with its device
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKSKConfigInfo::isLost: a configuration without a device");
        return true;
    }
    return gpu->lost->load();
}

void VKSKGraphicsAPI::simulateDeviceLoss(const bool persistent) {
    std::lock_guard lock(gLock);
    gLoseNewDevices = persistent;
    if (*gDevice != nullptr) {
        (*gDevice)->lost->store(true);
    }
}

// ---------------------------------------------------------------------------
// Surfaces
// ---------------------------------------------------------------------------

namespace {

// The first memory type among `allowedTypeBits` (VkMemoryRequirements.memoryTypeBits, one bit
// per type index) with all of `requiredFlags`; empty if there is none.
std::optional<uint32_t> findMemoryType(const VKSKDevice &gpu, const uint32_t allowedTypeBits,
                                       const VkMemoryPropertyFlags requiredFlags) {
    for (uint32_t typeIndex = 0; typeIndex < gpu.memory.memoryTypeCount; typeIndex++) {
        if (allowedTypeBits & (1u << typeIndex)
                && (gpu.memory.memoryTypes[typeIndex].propertyFlags & requiredFlags) == requiredFlags) {
            return typeIndex;
        }
    }
    return std::nullopt;
}

// NOLINTNEXTLINE(misc-misplaced-const): a Vulkan handle passed by value is const as every by-value parameter (the object it names is not)
void destroyImageNow(const VKSKDevice &gpu, const VkImage image, const VkDeviceMemory memory) {
    if (image != VK_NULL_HANDLE) {
        gpu.destroyImage(gpu.device, image, nullptr);
    }
    if (memory != VK_NULL_HANDLE) {
        gpu.freeMemory(gpu.device, memory, nullptr);
    }
}

// Destroys the retired images whose work is done, oldest first: a fence signals only after the
// work submitted before it, so the first one not signaled ends the scan. `wait`: waits for
// all of them (a new image's memory that cannot be allocated otherwise). A lost device's work
// is gone: its images go at once, as queueWaitIdle returned on a lost device before.
void reclaimRetired(VKSKDevice &gpu, const bool wait = false) {
    while (!gpu.retiredImages.empty()) {
        // NOLINTNEXTLINE(misc-misplaced-const): the Vulkan handles are bound by reference to const, not changed
        const auto &[fence, image, memory] = gpu.retiredImages.front();
        const VkResult status = gpu.lost->load() ? VK_ERROR_DEVICE_LOST
                              : wait ? gpu.waitForFences(gpu.device, 1, &fence, VK_TRUE, WAIT_WITHOUT_TIMEOUT)
                                     : gpu.getFenceStatus(gpu.device, fence);
        if (status == VK_NOT_READY || status == VK_TIMEOUT) {
            return;
        }
        destroyImageNow(gpu, image, memory);
        if (status == VK_SUCCESS && gpu.resetFences(gpu.device, 1, &fence) == VK_SUCCESS) {
            gpu.idleFences.push_back(fence);
        } else {
            gpu.destroyFence(gpu.device, fence, nullptr);
        }
        gpu.retiredImages.pop_front();
    }
}

// The image of a disposed surface, without waiting for the GPU: work submitted before (the
// backend submits a surface's draws before it disposes it, SKContext::releaseSurface) may still
// use it, so it is destroyed once the fence of an empty submit after that work has signaled
// (reclaimRetired at the next disposal, surface creation or present) instead of after a
// queueWaitIdle per disposal. Without a fence (a lost device, no memory for one) as before:
// the queue is waited for and the image destroyed at once.
// NOLINTNEXTLINE(misc-misplaced-const): a Vulkan handle passed by value is const as every by-value parameter (the object it names is not)
void retireImage(VKSKDevice &gpu, const VkImage image, const VkDeviceMemory memory) {
    reclaimRetired(gpu);
    if (image == VK_NULL_HANDLE && memory == VK_NULL_HANDLE) {
        return;
    }
    VkFence fence = VK_NULL_HANDLE;
    if (!gpu.lost->load()) {
        if (!gpu.idleFences.empty()) {
            fence = gpu.idleFences.back();
            gpu.idleFences.pop_back();
        } else {
            constexpr VkFenceCreateInfo fenceInfo = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
            if (gpu.createFence(gpu.device, &fenceInfo, nullptr, &fence) != VK_SUCCESS) {
                fence = VK_NULL_HANDLE;
            }
        }
    }
    if (fence != VK_NULL_HANDLE) {
        // no batches: the fence signals when all work submitted to the queue before has completed
        const VkResult result = gpu.queueSubmit(gpu.queue, 0, nullptr, fence);
        if (result == VK_SUCCESS) {
            gpu.retiredImages.push_back({.fence = fence, .image = image, .memory = memory});
            return;
        }
        if (result == VK_ERROR_DEVICE_LOST) {
            gpu.lost->store(true);
        }
    }
    gpu.queueWaitIdle(gpu.queue);
    if (fence != VK_NULL_HANDLE) {
        gpu.destroyFence(gpu.device, fence, nullptr);
    }
    destroyImageNow(gpu, image, memory);
}

// The persistent image Skia renders into: a render target for Graphite, sampled when
// drawn into another surface, copy source and destination for read-backs and uploads,
// an input attachment where Graphite reads the destination in the shader.
bool createImage(VKSKSurface *surface) {
    VKSKDevice &gpu = *surface->gpu;
    constexpr VkImageUsageFlags usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                                      | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                                      | VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT;
    const VkImageCreateInfo imageInfo = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = SURFACE_FORMAT,
        .extent = {.width = surface->width, .height = surface->height, .depth = 1},
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = usage,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    if (gpu.createImage(gpu.device, &imageInfo, nullptr, &surface->image) != VK_SUCCESS) {
        return false;
    }
    VkMemoryRequirements requirements;
    gpu.getImageMemoryRequirements(gpu.device, surface->image, &requirements);
    const std::optional<uint32_t> memoryType = findMemoryType(gpu, requirements.memoryTypeBits,
                                                              VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (!memoryType) {
        return false;
    }
    const VkMemoryAllocateInfo allocateInfo = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = requirements.size,
        .memoryTypeIndex = *memoryType,
    };
    reclaimRetired(gpu);
    VkResult allocation = gpu.allocateMemory(gpu.device, &allocateInfo, nullptr, &surface->memory);
    if (allocation != VK_SUCCESS && !gpu.retiredImages.empty()) {
        // the memory of images retired but not yet destroyed may be what is missing
        reclaimRetired(gpu, true);
        allocation = gpu.allocateMemory(gpu.device, &allocateInfo, nullptr, &surface->memory);
    }
    if (allocation != VK_SUCCESS || gpu.bindImageMemory(gpu.device, surface->image, surface->memory, 0) != VK_SUCCESS) {
        return false;
    }
    const VulkanTextureInfo textureInfo(VK_SAMPLE_COUNT_1_BIT, skgpu::Mipmapped::kNo, 0, SURFACE_FORMAT,
                                        VK_IMAGE_TILING_OPTIMAL, usage, VK_SHARING_MODE_EXCLUSIVE,
                                        VK_IMAGE_ASPECT_COLOR_BIT, {});
    skgpu::VulkanAlloc imageMemory;
    imageMemory.fMemory = surface->memory;
    imageMemory.fSize = requirements.size;
    surface->texture = BackendTextures::MakeVulkan(SkISize::Make(static_cast<int>(surface->width),
                                                                 static_cast<int>(surface->height)),
                                                   textureInfo, VK_IMAGE_LAYOUT_UNDEFINED, gpu.queueFamily,
                                                   surface->image, imageMemory);
    return surface->texture.isValid();
}

void releaseSwapchain(VKSKSurface *surface) {
    const VKSKDevice &gpu = *surface->gpu;
    // NOLINTNEXTLINE(misc-misplaced-const): a Vulkan handle held by value is const as every unchanged local (the object it names is not)
    for (const VkSemaphore semaphore : surface->renderDoneSemaphores) {
        gpu.destroySemaphore(gpu.device, semaphore, nullptr);
    }
    surface->renderDoneSemaphores.clear();
    surface->swapchainTextures.clear();
    surface->swapchainImages.clear();
    if (surface->swapchain != VK_NULL_HANDLE) {
        gpu.destroySwapchainKHR(gpu.device, surface->swapchain, nullptr);
        surface->swapchain = VK_NULL_HANDLE;
    }
}

// (Re)makes the swap chain of the client area, on the flusher (it waits for the queue,
// so no image of the old swap chain is still in use). Mailbox where offered, else
// immediate, else FIFO, as on Dawn: no vblank wait where the compositor allows it.
bool configureSwapchain(VKSKSurface *surface) {
    VKSKDevice &gpu = *surface->gpu;
    VkSurfaceCapabilitiesKHR capabilities;
    if (gVK.getPhysicalDeviceSurfaceCapabilitiesKHR(gpu.physical, surface->window, &capabilities) != VK_SUCCESS) {
        return false;
    }
    VkExtent2D extent = {.width = surface->clientArea.clientWidth, .height = surface->clientArea.clientHeight};
    if (capabilities.currentExtent.width != UINT32_MAX) {   // UINT32_MAX: the surface size is up to the swap chain
        extent = capabilities.currentExtent;   // the window's client area as the driver sees it now
    }
    if (extent.width == 0 || extent.height == 0) {
        return false;                  // minimized
    }
    uint32_t formatCount = 0;
    std::vector<VkSurfaceFormatKHR> formats;
    if (gVK.getPhysicalDeviceSurfaceFormatsKHR(gpu.physical, surface->window, &formatCount, nullptr) == VK_SUCCESS) {
        formats.resize(formatCount);
        if (gVK.getPhysicalDeviceSurfaceFormatsKHR(gpu.physical, surface->window, &formatCount, formats.data())
                < VK_SUCCESS) {
            formatCount = 0;
        }
    }
    formats.resize(formatCount);   // empty on failure: no format, no swap chain
    const bool bgraOffered = std::ranges::any_of(formats, [](const VkSurfaceFormatKHR &format) {
        return format.format == SURFACE_FORMAT;
    });
    if (!bgraOffered) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK: the window surface offers no B8G8R8A8_UNORM");
        return false;
    }
    uint32_t modeCount = 0;
    std::vector<VkPresentModeKHR> presentModes;
    if (gVK.getPhysicalDeviceSurfacePresentModesKHR(gpu.physical, surface->window, &modeCount, nullptr) == VK_SUCCESS) {
        presentModes.resize(modeCount);
        if (gVK.getPhysicalDeviceSurfacePresentModesKHR(gpu.physical, surface->window, &modeCount, presentModes.data())
                < VK_SUCCESS) {
            modeCount = 0;
        }
    }
    presentModes.resize(modeCount);   // empty on failure: FIFO, the mode every surface has
    VkPresentModeKHR presentMode = VK_PRESENT_MODE_FIFO_KHR;
    for (VkPresentModeKHR offered : presentModes) {
        if (offered == VK_PRESENT_MODE_MAILBOX_KHR
                || (offered == VK_PRESENT_MODE_IMMEDIATE_KHR && presentMode != VK_PRESENT_MODE_MAILBOX_KHR)) {
            presentMode = offered;
        }
    }
    uint32_t imageCount = std::max(capabilities.minImageCount, presentMode == VK_PRESENT_MODE_MAILBOX_KHR
                                                               ? MAILBOX_IMAGE_COUNT : DOUBLE_BUFFER_IMAGE_COUNT);
    if (capabilities.maxImageCount != 0) {   // 0: no upper bound
        imageCount = std::min(imageCount, capabilities.maxImageCount);
    }
    // Graphite renders only into images it could also read as input attachments (as
    // Skia's own Vulkan window context asks for); the rest where the surface offers it
    const VkImageUsageFlags usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT
            | (capabilities.supportedUsageFlags & (VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                                                   | VK_IMAGE_USAGE_SAMPLED_BIT));
    if ((capabilities.supportedUsageFlags & VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT) == 0) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK: the window surface's images cannot be input attachments");
        return false;
    }
    const VkSwapchainCreateInfoKHR swapchainInfo = {
        .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
        .surface = surface->window,
        .minImageCount = imageCount,
        .imageFormat = SURFACE_FORMAT,
        .imageColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR,
        .imageExtent = extent,
        .imageArrayLayers = 1,
        .imageUsage = usage,
        .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .preTransform = capabilities.currentTransform,
        .compositeAlpha = capabilities.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
                          ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
        .presentMode = presentMode,
        .clipped = VK_TRUE,
        .oldSwapchain = surface->swapchain,
    };
    gpu.queueWaitIdle(gpu.queue);
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    const VkResult created = gpu.createSwapchainKHR(gpu.device, &swapchainInfo, nullptr, &swapchain);
    releaseSwapchain(surface);   // the old one, retired by oldSwapchain
    if (created != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK: cannot create a %ux%u swap chain (%d)", extent.width, extent.height,
                      static_cast<int>(created));
        return false;
    }
    surface->swapchain = swapchain;
    surface->swapchainGeneration++;
    surface->currentImage = VKSK_NO_IMAGE;   // the images of the old swap chain are gone with it
    uint32_t imagesFound = 0;
    VkResult imagesQueried = gpu.getSwapchainImagesKHR(gpu.device, swapchain, &imagesFound, nullptr);
    if (imagesQueried == VK_SUCCESS && imagesFound > 0) {
        surface->swapchainImages.resize(imagesFound);
        imagesQueried = gpu.getSwapchainImagesKHR(gpu.device, swapchain, &imagesFound, surface->swapchainImages.data());
    }
    if ((imagesQueried != VK_SUCCESS && imagesQueried != VK_INCOMPLETE) || imagesFound == 0) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK: no images of the swap chain (%d)", static_cast<int>(imagesQueried));
        releaseSwapchain(surface);
        if (imagesQueried == VK_ERROR_DEVICE_LOST) {
            gpu.lost->store(true);
        }
        return false;
    }
    surface->swapchainImages.resize(imagesFound);
    VulkanTextureInfo textureInfo(VK_SAMPLE_COUNT_1_BIT, skgpu::Mipmapped::kNo, 0, SURFACE_FORMAT,
                                  VK_IMAGE_TILING_OPTIMAL, usage, VK_SHARING_MODE_EXCLUSIVE,
                                  VK_IMAGE_ASPECT_COLOR_BIT, {});
    constexpr VkSemaphoreCreateInfo semaphoreInfo = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    for (VkImage image : surface->swapchainImages) {
        surface->swapchainTextures.push_back(BackendTextures::MakeVulkan(
                SkISize::Make(static_cast<int>(extent.width), static_cast<int>(extent.height)), textureInfo,
                VK_IMAGE_LAYOUT_UNDEFINED, gpu.queueFamily, image, skgpu::VulkanAlloc()));
        VkSemaphore semaphore = VK_NULL_HANDLE;
        if (gpu.createSemaphore(gpu.device, &semaphoreInfo, nullptr, &semaphore) != VK_SUCCESS) {
            // a present would wait for a null semaphore
            J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK: cannot create the swap chain's semaphores");
            releaseSwapchain(surface);
            return false;
        }
        surface->renderDoneSemaphores.push_back(semaphore);
    }
    surface->clientArea.clientWidth = extent.width;
    surface->clientArea.clientHeight = extent.height;
    surface->recreateSwapchain = false;
    J2dTraceLn(J2D_TRACE_VERBOSE, "VKSK: swap chain %ux%u, %u images, present mode %d", extent.width, extent.height,
               imagesFound, (int) presentMode);
    return true;
}

} // namespace

// NOLINTNEXTLINE(misc-misplaced-const): the HWND passed by value is const as every by-value parameter (the window it names is not)
Win32SKSurface *VKSKConfigInfo::createSurface(const HWND hwnd, const uint32_t width, const uint32_t height,
                                              Win32SKSurface *win32Previous, const bool opaque) {
    // the surface being replaced is Vulkan's: one API renders per process
    VKSKSurface *previous = nullptr;
    if (win32Previous != nullptr && win32Previous->api() == Win32SKAPI::Vulkan) {
        // NOLINTNEXTLINE(cppcoreguidelines-pro-type-static-cast-downcast): checked by api()
        previous = static_cast<VKSKSurface *>(win32Previous);
    }
    if (isLost() || width == 0 || height == 0) {
        return nullptr;
    }
    if (width > gpu->maxImageSize || height > gpu->maxImageSize) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSKConfigInfo::createSurface: %ux%u exceeds the adapter's %u", width, height,
                      gpu->maxImageSize);
        return nullptr;
    }
    auto *surface = new VKSKSurface();
    surface->platformConfig = this;
    surface->gpu = gpu;
    surface->hwnd = hwnd;
    surface->width = width;
    surface->height = height;
    surface->opaque = opaque;
    if (hwnd != nullptr) {
        const std::optional<Win32SKClientArea> clientArea = Win32SKPlatform::clientArea(hwnd, width, height);
        if (!clientArea) {
            J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSKConfigInfo::createSurface: HWND %p is gone", hwnd);
            delete surface;
            return nullptr;
        }
        surface->clientArea = *clientArea;
    }
    if (!createImage(surface)) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSKConfigInfo::createSurface: cannot create a %ux%u image", width, height);
        delete surface;   // retires what createImage made
        return nullptr;
    }
    if (hwnd != nullptr) {
        if (previous != nullptr && previous->hwnd == hwnd && previous->window != VK_NULL_HANDLE
                && previous->gpu != gpu) {
            // the swap chain of another device (the one lost before this configuration's) cannot
            // be handed over, and the window takes one swap chain at a time: released now
            previous->gpu->queueWaitIdle(previous->gpu->queue);
            releaseSwapchain(previous);
            gVK.destroySurfaceKHR(gInstance, previous->window, nullptr);
            previous->window = VK_NULL_HANDLE;
        }
        if (previous != nullptr && previous->hwnd == hwnd && previous->window != VK_NULL_HANDLE
                && previous->gpu == gpu) {
            // one swap chain per window: the surface being replaced hands its over; the
            // flusher remakes it for the new size at the next present
            surface->window = previous->window;
            surface->swapchain = previous->swapchain;
            surface->swapchainImages = std::move(previous->swapchainImages);
            surface->swapchainTextures = std::move(previous->swapchainTextures);
            surface->renderDoneSemaphores = std::move(previous->renderDoneSemaphores);
            surface->swapchainGeneration = previous->swapchainGeneration;
            previous->window = VK_NULL_HANDLE;
            previous->swapchain = VK_NULL_HANDLE;
        } else {
            const VkWin32SurfaceCreateInfoKHR windowInfo = {
                .sType = VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR,
                .hinstance = GetModuleHandleW(nullptr),
                .hwnd = hwnd,
            };
            if (gVK.createWin32SurfaceKHR(gInstance, &windowInfo, nullptr, &surface->window) != VK_SUCCESS) {
                J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSKConfigInfo::createSurface: no Vulkan surface for HWND %p", hwnd);
                delete surface;
                return nullptr;
            }
        }
        surface->recreateSwapchain = true;
        if (gLoseNewDevices) {
            gpu->lost->store(true);   // simulateDeviceLoss(persistent): as a driver that does not come back
        }
    }
    J2dTraceLn(J2D_TRACE_INFO, "VKSKConfigInfo::createSurface: %p %ux%u hwnd=%p client=%ux%u at (%u,%u)", surface, width,
               height, hwnd, surface->clientArea.clientWidth, surface->clientArea.clientHeight,
               surface->clientArea.insetX, surface->clientArea.insetY);
    return surface;
}

// ---------------------------------------------------------------------------
// The configurations and surfaces as SKPlatform.h's
// ---------------------------------------------------------------------------

std::unique_ptr<Context> VKSKConfigInfo::makeContext(const ContextOptions &options) {
    if (isLost()) {
        return nullptr;
    }
    return ContextFactory::MakeVulkan(gpu->backendContext, options);
}

void *VKSKSurface::textureHandle() const {
    return !gpu->lost->load() ? static_cast<void *>(image) : nullptr;
}

bool VKSKSurface::backendTexture(BackendTexture *backendTexture, SkColorType *colorType) const {
    if (!texture.isValid() || gpu->lost->load()) {
        return false;
    }
    *backendTexture = texture;   // shares the layout state with every earlier copy
    *colorType = SURFACE_COLOR_TYPE;
    return true;
}

namespace {

// What a present acquired and releases once the GPU is done with it (releaseAcquired).
struct VKSKAcquired {
    std::shared_ptr<VKSKDevice> gpu;
    VkSemaphore acquireSemaphore;
};

// Graphite calls this when the GPU is done with the present's recording: the image's
// acquire semaphore has been waited for and can go.
// NOLINTNEXTLINE(misc-misplaced-const): the context passed by value is const as every by-value parameter (the object it points to is not)
void releaseAcquired(const GpuFinishedContext context, skgpu::CallbackResult) {
    const auto *acquired = static_cast<VKSKAcquired *>(context);
    acquired->gpu->destroySemaphore(acquired->gpu->device, acquired->acquireSemaphore, nullptr);
    delete acquired;
}

} // namespace

bool VKSKSurface::acquirePresentTarget(SKPlatformPresentTarget *target) {
    if (window == VK_NULL_HANDLE || gpu->lost->load()) {
        return true;   // Graphite presents on this platform; there is nothing to present now
    }
    VKSKDevice &device = *gpu;
    reclaimRetired(device);   // the retired images whose work is done
    if (currentImage != VKSK_NO_IMAGE) {
        // The image acquired last was never presented (its wrap or its recording's insert
        // failed, SKPresentation.cpp): it stays acquired until its swap chain is retired, and
        // acquires of unpresented images would exhaust the swap chain. A new one releases it.
        currentImage = VKSK_NO_IMAGE;
        recreateSwapchain = true;
    }
    if ((recreateSwapchain || swapchain == VK_NULL_HANDLE) && !configureSwapchain(this)) {
        return true;
    }
    constexpr VkSemaphoreCreateInfo semaphoreInfo = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    VkSemaphore acquireSemaphore = VK_NULL_HANDLE;
    if (device.createSemaphore(device.device, &semaphoreInfo, nullptr, &acquireSemaphore) != VK_SUCCESS) {
        return true;
    }
    // Not WAIT_WITHOUT_TIMEOUT: this runs on the flusher under the render-queue lock, and an acquire
    // that never returns (no image free, a driver that stalls) would hang the event thread behind it.
    // No image within the timeout: nothing is presented now and the swap chain is remade.
    constexpr uint64_t ACQUIRE_TIMEOUT_NANOS = 1'000'000'000;   // one second
    uint32_t imageIndex = 0;
    VkResult acquired = device.acquireNextImageKHR(device.device, swapchain, ACQUIRE_TIMEOUT_NANOS, acquireSemaphore,
                                                VK_NULL_HANDLE, &imageIndex);
    if (acquired == VK_ERROR_OUT_OF_DATE_KHR) {
        // the window changed under the swap chain: remake it and try once more
        if (configureSwapchain(this)) {
            acquired = device.acquireNextImageKHR(device.device, swapchain, ACQUIRE_TIMEOUT_NANOS, acquireSemaphore,
                                               VK_NULL_HANDLE, &imageIndex);
        }
    }
    if (acquired != VK_SUCCESS && acquired != VK_SUBOPTIMAL_KHR) {
        device.destroySemaphore(device.device, acquireSemaphore, nullptr);   // not signaled: no image was acquired
        if (acquired == VK_ERROR_DEVICE_LOST) {
            device.lost->store(true);
        } else if (acquired == VK_TIMEOUT || acquired == VK_NOT_READY) {
            recreateSwapchain = true;
        }
        if (!noImageReported) {
            noImageReported = true;
            J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSKSurface::acquirePresentTarget: no swap chain image (%d)",
                          static_cast<int>(acquired));
        }
        return true;
    }
    noImageReported = false;
    recreateSwapchain = acquired == VK_SUBOPTIMAL_KHR;   // presentable, but remake it next time
    currentImage = imageIndex;
    target->texture = swapchainTextures[imageIndex];
    target->colorType = SURFACE_COLOR_TYPE;
    target->imageKey = (static_cast<uint64_t>(swapchainGeneration) << SK_IMAGE_KEY_GENERATION_SHIFT) | imageIndex;
    target->source = SkIRect::MakeXYWH(static_cast<int>(clientArea.insetX), static_cast<int>(clientArea.insetY),
                                       static_cast<int>(std::min(width - clientArea.insetX, clientArea.clientWidth)),
                                       static_cast<int>(std::min(height - clientArea.insetY, clientArea.clientHeight)));
    target->waitSemaphore = BackendSemaphores::MakeVulkan(acquireSemaphore);
    target->signalSemaphore = BackendSemaphores::MakeVulkan(renderDoneSemaphores[imageIndex]);
    target->presentState = skgpu::MutableTextureStates::MakeVulkan(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, device.queueFamily);
    target->finishedProc = releaseAcquired;
    target->finishedContext = new VKSKAcquired{.gpu = gpu, .acquireSemaphore = acquireSemaphore};
    return true;
}

bool VKSKSurface::presentTarget() {
    if (swapchain == VK_NULL_HANDLE || currentImage == VKSK_NO_IMAGE || gpu->lost->load()) {
        return false;
    }
    const VKSKDevice &device = *gpu;
    const VkPresentInfoKHR presentInfo = {
        .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
        .waitSemaphoreCount = 1,
        .pWaitSemaphores = &renderDoneSemaphores[currentImage],
        .swapchainCount = 1,
        .pSwapchains = &swapchain,
        .pImageIndices = &currentImage,
    };
    const VkResult presented = device.queuePresentKHR(device.queue, &presentInfo);
    currentImage = VKSK_NO_IMAGE;
    if (presented == VK_ERROR_OUT_OF_DATE_KHR || presented == VK_SUBOPTIMAL_KHR) {
        recreateSwapchain = true;
    } else if (presented == VK_ERROR_DEVICE_LOST) {
        device.lost->store(true);
    }
    return presented == VK_SUCCESS || presented == VK_SUBOPTIMAL_KHR;
}

VKSKSurface::~VKSKSurface() {
    J2dTraceLn(J2D_TRACE_VERBOSE, "VKSKSurface::~VKSKSurface: %p", this);
    VKSKDevice &device = *gpu;
    if (window != VK_NULL_HANDLE) {   // not handed over to a replacing surface
        device.queueWaitIdle(device.queue);
        releaseSwapchain(this);
        gVK.destroySurfaceKHR(gInstance, window, nullptr);
    }
    retireImage(device, image, memory);
}

// ---------------------------------------------------------------------------
// Vulkan as a Win32SKGraphicsAPI
// ---------------------------------------------------------------------------

Win32SKGraphicsAPI &VKSKPlatform::graphicsAPI() {
    static SKNeverDestroyed<VKSKGraphicsAPI> graphicsAPI;   // never destroyed, as the instance and devices are not
    return *graphicsAPI;
}

void VKSKPlatform::rejectPreferredDevice(const bool reject) {
    std::lock_guard lock(gLock);
    gRejectPreferredDevice = reject;
}
