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

#include <malloc.h>
#include <Trace.h>
#include "jvm_md.h"
#include "VKBase.h"
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <string.h>

#define VULKAN_DLL JNI_LIB_NAME("vulkan")
static const uint32_t REQUIRED_VULKAN_VERSION = VK_MAKE_API_VERSION(0, 1, 2, 0);


#define MAX_ENABLED_LAYERS 5
#define MAX_ENABLED_EXTENSIONS 5
#define VALIDATION_LAYER_NAME "VK_LAYER_KHRONOS_validation"

#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
extern struct wl_display *wl_display;
#endif

static jboolean verbose;
static jint requestedDeviceNumber = -1;
static VKGraphicsEnvironment* geInstance = NULL;
static void* pVulkanLib = NULL;

static void vulkanLibClose() {
    if (pVulkanLib != NULL) {
        if (geInstance != NULL) {
            if (geInstance->layers != NULL) {
                free(geInstance->layers);
            }
            if (geInstance->extensions != NULL) {
                free(geInstance->extensions);
            }
            if (geInstance->physicalDevices != NULL) {
                free(geInstance->physicalDevices);
            }
            if (geInstance->devices != NULL) {
                PFN_vkDestroyDevice vkDestroyDevice = vulkanLibProc(geInstance->vkInstance, "vkDestroyDevice");

                for (uint32_t i = 0; i < geInstance->devicesCount; i++) {
                    if (geInstance->devices[i].enabledExtensions != NULL) {
                        free(geInstance->devices[i].enabledExtensions);
                    }
                    if (geInstance->devices[i].enabledLayers != NULL) {
                        free(geInstance->devices[i].enabledLayers);
                    }
                    if (geInstance->devices[i].name != NULL) {
                        free(geInstance->devices[i].name);
                    }
                    if (vkDestroyDevice != NULL && geInstance->devices[i].device != NULL) {
                        vkDestroyDevice(geInstance->devices[i].device, NULL);
                    }
                }
                free(geInstance->devices);
            }

            if (geInstance->vkInstance != NULL) {
                PFN_vkDestroyInstance vkDestroyInstance = vulkanLibProc(geInstance->vkInstance, "vkDestroyInstance");
                if (vkDestroyInstance != NULL) {
                    vkDestroyInstance(geInstance->vkInstance, NULL);
                }
            }
            free(geInstance);
            geInstance = NULL;
        }
        dlclose(pVulkanLib);
        pVulkanLib = NULL;
    }
}

void* vulkanLibProc(VkInstance vkInstance, char* procName) {
    static PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr = NULL;
    if (pVulkanLib == NULL) {
        pVulkanLib = dlopen(VULKAN_DLL, RTLD_NOW);
        if (pVulkanLib == NULL) {
            J2dRlsTrace1(J2D_TRACE_ERROR, "Failed to load %s\n", VULKAN_DLL)
            return NULL;
        }
        if (!vkGetInstanceProcAddr) {
            vkGetInstanceProcAddr = (PFN_vkGetInstanceProcAddr) dlsym(pVulkanLib, "vkGetInstanceProcAddr");
            if (vkGetInstanceProcAddr == NULL) {
                J2dRlsTrace1(J2D_TRACE_ERROR,
                            "Failed to get proc address of vkGetInstanceProcAddr from %s\n", VULKAN_DLL)
                return NULL;
            }
        }
    }

    void* vkProc = vkGetInstanceProcAddr(vkInstance, procName);

    if (vkProc == NULL) {
        J2dRlsTrace1(J2D_TRACE_ERROR, "%s is not supported\n", procName)
        return NULL;
    }
    return vkProc;
}


jboolean VK_Init(jboolean verb, jint requestedDevice) {
    verbose = verb;
    requestedDeviceNumber = requestedDevice;
    return VKGE_graphics_environment() != NULL;
}

const char* physicalDeviceTypeString(VkPhysicalDeviceType type)
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

VKGraphicsEnvironment* VKGE_graphics_environment() {
    if (geInstance == NULL) {

        PFN_vkEnumerateInstanceVersion vkEnumerateInstanceVersion =
                (PFN_vkEnumerateInstanceVersion) vulkanLibProc(
                        VK_NULL_HANDLE, "vkEnumerateInstanceVersion");
        PFN_vkEnumerateInstanceExtensionProperties vkEnumerateInstanceExtensionProperties =
                (PFN_vkEnumerateInstanceExtensionProperties) vulkanLibProc(
                        VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties");
        PFN_vkEnumerateInstanceLayerProperties vkEnumerateInstanceLayerProperties =
                (PFN_vkEnumerateInstanceLayerProperties) vulkanLibProc(
                        VK_NULL_HANDLE,"vkEnumerateInstanceLayerProperties");
        PFN_vkCreateInstance vkCreateInstance =
                (PFN_vkCreateInstance) vulkanLibProc(
                        VK_NULL_HANDLE, "vkCreateInstance");

        if (vkEnumerateInstanceVersion == NULL ||
            vkEnumerateInstanceExtensionProperties == NULL ||
            vkEnumerateInstanceLayerProperties == NULL ||
            vkCreateInstance == NULL)
        {
            J2dRlsTrace(J2D_TRACE_ERROR, "Required api is not supported\n")
            vulkanLibClose();
            return NULL;
        }

        uint32_t apiVersion = 0;

        if (vkEnumerateInstanceVersion(&apiVersion) != VK_SUCCESS) {
            J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: unable to enumerate Vulkan instance version\n")
            vulkanLibClose();
            return NULL;
        }

        J2dRlsTrace3(J2D_TRACE_INFO, "Vulkan: Available (%d.%d.%d)\n",
                     VK_API_VERSION_MAJOR(apiVersion),
                     VK_API_VERSION_MINOR(apiVersion),
                     VK_API_VERSION_PATCH(apiVersion))

        if (apiVersion < REQUIRED_VULKAN_VERSION) {
            J2dRlsTrace3(J2D_TRACE_ERROR, "Vulkan: Unsupported version. Required at least (%d.%d.%d)\n",
                         VK_API_VERSION_MAJOR(REQUIRED_VULKAN_VERSION),
                         VK_API_VERSION_MINOR(REQUIRED_VULKAN_VERSION),
                         VK_API_VERSION_PATCH(REQUIRED_VULKAN_VERSION))
            vulkanLibClose();
            return NULL;
        }

        geInstance = (VKGraphicsEnvironment*)malloc(sizeof(VKGraphicsEnvironment));
        *geInstance = (VKGraphicsEnvironment) {
            NULL,
            NULL, 0,
            NULL, 0, 0,
            NULL, 0,
            NULL,0
        };

        // Get the number of extensions and layers
        vkEnumerateInstanceExtensionProperties(NULL, &geInstance->extensionsCount, NULL);
        geInstance->extensions = (VkExtensionProperties*) malloc(sizeof(VkExtensionProperties)*geInstance->extensionsCount);
        vkEnumerateInstanceExtensionProperties(NULL, &geInstance->extensionsCount, geInstance->extensions);

        vkEnumerateInstanceLayerProperties(&geInstance->layersCount, NULL);
        geInstance->layers = (VkLayerProperties*) malloc(sizeof(VkLayerProperties)*geInstance->layersCount);
        vkEnumerateInstanceLayerProperties(&geInstance->layersCount, geInstance->layers);

        J2dRlsTrace(J2D_TRACE_VERBOSE, "    Supported instance layers:\n")
        for (uint32_t i = 0; i < geInstance->layersCount; i++) {
            J2dRlsTrace1(J2D_TRACE_VERBOSE, "        %s\n", (char *) geInstance->layers[i].layerName)
        }

        J2dRlsTrace(J2D_TRACE_VERBOSE, "    Supported instance extensions:\n")
        for (uint32_t i = 0; i < geInstance->extensionsCount; i++) {
            J2dRlsTrace1(J2D_TRACE_VERBOSE, "        %s\n", (char *) geInstance->extensions[i].extensionName)
        }

        char* enabledLayers[MAX_ENABLED_LAYERS];
        uint32_t enabledLayersCount = 0;
        char* enabledExtensions[MAX_ENABLED_EXTENSIONS];
        uint32_t enabledExtensionsCount = 0;
        void *pNext = NULL;
#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
        enabledExtensions[enabledExtensionsCount++] = VK_KHR_WAYLAND_SURFACE_EXTENSION_NAME;
#endif
        enabledExtensions[enabledExtensionsCount++] = VK_KHR_SURFACE_EXTENSION_NAME;

        // Check required layers & extensions.
        for (uint32_t i = 0; i < enabledExtensionsCount; i++) {
            int notFound = 1;
            for (uint32_t j = 0; j < geInstance->extensionsCount; j++) {
                if (strcmp((char *) geInstance->extensions[j].extensionName, enabledExtensions[i]) == 0) {
                    notFound = 0;
                    break;
                }
            }
            if (notFound) {
                J2dRlsTrace1(J2D_TRACE_ERROR, "Vulkan: Required extension %s not found\n", enabledExtensions[i])
                vulkanLibClose();
                return NULL;
            }
        }

        // Configure validation
#ifdef DEBUG
        VkValidationFeatureEnableEXT enables[] = {
//                VK_VALIDATION_FEATURE_ENABLE_GPU_ASSISTED_EXT,
//                VK_VALIDATION_FEATURE_ENABLE_GPU_ASSISTED_RESERVE_BINDING_SLOT_EXT,
                VK_VALIDATION_FEATURE_ENABLE_BEST_PRACTICES_EXT,
//                VK_VALIDATION_FEATURE_ENABLE_DEBUG_PRINTF_EXT,
                VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT
        };

        VkValidationFeaturesEXT features = {};
        features.sType = VK_STRUCTURE_TYPE_VALIDATION_FEATURES_EXT;
        features.enabledValidationFeatureCount = 2;
        features.pEnabledValidationFeatures = enables;

       // Includes the validation features into the instance creation process

        int foundDebugLayer = 0;
        for (uint32_t i = 0; i < geInstance->layersCount; i++) {
            if (strcmp((char *) geInstance->layers[i].layerName, VALIDATION_LAYER_NAME) == 0) {
                foundDebugLayer = 1;
                break;
            }
            J2dRlsTrace1(J2D_TRACE_VERBOSE, "        %s\n", (char *) geInstance->layers[i].layerName)
        }
        int foundDebugExt = 0;
        for (uint32_t i = 0; i < geInstance->extensionsCount; i++) {
            if (strcmp((char *) geInstance->extensions[i].extensionName, VK_EXT_DEBUG_UTILS_EXTENSION_NAME) == 0) {
                foundDebugExt = 1;
                break;
            }
        }

        if (foundDebugLayer && foundDebugExt) {
            enabledLayers[enabledLayersCount++] = VALIDATION_LAYER_NAME;
            enabledExtensions[enabledExtensionsCount++] = VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
            pNext = &features;
        } else {
            J2dRlsTrace2(J2D_TRACE_WARNING, "Vulkan: %s and %s are not supported\n",
                       VALIDATION_LAYER_NAME, VK_EXT_DEBUG_UTILS_EXTENSION_NAME)
        }
#endif
        VkApplicationInfo applicationInfo = {
                VK_STRUCTURE_TYPE_APPLICATION_INFO, NULL,
                /*pApplicationName*/   "OpenJDK",
                /*applicationVersion*/ 0,
                /*pEngineName*/        "OpenJDK",
                /*engineVersion*/      0,
                /*apiVersion*/         REQUIRED_VULKAN_VERSION
        };

        VkInstanceCreateInfo instanceCreateInfo = {
                VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                /*pNext*/                   pNext,
                /*flags*/                   0,
                /*pApplicationInfo*/        &applicationInfo,
                /*ppEnabledLayerNames*/     enabledLayersCount, (const char *const *) enabledLayers,
                /*ppEnabledExtensionNames*/ enabledExtensionsCount, (const char *const *) enabledExtensions,
        };

        if (vkCreateInstance(&instanceCreateInfo, NULL, &geInstance->vkInstance) != VK_SUCCESS) {
            J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: Failed to create Vulkan instance\n")
            vulkanLibClose();
            return NULL;
        } else {
            J2dRlsTrace(J2D_TRACE_INFO, "Vulkan: Instance Created\n")
        }
        PFN_vkEnumeratePhysicalDevices vkEnumeratePhysicalDevices =
                (PFN_vkEnumeratePhysicalDevices) vulkanLibProc(
                        geInstance->vkInstance, "vkEnumeratePhysicalDevices");
        if (vkEnumeratePhysicalDevices == NULL)
        {
            J2dRlsTrace(J2D_TRACE_ERROR, "vkEnumeratePhysicalDevices is not supported\n")

            vulkanLibClose();
            return NULL;
        }


        vkEnumeratePhysicalDevices(geInstance->vkInstance, &geInstance->physicalDevicesCount, NULL);

        if (geInstance->physicalDevicesCount == 0) {
            J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: Failed to find GPUs with Vulkan support\n")
            vulkanLibClose();
            return NULL;
        } else {
            J2dRlsTrace1(J2D_TRACE_INFO, "Vulkan: Found %d physical devices:\n", geInstance->physicalDevicesCount)
        }
        geInstance->physicalDevices = malloc(sizeof (VkPhysicalDevice)*geInstance->physicalDevicesCount);
        vkEnumeratePhysicalDevices(
                geInstance->vkInstance,
                &geInstance->physicalDevicesCount,
                geInstance->physicalDevices);

        PFN_vkGetPhysicalDeviceFeatures2 vkGetPhysicalDeviceFeatures2 =
                (PFN_vkGetPhysicalDeviceFeatures2)vulkanLibProc(
                        geInstance->vkInstance, "vkGetPhysicalDeviceFeatures2");

        PFN_vkGetPhysicalDeviceProperties2 vkGetPhysicalDeviceProperties2 =
                (PFN_vkGetPhysicalDeviceProperties2)
                        vulkanLibProc(geInstance->vkInstance, "vkGetPhysicalDeviceProperties2");

        PFN_vkGetPhysicalDeviceQueueFamilyProperties vkGetPhysicalDeviceQueueFamilyProperties =
                (PFN_vkGetPhysicalDeviceQueueFamilyProperties)
                        vulkanLibProc(geInstance->vkInstance, "vkGetPhysicalDeviceQueueFamilyProperties");
        PFN_vkEnumerateDeviceLayerProperties vkEnumerateDeviceLayerProperties =
                (PFN_vkEnumerateDeviceLayerProperties)
                        vulkanLibProc(geInstance->vkInstance, "vkEnumerateDeviceLayerProperties");
        PFN_vkEnumerateDeviceExtensionProperties vkEnumerateDeviceExtensionProperties =
                (PFN_vkEnumerateDeviceExtensionProperties)
                        vulkanLibProc(geInstance->vkInstance, "vkEnumerateDeviceExtensionProperties");
        PFN_vkCreateDevice vkCreateDevice =
                (PFN_vkCreateDevice)
                        vulkanLibProc(geInstance->vkInstance, "vkCreateDevice");

        if(vkGetPhysicalDeviceFeatures2 == NULL ||
           vkGetPhysicalDeviceProperties2 == NULL ||
           vkGetPhysicalDeviceQueueFamilyProperties == NULL ||
           vkEnumerateDeviceLayerProperties == NULL ||
           vkCreateDevice == NULL)
        {
            J2dRlsTrace(J2D_TRACE_ERROR, "Required api is not supported\n")
            vulkanLibClose();
            return NULL;
        }
#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
        PFN_vkGetPhysicalDeviceWaylandPresentationSupportKHR vkGetPhysicalDeviceWaylandPresentationSupportKHR =
                (PFN_vkGetPhysicalDeviceWaylandPresentationSupportKHR) vulkanLibProc(
                        geInstance->vkInstance, "vkGetPhysicalDeviceWaylandPresentationSupportKHR");
        if(vkGetPhysicalDeviceWaylandPresentationSupportKHR == NULL) {
            J2dRlsTrace(J2D_TRACE_ERROR, "vkGetPhysicalDeviceWaylandPresentationSupportKHR is not supported\n");
            vulkanLibClose();
            return NULL;
        }
#endif
        geInstance->devices = (VKLogicalDevice *) malloc(geInstance->physicalDevicesCount*sizeof(VKLogicalDevice));
        geInstance->devicesCount = 0;
        //geInstance->devicesC
        for (uint32_t i = 0; i < geInstance->physicalDevicesCount; i++) {
            VkPhysicalDeviceVulkan12Features device12Features;
            device12Features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
            device12Features.pNext = NULL;

            VkPhysicalDeviceFeatures2 deviceFeatures2 = {};
            deviceFeatures2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
            deviceFeatures2.pNext = &device12Features;
            vkGetPhysicalDeviceFeatures2(geInstance->physicalDevices[i], &deviceFeatures2);

            VkPhysicalDeviceProperties2 deviceProperties2 = {};
            deviceProperties2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
            vkGetPhysicalDeviceProperties2(geInstance->physicalDevices[i], &deviceProperties2);
            J2dRlsTrace5(J2D_TRACE_INFO, "\t- %s (%d.%d.%d, %s) ",
                         (const char *) deviceProperties2.properties.deviceName,
                         VK_API_VERSION_MAJOR(deviceProperties2.properties.apiVersion),
                         VK_API_VERSION_MINOR(deviceProperties2.properties.apiVersion),
                         VK_API_VERSION_PATCH(deviceProperties2.properties.apiVersion),
                         physicalDeviceTypeString(deviceProperties2.properties.deviceType))

            if (!deviceFeatures2.features.logicOp) {
                J2dRlsTrace(J2D_TRACE_INFO, " - hasLogicOp not supported, skipped \n")
                continue;
            }

            if (!device12Features.timelineSemaphore) {
                J2dRlsTrace(J2D_TRACE_INFO, " - hasTimelineSemaphore not supported, skipped \n")
                continue;
            }
            J2dRlsTrace(J2D_TRACE_INFO, "\n")

            uint32_t queueFamilyCount = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(
                    geInstance->physicalDevices[i], &queueFamilyCount, NULL);

            VkQueueFamilyProperties *queueFamilies = malloc(sizeof(VkQueueFamilyProperties) * queueFamilyCount);
            vkGetPhysicalDeviceQueueFamilyProperties(
                    geInstance->physicalDevices[i], &queueFamilyCount, queueFamilies);
            int64_t queueFamily = -1;
            for (uint32_t j = 0; j < queueFamilyCount; j++) {
#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
                VkBool32 presentationSupported = vkGetPhysicalDeviceWaylandPresentationSupportKHR(geInstance->physicalDevices[i], j, wl_display);
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
                J2dRlsTrace3(J2D_TRACE_INFO, "    %d queues in family (%.*s)\n", queueFamilies[j].queueCount, 5,
                             logFlags)

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
                J2dRlsTrace(J2D_TRACE_INFO, "    --------------------- Suitable queue not found, skipped \n")
                continue;
            }

            uint32_t layerCount;
            vkEnumerateDeviceLayerProperties(geInstance->physicalDevices[i], &layerCount, NULL);
            VkLayerProperties *layers = (VkLayerProperties *) malloc(layerCount * sizeof(VkLayerProperties));
            vkEnumerateDeviceLayerProperties(geInstance->physicalDevices[i], &layerCount, layers);
            J2dRlsTrace(J2D_TRACE_VERBOSE, "    Supported device layers:\n")
            for (uint32_t j = 0; j < layerCount; j++) {
                J2dRlsTrace1(J2D_TRACE_VERBOSE, "        %s\n", (char *) layers[j].layerName)
            }

            uint32_t extensionCount;
            vkEnumerateDeviceExtensionProperties(geInstance->physicalDevices[i], NULL, &extensionCount, NULL);
            VkExtensionProperties *extensions = (VkExtensionProperties *) malloc(
                    extensionCount * sizeof(VkExtensionProperties));
            vkEnumerateDeviceExtensionProperties(geInstance->physicalDevices[i], NULL, &extensionCount, extensions);
            J2dRlsTrace(J2D_TRACE_VERBOSE, "    Supported device extensions:\n")
            VkBool32 hasSwapChain = VK_FALSE;
            VkBool32 hasExtMemoryBudget = VK_FALSE;
            VkBool32 hasKhrSynchronization2 = VK_FALSE;
            VkBool32 hasKhrDynamicRendering = VK_FALSE;
            for (uint32_t j = 0; j < extensionCount; j++) {
                J2dRlsTrace1(J2D_TRACE_VERBOSE, "        %s\n", (char *) extensions[j].extensionName)
                hasSwapChain = hasSwapChain ||
                               strcmp(VK_KHR_SWAPCHAIN_EXTENSION_NAME, extensions[j].extensionName) == 0;
                hasExtMemoryBudget = hasExtMemoryBudget ||
                                     strcmp(VK_EXT_MEMORY_BUDGET_EXTENSION_NAME, extensions[j].extensionName) == 0;
                hasKhrSynchronization2 = hasKhrSynchronization2 ||
                                         strcmp(VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME, extensions[j].extensionName) ==
                                         0;
                hasKhrDynamicRendering = hasKhrDynamicRendering ||
                                         strcmp(VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME, extensions[j].extensionName) ==
                                         0;
            }
            free(extensions);
            J2dRlsTrace(J2D_TRACE_VERBOSE, "    Found:\n")
            if (hasSwapChain) {
                J2dRlsTrace(J2D_TRACE_VERBOSE, "    VK_KHR_SWAPCHAIN_EXTENSION_NAME\n")
            }
            if (hasExtMemoryBudget) {
                J2dRlsTrace(J2D_TRACE_VERBOSE, "    VK_EXT_MEMORY_BUDGET_EXTENSION_NAME\n")
            }
            if (hasKhrSynchronization2) {
                J2dRlsTrace(J2D_TRACE_VERBOSE, "    VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME\n")
            }
            if (hasKhrDynamicRendering) {
                J2dRlsTrace(J2D_TRACE_VERBOSE, "    VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME\n")
            }

            if (!hasSwapChain) {
                J2dRlsTrace(J2D_TRACE_INFO,
                            "    --------------------- Required VK_KHR_SWAPCHAIN_EXTENSION_NAME not found, skipped \n")
                continue;
            }


            //geInstance->devices->queueFamily = queueFamily;
            uint32_t deviceEnabledLayersCount = 0;
            char **deviceEnabledLayers = malloc(MAX_ENABLED_LAYERS * sizeof(char *));
            uint32_t deviceEnabledExtensionsCount = 0;
            char **deviceEnabledExtensions = malloc(MAX_ENABLED_EXTENSIONS * sizeof(char *));
            deviceEnabledExtensions[deviceEnabledExtensionsCount++] = VK_KHR_SWAPCHAIN_EXTENSION_NAME;

            if (hasExtMemoryBudget) {
                deviceEnabledExtensions[deviceEnabledExtensionsCount++] = VK_EXT_MEMORY_BUDGET_EXTENSION_NAME;
            }
            if (hasKhrSynchronization2) {
                deviceEnabledExtensions[deviceEnabledExtensionsCount++] = VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME;
            }
            if (hasKhrDynamicRendering) {
                deviceEnabledExtensions[deviceEnabledExtensionsCount++] = VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME;
            }
            // Validation layer
#ifdef DEBUG
            int validationLayerNotSupported = 1;
            for (uint32_t j = 0; j < layerCount; j++) {
                if (strcmp(VALIDATION_LAYER_NAME, layers[j].layerName) == 0) {
                    validationLayerNotSupported = 0;
                    deviceEnabledLayers[deviceEnabledLayersCount++] = VALIDATION_LAYER_NAME;
                    break;
                }
            }
            if (validationLayerNotSupported) {
                J2dRlsTrace1(J2D_TRACE_INFO, "    %s device layer is not supported\n", VALIDATION_LAYER_NAME)
            }
#endif
            free(layers);

            geInstance->devices[geInstance->devicesCount].device = VK_NULL_HANDLE;
            geInstance->devices[geInstance->devicesCount].physicalDevice = geInstance->physicalDevices[i];
            geInstance->devices[geInstance->devicesCount].queueFamily = queueFamily;
            geInstance->devices[geInstance->devicesCount].enabledLayers = deviceEnabledLayers;
            geInstance->devices[geInstance->devicesCount].enabledLayersCount = deviceEnabledLayersCount;
            geInstance->devices[geInstance->devicesCount].enabledExtensions = deviceEnabledExtensions;
            geInstance->devices[geInstance->devicesCount].enabledExtensionsCount = deviceEnabledExtensionsCount;
            geInstance->devices[geInstance->devicesCount].hasExtMemoryBudget = hasExtMemoryBudget;
            geInstance->devices[geInstance->devicesCount].hasKhrSynchronization2 = hasKhrSynchronization2;
            geInstance->devices[geInstance->devicesCount].hasKhrDynamicRendering = hasKhrDynamicRendering;
            geInstance->devices[i].name = malloc(strlen(deviceProperties2.properties.deviceName) + 1);
            strcpy(geInstance->devices[i].name, deviceProperties2.properties.deviceName);
            geInstance->devicesCount++;
        }
        if (geInstance->devicesCount == 0) {
            J2dRlsTrace(J2D_TRACE_ERROR, "No compatible device found\n")
            vulkanLibClose();
            return NULL;
        }
        if (verbose) {
            fprintf(stderr, "Vulkan graphics devices:\n");
        }
        geInstance->enabledDeviceNum = 0; // TODO pick first just to check that virtual device creation works

        requestedDeviceNumber = (requestedDeviceNumber == -1) ? 0 : requestedDeviceNumber;

        if (requestedDeviceNumber < 0 || (uint32_t)requestedDeviceNumber >= geInstance->devicesCount) {
            if (verbose) {
                fprintf(stderr, "  Requested device number (%d) not found, fallback to 0\n", requestedDeviceNumber);
            }
            requestedDeviceNumber = 0;
        }
        geInstance->enabledDeviceNum = requestedDeviceNumber;
        if (verbose) {
            for (uint32_t i = 0; i < geInstance->devicesCount; i++) {
                fprintf(stderr, " %c%d: %s\n", i == geInstance->enabledDeviceNum ? '*' : ' ',
                        i, geInstance->devices[i].name);
            }
            fprintf(stderr, "\n");
        }

        VkDeviceQueueCreateInfo queueCreateInfo = {};
        queueCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        queueCreateInfo.queueFamilyIndex = geInstance->devices->queueFamily;  // obtained separately
        queueCreateInfo.queueCount = 1;
        float queuePriority = 1.0f;
        queueCreateInfo.pQueuePriorities = &queuePriority;

        VkPhysicalDeviceFeatures features10 = {};  // set features you need
        features10.logicOp = VK_TRUE;

        VkPhysicalDeviceVulkan12Features features12 = {};
        features12.timelineSemaphore = VK_TRUE;
        pNext = &features12;
        VkPhysicalDeviceSynchronization2FeaturesKHR synchronization2Features = {};
        if (geInstance->devices[geInstance->enabledDeviceNum].hasKhrSynchronization2) {
            synchronization2Features.synchronization2 = VK_TRUE;
            synchronization2Features.pNext = pNext;
            pNext = &synchronization2Features;
        }

        VkPhysicalDeviceDynamicRenderingFeaturesKHR dynamicRenderingFeatures;
        if (geInstance->devices[geInstance->enabledDeviceNum].hasKhrDynamicRendering) {
            dynamicRenderingFeatures.dynamicRendering = VK_TRUE;
            dynamicRenderingFeatures.pNext = pNext;
            pNext = &dynamicRenderingFeatures;
        }
        VkDeviceCreateInfo createInfo = (VkDeviceCreateInfo){
                VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                pNext,
                0,
                1,
                &queueCreateInfo,
                geInstance->devices[geInstance->enabledDeviceNum].enabledLayersCount,
                (const char *const *) geInstance->devices[geInstance->enabledDeviceNum].enabledLayers,
                geInstance->devices[geInstance->enabledDeviceNum].enabledExtensionsCount,
                (const char *const *) geInstance->devices[geInstance->enabledDeviceNum].enabledExtensions,
                &features10
        };
        if (vkCreateDevice(
                   geInstance->devices->physicalDevice,
                   &createInfo,
                   NULL,
                   &geInstance->devices->device) != VK_SUCCESS)
        {
               J2dRlsTrace1(J2D_TRACE_ERROR, "Cannot create device:\n    %s\n",
                            geInstance->devices[geInstance->enabledDeviceNum].name)
               vulkanLibClose();
               return NULL;
        } else {
               J2dRlsTrace1(J2D_TRACE_INFO, "Logical device (%s) created\n",
                            geInstance->devices[geInstance->enabledDeviceNum].name)
        }
    }
    return geInstance;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *reserved) {
    vulkanLibClose();
}
