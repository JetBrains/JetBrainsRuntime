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

/**
 * Drawing buffer is an any buffer, used for drawing, e.g. vertex buffer, texel storage buffer
 */
typedef struct {
    VkBuffer buffer;
    // Drawing buffer has no ownership over its memory.
    // Provided memory and offset must only be used to flush memory writes.
    // Allocation and freeing is done in pages using VKAllocator.
    VkDeviceMemory memory;
    VkDeviceSize   offset;
    void*          data; // Only sequential writes!
} DrawingBuffer;

typedef struct {
    void*        data;
    VkDeviceSize offset;
    VkBool32     bound; // Whether corresponding buffer was bound to command buffer after last pipeline update.
} DrawingBufferWritingState;

#define TRACKED_RESOURCE(NAME) \
typedef struct {               \
    uint64_t timestamp;        \
    NAME value;                \
} Tracked ## NAME

TRACKED_RESOURCE(DrawingBuffer);
TRACKED_RESOURCE(VkCommandBuffer);
TRACKED_RESOURCE(VkSemaphore);

typedef struct {
    VKMemory*             memoryPages;
    TrackedDrawingBuffer* pendingBuffers;
} DrawingBufferPool;

/**
 * Renderer attached to device.
 */
struct VKRenderer {
    VKDevice*          device;
    VKPipelineContext* pipelineContext;

    TrackedVkCommandBuffer* pendingCommandBuffers;
    TrackedVkCommandBuffer* pendingSecondaryCommandBuffers;
    TrackedVkSemaphore*     pendingSemaphores;
    DrawingBufferPool       vertexBufferPool;
    DrawingBufferPool       maskFillBufferPool;
    VkBufferView*           maskFillBufferViews; // Ring buffer must be in sync with maskFillBufferPool.pendingBuffers.
    VkDescriptorSet*        maskFillBufferDescriptorSets; // Ring buffer must be in sync with maskFillBufferPool.pendingBuffers.
    VkDescriptorPool*       maskFillDescriptorPools;

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
    DrawingBuffer*       vertexBuffers;
    DrawingBuffer*       maskFillBuffers;
    VkBufferView*        maskFillBufferViews; // Array must be in sync with maskFillBuffers.
    VkDescriptorSet*     maskFillBufferDescriptorSets; // Array must be in sync with maskFillBuffers.
    VkFramebuffer        framebuffer[FORMAT_ALIAS_COUNT]; // Only when dynamicRendering=OFF.
    VkCommandBuffer      commandBuffer;

    uint32_t                  firstVertex;
    uint32_t                  vertexCount;
    DrawingBufferWritingState vertexBufferWriting;
    DrawingBufferWritingState maskFillBufferWriting;

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

/**
 * Move remaining elements (pop) from CURRENT to (push) NEW.
 * Then free CURRENT buffer and replace it with NEW.
 */
#define REPLACE_PENDING_BUFFER(CURRENT, NEW) do {          \
    uint32_t size = (uint32_t) RING_BUFFER_SIZE(CURRENT);  \
    for (uint32_t i = 0; i < size; i++) {                  \
        RING_BUFFER_PUSH(NEW, *RING_BUFFER_PEEK(CURRENT)); \
        RING_BUFFER_POP(CURRENT);                          \
    }                                                      \
    RING_BUFFER_FREE(CURRENT);                             \
    (CURRENT) = (NEW);                                     \
} while(0)

typedef void VKRenderer_AllocateDrawingBufferPageCallback(VKRenderer* renderer, TrackedDrawingBuffer* newRing);

/**
 * Retrieves a buffer from the pool, if it is available.
 * Otherwise allocates a new page and creates a set of buffers there.
 * bufferSize, pageSize, usageFlags and findMemoryTypeCallback are assumed to be compile time constants,
 * they are not used, if no allocation takes place.
 */
static DrawingBuffer VKRenderer_GetDrawingBuffer(VKRenderer* renderer, DrawingBufferPool* pool,
                                                 VkDeviceSize bufferSize, VkDeviceSize pageSize,
                                                 VkBufferUsageFlags usageFlags,
                                                 VKAllocator_FindMemoryTypeCallback findMemoryTypeCallback,
                                                 VKRenderer_AllocateDrawingBufferPageCallback allocatePageCallback) {
    // Reuse from pending.
    DrawingBuffer buffer = { .buffer = VK_NULL_HANDLE };
    POP_PENDING(renderer, pool->pendingBuffers, buffer);
    if (buffer.buffer != VK_NULL_HANDLE) return buffer;

    VKDevice* device = renderer->device;
    VKAllocator* alloc = device->allocator;
    // Allocate new ring buffer. Ring buffer grows when size reaches capacity, so leave one more slot to fit all buffers.
    TrackedDrawingBuffer* newRing = CARR_ring_buffer_realloc(NULL, sizeof(TrackedDrawingBuffer),
                                                             RING_BUFFER_SIZE(pool->pendingBuffers) + (pageSize / bufferSize) + 1);
    VK_RUNTIME_ASSERT(newRing);

    // Create single vertex buffer.
    VkBufferCreateInfo bufferInfo = {
            .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
            .size = bufferSize,
            .usage = usageFlags,
            .sharingMode = VK_SHARING_MODE_EXCLUSIVE
    };
    TrackedDrawingBuffer tempBuffer = {
            .timestamp = 0,
            .value.buffer = VK_NULL_HANDLE
    };
    VK_IF_ERROR(device->vkCreateBuffer(device->handle, &bufferInfo, NULL, &tempBuffer.value.buffer)) VK_UNHANDLED_ERROR();
    RING_BUFFER_PUSH(newRing, tempBuffer);

    // Check memory requirements. We aim to create pageSize / bufferSize buffers,
    // but due to implementation-specific alignment requirements this number can be lower (unlikely though).
    VKMemoryRequirements requirements = VKAllocator_BufferRequirements(alloc, tempBuffer.value.buffer);
    VKAllocator_PadToAlignment(&requirements); // Align for array-like allocation.
    VkDeviceSize realBufferSize = requirements.requirements.memoryRequirements.size;
    uint32_t bufferCount = pageSize / realBufferSize;
    requirements.requirements.memoryRequirements.size = pageSize;
    // Find memory type.
    findMemoryTypeCallback(&requirements);
    if (requirements.memoryType == VK_NO_MEMORY_TYPE) VK_UNHANDLED_ERROR();

    // Allocate new memory page.
    VKMemory page = VKAllocator_Allocate(&requirements);
    void* data = VKAllocator_Map(alloc, page);
    VkMappedMemoryRange range = VKAllocator_GetMemoryRange(alloc, page);

    // Create remaining buffers and bind memory.
    for (uint32_t i = 0;;) {
        DrawingBuffer* b = &newRing[i].value;
        b->memory = range.memory;
        b->offset = range.offset + realBufferSize * i;
        b->data = (void*) (((uint8_t*) data) + realBufferSize * i);
        VK_IF_ERROR(device->vkBindBufferMemory(device->handle, b->buffer, b->memory, b->offset)) VK_UNHANDLED_ERROR();
        if ((++i) >= bufferCount) break;
        VK_IF_ERROR(device->vkCreateBuffer(device->handle, &bufferInfo, NULL, &tempBuffer.value.buffer)) VK_UNHANDLED_ERROR();
        RING_BUFFER_PUSH(newRing, tempBuffer);
    }

    if (allocatePageCallback != NULL) allocatePageCallback(renderer, newRing);

    // Move existing pending buffers into new ring and update vertex pool state.
    REPLACE_PENDING_BUFFER(pool->pendingBuffers, newRing);
    ARRAY_PUSH_BACK(pool->memoryPages, page);
    J2dRlsTraceLn3(J2D_TRACE_INFO, "VKRenderer_GetDrawingBuffer: allocated new page, bufferCount=%d, unusedSpace=%d, totalPages=%d",
                   bufferCount, pageSize - bufferCount * realBufferSize, ARRAY_SIZE(pool->memoryPages));

    // Take first.
    tempBuffer = *RING_BUFFER_PEEK(pool->pendingBuffers);
    RING_BUFFER_POP(pool->pendingBuffers);
    return tempBuffer.value;
}

static void VKRenderer_ReleaseDrawingBufferPool(VKRenderer* renderer, DrawingBufferPool* pool) {
    for (;;) {
        DrawingBuffer buffer = { .buffer = VK_NULL_HANDLE };
        POP_PENDING(renderer, pool->pendingBuffers, buffer);
        if (buffer.buffer == VK_NULL_HANDLE) break;
        renderer->device->vkDestroyBuffer(renderer->device->handle, buffer.buffer, NULL);
    }
    RING_BUFFER_FREE(pool->pendingBuffers);
    pool->pendingBuffers = NULL;
    for (uint32_t i = 0; i < ARRAY_SIZE(pool->memoryPages); i++) {
        VKAllocator_Free(renderer->device->allocator, pool->memoryPages[i]);
    }
    ARRAY_FREE(pool->memoryPages);
    pool->memoryPages = NULL;
}

// Vertex buffers have fixed size. How to choose a good one?
// 1. Multiple of 6 - triangle and line modes have x3 and x2 vertices per primitive.
// 2. Multiple of 6 - most common vertex format VKColorVertex has 6 components.
// 3. Some nice power of 2 multiplier, for good alignment and adequate capacity.
#define VERTEX_BUFFER_SIZE (6 * 6 * 256) // 9KiB = 384 * sizeof(VKColorVertex)
#define VERTEX_BUFFER_PAGE_SIZE (4 * 1024 * 1024) // 4MiB - fits 455 buffers and leaves 1KiB unused
static void VKRenderer_FindVertexBufferMemoryType(VKMemoryRequirements* requirements) {
    VKAllocator_FindMemoryType(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                                             VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    VKAllocator_FindMemoryType(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, VK_ALL_MEMORY_PROPERTIES);
}
static DrawingBuffer VKRenderer_GetVertexBuffer(VKRenderer* renderer) {
    return VKRenderer_GetDrawingBuffer(renderer, &renderer->vertexBufferPool, VERTEX_BUFFER_SIZE, VERTEX_BUFFER_PAGE_SIZE,
                                      VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, VKRenderer_FindVertexBufferMemoryType, NULL);
}

#define MASK_FILL_BUFFER_SIZE (256 * 1024) // 256KiB = 256 typical MASK_FILL tiles
#define MASK_FILL_BUFFER_PAGE_SIZE (4 * 1024 * 1024) // 4MiB - fits 16 buffers
static void VKRenderer_FindMaskFillBufferMemoryType(VKMemoryRequirements* requirements) {
    VKAllocator_FindMemoryType(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                               VK_ALL_MEMORY_PROPERTIES);
    VKAllocator_FindMemoryType(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, VK_ALL_MEMORY_PROPERTIES);
}
static void VKRenderer_AllocateMaskFillBufferViewsPage(VKRenderer* renderer, TrackedDrawingBuffer* newRing) {
    assert(renderer != NULL && newRing != NULL);
    VKDevice* device = renderer->device;
    uint32_t bufferCount = (uint32_t) RING_BUFFER_SIZE(newRing);
    uint32_t capacity = (uint32_t) RING_BUFFER_CAPACITY(newRing);
    VkBufferView* newViewRing = CARR_ring_buffer_realloc(NULL, sizeof(VkBufferView), capacity);
    VkDescriptorSet* newDescriptorSetRing = CARR_ring_buffer_realloc(NULL, sizeof(VkDescriptorSet), capacity);
    VK_RUNTIME_ASSERT(newViewRing && newDescriptorSetRing);

    // Create descriptor pool.
    VkDescriptorPoolSize poolSize = {
            .type = VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER,
            .descriptorCount = bufferCount
    };
    VkDescriptorPoolCreateInfo descriptorPoolCreateInfo = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
            .flags = 0,
            .maxSets = bufferCount,
            .poolSizeCount = 1,
            .pPoolSizes = &poolSize
    };
    VkDescriptorPool pool;
    VK_IF_ERROR(device->vkCreateDescriptorPool(device->handle, &descriptorPoolCreateInfo, NULL, &pool)) VK_UNHANDLED_ERROR();
    ARRAY_PUSH_BACK(renderer->maskFillDescriptorPools, pool);

    // Allocate descriptor sets.
    VkDescriptorSetLayout layouts[bufferCount];
    for (uint32_t i = 0; i < bufferCount; i++) layouts[i] = renderer->pipelineContext->maskFillDescriptorSetLayout;
    VkDescriptorSetAllocateInfo allocateInfo = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            .descriptorPool = pool,
            .descriptorSetCount = bufferCount,
            .pSetLayouts = layouts
    };
    VK_IF_ERROR(device->vkAllocateDescriptorSets(device->handle, &allocateInfo, newDescriptorSetRing)) VK_UNHANDLED_ERROR();
    RING_BUFFER_T(newDescriptorSetRing)->tail = bufferCount;

    // Create buffer views and record them into descriptors.
    VkWriteDescriptorSet writeDescriptorSets[bufferCount];
    for (uint32_t i = 0; i < bufferCount; i++) {
        VkBufferViewCreateInfo bufferViewCreateInfo = {
                .sType = VK_STRUCTURE_TYPE_BUFFER_VIEW_CREATE_INFO,
                .buffer = newRing[i].value.buffer,
                .format = VK_FORMAT_R8_UNORM,
                .offset = 0,
                .range = VK_WHOLE_SIZE
        };
        VkBufferView bufferView;
        VK_IF_ERROR(device->vkCreateBufferView(device->handle, &bufferViewCreateInfo, NULL, &bufferView)) VK_UNHANDLED_ERROR();
        RING_BUFFER_PUSH(newViewRing, bufferView);

        writeDescriptorSets[i] = (VkWriteDescriptorSet) {
                .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                .dstSet = newDescriptorSetRing[i],
                .dstBinding = 0,
                .dstArrayElement = 0,
                .descriptorCount = 1,
                .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER,
                .pTexelBufferView = &newViewRing[i]
        };
    }
    device->vkUpdateDescriptorSets(device->handle, bufferCount, writeDescriptorSets, 0, NULL);

    REPLACE_PENDING_BUFFER(renderer->maskFillBufferViews, newViewRing);
    REPLACE_PENDING_BUFFER(renderer->maskFillBufferDescriptorSets, newDescriptorSetRing);
}
typedef struct {
    DrawingBuffer   buffer;
    VkBufferView    view;
    VkDescriptorSet descriptorSet;
} MaskFillBuffer;
static MaskFillBuffer VKRenderer_GetMaskFillBuffer(VKRenderer* renderer) {
    MaskFillBuffer result;
    result.buffer = VKRenderer_GetDrawingBuffer(renderer, &renderer->maskFillBufferPool,
                                                MASK_FILL_BUFFER_SIZE, MASK_FILL_BUFFER_PAGE_SIZE,
                                                VK_BUFFER_USAGE_STORAGE_TEXEL_BUFFER_BIT,
                                                VKRenderer_FindMaskFillBufferMemoryType,
                                                VKRenderer_AllocateMaskFillBufferViewsPage);
    result.view = *RING_BUFFER_PEEK(renderer->maskFillBufferViews);
    result.descriptorSet = *RING_BUFFER_PEEK(renderer->maskFillBufferDescriptorSets);
    RING_BUFFER_POP(renderer->maskFillBufferViews);
    RING_BUFFER_POP(renderer->maskFillBufferDescriptorSets);
    // maskFillBufferPool.pendingBuffers, maskFillBufferViews and maskFillBufferDescriptorSets must be in sync.
    assert(RING_BUFFER_SIZE(renderer->maskFillBufferPool.pendingBuffers) == RING_BUFFER_SIZE(renderer->maskFillBufferViews));
    assert(RING_BUFFER_SIZE(renderer->maskFillBufferPool.pendingBuffers) == RING_BUFFER_SIZE(renderer->maskFillBufferDescriptorSets));
    return result;
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
    // No need to destroy descriptor sets one by one, we will destroy the pool anyway.
    RING_BUFFER_FREE(renderer->maskFillBufferDescriptorSets);
    for (uint32_t i = 0; i < ARRAY_SIZE(renderer->maskFillDescriptorPools); i++) {
        device->vkDestroyDescriptorPool(device->handle, renderer->maskFillDescriptorPools[i], NULL);
    }
    ARRAY_FREE(renderer->maskFillDescriptorPools);
    for (;;) {
        VkBufferView* view = RING_BUFFER_PEEK(renderer->maskFillBufferViews);
        if (view == NULL) break;
        RING_BUFFER_POP(renderer->maskFillBufferViews);
        renderer->device->vkDestroyBufferView(device->handle, *view, NULL);
    }
    RING_BUFFER_FREE(renderer->maskFillBufferViews);
    VKRenderer_ReleaseDrawingBufferPool(renderer, &renderer->maskFillBufferPool);
    VKRenderer_ReleaseDrawingBufferPool(renderer, &renderer->vertexBufferPool);
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
 * Push all buffers in a given array into pending queue and updates their corresponding VkMappedMemoryRange structs.
 */
static void VKRenderer_FlushDrawingBuffers(VKRenderer* renderer, DrawingBufferPool* pool, DrawingBuffer** buffers, VkMappedMemoryRange* ranges) {
    uint32_t count = (uint32_t) ARRAY_SIZE(*buffers);
    for (uint32_t i = 0; i < count; i++) {
        DrawingBuffer* vb = &(*buffers)[i];
        ranges[i] = (VkMappedMemoryRange) {
                .sType  = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
                .memory = vb->memory,
                .offset = vb->offset,
                .size   = VERTEX_BUFFER_SIZE
        };
        PUSH_PENDING(renderer, pool->pendingBuffers, *vb);
    }
    ARRAY_RESIZE(*buffers, 0);
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
    surface->renderPass->vertexBufferWriting = (DrawingBufferWritingState) {NULL, 0, VK_FALSE};
    surface->renderPass->maskFillBufferWriting = (DrawingBufferWritingState) {NULL, 0, VK_FALSE};
    size_t vertexBufferCount = ARRAY_SIZE(surface->renderPass->vertexBuffers);
    size_t maskFillBufferCount = ARRAY_SIZE(surface->renderPass->maskFillBuffers);
    // maskFillBuffers, maskFillBufferViews and maskFillBufferDescriptorSets must be in sync.
    assert(maskFillBufferCount == ARRAY_SIZE(surface->renderPass->maskFillBufferViews));
    assert(maskFillBufferCount == ARRAY_SIZE(surface->renderPass->maskFillBufferDescriptorSets));
    if (vertexBufferCount == 0 && maskFillBufferCount == 0) return;
    VkMappedMemoryRange memoryRanges[vertexBufferCount + maskFillBufferCount];
    VKRenderer_FlushDrawingBuffers(surface->device->renderer, &surface->device->renderer->vertexBufferPool,
                            &surface->renderPass->vertexBuffers, memoryRanges);
    VKRenderer_FlushDrawingBuffers(surface->device->renderer, &surface->device->renderer->maskFillBufferPool,
                            &surface->renderPass->maskFillBuffers, memoryRanges + vertexBufferCount);
    for (uint32_t i = 0; i < maskFillBufferCount; i++) {
        RING_BUFFER_PUSH(surface->device->renderer->maskFillBufferViews, surface->renderPass->maskFillBufferViews[i]);
        RING_BUFFER_PUSH(surface->device->renderer->maskFillBufferDescriptorSets, surface->renderPass->maskFillBufferDescriptorSets[i]);
    }
    ARRAY_RESIZE(surface->renderPass->maskFillBufferViews, 0);
    ARRAY_RESIZE(surface->renderPass->maskFillBufferDescriptorSets, 0);
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
        ARRAY_FREE(surface->renderPass->maskFillBuffers);
        ARRAY_FREE(surface->renderPass->maskFillBufferViews);
        ARRAY_FREE(surface->renderPass->maskFillBufferDescriptorSets);
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
    // Technically, in case of dynamicRendering=ON, this could be postponed right until VKRenderer_FlushSurface,
    // but we cannot change image extent in the middle of render pass anyway, so there is no point in delaying it.
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
            .layout = VK_IMAGE_LAYOUT_UNDEFINED,
            .lastStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            .lastAccess = 0,
            .lastTimestamp = 0
    };

    // Initialize pipelines. They are cached until surface format changes.
    if (renderPass->context == NULL) {
        renderPass->context = VKPipelines_GetRenderPassContext(renderer->pipelineContext, surface->format[FORMAT_ALIAS_REAL]);
    }

    // Initialize framebuffer. It is only needed when dynamicRendering=OFF.
    if (!device->dynamicRendering && renderPass->framebuffer[FORMAT_ALIAS_REAL] == VK_NULL_HANDLE) {
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
    VkCommandBufferInheritanceRenderingInfoKHR inheritanceRenderingInfo;
    VkCommandBufferInheritanceInfo inheritanceInfo = {
            .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_INHERITANCE_INFO
    };
    if (device->dynamicRendering) {
        inheritanceRenderingInfo = (VkCommandBufferInheritanceRenderingInfoKHR) {
                .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_INHERITANCE_RENDERING_INFO_KHR,
                .flags = VK_RENDERING_CONTENTS_SECONDARY_COMMAND_BUFFERS_BIT,
                .viewMask = 0,
                .colorAttachmentCount = 1,
                .pColorAttachmentFormats = &surface->format[formatAlias],
                .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT
        };
        inheritanceInfo.pNext = &inheritanceRenderingInfo;
    } else {
        inheritanceInfo.renderPass = surface->renderPass->context->renderPass[formatAlias];
        inheritanceInfo.subpass = 0;
        inheritanceInfo.framebuffer = surface->renderPass->framebuffer[formatAlias];
    }
    VkCommandBufferBeginInfo commandBufferBeginInfo = {
            .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
            .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT | VK_COMMAND_BUFFER_USAGE_RENDER_PASS_CONTINUE_BIT,
            .pInheritanceInfo = &inheritanceInfo
    };
    VK_IF_ERROR(device->vkBeginCommandBuffer(commandBuffer, &commandBufferBeginInfo)) {
        renderer->device->vkFreeCommandBuffers(renderer->device->handle, renderer->commandPool, 1, &commandBuffer);
        VK_UNHANDLED_ERROR();
    }

    // When dynamicRendering=ON, we specify that we want to clear the attachment instead of
    // loading its content at the beginning of rendering, see VKRenderer_FlushSurface.
    // But with dynamicRendering=OFF we need to clear the attachment manually at the beginning of render pass.
    if (!device->dynamicRendering && surface->renderPass->pendingClear) {
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
    if (device->dynamicRendering) {
        VkRenderingAttachmentInfoKHR colorAttachmentInfo = {
                .sType = VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR,
                .imageView = surface->view[formatAlias],
                .imageLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                .resolveMode = VK_RESOLVE_MODE_NONE_KHR,
                .resolveImageView = VK_NULL_HANDLE,
                .resolveImageLayout = VK_IMAGE_LAYOUT_UNDEFINED,
                .loadOp = clear ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD,
                .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
                .clearValue = surface->background.vkClearValue
        };
        surface->renderPass->pendingClear = VK_FALSE;
        VkRect2D renderArea = {{0, 0}, surface->extent};
        VkRenderingInfoKHR renderingInfo = {
                .sType = VK_STRUCTURE_TYPE_RENDERING_INFO_KHR,
                .flags = VK_RENDERING_CONTENTS_SECONDARY_COMMAND_BUFFERS_BIT,
                .renderArea = renderArea,
                .layerCount = 1,
                .viewMask = 0,
                .colorAttachmentCount = 1,
                .pColorAttachments = &colorAttachmentInfo,
                .pDepthAttachment = NULL,
                .pStencilAttachment = NULL
        };
        device->vkCmdBeginRenderingKHR(cb, &renderingInfo);
    } else {
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
    }

    // Execute render pass commands.
    if (surface->renderPass->pendingCommands) {
        surface->renderPass->pendingCommands = VK_FALSE;
        VK_IF_ERROR(device->vkEndCommandBuffer(surface->renderPass->commandBuffer)) VK_UNHANDLED_ERROR();
        device->vkCmdExecuteCommands(cb, 1, &surface->renderPass->commandBuffer);
        PUSH_PENDING(renderer, renderer->pendingSecondaryCommandBuffers, surface->renderPass->commandBuffer);
        surface->renderPass->commandBuffer = VK_NULL_HANDLE;
    }

    if (device->dynamicRendering) {
        device->vkCmdEndRenderingKHR(cb);
    } else {
        device->vkCmdEndRenderPass(cb);
    }
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
 * Setup pipeline for drawing. Returns FALSE if surface is not yet ready for drawing.
 */
static VkBool32 VKRenderer_Validate(VKRenderingContext* context, PipelineType pipeline) {
    assert(context != NULL && context->surface != NULL);
    VKSDOps* surface = context->surface;

    // Validate render pass state.
    if (surface->renderPass == NULL || !surface->renderPass->pendingCommands) {
        // We must only [re]init render pass between frames.
        // Be careful to NOT call VKRenderer_InitRenderPass between render passes within single frame.
        if (!VKRenderer_InitRenderPass(surface)) return VK_FALSE;
    }
    VKRenderPass* renderPass = surface->renderPass;

    // Validate current composite.
    if (renderPass->currentComposite != context->composite) {
        // ALPHA_COMPOSITE_DST keeps destination intact, so don't even bother to change the state.
        if (context->composite == ALPHA_COMPOSITE_DST) return VK_FALSE;
        J2dTraceLn2(J2D_TRACE_VERBOSE, "VKRenderer_Validate: updating composite, old=%d, new=%d",
                    surface->renderPass->currentComposite, context->composite);
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
        J2dTraceLn2(J2D_TRACE_VERBOSE, "VKRenderer_Validate: updating pipeline, old=%d, new=%d",
                    surface->renderPass->currentPipeline, pipeline);
        VKRenderer_FlushDraw(surface);
        VkCommandBuffer cb = renderPass->commandBuffer;
        renderPass->currentPipeline = pipeline;
        surface->device->vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                           VKPipelines_GetPipeline(renderPass->context, context->composite, pipeline));
        surface->renderPass->vertexBufferWriting.bound = VK_FALSE;
        surface->renderPass->maskFillBufferWriting.bound = VK_FALSE;
    }
    return VK_TRUE;
}

/**
 * Allocate bytes for writing into buffer. Returned state contains:
 * - data   - pointer to the beginning of buffer, or NULL, if there is no buffer yet.
 * - offset - writing offset into the buffer data, or 0, if there is no buffer yet.
 * - bound  - whether corresponding buffer is bound to the command buffer,
 *            caller is responsible for checking this value and setting up & binding the buffer.
 */
inline DrawingBufferWritingState VKRenderer_AllocateDrawingBufferData(VKSDOps* surface,
                                                                      DrawingBufferWritingState* writingState,
                                                                      VkDeviceSize size, VkDeviceSize maxBufferSize) {
    assert(surface != NULL && surface->renderPass != NULL && writingState != NULL);
    assert(size <= maxBufferSize);
    DrawingBufferWritingState result = *writingState;
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
 * It is responsibility of the caller to pass correct vertexSize, matching current pipeline.
 * This function cannot draw more vertices than fits into single vertex buffer at once.
 */
static void* VKRenderer_Draw(VKRenderingContext* context, uint32_t vertices, size_t vertexSize) {
    assert(vertices > 0 && vertexSize > 0);
    assert(vertexSize * vertices <= VERTEX_BUFFER_SIZE);
    VKSDOps* surface = context->surface;
    DrawingBufferWritingState state = VKRenderer_AllocateDrawingBufferData(
            surface, &surface->renderPass->vertexBufferWriting, (VkDeviceSize) (vertexSize * vertices), VERTEX_BUFFER_SIZE);
    if (!state.bound) {
        if (state.data == NULL) {
            DrawingBuffer buffer = VKRenderer_GetVertexBuffer(surface->device->renderer);
            ARRAY_PUSH_BACK(surface->renderPass->vertexBuffers, buffer);
            surface->renderPass->vertexBufferWriting.data = state.data = buffer.data;
        }
        assert(ARRAY_SIZE(surface->renderPass->vertexBuffers) > 0);
        surface->renderPass->firstVertex = surface->renderPass->vertexCount = 0;
        surface->device->vkCmdBindVertexBuffers(surface->renderPass->commandBuffer, 0, 1,
                                                &(ARRAY_LAST(surface->renderPass->vertexBuffers).buffer), &state.offset);
    }
    surface->renderPass->vertexCount += vertices;
    return (void*) ((uint8_t*) state.data + state.offset);
}

/**
 * Convenience wrapper for VKRenderer_Draw, providing pointer to vertices of requested type.
 */
#define VK_DRAW(VERTICES, CONTEXT, VERTEX_COUNT) \
    (VERTICES) = VKRenderer_Draw((CONTEXT), (VERTEX_COUNT), sizeof((VERTICES)[0]))

/**
 * Allocate vertices from mask fill buffer. VKRenderer_Validate must have been called before.
 * This function cannot take more bytes than fits into single mask fill buffer at once.
 * Caller must write data at the returned pointer DrawingBufferWritingState.data
 * and take into account DrawingBufferWritingState.offset from the beginning of the bound buffer.
 */
static DrawingBufferWritingState VKRenderer_AllocateMaskFillBytes(VKRenderingContext* context, uint32_t size) {
    assert(size > 0);
    assert(size <= MASK_FILL_BUFFER_SIZE);
    VKSDOps* surface = context->surface;
    DrawingBufferWritingState state = VKRenderer_AllocateDrawingBufferData(
            surface, &surface->renderPass->maskFillBufferWriting, size, MASK_FILL_BUFFER_SIZE);
    if (!state.bound) {
        if (state.data == NULL) {
            MaskFillBuffer buffer = VKRenderer_GetMaskFillBuffer(surface->device->renderer);
            ARRAY_PUSH_BACK(surface->renderPass->maskFillBuffers, buffer.buffer);
            ARRAY_PUSH_BACK(surface->renderPass->maskFillBufferViews, buffer.view);
            ARRAY_PUSH_BACK(surface->renderPass->maskFillBufferDescriptorSets, buffer.descriptorSet);
            surface->renderPass->maskFillBufferWriting.data = state.data = buffer.buffer.data;
        }
        // maskFillBuffers, maskFillBufferViews and maskFillBufferDescriptorSets must be in sync.
        assert(ARRAY_SIZE(surface->renderPass->maskFillBuffers) == ARRAY_SIZE(surface->renderPass->maskFillBufferViews));
        assert(ARRAY_SIZE(surface->renderPass->maskFillBuffers) == ARRAY_SIZE(surface->renderPass->maskFillBufferDescriptorSets));
        assert(ARRAY_SIZE(surface->renderPass->maskFillBuffers) > 0);
        surface->device->vkCmdBindDescriptorSets(context->surface->renderPass->commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                                 surface->device->renderer->pipelineContext->maskFillPipelineLayout,
                                                 0, 1, &ARRAY_LAST(surface->renderPass->maskFillBufferDescriptorSets), 0, NULL);
    }
    state.data = (void*) ((uint8_t*) state.data + state.offset);
    return state;
}

// Drawing operations.

void VKRenderer_RenderRect(VKRenderingContext* context, PipelineType pipeline, jint x, jint y, jint w, jint h) {
    VKRenderer_RenderParallelogram(context, pipeline, (float) x, (float) y, (float) w, 0, 0, (float) h);
}

void VKRenderer_RenderParallelogram(VKRenderingContext* context, PipelineType pipeline,
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
    DrawingBufferWritingState maskState = VKRenderer_AllocateMaskFillBytes(context, byteCount);
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
