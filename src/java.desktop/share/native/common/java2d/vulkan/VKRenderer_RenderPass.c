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

#include "VKRenderer_Internal.h"

VkSemaphore VKRenderer_AddPendingSemaphore(VKRenderer* renderer);
void VKRenderer_ResetDrawing(VKSDOps* surface);

static void VKRenderer_CleanupFramebuffer(VKDevice* device, void* data) {
    device->vkDestroyFramebuffer(device->handle, (VkFramebuffer) data, NULL);
    J2dRlsTraceLn1(J2D_TRACE_VERBOSE, "VKRenderer_CleanupFramebuffer(%p)", data);
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
        VKRenderer* renderer = device->renderer;
        // Wait while surface resources are being used by the device.
        VKRenderer_Wait(renderer, surface->renderPass->lastTimestamp);
        VKRenderer_DiscardRenderPass(surface);
        // Release resources.
        device->vkDestroyFramebuffer(device->handle, surface->renderPass->framebuffer, NULL);
        if (surface->renderPass->commandBuffer != VK_NULL_HANDLE) {
            POOL_RETURN(renderer, renderer->secondaryCommandBufferPool, surface->renderPass->commandBuffer);
        }
        ARRAY_FREE(surface->renderPass->vertexBuffers);
        ARRAY_FREE(surface->renderPass->maskFillBuffers);
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
VkBool32 VKRenderer_InitRenderPass(VKSDOps* surface) {
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
            .lastTimestamp = 0,
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

    J2dRlsTraceLn1(J2D_TRACE_VERBOSE, "VKRenderer_InitRenderPass(%p)", surface);
    return VK_TRUE;
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
        POOL_RETURN(device->renderer, device->renderer->cleanupQueue,
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

        J2dRlsTraceLn1(J2D_TRACE_VERBOSE, "VKRenderer_InitFramebuffer(%p)", surface);
    }
}

/**
 * Begin render pass for the surface.
 */
void VKRenderer_BeginRenderPass(VKSDOps* surface) {
    assert(surface != NULL && surface->renderPass != NULL && !surface->renderPass->pendingCommands);
    VKRenderer_InitFramebuffer(surface);
    // We may have a pending flush, which is already obsolete.
    surface->renderPass->pendingFlush = VK_FALSE;
    VKDevice* device = surface->device;
    VKRenderer* renderer = device->renderer;

    // Initialize command buffer.
    VkCommandBuffer commandBuffer = surface->renderPass->commandBuffer;
    if (commandBuffer == VK_NULL_HANDLE) {
        if (!POOL_TAKE(renderer, renderer->secondaryCommandBufferPool, commandBuffer)) {
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
    J2dRlsTraceLn1(J2D_TRACE_VERBOSE, "VKRenderer_BeginRenderPass(%p)", surface);
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
    surface->renderPass->lastTimestamp = renderer->writeTimestamp;
    VkCommandBuffer cb = VKRenderer_Record(renderer);

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
        POOL_RETURN(renderer, renderer->secondaryCommandBufferPool, surface->renderPass->commandBuffer);
        surface->renderPass->commandBuffer = VK_NULL_HANDLE;
    }

    device->vkCmdEndRenderPass(cb);
    VKRenderer_ResetDrawing(surface);
    J2dRlsTraceLn3(J2D_TRACE_VERBOSE, "VKRenderer_FlushRenderPass(%p): hasCommands=%d, clear=%d", surface, hasCommands, clear);
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
        surface->renderPass->lastTimestamp = renderer->writeTimestamp;
        VkCommandBuffer cb = VKRenderer_Record(renderer);

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
        J2dRlsTraceLn1(J2D_TRACE_VERBOSE, "VKRenderer_FlushSurface(%p): queued for presentation", surface);
    }
}
