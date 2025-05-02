// Copyright 2025 JetBrains s.r.o.
// DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
//
// This code is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License version 2 only, as
// published by the Free Software Foundation.  Oracle designates this
// particular file as subject to the "Classpath" exception as provided
// by Oracle in the LICENSE file that accompanied this code.
//
// This code is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
// FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
// version 2 for more details (a copy is included in the LICENSE file that
// accompanied this code).
//
// You should have received a copy of the GNU General Public License version
// 2 along with this work; if not, write to the Free Software Foundation,
// Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
//
// Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
// or visit www.oracle.com if you need additional information or have any
// questions.

#ifndef VKRenderer_Internal_h_Included
#define VKRenderer_Internal_h_Included

#include <assert.h>
#include <string.h>
#include "VKBuffer.h"
#include "VKImage.h"
#include "VKDevice.h"
#include "VKRenderer.h"
#include "VKSurfaceData.h"
#include "VKUtil.h"

/**
 * Pool of resources with associated timestamps, guarding their reuse.
 * The pool must only be manipulated via POOL_* macros.
 */
#define POOL(TYPE)   \
RING_BUFFER(struct { \
    TYPE value;      \
    uint64_t tmstmp; \
})

/**
 * Take an available item from the pool. Returns VK_TRUE on success.
 */
#define POOL_TAKE(RENDERER, POOL, VAR) \
    VKRenderer_PoolTake((RENDERER), (POOL), &(VAR), RING_BUFFER_FRONT(POOL), sizeof(RING_BUFFER_FRONT(POOL)->value))

/**
 * Return an item to the pool. It will only become available again
 * after the next submitted batch of work completes execution on GPU.
 */
// In debug mode resource reuse will be randomly delayed by 3 timestamps in ~20% cases.
#define POOL_RETURN(RENDERER, POOL, VAR) \
    VKRenderer_PoolPut(&RING_BUFFER_PUSH_BACK(POOL).value, &(VAR), sizeof(RING_BUFFER_FRONT(POOL)->value), \
        (RENDERER)->writeTimestamp + (VK_DEBUG_RANDOM(20)*3))

/**
 * Insert an item into the pool. It is available for POOL_TAKE immediately.
 * This is usually used for bulk insertion of newly created resources.
 */
#define POOL_INSERT(POOL, VAR) \
    VKRenderer_PoolPut(&RING_BUFFER_PUSH_FRONT(POOL).value, &(VAR), sizeof(RING_BUFFER_FRONT(POOL)->value), 0ULL)

/**
 * Destroy all remaining entries in a pool and free its memory.
 * Intended usage:
 *  POOL_DRAIN_FOR(renderer, poolName, entry) {
 *      destroyEntry(entry->value);
 *  }
 */
#define POOL_DRAIN_FOR(TYPE, POOL, VAR) \
    for (TYPE* (VAR); VKRenderer_CheckPoolDrain((POOL), (VAR) = &RING_BUFFER_FRONT(POOL)->value); RING_BUFFER_POP_FRONT(POOL))

/**
 * Free pool memory. It doesn't destroy remaining items.
 */
#define POOL_FREE(POOL) RING_BUFFER_FREE(POOL)

typedef void (*VKCleanupHandler)(VKDevice* device, void* data);
typedef struct {
    VKCleanupHandler handler;
    void* data;
} VKCleanupEntry;

/**
 * Renderer attached to the device.
 */
struct VKRenderer {
    VKDevice*          device;
    VKPipelineContext* pipelineContext;

    POOL(VkCommandBuffer)   commandBufferPool;
    POOL(VkCommandBuffer)   secondaryCommandBufferPool;
    POOL(VkSemaphore)       semaphorePool;
    POOL(VKBuffer)          vertexBufferPool;
    POOL(VKTexelBuffer)     maskFillBufferPool;
    POOL(VKCleanupEntry)    cleanupQueue;
    ARRAY(VKMemory)         bufferMemoryPages;
    ARRAY(VkDescriptorPool) descriptorPools;

    VkCommandPool   commandPool;
    VkCommandBuffer commandBuffer;
    VkSemaphore timelineSemaphore;
    /**
     * Last known timestamp reached by GPU execution. Resources with equal or less timestamp may be safely reused.
     */
    uint64_t readTimestamp;
    /**
     * Next timestamp to be recorded. This is the last checkpoint to be hit by GPU execution.
     */
    uint64_t writeTimestamp;

    struct Wait {
        ARRAY(VkSemaphore)          semaphores;
        ARRAY(VkPipelineStageFlags) stages;
    } wait;

    struct PendingPresentation {
        ARRAY(VkSwapchainKHR) swapchains;
        ARRAY(uint32_t)       indices;
        ARRAY(VkResult)       results;
    } pendingPresentation;
};

typedef struct {
    // Only sequential writes and no reads from mapped memory!
    void* data;
    VkDeviceSize offset;
    // Whether corresponding buffer was bound to command buffer.
    VkBool32 bound;
} VKBufferWritingState;

typedef struct {
    VKBufferWritingState state;
    uint32_t             elements;
} VKBufferWriting;

/**
 * Rendering-related info attached to the surface.
 */
struct VKRenderPass {
    VKRenderPassContext* context;
    ARRAY(VKBuffer)      vertexBuffers;
    ARRAY(VKTexelBuffer) maskFillBuffers;
    ARRAY(VKSDOps*)      usedSurfaces;
    VkRenderPass         renderPass; // Non-owning.
    VkFramebuffer        framebuffer;
    VkCommandBuffer      commandBuffer;

    uint32_t             firstVertex;
    uint32_t             vertexCount;
    VKBufferWritingState vertexBufferWriting;
    VKBufferWritingState maskFillBufferWriting;

    VKPipelineDescriptor state;
    uint64_t             transformModCount; // Just a tag to detect when the transform was changed.
    uint64_t             clipModCount; // Just a tag to detect when the clip was changed.
    VkBool32             pendingFlush    : 1;
    VkBool32             pendingCommands : 1;
    VkBool32             pendingClear    : 1;
    AlphaType            outAlphaType    : 1;
};

/**
 * Whether GPU execution reached a given timestamp or not.
 * The last known GPU timestamp is cached, so VK_TRUE may return quickly,
 * otherwise, timeline semaphore is queried for the up-to-date value.
 */
inline VkBool32 VKRenderer_DidReach(VKRenderer* renderer, uint64_t timestamp) {
    return renderer->readTimestamp >= timestamp ||
           (renderer->device->vkGetSemaphoreCounterValue(renderer->device->handle, renderer->timelineSemaphore,
                                                         &renderer->readTimestamp) == VK_SUCCESS &&
                                                         renderer->readTimestamp >= timestamp);
}

/**
 * Wait till the GPU execution reached a given timestamp.
 */
void VKRenderer_Wait(VKRenderer* renderer, uint64_t timestamp);

/**
 * Wait for the latest checkpoint to be reached by GPU.
 * This only affects commands tracked by the timeline semaphore,
 * unlike vkDeviceWaitIdle / vkQueueWaitIdle.
 */
inline void VKRenderer_Sync(VKRenderer* renderer) {
    VKRenderer_Wait(renderer, renderer->writeTimestamp - 1);
}

/**
 * Record commands into the primary command buffer (outside of a render pass).
 * Recorded commands will be sent for execution via VKRenderer_Flush.
 */
VkCommandBuffer VKRenderer_Record(VKRenderer* renderer);

/**
 * Record barrier batches into the primary command buffer.
 */
void VKRenderer_RecordBarriers(VKRenderer* renderer,
                               VkBufferMemoryBarrier* bufferBarriers, VKBarrierBatch* bufferBatch,
                               VkImageMemoryBarrier* imageBarriers, VKBarrierBatch* imageBatch);

/**
 * End render pass for the surface and record it into the primary command buffer,
 * which will be executed on the next VKRenderer_Flush.
 */
VkBool32 VKRenderer_FlushRenderPass(VKSDOps* surface);

/**
 * Setup pipeline for drawing. Returns FALSE if the surface is not yet ready for drawing.
 */
VkBool32 VKRenderer_Validate(VKShader shader, VkPrimitiveTopology topology, AlphaType inAlphaType);

/**
 * Record draw command, if there are any pending vertices in the vertex buffer
 */
void VKRenderer_FlushDraw(VKSDOps* surface);

/**
 * Allocate vertices from vertex buffer.
 * VKRenderer_Validate must have been called before.
 * This function must be called after all dynamic allocation functions,
 * which can invalidate the drawing state, e.g., VKRenderer_AllocateMaskFillBytes.
 */
#define VK_DRAW(VERTICES, PRIMITIVE_COUNT, VERTEX_COUNT) \
    VKRenderer_AllocateVertices((PRIMITIVE_COUNT), (VERTEX_COUNT), sizeof((VERTICES)[0]), (void**) &(VERTICES))
uint32_t VKRenderer_AllocateVertices(uint32_t primitives, uint32_t vertices, size_t vertexSize, void** result);

/**
 * Allocate bytes for writing into buffer. Returned state contains:
 * - state.data   - pointer to the beginning of buffer, or NULL, if there is no buffer yet.
 * - state.offset - writing offset into the buffer data, or 0, if there is no buffer yet.
 * - state.bound  - whether corresponding buffer is bound to the command buffer,
 *                  caller is responsible for checking this value and setting up & binding the buffer.
 * - elements     - number of actually allocated elements.
 */
VKBufferWriting VKRenderer_AllocateBufferData(VKSDOps* surface, VKBufferWritingState* writingState,
                                              VkDeviceSize elements, VkDeviceSize elementSize,
                                              VkDeviceSize maxBufferSize);

/**
 * Get Color RGBA components in a format suitable for the current render pass.
 */
inline RGBA VKRenderer_GetRGBA(VKSDOps* surface, Color color) {
    return VKUtil_GetRGBA(color, surface->renderPass->outAlphaType);
}

/**
 * Get Color RGBA components of the current context color in a format suitable for the current render pass.
 */
inline RGBA VKRenderer_GetColor() {
    VKRenderingContext* context = VKRenderer_GetContext();
    return VKRenderer_GetRGBA(context->surface, context->renderColor);
}

/**
 * Helper function for POOL_TAKE macro.
 */
static inline VkBool32 VKRenderer_PoolTake(VKRenderer* renderer, void* pool, void* dst, void* src, size_t size) {
    if (src == NULL) return VK_FALSE;
    uint64_t timestamp = ((uint64_t*) src)[(size + 7) / 8];
    if (VKRenderer_DidReach(renderer, timestamp)) {
        memcpy(dst, src, size);
        RING_BUFFER(char) ring_buffer = pool;
        RING_BUFFER_POP_FRONT(ring_buffer);
        return VK_TRUE;
    }
    return VK_FALSE;
}

/**
 * Helper function for POOL_INSERT / POOL_RETURN macro.
 */
static inline void VKRenderer_PoolPut(void* dst, void* src, size_t size, uint64_t timestamp) {
    memcpy(dst, src, size);
    ((uint64_t*) dst)[(size + 7) / 8] = timestamp;
}

/**
 * Helper function for POOL_DRAIN_FOR macro.
 */
static inline VkBool32 VKRenderer_CheckPoolDrain(void* pool, void* entry) {
    if (entry != NULL) return VK_TRUE;
    if (pool != NULL) {
        RING_BUFFER(char) ring_buffer = pool;
        RING_BUFFER_FREE(ring_buffer);
    }
    return VK_FALSE;
}

#endif //VKRenderer_Internal_h_Included
