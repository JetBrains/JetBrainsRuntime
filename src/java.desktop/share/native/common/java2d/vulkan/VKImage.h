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

#include "VKTypes.h"

struct VKImage {
    VkImage                 image;
    VkDeviceMemory          memory;
    VkImageView             view;
    VkFormat                format;
    VkExtent2D              extent;

    VkImageLayout           layout;
    VkPipelineStageFlagBits lastStage;
    VkAccessFlagBits        lastAccess;
};

VKImage* VKImage_Create(VKDevice* device, uint32_t width, uint32_t height,
                        VkFormat format, VkImageTiling tiling,
                        VkImageUsageFlags usage,
                        VkMemoryPropertyFlags properties);

void VKImage_LoadBuffer(VKDevice* device, VKImage* image, VKBuffer* buffer,
                        uint32_t x0, uint32_t y0, uint32_t width, uint32_t height);

void VKImage_free(VKDevice* device, VKImage* image);
#endif // VKImage_h_Included
