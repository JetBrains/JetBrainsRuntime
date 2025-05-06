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

#define VERTEX_BUFFER_SIZE (128 * 1024) // 128KiB - enough to draw 910 quads (6 verts) with VKColorVertex.
#define VERTEX_BUFFER_PAGE_SIZE (1 * 1024 * 1024) // 1MiB - fits 8 buffers.
static void VKRenderer_FindVertexBufferMemoryType(VKMemoryRequirements* requirements) {
    VKAllocator_FindMemoryType(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                               VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    VKAllocator_FindMemoryType(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, VK_ALL_MEMORY_PROPERTIES);
}
static VKBuffer VKRenderer_GetVertexBuffer(VKRenderer* renderer) {
    VKBuffer buffer;
    if (POOL_TAKE(renderer, renderer->vertexBufferPool, buffer)) return buffer;
    uint32_t bufferCount = VERTEX_BUFFER_PAGE_SIZE / VERTEX_BUFFER_SIZE;
    VKBuffer buffers[bufferCount];
    VKMemory page = VKBuffer_CreateBuffers(renderer->device, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                                           VKRenderer_FindVertexBufferMemoryType,
                                           VERTEX_BUFFER_SIZE, VERTEX_BUFFER_PAGE_SIZE, &bufferCount, buffers);
    VK_RUNTIME_ASSERT(page);
    ARRAY_PUSH_BACK(renderer->bufferMemoryPages) = page;
    for (uint32_t i = 1; i < bufferCount; i++) POOL_INSERT(renderer->vertexBufferPool, buffers[i]);
    return buffers[0];
}

uint32_t VKRenderer_AllocateVertices(uint32_t primitives, uint32_t vertices, size_t vertexSize, void** result) {
    assert(vertices > 0 && vertexSize > 0);
    assert(vertexSize * vertices <= VERTEX_BUFFER_SIZE);
    VKSDOps* surface = VKRenderer_GetContext()->surface;
    VKBufferWriting writing = VKRenderer_AllocateBufferData(
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
