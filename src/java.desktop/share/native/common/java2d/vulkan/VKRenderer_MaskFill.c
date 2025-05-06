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

#define MASK_FILL_BUFFER_SIZE (256 * 1024) // 256KiB = 256 typical MASK_FILL tiles
#define MASK_FILL_BUFFER_PAGE_SIZE (4 * 1024 * 1024) // 4MiB - fits 16 buffers
static void VKRenderer_FindMaskFillBufferMemoryType(VKMemoryRequirements* requirements) {
    VKAllocator_FindMemoryType(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                               VK_ALL_MEMORY_PROPERTIES);
    VKAllocator_FindMemoryType(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, VK_ALL_MEMORY_PROPERTIES);
}
static VKTexelBuffer VKRenderer_GetMaskFillBuffer(VKRenderer* renderer) {
    VKTexelBuffer buffer;
    if (POOL_TAKE(renderer, renderer->maskFillBufferPool, buffer)) return buffer;
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
    for (uint32_t i = 1; i < bufferCount; i++) POOL_INSERT(renderer->maskFillBufferPool, texelBuffers[i]);
    ARRAY_PUSH_BACK(renderer->bufferMemoryPages) = page;
    ARRAY_PUSH_BACK(renderer->descriptorPools) = descriptorPool;
    return texelBuffers[0];
}

/**
 * Allocate bytes from mask fill buffer. VKRenderer_Validate must have been called before.
 * This function cannot take more bytes than fits into single mask fill buffer at once.
 * Caller must write data at the returned pointer VKBufferWritingState.data
 * and take into account VKBufferWritingState.offset from the beginning of the bound buffer.
 * This function can invalidate drawing state, always call it before VK_DRAW.
 */
static VKBufferWritingState VKRenderer_AllocateMaskFillBytes(uint32_t size) {
    assert(size > 0);
    assert(size <= MASK_FILL_BUFFER_SIZE);
    VKSDOps* surface = VKRenderer_GetContext()->surface;
    VKBufferWritingState state = VKRenderer_AllocateBufferData(
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
    VKBufferWritingState maskState = VKRenderer_AllocateMaskFillBytes(byteCount);
    if (mask != NULL) {
        memcpy(maskState.data, mask + maskoff, byteCount);
    } else {
        // Special case, fully opaque mask
        *((char *)maskState.data) = (char)0xFF;
    }

    VKMaskFillColorVertex* vs;
    VK_DRAW(vs, 1, 6);
    RGBA c = VKRenderer_GetColor();
    int offset = (int) maskState.offset;
    VKMaskFillColorVertex p1 = {x, y, offset, maskscan, c};
    VKMaskFillColorVertex p2 = {x + w, y, offset, maskscan, c};
    VKMaskFillColorVertex p3 = {x + w, y + h, offset, maskscan, c};
    VKMaskFillColorVertex p4 = {x, y + h, offset, maskscan, c};
    // Always keep p1 as provoking vertex for correct origin calculation in vertex shader.
    vs[0] = p1; vs[1] = p3; vs[2] = p2;
    vs[3] = p1; vs[4] = p3; vs[5] = p4;
}
