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
#include <vulkan/vulkan.h>
#include "CArrayUtil.h"
#include "jni.h"
#include "VKBuffer.h"

typedef char* pchar;

typedef struct {
    VkRenderPass        renderPass;
    VkDescriptorSetLayout descriptorSetLayout;
    VkDescriptorPool    descriptorPool;
    VkDescriptorSet     descriptorSets;
    VkPipelineLayout    pipelineLayout;
    VkPipeline          graphicsPipeline;
} VKRenderer;

typedef struct {
    VkDevice            device;
    VkPhysicalDevice    physicalDevice;
    VKRenderer*         fillTexturePoly;
    VKRenderer*         fillColorPoly;
    VKRenderer*         fillMaxColorPoly;
    char*               name;
    uint32_t            queueFamily;
    pchar*              enabledLayers;
    pchar*              enabledExtensions;
    VkCommandPool       commandPool;
    VkCommandBuffer     commandBuffer;
    VkSemaphore         imageAvailableSemaphore;
    VkSemaphore         renderFinishedSemaphore;
    VkFence             inFlightFence;
    VkQueue             queue;
    VkSampler           textureSampler;
    VKBuffer*           blitVertexBuffer;
} VKLogicalDevice;


typedef struct {
    VkInstance              vkInstance;
    VkPhysicalDevice*       physicalDevices;
    VKLogicalDevice*        devices;
    uint32_t                enabledDeviceNum;
    VkExtensionProperties*  extensions;
    VkLayerProperties*      layers;

    PFN_vkEnumeratePhysicalDevices vkEnumeratePhysicalDevices;
    PFN_vkGetPhysicalDeviceFeatures2 vkGetPhysicalDeviceFeatures2;
    PFN_vkGetPhysicalDeviceProperties2 vkGetPhysicalDeviceProperties2;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties vkGetPhysicalDeviceQueueFamilyProperties;
    PFN_vkEnumerateDeviceLayerProperties vkEnumerateDeviceLayerProperties;
    PFN_vkEnumerateDeviceExtensionProperties vkEnumerateDeviceExtensionProperties;
#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
    PFN_vkGetPhysicalDeviceWaylandPresentationSupportKHR vkGetPhysicalDeviceWaylandPresentationSupportKHR;
    PFN_vkCreateWaylandSurfaceKHR vkCreateWaylandSurfaceKHR;
#endif
    PFN_vkCreateShaderModule vkCreateShaderModule;
    PFN_vkCreatePipelineLayout vkCreatePipelineLayout;
    PFN_vkCreateGraphicsPipelines vkCreateGraphicsPipelines;
    PFN_vkDestroyShaderModule vkDestroyShaderModule;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR vkGetPhysicalDeviceSurfaceCapabilitiesKHR;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR vkGetPhysicalDeviceSurfaceFormatsKHR;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR vkGetPhysicalDeviceSurfacePresentModesKHR;
    PFN_vkCreateSwapchainKHR vkCreateSwapchainKHR;
    PFN_vkGetSwapchainImagesKHR vkGetSwapchainImagesKHR;
    PFN_vkCreateImageView vkCreateImageView;
    PFN_vkCreateFramebuffer vkCreateFramebuffer;
    PFN_vkCreateCommandPool vkCreateCommandPool;
    PFN_vkAllocateCommandBuffers vkAllocateCommandBuffers;
    PFN_vkCreateSemaphore vkCreateSemaphore;
    PFN_vkCreateFence vkCreateFence;
    PFN_vkGetDeviceQueue vkGetDeviceQueue;
    PFN_vkWaitForFences vkWaitForFences;
    PFN_vkResetFences vkResetFences;
    PFN_vkAcquireNextImageKHR vkAcquireNextImageKHR;
    PFN_vkResetCommandBuffer vkResetCommandBuffer;
    PFN_vkQueueSubmit vkQueueSubmit;
    PFN_vkQueuePresentKHR vkQueuePresentKHR;
    PFN_vkBeginCommandBuffer vkBeginCommandBuffer;
    PFN_vkCmdBeginRenderPass vkCmdBeginRenderPass;
    PFN_vkCmdBindPipeline vkCmdBindPipeline;
    PFN_vkCmdSetViewport vkCmdSetViewport;
    PFN_vkCmdSetScissor vkCmdSetScissor;
    PFN_vkCmdDraw vkCmdDraw;
    PFN_vkCmdEndRenderPass vkCmdEndRenderPass;
    PFN_vkEndCommandBuffer vkEndCommandBuffer;
    PFN_vkCreateImage vkCreateImage;
    PFN_vkCreateSampler vkCreateSampler;
    PFN_vkAllocateMemory vkAllocateMemory;
    PFN_vkGetPhysicalDeviceMemoryProperties vkGetPhysicalDeviceMemoryProperties;
    PFN_vkBindImageMemory vkBindImageMemory;
    PFN_vkCreateDescriptorSetLayout vkCreateDescriptorSetLayout;
    PFN_vkUpdateDescriptorSets vkUpdateDescriptorSets;
    PFN_vkCreateDescriptorPool vkCreateDescriptorPool;
    PFN_vkAllocateDescriptorSets vkAllocateDescriptorSets;
    PFN_vkCmdBindDescriptorSets vkCmdBindDescriptorSets;
    PFN_vkGetImageMemoryRequirements vkGetImageMemoryRequirements;
    PFN_vkCreateBuffer vkCreateBuffer;
    PFN_vkGetBufferMemoryRequirements vkGetBufferMemoryRequirements;
    PFN_vkBindBufferMemory vkBindBufferMemory;
    PFN_vkMapMemory vkMapMemory;
    PFN_vkUnmapMemory vkUnmapMemory;
    PFN_vkCmdBindVertexBuffers vkCmdBindVertexBuffers;
    PFN_vkCreateRenderPass vkCreateRenderPass;
    PFN_vkDestroyBuffer vkDestroyBuffer;
    PFN_vkFreeMemory vkFreeMemory;
    PFN_vkDestroyImageView vkDestroyImageView;
    PFN_vkDestroyImage vkDestroyImage;
    PFN_vkDestroyFramebuffer vkDestroyFramebuffer;
    PFN_vkFlushMappedMemoryRanges vkFlushMappedMemoryRanges;
    PFN_vkCmdPushConstants vkCmdPushConstants;
} VKGraphicsEnvironment;


jboolean VK_FindDevices();
jboolean VK_CreateLogicalDevice(jint requestedDeviceNumber);
jboolean VK_CreateLogicalDeviceRenderers();

VKGraphicsEnvironment* VKGE_graphics_environment();
void* vulkanLibProc(VkInstance vkInstance, char* procName);

#endif //VKBase_h_Included
