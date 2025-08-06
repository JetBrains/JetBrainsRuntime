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

#include <assert.h>
#include <string.h>
#include "sun_java2d_vulkan_VKGPU.h"
#include "VKUtil.h"
#include "VKAllocator.h"
#include "VKBuffer.h"
#include "VKDevice.h"
#include "VKImage.h"
#include "VKRenderer.h"
#include "VKSurfaceData.h"

/**
 * Pool of resources with associated timestamps, guarding their reuse.
 * The pool must only be manipulated via POOL_* macros.
 */
#define POOL(TYPE, NAME)                \
RING_BUFFER(struct PoolEntry_ ## NAME { \
    uint64_t timestamp;                 \
    TYPE value;                         \
}) NAME

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
#define POOL_RETURN(RENDERER, NAME, VAR) (RING_BUFFER_PUSH_BACK((RENDERER)->NAME) = \
    (struct PoolEntry_ ## NAME) { .timestamp = (RENDERER)->writeTimestamp + (VK_DEBUG_RANDOM(20)*3), .value = (VAR) })

/**
 * Insert an item into the pool. It is available for POOL_TAKE immediately.
 * This is usually used for bulk insertion of newly created resources.
 */
#define POOL_INSERT(RENDERER, NAME, VAR) (RING_BUFFER_PUSH_FRONT((RENDERER)->NAME) = \
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

    POOL(VkCommandBuffer,   commandBufferPool);
    POOL(VkCommandBuffer,   secondaryCommandBufferPool);
    POOL(VkSemaphore,       semaphorePool);
    POOL(VKBuffer,          vertexBufferPool);
    POOL(VKTexelBuffer,     maskFillBufferPool);
    POOL(VKCleanupEntry,    cleanupQueue);
    ARRAY(VKMemory)         bufferMemoryPages;
    ARRAY(VkDescriptorPool) descriptorPools;
    ARRAY(VkDescriptorPool) imageDescriptorPools;

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
} BufferWritingState;

typedef struct {
    BufferWritingState state;
    uint32_t             elements;
} BufferWriting;

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

    uint32_t           firstVertex;
    uint32_t           vertexCount;
    BufferWritingState vertexBufferWriting;
    BufferWritingState maskFillBufferWriting;

    VKPipelineDescriptor state;
    uint64_t             transformModCount; // Just a tag to detect when transform was changed.
    uint64_t             clipModCount; // Just a tag to detect when clip was changed.
    VkBool32             pendingFlush    : 1;
    VkBool32             pendingCommands : 1;
    VkBool32             pendingClear    : 1;
    AlphaType            outAlphaType    : 1;
};

// Rendering context is only accessed from VKRenderQueue_flushBuffer,
// which is only called from queue flusher thread, no need for synchronization.
static VKRenderingContext context = {
        .surface = NULL,
        .transform = VK_ID_TRANSFORM,
        .transformModCount = 1,
        .color = {},
        .renderColor = {},
        .composite = ALPHA_COMPOSITE_SRC_OVER,
        .extraAlpha = 1.0f,
        .clipModCount = 1,
        .clipRect = NO_CLIP,
        .clipSpanVertices = NULL
};

/**
 * Accessor to the global rendering context.
 */
VKRenderingContext *VKRenderer_GetContext() {
    return &context;
}

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
    if (pool != NULL) {
        RING_BUFFER(char) ring_buffer = pool;
        RING_BUFFER_FREE(ring_buffer);
    }
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
    ARRAY_PUSH_BACK(renderer->bufferMemoryPages) = page;
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
    ARRAY_PUSH_BACK(renderer->bufferMemoryPages) = page;
    ARRAY_PUSH_BACK(renderer->descriptorPools) = descriptorPool;
    return texelBuffers[0];
}

#define IMAGE_DESCRIPTOR_POOL_SIZE 64
static VkDescriptorSet VKRenderer_AllocateImageDescriptorSet(VKRenderer* renderer, VkDescriptorPool descriptorPool) {
    VKDevice* device = renderer->device;
    VkDescriptorSetAllocateInfo allocateInfo = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = descriptorPool,
        .descriptorSetCount = 1,
        .pSetLayouts = &renderer->pipelineContext->textureDescriptorSetLayout
    };
    VkDescriptorSet set;
    VkResult result = device->vkAllocateDescriptorSets(device->handle, &allocateInfo, &set);
    if (result == VK_SUCCESS) return set;
    if (result != VK_ERROR_OUT_OF_POOL_MEMORY && result != VK_ERROR_FRAGMENTED_POOL) {
        VK_IF_ERROR(result) VK_UNHANDLED_ERROR();
    }
    return VK_NULL_HANDLE;
}
void VKRenderer_CreateImageDescriptorSet(VKRenderer* renderer, VkDescriptorPool* descriptorPool, VkDescriptorSet* set) {
    VKDevice* device = renderer->device;
    for (int i = ARRAY_SIZE(renderer->imageDescriptorPools) - 1; i >= 0; i--) {
        *set = VKRenderer_AllocateImageDescriptorSet(renderer, renderer->imageDescriptorPools[i]);
        if (*set != VK_NULL_HANDLE) {
            *descriptorPool = renderer->imageDescriptorPools[i];
            return;
        }
    }
    VkDescriptorPoolSize poolSize = {
        .type = VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
        .descriptorCount = IMAGE_DESCRIPTOR_POOL_SIZE
    };
    VkDescriptorPoolCreateInfo poolInfo = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT,
        .poolSizeCount = 1,
        .pPoolSizes = &poolSize,
        .maxSets = IMAGE_DESCRIPTOR_POOL_SIZE
    };
    VK_IF_ERROR(device->vkCreateDescriptorPool(device->handle, &poolInfo, NULL, descriptorPool)) VK_UNHANDLED_ERROR();
    ARRAY_PUSH_BACK(renderer->imageDescriptorPools) = *descriptorPool;
    *set = VKRenderer_AllocateImageDescriptorSet(renderer, *descriptorPool);
}

static void VKRenderer_CleanupPendingResources(VKRenderer* renderer) {
    VKDevice* device = renderer->device;
    for (;;) {
        VKCleanupEntry entry = { NULL, NULL };
        POOL_TAKE(renderer, cleanupQueue, entry);
        if (entry.handler == NULL) break;
        entry.handler(device, entry.data);
    }
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

/**
 * Wait till the GPU execution reached a given timestamp.
 */
static void VKRenderer_Wait(VKRenderer* renderer, uint64_t timestamp) {
    if (renderer->readTimestamp < timestamp) {
        VkSemaphoreWaitInfo semaphoreWaitInfo = {
            .sType = VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO,
            .flags = 0,
            .semaphoreCount = 1,
            .pSemaphores = &renderer->timelineSemaphore,
            .pValues = &timestamp
        };
        VK_IF_ERROR(renderer->device->vkWaitSemaphores(renderer->device->handle, &semaphoreWaitInfo, -1)) VK_UNHANDLED_ERROR();
        else renderer->readTimestamp = timestamp; // On success, update the last known timestamp.
    }
    VKRenderer_CleanupPendingResources(renderer);
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
    for (uint32_t i = 0; i < ARRAY_SIZE(renderer->imageDescriptorPools); i++) {
        device->vkDestroyDescriptorPool(device->handle, renderer->imageDescriptorPools[i], NULL);
    }
    ARRAY_FREE(renderer->imageDescriptorPools);

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
    VKRenderer_CleanupPendingResources(renderer);
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
 * Get Color RGBA components in a format suitable for the current render pass.
 */
inline RGBA VKRenderer_GetRGBA(VKSDOps* surface, Color color) {
    return VKUtil_GetRGBA(color, surface->renderPass->outAlphaType);
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
    surface->renderPass->state.composite = NO_COMPOSITE;
    surface->renderPass->state.shader = NO_SHADER;
    surface->renderPass->transformModCount = 0;
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
 * Flush render passes depending on a given surface.
 * This function must be called before mutating a surface
 * because there may be pending render passes reading from that surface.
 */
static void VKRenderer_FlushDependentRenderPasses(VKSDOps* surface) {
    // We're going to clear dependentSurfaces in the end anyway,
    // so temporarily reset it to NULL to save on removing flushed render passes one-by-one.
    ARRAY(VKSDOps*) deps = surface->dependentSurfaces;
    surface->dependentSurfaces = NULL;
    uint32_t size = (uint32_t) ARRAY_SIZE(deps);
    if (size > 0) J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_FlushDependentRenderPasses(%p): %d", surface, size);
    for (uint32_t i = 0; i < size; i++) {
        VKRenderer_FlushRenderPass(deps[i]);
    }
    ARRAY_RESIZE(deps, 0);
    surface->dependentSurfaces = deps;
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
    VKDevice* device = surface->device;
    // Wait while surface & related resources are being used by the device.
    if (device != NULL) {
        VKRenderer_FlushDependentRenderPasses(surface);
        if (surface->lastTimestamp == device->renderer->writeTimestamp) {
            VKRenderer_Flush(device->renderer);
        }
        VKRenderer_Wait(device->renderer, surface->lastTimestamp);
    }
    if (surface->renderPass == NULL) return;
    if (device != NULL && device->renderer != NULL) {
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
            .state = {
                .stencilMode = STENCIL_MODE_NONE,
                .dstOpaque = VKSD_IsOpaque(surface),
                .inAlphaType = ALPHA_TYPE_UNKNOWN,
                .composite = NO_COMPOSITE,
                .shader = NO_SHADER
            },
            .transformModCount = 0,
            .clipModCount = 0,
            .pendingFlush = VK_FALSE,
            .pendingCommands = VK_FALSE,
            .pendingClear = VK_TRUE, // Clear the surface by default
    };

    // Initialize pipelines. They are cached until surface format changes.
    if (renderPass->context == NULL) {
        renderPass->context = VKPipelines_GetRenderPassContext(renderer->pipelineContext, surface->image->format);
    }

    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_InitRenderPass(%p)", surface);
    return VK_TRUE;
}

static void VKRenderer_CleanupFramebuffer(VKDevice* device, void* data) {
    device->vkDestroyFramebuffer(device->handle, (VkFramebuffer) data, NULL);
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_CleanupFramebuffer(%p)", data);
}

/**
 * Initialize surface framebuffer.
 * This function can be called between render passes of a single frame, unlike VKRenderer_InitRenderPass.
 */
static void VKRenderer_InitFramebuffer(VKSDOps* surface) {
    assert(surface != NULL && surface->device != NULL && surface->renderPass != NULL);
    VKDevice* device = surface->device;
    VKRenderPass* renderPass = surface->renderPass;

    if (renderPass->state.stencilMode == STENCIL_MODE_NONE && surface->stencil != NULL) {
        // Queue outdated color-only framebuffer for destruction.
        POOL_RETURN(device->renderer, cleanupQueue,
            ((VKCleanupEntry) { VKRenderer_CleanupFramebuffer, renderPass->framebuffer }));
        renderPass->framebuffer = VK_NULL_HANDLE;
        renderPass->state.stencilMode = STENCIL_MODE_OFF;
    }

    // Initialize framebuffer.
    if (renderPass->framebuffer == VK_NULL_HANDLE) {
        renderPass->renderPass = renderPass->context->renderPass[surface->stencil != NULL];
        VkImageView views[] = { VKImage_GetView(device, surface->image, surface->image->format, 0), VK_NULL_HANDLE };
        VkFramebufferCreateInfo framebufferCreateInfo = {
                .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
                .renderPass = renderPass->renderPass,
                .attachmentCount = 1,
                .pAttachments = views,
                .width = surface->image->extent.width,
                .height = surface->image->extent.height,
                .layers = 1
        };
        if (surface->stencil != NULL) {
            framebufferCreateInfo.attachmentCount = 2;
            views[1] = VKImage_GetView(device, surface->stencil, surface->stencil->format, 0);
        }
        VK_IF_ERROR(device->vkCreateFramebuffer(device->handle, &framebufferCreateInfo, NULL,
                                                &renderPass->framebuffer)) VK_UNHANDLED_ERROR();

        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_InitFramebuffer(%p)", surface);
    }
}

/**
 * Begin render pass for the surface.
 */
static void VKRenderer_BeginRenderPass(VKSDOps* surface) {
    assert(surface != NULL && surface->renderPass != NULL && !surface->renderPass->pendingCommands);
    VKRenderer_FlushDependentRenderPasses(surface);
    VKRenderer_InitFramebuffer(surface);
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
            .renderPass = surface->renderPass->renderPass,
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
                .clearValue = VKRenderer_GetRGBA(surface, surface->background).vkClearValue
        };
        if (VKSD_IsOpaque(surface)) clearAttachment.clearValue.color.float32[3] = 1.0f;
        VkClearRect clearRect = {
                .rect = {{0, 0}, surface->image->extent},
                .baseArrayLayer = 0,
                .layerCount = 1
        };
        device->vkCmdClearAttachments(commandBuffer, 1, &clearAttachment, 1, &clearRect);
        surface->renderPass->pendingClear = VK_FALSE;
    }

    // Set viewport.
    VkViewport viewport = {
            .x = 0.0f,
            .y = 0.0f,
            .width = (float) surface->image->extent.width,
            .height = (float) surface->image->extent.height,
            .minDepth = 0.0f,
            .maxDepth = 1.0f
    };
    device->vkCmdSetViewport(commandBuffer, 0, 1, &viewport);

    surface->renderPass->pendingCommands = VK_TRUE;
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_BeginRenderPass(%p)", surface);
}

/**
 * End render pass for the surface and record it into the primary command buffer,
 * which will be executed on the next VKRenderer_Flush.
 */
VkBool32 VKRenderer_FlushRenderPass(VKSDOps* surface) {
    assert(surface != NULL);
    // If pendingFlush is TRUE, pendingCommands must be FALSE
    assert(surface->renderPass == NULL || !surface->renderPass->pendingFlush || !surface->renderPass->pendingCommands);
    // Note that we skip render pass initialization, if we have pending flush,
    // which means that we missed the last flush, but didn't start a new render pass yet.
    // So now we are going to catch up the last frame, and don't need reconfiguration.
    // We also skip initialization if we have pending commands, because that means we are in the middle of frame.
    if (surface->renderPass == NULL || (!surface->renderPass->pendingCommands && !surface->renderPass->pendingFlush)) {
        if (!VKRenderer_InitRenderPass(surface)) return VK_FALSE;
        // Check for pendingClear after VKRenderer_InitRenderPass, it may be set after reconfiguration.
        if (!surface->renderPass->pendingClear) return VK_FALSE;
    }
    assert(surface->renderPass != NULL);

    VKRenderer_FlushDraw(surface);
    VkBool32 hasCommands = surface->renderPass->pendingCommands, clear = surface->renderPass->pendingClear;
    if(!hasCommands && !clear) return VK_FALSE;
    VKDevice* device = surface->device;
    VKRenderer* renderer = device->renderer;
    VkCommandBuffer cb = VKRenderer_Record(renderer);

    // Update dependencies on used surfaces.
    surface->lastTimestamp = renderer->writeTimestamp;
    for (uint32_t i = 0, surfaces = (uint32_t) ARRAY_SIZE(surface->renderPass->usedSurfaces); i < surfaces; i++) {
        VKSDOps* usedSurface = surface->renderPass->usedSurfaces[i];
        usedSurface->lastTimestamp = renderer->writeTimestamp;
        uint32_t newSize = 0, oldSize = (uint32_t) ARRAY_SIZE(usedSurface->dependentSurfaces);
        for (uint32_t j = 0; j < oldSize; j++) {
            VKSDOps* s = usedSurface->dependentSurfaces[j];
            if (s != surface) usedSurface->dependentSurfaces[newSize++] = s;
        }
        if (newSize != oldSize) ARRAY_RESIZE(usedSurface->dependentSurfaces, newSize);
    }
    ARRAY_RESIZE(surface->renderPass->usedSurfaces, 0);

    // Insert barriers to prepare surface for rendering.
    VkImageMemoryBarrier barriers[2];
    VKBarrierBatch barrierBatch = {};
    VKImage_AddBarrier(barriers, &barrierBatch, surface->image,
                       VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                       VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                       VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
    if (surface->stencil != NULL) {
        VKImage_AddBarrier(barriers, &barrierBatch, surface->stencil,
                           VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                           VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                           VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
    }
    VKRenderer_RecordBarriers(renderer, NULL, NULL, barriers, &barrierBatch);

    // If there is a pending clear, record it into render pass.
    if (clear) VKRenderer_BeginRenderPass(surface);
    // Begin render pass.
    VkRenderPassBeginInfo renderPassInfo = {
            .sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO,
            .renderPass = surface->renderPass->renderPass,
            .framebuffer = surface->renderPass->framebuffer,
            .renderArea = (VkRect2D) {{0, 0}, surface->image->extent},
            .clearValueCount = 0,
            .pClearValues = NULL
    };
    device->vkCmdBeginRenderPass(cb, &renderPassInfo, VK_SUBPASS_CONTENTS_SECONDARY_COMMAND_BUFFERS);

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
    return VK_TRUE;
}

void VKRenderer_FlushSurface(VKSDOps* surface) {
    assert(surface != NULL);
    if (!VKRenderer_FlushRenderPass(surface)) return;
    surface->renderPass->pendingFlush = VK_FALSE;

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
        VkCommandBuffer cb = VKRenderer_Record(renderer);
        surface->lastTimestamp = renderer->writeTimestamp;

        // Acquire swapchain image.
        VkSemaphore acquireSemaphore = VKRenderer_AddPendingSemaphore(renderer);
        ARRAY_PUSH_BACK(renderer->wait.semaphores) = acquireSemaphore;
        ARRAY_PUSH_BACK(renderer->wait.stages) = VK_PIPELINE_STAGE_TRANSFER_BIT; // Acquire image before blitting content onto swapchain

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
            VKImage_AddBarrier(barriers, &barrierBatch, surface->image, VK_PIPELINE_STAGE_TRANSFER_BIT,
                               VK_ACCESS_TRANSFER_READ_BIT, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            VKRenderer_RecordBarriers(renderer, NULL, NULL, barriers, &barrierBatch);
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
        ARRAY_PUSH_BACK(renderer->pendingPresentation.swapchains) = win->swapchain;
        ARRAY_PUSH_BACK(renderer->pendingPresentation.indices) = imageIndex;
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_FlushSurface(%p): queued for presentation", surface);
    }
}

void VKRenderer_ConfigureSurface(VKSDOps* surface, VkExtent2D extent, VKDevice* device) {
    assert(surface != NULL);
    surface->requestedExtent = extent;
    surface->requestedDevice = device;
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
 * - state.data   - pointer to the beginning of buffer, or NULL, if there is no buffer yet.
 * - state.offset - writing offset into the buffer data, or 0, if there is no buffer yet.
 * - state.bound  - whether corresponding buffer is bound to the command buffer,
 *                  caller is responsible for checking this value and setting up & binding the buffer.
 * - elements     - number of actually allocated elements.
 */
BufferWriting VKRenderer_AllocateBufferData(VKSDOps* surface, BufferWritingState* writingState,
                                            VkDeviceSize elements, VkDeviceSize elementSize,
                                            VkDeviceSize maxBufferSize) {
    assert(surface != NULL && surface->renderPass != NULL && writingState != NULL);
    assert(elementSize <= maxBufferSize);
    VkDeviceSize totalSize = elements * elementSize;
    BufferWriting result = { *writingState, elements };
    writingState->offset += totalSize;
    if (writingState->offset > maxBufferSize) { // Overflow.
        if (result.state.offset + elementSize > maxBufferSize) {
            // Cannot fit a single element, flush drawing commands and take another buffer.
            VKRenderer_FlushDraw(surface);
            result.state.offset = 0;
            result.state.bound = VK_FALSE;
            result.state.data = NULL;
        }
        // Calculate the number of remaining elements we can fit.
        result.elements = (maxBufferSize - result.state.offset) / elementSize;
        assert(result.elements > 0);
        if (result.elements > elements) result.elements = elements;
        writingState->offset = result.state.offset + result.elements * elementSize;
    }
    writingState->bound = VK_TRUE; // We assume the caller will check the result and bind the buffer right away!
    return result;
}

/**
 * Allocate vertices from the vertex buffer, returning the number of allocated primitives (>0).
 * VKRenderer_Validate must have been called before.
 * This function must not be used directly, use VK_DRAW macro instead.
 * This function must be called after all dynamic allocation functions,
 * which can invalidate the drawing state, e.g., VKRenderer_AllocateMaskFillBytes.
 */
static uint32_t VKRenderer_AllocateVertices(uint32_t primitives, uint32_t vertices, size_t vertexSize, void** result) {
    assert(vertices > 0 && vertexSize > 0);
    assert(vertexSize * vertices <= VERTEX_BUFFER_SIZE);
    VKSDOps* surface = VKRenderer_GetContext()->surface;
    BufferWriting writing = VKRenderer_AllocateBufferData(
            surface, &surface->renderPass->vertexBufferWriting, primitives, vertices * vertexSize, VERTEX_BUFFER_SIZE);
    if (!writing.state.bound) {
        if (writing.state.data == NULL) {
            VKBuffer buffer = VKRenderer_GetVertexBuffer(surface->device->renderer);
            ARRAY_PUSH_BACK(surface->renderPass->vertexBuffers) = buffer;
            surface->renderPass->vertexBufferWriting.data = writing.state.data = buffer.data;
        }
        assert(ARRAY_SIZE(surface->renderPass->vertexBuffers) > 0);
        surface->renderPass->firstVertex = surface->renderPass->vertexCount = 0;
        surface->device->vkCmdBindVertexBuffers(surface->renderPass->commandBuffer, 0, 1,
                                                &(ARRAY_LAST(surface->renderPass->vertexBuffers).handle), &writing.state.offset);
    }
    surface->renderPass->vertexCount += writing.elements * vertices;
    *((uint8_t**) result) = (uint8_t*) writing.state.data + writing.state.offset;
    return writing.elements;
}

/**
 * Allocate vertices from the vertex buffer, returning the number of allocated primitives (>0).
 * VKRenderer_Validate must have been called before.
 * This function must be called after all dynamic allocation functions,
 * which can invalidate the drawing state, e.g., VKRenderer_AllocateMaskFillBytes.
 */
#define VK_DRAW(VERTICES, PRIMITIVE_COUNT, VERTEX_COUNT) \
    VKRenderer_AllocateVertices((PRIMITIVE_COUNT), (VERTEX_COUNT), sizeof((VERTICES)[0]), (void**) &(VERTICES))

/**
 * Allocate bytes from mask fill buffer. VKRenderer_Validate must have been called before.
 * This function cannot take more bytes than fits into single mask fill buffer at once.
 * Caller must write data at the returned pointer BufferWritingState.data
 * and take into account BufferWritingState.offset from the beginning of the bound buffer.
 * This function can invalidate drawing state, always call it before VK_DRAW.
 */
static BufferWritingState VKRenderer_AllocateMaskFillBytes(uint32_t size) {
    assert(size > 0);
    assert(size <= MASK_FILL_BUFFER_SIZE);
    VKSDOps* surface = VKRenderer_GetContext()->surface;
    BufferWritingState state = VKRenderer_AllocateBufferData(
            surface, &surface->renderPass->maskFillBufferWriting, 1, size, MASK_FILL_BUFFER_SIZE).state;
    if (!state.bound) {
        if (state.data == NULL) {
            VKTexelBuffer buffer = VKRenderer_GetMaskFillBuffer(surface->device->renderer);
            ARRAY_PUSH_BACK(surface->renderPass->maskFillBuffers) = buffer;
            surface->renderPass->maskFillBufferWriting.data = state.data = buffer.buffer.data;
        }
        assert(ARRAY_SIZE(surface->renderPass->maskFillBuffers) > 0);
        surface->device->vkCmdBindDescriptorSets(surface->renderPass->commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                                 surface->device->renderer->pipelineContext->maskFillPipelineLayout,
                                                 0, 1, &ARRAY_LAST(surface->renderPass->maskFillBuffers).descriptorSet, 0, NULL);
    }
    state.data = (void*) ((uint8_t*) state.data + state.offset);
    return state;
}

static void VKRenderer_ValidateTransform() {
    VKRenderingContext* context = VKRenderer_GetContext();
    assert(context->surface != NULL);
    VKSDOps* surface = context->surface;
    VKRenderPass* renderPass = surface->renderPass;
    if (renderPass->transformModCount != context->transformModCount) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_ValidateTransform: updating transform");
        VKRenderer_FlushDraw(surface);
        renderPass->transformModCount = context->transformModCount;
        // Calculate user to device transform.
        VKTransform transform = {
                2.0f / (float) surface->image->extent.width, 0.0f, -1.0f,
                0.0f, 2.0f / (float) surface->image->extent.height, -1.0f
        };
        // Combine it with user transform.
        VKUtil_ConcatenateTransform(&transform, &context->transform);
        // Push the transform into shader.
        surface->device->vkCmdPushConstants(
                renderPass->commandBuffer,
                surface->device->renderer->pipelineContext->colorPipelineLayout, // TODO what if our pipeline layout differs?
                VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof(VKTransform), &transform
        );
    }
}

/**
 * Setup stencil attachment according to the context clip state.
 * If there is a clip shape, attachment is cleared with "fail" value and then
 * pixels inside the clip shape are set to "pass".
 * If there is no clip shape, whole attachment is cleared with "pass" value.
 */
static void VKRenderer_SetupStencil() {
    VKRenderingContext* context = VKRenderer_GetContext();
    assert(context != NULL && context->surface != NULL && context->surface->renderPass != NULL);
    VKSDOps* surface = context->surface;
    VKRenderPass* renderPass = surface->renderPass;
    VkCommandBuffer cb = renderPass->commandBuffer;
    VKRenderer_FlushDraw(surface);
    VKRenderer_ValidateTransform();

    // Clear stencil attachment.
    VkClearAttachment clearAttachment = {
            .aspectMask = VK_IMAGE_ASPECT_STENCIL_BIT,
            .clearValue.depthStencil.stencil = ARRAY_SIZE(context->clipSpanVertices) > 0 ?
                                               CLIP_STENCIL_EXCLUDE_VALUE : CLIP_STENCIL_INCLUDE_VALUE
    };
    VkClearRect clearRect = {
            .rect = {{0, 0}, surface->stencil->extent},
            .baseArrayLayer = 0,
            .layerCount = 1
    };
    surface->device->vkCmdClearAttachments(cb, 1, &clearAttachment, 1, &clearRect);

    // Bind the clip pipeline.
    surface->device->vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
        VKPipelines_GetPipelineInfo(surface->renderPass->context, (VKPipelineDescriptor) {
            .stencilMode = STENCIL_MODE_ON,
            .dstOpaque = VK_TRUE,
            .inAlphaType = ALPHA_TYPE_UNKNOWN,
            .composite = NO_COMPOSITE,
            .shader = SHADER_CLIP,
            .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
        }).pipeline);
    // Reset vertex buffer binding.
    renderPass->vertexBufferWriting.bound = VK_FALSE;

    // Rasterize clip spans.
    uint32_t primitiveCount = ARRAY_SIZE(context->clipSpanVertices) / 3;
    VKIntVertex* vs;
    for (uint32_t primitivesDrawn = 0; primitivesDrawn < primitiveCount;) {
        uint32_t currentDraw = VK_DRAW(vs, primitiveCount - primitivesDrawn, 3);
        memcpy(vs, context->clipSpanVertices + primitivesDrawn * 3, currentDraw * 3 * sizeof(VKIntVertex));
        primitivesDrawn += currentDraw;
    }
    VKRenderer_FlushDraw(surface);

    // Reset pipeline state.
    renderPass->state.shader = NO_SHADER;
}

void VKRenderer_CleanupLater(VKRenderer* renderer, VKCleanupHandler handler, void* data) {
    POOL_RETURN(renderer, cleanupQueue, ((VKCleanupEntry) { handler, data }));
}

void VKRenderer_RecordBarriers(VKRenderer* renderer,
                               VkBufferMemoryBarrier* bufferBarriers, VKBarrierBatch* bufferBatch,
                               VkImageMemoryBarrier* imageBarriers, VKBarrierBatch* imageBatch) {
    assert(renderer != NULL && renderer->device != NULL);
    if ((bufferBatch == NULL || bufferBatch->barrierCount == 0) &&
        (imageBatch == NULL || imageBatch->barrierCount == 0)) return;
    VkPipelineStageFlags srcStages = (bufferBatch != NULL ? bufferBatch->srcStages : 0) | (imageBatch != NULL ? imageBatch->srcStages : 0);
    VkPipelineStageFlags dstStages = (bufferBatch != NULL ? bufferBatch->dstStages : 0) | (imageBatch != NULL ? imageBatch->dstStages : 0);
    renderer->device->vkCmdPipelineBarrier(VKRenderer_Record(renderer), srcStages, dstStages,
                                           0, 0, NULL,
                                           bufferBatch != NULL ? bufferBatch->barrierCount : 0, bufferBarriers,
                                           imageBatch != NULL ? imageBatch->barrierCount : 0, imageBarriers);
}

/**
 * Setup pipeline for drawing. Returns FALSE if surface is not yet ready for drawing.
 */
VkBool32 VKRenderer_Validate(VKShader shader, VkPrimitiveTopology topology, AlphaType inAlphaType) {
    assert(context.surface != NULL);
    VKSDOps* surface = context.surface;

    // Init render pass.
    if (surface->renderPass == NULL || !surface->renderPass->pendingCommands) {
        // We must only [re]init render pass between frames.
        // Be careful to NOT call VKRenderer_InitRenderPass between render passes within single frame.
        if (!VKRenderer_InitRenderPass(surface)) return VK_FALSE;
    }
    VKRenderPass* renderPass = surface->renderPass;

    // Validate render pass state.
    if (renderPass->state.composite != context.composite ||
        renderPass->clipModCount != context.clipModCount) {
        // ALPHA_COMPOSITE_DST keeps destination intact, so don't even bother to change the state.
        if (context.composite == ALPHA_COMPOSITE_DST) return VK_FALSE;
        // Check whether a logic composite is requested / supported.
        if (COMPOSITE_GROUP(context.composite) == LOGIC_COMPOSITE_GROUP &&
            (surface->device->caps & sun_java2d_vulkan_VKGPU_CAP_LOGIC_OP_BIT) == 0) {
            J2dTraceLn(J2D_TRACE_ERROR, "VKRenderer_Validate: logic composite not supported");
            return VK_FALSE;
        }
        VKCompositeMode oldComposite = renderPass->state.composite;
        VkBool32 clipChanged = renderPass->clipModCount != context.clipModCount;
        // Init stencil attachment, if needed.
        if (clipChanged && ARRAY_SIZE(context.clipSpanVertices) > 0 && surface->stencil == NULL) {
            if (surface->renderPass->pendingCommands) VKRenderer_FlushRenderPass(surface);
            if (!VKSD_ConfigureImageSurfaceStencil(surface)) return VK_FALSE;
        }
        // Update state.
        VKRenderer_FlushDraw(surface);
        renderPass->state.composite = context.composite;
        renderPass->clipModCount = context.clipModCount;
        // Begin render pass.
        VkBool32 renderPassJustStarted = !renderPass->pendingCommands;
        if (renderPassJustStarted) VKRenderer_BeginRenderPass(surface);
        // Validate current clip.
        if (clipChanged || renderPassJustStarted) {
            J2dTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_Validate: updating clip");
            surface->device->vkCmdSetScissor(renderPass->commandBuffer, 0, 1, &context.clipRect);
            if (clipChanged) {
                if (ARRAY_SIZE(context.clipSpanVertices) > 0) {
                    VKRenderer_SetupStencil();
                    renderPass->state.stencilMode = STENCIL_MODE_ON;
                } else renderPass->state.stencilMode = surface->stencil != NULL ? STENCIL_MODE_OFF : STENCIL_MODE_NONE;
            }
        }
        // Validate current composite.
        if (oldComposite != context.composite) {
            J2dTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_Validate: updating composite, old=%d, new=%d", oldComposite, context.composite);
            // Reset the pipeline.
            renderPass->state.shader = NO_SHADER;
        }
    }

    // Validate current transform.
    VKRenderer_ValidateTransform();

    // Validate current pipeline.
    if (renderPass->state.shader != shader ||
        renderPass->state.topology != topology ||
        renderPass->state.inAlphaType != inAlphaType) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_Validate: updating pipeline, old=%d, new=%d",
                   renderPass->state.shader, shader);
        VKRenderer_FlushDraw(surface);
        VkCommandBuffer cb = renderPass->commandBuffer;
        renderPass->state.shader = shader;
        renderPass->state.topology = topology;
        renderPass->state.inAlphaType = inAlphaType;
        VKPipelineInfo pipelineInfo = VKPipelines_GetPipelineInfo(renderPass->context, renderPass->state);
        renderPass->outAlphaType = pipelineInfo.outAlphaType;
        surface->device->vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineInfo.pipeline);
        renderPass->vertexBufferWriting.bound = VK_FALSE;
        renderPass->maskFillBufferWriting.bound = VK_FALSE;
    }
    return VK_TRUE;
}

// Drawing operations.

void VKRenderer_RenderRect(VkBool32 fill, jint x, jint y, jint w, jint h) {
    VKRenderer_RenderParallelogram(fill, (float) x, (float) y,
                                   (float) w, 0, 0,(float) h);
}

void VKRenderer_RenderParallelogram(VkBool32 fill,
                                    jfloat x11, jfloat y11,
                                    jfloat dx21, jfloat dy21,
                                    jfloat dx12, jfloat dy12)
{
    if (!VKRenderer_Validate(SHADER_COLOR,
                             fill ? VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
                                  : VK_PRIMITIVE_TOPOLOGY_LINE_LIST, ALPHA_TYPE_UNKNOWN)) return; // Not ready.
    RGBA c = VKRenderer_GetRGBA(context.surface, context.renderColor);
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
    VK_DRAW(vs, 1, fill ? 6 : 8);
    uint32_t i = 0;
    vs[i++] = p1;
    vs[i++] = p2;
    vs[i++] = p3;
    vs[i++] = p4;
    vs[i++] = p1;
    if (!fill) {
        vs[i++] = p4;
        vs[i++] = p2;
    }
    vs[i++] = p3;
}

void VKRenderer_FillSpans(jint spanCount, jint *spans) {
    if (spanCount == 0) return;
    if (!VKRenderer_Validate(SHADER_COLOR, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, ALPHA_TYPE_UNKNOWN)) return; // Not ready.
    RGBA c = VKRenderer_GetRGBA(context.surface, context.renderColor);

    jfloat x1 = (float)*(spans++);
    jfloat y1 = (float)*(spans++);
    jfloat x2 = (float)*(spans++);
    jfloat y2 = (float)*(spans++);
    VKColorVertex p1 = {x1, y1, c};
    VKColorVertex p2 = {x2, y1, c};
    VKColorVertex p3 = {x2, y2, c};
    VKColorVertex p4 = {x1, y2, c};

    VKColorVertex* vs;
    VK_DRAW(vs, 1, 6);
    vs[0] = p1; vs[1] = p2; vs[2] = p3; vs[3] = p3; vs[4] = p4; vs[5] = p1;

    for (int i = 1; i < spanCount; i++) {
        p1.x = p4.x = (float)*(spans++);
        p1.y = p2.y = (float)*(spans++);
        p2.x = p3.x = (float)*(spans++);
        p3.y = p4.y = (float)*(spans++);

        VK_DRAW(vs, 1, 6);
        vs[0] = p1; vs[1] = p2; vs[2] = p3; vs[3] = p3; vs[4] = p4; vs[5] = p1;
    }
}

void VKRenderer_TextureRender(VkDescriptorSet srcDescriptorSet, VkBuffer vertexBuffer, uint32_t vertexNum,
                              jint filter, VKSamplerWrap wrap) {
    // VKRenderer_Validate was called by VKBlitLoops. TODO refactor this.
    VKSDOps* surface = VKRenderer_GetContext()->surface;
    VKRenderPass* renderPass = surface->renderPass;
    VkCommandBuffer cb = renderPass->commandBuffer;
    VKDevice* device = surface->device;

    // TODO We flush all pending draws and rebind the vertex buffer with the provided one.
    //      We will make it work with our unified vertex buffer later.
    VKRenderer_FlushDraw(surface);
    renderPass->vertexBufferWriting.bound = VK_FALSE;
    VkBuffer vertexBuffers[] = {vertexBuffer};
    VkDeviceSize offsets[] = {0};
    device->vkCmdBindVertexBuffers(cb, 0, 1, vertexBuffers, offsets);
    VkDescriptorSet descriptorSets[] = { srcDescriptorSet,
        VKSamplers_GetDescriptorSet(device, &device->renderer->pipelineContext->samplers, filter, wrap) };
    device->vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                    device->renderer->pipelineContext->texturePipelineLayout, 0, 2, descriptorSets, 0, NULL);
    device->vkCmdDraw(cb, vertexNum, 1, 0, 0);
}

void VKRenderer_MaskFill(jint x, jint y, jint w, jint h,
                         jint maskoff, jint maskscan, jint masklen, uint8_t *mask) {
    if (!VKRenderer_Validate(SHADER_MASK_FILL_COLOR,
                             VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, ALPHA_TYPE_UNKNOWN)) return; // Not ready.
    // maskoff is the offset from the beginning of mask,
    // it's the same as x and y offset within a tile (maskoff % maskscan, maskoff / maskscan).
    // maskscan is the number of bytes in a row/
    // masklen is the size of the whole mask tile, it may be way bigger, than number of actually needed bytes.
    uint32_t byteCount = maskscan * h;
    if (mask == NULL) {
        maskscan = 0;
        byteCount = 1;
    }
    BufferWritingState maskState = VKRenderer_AllocateMaskFillBytes(byteCount);
    if (mask != NULL) {
        memcpy(maskState.data, mask + maskoff, byteCount);
    } else {
        // Special case, fully opaque mask
        *((char *)maskState.data) = (char)0xFF;
    }

    VKMaskFillColorVertex* vs;
    VK_DRAW(vs, 1, 6);
    RGBA c = VKRenderer_GetRGBA(context.surface, context.renderColor);
    int offset = (int) maskState.offset;
    VKMaskFillColorVertex p1 = {x, y, offset, maskscan, c};
    VKMaskFillColorVertex p2 = {x + w, y, offset, maskscan, c};
    VKMaskFillColorVertex p3 = {x + w, y + h, offset, maskscan, c};
    VKMaskFillColorVertex p4 = {x, y + h, offset, maskscan, c};
    // Always keep p1 as provoking vertex for correct origin calculation in vertex shader.
    vs[0] = p1; vs[1] = p3; vs[2] = p2;
    vs[3] = p1; vs[4] = p3; vs[5] = p4;
}

void VKRenderer_DrawImage(VKImage* image, AlphaType alphaType, VkFormat format,
                          VKPackedSwizzle swizzle, jint filter, VKSamplerWrap wrap,
                          float sx1, float sy1, float sx2, float sy2,
                          float dx1, float dy1, float dx2, float dy2) {
    if (!VKRenderer_Validate(SHADER_BLIT, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP, alphaType)) return;
    VKSDOps* surface = VKRenderer_GetContext()->surface;
    VKDevice* device = surface->device;

    // Insert image barrier.
    {
        VkImageMemoryBarrier barrier;
        VKBarrierBatch barrierBatch = {};
        VKImage_AddBarrier(&barrier, &barrierBatch, image,
                           VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                           VK_ACCESS_SHADER_READ_BIT,
                           VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        VKRenderer_RecordBarriers(device->renderer, NULL, NULL, &barrier, &barrierBatch);
    }

    // We are going to change descriptor bindings, so flush drawing.
    VKRenderer_FlushDraw(surface);

    // Bind image & sampler descriptor sets.
    VkDescriptorSet descriptorSets[] = {
            VKImage_GetDescriptorSet(device, image, format, swizzle),
            VKSamplers_GetDescriptorSet(device, &device->renderer->pipelineContext->samplers, filter, wrap)
    };
    device->vkCmdBindDescriptorSets(surface->renderPass->commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                    device->renderer->pipelineContext->texturePipelineLayout, 0, 2, descriptorSets, 0, NULL);

    // Add vertices.
    VKTxVertex* vs;
    VK_DRAW(vs, 1, 4);
    vs[0] = (VKTxVertex) {dx1, dy1, sx1, sy1};
    vs[1] = (VKTxVertex) {dx2, dy1, sx2, sy1};
    vs[2] = (VKTxVertex) {dx1, dy2, sx1, sy2};
    vs[3] = (VKTxVertex) {dx2, dy2, sx2, sy2};
}

void VKRenderer_AddSurfaceDependency(VKSDOps* src, VKSDOps* dst) {
    assert(src != NULL);
    assert(dst != NULL);
    assert(dst->renderPass != NULL);
    // We don't care much about duplicates in our dependency arrays,
    // so just make a lazy deduplication attempt by checking the last element.
    if (ARRAY_SIZE(src->dependentSurfaces) == 0 || ARRAY_LAST(src->dependentSurfaces) != dst) {
        ARRAY_PUSH_BACK(src->dependentSurfaces) = dst;
    }
    if (ARRAY_SIZE(dst->renderPass->usedSurfaces) == 0 || ARRAY_LAST(dst->renderPass->usedSurfaces) != src) {
        ARRAY_PUSH_BACK(dst->renderPass->usedSurfaces) = src;
    }
}
