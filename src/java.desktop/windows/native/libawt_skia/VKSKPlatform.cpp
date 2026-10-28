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

#include "Trace.h"
#include "include/gpu/MutableTextureState.h"
#include "include/gpu/graphite/BackendSemaphore.h"
#include "include/gpu/graphite/Context.h"
#include "include/gpu/graphite/vk/VulkanGraphiteContext.h"
#include "include/gpu/graphite/vk/VulkanGraphiteTypes.h"
#include "include/gpu/vk/VulkanMemoryAllocator.h"
#include "include/gpu/vk/VulkanMutableTextureState.h"

#include <algorithm>
#include <cstring>
#include <mutex>

using namespace skgpu::graphite;

// Skia's VMA-based allocator, in the release's skia.lib without a public header
// (src/gpu/vk/vulkanmemoryallocator/VulkanMemoryAllocatorPriv.h declares it so).
namespace skgpu {
enum class ThreadSafe : bool;
namespace VulkanMemoryAllocators {
sk_sp<VulkanMemoryAllocator> Make(const VulkanBackendContext &, ThreadSafe);
}
}

static constexpr VkFormat kSurfaceFormat = VK_FORMAT_B8G8R8A8_UNORM;
static constexpr SkColorType kSurfaceColorType = kBGRA_8888_SkColorType;

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
    VkPhysicalDeviceFeatures2 features = {};
    skgpu::VulkanExtensions extensions;
    skgpu::VulkanBackendContext backend;
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
};

static std::mutex gLock;                      // the instance, the device, simulated loss
static HMODULE gLoader = nullptr;
static VKSKFunctions gVk;
static VkInstance gInstance = VK_NULL_HANDLE;
static uint32_t gInstanceVersion = VK_API_VERSION_1_1;
static std::vector<const char *> gInstanceExtensions;
static std::shared_ptr<VKSKDevice> gDevice;
static bool gLoseNewDevices = false;           // VKSK_SimulateDeviceLoss(persistent)

#define VKSK_INSTANCE_PROC(member, name) \
    gVk.member = (PFN_vk##name) gVk.getInstanceProcAddr(gInstance, "vk" #name)

// The loader and the instance, once; false if Vulkan is not installed or too old.
static bool initInstance() {
    if (gInstance != VK_NULL_HANDLE) {
        return true;
    }
    if (gLoader == nullptr) {
        gLoader = LoadLibraryW(L"vulkan-1.dll");
        if (gLoader == nullptr) {
            J2dRlsTraceLn(J2D_TRACE_INFO, "VKSK: no Vulkan loader (vulkan-1.dll)");
            return false;
        }
        gVk.getInstanceProcAddr = (PFN_vkGetInstanceProcAddr) GetProcAddress(gLoader, "vkGetInstanceProcAddr");
    }
    if (gVk.getInstanceProcAddr == nullptr) {
        return false;
    }
    gVk.createInstance = (PFN_vkCreateInstance) gVk.getInstanceProcAddr(VK_NULL_HANDLE, "vkCreateInstance");
    gVk.enumerateInstanceVersion =
            (PFN_vkEnumerateInstanceVersion) gVk.getInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion");
    uint32_t version = VK_API_VERSION_1_0;
    if (gVk.enumerateInstanceVersion == nullptr || gVk.enumerateInstanceVersion(&version) != VK_SUCCESS
            || version < VK_API_VERSION_1_1) {
        J2dRlsTraceLn(J2D_TRACE_INFO, "VKSK: Vulkan 1.1 is needed (the loader offers %u.%u)",
                      VK_API_VERSION_MAJOR(version), VK_API_VERSION_MINOR(version));
        return false;
    }
    gInstanceVersion = std::min(version, (uint32_t) VK_API_VERSION_1_3);
    gInstanceExtensions = { VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_WIN32_SURFACE_EXTENSION_NAME };
    VkApplicationInfo app = { VK_STRUCTURE_TYPE_APPLICATION_INFO };
    app.pApplicationName = "Java2D Skia";
    app.pEngineName = "Skia Graphite";
    app.apiVersion = gInstanceVersion;
    VkInstanceCreateInfo info = { VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
    info.pApplicationInfo = &app;
    info.enabledExtensionCount = (uint32_t) gInstanceExtensions.size();
    info.ppEnabledExtensionNames = gInstanceExtensions.data();
    if (gVk.createInstance == nullptr || gVk.createInstance(&info, nullptr, &gInstance) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK: cannot create a Vulkan instance");
        gInstance = VK_NULL_HANDLE;
        return false;
    }
    VKSK_INSTANCE_PROC(enumeratePhysicalDevices, EnumeratePhysicalDevices);
    VKSK_INSTANCE_PROC(getPhysicalDeviceProperties, GetPhysicalDeviceProperties);
    VKSK_INSTANCE_PROC(getPhysicalDeviceQueueFamilyProperties, GetPhysicalDeviceQueueFamilyProperties);
    VKSK_INSTANCE_PROC(getPhysicalDeviceFeatures2, GetPhysicalDeviceFeatures2);
    VKSK_INSTANCE_PROC(getPhysicalDeviceMemoryProperties, GetPhysicalDeviceMemoryProperties);
    VKSK_INSTANCE_PROC(enumerateDeviceExtensionProperties, EnumerateDeviceExtensionProperties);
    VKSK_INSTANCE_PROC(createDevice, CreateDevice);
    VKSK_INSTANCE_PROC(getDeviceProcAddr, GetDeviceProcAddr);
    VKSK_INSTANCE_PROC(getPhysicalDeviceWin32PresentationSupportKHR, GetPhysicalDeviceWin32PresentationSupportKHR);
    VKSK_INSTANCE_PROC(createWin32SurfaceKHR, CreateWin32SurfaceKHR);
    VKSK_INSTANCE_PROC(destroySurfaceKHR, DestroySurfaceKHR);
    VKSK_INSTANCE_PROC(getPhysicalDeviceSurfaceCapabilitiesKHR, GetPhysicalDeviceSurfaceCapabilitiesKHR);
    VKSK_INSTANCE_PROC(getPhysicalDeviceSurfaceFormatsKHR, GetPhysicalDeviceSurfaceFormatsKHR);
    VKSK_INSTANCE_PROC(getPhysicalDeviceSurfacePresentModesKHR, GetPhysicalDeviceSurfacePresentModesKHR);
    return true;
}

// The first discrete adapter, else the first one, with its graphics queue family that
// can present to a window; false if there is none.
static bool pickAdapter(VkPhysicalDevice *physical, uint32_t *queueFamily, VkPhysicalDeviceProperties *properties) {
    uint32_t count = 0;
    gVk.enumeratePhysicalDevices(gInstance, &count, nullptr);
    std::vector<VkPhysicalDevice> devices(count);
    gVk.enumeratePhysicalDevices(gInstance, &count, devices.data());
    for (int pass = 0; pass < 2; pass++) {
        for (VkPhysicalDevice candidate : devices) {
            VkPhysicalDeviceProperties props;
            gVk.getPhysicalDeviceProperties(candidate, &props);
            if (props.apiVersion < VK_API_VERSION_1_1
                    || (pass == 0 && props.deviceType != VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU)) {
                continue;
            }
            uint32_t families = 0;
            gVk.getPhysicalDeviceQueueFamilyProperties(candidate, &families, nullptr);
            std::vector<VkQueueFamilyProperties> familyProps(families);
            gVk.getPhysicalDeviceQueueFamilyProperties(candidate, &families, familyProps.data());
            for (uint32_t i = 0; i < families; i++) {
                if ((familyProps[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)
                        && gVk.getPhysicalDeviceWin32PresentationSupportKHR(candidate, i)) {
                    *physical = candidate;
                    *queueFamily = i;
                    *properties = props;
                    return true;
                }
            }
        }
    }
    return false;
}

static void deviceLost(void *context, const std::string &description, const std::vector<VkDeviceFaultAddressInfoEXT> &,
                       const std::vector<VkDeviceFaultVendorInfoEXT> &, const std::vector<std::byte> &) {
    auto *lost = static_cast<std::atomic<bool> *>(context);
    J2dRlsTraceLn(J2D_TRACE_ERROR, "VKSK: device lost: %s", description.c_str());
    lost->store(true);
}

#define VKSK_DEVICE_PROC(member, name) \
    gpu->member = (PFN_vk##name) gVk.getDeviceProcAddr(gpu->device, "vk" #name)

static std::shared_ptr<VKSKDevice> createDevice() {
    auto gpu = std::make_shared<VKSKDevice>();
    VkPhysicalDeviceProperties props;
    if (!pickAdapter(&gpu->physical, &gpu->queueFamily, &props)) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK: no Vulkan adapter with a queue that draws and presents");
        return nullptr;
    }
    gpu->name = props.deviceName;
    gpu->maxImageSize = props.limits.maxImageDimension2D;
    gVk.getPhysicalDeviceMemoryProperties(gpu->physical, &gpu->memory);

    // The device extensions: the swap chain, and what Graphite uses when present.
    uint32_t count = 0;
    gVk.enumerateDeviceExtensionProperties(gpu->physical, nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> available(count);
    gVk.enumerateDeviceExtensionProperties(gpu->physical, nullptr, &count, available.data());
    auto has = [&](const char *name) {
        return std::any_of(available.begin(), available.end(),
                           [name](const VkExtensionProperties &e) { return strcmp(e.extensionName, name) == 0; });
    };
    std::vector<const char *> extensions;
    for (const char *name : { VK_KHR_SWAPCHAIN_EXTENSION_NAME,
                              VK_EXT_RASTERIZATION_ORDER_ATTACHMENT_ACCESS_EXTENSION_NAME,
                              VK_EXT_DEVICE_FAULT_EXTENSION_NAME }) {
        if (has(name)) {
            extensions.push_back(name);
        }
    }
    if (!has(VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK: %s has no swap chain extension", gpu->name.c_str());
        return nullptr;
    }

    // Every core feature the adapter has, but robust buffer access (as Skia's own tools do).
    gpu->features = { VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2 };
    gVk.getPhysicalDeviceFeatures2(gpu->physical, &gpu->features);
    gpu->features.features.robustBufferAccess = VK_FALSE;

    const float priority = 1.0f;
    VkDeviceQueueCreateInfo queue = { VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO };
    queue.queueFamilyIndex = gpu->queueFamily;
    queue.queueCount = 1;
    queue.pQueuePriorities = &priority;
    VkDeviceCreateInfo info = { VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO };
    info.pNext = &gpu->features;
    info.queueCreateInfoCount = 1;
    info.pQueueCreateInfos = &queue;
    info.enabledExtensionCount = (uint32_t) extensions.size();
    info.ppEnabledExtensionNames = extensions.data();
    if (gVk.createDevice(gpu->physical, &info, nullptr, &gpu->device) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK: cannot create a device on %s", gpu->name.c_str());
        return nullptr;
    }
    VKSK_DEVICE_PROC(getDeviceQueue, GetDeviceQueue);
    VKSK_DEVICE_PROC(createImage, CreateImage);
    VKSK_DEVICE_PROC(destroyImage, DestroyImage);
    VKSK_DEVICE_PROC(getImageMemoryRequirements, GetImageMemoryRequirements);
    VKSK_DEVICE_PROC(allocateMemory, AllocateMemory);
    VKSK_DEVICE_PROC(freeMemory, FreeMemory);
    VKSK_DEVICE_PROC(bindImageMemory, BindImageMemory);
    VKSK_DEVICE_PROC(createSwapchainKHR, CreateSwapchainKHR);
    VKSK_DEVICE_PROC(destroySwapchainKHR, DestroySwapchainKHR);
    VKSK_DEVICE_PROC(getSwapchainImagesKHR, GetSwapchainImagesKHR);
    VKSK_DEVICE_PROC(acquireNextImageKHR, AcquireNextImageKHR);
    VKSK_DEVICE_PROC(queuePresentKHR, QueuePresentKHR);
    VKSK_DEVICE_PROC(createSemaphore, CreateSemaphore);
    VKSK_DEVICE_PROC(destroySemaphore, DestroySemaphore);
    VKSK_DEVICE_PROC(queueWaitIdle, QueueWaitIdle);
    gpu->getDeviceQueue(gpu->device, gpu->queueFamily, 0, &gpu->queue);

    // Graphite's view of it
    auto getProc = [](const char *name, VkInstance instance, VkDevice device) -> PFN_vkVoidFunction {
        return device != VK_NULL_HANDLE ? gVk.getDeviceProcAddr(device, name) : gVk.getInstanceProcAddr(instance, name);
    };
    gpu->extensions.init(getProc, gInstance, gpu->physical, (uint32_t) gInstanceExtensions.size(),
                         gInstanceExtensions.data(), (uint32_t) extensions.size(), extensions.data());
    skgpu::VulkanBackendContext &backend = gpu->backend;
    backend.fInstance = gInstance;
    backend.fPhysicalDevice = gpu->physical;
    backend.fDevice = gpu->device;
    backend.fQueue = gpu->queue;
    backend.fGraphicsQueueIndex = gpu->queueFamily;
    backend.fMaxAPIVersion = std::min(gInstanceVersion, props.apiVersion);
    backend.fVkExtensions = &gpu->extensions;
    backend.fDeviceFeatures2 = &gpu->features;
    backend.fGetProc = getProc;
    backend.fDeviceLostContext = gpu->lost.get();
    backend.fDeviceLostProc = deviceLost;
    backend.fMemoryAllocator = skgpu::VulkanMemoryAllocators::Make(backend, static_cast<skgpu::ThreadSafe>(true));
    if (!backend.fMemoryAllocator) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK: no memory allocator for %s", gpu->name.c_str());
        return nullptr;
    }
    J2dRlsTraceLn(J2D_TRACE_INFO, "VKSK: device on %s, Vulkan %u.%u, %zu device extensions", gpu->name.c_str(),
                  VK_API_VERSION_MAJOR(backend.fMaxAPIVersion), VK_API_VERSION_MINOR(backend.fMaxAPIVersion),
                  extensions.size());
    return gpu;
}

std::string VKSK_DescribeAdapter() {
    std::lock_guard<std::mutex> lock(gLock);
    VkPhysicalDevice physical;
    uint32_t queueFamily;
    VkPhysicalDeviceProperties props;
    if (!initInstance() || !pickAdapter(&physical, &queueFamily, &props)) {
        return std::string();
    }
    return std::string(props.deviceName) + " (Vulkan)";
}

VKSKConfigInfo *VKSK_CreateConfig() {
    std::lock_guard<std::mutex> lock(gLock);
    if (!initInstance()) {
        return nullptr;
    }
    if (gDevice != nullptr && gDevice->lost->load()) {
        gDevice.reset();   // a lost device is replaced; its configurations and surfaces keep it until they go
    }
    const bool shared = gDevice != nullptr;
    if (!shared) {
        gDevice = createDevice();
        if (gDevice == nullptr) {
            return nullptr;
        }
    }
    auto *config = new VKSKConfigInfo();
    config->gpu = gDevice;
    config->description = gDevice->name + " (Vulkan)";
    J2dRlsTraceLn(J2D_TRACE_INFO, "VKSK_CreateConfig: %s, max image %u, %s device", config->description.c_str(),
                  gDevice->maxImageSize, shared ? "shared" : "new");
    return config;
}

void VKSK_DisposeConfig(VKSKConfigInfo *config) {
    delete config;
}

bool VKSK_IsLost(const VKSKConfigInfo *config) {
    return config == nullptr || config->gpu == nullptr || config->gpu->lost->load();
}

void VKSK_SimulateDeviceLoss(bool persistent) {
    std::lock_guard<std::mutex> lock(gLock);
    gLoseNewDevices = persistent;
    if (gDevice != nullptr) {
        gDevice->lost->store(true);
    }
}

// ---------------------------------------------------------------------------
// Surfaces
// ---------------------------------------------------------------------------

static inline VKSKConfigInfo *configOf(SKPlatformConfig *config) {
    return static_cast<VKSKConfigInfo *>(reinterpret_cast<Win32SKConfigInfo *>(config));
}

static inline VKSKSurface *surfaceOf(SKPlatformSurface *surface) {
    return static_cast<VKSKSurface *>(reinterpret_cast<Win32SKSurface *>(surface));
}

static bool findMemoryType(const VKSKDevice &gpu, uint32_t typeBits, VkMemoryPropertyFlags flags, uint32_t *index) {
    for (uint32_t i = 0; i < gpu.memory.memoryTypeCount; i++) {
        if ((typeBits & (1u << i)) && (gpu.memory.memoryTypes[i].propertyFlags & flags) == flags) {
            *index = i;
            return true;
        }
    }
    return false;
}

// The persistent image Skia renders into: a render target for Graphite, sampled when
// drawn into another surface, copy source and destination for read-backs and uploads,
// an input attachment where Graphite reads the destination in the shader.
static bool createImage(VKSKSurface *s) {
    VKSKDevice &gpu = *s->gpu;
    const VkImageUsageFlags usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                                  | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                                  | VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT;
    VkImageCreateInfo info = { VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO };
    info.imageType = VK_IMAGE_TYPE_2D;
    info.format = kSurfaceFormat;
    info.extent = { s->width, s->height, 1 };
    info.mipLevels = 1;
    info.arrayLayers = 1;
    info.samples = VK_SAMPLE_COUNT_1_BIT;
    info.tiling = VK_IMAGE_TILING_OPTIMAL;
    info.usage = usage;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (gpu.createImage(gpu.device, &info, nullptr, &s->image) != VK_SUCCESS) {
        return false;
    }
    VkMemoryRequirements requirements;
    gpu.getImageMemoryRequirements(gpu.device, s->image, &requirements);
    VkMemoryAllocateInfo alloc = { VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO };
    alloc.allocationSize = requirements.size;
    if (!findMemoryType(gpu, requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, &alloc.memoryTypeIndex)
            || gpu.allocateMemory(gpu.device, &alloc, nullptr, &s->memory) != VK_SUCCESS
            || gpu.bindImageMemory(gpu.device, s->image, s->memory, 0) != VK_SUCCESS) {
        return false;
    }
    VulkanTextureInfo textureInfo(VK_SAMPLE_COUNT_1_BIT, skgpu::Mipmapped::kNo, 0, kSurfaceFormat,
                                  VK_IMAGE_TILING_OPTIMAL, usage, VK_SHARING_MODE_EXCLUSIVE,
                                  VK_IMAGE_ASPECT_COLOR_BIT, {});
    skgpu::VulkanAlloc memory;
    memory.fMemory = s->memory;
    memory.fSize = requirements.size;
    s->texture = BackendTextures::MakeVulkan(SkISize::Make((int) s->width, (int) s->height), textureInfo,
                                             VK_IMAGE_LAYOUT_UNDEFINED, gpu.queueFamily, s->image, memory);
    return s->texture.isValid();
}

static void releaseSwapchain(VKSKSurface *s) {
    VKSKDevice &gpu = *s->gpu;
    for (VkSemaphore semaphore : s->renderDone) {
        gpu.destroySemaphore(gpu.device, semaphore, nullptr);
    }
    s->renderDone.clear();
    s->imageTextures.clear();
    s->images.clear();
    if (s->swapchain != VK_NULL_HANDLE) {
        gpu.destroySwapchainKHR(gpu.device, s->swapchain, nullptr);
        s->swapchain = VK_NULL_HANDLE;
    }
}

// (Re)makes the swap chain of the client area, on the flusher (it waits for the queue,
// so no image of the old swap chain is still in use). Mailbox where offered, else
// immediate, else FIFO, as on Dawn: no vblank wait where the compositor allows it.
static bool configureSwapchain(VKSKSurface *s) {
    VKSKDevice &gpu = *s->gpu;
    VkSurfaceCapabilitiesKHR caps;
    if (gVk.getPhysicalDeviceSurfaceCapabilitiesKHR(gpu.physical, s->window, &caps) != VK_SUCCESS) {
        return false;
    }
    VkExtent2D extent = { s->clientWidth, s->clientHeight };
    if (caps.currentExtent.width != UINT32_MAX) {
        extent = caps.currentExtent;   // the window's client area as the driver sees it now
    }
    if (extent.width == 0 || extent.height == 0) {
        return false;                  // minimized
    }
    uint32_t formatCount = 0;
    gVk.getPhysicalDeviceSurfaceFormatsKHR(gpu.physical, s->window, &formatCount, nullptr);
    std::vector<VkSurfaceFormatKHR> formats(formatCount);
    gVk.getPhysicalDeviceSurfaceFormatsKHR(gpu.physical, s->window, &formatCount, formats.data());
    const bool bgra = std::any_of(formats.begin(), formats.end(),
                                  [](const VkSurfaceFormatKHR &f) { return f.format == kSurfaceFormat; });
    if (!bgra) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK: the window surface offers no B8G8R8A8_UNORM");
        return false;
    }
    uint32_t modeCount = 0;
    gVk.getPhysicalDeviceSurfacePresentModesKHR(gpu.physical, s->window, &modeCount, nullptr);
    std::vector<VkPresentModeKHR> modes(modeCount);
    gVk.getPhysicalDeviceSurfacePresentModesKHR(gpu.physical, s->window, &modeCount, modes.data());
    VkPresentModeKHR mode = VK_PRESENT_MODE_FIFO_KHR;
    for (VkPresentModeKHR m : modes) {
        if (m == VK_PRESENT_MODE_MAILBOX_KHR || (m == VK_PRESENT_MODE_IMMEDIATE_KHR && mode != VK_PRESENT_MODE_MAILBOX_KHR)) {
            mode = m;
        }
    }
    uint32_t imageCount = std::max(caps.minImageCount, mode == VK_PRESENT_MODE_MAILBOX_KHR ? 3u : 2u);
    if (caps.maxImageCount != 0) {
        imageCount = std::min(imageCount, caps.maxImageCount);
    }
    // Graphite renders only into images it could also read as input attachments (as
    // Skia's own Vulkan window context asks for); the rest where the surface offers it
    const VkImageUsageFlags usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT
            | (caps.supportedUsageFlags & (VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                                           | VK_IMAGE_USAGE_SAMPLED_BIT));
    if ((caps.supportedUsageFlags & VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT) == 0) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK: the window surface's images cannot be input attachments");
        return false;
    }
    VkSwapchainCreateInfoKHR info = { VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR };
    info.surface = s->window;
    info.minImageCount = imageCount;
    info.imageFormat = kSurfaceFormat;
    info.imageColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    info.imageExtent = extent;
    info.imageArrayLayers = 1;
    info.imageUsage = usage;
    info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    info.preTransform = caps.currentTransform;
    info.compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                          ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    info.presentMode = mode;
    info.clipped = VK_TRUE;
    info.oldSwapchain = s->swapchain;
    gpu.queueWaitIdle(gpu.queue);
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    const VkResult result = gpu.createSwapchainKHR(gpu.device, &info, nullptr, &swapchain);
    releaseSwapchain(s);   // the old one, retired by oldSwapchain
    if (result != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK: cannot create a %ux%u swap chain (%d)", extent.width, extent.height,
                      (int) result);
        return false;
    }
    s->swapchain = swapchain;
    s->generation++;
    uint32_t count = 0;
    gpu.getSwapchainImagesKHR(gpu.device, swapchain, &count, nullptr);
    s->images.resize(count);
    gpu.getSwapchainImagesKHR(gpu.device, swapchain, &count, s->images.data());
    VulkanTextureInfo textureInfo(VK_SAMPLE_COUNT_1_BIT, skgpu::Mipmapped::kNo, 0, kSurfaceFormat,
                                  VK_IMAGE_TILING_OPTIMAL, usage, VK_SHARING_MODE_EXCLUSIVE,
                                  VK_IMAGE_ASPECT_COLOR_BIT, {});
    VkSemaphoreCreateInfo semaphoreInfo = { VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO };
    for (VkImage image : s->images) {
        s->imageTextures.push_back(BackendTextures::MakeVulkan(SkISize::Make((int) extent.width, (int) extent.height),
                                                               textureInfo, VK_IMAGE_LAYOUT_UNDEFINED, gpu.queueFamily,
                                                               image, skgpu::VulkanAlloc()));
        VkSemaphore semaphore = VK_NULL_HANDLE;
        gpu.createSemaphore(gpu.device, &semaphoreInfo, nullptr, &semaphore);
        s->renderDone.push_back(semaphore);
    }
    s->clientWidth = extent.width;
    s->clientHeight = extent.height;
    s->recreate = false;
    J2dTraceLn(J2D_TRACE_VERBOSE, "VKSK: swap chain %ux%u, %u images, present mode %d", extent.width, extent.height,
               count, (int) mode);
    return true;
}

VKSKSurface *VKSK_CreateSurface(VKSKConfigInfo *config, HWND hwnd, uint32_t width, uint32_t height,
                                VKSKSurface *previous, bool opaque) {
    if (VKSK_IsLost(config) || width == 0 || height == 0) {
        return nullptr;
    }
    const std::shared_ptr<VKSKDevice> &gpu = config->gpu;
    if (width > gpu->maxImageSize || height > gpu->maxImageSize) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK_CreateSurface: %ux%u exceeds the adapter's %u", width, height,
                      gpu->maxImageSize);
        return nullptr;
    }
    auto *surface = new VKSKSurface();
    surface->config = config;
    surface->gpu = gpu;
    surface->hwnd = hwnd;
    surface->width = width;
    surface->height = height;
    surface->opaque = opaque;
    if (hwnd != nullptr) {
        // the window's insets and client area, as DawnSK_CreateSurface finds them
        RECT window = {}, client = {};
        POINT origin = { 0, 0 };
        if (!GetWindowRect(hwnd, &window) || !GetClientRect(hwnd, &client) || !ClientToScreen(hwnd, &origin)) {
            J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK_CreateSurface: HWND %p is gone", hwnd);
            delete surface;
            return nullptr;
        }
        LONG insetX = origin.x - window.left, insetY = origin.y - window.top;
        surface->insetX = insetX > 0 && (uint32_t) insetX < width ? (uint32_t) insetX : 0;
        surface->insetY = insetY > 0 && (uint32_t) insetY < height ? (uint32_t) insetY : 0;
        surface->clientWidth = client.right > 0 ? (uint32_t) client.right : width - surface->insetX;
        surface->clientHeight = client.bottom > 0 ? (uint32_t) client.bottom : height - surface->insetY;
    }
    if (!createImage(surface)) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK_CreateSurface: cannot create a %ux%u image", width, height);
        VKSK_DisposeSurface(reinterpret_cast<SKPlatformSurface *>(static_cast<Win32SKSurface *>(surface)));
        return nullptr;
    }
    if (hwnd != nullptr) {
        if (previous != nullptr && previous->hwnd == hwnd && previous->window != VK_NULL_HANDLE
                && previous->gpu == gpu) {
            // one swap chain per window: the surface being replaced hands its over; the
            // flusher remakes it for the new size at the next present
            surface->window = previous->window;
            surface->swapchain = previous->swapchain;
            surface->images = std::move(previous->images);
            surface->imageTextures = std::move(previous->imageTextures);
            surface->renderDone = std::move(previous->renderDone);
            surface->generation = previous->generation;
            previous->window = VK_NULL_HANDLE;
            previous->swapchain = VK_NULL_HANDLE;
        } else {
            VkWin32SurfaceCreateInfoKHR info = { VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR };
            info.hinstance = GetModuleHandleW(nullptr);
            info.hwnd = hwnd;
            if (gVk.createWin32SurfaceKHR(gInstance, &info, nullptr, &surface->window) != VK_SUCCESS) {
                J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK_CreateSurface: no Vulkan surface for HWND %p", hwnd);
                VKSK_DisposeSurface(reinterpret_cast<SKPlatformSurface *>(static_cast<Win32SKSurface *>(surface)));
                return nullptr;
            }
        }
        surface->recreate = true;
        if (gLoseNewDevices) {
            gpu->lost->store(true);   // VKSK_SimulateDeviceLoss(persistent): as a driver that does not come back
        }
    }
    J2dTraceLn(J2D_TRACE_INFO, "VKSK_CreateSurface: %p %ux%u hwnd=%p client=%ux%u at (%u,%u)", surface, width, height,
               hwnd, surface->clientWidth, surface->clientHeight, surface->insetX, surface->insetY);
    return surface;
}

// ---------------------------------------------------------------------------
// SKPlatform.h, the GPU side
// ---------------------------------------------------------------------------

std::unique_ptr<Context> VKSK_MakeContext(SKPlatformConfig *config, const ContextOptions &options) {
    VKSKConfigInfo *info = configOf(config);
    if (VKSK_IsLost(info)) {
        return nullptr;
    }
    return ContextFactory::MakeVulkan(info->gpu->backend, options);
}

SKPlatformConfig *VKSK_ConfigOf(SKPlatformSurface *surface) {
    VKSKSurface *s = surfaceOf(surface);
    return s != nullptr ? reinterpret_cast<SKPlatformConfig *>(static_cast<Win32SKConfigInfo *>(s->config)) : nullptr;
}

bool VKSK_SameDevice(SKPlatformConfig *a, SKPlatformConfig *b) {
    VKSKConfigInfo *ia = configOf(a);
    VKSKConfigInfo *ib = configOf(b);
    return ia != nullptr && ib != nullptr && ia->gpu != nullptr && ia->gpu == ib->gpu;
}

void *VKSK_TextureHandle(SKPlatformSurface *surface) {
    VKSKSurface *s = surfaceOf(surface);
    return s != nullptr && !s->gpu->lost->load() ? (void *) s->image : nullptr;
}

bool VKSK_BackendTexture(SKPlatformSurface *surface, BackendTexture *texture, SkColorType *colorType) {
    VKSKSurface *s = surfaceOf(surface);
    if (s == nullptr || !s->texture.isValid() || s->gpu->lost->load()) {
        return false;
    }
    *texture = s->texture;   // shares the layout state with every earlier copy
    *colorType = kSurfaceColorType;
    return true;
}

bool VKSK_IsWindow(SKPlatformSurface *surface) {
    VKSKSurface *s = surfaceOf(surface);
    return s != nullptr && s->window != VK_NULL_HANDLE;
}

struct VKSKAcquired {
    std::shared_ptr<VKSKDevice> gpu;
    VkSemaphore semaphore;
};

// Graphite calls this when the GPU is done with the present's recording: the image's
// acquire semaphore has been waited for and can go.
static void releaseAcquired(GpuFinishedContext context, skgpu::CallbackResult) {
    auto *acquired = static_cast<VKSKAcquired *>(context);
    acquired->gpu->destroySemaphore(acquired->gpu->device, acquired->semaphore, nullptr);
    delete acquired;
}

bool VKSK_AcquirePresentTarget(SKPlatformSurface *surface, SKPlatformPresentTarget *target) {
    VKSKSurface *s = surfaceOf(surface);
    if (s == nullptr || s->window == VK_NULL_HANDLE || s->gpu->lost->load()) {
        return true;   // Graphite presents on this platform; there is nothing to present now
    }
    VKSKDevice &gpu = *s->gpu;
    if ((s->recreate || s->swapchain == VK_NULL_HANDLE) && !configureSwapchain(s)) {
        return true;
    }
    VkSemaphoreCreateInfo semaphoreInfo = { VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO };
    VkSemaphore acquire = VK_NULL_HANDLE;
    if (gpu.createSemaphore(gpu.device, &semaphoreInfo, nullptr, &acquire) != VK_SUCCESS) {
        return true;
    }
    uint32_t index = 0;
    VkResult result = gpu.acquireNextImageKHR(gpu.device, s->swapchain, UINT64_MAX, acquire, VK_NULL_HANDLE, &index);
    if (result == VK_ERROR_OUT_OF_DATE_KHR) {
        // the window changed under the swap chain: remake it and try once more
        if (configureSwapchain(s)) {
            result = gpu.acquireNextImageKHR(gpu.device, s->swapchain, UINT64_MAX, acquire, VK_NULL_HANDLE, &index);
        }
    }
    if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
        gpu.destroySemaphore(gpu.device, acquire, nullptr);
        if (result == VK_ERROR_DEVICE_LOST) {
            gpu.lost->store(true);
        }
        if (!s->noImageReported) {
            s->noImageReported = true;
            J2dRlsTraceLn(J2D_TRACE_WARNING, "VKSK_AcquirePresentTarget: no swap chain image (%d)", (int) result);
        }
        return true;
    }
    s->noImageReported = false;
    s->recreate = result == VK_SUBOPTIMAL_KHR;   // presentable, but remake it next time
    s->current = index;
    target->texture = s->imageTextures[index];
    target->colorType = kSurfaceColorType;
    target->imageKey = ((uint64_t) s->generation << 32) | index;
    target->source = SkIRect::MakeXYWH((int) s->insetX, (int) s->insetY,
                                       (int) std::min(s->width - s->insetX, s->clientWidth),
                                       (int) std::min(s->height - s->insetY, s->clientHeight));
    target->waitSemaphore = BackendSemaphores::MakeVulkan(acquire);
    target->signalSemaphore = BackendSemaphores::MakeVulkan(s->renderDone[index]);
    target->presentState = skgpu::MutableTextureStates::MakeVulkan(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, gpu.queueFamily);
    target->finishedProc = releaseAcquired;
    target->finishedContext = new VKSKAcquired{ s->gpu, acquire };
    return true;
}

bool VKSK_PresentTarget(SKPlatformSurface *surface) {
    VKSKSurface *s = surfaceOf(surface);
    if (s == nullptr || s->swapchain == VK_NULL_HANDLE || s->current == UINT32_MAX || s->gpu->lost->load()) {
        return false;
    }
    VKSKDevice &gpu = *s->gpu;
    VkPresentInfoKHR info = { VK_STRUCTURE_TYPE_PRESENT_INFO_KHR };
    info.waitSemaphoreCount = 1;
    info.pWaitSemaphores = &s->renderDone[s->current];
    info.swapchainCount = 1;
    info.pSwapchains = &s->swapchain;
    info.pImageIndices = &s->current;
    const VkResult result = gpu.queuePresentKHR(gpu.queue, &info);
    s->current = UINT32_MAX;
    if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) {
        s->recreate = true;
    } else if (result == VK_ERROR_DEVICE_LOST) {
        gpu.lost->store(true);
    }
    return result == VK_SUCCESS || result == VK_SUBOPTIMAL_KHR;
}

void VKSK_DisposeSurface(SKPlatformSurface *surface) {
    VKSKSurface *s = surfaceOf(surface);
    if (s == nullptr) {
        return;
    }
    J2dTraceLn(J2D_TRACE_VERBOSE, "VKSK_DisposeSurface: %p", surface);
    VKSKDevice &gpu = *s->gpu;
    // Graphite's submitted work may still use the image (the backend submits before it
    // disposes, without waiting); on the flusher the queue can be waited for.
    gpu.queueWaitIdle(gpu.queue);
    if (s->window != VK_NULL_HANDLE) {   // not handed over to a replacing surface
        releaseSwapchain(s);
        gVk.destroySurfaceKHR(gInstance, s->window, nullptr);
    }
    if (s->image != VK_NULL_HANDLE) {
        gpu.destroyImage(gpu.device, s->image, nullptr);
    }
    if (s->memory != VK_NULL_HANDLE) {
        gpu.freeMemory(gpu.device, s->memory, nullptr);
    }
    delete s;
}
