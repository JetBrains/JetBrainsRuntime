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
#include "VKBuffer.h"
#include "VKImage.h"
#include "VKRenderer.h"
#include "VKSurfaceData.h"

#define TRACKED_RESOURCE(NAME) \
typedef struct {               \
    uint64_t timestamp;        \
    NAME value;                \
} Tracked ## NAME

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
    TrackedVkSemaphore* pendingSemaphores;

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

/**
 * Rendering-related info attached to surface.
 */
struct VKRenderPass {
    VKRenderPassContext* context;
    VkFramebuffer        framebuffer;
    VkCommandBuffer      commandBuffer;
    VkBool32             pendingFlush;
    VkBool32             pendingCommands;
    VkBool32             pendingClear;

    VkImageLayout           layout;
    VkPipelineStageFlagBits lastStage;
    VkAccessFlagBits        lastAccess;
    uint64_t                lastTimestamp; // When was this surface last used?
};

#define POP_PENDING(RENDERER, BUFFER, VAR) do {                                                             \
    size_t head = 0, tail = 0;                                                                              \
    if (BUFFER != NULL) { head = RING_BUFFER_T(BUFFER)->head; tail = RING_BUFFER_T(BUFFER)->tail; }         \
    uint64_t timestamp = (head == tail ? 0 : BUFFER[head].timestamp);                                       \
    if (timestamp != 0 && (RENDERER->readTimestamp >= timestamp || (                                        \
        RENDERER->device->vkGetSemaphoreCounterValue(RENDERER->device->handle, RENDERER->timelineSemaphore, \
        &RENDERER->readTimestamp) == VK_SUCCESS && RENDERER->readTimestamp >= timestamp))) {                \
        VAR = BUFFER[head].value;                                                                           \
        RING_BUFFER_POP(BUFFER);                                                                            \
    } else VAR = VK_NULL_HANDLE;                                                                            \
} while(0)


#define PUSH_PENDING(RENDERER, BUFFER, T) RING_BUFFER_PUSH_CUSTOM(BUFFER, BUFFER[tail].timestamp = RENDERER->writeTimestamp; BUFFER[tail].value = T;)

static VkSemaphore VKRenderer_AddPendingSemaphore(VKRenderer* renderer) {
    VKDevice* device = renderer->device;
    VkSemaphore semaphore;
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

    J2dRlsTraceLn(J2D_TRACE_INFO, "VKRenderer_Create: renderer=%p", renderer);
    return renderer;
}

void VKRenderer_Destroy(VKRenderer* renderer) {
    if (renderer == NULL) return;
    VKRenderer_Sync(renderer);
    VKPipelines_DestroyContext(renderer->pipelineContext);
    // No need to destroy command buffers one by one, we will destroy the pool anyway.
    RING_BUFFER_FREE(renderer->pendingCommandBuffers);
    RING_BUFFER_FREE(renderer->pendingSecondaryCommandBuffers);
    for (;;) {
        VkSemaphore semaphore;
        POP_PENDING(renderer, renderer->pendingSemaphores, semaphore);
        if (semaphore == VK_NULL_HANDLE) break;
        renderer->device->vkDestroySemaphore(renderer->device->handle, semaphore, NULL);
    }
    RING_BUFFER_FREE(renderer->pendingSemaphores);
    if (renderer->timelineSemaphore != VK_NULL_HANDLE) {
        renderer->device->vkDestroySemaphore(renderer->device->handle, renderer->timelineSemaphore, NULL);
    }
    if (renderer->commandPool != VK_NULL_HANDLE) {
        renderer->device->vkDestroyCommandPool(renderer->device->handle, renderer->commandPool, NULL);
    }
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
static VkCommandBuffer VKRenderer_Record(VKRenderer* renderer) {
    if (renderer->commandBuffer != VK_NULL_HANDLE) {
        return renderer->commandBuffer;
    }
    VKDevice* device = renderer->device;
    VkCommandBuffer commandBuffer;
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
        VK_IF_ERROR(device->vkEndCommandBuffer(renderer->commandBuffer)) {
            VK_UNHANDLED_ERROR();
            return; // TODO what to do?
        }
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
    VK_IF_ERROR(device->vkQueueSubmit(device->queue, 1, &submitInfo, VK_NULL_HANDLE)) {
        VK_UNHANDLED_ERROR();
        return; // TODO what to do?
    }
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
 * Prepare barrier info to be executed in batch, if needed.
 */
static void VKRenderer_AddSurfaceBarrier(VkImageMemoryBarrier* barriers, uint32_t* numBarriers,
                                         VkPipelineStageFlags* srcStages, VkPipelineStageFlags* dstStages,
                                         VKSDOps* surface, VkPipelineStageFlags stage, VkAccessFlags access, VkImageLayout layout) {
    assert(surface->image != NULL);
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
                .image = surface->image->image,
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
 * Discard all recorded commands for the render pass.
 */
static void VKRenderer_DiscardRenderPass(VKSDOps* surface) {
    assert(surface != NULL && surface->renderPass != NULL);
    if (surface->renderPass->pendingCommands) {
        assert(surface->device != NULL);
        VK_IF_ERROR(surface->device->vkResetCommandBuffer(surface->renderPass->commandBuffer, 0)) {
            VK_UNHANDLED_ERROR(); // TODO what to do?
        }
        surface->renderPass->pendingCommands = VK_FALSE;
        J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_DiscardRenderPass(%p)", surface);
    }
}

void VKRenderer_ReleaseRenderPass(VKSDOps* surface) {
    assert(surface != NULL);
    if (surface->renderPass == NULL) return;
    VKDevice* device = surface->device;
    if (device != NULL && device->renderer != NULL) {
        // Wait while surface resources are being used by the device.
        VKRenderer_Wait(device->renderer, surface->renderPass->lastTimestamp);
        VKRenderer_DiscardRenderPass(surface);
        // Release resources.
        if (surface->renderPass->framebuffer != VK_NULL_HANDLE) {
            device->vkDestroyFramebuffer(device->handle, surface->renderPass->framebuffer, NULL);
        }
        if (surface->renderPass->commandBuffer != VK_NULL_HANDLE) {
            PUSH_PENDING(device->renderer, device->renderer->pendingSecondaryCommandBuffers, surface->renderPass->commandBuffer);

        }
    }
    free(surface->renderPass);
    surface->renderPass = NULL;
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_ReleaseRenderPass(%p)", surface);
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
            .layout = VK_IMAGE_LAYOUT_UNDEFINED,
            .lastStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            .lastAccess = 0,
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
                return;
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
        return;
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

    surface->renderPass->pendingCommands = VK_TRUE;
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_BeginRenderPass(%p)", surface);
}

/**
 * End render pass for the surface and record it into the primary command buffer,
 * which will be executed on the next VKRenderer_Flush.
 */
static void VKRenderer_FlushRenderPass(VKSDOps* surface) {
    assert(surface != NULL && surface->renderPass != NULL);
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
        VK_IF_ERROR(device->vkEndCommandBuffer(surface->renderPass->commandBuffer)) {
            VK_UNHANDLED_ERROR();
            return; // TODO what to do?
        }
        device->vkCmdExecuteCommands(cb, 1, &surface->renderPass->commandBuffer);
        PUSH_PENDING(renderer, renderer->pendingSecondaryCommandBuffers, surface->renderPass->commandBuffer);
        surface->renderPass->commandBuffer = VK_NULL_HANDLE;
    }

    device->vkCmdEndRenderPass(cb);
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_FlushRenderPass(%p): hasCommands=%d, clear=%d", surface, hasCommands, clear);
}

void VKRenderer_FlushSurface(VKSDOps* surface) {
    assert(surface != NULL);
    // TODO this logic should be incorporated into per-surface state tracking object, managing rendering mode changes.
    if (surface->renderPass == NULL || (!surface->renderPass->pendingCommands && !surface->renderPass->pendingFlush)) {
        // We must only [re]init render pass between frames.
        // Now this is correct, but in future we may have frames consisting of multiple render passes,
        // so we must be careful to NOT call VKRenderer_InitRenderPass between render passes within single frame.
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
                .srcOffsets[1] = {surface->image->extent.width, surface->image->extent.height, 1},
                .dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 },
                .dstOffsets[0] = {0, 0, 0},
                .dstOffsets[1] = {surface->image->extent.width, surface->image->extent.height, 1},
        };
        device->vkCmdBlitImage(cb,
                               surface->image->image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
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
    // TODO this logic should be incorporated into per-surface state tracking object, managing rendering mode changes.
    //      this means, we must only really do *pending* flush, if we are between frames.
    if (surface->renderPass != NULL && surface->renderPass->pendingFlush)  {
        if (surface->renderPass->pendingCommands) surface->renderPass->pendingFlush = VK_FALSE;
        else {
            J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_ConfigureSurface(%p): pending flush", surface);
            VKRenderer_FlushSurface(surface);
        }
    }
}

/**
 * Record rendering commands for the surface. This will cause surface initialization.
 * Returns VK_NULL_HANDLE if surface is not ready for rendering.
 */
static VkCommandBuffer VKRenderer_Render(VKSDOps* surface) {
    assert(surface != NULL);
    // TODO following logic should be incorporated into per-surface state tracking object, managing rendering mode changes.
    if (surface->renderPass == NULL || !surface->renderPass->pendingCommands) {
        // We must only [re]init render pass between frames.
        // Now this is correct, but in future we may have frames consisting of multiple render passes,
        // so we must be careful to NOT call VKRenderer_InitRenderPass between render passes within single frame.
        if (!VKRenderer_InitRenderPass(surface)) return VK_NULL_HANDLE;
        VKRenderer_BeginRenderPass(surface);
    }
    return surface->renderPass->commandBuffer;
}

// TODO refactor following part =======================================================================================>

#define ARRAY_TO_VERTEX_BUF(device, vertices)                                           \
    VKBuffer_CreateFromData(device, vertices, ARRAY_SIZE(vertices)*sizeof ((vertices)[0]))

void VKRenderer_TextureRender(VKRenderingContext* context, VKImage *destImage, VKImage *srcImage,
                              VkBuffer vertexBuffer, uint32_t vertexNum)
{
    assert(context != NULL && context->surface != NULL);
    VKSDOps* surface = (VKSDOps*)context->surface;
    VkCommandBuffer cb = VKRenderer_Render(surface);
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


    device->vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, VKPipelines_GetPipeline(surface->renderPass->context, PIPELINE_BLIT));

    VkBuffer vertexBuffers[] = {vertexBuffer};
    VkDeviceSize offsets[] = {0};
    device->vkCmdBindVertexBuffers(cb, 0, 1, vertexBuffers, offsets);
    device->vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                    device->renderer->pipelineContext->texturePipelineLayout, 0, 1, &descriptorSet, 0, NULL);
    device->vkCmdDraw(cb, vertexNum, 1, 0, 0);
}

static void VKRenderer_ColorRender(VKRenderingContext* context, VKPipeline pipeline, VkBuffer vertexBuffer, uint32_t vertexNum) {
    assert(context != NULL && context->surface != NULL);
    VKSDOps* surface = context->surface;
    VkCommandBuffer cb = VKRenderer_Render(surface);
    VKDevice* device = surface->device;

    device->vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, VKPipelines_GetPipeline(surface->renderPass->context, pipeline));
    device->vkCmdPushConstants(
            cb,
            device->renderer->pipelineContext->pipelineLayout,
            VK_SHADER_STAGE_VERTEX_BIT,
            0,
            sizeof(Color),
            &context->color
    );
    VkDeviceSize offsets[] = {0};
    device->vkCmdBindVertexBuffers(cb, 0, 1, &vertexBuffer, offsets);
    device->vkCmdDraw(cb, vertexNum, 1, 0, 0);
}

void
VKRenderer_FillRect(VKRenderingContext* context, jint x, jint y, jint w, jint h)
{
    J2dTraceLn(J2D_TRACE_INFO, "VKRenderer_FillRect %d %d %d %d", x, y, w, h);

    if (w <= 0 || h <= 0) {
        return;
    }

    VKRenderer_RenderParallelogram(context, PIPELINE_FILL_COLOR, x, y, w, 0, 0, h);
}

void VKRenderer_RenderParallelogram(VKRenderingContext* context, VKPipeline pipeline,
                                  jfloat x11, jfloat y11,
                                  jfloat dx21, jfloat dy21,
                                  jfloat dx12, jfloat dy12)
{
    if (context->surface == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKRenderer_RenderParallelogram: current dest is null");
        return;
    }

    VKSDOps *vksdOps = (VKSDOps *)context->surface;
    VKRenderer_Render(vksdOps); // Init rendering state
    float width = vksdOps->image->extent.width;
    float height = vksdOps->image->extent.height;
    J2dRlsTraceLn(J2D_TRACE_VERBOSE,
                  "VKRenderQueue_flushBuffer: FILL_PARALLELOGRAM(W=%f, H=%f)",
                  width, height);
    VKVertex* vertices = NULL;
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
    // TODO coordinate remapping is wrong and covers only quarter of the viewport
    float p1x = -1.0f + x11 / width;
    float p1y = -1.0f + y11 / height;
    float p2x = -1.0f + (x11 + dx21) / width;
    float p2y = -1.0f + (y11 + dy21) / height;
    float p3x = -1.0f + (x11 + dx21 + dx12) / width;
    float p3y = -1.0f + (y11 + dy21 + dy12) / height;
    float p4x = -1.0f + (x11 + dx12) / width;
    float p4y = -1.0f + (y11 + dy12) / height;

    ARRAY_PUSH_BACK(vertices, ((VKVertex) {p1x, p1y}));
    ARRAY_PUSH_BACK(vertices, ((VKVertex) {p2x, p2y}));
    if (pipeline == PIPELINE_DRAW_COLOR) ARRAY_PUSH_BACK(vertices, ((VKVertex) {p2x, p2y}));
    ARRAY_PUSH_BACK(vertices, ((VKVertex) {p3x, p3y}));
    ARRAY_PUSH_BACK(vertices, ((VKVertex) {p3x, p3y}));
    ARRAY_PUSH_BACK(vertices, ((VKVertex) {p4x, p4y}));
    if (pipeline == PIPELINE_DRAW_COLOR) ARRAY_PUSH_BACK(vertices, ((VKVertex) {p4x, p4y}));
    ARRAY_PUSH_BACK(vertices, ((VKVertex) {p1x, p1y}));

    int vertexNum = ARRAY_SIZE(vertices);

    VKBuffer* renderVertexBuffer = ARRAY_TO_VERTEX_BUF(context->surface->device, vertices);
    ARRAY_FREE(vertices);

    VKRenderer_ColorRender(context, pipeline, renderVertexBuffer->buffer, vertexNum);
}

void VKRenderer_FillSpans(VKRenderingContext* context, jint spanCount, jint *spans)
{
    if (context->surface == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKRenderer_FillSpans: current dest is null");
        return;
    }

    if (spanCount == 0) {
        return;
    }

    VKSDOps *vksdOps = (VKSDOps *)context->surface;
    VKRenderer_Render(vksdOps); // Init rendering state
    float width = vksdOps->image->extent.width;
    float height = vksdOps->image->extent.height;
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_FillSpans(W=%f, H=%f, COUNT=%d)",
                  width, height, spanCount);

    const int VERT_COUNT = spanCount * 6;
    VKVertex* vertices = ARRAY_ALLOC(VKVertex, VERT_COUNT);
    for (int i = 0; i < spanCount; i++) {
        jfloat x1 = *(spans++);
        jfloat y1 = *(spans++);
        jfloat x2 = *(spans++);
        jfloat y2 = *(spans++);

        // TODO coordinate remapping is wrong and covers only quarter of the viewport
        float p1x = -1.0f + x1 / width;
        float p1y = -1.0f + y1 / height;
        float p2x = -1.0f + x2 / width;
        float p2y = p1y;
        float p3x = p2x;
        float p3y = -1.0f + y2 / height;
        float p4x = p1x;
        float p4y = p3y;

        ARRAY_PUSH_BACK(vertices, ((VKVertex){p1x,p1y}));

        ARRAY_PUSH_BACK(vertices, ((VKVertex){p2x,p2y}));

        ARRAY_PUSH_BACK(vertices, ((VKVertex){p3x,p3y}));

        ARRAY_PUSH_BACK(vertices, ((VKVertex){p3x,p3y}));

        ARRAY_PUSH_BACK(vertices, ((VKVertex){p4x,p4y}));

        ARRAY_PUSH_BACK(vertices, ((VKVertex){p1x,p1y}));
    }

    VKBuffer *fillVertexBuffer = ARRAY_TO_VERTEX_BUF(context->surface->device, vertices);
    if (!fillVertexBuffer) {
        J2dRlsTrace(J2D_TRACE_ERROR, "Cannot create vertex buffer\n");
        return;
    }
    ARRAY_FREE(vertices);

    VKRenderer_ColorRender(context, PIPELINE_FILL_COLOR, fillVertexBuffer->buffer, VERT_COUNT);
}

#endif /* !HEADLESS */
