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

#ifndef HEADLESS

#include <assert.h>
#include "VKUtil.h"
#include "VKBase.h"
#include "VKAllocator.h"
#include "VKRenderer.h"
#include "VKSurfaceData.h"

// Vertex buffers have fixed size. How to choose a good one?
// 1. Multiple of 6 - triangle and line modes have x3 and x2 vertices per primitive.
// 2. Multiple of 6 - most common vertex format VKColorVertex has 6 components.
// 3. Some nice power of 2 multiplier, for good alignment and adequate capacity.
#define VERTEX_BUFFER_SIZE (6 * 6 * 256) // 9KiB = 384 * sizeof(VKColorVertex)

// Vertex buffers are allocated in pages of fixed size.
#define VERTEX_BUFFER_PAGE_SIZE (4 * 1024 * 1024) // 4MiB - fits 455 buffers and leaves 1KiB unused

#define MAX_VERTEX_BUFFERS_PER_PAGE (VERTEX_BUFFER_PAGE_SIZE / VERTEX_BUFFER_SIZE)

typedef struct {
    VkBuffer buffer;
    // Vertex buffer has no ownership over its memory.
    // Provided memory and offset must only be used to flush memory writes.
    // Allocation and freeing is done in pages using VKAllocator.
    VkDeviceMemory memory;
    VkDeviceSize   offset;
    void*          data; // Only sequential writes!
} VKVertexBuffer;

#define TRACKED_RESOURCE(NAME) \
typedef struct {               \
    uint64_t timestamp;        \
    NAME value;                \
} Tracked ## NAME

TRACKED_RESOURCE(VKVertexBuffer);
TRACKED_RESOURCE(VkCommandBuffer);
TRACKED_RESOURCE(VkSemaphore);

/**
 * Renderer attached to device.
 */
struct VKRenderer {
    VKDevice*          device;
    VKPipelineContext* pipelineContext;

    TrackedVkCommandBuffer* pendingCommandBuffers;
    TrackedVkCommandBuffer* pendingSecondaryCommandBuffers;
    TrackedVkSemaphore*     pendingSemaphores;
    struct VertexBufferPool {
        VKMemory*              memoryPages;
        TrackedVKVertexBuffer* pendingBuffers;
    } vertexBufferPool;

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

/**
 * Rendering-related info attached to surface.
 */
struct VKRenderPass {
    VKRenderPassContext* context;
    VKVertexBuffer*      vertexBuffers;
    VkFramebuffer        framebuffer[FORMAT_ALIAS_COUNT];
    VkCommandBuffer      commandBuffer;

    void*        vertexBufferData;
    VkDeviceSize vertexBufferOffset;
    uint32_t     firstVertex;
    uint32_t     vertexCount;

    CompositeMode currentComposite;
    PipelineType  currentPipeline;
    VkBool32      pendingFlush;
    VkBool32      pendingCommands;
    VkBool32      pendingClear;

    VkImageLayout           layout;
    VkPipelineStageFlagBits lastStage;
    VkAccessFlagBits        lastAccess;
    uint64_t                lastTimestamp; // When was this surface last used?
};

#define POP_PENDING(RENDERER, BUFFER, VAR) do {                                                                   \
    if ((BUFFER) == NULL) break;                                                                                  \
    size_t head = RING_BUFFER_T(BUFFER)->head, tail = RING_BUFFER_T(BUFFER)->tail;                                \
    uint64_t timestamp;                                                                                           \
    if (head != tail && ((RENDERER)->readTimestamp >= (timestamp = (BUFFER)[head].timestamp) || (                 \
        (RENDERER)->device->vkGetSemaphoreCounterValue((RENDERER)->device->handle, (RENDERER)->timelineSemaphore, \
        &(RENDERER)->readTimestamp) == VK_SUCCESS && (RENDERER)->readTimestamp >= timestamp))) {                  \
        (VAR) = (BUFFER)[head].value;                                                                             \
        RING_BUFFER_POP(BUFFER);                                                                                  \
    }                                                                                                             \
} while(0)


// In debug mode resource reuse will be randomly delayed by 3 timestamps in ~20% cases.
#define PUSH_PENDING(RENDERER, BUFFER, T) RING_BUFFER_PUSH_CUSTOM(BUFFER, \
(BUFFER)[tail].timestamp = (RENDERER)->writeTimestamp + (VK_DEBUG_RANDOM(20)*3); (BUFFER)[tail].value = T;)

static VKVertexBuffer VKRenderer_GetVertexBuffer(VKRenderer* renderer) {
    // Reuse from pending.
    VKVertexBuffer buffer = { .buffer = VK_NULL_HANDLE };
    POP_PENDING(renderer, renderer->vertexBufferPool.pendingBuffers, buffer);
    if (buffer.buffer != VK_NULL_HANDLE) return buffer;

    VKDevice* device = renderer->device;
    VKAllocator* alloc = device->allocator;
    // Allocate new ring buffer. Ring buffer grows when size reaches capacity, so leave one more slot to fit all buffers.
    TrackedVKVertexBuffer* newRing =
            CARR_ring_buffer_realloc(NULL, sizeof(TrackedVKVertexBuffer),
                                     (ARRAY_SIZE(renderer->vertexBufferPool.memoryPages) + 1) * MAX_VERTEX_BUFFERS_PER_PAGE + 1);
    VK_RUNTIME_ASSERT(newRing);

    // Create single vertex buffer.
    VkBufferCreateInfo bufferInfo = {
            .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
            .size = VERTEX_BUFFER_SIZE,
            .usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
            .sharingMode = VK_SHARING_MODE_EXCLUSIVE
    };
    TrackedVKVertexBuffer tempBuffer = {
            .timestamp = 0,
            .value.buffer = VK_NULL_HANDLE
    };
    VK_IF_ERROR(device->vkCreateBuffer(device->handle, &bufferInfo, NULL, &tempBuffer.value.buffer)) VK_UNHANDLED_ERROR();
    RING_BUFFER_PUSH(newRing, tempBuffer);

    // Check memory requirements. We aim to create MAX_VERTEX_BUFFERS_PER_PAGE buffers,
    // but due to implementation-specific alignment requirements this number can be lower (unlikely though).
    VKMemoryRequirements requirements = VKAllocator_BufferRequirements(alloc, tempBuffer.value.buffer);
    VKAllocator_PadToAlignment(&requirements); // Align for array-like allocation.
    VkDeviceSize bufferSize = requirements.requirements.memoryRequirements.size;
    uint32_t bufferCount = VERTEX_BUFFER_PAGE_SIZE / bufferSize;
    requirements.requirements.memoryRequirements.size = VERTEX_BUFFER_PAGE_SIZE;
    // Find memory type.
    VKAllocator_FindMemoryType(&requirements,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT, VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    VKAllocator_FindMemoryType(&requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, VK_ALL_MEMORY_PROPERTIES);
    if (requirements.memoryType == VK_NO_MEMORY_TYPE) VK_UNHANDLED_ERROR();

    // Allocate new memory page.
    VKMemory page = VKAllocator_Allocate(&requirements);
    void* data = VKAllocator_Map(alloc, page);
    VkMappedMemoryRange range = VKAllocator_GetMemoryRange(alloc, page);

    // Create remaining buffers and bind memory.
    for (uint32_t i = 0;;) {
        VKVertexBuffer* b = &newRing[i].value;
        b->memory = range.memory;
        b->offset = range.offset + bufferSize * i;
        b->data = (void*) (((uint8_t*) data) + bufferSize * i);
        VK_IF_ERROR(device->vkBindBufferMemory(device->handle, b->buffer, b->memory, b->offset)) VK_UNHANDLED_ERROR();
        if ((++i) >= bufferCount) break;
        VK_IF_ERROR(device->vkCreateBuffer(device->handle, &bufferInfo, NULL, &tempBuffer.value.buffer)) VK_UNHANDLED_ERROR();
        RING_BUFFER_PUSH(newRing, tempBuffer);
    }

    // Move existing pending buffers into new ring and update vertex pool state.
    for (;;) {
        TrackedVKVertexBuffer* t = RING_BUFFER_PEEK(renderer->vertexBufferPool.pendingBuffers);
        if (t == NULL) break;
        RING_BUFFER_PUSH(newRing, *t);
        RING_BUFFER_POP(renderer->vertexBufferPool.pendingBuffers);
    }
    RING_BUFFER_FREE(renderer->vertexBufferPool.pendingBuffers);
    renderer->vertexBufferPool.pendingBuffers = newRing;
    ARRAY_PUSH_BACK(renderer->vertexBufferPool.memoryPages, page);
    J2dRlsTraceLn3(J2D_TRACE_INFO, "VKRenderer_GetVertexBuffer: allocated new page, bufferCount=%d, unusedSpace=%d, totalPages=%d",
                   bufferCount, VERTEX_BUFFER_PAGE_SIZE - bufferCount * bufferSize, ARRAY_SIZE(renderer->vertexBufferPool.memoryPages));

    // Take first.
    tempBuffer = *RING_BUFFER_PEEK(renderer->vertexBufferPool.pendingBuffers);
    RING_BUFFER_POP(renderer->vertexBufferPool.pendingBuffers);
    return tempBuffer.value;
}

static VkSemaphore VKRenderer_AddPendingSemaphore(VKRenderer* renderer) {
    VKDevice* device = renderer->device;
    VkSemaphore semaphore = VK_NULL_HANDLE;
    POP_PENDING(renderer, renderer->pendingSemaphores, semaphore);
    if (semaphore == VK_NULL_HANDLE) {
        VkSemaphoreCreateInfo createInfo = {
                .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
                .flags = 0
        };
        VK_IF_ERROR(device->vkCreateSemaphore(device->handle, &createInfo, NULL, &semaphore)) return VK_NULL_HANDLE;
    }
    PUSH_PENDING(renderer, renderer->pendingSemaphores, semaphore);
    return semaphore;
}

static void VKRenderer_Wait(VKRenderer* renderer, uint64_t timestamp) {
    if (renderer->readTimestamp >= timestamp) return;
    VKDevice* device = renderer->device;
    VkSemaphoreWaitInfo semaphoreWaitInfo = {
            .sType = VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO,
            .flags = 0,
            .semaphoreCount = 1,
            .pSemaphores = &renderer->timelineSemaphore,
            .pValues = &timestamp
    };
    VK_IF_ERROR(device->vkWaitSemaphores(device->handle, &semaphoreWaitInfo, -1)) {
    } else {
        // On success, update last known timestamp.
        renderer->readTimestamp = timestamp;
    }
}

void VKRenderer_Sync(VKRenderer* renderer) {
    // Wait for latest checkpoint to be hit by GPU.
    // This only affects commands performed by this renderer, unlike vkDeviceWaitIdle.
    VKRenderer_Wait(renderer, renderer->writeTimestamp - 1);
}

VKRenderer* VKRenderer_Create(VKDevice* device) {
    VKRenderer* renderer = calloc(1, sizeof(VKRenderer));
    VK_RUNTIME_ASSERT(renderer);

    renderer->pipelineContext = VKPipelines_CreateContext(device);
    if (renderer->pipelineContext == NULL) {
        VKRenderer_Destroy(renderer);
        return NULL;
    }

    // Create command pool
    // TODO we currently have single command pool with VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT
    //      we may need to consider having multiple pools to avoid resetting buffers one-by-one
    VkCommandPoolCreateInfo poolInfo = {
            .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
            .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
            .queueFamilyIndex = device->queueFamily
    };
    VK_IF_ERROR(device->vkCreateCommandPool(device->handle, &poolInfo, NULL, &renderer->commandPool)) {
        VKRenderer_Destroy(renderer);
        return NULL;
    }

    // Create timeline semaphore
    VkSemaphoreTypeCreateInfo semaphoreTypeCreateInfo = {
            .sType = VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO,
            .semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE,
            .initialValue = 0
    };
    VkSemaphoreCreateInfo semaphoreCreateInfo = {
            .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
            .pNext = &semaphoreTypeCreateInfo,
            .flags = 0
    };
    VK_IF_ERROR(device->vkCreateSemaphore(device->handle, &semaphoreCreateInfo, NULL, &renderer->timelineSemaphore)) {
        VKRenderer_Destroy(renderer);
        return NULL;
    }

    renderer->readTimestamp = 0;
    renderer->writeTimestamp = 1;
    renderer->device = device;

    J2dRlsTraceLn1(J2D_TRACE_INFO, "VKRenderer_Create: renderer=%p", renderer);
    return renderer;
}

void VKRenderer_Destroy(VKRenderer* renderer) {
    if (renderer == NULL) return;
    VKDevice* device = renderer->device;
    VKRenderer_Sync(renderer);
    // TODO Ensure all surface render passes are released, so that no resources got stuck there.
    //      We can just form a linked list from all render passes to have access to them from the renderer.
    VKPipelines_DestroyContext(renderer->pipelineContext);

    // Release tracked resources.
    // No need to destroy command buffers one by one, we will destroy the pool anyway.
    RING_BUFFER_FREE(renderer->pendingCommandBuffers);
    RING_BUFFER_FREE(renderer->pendingSecondaryCommandBuffers);
    for (;;) {
        VkSemaphore semaphore = VK_NULL_HANDLE;
        POP_PENDING(renderer, renderer->pendingSemaphores, semaphore);
        if (semaphore == VK_NULL_HANDLE) break;
        device->vkDestroySemaphore(device->handle, semaphore, NULL);
    }
    RING_BUFFER_FREE(renderer->pendingSemaphores);

    // Release vertex pool.
    for (;;) {
        VKVertexBuffer buffer = { .buffer = VK_NULL_HANDLE };
        POP_PENDING(renderer, renderer->vertexBufferPool.pendingBuffers, buffer);
        if (buffer.buffer == VK_NULL_HANDLE) break;
        device->vkDestroyBuffer(device->handle, buffer.buffer, NULL);
    }
    RING_BUFFER_FREE(renderer->vertexBufferPool.pendingBuffers);
    for (uint32_t i = 0; i < ARRAY_SIZE(renderer->vertexBufferPool.memoryPages); i++) {
        VKAllocator_Free(renderer->device->allocator, renderer->vertexBufferPool.memoryPages[i]);
    }
    ARRAY_FREE(renderer->vertexBufferPool.memoryPages);

    device->vkDestroySemaphore(device->handle, renderer->timelineSemaphore, NULL);
    device->vkDestroyCommandPool(device->handle, renderer->commandPool, NULL);
    ARRAY_FREE(renderer->wait.semaphores);
    ARRAY_FREE(renderer->wait.stages);
    ARRAY_FREE(renderer->pendingPresentation.swapchains);
    ARRAY_FREE(renderer->pendingPresentation.indices);
    ARRAY_FREE(renderer->pendingPresentation.results);
    J2dRlsTraceLn1(J2D_TRACE_INFO, "VKRenderer_Destroy(%p)", renderer);
    free(renderer);
}

/**
 * Record commands into primary command buffer (outside of a render pass).
 * Recorded commands will be sent for execution via VKRenderer_Flush.
 */
static VkCommandBuffer VKRenderer_Record(VKRenderer* renderer) {
    if (renderer->commandBuffer != VK_NULL_HANDLE) {
        return renderer->commandBuffer;
    }
    VKDevice* device = renderer->device;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    POP_PENDING(renderer, renderer->pendingCommandBuffers, commandBuffer);
    if (commandBuffer == VK_NULL_HANDLE) {
        VkCommandBufferAllocateInfo allocInfo = {
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                .commandPool = renderer->commandPool,
                .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
                .commandBufferCount = 1
        };
        VK_IF_ERROR(renderer->device->vkAllocateCommandBuffers(renderer->device->handle, &allocInfo, &commandBuffer)) {
            return VK_NULL_HANDLE;
        }
    }
    VkCommandBufferBeginInfo commandBufferBeginInfo = {
            .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
            .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
    };
    VK_IF_ERROR(device->vkBeginCommandBuffer(commandBuffer, &commandBufferBeginInfo)) {
        renderer->device->vkFreeCommandBuffers(renderer->device->handle, renderer->commandPool, 1, &commandBuffer);
        return VK_NULL_HANDLE;
    }
    renderer->commandBuffer = commandBuffer;
    J2dRlsTraceLn1(J2D_TRACE_VERBOSE, "VKRenderer_Record(%p): started", renderer);
    return commandBuffer;
}

void VKRenderer_Flush(VKRenderer* renderer) {
    if (renderer == NULL) return;
    VKDevice* device = renderer->device;
    size_t pendingPresentations = ARRAY_SIZE(renderer->pendingPresentation.swapchains);

    // Submit pending command buffer and semaphores.
    // Even if there are no commands to be sent, we can submit pending semaphores for presentation synchronization.
    if (renderer->commandBuffer != VK_NULL_HANDLE) {
        VK_IF_ERROR(device->vkEndCommandBuffer(renderer->commandBuffer)) VK_UNHANDLED_ERROR();
        PUSH_PENDING(renderer, renderer->pendingCommandBuffers, renderer->commandBuffer);
    } else if (pendingPresentations == 0) {
        return;
    }
    uint64_t signalSemaphoreValues[] = {renderer->writeTimestamp, 0};
    renderer->writeTimestamp++;
    VkSemaphore semaphores[] = {
            renderer->timelineSemaphore,
            // We add a presentation semaphore after timestamp increment, so it will be released one step later
            pendingPresentations > 0 ? VKRenderer_AddPendingSemaphore(renderer) : VK_NULL_HANDLE
    };
    VkTimelineSemaphoreSubmitInfo timelineSemaphoreSubmitInfo = {
            .sType = VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO,
            .waitSemaphoreValueCount = 0,
            .pWaitSemaphoreValues = NULL,
            .signalSemaphoreValueCount = pendingPresentations > 0 ? 2 : 1,
            .pSignalSemaphoreValues = signalSemaphoreValues
    };
    VkSubmitInfo submitInfo = {
            .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
            .pNext = &timelineSemaphoreSubmitInfo,
            .waitSemaphoreCount = ARRAY_SIZE(renderer->wait.semaphores),
            .pWaitSemaphores = renderer->wait.semaphores,
            .pWaitDstStageMask = renderer->wait.stages,
            .commandBufferCount = renderer->commandBuffer != VK_NULL_HANDLE ? 1 : 0,
            .pCommandBuffers = &renderer->commandBuffer,
            .signalSemaphoreCount = pendingPresentations > 0 ? 2 : 1,
            .pSignalSemaphores = semaphores
    };
    VK_IF_ERROR(device->vkQueueSubmit(device->queue, 1, &submitInfo, VK_NULL_HANDLE)) VK_UNHANDLED_ERROR();
    renderer->commandBuffer = VK_NULL_HANDLE;
    ARRAY_RESIZE(renderer->wait.semaphores, 0);
    ARRAY_RESIZE(renderer->wait.stages, 0);

    // Present pending swapchains
    if (pendingPresentations > 0) {
        ARRAY_RESIZE(renderer->pendingPresentation.results, pendingPresentations);
        VkPresentInfoKHR presentInfo = {
                .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
                .waitSemaphoreCount = 1,
                .pWaitSemaphores = &semaphores[1],
                .swapchainCount = pendingPresentations,
                .pSwapchains = renderer->pendingPresentation.swapchains,
                .pImageIndices = renderer->pendingPresentation.indices,
                .pResults = renderer->pendingPresentation.results
        };
        VkResult presentResult = device->vkQueuePresentKHR(device->queue, &presentInfo);
        if (presentResult != VK_SUCCESS) {
            // TODO check individual result codes in renderer->pendingPresentation.results
            // TODO possible suboptimal conditions
            VK_IF_ERROR(presentResult) {}
        }
        ARRAY_RESIZE(renderer->pendingPresentation.swapchains, 0);
        ARRAY_RESIZE(renderer->pendingPresentation.indices, 0);
    }
    J2dRlsTraceLn3(J2D_TRACE_VERBOSE, "VKRenderer_Flush(%p): buffers=%d, presentations=%d",
                   renderer, submitInfo.commandBufferCount, pendingPresentations);
}

/**
 * Prepare barrier info to be executed in batch, if needed.
 */
static void VKRenderer_AddSurfaceBarrier(VkImageMemoryBarrier* barriers, uint32_t* numBarriers,
                                         VkPipelineStageFlags* srcStages, VkPipelineStageFlags* dstStages,
                                         VKSDOps* surface, VkPipelineStageFlags stage, VkAccessFlags access, VkImageLayout layout) {
    assert(surface->image.handle != VK_NULL_HANDLE);
    // TODO Even if stage, access and layout didn't change, we may still need a barrier against WaW hazard.
    if (stage != surface->renderPass->lastStage || access != surface->renderPass->lastAccess || layout != surface->renderPass->layout) {
        barriers[*numBarriers] = (VkImageMemoryBarrier) {
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .srcAccessMask = surface->renderPass->lastAccess,
                .dstAccessMask = access,
                .oldLayout = surface->renderPass->layout,
                .newLayout = layout,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = surface->image.handle,
                .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 }
        };
        (*numBarriers)++;
        (*srcStages) |= surface->renderPass->lastStage;
        (*dstStages) |= stage;
        surface->renderPass->lastStage = stage;
        surface->renderPass->lastAccess = access;
        surface->renderPass->layout = layout;
    }
}

/**
 * Execute single barrier, if needed.
 */
static void VKRenderer_SurfaceBarrier(VKSDOps* surface, VkPipelineStageFlags stage, VkAccessFlags access, VkImageLayout layout) {
    VkImageMemoryBarrier barrier;
    uint32_t numBarriers = 0;
    VkPipelineStageFlags srcStages = 0, dstStages = 0;
    VKRenderer_AddSurfaceBarrier(&barrier, &numBarriers, &srcStages, &dstStages, surface, stage, access, layout);
    if (numBarriers == 1) {
        VKDevice* device = surface->device;
        VKRenderer* renderer = device->renderer;
        VkCommandBuffer cb = VKRenderer_Record(renderer);
        device->vkCmdPipelineBarrier(cb, srcStages, dstStages, 0, 0, NULL, 0, NULL, 1, &barrier);
    }
}

/**
 * Record draw command, if there are any pending vertices in the vertex buffer
 */
static void VKRenderer_FlushDraw(VKSDOps* surface) {
    assert(surface != NULL && surface->renderPass != NULL);
    if (surface->renderPass->vertexCount > 0) {
        assert(surface->renderPass->pendingCommands);
        surface->device->vkCmdDraw(surface->renderPass->commandBuffer, surface->renderPass->vertexCount, 1, surface->renderPass->firstVertex, 0);
        surface->renderPass->firstVertex += surface->renderPass->vertexCount;
        surface->renderPass->vertexCount = 0;
    }
}

/**
 * Flush vertex buffer writes, push vertex buffers to the pending queue, reset drawing state for the surface.
 */
static void VKRenderer_ResetDrawing(VKSDOps* surface) {
    assert(surface != NULL && surface->renderPass != NULL);
    surface->renderPass->currentComposite = NO_COMPOSITE;
    surface->renderPass->currentPipeline = NO_PIPELINE;
    surface->renderPass->vertexBufferData = NULL;
    surface->renderPass->vertexBufferOffset = VERTEX_BUFFER_SIZE;
    surface->renderPass->firstVertex = surface->renderPass->vertexCount = 0;
    size_t vertexBufferCount = ARRAY_SIZE(surface->renderPass->vertexBuffers);
    if (vertexBufferCount == 0) return;
    VkMappedMemoryRange memoryRanges[vertexBufferCount];
    for (uint32_t i = 0; i < vertexBufferCount; i++) {
        VKVertexBuffer* vb = &surface->renderPass->vertexBuffers[i];
        memoryRanges[i] = (VkMappedMemoryRange) {
                .sType  = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
                .memory = vb->memory,
                .offset = vb->offset,
                .size   = VERTEX_BUFFER_SIZE
        };
        PUSH_PENDING(surface->device->renderer, surface->device->renderer->vertexBufferPool.pendingBuffers, *vb);
    }
    ARRAY_RESIZE(surface->renderPass->vertexBuffers, 0);
    VK_IF_ERROR(surface->device->vkFlushMappedMemoryRanges(surface->device->handle, vertexBufferCount, memoryRanges)) {}
}

/**
 * Discard all recorded commands for the render pass.
 */
static void VKRenderer_DiscardRenderPass(VKSDOps* surface) {
    assert(surface != NULL && surface->renderPass != NULL);
    if (surface->renderPass->pendingCommands) {
        assert(surface->device != NULL);
        VK_IF_ERROR(surface->device->vkResetCommandBuffer(surface->renderPass->commandBuffer, 0)) VK_UNHANDLED_ERROR();
        surface->renderPass->pendingCommands = VK_FALSE;
        VKRenderer_ResetDrawing(surface);
        J2dRlsTraceLn1(J2D_TRACE_VERBOSE, "VKRenderer_DiscardRenderPass(%p)", surface);
    }
}

void VKRenderer_DestroyRenderPass(VKSDOps* surface) {
    assert(surface != NULL);
    if (surface->renderPass == NULL) return;
    VKDevice* device = surface->device;
    if (device != NULL && device->renderer != NULL) {
        // Wait while surface resources are being used by the device.
        VKRenderer_Wait(device->renderer, surface->renderPass->lastTimestamp);
        VKRenderer_DiscardRenderPass(surface);
        // Release resources.
        DESTROY_FORMAT_ALIASED_HANDLE(surface->renderPass->framebuffer, i) {
            device->vkDestroyFramebuffer(device->handle, surface->renderPass->framebuffer[i], NULL);
        }
        if (surface->renderPass->commandBuffer != VK_NULL_HANDLE) {
            PUSH_PENDING(device->renderer, device->renderer->pendingSecondaryCommandBuffers, surface->renderPass->commandBuffer);
        }
        ARRAY_FREE(surface->renderPass->vertexBuffers);
    }
    free(surface->renderPass);
    surface->renderPass = NULL;
    J2dRlsTraceLn1(J2D_TRACE_VERBOSE, "VKRenderer_DestroyRenderPass(%p)", surface);
}

/**
 * Initialize surface and render pass state.
 * It may execute pending resize request and re-initialize surface resources,
 * so it must only be called between frames.
 */
static VkBool32 VKRenderer_InitRenderPass(VKSDOps* surface) {
    assert(surface != NULL && (surface->renderPass == NULL || !surface->renderPass->pendingCommands));

    // Initialize surface image.
    if (!VKSD_ConfigureImageSurface(surface)) return VK_FALSE;

    if (surface->renderPass != NULL) return VK_TRUE;

    VKDevice* device = surface->device;
    VKRenderer* renderer = device->renderer;
    VKRenderPass* renderPass = surface->renderPass = malloc(sizeof(VKRenderPass));
    VK_RUNTIME_ASSERT(renderPass);
    (*renderPass) = (VKRenderPass) {
            .pendingCommands = VK_FALSE,
            .pendingClear = VK_TRUE, // Clear the surface by default
            .vertexBufferOffset = VERTEX_BUFFER_SIZE,
            .currentComposite = NO_COMPOSITE,
            .currentPipeline = NO_PIPELINE,
            .layout = VK_IMAGE_LAYOUT_UNDEFINED,
            .lastStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            .lastAccess = 0,
            .lastTimestamp = 0
    };

    // Initialize pipelines. They are cached until surface format changes.
    if (renderPass->context == NULL) {
        renderPass->context = VKPipelines_GetRenderPassContext(renderer->pipelineContext, surface->format[FORMAT_ALIAS_REAL]);
    }

    // Initialize framebuffer.
    if (renderPass->framebuffer[FORMAT_ALIAS_REAL] == VK_NULL_HANDLE) {
        VkFramebufferCreateInfo framebufferCreateInfo = {
                .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
                .attachmentCount = 1,
                .width = surface->extent.width,
                .height = surface->extent.height,
                .layers = 1
        };
        INIT_FORMAT_ALIASED_HANDLE(renderPass->framebuffer, surface->view, i) {
            framebufferCreateInfo.renderPass = renderPass->context->renderPass[i];
            framebufferCreateInfo.pAttachments = &surface->view[i];
            VK_IF_ERROR(device->vkCreateFramebuffer(device->handle, &framebufferCreateInfo, NULL,
                                                    &renderPass->framebuffer[i])) VK_UNHANDLED_ERROR();
        }
    }

    J2dRlsTraceLn1(J2D_TRACE_VERBOSE, "VKRenderer_InitRenderPass(%p)", surface);
    return VK_TRUE;
}

/**
 * Begin render pass for the surface.
 */
static void VKRenderer_BeginRenderPass(VKSDOps* surface) {
    assert(surface != NULL && surface->renderPass != NULL && !surface->renderPass->pendingCommands);
    // We may have a pending flush, which is already obsolete.
    surface->renderPass->pendingFlush = VK_FALSE;
    VKDevice* device = surface->device;
    VKRenderer* renderer = device->renderer;

    // Initialize command buffer.
    VkCommandBuffer commandBuffer = surface->renderPass->commandBuffer;
    if (commandBuffer == VK_NULL_HANDLE) {
        POP_PENDING(renderer, renderer->pendingSecondaryCommandBuffers, commandBuffer);
        if (commandBuffer == VK_NULL_HANDLE) {
            VkCommandBufferAllocateInfo allocInfo = {
                    .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                    .commandPool = renderer->commandPool,
                    .level = VK_COMMAND_BUFFER_LEVEL_SECONDARY,
                    .commandBufferCount = 1
            };
            VK_IF_ERROR(renderer->device->vkAllocateCommandBuffers(renderer->device->handle, &allocInfo, &commandBuffer)) {
                VK_UNHANDLED_ERROR();
            }
        }
        surface->renderPass->commandBuffer = commandBuffer;
    }

    // Begin recording render pass commands.
    FormatAlias formatAlias = COMPOSITE_TO_FORMAT_ALIAS(surface->renderPass->currentComposite);
    VkCommandBufferInheritanceInfo inheritanceInfo = {
            .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_INHERITANCE_INFO,
            .renderPass = surface->renderPass->context->renderPass[formatAlias],
            .subpass = 0,
            .framebuffer = surface->renderPass->framebuffer[formatAlias]
    };
    VkCommandBufferBeginInfo commandBufferBeginInfo = {
            .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
            .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT | VK_COMMAND_BUFFER_USAGE_RENDER_PASS_CONTINUE_BIT,
            .pInheritanceInfo = &inheritanceInfo
    };
    VK_IF_ERROR(device->vkBeginCommandBuffer(commandBuffer, &commandBufferBeginInfo)) {
        renderer->device->vkFreeCommandBuffers(renderer->device->handle, renderer->commandPool, 1, &commandBuffer);
        VK_UNHANDLED_ERROR();
    }

    if (surface->renderPass->pendingClear) {
        VkClearAttachment clearAttachment = {
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .colorAttachment = 0,
                .clearValue = surface->background.vkClearValue
        };
        VkClearRect clearRect = {
                .rect = {{0, 0}, surface->extent},
                .baseArrayLayer = 0,
                .layerCount = 1
        };
        device->vkCmdClearAttachments(commandBuffer, 1, &clearAttachment, 1, &clearRect);
        surface->renderPass->pendingClear = VK_FALSE;
    }

    // Set viewport and scissor.
    VkViewport viewport = {
            .x = 0.0f,
            .y = 0.0f,
            .width = (float) surface->extent.width,
            .height = (float) surface->extent.height,
            .minDepth = 0.0f,
            .maxDepth = 1.0f
    };
    VkRect2D scissor = {{0, 0}, surface->extent};
    device->vkCmdSetViewport(commandBuffer, 0, 1, &viewport);
    device->vkCmdSetScissor(commandBuffer, 0, 1, &scissor);
    // Calculate inverse viewport for vertex shader.
    viewport.width = 2.0f / viewport.width;
    viewport.height = 2.0f / viewport.height;
    device->vkCmdPushConstants(
            commandBuffer,
            renderer->pipelineContext->pipelineLayout,
            VK_SHADER_STAGE_VERTEX_BIT,
            0,
            sizeof(float) * 2,
            &viewport.width
    );

    surface->renderPass->pendingCommands = VK_TRUE;
    J2dRlsTraceLn1(J2D_TRACE_VERBOSE, "VKRenderer_BeginRenderPass(%p)", surface);
}

/**
 * End render pass for the surface and record it into the primary command buffer,
 * which will be executed on the next VKRenderer_Flush.
 */
static void VKRenderer_FlushRenderPass(VKSDOps* surface) {
    assert(surface != NULL && surface->renderPass != NULL);
    VKRenderer_FlushDraw(surface);
    VkBool32 hasCommands = surface->renderPass->pendingCommands, clear = surface->renderPass->pendingClear;
    if(!hasCommands && !clear) return;
    VKDevice* device = surface->device;
    VKRenderer* renderer = device->renderer;
    surface->renderPass->lastTimestamp = renderer->writeTimestamp;
    VkCommandBuffer cb = VKRenderer_Record(renderer);

    // Insert barrier to prepare surface for rendering.
    VKRenderer_SurfaceBarrier(surface, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                              VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                              VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
    // Begin render pass.
    FormatAlias formatAlias = COMPOSITE_TO_FORMAT_ALIAS(surface->renderPass->currentComposite);
    VkRenderPassBeginInfo renderPassInfo = {
            .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
            .renderPass = surface->renderPass->context->renderPass[formatAlias],
            .framebuffer = surface->renderPass->framebuffer[formatAlias],
            .renderArea.offset = (VkOffset2D){0, 0},
            .renderArea.extent = surface->extent,
            .clearValueCount = 0,
            .pClearValues = NULL
    };
    device->vkCmdBeginRenderPass(cb, &renderPassInfo, VK_SUBPASS_CONTENTS_SECONDARY_COMMAND_BUFFERS);
    // If there is a pending clear, record it into render pass.
    if (clear) VKRenderer_BeginRenderPass(surface);

    // Execute render pass commands.
    if (surface->renderPass->pendingCommands) {
        surface->renderPass->pendingCommands = VK_FALSE;
        VK_IF_ERROR(device->vkEndCommandBuffer(surface->renderPass->commandBuffer)) VK_UNHANDLED_ERROR();
        device->vkCmdExecuteCommands(cb, 1, &surface->renderPass->commandBuffer);
        PUSH_PENDING(renderer, renderer->pendingSecondaryCommandBuffers, surface->renderPass->commandBuffer);
        surface->renderPass->commandBuffer = VK_NULL_HANDLE;
    }

    device->vkCmdEndRenderPass(cb);
    VKRenderer_ResetDrawing(surface);
    J2dRlsTraceLn3(J2D_TRACE_VERBOSE, "VKRenderer_FlushRenderPass(%p): hasCommands=%d, clear=%d", surface, hasCommands, clear);
}

void VKRenderer_FlushSurface(VKSDOps* surface) {
    assert(surface != NULL);
    // If pendingFlush is TRUE, pendingCommands must be FALSE
    assert(surface->renderPass == NULL || !surface->renderPass->pendingFlush || !surface->renderPass->pendingCommands);
    // Note that we skip render pass initialization, if we have pending flush,
    // which means that we missed the last flush, but didn't start a new render pass yet.
    // So now we are going to catch up the last frame, and don't need reconfiguration.
    // We also skip initialization if we have pending commands, because that means we are in the middle of frame.
    if (surface->renderPass == NULL || (!surface->renderPass->pendingCommands && !surface->renderPass->pendingFlush)) {
        if (!VKRenderer_InitRenderPass(surface)) return;
        // Check for pendingClear after VKRenderer_InitRenderPass, it may be set after reconfiguration.
        if (!surface->renderPass->pendingClear) return;
    }

    surface->renderPass->pendingFlush = VK_FALSE;
    VKRenderer_FlushRenderPass(surface);

    // If this is a swapchain surface, we need to blit the content onto it and queue it for presentation.
    if (surface->drawableType == VKSD_WINDOW) {
        VKWinSDOps* win = (VKWinSDOps*) surface;

        // Configure window surface.
        if (!VKSD_ConfigureWindowSurface(win)) {
            // Surface is not ready, try again later.
            surface->renderPass->pendingFlush = VK_TRUE;
            return;
        }

        VKDevice* device = surface->device;
        VKRenderer* renderer = device->renderer;
        surface->renderPass->lastTimestamp = renderer->writeTimestamp;
        VkCommandBuffer cb = VKRenderer_Record(renderer);

        // Acquire swapchain image.
        VkSemaphore acquireSemaphore = VKRenderer_AddPendingSemaphore(renderer);
        ARRAY_PUSH_BACK(renderer->wait.semaphores, acquireSemaphore);
        ARRAY_PUSH_BACK(renderer->wait.stages, VK_PIPELINE_STAGE_TRANSFER_BIT); // Acquire image before blitting content onto swapchain

        uint32_t imageIndex;
        VkResult acquireImageResult = device->vkAcquireNextImageKHR(device->handle, win->swapchain, UINT64_MAX,
                                                                    acquireSemaphore, VK_NULL_HANDLE, &imageIndex);
        if (acquireImageResult != VK_SUCCESS) {
            // TODO possible suboptimal conditions
            VK_IF_ERROR(acquireImageResult) {}
        }

        // Insert barriers to prepare both main (src) and swapchain (dst) images for blit.
        {
            VkImageMemoryBarrier barriers[2] =
                    {{
                             .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                             .srcAccessMask = 0,
                             .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                             .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                             .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                             .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                             .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                             .image = win->swapchainImages[imageIndex],
                             .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 }
                     }};
            uint32_t numBarriers = 1;
            VkPipelineStageFlags srcStages = surface->renderPass->lastStage, dstStages = VK_PIPELINE_STAGE_TRANSFER_BIT;
            VKRenderer_AddSurfaceBarrier(barriers, &numBarriers, &srcStages, &dstStages, surface,
                                         VK_PIPELINE_STAGE_TRANSFER_BIT,
                                         VK_ACCESS_TRANSFER_READ_BIT,
                                         VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            device->vkCmdPipelineBarrier(cb, srcStages, dstStages, 0, 0, NULL, 0, NULL, numBarriers, barriers);
        }

        // Do blit.
        VkImageBlit blit = {
                .srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 },
                .srcOffsets[0] = {0, 0, 0},
                .srcOffsets[1] = {(int)surface->extent.width, (int)surface->extent.height, 1},
                .dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 },
                .dstOffsets[0] = {0, 0, 0},
                .dstOffsets[1] = {(int)surface->extent.width, (int)surface->extent.height, 1},
        };
        device->vkCmdBlitImage(cb,
                               surface->image.handle, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                               win->swapchainImages[imageIndex], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                               1, &blit, VK_FILTER_NEAREST);

        // Insert barrier to prepare swapchain image for presentation.
        {
            VkImageMemoryBarrier barrier = {
                    .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                    .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                    .dstAccessMask = 0,
                    .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    .newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                    .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                    .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                    .image = win->swapchainImages[imageIndex],
                    .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 }
            };
            device->vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, 0, NULL, 0, NULL, 1, &barrier);
        }

        // Add pending presentation request.
        ARRAY_PUSH_BACK(renderer->pendingPresentation.swapchains, win->swapchain);
        ARRAY_PUSH_BACK(renderer->pendingPresentation.indices, imageIndex);
        J2dRlsTraceLn1(J2D_TRACE_VERBOSE, "VKRenderer_FlushSurface(%p): queued for presentation", surface);
    }
}

void VKRenderer_ConfigureSurface(VKSDOps* surface, VkExtent2D extent) {
    assert(surface != NULL);
    surface->requestedExtent = extent;
    // We must only do pending flush between frames.
    if (surface->renderPass != NULL && surface->renderPass->pendingFlush)  {
        if (surface->renderPass->pendingCommands) {
            // New frame has already started, reset flag.
            surface->renderPass->pendingFlush = VK_FALSE;
        } else {
            // New frame has not begun yet, flush.
            J2dRlsTraceLn1(J2D_TRACE_VERBOSE, "VKRenderer_ConfigureSurface(%p): pending flush", surface);
            VKRenderer_FlushSurface(surface);
        }
    }
}

/**
 * Allocate vertices from vertex buffer.
 * This function skips pipeline state checks and must only be called after
 * VKRenderer_Draw have been called within the same drawing operation.
 */
static void* VKRenderer_FastDraw(VKSDOps* surface, uint32_t vertices, size_t vertexSize) {
    assert(surface != NULL && surface->renderPass != NULL);
    assert(vertices > 0 && vertexSize > 0);
    assert(vertexSize * vertices <= VERTEX_BUFFER_SIZE);
    VkDeviceSize offset = surface->renderPass->vertexBufferOffset;
    surface->renderPass->vertexBufferOffset += (VkDeviceSize) (vertexSize * vertices);
    // Overflow, need to take another vertex buffer
    if (surface->renderPass->vertexBufferOffset > VERTEX_BUFFER_SIZE) {
        VKRenderer_FlushDraw(surface);
        offset = 0;
        surface->renderPass->vertexBufferOffset = (VkDeviceSize) (vertexSize * vertices);
        surface->renderPass->firstVertex = surface->renderPass->vertexCount = 0;
        VKVertexBuffer buffer = VKRenderer_GetVertexBuffer(surface->device->renderer);
        ARRAY_PUSH_BACK(surface->renderPass->vertexBuffers, buffer);
        surface->renderPass->vertexBufferData = buffer.data;
        const VkDeviceSize zero = 0;
        surface->device->vkCmdBindVertexBuffers(surface->renderPass->commandBuffer, 0, 1, &buffer.buffer, &zero);
    }
    surface->renderPass->vertexCount += vertices;
    return (void*) (((uint8_t*) surface->renderPass->vertexBufferData) + offset);
}

/**
 * Setup pipeline for drawing and allocate vertices from vertex buffer.
 * Can return NULL if surface is not yet ready for drawing.
 * It is responsibility of the caller to pass correct vertexSize, matching provided pipeline.
 * This function cannot draw more vertices than fits into single vertex buffer at once.
 */
static void* VKRenderer_Draw(VKRenderingContext* context, PipelineType pipeline, uint32_t vertices, size_t vertexSize) {
    assert(context != NULL && context->surface != NULL);
    assert(vertices > 0 && vertexSize > 0);
    assert(vertexSize * vertices <= VERTEX_BUFFER_SIZE);
    VKSDOps* surface = context->surface;

    // Validate render pass state.
    if (surface->renderPass == NULL || !surface->renderPass->pendingCommands) {
        // We must only [re]init render pass between frames.
        // Be careful to NOT call VKRenderer_InitRenderPass between render passes within single frame.
        if (!VKRenderer_InitRenderPass(surface)) return NULL;
    }
    VKRenderPass* renderPass = surface->renderPass;

    // Validate current composite.
    if (renderPass->currentComposite != context->composite) {
        // ALPHA_COMPOSITE_DST keeps destination intact, so don't even bother to change the state.
        if (context->composite == ALPHA_COMPOSITE_DST) return NULL;
        J2dTraceLn2(J2D_TRACE_VERBOSE, "VKRenderer_Draw: updating composite, old=%d, new=%d", surface->renderPass->currentComposite, context->composite);
        VKRenderer_FlushDraw(surface);
        if (renderPass->currentComposite != NO_COMPOSITE) {
            // When required view format changes, we need to restart the render pass.
            // Or, if the format alias changes, but actual view format does not,
            // we need to reset the pipeline at the very least.
            // Formally this has nothing to do with format alias, but changed format alias
            // means that composite group was changed between logic and alpha, which in turn means
            // that we must reset the pipeline, as we don't support dynamic logicOp changing.
            // This could change if we added more logic composites, so be careful.
            FormatAlias oldFormatAlias = COMPOSITE_TO_FORMAT_ALIAS(renderPass->currentComposite);
            FormatAlias newFormatAlias = COMPOSITE_TO_FORMAT_ALIAS(context->composite);
            if (oldFormatAlias != newFormatAlias) {
                if (surface->view[oldFormatAlias] != surface->view[newFormatAlias]) {
                    VKRenderer_FlushRenderPass(surface);
                    assert(surface->renderPass->currentComposite == NO_COMPOSITE); // currentComposite must have been reset.
                    assert(surface->renderPass->currentPipeline == NO_PIPELINE); // currentPipeline must have been reset.
                } else renderPass->currentPipeline = NO_PIPELINE;
            }
        }
        // If composite is not set, start render pass.
        if (renderPass->currentComposite == NO_COMPOSITE) {
            renderPass->currentComposite = context->composite;
            VKRenderer_BeginRenderPass(surface);
            assert(surface->renderPass->currentPipeline == NO_PIPELINE);
        } else renderPass->currentComposite = context->composite;
        VKDevice* device = surface->device;
        VkCommandBuffer cb = renderPass->commandBuffer;
        // Reset the pipeline.
        renderPass->currentPipeline = NO_PIPELINE;
    }

    // Validate current pipeline.
    if (renderPass->currentPipeline != pipeline) {
        J2dTraceLn2(J2D_TRACE_VERBOSE, "VKRenderer_Draw: updating pipeline, old=%d, new=%d", surface->renderPass->currentPipeline, pipeline);
        VKRenderer_FlushDraw(surface);
        VkCommandBuffer cb = renderPass->commandBuffer;
        renderPass->currentPipeline = pipeline;
        surface->device->vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                           VKPipelines_GetPipeline(renderPass->context, context->composite, pipeline));

        VkDeviceSize offset = renderPass->vertexBufferOffset;
        void* oldData = renderPass->vertexBufferData;
        void* ptr = VKRenderer_FastDraw(surface, vertices, vertexSize);
        // If vertex buffer was not bound by VKRenderer_FastDraw, do it here.
        if (oldData == renderPass->vertexBufferData) {
            assert(ARRAY_SIZE(surface->renderPass->vertexBuffers) > 0);
            surface->device->vkCmdBindVertexBuffers(renderPass->commandBuffer, 0, 1,
                                                    &(ARRAY_LAST(renderPass->vertexBuffers).buffer), &offset);
            renderPass->firstVertex = 0;
            renderPass->vertexCount = vertices;
        }
        return ptr;
    } else return VKRenderer_FastDraw(surface, vertices, vertexSize);
}

/**
 * Convenience wrapper for VKRenderer_Draw, providing pointer to vertices of requested type.
 */
#define VK_DRAW(VERTEX_TYPE, CONTEXT, PIPELINE, VERTICES) \
VERTEX_TYPE* vs = (VERTEX_TYPE*) VKRenderer_Draw((CONTEXT), (PIPELINE), (VERTICES), sizeof(VERTEX_TYPE))

/**
 * Convenience wrapper for VKRenderer_FastDraw, updating pointer to vertices.
 */
#define VK_FAST_DRAW(CONTEXT, PIPELINE, VERTICES) \
vs = VKRenderer_Draw((CONTEXT), (PIPELINE), (VERTICES), sizeof(vs[0]))

// Drawing operations.

void VKRenderer_RenderRect(VKRenderingContext* context, PipelineType pipeline, jint x, jint y, jint w, jint h) {
    VKRenderer_RenderParallelogram(context, pipeline, (float) x, (float) y, (float) w, 0, 0, (float) h);
}

void VKRenderer_RenderParallelogram(VKRenderingContext* context, PipelineType pipeline,
                                    jfloat x11, jfloat y11,
                                    jfloat dx21, jfloat dy21,
                                    jfloat dx12, jfloat dy12) {
    VK_DRAW(VKColorVertex, context, pipeline, pipeline == PIPELINE_DRAW_COLOR ? 8 : 6);
    if (vs == NULL) return; // Not ready.
    Color c = context->color;
    /*                   dx21
     *    (p1)---------(p2) |          (p1)------
     *     |\            \  |            |  \    dy21
     *     | \            \ |       dy12 |   \
     *     |  \            \|            |   (p2)-
     *     |  (p4)---------(p3)        (p4)   |
     *      dx12                           \  |  dy12
     *                              dy21    \ |
     *                                  -----(p3)
     */
    VKColorVertex p1 = {x11, y11, c};
    VKColorVertex p2 = {x11 + dx21, y11 + dy21, c};
    VKColorVertex p3 = {x11 + dx21 + dx12, y11 + dy21 + dy12, c};
    VKColorVertex p4 = {x11 + dx12, y11 + dy12, c};

    uint32_t i = 0;
    vs[i++] = p1;
    vs[i++] = p2;
    vs[i++] = p3;
    vs[i++] = p4;
    vs[i++] = p1;
    if (pipeline == PIPELINE_DRAW_COLOR) {
        vs[i++] = p4;
        vs[i++] = p2;
    }
    vs[i++] = p3;
}

void VKRenderer_FillSpans(VKRenderingContext* context, jint spanCount, jint *spans) {
    if (spanCount == 0) return;
    VK_DRAW(VKColorVertex, context, PIPELINE_FILL_COLOR, 6);
    if (vs == NULL) return; // Not ready.
    Color c = context->color;

    jfloat x1 = (float)*(spans++);
    jfloat y1 = (float)*(spans++);
    jfloat x2 = (float)*(spans++);
    jfloat y2 = (float)*(spans++);
    VKColorVertex p1 = {x1, y1, c};
    VKColorVertex p2 = {x2, y1, c};
    VKColorVertex p3 = {x2, y2, c};
    VKColorVertex p4 = {x1, y2, c};

    vs[0] = p1; vs[1] = p2; vs[2] = p3; vs[3] = p3; vs[4] = p4; vs[5] = p1;

    for (int i = 1; i < spanCount; i++) {
        p1.x = p4.x = (float)*(spans++);
        p1.y = p2.y = (float)*(spans++);
        p2.x = p3.x = (float)*(spans++);
        p3.y = p4.y = (float)*(spans++);

        VK_FAST_DRAW(context, PIPELINE_FILL_COLOR, 6);
        vs[0] = p1; vs[1] = p2; vs[2] = p3; vs[3] = p3; vs[4] = p4; vs[5] = p1;
    }
}

void VKRenderer_TextureRender(VKRenderingContext* context, VKImage *destImage, VKImage *srcImage,
                              VkBuffer vertexBuffer, uint32_t vertexNum) {
    // TODO make TextureRender work with reused vertex buffers!
//    assert(context != NULL && context->surface != NULL);
//    VKSDOps* surface = (VKSDOps*)context->surface;
//    VkCommandBuffer cb = VKRenderer_Render(surface);
//    VKDevice* device = surface->device;
//
//    // TODO We create a new descriptor set on each command, we'll implement reusing them later.
//    VkDescriptorPoolSize poolSize = {
//            .type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
//            .descriptorCount = 1
//    };
//    VkDescriptorPoolCreateInfo descrPoolInfo = {
//            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
//            .poolSizeCount = 1,
//            .pPoolSizes = &poolSize,
//            .maxSets = 1
//    };
//    VkDescriptorPool descriptorPool;
//    VK_IF_ERROR(device->vkCreateDescriptorPool(device->handle, &descrPoolInfo, NULL, &descriptorPool)) VK_UNHANDLED_ERROR();
//
//    VkDescriptorSetAllocateInfo descrAllocInfo = {
//            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
//            .descriptorPool = descriptorPool,
//            .descriptorSetCount = 1,
//            .pSetLayouts = &device->renderer->pipelineContext->textureDescriptorSetLayout
//    };
//    VkDescriptorSet descriptorSet;
//    VK_IF_ERROR(device->vkAllocateDescriptorSets(device->handle, &descrAllocInfo, &descriptorSet)) VK_UNHANDLED_ERROR();
//
//    VkDescriptorImageInfo imageInfo = {
//            .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
//            .imageView = srcImage->view,
//            .sampler = device->renderer->pipelineContext->linearRepeatSampler
//    };
//
//    VkWriteDescriptorSet descriptorWrites = {
//            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
//            .dstSet = descriptorSet,
//            .dstBinding = 0,
//            .dstArrayElement = 0,
//            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
//            .descriptorCount = 1,
//            .pImageInfo = &imageInfo
//    };
//
//    device->vkUpdateDescriptorSets(device->handle, 1, &descriptorWrites, 0, NULL);
//
//
//    device->vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, VKPipelines_GetPipeline(surface->renderPass->context, PIPELINE_BLIT));
//
//    VkBuffer vertexBuffers[] = {vertexBuffer};
//    VkDeviceSize offsets[] = {0};
//    device->vkCmdBindVertexBuffers(cb, 0, 1, vertexBuffers, offsets);
//    device->vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
//                                    device->renderer->pipelineContext->texturePipelineLayout, 0, 1, &descriptorSet, 0, NULL);
//    device->vkCmdDraw(cb, vertexNum, 1, 0, 0);
}

#endif /* !HEADLESS */
