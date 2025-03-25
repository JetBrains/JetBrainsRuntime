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

// Enumeration of all used Vulkan functions via includer-provided *_FUNCTION_TABLE_ENTRY macro.

#ifndef GLOBAL_FUNCTION_TABLE_ENTRY
#define GLOBAL_FUNCTION_TABLE_ENTRY(NAME)
#endif
#ifndef INSTANCE_FUNCTION_TABLE_ENTRY
#define INSTANCE_FUNCTION_TABLE_ENTRY(NAME)
#endif
#ifndef OPTIONAL_INSTANCE_FUNCTION_TABLE_ENTRY
#define OPTIONAL_INSTANCE_FUNCTION_TABLE_ENTRY(NAME)
#endif
#ifndef DEVICE_FUNCTION_TABLE_ENTRY
#define DEVICE_FUNCTION_TABLE_ENTRY(NAME)
#endif

// Global functions.
GLOBAL_FUNCTION_TABLE_ENTRY(vkEnumerateInstanceVersion);
GLOBAL_FUNCTION_TABLE_ENTRY(vkEnumerateInstanceExtensionProperties);
GLOBAL_FUNCTION_TABLE_ENTRY(vkEnumerateInstanceLayerProperties);
GLOBAL_FUNCTION_TABLE_ENTRY(vkCreateInstance);

// Instance functions.
INSTANCE_FUNCTION_TABLE_ENTRY(vkDestroyInstance);
INSTANCE_FUNCTION_TABLE_ENTRY(vkEnumeratePhysicalDevices);
INSTANCE_FUNCTION_TABLE_ENTRY(vkGetPhysicalDeviceMemoryProperties);
INSTANCE_FUNCTION_TABLE_ENTRY(vkGetPhysicalDeviceFeatures2);
INSTANCE_FUNCTION_TABLE_ENTRY(vkGetPhysicalDeviceProperties2);
INSTANCE_FUNCTION_TABLE_ENTRY(vkGetPhysicalDeviceQueueFamilyProperties);
INSTANCE_FUNCTION_TABLE_ENTRY(vkGetPhysicalDeviceSurfaceCapabilitiesKHR);
INSTANCE_FUNCTION_TABLE_ENTRY(vkGetPhysicalDeviceSurfaceFormatsKHR);
INSTANCE_FUNCTION_TABLE_ENTRY(vkGetPhysicalDeviceSurfacePresentModesKHR);
INSTANCE_FUNCTION_TABLE_ENTRY(vkEnumerateDeviceLayerProperties);
INSTANCE_FUNCTION_TABLE_ENTRY(vkEnumerateDeviceExtensionProperties);
INSTANCE_FUNCTION_TABLE_ENTRY(vkCreateDevice);
INSTANCE_FUNCTION_TABLE_ENTRY(vkDestroySurfaceKHR);
INSTANCE_FUNCTION_TABLE_ENTRY(vkGetDeviceProcAddr);

#if defined(VK_USE_PLATFORM_WAYLAND_KHR)
OPTIONAL_INSTANCE_FUNCTION_TABLE_ENTRY(vkGetPhysicalDeviceWaylandPresentationSupportKHR);
OPTIONAL_INSTANCE_FUNCTION_TABLE_ENTRY(vkCreateWaylandSurfaceKHR);
#endif
#if defined(DEBUG)
OPTIONAL_INSTANCE_FUNCTION_TABLE_ENTRY(vkCreateDebugUtilsMessengerEXT);
OPTIONAL_INSTANCE_FUNCTION_TABLE_ENTRY(vkDestroyDebugUtilsMessengerEXT);
#endif

// Device functions.
DEVICE_FUNCTION_TABLE_ENTRY(vkDestroyDevice);
DEVICE_FUNCTION_TABLE_ENTRY(vkCreateShaderModule);
DEVICE_FUNCTION_TABLE_ENTRY(vkDestroyShaderModule);
DEVICE_FUNCTION_TABLE_ENTRY(vkCreatePipelineLayout);
DEVICE_FUNCTION_TABLE_ENTRY(vkDestroyPipelineLayout);
DEVICE_FUNCTION_TABLE_ENTRY(vkCreateGraphicsPipelines);
DEVICE_FUNCTION_TABLE_ENTRY(vkDestroyPipeline);
DEVICE_FUNCTION_TABLE_ENTRY(vkCreateSwapchainKHR);
DEVICE_FUNCTION_TABLE_ENTRY(vkDestroySwapchainKHR);
DEVICE_FUNCTION_TABLE_ENTRY(vkGetSwapchainImagesKHR);
DEVICE_FUNCTION_TABLE_ENTRY(vkCreateImageView);
DEVICE_FUNCTION_TABLE_ENTRY(vkCreateFramebuffer);
DEVICE_FUNCTION_TABLE_ENTRY(vkCreateCommandPool);
DEVICE_FUNCTION_TABLE_ENTRY(vkDestroyCommandPool);
DEVICE_FUNCTION_TABLE_ENTRY(vkAllocateCommandBuffers);
DEVICE_FUNCTION_TABLE_ENTRY(vkFreeCommandBuffers);
DEVICE_FUNCTION_TABLE_ENTRY(vkCreateSemaphore);
DEVICE_FUNCTION_TABLE_ENTRY(vkDestroySemaphore);
DEVICE_FUNCTION_TABLE_ENTRY(vkWaitSemaphores);
DEVICE_FUNCTION_TABLE_ENTRY(vkGetSemaphoreCounterValue);
DEVICE_FUNCTION_TABLE_ENTRY(vkCreateFence);
DEVICE_FUNCTION_TABLE_ENTRY(vkGetDeviceQueue);
DEVICE_FUNCTION_TABLE_ENTRY(vkWaitForFences);
DEVICE_FUNCTION_TABLE_ENTRY(vkResetFences);
DEVICE_FUNCTION_TABLE_ENTRY(vkAcquireNextImageKHR);
DEVICE_FUNCTION_TABLE_ENTRY(vkResetCommandBuffer);
DEVICE_FUNCTION_TABLE_ENTRY(vkQueueSubmit);
DEVICE_FUNCTION_TABLE_ENTRY(vkQueuePresentKHR);
DEVICE_FUNCTION_TABLE_ENTRY(vkBeginCommandBuffer);
DEVICE_FUNCTION_TABLE_ENTRY(vkCmdBlitImage);
DEVICE_FUNCTION_TABLE_ENTRY(vkCmdPipelineBarrier);
DEVICE_FUNCTION_TABLE_ENTRY(vkCmdBeginRenderPass);
DEVICE_FUNCTION_TABLE_ENTRY(vkCmdExecuteCommands);
DEVICE_FUNCTION_TABLE_ENTRY(vkCmdClearAttachments);
DEVICE_FUNCTION_TABLE_ENTRY(vkCmdBindPipeline);
DEVICE_FUNCTION_TABLE_ENTRY(vkCmdSetViewport);
DEVICE_FUNCTION_TABLE_ENTRY(vkCmdSetScissor);
DEVICE_FUNCTION_TABLE_ENTRY(vkCmdDraw);
DEVICE_FUNCTION_TABLE_ENTRY(vkCmdEndRenderPass);
DEVICE_FUNCTION_TABLE_ENTRY(vkEndCommandBuffer);
DEVICE_FUNCTION_TABLE_ENTRY(vkCreateImage);
DEVICE_FUNCTION_TABLE_ENTRY(vkCreateSampler);
DEVICE_FUNCTION_TABLE_ENTRY(vkDestroySampler);
DEVICE_FUNCTION_TABLE_ENTRY(vkAllocateMemory);
DEVICE_FUNCTION_TABLE_ENTRY(vkBindImageMemory);
DEVICE_FUNCTION_TABLE_ENTRY(vkCreateDescriptorSetLayout);
DEVICE_FUNCTION_TABLE_ENTRY(vkDestroyDescriptorSetLayout);
DEVICE_FUNCTION_TABLE_ENTRY(vkUpdateDescriptorSets);
DEVICE_FUNCTION_TABLE_ENTRY(vkCreateDescriptorPool);
DEVICE_FUNCTION_TABLE_ENTRY(vkDestroyDescriptorPool);
DEVICE_FUNCTION_TABLE_ENTRY(vkAllocateDescriptorSets);
DEVICE_FUNCTION_TABLE_ENTRY(vkFreeDescriptorSets);
DEVICE_FUNCTION_TABLE_ENTRY(vkCmdBindDescriptorSets);
DEVICE_FUNCTION_TABLE_ENTRY(vkGetImageMemoryRequirements2);
DEVICE_FUNCTION_TABLE_ENTRY(vkCreateBuffer);
DEVICE_FUNCTION_TABLE_ENTRY(vkDestroyBuffer);
DEVICE_FUNCTION_TABLE_ENTRY(vkCreateBufferView);
DEVICE_FUNCTION_TABLE_ENTRY(vkDestroyBufferView);
DEVICE_FUNCTION_TABLE_ENTRY(vkGetBufferMemoryRequirements2);
DEVICE_FUNCTION_TABLE_ENTRY(vkBindBufferMemory);
DEVICE_FUNCTION_TABLE_ENTRY(vkMapMemory);
DEVICE_FUNCTION_TABLE_ENTRY(vkUnmapMemory);
DEVICE_FUNCTION_TABLE_ENTRY(vkCmdBindVertexBuffers);
DEVICE_FUNCTION_TABLE_ENTRY(vkCreateRenderPass);
DEVICE_FUNCTION_TABLE_ENTRY(vkDestroyRenderPass);
DEVICE_FUNCTION_TABLE_ENTRY(vkFreeMemory);
DEVICE_FUNCTION_TABLE_ENTRY(vkDestroyImageView);
DEVICE_FUNCTION_TABLE_ENTRY(vkDestroyImage);
DEVICE_FUNCTION_TABLE_ENTRY(vkDestroyFramebuffer);
DEVICE_FUNCTION_TABLE_ENTRY(vkFlushMappedMemoryRanges);
DEVICE_FUNCTION_TABLE_ENTRY(vkInvalidateMappedMemoryRanges);
DEVICE_FUNCTION_TABLE_ENTRY(vkCmdPushConstants);
DEVICE_FUNCTION_TABLE_ENTRY(vkCmdCopyBufferToImage);
DEVICE_FUNCTION_TABLE_ENTRY(vkCmdCopyImageToBuffer);

#undef GLOBAL_FUNCTION_TABLE_ENTRY
#undef INSTANCE_FUNCTION_TABLE_ENTRY
#undef OPTIONAL_INSTANCE_FUNCTION_TABLE_ENTRY
#undef DEVICE_FUNCTION_TABLE_ENTRY
