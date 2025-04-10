/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2025, JetBrains s.r.o.. All rights reserved.
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

#ifndef VKFunctionTable_h_Included
#define VKFunctionTable_h_Included

// Enumeration of all used Vulkan functions via includer-provided FUNCTION_TABLE_ENTRY macro.

// Global functions.
#define GLOBAL_FUNCTION_TABLE(ENTRY, ...) \
ENTRY(__VA_ARGS__, vkEnumerateInstanceVersion); \
ENTRY(__VA_ARGS__, vkEnumerateInstanceExtensionProperties); \
ENTRY(__VA_ARGS__, vkEnumerateInstanceLayerProperties); \
ENTRY(__VA_ARGS__, vkCreateInstance); \

// Instance functions.
#define INSTANCE_FUNCTION_TABLE(ENTRY, ...) \
ENTRY(__VA_ARGS__, vkDestroyInstance); \
ENTRY(__VA_ARGS__, vkEnumeratePhysicalDevices); \
ENTRY(__VA_ARGS__, vkGetPhysicalDeviceMemoryProperties); \
ENTRY(__VA_ARGS__, vkGetPhysicalDeviceFeatures2); \
ENTRY(__VA_ARGS__, vkGetPhysicalDeviceProperties2); \
ENTRY(__VA_ARGS__, vkGetPhysicalDeviceQueueFamilyProperties); \
ENTRY(__VA_ARGS__, vkGetPhysicalDeviceSurfaceCapabilitiesKHR); \
ENTRY(__VA_ARGS__, vkGetPhysicalDeviceSurfaceFormatsKHR); \
ENTRY(__VA_ARGS__, vkGetPhysicalDeviceFormatProperties); \
ENTRY(__VA_ARGS__, vkGetPhysicalDeviceSurfacePresentModesKHR); \
ENTRY(__VA_ARGS__, vkEnumerateDeviceLayerProperties); \
ENTRY(__VA_ARGS__, vkEnumerateDeviceExtensionProperties); \
ENTRY(__VA_ARGS__, vkCreateDevice); \
ENTRY(__VA_ARGS__, vkDestroySurfaceKHR); \
ENTRY(__VA_ARGS__, vkGetDeviceProcAddr); \

#if defined(DEBUG)
#define DEBUG_INSTANCE_FUNCTION_TABLE(ENTRY, ...) \
ENTRY(__VA_ARGS__, vkCreateDebugUtilsMessengerEXT); \
ENTRY(__VA_ARGS__, vkDestroyDebugUtilsMessengerEXT);
#else
#define DEBUG_INSTANCE_FUNCTION_TABLE(ENTRY)
#endif

// Device functions.
#define DEVICE_FUNCTION_TABLE(ENTRY, ...) \
ENTRY(__VA_ARGS__, vkDestroyDevice); \
ENTRY(__VA_ARGS__, vkCreateShaderModule); \
ENTRY(__VA_ARGS__, vkDestroyShaderModule); \
ENTRY(__VA_ARGS__, vkCreatePipelineLayout); \
ENTRY(__VA_ARGS__, vkDestroyPipelineLayout); \
ENTRY(__VA_ARGS__, vkCreateGraphicsPipelines); \
ENTRY(__VA_ARGS__, vkDestroyPipeline); \
ENTRY(__VA_ARGS__, vkCreateSwapchainKHR); \
ENTRY(__VA_ARGS__, vkDestroySwapchainKHR); \
ENTRY(__VA_ARGS__, vkGetSwapchainImagesKHR); \
ENTRY(__VA_ARGS__, vkCreateImageView); \
ENTRY(__VA_ARGS__, vkCreateFramebuffer); \
ENTRY(__VA_ARGS__, vkCreateCommandPool); \
ENTRY(__VA_ARGS__, vkDestroyCommandPool); \
ENTRY(__VA_ARGS__, vkAllocateCommandBuffers); \
ENTRY(__VA_ARGS__, vkFreeCommandBuffers); \
ENTRY(__VA_ARGS__, vkCreateSemaphore); \
ENTRY(__VA_ARGS__, vkDestroySemaphore); \
ENTRY(__VA_ARGS__, vkWaitSemaphores); \
ENTRY(__VA_ARGS__, vkGetSemaphoreCounterValue); \
ENTRY(__VA_ARGS__, vkCreateFence); \
ENTRY(__VA_ARGS__, vkGetDeviceQueue); \
ENTRY(__VA_ARGS__, vkWaitForFences); \
ENTRY(__VA_ARGS__, vkResetFences); \
ENTRY(__VA_ARGS__, vkAcquireNextImageKHR); \
ENTRY(__VA_ARGS__, vkResetCommandBuffer); \
ENTRY(__VA_ARGS__, vkQueueSubmit); \
ENTRY(__VA_ARGS__, vkQueueWaitIdle); \
ENTRY(__VA_ARGS__, vkQueuePresentKHR); \
ENTRY(__VA_ARGS__, vkBeginCommandBuffer); \
ENTRY(__VA_ARGS__, vkCmdBlitImage); \
ENTRY(__VA_ARGS__, vkCmdPipelineBarrier); \
ENTRY(__VA_ARGS__, vkCmdBeginRenderPass); \
ENTRY(__VA_ARGS__, vkCmdExecuteCommands); \
ENTRY(__VA_ARGS__, vkCmdClearAttachments); \
ENTRY(__VA_ARGS__, vkCmdBindPipeline); \
ENTRY(__VA_ARGS__, vkCmdSetViewport); \
ENTRY(__VA_ARGS__, vkCmdSetScissor); \
ENTRY(__VA_ARGS__, vkCmdDraw); \
ENTRY(__VA_ARGS__, vkCmdEndRenderPass); \
ENTRY(__VA_ARGS__, vkEndCommandBuffer); \
ENTRY(__VA_ARGS__, vkCreateImage); \
ENTRY(__VA_ARGS__, vkCreateSampler); \
ENTRY(__VA_ARGS__, vkDestroySampler); \
ENTRY(__VA_ARGS__, vkAllocateMemory); \
ENTRY(__VA_ARGS__, vkBindImageMemory); \
ENTRY(__VA_ARGS__, vkCreateDescriptorSetLayout); \
ENTRY(__VA_ARGS__, vkDestroyDescriptorSetLayout); \
ENTRY(__VA_ARGS__, vkUpdateDescriptorSets); \
ENTRY(__VA_ARGS__, vkCreateDescriptorPool); \
ENTRY(__VA_ARGS__, vkDestroyDescriptorPool); \
ENTRY(__VA_ARGS__, vkAllocateDescriptorSets); \
ENTRY(__VA_ARGS__, vkFreeDescriptorSets); \
ENTRY(__VA_ARGS__, vkCmdBindDescriptorSets); \
ENTRY(__VA_ARGS__, vkGetImageMemoryRequirements2); \
ENTRY(__VA_ARGS__, vkCreateBuffer); \
ENTRY(__VA_ARGS__, vkDestroyBuffer); \
ENTRY(__VA_ARGS__, vkCreateBufferView); \
ENTRY(__VA_ARGS__, vkDestroyBufferView); \
ENTRY(__VA_ARGS__, vkGetBufferMemoryRequirements2); \
ENTRY(__VA_ARGS__, vkBindBufferMemory); \
ENTRY(__VA_ARGS__, vkMapMemory); \
ENTRY(__VA_ARGS__, vkUnmapMemory); \
ENTRY(__VA_ARGS__, vkCmdBindVertexBuffers); \
ENTRY(__VA_ARGS__, vkCreateRenderPass); \
ENTRY(__VA_ARGS__, vkDestroyRenderPass); \
ENTRY(__VA_ARGS__, vkFreeMemory); \
ENTRY(__VA_ARGS__, vkDestroyImageView); \
ENTRY(__VA_ARGS__, vkDestroyImage); \
ENTRY(__VA_ARGS__, vkDestroyFramebuffer); \
ENTRY(__VA_ARGS__, vkFlushMappedMemoryRanges); \
ENTRY(__VA_ARGS__, vkInvalidateMappedMemoryRanges); \
ENTRY(__VA_ARGS__, vkCmdPushConstants); \
ENTRY(__VA_ARGS__, vkCmdCopyBufferToImage); \
ENTRY(__VA_ARGS__, vkCmdCopyImageToBuffer); \
ENTRY(__VA_ARGS__, vkCmdCopyBuffer); \

// Utilities for working with function pointers.

#define DECL_PFN(_, NAME) PFN_ ## NAME NAME
#define GET_PROC_ADDR(GETPROCADDR, HANDLE, PREFIX, NAME) ((PREFIX NAME) = (PFN_ ## NAME) GETPROCADDR(HANDLE, #NAME))
#define CHECK_PROC_ADDR(MISSING_FLAG, ...) if (GET_PROC_ADDR(__VA_ARGS__) == NULL) (MISSING_FLAG = VK_TRUE)
#define LOG_MISSING_PFN(PREFIX, NAME) if ((PREFIX NAME) == NULL) J2dRlsTraceLn(J2D_TRACE_ERROR, "    " #NAME)

#endif //VKFunctionTable_h_Included
