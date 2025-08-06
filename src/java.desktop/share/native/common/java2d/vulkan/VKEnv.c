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
#include "VKCapabilityUtil.h"
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

static VKEnv* VKEnv_Create(PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr, VKPlatformData* platformData) {
    if (vkGetInstanceProcAddr == NULL) return NULL;

    // Init global function table.
    VkBool32 missingAPI = JNI_FALSE;
    GLOBAL_FUNCTION_TABLE(DECL_PFN)
    GLOBAL_FUNCTION_TABLE(CHECK_PROC_ADDR, missingAPI, vkGetInstanceProcAddr, NULL,)
    if (missingAPI) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Required API is missing:");
        GLOBAL_FUNCTION_TABLE(LOG_MISSING_PFN,)
        return NULL;
    }

    // Query API version.
    uint32_t apiVersion = 0;
    VK_IF_ERROR(vkEnumerateInstanceVersion(&apiVersion)) return NULL;
    J2dRlsTraceLn(J2D_TRACE_INFO, "Vulkan: Available (%d.%d.%d)",
                  VK_API_VERSION_MAJOR(apiVersion),
                  VK_API_VERSION_MINOR(apiVersion),
                  VK_API_VERSION_PATCH(apiVersion));

    // Query supported layers.
    uint32_t layerCount;
    VK_IF_ERROR(vkEnumerateInstanceLayerProperties(&layerCount, NULL)) return NULL;
    VkLayerProperties allLayers[layerCount];
    VK_IF_ERROR(vkEnumerateInstanceLayerProperties(&layerCount, allLayers)) return NULL;

    // Query supported extensions.
    uint32_t extensionCount;
    VK_IF_ERROR(vkEnumerateInstanceExtensionProperties(NULL, &extensionCount, NULL)) return NULL;
    VkExtensionProperties allExtensions[extensionCount];
    VK_IF_ERROR(vkEnumerateInstanceExtensionProperties(NULL, &extensionCount, allExtensions)) return NULL;

    // Log layers and extensions.
    VKNamedEntry_LogAll("instance layers", allLayers[0].layerName, layerCount, sizeof(VkLayerProperties));
    VKNamedEntry_LogAll("instance extensions", allExtensions[0].extensionName, extensionCount, sizeof(VkExtensionProperties));

    // Check API version.
    ARRAY(pchar) errors = NULL;
    if (apiVersion < REQUIRED_VULKAN_VERSION) ARRAY_PUSH_BACK(errors) = "Unsupported API version";

    // Check layers.
    VKNamedEntry* layers = NULL;
#ifdef DEBUG
    DEF_NAMED_ENTRY(layers, VK_KHR_VALIDATION_LAYER);
#endif
    VKNamedEntry_Match(layers, allLayers[0].layerName, layerCount, sizeof(VkLayerProperties));
    VKNamedEntry_LogFound(layers);

    // Check extensions.
    pchar PLATFORM_SURFACE_EXTENSION_NAME = platformData != NULL ? platformData->surfaceExtensionName : NULL;
    VKNamedEntry* extensions = NULL;
    DEF_NAMED_ENTRY(extensions, PLATFORM_SURFACE_EXTENSION);
    DEF_NAMED_ENTRY(extensions, VK_KHR_SURFACE_EXTENSION);
#ifdef DEBUG
    DEF_NAMED_ENTRY(extensions, VK_EXT_DEBUG_UTILS_EXTENSION);
#endif
    VKNamedEntry_Match(extensions, allExtensions[0].extensionName, extensionCount, sizeof(VkExtensionProperties));
    VKNamedEntry_LogFound(extensions);

    // Check found errors.
    if (errors != NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "    Vulkan is not supported:");
        VKCapabilityUtil_LogErrors(J2D_TRACE_ERROR, errors);
        ARRAY_FREE(errors);
        return NULL;
    }

    // Check presentation support.
    VkBool32 presentationSupported = PLATFORM_SURFACE_EXTENSION.found && VK_KHR_SURFACE_EXTENSION.found;
    if (!presentationSupported) PLATFORM_SURFACE_EXTENSION.found = VK_KHR_SURFACE_EXTENSION.found = NULL;

    // Configure validation
    void *pNext = NULL;
#ifdef DEBUG
    VkValidationFeatureEnableEXT enables[] = {
//            VK_VALIDATION_FEATURE_ENABLE_GPU_ASSISTED_EXT,
//            VK_VALIDATION_FEATURE_ENABLE_GPU_ASSISTED_RESERVE_BINDING_SLOT_EXT,
            VK_VALIDATION_FEATURE_ENABLE_BEST_PRACTICES_EXT,
//            VK_VALIDATION_FEATURE_ENABLE_DEBUG_PRINTF_EXT,
            VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT
    };
    VkValidationFeaturesEXT features = {
        .sType = VK_STRUCTURE_TYPE_VALIDATION_FEATURES_EXT,
        .enabledValidationFeatureCount = SARRAY_COUNT_OF(enables),
        .pEnabledValidationFeatures = enables
    };
    if (VK_KHR_VALIDATION_LAYER.found && VK_EXT_DEBUG_UTILS_EXTENSION.found) {
        pNext = &features;
    } else {
        VK_KHR_VALIDATION_LAYER.found = VK_EXT_DEBUG_UTILS_EXTENSION.found = NULL;
        J2dRlsTraceLn(J2D_TRACE_WARNING, "    Vulkan validation is not supported");
    }
#endif

    VKEnv* vk = malloc(sizeof(VKEnv));
    if (vk == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "    Cannot allocate VKEnv");
        return NULL;
    }
    *vk = (VKEnv) {
        .platformData = platformData,
        .presentationSupported = presentationSupported
    };

    ARRAY(pchar) enabledLayers = VKNamedEntry_CollectNames(layers);
    ARRAY(pchar) enabledExtensions = VKNamedEntry_CollectNames(extensions);

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
            .ppEnabledLayerNames = enabledLayers,
            .enabledExtensionCount = ARRAY_SIZE(enabledExtensions),
            .ppEnabledExtensionNames = enabledExtensions
    };

    VK_IF_ERROR(vkCreateInstance(&instanceCreateInfo, NULL, &vk->instance)) {
        ARRAY_FREE(enabledLayers);
        ARRAY_FREE(enabledExtensions);
        VKEnv_Destroy(vk);
        return NULL;
    }
    J2dRlsTraceLn(J2D_TRACE_INFO, "Vulkan: Instance Created");
    ARRAY_FREE(enabledLayers);
    ARRAY_FREE(enabledExtensions);

    INSTANCE_FUNCTION_TABLE(CHECK_PROC_ADDR, missingAPI, vkGetInstanceProcAddr, vk->instance, vk->)
    DEBUG_INSTANCE_FUNCTION_TABLE(GET_PROC_ADDR, vkGetInstanceProcAddr, vk->instance, vk->)
    if (missingAPI) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Required API is missing:");
        INSTANCE_FUNCTION_TABLE(LOG_MISSING_PFN, vk->)
        VKEnv_Destroy(vk);
        return NULL;
    }
    if (presentationSupported) {
        SURFACE_INSTANCE_FUNCTION_TABLE(CHECK_PROC_ADDR, missingAPI, vkGetInstanceProcAddr, vk->instance, vk->)
        if (missingAPI) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Required API is missing:");
            SURFACE_INSTANCE_FUNCTION_TABLE(LOG_MISSING_PFN, vk->)
        }
        if (missingAPI || !vk->platformData->initFunctions(vk, vkGetInstanceProcAddr)) {
            vk->presentationSupported = presentationSupported = VK_FALSE;
        }
    }
    J2dRlsTraceLn(J2D_TRACE_INFO, "Vulkan: Presentation supported = %s", presentationSupported ? "true" : "false");

    vk->composites = VKComposites_Create();

    // Create debug messenger
#if defined(DEBUG)
    if (VK_KHR_VALIDATION_LAYER.found && VK_EXT_DEBUG_UTILS_EXTENSION.found &&
        vk->vkCreateDebugUtilsMessengerEXT != NULL && pNext) {
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
    VkPhysicalDevice physicalDevices[count];
    VK_IF_ERROR(vk->vkEnumeratePhysicalDevices(vk->instance, &count, physicalDevices)) return JNI_FALSE;
    ARRAY_ENSURE_CAPACITY(vk->devices, count);
    J2dRlsTraceLn(J2D_TRACE_INFO, "Vulkan: Found %d physical devices:", count);
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
    jmethodID deviceConstructor = (*env)->GetMethodID(env, deviceClass, "<init>", "(JLjava/lang/String;II[I)V");
    if (deviceConstructor == NULL) return NULL;
    jobjectArray deviceArray = (*env)->NewObjectArray(env, ARRAY_SIZE(vk->devices), deviceClass, NULL);
    if (deviceArray == NULL) return NULL;
    for (uint32_t i = 0; i < ARRAY_SIZE(vk->devices); i++) {
        jstring name = JNU_NewStringPlatform(env, vk->devices[i].name);
        if (name == NULL) return NULL;
        jintArray supportedFormats = (*env)->NewIntArray(env, ARRAY_SIZE(vk->devices[i].supportedFormats));
        if (supportedFormats == NULL) return NULL;
        (*env)->SetIntArrayRegion(env, supportedFormats, 0, ARRAY_SIZE(vk->devices[i].supportedFormats), vk->devices[i].supportedFormats);
        jobject device = (*env)->NewObject(env, deviceClass, deviceConstructor,
                                           ptr_to_jlong(&vk->devices[i]), name, vk->devices[i].type,
                                           vk->devices[i].caps, supportedFormats);
        if (device == NULL) return NULL;
        (*env)->SetObjectArrayElement(env, deviceArray, i, device);
    }
    return deviceArray;
}

static VKEnv* instance = NULL;
JNIEXPORT VKEnv* VKEnv_GetInstance() {
    return instance;
}

/*
 * Class:     sun_java2d_vulkan_VKEnv
 * Method:    initNative
 * Signature: (J)[Lsun/java2d/vulkan/VKDevice;
 */
JNIEXPORT jobjectArray JNICALL
Java_sun_java2d_vulkan_VKEnv_initNative(JNIEnv* env, jclass vkenv, jlong platformData) {
#ifdef DEBUG
    // Init random for debug-related validation tricks.
    srand(platformData);
#endif

    PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr = vulkanLibOpen();
    if (vkGetInstanceProcAddr == NULL) return NULL;

    VKEnv* vk = VKEnv_Create(vkGetInstanceProcAddr, jlong_to_ptr(platformData));
    if (vk == NULL) {
        vulkanLibClose();
        return NULL;
    }

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

    instance = vk;
    return deviceArray;
}
