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

#include "VKTypes.h"
#include "VKPipelines.h"

struct VKRenderingContext {
    VKSDOps*        surface;
    VKTransform     transform;
    Color           color;
    VKCompositeMode composite;
    // Extra alpha is not used when painting with plain color,
    // in this case color.a already includes it.
    float extraAlpha;
    uint64_t           clipModCount; // Used to track changes to the clip.
    VkRect2D           clipRect;
    ARRAY(VKIntVertex) clipSpanVertices;
};

typedef struct {
    uint32_t barrierCount;
    VkPipelineStageFlags srcStages;
    VkPipelineStageFlags dstStages;
} VKBarrierBatch;

VKRenderer* VKRenderer_Create(VKDevice* device);

/**
 * Setup pipeline for drawing. Returns FALSE if the surface is not yet ready for drawing.
 */
VkBool32 VKRenderer_Validate(const VKRenderingContext* context, VKShader shader, VkPrimitiveTopology topology);

/**
 * Record commands into the primary command buffer (outside of a render pass).
 * Recorded commands will be sent for execution via VKRenderer_Flush.
 */
VkCommandBuffer VKRenderer_Record(VKRenderer* renderer);

/**
 * Prepare image barrier info to be executed in batch, if needed.
 */
void VKRenderer_AddImageBarrier(VkImageMemoryBarrier* barriers, VKBarrierBatch* batch,
                                VKImage* image, VkPipelineStageFlags stage, VkAccessFlags access, VkImageLayout layout);

void VKRenderer_Destroy(VKRenderer* renderer);

/**
 * Wait for the latest checkpoint to be reached by GPU.
 * This only affects commands tracked by the timeline semaphore,
 * unlike vkDeviceWaitIdle / vkQueueWaitIdle.
 */
void VKRenderer_Sync(VKRenderer* renderer);

/**
 * Submit pending command buffer, completed render passes & presentation requests.
 */
void VKRenderer_Flush(VKRenderer* renderer);

/**
 * Cancel render pass of the surface, release all associated resources and deallocate render pass.
 */
void VKRenderer_DestroyRenderPass(VKSDOps* surface);

/**
 * Flush pending render pass and queue surface for presentation (if applicable).
 */
void VKRenderer_FlushSurface(VKSDOps* surface);

/**
 * Request size for the surface. It has no effect, if it is already of the same size.
 * Actual resize will be performed later, before starting a new frame.
 */
void VKRenderer_ConfigureSurface(VKSDOps* surface, VkExtent2D extent);

/**
 * End render pass for the surface and record it into the primary command buffer,
 * which will be executed on the next VKRenderer_Flush.
 */
void VKRenderer_FlushRenderPass(VKSDOps* surface);

// Blit operations.

void VKRenderer_TextureRender(const VKRenderingContext* context,
                              VKImage *destImage, VKImage *srcImage,
                              VkBuffer vertexBuffer, uint32_t vertexNum);

// Drawing operations.

void VKRenderer_RenderRect(const VKRenderingContext* context, VkBool32 fill,
                           jint x, jint y, jint w, jint h);

void VKRenderer_RenderParallelogram(const VKRenderingContext* context, VkBool32 fill,
                                    jfloat x11, jfloat y11,
                                    jfloat dx21, jfloat dy21,
                                    jfloat dx12, jfloat dy12);

void VKRenderer_FillSpans(const VKRenderingContext* context, jint spanCount, jint *spans);

void VKRenderer_MaskFill(const VKRenderingContext* context,
                         jint x, jint y, jint w, jint h, jint maskoff, jint maskscan, jint masklen, uint8_t* mask);

#endif //VKRenderer_h_Included
