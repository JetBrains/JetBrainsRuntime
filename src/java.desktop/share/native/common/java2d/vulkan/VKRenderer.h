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

#include "SurfaceData.h"
#include "VKTypes.h"
#include "VKPipelines.h"

#define NO_CLIP ((VkRect2D) {{0, 0}, {0x7FFFFFFFU, 0x7FFFFFFFU}})

struct VKRenderingContext {
    VKSDOps*        surface;

    VKTransform     transform;
    uint64_t        transformModCount;

    // We keep this color separately from renderColor,
    // because we need consistent state when switching between XOR and alpha
    // composite modes. This variable holds last value set by SET_COLOR, while
    // renderColor holds color, currently used for drawing, which may have
    // also been provided by SET_XOR_COMPOSITE.
    Color           color;
    Color           renderColor;
    VKCompositeMode composite;

    // Extra alpha is not used when painting with plain color,
    // in this case color.a already includes it.
    float extraAlpha;
    uint64_t           clipModCount; // Used to track changes to the clip.
    VkRect2D           clipRect;
    ARRAY(VKIntVertex) clipSpanVertices;
};

VKRenderingContext* VKRenderer_GetContext();

VKRenderer* VKRenderer_Create(VKDevice* device);

void VKRenderer_Destroy(VKRenderer* renderer);

/**
 * Submit pending command buffer, completed render passes & presentation requests.
 */
void VKRenderer_Flush(VKRenderer* renderer);

/**
 * Wait for the latest checkpoint to be reached by GPU.
 * This only affects commands tracked by the timeline semaphore,
 * unlike vkDeviceWaitIdle / vkQueueWaitIdle.
 */
void VKRenderer_Sync(VKRenderer* renderer);

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
void VKRenderer_ConfigureSurface(VKSDOps* surface, VkExtent2D extent, VKDevice* device);

// Blit operations.

void VKRenderer_IsoBlit(VKSDOps* src, jint filter,
                        jint sx1, jint sy1, jint sx2, jint sy2,
                        jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2);

void VKRenderer_Blit(JNIEnv *env, SurfaceDataOps* src, jshort srctype, jint filter,
                     jint sx1, jint sy1, jint sx2, jint sy2,
                     jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2);

void VKRenderer_SurfaceToSwBlit(JNIEnv* env, VKSDOps* src, SurfaceDataOps* dst,
                                jint srcx, jint srcy, jint dstx, jint dsty, jint width, jint height);

// Drawing operations.

void VKRenderer_RenderRect(VkBool32 fill, jint x, jint y, jint w, jint h);

void VKRenderer_RenderParallelogram(VkBool32 fill,
                                    jfloat x11, jfloat y11,
                                    jfloat dx21, jfloat dy21,
                                    jfloat dx12, jfloat dy12);

void VKRenderer_FillSpans(jint spanCount, jint *spans);

void VKRenderer_MaskFill(jint x, jint y, jint w, jint h,
                         jint maskoff, jint maskscan, jint masklen, uint8_t *mask);

#endif //VKRenderer_h_Included
