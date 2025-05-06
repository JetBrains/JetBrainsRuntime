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

#include "sun_java2d_vulkan_VKGPU.h"
#include "VKRenderer_Internal.h"

VkBool32 VKRenderer_InitRenderPass(VKSDOps* surface);
void VKRenderer_BeginRenderPass(VKSDOps* surface);

void VKRenderer_FlushDraw(VKSDOps* surface) {
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
void VKRenderer_ResetDrawing(VKSDOps* surface) {
    assert(surface != NULL && surface->renderPass != NULL);
    surface->renderPass->state.composite = NO_COMPOSITE;
    surface->renderPass->state.shader = NO_SHADER;
    surface->renderPass->transformModCount = 0;
    surface->renderPass->firstVertex = 0;
    surface->renderPass->vertexCount = 0;
    surface->renderPass->vertexBufferWriting = (VKBufferWritingState) {NULL, 0, VK_FALSE};
    surface->renderPass->maskFillBufferWriting = (VKBufferWritingState) {NULL, 0, VK_FALSE};
    size_t vertexBufferCount = ARRAY_SIZE(surface->renderPass->vertexBuffers);
    size_t maskFillBufferCount = ARRAY_SIZE(surface->renderPass->maskFillBuffers);
    if (vertexBufferCount == 0 && maskFillBufferCount == 0) return;
    VKRenderer* renderer = surface->device->renderer;
    VkMappedMemoryRange memoryRanges[vertexBufferCount + maskFillBufferCount];
    for (uint32_t i = 0; i < vertexBufferCount; i++) {
        memoryRanges[i] = surface->renderPass->vertexBuffers[i].range;
        POOL_RETURN(renderer, surface->device->renderer->vertexBufferPool, surface->renderPass->vertexBuffers[i]);
    }
    for (uint32_t i = 0; i < maskFillBufferCount; i++) {
        memoryRanges[vertexBufferCount + i] = surface->renderPass->maskFillBuffers[i].buffer.range;
        POOL_RETURN(renderer, surface->device->renderer->maskFillBufferPool, surface->renderPass->maskFillBuffers[i]);
    }
    ARRAY_RESIZE(surface->renderPass->vertexBuffers, 0);
    ARRAY_RESIZE(surface->renderPass->maskFillBuffers, 0);
    VK_IF_ERROR(surface->device->vkFlushMappedMemoryRanges(surface->device->handle,
                                                           vertexBufferCount + maskFillBufferCount, memoryRanges)) {}
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
    for (uint32_t primitivesDrawn = 0;;) {
        uint32_t currentDraw = primitiveCount - primitivesDrawn;
        if (currentDraw == 0) break;
        currentDraw = VK_DRAW(vs, currentDraw, 3);
        memcpy(vs, context->clipSpanVertices + primitivesDrawn * 3, currentDraw * 3 * sizeof(VKIntVertex));
        primitivesDrawn += currentDraw;
    }
    VKRenderer_FlushDraw(surface);

    // Reset pipeline state.
    renderPass->state.shader = NO_SHADER;
}

/**
 * Setup pipeline for drawing. Returns FALSE if surface is not yet ready for drawing.
 */
VkBool32 VKRenderer_Validate(VKShader shader, VkPrimitiveTopology topology, AlphaType inAlphaType) {
    VKRenderingContext* context = VKRenderer_GetContext();
    assert(context->surface != NULL);
    VKSDOps* surface = context->surface;

    // Init render pass.
    if (surface->renderPass == NULL || !surface->renderPass->pendingCommands) {
        // We must only [re]init render pass between frames.
        // Be careful to NOT call VKRenderer_InitRenderPass between render passes within single frame.
        if (!VKRenderer_InitRenderPass(surface)) return VK_FALSE;
    }
    VKRenderPass* renderPass = surface->renderPass;

    // Validate render pass state.
    if (renderPass->state.composite != context->composite ||
        renderPass->clipModCount != context->clipModCount) {
        // ALPHA_COMPOSITE_DST keeps destination intact, so don't even bother to change the state.
        if (context->composite == ALPHA_COMPOSITE_DST) return VK_FALSE;
        // Check whether a logic composite is requested / supported.
        if (COMPOSITE_GROUP(context->composite) == LOGIC_COMPOSITE_GROUP &&
            (surface->device->caps & sun_java2d_vulkan_VKGPU_CAP_LOGIC_OP_BIT) == 0) {
            J2dTraceLn(J2D_TRACE_ERROR, "VKRenderer_Validate: logic composite not supported");
            return VK_FALSE;
        }
        VKCompositeMode oldComposite = renderPass->state.composite;
        VkBool32 clipChanged = renderPass->clipModCount != context->clipModCount;
        // Init stencil attachment, if needed.
        if (clipChanged && ARRAY_SIZE(context->clipSpanVertices) > 0 && surface->stencil == NULL) {
            if (surface->renderPass->pendingCommands) VKRenderer_FlushRenderPass(surface);
            if (!VKSD_ConfigureImageSurfaceStencil(surface)) return VK_FALSE;
        }
        // Update state.
        VKRenderer_FlushDraw(surface);
        renderPass->state.composite = context->composite;
        renderPass->clipModCount = context->clipModCount;
        // Begin render pass.
        VkBool32 renderPassJustStarted = !renderPass->pendingCommands;
        if (renderPassJustStarted) VKRenderer_BeginRenderPass(surface);
        // Validate current clip.
        if (clipChanged || renderPassJustStarted) {
            J2dTraceLn(J2D_TRACE_VERBOSE, "VKRenderer_Validate: updating clip");
            surface->device->vkCmdSetScissor(renderPass->commandBuffer, 0, 1, &context->clipRect);
            if (clipChanged) {
                if (ARRAY_SIZE(context->clipSpanVertices) > 0) {
                    VKRenderer_SetupStencil();
                    renderPass->state.stencilMode = STENCIL_MODE_ON;
                } else renderPass->state.stencilMode = surface->stencil != NULL ? STENCIL_MODE_OFF : STENCIL_MODE_NONE;
            }
        }
        // Validate current composite.
        if (oldComposite != context->composite) {
            J2dTraceLn2(J2D_TRACE_VERBOSE, "VKRenderer_Validate: updating composite, old=%d, new=%d", oldComposite, context->composite);
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
        J2dTraceLn2(J2D_TRACE_VERBOSE, "VKRenderer_Validate: updating pipeline, old=%d, new=%d",
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
