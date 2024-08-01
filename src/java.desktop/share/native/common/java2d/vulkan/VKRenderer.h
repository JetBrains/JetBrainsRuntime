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
#include "VKBuffer.h"

struct VKRenderingContext {
    VKSDOps* surface;
    Color color;
    VKTransform transform;
};

/**
 * Pool of resources with associated timestamps, guarding their reuse.
 * The pool must only be manipulated via POOL_* macros.
 */
#define POOL(TYPE, NAME)    \
struct PoolEntry_ ## NAME { \
    uint64_t timestamp;     \
    TYPE value;             \
} *NAME


/**
 * Renderer attached to device.
 */
struct VKRenderer {
    VKDevice*          device;
    VKPipelineContext* pipelineContext;

    POOL(VkCommandBuffer, commandBufferPool);
    POOL(VkCommandBuffer, secondaryCommandBufferPool);
    POOL(VkSemaphore,     semaphorePool);
    POOL(VKBuffer,        vertexBufferPool);
    VkDeviceMemory*       vertexBufferMemoryPages;

    /**
     * Last known timestamp hit by GPU execution. Resources with equal or less timestamp may be safely reused.
     */
    uint64_t readTimestamp;
    /**
     * Next timestamp to be recorded. This is the last checkpoint to be hit by GPU execution.
     */
    uint64_t writeTimestamp;

    VkSemaphore     timelineSemaphore;
    VkCommandPool   commandPool;
    VkCommandBuffer commandBuffer;

    struct Wait {
        VkSemaphore*          semaphores;
        VkPipelineStageFlags* stages;
    } wait;

    struct PendingPresentation {
        VkSwapchainKHR* swapchains;
        uint32_t*       indices;
        VkResult*       results;
    } pendingPresentation;
};

typedef struct {
    // Only sequential writes and no reads from mapped memory!
    void* data;
    VkDeviceSize offset;
    // Whether corresponding buffer was bound to command buffer.
    VkBool32 bound;
} BufferWritingState;

/**
 * Rendering-related info attached to surface.
 */
struct VKRenderPass {
    VKRenderPassContext* context;
    VKBuffer*            vertexBuffers;
    VkFramebuffer        framebuffer;
    VkCommandBuffer      commandBuffer;

    uint32_t           firstVertex;
    uint32_t           vertexCount;
    BufferWritingState vertexBufferWriting;

    VKPipeline currentPipeline;
    VkBool32   pendingFlush;
    VkBool32   pendingCommands;
    VkBool32   pendingClear;
    uint64_t   lastTimestamp; // When was this surface last used?
};



VKRenderer* VKRenderer_Create(VKDevice* device);

VkBool32 VKRenderer_Validate(VKRenderingContext* context, VKPipeline pipeline);
VkCommandBuffer VKRenderer_Record(VKRenderer* renderer);

void VKRenderer_Destroy(VKRenderer* renderer);

/**
 * Wait for all rendering commands to complete.
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
void VKRenderer_FlushRenderPass(VKSDOps* surface);
// Blit operations.

void VKRenderer_TextureRender(VKRenderingContext* context,
                              VKImage *destImage, VKImage *srcImage,
                              VkBuffer vertexBuffer, uint32_t vertexNum);

// Drawing operations.

void VKRenderer_RenderRect(VKRenderingContext* context, VKPipeline pipeline, jint x, jint y, jint w, jint h);

void VKRenderer_RenderParallelogram(VKRenderingContext* context, VKPipeline pipeline,
                                    jfloat x11, jfloat y11,
                                    jfloat dx21, jfloat dy21,
                                    jfloat dx12, jfloat dy12);

void VKRenderer_FillSpans(VKRenderingContext* context, jint spanCount, jint *spans);

#endif //VKRenderer_h_Included
