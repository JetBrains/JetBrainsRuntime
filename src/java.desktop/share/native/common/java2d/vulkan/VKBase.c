/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2024, JetBrains s.r.o.. All rights reserved.
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

#include <dlfcn.h>
#include <malloc.h>
#include <string.h>
#include <vulkan/vulkan.h>

#include "jlong_md.h"
#include "jvm_md.h"
#include "jni_util.h"
#include "CArrayUtil.h"
#include "VKBase.h"
#include "VKVertex.h"
#include "VKRenderer.h"
#include "VKTexturePool.h"
#include <Trace.h>

#define VULKAN_DLL JNI_LIB_NAME("vulkan")
#define VULKAN_1_DLL VERSIONED_JNI_LIB_NAME("vulkan", "1")
static const uint32_t REQUIRED_VULKAN_VERSION = VK_MAKE_API_VERSION(0, 1, 2, 0);


#define MAX_ENABLED_LAYERS 5
#define MAX_ENABLED_EXTENSIONS 5
#define VALIDATION_LAYER_NAME "VK_LAYER_KHRONOS_validation"
#define COUNT_OF(x) (sizeof(x)/sizeof(x[0]))

static jboolean verbose;
static VKGraphicsEnvironment* geInstance = NULL;
static void* pVulkanLib = NULL;
#define INCLUDE_BYTECODE
#define SHADER_ENTRY(NAME, TYPE) static uint32_t NAME ## _ ## TYPE ## _data[] = {
#define BYTECODE_END };
#include "vulkan/shader_list.h"
#undef INCLUDE_BYTECODE
#undef SHADER_ENTRY
#undef BYTECODE_END

#define GET_VK_PROC_RET_FALSE_IF_ERR(GETPROCADDR, STRUCT, HANDLE, NAME) do {                   \
    STRUCT->NAME = (PFN_ ## NAME) GETPROCADDR(HANDLE, #NAME);                                  \
    if (STRUCT->NAME == NULL) {                                                                \
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Required api is not supported. " #NAME " is missing.");\
        return JNI_FALSE;                                                                      \
    }                                                                                          \
} while (0)

static void vulkanLibClose() {
    if (pVulkanLib != NULL) {
        if (geInstance != NULL) {
            ARRAY_FREE(geInstance->physicalDevices);
            if (geInstance->devices != NULL) {
                for (uint32_t i = 0; i < ARRAY_SIZE(geInstance->devices); i++) {
                    if (geInstance->devices[i].enabledExtensions != NULL) {
                        free(geInstance->devices[i].enabledExtensions);
                    }
                    if (geInstance->devices[i].enabledLayers != NULL) {
                        free(geInstance->devices[i].enabledLayers);
                    }
                    if (geInstance->devices[i].name != NULL) {
                        free(geInstance->devices[i].name);
                    }
                    if (geInstance->devices[i].texturePool != NULL) {
                        VKTexturePool_Dispose(geInstance->devices[i].texturePool);
                    }
                    if (geInstance->devices[i].vkDestroyDevice != NULL && geInstance->devices[i].device != NULL) {
                        geInstance->devices[i].vkDestroyDevice(geInstance->devices[i].device, NULL);
                    }
                }
                free(geInstance->devices);
            }

#if defined(DEBUG)
            if (geInstance->vkDestroyDebugUtilsMessengerEXT != NULL &&
                geInstance->debugMessenger != NULL && geInstance->vkInstance != NULL) {
                geInstance->vkDestroyDebugUtilsMessengerEXT(geInstance->vkInstance, geInstance->debugMessenger, NULL);
            }
#endif

            if (geInstance->vkDestroyInstance != NULL && geInstance->vkInstance != NULL) {
                geInstance->vkDestroyInstance(geInstance->vkInstance, NULL);
            }
            free(geInstance);
            geInstance = NULL;
        }
        dlclose(pVulkanLib);
        pVulkanLib = NULL;
    }
}

static PFN_vkGetInstanceProcAddr vulkanLibOpen() {
    if (pVulkanLib == NULL) {
        pVulkanLib = dlopen(VULKAN_DLL, RTLD_NOW);
        if (pVulkanLib == NULL) {
            pVulkanLib = dlopen(VULKAN_1_DLL, RTLD_NOW);
        }
        if (pVulkanLib == NULL) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "Failed to load %s", VULKAN_DLL);
            return NULL;
        }
    }

    PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr = (PFN_vkGetInstanceProcAddr) dlsym(pVulkanLib, "vkGetInstanceProcAddr");
    if (vkGetInstanceProcAddr == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
                      "Failed to get proc address of vkGetInstanceProcAddr from %s", VULKAN_DLL);
        vulkanLibClose();
        return NULL;
    }
    return vkGetInstanceProcAddr;
}

static const char* physicalDeviceTypeString(VkPhysicalDeviceType type)
{
    switch (type)
    {
#define STR(r) case VK_PHYSICAL_DEVICE_TYPE_ ##r: return #r
        STR(OTHER);
        STR(INTEGRATED_GPU);
        STR(DISCRETE_GPU);
        STR(VIRTUAL_GPU);
        STR(CPU);
#undef STR
        default: return "UNKNOWN_DEVICE_TYPE";
    }
}

#if defined(DEBUG)
static VkBool32 debugCallback(
        VkDebugUtilsMessageSeverityFlagBitsEXT messageSeverity,
        VkDebugUtilsMessageTypeFlagsEXT messageType,
        const VkDebugUtilsMessengerCallbackDataEXT* pCallbackData,
        void* pUserData
) {
    if (pCallbackData == NULL) return VK_FALSE;
    // Here we can filter messages like this:
    // if (std::strcmp(pCallbackData->pMessageIdName, "UNASSIGNED-BestPractices-DrawState-ClearCmdBeforeDraw") == 0) return VK_FALSE;

    int level = J2D_TRACE_OFF;
    if (messageSeverity == VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT) level = J2D_TRACE_VERBOSE;
    else if (messageSeverity == VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT) level = J2D_TRACE_INFO;
    else if (messageSeverity == VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT) level = J2D_TRACE_WARNING;
    else if (messageSeverity == VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) level = J2D_TRACE_ERROR;

    J2dRlsTraceLn(level, pCallbackData->pMessage);

    if (messageSeverity == VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) {
        // raise(SIGABRT); TODO uncomment when all validation errors are fixed.
    }
    return VK_FALSE;
}
#endif

static jboolean VK_InitGraphicsEnvironment(PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr) {

    geInstance->vkInstance = VK_NULL_HANDLE;

#define INSTANCE_PROC(NAME) GET_VK_PROC_RET_FALSE_IF_ERR(vkGetInstanceProcAddr, geInstance, geInstance->vkInstance, NAME)
    INSTANCE_PROC(vkEnumerateInstanceVersion);
    INSTANCE_PROC(vkEnumerateInstanceExtensionProperties);
    INSTANCE_PROC(vkEnumerateInstanceLayerProperties);
    INSTANCE_PROC(vkCreateInstance);

    uint32_t apiVersion = 0;

    if (geInstance->vkEnumerateInstanceVersion(&apiVersion) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: unable to enumerate Vulkan instance version");
        return JNI_FALSE;
    }

    J2dRlsTraceLn(J2D_TRACE_INFO, "Vulkan: Available (%d.%d.%d)",
                  VK_API_VERSION_MAJOR(apiVersion),
                  VK_API_VERSION_MINOR(apiVersion),
                  VK_API_VERSION_PATCH(apiVersion));

    if (apiVersion < REQUIRED_VULKAN_VERSION) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Unsupported version. Required at least (%d.%d.%d)",
                      VK_API_VERSION_MAJOR(REQUIRED_VULKAN_VERSION),
                      VK_API_VERSION_MINOR(REQUIRED_VULKAN_VERSION),
                      VK_API_VERSION_PATCH(REQUIRED_VULKAN_VERSION));
        return JNI_FALSE;
    }

    uint32_t extensionsCount;
    // Get the number of extensions and layers
    if (geInstance->vkEnumerateInstanceExtensionProperties(NULL, &extensionsCount, NULL) != VK_SUCCESS)
    {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: vkEnumerateInstanceExtensionProperties fails");
        return JNI_FALSE;
    }
    VkExtensionProperties extensions[extensionsCount];
    if (geInstance->vkEnumerateInstanceExtensionProperties(NULL, &extensionsCount, extensions) != VK_SUCCESS)
    {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: vkEnumerateInstanceExtensionProperties fails");
        return JNI_FALSE;
    }

    uint32_t layersCount;
    if (geInstance->vkEnumerateInstanceLayerProperties(&layersCount, NULL) != VK_SUCCESS)
    {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: vkEnumerateInstanceLayerProperties fails");
        return JNI_FALSE;
    }
    VkLayerProperties layers[layersCount];
    if (geInstance->vkEnumerateInstanceLayerProperties(&layersCount, layers) != VK_SUCCESS)
    {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: vkEnumerateInstanceLayerProperties fails");
        return JNI_FALSE;
    }

    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "    Supported instance layers:");
    for (uint32_t i = 0; i < layersCount; i++) {
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "        %s", (char *) layers[i].layerName);
    }

    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "    Supported instance extensions:");
    for (uint32_t i = 0; i < extensionsCount; i++) {
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "        %s", (char *) extensions[i].extensionName);
    }

    pchar* enabledLayers = ARRAY_ALLOC(pchar, MAX_ENABLED_LAYERS);
    pchar* enabledExtensions = ARRAY_ALLOC(pchar, MAX_ENABLED_EXTENSIONS);
    void *pNext = NULL;
#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
    ARRAY_PUSH_BACK(&enabledExtensions, VK_KHR_WAYLAND_SURFACE_EXTENSION_NAME);
#endif
    ARRAY_PUSH_BACK(&enabledExtensions, VK_KHR_SURFACE_EXTENSION_NAME);

    // Check required layers & extensions.
    for (uint32_t i = 0; i < ARRAY_SIZE(enabledExtensions); i++) {
        int notFound = 1;
        for (uint32_t j = 0; j < extensionsCount; j++) {
            if (strcmp((char *) extensions[j].extensionName, enabledExtensions[i]) == 0) {
                notFound = 0;
                break;
            }
        }
        if (notFound) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Required extension %s not found", enabledExtensions[i]);
            ARRAY_FREE(enabledLayers);
            ARRAY_FREE(enabledExtensions);
            return JNI_FALSE;
        }
    }

    // Configure validation
#ifdef DEBUG
    VkValidationFeatureEnableEXT enables[] = {
//            VK_VALIDATION_FEATURE_ENABLE_GPU_ASSISTED_EXT,
//            VK_VALIDATION_FEATURE_ENABLE_GPU_ASSISTED_RESERVE_BINDING_SLOT_EXT,
            VK_VALIDATION_FEATURE_ENABLE_BEST_PRACTICES_EXT,
//            VK_VALIDATION_FEATURE_ENABLE_DEBUG_PRINTF_EXT,
            VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT
    };

    VkValidationFeaturesEXT features = {};
    features.sType = VK_STRUCTURE_TYPE_VALIDATION_FEATURES_EXT;
    features.enabledValidationFeatureCount = COUNT_OF(enables);
    features.pEnabledValidationFeatures = enables;

    // Includes the validation features into the instance creation process

    int foundDebugLayer = 0;
    for (uint32_t i = 0; i < layersCount; i++) {
        if (strcmp((char *) layers[i].layerName, VALIDATION_LAYER_NAME) == 0) {
            foundDebugLayer = 1;
            break;
        }
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "        %s", (char *) layers[i].layerName);
    }
    int foundDebugExt = 0;
    for (uint32_t i = 0; i < extensionsCount; i++) {
        if (strcmp((char *) extensions[i].extensionName, VK_EXT_DEBUG_UTILS_EXTENSION_NAME) == 0) {
            foundDebugExt = 1;
            break;
        }
    }

    if (foundDebugLayer && foundDebugExt) {
        ARRAY_PUSH_BACK(&enabledLayers, VALIDATION_LAYER_NAME);
        ARRAY_PUSH_BACK(&enabledExtensions, VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
        pNext = &features;
    } else {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "Vulkan: %s and %s are not supported",
                      VALIDATION_LAYER_NAME, VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
    }
#endif
    VkApplicationInfo applicationInfo = {
            .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
            .pNext = NULL,
            .pApplicationName = "OpenJDK",
            .applicationVersion = 0,
            .pEngineName = "OpenJDK",
            .engineVersion = 0,
            .apiVersion = REQUIRED_VULKAN_VERSION
    };

    VkInstanceCreateInfo instanceCreateInfo = {
            .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
            .pNext = pNext,
            .flags = 0,
            .pApplicationInfo = &applicationInfo,
            .enabledLayerCount = ARRAY_SIZE(enabledLayers),
            .ppEnabledLayerNames = (const char *const *) enabledLayers,
            .enabledExtensionCount = ARRAY_SIZE(enabledExtensions),
            .ppEnabledExtensionNames = (const char *const *) enabledExtensions
    };

    if (geInstance->vkCreateInstance(&instanceCreateInfo, NULL, &geInstance->vkInstance) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Failed to create Vulkan instance");
        ARRAY_FREE(enabledLayers);
        ARRAY_FREE(enabledExtensions);
        return JNI_FALSE;
    } else {
        J2dRlsTraceLn(J2D_TRACE_INFO, "Vulkan: Instance Created");
    }
    ARRAY_FREE(enabledLayers);
    ARRAY_FREE(enabledExtensions);

#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
    INSTANCE_PROC(vkGetPhysicalDeviceWaylandPresentationSupportKHR);
    INSTANCE_PROC(vkCreateWaylandSurfaceKHR);
#endif
    INSTANCE_PROC(vkDestroyInstance);
    INSTANCE_PROC(vkEnumeratePhysicalDevices);
    INSTANCE_PROC(vkGetPhysicalDeviceMemoryProperties);
    INSTANCE_PROC(vkGetPhysicalDeviceFeatures2);
    INSTANCE_PROC(vkGetPhysicalDeviceProperties2);
    INSTANCE_PROC(vkGetPhysicalDeviceQueueFamilyProperties);
    INSTANCE_PROC(vkGetPhysicalDeviceSurfaceCapabilitiesKHR);
    INSTANCE_PROC(vkGetPhysicalDeviceSurfaceFormatsKHR);
    INSTANCE_PROC(vkGetPhysicalDeviceSurfacePresentModesKHR);
    INSTANCE_PROC(vkEnumerateDeviceLayerProperties);
    INSTANCE_PROC(vkEnumerateDeviceExtensionProperties);
    INSTANCE_PROC(vkCreateDevice);
    INSTANCE_PROC(vkGetDeviceProcAddr);

    // Create debug messenger
#if defined(DEBUG)
    INSTANCE_PROC(vkCreateDebugUtilsMessengerEXT);
    INSTANCE_PROC(vkDestroyDebugUtilsMessengerEXT);
    if (pNext) {
        VkDebugUtilsMessengerCreateInfoEXT debugUtilsMessengerCreateInfo = {
                .flags =           0,
                .messageSeverity = VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT |
                                   VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
                                   VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT |
                                   VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT,
                .messageType =     VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                                   VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                                   VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT,
                .pfnUserCallback = &debugCallback
        };
        if (geInstance->vkCreateDebugUtilsMessengerEXT(geInstance->vkInstance, &debugUtilsMessengerCreateInfo,
                                                       NULL, &geInstance->debugMessenger) != VK_SUCCESS) {
            J2dRlsTraceLn(J2D_TRACE_WARNING, "Vulkan: Failed to create debug messenger");
        }
    }

#endif

    return JNI_TRUE;
}

static jboolean VK_FindDevices() {
    uint32_t physicalDevicesCount;
    if (geInstance->vkEnumeratePhysicalDevices(geInstance->vkInstance,
                                               &physicalDevicesCount,
                                               NULL) != VK_SUCCESS)
    {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: vkEnumeratePhysicalDevices fails");
        return JNI_FALSE;
    }

    if (physicalDevicesCount == 0) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Failed to find GPUs with Vulkan support");
        return JNI_FALSE;
    } else {
        J2dRlsTraceLn(J2D_TRACE_INFO, "Vulkan: Found %d physical devices:", physicalDevicesCount);
    }

    geInstance->physicalDevices = ARRAY_ALLOC(VkPhysicalDevice, physicalDevicesCount);
    if (geInstance->physicalDevices == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Cannot allocate VkPhysicalDevice");
        return JNI_FALSE;
    }

    if (geInstance->vkEnumeratePhysicalDevices(
            geInstance->vkInstance,
            &physicalDevicesCount,
            geInstance->physicalDevices) != VK_SUCCESS)
    {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: vkEnumeratePhysicalDevices fails");
        return JNI_FALSE;
    }

    geInstance->devices = ARRAY_ALLOC(VKLogicalDevice, physicalDevicesCount);
    if (geInstance->devices == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Cannot allocate VKLogicalDevice");
        return JNI_FALSE;
    }

    for (uint32_t i = 0; i < physicalDevicesCount; i++) {
        VkPhysicalDeviceVulkan12Features device12Features = {
                .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES,
                .pNext = NULL
        };

        VkPhysicalDeviceFeatures2 deviceFeatures2 = {
                .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
                .pNext = &device12Features
        };

        geInstance->vkGetPhysicalDeviceFeatures2(geInstance->physicalDevices[i], &deviceFeatures2);

        VkPhysicalDeviceProperties2 deviceProperties2 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
        geInstance->vkGetPhysicalDeviceProperties2(geInstance->physicalDevices[i], &deviceProperties2);
        J2dRlsTrace(J2D_TRACE_INFO, "\t- %s (%d.%d.%d, %s) ",
                    (const char *) deviceProperties2.properties.deviceName,
                    VK_API_VERSION_MAJOR(deviceProperties2.properties.apiVersion),
                    VK_API_VERSION_MINOR(deviceProperties2.properties.apiVersion),
                    VK_API_VERSION_PATCH(deviceProperties2.properties.apiVersion),
                    physicalDeviceTypeString(deviceProperties2.properties.deviceType));

        if (!deviceFeatures2.features.logicOp) {
            J2dRlsTraceLn(J2D_TRACE_INFO, " - hasLogicOp not supported, skipped");
            continue;
        }

        if (!device12Features.timelineSemaphore) {
            J2dRlsTraceLn(J2D_TRACE_INFO, " - hasTimelineSemaphore not supported, skipped");
            continue;
        }
        J2dRlsTraceLn(J2D_TRACE_INFO, "");

        uint32_t queueFamilyCount = 0;
        geInstance->vkGetPhysicalDeviceQueueFamilyProperties(
                geInstance->physicalDevices[i], &queueFamilyCount, NULL);

        VkQueueFamilyProperties *queueFamilies = (VkQueueFamilyProperties*)calloc(queueFamilyCount,
                                                                                  sizeof(VkQueueFamilyProperties));
        if (queueFamilies == NULL) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Cannot allocate VkQueueFamilyProperties");
            return JNI_FALSE;
        }

        geInstance->vkGetPhysicalDeviceQueueFamilyProperties(
                geInstance->physicalDevices[i], &queueFamilyCount, queueFamilies);
        int64_t queueFamily = -1;
        for (uint32_t j = 0; j < queueFamilyCount; j++) {
#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
            VkBool32 presentationSupported =
                geInstance->vkGetPhysicalDeviceWaylandPresentationSupportKHR(
                    geInstance->physicalDevices[i], j, geInstance->waylandDisplay);
#endif
            char logFlags[5] = {
                    queueFamilies[j].queueFlags & VK_QUEUE_GRAPHICS_BIT ? 'G' : '-',
                    queueFamilies[j].queueFlags & VK_QUEUE_COMPUTE_BIT ? 'C' : '-',
                    queueFamilies[j].queueFlags & VK_QUEUE_TRANSFER_BIT ? 'T' : '-',
                    queueFamilies[j].queueFlags & VK_QUEUE_SPARSE_BINDING_BIT ? 'S' : '-',
#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
                    presentationSupported ? 'P' : '-'
#else
                    '-'
#endif
            };
            J2dRlsTraceLn(J2D_TRACE_INFO, "    %d queues in family (%.*s)", queueFamilies[j].queueCount, 5,
                          logFlags);

            // TODO use compute workloads? Separate transfer-only DMA queue?
            if (queueFamily == -1 && (queueFamilies[j].queueFlags & VK_QUEUE_GRAPHICS_BIT)
                #if defined(VK_USE_PLATFORM_WAYLAND_KHR)
                && presentationSupported
#endif
                    ) {
                queueFamily = j;
            }
        }
        free(queueFamilies);
        if (queueFamily == -1) {
            J2dRlsTraceLn(J2D_TRACE_INFO, "    --------------------- Suitable queue not found, skipped");
            continue;
        }

        uint32_t layerCount;
        geInstance->vkEnumerateDeviceLayerProperties(geInstance->physicalDevices[i], &layerCount, NULL);
        VkLayerProperties *layers = (VkLayerProperties *) calloc(layerCount, sizeof(VkLayerProperties));
        if (layers == NULL) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Cannot allocate VkLayerProperties");
            return JNI_FALSE;
        }

        geInstance->vkEnumerateDeviceLayerProperties(geInstance->physicalDevices[i], &layerCount, layers);
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "    Supported device layers:");
        for (uint32_t j = 0; j < layerCount; j++) {
            J2dRlsTraceLn(J2D_TRACE_VERBOSE, "        %s", (char *) layers[j].layerName);
        }

        uint32_t extensionCount;
        geInstance->vkEnumerateDeviceExtensionProperties(geInstance->physicalDevices[i], NULL, &extensionCount, NULL);
        VkExtensionProperties *extensions = (VkExtensionProperties *) calloc(
                extensionCount, sizeof(VkExtensionProperties));
        if (extensions == NULL) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Cannot allocate VkExtensionProperties");
            return JNI_FALSE;
        }

        geInstance->vkEnumerateDeviceExtensionProperties(
                geInstance->physicalDevices[i], NULL, &extensionCount, extensions);
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "    Supported device extensions:");
        VkBool32 hasSwapChain = VK_FALSE;
        for (uint32_t j = 0; j < extensionCount; j++) {
            J2dRlsTraceLn(J2D_TRACE_VERBOSE, "        %s", (char *) extensions[j].extensionName);
            hasSwapChain = hasSwapChain ||
                           strcmp(VK_KHR_SWAPCHAIN_EXTENSION_NAME, extensions[j].extensionName) == 0;
        }
        free(extensions);
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "    Found:");
        if (hasSwapChain) {
            J2dRlsTraceLn(J2D_TRACE_VERBOSE, "    VK_KHR_SWAPCHAIN_EXTENSION_NAME");
        }

        if (!hasSwapChain) {
            J2dRlsTraceLn(J2D_TRACE_INFO,
                        "    --------------------- Required VK_KHR_SWAPCHAIN_EXTENSION_NAME not found, skipped");
            continue;
        }

        pchar* deviceEnabledLayers = ARRAY_ALLOC(pchar, MAX_ENABLED_LAYERS);
        if (deviceEnabledLayers == NULL) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Cannot allocate deviceEnabledLayers array");
            return JNI_FALSE;
        }

        pchar* deviceEnabledExtensions = ARRAY_ALLOC(pchar, MAX_ENABLED_EXTENSIONS);
        if (deviceEnabledExtensions == NULL) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Cannot allocate deviceEnabledExtensions array");
            return JNI_FALSE;
        }

        ARRAY_PUSH_BACK(&deviceEnabledExtensions, VK_KHR_SWAPCHAIN_EXTENSION_NAME);

        // Validation layer
#ifdef DEBUG
        int validationLayerNotSupported = 1;
            for (uint32_t j = 0; j < layerCount; j++) {
                if (strcmp(VALIDATION_LAYER_NAME, layers[j].layerName) == 0) {
                    validationLayerNotSupported = 0;
                    ARRAY_PUSH_BACK(&deviceEnabledLayers, VALIDATION_LAYER_NAME);
                    break;
                }
            }
            if (validationLayerNotSupported) {
                J2dRlsTraceLn(J2D_TRACE_INFO, "    %s device layer is not supported", VALIDATION_LAYER_NAME);
            }
#endif
        free(layers);
        char* deviceName = strdup(deviceProperties2.properties.deviceName);
        if (deviceName == NULL) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Cannot duplicate deviceName");
            return JNI_FALSE;
        }

        ARRAY_PUSH_BACK(&geInstance->devices,
                ((VKLogicalDevice) {
                .name = deviceName,
                .device = VK_NULL_HANDLE,
                .physicalDevice = geInstance->physicalDevices[i],
                .queueFamily = queueFamily,
                .enabledLayers = deviceEnabledLayers,
                .enabledExtensions = deviceEnabledExtensions,
        }));
    }
    if (ARRAY_SIZE(geInstance->devices) == 0) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "No compatible device found");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}
static VkRenderPassCreateInfo* VK_GetGenericRenderPassInfo() {
    static VkAttachmentDescription colorAttachment = {
            .format = VK_FORMAT_B8G8R8A8_UNORM, //TODO: swapChain colorFormat
            .samples = VK_SAMPLE_COUNT_1_BIT,
            .loadOp = VK_ATTACHMENT_LOAD_OP_LOAD,
            .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
            .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
            .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
            .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
            .finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR
    };

    static VkAttachmentReference colorReference = {
            .attachment = 0,
            .layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
    };

    static VkSubpassDescription subpassDescription = {
            .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
            .colorAttachmentCount = 1,
            .pColorAttachments = &colorReference
    };

    // Subpass dependencies for layout transitions
    static VkSubpassDependency dependency = {
            .srcSubpass = VK_SUBPASS_EXTERNAL,
            .dstSubpass = 0,
            .srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
            .dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
            .srcAccessMask = 0,
            .dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
    };

    static VkRenderPassCreateInfo renderPassInfo = {
            .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
            .attachmentCount = 1,
            .pAttachments = &colorAttachment,
            .subpassCount = 1,
            .pSubpasses = &subpassDescription,
            .dependencyCount = 1,
            .pDependencies = &dependency
    };
    return &renderPassInfo;
}

static jboolean VK_InitLogicalDevice(VKLogicalDevice* logicalDevice) {
    if (logicalDevice->device != VK_NULL_HANDLE) {
        return JNI_TRUE;
    }
    if (geInstance == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: VKGraphicsEnvironment is not initialized");
        return JNI_FALSE;
    }
    if (verbose) {
        for (uint32_t i = 0; i < ARRAY_SIZE(geInstance->devices); i++) {
            fprintf(stderr, " %c%d: %s\n", &geInstance->devices[i] == logicalDevice ? '*' : ' ',
                    i, geInstance->devices[i].name);
        }
        fprintf(stderr, "\n");
    }

    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueCreateInfo = {
            .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
            .queueFamilyIndex = logicalDevice->queueFamily,  // obtained separately
            .queueCount = 1,
            .pQueuePriorities = &queuePriority
    };

    VkPhysicalDeviceFeatures features10 = { .logicOp = VK_TRUE };

    VkDeviceCreateInfo createInfo = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .pNext = NULL,
        .flags = 0,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &queueCreateInfo,
        .enabledLayerCount = ARRAY_SIZE(logicalDevice->enabledLayers),
        .ppEnabledLayerNames = (const char *const *) logicalDevice->enabledLayers,
        .enabledExtensionCount = ARRAY_SIZE(logicalDevice->enabledExtensions),
        .ppEnabledExtensionNames = (const char *const *) logicalDevice->enabledExtensions,
        .pEnabledFeatures = &features10
    };

    if (geInstance->vkCreateDevice(logicalDevice->physicalDevice, &createInfo, NULL, &logicalDevice->device) != VK_SUCCESS)
    {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Cannot create device:\n    %s", logicalDevice->name);
        vulkanLibClose();
        return JNI_FALSE;
    }
    VkDevice device = logicalDevice->device;
    J2dRlsTraceLn(J2D_TRACE_INFO, "Logical device (%s) created", logicalDevice->name);

#define DEVICE_PROC(NAME) GET_VK_PROC_RET_FALSE_IF_ERR(geInstance->vkGetDeviceProcAddr, logicalDevice, device, NAME)
    DEVICE_PROC(vkDestroyDevice);
    DEVICE_PROC(vkCreateShaderModule);
    DEVICE_PROC(vkCreatePipelineLayout);
    DEVICE_PROC(vkCreateGraphicsPipelines);
    DEVICE_PROC(vkDestroyShaderModule);
    DEVICE_PROC(vkCreateSwapchainKHR);
    DEVICE_PROC(vkGetSwapchainImagesKHR);
    DEVICE_PROC(vkCreateImageView);
    DEVICE_PROC(vkCreateFramebuffer);
    DEVICE_PROC(vkCreateCommandPool);
    DEVICE_PROC(vkAllocateCommandBuffers);
    DEVICE_PROC(vkCreateSemaphore);
    DEVICE_PROC(vkCreateFence);
    DEVICE_PROC(vkGetDeviceQueue);
    DEVICE_PROC(vkWaitForFences);
    DEVICE_PROC(vkResetFences);
    DEVICE_PROC(vkAcquireNextImageKHR);
    DEVICE_PROC(vkResetCommandBuffer);
    DEVICE_PROC(vkQueueSubmit);
    DEVICE_PROC(vkQueuePresentKHR);
    DEVICE_PROC(vkBeginCommandBuffer);
    DEVICE_PROC(vkCmdBeginRenderPass);
    DEVICE_PROC(vkCmdBindPipeline);
    DEVICE_PROC(vkCmdSetViewport);
    DEVICE_PROC(vkCmdSetScissor);
    DEVICE_PROC(vkCmdDraw);
    DEVICE_PROC(vkCmdEndRenderPass);
    DEVICE_PROC(vkEndCommandBuffer);
    DEVICE_PROC(vkCreateImage);
    DEVICE_PROC(vkCreateSampler);
    DEVICE_PROC(vkAllocateMemory);
    DEVICE_PROC(vkBindImageMemory);
    DEVICE_PROC(vkCreateDescriptorSetLayout);
    DEVICE_PROC(vkUpdateDescriptorSets);
    DEVICE_PROC(vkCreateDescriptorPool);
    DEVICE_PROC(vkAllocateDescriptorSets);
    DEVICE_PROC(vkCmdBindDescriptorSets);
    DEVICE_PROC(vkGetImageMemoryRequirements);
    DEVICE_PROC(vkCreateBuffer);
    DEVICE_PROC(vkGetBufferMemoryRequirements);
    DEVICE_PROC(vkBindBufferMemory);
    DEVICE_PROC(vkMapMemory);
    DEVICE_PROC(vkUnmapMemory);
    DEVICE_PROC(vkCmdBindVertexBuffers);
    DEVICE_PROC(vkCreateRenderPass);
    DEVICE_PROC(vkDestroyBuffer);
    DEVICE_PROC(vkFreeMemory);
    DEVICE_PROC(vkDestroyImageView);
    DEVICE_PROC(vkDestroyImage);
    DEVICE_PROC(vkDestroyFramebuffer);
    DEVICE_PROC(vkFlushMappedMemoryRanges);
    DEVICE_PROC(vkCmdPushConstants);

    // Create command pool
    VkCommandPoolCreateInfo poolInfo = {
            .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
            .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
            .queueFamilyIndex = logicalDevice->queueFamily
    };
    if (logicalDevice->vkCreateCommandPool(device, &poolInfo, NULL, &logicalDevice->commandPool) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_INFO, "failed to create command pool!");
        return JNI_FALSE;
    }

    // Create command buffer
    VkCommandBufferAllocateInfo allocInfo = {
            .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
            .commandPool = logicalDevice->commandPool,
            .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
            .commandBufferCount = 1
    };

    if (logicalDevice->vkAllocateCommandBuffers(device, &allocInfo, &logicalDevice->commandBuffer) != VK_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_INFO, "failed to allocate command buffers!");
        return JNI_FALSE;
    }

    // Create semaphores
    VkSemaphoreCreateInfo semaphoreInfo = {
            .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO
    };

    VkFenceCreateInfo fenceInfo = {
            .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO,
            .flags = VK_FENCE_CREATE_SIGNALED_BIT
    };

    if (logicalDevice->vkCreateSemaphore(device, &semaphoreInfo, NULL, &logicalDevice->imageAvailableSemaphore) != VK_SUCCESS ||
        logicalDevice->vkCreateSemaphore(device, &semaphoreInfo, NULL, &logicalDevice->renderFinishedSemaphore) != VK_SUCCESS ||
        logicalDevice->vkCreateFence(device, &fenceInfo, NULL, &logicalDevice->inFlightFence) != VK_SUCCESS)
    {
        J2dRlsTraceLn(J2D_TRACE_INFO, "failed to create semaphores!");
        return JNI_FALSE;
    }

    logicalDevice->vkGetDeviceQueue(device, logicalDevice->queueFamily, 0, &logicalDevice->queue);
    if (logicalDevice->queue == NULL) {
        J2dRlsTraceLn(J2D_TRACE_INFO, "failed to get device queue!");
        return JNI_FALSE;
    }

    VKTxVertex* vertices = ARRAY_ALLOC(VKTxVertex, 4);
    ARRAY_PUSH_BACK(&vertices, ((VKTxVertex){-1.0f, -1.0f, 0.0f, 0.0f}));
    ARRAY_PUSH_BACK(&vertices, ((VKTxVertex){1.0f, -1.0f, 1.0f, 0.0f}));
    ARRAY_PUSH_BACK(&vertices, ((VKTxVertex){-1.0f, 1.0f, 0.0f, 1.0f}));
    ARRAY_PUSH_BACK(&vertices, ((VKTxVertex){1.0f, 1.0f, 1.0f, 1.0f}));
    logicalDevice->blitVertexBuffer = ARRAY_TO_VERTEX_BUF(logicalDevice, vertices);
    if (!logicalDevice->blitVertexBuffer) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Cannot create vertex buffer");
        return JNI_FALSE;
    }
    ARRAY_FREE(vertices);

    logicalDevice->texturePool = VKTexturePool_initWithDevice(logicalDevice);
    if (!logicalDevice->texturePool) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Cannot create texture pool");
        return JNI_FALSE;
    }

    geInstance->currentDevice = logicalDevice;

    return JNI_TRUE;
}

VKGraphicsEnvironment* VKGE_graphics_environment() {
    return geInstance;
}

/*
 * Class:     sun_java2d_vulkan_VKInstance
 * Method:    init
 * Signature: (JZI)Z
 */
JNIEXPORT jboolean JNICALL
Java_sun_java2d_vulkan_VKInstance_initNative(JNIEnv *env, jclass wlge, jlong nativePtr, jboolean verb, jint requestedDevice) {
    verbose = verb;
    PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr = vulkanLibOpen();
    if (vkGetInstanceProcAddr == NULL) {
        return JNI_FALSE;
    }
    geInstance = (VKGraphicsEnvironment*)malloc(sizeof(VKGraphicsEnvironment));
    if (geInstance == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Cannot allocate VKGraphicsEnvironment");
        vulkanLibClose();
        return JNI_FALSE;
    }
    *geInstance = (VKGraphicsEnvironment) {};
#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
    geInstance->waylandDisplay = (struct wl_display*) jlong_to_ptr(nativePtr);
#endif
    if (!VK_InitGraphicsEnvironment(vkGetInstanceProcAddr)) {
        vulkanLibClose();
        return JNI_FALSE;
    }
    if (!VK_FindDevices()) {
        vulkanLibClose();
        return JNI_FALSE;
    }
    if (requestedDevice < 0 || (uint32_t)requestedDevice >= ARRAY_SIZE(geInstance->devices)) {
        requestedDevice = 0;
    }
    if (!VK_InitLogicalDevice(&geInstance->devices[requestedDevice])) {
        vulkanLibClose();
        return JNI_FALSE;
    }

    if (geInstance->currentDevice->vkCreateRenderPass(
            geInstance->currentDevice->device, VK_GetGenericRenderPassInfo(),
            NULL, &geInstance->currentDevice->renderPass) != VK_SUCCESS)
    {
        J2dRlsTrace(J2D_TRACE_INFO, "Cannot create render pass for device");
        return JNI_FALSE;
    }
    
    if (!VK_CreateLogicalDeviceRenderers(geInstance->currentDevice)) {
        vulkanLibClose();
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL JNI_OnUnload(__attribute__((unused)) JavaVM *vm, __attribute__((unused)) void *reserved) {
    vulkanLibClose();
}
