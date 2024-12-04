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

#ifndef VKBase_h_Included
#define VKBase_h_Included
#include "VKTypes.h"
#include "VKTexturePool.h"
#include "VKRenderState.h"
#include "VKUtil.h"

struct VKDevice {
    VkDevice         handle;
    VkPhysicalDevice physicalDevice;
    char*            name;
    uint32_t         queueFamily;
    ARRAY(pchar)     enabledLayers;
    ARRAY(pchar)     enabledExtensions;
    VkQueue          queue;

    VKAllocator*     allocator;
    VKRenderer*      renderer;
    VKTexturePool*   texturePool;

    PFN_vkDestroyDevice vkDestroyDevice;
    PFN_vkCreateShaderModule vkCreateShaderModule;
    PFN_vkDestroyShaderModule vkDestroyShaderModule;
    PFN_vkCreatePipelineLayout vkCreatePipelineLayout;
    PFN_vkDestroyPipelineLayout vkDestroyPipelineLayout;
    PFN_vkCreateGraphicsPipelines vkCreateGraphicsPipelines;
    PFN_vkDestroyPipeline vkDestroyPipeline;
    PFN_vkCreateSwapchainKHR vkCreateSwapchainKHR;
    PFN_vkDestroySwapchainKHR vkDestroySwapchainKHR;
    PFN_vkGetSwapchainImagesKHR vkGetSwapchainImagesKHR;
    PFN_vkCreateImageView vkCreateImageView;
    PFN_vkCreateFramebuffer vkCreateFramebuffer;
    PFN_vkCreateCommandPool vkCreateCommandPool;
    PFN_vkDestroyCommandPool vkDestroyCommandPool;
    PFN_vkAllocateCommandBuffers vkAllocateCommandBuffers;
    PFN_vkFreeCommandBuffers vkFreeCommandBuffers;
    PFN_vkCreateSemaphore vkCreateSemaphore;
    PFN_vkDestroySemaphore vkDestroySemaphore;
    PFN_vkWaitSemaphores vkWaitSemaphores;
    PFN_vkGetSemaphoreCounterValue vkGetSemaphoreCounterValue;
    PFN_vkCreateFence vkCreateFence;
    PFN_vkGetDeviceQueue vkGetDeviceQueue;
    PFN_vkWaitForFences vkWaitForFences;
    PFN_vkResetFences vkResetFences;
    PFN_vkAcquireNextImageKHR vkAcquireNextImageKHR;
    PFN_vkResetCommandBuffer vkResetCommandBuffer;
    PFN_vkQueueSubmit vkQueueSubmit;
    PFN_vkQueuePresentKHR vkQueuePresentKHR;
    PFN_vkBeginCommandBuffer vkBeginCommandBuffer;
    PFN_vkCmdBlitImage vkCmdBlitImage;
    PFN_vkCmdPipelineBarrier vkCmdPipelineBarrier;
    PFN_vkCmdBeginRenderPass vkCmdBeginRenderPass;
    PFN_vkCmdExecuteCommands vkCmdExecuteCommands;
    PFN_vkCmdClearAttachments vkCmdClearAttachments;
    PFN_vkCmdBindPipeline vkCmdBindPipeline;
    PFN_vkCmdSetViewport vkCmdSetViewport;
    PFN_vkCmdSetScissor vkCmdSetScissor;
    PFN_vkCmdDraw vkCmdDraw;
    PFN_vkCmdEndRenderPass vkCmdEndRenderPass;
    PFN_vkEndCommandBuffer vkEndCommandBuffer;
    PFN_vkCreateImage vkCreateImage;
    PFN_vkCreateSampler vkCreateSampler;
    PFN_vkDestroySampler vkDestroySampler;
    PFN_vkAllocateMemory vkAllocateMemory;
    PFN_vkBindImageMemory vkBindImageMemory;
    PFN_vkCreateDescriptorSetLayout vkCreateDescriptorSetLayout;
    PFN_vkDestroyDescriptorSetLayout vkDestroyDescriptorSetLayout;
    PFN_vkUpdateDescriptorSets vkUpdateDescriptorSets;
    PFN_vkCreateDescriptorPool vkCreateDescriptorPool;
    PFN_vkDestroyDescriptorPool vkDestroyDescriptorPool;
    PFN_vkAllocateDescriptorSets vkAllocateDescriptorSets;
    PFN_vkCmdBindDescriptorSets vkCmdBindDescriptorSets;
    PFN_vkGetImageMemoryRequirements2 vkGetImageMemoryRequirements2;
    PFN_vkCreateBuffer vkCreateBuffer;
    PFN_vkDestroyBuffer vkDestroyBuffer;
    PFN_vkCreateBufferView vkCreateBufferView;
    PFN_vkDestroyBufferView vkDestroyBufferView;
    PFN_vkGetBufferMemoryRequirements2 vkGetBufferMemoryRequirements2;
    PFN_vkBindBufferMemory vkBindBufferMemory;
    PFN_vkMapMemory vkMapMemory;
    PFN_vkUnmapMemory vkUnmapMemory;
    PFN_vkCmdBindVertexBuffers vkCmdBindVertexBuffers;
    PFN_vkCreateRenderPass vkCreateRenderPass;
    PFN_vkDestroyRenderPass vkDestroyRenderPass;
    PFN_vkFreeMemory vkFreeMemory;
    PFN_vkDestroyImageView vkDestroyImageView;
    PFN_vkDestroyImage vkDestroyImage;
    PFN_vkDestroyFramebuffer vkDestroyFramebuffer;
    PFN_vkFlushMappedMemoryRanges vkFlushMappedMemoryRanges;
    PFN_vkInvalidateMappedMemoryRanges vkInvalidateMappedMemoryRanges;
    PFN_vkCmdPushConstants vkCmdPushConstants;
    PFN_vkCmdCopyBufferToImage vkCmdCopyBufferToImage;
    PFN_vkCmdCopyImageToBuffer vkCmdCopyImageToBuffer;
};

struct VKGraphicsEnvironment {
    VkInstance              vkInstance;
    ARRAY(VkPhysicalDevice) physicalDevices;
    ARRAY(VKDevice)         devices;
    VKDevice*               currentDevice;

#if defined(DEBUG)
    VkDebugUtilsMessengerEXT debugMessenger;

    PFN_vkCreateDebugUtilsMessengerEXT vkCreateDebugUtilsMessengerEXT;
    PFN_vkDestroyDebugUtilsMessengerEXT vkDestroyDebugUtilsMessengerEXT;
#endif

#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
    struct wl_display* waylandDisplay;

    PFN_vkGetPhysicalDeviceWaylandPresentationSupportKHR vkGetPhysicalDeviceWaylandPresentationSupportKHR;
    PFN_vkCreateWaylandSurfaceKHR vkCreateWaylandSurfaceKHR;
#endif

    PFN_vkEnumerateInstanceVersion vkEnumerateInstanceVersion;
    PFN_vkEnumerateInstanceExtensionProperties vkEnumerateInstanceExtensionProperties;
    PFN_vkEnumerateInstanceLayerProperties vkEnumerateInstanceLayerProperties;
    PFN_vkCreateInstance vkCreateInstance;
    PFN_vkDestroyInstance vkDestroyInstance;
    PFN_vkEnumeratePhysicalDevices vkEnumeratePhysicalDevices;
    PFN_vkGetPhysicalDeviceMemoryProperties vkGetPhysicalDeviceMemoryProperties;
    PFN_vkGetPhysicalDeviceFeatures2 vkGetPhysicalDeviceFeatures2;
    PFN_vkGetPhysicalDeviceProperties2 vkGetPhysicalDeviceProperties2;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties vkGetPhysicalDeviceQueueFamilyProperties;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR vkGetPhysicalDeviceSurfaceCapabilitiesKHR;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR vkGetPhysicalDeviceSurfaceFormatsKHR;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR vkGetPhysicalDeviceSurfacePresentModesKHR;
    PFN_vkEnumerateDeviceLayerProperties vkEnumerateDeviceLayerProperties;
    PFN_vkEnumerateDeviceExtensionProperties vkEnumerateDeviceExtensionProperties;
    PFN_vkCreateDevice vkCreateDevice;
    PFN_vkDestroySurfaceKHR vkDestroySurfaceKHR;
    PFN_vkGetDeviceProcAddr vkGetDeviceProcAddr;
};

VKGraphicsEnvironment* VKGE_graphics_environment();

#endif //VKBase_h_Included
