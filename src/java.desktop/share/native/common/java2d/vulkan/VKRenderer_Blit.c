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

#include "GraphicsPrimitiveMgr.h"
#include "VKRenderer_Internal.h"

#define SRCTYPE_BITS sun_java2d_vulkan_VKSwToSurfaceBlit_SRCTYPE_BITS

typedef struct {
    VkFormat        format;
    VKPackedSwizzle swizzle;
} BlitSrcType;

// See encodeSrcType() in VKBlitLoops.java
static BlitSrcType decodeSrcType(VKDevice* device, jshort srctype) {
    jshort type = (jshort) (srctype & sun_java2d_vulkan_VKSwToSurfaceBlit_SRCTYPE_MASK);
    const VKSampledSrcType* entry = &device->sampledSrcTypes.table[type];
    BlitSrcType result = { entry->format, 0 };
    switch (type) {
    case sun_java2d_vulkan_VKSwToSurfaceBlit_SRCTYPE_4BYTE: {
        uint32_t components[] = {
            ((uint32_t) srctype >>  SRCTYPE_BITS     ) & 0b11,
            ((uint32_t) srctype >> (SRCTYPE_BITS + 2)) & 0b11,
            ((uint32_t) srctype >> (SRCTYPE_BITS + 4)) & 0b11,
            ((uint32_t) srctype >> (SRCTYPE_BITS + 6)) & 0b11
        };
        result.swizzle = VK_PACK_SWIZZLE(
            entry->components[components[0]],
            entry->components[components[1]],
            entry->components[components[2]],
            components[3] == components[0] ? VK_COMPONENT_SWIZZLE_ONE : // Special case, a = r means no alpha.
            entry->components[components[3]]
        );
    } break;
    case sun_java2d_vulkan_VKSwToSurfaceBlit_SRCTYPE_3BYTE: {
        uint32_t components[] = {
            ((uint32_t) srctype >>  SRCTYPE_BITS     ) & 0b11,
            ((uint32_t) srctype >> (SRCTYPE_BITS + 2)) & 0b11,
            ((uint32_t) srctype >> (SRCTYPE_BITS + 4)) & 0b11
        };
        result.swizzle = VK_PACK_SWIZZLE(
            entry->components[components[0]],
            entry->components[components[1]],
            entry->components[components[2]],
            VK_COMPONENT_SWIZZLE_ONE
        );
    } break;
    default: {
        result.swizzle =
            VK_PACK_SWIZZLE(entry->components[0], entry->components[1], entry->components[2], entry->components[3]);
    } break;
    }
    return result;
}

static AlphaType getSrcAlphaType(jshort srctype) {
    return srctype & sun_java2d_vulkan_VKSwToSurfaceBlit_SRCTYPE_PRE_MULTIPLIED_ALPHA_BIT ?
        ALPHA_TYPE_PRE_MULTIPLIED : ALPHA_TYPE_STRAIGHT;
}

static void VKRenderer_FindStageBufferMemoryType(VKMemoryRequirements* requirements) {
    VKAllocator_FindMemoryType(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                               VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    VKAllocator_FindMemoryType(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, VK_ALL_MEMORY_PROPERTIES);
}

static void VKRenderer_DrawImage(VKImage* image, VkFormat format,
                                 VKPackedSwizzle swizzle, jint filter, VKSamplerWrap wrap,
                                 float sx1, float sy1, float sx2, float sy2,
                                 float dx1, float dy1, float dx2, float dy2) {
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

static void VKRenderer_DisposeTexture(VKDevice* device, void* data) {
    VKTexturePoolHandle_ReleaseTexture(data);
}

static void VKRenderer_DisposeBuffer(VKDevice* device, void* data) {
    device->vkDestroyBuffer(device->handle, (VkBuffer) data, NULL);
}

static void VKRenderer_DisposeMemory(VKDevice* device, void* data) {
    VKAllocator_Free(device->allocator, data);
}

static void VKRenderer_AddSurfaceDependency(VKSDOps* src, VKSDOps* dst) {
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

void VKRenderer_IsoBlit(VKSDOps* src, jint filter,
                        jint sx1, jint sy1, jint sx2, jint sy2,
                        jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2) {
    if (src == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKRenderer_IsoBlit: src is null");
        return;
    }
    VKRenderingContext* context = VKRenderer_GetContext();
    if (src == context->surface) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKRenderer_IsoBlit: surface blit into itself (%p)", src);
        return;
    }

    // Ensure all prior drawing to src surface have finished.
    VKRenderer_FlushRenderPass(src);

    VkBool32 srcOpaque = VKSD_IsOpaque(src);
    AlphaType alphaType = srcOpaque ? ALPHA_TYPE_STRAIGHT : ALPHA_TYPE_PRE_MULTIPLIED;
    static const VKPackedSwizzle OPAQUE_SWIZZLE = VK_PACK_SWIZZLE(VK_COMPONENT_SWIZZLE_IDENTITY,
                                                                  VK_COMPONENT_SWIZZLE_IDENTITY,
                                                                  VK_COMPONENT_SWIZZLE_IDENTITY,
                                                                  VK_COMPONENT_SWIZZLE_ONE);
    VKPackedSwizzle swizzle = srcOpaque ? OPAQUE_SWIZZLE : 0;

    if (!VKRenderer_Validate(SHADER_BLIT, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP, alphaType)) return;
    VKRenderer_DrawImage(src->image, src->image->format, swizzle, filter, SAMPLER_WRAP_BORDER,
                         (float)sx1, (float)sy1, (float)sx2, (float)sy2, (float)dx1, (float)dy1, (float)dx2, (float)dy2);
    VKRenderer_AddSurfaceDependency(src, context->surface);
}

void VKRenderer_Blit(JNIEnv *env, SurfaceDataOps* src, jshort srctype, jint filter,
                     jint sx1, jint sy1, jint sx2, jint sy2,
                     jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2) {
    if (src == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKRenderer_Blit: src is null");
        return;
    }
    VKRenderingContext* context = VKRenderer_GetContext();

    SurfaceDataRasInfo srcInfo = { .bounds = { sx1, sy1, sx2, sy2 } };
    // NOTE: This function will modify the contents of the bounds field to represent the maximum available raster data.
    if (src->Lock(env, src, &srcInfo, SD_LOCK_READ) != SD_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKRenderer_Blit: could not acquire lock");
        return;
    }
    if (srcInfo.bounds.x2 > srcInfo.bounds.x1 && srcInfo.bounds.y2 > srcInfo.bounds.y1) {
        src->GetRasInfo(env, src, &srcInfo);
        if (srcInfo.rasBase) {
            if (srcInfo.bounds.x1 != sx1) dx1 += (srcInfo.bounds.x1 - sx1) * (dx2 - dx1) / (sx2 - sx1);
            if (srcInfo.bounds.y1 != sy1) dy1 += (srcInfo.bounds.y1 - sy1) * (dy2 - dy1) / (sy2 - sy1);
            if (srcInfo.bounds.x2 != sx2) dx2 += (srcInfo.bounds.x2 - sx2) * (dx2 - dx1) / (sx2 - sx1);
            if (srcInfo.bounds.y2 != sy2) dy2 += (srcInfo.bounds.y2 - sy2) * (dy2 - dy1) / (sy2 - sy1);

            jint sw = (sx2 = srcInfo.bounds.x2) - (sx1 = srcInfo.bounds.x1);
            jint sh = (sy2 = srcInfo.bounds.y2) - (sy1 = srcInfo.bounds.y1);

            // Need to validate render pass early, as image may not yet be configured.
            AlphaType alphaType = getSrcAlphaType(srctype);
            if (!VKRenderer_Validate(SHADER_BLIT, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP, alphaType)) return;

            VKDevice* device = context->surface->device;
            BlitSrcType type = decodeSrcType(device, srctype);
            VKTexturePoolHandle* imageHandle = VKTexturePool_GetTexture(device->texturePool, sw, sh, type.format);
            VKImage* image = VKTexturePoolHandle_GetTexture(imageHandle);

            VkDeviceSize dataSize = sh * sw * srcInfo.pixelStride;
            VKBuffer buffer;
            VKMemory page = VKBuffer_CreateBuffers(device, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                                                   VKRenderer_FindStageBufferMemoryType,
                                                   dataSize, 0, &(uint32_t){1}, &buffer);

            char* raster = (char*) srcInfo.rasBase + sy1 * srcInfo.scanStride + sx1 * srcInfo.pixelStride;
            for (size_t row = 0; row < (size_t) sh; row++) {
                memcpy((char*) buffer.data + row * sw * srcInfo.pixelStride, raster, sw * srcInfo.pixelStride);
                raster += (uint32_t) srcInfo.scanStride;
            }

            {
                VkBufferMemoryBarrier bufferBarrier;
                VKBarrierBatch bufferBatch = {};
                VKBuffer_AddBarrier(&bufferBarrier, &bufferBatch, &buffer,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
                VkImageMemoryBarrier imageBarrier;
                VKBarrierBatch imageBatch = {};
                VKImage_AddBarrier(&imageBarrier, &imageBatch, image,
                                   VK_PIPELINE_STAGE_TRANSFER_BIT,
                                   VK_ACCESS_TRANSFER_WRITE_BIT,
                                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                VKRenderer_RecordBarriers(device->renderer, &bufferBarrier, &bufferBatch, &imageBarrier, &imageBatch);
            }
            VkBufferImageCopy region = (VkBufferImageCopy) {
                .bufferOffset = 0,
                .bufferRowLength = 0,
                .bufferImageHeight = 0,
                .imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .imageSubresource.mipLevel = 0,
                .imageSubresource.baseArrayLayer = 0,
                .imageSubresource.layerCount = 1,
                .imageOffset = {0, 0, 0},
                .imageExtent = {
                    .width = sw,
                    .height = sh,
                    .depth = 1
                }
            };
            device->vkCmdCopyBufferToImage(VKRenderer_Record(device->renderer), buffer.handle, image->handle,
                                                  VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
            {
                VkImageMemoryBarrier barrier;
                VKBarrierBatch barrierBatch = {};
                VKImage_AddBarrier(&barrier, &barrierBatch, image,
                                   VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                                   VK_ACCESS_SHADER_READ_BIT,
                                   VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                VKRenderer_RecordBarriers(device->renderer, NULL, NULL, &barrier, &barrierBatch);
            }

            VKRenderer_DrawImage(image, type.format, type.swizzle, filter, SAMPLER_WRAP_BORDER,
                         0, 0, (float)sw, (float)sh, (float)dx1, (float)dy1, (float)dx2, (float)dy2);

            ARRAY_PUSH_BACK(context->surface->renderPass->flushRanges) = buffer.range;
            ARRAY_PUSH_BACK(context->surface->renderPass->cleanupQueue) = (VKCleanupEntry) { VKRenderer_DisposeTexture, imageHandle };
            ARRAY_PUSH_BACK(context->surface->renderPass->cleanupQueue) = (VKCleanupEntry) { VKRenderer_DisposeBuffer, buffer.handle };
            ARRAY_PUSH_BACK(context->surface->renderPass->cleanupQueue) = (VKCleanupEntry) { VKRenderer_DisposeMemory, page };
        } else {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "VKRenderer_Blit: could not get raster info");
        }
        SurfaceData_InvokeRelease(env, src, &srcInfo);
    }
    SurfaceData_InvokeUnlock(env, src, &srcInfo);
}

/**
 * Specialized blit method for copying a native Vulkan "Surface" to a system
 * memory ("Sw") surface.
 */
void VKRenderer_SurfaceToSwBlit(JNIEnv* env, VKSDOps* src, SurfaceDataOps* dst,
                                jint srcx, jint srcy, jint dstx, jint dsty, jint width, jint height) {
    if (src == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKRenderer_SurfaceToSwBlit: src is null");
        return;
    }
    if (dst == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKRenderer_SurfaceToSwBlit: dst is null");
        return;
    }
    if (width <= 0 || height <= 0) {
        J2dTraceLn(J2D_TRACE_WARNING, "VKRenderer_SurfaceToSwBlit: dimensions are non-positive");
        return;
    }
    VKDevice* device = src->device;
    VKImage* image = src->image;
    if (image == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKRenderer_SurfaceToSwBlit: image is null");
        return;
    }

    SurfaceDataRasInfo srcInfo = { .bounds = {
        srcx,
        srcy,
        srcx + width,
        srcy + height
    }}, dstInfo = { .bounds = {
        dstx,
        dsty,
        dstx + width,
        dsty + height
    }};

    SurfaceData_IntersectBoundsXYXY(&srcInfo.bounds, 0, 0, (jint) image->extent.width, (jint) image->extent.height);
    SurfaceData_IntersectBlitBounds(&dstInfo.bounds, &srcInfo.bounds, srcx - dstx, srcy - dsty);

    // NOTE: This function will modify the contents of the bounds field to represent the maximum available raster data.
    if (dst->Lock(env, dst, &dstInfo, SD_LOCK_WRITE) != SD_SUCCESS) {
        J2dTraceLn(J2D_TRACE_WARNING, "VKRenderer_SurfaceToSwBlit: could not acquire lock");
        return;
    }
    if (dstInfo.bounds.x2 > dstInfo.bounds.x1 && dstInfo.bounds.y2 > dstInfo.bounds.y1) {
        dst->GetRasInfo(env, dst, &dstInfo);
        if (dstInfo.rasBase) {
            srcx = srcx - dstx + dstInfo.bounds.x1;
            srcy = srcy - dsty + dstInfo.bounds.y1;
            dstx = dstInfo.bounds.x1;
            dsty = dstInfo.bounds.y1;
            width = dstInfo.bounds.x2 - dstInfo.bounds.x1;
            height = dstInfo.bounds.y2 - dstInfo.bounds.y1;
            jsize bufferScan = width * dstInfo.pixelStride;
            jsize bufferSize = bufferScan * height;

            VKBuffer buffer;
            VKMemory page = VKBuffer_CreateBuffers(device, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VKRenderer_FindStageBufferMemoryType, bufferSize, 0, &(uint32_t){1}, &buffer);
            VK_RUNTIME_ASSERT(page != VK_NULL_HANDLE);

            // Ensure all prior drawing to src surface have finished.
            VKRenderer_FlushRenderPass(src);
            {
                VkImageMemoryBarrier barrier;
                VKBarrierBatch barrierBatch = {};
                VKImage_AddBarrier(&barrier, &barrierBatch, image,
                                   VK_PIPELINE_STAGE_TRANSFER_BIT,
                                   VK_ACCESS_TRANSFER_READ_BIT,
                                   VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                VKRenderer_RecordBarriers(device->renderer, NULL, NULL, &barrier, &barrierBatch);
            }
            VkBufferImageCopy region = {
                    .bufferOffset = 0,
                    .bufferRowLength = 0,
                    .bufferImageHeight = 0,
                    .imageSubresource= {
                            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                            .mipLevel = 0,
                            .baseArrayLayer = 0,
                            .layerCount = 1
                    },
                    .imageOffset = {srcx, srcy, 0},
                    .imageExtent = {width, height, 1}
            };
            device->vkCmdCopyImageToBuffer(VKRenderer_Record(device->renderer),
                image->handle, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer.handle, 1, &region);
            VKRenderer_Flush(device->renderer);
            VKRenderer_Sync(device->renderer);

            void* srcData = VKAllocator_Map(device->allocator, page);
            void* dstData = dstInfo.rasBase;
            dstData = PtrAddBytes(dstData, dstx * dstInfo.pixelStride);
            dstData = PtrPixelsRow(dstData, dsty, dstInfo.scanStride);
            if (bufferScan == dstInfo.scanStride) {
                // Tightly packed, copy in one go.
                memcpy(dstData, srcData, bufferSize);
            } else {
                // Sparse, copy by scanlines.
                for (jint i = 0; i < height; i++) {
                    memcpy(dstData, srcData, bufferScan);
                    srcData = PtrAddBytes(srcData, bufferScan);
                    dstData = PtrAddBytes(dstData, dstInfo.scanStride);
                }
            }
            VKAllocator_Unmap(device->allocator, page);
            device->vkDestroyBuffer(device->handle, buffer.handle, NULL);
            VKAllocator_Free(device->allocator, page);
        } else {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "VKRenderer_SurfaceToSwBlit: could not get raster info");
        }
        SurfaceData_InvokeRelease(env, dst, &dstInfo);
    }
    SurfaceData_InvokeUnlock(env, dst, &dstInfo);
}