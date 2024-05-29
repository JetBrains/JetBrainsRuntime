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

#ifndef VKImage_h_Included
#define VKImage_h_Included

#include <vulkan/vulkan.h>

typedef struct {
    VkImage                 image;
    VkDeviceMemory          memory;
    VkFramebuffer           framebuffer;
    VkImageView             view;
    VkFormat                format;
    VkExtent2D              extent;
    VkBool32                noImageDealloc;
} VKImage;

VKImage* VKImage_Create(uint32_t width, uint32_t height,
                        VkFormat format, VkImageTiling tiling,
                        VkImageUsageFlags usage,
                        VkMemoryPropertyFlags properties);

VKImage* VKImage_CreateImageArrayFromSwapChain(VkSwapchainKHR swapchainKhr,
                                               VkRenderPass renderPass,
                                               VkFormat format,
                                               VkExtent2D extent);

VkBool32 VKImage_CreateFramebuffer(VKImage *image, VkRenderPass renderPass);

void VKImage_free(VKImage* image);
void VKImage_dealloc(VKImage* image);
#endif // VKImage_h_Included
