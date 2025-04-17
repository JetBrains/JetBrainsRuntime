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

#include <string.h>
#include "jlong.h"
#include "SurfaceData.h"
#include "VKUtil.h"
#include "VKBlitLoops.h"
#include "VKSurfaceData.h"
#include "VKRenderer.h"


#include "Trace.h"
#include "VKImage.h"
#include "VKBuffer.h"
#include "CArrayUtil.h"

static void VKBlitSwToTextureViaPooledTexture(VKRenderingContext* context, VKImage* dest, const SurfaceDataRasInfo *srcInfo,
                     int dx1, int dy1, int dx2, int dy2) {
    VKSDOps* surface = context->surface;
    VKDevice* device = surface->device;

    const int sw = srcInfo->bounds.x2 - srcInfo->bounds.x1;
    const int sh = srcInfo->bounds.y2 - srcInfo->bounds.y1;
    const int dw = dx2 - dx1;
    const int dh = dy2 - dy1;

    if (dw < sw || dh < sh) {
        J2dTraceLn4(J2D_TRACE_ERROR, "VKBlitSwToTextureViaPooledTexture: dest size: (%d, %d) less than source size: (%d, %d)", dw, dh, sw, sh);
        return;
    }

    VKTxVertex* vertices = ARRAY_ALLOC(VKTxVertex, 4);
    /*
     *    (p1)---------(p2)
     *     |             |
     *     |             |
     *     |             |
     *    (p4)---------(p3)
     */

    VKTexturePoolHandle* hnd = VKTexturePool_GetTexture(device->texturePool, sw, sh, surface->image->format);
    double u = (double)sw / VKTexturePoolHandle_GetActualWidth(hnd);
    double v = (double)sh / VKTexturePoolHandle_GetActualHeight(hnd);

    ARRAY_PUSH_BACK(vertices, ((VKTxVertex) {dx1, dy1, 0.0f, 0.0f}));
    ARRAY_PUSH_BACK(vertices, ((VKTxVertex) {dx2, dy1, u, 0.0f}));
    ARRAY_PUSH_BACK(vertices, ((VKTxVertex) {dx1, dy2, 0.0f, v}));
    ARRAY_PUSH_BACK(vertices, ((VKTxVertex) {dx2, dy2, u, v}));

    VKBuffer* renderVertexBuffer = ARRAY_TO_VERTEX_BUF(device, vertices);
    ARRAY_FREE(vertices);

    const char *raster = srcInfo->rasBase;
    raster += (uint32_t)srcInfo->bounds.y1 * (uint32_t)srcInfo->scanStride + (uint32_t)srcInfo->bounds.x1 * (uint32_t)srcInfo->pixelStride;
    J2dTraceLn4(J2D_TRACE_VERBOSE, "replaceTextureRegion src (dw, dh) : [%d, %d] dest (dx1, dy1) =[%d, %d]",
                dw, dh, dx1, dy1);
    uint32_t dataSize = sw * sh * srcInfo->pixelStride;
    char* data = malloc(dataSize);
    // copy src pixels inside src bounds to buff
    for (int row = 0; row < sh; row++) {
        memcpy(data + (row * sw * srcInfo->pixelStride), raster, sw * srcInfo->pixelStride);
        raster += (uint32_t)srcInfo->scanStride;
    }
    VKBuffer *buffer = VKBuffer_CreateFromData(device, data, dataSize);
    free(data);

    VkCommandBuffer cb = VKRenderer_Record(device->renderer);
    {
        VkImageMemoryBarrier barrier;
        VKBarrierBatch barrierBatch = {};
        VKRenderer_AddImageBarrier(&barrier, &barrierBatch, ((VKImage *) VKTexturePoolHandle_GetTexture(hnd)),
                                   VK_PIPELINE_STAGE_TRANSFER_BIT,
                                   VK_ACCESS_TRANSFER_WRITE_BIT,
                                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

        if (barrierBatch.barrierCount > 0) {
            device->vkCmdPipelineBarrier(cb, barrierBatch.srcStages, barrierBatch.dstStages,
                                         0, 0, NULL,
                                         0, NULL,
                                         barrierBatch.barrierCount, &barrier);
        }
    }
    VKImage_LoadBuffer(context->surface->device,
                       VKTexturePoolHandle_GetTexture(hnd), buffer, 0, 0, sw, sh);
    {
        VkImageMemoryBarrier barrier;
        VKBarrierBatch barrierBatch = {};
        VKRenderer_AddImageBarrier(&barrier, &barrierBatch, ((VKImage *) VKTexturePoolHandle_GetTexture(hnd)),
                                   VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                                   VK_ACCESS_SHADER_READ_BIT,
                                   VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        if (barrierBatch.barrierCount > 0) {
            device->vkCmdPipelineBarrier(cb, barrierBatch.srcStages, barrierBatch.dstStages,
                                         0, 0, NULL,
                                         0, NULL,
                                         barrierBatch.barrierCount, &barrier);
        }
    }

    VKRenderer_TextureRender(context, dest, VKTexturePoolHandle_GetTexture(hnd),
                             renderVertexBuffer->handle, 4);

//  TODO: Not optimal but required for releasing raster buffer. Such Buffers should also be managed by special pools
//  TODO: Also, consider using VKRenderer_FlushRenderPass here to process pending command
    VKRenderer_Flush(device->renderer);
    VKRenderer_Sync(device->renderer);
//  TODO: Track lifecycle of the texture to avoid reuse of occupied texture
    VKTexturePoolHandle_ReleaseTexture(hnd);
    VKBuffer_Destroy(device, buffer);
//  TODO: Add proper sync for renderVertexBuffer
//    VKBuffer_Destroy(device, renderVertexBuffer);
}


void VKBlitLoops_IsoBlit(JNIEnv *env,
                         VKRenderingContext* context, jlong pSrcOps,
                         jboolean xform, jint hint,
                         jboolean texture,
                         jint sx1, jint sy1,
                         jint sx2, jint sy2,
                         jdouble dx1, jdouble dy1,
                         jdouble dx2, jdouble dy2)
{
    J2dRlsTraceLn8(J2D_TRACE_VERBOSE, "VKRenderQueue_flushBuffer: BLIT_IsoBlit (%d %d %d %d) -> (%f %f %f %f) ",
                                   sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2);
    J2dRlsTraceLn2(J2D_TRACE_VERBOSE, "VKRenderQueue_flushBuffer: BLIT_IsoBlit texture=%d xform=%d",
                                   texture, xform)
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
      J2dTraceLn2(J2D_TRACE_ERROR, "\tclipDestCoords: dcx1=%1.2f, dcx2=%1.2f", dcx1, dcx2);
      dcx1 = dcx2;
    }
    if (dcy1 >= dcy2) {
      J2dTraceLn2(J2D_TRACE_ERROR, "\tclipDestCoords: dcy1=%1.2f, dcy2=%1.2f", dcy1, dcy2);
      dcy1 = dcy2;
    }
  if (*dx2 <= dcx1 || *dx1 >= dcx2 || *dy2 <= dcy1 || *dy1 >= dcy2) {
    J2dTraceLn(J2D_TRACE_INFO, "\tclipDestCoords: dest rect doesn't intersect clip area");
    J2dTraceLn4(J2D_TRACE_INFO, "\tdx2=%1.4f <= dcx1=%1.4f || *dx1=%1.4f >= dcx2=%1.4f", *dx2, dcx1, *dx1, dcx2);
    J2dTraceLn4(J2D_TRACE_INFO, "\t*dy2=%1.4f <= dcy1=%1.4f || *dy1=%1.4f >= dcy2=%1.4f", *dy2, dcy1, *dy1, dcy2);
    return JNI_FALSE;
  }
  if (*dx1 < dcx1) {
    J2dTraceLn3(J2D_TRACE_VERBOSE, "\t\tdx1=%1.2f, will be clipped to %1.2f | sx1+=%d", *dx1, dcx1, (jint)((dcx1 - *dx1) * (sw/dw)));
    *sx1 += (jint)((dcx1 - *dx1) * (sw/dw));
    *dx1 = dcx1;
  }
  if (*dx2 > dcx2) {
    J2dTraceLn3(J2D_TRACE_VERBOSE, "\t\tdx2=%1.2f, will be clipped to %1.2f | sx2-=%d", *dx2, dcx2, (jint)((*dx2 - dcx2) * (sw/dw)));
    *sx2 -= (jint)((*dx2 - dcx2) * (sw/dw));
    *dx2 = dcx2;
  }
  if (*dy1 < dcy1) {
    J2dTraceLn3(J2D_TRACE_VERBOSE, "\t\tdy1=%1.2f, will be clipped to %1.2f | sy1+=%d", *dy1, dcy1, (jint)((dcy1 - *dy1) * (sh/dh)));
    *sy1 += (jint)((dcy1 - *dy1) * (sh/dh));
    *dy1 = dcy1;
  }
  if (*dy2 > dcy2) {
    J2dTraceLn3(J2D_TRACE_VERBOSE, "\t\tdy2=%1.2f, will be clipped to %1.2f | sy2-=%d", *dy2, dcy2, (jint)((*dy2 - dcy2) * (sh/dh)));
    *sy2 -= (jint)((*dy2 - dcy2) * (sh/dh));
    *dy2 = dcy2;
  }
  return JNI_TRUE;
}


void VKBlitLoops_Blit(JNIEnv *env,
                      VKRenderingContext* context, jlong pSrcOps,
                      jboolean xform, jint hint,
                      jint srctype, jboolean texture,
                      jint sx1, jint sy1,
                      jint sx2, jint sy2,
                      jdouble dx1, jdouble dy1,
                      jdouble dx2, jdouble dy2)
{
    J2dRlsTraceLn8(J2D_TRACE_VERBOSE, "VKRenderQueue_flushBuffer: BLIT_Blit (%d %d %d %d) -> (%f %f %f %f) ",
                                   sx1, sy1, sx2, sy2, dx1, dy1, dx2, dy2)
    J2dRlsTraceLn3(J2D_TRACE_VERBOSE, "VKRenderQueue_flushBuffer: BLIT_Blit texture=%d xform=%d srctype=%d",
                                   texture, xform, srctype)

    SurfaceDataOps *srcOps = (SurfaceDataOps *)jlong_to_ptr(pSrcOps);


    if (context == NULL || srcOps == NULL) {
        J2dRlsTraceLn2(J2D_TRACE_ERROR, "VKBlitLoops_Blit: context(%p) or srcOps(%p) is null",
                       context, srcOps)
        return;
    }


    if (!VKRenderer_Validate(context, PIPELINE_BLIT)) {
        J2dTraceLn(J2D_TRACE_INFO, "VKBlitLoops_Blit: VKRenderer_Validate cannot validate renderer");
        return;
    }
    VKSDOps *dstOps = context->surface;
    VKImage *dest = context->surface->image;
//    if (srctype < 0 || srctype >= sizeof(RasterFormatInfos)/ sizeof(MTLRasterFormatInfo)) {
//        J2dTraceLn1(J2D_TRACE_ERROR, "MTLBlitLoops_Blit: source pixel format %d isn't supported", srctype);
//        return;
//    }
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

//            MTLRasterFormatInfo rfi = RasterFormatInfos[srctype];
//
//            if (texture) {
//                replaceTextureRegion(mtlc, dest, &srcInfo, &rfi, (int) dx1, (int) dy1, (int) dx2, (int) dy2);
//            } else {
                VKBlitSwToTextureViaPooledTexture(context, dest, &srcInfo,
                                                  (int)dstX1, (int)dstY1, (int)dstX2, (int)dstY2);
//            }
        }
        SurfaceData_InvokeRelease(env, srcOps, &srcInfo);
    }
    SurfaceData_InvokeUnlock(env, srcOps, &srcInfo);
}