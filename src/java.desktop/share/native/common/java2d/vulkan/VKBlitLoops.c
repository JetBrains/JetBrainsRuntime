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
#include "GraphicsPrimitiveMgr.h"
#include "VKBuffer.h"
#include "VKImage.h"
#include "VKDevice.h"
#include "VKRenderer.h"
#include "VKSurfaceData.h"
#include "VKUtil.h"

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

static void VKTexturePoolTexture_Dispose(VKDevice* device, void* ctx) {
    VKTexturePoolHandle* hnd = (VKTexturePoolHandle*) ctx;
    VKTexturePoolHandle_ReleaseTexture(hnd);
}

static void VKBlitSwToTextureViaPooledTexture(VKRenderingContext* context,
                                              VKSDOps *dstOps,
                                              const SurfaceDataRasInfo *srcInfo, jshort srctype, jint hint,
                                              int dx1, int dy1, int dx2, int dy2) {
    VKSDOps* surface = context->surface;
    VKDevice* device = surface->device;

    const int sw = srcInfo->bounds.x2 - srcInfo->bounds.x1;
    const int sh = srcInfo->bounds.y2 - srcInfo->bounds.y1;

    ARRAY(VKTxVertex) vertices = ARRAY_ALLOC(VKTxVertex, 4);
    /*
     *    (p1)---------(p2)
     *     |             |
     *     |             |
     *     |             |
     *    (p4)---------(p3)
     */

    BlitSrcType type = decodeSrcType(device, srctype);
    VKTexturePoolHandle* hnd = VKTexturePool_GetTexture(device->texturePool, sw, sh, type.format);
    double u = (double)sw;
    double v = (double)sh;

    ARRAY_PUSH_BACK(vertices) = (VKTxVertex) {dx1, dy1, 0.0f, 0.0f};
    ARRAY_PUSH_BACK(vertices) = (VKTxVertex) {dx2, dy1, u, 0.0f};
    ARRAY_PUSH_BACK(vertices) = (VKTxVertex) {dx1, dy2, 0.0f, v};
    ARRAY_PUSH_BACK(vertices) = (VKTxVertex) {dx2, dy2, u, v};

    VKBuffer* renderVertexBuffer = ARRAY_TO_VERTEX_BUF(device, vertices);
    ARRAY_FREE(vertices);

    J2dTraceLn(J2D_TRACE_VERBOSE, "replaceTextureRegion src (dw, dh) : [%d, %d] dest (dx1, dy1) =[%d, %d]",
               (dx2 - dx1), (dy2 - dy1), dx1, dy1);
    VKBuffer *buffer =
            VKBuffer_CreateFromRaster(device, (VKBuffer_RasterInfo){
                .data = srcInfo->rasBase,
                .x1 = srcInfo->bounds.x1,
                .y1 = srcInfo->bounds.y1,
                .w = sw,
                .h = sh,
                .pixelStride = srcInfo->pixelStride,
                .scanStride = srcInfo->scanStride
                }, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);

    {
        VkImageMemoryBarrier barrier;
        VKBarrierBatch barrierBatch = {};
        VKImage_AddBarrier(&barrier, &barrierBatch, VKTexturePoolHandle_GetTexture(hnd),
                           VK_PIPELINE_STAGE_TRANSFER_BIT,
                           VK_ACCESS_TRANSFER_WRITE_BIT,
                           VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        VKRenderer_RecordBarriers(device->renderer, NULL, NULL, &barrier, &barrierBatch);
    }
    VKImage_LoadBuffer(context->surface->device,
                       VKTexturePoolHandle_GetTexture(hnd), buffer, 0, 0, sw, sh);
    {
        VkImageMemoryBarrier barrier;
        VKBarrierBatch barrierBatch = {};
        VKImage_AddBarrier(&barrier, &barrierBatch, VKTexturePoolHandle_GetTexture(hnd),
                           VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                           VK_ACCESS_SHADER_READ_BIT,
                           VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        VKRenderer_RecordBarriers(device->renderer, NULL, NULL, &barrier, &barrierBatch);
    }

    VKImage* src = VKTexturePoolHandle_GetTexture(hnd);
    VkDescriptorSet srcDescriptorSet = VKImage_GetDescriptorSet(device, src, type.format, type.swizzle);
    VKRenderer_TextureRender(srcDescriptorSet, renderVertexBuffer->handle, 4, hint, SAMPLER_WRAP_BORDER);

    VKRenderer_FlushSurface(dstOps);
    VKRenderer_CleanupLater(device->renderer, VKTexturePoolTexture_Dispose, hnd);
    VKRenderer_CleanupLater(device->renderer, VKBuffer_Dispose, buffer);
}

static jboolean clipDestCoords(
        VKRenderingContext* context,
        jdouble *dx1, jdouble *dy1, jdouble *dx2, jdouble *dy2,
        jint *sx1, jint *sy1, jint *sx2, jint *sy2,
        jint destW, jint destH) {
    // Trim destination rect by clip-rect (or dest.bounds)
    const jint sw    = *sx2 - *sx1;
    const jint sh    = *sy2 - *sy1;
    const jdouble dw = *dx2 - *dx1;
    const jdouble dh = *dy2 - *dy1;
    VkRect2D* clipRect = &context->clipRect;
    jdouble dcx1 = 0;
    jdouble dcx2 = destW;
    jdouble dcy1 = 0;
    jdouble dcy2 = destH;
    if (clipRect->offset.x > dcx1)
        dcx1 = clipRect->offset.x;
    const int maxX = clipRect->offset.x + clipRect->extent.width;
    if (dcx2 > maxX)
        dcx2 = maxX;
    if (clipRect->offset.y > dcy1)
        dcy1 = clipRect->offset.y;
    const int maxY = clipRect->offset.y + clipRect->extent.height;
    if (dcy2 > maxY)
        dcy2 = maxY;

    if (dcx1 >= dcx2) {
        J2dTraceLn(J2D_TRACE_ERROR, "\tclipDestCoords: dcx1=%1.2f, dcx2=%1.2f", dcx1, dcx2);
        dcx1 = dcx2;
    }
    if (dcy1 >= dcy2) {
        J2dTraceLn(J2D_TRACE_ERROR, "\tclipDestCoords: dcy1=%1.2f, dcy2=%1.2f", dcy1, dcy2);
        dcy1 = dcy2;
    }
    if (*dx2 <= dcx1 || *dx1 >= dcx2 || *dy2 <= dcy1 || *dy1 >= dcy2) {
        J2dTraceLn(J2D_TRACE_INFO, "\tclipDestCoords: dest rect doesn't intersect clip area");
        J2dTraceLn(J2D_TRACE_INFO, "\tdx2=%1.4f <= dcx1=%1.4f || *dx1=%1.4f >= dcx2=%1.4f", *dx2, dcx1, *dx1, dcx2);
        J2dTraceLn(J2D_TRACE_INFO, "\t*dy2=%1.4f <= dcy1=%1.4f || *dy1=%1.4f >= dcy2=%1.4f", *dy2, dcy1, *dy1, dcy2);
        return JNI_FALSE;
    }
    if (*dx1 < dcx1) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "\t\tdx1=%1.2f, will be clipped to %1.2f | sx1+=%d", *dx1, dcx1, (jint)((dcx1 - *dx1) * (sw/dw)));
        *sx1 += (jint)((dcx1 - *dx1) * (sw/dw));
        *dx1 = dcx1;
    }
    if (*dx2 > dcx2) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "\t\tdx2=%1.2f, will be clipped to %1.2f | sx2-=%d", *dx2, dcx2, (jint)((*dx2 - dcx2) * (sw/dw)));
        *sx2 -= (jint)((*dx2 - dcx2) * (sw/dw));
        *dx2 = dcx2;
    }
    if (*dy1 < dcy1) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "\t\tdy1=%1.2f, will be clipped to %1.2f | sy1+=%d", *dy1, dcy1, (jint)((dcy1 - *dy1) * (sh/dh)));
        *sy1 += (jint)((dcy1 - *dy1) * (sh/dh));
        *dy1 = dcy1;
    }
    if (*dy2 > dcy2) {
        J2dTraceLn(J2D_TRACE_VERBOSE, "\t\tdy2=%1.2f, will be clipped to %1.2f | sy2-=%d", *dy2, dcy2, (jint)((*dy2 - dcy2) * (sh/dh)));
        *sy2 -= (jint)((*dy2 - dcy2) * (sh/dh));
        *dy2 = dcy2;
    }
    return JNI_TRUE;
}

void VKBlitLoops_IsoBlit(VKSDOps* srcOps, jint filter,
                         jint sx1, jint sy1, jint sx2, jint sy2,
                         jdouble dx1, jdouble dy1, jdouble dx2, jdouble dy2) {
    if (srcOps == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKBlitLoops_IsoBlit: srcOps is null");
        return;
    }

    VKRenderingContext* context = VKRenderer_GetContext();
    if (srcOps == context->surface) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKBlitLoops_IsoBlit: surface blit into itself (%p)", srcOps);
        return;
    }

    // Ensure all prior drawing to src surface have finished.
    VKRenderer_FlushRenderPass(srcOps);

    VkBool32 srcOpaque = VKSD_IsOpaque(srcOps);
    AlphaType alphaType = srcOpaque ? ALPHA_TYPE_STRAIGHT : ALPHA_TYPE_PRE_MULTIPLIED;
    static const VKPackedSwizzle OPAQUE_SWIZZLE = VK_PACK_SWIZZLE(VK_COMPONENT_SWIZZLE_IDENTITY,
                                                                  VK_COMPONENT_SWIZZLE_IDENTITY,
                                                                  VK_COMPONENT_SWIZZLE_IDENTITY,
                                                                  VK_COMPONENT_SWIZZLE_ONE);
    VKPackedSwizzle swizzle = srcOpaque ? OPAQUE_SWIZZLE : 0;

    VKRenderer_DrawImage(srcOps->image, alphaType, srcOps->image->format, swizzle, filter, SAMPLER_WRAP_BORDER,
                         (float)sx1, (float)sy1, (float)sx2, (float)sy2, (float)dx1, (float)dy1, (float)dx2, (float)dy2);
    VKRenderer_AddSurfaceDependency(srcOps, context->surface);
}

static void VKBlitLoops_FindStageBufferMemoryType(VKMemoryRequirements* requirements) {
    VKAllocator_FindMemoryType(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                               VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    VKAllocator_FindMemoryType(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, VK_ALL_MEMORY_PROPERTIES);
}

void VKBlitLoops_Blit(JNIEnv *env,
                      jlong pSrcOps, jboolean xform, jint hint,
                      jshort srctype,
                      jint sx1, jint sy1,
                      jint sx2, jint sy2,
                      jdouble dx1, jdouble dy1,
                      jdouble dx2, jdouble dy2)
{
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderQueue_flushBuffer: BLIT_Blit (%d %d %d %d) -> (%f %f %f %f) ",
                  sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2);
    J2dRlsTraceLn(J2D_TRACE_VERBOSE, "VKRenderQueue_flushBuffer: BLIT_Blit xform=%d srctype=%d", xform, srctype);

    SurfaceDataOps *srcOps = (SurfaceDataOps *)jlong_to_ptr(pSrcOps);


    if (srcOps == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKBlitLoops_Blit: srcOps(%p) is null",
                      srcOps);
        return;
    }

    if (!VKRenderer_Validate(SHADER_BLIT, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP, getSrcAlphaType(srctype))) {
        J2dTraceLn(J2D_TRACE_INFO, "VKBlitLoops_Blit: VKRenderer_Validate cannot validate renderer");
        return;
    }

    VKRenderingContext *context = VKRenderer_GetContext();
    VKSDOps *dstOps = context->surface;
    const jint sw    = sx2 - sx1;
    const jint sh    = sy2 - sy1;
    const jint dw = dx2 - dx1;
    const jint dh = dy2 - dy1;

    if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0) {
        J2dTraceLn(J2D_TRACE_ERROR, "MTLBlitLoops_Blit: invalid dimensions");
        return;
    }

    if (!xform) {
        clipDestCoords(context,
                &dx1, &dy1, &dx2, &dy2,
                &sx1, &sy1, &sx2, &sy2,
                dstOps->image->extent.width,dstOps->image->extent.height
        );
    }

    SurfaceDataRasInfo srcInfo;
    srcInfo.bounds.x1 = sx1;
    srcInfo.bounds.y1 = sy1;
    srcInfo.bounds.x2 = sx2;
    srcInfo.bounds.y2 = sy2;

    // NOTE: This function will modify the contents of the bounds field to represent the maximum available raster data.
    if (srcOps->Lock(env, srcOps, &srcInfo, SD_LOCK_READ) != SD_SUCCESS) {
        J2dRlsTraceLn(J2D_TRACE_WARNING, "VKBlitLoops_Blit: could not acquire lock");
        return;
    }

    if (srcInfo.bounds.x2 > srcInfo.bounds.x1 && srcInfo.bounds.y2 > srcInfo.bounds.y1) {
        jdouble dstX1 = dx1;
        jdouble dstY1 = dy1;
        jdouble dstX2 = dx2;
        jdouble dstY2 = dy2;

        srcOps->GetRasInfo(env, srcOps, &srcInfo);
        if (srcInfo.rasBase) {
            if (srcInfo.bounds.x1 != sx1) {
                const int dx = srcInfo.bounds.x1 - sx1;
                dstX1 += dx * (dw / sw);
            }
            if (srcInfo.bounds.y1 != sy1) {
                const int dy = srcInfo.bounds.y1 - sy1;
                dstY1 += dy * (dh / sh);
            }
            if (srcInfo.bounds.x2 != sx2) {
                const int dx = srcInfo.bounds.x2 - sx2;
                dstX2 += dx * (dw / sw);
            }
            if (srcInfo.bounds.y2 != sy2) {
                const int dy = srcInfo.bounds.y2 - sy2;
                dstY2 += dy * (dh / sh);
            }

            VKBlitSwToTextureViaPooledTexture(context, dstOps, &srcInfo, srctype, hint,
                                              (int)dstX1, (int)dstY1,
                                              (int)dstX2, (int)dstY2);
        }
        SurfaceData_InvokeRelease(env, srcOps, &srcInfo);
    }
    SurfaceData_InvokeUnlock(env, srcOps, &srcInfo);
}

/**
 * Specialized blit method for copying a native Vulkan "Surface" to a system
 * memory ("Sw") surface.
 */
void VKBlitLoops_SurfaceToSwBlit(JNIEnv* env, VKSDOps* src, SurfaceDataOps* dst,
                                 jint srcx, jint srcy, jint dstx, jint dsty, jint width, jint height) {
    if (src == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKBlitLoops_SurfaceToSwBlit: src is null");
        return;
    }
    if (dst == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKBlitLoops_SurfaceToSwBlit: dst is null");
        return;
    }
    if (width <= 0 || height <= 0) {
        J2dTraceLn(J2D_TRACE_WARNING, "VKBlitLoops_SurfaceToSwBlit: dimensions are non-positive");
        return;
    }
    VKDevice* device = src->device;
    VKImage* image = src->image;
    if (image == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "VKBlitLoops_SurfaceToSwBlit: image is null");
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
        J2dTraceLn(J2D_TRACE_WARNING, "VKBlitLoops_SurfaceToSwBlit: could not acquire lock");
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
                VKBlitLoops_FindStageBufferMemoryType, bufferSize, 0, &(uint32_t){1}, &buffer);
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
            J2dRlsTraceLn(J2D_TRACE_ERROR, "VKBlitLoops_SurfaceToSwBlit: could not get raster info");
        }
        SurfaceData_InvokeRelease(env, dst, &dstInfo);
    }
    SurfaceData_InvokeUnlock(env, dst, &dstInfo);
}