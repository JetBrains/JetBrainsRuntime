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

#ifndef VKRenderer_h_Included
#define VKRenderer_h_Included

#include "j2d_md.h"
#include "VKBase.h"
#include "VKBuffer.h"
#include "VKImage.h"
#include "VKSurfaceData.h"

struct VKRenderer {
    VkRenderPass        renderPass;
    VkDescriptorSetLayout descriptorSetLayout;
    VkDescriptorPool    descriptorPool;
    VkDescriptorSet     descriptorSets;
    VkPipelineLayout    pipelineLayout;
    VkPipeline          graphicsPipeline;
};

VKRenderer* VKRenderer_CreateFillTexturePoly(VKLogicalDevice* logicalDevice);

VKRenderer* VKRenderer_CreateFillColorPoly(VKLogicalDevice* logicalDevice);

VKRenderer* VKRenderer_CreateFillMaxColorPoly(VKLogicalDevice* logicalDevice);

void VKRenderer_BeginRendering(VKLogicalDevice* logicalDevice);
void VKRenderer_EndRendering(VKLogicalDevice* logicalDevice, VkBool32 notifyRenderFinish, VkBool32 waitForDisplayImage);
void VKRenderer_TextureRender(VKLogicalDevice* logicalDevice, VKImage *destImage, VKImage *srcImage, VkBuffer vertexBuffer, uint32_t vertexNum);
void VKRenderer_ColorRender(VKLogicalDevice* logicalDevice, VKImage *destImage, uint32_t rgba, VkBuffer vertexBuffer, uint32_t vertexNum);
void VKRenderer_ColorRenderMaxRect(VKLogicalDevice* logicalDevice, VKImage *destImage, uint32_t rgba);
// fill ops
void VKRenderer_FillRect(VKLogicalDevice* logicalDevice, jint x, jint y, jint w, jint h);
void VKRenderer_FillParallelogram(VKLogicalDevice* logicalDevice,
                                  jint color, VKSDOps *dstOps,
                                  jfloat x11, jfloat y11,
                                  jfloat dx21, jfloat dy21,
                                  jfloat dx12, jfloat dy12);
void VKRenderer_FillSpans(VKLogicalDevice* logicalDevice, jint color, VKSDOps *dstOps, jint spanCount, jint *spans);

jboolean VK_CreateLogicalDeviceRenderers(VKLogicalDevice* logicalDevice);

#endif //VKRenderer_h_Included
