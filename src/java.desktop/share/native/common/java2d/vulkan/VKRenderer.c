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
#include <string.h>
#include "VKUtil.h"
#include "VKBase.h"
#include "VKAllocator.h"
#include "VKBuffer.h"
#include "VKImage.h"
#include "VKRenderer.h"
#include "VKSurfaceData.h"

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
 * Take an available item from the pool. VAR is left unchanged if there is no available item.
 */
#define POOL_TAKE(RENDERER, NAME, VAR) do {                                                          \
    if (VKRenderer_CheckPoolEntryAvailable((RENDERER), RING_BUFFER_FRONT((RENDERER)->NAME))) {       \
        (VAR) = RING_BUFFER_FRONT((RENDERER)->NAME)->value; RING_BUFFER_POP_FRONT((RENDERER)->NAME); \
    }} while(0)

/**
 * Return an item to the pool. It will only become available again
 * after the next submitted batch of work completes execution on GPU.
 */
// In debug mode resource reuse will be randomly delayed by 3 timestamps in ~20% cases.
#define POOL_RETURN(RENDERER, NAME, VAR) RING_BUFFER_PUSH_BACK((RENDERER)->NAME, \
    (struct PoolEntry_ ## NAME) { .timestamp = (RENDERER)->writeTimestamp + (VK_DEBUG_RANDOM(20)*3), .value = (VAR) })

/**
 * Insert an item into the pool. It is available for POOL_TAKE immediately.
 * This is usually used for bulk insertion of newly created resources.
 */
#define POOL_INSERT(RENDERER, NAME, VAR) RING_BUFFER_PUSH_FRONT((RENDERER)->NAME, \
    (struct PoolEntry_ ## NAME) { .timestamp = 0ULL, .value = (VAR) })

/**
 * Destroy all remaining entries in a pool and free its memory.
 * Intended usage:
 *  POOL_DRAIN_FOR(renderer, poolName, entry) {
 *      destroyEntry(entry->value);
 *  }
 */
#define POOL_DRAIN_FOR(RENDERER, NAME, ENTRY) for (struct PoolEntry_ ## NAME *(ENTRY); VKRenderer_CheckPoolDrain( \
    (RENDERER)->NAME, (ENTRY) = RING_BUFFER_FRONT((RENDERER)->NAME)); RING_BUFFER_POP_FRONT((RENDERER)->NAME))

/**
 * Free pool memory. It doesn't destroy remaining items.
 */
#define POOL_FREE(RENDERER, NAME) RING_BUFFER_FREE((RENDERER)->NAME)

/**
 * Renderer attached to the device.
 */
struct VKRenderer {
    VKDevice*          device;
    VKPipelineContext* pipelineContext;

    POOL(VkCommandBuffer, commandBufferPool);
    POOL(VkCommandBuffer, secondaryCommandBufferPool);
    POOL(VkSemaphore,     semaphorePool);
    POOL(VKBuffer,        vertexBufferPool);
    POOL(VKTexelBuffer,   maskFillBufferPool);
    VKMemory*             bufferMemoryPages;
    VkDescriptorPool*     descriptorPools;

    /**
     * Last known timestamp reached by GPU execution. Resources with equal or less timestamp may be safely reused.
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
 * Rendering-related info attached to the surface.
 */
struct VKRenderPass {
    VKRenderPassContext* context;
    VKBuffer*            vertexBuffers;
    VKTexelBuffer*       maskFillBuffers;
    VkFramebuffer        framebuffer;
    VkCommandBuffer      commandBuffer;

    uint32_t           firstVertex;
    uint32_t           vertexCount;
    BufferWritingState vertexBufferWriting;
    BufferWritingState maskFillBufferWriting;

    VKCompositeMode currentComposite;
    VKPipeline      currentPipeline;
    VkBool32        pendingFlush;
    VkBool32        pendingCommands;
    VkBool32        pendingClear;
    uint64_t        lastTimestamp; // When was this surface last used?
};

/**
 * Helper function for POOL_TAKE macro.
 */
inline VkBool32 VKRenderer_CheckPoolEntryAvailable(VKRenderer* renderer, void* entry) {
    if (entry == NULL) return VK_FALSE;
    uint64_t timestamp = *((uint64_t*) entry);
    return renderer->readTimestamp >= timestamp ||
           (renderer->device->vkGetSemaphoreCounterValue(renderer->device->handle, renderer->timelineSemaphore,
                                                         &renderer->readTimestamp) == VK_SUCCESS &&
                                                         renderer->readTimestamp >= timestamp);
}

/**
 * Helper function for POOL_DRAIN_FOR macro.
 */
static VkBool32 VKRenderer_CheckPoolDrain(void* pool, void* entry) {
    if (entry != NULL) return VK_TRUE;
    else if (pool != NULL) RING_BUFFER_FREE(pool);
    return VK_FALSE;
}

#define VERTEX_BUFFER_SIZE (128 * 1024) // 128KiB - enough to draw 910 quads (6 verts) with VKColorVertex.
#define VERTEX_BUFFER_PAGE_SIZE (1 * 1024 * 1024) // 1MiB - fits 8 buffers.
static void VKRenderer_FindVertexBufferMemoryType(VKMemoryRequirements* requirements) {
    VKAllocator_FindMemoryType(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                               VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    VKAllocator_FindMemoryType(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, VK_ALL_MEMORY_PROPERTIES);
}
static VKBuffer VKRenderer_GetVertexBuffer(VKRenderer* renderer) {
    VKBuffer buffer = { .handle = VK_NULL_HANDLE };
    POOL_TAKE(renderer, vertexBufferPool, buffer);
    if (buffer.handle != VK_NULL_HANDLE) return buffer;
    uint32_t bufferCount = VERTEX_BUFFER_PAGE_SIZE / VERTEX_BUFFER_SIZE;
    VKBuffer buffers[bufferCount];
    VKMemory page = VKBuffer_CreateBuffers(renderer->device, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                                           VKRenderer_FindVertexBufferMemoryType,
                                           VERTEX_BUFFER_SIZE, VERTEX_BUFFER_PAGE_SIZE, &bufferCount, buffers);
    VK_RUNTIME_ASSERT(page);
    ARRAY_PUSH_BACK(renderer->bufferMemoryPages, page);
    for (uint32_t i = 1; i < bufferCount; i++) POOL_INSERT(renderer, vertexBufferPool, buffers[i]);
    return buffers[0];
}

#define MASK_FILL_BUFFER_SIZE (256 * 1024) // 256KiB = 256 typical MASK_FILL tiles
#define MASK_FILL_BUFFER_PAGE_SIZE (4 * 1024 * 1024) // 4MiB - fits 16 buffers
static void VKRenderer_FindMaskFillBufferMemoryType(VKMemoryRequirements* requirements) {
    VKAllocator_FindMemoryType(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                               VK_ALL_MEMORY_PROPERTIES);
    VKAllocator_FindMemoryType(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, VK_ALL_MEMORY_PROPERTIES);
}
static VKTexelBuffer VKRenderer_GetMaskFillBuffer(VKRenderer* renderer) {
    VKTexelBuffer buffer = { .buffer.handle = VK_NULL_HANDLE };
    POOL_TAKE(renderer, maskFillBufferPool, buffer);
    if (buffer.buffer.handle != VK_NULL_HANDLE) return buffer;
    uint32_t bufferCount = MASK_FILL_BUFFER_PAGE_SIZE / MASK_FILL_BUFFER_SIZE;
    VKBuffer buffers[bufferCount];
    VKMemory page = VKBuffer_CreateBuffers(renderer->device, VK_BUFFER_USAGE_STORAGE_TEXEL_BUFFER_BIT,
                                           VKRenderer_FindMaskFillBufferMemoryType,
                                           MASK_FILL_BUFFER_SIZE, MASK_FILL_BUFFER_PAGE_SIZE, &bufferCount, buffers);
    VK_RUNTIME_ASSERT(page);
    VKTexelBuffer texelBuffers[bufferCount];
    VkDescriptorPool descriptorPool = VKBuffer_CreateTexelBuffers(
            renderer->device, VK_FORMAT_R8_UNORM, VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER,
            renderer->pipelineContext->maskFillDescriptorSetLayout, bufferCount, buffers, texelBuffers);
    VK_RUNTIME_ASSERT(descriptorPool);
    for (uint32_t i = 1; i < bufferCount; i++) POOL_INSERT(renderer, maskFillBufferPool, texelBuffers[i]);
    ARRAY_PUSH_BACK(renderer->bufferMemoryPages, page);
    ARRAY_PUSH_BACK(renderer->descriptorPools, descriptorPool);
    return texelBuffers[0];
}

static VkSemaphore VKRenderer_AddPendingSemaphore(VKRenderer* renderer) {
    VKDevice* device = renderer->device;
    VkSemaphore semaphore = VK_NULL_HANDLE;
    POOL_TAKE(renderer, semaphorePool, semaphore);
    if (semaphore == VK_NULL_HANDLE) {
        VkSemaphoreCreateInfo createInfo = {
                .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
                .flags = 0
        };
        VK_IF_ERROR(device->vkCreateSemaphore(device->handle, &createInfo, NULL, &semaphore)) return VK_NULL_HANDLE;
    }
    POOL_RETURN(renderer, semaphorePool, semaphore);
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

    J2dRlsTraceLn(J2D_TRACE_INFO, "VKRenderer_Create: renderer=%p", renderer);
    return renderer;
}

void VKRenderer_Destroy(VKRenderer* renderer) {
    if (renderer == NULL) return;
    VKDevice* device = renderer->device;
    assert(device != NULL && device->allocator != NULL);
    VKRenderer_Sync(renderer);
    // TODO Ensure all surface render passes are released, so that no resources got stuck there.
    //      We can just form a linked list from all render passes to have access to them from the renderer.
    VKPipelines_DestroyContext(renderer->pipelineContext);

    // No need to destroy command buffers one by one, we will destroy the pool anyway.
    POOL_FREE(renderer, commandBufferPool);
    POOL_FREE(renderer, secondaryCommandBufferPool);
    POOL_DRAIN_FOR(renderer, semaphorePool, entry) {
        device->vkDestroySemaphore(device->handle, entry->value, NULL);
    }

    // Destroy buffer pools.
    POOL_DRAIN_FOR(renderer, vertexBufferPool, entry) {
        device->vkDestroyBuffer(device->handle, entry->value.handle, NULL);
    }
    POOL_DRAIN_FOR(renderer, maskFillBufferPool, entry) {
        // No need to destroy descriptor sets one by one, we will destroy the pool anyway.
        device->vkDestroyBufferView(device->handle, entry->value.view, NULL);
        device->vkDestroyBuffer(device->handle, entry->value.buffer.handle, NULL);
    }
    for (uint32_t i = 0; i < ARRAY_SIZE(renderer->bufferMemoryPages); i++) {
        VKAllocator_Free(device->allocator, renderer->bufferMemoryPages[i]);
    }
    ARRAY_FREE(renderer->bufferMemoryPages);
    for (uint32_t i = 0; i < ARRAY_SIZE(renderer->descriptorPools); i++) {
        device->vkDestroyDescriptorPool(device->handle, renderer->descriptorPools[i], NULL);
    }
    ARRAY_FREE(renderer->descriptorPools);

    device->vkDestroySemaphore(device->handle, renderer->timelineSemaphore, NULL);
    device->vkDestroyCommandPool(device->handle, renderer->commandPool, NULL);
    ARRAY_FREE(renderer->wait.semaphores);
    ARRAY_FREE(renderer->wait.stages);
    ARRAY_FREE(renderer->pendingPresentation.swapchains);
    ARRAY_FREE(renderer->pendingPresentation.indices);
    ARRAY_FREE(renderer->pendingPresentation.results);
    J2dRlsTraceLn(J2D_TRACE_INFO, "VKRenderer_Destroy(%p)", renderer);
    free(renderer);
}

/**
 * Record commands into primary command buffer (outside of a render pass).
 * Recorded commands will be sent for execution via VKRenderer_Flush.
 */
VkCommandBuffer VKRenderer_Record(VKRenderer* renderer) {
    if (renderer->commandBuffer != VK_NULL_HANDLE) {
        return renderer->commandBuffer;
    }
    VKDevice* device = renderer->device;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    POOL_TAKE(renderer, commandBufferPool, commandBuffer);
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
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_Record(%p): started", renderer);
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
        POOL_RETURN(renderer, commandBufferPool, renderer->commandBuffer);
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
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_Flush(%p): buffers=%d, presentations=%d",
                  renderer, submitInfo.commandBufferCount, pendingPresentations);
}



/**
 * Prepare image barrier info to be executed in batch, if needed.
 */
void VKRenderer_AddImageBarrier(VkImageMemoryBarrier* barriers, VKBarrierBatch* batch,
                                       VKImage* image, VkPipelineStageFlags stage, VkAccessFlags access, VkImageLayout layout) {
    assert(barriers != NULL && batch != NULL && image != NULL);
    // TODO Even if stage, access and layout didn't change, we may still need a barrier against WaW hazard.
    if (stage != image->lastStage || access != image->lastAccess || layout != image->layout) {
        barriers[batch->barrierCount] = (VkImageMemoryBarrier) {
                .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
                .srcAccessMask = image->lastAccess,
                .dstAccessMask = access,
                .oldLayout = image->layout,
                .newLayout = layout,
                .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
                .image = image->handle,
                .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 }
        };
        batch->barrierCount++;
        batch->srcStages |= image->lastStage;
        batch->dstStages |= stage;
        image->lastStage = stage;
        image->lastAccess = access;
        image->layout = layout;
    }
}

/**
 * Record draw command, if there are any pending vertices in the vertex buffer
 */
inline void VKRenderer_FlushDraw(VKSDOps* surface) {
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
    surface->renderPass->firstVertex = 0;
    surface->renderPass->vertexCount = 0;
    surface->renderPass->vertexBufferWriting = (BufferWritingState) {NULL, 0, VK_FALSE};
    surface->renderPass->maskFillBufferWriting = (BufferWritingState) {NULL, 0, VK_FALSE};
    size_t vertexBufferCount = ARRAY_SIZE(surface->renderPass->vertexBuffers);
    size_t maskFillBufferCount = ARRAY_SIZE(surface->renderPass->maskFillBuffers);
    if (vertexBufferCount == 0 && maskFillBufferCount == 0) return;
    VkMappedMemoryRange memoryRanges[vertexBufferCount + maskFillBufferCount];
    for (uint32_t i = 0; i < vertexBufferCount; i++) {
        memoryRanges[i] = surface->renderPass->vertexBuffers[i].range;
        POOL_RETURN(surface->device->renderer, vertexBufferPool, surface->renderPass->vertexBuffers[i]);
    }
    for (uint32_t i = 0; i < maskFillBufferCount; i++) {
        memoryRanges[vertexBufferCount + i] = surface->renderPass->maskFillBuffers[i].buffer.range;
        POOL_RETURN(surface->device->renderer, maskFillBufferPool, surface->renderPass->maskFillBuffers[i]);
    }
    ARRAY_RESIZE(surface->renderPass->vertexBuffers, 0);
    ARRAY_RESIZE(surface->renderPass->maskFillBuffers, 0);
    VK_IF_ERROR(surface->device->vkFlushMappedMemoryRanges(surface->device->handle,
                                                           vertexBufferCount + maskFillBufferCount, memoryRanges)) {}
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
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_DiscardRenderPass(%p)", surface);
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
        device->vkDestroyFramebuffer(device->handle, surface->renderPass->framebuffer, NULL);
        if (surface->renderPass->commandBuffer != VK_NULL_HANDLE) {
            POOL_RETURN(device->renderer, secondaryCommandBufferPool, surface->renderPass->commandBuffer);
        }
        ARRAY_FREE(surface->renderPass->vertexBuffers);
        ARRAY_FREE(surface->renderPass->maskFillBuffers);
    }
    free(surface->renderPass);
    surface->renderPass = NULL;
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_DestroyRenderPass(%p)", surface);
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
            .currentComposite = NO_COMPOSITE,
            .currentPipeline = NO_PIPELINE,
            .lastTimestamp = 0
    };

    // Initialize pipelines. They are cached until surface format changes.
    if (renderPass->context == NULL) {
        renderPass->context = VKPipelines_GetRenderPassContext(renderer->pipelineContext, surface->image->format);
    }

    // Initialize framebuffer.
    if (renderPass->framebuffer == VK_NULL_HANDLE) {
        VkFramebufferCreateInfo framebufferCreateInfo = {
                .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
                .renderPass = renderPass->context->renderPass,
                .attachmentCount = 1,
                .pAttachments = &surface->image->view,
                .width = surface->image->extent.width,
                .height = surface->image->extent.height,
                .layers = 1
        };
        VK_IF_ERROR(device->vkCreateFramebuffer(device->handle, &framebufferCreateInfo, NULL,
                                                &renderPass->framebuffer)) VK_UNHANDLED_ERROR();
    }

    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_InitRenderPass(%p)", surface);
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
        POOL_TAKE(renderer, secondaryCommandBufferPool, commandBuffer);
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
    VkCommandBufferInheritanceInfo inheritanceInfo = {
            .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_INHERITANCE_INFO,
            .renderPass = surface->renderPass->context->renderPass,
            .subpass = 0,
            .framebuffer = surface->renderPass->framebuffer
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
                .rect = {{0, 0}, surface->image->extent},
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
            .width = (float) surface->image->extent.width,
            .height = (float) surface->image->extent.height,
            .minDepth = 0.0f,
            .maxDepth = 1.0f
    };
    VkRect2D scissor = {{0, 0}, surface->image->extent};
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
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_BeginRenderPass(%p)", surface);
}

/**
 * End render pass for the surface and record it into the primary command buffer,
 * which will be executed on the next VKRenderer_Flush.
 */
void VKRenderer_FlushRenderPass(VKSDOps* surface) {
    assert(surface != NULL && surface->renderPass != NULL);
    VKRenderer_FlushDraw(surface);
    VkBool32 hasCommands = surface->renderPass->pendingCommands, clear = surface->renderPass->pendingClear;
    if(!hasCommands && !clear) return;
    VKDevice* device = surface->device;
    VKRenderer* renderer = device->renderer;
    surface->renderPass->lastTimestamp = renderer->writeTimestamp;
    VkCommandBuffer cb = VKRenderer_Record(renderer);

    // Insert barrier to prepare surface for rendering.
    VkImageMemoryBarrier barriers[1];
    VKBarrierBatch barrierBatch = {};
    VKRenderer_AddImageBarrier(barriers, &barrierBatch, surface->image,
                               VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                               VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                               VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
    if (barrierBatch.barrierCount > 0) {
        device->vkCmdPipelineBarrier(cb, barrierBatch.srcStages, barrierBatch.dstStages,
                                     0, 0, NULL, 0, NULL, barrierBatch.barrierCount, barriers);
    }

    // Begin render pass.
    VkRenderPassBeginInfo renderPassInfo = {
            .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
            .renderPass = surface->renderPass->context->renderPass,
            .framebuffer = surface->renderPass->framebuffer,
            .renderArea.offset = (VkOffset2D){0, 0},
            .renderArea.extent = surface->image->extent,
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
        POOL_RETURN(renderer, secondaryCommandBufferPool, surface->renderPass->commandBuffer);
        surface->renderPass->commandBuffer = VK_NULL_HANDLE;
    }

    device->vkCmdEndRenderPass(cb);
    VKRenderer_ResetDrawing(surface);
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_FlushRenderPass(%p): hasCommands=%d, clear=%d", surface, hasCommands, clear);
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
            VkImageMemoryBarrier barriers[2] = {{
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
            VKBarrierBatch barrierBatch = {1, surface->image->lastStage | VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT};
            VKRenderer_AddImageBarrier(barriers, &barrierBatch, surface->image, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                       VK_ACCESS_TRANSFER_READ_BIT, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            device->vkCmdPipelineBarrier(cb, barrierBatch.srcStages, barrierBatch.dstStages,
                                         0, 0, NULL, 0, NULL, barrierBatch.barrierCount, barriers);
        }

        // Do blit.
        VkImageBlit blit = {
                .srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 },
                .srcOffsets[0] = {0, 0, 0},
                .srcOffsets[1] = {(int)surface->image->extent.width, (int)surface->image->extent.height, 1},
                .dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 },
                .dstOffsets[0] = {0, 0, 0},
                .dstOffsets[1] = {(int)surface->image->extent.width, (int)surface->image->extent.height, 1},
        };
        device->vkCmdBlitImage(cb,
                               surface->image->handle, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
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
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_FlushSurface(%p): queued for presentation", surface);
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
            J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_ConfigureSurface(%p): pending flush", surface);
            VKRenderer_FlushSurface(surface);
        }
    }
}

/**
 * Allocate bytes for writing into buffer. Returned state contains:
 * - data   - pointer to the beginning of buffer, or NULL, if there is no buffer yet.
 * - offset - writing offset into the buffer data, or 0, if there is no buffer yet.
 * - bound  - whether corresponding buffer is bound to the command buffer,
 *            caller is responsible for checking this value and setting up & binding the buffer.
 */
inline BufferWritingState VKRenderer_AllocateBufferData(VKSDOps* surface, BufferWritingState* writingState,
                                                        VkDeviceSize size, VkDeviceSize maxBufferSize) {
    assert(surface != NULL && surface->renderPass != NULL && writingState != NULL);
    assert(size <= maxBufferSize);
    BufferWritingState result = *writingState;
    writingState->offset += size;
    // Overflow, flush drawing commands and take another buffer.
    if (writingState->offset > maxBufferSize) {
        VKRenderer_FlushDraw(surface);
        writingState->offset = size;
        result.offset = 0;
        result.bound = VK_FALSE;
        result.data = NULL;
    }
    writingState->bound = VK_TRUE; // We assume caller will check the result and bind the buffer right away!
    return result;
}

/**
 * Allocate vertices from vertex buffer. VKRenderer_Validate must have been called before.
 * This function must not be used directly, use VK_DRAW macro instead.
 * It is responsibility of the caller to pass correct vertexSize, matching current pipeline.
 * This function cannot draw more vertices than fits into single vertex buffer at once.
 */
static void* VKRenderer_AllocateVertices(VKRenderingContext* context, uint32_t vertices, size_t vertexSize) {
    assert(vertices > 0 && vertexSize > 0);
    assert(vertexSize * vertices <= VERTEX_BUFFER_SIZE);
    VKSDOps* surface = context->surface;
    BufferWritingState state = VKRenderer_AllocateBufferData(
            surface, &surface->renderPass->vertexBufferWriting, (VkDeviceSize) (vertexSize * vertices), VERTEX_BUFFER_SIZE);
    if (!state.bound) {
        if (state.data == NULL) {
            VKBuffer buffer = VKRenderer_GetVertexBuffer(surface->device->renderer);
            ARRAY_PUSH_BACK(surface->renderPass->vertexBuffers, buffer);
            surface->renderPass->vertexBufferWriting.data = state.data = buffer.data;
        }
        assert(ARRAY_SIZE(surface->renderPass->vertexBuffers) > 0);
        surface->renderPass->firstVertex = surface->renderPass->vertexCount = 0;
        surface->device->vkCmdBindVertexBuffers(surface->renderPass->commandBuffer, 0, 1,
                                                &(ARRAY_LAST(surface->renderPass->vertexBuffers).handle), &state.offset);
    }
    surface->renderPass->vertexCount += vertices;
    return (void*) ((uint8_t*) state.data + state.offset);
}

/**
 * Allocate vertices from vertex buffer, providing pointer for writing.
 */
#define VK_DRAW(VERTICES, CONTEXT, VERTEX_COUNT) \
    (VERTICES) = VKRenderer_AllocateVertices((CONTEXT), (VERTEX_COUNT), sizeof((VERTICES)[0]))

/**
 * Allocate bytes from mask fill buffer. VKRenderer_Validate must have been called before.
 * This function cannot take more bytes than fits into single mask fill buffer at once.
 * Caller must write data at the returned pointer DrawingBufferWritingState.data
 * and take into account DrawingBufferWritingState.offset from the beginning of the bound buffer.
 */
static BufferWritingState VKRenderer_AllocateMaskFillBytes(VKRenderingContext* context, uint32_t size) {
    assert(size > 0);
    assert(size <= MASK_FILL_BUFFER_SIZE);
    VKSDOps* surface = context->surface;
    BufferWritingState state = VKRenderer_AllocateBufferData(
            surface, &surface->renderPass->maskFillBufferWriting, size, MASK_FILL_BUFFER_SIZE);
    if (!state.bound) {
        if (state.data == NULL) {
            VKTexelBuffer buffer = VKRenderer_GetMaskFillBuffer(surface->device->renderer);
            ARRAY_PUSH_BACK(surface->renderPass->maskFillBuffers, buffer);
            surface->renderPass->maskFillBufferWriting.data = state.data = buffer.buffer.data;
        }
        assert(ARRAY_SIZE(surface->renderPass->maskFillBuffers) > 0);
        surface->device->vkCmdBindDescriptorSets(context->surface->renderPass->commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                                 surface->device->renderer->pipelineContext->maskFillPipelineLayout,
                                                 0, 1, &ARRAY_LAST(surface->renderPass->maskFillBuffers).descriptorSet, 0, NULL);
    }
    state.data = (void*) ((uint8_t*) state.data + state.offset);
    return state;
}

/**
 * Setup pipeline for drawing. Returns FALSE if surface is not yet ready for drawing.
 */
VkBool32 VKRenderer_Validate(VKRenderingContext* context, VKPipeline pipeline) {
    assert(context != NULL && context->surface != NULL);
    VKSDOps* surface = context->surface;

    // Init render pass.
    if (surface->renderPass == NULL || !surface->renderPass->pendingCommands) {
        // We must only [re]init render pass between frames.
        // Be careful to NOT call VKRenderer_InitRenderPass between render passes within single frame.
        if (!VKRenderer_InitRenderPass(surface)) return VK_FALSE;
    }
    VKRenderPass* renderPass = surface->renderPass;

    // Validate render pass state.
    if (renderPass->currentComposite != context->composite) {
        // ALPHA_COMPOSITE_DST keeps destination intact, so don't even bother to change the state.
        if (context->composite == ALPHA_COMPOSITE_DST) return VK_FALSE;
        VKCompositeMode oldComposite = renderPass->currentComposite;
        // Update state.
        VKRenderer_FlushDraw(surface);
        renderPass->currentComposite = context->composite;
        // Begin render pass.
        if (!renderPass->pendingCommands) VKRenderer_BeginRenderPass(surface);
        // Validate current composite.
        J2dTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_Validate: updating composite, old=%d, new=%d", oldComposite, context->composite);
        // Reset the pipeline.
        renderPass->currentPipeline = NO_PIPELINE;
    }

    // Validate current pipeline.
    if (renderPass->currentPipeline != pipeline) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_Validate: updating pipeline, old=%d, new=%d",
                   renderPass->currentPipeline, pipeline);
        VKRenderer_FlushDraw(surface);
        VkCommandBuffer cb = renderPass->commandBuffer;
        renderPass->currentPipeline = pipeline;
        surface->device->vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                           VKPipelines_GetPipeline(renderPass->context, context->composite, pipeline));
        renderPass->vertexBufferWriting.bound = VK_FALSE;
        renderPass->maskFillBufferWriting.bound = VK_FALSE;
    }
    return VK_TRUE;
}

// Drawing operations.

void VKRenderer_RenderRect(VKRenderingContext* context, VKPipeline pipeline, jint x, jint y, jint w, jint h) {
    VKRenderer_RenderParallelogram(context, pipeline, (float) x, (float) y, (float) w, 0, 0, (float) h);
}

void VKRenderer_RenderParallelogram(VKRenderingContext* context, VKPipeline pipeline,
                                    jfloat x11, jfloat y11,
                                    jfloat dx21, jfloat dy21,
                                    jfloat dx12, jfloat dy12) {
    if (!VKRenderer_Validate(context, pipeline)) return; // Not ready.
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

    VKColorVertex* vs;
    VK_DRAW(vs, context, pipeline == PIPELINE_DRAW_COLOR ? 8 : 6);
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
    if (!VKRenderer_Validate(context, PIPELINE_FILL_COLOR)) return; // Not ready.
    Color c = context->color;

    jfloat x1 = (float)*(spans++);
    jfloat y1 = (float)*(spans++);
    jfloat x2 = (float)*(spans++);
    jfloat y2 = (float)*(spans++);
    VKColorVertex p1 = {x1, y1, c};
    VKColorVertex p2 = {x2, y1, c};
    VKColorVertex p3 = {x2, y2, c};
    VKColorVertex p4 = {x1, y2, c};

    VKColorVertex* vs;
    VK_DRAW(vs, context, 6);
    vs[0] = p1; vs[1] = p2; vs[2] = p3; vs[3] = p3; vs[4] = p4; vs[5] = p1;

    for (int i = 1; i < spanCount; i++) {
        p1.x = p4.x = (float)*(spans++);
        p1.y = p2.y = (float)*(spans++);
        p2.x = p3.x = (float)*(spans++);
        p3.y = p4.y = (float)*(spans++);

        VK_DRAW(vs, context, 6);
        vs[0] = p1; vs[1] = p2; vs[2] = p3; vs[3] = p3; vs[4] = p4; vs[5] = p1;
    }
}

void VKRenderer_TextureRender(VKRenderingContext* context, VKImage *destImage, VKImage *srcImage,
                              VkBuffer vertexBuffer, uint32_t vertexNum) {
    if (!VKRenderer_Validate(context, PIPELINE_BLIT)) return; // Not ready.
    VKSDOps* surface = (VKSDOps*)context->surface;
    VKRenderPass* renderPass = surface->renderPass;
    VkCommandBuffer cb = renderPass->commandBuffer;
    VKDevice* device = surface->device;

    // TODO We create a new descriptor set on each command, we'll implement reusing them later.
    VkDescriptorPoolSize poolSize = {
            .type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .descriptorCount = 1
    };
    VkDescriptorPoolCreateInfo descrPoolInfo = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
            .poolSizeCount = 1,
            .pPoolSizes = &poolSize,
            .maxSets = 1
    };
    VkDescriptorPool descriptorPool;
    VK_IF_ERROR(device->vkCreateDescriptorPool(device->handle, &descrPoolInfo, NULL, &descriptorPool)) VK_UNHANDLED_ERROR();

    VkDescriptorSetAllocateInfo descrAllocInfo = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            .descriptorPool = descriptorPool,
            .descriptorSetCount = 1,
            .pSetLayouts = &device->renderer->pipelineContext->textureDescriptorSetLayout
    };
    VkDescriptorSet descriptorSet;
    VK_IF_ERROR(device->vkAllocateDescriptorSets(device->handle, &descrAllocInfo, &descriptorSet)) VK_UNHANDLED_ERROR();

    VkDescriptorImageInfo imageInfo = {
            .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            .imageView = srcImage->view,
            .sampler = device->renderer->pipelineContext->linearRepeatSampler
    };

    VkWriteDescriptorSet descriptorWrites = {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = descriptorSet,
            .dstBinding = 0,
            .dstArrayElement = 0,
            .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            .descriptorCount = 1,
            .pImageInfo = &imageInfo
    };

    device->vkUpdateDescriptorSets(device->handle, 1, &descriptorWrites, 0, NULL);


    // TODO We flush all pending draws and rebind the vertex buffer with the provided one.
    //      We will make it work with our unified vertex buffer later.
    VKRenderer_FlushDraw(surface);
    renderPass->vertexBufferWriting.bound = VK_FALSE;
    VkBuffer vertexBuffers[] = {vertexBuffer};
    VkDeviceSize offsets[] = {0};
    device->vkCmdBindVertexBuffers(cb, 0, 1, vertexBuffers, offsets);
    device->vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                    device->renderer->pipelineContext->texturePipelineLayout, 0, 1, &descriptorSet, 0, NULL);
    device->vkCmdDraw(cb, vertexNum, 1, 0, 0);
}

void VKRenderer_MaskFill(VKRenderingContext* context, jint x, jint y, jint w, jint h,
                         jint maskoff, jint maskscan, jint masklen, uint8_t* mask) {
    if (!VKRenderer_Validate(context, PIPELINE_MASK_FILL_COLOR)) return; // Not ready.
    // maskoff is the offset from the beginning of mask,
    // it's the same as x and y offset within a tile (maskoff % maskscan, maskoff / maskscan).
    // maskscan is the number of bytes in a row/
    // masklen is the size of the whole mask tile, it may be way bigger, than number of actually needed bytes.
    VKMaskFillColorVertex* vs;
    VK_DRAW(vs, context, 6);
    Color c = context->color;

    uint32_t byteCount = maskscan * h;
    BufferWritingState maskState = VKRenderer_AllocateMaskFillBytes(context, byteCount);
    memcpy(maskState.data, mask + maskoff, byteCount);

    int offset = (int) maskState.offset;
    VKMaskFillColorVertex p1 = {x, y, offset, maskscan, c};
    VKMaskFillColorVertex p2 = {x + w, y, offset, maskscan, c};
    VKMaskFillColorVertex p3 = {x + w, y + h, offset, maskscan, c};
    VKMaskFillColorVertex p4 = {x, y + h, offset, maskscan, c};
    // Always keep p1 as provoking vertex for correct origin calculation in vertex shader.
    vs[0] = p1; vs[1] = p3; vs[2] = p2;
    vs[3] = p1; vs[4] = p3; vs[5] = p4;
}

#endif /* !HEADLESS */
