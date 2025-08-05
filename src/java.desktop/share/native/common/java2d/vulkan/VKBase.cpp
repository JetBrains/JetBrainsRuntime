/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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

#include "VKBase.h"
#include <Trace.h>
#include <set>

#define VALIDATION_LAYER_NAME "VK_LAYER_KHRONOS_validation"
static const uint32_t REQUIRED_VULKAN_VERSION = VK_MAKE_API_VERSION(0, 1, 0, 0);

std::unique_ptr<VKGraphicsEnvironment> VKGraphicsEnvironment::_ge_instance = nullptr;

// ========== Vulkan instance ==========

#if defined(DEBUG)
static vk::raii::DebugUtilsMessengerEXT debugMessenger = nullptr;

static VkBool32 debugCallback(
        VkDebugUtilsMessageSeverityFlagBitsEXT messageSeverity,
        VkDebugUtilsMessageTypeFlagsEXT messageType,
        const VkDebugUtilsMessengerCallbackDataEXT* pCallbackData,
        void* pUserData
) {
    auto data = (const vk::DebugUtilsMessengerCallbackDataEXT*) pCallbackData;
    if (data == nullptr) return 0;
    // Here we can filter messages like this:
    // if (std::strcmp(data->pMessageIdName, "UNASSIGNED-BestPractices-DrawState-ClearCmdBeforeDraw") == 0) return 0;

    int level = J2D_TRACE_OFF;
    if (messageSeverity == VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT) level = J2D_TRACE_VERBOSE;
    else if (messageSeverity == VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT) level = J2D_TRACE_INFO;
    else if (messageSeverity == VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) level = J2D_TRACE_WARNING;
    else if (messageSeverity == VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) level = J2D_TRACE_ERROR;

    J2dRlsTraceLn(level, data->pMessage);

    // TODO if (messageSeverity == VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) ABORT?
    return 0;
}
#endif

// ========== Vulkan device ==========

#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
extern struct wl_display *wl_display;
#endif

class PhysicalDevice : vk::raii::PhysicalDevice {
    friend class VKDevice;

    bool _supported = false;
    std::vector<const char*> _enabled_layers, _enabled_extensions;
    int _queue_family = -1;
public:

    int queue_family() const {
        return _queue_family;
    }

    std::vector<const char *> & enabled_layers()  {
        return _enabled_layers;
    }

    std::vector<const char *> & enabled_extensions()  {
        return _enabled_extensions;
    }

    explicit PhysicalDevice(vk::raii::PhysicalDevice&& handle) : vk::raii::PhysicalDevice(std::move(handle)) {
        const auto& properties = getProperties();
        const auto& queueFamilies = getQueueFamilyProperties();

        J2dRlsTrace(J2D_TRACE_INFO, "Vulkan: Found device %s (%d.%d.%d, %s)\n",
                    (const char*) properties.deviceName,
                    VK_API_VERSION_MAJOR(properties.apiVersion),
                    VK_API_VERSION_MINOR(properties.apiVersion),
                    VK_API_VERSION_PATCH(properties.apiVersion),
                    vk::to_string(properties.deviceType).c_str());
        if (properties.apiVersion < REQUIRED_VULKAN_VERSION) {
            J2dRlsTrace(J2D_TRACE_INFO, "    Unsupported Vulkan version\n");
            return;
        }

        // Check supported queue families.
        for (unsigned int i = 0; i < queueFamilies.size(); i++) {
            const auto& family = queueFamilies[i];
#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
            bool presentationSupported = getWaylandPresentationSupportKHR(i, *wl_display);
#endif
            char logFlags[5] {
                    family.queueFlags & vk::QueueFlagBits::eGraphics ? 'G' : '-',
                    family.queueFlags & vk::QueueFlagBits::eCompute ? 'C' : '-',
                    family.queueFlags & vk::QueueFlagBits::eTransfer ? 'T' : '-',
                    family.queueFlags & vk::QueueFlagBits::eSparseBinding ? 'S' : '-',
                    presentationSupported ? 'P' : '-'
            };
            J2dRlsTrace(J2D_TRACE_INFO, "    %d queues in family (%.*s)\n", family.queueCount, 5, logFlags);

            // TODO use compute workloads? Separate transfer-only DMA queue?
            if (_queue_family == -1 && (family.queueFlags & vk::QueueFlagBits::eGraphics) && presentationSupported) {
                _queue_family = i;
            }
        }

        if (_queue_family == -1) {
            J2dRlsTrace(J2D_TRACE_INFO, "    No suitable queue\n");
            return;
        }

        // Populate maps and log supported layers & extensions.
        std::set<std::string> layers, extensions;
        J2dRlsTrace(J2D_TRACE_VERBOSE, "    Supported device layers:\n");
        for (auto& l : enumerateDeviceLayerProperties()) {
            J2dRlsTrace(J2D_TRACE_VERBOSE, "        %s\n", (char*) l.layerName);
            layers.emplace((char*) l.layerName);
        }
        J2dRlsTrace(J2D_TRACE_VERBOSE, "    Supported device extensions:\n");
        for (auto& e : enumerateDeviceExtensionProperties(nullptr)) {
            J2dRlsTrace(J2D_TRACE_VERBOSE, "        %s\n", (char*) e.extensionName);
            extensions.emplace((char*) e.extensionName);
        }

        // Check required layers & extensions.
        _enabled_extensions.push_back(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
        bool requiredNotFound = false;
        for (auto e : _enabled_extensions) {
            if (extensions.find(e) == extensions.end()) {
                J2dRlsTrace(J2D_TRACE_INFO, "    Required device extension not supported: %s\n", (char*) e);
                requiredNotFound = true;
            }
        }
        if (requiredNotFound) return;

        // Validation layer
#ifdef DEBUG
        if (layers.find(VALIDATION_LAYER_NAME) != layers.end()) {
            _enabled_layers.push_back(VALIDATION_LAYER_NAME);
        } else {
            J2dRlsTrace(J2D_TRACE_INFO, "    %s device layer is not supported\n", VALIDATION_LAYER_NAME);
        }
#endif

        // This device is supported
        _supported = true;
    }

    operator bool() const {
        vk::PhysicalDevice handle = **this;
        return handle && _supported;
    }

    uint32_t getMaxImageDimension2D() {
        const auto& properties = getProperties();
        return properties.limits.maxImageDimension2D;
    }

};

VKGraphicsEnvironment *VKGraphicsEnvironment::graphics_environment() {
    if (!_ge_instance) {
        try {
            _ge_instance = std::unique_ptr<VKGraphicsEnvironment>(new VKGraphicsEnvironment());
        }
        catch (std::exception& e) {
            J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: %s\n", e.what());
            return nullptr;
        }
    }
    return _ge_instance.get();
}

VKGraphicsEnvironment::VKGraphicsEnvironment() :
    _vk_context(), _vk_instance(nullptr), _default_device(-1) {
    // Load library.
    uint32_t version = _vk_context.enumerateInstanceVersion();
    J2dRlsTrace(J2D_TRACE_INFO, "Vulkan: Available (%d.%d.%d)\n",
                VK_API_VERSION_MAJOR(version), VK_API_VERSION_MINOR(version), VK_API_VERSION_PATCH(version));

    if (version < REQUIRED_VULKAN_VERSION) {
        throw std::runtime_error("Vulkan: Unsupported version");
    }

    // Populate maps and log supported layers & extensions.
    std::set<std::string> layers, extensions;
    J2dRlsTrace(J2D_TRACE_VERBOSE, "    Supported instance layers:\n");
    for (auto &l: _vk_context.enumerateInstanceLayerProperties()) {
        J2dRlsTrace(J2D_TRACE_VERBOSE, "        %s\n", (char *) l.layerName);
        layers.emplace((char *) l.layerName);
    }
    J2dRlsTrace(J2D_TRACE_VERBOSE, "    Supported instance extensions:\n");
    for (auto &e: _vk_context.enumerateInstanceExtensionProperties(nullptr)) {
        J2dRlsTrace(J2D_TRACE_VERBOSE, "        %s\n", (char *) e.extensionName);
        extensions.emplace((char *) e.extensionName);
    }

    std::vector<const char *> enabledLayers, enabledExtensions;
    const void *pNext = nullptr;

    // Check required layers & extensions.
#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
    enabledExtensions.push_back(VK_KHR_WAYLAND_SURFACE_EXTENSION_NAME);
#endif
    enabledExtensions.push_back(VK_KHR_SURFACE_EXTENSION_NAME);
    for (auto e: enabledExtensions) {
        if (extensions.find(e) == extensions.end()) {
            throw std::runtime_error(std::string("Vulkan: Required instance extension not supported:") +
                                     (char *) e);
        }
    }
    // Configure validation
#ifdef DEBUG
    std::array<vk::ValidationFeatureEnableEXT, 4> enabledValidationFeatures = {
            vk::ValidationFeatureEnableEXT::eGpuAssisted,
            vk::ValidationFeatureEnableEXT::eGpuAssistedReserveBindingSlot,
            vk::ValidationFeatureEnableEXT::eBestPractices,
            vk::ValidationFeatureEnableEXT::eSynchronizationValidation
    };
    vk::ValidationFeaturesEXT validationFeatures {enabledValidationFeatures};
    if (layers.find(VALIDATION_LAYER_NAME) != layers.end() &&
        extensions.find(VK_EXT_DEBUG_UTILS_EXTENSION_NAME) != extensions.end()) {
        enabledLayers.push_back(VALIDATION_LAYER_NAME);
        enabledExtensions.push_back(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
        pNext = &validationFeatures;
    } else {
        J2dRlsTrace(J2D_TRACE_WARNING, "Vulkan: %s and %s are not supported\n",
                    VALIDATION_LAYER_NAME, VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
    }
#endif

    vk::ApplicationInfo applicationInfo{
            /*pApplicationName*/   "OpenJDK",
            /*applicationVersion*/ 0,
            /*pEngineName*/        "OpenJDK",
            /*engineVersion*/      0,
            /*apiVersion*/         REQUIRED_VULKAN_VERSION
    };

    vk::InstanceCreateInfo instanceCreateInfo{
            /*flags*/                   {},
            /*pApplicationInfo*/        &applicationInfo,
            /*ppEnabledLayerNames*/     enabledLayers,
            /*ppEnabledExtensionNames*/ enabledExtensions,
            /*pNext*/                   pNext
    };

    // Save context object at persistent address before passing it further.
    _vk_instance = vk::raii::Instance(_vk_context, instanceCreateInfo);
    J2dRlsTrace(J2D_TRACE_INFO, "Vulkan: Instance created\n");

    // Create debug messenger
#if defined(DEBUG)
    if (pNext) {
        debugMessenger = vk::raii::DebugUtilsMessengerEXT(_vk_instance, vk::DebugUtilsMessengerCreateInfoEXT {
                /*flags*/           {},
                /*messageSeverity*/ vk::DebugUtilsMessageSeverityFlagBitsEXT::eError |
                                    vk::DebugUtilsMessageSeverityFlagBitsEXT::eWarning |
                                    vk::DebugUtilsMessageSeverityFlagBitsEXT::eInfo |
                                    vk::DebugUtilsMessageSeverityFlagBitsEXT::eVerbose,
                /*messageType*/     vk::DebugUtilsMessageTypeFlagBitsEXT::eGeneral |
                                    vk::DebugUtilsMessageTypeFlagBitsEXT::eValidation |
                                    vk::DebugUtilsMessageTypeFlagBitsEXT::ePerformance,
                /*pfnUserCallback*/ &debugCallback
        });
    }

#endif

    // Find suitable devices.
    for (auto &handle: _vk_instance.enumeratePhysicalDevices()) {
        PhysicalDevice physicalDevice{std::move(handle)};
        if (physicalDevice) { // Supported.
            _physical_devices.push_back(std::move(physicalDevice));
        }
    }

    if (_physical_devices.empty()) {
        throw std::runtime_error("Vulkan: No suitable device found");
    }
    // Create virtual device for a physical device.
    // TODO system property for manual choice of GPU
    // TODO integrated/discrete presets
    // TODO performance/power saving mode switch on the fly?
    _default_physical_device = 0; // TODO pick first just to check hat virtual device creation works
    _devices.push_back(std::move(VKDevice{_physical_devices[_default_physical_device]}));
    _default_device = 0;
}

uint32_t VKGraphicsEnvironment::max_texture_size() {
    return _physical_devices[_default_physical_device].getMaxImageDimension2D();
}

vk::raii::Instance& VKGraphicsEnvironment::vk_instance() {
    return _vk_instance;
}

void VKGraphicsEnvironment::dispose() {
    _ge_instance.reset();
}

VKDevice& VKGraphicsEnvironment::default_device() {
    return _devices[_default_device];
}


VKDevice::VKDevice(PhysicalDevice& physicalDevice) : vk::raii::Device(nullptr), _command_pool(nullptr) {
    float queuePriorities[1] {1.0f}; // We only use one queue for now
    std::vector<vk::DeviceQueueCreateInfo> queueCreateInfos;
    queueCreateInfos.push_back(vk::DeviceQueueCreateInfo {
            {}, (uint32_t) physicalDevice.queue_family(), 1, &queuePriorities[0]
    });

    vk::DeviceCreateInfo deviceCreateInfo {
            /*flags*/                   {},
            /*pQueueCreateInfos*/       queueCreateInfos,
            /*ppEnabledLayerNames*/     physicalDevice.enabled_layers(),
            /*ppEnabledExtensionNames*/ physicalDevice.enabled_extensions(),
            /*pEnabledFeatures*/        nullptr
    };
    *((vk::raii::Device*) this) = {physicalDevice, deviceCreateInfo};
    this->_queue_family =  physicalDevice.queue_family();
    J2dRlsTrace(J2D_TRACE_INFO, "Vulkan: Device created\n"); // TODO which one?
}

extern "C" jint VK_MaxTextureSize() {
    return (jint)VKGraphicsEnvironment::graphics_environment()->max_texture_size();
}

extern "C" jboolean VK_Init() {

    if (VKGraphicsEnvironment::graphics_environment() != nullptr) {
        return true;
    }

#if defined(DEBUG)
    debugMessenger = nullptr;
#endif
    return false;
}