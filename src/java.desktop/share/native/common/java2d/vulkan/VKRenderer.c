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

#include "VKRenderer_Internal.h"

// Rendering context is only accessed from VKRenderQueue_flushBuffer,
// which is only called from the queue flusher thread, no need for synchronization.
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
VKRenderingContext* VKRenderer_GetContext() {
    return &context;
}

void VKRenderer_DisposeOnPrimaryComplete(VKRenderer* renderer, VKCleanupHandler handler, void* data) {
    POOL_RETURN(renderer, renderer->cleanupQueue, ((VKCleanupEntry) { handler, data }));
}

static void VKRenderer_CleanupPendingResources(VKRenderer* renderer) {
    VKDevice* device = renderer->device;
    for (VKCleanupEntry entry; POOL_TAKE(renderer, renderer->cleanupQueue, entry);) entry.handler(device, entry.data);
}

void VKRenderer_Wait(VKRenderer* renderer, uint64_t timestamp) {
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

VkSemaphore VKRenderer_AddPendingSemaphore(VKRenderer* renderer) {
    VKDevice* device = renderer->device;
    VkSemaphore semaphore;
    if (!POOL_TAKE(renderer, renderer->semaphorePool, semaphore)) {
        VkSemaphoreCreateInfo createInfo = {
                .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO,
                .flags = 0
        };
        VK_IF_ERROR(device->vkCreateSemaphore(device->handle, &createInfo, NULL, &semaphore)) return VK_NULL_HANDLE;
    }
    POOL_RETURN(renderer, renderer->semaphorePool, semaphore);
    return semaphore;
}

VkDescriptorSetLayout VKRenderer_GetImageDescriptorSetLayout(VKRenderer* renderer) {
    return renderer->pipelineContext->textureDescriptorSetLayout;
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
    POOL_FREE(renderer->commandBufferPool);
    POOL_FREE(renderer->secondaryCommandBufferPool);
    POOL_DRAIN_FOR(VkSemaphore, renderer->semaphorePool, entry) {
        device->vkDestroySemaphore(device->handle, *entry, NULL);
    }

    // Destroy buffer pools.
    POOL_DRAIN_FOR(VKBuffer, renderer->vertexBufferPool, entry) {
        device->vkDestroyBuffer(device->handle, entry->handle, NULL);
    }
    POOL_DRAIN_FOR(VKTexelBuffer, renderer->maskFillBufferPool, entry) {
        // No need to destroy descriptor sets one by one, we will destroy the pool anyway.
        device->vkDestroyBufferView(device->handle, entry->view, NULL);
        device->vkDestroyBuffer(device->handle, entry->buffer.handle, NULL);
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
    VkCommandBuffer commandBuffer;
    if (!POOL_TAKE(renderer, renderer->commandBufferPool, commandBuffer)) {
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
        POOL_RETURN(renderer, renderer->commandBufferPool, renderer->commandBuffer);
    } else if (pendingPresentations == 0) {
        return;
    }
    uint64_t signalSemaphoreValues[] = { renderer->writeTimestamp, 0 };
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
VKBufferWriting VKRenderer_AllocateBufferData(VKSDOps* surface, VKBufferWritingState* writingState,
                                              VkDeviceSize elements, VkDeviceSize elementSize,
                                              VkDeviceSize maxBufferSize) {
    assert(surface != NULL && surface->renderPass != NULL && writingState != NULL);
    assert(elementSize <= maxBufferSize);
    VkDeviceSize totalSize = elements * elementSize;
    VKBufferWriting result = { *writingState, elements };
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
