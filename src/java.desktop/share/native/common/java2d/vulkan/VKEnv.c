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
#include <string.h>
#include "VKUtil.h"
#include "VKEnv.h"
#include "VKDevice.h"

#define VULKAN_DLL JNI_LIB_NAME("vulkan")
#define VULKAN_1_DLL VERSIONED_JNI_LIB_NAME("vulkan", "1")

static void* pVulkanLib = NULL;
static void vulkanLibClose() {
    if (pVulkanLib != NULL) {
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
            J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Failed to load %s", VULKAN_DLL);
            return NULL;
        }
    }

    PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr = dlsym(pVulkanLib, "vkGetInstanceProcAddr");
    if (vkGetInstanceProcAddr == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
                      "Vulkan: Failed to get proc address of vkGetInstanceProcAddr from %s", VULKAN_DLL);
        vulkanLibClose();
        return NULL;
    }
    return vkGetInstanceProcAddr;
}

void VKDevice_Reset(VKDevice* device);

static void VKEnv_Destroy(VKEnv* vk) {
    if (vk == NULL) return;

    if (vk->devices != NULL) {
        for (uint32_t i = 0; i < ARRAY_SIZE(vk->devices); i++) {
            VKDevice_Reset(&vk->devices[i]);
        }
        ARRAY_FREE(vk->devices);
    }

#if defined(DEBUG)
    if (vk->vkDestroyDebugUtilsMessengerEXT != NULL && vk->instance != VK_NULL_HANDLE) {
        vk->vkDestroyDebugUtilsMessengerEXT(vk->instance, vk->debugMessenger, NULL);
    }
#endif

    VKComposites_Destroy(vk->composites);

    if (vk->vkDestroyInstance != NULL) {
        vk->vkDestroyInstance(vk->instance, NULL);
    }
    free(vk);
    J2dRlsTraceLn(J2D_TRACE_INFO, "VKEnv_Destroy");
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
        VK_FATAL_ERROR("Unhandled Vulkan validation error");
    }
    return VK_FALSE;
}
#endif

const char* VKFunctionTable_InitGlobal(PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr, VKFunctionTableGlobal* table);
const char* VKFunctionTable_InitInstance(PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr, VKEnv* instance);

static VKEnv* VKEnv_Create(PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr) {
    if (vkGetInstanceProcAddr == NULL) return NULL;

    VKFunctionTableGlobal table;
    const char* missingAPI = VKFunctionTable_InitGlobal(vkGetInstanceProcAddr, &table);
    if (missingAPI != NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Required API is missing: %s", missingAPI);
        return NULL;
    }

    uint32_t apiVersion = 0;

    VK_IF_ERROR(table.vkEnumerateInstanceVersion(&apiVersion)) return NULL;

    J2dRlsTraceLn(J2D_TRACE_INFO, "Vulkan: Available (%d.%d.%d)",
                  VK_API_VERSION_MAJOR(apiVersion),
                  VK_API_VERSION_MINOR(apiVersion),
                  VK_API_VERSION_PATCH(apiVersion));

    if (apiVersion < REQUIRED_VULKAN_VERSION) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Unsupported version. Required at least (%d.%d.%d)",
                      VK_API_VERSION_MAJOR(REQUIRED_VULKAN_VERSION),
                      VK_API_VERSION_MINOR(REQUIRED_VULKAN_VERSION),
                      VK_API_VERSION_PATCH(REQUIRED_VULKAN_VERSION));
        return NULL;
    }

    uint32_t extensionsCount;
    // Get the number of extensions and layers
    VK_IF_ERROR(table.vkEnumerateInstanceExtensionProperties(NULL, &extensionsCount, NULL)) return NULL;
    VkExtensionProperties extensions[extensionsCount];
    VK_IF_ERROR(table.vkEnumerateInstanceExtensionProperties(NULL, &extensionsCount, extensions)) return NULL;

    uint32_t layersCount;
    VK_IF_ERROR(table.vkEnumerateInstanceLayerProperties(&layersCount, NULL)) return NULL;
    VkLayerProperties layers[layersCount];
    VK_IF_ERROR(table.vkEnumerateInstanceLayerProperties(&layersCount, layers)) return NULL;

    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "    Supported instance layers:");
    for (uint32_t i = 0; i < layersCount; i++) {
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "        %s", (char *) layers[i].layerName);
    }

    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "    Supported instance extensions:");
    for (uint32_t i = 0; i < extensionsCount; i++) {
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "        %s", (char *) extensions[i].extensionName);
    }

    ARRAY(pchar) enabledLayers     = NULL;
    ARRAY(pchar) enabledExtensions = NULL;
    void *pNext = NULL;
#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
    ARRAY_PUSH_BACK(enabledExtensions) = VK_KHR_WAYLAND_SURFACE_EXTENSION_NAME;
#endif
    ARRAY_PUSH_BACK(enabledExtensions) = VK_KHR_SURFACE_EXTENSION_NAME;

    // Check required layers & extensions.
    for (uint32_t i = 0; i < ARRAY_SIZE(enabledExtensions); i++) {
        int notFound = 1;
        for (uint32_t j = 0; j < extensionsCount; j++) {
            if (strcmp(extensions[j].extensionName, enabledExtensions[i]) == 0) {
                notFound = 0;
                break;
            }
        }
        if (notFound) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Required extension %s not found", enabledExtensions[i]);
            ARRAY_FREE(enabledLayers);
            ARRAY_FREE(enabledExtensions);
            return NULL;
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
    features.enabledValidationFeatureCount = SARRAY_COUNT_OF(enables);
    features.pEnabledValidationFeatures = enables;

    // Includes the validation features into the instance creation process

    int foundDebugLayer = 0;
    for (uint32_t i = 0; i < layersCount; i++) {
        if (strcmp((char *) layers[i].layerName, "VK_LAYER_KHRONOS_validation") == 0) {
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
        ARRAY_PUSH_BACK(enabledLayers) = "VK_LAYER_KHRONOS_validation";
        ARRAY_PUSH_BACK(enabledExtensions) = VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
        pNext = &features;
    } else {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "Vulkan: %s and %s are not supported",
                      "VK_LAYER_KHRONOS_validation", VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
    }
#endif

    VKEnv* vk = malloc(sizeof(VKEnv));
    if (vk == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Cannot allocate VKEnv");
        ARRAY_FREE(enabledLayers);
        ARRAY_FREE(enabledExtensions);
        return NULL;
    }
    *vk = (VKEnv) {};

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

    VK_IF_ERROR(table.vkCreateInstance(&instanceCreateInfo, NULL, &vk->instance)) {
        ARRAY_FREE(enabledLayers);
        ARRAY_FREE(enabledExtensions);
        VKEnv_Destroy(vk);
        return NULL;
    }
    J2dRlsTraceLn(J2D_TRACE_INFO, "Vulkan: Instance Created");
    ARRAY_FREE(enabledLayers);
    ARRAY_FREE(enabledExtensions);

    missingAPI = VKFunctionTable_InitInstance(vkGetInstanceProcAddr, vk);
    if (missingAPI != NULL) {}
#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
    else if (vk->vkGetPhysicalDeviceWaylandPresentationSupportKHR == NULL)
        missingAPI = "vkGetPhysicalDeviceWaylandPresentationSupportKHR";
    else if (vk->vkCreateWaylandSurfaceKHR == NULL)
        missingAPI = "vkCreateWaylandSurfaceKHR";
#endif
    if (missingAPI != NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Required API is missing: %s", missingAPI);
        VKEnv_Destroy(vk);
        return NULL;
    }

    vk->composites = VKComposites_Create();

    // Create debug messenger
#if defined(DEBUG)
    if (foundDebugLayer && foundDebugExt && pNext) {
        VkDebugUtilsMessengerCreateInfoEXT debugUtilsMessengerCreateInfo = {
                .sType = VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT,
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
        VK_IF_ERROR(vk->vkCreateDebugUtilsMessengerEXT(vk->instance, &debugUtilsMessengerCreateInfo,
                                                       NULL, &vk->debugMessenger)) {}
    }
#endif

    return vk;
}

void VKDevice_CheckAndAdd(VKEnv* vk, VkPhysicalDevice physicalDevice);

static VkBool32 VKEnv_FindDevices(VKEnv* vk) {
    uint32_t count;
    VK_IF_ERROR(vk->vkEnumeratePhysicalDevices(vk->instance, &count, NULL)) return JNI_FALSE;
    J2dRlsTraceLn(J2D_TRACE_INFO, "Vulkan: Found %d physical devices:", count);
    VkPhysicalDevice physicalDevices[count];
    VK_IF_ERROR(vk->vkEnumeratePhysicalDevices(vk->instance, &count, physicalDevices)) return JNI_FALSE;
    ARRAY_ENSURE_CAPACITY(vk->devices, count);
    for (uint32_t i = 0; i < count; i++) {
        VKDevice_CheckAndAdd(vk, physicalDevices[i]);
    }
    if (ARRAY_SIZE(vk->devices) == 0) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: No compatible device found");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

static jobjectArray createJavaGPUs(JNIEnv *env, VKEnv* vk) {
    jclass deviceClass = (*env)->FindClass(env, "sun/java2d/vulkan/VKGPU");
    if (deviceClass == NULL) return NULL;
    jmethodID deviceConstructor = (*env)->GetMethodID(env, deviceClass, "<init>", "(JLjava/lang/String;I)V");
    if (deviceConstructor == NULL) return NULL;
    jobjectArray deviceArray = (*env)->NewObjectArray(env, ARRAY_SIZE(vk->devices), deviceClass, NULL);
    if (deviceArray == NULL) return NULL;
    for (uint32_t i = 0; i < ARRAY_SIZE(vk->devices); i++) {
        jstring name = JNU_NewStringPlatform(env, vk->devices[i].name);
        if (name == NULL) return NULL;
        jobject device = (*env)->NewObject(env, deviceClass, deviceConstructor,
                                           ptr_to_jlong(&vk->devices[i]), name, vk->devices[i].type);
        if (device == NULL) return NULL;
        (*env)->SetObjectArrayElement(env, deviceArray, i, device);
    }
    return deviceArray;
}

static VKEnv* instance = NULL;
VKEnv* VKEnv_GetInstance() {
    return instance;
}

/*
 * Class:     sun_java2d_vulkan_VKEnv
 * Method:    initNative
 * Signature: (JI)[Lsun/java2d/vulkan/VKDevice;
 */
JNIEXPORT jobjectArray JNICALL
Java_sun_java2d_vulkan_VKEnv_initNative(JNIEnv *env, jclass wlge, jlong nativePtr, jint requestedDevice) {
#ifdef DEBUG
    // Init random for debug-related validation tricks.
    srand(nativePtr);
#endif

    PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr = vulkanLibOpen();
    if (vkGetInstanceProcAddr == NULL) return NULL;

    VKEnv* vk = VKEnv_Create(vkGetInstanceProcAddr);
    if (vk == NULL) {
        vulkanLibClose();
        return NULL;
    }
#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
    vk->waylandDisplay = (struct wl_display*) jlong_to_ptr(nativePtr);
#endif

    if (!VKEnv_FindDevices(vk)) {
        VKEnv_Destroy(vk);
        vulkanLibClose();
        return NULL;
    }

    jobjectArray deviceArray = createJavaGPUs(env, vk);
    if (deviceArray == NULL) {
        VKEnv_Destroy(vk);
        vulkanLibClose();
        return NULL;
    }

    vk->currentDevice = &vk->devices[requestedDevice];
    instance = vk;
    return deviceArray;
}

JNIEXPORT void JNICALL JNI_OnUnload(__attribute__((unused)) JavaVM *vm, __attribute__((unused)) void *reserved) {
    VKEnv_Destroy(instance);
    vulkanLibClose();
}
