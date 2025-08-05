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

#include <malloc.h>
#include <Trace.h>
#include "jvm_md.h"
#include "VKBase.h"
#include "VKVertex.h"
#include "CArrayUtil.h"
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <string.h>

#define VULKAN_DLL JNI_LIB_NAME("vulkan")
#define VULKAN_1_DLL VERSIONED_JNI_LIB_NAME("vulkan", "1")
static const uint32_t REQUIRED_VULKAN_VERSION = VK_MAKE_API_VERSION(0, 1, 2, 0);


#define MAX_ENABLED_LAYERS 5
#define MAX_ENABLED_EXTENSIONS 5
#define VALIDATION_LAYER_NAME "VK_LAYER_KHRONOS_validation"
#define COUNT_OF(x) (sizeof(x)/sizeof(x[0]))

static jboolean verbose;
static jint requestedDeviceNumber = -1;
static VKGraphicsEnvironment* geInstance = NULL;
static void* pVulkanLib = NULL;
#define INCLUDE_BYTECODE
#define SHADER_ENTRY(NAME, TYPE) static uint32_t NAME ## _ ## TYPE ## _data[] = {
#define BYTECODE_END };
#include "vulkan/shader_list.h"
#undef INCLUDE_BYTECODE
#undef SHADER_ENTRY
#undef BYTECODE_END

#define DEF_VK_PROC_RET_IF_ERR(INST, NAME, RETVAL) PFN_ ## NAME NAME = (PFN_ ## NAME ) vulkanLibProc(INST, #NAME); \
    if (NAME == NULL) {                                                                        \
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Required api is not supported. %s is missing.", #NAME);\
        vulkanLibClose();                                                                      \
        return RETVAL;                                                                         \
    }


#define VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(SNAME, NAME) do {                                    \
    SNAME->NAME = (PFN_ ## NAME ) vulkanLibProc( SNAME->vkInstance, #NAME);                    \
    if (SNAME->NAME == NULL) {                                                                 \
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Required api is not supported. %s is missing.", #NAME);\
        vulkanLibClose();                                                                      \
        return NULL;                                                                           \
    }                                                                                          \
} while (0)

static void vulkanLibClose() {
    if (pVulkanLib != NULL) {
        if (geInstance != NULL) {
            if (geInstance->layers != NULL) {
                free(geInstance->layers);
            }
            if (geInstance->extensions != NULL) {
                free(geInstance->extensions);
            }
            ARRAY_FREE(geInstance->physicalDevices);
            if (geInstance->devices != NULL) {
                PFN_vkDestroyDevice vkDestroyDevice = vulkanLibProc(geInstance->vkInstance, "vkDestroyDevice");

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
            pVulkanLib = dlopen(VULKAN_1_DLL, RTLD_NOW);
        }
        if (pVulkanLib == NULL) {
            J2dRlsTrace(J2D_TRACE_ERROR, "Failed to load %s\n", VULKAN_DLL);
            return NULL;
        }
        if (!vkGetInstanceProcAddr) {
            vkGetInstanceProcAddr = (PFN_vkGetInstanceProcAddr) dlsym(pVulkanLib, "vkGetInstanceProcAddr");
            if (vkGetInstanceProcAddr == NULL) {
                J2dRlsTrace(J2D_TRACE_ERROR,
                            "Failed to get proc address of vkGetInstanceProcAddr from %s\n", VULKAN_DLL);
                return NULL;
            }
        }
    }

    void* vkProc = vkGetInstanceProcAddr(vkInstance, procName);

    if (vkProc == NULL) {
        J2dRlsTrace(J2D_TRACE_ERROR, "%s is not supported\n", procName);
        return NULL;
    }
    return vkProc;
}

/*
 * Class:     sun_java2d_vulkan_VKInstance
 * Method:    init
 * Signature: (JZI)Z
 */
JNIEXPORT jboolean JNICALL
Java_sun_java2d_vulkan_VKInstance_initNative(JNIEnv *env, jclass wlge, jlong nativePtr, jboolean verb, jint requestedDevice) {
    verbose = verb;
    VKGraphicsEnvironment* geInstance = VKGE_graphics_environment();
    if (geInstance == NULL) {
        return JNI_FALSE;
    }
#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
    geInstance->waylandDisplay = (struct wl_display*) jlong_to_ptr(nativePtr);
#endif
    if (!VK_FindDevices()) {
        return JNI_FALSE;
    }
    if (!VK_CreateLogicalDevice(requestedDevice)) {
        return JNI_FALSE;
    }
    return VK_CreateLogicalDeviceRenderers();
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

VKGraphicsEnvironment* VKGE_graphics_environment() {
    if (geInstance == NULL) {

        DEF_VK_PROC_RET_IF_ERR(VK_NULL_HANDLE, vkEnumerateInstanceVersion, NULL);
        DEF_VK_PROC_RET_IF_ERR(VK_NULL_HANDLE, vkEnumerateInstanceExtensionProperties, NULL);
        DEF_VK_PROC_RET_IF_ERR(VK_NULL_HANDLE, vkEnumerateInstanceLayerProperties, NULL);
        DEF_VK_PROC_RET_IF_ERR(VK_NULL_HANDLE, vkCreateInstance, NULL);

        uint32_t apiVersion = 0;

        if (vkEnumerateInstanceVersion(&apiVersion) != VK_SUCCESS) {
            J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: unable to enumerate Vulkan instance version\n");
            vulkanLibClose();
            return NULL;
        }

        J2dRlsTrace(J2D_TRACE_INFO, "Vulkan: Available (%d.%d.%d)\n",
                    VK_API_VERSION_MAJOR(apiVersion),
                    VK_API_VERSION_MINOR(apiVersion),
                    VK_API_VERSION_PATCH(apiVersion));

        if (apiVersion < REQUIRED_VULKAN_VERSION) {
            J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: Unsupported version. Required at least (%d.%d.%d)\n",
                        VK_API_VERSION_MAJOR(REQUIRED_VULKAN_VERSION),
                        VK_API_VERSION_MINOR(REQUIRED_VULKAN_VERSION),
                        VK_API_VERSION_PATCH(REQUIRED_VULKAN_VERSION));
            vulkanLibClose();
            return NULL;
        }

        geInstance = (VKGraphicsEnvironment*)malloc(sizeof(VKGraphicsEnvironment));
        if (geInstance == NULL) {
            J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: Cannot allocate VKGraphicsEnvironment\n");
            vulkanLibClose();
            return NULL;
        }

        *geInstance = (VKGraphicsEnvironment) {};
        uint32_t extensionsCount;
        // Get the number of extensions and layers
        if (vkEnumerateInstanceExtensionProperties(NULL,
                                                   &extensionsCount,
                                                   NULL) != VK_SUCCESS)
        {
            J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: vkEnumerateInstanceExtensionProperties fails\n");
            vulkanLibClose();
            return NULL;
        }

        geInstance->extensions = ARRAY_ALLOC(VkExtensionProperties, extensionsCount);

        if (geInstance->extensions == NULL) {
            J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: Cannot allocate VkExtensionProperties\n");
            vulkanLibClose();
            return NULL;
        }

        if (vkEnumerateInstanceExtensionProperties(NULL, &extensionsCount,
                                                   geInstance->extensions) != VK_SUCCESS)
        {
            J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: vkEnumerateInstanceExtensionProperties fails\n");
            vulkanLibClose();
            return NULL;
        }

        ARRAY_SIZE(geInstance->extensions) = extensionsCount;

        uint32_t layersCount;
        if (vkEnumerateInstanceLayerProperties(&layersCount, NULL) != VK_SUCCESS)
        {
            J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: vkEnumerateInstanceLayerProperties fails\n");
            vulkanLibClose();
            return NULL;
        }

        geInstance->layers = ARRAY_ALLOC(VkLayerProperties, layersCount);
        if (geInstance->layers == NULL) {
            J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: Cannot allocate VkLayerProperties\n");
            vulkanLibClose();
            return NULL;
        }

        if (vkEnumerateInstanceLayerProperties(&layersCount,
                                               geInstance->layers) != VK_SUCCESS)
        {
            J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: vkEnumerateInstanceLayerProperties fails\n");
            vulkanLibClose();
            return NULL;
        }

        ARRAY_SIZE(geInstance->layers) = layersCount;
        J2dRlsTrace(J2D_TRACE_VERBOSE, "    Supported instance layers:\n");
        for (uint32_t i = 0; i < layersCount; i++) {
            J2dRlsTrace(J2D_TRACE_VERBOSE, "        %s\n", (char *) geInstance->layers[i].layerName);
        }

        J2dRlsTrace(J2D_TRACE_VERBOSE, "    Supported instance extensions:\n");
        for (uint32_t i = 0; i < extensionsCount; i++) {
            J2dRlsTrace(J2D_TRACE_VERBOSE, "        %s\n", (char *) geInstance->extensions[i].extensionName);
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
                if (strcmp((char *) geInstance->extensions[j].extensionName, enabledExtensions[i]) == 0) {
                    notFound = 0;
                    break;
                }
            }
            if (notFound) {
                J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: Required extension %s not found\n", enabledExtensions[i]);
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
        features.enabledValidationFeatureCount = COUNT_OF(enables);
        features.pEnabledValidationFeatures = enables;

       // Includes the validation features into the instance creation process

        int foundDebugLayer = 0;
        for (uint32_t i = 0; i < layersCount; i++) {
            if (strcmp((char *) geInstance->layers[i].layerName, VALIDATION_LAYER_NAME) == 0) {
                foundDebugLayer = 1;
                break;
            }
            J2dRlsTrace(J2D_TRACE_VERBOSE, "        %s\n", (char *) geInstance->layers[i].layerName);
        }
        int foundDebugExt = 0;
        for (uint32_t i = 0; i < extensionsCount; i++) {
            if (strcmp((char *) geInstance->extensions[i].extensionName, VK_EXT_DEBUG_UTILS_EXTENSION_NAME) == 0) {
                foundDebugExt = 1;
                break;
            }
        }

        if (foundDebugLayer && foundDebugExt) {
            ARRAY_PUSH_BACK(&enabledLayers, VALIDATION_LAYER_NAME);
            ARRAY_PUSH_BACK(&enabledExtensions, VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            pNext = &features;
        } else {
            J2dRlsTrace(J2D_TRACE_WARNING, "Vulkan: %s and %s are not supported\n",
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
                .pNext =pNext,
                .flags = 0,
                .pApplicationInfo = &applicationInfo,
                .enabledLayerCount = ARRAY_SIZE(enabledLayers),
                .ppEnabledLayerNames = (const char *const *) enabledLayers,
                .enabledExtensionCount = ARRAY_SIZE(enabledExtensions),
                .ppEnabledExtensionNames = (const char *const *) enabledExtensions
        };

        if (vkCreateInstance(&instanceCreateInfo, NULL, &geInstance->vkInstance) != VK_SUCCESS) {
            J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: Failed to create Vulkan instance\n");
            vulkanLibClose();
            ARRAY_FREE(enabledLayers);
            ARRAY_FREE(enabledExtensions);
            return NULL;
        } else {
            J2dRlsTrace(J2D_TRACE_INFO, "Vulkan: Instance Created\n");
        }
        ARRAY_FREE(enabledLayers);
        ARRAY_FREE(enabledExtensions);

        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkEnumeratePhysicalDevices);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkGetPhysicalDeviceFeatures2);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkGetPhysicalDeviceProperties2);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkGetPhysicalDeviceQueueFamilyProperties);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkEnumerateDeviceLayerProperties);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkEnumerateDeviceExtensionProperties);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCreateShaderModule);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCreatePipelineLayout);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCreateGraphicsPipelines);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkDestroyShaderModule);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkGetPhysicalDeviceSurfaceCapabilitiesKHR);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkGetPhysicalDeviceSurfaceFormatsKHR);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkGetPhysicalDeviceSurfacePresentModesKHR);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCreateSwapchainKHR);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkGetSwapchainImagesKHR);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCreateImageView);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCreateFramebuffer);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCreateCommandPool);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkAllocateCommandBuffers);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCreateSemaphore);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCreateFence);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkGetDeviceQueue);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkWaitForFences);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkResetFences);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkAcquireNextImageKHR);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkResetCommandBuffer);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkQueueSubmit);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkQueuePresentKHR);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkBeginCommandBuffer);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCmdBeginRenderPass);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCmdBindPipeline);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCmdSetViewport);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCmdSetScissor);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCmdDraw);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkEndCommandBuffer);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCmdEndRenderPass);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCreateImage);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCreateSampler);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkAllocateMemory);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkGetPhysicalDeviceMemoryProperties);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkBindImageMemory);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCreateDescriptorSetLayout);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkUpdateDescriptorSets);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCreateDescriptorPool);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkAllocateDescriptorSets);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCmdBindDescriptorSets);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkGetImageMemoryRequirements);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCreateBuffer);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkGetBufferMemoryRequirements);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkBindBufferMemory);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkMapMemory);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkUnmapMemory);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCmdBindVertexBuffers);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCreateRenderPass);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkDestroyBuffer);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkFreeMemory);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkDestroyImageView);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkDestroyImage);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkDestroyFramebuffer);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkFlushMappedMemoryRanges);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCmdPushConstants);

#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkGetPhysicalDeviceWaylandPresentationSupportKHR);
        VKGE_INIT_VK_PROC_RET_NULL_IF_ERR(geInstance, vkCreateWaylandSurfaceKHR);
#endif

    }
    return geInstance;
}

jboolean VK_FindDevices() {
    uint32_t physicalDevicesCount;
    if (geInstance->vkEnumeratePhysicalDevices(geInstance->vkInstance,
                                               &physicalDevicesCount,
                                               NULL) != VK_SUCCESS)
    {
        J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: vkEnumeratePhysicalDevices fails\n");
        vulkanLibClose();
        return JNI_FALSE;
    }

    if (physicalDevicesCount == 0) {
        J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: Failed to find GPUs with Vulkan support\n");
        vulkanLibClose();
        return JNI_FALSE;
    } else {
        J2dRlsTrace(J2D_TRACE_INFO, "Vulkan: Found %d physical devices:\n", physicalDevicesCount);
    }

    geInstance->physicalDevices = ARRAY_ALLOC(VkPhysicalDevice, physicalDevicesCount);
    if (geInstance->physicalDevices == NULL) {
        J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: Cannot allocate VkPhysicalDevice\n");
        vulkanLibClose();
        return JNI_FALSE;
    }

    if (geInstance->vkEnumeratePhysicalDevices(
            geInstance->vkInstance,
            &physicalDevicesCount,
            geInstance->physicalDevices) != VK_SUCCESS)
    {
        J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: vkEnumeratePhysicalDevices fails\n");
        vulkanLibClose();
        return JNI_FALSE;
    }

    geInstance->devices = ARRAY_ALLOC(VKLogicalDevice, physicalDevicesCount);
    if (geInstance->devices == NULL) {
        J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: Cannot allocate VKLogicalDevice\n");
        vulkanLibClose();
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
            J2dRlsTrace(J2D_TRACE_INFO, " - hasLogicOp not supported, skipped \n");
            continue;
        }

        if (!device12Features.timelineSemaphore) {
            J2dRlsTrace(J2D_TRACE_INFO, " - hasTimelineSemaphore not supported, skipped \n");
            continue;
        }
        J2dRlsTrace(J2D_TRACE_INFO, "\n");

        uint32_t queueFamilyCount = 0;
        geInstance->vkGetPhysicalDeviceQueueFamilyProperties(
                geInstance->physicalDevices[i], &queueFamilyCount, NULL);

        VkQueueFamilyProperties *queueFamilies = (VkQueueFamilyProperties*)calloc(queueFamilyCount,
                                                                                  sizeof(VkQueueFamilyProperties));
        if (queueFamilies == NULL) {
            J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: Cannot allocate VkQueueFamilyProperties\n");
            vulkanLibClose();
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
            J2dRlsTrace(J2D_TRACE_INFO, "    %d queues in family (%.*s)\n", queueFamilies[j].queueCount, 5,
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
            J2dRlsTrace(J2D_TRACE_INFO, "    --------------------- Suitable queue not found, skipped \n");
            continue;
        }

        uint32_t layerCount;
        geInstance->vkEnumerateDeviceLayerProperties(geInstance->physicalDevices[i], &layerCount, NULL);
        VkLayerProperties *layers = (VkLayerProperties *) calloc(layerCount, sizeof(VkLayerProperties));
        if (layers == NULL) {
            J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: Cannot allocate VkLayerProperties\n");
            vulkanLibClose();
            return JNI_FALSE;
        }

        geInstance->vkEnumerateDeviceLayerProperties(geInstance->physicalDevices[i], &layerCount, layers);
        J2dRlsTrace(J2D_TRACE_VERBOSE, "    Supported device layers:\n");
        for (uint32_t j = 0; j < layerCount; j++) {
            J2dRlsTrace(J2D_TRACE_VERBOSE, "        %s\n", (char *) layers[j].layerName);
        }

        uint32_t extensionCount;
        geInstance->vkEnumerateDeviceExtensionProperties(geInstance->physicalDevices[i], NULL, &extensionCount, NULL);
        VkExtensionProperties *extensions = (VkExtensionProperties *) calloc(
                extensionCount, sizeof(VkExtensionProperties));
        if (extensions == NULL) {
            J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: Cannot allocate VkExtensionProperties\n");
            vulkanLibClose();
            return JNI_FALSE;
        }

        geInstance->vkEnumerateDeviceExtensionProperties(
                geInstance->physicalDevices[i], NULL, &extensionCount, extensions);
        J2dRlsTrace(J2D_TRACE_VERBOSE, "    Supported device extensions:\n");
        VkBool32 hasSwapChain = VK_FALSE;
        for (uint32_t j = 0; j < extensionCount; j++) {
            J2dRlsTrace(J2D_TRACE_VERBOSE, "        %s\n", (char *) extensions[j].extensionName);
            hasSwapChain = hasSwapChain ||
                           strcmp(VK_KHR_SWAPCHAIN_EXTENSION_NAME, extensions[j].extensionName) == 0;
        }
        free(extensions);
        J2dRlsTrace(J2D_TRACE_VERBOSE, "    Found:\n");
        if (hasSwapChain) {
            J2dRlsTrace(J2D_TRACE_VERBOSE, "    VK_KHR_SWAPCHAIN_EXTENSION_NAME\n");
        }

        if (!hasSwapChain) {
            J2dRlsTrace(J2D_TRACE_INFO,
                        "    --------------------- Required VK_KHR_SWAPCHAIN_EXTENSION_NAME not found, skipped \n");
            continue;
        }

        pchar* deviceEnabledLayers = ARRAY_ALLOC(pchar, MAX_ENABLED_LAYERS);
        if (deviceEnabledLayers == NULL) {
            J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: Cannot allocate deviceEnabledLayers array\n");
            vulkanLibClose();
            return JNI_FALSE;
        }

        pchar* deviceEnabledExtensions = ARRAY_ALLOC(pchar, MAX_ENABLED_EXTENSIONS);
        if (deviceEnabledExtensions == NULL) {
            J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: Cannot allocate deviceEnabledExtensions array\n");
            vulkanLibClose();
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
                J2dRlsTrace(J2D_TRACE_INFO, "    %s device layer is not supported\n", VALIDATION_LAYER_NAME);
            }
#endif
        free(layers);
        char* deviceName = strdup(deviceProperties2.properties.deviceName);
        if (deviceName == NULL) {
            J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: Cannot duplicate deviceName\n");
            vulkanLibClose();
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
        J2dRlsTrace(J2D_TRACE_ERROR, "No compatible device found\n");
        vulkanLibClose();
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

jboolean VK_CreateLogicalDevice(jint requestedDevice) {
    requestedDeviceNumber = requestedDevice;

    if (geInstance == NULL) {
        J2dRlsTrace(J2D_TRACE_ERROR, "Vulkan: VKGraphicsEnvironment is not initialized\n");
        return JNI_FALSE;
    }

    DEF_VK_PROC_RET_IF_ERR(geInstance->vkInstance, vkCreateDevice, JNI_FALSE)
    DEF_VK_PROC_RET_IF_ERR(geInstance->vkInstance, vkCreatePipelineCache, JNI_FALSE)
    DEF_VK_PROC_RET_IF_ERR(geInstance->vkInstance, vkCreateRenderPass, JNI_FALSE)

    requestedDeviceNumber = (requestedDeviceNumber == -1) ? 0 : requestedDeviceNumber;

    if (requestedDeviceNumber < 0 || (uint32_t)requestedDeviceNumber >= ARRAY_SIZE(geInstance->devices)) {
        if (verbose) {
            fprintf(stderr, "  Requested device number (%d) not found, fallback to 0\n", requestedDeviceNumber);
        }
        requestedDeviceNumber = 0;
    }
    geInstance->enabledDeviceNum = requestedDeviceNumber;
    if (verbose) {
        for (uint32_t i = 0; i < ARRAY_SIZE(geInstance->devices); i++) {
            fprintf(stderr, " %c%d: %s\n", i == geInstance->enabledDeviceNum ? '*' : ' ',
                    i, geInstance->devices[i].name);
        }
        fprintf(stderr, "\n");
    }

    VKLogicalDevice* logicalDevice = &geInstance->devices[geInstance->enabledDeviceNum];
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

    if (vkCreateDevice(logicalDevice->physicalDevice, &createInfo, NULL, &logicalDevice->device) != VK_SUCCESS)
    {
        J2dRlsTrace(J2D_TRACE_ERROR, "Cannot create device:\n    %s\n",
                    geInstance->devices[geInstance->enabledDeviceNum].name);
        vulkanLibClose();
        return JNI_FALSE;
    }
    VkDevice device = logicalDevice->device;
    J2dRlsTrace(J2D_TRACE_INFO, "Logical device (%s) created\n", logicalDevice->name);


    // Create command pool
    VkCommandPoolCreateInfo poolInfo = {
            .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
            .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
            .queueFamilyIndex = logicalDevice->queueFamily
    };
    if (geInstance->vkCreateCommandPool(device, &poolInfo, NULL, &logicalDevice->commandPool) != VK_SUCCESS) {
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

    if (geInstance->vkAllocateCommandBuffers(device, &allocInfo, &logicalDevice->commandBuffer) != VK_SUCCESS) {
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

    if (geInstance->vkCreateSemaphore(device, &semaphoreInfo, NULL, &logicalDevice->imageAvailableSemaphore) != VK_SUCCESS ||
        geInstance->vkCreateSemaphore(device, &semaphoreInfo, NULL, &logicalDevice->renderFinishedSemaphore) != VK_SUCCESS ||
        geInstance->vkCreateFence(device, &fenceInfo, NULL, &logicalDevice->inFlightFence) != VK_SUCCESS)
    {
        J2dRlsTraceLn(J2D_TRACE_INFO, "failed to create semaphores!");
        return JNI_FALSE;
    }

    geInstance->vkGetDeviceQueue(device, logicalDevice->queueFamily, 0, &logicalDevice->queue);
    if (logicalDevice->queue == NULL) {
        J2dRlsTraceLn(J2D_TRACE_INFO, "failed to get device queue!");
        return JNI_FALSE;
    }

    VKTxVertex* vertices = ARRAY_ALLOC(VKTxVertex, 4);
    ARRAY_PUSH_BACK(&vertices, ((VKTxVertex){-1.0f, -1.0f, 0.0f, 0.0f}));
    ARRAY_PUSH_BACK(&vertices, ((VKTxVertex){1.0f, -1.0f, 1.0f, 0.0f}));
    ARRAY_PUSH_BACK(&vertices, ((VKTxVertex){-1.0f, 1.0f, 0.0f, 1.0f}));
    ARRAY_PUSH_BACK(&vertices, ((VKTxVertex){1.0f, 1.0f, 1.0f, 1.0f}));
    logicalDevice->blitVertexBuffer = ARRAY_TO_VERTEX_BUF(vertices);
    if (!logicalDevice->blitVertexBuffer) {
        J2dRlsTrace(J2D_TRACE_ERROR, "Cannot create vertex buffer\n");
        return JNI_FALSE;
    }
    ARRAY_FREE(vertices);

    return JNI_TRUE;
}

JNIEXPORT void JNICALL JNI_OnUnload(__attribute__((unused)) JavaVM *vm, __attribute__((unused)) void *reserved) {
    vulkanLibClose();
}
