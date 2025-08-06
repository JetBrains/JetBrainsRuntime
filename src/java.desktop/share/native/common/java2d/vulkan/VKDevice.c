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

#include <string.h>
#include "sun_java2d_vulkan_VKGPU.h"
#include "VKUtil.h"
#include "VKEnv.h"
#include "VKAllocator.h"
#include "VKRenderer.h"
#include "VKTexturePool.h"

#if !defined(__BYTE_ORDER__) || __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
#define VK_LITTLE_ENDIAN
#endif

static const char* physicalDeviceTypeString(VkPhysicalDeviceType type) {
    switch (type) {
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

static VkBool32 VKDevice_CheckAndAddFormat(VKEnv* vk, VkPhysicalDevice physicalDevice,
                                           ARRAY(jint)* supportedFormats, VkFormat format, const char* name) {
    VkFormatProperties formatProperties;
    vk->vkGetPhysicalDeviceFormatProperties(physicalDevice, format, &formatProperties);
    static const VkFormatFeatureFlags SAMPLED_FLAGS = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
                                                      VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
    if ((formatProperties.optimalTilingFeatures & SAMPLED_FLAGS) == SAMPLED_FLAGS) {
        // Our format is supported for sampling.
        static const VkFormatFeatureFlags ATTACHMENT_FLAGS = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT |
                                                          VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT |
                                                          VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;
        if ((formatProperties.optimalTilingFeatures & ATTACHMENT_FLAGS) == ATTACHMENT_FLAGS) {
            // Our format is supported as a drawing destination.
            J2dRlsTraceLn(J2D_TRACE_INFO, "        %s (attachment)", name);
            ARRAY_PUSH_BACK(*supportedFormats) = (jint) format;
        } else J2dRlsTraceLn(J2D_TRACE_INFO, "        %s (sampled)", name);
        return VK_TRUE;
    }
    return VK_FALSE;
}

void VKDevice_CheckAndAdd(VKEnv* vk, VkPhysicalDevice physicalDevice) {
    // Query device properties.
    VkPhysicalDeviceVulkan12Features device12Features = {
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES,
            .pNext = NULL
    };
    VkPhysicalDeviceFeatures2 deviceFeatures2 = {
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2,
            .pNext = &device12Features
    };
    vk->vkGetPhysicalDeviceFeatures2(physicalDevice, &deviceFeatures2);
    VkPhysicalDeviceProperties2 deviceProperties2 = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2 };
    vk->vkGetPhysicalDeviceProperties2(physicalDevice, &deviceProperties2);

    // Check features.
    J2dRlsTrace(J2D_TRACE_INFO, "\t- %s (%d.%d.%d, %s) ",
                (const char *) deviceProperties2.properties.deviceName,
                VK_API_VERSION_MAJOR(deviceProperties2.properties.apiVersion),
                VK_API_VERSION_MINOR(deviceProperties2.properties.apiVersion),
                VK_API_VERSION_PATCH(deviceProperties2.properties.apiVersion),
                physicalDeviceTypeString(deviceProperties2.properties.deviceType));
    if (deviceProperties2.properties.apiVersion < REQUIRED_VULKAN_VERSION) {
        J2dRlsTraceLn(J2D_TRACE_INFO, " - unsupported API version, skipped");
        return;
    }

    if (!deviceFeatures2.features.logicOp) {
        J2dRlsTraceLn(J2D_TRACE_INFO, " - hasLogicOp not supported, skipped");
        return;
    }
    if (!device12Features.timelineSemaphore) {
        J2dRlsTraceLn(J2D_TRACE_INFO, " - hasTimelineSemaphore not supported, skipped");
        return;
    }
    J2dRlsTraceLn(J2D_TRACE_INFO, "");

    // Query queue family properties.
    uint32_t queueFamilyCount = 0;
    vk->vkGetPhysicalDeviceQueueFamilyProperties(
            physicalDevice, &queueFamilyCount, NULL);
    VkQueueFamilyProperties queueFamilies[queueFamilyCount];
    vk->vkGetPhysicalDeviceQueueFamilyProperties(
            physicalDevice, &queueFamilyCount, queueFamilies);

    // Find a queue family.
    int64_t queueFamily = -1;
    for (uint32_t j = 0; j < queueFamilyCount; j++) {
        VkBool32 presentationSupported = VK_FALSE;
#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
        presentationSupported =
            vk->vkGetPhysicalDeviceWaylandPresentationSupportKHR(physicalDevice, j, vk->waylandDisplay);
#endif
        char logFlags[5] = {
                queueFamilies[j].queueFlags & VK_QUEUE_GRAPHICS_BIT ? 'G' : '-',
                queueFamilies[j].queueFlags & VK_QUEUE_COMPUTE_BIT ? 'C' : '-',
                queueFamilies[j].queueFlags & VK_QUEUE_TRANSFER_BIT ? 'T' : '-',
                queueFamilies[j].queueFlags & VK_QUEUE_SPARSE_BINDING_BIT ? 'S' : '-',
                presentationSupported ? 'P' : '-'

        };
        J2dRlsTraceLn(J2D_TRACE_INFO, "    %d queues in family (%.*s)", queueFamilies[j].queueCount, 5, logFlags);

        // TODO use compute workloads? Separate transfer-only DMA queue?
        if (queueFamily == -1 && (queueFamilies[j].queueFlags & VK_QUEUE_GRAPHICS_BIT) && presentationSupported) {
            queueFamily = j;
        }
    }
    if (queueFamily == -1) {
        J2dRlsTraceLn(J2D_TRACE_INFO, "    --------------------- Suitable queue not found, skipped");
        return;
    }

    // Query supported layers.
    uint32_t layerCount;
    VK_IF_ERROR(vk->vkEnumerateDeviceLayerProperties(physicalDevice, &layerCount, NULL)) return;
    VkLayerProperties layers[layerCount];
    VK_IF_ERROR(vk->vkEnumerateDeviceLayerProperties(physicalDevice, &layerCount, layers)) return;

    // Query supported extensions.
    uint32_t extensionCount;
    VK_IF_ERROR(vk->vkEnumerateDeviceExtensionProperties(physicalDevice, NULL, &extensionCount, NULL)) return;
    VkExtensionProperties extensions[extensionCount];
    VK_IF_ERROR(vk->vkEnumerateDeviceExtensionProperties(physicalDevice, NULL, &extensionCount, extensions)) return;

    // Query supported formats.
    J2dRlsTraceLn(J2D_TRACE_INFO, "    Supported device formats:");
    VKSampledSrcTypes sampledSrcTypes = {{}, 0};
    VKSampledSrcType* SRCTYPE_4BYTE = &sampledSrcTypes.table[sun_java2d_vulkan_VKSwToSurfaceBlitContext_SRCTYPE_4BYTE];
    VKSampledSrcType* SRCTYPE_3BYTE = &sampledSrcTypes.table[sun_java2d_vulkan_VKSwToSurfaceBlitContext_SRCTYPE_3BYTE];
    VKSampledSrcType* SRCTYPE_565 = &sampledSrcTypes.table[sun_java2d_vulkan_VKSwToSurfaceBlitContext_SRCTYPE_565];
    VKSampledSrcType* SRCTYPE_555 = &sampledSrcTypes.table[sun_java2d_vulkan_VKSwToSurfaceBlitContext_SRCTYPE_555];
    ARRAY(jint) supportedFormats = NULL;
#define CHECK_AND_ADD_FORMAT(FORMAT) VKDevice_CheckAndAddFormat(vk, physicalDevice, &supportedFormats, FORMAT, #FORMAT)
    if (CHECK_AND_ADD_FORMAT(VK_FORMAT_B8G8R8A8_UNORM) && SRCTYPE_4BYTE->format == VK_FORMAT_UNDEFINED) {
        supportedFormats[0] |= sun_java2d_vulkan_VKGPU_FORMAT_PRESENTABLE_BIT; // TODO Check presentation support.
        *SRCTYPE_4BYTE = (VKSampledSrcType) { VK_FORMAT_B8G8R8A8_UNORM, {
            VK_COMPONENT_SWIZZLE_B, VK_COMPONENT_SWIZZLE_G, VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_A }};
    }
    if (CHECK_AND_ADD_FORMAT(VK_FORMAT_R8G8B8A8_UNORM) && SRCTYPE_4BYTE->format == VK_FORMAT_UNDEFINED) {
        *SRCTYPE_4BYTE = (VKSampledSrcType) { VK_FORMAT_R8G8B8A8_UNORM, {
            VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_G, VK_COMPONENT_SWIZZLE_B, VK_COMPONENT_SWIZZLE_A }};
    }
    if (CHECK_AND_ADD_FORMAT(VK_FORMAT_A8B8G8R8_UNORM_PACK32) && SRCTYPE_4BYTE->format == VK_FORMAT_UNDEFINED) {
        *SRCTYPE_4BYTE = (VKSampledSrcType) { VK_FORMAT_A8B8G8R8_UNORM_PACK32, {
#ifdef VK_LITTLE_ENDIAN
            VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_G, VK_COMPONENT_SWIZZLE_B, VK_COMPONENT_SWIZZLE_A
#else
            VK_COMPONENT_SWIZZLE_A, VK_COMPONENT_SWIZZLE_B, VK_COMPONENT_SWIZZLE_G, VK_COMPONENT_SWIZZLE_R
#endif
        }};
    }
    if (CHECK_AND_ADD_FORMAT(VK_FORMAT_R8G8B8_UNORM) && SRCTYPE_3BYTE->format == VK_FORMAT_UNDEFINED) {
        *SRCTYPE_3BYTE = (VKSampledSrcType) { VK_FORMAT_R8G8B8_UNORM, {
            VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_G, VK_COMPONENT_SWIZZLE_B, VK_COMPONENT_SWIZZLE_ONE }};
    }
    if (CHECK_AND_ADD_FORMAT(VK_FORMAT_B8G8R8_UNORM) && SRCTYPE_3BYTE->format == VK_FORMAT_UNDEFINED) {
        *SRCTYPE_3BYTE = (VKSampledSrcType) { VK_FORMAT_B8G8R8_UNORM, {
            VK_COMPONENT_SWIZZLE_B, VK_COMPONENT_SWIZZLE_G, VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_ONE }};
    }
    if (CHECK_AND_ADD_FORMAT(VK_FORMAT_R5G6B5_UNORM_PACK16) && SRCTYPE_565->format == VK_FORMAT_UNDEFINED) {
        *SRCTYPE_565 = (VKSampledSrcType) { VK_FORMAT_R5G6B5_UNORM_PACK16, {
            VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY }};
    }
    if (CHECK_AND_ADD_FORMAT(VK_FORMAT_A1R5G5B5_UNORM_PACK16) && SRCTYPE_555->format == VK_FORMAT_UNDEFINED) {
        *SRCTYPE_555 = (VKSampledSrcType) { VK_FORMAT_A1R5G5B5_UNORM_PACK16, {
            VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_ONE }};
    }
#undef CHECK_AND_ADD_FORMAT

    // Check sampled formats capabilities.
    if (SRCTYPE_4BYTE->format == VK_FORMAT_UNDEFINED) {
        J2dRlsTraceLn(J2D_TRACE_INFO, " - 4-byte sampled format not found, skipped");
        ARRAY_FREE(supportedFormats);
        return;
    }
    if (SRCTYPE_3BYTE->format != VK_FORMAT_UNDEFINED) sampledSrcTypes.caps |= sun_java2d_vulkan_VKGPU_SAMPLED_CAP_3BYTE_BIT;
    if (SRCTYPE_565->format != VK_FORMAT_UNDEFINED) sampledSrcTypes.caps |= sun_java2d_vulkan_VKGPU_SAMPLED_CAP_565_BIT;
    if (SRCTYPE_555->format != VK_FORMAT_UNDEFINED) sampledSrcTypes.caps |= sun_java2d_vulkan_VKGPU_SAMPLED_CAP_555_BIT;

    { // Check stencil format.
        VkFormatProperties formatProperties;
        vk->vkGetPhysicalDeviceFormatProperties(physicalDevice, VK_FORMAT_S8_UINT, &formatProperties);
        if ((formatProperties.optimalTilingFeatures & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
            J2dRlsTraceLn(J2D_TRACE_INFO, "        %s", "VK_FORMAT_S8_UINT (stencil)");
        } else {
            J2dRlsTraceLn(J2D_TRACE_INFO, " - VK_FORMAT_S8_UINT not supported, skipped");
            ARRAY_FREE(supportedFormats);
            return;
        }
    }

    // Log layers.
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "    Supported device layers:");
    for (uint32_t j = 0; j < layerCount; j++) {
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "        %s", (char *) layers[j].layerName);
    }

    // Check extensions.
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "    Supported device extensions:");
    VkBool32 hasSwapChain = VK_FALSE;
    for (uint32_t j = 0; j < extensionCount; j++) {
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "        %s", (char *) extensions[j].extensionName);
        hasSwapChain = hasSwapChain || strcmp(VK_KHR_SWAPCHAIN_EXTENSION_NAME, extensions[j].extensionName) == 0;
    }
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "Vulkan: Found device extensions:");
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "    " VK_KHR_SWAPCHAIN_EXTENSION_NAME " = %s", hasSwapChain ? "true" : "false");

    if (!hasSwapChain) {
        J2dRlsTraceLn(J2D_TRACE_INFO,
                    "    --------------------- Required " VK_KHR_SWAPCHAIN_EXTENSION_NAME " not found, skipped");
        ARRAY_FREE(supportedFormats);
        return;
    }

    ARRAY(pchar) deviceEnabledLayers     = NULL;
    ARRAY(pchar) deviceEnabledExtensions = NULL;
    ARRAY_PUSH_BACK(deviceEnabledExtensions) = VK_KHR_SWAPCHAIN_EXTENSION_NAME;

    // Check validation layer.
#ifdef DEBUG
    int validationLayerNotSupported = 1;
        for (uint32_t j = 0; j < layerCount; j++) {
            if (strcmp("VK_LAYER_KHRONOS_validation", layers[j].layerName) == 0) {
                validationLayerNotSupported = 0;
                ARRAY_PUSH_BACK(deviceEnabledLayers) = "VK_LAYER_KHRONOS_validation";
                break;
            }
        }
        if (validationLayerNotSupported) {
            J2dRlsTraceLn(J2D_TRACE_INFO, "    %s device layer is not supported", "VK_LAYER_KHRONOS_validation");
        }
#endif

    // Copy device name.
    char* deviceName = strdup(deviceProperties2.properties.deviceName);
    if (deviceName == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "Vulkan: Cannot duplicate deviceName");
        ARRAY_FREE(deviceEnabledLayers);
        ARRAY_FREE(deviceEnabledExtensions);
        ARRAY_FREE(supportedFormats);
        return;
    }

    // Valid device, add.
    ARRAY_PUSH_BACK(vk->devices) = (VKDevice) {
        .name = deviceName,
        .type = deviceProperties2.properties.deviceType,
        .handle = VK_NULL_HANDLE,
        .physicalDevice = physicalDevice,
        .queueFamily = queueFamily,
        .enabledLayers = deviceEnabledLayers,
        .enabledExtensions = deviceEnabledExtensions,
        .sampledSrcTypes = sampledSrcTypes,
        .supportedFormats = supportedFormats
    };
}

const char* VKFunctionTable_InitDevice(VKEnv* vk, VKDevice* device);

void VKDevice_Reset(VKDevice* device) {
    if (device == NULL) return;
    VKRenderer_Destroy(device->renderer);
    VKTexturePool_Dispose(device->texturePool);
    VKAllocator_Destroy(device->allocator);
    ARRAY_FREE(device->enabledExtensions);
    ARRAY_FREE(device->enabledLayers);
    ARRAY_FREE(device->supportedFormats);
    free(device->name);
    if (device->vkDestroyDevice != NULL) {
        device->vkDestroyDevice(device->handle, NULL);
    }
    J2dRlsTraceLn(J2D_TRACE_INFO, "VKDevice_Reset(%s)", device->name);
}

/*
 * Class:     sun_java2d_vulkan_VKGPU
 * Method:    reset
 * Signature: (J)void
 */
JNIEXPORT void JNICALL
Java_sun_java2d_vulkan_VKGPU_reset(JNIEnv *env, jclass jClass, jlong jDevice) {
    VKDevice* device = jlong_to_ptr(jDevice);
    if (device == NULL) {
        JNU_ThrowByName(env, "java/lang/IllegalStateException", "jDevice is NULL");
        return;
    }
    VKDevice_Reset(device);
}

/*
 * Class:     sun_java2d_vulkan_VKGPU
 * Method:    init
 * Signature: (J)void
 */
JNIEXPORT void JNICALL
Java_sun_java2d_vulkan_VKGPU_init(JNIEnv *env, jclass jClass, jlong jDevice) {
    VKDevice* device = jlong_to_ptr(jDevice);
    if (device == NULL) {
        JNU_ThrowByName(env, "java/lang/IllegalStateException", "jDevice is NULL");
        return;
    }
    if (device->handle != VK_NULL_HANDLE) return;

    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueCreateInfo = {
            .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
            .queueFamilyIndex = device->queueFamily,  // obtained separately
            .queueCount = 1,
            .pQueuePriorities = &queuePriority
    };

    VkPhysicalDeviceFeatures features10 = { .logicOp = VK_TRUE };
    VkPhysicalDeviceVulkan12Features features12 = {
            .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES,
            .timelineSemaphore = VK_TRUE
    };
    void *pNext = &features12;

    VkDeviceCreateInfo createInfo = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .pNext = pNext,
        .flags = 0,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &queueCreateInfo,
        .enabledLayerCount = ARRAY_SIZE(device->enabledLayers),
        .ppEnabledLayerNames = (const char *const *) device->enabledLayers,
        .enabledExtensionCount = ARRAY_SIZE(device->enabledExtensions),
        .ppEnabledExtensionNames = (const char *const *) device->enabledExtensions,
        .pEnabledFeatures = &features10
    };

    VKEnv* vk = VKEnv_GetInstance();
    VK_IF_ERROR(vk->vkCreateDevice(device->physicalDevice, &createInfo, NULL, &device->handle)) {
        JNU_ThrowByName(env, "java/lang/RuntimeException", "Cannot create device");
        return;
    }
    J2dRlsTraceLn(J2D_TRACE_INFO, "VKDevice_init(%s)", device->name);

    const char* missingAPI = VKFunctionTable_InitDevice(vk, device);
    if (missingAPI != NULL) {
        VKDevice_Reset(device);
        const char* fixedMessage = "Vulkan: Required API is missing: ";
        char message[strlen(fixedMessage) + strlen(missingAPI) + 1];
        strcpy(message, fixedMessage);
        strcat(message, missingAPI);
        JNU_ThrowByName(env, "java/lang/RuntimeException", message);
        return;
    }

    device->vkGetDeviceQueue(device->handle, device->queueFamily, 0, &device->queue);
    if (device->queue == NULL) {
        VKDevice_Reset(device);
        JNU_ThrowByName(env, "java/lang/RuntimeException", "Vulkan: Failed to get device queue");
        return;
    }

    device->allocator = VKAllocator_Create(device);
    if (!device->allocator) {
        VKDevice_Reset(device);
        JNU_ThrowByName(env, "java/lang/RuntimeException", "Vulkan: Cannot create allocator");
        return;
    }

    device->renderer = VKRenderer_Create(device);
    if (!device->renderer) {
        VKDevice_Reset(device);
        JNU_ThrowByName(env, "java/lang/RuntimeException", "Vulkan: Cannot create renderer");
        return;
    }

    device->texturePool = VKTexturePool_InitWithDevice(device);
    if (!device->texturePool) {
        VKDevice_Reset(device);
        JNU_ThrowByName(env, "java/lang/RuntimeException", "Vulkan: Cannot create texture pool");
        return;
    }
}
